package me.rerere.rikkahub.data.container

/**
 * Measured layout information needed to address a history-only LazyColumn.
 *
 * History rows are individual items. The active physical screen is intentionally one item, so its
 * internal measured rows are carried separately and can be addressed without splitting the grid
 * into lazy items. Tail is a small spacer item after that screen item.
 */
internal data class TerminalLazyViewportLayout(
    val historyLineIds: List<Long>,
    val screenLineIds: List<Long>,
    val historyGeneration: Long,
    val screenGeneration: Long,
    val measuredRows: List<TerminalMeasuredViewportItem>,
    val activeScreenItemTopPx: Int? = null,
    val tailItemHeightPx: Int,
    val viewportHeightPx: Int,
) {
    init {
        require(historyLineIds.distinct().size == historyLineIds.size)
        require(screenLineIds.distinct().size == screenLineIds.size)
        require(tailItemHeightPx > 0)
        require(viewportHeightPx >= 0)
    }

    val activeScreenItemIndex: Int get() = historyLineIds.size
    val tailItemIndex: Int get() = historyLineIds.size + 1
}

/** `scrollToItem` target. Positive offset places the item that many pixels above the viewport top. */
internal data class TerminalLazyViewportScrollTarget(
    val itemIndex: Int,
    val itemScrollOffsetPx: Int,
)

/**
 * Maps the controller's semantic locked anchor to a LazyColumn item target. A history row is the
 * item itself; a screen row is offset inside the one complete active-screen item. No estimated
 * prefix height is used, so variable-height rows do not change the target semantics.
 */
internal fun terminalLazyTargetForAnchor(
    layout: TerminalLazyViewportLayout,
    anchor: ViewportAnchor,
): TerminalLazyViewportScrollTarget? {
    if (anchor.screenGeneration != null && anchor.screenGeneration != layout.screenGeneration) return null
    if (anchor.historyGeneration != null && anchor.historyGeneration != layout.historyGeneration) return null
    val historyIndex = layout.historyLineIds.indexOf(anchor.lineId)
    if (historyIndex >= 0) {
        val measured = layout.measuredRows.firstOrNull { it.lineId == anchor.lineId }
        val clippedTopPx = measured?.let {
            anchor.clippedTopPx.coerceIn(0, it.heightPx - 1)
        } ?: anchor.clippedTopPx.coerceAtLeast(0)
        return TerminalLazyViewportScrollTarget(
            itemIndex = historyIndex,
            itemScrollOffsetPx = clippedTopPx,
        )
    }

    val measured = layout.measuredRows.firstOrNull { it.lineId == anchor.lineId } ?: return null
    if (measured.screenGeneration != anchor.screenGeneration) return null
    if (layout.screenLineIds.indexOf(anchor.lineId) < 0) return null
    val screenTop = layout.activeScreenItemTopPx ?: return null
    val rowOffset = measured.topPx - screenTop
    if (rowOffset < 0) return null
    return TerminalLazyViewportScrollTarget(
        itemIndex = layout.activeScreenItemIndex,
        itemScrollOffsetPx = rowOffset + anchor.clippedTopPx.coerceIn(0, measured.heightPx - 1),
    )
}

/** Places the bottom of the tail spacer at the viewport bottom. LazyList clamps short content. */
internal fun terminalLazyTargetForBottom(
    layout: TerminalLazyViewportLayout,
): TerminalLazyViewportScrollTarget = TerminalLazyViewportScrollTarget(
    itemIndex = layout.tailItemIndex,
    itemScrollOffsetPx = layout.tailItemHeightPx - layout.viewportHeightPx,
)

internal fun terminalLazyTargetForTop(
    layout: TerminalLazyViewportLayout,
): TerminalLazyViewportScrollTarget = TerminalLazyViewportScrollTarget(
    itemIndex = 0,
    itemScrollOffsetPx = 0,
)
