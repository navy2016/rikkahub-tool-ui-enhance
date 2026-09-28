package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedTranscript
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptCompositionObserver
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Natural production Text/layout, NOT the gesture fixture's uniform-height row boxes. Compare
 * every row (including offscreen ones), both scroll ranges and offsets. This guards geometry only;
 * it is not an end-to-end IME, long-selection/copy or real-device performance acceptance test.
 */
@RunWith(Parameterized::class)
class TerminalTranscriptGeometryInstrumentedTest(private val chunkLayers: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "chunkLayers={0}")
        fun modes() = listOf(arrayOf(false), arrayOf(true))
    }

    @get:Rule val compose = createComposeRule()
    @get:Rule val caseTimeout = Timeout.seconds(45)
    @get:Rule val probe = object : TestWatcher() {
        override fun starting(description: Description) {
            Log.i("TerminalViewportProbe", "geometry start ${description.methodName}")
        }
        override fun failed(error: Throwable, description: Description) {
            Log.e("TerminalViewportProbe", "geometry failed ${description.methodName}", error)
        }
        override fun succeeded(description: Description) {
            Log.i("TerminalViewportProbe", "geometry passed ${description.methodName}")
        }
    }

    private data class RowGeometry(val text: AnnotatedString, val x: Float, val y: Float, val width: Int, val height: Int)
    private data class Geometry(val rows: List<RowGeometry>, val vertical: Pair<Int, Int>, val horizontal: Pair<Int, Int>)

    private fun geometry(fixture: Fixture): Geometry {
        compose.waitForIdle()
        val nodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val rows = nodes.map { node ->
            // boundsInRoot is clipped by scroll containers. positionInRoot + size is the actual
            // natural row geometry, including rows entirely outside the visible viewport.
            RowGeometry(node.config[SemanticsProperties.Text].single(), node.positionInRoot.x,
                node.positionInRoot.y, node.size.width, node.size.height)
        }
        return compose.runOnIdle {
            assertEquals(fixture.frame.rows.map { it.text }, rows.map { it.text })
            Geometry(rows, fixture.vertical.value to fixture.vertical.maxValue,
                fixture.horizontal.value to fixture.horizontal.maxValue)
        }
    }

    private fun assertEquivalent(a: Geometry, b: Geometry) {
        assertEquals(a.vertical, b.vertical)
        assertEquals(a.horizontal, b.horizontal)
        assertEquals(a.rows.size, b.rows.size)
        a.rows.zip(b.rows).forEachIndexed { index, (expected, actual) ->
            assertEquals("row $index text/spans", expected.text, actual.text)
            assertEquals("row $index x", expected.x, actual.x, 0.01f)
            assertEquals("row $index y", expected.y, actual.y, 0.01f)
            assertEquals("row $index width", expected.width, actual.width)
            assertEquals("row $index height", expected.height, actual.height)
        }
    }

    private fun assertFlatMatchesGrouped(fixture: Fixture) {
        compose.runOnIdle { fixture.grouped = false }
        val flat = geometry(fixture)
        compose.runOnIdle {
            assertEquals(0, fixture.composedHistory)
            assertEquals(0, fixture.composedScreens)
            fixture.grouped = true
        }
        assertEquivalent(flat, geometry(fixture))
        compose.runOnIdle {
            val chunks = terminalHistoryChunks(fixture.frame.historyCount, fixture.frame.historyStartSequence,
                fixture.tui || fixture.frame.isAlternateScreen)
            assertEquals(chunks.size, fixture.composedChunks)
            assertEquals(if (chunks.isEmpty()) 0 else fixture.frame.historyCount, fixture.composedHistory)
            assertEquals(if (chunks.isEmpty()) 0 else 1, fixture.composedScreens)
        }
    }

    @Test
    fun naturalAnsiCjkRowsAndPartialChunkBoundariesHaveIdenticalGeometry() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        assertFlatMatchesGrouped(fixture)
        assertTrue(fixture.composedChunks > 1)
    }

    @Test
    fun headBucketRemovalAndStyledAppendsKeepAllRowsAndTheSameTail() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        assertFlatMatchesGrouped(fixture)
        // Initial ordinal 125: the third trim removes the ENTIRE first bucket.
        repeat(6) { index ->
            compose.runOnIdle {
                fixture.terminal.feed("\r\n" + fixture.line(1_000 + index))
                fixture.publishFrame()
            }
            assertFlatMatchesGrouped(fixture)
        }
        compose.runOnIdle {
            fixture.terminal.feed("\r\u001B[2K\u001B[1mchanged 中文\u001B[0m")
            fixture.publishFrame()
        }
        assertFlatMatchesGrouped(fixture)
        compose.runOnIdle { fixture.pan(Int.MAX_VALUE, 0) }
        compose.waitForIdle()
        assertFlatMatchesGrouped(fixture)
        compose.runOnIdle { assertEquals(fixture.vertical.maxValue, fixture.vertical.value) }
    }

    @Test
    fun selectionWrapperFontScaleResizeRtlAndBothAxesPreserveGeometry() {
        val fixture = Fixture()
        fixture.selection = true
        compose.setContent { fixture.Content() }
        assertFlatMatchesGrouped(fixture)
        compose.runOnIdle {
            fixture.fontScale = 1.3f
            fixture.fontSp = 18
            fixture.width = 240
            fixture.height = 180 // Viewport contraction; not a claim of injecting a real IME.
            fixture.rtl = true
            fixture.terminal.resize(columns = 92, rows = 20)
            fixture.publishFrame()
        }
        assertFlatMatchesGrouped(fixture)
        compose.runOnIdle {
            assertTrue(fixture.horizontal.maxValue > 41)
            fixture.pan(fixture.vertical.maxValue / 2 + 5, 41)
        }
        compose.waitForIdle()
        assertFlatScrollOffsets(fixture)
        assertFlatMatchesGrouped(fixture)
    }

    private fun assertFlatScrollOffsets(fixture: Fixture) = compose.runOnIdle {
        assertTrue(fixture.vertical.value > 0 && fixture.vertical.value < fixture.vertical.maxValue)
        assertEquals(41, fixture.horizontal.value)
    }

    @Test
    fun tuiAlternateClearAndReturnTransitionsKeepTheWholePhysicalScreen() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        assertFlatMatchesGrouped(fixture)
        compose.runOnIdle { fixture.tui = true }
        assertFlatMatchesGrouped(fixture)
        compose.runOnIdle {
            fixture.tui = false
            fixture.terminal.feed("\u001B[?1049hALT")
            fixture.publishFrame()
        }
        assertFlatMatchesGrouped(fixture)
        compose.runOnIdle { assertEquals(24, fixture.frame.rows.size) }
        compose.runOnIdle {
            fixture.terminal.feed("\u001B[?1049l")
            fixture.publishFrame()
        }
        assertFlatMatchesGrouped(fixture)
        compose.runOnIdle {
            fixture.terminal.clearScrollbackOnly()
            fixture.publishFrame()
        }
        assertFlatMatchesGrouped(fixture)
        compose.runOnIdle {
            fixture.terminal.feed("\u001B[24;1H\r\nnew history")
            fixture.publishFrame()
        }
        assertFlatMatchesGrouped(fixture)
    }

    private inner class Fixture : TerminalTranscriptCompositionObserver {
        val terminal = TerminalEmulator(initialColumns = 80, initialRows = 24, maxScrollbackLines = 260).apply {
            feed("\u001B[?25l" + (0 until 260 + 24 + 125).joinToString("\r\n") { line(it) })
        }
        var frame by mutableStateOf(terminal.renderFrame())
        private val rows = createTerminalRenderedRows(frame)
        var grouped by mutableStateOf(false)
        var selection by mutableStateOf(false)
        var tui by mutableStateOf(false)
        var rtl by mutableStateOf(false)
        var fontScale by mutableStateOf(1f)
        var fontSp by mutableStateOf(14)
        var width by mutableStateOf(300)
        var height by mutableStateOf(260)
        val vertical = ScrollState(0)
        val horizontal = ScrollState(0)
        private lateinit var scope: CoroutineScope
        var composedHistory = 0
        var composedChunks = 0
        var composedScreens = 0

        fun line(index: Int) = "r$index \u001B[${31 + index % 6}m" +
            (if (index % 3 == 0) "\u001B[1m中文 ANSI" else "ASCII") + "\u001B[0m " + "x".repeat(12 + index % 35)

        override fun historyChunkDelta(chunks: Int, rows: Int) {
            composedChunks += chunks
            composedHistory += rows
        }
        override fun activeScreenDelta(screens: Int) { composedScreens += screens }

        fun publishFrame() {
            val next = terminal.renderFrame()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(rows, next, tui || next.isAlternateScreen, nowMs = 1_000L)
                frame = next
            }
        }

        fun pan(y: Int, x: Int) {
            scope.launch {
                vertical.scrollTo(y)
                horizontal.scrollTo(x)
            }
        }

        @Composable
        fun Content() {
            scope = rememberCoroutineScope()
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MaterialTheme {
                    val style = TextStyle(fontFamily = JetbrainsMono, fontSize = fontSp.sp, lineHeight = fontSp.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
                    Column(Modifier.size(width.dp, height.dp).horizontalScroll(horizontal).verticalScroll(vertical)) {
                        val text: @Composable () -> Unit = {
                            if (grouped) TerminalRenderedTranscript(
                                rows.toList(), style,
                                terminalHistoryChunks(frame.historyCount, frame.historyStartSequence,
                                    tui || frame.isAlternateScreen),
                                isolateChunkDrawing = chunkLayers, observer = this@Fixture,
                            ) else TerminalRenderedRows(rows.toList(), style)
                        }
                        if (selection) SelectionContainer { Column { text() } } else text()
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
