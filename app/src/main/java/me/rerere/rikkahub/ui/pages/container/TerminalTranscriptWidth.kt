package me.rerere.rikkahub.ui.pages.container

import android.os.Trace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.MultiParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.utils.TerminalEmulator
import kotlin.math.ceil

/** Identity equality avoids scanning RenderFrame.rows and also distinguishes same-revision frames. */
internal class TerminalFrameReference(val frame: TerminalEmulator.RenderFrame) {
    override fun equals(other: Any?): Boolean = other is TerminalFrameReference && frame === other.frame
    override fun hashCode(): Int = System.identityHashCode(frame)
}

private data class TerminalTextMetrics(
    val style: TextStyle,
    val density: Density,
    val direction: LayoutDirection,
    val resolver: FontFamily.Resolver,
    val resolvedFonts: List<Any>,
)

@Composable
private fun terminalTextMetrics(style: TextStyle): TerminalTextMetrics {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    // Observe asynchronous typeface resolution, not only the stable resolver object. Terminal
    // ANSI spans use these four variants. Explicit Material Text style does not merge LocalTextStyle.
    val fonts = listOf(FontWeight.Normal, FontWeight.Bold).flatMap { weight ->
        listOf(FontStyle.Normal, FontStyle.Italic).map { fontStyle ->
            resolver.resolve(style.fontFamily, weight, fontStyle, style.fontSynthesis ?: FontSynthesis.All).value
        }
    }
    return TerminalTextMetrics(style, Density(density.density, density.fontScale), direction, resolver, fonts)
}

@Composable
internal fun rememberTerminalLazyLayoutPass(
    frame: TerminalEmulator.RenderFrame,
    style: TextStyle,
): TerminalLazyLayoutPass {
    val metrics = terminalTextMetrics(style)
    return remember(TerminalFrameReference(frame), metrics) { TerminalLazyLayoutPass(frame, metrics) }
}

/** Exact natural width of ALL surviving rows, including an offscreen widest history line. */
@Composable
internal fun rememberTerminalTranscriptWidth(frame: TerminalEmulator.RenderFrame, style: TextStyle): Int {
    val metrics = terminalTextMetrics(style)
    val index = remember { TerminalTranscriptWidthIndex() }
    return remember(TerminalFrameReference(frame), metrics) {
        Trace.beginSection("Terminal.productionWidth")
        try {
            val resolved = resolveDefaults(style, metrics.direction)
            index.width(frame, metrics) { text ->
                ceil(MultiParagraphIntrinsics(annotatedString = text, style = resolved, placeholders = emptyList(),
                    density = metrics.density, fontFamilyResolver = metrics.resolver)
                    .maxIntrinsicWidth).toInt()
            }
        } finally { Trace.endSection() }
    }
}
