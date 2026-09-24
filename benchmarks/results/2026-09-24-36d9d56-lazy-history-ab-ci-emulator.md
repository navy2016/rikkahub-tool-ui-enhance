# 普通 history LazyColumn A/B 测量报告

- 基准工作流：[#35961866854](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/35961866854)（**success**）。
- 源码 SHA：`36d9d569115806599ba28f13e0f247074135a96b`；每个矩阵项 3 次重复，30 项全部采集并由 `--suite ab --require-complete` 校验通过。
- CI 基准使用 API 34 x86_64 Android Emulator / Nexus 6 profile、2 vCPU、4 GiB guest RAM、768 MiB heap、SwiftShader；eager/lazy 同 APK 同设备相邻运行并交替顺序。
- 被测内容：80 列、24 个 active-screen rows、JetBrains Mono 14sp、ANSI/ASCII/CJK；history 各为 1k / 5k / 10k 行。
- 工作流摘要及原始 AndroidX JSON/Perfetto traces：artifact `terminal-scrollback-benchmark-36d9d569115806599ba28f13e0f247074135a96b`，artifact ID `10793219839`，14 天保留。
- 本地未执行 Gradle/APK 构建；未在实体设备测量。`benchmarks/summarize.py` 的同 run paired comparison 与汇总已成功通过。
- 下表保留 Actions 报告的原值。Mount/逐操作和 RSS 列为跨迭代中位数；frame percentiles 为该 iteration windows 收集的帧样本。
- RSS anon max 为每次迭代采样的匿名 RSS 最大值中位数（MiB），不是 PSS/总 RSS；所有 p95 均为 UI thread CPU frame timing，不是显示帧率。

## 完整 1k / 5k / 10k × 5 场景 × 2 渲染臂

| History | Scenario | Renderer | n | Mount→draw ms | renderFrame/op ms | rowSync/op ms | CPU p50 ms | CPU p95 ms | Overrun p95 ms | Overrun % | RSS anon max MiB |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | initialCompose | eager | 3 | 348.12 | 37.32 | 0.94 | 153.42 | 252.93 | 272.54 | 100.00 | 116.68 |
| 1000 | initialCompose | lazyHistory | 3 | 264.53 | 31.13 | 0.71 | 99.70 | 187.83 | 207.14 | 100.00 | 86.69 |
| 1000 | historyScroll | eager | 3 | — | — | — | 85.69 | 105.63 | 132.37 | 100.00 | 115.05 |
| 1000 | historyScroll | lazyHistory | 3 | — | — | — | 86.47 | 111.28 | 138.64 | 100.00 | 100.72 |
| 1000 | activeRowUpdate | eager | 3 | — | 1.35 | 0.89 | 73.18 | 107.32 | 140.27 | 100.00 | 148.86 |
| 1000 | activeRowUpdate | lazyHistory | 3 | — | 1.38 | 1.71 | 80.40 | 102.41 | 117.48 | 100.00 | 91.54 |
| 1000 | appendAndTrim | eager | 3 | — | 0.66 | 1.26 | 133.60 | 159.78 | 172.70 | 100.00 | 148.02 |
| 1000 | appendAndTrim | lazyHistory | 3 | — | 1.03 | 6.92 | 84.17 | 104.14 | 121.25 | 100.00 | 100.83 |
| 1000 | alternateScreenUpdate | eager | 3 | — | 0.74 | 0.50 | 82.32 | 113.18 | 136.94 | 99.05 | 88.28 |
| 1000 | alternateScreenUpdate | lazyHistory | 3 | — | 0.32 | 0.26 | 80.03 | 103.23 | 124.82 | 100.00 | 88.35 |
| 5000 | initialCompose | eager | 3 | 1422.36 | 112.08 | 2.51 | 171.32 | 1316.87 | 1373.06 | 100.00 | 241.76 |
| 5000 | initialCompose | lazyHistory | 3 | 296.84 | 102.52 | 1.49 | 110.45 | 253.53 | 250.69 | 100.00 | 211.68 |
| 5000 | historyScroll | eager | 3 | — | — | — | 80.37 | 105.03 | 136.23 | 100.00 | 196.82 |
| 5000 | historyScroll | lazyHistory | 3 | — | — | — | 84.20 | 110.13 | 132.92 | 100.00 | 116.86 |
| 5000 | activeRowUpdate | eager | 3 | — | 1.37 | 3.34 | 183.40 | 216.05 | 451.76 | 100.00 | 256.82 |
| 5000 | activeRowUpdate | lazyHistory | 3 | — | 3.30 | 8.52 | 78.63 | 107.47 | 122.63 | 100.00 | 112.85 |
| 5000 | appendAndTrim | eager | 3 | — | 1.43 | 8.26 | 1937.23 | 1997.42 | 2385.03 | 100.00 | 249.25 |
| 5000 | appendAndTrim | lazyHistory | 3 | — | 5.20 | 19.55 | 72.85 | 106.26 | 117.68 | 100.00 | 129.02 |
| 5000 | alternateScreenUpdate | eager | 3 | — | 0.93 | 0.25 | 75.52 | 106.79 | 129.33 | 99.06 | 101.38 |
| 5000 | alternateScreenUpdate | lazyHistory | 3 | — | 0.87 | 0.17 | 81.29 | 105.15 | 124.80 | 100.00 | 101.14 |
| 10000 | initialCompose | eager | 3 | 3597.71 | 188.31 | 3.86 | 226.48 | 3596.16 | 3587.76 | 100.00 | 350.97 |
| 10000 | initialCompose | lazyHistory | 3 | 345.50 | 173.85 | 3.80 | 171.57 | 367.06 | 361.32 | 100.00 | 346.43 |
| 10000 | historyScroll | eager | 3 | — | — | — | 109.47 | 135.74 | 202.02 | 100.00 | 285.40 |
| 10000 | historyScroll | lazyHistory | 3 | — | — | — | 88.15 | 103.67 | 127.72 | 100.00 | 155.46 |
| 10000 | activeRowUpdate | eager | 3 | — | 2.25 | 6.28 | 343.98 | 386.33 | 1034.76 | 100.00 | 344.67 |
| 10000 | activeRowUpdate | lazyHistory | 3 | — | 8.63 | 13.73 | 72.58 | 106.95 | 114.58 | 100.00 | 157.55 |
| 10000 | appendAndTrim | eager | 3 | — | 2.75 | 17.18 | 7342.37 | 7446.08 | 9055.18 | 100.00 | 345.74 |
| 10000 | appendAndTrim | lazyHistory | 3 | — | 5.38 | 31.04 | 55.13 | 96.80 | 91.42 | 100.00 | 188.14 |
| 10000 | alternateScreenUpdate | eager | 3 | — | 0.36 | 0.17 | 79.93 | 112.85 | 132.85 | 100.00 | 134.83 |
| 10000 | alternateScreenUpdate | lazyHistory | 3 | — | 0.33 | 0.24 | 78.70 | 108.91 | 122.20 | 100.00 | 134.75 |

## 同 run 配对 Eager / Lazy 比值

比值为 `eager ÷ lazyHistory`，只对同设备、同场景、同历史规模配对。>1 表示 lazy 的相应统计值较低；不是 FPS 倍数，也不是置信区间。Alternate-screen 两臂都强制 eager，仅作为路径/噪声对照，不作为 Lazy 的性能对比。

| History | Scenario | Mount E/L | CPU p95 E/L | rowSync E/L | RSS max E/L |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1000 | initialCompose | 1.32× | 1.35× | 1.32× | 1.35× |
| 1000 | historyScroll | — | 0.95× | — | 1.14× |
| 1000 | activeRowUpdate | — | 1.05× | 0.52× | 1.63× |
| 1000 | appendAndTrim | — | 1.53× | 0.18× | 1.47× |
| 5000 | initialCompose | 4.79× | 5.19× | 1.68× | 1.14× |
| 5000 | historyScroll | — | 0.95× | — | 1.68× |
| 5000 | activeRowUpdate | — | 2.01× | 0.39× | 2.28× |
| 5000 | appendAndTrim | — | 18.80× | 0.42× | 1.93× |
| 10000 | initialCompose | 10.41× | 9.80× | 1.02× | 1.01× |
| 10000 | historyScroll | — | 1.31× | — | 1.84× |
| 10000 | activeRowUpdate | — | 3.61× | 0.46× | 2.19× |
| 10000 | appendAndTrim | — | 76.92× | 0.55× | 1.84× |

## 结果解读与阶段决定

### 有利于 history-only 懒渲染的信号
- 首次呈现 mount→draw：1k **348→265 ms（1.32×）**，5k **1422→297 ms（4.79×）**，10k **3598→346 ms（10.41×）**。这是 fixture 中初次 Compose mount，而不是 app 启动。
- Append/trim CPU-frame p95：1k **160→104 ms（1.53×）**，5k **1997→106 ms（18.79×）**，10k **7446→97 ms（76.92×）**。候选每次 update 都请求 tail，并在 draw 后断言 tail/active grid 仍可见，因此不是把更新后的 screen 滚出视口所得的虚假收益。
- 活动行改写 p95：5k **216→107 ms（2.01×）**，10k **386→107 ms（3.61×）**。10k 六视口静态滚动 p95 **136→104 ms（1.31×）**。
- Alternate-screen 的 24 行对照 p95 在 1k/5k/10k 均约 105–113 ms；与 history 行数无明显同向恶化。候选在此路径被 gate 回原 eager 网格，没有拆分 TUI 的物理 screen item。
- mount RSS anon max 在 10k 为约 **351→346 MiB**，差异很小；update 场景中的 RSS 样本较低，但不是 PSS、稳态留存或真机省内存证明。

### 仍然存在的瓶颈和限制
- 对 10k append/trim，候选的 O(history) row-state sync 中位数约 **31.04 ms/次**，eager 为 **17.18 ms/次**；更新 state/list 本身仍全量处理。LazyColumn 解决的是 UI 子组合/布局规模，不会让 frame diff 或 row sync 自动变成 O(visible rows)。
- Lazy 的 `renderFrame` / row-sync 单次值在部分更新场景较高，例如 10k 活动行 **8.63 / 13.73 ms**，eager **2.25 / 6.28 ms**。这些是中位数/低重复数数据；不能从帧 p95 减去这些值推导精确 Compose 成本。
- 本次所有场景的 positive frame-overrun 比例约 99–100%，包括被优化的 Lazy arm 和固定 24 行对照；模拟器的软件 GPU/shared runner 负载不能支持“已达流畅 FPS”结论。测试仅 3 次重复、单一 emulator/profile、一次 CI run，缺少实体设备复测。
- benchmark 不覆盖生产 PTY、output scheduler、真实 viewport controller/anchor restore、IME、SelectionContainer、屏幕旋转、列表/全屏 movable-content 切换或手势/惯性操作。LazyListState 与现有 px-based ScrollState/controller 也尚未整合。报告不等同于生产可交付验证。
- 同 run 的 eager 绝对数据存在重尾（例如 10k append p95 7.45 秒），会明显放大比值。成对同机减少设备差异，但 3 次重复没有随机化置信区间/足够 host 重跑。

### 决定：继续做隔离的生产集成原型，不直接作为默认渲染器上线

**A/B 已足以支持继续原型化普通历史区 LazyColumn**：首次 mount 和大 scrollback append/trim 的相对差异明显，并且 24 行 TUI 对照基本保持平坦；不需要为了是否值得编写原型而再做一次 emulator A/B。

但本次结果**不授权直接把 LazyColumn 接入默认 production terminal**。下一步只在单独提交中为普通 history 实现并验证 LazyListState adapter：
1. `LOCKED` 语义按 stable `lineId` 与实际 `LazyListLayoutInfo` 行位置/裁剪像素映射，history trim/clear/new generation、font scale 和小数像素 rounding 均维持锚点。
2. 将 `TAIL / SCREEN / LOCKED` reducer effects 映射到一个明确的滚动执行器；手势夺权、过期 effect、IME、fling、输出与跳转竞态不得新增第二个 scroll writer。
3. Active physical screen 保持原 24 行网格，一个完整 item；alternate screen、配置 TUI 命令与完整网格模式全都继续 eager，并确保 screen-cell/mouse 物理坐标计算不变。
4. 只有普通 history 用 LazyColumn；不在当前 `verticalScroll` Column 内再嵌一个垂直 LazyColumn。
5. 保留 1k/5k/10k 上已证明的尾随/trim 测试；增加 LazyListState adapter 的 reducer/controller 事件序列、真实 Compose 手势/恢复/选择回归测试。
6. 在代表性 Android 真机做同版本 A/B（至少多轮完整 run），关注 10k append 及 active update 的 P95/极值、GC/RSS、锚点稳定性和文本选择；只有生产路径不退化且交互测试通过才讨论默认启用。

在上述 adapter 与真机门槛完成前，生产 `ProcessSessionPage`、物理 TUI 网格及 `TerminalViewportController` **保持不变**；目前只有隔离 benchmark target 使用 history-only LazyColumn。

## 来源链接

- [基准工作流 #35961866854](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/35961866854)
- [基准 job #107511964211](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/35961866854/job/107511964211)
- 原始 artifact ID：`10793219839`（14 天保留；包含 AndroidX JSON、trace、APK、日志与 environment metadata）。
