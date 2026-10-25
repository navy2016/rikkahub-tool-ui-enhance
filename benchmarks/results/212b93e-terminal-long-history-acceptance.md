# 长历史 TUI 分块优化：云端验收与 Release 完成

## 结论与使用

本轮生产优化已完成云端门禁、正式 Release 和独立 APK 校验。新模式需要从终端状态栏 **RENDER → TUI分块图层** 显式选择；默认渲染不变。可另选 **TUI轻量分块**，比较绘制图层的收益与内存开销。

两项均只对可信主屏已归档历史分块，完整活动屏幕、RAW输入、SCREEN跟随、实测行高、KEYS和原操作保持。SEL、MOUSE、全网格／备用屏幕等不符合条件时自动沿用兼容绘制；不是TUI虚拟历史或备用屏幕录像。用户此前确认的Python空白光标／Pi底部遮挡修复保留。

**本轮长历史性能仍待手机实测**。组件对照支持图层选项，但不承诺手机FPS或所有场景提速。未下载、未交付APK附件。

## 精确来源

- 分支：`opt/terminal-verified-a79e7b6`。
- 基线：`7218dd7e50577bc6fa94575aa476b2a436c71f61`，生产等同 `bff4a51`。
- 生产候选：`5eecb476370f22e6474f8f54e3bbf5ee5c898363`。
- 最终测试／Release：`212b93e04980b1087118007bd59da010a15435d8`。
- 生产树：`71b1b14fde6b0bc3ec413b8024012060c404858b`；后续两个测试修正均未改变此树。

生产差异只有 `TerminalRenderMode.kt`、`TerminalDisplayPolicy.kt`、`ProcessSessionPage.kt`、`TerminalRenderControls.kt`、`TerminalTranscriptViewport.kt` 的新模式和门控接线。解析、行同步、resize核心、字体、容量、输入与唯一滚动执行器未改。没有重新采用已否定的单行重组作用域实验。

## 门禁与原失败

| 覆盖 | 原运行 | 结果 |
| --- | --- | --- |
| Release JVM | [39413045004](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39413045004) | 473用例通过 |
| 完整视口 | [39413066710](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39413066710) | 173用例通过；新组件31矩形／6输出重进／10回退 |
| 同机组件性能 | [39413082782](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39413082782) | 90样本，12项预声明判据通过；未重采样 |
| 旧源码负对照修正 | [39436610415](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39436610415) | 2用例／4预期裁切矩形通过 |
| 显式追加页面V2 | [40062879915](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/40062879915) | 2新页面；10矩形／10内核查询／6持续输出重叠／6精确字节窗口／2输入回显／2清理 |
| 原页面回显／收敛 | 同一run `40062879915` | 36回显样本／12收敛回执通过 |
| 正式 Release | [40066315546](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/40066315546) | 成功 |
| 独立 APK 校验与比较 | [40068292208](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/40068292208) | 成功，与Release `39094656675`／`bff4a51`比较 |

此前基线诊断 `39195471176`、首轮编译失败 `39413076265`／`39413103263`、V1页面失败 `39436622755` 均保留原结论。旧阻塞报告／checkpoint没有改成成功。用户在阻塞报告后明确要求继续优化，本次只追加一个定向页面收敛／尺寸来源验收；未自动再追加修正轮，未重跑性能筛选快样本。

## 性能范围

90样本来自同APK／进程／设备的1k／5k／10k历史，默认TUI兼容与两个显式模式；冷挂载及3tick×2行输出，1轮预热／4轮正式ABC-CBA反序，18预热＋72正式。240→140→240dp是模型IME；不是完整手机／原APK整页ABBA或30分钟压力测试。

| 10k中位数（ms） | 默认TUI兼容 | TUI轻量分块 | TUI分块图层 |
| --- | ---: | ---: | ---: |
| 冷挂载完成 | 2131.000 | 2191.866 | 969.970 |
| 3tick输出完成 | 18198.567 | 1959.069 | 567.570 |
| 3tick组合至SideEffect跨度 | 16380.665 | 306.436 | 352.140 |

全部原始快慢样本保留。轻量分块在10k冷挂载四对均更慢，不称冷挂载提速；图层的组件结果更有利。时间包含帧／轮询等待与apply工作，不是纯CPU，也不能换算为手机整体提升率。采用图层作为优先实测选项，默认不替换。

## 页面V2解决的验收阻塞

V1原失败为 `IME/reentry resized the PTY grid expected:<32> but was:<25>`，位于当时 `TerminalTuiHistoryPageTest.kt:289`。它在初始目标网格收敛前保存32行，只检查已提交resize请求，未覆盖UI尚未派发的debounce。

V2保留原尺寸／光标／历史／字节断言，先等待实际视口除以cell高的目标行数、会话回执、model／snapshot与两次真实绘制一致，再锁定基线。每种模式5次 `env -u LINES -u COLUMNS stty size` 查询独立验证内核；只清除查询子进程的环境变量，不改会话继承的24×80环境。查询输出在原160tick字节窗口之外。

本次两种模式都记录：无键盘 `request1:32行 → request2:25行` 发生在initial收敛中；锁定25行后，键盘打开／关闭、持续输出、真实页面detach／重进和输入阶段均保持 `request2:25×80`。10矩形完整可见，6窗口原native读／解析字节完全一致；重进发生在有限输出结束前、页面脱离时至少推进3tick，最后session／capture清理回执完成。

这些是本次合成peer的真实页面／原生PTY／系统IME证据，不是账号真实Pi或所有设备的结论。尺寸观察是有界轮询／确认记录，不是完整请求创建trace；不据新结果补造旧run的事件或抹去旧失败。

## Release 身份与差异

- 版本／包名：`2.1.62`／`151`，`me.rerere.rikkahub.dev.next.mod`。
- APK：`app-release.apk`，83733567B；非debuggable，仅 `arm64-v8a`，签名和ZIP校验通过。
- APK SHA256：`7c5c3904ec355eb190b88ebbc4a2f2b260a76748661094658428b4f40a6700a1`。
- 与原包签名、manifest身份、字体内容多重集和Android `.version` 依赖元数据相同；native包内内容相同。
- 差异：源码修订metadata 1项、DEX 2项、`assets/dexopt/baseline.prof`／`baseline.profm` 2项、Web生成产物17项。不是“整个APK字节不变”。

原Web任务 `app/build.gradle.kts` 在缺少node_modules时调用 `npm install`，不会使用 `web-ui/bun.lock`；相同源码／锁文件不能证明本轮实际npm解析或Web产物可重复构建。该既有构建链本轮未改，未据Android版本元数据宣称所有Web依赖相同。Web可重复性单列为边界，不将其当成终端提速证据。

## 证据与实机复测

V2原报告SHA256 `f0d3928edbe65e0aeefbbbf917ef5f663f7709e1cfa74dd38c9bf9c2608828ea`，36回显报告 `f9f6e2bd9e4e402785cf7ed61c9cae3923677b7bc417159e387ce6124889d056`。完整compact摘要取回并复算，66份页面源码哈希与精确提交一致；独立APK比较原件也已核对。没有为获取证据重跑测试，没有本地Android／Kotlin／native编译或下载APK。

证据包保留旧阻塞归档、新原始成功JSON／回执、源码版本、独立校验、哈希及断网复算入口；本地哈希一致性不是第三方数字签名。后续实机建议同一长历史TUI会话从RENDER切到TUI分块图层，比较重进、持续输出中键盘开关、回读锁定及COPY／SEL／MOUSE／ALT回退。不得把前一轮已修底部的实机反馈扩写为本轮长历史性能已经实机通过。
