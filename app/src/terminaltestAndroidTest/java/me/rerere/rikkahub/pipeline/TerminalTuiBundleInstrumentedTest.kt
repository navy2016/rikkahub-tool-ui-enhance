package me.rerere.rikkahub.pipeline

import android.os.Bundle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.container.*
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

/** Real manager/page/native PTY, fixed synthetic peer; no account or external TUI conversation. */
class TerminalTuiBundleInstrumentedTest : KoinComponent {
    @get:Rule val compose = createAndroidComposeRule<TerminalPipelineTestActivity>()
    @get:Rule val timeout = Timeout.seconds(180)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()
    private val settings: SettingsStore by inject()
    private fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
    private fun shell(text: String) = "sh -c " + quote(text)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun receipt(case: String, value: JSONObject = JSONObject()) {
        value.put("suite", "terminal-tui-bundle-pty-v1").put("case", case).put("passed", true)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nTUI_PTY $value\n")
        })
    }

    private fun awaitOutput(id: String, marker: String) = compose.waitUntil(15_000) {
        manager.readInteractiveBuffer(id)?.contains(marker) == true
    }

    @Test fun tuiPtyProtocolHandshakeReturnsExactBytesAndKeepsCursor() {
        runBlocking { proot.start().getOrThrow() }
        val expected = "\u001b[?1;2c\u001b[>4;2m\u001b[5;11R".toByteArray()
        val script = """
            stty raw -echo -ixon -onlcr -isig
            printf '\033[3;4H\0337\033[5;11H\033[>7u\033[?u\033[c\033[>4;2m\033[?4m\033[6n'
            value=${'$'}(dd bs=1 count=${expected.size} 2>/dev/null | od -An -tx1 | tr -d ' \n')
            printf '\r\nHANDSHAKE:%s\r\n' "${'$'}value"
        """.trimIndent()
        val started = runBlocking { manager.startInteractiveSession("tui-protocol-${UUID.randomUUID()}",
            shell(script), columns = 80, rows = 24, ptyMode = PtyMode.RAW) }
        assertTrue(started.message, started.success)
        try {
            awaitOutput(started.processId, "HANDSHAKE:${hex(expected)}")
            assertEquals("native-pty", started.terminalBackend)
            receipt("protocol", JSONObject().put("responseBytes", expected.size).put("responseHex", hex(expected)))
        } finally {
            runBlocking { manager.closeInteractiveSession(started.processId) }
            manager.removeProcessRecord(started.processId)
        }
    }

    private fun page(script: String, mode: TerminalRenderMode = TerminalRenderMode.DEFAULT,
        mouse: Boolean = false, regular: Boolean = false, action: (String, TerminalPipelineTrace.Capture) -> Unit) {
        val sandbox = "tui-page-${UUID.randomUUID()}"
        val fixturePath = "/workspace/$sandbox/pi"
        val command = if (regular) "$fixturePath --tui-mode regular" else shell(script)
        val saved = runBlocking { settings.settingsFlow.first { !it.init } }
        var mounted by mutableStateOf(true)
        var id: String? = null
        var trace: TerminalPipelineTrace.Capture? = null
        try {
            runBlocking {
                val key = hex(MessageDigest.getInstance("SHA-256").digest(command.toByteArray()))
                settings.update { it.copy(terminalCommandPreferences = JSONArray().put(JSONObject()
                    .put("key", key).put("command", command).put("updatedAt", 1L).put("rawInputMode", true)
                    .put("terminalPanMode", !mouse).put("showFullInputBar", true).put("showStatusBar", mouse)
                    .put("showExtraKeys", false).put("autoScroll", true).put("forcedTerminalColumns", 80)).toString()) }
                settings.setTerminalRenderer(command, mode)
                proot.start().getOrThrow()
                if (regular) {
                    // Test-owned executable text uses the launch shell's ENOEXEC fallback.
                    // A synthetic peer validates policy wiring, not a real Pi conversation.
                    val setup = proot.execInteractive(sandbox, listOf("sh", "-c",
                        "mkdir -p ${quote("/workspace/$sandbox")}; printf '%s' ${quote(script + "\n")} > " +
                            "${quote(fixturePath)}; chmod 700 ${quote(fixturePath)}"))
                    try { check(setup.waitFor(15, TimeUnit.SECONDS) && setup.exitValue() == 0) }
                    finally { if (setup.isAlive) setup.destroyForcibly() }
                }
            }
            // COOKED is only the initial launch mode; the peer switches the kernel to raw before READY.
            val result = runBlocking { manager.startInteractiveSession(sandbox, command,
                ptyMode = if (regular) PtyMode.RAW else PtyMode.COOKED) }
            assertTrue(result.message, result.success)
            val process = result.processId
            id = process
            val capture = TerminalPipelineTrace.start(process, 4096)
            trace = capture
            val nav = Navigator(mutableListOf())
            compose.setContent {
                val values by settings.settingsFlow.collectAsState()
                RikkahubTheme {
                    CompositionLocalProvider(LocalSettings provides values, LocalNavController provides nav) {
                        if (mounted) ProcessSessionPage(sandbox, process)
                    }
                }
            }
            awaitOutput(process, "READY")
            compose.waitUntil(15_000) { capture.viewport()?.viewport?.initialized == true }
            compose.waitForIdle()
            action(process, capture)
        } finally {
            compose.runOnUiThread { mounted = false }
            compose.waitForIdle()
            trace?.close()
            id?.let { runBlocking { manager.closeInteractiveSession(it) }; manager.removeProcessRecord(it) }
            if (regular) runBlocking {
                val cleanup = proot.execInteractive(sandbox, listOf("sh", "-c",
                    "rm -f ${quote(fixturePath)}; rmdir ${quote("/workspace/$sandbox")}"))
                try { check(cleanup.waitFor(15, TimeUnit.SECONDS)) } finally { if (cleanup.isAlive) cleanup.destroyForcibly() }
            }
            runBlocking { settings.update { it.copy(terminalCommandPreferences = saved.terminalCommandPreferences,
                terminalRenderPreferences = saved.terminalRenderPreferences) } }
        }
    }

    @Test fun tuiPageImeCommitsAndDeletesWholeGraphemesInExactOrder() {
        val expected = "e\u0301👍🏽\u007F\u007F".toByteArray()
        val script = """
            stty raw -echo -ixon -onlcr -isig
            printf '\033[?25lREADY\r\n'
            value=${'$'}(dd bs=1 count=${expected.size} 2>/dev/null | od -An -tx1 | tr -d ' \n')
            printf '\r\nIME_HEX:%s\r\n' "${'$'}value"
            sleep 30
        """.trimIndent()
        page(script) { id, capture ->
            capture.beginWindow()
            for (text in listOf("e\u0301", "e\u0301👍🏽", "")) {
                compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) {
                    assertTrue(it(AnnotatedString(text)))
                }
                compose.waitForIdle()
            }
            awaitOutput(id, "IME_HEX:${hex(expected)}")
            assertEquals(3, capture.snapshot().count { it.stage == TerminalPipelineStage.UI_INPUT })
            assertEquals(expected.size, capture.snapshot().filter { it.stage == TerminalPipelineStage.INPUT_WRITTEN }.sumOf { it.bytes })
            receipt("ime", JSONObject().put("inputBytes", expected.size).put("inputHex", hex(expected)))
        }
    }

    @Test fun tuiPageMouseClickUsesActualScreenRowBelowStatusStrip() {
        val expected = "\u001b[<0;3;1M\u001b[<0;3;1m".toByteArray()
        val script = """
            stty raw -echo -ixon -onlcr -isig
            printf '\033[?25l\033[?1000h\033[?1006hMOUSE_READY\r\n'
            value=${'$'}(dd bs=1 count=${expected.size} 2>/dev/null | od -An -tx1 | tr -d ' \n')
            printf '\r\nMOUSE_HEX:%s\r\n' "${'$'}value"
            sleep 30
        """.trimIndent()
        page(script, mouse = true) { id, _ ->
            val terminal = requireNotNull(manager.getInteractiveTerminalEmulator(id))
            val width = terminal.cellPixelSize().first
            val matcher = SemanticsMatcher("mouse peer row") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.trimEnd() == "MOUSE_READY" }
            }
            compose.onNode(matcher, useUnmergedTree = true).performTouchInput {
                click(Offset(width * 2f + 1f, center.y))
            }
            awaitOutput(id, "MOUSE_HEX:${hex(expected)}")
            receipt("mouse", JSONObject().put("inputBytes", expected.size).put("inputHex", hex(expected)))
        }
    }

    @Test fun tuiPtyResizeReceiptMatchesKernelAndAtomicSnapshot() {
        runBlocking { proot.start().getOrThrow() }
        // BusyBox stty reads TIOCGWINSZ but its shared helper prefers inherited LINES/COLUMNS.
        // Remove ONLY those variables from the query subprocess; leave the session environment
        // and production launch/resize behavior unchanged. Keep both observations as evidence.
        val script = """
            stty -echo -onlcr
            printf 'READY\r\n'
            while IFS= read -r line; do
                case "${'$'}line" in SIZE:*) token=${'$'}{line#SIZE:};; *) exit 9;; esac
                inherited=${'$'}(stty size) || exit 10
                kernel=${'$'}(env -u LINES -u COLUMNS stty size) || exit 11
                printf 'SIZE:%s:%s;INHERITED:%s;ENV:%s %s\r\n' "${'$'}token" "${'$'}kernel" "${'$'}inherited" "${'$'}LINES" "${'$'}COLUMNS"
            done
        """.trimIndent()
        val started = runBlocking { manager.startInteractiveSession("tui-resize-${UUID.randomUUID()}", shell(script)) }
        assertTrue(started.message, started.success)
        val id = started.processId
        try {
            assertEquals("native-pty", started.terminalBackend)
            awaitOutput(id, "READY")
            val readings = JSONArray()
            for ((columns, rows) in listOf(60 to 10, 100 to 20, 80 to 24)) {
                val ack = runBlocking { manager.resizeInteractiveSessionAcknowledged(id, columns, rows).getOrThrow() }
                assertEquals(ack, manager.interactiveSessionSize(id))
                val response = Regex("(?m)^SIZE:${ack.request}:(\\d+) (\\d+);INHERITED:(\\d+) (\\d+);ENV:(\\d+) (\\d+)\\r?\\n")
                runBlocking { manager.sendInput(id, "SIZE:${ack.request}").getOrThrow() }
                try {
                    // Wait for a COMPLETE independently observed response, not for values equal
                    // to the expectation. A wrong dimension fails immediately at assertEquals.
                    compose.waitUntil(15_000) { response.containsMatchIn(manager.readInteractiveBuffer(id).orEmpty()) }
                } catch (error: Throwable) {
                    val diagnostic = JSONObject().put("request", ack.request).put("columns", columns).put("rows", rows)
                        .put("syntheticPeerTail", manager.readInteractiveBuffer(id).orEmpty().takeLast(500))
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("stream", "\nTUI_RESIZE_FAILURE $diagnostic\n")
                    })
                    throw error
                }
                val match = requireNotNull(response.find(manager.readInteractiveBuffer(id).orEmpty()))
                val actualRows = match.groupValues[1].toInt()
                val actualColumns = match.groupValues[2].toInt()
                val snap = requireNotNull(manager.readInteractiveOutput(id, renderTerminal = true))
                val reading = JSONObject().put("request", ack.request).put("rows", actualRows).put("columns", actualColumns)
                    .put("inheritedRows", match.groupValues[3].toInt()).put("inheritedColumns", match.groupValues[4].toInt())
                    .put("envRows", match.groupValues[5].toInt()).put("envColumns", match.groupValues[6].toInt())
                    .put("snapshotRows", snap.terminalRows).put("snapshotColumns", snap.terminalColumns)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "\nTUI_RESIZE_OBSERVED $reading\n")
                })
                assertEquals(rows, actualRows)
                assertEquals(columns, actualColumns)
                assertEquals(columns, snap.terminalColumns)
                assertEquals(rows, snap.terminalRows)
                assertEquals(24, reading.getInt("envRows"))
                assertEquals(80, reading.getInt("envColumns"))
                assertEquals(reading.getInt("envRows"), reading.getInt("inheritedRows"))
                assertEquals(reading.getInt("envColumns"), reading.getInt("inheritedColumns"))
                readings.put(reading)
            }
            receipt("resize", JSONObject().put("kernelChecks", 3).put("kernelReadings", readings))
        } finally { runBlocking { manager.closeInteractiveSession(id) }; manager.removeProcessRecord(id) }
    }

    @Test fun tuiPageOverlayIsExplicitAndKeepsUnderlyingTextVisible() {
        val script = "stty raw -echo -ixon -onlcr -isig; printf 'READYabcdef\\033[5 q\\033[1;3H'; sleep 30"
        page(script, TerminalRenderMode.CURSOR_OVERLAY) { id, capture ->
            compose.waitUntil(10_000) { capture.snapshot().any { it.stage == TerminalPipelineStage.FRAME_DRAWN } }
            val matcher = SemanticsMatcher("unmodified text under overlay") { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "READYabcdef" }
            }
            assertEquals(1, compose.onAllNodes(matcher, true).fetchSemanticsNodes().size)
            assertEquals(TerminalRenderMode.CURSOR_OVERLAY, capture.viewport()!!.viewport.preferred)
            assertTrue(requireNotNull(manager.getInteractiveTerminalEmulator(id)).plainText().contains("READYabcdef"))
            receipt("overlay", JSONObject().put("textIntact", true))
        }
    }

    @Test fun tuiPageRegularHistoryKeepsRawInputAndRestoresAlternateFallback() {
        val script = """
            stty raw -echo -ixon -onlcr -isig
            printf '\033[?25lREADY\r\n'
            read_byte() { value=${'$'}(dd bs=1 count=1 2>peer-read.log | od -An -tx1); }
            read_byte
            i=0
            while [ "${'$'}i" -lt 1100 ]; do printf 'history-%04d 中文\r\n' "${'$'}i"; i=${'$'}((i+1)); done
            printf 'REGULAR_DONE\r\n'
            read_byte
            printf '\033[?1049hALTERNATE_DONE'
            read_byte
            printf '\033[?1049l\r\nRESTORED_DONE\r\n'
            sleep 30
        """.trimIndent()
        page(script, TerminalRenderMode.REGULAR_HISTORY, regular = true) { id, capture ->
            fun input(text: String) {
                compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) {
                    assertTrue(it(AnnotatedString(text)))
                }
            }
            assertEquals("raw", manager.getProcess(id)?.ptyMode)
            input("s")
            awaitOutput(id, "REGULAR_DONE")
            compose.waitUntil(15_000) { capture.viewport()?.viewport?.let {
                !it.tui && !it.alternate && it.hasChunks && it.geometryReady && it.tailSatisfied
            } == true }
            input("sa")
            awaitOutput(id, "ALTERNATE_DONE")
            compose.waitUntil(15_000) { capture.viewport()?.viewport?.let { it.tui && it.alternate && !it.hasChunks } == true }
            input("san")
            awaitOutput(id, "RESTORED_DONE")
            compose.waitUntil(15_000) { capture.viewport()?.viewport?.let {
                !it.tui && !it.alternate && it.hasChunks && it.geometryReady && it.tailSatisfied
            } == true }
            assertEquals(1000, manager.getInteractiveTerminalEmulator(id)!!.renderFrame().historyCount)
            receipt("regular", JSONObject().put("historyRows", 1000).put("rawInput", true)
                .put("alternateRoundTrip", true).put("peer", "synthetic-pi-regular"))
        }
    }
}
