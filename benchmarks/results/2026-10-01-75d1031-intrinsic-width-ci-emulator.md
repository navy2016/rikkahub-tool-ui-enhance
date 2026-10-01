# 冷宽度扫描优化：精确自然宽度三组对照

结论：在同一模拟器、同一 APK、同次运行中，10k 历史的冷宽度扫描从 **908.41 ms 降至 423.87 ms**（−53.3%），首次挂载从 **1064.42 ms 降至 638.39 ms**（−40.0%）。逐行整数像素宽度与原完整布局一致，视口回归全部通过。首屏匿名 RSS 峰值样本基本不变，1k 首屏样本反而更慢；不宣称内存峰值或所有场景改善，不切换生产默认渲染器。

## 状态栏和 KEYS 保护

用户要求：不得改变终端状态栏／KEYS 中任何功能和语义，确需调整必须先提方案并获得确认。

- `b8baf63` 将这一限制写入仓库 `AGENTS.md`，包括入口、行为及语义。
- `git diff b8ea113..75d1031 -- app` 为空；本轮所有应用源码、状态栏／KEYS 处理逻辑、配置、输入、选择、TUI 和滚动控制器均未改动。
- `ProcessSessionPage.kt` SHA-256：`f293ccf7e2b77d6091a663e6af89572ae5d024be9df39284a4e67ad067ecedad`。
- 实现仅在 opt-in 基准／正确性 APK 中；用户当前应用 APK 无需更换，也没有新增状态栏／KEYS 开关。

## 改动及计时边界

被测提交：`75d1031ece31a0f79501200d71d3d365a5a61dd3`；分支 `fix/terminal-viewport-semantic-reducer`。

`TerminalIntrinsicWidthMeasurer` 使用 Compose `MultiParagraphIntrinsics` 的 `ceil(maxIntrinsicWidth)`。这正是无宽度上限、`softWrap=false`、`Clip` 场景中 TextMeasurer 在后续布局之前选择的宽度。显式样式先按相同方向解析默认值，字体塑形、样式跨度、回退字体和双向文本处理仍由 Compose 完成。

仅省去宽度预测量后续的 `MultiParagraph`／`TextLayoutResult` 创建；真正显示的 Text 仍使用原来的布局和绘制。没有字符数乘字宽的近似，没有新增长期 Paragraph 缓存，也没有强制 GC 或把冷宽度扫描移出计时区间。

三组均编入同一个 APK，按尺寸／场景相邻轮换顺序，每项 3 次：

| renderer | 内容 |
| --- | --- |
| `chunkedLayers` | 当前生产使用的全部历史 eager 分块／绘制层 |
| `lazyHistory` | 已修复横向范围的旧候选，以完整 TextMeasurer 布局测量宽度 |
| `lazyIntrinsic` | 新候选，仅计算精确自然宽度 |

两种 lazy 共享相同的行树、FIFO 宽度索引、帧／行同步器及跟尾方式，只有宽度测量路径不同。冷扫描仍需访问 H 行；活动更新访问 0 个历史宽度，单行归档访问 1 个新增历史宽度，活动屏幕每次仍测量。CI 保留这些工作量断言，并检查候选未调用完整宽度布局路径、旧对照未误用新路径。

这些计数验证的是调用路径，不是完整的 JVM／native 堆分配剖析；不能据此宣称对象分配总量或保留内存为零。

## GitHub Actions 验证

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| Release 视口／自然宽度 | 60 项，失败／错误／跳过／缺失套件均为 0 | [36854843875](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36854843875) |
| 基准夹具 JVM 测试 | 19 项：布局 10、FIFO 宽度索引 8、工作负载 1，全部通过 | [36854843971](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36854843971) |
| 同机性能矩阵 | 45 项：3 尺寸 × 5 场景 × 3 renderer，每项 3 次；宽度 trace 完整性检查通过 | 同上 |
| 本地报告验证 | 16 项 Python 测试、workflow YAML／shell 语法检查通过 | 未本地编译 Android／Kotlin |

60 项包括：24 项真实手势、8 项生产分块几何、11 项自然布局在两种宽度路径各运行一次（22 项），以及 6 项逐行宽度测试。逐行测试覆盖 ANSI／中文／组合字符／emoji、混合字体与变换、双向文本与段落缩进、空白与边界长度、palette／resize／cursor／generation 和 FIFO 失效。比较整数像素宽度，不放宽容差；完整视口检查行位置、范围、裁剪及 RTL。

应用源码及应用单测仍与已通过 264 项 Release 单测的 `8eb1ead` 相同，本轮没有把旧单测运行冒充新运行。所有本轮 Android／Kotlin 构建与仪器测试均在 GitHub Actions 执行，使用 Release／Release 派生目标，没有本地编译或构建应用 Debug APK。

## 同次测量结果

环境：API 34 x86_64、Nexus 6 profile、2 cores、4 GiB RAM、768 MiB heap、SwiftShader、`CompilationMode.Full()`；80 列＋24 活动行、JetBrains Mono 14sp。性能工作流时间 2026-10-01 11:21:21–11:49:14 UTC。下表单位 ms；RSS 单位 MiB，是匿名 RSS，不是 PSS 或总内存。

| 历史行数 | 指标 | 生产分块 | 完整宽度旧候选 | 自然宽度新候选 |
| ---: | --- | ---: | ---: | ---: |
| 1,000 | 首次挂载→绘制 | 327.29 | 223.34 | 272.14 |
| 5,000 | 首次挂载→绘制 | 1005.74 | 598.37 | 404.15 |
| 10,000 | 首次挂载→绘制 | 2001.21 | 1064.42 | 638.39 |
| 10,000 | 冷宽度扫描 | 不适用 | 908.41 | 423.87 |
| 10,000 | 活动更新 width/call | 不适用 | 7.15 | 2.05 |
| 10,000 | 追加裁剪 width/call | 不适用 | 7.77 | 3.20 |
| 10,000 | 活动更新 CPU frame p95 | 111.89 | 109.15 | 109.10 |
| 10,000 | 追加裁剪 CPU frame p95 | 166.84 | 106.39 | 105.29 |
| 10,000 | 历史滚动 CPU frame p95 | 109.35 | 110.64 | 103.75 |
| 10,000 | 首屏匿名 RSS max 中位数 | 353.03 | 387.92 | 387.80 |

宽度扫描本身在三种尺寸都降低：1k 为 114.80→46.12 ms，5k 为 458.55→188.48 ms，10k 为 908.41→423.87 ms。但完整首屏包含其他阶段，1k 的 223.34→272.14 ms 不能掩盖；不据此建立自动行数切换阈值。活动更新和追加裁剪 frame p95 在两种 lazy 间也没有与宽度耗时同比例下降。

10k 两种 lazy 的首屏匿名 RSS 样本为 387.92／387.80 MiB，未证明峰值下降。多个场景、包括同实现的备用屏幕，其 RSS 有明显差异；本轮没有堆转储或分配 trace，无法确定保留对象与 GC 的贡献。因此内存问题仍需独立剖析，不把减少完整布局调用直接等同于内存改善。

width/call 是每次迭代 sum/count 后取中位数；CPU p95 聚合捕获帧。它们不能相减推算其他阶段耗时；width 工作已包含在 mount／CPU 中，不属于 rowSync。所有结果仅为模拟器诊断，不是实际设备 FPS。不同运行的绝对数值不作受控比较；备用屏幕三组均走相同 eager 物理网格，不解释为 TUI 提速。

## 保留的后续门槛

1. 独立剖析首屏内存峰值，继续减少不必要的度量／分配，并复测小历史场景。冷扫描仍为 O(H)。
2. 可变真实行高与未知全局范围尚未完整接入生产语义滚动控制器；固定行高手势夹具与直接 item 地址的自然布局测试不能代替它。
3. 跨 lazy item 选择／剪贴板、真实 IME、鼠标坐标、回退切换、真实 shell 压力和设备 GPU 仍需验收。
4. 状态栏／KEYS 功能、入口、行为和语义保持不变。即便后续认为调整必要，也必须先给用户方案并获确认；本轮不提议或执行此类调整。

原始 benchmark JSON、Perfetto、Release 派生夹具 APK 位于 [artifact 11160260323](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36854843971/artifacts/11160260323)，名称 `terminal-scrollback-benchmark-widthIntrinsics-75d1031ece31a0f79501200d71d3d365a5a61dd3`，保留至 2026-10-15。配套 45 项矩阵 CSV 和 18 项宽度 CSV 由同次 check-run annotations 转换，是摘要而非逐帧原始数据。
