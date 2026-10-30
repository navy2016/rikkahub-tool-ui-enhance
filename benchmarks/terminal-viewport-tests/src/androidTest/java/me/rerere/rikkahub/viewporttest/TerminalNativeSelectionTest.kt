package me.rerere.rikkahub.viewporttest

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Observational tests only: a completed trace does not assert that the original failing SEL now works. */
class TerminalNativeSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(240)
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun proof(text: String?): JSONObject = JSONObject().put("length", text?.length ?: JSONObject.NULL)
        .put("sha256", text?.let { sha(it.toByteArray()) } ?: JSONObject.NULL)

    private class Capture(val name: String) {
        val events = JSONArray()
        var overflow = 0
        @Synchronized fun record(stage: String, fields: Map<String, Any?> = emptyMap()) {
            if (events.length() >= 8192) { overflow++; return }
            events.put(JSONObject(fields).put("stage", stage).put("sequence", events.length())
                .put("timeNs", SystemClock.elapsedRealtimeNanos()))
        }
    }

    private fun finish(capture: Capture, result: JSONObject) {
        val i = InstrumentationRegistry.getInstrumentation()
        val payload = JSONObject().put("suite", "terminal-native-selection-diagnostic-v1").put("case", capture.name)
            .put("acceptance", false).put("overflow", capture.overflow).put("pid", Process.myPid())
            .put("fingerprint", Build.FINGERPRINT)
            .put("targetApk", sha(File(i.targetContext.applicationInfo.sourceDir).readBytes()))
            .put("testApk", sha(File(i.context.applicationInfo.sourceDir).readBytes()))
            .put("result", result).put("events", capture.events).toString().toByteArray()
        val packed = ByteArrayOutputStream()
        DeflaterOutputStream(packed).use { it.write(payload) }
        val chunks = Base64.encodeToString(packed.toByteArray(), Base64.NO_WRAP).chunked(2600)
        chunks.forEachIndexed { index, part ->
            Log.i("TerminalNativeSelection", "NATIVE_SELECTION_PART " + JSONObject().put("case", capture.name)
                .put("part", index + 1).put("parts", chunks.size).put("sha256", sha(payload)).put("data", part))
        }
        assertEquals("Diagnostic trace overflow is not complete evidence", 0, capture.overflow)
    }

    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            val state = f.controller.state.value
            state.initialized && state.gesture == null && state.scrollEffect == null && f.activeWriters == 0 &&
                if (f.mode.isFullVirtual) f.observation()?.ready == true else f.bound.binding.eagerGeometry() != null
        }
        compose.waitForIdle()
    }

    private fun terminal() = TerminalEmulator(80, 24, 10000).apply {
        feed((0 until 10023).joinToString("\r\n") { "word$it 中文 e\u0301 👩‍💻" } + "\r\n")
    }

    private fun node(text: androidx.compose.ui.text.AnnotatedString) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(text)), true)

    private fun state(f: Fixture): Map<String, Any?> {
        val top = f.top()
        return mapOf("virtual" to f.isVirtual, "topId" to top.anchor.lineId, "clip" to top.anchor.clippedTopPx,
            "firstIndex" to if (f.isVirtual) f.lazy.firstVisibleItemIndex else f.frame.historyLineIds.indexOf(top.anchor.lineId),
            "firstOffset" to if (f.isVirtual) f.lazy.firstVisibleItemScrollOffset else f.eager.value,
            "writers" to f.activeWriters, "maxWriters" to f.maximumWriters,
            "effect" to f.controller.state.value.scrollEffect?.id, "gesture" to f.controller.state.value.gesture?.id)
    }

    private fun handles(): List<SemanticsNode> = compose.onAllNodes(SemanticsMatcher("selection handles") { n ->
        n.config.any { it.key.name == "SelectionHandleInfo" } ||
            n.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("terminal-selection-")
    }, true).fetchSemanticsNodes()

    private fun handleDescription(n: SemanticsNode): Map<String, Any?> = mapOf(
        "screenX" to n.positionOnScreen.x, "screenY" to n.positionOnScreen.y,
        "windowX" to n.positionInWindow.x, "windowY" to n.positionInWindow.y,
        "width" to n.size.width, "height" to n.size.height,
        "tag" to n.config.getOrElse(SemanticsProperties.TestTag) { "" },
        "legacy" to n.config.firstOrNull { it.key.name == "SelectionHandleInfo" }?.value?.toString())

    private fun selectionSemantics(c: Capture, phase: String) {
        val selected = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TextSelectionRange), true)
            .fetchSemanticsNodes().filter { !it.config[SemanticsProperties.TextSelectionRange].collapsed }
        c.record(phase, mapOf("selected" to selected.map { n ->
            val range = n.config[SemanticsProperties.TextSelectionRange]
            mapOf("start" to range.start, "end" to range.end,
                "text" to proof(n.config.getOrElse(SemanticsProperties.Text) { emptyList() }.singleOrNull()?.text))
        }, "handles" to handles().map(::handleDescription)))
    }

    private fun clickCopy(c: Capture): Boolean {
        compose.waitForIdle()
        val menu = device.wait(Until.findObject(By.text(compose.activity.getString(android.R.string.copy))), 5000)
        nativeGeometry(c,"before-copy")
        c.record("native-copy-found", mapOf("found" to (menu != null)))
        if (menu == null) return false
        menu.click()
        compose.waitForIdle()
        return true
    }

    private fun drag(c: Capture, from: Offset, to: Offset, f: Fixture) {
        val auto = compose.mainClock.autoAdvance
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        var position = from
        var released = false
        var delivered = 0
        fun inject(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, position.x, position.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)); delivered++ } finally { event.recycle() }
        }
        compose.mainClock.autoAdvance = false
        try {
            inject(MotionEvent.ACTION_DOWN)
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            repeat(220) { step ->
                position = from + (to - from) * ((step + 1) / 220f)
                inject(MotionEvent.ACTION_MOVE)
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                if (step % 20 == 0 || step == 219) c.record("driver-step", mapOf("move" to step + 1,
                    "screenX" to position.x, "screenY" to position.y,
                    "firstIndex" to f.lazy.firstVisibleItemIndex, "firstOffset" to f.lazy.firstVisibleItemScrollOffset,
                    "eagerOffset" to f.eager.value, "writers" to f.activeWriters,
                    "effect" to f.controller.state.value.scrollEffect?.id))
            }
            inject(MotionEvent.ACTION_UP); released = true
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        } finally {
            try { if (!released) inject(MotionEvent.ACTION_CANCEL) } finally { compose.mainClock.autoAdvance = auto }
        }
        c.record("driver-finished", mapOf("moves" to 220, "events" to delivered, "frames" to 222))
        compose.waitForIdle()
    }

    private fun nativeGeometry(c: Capture, phase: String) {
        fun rect(r: android.graphics.Rect) = listOf(r.left,r.top,r.right,r.bottom)
        val copy=device.findObject(By.text(compose.activity.getString(android.R.string.copy)))
        val all=device.findObject(By.text(compose.activity.getString(android.R.string.selectAll)))
        val grips=handles().map { h -> handleDescription(h)+mapOf(
            "centerX" to h.positionOnScreen.x+h.size.width/2f,"centerY" to h.positionOnScreen.y+h.size.height/2f) }
        val windows=InstrumentationRegistry.getInstrumentation().uiAutomation.windows.map { w ->
            val box=android.graphics.Rect();w.getBoundsInScreen(box)
            mapOf("type" to w.type,"layer" to w.layer,"focused" to w.isFocused,"active" to w.isActive,"bounds" to rect(box))
        }
        c.record("native-geometry",mapOf("phase" to phase,"copy" to copy?.visibleBounds?.let(::rect),
            "all" to all?.visibleBounds?.let(::rect),"handles" to grips,"windows" to windows))
    }

    @Test fun nativeSelectionTopDragAndTouchDispatch() = exercise(true)
    @Test fun nativeSelectionBottomMenuPlacement() = exercise(false)

    private fun exercise(dragging: Boolean) {
        var shown by mutableStateOf<Fixture?>(null, referentialEqualityPolicy())
        compose.setContent { shown?.Content() }
        for (mode in listOf(TerminalRenderMode.CHUNKED_LAYERS, TerminalRenderMode.FULL_VIRTUAL)) {
            val term = terminal(); val frame = term.renderFrame()
            val saved = TerminalViewportState(autoScroll = false, anchorLineId = frame.historyLineIds[500],
                anchorClippedTopPx = 0, anchorHistoryGeneration = frame.historyGeneration)
            val f = Fixture(stressSpans = false, terminalOverride = term, initialMode = mode, restored = saved,
                foreground = Color(0xFF00E676)).apply {
                tui = false; fontSp = 12; heightDp = 240; densityOverride = Density(1.33f, 1.3f)
                config = config.copy(selectionMode = true)
            }
            val c = Capture("${if (dragging) "top-drag" else "bottom-word"}-${mode.id}")
            val result = JSONObject().put("mode", mode.id).put("historyRows", frame.historyCount)
                .put("textSha256", sha(frame.rows.joinToString("\n") { it.text.text }.toByteArray()))
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            try {
                compose.runOnIdle { shown = f }; settle(f)
                compose.runOnIdle { f.bound.binding.onSelectionTrace = { stage, fields -> c.record(stage, fields) } }
                val view = compose.onNodeWithTag("item-output").fetchSemanticsNode()
                val firstRow = if (f.isVirtual) f.observation()!!.rows.first { it.topPx >= 0 &&
                    frame.rows[it.index].text.text.startsWith("word") }.index else {
                    val top = f.bound.binding.eagerGeometry()!!.capture(f.eager.value)
                    val index = (frame.historyLineIds + frame.screenLineIds).indexOf(top.anchor.lineId)
                    index + if (top.anchor.clippedTopPx > 0) 1 else 0
                }
                val rowIndex = if (dragging) firstRow else if (f.isVirtual) f.observation()!!.rows.last {
                    it.topPx >= 0 && it.bottomPx <= view.size.height && frame.rows[it.index].text.text.startsWith("word") }.index
                else {
                    var last=firstRow
                    for (index in firstRow until minOf(firstRow+80,frame.rows.size)) {
                        val candidate=node(frame.rows[index].text).fetchSemanticsNode()
                        if(candidate.positionInRoot.y+candidate.size.height>view.positionInRoot.y+view.size.height) break
                        last=index
                    }
                    last
                }
                val text = frame.rows[rowIndex].text
                val row = node(text); val n = row.fetchSemanticsNode(); val layouts = ArrayList<TextLayoutResult>()
                compose.runOnIdle { assertTrue(n.config[SemanticsActions.GetTextLayoutResult].action!!(layouts))
                    clipboard.setPrimaryClip(ClipData.newPlainText("selection-diagnostic", "probe-before-${c.name}")) }
                val layout = layouts.single(); val point = layout.getBoundingBox(3).center
                val range = layout.getWordBoundary(layout.getOffsetForPosition(point))
                val expected = text.text.substringBefore(' ')
                c.record("before", state(f) + mapOf("rowIndex" to rowIndex, "rowId" to (frame.historyLineIds + frame.screenLineIds)[rowIndex],
                    "rowScreenX" to n.positionOnScreen.x, "rowScreenY" to n.positionOnScreen.y,
                    "rowRootX" to n.positionInRoot.x, "rowRootY" to n.positionInRoot.y,
                    "touchX" to point.x, "touchY" to point.y, "expected" to proof(expected),
                    "wordFrom" to range.min, "wordTo" to range.max, "layoutWord" to proof(text.text.substring(range.min, range.max)),
                    "viewportScreenX" to view.positionOnScreen.x, "viewportScreenY" to view.positionOnScreen.y,
                    "viewportWidth" to view.size.width, "viewportHeight" to view.size.height))
                row.performTouchInput { longClick(point) }; compose.waitForIdle()
                selectionSemantics(c, "after-longpress")
                device.wait(Until.hasObject(By.text(compose.activity.getString(android.R.string.copy))),5000)
                nativeGeometry(c,"after-longpress")
                result.put("rowIndex", rowIndex).put("virtual", f.isVirtual)
                if (!dragging) {
                    result.put("copyMenu", clickCopy(c))
                    val actual = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    result.put("expectedWord", proof(expected)).put("actualClipboard", proof(actual)).put("wordMatches", actual == expected)
                    c.record("clipboard-observed", mapOf("expected" to proof(expected), "actual" to proof(actual), "matches" to (actual == expected)))
                } else {
                    val handle = handles().firstOrNull { h ->
                        h.config.getOrElse(SemanticsProperties.TestTag) { "" } == "terminal-selection-end" ||
                            h.config.any { it.key.name == "SelectionHandleInfo" && it.value.toString().contains("SelectionEnd") }
                    }
                    result.put("handleFound", handle != null)
                    if (handle != null) {
                        val from = handle.positionOnScreen + Offset(handle.size.width / 2f, handle.size.height / 2f)
                        val to = view.positionOnScreen + Offset(view.size.width / 2f, view.size.height + 20f)
                        c.record("handle-injection", handleDescription(handle) + mapOf("fromX" to from.x, "fromY" to from.y,
                            "toX" to to.x, "toY" to to.y))
                        drag(c, from, to, f); settle(f)
                        c.record("after-drag", state(f)); selectionSemantics(c, "after-drag-selection")
                        nativeGeometry(c,"after-drag")
                        val firstId = f.top().anchor.lineId
                        result.put("startRecycled", (frame.historyLineIds + frame.screenLineIds).indexOf(firstId) > rowIndex)
                        result.put("copyMenu", clickCopy(c))
                        result.put("actualClipboard", proof(clipboard.primaryClip?.getItemAt(0)?.text?.toString()))
                    }
                }
                result.put("finished", true)
            } catch (error: Throwable) {
                result.put("finished", false).put("error", error.toString().take(400))
                c.record("exception", mapOf("error" to error.toString().take(400)))
                throw error
            } finally {
                compose.runOnIdle { shown = null }; compose.waitForIdle()
                result.put("retainedRows", f.measurements.retainedRows).put("pointerRows", f.pointerMap.retainedRows)
                c.record("disposed", mapOf("retainedRows" to f.measurements.retainedRows, "pointerRows" to f.pointerMap.retainedRows))
                f.bound.binding.onSelectionTrace = null
                finish(c, result)
                assertEquals(0, f.measurements.retainedRows)
            }
        }
    }
}
