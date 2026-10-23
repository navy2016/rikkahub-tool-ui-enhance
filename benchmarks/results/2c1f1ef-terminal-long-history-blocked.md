# 长历史 TUI 优化：组件通过，实际页面验收受阻

本轮未发布正式 Release。候选保留为未完整验收的源码：473 JVM、173视口和90份组件性能样本通过；真实页面两种新模式均在尺寸断言失败。首次验收及一次汇总修正已用完，不自动追加第三轮。

## 实现与身份

基线 `7218dd7e50577bc6fa94575aa476b2a436c71f61`，生产等同用户实机确认底部修复的 `bff4a51`。候选 `5eecb476370f22e6474f8f54e3bbf5ee5c898363`，测试接线修正 `2c1f1efa9660d0601ee1d6db65565694858b624d`；两者生产树均为 `71b1b14fde6b0bc3ec413b8024012060c404858b`。仅操作 `opt/terminal-verified-a79e7b6`。

新增显式、按命令保存的 RENDER 选项：

- `TUI_CHUNKED`／`tuiChunkedEager`：复用原归档历史分块，不增加独立绘制图层。
- `TUI_CHUNKED_LAYERS`／`tuiChunkedLayers`：复用分块和绘制图层。

仅可信、非空主屏 TUI 归档历史，pan启用且无SEL、mouseTracking、全网格或备用屏幕时允许分块。只改变5个生产文件的模式与门控接线；未改解析、行同步、输入、resize核心、字体、历史容量或唯一滚动执行器。默认仍为 `CHUNKED_LAYERS`，原默认TUI实际沿用逐行兼容绘制。RAW、SCREEN跟随、完整活动屏幕及实测高度修复保持；未虚拟化TUI、未缓存已销毁UI、未改KEYS。

## 验收结果

| 检查 | 原运行 | 结果 |
| --- | --- | --- |
| Release JVM | [39413045004](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39413045004) | 473用例通过，含6个新增门控／FIFO／接线用例 |
| 完整视口 | [39413066710](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39413066710) | 173用例通过；新模式31矩形、6组输出／重进、10回退 |
| 生产模式性能对照 | [39413082782](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39413082782) | 90样本、45卸载；12项固定判据全部通过 |
| 首轮旧源码负对照 | [39413076265](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39413076265) | 编译失败，原失败保留 |
| 首轮真实页面 | [39413103263](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39413103263) | 编译失败，原失败保留 |
| 修正旧源码负对照 | [39436610415](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39436610415) | 2用例／4预期裁切矩形通过 |
| 修正真实页面 | [39436622755](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39436622755) | 编译成功，两个新增页面用例失败 |

首轮负对照误纳入依赖 `followTarget` 新API的诊断类，首轮页面引用模块未配置的 `uiautomator`／`UiDevice`。汇总修正仅将负对照helper切到 `7218dd7` 的兼容快照，并用Android平台 `uiAutomation.injectInputEvent(event, true)`；底部／默认矩形断言字节完全一致，未加依赖。修正时140项Python报告／接线检查通过；无本地Android编译。

## 组件性能：保留所有样本

同一APK、进程、设备下，比较不变的默认TUI兼容核心和两个显式生产选项。1k／5k／10k历史，各3模式×冷挂载／3tick输出×1轮预热／4轮正式ABC-CBA反序，共18预热＋72正式。流式3tick每tick2行，视口240→140→240dp是模型IME，不是真实键盘或完整页面性能。

| 10k历史中位数（ms） | 默认TUI兼容 | TUI轻量分块 | TUI分块图层 |
| --- | ---: | ---: | ---: |
| 冷挂载完成 | 2131.000 | 2191.866 | 969.970 |
| 3tick输出完成 | 18198.567 | 1959.069 | 567.570 |
| 3tick组合至SideEffect跨度 | 16380.665 | 306.436 | 352.140 |

轻量分块在10k冷挂载四对样本均更慢，不能称为冷挂载提速；图层模式的组件结果更有利。5k／10k流式四对完成时间都改善，完成及commit中位数≤控制80%；其余完成中位数≤控制115%＋20ms。全部预声明判据通过，但这是带帧／轮询等待的组件完成时间与包含apply的跨度，不是纯CPU、手机FPS或一般提速百分比；没有独立旧APK／整页ABBA／30分钟压力证据。

定位依据来自独立非验收探针 `c7caf0a`／`39383512446` 的54样本、27卸载；5k额外采样的236次观察中，保留的常见栈有166次包含 `SlotWriter.moveGroup`。这是栈频率，不是CPU占比。更早24tick诊断 `39195471176` 的5k／10k超时、32原始样本仍保留，不重标通过。

## 实际页面失败与因果边界

两个新模式原始失败均为：

```text
java.lang.AssertionError: IME/reentry resized the PTY grid expected:<32> but was:<25>
at me.rerere.rikkahub.pipeline.TerminalTuiHistoryPageTest.exercise(TerminalTuiHistoryPageTest.kt:289)
```

| 原件记录 | TUI轻量分块 | TUI分块图层 |
| --- | ---: | ---: |
| 测试更早保存的 `physicalRows` | 32 | 32 |
| 无IME的initial矩形时行数 | 25 | 32 |
| imeOpen结束矩形时行数 | 25 | 25 |
| 输出重叠tick | 1→19 | 1→7 |

已取得4份完整可见的实际光标矩形、2份在输出DONE之前的新输出／新绘制重叠，历史均为10000行。实际行高56px，无IME视口1418px（目标网格25行），键盘视口385px；未出现这4份矩形的底部裁切。**这不是两项用例通过**：关闭键盘、真实页面重进、RAW输入回显、最终清理回执和逐阶段精确字节核对未完成。失败时 `disposed:0` 位于finally清理之前，不是已证明的泄漏。

文件 `app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalTuiHistoryPageTest.kt:179` 的前置条件不足：

```kotlin
compose.waitUntil(30_000) { ready(false) && !manager.hasPendingTerminalResize(id) }
val physicalRows = terminal.rows
// 随后才检查两次实际矩形；其签名不含PTY行数。
rectangle("initial", false)
```

`hasPendingTerminalResize()`仅表示已提交给coordinator的请求是否完成；它不能覆盖 `ProcessSessionPage.kt:1506–1535` 尚未派发的 `viewportRowResizeJob`／初始布局debounce。该resize区域与基线字节一致。原底部页面测试会检查 `terminal.rows == desiredRows`，新测试未做此检查。

轻量分块在键盘前已由保存的32变成25，证明该用例捕获了不可靠的初始尺寸基线；不能把全部失败说成IME触发resize。图层模式缺少完整请求时序，不能认定它只是夹具错误，也不能认定新渲染器导致生产回归。下一次需直接记录与检查目标尺寸收敛，而非删除行数断言、改成25常量或固定等待。

同次页面工作流此前的15传输探针、2服务用例、137字节缓冲读取、6原TUI协议用例及14原默认底部矩形通过；原件／API回执已核对。这些预检不代替两个新增模式完整验收，最终36回显套件未被执行。

## 证据与交付边界

同次失败artifact `12272853233`（85497B）经既有workflow `39901820499` 原样中转，ZIP SHA256 `3fb9fa358e53464eec513bbd92504ff47b65d09b71bb019e7c72783a85614220`。仅取相关日志／JSON，不下载APK或完整构建日志。`corrected-page-original/`完整9成员，原bottom／byte预检日志另外保留；其他预检原始日志补取不完整，报告仅据已有API／JSON核验其相应范围。

`verify_evidence.py`复算90样本与12判据、31新组件矩形、原负对照、全部compact摘要及源码身份，正确拒绝原失败页面；65份页面源码哈希与对应提交完全一致。证据包包含源码版本快照、原始成功／失败报告、哈希清单和离线验证入口。哈希证明本地一致性，不是第三方数字签名，也不证明未执行的场景通过。

候选未回滚，也未完整采用或正式发布。最后已成功构建／独立校验的生产来源仍是 `bff4a51`／Release `39094656675`／校验 `39095178074`；本轮未下载或交付APK。

## 下一步需要授权

建议只追加一次定向范围：补强新页面测试的初始目标网格与两次实际绘制收敛，记录resize请求／确认时序，再运行两项新模式真实页面测试。保留原尺寸断言、原失败、既有性能样本及默认生产语义；不重跑性能筛选快样本。用户明确授权前不修改测试、不派发第三轮；如证据指向生产问题，先说明原因和修复范围。全部要求通过后才进入正式Release与独立校验。
