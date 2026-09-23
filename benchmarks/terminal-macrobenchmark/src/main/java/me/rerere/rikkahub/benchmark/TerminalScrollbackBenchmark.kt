package me.rerere.rikkahub.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@LargeTest
@OptIn(ExperimentalMetricApi::class)
@RunWith(Parameterized::class)
class TerminalScrollbackBenchmark(private val historyRows: Int) {
    companion object {
        private const val PACKAGE = "me.rerere.rikkahub.terminalbenchmark"
        private const val ACTIVITY = "me.rerere.rikkahub.benchmark.TerminalBenchmarkActivity"
        private const val TIMEOUT_MS = 180_000L

        @JvmStatic
        @Parameterized.Parameters(name = "history={0,number,#}")
        fun parameters() = listOf(arrayOf(1_000), arrayOf(5_000), arrayOf(10_000))
    }

    @get:Rule
    val benchmark = MacrobenchmarkRule()

    @Test
    fun initialCompose() = measure("initialCompose")

    @Test
    fun historyScroll() = measure("historyScroll")

    @Test
    fun activeRowUpdate() = measure("activeRowUpdate")

    @Test
    fun appendAndTrim() = measure("appendAndTrim")

    @Test
    fun alternateScreenUpdate() = measure("alternateScreenUpdate")

    private fun measure(scenario: String) {
        val iterations = InstrumentationRegistry.getArguments().getString("terminalIterations")?.toInt() ?: 5
        require(iterations in 1..50)
        val metrics = buildList {
            add(FrameTimingMetric())
            add(MemoryUsageMetric(MemoryUsageMetric.Mode.Max))
            add(TraceSectionMetric("Terminal.measure", label = "measure"))
            add(TraceSectionMetric("Terminal.draw", label = "draw"))
            if (scenario == "initialCompose") {
                add(TraceSectionMetric("Terminal.mountToDraw", TraceSectionMetric.Mode.First, "mountToDraw"))
            }
            if (scenario != "historyScroll") {
                add(TraceSectionMetric("Terminal.renderFrame", label = "renderFrame"))
                add(TraceSectionMetric("Terminal.rowSync", label = "rowSync"))
                add(TraceSectionMetric("Terminal.rowSync", TraceSectionMetric.Mode.Max, "rowSync"))
            }
        }
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = metrics,
            compilationMode = CompilationMode.Full(),
            iterations = iterations,
            setupBlock = {
                // Exclude emulator seeding and app startup, while avoiding cross-iteration cache/heap reuse.
                killProcess()
                startActivityAndWait(Intent().apply {
                    component = ComponentName(PACKAGE, ACTIVITY)
                    putExtra("history_rows", historyRows)
                    putExtra("scenario", scenario)
                })
                device.awaitStatus("prepared")
                if (scenario != "initialCompose") {
                    device.clickControl("benchmark_mount")
                    device.awaitStatus("mounted")
                }
                device.waitForIdle()
            },
            measureBlock = {
                device.clickControl(if (scenario == "initialCompose") "benchmark_mount" else "benchmark_run")
                device.awaitStatus(if (scenario == "initialCompose") "mounted" else "done")
                device.waitForIdle()
            },
        )
    }

    private fun UiDevice.clickControl(id: String) {
        checkNotNull(wait(Until.findObject(By.res(PACKAGE, id)), TIMEOUT_MS)) { "Missing control: $id" }.click()
    }

    private fun UiDevice.awaitStatus(text: String) {
        check(wait(Until.hasObject(By.res(PACKAGE, "benchmark_status").text(text)), TIMEOUT_MS)) {
            "Renderer did not reach '$text' (history=$historyRows); inspect logcat for crash/OOM"
        }
    }
}
