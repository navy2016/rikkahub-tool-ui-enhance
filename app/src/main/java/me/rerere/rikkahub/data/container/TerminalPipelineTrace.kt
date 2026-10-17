package me.rerere.rikkahub.data.container

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Fixed vocabulary only: never accept terminal text, command strings, paths or arbitrary labels. */
internal enum class TerminalPipelineStage {
    UI_INPUT, INPUT_ENQUEUE_STARTED, INPUT_ENQUEUED, INPUT_WRITE_STARTED, INPUT_WRITTEN,
    OUTPUT_READ, EMULATOR_FED, OUTPUT_EMIT_STARTED, OUTPUT_EMITTED,
    UI_OUTPUT_RECEIVED, FRAME_PUBLISHED, FRAME_DRAWN, PANEL_COMPOSED,
    NATIVE_READ_COMPLETED, PARSE_LOCK_WAIT, PARSE_LOCK_ACQUIRED, FRAME_CAPTURE_STARTED, FRAME_CAPTURED,
    ROW_SYNC_STARTED, ROW_SYNC_FINISHED,
}

internal data class TerminalPipelineEvent(
    val sequence: Long,
    val stage: TerminalPipelineStage,
    val timeNanos: Long,
    val bytes: Int,
    val frameRevision: Long,
    val virtual: Boolean? = null,
)

/** Opt-in diagnostics only. No frame, Text, Context, layout, callback or command is retained. */
internal data class TerminalPipelineViewport(
    val revision: Long,
    val preferred: TerminalRenderMode,
    val applied: TerminalRenderMode,
    val historyRows: Int,
    val hasChunks: Boolean,
    val pan: Boolean,
    val selection: Boolean,
    val tui: Boolean,
    val alternate: Boolean,
    val ime: Boolean,
    val avoidIme: Boolean,
    val imeFallback: Boolean,
    val wantsVirtual: Boolean,
    val virtual: Boolean,
    val bindingVirtual: Boolean,
    val sameFrame: Boolean,
    val initialized: Boolean,
    val following: Boolean,
    val gestureId: Long?,
    val effectId: Long?,
    val writers: Int,
    val scrolling: Boolean,
    val geometryReady: Boolean,
    val widthReady: Boolean,
    val tailSatisfied: Boolean,
) {
    /** Read-only acceptance predicate. Never derives mode expectations from the observed backend. */
    fun satisfies(
        mode: TerminalRenderMode, expectedVirtual: Boolean, expectedIme: Boolean, minRevision: Long,
    ): Boolean {
        return minRevision >= 0 && revision >= minRevision && preferred == mode && applied == mode &&
            hasChunks && historyRows > 0 &&
            pan && !selection && !tui && !alternate && ime == expectedIme &&
            wantsVirtual == expectedVirtual && virtual == expectedVirtual && bindingVirtual == expectedVirtual &&
            (!expectedVirtual || (!imeFallback && (!ime || avoidIme) && widthReady)) &&
            sameFrame && initialized && following && gestureId == null && effectId == null &&
            writers == 0 && !scrolling && geometryReady && tailSatisfied
    }
}

/** Two actual terminal draws with the same completed source. A repeated poll is never a draw. */
internal class TerminalPipelineSettleProbe(afterDraw: Long = 0L) {
    private var lastDraw = afterDraw
    private var previous: TerminalPipelineViewport? = null
    var confirmations = 0
        private set
    var firstStableDraw = 0L
        private set

    private fun reset() {
        previous = null
        confirmations = 0
        firstStableDraw = 0
    }

    fun observe(
        sample: TerminalPipelineViewportSample?,
        mode: TerminalRenderMode,
        expectedVirtual: Boolean,
        expectedIme: Boolean,
        minRevision: Long,
    ): Boolean {
        if (sample == null) {
            reset()
            return false
        }
        if (sample.draw < lastDraw) {
            reset()
            return false
        }
        if (sample.draw == lastDraw) return false
        lastDraw = sample.draw
        val value = sample.viewport
        if (!value.satisfies(mode, expectedVirtual, expectedIme, minRevision)) {
            reset()
            return false
        }
        if (previous == value) confirmations++ else {
            confirmations = 1
            firstStableDraw = sample.draw
        }
        previous = value
        return confirmations >= 2
    }
}

internal data class TerminalPipelineViewportSample(
    val draw: Long, val timeNanos: Long, val viewport: TerminalPipelineViewport,
)

/**
 * One explicitly installed, bounded diagnostic capture; no persistent switch, logger or callbacks.
 * Disabled calls read one volatile reference and allocate nothing. The app never starts a capture;
 * the opt-in Release instrumentation starts it before mounting the real page and closes it in finally.
 * Output can be unsolicited: these are phase timestamps, NOT inferred arbitrary input/echo causality.
 */
internal object TerminalPipelineTrace {
    @Volatile private var active: Capture? = null

    @Synchronized
    fun start(processId: String, capacity: Int = 2_048, clock: () -> Long = System::nanoTime): Capture {
        require(processId.isNotBlank() && capacity in 16..4_096)
        check(active == null) { "Terminal trace capture already active" }
        return Capture(processId, capacity, clock).also { active = it }
    }

    fun isRecording(processId: String): Boolean = active?.processId == processId

    /** Read only inside the opt-in draw modifier; a test pulse invalidates that draw node, not UI state. */
    fun viewportDrawRequest(processId: String): Int = active?.takeIf { it.processId == processId }?.drawRequest ?: 0

    fun record(processId: String, stage: TerminalPipelineStage, bytes: Int = 0,
        frameRevision: Long = -1, virtual: Boolean? = null) {
        val capture = active ?: return
        if (capture.processId == processId) capture.record(stage, bytes, frameRevision, virtual)
    }

    fun recordViewport(processId: String, viewport: TerminalPipelineViewport) {
        val capture = active ?: return
        if (capture.processId == processId) capture.recordViewport(viewport)
    }

    @Synchronized
    private fun stop(capture: Capture) {
        if (active === capture) active = null
        capture.seal()
    }

    internal class Capture internal constructor(
        internal val processId: String,
        private val capacity: Int,
        private val clock: () -> Long,
    ) : AutoCloseable {
        private val events = ArrayDeque<TerminalPipelineEvent>()
        private var sequence = 0L
        private var closed = false
        private var drawSequence = 0L
        private var latestViewport: TerminalPipelineViewportSample? = null
        private val viewportTransitions = ArrayDeque<TerminalPipelineViewportSample>()
        internal var drawRequest by mutableIntStateOf(0)
            private set
        @Volatile var dropped = 0L
            private set

        @Synchronized internal fun record(stage: TerminalPipelineStage, bytes: Int, frameRevision: Long,
            virtual: Boolean? = null) {
            if (closed) return // An IO caller can have captured the reference before close().
            require(bytes >= 0)
            if (events.size == capacity) { events.removeFirst(); dropped++ }
            events.addLast(TerminalPipelineEvent(++sequence, stage, clock(), bytes, frameRevision, virtual))
        }

        @Synchronized fun snapshot(): List<TerminalPipelineEvent> = events.toList()
        @Synchronized internal fun recordViewport(viewport: TerminalPipelineViewport) {
            if (closed) return
            val sample = TerminalPipelineViewportSample(++drawSequence, clock(), viewport)
            if (latestViewport?.viewport != viewport) {
                if (viewportTransitions.size == 64) viewportTransitions.removeFirst()
                viewportTransitions.addLast(sample)
            }
            latestViewport = sample
        }
        @Synchronized fun viewport(): TerminalPipelineViewportSample? = latestViewport
        @Synchronized fun viewportTransitions(): List<TerminalPipelineViewportSample> = viewportTransitions.toList()
        /** Test UI-thread request. It only schedules another actual draw of the existing terminal. */
        @Synchronized fun requestViewportDraw() {
            if (!closed) drawRequest++
        }
        /** Explicit test sample window, preserving monotonic sequence numbers across windows. */
        @Synchronized fun beginWindow(): Long {
            check(!closed)
            events.clear()
            dropped = 0L
            return sequence
        }
        @Synchronized internal fun seal() { closed = true }
        override fun close() = stop(this)
    }
}
