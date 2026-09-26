package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeWithVelocity
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.container.ViewportMode
import me.rerere.rikkahub.data.container.ViewportScrollOrigin
import org.junit.runner.Description
import org.junit.rules.TestWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Pointer-injection correctness tests, NOT performance samples or a complete lazy integration test. */
@RunWith(Parameterized::class)
class TerminalViewportGestureInstrumentedTest(private val lazyHistory: Boolean) {
    companion object {
        private const val PROBE_TAG = "TerminalViewportProbe"

        @JvmStatic
        @Parameterized.Parameters(name = "lazyHistory={0}")
        fun renderers(): List<Array<Boolean>> {
            val requestedArm = InstrumentationRegistry.getArguments()
                .getString("viewportLazyHistory")
            return when (requestedArm) {
                "false" -> listOf(arrayOf(false))
                "true" -> listOf(arrayOf(true))
                else -> listOf(arrayOf(false), arrayOf(true))
            }
        }
    }

    @get:Rule
    val compose = createComposeRule()

    // A stuck scroll mutation must produce a case-level JUnit failure, not consume the entire
    // 30-minute emulator job and hide which renderer/gesture combination stopped progressing.
    @get:Rule
    val caseTimeout = Timeout.seconds(45)

    @get:Rule
    val caseProbe = object : TestWatcher() {
        override fun starting(description: Description) {
            Log.i(PROBE_TAG, "JUnit starting lazy=$lazyHistory case=${description.methodName}")
        }

        override fun finished(description: Description) {
            Log.i(PROBE_TAG, "JUnit finished lazy=$lazyHistory case=${description.methodName}")
        }
    }

    private lateinit var viewport: ViewportGestureFixture

    @Before
    fun mountAtTheMiddleOfHistory() {
        Log.i(PROBE_TAG, "mount start lazy=$lazyHistory")
        viewport = ViewportGestureFixture(lazyHistory)
        compose.setContent { viewport.Content() }
        Log.i(PROBE_TAG, "content set lazy=$lazyHistory")
        settle()
        Log.i(PROBE_TAG, "settle complete lazy=$lazyHistory diagnostics=${viewport.diagnostics()}")
        compose.runOnIdle {
            assertTrue(viewport.controller.state.value.initialized)
            assertEquals(ViewportGestureFixture.INITIAL_PX, viewport.currentPx())
            assertFalse(viewport.isAtTop())
            assertFalse(viewport.isAtBottom())
        }
    }

    private fun settle() {
        Log.i(PROBE_TAG, "wait idle begin lazy=$lazyHistory")
        compose.waitForIdle()
        Log.i(PROBE_TAG, "wait idle complete lazy=$lazyHistory")
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
    }

    private fun fastSwipe(towardBottom: Boolean) {
        compose.runOnIdle { viewport.inputTimeMs += 100 }
        val node = compose.onNodeWithTag(ViewportGestureFixture.OUTPUT_TAG)
        val size = node.fetchSemanticsNode().size
        val top = Offset(size.width / 2f, size.height * 0.15f)
        val bottom = Offset(size.width / 2f, size.height * 0.85f)
        node.performTouchInput {
            swipeWithVelocity(
                start = if (towardBottom) bottom else top,
                end = if (towardBottom) top else bottom,
                endVelocity = 6_000f,
            )
        }
    }

    private fun assertNoJump() {
        settle()
        compose.runOnIdle {
            assertTrue(viewport.jumps.isEmpty())
            assertFalse(viewport.isAtTop())
            assertFalse(viewport.isAtBottom())
        }
    }

    @Test
    fun consecutiveUpwardSwipesJumpToActualBottomAndKeepTheScreenWhole() {
        fastSwipe(towardBottom = true)
        assertNoJump()
        fastSwipe(towardBottom = true)
        settle()
        compose.runOnIdle {
            assertEquals(1, viewport.jumps.size)
            assertTrue(viewport.diagnostics(), viewport.isAtBottom())
        }
        settle()
        compose.runOnIdle {
            assertTrue(viewport.diagnostics(), viewport.isAtBottom())
            assertEquals(ViewportMode.TAIL, viewport.controller.state.value.mode)
            assertEquals(1, viewport.composedScreens)
            assertEquals(1, viewport.maximumWriters)
            assertEquals(0, viewport.activeWriters)
            if (lazyHistory) assertTrue(viewport.composedHistory < ViewportGestureFixture.HISTORY_ROWS)
        }
    }

    @Test
    fun consecutiveDownwardSwipesJumpToActualTopAndStayLocked() {
        fastSwipe(towardBottom = false)
        assertNoJump()
        fastSwipe(towardBottom = false)
        settle()
        compose.runOnIdle {
            assertEquals(1, viewport.jumps.size)
            assertEquals(0, viewport.currentPx())
        }
        settle()
        compose.runOnIdle {
            assertTrue(viewport.diagnostics(), viewport.isAtTop())
            assertEquals(ViewportMode.LOCKED, viewport.controller.state.value.mode)
            assertEquals(viewport.frame.historyLineIds.first(), viewport.controller.state.value.anchor?.lineId)
            assertEquals(1, viewport.maximumWriters)
            assertEquals(0, viewport.activeWriters)
        }
    }

    @Test
    fun configuredThreeSwipesDoNotTriggerOnTheSecondSwipe() {
        compose.runOnIdle { viewport.config = viewport.config.copy(fastFlingRequiredCount = 3) }
        repeat(2) {
            fastSwipe(towardBottom = true)
            assertNoJump()
        }
        fastSwipe(towardBottom = true)
        settle()
        compose.runOnIdle {
            assertEquals(1, viewport.jumps.size)
            assertTrue(viewport.diagnostics(), viewport.isAtBottom())
        }
        settle()
        compose.runOnIdle { assertTrue(viewport.diagnostics(), viewport.isAtBottom()) }
    }

    @Test
    fun lazyLayoutPublishesMeasuredVisibleItemsAndRestoresStableAnchor() {
        if (!lazyHistory) return
        compose.runOnIdle {
            val measured = viewport.measuredVisibleItems()
            assertTrue(viewport.diagnostics(), measured.isNotEmpty())
            val anchor = viewport.captureMeasuredAnchorForTest()
            assertTrue(viewport.diagnostics(), anchor != null)
            assertEquals(viewport.currentPx(), viewport.resolveCapturedMeasuredAnchorForTest())
            viewport.publishMeasuredAnchorForTest()
        }
    }

    @Test
    fun slowSwipesPanWithoutTriggeringFastJump() {
        val node = compose.onNodeWithTag(ViewportGestureFixture.OUTPUT_TAG)
        val size = node.fetchSemanticsNode().size
        repeat(2) {
            compose.runOnIdle { viewport.inputTimeMs += 100 }
            node.performTouchInput {
                swipe(Offset(size.width / 2f, size.height * 0.8f), Offset(size.width / 2f, size.height * 0.2f), 600)
            }
        }
        assertNoJump()
        compose.runOnIdle { assertTrue(viewport.currentPx() > ViewportGestureFixture.INITIAL_PX) }
    }

    @Test
    fun selectionModeKeepsPanButDisablesFastJump() {
        compose.runOnIdle { viewport.config = viewport.config.copy(selectionMode = true) }
        repeat(2) { fastSwipe(towardBottom = true) }
        assertNoJump()
        compose.runOnIdle { assertTrue(viewport.currentPx() > ViewportGestureFixture.INITIAL_PX) }
    }

    @Test
    fun mouseModeDoesNotLetComposePanOrFastJump() {
        compose.runOnIdle { viewport.config = viewport.config.copy(panEnabled = false) }
        repeat(2) { fastSwipe(towardBottom = true) }
        assertNoJump()
        compose.runOnIdle { assertEquals(ViewportGestureFixture.INITIAL_PX, viewport.currentPx()) }
    }

    @Test
    fun horizontalSwipesStillPanHorizontallyWithoutTriggeringVerticalJump() {
        val node = compose.onNodeWithTag(ViewportGestureFixture.OUTPUT_TAG)
        val size = node.fetchSemanticsNode().size
        repeat(2) {
            compose.runOnIdle { viewport.inputTimeMs += 100 }
            node.performTouchInput {
                swipeWithVelocity(
                    Offset(size.width * 0.8f, size.height / 2f), Offset(size.width * 0.2f, size.height / 2f),
                    endVelocity = 6_000f,
                )
            }
        }
        assertNoJump()
        compose.runOnIdle {
            assertEquals(ViewportGestureFixture.INITIAL_PX, viewport.currentPx())
            assertTrue(viewport.horizontalScroll.value > 0)
        }
    }

    @Test
    fun thirtyAppendsAndTrimsDuringBottomJumpCannotLeaveTheTailBehind() {
        compose.runOnIdle { viewport.emitOutputDuringNextJump = true }
        repeat(2) { fastSwipe(towardBottom = true) }
        settle()
        compose.runOnIdle {
            assertEquals(30, viewport.emittedUpdates)
            assertEquals(1, viewport.jumps.size)
            assertTrue(viewport.diagnostics(), viewport.isAtBottom())
            assertEquals(ViewportMode.TAIL, viewport.controller.state.value.mode)
            assertEquals(1, viewport.composedScreens)
            assertEquals(1, viewport.maximumWriters)
            assertEquals(0, viewport.activeWriters)
        }
    }

    @Test
    fun thirtyAppendsAndTrimsDuringTopJumpNeverReenableFollow() {
        compose.runOnIdle { viewport.emitOutputDuringNextJump = true }
        repeat(2) { fastSwipe(towardBottom = false) }
        settle()
        compose.runOnIdle {
            assertEquals(30, viewport.emittedUpdates)
            assertEquals(1, viewport.jumps.size)
            assertTrue(viewport.diagnostics(), viewport.isAtTop())
            assertEquals(ViewportMode.LOCKED, viewport.controller.state.value.mode)
            assertEquals(viewport.frame.historyLineIds.first(), viewport.controller.state.value.anchor?.lineId)
            assertEquals(1, viewport.maximumWriters)
        }
    }

    @Test
    fun aNewPointerDragInterruptsJumpAndOldCompletionCannotResumeFollow() {
        Log.i(PROBE_TAG, "interrupt case start lazy=$lazyHistory")
        compose.runOnIdle { viewport.config = viewport.config.copy(fastFlingRequiredCount = 1) }
        compose.mainClock.autoAdvance = false
        try {
            fastSwipe(towardBottom = true)
            compose.mainClock.advanceTimeBy(32)
            // The lazy arm's scroll animation is intentionally still in flight here. runOnIdle
            // waits for Compose animations to become idle, but autoAdvance is disabled above, so
            // it would deadlock before the new pointer can interrupt that animation.
            compose.runOnUiThread {
                assertEquals(1, viewport.jumps.size)
                assertTrue(viewport.diagnostics(), viewport.controller.isCurrent(viewport.jumps.single()))
            }
            val node = compose.onNodeWithTag(ViewportGestureFixture.OUTPUT_TAG)
            val size = node.fetchSemanticsNode().size
            node.performTouchInput {
                down(Offset(size.width / 2f, size.height * 0.15f))
                repeat(3) { moveBy(Offset(0f, 100f), delayMillis = 32) }
            }
            compose.mainClock.advanceTimeBy(32)
            compose.runOnUiThread {
                assertFalse(viewport.controller.isCurrent(viewport.jumps.single()))
                assertEquals(ViewportScrollOrigin.USER_DRAG, viewport.controller.state.value.gesture?.origin)
                assertEquals(ViewportMode.LOCKED, viewport.controller.state.value.mode)
            }
            node.performTouchInput {
                advanceEventTime(500) // Release without creating another high-velocity fling.
                up()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        settle()
        compose.runOnIdle {
            assertEquals(1, viewport.jumps.size)
            assertNull(viewport.controller.state.value.gesture)
            assertEquals(ViewportMode.LOCKED, viewport.controller.state.value.mode)
            assertFalse(viewport.isAtBottom())
            assertEquals(1, viewport.maximumWriters)
            assertEquals(0, viewport.activeWriters)
        }
    }

}
