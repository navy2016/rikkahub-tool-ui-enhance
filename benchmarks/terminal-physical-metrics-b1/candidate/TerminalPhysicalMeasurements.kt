package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import me.rerere.rikkahub.data.container.TerminalPhysicalRowHeights
import me.rerere.rikkahub.data.container.TerminalScreenWidthSource
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows
import java.lang.ref.WeakReference
import java.util.Collections
import kotlin.math.ceil

/** The existing height entry now also carries a scalar intrinsic width, never a retained layout. */
internal data class TerminalPhysicalRowMeasurement(val text: AnnotatedString, val height: Int, val naturalWidth: Int?)

/** Same-map view of the height cache, bounded by current physical rows. A weak frame capability
 * prevents an escaped width product from retaining an entire historical document. No callbacks,
 * mutable row states, TextLayoutResult, Paragraph or historical rows belong to this product. */
internal class TerminalPhysicalMeasurements internal constructor(
    frame: TerminalEmulator.RenderFrame,
    private val metricKey: Any,
    val heights: TerminalPhysicalRowHeights,
    private val rows: Map<Long, TerminalPhysicalRowMeasurement>,
    shareWidths: Boolean,
) : TerminalScreenWidthSource {
    private val frameReference = WeakReference(frame)
    private val shareWidths = shareWidths && frame.ownedRows() != null
    val retainedRows: Int get() = rows.size
    init { require(rows.size <= TerminalEmulator.MAX_ROWS && heights.size == frame.screenLineIds.size) }
    internal fun row(id: Long): TerminalPhysicalRowMeasurement? = rows[id]

    internal fun matches(frame: TerminalEmulator.RenderFrame, metric: Any, sharing: Boolean): Boolean =
        frameReference.get() === frame && metricKey == metric && shareWidths == sharing

    override fun width(frame: TerminalEmulator.RenderFrame, metricKey: Any, screenIndex: Int, text: AnnotatedString): Int? {
        if (!shareWidths || !matches(frame, metricKey, true) || screenIndex !in frame.screenLineIds.indices) return null
        val value = rows[frame.screenLineIds[screenIndex]] ?: return null
        // A grace-held screen can display a previous Text over a newly blank captured frame.
        // Such a product cannot replace the frame Text's measurement; deferredWidth stays intact.
        if (value.text != text || frame.rows[frame.historyCount + screenIndex].text != text) return null
        return value.naturalWidth
    }
}

/** Called only for the canonical packaged style and owned current rows. Read the intrinsic,
 * NOT the constraint-clamped TextLayoutResult.size.width. The layout is not stored anywhere. */
private fun terminalMeasuredNaturalWidth(result: TextLayoutResult, text: AnnotatedString,
    metrics: TerminalTextMetrics): Int? {
    val input = result.layoutInput
    val intrinsic = result.multiParagraph.intrinsics
    if (input.text != text || input.style != metrics.style || input.density != metrics.density ||
        input.layoutDirection != metrics.direction || input.fontFamilyResolver !== metrics.resolver ||
        input.softWrap || input.maxLines != 1 || input.placeholders.isNotEmpty() ||
        intrinsic.hasStaleResolvedFonts) return null
    val width = intrinsic.maxIntrinsicWidth
    return if (width.isFinite() && width >= 0f) ceil(width).toInt() else null
}

/** Only current physical rows, never history, retain Text keys. No offscreen layout object is cached. */
private class TerminalPhysicalMeasurementCache {
    var product: TerminalPhysicalMeasurements? = null
}

@Composable
internal fun rememberTerminalPhysicalMeasurements(frame: TerminalEmulator.RenderFrame,
    rows: List<TerminalRenderedRowState>, style: TextStyle, metrics: Any): TerminalPhysicalMeasurements {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val cache = remember(metrics) { TerminalPhysicalMeasurementCache() }
    val previous = cache.product
    val fontMetrics = metrics as? TerminalTextMetrics
    val proof = remember(metrics) { fontMetrics?.checkpointProof() }
    val sharing = proof != null && fontMetrics != null && fontMetrics.style == style &&
        fontMetrics.density == LocalDensity.current && fontMetrics.direction == LocalLayoutDirection.current &&
        fontMetrics.resolver === LocalFontFamilyResolver.current && frame.ownedRows() != null
    val next = HashMap<Long, TerminalPhysicalRowMeasurement>(frame.screenLineIds.size)
    var unchanged = true
    val heights = IntArray(frame.screenLineIds.size) { index ->
        val id = frame.screenLineIds[index]
        val text = rows[frame.historyCount + index].text
        val old = previous?.row(id)?.takeIf { it.text == text }
        val value = old ?: run {
            unchanged = false
            val measured = measurer.measure(text, style, softWrap = false, maxLines = 1)
            TerminalPhysicalRowMeasurement(text, measured.size.height.coerceAtLeast(1),
                if (sharing) terminalMeasuredNaturalWidth(measured, text, checkNotNull(fontMetrics)) else null)
        }
        next[id] = value
        value.height
    }
    val value = TerminalPhysicalRowHeights(heights)
    val result = previous?.heights?.takeIf { it == value } ?: value
    // Publish one immutable map, shared with the existing height cache; never accumulate visits.
    val product = previous?.takeIf {
        unchanged && it.retainedRows == next.size && it.heights === result && it.matches(frame, metrics, sharing)
    } ?: TerminalPhysicalMeasurements(frame, metrics, result, Collections.unmodifiableMap(next), sharing)
    SideEffect { cache.product = product }
    return product
}
