package me.rerere.rikkahub.service

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.countsTowardKeepRecent
import me.rerere.ai.ui.findKeepStartIndexForVisibleMessages
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.buildKnowledgeBaseGuidancePrompt
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_LEDGER_PATCH_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_LEDGER_PROMPT
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.buildListKnowledgeBaseDocumentsTool
import me.rerere.rikkahub.data.ai.tools.buildReadKnowledgeBaseChunksTool
import me.rerere.rikkahub.data.ai.tools.buildReadSourceTool
import me.rerere.rikkahub.data.ai.tools.buildRecallMemoryTool
import me.rerere.rikkahub.data.ai.tools.buildSearchKnowledgeBaseTool
import me.rerere.rikkahub.data.ai.tools.buildSearchSourceTool
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DeliveryOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getEmbeddingModel
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.memory.IndexedSourceMessage
import me.rerere.rikkahub.data.memory.buildMemoryIndexChunks
import me.rerere.rikkahub.data.memory.buildLiveTailSourceDigest
import me.rerere.rikkahub.data.memory.buildSourcePreviewChunks
import me.rerere.rikkahub.data.memory.isWeakSourceResult
import me.rerere.rikkahub.data.memory.parseSourceRef
import me.rerere.rikkahub.data.memory.rankMemoryChunks
import me.rerere.rikkahub.data.memory.rankSourcePreviewChunks
import me.rerere.rikkahub.data.memory.sourceRef
import me.rerere.rikkahub.data.model.applyLedgerPatchDocument
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.ConversationCompressionState
import me.rerere.rikkahub.data.model.LedgerPatchDocument
import me.rerere.rikkahub.data.model.MemoryIndexChunk
import me.rerere.rikkahub.data.model.compressionEventOrder
import me.rerere.rikkahub.data.model.latestCompressionEvent
import me.rerere.rikkahub.data.model.parseLedgerPatchDocument
import me.rerere.rikkahub.data.model.PendingLedgerBatch
import me.rerere.rikkahub.data.model.ScheduledPromptTask
import me.rerere.rikkahub.data.model.ReadSourceResult
import me.rerere.rikkahub.data.model.RecallMemoryChunk
import me.rerere.rikkahub.data.model.RecallMemoryResult
import me.rerere.rikkahub.data.model.SearchSourceCandidate
import me.rerere.rikkahub.data.model.SearchSourceResult
import me.rerere.rikkahub.data.model.SourceDigestMessage
import me.rerere.rikkahub.data.model.SourcePreviewChunk
import me.rerere.rikkahub.data.model.buildLiveTailDigestJson
import me.rerere.rikkahub.data.model.normalizeRollingSummaryJson
import me.rerere.rikkahub.data.model.parseRollingSummaryDocument
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryIndexRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.PendingLedgerBatchRepository
import me.rerere.rikkahub.data.repository.SourcePreviewRepository
import me.rerere.rikkahub.data.skills.SkillsRepository
import me.rerere.rikkahub.data.skills.buildSkillsCatalogPrompt
import me.rerere.rikkahub.sandbox.SandboxEngine
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import java.time.Instant
import kotlin.time.Clock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
internal const val DIALOGUE_SUMMARY_MAX_OUTPUT_TOKENS = 65_536
private const val ROLLING_SUMMARY_MIN_OUTPUT_TOKENS = 1_200
private const val ROLLING_SUMMARY_TARGET_OUTPUT_TOKENS = 2_500
private const val ROLLING_SUMMARY_HARD_CAP_TOKENS = 30_000
private const val ROLLING_SUMMARY_MAX_CHRONOLOGY_ITEMS = 18
private const val ROLLING_SUMMARY_MAX_DETAIL_CAPSULES = 14
private const val RECALL_BM25_TOP_K = 50
private const val RECALL_VECTOR_RERANK_K = 30
private const val RECALL_MAX_RETURN_CHUNKS = 5
private const val RECALL_MAX_INJECT_TOKENS = 2_500
private const val SOURCE_PREVIEW_MAX_RESULTS = 3

internal fun shouldPreservePendingToolNode(tools: List<UIMessagePart.Tool>): Boolean {
    if (tools.isEmpty()) return false
    if (tools.all { it.isExecuted }) return true

    return tools.any { tool ->
        tool.approvalState is ToolApprovalState.Approved ||
            tool.approvalState is ToolApprovalState.Answered ||
            tool.approvalState is ToolApprovalState.Cancelled
    }
}

enum class ChatNoticeKind {
    ERROR,
    SUCCESS,
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val kind: ChatNoticeKind = ChatNoticeKind.ERROR,
)

data class ScheduledTaskExecutionResult(
    val replyPreview: String,
    val replyText: String,
    val modelId: Uuid?,
    val providerName: String,
)

enum class CompressionUiPhase {
    Compressing,
    Indexing,
}

data class CompressionUiState(
    val conversationId: Uuid,
    val trigger: String,
    val phase: CompressionUiPhase,
)

data class LedgerGenerationUiState(
    val conversationId: Uuid,
    val trigger: String,
)

enum class CompressionRegenerationTarget {
    DialogueSummary,
    MemoryLedger,
}

private data class CompressionBudget(
    val incrementalInputTokens: Int,
    val minOutputTokens: Int,
    val targetOutputTokens: Int,
    val hardCapTokens: Int,
    val minChronologyItems: Int,
    val minDetailCapsules: Int,
)

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
        DeliveryOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryIndexRepository: MemoryIndexRepository,
    private val pendingLedgerBatchRepository: PendingLedgerBatchRepository,
    private val sourcePreviewRepository: SourcePreviewRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val skillsRepository: SkillsRepository,
    private val knowledgeBaseService: KnowledgeBaseService,
) {
    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)
    private val lastStreamingSaveAt = ConcurrentHashMap<Uuid, Long>()
    private val latestStreamingSnapshots = ConcurrentHashMap<Uuid, Conversation>()
    private val streamingSnapshotJobs = ConcurrentHashMap<Uuid, Job>()
    private val streamingSnapshotMutexes = ConcurrentHashMap<Uuid, Mutex>()
    private val foregroundGenerationIds = ConcurrentHashMap.newKeySet<Uuid>()

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    private val _compressionUiStates = MutableStateFlow<Map<Uuid, CompressionUiState>>(emptyMap())
    private val _ledgerGenerationUiStates = MutableStateFlow<Map<Uuid, LedgerGenerationUiState>>(emptyMap())
    private val _compressionWorkerJobs = MutableStateFlow<Map<Uuid, Job?>>(emptyMap())
    private val _compressionScrollEvents = MutableSharedFlow<Pair<Uuid, Long>>(extraBufferCapacity = 8)
    val compressionScrollEvents: SharedFlow<Pair<Uuid, Long>> = _compressionScrollEvents.asSharedFlow()

    fun addError(error: Throwable, conversationId: Uuid? = null, title: String? = null) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId) }
    }

    fun addSuccessNotice(message: String, conversationId: Uuid? = null, title: String? = null) {
        _errors.update {
            it + ChatError(
                title = title,
                error = IllegalStateException(message),
                conversationId = conversationId,
                kind = ChatNoticeKind.SUCCESS,
            )
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    suspend fun executeScheduledTask(task: ScheduledPromptTask): Result<ScheduledTaskExecutionResult> = runCatching {
        if (task.prompt.isBlank()) {
            throw BadRequestException("Scheduled prompt cannot be blank")
        }

        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(task.assistantId)
            ?: throw NotFoundException("Assistant not found: ${task.assistantId}")
        val maxSearchIndex = (settings.searchServices.size - 1).coerceAtLeast(0)
        val effectiveSearchServiceIndex = task.overrideSearchServiceIndex?.let { index ->
            if (settings.searchServices.isEmpty()) {
                settings.searchServiceSelected
            } else {
                index.coerceIn(0, maxSearchIndex)
            }
        } ?: settings.searchServiceSelected.coerceIn(0, maxSearchIndex)
        val effectiveSettings = settings.copy(
            enableWebSearch = task.overrideEnableWebSearch ?: settings.enableWebSearch,
            searchServiceSelected = effectiveSearchServiceIndex
        )
        val effectiveAssistant = assistant.copy(
            chatModelId = task.overrideModelId ?: assistant.chatModelId,
            localTools = task.overrideLocalTools ?: assistant.localTools,
            mcpServers = task.overrideMcpServers ?: assistant.mcpServers
        )
        val model = effectiveAssistant.chatModelId?.let { effectiveSettings.findModelById(it) }
            ?: effectiveSettings.getCurrentChatModel()
            ?: throw IllegalStateException("No model configured for scheduled task")
        val provider = model.findProvider(effectiveSettings.providers)
            ?: throw IllegalStateException("Provider not found for model: ${model.id}")

        val promptText = task.prompt.replaceRegexes(
            assistant = effectiveAssistant,
            scope = AssistantAffectScope.USER,
            visual = false
        )
        val messages = buildList {
            addAll(effectiveAssistant.presetMessages)
            add(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text(promptText))
                )
            )
        }

        val sandboxId = task.id
        if (effectiveAssistant.localTools.contains(LocalToolOption.Container)) {
            skillsRepository.refresh()
        }
        val kbToolAvailable = knowledgeBaseService.isSearchToolAvailable(
            assistant = effectiveAssistant,
            model = model,
            settings = effectiveSettings,
        )
        val knowledgeBasePrompt = if (kbToolAvailable) {
            buildKnowledgeBaseGuidancePrompt()
        } else {
            ""
        }
        val skillPrompt = buildSkillsCatalogPrompt(
            assistant = effectiveAssistant,
            model = model,
            catalog = skillsRepository.state.value,
        )?.trim().orEmpty()
        val baseSystemPrompt = effectiveAssistant.systemPrompt.trim()
        val mergedSystemPrompt = listOf(
            baseSystemPrompt,
            skillPrompt,
            knowledgeBasePrompt.trim(),
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        val assistantForGeneration = if (mergedSystemPrompt == baseSystemPrompt) {
            effectiveAssistant
        } else {
            effectiveAssistant.copy(
                systemPrompt = mergedSystemPrompt
            )
        }

        val availableMcpTools = mcpManager.getAvailableToolsForServers(assistantForGeneration.mcpServers)
        val mcpWrappedTools = availableMcpTools.map { tool ->
            Tool(
                name = "mcp__${tool.name}",
                description = tool.description ?: "",
                parameters = { tool.inputSchema },
                needsApproval = tool.needsApproval,
                execute = {
                    mcpManager.callToolFromServers(
                        serverIds = assistantForGeneration.mcpServers,
                        toolName = tool.name,
                        args = it.jsonObject
                    )
                },
            )
        }

        var generatedMessages = messages
        generationHandler.generateText(
            settings = effectiveSettings,
            model = model,
            messages = messages,
            assistant = assistantForGeneration,
            memories = if (assistantForGeneration.useGlobalMemory) {
                memoryRepository.getGlobalMemories()
            } else {
                memoryRepository.getMemoriesOfAssistant(assistantForGeneration.id.toString())
            },
            inputTransformers = buildList {
                addAll(inputTransformers)
                add(templateTransformer)
            },
            outputTransformers = outputTransformers,
            tools = buildList {
                if (effectiveSettings.enableWebSearch) {
                    addAll(createSearchTools(context, effectiveSettings))
                }
                if (assistantForGeneration.enableRecentChatsReference) {
                    add(
                        buildRecallMemoryTool(json = JsonInstant) { query, channel, role ->
                            recallMemory(
                                assistantId = assistantForGeneration.id,
                                query = query,
                                channel = channel,
                                role = role
                            )
                        }
                    )
                    add(
                        buildSearchSourceTool(json = JsonInstant) { query, role, candidateConversationIds ->
                            searchSource(
                                assistantId = assistantForGeneration.id,
                                query = query,
                                role = role,
                                candidateConversationIds = candidateConversationIds
                            )
                        }
                    )
                    add(
                        buildReadSourceTool(json = JsonInstant) { sourceRef ->
                            readSource(
                                assistantId = assistantForGeneration.id,
                                sourceRef = sourceRef
                            )
                        }
                    )
                }
                if (kbToolAvailable) {
                    add(
                        buildListKnowledgeBaseDocumentsTool(json = JsonInstant) {
                            knowledgeBaseService.listKnowledgeBaseDocuments(assistantForGeneration.id)
                        }
                    )
                    add(
                        buildSearchKnowledgeBaseTool(json = JsonInstant) { query, documentIds ->
                            knowledgeBaseService.searchKnowledgeBase(
                                assistantId = assistantForGeneration.id,
                                query = query,
                                documentIds = documentIds,
                            )
                        }
                    )
                    add(
                        buildReadKnowledgeBaseChunksTool(json = JsonInstant) { documentId, chunkOrders ->
                            knowledgeBaseService.readKnowledgeBaseChunks(
                                assistantId = assistantForGeneration.id,
                                documentId = documentId,
                                chunkOrders = chunkOrders,
                            )
                        }
                    )
                }
                addAll(
                    localTools.getTools(
                        options = assistantForGeneration.localTools,
                        sandboxId = sandboxId,
                        enabledSkills = assistantForGeneration.enabledSkills,
                        settings = effectiveSettings,
                        parentModel = model,
                        subAgents = me.rerere.rikkahub.data.model.SubAgentTemplates.All,
                        mcpTools = mcpWrappedTools,
                    )
                )
                // ✅ MCP 工具在所有阶段都可用（包括只读阶段，因为 MCP 可能包含只读工具如搜索）
                addAll(mcpWrappedTools)
            },
        ).collect { chunk ->
            when (chunk) {
                is GenerationChunk.Messages -> {
                    generatedMessages = chunk.messages
                }
            }
        }

        val finalMessage = generatedMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: throw IllegalStateException("Scheduled task did not generate an assistant reply")
        val replyText = finalMessage.toText().trim()
        if (replyText.isBlank()) {
            throw IllegalStateException("Scheduled task generated an empty reply")
        }

        ScheduledTaskExecutionResult(
            replyPreview = replyText.take(200),
            replyText = replyText.take(20_000),
            modelId = model.id,
            providerName = provider.name
        )
    }

    fun getCompressionUiStateFlow(conversationId: Uuid): Flow<CompressionUiState?> {
        return _compressionUiStates.map { it[conversationId] }
    }

    private fun updateCompressionUiState(conversationId: Uuid, state: CompressionUiState?) {
        _compressionUiStates.update { current ->
            if (state == null) current - conversationId else current + (conversationId to state)
        }
    }

    fun getLedgerGenerationUiStateFlow(conversationId: Uuid): Flow<LedgerGenerationUiState?> {
        return _ledgerGenerationUiStates.map { it[conversationId] }
    }

    private fun updateLedgerGenerationUiState(conversationId: Uuid, state: LedgerGenerationUiState?) {
        _ledgerGenerationUiStates.update { current ->
            if (state == null) current - conversationId else current + (conversationId to state)
        }
    }

    private fun updateCompressionWorkerJob(conversationId: Uuid, job: Job?) {
        _compressionWorkerJobs.update { current ->
            if (job == null) current - conversationId else current + (conversationId to job)
        }
    }

    fun cancelCompressionWork(conversationId: Uuid) {
        _compressionWorkerJobs.value[conversationId]?.cancel()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> {
                _isForeground.value = false
                // Going to background must keep generation alive. Persist the latest streamed
                // state for process-death recovery, but do not mutate pending tools into an
                // interrupted/cancelled result while the service job may continue running.
                runCatching {
                    runBlocking {
                        sessions.keys.toList().forEach { conversationId ->
                            preserveGenerationSnapshot(
                                conversationId = conversationId,
                                markAssistantFinished = false,
                                force = true,
                            )
                        }
                    }
                }.onFailure {
                    Log.e(TAG, "Failed to persist generation snapshot on app background", it)
                }
            }
            else -> {}
        }
    }

    init {
        // 添加生命周期观察者
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        runBlocking {
            sessions.keys.toList().forEach { conversationId ->
                flushStreamingSnapshot(conversationId)
                preserveGenerationSnapshot(
                    conversationId = conversationId,
                    markAssistantFinished = false,
                    force = true
                )
            }
        }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
        lastStreamingSaveAt.clear()
        latestStreamingSnapshots.clear()
        streamingSnapshotJobs.values.forEach { it.cancel() }
        streamingSnapshotJobs.clear()
        streamingSnapshotMutexes.clear()
        foregroundGenerationIds.clear()
        runCatching { RikkaHubForegroundService.stopService(context) }
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    fun countUncompressedVisibleMessages(conversation: Conversation): Int {
        val startIndex = (conversation.compressionState.lastCompressedMessageIndex + 1).coerceAtLeast(0)
        return conversation.currentMessages
            .drop(startIndex)
            .count { it.countsTowardKeepRecent() }
    }

    fun estimateCurrentPromptTokens(conversation: Conversation): Int {
        val settings = settingsStore.settingsFlow.value
        val model = settings.getCurrentChatModel()
        val provider = model?.findProvider(settings.providers)
        return estimatePromptTokenUsage(
            conversation = conversation,
            charsPerToken = settings.tokenEstimatorCharsPerToken,
            sendReasoningContent = provider?.sendReasoningContent == true,
        )
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            val activeGeneration = hasActiveGeneration(conversationId)
            val restoredConversation = if (activeGeneration) {
                // Switching pages must not be treated as process death. If this process still owns
                // an active generation/tool job, do not replace live in-memory state with a stale
                // database snapshot.
                getConversationFlow(conversationId).value
            } else {
                conversation.toInterruptedSnapshotIfStaleAfterProcessDeath()
            }
            if (!activeGeneration) {
                updateConversation(conversationId, restoredConversation)
                if (restoredConversation !== conversation) {
                    persistConversationSnapshot(conversationId, restoredConversation)
                }
            }
            settingsStore.updateAssistant(restoredConversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        val processedContent = preprocessUserInputParts(content)

        val job = appScope.launch {
            try {
                val currentConversation = session.state.value

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId, autoResendUserMessage = true)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job)
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>): List<UIMessagePart> {
        val assistant = settingsStore.settingsFlow.value.getCurrentAssistant()
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        inputOverride: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.copy(
                        messages = node.messages.map { msg ->
                            msg.copy(
                                parts = msg.parts.map { part ->
                                    when {
                                        part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                            part.copy(
                                                input = inputOverride ?: part.input,
                                                approvalState = newApprovalState,
                                            )
                                        }

                                        else -> part
                                    }
                                }
                            )
                        }
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    private fun startGenerationForegroundGuard(conversationId: Uuid) {
        val wasEmpty = foregroundGenerationIds.isEmpty()
        foregroundGenerationIds.add(conversationId)
        if (wasEmpty) {
            runCatching { RikkaHubForegroundService.startService(context) }
                .onFailure { Log.w(TAG, "Failed to start generation foreground service", it) }
        }
    }

    private fun stopGenerationForegroundGuard(conversationId: Uuid) {
        foregroundGenerationIds.remove(conversationId)
        if (foregroundGenerationIds.isEmpty()) {
            runCatching { RikkaHubForegroundService.stopService(context) }
                .onFailure { Log.w(TAG, "Failed to stop generation foreground service", it) }
        }
    }

    private fun Throwable.isRecoverableStreamAbort(appInForeground: Boolean): Boolean {
        if (this is CancellationException) return false
        val text = buildString {
            append(this@isRecoverableStreamAbort::class.java.name)
            append(' ')
            append(message.orEmpty())
            append('\n')
            append(stackTraceToString())
        }
        val explicitStreamAbort = this is java.io.EOFException ||
            text.contains("Software caused connection abort", ignoreCase = true) ||
            text.contains("connection abort", ignoreCase = true) ||
            text.contains("connection reset", ignoreCase = true) ||
            text.contains("stream was reset", ignoreCase = true) ||
            text.contains("unexpected end of stream", ignoreCase = true) ||
            text.contains("Http2Reader.nextFrame", ignoreCase = true)
        val backgroundSocketAbort = !appInForeground &&
            (this is java.net.SocketException || this is java.net.SocketTimeoutException)
        return explicitStreamAbort || backgroundSocketAbort
    }

    private fun Settings.autoResendFailureMatchers(): List<String> =
        autoResendUserMessageFailureMatchers
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

    private fun Throwable.matchesAutoResendFailure(settings: Settings): Boolean {
        val matchers = settings.autoResendFailureMatchers()
        if (matchers.isEmpty()) return false
        val text = buildString {
            append(message.orEmpty())
            append('\n')
            append(this@matchesAutoResendFailure::class.java.name)
            append('\n')
            append(stackTraceToString())
        }
        return matchers.any { matcher -> text.contains(matcher, ignoreCase = true) }
    }

    private fun Conversation.hasToolActivityFromNode(startIndex: Int): Boolean {
        if (startIndex < 0) return true
        return messageNodes.drop(startIndex).any { node ->
            node.messages.any { message ->
                message.role == MessageRole.TOOL || message.getTools().isNotEmpty()
            }
        }
    }

    private fun hasActiveGeneration(conversationId: Uuid): Boolean {
        return sessions[conversationId]?.isGenerating == true ||
            foregroundGenerationIds.contains(conversationId) ||
            streamingSnapshotJobs.containsKey(conversationId) ||
            latestStreamingSnapshots.containsKey(conversationId)
    }

    private suspend fun appendAutoContinueAfterToolFailureMessage(
        conversationId: Uuid,
        settings: Settings,
    ) {
        val continueText = settings.autoContinueAfterToolFailureMessage.trim().ifBlank { "继续" }
        val current = getConversationFlow(conversationId).value
        val preserved = current.toPreservedGenerationSnapshot(markAssistantFinished = true)
        // The suppression flag only covers the race window before this USER continue message is
        // appended. Once the last message is USER, WorkflowAutoContinue cannot duplicate it, so
        // clear the flag to let the assistant response generated from this continue message be
        // evaluated normally by workflow auto-continue.
        val updatedWorkflowState = preserved.workflowState?.copy(suppressNextAutoContinue = false)
        val updatedConversation = preserved.copy(
            workflowState = updatedWorkflowState,
            messageNodes = preserved.messageNodes + UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(continueText)),
            ).toMessageNode(),
            updateAt = Instant.now(),
        )
        saveConversation(conversationId, updatedConversation)
        handleMessageComplete(
            conversationId = conversationId,
            messageRange = null,
            autoResendUserMessage = false,
        )
    }

    private data class GenerationMessageSelection(
        val messages: List<UIMessage>,
        val writeBackStartIndex: Int,
    )

    private fun selectMessagesForGenerationAfterCompression(conversation: Conversation): GenerationMessageSelection {
        val currentMessages = conversation.currentMessages
        if (currentMessages.isEmpty()) {
            throw IllegalStateException("Conversation has no messages to generate from")
        }
        val compressedUntil = conversation.compressionState.lastCompressedMessageIndex
            .coerceAtLeast(-1)
            .coerceAtMost(currentMessages.lastIndex)
        val hasSummary = conversation.compressionState.hasSummary && compressedUntil >= 0
        if (!hasSummary) {
            return GenerationMessageSelection(currentMessages, 0)
        }
        val tailStart = (compressedUntil + 1).coerceIn(0, currentMessages.size)
        val tail = currentMessages.drop(tailStart)
        if (tail.isNotEmpty()) {
            return GenerationMessageSelection(tail, tailStart)
        }

        val fallbackStart = currentMessages.indexOfLast { message ->
            message.role == MessageRole.USER && message.countsTowardKeepRecent()
        }.takeIf { it >= 0 } ?: currentMessages.indexOfLast { it.countsTowardKeepRecent() }
        if (fallbackStart < 0) {
            throw IllegalStateException("Compressed conversation has no active message tail. Please undo/regenerate compression or send a new message.")
        }
        return GenerationMessageSelection(
            messages = currentMessages.drop(fallbackStart),
            writeBackStartIndex = fallbackStart,
        )
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        autoResendUserMessage: Boolean = false,
        autoResendAttempt: Int = 0
    ) {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.getCurrentChatModel() ?: return
        var promptCharsForCalibration = 0
        val retryBaseNodeCount = if (messageRange == null && autoResendUserMessage) {
            getConversationFlow(conversationId).value.messageNodes.size
        } else {
            -1
        }
        startGenerationForegroundGuard(conversationId)

        val generationResult = runCatching {
            var conversation = getConversationFlow(conversationId).value
            val assistant = settings.getCurrentAssistant()
            val hasKnowledgeBaseDocuments = assistant.enableKnowledgeBaseTool &&
                knowledgeBaseService.hasDocuments(assistant.id)

            // reset suggestions
            updateConversation(conversationId, conversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                val toolRequired = settings.enableWebSearch ||
                    assistant.enableMemory ||
                    assistant.enableRecentChatsReference ||
                    hasKnowledgeBaseDocuments ||
                    assistant.enabledSkills.isNotEmpty() ||
                    assistant.localTools.isNotEmpty() ||
                    mcpManager.getAllAvailableTools().isNotEmpty()
                if (toolRequired) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            conversation = getConversationFlow(conversationId).value

            // If container tool is enabled, import current user documents into sandbox first.
            if (assistant.localTools.contains(LocalToolOption.Container)) {
                importDocumentsToSandbox(conversation, conversationId.toString())
            }

            if (messageRange == null && settings.autoCompressEnabled) {
                val nextSendPromptTokens = estimatePromptTokenUsage(
                    conversation = conversation,
                    charsPerToken = settings.tokenEstimatorCharsPerToken,
                    sendReasoningContent = model.findProvider(settings.providers)?.sendReasoningContent == true,
                )
                val contextLimit = model.contextSize
                val triggerTokens = contextLimit?.let { limit ->
                    (limit * (settings.autoCompressTriggerTokens.coerceIn(1, 100) / 100.0)).toInt()
                }
                if (triggerTokens != null && nextSendPromptTokens >= triggerTokens) {
                    runCatching {
                        val compressMessageCount = settings.manualCompressKeepRecentMessages.coerceAtLeast(1)
                        compressConversationByMessageCount(
                            conversationId = conversationId,
                            conversation = conversation,
                            additionalPrompt = "",
                            compressMessageCount = compressMessageCount,
                            generateMemoryLedger = true,
                            trigger = "auto-threshold",
                        ).getOrThrow()
                    }.onFailure { error ->
                        addError(
                            error,
                            conversationId = conversationId,
                            title = context.getString(R.string.error_title_compress_conversation)
                        )
                    }
                    conversation = getConversationFlow(conversationId).value
                }
            }

            val messagesForGeneration: List<UIMessage>
            val generationWriteBackStartIndex: Int
            val dialogueSummaryTextForGeneration: String
            val legacyRollingSummaryJsonForGeneration: String
            if (messageRange != null) {
                generationWriteBackStartIndex = messageRange.start
                messagesForGeneration = conversation.currentMessages
                    .subList(messageRange.start, messageRange.endInclusive + 1)
                dialogueSummaryTextForGeneration = ""
                legacyRollingSummaryJsonForGeneration = ""
            } else {
                val selection = selectMessagesForGenerationAfterCompression(conversation)
                messagesForGeneration = selection.messages
                generationWriteBackStartIndex = selection.writeBackStartIndex
                dialogueSummaryTextForGeneration = conversation.compressionState.dialogueSummaryText
                legacyRollingSummaryJsonForGeneration = conversation.compressionState.rollingSummaryJson
            }
            promptCharsForCalibration = estimatePromptCharCount(
                messages = messagesForGeneration,
                dialogueSummaryText = dialogueSummaryTextForGeneration,
                legacyRollingSummaryJson = legacyRollingSummaryJsonForGeneration,
            )

            if (assistant.localTools.contains(LocalToolOption.Container)) {
                skillsRepository.refresh()
            }
            val kbToolAvailable = knowledgeBaseService.isSearchToolAvailable(
                assistant = assistant,
                model = model,
                settings = settings,
            )
            val knowledgeBasePrompt = if (kbToolAvailable) {
                buildKnowledgeBaseGuidancePrompt()
            } else {
                ""
            }
            val skillPrompt = buildSkillsCatalogPrompt(
                assistant = assistant,
                model = model,
                catalog = skillsRepository.state.value,
            )?.trim().orEmpty()
            val baseSystemPrompt = assistant.systemPrompt.trim()
            val mergedSystemPrompt = listOf(
                baseSystemPrompt,
                skillPrompt,
                knowledgeBasePrompt.trim(),
            ).filter { it.isNotBlank() }.joinToString("\n\n")
            val assistantForGeneration = if (mergedSystemPrompt == baseSystemPrompt) {
                assistant
            } else {
                assistant.copy(
                    systemPrompt = mergedSystemPrompt
                )
            }

            val availableMcpTools = mcpManager.getAllAvailableTools()
            val mcpWrappedTools = availableMcpTools.map { tool ->
                Tool(
                    name = "mcp__" + tool.name,
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = tool.needsApproval,
                    execute = {
                        listOf(
                            UIMessagePart.Text(
                                mcpManager.callTool(tool.name, it.jsonObject).toString()
                            )
                        )
                    },
                )
            }

            // start generating
            generationHandler.generateText(
                settings = settings,
                model = model,
                messages = messagesForGeneration,
                assistant = assistantForGeneration,
                memories = if (assistantForGeneration.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistantForGeneration.id.toString())
                },
                dialogueSummaryText = dialogueSummaryTextForGeneration,
                legacyRollingSummaryJson = legacyRollingSummaryJsonForGeneration,
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (settings.enableWebSearch) {
                        addAll(createSearchTools(context, settings))
                    }
                    if (assistantForGeneration.enableRecentChatsReference) {
                        add(
                            buildRecallMemoryTool(json = JsonInstant) { query, channel, role ->
                                recallMemory(
                                    assistantId = assistantForGeneration.id,
                                    query = query,
                                    channel = channel,
                                    role = role
                                )
                            }
                        )
                        add(
                            buildSearchSourceTool(json = JsonInstant) { query, role, candidateConversationIds ->
                                searchSource(
                                    assistantId = assistantForGeneration.id,
                                    query = query,
                                    role = role,
                                    candidateConversationIds = candidateConversationIds
                                )
                            }
                        )
                        add(
                            buildReadSourceTool(json = JsonInstant) { sourceRef ->
                                readSource(
                                    assistantId = assistantForGeneration.id,
                                    sourceRef = sourceRef
                                )
                            }
                        )
                    }
                    if (kbToolAvailable) {
                        add(
                            buildListKnowledgeBaseDocumentsTool(json = JsonInstant) {
                                knowledgeBaseService.listKnowledgeBaseDocuments(assistantForGeneration.id)
                            }
                        )
                        add(
                            buildSearchKnowledgeBaseTool(json = JsonInstant) { query, documentIds ->
                                knowledgeBaseService.searchKnowledgeBase(
                                    assistantId = assistantForGeneration.id,
                                    query = query,
                                    documentIds = documentIds,
                                )
                            }
                        )
                        add(
                            buildReadKnowledgeBaseChunksTool(json = JsonInstant) { documentId, chunkOrders ->
                                knowledgeBaseService.readKnowledgeBaseChunks(
                                    assistantId = assistantForGeneration.id,
                                    documentId = documentId,
                                    chunkOrders = chunkOrders,
                                )
                            }
                        )
                    }
                    addAll(
                        localTools.getTools(
                            options = assistantForGeneration.localTools,
                            sandboxId = conversationId,
                            enabledSkills = assistantForGeneration.enabledSkills,
                            workflowStateProvider = {
                                getConversationFlow(conversationId).value.workflowState
                            },
                            onWorkflowStateUpdate = { newWorkflowState ->
                                val currentConversation = getConversationFlow(conversationId).value
                                updateConversation(
                                    conversationId,
                                    currentConversation.copy(workflowState = newWorkflowState)
                                )
                            },
                            todoStateProvider = {
                                val currentConversation = getConversationFlow(conversationId).value
                                currentConversation.todoState ?: if (
                                    assistantForGeneration.localTools.contains(
                                        me.rerere.rikkahub.data.ai.tools.LocalToolOption.WorkflowTodo
                                    )
                                ) {
                                    val newTodoState = me.rerere.rikkahub.data.model.TodoState(isEnabled = true)
                                    updateConversation(
                                        conversationId,
                                        currentConversation.copy(todoState = newTodoState)
                                    )
                                    newTodoState
                                } else {
                                    null
                                }
                            },
                            onTodoStateUpdate = { newTodoState ->
                                val currentConversation = getConversationFlow(conversationId).value
                                updateConversation(
                                    conversationId,
                                    currentConversation.copy(todoState = newTodoState)
                                )
                            },
                            subAgents = me.rerere.rikkahub.data.model.SubAgentTemplates.All,
                            settings = settings,
                            parentModel = model,
                            parentWorkflowPhase = conversation.workflowState?.phase,
                            mcpTools = mcpWrappedTools,
                        )
                    )
                    // ✅ MCP 工具在所有阶段都可用（包括只读阶段，因为 MCP 可能包含只读工具如搜索）
                    addAll(mcpWrappedTools)
                },
            ).onCompletion {
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // Show notification if app is not in foreground
                if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration) {
                    sendGenerationDoneNotification(conversationId)
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(
                                messages = chunk.messages,
                                startIndex = generationWriteBackStartIndex
                            )
                        updateConversation(conversationId, updatedConversation)
                        saveStreamingSnapshotIfDue(conversationId, updatedConversation)

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, chunk.messages)
                        }
                    }
                }
            }
        }

        generationResult.onFailure { error ->
            stopGenerationForegroundGuard(conversationId)
            cancelLiveUpdateNotification(conversationId)

            error.printStackTrace()
            Logging.log(TAG, "handleMessageComplete: $error")
            Logging.log(TAG, error.stackTraceToString())

            flushStreamingSnapshot(conversationId)

            if (error is CancellationException) {
                // Page switches, background transitions, and normal coroutine cancellation must
                // not mutate pending tool calls into TOOL_EXECUTION_INTERRUPTED. User stop is
                // handled explicitly by stopGeneration().
                preserveGenerationSnapshot(
                    conversationId = conversationId,
                    markAssistantFinished = false,
                    force = true
                )
                return@onFailure
            }

            val latestConversation = getConversationFlow(conversationId).value
            val canAutoResendUserMessage = autoResendUserMessage &&
                messageRange == null &&
                settings.autoResendUserMessageMaxAttempts > 0 &&
                autoResendAttempt < settings.autoResendUserMessageMaxAttempts &&
                error.matchesAutoResendFailure(settings) &&
                !latestConversation.hasToolActivityFromNode(retryBaseNodeCount)
            if (canAutoResendUserMessage) {
                preserveGenerationSnapshot(
                    conversationId = conversationId,
                    markAssistantFinished = false,
                    force = true
                )
                val retryConversation = getConversationFlow(conversationId).value.let { current ->
                    if (retryBaseNodeCount in 1..current.messageNodes.size) {
                        current.copy(
                            messageNodes = current.messageNodes.take(retryBaseNodeCount),
                            updateAt = Instant.now()
                        )
                    } else {
                        current
                    }
                }
                updateConversation(conversationId, retryConversation)
                persistConversationSnapshot(conversationId, retryConversation)
                val waitSeconds = settings.autoResendUserMessageIntervalSeconds.coerceAtLeast(1)
                Logging.log(
                    TAG,
                    "auto resend user message: attempt ${autoResendAttempt + 1}/${settings.autoResendUserMessageMaxAttempts}, wait=${waitSeconds}s, error=${error.message}"
                )
                delay(waitSeconds * 1000L)
                handleMessageComplete(
                    conversationId = conversationId,
                    messageRange = null,
                    autoResendUserMessage = true,
                    autoResendAttempt = autoResendAttempt + 1
                )
                return@onFailure
            }

            val hasToolActivity = latestConversation.hasToolActivityFromNode(retryBaseNodeCount)
            val canAutoContinueAfterToolFailure = autoResendUserMessage &&
                messageRange == null &&
                settings.autoContinueAfterToolFailureEnabled &&
                error.matchesAutoResendFailure(settings) &&
                hasToolActivity
            if (canAutoContinueAfterToolFailure) {
                val waitSeconds = settings.autoResendUserMessageIntervalSeconds.coerceAtLeast(1)
                preserveGenerationSnapshot(
                    conversationId = conversationId,
                    markAssistantFinished = true,
                    force = true
                )
                // Set the workflow suppression flag before the delay/auto-continue message append.
                // This closes the race where generationDoneFlow reaches the UI while the last
                // visible message is still ASSISTANT, causing WorkflowAutoContinue to send another
                // "继续" before the failure auto-continue appends its own USER message.
                val suppressedConversation = getConversationFlow(conversationId).value.let { current ->
                    current.copy(
                        workflowState = current.workflowState?.copy(suppressNextAutoContinue = true),
                        updateAt = Instant.now(),
                    )
                }
                saveConversation(conversationId, suppressedConversation)
                Logging.log(
                    TAG,
                    "auto continue after tool failure: wait=${waitSeconds}s, message=${settings.autoContinueAfterToolFailureMessage.ifBlank { "继续" }}, error=${error.message}"
                )
                delay(waitSeconds * 1000L)
                appendAutoContinueAfterToolFailureMessage(conversationId, settings)
                return@onFailure
            }

            if (error.isRecoverableStreamAbort(appInForeground = isForeground.value)) {
                // Android/Doze/NAT/proxy may abort a long SSE/HTTP2 socket while the app is in the
                // background. Preserve partial assistant text, but close any unfinished tool calls
                // with an explicit interrupted result so the UI and future context do not treat
                // them as still-running/pending tools.
                preserveInterruptedToolGenerationSnapshot(
                    conversationId = conversationId,
                    error = "Network stream was interrupted while the app was backgrounded; partial output was preserved.",
                    errorCode = "NETWORK_STREAM_INTERRUPTED",
                    reason = "Network stream interrupted while app was backgrounded",
                    markAssistantFinished = false,
                    updateSessionState = false,
                )
                addError(
                    IllegalStateException(
                        "Network stream was interrupted while the app was backgrounded; partial output was preserved. You can continue or retry generation.",
                        error
                    ),
                    conversationId,
                    title = context.getString(R.string.error_title_generation)
                )
                return@onFailure
            }

            addError(error, conversationId, title = context.getString(R.string.error_title_generation))

            // Preserve latest assistant text, but do not turn provider/network failures into
            // synthetic tool-call interruption output. Pending tools should remain recoverable
            // unless the user explicitly cancels or the process actually dies.
            preserveGenerationSnapshot(
                conversationId = conversationId,
                markAssistantFinished = true,
                force = false
            )
        }

        generationResult.onSuccess {
            stopGenerationForegroundGuard(conversationId)
            flushStreamingSnapshot(conversationId)
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)
            calibrateTokenEstimator(
                promptChars = promptCharsForCalibration,
                actualPromptTokens = finalConversation.currentMessages.lastOrNull()?.usage?.promptTokens ?: 0
            )

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
            lastStreamingSaveAt.remove(conversationId)
        }
    }

    private suspend fun preserveGenerationSnapshot(
        conversationId: Uuid,
        markAssistantFinished: Boolean,
        force: Boolean = false,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val preservedConversation = currentConversation.toPreservedGenerationSnapshot(markAssistantFinished)
        if (force) {
            persistConversationSnapshot(conversationId, preservedConversation)
        } else {
            saveConversation(conversationId, preservedConversation)
        }
        lastStreamingSaveAt[conversationId] = System.currentTimeMillis()
    }

    private fun Conversation.toPreservedGenerationSnapshot(markAssistantFinished: Boolean): Conversation {
        val now = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
        return copy(
            messageNodes = messageNodes.mapIndexed { index, node ->
                node.copy(
                    messages = node.messages.mapIndexed { messageIndex, message ->
                        val selectedMessage = messageIndex == node.selectIndex
                        val lastNode = index == messageNodes.lastIndex
                        val shouldFinishMessage = markAssistantFinished && selectedMessage && lastNode &&
                            message.role == MessageRole.ASSISTANT &&
                            message.finishedAt == null
                        val finishedMessage = if (shouldFinishMessage) {
                            message.copy(finishedAt = now)
                        } else {
                            message
                        }
                        finishedMessage.finishReasoning()
                    }
                )
            },
            updateAt = Instant.now()
        )
    }

    private fun Conversation.toInterruptedSnapshotIfStaleAfterProcessDeath(): Conversation {
        val selectedMessages = messageNodes.mapNotNull { node ->
            node.messages.getOrNull(node.selectIndex)
        }
        val hasUnfinishedAssistant = selectedMessages.any { message ->
            message.role == MessageRole.ASSISTANT && message.finishedAt == null
        }
        val hasUnfinishedTool = selectedMessages.any { message ->
            message.getTools().any { tool -> !tool.isExecuted }
        }
        if (!hasUnfinishedAssistant && !hasUnfinishedTool) return this
        return toInterruptedToolGenerationSnapshot(
            error = "Tool execution was interrupted because the app process stopped before completion",
            errorCode = "TOOL_EXECUTION_INTERRUPTED",
            reason = "App process stopped",
            markAssistantFinished = true,
        )
    }

    private fun saveStreamingSnapshotIfDue(conversationId: Uuid, conversation: Conversation) {
        // Keep every latest streamed state available for process-death/user-stop recovery, but
        // do not block stream collection on a Room write for every token. The provider callback
        // flow is fed by OkHttp's SSE thread; if the collector is slowed by synchronous DB writes,
        // stream chunks can be dropped before they are merged into the assistant message.
        latestStreamingSnapshots[conversationId] =
            conversation.toPreservedGenerationSnapshot(markAssistantFinished = false)
        lastStreamingSaveAt[conversationId] = System.currentTimeMillis()
        ensureStreamingSnapshotWriter(conversationId)
    }

    private fun ensureStreamingSnapshotWriter(conversationId: Uuid) {
        streamingSnapshotJobs.computeIfAbsent(conversationId) {
            appScope.launch(Dispatchers.IO) {
                try {
                    val mutex = streamingSnapshotMutexes.computeIfAbsent(conversationId) { Mutex() }
                    mutex.withLock {
                        while (true) {
                            val snapshot = latestStreamingSnapshots.remove(conversationId) ?: break
                            persistConversationSnapshot(conversationId, snapshot)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Failed to persist streaming snapshot", error)
                } finally {
                    streamingSnapshotJobs.remove(conversationId)
                    if (latestStreamingSnapshots.containsKey(conversationId)) {
                        ensureStreamingSnapshotWriter(conversationId)
                    }
                }
            }
        }
    }

    private suspend fun flushStreamingSnapshot(conversationId: Uuid) {
        streamingSnapshotJobs.remove(conversationId)?.join()
        val snapshot = latestStreamingSnapshots.remove(conversationId) ?: return
        val mutex = streamingSnapshotMutexes.computeIfAbsent(conversationId) { Mutex() }
        mutex.withLock {
            persistConversationSnapshot(conversationId, snapshot)
        }
        lastStreamingSaveAt[conversationId] = System.currentTimeMillis()
    }

    // ---- 检查无效消息 ----

    private suspend fun importDocumentsToSandbox(
        conversation: Conversation,
        sandboxId: String
    ) = withContext(Dispatchers.IO) {
        val lastUserMessage = conversation.messageNodes
            .flatMap { it.messages }
            .filter { it.role == MessageRole.USER }
            .lastOrNull()

        lastUserMessage?.parts?.filterIsInstance<UIMessagePart.Document>()?.forEach { document ->
            try {
                val fileUri = android.net.Uri.parse(document.url)
                val targetPath = SandboxEngine.importFileToSandbox(
                    context,
                    sandboxId,
                    fileUri,
                    document.fileName
                )
                if (targetPath != null) {
                    Log.i(TAG, "Imported file to sandbox [$sandboxId]: $targetPath")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import file to sandbox: ${document.fileName}", e)
            }
        }
    }

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { index, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep tool nodes that are ready to resume after approval or ask_user answers.
                if (shouldPreservePendingToolNode(node.currentMessage.getTools())) {
                    return@mapIndexed node
                }

                // Remove message with pending non-approved tools
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(
            conversationId,
            normalizeCompressionState(conversation.copy(messageNodes = messagesNodes))
        )
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText() })
                    ),
                ),
                params = TextGenerationParams(
                    model = model,
                    thinkingBudget = 0,
                ),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generate_title))
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.suggestionModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText() }),
                    )
                ),
                params = TextGenerationParams(
                    model = model,
                    thinkingBudget = 0,
                ),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩与记忆索引 ----

    private fun Conversation.findCompressEndIndexForUncompressedVisibleCount(
        compressVisibleMessageCount: Int
    ): Int? {
        val startIndex = (compressionState.lastCompressedMessageIndex + 1).coerceAtLeast(0)
        if (startIndex > currentMessages.lastIndex) return null
        var remaining = compressVisibleMessageCount.coerceAtLeast(1)
        for (index in startIndex..currentMessages.lastIndex) {
            if (!currentMessages[index].countsTowardKeepRecent()) continue
            remaining--
            if (remaining == 0) return index
        }
        return currentMessages
            .mapIndexedNotNull { index, message ->
                if (index >= startIndex && message.countsTowardKeepRecent()) index else null
            }
            .lastOrNull()
    }

    suspend fun compressConversationByMessageCount(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        compressMessageCount: Int,
        generateMemoryLedger: Boolean = true,
        trigger: String = "manual",
    ): Result<Unit> {
        updateCompressionWorkerJob(conversationId, currentCoroutineContext()[Job])
        return runCatching<Unit> {
            val compressEndIndex = conversation.findCompressEndIndexForUncompressedVisibleCount(compressMessageCount)
                ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
            compressConversationInternal(
                conversationId = conversationId,
                conversation = conversation,
                additionalPrompt = additionalPrompt,
                keepRecentMessages = countUncompressedVisibleMessages(conversation)
                    .minus(compressMessageCount.coerceAtLeast(1))
                    .coerceAtLeast(0),
                trigger = trigger,
                generateMemoryLedger = generateMemoryLedger,
                compressEndIndexOverride = compressEndIndex,
            )
            Unit
        }.also {
            updateCompressionWorkerJob(conversationId, null)
        }
    }

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        keepRecentMessages: Int = 6,
        generateMemoryLedger: Boolean = true,
    ): Result<Unit> {
        updateCompressionWorkerJob(conversationId, currentCoroutineContext()[Job])
        return runCatching<Unit> {
            compressConversationInternal(
                conversationId = conversationId,
                conversation = conversation,
                additionalPrompt = additionalPrompt,
                keepRecentMessages = keepRecentMessages,
                trigger = "manual",
                generateMemoryLedger = generateMemoryLedger,
            )
            Unit
        }.also {
            updateCompressionWorkerJob(conversationId, null)
        }
    }

    suspend fun generateMemoryIndex(conversationId: Uuid): Result<Int> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: throw IllegalStateException("Conversation not found")
        try {
            val indexedCount = rebuildConversationIndexes(
                conversationId = conversationId,
                conversation = conversation,
                settings = settings,
            )
            addSuccessNotice(
                message = context.getString(R.string.memory_index_updated),
                conversationId = conversationId,
                title = context.getString(R.string.memory_index_updated_title)
            )
            indexedCount
        } catch (error: Throwable) {
            saveConversation(
                conversationId,
                conversation.copy(
                    memoryIndexState = conversation.memoryIndexState.copy(
                        lastIndexStatus = "failed",
                        lastIndexError = error.message.orEmpty()
                    )
                )
            )
            throw error
        }
    }

    suspend fun regenerateLatestCompression(
        conversationId: Uuid,
        target: CompressionRegenerationTarget = CompressionRegenerationTarget.DialogueSummary,
    ): Result<Unit> {
        updateCompressionWorkerJob(conversationId, currentCoroutineContext()[Job])
        return runCatching<Unit> {
            val conversation = conversationRepo.getConversationById(conversationId)
                ?: throw IllegalStateException("Conversation not found")
            val latestEvent = conversation.compressionEvents.latestCompressionEvent()
                ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_no_latest_summary))
            when (target) {
                CompressionRegenerationTarget.DialogueSummary -> {
                    val rebuiltConversation = conversation.copy(
                        compressionState = conversation.compressionState.copy(
                            dialogueSummaryText = latestEvent.baseDialogueSummaryText,
                            dialogueSummaryTokenEstimate = estimateTokenCount(
                                latestEvent.baseDialogueSummaryText,
                                settingsStore.settingsFlow.first().tokenEstimatorCharsPerToken
                            ),
                            dialogueSummaryUpdatedAt = Instant.now(),
                            lastCompressedMessageIndex = (latestEvent.compressStartIndex - 1).coerceAtLeast(-1),
                            memoryLedgerStatus = "stale",
                            memoryLedgerError = "",
                            updatedAt = Instant.now()
                        )
                    )
                    // Keep the previous compression event alive until the regenerated summary
                    // has been persisted successfully. If we remove it first and generation
                    // fails, the latest card loses its original base summary / ledger lineage.
                    val regeneratedConversation = compressConversationInternal(
                        conversationId = conversationId,
                        conversation = rebuiltConversation,
                        additionalPrompt = latestEvent.additionalPrompt,
                        keepRecentMessages = latestEvent.keepRecentMessages,
                        trigger = "regenerate-dialogue-summary",
                        generateMemoryLedger = false,
                        baseDialogueSummaryTextOverride = latestEvent.baseDialogueSummaryText,
                        baseRollingSummaryJsonOverride = conversation.compressionState.rollingSummaryJson.ifBlank {
                            latestEvent.baseLedgerJson.ifBlank { latestEvent.baseSummaryJson.ifBlank { "{}" } }
                        },
                        compressStartIndexOverride = latestEvent.compressStartIndex,
                        compressEndIndexOverride = latestEvent.compressEndIndex
                    )
                    removeSupersededCompressionArtifacts(
                        conversationId = conversationId,
                        conversation = regeneratedConversation,
                        supersededEventId = latestEvent.id,
                    )
                }

                CompressionRegenerationTarget.MemoryLedger -> {
                    val baseLedgerJson = latestEvent.baseLedgerJson.ifBlank { latestEvent.baseSummaryJson.ifBlank { "{}" } }
                    val rebuiltConversation = conversation.copy(
                        compressionState = conversation.compressionState.copy(
                            rollingSummaryJson = baseLedgerJson,
                            rollingSummaryTokenEstimate = estimateTokenCount(
                                baseLedgerJson,
                                settingsStore.settingsFlow.first().tokenEstimatorCharsPerToken
                            ),
                            memoryLedgerStatus = "pending",
                            memoryLedgerError = "",
                            updatedAt = Instant.now()
                        )
                    )
                    saveConversation(conversationId, rebuiltConversation)
                    val incrementalMessages = rebuiltConversation.currentMessages
                        .subList(latestEvent.compressStartIndex, latestEvent.compressEndIndex + 1)
                        .joinToString("\n\n") { message -> message.toCompressionText() }
                    pendingLedgerBatchRepository.upsertPendingBatch(
                        conversationId = conversationId,
                        eventId = latestEvent.id,
                        startIndex = latestEvent.compressStartIndex,
                        endIndex = latestEvent.compressEndIndex,
                        incrementalMessages = incrementalMessages,
                    )
                    val settings = settingsStore.settingsFlow.first()
                    val model = settings.findModelById(settings.compressModelId)
                        ?: settings.getCurrentChatModel()
                        ?: throw IllegalStateException("No model available for compression")
                    val provider = model.findProvider(settings.providers)
                        ?: throw IllegalStateException("Compression provider not found")
                    val providerHandler = providerManager.getProviderByType(provider)

                    updateLedgerGenerationUiState(
                        conversationId,
                        LedgerGenerationUiState(conversationId = conversationId, trigger = "regenerate-memory-ledger")
                    )
                    try {
                        var updatedConversation = processPendingLedgerBatches(
                            conversationId = conversationId,
                            conversation = rebuiltConversation,
                            trigger = "regenerate-memory-ledger",
                            settings = settings,
                            provider = provider,
                            providerHandler = providerHandler,
                            model = model,
                        )
                        updateCompressionUiState(
                            conversationId,
                            CompressionUiState(
                                conversationId = conversationId,
                                trigger = "regenerate-memory-ledger",
                                phase = CompressionUiPhase.Indexing
                            )
                        )
                        updatedConversation = rebuildIndexesWithRecovery(
                            conversationId = conversationId,
                            conversation = updatedConversation,
                            settings = settings,
                            showSuccessNotice = true,
                        )
                    } finally {
                        updateLedgerGenerationUiState(conversationId, null)
                        updateCompressionUiState(conversationId, null)
                    }
                }
            }
            Unit
        }.also {
            updateCompressionWorkerJob(conversationId, null)
        }
    }

    suspend fun editLatestDialogueSummary(
        conversationId: Uuid,
        editedSummaryText: String,
    ): Result<Unit> {
        return runCatching {
            val normalizedSummary = normalizeCompressionPlainText(editedSummaryText)
            if (normalizedSummary.isBlank()) {
                throw IllegalStateException("Dialogue summary cannot be blank")
            }

            val conversation = conversationRepo.getConversationById(conversationId)
                ?: throw IllegalStateException("Conversation not found")
            val latestEvent = conversation.compressionEvents.latestCompressionEvent()
                ?: throw IllegalStateException(context.getString(R.string.chat_page_compress_no_latest_summary))
            val now = Instant.now()
            val tokenEstimate = estimateTokenCount(
                text = normalizedSummary,
                charsPerToken = settingsStore.settingsFlow.first().tokenEstimatorCharsPerToken
            )
            val updatedEvent = latestEvent.copy(
                dialogueSummaryText = normalizedSummary,
                dialogueSummaryPreview = buildDialogueSummaryPreview(normalizedSummary),
            )
            conversationRepo.updateCompressionEvent(updatedEvent, conversationId)
            val updatedConversation = conversation.copy(
                compressionState = conversation.compressionState.copy(
                    // Manual edits become the source of truth for future compaction.
                    // The next summary pass must continue from what the user kept here.
                    dialogueSummaryText = normalizedSummary,
                    dialogueSummaryTokenEstimate = tokenEstimate,
                    dialogueSummaryUpdatedAt = now,
                    updatedAt = now,
                ),
                compressionEvents = conversation.compressionEvents
                    .map { event -> if (event.id == updatedEvent.id) updatedEvent else event }
                    .sortedWith(compressionEventOrder),
                chatSuggestions = emptyList(),
            )
            saveConversation(conversationId, updatedConversation)
        }
    }

    private suspend fun compressConversationInternal(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        keepRecentMessages: Int,
        trigger: String,
        generateMemoryLedger: Boolean,
        baseDialogueSummaryTextOverride: String? = null,
        baseRollingSummaryJsonOverride: String? = null,
        compressStartIndexOverride: Int? = null,
        compressEndIndexOverride: Int? = null,
    ): Conversation {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Compression provider not found")
        val providerHandler = providerManager.getProviderByType(provider)

        val normalizedKeepRecent = keepRecentMessages.coerceAtLeast(0)
        val startIndex = compressStartIndexOverride
            ?: (conversation.compressionState.lastCompressedMessageIndex + 1).coerceAtLeast(0)
        if (startIndex > conversation.currentMessages.lastIndex) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }
        val uncompressedMessages = conversation.currentMessages.drop(startIndex)
        val keepStartRelativeIndex = uncompressedMessages.findKeepStartIndexForVisibleMessages(normalizedKeepRecent)
            ?: uncompressedMessages.size
        val keepStartIndex = startIndex + keepStartRelativeIndex
        val requestedCompressEndIndex = compressEndIndexOverride ?: (keepStartIndex - 1)
        // Manual/auto compression applies keepRecentMessages only to the currently
        // uncompressed tail. Applying it to the full currentMessages list made the
        // custom "messages to compress" setting drift after previous compression
        // events and often collapsed to compressing only the frontier message.
        val compressEndIndex = requestedCompressEndIndex
            .coerceAtLeast(startIndex)
            .coerceAtMost(conversation.currentMessages.lastIndex)
        if (compressEndIndex < startIndex) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        val showCompressionProgress = trigger in setOf(
            "manual",
            "auto-threshold",
            "regenerate-dialogue-summary",
            "regenerate-memory-ledger",
        )
        val showIndexSuccessNotice =
            trigger == "manual" || trigger == "auto-threshold" ||
                trigger == "regenerate-dialogue-summary" || trigger == "regenerate-memory-ledger"
        if (showCompressionProgress) {
            updateCompressionUiState(
                conversationId,
                CompressionUiState(
                    conversationId = conversationId,
                    trigger = trigger,
                    phase = CompressionUiPhase.Compressing
                )
            )
        }

        try {
            val incrementalMessages = conversation.currentMessages
                .subList(startIndex, compressEndIndex + 1)
                .joinToString("\n\n") { message ->
                    message.toCompressionText()
                }

            val currentDialogueSummary = baseDialogueSummaryTextOverride
                ?: conversation.compressionState.dialogueSummaryText
            val currentRollingSummary = baseRollingSummaryJsonOverride
                ?: conversation.compressionState.rollingSummaryJson.ifBlank { "{}" }
            val dialogueAdditionalContext = buildString {
                if (additionalPrompt.isNotBlank()) {
                    append("Additional instructions from user: ")
                    append(additionalPrompt)
                    appendLine()
                }
                append("Summary maintenance trigger: ")
                append(trigger)
                appendLine()
                append("Keep recent visible messages outside compression: ")
                append(normalizedKeepRecent)
            }

            fun buildDialoguePrompt(extraContext: String = dialogueAdditionalContext): String {
                return settings.dialogueCompressPrompt.applyPlaceholders(
                    "dialogue_summary_text" to currentDialogueSummary,
                    "incremental_messages" to incrementalMessages,
                    "additional_context" to extraContext,
                    "locale" to Locale.getDefault().displayName
                )
            }

            suspend fun runDialogueSummary(prompt: String): String {
                val result = providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(prompt)),
                    params = compressionGenerationParams(
                        model = model,
                        // Keep summary budgeting hidden from the model. The wide cap here is
                        // only a safety fuse against accidental truncation inside the app.
                        maxTokens = DIALOGUE_SUMMARY_MAX_OUTPUT_TOKENS
                    ),
                )
                val summary = normalizeCompressionPlainText(
                    result.choices.firstOrNull()?.message?.toText().orEmpty()
                )
                if (summary.isBlank()) {
                    throw IllegalStateException("Failed to generate dialogue summary")
                }
                return summary
            }

            var nextDialogueSummary = runDialogueSummary(buildDialoguePrompt())
            var dialogueSummaryTokenEstimate = estimateTokenCount(
                text = nextDialogueSummary,
                charsPerToken = settings.tokenEstimatorCharsPerToken
            )

            val boundaryIndex = (compressEndIndex + 1).coerceIn(0, conversation.messageNodes.size)
            val event = conversationRepo.addCompressionEvent(
                conversationId = conversationId,
                boundaryIndex = boundaryIndex,
                dialogueSummaryText = nextDialogueSummary,
                dialogueSummaryPreview = buildDialogueSummaryPreview(nextDialogueSummary),
                ledgerSnapshot = "",
                summarySnapshot = "",
                compressStartIndex = startIndex,
                compressEndIndex = compressEndIndex,
                keepRecentMessages = normalizedKeepRecent,
                trigger = trigger,
                additionalPrompt = additionalPrompt,
                baseDialogueSummaryText = currentDialogueSummary,
                baseLedgerJson = currentRollingSummary,
                baseSummaryJson = currentRollingSummary,
            )

            var updatedConversation = conversation.copy(
                compressionState = conversation.compressionState.copy(
                    dialogueSummaryText = nextDialogueSummary,
                    dialogueSummaryTokenEstimate = dialogueSummaryTokenEstimate,
                    dialogueSummaryUpdatedAt = Instant.now(),
                    memoryLedgerStatus = if (generateMemoryLedger) "pending" else "stale",
                    memoryLedgerError = "",
                    lastCompressedMessageIndex = compressEndIndex,
                    updatedAt = Instant.now()
                ),
                compressionEvents = (conversation.compressionEvents + event).sortedWith(compressionEventOrder),
                chatSuggestions = emptyList(),
            )
            saveConversation(conversationId, updatedConversation)
            _compressionScrollEvents.tryEmit(conversationId to event.id)

            pendingLedgerBatchRepository.upsertPendingBatch(
                conversationId = conversationId,
                eventId = event.id,
                startIndex = startIndex,
                endIndex = compressEndIndex,
                incrementalMessages = incrementalMessages,
            )

            if (!generateMemoryLedger) {
                return getConversationFlow(conversationId).value
            }

            if (showCompressionProgress) {
                updateCompressionUiState(conversationId, null)
            }
            updateLedgerGenerationUiState(
                conversationId,
                LedgerGenerationUiState(conversationId = conversationId, trigger = trigger)
            )
            try {
                updatedConversation = processPendingLedgerBatches(
                    conversationId = conversationId,
                    conversation = updatedConversation,
                    trigger = trigger,
                    settings = settings,
                    provider = provider,
                    providerHandler = providerHandler,
                    model = model,
                )
            } finally {
                updateLedgerGenerationUiState(conversationId, null)
            }

            if (showCompressionProgress) {
                updateCompressionUiState(
                    conversationId,
                    CompressionUiState(
                        conversationId = conversationId,
                        trigger = trigger,
                        phase = CompressionUiPhase.Indexing
                    )
                )
            }
            updatedConversation = rebuildIndexesWithRecovery(
                conversationId = conversationId,
                conversation = updatedConversation,
                settings = settings,
                showSuccessNotice = showIndexSuccessNotice,
            )
            return getConversationFlow(conversationId).value
        } finally {
            updateLedgerGenerationUiState(conversationId, null)
            if (showCompressionProgress) {
                updateCompressionUiState(conversationId, null)
            }
        }
    }

    private fun buildSummarySnapshot(summaryJson: String): String {
        return parseRollingSummaryDocument(summaryJson)
            .toSummarySnapshot()
            .toJson()
    }

    private suspend fun rebuildIndexesWithRecovery(
        conversationId: Uuid,
        conversation: Conversation,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        showSuccessNotice: Boolean,
    ): Conversation {
        val startedAt = System.currentTimeMillis()
        logLedgerStep(conversationId, "index", "memory index rebuild started")
        return try {
            rebuildConversationIndexes(
                conversationId = conversationId,
                conversation = conversation,
                settings = settings,
            )
            logLedgerStep(
                conversationId,
                "index",
                "memory index rebuild finished in ${System.currentTimeMillis() - startedAt}ms"
            )
            if (showSuccessNotice) {
                addSuccessNotice(
                    message = context.getString(R.string.memory_index_updated),
                    conversationId = conversationId,
                    title = context.getString(R.string.memory_index_updated_title)
                )
            }
            getConversationFlow(conversationId).value
        } catch (error: Throwable) {
            val failedConversation = conversation.copy(
                memoryIndexState = conversation.memoryIndexState.copy(
                    lastIndexStatus = "failed",
                    lastIndexError = error.message.orEmpty()
                )
            )
            saveConversation(conversationId, failedConversation)
            logLedgerStep(
                conversationId,
                "index",
                "memory index rebuild failed after ${System.currentTimeMillis() - startedAt}ms",
                error
            )
            addError(
                error = error,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_memory_index)
            )
            failedConversation
        }
    }

    private suspend fun processPendingLedgerBatches(
        conversationId: Uuid,
        conversation: Conversation,
        trigger: String,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        provider: me.rerere.ai.provider.ProviderSetting,
        providerHandler: me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>,
        model: me.rerere.ai.provider.Model,
    ): Conversation {
        var currentConversation = conversation
        val processableBatches = pendingLedgerBatchRepository.getProcessableOfConversation(conversationId)
        if (processableBatches.isEmpty()) {
            logLedgerStep(conversationId, trigger, "no pending ledger batches")
            return currentConversation
        }

        logLedgerStep(
            conversationId,
            trigger,
            "processing ${processableBatches.size} pending ledger batch(es)"
        )

        processableBatches.forEach { batch ->
            val batchStartedAt = System.currentTimeMillis()
            if (currentConversation.compressionEvents.none { it.id == batch.eventId }) {
                // Older buggy regenerations could leave behind pending-ledger rows whose
                // source compression event has already been replaced. Drop those stale rows
                // instead of letting them poison every later ledger rebuild attempt.
                pendingLedgerBatchRepository.deleteByConversationAndEvent(conversationId, batch.eventId)
                logLedgerStep(
                    conversationId,
                    trigger,
                    "batch ${batch.id} dropped because source event ${batch.eventId} no longer exists"
                )
                return@forEach
            }
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} range=${batch.startIndex}..${batch.endIndex} attempt=${batch.attemptCount + 1} started"
            )
            val runningBatch = pendingLedgerBatchRepository.updateStatus(
                batch = batch,
                status = "running",
                attemptCount = batch.attemptCount + 1,
                lastError = "",
            )
            try {
                currentConversation = applyLedgerBatch(
                    conversationId = conversationId,
                    conversation = currentConversation,
                    batch = runningBatch,
                    trigger = trigger,
                    settings = settings,
                    provider = provider,
                    providerHandler = providerHandler,
                    model = model,
                )
                pendingLedgerBatchRepository.updateStatus(
                    batch = runningBatch,
                    status = "done",
                    attemptCount = runningBatch.attemptCount,
                    lastError = "",
                )
                logLedgerStep(
                    conversationId,
                    trigger,
                    "batch ${batch.id} finished in ${System.currentTimeMillis() - batchStartedAt}ms"
                )
            } catch (error: CancellationException) {
                pendingLedgerBatchRepository.updateStatus(
                    batch = runningBatch,
                    status = "pending",
                    attemptCount = batch.attemptCount,
                    lastError = "cancelled",
                )
                logLedgerStep(
                    conversationId,
                    trigger,
                    "batch ${batch.id} cancelled after ${System.currentTimeMillis() - batchStartedAt}ms",
                    error
                )
                throw error
            } catch (error: Throwable) {
                pendingLedgerBatchRepository.updateStatus(
                    batch = runningBatch,
                    status = "failed",
                    attemptCount = runningBatch.attemptCount,
                    lastError = error.message.orEmpty(),
                )
                logLedgerStep(
                    conversationId,
                    trigger,
                    "batch ${batch.id} failed after ${System.currentTimeMillis() - batchStartedAt}ms",
                    error
                )
                currentConversation = currentConversation.copy(
                    compressionState = currentConversation.compressionState.copy(
                        memoryLedgerStatus = "failed",
                        memoryLedgerError = error.message.orEmpty(),
                        updatedAt = Instant.now()
                    )
                )
                saveConversation(conversationId, currentConversation)
                addError(
                    error = error,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_compress_conversation)
                )
                return currentConversation
            }
        }
        return currentConversation
    }

    private suspend fun applyLedgerBatch(
        conversationId: Uuid,
        conversation: Conversation,
        batch: PendingLedgerBatch,
        trigger: String,
        settings: me.rerere.rikkahub.data.datastore.Settings,
        provider: me.rerere.ai.provider.ProviderSetting,
        providerHandler: me.rerere.ai.provider.Provider<me.rerere.ai.provider.ProviderSetting>,
        model: me.rerere.ai.provider.Model,
    ): Conversation {
        val event = conversation.compressionEvents.firstOrNull { it.id == batch.eventId }
            ?: throw IllegalStateException("Compression event ${batch.eventId} not found for pending ledger batch")
        val ledgerStartedAt = System.currentTimeMillis()
        val currentRollingSummary = conversation.compressionState.rollingSummaryJson.ifBlank {
            event.baseLedgerJson.ifBlank { event.baseSummaryJson.ifBlank { "{}" } }
        }
        val budget = calculateCompressionBudget(
            incrementalMessages = batch.incrementalMessages,
            charsPerToken = settings.tokenEstimatorCharsPerToken
        )
        val additionalContext = buildString {
            if (event.additionalPrompt.isNotBlank()) {
                append("Additional user instructions: ")
                append(event.additionalPrompt)
                appendLine()
            }
            append("Ledger maintenance trigger: ")
            append(trigger)
            appendLine()
            append("Pending ledger batch range: ")
            append(batch.startIndex)
            append("..")
            append(batch.endIndex)
        }

        suspend fun runPatchPrompt(): String {
            val patchStartedAt = System.currentTimeMillis()
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} patch request started inputChars=${batch.incrementalMessages.length}"
            )
            val prompt = DEFAULT_MEMORY_LEDGER_PATCH_PROMPT.applyPlaceholders(
                "rolling_summary_json" to currentRollingSummary,
                "incremental_messages" to batch.incrementalMessages,
                "additional_context" to additionalContext,
                "locale" to Locale.getDefault().displayName
            )
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = compressionGenerationParams(model = model),
            )
            val normalized = normalizeCompressionJsonText(result.choices.firstOrNull()?.message?.toText().orEmpty())
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} patch request finished in ${System.currentTimeMillis() - patchStartedAt}ms outputChars=${normalized.length}"
            )
            return normalized
        }

        suspend fun runLedgerRewrite(): String {
            val rewriteStartedAt = System.currentTimeMillis()
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} full ledger rewrite started inputChars=${batch.incrementalMessages.length}"
            )
            val prompt = DEFAULT_MEMORY_LEDGER_PROMPT.applyPlaceholders(
                "rolling_summary_json" to currentRollingSummary,
                "incremental_messages" to batch.incrementalMessages,
                "incremental_input_tokens" to budget.incrementalInputTokens.toString(),
                "min_output_tokens" to budget.minOutputTokens.toString(),
                "target_output_tokens" to budget.targetOutputTokens.toString(),
                "hard_cap_tokens" to budget.hardCapTokens.toString(),
                "min_chronology_items" to budget.minChronologyItems.toString(),
                "min_detail_capsules" to budget.minDetailCapsules.toString(),
                "additional_context" to additionalContext,
                "locale" to Locale.getDefault().displayName
            )
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = compressionGenerationParams(model = model),
            )
            val rawSummary = normalizeCompressionJsonText(result.choices.firstOrNull()?.message?.toText().orEmpty())
            if (rawSummary.isBlank()) {
                throw IllegalStateException("Failed to generate memory ledger")
            }
            val normalized = normalizeRollingSummaryJson(
                rawSummary = rawSummary,
                summaryTurn = batch.endIndex + 1,
                updatedAt = Instant.now()
            )
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} full ledger rewrite finished in ${System.currentTimeMillis() - rewriteStartedAt}ms outputChars=${normalized.length}"
            )
            return normalized
        }

        val nextRollingSummaryJson = runCatching {
            // Patch is only a fast path. If it fails, fall back to a full ledger rewrite
            // rather than forcing a lower-quality partial result into the persisted ledger.
            logLedgerStep(conversationId, trigger, "batch ${batch.id} attempting patch fast path")
            val patchDocument: LedgerPatchDocument = parseLedgerPatchDocument(runPatchPrompt())
            val baseDocument = parseRollingSummaryDocument(currentRollingSummary)
            applyLedgerPatchDocument(
                base = baseDocument,
                patch = patchDocument,
                fallbackTurn = batch.endIndex + 1,
                updatedAt = Instant.now()
            ).toJson()
        }.getOrElse {
            logLedgerStep(
                conversationId,
                trigger,
                "batch ${batch.id} patch fast path failed, falling back to full rewrite",
                it
            )
            runLedgerRewrite()
        }

        val ledgerSnapshot = buildSummarySnapshot(nextRollingSummaryJson)
        val updatedEvent = event.copy(
            ledgerSnapshot = ledgerSnapshot,
            summarySnapshot = ledgerSnapshot,
        )
        conversationRepo.updateCompressionEvent(updatedEvent, conversationId)

        val updatedConversation = conversation.copy(
            compressionState = conversation.compressionState.copy(
                rollingSummaryJson = nextRollingSummaryJson,
                rollingSummaryTokenEstimate = estimateTokenCount(
                    nextRollingSummaryJson,
                    settings.tokenEstimatorCharsPerToken
                ),
                memoryLedgerStatus = "ready",
                memoryLedgerError = "",
                updatedAt = Instant.now()
            ),
            compressionEvents = conversation.compressionEvents.map {
                if (it.id == updatedEvent.id) updatedEvent else it
            }.sortedWith(compressionEventOrder),
            chatSuggestions = emptyList(),
        )
        saveConversation(conversationId, updatedConversation)
        logLedgerStep(
            conversationId,
            trigger,
            "batch ${batch.id} persisted in ${System.currentTimeMillis() - ledgerStartedAt}ms snapshotChars=${ledgerSnapshot.length}"
        )
        return updatedConversation
    }

    private fun buildDialogueSummaryPreview(summaryText: String): String {
        return summaryText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("[") && !it.endsWith("]") }
            .take(3)
            .joinToString(" | ")
            .take(220)
    }

    private suspend fun removeSupersededCompressionArtifacts(
        conversationId: Uuid,
        conversation: Conversation,
        supersededEventId: Long,
    ): Conversation {
        // Summary regeneration now creates a replacement event from the exact same base
        // summary + incremental range. Once the replacement exists, we can safely remove
        // the superseded event row and its old pending-ledger batch together.
        pendingLedgerBatchRepository.deleteByConversationAndEvent(conversationId, supersededEventId)
        conversationRepo.deleteCompressionEvent(conversationId, supersededEventId)
        val cleanedConversation = conversation.copy(
                compressionEvents = conversation.compressionEvents
                    .filterNot { it.id == supersededEventId }
                    .sortedWith(compressionEventOrder),
        )
        saveConversation(conversationId, cleanedConversation)
        return cleanedConversation
    }

    private fun logLedgerStep(
        conversationId: Uuid,
        trigger: String,
        message: String,
        error: Throwable? = null,
    ) {
        val text = "[ledger][$conversationId][$trigger] $message"
        if (error == null) {
            Log.d(TAG, text)
            Logging.log(TAG, text)
        } else {
            Log.e(TAG, text, error)
            Logging.log(TAG, "$text :: ${error.stackTraceToString()}")
        }
    }

    private fun normalizeCompressionPlainText(rawText: String): String {
        val withoutThinking = rawText
            .replace(Regex("(?is)<think>.*?</think>"), "")
            .replace(Regex("(?im)^\\s*(reasoning|thoughts?|thinking|思考过程|推理过程)\\s*[:：]?\\s*$"), "")
            .trim()
        val unfenced = withoutThinking
            .removePrefix("```text")
            .removePrefix("```markdown")
            .removePrefix("```md")
            .removePrefix("```json")
            .removePrefix("```")
            .trim()
            .removeSuffix("```")
            .trim()
        return unfenced
            .lines()
            .dropWhile { line ->
                val trimmed = line.trim()
                trimmed.equals("text", ignoreCase = true) ||
                    trimmed.equals("markdown", ignoreCase = true) ||
                    trimmed.equals("md", ignoreCase = true) ||
                    trimmed.equals("json", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
    }

    private fun normalizeCompressionJsonText(rawText: String): String {
        val sanitized = normalizeCompressionPlainText(rawText)
        val firstBrace = sanitized.indexOfFirst { it == '{' }
        val lastBrace = sanitized.indexOfLast { it == '}' }
        return if (firstBrace >= 0 && lastBrace > firstBrace) {
            sanitized.substring(firstBrace, lastBrace + 1).trim()
        } else {
            sanitized
        }
    }

    private fun compressionGenerationParams(
        model: me.rerere.ai.provider.Model,
        maxTokens: Int? = null,
    ): TextGenerationParams {
        return TextGenerationParams(
            model = model,
            maxTokens = maxTokens,
            includeThoughtsInResponse = false,
        )
    }

    private fun calculateCompressionBudget(
        incrementalMessages: String,
        charsPerToken: Float,
    ): CompressionBudget {
        val incrementalInputTokens = estimateTokenCount(
            text = incrementalMessages,
            charsPerToken = charsPerToken
        )
        val minOutputTokens = ((incrementalInputTokens * 0.10).let(::ceil).toInt())
            .coerceIn(ROLLING_SUMMARY_MIN_OUTPUT_TOKENS, 12_000)
        val targetOutputTokens = ((incrementalInputTokens * 0.16).let(::ceil).toInt())
            .coerceIn(ROLLING_SUMMARY_TARGET_OUTPUT_TOKENS, 18_000)
        val minChronologyItems = ceil(incrementalInputTokens / 1_500.0)
            .toInt()
            .coerceIn(2, ROLLING_SUMMARY_MAX_CHRONOLOGY_ITEMS)
        val minDetailCapsules = ceil(incrementalInputTokens / 2_500.0)
            .toInt()
            .coerceIn(1, ROLLING_SUMMARY_MAX_DETAIL_CAPSULES)
        return CompressionBudget(
            incrementalInputTokens = incrementalInputTokens,
            minOutputTokens = minOutputTokens,
            targetOutputTokens = targetOutputTokens,
            hardCapTokens = ROLLING_SUMMARY_HARD_CAP_TOKENS,
            minChronologyItems = minChronologyItems,
            minDetailCapsules = minDetailCapsules,
        )
    }

    private fun estimateTokenCount(text: String, charsPerToken: Float): Int {
        val ratio = charsPerToken.coerceIn(2.0f, 8.0f).toDouble()
        val value = (text.length / ratio).toInt()
        return max(1, value)
    }

    private fun UIMessage.toSourceText(): String {
        return parts.joinToString("\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }.trim()
    }

    private fun UIMessage.createdAtInstant(): Instant {
        return runCatching {
            java.time.LocalDateTime.parse(createdAt.toString())
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
        }.getOrElse { Instant.now() }
    }

    private fun UIMessage.toCompressionText(sendReasoningContent: Boolean = true): String {
        val text = buildString {
            parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> appendLine(part.text)
                    is UIMessagePart.Reasoning -> if (sendReasoningContent) appendLine("[reasoning] ${part.reasoning.take(1200)}")
                    is UIMessagePart.Tool -> {
                        appendLine(renderToolCompressionEnvelope(part))
                    }

                    else -> Unit
                }
            }
        }.trim()
        return "[${role.name}] $text".trim()
    }

    private fun renderToolCompressionEnvelope(part: UIMessagePart.Tool): String {
        val outputText = part.output.joinToString("\n") {
            when (it) {
                is UIMessagePart.Text -> it.text
                else -> it.toString()
            }
        }.trim()
        val keyFields = extractKeyFields(outputText)
        val identifiers = extractIdentifiers(outputText)
        val errors = extractErrors(outputText)
        val pathsOrUrls = extractPathsOrUrls(outputText)
        val normalizedOutput = when {
            outputText.isBlank() -> ""
            outputText.length <= 4_000 -> outputText
            else -> buildString {
                appendLine("output_summary:")
                keyFields.takeIf { it.isNotEmpty() }?.let {
                    appendLine(it.joinToString("\n") { field -> "- $field" })
                }
                appendLine("output_head:")
                appendLine(outputText.take(1_200))
                appendLine("output_tail:")
                append(outputText.takeLast(1_200))
            }.trim()
        }
        return buildString {
            appendLine("[tool] ${part.toolName}")
            appendLine("input: ${part.input.take(1_600)}")
            if (keyFields.isNotEmpty()) {
                appendLine("key_fields: ${keyFields.joinToString(" | ")}")
            }
            if (identifiers.isNotEmpty()) {
                appendLine("identifiers: ${identifiers.joinToString(", ")}")
            }
            if (errors.isNotEmpty()) {
                appendLine("errors: ${errors.joinToString(" | ")}")
            }
            if (pathsOrUrls.isNotEmpty()) {
                appendLine("paths_or_urls: ${pathsOrUrls.joinToString(", ")}")
            }
            if (normalizedOutput.isNotBlank()) {
                appendLine(normalizedOutput)
            }
        }.trim()
    }

    private fun extractKeyFields(text: String): List<String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.contains(':') &&
                    line.any(Char::isLetterOrDigit) &&
                    line.length in 4..220
            }
            .distinct()
            .take(12)
            .toList()
    }

    private fun extractIdentifiers(text: String): List<String> {
        return Regex("[A-Za-z0-9_./:-]{3,}")
            .findAll(text)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)
            .toList()
    }

    private fun extractErrors(text: String): List<String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.contains("error", ignoreCase = true) ||
                    line.contains("exception", ignoreCase = true) ||
                    line.contains("failed", ignoreCase = true)
            }
            .distinct()
            .take(8)
            .toList()
    }

    private fun extractPathsOrUrls(text: String): List<String> {
        val matches = mutableListOf<String>()
        Regex("""https?://\S+|[A-Za-z]:\\[^\s]+|/[\w./-]+""")
            .findAll(text)
            .forEach { matches += it.value }
        return matches.distinct().take(12)
    }

    private suspend fun rebuildConversationIndexes(
        conversationId: Uuid,
        conversation: Conversation,
        settings: me.rerere.rikkahub.data.datastore.Settings,
    ): Int {
        val embeddingModel = settings.getEmbeddingModel()
            ?: throw IllegalStateException(context.getString(R.string.memory_index_embedding_required))
        if (embeddingModel.type != ModelType.EMBEDDING) {
            throw IllegalStateException(context.getString(R.string.memory_index_embedding_required))
        }
        val provider = embeddingModel.findProvider(settings.providers)
            ?: throw IllegalStateException("Embedding provider not found")
        val providerHandler = providerManager.getProviderByType(provider)
        val rollingSummaryJson = conversation.compressionState.rollingSummaryJson
            .takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(context.getString(R.string.memory_index_missing_summary))

        val liveTailDigest = buildLiveTailDigestJson(
            messages = collectLiveTailDigestMessages(conversation),
            updatedAt = Instant.now(),
            charsPerToken = settings.tokenEstimatorCharsPerToken
        )
        val memoryChunks = buildMemoryIndexChunks(
            rollingSummaryJson = rollingSummaryJson,
            charsPerToken = settings.tokenEstimatorCharsPerToken,
            liveTailDigestJson = liveTailDigest.json
        )
        if (memoryChunks.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.memory_index_empty))
        }

        val chunkEmbeddings = mutableListOf<List<Float>>()
        memoryChunks.chunked(32).forEach { batch ->
            val embeddingResult = providerHandler.generateEmbedding(
                providerSetting = provider,
                params = EmbeddingGenerationParams(
                    model = embeddingModel,
                    input = batch.map { it.content },
                )
            )
            chunkEmbeddings += embeddingResult.embeddings
        }
        if (chunkEmbeddings.size != memoryChunks.size) {
            throw IllegalStateException("Embedding result size mismatch")
        }

        val now = Instant.now()
        val memoryRecords = memoryChunks.mapIndexed { index, chunk ->
            MemoryIndexChunk(
                assistantId = conversation.assistantId,
                conversationId = conversation.id,
                sectionKey = chunk.sectionKey,
                chunkOrder = chunk.chunkOrder,
                content = chunk.content,
                tokenEstimate = chunk.tokenEstimate,
                embedding = chunkEmbeddings[index],
                metadata = chunk.metadata,
                updatedAt = now,
            )
        }
        val sourcePreviewRecords = buildSourcePreviewIndexChunks(conversation, now)

        memoryIndexRepository.replaceConversationChunks(
            assistantId = conversation.assistantId,
            conversationId = conversation.id,
            chunks = memoryRecords
        )
        sourcePreviewRepository.replaceConversationChunks(
            conversationId = conversation.id,
            chunks = sourcePreviewRecords
        )

        val refreshed = conversation.copy(
            memoryIndexState = conversation.memoryIndexState.copy(
                lastIndexStatus = "success",
                lastIndexedAt = now,
                lastIndexError = ""
            )
        )
        saveConversation(conversationId, refreshed)
        return memoryRecords.size
    }

    private fun buildSourcePreviewIndexChunks(
        conversation: Conversation,
        updatedAt: Instant,
    ): List<SourcePreviewChunk> {
        return buildSourcePreviewChunks(
            messages = collectIndexableSourceMessages(conversation)
        ).map { chunk ->
            SourcePreviewChunk(
                assistantId = conversation.assistantId,
                conversationId = conversation.id,
                messageId = Uuid.parse(chunk.messageId),
                role = chunk.role,
                chunkOrder = chunk.chunkOrder,
                prefixText = chunk.prefixText,
                searchText = chunk.searchText,
                blockType = chunk.blockType,
                updatedAt = updatedAt,
            )
        }
    }

    private fun collectLiveTailDigestMessages(conversation: Conversation): List<SourceDigestMessage> {
        val startIndex = (conversation.compressionState.lastCompressedMessageIndex + 1)
            .coerceAtLeast(0)
            .coerceAtMost(conversation.currentMessages.size)
        return conversation.currentMessages
            .drop(startIndex)
            .filter { message ->
                (message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT) &&
                    message.toSourceText().isNotBlank()
            }
            .map { message ->
                SourceDigestMessage(
                    messageId = message.id.toString(),
                    role = message.role.name.lowercase(),
                    text = message.toSourceText(),
                    createdAt = message.createdAtInstant()
                )
            }
    }

    private fun collectIndexableSourceMessages(conversation: Conversation): List<IndexedSourceMessage> {
        return conversation.messageNodes
            .flatMap { node -> node.messages }
            .asSequence()
            .filter { message -> message.role == MessageRole.USER || message.role == MessageRole.ASSISTANT }
            .map { message ->
                IndexedSourceMessage(
                    messageId = message.id.toString(),
                    role = message.role.name.lowercase(),
                    text = message.toSourceText()
                )
            }
            .filter { it.text.isNotBlank() }
            .distinctBy { it.messageId }
            .toList()
    }

    private suspend fun recallMemory(
        assistantId: Uuid,
        query: String,
        channel: String,
        role: String,
    ): RecallMemoryResult {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return RecallMemoryResult(
                query = query,
                channel = channel,
                role = role,
                returnedCount = 0,
                candidateConversationIds = emptyList(),
                chunks = emptyList()
            )
        }

        val indexedChunks = memoryIndexRepository.getChunksOfAssistant(assistantId)
        if (indexedChunks.isEmpty()) {
            return RecallMemoryResult(
                query = query,
                channel = channel,
                role = role,
                returnedCount = 0,
                candidateConversationIds = emptyList(),
                chunks = emptyList()
            )
        }

        val settings = settingsStore.settingsFlow.first()
        val embeddingModel = settings.getEmbeddingModel()
            ?: return RecallMemoryResult(
                query = query,
                channel = channel,
                role = role,
                returnedCount = 0,
                candidateConversationIds = emptyList(),
                chunks = emptyList()
            )
        val provider = embeddingModel.findProvider(settings.providers)
            ?: return RecallMemoryResult(
                query = query,
                channel = channel,
                role = role,
                returnedCount = 0,
                candidateConversationIds = emptyList(),
                chunks = emptyList()
            )
        val providerHandler = providerManager.getProviderByType(provider)
        val queryEmbedding = providerHandler.generateEmbedding(
            providerSetting = provider,
            params = EmbeddingGenerationParams(
                model = embeddingModel,
                input = listOf(normalizedQuery)
            )
        ).embeddings.firstOrNull()
            ?: return RecallMemoryResult(
                query = query,
                channel = channel,
                role = role,
                returnedCount = 0,
                candidateConversationIds = emptyList(),
                chunks = emptyList()
            )

        val retrievalChunks = indexedChunks.map { indexed ->
            me.rerere.rikkahub.data.memory.MemorySummaryChunk(
                sectionKey = indexed.chunk.sectionKey,
                chunkOrder = indexed.chunk.chunkOrder,
                content = buildString {
                    if (indexed.conversationTitle.isNotBlank()) {
                        appendLine(indexed.conversationTitle)
                    }
                    append(indexed.chunk.content)
                },
                tokenEstimate = indexed.chunk.tokenEstimate,
                metadata = indexed.chunk.metadata
            )
        }
        val ranked = rankMemoryChunks(
            query = normalizedQuery,
            chunks = retrievalChunks,
            documentEmbeddings = indexedChunks.map { it.chunk.embedding },
            queryEmbedding = queryEmbedding,
            channel = channel,
            role = role,
            bm25TopK = RECALL_BM25_TOP_K,
            vectorTopK = RECALL_VECTOR_RERANK_K
        ).map { score ->
            val indexed = indexedChunks[score.docIndex]
            RecallMemoryChunk(
                chunkId = indexed.chunk.id,
                assistantId = indexed.chunk.assistantId,
                conversationId = indexed.chunk.conversationId,
                conversationTitle = indexed.conversationTitle,
                sectionKey = indexed.chunk.sectionKey,
                content = indexed.chunk.content,
                lane = indexed.chunk.metadata.lane,
                status = indexed.chunk.metadata.status,
                tags = indexed.chunk.metadata.tags,
                entityKeys = indexed.chunk.metadata.entityKeys,
                timeRef = indexed.chunk.metadata.timeRef,
                bm25Score = score.bm25Score,
                vectorScore = score.vectorScore,
                finalScore = score.finalScore,
                tokenEstimate = indexed.chunk.tokenEstimate,
                updatedAt = indexed.chunk.updatedAt
            )
        }

        val selected = buildList {
            var usedTokens = 0
            ranked.forEach { chunk ->
                if (size >= RECALL_MAX_RETURN_CHUNKS) return@forEach
                val nextTokens = chunk.tokenEstimate.coerceAtLeast(1)
                if (usedTokens + nextTokens > RECALL_MAX_INJECT_TOKENS) return@forEach
                add(chunk)
                usedTokens += nextTokens
            }
        }

        val candidateConversationIds = ranked
            .map { it.conversationId }
            .distinct()
            .take(6)

        return RecallMemoryResult(
            query = query,
            channel = channel,
            role = role,
            returnedCount = selected.size,
            candidateConversationIds = candidateConversationIds,
            chunks = selected
        )
    }

    private suspend fun searchSource(
        assistantId: Uuid,
        query: String,
        role: String,
        candidateConversationIds: List<String>,
    ): SearchSourceResult {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            return SearchSourceResult(
                query = query,
                role = role,
                returnedCount = 0,
                usedFallbackScope = false,
                candidates = emptyList()
            )
        }

        val scopedConversationIds = candidateConversationIds.mapNotNull {
            runCatching { Uuid.parse(it) }.getOrNull()
        }
        val scopedChunks = if (scopedConversationIds.isNotEmpty()) {
            sourcePreviewRepository.getChunksOfConversations(assistantId, scopedConversationIds)
        } else {
            emptyList()
        }
        var ranked = rankSourcePreviewChunks(
            query = normalizedQuery,
            chunks = scopedChunks,
            role = role,
            candidateConversationIds = candidateConversationIds.toSet(),
            usedFallbackScope = false
        )
        var usedFallbackScope = false
        if (ranked.isEmpty() || isWeakSourceResult(ranked.firstOrNull())) {
            val allChunks = sourcePreviewRepository.getChunksOfAssistant(assistantId)
            ranked = rankSourcePreviewChunks(
                query = normalizedQuery,
                chunks = allChunks,
                role = role,
                candidateConversationIds = candidateConversationIds.toSet(),
                usedFallbackScope = true
            )
            usedFallbackScope = true
        }

        val allChunksByIndex = if (usedFallbackScope || scopedChunks.isEmpty()) {
            sourcePreviewRepository.getChunksOfAssistant(assistantId)
        } else {
            scopedChunks
        }
        val candidates = ranked
            .mapNotNull { score ->
                val chunk = allChunksByIndex.getOrNull(score.chunkIndex) ?: return@mapNotNull null
                SearchSourceCandidate(
                    sourceRef = sourceRef(
                        conversationId = chunk.conversationId.toString(),
                        messageId = chunk.messageId.toString()
                    ),
                    conversationId = chunk.conversationId,
                    messageId = chunk.messageId,
                    role = chunk.role,
                    prefix = chunk.prefixText,
                    hitSnippet = score.matchedSnippet,
                    score = score.score,
                    usedFallbackScope = score.usedFallbackScope
                )
            }
            .distinctBy { it.sourceRef }
            .take(SOURCE_PREVIEW_MAX_RESULTS)

        return SearchSourceResult(
            query = query,
            role = role,
            returnedCount = candidates.size,
            usedFallbackScope = usedFallbackScope,
            candidates = candidates
        )
    }

    private suspend fun readSource(
        assistantId: Uuid,
        sourceRef: String,
    ): ReadSourceResult {
        val parsed = parseSourceRef(sourceRef)
            ?: throw IllegalArgumentException("Invalid source_ref")
        val conversationId = Uuid.parse(parsed.conversationId)
        val messageId = Uuid.parse(parsed.messageId)
        val conversation = conversationRepo.getConversationById(conversationId)
            ?: throw IllegalStateException("Conversation not found")
        if (conversation.assistantId != assistantId) {
            throw IllegalStateException("Source does not belong to current assistant")
        }
        val message = conversation.messageNodes
            .flatMap { it.messages }
            .firstOrNull { it.id == messageId && (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) }
            ?: throw IllegalStateException("Message not found")
        return ReadSourceResult(
            sourceRef = sourceRef,
            conversationId = conversationId,
            messageId = messageId,
            role = message.role.name.lowercase(),
            createdAt = message.createdAtInstant(),
            content = message.toSourceText()
        )
    }

    private fun normalizeCompressionState(conversation: Conversation): Conversation {
        val maxIndex = conversation.messageNodes.lastIndex
        val normalizedCompressedIndex = conversation.compressionState.lastCompressedMessageIndex
            .coerceAtLeast(-1)
            .coerceAtMost(maxIndex)
        val normalizedEvents = conversation.compressionEvents.mapNotNull { event ->
            if (maxIndex < 0) return@mapNotNull null
            val start = event.compressStartIndex.coerceIn(0, maxIndex)
            val end = event.compressEndIndex.coerceIn(start, maxIndex)
            event.copy(
                boundaryIndex = event.boundaryIndex.coerceIn(0, conversation.messageNodes.size),
                compressStartIndex = start,
                compressEndIndex = end,
                keepRecentMessages = event.keepRecentMessages.coerceAtLeast(0),
            )
        }
        return conversation.copy(
            compressionState = conversation.compressionState.copy(
                lastCompressedMessageIndex = normalizedCompressedIndex
            ),
            compressionEvents = normalizedEvents,
        )
    }

    private fun estimatePromptTokenUsage(
        conversation: Conversation,
        charsPerToken: Float,
        sendReasoningContent: Boolean = true,
    ): Int {
        val compressedUntil = conversation.compressionState.lastCompressedMessageIndex
            .coerceAtMost(conversation.currentMessages.lastIndex)
        val activeMessages = if (conversation.compressionState.hasSummary && compressedUntil >= 0) {
            conversation.currentMessages.drop(compressedUntil + 1)
        } else {
            conversation.currentMessages
        }
        val estimatedChars = estimatePromptCharCount(
            messages = activeMessages,
            dialogueSummaryText = conversation.compressionState.dialogueSummaryText,
            legacyRollingSummaryJson = conversation.compressionState.rollingSummaryJson,
            sendReasoningContent = sendReasoningContent,
        )
        val ratio = charsPerToken.coerceIn(2.0f, 8.0f).toDouble()
        return (estimatedChars / ratio).toInt().coerceAtLeast(1)
    }

    private fun estimatePromptCharCount(
        messages: List<UIMessage>,
        dialogueSummaryText: String,
        legacyRollingSummaryJson: String,
        sendReasoningContent: Boolean = true,
    ): Int {
        val messageChars = messages.sumOf { message ->
            message.toCompressionText(sendReasoningContent).length
        }
        val summaryChars = when {
            dialogueSummaryText.isNotBlank() -> dialogueSummaryText.length
            legacyRollingSummaryJson.isBlank() -> 0
            else -> parseRollingSummaryDocument(legacyRollingSummaryJson).toCurrentViewProjection().length
        }
        return messageChars + summaryChars
    }

    private fun calibrateTokenEstimator(
        promptChars: Int,
        actualPromptTokens: Int,
    ) {
        if (promptChars <= 0 || actualPromptTokens <= 0) return
        val observed = promptChars.toFloat() / actualPromptTokens.toFloat()
        val old = settingsStore.settingsFlow.value.tokenEstimatorCharsPerToken
        val updated = (old * 0.8f + observed * 0.2f).coerceIn(2.0f, 8.0f)
        appScope.launch {
            settingsStore.update {
                it.copy(tokenEstimatorCharsPerToken = updated)
            }
        }
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid) {
        // 先取消 Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = context.getString(R.string.notification_chat_done_title)
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50) ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = context.getString(R.string.notification_live_update_title)
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.removePrefix("mcp__")
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = normalizeCompressionState(conversation.copy())
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return
        val processedParts = preprocessUserInputParts(parts)

        val currentConversation = getConversationFlow(conversationId).value
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversationId = Uuid.random()
        val forkConversation = Conversation(
            id = forkConversationId,
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
        )

        if (!SandboxEngine.copySandbox(context, conversationId.toString(), forkConversationId.toString())) {
            Log.w(TAG, "forkConversationAtMessage: failed to copy sandbox from $conversationId to $forkConversationId")
        }

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    suspend fun deleteMessageNodes(
        conversationId: Uuid,
        nodeIds: Set<Uuid>,
    ) {
        if (nodeIds.isEmpty()) return

        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.filterNot { it.id in nodeIds }

        saveConversation(
            conversationId,
            currentConversation.copy(messageNodes = updatedNodes)
        )
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob()
        job?.cancel()

        // Wait for the generation coroutine to run its cancellation/failure handlers before
        // taking the final preservation snapshot. Cancelling after the pre-stop snapshot could
        // otherwise persist an older state that does not yet contain the just-created tool call,
        // making the tool details disappear when the user stops at the exact moment the tool is
        // starting.
        withContext(NonCancellable) {
            job?.join()
            flushStreamingSnapshot(conversationId)
            persistInterruptedToolGenerationSnapshot(
                conversationId = conversationId,
                error = "Tool execution was cancelled by user",
                errorCode = "TOOL_EXECUTION_CANCELLED",
                reason = "Cancelled by user",
                markAssistantFinished = true,
                updateSessionState = true
            )
        }
    }

    private suspend fun persistInterruptedToolGenerationSnapshot(
        conversationId: Uuid,
        error: String,
        errorCode: String,
        reason: String,
        markAssistantFinished: Boolean,
        updateSessionState: Boolean,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val preservedConversation = currentConversation.toInterruptedToolGenerationSnapshot(
            error = error,
            errorCode = errorCode,
            reason = reason,
            markAssistantFinished = markAssistantFinished
        )
        if (updateSessionState) {
            saveConversation(conversationId, preservedConversation)
        } else {
            persistConversationSnapshot(conversationId, preservedConversation)
        }
        lastStreamingSaveAt[conversationId] = System.currentTimeMillis()
    }

    private fun Conversation.toInterruptedToolGenerationSnapshot(
        error: String,
        errorCode: String,
        reason: String,
        markAssistantFinished: Boolean,
    ): Conversation {
        val interruptionOutput = listOf(
            UIMessagePart.Text(
                """{"error":"$error","error_code":"$errorCode"}"""
            )
        )
        val conversationWithInterruptedTools = copy(
            messageNodes = messageNodes.map { node ->
                val currentMessage = node.currentMessage
                val updatedParts = currentMessage.parts.map { part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted) {
                        part.copy(
                            output = interruptionOutput,
                            approvalState = ToolApprovalState.Cancelled(reason)
                        )
                    } else {
                        part
                    }
                }

                if (updatedParts == currentMessage.parts) {
                    node
                } else {
                    node.copy(
                        messages = node.messages.mapIndexed { messageIndex, message ->
                            if (messageIndex == node.selectIndex) {
                                message.copy(parts = updatedParts)
                            } else {
                                message
                            }
                        }
                    )
                }
            }
        )
        return conversationWithInterruptedTools.toPreservedGenerationSnapshot(markAssistantFinished)
    }

    private suspend fun persistConversationSnapshot(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return
        }

        val updatedConversation = normalizeCompressionState(conversation.copy())
        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

}
