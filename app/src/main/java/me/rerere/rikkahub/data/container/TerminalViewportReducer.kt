package me.rerere.rikkahub.data.container

import me.rerere.rikkahub.utils.TerminalEmulator

/**
 * Semantic viewport modes for the terminal reducer.
 * Replaces the boolean autoScroll + fragile pixel-based compensation with an
 * explicit mode that distinguishes tail-follow, screen-follow, and line-locked.
 */
enum class ViewportMode {
    /** Follow the latest output (bottom of the grid). History append/trim keeps
     *  the scroll at the bottom — no drift. */
    TAIL,

    /** Keep the physical screen (the last N rows) visible at the viewport top.
     *  New output pushes old rows into history; the screen-top stays at the
     *  first visible screen row. */
    SCREEN,

    /** Locked to a specific stable line ID. The scroll offset is recomputed
     *  so that the anchored line remains at a fixed pixel offset from the
     *  viewport top, even across history trim, resize, or font-size change. */
    LOCKED
}

/**
 * Pure reducer input: the previous viewport state and the new terminal frame.
 * Produces a new viewport state and a target scroll offset (in pixels) that
 * the UI should apply.
 */
data class ViewportInput(
    val mode: ViewportMode,
    /** For LOCKED mode: the stable line ID to anchor on. */
    val anchorLineId: Long? = null,
    /** For LOCKED mode: how many pixels from the viewport top the anchored
     *  line should be positioned at. */
    val anchorIntraOffsetPx: Int = 0,
    /** Scroll offset reported by the scrollable container (px). */
    val currentScrollPx: Int = 0,
    /** Maximum scroll value (px). */
    val maxScrollPx: Int = 0,
    /** Viewport height (px) of the grid-usable area. */
    val viewportHeightPx: Int = 0,
    /** Cell height (px) for rounding. */
    val cellHeightPx: Int = 20,
)

data class ViewportOutput(
    val mode: ViewportMode,
    val anchorLineId: Long? = null,
    val anchorIntraOffsetPx: Int = 0,
    /** The scroll offset the UI should apply. */
    val targetScrollPx: Int,
    /** Whether the anchor line was trimmed and mode needs fallback. */
    val anchorTrimmed: Boolean = false,
)

/**
 * Reduces the previous viewport state against a new terminal frame,
 * returning the target scroll offset and updated mode.
 *
 * @param input  previous viewport state and current scroll metrics.
 * @param frame  the new terminal frame (with line-ID metadata).
 * @param renderedRows  the rendered row count in the UI (history + screen).
 */
fun reduceViewport(
    input: ViewportInput,
    frame: TerminalEmulator.RenderFrame,
    renderedRows: Int,
): ViewportOutput {
    val renderedLineIds = buildLineIdsFromFrame(frame, renderedRows)
    val maxScroll = input.maxScrollPx
    val viewportHeight = input.viewportHeightPx

    return when (input.mode) {
        ViewportMode.TAIL -> {
            // TAIL: always scroll to the bottom of the content.
            val target = maxScroll
            ViewportOutput(
                mode = ViewportMode.TAIL,
                targetScrollPx = target,
            )
        }

        ViewportMode.SCREEN -> {
            // SCREEN: keep the screen start row at the top of the viewport.
            val screenStartPx = frame.screenStartRow * input.cellHeightPx
            val target = screenStartPx.coerceIn(0, maxScroll)
            ViewportOutput(
                mode = ViewportMode.SCREEN,
                targetScrollPx = target,
            )
        }

        ViewportMode.LOCKED -> {
            val anchorId = input.anchorLineId ?: run {
                // No anchor → fall back to TAIL
                return ViewportOutput(
                    mode = ViewportMode.TAIL,
                    targetScrollPx = maxScroll,
                    anchorTrimmed = true,
                )
            }
            // Find the rendered row index for anchorId
            val anchorRowIndex = renderedLineIds.indexOf(anchorId)
            if (anchorRowIndex < 0) {
                // Anchor line was trimmed or not yet in the document.
                // Fall back to TAIL.
                ViewportOutput(
                    mode = ViewportMode.TAIL,
                    targetScrollPx = maxScroll,
                    anchorTrimmed = true,
                )
            } else {
                // Position the anchor row at anchorIntraOffsetPx from the viewport top.
                val target = (anchorRowIndex * input.cellHeightPx - input.anchorIntraOffsetPx)
                    .coerceIn(0, maxScroll)
                ViewportOutput(
                    mode = ViewportMode.LOCKED,
                    anchorLineId = anchorId,
                    anchorIntraOffsetPx = input.anchorIntraOffsetPx,
                    targetScrollPx = target,
                )
            }
        }
    }
}

/**
 * Builds a list of stable line IDs for each rendered row (history + screen).
 * The returned list size may be less than [renderedRows] if the frame's
 * metadata is incomplete (e.g. history without IDs — treated as a fallback).
 */
internal fun buildLineIdsFromFrame(
    frame: TerminalEmulator.RenderFrame,
    renderedRows: Int,
): List<Long> {
    val ids = ArrayList<Long>(renderedRows)
    // History rows
    val historyCount = frame.historyCount
    val historyStartId = frame.historyStartId
    if (historyCount > 0 && historyStartId > 0) {
        for (i in 0 until historyCount.coerceAtMost(renderedRows)) {
            ids.add(historyStartId + i)
        }
    }
    // Screen rows
    val screenIds = frame.screenLineIds
    for (i in 0 until screenIds.size.coerceAtMost(renderedRows - ids.size)) {
        ids.add(screenIds[i])
    }
    return ids
}
