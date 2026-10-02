package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.rerere.rikkahub.data.container.TerminalEagerViewportGeometry
import me.rerere.rikkahub.data.container.TerminalItemViewport
import me.rerere.rikkahub.data.container.TerminalVisibleRow
import me.rerere.rikkahub.utils.TerminalEmulator

internal const val TERMINAL_LAZY_SCREEN_KEY = "terminal-active-screen"
internal const val TERMINAL_LAZY_TAIL_KEY = "terminal-tail"
internal class TerminalLazyLayoutPass(val frame: TerminalEmulator.RenderFrame, val metricKey: Any)

/**
 * UI-thread bookkeeping, not Compose state. Publishing a conflated notification never subscribes
 * a measure block to its own writes. Ownership tokens protect a row archiving/recomposing while its
 * old node is disposed. Text and font metrics, not frame revisions, prove height reuse.
 */
internal class TerminalLazyItemMeasurements {
    private data class Height(val owner: Any, val metricKey: Any, val text: AnnotatedString, val pixels: Int)
    private val heights = mutableMapOf<Long, Height>()
    private val mutableChanges = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = mutableChanges.asSharedFlow()
    private var generation = 0L
    private var eagerCache: TerminalEagerViewportGeometry? = null
    private var eagerGeneration = -1L
    private var eagerMetricKey: Any? = null
    val retainedRows: Int get() = heights.size

    private fun changed() {
        generation++
        eagerCache = null
        mutableChanges.tryEmit(Unit)
    }

    @Composable
    fun Row(pass: TerminalLazyLayoutPass, row: TerminalRenderedRowState, style: TextStyle) {
        val token = remember(row.lineId, pass.metricKey) { Any() }
        val text = row.text
        DisposableEffect(token, row.lineId) {
            onDispose {
                if (heights[row.lineId]?.owner === token) {
                    heights.remove(row.lineId)
                    changed()
                }
            }
        }
        Box(Modifier.layout { measurable, constraints ->
            val child = measurable.measure(constraints)
            val measured = Height(token, pass.metricKey, text, child.height)
            if (heights[row.lineId] != measured) {
                heights[row.lineId] = measured
                changed()
            }
            layout(child.width, child.height) { child.place(0, 0) }
        }) { TerminalRenderedRow(row, style) }
    }

    private fun validFrame(frame: TerminalEmulator.RenderFrame): Boolean =
        frame.historyCount in 0..frame.rows.size && frame.screenStartRow == frame.historyCount &&
            frame.historyLineIds.size == frame.historyCount &&
            frame.screenLineIds.size == frame.rows.size - frame.historyCount

    private fun height(pass: TerminalLazyLayoutPass, index: Int): Int? {
        val frame = pass.frame
        val id = if (index < frame.historyCount) frame.historyLineIds[index]
            else frame.screenLineIds[index - frame.historyCount]
        return heights[id]?.takeIf {
            it.metricKey == pass.metricKey && it.text == frame.rows[index].text && it.pixels > 0
        }?.pixels
    }

    fun read(pass: TerminalLazyLayoutPass, state: LazyListState, cellHeightPx: Int, tailPaddingPx: Int): TerminalItemViewport? {
        val frame = pass.frame
        if (!validFrame(frame) || frame.isAlternateScreen || cellHeightPx <= 0 || tailPaddingPx < 0) return null
        val info = state.layoutInfo
        if (info.totalItemsCount != frame.historyCount + 2 || info.viewportSize.height <= 0) return null
        val rows = mutableListOf<TerminalVisibleRow>()
        for (item in info.visibleItemsInfo) {
            if (item.index < 0) return null
            if (item.index < frame.historyCount) {
                if (item.key != frame.historyLineIds[item.index] || height(pass, item.index) != item.size) return null
                rows += TerminalVisibleRow(item.index, item.offset - info.viewportStartOffset, item.size)
            } else if (item.index == frame.historyCount) {
                if (item.key != TERMINAL_LAZY_SCREEN_KEY) return null
                var top = item.offset - info.viewportStartOffset
                val screenTop = top
                for (index in frame.historyCount until frame.rows.size) {
                    val pixels = height(pass, index) ?: return null
                    rows += TerminalVisibleRow(index, top, pixels)
                    top += pixels
                }
                if (top - screenTop != item.size) return null
            } else if (item.index != frame.historyCount + 1 || item.key != TERMINAL_LAZY_TAIL_KEY) return null
        }
        if (rows.isEmpty() || rows.zipWithNext().any { (a, b) -> a.index >= b.index || a.bottomPx > b.topPx }) return null
        return TerminalItemViewport(frame, pass.metricKey, rows, info.viewportSize.height,
            cellHeightPx, tailPaddingPx, state.canScrollBackward, state.canScrollForward)
    }

    /** Only used for a renderer handoff/fallback, never adds an O(H) cache to the default renderer. */
    fun readEager(pass: TerminalLazyLayoutPass): TerminalEagerViewportGeometry? {
        val frame = pass.frame
        if (!validFrame(frame) || frame.rows.isEmpty()) return null
        eagerCache?.takeIf { it.frame === frame && eagerGeneration == generation && eagerMetricKey == pass.metricKey }
            ?.let { return it }
        val rowHeights = IntArray(frame.rows.size)
        for (index in rowHeights.indices) rowHeights[index] = height(pass, index) ?: return null
        return TerminalEagerViewportGeometry(frame, rowHeights).also {
            eagerGeneration = generation
            eagerMetricKey = pass.metricKey
            eagerCache = it
        }
    }
}

internal class TerminalRowMeasurementScope(val pass: TerminalLazyLayoutPass, val measurements: TerminalLazyItemMeasurements)
