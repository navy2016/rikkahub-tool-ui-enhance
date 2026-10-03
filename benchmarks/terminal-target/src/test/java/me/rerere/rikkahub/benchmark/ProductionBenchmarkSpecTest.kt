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
}
