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
 * TUI blank needs a later commit. Stable row states and their pending blank grace survive structural updates.
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
    // SnapshotStateList.toList() is an O(1), immutable view. Read the list's snapshot record once
    // rather than once per row, and retain it while applying any structural changes below.
    val previousRows = terminalRenderedRows.toList()
    val idsChanged = previousRows.size != rendered.size ||
        previousRows.indices.any { previousRows[it].lineId != nextLineIds[it] }
    if (idsChanged) {
        synchronizeTerminalRowStructure(terminalRenderedRows, previousRows, rendered, nextLineIds)
    }
    val currentRows = if (idsChanged) terminalRenderedRows.toList() else previousRows
    var hasDeferredGridBlank = false
    val stableGridStart = (rendered.size - TERMINAL_GRID_STABLE_BOTTOM_ROWS).coerceAtLeast(0)
    for (index in rendered.indices) {
        val rowState = currentRows[index]
        val next = rendered[index].text
        val previousText = rowState.text
        // Cached history rows usually keep the same AnnotatedString. A pending TUI blank must
        // still run through the grace/force logic, even if this frame reuses an existing text.
        if (previousText === next && rowState.pendingBlankSinceMs == 0L) continue
        val deferTransientGridClear = usesTuiViewport &&
            index >= stableGridStart &&
            previousText.text.isNotBlank() &&
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
        if (previousText != next) rowState.text = next
        rowState.pendingBlankSinceMs = 0L
    }
    return hasDeferredGridBlank
}

/** Only the list structure changes here; the caller updates text within the same mutable snapshot. */
private fun synchronizeTerminalRowStructure(
    rows: SnapshotStateList<TerminalRenderedRowState>,
    previous: List<TerminalRenderedRowState>,
    rendered: List<TerminalEmulator.RenderedRow>,
    nextIds: List<Long>,
) {
    if (nextIds.isEmpty()) {
        rows.clear()
        return
    }
    val retainedStart = previous.indexOfFirst { it.lineId == nextIds.first() }
    if (retainedStart >= 0) {
        val retainedCount = minOf(previous.size - retainedStart, nextIds.size)
        val overlapMatches = (0 until retainedCount).all { previous[retainedStart + it].lineId == nextIds[it] }
        // New emulator line IDs exceed all earlier IDs. Verify that condition rather than assume
        // any suffix is new: a reorder can move an old row from before retainedStart to the tail.
        val maxPreviousId = if (retainedCount < nextIds.size) previous.maxOf { it.lineId } else Long.MAX_VALUE
        val suffixIsNew = (retainedCount until nextIds.size).all { nextIds[it] > maxPreviousId }
        if (overlapMatches && suffixIsNew) {
            // Normal append/trim: only edit the head/tail and retain all surviving row objects.
            // No full ID map, clear/reinsert, or per-row snapshot-list write is needed. ID validation
            // and persistent-list edits still have history-sized work; this is not O(visible rows).
            if (retainedStart > 0) rows.subList(0, retainedStart).clear()
            if (rows.size > retainedCount) rows.subList(retainedCount, rows.size).clear()
            if (nextIds.size > retainedCount) {
                rows.addAll((retainedCount until nextIds.size).map { index ->
                    TerminalRenderedRowState(nextIds[index], rendered[index].text)
                })
            }
            return
        }
    }
    // Reorder/replace/non-monotonic IDs: retain the original stable-ID semantics. Build off-list
    // and publish in bulk, instead of performing up to 10k individual SnapshotStateList.add calls.
    val existing = previous.associateBy { it.lineId }
    val replacement = nextIds.mapIndexed { index, id ->
        existing[id] ?: TerminalRenderedRowState(id, rendered[index].text)
    }
    rows.clear()
    rows.addAll(replacement)
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
