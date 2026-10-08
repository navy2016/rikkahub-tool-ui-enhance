package me.rerere.rikkahub.data.container

import androidx.compose.ui.text.AnnotatedString
import me.rerere.rikkahub.utils.TerminalEmulator
import me.rerere.rikkahub.utils.ownedRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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
        // Surviving row sizes are stable by ID/text. A position-dependent height change must
        // invalidate the contributing block; it is covered separately, not silently simulated.
        val values = stableHeights(initial)
        val before = requireNotNull(cache.read(initial, 1) { values[it] })
        for (command in listOf("\r\nappended", "\u001B[3J")) {
            terminal.feed(command)
            val next = terminal.renderFrame()
            val nextHeights = stableHeights(next)
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

    @Test fun passiveDiagnosticsNeverPopulateOrReturnInvalidatedGeometry() {
        val frame = seeded().renderFrame()
        val cache = TerminalEagerGeometryCache()
        assertNull(cache.peek(frame, 1))
        assertEquals(0L, cache.visitedHistoryRows)
        val geometry = requireNotNull(cache.read(frame, 1) { 20 })
        assertSame(geometry, cache.peek(frame, 1))
        assertNull(cache.peek(frame.copy(), 1))
        assertNull(cache.peek(frame, 2))
        cache.invalidate(historyChanged = false)
        assertNull(cache.peek(frame, 1))
        assertEquals(frame.historyCount.toLong(), cache.visitedHistoryRows)
        cache.clear()
        assertNull(cache.peek(frame, 1))
    }

    @Test fun prefixRejectsInvalidHeightsAndOverflowInsteadOfWrappingCoordinates() {
        assertNull(TerminalMeasuredHeightPrefix.measure(3) { if (it == 1) 0 else 20 })
        assertNull(TerminalMeasuredHeightPrefix.measure(3) { if (it == 1) -1 else 20 })
        assertThrows(ArithmeticException::class.java) {
            TerminalMeasuredHeightPrefix.measure(2) { Int.MAX_VALUE }
        }
    }

    private fun stableHeights(frame: TerminalEmulator.RenderFrame) = IntArray(frame.rows.size) { index ->
        val id = if (index < frame.historyCount) frame.historyLineIds[index] else frame.screenLineIds[index - frame.historyCount]
        19 + (id % 11).toInt() + frame.rows[index].text.length % 5
    }

    private class Registry(val cache: TerminalEagerGeometryCache) {
        val tokens = mutableMapOf<Long, TerminalEagerHistoryBlockToken>()
        fun read(frame: TerminalEmulator.RenderFrame, values: IntArray, missing: Int = -1,
            fail: Int = -1, metric: Any = "font"): TerminalEagerViewportGeometry? =
            cache.read(frame, metric, onHistoryRowMeasured = { index, token -> tokens[frame.historyLineIds[index]] = token }) {
                if (it == fail) error("measurement failed")
                if (it == missing) null else values[it]
            }

        fun remove(id: Long) { cache.invalidateBlock(tokens.remove(id)) }
    }

    @Test fun fifoAppendsAndTrimsAtOneFiveAndTenThousandRowsReadOnlyBoundaryBlocks() {
        for (size in listOf(1_000, 5_000, 10_000)) {
            val terminal = seeded(size)
            val cache = TerminalEagerGeometryCache()
            val registry = Registry(cache)
            var frame = terminal.renderFrame()
            val initialHeights = stableHeights(frame)
            val original = requireNotNull(registry.read(frame, initialHeights))
            val oldTotal = original.contentHeightPx
            val initialVisits = cache.visitedHistoryRows
            repeat(140) { update ->
                val removed = frame.historyLineIds.first()
                val oldSource = requireNotNull(frame.ownedRows()).history
                terminal.feed("\r\nrow:append-$update")
                frame = terminal.renderFrame()
                registry.remove(removed) // Production's detached head must not invalidate every block.
                val before = cache.visitedHistoryRows
                val values = stableHeights(frame)
                val result = requireNotNull(registry.read(frame, values))
                val changedRows = requireNotNull(frame.ownedRows()).history.blocks.filter { block ->
                    oldSource.blocks.none { it === block }
                }.sumOf { it.size }
                assertEquals("size=$size update=$update", changedRows.toLong(), cache.visitedHistoryRows - before)
                assertTrue(cache.visitedHistoryRows - before <= 256)
                assertEquals(size, cache.retainedHistoryRows)
                assertEquals(requireNotNull(frame.ownedRows()).history.blocks.size, cache.retainedHistoryBlocks)
                assertEquals(values.sum(), result.contentHeightPx)
                if (update % 35 == 0) checkGeometry(result, values)
            }
            assertTrue(cache.visitedHistoryRows - initialVisits <= 140L * 256)
            assertTrue(cache.reusedHistoryRows >= 140L * (size - 256))
            assertEquals("previous published prefix was mutated", oldTotal, original.contentHeightPx)
            checkGeometry(original, initialHeights)
        }
    }

    @Test fun oneRemeasuredHistoricalRowRevokesOnlyItsBlockAndStaleTokenCannotRevokeReplacement() {
        val frame = seeded(1_000).renderFrame()
        val cache = TerminalEagerGeometryCache()
        val registry = Registry(cache)
        val values = stableHeights(frame)
        val original = requireNotNull(registry.read(frame, values))
        val id = frame.historyLineIds[300]
        val stale = requireNotNull(registry.tokens[id])
        cache.invalidateBlock(stale)
        assertNull(cache.peek(frame, "font"))
        assertEquals(1_000 - 128, cache.retainedHistoryRows)
        values[300] += 40
        val visits = cache.visitedHistoryRows
        val changed = requireNotNull(registry.read(frame, values))
        assertEquals(128L, cache.visitedHistoryRows - visits)
        assertNotSame(stale, registry.tokens[id])
        val now = cache.visitedHistoryRows
        cache.invalidateBlock(stale)
        val again = requireNotNull(registry.read(frame, values))
        assertSame(changed.historyPrefix, again.historyPrefix)
        assertEquals(now, cache.visitedHistoryRows)
        checkGeometry(changed, values)
        assertEquals(values[300] - 40, original.height(300))
        val current = requireNotNull(registry.tokens[id])
        cache.clear()
        registry.read(frame, values)
        val afterClear = cache.visitedHistoryRows
        cache.invalidateBlock(current)
        registry.read(frame, values)
        assertEquals("old capability crossed a cache clear", afterClear, cache.visitedHistoryRows)
    }

    @Test fun missingBoundaryMeasurementKeepsAllOtherBlocksButPublishesNoPartialGeometry() {
        val terminal = seeded(1_000)
        val cache = TerminalEagerGeometryCache()
        val registry = Registry(cache)
        val initial = terminal.renderFrame()
        registry.read(initial, stableHeights(initial))
        terminal.feed("\r\nnew")
        val next = terminal.renderFrame()
        registry.remove(initial.historyLineIds.first())
        val values = stableHeights(next)
        assertNull(registry.read(next, values, missing = 0))
        assertNull(cache.peek(next, "font"))
        assertEquals(768, cache.retainedHistoryRows) // Six complete middle blocks survive first-boundary failure.
        val before = cache.visitedHistoryRows
        assertNull(registry.read(next, values, missing = next.historyCount - 1))
        assertEquals(895, cache.retainedHistoryRows) // Head was complete; incomplete tail not published.
        assertEquals(232L, cache.visitedHistoryRows - before)
        val retry = cache.visitedHistoryRows
        val completed = requireNotNull(registry.read(next, values))
        assertEquals(105L, cache.visitedHistoryRows - retry)
        checkGeometry(completed, values)
        val tailToken = requireNotNull(registry.tokens[next.historyLineIds.last()])
        cache.invalidateBlock(tailToken)
        assertThrows(IllegalStateException::class.java) { registry.read(next, values, fail = 950) }
        assertNull(cache.peek(next, "font"))
        assertEquals(895, cache.retainedHistoryRows)
        checkGeometry(requireNotNull(registry.read(next, values)), values)
    }

    @Test fun trimAcrossWholeBucketsNeverRetainsRetiredHeightBlocks() {
        val terminal = seeded(1_000)
        val cache = TerminalEagerGeometryCache()
        val registry = Registry(cache)
        val first = terminal.renderFrame()
        val values = stableHeights(first)
        val original = requireNotNull(registry.read(first, values))
        terminal.setMaxScrollbackLines(350)
        val trimmed = terminal.renderFrame()
        first.historyLineIds.take(650).forEach { registry.remove(it) }
        val visits = cache.visitedHistoryRows
        checkGeometry(requireNotNull(registry.read(trimmed, stableHeights(trimmed))), stableHeights(trimmed))
        assertEquals(118L, cache.visitedHistoryRows - visits)
        assertEquals(350, cache.retainedHistoryRows)
        assertEquals(3, cache.retainedHistoryBlocks)
        checkGeometry(original, values)
        terminal.setMaxScrollbackLines(1)
        val last = terminal.renderFrame()
        checkGeometry(requireNotNull(registry.read(last, stableHeights(last))), stableHeights(last))
        assertEquals(1, cache.retainedHistoryRows)
        assertEquals(1, cache.retainedHistoryBlocks)
        terminal.clearScrollbackOnly()
        val cleared = terminal.renderFrame()
        registry.read(cleared, stableHeights(cleared))
        assertEquals(0, cache.retainedHistoryRows)
        assertEquals(0, cache.retainedHistoryBlocks)
    }

    @Test fun randomFifoHeightInvalidationAndMetricResetMatchIndependentGeometry() {
        val random = Random(917)
        val terminal = seeded(1_200)
        val cache = TerminalEagerGeometryCache()
        val registry = Registry(cache)
        val overrides = mutableMapOf<Long, Int>()
        var scale = 1
        var frame = terminal.renderFrame()
        repeat(180) { round ->
            val before = frame
            when (round % 5) {
                0 -> repeat(random.nextInt(1, 25)) { terminal.feed("\r\nrandom-$round-$it") }
                1 -> {
                    val id = frame.historyLineIds[random.nextInt(frame.historyCount)]
                    overrides[id] = random.nextInt(12, 61)
                    cache.invalidateBlock(registry.tokens[id])
                }
                2 -> terminal.feed("\r\u001B[2Kscreen-$round")
                3 -> scale = if (scale == 1) 2 else 1
                else -> terminal.setMaxScrollbackLines(random.nextInt(200, 1_500))
            }
            frame = terminal.renderFrame()
            val live = frame.historyLineIds.toSet()
            before.historyLineIds.filter { it !in live }.forEach { registry.remove(it) }
            val values = stableHeights(frame).also { heights ->
                for (index in heights.indices) {
                    val id = if (index < frame.historyCount) frame.historyLineIds[index]
                        else frame.screenLineIds[index - frame.historyCount]
                    heights[index] = (overrides[id] ?: heights[index]) * scale
                }
            }
            val result = requireNotNull(registry.read(frame, values, metric = scale))
            checkGeometry(result, values)
            assertEquals(frame.historyCount, cache.retainedHistoryRows)
            assertEquals(requireNotNull(frame.ownedRows()).history.blocks.size, cache.retainedHistoryBlocks)
        }
    }

    @Test fun scalarBlockDirectoryOffsetsMatchFlatPrefixAtEveryBoundaryAlignment() {
        for (first in listOf(0L, 1L, 63L, 127L, 128L, Long.MAX_VALUE - 2_048)) {
            for (size in listOf(0, 1, 127, 128, 129, 256, 1_024)) {
                val heights = IntArray(size) { 3 + it % 17 }
                val parts = mutableListOf<TerminalMeasuredHeightPrefix>()
                var cursor = 0
                while (cursor < size) {
                    val count = minOf(128 - ((first + cursor) % 128).toInt(), size - cursor)
                    parts.add(TerminalMeasuredHeightPrefix.fromHeights(heights, cursor, cursor + count))
                    cursor += count
                }
                val prefix = TerminalMeasuredHeightPrefix.fromBlocks(first, parts)
                assertEquals(size, prefix.size)
                var sum = 0
                for (index in 0..size) {
                    assertEquals("first=$first size=$size index=$index", sum, prefix.offset(index))
                    if (index < size) sum += heights[index]
                }
                assertEquals(sum, prefix.totalHeightPx)
                assertThrows(IndexOutOfBoundsException::class.java) { prefix.offset(-1) }
                assertThrows(IndexOutOfBoundsException::class.java) { prefix.offset(size + 1) }
            }
        }
        val one = TerminalMeasuredHeightPrefix.fromHeights(intArrayOf(10), 0, 1)
        assertThrows(IllegalArgumentException::class.java) { TerminalMeasuredHeightPrefix.fromBlocks(0, listOf(one, one)) }
        assertThrows(ArithmeticException::class.java) { TerminalMeasuredHeightPrefix.fromBlocks(Long.MAX_VALUE, listOf(one)) }
        val maximum = requireNotNull(TerminalMeasuredHeightPrefix.measure(128) { if (it == 0) Int.MAX_VALUE - 127 else 1 })
        assertThrows(ArithmeticException::class.java) { TerminalMeasuredHeightPrefix.fromBlocks(0, listOf(maximum, one)) }
    }
}
