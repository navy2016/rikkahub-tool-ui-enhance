package me.rerere.rikkahub.viewporttest

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.ui.pages.container.TerminalConfiguredTranscript
import me.rerere.rikkahub.ui.pages.container.TerminalRenderButton
import me.rerere.rikkahub.ui.pages.container.TerminalRenderModeDialog
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptCompositionObserver
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRowsSyncState
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class TerminalRenderModeInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val timeout = Timeout.seconds(45)

    private data class Geometry(
        val rows: List<Row>, val vertical: Pair<Int, Int>, val horizontal: Pair<Int, Int>,
    )
    private data class Row(val text: AnnotatedString, val x: Float, val y: Float, val width: Int, val height: Int)

    private fun geometry(fixture: Fixture): Geometry {
        compose.waitForIdle()
        val rows = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
        ).fetchSemanticsNodes().map { node ->
            Row(node.config[SemanticsProperties.Text].single(), node.positionInRoot.x, node.positionInRoot.y,
                node.size.width, node.size.height)
        }
        return compose.runOnIdle {
            assertEquals(fixture.frame.rows.map { it.text }, rows.map { it.text })
            Geometry(rows, fixture.vertical.value to fixture.vertical.maxValue,
                fixture.horizontal.value to fixture.horizontal.maxValue)
        }
    }

    @Test
    fun rendererSwitchKeepsNaturalGeometrySelectionAndBothScrollOffsets() {
        val fixture = Fixture()
        compose.setContent { fixture.Content() }
        compose.waitForIdle()
        compose.runOnIdle { fixture.pan(fixture.vertical.maxValue / 2 + 3, 41) }
        compose.waitForIdle()
        val baseline = geometry(fixture)
        assertTrue(baseline.vertical.first > 0)
        assertEquals(41, baseline.horizontal.first)
        for (mode in listOf(TerminalRenderMode.CHUNKED, TerminalRenderMode.FLAT, TerminalRenderMode.CHUNKED_LAYERS)) {
            compose.runOnIdle { fixture.mode = mode }
            assertEquals("mode=$mode", baseline, geometry(fixture))
        }
        compose.runOnIdle {
            fixture.tui = true
            fixture.publish()
        }
        val tui = geometry(fixture)
        for (mode in TerminalRenderMode.entries) {
            compose.runOnIdle { fixture.mode = mode }
            assertEquals("TUI fallback mode=$mode", tui, geometry(fixture))
            assertEquals(0, fixture.composedChunks)
        }
    }

    @Test
    fun renderButtonCancelApplyResetAndLongPressAreExplicit() {
        var value by mutableStateOf(TerminalRenderMode.DEFAULT)
        var show by mutableStateOf(false)
        var applied: TerminalRenderMode? = null
        var edits = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.size(360.dp)) {
                    TerminalRenderButton(value, value, onEditItems = { edits++ }, onClick = { show = true })
                    if (show) TerminalRenderModeDialog(
                        value = value, hasHistoryChunks = true, usesTuiViewport = false,
                        onDismiss = { show = false }, onSave = { applied = it; value = it; show = false },
                    )
                }
            }
        }
        compose.onNodeWithTag("terminal-render-button").assertIsDisplayed().performClick()
        compose.onNodeWithTag("terminal-render-option-chunkedLayers").assertIsSelected()
        compose.onNodeWithTag("terminal-render-option-eager").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("terminal-render-cancel").performClick()
        assertEquals(TerminalRenderMode.DEFAULT, value)
        assertEquals(null, applied)
        compose.onNodeWithTag("terminal-render-button").performClick()
        compose.onNodeWithTag("terminal-render-option-eager").performScrollTo().assertIsNotSelected().performClick()
        compose.onNodeWithTag("terminal-render-apply").performClick()
        assertEquals(TerminalRenderMode.FLAT, applied)
        compose.onNodeWithTag("terminal-render-button").performClick()
        compose.onNodeWithTag("terminal-render-option-lazyHistoryIme").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("terminal-render-apply").performClick()
        assertEquals(TerminalRenderMode.VIRTUAL_HISTORY_IME, applied)
        compose.onNodeWithTag("terminal-render-button").performClick()
        compose.onNodeWithTag("terminal-render-reset").performScrollTo().performClick()
        compose.onNodeWithTag("terminal-render-option-chunkedLayers").performScrollTo()
        compose.onNodeWithTag("terminal-render-option-chunkedLayers").assertIsSelected()
        compose.onNodeWithTag("terminal-render-apply").performClick()
        assertEquals(TerminalRenderMode.DEFAULT, applied)
        compose.onNodeWithTag("terminal-render-button").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, edits) }
    }

    private inner class Fixture : TerminalTranscriptCompositionObserver {
        val terminal = TerminalEmulator(initialColumns = 80, initialRows = 24, maxScrollbackLines = 260).apply {
            feed("\u001B[?25l" + (0 until 284).joinToString("\r\n") {
                "r$it \u001B[${31 + it % 6}m" + (if (it % 3 == 0) "中文 e\u0301" else "ASCII") +
                    "\u001B[0m " + "x".repeat(12 + it % 35)
            })
        }
        var frame by mutableStateOf(terminal.renderFrame())
        private val rows = createTerminalRenderedRows(frame)
        private val sync = createTerminalRenderedRowsSyncState(frame, rows)
        var mode by mutableStateOf(TerminalRenderMode.DEFAULT)
        var tui by mutableStateOf(false)
        val vertical = ScrollState(0)
        val horizontal = ScrollState(0)
        private lateinit var scope: CoroutineScope
        var composedChunks = 0

        override fun historyChunkDelta(chunks: Int, rows: Int) { composedChunks += chunks }
        override fun activeScreenDelta(screens: Int) = Unit

        fun publish() {
            val next = terminal.renderFrame()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(this.rows, next, tui, nowMs = 1_000L, syncState = sync)
                frame = next
            }
        }

        fun pan(y: Int, x: Int) { scope.launch { vertical.scrollTo(y); horizontal.scrollTo(x) } }

        @Composable fun Content() {
            scope = rememberCoroutineScope()
            MaterialTheme {
                val style = TextStyle(fontFamily = JetbrainsMono, fontSize = 14.sp, lineHeight = 14.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
                Column(Modifier.size(300.dp, 260.dp).horizontalScroll(horizontal).verticalScroll(vertical)) {
                    SelectionContainer {
                        Column {
                            TerminalConfiguredTranscript(rows.toList(), style,
                                terminalHistoryChunks(frame.historyCount, frame.historyStartSequence, tui),
                                mode, observer = this@Fixture)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
