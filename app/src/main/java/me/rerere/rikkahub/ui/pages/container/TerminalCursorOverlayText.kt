package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.utils.TerminalEmulator

internal class TerminalCursorState(initial: TerminalEmulator.CursorOverlay?) {
    var value by mutableStateOf(initial)
}

internal val LocalTerminalCursorOverlays = compositionLocalOf<Map<Long, TerminalCursorState>> { emptyMap() }

/** Keeps the original natural Text layout. Cursor geometry comes from the shaped grapheme, not W*column. */
@Composable
internal fun TerminalCursorOverlayText(text: AnnotatedString, style: TextStyle,
    cursorState: TerminalCursorState, modifier: Modifier = Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BasicText(text, modifier.drawWithContent {
        drawContent()
        val cursor = cursorState.value ?: return@drawWithContent
        val result = layout ?: return@drawWithContent
        if (result.layoutInput.text != text || cursor.start !in text.indices ||
            cursor.end <= cursor.start || cursor.end > text.length || cursor.cells <= 0 ||
            cursor.part !in 0 until cursor.cells) return@drawWithContent
        val box = result.getPathForRange(cursor.start, cursor.end).getBounds()
        if (box.width <= 0f || box.height <= 0f) return@drawWithContent
        val width = box.width / cursor.cells
        val part = if (result.getBidiRunDirection(cursor.start) == ResolvedTextDirection.Rtl)
            cursor.cells - 1 - cursor.part else cursor.part
        val left = box.left + width * part
        val thickness = 1.dp.toPx().coerceAtMost(width).coerceAtMost(box.height)
        when (cursor.shape) {
            TerminalEmulator.CursorShape.BAR, TerminalEmulator.CursorShape.STEADY_BAR ->
                drawRect(cursor.color, Offset(left, box.top), Size(thickness, box.height))
            TerminalEmulator.CursorShape.UNDERLINE, TerminalEmulator.CursorShape.STEADY_UNDERLINE ->
                drawRect(cursor.color, Offset(left, box.bottom - thickness), Size(width, thickness))
            else -> Unit // block and unsupported styles keep the original renderer
        }
    }, style = style, softWrap = false, maxLines = 1, onTextLayout = { layout = it })
}
