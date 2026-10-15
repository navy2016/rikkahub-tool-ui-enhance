package me.rerere.rikkahub.data.container

/** One scalar checkpoint per session; a newer mount or session close revokes stale publishers. */
internal class TerminalWidthCheckpointStore {
    private var serial = 0L
    private var closed = false
    private var entry: TerminalTranscriptWidthIndex.Checkpoint? = null
    data class Lease internal constructor(val id: Long)

    @Synchronized fun open(): Lease? = if (closed) null else Lease(++serial)
    @Synchronized fun read(lease: Lease): TerminalTranscriptWidthIndex.Checkpoint? =
        entry.takeIf { !closed && lease.id == serial }
    @Synchronized fun peek(): TerminalTranscriptWidthIndex.Checkpoint? = entry.takeIf { !closed }
    @Synchronized fun save(lease: Lease, checkpoint: TerminalTranscriptWidthIndex.Checkpoint?) {
        if (!closed && lease.id == serial) entry = checkpoint
    }
    @Synchronized fun close() { closed = true; serial++; entry = null }
    @Synchronized fun invalidate() { serial++; entry = null }
    @Synchronized fun retainedRows(): Int = entry?.retainedRows ?: 0
}
