package me.rerere.rikkahub.benchmark

/** Shared by the target and its driver; no Android or renderer implementation lives here. */
internal object ProductionBenchmarkSpec {
    const val VERSION = "production-viewport-v1"
    const val PACKAGE = "me.rerere.rikkahub.terminalbenchmark"
    const val ACTIVITY = "me.rerere.rikkahub.benchmark.ProductionTerminalBenchmarkActivity"
    const val UPDATE_COUNT = 30
    const val IME_UPDATE_COUNT = 8
    const val BACKGROUND_LINES = 12
    const val UPDATE_INTERVAL_MS = 33L
    val sizes = listOf(1_000, 5_000, 10_000)
    val scenarios = listOf("initialCompose", "activeRowUpdate", "appendAndTrim",
        "semanticJump", "imeRoundTrip", "detachRestore")
    val modes = listOf("chunkedLayers", "lazyHistory")

    fun cases(group: String, smoke: Boolean = false): List<Array<Any>> {
        require(group in scenarios) { "Unknown production scenario: $group" }
        return (if (smoke) sizes.take(1) else sizes).flatMapIndexed { index, size ->
            val ordered = if ((index + scenarios.indexOf(group)) % 2 == 0) modes else modes.reversed()
            ordered.map { mode -> arrayOf<Any>(size, group, mode) }
        }
    }

    fun validate(size: Int, scenario: String, mode: String) {
        require(size in sizes && scenario in scenarios && mode in modes) { "Invalid production benchmark input" }
    }
}
