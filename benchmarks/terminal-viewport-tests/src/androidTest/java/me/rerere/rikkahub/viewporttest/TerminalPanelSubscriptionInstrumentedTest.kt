package me.rerere.rikkahub.viewporttest

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportState
import me.rerere.rikkahub.data.container.ViewportAnchor
import me.rerere.rikkahub.data.container.ViewportScrollOrigin
import me.rerere.rikkahub.ui.pages.container.rememberTerminalFollowEnabled
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Old/new subscriptions share ONE controller and device. Counts exclude initial mount work. */
class TerminalPanelSubscriptionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TerminalViewportTestActivity>()
    @get:Rule val timeout = Timeout.seconds(60)

    private class Counts {
        val values = mutableListOf<Boolean>()
        val compositions: Int get() = values.size
    }

    @Composable private fun Legacy(controller: TerminalViewportController, counts: Counts) {
        val state by controller.state.collectAsStateWithLifecycle()
        val enabled = state.autoScroll
        SideEffect { counts.values += enabled }
        Text("legacy=$enabled")
    }

    @Composable private fun Projected(controller: TerminalViewportController, counts: Counts) {
        val enabled by rememberTerminalFollowEnabled(controller)
        SideEffect { counts.values += enabled }
        Text("projected=$enabled")
    }

    @Test fun panelSubscriptionPairsSuppressAnchorAndGestureRecompositionsButKeepBooleanTransitions() {
        val controller = TerminalViewportController(TerminalViewportState(autoScroll = false,
            anchorLineId = 999, anchorClippedTopPx = 3))
        val legacy = Counts()
        val projected = Counts()
        compose.setContent { Column { Legacy(controller, legacy); Projected(controller, projected) } }
        compose.waitForIdle()
        val beforeOld = legacy.compositions
        val beforeNew = projected.compositions
        repeat(30) { index ->
            compose.runOnIdle { controller.restoreItemAnchor(ViewportAnchor(100L + index, index % 20), 20) }
            compose.waitForIdle() // Every update gets a completed composition, not a conflated burst.
        }
        repeat(12) {
            var token = 0L
            compose.runOnIdle { token = controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, 0) }
            compose.waitForIdle()
            compose.runOnIdle { controller.endUserScroll(token) }
            compose.waitForIdle()
        }
        val oldWork = legacy.compositions - beforeOld
        val newWork = projected.compositions - beforeNew
        assertEquals(54, oldWork)
        assertEquals(0, newWork)
        assertFalse(projected.values.last())
        for (enabled in listOf(true, false, true)) {
            compose.runOnIdle { controller.setFollow(enabled, 0) }
            compose.waitForIdle()
            assertEquals(enabled, projected.values.last())
            assertEquals(enabled, legacy.values.last())
        }
        assertEquals(beforeNew + 3, projected.compositions)
        Log.i("TerminalPanelSubscription", "FOLLOW_WORK " + JSONObject().put("updates", 54)
            .put("legacyCompositions", oldWork).put("projectedCompositions", newWork)
            .put("booleanTransitions", 3).toString())
    }

    @Test fun panelSubscriptionControllerReplacementStartsWithItsOwnStateAndDetachesOldOne() {
        val old = TerminalViewportController()
        val replacement = TerminalViewportController(TerminalViewportState(autoScroll = false))
        var controller by mutableStateOf(old, referentialEqualityPolicy())
        val counts = Counts()
        compose.setContent { Projected(controller, counts) }
        compose.waitForIdle()
        assertTrue(counts.values.last())
        val before = counts.compositions
        compose.runOnIdle { controller = replacement }
        compose.waitForIdle()
        assertTrue(counts.values.drop(before).isNotEmpty())
        assertTrue("old controller value leaked into first replacement composition", counts.values.drop(before).all { !it })
        val replaced = counts.compositions
        compose.runOnIdle { old.restoreItemAnchor(ViewportAnchor(300, 5), 20) }
        compose.waitForIdle()
        assertEquals(replaced, counts.compositions)
        compose.runOnIdle { replacement.setFollow(true, 0) }
        compose.waitForIdle()
        assertTrue(counts.values.last())
        assertEquals(replaced + 1, counts.compositions)
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    @Test fun panelSubscriptionLifecycleRestartReadsLatestBooleanWithoutCollectingWhileStopped() {
        val controller = TerminalViewportController()
        val counts = Counts()
        val owner = Owner()
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner) { Projected(controller, counts) } }
        compose.waitForIdle()
        assertTrue(counts.values.last())
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        val stopped = counts.compositions
        compose.runOnIdle { controller.restoreItemAnchor(ViewportAnchor(100, 3), 20) }
        compose.waitForIdle()
        assertEquals(stopped, counts.compositions)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertFalse(counts.values.last())
        assertEquals(stopped + 1, counts.compositions)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        val nextStop = counts.compositions
        compose.runOnIdle {
            controller.setFollow(true, 0)
            controller.restoreItemAnchor(ViewportAnchor(101, 7), 20)
            controller.setFollow(true, 0)
        }
        compose.waitForIdle()
        assertEquals(nextStop, counts.compositions)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals(nextStop + 1, counts.compositions)
        assertTrue(counts.values.last())
    }
}
