package me.rerere.rikkahub.viewporttest

import android.content.ClipboardManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import java.security.MessageDigest
import java.io.File
import me.rerere.rikkahub.data.container.TerminalItemScrollTarget
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.ViewportMode
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import me.rerere.rikkahub.utils.TerminalEmulator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Exact production row-item surface and legacy controls. Source seeding is excluded, cold widths are NOT. */
class TerminalFullVirtualInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(240)
    private fun terminal(size: Int = 1000) = TerminalEmulator(80,24,size).apply {
        feed((0 until size+23).joinToString("\r\n") { "word$it 中文 e\u0301 👩‍💻" } + "\r\n")
    }
    private fun fixture(size: Int=1000, tui: Boolean=true, systemIme: Boolean=false,
        terminal: TerminalEmulator=terminal(size), mode: TerminalRenderMode=TerminalRenderMode.FULL_VIRTUAL,
        saved: TerminalViewportState=TerminalViewportState(autoScroll=true), stress: Boolean=false) =
        Fixture(stressSpans=stress,systemIme=systemIme,initialMode=mode,terminalOverride=terminal,restored=saved,
            foreground=Color(0xFF00E676)).apply {
            this.tui=tui; fontSp=12; heightDp=240; densityOverride=Density(1.33f,1.3f)
        }
    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            val p=f.controller.state.value
            p.initialized && p.gesture==null && p.scrollEffect==null && f.activeWriters==0 &&
                f.bound.pass.frame===f.frame && (if(f.mode.isFullVirtual) f.observation()?.ready==true else
                    f.measurements.peekEager(f.bound.pass)?.frame===f.frame)
        }
        compose.waitForIdle()
        assertTrue(f.maximumWriters<=1)
        if(f.mode.isFullVirtual) {
            assertTrue(f.isVirtual);assertTrue(f.bound.binding.fullVirtual)
            assertTrue("Whole history was materialized",f.rowStore.retainedRowStates<=80)
            assertTrue("Offscreen layout retained",f.measurements.retainedRows<=128)
            assertEquals(0,f.measurements.retainedEagerHistoryRows)
        }
    }
    private fun record(f: Fixture, case: String, phase: String, cursor: Boolean=true) {
        val state=f.controller.state.value
        var top=0f; var bottom=0f;var rowHeight=0
        val viewport=compose.onNodeWithTag("item-output").fetchSemanticsNode()
        if(cursor) {
            val target=f.frame.historyCount+f.frame.cursorRow
            val text=f.frame.rows[target].text
            val nodes=compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text,listOf(text)),true).fetchSemanticsNodes()
            val row=nodes.single()
            top=row.positionInRoot.y-viewport.positionInRoot.y;rowHeight=row.size.height;bottom=top+rowHeight
            assertTrue("Cursor clipped $case/$phase",top>=-1 && bottom<=viewport.size.height+1)
        }
        Log.i("TerminalFullVirtual","FULL_VIRTUAL_RECT "+JSONObject().put("case",case).put("phase",phase)
            .put("mode",f.mode.id).put("virtual",f.isVirtual).put("tui",f.tui||f.frame.isAlternateScreen||f.fullGrid)
            .put("ime",f.ime).put("selection",f.config.selectionMode).put("pan",f.config.panEnabled)
            .put("alternate",f.frame.isAlternateScreen).put("historyRows",f.frame.historyCount)
            .put("screenRows",f.frame.screenLineIds.size).put("rowStates",f.rowStore.retainedRowStates)
            .put("measuredRows",f.measurements.retainedRows).put("writers",f.maximumWriters)
            .put("viewportMode",state.mode.name).put("cursorChecked",cursor)
            .put("top",top.toDouble()).put("bottom",bottom.toDouble()).put("rowHeight",rowHeight).put("viewport",viewport.size.height))
    }
    private fun done(name: String) = Log.i("TerminalFullVirtual","FULL_VIRTUAL_CASE "+JSONObject().put("case",name).put("passed",true))

    private fun clickSystemAction(device: UiDevice, label: String) {
        // Native UiAutomator waits do not advance the Compose test clock. Flush the completed
        // gesture/selection state before asking Android's native floating action menu for an item.
        compose.waitForIdle()
        val item=device.wait(Until.findObject(By.text(label)),5000)
        if(item==null) {
            val out=java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(out)
            Log.e("TerminalFullVirtual","FULL_VIRTUAL_MENU_FAILURE "+out.toString("UTF-8").take(12000))
        }
        assertNotNull("System action '$label' missing after completed Compose gesture",item)
        checkNotNull(item).click()
        compose.waitForIdle()
    }

    private fun selectionDevice(case: String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        Log.i("TerminalFullVirtual","FULL_VIRTUAL_SELECTION_DEVICE "+JSONObject().put("case",case)
            .put("pid",Process.myPid()).put("fingerprint",Build.FINGERPRINT)
            .put("targetApk",sha256(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes()))
            .put("testApk",sha256(File(instrumentation.context.applicationInfo.sourceDir).readBytes())))
    }

    private fun dragWithFrames(from: Offset, to: Offset) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val prior=compose.mainClock.autoAdvance
        val downTime=SystemClock.uptimeMillis()
        var current=from
        var finished=false
        var delivered=0
        fun event(action: Int, point: Offset) {
            val input=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,point.x,point.y,0)
            input.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Platform selection touch rejected",automation.injectInputEvent(input,true));delivered++ }
            finally { input.recycle() }
        }
        compose.mainClock.autoAdvance=false
        try {
            event(MotionEvent.ACTION_DOWN,from)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle() // autoAdvance=false: wait for Android draw, not an endless held-drag loop.
            repeat(220) { step ->
                current=from+(to-from)*((step+1)/220f)
                event(MotionEvent.ACTION_MOVE,current)
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
            }
            event(MotionEvent.ACTION_UP,current)
            finished=true
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        } finally {
            try { if(!finished) event(MotionEvent.ACTION_CANCEL,current) }
            finally { compose.mainClock.autoAdvance=prior }
        }
        Log.i("TerminalFullVirtual","FULL_VIRTUAL_HANDLE_FRAMES "+JSONObject()
            .put("moves",220).put("events",delivered).put("frames",222).put("screenCoordinates",true))
        compose.waitForIdle()
    }

    @Test fun fullVirtualAllScenesRemainVirtualWithRealGeometry() {
        var shown by mutableStateOf<Fixture?>(null,referentialEqualityPolicy())
        compose.setContent{shown?.Content()}
        for(size in listOf(1000,5000,10000)) for(tui in listOf(false,true)) {
            val f=fixture(size,tui);compose.runOnIdle{shown=f};settle(f)
            val name="$size-${if(tui) "tui" else "ordinary"}"
            record(f,name,"initial")
            val stream=compose.runOnIdle{f.stream(24,2,trailingNewline=true){step->f.ime=step in 8..15;f.heightDp=if(f.ime)140 else 240}}
            compose.waitUntil(45_000){stream.isCompleted};assertFalse(stream.isCancelled);settle(f)
            assertEquals(size,f.frame.historyCount);record(f,name,"stream24")
            compose.runOnIdle{f.config=f.config.copy(selectionMode=true)};settle(f);record(f,name,"sel")
            compose.runOnIdle{f.config=f.config.copy(selectionMode=false,panEnabled=false)};settle(f);record(f,name,"mouse")
            compose.runOnIdle{f.fullGrid=true};settle(f);record(f,name,"fullGrid")
            compose.runOnIdle{f.terminal.feed("\u001b[?1049hALT\r\n");f.publish()};settle(f);record(f,name,"alt",false)
            assertEquals(0,f.frame.historyCount)
            compose.runOnIdle{f.terminal.feed("\u001b[?1049l");f.fullGrid=false;f.config=f.config.copy(panEnabled=true);f.publish()}
            settle(f);record(f,name,"main")
            compose.runOnIdle{shown=null};compose.waitForIdle()
            assertEquals(0,f.measurements.retainedRows);assertEquals(0,f.pointerMap.retainedRows)
        }
        done("scenes")
    }

    @Test fun fullVirtualLockedFontsRtlImeAndReentryPreserveTheStableAnchor() {
        val terminal=terminal(10000);val initial=terminal.renderFrame()
        val saved=TerminalViewportState(autoScroll=false,anchorLineId=initial.historyLineIds[6000],
            anchorClippedTopPx=5,anchorHistoryGeneration=initial.historyGeneration)
        val f=fixture(10000,systemIme=true,terminal=terminal,saved=saved,stress=true)
        var shown by mutableStateOf<Fixture?>(f,referentialEqualityPolicy());compose.setContent{shown?.Content()};settle(f)
        val original=compose.runOnIdle{f.top()}
        for(size in listOf(19,12)) {
            compose.runOnIdle{f.fontSp=size;f.rtl=!f.rtl};settle(f)
            assertEquals(original.anchor.lineId,f.top().anchor.lineId)
        }
        compose.runOnUiThread{f.inputFocus.requestFocus();f.keyboard?.show()}
        compose.waitUntil(10_000){f.ime};settle(f)
        assertEquals(original.anchor.lineId,f.top().anchor.lineId);record(f,"locked","ime",false)
        compose.runOnUiThread{f.keyboard?.hide()};compose.waitUntil(10_000){!f.ime};settle(f)
        val state=compose.runOnIdle{f.snapshotViewport()}
        compose.runOnIdle{shown=null};compose.waitForIdle();assertEquals(0,f.measurements.retainedRows)
        terminal.feed((0..7).joinToString("\r\n",postfix="\r\n"){"detached-$it"})
        val remount=fixture(10000,terminal=terminal,saved=state,stress=true)
        compose.runOnIdle{shown=remount};settle(remount)
        assertEquals(original.anchor.lineId,remount.top().anchor.lineId);record(remount,"locked","remount",false)
        compose.runOnIdle{shown=null};compose.waitForIdle();assertEquals(0,remount.measurements.retainedRows)
        done("anchors")
    }

    @Test fun fullVirtualSelectionSystemMenuCopiesAWordAndOffscreenSelectAll() {
        selectionDevice("copy")
        val f=fixture(10000,tui=false).apply{config=config.copy(selectionMode=true)}
        compose.setContent{f.Content()};settle(f)
        val clipboard=compose.activity.getSystemService(ClipboardManager::class.java)
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val expected=f.frame.rows.joinToString("\n"){it.text.text}
        val visible=f.observation()!!.rows.first{it.topPx>=0 && f.frame.rows[it.index].text.text.startsWith("word")}
        val text=f.frame.rows[visible.index].text
        val row=compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text,listOf(text)),true)
        val layouts=ArrayList<TextLayoutResult>()
        val node=row.fetchSemanticsNode()
        compose.runOnIdle{assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!(layouts))}
        row.performTouchInput{longClick(layouts.single().getBoundingBox(3).center)}
        val copy=compose.activity.getString(android.R.string.copy)
        val all=compose.activity.getString(android.R.string.selectAll)
        clickSystemAction(device,copy)
        compose.waitUntil(5000){clipboard.primaryClip?.getItemAt(0)?.text?.toString()==text.text.substringBefore(' ')}
        row.performTouchInput{longClick(layouts.single().getBoundingBox(3).center)}
        clickSystemAction(device,all)
        clickSystemAction(device,copy)
        compose.waitUntil(5000){clipboard.primaryClip?.getItemAt(0)?.text?.toString()==expected}
        assertTrue(f.isVirtual);assertTrue(f.measurements.retainedRows<=128)
        Log.i("TerminalFullVirtual","FULL_VIRTUAL_COPY "+JSONObject().put("allRows",f.frame.rows.size)
            .put("bytes",expected.toByteArray().size).put("sha256",MessageDigest.getInstance("SHA-256").digest(expected.toByteArray()).joinToString(""){"%02x".format(it)})
            .put("word",true).put("selectAll",true).put("virtual",f.isVirtual).put("measuredRows",f.measurements.retainedRows))
        done("copy")
    }

    @Test fun fullVirtualSelectionHandleCrossesViewportWithoutLosingOffscreenText() {
        selectionDevice("drag")
        val terminal=terminal(10000);val frame=terminal.renderFrame()
        val saved=TerminalViewportState(autoScroll=false,anchorLineId=frame.historyLineIds[500],
            anchorClippedTopPx=0,anchorHistoryGeneration=frame.historyGeneration)
        val f=fixture(10000,tui=false,terminal=terminal,saved=saved).apply{config=config.copy(selectionMode=true)}
        var shown by mutableStateOf(true)
        compose.setContent{if(shown)f.Content()};settle(f)
        val startIndex=f.observation()!!.rows.first{it.topPx>=0}.index
        val text=frame.rows[startIndex].text
        val row=compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text,listOf(text)),true)
        val results=ArrayList<TextLayoutResult>();val node=row.fetchSemanticsNode()
        compose.runOnIdle{node.config[SemanticsActions.GetTextLayoutResult].action!!(results)}
        row.performTouchInput{longClick(results.single().getBoundingBox(3).center)}
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitForIdle()
        val handle=compose.onNodeWithTag("terminal-selection-end").fetchSemanticsNode()
        val view=compose.onNodeWithTag("item-output").fetchSemanticsNode()
        val from=handle.positionOnScreen+Offset(handle.size.width/2f,handle.size.height/2f)
        val to=view.positionOnScreen+Offset(view.size.width/2f,view.size.height+20f)
        Log.i("TerminalFullVirtual","FULL_VIRTUAL_HANDLE_INPUT "+JSONObject()
            .put("screenStartX",from.x.toDouble()).put("screenStartY",from.y.toDouble())
            .put("screenEndX",to.x.toDouble()).put("screenEndY",to.y.toDouble())
            .put("popupWindowX",handle.boundsInWindow.center.x.toDouble())
            .put("popupWindowY",handle.boundsInWindow.center.y.toDouble()))
        dragWithFrames(from,to)
        compose.waitForIdle()
        compose.waitUntil(10_000){f.controller.state.value.scrollEffect==null && f.activeWriters==0}
        val last=f.observation()!!.rows.last{it.topPx<f.viewportHeight}
        assertTrue("Drag never recycled the start row",f.observation()!!.rows.first().index>startIndex)
        val selected=compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TextSelectionRange),true)
            .fetchSemanticsNodes().mapNotNull { n ->
                val range=n.config[SemanticsProperties.TextSelectionRange]
                val value=n.config.getOrElse(SemanticsProperties.Text){emptyList()}.singleOrNull()
                if(range.collapsed || value==null) null else {
                    val index=(startIndex..last.index).firstOrNull{frame.rows[it].text==value}
                    index?.let{it to range}
                }
            }
        val end=selected.maxBy{it.first}
        assertTrue(end.first>startIndex)
        val expected=(startIndex..end.first).joinToString("\n") { index ->
            val value=frame.rows[index].text.text
            if(index==end.first)value.substring(0,end.second.max) else value
        }
        val copy=compose.activity.getString(android.R.string.copy)
        clickSystemAction(device,copy)
        val clipboard=compose.activity.getSystemService(ClipboardManager::class.java)
        compose.waitUntil(5000){clipboard.primaryClip?.getItemAt(0)?.text?.toString()==expected}
        assertTrue(f.isVirtual);assertTrue(f.measurements.retainedRows<=128)
        Log.i("TerminalFullVirtual","FULL_VIRTUAL_DRAG "+JSONObject().put("startRow",startIndex)
            .put("endRow",end.first).put("firstVisible",f.observation()!!.rows.first().index)
            .put("bytes",expected.toByteArray().size).put("sha256",sha256(expected.toByteArray()))
            .put("virtual",true).put("measuredRows",f.measurements.retainedRows).put("writers",f.maximumWriters))
        compose.runOnIdle{shown=false};compose.waitForIdle();assertEquals(0,f.measurements.retainedRows)
        done("drag")
    }

    @Test fun fullVirtualMouseMappingIncludesOffscreenPhysicalOrigin() {
        val f=fixture().apply{config=config.copy(panEnabled=false);fullGrid=true;heightDp=100}
        compose.setContent{f.Content()};settle(f)
        val obs=f.observation()!!
        val row=obs.rows.first{it.index>=f.frame.historyCount && it.topPx>=0 && it.bottomPx<=obs.viewportHeightPx}
        val text=f.frame.rows[row.index].text
        val node=compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Text,listOf(text)),true).fetchSemanticsNode()
        val view=compose.onNodeWithTag("item-output").fetchSemanticsNode()
        val local=Offset(node.positionInRoot.x-view.positionInRoot.x+1,node.positionInRoot.y-view.positionInRoot.y+2)
        val hit=checkNotNull(f.pointerMap.hit(local.x,local.y))
        assertEquals(row.index-f.frame.historyCount,hit.row);assertEquals(0,hit.column)
        assertEquals(f.bound.binding.physicalRows!!.top(hit.row)+2,hit.pixelY)
        assertNull(f.pointerMap.hit(-1f,local.y));assertNull(f.pointerMap.hit(local.x,-1f))
        assertTrue(f.pointerMap.retainedRows<24);record(f,"pointer","offscreen-origin",false)
        done("mouse")
    }

    @Test fun fullVirtualModeSwitchKeepsOldChoicesAndMeasuredReadPosition() {
        val terminal=terminal();val frame=terminal.renderFrame()
        val saved=TerminalViewportState(autoScroll=false,anchorLineId=frame.historyLineIds[500],
            anchorClippedTopPx=7,anchorHistoryGeneration=frame.historyGeneration)
        val f=fixture(terminal=terminal,mode=TerminalRenderMode.TUI_CHUNKED_LAYERS,saved=saved)
        compose.setContent{f.Content()};settle(f);val before=f.top()
        compose.runOnIdle{f.mode=TerminalRenderMode.FULL_VIRTUAL;f.publish()};settle(f)
        assertEquals(before.anchor.lineId,f.top().anchor.lineId);assertEquals(before.anchor.clippedTopPx,f.top().anchor.clippedTopPx)
        for(mode in listOf(TerminalRenderMode.CHUNKED_LAYERS,TerminalRenderMode.TUI_CHUNKED_LAYERS,TerminalRenderMode.FULL_VIRTUAL)) {
            compose.runOnIdle{f.mode=mode;f.publish()};settle(f)
            assertEquals(before.anchor.lineId,f.top().anchor.lineId)
            assertEquals(mode.isFullVirtual,f.isVirtual)
        }
        done("handoff")
    }

    @Test fun fullVirtualPairedColdAndStreamingMeasurementsKeepEverySample() {
        var shown by mutableStateOf<Fixture?>(null,referentialEqualityPolicy());compose.setContent{shown?.Content()}
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        Log.i("TerminalFullVirtual","FULL_VIRTUAL_DEVICE "+JSONObject().put("pid",Process.myPid())
            .put("fingerprint",Build.FINGERPRINT).put("targetApk",sha256(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes()))
            .put("testApk",sha256(File(instrumentation.context.applicationInfo.sourceDir).readBytes())))
        for(size in listOf(1000,5000,10000)) for(round in 0..4) {
            val modes=listOf(TerminalRenderMode.TUI_CHUNKED_LAYERS,TerminalRenderMode.FULL_VIRTUAL).let{if(round%2==0)it else it.reversed()}
            for(mode in modes) {
                val term=terminal(size);val start=System.nanoTime();val f=fixture(size,terminal=term,mode=mode)
                val prepared=System.nanoTime();compose.runOnIdle{shown=f};settle(f);val cold=System.nanoTime()-start
                val begin=System.nanoTime()
                val job=compose.runOnIdle{f.stream(3,2,trailingNewline=true){step->f.ime=step==1;f.heightDp=if(f.ime)140 else 240}}
                compose.waitUntil(30_000){job.isCompleted};assertFalse(job.isCancelled);settle(f)
                val stream=System.nanoTime()-begin
                assertEquals(size,f.frame.historyCount)
                val cursorIndex=f.frame.historyCount+f.frame.cursorRow
                val actual=f.measurements.peekEager(f.bound.pass)?.let { geometry ->
                    listOf(geometry.bottom(cursorIndex)-geometry.height(cursorIndex)-f.eager.value,
                        geometry.bottom(cursorIndex)-f.eager.value,geometry.height(cursorIndex))
                } ?: f.observation()!!.measuredRow(cursorIndex)!!.let{listOf(it.topPx,it.bottomPx,it.heightPx)}
                assertTrue(actual[0]>=-1 && actual[1]<=f.viewportHeight+1)
                Log.i("TerminalFullVirtual","FULL_VIRTUAL_PERF "+JSONObject().put("size",size).put("round",round)
                    .put("mode",mode.id).put("warmup",round==0).put("coldNs",cold).put("stateNs",prepared-start)
                    .put("streamNs",stream).put("historyRows",f.frame.historyCount).put("rowStates",f.rowStore.retainedRowStates)
                    .put("cursorTop",actual[0]).put("cursorBottom",actual[1]).put("rowHeight",actual[2]).put("viewport",f.viewportHeight)
                    .put("measuredRows",f.measurements.retainedRows).put("writers",f.maximumWriters))
                compose.runOnIdle{shown=null};compose.waitForIdle();assertEquals(0,f.measurements.retainedRows)
                Log.i("TerminalFullVirtual","FULL_VIRTUAL_DISPOSE "+JSONObject().put("size",size).put("round",round)
                    .put("mode",mode.id).put("measuredRows",f.measurements.retainedRows).put("pointerRows",f.pointerMap.retainedRows))
            }
        }
        done("performance")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
}
