package me.rerere.rikkahub.benchmark

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil

/**
 * The same width chosen by TextMeasurer with unbounded constraints, Clip, softWrap=false and
 * maxLines=1: ceil(maxIntrinsicWidth). Font shaping, spans, paragraph styles and bidi resolution
 * remain Compose's responsibility. Only the subsequent MultiParagraph/TextLayoutResult allocation
 * is omitted. The visible Text still performs its unchanged real layout and draw.
 *
 * The instance belongs to one resolved metric key. Intrinsics exist only during this call: no
 * history-sized Paragraph/layout cache, character-width table or TextMeasurer LRU is introduced.
 */
internal class TerminalIntrinsicWidthMeasurer(
    style: TextStyle,
    private val density: Density,
    direction: LayoutDirection,
    private val resolver: FontFamily.Resolver,
) {
    private val resolvedStyle = resolveDefaults(style, direction)

    fun width(text: AnnotatedString): Int = ceil(MultiParagraphIntrinsics(
        annotatedString = text,
        style = resolvedStyle,
        placeholders = emptyList(),
        density = density,
        fontFamilyResolver = resolver,
    ).maxIntrinsicWidth).toInt()
}
