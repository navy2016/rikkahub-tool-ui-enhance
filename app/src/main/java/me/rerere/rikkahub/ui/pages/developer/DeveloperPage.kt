package me.rerere.rikkahub.ui.pages.developer

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.utils.writeClipboardText
import java.io.File
import kotlin.time.Clock
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileScript
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.AILogging
import org.koin.androidx.compose.koinViewModel

@Composable
fun DeveloperPage(vm: DeveloperVM = koinViewModel()) {
    val pager = rememberPagerState { 1 }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Developer Page",
                        maxLines = 1,
                    )
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                NavigationBarItem(
                    selected = pager.currentPage == 0,
                    onClick = { scope.launch { pager.animateScrollToPage(0) } },
                    label = {
                        Text(text = "Developer")
                    },
                    icon = {
                        Icon(HugeIcons.FileScript, null)
                    }
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pager,
            contentPadding = innerPadding
        ) { page ->
            when (page) {
                0 -> {
                    LoggingPaging(vm = vm)
                }
            }
        }
    }
}

@Composable
fun LoggingPaging(vm: DeveloperVM) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val exportedText = logs.joinToString("\n\n---\n\n") { it.toExportText() }

    fun exportLogs() {
        val file = File(context.cacheDir, "ai_logs_${Clock.System.now().toString().replace(':', '_')}.txt")
        file.writeText(exportedText.ifBlank { "No AI logs" })
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, exportedText.ifBlank { "No AI logs" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出 AI 日志"))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { context.writeClipboardText(exportedText) }, enabled = logs.isNotEmpty()) {
                    Text("复制全部")
                }
                Button(onClick = { exportLogs() }, enabled = logs.isNotEmpty()) {
                    Text("导出全部")
                }
            }
        }
        items(logs) { log ->
            when (log) {
                is AILogging.Generation -> {
                    val text = log.toExportText()
                    Card {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Generation · ${log.providerSetting.name} · ${log.params.model.modelId}")
                            Text("stream=${log.stream}, messages=${log.messages.size}, final=${log.finalMessageCount ?: "-"}, estimatedTokens=${log.estimatedPromptTokens ?: "-"}, contextLimit=${log.contextLimit ?: "-"}, tools=${log.toolsEnabled ?: false}, safety=${log.contextSafetyStatus ?: "-"}")
                            Row {
                                TextButton(onClick = { context.writeClipboardText(text) }) { Text("复制") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun AILogging.toExportText(): String = when (this) {
    is AILogging.Generation -> buildString {
        appendLine("type: generation")
        appendLine("provider: ${providerSetting.name}")
        appendLine("model: ${params.model.modelId}")
        appendLine("stream: $stream")
        appendLine("messages: ${messages.size}")
        appendLine("finalMessageCount: ${finalMessageCount ?: ""}")
        appendLine("estimatedPromptTokens: ${estimatedPromptTokens ?: ""}")
        appendLine("contextLimit: ${contextLimit ?: ""}")
        appendLine("toolsEnabled: ${toolsEnabled ?: false}")
        appendLine("contextSafetyStatus: ${contextSafetyStatus ?: ""}")
        appendLine("maxTokens: ${params.maxTokens ?: ""}")
        appendLine("temperature: ${params.temperature ?: ""}")
        appendLine("topP: ${params.topP ?: ""}")
    }
}

