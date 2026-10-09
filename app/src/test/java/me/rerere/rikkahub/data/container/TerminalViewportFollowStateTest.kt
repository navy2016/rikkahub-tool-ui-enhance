package me.rerere.rikkahub.data.container

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.ui.pages.container.terminalFollowChanges
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalViewportFollowStateTest {
    @Test fun tenThousandEffectsAndGesturesDoNotPublishAnotherFollowValue() = runBlocking {
        val values = flow {
            emit(TerminalViewportControllerState(ViewportMode.TAIL))
            repeat(10_000) { index ->
                emit(TerminalViewportControllerState(ViewportMode.TAIL, initialized = true,
                    gesture = TerminalViewportGesture(index.toLong(), ViewportScrollOrigin.USER_DRAG),
                    scrollEffect = TerminalViewportScrollEffect(index.toLong(), index, ViewportScrollOrigin.REDUCER)))
            }
        }.terminalFollowChanges().toList()
        assertEquals(listOf(true), values)
    }

    @Test fun everyBooleanTransitionIsPreservedAndScreenTailShareFollowState() = runBlocking {
        val values = flowOf(ViewportMode.TAIL, ViewportMode.SCREEN, ViewportMode.LOCKED, ViewportMode.LOCKED,
            ViewportMode.SCREEN, ViewportMode.TAIL, ViewportMode.LOCKED)
            .map { TerminalViewportControllerState(it) }.terminalFollowChanges().toList()
        assertEquals(listOf(true, false, true, false), values)
    }

    @Test fun lockedAnchorAndCaptureScaleUpdatesKeepOneFalseValue() = runBlocking {
        val values = flow {
            repeat(1000) { index ->
                emit(TerminalViewportControllerState(ViewportMode.LOCKED,
                    anchor = ViewportAnchor(index.toLong(), index % 20, screenGeneration = null, historyGeneration = null),
                    anchorRowHeightPx = 20 + index,
                    anchorCellHeightPx = 18 + index, initialized = index > 0))
            }
        }.terminalFollowChanges().toList()
        assertEquals(listOf(false), values)
    }

    @Test fun coldProjectionHasNoBackgroundSubscriptionAndRestartReadsLatestState() = runBlocking {
        val source = MutableStateFlow(TerminalViewportControllerState(ViewportMode.TAIL))
        val projected = source.terminalFollowChanges()
        assertEquals(0, source.subscriptionCount.value)
        val values = mutableListOf<Boolean>()
        val first = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { projected.collect { values += it } }
        assertEquals(listOf(true), values)
        source.value = source.value.copy(mode = ViewportMode.LOCKED)
        assertEquals(listOf(true, false), values)
        first.cancelAndJoin()
        assertEquals(0, source.subscriptionCount.value)
        source.value = source.value.copy(mode = ViewportMode.SCREEN)
        val next = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { projected.collect { values += it } }
        assertEquals(listOf(true, false, true), values)
        next.cancelAndJoin()
        assertEquals(0, source.subscriptionCount.value)
    }

    @Test fun actualControllerGesturesAndRestoresDoNotCreateASecondFollowOwner() = runBlocking {
        val controller = TerminalViewportController()
        val values = mutableListOf<Boolean>()
        val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            controller.state.terminalFollowChanges().collect { values += it }
        }
        repeat(100) {
            val gesture = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 0)
            controller.endUserScroll(gesture)
        }
        assertEquals(listOf(true), values)
        repeat(100) {
            controller.restoreItemAnchor(ViewportAnchor(it.toLong(), 5, screenGeneration = null, historyGeneration = null), 20)
        }
        assertEquals(listOf(true, false), values)
        controller.setFollow(true, 0)
        assertEquals(listOf(true, false, true), values)
        assertEquals(true, controller.state.value.autoScroll)
        observer.cancelAndJoin()
    }
}
