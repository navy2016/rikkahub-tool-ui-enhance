package me.rerere.rikkahub.utils

import java.util.Collections
import java.util.RandomAccess

internal const val TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS = 128

/** A transient builder value, never a reference to mutable emulator cells. */
internal class TerminalHistorySnapshotRow(
    val row: TerminalEmulator.RenderedRow,
    val lineId: Long,
    val isNotBlank: Boolean,
)

/** One immutable archival-ordinal bucket, containing only rows that survive this snapshot. */
internal class TerminalHistorySnapshotBlock private constructor(
    val firstSequence: Long,
    private val rendered: List<TerminalEmulator.RenderedRow>,
    private val ids: LongArray,
    private val occupied: BooleanArray,
    val contentBounds: TerminalEmulator.ContentBounds,
) {
    val size: Int get() = rendered.size
    val endSequence: Long get() = firstSequence + size
    fun row(index: Int): TerminalEmulator.RenderedRow = rendered[index]
    fun lineId(index: Int): Long = ids[index]

    companion object {
        fun build(
            firstSequence: Long,
            size: Int,
            previous: TerminalHistorySnapshotBlock?,
            read: (Long) -> TerminalHistorySnapshotRow,
        ): TerminalHistorySnapshotBlock {
            require(firstSequence >= 0 && size in 1..TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS &&
                size <= TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS - firstSequence % TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS)
            Math.addExact(firstSequence, size.toLong())
            val rows = ArrayList<TerminalEmulator.RenderedRow>(size)
            val ids = LongArray(size)
            val occupied = BooleanArray(size)
            var first: Int? = null
            var last: Int? = null
            var count = 0
            for (index in 0 until size) {
                val sequence = firstSequence + index
                if (previous != null && sequence >= previous.firstSequence && sequence < previous.endSequence) {
                    val oldIndex = (sequence - previous.firstSequence).toInt()
                    rows.add(previous.rendered[oldIndex])
                    ids[index] = previous.ids[oldIndex]
                    occupied[index] = previous.occupied[oldIndex]
                } else {
                    val value = read(sequence)
                    rows.add(value.row)
                    ids[index] = value.lineId
                    occupied[index] = value.isNotBlank
                }
                if (occupied[index]) {
                    if (first == null) first = index
                    last = index
                    count++
                }
            }
            return TerminalHistorySnapshotBlock(firstSequence, rows, ids, occupied,
                TerminalEmulator.ContentBounds(first, last, count))
        }
    }
}

/**
 * Owned immutable history. FIFO snapshots share complete buckets, copy only the changed boundary
 * buckets, and read only new rows. The directory and content-bound aggregation are O(H / 128), not
 * O(1); cold/style/column resets still read H rows. No slice retains a previous snapshot or a trimmed
 * row, and neither TextLayouts nor mutable cells/deques/read callbacks escape into the snapshot.
 */
internal class TerminalHistorySnapshot private constructor(
    val owner: Any,
    val generation: Long,
    val renderRevision: Long,
    val firstSequence: Long,
    blocks: List<TerminalHistorySnapshotBlock>,
    val size: Int,
    val contentBounds: TerminalEmulator.ContentBounds,
) {
    init { require(firstSequence >= 0 && size in 0..TerminalEmulator.MAX_SCROLLBACK_LINES) }

    // The builder relinquishes the directory; published blocks never change. Keep public List
    // equality/hash/random-access semantics and reject Java/MutableList mutation attempts.
    val blocks: List<TerminalHistorySnapshotBlock> = Collections.unmodifiableList(blocks)
    val endSequence: Long = Math.addExact(firstSequence, size.toLong())
    val rows: List<TerminalEmulator.RenderedRow> = Collections.unmodifiableList(Rows(this))
    val lineIds: List<Long> = Collections.unmodifiableList(LineIds(this))

    private fun blockAt(index: Int): TerminalHistorySnapshotBlock {
        if (index !in 0 until size) throw IndexOutOfBoundsException("history row $index, size $size")
        val slot = ((firstSequence % TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS).toInt() + index) /
            TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS
        return blocks[slot]
    }

    private class Rows(private val snapshot: TerminalHistorySnapshot) : AbstractList<TerminalEmulator.RenderedRow>(), RandomAccess {
        override val size: Int get() = snapshot.size
        override fun get(index: Int): TerminalEmulator.RenderedRow {
            val block = snapshot.blockAt(index)
            return block.row((snapshot.firstSequence + index - block.firstSequence).toInt())
        }
    }

    private class LineIds(private val snapshot: TerminalHistorySnapshot) : AbstractList<Long>(), RandomAccess {
        override val size: Int get() = snapshot.size
        override fun get(index: Int): Long {
            val block = snapshot.blockAt(index)
            return block.lineId((snapshot.firstSequence + index - block.firstSequence).toInt())
        }
    }

    companion object {
        fun empty(owner: Any, generation: Long, renderRevision: Long, firstSequence: Long): TerminalHistorySnapshot =
            TerminalHistorySnapshot(owner, generation, renderRevision, firstSequence, emptyList(), 0,
                TerminalEmulator.ContentBounds(null, null, 0))

        /**
         * Called only by the owner of a FIFO archive. Within one owner/generation/renderRevision,
         * surviving ordinals MUST retain their row, ID and occupancy; any replacement revokes that
         * proof. The callback is evaluated synchronously for new rows only, and is never retained.
         */
        fun build(
            owner: Any,
            generation: Long,
            renderRevision: Long,
            firstSequence: Long,
            size: Int,
            previous: TerminalHistorySnapshot?,
            read: (Int) -> TerminalHistorySnapshotRow,
        ): TerminalHistorySnapshot {
            require(firstSequence >= 0 && size in 0..TerminalEmulator.MAX_SCROLLBACK_LINES)
            val end = Math.addExact(firstSequence, size.toLong())
            val reusable = previous?.takeIf {
                it.owner === owner && it.generation == generation && it.renderRevision == renderRevision &&
                    firstSequence >= it.firstSequence && end >= it.endSequence
            }
            if (reusable != null && firstSequence == reusable.firstSequence && size == reusable.size) return reusable
            if (size == 0) return empty(owner, generation, renderRevision, firstSequence)
            val blocks = ArrayList<TerminalHistorySnapshotBlock>(size / TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS + 2)
            var cursor = firstSequence
            var first: Int? = null
            var last: Int? = null
            var occupied = 0
            while (cursor < end) {
                val remaining = TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS - (cursor % TERMINAL_HISTORY_SNAPSHOT_BLOCK_ROWS).toInt()
                val count = minOf(remaining, (end - cursor).toInt())
                val old = reusable?.let {
                    if (cursor < it.endSequence) it.blockAt((cursor - it.firstSequence).toInt()) else null
                }
                val block = if (old != null && old.firstSequence == cursor && old.size == count) old else {
                    TerminalHistorySnapshotBlock.build(cursor, count, old) { sequence ->
                        read((sequence - firstSequence).toInt())
                    }
                }
                blocks.add(block)
                val offset = (cursor - firstSequence).toInt()
                if (first == null) first = block.contentBounds.firstNonBlankRow?.let { offset + it }
                block.contentBounds.lastNonBlankRow?.let { last = offset + it }
                occupied += block.contentBounds.nonBlankRowCount
                cursor += count
            }
            return TerminalHistorySnapshot(owner, generation, renderRevision, firstSequence, blocks, size,
                TerminalEmulator.ContentBounds(first, last, occupied))
        }
    }
}

/**
 * O(1) concatenation with random access. An unchanged history prefix is compared by identity;
 * general List equality/hash semantics are preserved for legacy/synthetic frames and tests.
 */
internal class TerminalFrameRows(
    val history: TerminalHistorySnapshot,
    screenRows: List<TerminalEmulator.RenderedRow>,
    val screenLineIds: List<Long>,
    val screenGeneration: Long,
    val columns: Int,
    val includesHistory: Boolean,
    val nextLineId: Long,
) : AbstractList<TerminalEmulator.RenderedRow>(), RandomAccess {
    private val screenRows = Collections.unmodifiableList(screenRows)
    override val size: Int get() = history.rows.size + screenRows.size

    override fun get(index: Int): TerminalEmulator.RenderedRow {
        if (index !in 0 until size) throw IndexOutOfBoundsException("row $index, size $size")
        return if (index < history.rows.size) history.rows[index] else screenRows[index - history.rows.size]
    }

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other is TerminalFrameRows && history === other.history -> screenRows == other.screenRows
        else -> super.equals(other)
    }

    override fun hashCode(): Int = super.hashCode()
}

/**
 * Only emulator-owned rows may prove a FIFO overlap. RenderFrame.copy with replaced rows, IDs or
 * identity metadata deliberately loses this proof instead of trusting plausible-looking counters.
 */
internal fun TerminalEmulator.RenderFrame.ownedRows(): TerminalFrameRows? {
    val owned = rows as? TerminalFrameRows ?: return null
    val history = owned.history
    return owned.takeIf {
        historyCount == history.rows.size && screenStartRow == historyCount &&
            historyStartSequence == history.firstSequence.takeIf { historyCount > 0 } &&
            historyGeneration == history.generation && historyRenderRevision == history.renderRevision &&
            historyLineIds === history.lineIds && screenLineIds === owned.screenLineIds &&
            screenGeneration == owned.screenGeneration
    }
}
