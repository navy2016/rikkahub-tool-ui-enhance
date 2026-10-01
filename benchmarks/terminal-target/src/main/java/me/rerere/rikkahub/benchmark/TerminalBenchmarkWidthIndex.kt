package me.rerere.rikkahub.benchmark

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows

/**
 * Exact scalar widths for the opt-in lazy renderer. No Paragraph, LayoutResult, row State or cell
 * is retained. Owned immutable FIFO history permits amortized O(append + trim) width maintenance;
 * cold/style/font/owner/column changes and synthetic frames measure the full rendered history.
 * A monotonic deque retains only candidates for the widest surviving history row, at most H ints
 * plus ordinals. Screen rows are always remeasured because their Text may change in place.
 *
 * This is a memoized calculation, not published UI state: an aborted composition may populate the
 * cache, but every query proves its input range/metric key. Exceptions revoke the proof before work.
 */
internal class TerminalBenchmarkWidthIndex {
    private data class Source(
        val owner: Any,
        val generation: Long,
        val renderRevision: Long,
        val columns: Int,
        val first: Long,
        val end: Long,
    )
    private data class Candidate(val sequence: Long, val width: Int)

    private val widest = ArrayDeque<Candidate>()
    private var source: Source? = null
    private var metrics: Any? = null
    var lastMeasuredHistoryRows: Int = 0
        private set
    var lastMeasuredScreenRows: Int = 0
        private set
    val retainedCandidates: Int get() = widest.size

    fun width(frame: TerminalEmulator.RenderFrame, metricKey: Any, measure: (AnnotatedString) -> Int): Int {
        require(frame.historyCount in 0..frame.rows.size)
        lastMeasuredHistoryRows = 0
        lastMeasuredScreenRows = 0
        val owned = frame.ownedRows()
        val next = owned?.let {
            Source(it.history.owner, it.history.generation, it.history.renderRevision, it.columns,
                it.history.firstSequence, it.history.endSequence)
        }
        val previous = source
        val reuse = previous != null && next != null && metrics == metricKey &&
            previous.owner === next.owner && previous.generation == next.generation &&
            previous.renderRevision == next.renderRevision && previous.columns == next.columns &&
            previous.first >= 0 && next.first >= previous.first && next.end >= previous.end &&
            next.end >= next.first && previous.end >= previous.first
        source = null // Failed text measurement must never leave a partially advanced valid cache.
        if (!reuse) widest.clear()
        val firstSequence = next?.first ?: 0L
        while (widest.isNotEmpty() && widest.first().sequence < firstSequence) widest.removeFirst()
        val start = if (reuse) (checkNotNull(previous).end - firstSequence)
            .coerceIn(0L, frame.historyCount.toLong()).toInt() else 0
        for (index in start until frame.historyCount) {
            val width = measure(frame.rows[index].text).also { require(it >= 0) }
            lastMeasuredHistoryRows++
            while (widest.isNotEmpty() && widest.last().width <= width) widest.removeLast()
            widest.addLast(Candidate(firstSequence + index, width))
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
