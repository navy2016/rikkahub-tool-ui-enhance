package me.rerere.rikkahub.ui.pages.container

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import me.rerere.rikkahub.data.container.buildLineIdsFromFrame
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.TerminalFrameRows
import me.rerere.rikkahub.utils.ownedRows

internal const val TERMINAL_GRID_CLEAR_GRACE_MS = 50L
internal const val TERMINAL_GRID_STABLE_BOTTOM_ROWS = 8

@Stable
internal class TerminalRenderedRowState(
    val lineId: Long,
    initialText: AnnotatedString,
) {
    var text by mutableStateOf(initialText)
    var pendingBlankSinceMs: Long = 0L
}

/**
 * Session-local proof tied to the exact immutable row-list view last synchronized. This state is
 * never read by composition, but participates in the caller's snapshot so an aborted publication
 * cannot commit newer cache metadata while leaving older row Text behind.
 * Only emulator-owned snapshots may skip retained history. Synthetic metadata, externally edited
 * structure, pending blanks, global style/geometry changes and ambiguous transitions fall back.
 */
internal class TerminalRenderedRowsSyncState(
    frame: TerminalEmulator.RenderFrame,
    initialRows: List<TerminalRenderedRowState>,
) {
    var lastUsedMetadataFastPath: Boolean = false
        private set
    var lastUsedFifoFastPath: Boolean = false
        private set
    var lastVisitedTextRows: Int = frame.rows.size
        private set
    var lastSkippedHistoryRows: Int = 0
        private set

    private data class Binding(
        val owned: TerminalFrameRows?,
        val rowView: List<TerminalRenderedRowState>,
        val pendingBlanks: Boolean,
    )

    private var binding by mutableStateOf(
        Binding(frame.ownedRows(), initialRows, false),
        androidx.compose.runtime.referentialEqualityPolicy(),
    )

    init {
        // One creation-time check, not a per-frame scan. Refuse a mismatched initial binding.
        val owned = binding.owned
        var valid = owned != null && initialRows.size == frame.rows.size
        var pending = false
        if (valid && owned != null) {
            for (index in initialRows.indices) {
                val expectedId = if (index < frame.historyCount) frame.historyLineIds[index]
                    else frame.screenLineIds[index - frame.historyCount]
                val row = initialRows[index]
                if (row.lineId != expectedId || row.text != frame.rows[index].text) valid = false
                if (row.pendingBlankSinceMs != 0L) pending = true
            }
        }
        binding = Binding(owned.takeIf { valid }, initialRows, pending)
    }

    internal fun hasSameStructure(frame: TerminalEmulator.RenderFrame, previousRows: List<TerminalRenderedRowState>): Boolean {
        val binding = binding
        val before = binding.owned ?: return false
        val after = frame.ownedRows() ?: return false
        return previousRows === binding.rowView && before.history.owner === after.history.owner &&
            before.history.generation == after.history.generation &&
            before.history.firstSequence == after.history.firstSequence &&
            before.history.rows.size == after.history.rows.size &&
            before.includesHistory == after.includesHistory && before.columns == after.columns &&
            before.screenGeneration == after.screenGeneration && before.screenLineIds == after.screenLineIds
    }

    internal fun canSkipHistory(frame: TerminalEmulator.RenderFrame): Boolean = binding.let {
        !it.pendingBlanks && it.owned?.history?.renderRevision == frame.historyRenderRevision
    }

    internal fun fifoPlan(
        frame: TerminalEmulator.RenderFrame,
        previousRows: List<TerminalRenderedRowState>,
        usesTuiViewport: Boolean,
    ): TerminalFifoRowPlan? {
        val binding = binding
        val before = binding.owned ?: return null
        val after = frame.ownedRows() ?: return null
        if (usesTuiViewport || binding.pendingBlanks || previousRows !== binding.rowView ||
            !before.includesHistory || !after.includesHistory ||
            before.history.owner !== after.history.owner || before.history.generation != after.history.generation ||
            before.history.renderRevision != after.history.renderRevision || before.columns != after.columns ||
            before.screenGeneration != after.screenGeneration || before.screenLineIds.size != after.screenLineIds.size ||
            before.nextLineId > after.nextLineId
        ) return null
        val old = before.history
        val next = after.history
        // FIFO can remove a prefix and append a suffix, never replace/reorder the retained range.
        if (old.firstSequence < 0 || next.firstSequence < old.firstSequence ||
            next.firstSequence > old.endSequence || next.endSequence < old.endSequence ||
            old.endSequence < old.firstSequence || next.endSequence < next.firstSequence ||
            (next.firstSequence == old.firstSequence && next.endSequence == old.endSequence)
        ) return null
        val dropped = (next.firstSequence - old.firstSequence).toInt()
        val retained = (old.endSequence - next.firstSequence).toInt()
        val previousScreen = previousRows.subList(old.rows.size, previousRows.size)
            .withIndex().associateBy { it.value.lineId }
        val tail = ArrayList<TerminalRenderedRowState>(after.size - retained)
        var lastScreenIndex = -1
        var lastNewId = before.nextLineId - 1
        var foundNewRow = false
        for (index in retained until after.size) {
            val id = if (index < next.rows.size) next.lineIds[index] else after.screenLineIds[index - next.rows.size]
            val existing = previousScreen[id]
            // Unknown old IDs are NOT new rows. Keep the general stable-ID reconciliation for them.
            if (existing != null) {
                if (foundNewRow || existing.index <= lastScreenIndex) return null
                lastScreenIndex = existing.index
            } else {
                if (id < before.nextLineId || id <= lastNewId) return null
                foundNewRow = true
                lastNewId = id
            }
            tail.add(existing?.value ?: TerminalRenderedRowState(id, after[index].text))
        }
        // The usual append keeps the entire old screen as a prefix of the new tail. Preserve it
        // structurally too, so this path needs only head trim + new suffix insertion, not reinsertion
        // of the whole screen. Text synchronization still visits the newly archived rows.
        var screenPrefix = 0
        while (screenPrefix < before.screenLineIds.size && screenPrefix < tail.size &&
            previousRows[old.rows.size + screenPrefix] === tail[screenPrefix]
        ) screenPrefix++
        return TerminalFifoRowPlan(dropped, retained, screenPrefix, tail)
    }

    internal fun record(
        frame: TerminalEmulator.RenderFrame,
        rows: List<TerminalRenderedRowState>,
        usedFastPath: Boolean,
        usedFifoFastPath: Boolean,
        visitedTextRows: Int,
        skippedHistoryRows: Int,
        hasPendingBlanks: Boolean,
    ) {
        binding = Binding(frame.ownedRows(), rows, hasPendingBlanks)
        lastUsedMetadataFastPath = usedFastPath
        lastUsedFifoFastPath = usedFifoFastPath
        lastVisitedTextRows = visitedTextRows
        lastSkippedHistoryRows = skippedHistoryRows
    }
}

internal data class TerminalFifoRowPlan(
    val droppedHistoryRows: Int,
    val retainedHistoryRows: Int,
    val retainedScreenPrefix: Int,
    val tailRows: List<TerminalRenderedRowState>,
)

internal fun createTerminalRenderedRowsSyncState(
    frame: TerminalEmulator.RenderFrame,
    rows: SnapshotStateList<TerminalRenderedRowState>,
): TerminalRenderedRowsSyncState = TerminalRenderedRowsSyncState(frame, rows.toList())

internal fun createTerminalRenderedRows(
    frame: TerminalEmulator.RenderFrame,
): SnapshotStateList<TerminalRenderedRowState> = mutableStateListOf<TerminalRenderedRowState>().apply {
    val lineIds = buildLineIdsFromFrame(frame, frame.rows.size)
    addAll(frame.rows.mapIndexed { index, row ->
        TerminalRenderedRowState(
            lineId = lineIds.getOrNull(index) ?: Long.MIN_VALUE + index,
            initialText = row.text,
        )
    })
}

/**
 * Shared by the terminal and the isolated rendering benchmark. The caller owns the mutable snapshot
 * so row updates and viewport metadata are still published atomically. Returns whether a transient
 * TUI blank needs a later commit. Stable row states and their pending blank grace survive structural updates.
 */
internal fun synchronizeTerminalRenderedRows(
    terminalRenderedRows: SnapshotStateList<TerminalRenderedRowState>,
    frame: TerminalEmulator.RenderFrame,
    usesTuiViewport: Boolean,
    forcePendingGridBlanks: Boolean = false,
    nowMs: Long,
    syncState: TerminalRenderedRowsSyncState? = null,
): Boolean {
    val rendered = frame.rows
    // SnapshotStateList.toList() is an O(1), immutable view. Read the list's snapshot record once
    // rather than once per row, and retain it while applying any structural changes below.
    val previousRows = terminalRenderedRows.toList()
    val fifo = syncState?.fifoPlan(frame, previousRows, usesTuiViewport)
    val metadataProvesSameStructure = previousRows.size == rendered.size &&
        syncState?.hasSameStructure(frame, previousRows) == true
    val nextLineIds = if (fifo != null || metadataProvesSameStructure) null else buildLineIdsFromFrame(frame, rendered.size).ifEmpty {
        rendered.indices.map { index -> Long.MIN_VALUE + index }
    }
    val idsChanged = fifo != null || nextLineIds != null && (previousRows.size != rendered.size ||
        previousRows.indices.any { previousRows[it].lineId != nextLineIds[it] })
    if (fifo != null) {
        if (fifo.droppedHistoryRows > 0) terminalRenderedRows.subList(0, fifo.droppedHistoryRows).clear()
        val retainedEnd = fifo.retainedHistoryRows + fifo.retainedScreenPrefix
        if (terminalRenderedRows.size > retainedEnd) terminalRenderedRows.subList(retainedEnd, terminalRenderedRows.size).clear()
        if (fifo.tailRows.size > fifo.retainedScreenPrefix) {
            terminalRenderedRows.addAll(fifo.tailRows.subList(fifo.retainedScreenPrefix, fifo.tailRows.size))
        }
        // Persistent-list structural edits can still have history-sized work. Only retained ID/
        // Text validation is skipped; the benchmark must report row-sync and whole-frame costs.
    } else if (idsChanged) {
        synchronizeTerminalRowStructure(terminalRenderedRows, previousRows, rendered, checkNotNull(nextLineIds))
    }
    val currentRows = if (idsChanged) terminalRenderedRows.toList() else previousRows
    var hasDeferredGridBlank = false
    val stableGridStart = (rendered.size - TERMINAL_GRID_STABLE_BOTTOM_ROWS).coerceAtLeast(0)
    val historyCanBeSkipped = metadataProvesSameStructure && syncState?.canSkipHistory(frame) == true
    val textStart = fifo?.retainedHistoryRows ?: if (historyCanBeSkipped) frame.historyCount else 0
    for (index in textStart until rendered.size) {
        val rowState = currentRows[index]
        val next = rendered[index].text
        val previousText = rowState.text
        // Cached history rows usually keep the same AnnotatedString. A pending TUI blank must
        // still run through the grace/force logic, even if this frame reuses an existing text.
        if (previousText === next && rowState.pendingBlankSinceMs == 0L) continue
        val deferTransientGridClear = usesTuiViewport &&
            index >= stableGridStart &&
            previousText.text.isNotBlank() &&
            next.text.isBlank()
        if (deferTransientGridClear && !forcePendingGridBlanks) {
            if (rowState.pendingBlankSinceMs == 0L) rowState.pendingBlankSinceMs = nowMs
            if (nowMs - rowState.pendingBlankSinceMs < TERMINAL_GRID_CLEAR_GRACE_MS) {
                hasDeferredGridBlank = true
                continue
            }
        } else if (!next.text.isBlank()) {
            rowState.pendingBlankSinceMs = 0L
        }
        if (previousText != next) rowState.text = next
        rowState.pendingBlankSinceMs = 0L
    }
    syncState?.record(
        frame = frame,
        rows = currentRows,
        usedFastPath = metadataProvesSameStructure && historyCanBeSkipped,
        usedFifoFastPath = fifo != null,
        visitedTextRows = (rendered.size - textStart).coerceAtLeast(0),
        skippedHistoryRows = textStart,
        hasPendingBlanks = hasDeferredGridBlank,
    )
    return hasDeferredGridBlank
}

/** Only the list structure changes here; the caller updates text within the same mutable snapshot. */
private fun synchronizeTerminalRowStructure(
    rows: SnapshotStateList<TerminalRenderedRowState>,
    previous: List<TerminalRenderedRowState>,
    rendered: List<TerminalEmulator.RenderedRow>,
    nextIds: List<Long>,
) {
    if (nextIds.isEmpty()) {
        rows.clear()
        return
    }
    val retainedStart = previous.indexOfFirst { it.lineId == nextIds.first() }
    if (retainedStart >= 0) {
        val retainedCount = minOf(previous.size - retainedStart, nextIds.size)
        val overlapMatches = (0 until retainedCount).all { previous[retainedStart + it].lineId == nextIds[it] }
        // New emulator line IDs exceed all earlier IDs. Verify that condition rather than assume
        // any suffix is new: a reorder can move an old row from before retainedStart to the tail.
        val maxPreviousId = if (retainedCount < nextIds.size) previous.maxOf { it.lineId } else Long.MAX_VALUE
        val suffixIsNew = (retainedCount until nextIds.size).all { nextIds[it] > maxPreviousId }
        if (overlapMatches && suffixIsNew) {
            // Normal append/trim: only edit the head/tail and retain all surviving row objects.
            // No full ID map, clear/reinsert, or per-row snapshot-list write is needed. ID validation
            // and persistent-list edits still have history-sized work; this is not O(visible rows).
            if (retainedStart > 0) rows.subList(0, retainedStart).clear()
            if (rows.size > retainedCount) rows.subList(retainedCount, rows.size).clear()
            if (nextIds.size > retainedCount) {
                rows.addAll((retainedCount until nextIds.size).map { index ->
                    TerminalRenderedRowState(nextIds[index], rendered[index].text)
                })
            }
            return
        }
    }
    // Reorder/replace/non-monotonic IDs: retain the original stable-ID semantics. Build off-list
    // and publish in bulk, instead of performing up to 10k individual SnapshotStateList.add calls.
    val existing = previous.associateBy { it.lineId }
    val replacement = nextIds.mapIndexed { index, id ->
        existing[id] ?: TerminalRenderedRowState(id, rendered[index].text)
    }
    rows.clear()
    rows.addAll(replacement)
}

/** Eager physical rows; the parent owns scrolling, selection and all viewport coordinates. */
@Composable
internal fun TerminalRenderedRows(
    rows: List<TerminalRenderedRowState>,
    style: TextStyle,
) {
    if (rows.isEmpty()) {
        Text(text = "等待输出...", style = style, softWrap = false, maxLines = 1)
    } else {
        rows.forEach { row ->
            key(row.lineId) {
                TerminalRenderedRow(state = row, style = style)
            }
        }
    }
}

@Composable
private fun TerminalRenderedRow(
    state: TerminalRenderedRowState,
    style: TextStyle,
) {
    Text(text = state.text, style = style, softWrap = false, maxLines = 1)
}

internal const val TERMINAL_HISTORY_CHUNK_ROWS = 128

/** A composition partition, NOT a scroll item, row ID, measured height or viewport coordinate. */
internal data class TerminalHistoryChunk(val bucket: Long, val start: Int, val endExclusive: Int)

/**
 * History is a FIFO sequence even when its stable line IDs have gaps or are out of order. Bucketing
 * the archival ordinal bounds BOTH rows per group and number of groups. Positional chunking would
 * move a row across every boundary on each head trim; line-ID buckets can degenerate to one row
 * each. Neither problem occurs here. Work is O(chunks), independent of the line-ID distribution.
 * Unknown metadata and TUI/physical-grid modes conservatively retain the flat eager path.
 */
internal fun terminalHistoryChunks(
    historyCount: Int,
    firstSequence: Long?,
    usesTuiViewport: Boolean = false,
): List<TerminalHistoryChunk> {
    if (usesTuiViewport || historyCount <= 0 || firstSequence == null || firstSequence < 0L ||
        firstSequence > Long.MAX_VALUE - (historyCount - 1).toLong()
    ) return emptyList()
    return buildList {
        var start = 0
        while (start < historyCount) {
            val sequence = firstSequence + start
            val count = minOf(
                historyCount - start,
                TERMINAL_HISTORY_CHUNK_ROWS - (sequence % TERMINAL_HISTORY_CHUNK_ROWS).toInt(),
            )
            add(TerminalHistoryChunk(sequence / TERMINAL_HISTORY_CHUNK_ROWS, start, start + count))
            start += count
        }
    }
}

/** Non-observable diagnostics used by isolated fixtures, absent in production. Never drive UI state. */
internal interface TerminalTranscriptCompositionObserver {
    fun historyChunkDelta(chunks: Int, rows: Int)
    fun activeScreenDelta(screens: Int)
}

/**
 * Fully eager transcript: same Text, ordering and natural sizes as [TerminalRenderedRows]. The
 * parent still owns ScrollState, SelectionContainer, tail padding and all viewport coordinates.
 * Only ordinary history is partitioned; the complete active screen stays together. Callers pass
 * an immutable row-list view and a plan from the SAME frame/snapshot.
 *
 * Drawing isolation is enabled by the production ordinary-history caller after the same-run
 * comparison and geometry regressions passed. It remains false by default for other callers and
 * for benchmark controls; no estimated/fixed row heights are used.
 */
@Composable
internal fun TerminalRenderedTranscript(
    rows: List<TerminalRenderedRowState>,
    style: TextStyle,
    historyChunks: List<TerminalHistoryChunk>,
    isolateChunkDrawing: Boolean = false,
    observer: TerminalTranscriptCompositionObserver? = null,
) {
    if (historyChunks.isEmpty()) {
        TerminalRenderedRows(rows, style)
        return
    }
    historyChunks.forEach { chunk ->
        key(chunk.bucket) {
            TerminalHistoryChunkRows(
                chunk = TerminalHistoryChunkContent(chunk.bucket, rows.subList(chunk.start, chunk.endExclusive)),
                style = style,
                isolateDrawing = isolateChunkDrawing,
                observer = observer,
            )
        }
    }
    // Distinct source/key scope from history. A screen row archiving may recompose ONCE across
    // this boundary, but surviving history rows keep both their bucket and stable line-ID keys.
    key("terminal-active-screen") {
        if (observer != null) {
            DisposableEffect(observer) {
                observer.activeScreenDelta(1)
                onDispose { observer.activeScreenDelta(-1) }
            }
        }
        Column { TerminalRenderedRows(rows.subList(historyChunks.last().endExclusive, rows.size), style) }
    }
}

/** The list is an immutable view; observable @Stable row states inside it may update their text. */
@Stable
private data class TerminalHistoryChunkContent(val bucket: Long, val rows: List<TerminalRenderedRowState>)

@Composable
private fun TerminalHistoryChunkRows(
    chunk: TerminalHistoryChunkContent,
    style: TextStyle,
    isolateDrawing: Boolean,
    observer: TerminalTranscriptCompositionObserver?,
) {
    if (observer != null) {
        DisposableEffect(chunk.bucket, chunk.rows.size, observer) {
            val count = chunk.rows.size
            observer.historyChunkDelta(1, count)
            onDispose { observer.historyChunkDelta(-1, -count) }
        }
    }
    // Default graphicsLayer has no clipping, alpha or offscreen raster buffer. It only gives
    // unchanged chunks their own display list; the benchmark measures whether this is worthwhile.
    Column(if (isolateDrawing) Modifier.graphicsLayer() else Modifier) {
        TerminalRenderedRows(chunk.rows, style)
    }
}
