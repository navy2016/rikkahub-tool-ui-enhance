package me.rerere.rikkahub.benchmark

import me.rerere.rikkahub.utils.TerminalEmulator

/** Deterministic ANSI/ASCII/CJK output, always narrower than the physical 80-column screen. */
internal object TerminalBenchmarkWorkload {
    const val COLUMNS = 80
    const val SCREEN_ROWS = 24
    const val UPDATE_COUNT = 30
    const val UPDATE_INTERVAL_MS = 33L
    val historySizes = listOf(1_000, 5_000, 10_000)

    fun line(index: Int): String = buildString {
        append("\u001b[36m")
        append(index.toString().padStart(6, '0'))
        append("\u001b[0m ")
        when (index % 4) {
            0 -> append("\u001b[1;32mPASS\u001b[0m compile terminal-renderer.kt (cached) 0123456789")
            1 -> append("\u001b[33mWARN\u001b[0m viewport history retained; output batch 0123456789")
            2 -> append("\u001b[35mINFO\u001b[0m 中文输出 / build progress 0123456789 abcdefghijkl")
            else -> append("\u001b[34mTRACE\u001b[0m /workspace/project/src/terminal/renderer.kt:120")
        }
    }

    /** Does not render: the first renderFrame must remain cold for the initial-compose scenario. */
    fun prepare(historyRows: Int): TerminalEmulator {
        require(historyRows in historySizes)
        return TerminalEmulator(COLUMNS, SCREEN_ROWS, maxScrollbackLines = historyRows).apply {
            repeat(historyRows + SCREEN_ROWS) { index ->
                if (index > 0) feed("\r\n")
                feed(line(index))
            }
        }
    }

    fun enterAlternateScreen(terminal: TerminalEmulator) {
        terminal.feed("\u001b[?1049h")
        repeat(SCREEN_ROWS) { index ->
            if (index > 0) terminal.feed("\r\n")
            terminal.feed(line(index))
        }
    }
}
