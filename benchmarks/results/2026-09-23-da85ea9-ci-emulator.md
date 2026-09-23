# Terminal scrollback rendering baseline

- Commit: `da85ea958ce2cc0858863ad7eaf9a9fbf8e94e6f`
- Environment: **ci-emulator**; compilation: `Full`; renderer: eager `Column`.
- 80 columns × 24 active rows, JetBrains Mono 14sp, ANSI/ASCII/CJK; sizes below count history only.
- Emulator numbers are diagnostic, **not physical-device FPS or a migration acceptance threshold**.
- No shell/PTY startup, viewport controller, IME, text selection or app-wide startup in this harness.
- Missing metrics are `—`, never zero. Times are ms; RSS anon is MiB (not total/PSS).

| History | Scenario | Repeats | Mount→draw | renderFrame/op | row sync/op | CPU frame p50 | CPU frame p95 | Overrun p95 | Overrun frames % | RSS anon max¹ |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | initialCompose | 3 | 214.32 | 13.76 | 0.48 | 113.03 | 147.89 | 149.21 | 100.00 | 117.13 |
| 1000 | historyScroll | 3 | — | — | — | 63.54 | 79.46 | 94.25 | 100.00 | 115.95 |
| 1000 | activeRowUpdate | 3 | — | 0.59 | 0.80 | 53.55 | 70.26 | 84.35 | 100.00 | 150.53 |
| 1000 | appendAndTrim | 3 | — | 0.40 | 0.99 | 80.58 | 107.82 | 100.01 | 100.00 | 151.97 |
| 1000 | alternateScreenUpdate | 3 | — | 0.37 | 0.13 | 53.17 | 80.23 | 81.82 | 99.16 | 89.37 |
| 5000 | initialCompose | 3 | 877.95 | 53.12 | 2.23 | 92.21 | 820.98 | 909.08 | 100.00 | 373.70 |
| 5000 | historyScroll | 3 | — | — | — | 62.44 | 82.26 | 101.42 | 99.43 | 373.12 |
| 5000 | activeRowUpdate | 3 | — | 0.79 | 2.04 | 113.66 | 142.27 | 346.47 | 100.00 | 245.01 |
| 5000 | appendAndTrim | 3 | — | 0.81 | 4.19 | 1036.42 | 1113.20 | 1282.53 | 100.00 | 252.25 |
| 5000 | alternateScreenUpdate | 3 | — | 0.48 | 0.15 | 43.88 | 73.00 | 72.58 | 98.40 | 199.46 |
| 10000 | initialCompose | 3 | 2105.32 | 110.16 | 1.47 | 86.50 | 1972.91 | 2047.52 | 100.00 | 661.59 |
| 10000 | historyScroll | 3 | — | — | — | 68.81 | 81.09 | 115.12 | 100.00 | 294.75 |
| 10000 | activeRowUpdate | 3 | — | 1.43 | 4.37 | 219.95 | 247.57 | 1165.05 | 100.00 | 364.02 |
| 10000 | appendAndTrim | 3 | — | 1.65 | 11.82 | 3910.64 | 4046.61 | 4825.71 | 100.00 | 348.61 |
| 10000 | alternateScreenUpdate | 3 | — | 0.18 | 0.12 | 44.00 | 70.54 | 68.02 | 98.31 | 134.80 |

¹ Median of per-iteration maxima. Mount and per-operation columns are medians across iterations;
frame percentiles pool captured frames as AndroidX reports them, including the small native harness UI.
Overrun % is the proportion of captured frames with positive `frameOverrunMs`, not inferred from a fixed 16ms threshold.
Initial-compose p95 has few frames: use mount→draw and the raw traces, not p95 alone.
`renderFrame/op` and `row sync/op` do **not** include asynchronous Compose recomposition/layout/draw.
Updates: 30 separately drawn operations, nominally 33ms apart; slow frames extend the workload rather than drop updates.
`alternateScreenUpdate` preloads the stated history but renders only the 24-row alternate screen.

## Device context

```json
{
  "build": {
    "brand": "Android",
    "device": "emu64x",
    "fingerprint": "Android/sdk_phone64_x86_64/emu64x:14/UE1A.230829.036.A1/11228894:userdebug/test-keys",
    "id": "UE1A.230829.036.A1",
    "model": "Android SDK built for x86_64",
    "type": "userdebug",
    "version": {
      "codename": "REL",
      "sdk": 34
    }
  },
  "cpuCoreCount": 2,
  "cpuLocked": false,
  "cpuMaxFreqHz": 2000,
  "memTotalBytes": 4112932864,
  "sustainedPerformanceModeEnabled": false,
  "artMainlineVersion": 342090000,
  "osCodenameAbbreviated": "U",
  "compilationMode": "run-from-apk",
  "payload": {}
}
```

## 采集与验证记录

- 测量源码：`da85ea958ce2cc0858863ad7eaf9a9fbf8e94e6f`。
- [基准工作流 #35812457024](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/35812457024)：
  **success**，2026-09-23 02:58–03:15 UTC。
- [同 SHA 终端回归测试 #35812457049](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/35812457049)：
  **success**，包含新行状态行为测试及现有 viewport controller/reducer 测试。
- 上表原样归档自该基准工作流的 `Terminal rendering measurements` annotation / job summary，
  共 **3 种历史长度 × 5 个场景 × 3 次重复**。CI 的 `--require-complete` 校验已通过：
  每项都有帧数据，首次呈现各含 1 次 render/sync，更新场景各含 30 次 render/sync。
- 原始 AndroidX JSON、Perfetto traces、设备配置、源码/字体 SHA-256、日志和基准 APK 位于该 run 的 artifact：
  `terminal-scrollback-benchmark-da85ea958ce2cc0858863ad7eaf9a9fbf8e94e6f`，artifact ID `10730578116`。
  工作流保留期为 **14 天**；本文件保留汇总，不包含原始逐帧样本或 trace。
- 本地没有执行 Gradle/APK 构建，也没有在用户实体手机上进行性能采集。

### 关于 compilationMode 元数据

测试对 renderer target 显式配置 `CompilationMode.Full()`。上面的原始 `context.compilationMode`
为 `run-from-apk`，来自 AndroidX `BenchmarkData.Context` 对 instrumentation `targetContext` 的查询。
本基准使用 self-instrumenting 的独立 test APK，因此该字段描述 **测试驱动 APK**，不是被测 renderer APK。
保留原始字段，不能将它改写为 `Full`，也不能据此把本次测量称为 debug 构建。

## 判读与下一步决定

### 已观测到的趋势

1. **首次呈现随历史长度明显增加**：mount→draw 的跨重复中位数为
   **214.32 / 877.95 / 2105.32 ms**。冷 `renderFrame` 同时增加至 110.16 ms，但不足以单独解释首次呈现延迟。
2. **静态历史滚动没有同等幅度的退化**：CPU frame p95 为 **79.46 / 82.26 / 81.09 ms**。
   本次最明显的问题不是已经缓存好的历史内容平移，而是历史/活动行更新之后的 UI 工作。
3. **追加并裁剪最严重**：CPU frame p95 为 **107.82 / 1113.20 / 4046.61 ms**。
   10k 相对 1k 约增加 37.5 倍。同期 warm `renderFrame` 仅为
   **0.40 / 0.81 / 1.65 ms/次**，行状态同步为 **0.99 / 4.19 / 11.82 ms/次**。
   这支持优先调查 eager 行列表重组及后续 Compose/UI 工作，而不是只优化 emulator 渲染快照。
   这些统计口径不同，**不能用 p95 减去两项中位数来推导精确的 Compose 耗时**，
   也不能仅凭这些点断定某个内部算法的复杂度。
4. **活动行改写也受到历史规模影响**：CPU frame p95 为 **70.26 / 142.27 / 247.57 ms**。
5. **24 行 alternate screen 对照没有随历史规模同向退化**：CPU frame p95 为
   **80.23 / 73.00 / 70.54 ms**。这支持继续把 TUI 物理网格与普通 history 的优化分开。
6. 首次呈现窗口的 RSS anon 采样峰值中位数为 **117.13 / 373.70 / 661.59 MiB**。
   它包含该窗口内的分配/GC 影响，**不是整个应用的总 PSS，也不是稳定常驻内存**。

### 不能从本次数据得出的结论

- 连 24 行对照的 CPU frame p95 都在约 70–80 ms，大多数 overrun 为正。
  本环境有明显的模拟器/软件 GPU/共享主机开销，不能据此宣称真机只有某个 FPS。
- 只有 3 次重复，未做真机复测，未评估生产 PTY 调度、controller、IME、selection 或 app 启动。
- 尚未运行 LazyColumn 候选，因此没有可以报告的“迁移后提升百分比”。
- 汇总支持定位下一项实验，但没有完成对原始 Perfetto trace 的热点归因。

### 决定

**进入仅限 benchmark 的普通 history `LazyColumn` A/B 验证，不直接迁移生产视口。**

候选应复用相同 emulator、行状态更新、字体、数据集及采样方式，并且：

- 只虚拟化普通 history；active screen 继续保持一个物理网格区域。
- alternate screen、配置的 TUI 命令和完整网格模式继续走现有渲染路径，不能只判断 alternate-screen 标志。
- 比较首次呈现、append/trim、活动行更新、静态滚动及内存；同时观察仍为 O(history) 的行状态同步成本。
- 同机对照并在代表性真机复测后，再决定是否接入生产终端。

生产接入前必须另外验证：稳定 lineId 锚点、行内 clipped offset、裁剪/清空换代、字体缩放、
单一滚动执行器和用户手势 mode 变更规则、IME、跳转期间输出、列表/全屏切换，以及跨行文本选择。
不能简单把 `LazyColumn` 嵌进现有无限高度的 `verticalScroll`，或新增第二个垂直滚动写入者。

本次仅完成基线和实验方向选择；**没有合入 LazyColumn，TUI 渲染与 viewport 坐标模型未迁移。**
