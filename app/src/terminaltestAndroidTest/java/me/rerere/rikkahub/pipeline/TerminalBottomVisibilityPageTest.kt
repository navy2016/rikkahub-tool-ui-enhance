package me.rerere.rikkahub.pipeline

import android.os.Bundle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.container.PtyMode
import me.rerere.rikkahub.data.container.TerminalPipelineTrace
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

/** The production page, actual child output and native PTY; cursor/row bounds are independent of controller formulas. */
class TerminalBottomVisibilityPageTest : KoinComponent {
    @get:Rule val compose = createAndroidComposeRule<TerminalPipelineTestActivity>()
    @get:Rule val timeout = Timeout.seconds(240)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()
    private val settings: SettingsStore by inject()

    @Test fun bottomPageBlankCursorWithStatusKeysImeAndRemount() = exercise(tui = false)
    @Test fun bottomPageTuiGrowingNaturalHistoryWithStatusKeysAndIme() = exercise(tui = true)

    private fun exercise(tui: Boolean) {
        val saved = runBlocking { settings.settingsFlow.first { !it.init } }
        val sandbox = "bottom-visible-${UUID.randomUUID()}"
        val script = """
            stty -echo -onlcr
            printf 'READY\r\n'
            total=0
            while IFS= read -r command; do
                case "${'$'}command" in
                    SEED*)
                        count=${'$'}{command#SEED}
                        i=0
                        while [ "${'$'}i" -lt "${'$'}count" ]; do
                            printf 'history-%06d 中文 é 👩‍💻\r\n' "${'$'}total"
                            total=${'$'}((total+1)); i=${'$'}((i+1))
                        done
                        printf 'DONE:%s\r\n' "${'$'}total"
                        ;;
                    NEXT) printf 'NEXT\r\n';;
                esac
            done
        """.trimIndent()
        val command = "sh -c '" + script.replace("'", "'\"'\"'") + "'"
        var mounted by mutableStateOf(true)
        var id: String? = null
        var trace: TerminalPipelineTrace.Capture? = null
        try {
            runBlocking {
                val key = MessageDigest.getInstance("SHA-256").digest(command.toByteArray()).joinToString("") { "%02x".format(it) }
                settings.update { it.copy(terminalCommandPreferences = JSONArray().put(JSONObject()
                    .put("key", key).put("command", command).put("updatedAt", 1L)
                    .put("rawInputMode", true).put("terminalPanMode", true).put("showFullInputBar", true)
                    .put("showStatusBar", true).put("showExtraKeys", true).put("autoScroll", true)
                    .put("maxScrollbackLines", 4000).put("terminalFontSizeSp", 12).put("forcedTerminalColumns", 80)).toString(),
                    terminalCustomTuiCommands = if (tui) "sh" else "", terminalFullGridCommands = "") }
                settings.setTerminalRenderer(command, TerminalRenderMode.DEFAULT)
                proot.start().getOrThrow()
            }
            // Cooked line discipline is deliberate; TUI policy is forced through the same command
            // matcher as real Pi without requiring an account or pretending this child is Pi.
            val result = runBlocking { manager.startInteractiveSession(sandbox, command, ptyMode = PtyMode.COOKED) }
            assertTrue(result.message, result.success)
            assertEquals("native-pty", result.terminalBackend)
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
            compose.waitUntil(15_000) { manager.readInteractiveBuffer(process)?.contains("READY") == true }
            Espresso.closeSoftKeyboard()
            compose.waitUntil(15_000) { capture.viewport()?.viewport?.let { it.initialized && !it.ime && it.writers == 0 } == true }
            val terminal = requireNotNull(manager.getInteractiveTerminalEmulator(process))
            var total = 0
            val geometry = ArrayList<JSONObject>()

            fun assertCursor(phase: String, ime: Boolean, preservedRows: Int? = null) {
                val after = capture.viewport()?.draw ?: 0L
                var consecutive = 0
                var previousDraw = after
                var previousGeometry: List<Any>? = null
                var accepted: JSONObject? = null
                var lastReadiness: JSONObject? = null
                try { compose.waitUntil(15_000) {
                    val sample = capture.viewport() ?: return@waitUntil false
                    if (sample.draw <= previousDraw) {
                        compose.runOnUiThread { capture.requestViewportDraw() }
                        return@waitUntil false
                    }
                    previousDraw = sample.draw
                    val frame = terminal.renderFrame()
                    val expected = frame.rows[frame.screenStartRow + frame.cursorRow].text
                    val p = sample.viewport
                    val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), true)
                        .fetchSemanticsNodes().filter { it.config[SemanticsProperties.Text].singleOrNull() == expected }
                    val viewports = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag,
                        "terminal-trace-output"), true).fetchSemanticsNodes()
                    val desiredRows = preservedRows ?: viewports.singleOrNull()?.let {
                        (it.size.height / terminal.cellPixelSize().second).coerceIn(6, 80)
                    }
                    lastReadiness = JSONObject().put("phase", phase).put("revision", frame.revision)
                        .put("publishedRevision", p.revision).put("sameFrame", p.sameFrame).put("geometryReady", p.geometryReady)
                        .put("nodes", nodes.size).put("viewports", viewports.size).put("cursorVisible", frame.cursorVisible)
                        .put("blank", expected.text.isBlank()).put("rows", terminal.rows).put("requestedRows", desiredRows)
                        .put("pendingResize", manager.hasPendingTerminalResize(process)).put("ime", p.ime)
                        .put("tui", p.tui).put("writers", p.writers).put("effect", p.effectId)
                    if (!frame.cursorVisible || !expected.text.isBlank() || nodes.size != 1 || viewports.size != 1 ||
                        p.revision < frame.revision || !p.initialized || !p.sameFrame || !p.geometryReady ||
                        p.tui != tui || p.ime != ime || p.writers != 0 || p.scrolling || p.effectId != null || p.gestureId != null ||
                        terminal.rows != desiredRows || manager.hasPendingTerminalResize(process)) {
                        consecutive = 0
                        previousGeometry = null
                        compose.runOnUiThread { capture.requestViewportDraw() }
                        return@waitUntil false
                    }
                    val row = nodes.single()
                    val viewport = viewports.single()
                    val top = row.positionInRoot.y - viewport.positionInRoot.y
                    val bottom = top + row.size.height
                    accepted = JSONObject().put("phase", phase).put("tui", tui).put("ime", ime)
                        .put("historyRows", frame.historyCount).put("cursorRow", frame.cursorRow)
                        .put("top", top.toDouble()).put("bottom", bottom.toDouble()).put("viewport", viewport.size.height)
                        .put("rowHeight", row.size.height).put("draw", sample.draw).put("revision", frame.revision)
                    val key = listOf(frame.revision, frame.screenLineIds[frame.cursorRow], row.size.height,
                        viewport.size.height, top, bottom)
                    if (top >= -1f && bottom <= viewport.size.height + 1f) {
                        consecutive = if (previousGeometry == key) consecutive + 1 else 1
                        previousGeometry = key
                    } else {
                        consecutive = 0
                        previousGeometry = null
                    }
                    if (consecutive < 2) compose.runOnUiThread { capture.requestViewportDraw() }
                    consecutive >= 2
                } } catch (error: Throwable) {
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                        putString("stream", "\nBOTTOM_PAGE_FAILURE " + JSONObject().put("phase", phase)
                            .put("readiness", lastReadiness).put("rectangle", accepted) + "\n")
                    })
                    throw error
                }
                val acceptedValue = requireNotNull(accepted).put("confirmations", consecutive)
                geometry.add(acceptedValue)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                    putString("stream", "\nBOTTOM_PAGE_RECT $acceptedValue\n")
                })
            }

            for (count in if (tui) listOf(160, 400, 600) else listOf(160)) {
                capture.beginWindow()
                total += count
                runBlocking { manager.sendInput(process, "SEED$count").getOrThrow() }
                compose.waitUntil(20_000) { manager.readInteractiveBuffer(process)?.contains("DONE:$total\r\n") == true }
                assertCursor("history$total", false)
            }
            val initialRows = terminal.rows
            compose.onNode(hasSetTextAction()).performClick()
            compose.waitUntil(10_000) { capture.viewport()?.viewport?.ime == true }
            assertCursor("imeOpen", true, initialRows)
            assertEquals(initialRows, terminal.rows)
            Espresso.closeSoftKeyboard()
            compose.waitUntil(10_000) { capture.viewport()?.viewport?.ime == false }
            assertCursor("imeClosed", false, initialRows)
            assertEquals(initialRows, terminal.rows)
            // Top toolbar toggles strip visibility without touching process or preferred renderer.
            compose.onNodeWithText("隐藏状态").performClick()
            assertCursor("statusHidden", false)
            compose.onNodeWithText("状态", useUnmergedTree = true).performClick()
            assertCursor("statusShown", false)
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            compose.runOnIdle { mounted = true }
            compose.waitForIdle()
            assertCursor("remount", false)
            assertEquals(TerminalRenderMode.DEFAULT, capture.viewport()!!.viewport.preferred)
            val receipt = JSONObject().put("suite", "terminal-bottom-page-v1").put("case", if (tui) "tui" else "cursor")
                .put("geometry", JSONArray(geometry)).put("nativePty", true).put("defaultRenderer", true)
                .put("statusAndKeys", true).put("initialRows", initialRows)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "\nBOTTOM_PAGE $receipt\n")
            })
        } finally {
            compose.runOnUiThread { mounted = false }
            compose.waitForIdle()
            trace?.close()
            id?.let { runBlocking { manager.closeInteractiveSession(it) }; manager.removeProcessRecord(it) }
            runBlocking { settings.update { it.copy(terminalCommandPreferences = saved.terminalCommandPreferences,
                terminalRenderPreferences = saved.terminalRenderPreferences, terminalCustomTuiCommands = saved.terminalCustomTuiCommands,
                terminalFullGridCommands = saved.terminalFullGridCommands) } }
        }
    }
}
