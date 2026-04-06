package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LedgerPatchTest {

    @Test
    fun `applyLedgerPatchDocument updates existing entry and appends chronology`() {
        val base = RollingSummaryDocument(
            facts = listOf(
                RollingSummaryEntry(
                    id = "fact_1",
                    text = "Current port is 6432",
                    salience = 0.8
                )
            )
        )
        val patch = LedgerPatchDocument(
            updateEntries = listOf(
                EntryPatch(
                    sectionKey = "facts",
                    entry = RollingSummaryEntry(
                        id = "fact_1",
                        text = "Current port is 6433",
                        salience = 0.95,
                        locator = "message:12"
                    )
                )
            ),
            appendChronology = listOf(
                ChronologyPatch(
                    episode = RollingSummaryChronologyEpisode(
                        id = "chrono_1",
                        turnRange = "10-12",
                        summary = "Port changed from 6432 to 6433",
                        salience = 0.9
                    )
                )
            )
        )

        val updated = applyLedgerPatchDocument(
            base = base,
            patch = patch,
            fallbackTurn = 12,
            updatedAt = Instant.ofEpochMilli(1234)
        )

        assertEquals("Current port is 6433", updated.facts.single().text)
        assertEquals("message:12", updated.facts.single().locator)
        assertEquals(1, updated.chronology.size)
        assertEquals("Port changed from 6432 to 6433", updated.chronology.single().summary)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `applyLedgerPatchDocument rejects updates for missing ids`() {
        applyLedgerPatchDocument(
            base = RollingSummaryDocument(),
            patch = LedgerPatchDocument(
                updateEntries = listOf(
                    EntryPatch(
                        sectionKey = "facts",
                        entry = RollingSummaryEntry(
                            id = "missing",
                            text = "Should fail"
                        )
                    )
                )
            ),
            fallbackTurn = 1,
            updatedAt = Instant.now()
        )
    }

    @Test
    fun `applyLedgerPatchDocument keeps superseded entries instead of deleting them`() {
        val base = RollingSummaryDocument(
            tasks = listOf(
                RollingSummaryEntry(
                    id = "task_1",
                    text = "Investigate compression regression",
                    status = "active"
                )
            )
        )
        val patch = LedgerPatchDocument(
            supersedeEntries = listOf(
                SupersedeEntryPatch(
                    sectionKey = "tasks",
                    entryId = "task_1",
                    status = "done",
                    reason = "Fixed and verified",
                    relatedIds = listOf("decision_1")
                )
            )
        )

        val updated = applyLedgerPatchDocument(
            base = base,
            patch = patch,
            fallbackTurn = 8,
            updatedAt = Instant.now()
        )

        assertEquals(1, updated.tasks.size)
        assertEquals("done", updated.tasks.single().status)
        assertEquals("Fixed and verified", updated.tasks.single().reason)
        assertTrue(updated.tasks.single().relatedIds.contains("decision_1"))
    }
}
