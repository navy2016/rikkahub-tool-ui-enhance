package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator

/** Exact prefix of measured eager Text heights. Used only while handing off from/to virtual history. */
internal class TerminalEagerViewportGeometry(val frame: TerminalEmulator.RenderFrame, heights: IntArray) {
    private val tops = IntArray(heights.size + 1)
    init {
        require(heights.isNotEmpty() && heights.size == frame.rows.size && heights.all { it > 0 })
        heights.forEachIndexed { index, height -> tops[index + 1] = Math.addExact(tops[index], height) }
    }
    val contentHeightPx: Int get() = tops.last()
    fun height(index: Int): Int = tops[index + 1] - tops[index]
    fun bottom(index: Int): Int = tops[index + 1]
    fun capture(scrollPx: Int): TerminalItemScrollTarget.Anchor {
        val position = scrollPx.coerceIn(0, (contentHeightPx - 1).coerceAtLeast(0))
        var lo = 0
        var hi = frame.rows.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= position) lo = mid else hi = mid - 1
        }
        val id = if (lo < frame.historyCount) frame.historyLineIds[lo] else frame.screenLineIds[lo - frame.historyCount]
        return TerminalItemScrollTarget.Anchor(ViewportAnchor(id, position - tops[lo],
            frame.screenGeneration.takeIf { lo >= frame.historyCount },
            frame.historyGeneration.takeIf { lo < frame.historyCount }), height(lo))
    }
    fun target(anchor: ViewportAnchor, capturedRowHeightPx: Int, lookup: TerminalViewportLineLookup): Int? {
        val resolved = resolveTerminalItemAnchor(frame, anchor, lookup) ?: return null
        val index = lookup.find(frame, frame.rows.size, resolved.lineId)
        if (index < 0) return null
        return tops[index] + terminalScaledItemClip(resolved.clippedTopPx, capturedRowHeightPx, height(index))
    }
}
