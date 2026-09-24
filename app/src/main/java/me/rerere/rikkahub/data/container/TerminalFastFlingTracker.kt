package me.rerere.rikkahub.data.container

import kotlin.math.abs

internal const val TERMINAL_FAST_FLING_VELOCITY_PX = 3500f
internal const val TERMINAL_FAST_FLING_WINDOW_MS = 700L
internal const val TERMINAL_EDGE_THRESHOLD_PX = 80

internal enum class TerminalJumpEdge { TOP, BOTTOM }

/**
 * Session-local recognizer, independent of ScrollState/LazyListState. It emits intent, never scrolls.
 * Non-qualifying flings don't count; a direction change or expired window starts a new sequence.
 * The caller uses a monotonic clock and checks whether the requested edge has already been reached.
 */
internal class TerminalFastFlingTracker {
    private var lastDirection: TerminalJumpEdge? = null
    private var lastAtMs = 0L
    private var count = 0

    fun onFling(
        velocityX: Float,
        velocityY: Float,
        nowMs: Long,
        enabled: Boolean,
        requiredCount: Int,
    ): TerminalJumpEdge? {
        require(requiredCount > 0)
        if (!enabled || !velocityX.isFinite() || !velocityY.isFinite()) return null
        if (abs(velocityY) < TERMINAL_FAST_FLING_VELOCITY_PX) return null
        if (abs(velocityX) > abs(velocityY) * 0.8f) return null
        val direction = if (velocityY < 0f) TerminalJumpEdge.BOTTOM else TerminalJumpEdge.TOP
        val elapsed = nowMs - lastAtMs
        count = if (direction == lastDirection && elapsed in 0L..TERMINAL_FAST_FLING_WINDOW_MS) count + 1 else 1
        lastDirection = direction
        lastAtMs = nowMs
        if (count < requiredCount) return null
        count = 0
        return direction
    }
}
