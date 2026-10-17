package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.TerminalHistorySnapshotBlock
import me.rerere.rikkahub.utils.ownedRows

/** History state is allocated by the consumer, never by publication. The active screen remains observable. */
internal class TerminalSparseRows(
    val frame: TerminalEmulator.RenderFrame,
    val screen: List<TerminalRenderedRowState>,
) : AbstractList<TerminalRenderedRowState>(), RandomAccess {
    init { require(screen.size == frame.rows.size - frame.historyCount && frame.ownedRows() != null) }
    override val size: Int get() = frame.rows.size
    override fun get(index: Int): TerminalRenderedRowState {
        if (index !in indices) throw IndexOutOfBoundsException("terminal row $index/$size")
        return if (index < frame.historyCount) {
            TerminalRenderedRowState(frame.historyLineIds[index], frame.rows[index].text)
        } else screen[index - frame.historyCount]
    }
    fun block(index: Int): TerminalHistorySnapshotBlock = requireNotNull(frame.ownedRows()).history.blocks[index]
}

/** Called within the publisher's mutable Snapshot; disposed UI trees never remain in this store. */
internal class TerminalRowStore(initial: TerminalEmulator.RenderFrame, sparse: Boolean = false) {
    private data class State(
        val frame: TerminalEmulator.RenderFrame,
        val mutableRows: SnapshotStateList<TerminalRenderedRowState>,
        val sync: TerminalRenderedRowsSyncState,
        val sparse: Boolean,
        val view: List<TerminalRenderedRowState>,
        val cursors: Map<Long, TerminalCursorState> = emptyMap(),
    )

    private fun physical(frame: TerminalEmulator.RenderFrame): TerminalEmulator.RenderFrame = frame.copy(
        rows = frame.rows.subList(frame.historyCount, frame.rows.size),
        contentBounds = frame.screenContentBounds, screenStartRow = 0, historyCount = 0,
        historyStartId = 0, historyEndId = 0, historyStartSequence = null, historyLineIds = emptyList(),
    )

    private fun initialState(frame: TerminalEmulator.RenderFrame, sparse: Boolean): State {
        val useSparse = sparse && frame.ownedRows() != null
        val content = if (useSparse) physical(frame) else frame
        val rows = createTerminalRenderedRows(content)
        val sync = createTerminalRenderedRowsSyncState(content, rows)
        val view = if (useSparse) TerminalSparseRows(frame, rows.toList()) else rows.toList()
        return State(frame, rows, sync, useSparse, view, cursorStates(frame, view, emptyMap()))
    }

    private var state by mutableStateOf(initialState(initial, sparse), referentialEqualityPolicy())
    val isSparse: Boolean get() = state.sparse
    val retainedRowStates: Int get() = state.mutableRows.size
    val sync: TerminalRenderedRowsSyncState get() = state.sync

    fun view(frame: TerminalEmulator.RenderFrame): List<TerminalRenderedRowState> {
        require(state.frame === frame) { "Terminal row store publication mismatch" }
        return state.view
    }

    fun cursors(frame: TerminalEmulator.RenderFrame): Map<Long, TerminalCursorState> {
        require(state.frame === frame) { "Terminal cursor publication mismatch" }
        return state.cursors
    }

    private fun cursorStates(frame: TerminalEmulator.RenderFrame, rows: List<TerminalRenderedRowState>,
        before: Map<Long, TerminalCursorState>): Map<Long, TerminalCursorState> {
        if (!frame.cursorOverlayMode) return emptyMap()
        val next = LinkedHashMap<Long, TerminalCursorState>(frame.screenLineIds.size)
        frame.screenLineIds.forEachIndexed { index, id ->
            val rowIndex = frame.historyCount + index
            val rendered = frame.rows[rowIndex]
            // A deferred transient clear keeps old Text; do not paint new cursor offsets over it.
            val cursor = rendered.cursorOverlay.takeIf { rows[rowIndex].text == rendered.text }
            val holder = before[id] ?: TerminalCursorState(cursor)
            holder.value = cursor
            next[id] = holder
        }
        return if (before.keys == next.keys && next.all { (id, holder) -> before[id] === holder }) before
            else java.util.Collections.unmodifiableMap(next)
    }

    fun publish(
        frame: TerminalEmulator.RenderFrame, sparse: Boolean, tui: Boolean, force: Boolean, nowMs: Long,
    ): Boolean {
        val before = state
        val useSparse = sparse && frame.ownedRows() != null
        val content = if (useSparse) physical(frame) else frame
        val selected = if (useSparse == before.sparse) before else {
            // A backend transition may reuse screen states (including deferred TUI blanks), never a dead history tree.
            val oldScreen = if (before.sparse) before.mutableRows.toList() else
                before.mutableRows.toList().subList(before.frame.historyCount, before.mutableRows.size)
            val byId = oldScreen.associateBy { it.lineId }
            val rows = mutableStateListOf<TerminalRenderedRowState>()
            rows.addAll(content.rows.mapIndexed { index, row ->
                val id = if (index < content.historyCount) content.historyLineIds[index]
                    else content.screenLineIds[index - content.historyCount]
                byId[id] ?: TerminalRenderedRowState(id, row.text)
            })
            State(frame, rows, createTerminalRenderedRowsSyncState(content, rows), useSparse, rows.toList())
        }
        val deferred = synchronizeTerminalRenderedRows(selected.mutableRows, content, tui,
            forcePendingGridBlanks = force, nowMs = nowMs, syncState = selected.sync)
        val view = selected.mutableRows.toList()
        val publishedView = if (useSparse) TerminalSparseRows(frame, view) else view
        state = State(frame, selected.mutableRows, selected.sync, useSparse, publishedView,
            cursorStates(frame, publishedView, before.cursors))
        return deferred
    }
}
