package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.TerminalHistorySnapshot
import me.rerere.rikkahub.utils.ownedRows

/**
 * UI-thread cache for measured eager fallback only. A trusted immutable history identity proves its
 * Text/IDs; the measurement owner must call invalidate for every changed/disposed measured row.
 * The first successful history scan marks those rows as history contributors in that owner. A
 * contributor change revokes this proof even if the frame/metrics are unchanged. Untrusted frames,
 * FIFO edits and metric changes still scan history. Ordinary active-screen updates visit only S rows.
 */
internal class TerminalEagerGeometryCache {
    private class History(
        val source: TerminalHistorySnapshot,
        val columns: Int,
        val metricKey: Any,
        val prefix: TerminalMeasuredHeightPrefix,
    )

    private var history: History? = null
    private var geometry: TerminalEagerViewportGeometry? = null
    private var geometryMetricKey: Any? = null
    val retainedHistoryRows: Int get() = history?.prefix?.size ?: 0
    var historyBuildCount = 0L
        private set
    var visitedHistoryRows = 0L
        private set
    var visitedScreenRows = 0L
        private set

    fun invalidate(historyChanged: Boolean) {
        geometry = null
        geometryMetricKey = null
        if (historyChanged) history = null
    }

    fun clear() = invalidate(historyChanged = true)

    /** Passive diagnostics must not populate a cache before the production layout observer does. */
    fun peek(frame: TerminalEmulator.RenderFrame, metricKey: Any): TerminalEagerViewportGeometry? =
        geometry?.takeIf { it.frame === frame && geometryMetricKey == metricKey }

    fun read(
        frame: TerminalEmulator.RenderFrame,
        metricKey: Any,
        readHeight: (Int) -> Int?,
    ): TerminalEagerViewportGeometry? {
        if (frame.rows.isEmpty() || frame.historyCount !in 0..frame.rows.size ||
            frame.screenStartRow != frame.historyCount || frame.historyLineIds.size != frame.historyCount ||
            frame.screenLineIds.size != frame.rows.size - frame.historyCount
        ) {
            clear()
            return null
        }
        peek(frame, metricKey)?.let { return it }
        geometry = null
        geometryMetricKey = null
        val owned = frame.ownedRows()
        val cached = history?.takeIf {
            owned != null && it.source === owned.history && it.columns == owned.columns && it.metricKey == metricKey
        }?.prefix
        val prefix = cached ?: run {
            history = null // Incomplete replacement measurements never leave an old proof reusable.
            val measured = TerminalMeasuredHeightPrefix.measure(frame.historyCount) { index ->
                visitedHistoryRows++
                readHeight(index)
            } ?: return null
            historyBuildCount++
            if (owned != null) history = History(owned.history, owned.columns, metricKey, measured)
            measured
        }
        val screen = TerminalMeasuredHeightPrefix.measure(frame.rows.size - frame.historyCount) { index ->
            visitedScreenRows++
            readHeight(frame.historyCount + index)
        } ?: return null
        return TerminalEagerViewportGeometry(frame, prefix, screen).also {
            geometry = it
            geometryMetricKey = metricKey
        }
    }
}
