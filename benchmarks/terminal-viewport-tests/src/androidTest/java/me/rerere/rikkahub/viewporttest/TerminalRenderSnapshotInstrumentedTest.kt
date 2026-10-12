package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.ui.pages.container.TerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalLazyLayoutPass
import me.rerere.rikkahub.ui.pages.container.TerminalRenderSnapshot
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptViewport
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRowsSyncState
import me.rerere.rikkahub.ui.pages.container.rememberTerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.rememberTerminalVirtualHistoryPolicy
import me.rerere.rikkahub.ui.pages.container.requireTerminalRenderPair
import me.rerere.rikkahub.ui.pages.container.synchronizeTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Synthetic old-content negative control plus actual production binding/Text publication stress. */
class TerminalRenderSnapshotInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(60)

    @Test fun renderSnapshotRetainedChildRejectsLiveFrameButAcceptsItsCapturedPair() {
        val terminal = TerminalEmulator(80, 6, 128).apply { feed("\u001B[?25lseed") }
        val frame = mutableStateOf(terminal.renderFrame(), referentialEqualityPolicy())
        val rows = createTerminalRenderedRows(frame.value)
        val captured = TerminalRenderSnapshot.capture(frame.value, rows)
        val pass = TerminalLazyLayoutPass(captured.frame, "fixed-font")
        var childPulse by mutableIntStateOf(0)
        var observedPulse = -1
        var rejectedLivePair = false
        compose.setContent {
            // Retain the old parent's arguments deliberately, then recompose ONLY this child.
            // A child that re-reads live states can mix them with that retained old pass.
            Column {
                val pulse = childPulse
                val live = frame.value
                val liveRows = rows.toList()
                requireTerminalRenderPair(pass, captured.frame, captured.rows)
                val rejected = runCatching { requireTerminalRenderPair(pass, live, liveRows) }.isFailure
                SideEffect { observedPulse = pulse; rejectedLivePair = rejected }
            }
        }
        compose.waitForIdle()
        assertEquals(false, rejectedLivePair)
        compose.runOnIdle {
            terminal.feed((0 until 12).joinToString("\r\n", prefix = "\r\n") { "new-$it" })
            val next = terminal.renderFrame()
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(rows, next, false, nowMs = 100)
                frame.value = next
                childPulse++
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, observedPulse)
            assertTrue("negative control did not expose stale-pass/live-frame mismatch", rejectedLivePair)
            assertSame(pass.frame, captured.frame)
            assertEquals(6, captured.rows.size)
            assertTrue(rows.size > captured.rows.size)
        }
    }

    private class Fixture(val mode: TerminalRenderMode, private val legacyCapture: Boolean = false) {
        val terminal = TerminalEmulator(80, 6, 128).apply {
            feed("\u001B[?25l" + (0 until 22).joinToString("\r\n") { "row-$it 中文" })
        }
        private val initialFrame = terminal.renderFrame()
        val rows = createTerminalRenderedRows(initialFrame)
        val sync = createTerminalRenderedRowsSyncState(initialFrame, rows)
        var captureCalls = 0
        var published by mutableStateOf(capture(initialFrame), referentialEqualityPolicy())
            private set
        val frame: TerminalEmulator.RenderFrame get() = published.frame
        val controller = TerminalViewportController()
        val eager = ScrollState(Int.MAX_VALUE)
        val lazy = LazyListState()
        val horizontal = ScrollState(0)
        val measurements = TerminalLazyItemMeasurements()
        var ime by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        var mountId by mutableIntStateOf(0)
        var childPulse by mutableIntStateOf(0)
        var panelPulse by mutableIntStateOf(0)
        var viewportHeight by mutableIntStateOf(0)
        lateinit var scope: CoroutineScope
        lateinit var bound: TerminalBoundViewport
        var renderedFrame: TerminalEmulator.RenderFrame? = null
        var consumed = 0
        var visibleModeMask = 0
        var panelCompositions = 0
        var committedPulse = -1
        var consumedSnapshot: TerminalRenderSnapshot? = null

        private fun capture(frame: TerminalEmulator.RenderFrame): TerminalRenderSnapshot {
            captureCalls++ // Test-only factory invocation count, not a production trace/counter.
            return TerminalRenderSnapshot.capture(frame, rows)
        }

        fun publish(index: Int) {
            terminal.feed("\r\noutput-$index 中文")
            publishFrame(terminal.renderFrame(), index.toLong())
        }

        fun publishFrame(next: TerminalEmulator.RenderFrame, nowMs: Long = 100) {
            Snapshot.withMutableSnapshot {
                synchronizeTerminalRenderedRows(rows, next, false, nowMs = nowMs, syncState = sync)
                published = capture(next)
                childPulse++
            }
        }

        @Composable fun Content() {
            scope = rememberCoroutineScope()
            if (mounted) key(mountId) { Panel() }
        }

        @Composable private fun Panel() {
            val pulse = panelPulse
            val style = TextStyle(color = Color.Green, fontFamily = JetbrainsMono,
                fontSize = 12.sp, lineHeight = 12.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
            val density = LocalDensity.current
            val cell = rememberTextMeasurer().measure("W", style).size.height.coerceAtLeast(1)
            val tail = with(density) { 8.dp.roundToPx() }
            // The control is the former call site; both arms use the SAME factory and renderer.
            val captured = if (legacyCapture) capture(frame) else published
            SideEffect { panelCompositions++; committedPulse = pulse }
            val chunks = remember(captured.frame.historyCount, captured.frame.historyStartSequence) {
                terminalHistoryChunks(captured.frame.historyCount, captured.frame.historyStartSequence, false)
            }
            val policy = rememberTerminalVirtualHistoryPolicy(mountId, mode, ime)
            val wants = mode.isVirtualHistory && policy.allows(chunks.isNotEmpty(), true, false, false, ime, true)
            val current = rememberTerminalBoundViewport(mountId, controller, eager, lazy, measurements,
                captured.frame, style, wants,
                metrics = { TerminalViewportMetrics(eager.maxValue, viewportHeight, cell, tail,
                    imeVisible = ime, avoidIme = ime) }, gestureConfig = TerminalViewportGestureConfig(true, false, 2))
            bound = current
            Box(Modifier.size(320.dp, if (ime) 160.dp else 240.dp).testTag("snapshot-output")
                .onSizeChanged { viewportHeight = it.height }) {
                Column(Modifier.fillMaxSize()) {
                    val pulse = childPulse // Independent nested invalidation with retained parent arguments.
                    requireTerminalRenderPair(current.pass, captured.frame, captured.rows)
                    SideEffect {
                        check(pulse >= 0)
                        consumed++
                        consumedSnapshot = captured
                        visibleModeMask = visibleModeMask or if (current.virtualHistoryEnabled) 2 else 1
                    }
                    TerminalTranscriptViewport(captured.frame, captured.rows, style, chunks, mode, current,
                        horizontal, panEnabled = true, selectionMode = false,
                        modifier = Modifier.fillMaxSize().drawWithContent {
                            drawContent()
                            renderedFrame = captured.frame
                        })
                }
            }
        }
    }

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            f.renderedFrame === f.frame && f.bound.pass.frame === f.frame && f.controller.state.value.initialized &&
                f.controller.state.value.scrollEffect == null && f.bound.binding.activeWriters == 0
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(f.bound.binding.maximumWriters <= 1) }
    }

    @Test fun renderSnapshotNoOutputRecompositionsReusePublicationAndMeasureRetainedChildWork() {
        val legacy = Fixture(TerminalRenderMode.DEFAULT, legacyCapture = true)
        val candidate = Fixture(TerminalRenderMode.DEFAULT)
        compose.setContent { Column { legacy.Content(); candidate.Content() } }
        settle(legacy)
        settle(candidate)
        val original = compose.runOnIdle { candidate.published }
        val rowView = original.rows
        val capturesBefore = compose.runOnIdle { legacy.captureCalls to candidate.captureCalls }
        val panelsBefore = compose.runOnIdle { legacy.panelCompositions to candidate.panelCompositions }
        val childrenBefore = compose.runOnIdle { legacy.consumed to candidate.consumed }
        repeat(32) { index ->
            compose.runOnIdle { legacy.panelPulse++; candidate.panelPulse++ }
            compose.waitForIdle() // Every update must produce an independently committed panel.
            compose.runOnIdle {
                assertEquals(index + 1, legacy.committedPulse)
                assertEquals(index + 1, candidate.committedPulse)
                assertSame(original, candidate.published)
                assertSame(original, candidate.consumedSnapshot)
                assertSame(rowView, candidate.published.rows)
            }
        }
        compose.runOnIdle {
            val legacyCaptures = legacy.captureCalls - capturesBefore.first
            val publishedCaptures = candidate.captureCalls - capturesBefore.second
            assertEquals(32, legacyCaptures)
            assertEquals(0, publishedCaptures)
            assertEquals(32, legacy.panelCompositions - panelsBefore.first)
            assertEquals(32, candidate.panelCompositions - panelsBefore.second)
            // Report child commits rather than assuming that factory savings imply Text/FPS gains.
            Log.i("TerminalRenderSnapshot", "SNAPSHOT_WORK " + JSONObject()
                .put("updates", 32)
                .put("legacyCaptures", legacyCaptures)
                .put("publishedCaptures", publishedCaptures)
                .put("legacyPanelCompositions", legacy.panelCompositions - panelsBefore.first)
                .put("publishedPanelCompositions", candidate.panelCompositions - panelsBefore.second)
                .put("legacyChildCompositions", legacy.consumed - childrenBefore.first)
                .put("publishedChildCompositions", candidate.consumed - childrenBefore.second)
                .put("sameSnapshotChecks", 32)
                .put("sameRowViewChecks", 32))
        }
    }

    @Test fun renderSnapshotSameFrameAndSameRevisionPublicationsStillReachTheChild() {
        val f = Fixture(TerminalRenderMode.VIRTUAL_HISTORY_IME)
        compose.setContent { f.Content() }
        settle(f)
        val before = compose.runOnIdle { f.published }
        val passBefore = compose.runOnIdle { f.bound.pass }
        compose.runOnIdle { f.publishFrame(before.frame) }
        settle(f)
        val sameFrame = compose.runOnIdle {
            assertNotSame(before, f.published)
            assertSame(before.frame, f.published.frame)
            assertSame(before.rows, f.published.rows)
            assertSame(f.published, f.consumedSnapshot)
            assertSame(passBefore, f.bound.pass)
            f.published
        }
        val replacement = sameFrame.frame.copy()
        compose.runOnIdle { f.publishFrame(replacement) }
        settle(f)
        compose.runOnIdle {
            assertNotSame(sameFrame, f.published)
            assertSame(replacement, f.published.frame)
            assertEquals(before.frame.revision, f.published.frame.revision)
            assertSame(before.rows, f.published.rows)
            assertSame(f.published, f.consumedSnapshot)
            assertNotSame(passBefore, f.bound.pass)
            assertSame(replacement, f.bound.pass.frame)
        }
    }

    private fun exercise(mode: TerminalRenderMode) {
        val f = Fixture(mode)
        compose.setContent { f.Content() }
        settle(f)
        val job = compose.runOnIdle {
            f.scope.launch {
                repeat(160) { index ->
                    withFrameNanos { }
                    if (index == 24) f.ime = true
                    if (index == 90) f.ime = false
                    f.publish(index)
                }
            }
        }
        compose.waitUntil(25_000) { job.isCompleted }
        settle(f)
        compose.runOnIdle {
            assertEquals(128, f.frame.historyCount)
            assertTrue(f.consumed > 2)
            if (mode == TerminalRenderMode.VIRTUAL_HISTORY) assertEquals(3, f.visibleModeMask)
            assertTrue(f.controller.state.value.autoScroll)
            f.mounted = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, f.measurements.retainedRows)
            f.mountId++
            f.renderedFrame = null
            f.mounted = true
        }
        settle(f)
        compose.runOnIdle {
            assertEquals(mode.isVirtualHistory, f.bound.virtualHistoryEnabled)
            assertSame(f.frame, f.bound.pass.frame)
            if (mode.isVirtualHistory) assertNotNull(f.bound.binding.observation())
        }
        val expected = compose.runOnIdle { f.frame.rows.last().text }
        val row = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().single { it.config[SemanticsProperties.Text].singleOrNull() == expected }
        val viewport = compose.onNodeWithTag("snapshot-output").fetchSemanticsNode()
        val top = row.positionInRoot.y - viewport.positionInRoot.y
        assertTrue("final row clipped after streaming/remount", top >= -1f && top + row.size.height <= viewport.size.height + 1f)
    }

    @Test fun renderSnapshotDefaultStreamingAndRemountPreserveCompletePublication() = exercise(TerminalRenderMode.DEFAULT)
    @Test fun renderSnapshotVirtualImeFallbackAndRemountPreserveCompletePublication() = exercise(TerminalRenderMode.VIRTUAL_HISTORY)
    @Test fun renderSnapshotStableVirtualImeAndRemountPreserveCompletePublication() = exercise(TerminalRenderMode.VIRTUAL_HISTORY_IME)
}
