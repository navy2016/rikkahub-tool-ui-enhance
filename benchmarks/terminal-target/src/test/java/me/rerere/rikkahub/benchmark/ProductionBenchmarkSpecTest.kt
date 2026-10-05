package me.rerere.rikkahub.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionBenchmarkSpecTest {
    @Test fun everyScenarioContainsAllSizesAndAnAdjacentProductionPair() {
        for (group in ProductionBenchmarkSpec.scenarios) {
            val cases = ProductionBenchmarkSpec.cases(group)
            assertEquals(6, cases.size)
            for ((index, pair) in cases.chunked(2).withIndex()) {
                assertEquals(listOf(ProductionBenchmarkSpec.sizes[index]), pair.map { it[0] }.distinct())
                assertEquals(listOf(group), pair.map { it[1] }.distinct())
                assertEquals(ProductionBenchmarkSpec.modes.toSet(), pair.map { it[2] }.toSet())
            }
            assertTrue(cases[0][2] != cases[2][2])
        }
    }

    @Test fun smokeKeepsBothModesAndNeverPretendsToBeAFullMatrix() {
        for (group in ProductionBenchmarkSpec.scenarios) {
            val smoke = ProductionBenchmarkSpec.cases(group, smoke = true)
            assertEquals(2, smoke.size)
            assertEquals(listOf(1000), smoke.map { it[0] }.distinct())
            assertEquals(ProductionBenchmarkSpec.modes.toSet(), smoke.map { it[2] }.toSet())
        }
    }

    @Test fun unknownInputsCannotSilentlySelectAnotherRendererOrScenario() {
        for (mode in ProductionBenchmarkSpec.modes) {
            ProductionBenchmarkSpec.validate(1000, "initialCompose", mode)
        }
        assertThrows(IllegalArgumentException::class.java) { ProductionBenchmarkSpec.cases("oldLazy") }
        assertThrows(IllegalArgumentException::class.java) { ProductionBenchmarkSpec.validate(10, "initialCompose", "lazyHistory") }
        assertThrows(IllegalArgumentException::class.java) { ProductionBenchmarkSpec.validate(1000, "old", "lazyHistory") }
        assertThrows(IllegalArgumentException::class.java) { ProductionBenchmarkSpec.validate(1000, "initialCompose", "eager") }
    }

    @Test fun phaseReceiptsAreDurableBeforeWaitAndCannotAcceptAnotherLaunch() {
        val progress = ProductionBenchmarkProgress("new-launch")
        assertTrue(!progress.accept("old-launch", "done"))
        assertTrue(!progress.await("prepared", 0))
        for (phase in listOf("prepared", "mounted", "done")) {
            assertTrue(progress.accept("new-launch", phase))
            assertTrue(progress.accept("new-launch", phase)) // Duplicate delivery does not advance.
        }
        for (phase in listOf("prepared", "mounted", "done")) assertTrue(progress.await(phase, 0))
        assertEquals("done", progress.currentPhase)
    }

    @Test fun missingOutOfOrderAndFailedPhasesNeverReportCompletion() {
        for (bad in listOf("done", "mounted", "unknown", "failed:viewport assertion")) {
            val progress = ProductionBenchmarkProgress("launch")
            assertTrue(progress.accept("launch", bad))
            for (phase in listOf("prepared", "mounted", "done")) {
                assertThrows(IllegalStateException::class.java) { progress.await(phase, 0) }
            }
        }
        val progress = ProductionBenchmarkProgress("launch")
        assertTrue(progress.accept("launch", "prepared"))
        assertTrue(!progress.await("mounted", 0))
        progress.accept("launch", "failed:layout")
        assertThrows(IllegalStateException::class.java) { progress.await("prepared", 0) }
    }

    @Test fun asynchronousReceiptWakesTheDriverWithoutPollingTheUiTree() {
        val progress = ProductionBenchmarkProgress("launch")
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> { progress.await("prepared", 5_000) }
            assertTrue(progress.accept("launch", "prepared"))
            assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }
}
