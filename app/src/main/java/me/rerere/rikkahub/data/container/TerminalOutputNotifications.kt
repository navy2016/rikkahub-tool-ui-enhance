package me.rerere.rikkahub.data.container

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Byte parsing/response delivery is separate. Only this scalar display notification may be conflated. */
internal class TerminalOutputNotifications {
    data class Update(val sequence: Long, val totalBytes: Long, val revision: Long, val timeNanos: Long)
    private val updates = MutableStateFlow(Update(0, 0, -1, 0))
    val flow = updates.asStateFlow()

    @Synchronized fun published(bytes: Int, revision: Long, timeNanos: Long) {
        require(bytes >= 0)
        val previous = updates.value
        updates.value = Update(Math.addExact(previous.sequence, 1),
            Math.addExact(previous.totalBytes, bytes.toLong()), maxOf(previous.revision, revision),
            if (bytes > 0) maxOf(previous.timeNanos, timeNanos) else previous.timeNanos)
    }
}
