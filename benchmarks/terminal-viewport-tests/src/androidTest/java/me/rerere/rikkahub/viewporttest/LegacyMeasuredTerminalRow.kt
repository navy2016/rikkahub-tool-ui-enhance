package me.rerere.rikkahub.viewporttest

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.pages.container.terminalUsesBasicText

/** b7a93c9's two restart scopes; the private node factory is shared, not reimplemented. */
@Composable
internal fun LegacyMeasuredTerminalRow(
    measurements: TerminalLazyItemMeasurements,
    metricKey: Any,
    row: TerminalRenderedRowState,
    style: TextStyle,
) {
    val text = row.text
    measurements.onRowComposed?.let { observer -> SideEffect { observer(row.lineId) } }
    LegacyTerminalRenderedRow(row, style, measurements.rowModifier(metricKey, row.lineId, text))
}

/** Keep the actual old rendering body here; do not delegate to the new non-restartable helper. */
@Composable
internal fun LegacyTerminalRenderedRow(
    state: TerminalRenderedRowState,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val text = state.text
    if (terminalUsesBasicText(text, style)) {
        BasicText(text = text, modifier = modifier, style = style, softWrap = false, maxLines = 1)
    } else {
        Text(text = text, modifier = modifier, style = style, softWrap = false, maxLines = 1)
    }
}
