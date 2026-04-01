package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.WorkflowPhase
import me.rerere.rikkahub.data.model.parseRollingSummaryDocument
import me.rerere.rikkahub.utils.JsonInstantPretty

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(
                    buildJsonObject {
                        put("id", memory.id)
                        put("content", memory.content)
                    }
                )
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

internal fun buildDialogueSummaryPrompt(dialogueSummaryText: String): String {
    if (dialogueSummaryText.isBlank()) return ""
    return buildString {
        appendLine()
        append("**Primary Compaction Summary**")
        appendLine()
        append(
            "This is the maintained high-priority continuity summary for the current conversation. " +
                "Treat it as working state, but let newer messages override stale details."
        )
        appendLine()
        append(dialogueSummaryText)
        appendLine()
    }
}

internal fun buildLegacyRollingSummaryPrompt(rollingSummaryJson: String): String {
    if (rollingSummaryJson.isBlank()) return ""
    val summaryProjection = parseRollingSummaryDocument(rollingSummaryJson).toCurrentViewProjection()
    if (summaryProjection.isBlank()) return ""
    return buildString {
        appendLine()
        append("**Legacy Rolling Summary Fallback**")
        appendLine()
        append(
            "This conversation has not been re-compressed into the new primary summary format yet. " +
                "Use this projected legacy summary as fallback background state."
        )
        appendLine()
        append(summaryProjection)
        appendLine()
    }
}

internal fun buildRecallMemoryGuidancePrompt(): String = """
    **Historical Memory Retrieval**
    `recall_memory(query, channel, role)` retrieves structured historical memory for this assistant.
    - Use `channel=current` for still-effective facts, preferences, constraints, tasks, artifacts, and recent compressed context.
    - Use `channel=history` for old versions, change history, and decision evolution.
    - Use `role=user|assistant|any` to focus on user-originated, assistant-originated, or all history.
    If you need the exact original wording after finding relevant history, call `search_source(query, role, candidate_conversation_ids)` and then `read_source(source_ref)`.
""".trimIndent()

internal fun buildKnowledgeBaseGuidancePrompt(): String = """
    **Knowledge Base Retrieval**
    Available tools:
    - `list_knowledge_base_documents()` lists searchable documents with document id, file name, mime type, and chunk count.
    - `search_knowledge_base(query, document_ids?)` finds relevant snippets, optionally inside chosen documents only.
    - `read_knowledge_base_chunks(document_id, chunk_orders)` reads exact chunk numbers from a chosen document.
    Rules:
    - Use this for uploaded manuals, PDFs, notes, reports, specs, and other document knowledge.
    - For broad teaching requests or a new topic, call `list_knowledge_base_documents()` first, then choose a document before searching.
    - When the user already names a document, search inside that document directly.
    - If a hit looks incomplete, truncated, or missing surrounding explanation, call `read_knowledge_base_chunks` before answering.
    - Do not treat truncated snippets as complete source text and do not silently fill in missing textbook wording.
    - Mention the source file name when answering from retrieved snippets.
    - If the search result quality is `weak`, prefer listing documents or narrowing the document scope before continuing.
    - If the tool returns no relevant match, explicitly say the information was not found in the knowledge base.
""".trimIndent()


internal fun buildWorkflowPhasePrompt(phase: WorkflowPhase): String {
    return when (phase) {
        WorkflowPhase.PLAN -> """
**[Workflow Phase: PLAN — READ-ONLY MODE]**
You are currently in the PLAN phase. Your responsibilities:
- Analyze requirements and formulate a detailed execution plan
- Read and examine files, code structure, and project architecture
- Create and update TODO items to outline the work
- Ask clarifying questions if needed

STRICT CONSTRAINTS:
- DO NOT modify, create, write, or delete any files
- DO NOT execute any commands that change system state
- DO NOT run code that has side effects (writing files, installing packages, etc.)
- If you need to show code changes, present them as a plan/diff description ONLY
- Use only read-only tools (reading files, listing directories, searching code)

When your plan is complete, summarize the steps and wait for user confirmation to proceed.
""".trimIndent()

        WorkflowPhase.EXECUTE -> """
**[Workflow Phase: EXECUTE]**
You are in the EXECUTE phase. You have full access to all tools.
- Execute the plan step by step
- Create, modify, and delete files as needed
- Run commands, install dependencies, run tests
- Update TODO items to track progress
- If you encounter issues, fix them or note them for review
""".trimIndent()

        WorkflowPhase.REVIEW -> """
**[Workflow Phase: REVIEW — READ-ONLY MODE]**
You are currently in the REVIEW phase. Your responsibilities:
- Review the work done in the EXECUTE phase
- Check code quality, correctness, security, and best practices
- Read files and examine results of execution
- Run tests and verification commands (read-only)
- Identify issues, bugs, potential improvements
- Update TODO items with review findings

STRICT CONSTRAINTS:
- DO NOT modify, create, write, or delete any files
- DO NOT execute any commands that change system state
- Only use read-only tools for verification
- Present any needed fixes as recommendations, do not apply them directly

When review is complete, summarize findings and wait for user confirmation.
""".trimIndent()
    }
}
