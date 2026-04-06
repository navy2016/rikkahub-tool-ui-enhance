package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant

@Serializable
data class LedgerPatchDocument(
    @SerialName("add_entries")
    val addEntries: List<EntryPatch> = emptyList(),
    @SerialName("update_entries")
    val updateEntries: List<EntryPatch> = emptyList(),
    @SerialName("supersede_entries")
    val supersedeEntries: List<SupersedeEntryPatch> = emptyList(),
    @SerialName("append_chronology")
    val appendChronology: List<ChronologyPatch> = emptyList(),
    @SerialName("add_or_update_detail_capsules")
    val addOrUpdateDetailCapsules: List<DetailCapsulePatch> = emptyList(),
    @SerialName("update_meta")
    val updateMeta: LedgerPatchMeta? = null,
)

@Serializable
data class EntryPatch(
    @SerialName("section_key")
    val sectionKey: String,
    val entry: RollingSummaryEntry,
)

@Serializable
data class SupersedeEntryPatch(
    @SerialName("section_key")
    val sectionKey: String,
    @SerialName("entry_id")
    val entryId: String,
    val status: String = "superseded",
    val reason: String? = null,
    @SerialName("related_ids")
    val relatedIds: List<String> = emptyList(),
)

@Serializable
data class ChronologyPatch(
    val episode: RollingSummaryChronologyEpisode,
)

@Serializable
data class DetailCapsulePatch(
    val capsule: RollingSummaryDetailCapsule,
)

@Serializable
data class LedgerPatchMeta(
    @SerialName("summary_turn")
    val summaryTurn: Int? = null,
    @SerialName("updated_at")
    val updatedAt: Long? = null,
)

private val PATCHABLE_ENTRY_SECTIONS = setOf(
    "facts",
    "preferences",
    "tasks",
    "decisions",
    "constraints",
    "open_questions",
    "artifacts",
    "timeline",
)

fun parseLedgerPatchDocument(raw: String): LedgerPatchDocument =
    JsonInstant.decodeFromString(raw)

fun applyLedgerPatchDocument(
    base: RollingSummaryDocument,
    patch: LedgerPatchDocument,
    fallbackTurn: Int,
    updatedAt: Instant,
): RollingSummaryDocument {
    var facts = base.facts.toMutableList()
    var preferences = base.preferences.toMutableList()
    var tasks = base.tasks.toMutableList()
    var decisions = base.decisions.toMutableList()
    var constraints = base.constraints.toMutableList()
    var openQuestions = base.openQuestions.toMutableList()
    var artifacts = base.artifacts.toMutableList()
    var timeline = base.timeline.toMutableList()
    var chronology = base.chronology.toMutableList()
    var detailCapsules = base.detailCapsules.toMutableList()

    fun sectionEntries(sectionKey: String): MutableList<RollingSummaryEntry> = when (sectionKey) {
        "facts" -> facts
        "preferences" -> preferences
        "tasks" -> tasks
        "decisions" -> decisions
        "constraints" -> constraints
        "open_questions" -> openQuestions
        "artifacts" -> artifacts
        "timeline" -> timeline
        else -> throw IllegalArgumentException("Unsupported section key for ledger patch: $sectionKey")
    }

    patch.addEntries.forEach { patchEntry ->
        require(patchEntry.sectionKey in PATCHABLE_ENTRY_SECTIONS) {
            "Unsupported add_entries section: ${patchEntry.sectionKey}"
        }
        val normalized = patchEntry.entry.normalized(patchEntry.sectionKey, fallbackTurn)
        val target = sectionEntries(patchEntry.sectionKey)
        if (target.none { it.id == normalized.id }) {
            target += normalized
        }
    }

    patch.updateEntries.forEach { patchEntry ->
        require(patchEntry.sectionKey in PATCHABLE_ENTRY_SECTIONS) {
            "Unsupported update_entries section: ${patchEntry.sectionKey}"
        }
        val normalized = patchEntry.entry.normalized(patchEntry.sectionKey, fallbackTurn)
        val target = sectionEntries(patchEntry.sectionKey)
        val index = target.indexOfFirst { it.id == normalized.id }
        require(index >= 0) {
            "Ledger patch attempted to update missing entry '${normalized.id}' in ${patchEntry.sectionKey}"
        }
        target[index] = normalized
    }

    patch.supersedeEntries.forEach { supersede ->
        require(supersede.sectionKey in PATCHABLE_ENTRY_SECTIONS) {
            "Unsupported supersede_entries section: ${supersede.sectionKey}"
        }
        val target = sectionEntries(supersede.sectionKey)
        val index = target.indexOfFirst { it.id == supersede.entryId }
        require(index >= 0) {
            "Ledger patch attempted to supersede missing entry '${supersede.entryId}' in ${supersede.sectionKey}"
        }
        val existing = target[index]
        target[index] = existing.copy(
            status = supersede.status,
            reason = supersede.reason ?: existing.reason,
            relatedIds = (existing.relatedIds + supersede.relatedIds).distinct(),
        ).normalized(supersede.sectionKey, fallbackTurn)
    }

    patch.appendChronology.forEach { chronologyPatch ->
        val normalized = chronologyPatch.episode.normalized(fallbackTurn)
        val index = chronology.indexOfFirst { it.id == normalized.id }
        if (index >= 0) {
            chronology[index] = normalized
        } else {
            chronology += normalized
        }
    }

    patch.addOrUpdateDetailCapsules.forEach { detailPatch ->
        val normalized = detailPatch.capsule.normalized(fallbackTurn)
        val index = detailCapsules.indexOfFirst { it.id == normalized.id }
        if (index >= 0) {
            detailCapsules[index] = normalized
        } else {
            detailCapsules += normalized
        }
    }

    return RollingSummaryDocument(
        meta = base.meta.copy(
            summaryTurn = patch.updateMeta?.summaryTurn ?: fallbackTurn,
            updatedAt = patch.updateMeta?.updatedAt ?: updatedAt.toEpochMilli(),
        ),
        facts = facts,
        preferences = preferences,
        tasks = tasks,
        decisions = decisions,
        constraints = constraints,
        openQuestions = openQuestions,
        artifacts = artifacts,
        timeline = timeline,
        chronology = chronology,
        detailCapsules = detailCapsules,
    ).withUpdatedMeta(
        summaryTurn = patch.updateMeta?.summaryTurn ?: fallbackTurn,
        updatedAt = updatedAt,
    )
}
