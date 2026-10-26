package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import me.rerere.rikkahub.data.container.TerminalPhysicalRowHeights
import me.rerere.rikkahub.utils.TerminalEmulator

/** Only current physical rows, never history, retain Text keys. No offscreen layout object is cached. */
private class TerminalPhysicalMeasurementCache {
    data class Entry(val text: AnnotatedString, val height: Int)
    var rows = emptyMap<Long, Entry>()
    var result: TerminalPhysicalRowHeights? = null
}

@Composable
internal fun rememberTerminalPhysicalMeasurements(frame: TerminalEmulator.RenderFrame,
    rows: List<TerminalRenderedRowState>, style: TextStyle, metrics: Any): TerminalPhysicalRowHeights {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val cache = remember(metrics) { TerminalPhysicalMeasurementCache() }
    val next = HashMap<Long, TerminalPhysicalMeasurementCache.Entry>(frame.screenLineIds.size)
    val heights = IntArray(frame.screenLineIds.size) { index ->
        val id = frame.screenLineIds[index]
        val text = rows[frame.historyCount + index].text
        val old = cache.rows[id]?.takeIf { it.text == text }
        val value = old ?: TerminalPhysicalMeasurementCache.Entry(text,
            measurer.measure(text, style, softWrap = false, maxLines = 1).size.height.coerceAtLeast(1))
        next[id] = value
        value.height
    }
    val value = TerminalPhysicalRowHeights(heights)
    val result = cache.result?.takeIf { it == value } ?: value
    SideEffect { cache.rows = next; cache.result = result }
    return result
}
