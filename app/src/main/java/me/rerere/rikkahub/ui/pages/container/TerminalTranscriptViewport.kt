package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.utils.TerminalEmulator

/** The production output branch, shared unchanged by the app and interaction tests. */
@Composable
internal fun TerminalTranscriptViewport(
    frame: TerminalEmulator.RenderFrame,
    rows: List<TerminalRenderedRowState>,
    style: TextStyle,
    historyChunks: List<TerminalHistoryChunk>,
    mode: TerminalRenderMode,
    bound: TerminalBoundViewport,
    horizontalScroll: ScrollState,
    panEnabled: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
) {
    if (bound.virtualHistoryEnabled) {
        TerminalVirtualHistoryTranscript(frame, rows, style, bound.pass, bound.binding.measurements,
            bound.binding.lazy, horizontalScroll, panEnabled && !selectionMode,
            bound.gestures.flingBehavior, modifier)
    } else {
        Column(modifier.horizontalScroll(horizontalScroll, enabled = panEnabled || selectionMode)
            .verticalScroll(bound.binding.eager, enabled = panEnabled || selectionMode,
                flingBehavior = bound.gestures.flingBehavior)) {
            val content: @Composable () -> Unit = {
                TerminalConfiguredTranscript(rows, style, historyChunks, mode,
                    measurement = bound.eagerMeasurement)
            }
            if (selectionMode) SelectionContainer { Column { content() } } else content()
            Spacer(Modifier.height(8.dp))
        }
    }
}
