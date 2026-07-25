package me.rerere.rikkahub.data.container

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BunCompatibilitySourceTest {
    private val source = listOf(
        File("app/src/main/java/me/rerere/rikkahub/data/container/PRootManager.kt"),
        File("src/main/java/me/rerere/rikkahub/data/container/PRootManager.kt")
    ).first { it.isFile }.readText()

    @Test
    fun bunWrapperIsPreferredOverRawBundledBinaryInPath() {
        assertTrue(source.contains("processEnv[\"PATH\"] = \"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/local/bun/bin\""))
        assertTrue(source.contains("val finalPath = (basePath.split(\":\") + toolPaths)"))
        assertTrue(source.contains("writeBunWrapper(File(localBin, \"bun\"), isBunx = false)"))
        assertTrue(source.contains("writeBunWrapper(File(localBin, \"bunx\"), isBunx = true)"))
    }

    @Test
    fun bunGlobalInstallsUsePersistentCacheStableWorkdirAndCopyBackend() {
        assertTrue(source.contains("""BUN_CACHE_DIR"] = "/root/.cache/bun/install/cache"""))
        assertTrue(source.contains("BUN_RIKKAHUB_WORKDIR:-/root/.bun-rikkahub/work"))
        assertTrue(source.contains("root/.bun-rikkahub/work/node_modules"))
        assertTrue(source.contains("""BUN_LINKER_BACKEND"] = "copyfile"""))
        assertTrue(source.contains("backend = \\\"copyfile\\\""))
        assertTrue(source.contains("run_in_bun_work \"${'$'}cmd\" --backend copyfile"))
    }

    @Test
    fun bunGlobalBinRepairRecreatesPackageBinEntries() {
        assertTrue(source.contains("rikkahub-bun-bin-repair.js"))
        assertTrue(source.contains("package.json"))
        assertTrue(source.contains("pkg.bin"))
        assertTrue(source.contains("fs.symlinkSync(target, out)"))
        assertTrue(source.contains("repair_bun_global_bins"))
        assertTrue(source.contains("/usr/local/bin 2>/dev/null || true"))
    }
}
