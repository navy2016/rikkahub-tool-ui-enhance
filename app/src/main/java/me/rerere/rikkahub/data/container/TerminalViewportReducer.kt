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

    /** Follow the semantic physical-screen target supplied by the terminal UI.
     *  New output can push rows into history without moving the screen lane. */
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
    /** Generation of the physical screen that owned this anchor when it was captured. */
    val anchorScreenGeneration: Long? = null,
    /** Scroll offset reported by the scrollable container (px). */
    val currentScrollPx: Int = 0,
    /** Maximum scroll value (px). */
    val maxScrollPx: Int = 0,
    /** Viewport height (px) of the grid-usable area. */
    val viewportHeightPx: Int = 0,
    /** Cell height (px) for rounding. */
    val cellHeightPx: Int = 20,
    /** Semantic target for normal tail-following. */
    val tailScrollPx: Int = maxScrollPx,
    /** Semantic target for physical-screen following. Defaults to screen top. */
    val screenScrollPx: Int? = null,
    /** Mode selected when a locked anchor no longer exists. */
    val fallbackMode: ViewportMode = ViewportMode.TAIL,
)

data class ViewportOutput(
    val mode: ViewportMode,
    val anchorLineId: Long? = null,
    val anchorIntraOffsetPx: Int = 0,
    val anchorScreenGeneration: Long? = null,
    /** The scroll offset the UI should apply. */
    val targetScrollPx: Int,
    /** Whether the anchor line was trimmed and mode needs fallback. */
    val anchorTrimmed: Boolean = false,
)

/** A stable row anchor captured from the current scroll position. */
data class ViewportAnchor(
    val lineId: Long,
    val intraOffsetPx: Int,
    /** Non-null only when [lineId] belongs to the active physical screen. */
    val screenGeneration: Long?,
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

    fun fallback(trimmed: Boolean = true): ViewportOutput {
        val mode = input.fallbackMode
        val target = when (mode) {
            ViewportMode.TAIL -> input.tailScrollPx
            ViewportMode.SCREEN -> input.screenScrollPx ?: frame.screenStartRow * input.cellHeightPx
            ViewportMode.LOCKED -> input.tailScrollPx
        }.coerceIn(0, maxScroll)
        return ViewportOutput(
            mode = if (mode == ViewportMode.LOCKED) ViewportMode.TAIL else mode,
            targetScrollPx = target,
            anchorTrimmed = trimmed,
        )
    }

    return when (input.mode) {
        ViewportMode.TAIL -> {
            // TAIL: always scroll to the bottom of the content.
            val target = input.tailScrollPx.coerceIn(0, maxScroll)
            ViewportOutput(
                mode = ViewportMode.TAIL,
                targetScrollPx = target,
            )
        }

        ViewportMode.SCREEN -> {
            // SCREEN: follow the physical-screen semantic target.
            val screenStartPx = input.screenScrollPx ?: frame.screenStartRow * input.cellHeightPx
            val target = screenStartPx.coerceIn(0, maxScroll)
            ViewportOutput(
                mode = ViewportMode.SCREEN,
                targetScrollPx = target,
            )
        }

        ViewportMode.LOCKED -> {
            val anchorId = input.anchorLineId ?: run {
                return fallback()
            }
            // Find the rendered row index for anchorId
            val anchorRowIndex = renderedLineIds.indexOf(anchorId)
            val anchorIsScreenRow = anchorRowIndex >= frame.historyCount
            if (anchorRowIndex < 0) {
                fallback()
            } else if (anchorIsScreenRow && input.anchorScreenGeneration != null &&
                input.anchorScreenGeneration != frame.screenGeneration
            ) {
                // A full clear or alternate-screen reset replaced this physical screen.
                fallback()
            } else {
                // Position the anchor row at anchorIntraOffsetPx from the viewport top.
                val target = (anchorRowIndex * input.cellHeightPx - input.anchorIntraOffsetPx)
                    .coerceIn(0, maxScroll)
                ViewportOutput(
                    mode = ViewportMode.LOCKED,
                    anchorLineId = anchorId,
                    anchorIntraOffsetPx = input.anchorIntraOffsetPx,
                    anchorScreenGeneration = if (anchorIsScreenRow) input.anchorScreenGeneration else null,
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
    val historyIds = frame.historyLineIds
    if (historyIds.isNotEmpty()) {
        for (i in 0 until historyIds.size.coerceAtMost(renderedRows)) {
            ids.add(historyIds[i])
        }
    } else {
        val historyStartId = frame.historyStartId
        if (historyCount > 0 && historyStartId > 0) {
            for (i in 0 until historyCount.coerceAtMost(renderedRows)) {
                ids.add(historyStartId + i)
            }
        }
    }
    // Screen rows
    val screenIds = frame.screenLineIds
    for (i in 0 until screenIds.size.coerceAtMost(renderedRows - ids.size)) {
        ids.add(screenIds[i])
    }
    return ids
}

/** Captures the stable row visible at the top edge of a manually positioned viewport. */
internal fun captureViewportAnchor(
    frame: TerminalEmulator.RenderFrame,
    renderedRows: Int,
    scrollPx: Int,
    cellHeightPx: Int,
): ViewportAnchor? {
    if (cellHeightPx <= 0) return null
    val lineIds = buildLineIdsFromFrame(frame, renderedRows)
    if (lineIds.isEmpty()) return null
    val row = (scrollPx.coerceAtLeast(0) / cellHeightPx).coerceIn(0, lineIds.lastIndex)
    val lineId = lineIds.getOrNull(row) ?: return null
    val intraOffset = scrollPx.coerceAtLeast(0) % cellHeightPx
    return ViewportAnchor(
        lineId = lineId,
        intraOffsetPx = intraOffset,
        screenGeneration = if (row >= frame.historyCount) frame.screenGeneration else null,
    )
}
