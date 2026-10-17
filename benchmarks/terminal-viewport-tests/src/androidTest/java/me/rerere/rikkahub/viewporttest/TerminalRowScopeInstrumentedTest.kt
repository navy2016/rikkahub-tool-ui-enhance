package me.rerere.rikkahub.viewporttest

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.container.TerminalEagerViewportGeometry
import me.rerere.rikkahub.ui.pages.container.LocalTerminalRowDecorator
import me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements
import me.rerere.rikkahub.ui.pages.container.TerminalRenderSnapshot
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedRowState
import me.rerere.rikkahub.ui.pages.container.TerminalRenderedTranscript
import me.rerere.rikkahub.ui.pages.container.TerminalTranscriptCompositionObserver
import me.rerere.rikkahub.ui.pages.container.createTerminalRenderedRows
import me.rerere.rikkahub.ui.pages.container.rememberTerminalLazyLayoutPass
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.ui.pages.container.TerminalLazyLayoutPass
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.security.MessageDigest

/** Same default eager row tree, node and geometry; only the measured row's restart scope differs. */
class TerminalRowScopeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(240)

    private val baseStyle = TextStyle(color = Color.Green, fontFamily = JetbrainsMono,
        fontSize = 14.sp, lineHeight = 14.sp, platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

    private fun frame(size: Int): TerminalEmulator.RenderFrame = TerminalEmulator(80, 24, size).apply {
        feed("\u001b[?25l" + (0 until size + 24).joinToString("\r\n") {
            "line-$it \u001b[1;32m中文\u001b[0m e\u0301🙂"
        })
    }.renderFrame().also { assertEquals(size, it.historyCount) }

    private class Counts : TerminalTranscriptCompositionObserver {
        var entries = 0
        var chunks = 0
        var historyRows = 0
        var screens = 0
        override fun transcriptComposed(cached: Boolean) { entries++ }
        override fun historyChunkDelta(chunks: Int, rows: Int) { this.chunks += chunks; historyRows += rows }
        override fun activeScreenDelta(screens: Int) { this.screens += screens }
    }

    private class Mount(initial: TerminalEmulator.RenderFrame, val legacy: Boolean, style: TextStyle,
        val measured: Boolean = true) {
        val rows = createTerminalRenderedRows(initial)
        var source by mutableStateOf(TerminalRenderSnapshot.capture(initial, rows), referentialEqualityPolicy())
        val counts = Counts()
        val rowCommits = HashMap<Long, Int>()
        val measurements = TerminalLazyItemMeasurements().apply {
            onRowComposed = { id -> rowCommits[id] = (rowCommits[id] ?: 0) + 1 }
        }
        val eager = ScrollState(Int.MAX_VALUE)
        val horizontal = ScrollState(0)
        var style by mutableStateOf(style)
        var direction by mutableStateOf(LayoutDirection.Ltr)
        var density by mutableStateOf(Density(1f))
        var drawPulse by mutableIntStateOf(0)
        @Volatile var draws = 0L
        var committed = false
        lateinit var pass: TerminalLazyLayoutPass

        @Composable fun Content() {
            val current = source
            CompositionLocalProvider(LocalDensity provides density, LocalLayoutDirection provides direction) {
                MaterialTheme {
                    pass = rememberTerminalLazyLayoutPass(current.frame, style)
                    val metric = pass.metricKey
                    val decorator: @Composable (TerminalRenderedRowState, TextStyle) -> Unit = remember(metric) {
                        { row, textStyle ->
                            if (legacy) LegacyMeasuredTerminalRow(measurements, metric, row, textStyle)
                            else measurements.Row(metric, row, textStyle)
                        }
                    }
                    val chunks = remember(current.frame.historyCount, current.frame.historyStartSequence, measured) {
                        terminalHistoryChunks(current.frame.historyCount, current.frame.historyStartSequence,
                            usesTuiViewport = !measured)
                    }
                    SideEffect { committed = true }
                    Box(Modifier.size(320.dp, 240.dp).testTag("row-scope-output")) {
                        Column(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(eager)
                            .drawWithContent { drawPulse; drawContent(); draws++ }) {
                            CompositionLocalProvider(LocalTerminalRowDecorator provides decorator.takeIf { measured }) {
                                TerminalRenderedTranscript(current.rows, style, chunks, isolateChunkDrawing = measured,
                                    observer = counts, rowSource = current)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    /** Exact measured prefix plus two new row-tree draws; no fixed sleep, guessed height or scrolling repair. */
    private fun settled(mount: Mount, requireTail: Boolean = true): TerminalEagerViewportGeometry? {
        compose.waitForIdle()
        val geometry = compose.runOnIdle {
            assertTrue(mount.committed)
            if (requireTail) assertEquals(mount.eager.maxValue, mount.eager.value)
            if (mount.measured) requireNotNull(mount.measurements.readEager(mount.pass)) else null
        }
        val afterDraw = compose.runOnIdle { mount.draws }
        compose.waitUntil(30_000) {
            if (mount.draws >= afterDraw + 2) true else {
                compose.runOnUiThread { mount.drawPulse++ }
                false
            }
        }
        compose.runOnIdle {
            if (mount.measured) assertSame(geometry, mount.measurements.peekEager(mount.pass))
            if (requireTail) assertEquals(mount.eager.maxValue, mount.eager.value)
        }
        return geometry
    }

    private fun assertTail(mount: Mount) {
        val expected = compose.runOnIdle { mount.rows.last().text }
        val text = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(expected)),
            useUnmergedTree = true).fetchSemanticsNode()
        val viewport = compose.onNodeWithTag("row-scope-output").fetchSemanticsNode()
        val top = text.positionInRoot.y - viewport.positionInRoot.y
        assertTrue("Last actual Text is clipped", top >= -1 && top + text.size.height <= viewport.size.height + 1)
    }

    private fun release(mount: Mount, clear: () -> Unit) {
        compose.runOnIdle { clear() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, mount.measurements.retainedRows)
            assertEquals(0, mount.measurements.retainedEagerHistoryRows)
            assertEquals(0, mount.counts.chunks)
            assertEquals(0, mount.counts.screens)
        }
    }

    private fun cold(size: Int) {
        val initial = frame(size) // Shared immutable source and font; model seeding is not timed.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun apkHash(context: android.content.Context): String {
            check(context.applicationInfo.splitSourceDirs.isNullOrEmpty())
            val digest = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        Log.i("TerminalRowScope", "ROW_SCOPE_DEVICE " + JSONObject().put("size", size)
            .put("pid", android.os.Process.myPid()).put("fingerprint", android.os.Build.FINGERPRINT)
            .put("sdk", android.os.Build.VERSION.SDK_INT).put("density", 1).put("fontScale", 1)
            .put("targetPackage", instrumentation.targetContext.packageName)
            .put("testPackage", instrumentation.context.packageName)
            .put("targetApkSha256", apkHash(instrumentation.targetContext))
            .put("testApkSha256", apkHash(instrumentation.context)))
        var shown by mutableStateOf<Mount?>(null, referentialEqualityPolicy())
        compose.setContent { shown?.let { key(it) { it.Content() } } }
        var sequence = 0
        for (phase in listOf("warmup", "measurement")) {
            repeat(if (phase == "warmup") 1 else 4) { pair ->
                val order = if (pair % 2 == 0) listOf(true, false) else listOf(false, true)
                val shapes = mutableListOf<String>()
                for ((position, legacy) in order.withIndex()) {
                    val mount = Mount(initial, legacy, baseStyle) // State allocation is also outside timing.
                    var started = 0L
                    compose.runOnIdle { started = SystemClock.elapsedRealtimeNanos(); shown = mount }
                    val geometry = requireNotNull(settled(mount))
                    val elapsed = SystemClock.elapsedRealtimeNanos() - started
                    assertTail(mount) // Expensive semantics walk is outside the paired timer.
                    val shape = compose.runOnIdle {
                        assertEquals(size + 24, mount.measurements.retainedRows)
                        assertEquals((size + 24).toLong(), mount.measurements.createdRowNodes)
                        assertEquals(size, mount.counts.historyRows)
                        assertEquals(1, mount.counts.screens)
                        assertEquals(initial.historyLineIds.toSet() + initial.screenLineIds, mount.rowCommits.keys)
                        val heights = (0 until size + 24).joinToString(",") { geometry.height(it).toString() }
                        val signature = "$heights|${mount.eager.maxValue}|${mount.horizontal.maxValue}"
                        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                    }
                    shapes.add(shape)
                    val commits = mount.rowCommits.values.sum()
                    val nodes = mount.measurements.createdRowNodes
                    val retainedRows = mount.measurements.retainedRows
                    val historyChunks = mount.counts.chunks
                    val draws = mount.draws
                    release(mount) { shown = null }
                    Log.i("TerminalRowScope", "ROW_SCOPE_COLD " + JSONObject()
                        .put("size", size).put("phase", phase).put("pair", pair).put("position", position)
                        .put("arm", if (legacy) "legacy" else "single").put("sequence", sequence++)
                        .put("elapsedNanos", elapsed).put("rowCommits", commits).put("nodes", nodes)
                        .put("retainedRows", retainedRows).put("disposedRows", mount.measurements.retainedRows)
                        .put("heightSha256", shape).put("heightPx", geometry.contentHeightPx)
                        .put("historyChunks", historyChunks).put("draws", draws))
                }
                assertEquals("Both arms must retain identical natural geometry and scroll ranges", shapes[0], shapes[1])
            }
        }
    }

    @Test fun rowScopeColdOneThousandPairedMounts() = cold(1000)
    @Test fun rowScopeColdFiveThousandPairedMounts() = cold(5000)
    @Test fun rowScopeColdTenThousandPairedMounts() = cold(10000)

    @Test fun rowScopeActiveTextAndAbortedSnapshotKeepRowLocalSubscription() {
        val mount = Mount(frame(260), false, baseStyle)
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) mount.Content() }
        settled(mount)
        val index = 150
        val row = mount.rows[index]
        val entries = mount.counts.entries
        val before = mount.rowCommits.toMap()
        val old = row.text
        val aborted = Snapshot.takeMutableSnapshot()
        try { aborted.enter { row.text = AnnotatedString("uncommitted") } } finally { aborted.dispose() }
        compose.waitForIdle()
        assertEquals(old, row.text)
        assertEquals(before, mount.rowCommits)
        val next = buildAnnotatedString {
            append("changed 中文 e\u0301🙂")
            addStyle(SpanStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold), 0, 7)
        }
        compose.runOnIdle { row.text = next }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(entries, mount.counts.entries)
            for ((id, count) in before) assertEquals(if (id == row.lineId) count + 1 else count, mount.rowCommits[id])
            assertNull(mount.measurements.readEager(mount.pass)) // Old frame cannot accept new text's height.
            val current = mount.source.frame
            val changed = current.copy(rows = current.rows.mapIndexed { i, value ->
                if (i == index) TerminalEmulator.RenderedRow(next) else value
            })
            mount.source = TerminalRenderSnapshot.capture(changed, mount.rows)
        }
        // No follow controller lives in this row-tree fixture. A taller historical row does not
        // entitle it to repair the ScrollState; production tail behavior has dedicated regressions.
        val geometry = requireNotNull(settled(mount, requireTail = false))
        assertTrue(geometry.height(index) > geometry.height(index - 1))
        release(mount) { shown = false }
    }

    private data class RowGeometry(val text: AnnotatedString, val x: Float, val y: Float,
        val width: Int, val height: Int, val firstBaseline: Float, val lastBaseline: Float)

    private fun geometry(): List<RowGeometry> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true,
    ).fetchSemanticsNodes().map { node ->
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
        val result = layouts.single()
        RowGeometry(node.config[SemanticsProperties.Text].single(), node.positionInRoot.x, node.positionInRoot.y,
            node.size.width, node.size.height, result.firstBaseline, result.lastBaseline)
    }

    @Test fun rowScopeBothPathsMatchStyledGeometryAtFontScaleAndRtl() {
        val plain = frame(32)
        val initial = plain.copy(rows = plain.rows.mapIndexed { i, row ->
            if (i % 7 != 0) row else TerminalEmulator.RenderedRow(buildAnnotatedString {
                append("العربية e\u0301 👩‍💻 中 $i")
                addStyle(SpanStyle(fontSize = 23.sp, fontStyle = FontStyle.Italic), 0, 4)
            })
        })
        var shown by mutableStateOf<Mount?>(null, referentialEqualityPolicy())
        compose.setContent { shown?.let { key(it) { it.Content() } } }
        for (direction in LayoutDirection.entries) for (scale in listOf(1f, 1.3f)) {
            var original: List<RowGeometry>? = null
            for (legacy in listOf(true, false)) {
                val mount = Mount(initial, legacy, baseStyle.copy(fontSize = 17.sp)).apply {
                    this.direction = direction
                    density = Density(1.33f, scale)
                }
                compose.runOnIdle { shown = mount }
                settled(mount)
                val measured = geometry()
                assertEquals(initial.rows.map { it.text }, measured.map { it.text })
                if (legacy) original = measured else assertEquals(original, measured)
                assertTail(mount)
                release(mount) { shown = null }
            }
        }
    }

    @Test fun rowScopeUnmeasuredTuiRowDoesNotInvalidateTranscriptParent() {
        val mount = Mount(frame(1), false, baseStyle, measured = false)
        var shown by mutableStateOf(true)
        compose.setContent { if (shown) mount.Content() }
        settled(mount)
        val entries = mount.counts.entries
        repeat(8) { i ->
            val next = AnnotatedString("active-$i 中文")
            compose.runOnIdle { mount.rows.last().text = next }
            compose.waitForIdle()
            assertTail(mount)
            compose.runOnIdle {
                assertEquals(entries, mount.counts.entries)
                assertEquals(0, mount.measurements.retainedRows)
            }
        }
        release(mount) { shown = false }
    }
}
