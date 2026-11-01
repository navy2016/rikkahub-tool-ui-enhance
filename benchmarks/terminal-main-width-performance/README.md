# A1端到端性能V1：306 versus 独立MAIN历史宽度生命周期

用户在533 JVM/20组件机制通过后要求继续。本轮只运行新冻结端到端性能，不自动采用，不叠P2/B/C。
基线306e0b80b508c961bc00f2d4b34a2688e5857a1a/main ac97798416997a8230c9fa2a5a8569cf0ed0a911。
候选逐字节等于c4113db的五源文件，身份f417e3fb3fe81d92fb4846f43b4e73ab225cf64b15594ed677ae54a944eaa422。

## 前提、构建与运行

- 新build之前只读复核run41275351431/d9980ab的533 JVM、20组件、37生命周期点及两份原始report hash。
- 两目标均为非debuggable、未混淆的Release派生terminaltest；candidate先构建且只编译一次公共test driver。
- 双目标必须只差A1五份源码，Page/helper/默认/输入/资源/字体/native/proot资产和签名不变。
- 正式生产app/src/main保持306；临时覆盖在finally恢复。无正式Release，无本地APK下载。
- 一次KVM boot，四对AB/BA/AB/BA；同个driver、设备、网格、IME与资源。每方法独立目标进程。
- 先稳定隐藏网格，再开真实IME；等同PID启动写入完成后一次配置，核对persisted/flow/首次LocalSettings。

## 固定矩阵

1k/10k × IME隐藏/显示，共4场景；refresh、SYNC60ms、ALT返回共3条件。
每场景每次目标启动21轮，首轮预热，20轮正式。条件顺序按protocol.json的六种排列循环。
总2016窗口=96预热+1920正式；每场景/条件/arm/pair恰20正式窗口。新样本不与旧55/P2/inframe成绩混算。

原有限字节peer、25s响应截止、首次合格UI尝试后自然500ms截止不变。后置可见性检查不当计时终点。
不在计时窗口强制绘制，不缩短保护等待，不删除异常；native正确性失败保留已执行方法后停外层。
身份缺失保留null，只完成已冻结矩阵，不补采。帧身份parser原字节不变，NO_SUBMISSION不是借其他帧的理由。

## 新门槛（全部在采样前定义）

1. **返回收益**：每场景primary(inputToBufferEvidenceNs)及coGate(inputToAttemptNs)四配对比值中位≤0.80，且各≥3/4配对改善。
2. **非目标守卫**：refresh/SYNC同上两终点中位比值≤1.05；所有场景/条件系统DisplayFrame结束中位比值≤1.05。
3. **样本p95守卫**：每个20正式窗口arm取nearest-rank第19项，再计算四配对p95比值中位；全部3终点/3条件/4场景≤1.10。
   这是预注册的样本尾部检查，不是总体95分位置信估计或手机性能保证；不混池补足数量。
4. **最坏窗口守卫**：每个独立pair/场景/条件/终点，candidate最大值≤max(legacy最大值×1.5,legacy最大值+100ms)。
   任意一对超出即失败，不能再以四对中位掩盖。
5. 所有2016窗口（包括预热）必须有自己的精确提交/呈现证据；一个正式null使该arm的中位/p95/最大值及配对聚合无效。
6. 工作次数、宽度区间、主线程Choreographer长区间仅诊断；墙钟不叫CPU，不做相减归因，不替代主终点。

统计顺序：同一个arm/pair内部统计→candidate/legacy306→四配对比值中位。
窗口峰值另逐pair判定。收益不能抵消刷新/SYNC/系统/尾部守卫，不允许改门槛救成绩。

## 证据传输与边界

每case63窗口，预先把case传输上限设为1024×2600字符、解压32MiB；两个FrameMetrics observer仍最多8192条。
Perfetto沿用32MiB ring/240s/96MiB上限，BOOTTIME和原identity状态机不变。扩大的是病例序列化容量，不是观察截止或身份容错。
保留原方法log、原case数据、每case pftrace、安装APK hash、manifest、boot、plan、每次结果及最终汇总。
每个完整case之后报告取证进度，不启动本地后台监控；取证中转不是重新测量。

只有性能与资料完整性均通过，后续才运行缺失的完整182视口/页面/生命周期回归并讨论正式采用。
本轮不宣称正式混淆APK或用户设备提速。失败完整归档，不微调同候选反复追成绩。
