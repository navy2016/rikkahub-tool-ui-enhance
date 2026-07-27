package me.rerere.rikkahub.data.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcessSessionNavigationSourceTest {
    private val source = listOf(
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt"),
        File("src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt")
    ).first { it.isFile }.readText()

    @Test
    fun initialInteractiveSessionIsConsumedAfterFirstSelection() {
        assertTrue(source.contains("var pendingInitialProcessId by remember(initialProcessId)"))
        assertTrue(source.contains("pendingInitialProcessId = null"))
        assertFalse(source.contains("LaunchedEffect(initialProcessId, sandboxProcesses)"))
    }
}
