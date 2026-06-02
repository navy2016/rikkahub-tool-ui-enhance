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
            if (workflowState.suppressNextAutoContinue) {
                vm.updateWorkflowState(workflowState.copy(suppressNextAutoContinue = false))
                return@collect
            }

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

            // 检查是否有未完成 tool；pending 只是其中一种，Approved/Auto 但尚未执行也必须阻止自动继续
            val hasUnfinishedTool = currentConversation.currentMessages.any { message ->
                message.getTools().any { tool -> !tool.isExecuted }
            }
            if (hasUnfinishedTool) return@collect

            // 【关键修复】重新获取最新的 conversation 状态
            val latestWorkflowState = vm.conversation.value.workflowState
            
            // 【关键修复】使用最新的计数进行检查
            if (latestWorkflowState?.autoContinueCount ?: 0 >= latestWorkflowState?.autoContinueMaxCount ?: Int.MAX_VALUE) {
                // 达到最大次数，重置计数并停止自动继续
                vm.resetWorkflowAutoContinueCount()
                return@collect
            }

            // 应用设置的延迟时间
            delay(vm.conversation.value.workflowState?.autoContinueDelayMs ?: 1000L)

            // 先增加计数（同步执行确保完成）
            vm.incrementWorkflowAutoContinueCount()

            // 满足所有条件，自动发送"继续"
            vm.handleMessageSend(listOf(UIMessagePart.Text("继续")), answer = true, fromAutoContinue = true)
        }
    }
}
