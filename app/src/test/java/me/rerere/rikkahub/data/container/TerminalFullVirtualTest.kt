package me.rerere.rikkahub.data.container

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import me.rerere.rikkahub.ui.pages.container.TerminalRowStore
import me.rerere.rikkahub.ui.pages.container.TerminalSparseRows
import me.rerere.rikkahub.ui.pages.container.TerminalSelectionDragMotion
import me.rerere.rikkahub.ui.pages.container.terminalSelectionClipboardText
import me.rerere.rikkahub.ui.pages.container.terminalSelectionHandlePosition
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test

class TerminalFullVirtualTest {
    @Test fun fullVirtualHandleTouchBoxAvoidsStatusAndNavigationBars() {
        val safe=IntRect(0,84,1440,2392);val size=IntSize(88,88)
        assertEquals(IntOffset(84,84),terminalSelectionHandlePosition(IntOffset(84,29),size,safe))
        assertEquals(IntOffset(0,84),terminalSelectionHandlePosition(IntOffset(-88,29),size,safe))
        assertEquals(IntOffset(1352,2304),terminalSelectionHandlePosition(IntOffset(1430,2400),size,safe))
    }

    @Test fun fullVirtualHandleSafeWindowPreservesInteriorAndFloatingOrigins() {
        val safe=IntRect(20,40,420,340);val size=IntSize(40,40)
        assertEquals(IntOffset(120,160),terminalSelectionHandlePosition(IntOffset(120,160),size,safe))
        assertEquals(IntOffset(20,40),terminalSelectionHandlePosition(IntOffset(-10,-30),size,safe))
        assertEquals(IntOffset(380,300),terminalSelectionHandlePosition(IntOffset(500,800),size,safe))
        assertEquals(IntOffset(20,40),terminalSelectionHandlePosition(IntOffset(100,100),IntSize(800,800),safe))
    }

    @Test fun fullVirtualClipboardCoalescesRepeatedRowsWithoutLosingTextOrAnnotations() {
        val source=buildAnnotatedString {
            repeat(10000) { row ->
                val first=length;append("word$row 中文 e\u0301 👩‍💻")
                addStyle(SpanStyle(color=Color.Green),first,length)
                if(row==123)addStringAnnotation("URL","https://example.org",first,length)
                append('\n')
            }
        }
        val copy=terminalSelectionClipboardText(source)
        assertEquals(source.text,copy.text)
        assertEquals(1,copy.spanStyles.size)
        assertEquals(source.getStringAnnotations(0,source.length),copy.getStringAnnotations(0,copy.length))
        assertEquals(source.spanStyles.first().start,copy.spanStyles.single().start)
        assertEquals(source.spanStyles.last().end,copy.spanStyles.single().end)
    }

    @Test fun fullVirtualClipboardDoesNotMergeConflictingOrOverlappingStyles() {
        val red=SpanStyle(color=Color.Red);val blue=SpanStyle(color=Color.Blue)
        val source=buildAnnotatedString {
            append("red\nblue\nred")
            addStyle(red,0,3);addStyle(blue,4,8);addStyle(red,9,length)
            addStyle(blue,0,length)
        }
        assertSame(source,terminalSelectionClipboardText(source))
        val overlap=AnnotatedString("abcd",listOf(AnnotatedString.Range(red,0,3),AnnotatedString.Range(red,2,4)))
        assertSame(overlap,terminalSelectionClipboardText(overlap))
    }

    @Test fun fullVirtualClipboardPreservesUnstyledAndSeparatedText() {
        val plain=AnnotatedString("plain e\u0301 👩‍💻")
        assertSame(plain,terminalSelectionClipboardText(plain))
        val style=SpanStyle(color=Color.Red)
        val gap=AnnotatedString("ab cd",listOf(AnnotatedString.Range(style,0,2),AnnotatedString.Range(style,3,5)))
        assertSame(gap,terminalSelectionClipboardText(gap))
    }

    @Test fun fullVirtualStationaryLongPressAndHandleDownNeverAutoscroll() {
        val motion = TerminalSelectionDragMotion()
        for (point in listOf(Offset(42f,14.5f), Offset(42f,300f))) {
            motion.begin(point)
            repeat(222) { assertEquals(0,motion.edgeDelta(point,319f)) }
            motion.move(point)
            assertEquals(0,motion.edgeDelta(point,319f))
            motion.end()
        }
    }

    @Test fun fullVirtualSelectionOnlyScrollsTowardTheDraggedEdge() {
        val motion = TerminalSelectionDragMotion()
        motion.begin(Offset(42f,14.5f))
        motion.move(Offset(44f,20f))
        assertEquals(0,motion.edgeDelta(Offset(44f,20f),319f))
        motion.move(Offset(44f,300f))
        assertTrue(motion.edgeDelta(Offset(44f,300f),319f)>0)
        motion.move(Offset(44f,290f))
        assertEquals(0,motion.edgeDelta(Offset(44f,290f),319f))
        motion.move(Offset(44f,10f))
        assertTrue(motion.edgeDelta(Offset(44f,10f),319f)<0)
    }

    @Test fun fullVirtualSelectionHoldingAfterRealDragRetainsIntentUntilRelease() {
        val motion = TerminalSelectionDragMotion()
        motion.begin(Offset(0f,160f));motion.move(Offset(4f,310f))
        val expected = motion.edgeDelta(Offset(4f,310f),319f)
        assertTrue(expected>0)
        repeat(30) { motion.move(Offset(4f,310f));assertEquals(expected,motion.edgeDelta(Offset(4f,310f),319f)) }
        motion.end();assertEquals(0,motion.edgeDelta(Offset(4f,310f),319f))
        motion.begin(Offset(4f,310f));assertEquals(0,motion.edgeDelta(Offset(4f,310f),319f))
    }

    @Test fun fullVirtualHorizontalOnlySelectionDoesNotInventVerticalMotion() {
        val motion = TerminalSelectionDragMotion()
        motion.begin(Offset(0f,15f));motion.move(Offset(200f,15f))
        assertEquals(0,motion.edgeDelta(Offset(200f,15f),319f))
        assertEquals(0,motion.edgeDelta(Offset(200f,15f),0f))
    }

    private fun terminal(size: Int = 1000) = TerminalEmulator(80, 24, size).apply {
        feed((0 until size + 23).joinToString("\r\n") { "row-$it 中文 e\u0301 👩‍💻" } + "\r\n")
    }
    private fun select(frame: TerminalEmulator.RenderFrame) = TerminalVirtualSelection().apply {
        bind(frame) { frame.rows[frame.historyCount + it].text }
    }
    private fun view(frame: TerminalEmulator.RenderFrame, viewport: Int = 200, top: Int = 0,
        tui: Boolean = true, grid: Boolean = false, ime: Boolean = false, avoid: Boolean = true,
        firstPhysical: Int = 0) = TerminalItemViewport(frame, "exact", listOf(
            TerminalVisibleRow(frame.historyCount + firstPhysical, top, 20)), viewport, 20, 8, true, true,
        TerminalFullVirtualPolicy(TerminalPhysicalRowHeights(IntArray(24) { 20 }), tui, grid, ime, avoid))

    @Test fun fullVirtualIndependentPreferenceNeverChangesTheNineOldModes() {
        assertEquals(10, TerminalRenderMode.entries.size)
        assertEquals(TerminalRenderMode.CHUNKED_LAYERS, TerminalRenderMode.DEFAULT)
        val value = TerminalRenderMode.FULL_VIRTUAL
        assertFalse(value.isVirtualHistory)
        assertEquals(value, TerminalRenderMode.fromId("fullVirtual"))
        for (chunks in listOf(false,true)) for (allowed in listOf(false,true)) {
            assertEquals(value, effectiveTerminalRenderMode(value,chunks,allowed))
        }
        val raw = updatedTerminalRenderPreferences("", "pi --resume", value)
        assertEquals(value,terminalRendererForCommand(raw,"pi --resume"))
        assertEquals(TerminalRenderMode.DEFAULT,terminalRendererForCommand(raw,"sh"))
        for (old in TerminalRenderMode.entries.filterNot { it.isFullVirtual }) {
            assertEquals(TerminalRenderMode.FLAT,effectiveTerminalRenderMode(old,false))
        }
    }

    @Test fun fullVirtualSparseTuiPublishesOnlyScreenStatesAndRetainsEveryHistoryRow() {
        for (size in listOf(1000,5000,10000)) {
            val terminal=terminal(size)
            var frame=terminal.renderFrame()
            val store=TerminalRowStore(frame,true)
            repeat(8) {
                terminal.feed("\r\nlive-$it")
                frame=terminal.renderFrame()
                Snapshot.withMutableSnapshot { store.publish(frame,true,true,true,1000L+it) }
                val rows=store.view(frame)
                assertTrue(rows is TerminalSparseRows)
                assertEquals(24,store.retainedRowStates)
                assertEquals(size+24,rows.size)
                assertTrue(store.sync.lastVisitedTextRows<=24)
                assertEquals(frame.rows[500].text,rows[500].text)
            }
            terminal.feed("\u001b[?1049hALT")
            frame=terminal.renderFrame()
            Snapshot.withMutableSnapshot { store.publish(frame,true,true,true,2000) }
            assertEquals(0,frame.historyCount)
            assertEquals(24,store.retainedRowStates)
            assertTrue(store.view(frame) is TerminalSparseRows)
        }
    }

    @Test fun fullVirtualScreenFollowUsesScreenOriginRatherThanHistoryNominalHeight() {
        val raw=terminal().renderFrame()
        val frame=raw.copy(cursorRow=2,screenContentBounds=TerminalEmulator.ContentBounds(0,2,3))
        val v=view(frame,400,top=100)
        assertEquals(100,v.physicalScreenTop)
        assertEquals(100,v.followDelta())
        assertFalse(v.isSatisfied(TerminalItemScrollTarget.Follow))
        val at=v.copy(rows=listOf(TerminalVisibleRow(frame.historyCount,0,20)))
        assertTrue(at.isSatisfied(TerminalItemScrollTarget.Follow))
        val controller=TerminalViewportController()
        controller.updateItemViewport(frame,v)
        assertEquals(ViewportMode.SCREEN,controller.state.value.mode)
        assertEquals(TerminalItemScrollTarget.Follow,controller.state.value.scrollEffect?.itemTarget)
        assertEquals(frame.historyCount+2,v.itemIndex(frame.historyCount+2))
    }

    @Test fun fullVirtualFullGridAndAlternateIncludeBlankPhysicalRows() {
        val raw=terminal().renderFrame()
        val frame=raw.copy(cursorRow=0,cursorVisible=false,screenContentBounds=TerminalEmulator.ContentBounds(null,null,0))
        val v=view(frame,100,top=-20,grid=true,firstPhysical=1)
        assertEquals(-40,v.physicalScreenTop)
        assertEquals(23+frame.historyCount,v.followRowIndex)
        assertEquals(348,v.followDelta())
        val alt=TerminalEmulator(80,24).apply { feed("\u001b[?1049h") }.renderFrame()
        assertTrue(view(alt,grid=true).ready)
        assertFalse(view(alt,grid=true).copy(fullVirtual=null).ready)
        assertThrows(IllegalArgumentException::class.java) { TerminalPhysicalRowHeights(IntArray(81){20}) }
    }

    @Test fun fullVirtualImeOffsetHoldRemainsAutoWithoutForcingTail() {
        val frame=terminal().renderFrame()
        val controller=TerminalViewportController()
        val before=view(frame,480,top=-8,ime=false)
        controller.updateItemViewport(frame,before)
        assertNull(controller.state.value.scrollEffect)
        controller.updateItemViewport(frame,view(frame,200,top=-8,ime=true,avoid=false))
        assertTrue(controller.state.value.autoScroll)
        assertNull(controller.state.value.scrollEffect)
        controller.updateItemViewport(frame,view(frame,200,top=-8,ime=true,avoid=true))
        assertEquals(TerminalItemScrollTarget.Follow,controller.state.value.scrollEffect?.itemTarget)
        assertEquals(ViewportMode.SCREEN,controller.state.value.mode)
    }

    @Test fun fullVirtualSelectionEdgesSubmitOneBoundedOwnerEffect() {
        val frame=terminal().renderFrame()
        val controller=TerminalViewportController(followInitially=false)
        val current=view(frame,top=-7)
        controller.updateItemViewport(frame,current)
        controller.beginVirtualSelection()
        assertTrue(controller.scrollSelectionBy(9999))
        val first=checkNotNull(controller.state.value.scrollEffect)
        assertEquals(200,first.selectionDeltaPx)
        assertFalse(controller.scrollSelectionBy(10))
        controller.observeItemViewport(current.copy(rows=listOf(TerminalVisibleRow(frame.historyCount+1,-4,20))))
        controller.scrollFinished(first.id,0,true)
        assertEquals(frame.screenLineIds[1],controller.state.value.anchor?.lineId)
        controller.endVirtualSelection()
        assertNull(controller.state.value.scrollEffect)
    }

    @Test fun fullVirtualSelectionCopiesOffscreenRowsAndAllWithoutUiState() {
        val frame=terminal(10000).renderFrame()
        val selection=select(frame)
        selection.select(10,2,5)
        selection.move(false,9000,7)
        val expected=frame.rows.subList(10,9001).mapIndexed { i,r ->
            when(i){0->r.text.text.substring(2);8990->r.text.text.substring(0,7);else->r.text.text}
        }.joinToString("\n")
        assertEquals(expected,selection.copy()?.text)
        assertEquals(0,selection.retainedPhysicalTextKeys)
        selection.selectAll()
        assertEquals(frame.rows.joinToString("\n"){it.text.text},selection.copy()?.text)
        assertEquals(24,selection.retainedPhysicalTextKeys)
        selection.dispose()
        assertNull(selection.copy())
        assertEquals(0,selection.retainedPhysicalTextKeys)
    }

    @Test fun fullVirtualSelectionSurvivesFifoUntilAnEndpointOrTextIsRetired() {
        val terminal=terminal()
        var frame=terminal.renderFrame()
        val selection=select(frame)
        selection.select(500,0,3)
        selection.move(false,520,5)
        val content=selection.copy()
        repeat(3){terminal.feed("\r\nnew-$it")}
        frame=terminal.renderFrame()
        selection.bind(frame){frame.rows[frame.historyCount+it].text}
        assertEquals(content,selection.copy())
        assertEquals(497,selection.range!!.first.index)
        terminal.clearScrollbackOnly()
        frame=terminal.renderFrame()
        selection.bind(frame){frame.rows[frame.historyCount+it].text}
        assertNull(selection.range)
    }

    @Test fun fullVirtualSelectionRejectsEditedScreenAndAlternateGeneration() {
        val terminal=terminal()
        var frame=terminal.renderFrame()
        val selection=select(frame)
        selection.select(frame.historyCount,0,3)
        terminal.feed("\u001b[1;1Hchanged")
        frame=terminal.renderFrame()
        selection.bind(frame){frame.rows[frame.historyCount+it].text}
        assertNull(selection.range)
        selection.select(30,0,3)
        terminal.feed("\u001b[?1049hALT")
        frame=terminal.renderFrame()
        selection.bind(frame){frame.rows[frame.historyCount+it].text}
        assertNull(selection.range)
    }

    @Test fun fullVirtualSelectionNeverSplitsExtendedGraphemes() {
        for (grapheme in listOf("e\u0301","👩‍💻","🇨🇳","👍🏽")) {
            val text="A${grapheme}B"
            for (offset in 2 until 1+grapheme.length) {
                assertEquals(1,terminalSelectionBoundary(text,offset,false))
                assertEquals(1+grapheme.length,terminalSelectionBoundary(text,offset,true))
            }
        }
    }
}
