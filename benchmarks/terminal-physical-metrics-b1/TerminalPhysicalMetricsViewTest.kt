package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.resolveDefaults
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.ui.pages.container.TerminalPhysicalMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.pages.container.TerminalRowWidthMeasurer
import me.rerere.rikkahub.ui.pages.container.TerminalTextMetrics
import me.rerere.rikkahub.ui.pages.container.rememberTerminalPhysicalMeasurements
import me.rerere.rikkahub.ui.pages.container.terminalTextMetrics
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.math.ceil

/** Actual Compose1.11 fonts, heights, intrinsics, viewport and lifecycle. No test-clock performance claim. */
class TerminalPhysicalMetricsViewTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(300)
    private fun style(size: Int = 14) = TextStyle(color = Color(0xFF00E676), fontFamily = JetbrainsMono,
        fontSize = size.sp, lineHeight = size.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
    private fun model(history: Int = 1000) = TerminalEmulator(80, 24, history).apply {
        feed("\u001b[?25l" + (0 until history + 24).joinToString("\r\n") {
            if (it == 0) "W".repeat(79) else "row-$it 中文 e\u0301 👩‍💻"
        } + "\r")
    }
    private fun fixture(model: TerminalEmulator = model(), custom: Boolean = false) = Fixture(
        stressSpans = false, initialMode = TerminalRenderMode.FULL_VIRTUAL, terminalOverride = model,
        restored = TerminalViewportState(autoScroll = true), foreground = Color(0xFF00E676),
    ).apply {
        tui = true; fontSp = 12; heightDp = 420; densityOverride = Density(1f)
        if (custom) fontFamily = FontFamily.Serif
    }
    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            val s = f.controller.state.value
            s.initialized && s.gesture == null && s.scrollEffect == null && f.activeWriters == 0 &&
                f.bound.pass.frame === f.frame && f.observation()?.ready == true &&
                f.bound.binding.widthIndex.readyFor(f.frame, f.bound.pass.metricKey)
        }
        compose.waitForIdle()
        assertTrue(f.isVirtual); assertTrue(f.bound.binding.fullVirtual)
        assertTrue(f.maximumWriters <= 1); assertTrue(f.rowStore.retainedRowStates <= 80)
        assertTrue(f.measurements.retainedRows <= 128); assertEquals(0, f.measurements.retainedEagerHistoryRows)
    }
    private fun fullOracle(f: Fixture) = compose.runOnIdle {
        val metrics = f.bound.pass.metricKey as TerminalTextMetrics
        val p = requireNotNull(f.bound.physicalMeasurements)
        val oracle = TerminalRowWidthMeasurer(f.frame, resolveDefaults(metrics.style, metrics.direction), metrics.density, metrics.resolver)
        val full = TextMeasurer(metrics.resolver, metrics.density, metrics.direction, cacheSize = 0)
        var maximum = f.frame.rows.maxOfOrNull { oracle.width(it.text) } ?: 0
        for (i in f.frame.screenLineIds.indices) {
            val displayed = f.rows[f.frame.historyCount + i].text
            val layout = full.measure(displayed, metrics.style, softWrap = false, maxLines = 1)
            assertEquals(layout.size.height.coerceAtLeast(1), p.heights.rowHeight(i))
            maximum = maxOf(maximum, oracle.width(displayed))
            val text = f.frame.rows[f.frame.historyCount + i].text
            p.width(f.frame, metrics, i, text)?.let { assertEquals(oracle.width(text), it) }
        }
        assertEquals((maximum - f.viewportWidth).coerceAtLeast(0), f.horizontal.maxValue)
    }
    private fun record(scene: String, shared: Long = 0, fallback: Long = 0, history: Long = 0,
        rows: Int = 0, writers: Int = 0, comparisons: Int = 0, rejected: Int = 0) {
        Log.i("TerminalPhysicalMetrics", "PHYSICAL_METRICS_VIEW " + JSONObject().put("scene", scene)
            .put("sharedScreenDelta", shared).put("fallbackScreenDelta", fallback).put("historyDelta", history)
            .put("physicalRows", rows).put("writers", writers).put("oracleComparisons", comparisons)
            .put("rejectedProducts", rejected).put("performanceMeasured", false))
    }
    private fun step(f: Fixture, scene: String, expectedShared: Long? = null, expectedHistory: Long? = null, action: () -> Unit) {
        val before = compose.runOnIdle { f.bound.binding.widthIndex.let { Triple(it.sharedScreenRows, it.measuredScreenRows, it.measuredHistoryRows) } }
        compose.runOnIdle(action); settle(f); fullOracle(f)
        compose.runOnIdle {
            val index = f.bound.binding.widthIndex
            val shared = index.sharedScreenRows - before.first; val fallback = index.measuredScreenRows - before.second
            val history = index.measuredHistoryRows - before.third
            expectedShared?.let { assertEquals(scene, it, shared) }; expectedHistory?.let { assertEquals(scene, it, history) }
            if (expectedShared != null && expectedShared > 0) assertEquals(scene, 0L, fallback)
            record(scene, shared, fallback, history, f.bound.physicalMeasurements!!.retainedRows, f.maximumWriters, 24)
        }
    }

    private inner class Probe(initial: TerminalEmulator.RenderFrame) {
        var frame by mutableStateOf(initial, referentialEqualityPolicy())
        var displayed by mutableStateOf<List<TerminalRenderedRowState>?>(null, referentialEqualityPolicy())
        var textStyle by mutableStateOf(style())
        var density by mutableStateOf(Density(1f))
        var direction by mutableStateOf(LayoutDirection.Ltr)
        lateinit var product: TerminalPhysicalMeasurements
        lateinit var metrics: TerminalTextMetrics
        @Composable fun Content() {
            CompositionLocalProvider(LocalDensity provides density, LocalLayoutDirection provides direction) {
                metrics = terminalTextMetrics(textStyle)
                val current = frame
                val rows = displayed ?: current.rows.mapIndexed { i, row -> TerminalRenderedRowState(
                    if (i < current.historyCount) current.historyLineIds[i] else current.screenLineIds[i - current.historyCount], row.text) }
                product = rememberTerminalPhysicalMeasurements(current, rows, textStyle, metrics)
            }
        }
    }
    @Test fun viewNaturalWidthsAndHeightsMatchUncachedOracleAcrossGlyphsMetricsAndDirections() {
        val model = TerminalEmulator(240, 24, 100)
        val lines = listOf("", " ", "        ", "trailing  ", " leading", "ffi -> !==", "中文 日本語 한국어",
            "e\u0301\u0327 A\u030a", "👩‍💻 👨‍👩‍👧‍👦 🇨🇳 ⚙️", "עברית العربية 123 ABC", "╭─┬──╮ └─┴──┘",
            "\u001b[1;31mbold\u001b[0m \u001b[3;34mitalic\u001b[0m", "\u001b[1;3;4m双重字体\u001b[0m",
            "\u001b]8;;https://example.invalid/\u001b\\URL 👩‍💻\u001b]8;;\u001b\\", "W".repeat(239))
        model.feed("\u001b[?25l" + lines.joinToString("\r\n")); val probe = Probe(model.renderFrame())
        compose.setContent { probe.Content() }; compose.waitForIdle(); var comparisons = 0
        for (density in listOf(Density(1f), Density(1.33f, 1.3f), Density(2.75f, 1.15f)))
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) for (size in listOf(12, 19)) {
                compose.runOnIdle { probe.density = density; probe.direction = direction; probe.textStyle = style(size) }
                compose.waitForIdle()
                compose.runOnIdle {
                    val m = probe.metrics; val p = probe.product; val frame = probe.frame
                    val oracle = TerminalRowWidthMeasurer(frame, resolveDefaults(m.style, m.direction), m.density, m.resolver)
                    val full = TextMeasurer(m.resolver, m.density, m.direction, cacheSize = 0)
                    frame.screenLineIds.indices.forEach { i ->
                        val text = frame.rows[frame.historyCount + i].text
                        val layout = full.measure(text, m.style, softWrap = false, maxLines = 1)
                        assertEquals(layout.size.height.coerceAtLeast(1), p.heights.rowHeight(i))
                        val scalar = p.width(frame, m, i, text); assertNotNull("physical row=$i", scalar)
                        assertEquals(oracle.width(text), scalar)
                        assertEquals(ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt(), scalar)
                        comparisons++
                    }
                }
            }
        record("glyph-metrics-oracle", comparisons = comparisons, rows = 24)
    }
    @Test fun viewExactSameFrameForceBlankChangesOnlyDisplayedProductIdentity() {
        val model = TerminalEmulator(80, 6, 100).apply { feed("\u001b[?25l\u001b[2J\u001b[H") }
        val frame = model.renderFrame(); val probe = Probe(frame)
        val rows = frame.screenLineIds.mapIndexed { i, id -> TerminalRenderedRowState(id,
            if (i == 5) AnnotatedString("old chrome " + "W".repeat(50)) else frame.rows[i].text) }
        probe.displayed = rows
        compose.setContent { probe.Content() }; compose.waitForIdle()
        val held = compose.runOnIdle { probe.product }
        compose.runOnIdle {
            assertNull(held.width(frame, probe.metrics, 5, frame.rows[5].text))
            rows[5].text = frame.rows[5].text
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame(frame, probe.frame); assertNotSame(held, probe.product)
            assertNull(held.width(frame, probe.metrics, 5, frame.rows[5].text))
            assertNotNull(probe.product.width(frame, probe.metrics, 5, frame.rows[5].text))
            record("same-frame-forced-blank", rows = 6, comparisons = 1, rejected = 1)
        }
    }
    @Test fun viewUnknownSourcesCustomStylesAndParagraphAnnotationsKeepCompatibility() {
        val model = TerminalEmulator(80, 6, 100).apply { feed("\u001b[?25lhello\r\nworld") }
        val original = model.renderFrame(); val probe = Probe(original)
        compose.setContent { probe.Content() }; compose.waitForIdle(); var rejected = 0
        val decorated = original.copy(rows = original.rows.mapIndexed { i, row ->
            if (i != 0) row else TerminalEmulator.RenderedRow(buildAnnotatedString {
                append("abc\nRTL العربية")
                addStyle(ParagraphStyle(textDirection = TextDirection.Rtl), 0, length)
                addStyle(SpanStyle(fontFamily = FontFamily.Cursive, fontSize = 29.sp,
                    fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), 0, 3)
                addLink(LinkAnnotation.Url("https://example.invalid/"), 0, 3)
            })
        })
        val cases = listOf(original.copy(rows = original.rows.toList()) to style(), decorated to style(),
            original to style().copy(fontFamily = FontFamily.Serif), original to style().copy(color = Color.Red),
            original to style().copy(letterSpacing = 0.7.sp))
        for ((frame, textStyle) in cases) {
            compose.runOnIdle { probe.frame = frame; probe.textStyle = textStyle }; compose.waitForIdle()
            compose.runOnIdle {
                val m = probe.metrics; val p = probe.product
                val full = TextMeasurer(m.resolver, m.density, m.direction, cacheSize = 0)
                frame.screenLineIds.indices.forEach { i ->
                    val text = frame.rows[frame.historyCount + i].text
                    assertNull(p.width(frame, m, i, text)); rejected++
                    assertEquals(full.measure(text, m.style, softWrap = false, maxLines = 1).size.height.coerceAtLeast(1), p.heights.rowHeight(i))
                }
                val oracle = TerminalRowWidthMeasurer(frame, resolveDefaults(m.style, m.direction), m.density, m.resolver)
                val index = TerminalTranscriptWidthIndex()
                assertEquals(frame.rows.maxOf { oracle.width(it.text) }, index.width(frame, m, p, oracle::width))
                assertEquals(0, index.lastSharedScreenRows)
            }
        }
        record("compatibility-oracle", rows = 6, rejected = rejected, comparisons = rejected)
    }
    @Test fun viewMainAndAltMultirowRedrawShareWidthsButReturnStillMeasuresHistory() {
        var shown by mutableStateOf<Fixture?>(null, referentialEqualityPolicy())
        compose.setContent { Box { shown?.Content() } }
        for (size in listOf(1000, 10000)) {
            val f = fixture(model(size)); compose.runOnIdle { shown = f }; settle(f); fullOracle(f)
            step(f, "$size-main-edit", 24, 0) {
                f.terminal.feed("\u001b[H" + (0 until 24).joinToString("\r\n") { "\u001b[2K\u001b[1;3mMAIN-$it 中文\u001b[0m" } + "\r")
                f.publish()
            }
            step(f, "$size-alt-enter", 24, 0) { f.terminal.feed("\u001b[?1049h\u001b[?25lALT\r"); f.publish() }
            step(f, "$size-alt-redraw", 24, 0) {
                f.terminal.feed("\u001b[H" + (0 until 24).joinToString("\r\n") { "\u001b[2KALT-$it e\u0301 👩‍💻" } + "\r")
                f.publish()
            }
            assertEquals(0, f.bound.binding.widthIndex.retainedScreenRows)
            // Cold worker result may be rechecked on the latest same-generation frame, so count
            // exact historical visits and absence of duplicate intrinsic measurement, not commits.
            step(f, "$size-main-return", expectedHistory = size.toLong()) { f.terminal.feed("\u001b[?1049l"); f.publish() }
            assertEquals(0, f.bound.binding.widthIndex.lastMeasuredScreenRows)
            step(f, "$size-fifo", 1, 1) { f.terminal.feed("\u001b[24;1H\r\nappend\r"); f.publish() }
            compose.runOnIdle { shown = null }; compose.waitForIdle()
            assertFalse(f.bound.binding.widthIndex.hasRetainedState); assertEquals(0, f.measurements.retainedRows)
        }
    }
    @Test fun viewSameFrameBlankGraceKeepsOldChromeWidthAndNewProductOnForcedClear() {
        val f = fixture(); compose.setContent { f.Content() }; settle(f)
        step(f, "chrome-before", expectedHistory = 0) {
            f.terminal.feed("\u001b[24;1H\u001b[2K" + "W".repeat(79) + "\r"); f.publish()
        }
        compose.runOnIdle {
            f.terminal.feed("\u001b[24;1H\u001b[2K\r")
            f.publish(forcePendingBlanks = false, nowMs = 1000)
        }
        settle(f); fullOracle(f)
        val held = compose.runOnIdle { f.bound.physicalMeasurements!! }
        compose.runOnIdle {
            val p = f.bound.physicalMeasurements!!; val m = f.bound.pass.metricKey; val at = f.frame.historyCount + 23
            assertNotEquals(f.frame.rows[at].text, f.rows[at].text)
            assertNull(p.width(f.frame, m, 23, f.frame.rows[at].text))
            record("blank-held", rows = p.retainedRows, rejected = 1, comparisons = 24)
            // Fixture.render produces same revision but a fresh frame, like a second publication.
            f.publish(forcePendingBlanks = true, nowMs = 1060)
        }
        settle(f); fullOracle(f)
        compose.runOnIdle {
            val p = f.bound.physicalMeasurements!!; assertNotSame(held, p)
            assertNotNull(p.width(f.frame, f.bound.pass.metricKey, 23, f.frame.rows[f.frame.historyCount + 23].text))
            assertNull(held.width(f.frame, f.bound.pass.metricKey, 23, f.frame.rows[f.frame.historyCount + 23].text))
            record("blank-forced", rows = p.retainedRows, comparisons = 24)
        }
    }
    @Test fun viewFontsDensityRtlResizeAndCustomFallbackPreserveExactGeometry() {
        val f = fixture(); compose.setContent { f.Content() }; settle(f)
        step(f, "font-size", expectedShared = 24, expectedHistory = 1000) { f.fontSp = 16 }
        step(f, "density-scale", expectedShared = 24, expectedHistory = 1000) { f.densityOverride = Density(1.33f, 1.3f) }
        step(f, "rtl", expectedShared = 24, expectedHistory = 1000) { f.rtl = true }
        step(f, "columns", expectedShared = 24, expectedHistory = 1000) { f.terminal.resize(70, 24); f.publish() }
        step(f, "custom-font", expectedShared = 0, expectedHistory = 1000) { f.fontFamily = FontFamily.Serif }
        assertTrue(f.bound.binding.widthIndex.lastMeasuredScreenRows > 0)
        step(f, "packaged-return", expectedShared = 24, expectedHistory = 1000) { f.fontFamily = JetbrainsMono }
    }
    @Test fun viewStaleFrameAndMetricProductsRejectReuseAcrossUnchangedRevisionAndRemount() {
        var shown by mutableStateOf<Fixture?>(null, referentialEqualityPolicy())
        compose.setContent { shown?.Content() }; val model = model(); val f = fixture(model)
        compose.runOnIdle { shown = f }; settle(f)
        val old = compose.runOnIdle { f.bound.physicalMeasurements!! }
        val oldFrame = f.frame; val oldMetric = f.bound.pass.metricKey
        step(f, "same-revision", expectedShared = 0, expectedHistory = 0) { f.publish() }
        compose.runOnIdle {
            assertEquals(oldFrame.revision, f.frame.revision); assertNotSame(oldFrame, f.frame)
            assertNull(old.width(f.frame, f.bound.pass.metricKey, 0, f.frame.rows[f.frame.historyCount].text))
            assertNull(f.bound.physicalMeasurements!!.width(f.frame, "unrelated metrics", 0, f.frame.rows[f.frame.historyCount].text))
            // The old immutable product still describes only its own old input, never a new one.
            assertNotNull(old.width(oldFrame, oldMetric, 0, oldFrame.rows[oldFrame.historyCount].text))
        }
        compose.runOnIdle { shown = null }; compose.waitForIdle()
        assertFalse(f.bound.binding.widthIndex.hasRetainedState)
        val next = fixture(model); compose.runOnIdle { shown = next }; settle(next); fullOracle(next)
        compose.runOnIdle {
            assertNotSame(old, next.bound.physicalMeasurements)
            assertNull(old.width(next.frame, next.bound.pass.metricKey, 0, next.frame.rows[next.frame.historyCount].text))
            record("remount", rows = next.bound.physicalMeasurements!!.retainedRows, comparisons = 24, rejected = 1)
            shown = null
        }
        compose.waitForIdle(); assertEquals(0, next.measurements.retainedRows)
    }
}
