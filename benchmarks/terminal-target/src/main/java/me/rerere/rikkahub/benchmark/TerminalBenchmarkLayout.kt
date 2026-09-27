package me.rerere.rikkahub.benchmark

import me.rerere.rikkahub.utils.TerminalEmulator

internal enum class BenchmarkRenderer(val wireName: String) {
    EAGER("eager"),
    LAZY_HISTORY("lazyHistory"),
    CHUNKED_EAGER("chunkedEager");

    companion object {
        fun fromWireName(value: String): BenchmarkRenderer = entries.firstOrNull { it.wireName == value }
            ?: error("Unknown benchmark renderer: $value")
    }
}

internal const val EAGER_HISTORY_CHUNK_IDS = 128L

/** Stable composition key plus indices, NOT pixel geometry or a lazy-list item. */
internal data class EagerHistoryChunk(val bucket: Long, val start: Int, val endExclusive: Int)

/** Unsupported metadata falls back to flat eager; never reorder rows just to form groups. */
internal fun stableEagerHistoryChunks(ids: List<Long>): List<EagerHistoryChunk> {
    if (ids.isEmpty()) return emptyList()
    if ((1 until ids.size).any { ids[it - 1] >= ids[it] }) return emptyList()
    return buildList {
        var start = 0
        var bucket = Math.floorDiv(ids[0], EAGER_HISTORY_CHUNK_IDS)
        for (index in 1 until ids.size) {
            val nextBucket = Math.floorDiv(ids[index], EAGER_HISTORY_CHUNK_IDS)
            if (nextBucket != bucket) {
                add(EagerHistoryChunk(bucket, start, index))
                start = index
                bucket = nextBucket
            }
        }
        add(EagerHistoryChunk(bucket, start, ids.size))
    }
}

/** Benchmark-only partition. No production viewport state or pixel-to-row adapter lives here. */
internal data class TerminalBenchmarkLayout(
    val historyRows: Int,
    val screenRows: Int,
    val useLazyHistory: Boolean,
    val historyChunks: List<EagerHistoryChunk> = emptyList(),
) {
    val useChunkedHistory: Boolean get() = historyChunks.isNotEmpty()
    val activeScreenItemIndex: Int get() = historyRows
    val tailItemIndex: Int get() = historyRows + 1
    val lazyItemCount: Int get() = historyRows + 2

    companion object {
        const val ACTIVE_SCREEN_KEY = "terminal-active-screen"
        const val TAIL_KEY = "terminal-tail"

        fun fromFrame(
            frame: TerminalEmulator.RenderFrame,
            renderer: BenchmarkRenderer,
            configuredTui: Boolean = false,
            preserveFullGrid: Boolean = false,
        ): TerminalBenchmarkLayout {
            require(frame.historyCount >= 0 && frame.historyCount <= frame.rows.size)
            require(frame.screenStartRow == frame.historyCount)
            val ordinaryHistory = !frame.isAlternateScreen && !configuredTui && !preserveFullGrid
            val chunks = if (renderer == BenchmarkRenderer.CHUNKED_EAGER && ordinaryHistory &&
                frame.historyLineIds.size == frame.historyCount
            ) stableEagerHistoryChunks(frame.historyLineIds) else emptyList()
            return TerminalBenchmarkLayout(
                historyRows = frame.historyCount,
                screenRows = frame.rows.size - frame.historyCount,
                useLazyHistory = renderer == BenchmarkRenderer.LAZY_HISTORY &&
                    ordinaryHistory,
                historyChunks = chunks,
            )
        }
    }
}
