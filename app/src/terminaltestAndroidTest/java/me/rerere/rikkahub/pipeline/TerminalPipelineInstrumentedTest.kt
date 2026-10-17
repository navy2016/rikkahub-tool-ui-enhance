package me.rerere.rikkahub.pipeline

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.NativePtyBridge
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.container.PtyMode
import me.rerere.rikkahub.data.container.TerminalPipelineEvent
import me.rerere.rikkahub.data.container.TerminalPipelineStage
import me.rerere.rikkahub.data.container.TerminalPipelineTrace
import me.rerere.rikkahub.data.container.TerminalPipelineViewportSample
import me.rerere.rikkahub.data.container.TerminalPipelineSettleProbe
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.container.ProcessSessionPage
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.security.MessageDigest
import java.util.UUID

/** Real production page, session manager, packaged PRoot and native PTY. No mocked echo/feed/scroll. */
class TerminalPipelineInstrumentedTest : KoinComponent {
    @get:Rule val compose = createAndroidComposeRule<TerminalPipelineTestActivity>()
    @get:Rule val timeout = Timeout.seconds(
        if (InstrumentationRegistry.getArguments().getString("terminalSoakSeconds") != null) 2100 else 240)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()
    private val settings: SettingsStore by inject()
    private var diagnosticProcessId: String? = null
    private var diagnosticCapture: TerminalPipelineTrace.Capture? = null
    private var requireSettledSamples = true

    @Test fun pipelineDefaultPtyEchoKeyboardAndRemount() = exercise(TerminalRenderMode.DEFAULT)
    @Test fun pipelineVirtualPtyEchoKeyboardAndRemount() = exercise(TerminalRenderMode.VIRTUAL_HISTORY)
    @Test fun pipelineStableVirtualPtyEchoKeyboardAndRemount() = exercise(TerminalRenderMode.VIRTUAL_HISTORY_IME)

    @Test fun bundleSustainedPtyOutputInputAndRemount() {
        val args = InstrumentationRegistry.getArguments()
        val seconds = requireNotNull(args.getString("terminalSoakSeconds")).toInt()
        require(seconds in 60..1800)
        val mode = TerminalRenderMode.fromId(requireNotNull(args.getString("terminalSoakMode")))
        exercise(mode, seconds)
    }

    /** Wake the existing frame/layout observers; never mutate scroll, controller, geometry or mode. */
    private fun awaitSettledViewport(
        capture: TerminalPipelineTrace.Capture, mode: TerminalRenderMode, expectedVirtual: Boolean,
        expectedIme: Boolean, phase: String, launch: String,
    ) {
        val id = requireNotNull(diagnosticProcessId)
        val started = System.nanoTime()
        val afterDraw = capture.viewport()?.draw ?: 0L
        val probe = TerminalPipelineSettleProbe(afterDraw)
        var accepted: TerminalPipelineViewportSample? = null
        var acceptedRevision = -1L
        try {
            compose.waitUntil(15_000) {
                val sample = capture.viewport()
                val minRevision = requireNotNull(manager.getInteractiveTerminalEmulator(id)).revision()
                val ready = probe.observe(sample, mode, expectedVirtual, expectedIme, minRevision)
                if (ready) {
                    accepted = sample
                    acceptedRevision = minRevision
                } else compose.runOnUiThread { capture.requestViewportDraw() }
                ready
            }
        } catch (error: Throwable) {
            val last = capture.viewport()?.toDiagnosticJson() ?: JSONObject.NULL
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nSETTLE_FAILURE " + JSONObject().put("phase", phase)
                    .put("launch", launch).put("mode", mode.id).put("expectedVirtual", expectedVirtual)
                    .put("expectedIme", expectedIme).put("last", last) + "\n")
            })
            throw error
        }
        val receipt = JSONObject().put("suite", "terminal-settled-viewport-v1").put("phase", phase)
            .put("launch", launch).put("mode", mode.id).put("expectedVirtual", expectedVirtual)
            .put("expectedIme", expectedIme).put("settleMs", (System.nanoTime() - started) / 1_000_000.0)
            .put("afterDraw", afterDraw).put("firstStableDraw", probe.firstStableDraw)
            .put("confirmations", probe.confirmations).put("minRevision", acceptedRevision)
            .put("viewport", requireNotNull(accepted).toDiagnosticJson())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nSETTLE_RECEIPT $receipt\n")
        })
    }

    /** Diagnostic, NOT a passed pressure test: records the original instantaneous assertion then convergence. */
    @Test fun diagnoseStableMountHandoff() = exercise(TerminalRenderMode.VIRTUAL_HISTORY_IME,
        soakSeconds = 60, observeMountOnly = true)

    private fun TerminalPipelineViewportSample.toDiagnosticJson(): JSONObject {
        val p = viewport
        return JSONObject().put("draw", draw).put("timeNanos", timeNanos).put("revision", p.revision)
            .put("preferred", p.preferred.id).put("applied", p.applied.id).put("historyRows", p.historyRows)
            .put("hasChunks", p.hasChunks).put("pan", p.pan).put("selection", p.selection).put("tui", p.tui)
            .put("alternate", p.alternate).put("ime", p.ime).put("avoidIme", p.avoidIme)
            .put("imeFallback", p.imeFallback).put("wantsVirtual", p.wantsVirtual).put("virtual", p.virtual)
            .put("bindingVirtual", p.bindingVirtual).put("sameFrame", p.sameFrame)
            .put("initialized", p.initialized).put("following", p.following)
            .put("gestureId", p.gestureId ?: JSONObject.NULL).put("effectId", p.effectId ?: JSONObject.NULL)
            .put("writers", p.writers).put("scrolling", p.scrolling).put("geometryReady", p.geometryReady)
            .put("widthReady", p.widthReady).put("tailSatisfied", p.tailSatisfied)
    }

    private fun diagnoseMount(capture: TerminalPipelineTrace.Capture, mode: TerminalRenderMode, launch: String) {
        val before = capture.viewport()
        var oldRulePassed = true
        try {
            sampleGroup(mode, "mounted", launch, capture, expectVirtual = true)
        } catch (error: AssertionError) {
            oldRulePassed = false
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nMOUNT_OLD_RULE_FAILURE ${error.javaClass.simpleName}\n")
            })
        }
        val afterOldRule = capture.viewport()
        val began = SystemClock.elapsedRealtimeNanos()
        var converged = false
        try {
            compose.waitUntil(15_000) {
                val p = capture.viewport()?.viewport ?: return@waitUntil false
                p.preferred == mode && p.applied == mode && p.wantsVirtual && p.virtual && p.bindingVirtual &&
                    p.sameFrame && p.geometryReady && p.widthReady && p.initialized && p.following &&
                    p.gestureId == null && p.effectId == null && p.writers == 0 && !p.scrolling && p.tailSatisfied
            }
            converged = true
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            // Timeout is an explicit diagnostic outcome, never disguised as pressure acceptance.
        }
        val value = JSONObject().put("suite", "terminal-stable-mount-diagnostic-v1").put("launch", launch)
            .put("oldRulePassed", oldRulePassed).put("converged", converged)
            .put("convergenceWaitMs", (SystemClock.elapsedRealtimeNanos() - began) / 1_000_000.0)
            .put("before", before?.toDiagnosticJson() ?: JSONObject.NULL)
            .put("afterOldRule", afterOldRule?.toDiagnosticJson() ?: JSONObject.NULL)
            .put("last", capture.viewport()?.toDiagnosticJson() ?: JSONObject.NULL)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nMOUNT_DIAGNOSTIC $value\n")
        })
        capture.viewportTransitions().takeLast(16).forEachIndexed { index, sample ->
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nMOUNT_TRANSITION " + sample.toDiagnosticJson()
                    .put("index", index).put("launch", launch) + "\n")
            })
        }
    }

    private fun soakCommand(): String {
        val script = """
            stty -echo -onlcr
            printf '\033[?25lREADY\r\n'
            i=0
            while IFS= read -r line; do
              case "${'$'}line" in
                SEED)
                  n=0; while [ "${'$'}n" -lt 10150 ]; do
                    printf 'history-%05d 中文\r\n' "${'$'}n"; n=${'$'}((n+1))
                  done
                  printf 'SEED_DONE\r\n';;
                BURST:*)
                  n=0; while [ "${'$'}n" -lt 32 ]; do
                    printf 'load-%08d \033[32m中文\033[0m\r\n' "${'$'}i"
                    i=${'$'}((i+1)); n=${'$'}((n+1))
                  done
                  printf 'ECHO:%s\r\n' "${'$'}line";;
                *) printf 'ECHO:%s\r\n' "${'$'}line";;
              esac
            done
        """.trimIndent()
        return "sh -c '" + script.replace("'", "'\"'\"'") + "'"
    }

    /** Monotonic real time, not Compose's accelerated clock. Each cycle waits for actual visible child output. */
    private fun sustained(mode: TerminalRenderMode, launch: String, processId: String,
        capture: TerminalPipelineTrace.Capture, seconds: Int, mount: (Boolean) -> Unit) {
        awaitSettledViewport(capture, mode, mode.isVirtualHistory, false, "pressureStart", launch)
        val start = SystemClock.elapsedRealtimeNanos()
        val deadline = start + seconds * 1_000_000_000L
        var cycle = 0
        var remounts = 0
        var bucket = 0
        var lastReport = start
        var maxCycleNs = 0L
        var cycleSumNs = 0L
        var bucketCycles = 0
        var readBytes = 0L
        var fedBytes = 0L
        var published = 0L
        var drawn = 0L
        val gcStart = Debug.getRuntimeStat("art.gc.gc-count").toLongOrNull()
        fun report(final: Boolean) {
            val now = SystemClock.elapsedRealtimeNanos()
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            val runtime = Runtime.getRuntime()
            val sample = JSONObject().put("suite", "terminal-bundle-soak-v1").put("launch", launch)
                .put("mode", mode.id).put("bucket", bucket++).put("final", final)
                .put("requestedSeconds", seconds).put("elapsedMs", (now - start) / 1_000_000.0)
                .put("cycles", cycle).put("bucketCycles", bucketCycles).put("remounts", remounts)
                .put("cycleMeanMs", if (bucketCycles == 0) 0.0 else cycleSumNs / bucketCycles / 1_000_000.0)
                .put("cycleMaxMs", maxCycleNs / 1_000_000.0).put("readBytes", readBytes).put("fedBytes", fedBytes)
                .put("published", published).put("drawn", drawn).put("traceDropped", 0)
                .put("historyRows", requireNotNull(manager.getInteractiveTerminalEmulator(processId))
                    .renderFrame().historyCount)
                .put("pssKb", memory.totalPss).put("javaUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
                .put("gcCount", Debug.getRuntimeStat("art.gc.gc-count").toLongOrNull() ?: JSONObject.NULL)
                .put("gcStart", gcStart ?: JSONObject.NULL)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nSOAK_SAMPLE $sample\n")
            })
            lastReport = now
            maxCycleNs = 0
            cycleSumNs = 0
            bucketCycles = 0
        }
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            compose.waitForIdle()
            capture.beginWindow()
            val began = SystemClock.elapsedRealtimeNanos()
            val marker = "BURST:$cycle"
            compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) {
                check(it(AnnotatedString(marker)))
            }
            compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.OnImeAction) { check(it()) }
            waitVisible("ECHO:$marker")
            compose.waitForIdle()
            val events = capture.snapshot()
            assertEquals(0L, capture.dropped)
            val reads = events.filter { it.stage == TerminalPipelineStage.OUTPUT_READ }.sumOf { it.bytes.toLong() }
            val feeds = events.filter { it.stage == TerminalPipelineStage.EMULATOR_FED }.sumOf { it.bytes.toLong() }
            assertEquals(reads, feeds)
            val expectedBytes = buildString {
                repeat(32) { offset ->
                    append("load-%08d \u001b[32m中文\u001b[0m\r\n".format(java.util.Locale.ROOT, cycle * 32 + offset))
                }
                append("ECHO:$marker\r\n")
            }.toByteArray().size.toLong()
            assertEquals("A marker alone cannot prove a complete 32-line burst", expectedBytes, feeds)
            assertEquals(1, events.count { it.stage == TerminalPipelineStage.UI_INPUT })
            val revision = events.last { it.stage == TerminalPipelineStage.EMULATOR_FED }.frameRevision
            assertTrue(events.any { it.stage == TerminalPipelineStage.FRAME_DRAWN && it.frameRevision >= revision })
            val finalDraw = events.last {
                it.stage == TerminalPipelineStage.FRAME_DRAWN && it.frameRevision >= revision
            }
            assertEquals("Pressure must run the requested backend", mode.isVirtualHistory, finalDraw.virtual)
            val notification = requireNotNull(manager.observeTerminalUpdates(processId)).value
            assertTrue(notification.revision >= revision)
            readBytes += reads
            fedBytes += feeds
            published += events.count { it.stage == TerminalPipelineStage.FRAME_PUBLISHED }
            drawn += events.count { it.stage == TerminalPipelineStage.FRAME_DRAWN }
            val elapsed = SystemClock.elapsedRealtimeNanos() - began
            maxCycleNs = maxOf(maxCycleNs, elapsed)
            cycleSumNs += elapsed
            bucketCycles++
            cycle++
            if (SystemClock.elapsedRealtimeNanos() - lastReport >= 60_000_000_000L) {
                report(false)
                if (bucket % 5 == 0) {
                    compose.runOnIdle { mount(false) }
                    compose.waitForIdle()
                    compose.runOnIdle { mount(true) }
                    waitVisible("ECHO:$marker")
                    awaitSettledViewport(capture, mode, mode.isVirtualHistory, false, "pressureRemount", launch)
                    remounts++
                }
            }
        }
        assertTrue(cycle > 0 && readBytes == fedBytes)
        report(true)
    }

    private fun exercise(mode: TerminalRenderMode, soakSeconds: Int = 0, observeMountOnly: Boolean = false) {
        requireSettledSamples = !observeMountOnly
        check(compose.activity.packageName == "me.rerere.rikkahub.dev.next.terminaltest")
        if (Build.VERSION.SDK_INT >= 33) assertEquals("Runner must grant only the test app's notifications before launch",
            PackageManager.PERMISSION_GRANTED, compose.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val launch = UUID.randomUUID().toString()
        val sandbox = "pipeline-$launch"
        // Cooked input keeps exact line semantics; echo is from the child only, no line-discipline echo.
        val command = if (soakSeconds > 0) soakCommand() else TerminalPipelineWorkload.command
        val saved = runBlocking { settings.settingsFlow.first { !it.init } }
        val key = MessageDigest.getInstance("SHA-256").digest(command.toByteArray())
            .joinToString("") { "%02x".format(it) }
        var mounted by mutableStateOf(true)
        var processId: String? = null
        var capture: TerminalPipelineTrace.Capture? = null
        try {
            runBlocking {
                settings.update { it.copy(terminalCommandPreferences = JSONArray().put(JSONObject()
                    .put("key", key).put("command", command).put("updatedAt", 1L)
                    .put("rawInputMode", false).put("terminalPanMode", true).put("showFullInputBar", true)
                    .put("showStatusBar", false).put("showExtraKeys", false).put("autoScroll", true)
                    .put("forcedTerminalColumns", 80).put("maxScrollbackLines", if (soakSeconds > 0) 10000 else 1000))
                    .toString()) }
                settings.setTerminalRenderer(command, mode)
                proot.start().getOrThrow()
            }
            assertTrue("Native PTY unavailable: ${NativePtyBridge.unavailableReason}", NativePtyBridge.isAvailable)
            val started = runBlocking { manager.startInteractiveSession(sandbox, command,
                preferTty = true, columns = 80, rows = 24, ptyMode = PtyMode.COOKED) }
            assertTrue("Session failed: ${started.message}", started.success)
            assertEquals("native-pty", started.terminalBackend)
            val id = started.processId
            processId = id
            diagnosticProcessId = id
            val trace = TerminalPipelineTrace.start(id, 4096)
            capture = trace
            diagnosticCapture = trace
            val nav = Navigator(mutableListOf())
            compose.setContent {
                val values by settings.settingsFlow.collectAsState()
                RikkahubTheme {
                    CompositionLocalProvider(LocalSettings provides values, LocalNavController provides nav) {
                        if (mounted) ProcessSessionPage(sandboxId = sandbox, initialProcessId = id)
                    }
                }
            }
            compose.waitUntil(30_000) { trace.snapshot().any { it.stage == TerminalPipelineStage.FRAME_DRAWN } }
            compose.waitUntil(10_000) { compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED }
            Log.i("TerminalPipelineTest", "PIPELINE_READY mode=${mode.id} lifecycle=${compose.activity.lifecycle.currentState}")
            waitVisible("READY")
            compose.waitForIdle()
            Espresso.closeSoftKeyboard()
            // Let normal first-layout PTY resize complete before starting deterministic child output.
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            runBlocking { manager.sendInput(id, "SEED").getOrThrow() }
            waitVisible("SEED_DONE")
            compose.waitForIdle()
            val fullHeight = outputHeight()
            val physicalRows = requireNotNull(manager.getInteractiveTerminalEmulator(id)).rows
            if (observeMountOnly) {
                diagnoseMount(trace, mode, launch)
                return
            }
            sampleGroup(mode, "mounted", launch, trace, expectVirtual = mode.isVirtualHistory)

            compose.onNode(hasSetTextAction()).performClick()
            compose.waitUntil(15_000) { outputHeight() < fullHeight }
            compose.waitForIdle()
            sampleGroup(mode, "imeVisible", launch, trace,
                expectVirtual = mode == TerminalRenderMode.VIRTUAL_HISTORY_IME)
            assertEquals(physicalRows, requireNotNull(manager.getInteractiveTerminalEmulator(id)).rows)

            Espresso.closeSoftKeyboard()
            compose.waitUntil(15_000) { outputHeight() == fullHeight }
            compose.waitForIdle()
            sampleGroup(mode, "imeHidden", launch, trace,
                expectVirtual = mode == TerminalRenderMode.VIRTUAL_HISTORY_IME)
            assertEquals(physicalRows, requireNotNull(manager.getInteractiveTerminalEmulator(id)).rows)

            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            compose.runOnIdle { mounted = true }
            compose.waitForIdle()
            waitVisible("ECHO:", exact = false)
            sampleGroup(mode, "remount", launch, trace, expectVirtual = mode.isVirtualHistory)
            assertEquals("trace ring overflow invalidates sample provenance", 0L, trace.dropped)
            if (soakSeconds > 0) sustained(mode, launch, id, trace, soakSeconds) { visible -> mounted = visible }
        } finally {
            try {
                compose.runOnUiThread { mounted = false }
                compose.waitForIdle()
            } finally {
                capture?.close()
                diagnosticCapture = null
                diagnosticProcessId = null
                try {
                    processId?.let { id -> runBlocking { manager.closeInteractiveSession(id) }; manager.removeProcessRecord(id) }
                } finally {
                    runBlocking { settings.update { it.copy(terminalCommandPreferences = saved.terminalCommandPreferences,
                        terminalRenderPreferences = saved.terminalRenderPreferences) } }
                }
            }
        }
    }

    private fun sampleGroup(mode: TerminalRenderMode, phase: String, launch: String,
        capture: TerminalPipelineTrace.Capture, expectVirtual: Boolean) {
        if (requireSettledSamples) {
            awaitSettledViewport(capture, mode, expectVirtual, phase == "imeVisible", phase, launch)
        }
        repeat(3) { index ->
            val marker = "$phase-$index"
            val expectedBytes = "ECHO:$marker\r\n".toByteArray().size
            compose.waitForIdle()
            val before = capture.beginWindow()
            // Invoke existing field actions without the test helper's automatic RequestFocus;
            // only the explicit keyboard phase may show the IME. Submission still runs the app.
            compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) {
                check(it(AnnotatedString(marker)))
            }
            compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.OnImeAction) { check(it()) }
            waitVisible("ECHO:$marker")
            compose.waitForIdle()
            val visibleCheckedAt = System.nanoTime()
            val rows = capture.snapshot().filter { it.sequence > before }
            val ui = rows.single { it.stage == TerminalPipelineStage.UI_INPUT }
            val output = rows.filter { it.stage == TerminalPipelineStage.EMULATOR_FED }
            assertEquals("Unexpected/partial output cannot be treated as a deterministic echo", expectedBytes, output.sumOf { it.bytes })
            val fed = output.last()
            assertTrue(fed.frameRevision >= 0)
            val published = rows.first { it.stage == TerminalPipelineStage.FRAME_PUBLISHED && it.frameRevision >= fed.frameRevision }
            val drawn = rows.first { it.stage == TerminalPipelineStage.FRAME_DRAWN && it.frameRevision >= published.frameRevision }
            val finalDraw = rows.last { it.stage == TerminalPipelineStage.FRAME_DRAWN && it.frameRevision >= published.frameRevision }
            val panelCompositions = rows.count { it.stage == TerminalPipelineStage.PANEL_COMPOSED }
            assertTrue("Full-page composition probe missing", panelCompositions > 0)
            assertEquals("Incorrect renderer for keyboard phase", expectVirtual, finalDraw.virtual)
            assertEquals("Incomplete trace sample", 0L, capture.dropped)
            assertTrue("Echo must be actually drawn after submission", drawn.timeNanos >= ui.timeNanos)
            val report = JSONObject().put("launch", launch).put("mode", mode.id).put("phase", phase).put("iteration", index)
                .put("inputToDrawMs", (drawn.timeNanos - ui.timeNanos) / 1_000_000.0)
                .put("inputToVisibleCheckMs", (visibleCheckedAt - ui.timeNanos) / 1_000_000.0)
                .put("panelCompositions", panelCompositions)
                .put("frameRevision", drawn.frameRevision).put("outputBytes", expectedBytes).put("virtual", finalDraw.virtual)
            for (stage in listOf(TerminalPipelineStage.INPUT_ENQUEUE_STARTED, TerminalPipelineStage.INPUT_WRITE_STARTED,
                TerminalPipelineStage.OUTPUT_READ, TerminalPipelineStage.EMULATOR_FED,
                TerminalPipelineStage.UI_OUTPUT_RECEIVED, TerminalPipelineStage.FRAME_PUBLISHED, TerminalPipelineStage.FRAME_DRAWN)) {
                val event: TerminalPipelineEvent = when (stage) {
                    TerminalPipelineStage.EMULATOR_FED -> fed
                    TerminalPipelineStage.FRAME_PUBLISHED -> published
                    TerminalPipelineStage.FRAME_DRAWN -> drawn
                    else -> rows.first { it.stage == stage }
                }
                report.put(stage.name, (event.timeNanos - ui.timeNanos) / 1_000_000.0)
            }
            // Keep v1 timestamps intact. These new spans measure only the deterministic echo's
            // actual read/parse and the selected publication, never arbitrary input/output causality.
            val read = rows.first { it.stage == TerminalPipelineStage.NATIVE_READ_COMPLETED }
            val locked = rows.first { it.stage == TerminalPipelineStage.PARSE_LOCK_ACQUIRED }
            val captured = rows.first { it.stage == TerminalPipelineStage.FRAME_CAPTURED && it.frameRevision == published.frameRevision }
            val captureStart = rows.last { it.stage == TerminalPipelineStage.FRAME_CAPTURE_STARTED && it.sequence < captured.sequence }
            val syncStart = rows.first { it.stage == TerminalPipelineStage.ROW_SYNC_STARTED && it.frameRevision == published.frameRevision }
            val syncEnd = rows.first { it.stage == TerminalPipelineStage.ROW_SYNC_FINISHED && it.frameRevision == published.frameRevision }
            val phase = JSONObject().put("readToParseLockMs", (locked.timeNanos - read.timeNanos) / 1_000_000.0)
                .put("parseThroughLastChunkMs", (fed.timeNanos - locked.timeNanos) / 1_000_000.0)
                .put("captureMs", (captured.timeNanos - captureStart.timeNanos) / 1_000_000.0)
                .put("rowSyncMs", (syncEnd.timeNanos - syncStart.timeNanos) / 1_000_000.0)
            report.put("phaseTimingContract", "terminal-echo-phases-v1").put("phaseTimings", phase)
            Log.i("TerminalPipelineTest", "PIPELINE_SAMPLE $report")
        }
    }

    private fun outputHeight(): Int = compose.onNodeWithTag("terminal-trace-output", useUnmergedTree = true)
        .fetchSemanticsNode().size.height

    private fun waitVisible(marker: String, exact: Boolean = true) {
        try {
            compose.waitUntil(20_000) {
                val viewport = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag,
                    "terminal-trace-output"), useUnmergedTree = true).fetchSemanticsNodes().singleOrNull() ?: return@waitUntil false
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                    .fetchSemanticsNodes().any { node ->
                        val top = node.positionInRoot.y - viewport.positionInRoot.y
                        node.config[SemanticsProperties.Text].any {
                            if (exact) it.text.trimEnd() == marker else it.text.contains(marker)
                        } && top >= -1f && top + node.size.height <= viewport.size.height + 1f
                    }
            }
        } catch (error: Throwable) {
            // The child is synthetic, but still emit only booleans/counts/coordinates, never text.
            val id = diagnosticProcessId
            val frame = id?.let { manager.getInteractiveTerminalEmulator(it)?.renderFrame() }
            val trace = diagnosticCapture?.snapshot().orEmpty()
            Log.e("TerminalPipelineTest", "PIPELINE_DIAGNOSTIC bufferHasMarker=" +
                (id?.let { manager.readInteractiveBuffer(it)?.contains(marker) }) +
                " emulatorMatches=${frame?.rows?.count { it.text.text.trimEnd() == marker }}" +
                " frame=${frame?.revision} rows=${frame?.rows?.size} history=${frame?.historyCount}" +
                " counts=${trace.groupingBy { it.stage }.eachCount()}" +
                " lifecycle=${compose.activity.lifecycle.currentState}")
            runCatching {
                val viewports = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag,
                    "terminal-trace-output"), useUnmergedTree = true).fetchSemanticsNodes()
                val candidates = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                    useUnmergedTree = true).fetchSemanticsNodes().filter { node ->
                    node.config[SemanticsProperties.Text].any { it.text.contains(marker) }
                }
                Log.e("TerminalPipelineTest", "PIPELINE_GEOMETRY viewports=${viewports.size}" +
                    " bounds=${viewports.map { listOf(it.positionInRoot.y, it.size.height) }}" +
                    " matches=${candidates.size} candidateBounds=${candidates.take(4).map {
                        listOf(it.positionInRoot.y, it.size.height) }}")
            }.onFailure { Log.e("TerminalPipelineTest", "PIPELINE_GEOMETRY unavailable=${it.javaClass.simpleName}") }
            throw error
        }
        val node = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().last { n -> n.config[SemanticsProperties.Text].any {
                if (exact) it.text.trimEnd() == marker else it.text.contains(marker)
            } }
        val viewport = compose.onNodeWithTag("terminal-trace-output", useUnmergedTree = true).fetchSemanticsNode()
        val top = node.positionInRoot.y - viewport.positionInRoot.y
        assertTrue("Actual echo row outside viewport: top=$top height=${node.size.height} viewport=${viewport.size.height}",
            top >= -1f && top + node.size.height <= viewport.size.height + 1f)
    }
}
