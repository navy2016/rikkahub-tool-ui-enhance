package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.data.container.terminalRegularHistoryAllowed
import me.rerere.rikkahub.ui.pages.container.*
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class TerminalTuiDisplayInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout = Timeout.seconds(120)
    private val style = TextStyle(color = Color.Green, fontFamily = JetbrainsMono, fontSize = 18.sp,
        lineHeight = 18.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
    private val textMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)

    private fun layout(): TextLayoutResult {
        val node = compose.onAllNodes(textMatcher, useUnmergedTree = true).fetchSemanticsNodes().single()
        val values = mutableListOf<TextLayoutResult>()
        assertTrue(checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(values))
        return values.single()
    }

    private fun pixels(): IntArray {
        val image = compose.onNodeWithTag("overlay-pixels").captureToImage()
        val map = image.toPixelMap()
        return IntArray(image.width * image.height) { map[it % image.width, it / image.width].toArgb() }
    }

    @Test fun tuiDisplayCursorOverlayPreservesNaturalTextAndChangesOnlyCursorPixels() {
        val text = AnnotatedString("abc 中 e\u0301 👩‍💻")
        val row = TerminalRenderedRowState(1, text)
        val cursor = TerminalCursorState(null)
        val cursors = mapOf(1L to cursor)
        var scaled by mutableStateOf(false)
        var rtl by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides if (scaled) Density(1f, 1.3f) else Density(1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalTerminalCursorOverlays provides cursors) {
                MaterialTheme {
                    Box(Modifier.size(360.dp, 80.dp).background(Color.Black).testTag("overlay-pixels")) {
                        TerminalRenderedRow(row, style)
                    }
                }
            }
        }
        for (scale in listOf(false, true)) for (direction in listOf(false, true)) {
            compose.runOnIdle { cursor.value = null; scaled = scale; rtl = direction }
            compose.waitForIdle()
            val original = layout()
            val base = pixels()
            for (shape in listOf(TerminalEmulator.CursorShape.BAR, TerminalEmulator.CursorShape.UNDERLINE)) {
                compose.runOnIdle { cursor.value = TerminalEmulator.CursorOverlay(0, 1, 0, 1, shape, Color.Red) }
                compose.waitForIdle()
                val next = layout()
                assertEquals(text, next.layoutInput.text)
                assertEquals(original.size, next.size)
                assertEquals(original.firstBaseline, next.firstBaseline, 0f)
                val painted = pixels()
                assertEquals(base.size, painted.size)
                val changed = base.indices.filter { base[it] != painted[it] }
                assertTrue("No cursor was actually drawn", changed.isNotEmpty())
                assertTrue("Cursor replaced the text rather than overlaying it", changed.size < 120)
            }
        }
        Log.i("TerminalTuiDisplay", "TUI_DISPLAY {\"case\":\"cursorPixels\",\"variants\":8,\"passed\":true}")
    }

    @Test fun tuiDisplayCursorMovesInvalidateDrawWithoutRecomposingTextOwner() {
        val row = TerminalRenderedRowState(1, AnnotatedString("abcdef"))
        val cursor = TerminalCursorState(null)
        val cursors = mapOf(1L to cursor)
        var composed = 0
        compose.setContent {
            CompositionLocalProvider(LocalTerminalCursorOverlays provides cursors) {
                MaterialTheme {
                    SideEffect { composed++ }
                    Box(Modifier.size(300.dp, 70.dp).background(Color.Black).testTag("overlay-pixels")) {
                        TerminalRenderedRow(row, style)
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = composed
        val original = layout().size
        val withoutCursor = pixels()
        repeat(24) { index ->
            compose.runOnIdle { cursor.value = TerminalEmulator.CursorOverlay(index % 6, index % 6 + 1,
                0, 1, TerminalEmulator.CursorShape.BAR, Color.Red) }
            compose.waitForIdle()
            assertEquals(original, layout().size)
            assertFalse("Cursor draw was missing", pixels().contentEquals(withoutCursor))
        }
        assertEquals(before, composed)
        Log.i("TerminalTuiDisplay", "TUI_DISPLAY {\"case\":\"cursorMoves\",\"moves\":24,\"parentExtra\":0,\"passed\":true}")
    }

    private inner class PointerFixture {
        val terminal = TerminalEmulator(40, 6, 50).apply { feed("\u001b[?25l" +
            (0..23).joinToString("\r\n") { "r$it ${"x".repeat(32)}" }) }
        var frame by mutableStateOf(terminal.renderFrame(), referentialEqualityPolicy())
        val store = TerminalRowStore(frame)
        var source by mutableStateOf(TerminalRenderSnapshot.capture(frame, store), referentialEqualityPolicy())
        val map = TerminalPointerMap()
        val controller = TerminalViewportController()
        val eager = ScrollState(Int.MAX_VALUE)
        val horizontal = ScrollState(0)
        val lazy = androidx.compose.foundation.lazy.LazyListState()
        val measurements = TerminalLazyItemMeasurements()
        var mounted by mutableStateOf(true)
        var cell by mutableStateOf(10)
        var viewport by mutableStateOf(0)
        var bound: TerminalBoundViewport? = null

        @Composable fun Content() {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    if (mounted) {
                        val current = rememberTerminalBoundViewport("pointer", controller, eager, lazy, measurements,
                            frame, style, false, { TerminalViewportMetrics(eager.maxValue, viewport, 18, 8) },
                            TerminalViewportGestureConfig(false, false, 2))
                        SideEffect { bound = current }
                        Column(Modifier.size(300.dp, 190.dp).onGloballyPositioned { map.inputCoordinates = it }) {
                            Spacer(Modifier.height(32.dp))
                            TerminalTranscriptViewport(frame, source.rows, style,
                                terminalHistoryChunks(frame.historyCount, frame.historyStartSequence, false),
                                TerminalRenderMode.DEFAULT, current, horizontal, false, false,
                                Modifier.weight(1f).testTag("pointer-viewport").onGloballyPositioned { viewport = it.size.height },
                                rowSource = source, pointerMap = map, cellWidthPx = cell)
                        }
                    }
                }
            }
        }
    }

    @Test fun tuiDisplayMouseHitsMeasuredScreenRowsAndRejectsHistoryStatusAndNegativePixels() {
        val f = PointerFixture()
        compose.setContent { f.Content() }
        compose.waitUntil(5000) { f.controller.state.value.initialized && f.eager.value < Int.MAX_VALUE }
        compose.waitForIdle()
        val node = compose.onAllNodes(textMatcher, true).fetchSemanticsNodes().first {
            it.config[SemanticsProperties.Text].single() == f.frame.rows[f.frame.historyCount].text
        }
        val origin = f.map.inputCoordinates!!.localToRoot(Offset.Zero)
        val rowX = node.positionInRoot.x - origin.x
        val rowY = node.positionInRoot.y - origin.y
        compose.runOnIdle {
            assertNull(f.map.hit(5f, 10f))
            assertNull(f.map.hit(rowX - 0.2f, rowY + 1f))
            assertNull(f.map.hit(rowX + 1f, rowY - 0.2f))
            val hit = requireNotNull(f.map.hit(rowX + 11f, rowY + 1f))
            assertEquals(0, hit.row)
            assertEquals(1, hit.column)
            assertEquals(f.frame.screenGeneration, hit.generation)
            assertEquals(6, f.map.retainedRows)
            assertNull(terminalPointerColumn(Float.NaN, 10, 40))
            assertNull(terminalPointerColumn(-0.01f, 10, 40))
        }
        compose.runOnIdle { f.mounted = false }
        compose.waitForIdle()
        assertEquals(0, f.map.retainedRows)
        assertNull(f.map.hit(11f, 40f))
        Log.i("TerminalTuiDisplay", "TUI_DISPLAY {\"case\":\"pointerLayout\",\"screenRows\":6,\"disposedRows\":0,\"passed\":true}")
    }

    @Test fun tuiDisplayRegularHistoryUsesNaturalChunkGeometryAndKeepsEveryRow() {
        val f = PointerFixture()
        val command = "pi --tui-mode regular --resume"
        var regular by mutableStateOf(false)
        var chunks = 0
        val observer = object : TerminalTranscriptCompositionObserver {
            override fun historyChunkDelta(count: Int, rows: Int) { chunks += count }
            override fun activeScreenDelta(screens: Int) = Unit
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme {
                    Column {
                        val mode = if (regular) TerminalRenderMode.REGULAR_HISTORY else TerminalRenderMode.DEFAULT
                        val allowed = terminalRegularHistoryAllowed(mode, command, false, false, true, false, false)
                        TerminalConfiguredTranscript(f.source.rows, style,
                            terminalHistoryChunks(f.frame.historyCount, f.frame.historyStartSequence, !allowed), mode,
                            observer = observer, rowSource = f.source)
                    }
                }
            }
        }
        fun geometry() = compose.onAllNodes(textMatcher, true).fetchSemanticsNodes().map {
            Triple(it.config[SemanticsProperties.Text].single(), it.positionInRoot, it.size)
        }
        compose.waitForIdle()
        val original = geometry()
        assertEquals(f.frame.rows.size, original.size)
        assertEquals(0, chunks)
        compose.runOnIdle { regular = true }
        compose.waitForIdle()
        assertEquals(original, geometry())
        assertTrue(chunks > 0)
        compose.runOnIdle { regular = false }
        compose.waitForIdle()
        assertEquals(original, geometry())
        assertEquals(0, chunks)
        Log.i("TerminalTuiDisplay", "TUI_DISPLAY {\"case\":\"regularHistory\",\"rows\":24,\"passed\":true}")
    }
}
