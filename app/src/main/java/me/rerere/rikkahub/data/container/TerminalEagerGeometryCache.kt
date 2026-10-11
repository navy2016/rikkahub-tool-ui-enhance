package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.TerminalHistorySnapshot
import me.rerere.rikkahub.utils.TerminalHistorySnapshotBlock
import me.rerere.rikkahub.utils.TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS
import me.rerere.rikkahub.utils.ownedRows

/** A row's invalidation capability. It retains only an ordinal bucket, never UI/text/source. */
internal class TerminalEagerHistoryBlockToken internal constructor(internal val bucket: Long)

/**
 * Exact eager geometry. Trusted unchanged archival blocks reuse scalar height prefixes; FIFO
 * boundaries and invalidated blocks read actual current row heights. The registry invalidates a
 * block via each contributing row's unique token even when Text/metric/source did not change.
 * Directory copying/aggregation is O(H / 128); no full-height array is rebuilt on each FIFO edit.
 * Only complete blocks can survive a missing-layout retry, and full geometry is published ONLY
 * when all rows are valid. Untrusted/replaced/metric-invalid history takes the conservative scan.
 */
internal class TerminalEagerGeometryCache {
    private class Block(
        val source: TerminalHistorySnapshotBlock,
        val token: TerminalEagerHistoryBlockToken,
        val prefix: TerminalMeasuredHeightPrefix,
    )

    private class History(
        val source: TerminalHistorySnapshot,
        val columns: Int,
        val metricKey: Any,
    ) {
        val blocks = HashMap<Long, Block>()
        var prefix: TerminalMeasuredHeightPrefix? = null
        var retainedRows = 0
    }

    /** Old detached/replaced owners cannot evict a newer block with the same ordinal bucket. */
    fun invalidateBlock(token: TerminalEagerHistoryBlockToken?) {
        geometry = null
        geometryMetricKey = null
        if (token == null) return
        val saved = history ?: return
        val block = saved.blocks[token.bucket] ?: return
        if (block.token !== token) return
        saved.blocks.remove(token.bucket)
        saved.retainedRows -= block.prefix.size
        saved.prefix = null
    }

    private var history: History? = null
    private var geometry: TerminalEagerViewportGeometry? = null
    private var geometryMetricKey: Any? = null
    val retainedHistoryRows: Int get() = history?.retainedRows ?: 0
    val retainedHistoryBlocks: Int get() = history?.blocks?.size ?: 0
    var historyBuildCount = 0L
        private set
    var visitedHistoryRows = 0L
        private set
    var visitedScreenRows = 0L
        private set
    var visitedHistoryBlocks = 0L
        private set
    var measuredHistoryBlocks = 0L
        private set
    var reusedHistoryBlocks = 0L
        private set
    var reusedHistoryRows = 0L
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
        onHistoryRowMeasured: ((Int, TerminalEagerHistoryBlockToken) -> Unit)? = null,
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
        val owned = frame.ownedRows()?.takeIf { it.includesHistory && !frame.isAlternateScreen }
        val prefix = if (owned == null) {
            history = null
            TerminalMeasuredHeightPrefix.measure(frame.historyCount) { index ->
                visitedHistoryRows++
                readHeight(index)
            }?.also { historyBuildCount++ }
        } else measuredHistory(owned.history, owned.columns, metricKey, onHistoryRowMeasured, readHeight)
        if (prefix == null) return null
        val screen = TerminalMeasuredHeightPrefix.measure(frame.rows.size - frame.historyCount) { index ->
            visitedScreenRows++
            readHeight(frame.historyCount + index)
        } ?: return null
        return TerminalEagerViewportGeometry(frame, prefix, screen).also {
            geometry = it
            geometryMetricKey = metricKey
        }
    }

    private fun measuredHistory(
        source: TerminalHistorySnapshot,
        columns: Int,
        metricKey: Any,
        onMeasured: ((Int, TerminalEagerHistoryBlockToken) -> Unit)?,
        readHeight: (Int) -> Int?,
    ): TerminalMeasuredHeightPrefix? {
        val previous = history?.takeIf {
            it.columns == columns && it.metricKey == metricKey && it.source.owner === source.owner &&
                it.source.generation == source.generation && it.source.renderRevision == source.renderRevision &&
                source.firstSequence >= it.source.firstSequence && source.endSequence >= it.source.endSequence
        }
        val current = if (previous != null && previous.source === source) previous else History(source, columns, metricKey).also { next ->
            if (previous != null) {
                // Preserve EVERY complete surviving block before any new row can be missing.
                // Never carry changed boundary sources or removed blocks into the new directory.
                for (block in source.blocks) {
                    visitedHistoryBlocks++
                    val bucket = block.firstSequence / TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS
                    val cached = previous.blocks[bucket]?.takeIf { it.source === block } ?: continue
                    next.blocks[bucket] = cached
                    next.retainedRows += cached.prefix.size
                }
            }
        }
        history = current
        current.prefix?.let { return it }
        val parts = ArrayList<TerminalMeasuredHeightPrefix>(source.blocks.size)
        for (block in source.blocks) {
            visitedHistoryBlocks++
            val bucket = block.firstSequence / TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS
            val cached = current.blocks[bucket]
            val measured = if (cached != null) {
                check(cached.source === block)
                reusedHistoryBlocks++
                reusedHistoryRows += block.size
                cached.prefix
            } else {
                val token = TerminalEagerHistoryBlockToken(bucket)
                val start = (block.firstSequence - source.firstSequence).toInt()
                val result = TerminalMeasuredHeightPrefix.measure(block.size) { offset ->
                    val index = start + offset
                    visitedHistoryRows++
                    readHeight(index)?.takeIf { it > 0 }?.also { onMeasured?.invoke(index, token) }
                } ?: return null
                // A partially measured block never gets a reusable proof, even if its rows were stamped.
                current.blocks[bucket] = Block(block, token, result)
                current.retainedRows += result.size
                measuredHistoryBlocks++
                result
            }
            parts.add(measured)
        }
        return TerminalMeasuredHeightPrefix.fromBlocks(source.firstSequence, parts).also {
            check(it.size == source.size)
            historyBuildCount++
            current.prefix = it
        }
    }
}
