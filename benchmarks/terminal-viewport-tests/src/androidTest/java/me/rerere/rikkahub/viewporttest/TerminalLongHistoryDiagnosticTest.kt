package me.rerere.rikkahub.viewporttest

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.ui.pages.container.TerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalRenderSnapshot
import me.rerere.rikkahub.ui.pages.container.TerminalRowStore
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptViewport
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.rememberTerminalBoundViewport
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Non-acceptance component diagnosis. No cloned renderer, native PTY or real system IME here. */
class TerminalLongHistoryDiagnosticTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(240)
    private fun now() = SystemClock.elapsedRealtimeNanos()

    private class Counters {
        var measureNs = 0L
        var placeNs = 0L
        var drawNs = 0L
        var commitSpanNs = 0L
        var measureCount = 0
        var placeCount = 0
        var drawCount = 0
        var commits = 0
        var feedCaptureNs = 0L
        var syncNs = 0L
        var syncVisited = 0L
        var syncSkipped = 0L
        var fifoUses = 0
        var firstDrawNs = 0L
        var lastDrawnRevision = -1L
        val publicationNs = ArrayList<Long>()
    }

    private class Mount(val terminal: TerminalEmulator, val tui: Boolean) {
        private val frameStarted = SystemClock.elapsedRealtimeNanos()
        private val initial = terminal.renderFrame()
        val initialFrameNs = SystemClock.elapsedRealtimeNanos() - frameStarted
        private val stateStarted = SystemClock.elapsedRealtimeNanos()
        val store = TerminalRowStore(initial)
        var source by mutableStateOf(TerminalRenderSnapshot.capture(initial, store), referentialEqualityPolicy())
        val createStateNs = SystemClock.elapsedRealtimeNanos() - stateStarted
        val controller = TerminalViewportController()
        val measurements = TerminalLazyItemMeasurements()
        val eager = ScrollState(Int.MAX_VALUE)
        val horizontal = ScrollState(0)
        val lazy = LazyListState()
        var heightDp by mutableIntStateOf(240)
        var viewportHeight by mutableIntStateOf(0)
        var drawPulse by mutableIntStateOf(0)
        var streamRequest by mutableIntStateOf(0)
        var completedTicks = 0
        var outputSequence = 0
        var cellHeight = 1
        var tail = 1
        var stats = Counters()
        lateinit var bound: TerminalBoundViewport
        val hasBinding: Boolean get() = ::bound.isInitialized

        fun metrics() = TerminalViewportMetrics(eager.maxValue, viewportHeight, cellHeight, tail,
            usesTuiViewport = tui, imeVisible = heightDp < 240, avoidIme = heightDp < 240)

        fun publishTick(step: Int, resize: Boolean) {
            val started = SystemClock.elapsedRealtimeNanos()
            repeat(2) { terminal.feed("live-${outputSequence++} \u001b[32m中文\u001b[0m e\u0301 👩‍💻\r\n") }
            val frame = terminal.renderFrame()
            val fed = SystemClock.elapsedRealtimeNanos()
            Snapshot.withMutableSnapshot {
                check(!store.publish(frame, false, tui, false, SystemClock.uptimeMillis()))
                source = TerminalRenderSnapshot.capture(frame, store)
                if (resize) heightDp = if (step in 8..15) 140 else 240
            }
            val synced = SystemClock.elapsedRealtimeNanos()
            stats.feedCaptureNs += fed - started
            stats.syncNs += synced - fed
            stats.syncVisited += store.sync.lastVisitedTextRows
            stats.syncSkipped += store.sync.lastSkippedHistoryRows
            if (store.sync.lastUsedFifoFastPath) stats.fifoUses++
            stats.publicationNs.add(synced - started)
            completedTicks++
        }

        @Composable fun Content() {
            val request = streamRequest
            LaunchedEffect(request) {
                if (request != 0) repeat(24) { step ->
                    withFrameNanos { }
                    publishTick(step, resize = request == 1)
                }
            }
            CompositionLocalProvider(LocalDensity provides Density(1.33f, 1.3f),
                LocalLayoutDirection provides LayoutDirection.Ltr) {
                Output()
            }
        }

        @Composable private fun Output() {
            val entered = SystemClock.elapsedRealtimeNanos()
            val captured = source
            val frame = captured.frame
            val density = LocalDensity.current
            val style = TextStyle(color = Color(0xFF00E676), fontFamily = JetbrainsMono,
                fontSize = 12.sp, lineHeight = 12.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))
            cellHeight = rememberTextMeasurer().measure("W", style).size.height.coerceAtLeast(1)
            tail = with(density) { 8.dp.roundToPx() }
            val chunks = remember(frame.historyCount, frame.historyStartSequence) {
                terminalHistoryChunks(frame.historyCount, frame.historyStartSequence, tui)
            }
            val binding = rememberTerminalBoundViewport(this, controller, eager, lazy, measurements, frame, style,
                wantsVirtual = false, metrics = { metrics() }, gestureConfig = TerminalViewportGestureConfig(true, false, 2))
            bound = binding
            SideEffect {
                stats.commits++
                stats.commitSpanNs += SystemClock.elapsedRealtimeNanos() - entered
            }
            MaterialTheme {
                Box(Modifier.size(320.dp, heightDp.dp).testTag("long-history-output")
                    .onSizeChanged { viewportHeight = it.height }) {
                    TerminalTranscriptViewport(frame, captured.rows, style, chunks, TerminalRenderMode.DEFAULT,
                        binding, horizontal, true, false,
                        Modifier.fillMaxSize().layout { child, constraints ->
                            val started = SystemClock.elapsedRealtimeNanos()
                            val placeable = child.measure(constraints)
                            stats.measureNs += SystemClock.elapsedRealtimeNanos() - started
                            stats.measureCount++
                            layout(placeable.width, placeable.height) {
                                val placing = SystemClock.elapsedRealtimeNanos()
                                placeable.placeRelative(0, 0)
                                stats.placeNs += SystemClock.elapsedRealtimeNanos() - placing
                                stats.placeCount++
                            }
                        }.drawWithContent {
                            drawPulse
                            val started = SystemClock.elapsedRealtimeNanos()
                            drawContent()
                            val ended = SystemClock.elapsedRealtimeNanos()
                            stats.drawNs += ended - started
                            stats.drawCount++
                            if (stats.firstDrawNs == 0L) stats.firstDrawNs = ended
                            stats.lastDrawnRevision = frame.revision
                        }, rowSource = captured)
                }
            }
        }
    }

    private fun settle(mount: Mount, expectedTicks: Int) {
        var lastDraw = 0
        var stable = 0
        var lastRevision = -1L
        compose.waitUntil(45_000) {
            var done = false
            compose.runOnUiThread {
                val count = mount.stats.drawCount
                val frame = mount.source.frame
                val state = mount.controller.state.value
                val geometry = if (mount.hasBinding) mount.measurements.peekEager(mount.bound.pass) else null
                val ready = mount.completedTicks == expectedTicks && geometry != null && geometry.frame === frame &&
                    mount.bound.pass.frame === frame && state.initialized && state.scrollEffect == null &&
                    state.gesture == null && mount.bound.binding.activeWriters == 0 && !mount.eager.isScrollInProgress &&
                    mount.stats.lastDrawnRevision == frame.revision && mount.eager.value == geometry.followTarget(mount.metrics())
                if (count > lastDraw && ready) {
                    stable = if (lastRevision == frame.revision) stable + 1 else 1
                    lastRevision = frame.revision
                } else if (!ready) stable = 0
                lastDraw = count
                done = stable >= 2
                if (!done) mount.drawPulse++
            }
            done
        }
    }

    private fun sample(mount: Mount, size: Int, round: Int, phase: String, started: Long,
        initialNodes: Long, initialMeasures: Long, initialHeightVisits: Long, elapsed: Long) {
        val source = mount.source
        val frame = source.frame
        val index = frame.screenStartRow + frame.cursorRow
        val row = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(frame.rows[index].text)), true)
            .fetchSemanticsNodes().single()
        val viewport = compose.onNodeWithTag("long-history-output").fetchSemanticsNode()
        val top = row.positionInRoot.y - viewport.positionInRoot.y
        assertTrue("blank cursor clipped", top >= -1 && top + row.size.height <= viewport.size.height + 1)
        assertEquals(size, frame.historyCount)
        assertEquals(size + 24, mount.measurements.retainedRows)
        assertTrue(mount.bound.binding.maximumWriters <= 1)
        val stats = mount.stats
        Log.i("TerminalLongHistory", "LONG_SAMPLE " + JSONObject().put("size", size)
            .put("profile", if (mount.tui) "tui" else "ordinary").put("round", round).put("warmup", round == 0)
            .put("phase", phase).put("stateCreateNs", if (phase == "streamIme") 0 else mount.createStateNs)
            .put("initialFrameNs", if (phase == "streamIme") 0 else mount.initialFrameNs)
            .put("firstDrawNs", (stats.firstDrawNs - started).coerceAtLeast(0)).put("completedNs", elapsed)
            .put("rootMeasureNs", stats.measureNs).put("rootPlaceNs", stats.placeNs).put("rootDrawNs", stats.drawNs)
            .put("commitSpanNs", stats.commitSpanNs).put("commits", stats.commits)
            .put("rootMeasures", stats.measureCount).put("rootPlacements", stats.placeCount).put("rootDraws", stats.drawCount)
            .put("feedCaptureNs", stats.feedCaptureNs).put("rowSyncNs", stats.syncNs)
            .put("syncVisited", stats.syncVisited).put("syncSkipped", stats.syncSkipped).put("fifoUses", stats.fifoUses)
            .put("publicationNs", JSONArray(stats.publicationNs)).put("ticks", mount.completedTicks)
            .put("createdNodes", mount.measurements.createdRowNodes - initialNodes)
            .put("rowMeasures", mount.measurements.measuredRowCount - initialMeasures)
            .put("heightVisits", mount.measurements.eagerVisitedHistoryRows - initialHeightVisits)
            .put("retainedRows", mount.measurements.retainedRows).put("writers", mount.bound.binding.maximumWriters)
            .put("cursorTop", top.toDouble()).put("cursorBottom", (top + row.size.height).toDouble())
            .put("rowHeight", row.size.height).put("viewportHeight", viewport.size.height))
    }

    private fun diagnose(size: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun apk(context: android.content.Context): String {
            check(context.applicationInfo.splitSourceDirs.isNullOrEmpty())
            val sha = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val n = stream.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
            }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        Log.i("TerminalLongHistory", "LONG_DEVICE " + JSONObject().put("size", size).put("pid", android.os.Process.myPid())
            .put("fingerprint", android.os.Build.FINGERPRINT).put("targetApk", apk(instrumentation.targetContext))
            .put("testApk", apk(instrumentation.context)).put("density", 1.33).put("fontScale", 1.3))
        var mounted by mutableStateOf<Mount?>(null, referentialEqualityPolicy())
        compose.setContent { mounted?.let { key(it) { it.Content() } } }
        repeat(4) { round ->
            for (tui in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                val terminal = TerminalEmulator(80, 24, size).apply {
                    feed((0 until size + 23).joinToString("\r\n") { "line-$it 中文 e\u0301 👩‍💻" } + "\r\n")
                }
                lateinit var first: Mount
                var started = 0L
                compose.runOnIdle { first = Mount(terminal, tui); started = now(); mounted = first }
                settle(first, 0)
                sample(first, size, round, "cold", started, 0, 0, 0, now() - started)
                val nodes = first.measurements.createdRowNodes
                val measures = first.measurements.measuredRowCount
                val visits = first.measurements.eagerVisitedHistoryRows
                compose.runOnIdle { first.stats = Counters(); first.completedTicks = 0; started = now(); first.streamRequest = 1 }
                settle(first, 24)
                sample(first, size, round, "streamIme", started, nodes, measures, visits, now() - started)
                compose.runOnIdle { mounted = null }
                compose.waitForIdle()
                assertEquals(0, first.measurements.retainedRows)
                assertEquals(0, first.measurements.retainedEagerHistoryRows)
                // Output while detached is real model mutation, not a reused old render snapshot.
                repeat(8) { terminal.feed("detached-$it 中文\r\n") }
                lateinit var reentry: Mount
                compose.runOnIdle { reentry = Mount(terminal, tui); reentry.streamRequest = 2; started = now(); mounted = reentry }
                settle(reentry, 24)
                sample(reentry, size, round, "reentry", started, 0, 0, 0, now() - started)
                compose.runOnIdle { mounted = null }
                compose.waitForIdle()
                assertEquals(0, reentry.measurements.retainedRows)
                assertEquals(0, reentry.measurements.retainedEagerHistoryRows)
            }
        }
    }

    @Test fun longHistoryOneThousand() = diagnose(1000)
    @Test fun longHistoryFiveThousand() = diagnose(5000)
    @Test fun longHistoryTenThousand() = diagnose(10000)
}
