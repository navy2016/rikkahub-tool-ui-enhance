package me.rerere.rikkahub.pipeline

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream

/** API34 collector diagnosis only, no terminal/cache candidate or performance acceptance. */
class TerminalFrameMetricsAuditTest {
    @get:Rule val timeout=Timeout.seconds(150)
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun await(label:String, condition:()->Boolean) {
        val deadline=System.nanoTime()+30_000_000_000L
        while(!condition()) { if(System.nanoTime()>=deadline)fail("Frame audit timeout: $label");SystemClock.sleep(2) }
    }
    private fun dump()=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
        "dumpsys gfxinfo me.rerere.rikkahub.dev.next.terminaltest framestats")).bufferedReader().use{it.readText()}
    private fun emit(value:JSONObject) {
        val raw=value.toString().toByteArray();val hash=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}
        val bytes=ByteArrayOutputStream();DeflaterOutputStream(bytes).use{it.write(raw)}
        val parts=Base64.getEncoder().encodeToString(bytes.toByteArray()).chunked(2600)
        check(parts.size<=128)
        parts.forEachIndexed { i,part -> instrumentation.sendStatus(0,Bundle().apply {
            putString("stream","\nFRAME_AUDIT_PART "+JSONObject().put("part",i+1).put("parts",parts.size).put("sha256",hash).put("data",part)+"\n")
        }) }
    }
    private class Capture:Window.OnFrameMetricsAvailableListener {
        private val rawField=FrameMetrics::class.java.getDeclaredField("mTimingData").apply{isAccessible=true}
        private val metrics=ArrayList<JSONObject>()
        private val requests=ArrayList<JSONObject>()
        private val draws=ArrayList<JSONObject>()
        private val commits=ArrayList<JSONObject>()
        @Synchronized override fun onFrameMetricsAvailable(window:Window,value:FrameMetrics,dropped:Int) {
            check(metrics.size<4096)
            val copy=(rawField.get(value) as LongArray).clone()
            metrics.add(JSONObject().put("ns",System.nanoTime()).put("raw",JSONArray(copy.toList())).put("dropped",dropped)
                .put("publicVsync",value.getMetric(FrameMetrics.VSYNC_TIMESTAMP))
                .put("publicIntended",value.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)))
        }
        @Synchronized fun request(phase:String,index:Int) {
            check(requests.size<512);requests.add(JSONObject().put("phase",phase).put("index",index).put("ns",System.nanoTime()))
        }
        @Synchronized fun draw(phase:String,index:Int) {
            check(draws.size<512);draws.add(JSONObject().put("phase",phase).put("index",index).put("ns",System.nanoTime()))
        }
        @Synchronized fun commit(phase:String,index:Int) {
            check(commits.size<512);commits.add(JSONObject().put("phase",phase).put("index",index).put("ns",System.nanoTime()))
        }
        @Synchronized fun count(phase:String)=draws.count{it.getString("phase")==phase}
        @Synchronized fun json()=JSONObject().put("requests",JSONArray(requests)).put("draws",JSONArray(draws))
            .put("commits",JSONArray(commits)).put("metrics",JSONArray(metrics))
    }
    private class AuditView(context:Context,val capture:Capture):View(context) {
        val paint=Paint();var phase="";var index=0;var paintIndex=0;var interval=0L;var active=false;var pending=false
        private fun request() {
            check(active && !pending);pending=true;paintIndex=index
            val p=phase;val n=index
            capture.request(p,n)
            viewTreeObserver.registerFrameCommitCallback { capture.commit(p,n) }
            invalidate()
        }
        fun start(value:String,delayMs:Long) {
            check(!active && !pending);phase=value;index=0;interval=delayMs;active=true;request()
        }
        override fun onDraw(canvas:Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(if(paintIndex%2==0)Color.DKGRAY else Color.BLACK)
            // Fixed visible damage, not changes in terminal content, font, IME or global policy.
            for(y in 0 until 32)for(x in 0 until 24) {
                paint.color=Color.rgb((x*11+paintIndex*17)%256,(y*7+paintIndex*13)%256,(x+y+paintIndex*19)%256)
                val left=x*width/24f;val top=y*height/32f
                canvas.drawRect(left,top,left+width/26f,top+height/35f,paint)
            }
            if(!active || !pending)return
            pending=false
            capture.draw(phase,index);index++
            if(index==96)active=false
            else if(interval==0L)postOnAnimation{request()} else postDelayed({request()},interval)
        }
    }
    @Test fun auditWindowRawMetricsAndCommitCallbacks() {
        instrumentation.uiAutomation.serviceInfo
        val capture=Capture();val thread=HandlerThread("frame-audit-metrics").apply{start()};val handler=Handler(thread.looper)
        val phases=JSONArray();var view:AuditView?=null;var attached=false;var removedNs=0L
        val scenario=ActivityScenario.launch<TerminalPipelineTestActivity>(Intent(instrumentation.targetContext,TerminalPipelineTestActivity::class.java))
        try {
            scenario.onActivity { activity ->
                view=AuditView(activity,capture);activity.setContentView(view)
                activity.window.addOnFrameMetricsAvailableListener(capture,handler);attached=true
            }
            await("nonzero window"){view?.let{it.width>0 && it.height>0 && it.isHardwareAccelerated}==true}
            for((phase,delay) in listOf("paced" to 40L,"vsync" to 0L)) {
                val before=JSONObject().put("monoNs",System.nanoTime()).put("bootNs",SystemClock.elapsedRealtimeNanos())
                scenario.onActivity{view!!.start(phase,delay)}
                await("96 drawn $phase"){capture.count(phase)==96}
                // Only collection/drain; no extra redraw or replacement for missing metrics.
                SystemClock.sleep(250)
                phases.put(JSONObject().put("phase",phase).put("clockBefore",before)
                    .put("clockAfter",JSONObject().put("monoNs",System.nanoTime()).put("bootNs",SystemClock.elapsedRealtimeNanos()))
                    .put("gfxinfo",dump()))
            }
            scenario.onActivity{activity->activity.window.removeOnFrameMetricsAvailableListener(capture);attached=false;removedNs=System.nanoTime()}
            val drained=CountDownLatch(1);handler.post{drained.countDown()};assertTrue(drained.await(5,TimeUnit.SECONDS))
            emit(capture.json().put("pid",Process.myPid()).put("phases",phases).put("passed",true)
                .put("listenerRemovedNs",removedNs).put("handlerDrainedNs",System.nanoTime())
                .put("performanceMeasured",false).put("noTerminalCandidate",true))
        } finally {
            if(attached)scenario.onActivity{it.window.removeOnFrameMetricsAvailableListener(capture)}
            scenario.close();thread.quitSafely();thread.join(5000)
            assertFalse("Frame audit handler leaked",thread.isAlive)
        }
    }
}
