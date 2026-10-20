package me.rerere.rikkahub.data.container

import java.io.File
import me.rerere.rikkahub.ui.pages.container.terminalHistoryChunks
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test

class TerminalTuiHistoryTest {
    private val modes = listOf(TerminalRenderMode.TUI_CHUNKED, TerminalRenderMode.TUI_CHUNKED_LAYERS)
    private fun terminal() = TerminalEmulator(80, 24, 1000).apply {
        feed((0 until 1023).joinToString("\r\n") { "history-$it 中文" } + "\r\n")
    }

    @Test fun tuiHistoryOnlyExplicitNewModesCanEnableGrouping() {
        val frame = terminal().renderFrame()
        for (mode in TerminalRenderMode.entries) {
            assertEquals(mode in modes, terminalTuiHistoryAllowed(mode, frame, true, false, true, false))
            assertFalse(terminalTuiHistoryAllowed(mode, frame, false, false, true, false))
        }
        assertEquals(TerminalRenderMode.CHUNKED_LAYERS, TerminalRenderMode.DEFAULT)
        assertTrue(terminalHistoryChunks(frame.historyCount, frame.historyStartSequence, true).isEmpty())
        assertEquals("tuiChunkedEager", modes[0].id)
        assertEquals("tuiChunkedLayers", modes[1].id)
        assertTrue(modes.all { !it.isVirtualHistory })
    }

    @Test fun tuiHistoryPreservesMouseSelectionFullGridAndAlternateFallbacks() {
        val terminal = terminal()
        for (mode in modes) {
            val frame = terminal.renderFrame()
            assertFalse(terminalTuiHistoryAllowed(mode, frame, true, true, true, false))
            assertFalse(terminalTuiHistoryAllowed(mode, frame, true, false, false, false))
            assertFalse(terminalTuiHistoryAllowed(mode, frame, true, false, true, true))
            terminal.feed("\u001b[?1000h")
            assertFalse(terminalTuiHistoryAllowed(mode, terminal.renderFrame(), true, false, true, false))
            terminal.feed("\u001b[?1000l\u001b[?1049h")
            assertFalse(terminalTuiHistoryAllowed(mode, terminal.renderFrame(), true, false, true, false))
            terminal.feed("\u001b[?1049l")
            assertTrue(terminalTuiHistoryAllowed(mode, terminal.renderFrame(), true, false, true, false))
        }
    }

    @Test fun tuiHistoryRejectsCopiedForgedAndEmptyHistory() {
        val frame = terminal().renderFrame()
        val forged = listOf(frame.copy(rows = frame.rows.map { it }),
            frame.copy(historyLineIds = frame.historyLineIds.map { it }),
            frame.copy(historyStartSequence = -1), frame.copy(historyGeneration = frame.historyGeneration + 1),
            frame.copy(screenStartRow = 0), TerminalEmulator(80, 24).renderFrame())
        for (mode in modes) for (value in forged) {
            assertFalse(terminalTuiHistoryAllowed(mode, value, true, false, true, false))
        }
    }

    @Test fun tuiHistoryPerCommandPersistenceAndEffectiveFallbackAreIndependent() {
        var raw = updatedTerminalRenderPreferences("", "pi --resume", modes[0])
        raw = updatedTerminalRenderPreferences(raw, "omp", modes[1])
        assertEquals(modes[0], terminalRendererForCommand(raw, "pi --resume"))
        assertEquals(modes[1], terminalRendererForCommand(raw, "omp"))
        assertEquals(TerminalRenderMode.DEFAULT, terminalRendererForCommand(raw, "sh"))
        for (mode in modes) {
            assertEquals(mode, TerminalRenderMode.fromId(mode.id))
            assertEquals(mode, effectiveTerminalRenderMode(mode, true, tuiHistoryAllowed = true))
            assertEquals(TerminalRenderMode.DEFAULT, effectiveTerminalRenderMode(mode, true))
            assertEquals(TerminalRenderMode.FLAT, effectiveTerminalRenderMode(mode, false, tuiHistoryAllowed = true))
        }
    }

    @Test fun tuiHistoryGroupingFollowsOwnedFifoWithoutChangingScreenModeOrText() {
        val terminal = terminal()
        var before = terminal.renderFrame()
        repeat(12) {
            terminal.feed("live-$it 中文\r\n")
            val frame = terminal.renderFrame()
            assertTrue(terminalTuiHistoryAllowed(modes[0], frame, true, false, true, false))
            val plan = terminalHistoryChunks(frame.historyCount, frame.historyStartSequence, false)
            assertEquals(frame.historyCount, plan.sumOf { it.endExclusive - it.start })
            assertTrue(plan.all { it.endExclusive - it.start in 1..128 })
            assertEquals(before.historyLineIds.drop(1), frame.historyLineIds.dropLast(1))
            assertEquals(before.rows.subList(1, before.historyCount).map { it.text }, frame.rows.take(frame.historyCount-1).map { it.text })
            assertTrue(frame.cursorVisible)
            before = frame
        }
    }

    @Test fun tuiHistoryWiringKeepsScreenMetricsRawStoreAndOneScrollWriter() {
        fun source(name: String) = listOf(File("app/src/main/java/me/rerere/rikkahub/$name"),
            File("src/main/java/me/rerere/rikkahub/$name")).first { it.isFile }.readText()
        val page = source("ui/pages/container/ProcessSessionPage.kt")
        assertTrue(page.contains("usesTuiViewport = currentUsesTuiViewport && !tuiHistory"))
        assertTrue(page.contains("usesTuiViewport = currentUsesTuiViewport,"))
        assertTrue(page.contains("val commandIsTui = commandNeedsTuiCompatibility && !regularHistory"))
        assertFalse(page.contains("commandNeedsTuiCompatibility && !tuiHistory"))
        assertTrue(page.contains("val wantedSparse = appliedTerminalRenderMode.isVirtualHistory && !currentUsesTuiViewport"))
        assertTrue(page.contains("terminalRowStore.publish(frame, sparse, usesTuiViewport"))
        assertEquals(3, page.split("tuiHistoryAllowed = tuiHistory").size - 1)
        val rows = source("ui/pages/container/TerminalRenderedRows.kt")
        assertTrue(rows.contains("if (usesTuiViewport || binding.pendingBlanks"))
        assertTrue(rows.contains("key(chunk.bucket)"))
        assertTrue(rows.contains("key(\"terminal-active-screen\")"))
        assertFalse(rows.contains("TUI_CHUNKED")) // Reuse renderer, do not create a fork.
        val binding = source("ui/pages/container/TerminalViewportBinding.kt")
        assertTrue(binding.contains("val measuredEager = !virtual"))
        assertEquals(1, binding.split("LaunchedEffect(binding) { binding.runEffects() }").size - 1)
    }
}
