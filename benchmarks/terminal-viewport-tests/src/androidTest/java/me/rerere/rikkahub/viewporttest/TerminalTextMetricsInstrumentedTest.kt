package me.rerere.rikkahub.viewporttest

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalTranscriptWidthIndex
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.ui.pages.container.TerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalTextMetrics
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptViewport
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.rememberTerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.rememberTerminalTranscriptWidth
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.pages.container.terminalTextMetrics
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.atomic.AtomicInteger

/** Actual 1.10.5 resolver/State semantics. No custom sealed Resolver, network or font preload. */
class TerminalTextMetricsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(90)

    private class Probe(initialResolver: FontFamily.Resolver, val legacy: Boolean = false) {
        var resolver by mutableStateOf(initialResolver, referentialEqualityPolicy())
        var style by mutableStateOf(
            TextStyle(color = Color.Green, fontFamily = JetbrainsMono, fontSize = 14.sp),
            referentialEqualityPolicy(),
        )
        var density by mutableStateOf(Density(1.5f, 1.1f))
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var pulse by mutableIntStateOf(0)
        var resolves = 0
        var compositions = 0
        var metricsCreated = 0
        var committedPulse = -1
        var metrics: TerminalTextMetrics? = null
        private val counted: () -> Unit = { resolves++ }

        @Composable fun Content() {
            CompositionLocalProvider(
                LocalFontFamilyResolver provides resolver,
                LocalDensity provides density,
                LocalLayoutDirection provides direction,
            ) { Consumer() }
        }

        @Composable private fun Consumer() {
            val update = pulse
            val current = if (legacy) legacyMetrics(style) else terminalTextMetrics(style, counted)
            SideEffect {
                compositions++
                if (metrics !== current) metricsCreated++
                metrics = current
                committedPulse = update
            }
        }

        /** Exact prior list construction/query sequence, scoped only to this test control. */
        @Composable private fun legacyMetrics(style: TextStyle): TerminalTextMetrics {
            val d = LocalDensity.current
            val r = LocalFontFamilyResolver.current
            val fonts = listOf(FontWeight.Normal, FontWeight.Bold).flatMap { weight ->
                listOf(FontStyle.Normal, FontStyle.Italic).map { fontStyle ->
                    resolves++
                    r.resolve(style.fontFamily, weight, fontStyle, style.fontSynthesis ?: FontSynthesis.All).value
                }
            }
            return TerminalTextMetrics(style, Density(d.density, d.fontScale), LocalLayoutDirection.current, r, fonts)
        }
    }

    @Test fun textMetricsPackagedFontAvoids128RepeatedResolvesAndReusesIdentity() {
        val resolver = createFontFamilyResolver(compose.activity)
        val old = Probe(resolver, legacy = true)
        val new = Probe(resolver)
        compose.setContent { Column { old.Content(); new.Content() } }
        compose.waitForIdle()
        val baseline = compose.runOnIdle {
            assertEquals(4, old.resolves)
            assertEquals(4, new.resolves)
            assertEquals(old.metrics, new.metrics)
            listOf(old.resolves, new.resolves, old.compositions, new.compositions,
                old.metricsCreated, new.metricsCreated)
        }
        val before = compose.runOnIdle { requireNotNull(new.metrics) }
        repeat(32) { update ->
            compose.runOnIdle { old.pulse++; new.pulse++ }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(update + 1, old.committedPulse)
                assertEquals(update + 1, new.committedPulse)
                assertSame(before, new.metrics)
                assertSame(before.density, new.metrics!!.density)
                assertSame(before.resolvedFonts, new.metrics!!.resolvedFonts)
                assertEquals(old.metrics, new.metrics)
            }
        }
        compose.runOnIdle {
            assertEquals(128, old.resolves - baseline[0])
            assertEquals(0, new.resolves - baseline[1])
            assertEquals(32, old.compositions - baseline[2])
            assertEquals(32, new.compositions - baseline[3])
            assertEquals(32, old.metricsCreated - baseline[4])
            assertEquals(0, new.metricsCreated - baseline[5])
            Log.i("TerminalTextMetrics", "TEXT_METRICS_WORK " + JSONObject()
                .put("updates", 32).put("legacyResolves", old.resolves - baseline[0])
                .put("cachedResolves", new.resolves - baseline[1])
                .put("legacyMetrics", old.metricsCreated - baseline[4])
                .put("cachedMetrics", new.metricsCreated - baseline[5])
                .put("legacyCompositions", old.compositions - baseline[2])
                .put("cachedCompositions", new.compositions - baseline[3])
                .put("identityChecks", 32))
        }
    }

    @Test fun textMetricsStyleDensityDirectionSynthesisAndResolverInvalidatePrecisely() {
        val f = Probe(createFontFamilyResolver(compose.activity))
        compose.setContent { f.Content() }
        compose.waitForIdle()
        fun change(expectedResolves: Int = 0, update: () -> Unit) {
            val before = compose.runOnIdle { requireNotNull(f.metrics) to f.resolves }
            compose.runOnIdle { update() }
            compose.waitForIdle()
            compose.runOnIdle {
                assertNotSame(before.first, f.metrics)
                assertNotEquals(before.first, f.metrics)
                assertEquals(expectedResolves, f.resolves - before.second)
            }
        }
        change { f.style = f.style.copy(fontSize = 19.sp) }
        change { f.style = f.style.copy(lineHeight = 23.sp) }
        change { f.style = f.style.copy(color = Color.Red) }
        change { f.style = f.style.copy(letterSpacing = 0.5.sp) }
        change { f.style = f.style.copy(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic) }
        change { f.density = Density(2f, 1.1f) }
        change { f.density = Density(2f, 1.6f) }
        change { f.direction = LayoutDirection.Rtl }
        change(4) { f.style = f.style.copy(fontSynthesis = FontSynthesis.None) }
        change(4) { f.resolver = createFontFamilyResolver(compose.activity) }
        change(4) { f.style = f.style.copy(fontFamily = FontFamily.Serif) }
        change(4) { f.style = f.style.copy(fontFamily = JetbrainsMono) }
    }

    @Test fun textMetricsEqualFamilyCopiesAndCustomFontsKeepLiveResolveBehavior() {
        val f = Probe(createFontFamilyResolver(compose.activity))
        val copy = FontFamily((JetbrainsMono as FontListFontFamily).fonts)
        assertEquals(JetbrainsMono, copy)
        assertNotSame(JetbrainsMono, copy)
        f.style = f.style.copy(fontFamily = copy)
        compose.setContent { f.Content() }
        compose.waitForIdle()
        for (family in listOf(copy, FontFamily.Serif, null)) {
            compose.runOnIdle { f.style = f.style.copy(fontFamily = family) }
            compose.waitForIdle()
            val before = compose.runOnIdle { f.resolves }
            repeat(8) {
                compose.runOnIdle { f.pulse++ }
                compose.waitForIdle()
            }
            compose.runOnIdle { assertEquals(32, f.resolves - before) }
        }
    }

    private class Gate {
        val result = CompletableDeferred<Typeface?>()
        val started = AtomicInteger()
        val finished = AtomicInteger()
        val cancelled = AtomicInteger()
        val family = FontFamily(listOf(
            GateFont(this, FontWeight.Normal, FontStyle.Normal),
            GateFont(this, FontWeight.Normal, FontStyle.Italic),
            GateFont(this, FontWeight.Bold, FontStyle.Normal),
            GateFont(this, FontWeight.Bold, FontStyle.Italic),
        ))
    }

    private class GateFont(val gate: Gate, override val weight: FontWeight, override val style: FontStyle) :
        AndroidFont(FontLoadingStrategy.Async, Loader, FontVariation.Settings())

    private object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface? = error("Async font blocked")
        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? {
            val gate = (font as GateFont).gate
            gate.started.incrementAndGet()
            return try {
                gate.result.await()
            } catch (cancel: CancellationException) {
                gate.cancelled.incrementAndGet()
                throw cancel
            } finally {
                gate.finished.incrementAndGet()
            }
        }
    }

    /** Real production binding, Text and both width/height cache consumers; the frame never changes. */
    private class GeometryFixture(family: FontFamily, initialResolver: FontFamily.Resolver) {
        val frame = TerminalEmulator(80, 6, 32).apply {
            feed("\u001B[?25l" + (0 until 22).joinToString("\r\n") { "$it iiiiiiiii WWW" })
        }.renderFrame()
        val rows = createTerminalRenderedRows(frame).toList()
        val style = TextStyle(color = Color.Green, fontFamily = family, fontSize = 18.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false))
        val density = Density(1.5f)
        var resolver by mutableStateOf(initialResolver, referentialEqualityPolicy())
        var pulse by mutableIntStateOf(0)
        var viewportHeight by mutableIntStateOf(0)
        val controller = TerminalViewportController()
        val eager = ScrollState(Int.MAX_VALUE)
        val lazy = LazyListState()
        val horizontal = ScrollState(0)
        val measurements = TerminalLazyItemMeasurements()
        val widths = TerminalTranscriptWidthIndex()
        lateinit var bound: TerminalBoundViewport
        var width = 0
        var committedPulse = -1

        @Composable fun Content() {
            CompositionLocalProvider(LocalFontFamilyResolver provides resolver, LocalDensity provides density,
                LocalLayoutDirection provides LayoutDirection.Ltr) { Output() }
        }

        @Composable private fun Output() {
            val update = pulse
            val cell = rememberTextMeasurer().measure("W", style).size.height.coerceAtLeast(1)
            val tail = with(density) { 8.dp.roundToPx() }
            val current = rememberTerminalBoundViewport(this, controller, eager, lazy, measurements, frame, style,
                wantsVirtual = false, metrics = { TerminalViewportMetrics(eager.maxValue, viewportHeight, cell, tail) },
                gestureConfig = TerminalViewportGestureConfig(true, false, 2))
            // Separate scalar index avoids the ordinary eager binding intentionally clearing its own index.
            val naturalWidth = rememberTerminalTranscriptWidth(frame, style, widths)
            SideEffect { bound = current; width = naturalWidth; committedPulse = update }
            Box(Modifier.size(320.dp, 200.dp).testTag("font-output").onSizeChanged { viewportHeight = it.height }) {
                TerminalTranscriptViewport(frame, rows, style,
                    terminalHistoryChunks(frame.historyCount, frame.historyStartSequence, false),
                    TerminalRenderMode.DEFAULT, current, horizontal, true, false, Modifier.fillMaxSize())
            }
        }
    }

    private fun settle(f: GeometryFixture) {
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            f.controller.state.value.initialized && f.controller.state.value.scrollEffect == null &&
                f.bound.binding.activeWriters == 0 && f.measurements.peekEager(f.bound.pass) != null
        }
        compose.waitForIdle()
    }

    private fun assertGeometry(f: GeometryFixture) {
        settle(f)
        compose.runOnIdle {
            assertSame(f.frame, f.bound.pass.frame)
            val geometry = requireNotNull(f.measurements.peekEager(f.bound.pass))
            val oracle = TextMeasurer(f.resolver, f.density, LayoutDirection.Ltr, cacheSize = 0)
            val expected = f.frame.rows.map { oracle.measure(it.text, f.style, softWrap = false,
                maxLines = 1, skipCache = true).size }
            assertEquals(expected.maxOf { it.width }, f.width)
            expected.forEachIndexed { index, size -> assertEquals(size.height, geometry.height(index)) }
            assertTrue(f.bound.binding.maximumWriters <= 1)
        }
        val lastText = f.frame.rows.last().text
        val row = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().single { it.config[SemanticsProperties.Text].singleOrNull() == lastText }
        val viewport = compose.onNodeWithTag("font-output").fetchSemanticsNode()
        val top = row.positionInRoot.y - viewport.positionInRoot.y
        assertTrue("async font clipped bottom row", top >= -1f && top + row.size.height <= viewport.size.height + 1f)
    }

    @Test fun textMetricsAsyncSuccessUpdatesWidthsAndRealHeightsWithoutOutput() {
        val gate = Gate()
        val f = GeometryFixture(gate.family, createFontFamilyResolver(compose.activity))
        compose.setContent { f.Content() }
        assertGeometry(f)
        compose.waitUntil(5_000) { gate.started.get() >= 4 }
        val before = compose.runOnIdle { f.bound.pass to f.width }
        compose.runOnIdle { gate.result.complete(Typeface.MONOSPACE) }
        compose.waitUntil(5_000) { gate.finished.get() >= 4 }
        assertGeometry(f)
        compose.runOnIdle {
            assertNotSame(before.first, f.bound.pass)
            assertNotEquals(before.first.metricKey, f.bound.pass.metricKey)
            assertNotEquals(before.second, f.width)
            assertNull(f.measurements.peekEager(before.first))
            assertSame(f.frame, before.first.frame)
        }
    }

    @Test fun textMetricsAsyncFailureKeepsFallbackAndDoesNotRetryPermanentFailure() {
        val gate = Gate()
        val f = GeometryFixture(gate.family, createFontFamilyResolver(compose.activity))
        compose.setContent { f.Content() }
        assertGeometry(f)
        compose.waitUntil(5_000) { gate.started.get() >= 4 }
        val before = compose.runOnIdle { f.bound.pass to f.width }
        compose.runOnIdle { gate.result.completeExceptionally(IllegalStateException("fixed test font failure")) }
        compose.waitUntil(5_000) { gate.finished.get() >= 4 }
        compose.waitForIdle()
        val loads = gate.started.get()
        repeat(8) { index ->
            compose.runOnIdle { f.pulse++ }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(index + 1, f.committedPulse) }
        }
        assertGeometry(f)
        compose.runOnIdle {
            assertEquals(loads, gate.started.get())
            assertEquals(before.first.metricKey, f.bound.pass.metricKey)
            assertEquals(before.second, f.width)
        }
    }

    @Test fun textMetricsAsyncCancellationAllowsRetryWithReplacementResolver() {
        val gate = Gate()
        val firstJob = Job()
        val replacementJob = Job()
        val resolver = createFontFamilyResolver(compose.activity, Dispatchers.Main.immediate + firstJob)
        val f = GeometryFixture(gate.family, resolver)
        try {
            compose.setContent { f.Content() }
            assertGeometry(f)
            compose.waitUntil(5_000) { gate.started.get() >= 4 }
            compose.runOnIdle { firstJob.cancel() }
            compose.waitUntil(5_000) { gate.cancelled.get() >= 4 }
            val loads = gate.started.get()
            compose.runOnIdle {
                f.resolver = createFontFamilyResolver(compose.activity, Dispatchers.Main.immediate + replacementJob)
            }
            compose.waitForIdle()
            compose.waitUntil(5_000) { gate.started.get() > loads }
            val before = compose.runOnIdle { f.bound.pass }
            compose.runOnIdle { gate.result.complete(Typeface.MONOSPACE) }
            compose.waitUntil(5_000) { gate.finished.get() == gate.started.get() }
            assertGeometry(f)
            compose.runOnIdle {
                assertNotEquals(before.metricKey, f.bound.pass.metricKey)
                assertSame(f.frame, f.bound.pass.frame)
            }
        } finally {
            firstJob.cancel()
            replacementJob.cancel()
        }
    }
}
