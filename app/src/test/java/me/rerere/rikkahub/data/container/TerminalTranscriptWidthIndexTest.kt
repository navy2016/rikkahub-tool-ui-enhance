package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private class Identity
    private fun metric(
        metadata: Any,
        family: Any? = Identity(),
        resolver: Any = Identity(),
        fonts: List<Any> = List(4) { Identity() },
    ) = TerminalTranscriptWidthMetricKey(metadata, family, resolver, fonts)

    @Test fun freshCrossMountMetricKeysReuseOnlyTheSameLiveFontIdentities() {
        val frame = terminal().renderFrame()
        val index = TerminalTranscriptWidthIndex()
        val family = Identity()
        val resolver = Identity()
        val fonts = List(4) { Identity() }
        index.width(frame, metric("style-14-ltr-density-1", family, resolver, fonts), ::measure)
        assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)

        // A remounted page creates a new key object. Equal scalar metadata plus identical live font
        // ownership is the only condition that may reuse the session's scalar candidates.
        index.width(frame, metric("style-14-ltr-density-1", family, resolver, fonts), ::measure)
        assertEquals(0, index.lastMeasuredHistoryRows)
        assertEquals(frame.rows.size - frame.historyCount, index.lastMeasuredScreenRows)

        for (changed in listOf(
            metric("style-21-ltr-density-1", family, resolver, fonts),
            metric("style-14-ltr-density-1", Identity(), resolver, fonts),
            metric("style-14-ltr-density-1", family, Identity(), fonts),
            metric("style-14-ltr-density-1", family, resolver, fonts.toMutableList().also { it[2] = Identity() }),
        )) {
            index.width(frame, changed, ::measure)
            assertEquals(frame.historyCount, index.lastMeasuredHistoryRows)
        }
    }

    @Test fun crossMountReuseMeasuresOnlyBackgroundArchivesAndCurrentScreen() {
        val terminal = terminal(1_000)
        val index = TerminalTranscriptWidthIndex()
        val family = Identity()
        val resolver = Identity()
        val fonts = List(4) { Identity() }
        val firstKey = metric("same-width-style", family, resolver, fonts)
        index.width(terminal.renderFrame(), firstKey, ::measure)
        val historyWork = index.measuredHistoryRows
        val screenWork = index.measuredScreenRows
        repeat(12) { terminal.feed("\r\nbackground-$it") }
        val next = terminal.renderFrame()
        val remountedKey = metric("same-width-style", family, resolver, fonts)
        assertEquals(expected(next), index.width(next, remountedKey, ::measure))
        assertEquals(12, index.lastMeasuredHistoryRows)
        assertEquals(6, index.lastMeasuredScreenRows)
        assertEquals(historyWork + 12, index.measuredHistoryRows)
        assertEquals(screenWork + 6, index.measuredScreenRows)
    }

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
            assertEquals(6, index.lastMeasuredScreenRows)
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
