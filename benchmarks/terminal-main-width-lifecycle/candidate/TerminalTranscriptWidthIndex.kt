package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows

/**
 * Scalar-only FIFO maximum, promoted from the controlled width experiment. History reuse requires
 * emulator-owned immutable metadata; synthetic/replaced frames always take the full scan. Only the
 * current physical screen retains annotated-text keys plus scalar widths (at most MAX_ROWS). No
 * Text layout, Paragraph, mutable cell or history-sized TextMeasurer cache is retained.
 * FULL_VIRTUAL may supply a packaged-font proof: its ONE main-history model then outlives
 * an owned ALT screen. This is not a second parked checkpoint, and ALT never contributes
 * that hidden history to its width. Legacy callers keep the original invalidation policy.
 */
internal class TerminalTranscriptWidthIndex {
    /** Source/metric proof plus copied scalar candidates only; never a frame or annotated screen Text. */
    class Checkpoint internal constructor(
        internal val source: Source,
        internal val proof: Any,
        internal val candidates: List<Candidate>,
    ) {
        val retainedRows: Int get() = candidates.size
    }

    fun checkpoint(proof: Any, metricKey: Any? = metrics): Checkpoint? = source?.takeIf {
        historyMetrics == metricKey && (historyProof == null || historyProof == proof)
    }?.let { current ->
        if (current.end <= current.first) null else Checkpoint(current, proof, widest.toList())
    }

    /** Only a complete checkpoint with an equal external font proof may initialize a new mounted index. */
    fun restore(checkpoint: Checkpoint?, proof: Any, metricKey: Any): Boolean {
        if (checkpoint == null || checkpoint.proof != proof) return false
        clear()
        source = checkpoint.source
        metrics = metricKey
        historyMetrics = metricKey
        historyProof = checkpoint.proof
        widest.addAll(checkpoint.candidates)
        return true
    }

    /** Worker-owned copy; failed or cancelled width work must never mutate the displayed index. */
    fun fork(): TerminalTranscriptWidthIndex = TerminalTranscriptWidthIndex().also {
        it.source = source
        it.metrics = metrics
        it.historyMetrics = historyMetrics
        it.historyProof = historyProof
        it.widest.addAll(widest)
        it.screenGeneration = screenGeneration
        it.screenWidths = screenWidths
        it.measuredHistoryRows = measuredHistoryRows
        it.measuredScreenRows = measuredScreenRows
        it.reusedScreenRows = reusedScreenRows
    }
    internal data class Source(
        val owner: Any, val generation: Long, val renderRevision: Long,
        val columns: Int, val first: Long, val end: Long,
    )
    internal data class Candidate(val sequence: Long, val width: Int)
    private data class ScreenWidth(val text: AnnotatedString, val width: Int)
    private val widest = ArrayDeque<Candidate>()
    // The scalar history proof is independent of the current displayed-frame proof below.
    private var source: Source? = null
    private var historyMetrics: Any? = null
    private var historyProof: Any? = null
    private var metrics: Any? = null
    private var screenGeneration: Long? = null
    private var screenWidths: Map<Long, ScreenWidth> = emptyMap()
    var lastMeasuredHistoryRows = 0
        private set
    var lastMeasuredScreenRows = 0
        private set
    var measuredHistoryRows = 0L
        private set
    var measuredScreenRows = 0L
        private set
    var lastReusedScreenRows = 0
        private set
    var reusedScreenRows = 0L
        private set
    val retainedCandidates: Int get() = widest.size
    val retainedScreenRows: Int get() = screenWidths.size
    val hasRetainedState: Boolean get() = source != null || metrics != null || widest.isNotEmpty() || screenWidths.isNotEmpty()
    private var measuredFrame = java.lang.ref.WeakReference<TerminalEmulator.RenderFrame>(null)
    var epoch = 0L
        private set

    fun readyFor(frame: TerminalEmulator.RenderFrame, metricKey: Any): Boolean =
        measuredFrame.get() === frame && metrics == metricKey

    fun requiredHistoryMeasurements(frame: TerminalEmulator.RenderFrame, metricKey: Any,
        independentHistoryProof: Any? = null): Int {
        val next = sourceOf(frame)
        val previous = source
        return if (canReuse(previous, next, metricKey) && compatibleProof(independentHistoryProof)) {
            val start = (checkNotNull(previous).end - checkNotNull(next).first)
                .coerceIn(0L, frame.historyCount.toLong()).toInt()
            frame.historyCount - start
        } else frame.historyCount
    }

    fun acceptComputed(
        other: TerminalTranscriptWidthIndex,
        expectedEpoch: Long = epoch,
        completedFrame: TerminalEmulator.RenderFrame? = other.measuredFrame.get(),
    ): Boolean {
        if (expectedEpoch != epoch) return false
        require(completedFrame != null && other.measuredFrame.get() === completedFrame && other.metrics != null) {
            "Incomplete terminal width result"
        }
        epoch++
        source = other.source
        metrics = other.metrics
        historyMetrics = other.historyMetrics
        historyProof = other.historyProof
        widest.clear()
        widest.addAll(other.widest)
        screenGeneration = other.screenGeneration
        screenWidths = other.screenWidths
        measuredHistoryRows = other.measuredHistoryRows
        measuredScreenRows = other.measuredScreenRows
        reusedScreenRows = other.reusedScreenRows
        lastMeasuredHistoryRows = other.lastMeasuredHistoryRows
        lastMeasuredScreenRows = other.lastMeasuredScreenRows
        lastReusedScreenRows = other.lastReusedScreenRows
        measuredFrame = java.lang.ref.WeakReference(completedFrame)
        return true
    }

    private fun clearScreen() {
        screenWidths = emptyMap()
        screenGeneration = null
    }

    /** Release fonts/owner tokens as well as scalar candidates; work counters remain cumulative. */
    fun clear() {
        epoch++
        measuredFrame.clear()
        widest.clear()
        source = null
        metrics = null
        historyMetrics = null
        historyProof = null
        clearScreen()
        lastMeasuredHistoryRows = 0
        lastMeasuredScreenRows = 0
        lastReusedScreenRows = 0
    }

    private fun sourceOf(frame: TerminalEmulator.RenderFrame): Source? =
        frame.ownedRows()?.takeIf { it.includesHistory }?.let {
            Source(it.history.owner, it.history.generation, it.history.renderRevision, it.columns,
                it.history.firstSequence, it.history.endSequence)
        }

    private fun canReuse(previous: Source?, next: Source?, metricKey: Any): Boolean =
        previous != null && next != null && historyMetrics == metricKey &&
            previous.owner === next.owner && previous.generation == next.generation &&
            previous.renderRevision == next.renderRevision && previous.columns == next.columns &&
            previous.first >= 0 && next.first >= previous.first && next.end >= previous.end &&
            next.end >= next.first && previous.end >= previous.first

    private fun compatibleProof(proof: Any?): Boolean =
        proof == null || historyProof == null || historyProof == proof

    /** Only actual owned ALT can hide main history. includeScrollback=false on MAIN is NOT ALT.
     * ALT exposes the owner's generation/style/columns and current archival end, but NOT its
     * retained first ordinal. Never infer a FIFO trim or advance the measured end from this view;
     * the next owned MAIN frame must prune and measure its actual surviving range.
     */
    private fun canKeepHiddenHistory(frame: TerminalEmulator.RenderFrame, metricKey: Any, proof: Any?): Boolean {
        if (proof == null || !frame.isAlternateScreen || historyProof != proof || historyMetrics != metricKey) return false
        val previous = source ?: return false
        val owned = frame.ownedRows() ?: return false
        return owned.alternateScreen && !owned.includesHistory && frame.historyCount == 0 &&
            previous.owner === owned.history.owner && previous.generation == owned.history.generation &&
            previous.renderRevision == owned.history.renderRevision && previous.columns == owned.columns &&
            previous.end <= owned.history.endSequence
    }

    /**
     * A committed eager fallback prunes retired candidates without measuring any Text. Keep the
     * measured end, NOT the latest frame end: output arriving while hidden must be measured on retry.
     * Advancing the first ordinal also prevents a later older/speculative frame reusing lost maxima.
     * Drop screen text keys on every committed compatibility fallback, even without output. Only
     * scalar history candidates/validity metadata survive; no frame, rows, callback or layout does.
     */
    fun retainFor(frame: TerminalEmulator.RenderFrame, metricKey: Any) {
        epoch++
        measuredFrame.clear()
        clearScreen()
        val previous = source
        val next = sourceOf(frame)
        if (frame.historyCount == 0 || !canReuse(previous, next, metricKey)) {
            clear()
            return
        }
        val first = checkNotNull(next).first
        while (widest.isNotEmpty() && widest.first().sequence < first) widest.removeFirst()
        source = checkNotNull(previous).copy(first = first, end = maxOf(first, previous.end))
    }

    fun width(frame: TerminalEmulator.RenderFrame, metricKey: Any, measure: (AnnotatedString) -> Int): Int {
        return width(frame, metricKey, null, measure)
    }

    /** Explicit proof only from FULL_VIRTUAL; unknown/custom/async fonts must pass null. */
    fun width(frame: TerminalEmulator.RenderFrame, metricKey: Any, independentHistoryProof: Any?,
        measure: (AnnotatedString) -> Int): Int {
        require(frame.historyCount in 0..frame.rows.size)
        epoch++
        lastMeasuredHistoryRows = 0
        lastMeasuredScreenRows = 0
        lastReusedScreenRows = 0
        val next = sourceOf(frame)
        val previous = source
        val reuse = canReuse(previous, next, metricKey) && compatibleProof(independentHistoryProof)
        val keepHidden = canKeepHiddenHistory(frame, metricKey, independentHistoryProof)
        val previousHistoryMetrics = historyMetrics
        val previousHistoryProof = historyProof
        val screenCount = frame.rows.size - frame.historyCount
        val cacheScreen = next != null && !frame.isAlternateScreen &&
            screenCount in 1..TerminalEmulator.MAX_ROWS && frame.screenLineIds.size == screenCount
        val reusableScreen = if (cacheScreen && reuse && screenGeneration == frame.screenGeneration) screenWidths
            else emptyMap()
        measuredFrame.clear()
        source = null // A failed measurement must revoke the partially advanced cache.
        historyMetrics = null
        historyProof = null
        metrics = null
        clearScreen() // Publish the new screen only after EVERY requested measurement succeeds.
        if (!reuse && !keepHidden) widest.clear()
        val first = next?.first ?: 0L
        while (widest.isNotEmpty() && widest.first().sequence < first) widest.removeFirst()
        val start = if (reuse) (checkNotNull(previous).end - first)
            .coerceIn(0L, frame.historyCount.toLong()).toInt() else 0
        for (index in start until frame.historyCount) {
            val width = measure(frame.rows[index].text).also { require(it >= 0) }
            lastMeasuredHistoryRows++
            measuredHistoryRows++
            while (widest.isNotEmpty() && widest.last().width <= width) widest.removeLast()
            widest.addLast(Candidate(first + index, width))
        }
        // Hidden MAIN history is NEVER an ALT viewport's width (nor its horizontal range).
        var maximum = if (keepHidden) 0 else widest.firstOrNull()?.width ?: 0
        // Replace, never accumulate: FIFO screen rows can move to history or disappear on resize.
        val nextScreen = if (cacheScreen) HashMap<Long, ScreenWidth>(screenCount) else null
        for (index in frame.historyCount until frame.rows.size) {
            val text = frame.rows[index].text
            val id = if (cacheScreen) frame.screenLineIds[index - frame.historyCount] else null
            // Full equality handles the uncached oracle/synthetic path too, including all styling.
            val cached = id?.let { reusableScreen[it] }?.takeIf { it.text == text }
            val width = if (cached != null) {
                lastReusedScreenRows++
                reusedScreenRows++
                cached.width
            } else {
                measure(text).also {
                    require(it >= 0)
                    lastMeasuredScreenRows++
                    measuredScreenRows++
                }
            }
            if (nextScreen != null) nextScreen[checkNotNull(id)] = cached ?: ScreenWidth(text, width)
            maximum = maxOf(maximum, width)
        }
        screenWidths = nextScreen ?: emptyMap()
        screenGeneration = frame.screenGeneration.takeIf { cacheScreen }
        metrics = metricKey
        source = if (keepHidden) previous else next
        historyMetrics = if (keepHidden) previousHistoryMetrics else metricKey.takeIf { next != null }
        historyProof = if (keepHidden) previousHistoryProof else independentHistoryProof.takeIf { next != null }
        measuredFrame = java.lang.ref.WeakReference(frame)
        return maximum
    }
}
