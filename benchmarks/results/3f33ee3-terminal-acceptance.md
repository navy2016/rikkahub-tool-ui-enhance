# 3f33ee3：面板订阅收窄与真实页面／PTY 验收

生产代码及 Release APK：`3f33ee3ae030e39692e5bb09358e6ca073ab3296`。
分支：`opt/terminal-verified-a79e7b6`。本轮减少控制器状态引发的无关面板重组，
保留此前 `9476a78` 的虚拟滚动提前完成、默认新会话底行修复和增量真实行高索引。

## 结论

- 同一控制器、同一设备上的 30 次锚点更新及 24 次手势开始／结束，旧订阅产生 54 次
  额外重组，新订阅为 0；三次 AUTO／LOCK 转换仍分别更新。统计不包含初次挂载。
- 374 项 Release JVM、126 项视口回归、25 项夹具 JVM、18 个组件冒烟全部通过。
  控制器替换和生命周期暂停／恢复不泄漏旧状态或丢失最新跟随状态。
- 实际 `ProcessSessionPage`、生产会话管理器、PRoot 和 native PTY 完成 15 个传输探针，
  三模式 × 四阶段 × 三次的 36 条回显全部通过，无 pipe 后端替代。
- 正式 arm64 Release 签名、包名、ZIP、ABI 及 SHA-256 独立校验通过。
  当前没有旧／新完整页面同设备配对计时，不能由这些结果计算整体提速或手机 FPS。

## 实现和不变边界

面板原先收集整个 `TerminalViewportControllerState`，却只读取 `autoScroll`。
`TerminalViewportFollowState.kt` 使用冷 `map { it.autoScroll }.distinctUntilChanged()`
投影，保留 `collectAsStateWithLifecycle`。`key(controller)` 将初值和收集范围绑定到当前
会话控制器，替换控制器时不沿用上一会话的布尔值；没有新增可变 follow 状态、后台 scope
或 `stateIn` 缓存。

滚动绑定、唯一滚动执行器、手势和视口持久化仍使用原控制器完整实时状态。未修改控制器、
默认渲染选择、自然 Text 行高、稳定行 ID、阅读锚点、AUTO／LOCK 操作、状态栏／KEYS、
SEL、MOUSE、TUI／备用屏幕、虚拟历史 IME 回退、PTY 字节顺序、resize 或输出合并策略。

`PANEL_COMPOSED` 仅在仪器测试明确安装 `TerminalPipelineTrace` 后记录提交的面板重组，
正常使用不安装计数 SideEffect，也不输出日志。追踪只接受固定枚举、数量和 revision，
没有用户命令／输入／输出文本；4096 事件上限、丢失检测、关闭及会话隔离保持不变。

## 可复核的工作量证据

[新旧订阅记录](e55e693-panel-subscription.json) 来自同一次挂载的两个订阅；每次状态更新
之后都等待 Compose 完成，不用突发更新被 StateFlow 合并来冒充避免重组。
三个仪器测试还分别验证旧控制器解绑、替换后第一帧初值、停止时不收集和恢复后最新状态。
五个 JVM 测试覆盖一万次效果／手势变化、锁定锚点／尺度变化、全部布尔转换和冷流生命周期。

CI 报告器要求三个精确 JUnit 用例全部成功，唯一 `FOLLOW_WORK` 的四项整数计数严格匹配。
缺失、重复、跳过、浮点或布尔伪整数均拒绝。复制进夹具的四个生产源码逐一校验，
报告包含 11 个来源哈希及实际夹具／测试 APK 哈希。

此前的滚动优化也在本轮复验：[40 条滚动配对记录](e55e693-scroll-pairs.json)。
历史锚点和整屏跟随各 10 对，旧执行器最终等待 1 帧，新执行器 0 帧，几何目标相同；
缺测、字体变化、过期 token、取消和停滞上限测试全部通过。只在滚动 mutation 返回后
重新读取布局、同 frame／字体／视口且目标已满足时提前结束，保留动画交接等待。
这些是执行器等待和订阅工作量的受控证据，不是完整页面整体延迟比例。

## 真实页面结果

测试使用 Android 14 x86_64 模拟器，1440×2560、密度 560、LatinIME。每模式独立进程，
真实输入框语义动作调用正常提交回调；固定子进程通过 native PTY 回显。阶段依次为
挂载后、键盘显示、键盘隐藏、组合树重新挂载；不是 Android 进程死亡恢复或物理键盘输入。
验证精确回显字节数、完整 frame revision、真实行可见边界、渲染回退和键盘期间 PTY 行数。

下表每格计时均为该组 3 条记录的中位数，单位 ms；重组数在该组三条记录中一致。

| 模式 | 阶段 | 输入到首次 draw | 输入到可见检查 | 面板重组 |
| --- | --- | ---: | ---: | ---: |
| 默认分块图层 | 挂载 | 70.84 | 178.97 | 2 |
| 默认分块图层 | 键盘显示 | 86.02 | 232.89 | 2 |
| 默认分块图层 | 键盘隐藏 | 49.52 | 154.47 | 2 |
| 默认分块图层 | 重新挂载 | 43.23 | 146.24 | 2 |
| 虚拟历史 | 挂载 | 55.32 | 161.72 | 1 |
| 虚拟历史 | 键盘显示 | 108.48 | 247.86 | 2 |
| 虚拟历史 | 键盘隐藏 | 48.98 | 156.50 | 2 |
| 虚拟历史 | 重新挂载 | 45.29 | 148.44 | 1 |
| 键盘稳定虚拟历史 | 挂载 | 50.00 | 146.05 | 1 |
| 键盘稳定虚拟历史 | 键盘显示 | 101.33 | 234.80 | 1 |
| 键盘稳定虚拟历史 | 键盘隐藏 | 50.66 | 165.05 | 1 |
| 键盘稳定虚拟历史 | 重新挂载 | 38.02 | 145.08 | 1 |

首次 draw 指含完整回复的 frame 首次绘制回调，不保证此时行已对齐；可见检查含测试
semantics／idle 等待，是实际整行可见的观测上界，不是 GPU 呈现时间。
重组窗口从 SetText 之前开始，包含输入、提交和输出，不能称为纯输出重组数。
旧 `616010f` 没有重组计数且来自不同运行，不能补零或拼成新旧耗时 A/B。

[全部样本 CSV](3f33ee3-pipeline-samples.csv) 与 [完整页面原始证据](3f33ee3-pipeline.json)
保留每条输入、入队、写入、读取、解析、UI 收到、frame 发布及绘制时间。各阶段的中位数
不能相加代替整条链路的中位数。固定单请求及精确字节校验建立的关联不推广到任意后台输出。

模拟器专用 PRoot 补丁将 musl 旧式 fork 转为 clone(SIGCHLD)，解决 Android 返回 ENOSYS
导致的 stty 前缀失败；缺失的 string.h 声明补丁不改变运行策略。覆盖仅进入 opt-in
`terminaltest` 资产，正式 arm64 资产、系统安全策略和 PTY 模式不改。
来源、NDK、两项补丁和覆盖二进制哈希随 27 个来源哈希记录；详情见
[测试专用 PRoot](../proot-x86_64/README.md) 和 [采样协议](../PIPELINE.md)。

## 验证链与提交身份

| 验证 | 实际构建／测试 SHA | 结果与 run |
| --- | --- | --- |
| 正式 Release | `3f33ee3` | [37875610936](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37875610936) 成功 |
| 真实页面及传输 | `3f33ee3` | [37875611033](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37875611033)：15 探针、36 回显 |
| 组件冒烟／夹具 JVM | `3f33ee3` | [37875610945](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37875610945)：18 用例、25 JVM |
| 完整视口及两项工作量对照 | `e55e693` | [37921711714](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37921711714)：126 项 |
| Release JVM | `e2ddf0f` | [37922512552](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37922512552)：374 项 |
| 独立 APK 校验 | 校验器 `e55e693`，APK `3f33ee3` | [37921712290](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37921712290) 成功 |

`e55e6933ced4e75348bae44f98376426e4718c9b` 只补齐新测试的 `ViewportAnchor` 代际参数；
`e2ddf0f4b2cc5a9e2975a525a746d6798a7de2b4` 只更新两处仍要求旧变量写法的源码约束，
改为检查相同控制器、布尔投影及生命周期收集，不删除 IME／PTY 保护。
三个提交的 `app/src/main` Git tree 都是 `2c4a0b44276938e925ac56fbd209ad34806f4601`；
应用构建、版本依赖及 Compose 配置也相同。报告保留各自真实 SHA，不重标测试或 APK。
失败的先前运行不计为成功证据，汇总见 [验证来源 JSON](3f33ee3-verification.json)。

本地仅执行 37 项 CI 脚本测试、56 项基准脚本测试及静态检查；所有 Android／原生
构建和 Kotlin／仪器测试在 GitHub Actions 完成。

## Release APK 与后续边界

[Release ZIP（内含 app-release.apk）](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37875610936/artifacts/11592741772)
在归档时未过期，API 到期时间 `2026-10-23T02:52:34Z`。

- 包名 `me.rerere.rikkahub.dev.next.mod`，版本 `2.1.62`／`151`，仅 `arm64-v8a`，非 debuggable。
- APK 83,699,907 bytes；artifact ZIP 81,438,662 bytes。
- APK SHA-256：`c3fcd6a96de574c07a639a47ca61ece240de8a39308b1b0caabb22f75680ca7d`。
- 签名证书：`f136fff34c01d34edd2f625e77df5d408f6e09431b2e46e6d58cc5654a722265`。
- [独立校验记录](3f33ee3-apk-verification.json)。无需清数据或切换默认渲染方式。

本 SHA 的组件结果是 18 个冒烟用例，并非新的完整 V4 54 用例／162 测量；旧 `c9c082d`
完整矩阵仍独立保存。后续先做真实页面同设备旧／新受控对照，再按分段数据处理发布到绘制
的工作及冷挂载／恢复。手机端长期输出、30 分钟内存／GC、实际输入延迟尚待实机验证。
