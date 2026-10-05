package me.rerere.rikkahub.benchmark

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.UUID

/** Each invocation is one complete scenario, ALL selected sizes and BOTH actual production modes. */
@LargeTest
@OptIn(ExperimentalMetricApi::class)
@RunWith(Parameterized::class)
class ProductionTerminalBenchmark(private val history: Int, private val scenario: String, private val mode: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "history={0,number,#},scenario={1},mode={2}")
        fun cases(): List<Array<Any>> {
            val arguments = InstrumentationRegistry.getArguments()
            return ProductionBenchmarkSpec.cases(checkNotNull(arguments.getString("productionScenario")),
                smoke = arguments.getString("productionSmoke") == "true")
        }
    }

    @get:Rule val benchmark = MacrobenchmarkRule()
    private lateinit var progress: ProductionBenchmarkProgress
    private var phaseReceiver: BroadcastReceiver? = null

    @After fun closePhaseReceiver() {
        phaseReceiver?.let { InstrumentationRegistry.getInstrumentation().context.unregisterReceiver(it) }
        phaseReceiver = null
    }

    private fun newPhaseReceiver(token: String) {
        closePhaseReceiver()
        val receipt = ProductionBenchmarkProgress(token)
        progress = receipt
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ProductionBenchmarkSpec.PHASE_ACTION) receipt.accept(
                    intent.getStringExtra(ProductionBenchmarkSpec.PHASE_TOKEN),
                    intent.getStringExtra(ProductionBenchmarkSpec.PHASE_VALUE))
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().context
        check(context.packageName == ProductionBenchmarkSpec.DRIVER_PACKAGE)
        val filter = IntentFilter(ProductionBenchmarkSpec.PHASE_ACTION)
        val handler = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, ProductionBenchmarkSpec.PHASE_PERMISSION, handler,
                Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter, ProductionBenchmarkSpec.PHASE_PERMISSION, handler)
        }
        phaseReceiver = receiver
    }

    @Test fun production() {
        val arguments = InstrumentationRegistry.getArguments()
        val iterations = arguments.getString("terminalIterations")?.toInt() ?: 3
        require(iterations in 1..50)
        ProductionBenchmarkSpec.validate(history, scenario, mode)
        val sections = listOf(
            "Prod.mountToSettledDraw" to "mount",
            "Prod.operation" to "operation",
            "Prod.outputToSettledDraw" to "output",
            "Prod.feed" to "feed",
            "Prod.renderFrame" to "renderFrame",
            "Prod.rowSync" to "rowSync",
            "Prod.measure" to "measure",
            "Prod.draw" to "draw",
            "Prod.validation" to "validation",
            "Prod.jumpTop" to "jumpTop",
            "Prod.jumpTail" to "jumpTail",
            "Prod.imeShow" to "imeShow",
            "Prod.imeHide" to "imeHide",
            "Prod.explicitRetry" to "explicitRetry",
            "Prod.detach" to "detach",
            "Prod.restoreToSettledDraw" to "restore",
            "Terminal.productionWidth" to "width",
            "Terminal.productionEagerGeometry" to "eagerGeometry",
            "Terminal.productionViewportUpdate" to "viewport",
            "Terminal.productionScrollEffect" to "scrollEffect",
        )
        val metrics = buildList {
            add(FrameTimingMetric())
            add(MemoryUsageMetric(MemoryUsageMetric.Mode.Max))
            for ((section, label) in sections) {
                add(TraceSectionMetric(section, label = label))
                add(TraceSectionMetric(section, TraceSectionMetric.Mode.Max, label))
            }
        }
        benchmark.measureRepeated(
            packageName = ProductionBenchmarkSpec.PACKAGE,
            metrics = metrics,
            compilationMode = CompilationMode.Full(),
            iterations = iterations,
            setupBlock = {
                killProcess()
                // Fresh receiver + nonce per Activity launch: old or cached completion cannot pass.
                val token = UUID.randomUUID().toString()
                newPhaseReceiver(token)
                startActivityAndWait(Intent().apply {
                    component = ComponentName(ProductionBenchmarkSpec.PACKAGE, ProductionBenchmarkSpec.ACTIVITY)
                    putExtra("history_rows", history)
                    putExtra("scenario", scenario)
                    putExtra("renderer", mode)
                    putExtra(ProductionBenchmarkSpec.PHASE_TOKEN, token)
                })
                awaitPhase("prepared")
                if (scenario != "initialCompose") {
                    device.clickControl("benchmark_mount")
                    awaitPhase("mounted")
                }
                device.waitForIdle()
            },
            measureBlock = {
                device.clickControl(if (scenario == "initialCompose") "benchmark_mount" else "benchmark_run")
                awaitPhase(if (scenario == "initialCompose") "mounted" else "done")
                device.waitForIdle()
            },
        )
    }

    private fun UiDevice.clickControl(id: String) {
        checkNotNull(wait(Until.findObject(By.res(ProductionBenchmarkSpec.PACKAGE, id)), 15_000)) {
            "Missing benchmark control $id"
        }.click()
    }

    private fun awaitPhase(phase: String) {
        check(progress.await(phase, 150_000)) {
            "Production viewport timed out: $history/$scenario/$mode/$phase; last=${progress.currentPhase}"
        }
    }
}
