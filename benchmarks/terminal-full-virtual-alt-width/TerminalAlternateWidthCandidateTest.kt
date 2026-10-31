package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test

/** CI-only candidate contract. Original TerminalTranscriptWidthIndexTest stays unchanged. */
class TerminalAlternateWidthCandidateTest {
    private fun terminal(count: Int=24)=TerminalEmulator(80,count,1000).apply {
        feed("\u001b[?1049h\u001b[?25l"+(0 until count).joinToString("\r\n"){"row-$it 中文 e\u0301 👩‍💻"}+"\r")
    }
    private fun measure(text: AnnotatedString)=text.length+text.spanStyles.size*3+text.getStringAnnotations(0,text.length).size
    private fun expected(f: TerminalEmulator.RenderFrame)=f.rows.maxOfOrNull{measure(it.text)} ?: 0
    @Test fun alternateIdentityAndOneDirtyRowReuseOnlyUntouchedText() {
        for(n in listOf(6,24,80)) {
            val t=terminal(n);val index=TerminalTranscriptWidthIndex();val first=t.renderFrame()
            assertTrue(first.isAlternateScreen);assertEquals(0,first.historyCount)
            assertEquals(expected(first),index.fullVirtualWidth(first,1,::measure));assertEquals(n,index.lastMeasuredScreenRows)
            repeat(4) {
                val rebuilt=t.renderFrameUncached()
                assertEquals(expected(rebuilt),index.fullVirtualWidth(rebuilt,1){error("Equal annotated row remeasured")})
                assertEquals(n,index.lastReusedScreenRows)
            }
            repeat(20) { i ->
                t.feed("\u001b[2;1H\u001b[2Kchanged-$i\r")
                val f=t.renderFrame();assertEquals(expected(f),index.fullVirtualWidth(f,1,::measure))
                assertEquals(1,index.lastMeasuredScreenRows);assertEquals(n-1,index.lastReusedScreenRows)
                assertEquals(n,index.retainedScreenRows);assertEquals(0,index.retainedCandidates)
            }
            assertNull(index.checkpoint("font"));index.clear();assertFalse(index.hasRetainedState)
        }
    }
    @Test fun originalEntryNeverOptsInAndMainScreenStillUsesOldRules() {
        val t=terminal();val f=t.renderFrame();val index=TerminalTranscriptWidthIndex()
        index.fullVirtualWidth(f,1,::measure)
        repeat(3){assertEquals(expected(f),index.width(f,1,::measure));assertEquals(24,index.lastMeasuredScreenRows);assertEquals(0,index.retainedScreenRows)}
        t.feed("\u001b[?1049lmain\r")
        val main=t.renderFrame();val reference=TerminalTranscriptWidthIndex()
        assertEquals(reference.width(main,1,::measure),index.fullVirtualWidth(main,1,::measure))
        assertEquals(reference.retainedScreenRows,index.retainedScreenRows)
    }
    @Test fun styleChangesAndShrinkingMaximumRemainExact() {
        val t=terminal();val index=TerminalTranscriptWidthIndex()
        t.feed("\u001b[2;1H\u001b[2K"+"W".repeat(70)+"\r")
        val wide=index.fullVirtualWidth(t.renderFrame(),1,::measure)
        t.feed("\u001b[2;1H\u001b[2Ktiny\r")
        val small=index.fullVirtualWidth(t.renderFrame(),1,::measure);assertTrue(small<wide);assertEquals(1,index.lastMeasuredScreenRows)
        for(styled in listOf("\u001b[1mtiny\u001b[0m","\u001b[3mtiny\u001b[0m","\u001b]8;;https://example.invalid/new\u001b\\tiny\u001b]8;;\u001b\\")) {
            t.feed("\u001b[2;1H\u001b[2K$styled\r");val f=t.renderFrame()
            assertEquals(expected(f),index.fullVirtualWidth(f,1,::measure));assertEquals(1,index.lastMeasuredScreenRows)
        }
    }
    @Test fun alternateEntryResizeFontOwnerAndReplacedMetadataInvalidate() {
        val t=terminal();val f=t.renderFrame()
        val replacements=listOf(f.copy(rows=f.rows.toList()),f.copy(screenLineIds=f.screenLineIds.map{it}),
            f.copy(screenGeneration=f.screenGeneration+1),f.copy(historyGeneration=f.historyGeneration+1),terminal().renderFrame())
        for(next in replacements) {
            val index=TerminalTranscriptWidthIndex();index.fullVirtualWidth(f,1,::measure)
            assertEquals(expected(next),index.fullVirtualWidth(next,1,::measure));assertEquals(24,index.lastMeasuredScreenRows)
        }
        val index=TerminalTranscriptWidthIndex();index.fullVirtualWidth(f,1,::measure)
        assertEquals(expected(f)*2,index.fullVirtualWidth(f,2){measure(it)*2});assertEquals(24,index.lastMeasuredScreenRows)
        t.resize(60,18);val resized=t.renderFrame();assertEquals(expected(resized),index.fullVirtualWidth(resized,1,::measure));assertEquals(18,index.lastMeasuredScreenRows)
        t.feed("\u001b[?1049l\u001b[?1049h");val reset=t.renderFrame()
        assertEquals(expected(reset),index.fullVirtualWidth(reset,1,::measure));assertEquals(18,index.lastMeasuredScreenRows)
    }
    @Test fun insertDeleteAndCursorChangesMatchFullScan() {
        val t=terminal();val index=TerminalTranscriptWidthIndex();index.fullVirtualWidth(t.renderFrame(),1,::measure)
        for(code in listOf("\u001b[3;1H\u001b[L","\u001b[6;1H\u001b[M","\u001b[?25h\u001b[2;9H","\u001b[3;4H","\u001b[?25l")) {
            t.feed(code);val f=t.renderFrame();assertEquals(expected(f),index.fullVirtualWidth(f,1,::measure))
            assertEquals(24,index.retainedScreenRows)
        }
    }
    @Test fun failureAndFallbackRevokeCacheAndForkHonorsEpoch() {
        val t=terminal();val index=TerminalTranscriptWidthIndex();index.fullVirtualWidth(t.renderFrame(),1,::measure)
        t.feed("\u001b[2;1Hnew2\r\u001b[3;1Hnew3\r");val f=t.renderFrame();var calls=0
        assertThrows(IllegalStateException::class.java){index.fullVirtualWidth(f,1){if(++calls==2)error("font failure") else measure(it)}}
        assertEquals(0,index.retainedScreenRows);assertFalse(index.readyFor(f,1))
        assertEquals(expected(f),index.fullVirtualWidth(f,1,::measure));assertEquals(24,index.lastMeasuredScreenRows)
        val epoch=index.epoch;val worker=index.fork();worker.fullVirtualWidth(f,1){error("Fork lost exact rows")}
        assertTrue(index.acceptComputed(worker,epoch,f));assertTrue(index.readyFor(f,1))
        val obsolete=index.epoch;index.retainFor(f,1);assertFalse(index.hasRetainedState);assertFalse(index.acceptComputed(worker,obsolete,f))
    }
}
