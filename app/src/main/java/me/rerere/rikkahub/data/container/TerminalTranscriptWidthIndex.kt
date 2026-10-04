package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows

/**
 * Scalar-only FIFO maximum, promoted from the controlled width experiment. History reuse requires
 * emulator-owned immutable metadata; synthetic/replaced frames always take the full scan. No Text
 * layout, Paragraph, mutable cell or history-sized TextMeasurer cache is retained.
 */
internal class TerminalTranscriptWidthIndex {
    private data class Source(
        val owner: Any, val generation: Long, val renderRevision: Long,
        val columns: Int, val first: Long, val end: Long,
    )
    private data class Candidate(val sequence: Long, val width: Int)
    private val widest = ArrayDeque<Candidate>()
    private var source: Source? = null
    private var metrics: Any? = null
    var lastMeasuredHistoryRows = 0
        private set
    var lastMeasuredScreenRows = 0
        private set
    var measuredHistoryRows = 0L
        private set
    var measuredScreenRows = 0L
        private set
    val retainedCandidates: Int get() = widest.size
    val hasRetainedState: Boolean get() = source != null || metrics != null || widest.isNotEmpty()

    /** Release fonts/owner tokens as well as scalar candidates; work counters remain cumulative. */
    fun clear() {
        widest.clear()
        source = null
        metrics = null
        lastMeasuredHistoryRows = 0
        lastMeasuredScreenRows = 0
    }

    private fun sourceOf(frame: TerminalEmulator.RenderFrame): Source? =
        frame.ownedRows()?.takeIf { it.includesHistory }?.let {
            Source(it.history.owner, it.history.generation, it.history.renderRevision, it.columns,
                it.history.firstSequence, it.history.endSequence)
        }

    private fun canReuse(previous: Source?, next: Source?, metricKey: Any): Boolean =
        previous != null && next != null && metrics == metricKey &&
            previous.owner === next.owner && previous.generation == next.generation &&
            previous.renderRevision == next.renderRevision && previous.columns == next.columns &&
            previous.first >= 0 && next.first >= previous.first && next.end >= previous.end &&
            next.end >= next.first && previous.end >= previous.first

    /**
     * A committed eager fallback prunes retired candidates without measuring any Text. Keep the
     * measured end, NOT the latest frame end: output arriving while hidden must be measured on retry.
     * Advancing the first ordinal also prevents a later older/speculative frame reusing lost maxima.
     * No frame, rows, measure callback or layout survives this call.
     */
    fun retainFor(frame: TerminalEmulator.RenderFrame, metricKey: Any) {
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
        require(frame.historyCount in 0..frame.rows.size)
        lastMeasuredHistoryRows = 0
        lastMeasuredScreenRows = 0
        val next = sourceOf(frame)
        val previous = source
        val reuse = canReuse(previous, next, metricKey)
        source = null // A failed measurement must revoke the partially advanced cache.
        metrics = null
        if (!reuse) widest.clear()
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
        var maximum = widest.firstOrNull()?.width ?: 0
        for (index in frame.historyCount until frame.rows.size) {
            val width = measure(frame.rows[index].text).also { require(it >= 0) }
            lastMeasuredScreenRows++
            measuredScreenRows++
            maximum = maxOf(maximum, width)
        }
        metrics = metricKey
        source = next
        return maximum
    }
}
