package me.rerere.rikkahub.utils

import java.util.Collections
import java.util.RandomAccess

/** Owned, immutable history. Never retains mutable emulator cells or the scrollback deque. */
internal class TerminalHistorySnapshot(
    val owner: Any,
    val generation: Long,
    val renderRevision: Long,
    val firstSequence: Long,
    rows: List<TerminalEmulator.RenderedRow>,
    lineIds: List<Long>,
    val contentBounds: TerminalEmulator.ContentBounds,
) {
    // The builder relinquishes both lists. Wrappers also reject accidental Java/MutableList writes.
    val rows: List<TerminalEmulator.RenderedRow> = Collections.unmodifiableList(rows)
    val lineIds: List<Long> = Collections.unmodifiableList(lineIds)
    val endSequence: Long get() = firstSequence + rows.size
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
