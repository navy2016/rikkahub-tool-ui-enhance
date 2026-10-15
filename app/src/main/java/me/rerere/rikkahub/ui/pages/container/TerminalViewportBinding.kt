package me.rerere.rikkahub.ui.pages.container

import android.os.Trace
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import me.rerere.rikkahub.data.container.TerminalItemViewport
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.data.container.TerminalWidthCheckpointStore
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.data.container.TerminalViewportScrollEffect
import me.rerere.rikkahub.utils.TerminalEmulator

internal data class TerminalViewportBindingInput(
    val pass: TerminalLazyLayoutPass,
    val virtual: Boolean,
    val measureEager: Boolean,
    val metrics: () -> TerminalViewportMetrics,
)

/** The same synchronous input reads and completed-layout refresh in the app AND instrumented tests. */
internal class TerminalViewportBinding(
    val controller: TerminalViewportController,
    val eager: ScrollState,
    val lazy: LazyListState,
    val measurements: TerminalLazyItemMeasurements,
    private val input: () -> TerminalViewportBindingInput,
    val widthCheckpoint: TerminalWidthCheckpointStore? = null,
) {
    // This binding outlives backend branches, but is scoped to one mounted session/controller.
    val widthIndex = TerminalTranscriptWidthIndex()
    var checkpointLease: TerminalWidthCheckpointStore.Lease? = null
        private set
    fun attachWidthCheckpoint() { checkpointLease = widthCheckpoint?.open() }
    fun saveWidthCheckpoint() {
        val current = input().pass
        val proof = (current.metricKey as? TerminalTextMetrics)?.checkpointProof()
        val lease = checkpointLease
        if (proof != null && lease != null) {
            // A compatibility fallback may have pruned history without measuring its new suffix.
            // Save only the still-valid measured range with its matching metric identity.
            widthCheckpoint?.save(lease, widthIndex.checkpoint(proof, current.metricKey))
        }
    }
    val virtual: Boolean get() = input().virtual
    val pass: TerminalLazyLayoutPass get() = input().pass
    val measureEager: Boolean get() = input().measureEager
    @Volatile var activeWriters = 0
        private set
    @Volatile var maximumWriters = 0
        private set
    @Volatile var effectCount = 0
        private set
    private var previousBackend: Boolean? = null
    private var attachedBackend: Boolean? = null
    var onEffectStarted: ((TerminalViewportScrollEffect) -> Unit)? = null

    fun backendCommitted() {
        if (previousBackend != null && previousBackend != virtual) controller.pauseScrollEffects()
        previousBackend = virtual
        if (!measureEager) measurements.clearEagerCache()
        if (!virtual) widthIndex.retainFor(pass.frame, pass.metricKey)
    }

    fun observation(): TerminalItemViewport? {
        val current = input()
        if (!current.virtual) return null
        val metrics = current.metrics()
        return measurements.read(current.pass, lazy, metrics.cellHeightPx, metrics.tailPaddingPx)
    }

    fun eagerGeometry() = if (measureEager) measurements.readEager(pass) else null

    /** Cold width work has no horizontal range yet. Wait for real live layout, never clamp to a placeholder. */
    suspend fun awaitHorizontalLayout(horizontal: ScrollState) {
        do {
            withFrameNanos { }
            val current = input()
            val measured = if (current.virtual) {
                widthIndex.readyFor(current.pass.frame, current.pass.metricKey) && observation() != null
            } else if (current.measureEager) {
                measurements.peekEager(current.pass) != null
            } else eager.maxValue != Int.MAX_VALUE
            if (measured && horizontal.maxValue != Int.MAX_VALUE && current.metrics().viewportHeightPx > 0) return
        } while (true)
    }

    /** Called before AND after consumed pointer deltas, for AUTO, and in the executor's finally. */
    fun currentScrollPx(): Int {
        val current = input()
        if (attachedBackend != current.virtual) updateViewport()
        if (current.virtual) {
            controller.observeItemViewport(current.pass.frame, observation())
            return 0 // Sentinel is never treated as a total or absolute lazy coordinate.
        }
        controller.observeEagerGeometry(current.pass.frame, eagerGeometry())
        return eager.value
    }

    fun updateViewport() {
        Trace.beginSection("Terminal.productionViewportUpdate")
        try {
            val current = input()
            attachedBackend = current.virtual
            if (current.virtual) controller.updateItemViewport(current.pass.frame, observation()) else {
                controller.updateViewport(current.pass.frame, current.pass.frame.rows.size, current.metrics(), eager.value,
                    measuredGeometry = eagerGeometry(), requireMeasuredGeometry = current.measureEager)
            }
        } finally { Trace.endSection() }
    }

    suspend fun runEffects() {
        runTerminalViewportScrollEffects(controller, { currentScrollPx() }, { eager.maxValue },
            { if (virtual) lazy.isScrollInProgress else eager.isScrollInProgress },
            scrollToItemTarget = { effect ->
                if (!virtual) false else {
                    activeWriters++
                    maximumWriters = maxOf(maximumWriters, activeWriters)
                    effectCount++
                    Trace.beginAsyncSection("Terminal.productionScrollEffect", effect.id.toInt())
                    try {
                        onEffectStarted?.invoke(effect)
                        executeTerminalLazyItemScroll(effect, lazy, { observation() },
                            { virtual && controller.isCurrent(effect) })
                    } finally {
                        Trace.endAsyncSection("Terminal.productionScrollEffect", effect.id.toInt())
                        currentScrollPx()
                        activeWriters--
                    }
                }
            }) { effect, target ->
            if (!virtual) {
                activeWriters++
                maximumWriters = maxOf(maximumWriters, activeWriters)
                effectCount++
                Trace.beginAsyncSection("Terminal.productionScrollEffect", effect.id.toInt())
                try {
                    onEffectStarted?.invoke(effect)
                    if (effect.animated) eager.animateScrollTo(target) else eager.scrollTo(target)
                } finally {
                    Trace.endAsyncSection("Terminal.productionScrollEffect", effect.id.toInt())
                    activeWriters--
                }
            }
        }
    }
}

internal data class TerminalBoundViewport(
    val binding: TerminalViewportBinding,
    val virtualHistoryEnabled: Boolean,
    val pass: TerminalLazyLayoutPass,
    val eagerMeasurement: TerminalRowMeasurementScope?,
    val gestures: TerminalViewportGestures,
)

/** Production orchestration. Ordinary eager and virtual viewports both use actual Text geometry. */
@Composable
internal fun rememberTerminalBoundViewport(
    sessionKey: Any,
    controller: TerminalViewportController,
    eager: ScrollState,
    lazy: LazyListState,
    measurements: TerminalLazyItemMeasurements,
    frame: TerminalEmulator.RenderFrame,
    style: androidx.compose.ui.text.TextStyle,
    wantsVirtual: Boolean,
    metrics: () -> TerminalViewportMetrics,
    gestureConfig: TerminalViewportGestureConfig,
    widthCheckpoint: TerminalWidthCheckpointStore? = null,
): TerminalBoundViewport {
    val pass = rememberTerminalLazyLayoutPass(frame, style)
    var armed by remember(sessionKey) {
        mutableStateOf(wantsVirtual && (controller.state.value.autoScroll || controller.state.value.anchor != null))
    }
    var visitedVirtual by remember(sessionKey) { mutableStateOf(armed || controller.state.value.anchorRowHeightPx > 0) }
    val virtual = wantsVirtual && armed
    // Natural Text heights are not the nominal "W" cell height (fallback fonts, spans and pixel
    // rounding). Gating measurement on a previous virtual visit made fresh DEFAULT sessions aim
    // above the real tail and snap upward on drag release. Measure from the very first layout;
    // preserve the existing TUI/physical-grid path and the one scroll writer.
    // The composition needs only the TUI policy. Calling the entire metrics supplier here also
    // subscribes its caller to ScrollState.maxValue and layout/IME sizes: every content-height
    // change would recompose the panel after layout. Project the Boolean; the snapshotFlow below
    // still observes ALL live metrics and is solely responsible for viewport reconciliation.
    val latestMetrics by rememberUpdatedState(metrics)
    val usesTuiViewport by remember(sessionKey) {
        derivedStateOf(structuralEqualityPolicy()) { latestMetrics().usesTuiViewport }
    }
    val measuredEager = !virtual && !usesTuiViewport
    val latestInput by rememberUpdatedState(TerminalViewportBindingInput(pass, virtual, measuredEager, metrics))
    val latestWants by rememberUpdatedState(wantsVirtual)
    val binding = remember(sessionKey, controller, eager, lazy, measurements, widthCheckpoint) {
        TerminalViewportBinding(controller, eager, lazy, measurements, { latestInput }, widthCheckpoint)
    }
    DisposableEffect(binding) {
        binding.attachWidthCheckpoint()
        onDispose { binding.saveWidthCheckpoint(); binding.widthIndex.clear() }
    }
    SideEffect {
        binding.backendCommitted()
        if (!wantsVirtual && armed) armed = false
        if (virtual && !visitedVirtual) visitedVirtual = true
    }
    LaunchedEffect(binding) {
        merge(
            snapshotFlow {
                val current = latestInput
                // Observe geometry and IME even without output. Frame identity comparisons are O(1).
                listOf(TerminalFrameReference(current.pass.frame), current.pass.metricKey, current.virtual,
                    current.measureEager, current.metrics(), latestWants,
                    if (current.virtual) lazy.layoutInfo else null, eager.isScrollInProgress)
            }.map { Unit },
            measurements.changes,
            controller.state.map { Triple(it.initialized, it.gesture?.id, it.scrollEffect?.id) }
                .distinctUntilChanged().filter { latestWants && !binding.virtual }.map { Unit },
        ).conflate().collect {
            withFrameNanos { }
            // Cover every mutation during the frame wait with this refresh. Rearm BEFORE reading
            // live layout, so a subsequent measure/disposal always schedules the next refresh.
            measurements.acknowledgeChanges()
            // Never apply a layout captured before yielding; all frame/metrics reads are live.
            if (latestWants && !binding.virtual) {
                val geometry = binding.eagerGeometry()
                if (geometry != null && controller.state.value.initialized &&
                    controller.state.value.gesture == null && controller.state.value.scrollEffect == null &&
                    !eager.isScrollInProgress
                ) {
                    // On the first opt-in, the old fixed-cell anchor may not describe natural
                    // heights. On subsequent handoffs, preserve the already measured anchor.
                    if (!visitedVirtual && !controller.state.value.autoScroll) {
                        val top = geometry.capture(eager.value)
                        controller.restoreItemAnchor(top.anchor, top.capturedRowHeightPx)
                    }
                    controller.pauseScrollEffects()
                    visitedVirtual = true
                    armed = true
                    return@collect
                }
            }
            binding.updateViewport()
        }
    }
    LaunchedEffect(binding) { binding.runEffects() }
    val gestures = rememberTerminalViewportGestures(sessionKey, controller, gestureConfig,
        if (virtual) lazy.interactionSource else eager.interactionSource,
        currentScrollPx = { binding.currentScrollPx() },
        isScrollInProgress = { if (binding.virtual) lazy.isScrollInProgress else eager.isScrollInProgress })
    val eagerMeasurement = remember(measuredEager, pass, measurements) {
        if (measuredEager) TerminalRowMeasurementScope(pass, measurements) else null
    }
    return remember(binding, virtual, pass, eagerMeasurement, gestures) {
        TerminalBoundViewport(binding, virtual, pass, eagerMeasurement, gestures)
    }
}
