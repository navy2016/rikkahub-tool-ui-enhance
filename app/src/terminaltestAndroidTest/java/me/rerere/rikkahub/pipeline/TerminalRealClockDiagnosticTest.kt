package me.rerere.rikkahub.pipeline

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Handler
import android.os.HandlerThread
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.DeflaterOutputStream
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

/** ActivityScenario + platform accessibility only: no Compose test owner/clock/idling resources. */
class TerminalRealClockDiagnosticTest : KoinComponent {
    @get:Rule val timeout=Timeout.seconds(360)
    private val manager: BackgroundProcessManager by inject()
    private val proot: PRootManager by inject()
    private val settings: SettingsStore by inject()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    private fun hash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun emit(tag: String,value: JSONObject) { instrumentation.sendStatus(0,Bundle().apply{putString("stream","\nREAL_CLOCK_$tag $value\n")}) }
    private fun emitCase(value: JSONObject) {
        val body=value.toString();val output=ByteArrayOutputStream()
        DeflaterOutputStream(output).use{it.write(body.toByteArray())}
        val parts=Base64.getEncoder().encodeToString(output.toByteArray()).chunked(2600)
        check(parts.size<=256){"Real clock case evidence exceeded bound"}
        parts.forEachIndexed{index,part->emit("PART",JSONObject().put("case",value.getString("case")).put("part",index+1)
            .put("parts",parts.size).put("sha256",hash(body)).put("data",part))}
    }
    private fun await(label: String,millis: Long=30_000,predicate: ()->Boolean) {
        val deadline=System.nanoTime()+millis*1_000_000L
        while(!predicate()){if(System.nanoTime()>=deadline)fail("Real clock deadline: $label");SystemClock.sleep(2)}
    }
    private fun command(text: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(text)).bufferedReader().use{it.readText()}
    private fun editor(): AccessibilityNodeInfo? {
        val root=automation.rootInActiveWindow ?: return null
        val todo=ArrayDeque<AccessibilityNodeInfo>();todo.add(root);var found: AccessibilityNodeInfo?=null
        while(todo.isNotEmpty()) {
            val node=todo.removeFirst()
            if(node.isEditable && node.isVisibleToUser && node.packageName?.toString()=="me.rerere.rikkahub.dev.next.terminaltest") {
                check(found==null){"Multiple visible terminal editors"};found=node;continue
            }
            repeat(node.childCount){node.getChild(it)?.let(todo::addLast)}
            node.recycle()
        }
        return found
    }
    private fun tap(node: AccessibilityNodeInfo) {
        val rect=Rect();node.getBoundsInScreen(rect);val down=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,rect.exactCenterX(),rect.exactCenterY(),0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try{assertTrue(automation.injectInputEvent(event,true))}finally{event.recycle()}
        }
    }
    private fun back() {
        val now=SystemClock.uptimeMillis()
        for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP))assertTrue(automation.injectInputEvent(KeyEvent(now,SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_BACK,0),true))
    }
    private data class Draw(val timeNs: Long,val drawingTimeMs: Long)
    private class Observers : Window.OnFrameMetricsAvailableListener {
        val metrics=ArrayList<LongArray>();val draws=ArrayList<Draw>();var overflow=0
        private val completedWindows=HashSet<Long>()
        @Synchronized override fun onFrameMetricsAvailable(window: Window,frame: FrameMetrics,dropped: Int) {
            if(metrics.size>=8192){overflow++;return}
            metrics.add(longArrayOf(System.nanoTime(),frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
                frame.getMetric(FrameMetrics.TOTAL_DURATION),frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),frame.getMetric(FrameMetrics.DRAW_DURATION),
                frame.getMetric(FrameMetrics.SYNC_DURATION),frame.getMetric(FrameMetrics.GPU_DURATION),frame.getMetric(FrameMetrics.DEADLINE),dropped.toLong()))
            completedWindows.add(frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP)/1_000_000L)
        }
        @Synchronized fun draw(timeMs: Long){if(draws.size>=8192)overflow++ else draws.add(Draw(System.nanoTime(),timeMs))}
        @Synchronized fun readDraws()=draws.toList()
        @Synchronized fun readMetrics()=metrics.map{it.copyOf()}
        @Synchronized fun windowFinishedAfter(viewportNs: Long): Boolean {
            val draw=draws.lastOrNull{it.timeNs<=viewportNs} ?: return false
            return draw.drawingTimeMs in completedWindows
        }
    }
    @Test fun realClockDefaultOrdinary()=exercise(TerminalRenderMode.DEFAULT,false)
    @Test fun realClockFullOrdinary()=exercise(TerminalRenderMode.FULL_VIRTUAL,false)
    @Test fun realClockDefaultTui()=exercise(TerminalRenderMode.DEFAULT,true)
    @Test fun realClockFullTui()=exercise(TerminalRenderMode.FULL_VIRTUAL,true)

    private fun exercise(mode: TerminalRenderMode,tui: Boolean) {
        val case="${mode.id}-${if(tui)"tui" else "ordinary"}";val launch=UUID.randomUUID().toString();val sandbox="real-clock-$launch"
        val saved=runBlocking{settings.settingsFlow.first{!it.init}}
        val script="""
            stty icanon icrnl -echo -onlcr
            printf 'READY\r\n'
            while IFS= read -r command; do
              case "${'$'}command" in
                SEED)
                  i=0;while [ "${'$'}i" -lt 1120 ]; do
                    printf 'row-%05d 中文 é 👩‍💻\r\n' "${'$'}i";i=${'$'}((i+1))
                  done
                  printf 'SEEDED\r\n';;
                ECHO:*) printf '%s\r\n' "${'$'}command";;
              esac
            done
        """.trimIndent()
        val peer="sh -c '"+script.replace("'","'\"'\"'")+"'"
        var mounted by mutableStateOf(false);var process: String?=null;var capture: TerminalPipelineTrace.Capture?=null
        var scenario: ActivityScenario<TerminalPipelineTestActivity>?=null;var listener: ViewTreeObserver.OnDrawListener?=null
        val observer=Observers();val thread=HandlerThread("real-clock-metrics").apply{start()};val handler=Handler(thread.looper)
        val samples=ArrayList<JSONObject>();val dumps=ArrayList<JSONObject>();val gates=ArrayList<JSONObject>();var attached=false
        try {
            runBlocking {
                settings.update{it.copy(terminalCommandPreferences=JSONArray().put(JSONObject().put("key",hash(peer)).put("command",peer).put("updatedAt",1L)
                    .put("rawInputMode",false).put("terminalPanMode",true).put("showFullInputBar",true).put("showStatusBar",true).put("showExtraKeys",true)
                    .put("autoScroll",true).put("forcedTerminalColumns",80).put("terminalFontSizeSp",12).put("maxScrollbackLines",1000)).toString(),
                    terminalFullGridCommands="",terminalCustomTuiCommands=if(tui)"sh" else "")}
                settings.setTerminalRenderer(peer,mode);proot.start().getOrThrow()
            }
            val start=runBlocking{manager.startInteractiveSession(sandbox,peer,columns=80,rows=24,ptyMode=if(tui)PtyMode.RAW else PtyMode.COOKED)}
            assertTrue(start.message,start.success);assertEquals("native-pty",start.terminalBackend);val id=start.processId;process=id
            val terminal=requireNotNull(manager.getInteractiveTerminalEmulator(id));val trace=TerminalPipelineTrace.start(id,4096);capture=trace
            val nav=Navigator(mutableListOf())
            val owner=ActivityScenario.launch<TerminalPipelineTestActivity>(Intent(instrumentation.targetContext,TerminalPipelineTestActivity::class.java));scenario=owner
            owner.onActivity { activity ->
                val draw=ViewTreeObserver.OnDrawListener{observer.draw(activity.window.decorView.drawingTime)};listener=draw
                activity.window.decorView.viewTreeObserver.addOnDrawListener(draw)
                activity.window.addOnFrameMetricsAvailableListener(observer,handler);attached=true
                activity.setContent {
                    val value by settings.settingsFlow.collectAsState()
                    RikkahubTheme{CompositionLocalProvider(LocalSettings provides value,LocalNavController provides nav){if(mounted)ProcessSessionPage(sandbox,id)}}
                }
            }
            await("peer READY"){manager.readInteractiveBuffer(id)?.contains("READY\r\n")==true}
            owner.onActivity{mounted=true}
            await("initial page"){trace.viewport()?.viewport?.initialized==true && terminal.maxScrollbackLines()==1000}
            runBlocking{manager.sendInput(id,"SEED").getOrThrow()}
            await("seeded"){manager.readInteractiveBuffer(id)?.contains("SEEDED\r\n")==true}
            fun ready(ime: Boolean): Boolean {
                val p=trace.viewport()?.viewport ?: return false
                return p.preferred==mode && p.applied==mode && p.virtual==mode.isFullVirtual && p.bindingVirtual==mode.isFullVirtual && p.fullVirtual==mode.isFullVirtual &&
                    p.ime==ime && p.tui==tui && !p.selection && !p.alternate && p.pan && p.sameFrame && p.initialized && p.following && p.geometryReady &&
                    p.gestureId==null && p.effectId==null && p.writers==0 && !p.scrolling && p.tailSatisfied && p.historyRows==1000 &&
                    !manager.hasPendingTerminalResize(id) && (!mode.isFullVirtual || p.widthReady)
            }
            fun settle(ime: Boolean,phase: String) {
                var signature: List<Any?>?=null;var stableSince=System.nanoTime();var lastPulse=0L
                await("stable $phase") {
                    val ack=manager.interactiveSessionSize(id);val p=trace.viewport()?.viewport
                    val next=listOf(ack?.request,ack?.columns,ack?.rows,terminal.columns,terminal.rows,p?.revision,p?.ime)
                    if(next!=signature || !ready(ime)){signature=next;stableSince=System.nanoTime()}
                    // Setup only: refresh the existing observer, never inside a measured input window.
                    if(!ready(ime) && System.nanoTime()-lastPulse>100_000_000L){owner.onActivity{trace.requestViewportDraw()};lastPulse=System.nanoTime()}
                    ready(ime) && ack!=null && ack.rows==terminal.rows && ack.columns==terminal.columns && System.nanoTime()-stableSince>=700_000_000L
                }
                gates.add(JSONObject().put("phase",phase).put("rows",terminal.rows).put("columns",terminal.columns).put("ime",ime).put("virtual",mode.isFullVirtual))
            }
            settle(false,"hidden");val rows=terminal.rows
            for(phase in listOf("hidden","shown","hiddenAgain")) {
                if(phase=="shown"){val field=requireNotNull(editor());try{tap(field)}finally{field.recycle()};settle(true,phase)}
                if(phase=="hiddenAgain"){back();settle(false,phase)}
                val ime=phase=="shown";assertEquals(rows,terminal.rows)
                var field: AccessibilityNodeInfo?=null
                await("editable native node"){field=editor();field!=null}
                val input=checkNotNull(field)
                try {
                    assertTrue("Native editor has no IME action",input.actionList.any{it.id==AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id})
                    val clockBefore=JSONObject().put("monoNs",System.nanoTime()).put("bootNs",SystemClock.elapsedRealtimeNanos())
                    repeat(12) { index ->
                        val expected="ECHO:$case:$phase:${index.toString().padStart(2,'0')}"
                        val output="$expected\r\n";val payload=output.toByteArray();val before=trace.beginWindow()
                        val beforeRevision=terminal.revision()
                        assertTrue("Editor detached",input.refresh())
                        val arguments=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,expected)}
                        val began=System.nanoTime()
                        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,arguments))
                        assertTrue(input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
                        val actionReturned=System.nanoTime()
                        await("native echo first draw",15_000) {
                            val view=trace.viewport();view!=null && view.viewport.revision>beforeRevision && view.viewport.revision==terminal.revision() &&
                                view.viewport.sameFrame && view.viewport.ime==ime && view.viewport.virtual==mode.isFullVirtual && view.timeNanos>=began &&
                                view.viewport.geometryReady && (!mode.isFullVirtual || view.viewport.widthReady) && observer.windowFinishedAfter(view.timeNanos)
                        }
                        val observed=System.nanoTime();val events=trace.snapshot().filter{it.sequence>before}
                        val ui=checkNotNull(events.singleOrNull{it.stage==TerminalPipelineStage.UI_INPUT}){"Missing/duplicate UI_INPUT"};val fed=events.filter{it.stage==TerminalPipelineStage.EMULATOR_FED}
                        assertTrue("Actual finite echo missing",manager.readInteractiveBuffer(id)?.contains(output)==true)
                        assertEquals(0L,trace.dropped);assertEquals(payload.size,fed.sumOf{it.bytes})
                        assertEquals(payload.size,events.filter{it.stage==TerminalPipelineStage.OUTPUT_READ}.sumOf{it.bytes})
                        val revision=fed.last().frameRevision
                        val published=checkNotNull(events.firstOrNull{it.stage==TerminalPipelineStage.FRAME_PUBLISHED && it.frameRevision>=revision}){"No FRAME_PUBLISHED for completed response"}
                        val draw=checkNotNull(events.firstOrNull{it.stage==TerminalPipelineStage.FRAME_DRAWN && it.frameRevision>=published.frameRevision}){"No eligible FRAME_DRAWN for completed response"}
                        assertTrue(draw.virtual==mode.isFullVirtual);assertEquals(rows,terminal.rows)
                        samples.add(JSONObject().put("phase",phase).put("index",index).put("warmup",index<2).put("beganNs",began)
                            .put("actionReturnedNs",actionReturned).put("observedNs",observed).put("uiNs",ui.timeNanos).put("publishedNs",published.timeNanos)
                            .put("drawNs",draw.timeNanos).put("fedRevision",revision).put("publishedRevision",published.frameRevision).put("drawRevision",draw.frameRevision)
                            .put("outputBytes",payload.size).put("outputSha256",hash(output)).put("virtual",mode.isFullVirtual)
                            .put("events",JSONArray().apply{events.forEach{e->put(JSONObject().put("seq",e.sequence).put("stage",e.stage.name).put("ns",e.timeNanos).put("bytes",e.bytes).put("revision",e.frameRevision))}}))
                        // Fixed spacing, after the measured draw; no global-idle or test-clock calls.
                        SystemClock.sleep(80)
                    }
                    SystemClock.sleep(180)
                    dumps.add(JSONObject().put("phase",phase).put("clockBefore",clockBefore).put("clockAfter",JSONObject().put("monoNs",System.nanoTime()).put("bootNs",SystemClock.elapsedRealtimeNanos()))
                        .put("gfxinfo",command("dumpsys gfxinfo me.rerere.rikkahub.dev.next.terminaltest framestats")))
                } finally {input.recycle()}
            }
            owner.onActivity{activity->listener?.let{activity.window.decorView.viewTreeObserver.removeOnDrawListener(it)};activity.window.removeOnFrameMetricsAvailableListener(observer);attached=false}
            val drained=CountDownLatch(1);handler.post{drained.countDown()};assertTrue(drained.await(5,TimeUnit.SECONDS));assertEquals(0,observer.overflow)
            val draws=JSONArray().apply{observer.readDraws().forEach{put(JSONObject().put("ns",it.timeNs).put("drawingTimeMs",it.drawingTimeMs))}}
            val metrics=JSONArray().apply{observer.readMetrics().forEach{put(JSONArray(it.toList()))}}
            emitCase(JSONObject().put("case",case).put("mode",mode.id).put("tui",tui).put("launch",launch).put("pid",android.os.Process.myPid())
                .put("samples",JSONArray(samples)).put("gates",JSONArray(gates)).put("draws",draws).put("metrics",metrics).put("phaseDumps",JSONArray(dumps))
                .put("clock", "real-android-no-compose-test-clock").put("droppedFrameMetrics",observer.readMetrics().sumOf{it[9]}).put("passed",true))
        } catch(error: Throwable) {
            emitCase(JSONObject().put("case",case).put("passed",false).put("error",error.toString()).put("samples",JSONArray(samples))
                .put("lastEvents",JSONArray().apply{capture?.snapshot()?.takeLast(96)?.forEach{e->put(JSONObject().put("seq",e.sequence).put("stage",e.stage.name).put("ns",e.timeNanos).put("bytes",e.bytes).put("revision",e.frameRevision))}}))
            throw error
        } finally {
            try {
                scenario?.onActivity{activity->mounted=false;if(attached){listener?.let{activity.window.decorView.viewTreeObserver.removeOnDrawListener(it)};activity.window.removeOnFrameMetricsAvailableListener(observer);attached=false}}
                scenario?.close()
            } finally {
                capture?.close();thread.quitSafely();thread.join(5000)
                try{process?.let{id->runBlocking{manager.closeInteractiveSession(id)};manager.removeProcessRecord(id)}}
                finally{runBlocking{settings.update{it.copy(terminalCommandPreferences=saved.terminalCommandPreferences,terminalRenderPreferences=saved.terminalRenderPreferences,
                    terminalCustomTuiCommands=saved.terminalCustomTuiCommands,terminalFullGridCommands=saved.terminalFullGridCommands)}}}
                emit("CLEANUP",JSONObject().put("case",case).put("closed",process?.let{manager.getInteractiveTerminalEmulator(it)==null} ?: true).put("threadStopped",!thread.isAlive))
            }
        }
    }
}
