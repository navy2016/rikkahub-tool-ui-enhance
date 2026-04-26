package me.rerere.rikkahub.utils

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import me.rerere.rikkahub.sandbox.SandboxEngine

/**
 * 系统监控工具类
 *
 * 提供 CPU 使用率采样，基于 /proc/stat 差值计算。
 */
@Singleton
class SystemMonitor @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val PROC_STAT = "/proc/stat"
    }

    private var lastCpuTotal: Long = 0
    private var lastCpuIdle: Long = 0
    private var lastProcessCpuMs: Long = 0
    private var lastProcessWallMs: Long = 0

    /**
     * 获取 CPU 使用率（0f ~ 100f）。系统 /proc/stat 不可读时回退到当前进程 CPU 采样。
     */
    suspend fun getCpuUsagePercent(): Float = withContext(Dispatchers.IO) {
        readCpuUsagePercent() ?: readProcessCpuUsagePercent() ?: 0f
    }

    suspend fun getSandboxUsagePercent(sandboxId: String): Float = withContext(Dispatchers.IO) {
        runCatching {
            SandboxEngine.getSandboxUsage(context, sandboxId).usagePercent.toFloat().coerceIn(0f, 100f)
        }.getOrDefault(0f)
    }

    private suspend fun readCpuUsagePercent(): Float? {
        return try {
            val statFile = File(PROC_STAT)
            if (!statFile.exists() || !statFile.canRead()) return null

            val line = statFile.bufferedReader().useLines { lines ->
                lines.firstOrNull { it.startsWith("cpu ") }
            } ?: return null

            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) return null

            val user = parts[1].toLongOrNull() ?: 0
            val nice = parts[2].toLongOrNull() ?: 0
            val system = parts[3].toLongOrNull() ?: 0
            val idle = parts[4].toLongOrNull() ?: 0
            val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0
            val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0
            val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0
            val steal = parts.getOrNull(8)?.toLongOrNull() ?: 0

            val total = user + nice + system + idle + iowait + irq + softirq + steal
            val idleTotal = idle + iowait

            if (lastCpuTotal == 0L) {
                lastCpuTotal = total
                lastCpuIdle = idleTotal
                delay(120)
                return readCpuUsagePercent()
            }

            val totalDelta = total - lastCpuTotal
            val idleDelta = idleTotal - lastCpuIdle

            lastCpuTotal = total
            lastCpuIdle = idleTotal

            if (totalDelta <= 0) return 0f

            (((totalDelta - idleDelta).toFloat() / totalDelta) * 100f).coerceIn(0f, 100f)
        } catch (_: Exception) {
            null
        }
    }

    private fun readProcessCpuUsagePercent(): Float? {
        return try {
            val cpuMs = android.os.Process.getElapsedCpuTime()
            val wallMs = SystemClock.elapsedRealtime()
            if (lastProcessCpuMs == 0L || lastProcessWallMs == 0L) {
                lastProcessCpuMs = cpuMs
                lastProcessWallMs = wallMs
                return 0f
            }
            val cpuDelta = cpuMs - lastProcessCpuMs
            val wallDelta = wallMs - lastProcessWallMs
            lastProcessCpuMs = cpuMs
            lastProcessWallMs = wallMs
            if (wallDelta <= 0) return 0f
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            ((cpuDelta.toFloat() / (wallDelta * cores)) * 100f).coerceIn(0f, 100f)
        } catch (_: Exception) {
            null
        }
    }

    fun cpuUsageFlow(intervalMs: Long = 1000L): Flow<Float> = flow {
        while (true) {
            emit(getCpuUsagePercent())
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)
}
