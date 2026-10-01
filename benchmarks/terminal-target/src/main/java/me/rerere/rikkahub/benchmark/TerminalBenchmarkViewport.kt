package me.rerere.rikkahub.benchmark

import android.os.Trace
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedTranscript
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptCompositionObserver
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRows

/** Non-observable, main-thread counters; probes must not themselves trigger recomposition. */
internal class LazyCompositionStats : TerminalTranscriptCompositionObserver {
    var historyRows = 0
    var historyChunks = 0
    var activeGrids = 0
    var measuredHistoryWidths = 0L
    var measuredScreenWidths = 0L

    override fun historyChunkDelta(chunks: Int, rows: Int) {
        historyChunks += chunks
        historyRows += rows
        Trace.setCounter("Terminal.eagerHistoryChunks", historyChunks.toLong())
    }

    override fun activeScreenDelta(screens: Int) { activeGrids += screens }
}

@Composable
internal fun TerminalBenchmarkViewport(
    frame: TerminalEmulator.RenderFrame,
    rows: List<TerminalRenderedRowState>,
    layout: TerminalBenchmarkLayout,
    style: TextStyle,
    verticalScroll: ScrollState,
    horizontalScroll: ScrollState,
    lazyScroll: LazyListState,
    stats: LazyCompositionStats,
    modifier: Modifier = Modifier,
) {
    if (layout.useChunkedHistory) {
        // EXACT production grouping/Text, not a benchmark-local clone. Only layers differ between
        // these two arms. All rows remain eager and ScrollState owns actual measured geometry.
        Column(modifier.horizontalScroll(horizontalScroll).verticalScroll(verticalScroll)) {
            TerminalRenderedTranscript(
                rows = rows,
                style = style,
                historyChunks = layout.historyChunks,
                isolateChunkDrawing = layout.isolateChunkDrawing,
                observer = stats,
            )
            Spacer(Modifier.height(8.dp))
        }
        return
    }

    if (!layout.useLazyHistory) {
        // Unchanged eager backend, including ALL TUI/alternate/full-grid cases in either arm.
        Column(modifier.horizontalScroll(horizontalScroll).verticalScroll(verticalScroll)) {
            TerminalRenderedRows(rows, style)
            Spacer(Modifier.height(8.dp))
        }
        return
    }

    val widthPx = exactTranscriptWidth(frame, style, stats)
    val density = LocalDensity.current
    // Width is independent of which rows happen to be composed. Measure natural Text width once
    // per new row/metric revision; do not compose all history or approximate width from cell counts.
    LazyColumn(modifier = modifier.horizontalScroll(horizontalScroll)
        .widthIn(min = with(density) { widthPx.toDp() }), state = lazyScroll) {
        items(
            count = layout.historyRows,
            key = { index -> rows[index].lineId },
            contentType = { "history" },
        ) { index ->
            val row = rows[index]
            DisposableEffect(row.lineId) {
                stats.historyRows++
                Trace.setCounter("Terminal.lazyHistoryCompositions", stats.historyRows.toLong())
                onDispose {
                    stats.historyRows--
                    Trace.setCounter("Terminal.lazyHistoryCompositions", stats.historyRows.toLong())
                }
            }
            // Reuse the real production Text/row-state implementation, not a lighter replacement.
            TerminalRenderedRows(listOf(row), style)
        }
        item(key = TerminalBenchmarkLayout.ACTIVE_SCREEN_KEY, contentType = "physical-grid") {
            DisposableEffect(Unit) {
                stats.activeGrids++
                onDispose { stats.activeGrids-- }
            }
            // ONE item containing the entire physical screen. Its 24 rows are not lazy items.
            Column {
                TerminalRenderedRows(List(layout.screenRows) { rows[layout.historyRows + it] }, style)
            }
        }
        item(key = TerminalBenchmarkLayout.TAIL_KEY, contentType = "tail") {
            Spacer(Modifier.height(8.dp))
        }
    }
}

private data class WidthMetrics(
    val style: TextStyle,
    val density: Density,
    val direction: LayoutDirection,
    val resolver: androidx.compose.ui.text.font.FontFamily.Resolver,
    val resolvedFonts: List<Any>,
)

@Composable
private fun exactTranscriptWidth(
    frame: TerminalEmulator.RenderFrame,
    style: TextStyle,
    stats: LazyCompositionStats,
): Int {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    val mergedStyle = LocalTextStyle.current.merge(style)
    // TerminalEmulator emits normal/bold × regular/italic spans. Observe each resolved typeface
    // so a delayed font resolution revokes scalar widths, even when the resolver object is stable.
    // Synthetic frames cannot use owned-history reuse and measure their arbitrary spans every time.
    val fonts = listOf(FontWeight.Normal, FontWeight.Bold).flatMap { weight ->
        listOf(FontStyle.Normal, FontStyle.Italic).map { fontStyle ->
            resolver.resolve(mergedStyle.fontFamily, weight, fontStyle,
                mergedStyle.fontSynthesis ?: FontSynthesis.All).value
        }
    }
    val metricKey = WidthMetrics(mergedStyle, density, direction, resolver, fonts)
    val index = remember { TerminalBenchmarkWidthIndex() }
    // No history-sized paragraph cache: scalar history maxima live in the index, not TextMeasurer.
    val measurer = rememberTextMeasurer(cacheSize = 0)
    Trace.beginSection("Terminal.widthIndex")
    return try {
        index.width(frame, metricKey) { text ->
            measurer.measure(text, mergedStyle, softWrap = false, maxLines = 1,
                layoutDirection = direction, density = density, fontFamilyResolver = resolver,
                skipCache = true).size.width
        }.also {
            stats.measuredHistoryWidths += index.lastMeasuredHistoryRows
            stats.measuredScreenWidths += index.lastMeasuredScreenRows
            Trace.setCounter("Terminal.widthHistoryMeasurements", stats.measuredHistoryWidths)
            Trace.setCounter("Terminal.widthCandidates", index.retainedCandidates.toLong())
        }
    } finally {
        Trace.endSection()
    }
}
