package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator

/** Immutable scalar prefix. Screen updates may share the historical prefix without copying it. */
internal class TerminalMeasuredHeightPrefix private constructor(private val tops: IntArray) {
    val size: Int get() = tops.size - 1
    val totalHeightPx: Int get() = tops.last()
    fun offset(index: Int): Int = tops[index]

    companion object {
        fun fromHeights(heights: IntArray, start: Int, end: Int): TerminalMeasuredHeightPrefix {
            require(start in 0..end && end <= heights.size)
            return requireNotNull(measure(end - start) { heights[start + it] })
        }

        /** Missing or nonpositive measurements do not publish a partial prefix. */
        fun measure(size: Int, readHeight: (Int) -> Int?): TerminalMeasuredHeightPrefix? {
            require(size >= 0)
            val tops = IntArray(size + 1)
            for (index in 0 until size) {
                val height = readHeight(index)?.takeIf { it > 0 } ?: return null
                tops[index + 1] = Math.addExact(tops[index], height)
            }
            return TerminalMeasuredHeightPrefix(tops)
        }
    }
}

/** Exact measured eager geometry. History and screen are immutable, independently sized prefixes. */
internal class TerminalEagerViewportGeometry(
    val frame: TerminalEmulator.RenderFrame,
    val historyPrefix: TerminalMeasuredHeightPrefix,
    private val screenPrefix: TerminalMeasuredHeightPrefix,
) {
    constructor(frame: TerminalEmulator.RenderFrame, heights: IntArray) : this(
        frame,
        TerminalMeasuredHeightPrefix.fromHeights(heights, 0, frame.historyCount),
        TerminalMeasuredHeightPrefix.fromHeights(heights, frame.historyCount, heights.size),
    )

    init {
        require(frame.rows.isNotEmpty() && historyPrefix.size == frame.historyCount &&
            historyPrefix.size + screenPrefix.size == frame.rows.size)
    }
    val contentHeightPx: Int = Math.addExact(historyPrefix.totalHeightPx, screenPrefix.totalHeightPx)

    private fun offset(index: Int): Int = if (index < historyPrefix.size) historyPrefix.offset(index)
        else historyPrefix.totalHeightPx + screenPrefix.offset(index - historyPrefix.size)

    fun height(index: Int): Int = offset(index + 1) - offset(index)
    fun bottom(index: Int): Int = offset(index + 1)
    fun capture(scrollPx: Int): TerminalItemScrollTarget.Anchor {
        val position = scrollPx.coerceIn(0, (contentHeightPx - 1).coerceAtLeast(0))
        var lo = 0
        var hi = frame.rows.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (offset(mid) <= position) lo = mid else hi = mid - 1
        }
        val id = if (lo < frame.historyCount) frame.historyLineIds[lo] else frame.screenLineIds[lo - frame.historyCount]
        return TerminalItemScrollTarget.Anchor(ViewportAnchor(id, position - offset(lo),
            frame.screenGeneration.takeIf { lo >= frame.historyCount },
            frame.historyGeneration.takeIf { lo < frame.historyCount }), height(lo))
    }
    fun target(anchor: ViewportAnchor, capturedRowHeightPx: Int, lookup: TerminalViewportLineLookup): Int? {
        val resolved = resolveTerminalItemAnchor(frame, anchor, lookup) ?: return null
        val index = lookup.find(frame, frame.rows.size, resolved.lineId)
        if (index < 0) return null
        return offset(index) + terminalScaledItemClip(resolved.clippedTopPx, capturedRowHeightPx, height(index))
    }
}
