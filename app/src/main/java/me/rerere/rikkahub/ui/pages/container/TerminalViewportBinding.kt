package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import me.rerere.rikkahub.data.container.TerminalItemViewport
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
) {
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
    }

    fun observation(): TerminalItemViewport? {
        val current = input()
        if (!current.virtual) return null
        val metrics = current.metrics()
        return measurements.read(current.pass, lazy, metrics.cellHeightPx, metrics.tailPaddingPx)
    }

    fun eagerGeometry() = if (measureEager) measurements.readEager(pass) else null

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
        val current = input()
        attachedBackend = current.virtual
        if (current.virtual) controller.updateItemViewport(current.pass.frame, observation()) else {
            controller.updateViewport(current.pass.frame, current.pass.frame.rows.size, current.metrics(), eager.value,
                measuredGeometry = eagerGeometry(), requireMeasuredGeometry = current.measureEager)
        }
    }

    suspend fun runEffects() {
        runTerminalViewportScrollEffects(controller, { currentScrollPx() }, { eager.maxValue },
            { if (virtual) lazy.isScrollInProgress else eager.isScrollInProgress },
            scrollToItemTarget = { effect ->
                if (!virtual) false else {
                    activeWriters++
                    maximumWriters = maxOf(maximumWriters, activeWriters)
                    effectCount++
                    try {
                        onEffectStarted?.invoke(effect)
                        executeTerminalLazyItemScroll(effect, lazy, { observation() },
                            { virtual && controller.isCurrent(effect) })
                    } finally {
                        currentScrollPx()
                        activeWriters--
                    }
                }
            }) { effect, target ->
            if (!virtual) {
                activeWriters++
                maximumWriters = maxOf(maximumWriters, activeWriters)
                effectCount++
                try {
                    onEffectStarted?.invoke(effect)
                    if (effect.animated) eager.animateScrollTo(target) else eager.scrollTo(target)
                } finally { activeWriters-- }
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

/** Production orchestration, including renderer handoff. Default eager rendering stays unmeasured. */
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
): TerminalBoundViewport {
    val pass = rememberTerminalLazyLayoutPass(frame, style)
    var armed by remember(sessionKey) {
        mutableStateOf(wantsVirtual && (controller.state.value.autoScroll || controller.state.value.anchor != null))
    }
    var visitedVirtual by remember(sessionKey) { mutableStateOf(armed || controller.state.value.anchorRowHeightPx > 0) }
    val virtual = wantsVirtual && armed
    val measuredEager = !virtual && !metrics().usesTuiViewport && (wantsVirtual || visitedVirtual)
    val latestInput by rememberUpdatedState(TerminalViewportBindingInput(pass, virtual, measuredEager, metrics))
    val latestWants by rememberUpdatedState(wantsVirtual)
    val binding = remember(sessionKey, controller, eager, lazy, measurements) {
        TerminalViewportBinding(controller, eager, lazy, measurements) { latestInput }
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
    return TerminalBoundViewport(binding, virtual, pass,
        if (measuredEager) TerminalRowMeasurementScope(pass, measurements) else null, gestures)
}
