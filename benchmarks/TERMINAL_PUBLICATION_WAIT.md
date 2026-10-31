# 独立发布等待诊断v1（不是P2重跑或新优化候选）

生产基线55db111，main tree8caf50d5c40e228f1d5bcc09d3db505757568b68。
历史完整P2实验已拒绝；这里只以其**原版侧**只读定位，不合并候选或改变旧门槛。
原版64个正式普通刷新窗口：fed→UI接收中位约0.08–0.10ms，接收→capture约10.4–11.7ms，
capture/model锁/构帧约0.12–0.14ms，captured→publish约0.25ms。每窗口接收和capture之间
均观察到一次主Choreographer起点，capture均在其区间外。没有任务启动／wait注册标记，不能据此认定原因。

机器协议`terminal-publication-wait/protocol.json`在设备运行前冻结。H1：等待主要位于已注册的帧时钟，
而非任务排队、同步输出或resize。H2：帧时钟恢复位于Choreographer回调/绘制之后，导致发布错过该帧。
H1/H2是待检验假设，不是结论；这次探针不能证明任何优化收益，也不能归因旧P2的回退。

## 只加追踪，不改调度

CI临时overlay三文件：追加固定枚举标记；在既有schedule、launch、withFrameNanos、sync、resize、
publish、blank-grace、通知发布前后记录。原有判断、等待常量、顺序、Snapshot事务保持。
将探针语句剥离后必须还原原始字节；条件return展开、空frame callback插入及提取调用返回值有机械逆变换检查。
探针不增加模型revision读取、不使用trace数据决策、不改任务启动方式、不新增UI状态或滚动写者。
启动、数据配置、字体、物理网格按已验证流程，记录时钟为BOOT，生产MONOTONIC逻辑不改。

## 固定矩阵

1k/10k×原生IME隐藏/显示，每场景4轮普通刷新和同步输出对照交错：32窗口=16普通+16sync。
sync对照在同一行写入前发DEC2026开始，固定sleep60ms后结束；不修改应用100ms保护上限。
若同步输出在应用检查前已结束，保留这个观察，不能重试到想要的等待。至少出现一个实际激活的sync
检查才算覆盖该控制路径；未覆盖则报告覆盖不足。所有窗口保留精确字节、原MAIN历史身份和就绪状态。
第一完整响应尝试后仅观察自然绘制500ms；不强制重绘，不重发输入，不改变生产帧率。

输出按真实标记分别给出通知、job排队、frame wait、sync guard、resize guard、模型capture、row publish。
frame wait内部再区分注册→既有frame callback执行、callback→协程继续；不把callback参数的MONOTONIC时间混作执行BOOT时间。
新增标记的布尔状态存入现有可选Boolean槽并以flag导出，表示该标记自己的状态，不是渲染后端声明。
记录已有job合并与完整cycle，不能把一次received机械等同于一次新任务。CPU由同次调度轨迹求交；
分段中位数不能相加。判定需要完整链和原件，不预设“等帧一定多余”。
没有优化对照、性能百分比或生产采用。只有新机制被证实，才考虑单独设计候选并重新冻结性能试验。
