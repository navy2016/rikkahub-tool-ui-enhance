package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.horizontalScroll(horizontalScroll, enabled = userScrollEnabled),
        state = state,
        userScrollEnabled = userScrollEnabled,
        flingBehavior = flingBehavior,
    ) {
        items(
            count = frame.historyCount,
            key = { index -> rows[index].lineId },
            contentType = { "terminal-history" },
        ) { index ->
            measurements.Row(pass, rows[index], style)
        }
        item(key = TERMINAL_LAZY_SCREEN_KEY, contentType = "terminal-screen") {
            androidx.compose.foundation.layout.Column {
                for (index in frame.historyCount until rows.size) {
                    androidx.compose.runtime.key(rows[index].lineId) {
                        measurements.Row(pass, rows[index], style)
                    }
                }
            }
        }
        item(key = TERMINAL_LAZY_TAIL_KEY, contentType = "terminal-tail") {
            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        }
    }
}
