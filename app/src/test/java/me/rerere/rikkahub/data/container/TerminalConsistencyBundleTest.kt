package me.rerere.rikkahub.data.container

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import me.rerere.rikkahub.utils.TerminalEmulator

class TerminalConsistencyBundleTest {
    @Test fun consistencySyncOutputCompletionWakesWithoutPollingAndTimeoutRemainsBounded() = runBlocking {
        val notices = TerminalOutputNotifications()
        notices.published(5, 1, 1, synchronizedOutput = true)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { awaitTerminalSynchronizedOutput(notices.flow, 1000) }
        assertFalse(waiting.isCompleted)
        notices.published(3, 2, 2, synchronizedOutput = false)
        waiting.await()
        assertEquals(8L, notices.flow.value.totalBytes)
        notices.published(1, 3, 3, synchronizedOutput = true)
        awaitTerminalSynchronizedOutput(notices.flow, 1)
        assertTrue(notices.flow.value.synchronizedOutput)
    }

    @Test fun consistencyAbandonedCursorPublicationCannotMutateVisibleSnapshot() {
        val terminal = TerminalEmulator(40, 8).apply { feed("abc\u001b[5 q\u001b[H") }
        val initial = terminal.renderFrameForCursorOverlay()
        val store = me.rerere.rikkahub.ui.pages.container.TerminalRowStore(initial)
        val cursor = store.cursors(initial).getValue(initial.screenLineIds.first())
        assertEquals(0, cursor.value!!.start)
        terminal.feed("\u001b[1;3H")
        val next = terminal.renderFrameForCursorOverlay()
        val abandoned = androidx.compose.runtime.snapshots.Snapshot.takeMutableSnapshot()
        try {
            abandoned.enter { store.publish(next, false, true, false, 100); assertEquals(2, cursor.value!!.start) }
        } finally { abandoned.dispose() }
        assertSame(cursor, store.cursors(initial).getValue(initial.screenLineIds.first()))
        assertEquals(0, cursor.value!!.start)
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { store.publish(next, false, true, false, 101) }
        assertEquals(2, cursor.value!!.start)
        assertEquals(8, store.cursors(next).size)
        val normal = terminal.renderFrame()
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { store.publish(normal, false, true, false, 102) }
        assertTrue(store.cursors(normal).isEmpty())
    }

    @Test fun consistencyGraphemeEditsKeepCombiningZwjFlagsAndReplacementWhole() {
        for (cluster in listOf("e\u0301", "👍🏽", "👩‍💻", "🇨🇳", "1️⃣")) {
            assertEquals(listOf(cluster), terminalGraphemes(cluster))
            assertEquals(TerminalTextEdit(0, cluster, 1), terminalTextEdit("a", "a$cluster"))
            assertEquals(TerminalTextEdit(1, "", 0), terminalTextEdit("a$cluster", "a"))
        }
        assertEquals(TerminalTextEdit(3, "新词", 2), terminalTextEdit("拼音词", "新词"))
        assertEquals(listOf("🇨🇳", "🇺🇸"), terminalGraphemes("🇨🇳🇺🇸"))
    }

    @Test fun consistencyOneQueuePreservesMixedEventsAndOwnsRawArrays() = runBlocking {
        val queue = TerminalUiInputQueue()
        val bytes = byteArrayOf(0, 0xFF.toByte())
        assertTrue(queue.offer(TerminalUiInput.Text("a")))
        assertTrue(queue.offer(TerminalUiInput.Bytes(bytes)))
        assertTrue(queue.offer(TerminalUiInput.Control(ControlInput.ENTER)))
        assertTrue(queue.offer(TerminalUiInput.Text("\u007Fe\u0301")))
        bytes.fill(4)
        queue.close()
        assertFalse(queue.offer(TerminalUiInput.Text("late")))
        val received = ArrayList<TerminalUiInput>()
        queue.consume { received.add(it) }
        assertEquals(4, received.size)
        assertEquals(TerminalUiInput.Text("a"), received[0])
        assertArrayEquals(byteArrayOf(0, 0xFF.toByte()), (received[1] as TerminalUiInput.Bytes).value)
        assertEquals(TerminalUiInput.Control(ControlInput.ENTER), received[2])
        assertEquals(TerminalUiInput.Text("\u007Fe\u0301"), received[3])
    }

    @Test fun consistencyResizeFailureAndSupersededRequestsCannotCommit() = runBlocking {
        val resize = TerminalResizeCoordinator(80, 24, Mutex())
        var commits = 0
        val old = resize.request(90, 30)
        val latest = resize.request(100, 40)
        assertTrue(resize.apply(old, { error("stale backend") }, { commits++ }).isFailure)
        assertTrue(resize.apply(latest, { false }, { commits++ }).isFailure)
        assertEquals(TerminalSizeReceipt(0, 80, 24), resize.applied)
        assertEquals(0, commits)
        val retry = resize.request(100, 40)
        assertEquals(retry, resize.apply(retry, { true }, { commits++ }).getOrThrow())
        assertEquals(1, commits)
        val same = resize.request(100, 40)
        assertEquals(same, resize.apply(same, { error("same size backend") }, { error("same size model") }).getOrThrow())
    }

    @Test fun consistencyResizeExcludesParserAndFinishesAckEvenAfterCancellation() = runBlocking {
        val lock = Mutex()
        val resize = TerminalResizeCoordinator(80, 24, lock)
        val backendEntered = CompletableDeferred<Unit>()
        val releaseBackend = CompletableDeferred<Unit>()
        val order = ArrayList<String>()
        val request = resize.request(90, 30)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            resize.apply(request, {
                order.add("kernel")
                backendEntered.complete(Unit)
                releaseBackend.await()
                true
            }, { order.add("model") }, { order.add("ack"); assertEquals(it, resize.applied) })
        }
        backendEntered.await()
        val parse = async(start = CoroutineStart.UNDISPATCHED) { lock.withLock { order.add("parse") } }
        assertFalse(parse.isCompleted)
        job.cancel()
        releaseBackend.complete(Unit)
        job.join()
        parse.await()
        assertEquals(listOf("kernel", "model", "ack", "parse"), order)
        assertEquals(request, resize.applied)
    }

    @Test fun consistencyReadSnapshotMapsWideAndCombinedGlyphsWithoutSplittingSurrogates() {
        val terminal = TerminalEmulator(40, 8)
        terminal.feed("中e\u0301👩‍💻x\u001b[1;3H")
        val value = renderLiveTerminalSnapshot(terminal)
        assertEquals("e\u0301", value.cursorLineCharAtCursor)
        assertEquals("中", value.cursorLineCharBeforeCursor)
        assertEquals("👩‍💻", value.cursorLineCharAfterCursor)
        assertEquals("中", value.cursorLineTextBeforeCursor)
        assertEquals(1, value.cursorTextColumn)
        assertEquals(6, value.cursorLineLastNonBlankColumn)
        terminal.feed("\u001b[1;2H")
        assertEquals("中", renderLiveTerminalSnapshot(terminal).cursorLineCharAtCursor)
        terminal.feed("\u001b[1;6H")
        assertEquals("x", renderLiveTerminalSnapshot(terminal).cursorLineCharAtCursor)
    }

    @Test fun consistencyReadSnapshotNeverConsumesProtocolOrClipboardQueues() {
        val terminal = TerminalEmulator(40, 8)
        terminal.feed("\u001b[2;3H\u001b[6n\u001b]52;c;aGVsbG8=\u0007")
        val first = renderLiveTerminalSnapshot(terminal)
        val second = renderLiveTerminalSnapshot(terminal)
        assertEquals(first, second)
        assertEquals(first.responses, terminal.drainResponses())
        assertEquals(first.clipboardRequests, terminal.drainClipboardRequests())
    }

    @Test fun consistencyAtomicSnapshotDoesNotMixResizeTextCursorAndModes() {
        val terminal = TerminalEmulator(40, 8)
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                repeat(1000) { step ->
                    synchronized(terminal) {
                        val cols = if (step % 2 == 0) 40 else 60
                        val rows = if (step % 2 == 0) 8 else 12
                        terminal.resize(cols, rows)
                        terminal.feed("\u001b[2J\u001b[1;1Hsize:$cols:$rows\u001b[$rows;${cols}H")
                    }
                }
            } catch (error: Throwable) { failure.set(error) }
        }
        terminal.feed("size:40:8\u001b[8;40H")
        writer.start()
        repeat(1000) {
            val snap = renderLiveTerminalSnapshot(terminal)
            assertEquals("size:${snap.columns}:${snap.rows}", snap.screenLines.first())
            assertEquals(snap.rows, snap.cursorRow)
            assertEquals(snap.columns, snap.cursorColumn)
            assertEquals(snap.rows, snap.screenLines.size)
        }
        writer.join(5000)
        assertFalse(writer.isAlive)
        failure.get()?.let { throw AssertionError(it) }
    }
}
