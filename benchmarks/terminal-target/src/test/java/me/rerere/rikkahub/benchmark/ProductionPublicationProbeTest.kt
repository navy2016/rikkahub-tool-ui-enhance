package me.rerere.rikkahub.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductionPublicationProbeTest {
    @Test fun captureContractCannotMixOriginalAndDrainedWindows() {
        ProductionBenchmarkSpec.requireCapture("tail-drain-500ms-v1")
        assertEquals(500L, ProductionBenchmarkSpec.CAPTURE_DRAIN_MS)
        for (capture in listOf(null, "", "old-window")) {
            assertThrows(IllegalArgumentException::class.java) { ProductionBenchmarkSpec.requireCapture(capture) }
        }
    }

    @Test fun phasePublicationIsStoredBeforeWakeupAndConflictingDuplicatesFail() {
        val progress = ProductionBenchmarkProgress("launch")
        progress.accept("launch", "prepared")
        progress.accept("launch", "mounted", "exact publication")
        assertEquals(true, progress.await("mounted", 0))
        assertEquals("exact publication", progress.publication("mounted"))
        progress.accept("another", "done", "stale")
        assertEquals(null, progress.publication("done"))
        progress.accept("launch", "mounted", "exact publication")
        progress.accept("launch", "mounted", "different publication")
        assertThrows(IllegalStateException::class.java) { progress.await("mounted", 0) }
        assertEquals("exact publication", progress.publication("mounted"))
    }

    @Test fun absentOrOlderContractsCannotLaunchV5() {
        ProductionBenchmarkSpec.requireVersion("production-viewport-v5")
        for (version in listOf(null, "", "production-viewport-v3", "production-viewport-v4")) {
            assertThrows(IllegalArgumentException::class.java) { ProductionBenchmarkSpec.requireVersion(version) }
        }
    }

    @Test fun eagerPublicationRequiresCommittedProvenanceAndOneLiveView() {
        val probe = ProductionPublicationProbe()
        assertThrows(IllegalStateException::class.java) { probe.paired(true) }
        probe.published()
        probe.panelCommitted()
        assertThrows(IllegalStateException::class.java) { probe.paired(true) }
        probe.viewsCreated(8, 9)
        probe.viewLifetime(1)
        assertThrows(IllegalStateException::class.java) { probe.paired(true) }
        probe.transcriptComposed(true)
        probe.paired(true)
        assertEquals(ProductionPublicationProbe.Work(1, 1, 1, 1, 1, 1, 8, 9, 1), probe.snapshot())
    }

    @Test fun activePublicationAndImmutableReceiptsDoNotInventNewViews() {
        val probe = ProductionPublicationProbe()
        probe.published()
        probe.panelCommitted()
        probe.viewsCreated(80, 81)
        probe.viewLifetime(1)
        probe.transcriptComposed(true)
        probe.paired(true)
        val first = probe.snapshot()
        repeat(30) {
            probe.published()
            probe.panelCommitted()
            probe.transcriptComposed(true)
            probe.paired(true)
        }
        val after = probe.snapshot()
        assertEquals(1, first.publications)
        assertEquals(31, after.publications)
        assertEquals(31, after.pairingChecks)
        assertEquals(first.chunkWrappers, after.chunkWrappers)
        assertEquals(first.rowSlices, after.rowSlices)
    }

    @Test fun uncachedSlicesAndBypassedProvenanceAreRejected() {
        for ((chunks, slices) in listOf(0 to 1, 1 to 1, -1 to 0, 8 to 10)) {
            assertThrows(IllegalStateException::class.java) {
                ProductionPublicationProbe().viewsCreated(chunks, slices)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            ProductionPublicationProbe().transcriptComposed(false)
        }
    }

    @Test fun fallbackAndDetachMustReleaseBeforeVirtualOrReplacementOwnership() {
        val probe = ProductionPublicationProbe()
        probe.published()
        probe.panelCommitted()
        probe.paired(false)
        probe.viewsCreated(8, 9)
        probe.viewLifetime(1)
        probe.transcriptComposed(true)
        probe.paired(true)
        assertThrows(IllegalStateException::class.java) { probe.paired(false) }
        assertThrows(IllegalStateException::class.java) { probe.viewLifetime(1) }
        probe.viewLifetime(-1)
        probe.paired(false)
        assertEquals(0, probe.snapshot().liveViews)
        assertThrows(IllegalStateException::class.java) { probe.viewLifetime(-1) }
        val replacement = ProductionPublicationProbe()
        assertEquals(0, replacement.snapshot().chunkWrappers)
    }
}
