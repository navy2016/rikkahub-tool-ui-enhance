package me.rerere.rikkahub.data.container

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class ControlInput {
    CTRL_C,
    CTRL_D,
    TAB,
    ESC,
    UP,
    DOWN,
    ENTER,
    BACKSPACE
}

/**
 * 后台进程管理器
 *
 * 负责管理两类进程：
 * 1. 后台非交互进程（原有 container_shell_bg 行为）：输出到日志文件
 * 2. 交互式 session（container_shell_bg interactive=true）：支持 stdin / 实时输出 / 可选 tty
 */
@Singleton
class BackgroundProcessManager @Inject constructor(
    private val context: Context
) {
    private val prootManager: PRootManager
        get() = GlobalContext.get().get()

    companion object {
        private const val TAG = "BackgroundProcessManager"

        // 日志文件大小限制（10MB），internal 以便 PRootManager 访问
        internal const val MAX_LOG_FILE_SIZE = 10 * 1024 * 1024 // 10MB

        private const val PROCESS_CHECK_INTERVAL_MS = 5000L
        private const val MAX_RUNNING_PROCESSES_PER_SANDBOX = 10
        private const val MAX_INTERACTIVE_SESSIONS = 5
        private const val INTERACTIVE_BUFFER_MAX_BYTES = 256 * 1024
    }

    /**
     * 原有后台进程记录
     */
    private val processes = ConcurrentHashMap<String, BackgroundProcessInfo>()

    /**
     * 新增：交互式 session 记录
     */
    private val interactiveSessions = ConcurrentHashMap<String, InteractiveSessionRecord>()

    /**
     * 统一状态流（后台进程 + 交互 session）
     */
    private val _processStates = MutableStateFlow<List<BackgroundProcessInfo>>(emptyList())
    val processStates: StateFlow<List<BackgroundProcessInfo>> = _processStates.asStateFlow()

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitoringJob: Job? = null

    init {
        startProcessMonitoring()
    }

    /**
     * 交互式会话输出缓冲
     * 仅保留最近 maxBytes 数据，供 UI 展示和 tool read/logs 复用
     */
    private class SessionOutputBuffer(
        private val maxBytes: Int = INTERACTIVE_BUFFER_MAX_BYTES
    ) {
        private val data = ByteArrayOutputStream()

        @Synchronized
        fun append(bytes: ByteArray) {
            val current = data.toByteArray()
            val merged = current + bytes
            val trimmed = if (merged.size > maxBytes) {
                merged.copyOfRange(merged.size - maxBytes, merged.size)
            } else {
                merged
            }
            data.reset()
            data.write(trimmed)
        }

        @Synchronized
        fun snapshot(): ByteArray = data.toByteArray()

        @Synchronized
        fun snapshotAsString(): String = data.toByteArray().toString(Charsets.UTF_8)
    }

    /**
     * 交互式 session 运行态记录
     */
    private data class InteractiveSessionRecord(
        val processId: String,
        val sandboxId: String,
        val command: String,
        val process: Process,
        val ttyEnabled: Boolean,
        val outputFlow: MutableSharedFlow<ByteArray>,
        val outputBuffer: SessionOutputBuffer,
        var stdoutJob: Job? = null,
        var stderrJob: Job? = null,
        var waiterJob: Job? = null,
        val createdAt: Long = System.currentTimeMillis(),
        var startedAt: Long? = System.currentTimeMillis(),
        var exitedAt: Long? = null,
        var exitCode: Int? = null,
        var finalStatus: ProcessStatus? = null,
        var lastActivityAt: Long = System.currentTimeMillis(),
        val tag: String? = null
    )

    /**
     * 启动后台进程（原有逻辑）
     */
    suspend fun startBackgroundProcess(
        sandboxId: String,
        command: String,
        tag: String? = null
    ): ProcessExecutionResult = withContext(Dispatchers.IO) {
        try {
            val runningCount = processes.values
                .count { it.sandboxId == sandboxId && it.status == ProcessStatus.RUNNING }

            if (runningCount >= MAX_RUNNING_PROCESSES_PER_SANDBOX) {
                return@withContext ProcessExecutionResult(
                    success = false,
                    processId = "",
                    status = ProcessStatus.FAILED,
                    message = "Too many running processes (max $MAX_RUNNING_PROCESSES_PER_SANDBOX). Please stop some processes first."
                )
            }

            val processId = generateProcessId()
            val timestamp = System.currentTimeMillis()

            val logsDir = File(context.filesDir, "sandboxes/$sandboxId/logs").apply { mkdirs() }
            val stdoutFile = File(logsDir, "$processId.stdout.log")
            val stderrFile = File(logsDir, "$processId.stderr.log")

            val processInfo = BackgroundProcessInfo(
                processId = processId,
                sandboxId = sandboxId,
                command = command,
                status = ProcessStatus.STARTING,
                pid = null,
                stdoutPath = stdoutFile.absolutePath,
                stderrPath = stderrFile.absolutePath,
                createdAt = timestamp,
                startedAt = null,
                exitedAt = null,
                exitCode = null,
                tag = tag
            )

            val result = prootManager.execInBackground(
                sandboxId = sandboxId,
                command = listOf("sh", "-c", command),
                processId = processId,
                stdoutFile = stdoutFile,
                stderrFile = stderrFile
            )

            if (result.exitCode == 0) {
                val startedAt = System.currentTimeMillis()
                val pid = extractPidFromOutput(result.stdout)

                val updatedInfo = processInfo.copy(
                    status = ProcessStatus.RUNNING,
                    pid = pid,
                    startedAt = startedAt
                )

                processes[processId] = updatedInfo
                refreshProcessStates()

                Log.i(TAG, "Started background process: $processId, command: $command")

                ProcessExecutionResult(
                    success = true,
                    processId = processId,
                    status = ProcessStatus.RUNNING,
                    message = "Process started successfully",
                    stdoutFile = stdoutFile.absolutePath,
                    stderrFile = stderrFile.absolutePath,
                    pid = pid,
                    isInteractive = false,
                    stdinEnabled = false,
                    ttyEnabled = false
                )
            } else {
                val failedInfo = processInfo.copy(status = ProcessStatus.FAILED)
                processes[processId] = failedInfo
                refreshProcessStates()

                ProcessExecutionResult(
                    success = false,
                    processId = processId,
                    status = ProcessStatus.FAILED,
                    message = "Failed to start process: ${result.stderr}",
                    isInteractive = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting background process", e)
            ProcessExecutionResult(
                success = false,
                processId = "",
                status = ProcessStatus.FAILED,
                message = "Error: ${e.message}",
                isInteractive = false
            )
        }
    }

    /**
     * 启动交互式 session
     *
     * 优先尝试使用 script 分配轻量 tty；
     * 如果容器内不存在 script，则自动 fallback 为普通 pipe 模式。
     */
    suspend fun startInteractiveSession(
        sandboxId: String,
        command: String,
        tag: String? = null,
        preferTty: Boolean = true
    ): ProcessExecutionResult = withContext(Dispatchers.IO) {
        try {
            val aliveInteractiveCount = interactiveSessions.values.count { it.process.isAlive }
            if (aliveInteractiveCount >= MAX_INTERACTIVE_SESSIONS) {
                return@withContext ProcessExecutionResult(
                    success = false,
                    processId = "",
                    status = ProcessStatus.FAILED,
                    message = "Too many interactive sessions (max $MAX_INTERACTIVE_SESSIONS)."
                )
            }

            val processId = generateProcessId()
            val ttyEnabled = preferTty && hasScriptCommand(sandboxId)

            val wrappedCommand = if (ttyEnabled) {
                val quoted = shellQuote(command)
                "TERM=xterm-256color LINES=24 COLUMNS=80 script -q -e -c $quoted /dev/null"
            } else {
                "TERM=xterm-256color LINES=24 COLUMNS=80 $command"
            }

            val process = prootManager.execInteractive(
                sandboxId = sandboxId,
                command = listOf("sh", "-lc", wrappedCommand)
            )

            val outputFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
            val outputBuffer = SessionOutputBuffer()

            val record = InteractiveSessionRecord(
                processId = processId,
                sandboxId = sandboxId,
                command = command,
                process = process,
                ttyEnabled = ttyEnabled,
                outputFlow = outputFlow,
                outputBuffer = outputBuffer,
                tag = tag
            )

            interactiveSessions[processId] = record

            record.stdoutJob = launchStreamReader(
                inputStream = process.inputStream,
                outputFlow = outputFlow,
                buffer = outputBuffer
            ) {
                record.lastActivityAt = System.currentTimeMillis()
            }

            record.stderrJob = launchStreamReader(
                inputStream = process.errorStream,
                outputFlow = outputFlow,
                buffer = outputBuffer
            ) {
                record.lastActivityAt = System.currentTimeMillis()
            }

            record.waiterJob = appScope.launch {
                val code = try {
                    process.waitFor()
                } catch (_: Exception) {
                    -1
                }

                interactiveSessions[processId]?.let { session ->
                    session.exitCode = code
                    session.exitedAt = System.currentTimeMillis()
                    if (session.finalStatus == null) {
                        session.finalStatus = if (code == 0) {
                            ProcessStatus.COMPLETED
                        } else {
                            ProcessStatus.FAILED
                        }
                    }
                }
                refreshProcessStates()
            }

            refreshProcessStates()

            ProcessExecutionResult(
                success = true,
                processId = processId,
                status = ProcessStatus.RUNNING,
                message = "Interactive session started",
                pid = tryGetPid(process),
                isInteractive = true,
                stdinEnabled = true,
                ttyEnabled = ttyEnabled
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting interactive session", e)
            ProcessExecutionResult(
                success = false,
                processId = "",
                status = ProcessStatus.FAILED,
                message = "Error: ${e.message}",
                isInteractive = true
            )
        }
    }

    /**
     * 获取进程信息（统一视图）
     */
    fun getProcess(processId: String): BackgroundProcessInfo? {
        refreshProcessStates()
        return _processStates.value.firstOrNull { it.processId == processId }
    }

    /**
     * 获取指定 sandbox 的所有进程
     */
    fun getProcessesBySandbox(sandboxId: String): List<BackgroundProcessInfo> {
        refreshProcessStates()
        return _processStates.value.filter { it.sandboxId == sandboxId }
    }

    /**
     * 获取所有进程
     */
    fun getAllProcesses(): List<BackgroundProcessInfo> {
        refreshProcessStates()
        return _processStates.value
    }

    /**
     * 向交互式 session 发送文本输入
     */
    suspend fun sendInput(
        processId: String,
        input: String,
        appendNewline: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val record = interactiveSessions[processId]
            ?: return@withContext Result.failure(
                IllegalStateException("Interactive session not found: $processId")
            )

        try {
            val bytes = if (appendNewline) {
                (input + "\n").toByteArray(Charsets.UTF_8)
            } else {
                input.toByteArray(Charsets.UTF_8)
            }
            record.process.outputStream.write(bytes)
            record.process.outputStream.flush()
            record.lastActivityAt = System.currentTimeMillis()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending input to session: $processId", e)
            Result.failure(e)
        }
    }

    /**
     * 发送控制输入
     */
    suspend fun sendControlInput(
        processId: String,
        control: ControlInput
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val record = interactiveSessions[processId]
            ?: return@withContext Result.failure(
                IllegalStateException("Interactive session not found: $processId")
            )

        try {
            val bytes = when (control) {
                ControlInput.CTRL_C -> byteArrayOf(0x03)
                ControlInput.CTRL_D -> byteArrayOf(0x04)
                ControlInput.TAB -> byteArrayOf('\t'.code.toByte())
                ControlInput.ESC -> byteArrayOf(0x1B)
                ControlInput.UP -> byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())
                ControlInput.DOWN -> byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())
                ControlInput.ENTER -> byteArrayOf('\n'.code.toByte())
                ControlInput.BACKSPACE -> byteArrayOf(0x7F)
            }

            record.process.outputStream.write(bytes)
            record.process.outputStream.flush()
            record.lastActivityAt = System.currentTimeMillis()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending control input to session: $processId", e)
            Result.failure(e)
        }
    }

    /**
     * 观察交互式输出流
     */
    fun observeOutput(processId: String): Flow<ByteArray>? {
        return interactiveSessions[processId]?.outputFlow?.asSharedFlow()
    }

    /**
     * 读取交互式输出缓冲
     */
    fun readInteractiveBuffer(processId: String): String? {
        return interactiveSessions[processId]?.outputBuffer?.snapshotAsString()
    }

    /**
     * 关闭交互式会话
     */
    suspend fun closeInteractiveSession(processId: String): ProcessExecutionResult = withContext(Dispatchers.IO) {
        val record = interactiveSessions[processId]
            ?: return@withContext ProcessExecutionResult(
                success = false,
                processId = processId,
                status = ProcessStatus.FAILED,
                message = "Interactive session not found: $processId"
            )

        try {
            if (record.process.isAlive) {
                record.finalStatus = ProcessStatus.STOPPED
                record.exitedAt = System.currentTimeMillis()

                record.process.destroy()
                if (!record.process.waitFor(300, TimeUnit.MILLISECONDS)) {
                    record.process.destroyForcibly()
                }
            }

            record.exitCode = try {
                record.process.exitValue()
            } catch (_: Exception) {
                -1
            }

            record.stdoutJob?.cancel()
            record.stderrJob?.cancel()
            record.waiterJob?.cancel()

            refreshProcessStates()

            ProcessExecutionResult(
                success = true,
                processId = processId,
                status = ProcessStatus.STOPPED,
                message = "Interactive session stopped",
                pid = tryGetPid(record.process),
                isInteractive = true,
                stdinEnabled = true,
                ttyEnabled = record.ttyEnabled
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error closing interactive session: $processId", e)
            ProcessExecutionResult(
                success = false,
                processId = processId,
                status = ProcessStatus.FAILED,
                message = "Error: ${e.message}",
                isInteractive = true
            )
        }
    }

    /**
     * 终止进程
     * 对交互 session 自动转到 closeInteractiveSession
     */
    suspend fun killProcess(processId: String): ProcessExecutionResult = withContext(Dispatchers.IO) {
        interactiveSessions[processId]?.let {
            return@withContext closeInteractiveSession(processId)
        }

        try {
            val managedProcess = processes[processId]
                ?: return@withContext ProcessExecutionResult(
                    success = false,
                    processId = processId,
                    status = ProcessStatus.FAILED,
                    message = "Process not found: $processId"
                )

            val result = prootManager.killBackgroundProcess(processId)

            if (result.exitCode == 0) {
                val updatedInfo = managedProcess.copy(
                    status = ProcessStatus.STOPPED,
                    exitedAt = System.currentTimeMillis()
                )
                processes[processId] = updatedInfo
                refreshProcessStates()

                ProcessExecutionResult(
                    success = true,
                    processId = processId,
                    status = ProcessStatus.STOPPED,
                    message = "Process stopped successfully"
                )
            } else {
                ProcessExecutionResult(
                    success = false,
                    processId = processId,
                    status = managedProcess.status,
                    message = result.stderr
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error killing process: $processId", e)
            ProcessExecutionResult(
                success = false,
                processId = processId,
                status = ProcessStatus.FAILED,
                message = "Error: ${e.message}"
            )
        }
    }

    /**
     * 读取进程日志
     * - 普通后台进程：读取日志文件
     * - 交互 session：读取内存缓冲
     */
    suspend fun readProcessLogs(
        processId: String,
        stream: String = "stdout",
        offset: Int = 0,
        limit: Int = 1000
    ): LogReadResult = withContext(Dispatchers.IO) {
        try {
            interactiveSessions[processId]?.let { session ->
                val allLines = session.outputBuffer.snapshotAsString().lines()
                val totalLines = allLines.size
                val lines = if (offset < totalLines) {
                    val end = minOf(offset + limit, totalLines)
                    allLines.subList(offset, end)
                } else {
                    emptyList()
                }

                return@withContext LogReadResult(
                    lines = lines,
                    totalLines = totalLines,
                    hasMore = offset + limit < totalLines
                )
            }

            val processInfo = processes[processId]
                ?: return@withContext LogReadResult(
                    lines = emptyList(),
                    totalLines = 0,
                    hasMore = false,
                    error = "Process not found: $processId"
                )

            val logFile = when (stream) {
                "stdout" -> File(processInfo.stdoutPath)
                "stderr" -> File(processInfo.stderrPath)
                else -> return@withContext LogReadResult(
                    lines = emptyList(),
                    totalLines = 0,
                    hasMore = false,
                    error = "Invalid stream: $stream"
                )
            }

            if (!logFile.exists()) {
                return@withContext LogReadResult(
                    lines = emptyList(),
                    totalLines = 0,
                    hasMore = false
                )
            }

            val allLines = logFile.readLines()
            val totalLines = allLines.size

            val lines = if (offset < allLines.size) {
                val end = minOf(offset + limit, allLines.size)
                allLines.subList(offset, end)
            } else {
                emptyList()
            }

            LogReadResult(
                lines = lines,
                totalLines = totalLines,
                hasMore = offset + limit < totalLines
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading logs for process: $processId", e)
            LogReadResult(
                lines = emptyList(),
                totalLines = 0,
                hasMore = false,
                error = "Error: ${e.message}"
            )
        }
    }

    /**
     * 删除单条进程记录（仅允许删除已结束记录）
     */
    fun removeProcessRecord(processId: String): Boolean {
        val interactive = interactiveSessions[processId]
        if (interactive != null) {
            if (interactive.process.isAlive) return false
            interactive.stdoutJob?.cancel()
            interactive.stderrJob?.cancel()
            interactive.waiterJob?.cancel()
            interactiveSessions.remove(processId)
            refreshProcessStates()
            return true
        }

        val info = processes[processId] ?: return false
        if (info.status == ProcessStatus.RUNNING || info.status == ProcessStatus.STARTING) {
            return false
        }

        try {
            if (info.stdoutPath.isNotBlank()) File(info.stdoutPath).delete()
            if (info.stderrPath.isNotBlank()) File(info.stderrPath).delete()
        } catch (_: Exception) {
        }

        processes.remove(processId)
        refreshProcessStates()
        return true
    }

    /**
     * 清理已结束的旧进程记录
     */
    suspend fun cleanupOldProcesses(
        olderThan: Long = 24 * 60 * 60 * 1000L
    ): Int = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()

            val toRemove = processes.values
                .filter { it.exitedAt != null && (now - it.exitedAt!!) > olderThan }
                .map { it.processId }

            toRemove.forEach { processId ->
                val info = processes[processId]
                info?.let {
                    try {
                        File(it.stdoutPath).delete()
                        File(it.stderrPath).delete()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete log files for process $processId", e)
                    }
                }
                processes.remove(processId)
            }

            val interactiveToRemove = interactiveSessions.values
                .filter { it.exitedAt != null && (now - it.exitedAt!!) > olderThan }
                .map { it.processId }

            interactiveToRemove.forEach { processId ->
                interactiveSessions[processId]?.let { record ->
                    record.stdoutJob?.cancel()
                    record.stderrJob?.cancel()
                    record.waiterJob?.cancel()
                }
                interactiveSessions.remove(processId)
            }

            refreshProcessStates()

            Log.i(
                TAG,
                "Cleaned up ${toRemove.size} old background processes and ${interactiveToRemove.size} old interactive sessions"
            )

            toRemove.size + interactiveToRemove.size
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old processes", e)
            0
        }
    }

    /**
     * 清理指定 sandbox 的所有进程
     */
    suspend fun cleanupSandboxProcesses(sandboxId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val sandboxProcesses = processes.values
                .filter { it.sandboxId == sandboxId && it.status == ProcessStatus.RUNNING }
                .map { it.processId }

            sandboxProcesses.forEach { processId ->
                killProcess(processId)
            }

            val interactiveIds = interactiveSessions.values
                .filter { it.sandboxId == sandboxId }
                .map { it.processId }

            interactiveIds.forEach { processId ->
                closeInteractiveSession(processId)
            }

            val bgToRemove = processes.filter { it.value.sandboxId == sandboxId }.keys.toList()
            bgToRemove.forEach { processes.remove(it) }

            interactiveIds.forEach { processId ->
                interactiveSessions[processId]?.stdoutJob?.cancel()
                interactiveSessions[processId]?.stderrJob?.cancel()
                interactiveSessions[processId]?.waiterJob?.cancel()
                interactiveSessions.remove(processId)
            }

            refreshProcessStates()
            Result.success(bgToRemove.size + interactiveIds.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 停止所有运行中的进程
     */
    suspend fun stopAllProcesses(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val runningProcesses = processes.values
                .filter { it.status == ProcessStatus.RUNNING }
                .map { it.processId }

            runningProcesses.forEach { processId ->
                killProcess(processId)
            }

            interactiveSessions.keys.toList().forEach { processId ->
                closeInteractiveSession(processId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 标记所有运行中的进程为已停止（容器停止时调用）
     */
    fun markAllProcessesStopped() {
        val updatedProcesses = processes.values.map { process ->
            if (process.status == ProcessStatus.RUNNING || process.status == ProcessStatus.STARTING) {
                process.copy(
                    status = ProcessStatus.STOPPED,
                    exitedAt = System.currentTimeMillis(),
                    exitCode = -1
                )
            } else {
                process
            }
        }

        updatedProcesses.forEach { process ->
            processes[process.processId] = process
        }

        interactiveSessions.values.forEach { record ->
            record.finalStatus = ProcessStatus.STOPPED
            record.exitedAt = System.currentTimeMillis()
            record.exitCode = -1
            try {
                if (record.process.isAlive) {
                    record.process.destroyForcibly()
                }
            } catch (_: Exception) {
            }
        }

        refreshProcessStates()
        Log.d(TAG, "All processes marked as stopped")
    }

    /**
     * 清理所有进程状态（容器销毁时调用）
     */
    fun clearAllProcesses() {
        interactiveSessions.values.forEach { record ->
            try {
                if (record.process.isAlive) {
                    record.process.destroyForcibly()
                }
            } catch (_: Exception) {
            }
            record.stdoutJob?.cancel()
            record.stderrJob?.cancel()
            record.waiterJob?.cancel()
        }

        processes.clear()
        interactiveSessions.clear()
        _processStates.value = emptyList()
        Log.d(TAG, "All process states cleared")
    }

    /**
     * 生成进程ID
     */
    private fun generateProcessId(): String {
        val uuid = UUID.randomUUID().toString().take(8)
        val timestamp = System.currentTimeMillis().toString(36)
        return "proc_${timestamp}_$uuid"
    }

    /**
     * 启动进程监控协程
     */
    private fun startProcessMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = appScope.launch {
            while (true) {
                try {
                    delay(PROCESS_CHECK_INTERVAL_MS)
                    updateProcessStates()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in process monitoring", e)
                }
            }
        }
        Log.i(TAG, "Process monitoring started")
    }

    /**
     * 周期性更新进程状态
     */
    private suspend fun updateProcessStates() {
        val updatedBackground = processes.values.map { process ->
            if (process.status == ProcessStatus.RUNNING && process.pid != null) {
                if (!isProcessAlive(process.pid)) {
                    process.copy(
                        status = ProcessStatus.FAILED,
                        exitedAt = System.currentTimeMillis(),
                        exitCode = -1
                    )
                } else {
                    process
                }
            } else {
                process
            }
        }

        updatedBackground.forEach { process ->
            processes[process.processId] = process
        }

        interactiveSessions.values.forEach { session ->
            if (!session.process.isAlive && session.finalStatus == null) {
                val exitCode = try {
                    session.process.exitValue()
                } catch (_: Exception) {
                    -1
                }
                session.exitCode = exitCode
                session.exitedAt = System.currentTimeMillis()
                session.finalStatus = if (exitCode == 0) {
                    ProcessStatus.COMPLETED
                } else {
                    ProcessStatus.FAILED
                }
            }
        }

        refreshProcessStates()
    }

    /**
     * 统一重建状态流
     */
    private fun refreshProcessStates() {
        val backgroundList = processes.values.toList()

        val interactiveList = interactiveSessions.values.map { record ->
            val status = when {
                record.process.isAlive -> ProcessStatus.RUNNING
                record.finalStatus != null -> record.finalStatus!!
                record.exitCode == 0 -> ProcessStatus.COMPLETED
                else -> ProcessStatus.FAILED
            }

            BackgroundProcessInfo(
                processId = record.processId,
                sandboxId = record.sandboxId,
                command = record.command,
                status = status,
                pid = tryGetPid(record.process),
                stdoutPath = "",
                stderrPath = "",
                createdAt = record.createdAt,
                startedAt = record.startedAt,
                exitedAt = record.exitedAt,
                exitCode = record.exitCode,
                tag = record.tag,
                isInteractive = true,
                stdinEnabled = true,
                ttyEnabled = record.ttyEnabled,
                processSource = "container_shell_bg"
            )
        }

        _processStates.value = (backgroundList + interactiveList)
            .sortedByDescending { it.createdAt }
    }

    /**
     * 读取交互流，按 byte chunk 处理
     */
    private fun launchStreamReader(
        inputStream: InputStream,
        outputFlow: MutableSharedFlow<ByteArray>,
        buffer: SessionOutputBuffer,
        onChunk: () -> Unit = {}
    ): Job = appScope.launch {
        try {
            val chunk = ByteArray(4096)
            while (isActive) {
                val read = inputStream.read(chunk)
                if (read < 0) break
                if (read == 0) continue

                val data = chunk.copyOf(read)
                buffer.append(data)
                outputFlow.emit(data)
                onChunk()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 容器内探测 script 命令
     */
    private suspend fun hasScriptCommand(sandboxId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val probe = prootManager.execInteractive(
                sandboxId = sandboxId,
                command = listOf(
                    "sh",
                    "-lc",
                    "if command -v script >/dev/null 2>&1; then printf 1; else printf 0; fi"
                )
            )

            val output = try {
                probe.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                ""
            }

            try {
                probe.waitFor(3, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }

            try {
                if (probe.isAlive) probe.destroyForcibly() else probe.destroy()
            } catch (_: Exception) {
            }

            output.trim() == "1"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检查进程是否存活
     */
    private fun isProcessAlive(pid: Int): Boolean {
        return try {
            File("/proc/$pid").exists()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 从输出中提取 PID
     * 输出格式：Process started with PID: 12345
     */
    private fun extractPidFromOutput(output: String): Int? {
        return try {
            val pattern = Regex("PID:\\s*(\\d+)")
            val match = pattern.find(output)
            match?.groupValues?.get(1)?.toIntOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract PID from output: $output", e)
            null
        }
    }

    private fun tryGetPid(process: Process): Int? {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.get(process) as? Int
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 对 shell 参数做单引号安全转义
     */
    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
