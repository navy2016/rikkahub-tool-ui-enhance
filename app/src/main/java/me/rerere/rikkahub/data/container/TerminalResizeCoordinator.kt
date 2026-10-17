package me.rerere.rikkahub.data.container

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class TerminalSizeReceipt(val request: Long, val columns: Int, val rows: Int)

/** Requests are numbered before IO dispatch. Only resize intents conflate; output bytes never do. */
internal class TerminalResizeCoordinator(columns: Int, rows: Int, private val outputMutex: Mutex) {
    private val latest = AtomicLong()
    @Volatile var applied = TerminalSizeReceipt(0, columns, rows)
        private set
    val pending: Boolean get() = latest.get() != applied.request

    fun request(columns: Int, rows: Int) = TerminalSizeReceipt(latest.incrementAndGet(),
        columns.coerceIn(20, 240), rows.coerceIn(6, 80))

    suspend fun apply(
        request: TerminalSizeReceipt,
        backend: suspend () -> Boolean,
        commit: () -> Unit,
        acknowledged: (TerminalSizeReceipt) -> Unit = {},
    ): Result<TerminalSizeReceipt> = outputMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (request.request != latest.get()) return@withLock Result.failure(
            IllegalStateException("Terminal resize request superseded"))
        // Once the kernel changes, cancellation cannot split model/receipt publication from it.
        withContext(NonCancellable) {
            if (applied.columns != request.columns || applied.rows != request.rows) {
                val accepted = try { backend() } catch (error: Exception) {
                    return@withContext Result.failure(error)
                }
                if (!accepted) return@withContext Result.failure(IllegalStateException("Failed to apply PTY resize"))
                commit()
            }
            applied = request
            acknowledged(request)
            Result.success(request)
        }
    }
}
