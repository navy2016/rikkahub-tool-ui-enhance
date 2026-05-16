package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.highlight.HighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Eraser
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.MagicWand01
import me.rerere.hugeicons.stroke.QuillWrite01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.writeClipboardText
import org.koin.compose.koinInject
private object ToolNames {
    const val MEMORY = "memory_tool"
    const val SEARCH_WEB = "search_web"
    const val SCRAPE_WEB = "scrape_web"
    const val GET_TIME_INFO = "get_time_info"
    const val CLIPBOARD = "clipboard_tool"
    const val TTS = "text_to_speech"
    const val ASK_USER = "ask_user"
    const val USE_SKILL = "use_skill"
}

private object MemoryActions {
    const val CREATE = "create"
    const val EDIT = "edit"
    const val DELETE = "delete"
}

private object ClipboardActions {
    const val READ = "read"
    const val WRITE = "write"
}

private fun getToolIcon(toolName: String, action: String?) = when (toolName) {
    ToolNames.MEMORY -> when (action) {
        MemoryActions.CREATE, MemoryActions.EDIT -> HugeIcons.QuillWrite01
        MemoryActions.DELETE -> HugeIcons.Eraser
        else -> HugeIcons.QuillWrite01
    }

    ToolNames.SEARCH_WEB -> HugeIcons.Search01
    ToolNames.SCRAPE_WEB -> HugeIcons.GlobalSearch
    ToolNames.GET_TIME_INFO -> HugeIcons.Time02
    ToolNames.CLIPBOARD -> HugeIcons.Clipboard
    ToolNames.TTS -> HugeIcons.VolumeHigh
    ToolNames.ASK_USER -> HugeIcons.BubbleChatQuestion
    ToolNames.USE_SKILL -> HugeIcons.MagicWand01
    else -> HugeIcons.Tools
}

private fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

private fun buildToolApprovalSuggestion(toolName: String, input: String): String {
    return buildString {
        appendLine("Review this tool call before approving.")
        appendLine("Tool: $toolName")
        appendLine("Checklist:")
        appendLine("- JSON must be valid and fully escaped.")
        appendLine("- Arguments should match the user's request.")
        appendLine("- File paths, shell commands, URLs, and destructive operations should be intentional.")
        appendLine("- If invalid, edit the JSON input here before allowing.")
        appendLine()
        appendLine("Original JSON input:")
        append(input.ifBlank { "{}" })
    }.trim()
}

/**
 * Validate JSON input and return a list of error descriptions with locations.
 * Returns empty list if JSON is valid.
 */
private fun validateJsonInput(rawInput: String): List<JsonValidationError> {
    val input = rawInput.trim().ifBlank { "{}" }
    val errors = mutableListOf<JsonValidationError>()
    try {
        kotlinx.serialization.json.Json.parseToJsonElement(input)
    } catch (e: Exception) {
        val message = e.message ?: "Unknown JSON error"
        // Extract offset from error message
        val offsetMatch = Regex("""offset\s+(\d+)""").find(message)
        val offset = offsetMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (offset != null) {
            val safeOffset = offset.coerceIn(0, input.length)
            val lineStart = input.lastIndexOf('\n', (safeOffset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val column = safeOffset - lineStart + 1
            val lineEnd = input.indexOf('\n', safeOffset).let { if (it < 0) input.length else it }
            val contextLine = input.substring(lineStart, lineEnd)
            errors.add(JsonValidationError(
                offset = offset,
                column = column,
                message = message,
                contextLine = contextLine,
                pointerColumn = safeOffset - lineStart,
            ))
        } else {
            errors.add(JsonValidationError(
                offset = -1,
                column = -1,
                message = message,
                contextLine = "",
                pointerColumn = 0,
            ))
        }
    }
    return errors
}

private data class JsonValidationError(
    val offset: Int,
    val column: Int,
    val message: String,
    val contextLine: String,
    val pointerColumn: Int,
)

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, inputOverride: String?) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onStopGeneration: (() -> Unit)? = null,
    onDeleteToolCall: ((toolCallId: String) -> Unit)? = null,
) {
    val isAskUser = tool.toolName == ToolNames.ASK_USER

    if (isAskUser) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }
    var showResult by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val eventBus: AppEventBus = koinInject()
    val settingsStore = koinInject<SettingsStore>()
    val appSettings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isPending = tool.approvalState is ToolApprovalState.Pending
    var showApprovalDialog by remember(tool.toolCallId) { mutableStateOf(false) }
    LaunchedEffect(isPending, tool.toolCallId) {
        if (isPending) showApprovalDialog = true
    }
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val isCancelled = tool.approvalState is ToolApprovalState.Cancelled
    val arguments = tool.inputAsJson()
    val memoryAction = arguments.getStringContent("action")
    val content = if (tool.isExecuted) {
        runCatching {
            JsonInstant.parseToJsonElement(
                tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            )
        }.getOrElse { JsonObject(emptyMap()) }
    } else {
        null
    }
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    val documents = tool.output.filterIsInstance<UIMessagePart.Document>()

    // Detect if tool output contains errors (TOOL_EXECUTION_FAILED)
    val hasToolError = tool.isExecuted && tool.output.filterIsInstance<UIMessagePart.Text>().any { textPart ->
        textPart.text.contains("TOOL_EXECUTION_FAILED") || textPart.text.contains("TOOL_EXECUTION_CANCELLED")
    }

    val title = when (tool.toolName) {
        ToolNames.MEMORY -> when (memoryAction) {
            MemoryActions.CREATE -> stringResource(R.string.chat_message_tool_create_memory)
            MemoryActions.EDIT -> stringResource(R.string.chat_message_tool_edit_memory)
            MemoryActions.DELETE -> stringResource(R.string.chat_message_tool_delete_memory)
            else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
        }

        ToolNames.SEARCH_WEB -> stringResource(
            R.string.chat_message_tool_search_web,
            arguments.getStringContent("query") ?: ""
        )

        ToolNames.SCRAPE_WEB -> stringResource(R.string.chat_message_tool_scrape_web)
        ToolNames.GET_TIME_INFO -> stringResource(R.string.chat_message_tool_get_time)
        ToolNames.CLIPBOARD -> when (memoryAction) {
            ClipboardActions.READ -> stringResource(R.string.chat_message_tool_clipboard_read)
            ClipboardActions.WRITE -> stringResource(R.string.chat_message_tool_clipboard_write)
            else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
        }

        ToolNames.TTS -> {
            val preview = arguments.getStringContent("text")?.let { text ->
                if (text.length > 24) text.take(24) + "…" else text
            } ?: ""
            "Speaking: $preview"
        }

        ToolNames.USE_SKILL -> {
            val skillName = arguments.getStringContent("name") ?: ""
            val path = arguments.getStringContent("path")
            if (path != null) "Skill: $skillName / $path" else "Skill: $skillName"
        }

        else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
    }

    // 判断是否有额外内容需要显示
    val hasExtraContent = when (tool.toolName) {
        ToolNames.MEMORY -> memoryAction in listOf(MemoryActions.CREATE, MemoryActions.EDIT) &&
            content.getStringContent("content") != null

        ToolNames.SEARCH_WEB -> content.getStringContent("answer") != null ||
            (content?.jsonObject?.get("items")?.jsonArray?.isNotEmpty() == true)

        ToolNames.SCRAPE_WEB -> arguments.getStringContent("url") != null
        ToolNames.TTS -> arguments.getStringContent("text") != null
        else -> false
    } || isDenied || images.isNotEmpty() || documents.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = getToolIcon(tool.toolName, memoryAction),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (hasToolError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(
                        onClick = { showApprovalDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.chat_message_tool_deny),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { showApprovalDialog = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = stringResource(R.string.chat_message_tool_approve),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else if (tool.isExecuted && onDeleteToolCall != null) {
            {
                IconButton(
                    onClick = { onDeleteToolCall(tool.toolCallId) },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = stringResource(R.string.chat_message_tool_delete),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        } else {
            null
        },
        onClick = if (content != null || isPending || loading || images.isNotEmpty() || documents.isNotEmpty()) {
            { showResult = true }
        } else {
            null
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tool.toolName == ToolNames.MEMORY &&
                        memoryAction in listOf(MemoryActions.CREATE, MemoryActions.EDIT)
                    ) {
                        content.getStringContent("content")?.let { memoryContent ->
                            Text(
                                text = memoryContent,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.shimmer(isLoading = loading),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (tool.toolName == ToolNames.SEARCH_WEB) {
                        content.getStringContent("answer")?.let { answer ->
                            Text(
                                text = answer,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.shimmer(isLoading = loading),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val items = content?.jsonObject?.get("items")?.jsonArray ?: emptyList()
                        if (items.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FaviconRow(
                                    urls = items.mapNotNull { it.getStringContent("url") },
                                    size = 18.dp,
                                )
                                Text(
                                    text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                    if (tool.toolName == ToolNames.SCRAPE_WEB) {
                        val url = arguments.getStringContent("url") ?: ""
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                    if (tool.toolName == ToolNames.TTS) {
                        val text = arguments.getStringContent("text") ?: ""
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalIconButton(
                                onClick = { scope.launch { eventBus.emit(AppEvent.Speak(text)) } },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Refresh01,
                                    contentDescription = "Replay",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (documents.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            documents.forEach { document ->
                                DeliveryDocumentChip(document)
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (isCancelled) {
                        val reason = (tool.approvalState as ToolApprovalState.Cancelled).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_cancelled) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showApprovalDialog && isPending && onToolApproval != null) {
        ToolApprovalDialog(
            toolName = tool.toolName,
            initialInput = tool.input,
            manualApprovalEnabled = appSettings.manualToolApprovalEnabled,
            onManualApprovalEnabledChange = { enabled ->
                scope.launch {
                    settingsStore.update { settings -> settings.copy(manualToolApprovalEnabled = enabled) }
                }
            },
            onDismiss = { showApprovalDialog = false },
            onApprove = { editedInput ->
                showApprovalDialog = false
                onToolApproval(tool.toolCallId, true, "", editedInput)
            },
            onDeny = { reason, editedInput ->
                showApprovalDialog = false
                onToolApproval(tool.toolCallId, false, reason, editedInput)
            },
            onCopyText = { text -> context.writeClipboardText(text) },
        )
    }

    if (showResult) {
        ToolCallPreviewSheet(
            toolName = tool.toolName,
            arguments = arguments,
            content = content,
            output = tool.output,
            isRunning = loading,
            manualApprovalEnabled = appSettings.manualToolApprovalEnabled,
            onManualApprovalEnabledChange = { enabled ->
                scope.launch {
                    settingsStore.update { settings -> settings.copy(manualToolApprovalEnabled = enabled) }
                }
            },
            onStopGeneration = onStopGeneration,
            onDismissRequest = { showResult = false }
        )
    }
}

@Composable
private fun ToolCallPreviewSheet(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
    output: List<UIMessagePart>,
    isRunning: Boolean = false,
    manualApprovalEnabled: Boolean = false,
    onManualApprovalEnabledChange: (Boolean) -> Unit = {},
    onStopGeneration: (() -> Unit)? = null,
    onDismissRequest: () -> Unit = {}
) {
    val memoryRepo: MemoryRepository = koinInject()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun clearChatInputFocus() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val dismissSheet = {
        clearChatInputFocus()
        onDismissRequest()
    }

    LaunchedEffect(Unit) {
        clearChatInputFocus()
    }

    val memoryAction = arguments.getStringContent("action")
    val isMemoryOperation = toolName == ToolNames.MEMORY &&
        memoryAction in listOf(MemoryActions.CREATE, MemoryActions.EDIT)
    val memoryId = (content as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = dismissSheet,
        content = {
            Column {
                ManualToolApprovalToggle(
                    enabled = manualApprovalEnabled,
                    onCheckedChange = onManualApprovalEnabledChange,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                when {
                content == null && isRunning -> RunningToolPreview(
                    toolName = toolName,
                    arguments = arguments,
                    onStopGeneration = onStopGeneration,
                    onDismissRequest = dismissSheet,
                )

                content == null -> GenericToolPreview(
                    toolName = toolName,
                    arguments = arguments,
                    output = emptyList(),
                    isMemoryOperation = false,
                    memoryId = null,
                    memoryRepo = memoryRepo,
                    scope = scope,
                    onDismissRequest = dismissSheet
                )

                toolName == ToolNames.SEARCH_WEB -> SearchWebPreview(
                    arguments = arguments,
                    content = content,
                )

                toolName == ToolNames.SCRAPE_WEB -> ScrapeWebPreview(content = content)
                else -> GenericToolPreview(
                    toolName = toolName,
                    arguments = arguments,
                    output = output,
                    isMemoryOperation = isMemoryOperation,
                    memoryId = memoryId,
                    memoryRepo = memoryRepo,
                    scope = scope,
                    onDismissRequest = dismissSheet
                )
            }
            }
        },
    )
}

@Composable
private fun SearchWebPreview(
    arguments: JsonElement,
    content: JsonElement,
) {
    val context = LocalContext.current
    val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
    val answer = content.getStringContent("answer")
    val query = arguments.getStringContent("query") ?: ""

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(stringResource(R.string.chat_message_tool_search_prefix, query))
        }

        if (answer != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    MarkdownBlock(
                        content = answer,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (items.isNotEmpty()) {
            items(items) { item ->
                val url = item.getStringContent("url") ?: return@items
                val title = item.getStringContent("title") ?: return@items
                val text = item.getStringContent("text") ?: return@items

                Card(
                    onClick = { context.openUrl(url) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Favicon(
                            url = url,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(text = title, maxLines = 1)
                            Text(
                                text = text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = url,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        } else {
            item {
                HighlightText(
                    code = JsonInstantPretty.encodeToString(content),
                    language = "json",
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun ScrapeWebPreview(content: JsonElement) {
    val urls = content.jsonObject["urls"]?.jsonArray ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(
                    R.string.chat_message_tool_scrape_prefix,
                    urls.joinToString(", ") { it.getStringContent("url") ?: "" }
                )
            )
        }

        items(urls) { url ->
            val urlObject = url.jsonObject
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = urlObject["url"]?.jsonPrimitive?.content ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.fillMaxWidth()
                )
                Card {
                    MarkdownBlock(
                        content = urlObject["content"]?.jsonPrimitive?.content ?: "",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GenericToolPreview(
    toolName: String,
    arguments: JsonElement,
    output: List<UIMessagePart>,
    isMemoryOperation: Boolean,
    memoryId: Int?,
    memoryRepo: MemoryRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismissRequest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.8f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_message_tool_call_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            if (isMemoryOperation && memoryId != null) {
                IconButton(
                    onClick = {
                        scope.launch {
                            memoryRepo.deleteMemory(memoryId)
                            onDismissRequest()
                        }
                    }
                ) {
                    Icon(
                        imageVector = HugeIcons.Delete01,
                        contentDescription = "Delete memory"
                    )
                }
            }
        }
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_label, toolName))
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
            )
        }
        if (output.isNotEmpty()) {
            FormItem(
                label = {
                    Text(stringResource(R.string.chat_message_tool_call_result))
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    output.fastForEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> HighlightCodeBlock(
                                code = runCatching {
                                    JsonInstantPretty.encodeToString(
                                        JsonInstant.parseToJsonElement(part.text)
                                    )
                                }.getOrElse { part.text },
                                language = "json",
                                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
                            )

                            is UIMessagePart.Image -> ZoomableAsyncImage(
                                model = part.url,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            is UIMessagePart.Document -> {}

                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryDocumentChip(document: UIMessagePart.Document) {
    val context = LocalContext.current
    Surface(
        tonalElevation = 2.dp,
        onClick = {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                data = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    document.url.toUri().toFile()
                )
            }
            context.startActivity(Intent.createChooser(intent, null))
        },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = HugeIcons.QuillWrite01,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = document.fileName,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onToolAnswer != null) {
                            when (q.selectionType) {
                                "single" -> {
                                    // Single select: chips only, no text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                "multi" -> {
                                    // Multi select: chips only, multiple can be selected
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                val selectedSet = multiAnswers[q.id] ?: emptySet()
                                                FilterChip(
                                                    selected = selectedSet.contains(option),
                                                    onClick = {
                                                        val current = selectedSet.toMutableSet()
                                                        if (current.contains(option)) current.remove(option)
                                                        else current.add(option)
                                                        multiAnswers[q.id] = current
                                                    },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    // Text (default): optional option chips + free text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    // Free text input
                                    OutlinedTextField(
                                        value = answers[q.id] ?: "",
                                        onValueChange = { answers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = false,
                                        minLines = 1,
                                        maxLines = 3,
                                    )
                                }
                            }
                        } else if (isAnswered) {
                            // Show the user's answer
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerJson = runCatching {
                                JsonInstant.parseToJsonElement(answeredState.answer)
                            }.getOrNull()
                            val answerText = answerJson?.jsonObject?.get("answers")
                                ?.jsonObject?.get(q.id)?.jsonPrimitive?.contentOrNull
                                ?: answeredState.answer
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Submit button
                if (isPending && onToolAnswer != null) {
                    FilledTonalButton(
                        onClick = {
                            val answerPayload = buildJsonObject {
                                put("answers", buildJsonObject {
                                    questions.forEach { q ->
                                        when (q.selectionType) {
                                            "multi" -> put(q.id, JsonPrimitive(multiAnswers[q.id]?.joinToString(", ") ?: ""))
                                            else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                        }
                                    }
                                })
                            }
                            onToolAnswer(tool.toolCallId, answerPayload.toString())
                        },
                        enabled = questions.all { q ->
                            when (q.selectionType) {
                                "multi" -> !multiAnswers[q.id].isNullOrEmpty()
                                else -> !answers[q.id].isNullOrBlank()
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_message_tool_submit),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
    )
}


@Composable
private fun ManualToolApprovalToggle(
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chat_message_tool_manual_approval),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun ToolApprovalDialog(
    toolName: String,
    initialInput: String,
    manualApprovalEnabled: Boolean,
    onManualApprovalEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onApprove: (String) -> Unit,
    onDeny: (reason: String, editedInput: String) -> Unit,
    onCopyText: (String) -> Unit,
) {
    var editedInput by remember(initialInput) { mutableStateOf(initialInput.ifBlank { "{}" }) }
    var denyReason by remember { mutableStateOf("") }
    val approvalSuggestion = remember(toolName, initialInput) {
        buildToolApprovalSuggestion(toolName, initialInput.ifBlank { "{}" })
    }
    // Live JSON validation
    val jsonErrors = remember(editedInput) { validateJsonInput(editedInput) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_message_tool_approval_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ManualToolApprovalToggle(
                    enabled = manualApprovalEnabled,
                    onCheckedChange = onManualApprovalEnabledChange,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onCopyText(initialInput.ifBlank { "{}" }) }) {
                        Text(stringResource(R.string.chat_message_tool_copy_original_input))
                    }
                    TextButton(onClick = { onCopyText(approvalSuggestion) }) {
                        Text(stringResource(R.string.chat_message_tool_copy_approval_suggestion))
                    }
                }
                OutlinedTextField(
                    value = editedInput,
                    onValueChange = { editedInput = it },
                    label = { Text(stringResource(R.string.chat_message_tool_edit_json_input)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 8,
                    maxLines = 18,
                    textStyle = TextStyle(fontSize = 12.sp, lineHeight = 15.sp),
                    isError = jsonErrors.isNotEmpty(),
                )
                // JSON validation result display
                if (jsonErrors.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.chat_message_tool_json_validation_errors),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        jsonErrors.forEach { err ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (err.offset >= 0) {
                                    Text(
                                        text = stringResource(R.string.chat_message_tool_error_at_offset, err.offset, err.column),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                Text(
                                    text = err.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (err.contextLine.isNotBlank()) {
                                    Text(
                                        text = err.contextLine,
                                        style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        maxLines = 2,
                                    )
                                    Text(
                                        text = " ".repeat(err.pointerColumn.coerceAtLeast(0)) + "^",
                                        style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.chat_message_tool_json_valid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = denyReason,
                    onValueChange = { denyReason = it },
                    label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApprove(editedInput) }) {
                Text(stringResource(R.string.chat_message_tool_approve))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onDeny(denyReason, editedInput) }) {
                    Text(stringResource(R.string.chat_message_tool_deny))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    )
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun RunningToolPreview(
    toolName: String,
    arguments: JsonElement,
    onStopGeneration: (() -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight(0.6f)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header: running indicator + stop button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DotLoading(size = 12.dp)
                Text(
                    text = stringResource(R.string.chat_message_tool_running_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            if (onStopGeneration != null) {
                FilledTonalButton(
                    onClick = {
                        onStopGeneration()
                        onDismissRequest()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_message_tool_stop))
                }
            }
        }

        // Tool name and input arguments
        FormItem(
            label = {
                Text(stringResource(R.string.chat_message_tool_call_label, toolName))
            }
        ) {
            HighlightCodeBlock(
                code = JsonInstantPretty.encodeToString(arguments),
                language = "json",
                style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
            )
        }

        // Hint text
        Text(
            text = stringResource(R.string.chat_message_tool_running_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
