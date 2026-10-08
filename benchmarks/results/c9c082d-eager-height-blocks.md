# c9c082d：默认终端增量行高索引与完整 V4 验收

应用与 Release SHA：`c9c082deb59c03d21675fc9124556299e969eeb7`。
开发分支：`opt/terminal-verified-a79e7b6`。基线是已修复底行遮挡的 `d18639b`，
其文档 HEAD `e8d3208d09559ad65b7557d5b206dccdc71c82c6` 的应用、基准及构建输入与之相同。
两版分别完成 V4 六场景、三长度、三模式、每例三次的完整矩阵；不合并不同 Runner 的样本。

## 结论

- 默认分块图层在 10k 历史下，30 次追加／裁剪共实际读取 4,320 行历史高度，平均每次 144 行；
  每轮复用 295,680 行高度，重测 60 个边界块。三个独立迭代的计数一致。
- 活动行改写保持零历史行高读取，不增加旧版本已避免的历史工作。块目录仍需 O(H / 128)
  遍历；eager UI 与行高登记仍保留 O(H)，不是整个追加或渲染管线 O(1)。
- 359 项 Release JVM、116 项视口回归、25 项夹具 JVM、18 个冒烟用例全部通过。
  新版本完整 54 用例／162 次测量也通过，保留新会话底部定位修复。
- 同一新版本的 10k 追加场景，默认输出／次 201.29 ms，原虚拟 156.39 ms、键盘稳定虚拟
  160.13 ms。默认总体开销仍较高。本轮证明减少重复读取，尚无旧／新同设备受控性能 A/B，
  不能把独立基线的耗时变化计算成优化提升率。

## 实现及安全边界

旧 `TerminalEagerGeometryCache` 只在整个历史 snapshot 引用相同时复用前缀；FIFO 改变历史，
或任何参与前缀的行卸载，都会撤销整个前缀。新实现沿用真实 Text 行高，按已有的 128 行
不可变历史块保存平坦标量前缀，完整未变的块直接复用，改变的首尾块重新读取实际行高。

每个有效参与行登记唯一的 `TerminalEagerHistoryBlockToken`，它只含归档 bucket 编号，不持有
UI、文本、source 或缓存实例。行高、文本、owner 变化及卸载撤销对应当前块；旧 owner 的
token 不能撤销同 bucket 的新块。字体／密度／方向、列数、来源 owner、history generation、
renderRevision 与反向／替换 snapshot 保留保守失效路径。

缺失、非正高度或测量异常不能发布部分块或整幅几何。其他已经完整验证的当前历史块可供
下次重试使用；屏幕尚未完成时，完整历史前缀仍可复用。已经发布的旧前缀保持不可变；新目录
不引用旧目录或被裁剪的块。数组总和及序号溢出显式拒绝，不产生环绕坐标。

生产修改仅涉及 `TerminalEagerGeometryCache.kt`、`TerminalEagerViewportGeometry.kt` 和
`TerminalLazyItemMeasurements.kt`。视口控制器、首次 eager 测量启用条件、滚动执行器、
文本绘制组件、默认模式选择及 ProcessSessionPage 与 `d18639b` 相同。默认仍为分块图层，
保留真实行高、稳定行 ID、阅读锚点、TUI／全网格、IME、PTY 输入、鼠标、选择和复制语义。

## 工作量证据

以下均为默认模式一次完整迭代中的 **30 次追加／裁剪**，不包含 setup 冷测量；每个长度
都有三条独立启动记录，计数一致。读取指缓存读取行高登记，不是 Compose Text 实际测量次数。

| 历史行 | 实际读取行高 | 复用行高 | 重测块 | 缓存目录访问 | 最终保留块 | 平均读取／次 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | 6,192 | 23,808 | 60 | 492 | 9 | 206.4 |
| 5,000 | 4,080 | 145,920 | 60 | 2,400 | 40 | 136 |
| 10,000 | 4,320 | 295,680 | 60 | 4,740 | 79 | 144 |

目录访问计数覆盖缓存的筛选与拼接遍历，不是所有指令／布局工作。不同长度首尾 bucket
大小不同，所以 1k 边界读取高于 5k 是实际分块结果。正常完整测量的单次 FIFO 单元测试
要求精确等于改变边界的行数、最多 256；生产基准允许含测量重试的最多 512 次读取，
仍会拒绝整历史重扫。旧实现会为该场景的每次新历史 snapshot 扫描全前缀；本报告不把
源码推导值伪装成旧版本的新增计数实测。

四个操作场景共有 108 条唯一启动 token 的 `HEIGHT_WORK`，均与 `WIDTH_WORK` 的场景、
长度、模式和启动匹配。`initialCompose` 与 `detachRestore` 没有该日志，表示未记录，
不是零开销。原始数据见 [c9c082d-height-work.csv](c9c082d-height-work.csv) 及
[完整来源 JSON](c9c082d-production-viewport-ci.json)。

## 验证链

| 验证 | 结果 | 来源 |
| --- | --- | --- |
| 修复版旧基线 e8d3208 完整 V4 | 54 用例／162 测量 | [37720935305](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37720935305) |
| 旧基线来源导出 | 六场景、42 个源码哈希匹配 | [37724074854](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37724074854) |
| c9c082d 应用 Release JVM | 359 项通过，行高缓存 suite 18 项 | [37722630316](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37722630316) |
| c9c082d 完整视口回归 | 116 项通过，0 failures/errors/skips | [37722630336](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37722630336) |
| 夹具 JVM＋六场景三模式冒烟 | 25 项 JVM、18 用例通过 | [37722630303](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37722630303) |
| c9c082d 完整 V4 | 54 用例／162 测量 | [37724061734](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37724061734) |
| c9c082d 来源导出 | 六场景、42 个源码哈希匹配 | [37726277181](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37726277181) |
| Release APK | 构建成功 | [37722630334](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37722630334) |
| APK 独立校验 | 签名／ZIP／manifest／ABI／哈希通过 | [37724070805](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37724070805) |

新增仪器测试覆盖原生 12sp 中文／emoji／组合字符和多种字体缩放、10k 历史跨 bucket
连续裁剪且保持锁定锚点、单个历史行高变化只重读其所属 128 行块。原新会话底行可见性、
手动到底松手、真实键盘、TUI、切换、选择和复制测试全部继续通过。
JVM 另覆盖随机 FIFO／定向失效／metric 变化、缺失行与异常重试、旧 token、完整块退休、
块首对齐、总和溢出和旧几何不可变。本地仅做 56 项基准脚本、15 项诊断脚本及静态检查。

完整结果分别归档在 [旧基线](e8d3208-production-viewport-ci.md) 和
[新版本](c9c082d-production-viewport-ci.md)；来源与成功组计数汇总在
[c9c082d-verification.json](c9c082d-verification.json)。它们是独立运行，不是性能 A/B。

## 剩余问题与下一阶段

新版本 10k 默认冷首屏为 7,654.35 ms、恢复总操作为 8,530.40 ms；这些模拟器诊断值说明
全量 eager 行树的首次组合／测量仍是后续目标。单靠块级前缀复用没有解决冷首屏、
全页输入回显或所有终端卡顿。真实 ProcessSessionPage＋PTY 回显、同设备受控性能对照、
实机 30 分钟内存／GC 压力验收仍未完成，不能称为手机 FPS 或实机端到端加速。

下一轮先补真实页面／PTY 的输入到绘制追踪与受控对照，再依据证据处理视口重复协调或
冷测宽；不撤销底部定位修复，也不把虚拟模式改成默认。

## Release APK

[下载 ZIP（包含 app-release.apk，需 GitHub 登录）](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37722630334/artifacts/11526304905)。
交付时 artifact 未过期；API 到期时间 `2026-10-22T03:32:15Z`。

- SHA：`c9c082deb59c03d21675fc9124556299e969eeb7`
- 包名 `me.rerere.rikkahub.dev.next.mod`；版本 `2.1.62`／`151`
- APK 83,699,299 bytes；artifact ZIP 81,438,380 bytes；`arm64-v8a`；非 debuggable
- APK SHA-256：`18ec80ac69d6569362a2f2529ba5beb893b316e6caaa73bb49720e9d0b3cd508`
- 签名证书 SHA-256：`f136fff34c01d34edd2f625e77df5d408f6e09431b2e46e6d58cc5654a722265`

不需要清除数据或切换渲染方式。实机复测优先使用新会话默认模式，长输出达到历史上限后
持续追加，检查底行、锁定阅读、手动到底松手与键盘往返。
