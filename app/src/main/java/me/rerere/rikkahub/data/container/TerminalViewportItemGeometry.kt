package me.rerere.rikkahub.data.container

/** A measured rendered item in the same absolute content coordinate space as the viewport. */
internal data class TerminalMeasuredViewportItem(
    val lineId: Long,
    val topPx: Int,
    val heightPx: Int,
    val screenGeneration: Long? = null,
    val historyGeneration: Long? = null,
) {
    init {
        require(heightPx > 0)
    }

    val bottomPx: Int get() = topPx + heightPx
}

/** The visible item at the viewport's top edge, including its real clipped pixel offset. */
internal data class TerminalViewportItemAnchor(
    val lineId: Long,
    val clippedTopPx: Int,
    val screenGeneration: Long?,
    val historyGeneration: Long?,
)

/**
 * Captures the top-edge item from measured layout data. The caller supplies absolute content tops;
 * for LazyColumn these come from the current visible item offset plus the known content origin, not
 * from an assumed cell height. The item remains valid when preceding items have different heights.
 */
internal fun captureMeasuredViewportAnchor(
    items: List<TerminalMeasuredViewportItem>,
    scrollPx: Int,
): TerminalViewportItemAnchor? {
    if (items.isEmpty()) return null
    val scroll = scrollPx.coerceAtLeast(0)
    val item = items.lastOrNull { it.topPx <= scroll } ?: items.first()
    val clippedTopPx = (scroll - item.topPx).coerceIn(0, item.heightPx - 1)
    return TerminalViewportItemAnchor(
        lineId = item.lineId,
        clippedTopPx = clippedTopPx,
        screenGeneration = item.screenGeneration,
        historyGeneration = item.historyGeneration,
    )
}

/** Reconstructs the absolute scroll offset when the stable item survives a frame update. */
internal fun resolveMeasuredViewportAnchor(
    items: List<TerminalMeasuredViewportItem>,
    anchor: TerminalViewportItemAnchor,
    maxScrollPx: Int,
): Int? {
    val item = items.firstOrNull { it.lineId == anchor.lineId } ?: return null
    if (anchor.screenGeneration != null && anchor.screenGeneration != item.screenGeneration) return null
    if (anchor.historyGeneration != null && anchor.historyGeneration != item.historyGeneration) return null
    return (item.topPx + anchor.clippedTopPx.coerceIn(0, item.heightPx - 1)).coerceIn(0, maxScrollPx)
}
