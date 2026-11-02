package me.rerere.rikkahub.pipeline

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.container.PtyMode
import me.rerere.rikkahub.data.container.TerminalPipelineStage
import me.rerere.rikkahub.data.container.TerminalPipelineTrace
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.terminalRendererForCommand
import me.rerere.rikkahub.data.container.updatedTerminalRenderPreferences
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

/** Real306page and touch injection. The peer receives bytes; it cannot invoke local selection. */
class Terminal306DeviceDiagnosticTest : KoinComponent {
    @get:Rule val compose = createAndroidComposeRule<TerminalPipelineTestActivity>()
    @get:Rule val timeout = Timeout.seconds(240)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()
    private val settings: SettingsStore by inject()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }
    private fun emit(value: JSONObject) = instrumentation.sendStatus(0, Bundle().apply {
        putString("stream", "\nDEVICE306_CASE $value\n")
    })
    private fun shellRead(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun startupFence() {
        compose.waitUntil(30_000) {
            val text = shellRead("logcat -d -v brief --pid ${android.os.Process.myPid()} -s RikkaHubApp:I *:S")
            check(!text.contains("incrementLaunchCount failed"))
            text.contains("incrementLaunchCount:")
        }
    }
    private fun touch(action: Int, down: Long, position: Offset) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, position.x, position.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Platform touch rejected", automation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }
    private fun click(label: String) {
        val node = compose.onAllNodes(SemanticsMatcher("exact control $label") { n ->
            n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == label }
        }, true).onFirst().performScrollTo().fetchSemanticsNode()
        val position = node.positionOnScreen + Offset(node.size.width / 2f, node.size.height / 2f)
        val now = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, now, position); touch(MotionEvent.ACTION_UP, now, position)
        compose.waitForIdle()
    }
    private fun screenshot(name: String): String {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "device306").apply { mkdirs() }
        val target = File(dir, "$name.png")
        val image = checkNotNull(automation.takeScreenshot())
        try { target.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
        return target.absolutePath
    }
    @Test fun mouseFullVirtualSelOff() = page(TerminalRenderMode.FULL_VIRTUAL, true)
    @Test fun mouseDefaultSelOff() = page(TerminalRenderMode.DEFAULT, true)
    @Test fun widthFullVirtualLockAndAuto() = page(TerminalRenderMode.FULL_VIRTUAL, false)
    @Test fun widthDefaultLockAndAuto() = page(TerminalRenderMode.DEFAULT, false)

    private fun page(mode: TerminalRenderMode, mouse: Boolean) {
        startupFence()
        val saved = runBlocking { settings.settingsFlow.first { !it.init } }
        val tag = (if (mouse) "mouse" else "width") + "-" + mode.id
        val script = """
            stty raw -echo -ixon -onlcr -isig
            draw() {
              set -- ${'$'}(env -u LINES -u COLUMNS stty size)
              rr=${'$'}1; cc=${'$'}2
              printf '\033[?25l\033[2J\033[H'
              i=1
              while [ "${'$'}i" -le "${'$'}rr" ]; do
                printf '\033[%s;1HROW_%02d ' "${'$'}i" "${'$'}i"
                j=7
                while [ "${'$'}j" -lt "${'$'}((cc-1))" ]; do printf x; j=${'$'}((j+1)); done
                printf '|'; i=${'$'}((i+1))
              done
              printf '\r'
            }
            trap 'draw' WINCH
            printf 'READY306\r\n'
            while :; do
              if IFS= read -r cmd; then
                case "${'$'}cmd" in
                  DRAW) draw ;;
                  ARM:*)
                    phase=${'$'}{cmd#ARM:}; draw
                    printf '\033[?1002h\033[?1006h'
                    value=; count=0
                    while :; do
                      byte=${'$'}(dd bs=1 count=1 2>/dev/null | od -An -tx1 | tr -d ' \n')
                      [ "${'$'}byte" = 1f ] && break
                      [ -n "${'$'}byte" ] || exit 21
                      value="${'$'}value${'$'}byte"; count=${'$'}((count+1)); [ "${'$'}count" -lt 512 ] || exit 22
                    done
                    printf '\033[?1002l\033[?1006l\033[1;1H\033[2KBYTES_%s:%s\r' "${'$'}phase" "${'$'}value" ;;
                  OSC) printf '\033]52;c;ZGV2aWNlMzA2LW9zYzUyLWNvbnRyb2w=\007\033[1;1H\033[2KOSC306_SENT\r' ;;
                esac
              fi
            done
        """.trimIndent()
        val command = "sh -c '" + script.replace("'", "'\"'\"'") + "'"
        val sandbox = "device306-${UUID.randomUUID()}"
        var id: String? = null
        var capture: TerminalPipelineTrace.Capture? = null
        var mounted by mutableStateOf(false)
        val configuredLocally = AtomicBoolean(false)
        val evidence = JSONArray()
        val screenshots = JSONArray()
        val findings = ArrayList<String>()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val previousClip = compose.runOnUiThread { clipboard.primaryClip }
        val key = hash(command)
        fun configured(raw: String, render: String): Boolean {
            val items = runCatching { JSONArray(raw) }.getOrNull() ?: return false
            val item = (0 until items.length()).map { items.getJSONObject(it) }.singleOrNull { it.optString("key") == key }
                ?: return false
            return item.getBoolean("rawInputMode") && item.getBoolean("terminalPanMode") == !mouse &&
                item.getBoolean("showFullInputBar") && item.getBoolean("showStatusBar") && !item.getBoolean("showExtraKeys") &&
                item.getBoolean("autoScroll") && item.getInt("forcedTerminalColumns") == 80 &&
                terminalRendererForCommand(render, command) == mode
        }
        try {
            runBlocking {
                settings.update { it.copy(
                    terminalCommandPreferences = JSONArray().put(JSONObject().put("key", key).put("command", command).put("updatedAt", 1L)
                        .put("rawInputMode", true).put("terminalPanMode", !mouse).put("showFullInputBar", true)
                        .put("showStatusBar", true).put("showExtraKeys", false).put("autoScroll", true)
                        .put("forcedTerminalColumns", 80).put("terminalFontSizeSp", 12).put("maxScrollbackLines", 1000)).toString(),
                    terminalRenderPreferences = updatedTerminalRenderPreferences(it.terminalRenderPreferences, command, mode),
                    terminalFullGridCommands = "", terminalCustomTuiCommands = "sh",
                    terminalStatusBarItems = "[{\"id\":\"AUTO\"},{\"id\":\"COLS\"},{\"id\":\"TOUCH\"}]") }
                withTimeout(30_000) { settings.settingsFlowRaw.first { !it.init && configured(it.terminalCommandPreferences, it.terminalRenderPreferences) } }
                withTimeout(30_000) { settings.settingsFlow.first { !it.init && configured(it.terminalCommandPreferences, it.terminalRenderPreferences) } }
                proot.start().getOrThrow()
            }
            val started = runBlocking { manager.startInteractiveSession(sandbox, command, columns = 80, rows = 24, ptyMode = PtyMode.RAW) }
            assertTrue(started.message, started.success); assertEquals("native-pty", started.terminalBackend)
            val process = started.processId; id = process
            val terminal = checkNotNull(manager.getInteractiveTerminalEmulator(process))
            val trace = TerminalPipelineTrace.start(process, 4096, SystemClock::elapsedRealtimeNanos); capture = trace
            val nav = Navigator(mutableListOf())
            compose.setContent {
                val values by settings.settingsFlow.collectAsState()
                if (!mounted && !values.init && configured(values.terminalCommandPreferences, values.terminalRenderPreferences)) {
                    SideEffect { configuredLocally.set(true) }
                }
                RikkahubTheme {
                    CompositionLocalProvider(LocalSettings provides values, LocalNavController provides nav) {
                        if (mounted) ProcessSessionPage(sandbox, process)
                    }
                }
            }
            compose.waitUntil(30_000) { configuredLocally.get() && manager.readInteractiveBuffer(process)?.contains("READY306") == true }
            compose.runOnUiThread { mounted = true }
            fun send(text: String) = runBlocking { manager.sendInput(process, text + "\n", appendNewline = false).getOrThrow() }
            fun settle() {
                var signature: List<Any?>? = null
                var stableSince = SystemClock.elapsedRealtime()
                compose.waitUntil(30_000) {
                    val frame = terminal.renderFrame(); val ack = manager.interactiveSessionSize(process); val v = trace.viewport()?.viewport
                    val valid = v != null && v.initialized && v.sameFrame && v.geometryReady && (!mode.isFullVirtual || v.widthReady) &&
                        v.preferred == mode && v.applied == mode && v.pan == !mouse && !v.selection && v.effectId == null && v.gestureId == null &&
                        v.writers == 0 && !v.scrolling && v.revision == frame.revision && !manager.hasPendingTerminalResize(process) &&
                        ack != null && ack.columns == terminal.columns && ack.rows == terminal.rows
                    val next = listOf(frame.revision, ack?.request, ack?.columns, ack?.rows, v?.pan, v?.selection, v?.following)
                    if (!valid || signature != next) { signature = next; stableSince = SystemClock.elapsedRealtime() }
                    compose.runOnUiThread { trace.requestViewportDraw() }
                    valid && SystemClock.elapsedRealtime() - stableSince >= 250
                }
                compose.waitForIdle()
            }
            settle(); send("DRAW"); settle()
            fun viewReceipt(phase: String): JSONObject {
                val state = checkNotNull(trace.viewport()).viewport
                val viewport = compose.onNodeWithTag("terminal-trace-output").fetchSemanticsNode()
                val last = "ROW_${terminal.rows.toString().padStart(2, '0')} "
                val nodes = compose.onAllNodes(SemanticsMatcher("physical bottom") { n ->
                    n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.startsWith(last) }
                }, true).fetchSemanticsNodes()
                val bottom = nodes.singleOrNull()?.let { it.positionOnScreen.y + it.size.height }
                return JSONObject().put("phase", phase).put("following", state.following).put("pan", state.pan).put("selection", state.selection)
                    .put("tailSatisfied", state.tailSatisfied).put("writers", state.writers).put("columns", terminal.columns).put("rows", terminal.rows)
                    .put("screenGeneration", terminal.renderFrame().screenGeneration).put("viewportBottom", (viewport.positionOnScreen.y + viewport.size.height).toDouble())
                    .put("contentBottom", bottom?.toDouble() ?: JSONObject.NULL)
                    .put("bottomGap", bottom?.let { (viewport.positionOnScreen.y + viewport.size.height - it).toDouble() } ?: JSONObject.NULL)
            }
            evidence.put(viewReceipt("initial")); screenshots.put(screenshot("$tag-initial"))
            if (mouse) {
                for (phase in listOf("tap", "drag", "hold-drag")) {
                    send("ARM:$phase")
                    compose.waitUntil(15_000) { terminal.isMouseTrackingEnabled() }
                    settle(); trace.beginWindow()
                    val sentinel = "device306-$tag-$phase-untouched"
                    compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("test", sentinel)) }
                    val viewport = compose.onNodeWithTag("terminal-trace-output").fetchSemanticsNode()
                    val rows = compose.onAllNodes(SemanticsMatcher("physical ASCII row") { n ->
                        n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.startsWith("ROW_") }
                    }, true).fetchSemanticsNodes()
                    val node = rows.first { it.positionOnScreen.y >= viewport.positionOnScreen.y + 2 &&
                        it.positionOnScreen.y + it.size.height <= viewport.positionOnScreen.y + viewport.size.height - 2 }
                    val text = node.config[SemanticsProperties.Text].single().text
                    val row = text.substring(4, 6).toInt()
                    val width = terminal.cellPixelSize().first
                    val origin = node.positionOnScreen
                    val y = origin.y + node.size.height / 2f
                    val start = Offset(origin.x + width * 2.5f, y)
                    val expected = StringBuilder("\u001b[<0;3;${row}M")
                    val down = SystemClock.uptimeMillis()
                    var last = start
                    var released = false
                    try {
                        touch(MotionEvent.ACTION_DOWN, down, start)
                        if (phase == "hold-drag") SystemClock.sleep(800)
                        if (phase != "tap") for (column in 4..7) {
                            last = Offset(origin.x + width * (column - 0.5f), y)
                            SystemClock.sleep(40); touch(MotionEvent.ACTION_MOVE, down, last)
                            expected.append("\u001b[<32;${column};${row}M")
                        }
                        SystemClock.sleep(40); touch(MotionEvent.ACTION_UP, down, last)
                        released = true
                    } finally {
                        if (!released) touch(MotionEvent.ACTION_CANCEL, down, last)
                    }
                    expected.append("\u001b[<0;${if (phase == "tap") 3 else 7};${row}m")
                    // Fence BOTH input queues before the direct-manager terminator: UI_INPUT is
                    // emitted synchronously for every accepted pointer write; manager enqueues
                    // are asynchronous and cannot be inferred from one momentary empty queue.
                    compose.waitUntil(5_000) {
                        val writes = trace.snapshot()
                        writes.count { it.stage == TerminalPipelineStage.UI_INPUT } ==
                            writes.count { it.stage == TerminalPipelineStage.INPUT_ENQUEUED } &&
                            writes.filter { it.stage == TerminalPipelineStage.INPUT_ENQUEUED }.sumOf { it.bytes } ==
                            writes.filter { it.stage == TerminalPipelineStage.INPUT_WRITTEN }.sumOf { it.bytes }
                    }
                    // Complete the independent byte-reading peer even if the UI emitted no input.
                    runBlocking { manager.sendInput(process, "\u001f", appendNewline = false).getOrThrow() }
                    val receipt = Regex("BYTES_${Regex.escape(phase)}:([0-9a-f]*)\\r")
                    compose.waitUntil(30_000) { receipt.containsMatchIn(manager.readInteractiveBuffer(process).orEmpty()) }
                    val actual = checkNotNull(receipt.find(manager.readInteractiveBuffer(process).orEmpty())).groupValues[1]
                    val clipboardText = compose.runOnUiThread { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
                    val state = checkNotNull(trace.viewport()).viewport
                    val events = trace.snapshot()
                    val localSelections = compose.onAllNodes(SemanticsMatcher("non-editable local selection") { n ->
                        !n.config.contains(SemanticsProperties.EditableText) &&
                            n.config.getOrElse(SemanticsProperties.TextSelectionRange) { androidx.compose.ui.text.TextRange.Zero }.collapsed.not()
                    }, true).fetchSemanticsNodes().size
                    val record = viewReceipt(phase).put("row", row).put("expectedHex", hex(expected.toString().toByteArray())).put("receivedHex", actual)
                        .put("wireMatches", actual == hex(expected.toString().toByteArray())).put("clipboardUnchanged", clipboardText == sentinel)
                        .put("clipboardSha256", clipboardText?.let(::hash) ?: JSONObject.NULL)
                        .put("uiInputCount", events.count { it.stage == TerminalPipelineStage.UI_INPUT })
                        .put("localNonEditableSelections", localSelections).put("traceDropped", trace.dropped)
                        .put("nativeEventPosition", JSONArray(listOf(start.x.toDouble(), start.y.toDouble(), last.x.toDouble(), last.y.toDouble())))
                        .put("cellWidth", width).put("physicalRows", terminal.rows).put("pointerColumnRange", JSONArray(listOf(3, if (phase == "tap") 3 else 7)))
                        .put("inputEvents", JSONArray().apply { events.filter { it.stage in setOf(TerminalPipelineStage.UI_INPUT,
                            TerminalPipelineStage.INPUT_ENQUEUED, TerminalPipelineStage.INPUT_WRITTEN) }.forEach {
                            put(JSONObject().put("seq", it.sequence).put("stage", it.stage.name).put("bytes", it.bytes)) } })
                        .put("inputWrittenBytes", events.filter { it.stage == TerminalPipelineStage.INPUT_WRITTEN }.sumOf { it.bytes })
                    evidence.put(record); screenshots.put(screenshot("$tag-$phase"))
                    if (!record.getBoolean("wireMatches")) findings.add("$phase:wire-mismatch")
                    if (clipboardText != sentinel) findings.add("$phase:unexpected-clipboard")
                    if (localSelections != 0) findings.add("$phase:local-selection")
                    if (state.pan || state.selection) findings.add("$phase:mode-not-mouse-seloff")
                }
                send("OSC")
                compose.waitUntil(15_000) { compose.runOnUiThread { clipboard.primaryClip?.getItemAt(0)?.text?.toString() } == "device306-osc52-control" }
                evidence.put(JSONObject().put("phase", "explicit-osc52-control").put("clipboardMatches", true))
            } else {
                assertTrue(checkNotNull(trace.viewport()).viewport.following)
                click("AUTO"); settle(); val before = viewReceipt("before-locked-width"); evidence.put(before)
                assertFalse(before.getBoolean("following")); assertEquals(80, terminal.columns)
                click("80C"); compose.waitUntil(20_000) { terminal.columns == 100 }; settle()
                val after = viewReceipt("after-locked-width"); evidence.put(after)
                after.put("preservedPriorMode", !after.getBoolean("following"))
                if (after.getBoolean("following")) findings.add("width:LOCK-became-AUTO")
                if (!after.getBoolean("following")) { click("LOCK"); settle() }
                evidence.put(viewReceipt("before-auto-width")); click("100C")
                compose.waitUntil(20_000) { terminal.columns == 120 }; settle()
                val autoAfter = viewReceipt("after-auto-width"); autoAfter.put("preservedPriorMode", autoAfter.getBoolean("following")); evidence.put(autoAfter)
                if (!autoAfter.getBoolean("following")) findings.add("width:AUTO-became-LOCK")
                screenshots.put(screenshot("$tag-after-width"))
            }
            emit(JSONObject().put("case", tag).put("mode", mode.id).put("nativePty", true).put("productionChanged", false)
                .put("executionComplete", true).put("findings", JSONArray(findings)).put("observations", evidence).put("screenshots", screenshots)
                .put("targetApkSha256", fileHash(File(instrumentation.targetContext.applicationInfo.sourceDir)))
                .put("performanceMeasured", false).put("allReportedDeviceBugsFixed", false))
        } catch (error: Throwable) {
            emit(JSONObject().put("case", tag).put("mode", mode.id).put("executionComplete", false).put("error", error.toString())
                .put("findings", JSONArray(findings)).put("observations", evidence).put("screenshots", screenshots)
                .put("viewport", capture?.viewport()?.viewport.toString()).put("peerTail", id?.let { manager.readInteractiveBuffer(it)?.takeLast(2000) }))
            throw error
        } finally {
            try {
                compose.runOnUiThread { mounted = false }
                compose.waitForIdle()
            } finally {
                capture?.close()
                try {
                    id?.let { process -> runBlocking { manager.closeInteractiveSession(process) }; manager.removeProcessRecord(process) }
                } finally {
                    compose.runOnUiThread { if (previousClip != null) clipboard.setPrimaryClip(previousClip) else clipboard.clearPrimaryClip() }
                    runBlocking { settings.update { it.copy(terminalCommandPreferences = saved.terminalCommandPreferences,
                        terminalRenderPreferences = saved.terminalRenderPreferences, terminalCustomTuiCommands = saved.terminalCustomTuiCommands,
                        terminalFullGridCommands = saved.terminalFullGridCommands, terminalStatusBarItems = saved.terminalStatusBarItems) } }
                }
            }
            emit(JSONObject().put("case", tag).put("cleanup", true).put("closed", id?.let { manager.getInteractiveTerminalEmulator(it) == null } ?: true))
        }
    }
}
