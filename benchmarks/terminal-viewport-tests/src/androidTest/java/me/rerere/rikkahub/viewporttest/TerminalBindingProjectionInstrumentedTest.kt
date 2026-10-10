package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.ui.pages.container.TerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptViewport
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.rememberTerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description

/** Layout-only changes exercise the actual Text, controller and sole scroll executor. */
class TerminalBindingProjectionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(60)
    @get:Rule val failures = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            Log.e("TerminalViewportProbe", "bindingProjection lazy=true ${description.methodName}", error)
        }
    }

    private class Fixture {
        val terminal = TerminalEmulator(80, 8, 96).apply {
            feed("\u001B[?25l" + (0 until 104).joinToString("\r\n") { "line-$it 中文 e\u0301" })
        }
        var frame by mutableStateOf(terminal.renderFrame(), referentialEqualityPolicy())
        val rows = createTerminalRenderedRows(frame)
        var controller by mutableStateOf(TerminalViewportController(), referentialEqualityPolicy())
        val eager = ScrollState(Int.MAX_VALUE)
        val lazy = LazyListState()
        val horizontal = ScrollState(0)
        val measurements = TerminalLazyItemMeasurements()
        var requestedHeight by mutableIntStateOf(480)
        var measuredHeight by mutableIntStateOf(0)
        var tui by mutableStateOf(false)
        var fontSp by mutableIntStateOf(12)
        var unrelated by mutableIntStateOf(0)
        var overrideSupplier by mutableStateOf(false)
        var cellHeight = 1
        var tailPadding = 1
        var panelCompositions = 0
        var legacyCompositions = 0
        var committedPulse = -1
        var legacyTui = false
        lateinit var bound: TerminalBoundViewport

        fun metrics(forceTui: Boolean? = null) = TerminalViewportMetrics(
            eager.maxValue, measuredHeight, cellHeight, tailPadding, usesTuiViewport = forceTui ?: tui)

        @Composable fun Content() {
            Box(Modifier.fillMaxSize()) {
                LegacyMetricsConsumer()
                Panel()
            }
        }

        @Composable private fun LegacyMetricsConsumer() {
            // Exactly the former composition read: all supplier state is observed even though
            // the consumer uses only one Boolean. This control never drives layout or scrolling.
            val usesTui = metrics().usesTuiViewport
            SideEffect { legacyCompositions++; legacyTui = usesTui }
        }

        @Composable private fun Panel() {
            val pulse = unrelated
            val supplied = overrideSupplier
            val density = LocalDensity.current
            val style = TextStyle(color = Color.Green, fontFamily = JetbrainsMono,
                fontSize = fontSp.sp, lineHeight = fontSp.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
            cellHeight = rememberTextMeasurer().measure("W", style).size.height.coerceAtLeast(1)
            tailPadding = with(density) { 8.dp.roundToPx() }
            bound = rememberTerminalBoundViewport(this, controller, eager, lazy, measurements, frame, style,
                wantsVirtual = false, metrics = { metrics(if (supplied) true else null) },
                gestureConfig = TerminalViewportGestureConfig(true, false, 2))
            SideEffect { panelCompositions++; committedPulse = pulse }
            val chunks = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence,
                usesTuiViewport = bound.eagerMeasurement == null)
            Layout(modifier = Modifier.testTag("binding-output").onSizeChanged { measuredHeight = it.height },
                content = {
                    TerminalTranscriptViewport(frame, rows.toList(), style, chunks, TerminalRenderMode.DEFAULT,
                        bound, horizontal, panEnabled = true, selectionMode = false)
                }) { children, constraints ->
                // Read the request ONLY in measure, not composition. Each change must update the
                // production controller through live metric observation, not parent recomposition.
                val width = 900.coerceIn(constraints.minWidth, constraints.maxWidth)
                val height = requestedHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
                val child = children.single().measure(Constraints.fixed(width, height))
                layout(width, height) { child.place(0, 0) }
            }
        }
    }

    private fun mount(): Fixture = Fixture().also { f -> compose.setContent { f.Content() }; settle(f) }

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            f.controller.state.value.initialized && f.controller.state.value.scrollEffect == null &&
                f.bound.binding.activeWriters == 0 && f.measuredHeight == f.requestedHeight
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(f.bound.binding.maximumWriters <= 1) }
    }

    private fun assertBottom(f: Fixture) {
        val text = compose.runOnIdle { f.frame.rows[requireNotNull(f.frame.contentBounds.lastNonBlankRow)].text }
        val last = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true).fetchSemanticsNodes().single { it.config[SemanticsProperties.Text].singleOrNull() == text }
        val viewport = compose.onNodeWithTag("binding-output").fetchSemanticsNode()
        val top = last.positionInRoot.y - viewport.positionInRoot.y
        assertTrue("bottom row outside actual viewport: top=$top row=${last.size.height} viewport=${viewport.size.height}",
            top >= -1f && top + last.size.height <= viewport.size.height + 1f)
        compose.runOnIdle {
            assertTrue(f.controller.state.value.autoScroll)
            assertNotNull(f.measurements.peekEager(f.bound.pass))
        }
    }

    @Test fun bindingProjectionLayoutChangesKeepActualBottomWithoutRecomposingPanel() {
        val f = mount()
        assertBottom(f)
        val panelBefore = compose.runOnIdle { f.panelCompositions }
        val legacyBefore = compose.runOnIdle { f.legacyCompositions }
        val bound = compose.runOnIdle { f.bound }
        repeat(24) { index ->
            compose.runOnIdle { f.requestedHeight = if (index % 2 == 0) 420 else 560 }
            settle(f)
            assertBottom(f)
        }
        compose.runOnIdle {
            val oldWork = f.legacyCompositions - legacyBefore
            val newWork = f.panelCompositions - panelBefore
            assertTrue("legacy full-metric subscription was not exercised", oldWork >= 24)
            assertEquals("layout-only metrics recomposed the panel", 0, newWork)
            assertSame(bound, f.bound)
            Log.i("TerminalBindingProjection", "BINDING_WORK " + JSONObject().put("layoutChanges", 24)
                .put("legacyCompositions", oldWork).put("projectedCompositions", newWork)
                .put("bottomChecks", 24).put("bindingStable", true))
        }
    }

    @Test fun bindingProjectionUnrelatedRecompositionsRetainBoundObjectsAndSupplierPolicyStaysLive() {
        val f = mount()
        val before = compose.runOnIdle { f.bound }
        repeat(6) { index ->
            compose.runOnIdle { f.unrelated = index + 1 }
            settle(f)
            compose.runOnIdle {
                assertEquals(index + 1, f.committedPulse)
                assertSame(before, f.bound)
                assertSame(before.eagerMeasurement, f.bound.eagerMeasurement)
            }
        }
        // Replace the supplier closure without changing its original captured TUI state.
        compose.runOnIdle { f.overrideSupplier = true }
        settle(f)
        compose.runOnIdle {
            assertFalse(f.tui)
            assertNull(f.bound.eagerMeasurement)
            assertSame(before.binding, f.bound.binding)
            assertEquals(0, f.measurements.retainedEagerHistoryRows)
        }
        compose.runOnIdle { f.overrideSupplier = false }
        settle(f)
        assertBottom(f)
    }

    @Test fun bindingProjectionTuiFontFrameAndControllerChangesInvalidateTheCorrectObjects() {
        val f = mount()
        val initial = compose.runOnIdle { f.bound }
        compose.runOnIdle { f.tui = true }
        settle(f)
        compose.runOnIdle { assertNull(f.bound.eagerMeasurement); assertTrue(f.legacyTui) }
        compose.runOnIdle { f.tui = false; f.fontSp = 18 }
        settle(f)
        assertBottom(f)
        val resized = compose.runOnIdle { f.bound }
        assertNotSame(initial.pass, resized.pass)
        assertNotSame(initial.eagerMeasurement, resized.eagerMeasurement)
        assertSame(initial.binding, resized.binding)
        compose.runOnIdle { f.frame = f.frame.copy() } // Same revision is NOT the same frame identity.
        settle(f)
        val replacedFrame = compose.runOnIdle { f.bound }
        assertNotSame(resized.pass, replacedFrame.pass)
        assertSame(resized.binding, replacedFrame.binding)
        compose.runOnIdle { f.controller = TerminalViewportController() }
        settle(f)
        compose.runOnIdle {
            assertNotSame(replacedFrame.binding, f.bound.binding)
            assertSame(f.controller, f.bound.binding.controller)
        }
        assertBottom(f)
    }
}
