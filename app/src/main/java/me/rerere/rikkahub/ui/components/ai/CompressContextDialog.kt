package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

enum class CompressContextDialogMode {
    Manual,
    AutoProgress,
    RegenerateConfirm,
}

private data class CompressionPreviewMessage(
    val index: Int,
    val role: String,
    val tokens: Int,
    val text: String,
)

private fun Conversation.uncompressedVisibleMessages(): List<CompressionPreviewMessage> {
    val startIndex = (compressionState.lastCompressedMessageIndex + 1).coerceAtLeast(0)
    return currentMessages
        .drop(startIndex)
        .mapIndexedNotNull { offset, message ->
            if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) return@mapIndexedNotNull null
            val text = message.previewText().ifBlank { "[empty]" }
            CompressionPreviewMessage(
                index = startIndex + offset,
                role = message.role.name,
                tokens = estimatePreviewTokens(text),
                text = text.replace("\s+".toRegex(), " ").take(220)
            )
        }
}

private fun UIMessage.previewText(): String = parts.joinToString(" ") { part ->
    when (part) {
        is UIMessagePart.Text -> part.text
        is UIMessagePart.Reasoning -> "[reasoning] ${part.reasoning.take(200)}"
        is UIMessagePart.Tool -> "[tool:${part.toolName}] input=${part.input.take(120)} output=${part.output.joinToString(" ") { output -> if (output is UIMessagePart.Text) output.text else output.toString() }.take(200)}"
        is UIMessagePart.Image -> "[image]"
        is UIMessagePart.Video -> "[video]"
        is UIMessagePart.Audio -> "[audio]"
        is UIMessagePart.Document -> "[document:${part.fileName}]"
        else -> ""
    }
}

private fun estimatePreviewTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

@Composable
fun CompressContextDialog(
    mode: CompressContextDialogMode,
    onDismiss: () -> Unit,
    initialAutoCompressEnabled: Boolean = false,
    initialAutoCompressTriggerTokens: Int = 12000,
    initialCompressMessageCount: Int = 6,
    conversation: Conversation? = null,
    currentSendTokens: Int = 0,
    progressMessage: String = "",
    regenerateTitle: String? = null,
    regenerateDescription: String? = null,
    regenerateActionLabel: String? = null,
    initialGenerateMemoryLedger: Boolean = true,
    onConfirmManual: ((
        additionalPrompt: String,
        compressMessageCount: Int,
        autoCompressEnabled: Boolean,
        autoCompressTriggerTokens: Int,
        generateMemoryLedger: Boolean,
    ) -> Unit)? = null,
    onCancelProgress: (() -> Unit)? = null,
    onConfirmRegenerate: (() -> Unit)? = null,
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var compressMessageCountInput by remember(initialCompressMessageCount) {
        mutableStateOf(initialCompressMessageCount.toString())
    }
    var showUncompressedPreview by remember { mutableStateOf(false) }
    var autoCompressEnabled by remember { mutableStateOf(initialAutoCompressEnabled) }
    var autoCompressTriggerTokensInput by remember { mutableStateOf(initialAutoCompressTriggerTokens.toString()) }
    var generateMemoryLedger by remember { mutableStateOf(initialGenerateMemoryLedger) }
    val uncompressedMessages = remember(conversation) {
        conversation?.uncompressedVisibleMessages().orEmpty()
    }

    val titleText = when (mode) {
        CompressContextDialogMode.Manual,
        CompressContextDialogMode.AutoProgress -> stringResource(R.string.chat_page_compress_context_title)
        CompressContextDialogMode.RegenerateConfirm -> regenerateTitle
            ?: stringResource(R.string.chat_page_regenerate_compression_title)
    }

    AlertDialog(
        onDismissRequest = {
            when (mode) {
                CompressContextDialogMode.Manual -> onDismiss()
                CompressContextDialogMode.AutoProgress -> {}
                CompressContextDialogMode.RegenerateConfirm -> onDismiss()
            }
        },
        title = { Text(titleText) },
        text = {
            when (mode) {
                CompressContextDialogMode.Manual -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.chat_page_compress_context_desc_v2),
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text(
                            text = stringResource(
                                R.string.chat_page_compress_uncompressed_total,
                                uncompressedMessages.size
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            text = stringResource(
                                R.string.chat_page_compress_current_send_tokens,
                                currentSendTokens.coerceAtLeast(0)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = compressMessageCountInput,
                            onValueChange = { compressMessageCountInput = it.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.chat_page_compress_message_count)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showUncompressedPreview = !showUncompressedPreview },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        if (showUncompressedPreview) {
                                            R.string.chat_page_compress_preview_collapse
                                        } else {
                                            R.string.chat_page_compress_preview_expand
                                        }
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (showUncompressedPreview) {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 260.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(uncompressedMessages) { preview ->
                                            Text(
                                                text = "#${preview.index + 1} · ${preview.tokens} tokens · ${preview.role}: ${preview.text}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Switch(
                                checked = autoCompressEnabled,
                                onCheckedChange = { autoCompressEnabled = it }
                            )
                            Text(
                                text = stringResource(R.string.chat_page_auto_compress),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        OutlinedTextField(
                            value = autoCompressTriggerTokensInput,
                            onValueChange = { autoCompressTriggerTokensInput = it.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.chat_page_auto_compress_threshold)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )

                        OutlinedTextField(
                            value = additionalPrompt,
                            onValueChange = { additionalPrompt = it },
                            label = { Text(stringResource(R.string.chat_page_compress_additional_prompt)) },
                            placeholder = {
                                Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Switch(
                                checked = generateMemoryLedger,
                                onCheckedChange = { generateMemoryLedger = it }
                            )
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_page_generate_memory_ledger_with_compress),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = if (generateMemoryLedger) {
                                        stringResource(R.string.chat_page_generate_memory_ledger_with_compress_desc)
                                    } else {
                                        stringResource(R.string.chat_page_skip_memory_ledger_with_compress_desc)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.chat_page_compress_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                CompressContextDialogMode.AutoProgress -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(progressMessage.ifBlank { stringResource(R.string.chat_page_compressing) })
                    }
                }

                CompressContextDialogMode.RegenerateConfirm -> {
                    Text(
                        text = regenerateDescription
                            ?: stringResource(R.string.chat_page_regenerate_compression_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            when (mode) {
                CompressContextDialogMode.Manual -> {
                    TextButton(
                        onClick = {
                            val compressMessageCount = compressMessageCountInput.toIntOrNull()?.coerceAtLeast(1) ?: 6
                            val autoThreshold = autoCompressTriggerTokensInput.toIntOrNull()?.coerceAtLeast(1000) ?: 12000
                            onConfirmManual?.invoke(
                                additionalPrompt,
                                compressMessageCount,
                                autoCompressEnabled,
                                autoThreshold,
                                generateMemoryLedger,
                            )
                            onDismiss()
                        }
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                }

                CompressContextDialogMode.AutoProgress -> {
                    TextButton(onClick = { onCancelProgress?.invoke() ?: onDismiss() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }

                CompressContextDialogMode.RegenerateConfirm -> {
                    TextButton(
                        onClick = {
                            onConfirmRegenerate?.invoke()
                            onDismiss()
                        }
                    ) {
                        Text(regenerateActionLabel ?: stringResource(R.string.confirm))
                    }
                }
            }
        },
        dismissButton = {
            when (mode) {
                CompressContextDialogMode.Manual -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }

                CompressContextDialogMode.AutoProgress -> Unit

                CompressContextDialogMode.RegenerateConfirm -> {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    )
}
