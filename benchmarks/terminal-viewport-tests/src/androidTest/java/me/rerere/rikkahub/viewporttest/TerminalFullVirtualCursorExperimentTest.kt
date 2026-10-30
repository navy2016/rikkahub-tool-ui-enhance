package me.rerere.rikkahub.viewporttest

import android.os.Build
import android.os.Process
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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

/** An isolated adapter experiment: production55db111 is unchanged and this is NOT a phone/PTY test. */
class TerminalFullVirtualCursorExperimentTest {
    @get:Rule val compose=createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout=Timeout.seconds(480)
    private fun hash(raw: ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}
    private fun emit(kind: String,data: JSONObject)=Log.i("TerminalCursorExperiment","CURSOR_VIRTUAL_$kind $data")
    private fun fixture(size: Int,overlay: Boolean,shape: Int): Fixture {
        val terminal=TerminalEmulator(80,24,size).apply {
            feed("\u001b[?25l"+(0 until size+24).joinToString("\r\n") { "abcdef 中文 e\u0301 👩‍💻 אבג xyz row$it" })
            feed("\u001b[$shape q\u001b]12;#ff0000\u0007\u001b[22;3H\u001b[?25h")
        }
        return Fixture(stressSpans=false,initialMode=TerminalRenderMode.FULL_VIRTUAL,terminalOverride=terminal,
            restored=TerminalViewportState(autoScroll=true),foreground=Color.Green,cursorOverlayExperiment=overlay).apply {
            tui=true;fontSp=12;heightDp=240;densityOverride=Density(1f)
        }
    }
    private fun settle(f: Fixture) {
        compose.waitForIdle()
        compose.waitUntil(30_000) {
            val state=f.controller.state.value
            state.initialized && state.gesture==null && state.scrollEffect==null && f.activeWriters==0 && f.observation()?.ready==true
        }
        compose.waitForIdle()
        assertTrue(f.isVirtual);assertTrue(f.rowStore.retainedRowStates<=80);assertTrue(f.measurements.retainedRows<=128)
        assertTrue(f.maximumWriters<=1);assertEquals(0,f.measurements.retainedEagerHistoryRows)
    }
    private fun layout(f: Fixture,physicalRow: Int): TextLayoutResult {
        val text=f.rows[f.frame.historyCount+physicalRow].text
        val node=compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text,listOf(text)),true).fetchSemanticsNodes().single()
        val out=ArrayList<TextLayoutResult>()
        compose.runOnUiThread{assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out))}
        return out.single()
    }
    private fun pixels(): Pair<Int,IntArray> {
        val image=compose.onNodeWithTag("item-output").captureToImage();val map=image.toPixelMap()
        return image.width to IntArray(image.width*image.height){map[it%image.width,it/image.width].toArgb()}
    }

    @Test fun cursorVirtualOverlayGraphemePixelsAndNaturalLayout() {
        var shown by mutableStateOf<Fixture?>(null,referentialEqualityPolicy())
        compose.setContent{Box(Modifier.background(Color.Black)){shown?.Content()}}
        var cases=0
        for(rtl in listOf(false,true))for(scale in listOf(1.0,1.3))for(shape in listOf(6,4)) {
            val f=fixture(1000,true,shape).apply{this.rtl=rtl;densityOverride=Density(1f,scale.toFloat())}
            compose.runOnIdle{shown=f};settle(f)
            for(column in listOf(6,8,13,15,18)) {
                compose.runOnIdle{f.terminal.feed("\u001b[22;${column}H\u001b[?25l");f.publish()};settle(f)
                val original=layout(f,21);val before=pixels()
                compose.runOnIdle{f.terminal.feed("\u001b[?25h");f.publish()};settle(f)
                val current=layout(f,21);val after=pixels();val cursor=checkNotNull(f.frame.rows[f.frame.historyCount+21].cursorOverlay)
                assertEquals(original.layoutInput.text,current.layoutInput.text);assertEquals(original.size,current.size)
                assertSame("Cursor visibility reshaped text",original.multiParagraph,current.multiParagraph)
                val rowText=f.rows[f.frame.historyCount+21].text
                val node=compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Text,listOf(rowText)),true).fetchSemanticsNodes().single()
                val viewport=compose.onNodeWithTag("item-output").fetchSemanticsNode()
                val box=current.getPathForRange(cursor.start,cursor.end).getBounds()
                val width=box.width/cursor.cells
                val part=if(current.getBidiRunDirection(cursor.start)==ResolvedTextDirection.Rtl)cursor.cells-1-cursor.part else cursor.part
                val left=node.positionInRoot.x-viewport.positionInRoot.x+box.left+part*width
                val top=node.positionInRoot.y-viewport.positionInRoot.y+box.top
                val thickness=minOf(1f,width,box.height)
                val x0=left;val x1=left+if(shape==6)thickness else width
                val y0=if(shape==6)top else top+box.height-thickness;val y1=top+box.height
                assertEquals(before.first,after.first);assertEquals(before.second.size,after.second.size)
                val changed=before.second.indices.filter{before.second[it]!=after.second[it]}
                assertTrue("Cursor pixels missing",changed.isNotEmpty())
                assertTrue("Pixels outside actual grapheme cursor bounds",changed.all {
                    val x=it%before.first;val y=it/before.first
                    x+1>=x0-1 && x<=x1+1 && y+1>=y0-1 && y<=y1+1
                })
                emit("PIXEL",JSONObject().put("rtl",rtl).put("scale",scale).put("shape",shape).put("column",column)
                    .put("changedPixels",changed.size).put("paragraphReused",true).put("virtual",true).put("measuredRows",f.measurements.retainedRows))
                cases++
            }
            compose.runOnIdle{shown=null};compose.waitForIdle();assertEquals(0,f.measurements.retainedRows)
        }
        assertEquals(40,cases)
    }

    @Test fun cursorVirtualPairedMovesKeepEverySample() {
        var shown by mutableStateOf<Fixture?>(null,referentialEqualityPolicy())
        compose.setContent{Box(Modifier.background(Color.Black)){shown?.Content()}}
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        emit("DEVICE",JSONObject().put("pid",Process.myPid()).put("fingerprint",Build.FINGERPRINT)
            .put("targetApk",hash(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes()))
            .put("testApk",hash(File(instrumentation.context.applicationInfo.sourceDir).readBytes())))
        for(size in listOf(1000,10000))for(shape in listOf(6,4))for(round in 0..4) {
            val arms=if(round%2==0)listOf(false,true) else listOf(true,false)
            for(overlay in arms) {
                val f=fixture(size,overlay,shape);compose.runOnIdle{shown=f};settle(f)
                val textBefore=hash(f.terminal.plainText(includeScrollback=true).toByteArray())
                val physicalRows=(20..22).toList();var previous=physicalRows.associateWith{layout(f,it)}
                val widthBefore=f.bound.binding.widthIndex.measuredScreenRows
                val measuredBefore=f.measurements.measuredRowCount
                var paragraphs=0;var textChanges=0
                val begin=System.nanoTime()
                repeat(24) { step ->
                    val row=20+step%3;val column=2+step%16
                    compose.runOnIdle{f.terminal.feed("\u001b[${row+1};${column}H");f.publish()};settle(f)
                    assertEquals(overlay,f.frame.cursorOverlayMode)
                    val next=physicalRows.associateWith{layout(f,it)}
                    for(index in physicalRows) {
                        if(previous[index]!!.multiParagraph!==next[index]!!.multiParagraph)paragraphs++
                        if(previous[index]!!.layoutInput.text!=next[index]!!.layoutInput.text)textChanges++
                    }
                    previous=next
                }
                val elapsed=System.nanoTime()-begin
                val textAfter=hash(f.terminal.plainText(includeScrollback=true).toByteArray());assertEquals(textBefore,textAfter)
                assertEquals(size,f.frame.historyCount)
                emit("SAMPLE",JSONObject().put("size",size).put("shape",shape).put("round",round).put("overlay",overlay).put("warmup",round==0)
                    .put("moves",24).put("elapsedNs",elapsed).put("paragraphChanges",paragraphs).put("textChanges",textChanges)
                    .put("widthMeasurements",f.bound.binding.widthIndex.measuredScreenRows-widthBefore)
                    .put("rowMeasurements",f.measurements.measuredRowCount-measuredBefore).put("textSha256",textAfter)
                    .put("retainedRows",f.rowStore.retainedRowStates).put("measuredRows",f.measurements.retainedRows).put("writers",f.maximumWriters))
                compose.runOnIdle{shown=null};compose.waitForIdle();assertEquals(0,f.measurements.retainedRows)
                emit("DISPOSE",JSONObject().put("size",size).put("shape",shape).put("round",round).put("overlay",overlay)
                    .put("retainedLayouts",f.measurements.retainedRows).put("pointerRows",f.pointerMap.retainedRows))
            }
        }
    }
}
