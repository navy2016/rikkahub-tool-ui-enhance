package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test

/** CI overlay only. Original width-index tests remain byte-identical. */
class TerminalWidthRecoveryCandidateTest {
    private fun terminal(n: Int = 1000) = TerminalEmulator(80, 6, n).apply {
        feed("\u001b[?25l" + (0 until n + 6).joinToString("\r\n") { "row-$it 中文 e\u0301" } + "\r")
    }
    private fun measure(s: AnnotatedString) = s.length * 7 + s.spanStyles.size
    private fun full(t: TerminalEmulator.RenderFrame) = t.rows.maxOf { measure(it.text) }
    private fun enter(t: TerminalEmulator, index: TerminalTranscriptWidthIndex, proof: Any? = "font"): TerminalEmulator.RenderFrame {
        t.feed("\u001b[?1049h\u001b[?25lALT\r")
        return t.renderFrame().also { frame ->
            assertFalse(index.prepareFullVirtualHistory(frame, 1, proof))
            assertEquals(full(frame), index.width(frame, 1, ::measure))
        }
    }
    @Test fun sameHistoryReturnsWithoutReshapingHistoryButAltWidthsRemainUncached() {
        for (n in listOf(1000,10000)) {
            val t=terminal(n);val i=TerminalTranscriptWidthIndex();val initial=t.renderFrame()
            i.width(initial,1,::measure)
            repeat(4) {
                val alt=enter(t,i);assertTrue(i.retainedParkedHistoryCandidates in 1..n)
                repeat(3) { i.width(alt,1,::measure);assertEquals(6,i.lastMeasuredScreenRows);assertEquals(0,i.retainedScreenRows) }
                t.feed("\u001b[?1049l");val next=t.renderFrame();val version=i.fullVirtualRestoreVersion
                assertTrue(i.prepareFullVirtualHistory(next,1,"font"));assertTrue(i.fullVirtualRestoreVersion>version)
                assertEquals(0,i.requiredHistoryMeasurements(next,1));assertEquals(full(next),i.width(next,1,::measure))
                assertEquals(0,i.lastMeasuredHistoryRows);assertEquals(0,i.retainedParkedHistoryCandidates)
                assertFalse(i.prepareFullVirtualHistory(next,1,"font"))
            }
            assertEquals(n.toLong(),i.measuredHistoryRows)
        }
    }
    @Test fun noOptInStillScansOnReturn() {
        val t=terminal();val i=TerminalTranscriptWidthIndex();i.width(t.renderFrame(),1,::measure)
        t.feed("\u001b[?1049h");i.width(t.renderFrame(),1,::measure)
        t.feed("\u001b[?1049l");val f=t.renderFrame();assertEquals(full(f),i.width(f,1,::measure))
        assertEquals(1000,i.lastMeasuredHistoryRows);assertEquals(0,i.retainedParkedHistoryCandidates)
        val cold=TerminalTranscriptWidthIndex();enter(t,cold)
        assertEquals(0,cold.retainedParkedHistoryCandidates)
        t.feed("\u001b[?1049l");val firstMain=t.renderFrame()
        assertFalse(cold.prepareFullVirtualHistory(firstMain,1,"font"))
        assertEquals(full(firstMain),cold.width(firstMain,1,::measure));assertEquals(1000,cold.lastMeasuredHistoryRows)
    }
    @Test fun fontsColumnsOwnerGenerationAndSyntheticFramesCannotRestore() {
        for (kind in listOf("font","columns","owner","reset","render-style","synthetic","unknown-font","main-only-frame")) {
            val t=terminal();val i=TerminalTranscriptWidthIndex();i.width(t.renderFrame(),1,::measure);enter(t,i)
            if(kind=="columns")t.resize(72,6)
            if(kind=="reset")t.reset()
            if(kind=="render-style")t.feed("\u001b]4;2;rgb:ffff/0000/0000\u0007")
            t.feed("\u001b[?1049l")
            val f=when(kind) {
                "owner" -> terminal().renderFrame()
                "synthetic" -> t.renderFrame().let { it.copy(rows=it.rows.toList()) }
                "main-only-frame" -> t.renderFrame(includeScrollback=false)
                else -> t.renderFrame()
            }
            val proof=when(kind) { "font" -> "other-font"; "unknown-font" -> null; else -> "font" }
            assertFalse(kind,i.prepareFullVirtualHistory(f,1,proof));assertEquals(0,i.retainedParkedHistoryCandidates)
            assertEquals(full(f),i.width(f,1,::measure));assertEquals(f.historyCount,i.lastMeasuredHistoryRows)
        }
    }
    @Test fun invalidationWhileAltCannotReviveParkedState() {
        for (clear in listOf<(TerminalTranscriptWidthIndex,TerminalEmulator.RenderFrame)->Unit>(
            {i,_ -> i.clear()}, {i,f -> i.retainFor(f,1)}, {i,_ -> i.discardFullVirtualHistory()},
            {i,f -> i.prepareFullVirtualHistory(f,1,"changed-font")},
            {i,f -> i.prepareFullVirtualHistory(f,1,null)})) {
            val t=terminal();val i=TerminalTranscriptWidthIndex();i.width(t.renderFrame(),1,::measure);val alt=enter(t,i)
            clear(i,alt);assertEquals(0,i.retainedParkedHistoryCandidates)
            t.feed("\u001b[?1049l");val main=t.renderFrame();assertFalse(i.prepareFullVirtualHistory(main,1,"font"))
            i.width(main,1,::measure);assertEquals(1000,i.lastMeasuredHistoryRows)
        }
    }
    @Test fun trimmedOrAppendedHistoryMeasuresExactlyUnseenSurvivors() {
        for (added in listOf(0,3,140)) {
            val t=terminal(200);val i=TerminalTranscriptWidthIndex();i.width(t.renderFrame(),1,::measure);enter(t,i)
            t.setMaxScrollbackLines(150);t.feed("\u001b[?1049l\u001b[6;1H")
            repeat(added) { t.feed("\r\nnew-$it\r") }
            val main=t.renderFrame();assertTrue(i.prepareFullVirtualHistory(main,1,"font"))
            assertEquals(full(main),i.width(main,1,::measure));assertEquals(minOf(added,150),i.lastMeasuredHistoryRows)
            assertTrue(i.retainedCandidates<=150)
        }
    }
    @Test fun forkDoesNotCopyParkedCheckpointAndRestoreRevokesOldWorkerEpoch() {
        val t=terminal();val i=TerminalTranscriptWidthIndex();val main=t.renderFrame();i.width(main,1,::measure)
        val before=i.fork();val oldEpoch=i.epoch
        before.width(main,1,::measure);enter(t,i)
        assertEquals(0,i.fork().retainedParkedHistoryCandidates)
        assertFalse(i.acceptComputed(before,oldEpoch,main))
        t.feed("\u001b[?1049l");val back=t.renderFrame()
        val epochBeforeRestore=i.epoch;assertTrue(i.prepareFullVirtualHistory(back,1,"font"))
        assertTrue(i.epoch>epochBeforeRestore);assertFalse(i.readyFor(back,1))
        assertFalse(i.acceptComputed(before,epochBeforeRestore,main))
        assertEquals(full(back),i.width(back,1,::measure))
    }
    @Test fun failedReturnedScreenMeasurementRevokesTheRestoredHistoryAndCheckpoint() {
        val t=terminal();val i=TerminalTranscriptWidthIndex();i.width(t.renderFrame(),1,::measure);enter(t,i)
        t.feed("\u001b[?1049l");val back=t.renderFrame();assertTrue(i.prepareFullVirtualHistory(back,1,"font"))
        assertThrows(IllegalStateException::class.java) { i.width(back,1) { error("screen font failure") } }
        assertFalse(i.readyFor(back,1));assertEquals(0,i.retainedParkedHistoryCandidates)
        assertEquals(full(back),i.width(back,1,::measure));assertEquals(1000,i.lastMeasuredHistoryRows)
    }
    @Test fun staleSessionLeaseStillCannotOverwriteCurrentCheckpoint() {
        val t=terminal();val i=TerminalTranscriptWidthIndex();i.width(t.renderFrame(),1,::measure)
        val store=TerminalWidthCheckpointStore();val old=store.open()!!;val current=store.open()!!
        val cp=i.checkpoint("font",1)!!;store.save(current,cp);store.save(old,null)
        assertSame(cp,store.read(current));store.close();store.save(current,cp);assertNull(store.peek())
    }
}
