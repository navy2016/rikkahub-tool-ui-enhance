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
        assertTrue(source.contains("processEnv[\"PATH\"] = \"/usr/local/sbin:/usr/local/bin:/usr/local/node/bin"))
        assertTrue(source.contains("listOf(\"/usr/local/sbin\", \"/usr/local/bin\") + toolPaths"))
        assertTrue(source.contains("/usr/local/bun/bin\""))
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
        assertTrue(source.contains("run_in_bun_work"))
        assertTrue(source.contains("--backend copyfile"))
    }

    @Test
    fun ompInstallerUsesOfficialMuslBinaryInsteadOfBunPackage() {
        assertTrue(source.contains("rikkahub-install-omp"))
        assertTrue(source.contains("omp-linux-musl-arm64"))
        assertTrue(source.contains("omp-linux-musl-x64"))
        assertTrue(source.contains("github.com/can1357/oh-my-pi/releases"))
        assertTrue(source.contains("Do not use: bun install -g @oh-my-pi/pi-coding-agent"))
        assertTrue(source.contains("OMP_VERSION:-v17.1.4"))
        assertTrue(source.contains("downloaded omp asset is not an ELF executable"))
        val installSection = source.substringAfter("File(binDir, \"rikkahub-install-omp\")")
            .substringBefore("File(binDir, \"rikkahub-test-omp\")")
        assertTrue(!installSection.contains("cat <<'EOF'"))
    }

    @Test
    fun bunGlobalBinRepairRecreatesPackageBinEntries() {
        assertTrue(source.contains("rikkahub-bun-bin-repair.js"))
        assertTrue(source.contains("package.json"))
        assertTrue(source.contains("pkg.bin"))
        assertTrue(source.contains("fs.symlinkSync(target, out)"))
        assertTrue(source.contains("repair_bun_global_bins"))
        assertTrue(source.contains("sanitize_bun_global_store"))
        assertTrue(source.contains("/tmp/npm-cache"))
        assertTrue(source.contains("packageHasBadSymlink"))
        assertTrue(source.contains("/usr/local/bin 2>/dev/null || true"))
    }
}
