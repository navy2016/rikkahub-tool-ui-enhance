package me.rerere.rikkahub.pipeline

import android.util.Log
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.container.BackgroundProcessManager
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
    @get:Rule val timeout = Timeout.seconds(180)
    private val proot: PRootManager by inject()
    private val manager: BackgroundProcessManager by inject()

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
        val prefix = "export TERM=xterm-256color LINES=24 COLUMNS=80; export FORCE_COLOR=1 COLORTERM=truecolor; " +
            "stty sane rows 24 cols 80 2>/dev/null || stty rows 24 cols 80 2>/dev/null || true; "
        results += probe("cookedBuiltin", "COOKED_BUILTIN_READY\r\n") {
            runBlocking { proot.execNativePty("pipeline-transport", listOf("sh", "-c", "printf 'COOKED_BUILTIN_READY\\n'"),
                ptyMode = PtyMode.COOKED) }
        }
        results += probe("rawLogin", "RAW_LOGIN_READY\n") {
            runBlocking { proot.execNativePty("pipeline-transport", listOf("sh", "-lc", "printf 'RAW_LOGIN_READY\\n'"),
                ptyMode = PtyMode.RAW) }
        }
        results += probe("cookedLoginOnly", "LOGIN_READY\r\n") {
            runBlocking { proot.execNativePty("pipeline-transport", listOf("sh", "-lc", "printf 'LOGIN_READY\\n'"),
                ptyMode = PtyMode.COOKED) }
        }
        results += probe("pipeLogin", "PIPE_LOGIN_READY\n") {
            runBlocking { proot.execInteractive("pipeline-transport", listOf("sh", "-lc", "printf 'PIPE_LOGIN_READY\\n'")) }
        }
        results += probe("cookedSttyOnly", "BEFORE_STTY\r\nAFTER_STTY\r\n") {
            runBlocking { proot.execNativePty("pipeline-transport", listOf("sh", "-c",
                "printf 'BEFORE_STTY\\n'; stty sane rows 24 cols 80; printf 'AFTER_STTY\\n'"), ptyMode = PtyMode.COOKED) }
        }
        results += probe("rawExternal", "EXTERNAL_READY\n") {
            runBlocking { proot.execNativePty("pipeline-transport", listOf("sh", "-c", "/bin/echo EXTERNAL_READY"),
                ptyMode = PtyMode.RAW) }
        }
        results += probe("pipePrefix", "PIPE_PREFIX_READY\n") {
            runBlocking { proot.execInteractive("pipeline-transport", listOf("sh", "-lc", prefix + "printf 'PIPE_PREFIX_READY\\n'")) }
        }
        results += probe("redirectOnly", "REDIRECT_READY\r\n") {
            runBlocking { proot.execNativePty("pipeline-transport", listOf("sh", "-c",
                "true 2>/dev/null; printf 'REDIRECT_READY\\n'"), ptyMode = PtyMode.COOKED) }
        }
        results += probe("cookedLogin", "COOKED_READY\r\n") {
            runBlocking { proot.execNativePty("pipeline-transport", listOf("sh", "-lc", prefix + "printf 'COOKED_READY\\n'"),
                ptyMode = PtyMode.COOKED) }
        }
        results += probe("directWorkload", TerminalPipelineWorkload.READY, persistent = true) {
            runBlocking { proot.execNativePty("pipeline-transport",
                listOf("sh", "-lc", prefix + "exec " + TerminalPipelineWorkload.command), ptyMode = PtyMode.COOKED) }
        }
        results += managerProbe("managerPrintf", "printf 'MANAGER_READY\\n'", "MANAGER_READY\r\n")
        results += managerProbe("managerWorkload", TerminalPipelineWorkload.command, TerminalPipelineWorkload.READY)
        assertTrue("One or more transport probes failed; see TRANSPORT_PROBE scalar diagnostics", results.all { it })
    }

    private fun managerProbe(label: String, command: String, expected: String): Boolean {
        val started = runBlocking { manager.startInteractiveSession("pipeline-transport", command, ptyMode = PtyMode.COOKED) }
        if (!started.success) {
            Log.i("TerminalPipelineTest", "TRANSPORT_PROBE " + JSONObject().put("probe", label).put("matched", false)
                .put("timedOut", false).put("readerFailure", "start failed").put("stdoutBytes", 0).put("stderrBytes", 0))
            return false
        }
        var contents = ""
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            contents = manager.readInteractiveBuffer(started.processId).orEmpty()
            if (contents == expected) break
            Thread.sleep(20)
        }
        val info = manager.getProcess(started.processId)
        val report = JSONObject().put("probe", label).put("matched", contents == expected).put("timedOut", contents != expected)
            .put("readerFailure", JSONObject.NULL).put("stdoutBytes", contents.toByteArray().size).put("stderrBytes", 0)
            .put("status", info?.status?.name).put("exit", info?.exitCode).put("backend", info?.terminalBackend)
        started.pid?.let { pid -> scalarProcessState(pid, report) }
        Log.i("TerminalPipelineTest", "TRANSPORT_PROBE $report")
        runBlocking { manager.closeInteractiveSession(started.processId) }
        manager.removeProcessRecord(started.processId)
        return contents == expected
    }

    private fun scalarProcessState(pid: Int, report: JSONObject) {
        runCatching {
            File("/proc/$pid/status").useLines { lines ->
                lines.filter { it.startsWith("State:") || it.startsWith("TracerPid:") || it.startsWith("Threads:") }
                    .forEach { report.put(it.substringBefore(':'), it.substringAfter(':').trim()) }
            }
            report.put("wchan", File("/proc/$pid/wchan").readText().trim().take(100))
        }
    }

    private fun probe(label: String, expected: String, persistent: Boolean = false, create: () -> Process): Boolean {
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
            val output = pool.submit<ByteArray> { readBounded(child.inputStream, if (persistent) expected.toByteArray().size else 4096) }
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
            if (pid != null) scalarProcessState(pid, report)
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
        if (!matched) {
            // These probes run ONLY constant synthetic commands in the disposable test app.
            // Preserve bounded errors to distinguish shell startup from missing output delivery.
            report.put("fixedStdoutPrefix", bytesOut.toString(Charsets.UTF_8).take(240))
                .put("fixedStderrPrefix", bytesError.toString(Charsets.UTF_8).take(240))
        }
        Log.i("TerminalPipelineTest", "TRANSPORT_PROBE $report")
        return matched && bytesError.isEmpty() && !timedOut && readerFailure == null
    }

    private fun readBounded(input: InputStream, limit: Int = 4096): ByteArray {
        val out = ByteArrayOutputStream()
        val bytes = ByteArray(512)
        while (out.size() < limit) {
            val read = input.read(bytes, 0, minOf(bytes.size, limit - out.size()))
            if (read < 0) break
            if (read > 0) out.write(bytes, 0, read)
        }
        return out.toByteArray()
    }
}
