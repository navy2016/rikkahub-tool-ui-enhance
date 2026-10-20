package me.rerere.rikkahub.viewporttest

import android.os.SystemClock
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportMetrics
import me.rerere.rikkahub.data.container.effectiveTerminalRenderMode
import me.rerere.rikkahub.data.container.terminalTuiHistoryAllowed
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

    private class Mount(
        val terminal: TerminalEmulator,
        val tui: Boolean,
        val probeMode: TerminalRenderMode? = null,
        val productionModes: Boolean = false,
    ) {
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
        var historyAllowed = false
        var effectiveMode = TerminalRenderMode.DEFAULT
        var chunkCount = 0
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
                if (resize) heightDp = if (if (probeMode == null) step in 8..15 else step == 1) 140 else 240
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
                if (request != 0) repeat(if (probeMode == null) 24 else 3) { step ->
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
            val allowed = productionModes && terminalTuiHistoryAllowed(
                checkNotNull(probeMode), frame, tui, frame.isAlternateScreen, true, false)
            historyAllowed = allowed
            val chunks = remember(frame.historyCount, frame.historyStartSequence, allowed) {
                // Diagnostic-only intervention: change archival grouping, NEVER the TUI metrics,
                // row synchronization, screen preservation, input or sole scroll executor.
                terminalHistoryChunks(frame.historyCount, frame.historyStartSequence,
                    if (productionModes) tui && !allowed
                    else tui && (probeMode == null || probeMode == TerminalRenderMode.FLAT))
            }
            chunkCount = chunks.size
            effectiveMode = effectiveTerminalRenderMode(probeMode ?: TerminalRenderMode.DEFAULT,
                chunks.isNotEmpty(), tuiHistoryAllowed = allowed)
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
                    TerminalTranscriptViewport(frame, captured.rows, style, chunks, probeMode ?: TerminalRenderMode.DEFAULT,
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
                        }, rowSource = captured, tuiHistoryAllowed = allowed)
                }
            }
        }

        fun checkpoint(): JSONObject {
            val frame = source.frame
            val state = controller.state.value
            val geometry = if (hasBinding) measurements.peekEager(bound.pass) else null
            return JSONObject().put("tui", tui).put("mode", probeMode?.id ?: "original")
                .put("history", frame.historyCount).put("ticks", completedTicks).put("heightDp", heightDp)
                .put("viewport", viewportHeight).put("frameRevision", frame.revision)
                .put("passRevision", if (hasBinding) bound.pass.frame.revision else -1)
                .put("drawnRevision", stats.lastDrawnRevision).put("draws", stats.drawCount)
                .put("geometry", geometry != null).put("currentGeometry", geometry?.frame === frame)
                .put("initialized", state.initialized).put("effect", state.scrollEffect?.id ?: -1)
                .put("gesture", state.gesture?.id ?: -1).put("scrolling", eager.isScrollInProgress)
                .put("writers", if (hasBinding) bound.binding.activeWriters else -1)
                .put("scroll", eager.value).put("maxScroll", eager.maxValue)
                .put("target", geometry?.followTarget(metrics()) ?: -1)
                .put("commits", stats.commits).put("commitSpanNs", stats.commitSpanNs)
                .put("rowSyncNs", stats.syncNs).put("rowMeasures", measurements.measuredRowCount)
        }
    }

    private fun settle(mount: Mount, expectedTicks: Int) {
        var lastDraw = 0
        var stable = 0
        var lastRevision = -1L
        val started = now()
        var nextCheckpoint = started + 10_000_000_000L
        try {
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
                if (!done && now() >= nextCheckpoint) {
                    Log.i("TerminalLongHistory", "LONG_WAIT " + mount.checkpoint()
                        .put("expectedTicks", expectedTicks).put("elapsedNs", now() - started).put("stableDraws", stable))
                    nextCheckpoint = now() + 10_000_000_000L
                }
            }
            done
        }
        } catch (error: Exception) {
            compose.runOnUiThread {
                Log.e("TerminalLongHistory", "LONG_TIMEOUT " + mount.checkpoint()
                    .put("expectedTicks", expectedTicks).put("elapsedNs", now() - started).put("stableDraws", stable))
            }
            throw error
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

    /** Separate slow sampling pass, never included in the unprofiled paired timing observations. */
    private class MainThreadSampler {
        private val running = AtomicBoolean(true)
        private val main = Looper.getMainLooper().thread
        private val stacks = HashMap<String, Int>()
        private var observations = 0
        private val worker = Thread({
            while (running.get()) {
                val stack = main.stackTrace.take(48)
                val key = stack.take(12).joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                synchronized(stacks) {
                    observations++
                    if (key in stacks || stacks.size < 128) stacks[key] = (stacks[key] ?: 0) + 1
                }
                LockSupport.parkNanos(20_000_000L)
            }
        }, "terminal-main-sampler").apply { isDaemon = true; start() }

        fun finish(): JSONObject {
            running.set(false)
            LockSupport.unpark(worker)
            worker.join(5_000)
            check(!worker.isAlive) { "Main sampler did not stop" }
            return synchronized(stacks) {
                JSONObject().put("observations", observations).put("intervalNs", 20_000_000L)
                    .put("topStacks", JSONArray(stacks.entries.sortedByDescending { it.value }.take(12).map {
                        JSONObject().put("count", it.value).put("stack", it.key)
                    }))
            }
        }
    }

    private fun probe(size: Int, productionModes: Boolean = false) {
        // NOT v1:3 ticks rather than24, one warmup then AB/BA pairs. Kept in an independent report.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        val marker = if (productionModes) "LONG_PERF" else "LONG_PROBE"
        Log.i("TerminalLongHistory", "${marker}_DEVICE " + JSONObject().put("size", size)
            .put("pid", android.os.Process.myPid()).put("fingerprint", android.os.Build.FINGERPRINT)
            .put("targetApk", digest(File(instrumentation.targetContext.applicationInfo.sourceDir)))
            .put("testApk", digest(File(instrumentation.context.applicationInfo.sourceDir))))
        val modes = if (productionModes) listOf(TerminalRenderMode.DEFAULT,
            TerminalRenderMode.TUI_CHUNKED, TerminalRenderMode.TUI_CHUNKED_LAYERS)
            else listOf(TerminalRenderMode.FLAT, TerminalRenderMode.CHUNKED, TerminalRenderMode.CHUNKED_LAYERS)
        var mounted by mutableStateOf<Mount?>(null, referentialEqualityPolicy())
        compose.setContent { mounted?.let { key(it) { it.Content() } } }
        fun model() = TerminalEmulator(80, 24, size).apply {
            feed((0 until size + 23).joinToString("\r\n") { "line-$it 中文 e\u0301 👩‍💻" } + "\r\n")
        }
        fun shape(mount: Mount): String {
            val frame = mount.source.frame
            val geometry = requireNotNull(mount.measurements.peekEager(mount.bound.pass))
            assertSame(frame, geometry.frame)
            val value = (0 until size + 24).joinToString(",") { geometry.height(it).toString() } +
                "|${mount.eager.maxValue}|${mount.horizontal.maxValue}|${mount.eager.value}"
            return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        fun record(mount: Mount, round: Int, phase: String, started: Long, nodes: Long, measures: Long, visits: Long): String {
            val elapsed = now() - started
            // End timing before actual semantics traversal, geometry hashing and disposal.
            val frame = mount.source.frame
            val expected = frame.rows[frame.screenStartRow + frame.cursorRow].text
            val text = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(expected)), true)
                .fetchSemanticsNodes().single()
            val view = compose.onNodeWithTag("long-history-output").fetchSemanticsNode()
            val top = text.positionInRoot.y - view.positionInRoot.y
            val bottom = top + text.size.height
            assertTrue("Probe cursor clipped", top >= -1 && bottom <= view.size.height + 1)
            assertEquals(size + 24, mount.measurements.retainedRows)
            assertTrue(mount.bound.binding.maximumWriters <= 1)
            val signature = shape(mount)
            val s = mount.stats
            if (productionModes) {
                assertEquals(mount.probeMode!!.isTuiHistory, mount.historyAllowed)
                assertEquals(if (mount.historyAllowed) mount.probeMode else TerminalRenderMode.FLAT, mount.effectiveMode)
                assertEquals(mount.historyAllowed, mount.chunkCount > 0)
            }
            Log.i("TerminalLongHistory", "${marker}_SAMPLE " + JSONObject().put("size", size).put("tui", true)
                .put("mode", mount.probeMode!!.id).put("round", round).put("warmup", round == 0).put("phase", phase)
                .put("completedNs", elapsed).put("firstDrawNs", s.firstDrawNs - started)
                .put("stateCreateNs", if (phase == "cold") mount.createStateNs else 0)
                .put("initialFrameNs", if (phase == "cold") mount.initialFrameNs else 0)
                .put("commitSpanNs", s.commitSpanNs).put("commits", s.commits).put("rootMeasureNs", s.measureNs)
                .put("rootPlaceNs", s.placeNs).put("rootDrawNs", s.drawNs).put("rootDraws", s.drawCount)
                .put("feedCaptureNs", s.feedCaptureNs).put("rowSyncNs", s.syncNs)
                .put("syncVisited", s.syncVisited).put("syncSkipped", s.syncSkipped).put("fifoUses", s.fifoUses)
                .put("ticks", mount.completedTicks).put("publicationNs", JSONArray(s.publicationNs))
                .put("createdNodes", mount.measurements.createdRowNodes - nodes)
                .put("rowMeasures", mount.measurements.measuredRowCount - measures)
                .put("heightVisits", mount.measurements.eagerVisitedHistoryRows - visits)
                .put("retainedRows", mount.measurements.retainedRows).put("shapeSha256", signature)
                .put("cursorTop", top.toDouble()).put("cursorBottom", bottom.toDouble())
                .put("rowHeight", text.size.height).put("viewportHeight", view.size.height)
                .put("writers", mount.bound.binding.maximumWriters)
                .put("productionModes", productionModes).put("historyAllowed", mount.historyAllowed)
                .put("effectiveMode", mount.effectiveMode.id).put("chunks", mount.chunkCount))
            return signature
        }
        repeat(if (productionModes) 5 else 3) { round ->
            val shapes = HashMap<String, String>()
            for (mode in if (round % 2 == 0) modes else modes.reversed()) {
                val terminal = model()
                lateinit var mount: Mount
                var started = 0L
                compose.runOnIdle { mount = Mount(terminal, true, mode, productionModes); started = now(); mounted = mount }
                settle(mount, 0)
                val cold = record(mount, round, "cold", started, 0, 0, 0)
                shapes.putIfAbsent("cold", cold)?.let { assertEquals(it, cold) }
                val nodes = mount.measurements.createdRowNodes
                val measures = mount.measurements.measuredRowCount
                val visits = mount.measurements.eagerVisitedHistoryRows
                compose.runOnIdle { mount.stats = Counters(); mount.completedTicks = 0; started = now(); mount.streamRequest = 1 }
                settle(mount, 3)
                val streamed = record(mount, round, "streamIme3", started, nodes, measures, visits)
                shapes.putIfAbsent("streamIme3", streamed)?.let { assertEquals(it, streamed) }
                compose.runOnIdle { mounted = null }
                compose.waitForIdle()
                assertEquals(0, mount.measurements.retainedRows)
                assertEquals(0, mount.measurements.retainedEagerHistoryRows)
                Log.i("TerminalLongHistory", "${marker}_DISPOSE " + JSONObject().put("size", size)
                    .put("mode", mode.id).put("round", round).put("retainedRows", mount.measurements.retainedRows)
                    .put("retainedHeightRows", mount.measurements.retainedEagerHistoryRows))
            }
        }
        // Sample only one fixed slow run, separately labelled. No baseline matrix or phone-FPS claim.
        if (size == 5000 && !productionModes) {
            val terminal = model()
            lateinit var mount: Mount
            compose.runOnIdle { mount = Mount(terminal, true, TerminalRenderMode.FLAT); mounted = mount }
            settle(mount, 0)
            val sampler = MainThreadSampler()
            try {
                compose.runOnIdle { mount.stats = Counters(); mount.completedTicks = 0; mount.streamRequest = 1 }
                settle(mount, 3)
            } finally {
                val data = sampler.finish()
                val traces = data.getJSONArray("topStacks")
                Log.i("TerminalLongHistory", "LONG_PROBE_STACK_INFO " + JSONObject().put("size", size)
                    .put("observations", data.getInt("observations")).put("intervalNs", 20_000_000).put("parts", traces.length()))
                repeat(traces.length()) { i ->
                    Log.i("TerminalLongHistory", "LONG_PROBE_STACK " + traces.getJSONObject(i).put("index", i).put("size", size))
                }
            }
            compose.runOnIdle { mounted = null }
            compose.waitForIdle()
            assertEquals(0, mount.measurements.retainedRows)
        }
    }

    private fun selected(size: Int) {
        if (InstrumentationRegistry.getArguments().getString("longHistoryPerformance") == "true") {
            probe(size, productionModes = true)
        } else if (InstrumentationRegistry.getArguments().getString("longHistoryProbe") == "true") probe(size) else diagnose(size)
    }

    @Test fun longHistoryOneThousand() = selected(1000)
    @Test fun longHistoryFiveThousand() = selected(5000)
    @Test fun longHistoryTenThousand() = selected(10000)
}
