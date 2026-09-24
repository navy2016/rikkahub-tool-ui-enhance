package me.rerere.rikkahub.ui.pages.container

import android.os.SystemClock
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.container.TERMINAL_EDGE_THRESHOLD_PX
import me.rerere.rikkahub.data.container.TerminalFastFlingTracker
import me.rerere.rikkahub.data.container.TerminalJumpEdge
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.ViewportScrollOrigin

internal data class TerminalViewportGestureConfig(
    val panEnabled: Boolean,
    val selectionMode: Boolean,
    val fastFlingRequiredCount: Int,
) {
    val fastFlingEnabled: Boolean get() = panEnabled && !selectionMode
}

internal class TerminalViewportGestures(
    val connection: NestedScrollConnection,
    val flingBehavior: FlingBehavior,
    val resetFastFling: () -> Unit,
)

/**
 * The same gesture ownership and fast-fling jump path for either scroll container. This never calls
 * a scrolling API: jumps go through the controller and its single effect executor. Pixel reads must
 * be supplied by the active backend, not a ScrollState captured before a renderer/geometry change.
 */
@Composable
internal fun rememberTerminalViewportGestures(
    sessionKey: Any,
    controller: TerminalViewportController,
    config: TerminalViewportGestureConfig,
    interactionSource: InteractionSource,
    currentScrollPx: () -> Int,
    isScrollInProgress: () -> Boolean,
    // Elapsed realtime is monotonic and includes sleep; a screen-off interval must expire a streak.
    nowMs: () -> Long = { SystemClock.elapsedRealtime() },
): TerminalViewportGestures {
    val latestScrollPx by rememberUpdatedState(currentScrollPx)
    val latestBusy by rememberUpdatedState(isScrollInProgress)
    val latestConfig by rememberUpdatedState(config)
    val latestClock by rememberUpdatedState(nowMs)
    val tracker = remember(sessionKey, controller) { TerminalFastFlingTracker() }
    val connection = remember(sessionKey, controller) {
        TerminalViewportNestedScrollConnection(
            controller, tracker, { latestConfig }, { latestScrollPx() }, { latestClock() },
        )
    }
    val defaultFlingBehavior = ScrollableDefaults.flingBehavior()
    val flingBehavior = remember(sessionKey, controller, defaultFlingBehavior) {
        TerminalViewportFlingBehavior(controller, { latestScrollPx() }, defaultFlingBehavior)
    }

    val isDragged by interactionSource.collectIsDraggedAsState()
    LaunchedEffect(sessionKey, controller, interactionSource) {
        // A cancelled drag or wheel input need not fling. End it only after both inputs are idle.
        combine(
            snapshotFlow { isDragged || latestBusy() },
            controller.state.map { it.gesture }.distinctUntilChanged(),
        ) { busy, gesture -> busy to gesture }.collectLatest { (busy, gesture) ->
            if (!busy && gesture?.origin == ViewportScrollOrigin.USER_DRAG) {
                withFrameNanos { }
                if (!isDragged && !latestBusy()) controller.endUserScroll(gesture.id)
            }
        }
    }
    return remember(connection, flingBehavior, tracker) {
        TerminalViewportGestures(connection, flingBehavior, resetFastFling = { tracker.reset() })
    }
}

internal class TerminalViewportNestedScrollConnection(
    private val controller: TerminalViewportController,
    private val tracker: TerminalFastFlingTracker,
    private val config: () -> TerminalViewportGestureConfig,
    private val currentScrollPx: () -> Int,
    private val nowMs: () -> Long,
) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) {
            controller.beginUserScroll(ViewportScrollOrigin.USER_DRAG, currentScrollPx())
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // Capture after consumption, including the final delta. Neither a layout clamp nor a jump
        // animation can pass this gate without an active user gesture.
        val gesture = controller.state.value.gesture
        val userDelta = source == NestedScrollSource.UserInput ||
            (source == NestedScrollSource.SideEffect && gesture?.origin == ViewportScrollOrigin.USER_FLING)
        if (consumed.y != 0f && userDelta && gesture != null) {
            controller.userScrolled(gesture.id, currentScrollPx())
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val config = config()
        val edge = tracker.onFling(
            velocityX = available.x,
            velocityY = available.y,
            nowMs = nowMs(),
            enabled = config.fastFlingEnabled,
            requiredCount = config.fastFlingRequiredCount,
        ) ?: return Velocity.Zero
        val currentPx = currentScrollPx()
        when (edge) {
            TerminalJumpEdge.BOTTOM -> {
                if (controller.isNearBottom(currentPx)) return Velocity.Zero
                controller.jumpToBottom(currentPx)
            }
            TerminalJumpEdge.TOP -> {
                if (currentPx <= TERMINAL_EDGE_THRESHOLD_PX) return Velocity.Zero
                controller.jumpToTop(currentPx)
            }
        }
        // Consume the triggering velocity so the child's normal fling cannot cancel the jump.
        return available
    }
}

internal class TerminalViewportFlingBehavior(
    private val controller: TerminalViewportController,
    private val currentScrollPx: () -> Int,
    private val delegate: FlingBehavior,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        // A fast-fling jump consumed all velocity in onPreFling; don't cancel that jump.
        if (initialVelocity == 0f) return 0f
        val token = controller.beginUserScroll(ViewportScrollOrigin.USER_FLING, currentScrollPx())
        try {
            return with(delegate) { this@performFling.performFling(initialVelocity) }
        } finally {
            controller.endUserScroll(token)
        }
    }
}
