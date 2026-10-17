package me.rerere.rikkahub.data.container

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Byte parsing/response delivery is separate. Only this scalar display notification may be conflated. */
internal class TerminalOutputNotifications {
    data class Update(val sequence: Long, val totalBytes: Long, val revision: Long, val timeNanos: Long,
        val synchronizedOutput: Boolean = false)
    private val updates = MutableStateFlow(Update(0, 0, -1, 0))
    val flow = updates.asStateFlow()

    @Synchronized fun published(bytes: Int, revision: Long, timeNanos: Long, synchronizedOutput: Boolean = false) {
        require(bytes >= 0)
        val previous = updates.value
        updates.value = Update(Math.addExact(previous.sequence, 1),
            Math.addExact(previous.totalBytes, bytes.toLong()), maxOf(previous.revision, revision),
            if (bytes > 0) maxOf(previous.timeNanos, timeNanos) else previous.timeNanos, synchronizedOutput)
    }
}

/** A StateFlow read closes the check/subscribe race; the existing timeout remains a safety bound. */
internal suspend fun awaitTerminalSynchronizedOutput(
    updates: StateFlow<TerminalOutputNotifications.Update>, maximumWaitMs: Long,
): Boolean {
    if (!updates.value.synchronizedOutput) return true
    return withTimeoutOrNull(maximumWaitMs) { updates.first { !it.synchronizedOutput }; true } == true
}
