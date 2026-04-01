package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator

enum class CompressContextDialogMode {
    Manual,
    AutoProgress,
    RegenerateConfirm,
}

@Composable
fun CompressContextDialog(
    mode: CompressContextDialogMode,
    onDismiss: () -> Unit,
    initialAutoCompressEnabled: Boolean = false,
    initialAutoCompressTriggerTokens: Int = 12000,
    initialKeepRecentMessages: Int = 6,
    progressMessage: String = "",
    regenerateTitle: String? = null,
    regenerateDescription: String? = null,
    regenerateActionLabel: String? = null,
    initialGenerateMemoryLedger: Boolean = true,
    onConfirmManual: ((
        additionalPrompt: String,
        keepRecentMessages: Int,
        autoCompressEnabled: Boolean,
        autoCompressTriggerTokens: Int,
        generateMemoryLedger: Boolean,
    ) -> Unit)? = null,
    onCancelProgress: (() -> Unit)? = null,
    onConfirmRegenerate: (() -> Unit)? = null,
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var keepRecentMessagesInput by remember(initialKeepRecentMessages) {
        mutableStateOf(initialKeepRecentMessages.toString())
    }
    var autoCompressEnabled by remember { mutableStateOf(initialAutoCompressEnabled) }
    var autoCompressTriggerTokensInput by remember { mutableStateOf(initialAutoCompressTriggerTokens.toString()) }
    var generateMemoryLedger by remember { mutableStateOf(initialGenerateMemoryLedger) }

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

                        OutlinedTextField(
                            value = keepRecentMessagesInput,
                            onValueChange = { keepRecentMessagesInput = it.filter(Char::isDigit) },
                            label = { Text(stringResource(R.string.chat_page_compress_keep_recent)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )

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
                            val keepRecentMessages = keepRecentMessagesInput.toIntOrNull()?.coerceAtLeast(0) ?: 6
                            val autoThreshold = autoCompressTriggerTokensInput.toIntOrNull()?.coerceAtLeast(1000) ?: 12000
                            onConfirmManual?.invoke(
                                additionalPrompt,
                                keepRecentMessages,
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
