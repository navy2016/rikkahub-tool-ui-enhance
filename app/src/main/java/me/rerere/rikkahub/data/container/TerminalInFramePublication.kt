package me.rerere.rikkahub.data.container

import java.util.concurrent.atomic.AtomicBoolean

/** A conservative decision only. No waiting, state mutation or model reads are hidden here. */
internal fun terminalInFramePublicationAllowed(
    fullVirtual: Boolean,
    hasNotificationOwner: Boolean,
    active: Boolean,
    attached: Boolean,
    onMainThread: Boolean,
    selectionActive: Boolean,
    resizePending: Boolean,
    awaitingResizeRedraw: Boolean,
    forcedBlankPending: Boolean,
    synchronizedOutput: Boolean,
    snapshotWritable: Boolean,
): Boolean = fullVirtual && hasNotificationOwner && active && attached && onMainThread &&
    !selectionActive && !resizePending && !awaitingResizeRedraw && !forcedBlankPending && !synchronizedOutput && snapshotWritable

/**
 * Clear only BEFORE capture: accepted bytes are already in the emulator. A notification posted
 * during/after capture must remain pending for the next cycle. A refused capture restores work.
 * `publish` must retain the emulator-lock synchronized-output recheck and the atomic row/frame commit.
 */
internal inline fun tryTerminalInFramePublication(
    allowed: Boolean,
    pending: AtomicBoolean,
    publish: () -> Boolean,
): Boolean {
    if (!allowed) return false
    pending.set(false)
    val completed = publish()
    if (!completed) pending.set(true)
    return completed
}
