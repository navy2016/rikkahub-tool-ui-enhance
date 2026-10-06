# 42caa6d：会话级标量宽度摘要与完整重挂载对照

本轮把精确宽度索引的所有权从页面绑定移到交互进程会话。页面导航／重建不再丢弃已验证的
历史标量宽度候选；真正删除进程记录、清理 sandbox 或清空管理器时仍立即释放。缓存不保留
`Paragraph`、`TextLayoutResult`、完整历史文本或页面字体对象：字体家族、解析器和四种实际字体
结果都以弱身份校验，弱引用失效或字体／样式／密度／方向／列数／历史来源变化均全量重算。

完整 `detachRestore` 场景在页面分离期间追加并裁剪 12 行，再重建生产视口。三种历史长度、
两种虚拟模式的每次迭代都由硬断言确认：只补测 12 条后台新增历史和当前 24 行活动屏幕；
不会重扫幸存历史。10k 同批次结果如下：

| 模式 | 总操作 | 宽度阶段 | Restore | RSS anon |
| --- | ---: | ---: | ---: | ---: |
| 默认分块图层 | 7347.68 ms | — | 7113.75 ms | 444.64 MiB |
| 原虚拟历史 | 312.12 ms | 2.28 ms | 268.12 ms | 170.91 MiB |
| 键盘稳定虚拟历史 | 319.74 ms | 4.85 ms | 260.94 ms | 177.42 MiB |

旧完整 V3 基线的重挂载宽度阶段来自独立 Runner，不能据此计算严格提升百分比；本轮可接受的
因果证据是源码工作量断言与同批次宽度阶段：历史规模从 1k 增至 10k 时，虚拟模式仍只测 12 行，
宽度阶段保持在 `1.91–4.85 ms`。默认 eager 不使用虚拟宽度索引，作为原行为对照保持不变。

来源：[37497601322](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37497601322)；SHA：`42caa6ddac2db33264328b952f8b50caa4a0729c`；协议：`production-viewport-v3`。

1 场景 × 三种历史长度 × 3 种生产模式 = 9 个用例，每例 3 次，共 27 次测量；全部成功。
每个场景内共享 APK、设备和仪器调用；不同场景／Runner／协议的帧样本不得合并，也不计算跨运行提升率。

原始设备／输入法上下文、源文件哈希和 APK 哈希由[只读导出](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37499482837)传回并校验完整性，保存在同名 JSON。
目标 APK SHA-256：`833d30a318dedb7e633c82c36fc7b5801bad0c73d5de0a9fad66e5662ecd48a7`。

表格来源为通过完整校验后的 CI 摘要，保留两位小数；不是原始 Perfetto 帧样本。缺失项为 —，不补零。
时间单位 ms；RSS 为匿名 RSS 采样峰值的迭代中位数（MiB），不是 PSS／Java／Native／GPU 分项峰值。
输出/次包含生产协调和两次稳定绘制确认；不是无限速 PTY 吞吐。各阶段重叠，不相减分解耗时。

| 场景 | 历史行 | 模式 | 次数 | 首屏 | 总操作 | 输出/次 | CPU p95 | 宽度/次 | RSS anon |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| detachRestore | 1000 | chunkedLayers | 3 | — | 871.18 | — | 547.71 | — | 136.24 |
| detachRestore | 1000 | lazyHistory | 3 | — | 314.81 | — | 120.93 | 2.36 | 97.36 |
| detachRestore | 1000 | lazyHistoryIme | 3 | — | 329.18 | — | 123.06 | 2.35 | 97.57 |
| detachRestore | 5000 | chunkedLayers | 3 | — | 4414.30 | — | 134.73 | — | 230.12 |
| detachRestore | 5000 | lazyHistory | 3 | — | 309.64 | — | 122.55 | 4.27 | 118.98 |
| detachRestore | 5000 | lazyHistoryIme | 3 | — | 312.10 | — | 139.97 | 1.91 | 118.86 |
| detachRestore | 10000 | chunkedLayers | 3 | — | 7347.68 | — | 215.58 | — | 444.64 |
| detachRestore | 10000 | lazyHistory | 3 | — | 312.12 | — | 126.89 | 2.28 | 170.91 |
| detachRestore | 10000 | lazyHistoryIme | 3 | — | 319.74 | — | 132.30 | 4.85 | 177.42 |

## detachRestore — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.53 | 0.37 | 0.75 | — | — | 0.09 | 335.16 | 12.74 | 0.09 |
| 1000 | lazyHistory | 0.59 | 0.37 | 0.76 | 2.36 | — | 0.09 | 11.47 | 0.21 | 113.50 |
| 1000 | lazyHistoryIme | 0.55 | 0.41 | 0.76 | 2.35 | — | 0.08 | 8.31 | 0.12 | 125.54 |
| 5000 | chunkedLayers | 0.53 | 0.46 | 8.55 | — | — | 0.16 | 3152.78 | 85.66 | 0.05 |
| 5000 | lazyHistory | 0.54 | 0.38 | 8.74 | 4.27 | — | 0.08 | 20.34 | 0.14 | 110.96 |
| 5000 | lazyHistoryIme | 0.99 | 0.31 | 6.21 | 1.91 | — | 0.09 | 22.25 | 0.14 | 114.99 |
| 10000 | chunkedLayers | 0.60 | 0.39 | 7.10 | — | — | 0.31 | 4657.64 | 225.71 | 0.05 |
| 10000 | lazyHistory | 3.36 | 0.31 | 12.36 | 2.28 | — | 0.11 | 14.11 | 0.12 | 103.06 |
| 10000 | lazyHistoryIme | 0.57 | 3.71 | 13.83 | 4.85 | — | 0.12 | 18.01 | 0.18 | 110.36 |

### Transitions (ms)

| History | Mode | detach | restore |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 55.12 | 820.91 |
| 1000 | lazyHistory | 39.97 | 266.50 |
| 1000 | lazyHistoryIme | 43.18 | 285.32 |
| 5000 | chunkedLayers | 107.84 | 4255.79 |
| 5000 | lazyHistory | 42.80 | 263.76 |
| 5000 | lazyHistoryIme | 41.99 | 272.40 |
| 10000 | chunkedLayers | 233.27 | 7113.75 |
| 10000 | lazyHistory | 43.99 | 268.12 |
| 10000 | lazyHistoryIme | 38.20 | 260.94 |

## 验收边界

API 34 x86_64 KVM 模拟器；Full 编译；非 debuggable、profileable、未混淆的 Release 派生目标。
复用真实生产渲染、视口控制器和系统键盘；不含完整页面、实际 shell/PTY、输入回显延迟或手机长期稳定性。
本报告不宣称真实手机 FPS，不能把历史版本的其他 Runner 测量当成受控前后对照。

## 生命周期与失效边界

- `InteractiveSessionRecord.terminalTranscriptWidthIndex` 与同一个 `TerminalEmulator` 共存；
  `ProcessSessionPage` 只借用，不在页面 `onDispose` 清空。
- `removeProcessRecord`、过期清理、sandbox 清理和 `clearAllProcesses` 四条路径全部清空索引。
- `TerminalTranscriptWidthMetricKey` 只强持有移除 `FontFamily` 后的样式值、density 和 direction；
  FontFamily、Resolver 和 resolved typeface 均为 `WeakReference`，且按身份匹配。
- eager 回退与虚拟宽度计算共享 `pass.widthMetricKey`，避免切换后误清缓存；异常测量、合成帧、
  旧帧、历史 generation／renderRevision／owner／columns 变化仍撤销复用。
- 活动行更新和追加场景的生产断言分别要求每帧测量 0／1 条历史；重挂载要求恰好 12 条。
- 页面本地／测试后备索引仍在页面销毁时清空；只有明确传入的会话索引可以跨重挂载。

## 回归与交付

| 验证 | 结果 | GitHub Actions run |
| --- | --- | --- |
| 应用 Release JVM | 343 项，失败／错误／跳过均为 0 | [37493977264](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37493977264) |
| Release 视口仪器测试 | 106 项全部通过，含真实 Compose 重挂载增量断言 | [37495452518](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37495452518) |
| 三模式六场景冒烟 | 18 用例通过，含活动更新 0 行、追加 1 行及重挂载 12 行断言 | [37495464249](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37495464249) |
| 完整重挂载场景 | 9 用例／27 次测量通过 | [37497601322](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37497601322) |
| 原始来源证据 | 42 个源码／字体／构建哈希及原始设备上下文校验通过 | [37499482837](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37499482837) |
| Release 构建／独立 APK 校验 | 构建、签名、manifest、ZIP、ABI、哈希均通过 | [37493977223](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37493977223)／[37495475988](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37495475988) |

[下载 Release artifact（ZIP 内含 app-release.apk）](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37493977223/artifacts/11426663689)。
需要登录 GitHub；API 报告到期时间为 `2026-10-20T16:23:28Z`。

- 源码：`42caa6ddac2db33264328b952f8b50caa4a0729c`
- 包名／版本：`me.rerere.rikkahub.dev.next.mod`，`2.1.62`／`151`
- APK 大小：`83697383` bytes；ABI：`arm64-v8a`；`debuggable=false`
- APK SHA-256：`efbf4186a914e57f5ba73ae5b098f7666653463a442b5dbf3560e6986236027a`
- 签名证书 SHA-256：`f136fff34c01d34edd2f625e77df5d408f6e09431b2e46e6d58cc5654a722265`

## 后续

会话重挂载冷扫已消除，但首次进入虚拟模式时仍必须建立精确历史宽度；完整 V3 基线在 10k
首次组合中报告 `420.02–711.68 ms` 的冷宽度阶段。下一步应优先减少活动屏幕重复 intrinsic
测量，并探索不缓存布局对象、不猜测字符宽度的首次历史分块摘要。真实 `ProcessSessionPage + PTY`、
输入到回显、实机 IME 和长期内存／GC 仍未由组件基准覆盖。
