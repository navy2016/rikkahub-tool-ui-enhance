package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.container.TerminalViewportController
import me.rerere.rikkahub.data.container.TerminalViewportScrollEffect

/**
 * One collector owns all programmatic vertical scrolling, regardless of the container. The actual
 * scrolling callback stays in that collector; this must never launch another writer/sibling job.
 * [isScrollInProgress] must read the active scrollable's observable state.
 */
internal suspend fun runTerminalViewportScrollEffects(
    controller: TerminalViewportController,
    currentScrollPx: () -> Int,
    maxScrollPx: () -> Int,
    isScrollInProgress: () -> Boolean,
    scrollTo: suspend (effect: TerminalViewportScrollEffect, targetPx: Int) -> Unit,
) {
    controller.state.map { it.scrollEffect }.distinctUntilChanged().collectLatest { effect ->
        if (effect == null) return@collectLatest
        var completed = false
        try {
            if (effect.animated) {
                // onPreFling publishes the jump BEFORE Scrollable runs its child fling. Even with
                // zero remaining velocity, that child briefly enters a new scroll mutation and can
                // cancel an animation started synchronously here. Yield a frame for that handoff,
                // then wait for the child to release its mutation. Checking idle BEFORE yielding
                // is insufficient: the zero-velocity mutation may not have started yet.
                withFrameNanos { }
                snapshotFlow { isScrollInProgress() }.first { !it }
            }
            // A new drag/jump can invalidate the effect during the handoff. Never replay stale intent.
            if (!controller.isCurrent(effect)) return@collectLatest
            val target = effect.targetScrollPx.coerceIn(0, maxScrollPx())
            scrollTo(effect, target)
            completed = true
        } finally {
            controller.scrollFinished(effect.id, currentScrollPx(), completed)
        }
    }
}
