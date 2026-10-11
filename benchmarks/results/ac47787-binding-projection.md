# ac47787：消除布局指标引发的面板重组，完整 V4 验收

生产、测试和 Release 均为 `ac47787cf2939a91779f2563fa40bb8b610a07e7`，
分支 `opt/terminal-verified-a79e7b6`。所有 Android／原生编译和仪器测试在 GitHub Actions 完成。

## 本轮成果

- 24 次真实输出区域高度变化，旧完整指标订阅产生 24 次额外重组；新绑定所在面板为 0。
  24 次独立检查均确认实际 Text 底行完整可见，控制器继续跟随最新布局，绑定对象保持同一实例。
- 374 项 Release JVM、129 项视口回归、25 项夹具 JVM全部通过；完整 V4 六场景、三长度、
  三模式、每例三次，共 54 用例／162 次测量全部通过。
- 实际页面／PRoot／native PTY 的 15 个传输探针、36 条回显全部通过；36 条采样的
  `panelCompositions` 均为 1。此窗口包含 SetText、提交和输出，不是纯输出重组计数。
- 正式 arm64 Release 的签名、ZIP、包名、ABI 和哈希独立校验成功。

## 改动与保护

唯一生产修改是 `TerminalViewportBinding.kt`。原来组合期间调用 `metrics().usesTuiViewport`，
连带订阅了 supplier 读取的滚动范围、视口高度等布局状态。现在只把 TUI 布尔值的变化
传给组合，并保留最新 supplier，避免因捕获旧闭包而漏掉后续模式转换：

```kotlin
val latestMetrics by rememberUpdatedState(metrics)
val usesTuiViewport by remember(sessionKey) {
    derivedStateOf(structuralEqualityPolicy()) { latestMetrics().usesTuiViewport }
}
val measuredEager = !virtual && !usesTuiViewport
```

原 `snapshotFlow` 仍观察 `current.metrics()` 的全部实时字段，原有等帧、测量通知确认、
live geometry 读取及唯一滚动执行器不变。普通默认会话从首次布局采用真实行高的条件不变。
同时按实际依赖记忆 `TerminalRowMeasurementScope` 和 `TerminalBoundViewport`；字体、frame
身份、模式和 controller 变化时，相关对象正确失效，未变化时复用。

新增三项仪器测试覆盖真实高度变化／底行、无关面板更新、替换 supplier、TUI 往返、字体
变化、同 revision 不同 frame、controller 替换。旧测量能力 token、图层、输入／PTY、
鼠标、选择复制、KEYS、AUTO／LOCK、虚拟历史 IME 回退和默认渲染方式均未修改。
缺测／过期／取消保护仍由原测试覆盖，不以行高估算或隐藏多余行替换真实几何。

CI 要求唯一 `BINDING_WORK` 及三个精确 JUnit 用例全部成功；记录绑定源码与夹具 APK 哈希。
独立归档重新核对全部分片、源码和计数：[绑定证据](ac47787-binding-evidence.json)、
[既有订阅对照](ac47787-panel-evidence.json)、[滚动对照](ac47787-scroll-evidence.json)。

## 验证来源

| 验证 | 成果 | Run |
| --- | --- | --- |
| Release JVM | 374 项，0 失败／跳过 | [38048241638](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/38048241638) |
| 完整视口回归 | 129 项，含真实布局计数对照 | [38048241632](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/38048241632) |
| 完整生产 V4 | 25 夹具 JVM、54 用例／162 测量 | [38048241693](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/38048241693) |
| V4 来源导出 | 六场景、42 个源码哈希匹配 | [38049802921](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/38049802921) |
| 真实页面 PTY | 15 探针、36 回显 | [38048241980](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/38048241980) |
| 正式 Release | 构建成功 | [38048241765](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/38048241765) |
| APK 独立校验 | 签名／ZIP／manifest／ABI／哈希通过 | [38049340982](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/38049340982) |

[验证汇总 JSON](ac47787-verification.json) 保留各 run、来源哈希及 APK 身份。
本地仅执行 54 项 CI 脚本测试、56 项基准脚本测试及静态检查，未本地编译。

## 性能边界与下一重点

本轮证明减少了布局触发的重复组合，不宣称所有场景同比例加速。当前完整 V4 与历史版本
使用不同 Runner，不计算跨运行提升率。上一轮仅比较完整状态／布尔订阅的同设备实验已
单独归档：[3b5c3e0 AB／BA 对照](3b5c3e0-paired-subscription.md)；其耗时有升有降，不能
作为本轮布局指标投影的性能 A/B。

最新 V4 中，10k 默认模式的冷首屏为 8,246.65 ms、重新挂载总操作为 5,758.30 ms；
虚拟历史两模式的 10k 冷测宽分别为 1,473.10／1,979.04 ms。它们是模拟器中的诊断指标，
不是手机实测；说明默认全量行树的冷组合／测量、虚拟模式冷测宽仍需重点优化。
10k 默认追加仍保持每轮 30 次操作读取 4,320 行历史高度（每次平均 144），旧增量行高收益保留。

[完整 V4 表格](ac47787-production-viewport-ci.md)、[CSV](ac47787-production-viewport-ci.csv) 和
[原始来源 JSON](ac47787-production-viewport-ci.json) 保留全部结果，包括较高耗时。
测宽／测量等阶段可能重叠，不相加或相减推导未直接记录的指标。

[真实 PTY 样本](ac47787-pipeline-samples.csv) 和 [完整证据](ac47787-pipeline.json) 含安装后
目标／测试 APK 哈希。`inputToDrawMs` 是包含回复的首次 draw 回调，可见检查包含测试等待；
两者均不代表 GPU 呈现、物理键盘延迟或手机 FPS。测试用 x86_64 PRoot 覆盖仍不进入正式
arm64 Release。手机长期输出、30 分钟内存／GC 和实际输入延迟验收尚待完成。

## Release APK

[下载 Release ZIP（内含 app-release.apk）](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/38048241765/artifacts/11668453753)。
归档时 artifact 未过期，API 到期时间 `2026-10-24T11:33:52Z`。

- 包名 `me.rerere.rikkahub.dev.next.mod`，版本 `2.1.62`／`151`，仅 `arm64-v8a`，非 debuggable。
- APK 83,699,827 bytes；artifact ZIP 81,438,447 bytes。
- APK SHA-256：`391b95cfd82ae4001118ce379dbc35b56aa9b4b48b6f16f640f12fa1ca5067e6`。
- 签名证书：`f136fff34c01d34edd2f625e77df5d408f6e09431b2e46e6d58cc5654a722265`。
- [独立校验记录](ac47787-apk-verification.json)。保留数据直接升级，无需改变默认操作或渲染选项。
