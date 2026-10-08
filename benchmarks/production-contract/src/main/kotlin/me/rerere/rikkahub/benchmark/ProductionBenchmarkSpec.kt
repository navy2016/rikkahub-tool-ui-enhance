package me.rerere.rikkahub.benchmark

/** Shared by the target and its driver; no Android or renderer implementation lives here. */
internal object ProductionBenchmarkSpec {
    const val VERSION = "production-viewport-v4"
    const val PHASE_TOKEN = "production.phase.token"
    const val PHASE_VALUE = "production.phase.value"
    const val PACKAGE = "me.rerere.rikkahub.terminalbenchmark"
    const val DRIVER_PACKAGE = "$PACKAGE.test"
    const val PHASE_ACTION = "$PACKAGE.PROGRESS"
    const val PHASE_PERMISSION = "$PACKAGE.permission.PROGRESS"
    const val ACTIVITY = "me.rerere.rikkahub.benchmark.ProductionTerminalBenchmarkActivity"
    const val UPDATE_COUNT = 30
    const val IME_UPDATE_COUNT = 8
    const val BACKGROUND_LINES = 12
    const val UPDATE_INTERVAL_MS = 33L
    val sizes = listOf(1_000, 5_000, 10_000)
    val scenarios = listOf("initialCompose", "activeRowUpdate", "appendAndTrim",
        "semanticJump", "imeRoundTrip", "detachRestore")
    val modes = listOf("chunkedLayers", "lazyHistory", "lazyHistoryIme")

    fun cases(group: String, smoke: Boolean = false): List<Array<Any>> {
        require(group in scenarios) { "Unknown production scenario: $group" }
        return (if (smoke) sizes.take(1) else sizes).flatMapIndexed { index, size ->
            val rotation = (index + scenarios.indexOf(group)) % modes.size
            val ordered = modes.drop(rotation) + modes.take(rotation)
            ordered.map { mode -> arrayOf<Any>(size, group, mode) }
        }
    }

    fun validate(size: Int, scenario: String, mode: String) {
        require(size in sizes && scenario in scenarios && mode in modes) { "Invalid production benchmark input" }
    }
}
