package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
                // cancel an animation started synchronously here. Yield at least one frame for
                // that handoff, then give the child a bounded number of frames to release its
                // mutation. Waiting on an unbounded snapshotFlow can leave the sole writer stuck
                // forever when an Android emulator loses the corresponding idle notification.
                // Do not wait for several virtual frames here: Compose tests and lifecycle
                // transitions may intentionally stop the frame clock while a pointer mutation is
                // being cancelled. One yielded frame is the complete handoff window; the sole
                // writer then proceeds even if the child still reports busy.
                withFrameNanos { }
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
