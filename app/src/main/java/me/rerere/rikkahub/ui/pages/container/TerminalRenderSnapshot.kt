package me.rerere.rikkahub.ui.pages.container

import androidx.compose.runtime.snapshots.SnapshotStateList
import me.rerere.rikkahub.utils.TerminalEmulator

/**
 * The frame and immutable row-list structure read in ONE caller snapshot. Capture at the scope
 * that creates the viewport binding, before passing content lambdas to child/subcompositions.
 * Never re-read a live frame/list in a child while retaining its parent's older layout pass.
 *
 * toList() is O(1); row objects keep their existing per-row observable text and stable line IDs.
 * This does not freeze/copy Text, retain a second history, change publication or own a coroutine.
 */
internal class TerminalRenderSnapshot private constructor(
    val frame: TerminalEmulator.RenderFrame,
    val rows: List<TerminalRenderedRowState>,
) {
    companion object {
        fun capture(frame: TerminalEmulator.RenderFrame, rows: SnapshotStateList<TerminalRenderedRowState>): TerminalRenderSnapshot {
            val view = rows.toList()
            require(view.size == frame.rows.size) {
                "Terminal render capture mismatch: frameRevision=${frame.revision} frameRows=${frame.rows.size} rows=${view.size}"
            }
            return TerminalRenderSnapshot(frame, view)
        }
    }
}

/** Keep the exact original invariant; diagnostics contain only counts/revisions, never text. */
internal fun requireTerminalRenderPair(
    pass: TerminalLazyLayoutPass,
    frame: TerminalEmulator.RenderFrame,
    rows: List<TerminalRenderedRowState>,
) {
    require(pass.frame === frame && rows.size == frame.rows.size) {
        "Terminal render pair mismatch: sameFrame=${pass.frame === frame} " +
            "passRevision=${pass.frame.revision} frameRevision=${frame.revision} " +
            "passRows=${pass.frame.rows.size} frameRows=${frame.rows.size} rows=${rows.size}"
    }
}
