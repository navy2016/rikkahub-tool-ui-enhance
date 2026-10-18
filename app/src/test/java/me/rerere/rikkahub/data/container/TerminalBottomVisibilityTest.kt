package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test

class TerminalBottomVisibilityTest {
    private fun frame(history: Int = 8, textRow: Int = 3, cursor: Int = 4, visible: Boolean = true,
        alternate: Boolean = false): TerminalEmulator.RenderFrame {
        val h = if (alternate) 0 else history
        return TerminalEmulator.RenderFrame(
            rows = List(h + 6) { TerminalEmulator.RenderedRow(AnnotatedString(if (it <= h + textRow) "r$it" else " ")) },
            contentBounds = TerminalEmulator.ContentBounds(0, h + textRow, h + textRow + 1),
            screenContentBounds = TerminalEmulator.ContentBounds(0, textRow, textRow + 1),
            historyCount = h, screenStartRow = h, historyLineIds = List(h) { 10L + it },
            screenLineIds = List(6) { 1000L + it }, historyGeneration = 1, screenGeneration = if (alternate) 2 else 1,
            cursorRow = cursor, cursorVisible = visible, isAlternateScreen = alternate, modeSummary = "", revision = 1,
        )
    }

    private fun geometry(frame: TerminalEmulator.RenderFrame, height: (Int) -> Int = { 20 }) =
        TerminalEagerViewportGeometry(frame, IntArray(frame.rows.size, height))

    private fun target(frame: TerminalEmulator.RenderFrame, geometry: TerminalEagerViewportGeometry,
        tui: Boolean = false, full: Boolean = false): Int {
        val controller = TerminalViewportController()
        controller.updateViewport(frame, frame.rows.size,
            TerminalViewportMetrics(geometry.contentHeightPx - 80 + 8, 80, 20, 8, tui, full), 0, geometry, true)
        return controller.state.value.scrollEffect?.targetScrollPx ?: 0
    }

    @Test fun bottomVisibilityBlankCursorIsPartOfMeasuredFollowBoundary() {
        val f = frame()
        val g = geometry(f) { if (it == f.historyCount + f.cursorRow) 31 else 20 }
        assertEquals(g.bottom(f.historyCount + f.cursorRow) + 8 - 80, target(f, g))
        val hidden = f.copy(cursorVisible = false)
        assertEquals(geometry(hidden).bottom(hidden.historyCount + 3) + 8 - 80, target(hidden, geometry(hidden)))
    }

    @Test fun bottomVisibilityShellPromptKeepsSameTargetAndTrailingBlanksStayHidden() {
        val f = frame(textRow = 4, cursor = 4)
        val g = geometry(f)
        assertEquals(g.bottom(f.historyCount + 4) + 8 - 80, target(f, g))
        val beyond = f.copy(cursorRow = 999)
        assertEquals(target(f, g), target(beyond, geometry(beyond)))
    }

    @Test fun bottomVisibilityItemFollowUsesCursorRowNotLastNonblank() {
        val f = frame()
        val view = TerminalItemViewport(f, "font", listOf(
            TerminalVisibleRow(f.historyCount + 3, 62, 30), TerminalVisibleRow(f.historyCount + 4, 92, 31)),
            100, 20, 8, true, true)
        assertEquals(f.historyCount + 4, view.followRowIndex)
        assertFalse(view.isSatisfied(TerminalItemScrollTarget.Follow))
        val done = view.copy(rows = view.rows.map { it.copy(topPx = it.topPx - 31) })
        assertTrue(done.isSatisfied(TerminalItemScrollTarget.Follow))
        assertTrue(view.copy(frame = f.copy(cursorVisible = false)).isSatisfied(TerminalItemScrollTarget.Follow))
    }

    @Test fun bottomVisibilityAllBlankScreenStillFollowsVisibleCursor() {
        val terminal = TerminalEmulator(20, 6).apply { feed("\u001b[6;1H") }
        val f = terminal.renderFrame()
        assertNull(f.contentBounds.lastNonBlankRow)
        assertEquals(48, target(f, geometry(f)))
        val hidden = f.copy(cursorVisible = false)
        assertEquals(0, target(hidden, geometry(hidden)))
    }

    @Test fun bottomVisibilityTuiPrefixUsesEveryMeasuredHistoryHeight() {
        for (history in listOf(0, 128, 1024)) {
            val f = frame(history)
            val g = geometry(f) { if (it < history) 23 + it % 3 else 27 }
            val expected = maxOf(g.historyPrefix.totalHeightPx, g.bottom(history + 4) + 8 - 80)
            assertEquals("history=$history", expected, target(f, g, tui = true))
        }
    }

    @Test fun bottomVisibilityFullGridAndAlternateRespectMeasuredScreenPolicy() {
        for (alternate in listOf(false, true)) {
            val f = frame(alternate = alternate)
            val g = geometry(f) { 29 + it % 4 }
            assertEquals(g.contentHeightPx + 8 - 80, target(f, g, tui = true, full = true))
        }
    }

    @Test fun bottomVisibilityMissingOrWrongFrameMeasurementCannotPublishTuiTarget() {
        val f = frame()
        val controller = TerminalViewportController()
        val metrics = TerminalViewportMetrics(600, 80, 20, 8, usesTuiViewport = true)
        controller.updateViewport(f, f.rows.size, metrics, 0, null, true)
        assertFalse(controller.state.value.initialized)
        controller.updateViewport(f, f.rows.size, metrics, 0, geometry(f.copy()), true)
        assertNull(controller.state.value.scrollEffect)
        controller.updateViewport(f, f.rows.size, metrics, 0, geometry(f), true)
        assertTrue(controller.state.value.initialized)
    }

    @Test fun bottomVisibilityAutomaticImeAvoidanceUsesActualExtentIncludingCursor() {
        val f = frame(history = 0, textRow = 1, cursor = 2)
        val g = geometry(f) { 41 }
        val controller = TerminalViewportController()
        controller.updateViewport(f, f.rows.size, TerminalViewportMetrics(300, 200, 20, 8,
            automaticImeAvoidance = true), 0, g, true)
        assertNull(controller.state.value.scrollEffect)
        // The nominal extent is60+8<100, actual extent is123+8>100. It must not hold offset0.
        controller.updateViewport(f, f.rows.size, TerminalViewportMetrics(300, 100, 20, 8,
            imeVisible = true, avoidIme = false, automaticImeAvoidance = true), 0, g, true)
        assertEquals(31, controller.state.value.scrollEffect?.targetScrollPx)
        val effect = requireNotNull(controller.state.value.scrollEffect)
        controller.scrollFinished(effect.id, 31, true)
        controller.updateViewport(f, f.rows.size, TerminalViewportMetrics(300, 200, 20, 8,
            automaticImeAvoidance = true), 31, g, true)
        assertEquals(0, controller.state.value.scrollEffect?.targetScrollPx)
    }

    @Test fun bottomVisibilityScreenRelativeImeExtentDoesNotCountOldHistory() {
        val f = frame(history = 1024, textRow = 1, cursor = 2)
        val g = geometry(f) { if (it < 1024) 37 else 41 }
        assertEquals(123, g.viewportContentHeight(true, false))
        assertEquals(246, g.viewportContentHeight(true, true))
        assertEquals(1024 * 37 + 123, g.viewportContentHeight(false, false))
    }

    @Test fun bottomVisibilityTuiLockedReaderPreservesMeasuredAnchorAcrossAppendAndTrim() {
        val before = frame(history = 8)
        val g = geometry(before) { 30 }
        val controller = TerminalViewportController(TerminalViewportState(autoScroll = false,
            anchorLineId = 14, anchorClippedTopPx = 7, anchorRowHeightPx = 30, anchorHistoryGeneration = 1))
        controller.updateViewport(before, before.rows.size, TerminalViewportMetrics(500, 80, 20, 8, true), 0, g, true)
        val first = requireNotNull(controller.state.value.scrollEffect)
        assertEquals(127, first.targetScrollPx)
        controller.scrollFinished(first.id, first.targetScrollPx, true)
        val after = before.copy(historyLineIds = (11L..18L).toList(), revision = 2)
        controller.updateViewport(after, after.rows.size, TerminalViewportMetrics(500, 80, 20, 8, true),
            127, geometry(after) { 30 }, true)
        assertEquals(97, controller.state.value.scrollEffect?.targetScrollPx)
        assertFalse(controller.state.value.autoScroll)
        assertEquals(14L, controller.state.value.anchor?.lineId)
    }
}
