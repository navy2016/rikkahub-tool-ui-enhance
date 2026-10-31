package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import java.security.MessageDigest
import me.rerere.rikkahub.data.container.TerminalRenderMode
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.TerminalFullVirtualMechanismProbe as Probe
import me.rerere.rikkahub.ui.pages.container.TerminalSparseRows
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.viewporttest.TerminalItemViewportInstrumentedTest.Fixture
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** P1 candidate correctness/mechanism only; no wall-clock performance inference. */
class TerminalFullVirtualContentTest {
    @get:Rule val compose=createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout=Timeout.seconds(420)
    private fun emit(tag:String,value:JSONObject)=Log.i("TerminalFullContent","FULL_CONTENT_$tag $value")
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun settle(f:Fixture) {
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            val s=f.controller.state.value
            s.initialized && s.gesture==null && s.scrollEffect==null && f.activeWriters==0 &&
                f.bound.pass.frame===f.frame && f.observation()?.ready==true
        }
        compose.waitForIdle()
        assertTrue(f.isVirtual);assertTrue(f.rowStore.retainedRowStates<=80)
        assertTrue(f.measurements.retainedRows<=128);assertEquals(0,f.measurements.retainedEagerHistoryRows)
        assertTrue(f.maximumWriters<=1)
    }
    private data class State(val frame:Any,val screen:Any,val ids:List<Long>,val text:Map<Long,AnnotatedString>,
        val metric:Any,val measured:Long,val notifications:Long,val effects:Int)
    private fun state(f:Fixture):State {
        val rows=f.rows as TerminalSparseRows
        return State(f.frame,rows.screen,rows.screen.map{it.lineId},rows.screen.associate{it.lineId to it.text},
            f.bound.pass.metricKey,f.measurements.measuredRowCount,f.measurements.publishedNotifications,f.bound.binding.effectCount)
    }
    private fun layouts(f:Fixture):Map<Long,TextLayoutResult> {
        val visible=f.observation()!!.rows.map{it.index}.filter{it>=f.frame.historyCount}.map{f.rows[it]}
        val nodes=compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),true).fetchSemanticsNodes()
        val result=LinkedHashMap<Long,TextLayoutResult>()
        for(row in visible) {
            // Duplicate/blank texts are not guessed into row identities; report eligible pairs only.
            if(visible.count{it.text==row.text}!=1)continue
            val match=nodes.filter{it.config.getOrElse(SemanticsProperties.Text){emptyList()}==listOf(row.text)}
            if(match.size!=1)continue
            val values=ArrayList<TextLayoutResult>()
            compose.runOnUiThread{assertTrue(match.single().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(values))}
            if(values.size==1)result[row.lineId]=values.single()
        }
        return result
    }
    private fun sample(f:Fixture,label:String,repeat:Int=0,action:()->Unit) {
        val previous=compose.runOnIdle{state(f)};val oldLayout=layouts(f)
        compose.runOnIdle{Probe.beginWindow();action()};settle(f)
        val current=compose.runOnIdle{state(f)};val counts=compose.runOnIdle{Probe.snapshot()};val nextLayout=layouts(f)
        val changed=current.text.keys.filter{previous.text[it]!=current.text[it]}.toSet()
        val unchanged=current.text.keys.filter{it in previous.text && previous.text[it]==current.text[it]}.toSet()
        val common=oldLayout.keys.intersect(nextLayout.keys).filter{it in unchanged && previous.metric==current.metric}
        val paragraphChanges=common.count{oldLayout[it]!!.multiParagraph !== nextLayout[it]!!.multiParagraph}
        if(label in listOf("sameFrame","republish","text"))assertTrue("Immutable physical list changed without membership/order change",previous.screen===current.screen)
        if(label=="sameFrame")assertTrue(previous.frame===current.frame)
        if(label=="republish" || label=="text")assertTrue(previous.frame!==current.frame)
        if(label=="text")assertEquals(1,changed.size)
        if(label in listOf("sameFrame","republish")) {
            assertEquals(0,changed.size);assertTrue(common.isNotEmpty());assertEquals(0,paragraphChanges)
        }
        val expectedFast=f.frame.isAlternateScreen && f.config.panEnabled && !f.config.selectionMode
        assertEquals("Incorrect content branch: $label",expectedFast,Probe.boundedItemsUsed)
        if(label in listOf("sameFrame","republish","text")) {
            assertEquals("Content changed for a stable physical structure",0,counts.definitionChanges)
            assertEquals("Intervals rebuilt for stable physical structure",0,counts.intervalBuilds)
            assertEquals("Unchanged items recomposed",0,counts.items.filterKeys{it in unchanged}.values.sum())
            assertEquals("Unchanged Row recomposed",0,counts.rows.filterKeys{it in unchanged}.values.sum())
            assertEquals("Unchanged paragraph rebuilt",0,paragraphChanges)
        }
        if(label in listOf("insert","delete","font","scale","rtl","selectionOn","selectionOff","mouseOn","mouseOff","main","alternateAgain","resize"))
            assertTrue("Required invalidation missing: $label",counts.definitionChanges>0)
        emit("SAMPLE",JSONObject().put("fastPath",Probe.boundedItemsUsed).put("scene",label).put("repeat",repeat).put("alternate",f.frame.isAlternateScreen)
            .put("sameScreenIdentity",previous.screen===current.screen).put("sameFrameIdentity",previous.frame===current.frame)
            .put("sameMetric",previous.metric==current.metric).put("sameIds",previous.ids==current.ids)
            .put("changedRows",JSONArray(changed.toList())).put("unchangedRows",JSONArray(unchanged.toList()))
            .put("definitions",counts.definitions).put("definitionChanges",counts.definitionChanges).put("screenChanges",counts.screenChanges)
            .put("frameChanges",counts.frameChanges).put("intervalBuilds",counts.intervalBuilds).put("itemAttempts",counts.itemAttempts)
            .put("items",JSONObject(counts.items.mapKeys{it.key.toString()})).put("rowCompositions",JSONObject(counts.rows.mapKeys{it.key.toString()}))
            .put("unchangedItemCommits",counts.items.filterKeys{it in unchanged}.values.sum())
            .put("unchangedRowCommits",counts.rows.filterKeys{it in unchanged}.values.sum())
            .put("rowMeasurements",current.measured-previous.measured).put("notifications",current.notifications-previous.notifications)
            .put("scrollEffects",current.effects-previous.effects).put("eligibleParagraphs",common.size).put("changedParagraphs",paragraphChanges)
            .put("rowStates",f.rowStore.retainedRowStates).put("measuredRows",f.measurements.retainedRows).put("maxWriters",f.maximumWriters)
            .put("physicalTextSha256",hash(current.ids.joinToString("\n"){current.text.getValue(it).text})))
    }
    @Test fun fullVirtualContentIdentityAndWork() {
        val t=TerminalEmulator(80,24,1000).apply {
            feed("\u001b[?25l"+(0 until 1030).joinToString("\r\n"){"main-$it 中文 e\u0301 👩‍💻"}+"\r")
            feed("\u001b[?1049h\u001b[?25l"+(0 until 24).joinToString("\r\n"){"alt-${it.toString().padStart(2,'0')} 中文 e\u0301 👩‍💻"}+"\r")
        }
        val f=Fixture(stressSpans=false,initialMode=TerminalRenderMode.FULL_VIRTUAL,terminalOverride=t,
            restored=TerminalViewportState(autoScroll=true),foreground=Color(0xFF00E676)).apply {
            tui=true;fontSp=12;heightDp=420;densityOverride=Density(1f)
        }
        var shown by mutableStateOf<Fixture?>(null,referentialEqualityPolicy())
        Probe.start();f.measurements.onRowComposed=Probe::rowCommitted
        try {
            compose.setContent{Box(Modifier.background(Color.Black)){shown?.Content()}}
            compose.runOnIdle{shown=f};settle(f)
            repeat(3){sample(f,"sameFrame",it){}}
            repeat(3){sample(f,"republish",it){f.publish()}}
            repeat(4){i->sample(f,"text",i){t.feed("\u001b[20;1H\u001b[2Kedit-$i 中文 e\u0301 👩‍💻\r");f.publish()}}
            sample(f,"cursor"){t.feed("\u001b[?25h\u001b[21;5H");f.publish()}
            sample(f,"insert"){t.feed("\u001b[8;1H\u001b[L");f.publish()}
            sample(f,"delete"){t.feed("\u001b[9;1H\u001b[M");f.publish()}
            sample(f,"font"){f.fontSp=15}
            sample(f,"scale"){f.densityOverride=Density(1f,1.3f)}
            sample(f,"rtl"){f.rtl=true}
            sample(f,"ime"){f.ime=true;f.heightDp=240}
            sample(f,"imeHidden"){f.ime=false;f.heightDp=420}
            sample(f,"selectionOn"){f.config=TerminalViewportGestureConfig(true,true,2)}
            sample(f,"selectionOff"){f.config=TerminalViewportGestureConfig(true,false,2)}
            sample(f,"mouseOn"){f.config=TerminalViewportGestureConfig(false,false,2)}
            sample(f,"mouseOff"){f.config=TerminalViewportGestureConfig(true,false,2)}
            sample(f,"main"){t.feed("\u001b[?1049l");f.publish()}
            sample(f,"alternateAgain"){t.feed("\u001b[?1049h");f.publish()}
            sample(f,"resize"){t.resize(72,18);f.publish()}
            compose.runOnIdle{shown=null};compose.waitForIdle()
            assertEquals(0,f.measurements.retainedRows);assertEquals(0,f.pointerMap.retainedRows)
            emit("DONE",JSONObject().put("samples",25).put("retainedRows",f.measurements.retainedRows).put("pointerRows",f.pointerMap.retainedRows)
                .put("performanceMeasured",false).put("simulatedIme",true).put("passed",true))
        } finally {compose.runOnIdle{shown=null;f.measurements.onRowComposed=null};compose.waitForIdle();Probe.stop()}
    }

    private fun boundaryFixture(unowned:Boolean=false):Fixture {
        val t=TerminalEmulator(80,24,1000).apply {
            feed("\u001b[?1049h\u001b[?25l"+(0 until 24).joinToString("\r\n"){"alt-${it.toString().padStart(2,'0')} 中文 e\u0301 👩‍💻"}+"\r")
        }
        return Fixture(stressSpans=false,initialMode=TerminalRenderMode.FULL_VIRTUAL,terminalOverride=t,
            restored=TerminalViewportState(autoScroll=true),foreground=Color(0xFF00E676),contentUnownedControl=unowned).apply {
            tui=true;fontSp=12;heightDp=420;densityOverride=Density(1f)
        }
    }
    private fun pixels():Pair<Int,IntArray> {
        val image=compose.onNodeWithTag("item-output").captureToImage();val map=image.toPixelMap()
        return image.width to IntArray(image.width*image.height){map[it%image.width,it/image.width].toArgb()}
    }
    @Test fun fullVirtualContentBoundaryChecks() {
        var shown by mutableStateOf<Fixture?>(null,referentialEqualityPolicy())
        Probe.start()
        try {
            compose.setContent{Box(Modifier.background(Color.Black)){shown?.Content()}}
            val owned=boundaryFixture()
            compose.runOnIdle{shown=owned};settle(owned);assertTrue(Probe.boundedItemsUsed)
            val expected=pixels()
            compose.runOnIdle{shown=null};compose.waitForIdle();assertEquals(0,owned.measurements.retainedRows)
            val unowned=boundaryFixture(true)
            compose.runOnIdle{shown=unowned};settle(unowned);assertFalse(Probe.boundedItemsUsed)
            val actual=pixels();assertEquals(expected.first,actual.first);assertArrayEquals(expected.second,actual.second)
            emit("BOUNDARY",JSONObject().put("case","unownedFallback").put("passed",true).put("pixels",actual.second.size))
            compose.runOnIdle{shown=null};compose.waitForIdle();assertEquals(0,unowned.measurements.retainedRows)
            val f=boundaryFixture()
            compose.runOnIdle{shown=f};settle(f)
            val previous=compose.runOnIdle{f.rows[23].text}
            compose.runOnIdle{f.terminal.feed("\u001b[24;1H\u001b[2K");f.publish(false,1000L)};settle(f)
            val heldFrame=compose.runOnIdle{f.frame};assertEquals(previous,compose.runOnIdle{f.rows[23].text})
            compose.runOnIdle{Probe.beginWindow();f.publish(true,1050L)};settle(f)
            assertEquals(heldFrame.revision,f.frame.revision)
            assertTrue(compose.runOnIdle{f.rows[23].text.text.isBlank()})
            assertEquals(0,Probe.snapshot().definitionChanges)
            emit("BOUNDARY",JSONObject().put("case","sameFrameBlankGrace").put("passed",true).put("sameRevision",true))
            compose.runOnIdle{shown=null};compose.waitForIdle();assertEquals(0,f.measurements.retainedRows)
        } finally {compose.runOnIdle{shown=null};compose.waitForIdle();Probe.stop()}
    }
}
