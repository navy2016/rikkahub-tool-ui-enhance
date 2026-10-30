package me.rerere.rikkahub.pipeline

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.container.PtyMode
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Frozen baseline production, real page/native PTY; never an account-backed TUI or phone FPS claim. */
class TerminalFullVirtualLatencyTest : KoinComponent {
    @get:Rule val compose=createAndroidComposeRule<TerminalPipelineTestActivity>()
    @get:Rule val timeout=Timeout.seconds(480)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()
    private val settings: SettingsStore by inject()
    private fun now()=System.nanoTime()
    private fun hash(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun emit(marker: String,value: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("stream","\n$marker $value\n")})
    }
    private class Frames : Window.OnFrameMetricsAvailableListener {
        private val values=ArrayList<JSONObject>()
        var overflow=0
            private set
        @Synchronized override fun onFrameMetricsAvailable(window: Window,metrics: FrameMetrics,dropped: Int) {
            if(values.size>=8192){overflow++;return}
            val row=JSONObject().put("observedNs",System.nanoTime()).put("dropped",dropped)
            for((name,key) in listOf("vsyncNs" to FrameMetrics.VSYNC_TIMESTAMP,"intendedVsyncNs" to FrameMetrics.INTENDED_VSYNC_TIMESTAMP,
                "totalNs" to FrameMetrics.TOTAL_DURATION,"layoutNs" to FrameMetrics.LAYOUT_MEASURE_DURATION,
                "drawNs" to FrameMetrics.DRAW_DURATION,"syncNs" to FrameMetrics.SYNC_DURATION,
                "inputNs" to FrameMetrics.INPUT_HANDLING_DURATION,"animationNs" to FrameMetrics.ANIMATION_DURATION,
                "firstDraw" to FrameMetrics.FIRST_DRAW_FRAME)) row.put(name,metrics.getMetric(key))
            row.put("deadlineNs",if(Build.VERSION.SDK_INT>=31)metrics.getMetric(FrameMetrics.DEADLINE) else -1)
            row.put("gpuNs",if(Build.VERSION.SDK_INT>=31)metrics.getMetric(FrameMetrics.GPU_DURATION) else -1)
            values.add(row)
        }
        @Synchronized fun snapshot()=values.toList()
    }
    private fun memory()=JSONObject().put("pssKb",Debug.getPss()).put("nativeBytes",Debug.getNativeHeapAllocatedSize())
        .put("javaUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
        .put("allocatedBytes",Debug.getRuntimeStat("art.gc.bytes-allocated").toLongOrNull() ?: JSONObject.NULL)
        .put("gcCount",Debug.getRuntimeStat("art.gc.gc-count").toLongOrNull() ?: JSONObject.NULL)
        .put("gcTimeMs",Debug.getRuntimeStat("art.gc.gc-time").toLongOrNull() ?: JSONObject.NULL)

    /** Existing layout results only. Never invoke a measurer or create one node per historical row. */
    private fun layouts(): Map<Int,TextLayoutResult> {
        val viewport=compose.onNodeWithTag("terminal-trace-output",true).fetchSemanticsNode()
        val nodes=compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),true).fetchSemanticsNodes()
        val values=LinkedHashMap<Int,TextLayoutResult>()
        compose.runOnUiThread {
            for(node in nodes) {
                val top=node.positionInRoot.y-viewport.positionInRoot.y
                if(top<0 || top+node.size.height>viewport.size.height+1)continue
                val result=ArrayList<TextLayoutResult>()
                if(node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(result)==true && result.size==1)values[node.id]=result.single()
            }
        }
        return values
    }
    private fun openIme() {
        val node=compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        val x=node.positionOnScreen.x+node.size.width/2f;val y=node.positionOnScreen.y+node.size.height/2f
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;val started=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val e=MotionEvent.obtain(started,SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN
            try{assertTrue(automation.injectInputEvent(e,true))}finally{e.recycle()}
        }
    }
    @Test fun baselineOrdinary1000()=exercise(1000,false)
    @Test fun baselineOrdinary10000()=exercise(10000,false)
    @Test fun baselineTui1000()=exercise(1000,true)
    @Test fun baselineTui10000()=exercise(10000,true)

    private fun exercise(size: Int,tui: Boolean) {
        val case="${if(tui)"tui" else "ordinary"}-$size";val launch=UUID.randomUUID().toString()
        val saved=runBlocking{settings.settingsFlow.first{!it.init}}
        val script="""
            stty icanon icrnl -echo -onlcr
            printf 'READY\r\n'
            while IFS= read -r line; do
              case "${'$'}line" in
                SEED)
                  i=0; while [ "${'$'}i" -lt ${size+100} ]; do
                    printf 'history-%06d 中文 é 👩‍💻\r\n' "${'$'}i"; i=${'$'}((i+1))
                  done
                  printf 'SEEDED\r\n';;
                ECHO:*) printf '%s\r\n' "${'$'}line";;
                CURSOR:*)
                  tail=${'$'}{line#CURSOR:}; row=${'$'}{tail%%:*}; col=${'$'}{tail#*:}
                  printf '\033[%d;%dH' "${'$'}row" "${'$'}col";;
                ROW:*) printf '\r\033[2K%s' "${'$'}line";;
                BURST:*)
                  i=0; while [ "${'$'}i" -lt 32 ]; do
                    printf 'B:%s:%02d 中文\r\n' "${'$'}line" "${'$'}i";i=${'$'}((i+1))
                  done;;
              esac
            done
        """.trimIndent()
        val command="sh -c '"+script.replace("'","'\"'\"'")+"'"
        val sandbox="full-latency-$launch";var mounted by mutableStateOf(false)
        var id: String?=null;var capture: TerminalPipelineTrace.Capture?=null
        val frames=Frames();val frameThread=HandlerThread("terminal-baseline-frames");frameThread.start()
        val frameHandler=Handler(frameThread.looper)
        try {
            runBlocking {
                settings.update{it.copy(terminalCommandPreferences=JSONArray().put(JSONObject()
                    .put("key",hash(command)).put("command",command).put("updatedAt",1L)
                    .put("rawInputMode",false).put("terminalPanMode",true).put("showFullInputBar",true)
                    .put("showStatusBar",true).put("showExtraKeys",true).put("autoScroll",true)
                    .put("forcedTerminalColumns",80).put("terminalFontSizeSp",12).put("maxScrollbackLines",size)).toString(),
                    terminalFullGridCommands="",terminalCustomTuiCommands=if(tui)"sh" else "")}
                settings.setTerminalRenderer(command,TerminalRenderMode.FULL_VIRTUAL);proot.start().getOrThrow()
            }
            val start=runBlocking{manager.startInteractiveSession(sandbox,command,columns=80,rows=24,ptyMode=if(tui)PtyMode.RAW else PtyMode.COOKED)}
            assertTrue(start.message,start.success);assertEquals("native-pty",start.terminalBackend)
            val process=start.processId;id=process
            val trace=TerminalPipelineTrace.start(process,4096);capture=trace
            val terminal=requireNotNull(manager.getInteractiveTerminalEmulator(process))
            val nav=Navigator(mutableListOf())
            compose.runOnUiThread{compose.activity.window.addOnFrameMetricsAvailableListener(frames,frameHandler)}
            compose.setContent {
                val values by settings.settingsFlow.collectAsState()
                RikkahubTheme { CompositionLocalProvider(LocalSettings provides values,LocalNavController provides nav) {
                    if(mounted)ProcessSessionPage(sandbox,process)
                } }
            }
            fun ready(ime: Boolean): Boolean {
                val p=trace.viewport()?.viewport ?: return false
                return p.preferred==TerminalRenderMode.FULL_VIRTUAL && p.applied==p.preferred && p.fullVirtual && p.virtual && p.bindingVirtual &&
                    p.ime==ime && p.tui==tui && !p.alternate && !p.selection && p.pan && p.sameFrame && p.geometryReady && p.widthReady &&
                    p.initialized && p.following && p.tailSatisfied && p.gestureId==null && p.effectId==null && p.writers==0 && !p.scrolling &&
                    p.retainedRowStates in 6..80 && p.measuredRows in 1..128 && !manager.hasPendingTerminalResize(process)
            }
            fun settle(ime: Boolean) {
                compose.waitUntil(30_000){
                    val ok=ready(ime)
                    if(!ok)compose.runOnUiThread{trace.requestViewportDraw()}
                    ok
                };compose.waitForIdle()
            }
            compose.waitUntil(15_000){manager.readInteractiveBuffer(process)?.contains("READY\r\n")==true}
            val mountStart=now();compose.runOnUiThread{mounted=true};Espresso.closeSoftKeyboard()
            compose.waitUntil(30_000){terminal.maxScrollbackLines()==size && trace.viewport()?.viewport?.initialized==true && !manager.hasPendingTerminalResize(process)}
            runBlocking{manager.sendInput(process,"SEED").getOrThrow()}
            compose.waitUntil(30_000){manager.readInteractiveBuffer(process)?.contains("SEEDED\r\n")==true}
            settle(false);assertEquals(size,terminal.renderFrame().historyCount)
            val physicalRows=terminal.rows
            emit("FV_LATENCY_MOUNT",JSONObject().put("case",case).put("launch",launch).put("seedIncluded",true)
                .put("elapsedNs",now()-mountStart).put("historyRows",size).put("columns",terminal.columns).put("rows",physicalRows))
            emit("FV_LATENCY_MEMORY",memory().put("case",case).put("launch",launch).put("phase","ready"))
            for(phase in listOf("echo","cursor","row","burst","imeEcho","remountEcho")) {
                if(phase=="imeEcho") { openIme();settle(true) }
                if(phase=="remountEcho") {
                    Espresso.closeSoftKeyboard();settle(false)
                    compose.runOnUiThread{mounted=false};compose.waitForIdle()
                    val began=now();compose.runOnUiThread{mounted=true};settle(false)
                    emit("FV_LATENCY_MOUNT",JSONObject().put("case",case).put("launch",launch).put("seedIncluded",false)
                        .put("elapsedNs",now()-began).put("historyRows",size).put("columns",terminal.columns).put("rows",terminal.rows))
                }
                repeat(22) { iteration ->
                    val ime=phase=="imeEcho";settle(ime)
                    val previous=layouts();val preFrame=terminal.renderFrame()
                    val row=physicalRows;val column=3+(iteration%30)
                    val marker="$case-$phase-${iteration.toString().padStart(2,'0')}"
                    val input=when(phase){"cursor"->"CURSOR:$row:$column";"row"->"ROW:$marker";"burst"->"BURST:$marker";else->"ECHO:$marker"}
                    val output=when(phase){"cursor"->"\u001b[$row;${column}H";"row"->"\r\u001b[2K$input";
                        "burst"->(0 until 32).joinToString(""){"B:$input:${it.toString().padStart(2,'0')} 中文\r\n"};else->"$input\r\n"}
                    trace.beginWindow();val started=now()
                    compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText){check(it(AnnotatedString(input)))}
                    compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.OnImeAction){check(it())}
                    compose.waitUntil(15_000){
                        val events=trace.snapshot();val fed=events.filter{it.stage==TerminalPipelineStage.EMULATOR_FED}
                        fed.sumOf{it.bytes}==output.toByteArray().size && fed.isNotEmpty() &&
                            events.any{it.stage==TerminalPipelineStage.FRAME_DRAWN && it.frameRevision>=fed.last().frameRevision} && ready(ime)
                    }
                    val checked=now();compose.waitForIdle()
                    val events=trace.snapshot();assertEquals(0L,trace.dropped)
                    val ui=events.single{it.stage==TerminalPipelineStage.UI_INPUT}
                    val fed=events.filter{it.stage==TerminalPipelineStage.EMULATOR_FED}
                    assertEquals(output.toByteArray().size,fed.sumOf{it.bytes})
                    assertEquals(output.toByteArray().size,events.filter{it.stage==TerminalPipelineStage.OUTPUT_READ}.sumOf{it.bytes})
                    val last=fed.last();val publication=events.first{it.stage==TerminalPipelineStage.FRAME_PUBLISHED && it.frameRevision>=last.frameRevision}
                    val drawn=events.first{it.stage==TerminalPipelineStage.FRAME_DRAWN && it.frameRevision>=publication.frameRevision}
                    val after=terminal.renderFrame();assertEquals(size,after.historyCount);assertEquals(physicalRows,terminal.rows)
                    assertTrue(drawn.virtual==true && drawn.timeNanos>=ui.timeNanos)
                    if(phase=="cursor"){assertEquals(row-1,after.cursorRow);assertEquals(column-1,after.cursorColumn)}
                    else if(phase=="row")assertTrue(after.rows[after.historyCount+after.cursorRow].text.text.contains(input))
                    else assertTrue(manager.readInteractiveBuffer(process)?.contains(output)==true)
                    val next=layouts();val common=previous.keys.intersect(next.keys)
                    val replaced=common.count{previous[it]!==next[it]}
                    val changed=common.count{previous[it]!!.layoutInput.text!=next[it]!!.layoutInput.text}
                    val state=trace.viewport()!!.viewport
                    val result=JSONObject().put("case",case).put("launch",launch).put("phase",phase).put("iteration",iteration)
                        .put("warmup",iteration<2).put("historyRows",size).put("tui",tui).put("ime",ime).put("virtual",state.fullVirtual&&state.virtual&&state.bindingVirtual)
                        .put("startedNs",started).put("uiNs",ui.timeNanos).put("drawNs",drawn.timeNanos).put("checkedNs",checked)
                        .put("inputToDrawNs",drawn.timeNanos-ui.timeNanos).put("inputToCheckNs",checked-ui.timeNanos)
                        .put("inputBytes",input.toByteArray().size).put("outputBytes",output.toByteArray().size).put("outputSha256",hash(output))
                        .put("beforeRevision",preFrame.revision).put("fedRevision",last.frameRevision).put("publishedRevision",publication.frameRevision).put("drawnRevision",drawn.frameRevision)
                        .put("visibleBefore",previous.size).put("visibleAfter",next.size).put("commonLayouts",common.size).put("replacedLayouts",replaced).put("changedTexts",changed)
                        .put("retainedRows",state.retainedRowStates).put("measuredRows",state.measuredRows).put("writers",state.writers)
                        .put("panelCompositions",events.count{it.stage==TerminalPipelineStage.PANEL_COMPOSED})
                        .put("captures",events.count{it.stage==TerminalPipelineStage.FRAME_CAPTURED}).put("publications",events.count{it.stage==TerminalPipelineStage.FRAME_PUBLISHED})
                    val spans=JSONObject()
                    for((label,startStage,endStage) in listOf(Triple("readToLockNs",TerminalPipelineStage.NATIVE_READ_COMPLETED,TerminalPipelineStage.PARSE_LOCK_ACQUIRED),
                        Triple("captureNs",TerminalPipelineStage.FRAME_CAPTURE_STARTED,TerminalPipelineStage.FRAME_CAPTURED),
                        Triple("rowSyncNs",TerminalPipelineStage.ROW_SYNC_STARTED,TerminalPipelineStage.ROW_SYNC_FINISHED))) {
                        val ends=events.filter{it.stage==endStage};var total=0L
                        for(end in ends){val begin=events.lastOrNull{it.stage==startStage&&it.sequence<end.sequence};if(begin!=null)total+=end.timeNanos-begin.timeNanos}
                        spans.put(label,total)
                    }
                    spans.put("parseWindowNs",last.timeNanos-events.first{it.stage==TerminalPipelineStage.PARSE_LOCK_ACQUIRED}.timeNanos)
                    spans.put("uiToNativeReadNs",events.first{it.stage==TerminalPipelineStage.NATIVE_READ_COMPLETED}.timeNanos-ui.timeNanos)
                    spans.put("feedToPublishNs",publication.timeNanos-last.timeNanos)
                    spans.put("publishToDrawNs",drawn.timeNanos-publication.timeNanos)
                    result.put("spans",spans)
                    result.put("events",JSONArray().apply{events.forEach{e->put(JSONObject().put("sequence",e.sequence).put("stage",e.stage.name)
                        .put("timeNs",e.timeNanos).put("bytes",e.bytes).put("revision",e.frameRevision).put("virtual",e.virtual ?: JSONObject.NULL))}})
                    emit("FV_LATENCY_SAMPLE",result)
                }
                emit("FV_LATENCY_MEMORY",memory().put("case",case).put("launch",launch).put("phase",phase))
            }
            compose.waitForIdle()
            compose.runOnUiThread{compose.activity.window.removeOnFrameMetricsAvailableListener(frames)}
            val drained=CountDownLatch(1);frameHandler.post{drained.countDown()}
            assertTrue("Frame metrics listener did not drain",drained.await(5,TimeUnit.SECONDS))
            val nativeFrames=frames.snapshot()
            nativeFrames.forEachIndexed{index,value->emit("FV_LATENCY_FRAME",value.put("case",case).put("launch",launch).put("index",index))}
            assertEquals(0,frames.overflow);assertTrue(nativeFrames.isNotEmpty())
            emit("FV_LATENCY_CASE",JSONObject().put("case",case).put("launch",launch).put("passed",true).put("samples",132)
                .put("pid",android.os.Process.myPid())
                .put("frames",nativeFrames.size).put("frameReportsDropped",nativeFrames.sumOf{it.getInt("dropped")}).put("frameBufferOverflow",frames.overflow)
                .put("refreshRate",compose.activity.display?.refreshRate ?: 0f).put("fingerprint",Build.FINGERPRINT))
        } finally {
            try{compose.runOnUiThread{mounted=false;compose.activity.window.removeOnFrameMetricsAvailableListener(frames)};compose.waitForIdle()}
            finally {
                capture?.close();frameThread.quitSafely();frameThread.join(5000)
                try{id?.let{runBlocking{manager.closeInteractiveSession(it)};manager.removeProcessRecord(it)}}
                finally{runBlocking{settings.update{it.copy(terminalCommandPreferences=saved.terminalCommandPreferences,
                    terminalRenderPreferences=saved.terminalRenderPreferences,terminalCustomTuiCommands=saved.terminalCustomTuiCommands,
                    terminalFullGridCommands=saved.terminalFullGridCommands)}}}
                emit("FV_LATENCY_CLEANUP",JSONObject().put("case",case).put("launch",launch).put("closed",id?.let{manager.getInteractiveTerminalEmulator(it)==null} ?: true)
                    .put("frameThreadStopped",!frameThread.isAlive))
            }
        }
    }
}
