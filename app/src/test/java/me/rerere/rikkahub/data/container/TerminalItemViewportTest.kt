package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalItemViewportTest {
    private fun frame(first: Long = 100, generation: Long = 1, revision: Long = 1): TerminalEmulator.RenderFrame {
        val history = List(100) { first + it }
        return TerminalEmulator.RenderFrame(
            rows = List(104) { TerminalEmulator.RenderedRow(AnnotatedString("row$it")) },
            contentBounds = TerminalEmulator.ContentBounds(0, 103, 104),
            screenContentBounds = TerminalEmulator.ContentBounds(0, 3, 4),
            screenStartRow = 100, historyCount = 100, historyStartId = first, historyEndId = first + 99,
            historyLineIds = history, historyGeneration = generation,
            screenLineIds = listOf(1000, 1001, 1002, 1003), screenGeneration = 1,
            cursorRow = 3, cursorVisible = false, isAlternateScreen = false, modeSummary = "", revision = revision,
        )
    }

    private fun view(frame: TerminalEmulator.RenderFrame, index: Int = 50, top: Int = -7, height: Int = 31,
        viewport: Int = 100, atTop: Boolean = false, atBottom: Boolean = false) = TerminalItemViewport(
        frame, "metrics-$height", listOf(TerminalVisibleRow(index, top, height)), viewport, 20, 8,
        canScrollBackward = !atTop, canScrollForward = !atBottom,
    )

    @Test fun captureUsesMeasuredRowNotNominalPixelDivision() {
        val frame = frame()
        val controller = TerminalViewportController(followInitially = false)
        controller.updateItemViewport(frame, view(frame))
        assertEquals(150L, controller.state.value.anchor?.lineId)
        assertEquals(7, controller.state.value.anchor?.clippedTopPx)
        assertEquals(31, controller.state.value.anchorRowHeightPx)
        assertNull(controller.state.value.scrollEffect)
    }

    @Test fun completedItemScrollReconcilesAgainstTheNewMeasuredViewport() {
        val frame = frame()
        val controller = TerminalViewportController()
        controller.updateItemViewport(frame, view(frame, index = 50, top = -7))
        controller.jumpToBottom(0)
        val effect = requireNotNull(controller.state.value.scrollEffect)
        assertEquals(TerminalItemScrollTarget.Follow, effect.itemTarget)
        controller.observeItemViewport(view(frame, index = 103, top = 63, height = 29, atBottom = true))
        controller.scrollFinished(effect.id, 0, completed = true)
        assertNull(controller.state.value.scrollEffect)
        assertTrue(controller.state.value.autoScroll)
        assertTrue(view(frame, index = 103, top = 63, height = 29, atBottom = true)
            .isSatisfied(TerminalItemScrollTarget.Follow))
    }

    @Test fun synchronousScrollCompletionRequiresCurrentFrameIdentityAndGeometry() {
        val frame = frame()
        val before = view(frame, index = 103, top = 80, height = 29)
        val after = view(frame, index = 103, top = 63, height = 29)
        val target = TerminalItemScrollTarget.Follow
        assertFalse(before.isSatisfied(target))
        assertTrue(after.confirmsScrollFrom(before, target))
        // Same revision/data is not the same publication, including reused revisions in another session.
        assertFalse(after.copy(frame = frame.copy()).confirmsScrollFrom(before, target))
        assertFalse(after.copy(layoutKey = "another-font").confirmsScrollFrom(before, target))
        assertFalse(after.copy(cellHeightPx = 21).confirmsScrollFrom(before, target))
        assertFalse(after.copy(viewportHeightPx = 101).confirmsScrollFrom(before, target))
        assertFalse(after.copy(tailPaddingPx = 9).confirmsScrollFrom(before, target))
        assertFalse(before.confirmsScrollFrom(before, target))
        assertFalse(after.copy(rows = emptyList()).confirmsScrollFrom(before, target))
        assertFalse(after.confirmsScrollFrom(before.copy(viewportHeightPx = 0), target))
    }

    @Test fun synchronousCompletionPreservesMeasuredAnchorAndDirectionalClamping() {
        val frame = frame()
        val before = view(frame, index = 50, top = -20, height = 31)
        val after = view(frame, index = 50, top = -7, height = 31)
        val target = TerminalItemScrollTarget.Anchor(ViewportAnchor(150, 7, null, 1), 31)
        assertTrue(after.confirmsScrollFrom(before, target))
        assertFalse(after.copy(rows = listOf(TerminalVisibleRow(50, -6, 31))).confirmsScrollFrom(before, target))
        val tailBefore = view(frame, index = 103, top = 80, height = 29)
        // Reaching a physical end permits clamping ONLY in the blocked direction.
        assertTrue(tailBefore.copy(canScrollForward = false).confirmsScrollFrom(tailBefore, TerminalItemScrollTarget.Follow))
        assertFalse(view(frame, index = 103, top = 4, height = 29, atBottom = true)
            .confirmsScrollFrom(tailBefore, TerminalItemScrollTarget.Follow))
        assertTrue(view(frame, index = 0, top = 0, height = 29, atTop = true)
            .confirmsScrollFrom(view(frame, index = 0, top = -20, height = 29), TerminalItemScrollTarget.Top))
    }

    @Test fun structuralEndStillNeedsBackwardCorrectionForBlankScreenRows() {
        val frame = frame()
        assertFalse(view(frame, index = 103, top = 4, height = 29, atBottom = true)
            .isSatisfied(TerminalItemScrollTarget.Follow))
        assertTrue(view(frame, index = 103, top = 4, height = 29, atTop = true, atBottom = true)
            .isSatisfied(TerminalItemScrollTarget.Follow))
    }

    @Test fun measuredEagerHandoffRestoresVariableHeightAnchorNotNominalGrid() {
        val frame = frame()
        val controller = TerminalViewportController(followInitially = false)
        controller.updateItemViewport(frame, view(frame, index = 50, top = -7, height = 31))
        val geometry = TerminalEagerViewportGeometry(frame, IntArray(frame.rows.size) { if (it == 50) 31 else 43 })
        val metrics = TerminalViewportMetrics(geometry.contentHeightPx - 100, 100, 20, 8)
        controller.updateViewport(frame, frame.rows.size, metrics, 0, null, true)
        assertNull(controller.state.value.scrollEffect)
        controller.updateViewport(frame, frame.rows.size, metrics, 0, geometry, true)
        val effect = requireNotNull(controller.state.value.scrollEffect)
        assertEquals(50 * 43 + 7, effect.targetScrollPx)
        controller.scrollFinished(effect.id, effect.targetScrollPx, true)
        assertNull(controller.state.value.scrollEffect)
        assertEquals(150L, controller.state.value.anchor?.lineId)
    }

    @Test fun topJumpSurvivesRendererHandoffAndTrimWhileWaitingForEagerGeometry() {
        val initial = frame()
        val controller = TerminalViewportController(followInitially = false)
        controller.updateItemViewport(initial, view(initial))
        controller.jumpToTop(0)
        val oldJump = requireNotNull(controller.state.value.scrollEffect)
        controller.pauseScrollEffects()
        val next = frame(first = 130, revision = 2)
        val geometry = TerminalEagerViewportGeometry(next, IntArray(next.rows.size) { 37 })
        val metrics = TerminalViewportMetrics(geometry.contentHeightPx - 100, 100, 20, 8)
        controller.updateViewport(next, next.rows.size, metrics, 1234, null, true)
        assertNull(controller.state.value.scrollEffect)
        controller.scrollFinished(oldJump.id, 999, false)
        controller.updateViewport(next, next.rows.size, metrics, 1234, geometry, true)
        val effect = requireNotNull(controller.state.value.scrollEffect)
        assertEquals(0, effect.targetScrollPx)
        assertEquals(130L, controller.state.value.anchor?.lineId)
        assertFalse(controller.state.value.autoScroll)
        controller.scrollFinished(effect.id, 0, true)
        assertNull(controller.state.value.scrollEffect)
    }

    @Test fun newUserInputCancelsTopIntentDuringRendererHandoff() {
        val frame = frame()
        val controller = TerminalViewportController(followInitially = false)
        controller.updateItemViewport(frame, view(frame))
        controller.jumpToTop(0)
        controller.pauseScrollEffects()
        val geometry = TerminalEagerViewportGeometry(frame, IntArray(frame.rows.size) { 37 })
        val metrics = TerminalViewportMetrics(geometry.contentHeightPx - 100, 100, 20, 8)
        controller.updateViewport(frame, frame.rows.size, metrics, 1234, geometry, true)
        val top = requireNotNull(controller.state.value.scrollEffect)
        val drag = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 1234)
        controller.userScrolled(drag, 1258)
        controller.scrollFinished(top.id, 0, false)
        controller.endUserScroll(drag)
        assertEquals(134L, controller.state.value.anchor?.lineId)
        assertEquals(0, controller.state.value.anchor?.clippedTopPx)
        assertNull(controller.state.value.scrollEffect)
    }

    @Test fun imeHoldAcrossDelayedEagerMeasurementUsesVirtualTopNotDormantScroll() {
        val frame = frame()
        val controller = TerminalViewportController()
        controller.updateItemViewport(frame, view(frame, index = 50, top = -7, height = 31))
        val geometry = TerminalEagerViewportGeometry(frame, IntArray(frame.rows.size) { if (it == 50) 31 else 43 })
        val metrics = TerminalViewportMetrics(geometry.contentHeightPx - 100, 100, 20, 8,
            imeVisible = true, avoidIme = false)
        controller.updateViewport(frame, frame.rows.size, metrics, 123, null, true)
        controller.updateViewport(frame, frame.rows.size, metrics, 123, geometry, true)
        assertEquals(50 * 43 + 7, controller.state.value.scrollEffect?.targetScrollPx)
        assertTrue(controller.state.value.autoScroll)
    }

    @Test fun savedAnchorRestoresWithoutEverKnowingTotalHeight() {
        val frame = frame()
        val controller = TerminalViewportController(TerminalViewportState(autoScroll = false,
            verticalOffsetPx = 999999, anchorLineId = 175, anchorClippedTopPx = 9,
            anchorHistoryGeneration = 1, anchorRowHeightPx = 45))
        controller.updateItemViewport(frame, null)
        assertFalse(controller.state.value.initialized)
        controller.updateItemViewport(frame, view(frame))
        val target = controller.state.value.scrollEffect!!.itemTarget as TerminalItemScrollTarget.Anchor
        assertEquals(175L, target.anchor.lineId)
        assertEquals(45, target.capturedRowHeightPx)
        assertEquals(0, controller.state.value.scrollEffect!!.targetScrollPx)
    }

    @Test fun restoredUnknownHeightBindsOnlyTheRequestedMeasuredRowThenScalesWithoutDrift() {
        val frame = frame()
        val controller = TerminalViewportController(TerminalViewportState(autoScroll = false,
            anchorLineId = 175, anchorClippedTopPx = 7, anchorHistoryGeneration = 1))
        controller.updateItemViewport(frame, view(frame, index = 50, height = 19))
        assertEquals(0, controller.state.value.anchorRowHeightPx) // Unrelated visible row is NOT a scale.
        val restore = requireNotNull(controller.state.value.scrollEffect)
        controller.observeItemViewport(view(frame, index = 75, top = -7, height = 31))
        controller.scrollFinished(restore.id, 0, true)
        assertEquals(31, controller.state.value.anchorRowHeightPx)
        assertEquals(7, controller.state.value.anchor?.clippedTopPx)
        assertNull(controller.state.value.scrollEffect)
        repeat(3) {
            controller.updateItemViewport(frame, view(frame, index = 75, top = -7, height = 45))
            val effect = requireNotNull(controller.state.value.scrollEffect)
            val target = effect.itemTarget as TerminalItemScrollTarget.Anchor
            assertEquals(31, target.capturedRowHeightPx)
            assertEquals(10, terminalScaledItemClip(target.anchor.clippedTopPx, target.capturedRowHeightPx, 45))
            controller.observeItemViewport(view(frame, index = 75, top = -10, height = 45))
            controller.scrollFinished(effect.id, 0, true)
            assertNull(controller.state.value.scrollEffect)
            controller.updateItemViewport(frame, view(frame, index = 75, top = -7, height = 31))
            assertNull(controller.state.value.scrollEffect)
        }
        assertEquals(31, controller.state.value.anchorRowHeightPx)
        assertEquals(7, controller.state.value.anchor?.clippedTopPx)
    }

    @Test fun unknownRestoredScaleIgnoresStaleLayoutAndNeverOverwritesAKnownScale() {
        val frame = frame()
        val next = frame.copy()
        val controller = TerminalViewportController(TerminalViewportState(autoScroll = false,
            anchorLineId = 150, anchorClippedTopPx = 7, anchorHistoryGeneration = 1))
        controller.updateItemViewport(next, view(frame, height = 31))
        assertEquals(0, controller.state.value.anchorRowHeightPx)
        assertFalse(controller.state.value.initialized)
        controller.updateItemViewport(next, view(next, height = 31))
        assertEquals(31, controller.state.value.anchorRowHeightPx)
        controller.updateItemViewport(next, view(next, height = 47))
        assertEquals(31, controller.state.value.anchorRowHeightPx)
        assertEquals(7, controller.state.value.anchor?.clippedTopPx)
        val known = TerminalViewportController(TerminalViewportState(autoScroll = false,
            anchorLineId = 150, anchorClippedTopPx = 7, anchorHistoryGeneration = 1, anchorRowHeightPx = 29))
        known.updateItemViewport(next, view(next, height = 47))
        assertEquals(29, known.state.value.anchorRowHeightPx)
    }

    @Test fun firstKnownHeightClampsLegacyClipToTheActualRowAndKeepsItAcrossHandoff() {
        val frame = frame()
        val controller = TerminalViewportController(TerminalViewportState(autoScroll = false,
            anchorLineId = 150, anchorClippedTopPx = 999, anchorHistoryGeneration = 1))
        controller.updateItemViewport(frame, view(frame, top = -30, height = 31))
        assertEquals(31, controller.state.value.anchorRowHeightPx)
        assertEquals(30, controller.state.value.anchor?.clippedTopPx)
        assertNull(controller.state.value.scrollEffect)
        val geometry = TerminalEagerViewportGeometry(frame, IntArray(frame.rows.size) { 31 })
        val metrics = TerminalViewportMetrics(geometry.contentHeightPx - 100, 100, 20, 8)
        controller.updateViewport(frame, frame.rows.size, metrics, 0, geometry, true)
        val effect = requireNotNull(controller.state.value.scrollEffect)
        assertEquals(50 * 31 + 30, effect.targetScrollPx)
        controller.scrollFinished(effect.id, effect.targetScrollPx, true)
        controller.updateItemViewport(frame, view(frame, top = -30, height = 47))
        assertEquals(31, controller.state.value.anchorRowHeightPx)
        assertEquals(30, controller.state.value.anchor?.clippedTopPx)
    }

    @Test fun fontChangesScaleFromOriginalCaptureAndDoNotAccumulateRounding() {
        val frame = frame()
        val controller = TerminalViewportController(followInitially = false)
        controller.updateItemViewport(frame, view(frame, top = -13, height = 30))
        repeat(4) {
            controller.updateItemViewport(frame, view(frame, top = -13, height = 47))
            val effect = controller.state.value.scrollEffect!!
            val target = effect.itemTarget as TerminalItemScrollTarget.Anchor
            assertEquals(20, terminalScaledItemClip(target.anchor.clippedTopPx, target.capturedRowHeightPx, 47))
            controller.observeItemViewport(view(frame, top = -20, height = 47))
            controller.scrollFinished(effect.id, 0, true)
            assertNull(controller.state.value.scrollEffect)
            controller.updateItemViewport(frame, view(frame, top = -13, height = 30))
            assertNull(controller.state.value.scrollEffect)
        }
        assertEquals(30, controller.state.value.anchorRowHeightPx)
        assertEquals(13, controller.state.value.anchor?.clippedTopPx)
    }

    @Test fun trimKeepsSurvivingIdThenReplacesRemovedHistoryButClearFollows() {
        val initial = frame()
        val controller = TerminalViewportController(followInitially = false)
        controller.updateItemViewport(initial, view(initial))
        val trimmed = frame(first = 120, revision = 2)
        controller.updateItemViewport(trimmed, view(trimmed, index = 30))
        assertNull(controller.state.value.scrollEffect)
        assertEquals(150L, controller.state.value.anchor?.lineId)
        val removed = frame(first = 160, revision = 3)
        controller.updateItemViewport(removed, view(removed))
        assertEquals(160L, controller.state.value.anchor?.lineId)
        assertFalse(controller.state.value.autoScroll)
        val cleared = frame(first = 100, generation = 2, revision = 4)
        controller.updateItemViewport(cleared, view(cleared))
        assertEquals(TerminalItemScrollTarget.Follow, controller.state.value.scrollEffect?.itemTarget)
        assertTrue(controller.state.value.autoScroll)
    }

    @Test fun staleFrameWithSameRevisionCannotDriveCurrentLayout() {
        val initial = frame()
        val next = frame(first = 200)
        val controller = TerminalViewportController(followInitially = false)
        controller.updateItemViewport(initial, view(initial))
        controller.updateItemViewport(next, view(initial))
        assertNull(controller.state.value.scrollEffect)
        assertFalse(controller.state.value.autoScroll)
        assertNotEquals(view(initial), view(next))
        controller.updateItemViewport(next, view(next))
        assertEquals(200L, controller.state.value.anchor?.lineId)
    }

    @Test fun outputDuringJumpAndStaleCompletionCannotOverrideNewDrag() {
        val frame = frame()
        val controller = TerminalViewportController(followInitially = false)
        controller.updateItemViewport(frame, view(frame))
        controller.jumpToBottom(0)
        val jump = controller.state.value.scrollEffect!!
        assertTrue(jump.animated)
        assertEquals(TerminalItemScrollTarget.Follow, jump.itemTarget)
        val next = frame(first = 101, revision = 2)
        controller.updateItemViewport(next, view(next))
        assertEquals(jump, controller.state.value.scrollEffect)
        val token = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 0)
        controller.observeItemViewport(view(next, index = 60, top = -11, height = 33))
        controller.userScrolled(token, 0)
        controller.scrollFinished(jump.id, 0, true)
        controller.endUserScroll(token)
        assertFalse(controller.state.value.autoScroll)
        assertEquals(161L, controller.state.value.anchor?.lineId)
        assertEquals(11, controller.state.value.anchor?.clippedTopPx)
        assertNull(controller.state.value.scrollEffect)
    }

    @Test fun topJumpCapturesLatestFirstLineAfterTrimAndRemainsLocked() {
        val initial = frame()
        val controller = TerminalViewportController()
        controller.updateItemViewport(initial, view(initial))
        controller.jumpToTop(0)
        val jump = controller.state.value.scrollEffect!!
        assertEquals(TerminalItemScrollTarget.Top, jump.itemTarget)
        val next = frame(first = 130, revision = 2)
        controller.updateItemViewport(next, view(next))
        controller.observeItemViewport(view(next, index = 0, top = 0, atTop = true))
        controller.scrollFinished(jump.id, 0, true)
        assertEquals(130L, controller.state.value.anchor?.lineId)
        assertFalse(controller.state.value.autoScroll)
        assertNull(controller.state.value.scrollEffect)
    }

    @Test fun lockDuringUnmeasuredFrameIsNotLost() {
        val frame = frame()
        val controller = TerminalViewportController()
        controller.updateItemViewport(frame, null)
        controller.setFollow(false, 0)
        assertFalse(controller.state.value.autoScroll)
        controller.updateItemViewport(frame, view(frame, index = 42))
        assertEquals(142L, controller.state.value.anchor?.lineId)
        assertNull(controller.state.value.scrollEffect)
    }

    @Test fun consumedDragDuringUnmeasuredOutputCannotResumeFollow() {
        val initial = frame()
        val controller = TerminalViewportController()
        controller.updateItemViewport(initial, view(initial))
        val token = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 0)
        val next = frame(first = 105, revision = 2)
        controller.updateItemViewport(next, null)
        controller.userScrolled(token, 0)
        controller.endUserScroll(token)
        assertFalse(controller.state.value.autoScroll)
        controller.updateItemViewport(next, view(next, index = 65, top = -9))
        assertEquals(170L, controller.state.value.anchor?.lineId)
        assertEquals(9, controller.state.value.anchor?.clippedTopPx)
        assertNull(controller.state.value.scrollEffect)
    }

    @Test fun semanticBottomAndContractedViewportUseMeasuredContentNotStructuralTail() {
        val base = frame()
        val short = base.copy(contentBounds = TerminalEmulator.ContentBounds(0, 101, 102))
        val observation = view(short, index = 101, top = 52, height = 40)
        assertTrue(observation.isSatisfied(TerminalItemScrollTarget.Follow))
        assertFalse(observation.copy(viewportHeightPx = 60).isSatisfied(TerminalItemScrollTarget.Follow))
        val controller = TerminalViewportController()
        controller.updateItemViewport(short, observation)
        assertNull(controller.state.value.scrollEffect)
        controller.updateItemViewport(short, observation.copy(viewportHeightPx = 60))
        assertEquals(TerminalItemScrollTarget.Follow, controller.state.value.scrollEffect?.itemTarget)
    }

    @Test fun screenAnchorMayArchiveButHistoryCannotMigrateBackIntoScreen() {
        val frame = frame()
        val lookup = TerminalViewportLineLookup()
        val archived = frame.copy(historyLineIds = frame.historyLineIds.drop(1) + 1000L,
            screenLineIds = listOf(1001, 1002, 1003, 1004), screenGeneration = 2)
        val oldScreen = ViewportAnchor(1000, 5, 1, null)
        assertEquals(ViewportAnchor(1000, 5, null, 1), resolveTerminalItemAnchor(archived, oldScreen, lookup))
        assertNull(resolveTerminalItemAnchor(frame, ViewportAnchor(1000, 5, null, 1), lookup))
        assertNull(resolveTerminalItemAnchor(archived, ViewportAnchor(1001, 5, 1, null), lookup))
    }
}
