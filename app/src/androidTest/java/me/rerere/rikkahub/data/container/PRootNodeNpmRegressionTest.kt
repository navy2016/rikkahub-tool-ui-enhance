package me.rerere.rikkahub.data.container

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

@RunWith(AndroidJUnit4::class)
class PRootNodeNpmRegressionTest : KoinComponent {
    private val prootManager: PRootManager by inject()

    @Test
    fun nodeNpmRealWorkflowPassesWithoutJsCompatibilityWrapper() = runBlocking {
        val startResult = prootManager.start()
        assertTrue(
            "Container start failed: ${startResult.exceptionOrNull()?.stackTraceToString()}",
            startResult.isSuccess
        )

        val result = prootManager.executeShellCancellable(
            sandboxId = "proot-node-npm-regression",
            command = "rikkahub-test-node-npm",
            timeoutSeconds = 900,
            executionId = "proot-node-npm-regression"
        )
        val exitCode = result["exitCode"]?.jsonPrimitive?.intOrNull
        val stdout = result["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val stderr = result["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val success = result["success"]?.jsonPrimitive?.booleanOrNull

        assertEquals(
            "Node/npm regression workflow failed.\nSTDOUT:\n$stdout\nSTDERR:\n$stderr",
            0,
            exitCode
        )
        assertEquals(
            "Node/npm regression workflow did not report success.\nSTDOUT:\n$stdout\nSTDERR:\n$stderr",
            true,
            success
        )
        assertTrue(
            "Missing success marker.\nSTDOUT:\n$stdout\nSTDERR:\n$stderr",
            stdout.contains("RIKKAHUB_NODE_NPM_REGRESSION_OK")
        )
        assertTrue(
            "Legacy JS compatibility wrapper leaked into output.\nSTDOUT:\n$stdout\nSTDERR:\n$stderr",
            !stdout.contains("PATCH_INIT") && !stdout.contains("PRESCAN_DONE") && !stdout.contains("ESM_LOADER_OK")
        )
    }

}
