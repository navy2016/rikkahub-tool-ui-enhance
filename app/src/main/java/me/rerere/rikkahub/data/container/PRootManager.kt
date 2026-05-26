package me.rerere.rikkahub.data.container

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Singleton
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.sandbox.SandboxEngine

/**
 * PRoot 容器管理器 - 全局单例架构
 *
 * 核心变更：
 * - 全局单一容器实例（非 per-conversation）
 * - 4 状态管理：未初始化 / 初始化中 / 运行中 / 已停止
 * - upper 层全局共享（所有对话共用 pip 包）
 * - 沙箱目录 per-conversation 隔离（通过 workspace bind mount）
 *
 * 状态流转：
 * NotInitialized → Initializing → Running ←→ Stopped
 *                      ↓              ↓
 *                   Error          NotInitialized (销毁后)
 */
@Singleton
class PRootManager(
    private val context: Context,
    private val settingsStore: SettingsStore
) {

    data class ContainerDirectoryEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long,
    )

    companion object {
        private const val TAG = "PRootManager"
        private const val DEFAULT_TIMEOUT_MS = 300_000L // 5分钟
        private const val DEFAULT_MAX_MEMORY_MB = 6144  // 6GB for compilation tasks

        // Rootfs 版本控制 - 每次更新 alpine rootfs 时递增此版本号
        private const val ROOTFS_VERSION = 2
        private const val ROOTFS_VERSION_FILE = "rootfs_version.txt"

        // PRoot runtime 版本控制 - 每次更新/替换 PRoot 二进制或随附 loader/lib 时递增。
        // 当前资产来自 Termux proot 5.1.107.72 + libtalloc 2.4.3，用于修复现代 Node/npm
        // 在 Android PRoot 环境下 stat/realpath/openat/worker-thread 不一致的问题。
        private const val PROOT_RUNTIME_VERSION = "termux-proot-5.1.107.72-libtalloc-2.4.3-r3-seccomp-auto"
        private const val PROOT_RUNTIME_VERSION_FILE = "proot_runtime_version.txt"
    }

    // 目录
    private val prootDir: File by lazy { File(context.filesDir, "proot") }
    private val rootfsDir: File by lazy { File(context.filesDir, "rootfs") }
    private val containerDir: File by lazy { File(context.filesDir, "container") }

    // 全局容器状态
    private var globalContainer: ContainerState? = null
    private var currentProcess: Process? = null
    private val cancellableProcesses = ConcurrentHashMap<String, Process>()
    private val executionAliases = ConcurrentHashMap<String, String>()
    private val processMutex = Mutex()  // 保护 currentProcess 的并发访问

    // 后台进程管理
    private val backgroundProcesses = ConcurrentHashMap<String, BackgroundProcessRecord>()

    // 状态流
    private val _containerState = MutableStateFlow<ContainerStateEnum>(ContainerStateEnum.NotInitialized)
    val containerState: StateFlow<ContainerStateEnum> = _containerState.asStateFlow()

    // 运行状态（用于工具暴露判断）
    val isRunning: Boolean
        get() = _containerState.value == ContainerStateEnum.Running

    /**
     * 获取容器 upper 层目录路径（用于工具安装）
     * 修复：不再依赖 globalContainer，直接检查目录存在性
     */
    fun getUpperDir(): String? {
        // 优先使用 globalContainer 的路径（如果已创建）
        globalContainer?.upperDir?.let { return it }

        // App 重启后 globalContainer 为 null，直接检查目录
        val upperDir = File(containerDir, "upper")
        return if (upperDir.exists()) upperDir.absolutePath else null
    }

    // 自动管理标志
    @Volatile
    private var autoManagementEnabled = false

    // 自动管理的监听器引用（用于移除）
    private var autoManagementObserver: LifecycleEventObserver? = null

    // 当前容器启用设置
    @Volatile
    private var currentEnableContainerRuntime = false

    /**
     * 检查是否需要初始化（资源文件是否存在且有效）
     */
    fun checkInitializationStatus(): Boolean {
        val prootBinary = File(prootDir, "proot")
        val rootfsValid = rootfsDir.exists()
                && rootfsDir.listFiles()?.isNotEmpty() == true
                && File(rootfsDir, "bin/sh").exists()
                && File(rootfsDir, "bin/sh").length() > 0

        if (prootBinary.exists() && rootfsDir.exists()) {
            val shFile = File(rootfsDir, "bin/sh")
            Log.d(TAG, "Checking rootfs: exists=${rootfsDir.exists()}, " +
                    "hasFiles=${rootfsDir.listFiles()?.isNotEmpty()}, " +
                    "shExists=${shFile.exists()}, " +
                    "shSize=${if (shFile.exists()) shFile.length() else 0}")
        }

        return prootBinary.exists() && isPRootRuntimeCurrent() && rootfsValid
    }

    private fun isPRootRuntimeCurrent(): Boolean {
        val prootBinary = File(prootDir, "proot")
        val versionFile = File(prootDir, PROOT_RUNTIME_VERSION_FILE)
        return runCatching {
            prootBinary.exists() && versionFile.exists() &&
                versionFile.readText().trim() == PROOT_RUNTIME_VERSION
        }.getOrDefault(false)
    }

    /**
     * 初始化 PRoot 环境（下载/解压资源）
     * 从 NotInitialized → Initializing → Running
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_containerState.value != ContainerStateEnum.NotInitialized) {
                return@withContext Result.failure(IllegalStateException("Already initialized or initializing"))
            }
            
            Log.d(TAG, "Starting container initialization...")
            _containerState.value = ContainerStateEnum.Initializing(0f)
            
            // 创建目录
            Log.d(TAG, "Creating directories: prootDir=$prootDir, rootfsDir=$rootfsDir, containerDir=$containerDir")
            prootDir.mkdirs()
            rootfsDir.mkdirs()
            containerDir.mkdirs()
            
            _containerState.value = ContainerStateEnum.Initializing(0.2f)
            
            // 解压 PRoot 二进制文件
            Log.d(TAG, "Extracting PRoot binary...")
            extractPRootBinary()
            Log.d(TAG, "PRoot binary extracted successfully")
            
            _containerState.value = ContainerStateEnum.Initializing(0.5f)
            
            // 解压 Alpine rootfs
            Log.d(TAG, "Extracting Alpine rootfs...")
            extractAlpineRootfs()
            Log.d(TAG, "Alpine rootfs extracted successfully")
            
            _containerState.value = ContainerStateEnum.Initializing(0.8f)
            
            hardenAlpineBaseEnvironment()

            // 创建全局容器
            Log.d(TAG, "Creating global container...")
            createGlobalContainer()
            Log.d(TAG, "Global container created successfully")

            // 旧版本曾通过 JS monkey patch 包装 node/npm 来规避 PRoot 文件系统兼容问题。
            // 长期方案改为替换 PRoot runtime，因此初始化时仅清理遗留 wrapper，确保后续
            // node/npm 直接走 Alpine 官方二进制，真实暴露 PRoot 层回归。
            cleanupLegacyNodeNpmCompatibilityWrappers(File(containerDir, "upper"))

            _containerState.value = ContainerStateEnum.Running
            Log.d(TAG, "Container initialization completed successfully!")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Container initialization failed", e)
            _containerState.value = ContainerStateEnum.Error(e.message ?: "Unknown error: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    /**
     * 启动容器（从 Stopped 到 Running）
     * 如果从未初始化，会先执行初始化
     */
    suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (_containerState.value) {
                is ContainerStateEnum.Running -> {
                    return@withContext Result.success(Unit) // 已经在运行
                }
                is ContainerStateEnum.Initializing -> {
                    return@withContext Result.failure(IllegalStateException("Already initializing"))
                }
                is ContainerStateEnum.NotInitialized -> {
                    // 需要初始化
                    return@withContext initialize()
                }
                is ContainerStateEnum.Stopped -> {
                    // 从停止状态恢复；升级后先刷新 PRoot runtime，避免继续使用旧二进制。
                    extractPRootBinary()
                    if (globalContainer == null) {
                        createGlobalContainer()
                    } else {
                        // 确保子目录/运行时脚本存在（兼容升级前已初始化的容器）
                        val upperDir = File(containerDir, "upper")
                        ensureContainerRuntimeFiles(upperDir)
                        cleanupLegacyNodeNpmCompatibilityWrappers(upperDir)
                    }
                    _containerState.value = ContainerStateEnum.Running
                    return@withContext Result.success(Unit)
                }
                is ContainerStateEnum.Error -> {
                    // 错误后重试，需要重新初始化
                    _containerState.value = ContainerStateEnum.NotInitialized
                    return@withContext initialize()
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 停止容器（Running → Stopped）
     * 保留 upper 层，只 kill 进程
     */
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_containerState.value != ContainerStateEnum.Running) {
                return@withContext Result.failure(IllegalStateException("Container not running"))
            }
            
            // 终止当前进程（使用 Mutex 保护）
            processMutex.withLock {
                currentProcess?.destroyForcibly()
                currentProcess = null
            }

            _containerState.value = ContainerStateEnum.Stopped
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 销毁容器（任意状态 → NotInitialized）
     * 删除 upper 层和 rootfs，需要重新初始化
     */
    suspend fun destroy(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 停止进程（使用 Mutex 保护）
            processMutex.withLock {
                currentProcess?.destroyForcibly()
                currentProcess = null
            }

            // 删除 upper 层（可写层）
            val upperDir = File(containerDir, "upper")
            if (upperDir.exists()) {
                upperDir.deleteRecursively()
            }

            // 删除 work 目录（OverlayFS 工作目录）
            val workDir = File(containerDir, "work")
            if (workDir.exists()) {
                workDir.deleteRecursively()
            }

            // 删除 rootfs（只读层）- 彻底重置，防止污染
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
                Log.d(TAG, "Rootfs deleted for complete reset")
            }

            // 清理可能残留的开发工具配置文件
            val configDir = File(context.filesDir, "container/config")
            if (configDir.exists()) {
                configDir.deleteRecursively()
            }

            globalContainer = null
            _containerState.value = ContainerStateEnum.NotInitialized
            Log.d(TAG, "Container destroyed completely")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 执行 Python 代码
     * @param sandboxId 沙箱 ID（通常是 conversationId）
     */
    suspend fun executePython(
        sandboxId: String,
        code: String,
        packages: List<String> = emptyList(),
        pipInstallTimeoutSeconds: Int = 120,
        commandTimeoutSeconds: Int = 300
    ): JsonObject {
        if (_containerState.value != ContainerStateEnum.Running) {
            return buildJsonObject {
                put("success", false)
                put("error", "Container not running. Current state: ${_containerState.value}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }
        
        return try {
            // 确保沙箱目录存在
            val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId")
            val deliveryDir = SandboxEngine.getDeliveryDir(context, sandboxId)
            val runtimeSkillsDir = SandboxEngine.getRuntimeSkillsDir(context, sandboxId)
            sandboxDir.mkdirs()
            
            // 安装依赖
            if (packages.isNotEmpty()) {
                val pipResult = execInContainer(
                    sandboxId = sandboxId,
                    command = listOf("pip", "install") + packages,
                    timeoutMs = pipInstallTimeoutSeconds * 1000L
                )
                
                if (pipResult.exitCode != 0) {
                    return buildJsonObject {
                        put("success", false)
                        put("error", "Failed to install packages: ${pipResult.stderr}")
                        put("exitCode", pipResult.exitCode)
                        put("stdout", pipResult.stdout)
                        put("stderr", pipResult.stderr)
                    }
                }
            }

            // 使用 Base64 编码写入脚本文件（避免 shell 注入）
            val scriptFile = "/tmp/script_${System.currentTimeMillis()}.py"
            val encodedCode = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val toolEnv = getToolEnvironment()
            val writeResult = execInContainer(
                sandboxId = sandboxId,
                command = listOf("sh", "-c", "echo '$encodedCode' | base64 -d > $scriptFile"),
                env = toolEnv,
                timeoutMs = commandTimeoutSeconds * 1000L
            )

            if (writeResult.exitCode != 0) {
                return buildJsonObject {
                    put("success", false)
                    put("error", "Failed to write script: ${writeResult.stderr}")
                    put("exitCode", writeResult.exitCode)
                    put("stdout", writeResult.stdout)
                    put("stderr", writeResult.stderr)
                }
            }

            // 执行脚本
            val execResult = execInContainer(
                sandboxId = sandboxId,
                command = listOf("python3", scriptFile),
                env = toolEnv,
                timeoutMs = commandTimeoutSeconds * 1000L
            )

            buildJsonObject {
                put("success", execResult.exitCode == 0)
                put("exitCode", execResult.exitCode)
                put("stdout", execResult.stdout)
                put("stderr", execResult.stderr)  // 始终包含 stderr，即使为空
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("success", false)
                put("error", "Container execution error: ${e.message}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }
    }

    /**
     * 执行 Shell 命令
     * @param sandboxId 沙箱 ID（通常是 conversationId）
     */
    suspend fun executeShell(
        sandboxId: String,
        command: String,
        timeoutSeconds: Int = 300
    ): JsonObject {
        Log.d(TAG, "[ExecuteShell] ========== Command: $command ==========")
        Log.d(TAG, "[ExecuteShell] Container state: ${_containerState.value}")

        if (_containerState.value != ContainerStateEnum.Running) {
            Log.e(TAG, "[ExecuteShell] FAILED: Container not running!")
            return buildJsonObject {
                put("success", false)
                put("error", "Container not running. Current state: ${_containerState.value}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }

        return try {
            // 确保沙箱目录存在
            val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId")
            sandboxDir.mkdirs()

            // 获取已安装工具的环境变量
            val toolEnv = getToolEnvironment()
            Log.d(TAG, "[ExecuteShell] Environment: $toolEnv")

            val execResult = execInContainer(
                sandboxId = sandboxId,
                command = listOf("sh", "-c", command),
                env = toolEnv,
                timeoutMs = timeoutSeconds * 1000L
            )

            Log.d(TAG, "[ExecuteShell] Result - exitCode=${execResult.exitCode}")
            Log.d(TAG, "[ExecuteShell] stdout: ${execResult.stdout.take(500)}")
            Log.d(TAG, "[ExecuteShell] stderr: ${execResult.stderr.take(500)}")

            buildJsonObject {
                put("success", execResult.exitCode == 0)
                put("exitCode", execResult.exitCode)
                put("stdout", execResult.stdout)
                put("stderr", execResult.stderr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ExecuteShell] Exception!", e)
            buildJsonObject {
                put("success", false)
                put("error", "Shell execution error: ${e.message}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }
    }

    
    suspend fun executeShellCancellable(
        sandboxId: String,
        command: String,
        timeoutSeconds: Int = 300,
        executionId: String = sandboxId
    ): JsonObject {
        val effectiveExecutionId = if (executionId == sandboxId) {
            "${sandboxId}_shell_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        } else {
            executionId
        }
        if (effectiveExecutionId != executionId) {
            executionAliases[executionId] = effectiveExecutionId
        }

        Log.d(TAG, "[ExecuteShellCancellable] ========== Command: $command ==========")
        Log.d(TAG, "[ExecuteShellCancellable] Container state: ${_containerState.value}")

        if (_containerState.value != ContainerStateEnum.Running) {
            Log.e(TAG, "[ExecuteShellCancellable] FAILED: Container not running!")
            return buildJsonObject {
                put("success", false)
                put("error", "Container not running. Current state: ${_containerState.value}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }

        return try {
            // 确保沙箱目录存在
            val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId")
            sandboxDir.mkdirs()

            // 获取已安装工具的环境变量
            val toolEnv = getToolEnvironment()
            Log.d(TAG, "[ExecuteShellCancellable] Environment: $toolEnv")

            val execResult = execInContainerCancellable(
                sandboxId = sandboxId,
                command = listOf("sh", "-c", command),
                env = toolEnv,
                timeoutMs = timeoutSeconds * 1000L,
                executionId = effectiveExecutionId
            )

            Log.d(TAG, "[ExecuteShellCancellable] Result - exitCode=${execResult.exitCode}")
            Log.d(TAG, "[ExecuteShellCancellable] stdout: ${execResult.stdout.take(500)}")
            Log.d(TAG, "[ExecuteShellCancellable] stderr: ${execResult.stderr.take(500)}")

            buildJsonObject {
                put("success", execResult.exitCode == 0)
                put("exitCode", execResult.exitCode)
                put("stdout", execResult.stdout)
                put("stderr", execResult.stderr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[ExecuteShellCancellable] Exception!", e)
            buildJsonObject {
                put("success", false)
                put("error", "Shell execution error: ${e.message}")
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "")
            }
        }
    }

    fun killExecution(executionId: String): Boolean {
        val effectiveId = executionAliases.remove(executionId) ?: executionId
        val process = cancellableProcesses.remove(effectiveId) ?: return false
        return try {
            Log.d(TAG, "[KillExecution] Killing process for execution: $executionId ($effectiveId)")
            terminateProcessTree(process, force = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "[KillExecution] Failed to kill process", e)
            false
        }
    }

    suspend fun listContainerDirectory(
        sandboxId: String,
        containerPath: String,
    ): List<ContainerDirectoryEntry> {
        val normalizedPath = normalizeContainerPath(containerPath)
        val mountedHostDir = SandboxEngine.resolveHostFileForContainerPath(context, sandboxId, normalizedPath)
        if (mountedHostDir != null && mountedHostDir.exists() && mountedHostDir.isDirectory) {
            return mountedHostDir.listFiles()
                ?.map { file ->
                    val childPath = buildContainerChildPath(normalizedPath, file.name)
                    ContainerDirectoryEntry(
                        name = file.name,
                        path = childPath,
                        isDirectory = file.isDirectory,
                        size = if (file.isFile) file.length() else 0L,
                        modified = file.lastModified(),
                    )
                }
                ?.sortedWith(compareBy<ContainerDirectoryEntry>({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        }

        if (_containerState.value != ContainerStateEnum.Running) return emptyList()

        val escaped = normalizedPath.replace("'", "'\"'\"'")
        val script = """
            target='$escaped'
            if [ ! -d "${'$'}target" ]; then
              exit 0
            fi
            find "${'$'}target" -mindepth 1 -maxdepth 1 -exec stat -c '%n\t%F\t%s\t%Y' {} \;
        """.trimIndent()
        val result = execInContainer(
            sandboxId = sandboxId,
            command = listOf("sh", "-c", script),
            env = getToolEnvironment(),
        )
        if (result.exitCode != 0) return emptyList()

        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@mapNotNull null
                val fullPath = parts[0]
                ContainerDirectoryEntry(
                    name = fullPath.substringAfterLast('/').ifBlank { fullPath },
                    path = fullPath,
                    isDirectory = parts[1].contains("directory", ignoreCase = true),
                    size = parts[2].toLongOrNull() ?: 0L,
                    modified = parts[3].toLongOrNull()?.times(1000L) ?: 0L,
                )
            }
            .sortedWith(compareBy<ContainerDirectoryEntry>({ !it.isDirectory }, { it.name.lowercase() }))
            .toList()
    }

    suspend fun readContainerTextFile(
        sandboxId: String,
        containerPath: String,
        maxBytes: Int = 128 * 1024,
    ): String? {
        val normalizedPath = normalizeContainerPath(containerPath)
        val mountedHostFile = SandboxEngine.resolveHostFileForContainerPath(context, sandboxId, normalizedPath)
        if (mountedHostFile != null && mountedHostFile.exists() && mountedHostFile.isFile) {
            return runCatching { mountedHostFile.readText() }.getOrNull()
        }
        if (_containerState.value != ContainerStateEnum.Running) return null

        val escaped = normalizedPath.replace("'", "'\"'\"'")
        val script = """
            target='$escaped'
            if [ ! -f "${'$'}target" ]; then
              exit 0
            fi
            head -c $maxBytes "${'$'}target"
        """.trimIndent()
        val result = execInContainer(
            sandboxId = sandboxId,
            command = listOf("sh", "-c", script),
            env = getToolEnvironment(),
        )
        return if (result.exitCode == 0) result.stdout else null
    }

    /**
     * 获取已安装的包列表（用于统计展示）
     */
    suspend fun getInstalledPackages(timeoutSeconds: Int = 30): List<String> = withContext(Dispatchers.IO) {
        try {
            val upperDir = File(containerDir, "upper/usr/local")
            if (!upperDir.exists()) return@withContext emptyList()
            
            // 读取 pip 列表
            val result = execInContainer(
                sandboxId = "system",
                command = listOf("pip", "list", "--format=freeze"),
                timeoutMs = timeoutSeconds * 1000L
            )
            
            if (result.exitCode == 0) {
                result.stdout.lines()
                    .mapNotNull { line ->
                        line.substringBefore("==").takeIf { it.isNotBlank() }
                    }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取容器大小（用于统计展示）
     */
    fun getContainerSize(): Long {
        return calculateDirectorySize(containerDir)
    }

    /**
     * 基础 PATH（Alpine Linux 默认）
     */
    private val basePath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /**
     * 获取已安装工具的环境变量（用于容器命令执行）
     * 避免循环依赖，直接检查 upper 层目录
     */
    private fun getToolEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        val toolPaths = mutableListOf<String>()
        val upperLocalDir = File(containerDir, "upper/usr/local")

        Log.d(TAG, "[ToolEnv] Checking tools in: ${upperLocalDir.absolutePath}")
        Log.d(TAG, "[ToolEnv] Directory exists: ${upperLocalDir.exists()}")

        // Node.js
        val nodeExists = File(upperLocalDir, "node/bin/node").exists()
        Log.d(TAG, "[ToolEnv] Node.js exists: $nodeExists")
        if (nodeExists) {
            toolPaths.add("/usr/local/node/bin")
            env["NODE_HOME"] = "/usr/local/node"
        }

        // Go
        val goExists = File(upperLocalDir, "go/bin/go").exists()
        Log.d(TAG, "[ToolEnv] Go exists: $goExists")
        if (goExists) {
            toolPaths.add("/usr/local/go/bin")
            env["GOROOT"] = "/usr/local/go"
        }

        // OpenJDK
        val jdkDirs = upperLocalDir.listFiles { f -> f.isDirectory && f.name.startsWith("jdk") }
        Log.d(TAG, "[ToolEnv] JDK dirs: ${jdkDirs?.map { it.name }}")
        if (jdkDirs?.isNotEmpty() == true) {
            toolPaths.add("/usr/local/${jdkDirs[0].name}/bin")
            env["JAVA_HOME"] = "/usr/local/${jdkDirs[0].name}"
        }

        // Rust
        val rustExists = File(upperLocalDir, "rust/bin/rustc").exists()
        Log.d(TAG, "[ToolEnv] Rust exists: $rustExists")
        if (rustExists) {
            toolPaths.add("/usr/local/rust/bin")
            env["RUST_HOME"] = "/usr/local/rust"
        }

        // Python (包括 pip)
        val pythonBinDir = when {
            File(upperLocalDir, "python3/bin/python3").exists() -> "python3/bin"
            File(upperLocalDir, "python/bin/python3").exists() -> "python/bin"
            File(upperLocalDir, "python/bin/python").exists() -> "python/bin"
            else -> null
        }
        Log.d(TAG, "[ToolEnv] Python bin dir: $pythonBinDir")
        if (pythonBinDir != null) {
            val pythonHome = "/usr/local/${pythonBinDir.substringBefore("/")}"
            toolPaths.add("$pythonHome/bin")
            env["PYTHON_HOME"] = pythonHome
            // 确保 pip 能找到
            env["PIP_CACHE_DIR"] = "/tmp/pip-cache"
            Log.d(TAG, "[ToolEnv] Python configured: PYTHON_HOME=$pythonHome, pip available")
        }

        // Node.js 模块路径（确保 npm 可用）
        val nodePath = listOf("/usr/local/lib/node_modules", "/usr/lib/node_modules")
        env["NODE_PATH"] = nodePath.joinToString(":")
        env["NPM_CONFIG_PREFIX"] = "/usr/local"
        env["npm_config_prefix"] = "/usr/local"
        env["NPM_CONFIG_CACHE"] = "/tmp/npm-cache"

        // 组合 PATH：工具路径 + 基础 PATH（确保基础命令可用）
        val finalPath = if (toolPaths.isNotEmpty()) {
            toolPaths.joinToString(":") + ":" + basePath
        } else {
            basePath
        }
        env["PATH"] = finalPath

        Log.d(TAG, "[ToolEnv] Generated PATH: $finalPath")
        Log.d(TAG, "[ToolEnv] Full env: $env")

        return env
    }

    // ==================== Private Methods ====================

    private fun hardenAlpineBaseEnvironment() {
        runCatching {
            val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
            File(etcDir, "apk").mkdirs()
            File(etcDir, "apk/repositories").writeText(
                "https://dl-cdn.alpinelinux.org/alpine/v3.19/main\n" +
                    "https://dl-cdn.alpinelinux.org/alpine/v3.19/community\n"
            )
            File(etcDir, "resolv.conf").writeText(
                "nameserver 1.1.1.1\n" +
                    "nameserver 8.8.8.8\n" +
                    "options timeout:2 attempts:2\n"
            )
            File(etcDir, "nsswitch.conf").writeText("hosts: files dns\n")
            writeContainerHostsFile(File(containerDir, "upper"))
            File(rootfsDir, "tmp").apply {
                mkdirs()
                setReadable(true, false)
                setWritable(true, false)
                setExecutable(true, false)
            }
            val profileDir = File(etcDir, "profile.d").apply { mkdirs() }
            File(profileDir, "rikkahub.sh").writeText(
                "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n" +
                    "export NPM_CONFIG_PREFIX=/usr/local\n" +
                    "export npm_config_prefix=/usr/local\n" +
                    "export NPM_CONFIG_CACHE=/tmp/npm-cache\n" +
                    "export NPM_CONFIG_AUDIT=false\n" +
                    "export NPM_CONFIG_FUND=false\n" +
                    "export NO_UPDATE_NOTIFIER=1\n" +
                    "export PIP_DISABLE_PIP_VERSION_CHECK=1\n"
            )
        }.onFailure {
            Log.w(TAG, "Failed to harden Alpine base environment", it)
        }
    }

    private fun ensureContainerRuntimeFiles(upperDir: File = File(containerDir, "upper")) {
        File(upperDir, "usr/local").mkdirs()
        File(upperDir, "usr/local/bin").mkdirs()
        File(upperDir, "usr/local/lib/node_modules").mkdirs()
        File(upperDir, "usr/lib").mkdirs()
        File(upperDir, "root").mkdirs()
        File(upperDir, "tmp").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        File(upperDir, "var/cache/apk").mkdirs()
        val etcDir = File(upperDir, "etc").apply { mkdirs() }
        File(etcDir, "apk").mkdirs()
        File(etcDir, "apk/repositories").writeText(
            "https://dl-cdn.alpinelinux.org/alpine/v3.19/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.19/community\n"
        )
        File(etcDir, "resolv.conf").writeText(
            "nameserver 1.1.1.1\n" +
                "nameserver 8.8.8.8\n" +
                "options timeout:2 attempts:2\n"
        )
        File(upperDir, "root/.npmrc").writeText(
            "prefix=/usr/local\ncache=/tmp/npm-cache\naudit=false\nfund=false\nupdate-notifier=false\n"
        )
        writeContainerUtilityScripts(upperDir)
    }

    private fun writeContainerUtilityScripts(upperDir: File) {
        val binDir = File(upperDir, "usr/local/bin").apply { mkdirs() }
        File(binDir, "rikkahub-fix-apk").apply {
            writeText("""#!/bin/sh
set -u
printf 'https://dl-cdn.alpinelinux.org/alpine/v3.19/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.19/community\n' > /etc/apk/repositories || exit 11
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:2 attempts:2\n' > /etc/resolv.conf || exit 12
mkdir -p /tmp /tmp/npm-cache /tmp/pip-cache /var/cache/apk
chmod 1777 /tmp /tmp/npm-cache /tmp/pip-cache 2>/dev/null || true
command -v apk >/dev/null 2>&1 || { echo 'apk not found in PATH' >&2; exit 13; }
apk update || apk update --no-cache
""")
            setExecutable(true, false)
        }
        File(binDir, "rikkahub-tmux").apply {
            writeText("""#!/bin/sh
set -u
SESSION="${'$'}{1:-main}"
if command -v tmux >/dev/null 2>&1; then
    exec tmux new-session -A -s "${'$'}SESSION"
fi
printf '%s\n' "tmux is not installed; attempting to install it..."
if command -v rikkahub-fix-apk >/dev/null 2>&1; then
    rikkahub-fix-apk >/dev/null 2>&1 || true
fi
if command -v apk >/dev/null 2>&1; then
    if apk add --no-cache tmux; then
        exec tmux new-session -A -s "${'$'}SESSION"
    fi
fi
printf '%s\n' "Unable to start tmux. Falling back to sh -l. Run 'rikkahub-install-cli' or 'apk add --no-cache tmux' to enable tmux."
exec sh -l
""")
            setExecutable(true, false)
        }
        File(binDir, "rikkahub-install-cli").apply {
            writeText("""#!/bin/sh
set -u
rikkahub-fix-apk || exit ${'$'}?
apk add --no-cache bash ca-certificates curl git openssh-client vim nano util-linux nodejs npm tmux || exit ${'$'}?
command -v update-ca-certificates >/dev/null 2>&1 && update-ca-certificates || true
npm config set prefix /usr/local
npm config set cache /tmp/npm-cache
npm config set audit false
npm config set fund false
npm config set update-notifier false
npm install -g @anthropic-ai/claude-code @openai/codex opencode-ai
""")
            setExecutable(true, false)
        }
        File(binDir, "rikkahub-test-node-npm").apply {
            writeText("""#!/bin/sh
set -eu
printf '%s\n' '== RikkaHub PRoot Node/npm regression =='
printf '%s\n' "kernel=${'$'}(uname -a 2>/dev/null || true)"
printf '%s\n' "arch=${'$'}(uname -m 2>/dev/null || true)"

rikkahub-fix-apk
apk add --no-cache nodejs npm

# Remove only obsolete JS monkey-patch wrappers from previous app versions.
# Non-wrapper user-installed binaries in /usr/local/bin are left untouched.
for f in /usr/local/bin/node /usr/local/bin/npm /usr/local/bin/npx; do
    if [ -f "${'$'}f" ] && grep -qE 'PRoot Node.js Wrapper|PATCH_INIT|PRESCAN_DONE|ESM_LOADER_OK' "${'$'}f" 2>/dev/null; then
        rm -f "${'$'}f"
    fi
done
hash -r 2>/dev/null || true

printf '%s\n' "node_path=${'$'}(command -v node)"
printf '%s\n' "npm_path=${'$'}(command -v npm)"
printf '%s\n' "npx_path=${'$'}(command -v npx)"
node --version
npm --version

node <<'NODE'
const fs = require('node:fs');
const checks = ['/usr/bin/node', '/usr/lib/node_modules/npm/package.json'];
for (const p of checks) {
  const st = fs.statSync(p);
  const real = fs.realpathSync(p);
  console.log('main-fs-ok', p, st.isFile(), real);
}
NODE

node <<'NODE'
const { Worker } = require('node:worker_threads');
new Promise((resolve, reject) => {
  const worker = new Worker(`
    const fs = require('node:fs');
    const { parentPort } = require('node:worker_threads');
    const p = '/usr/lib/node_modules/npm/package.json';
    parentPort.postMessage({ exists: fs.existsSync(p), file: fs.statSync(p).isFile(), real: fs.realpathSync(p) });
  `, { eval: true });
  worker.on('message', msg => {
    console.log('worker-fs-ok', JSON.stringify(msg));
    if (!msg.exists || !msg.file) reject(new Error('worker fs check failed'));
    else resolve();
  });
  worker.on('error', reject);
  worker.on('exit', code => { if (code !== 0) reject(new Error('worker exit ' + code)); });
}).catch(err => {
  console.error(err && err.stack || err);
  process.exit(1);
});
NODE

work="${'$'}{TMPDIR:-/tmp}/rikkahub-npm-regression.${'$'}${'$'}"
mkdir -p "${'$'}work"
trap 'rm -rf "${'$'}work"' EXIT
cd "${'$'}work"
npm init -y >/dev/null
npm install --no-audit --no-fund lodash@4.17.21 chalk@5.3.0 cowsay@1.5.0
node -e "console.log('lodash-ok', require('lodash').VERSION)"
node --input-type=module -e "import chalk from 'chalk'; console.log(chalk.green('chalk-esm-ok'))"
npm exec -- cowsay local > cowsay-local.txt
grep -q local cowsay-local.txt
export npm_config_prefix="${'$'}work/npm-global"
export PATH="${'$'}work/npm-global/bin:${'$'}PATH"
npm install -g --no-audit --no-fund cowsay@1.5.0
cowsay global > cowsay-global.txt
grep -q global cowsay-global.txt
printf '%s\n' 'RIKKAHUB_NODE_NPM_REGRESSION_OK'
""")
            setExecutable(true, false)
        }
    }


    private fun cleanupLegacyNodeNpmCompatibilityWrappers(upperDir: File = File(containerDir, "upper")) {
        runCatching {
            val binDir = File(upperDir, "usr/local/bin")
            val legacyMarkers = listOf("PRoot Node.js Wrapper", "PATCH_INIT", "PRESCAN_DONE", "ESM_LOADER_OK")
            listOf("node", "npm", "npx").forEach { name ->
                val file = File(binDir, name)
                if (!file.exists() || !file.isFile) return@forEach
                val head = runCatching { file.readText().take(8192) }.getOrDefault("")
                if (legacyMarkers.any { marker -> head.contains(marker) }) {
                    if (file.delete()) {
                        Log.i(TAG, "Removed legacy Node/npm compatibility wrapper: ${file.absolutePath}")
                    } else {
                        Log.w(TAG, "Failed to remove legacy Node/npm compatibility wrapper: ${file.absolutePath}")
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "Failed to clean legacy Node/npm compatibility wrappers", it)
        }
    }

    private suspend fun createGlobalContainer() = withContext(Dispatchers.IO) {
        val workDir = File(containerDir, "work").apply { mkdirs() }
        val upperDir = File(containerDir, "upper").apply { mkdirs() }

        // 创建/刷新 bind mount 所需的子目录、配置和工具脚本
        ensureContainerRuntimeFiles(upperDir)
        cleanupLegacyNodeNpmCompatibilityWrappers(upperDir)

        globalContainer = ContainerState(
            id = "global",
            workDir = workDir.absolutePath,
            upperDir = upperDir.absolutePath
        )
    }

    private suspend fun execInContainerCancellable(
        sandboxId: String,
        command: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        env: Map<String, String> = emptyMap(),
        executionId: String
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val container = globalContainer
                ?: return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Global container not created"
                )

            val prootCmd = buildProotCommand(sandboxId, command, env, container)
            val processBuilder = ProcessBuilder(prootCmd)
            processBuilder.redirectErrorStream(false)
            setupProcessEnvironment(processBuilder.environment(), env)

            Log.d(TAG, "[ExecInContainerCancellable] Executing: $executionId")

            val process = processBuilder.start()
            cancellableProcesses[executionId] = process

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stdout", e)
                }
            }

            val stderrThread = Thread {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderrBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stderr", e)
                }
            }

            stdoutThread.start()
            stderrThread.start()

            val startTime = System.currentTimeMillis()
            var finished = false
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (!isActive) {
                    Log.d(TAG, "[ExecInContainerCancellable] Coroutine cancelled, killing process")
                    terminateProcessTree(process, force = true)
                    cancellableProcesses.remove(executionId)
                    executionAliases.entries.removeIf { it.value == executionId }
                    stdoutThread.join(500)
                    stderrThread.join(500)
                    return@withContext ExecutionResult(
                        exitCode = -1,
                        stdout = stdoutBuilder.toString(),
                        stderr = stderrBuilder.toString() + "\nExecution was cancelled"
                    )
                }
                if (process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
            }

            stdoutThread.join(1000)
            stderrThread.join(1000)
            cancellableProcesses.remove(executionId)
            executionAliases.entries.removeIf { it.value == executionId }

            if (!finished) {
                terminateProcessTree(process, force = true)
                return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = stdoutBuilder.toString(),
                    stderr = stderrBuilder.toString() + "\nExecution timed out"
                )
            }

            ExecutionResult(
                exitCode = process.exitValue(),
                stdout = stdoutBuilder.toString().trim(),
                stderr = stderrBuilder.toString().trim()
            )
        } catch (e: Exception) {
            cancellableProcesses.remove(executionId)
            executionAliases.entries.removeIf { it.value == executionId }
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Execution error: ${e.message}"
            )
        }
    }

    private suspend fun execInContainer(
        sandboxId: String,
        command: List<String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        env: Map<String, String> = emptyMap()
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val container = globalContainer
                ?: return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Global container not created"
                )

            val prootCmd = buildProotCommand(sandboxId, command, env, container)

            // 执行命令
            val processBuilder = ProcessBuilder(prootCmd)
            processBuilder.redirectErrorStream(false) // 分离 stdout 和 stderr

            // 设置环境变量
            val processEnv = processBuilder.environment()
            setupProcessEnvironment(processEnv, env)

            Log.d(TAG, "[ExecInContainer] ========== Executing command ==========")
            Log.d(TAG, "[ExecInContainer] Command: $command")
            Log.d(TAG, "[ExecInContainer] Full prootCmd: $prootCmd")
            Log.d(TAG, "[ExecInContainer] Final env PATH: ${processEnv["PATH"]}")
            Log.d(TAG, "[ExecInContainer] Full env: $processEnv")

            val process = processBuilder.start()

            // 使用 Mutex 保护 currentProcess 赋值
            processMutex.withLock {
                currentProcess = process
            }

            // 并行读取 stdout 和 stderr
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val stdoutThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stdoutBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stdout", e)
                }
            }

            val stderrThread = Thread {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            stderrBuilder.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stderr", e)
                }
            }

            stdoutThread.start()
            stderrThread.start()

            // 等待完成或超时
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

            // 等待读取线程完成
            stdoutThread.join(1000)
            stderrThread.join(1000)

            if (!finished) {
                terminateProcessTree(process, force = true)
                // 清理 currentProcess
                processMutex.withLock {
                    if (currentProcess == process) {
                        currentProcess = null
                    }
                }
                return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = stdoutBuilder.toString(),
                    stderr = stderrBuilder.toString() + "\nExecution timed out after ${timeoutMs}ms"
                )
            }

            val exitCode = process.exitValue()

            // 使用 Mutex 保护 currentProcess 清理
            processMutex.withLock {
                if (currentProcess == process) {
                    currentProcess = null
                }
            }

            val result = ExecutionResult(
                exitCode = exitCode,
                stdout = stdoutBuilder.toString().trim(),
                stderr = stderrBuilder.toString().trim()
            )

            Log.d(TAG, "[ExecInContainer] ========== Execution completed ==========")
            Log.d(TAG, "[ExecInContainer] exitCode=$exitCode")
            Log.d(TAG, "[ExecInContainer] stdout: ${result.stdout.take(500)}")
            Log.d(TAG, "[ExecInContainer] stderr: ${result.stderr.take(500)}")

            result
        } catch (e: Exception) {
            // 清理 currentProcess
            processMutex.withLock {
                currentProcess = null
            }
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Execution error: ${e.message}"
            )
        }
    }

    /**
     * 清理 upper 层的临时文件，但保留已安装的开发工具
     * 用于释放空间而不影响开发环境
     */
    suspend fun cleanupUpperLayer(): Result<CleanupResult> = withContext(Dispatchers.IO) {
        try {
            val upperDir = File(containerDir, "upper")
            if (!upperDir.exists()) {
                return@withContext Result.success(CleanupResult(0, 0, emptyList()))
            }

            var totalFreedBytes = 0L
            var totalFilesCleaned = 0
            val cleanedPaths = mutableListOf<String>()

            // 1. 清理 /tmp 目录（临时文件）
            val tmpDir = File(upperDir, "tmp")
            if (tmpDir.exists()) {
                val size = calculateDirectorySize(tmpDir)
                tmpDir.listFiles()?.forEach { it.deleteRecursively() }
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/tmp")
            }

            // 2. 清理 /var/cache 目录
            val cacheDir = File(upperDir, "var/cache")
            if (cacheDir.exists()) {
                val size = calculateDirectorySize(cacheDir)
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/var/cache")
            }

            // 3. 清理 pip 缓存
            val pipCacheDir = File(upperDir, "root/.cache/pip")
            if (pipCacheDir.exists()) {
                val size = calculateDirectorySize(pipCacheDir)
                pipCacheDir.deleteRecursively()
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/root/.cache/pip")
            }

            // 4. 清理 npm 缓存（如果安装了 Node.js）
            val npmCacheDir = File(upperDir, "root/.npm")
            if (npmCacheDir.exists()) {
                val size = calculateDirectorySize(npmCacheDir)
                npmCacheDir.deleteRecursively()
                totalFreedBytes += size
                totalFilesCleaned++
                cleanedPaths.add("/root/.npm")
            }

            // 5. 清理 /workspace 中的临时构建文件（但保留源代码）
            val workspaceDir = File(upperDir, "workspace")
            if (workspaceDir.exists()) {
                val buildPatterns = listOf("build", "dist", "target", "node_modules", ".gradle", "__pycache__", "*.pyc", ".pytest_cache")
                workspaceDir.walkTopDown()
                    .filter { it.isDirectory }
                    .filter { dir -> buildPatterns.any { pattern -> dir.name == pattern || dir.name.endsWith(pattern.removePrefix("*.")) } }
                    .forEach { dirToClean ->
                        val size = calculateDirectorySize(dirToClean)
                        dirToClean.deleteRecursively()
                        totalFreedBytes += size
                        totalFilesCleaned++
                        cleanedPaths.add("/workspace/${dirToClean.relativeTo(workspaceDir).path}")
                    }
            }

            Log.d(TAG, "[CleanupUpperLayer] Freed ${totalFreedBytes / 1024 / 1024}MB in $totalFilesCleaned directories")

            Result.success(CleanupResult(
                freedBytes = totalFreedBytes,
                cleanedCount = totalFilesCleaned,
                cleanedPaths = cleanedPaths
            ))
        } catch (e: Exception) {
            Log.e(TAG, "[CleanupUpperLayer] Cleanup failed", e)
            Result.failure(e)
        }
    }

    /**
     * 清理结果数据类
     */
    data class CleanupResult(
        val freedBytes: Long,
        val cleanedCount: Int,
        val cleanedPaths: List<String>
    ) {
        fun formatFreedSize(): String {
            return when {
                freedBytes < 1024 -> "$freedBytes B"
                freedBytes < 1024 * 1024 -> String.format("%.2f KB", freedBytes / 1024.0)
                freedBytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", freedBytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", freedBytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }

    private suspend fun extractPRootBinary() = withContext(Dispatchers.IO) {
        val prootBinary = File(prootDir, "proot")
        val runtimeVersionFile = File(prootDir, PROOT_RUNTIME_VERSION_FILE)
        val installedVersion = runtimeVersionFile.takeIf { it.exists() }?.readText()?.trim()
        val needsUpdate = !prootBinary.exists() || installedVersion != PROOT_RUNTIME_VERSION

        if (!needsUpdate) {
            Log.d(TAG, "PRoot runtime already exists at ${prootBinary.absolutePath}, version=$installedVersion")
            ensurePRootExecutablePermissions()
            return@withContext
        }

        Log.i(TAG, "Installing PRoot runtime version=$PROOT_RUNTIME_VERSION (previous=$installedVersion)")
        prootDir.deleteRecursively()
        prootDir.mkdirs()

        val arch = getDeviceArchitecture()
        val assetPath = "proot/proot-$arch"
        Log.d(TAG, "Extracting PRoot binary for architecture: $arch from $assetPath")

        try {
            context.assets.open(assetPath).use { input ->
                prootBinary.outputStream().use { output ->
                    val copied = input.copyTo(output)
                    Log.d(TAG, "Copied $copied bytes to ${prootBinary.absolutePath}")
                }
            }

            extractOptionalAsset("proot/loader-$arch", File(prootDir, "loader"))
            extractOptionalAsset("proot/loader32-$arch", File(prootDir, "loader32"))
            extractOptionalAsset("proot/libtalloc-$arch.so.2", File(prootDir, "libtalloc.so.2"))

            ensurePRootExecutablePermissions()
            runtimeVersionFile.writeText(PROOT_RUNTIME_VERSION)

            Log.d(
                TAG,
                "PRoot runtime ready, proot=${prootBinary.exists()}/${prootBinary.length()}, " +
                    "loader=${File(prootDir, "loader").exists()}, " +
                    "loader32=${File(prootDir, "loader32").exists()}, " +
                    "libtalloc=${File(prootDir, "libtalloc.so.2").exists()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract PRoot runtime from $assetPath", e)
            prootDir.deleteRecursively()
            throw RuntimeException("Failed to extract PRoot runtime for $arch: ${e.message}", e)
        }
    }

    private fun extractOptionalAsset(assetPath: String, target: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setReadable(true, false)
            target.setExecutable(true, false)
            Log.d(TAG, "Extracted optional PRoot asset: $assetPath -> ${target.absolutePath}")
            true
        } catch (e: java.io.FileNotFoundException) {
            Log.d(TAG, "Optional PRoot asset not bundled: $assetPath")
            false
        }
    }

    private fun ensurePRootExecutablePermissions() {
        listOf("proot", "loader", "loader32").forEach { name ->
            val file = File(prootDir, name)
            if (!file.exists()) return@forEach
            try {
                val process = Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath))
                val exitCode = process.waitFor()
                Log.d(TAG, "Set executable permission for $name via chmod, exit code: $exitCode")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to chmod $name, trying setExecutable", e)
                file.setExecutable(true, false)
            }
        }
        File(prootDir, "libtalloc.so.2").takeIf { it.exists() }?.setReadable(true, false)
    }

    private suspend fun extractAlpineRootfs() = withContext(Dispatchers.IO) {
        // 检查是否需要更新 rootfs（版本控制）
        val needUpdate = checkRootfsNeedsUpdate()

        if (!rootfsDir.exists() || rootfsDir.listFiles()?.isEmpty() == true || needUpdate) {
            if (needUpdate && rootfsDir.exists()) {
                Log.d(TAG, "Rootfs version mismatch or update required, deleting old rootfs...")
                rootfsDir.deleteRecursively()
            }

            Log.d(TAG, "Extracting Alpine rootfs to $rootfsDir")
            try {
                // 根据架构选择正确的 rootfs 文件
                val arch = getDeviceArchitecture()
                val rootfsAssetName = when (arch) {
                    "aarch64" -> "rootfs/alpine-minirootfs-3.19.0-aarch64.tar.gz"
                    "armv7a" -> "rootfs/alpine-minirootfs-3.19.0-armhf.tar.gz"
                    "x86_64" -> "rootfs/alpine-minirootfs-3.19.0-x86_64.tar.gz"
                    "i686" -> "rootfs/alpine-minirootfs-3.19.0-x86.tar.gz"
                    else -> "rootfs/alpine-minirootfs-3.19.0-aarch64.tar.gz"
                }

                Log.d(TAG, "Loading rootfs from assets: $rootfsAssetName for architecture: $arch")

                val inputStream = try {
                    context.assets.open(rootfsAssetName)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open rootfs asset: $rootfsAssetName", e)
                    // 回退：尝试不带 .gz 后缀的文件名
                    val tarName = rootfsAssetName.replace(".tar.gz", ".tar")
                    try {
                        Log.d(TAG, "Trying: $tarName")
                        context.assets.open(tarName)
                    } catch (e2: Exception) {
                        // 再回退到通用文件名
                        try {
                            Log.d(TAG, "Trying fallback: alpine/alpine-rootfs.tar")
                            context.assets.open("alpine/alpine-rootfs.tar")
                        } catch (e3: Exception) {
                            Log.d(TAG, "Trying fallback: rootfs/alpine-rootfs.tar.gz")
                            context.assets.open("rootfs/alpine-rootfs.tar.gz")
                        }
                    }
                }

                inputStream.use { input ->
                    extractTarGz(input, rootfsDir)
                }

                // 写入版本文件
                writeRootfsVersion()

                val fileCount = rootfsDir.walkTopDown().count()
                Log.d(TAG, "Alpine rootfs extracted successfully, total files: $fileCount")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract Alpine rootfs", e)
                throw RuntimeException("Failed to extract Alpine rootfs: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "Alpine rootfs already exists at $rootfsDir")
        }
    }

    /**
     * 检查 rootfs 是否需要更新
     * 通过对比本地版本文件和代码中的版本号
     */
    private fun checkRootfsNeedsUpdate(): Boolean {
        val versionFile = File(rootfsDir, ROOTFS_VERSION_FILE)
        if (!versionFile.exists()) {
            Log.d(TAG, "Rootfs version file not found, needs update")
            return true
        }

        return try {
            val localVersion = versionFile.readText().trim().toIntOrNull() ?: 0
            val needsUpdate = localVersion < ROOTFS_VERSION
            if (needsUpdate) {
                Log.d(TAG, "Rootfs version outdated: local=$localVersion, required=$ROOTFS_VERSION")
            }
            needsUpdate
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read rootfs version, assuming needs update", e)
            true
        }
    }

    /**
     * 写入 rootfs 版本文件
     */
    private fun writeRootfsVersion() {
        try {
            val versionFile = File(rootfsDir, ROOTFS_VERSION_FILE)
            versionFile.writeText(ROOTFS_VERSION.toString())
            Log.d(TAG, "Rootfs version file written: $ROOTFS_VERSION")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write rootfs version file", e)
        }
    }

    /**
     * 纯 Java 实现的 tar/tar.gz 解压（不依赖系统 tar 命令）
     * 自动检测是否为 gzip 格式
     */
    private fun extractTarGz(input: java.io.InputStream, targetDir: File) {
        targetDir.mkdirs()

        // 检测是否为 gzip 格式（前两个字节是 0x1f 0x8b）
        val magic = ByteArray(2)
        input.mark(2)
        val magicRead = input.read(magic)
        input.reset()

        val isGzip = magicRead == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        Log.d(TAG, "Archive format detected: ${if (isGzip) "gzip" else "plain tar"}")

        val tarInput = if (isGzip) {
            java.util.zip.GZIPInputStream(input)
        } else {
            input
        }

        tarInput.use { tarIn ->
            extractTar(tarIn, targetDir)
        }
    }

    /**
     * 解压纯 tar 格式
     */
    private fun extractTar(input: java.io.InputStream, targetDir: File) {
        val buffer = ByteArray(8192)

        while (true) {
            // 读取 tar 头部（512 字节）
            val header = ByteArray(512)
            var bytesRead = 0
            while (bytesRead < 512) {
                val read = input.read(header, bytesRead, 512 - bytesRead)
                if (read == -1) break
                bytesRead += read
            }

            if (bytesRead < 512) break // 文件结束

            // 检查是否为空块（tar 结尾）
            if (header.all { it == 0.toByte() }) {
                // 检查下一个块是否也是空的
                val nextHeader = ByteArray(512)
                var nextBytesRead = 0
                while (nextBytesRead < 512) {
                    val read = input.read(nextHeader, nextBytesRead, 512 - nextBytesRead)
                    if (read == -1) break
                    nextBytesRead += read
                }
                if (nextHeader.all { it == 0.toByte() }) break
            }

            // 解析文件名（前 100 字节）
            val nameBytes = header.copyOfRange(0, 100)
            val name = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
            if (name.isEmpty()) continue

            // 解析文件大小（第 124-135 字节，八进制）
            val sizeBytes = header.copyOfRange(124, 136)
            val sizeStr = String(sizeBytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
            val fileSize = if (sizeStr.isEmpty()) 0 else sizeStr.toLong(8)

            // 解析文件类型（第 156 字节）
            val typeFlag = header[156].toInt()

            val file = File(targetDir, name)

            when (typeFlag) {
                '5'.code -> {
                    // 目录
                    file.mkdirs()
                }
                '0'.code, 0 -> {
                    // 普通文件
                    file.parentFile?.mkdirs()
                    file.outputStream().use { output ->
                        var remaining = fileSize
                        while (remaining > 0) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    // 设置可执行权限（如果 mode 中有执行位）
                    val modeBytes = header.copyOfRange(100, 108)
                    val mode = String(modeBytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
                    if (mode.isNotEmpty()) {
                        val modeInt = mode.toInt(8)
                        if ((modeInt and 0b001001001) != 0) {
                            file.setExecutable(true)
                        }
                    }
                }
                '2'.code -> {
                    // 符号链接 - 读取链接目标并创建真正的符号链接
                    // 符号链接目标在 tar 头部的 157-256 字节（linkname 字段）
                    val linkNameBytes = header.copyOfRange(157, 257)
                    val linkName = String(linkNameBytes, Charsets.UTF_8).trimEnd('\u0000')
                    if (linkName.isNotEmpty()) {
                        file.parentFile?.mkdirs()
                        try {
                            android.system.Os.symlink(linkName, file.absolutePath)
                            Log.d(TAG, "Created symlink: ${file.absolutePath} -> $linkName")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to create symlink: ${file.absolutePath} -> $linkName, falling back to empty file", e)
                            file.createNewFile()
                        }
                    } else {
                        // 如果链接名为空，创建空文件占位
                        file.parentFile?.mkdirs()
                        file.createNewFile()
                    }
                }
                else -> {
                    // 其他类型，跳过内容
                    var remaining = fileSize
                    while (remaining > 0) {
                        val toSkip = minOf(buffer.size.toLong(), remaining).toInt()
                        val skipped = input.read(buffer, 0, toSkip)
                        if (skipped == -1) break
                        remaining -= skipped
                    }
                }
            }

            // 跳过填充到 512 字节边界的字节
            val padding = (512 - (fileSize % 512)) % 512
            var remainingPadding = padding
            while (remainingPadding > 0) {
                val skipped = input.skip(remainingPadding.toLong())
                if (skipped == 0L) break
                remainingPadding -= skipped.toInt()
            }
        }
    }

    private fun getDeviceArchitecture(): String {
        return when (android.system.Os.uname().machine) {
            "aarch64" -> "aarch64"
            "armv7l", "armv8l" -> "armv7a"
            "x86_64" -> "x86_64"
            "i686", "i386" -> "i686"
            else -> "aarch64"
        }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    // ==================== Auto Management ====================

    /**
     * App 启动时恢复容器状态
     * 如果 upper 目录存在且有效，恢复到 Stopped 状态
     * 调用此方法前应先调用 checkInitializationStatus() 确认已初始化
     *
     * @return 是否成功恢复状态
     */
    suspend fun restoreState(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 检查 rootfs 是否已初始化
            if (!checkInitializationStatus()) {
                Log.d(TAG, "[RestoreState] Rootfs not initialized, cannot restore state")
                return@withContext false
            }

            // 检查 upper 目录是否存在（表示之前有容器被创建过）
            val upperDir = File(containerDir, "upper")
            val workDir = File(containerDir, "work")

            if (upperDir.exists()) {
                Log.d(TAG, "[RestoreState] Found existing upper directory, restoring container state")

                // 确保子目录存在（兼容旧版本升级或部分目录被删除的情况）
                File(upperDir, "usr/local").apply { mkdirs() }
                File(upperDir, "usr/lib").apply { mkdirs() }
                File(upperDir, "root").apply { mkdirs() }

                // 创建 globalContainer（不启动进程）
                if (globalContainer == null) {
                    globalContainer = ContainerState(
                        id = "global",
                        workDir = workDir.absolutePath,
                        upperDir = upperDir.absolutePath
                    )
                    Log.d(TAG, "[RestoreState] Global container recreated")
                }

                // 设置状态为 Stopped（表示容器数据存在但未运行）
                _containerState.value = ContainerStateEnum.Stopped
                Log.d(TAG, "[RestoreState] Container state restored to Stopped")
                true
            } else {
                // rootfs 已初始化但 upper 目录不存在，说明是首次初始化
                // 创建 upper 目录并设置为 Running（兼容旧逻辑）
                Log.d(TAG, "[RestoreState] Rootfs initialized but no upper dir, creating fresh container")
                createGlobalContainer()
                _containerState.value = ContainerStateEnum.Running
                Log.d(TAG, "[RestoreState] Fresh container created and set to Running")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "[RestoreState] Failed to restore container state", e)
            false
        }
    }

    /**
     * 启用容器自动管理
     * - 应用进入前台时自动启动容器（如果启用且处于 Stopped 状态）
     * - 应用进入后台时自动停止容器
     *
     * @param enableContainerRuntime 用户是否启用了容器运行时功能
     */
    fun enableAutoManagement(enableContainerRuntime: Boolean) {
        // 更新当前设置
        currentEnableContainerRuntime = enableContainerRuntime
        Log.d(TAG, "[AutoManage] enableAutoManagement called with enableContainerRuntime=$enableContainerRuntime, autoManagementEnabled=$autoManagementEnabled")

        // 如果已经添加过 observer，不再重复添加
        if (autoManagementEnabled) {
            Log.d(TAG, "[AutoManage] Auto management already enabled, just updating setting to $enableContainerRuntime")
            return
        }

        autoManagementEnabled = true
        Log.d(TAG, "[AutoManage] Enabling container auto management (enableContainerRuntime=$enableContainerRuntime)")

        // 监听应用生命周期
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                Log.d(TAG, "[AutoManage] Lifecycle event: $event, currentEnableContainerRuntime=$currentEnableContainerRuntime")
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        // 应用进入前台
                        Log.d(TAG, "[AutoManage] App came to foreground, checking if should auto-init...")
                        Log.d(TAG, "[AutoManage] currentEnableContainerRuntime=$currentEnableContainerRuntime, state=${_containerState.value}")
                        if (currentEnableContainerRuntime) {
                            when (_containerState.value) {
                                is ContainerStateEnum.Stopped -> {
                                    Log.d(TAG, "[AutoManage] Auto-starting container from Stopped state")
                                    GlobalScope.launch(Dispatchers.IO) {
                                        val result = start()
                                        Log.d(TAG, "[AutoManage] Auto-start result: $result")
                                    }
                                }
                                is ContainerStateEnum.NotInitialized -> {
                                    Log.d(TAG, "[AutoManage] Container not initialized, auto-initializing...")
                                    GlobalScope.launch(Dispatchers.IO) {
                                        Log.d(TAG, "[AutoManage] Calling initialize()...")
                                        val result = initialize()
                                        Log.d(TAG, "[AutoManage] Auto-initialize result: $result")
                                    }
                                }
                                else -> {
                                    Log.d(TAG, "Container state: ${_containerState.value}, no action needed")
                                }
                            }
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        // 应用进入后台
                        Log.d(TAG, "App went to background")
                        if (false && currentEnableContainerRuntime && _containerState.value == ContainerStateEnum.Running) {
                            Log.d(TAG, "Auto-stopping container")
                            GlobalScope.launch(Dispatchers.IO) {
                                stop()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        lifecycle.addObserver(observer)
        autoManagementObserver = observer
    }

    // ==================== Background Process Management ====================

    /**
     * 后台执行命令（非阻塞）
     *
     * @param sandboxId 沙箱ID
     * @param command 要执行的命令列表
     * @param processId 进程ID
     * @param stdoutFile 标准输出日志文件
     * @param stderrFile 标准错误日志文件
     * @param env 环境变量
     * @return ExecutionResult
     */
    suspend fun execInBackground(
        sandboxId: String,
        command: List<String>,
        processId: String,
        stdoutFile: File,
        stderrFile: File,
        env: Map<String, String> = emptyMap()
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val container = globalContainer ?: return@withContext ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Global container not created"
            )

            // 构建 PRoot 命令
            val prootCmd = buildProotCommand(sandboxId, command, env, container)

            Log.d(TAG, "[ExecInBackground] Starting process: $processId")
            Log.d(TAG, "[ExecInBackground] Command: ${command.joinToString(" ")}")

            // 创建进程
            val processBuilder = ProcessBuilder(prootCmd)
            processBuilder.redirectErrorStream(false)

            // 设置环境变量
            val processEnv = processBuilder.environment()
            setupProcessEnvironment(processEnv, env)

            val process = processBuilder.start()

            // 获取进程PID
            val pid = getProcessPid(process)

            // 启动异步线程读取输出并写入文件（带大小限制）
            val maxLogSize = BackgroundProcessManager.MAX_LOG_FILE_SIZE.toLong()

            val stdoutJob = GlobalScope.launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        stdoutFile.bufferedWriter().use { writer ->
                            var line: String?
                            var currentSize = 0L
                            while (reader.readLine().also { line = it } != null) {
                                val lineBytes = line!!.toByteArray().size + 1 // +1 for newline
                                currentSize += lineBytes

                                // 检查日志大小限制
                                if (currentSize > maxLogSize) {
                                    writer.write("[Log truncated: exceeded max size ${maxLogSize / 1024 / 1024}MB]")
                                    writer.newLine()
                                    writer.flush()
                                    Log.w(TAG, "[ExecInBackground] stdout log truncated for $processId (exceeded ${maxLogSize} bytes)")
                                    break
                                }

                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[ExecInBackground] Error reading stdout for $processId", e)
                }
            }

            val stderrJob = GlobalScope.launch(Dispatchers.IO) {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        stderrFile.bufferedWriter().use { writer ->
                            var line: String?
                            var currentSize = 0L
                            while (reader.readLine().also { line = it } != null) {
                                val lineBytes = line!!.toByteArray().size + 1 // +1 for newline
                                currentSize += lineBytes

                                // 检查日志大小限制
                                if (currentSize > maxLogSize) {
                                    writer.write("[Log truncated: exceeded max size ${maxLogSize / 1024 / 1024}MB]")
                                    writer.newLine()
                                    writer.flush()
                                    Log.w(TAG, "[ExecInBackground] stderr log truncated for $processId (exceeded ${maxLogSize} bytes)")
                                    break
                                }

                                writer.write(line)
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[ExecInBackground] Error reading stderr for $processId", e)
                }
            }

            // 存储后台进程记录
            val record = BackgroundProcessRecord(
                processId = processId,
                process = process,
                stdoutJob = stdoutJob,
                stderrJob = stderrJob,
                createdAt = System.currentTimeMillis()
            )
            backgroundProcesses[processId] = record

            Log.d(TAG, "[ExecInBackground] Process started: $processId, PID: $pid")

            ExecutionResult(
                exitCode = 0,
                stdout = "Process started with PID: $pid",
                stderr = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "[ExecInBackground] Failed to start process: $processId", e)
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to start background process: ${e.message}"
            )
        }
    }


    /**
     * 在容器内启动交互式进程。
     *
     * 与 execInBackground 不同：
     * - 不重定向 stdout/stderr 到文件
     * - 直接返回 Process，由上层管理 stdin/stdout/stderr
     */
    suspend fun execInteractive(
        sandboxId: String,
        command: List<String>,
        env: Map<String, String> = emptyMap()
    ): Process = withContext(Dispatchers.IO) {
        val container = globalContainer ?: throw IllegalStateException("Global container not created")

        val prootCmd = buildProotCommand(sandboxId, command, env, container)

        Log.d(TAG, "[ExecInteractive] Command: ${command.joinToString(" ")}")

        val processBuilder = ProcessBuilder(prootCmd)
        processBuilder.redirectErrorStream(false)

        val processEnv = processBuilder.environment()
        setupProcessEnvironment(processEnv, env)

        processBuilder.start()
    }

    suspend fun execNativePty(
        sandboxId: String,
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        columns: Int = 80,
        rows: Int = 24
    ): NativePtyProcess = withContext(Dispatchers.IO) {
        val container = globalContainer ?: throw IllegalStateException("Global container not created")
        val prootCmd = buildProotCommand(sandboxId, command, env, container)
        val processEnv = System.getenv().toMutableMap()
        setupProcessEnvironment(processEnv, env)
        NativePtyBridge.start(prootCmd, processEnv, columns, rows)
            ?: throw IllegalStateException("Native PTY backend unavailable: ${NativePtyBridge.unavailableReason ?: "unknown"}")
    }

    fun signalProcessTree(process: Process?, signal: String) {
        if (process == null) return
        val pid = getProcessPid(process) ?: return
        val safeSignal = signal.filter { it.isLetterOrDigit() }.take(16).ifBlank { return }
        try {
            ProcessBuilder(
                "sh",
                "-c",
                "kill -$safeSignal -$pid 2>/dev/null || true; pkill -$safeSignal -P $pid 2>/dev/null || true"
            ).start().waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
    }

    fun terminateProcessTree(process: Process?, force: Boolean = false) {
        if (process == null) return
        val pid = getProcessPid(process)
        try {
            if (!force) process.destroy() else process.destroyForcibly()
        } catch (_: Exception) {
        }
        if (pid != null) {
            try {
                val signal = if (force) "-KILL" else "-TERM"
                ProcessBuilder("sh", "-c", "pkill $signal -P $pid 2>/dev/null || true; kill $signal -$pid 2>/dev/null || true")
                    .start()
                    .waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 终止后台进程
     */
    suspend fun killBackgroundProcess(processId: String): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val record = backgroundProcesses[processId]
                ?: return@withContext ExecutionResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Background process not found: $processId"
                )

            Log.d(TAG, "[KillBackgroundProcess] Killing process: $processId")

            // 优先温和终止整个进程树，再强杀残留
            terminateProcessTree(record.process, force = false)
            if (record.process?.waitFor(1200, java.util.concurrent.TimeUnit.MILLISECONDS) != true) {
                terminateProcessTree(record.process, force = true)
                record.process?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }

            // 取消输出读取Job
            record.stdoutJob?.cancel()
            record.stderrJob?.cancel()

            // 移除记录
            backgroundProcesses.remove(processId)

            Log.d(TAG, "[KillBackgroundProcess] Process killed: $processId")

            ExecutionResult(
                exitCode = 0,
                stdout = "Process killed successfully",
                stderr = ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "[KillBackgroundProcess] Error killing process: $processId", e)
            ExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = "Error: ${e.message}"
            )
        }
    }

    /**
     * 检查后台进程是否还在运行
     */
    fun isBackgroundProcessAlive(processId: String): Boolean {
        val record = backgroundProcesses[processId] ?: return false
        return record.process?.isAlive == true
    }

    /**
     * 获取后台进程的退出码
     */
    fun getBackgroundProcessExitCode(processId: String): Int? {
        val record = backgroundProcesses[processId] ?: return null
        val process = record.process ?: return null
        return if (!process.isAlive) {
            process.exitValue()
        } else {
            null
        }
    }

    /**
     * 清理已结束的后台进程
     */
    suspend fun cleanupFinishedBackgroundProcesses() = withContext(Dispatchers.IO) {
        val finished = mutableListOf<String>()

        backgroundProcesses.keys.forEach { processId ->
            val record = backgroundProcesses[processId]
            if (record?.process?.isAlive == false) {
                finished.add(processId)
                record.stdoutJob?.cancel()
                record.stderrJob?.cancel()
            }
        }

        finished.forEach { backgroundProcesses.remove(it) }

        if (finished.isNotEmpty()) {
            Log.d(TAG, "Cleaned up ${finished.size} finished background processes")
        }
    }

    /**
     * 获取Java进程的PID
     */
    private fun getProcessPid(process: Process): Int? {
        return try {
            // 通过反射获取PID（不同Android版本可能不同）
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get process PID", e)
            null
        }
    }


    private fun writeContainerHostsFile(upperDir: File) {
        runCatching {
            val hostsContent = buildContainerHostsContent()
            val upperEtcDir = File(upperDir, "etc").apply { mkdirs() }
            File(upperEtcDir, "hosts").writeText(hostsContent)
            val rootfsEtcDir = File(rootfsDir, "etc").apply { mkdirs() }
            File(rootfsEtcDir, "hosts").writeText(hostsContent)
        }.onFailure {
            Log.w(TAG, "Failed to write container hosts file", it)
        }
    }

    private fun buildContainerHostsContent(): String {
        val customHosts = settingsStore.settingsFlow.value.containerCustomHosts.trim()
        val baseHosts = "127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n"
        return if (customHosts.isBlank()) {
            baseHosts
        } else {
            baseHosts + "\n# Custom hosts from RikkaHub settings\n" + customHosts + "\n"
        }
    }

    /**
     * 设置进程环境变量
     */
    private fun setupProcessEnvironment(
        processEnv: MutableMap<String, String>,
        customEnv: Map<String, String>
    ) {
        processEnv["HOME"] = "/root"
        processEnv["TMPDIR"] = "/tmp"
        processEnv["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
        File(prootDir, "loader").takeIf { it.exists() }?.let { processEnv["PROOT_LOADER"] = it.absolutePath }
        File(prootDir, "loader32").takeIf { it.exists() }?.let { processEnv["PROOT_LOADER_32"] = it.absolutePath }
        // Do not force PROOT_NO_SECCOMP. On Android 15 / Linux 6.6 devices the ptrace-only
        // fallback path can return ENOSYS for chdir/fchdir, breaking shell cd and apk scripts.
        // Let PRoot choose its native seccomp/ptrace mode unless callers explicitly override it.
        processEnv.remove("PROOT_NO_SECCOMP")
        processEnv["PREFIX"] = "/usr"
        processEnv["NPM_CONFIG_PREFIX"] = "/usr/local"
        processEnv["npm_config_prefix"] = "/usr/local"
        processEnv["NPM_CONFIG_CACHE"] = "/tmp/npm-cache"
        processEnv["NPM_CONFIG_AUDIT"] = "false"
        processEnv["NPM_CONFIG_FUND"] = "false"
        processEnv["NO_UPDATE_NOTIFIER"] = "1"
        processEnv["PIP_DISABLE_PIP_VERSION_CHECK"] = "1"
        processEnv["NODE_PATH"] = "/usr/local/lib/node_modules:/usr/lib/node_modules"
        processEnv["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        // 合并自定义环境变量
        processEnv.putAll(customEnv)

        // 不继承宿主 LD_PRELOAD。termux-exec 会改写 execve 路径，容易干扰 PRoot 自己的
        // path translation；长期方案依赖修复后的 PRoot runtime，而不是 LD_PRELOAD hook。
        processEnv.remove("LD_PRELOAD")

    }

    /**
     * 构建PRoot命令
     */
    private fun buildProotCommand(
        sandboxId: String,
        command: List<String>,
        env: Map<String, String>,
        container: ContainerState
    ): List<String> {
        val prootBinary = File(prootDir, "proot").absolutePath
        val sandboxDir = File(context.filesDir, "sandboxes/$sandboxId").apply { mkdirs() }
        val deliveryDir = SandboxEngine.getDeliveryDir(context, sandboxId).apply { mkdirs() }
        val runtimeSkillsDir = SandboxEngine.getRuntimeSkillsDir(context, sandboxId).apply { mkdirs() }
        val skillLibraryDir = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() }
        val upperDir = File(container.upperDir)
        ensureContainerRuntimeFiles(upperDir)
        writeContainerHostsFile(upperDir)

        return buildList {
            add(prootBinary)

            // 根目录使用基础 rootfs（只读）；先设置 rootfs，再让后续 bind 覆盖 /etc/hosts 等路径。
            add("-R")
            add(rootfsDir.absolutePath)

            // 绑定挂载系统目录（必要）
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")

            // 绑定挂载对话的沙箱目录到 /workspace
            add("-b")
            add("${sandboxDir.absolutePath}:/workspace")
            add("-b")
            add("${deliveryDir.absolutePath}:/delivery")
            add("-b")
            add("${skillLibraryDir.absolutePath}:/skills")
            add("-b")
            add("${runtimeSkillsDir.absolutePath}:/opt/rikkahub/skills")

            // 绑定挂载容器的 upper 层到 /usr/local（pip 安装位置）
            add("-b")
            add("${container.upperDir}/usr/local:/usr/local")

            // 绑定挂载 upper 层到 /root（用户级 pip/npm 配置）
            add("-b")
            add("${container.upperDir}/root:/root")

            // 绑定 writable tmp/cache；-R rootfs 下 rootfs 自带 /tmp 可能不可写。
            add("-b")
            add("${container.upperDir}/tmp:/tmp")
            add("-b")
            add("${container.upperDir}/var/cache/apk:/var/cache/apk")

            // 额外绑定挂载 usr/lib 以确保库文件可访问
            add("-b")
            add("${container.upperDir}/usr/lib:/usr/lib!")
            // 绑定可写 apk/DNS 配置，避免 -R rootfs 下修复脚本无法写 /etc
            add("-b")
            add("${container.upperDir}/etc/apk/repositories:/etc/apk/repositories")
            add("-b")
            add("${container.upperDir}/etc/resolv.conf:/etc/resolv.conf")
            add("-b")
            add("${container.upperDir}/etc/hosts:/etc/hosts")

            // 设置工作目录
            add("-w")
            add("/workspace")

            // 启用符号链接修复
            add("--link2symlink")

            // 执行的命令。PRoot 资产已将 RUNPATH patch 为 $ORIGIN，
            // libtalloc 由 Android linker 从 proot 同目录加载，无需 LD_LIBRARY_PATH，
            // 因此也不会污染 Alpine guest 的 apk/node 动态链接环境。
            addAll(command)
        }
    }

    private fun normalizeContainerPath(path: String): String {
        if (path.isBlank()) return "/"
        val normalized = path.replace('\\', '/').replace(Regex("/+"), "/").trimEnd('/')
        return if (normalized.startsWith("/")) normalized.ifBlank { "/" } else "/$normalized"
    }

    private fun buildContainerChildPath(parent: String, name: String): String {
        val normalizedParent = normalizeContainerPath(parent)
        return when (normalizedParent) {
            "/" -> "/$name"
            else -> "$normalizedParent/$name"
        }
    }
}

// ==================== Data Classes ====================

data class ContainerState(
    val id: String,
    val workDir: String,
    val upperDir: String
)

data class ExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

/**
 * 容器状态枚举（4状态）
 */
sealed class ContainerStateEnum {
    object NotInitialized : ContainerStateEnum()
    data class Initializing(val progress: Float) : ContainerStateEnum()
    object Running : ContainerStateEnum()
    object Stopped : ContainerStateEnum()
    data class Error(val message: String) : ContainerStateEnum()
}

/**
 * 后台进程记录（内部使用）
 *
 * @property processId 进程ID
 * @property process Java Process对象
 * @property stdoutJob stdout读取Job
 * @property stderrJob stderr读取Job
 * @property createdAt 创建时间戳
 */
data class BackgroundProcessRecord(
    val processId: String,
    val process: Process?,
    val stdoutJob: kotlinx.coroutines.Job?,
    val stderrJob: kotlinx.coroutines.Job?,
    val createdAt: Long
)
