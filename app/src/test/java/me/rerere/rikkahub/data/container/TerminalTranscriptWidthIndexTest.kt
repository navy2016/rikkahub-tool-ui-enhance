package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class TerminalTranscriptWidthIndexTest {
    private fun terminal(size: Int = 100) = TerminalEmulator(80, 6, size).apply {
        feed("\u001B[?25l" + (0 until size + 6).joinToString("\r\n") {
            if (it == 0) "W".repeat(70) else "row:$it"
        })
    }
    private fun measure(text: AnnotatedString): Int = text.length * 7 + text.spanStyles.size
    private fun expected(frame: TerminalEmulator.RenderFrame) = frame.rows.maxOf { measure(it.text) }

    @Test fun ownedActivityUpdatesDoNotMeasureHistoryAtOneFiveAndTenThousandRows() {
        for (size in listOf(1000, 5000, 10000)) {
            val terminal = terminal(size)
            val index = TerminalTranscriptWidthIndex()
            val first = terminal.renderFrame()
            assertEquals(expected(first), index.width(first, "metrics", ::measure))
            assertEquals(size, index.lastMeasuredHistoryRows)
            terminal.feed("\r\u001B[2Kchanged")
            val next = terminal.renderFrame()
            assertEquals(expected(next), index.width(next, "metrics", ::measure))
            assertEquals(0, index.lastMeasuredHistoryRows)
            assertEquals(1, index.lastMeasuredScreenRows)
            assertEquals(5, index.lastReusedScreenRows)
            assertEquals(6, index.retainedScreenRows)
        }
    }

    @Test fun trimsRemoveOffscreenMaximumAndMeasureOnlyNewArchivedRow() {
        val terminal = terminal()
        val index = TerminalTranscriptWidthIndex()
        val wide = index.width(terminal.renderFrame(), 1, ::measure)
        repeat(30) {
            terminal.feed("\r\nappend-$it")
            val frame = terminal.renderFrame()
            assertEquals(expected(frame), index.width(frame, 1, ::measure))
            assertTrue(index.width(frame, 1, ::measure) < wide)
            assertTrue(index.retainedCandidates <= frame.historyCount)
        }
        terminal.feed("\r\nlast")
        index.width(terminal.renderFrame(), 1, ::measure)
        assertEquals(1, index.lastMeasuredHistoryRows)
    }

    @Test fun metricsOwnerAndReplacedRowsRevokeFifoProof() {
        val frame = terminal().renderFrame()
        val index = TerminalTranscriptWidthIndex()
        index.width(frame, 1, ::measure)
        index.width(frame, 2, ::measure)
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
        val synthetic = frame.copy(rows = frame.rows.map { it.copy(text = AnnotatedString("longer " + it.text)) })
        assertEquals(expected(synthetic), index.width(synthetic, 2, ::measure))
        assertEquals(synthetic.historyCount, index.lastMeasuredHistoryRows)
        val another = terminal().renderFrame()
        assertEquals(expected(another), index.width(another, 2, ::measure))
        assertEquals(another.historyCount, index.lastMeasuredHistoryRows)
    }

    @Test fun failedMeasurementDoesNotLeavePartiallyValidMaximum() {
        val frame = terminal().renderFrame()
        val index = TerminalTranscriptWidthIndex()
        assertThrows(IllegalStateException::class.java) { index.width(frame, 1) { error("font failure") } }
        assertEquals(expected(frame), index.width(frame, 1, ::measure))
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
    }

    @Test fun dormantScreenUpdatesAtAllSizesKeepWidthsWithoutMeasuringText() {
        for (size in listOf(1000, 5000, 10000)) {
            val terminal = terminal(size)
            val index = TerminalTranscriptWidthIndex()
            index.width(terminal.renderFrame(), 1, ::measure)
            repeat(8) {
                terminal.feed("\r\u001B[2Khidden-$it")
                index.retainFor(terminal.renderFrame(), 1)
                assertEquals(0, index.retainedScreenRows)
            }
            assertEquals(size.toLong(), index.measuredHistoryRows)
            assertEquals(6L, index.measuredScreenRows)
            assertTrue(index.hasRetainedState)
            val next = terminal.renderFrame()
            assertEquals(expected(next), index.width(next, 1, ::measure))
            assertEquals(0, index.lastMeasuredHistoryRows)
            assertEquals(6, index.lastMeasuredScreenRows)
        }
    }

    @Test fun dormantArchivesMeasureEveryMissingSurvivorAndRemoveTheOffscreenMaximum() {
        for (size in listOf(129, 1000)) for (added in listOf(3, 128, size + 5)) {
            val terminal = terminal(size)
            val index = TerminalTranscriptWidthIndex()
            val wide = index.width(terminal.renderFrame(), 1, ::measure)
            repeat(added) {
                terminal.feed("\r\nhidden-$it")
                index.retainFor(terminal.renderFrame(), 1)
                assertTrue(index.retainedCandidates <= size)
            }
            assertEquals(size.toLong(), index.measuredHistoryRows)
            assertEquals(6L, index.measuredScreenRows)
            val frame = terminal.renderFrame()
            assertEquals(expected(frame), index.width(frame, 1, ::measure))
            assertEquals(minOf(added, size), index.lastMeasuredHistoryRows)
            assertTrue(index.width(frame, 1, ::measure) < wide)
        }
    }

    @Test fun dormantHeadTrimNeedsNoNewMeasurementAndCannotProveARetiredFrame() {
        val terminal = terminal(260)
        val original = terminal.renderFrame()
        val index = TerminalTranscriptWidthIndex()
        val wide = index.width(original, 1, ::measure)
        terminal.setMaxScrollbackLines(128)
        val trimmed = terminal.renderFrame()
        index.retainFor(trimmed, 1)
        assertTrue(index.retainedCandidates <= 128)
        assertEquals(expected(trimmed), index.width(trimmed, 1, ::measure))
        assertEquals(0, index.lastMeasuredHistoryRows)
        // The previously widest row was removed. Older/speculative frames must revoke the cache.
        assertEquals(wide, index.width(original, 1, ::measure))
        assertEquals(260, index.lastMeasuredHistoryRows)
    }

    @Test fun dormantInvalidationsReleaseRetainedStateBeforeAnyRetry() {
        val edits: List<(TerminalEmulator) -> TerminalEmulator.RenderFrame> = listOf(
            { it.feed("\u001B[?5h"); it.renderFrame() },
            { it.feed("\u001B]4;2;rgb:ffff/0000/0000\u0007"); it.renderFrame() },
            { it.resize(40, 6); it.renderFrame() },
            { it.clearScrollbackOnly(); it.feed("\r\nnew"); it.renderFrame() },
            { it.renderFrame(includeScrollback = false) },
            { it.feed("\u001B[?1049hALT"); it.renderFrame() },
            { terminal().renderFrame() },
            { it.renderFrame().let { frame -> frame.copy(rows = frame.rows.toList()) } },
        )
        for (edit in edits) {
            val terminal = terminal()
            val index = TerminalTranscriptWidthIndex()
            index.width(terminal.renderFrame(), 1, ::measure)
            val frame = edit(terminal)
            index.retainFor(frame, 1)
            assertFalse(index.hasRetainedState)
            assertEquals(0, index.retainedCandidates)
            assertEquals(100L, index.measuredHistoryRows)
            assertEquals(expected(frame), index.width(frame, 1, ::measure))
            assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
        }
        val frame = terminal().renderFrame()
        val index = TerminalTranscriptWidthIndex()
        index.width(frame, 1, ::measure)
        index.retainFor(frame, 2)
        assertFalse(index.hasRetainedState)
        assertEquals(expected(frame), index.width(frame, 2, ::measure))
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
    }

    @Test fun disposalAndPartialMeasurementFailureRevokeAllCacheOwnership() {
        val terminal = terminal()
        val index = TerminalTranscriptWidthIndex()
        val frame = terminal.renderFrame()
        index.width(frame, 1, ::measure)
        index.clear()
        assertFalse(index.hasRetainedState)
        assertEquals(0, index.retainedCandidates)
        assertEquals(0, index.retainedScreenRows)
        assertEquals(expected(frame), index.width(frame, 1, ::measure))
        assertEquals(100, index.lastMeasuredHistoryRows)
        terminal.feed("\r\na\r\nb\r\nc")
        val next = terminal.renderFrame()
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            index.width(next, 1) { if (++calls == 2) error("font failure") else measure(it) }
        }
        index.retainFor(next, 1)
        assertFalse(index.hasRetainedState)
        assertEquals(expected(next), index.width(next, 1, ::measure))
        assertEquals(100, index.lastMeasuredHistoryRows)
    }

    @Test fun equalRebuiltScreenTextNeedsNoMeasurementAndRetentionStaysBounded() {
        for (screenRows in listOf(6, 24, 80)) {
            val terminal = TerminalEmulator(80, screenRows, 128).apply {
                feed("\u001B[?25l" + (0 until 128 + screenRows).joinToString("\r\n") { "row-$it" })
            }
            val index = TerminalTranscriptWidthIndex()
            val original = terminal.renderFrame()
            val width = index.width(original, 1, ::measure)
            repeat(4) {
                val rebuilt = terminal.renderFrame()
                assertNotSame(original.rows.last().text, rebuilt.rows.last().text)
                assertEquals(width, index.width(rebuilt, 1) { error("Equal annotated screen text was remeasured") })
                assertEquals(0, index.lastMeasuredScreenRows)
                assertEquals(screenRows, index.lastReusedScreenRows)
                assertEquals(screenRows, index.retainedScreenRows)
            }
            repeat(20) {
                terminal.feed("\r\u001B[2Kactive-$it")
                val next = terminal.renderFrame()
                assertEquals(expected(next), index.width(next, 1, ::measure))
                assertEquals(1, index.lastMeasuredScreenRows)
                assertEquals(screenRows - 1, index.lastReusedScreenRows)
                assertEquals(screenRows, index.retainedScreenRows)
            }
            assertEquals(128L, index.measuredHistoryRows)
            assertEquals(screenRows.toLong() + 20, index.measuredScreenRows)
        }
    }

    @Test fun scrollingAndScreenLineEditsReuseStableIdsInsteadOfOldPositions() {
        val terminal = terminal()
        val index = TerminalTranscriptWidthIndex()
        index.width(terminal.renderFrame(), 1, ::measure)
        repeat(30) {
            terminal.feed("\r\nappend-$it")
            val next = terminal.renderFrame()
            assertEquals(expected(next), index.width(next, 1, ::measure))
            assertEquals(1, index.lastMeasuredHistoryRows)
            assertEquals(1, index.lastMeasuredScreenRows)
            assertEquals(5, index.lastReusedScreenRows)
            assertEquals(6, index.retainedScreenRows)
        }
        for (command in listOf("\u001B[2;1H\u001B[L", "\u001B[3;1H\u001B[M")) {
            terminal.feed(command)
            val next = terminal.renderFrame()
            assertEquals(expected(next), index.width(next, 1, ::measure))
            assertEquals(1, index.lastMeasuredScreenRows)
            assertEquals(5, index.lastReusedScreenRows)
            assertEquals(6, index.retainedScreenRows)
        }
    }

    @Test fun shorteningTheWidestScreenRowReducesTheMaximum() {
        val terminal = TerminalEmulator(80, 6, 100).apply {
            feed("\u001B[?25l" + (0..5).joinToString("\r\n") { if (it == 0) "W".repeat(70) else "short-$it" })
        }
        val index = TerminalTranscriptWidthIndex()
        val original = index.width(terminal.renderFrame(), 1, ::measure)
        terminal.feed("\u001B[H\u001B[2Ktiny")
        val next = terminal.renderFrame()
        val shorter = index.width(next, 1, ::measure)
        assertEquals(expected(next), shorter)
        assertTrue(shorter < original)
        assertEquals(1, index.lastMeasuredScreenRows)
        assertEquals(5, index.lastReusedScreenRows)
        assertEquals(6, index.retainedScreenRows)
        index.retainFor(next, 1) // Empty history has no reason to retain fonts/owner/screen text.
        assertFalse(index.hasRetainedState)
        assertEquals(0, index.retainedScreenRows)
    }

    @Test fun identicalCharactersWithDifferentAnsiOrUrlAnnotationsAreNotCacheHits() {
        val terminal = terminal()
        val index = TerminalTranscriptWidthIndex()
        var frame = terminal.renderFrame()
        val plain = frame.rows.last().text.text
        index.width(frame, 1, ::measure)
        for (styled in listOf("\u001B[1m$plain\u001B[0m", "\u001B[3m$plain\u001B[0m",
            "\u001B]8;;https://example.invalid/new\u001B\\$plain\u001B]8;;\u001B\\")) {
            terminal.feed("\r\u001B[2K$styled")
            val next = terminal.renderFrame()
            assertEquals(plain, next.rows.last().text.text)
            assertNotEquals(frame.rows.last().text, next.rows.last().text)
            assertEquals(expected(next), index.width(next, 1, ::measure))
            assertEquals(1, index.lastMeasuredScreenRows)
            assertEquals(5, index.lastReusedScreenRows)
            frame = next
        }
    }

    @Test fun syntheticRowsIdsGenerationAndDifferentOwnerCannotReuseScreenWidths() {
        val frame = terminal().renderFrame()
        val replacements = listOf(
            frame.copy(rows = frame.rows.toList()),
            frame.copy(screenLineIds = frame.screenLineIds.map { it }),
            frame.copy(screenGeneration = frame.screenGeneration + 1),
            frame.copy(historyGeneration = frame.historyGeneration + 1),
            terminal().renderFrame(),
        )
        for (replacement in replacements) {
            val index = TerminalTranscriptWidthIndex()
            index.width(frame, 1, ::measure)
            assertEquals(expected(replacement), index.width(replacement, 1, ::measure))
            assertEquals(6, index.lastMeasuredScreenRows)
            assertEquals(0, index.lastReusedScreenRows)
            if (replacement !== replacements.last()) assertEquals(0, index.retainedScreenRows)
        }
        val index = TerminalTranscriptWidthIndex()
        index.width(frame, 1, ::measure)
        assertEquals(expected(frame) * 2, index.width(frame, 2) { measure(it) * 2 })
        assertEquals(6, index.lastMeasuredScreenRows)
        assertEquals(0, index.lastReusedScreenRows)
    }

    @Test fun failureAfterScreenCacheHitsDoesNotPublishAPartialReplacement() {
        val terminal = terminal()
        val index = TerminalTranscriptWidthIndex()
        index.width(terminal.renderFrame(), 1, ::measure)
        terminal.feed("\u001B[5;1H\u001B[2Knew-five\u001B[6;1H\u001B[2Knew-six")
        val next = terminal.renderFrame()
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            index.width(next, 1) { if (++calls == 2) error("screen font failure") else measure(it) }
        }
        assertEquals(4, index.lastReusedScreenRows)
        assertEquals(0, index.retainedScreenRows)
        assertEquals(expected(next), index.width(next, 1, ::measure))
        assertEquals(100, index.lastMeasuredHistoryRows)
        assertEquals(6, index.lastMeasuredScreenRows)
        assertEquals(0, index.lastReusedScreenRows)
        terminal.feed("\r\u001B[2Knegative")
        assertThrows(IllegalArgumentException::class.java) { index.width(terminal.renderFrame(), 1) { -1 } }
        assertEquals(0, index.retainedScreenRows)
        index.clear()
        assertFalse(index.hasRetainedState)
    }

    @Test fun randomizedScreenEditsCursorAndResizeMatchTheFullOracle() {
        val terminal = terminal(129)
        val index = TerminalTranscriptWidthIndex()
        val random = Random(7331)
        repeat(160) { update ->
            when (random.nextInt(7)) {
                0 -> terminal.feed("\r\u001B[2K" + "w".repeat(random.nextInt(0, 25)))
                1 -> terminal.feed("\r\nappend-$update")
                2 -> terminal.feed("\u001B[${random.nextInt(1, terminal.rows + 1)};1H\u001B[L")
                3 -> terminal.feed("\u001B[${random.nextInt(1, terminal.rows + 1)};1H\u001B[M")
                4 -> terminal.feed("\u001B[?25h\u001B[${random.nextInt(1, terminal.rows + 1)};1H")
                5 -> terminal.feed("\u001B[?25l\r\u001B[1mANSI-$update\u001B[0m")
                else -> terminal.resize(40 + random.nextInt(0, 3) * 20, random.nextInt(6, 15))
            }
            val next = terminal.renderFrame()
            val metric = update / 17
            assertEquals(expected(next), index.width(next, metric, ::measure))
            assertEquals(terminal.rows, index.lastMeasuredScreenRows + index.lastReusedScreenRows)
            assertEquals(terminal.rows, index.retainedScreenRows)
            assertTrue(index.retainedScreenRows <= TerminalEmulator.MAX_ROWS)
        }
    }

    @Test fun randomizedDormantFifoBurstsMatchAFullWidthOracle() {
        val random = Random(924)
        val terminal = terminal(129)
        val index = TerminalTranscriptWidthIndex()
        index.width(terminal.renderFrame(), 1, ::measure)
        repeat(100) {
            val added = random.nextInt(0, 180)
            val historyWork = index.measuredHistoryRows
            repeat(added) {
                terminal.feed("\r\n" + "W".repeat(random.nextInt(1, 79)))
                index.retainFor(terminal.renderFrame(), 1)
            }
            val next = terminal.renderFrame()
            assertEquals(historyWork, index.measuredHistoryRows)
            assertEquals(expected(next), index.width(next, 1, ::measure))
            assertEquals(minOf(added, 129), index.lastMeasuredHistoryRows)
        }
    }
}
