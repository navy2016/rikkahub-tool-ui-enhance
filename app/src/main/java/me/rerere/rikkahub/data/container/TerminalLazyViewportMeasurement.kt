package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator

/** The structural item kinds exposed by the history-only LazyColumn. */
internal enum class TerminalLazyViewportItemKind {
    HISTORY,
    ACTIVE_SCREEN,
    TAIL,
}

/** A viewport-relative LazyList item snapshot. [offsetPx] includes the list's viewport origin. */
internal data class TerminalLazyViewportVisibleItem(
    val kind: TerminalLazyViewportItemKind,
    val lineId: Long? = null,
    val offsetPx: Int,
    val heightPx: Int,
) {
    init {
        require(heightPx > 0)
    }
}

/**
 * Measured state produced by [TerminalLazyViewportMeasurementTracker]. A range is only known when
 * LazyList has reached the structural tail; callers must not replace an unknown range with a row
 * count multiplied by a nominal cell height.
 */
internal data class TerminalLazyViewportMeasurement(
    val layout: TerminalLazyViewportLayout,
    val currentScrollPx: Int,
    val maxScrollPx: Int?,
    val generationChanged: Boolean,
)

/**
 * Converts LazyList's viewport-relative item offsets into a stable content coordinate space.
 *
 * The tracker carries forward an absolute top for any item visible in both layouts. If a jump
 * removes all overlap, [setExpectedScrollPx] supplies the coordinate of the new viewport. This
 * makes the fallback explicit instead of silently estimating a prefix from row count or cell
 * height. The tracker is deliberately independent of Compose so it can be exercised on the JVM.
 */
internal class TerminalLazyViewportMeasurementTracker(
    private val tailItemHeightPx: Int,
) {
    init {
        require(tailItemHeightPx > 0)
    }

    private data class ItemKey(
        val kind: TerminalLazyViewportItemKind,
        val lineId: Long?,
    )

    private val previousAbsoluteTops = mutableMapOf<ItemKey, Int>()
    private var currentScrollPx = 0
    private var expectedScrollPx: Int? = null
    private var historyGeneration: Long? = null
    private var screenGeneration: Long? = null

    fun reset(scrollPx: Int = 0) {
        previousAbsoluteTops.clear()
        currentScrollPx = scrollPx.coerceAtLeast(0)
        expectedScrollPx = null
        historyGeneration = null
        screenGeneration = null
    }

    /** Sets the logical content coordinate used by the next disjoint layout observation. */
    fun setExpectedScrollPx(scrollPx: Int) {
        expectedScrollPx = scrollPx.coerceAtLeast(0)
    }

    fun update(
        frame: TerminalEmulator.RenderFrame,
        visibleItems: List<TerminalLazyViewportVisibleItem>,
        screenRowHeights: Map<Long, Int>,
        screenRowsComplete: Boolean,
        viewportStartOffsetPx: Int,
        viewportHeightPx: Int,
        canScrollForward: Boolean,
    ): TerminalLazyViewportMeasurement {
        val historyChanged = historyGeneration != null && historyGeneration != frame.historyGeneration
        val screenChanged = screenGeneration != null && screenGeneration != frame.screenGeneration
        if (historyChanged) {
            previousAbsoluteTops.clear()
            expectedScrollPx = null
        } else if (screenChanged) {
            previousAbsoluteTops.keys.removeAll {
                it.kind == TerminalLazyViewportItemKind.ACTIVE_SCREEN ||
                    it.kind == TerminalLazyViewportItemKind.TAIL
            }
            expectedScrollPx = null
        }
        historyGeneration = frame.historyGeneration
        screenGeneration = frame.screenGeneration

        val scrollPx = expectedScrollPx ?: visibleItems.firstNotNullOfOrNull { item ->
            val key = ItemKey(item.kind, item.lineId)
            previousAbsoluteTops[key]?.let { absoluteTop ->
                absoluteTop - (item.offsetPx - viewportStartOffsetPx)
            }
        } ?: currentScrollPx
        expectedScrollPx = null
        currentScrollPx = scrollPx.coerceAtLeast(0)

        val absoluteItems = visibleItems.map { item ->
            val key = ItemKey(item.kind, item.lineId)
            val absoluteTop = currentScrollPx + item.offsetPx - viewportStartOffsetPx
            previousAbsoluteTops[key] = absoluteTop
            item to absoluteTop
        }
        val tailItem = absoluteItems.firstOrNull { it.first.kind == TerminalLazyViewportItemKind.TAIL }
        val tailBottomPx = tailItem?.second?.plus(tailItem.first.heightPx)
        val measuredRows = absoluteItems.mapNotNull { (item, absoluteTop) ->
            if (item.kind != TerminalLazyViewportItemKind.HISTORY || item.lineId == null) return@mapNotNull null
            TerminalMeasuredViewportItem(
                lineId = item.lineId,
                topPx = absoluteTop,
                heightPx = item.heightPx,
                historyGeneration = frame.historyGeneration,
            )
        }.toMutableList()

        val screenItem = absoluteItems.firstOrNull { it.first.kind == TerminalLazyViewportItemKind.ACTIVE_SCREEN }
        val activeScreenItemTopPx = screenItem?.second
        if (screenItem != null && screenRowsComplete) {
            val measuredScreenRows = frame.screenLineIds.map { lineId ->
                screenRowHeights[lineId]
            }
            val measuredScreenHeight = measuredScreenRows.sumOf { it ?: 0 }
            if (measuredScreenRows.all { it != null } && measuredScreenHeight == screenItem.first.heightPx) {
                var rowTopPx = screenItem.second
                frame.screenLineIds.forEachIndexed { index, lineId ->
                    val heightPx = requireNotNull(measuredScreenRows[index])
                    measuredRows += TerminalMeasuredViewportItem(
                        lineId = lineId,
                        topPx = rowTopPx,
                        heightPx = heightPx,
                        screenGeneration = frame.screenGeneration,
                    )
                    rowTopPx += heightPx
                }
            }
        }

        val layout = TerminalLazyViewportLayout(
            historyLineIds = frame.historyLineIds,
            screenLineIds = frame.screenLineIds,
            historyGeneration = frame.historyGeneration,
            screenGeneration = frame.screenGeneration,
            measuredRows = measuredRows.sortedBy { it.topPx },
            activeScreenItemTopPx = activeScreenItemTopPx,
            tailItemHeightPx = tailItemHeightPx,
            viewportHeightPx = viewportHeightPx,
        )
        return TerminalLazyViewportMeasurement(
            layout = layout,
            currentScrollPx = currentScrollPx,
            maxScrollPx = if (tailBottomPx != null && !canScrollForward) {
                (tailBottomPx - viewportHeightPx).coerceAtLeast(0)
            } else null,
            generationChanged = historyChanged || screenChanged,
        )
    }
}
