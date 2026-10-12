package me.rerere.rikkahub.ui.pages.container

import android.os.Trace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator

/** Identity equality avoids scanning RenderFrame.rows and also distinguishes same-revision frames. */
internal class TerminalFrameReference(val frame: TerminalEmulator.RenderFrame) {
    override fun equals(other: Any?): Boolean = other is TerminalFrameReference && frame === other.frame
    override fun hashCode(): Int = System.identityHashCode(frame)
}

/** Resolver identity is part of both remember keys and cache equality, not just its font values. */
private class TerminalResolverReference(val resolver: FontFamily.Resolver) {
    override fun equals(other: Any?): Boolean = other is TerminalResolverReference && resolver === other.resolver
    override fun hashCode(): Int = System.identityHashCode(resolver)
}

internal data class TerminalTextMetrics(
    val style: TextStyle,
    val density: Density,
    val direction: LayoutDirection,
    val resolver: FontFamily.Resolver,
    val resolvedFonts: List<Any>,
) {
    override fun equals(other: Any?): Boolean = this === other || other is TerminalTextMetrics &&
        style == other.style && density == other.density && direction == other.direction &&
        resolver === other.resolver && resolvedFonts == other.resolvedFonts

    override fun hashCode(): Int {
        var result = style.hashCode()
        result = 31 * result + density.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + System.identityHashCode(resolver)
        return 31 * result + resolvedFonts.hashCode()
    }
}

private fun resolveTerminalFontStates(
    resolver: FontFamily.Resolver,
    family: FontFamily?,
    synthesis: FontSynthesis,
    onResolve: (() -> Unit)?,
): List<State<Any>> {
    fun resolve(weight: FontWeight, style: FontStyle): State<Any> {
        onResolve?.invoke() // Test-only call counter; production callers never install this probe.
        return resolver.resolve(family, weight, style, synthesis)
    }
    return listOf(
        resolve(FontWeight.Normal, FontStyle.Normal),
        resolve(FontWeight.Normal, FontStyle.Italic),
        resolve(FontWeight.Bold, FontStyle.Normal),
        resolve(FontWeight.Bold, FontStyle.Italic),
    )
}

@Composable
internal fun terminalTextMetrics(style: TextStyle, onResolve: (() -> Unit)? = null): TerminalTextMetrics {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    val resolverKey = TerminalResolverReference(resolver)
    val family = style.fontFamily
    val synthesis = style.fontSynthesis ?: FontSynthesis.All
    // Only the exact packaged blocking font can retain lookup states. Structurally equal copies,
    // custom/optional/async fonts retain the former live resolve/retry/cancellation behavior.
    val packagedBlocking = family === JetbrainsMono && family is FontListFontFamily &&
        family.fonts.all { it.loadingStrategy == FontLoadingStrategy.Blocking }
    val states = if (packagedBlocking) {
        remember(resolverKey, synthesis) { resolveTerminalFontStates(resolver, family, synthesis, onResolve) }
    } else {
        resolveTerminalFontStates(resolver, family, synthesis, onResolve)
    }
    // Read EVERY State.value outside remember, even on the fast path. Font completion must still
    // invalidate natural heights and widths without needing output, a new frame or an IME change.
    val normal = states[0].value
    val italic = states[1].value
    val bold = states[2].value
    val boldItalic = states[3].value
    val metricDensity = remember(density.density, density.fontScale) { Density(density.density, density.fontScale) }
    return remember(style, metricDensity, direction, resolverKey, normal, italic, bold, boldItalic) {
        TerminalTextMetrics(style, metricDensity, direction, resolver, listOf(normal, italic, bold, boldItalic))
    }
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
internal fun rememberTerminalTranscriptWidth(
    frame: TerminalEmulator.RenderFrame,
    style: TextStyle,
    index: TerminalTranscriptWidthIndex,
): Int {
    val metrics = terminalTextMetrics(style)
    return remember(index, TerminalFrameReference(frame), metrics) {
        Trace.beginSection("Terminal.productionWidth")
        try {
            val resolved = resolveDefaults(style, metrics.direction)
            val measurer = TerminalRowWidthMeasurer(frame, resolved, metrics.density, metrics.resolver)
            index.width(frame, metrics, measurer::width)
        } finally { Trace.endSection() }
    }
}
