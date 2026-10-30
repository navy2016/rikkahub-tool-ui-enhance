package me.rerere.rikkahub.viewporttest

import android.content.ClipboardManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.security.MessageDigest
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Regression assertions for the diagnosed motion-intent defect; never a performance resample. */
class TerminalSelectionAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(240)
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val clipboard get() = compose.activity.getSystemService(ClipboardManager::class.java)
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun terminal() = TerminalEmulator(80,24,10000).apply {
        feed((0 until 10023).joinToString("\r\n") { "word$it 中文 e\u0301 👩‍💻" } + "\r\n")
    }
    private fun fixture(term: TerminalEmulator = terminal(), saved: TerminalViewportState? = null): Fixture {
        val frame = term.renderFrame()
        val restored = saved ?: TerminalViewportState(autoScroll=false, anchorLineId=frame.historyLineIds[500],
            anchorClippedTopPx=0,anchorHistoryGeneration=frame.historyGeneration)
        return Fixture(stressSpans=false,terminalOverride=term,initialMode=TerminalRenderMode.FULL_VIRTUAL,restored=restored,
            foreground=Color(0xFF00E676)).apply {
            fontSp=12;heightDp=240;densityOverride=Density(1.33f,1.3f);config=config.copy(selectionMode=true)
        }
    }
    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            val s=f.controller.state.value
            s.initialized && s.gesture==null && s.scrollEffect==null && f.activeWriters==0 &&
                (if(f.isVirtual) f.observation()?.ready==true else f.bound.binding.eagerGeometry()!=null)
        }
        compose.waitForIdle()
    }
    private fun copy(expected: String) {
        compose.waitForIdle()
        val copy=checkNotNull(device.wait(Until.findObject(By.text(compose.activity.getString(android.R.string.copy))),5000))
        val all=device.findObject(By.text(compose.activity.getString(android.R.string.selectAll)))
        val handles=compose.onAllNodes(SemanticsMatcher("selection grip") { node ->
            node.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("terminal-selection-")
        },true).fetchSemanticsNodes()
        assertTrue(handles.isNotEmpty())
        for(handle in handles) {
            val x=(handle.positionOnScreen.x+handle.size.width/2f).toInt()
            val y=(handle.positionOnScreen.y+handle.size.height/2f).toInt()
            assertFalse("Copy menu intercepts the actual grip center",copy.visibleBounds.contains(x,y))
            assertFalse("Select-all menu intercepts the actual grip center",all?.visibleBounds?.contains(x,y)==true)
        }
        Log.i("TerminalSelectionAcceptance","SELECTION_REPAIR_MENU "+JSONObject().put("avoidsHandles",true)
            .put("handles",handles.size).put("expectedLength",expected.length))
        copy.click()
        compose.waitForIdle()
        compose.waitUntil(5000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString()==expected }
    }
    private fun row(f: Fixture, bottom: Boolean=false): Int {
        val rows=f.observation()!!.rows.filter { it.topPx>=0 && it.bottomPx<=f.viewportHeight &&
            f.frame.rows[it.index].text.text.isNotBlank() }
        return (if(bottom) rows.last() else rows.first()).index
    }
    private fun selectable(f: Fixture,index: Int) = compose.onNode(
        SemanticsMatcher.expectValue(SemanticsProperties.Text,listOf(f.frame.rows[index].text)),true)
    private fun layouts(f: Fixture,index: Int): TextLayoutResult {
        val results=ArrayList<TextLayoutResult>();val node=selectable(f,index).fetchSemanticsNode()
        compose.runOnIdle { assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!(results)) }
        return results.single()
    }
    private fun word(f: Fixture,index: Int): String {
        val before=f.top();val result=layouts(f,index)
        selectable(f,index).performTouchInput { longClick(result.getBoundingBox(3).center,1500) }
        compose.waitForIdle()
        assertEquals("Stationary long-press changed the reader anchor",before,f.top())
        val expected=f.frame.rows[index].text.text.substringBefore(' ')
        copy(expected)
        return expected
    }
    private fun record(f: Fixture,phase: String,index: Int,copied: String) {
        assertTrue(f.isVirtual);assertTrue(f.bound.binding.fullVirtual)
        assertEquals(f.frame.screenLineIds.size,f.rowStore.retainedRowStates)
        assertTrue(f.measurements.retainedRows<=128);assertTrue(f.maximumWriters<=1)
        val node=selectable(f,index).fetchSemanticsNode()
        val view=compose.onNodeWithTag("item-output").fetchSemanticsNode()
        val top=node.positionInRoot.y-view.positionInRoot.y
        assertTrue(top>=-1 && top+node.size.height<=view.size.height+1)
        Log.i("TerminalSelectionAcceptance","SELECTION_REPAIR_ROW "+JSONObject().put("phase",phase)
            .put("virtual",f.isVirtual).put("rowIndex",index).put("historyRows",f.frame.historyCount)
            .put("selection",f.config.selectionMode).put("pan",f.config.panEnabled).put("ime",f.ime)
            .put("alternate",f.frame.isAlternateScreen).put("tui",f.tui).put("fullGrid",f.fullGrid)
            .put("rowStates",f.rowStore.retainedRowStates).put("measuredRows",f.measurements.retainedRows)
            .put("writers",f.maximumWriters).put("length",copied.length).put("sha256",hash(copied))
            .put("top",top.toDouble()).put("bottom",(top+node.size.height).toDouble())
            .put("rowHeight",node.size.height).put("viewport",view.size.height))
    }
    private fun done(name: String) { Log.i("TerminalSelectionAcceptance","SELECTION_REPAIR_CASE "+JSONObject().put("case",name).put("passed",true)) }

    @Test fun selectionRepairStationaryEdgesKeepExactWords() {
        val f=fixture();var shown by mutableStateOf(true)
        compose.setContent { if(shown) f.Content() };settle(f)
        var edges=0
        compose.runOnIdle { f.bound.binding.onSelectionTrace={stage,_->if(stage=="edge-request")edges++} }
        for(bottom in listOf(false,true)) {
            val at=row(f,bottom);val text=word(f,at)
            assertEquals("Still finger scheduled selection edge scrolling",0,edges)
            record(f,if(bottom)"stationary-bottom" else "stationary-top",at,text)
        }
        compose.runOnIdle { f.bound.binding.onSelectionTrace=null;shown=false };compose.waitForIdle()
        assertEquals(0,f.measurements.retainedRows);done("stationary")
    }

    @Test fun selectionRepairReverseGraphemesAndPopupGeometry() {
        val f=fixture();var shown by mutableStateOf(true)
        compose.setContent { if(shown)f.Content() };settle(f)
        for(rtl in listOf(false,true)) {
            compose.runOnIdle { f.rtl=rtl };settle(f)
            val at=row(f);val value=f.frame.rows[at].text.text;val from=value.indexOf("e\u0301")
            val textLayout=layouts(f,at);val node=selectable(f,at).fetchSemanticsNode()
            compose.runOnIdle { assertTrue(node.config[SemanticsActions.SetSelection].action!!(value.length,from,false)) }
            compose.waitForIdle()
            val end=compose.onNodeWithTag("terminal-selection-end").fetchSemanticsNode()
            // Reversed LTR substring's end handle points left, including under RTL layout defaults.
            val point=node.positionOnScreen.x+textLayout.getHorizontalPosition(from,true)
            assertEquals("Popup density/width moved the logical endpoint",point.toDouble(),
                (end.positionOnScreen.x+end.size.width).toDouble(),1.1)
            val expected=value.substring(from)
            assertTrue(expected.contains("e\u0301") && expected.contains("👩‍💻"))
            copy(expected);record(f,if(rtl)"reverse-rtl" else "reverse-ltr",at,expected)
        }
        compose.runOnIdle { shown=false };compose.waitForIdle();assertEquals(0,f.measurements.retainedRows);done("reverse")
    }

    @Test fun selectionRepairSceneLifecycleAndClipboardCompatibility() {
        val term=terminal();var f=fixture(term)
        var shown by mutableStateOf<Fixture?>(f,referentialEqualityPolicy())
        compose.setContent { shown?.Content() };settle(f)
        for(phase in listOf("ordinary","tui","ime","mouse","fullGrid","alternate","main")) {
            compose.runOnIdle {
                f.tui=phase=="tui";f.ime=phase=="ime";f.heightDp=if(f.ime)140 else 240
                f.config=f.config.copy(panEnabled=phase!="mouse",selectionMode=true);f.fullGrid=phase=="fullGrid"
                if(phase=="alternate") term.feed("\u001b[?1049h"+(1..24).joinToString(""){ "\u001b[$it;1Haltword$it 中文 e\u0301 👩‍💻" })
                if(phase=="main") term.feed("\u001b[?1049l")
                f.publish()
            }
            settle(f);val index=row(f);record(f,phase,index,word(f,index))
            assertEquals(if(phase=="alternate")0 else 10000,f.frame.historyCount)
        }
        val anchor=f.top().anchor.lineId
        for(mode in listOf(TerminalRenderMode.CHUNKED_LAYERS,TerminalRenderMode.FULL_VIRTUAL)) {
            compose.runOnIdle { f.mode=mode;f.publish() };settle(f)
            assertEquals(anchor,f.top().anchor.lineId)
        }
        var at=row(f);record(f,"handoff",at,word(f,at))
        val saved=compose.runOnIdle { f.snapshotViewport() }
        compose.runOnIdle { shown=null };compose.waitForIdle();assertEquals(0,f.measurements.retainedRows)
        f=fixture(term,saved)
        compose.runOnIdle { shown=f };settle(f);assertEquals(anchor,f.top().anchor.lineId)
        at=row(f);record(f,"reentry",at,word(f,at))
        compose.runOnIdle { shown=null };compose.waitForIdle()
        assertEquals(0,f.measurements.retainedRows);assertEquals(0,f.pointerMap.retainedRows);done("scenes")
    }
}
