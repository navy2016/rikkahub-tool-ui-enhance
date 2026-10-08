package me.rerere.rikkahub.pipeline

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
    @get:Rule val timeout = Timeout.seconds(240)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()
    private val settings: SettingsStore by inject()
    private var diagnosticProcessId: String? = null
    private var diagnosticCapture: TerminalPipelineTrace.Capture? = null

    @Test fun pipelineDefaultPtyEchoKeyboardAndRemount() = exercise(TerminalRenderMode.DEFAULT)
    @Test fun pipelineVirtualPtyEchoKeyboardAndRemount() = exercise(TerminalRenderMode.VIRTUAL_HISTORY)
    @Test fun pipelineStableVirtualPtyEchoKeyboardAndRemount() = exercise(TerminalRenderMode.VIRTUAL_HISTORY_IME)

    private fun exercise(mode: TerminalRenderMode) {
        check(compose.activity.packageName == "me.rerere.rikkahub.dev.next.terminaltest")
        if (Build.VERSION.SDK_INT >= 33) assertEquals("Runner must grant only the test app's notifications before launch",
            PackageManager.PERMISSION_GRANTED, compose.activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val launch = UUID.randomUUID().toString()
        val sandbox = "pipeline-$launch"
        // Cooked input keeps exact line semantics; echo is from the child only, no line-discipline echo.
        val script = """
            stty -echo -onlcr
            printf '\033[?25lREADY\r\n'
            while IFS= read -r line; do
              if [ "${'$'}line" = SEED ]; then
                i=0; while [ "${'$'}i" -lt 1100 ]; do printf 'history-%04d 中文\r\n' "${'$'}i"; i=${'$'}((i+1)); done
                printf 'SEED_DONE\r\n'
              else
                printf 'ECHO:%s\r\n' "${'$'}line"
              fi
            done
        """.trimIndent()
        val command = "sh -c '" + script.replace("'", "'\"'\"'") + "'"
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
                    .put("forcedTerminalColumns", 80).put("maxScrollbackLines", 1000)).toString()) }
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
            assertEquals("Incorrect renderer for keyboard phase", expectVirtual, finalDraw.virtual)
            assertEquals("Incomplete trace sample", 0L, capture.dropped)
            assertTrue("Echo must be actually drawn after submission", drawn.timeNanos >= ui.timeNanos)
            val report = JSONObject().put("launch", launch).put("mode", mode.id).put("phase", phase).put("iteration", index)
                .put("inputToDrawMs", (drawn.timeNanos - ui.timeNanos) / 1_000_000.0)
                .put("inputToVisibleCheckMs", (visibleCheckedAt - ui.timeNanos) / 1_000_000.0)
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
            Log.i("TerminalPipelineTest", "PIPELINE_SAMPLE $report")
        }
    }

    private fun outputHeight(): Int = compose.onNodeWithTag("terminal-trace-output").fetchSemanticsNode().size.height

    private fun waitVisible(marker: String, exact: Boolean = true) {
        try {
            compose.waitUntil(20_000) {
                val viewport = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag,
                    "terminal-trace-output")).fetchSemanticsNodes().singleOrNull() ?: return@waitUntil false
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
        val viewport = compose.onNodeWithTag("terminal-trace-output").fetchSemanticsNode()
        val top = node.positionInRoot.y - viewport.positionInRoot.y
        assertTrue("Actual echo row outside viewport: top=$top height=${node.size.height} viewport=${viewport.size.height}",
            top >= -1f && top + node.size.height <= viewport.size.height + 1f)
    }
}
