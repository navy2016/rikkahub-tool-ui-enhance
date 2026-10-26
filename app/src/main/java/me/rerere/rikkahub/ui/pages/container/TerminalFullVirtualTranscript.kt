package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.utils.TerminalEmulator

/**
 * Explicit full virtualization, including physical screen rows. Old renderer branches never enter
 * here. Layout state is proportional to visible/precomposed rows, selection endpoints are semantic,
 * and a hidden history row never becomes a Composable merely because it is selected.
 */
@Composable
internal fun TerminalFullVirtualTranscript(
    frame: TerminalEmulator.RenderFrame,
    rows: List<TerminalRenderedRowState>,
    style: TextStyle,
    bound: TerminalBoundViewport,
    horizontal: ScrollState,
    panEnabled: Boolean,
    selectionEnabled: Boolean,
    modifier: Modifier = Modifier,
    pointerMap: TerminalPointerMap? = null,
    cellWidthPx: Int = 1,
) {
    require(bound.virtualHistoryEnabled)
    requireTerminalRenderPair(bound.pass, frame, rows)
    val binding = bound.binding
    val preparedWidth = rememberTerminalPreparedWidth(frame, style, binding.widthIndex, binding.widthCheckpoint)
    val selection = remember(binding) { TerminalVirtualSelectionUi(binding) }
    // Never retain the transient complete sparse list inside the endpoint provider.
    val screen = if (rows is TerminalSparseRows) rows.screen else rows.subList(frame.historyCount, rows.size)
    // The TUI blank grace may still display a previous screen Text while the captured frame is
    // blank. Include its exact width too; never clip deferred chrome to the newer blank's width.
    val metrics = bound.pass.metricKey as TerminalTextMetrics
    val deferredWidth = if (screen.indices.any { screen[it].text != frame.rows[frame.historyCount + it].text }) {
        val measurer = TerminalRowWidthMeasurer(frame, resolveDefaults(style, metrics.direction), metrics.density, metrics.resolver)
        screen.indices.maxOfOrNull { index ->
            val displayed = screen[index].text
            if (displayed == frame.rows[frame.historyCount + index].text) 0 else measurer.width(displayed)
        } ?: 0
    } else 0
    val width = preparedWidth?.let { maxOf(it, deferredWidth) }
    SideEffect { selection.bind(frame, screen, selectionEnabled) }
    DisposableEffect(selection) { onDispose { selection.dispose() } }
    val mouse = pointerMap?.takeIf { !panEnabled && !selectionEnabled }
    val pointerScope = mouse?.let {
        val pass = remember(TerminalFrameReference(frame), TerminalChunkViewIdentity(rows, frame),
            bound.pass.metricKey, cellWidthPx, binding.physicalRows) {
            TerminalPointerMap.Pass(frame, rows, cellWidthPx, bound.pass.metricKey, binding.physicalRows)
        }
        SideEffect { mouse.bind(pass) }
        DisposableEffect(mouse) { onDispose { mouse.clear() } }
        remember(mouse, pass) { TerminalPointerScope(mouse, pass) }
    }
    val density = LocalDensity.current
    TerminalVirtualSelectionContainer(selection, selectionEnabled,
        modifier.onGloballyPositioned { mouse?.viewportCoordinates = it }) {
        if (width == null) {
            Box(Modifier.fillMaxSize())
        } else {
            CompositionLocalProvider(LocalTerminalPointerScope provides pointerScope) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().horizontalScroll(horizontal, enabled = panEnabled || selectionEnabled)
                        .widthIn(min = with(density) { width.toDp() }),
                    state = binding.lazy,
                    userScrollEnabled = panEnabled || selectionEnabled,
                    flingBehavior = bound.gestures.flingBehavior,
                ) {
                    items(count = frame.rows.size, key = { index ->
                        if (index < frame.historyCount) frame.historyLineIds[index] else frame.screenLineIds[index - frame.historyCount]
                    }, contentType = { "terminal-virtual-row" }) { index ->
                        val row = if (rows is TerminalSparseRows && index < frame.historyCount) {
                            val id = frame.historyLineIds[index]
                            val text = frame.rows[index].text
                            remember(id, text) { TerminalRenderedRowState(id, text) }
                        } else rows[index]
                        key(bound.pass.metricKey, row.lineId) {
                            TerminalVirtualSelectableRow(selection, index, row, style, bound.pass.metricKey,
                                binding.measurements, selectionEnabled)
                        }
                    }
                    item(key = TERMINAL_LAZY_TAIL_KEY, contentType = "terminal-tail") {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
