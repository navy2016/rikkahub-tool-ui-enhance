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
    val retainedCandidates: Int get() = widest.size

    fun width(frame: TerminalEmulator.RenderFrame, metricKey: Any, measure: (AnnotatedString) -> Int): Int {
        require(frame.historyCount in 0..frame.rows.size)
        lastMeasuredHistoryRows = 0
        lastMeasuredScreenRows = 0
        val next = frame.ownedRows()?.let {
            Source(it.history.owner, it.history.generation, it.history.renderRevision, it.columns,
                it.history.firstSequence, it.history.endSequence)
        }
        val previous = source
        val reuse = previous != null && next != null && metrics == metricKey &&
            previous.owner === next.owner && previous.generation == next.generation &&
            previous.renderRevision == next.renderRevision && previous.columns == next.columns &&
            previous.first >= 0 && next.first >= previous.first && next.end >= previous.end &&
            next.end >= next.first && previous.end >= previous.first
        source = null // A failed measurement must revoke the partially advanced cache.
        if (!reuse) widest.clear()
        val first = next?.first ?: 0L
        while (widest.isNotEmpty() && widest.first().sequence < first) widest.removeFirst()
        val start = if (reuse) (checkNotNull(previous).end - first)
            .coerceIn(0L, frame.historyCount.toLong()).toInt() else 0
        for (index in start until frame.historyCount) {
            val width = measure(frame.rows[index].text).also { require(it >= 0) }
            lastMeasuredHistoryRows++
            while (widest.isNotEmpty() && widest.last().width <= width) widest.removeLast()
            widest.addLast(Candidate(first + index, width))
        }
        var maximum = widest.firstOrNull()?.width ?: 0
        for (index in frame.historyCount until frame.rows.size) {
            val width = measure(frame.rows[index].text).also { require(it >= 0) }
            lastMeasuredScreenRows++
            maximum = maxOf(maximum, width)
        }
        metrics = metricKey
        source = next
        return maximum
    }
}
