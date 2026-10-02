package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import me.rerere.rikkahub.data.container.TerminalItemViewport
import me.rerere.rikkahub.data.container.TerminalVisibleRow
import me.rerere.rikkahub.utils.TerminalEmulator

internal const val TERMINAL_LAZY_SCREEN_KEY = "terminal-active-screen"
internal const val TERMINAL_LAZY_TAIL_KEY = "terminal-tail"

/** A render/metric identity, not a structural equality comparison of thousands of rows. */
internal class TerminalLazyLayoutPass(val frame: TerminalEmulator.RenderFrame, val metricKey: Any)

/**
 * Measures only composed rows. Measurements are discarded on disposal and stamped by layout pass,
 * including same-height text/font changes. onSizeChanged alone cannot stamp an unchanged height.
 * Read LazyList's current offsets synchronously after a consumed drag, not last frame's positions.
 * This adapter neither publishes viewport intent nor writes the scroll state.
 */
internal class TerminalLazyItemMeasurements {
    private data class Height(val pass: TerminalLazyLayoutPass, val pixels: Int)
    // This is UI-thread layout bookkeeping, not UI state. A Compose state map here caused writes
    // from measure to invalidate the same LazyColumn that snapshotFlow was reading.
    private val heights = mutableMapOf<Long, Height>()
    private var measurementVersion by mutableIntStateOf(0)
    val retainedRows: Int get() = heights.size

    @Composable
    fun Row(pass: TerminalLazyLayoutPass, row: TerminalRenderedRowState, style: TextStyle) {
        DisposableEffect(pass, row.lineId) {
            onDispose {
                if (heights[row.lineId]?.pass === pass) {
                    heights.remove(row.lineId)
                    measurementVersion++
                }
            }
        }
        Box(Modifier.onSizeChanged { size ->
            val height = size.height
            if (height > 0 && heights[row.lineId] != Height(pass, height)) {
                heights[row.lineId] = Height(pass, height)
                measurementVersion++
            }
        }) {
            TerminalRenderedRows(listOf(row), style)
        }
    }

    fun read(pass: TerminalLazyLayoutPass, state: LazyListState, cellHeightPx: Int, tailPaddingPx: Int): TerminalItemViewport? {
        // Subscribe snapshotFlow to layout callbacks without making the height map observable.
        measurementVersion
        val frame = pass.frame
        if (frame.isAlternateScreen || frame.historyCount !in 0..frame.rows.size ||
            frame.historyLineIds.size != frame.historyCount ||
            frame.screenLineIds.size != frame.rows.size - frame.historyCount || cellHeightPx <= 0
        ) return null
        val info = state.layoutInfo
        if (info.totalItemsCount != frame.historyCount + 2 || info.viewportSize.height <= 0) return null
        val rows = mutableListOf<TerminalVisibleRow>()
        for (item in info.visibleItemsInfo) {
            if (item.index < frame.historyCount) {
                val id = frame.historyLineIds[item.index]
                if (item.key != id) return null // Stable key has not yet moved after FIFO head trim.
                val measured = heights[id] ?: return null
                if (measured.pass !== pass || measured.pixels != item.size || item.size <= 0) return null
                rows += TerminalVisibleRow(item.index, item.offset - info.viewportStartOffset, item.size)
            } else if (item.index == frame.historyCount) {
                if (item.key != TERMINAL_LAZY_SCREEN_KEY) return null
                val measured = frame.screenLineIds.map { id ->
                    heights[id]?.takeIf { it.pass === pass && it.pixels > 0 }?.pixels ?: return null
                }
                if (measured.sum() != item.size) return null
                var top = item.offset - info.viewportStartOffset
                measured.forEachIndexed { index, height ->
                    rows += TerminalVisibleRow(frame.historyCount + index, top, height)
                    top += height
                }
            } else if (item.index != frame.historyCount + 1 || item.key != TERMINAL_LAZY_TAIL_KEY) return null
        }
        if (rows.isEmpty()) return null
        // LazyList has no content padding in this adapter; the fixed status strip is its sibling.
        return TerminalItemViewport(frame, pass.metricKey, rows, info.viewportSize.height,
            cellHeightPx, tailPaddingPx, state.canScrollBackward, state.canScrollForward)
    }
}
