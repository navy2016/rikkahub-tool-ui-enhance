package me.rerere.rikkahub.ui.pages.container

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import me.rerere.rikkahub.data.container.buildLineIdsFromFrame
import me.rerere.rikkahub.utils.TerminalEmulator

internal const val TERMINAL_GRID_CLEAR_GRACE_MS = 50L
internal const val TERMINAL_GRID_STABLE_BOTTOM_ROWS = 8

@Stable
internal class TerminalRenderedRowState(
    val lineId: Long,
    initialText: AnnotatedString,
) {
    var text by mutableStateOf(initialText)
    var pendingBlankSinceMs: Long = 0L
}

internal fun createTerminalRenderedRows(
    frame: TerminalEmulator.RenderFrame,
): SnapshotStateList<TerminalRenderedRowState> = mutableStateListOf<TerminalRenderedRowState>().apply {
    val lineIds = buildLineIdsFromFrame(frame, frame.rows.size)
    addAll(frame.rows.mapIndexed { index, row ->
        TerminalRenderedRowState(
            lineId = lineIds.getOrNull(index) ?: Long.MIN_VALUE + index,
            initialText = row.text,
        )
    })
}

/**
 * Shared by the terminal and the isolated rendering benchmark. The caller owns the mutable snapshot
 * so row updates and viewport metadata are still published atomically. Returns whether a transient
 * TUI blank needs a later commit. This deliberately preserves the existing eager row-list algorithm.
 */
internal fun synchronizeTerminalRenderedRows(
    terminalRenderedRows: SnapshotStateList<TerminalRenderedRowState>,
    frame: TerminalEmulator.RenderFrame,
    usesTuiViewport: Boolean,
    forcePendingGridBlanks: Boolean = false,
    nowMs: Long,
): Boolean {
    val rendered = frame.rows
    val nextLineIds = buildLineIdsFromFrame(frame, rendered.size).ifEmpty {
        rendered.indices.map { index -> Long.MIN_VALUE + index }
    }
    val idsChanged = terminalRenderedRows.size != rendered.size ||
        terminalRenderedRows.indices.any { terminalRenderedRows[it].lineId != nextLineIds[it] }
    if (idsChanged) {
        // Scrollback trim shifts every positional row. Reuse state by stable ID so a row's
        // pending blank grace period and Compose slot follow the row, not its old index.
        val existing = terminalRenderedRows.associateBy { it.lineId }
        terminalRenderedRows.clear()
        rendered.forEachIndexed { index, row ->
            terminalRenderedRows.add(
                existing[nextLineIds[index]] ?: TerminalRenderedRowState(nextLineIds[index], row.text)
            )
        }
    }
    var hasDeferredGridBlank = false
    val stableGridStart = (rendered.size - TERMINAL_GRID_STABLE_BOTTOM_ROWS).coerceAtLeast(0)
    for (index in rendered.indices) {
        val rowState = terminalRenderedRows[index]
        val next = rendered[index].text
        val deferTransientGridClear = usesTuiViewport &&
            index >= stableGridStart &&
            rowState.text.text.isNotBlank() &&
            next.text.isBlank()
        if (deferTransientGridClear && !forcePendingGridBlanks) {
            if (rowState.pendingBlankSinceMs == 0L) rowState.pendingBlankSinceMs = nowMs
            if (nowMs - rowState.pendingBlankSinceMs < TERMINAL_GRID_CLEAR_GRACE_MS) {
                hasDeferredGridBlank = true
                continue
            }
        } else if (!next.text.isBlank()) {
            rowState.pendingBlankSinceMs = 0L
        }
        if (rowState.text != next) rowState.text = next
        rowState.pendingBlankSinceMs = 0L
    }
    return hasDeferredGridBlank
}

/** Eager physical rows; the parent owns scrolling, selection and all viewport coordinates. */
@Composable
internal fun TerminalRenderedRows(
    rows: List<TerminalRenderedRowState>,
    style: TextStyle,
) {
    if (rows.isEmpty()) {
        Text(text = "等待输出...", style = style, softWrap = false, maxLines = 1)
    } else {
        rows.forEach { row ->
            key(row.lineId) {
                TerminalRenderedRow(state = row, style = style)
            }
        }
    }
}

@Composable
private fun TerminalRenderedRow(
    state: TerminalRenderedRowState,
    style: TextStyle,
) {
    Text(text = state.text, style = style, softWrap = false, maxLines = 1)
}
