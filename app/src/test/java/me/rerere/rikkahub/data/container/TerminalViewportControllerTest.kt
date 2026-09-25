package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalViewportControllerTest {
    private fun makeFrame(
        history: List<Long> = (100L..119L).toList(),
        screen: List<Long> = (1000L..1009L).toList(),
        historyGeneration: Long = 1,
        screenGeneration: Long = 1,
        revision: Long = 1,
        bottomScreenRow: Int = screen.lastIndex,
    ): TerminalEmulator.RenderFrame {
        val lastRow = history.size + bottomScreenRow
        return TerminalEmulator.RenderFrame(
            rows = List(history.size + screen.size) { row ->
                TerminalEmulator.RenderedRow(AnnotatedString(if (row <= lastRow) "row$row" else ""))
            },
            contentBounds = TerminalEmulator.ContentBounds(0, lastRow, lastRow + 1),
            screenContentBounds = TerminalEmulator.ContentBounds(0, bottomScreenRow, bottomScreenRow + 1),
            screenStartRow = history.size,
            historyCount = history.size,
            historyStartId = history.firstOrNull() ?: 0,
            historyEndId = history.lastOrNull() ?: 0,
            historyLineIds = history,
            historyGeneration = historyGeneration,
            screenLineIds = screen,
            screenGeneration = screenGeneration,
            cursorRow = 0,
            cursorVisible = false,
            isAlternateScreen = false,
            modeSummary = "",
            revision = revision,
        )
    }

    private fun metricsFor(frame: TerminalEmulator.RenderFrame = makeFrame()) = TerminalViewportMetrics(
        maxScrollPx = (frame.rows.size * 20 - 100).coerceAtLeast(0),
        viewportHeightPx = 100,
        cellHeightPx = 20,
        tailPaddingPx = 0,
    )

    private fun TerminalViewportController.update(
        frame: TerminalEmulator.RenderFrame = makeFrame(),
        metrics: TerminalViewportMetrics = metricsFor(frame),
        scroll: Int = 0,
    ) = updateViewport(frame, frame.rows.size, metrics, scroll)

    private fun TerminalViewportController.effect() = requireNotNull(state.value.scrollEffect)

    private fun TerminalViewportController.finish() {
        val effect = effect()
        scrollFinished(effect.id, effect.targetScrollPx, completed = true)
    }

    private fun lockedController(scroll: Int = 73): TerminalViewportController = TerminalViewportController().apply {
        update()
        val drag = beginUserScroll(ViewportScrollOrigin.USER_DRAG, 0)
        userScrolled(drag, scroll)
        endUserScroll(drag)
    }

    @Test
    fun waitsForRealMetricsBeforeResolvingSavedSemanticAnchor() {
        val controller = TerminalViewportController(restored = TerminalViewportState(
            autoScroll = false,
            verticalOffsetPx = 400,
            anchorLineId = 103,
            anchorClippedTopPx = 13,
            anchorCellHeightPx = 20,
            anchorHistoryGeneration = 1,
        ))
        controller.update(metrics = metricsFor().copy(viewportHeightPx = 0))
        assertFalse(controller.state.value.initialized)
        assertNull(controller.state.value.scrollEffect)
        controller.update(metrics = metricsFor().copy(maxScrollPx = Int.MAX_VALUE))
        assertFalse(controller.state.value.initialized)

        controller.update()
        assertEquals(73, controller.effect().targetScrollPx)
        assertEquals(ViewportScrollOrigin.RESTORE, controller.effect().origin)
        controller.finish()
        assertNull(controller.state.value.scrollEffect)
        assertFalse(controller.state.value.autoScroll)
    }

    @Test
    fun legacyPixelOnlyRestoreCapturesAnAnchorOnce() {
        val controller = TerminalViewportController(restored = TerminalViewportState(
            autoScroll = false, verticalOffsetPx = 73,
        ))
        controller.update()
        assertEquals(103L, controller.state.value.anchor?.lineId)
        assertEquals(13, controller.state.value.anchor?.clippedTopPx)
        assertEquals(73, controller.effect().targetScrollPx)
        controller.finish()
        controller.update(makeFrame(history = (101L..120L).toList(), revision = 2), scroll = 73)
        assertEquals(53, controller.effect().targetScrollPx)
    }

    @Test
    fun tailFollowsNewOutputWithoutAcquiringAnAnchor() {
        val controller = TerminalViewportController()
        controller.update()
        assertEquals(500, controller.effect().targetScrollPx)
        controller.finish()
        controller.update(makeFrame(history = (100L..121L).toList(), revision = 2), scroll = 500)
        assertEquals(540, controller.effect().targetScrollPx)
        assertTrue(controller.state.value.autoScroll)
        assertNull(controller.state.value.anchor)
    }

    @Test
    fun repeatedFrameAtSameTargetDoesNotRestartPendingEffect() {
        val controller = TerminalViewportController()
        controller.update()
        val pending = controller.effect()
        controller.update(makeFrame(revision = 2))
        assertEquals(pending, controller.effect())
    }

    @Test
    fun staleProgrammaticCompletionCannotOverwriteNewerEffect() {
        val controller = TerminalViewportController()
        controller.update()
        val old = controller.effect()
        controller.update(makeFrame(history = (100L..121L).toList(), revision = 2))
        val newer = controller.effect()
        assertFalse(controller.isCurrent(old))
        controller.scrollFinished(old.id, 99, completed = true)
        assertEquals(newer, controller.effect())
        assertEquals(ViewportMode.TAIL, controller.state.value.mode)
    }

    @Test
    fun programmaticCancellationDoesNotConvertFollowToLocked() {
        val controller = TerminalViewportController()
        controller.update()
        controller.scrollFinished(controller.effect().id, 99, completed = false)
        assertTrue(controller.state.value.autoScroll)
        assertNull(controller.state.value.anchor)
        assertNull(controller.state.value.scrollEffect)
        controller.update(makeFrame(revision = 2), scroll = 99)
        assertEquals(500, controller.effect().targetScrollPx)
    }

    @Test
    fun newDragInvalidatesPendingScrollAndIgnoresItsCleanup() {
        val controller = TerminalViewportController()
        controller.update()
        val old = controller.effect()
        val token = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 0)
        assertFalse(controller.isCurrent(old))
        controller.userScrolled(token, 73)
        controller.scrollFinished(old.id, 500, completed = true)
        assertEquals(103L, controller.state.value.anchor?.lineId)
        assertNull(controller.state.value.scrollEffect)
        assertEquals(token, controller.state.value.gesture?.id)
    }

    @Test
    fun frameAndImeChangesWaitForDragToFinish() {
        val controller = TerminalViewportController()
        controller.update()
        val token = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 0)
        controller.userScrolled(token, 73)
        val trimmed = makeFrame(history = (101L..120L).toList(), revision = 2)
        controller.update(trimmed, metricsFor(trimmed).copy(
            viewportHeightPx = 60, maxScrollPx = 540, imeVisible = true, avoidIme = true,
        ), scroll = 73)
        assertNull(controller.state.value.scrollEffect)
        assertEquals(103L, controller.state.value.anchor?.lineId)

        controller.endUserScroll(token)
        assertEquals(53, controller.effect().targetScrollPx)
        assertFalse(controller.state.value.autoScroll)
    }

    @Test
    fun finalConsumedFlingDeltaIsNotLostWhenScrollingBecomesIdle() {
        val controller = TerminalViewportController()
        controller.update()
        val token = controller.beginUserScroll(ViewportScrollOrigin.USER_FLING, 0)
        controller.userScrolled(token, 65)
        controller.userScrolled(token, 73)
        controller.endUserScroll(token)
        assertEquals(13, controller.state.value.anchor?.clippedTopPx)
        assertNull(controller.state.value.scrollEffect)
        controller.update(makeFrame(history = (101L..120L).toList(), revision = 2), scroll = 73)
        assertEquals(53, controller.effect().targetScrollPx)
    }

    @Test
    fun oldFlingFinallyCannotEndANewerDrag() {
        val controller = TerminalViewportController()
        controller.update()
        val fling = controller.beginUserScroll(ViewportScrollOrigin.USER_FLING, 0)
        controller.userScrolled(fling, 73)
        val drag = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 73)
        assertNotEquals(fling, drag)
        controller.endUserScroll(fling)
        controller.userScrolled(fling, 500)
        assertEquals(drag, controller.state.value.gesture?.id)
        assertFalse(controller.state.value.autoScroll)
        controller.userScrolled(drag, 47)
        controller.endUserScroll(drag)
        assertEquals(102L, controller.state.value.anchor?.lineId)
        assertEquals(7, controller.state.value.anchor?.clippedTopPx)
    }

    @Test
    fun draggingBackToBottomExplicitlyResumesFollow() {
        val controller = lockedController()
        val token = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 73)
        controller.userScrolled(token, 500)
        controller.endUserScroll(token)
        assertTrue(controller.state.value.autoScroll)
        assertNull(controller.state.value.anchor)
    }

    @Test
    fun outputDuringExplicitJumpIsAppliedAfterAnimationNotDuringIt() {
        val controller = lockedController()
        controller.jumpToBottom(73)
        val jump = controller.effect()
        assertTrue(jump.animated)
        controller.update(makeFrame(history = (100L..121L).toList(), revision = 2), scroll = 100)
        assertEquals(jump, controller.effect())
        controller.scrollFinished(jump.id, 500, completed = true)
        assertEquals(540, controller.effect().targetScrollPx)
        assertFalse(controller.effect().animated)
        assertTrue(controller.state.value.autoScroll)
    }

    @Test
    fun measuredAnchorTargetIsForwardedWithoutChangingEagerFallbackDefaults() {
        val controller = lockedController()
        controller.setMeasuredAnchorScrollPx(317)
        controller.update(revision = 2)
        assertEquals(317, controller.effect().targetScrollPx)
        controller.setMeasuredAnchorScrollPx(null)
        controller.finish()
        controller.update(revision = 3, scroll = 317)
        assertEquals(73, controller.effect().targetScrollPx)
    }

    @Test
    fun jumpToTopStaysLockedDuringItsProgrammaticAnimation() {
        val controller = lockedController()
        controller.jumpToTop(73)
        val jump = controller.effect()
        controller.update(makeFrame(revision = 2), scroll = 35)
        assertEquals(jump, controller.effect())
        controller.finish()
        assertEquals(100L, controller.state.value.anchor?.lineId)
        assertFalse(controller.state.value.autoScroll)
        assertNull(controller.state.value.scrollEffect)
    }

    @Test
    fun userCanInterruptAnExplicitJumpWithoutBeingPulledBack() {
        val controller = lockedController()
        controller.jumpToBottom(73)
        val jump = controller.effect()
        val drag = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 120)
        controller.userScrolled(drag, 113)
        controller.scrollFinished(jump.id, 120, completed = false)
        controller.endUserScroll(drag)
        assertEquals(105L, controller.state.value.anchor?.lineId)
        assertEquals(13, controller.state.value.anchor?.clippedTopPx)
        assertNull(controller.state.value.scrollEffect)
    }

    @Test
    fun temporaryLayoutClampAndFullListPlacementDoNotRecaptureAnchor() {
        val controller = lockedController(213)
        val anchor = controller.state.value.anchor
        controller.update(metrics = metricsFor().copy(maxScrollPx = 0, viewportHeightPx = 600), scroll = 0)
        assertEquals(anchor, controller.state.value.anchor)
        assertNull(controller.state.value.scrollEffect)
        controller.update(metrics = metricsFor(), scroll = 0)
        assertEquals(213, controller.effect().targetScrollPx)
        assertEquals(anchor, controller.state.value.anchor)
    }

    @Test
    fun incompleteMetadataPausesRatherThanUnlockingOrApplyingAnOldEffect() {
        val controller = lockedController()
        controller.update(metrics = metricsFor().copy(cellHeightPx = 30), scroll = 73)
        val pending = controller.effect()
        controller.update(makeFrame().copy(historyLineIds = emptyList()), scroll = 0)
        assertNull(controller.state.value.scrollEffect)
        assertFalse(controller.isCurrent(pending))
        assertFalse(controller.state.value.autoScroll)
        controller.update(scroll = 0)
        assertEquals(73, controller.effect().targetScrollPx)
    }

    @Test
    fun repeatedFontChangesUseOriginalCaptureScaleWithoutRoundingDrift() {
        val controller = lockedController()
        val original = controller.state.value.anchor
        repeat(3) {
            controller.update(metrics = metricsFor().copy(cellHeightPx = 31, maxScrollPx = 830), scroll = 73)
            assertEquals(113, controller.effect().targetScrollPx) // 3 * 31 + round(13 * 31 / 20)
            controller.finish()
            controller.update(scroll = 113)
            assertEquals(73, controller.effect().targetScrollPx)
            controller.finish()
        }
        assertEquals(20, controller.state.value.anchorCellHeightPx)
        assertEquals(original, controller.state.value.anchor)
    }

    @Test
    fun historyTrimAndClearUseReducerPolicyThroughTheController() {
        val controller = lockedController()
        controller.update(makeFrame(history = (110L..129L).toList(), revision = 2), scroll = 73)
        assertEquals(110L, controller.state.value.anchor?.lineId)
        assertEquals(13, controller.effect().targetScrollPx)
        controller.finish()
        controller.update(makeFrame(history = (200L..219L).toList(), historyGeneration = 2, revision = 3), scroll = 13)
        assertTrue(controller.state.value.autoScroll)
        assertNull(controller.state.value.anchor)
        assertEquals(500, controller.effect().targetScrollPx)
    }

    @Test
    fun imeHideNeverRestoresPixelsCapturedBeforeNewUserInput() {
        val controller = TerminalViewportController()
        controller.update()
        controller.finish()
        val imeMetrics = metricsFor().copy(imeVisible = true, avoidIme = true, viewportHeightPx = 60, maxScrollPx = 540)
        controller.update(metrics = imeMetrics, scroll = 500)
        assertEquals(540, controller.effect().targetScrollPx)
        controller.finish()
        val drag = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 540)
        controller.userScrolled(drag, 73)
        controller.endUserScroll(drag)
        val trimmed = makeFrame(history = (101L..120L).toList(), revision = 2)
        controller.update(trimmed, metricsFor(trimmed), scroll = 73)
        assertEquals(53, controller.effect().targetScrollPx)
        assertEquals(103L, controller.state.value.anchor?.lineId)
        assertFalse(controller.state.value.autoScroll)
    }

    @Test
    fun followingImeHideUsesLatestOutputInsteadOfPreImePixels() {
        val controller = TerminalViewportController()
        controller.update()
        controller.finish()
        controller.update(metrics = metricsFor().copy(
            imeVisible = true, avoidIme = true, viewportHeightPx = 60, maxScrollPx = 540,
        ), scroll = 500)
        controller.finish()
        val appended = makeFrame(history = (100L..121L).toList(), revision = 2)
        controller.update(appended, metricsFor(appended), scroll = 540)
        assertNull(controller.state.value.scrollEffect) // latest tail is 540, not the old 500
        assertTrue(controller.state.value.autoScroll)
    }

    @Test
    fun shortTuiImeHoldIsInvalidatedByAnExplicitJump() {
        val short = makeFrame(bottomScreenRow = 1)
        val controller = TerminalViewportController()
        val tuiMetrics = metricsFor(short).copy(usesTuiViewport = true)
        controller.update(short, tuiMetrics)
        assertEquals(400, controller.effect().targetScrollPx)
        controller.finish()
        controller.update(short, tuiMetrics.copy(imeVisible = true), scroll = 400)
        val appended = makeFrame(history = (100L..121L).toList(), bottomScreenRow = 1, revision = 2)
        controller.update(appended, metricsFor(appended).copy(usesTuiViewport = true, imeVisible = true), scroll = 400)
        assertNull(controller.state.value.scrollEffect)
        controller.jumpToBottom(400)
        assertEquals(440, controller.effect().targetScrollPx)
    }

    @Test
    fun tuiScreenResetDiscardsThePreviousImeBottomRow() {
        val controller = TerminalViewportController()
        val tuiMetrics = metricsFor().copy(usesTuiViewport = true, imeVisible = true, avoidIme = true)
        controller.update(metrics = tuiMetrics)
        controller.finish()
        val replaced = makeFrame(screen = (2000L..2009L).toList(), screenGeneration = 2, revision = 2, bottomScreenRow = 1)
        controller.update(replaced, tuiMetrics, scroll = 500)
        assertEquals(ViewportMode.SCREEN, controller.state.value.mode)
        assertEquals(400, controller.effect().targetScrollPx)
    }

    @Test(expected = IllegalArgumentException::class)
    fun programmaticOriginsCannotEnterTheUserInputPath() {
        TerminalViewportController().beginUserScroll(ViewportScrollOrigin.IME, 0)
    }
}
