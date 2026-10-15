package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.utils.TerminalEmulator

/**
 * Production virtual-history surface. The active physical screen remains one LazyColumn item;
 * stable line IDs are the only history keys. Selection/mouse callers deliberately stay outside
 * this composable and use the existing eager path.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TerminalVirtualHistoryTranscript(
    frame: TerminalEmulator.RenderFrame,
    rows: List<TerminalRenderedRowState>,
    style: TextStyle,
    pass: TerminalLazyLayoutPass,
    measurements: TerminalLazyItemMeasurements,
    state: LazyListState,
    horizontalScroll: androidx.compose.foundation.ScrollState,
    userScrollEnabled: Boolean,
    flingBehavior: FlingBehavior,
    widthIndex: TerminalTranscriptWidthIndex,
    modifier: Modifier = Modifier,
    checkpoint: me.rerere.rikkahub.data.container.TerminalWidthCheckpointStore? = null,
) {
    requireTerminalRenderPair(pass, frame, rows)
    val widthPx = if (rows is TerminalSparseRows) rememberTerminalPreparedWidth(frame, style, widthIndex, checkpoint)
        else rememberTerminalTranscriptWidth(frame, style, widthIndex)
    if (widthPx == null) {
        // No guessed width, stale horizontal range or phantom measured row is published while cold work runs.
        androidx.compose.foundation.layout.Box(modifier)
        return
    }
    val density = LocalDensity.current
    LazyColumn(
        modifier = modifier.horizontalScroll(horizontalScroll, enabled = userScrollEnabled)
            .widthIn(min = with(density) { widthPx.toDp() }),
        state = state,
        userScrollEnabled = userScrollEnabled,
        flingBehavior = flingBehavior,
    ) {
        items(
            count = frame.historyCount,
            key = { index -> frame.historyLineIds[index] },
            contentType = { "terminal-history" },
        ) { index ->
            val id = frame.historyLineIds[index]
            val row = if (rows is TerminalSparseRows) {
                val text = frame.rows[index].text
                remember(id, text) { TerminalRenderedRowState(id, text) }
            } else rows[index]
            androidx.compose.runtime.key(pass.metricKey, id) {
                measurements.Row(pass.metricKey, row, style)
            }
        }
        item(key = TERMINAL_LAZY_SCREEN_KEY, contentType = "terminal-screen") {
            androidx.compose.foundation.layout.Column {
                for (index in frame.historyCount until rows.size) {
                    androidx.compose.runtime.key(pass.metricKey, rows[index].lineId) {
                        measurements.Row(pass.metricKey, rows[index], style)
                    }
                }
            }
        }
        item(key = TERMINAL_LAZY_TAIL_KEY, contentType = "terminal-tail") {
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        }
    }
}
