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
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
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
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.container.BackgroundProcessManager
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.container.PtyMode
import me.rerere.rikkahub.data.container.TerminalPipelineStage
import me.rerere.rikkahub.data.container.TerminalPipelineEvent
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
import me.rerere.rikkahub.utils.ownedRows
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** ActivityScenario + platform accessibility only: no Compose test owner/clock/idling resources. */
class TerminalP2IdentityPerformanceTest : KoinComponent {
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
        val deadline=SystemClock.elapsedRealtimeNanos()+millis*1_000_000L
        while(!predicate()){if(SystemClock.elapsedRealtimeNanos()>=deadline)fail("Real clock deadline: $label");SystemClock.sleep(2)}
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
    private fun visibleText(expected: String): Boolean {
        val root=automation.rootInActiveWindow ?: return false
        val todo=ArrayDeque<AccessibilityNodeInfo>();todo.add(root);var found=false
        while(todo.isNotEmpty()) {
            val node=todo.removeFirst()
            if(!node.isEditable && node.isVisibleToUser && node.packageName?.toString()=="me.rerere.rikkahub.dev.next.terminaltest" && node.text?.toString()?.trimEnd()==expected) {
                val bounds=Rect();node.getBoundsInScreen(bounds);found=found || !bounds.isEmpty
            }
            repeat(node.childCount){node.getChild(it)?.let(todo::addLast)};node.recycle()
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
    /** Use the production command-key/decoder, not a second JSON oracle. Setup only, never timed. */
    private fun decodedPreference(raw: String, peer: String): Map<String, Any?> {
        val method=Class.forName("me.rerere.rikkahub.ui.pages.container.ProcessSessionPageKt")
            .getDeclaredMethod("terminalCommandPreference",String::class.java,String::class.java).apply{isAccessible=true}
        val value=method.invoke(null,raw,peer) ?: return emptyMap()
        val names=listOf("key","rawInputMode","terminalPanMode","showFullInputBar","showExtraKeys",
            "forcedTerminalColumns","maxScrollbackLines","terminalFontSizeSp")
        return names.associateWith { name ->
            value.javaClass.getMethod("get"+name.replaceFirstChar{it.uppercaseChar()}).apply{isAccessible=true}.invoke(value)
        }
    }
    private fun startupReceipt(): String {
        val pid=android.os.Process.myPid();val deadline=SystemClock.elapsedRealtimeNanos()+30_000_000_000L
        while(SystemClock.elapsedRealtimeNanos()<deadline) {
            val log=command("logcat -d -v brief --pid $pid -s RikkaHubApp:I '*:S'")
            check(!log.contains("incrementLaunchCount failed")) { "Startup writer failed" }
            val line=log.lineSequence().singleOrNull { it.contains("incrementLaunchCount:") }
            if(line!=null)return line.trim()
            SystemClock.sleep(20)
        }
        error("No startup writer completion receipt for this PID")
    }
    private data class Draw(val timeNs: Long,val drawingTimeMs: Long)
    private class Observers : Window.OnFrameMetricsAvailableListener {
        val metrics=ArrayList<LongArray>();val rawMetrics=ArrayList<LongArray>();val draws=ArrayList<Draw>();var overflow=0
        private val rawField=FrameMetrics::class.java.getDeclaredField("mTimingData").apply{isAccessible=true}
        private val traversals=ArrayList<LongArray>()
        @Synchronized override fun onFrameMetricsAvailable(window: Window,frame: FrameMetrics,dropped: Int) {
            if(metrics.size>=8192){overflow++;return}
            val copied=(rawField.get(frame) as LongArray).clone()
            rawMetrics.add(copied)
            metrics.add(longArrayOf(SystemClock.elapsedRealtimeNanos(),frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),frame.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
                frame.getMetric(FrameMetrics.TOTAL_DURATION),frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),frame.getMetric(FrameMetrics.DRAW_DURATION),
                frame.getMetric(FrameMetrics.SYNC_DURATION),frame.getMetric(FrameMetrics.GPU_DURATION),frame.getMetric(FrameMetrics.DEADLINE),dropped.toLong()))
        }
        @Synchronized fun draw(timeMs: Long){if(draws.size>=8192)overflow++ else draws.add(Draw(SystemClock.elapsedRealtimeNanos(),timeMs))}
        @Synchronized fun readDraws()=draws.toList()
        @Synchronized fun readMetrics()=metrics.map{it.copyOf()}
        @Synchronized fun readRawMetrics()=rawMetrics.map{it.copyOf()}
        @Synchronized fun traversalFinished(began: Long){if(traversals.size<8192)traversals.add(longArrayOf(began,SystemClock.elapsedRealtimeNanos())) else overflow++}
        @Synchronized fun windowFinishedAfter(viewportNs: Long)=traversals.any{it[0]<=viewportNs && viewportNs<=it[1]}
        @Synchronized fun readTraversals()=traversals.map{it.copyOf()}
    }
    @Test fun p2IdentityOneThousandHidden() = exercise(1000, false)
    @Test fun p2IdentityOneThousandShown() = exercise(1000, true)
    @Test fun p2IdentityTenThousandHidden() = exercise(10000, false)
    @Test fun p2IdentityTenThousandShown() = exercise(10000, true)

    private fun exercise(size: Int, expectedIme: Boolean) {
        val mode = TerminalRenderMode.FULL_VIRTUAL
        val tui = true
        val setupOnly=InstrumentationRegistry.getArguments().getString("realClockSetupOnly")=="true"
        val case="p2identity-$size-${if(expectedIme) "shown" else "hidden"}";val launch=UUID.randomUUID().toString();val sandbox="real-clock-$launch"
        val startup = startupReceipt()
        val saved=runBlocking{settings.settingsFlow.first{!it.init}}
        val script="""
            stty icanon icrnl -echo -onlcr
            in_alt=0
            printf 'READY\r\n'
            while IFS= read -r command; do
              case "${'$'}command" in
                SEED)
                  i=0;while [ "${'$'}i" -lt ${size+120} ]; do
                    printf 'row-%05d 中文 é 👩‍💻\r\n' "${'$'}i";i=${'$'}((i+1))
                  done
                  printf '\033[?25l\033[24;1H\033[2KSEEDED\r';;
                ALT) printf '\033[?1049h\033[?25l'
                  i=1;while [ "${'$'}i" -le 18 ];do
                    printf '\033[%s;1Hrow-%02d 中文 é 👩‍💻 0123456789' "${'$'}i" "${'$'}i";i=${'$'}((i+1))
                  done
                  printf '\033[24;1H\033[2KALT-READY\r';in_alt=1;;
                ECHO:*)
                  if [ "${'$'}in_alt" -eq 1 ];then printf '\033[?1049l';in_alt=0;fi
                  printf '\033[24;1H\033[2K%s\r' "${'$'}command";;
              esac
            done
        """.trimIndent()
        val peer="sh -c '"+script.replace("'","'\"'\"'")+"'"
        val intendedPreference=JSONArray().put(JSONObject().put("key",hash(peer)).put("command",peer).put("updatedAt",1L)
            .put("rawInputMode",false).put("terminalPanMode",true).put("showFullInputBar",true).put("showStatusBar",true).put("showExtraKeys",true)
            .put("autoScroll",true).put("forcedTerminalColumns",80).put("terminalFontSizeSp",12).put("maxScrollbackLines",size)).toString()
        fun configured(raw: String, render: String, custom: String, fullGrid: String): Boolean {
            val preference=decodedPreference(raw,peer)
            return preference["key"]==hash(peer) && preference["rawInputMode"]==false && preference["terminalPanMode"]==true &&
                preference["showFullInputBar"]==true && preference["showExtraKeys"]==true && preference["forcedTerminalColumns"]==80 &&
                preference["maxScrollbackLines"]==size && preference["terminalFontSizeSp"]==12f &&
                terminalRendererForCommand(render,peer)==mode && custom=="sh" && fullGrid.isEmpty()
        }
        val preparation=ArrayList<JSONObject>()
        fun snapshotSetup(stage: String) {
            val current=settings.settingsFlow.value
            preparation.add(JSONObject().put("stage",stage).put("ns",SystemClock.elapsedRealtimeNanos()).put("init",current.init)
                .put("rawSha256",hash(current.terminalCommandPreferences)).put("expectedRawSha256",hash(intendedPreference))
                .put("commandSha256",hash(peer)).put("decoded",JSONObject(decodedPreference(current.terminalCommandPreferences,peer)))
                .put("configured",configured(current.terminalCommandPreferences,current.terminalRenderPreferences,
                    current.terminalCustomTuiCommands,current.terminalFullGridCommands)))
        }
        val localSettingsReady=CountDownLatch(1)
        var firstPanelCommitted by mutableStateOf(false)
        var mounted by mutableStateOf(false);var process: String?=null;var capture: TerminalPipelineTrace.Capture?=null
        var scenario: ActivityScenario<TerminalPipelineTestActivity>?=null;var listener: ViewTreeObserver.OnDrawListener?=null
        val observer=Observers();val thread=HandlerThread("real-clock-metrics").apply{start()};val handler=Handler(thread.looper)
        val mirror=Observers();val mirrorThread=HandlerThread("frame-mirror-metrics").apply{start()};val mirrorHandler=Handler(mirrorThread.looper)
        val samples=ArrayList<JSONObject>();val dumps=ArrayList<JSONObject>();val gates=ArrayList<JSONObject>();var attached=false
        try {
            // Establish the native accessibility connection BEFORE app startup/measurement.
            automation.serviceInfo
            runBlocking {
                snapshotSetup("before-write")
                // One atomic test preparation write; avoid a second setter copying an older flow emission.
                settings.update{it.copy(terminalCommandPreferences=intendedPreference,
                    terminalRenderPreferences=updatedTerminalRenderPreferences(it.terminalRenderPreferences,peer,mode),
                    terminalFullGridCommands="",terminalCustomTuiCommands="sh")}
                snapshotSetup("after-write")
                withTimeout(30_000) { settings.settingsFlowRaw.first {
                    !it.init && configured(it.terminalCommandPreferences,it.terminalRenderPreferences,it.terminalCustomTuiCommands,it.terminalFullGridCommands)
                } }
                withTimeout(30_000) { settings.settingsFlow.first {
                    !it.init && configured(it.terminalCommandPreferences,it.terminalRenderPreferences,it.terminalCustomTuiCommands,it.terminalFullGridCommands)
                } }
                snapshotSetup("persisted-and-flow-confirmed")
                proot.start().getOrThrow()
            }
            val start=runBlocking{manager.startInteractiveSession(sandbox,peer,columns=80,rows=24,ptyMode=if(tui)PtyMode.RAW else PtyMode.COOKED)}
            assertTrue(start.message,start.success);assertEquals("native-pty",start.terminalBackend);val id=start.processId;process=id
            val terminal=requireNotNull(manager.getInteractiveTerminalEmulator(id));val trace=TerminalPipelineTrace.start(id,4096, clock=SystemClock::elapsedRealtimeNanos);capture=trace
            val nav=Navigator(mutableListOf())
            val owner=ActivityScenario.launch<TerminalPipelineTestActivity>(Intent(instrumentation.targetContext,TerminalPipelineTestActivity::class.java));scenario=owner
            owner.onActivity { activity ->
                val main=Handler(Looper.getMainLooper())
                val draw=ViewTreeObserver.OnDrawListener{
                    val began=SystemClock.elapsedRealtimeNanos();observer.draw(activity.window.decorView.drawingTime)
                    main.post{observer.traversalFinished(began)}
                };listener=draw
                activity.window.decorView.viewTreeObserver.addOnDrawListener(draw)
                activity.window.addOnFrameMetricsAvailableListener(observer,handler)
                activity.window.addOnFrameMetricsAvailableListener(mirror,mirrorHandler);attached=true
                activity.setContent {
                    val value by settings.settingsFlow.collectAsState()
                    // Observe the LocalSettings value BEFORE mounting stateful terminal controls.
                    if(!mounted && !value.init && configured(value.terminalCommandPreferences,value.terminalRenderPreferences,
                        value.terminalCustomTuiCommands,value.terminalFullGridCommands)) SideEffect { localSettingsReady.countDown() }
                    RikkahubTheme {
                        CompositionLocalProvider(LocalSettings provides value,LocalNavController provides nav) {
                            // Validate the exact value passed to the FIRST panel, not just a prior flow poll.
                            // After first commit this guard is bypassed; it cannot unmount a measured page.
                            if(mounted && (firstPanelCommitted || (!value.init && configured(value.terminalCommandPreferences,
                                value.terminalRenderPreferences,value.terminalCustomTuiCommands,value.terminalFullGridCommands)))) {
                                if(!firstPanelCommitted) SideEffect { firstPanelCommitted=true }
                                ProcessSessionPage(sandbox,id)
                            }
                        }
                    }
                }
            }
            await("peer READY"){manager.readInteractiveBuffer(id)?.contains("READY\r\n")==true}
            assertTrue("Configured LocalSettings never reached the test host",localSettingsReady.await(30,TimeUnit.SECONDS))
            owner.onActivity{
                val current=settings.settingsFlow.value
                assertTrue("Configuration changed before first mount",configured(current.terminalCommandPreferences,
                    current.terminalRenderPreferences,current.terminalCustomTuiCommands,current.terminalFullGridCommands))
                snapshotSetup("before-mount");mounted=true
            }
            await("initial page"){trace.viewport()?.viewport?.initialized==true && terminal.maxScrollbackLines()==size}
            runBlocking{manager.sendInput(id,"SEED").getOrThrow()}
            await("seeded"){manager.readInteractiveBuffer(id)?.contains("SEEDED\r")==true}
            // Lock the settled physical grid BEFORE opening IME; the keyboard is a viewport change.
            var gridSignature: List<Any?>? = null
            var gridStableSince = SystemClock.elapsedRealtimeNanos()
            var gridPulse = 0L
            await("initial hidden grid stable") {
                val ack=manager.interactiveSessionSize(id)
                val p=trace.viewport()?.viewport
                val signature=listOf(ack?.request,ack?.columns,ack?.rows,terminal.columns,terminal.rows,p?.revision)
                val valid=ack!=null && ack.columns==80 && ack.rows==terminal.rows && terminal.rows>=24 &&
                    !manager.hasPendingTerminalResize(id) && p!=null && p.widthReady && p.geometryReady &&
                    p.historyRows==size && !p.alternate && !p.ime
                if(signature!=gridSignature || !valid) { gridSignature=signature;gridStableSince=SystemClock.elapsedRealtimeNanos() }
                if(!valid && SystemClock.elapsedRealtimeNanos()-gridPulse>100_000_000L) { owner.onActivity{trace.requestViewportDraw()};gridPulse=SystemClock.elapsedRealtimeNanos() }
                valid && SystemClock.elapsedRealtimeNanos()-gridStableSince>=700_000_000L
            }
            val physicalRowsBeforeIme=terminal.rows
            if(expectedIme) {
                var inputNode: AccessibilityNodeInfo? = null
                await("native editor for keyboard setup") { inputNode=editor(); inputNode!=null }
                val input=checkNotNull(inputNode)
                try { tap(input) } finally { input.recycle() }
            }
            fun ready(alternate: Boolean): Boolean {
                val p=trace.viewport()?.viewport ?: return false
                return p.preferred==mode && p.applied==mode && p.virtual==mode.isFullVirtual && p.bindingVirtual==mode.isFullVirtual && p.fullVirtual==mode.isFullVirtual &&
                    p.ime==expectedIme && p.tui==tui && !p.selection && p.alternate==alternate && p.pan && p.sameFrame && p.initialized && p.following &&
                    p.gestureId==null && p.effectId==null && p.writers==0 && !p.scrolling && p.historyRows==(if(alternate)0 else size) &&
                    !manager.hasPendingTerminalResize(id) && (!mode.isFullVirtual || p.widthReady)
            }
            var lastMarker="SEEDED"
            fun settle(alternate: Boolean,phase: String) {
                var signature: List<Any?>?=null;var stableSince=SystemClock.elapsedRealtimeNanos();var lastPulse=0L
                await("stable $phase") {
                    val ack=manager.interactiveSessionSize(id);val p=trace.viewport()?.viewport
                    val next=listOf(ack?.request,ack?.columns,ack?.rows,terminal.columns,terminal.rows,p?.revision,p?.ime)
                    if(next!=signature || !ready(alternate)){signature=next;stableSince=SystemClock.elapsedRealtimeNanos()}
                    // Setup only: refresh the existing observer, never inside a measured input window.
                    if(!ready(alternate) && SystemClock.elapsedRealtimeNanos()-lastPulse>100_000_000L){owner.onActivity{trace.requestViewportDraw()};lastPulse=SystemClock.elapsedRealtimeNanos()}
                    ready(alternate) && ack!=null && ack.rows==terminal.rows && ack.columns==terminal.columns && SystemClock.elapsedRealtimeNanos()-stableSince>=700_000_000L
                }
                await("post-setup native visibility: $phase",3000){visibleText(lastMarker)}
                gates.add(JSONObject().put("phase",phase).put("rows",terminal.rows).put("columns",terminal.columns).put("alternate",alternate).put("ime",expectedIme).put("virtual",true))
            }
            settle(false,"initial");val rows=terminal.rows
            assertEquals("IME changed the physical PTY grid",physicalRowsBeforeIme,rows)
            assertTrue("Row24 protocol target outside grid", rows >= 24)
            val initialHistory=terminal.renderFrame().ownedRows()!!.history
            assertEquals(size,initialHistory.size)
            if(setupOnly) {
                emitCase(JSONObject().put("case",case).put("setupOnly",true).put("acceptance",false).put("passed",true)
                    .put("viewport",trace.viewport()?.viewport.toString()).put("modelRows",terminal.rows).put("modelColumns",terminal.columns)
                    .put("modelHistory",terminal.renderFrame().historyCount).put("expectedIme",expectedIme).put("preparation",JSONArray(preparation))
                    .put("samples",JSONArray()).put("gates",JSONArray(gates)))
                return
            }
            for(round in 0..4) {
                val conditions=if(round%2==0) listOf("refresh","return") else listOf("return","refresh")
                for((position,condition) in conditions.withIndex()) {
                    val phase="r$round-$condition"
                    if(condition=="return") {
                        runBlocking{manager.sendInput(id,"ALT").getOrThrow()}
                        await("alternate marker"){trace.viewport()?.viewport?.alternate==true}
                        lastMarker="ALT-READY";settle(true,"prepare-$phase")
                    } else settle(false,"prepare-$phase")
                    var field: AccessibilityNodeInfo?=null
                    await("editable native node"){field=editor();field!=null}
                    val input=checkNotNull(field)
                    try {
                        assertTrue("Native editor has no IME action",input.actionList.any{it.id==AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id})
                        val expected="ECHO:$case:${round.toString().padStart(2,'0')}:$position"
                        val output=(if(condition=="return") "\u001b[?1049l" else "")+"\u001b[24;1H\u001b[2K$expected\r"
                        val payload=output.toByteArray();val before=trace.beginWindow()
                        assertTrue("Editor detached",input.refresh())
                        val arguments=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,expected)}
                        val clockBefore=JSONObject().put("readStartBootNs",SystemClock.elapsedRealtimeNanos()).put("readEndBootNs",SystemClock.elapsedRealtimeNanos())
                        val began=SystemClock.elapsedRealtimeNanos()
                        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,arguments))
                        assertTrue(input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id))
                        val actionReturned=SystemClock.elapsedRealtimeNanos()
                        var observedEvents: List<TerminalPipelineEvent>?=null;var polls=0
                        await("complete main recovery draw",25_000) {
                            val snapshot=trace.snapshot();polls++
                            val feeds=snapshot.filter{it.stage==TerminalPipelineStage.EMULATOR_FED}
                            val complete=feeds.isNotEmpty() && feeds.sumOf{it.bytes}==payload.size &&
                                snapshot.any{it.stage==TerminalPipelineStage.FRAME_DRAWN && it.frameRevision==feeds.last().frameRevision && it.virtual==true}
                            if(complete)observedEvents=snapshot
                            complete
                        }
                        // Endpoint A is a UI attempt; primary B requires its own exact buffer chain.
                        val initialEvents=checkNotNull(observedEvents).filter{it.sequence>before}
                        val initialFed=initialEvents.filter{it.stage==TerminalPipelineStage.EMULATOR_FED}
                        val exactRevision=initialFed.last().frameRevision
                        val firstAttempt=checkNotNull(initialEvents.firstOrNull {
                            it.stage==TerminalPipelineStage.FRAME_DRAWN && it.frameRevision==exactRevision
                        }) { "No exact response-revision UI attempt" }
                        val tailEnd=firstAttempt.timeNanos+500_000_000L
                        while(SystemClock.elapsedRealtimeNanos()<tailEnd) SystemClock.sleep(2)
                        // The cutoff is the predetermined tail end, NOT a later scheduler wake-up.
                        val closed=tailEnd
                        val events=trace.snapshot().filter{it.sequence>before}
                        val observed=SystemClock.elapsedRealtimeNanos()
                        val ui=checkNotNull(events.singleOrNull{it.stage==TerminalPipelineStage.UI_INPUT}){"Missing/duplicate UI_INPUT"}
                        val feeds=events.filter{it.stage==TerminalPipelineStage.EMULATOR_FED}
                        assertTrue("Finite response missing",manager.readInteractiveBuffer(id)?.contains(output)==true)
                        assertEquals(0L,trace.dropped);assertEquals(payload.size,feeds.sumOf{it.bytes})
                        assertEquals(payload.size,events.filter{it.stage==TerminalPipelineStage.OUTPUT_READ}.sumOf{it.bytes})
                        val revision=feeds.last().frameRevision
                        val published=checkNotNull(events.firstOrNull{it.stage==TerminalPipelineStage.FRAME_PUBLISHED && it.frameRevision==revision})
                        val draw=checkNotNull(events.firstOrNull{it.stage==TerminalPipelineStage.FRAME_DRAWN && it.frameRevision==published.frameRevision})
                        assertTrue(draw.virtual==true);assertEquals(rows,terminal.rows)
                        // Queries and history identity checks are AFTER the measured recovery draw.
                        await("post-recovery native visibility",3000){visibleText(expected)};lastMarker=expected
                        val recovered=terminal.renderFrame()
                        assertFalse(recovered.isAlternateScreen);assertEquals(size,recovered.historyCount)
                        assertSame("Unchanged MAIN history was replaced",initialHistory,recovered.ownedRows()!!.history)
                        samples.add(JSONObject().put("phase",phase).put("index",round).put("condition",condition).put("position",position)
                            .put("warmup",round==0).put("clock","CLOCK_BOOTTIME").put("closedBootNs",closed).put("beganNs",began).put("actionReturnedNs",actionReturned).put("observedNs",observed)
                            .put("uiNs",ui.timeNanos).put("publishedNs",published.timeNanos).put("drawNs",draw.timeNanos)
                            .put("fedRevision",revision).put("publishedRevision",published.frameRevision).put("drawRevision",draw.frameRevision)
                            .put("outputBytes",payload.size).put("outputSha256",hash(output)).put("virtual",true).put("tracePolls",polls)
                            .put("eventCount",events.size).put("visibleAfterMeasurement",true).put("sameInitialHistory",true)
                            .put("events",JSONArray().apply{events.forEach{e->put(JSONObject().put("seq",e.sequence).put("stage",e.stage.name)
                                .put("ns",e.timeNanos).put("bytes",e.bytes).put("revision",e.frameRevision).put("virtual",e.virtual ?: JSONObject.NULL))}}))
                        dumps.add(JSONObject().put("phase",phase).put("clockBefore",clockBefore)
                            .put("clockAfter",JSONObject().put("readStartBootNs",SystemClock.elapsedRealtimeNanos()).put("readEndBootNs",SystemClock.elapsedRealtimeNanos()))
                            .put("gfxinfo",command("dumpsys gfxinfo me.rerere.rikkahub.dev.next.terminaltest framestats")))
                    } finally {input.recycle()}
                }
            }
            owner.onActivity{activity->listener?.let{activity.window.decorView.viewTreeObserver.removeOnDrawListener(it)};activity.window.removeOnFrameMetricsAvailableListener(observer);activity.window.removeOnFrameMetricsAvailableListener(mirror);attached=false}
            val drained=CountDownLatch(2);handler.post{drained.countDown()};mirrorHandler.post{drained.countDown()}
            assertTrue(drained.await(5,TimeUnit.SECONDS));assertEquals(0,observer.overflow);assertEquals(0,mirror.overflow)
            val draws=JSONArray().apply{observer.readDraws().forEach{put(JSONObject().put("ns",it.timeNs).put("drawingTimeMs",it.drawingTimeMs))}}
            val metrics=JSONArray().apply{observer.readMetrics().forEach{put(JSONArray(it.toList()))}}
            emitCase(JSONObject().put("case",case).put("mode",mode.id).put("tui",tui).put("launch",launch).put("pid",android.os.Process.myPid())
                .put("samples",JSONArray(samples)).put("gates",JSONArray(gates)).put("draws",draws).put("metrics",metrics).put("phaseDumps",JSONArray(dumps))
                .put("preparation",JSONArray(preparation)).put("startupReceipt",startup)
                .put("metricsRaw",JSONArray().apply{observer.readRawMetrics().forEach{put(JSONArray(it.toList()))}})
                .put("mirrorMetrics",JSONArray().apply{mirror.readMetrics().forEach{put(JSONArray(it.toList()))}})
                .put("mirrorRaw",JSONArray().apply{mirror.readRawMetrics().forEach{put(JSONArray(it.toList()))}})
                .put("collectorDiagnosisOnly",false).put("performanceMeasured",true).put("protocolId","terminal-p2-identity-performance-v1")
                .put("completedTraversals",JSONArray().apply{observer.readTraversals().forEach{put(JSONArray(it.toList()))}})
                .put("clock", "CLOCK_BOOTTIME").put("historySize",size).put("baselineOnly",false).put("expectedIme",expectedIme).put("droppedFrameMetrics",observer.readMetrics().sumOf{it[9]}).put("passed",true))
        } catch(error: Throwable) {
            runCatching{snapshotSetup("failure")}.onFailure {
                preparation.add(JSONObject().put("stage","failure-diagnostic").put("error",it.toString()))
            }
            val model=process?.let{manager.getInteractiveTerminalEmulator(it)}
            val info=model?.renderFrame()
            emitCase(JSONObject().put("case",case).put("setupOnly",setupOnly).put("acceptance",false).put("passed",false).put("error",error.toString()).put("samples",JSONArray(samples))
                .put("preparation",JSONArray(preparation)).put("maxScrollbackLines",model?.maxScrollbackLines())
                .put("viewport",capture?.viewport()?.viewport.toString()).put("modelRows",model?.rows).put("modelColumns",model?.columns)
                .put("modelRevision",info?.revision).put("modelHistory",info?.historyCount)
                .put("resizePending",process?.let{manager.hasPendingTerminalResize(it)})
                .put("lastEvents",JSONArray().apply{capture?.snapshot()?.takeLast(96)?.forEach{e->put(JSONObject().put("seq",e.sequence).put("stage",e.stage.name).put("ns",e.timeNanos).put("bytes",e.bytes).put("revision",e.frameRevision))}}))
            if(setupOnly)return
            throw error
        } finally {
            try {
                scenario?.onActivity{activity->mounted=false;if(attached){listener?.let{activity.window.decorView.viewTreeObserver.removeOnDrawListener(it)};activity.window.removeOnFrameMetricsAvailableListener(observer);activity.window.removeOnFrameMetricsAvailableListener(mirror);attached=false}}
                scenario?.close()
            } finally {
                capture?.close();thread.quitSafely();mirrorThread.quitSafely();thread.join(5000);mirrorThread.join(5000)
                try{process?.let{id->runBlocking{manager.closeInteractiveSession(id)};manager.removeProcessRecord(id)}}
                finally{runBlocking{settings.update{it.copy(terminalCommandPreferences=saved.terminalCommandPreferences,terminalRenderPreferences=saved.terminalRenderPreferences,
                    terminalCustomTuiCommands=saved.terminalCustomTuiCommands,terminalFullGridCommands=saved.terminalFullGridCommands)}}}
                emit("CLEANUP",JSONObject().put("case",case).put("closed",process?.let{manager.getInteractiveTerminalEmulator(it)==null} ?: true).put("threadStopped",!thread.isAlive && !mirrorThread.isAlive))
            }
        }
    }
}
