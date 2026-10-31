# 帧身份协议v1：先验证采集，不运行优化对照

协议机器原件：`terminal-frame-identity/protocol.json`。本协议不修改P2 v1/v2的定义、门槛、失败或样本。
生产仍为55db111，候选仍仅为隔离补丁。本阶段只允许一次未改生产的固定采集资格验证。

## 三个独立终点

1. **UI尝试A**：完整响应最终EMULATOR_FED revision对应的每一次FRAME_DRAWN。保存所有回调，记录第一尝试，但不声称它已提交。
2. **缓冲证据B**：该尝试自己的UI帧token→同token的RT DrawFrames→该任务内唯一VRI/frameNumber映射→同frameNumber的BLAST取帧，以及唯一is_buffer=true SurfaceFrame。时间点命名为BLAST取帧trace开始；不是精确驱动排队时间、GPU完成或像素可见时间。
3. **系统终点C**：B的同PID/token SurfaceFrame通过display_frame_token连接到完整DisplayFrame结束。它是系统合成终点，不是物理屏幕光子或首次看到目标文本。

三者分别存值。完整同步但没有Drawing/mapping/acquire/swap或SurfaceFrame的RT任务记为NO_SUBMISSION；UNKNOWN/AMBIGUOUS不得当NO_SUBMISSION。
若后续**新的合格UI尝试**拥有自己的提交链，可以作为该窗口的首个提交尝试；旧未提交尝试仍保留。只有RT动画而没有合格UI尝试的后续buffer不得借用。
观察窗口内没有可证明提交／呈现时记null并保留原因，不能丢掉该窗口、用0、用最近帧或延长窗口寻找结果。

## 同一时钟，避免跨时钟猜测

本次显式向现有`TerminalPipelineTrace.start`注入`SystemClock.elapsedRealtimeNanos`：流水线与驱动采集时间全部BOOTTIME。
ftrace必须使用默认boot时钟，FrameTimeline必须为clock6；其他时钟拒绝，不做最近时间配对或小偏移忽略。
窗口FrameMetrics的公开Vsync仍为MONOTONIC，**只按原始token做旁证**，并核验公开值与原始数组自身一致。
缺FrameMetrics通知与无buffer提交是不同状态。旁证缺失如实保留，不是旧P2的FrameMetrics门禁获豁免。

## 每次有限响应的完整观察

先完成同PID启动写入回执、持久层、流、实际首次LocalSettings、80列和IME稳定门槛。
每次只发一个可验证的有限PTY响应。响应与第一UI尝试须在25s内出现，随后只观察自然绘制500ms。
在读取快照前固定BOOT截止时间；快照只接纳截止前的事件。截止后进行无障碍最终可见性检查。
窗口内禁止发重绘请求、语义动作、绘后注册提交回调或重发输入。UI试验层不得更改生产帧发布／滚动执行器。
每次保留完整事件、准确字节数／hash、历史身份、所有合格尝试、两套原始FrameMetrics、原始Perfetto及二进制安装审计。

## 资格验证不是性能验收

固定1k/10k×原生IME隐藏/显示；每场景4轮，每轮刷新与返回各一次，AB/BA交错，共32窗口。
不区分性能预热、不计算优化收益、不构建P2候选。资格验证要求帧链可判定、时钟和原件完整；NO_SUBMISSION、DROPPED及旁证缺失是观测类别，不伪装成成功提交。
任何UNKNOWN、AMBIGUOUS、旁证CONFLICT、数据丢失或未闭合的相关span必须使资格门禁失败，仍保存所有原件。

未来优化试验必须另行冻结终点、配对、未提交／删失值处理和采用门槛；不得用本次资格结果给旧230窗口追认通过。
实现先用确定性的解析单元测试覆盖跳过、统计缺失、重复token、无关动画、时钟错误、丢包和多次合格尝试，再运行一次上述CI资格验证。
