package me.rerere.rikkahub.pipeline

import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.container.PtyMode
import me.rerere.rikkahub.data.container.TerminalPipelineTrace
import me.rerere.rikkahub.data.container.TerminalPipelineStage
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.pages.container.ProcessSessionPage
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Real page/native PTY and real system keyboard; synthetic finite peer, never an account-backed Pi session. */
class TerminalTuiHistoryPageTest : KoinComponent {
    @get:Rule val compose = createAndroidComposeRule<TerminalPipelineTestActivity>()
    @get:Rule val timeout = Timeout.seconds(240)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()
    private val settings: SettingsStore by inject()
    private fun now() = SystemClock.elapsedRealtimeNanos()
    private fun tap(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Touch injection rejected", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }
    private fun back() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0)
            assertTrue("Back injection rejected", automation.injectInputEvent(event, true))
        }
    }
    private fun emit(marker: String, value: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\n$marker $value\n")
        })
    }

    @Test fun tuiHistoryPageChunkedKeyboardAndReentryDuringNativeOutput() = exercise(TerminalRenderMode.TUI_CHUNKED)
    @Test fun tuiHistoryPageLayersKeyboardAndReentryDuringNativeOutput() = exercise(TerminalRenderMode.TUI_CHUNKED_LAYERS)

    private fun exercise(mode: TerminalRenderMode) {
        val saved = runBlocking { settings.settingsFlow.first { !it.init } }
        val sandbox = "tui-history-${UUID.randomUUID()}"
        // The session and UI remain RAW. This synthetic shell peer explicitly enables canonical
        // reads/CR conversion so its command protocol needs no account, Python or external tools.
        val script = """
            stty icanon icrnl -echo -onlcr
            printf 'READY\r\n'
            worker=
            trap 'if [ -n "${'$'}worker" ]; then kill "${'$'}worker"; fi' EXIT HUP TERM
            while IFS= read -r command; do
                case "${'$'}command" in
                    SEED)
                        i=0
                        while [ "${'$'}i" -lt 10100 ]; do
                            printf 'history-%06d 中文 é 👩‍💻\r\n' "${'$'}i"
                            i=${'$'}((i+1))
                        done
                        printf 'SEEDED\r\n'
                        ;;
                    RUN:*)
                        tag=${'$'}{command#RUN:}
                        (
                            i=0
                            while [ "${'$'}i" -lt 160 ]; do
                                printf 'LIVE:%s:%03d 中文 é 👩‍💻\r\nbody:%s:%03d\r\n' "${'$'}tag" "${'$'}i" "${'$'}tag" "${'$'}i"
                                i=${'$'}((i+1))
                                sleep 0.1
                            done
                            printf 'DONE:%s\r\n' "${'$'}tag"
                        ) &
                        worker=${'$'}!
                        ;;
                    ACK:*) printf '%s\r\n' "${'$'}command" ;;
                esac
            done
        """.trimIndent()
        val command = "sh -c '" + script.replace("'", "'\"'\"'") + "'"
        var mounted by mutableStateOf(false)
        val disposals = AtomicInteger()
        var processId: String? = null
        var recording: TerminalPipelineTrace.Capture? = null
        try {
            runBlocking {
                val key = MessageDigest.getInstance("SHA-256").digest(command.toByteArray()).joinToString("") { "%02x".format(it) }
                settings.update { it.copy(terminalCommandPreferences = JSONArray().put(JSONObject()
                    .put("key", key).put("command", command).put("updatedAt", 1L)
                    .put("rawInputMode", true).put("terminalPanMode", true).put("showFullInputBar", true)
                    .put("showStatusBar", true).put("showExtraKeys", true).put("autoScroll", true)
                    .put("maxScrollbackLines", 10000).put("forcedTerminalColumns", 80).put("terminalFontSizeSp", 12)).toString(),
                    terminalFullGridCommands = "", terminalCustomTuiCommands = "sh") }
                settings.setTerminalRenderer(command, mode)
                proot.start().getOrThrow()
            }
            val start = runBlocking { manager.startInteractiveSession(sandbox, command, columns = 80, rows = 24, ptyMode = PtyMode.RAW) }
            assertTrue(start.message, start.success)
            assertEquals("native-pty", start.terminalBackend)
            val id = start.processId
            processId = id
            val trace = TerminalPipelineTrace.start(id, 4096)
            recording = trace
            val nav = Navigator(mutableListOf())
            compose.setContent {
                val values by settings.settingsFlow.collectAsState()
                RikkahubTheme {
                    CompositionLocalProvider(LocalSettings provides values, LocalNavController provides nav) {
                        if (mounted) {
                            DisposableEffect(id) { onDispose { disposals.incrementAndGet() } }
                            ProcessSessionPage(sandbox, id)
                        }
                    }
                }
            }
            fun received(marker: String) = manager.readInteractiveBuffer(id)?.contains(marker) == true
            fun send(value: String) = runBlocking { manager.sendInput(id, value).getOrThrow() }
            compose.waitUntil(15_000) { received("READY\r\n") }
            val terminal = requireNotNull(manager.getInteractiveTerminalEmulator(id))
            compose.runOnUiThread { mounted = true }
            Espresso.closeSoftKeyboard()
            // The page owns the history preference. A detached fresh session still has its
            // default limit; do not seed10k until the real page has applied the requested limit.
            compose.waitUntil(30_000) {
                terminal.maxScrollbackLines() == 10000 && terminal.columns == 80 &&
                    trace.viewport()?.viewport?.let { it.initialized && !it.ime && it.sameFrame } == true &&
                    !manager.hasPendingTerminalResize(id)
            }
            send("SEED")
            compose.waitUntil(30_000) { received("SEEDED\r\n") }
            assertEquals(10000, terminal.renderFrame().historyCount)
            fun ready(ime: Boolean): Boolean {
                val p = trace.viewport()?.viewport ?: return false
                return p.preferred == mode && p.applied == mode && p.tui && p.hasChunks && p.historyRows == 10000 &&
                    !p.virtual && !p.bindingVirtual && !p.alternate && p.pan && !p.selection && p.ime == ime &&
                    p.sameFrame && p.geometryReady && p.initialized && p.following && p.writers <= 1
            }
            compose.waitUntil(30_000) { ready(false) && !manager.hasPendingTerminalResize(id) }
            val physicalRows = terminal.rows
            val sentinel = terminal.renderFrame().historyLineIds[5000]
            val rectangles = JSONArray()
            fun rectangle(phase: String, ime: Boolean) {
                var lastDraw = trace.viewport()?.draw ?: 0L
                var lastRect: List<Any>? = null
                var confirmations = 0
                var accepted: JSONObject? = null
                compose.waitUntil(30_000) {
                    val sample = trace.viewport() ?: return@waitUntil false
                    val p = sample.viewport
                    if (sample.draw <= lastDraw || !ready(ime) || p.writers != 0 || p.effectId != null ||
                        p.gestureId != null || p.scrolling || !p.tailSatisfied || manager.hasPendingTerminalResize(id)) {
                        compose.runOnUiThread { trace.requestViewportDraw() }
                        return@waitUntil false
                    }
                    lastDraw = sample.draw
                    val frame = terminal.renderFrame()
                    if (p.revision != frame.revision) return@waitUntil false
                    assertTrue(frame.cursorVisible)
                    val expected = frame.rows[frame.historyCount + frame.cursorRow].text
                    assertTrue("Expected completed blank input cursor", expected.text.isBlank())
                    val row = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(expected)), true)
                        .fetchSemanticsNodes().single()
                    val view = compose.onNodeWithTag("terminal-trace-output").fetchSemanticsNode()
                    val top = row.positionInRoot.y - view.positionInRoot.y
                    val bottom = top + row.size.height
                    assertTrue("Actual cursor clipped in $phase", top >= -1 && bottom <= view.size.height + 1)
                    val signature = listOf(frame.revision, top, bottom, row.size.height, view.size.height)
                    confirmations = if (lastRect == signature) confirmations + 1 else 1
                    lastRect = signature
                    accepted = JSONObject().put("mode", mode.id).put("phase", phase).put("ime", ime)
                        .put("top", top.toDouble()).put("bottom", bottom.toDouble()).put("rowHeight", row.size.height)
                        .put("viewport", view.size.height).put("historyRows", frame.historyCount)
                        .put("rows", terminal.rows).put("draw", sample.draw).put("revision", frame.revision)
                        .put("confirmations", confirmations)
                    if (confirmations < 2) compose.runOnUiThread { trace.requestViewportDraw() }
                    confirmations >= 2
                }
                rectangles.put(requireNotNull(accepted))
                emit("TUI_HISTORY_PAGE_RECT", requireNotNull(accepted))
            }
            rectangle("initial", false)
            val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            val inputBounds = input.boundsInWindow
            val overlaps = JSONArray()
            val outputWindows = JSONArray()
            for ((phase, ime) in listOf("imeOpen" to true, "imeClosed" to false, "reentry" to false)) {
                trace.beginWindow()
                var windows = 0
                var readBytes = 0L
                var fedBytes = 0L
                var published = 0
                var drawn = 0
                fun drainTrace() = synchronized(trace) {
                    assertEquals("Trace overflow invalidates phase provenance", 0L, trace.dropped)
                    val events = trace.snapshot()
                    readBytes += events.filter { it.stage == TerminalPipelineStage.OUTPUT_READ }.sumOf { it.bytes.toLong() }
                    fedBytes += events.filter { it.stage == TerminalPipelineStage.EMULATOR_FED }.sumOf { it.bytes.toLong() }
                    published += events.count { it.stage == TerminalPipelineStage.FRAME_PUBLISHED }
                    drawn += events.count { it.stage == TerminalPipelineStage.FRAME_DRAWN }
                    trace.beginWindow()
                    windows++
                }
                val ticks = Regex("LIVE:$phase:(\\d{3})")
                fun tick(): Int {
                    drainTrace()
                    return ticks.findAll(manager.readInteractiveBuffer(id).orEmpty()).lastOrNull()?.groupValues?.get(1)?.toInt() ?: -1
                }
                send("RUN:$phase")
                compose.waitUntil(10_000) { tick() >= 1 }
                val beforeTick = tick()
                val beforeDraw = trace.viewport()!!.draw
                val beforeRevision = trace.viewport()!!.viewport.revision
                val began = now()
                var detachedTicks = 0
                when (phase) {
                    "imeOpen" -> tap(inputBounds.center.x, inputBounds.center.y)
                    "imeClosed" -> back()
                    "reentry" -> {
                        val oldDisposals = disposals.get()
                        compose.runOnUiThread { mounted = false }
                        compose.waitUntil(10_000) { disposals.get() > oldDisposals }
                        val firstDetached = tick()
                        compose.waitUntil(10_000) { tick() >= firstDetached + 3 }
                        detachedTicks = tick() - firstDetached
                        compose.runOnUiThread { mounted = true }
                    }
                }
                // No global-idle or semantics traversal while the producer is active. Require
                // a NEW matching draw and MORE native output before the finite DONE marker.
                compose.waitUntil(30_000) {
                    drainTrace()
                    ready(ime) && trace.viewport()!!.draw > beforeDraw &&
                        trace.viewport()!!.viewport.revision > beforeRevision && tick() > beforeTick
                }
                val afterTick = tick()
                assertFalse("Interaction happened after output ended", received("DONE:$phase\r\n"))
                assertTrue(afterTick in (beforeTick+1)..159)
                val overlap = JSONObject().put("mode", mode.id).put("phase", phase).put("ime", ime)
                    .put("beforeTick", beforeTick).put("afterTick", afterTick).put("detachedTicks", detachedTicks)
                    .put("elapsedNs", now() - began).put("beforeDraw", beforeDraw).put("afterDraw", trace.viewport()!!.draw)
                    .put("beforeRevision", beforeRevision).put("afterRevision", trace.viewport()!!.viewport.revision)
                    .put("producerStillRunning", true).put("historyRows", terminal.renderFrame().historyCount)
                overlaps.put(overlap)
                emit("TUI_HISTORY_PAGE_OVERLAP", overlap)
                compose.waitUntil(45_000) { drainTrace(); received("DONE:$phase\r\n") }
                rectangle(phase, ime)
                drainTrace()
                assertEquals("IME/reentry resized the PTY grid", physicalRows, terminal.rows)
                assertTrue("Surviving archived history vanished", sentinel in terminal.renderFrame().historyLineIds)
                val expectedBytes = (0 until 160).sumOf { tick ->
                    "LIVE:$phase:${tick.toString().padStart(3, '0')} 中文 e\u0301 👩‍💻\r\nbody:$phase:${tick.toString().padStart(3, '0')}\r\n".toByteArray().size
                } + "DONE:$phase\r\n".toByteArray().size
                assertEquals("Finite peer output bytes differ", expectedBytes.toLong(), readBytes)
                assertEquals("Parser did not receive all output", readBytes, fedBytes)
                assertTrue(published > 0 && drawn > 0)
                outputWindows.put(JSONObject().put("phase", phase).put("windows", windows).put("readBytes", readBytes)
                    .put("fedBytes", fedBytes).put("expectedBytes", expectedBytes).put("published", published).put("drawn", drawn))
            }
            // Verify the actual RAW UI bridge still submits a complete line through native PTY.
            val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            val setText = requireNotNull(field.config[SemanticsActions.SetText].action)
            val submit = requireNotNull(field.config[SemanticsActions.OnImeAction].action)
            compose.runOnUiThread { assertTrue(setText(AnnotatedString("ACK:bridge"))); assertTrue(submit()) }
            compose.waitUntil(15_000) { received("ACK:bridge\r\n") }
            rectangle("input", false)
            emit("TUI_HISTORY_PAGE", JSONObject().put("suite", "terminal-tui-history-page-v1").put("mode", mode.id)
                .put("nativePty", true).put("rawInput", true).put("ptyMode", "raw").put("peerInputDiscipline", "canonical-test-peer")
                .put("statusAndKeys", true).put("historyRows", 10000).put("physicalRows", physicalRows)
                .put("rectangles", rectangles).put("overlaps", overlaps).put("outputWindows", outputWindows).put("inputEcho", "ACK:bridge")
                .put("survivingHistoryRetained", true).put("traceDropped", 0)
                .put("disposalsBeforeCleanup", disposals.get()))
        } catch (error: Throwable) {
            val p = recording?.viewport()
            emit("TUI_HISTORY_PAGE_FAILURE", JSONObject().put("mode", mode.id).put("message", error.toString().take(500))
                .put("draw", p?.draw).put("viewport", p?.viewport?.toString()).put("disposed", disposals.get()))
            throw error
        } finally {
            try {
                compose.runOnUiThread { mounted = false }
                compose.waitForIdle()
            } finally {
                recording?.close()
                try {
                    processId?.let { runBlocking { manager.closeInteractiveSession(it) }; manager.removeProcessRecord(it) }
                } finally {
                    runBlocking { settings.update { it.copy(terminalCommandPreferences = saved.terminalCommandPreferences,
                        terminalRenderPreferences = saved.terminalRenderPreferences,
                        terminalCustomTuiCommands = saved.terminalCustomTuiCommands, terminalFullGridCommands = saved.terminalFullGridCommands) } }
                }
            }
        }
        assertTrue("Page tree was not disposed after reentry and cleanup", disposals.get() >= 2)
        assertNull(manager.getInteractiveTerminalEmulator(requireNotNull(processId)))
        emit("TUI_HISTORY_PAGE_CLEANUP", JSONObject().put("mode", mode.id).put("disposals", disposals.get())
            .put("sessionClosed", true).put("captureClosed", true))
    }
}
