package me.rerere.rikkahub.data.container

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.pages.container.TerminalViewportFlingBehavior
import me.rerere.rikkahub.ui.pages.container.TerminalViewportGestureConfig
import me.rerere.rikkahub.ui.pages.container.TerminalViewportNestedScrollConnection
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFastFlingTest {
    private fun TerminalFastFlingTracker.fling(
        at: Long,
        y: Float = -4_000f,
        x: Float = 0f,
        enabled: Boolean = true,
        required: Int = 2,
    ) = onFling(x, y, at, enabled, required)

    @Test
    fun configuredCountsOneThroughFiveWorkInBothDirections() {
        for (required in 1..5) {
            for ((y, edge) in listOf(-4_000f to TerminalJumpEdge.BOTTOM, 4_000f to TerminalJumpEdge.TOP)) {
                val tracker = TerminalFastFlingTracker()
                repeat(required - 1) { assertNull(tracker.fling(it * 100L, y, required = required)) }
                assertEquals(edge, tracker.fling((required - 1) * 100L, y, required = required))
            }
        }
    }

    @Test
    fun speedDiagonalAndWindowBoundariesKeepTheExistingThresholds() {
        val tracker = TerminalFastFlingTracker()
        assertNull(tracker.fling(1_000, y = -3_499f))
        assertNull(tracker.fling(1_000, y = -3_500f, x = 2_801f))
        assertNull(tracker.fling(1_000, y = -3_500f, x = 2_800f))
        assertEquals(TerminalJumpEdge.BOTTOM, tracker.fling(1_700, y = -3_500f))
    }

    @Test
    fun changingDirectionOrExceedingTheWindowRestartsTheSequence() {
        val tracker = TerminalFastFlingTracker()
        assertNull(tracker.fling(1_000))
        assertNull(tracker.fling(1_100, y = 4_000f))
        assertNull(tracker.fling(1_200))
        assertNull(tracker.fling(1_901))
        assertEquals(TerminalJumpEdge.BOTTOM, tracker.fling(2_001))
    }

    @Test
    fun ineligibleFlingsDoNotCountAndTriggeringStartsANewSequence() {
        val tracker = TerminalFastFlingTracker()
        assertNull(tracker.fling(1_000, enabled = false))
        assertNull(tracker.fling(1_100))
        assertNull(tracker.fling(1_200, y = -500f))
        assertNull(tracker.fling(1_300, x = 8_000f))
        assertEquals(TerminalJumpEdge.BOTTOM, tracker.fling(1_400))
        assertNull(tracker.fling(1_500))
        assertEquals(TerminalJumpEdge.BOTTOM, tracker.fling(1_600))
    }

    @Test
    fun invalidVelocityOrBackwardsClockCannotProduceAnAccidentalJump() {
        val tracker = TerminalFastFlingTracker()
        assertNull(tracker.fling(1_000, y = Float.NaN))
        assertNull(tracker.fling(1_000, y = Float.POSITIVE_INFINITY))
        assertNull(tracker.fling(1_000, x = Float.NaN))
        assertNull(tracker.fling(1_000))
        assertNull(tracker.fling(900))
        assertEquals(TerminalJumpEdge.BOTTOM, tracker.fling(1_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroRequiredCountIsNotASupportedSetting() {
        TerminalFastFlingTracker().fling(1_000, required = 0)
    }

    private fun frame(historyRows: Int = 100, revision: Long = 1): TerminalEmulator.RenderFrame {
        val screenRows = 24
        return TerminalEmulator.RenderFrame(
            rows = List(historyRows + screenRows) { TerminalEmulator.RenderedRow(AnnotatedString("row$it")) },
            contentBounds = TerminalEmulator.ContentBounds(0, historyRows + screenRows - 1, historyRows + screenRows),
            screenContentBounds = TerminalEmulator.ContentBounds(0, screenRows - 1, screenRows),
            screenStartRow = historyRows,
            historyCount = historyRows,
            historyStartId = 100,
            historyEndId = 100L + historyRows - 1,
            historyLineIds = List(historyRows) { 100L + it },
            historyGeneration = 1,
            screenLineIds = List(screenRows) { 1_000L + it },
            screenGeneration = 1,
            cursorRow = 0,
            cursorVisible = false,
            isAlternateScreen = false,
            modeSummary = "",
            revision = revision,
        )
    }

    private inner class Fixture(required: Int = 2) {
        var px = 400
        var now = 1_000L
        var config = TerminalViewportGestureConfig(panEnabled = true, selectionMode = false, required)
        val controller = TerminalViewportController(TerminalViewportState(autoScroll = false, verticalOffsetPx = px))
        val connection = TerminalViewportNestedScrollConnection(
            controller, TerminalFastFlingTracker(), { config }, { px }, { now },
        )
        val scope = object : ScrollScope {
            override fun scrollBy(pixels: Float): Float {
                px += pixels.toInt()
                return pixels
            }
        }

        init { update() }

        fun update(frame: TerminalEmulator.RenderFrame = frame()) {
            controller.updateViewport(
                frame, frame.rows.size,
                TerminalViewportMetrics(frame.rows.size * 20 - 200, 200, 20, 0), px,
            )
        }

        fun effect() = requireNotNull(controller.state.value.scrollEffect)

        suspend fun fling(velocity: Velocity = Velocity(0f, -4_000f)): Velocity {
            now += 100
            return connection.onPreFling(velocity)
        }
    }

    @Test
    fun fastBottomJumpConsumesAllVelocityAndZeroChildFlingCannotCancelIt() = runBlocking {
        val f = Fixture()
        assertEquals(Velocity.Zero, f.fling())
        val velocity = Velocity(200f, -4_000f)
        assertEquals(velocity, f.fling(velocity))
        val jump = f.effect()
        assertEquals(ViewportMode.TAIL, f.controller.state.value.mode)
        assertEquals(ViewportScrollOrigin.JUMP, jump.origin)
        assertTrue(jump.animated)
        assertEquals(2_280, jump.targetScrollPx)
        var delegated = false
        val behavior = TerminalViewportFlingBehavior(f.controller, { f.px }, object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                delegated = true
                return initialVelocity
            }
        })
        with(behavior) { assertEquals(0f, f.scope.performFling(0f), 0f) }
        assertFalse(delegated)
        assertTrue(f.controller.isCurrent(jump))
    }

    @Test
    fun fastTopJumpLocksFirstStableLineAndProgrammaticDeltasCannotRecaptureIt() = runBlocking {
        val f = Fixture()
        assertEquals(Velocity.Zero, f.fling(Velocity(0f, 4_000f)))
        assertEquals(Velocity(0f, 4_000f), f.fling(Velocity(0f, 4_000f)))
        val jump = f.effect()
        assertEquals(0, jump.targetScrollPx)
        assertEquals(ViewportMode.LOCKED, f.controller.state.value.mode)
        assertEquals(100L, f.controller.state.value.anchor?.lineId)
        f.px = 200
        f.connection.onPostScroll(Offset(0f, 200f), Offset.Zero, NestedScrollSource.SideEffect)
        assertTrue(f.controller.isCurrent(jump))
        assertEquals(100L, f.controller.state.value.anchor?.lineId)
        f.controller.scrollFinished(jump.id, 0, completed = true)
        assertNull(f.controller.state.value.scrollEffect)
        assertFalse(f.controller.state.value.autoScroll)
    }

    @Test
    fun nearEdgeThresholdsDoNotConsumeANormalFlingOrInventAJump() = runBlocking {
        val f = Fixture(required = 1)
        f.px = TERMINAL_EDGE_THRESHOLD_PX
        assertEquals(Velocity.Zero, f.fling(Velocity(0f, 4_000f)))
        f.px++
        assertEquals(Velocity(0f, 4_000f), f.fling(Velocity(0f, 4_000f)))
        val bottom = Fixture(required = 1)
        bottom.px = 2_280 - 40 // Existing two-cell near-bottom tolerance.
        assertEquals(Velocity.Zero, bottom.fling())
        bottom.px--
        assertEquals(Velocity(0f, -4_000f), bottom.fling())
    }

    @Test
    fun selectionAndMouseModesDisableFastJumpsWithoutChangingControllerIntent() = runBlocking {
        val f = Fixture(required = 1)
        for (config in listOf(
            TerminalViewportGestureConfig(panEnabled = false, selectionMode = false, 1),
            TerminalViewportGestureConfig(panEnabled = true, selectionMode = true, 1),
        )) {
            f.config = config
            assertEquals(Velocity.Zero, f.fling())
            assertEquals(Velocity.Zero, f.fling(Velocity(0f, 4_000f)))
            assertNull(f.controller.state.value.scrollEffect)
            assertFalse(f.controller.state.value.autoScroll)
        }
        // Read the CURRENT config from the same connection, not a closure retained from MOUSE mode.
        f.config = TerminalViewportGestureConfig(panEnabled = true, selectionMode = false, 1)
        assertEquals(Velocity(0f, -4_000f), f.fling())
    }

    @Test
    fun outputDuringJumpIsDeferredThenReconciledToTheNewBottom() = runBlocking {
        val f = Fixture(required = 1)
        f.fling()
        val jump = f.effect()
        f.px = 800
        f.update(frame(historyRows = 103, revision = 2))
        assertEquals(jump, f.effect())
        f.controller.scrollFinished(jump.id, jump.targetScrollPx, completed = true)
        val correction = f.effect()
        assertNotEquals(jump.id, correction.id)
        assertEquals(2_340, correction.targetScrollPx)
        assertFalse(correction.animated)
        assertTrue(f.controller.state.value.autoScroll)
    }

    @Test
    fun realDragInterruptsJumpAndOldCompletionCannotPullTheViewportBack() = runBlocking {
        val f = Fixture(required = 1)
        f.fling()
        val jump = f.effect()
        f.connection.onPreScroll(Offset(0f, -10f), NestedScrollSource.UserInput)
        f.px = 413
        f.connection.onPostScroll(Offset(0f, -13f), Offset.Zero, NestedScrollSource.UserInput)
        val gesture = requireNotNull(f.controller.state.value.gesture)
        assertFalse(f.controller.isCurrent(jump))
        assertEquals(120L, f.controller.state.value.anchor?.lineId)
        assertEquals(13, f.controller.state.value.anchor?.clippedTopPx)
        f.controller.scrollFinished(jump.id, jump.targetScrollPx, completed = true)
        assertEquals(gesture, f.controller.state.value.gesture)
        f.controller.endUserScroll(gesture.id)
        assertNull(f.controller.state.value.scrollEffect)
        assertFalse(f.controller.state.value.autoScroll)
    }

    @Test
    fun cancelledOldFlingCannotEndTheDragThatReplacedIt() = runBlocking {
        val f = Fixture()
        val behavior = TerminalViewportFlingBehavior(f.controller, { f.px }, object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                f.connection.onPreScroll(Offset(0f, -10f), NestedScrollSource.UserInput)
                throw CancellationException("New pointer input superseded the fling")
            }
        })
        try {
            with(behavior) { f.scope.performFling(4_000f) }
            error("The delegated cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(ViewportScrollOrigin.USER_DRAG, f.controller.state.value.gesture?.origin)
        }
    }

    @Test
    fun finalConsumedNormalFlingDeltaCapturesTheClippedAnchor() = runBlocking {
        val f = Fixture()
        val behavior = TerminalViewportFlingBehavior(f.controller, { f.px }, object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                scrollBy(13f)
                f.connection.onPostScroll(Offset(0f, -13f), Offset.Zero, NestedScrollSource.SideEffect)
                return 0f
            }
        })
        with(behavior) { f.scope.performFling(4_000f) }
        assertNull(f.controller.state.value.gesture)
        assertEquals(120L, f.controller.state.value.anchor?.lineId)
        assertEquals(13, f.controller.state.value.anchor?.clippedTopPx)
        assertNull(f.controller.state.value.scrollEffect)
    }
}
