package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator

/** Text bounds intentionally exclude blank cells; viewport following must also reveal a visible
 * cursor on a blank row (print/newline/read). Keep hidden cursors and unused grid blanks excluded. */
internal fun TerminalEmulator.RenderFrame.terminalFollowRowIndex(): Int? {
    val text = contentBounds.lastNonBlankRow?.takeIf { it in rows.indices } ?: -1
    val cursor = if (cursorVisible && cursorRow in screenLineIds.indices &&
        screenStartRow in 0..rows.size && cursorRow < rows.size - screenStartRow
    ) screenStartRow + cursorRow else -1
    return maxOf(text, cursor).takeIf { it >= 0 }
}

/** Active screen extent remains screen-relative even when its preceding history is much taller. */
internal fun TerminalEmulator.RenderFrame.terminalActiveBottomRow(preservePhysicalGrid: Boolean): Int? =
    if (preservePhysicalGrid) screenLineIds.indices.lastOrNull() else {
        maxOf(screenContentBounds.lastNonBlankRow?.takeIf { it in screenLineIds.indices } ?: -1,
            cursorRow.takeIf { cursorVisible && it in screenLineIds.indices } ?: -1).takeIf { it >= 0 }
    }

internal fun terminalImeAnchorScrollTarget(
    lastNonBlankRow: Int?,
    terminalCellHeightPx: Int,
    viewportHeightPx: Int,
    terminalTailPaddingPx: Int,
    maxScrollPx: Int,
): Int {
    if (lastNonBlankRow == null || viewportHeightPx <= 0) return 0
    val lastContentBottomPx = (lastNonBlankRow + 1) * terminalCellHeightPx + terminalTailPaddingPx
    return (lastContentBottomPx - viewportHeightPx).coerceIn(0, maxScrollPx)
}

internal fun terminalTuiViewportScrollTarget(
    screenStartRow: Int,
    lastActiveScreenRow: Int?,
    terminalCellHeightPx: Int,
    viewportHeightPx: Int,
    terminalTailPaddingPx: Int,
    maxScrollPx: Int,
): Int {
    val screenStartPx = screenStartRow * terminalCellHeightPx
    if (lastActiveScreenRow == null || viewportHeightPx <= 0) {
        return screenStartPx.coerceIn(0, maxScrollPx)
    }
    val activeBottomPx = screenStartPx +
        (lastActiveScreenRow + 1) * terminalCellHeightPx + terminalTailPaddingPx
    return maxOf(screenStartPx, activeBottomPx - viewportHeightPx).coerceIn(0, maxScrollPx)
}

internal fun terminalEffectiveScreenBottomRow(
    screenContentBounds: TerminalEmulator.ContentBounds,
    cursorRow: Int,
    cursorVisible: Boolean,
): Int? = listOfNotNull(
    screenContentBounds.lastNonBlankRow,
    cursorRow.takeIf { cursorVisible },
).maxOrNull()

