// Frozen control: production TerminalLazyItemExecutor.kt at 1487040d79944661e7083d6f25f4908e4c5ff7f1.
// Only package/function name/imports differ. Test-only; never included in the production application.
package me.rerere.rikkahub.viewporttest

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import me.rerere.rikkahub.data.container.TerminalItemScrollTarget
import me.rerere.rikkahub.data.container.TerminalItemViewport
import me.rerere.rikkahub.data.container.TerminalViewportLineLookup
import me.rerere.rikkahub.data.container.TerminalViewportScrollEffect
import me.rerere.rikkahub.data.container.resolveTerminalItemAnchor
import me.rerere.rikkahub.data.container.terminalScaledItemClip

internal suspend fun executeLegacyTerminalLazyItemScroll(
    effect: TerminalViewportScrollEffect,
    state: LazyListState,
    current: () -> TerminalItemViewport?,
    isCurrent: () -> Boolean,
): Boolean {
    val requested = requireNotNull(effect.itemTarget)
    val lookup = TerminalViewportLineLookup()
    var animated = effect.animated
    repeat(16) {
        if (!isCurrent()) return false
        val observation = current()
        if (observation == null || !observation.ready) {
            withFrameNanos { }
            return@repeat
        }
        val target = when (requested) {
            is TerminalItemScrollTarget.Anchor -> {
                val resolved = resolveTerminalItemAnchor(observation.frame, requested.anchor, lookup) ?: return true
                requested.copy(anchor = resolved)
            }
            else -> requested
        }
        if (observation.isSatisfied(target)) return true
        val rowIndex = when (target) {
            TerminalItemScrollTarget.Top -> 0
            TerminalItemScrollTarget.Follow -> observation.followRowIndex ?: 0
            is TerminalItemScrollTarget.Anchor -> lookup.find(
                observation.frame, observation.frame.rows.size, target.anchor.lineId,
            ).takeIf { it >= 0 } ?: return true
        }
        val row = observation.rows.firstOrNull { it.index == rowIndex }
        if (row == null || target == TerminalItemScrollTarget.Top ||
            (target == TerminalItemScrollTarget.Follow && observation.followRowIndex == null)
        ) {
            val itemIndex = minOf(rowIndex, observation.frame.historyCount)
            if (animated) state.animateScrollToItem(itemIndex) else state.scrollToItem(itemIndex)
        } else {
            val delta = when (target) {
                TerminalItemScrollTarget.Top -> row.topPx
                TerminalItemScrollTarget.Follow -> row.bottomPx + observation.tailPaddingPx - observation.viewportHeightPx
                is TerminalItemScrollTarget.Anchor -> row.topPx + terminalScaledItemClip(
                    target.anchor.clippedTopPx, target.capturedRowHeightPx, row.heightPx,
                )
            }
            if (animated) state.animateScrollBy(delta.toFloat()) else state.scrollBy(delta.toFloat())
        }
        animated = false // The final measured correction must not start another long animation.
        withFrameNanos { }
    }
    // A stalled/mismatched layout is not success. The next real layout can request reconciliation.
    return false
}
