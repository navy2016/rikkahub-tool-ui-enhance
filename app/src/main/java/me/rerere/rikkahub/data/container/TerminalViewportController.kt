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
)

internal data class TerminalViewportControllerState(
    val mode: ViewportMode,
    val anchor: ViewportAnchor? = null,
    /** Keep the original capture scale so repeated font changes don't accumulate rounding drift. */
    val anchorCellHeightPx: Int = 0,
    val initialized: Boolean = false,
    val gesture: TerminalViewportGesture? = null,
    val scrollEffect: TerminalViewportScrollEffect? = null,
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
    private var measuredAnchorScrollPx: Int? = null

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
    ) {
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
        } else if (previousMetrics?.imeVisible != true && state.value.autoScroll) {
            // Mode is authoritative. Do not infer user intent from an IME-clamped pixel position.
            imeAnchor = ImeAnchor(scrollPx, frame.screenGeneration, activeBottomRow)
        } else {
            imeAnchor = imeAnchor?.let { previous ->
                if (previous.screenGeneration != frame.screenGeneration) {
                    ImeAnchor(scrollPx, frame.screenGeneration, activeBottomRow)
                } else previous.copy(bottomScreenRow = listOfNotNull(
                    previous.bottomScreenRow, activeBottomRow,
                ).maxOrNull())
            }
        }

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
     * Supplies an exact target from a measured LazyList layout. This is an optional adapter input;
     * eager/TUI callers never set it and retain the existing fixed-grid reducer behavior.
     */
    fun setMeasuredAnchorScrollPx(targetScrollPx: Int?) {
        measuredAnchorScrollPx = targetScrollPx?.coerceAtLeast(0)
    }

    /** Starts or continues real user input; called before the scrollable consumes its delta. */
    fun beginUserScroll(origin: ViewportScrollOrigin, currentScrollPx: Int): Long {
        require(origin.isUserInput)
        scrollPx = currentScrollPx.coerceAtLeast(0)
        imeAnchor = null
        val gesture = state.value.gesture?.takeIf { it.origin == origin }
            ?: TerminalViewportGesture(++nextOperationId, origin)
        mutableState.value = state.value.copy(gesture = gesture, scrollEffect = null)
        return gesture.id
    }

    /** Called only for consumed user deltas, never for layout clamps or programmatic feedback. */
    fun userScrolled(gestureId: Long, currentScrollPx: Int) {
        if (state.value.gesture?.id != gestureId) return
        scrollPx = currentScrollPx.coerceAtLeast(0)
        if (!ready()) return
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
        mutableState.value = state.value.copy(gesture = null, scrollEffect = null)
        if (enabled) {
            mutableState.value = state.value.copy(mode = followMode(), anchor = null, anchorCellHeightPx = 0)
        } else {
            capture(scrollPx)
        }
        reconcile(ViewportScrollOrigin.JUMP)
    }

    fun jumpToBottom(currentScrollPx: Int) = jump(toBottom = true, currentScrollPx = currentScrollPx)

    fun jumpToTop(currentScrollPx: Int) = jump(toBottom = false, currentScrollPx = currentScrollPx)

    private fun jump(toBottom: Boolean, currentScrollPx: Int) {
        scrollPx = currentScrollPx.coerceAtLeast(0)
        imeAnchor = null
        mutableState.value = state.value.copy(gesture = null, scrollEffect = null)
        if (toBottom) {
            mutableState.value = state.value.copy(mode = followMode(), anchor = null, anchorCellHeightPx = 0)
        } else capture(0)
        reconcile(ViewportScrollOrigin.JUMP, animated = true)
    }

    fun isCurrent(effect: TerminalViewportScrollEffect): Boolean =
        state.value.gesture == null && state.value.scrollEffect?.id == effect.id

    fun scrollFinished(effectId: Long, currentScrollPx: Int, completed: Boolean) {
        if (state.value.scrollEffect?.id != effectId) return
        scrollPx = currentScrollPx.coerceAtLeast(0)
        mutableState.value = state.value.copy(scrollEffect = null)
        // Frame/geometry changes during an explicit jump are deferred, not allowed to cancel it.
        if (completed) reconcile(ViewportScrollOrigin.REDUCER)
    }

    fun isNearBottom(currentScrollPx: Int): Boolean {
        val metrics = metrics ?: return false
        val frame = frame ?: return false
        return currentScrollPx >= bottomTarget(frame, metrics) - metrics.cellHeightPx * 2
    }

    private fun followMode(): ViewportMode =
        if (metrics?.usesTuiViewport == true) ViewportMode.SCREEN else ViewportMode.TAIL

    private fun ready(): Boolean {
        val frame = frame ?: return false
        val metrics = metrics ?: return false
        return metrics.cellHeightPx > 0 && metrics.viewportHeightPx > 0 &&
            metrics.maxScrollPx >= 0 && metrics.maxScrollPx != Int.MAX_VALUE && renderedRows > 0 &&
            renderedRows == frame.rows.size && frame.historyCount >= 0 &&
            frame.historyLineIds.size == frame.historyCount &&
            frame.screenLineIds.size == renderedRows - frame.historyCount
    }

    private fun capture(atScrollPx: Int) {
        val frame = frame
        val metrics = metrics
        val anchor = if (ready() && frame != null && metrics != null) {
            captureViewportAnchor(frame, renderedRows, atScrollPx.coerceIn(0, metrics.maxScrollPx), metrics.cellHeightPx)
        } else null
        legacyOffsetPx = if (anchor == null) atScrollPx else null
        mutableState.value = state.value.copy(
            mode = ViewportMode.LOCKED,
            anchor = anchor,
            anchorCellHeightPx = metrics?.cellHeightPx ?: 0,
        )
    }

    private fun reconcile(origin: ViewportScrollOrigin, animated: Boolean = false) {
        if (!ready() || !state.value.initialized || state.value.gesture != null) return
        if (state.value.scrollEffect?.animated == true) return
        val frame = requireNotNull(frame)
        val metrics = requireNotNull(metrics)
        if (!state.value.autoScroll && state.value.anchor == null) capture(legacyOffsetPx ?: scrollPx)
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
                measuredAnchorScrollPx = measuredAnchorScrollPx,
            ), frame, renderedRows,
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

    private fun activeBottomRow(frame: TerminalEmulator.RenderFrame, metrics: TerminalViewportMetrics): Int? =
        if (metrics.preservePhysicalGrid) frame.screenLineIds.lastIndex.takeIf { it >= 0 } else {
            terminalEffectiveScreenBottomRow(frame.screenContentBounds, frame.cursorRow, frame.cursorVisible)
        }

    private fun bottomTarget(frame: TerminalEmulator.RenderFrame, metrics: TerminalViewportMetrics): Int =
        if (metrics.usesTuiViewport) {
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
