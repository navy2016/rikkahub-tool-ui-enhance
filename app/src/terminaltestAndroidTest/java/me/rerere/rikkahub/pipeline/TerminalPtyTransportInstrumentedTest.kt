package me.rerere.rikkahub.pipeline

import android.util.Log
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.container.NativePtyBridge
import me.rerere.rikkahub.data.container.NativePtyProcess
import me.rerere.rikkahub.data.container.PRootManager
import me.rerere.rikkahub.data.container.PtyMode
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounded transport probes before involving Compose, a session reader or stdin actors. */
class TerminalPtyTransportInstrumentedTest : KoinComponent {
    @get:Rule val timeout = Timeout.seconds(90)
    private val proot: PRootManager by inject()

    @Test fun transportNativeAndProotProduceExactFixedBytes() {
        assertTrue("Native PTY unavailable: ${NativePtyBridge.unavailableReason}", NativePtyBridge.isAvailable)
        val results = mutableListOf<Boolean>()
        results += probe("androidPty", "ANDROID_PTY_READY\n") {
            requireNotNull(NativePtyBridge.start(listOf("/system/bin/sh", "-c", "printf 'ANDROID_PTY_READY\\n'"),
                System.getenv(), 80, 24, PtyMode.RAW))
        }
        runBlocking { proot.start().getOrThrow() }
        results += probe("prootPipe", "PROOT_PIPE_READY\n") {
            runBlocking { proot.execInteractive("pipeline-transport", listOf("sh", "-c", "printf 'PROOT_PIPE_READY\\n'")) }
        }
        results += probe("prootPty", "PROOT_PTY_READY\n") {
            runBlocking { proot.execNativePty("pipeline-transport", listOf("sh", "-c", "printf 'PROOT_PTY_READY\\n'"),
                ptyMode = PtyMode.RAW) }
        }
        assertTrue("One or more transport probes failed; see TRANSPORT_PROBE scalar diagnostics", results.all { it })
    }

    private fun probe(label: String, expected: String, create: () -> Process): Boolean {
        val pool = Executors.newFixedThreadPool(2) { task -> Thread(task, "pipeline-probe-reader").apply { isDaemon = true } }
        var process: Process? = null
        var bytesOut = ByteArray(0)
        var bytesError = ByteArray(0)
        var readerFailure: String? = null
        var timedOut = false
        val report = JSONObject().put("probe", label)
        try {
            val child = create()
            process = child
            val output = pool.submit<ByteArray> { readBounded(child.inputStream) }
            val errors = pool.submit<ByteArray> { readBounded(child.errorStream) }
            try {
                bytesOut = output.get(8, TimeUnit.SECONDS)
                bytesError = errors.get(2, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                timedOut = true
            } catch (error: Exception) {
                readerFailure = (error.cause ?: error).javaClass.simpleName
            }
            // Read process state before destroying; retain only numeric/state fields, not argv/env.
            val pid = (child as? NativePtyProcess)?.pidOrNull()
            if (pid != null) runCatching {
                File("/proc/$pid/status").useLines { lines ->
                    lines.filter { it.startsWith("State:") || it.startsWith("TracerPid:") || it.startsWith("Threads:") }
                        .forEach { report.put(it.substringBefore(':'), it.substringAfter(':').trim()) }
                }
                report.put("wchan", File("/proc/$pid/wchan").readText().trim().take(100))
            }
            report.put("alive", child.isAlive)
            if (!child.isAlive) report.put("exit", runCatching { child.exitValue() }.getOrDefault(-999))
        } catch (error: Exception) {
            readerFailure = error.javaClass.simpleName
        } finally {
            process?.let { child ->
                runCatching { if (child.isAlive) child.destroyForcibly() }
                runCatching { child.inputStream.close() }
                runCatching { child.errorStream.close() }
                runCatching { child.outputStream.close() }
            }
            pool.shutdownNow()
        }
        val matched = bytesOut.contentEquals(expected.toByteArray())
        report.put("matched", matched).put("stdoutBytes", bytesOut.size).put("stderrBytes", bytesError.size)
            .put("timedOut", timedOut).put("readerFailure", readerFailure ?: JSONObject.NULL)
        Log.i("TerminalPipelineTest", "TRANSPORT_PROBE $report")
        return matched && bytesError.isEmpty() && !timedOut && readerFailure == null
    }

    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val bytes = ByteArray(512)
        while (out.size() < 4_096) {
            val read = input.read(bytes, 0, minOf(bytes.size, 4_096 - out.size()))
            if (read < 0) break
            if (read > 0) out.write(bytes, 0, read)
        }
        return out.toByteArray()
    }
}
