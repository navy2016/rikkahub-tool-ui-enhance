package me.rerere.rikkahub.data.container

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.utils.TerminalEmulator

/** All dimensions refer to the grid below the fixed status-bar strip. */
internal data class TerminalViewportMetrics(
    val maxScrollPx: Int,
    val viewportHeightPx: Int,
    val cellHeightPx: Int,
    val tailPaddingPx: Int,
    val usesTuiViewport: Boolean = false,
    val preservePhysicalGrid: Boolean = false,
    val imeVisible: Boolean = false,
    val avoidIme: Boolean = false,
)

internal data class TerminalViewportGesture(val id: Long, val origin: ViewportScrollOrigin)

internal data class TerminalViewportScrollEffect(
    val id: Long,
    val targetScrollPx: Int,
    val origin: ViewportScrollOrigin,
    val animated: Boolean = false,
    /** Non-null only for an explicitly attached measured/item backend; pixels are unused there. */
    val itemTarget: TerminalItemScrollTarget? = null,
)

internal data class TerminalViewportControllerState(
    val mode: ViewportMode,
    val anchor: ViewportAnchor? = null,
    /** Keep the original capture scale so repeated font changes don't accumulate rounding drift. */
    val anchorCellHeightPx: Int = 0,
    val initialized: Boolean = false,
    val gesture: TerminalViewportGesture? = null,
    val scrollEffect: TerminalViewportScrollEffect? = null,
    val anchorRowHeightPx: Int = 0,
) {
    val autoScroll: Boolean get() = mode != ViewportMode.LOCKED
}

/**
 * Session-local, UI-thread-confined owner of terminal viewport intent. This class never scrolls or
 * starts coroutines: inputs synchronously publish state and at most one outstanding scroll effect.
 * Only the UI's effect collector touches ScrollState. Programmatic scroll feedback cannot change
 * follow intent, and token checks prevent an old animation/fling from completing a newer operation.
 */
internal class TerminalViewportController(
    restored: TerminalViewportState? = null,
    followInitially: Boolean = true,
) {
    private val follow = restored?.autoScroll ?: followInitially
    private val mutableState = MutableStateFlow(
        TerminalViewportControllerState(
            mode = if (follow) {
                restored?.viewportMode?.takeUnless { it == ViewportMode.LOCKED } ?: ViewportMode.TAIL
            } else ViewportMode.LOCKED,
            anchor = if (follow) null else restored?.let { saved ->
                saved.anchorLineId?.let { id ->
                    ViewportAnchor(
                        lineId = id,
                        clippedTopPx = saved.anchorClippedTopPx,
                        screenGeneration = saved.anchorScreenGeneration,
                        historyGeneration = saved.anchorHistoryGeneration,
                    )
                }
            },
            anchorCellHeightPx = restored?.anchorCellHeightPx ?: 0,
            anchorRowHeightPx = restored?.anchorRowHeightPx ?: 0,
        )
    )
    val state: StateFlow<TerminalViewportControllerState> = mutableState.asStateFlow()

    private var nextOperationId = 0L
    private var frame: TerminalEmulator.RenderFrame? = null
    private var metrics: TerminalViewportMetrics? = null
    private var lastValidMetrics: TerminalViewportMetrics? = null
    private var renderedRows = 0
    private var scrollPx = 0
    private var legacyOffsetPx = restored?.takeUnless { it.autoScroll }?.verticalOffsetPx
    private var imeAnchor: ImeAnchor? = null
    private var measuredAnchorResolution: MeasuredAnchorResolution? = null
    private val anchorLookup = TerminalViewportLineLookup()
    private var usesItemViewport = false
    private var itemViewport: TerminalItemViewport? = null
    private var pendingItemTop = false
    private var captureItemLock = false
    private var eagerGeometry: TerminalEagerViewportGeometry? = null
    private var requireEagerGeometry = false
    private var pendingItemHandoff: TerminalItemScrollTarget.Anchor? = null

    private data class MeasuredAnchorResolution(
        val frameRevision: Long,
        val anchor: TerminalViewportItemAnchor,
        val scrollPx: Int,
    )

    private data class ImeAnchor(
        val offsetPx: Int,
        val screenGeneration: Long,
        val bottomScreenRow: Int?,
    )

    fun updateViewport(
        frame: TerminalEmulator.RenderFrame,
        renderedRows: Int,
        metrics: TerminalViewportMetrics,
        currentScrollPx: Int,
        measuredGeometry: TerminalEagerViewportGeometry? = null,
        requireMeasuredGeometry: Boolean = false,
    ) {
        if (usesItemViewport) pendingItemHandoff = itemViewport?.capture()
        eagerGeometry = measuredGeometry?.takeIf { it.frame === frame }
        requireEagerGeometry = requireMeasuredGeometry
        if (usesItemViewport) {
            usesItemViewport = false
            itemViewport = null
            // Keep an explicit Top intent across a cancelled lazy animation and a delayed eager
            // measurement. The dormant ScrollState position is not the user's requested target.
            captureItemLock = false
            mutableState.value = state.value.copy(gesture = null, scrollEffect = null)
        }
        val previousMetrics = lastValidMetrics
        this.frame = frame
        this.renderedRows = renderedRows
        this.metrics = metrics
        scrollPx = currentScrollPx.coerceAtLeast(0)
        if (!ready()) {
            mutableState.value = state.value.copy(scrollEffect = null)
            return
        }
        lastValidMetrics = metrics

        val activeBottomRow = activeBottomRow(frame, metrics)
        if (!metrics.imeVisible) {
            imeAnchor = null
        } else if ((previousMetrics?.imeVisible != true || pendingItemHandoff != null) && state.value.autoScroll) {
            // Mode is authoritative. Do not infer user intent from an IME-clamped pixel position.
            val handoffPx = pendingItemHandoff?.let {
                eagerGeometry?.target(it.anchor, it.capturedRowHeightPx, anchorLookup)
            }
            imeAnchor = ImeAnchor(handoffPx ?: scrollPx, frame.screenGeneration, activeBottomRow)
        } else {
            imeAnchor = imeAnchor?.let { previous ->
                if (previous.screenGeneration != frame.screenGeneration) {
                    ImeAnchor(scrollPx, frame.screenGeneration, activeBottomRow)
                } else previous.copy(bottomScreenRow = listOfNotNull(
                    previous.bottomScreenRow, activeBottomRow,
                ).maxOrNull())
            }
        }

        pendingItemHandoff = null
        val initial = !state.value.initialized
        if (initial) {
            if (!state.value.autoScroll && state.value.anchor == null) {
                capture(legacyOffsetPx ?: scrollPx)
            }
            legacyOffsetPx = null
            mutableState.value = state.value.copy(initialized = true)
        }
        val origin = when {
            initial -> ViewportScrollOrigin.RESTORE
            previousMetrics?.imeVisible != metrics.imeVisible || metrics.imeVisible -> ViewportScrollOrigin.IME
            previousMetrics?.cellHeightPx != metrics.cellHeightPx ||
                previousMetrics?.viewportHeightPx != metrics.viewportHeightPx -> ViewportScrollOrigin.RESIZE
            else -> ViewportScrollOrigin.REDUCER
        }
        reconcile(origin)
    }

    /**
     * Opt-in item-native geometry; current production eager callers keep updateViewport unchanged.
     * Null/stale geometry pauses reconciliation. It must never revert to nominal-row arithmetic.
     * The completed layout's frame is compared by identity, not revision alone (sessions can reuse it).
     */
    fun updateItemViewport(frame: TerminalEmulator.RenderFrame, observation: TerminalItemViewport?) {
        val previous = itemViewport
        eagerGeometry = null
        requireEagerGeometry = false
        pendingItemHandoff = null
        if (!usesItemViewport) {
            mutableState.value = state.value.copy(gesture = null, scrollEffect = null)
            imeAnchor = null
            measuredAnchorResolution = null
        }
        usesItemViewport = true
        this.frame = frame
        renderedRows = frame.rows.size
        itemViewport = observation?.takeIf { it.frame === frame && it.ready && !frame.isAlternateScreen }
        if (itemViewport == null) {
            // Do not disturb an explicit in-flight jump for each output frame. Its executor must
            // re-read valid geometry before completion; the next completed layout reconciles it.
            if (state.value.scrollEffect?.animated != true) mutableState.value = state.value.copy(scrollEffect = null)
            return
        }
        val initial = !state.value.initialized
        if (initial) {
            if (!state.value.autoScroll && state.value.anchor == null) capture(0)
            legacyOffsetPx = null // Item backends must restore semantic IDs, never interpret legacy pixels.
            mutableState.value = state.value.copy(initialized = true)
        }
        val origin = when {
            initial -> ViewportScrollOrigin.RESTORE
            previous?.layoutKey != itemViewport?.layoutKey ||
                previous?.viewportHeightPx != itemViewport?.viewportHeightPx -> ViewportScrollOrigin.RESIZE
            else -> ViewportScrollOrigin.REDUCER
        }
        reconcile(origin)
    }

    /** Live layout refresh for consumed input/completion, without publishing a new scroll effect. */
    fun observeItemViewport(observation: TerminalItemViewport?) {
        if (usesItemViewport) itemViewport = observation?.takeIf { it.frame === frame && it.ready }
    }

    /** Refresh a just-consumed user layout without reconciling programmatic intent. */
    fun observeItemViewport(frame: TerminalEmulator.RenderFrame, observation: TerminalItemViewport?) {
        if (!usesItemViewport) return
        this.frame = frame
        renderedRows = frame.rows.size
        observeItemViewport(observation)
    }

    fun observeEagerGeometry(frame: TerminalEmulator.RenderFrame, geometry: TerminalEagerViewportGeometry?) {
        if (usesItemViewport || !requireEagerGeometry) return
        this.frame = frame
        renderedRows = frame.rows.size
        eagerGeometry = geometry?.takeIf { it.frame === frame }
    }

    /** Switching a renderer invalidates the old writer token, never changes follow/lock intent. */
    fun pauseScrollEffects() {
        mutableState.value = state.value.copy(gesture = null, scrollEffect = null)
    }

    /** Explicit semantic restore, including a renderer handoff; never consumes a guessed offset. */
    fun restoreItemAnchor(anchor: ViewportAnchor, rowHeightPx: Int) {
        require(rowHeightPx >= 0)
        pendingItemTop = false
        captureItemLock = false
        mutableState.value = state.value.copy(mode = ViewportMode.LOCKED, anchor = anchor,
            anchorRowHeightPx = rowHeightPx, gesture = null, scrollEffect = null)
        if (usesItemViewport) reconcile(ViewportScrollOrigin.RESTORE)
    }

    /**
     * Supplies an exact target from a measured LazyList layout. The line ID and frame revision are
     * part of the contract so a delayed layout observation cannot drive a newer terminal frame.
     * Eager/TUI callers never set it and retain the existing fixed-grid reducer behavior.
     */
    fun setMeasuredAnchorTarget(
        frameRevision: Long,
        anchor: TerminalViewportItemAnchor?,
        targetScrollPx: Int?,
    ) {
        measuredAnchorResolution = if (anchor != null && targetScrollPx != null) {
            MeasuredAnchorResolution(frameRevision, anchor, targetScrollPx.coerceAtLeast(0))
        } else null
    }

    /** Starts or continues real user input; called before the scrollable consumes its delta. */
    fun beginUserScroll(origin: ViewportScrollOrigin, currentScrollPx: Int): Long {
        require(origin.isUserInput)
        scrollPx = currentScrollPx.coerceAtLeast(0)
        imeAnchor = null
        measuredAnchorResolution = null
        pendingItemTop = false
        captureItemLock = false
        val gesture = state.value.gesture?.takeIf { it.origin == origin }
            ?: TerminalViewportGesture(++nextOperationId, origin)
        mutableState.value = state.value.copy(gesture = gesture, scrollEffect = null)
        return gesture.id
    }

    /** Called only for consumed user deltas, never for layout clamps or programmatic feedback. */
    fun userScrolled(gestureId: Long, currentScrollPx: Int) {
        if (state.value.gesture?.id != gestureId) return
        scrollPx = currentScrollPx.coerceAtLeast(0)
        if (!ready()) {
            if (usesItemViewport) {
                // A consumed user delta is authoritative even between frame publication and layout.
                captureItemLock = true
                mutableState.value = state.value.copy(mode = ViewportMode.LOCKED, anchor = null)
            }
            return
        }
        if (isNearBottom(scrollPx)) {
            mutableState.value = state.value.copy(mode = followMode(), anchor = null, anchorCellHeightPx = 0)
        } else {
            capture(scrollPx)
        }
    }

    /** The final consumed delta has already captured the anchor, including sub-row clipping. */
    fun endUserScroll(gestureId: Long) {
        if (state.value.gesture?.id != gestureId) return
        mutableState.value = state.value.copy(gesture = null)
        reconcile(ViewportScrollOrigin.REDUCER)
    }

    fun setFollow(enabled: Boolean, currentScrollPx: Int) {
        scrollPx = currentScrollPx.coerceAtLeast(0)
        imeAnchor = null
        measuredAnchorResolution = null
        pendingItemTop = false
        captureItemLock = false
        mutableState.value = state.value.copy(gesture = null, scrollEffect = null)
        if (enabled) {
            mutableState.value = state.value.copy(mode = followMode(), anchor = null, anchorCellHeightPx = 0)
        } else {
            if (usesItemViewport && !ready()) {
                captureItemLock = true
                mutableState.value = state.value.copy(mode = ViewportMode.LOCKED, anchor = null)
                return
            }
            capture(scrollPx)
        }
        reconcile(ViewportScrollOrigin.JUMP)
    }

    fun jumpToBottom(currentScrollPx: Int) = jump(toBottom = true, currentScrollPx = currentScrollPx)

    fun jumpToTop(currentScrollPx: Int) = jump(toBottom = false, currentScrollPx = currentScrollPx)

    private fun jump(toBottom: Boolean, currentScrollPx: Int) {
        scrollPx = currentScrollPx.coerceAtLeast(0)
        imeAnchor = null
        measuredAnchorResolution = null
        pendingItemTop = false
        captureItemLock = false
        mutableState.value = state.value.copy(gesture = null, scrollEffect = null)
        if (usesItemViewport) {
            pendingItemTop = !toBottom
            mutableState.value = state.value.copy(mode = if (toBottom) ViewportMode.TAIL else ViewportMode.LOCKED,
                anchor = null, anchorCellHeightPx = 0, anchorRowHeightPx = 0)
            reconcile(ViewportScrollOrigin.JUMP, animated = true)
            return
        }
        if (toBottom) {
            mutableState.value = state.value.copy(mode = followMode(), anchor = null, anchorCellHeightPx = 0)
        } else capture(0)
        reconcile(ViewportScrollOrigin.JUMP, animated = true)
    }

    fun isCurrent(effect: TerminalViewportScrollEffect): Boolean =
        state.value.gesture == null && state.value.scrollEffect?.id == effect.id

    fun scrollFinished(effectId: Long, currentScrollPx: Int, completed: Boolean) {
        if (state.value.scrollEffect?.id != effectId) return
        if (completed && pendingItemTop && state.value.scrollEffect?.itemTarget == TerminalItemScrollTarget.Top &&
            itemViewport?.atTop == true
        ) {
            pendingItemTop = false
            capture(0)
        }
        scrollPx = currentScrollPx.coerceAtLeast(0)
        mutableState.value = state.value.copy(scrollEffect = null)
        // Frame/geometry changes during an explicit jump are deferred, not allowed to cancel it.
        if (completed) reconcile(ViewportScrollOrigin.REDUCER)
    }

    fun isNearBottom(currentScrollPx: Int): Boolean {
        if (usesItemViewport) return itemViewport?.nearFollow() == true
        val metrics = metrics ?: return false
        val frame = frame ?: return false
        return currentScrollPx >= bottomTarget(frame, metrics) - metrics.cellHeightPx * 2
    }

    fun isNearTop(currentScrollPx: Int): Boolean = if (usesItemViewport) itemViewport?.nearTop() == true
        else currentScrollPx <= TERMINAL_EDGE_THRESHOLD_PX

    private fun followMode(): ViewportMode =
        if (!usesItemViewport && metrics?.usesTuiViewport == true) ViewportMode.SCREEN else ViewportMode.TAIL

    private fun ready(): Boolean {
        if (usesItemViewport) return itemViewport?.let { it.frame === frame && it.ready } == true
        val frame = frame ?: return false
        val metrics = metrics ?: return false
        if (requireEagerGeometry && eagerGeometry?.frame !== frame) return false
        return metrics.cellHeightPx > 0 && metrics.viewportHeightPx > 0 &&
            metrics.maxScrollPx >= 0 && metrics.maxScrollPx != Int.MAX_VALUE && renderedRows > 0 &&
            renderedRows == frame.rows.size && frame.historyCount >= 0 &&
            frame.historyLineIds.size == frame.historyCount &&
            frame.screenLineIds.size == renderedRows - frame.historyCount
    }

    private fun capture(atScrollPx: Int) {
        if (usesItemViewport) {
            val observation = itemViewport ?: return
            val captured = observation.capture() ?: return
            legacyOffsetPx = null
            captureItemLock = false
            mutableState.value = state.value.copy(mode = ViewportMode.LOCKED, anchor = captured.anchor,
                anchorCellHeightPx = observation.cellHeightPx, anchorRowHeightPx = captured.capturedRowHeightPx)
            return
        }
        val frame = frame
        val metrics = metrics
        val measured = eagerGeometry?.takeIf { ready() && it.frame === frame }?.capture(atScrollPx)
        if (measured != null) {
            legacyOffsetPx = null
            mutableState.value = state.value.copy(mode = ViewportMode.LOCKED, anchor = measured.anchor,
                anchorCellHeightPx = metrics?.cellHeightPx ?: 0, anchorRowHeightPx = measured.capturedRowHeightPx)
            return
        }
        val anchor = if (ready() && frame != null && metrics != null) {
            captureViewportAnchor(frame, renderedRows, atScrollPx.coerceIn(0, metrics.maxScrollPx), metrics.cellHeightPx)
        } else null
        legacyOffsetPx = if (anchor == null) atScrollPx else null
        mutableState.value = state.value.copy(
            mode = ViewportMode.LOCKED,
            anchor = anchor,
            anchorCellHeightPx = metrics?.cellHeightPx ?: 0,
            anchorRowHeightPx = 0,
        )
    }

    private fun reconcile(origin: ViewportScrollOrigin, animated: Boolean = false) {
        if (!ready() || !state.value.initialized || state.value.gesture != null) return
        if (state.value.scrollEffect?.animated == true) return
        if (usesItemViewport) {
            reconcileItems(origin, animated)
            return
        }
        val frame = requireNotNull(frame)
        val metrics = requireNotNull(metrics)
        if (pendingItemTop) {
            capture(0)
            pendingItemTop = false
        }
        if (!state.value.autoScroll && state.value.anchor == null) capture(legacyOffsetPx ?: scrollPx)
        if (eagerGeometry != null && state.value.anchor != null) {
            val resolved = resolveTerminalItemAnchor(frame, requireNotNull(state.value.anchor), anchorLookup)
            mutableState.value = if (resolved == null) state.value.copy(mode = followMode(), anchor = null) else {
                // Older saved default anchors have no measured scale. Bind once to the resolved
                // row's first actual height, just as the item backend does; do not rebase on every
                // font change or overwrite a known capture height during a renderer handoff.
                val height = if (state.value.anchorRowHeightPx <= 0) {
                    val index = anchorLookup.find(frame, renderedRows, resolved.lineId)
                    index.takeIf { it >= 0 }?.let { requireNotNull(eagerGeometry).height(it) }
                } else null
                state.value.copy(
                    anchor = if (height != null) resolved.copy(clippedTopPx = resolved.clippedTopPx.coerceIn(0, height - 1))
                        else resolved,
                    anchorRowHeightPx = height ?: state.value.anchorRowHeightPx,
                    anchorCellHeightPx = if (height != null) metrics.cellHeightPx else state.value.anchorCellHeightPx,
                )
            }
        }
        val anchor = state.value.anchor
        val captureHeight = state.value.anchorCellHeightPx.takeIf { it > 0 } ?: metrics.cellHeightPx
        val clippedTop = (((anchor?.clippedTopPx ?: 0).toLong() * metrics.cellHeightPx + captureHeight / 2) /
            captureHeight).toInt().coerceIn(0, metrics.cellHeightPx - 1)
        val bottomTarget = bottomTarget(frame, metrics)
        val followTarget = if (metrics.imeVisible && !metrics.avoidIme) {
            imeAnchor?.offsetPx?.coerceIn(0, metrics.maxScrollPx) ?: bottomTarget
        } else bottomTarget
        val output = reduceViewport(
            ViewportInput(
                mode = if (state.value.autoScroll) followMode() else ViewportMode.LOCKED,
                anchorLineId = anchor?.lineId,
                anchorClippedTopPx = clippedTop,
                anchorScreenGeneration = anchor?.screenGeneration,
                anchorHistoryGeneration = anchor?.historyGeneration,
                currentScrollPx = scrollPx,
                maxScrollPx = metrics.maxScrollPx,
                viewportHeightPx = metrics.viewportHeightPx,
                cellHeightPx = metrics.cellHeightPx,
                tailScrollPx = followTarget,
                screenScrollPx = followTarget,
                fallbackMode = followMode(),
                measuredAnchorScrollPx = anchor?.let {
                    eagerGeometry?.target(it, state.value.anchorRowHeightPx, anchorLookup)
                } ?: measuredAnchorResolution?.takeIf { measured ->
                    measured.frameRevision == frame.revision &&
                        measured.anchor.lineId == anchor?.lineId &&
                        measured.anchor.clippedTopPx == anchor?.clippedTopPx &&
                        measured.anchor.screenGeneration == anchor?.screenGeneration &&
                        measured.anchor.historyGeneration == anchor?.historyGeneration
                }?.scrollPx,
            ), frame, renderedRows, anchorLookup,
        )
        val nextAnchor = output.anchorLineId?.let { id ->
            ViewportAnchor(
                lineId = id,
                clippedTopPx = anchor?.clippedTopPx ?: 0,
                screenGeneration = output.anchorScreenGeneration,
                historyGeneration = output.anchorHistoryGeneration,
            )
        }
        mutableState.value = state.value.copy(mode = output.mode, anchor = nextAnchor)
        if (output.targetScrollPx == scrollPx) {
            mutableState.value = state.value.copy(scrollEffect = null)
        } else if (state.value.scrollEffect?.targetScrollPx != output.targetScrollPx) {
            mutableState.value = state.value.copy(scrollEffect = TerminalViewportScrollEffect(
                id = ++nextOperationId,
                targetScrollPx = output.targetScrollPx,
                origin = origin,
                animated = animated,
            ))
        }
    }

    private fun reconcileItems(origin: ViewportScrollOrigin, animated: Boolean) {
        val observation = itemViewport ?: return
        if (pendingItemTop) {
            if (observation.atTop) {
                pendingItemTop = false
                capture(0)
            } else {
                publishItemTarget(TerminalItemScrollTarget.Top, origin, animated)
                return
            }
        }
        if (captureItemLock) capture(0)
        if (!state.value.autoScroll && state.value.anchor == null) capture(0)
        val previousAnchor = state.value.anchor
        val resolved = previousAnchor?.let { resolveTerminalItemAnchor(observation.frame, it, anchorLookup) }
        val target = if (state.value.autoScroll || resolved == null) {
            mutableState.value = state.value.copy(
                mode = ViewportMode.TAIL, anchor = null, anchorCellHeightPx = 0, anchorRowHeightPx = 0,
            )
            TerminalItemScrollTarget.Follow
        } else {
            // Legacy/restored IDs may have no measured capture scale. Bind it exactly once when
            // the requested row actually appears in a valid layout, not to the currently visible
            // unrelated row or a nominal cell. Otherwise every later font change treats the old
            // pixel clip as if it were captured at the NEW height and silently loses scaling.
            val measured = if (state.value.anchorRowHeightPx <= 0) observation.rows.firstOrNull {
                observation.lineId(it.index) == resolved.lineId
            } else null
            val anchor = if (measured != null) resolved.copy(
                clippedTopPx = resolved.clippedTopPx.coerceIn(0, measured.heightPx - 1),
            ) else resolved
            mutableState.value = state.value.copy(mode = ViewportMode.LOCKED, anchor = anchor,
                anchorRowHeightPx = measured?.heightPx ?: state.value.anchorRowHeightPx,
                anchorCellHeightPx = if (measured != null) observation.cellHeightPx else state.value.anchorCellHeightPx)
            TerminalItemScrollTarget.Anchor(anchor, state.value.anchorRowHeightPx)
        }
        publishItemTarget(target, origin, animated)
    }

    private fun publishItemTarget(target: TerminalItemScrollTarget, origin: ViewportScrollOrigin, animated: Boolean) {
        val observation = itemViewport ?: return
        if (observation.isSatisfied(target)) {
            mutableState.value = state.value.copy(scrollEffect = null)
        } else if (state.value.scrollEffect?.itemTarget != target) {
            mutableState.value = state.value.copy(scrollEffect = TerminalViewportScrollEffect(
                id = ++nextOperationId,
                targetScrollPx = 0, // No global pixel coordinate exists for an unmeasured prefix.
                origin = origin,
                animated = animated,
                itemTarget = target,
            ))
        }
    }

    private fun activeBottomRow(frame: TerminalEmulator.RenderFrame, metrics: TerminalViewportMetrics): Int? =
        if (metrics.preservePhysicalGrid) frame.screenLineIds.lastIndex.takeIf { it >= 0 } else {
            terminalEffectiveScreenBottomRow(frame.screenContentBounds, frame.cursorRow, frame.cursorVisible)
        }

    private fun bottomTarget(frame: TerminalEmulator.RenderFrame, metrics: TerminalViewportMetrics): Int {
        val geometry = eagerGeometry?.takeIf { it.frame === frame && !metrics.usesTuiViewport }
        if (geometry != null) {
            val last = frame.contentBounds.lastNonBlankRow?.takeIf { it in frame.rows.indices } ?: return 0
            return (geometry.bottom(last) + metrics.tailPaddingPx - metrics.viewportHeightPx).coerceIn(0, metrics.maxScrollPx)
        }
        return if (metrics.usesTuiViewport) {
            terminalTuiViewportScrollTarget(
                screenStartRow = frame.screenStartRow,
                lastActiveScreenRow = listOfNotNull(activeBottomRow(frame, metrics), imeAnchor?.bottomScreenRow).maxOrNull(),
                terminalCellHeightPx = metrics.cellHeightPx,
                viewportHeightPx = metrics.viewportHeightPx,
                terminalTailPaddingPx = metrics.tailPaddingPx,
                maxScrollPx = metrics.maxScrollPx,
            )
        } else {
            terminalImeAnchorScrollTarget(
                lastNonBlankRow = frame.contentBounds.lastNonBlankRow,
                terminalCellHeightPx = metrics.cellHeightPx,
                viewportHeightPx = metrics.viewportHeightPx,
                terminalTailPaddingPx = metrics.tailPaddingPx,
                maxScrollPx = metrics.maxScrollPx,
            )
        }
    }
}
