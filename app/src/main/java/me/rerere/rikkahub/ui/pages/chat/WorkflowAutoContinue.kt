package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Conversation

/**
 * Workflow AutoContinue Effect
 *
 * 当满足以下条件时，自动发送"继续"消息：
 * 1. 当前 conversation 对应的 workflowState?.autoContinue == true
 * 2. generationDoneFlow 触发且 conversation id 匹配
 * 3. 当前模型非空
 * 4. 当前最后一条消息是 ASSISTANT
 * 5. 当前没有 pending tool
 * 6. 当前没有正在运行的任务
 * 7. autoContinueCount < autoContinueMaxCount（未达到最大次数）
 */
@Composable
fun WorkflowAutoContinue(
    vm: ChatVM,
    conversation: Conversation,
    settings: Settings,
    loadingJob: Job?,
) {
    val currentConversation by rememberUpdatedState(conversation)
    val currentSettings by rememberUpdatedState(settings)
    val currentLoadingJob by rememberUpdatedState(loadingJob)

    LaunchedEffect(Unit) {
        vm.generationDoneFlow.collect { conversationId ->
            // 检查 conversation id 是否匹配
            if (conversationId != currentConversation.id) return@collect

            // 检查 autoContinue 是否开启
            val workflowState = currentConversation.workflowState
            if (workflowState?.autoContinue != true) return@collect

            // 等待正在运行的任务结束
            while (currentLoadingJob?.isActive == true) {
                delay(50)
            }

            // 检查当前模型是否为空
            val model = currentSettings.getCurrentChatModel()
            if (model == null) return@collect

            // 检查最后一条消息是否是 ASSISTANT
            val lastMessage = currentConversation.currentMessages.lastOrNull()
            if (lastMessage == null || lastMessage.role != MessageRole.ASSISTANT) return@collect

            // 检查是否有 pending tool
            val hasPendingTool = currentConversation.currentMessages.any { message ->
                message.parts.any { part ->
                    part is UIMessagePart.Tool && part.isPending
                }
            }
            if (hasPendingTool) return@collect

            // 检查是否达到最大次数（在发送前检查）
            // 注意：这里检查的是当前计数，如果已经达到最大值，则停止
            if (workflowState.autoContinueCount >= workflowState.autoContinueMaxCount) {
                // 达到最大次数，重置计数并停止自动继续
                vm.resetWorkflowAutoContinueCount()
                return@collect
            }

            // 应用设置的延迟时间
            delay(workflowState.autoContinueDelayMs)

            // 先增加计数，再发送消息
            // 这样下一次检查时会使用更新后的计数
            vm.incrementWorkflowAutoContinueCount()

            // 满足所有条件，自动发送"继续"
            vm.handleMessageSend(listOf(UIMessagePart.Text("继续")), answer = true, fromAutoContinue = true)
        }
    }
}
