package me.rerere.rikkahub.ui.pages.container

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows
import kotlin.math.ceil

/**
 * One synchronous width pass, never a retained Text/Paragraph cache. Owned emulator rows contain
 * SpanStyle and optional URL StringAnnotation only; the latter is metadata, not a width-bearing
 * annotation. In Compose 1.10.5 a row without ParagraphStyle becomes one ParagraphIntrinsics after
 * MultiParagraphIntrinsics normalizes ranges, copies annotations and creates its per-paragraph list.
 * Skip those wrappers, NOT font shaping. Arbitrary/replaced frames keep all annotations on the old
 * path, including LinkAnnotation/Bullet/paragraph styling that an emulator row cannot contain.
 */
internal class TerminalRowWidthMeasurer(
    frame: TerminalEmulator.RenderFrame,
    private val resolvedStyle: TextStyle,
    private val density: Density,
    private val resolver: FontFamily.Resolver,
) {
    private val owned = frame.ownedRows() != null
    var singleParagraphCalls = 0
        private set
    var multiParagraphCalls = 0
        private set

    fun width(text: AnnotatedString): Int {
        val single = owned && text.isNotEmpty() && text.paragraphStyles.isEmpty() &&
            text.text.none { it == '\n' || it == '\r' || it == '\u0085' || it == '\u2028' || it == '\u2029' }
        val intrinsics = if (single) {
            singleParagraphCalls++
            // This is the same platform ParagraphIntrinsics factory used by the multi-paragraph
            // implementation. Span order, bidi, letter spacing, fallback fonts and rounding remain.
            ParagraphIntrinsics(text = text.text, style = resolvedStyle, annotations = text.spanStyles,
                density = density, fontFamilyResolver = resolver, placeholders = emptyList())
        } else {
            multiParagraphCalls++
            MultiParagraphIntrinsics(annotatedString = text, style = resolvedStyle, placeholders = emptyList(),
                density = density, fontFamilyResolver = resolver)
        }
        return ceil(intrinsics.maxIntrinsicWidth).toInt()
    }
}
