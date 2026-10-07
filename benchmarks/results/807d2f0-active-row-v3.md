# 807d2f0：独立分支、活动屏幕增量测宽与 Release 验收

开发分支：[opt/terminal-verified-a79e7b6](https://github.com/navy2016/rikkahub-tool-ui-enhance/tree/opt/terminal-verified-a79e7b6)。
按用户要求，从本地已验证的 `a79e7b693d42f22a086959e9fac0525b88515663` 建立并推送，
未 pull、merge、rebase 或覆盖原远端 `fix/terminal-viewport-semantic-reducer` 的新提交。
最后检查原远端仍为 `e7a7c7f658b0ba0d18c8bd575c057c0e6abafbef`；本报告不评价这些提交的质量。

应用优化提交：`ebc13f231c70b202633eb4cac5d252502bd02c9e`；Release 构建提交：
`807d2f02836f25ff0cfd1b23332a374537b3a1a0`。后者只改 JVM 测试夹具，应用、仪器测试、基准及
workflow 文件与前者一致。默认仍为 `CHUNKED_LAYERS`；既有 RENDER 可选模式、键盘、状态栏、
KEYS、PTY、自然行高、链接及复制语义不变，没有新增用户操作。

## 本轮结论

- 虚拟模式的 24 行活动屏幕，每次单行改写只重新测宽 1 行。每轮 30 次更新共测量 30 行、
  复用 690 行、归档历史额外测量 0 行；27 条启动 token 绑定记录齐全，三种历史长度均通过。
  原实现无条件测量 24 × 30 = 720 行；这是明确的工作量减少，不是整体速度提升 24 倍。
- 缓存最多保留当前物理屏幕的注解文本键与标量宽度（上限 80 行），不缓存 Paragraph、
  TextLayoutResult、可变 cell、完整 frame 或不断累积的历史文本。
- 完整活动行基准中，10k 新虚拟模式输出／次为 192.85 ms，默认模式为 179.94 ms。
  整体更新仍未快于默认；该运行不含同机旧测宽实现，不能用其他 Runner 的旧数据计算速度提升率。
- 冷启动／重新挂载和 metric 失效仍需扫描相关历史。本轮没有消除全部终端卡顿；后续应追踪
  活动行更新的视口协调／滚动完成等待，并独立验证冷扫描和实机 PTY 输入回显。

来源：[37563252917](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37563252917)；SHA：`807d2f02836f25ff0cfd1b23332a374537b3a1a0`；协议：`production-viewport-v3`。

1 场景 × 三种历史长度 × 3 种生产模式 = 9 个用例，每例 3 次，共 27 次测量；全部成功。
每个场景内共享 APK、设备和仪器调用；不同场景／Runner／协议的帧样本不得合并，也不计算跨运行提升率。

原始设备／输入法上下文、源文件哈希和 APK 哈希由[只读导出](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37564434194)传回并校验完整性，保存在同名 JSON。
目标 APK SHA-256：`af16028f7cbd70de57abc29c1746f1ead3709b61f02ed76c983291d8f2071c49`。

表格来源为通过完整校验后的 CI 摘要，保留两位小数；不是原始 Perfetto 帧样本。缺失项为 —，不补零。
时间单位 ms；RSS 为匿名 RSS 采样峰值的迭代中位数（MiB），不是 PSS／Java／Native／GPU 分项峰值。
输出/次包含生产协调和两次稳定绘制确认；不是无限速 PTY 吞吐。各阶段重叠，不相减分解耗时。

| 场景 | 历史行 | 模式 | 次数 | 首屏 | 总操作 | 输出/次 | CPU p95 | 宽度/次 | RSS anon |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| activeRowUpdate | 1000 | chunkedLayers | 3 | — | 5174.93 | 172.48 | 134.20 | — | 105.98 |
| activeRowUpdate | 1000 | lazyHistory | 3 | — | 5909.41 | 196.96 | 132.87 | 0.48 | 109.44 |
| activeRowUpdate | 1000 | lazyHistoryIme | 3 | — | 5708.01 | 190.26 | 133.14 | 1.82 | 109.31 |
| activeRowUpdate | 5000 | chunkedLayers | 3 | — | 5267.91 | 175.58 | 135.03 | — | 165.41 |
| activeRowUpdate | 5000 | lazyHistory | 3 | — | 5750.62 | 191.67 | 133.77 | 0.77 | 118.69 |
| activeRowUpdate | 5000 | lazyHistoryIme | 3 | — | 5620.76 | 187.34 | 133.68 | 0.67 | 118.88 |
| activeRowUpdate | 10000 | chunkedLayers | 3 | — | 5398.71 | 179.94 | 135.62 | — | 272.32 |
| activeRowUpdate | 10000 | lazyHistory | 3 | — | 5775.99 | 192.52 | 134.13 | 1.25 | 151.09 |
| activeRowUpdate | 10000 | lazyHistoryIme | 3 | — | 5789.41 | 192.85 | 134.47 | 1.18 | 160.80 |

## activeRowUpdate — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.18 | 0.52 | 0.46 | — | — | 0.04 | — | 0.01 | — |
| 1000 | lazyHistory | 0.15 | 1.19 | 0.19 | 0.48 | — | 0.33 | — | 0.03 | 98.00 |
| 1000 | lazyHistoryIme | 0.09 | 0.62 | 0.26 | 1.82 | — | 0.36 | — | 0.01 | 106.55 |
| 5000 | chunkedLayers | 0.16 | 0.40 | 0.15 | — | — | 0.05 | — | 0.01 | — |
| 5000 | lazyHistory | 0.14 | 1.10 | 0.44 | 0.77 | — | 0.19 | — | 0.02 | 96.93 |
| 5000 | lazyHistoryIme | 0.09 | 0.51 | 0.31 | 0.67 | — | 0.22 | — | 0.01 | 83.31 |
| 10000 | chunkedLayers | 0.12 | 0.54 | 0.38 | — | — | 0.05 | — | 0.01 | — |
| 10000 | lazyHistory | 0.12 | 0.77 | 0.14 | 1.25 | — | 0.19 | — | 0.01 | 89.85 |
| 10000 | lazyHistoryIme | 0.10 | 0.67 | 0.31 | 1.18 | — | 0.23 | — | 0.01 | 69.45 |

## 验收边界

API 34 x86_64 KVM 模拟器；Full 编译；非 debuggable、profileable、未混淆的 Release 派生目标。
复用真实生产渲染、视口控制器和系统键盘；不含完整页面、实际 shell/PTY、输入回显延迟或手机长期稳定性。
本报告不宣称真实手机 FPS，不能把历史版本的其他 Runner 测量当成受控前后对照。

## 缓存正确性与生命周期

仅 `RenderFrame.ownedRows()` 认可的可信帧可以缓存活动屏幕。复用同时要求 owner、generation、
renderRevision、columns、screenGeneration、metric 相容，且稳定行 ID 和完整 `AnnotatedString`
相等。不能只比较 `.text` 或引用，因为渲染器每帧重建屏幕注解，ANSI、URL、光标和段落样式也会变化。

每轮重新建立当前屏幕映射，只保留幸存行；当前最大宽度每轮由当前各行取最大，允许缩短而不是
永远保留旧最大值。新增屏幕映射在所有测量成功后才发布；异常或非法负宽度不得保留部分结果。
字体／密度／方向变化、非可信替换帧、来源或屏幕代际变化会撤销复用。

`retainFor()` 进入兼容回退时立刻清空屏幕文本键，继续仅保留原有历史标量候选及有效性元数据，
不改变原虚拟模式的 IME 锁存回退与显式 retry。`clear()`／页面释放同时清理全部所有权。

## 回归及测试前提修复

`ebc13f2` 的首次 JVM 回归有三项新增夹具失败，原因是既有 `appendStyledLine()` 即使隐藏光标，
仍为当前光标列保留尾部空格，光标移动会改变额外一行。准确诊断包括：

```text
expected:<row:105 []> but was:<row:105 [ ]>
expected:<1> but was:<2>
```

`807d2f0` 仅对隔离单行／样式的夹具显式将光标归零，并新增
`hiddenCursorColumnReservationChangesBothMovedAndNewRows` 覆盖真实的两行变化；没有修改
终端渲染行为或放宽全量宽度 oracle。18 项宽度索引 JVM 测试覆盖 6／24／80 行、FIFO、插删行、
最大值收缩、ANSI／URL、合成帧／不同来源、测量失败、随机光标／尺寸变化和回退释放。

仪器测试用 cacheSize=0 的真实字体完整布局作独立 oracle，覆盖 CJK、emoji、组合字符、ANSI、
光标、URL、密度、RTL、缩放和 resize。真实 IME 及原有像素／复制回归继续通过。

| 验证 | SHA | 结果／来源 |
| --- | --- | --- |
| 应用 Release JVM | 807d2f0 | [349 项通过](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37562406033)，0 failures/errors/skips |
| Release 视口仪器测试 | ebc13f2 | [107 项通过](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37561477792)；应用和仪器输入与 807d2f0 相同 |
| 夹具 JVM＋六场景三模式冒烟 | ebc13f2 | [25 项 JVM、18 个冒烟用例通过](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37561477752) |
| 完整活动行更新 | 807d2f0 | [9 用例／27 次测量通过](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37563252917)，非完整六场景矩阵 |
| 来源导出 | 807d2f0 | [37564434194](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37564434194)，42 个源／字体／构建哈希匹配 |
| 应用 Release | 807d2f0 | [构建成功](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37562406012) |
| 独立 APK 校验 | 807d2f0 | [签名、ZIP、manifest、ABI、SHA-256 通过](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37563260757) |

本地仅运行 53 项基准脚本测试、10 项 CI 报告测试及静态检查；Android 编译与设备执行都在 GitHub Actions。
之前的 [a79e7b6 六场景基线](a79e7b6-verification.md) 已单独归档，不挪用为本轮新代码的全量性能结果。

## 每轮活动屏幕工作量

以下三种历史长度的每个模式均有三条独立启动记录；数字为每轮 30 次更新期间的增量，
不包含 setup 的冷测量。原记录和源码／APK 上下文保存在同名 JSON 的 `work_samples`。

| 历史行 | 模式 | 测量历史 | 测量屏幕 | 复用屏幕 | 最终保留屏幕 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1,000／5,000／10,000 | chunkedLayers | 0 | 0 | 0 | 0 |
| 1,000／5,000／10,000 | lazyHistory | 0 | 30 | 690 | 24 |
| 1,000／5,000／10,000 | lazyHistoryIme | 0 | 30 | 690 | 24 |

默认模式不调用虚拟宽度索引，不能将其 0 次解读为默认模式完全不测量文本。
带光标换行时前一光标行和新行可同时变化，因此追加场景单次允许测两行，不能忽略真实注解变化。

## Release APK

[下载 ZIP（内含 app-release.apk）](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37562406012/artifacts/11457930063)。
需要登录 GitHub；未在本地强行下载 APK。API 报告到期时间：`2026-10-21T02:41:06Z`。
详情：[807d2f0-apk-verification.json](807d2f0-apk-verification.json)。

- 构建 SHA：`807d2f02836f25ff0cfd1b23332a374537b3a1a0`
- 包名：`me.rerere.rikkahub.dev.next.mod`；版本：`2.1.62`／`151`
- APK：`83696535` bytes；`arm64-v8a`；`debuggable=false`
- APK SHA-256：`e765b2314848d9628aff5e958b3df207036ac436f24b0135ef4a04c5bfd505cc`
- 签名证书 SHA-256：`f136fff34c01d34edd2f625e77df5d408f6e09431b2e46e6d58cc5654a722265`
