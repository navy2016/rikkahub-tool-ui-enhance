package me.rerere.rikkahub.data.container

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationSandboxCleanupSourceTest {
    private val repositorySource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt"),
        File("src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt")
    ).first { it.isFile }.readText()
    private val diSource = listOf(
        File("app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt"),
        File("src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt")
    ).first { it.isFile }.readText()

    @Test
    fun deletingConversationStopsProcessesBeforeDeletingSandboxDirectory() {
        val deleteSection = repositorySource.substringAfter("suspend fun deleteConversation")
            .substringBefore("suspend fun searchMessages")
        assertTrue(deleteSection.contains("backgroundProcessManager.cleanupSandboxProcesses(sandboxId)"))
        assertTrue(deleteSection.contains("SandboxEngine.deleteSandbox(context, sandboxId)"))
        assertTrue(deleteSection.indexOf("cleanupSandboxProcesses") < deleteSection.indexOf("deleteSandbox"))
        assertTrue(diSource.contains("ConversationRepository(get(), get(), get(), get(), get(), get(), get(), get(), get())"))
    }
}
