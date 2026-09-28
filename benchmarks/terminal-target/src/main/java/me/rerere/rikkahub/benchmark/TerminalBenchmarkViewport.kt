package me.rerere.rikkahub.benchmark

import android.os.Trace
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedTranscript
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptCompositionObserver
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRows

/** Non-observable, main-thread counters; probes must not themselves trigger recomposition. */
internal class LazyCompositionStats : TerminalTranscriptCompositionObserver {
    var historyRows = 0
    var historyChunks = 0
    var activeGrids = 0

    override fun historyChunkDelta(chunks: Int, rows: Int) {
        historyChunks += chunks
        historyRows += rows
        Trace.setCounter("Terminal.eagerHistoryChunks", historyChunks.toLong())
    }

    override fun activeScreenDelta(screens: Int) { activeGrids += screens }
}

@Composable
internal fun TerminalBenchmarkViewport(
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

    // Bounded LazyColumn replaces verticalScroll; it is never nested inside a vertical scroller.
    LazyColumn(modifier = modifier.horizontalScroll(horizontalScroll), state = lazyScroll) {
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
