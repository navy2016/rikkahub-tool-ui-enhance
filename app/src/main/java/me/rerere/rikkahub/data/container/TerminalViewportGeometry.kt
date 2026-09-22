package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator

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

