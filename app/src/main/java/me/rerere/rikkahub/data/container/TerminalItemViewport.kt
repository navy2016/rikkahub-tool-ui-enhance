package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator

/** Item-native intent. No synthetic total height or prefix-height estimate is a scroll target. */
internal sealed interface TerminalItemScrollTarget {
    data object Top : TerminalItemScrollTarget
    data object Follow : TerminalItemScrollTarget
    data class Anchor(val anchor: ViewportAnchor, val capturedRowHeightPx: Int) : TerminalItemScrollTarget
}

/** Measured row coordinates relative to the visible viewport, NOT absolute transcript pixels. */
internal data class TerminalVisibleRow(val index: Int, val topPx: Int, val heightPx: Int) {
    init { require(index >= 0 && heightPx > 0) }
    val bottomPx: Int get() = topPx + heightPx
}

/**
 * One completed layout, stamped with the exact rendered frame and layout identity. A caller must
 * reject LazyList items whose keys/indices still describe the previous frame. Only currently
 * visible history and (at most) the complete physical screen belong here, not all visited rows.
 * [layoutKey] changes on font/density/direction/backend changes, not ordinary scrolling/output.
 */
internal data class TerminalItemViewport(
    val frame: TerminalEmulator.RenderFrame,
    val layoutKey: Any,
    val rows: List<TerminalVisibleRow>,
    val viewportHeightPx: Int,
    val cellHeightPx: Int,
    val tailPaddingPx: Int,
    val canScrollBackward: Boolean,
    val canScrollForward: Boolean,
) {
    init {
        require(viewportHeightPx >= 0 && cellHeightPx > 0 && tailPaddingPx >= 0)
        require(rows.zipWithNext().all { (a, b) -> a.index < b.index && a.bottomPx <= b.topPx })
        require(rows.all { it.index < frame.rows.size })
    }

    val ready: Boolean get() = !frame.isAlternateScreen && viewportHeightPx > 0 && rows.isNotEmpty() &&
        frame.historyCount in 0..frame.rows.size && frame.screenStartRow == frame.historyCount &&
        frame.historyLineIds.size == frame.historyCount &&
        frame.screenLineIds.size == frame.rows.size - frame.historyCount

    fun lineId(index: Int): Long = if (index < frame.historyCount) frame.historyLineIds[index]
        else frame.screenLineIds[index - frame.historyCount]

    fun anchorAt(row: TerminalVisibleRow, clippedPx: Int): ViewportAnchor = ViewportAnchor(
        lineId(row.index), clippedPx.coerceIn(0, row.heightPx - 1),
        if (row.index >= frame.historyCount) frame.screenGeneration else null,
        if (row.index < frame.historyCount) frame.historyGeneration else null,
    )

    /** Padding below the final row retains the last real row, never invents an extra line ID. */
    fun capture(): TerminalItemScrollTarget.Anchor? {
        if (!ready) return null
        val row = rows.firstOrNull { it.bottomPx > 0 } ?: rows.last()
        return TerminalItemScrollTarget.Anchor(anchorAt(row, -row.topPx), row.heightPx)
    }

    val atTop: Boolean get() = !canScrollBackward

    /** The existing fast-fling tolerance can be proved only when the first row is measured. */
    fun nearTop(): Boolean = atTop || rows.firstOrNull { it.index == 0 }?.let {
        -it.topPx <= TERMINAL_EDGE_THRESHOLD_PX
    } == true

    /** Same semantic content bottom as the eager controller, not necessarily the structural tail. */
    val followRowIndex: Int? get() = frame.contentBounds.lastNonBlankRow?.takeIf { it in frame.rows.indices }

    fun nearFollow(): Boolean {
        if (!ready) return false
        val index = followRowIndex ?: return atTop
        val row = rows.firstOrNull { it.index == index }
        return if (row != null) row.bottomPx + tailPaddingPx - viewportHeightPx <= cellHeightPx * 2
        else rows.first().index > index // Already below the semantic bottom, including blank grid rows.
    }

    fun isSatisfied(target: TerminalItemScrollTarget): Boolean {
        if (!ready) return false
        val delta = when (target) {
            TerminalItemScrollTarget.Top -> return atTop
            TerminalItemScrollTarget.Follow -> {
                val index = followRowIndex ?: return atTop
                val row = rows.firstOrNull { it.index == index } ?: return false
                row.bottomPx + tailPaddingPx - viewportHeightPx
            }
            is TerminalItemScrollTarget.Anchor -> {
                val row = rows.firstOrNull { lineId(it.index) == target.anchor.lineId } ?: return false
                row.topPx + terminalScaledItemClip(target.anchor.clippedTopPx, target.capturedRowHeightPx, row.heightPx)
            }
        }
        // A semantic bottom above the viewport bottom still needs a BACKWARD correction even at
        // the structural end (e.g. an active screen with blank rows). Clamp only in the blocked direction.
        return delta == 0 || (delta < 0 && !canScrollBackward) || (delta > 0 && !canScrollForward)
    }

    // Frame identity is the contract. Do not structurally compare a 10k-row frame on every scroll.
    override fun equals(other: Any?): Boolean = other is TerminalItemViewport && frame === other.frame &&
        layoutKey == other.layoutKey && rows == other.rows && viewportHeightPx == other.viewportHeightPx &&
        cellHeightPx == other.cellHeightPx && tailPaddingPx == other.tailPaddingPx &&
        canScrollBackward == other.canScrollBackward && canScrollForward == other.canScrollForward

    override fun hashCode(): Int = 31 * System.identityHashCode(frame) + rows.hashCode()
}

/** Always scale from the original capture, preventing drift after repeated font-size changes. */
internal fun terminalScaledItemClip(clippedPx: Int, capturedHeightPx: Int, currentHeightPx: Int): Int {
    require(currentHeightPx > 0)
    val originalHeight = capturedHeightPx.takeIf { it > 0 } ?: currentHeightPx
    return ((clippedPx.coerceAtLeast(0).toLong() * currentHeightPx + originalHeight / 2) / originalHeight)
        .coerceIn(0L, currentHeightPx.toLong() - 1).toInt()
}

/** Identity policy shared in spirit with reduceViewport; no cell-height arithmetic is used here. */
internal fun resolveTerminalItemAnchor(
    frame: TerminalEmulator.RenderFrame,
    anchor: ViewportAnchor,
    lookup: TerminalViewportLineLookup,
): ViewportAnchor? {
    val index = lookup.find(frame, frame.rows.size, anchor.lineId)
    val historyChanged = anchor.screenGeneration == null && anchor.historyGeneration != null &&
        anchor.historyGeneration != frame.historyGeneration
    if (index >= 0) {
        if (index < frame.historyCount) {
            if (historyChanged) return null
            return anchor.copy(screenGeneration = null, historyGeneration = frame.historyGeneration)
        }
        if (anchor.historyGeneration != null ||
            (anchor.screenGeneration != null && anchor.screenGeneration != frame.screenGeneration)
        ) return null
        return anchor.copy(screenGeneration = frame.screenGeneration, historyGeneration = null)
    }
    if (anchor.screenGeneration != null || historyChanged) return null
    val replacement = nearestHistoryLineId(frame.historyLineIds, anchor.lineId) ?: return null
    return anchor.copy(lineId = replacement, screenGeneration = null, historyGeneration = frame.historyGeneration)
}
