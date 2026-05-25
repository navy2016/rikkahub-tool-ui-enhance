package me.rerere.ai.provider.providers

import android.util.Log
import kotlinx.coroutines.channels.ProducerScope

internal fun <T> ProducerScope<T>.trySendStreamChunk(
    value: T,
    tag: String,
    label: String = "stream chunk",
) {
    val result = trySend(value)
    if (result.isSuccess) return

    val error = result.exceptionOrNull()
    if (result.isClosed) {
        Log.d(tag, "Dropped $label because stream channel is already closed", error)
        return
    }

    val failure = error ?: IllegalStateException("Failed to enqueue $label")
    Log.e(tag, "Failed to enqueue $label", failure)
    close(failure)
}
