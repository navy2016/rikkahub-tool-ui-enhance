package me.rerere.rikkahub.ui.pages.container

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

/**
 * Called ONLY by runTerminalViewportScrollEffects, never from a sibling scroll coroutine.
 * A disjoint jump first measures its target item, then aligns the actual row. Screen rows remain
 * inside one screen item. No global pixel range, estimated prefix, fixed height or O(history)
 * measurement table is needed. Bounded frame yields also make cancellation/layout failure safe.
 */
internal suspend fun executeTerminalLazyItemScroll(
    effect: TerminalViewportScrollEffect,
    state: LazyListState,
    current: () -> TerminalItemViewport?,
    isCurrent: () -> Boolean,
): Boolean {
    effect.selectionDeltaPx?.let { delta ->
        if (!isCurrent()) return false
        val before = current()?.takeIf { it.ready && it.fullVirtual != null } ?: return false
        state.scrollBy(delta.toFloat())
        if (!isCurrent()) return false
        // A selection-edge delta is consumed user intent, not a semantic follow target. Capture
        // a fresh layout after it, or let the controller capture the next one instead of snapping back.
        return current()?.let { it.ready && it.layoutKey == before.layoutKey } == true
    }
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
        val row = if (target == TerminalItemScrollTarget.Follow) observation.measuredRow(rowIndex)
            else observation.rows.firstOrNull { it.index == rowIndex }
        if (row == null || target == TerminalItemScrollTarget.Top ||
            (target == TerminalItemScrollTarget.Follow && observation.followRowIndex == null)
        ) {
            val itemIndex = observation.itemIndex(rowIndex)
            if (animated) state.animateScrollToItem(itemIndex) else state.scrollToItem(itemIndex)
        } else {
            val delta = when (target) {
                TerminalItemScrollTarget.Top -> row.topPx
                TerminalItemScrollTarget.Follow -> observation.followDelta() ?: return false
                is TerminalItemScrollTarget.Anchor -> row.topPx + terminalScaledItemClip(
                    target.anchor.clippedTopPx, target.capturedRowHeightPx, row.heightPx,
                )
            }
            if (animated) state.animateScrollBy(delta.toFloat()) else state.scrollBy(delta.toFloat())
        }
        animated = false // The final measured correction must not start another long animation.
        if (!isCurrent()) return false
        // Read AFTER the mutation. LazyList can update measured offsets synchronously; do not
        // spend another frame holding the sole writer when this exact target is already satisfied.
        // A new frame/font/viewport, missing layout or unsatisfied target still yields as before.
        val completedLayout = current()
        if (!isCurrent()) return false
        if (completedLayout?.confirmsScrollFrom(observation, target) == true) return true
        withFrameNanos { }
    }
    // A stalled/mismatched layout is not success. The next real layout can request reconciliation.
    return false
}
