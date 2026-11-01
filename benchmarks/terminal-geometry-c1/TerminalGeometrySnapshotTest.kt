package me.rerere.rikkahub.data.container

import java.lang.reflect.Modifier
import me.rerere.rikkahub.utils.TerminalEmulator
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/** Detached scalar geometry only. No JVM timing/Compose font or real-device performance claim. */
class TerminalGeometrySnapshotTest {
    private fun frame(history: Int = 32) = TerminalEmulator(80, 24, history).apply {
        feed("\u001b[?25l" + (0 until history + 24).joinToString("\r\n") { "row-$it 中文" } + "\r")
    }.renderFrame()
    private fun policy(ime: Boolean = false, retained: Int? = null) = TerminalFullVirtualPolicy(
        TerminalPhysicalRowHeights(IntArray(24) { 20 }), tui = true, preserveGrid = false,
        ime = ime, avoidIme = true, retainedScreenBottom = retained)
    private fun rows(frame: TerminalEmulator.RenderFrame, top: Int = -7) = (0 until 10).map {
        TerminalVisibleRow(frame.historyCount + it, top + it * 20, 20)
    }
    private fun view(frame: TerminalEmulator.RenderFrame, rows: List<TerminalVisibleRow>,
        policy: TerminalFullVirtualPolicy? = policy(), height: Int = 190) = TerminalItemViewport(
        frame, "font", rows, height, 20, 8, true, true, policy)
    private fun snapshot(frame: TerminalEmulator.RenderFrame, rows: List<TerminalVisibleRow> = rows(frame)) =
        requireNotNull(TerminalVisibleRowSnapshot.capture(rows, frame.rows.size))
    private fun equivalent(a: TerminalItemViewport, b: TerminalItemViewport) {
        assertEquals(a, b); assertEquals(a.hashCode(), b.hashCode()); assertEquals(a.ready, b.ready)
        assertEquals(a.capture(), b.capture()); assertEquals(a.physicalScreenTop, b.physicalScreenTop)
        assertEquals(a.followRowIndex, b.followRowIndex); assertEquals(a.followDelta(), b.followDelta())
        assertEquals(a.nearTop(), b.nearTop()); assertEquals(a.nearFollow(), b.nearFollow())
        for (target in listOf(TerminalItemScrollTarget.Top, TerminalItemScrollTarget.Follow)) {
            assertEquals(a.isSatisfied(target), b.isSatisfied(target))
        }
        for (index in 0 until a.frame.rows.size) assertEquals(a.measuredRow(index), b.measuredRow(index))
    }

    @Test fun capturedScalarRowsEqualOrdinaryListAndHash() {
        val frame = frame(); val rows = rows(frame); val copy = snapshot(frame, rows)
        assertEquals(rows, copy); assertEquals(copy, rows); assertEquals(rows.hashCode(), copy.hashCode())
        assertTrue(copy is java.util.RandomAccess); assertTrue(copy.validatedFor(frame.rows.size))
        equivalent(view(frame, rows), view(frame, copy))
    }
    @Test fun sourceListChangesCannotMutatePublishedGeometry() {
        val frame = frame(); val source = rows(frame).toMutableList(); val copy = snapshot(frame, source)
        val expected = source.toList(); source[0] = TerminalVisibleRow(0, 999, 1); source.clear()
        assertEquals(expected, copy); equivalent(view(frame, expected), view(frame, copy))
    }
    @Test fun exportedArraysCannotMutateSnapshotStorage() {
        val frame = frame(); val copy = snapshot(frame); val old = copy[0]; val array = copy.toTypedArray()
        array[0] = TerminalVisibleRow(0, 0, 1); assertEquals(old, copy[0])
    }
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UNCHECKED_CAST")
    @Test fun javaListMutationIsRejected() {
        val frame = frame(); val copy = snapshot(frame)
        assertThrows(UnsupportedOperationException::class.java) {
            (copy as java.util.List<TerminalVisibleRow>).set(0, TerminalVisibleRow(0, 0, 1))
        }
        assertEquals(rows(frame), copy)
    }
    @Test fun invalidCountsAndOutOfFrameRowsCannotGainProof() {
        assertNull(TerminalVisibleRowSnapshot.capture(emptyList(), -1))
        assertNull(TerminalVisibleRowSnapshot.capture(listOf(TerminalVisibleRow(0, 0, 20)), 0))
        assertNull(TerminalVisibleRowSnapshot.capture(listOf(TerminalVisibleRow(5, 0, 20)), 5))
        assertNotNull(TerminalVisibleRowSnapshot.capture(listOf(TerminalVisibleRow(4, 0, 20)), 5))
    }
    @Test fun duplicateUnsortedAndOverlappingRowsCannotGainProof() {
        for (rows in listOf(
            listOf(TerminalVisibleRow(1, 0, 20), TerminalVisibleRow(1, 20, 20)),
            listOf(TerminalVisibleRow(2, 0, 20), TerminalVisibleRow(1, 20, 20)),
            listOf(TerminalVisibleRow(1, 0, 20), TerminalVisibleRow(2, 19, 20)),
        )) assertNull(TerminalVisibleRowSnapshot.capture(rows, 24))
    }
    @Test fun touchingRowsGapsAndNegativeCoordinatesKeepExactGeometry() {
        val frame = frame(); val rows = listOf(TerminalVisibleRow(0, -71, 20), TerminalVisibleRow(2, -51, 37),
            TerminalVisibleRow(4, 25, 19))
        equivalent(view(frame, rows), view(frame, snapshot(frame, rows)))
    }
    @Test fun emptySnapshotIsNotAReadyViewport() {
        val frame = frame(); val rows = snapshot(frame, emptyList())
        assertFalse(view(frame, rows).ready); assertNull(view(frame, rows).capture())
        assertNotNull(TerminalVisibleRowSnapshot.capture(emptyList(), 0))
    }
    @Test fun shapeProofIsBoundToExactFrameRowCount() {
        val frame = frame(); val rows = snapshot(frame)
        assertFalse(rows.validatedFor(frame.rows.size + 1)); assertFalse(rows.validatedFor(frame.rows.size - 1))
        val smaller = TerminalEmulator(80, 6).renderFrame()
        assertThrows(IllegalArgumentException::class.java) { view(frame, rows).copy(frame = smaller) }
    }
    @Test fun sameCountDifferentFrameStillRequiresControllerFrameIdentity() {
        val first = frame(); val next = first.copy(revision = first.revision)
        val old = view(first, snapshot(first)); val controller = TerminalViewportController()
        controller.updateItemViewport(next, old)
        assertFalse(controller.state.value.initialized); assertNull(controller.state.value.scrollEffect)
        controller.updateItemViewport(next, view(next, snapshot(next)))
        assertTrue(controller.state.value.initialized)
    }
    @Test fun ordinaryMutableRowsNeverUseNoOpPolicyFastPath() {
        val frame = frame(); val source = rows(frame).toMutableList(); val view = view(frame, source)
        assertNotSame(view, view.withFullVirtualPolicy(requireNotNull(view.fullVirtual)))
        source[1] = TerminalVisibleRow(source[0].index, source[1].topPx, 20)
        assertThrows(IllegalArgumentException::class.java) { view.withFullVirtualPolicy(requireNotNull(view.fullVirtual)) }
    }
    @Test fun proofBearingSamePolicyReturnsExactObservation() {
        val frame = frame(); val view = view(frame, snapshot(frame)); val policy = requireNotNull(view.fullVirtual)
        repeat(512) { assertSame(view, view.withFullVirtualPolicy(policy.copy())) }
    }
    @Test fun everyChangedFullPolicyCreatesNewObservationButSharesGeometry() {
        val frame = frame(); val original = view(frame, snapshot(frame)); val policy = requireNotNull(original.fullVirtual)
        val changes = listOf(policy.copy(tui = false), policy.copy(preserveGrid = true), policy.copy(ime = true),
            policy.copy(avoidIme = false), policy.copy(retainedScreenBottom = 20),
            policy.copy(screen = TerminalPhysicalRowHeights(IntArray(24) { 21 })))
        for (changed in changes) {
            val next = original.withFullVirtualPolicy(changed)
            assertNotSame(original, next); assertSame(original.rows, next.rows); assertEquals(changed, next.fullVirtual)
            equivalent(original.copy(rows = original.rows.toList(), fullVirtual = changed), next)
        }
    }
    @Test fun changedRetainedImeBottomIsNeverSwallowed() {
        val frame = frame(); val original = view(frame, snapshot(frame), policy(ime = true))
        val next = original.withFullVirtualPolicy(original.fullVirtual!!.copy(retainedScreenBottom = 23))
        assertEquals(23, next.fullVirtual!!.retainedScreenBottom); assertNull(original.fullVirtual!!.retainedScreenBottom)
        assertSame(original.rows, next.rows); assertSame(next, next.withFullVirtualPolicy(next.fullVirtual!!))
    }
    @Test fun removingFullPolicyKeepsHistoryBackendValidationAndReadiness() {
        val model = TerminalEmulator(80, 24).apply { feed("\u001b[?1049hALT") }; val frame = model.renderFrame()
        val original = view(frame, snapshot(frame))
        assertTrue(original.ready); assertFalse(original.copy(fullVirtual = null).ready)
        val legacy = original.copy(fullVirtual = null)
        assertNotSame(legacy, legacy.withFullVirtualPolicy(original.fullVirtual!!))
    }
    @Test fun ordinaryCopyStillRejectsInvalidRowsAndViewportScalars() {
        val frame = frame(); val original = view(frame, snapshot(frame))
        assertThrows(IllegalArgumentException::class.java) { original.copy(viewportHeightPx = -1) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(cellHeightPx = 0) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(tailPaddingPx = -1) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(rows = listOf(TerminalVisibleRow(frame.rows.size, 0, 20))) }
    }
    @Test fun repeatedPolicyChangesNeverReadTheMutableSourceAgain() {
        val frame = frame(); val source = object : AbstractList<TerminalVisibleRow>() {
            val values = rows(frame); var reads = 0
            override val size: Int get() = values.size
            override fun get(index: Int): TerminalVisibleRow { reads++; return values[index] }
        }
        val snapshot = snapshot(frame, source); assertEquals(source.size, source.reads)
        val original = view(frame, snapshot); val captured = source.reads
        repeat(512) { index ->
            val changed = original.withFullVirtualPolicy(original.fullVirtual!!.copy(ime = index % 2 == 0, retainedScreenBottom = index % 24))
            assertSame(snapshot, changed.rows)
        }
        assertEquals(captured, source.reads)
    }
    @Test fun snapshotRetainsOnlyScalarRowArrayAndCount() {
        val fields = TerminalVisibleRowSnapshot::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(setOf("values", "frameRowCount"), fields.map { it.name }.toSet())
        assertEquals(Array<TerminalVisibleRow>::class.java, fields.single { it.name == "values" }.type)
        assertEquals(Int::class.javaPrimitiveType, fields.single { it.name == "frameRowCount" }.type)
    }
    @Test fun snapshotDoesNotCacheOffsetsAcrossTwoCaptures() {
        val frame = frame(); val first = snapshot(frame, rows(frame, -7)); val second = snapshot(frame, rows(frame, -16))
        assertNotSame(first, second); assertEquals(-7, first[0].topPx); assertEquals(-16, second[0].topPx)
        assertNotEquals(view(frame, first).capture(), view(frame, second).capture())
    }
    @Test fun measuredOffscreenPhysicalRowsMatchOrdinaryGeometry() {
        val frame = frame(); val rows = listOf(TerminalVisibleRow(frame.historyCount + 6, -3, 20))
        equivalent(view(frame, rows), view(frame, snapshot(frame, rows)))
    }
    @Test fun followAnchorTopAndBlockedDirectionsMatchOrdinaryGeometry() {
        val frame = frame()
        for (top in listOf(-300, -7, 0, 17, 320)) for (back in listOf(false, true)) for (forward in listOf(false, true)) {
            val plain = view(frame, rows(frame, top)).copy(canScrollBackward = back, canScrollForward = forward)
            val proof = plain.copy(rows = snapshot(frame, plain.rows))
            equivalent(plain, proof)
            val anchor = plain.capture()!!; assertEquals(plain.isSatisfied(anchor), proof.isSatisfied(anchor))
            assertEquals(plain.confirmsScrollFrom(plain, anchor), proof.confirmsScrollFrom(proof, anchor))
        }
    }
    @Test fun controllerImeAndPolicySequencesMatchOrdinaryObservations() {
        val frame = frame(); val original = TerminalViewportController(); val optimized = TerminalViewportController()
        for (step in 0..31) {
            val policy = policy(ime = step in 3..24).copy(avoidIme = step % 3 == 0, preserveGrid = step % 5 == 0)
            val plain = view(frame, rows(frame, -step), policy)
            val proof = plain.copy(rows = snapshot(frame, plain.rows))
            original.updateItemViewport(frame, plain); optimized.updateItemViewport(frame, proof)
            assertEquals(original.state.value, optimized.state.value)
            equivalent(original.withRetainedItemScreenBottom(plain), optimized.withRetainedItemScreenBottom(proof))
            original.observeItemViewport(frame, plain); optimized.observeItemViewport(frame, proof)
            assertEquals(original.state.value, optimized.state.value)
        }
    }
    @Test fun randomizedValidGeometryAndPoliciesMatchOrdinaryOracle() {
        val random = Random(306); val frame = frame(1000)
        repeat(500) {
            val size = random.nextInt(1, 40); val begin = random.nextInt(0, frame.rows.size - size)
            var top = random.nextInt(-200, 201)
            val rows = (0 until size).map { n ->
                val height = random.nextInt(1, 60); TerminalVisibleRow(begin + n, top, height).also { top += height + random.nextInt(0, 5) }
            }
            val policy = policy(random.nextBoolean(), if (random.nextBoolean()) random.nextInt(24) else null)
                .copy(tui = random.nextBoolean(), preserveGrid = random.nextBoolean(), avoidIme = random.nextBoolean())
            equivalent(view(frame, rows, policy), view(frame, snapshot(frame, rows), policy))
        }
    }
    @Test fun randomizedMalformedListsHaveExactlyOriginalAcceptanceRules() {
        val random = Random(9751); val frame = frame()
        repeat(1000) {
            val rows = List(random.nextInt(0, 8)) { TerminalVisibleRow(random.nextInt(frame.rows.size + 4),
                random.nextInt(-80, 180), random.nextInt(1, 50)) }
            val oldAccepted = rows.zipWithNext().all { (a, b) -> a.index < b.index && a.bottomPx <= b.topPx } &&
                rows.all { it.index < frame.rows.size }
            val proof = TerminalVisibleRowSnapshot.capture(rows, frame.rows.size)
            assertEquals(oldAccepted, proof != null)
            if (proof != null) equivalent(view(frame, rows), view(frame, proof))
        }
    }
}
