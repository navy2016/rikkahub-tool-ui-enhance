package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test

/** Real emulator IDs plus exact scalar measured geometry; no font/performance claim. */
class Terminal306WidthIntentTest {
    private fun terminal() = TerminalEmulator(80, 24, 1000).apply {
        feed("\u001b[?25l" + (0 until 64).joinToString("\r\n") { "line-$it" })
    }
    private fun view(frame: TerminalEmulator.RenderFrame, row: Int = 3, top: Int = -7) = TerminalItemViewport(
        frame, "exact", listOf(TerminalVisibleRow(frame.historyCount + row, top, 20)), 200, 20, 8, true, true,
        TerminalFullVirtualPolicy(TerminalPhysicalRowHeights(IntArray(frame.screenLineIds.size) { 20 }),
            tui = true, preserveGrid = false, ime = false, avoidIme = false))
    private fun update(controller: TerminalViewportController, frame: TerminalEmulator.RenderFrame, eager: Boolean,
        ready: Boolean = true, row: Int = 3, top: Int = -7) {
        if (eager) {
            val geometry = TerminalEagerViewportGeometry(frame, IntArray(frame.rows.size) { 20 })
            controller.updateViewport(frame, frame.rows.size,
                TerminalViewportMetrics(geometry.contentHeightPx - 200, 200, 20, 8, usesTuiViewport = true),
                (frame.historyCount + row) * 20 - top, if (ready) geometry else null, true)
        } else controller.updateItemViewport(frame, if (ready) view(frame, row, top) else null)
    }
    private fun clear(terminal: TerminalEmulator) {
        terminal.feed("\u001b[2J\u001b[Hredrawn\u001b[24;1Hbottom")
    }
    @Test fun widthAloneKeepsTheOriginalAutoAndLockChoicesInBothBackends() {
        for (eager in listOf(false, true)) for (auto in listOf(false, true)) {
            val t = terminal(); val c = TerminalViewportController(followInitially = auto)
            update(c, t.renderFrame(), eager); val anchor = c.state.value.anchor
            c.beginWidthChange(120); t.resize(120, 24)
            val frame = t.renderFrame(); c.completeWidthChange(frame); update(c, frame, eager)
            assertEquals(auto, c.state.value.autoScroll)
            if (!auto) assertEquals(anchor, c.state.value.anchor)
        }
    }
    @Test fun widthAndTuiEd2RebindToCurrentMeasuredRowsWithoutTurningAutoOn() {
        for (eager in listOf(false, true)) {
            val t = terminal(); val c = TerminalViewportController(followInitially = false)
            val before = t.renderFrame(); update(c, before, eager)
            val anchor = requireNotNull(c.state.value.anchor); assertNotNull(c.beginWidthChange(120))
            t.resize(120, 24); clear(t); val frame = t.renderFrame()
            assertNotEquals(before.screenGeneration, frame.screenGeneration)
            assertFalse(anchor.lineId in frame.screenLineIds)
            c.completeWidthChange(frame); update(c, frame, eager, ready = false)
            assertFalse(c.state.value.autoScroll)
            update(c, frame, eager)
            assertFalse(c.state.value.autoScroll)
            assertEquals(frame.screenLineIds[3], c.state.value.anchor?.lineId)
            assertEquals(frame.screenGeneration, c.state.value.anchor?.screenGeneration)
            assertEquals(7, c.state.value.anchor?.clippedTopPx)
            assertEquals(20, c.state.value.anchorRowHeightPx)
        }
    }
    @Test fun autoBeforeWidthAndClearRemainsAuto() {
        for (eager in listOf(false, true)) {
            val t = terminal(); val c = TerminalViewportController()
            update(c, t.renderFrame(), eager); assertNull(c.beginWidthChange(120))
            t.resize(120, 24); clear(t); val f = t.renderFrame(); c.completeWidthChange(f); update(c, f, eager)
            assertTrue(c.state.value.autoScroll); assertNull(c.state.value.anchor)
        }
    }
    @Test fun unrelatedClearStillUsesOriginalFallbackPolicy() {
        for (eager in listOf(false, true)) {
            val t = terminal(); val c = TerminalViewportController(followInitially = false)
            update(c, t.renderFrame(), eager); clear(t); update(c, t.renderFrame(), eager)
            assertTrue(c.state.value.autoScroll)
        }
    }
    @Test fun protectionExpiresAfterTheMatchingPublishedLayout() {
        for (eager in listOf(false, true)) {
            val t = terminal(); val c = TerminalViewportController(followInitially = false)
            update(c, t.renderFrame(), eager); c.beginWidthChange(120); t.resize(120, 24); clear(t)
            val f = t.renderFrame(); c.completeWidthChange(f); update(c, f, eager); assertFalse(c.state.value.autoScroll)
            clear(t); update(c, t.renderFrame(), eager); assertTrue(c.state.value.autoScroll)
        }
    }
    @Test fun canceledRequestDoesNotProtectUnrelatedRedraw() {
        for (eager in listOf(false, true)) {
            val t = terminal(); val c = TerminalViewportController(followInitially = false)
            update(c, t.renderFrame(), eager); val request = c.beginWidthChange(120); c.cancelWidthChange(request)
            t.resize(120, 24); clear(t); update(c, t.renderFrame(), eager); assertTrue(c.state.value.autoScroll)
        }
    }
    @Test fun staleCancelCannotCancelNewerWidthRequest() {
        for (eager in listOf(false, true)) {
            val t = terminal(); val c = TerminalViewportController(followInitially = false)
            update(c, t.renderFrame(), eager); val old = c.beginWidthChange(120); val next = c.beginWidthChange(160)
            assertNotEquals(old, next); c.cancelWidthChange(old)
            t.resize(160, 24); clear(t); val f = t.renderFrame(); c.completeWidthChange(f); update(c, f, eager)
            assertFalse(c.state.value.autoScroll)
        }
    }
    @Test fun explicitAutoAfterWidthRequestWins() {
        for (eager in listOf(false, true)) {
            val t = terminal(); val c = TerminalViewportController(followInitially = false)
            update(c, t.renderFrame(), eager); c.beginWidthChange(120); c.setFollow(true, 0)
            t.resize(120, 24); clear(t); val f = t.renderFrame(); c.completeWidthChange(f); update(c, f, eager)
            assertTrue(c.state.value.autoScroll)
        }
    }
    @Test fun userDragInvalidatesOldWidthProtection() {
        val t = terminal(); val c = TerminalViewportController(followInitially = false)
        update(c, t.renderFrame(), false); c.beginWidthChange(120)
        val gesture = c.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 0); c.userScrolled(gesture, 0); c.endUserScroll(gesture)
        t.resize(120, 24); clear(t); val f = t.renderFrame(); c.completeWidthChange(f); update(c, f, false)
        assertTrue(c.state.value.autoScroll)
    }
    @Test fun anotherEmulatorCannotReuseWidthProtection() {
        val t = terminal(); val c = TerminalViewportController(followInitially = false)
        update(c, t.renderFrame(), false); c.beginWidthChange(120)
        val other = terminal(); other.resize(120, 24); clear(other); val f = other.renderFrame()
        c.completeWidthChange(f); update(c, f, false); assertTrue(c.state.value.autoScroll)
    }
    @Test fun rowsChangeOrAlternateTransitionDoesNotBorrowWidthOnlyProtection() {
        for (alt in listOf(false, true)) {
            val t = terminal(); val c = TerminalViewportController(followInitially = false)
            update(c, t.renderFrame(), false); c.beginWidthChange(120)
            t.resize(120, if (alt) 24 else 30)
            if (alt) t.feed("\u001b[?1049h") else clear(t)
            val f = t.renderFrame(); c.completeWidthChange(f); update(c, f, false)
            assertTrue(c.state.value.autoScroll)
        }
    }
    @Test fun historyLockKeepsExistingIdAcrossWidthWithoutScreenProtection() {
        val t = terminal(); val c = TerminalViewportController(followInitially = false); val old = t.renderFrame()
        val v = TerminalItemViewport(old, "font", listOf(TerminalVisibleRow(4, -7, 20)), 200, 20, 8, true, true)
        c.updateItemViewport(old, v); val anchor = c.state.value.anchor
        assertNull(c.beginWidthChange(120)); t.resize(120, 24); clear(t); val f = t.renderFrame()
        c.completeWidthChange(f); c.updateItemViewport(f, v.copy(frame = f))
        assertFalse(c.state.value.autoScroll); assertEquals(anchor, c.state.value.anchor)
    }
    @Test fun unmeasuredInputCannotCreateAnUnboundedWidthLease() {
        val t = terminal(); val f = t.renderFrame(); val c = TerminalViewportController(followInitially = false)
        c.updateItemViewport(f, null); assertNull(c.beginWidthChange(120))
    }
    @Test fun unchangedWidthDoesNotProtectAFullClear() {
        val t = terminal(); val c = TerminalViewportController(followInitially = false)
        update(c, t.renderFrame(), false); assertNull(c.beginWidthChange(80))
        clear(t); update(c, t.renderFrame(), false); assertTrue(c.state.value.autoScroll)
    }
}
