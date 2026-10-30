package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.concurrent.ConcurrentLinkedQueue
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**16bounded publications,not a repeat of the40performance windows. Manual Compose clock is explicit. */
class TerminalFullVirtualCursorTimingDiagnosticTest {
    @get:Rule val compose=createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout=Timeout.seconds(120)
    @Test fun cursorDiagnosticPublicationToActualDrawWithoutAutoAdvance() {
        var shown by mutableStateOf<Fixture?>(null,referentialEqualityPolicy())
        compose.setContent{shown?.Content()}
        for(overlay in listOf(false,true)) {
            val terminal=TerminalEmulator(80,24,1000).apply {
                feed("\u001b[?25l"+(0 until 1024).joinToString("\r\n"){"abcdef 中文 e\u0301 👩‍💻 אבג xyz row$it"})
                feed("\u001b[6 q\u001b[22;3H\u001b[?25h")
            }
            val draws=ConcurrentLinkedQueue<Pair<Long,Long>>()
            val f=Fixture(stressSpans=false,initialMode=TerminalRenderMode.FULL_VIRTUAL,terminalOverride=terminal,
                restored=TerminalViewportState(autoScroll=true),foreground=Color.Green,cursorOverlayExperiment=overlay).apply {
                tui=true;fontSp=12;heightDp=240;densityOverride=Density(1f)
                diagnosticDrawObserver={revision->draws.add(revision to System.nanoTime())}
            }
            compose.runOnIdle{shown=f};compose.waitForIdle()
            compose.waitUntil(30_000){f.controller.state.value.initialized && f.observation()?.ready==true && f.activeWriters==0}
            compose.waitForIdle()
            compose.mainClock.autoAdvance=false
            try {
                repeat(8) { step ->
                    draws.clear();val clockBefore=compose.mainClock.currentTime
                    var began=0L;var published=0L;var target=0L
                    compose.runOnUiThread {
                        began=System.nanoTime();terminal.feed("\u001b[${21+step%3};${2+step}H");f.publish()
                        target=f.frame.revision;published=System.nanoTime()
                    }
                    var advanced=0
                    while(draws.none{it.first==target} && advanced<8) {
                        compose.mainClock.advanceTimeByFrame();advanced++;compose.waitForIdle()
                    }
                    val returned=System.nanoTime();val drawn=draws.firstOrNull{it.first==target}
                    assertNotNull("No actual target draw within8explicit frames",drawn)
                    assertTrue(f.isVirtual && f.rowStore.retainedRowStates<=80 && f.measurements.retainedRows<=128 && f.maximumWriters<=1)
                    Log.i("TerminalCursorDiagnostic","CURSOR_DRAW_DIAGNOSTIC "+JSONObject().put("overlay",overlay).put("step",step)
                        .put("revision",target).put("beganNs",began).put("publishedNs",published).put("drawnNs",drawn!!.second)
                        .put("returnedNs",returned).put("explicitFrames",advanced).put("clockBeforeMs",clockBefore)
                        .put("clockAfterMs",compose.mainClock.currentTime).put("targetDraws",draws.count{it.first==target})
                        .put("writers",f.maximumWriters).put("virtual",true))
                }
            } finally {compose.mainClock.autoAdvance=true}
            compose.runOnIdle{shown=null};compose.waitForIdle();assertEquals(0,f.measurements.retainedRows)
        }
    }
}
