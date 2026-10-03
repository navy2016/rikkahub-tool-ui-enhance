package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** The oracle sums independent row heights; it does not use either cached prefix implementation. */
class TerminalEagerGeometryCacheTest {
    private fun seeded(historyRows: Int = 100) = TerminalEmulator(80, 6, historyRows).apply {
        feed("\u001B[?25l" + (0 until historyRows + 6).joinToString("\r\n") { "row:$it" })
    }

    private fun heights(frame: TerminalEmulator.RenderFrame) = IntArray(frame.rows.size) { index ->
        20 + frame.rows[index].text.length % 5 + (index % 7) * 3
    }

    private fun checkGeometry(geometry: TerminalEagerViewportGeometry, heights: IntArray) {
        val frame = geometry.frame
        val ids = frame.historyLineIds + frame.screenLineIds
        val lookup = TerminalViewportLineLookup()
        var top = 0
        for (index in heights.indices) {
            val rowHeight = heights[index]
            assertEquals(rowHeight, geometry.height(index))
            assertEquals(top + rowHeight, geometry.bottom(index))
            for (clip in listOf(0, rowHeight / 2, rowHeight - 1)) {
                val captured = geometry.capture(top + clip)
                assertEquals(ids[index], captured.anchor.lineId)
                assertEquals(clip, captured.anchor.clippedTopPx)
                assertEquals(rowHeight, captured.capturedRowHeightPx)
                assertEquals(top + clip, geometry.target(captured.anchor, captured.capturedRowHeightPx, lookup))
            }
            top += rowHeight
        }
        assertEquals(top, geometry.contentHeightPx)
        assertEquals(0, geometry.capture(-1).anchor.clippedTopPx)
        assertEquals(ids.last(), geometry.capture(Int.MAX_VALUE).anchor.lineId)
        assertEquals(heights.last() - 1, geometry.capture(Int.MAX_VALUE).anchor.clippedTopPx)
    }

    @Test fun splitPrefixMatchesFullSumsAcrossHistoryScreenBoundaryAndEmptyHistory() {
        for (frame in listOf(seeded().renderFrame(), TerminalEmulator(80, 6).renderFrame())) {
            val values = heights(frame)
            val cache = TerminalEagerGeometryCache()
            val result = requireNotNull(cache.read(frame, "font") { values[it] })
            checkGeometry(result, values)
            assertSame(result, cache.read(frame, "font") { error("Unchanged frame was rescanned") })
        }
    }

    @Test fun activeUpdatesShareHistoryAtOneFiveAndTenThousandRowsWithoutHistoryVisits() {
        for (size in listOf(1000, 5000, 10000)) {
            val terminal = seeded(size)
            val cache = TerminalEagerGeometryCache()
            val initial = terminal.renderFrame()
            val initialHeights = heights(initial)
            val original = requireNotNull(cache.read(initial, "font") { initialHeights[it] })
            val oldTotal = original.contentHeightPx
            repeat(8) { update ->
                terminal.feed("\r\u001B[2Kactive-$update")
                val next = terminal.renderFrame()
                val currentHeights = initialHeights.copyOf().also { it[it.lastIndex] += update + 1 }
                cache.invalidate(historyChanged = false)
                val result = requireNotNull(cache.read(next, "font") { index ->
                    check(index >= next.historyCount) { "Active output scanned archived height $index" }
                    currentHeights[index]
                })
                assertSame(original.historyPrefix, result.historyPrefix)
                assertSame(next, result.frame)
                assertEquals(currentHeights.sum(), result.contentHeightPx)
                assertEquals(oldTotal, original.contentHeightPx)
                assertEquals(initialHeights.last(), original.height(initialHeights.lastIndex))
                assertSame(result, cache.read(next, "font") { error("Same layout queried twice") })
            }
            assertEquals(size.toLong(), cache.visitedHistoryRows)
            assertEquals(6L * 9, cache.visitedScreenRows)
            assertEquals(1L, cache.historyBuildCount)
        }
    }

    @Test fun historyRemeasureInvalidatesSameFrameAndDoesNotMutatePublishedGeometry() {
        val frame = seeded().renderFrame()
        val values = heights(frame)
        val cache = TerminalEagerGeometryCache()
        val before = requireNotNull(cache.read(frame, 1) { values[it] })
        val changed = values.copyOf().also { it[3] += 23 }
        cache.invalidate(historyChanged = true)
        val after = requireNotNull(cache.read(frame, 1) { changed[it] })
        assertNotSame(before.historyPrefix, after.historyPrefix)
        assertEquals(2L * frame.historyCount, cache.visitedHistoryRows)
        checkGeometry(before, values)
        checkGeometry(after, changed)
    }

    @Test fun sameFrameScreenRemeasureOnlyReplacesScreenPrefix() {
        val frame = seeded().renderFrame()
        val values = heights(frame)
        val cache = TerminalEagerGeometryCache()
        val before = requireNotNull(cache.read(frame, 1) { values[it] })
        val changed = values.copyOf().also { it[it.lastIndex] += 17 }
        cache.invalidate(historyChanged = false)
        val after = requireNotNull(cache.read(frame, 1) { changed[it] })
        assertSame(before.historyPrefix, after.historyPrefix)
        assertNotSame(before, after)
        checkGeometry(before, values)
        checkGeometry(after, changed)
    }

    @Test fun historyTrimAppendAndClearRebuildAgainstCurrentIdentity() {
        val terminal = seeded()
        val cache = TerminalEagerGeometryCache()
        val initial = terminal.renderFrame()
        val values = heights(initial)
        val before = requireNotNull(cache.read(initial, 1) { values[it] })
        for (command in listOf("\r\nappended", "\u001B[3J")) {
            terminal.feed(command)
            val next = terminal.renderFrame()
            val nextHeights = heights(next)
            val after = requireNotNull(cache.read(next, 1) { nextHeights[it] })
            assertNotSame(before.historyPrefix, after.historyPrefix)
            checkGeometry(after, nextHeights)
        }
        checkGeometry(before, values)
    }

    @Test fun metricsAndDifferentTerminalOwnerNeverReuseHistoricalPrefix() {
        val cache = TerminalEagerGeometryCache()
        val frame = seeded().renderFrame()
        val values = heights(frame)
        val initial = requireNotNull(cache.read(frame, "font14-ltr") { values[it] })
        val resized = requireNotNull(cache.read(frame, "font21-rtl") { values[it] * 2 })
        assertNotSame(initial.historyPrefix, resized.historyPrefix)
        val another = seeded().renderFrame()
        val replaced = requireNotNull(cache.read(another, "font21-rtl") { values[it] * 2 })
        assertNotSame(resized.historyPrefix, replaced.historyPrefix)
        assertEquals(3L * frame.historyCount, cache.visitedHistoryRows)
    }

    @Test fun syntheticRowsAndIdsCannotReuseSameRevisionOrPlausibleMetadata() {
        val frame = seeded().renderFrame()
        val cache = TerminalEagerGeometryCache()
        val original = requireNotNull(cache.read(frame, 1) { 20 })
        val rowsReplaced = frame.copy(rows = frame.rows.map { it.copy(text = AnnotatedString("changed")) })
        val idsReplaced = frame.copy(historyLineIds = frame.historyLineIds.map { it + 10000 })
        for (synthetic in listOf(rowsReplaced, rowsReplaced.copy(), idsReplaced)) {
            val result = requireNotNull(cache.read(synthetic, 1) { 30 })
            assertNotSame(original.historyPrefix, result.historyPrefix)
            assertEquals(0, cache.retainedHistoryRows)
            assertEquals(synthetic.rows.size * 30, result.contentHeightPx)
        }
        assertEquals(4L * frame.historyCount, cache.visitedHistoryRows)
    }

    @Test fun missingOrThrowingHistoryMeasurementNeverPublishesPartialProof() {
        val frame = seeded().renderFrame()
        val cache = TerminalEagerGeometryCache()
        cache.read(frame, 1) { 20 }
        cache.invalidate(historyChanged = true)
        assertNull(cache.read(frame, 1) { if (it == 10) null else 30 })
        assertEquals(0, cache.retainedHistoryRows)
        assertThrows(IllegalStateException::class.java) {
            cache.read(frame, 1) { if (it == 10) error("missing font") else 30 }
        }
        assertEquals(0, cache.retainedHistoryRows)
        val values = heights(frame)
        checkGeometry(requireNotNull(cache.read(frame, 1) { values[it] }), values)
    }

    @Test fun incompleteScreenCanRetryWithoutRescanningProvenHistory() {
        val frame = seeded().renderFrame()
        val cache = TerminalEagerGeometryCache()
        assertNull(cache.read(frame, 1) { if (it == frame.historyCount) null else 20 })
        assertEquals(frame.historyCount, cache.retainedHistoryRows)
        val visits = cache.visitedHistoryRows
        val geometry = requireNotNull(cache.read(frame, 1) { index ->
            check(index >= frame.historyCount) { "Proven history was rescanned during screen retry" }
            30
        })
        assertEquals(visits, cache.visitedHistoryRows)
        assertEquals(frame.historyCount * 20 + 6 * 30, geometry.contentHeightPx)
    }

    @Test fun clearAndInvalidFrameReleaseTheHistoricalProof() {
        val frame = seeded().renderFrame()
        val cache = TerminalEagerGeometryCache()
        val initial = requireNotNull(cache.read(frame, 1) { 20 })
        cache.clear()
        assertEquals(0, cache.retainedHistoryRows)
        val next = requireNotNull(cache.read(frame, 1) { 20 })
        assertNotSame(initial.historyPrefix, next.historyPrefix)
        assertNull(cache.read(frame.copy(historyCount = frame.rows.size + 1), 1) { error("Invalid frame read") })
        assertEquals(0, cache.retainedHistoryRows)
    }

    @Test fun prefixRejectsInvalidHeightsAndOverflowInsteadOfWrappingCoordinates() {
        assertNull(TerminalMeasuredHeightPrefix.measure(3) { if (it == 1) 0 else 20 })
        assertNull(TerminalMeasuredHeightPrefix.measure(3) { if (it == 1) -1 else 20 })
        assertThrows(ArithmeticException::class.java) {
            TerminalMeasuredHeightPrefix.measure(2) { Int.MAX_VALUE }
        }
    }
}
