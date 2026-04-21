package me.rerere.rikkahub.data.container

import kotlinx.serialization.Serializable

/**
 * 后台进程信息
 *
 * @property processId 进程ID（UUID + 时间戳）
 * @property sandboxId 关联的沙箱ID
 * @property command 执行的命令
 * @property status 进程状态
 * @property pid 系统进程ID（容器内）
 * @property stdoutPath 标准输出日志文件路径
 * @property stderrPath 标准错误日志文件路径
 * @property createdAt 创建时间戳
 * @property startedAt 实际启动时间戳
 * @property exitedAt 退出时间戳
 * @property exitCode 退出码（已结束时）
 * @property tag 用户自定义标签（可选）
 * @property isInteractive 是否为交互式 session
 * @property stdinEnabled 是否支持 stdin 输入
 * @property ttyEnabled 是否启用了 tty/script 包装
 * @property terminalColumns 终端列数
 * @property terminalRows 终端行数
 * @property terminalBackend 终端后端：pipe、script-sigwinch 或 native-pty
 * @property processSource 进程来源，当前统一为 container_shell_bg
 */
@Serializable
data class BackgroundProcessInfo(
    val processId: String,
    val sandboxId: String,
    val command: String,
    val status: ProcessStatus,
    val pid: Int?,
    val stdoutPath: String,
    val stderrPath: String,
    val createdAt: Long,
    val startedAt: Long?,
    val exitedAt: Long?,
    val exitCode: Int?,
    val tag: String? = null,
    val isInteractive: Boolean = false,
    val stdinEnabled: Boolean = false,
    val ttyEnabled: Boolean = false,
    val terminalColumns: Int = 80,
    val terminalRows: Int = 24,
    val terminalBackend: String = "pipe",
    val processSource: String = "container_shell_bg"
)

/**
 * 进程状态枚举
 */
@Serializable
enum class ProcessStatus {
    /**
     * 启动中
     */
    STARTING,

    /**
     * 运行中
     */
    RUNNING,

    /**
     * 已停止（用户手动终止）
     */
    STOPPED,

    /**
     * 正常完成
     */
    COMPLETED,

    /**
     * 启动失败或异常退出
     */
    FAILED,

    /**
     * 孤立进程（App崩溃后残留）
     */
    ORPHANED
}

/**
 * 进程执行结果
 *
 * @property success 是否成功
 * @property processId 进程ID
 * @property status 进程状态
 * @property message 结果消息
 * @property stdoutFile 标准输出日志文件路径
 * @property stderrFile 标准错误日志文件路径
 * @property pid 系统进程ID（容器内）
 * @property terminalColumns 终端列数
 * @property terminalRows 终端行数
 * @property terminalBackend 终端后端：pipe、script-sigwinch 或 native-pty
 */
@Serializable
data class ProcessExecutionResult(
    val success: Boolean,
    val processId: String,
    val status: ProcessStatus,
    val message: String,
    val stdoutFile: String? = null,
    val stderrFile: String? = null,
    val pid: Int? = null,
    val isInteractive: Boolean = false,
    val stdinEnabled: Boolean = false,
    val ttyEnabled: Boolean = false,
    val terminalColumns: Int = 80,
    val terminalRows: Int = 24,
    val terminalBackend: String = "pipe"
)

/**
 * 日志读取结果
 *
 * @property lines 日志行列表
 * @property totalLines 总行数
 * @property hasMore 是否还有更多日志
 * @property error 错误信息（如果失败）
 */
@Serializable
data class LogReadResult(
    val lines: List<String>,
    val totalLines: Int,
    val hasMore: Boolean,
    val error: String? = null
)
