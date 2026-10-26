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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
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
import me.rerere.rikkahub.data.container.TerminalSizeReceipt
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
class TerminalFullVirtualPageTest : KoinComponent {
    @get:Rule val compose = createAndroidComposeRule<TerminalPipelineTestActivity>()
    @get:Rule val timeout = Timeout.seconds(240)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()
    private val settings: SettingsStore by inject()
    private var caseKind = "unstarted"
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
        value.put("kind", caseKind)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\n${marker.replace("TUI_HISTORY", "FULL_VIRTUAL")} $value\n")
        })
    }

    @Test fun fullVirtualPageOrdinaryKeyboardSelectionMouseAndReentry() = exercise(false)
    @Test fun fullVirtualPageTuiKeyboardSelectionMouseAndReentry() = exercise(true)

    private fun exercise(tui: Boolean) {
        val mode = TerminalRenderMode.FULL_VIRTUAL
        caseKind = if (tui) "tui" else "ordinary"
        val saved = runBlocking { settings.settingsFlow.first { !it.init } }
        val sandbox = "full-virtual-${UUID.randomUUID()}"
        // UI input stays RAW in both cases. Ordinary uses a cooked session and TUI a raw session;
        // this synthetic peer explicitly sets canonical read discipline in either case.
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
                    MOUSE) printf '\033[?1000h\033[?1006hMOUSE-READY\r\n' ;;
                    UNMOUSE) printf '\033[?1000l\033[?1006lMOUSE-END\r\n' ;;
                    ALT) printf '\033[?1049h\033[Halternate-top\033[10;1HALT-READY\r\n' ;;
                    MAIN) printf '\033[?1049lMAIN-READY\r\n' ;;
                    SIZE:*)
                        token=${'$'}{command#SIZE:}
                        kernel=${'$'}(env -u LINES -u COLUMNS stty size) || exit 11
                        printf 'SIZE:%s:%s;ENV:%s %s\r\n' "${'$'}token" "${'$'}kernel" "${'$'}LINES" "${'$'}COLUMNS"
                        ;;
                esac
            done
        """.trimIndent()
        val command = "sh -c '" + script.replace("'", "'\"'\"'") + "'"
        var mounted by mutableStateOf(false)
        val disposals = AtomicInteger()
        var processId: String? = null
        var recording: TerminalPipelineTrace.Capture? = null
        var phaseLabel = "setup"
        val resizeObservations = JSONArray()
        val kernelReadings = JSONArray()
        var lastGridReadiness: JSONObject? = null
        try {
            runBlocking {
                val key = MessageDigest.getInstance("SHA-256").digest(command.toByteArray()).joinToString("") { "%02x".format(it) }
                settings.update { it.copy(terminalCommandPreferences = JSONArray().put(JSONObject()
                    .put("key", key).put("command", command).put("updatedAt", 1L)
                    .put("rawInputMode", true).put("terminalPanMode", true).put("showFullInputBar", true)
                    .put("showStatusBar", true).put("showExtraKeys", true).put("autoScroll", true)
                    .put("maxScrollbackLines", 10000).put("forcedTerminalColumns", 80).put("terminalFontSizeSp", 12)).toString(),
                    terminalFullGridCommands = "", terminalCustomTuiCommands = if (tui) "sh" else "") }
                settings.setTerminalRenderer(command, mode)
                proot.start().getOrThrow()
            }
            val start = runBlocking { manager.startInteractiveSession(sandbox, command, columns = 80, rows = 24,
                ptyMode = if (tui) PtyMode.RAW else PtyMode.COOKED) }
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
            val terminal = requireNotNull(manager.getInteractiveTerminalEmulator(id))
            var previousSize: List<Any?>? = null
            fun observeSize() {
                val ack = requireNotNull(manager.interactiveSessionSize(id))
                val model = synchronized(terminal) { listOf(terminal.columns, terminal.rows) }
                val pending = manager.hasPendingTerminalResize(id)
                if (manager.interactiveSessionSize(id) != ack) return // Do not publish a mixed receipt/model observation.
                val sample = trace.viewport()
                val signature = listOf(phaseLabel, mounted, ack.request, ack.columns, ack.rows,
                    model[0], model[1], pending, sample?.viewport?.ime)
                if (previousSize != signature) {
                    check(resizeObservations.length() < 128) { "Resize observation inventory overflow" }
                    val observation = JSONObject().put("mode", mode.id).put("phase", phaseLabel).put("timeNs", now())
                        .put("request", ack.request).put("columns", ack.columns).put("rows", ack.rows)
                        .put("modelColumns", model[0]).put("modelRows", model[1]).put("pending", pending)
                        .put("mounted", mounted).put("ime", sample?.viewport?.ime ?: false)
                        .put("draw", sample?.draw ?: 0).put("revision", sample?.viewport?.revision ?: -1)
                    resizeObservations.put(observation)
                    emit("TUI_HISTORY_PAGE_SIZE", observation)
                    previousSize = signature
                }
            }
            compose.waitUntil(15_000) { received("READY\r\n") }
            phaseLabel = "mount"
            compose.runOnUiThread { mounted = true }
            Espresso.closeSoftKeyboard()
            // The page owns the history preference. A detached fresh session still has its
            // default limit; do not seed10k until the real page has applied the requested limit.
            compose.waitUntil(30_000) {
                observeSize()
                terminal.maxScrollbackLines() == 10000 && terminal.columns == 80 &&
                    trace.viewport()?.viewport?.let { it.initialized && !it.ime && it.sameFrame } == true &&
                    !manager.hasPendingTerminalResize(id)
            }
            send("SEED")
            phaseLabel = "seed"
            compose.waitUntil(30_000) { observeSize(); received("SEEDED\r\n") }
            assertEquals(10000, terminal.renderFrame().historyCount)
            fun ready(ime: Boolean, selection: Boolean = false, pan: Boolean = true, alternate: Boolean = false): Boolean {
                val p = trace.viewport()?.viewport ?: return false
                return p.preferred == mode && p.applied == mode && p.tui == (tui || alternate) &&
                    p.historyRows == (if (alternate) 0 else 10000) && p.fullVirtual &&
                    p.retainedRowStates in 6..80 && p.measuredRows in 1..128 &&
                    p.virtual && p.bindingVirtual && p.alternate == alternate && p.pan == pan && p.selection == selection && p.ime == ime &&
                    p.sameFrame && p.geometryReady && p.initialized && p.following && p.writers <= 1
            }
            val rectangles = JSONArray()
            fun rectangle(phase: String, ime: Boolean, preserved: TerminalSizeReceipt? = null,
                selection: Boolean = false, pan: Boolean = true, alternate: Boolean = false,
                checkCursor: Boolean = true): JSONObject {
                phaseLabel = phase
                var lastDraw = trace.viewport()?.draw ?: 0L
                var lastRect: List<Any>? = null
                var confirmations = 0
                var firstStableDraw = 0L
                var accepted: JSONObject? = null
                compose.waitUntil(30_000) {
                    observeSize()
                    val sample = trace.viewport() ?: return@waitUntil false
                    val p = sample.viewport
                    if (sample.draw <= lastDraw || !ready(ime, selection, pan, alternate) || p.writers != 0 || p.effectId != null ||
                        p.gestureId != null || p.scrolling || !p.tailSatisfied || manager.hasPendingTerminalResize(id)) {
                        if (sample.draw > lastDraw) { confirmations = 0; lastRect = null; lastDraw = sample.draw }
                        compose.runOnUiThread { trace.requestViewportDraw() }
                        return@waitUntil false
                    }
                    lastDraw = sample.draw
                    val frame = terminal.renderFrame()
                    if (p.revision != frame.revision) {
                        confirmations = 0
                        lastRect = null
                        compose.runOnUiThread { trace.requestViewportDraw() }
                        return@waitUntil false
                    }
                    assertTrue(frame.cursorVisible)
                    val expected = frame.rows[frame.historyCount + frame.cursorRow].text
                    val row = if (checkCursor) compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(expected)), true)
                        .fetchSemanticsNodes().single() else null
                    val view = compose.onNodeWithTag("terminal-trace-output").fetchSemanticsNode()
                    val cellHeight = terminal.cellPixelSize().second
                    assertTrue(cellHeight > 0)
                    val measuredRows = (view.size.height / cellHeight).coerceIn(6, 80)
                    val desiredRows = preserved?.rows ?: measuredRows
                    val ack = requireNotNull(manager.interactiveSessionSize(id))
                    val snapshot = requireNotNull(manager.readInteractiveOutput(id, mode = "tail", limitBytes = 1,
                        renderTerminal = true))
                    lastGridReadiness = JSONObject().put("phase", phase).put("ime", ime).put("draw", sample.draw)
                        .put("request", ack.request).put("rows", terminal.rows).put("ackRows", ack.rows)
                        .put("desiredRows", desiredRows).put("measuredRows", measuredRows).put("viewport", view.size.height)
                        .put("cellHeight", cellHeight).put("pending", manager.hasPendingTerminalResize(id))
                    // Submitted-request idleness alone misses the UI's not-yet-submitted mount
                    // debounce. Match the ACTUAL target grid and its acknowledged model first.
                    if (terminal.rows != desiredRows || ack.rows != desiredRows || terminal.columns != 80 || ack.columns != 80 ||
                        snapshot.terminalRows != desiredRows || snapshot.terminalColumns != 80 ||
                        manager.hasPendingTerminalResize(id) ||
                        manager.interactiveSessionSize(id) != ack || terminal.revision() != frame.revision) {
                        confirmations = 0
                        lastRect = null
                        compose.runOnUiThread { trace.requestViewportDraw() }
                        return@waitUntil false
                    }
                    val top = row?.let { it.positionInRoot.y - view.positionInRoot.y } ?: 0f
                    val bottom = top + (row?.size?.height ?: 0)
                    if (checkCursor) assertTrue("Actual cursor clipped in $phase", top >= -1 && bottom <= view.size.height + 1)
                    val signature = listOf(frame.revision, top, bottom, row?.size?.height ?: 0, view.size.height,
                        cellHeight, desiredRows, terminal.rows, terminal.columns, ack.request)
                    confirmations = if (lastRect == signature) confirmations + 1 else 1
                    if (confirmations == 1) firstStableDraw = sample.draw
                    lastRect = signature
                    accepted = JSONObject().put("mode", mode.id).put("phase", phase).put("ime", ime)
                        .put("top", top.toDouble()).put("bottom", bottom.toDouble()).put("rowHeight", row?.size?.height ?: 0)
                        .put("viewport", view.size.height).put("historyRows", frame.historyCount)
                        .put("rows", terminal.rows).put("draw", sample.draw).put("revision", frame.revision)
                        .put("columns", terminal.columns).put("request", ack.request).put("timeNs", now())
                        .put("desiredRows", desiredRows).put("measuredRows", measuredRows).put("cellHeight", cellHeight)
                        .put("gridSource", if (preserved == null) "measured" else "preserved")
                        .put("pendingResize", false).put("ackRows", ack.rows).put("ackColumns", ack.columns)
                        .put("snapshotRows", snapshot.terminalRows).put("snapshotColumns", snapshot.terminalColumns)
                        .put("firstStableDraw", firstStableDraw)
                        .put("virtual", p.virtual).put("fullVirtual", p.fullVirtual).put("selection", p.selection)
                        .put("pan", p.pan).put("alternate", p.alternate).put("tui", p.tui)
                        .put("rowStates", p.retainedRowStates).put("measuredUiRows", p.measuredRows)
                        .put("cursorChecked", checkCursor)
                        .put("confirmations", confirmations)
                    if (confirmations < 2) compose.runOnUiThread { trace.requestViewportDraw() }
                    confirmations >= 2
                }
                rectangles.put(requireNotNull(accepted))
                emit("TUI_HISTORY_PAGE_RECT", requireNotNull(accepted))
                return requireNotNull(accepted)
            }
            val initial = rectangle("initial", false)
            // Freeze only AFTER target-grid convergence and two actual same-source draws.
            val baseline = TerminalSizeReceipt(initial.getLong("request"), initial.getInt("columns"), initial.getInt("rows"))
            val physicalRows = baseline.rows
            val sentinel = terminal.renderFrame().historyLineIds[5000]
            fun kernelSize(phase: String, ime: Boolean) {
                val before = requireNotNull(manager.interactiveSessionSize(id))
                assertEquals("Applied rows changed after the measured baseline", physicalRows, before.rows)
                assertEquals(80, before.columns)
                val began = now()
                val response = Regex("(?m)^SIZE:$phase:(\\d+) (\\d+);ENV:(\\d+) (\\d+)\\r?\\n")
                send("SIZE:$phase")
                compose.waitUntil(15_000) { observeSize(); response.containsMatchIn(manager.readInteractiveBuffer(id).orEmpty()) }
                val match = requireNotNull(response.find(manager.readInteractiveBuffer(id).orEmpty()))
                val actualRows = match.groupValues[1].toInt()
                val actualColumns = match.groupValues[2].toInt()
                val snapshot = requireNotNull(manager.readInteractiveOutput(id, mode = "tail", limitBytes = 1, renderTerminal = true))
                val after = requireNotNull(manager.interactiveSessionSize(id))
                val reading = JSONObject().put("mode", mode.id).put("phase", phase).put("ime", ime)
                    .put("beforeNs", began).put("afterNs", now()).put("request", after.request)
                    .put("beforeRequest", before.request).put("reply", match.value)
                    .put("rows", actualRows).put("columns", actualColumns).put("modelRows", terminal.rows)
                    .put("modelColumns", terminal.columns).put("snapshotRows", snapshot.terminalRows)
                    .put("snapshotColumns", snapshot.terminalColumns).put("envRows", match.groupValues[3].toInt())
                    .put("envColumns", match.groupValues[4].toInt()).put("pendingResize", manager.hasPendingTerminalResize(id))
                kernelReadings.put(reading)
                emit("TUI_HISTORY_PAGE_KERNEL", reading)
                assertEquals("Kernel rows differ from measured baseline", physicalRows, actualRows)
                assertEquals(80, actualColumns)
                assertEquals("Size changed during the independent kernel query", before, after)
                assertEquals(physicalRows, snapshot.terminalRows)
                assertEquals(80, snapshot.terminalColumns)
                assertEquals("Inherited session environment must not change", 24, reading.getInt("envRows"))
                assertEquals(80, reading.getInt("envColumns"))
                compose.waitUntil(15_000) {
                    observeSize()
                    ready(ime) && trace.viewport()!!.viewport.revision == terminal.revision() &&
                        !manager.hasPendingTerminalResize(id)
                }
            }
            kernelSize("initial", false)
            val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            val inputBounds = input.boundsInWindow
            val overlaps = JSONArray()
            val outputWindows = JSONArray()
            fun buttonBounds(label: String) = compose.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(AnnotatedString(label))), true)
                .onFirst().performScrollTo().fetchSemanticsNode().boundsInWindow
            fun action(label: String) {
                val bounds = buttonBounds(label)
                tap(bounds.center.x, bounds.center.y)
            }
            val selectionBounds = buttonBounds("SEL")
            for ((phase, ime) in listOf("imeOpen" to true, "imeClosed" to false, "selection" to false, "reentry" to false)) {
                phaseLabel = phase
                observeSize()
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
                    observeSize()
                    drainTrace()
                    return ticks.findAll(manager.readInteractiveBuffer(id).orEmpty()).lastOrNull()?.groupValues?.get(1)?.toInt() ?: -1
                }
                send("RUN:$phase")
                compose.waitUntil(10_000) { tick() >= 1 }
                val beforeTick = tick()
                val beforeDraw = trace.viewport()!!.draw
                val beforeRevision = trace.viewport()!!.viewport.revision
                val beforeSize = requireNotNull(manager.interactiveSessionSize(id))
                val began = now()
                var detachedTicks = 0
                when (phase) {
                    "imeOpen" -> tap(inputBounds.center.x, inputBounds.center.y)
                    "imeClosed" -> back()
                    "selection" -> tap(selectionBounds.center.x, selectionBounds.center.y)
                    "reentry" -> {
                        val oldDisposals = disposals.get()
                        compose.runOnUiThread { mounted = false }
                        compose.waitUntil(10_000) { observeSize(); disposals.get() > oldDisposals }
                        val firstDetached = tick()
                        compose.waitUntil(10_000) { tick() >= firstDetached + 3 }
                        detachedTicks = tick() - firstDetached
                        compose.runOnUiThread { mounted = true }
                    }
                }
                // No global-idle or semantics traversal while the producer is active. Require
                // a NEW matching draw and MORE native output before the finite DONE marker.
                compose.waitUntil(30_000) {
                    observeSize()
                    drainTrace()
                    ready(ime, selection = phase == "selection") && trace.viewport()!!.draw > beforeDraw &&
                        trace.viewport()!!.viewport.revision > beforeRevision && tick() > beforeTick &&
                        terminal.rows == physicalRows && manager.interactiveSessionSize(id)?.rows == physicalRows &&
                        !manager.hasPendingTerminalResize(id)
                }
                val afterTick = tick()
                assertFalse("Interaction happened after output ended", received("DONE:$phase\r\n"))
                assertTrue(afterTick in (beforeTick+1)..159)
                val overlap = JSONObject().put("mode", mode.id).put("phase", phase).put("ime", ime)
                    .put("beforeTick", beforeTick).put("afterTick", afterTick).put("detachedTicks", detachedTicks)
                    .put("elapsedNs", now() - began).put("beforeDraw", beforeDraw).put("afterDraw", trace.viewport()!!.draw)
                    .put("beforeRevision", beforeRevision).put("afterRevision", trace.viewport()!!.viewport.revision)
                    .put("beforeRequest", beforeSize.request).put("afterRequest", manager.interactiveSessionSize(id)!!.request)
                    .put("beforeRows", beforeSize.rows).put("afterRows", terminal.rows)
                    .put("producerStillRunning", true).put("historyRows", terminal.renderFrame().historyCount)
                overlaps.put(overlap)
                emit("TUI_HISTORY_PAGE_OVERLAP", overlap)
                compose.waitUntil(45_000) { observeSize(); drainTrace(); received("DONE:$phase\r\n") }
                rectangle(phase, ime, baseline, selection = phase == "selection")
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
                if (phase == "selection") {
                    val expected = terminal.plainText(includeScrollback = true)
                    val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                    action("COPY")
                    compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == expected }
                    emit("FULL_VIRTUAL_PAGE_COPY", JSONObject().put("mode", mode.id).put("bytes", expected.toByteArray().size)
                        .put("sha256", MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()).joinToString("") { "%02x".format(it) })
                        .put("source", "unchanged-output-COPY").put("fullVirtual", true))
                    action("SEL")
                    compose.waitUntil(15_000) { ready(false) }
                }
                // Query-only peer lines are OUTSIDE the exact160tick burst byte window.
                kernelSize(phase, ime)
            }
            send("MOUSE")
            compose.waitUntil(15_000) { received("MOUSE-READY\r\n") }
            action("TOUCH")
            rectangle("mouse", false, baseline, pan = false)
            action("MOUSE")
            send("UNMOUSE")
            compose.waitUntil(15_000) { received("MOUSE-END\r\n") }
            send("ALT")
            compose.waitUntil(15_000) { received("ALT-READY\r\n") }
            rectangle("alternate", false, baseline, alternate = true, checkCursor = false)
            send("MAIN")
            compose.waitUntil(15_000) { received("MAIN-READY\r\n") }
            rectangle("main", false, baseline)
            assertTrue("Main screen surviving history lost on ALT round trip", sentinel in terminal.renderFrame().historyLineIds)
            // Verify the actual RAW UI bridge still submits a complete line through native PTY.
            phaseLabel = "input"
            val field = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            val setText = requireNotNull(field.config[SemanticsActions.SetText].action)
            val submit = requireNotNull(field.config[SemanticsActions.OnImeAction].action)
            compose.runOnUiThread { assertTrue(setText(AnnotatedString("ACK:bridge"))); assertTrue(submit()) }
            compose.waitUntil(15_000) { received("ACK:bridge\r\n") }
            rectangle("input", false, baseline)
            kernelSize("input", false)
            emit("TUI_HISTORY_PAGE", JSONObject().put("suite", "terminal-full-virtual-page-v1").put("mode", mode.id)
                .put("nativePty", true).put("rawInput", true).put("ptyMode", if (tui) "raw" else "cooked").put("peerInputDiscipline", "canonical-test-peer")
                .put("statusAndKeys", true).put("historyRows", 10000).put("physicalRows", physicalRows)
                .put("rectangles", rectangles).put("overlaps", overlaps).put("outputWindows", outputWindows).put("inputEcho", "ACK:bridge")
                .put("survivingHistoryRetained", true).put("traceDropped", 0)
                .put("baselineRequest", baseline.request).put("kernelReadings", kernelReadings)
                .put("resizeObservations", resizeObservations).put("gridContract", "measured-target-ack-kernel-v1")
                .put("disposalsBeforeCleanup", disposals.get()))
        } catch (error: Throwable) {
            val p = recording?.viewport()
            emit("TUI_HISTORY_PAGE_FAILURE", JSONObject().put("mode", mode.id).put("message", error.toString().take(500))
                .put("phase", phaseLabel).put("grid", lastGridReadiness).put("kernelReadings", kernelReadings)
                .put("resizeObservations", resizeObservations)
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
