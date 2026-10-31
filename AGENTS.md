# Repository Guidelines

本文档面向贡献者，概述本仓库的模块结构、开发流程与提交规范，便于快速上手并保持一致的协作质量。

## Active terminal optimization branch

用户要求从本地已验证的 `a79e7b693d42f22a086959e9fac0525b88515663` 建立独立优化线。
当前使用 `opt/terminal-verified-a79e7b6`；推送和 workflow dispatch 均须明确指定此分支。
原远端 `fix/terminal-viewport-semantic-reducer` 已有其他新提交，不自动 pull、merge、rebase 或覆盖它。
基础应用代码为 `2b0f259`，`a79e7b6` 仅增加文档，已通过 V3 六场景 54 用例／162 次测量。
用户随后实机复核：`2b0f259` 也有全新默认会话底行半遮挡／松手回弹；切过虚拟历史后，
该会话的 eager 实测几何路径会掩盖此缺陷。旧 V3 使用同一 nominal cell 公式判定默认完成，
不能作为此缺陷不存在的证据。当前修复要求普通 eager 从首次布局采用实测行高，保持默认
CHUNKED_LAYERS 和 TUI／全网格策略不变；基准协议升为 V4，不重标旧测量结果。
`c9c082d` 完成按不可变历史块复用真实行高：行的标量 token 只可撤销对应当前块，旧 token
不能撤销替换块；不完整块／几何不能发布，允许已验证完整块在重试中保留。新旧 V4 各
54 用例／162 测量通过；新版本 359 JVM、116 视口测试通过。证据见
`benchmarks/results/c9c082d-eager-height-blocks.md`。不可将独立 Runner 结果解释为受控提速。

`3f33ee3` 收窄面板订阅为生命周期感知的 AUTO/LOCK 冷投影，保留完整控制器、手势、滚动
和持久化路径。同控制器 54 次锚点／手势更新的额外重组为旧 54／新 0；三次布尔转换、
控制器替换和生命周期恢复通过。生产代码对应 374 JVM、126 视口、18 组件冒烟及真实页面
15 传输探针／36 回显全部通过。JVM 测试 SHA 为 `e2ddf0f`，视口测试为 `e55e693`，两者
只修测试，与 Release `3f33ee3` 的生产树完全相同。证据及 APK 见
`benchmarks/results/3f33ee3-terminal-acceptance.md`。不得将订阅工作量结果称为手机整体加速，
也不得将本轮冒烟称为完整 V4。测试用 x86_64 PRoot 覆盖不进入正式 arm64 Release。

最新生产 `ac47787` 仅修改 `TerminalViewportBinding.kt`：组合只观察最新指标 supplier 的
TUI 布尔投影，并记忆未变的测量 scope／绑定对象。原 snapshotFlow 仍观察全部实时指标，
真实行高、首布局门控和唯一滚动执行器不变。24 次真实布局变化的额外面板重组旧 24／新 0，
24 次底行检查通过；374 JVM、129 视口、完整 V4 54／162、15 传输探针／36 回显及 Release
校验全部通过。证据见 `benchmarks/results/ac47787-binding-projection.md`。下一重点是默认
长历史冷组合／测量和虚拟历史冷测宽，不得用旧 Runner 数值计算提升率。上一轮订阅同设备
AB／BA 288 样本已归档在 `benchmarks/results/3b5c3e0-paired-subscription.md`，耗时有升有降。

## Build, Test, and Development Commands

当前用户已授权 B1—B6 一次总批次：集中完成生产代码、测试、文档及必要 CI 后统一验收，
不再逐模块请示。基线 `fe43ec19e6e565acda8b8f3dbc6c530938609b32`；范围及验证约束见
`benchmarks/TERMINAL_BUNDLE.md`。最多两轮验证，禁止本地 Android/Kotlin/native 编译和 Debug 构建。
正式 Release 在全部云端门禁通过之后构建并独立校验；缺实机时必须标明实机待验。
批次提交使用 `[skip ci]`，避免推送提前启动 Release 和重复验证；Actions 显式指定本分支和核对 SHA。
本段仅记录授权和协议，不表示本批已通过测试。

用户已在 `2723a4c` 总批次报告之后授权追加一轮，仅诊断修复稳定虚拟历史初始模式验收失败
（原 run `38838668624`）并重验。允许先做显式非验收诊断，再定向修复／回归及该模式 1800s 压力。
保留原失败，不以固定等待／删除模式断言取代真正收敛，不扩展优化范围；通过后可继续原 Release 交付。

上述总批次及追加验收已完成：Release `6e81739` / run `38914679438`，独立校验 `38916749973`。
用户随后实测 Pi 问题由新版 Pi fullscreen 配置引起，`--tui-mode regular --resume` 已恢复完整历史滚动；
不据此修改终端协议／鼠标／历史渲染，也不把排查中的候选提交记作已证明的回归原因。
用户现已要求回归主线继续优化。本轮基线 `6e81739d8790c524ae25b010dcd6e21ee0ee0a4d`，
仅优化 256KiB 原始会话输出缓冲为有界环形存储，范围／证据协议见 `benchmarks/TERMINAL_BYTE_BUFFER.md`。
容量、读取偏移和逐请求 UTF-8 截断语义不变，parsed scrollback、渲染／滚动／状态栏／KEYS 不变。
云端先运行 Release JVM 差分／并发／分配量对照，再用 Release-derived PTY/页面验证，门禁通过后
正式 Release 构建及独立校验。仍禁止本地 Android/Kotlin/native 编译，用户不需要 APK 附件。

字节缓冲轮已完成：`b7a93c9`，39 JVM、15 传输／2 服务／137 次读取／36 页面回显通过；
Release `39005664006`、独立校验 `39006232031` 成功。随后单测量行作用域实验
`c4e3ad5`／`98f27e2` 完成 98 JVM、50 视口、36 页面回显／12 收敛回执，功能门禁通过；
同机 10k 冷行树挂载旧／新中位数 1056.853／1075.879ms，四对样本候选均更慢，因此未采用。
生产代码、应用测试及视口夹具已完整恢复 `b7a93c9b5734ce1386d919d3be6085ef725124bb`，
没有新 Release。实验源码／断言保存在原提交和证据归档，原静态检查失败不得重标成功。
结案见 `benchmarks/results/98f27e2-row-scope-rejected.md`；`benchmarks/ROW_SCOPE.md` 仅为历史协议。
不重测筛选更快样本，不将作用域数减少解释为手机提速。后续应重新定位冷组合／布局热点。

用户已授权四项作为一次总批次推进，基线 `7f60e641f3840c1a3e025202496a381954266c8e`。
范围是协议／IME／鼠标、resize／快照／输入顺序、解析降分配、RAW 与布局解耦及可选光标绘制；
协议见 `benchmarks/TERMINAL_TUI_BUNDLE.md`。集中实现后统一云端验收，最多首次及一次汇总修正，
不逐模块请示。保留原默认和状态栏／KEYS 操作；新增显示路径仅从现有 RENDER 显式选择。
不包括完整 Kitty 协议或全屏历史录像。此段记录授权，不代表已通过测试或允许混算旧证据。

四项总批次已完成：功能提交 `9179958`、解析降分配 `d86ee64`，最终 `a914fca` 仅修正测试的
BusyBox `stty size` 环境覆盖查询及回执。`d86ee64`／`a914fca` 生产树均为
`639ee4f3286cb647a0b09cfa61a3dc1659586e1d`。457 JVM、162 视口通过；第二轮 PTY／页面
`39033990454` 完成15传输、2服务、137读取、6TUI、36回显／12收敛。原 `39032047858`
的尺寸测试失败保留，不据本地诊断补造原 Android 子进程输出。页面 Check API 缺摘要，
已取回同一次运行原件并复算。Release `39036798535`、独立校验 `39037487624` 成功，
仅 arm64，字体／依赖／native／签名保持；本轮未下载 APK，实机待验。报告见
`benchmarks/results/a914fca-terminal-tui-bundle.md`，身份清单见对应 acceptance.json。
默认仍为 CHUNKED_LAYERS。新增 RENDER `REGULAR_HISTORY` 仅显式直接 Pi regular 命令，
`CURSOR_OVERLAY` 仅可选竖线／下划线叠加；RAW 输入、原 KEYS、兼容回退和单滚动执行器保留。
同 JVM 固定三负载解析分配减少81%—97%，不是手机整体提升率；本轮没有新完整 V6、
整页 AB／BA 或30分钟压力。两轮验证已用完，不自动启动第三轮。后续勿重做已否定的行作用域实验。

用户随后实测：Python／从 sh 启动 Python 的空白光标行被遮挡约半行；Pi 遮挡随历史增加。
已明确授权独立的底部可见性专项修复及云端验证，基线 `8ed4f06e10039233511c4ef43bb39ed717ef6ef6`
（生产等同 a914fca）。范围仅为可见光标行的跟随边界、TUI 实测历史／屏幕高度、相关 IME／
视口几何和回归证据，见 `benchmarks/TERMINAL_BOTTOM_VISIBILITY.md`。不改默认渲染／KEYS／
输入协议、不以固定留白或固定行高掩盖遮挡。旧门禁结论保留为其原覆盖范围；新验收必须直接
检查光标行与TUI底行的实际屏幕边界，包含原版负对照。此授权是新专项，不是重标旧批次成功。

底部可见性专项已完成：生产 `bff4a51f762d33ac9164b4139f0a64d34a902283`，树
`c1463f057fda3bbc693f7e8164065da4baebcdb1`。210 JVM、168视口、24候选矩形和实际页面
14矩形／6TUI／36回显／12收敛通过；旧源码负对照2用例复现4份裁切矩形。旧版空白光标
27px行被裁16px，候选0px；TUI变高行夹具128／512／1024历史的旧偏差907／3212／6283px，
候选均0px。这是云端合成夹具，不是手机或真实Pi测量，不作性能提升率。Release `39094656675`
与独立校验 `39095178074` 成功，版本仍2.1.62／151，须按bff4a51构建来源区分旧包。
首轮即通过，未使用修正重验。没有下载APK，原设备复测仍待用户确认。报告及验收身份见
`benchmarks/results/bff4a51-terminal-bottom-visibility.md` 与对应 `-acceptance.json`。
普通／虚拟历史跟随含可见光标；全部eager含TUI必须有完整实测高度，不能恢复成
historyCount乘标称高度。默认图层、8dp尾部、KEYS、输入协议及唯一滚动执行器保持。
页面API后半段摘要缺失已由既有artifact中转补齐并核对原日志，不据此重跑测试。

用户已实机确认 bff4a51 的 Python 空白光标及 Pi 累积遮挡均已修复。该反馈仅覆盖这两项，
不扩写为性能／输入协议全面实机通过。随后授权长历史重进性能专项，并补充：长历史TUI的
重进、IME交互偏慢，持续生成消息时明显卡顿。新基线 `7218dd7e50577bc6fa94575aa476b2a436c71f61`
（生产等同bff4a51）。先用1k／5k／10k分阶段诊断确定工作量，再针对热点优化；协议见
`benchmarks/TERMINAL_LONG_HISTORY.md`。保留实测行高／光标完整可见／历史容量／默认渲染／
KEYS和唯一滚动执行器。不重做已否定的单行重组作用域实验，不把组件诊断当成实机FPS。

长历史基线诊断39195471176有2/3用例超时，32原始样本保留，不重标通过。独立三模式诊断
c7caf0a／39383512446完成54样本和27卸载；调用栈指向SlotWriter.moveGroup，分块减轻
长历史头删后的大组移动。证据已中转取回并复算。当前候选仅新增显式RENDER选项
TUI_CHUNKED／TUI_CHUNKED_LAYERS，仅可信主屏归档历史、pan且无SEL/MOUSE/全网格/ALT
可启用。默认和原模式资格、RAW、SCREEN跟随、真实行高、解析/同步/滚动核心均不变。
候选验收协议见TERMINAL_LONG_HISTORY.md：173视口、新6JVM、实际页面持续输出/真实键盘/
重进和90同机组件样本，不据诊断声明生产或实机通过。候选5eecb47／生产树
71b1b14fde6b0bc3ec413b8024012060c404858b首轮473JVM、173视口和90组件样本已通过；
旧版负对照39413076265及真实页面39413103263在测试包编译失败，原失败保留。汇总修正
2c1f1ef仅旧版测试快照与平台输入注入，不改生产／断言／性能阈值；负对照39436610415
已通过2用例／4预期裁切矩形，真实页面39436622755编译后两项均失败：
"IME/reentry resized the PTY grid expected:<32> but was:<25>"，位置TerminalTuiHistoryPageTest.kt:289。
轻量分块initial无IME矩形已25行，而保存基线32；图层initial32、imeOpen25，其变化时序
仍未证明。新测试缺少desiredRows收敛且提前保存physicalRows，不据此重标通过或认定生产
IME回归。仅4部分矩形／2imeOpen输出重叠，重进／输入／清理未完成。两轮预算已用完，
仅归档，禁止自动第三轮、生产修复／回滚或Release；须用户明确追加定向授权。报告见
benchmarks/results/2c1f1ef-terminal-long-history-blocked.md，身份清单为同目录
2c1f1ef-terminal-long-history-checkpoint.json。

用户在该阻塞报告后明确要求“继续推进优化”，并继续确认推进。本次仅追加一次定向页面
收敛／resize来源验收：以dd06a6b为文档基线、生产树仍71b1b14fde6b0bc3ec413b8024012060c404858b。
允许修正新页面测试的初始网格收敛、查询真实内核尺寸、报告与必要CI接线；原断言／160tick
负载／默认／KEYS／输入／生产核心不改。新增V2证据不能重标V1失败。运行两项新页面及原
此前未到达的36回显页面套件；不重跑已通过性能／组件门禁，不自动再追加修正轮。只有
追加页面验收通过才能继续既定Release；如指向生产问题，先说明因果和具体修复范围。

该显式追加轮已完成：212b93e／40062879915通过2页面、10实际矩形／10内核查询／6持续
输出重叠／6精确字节窗口／2输入与2清理、36回显／12收敛；两模式无IME32→25收敛后
request2保持25×80。仅测试／报告／CI修正，生产树仍71b1b14fde6b0bc3ec413b8024012060c404858b。
Release40066315546、独立校验40068292208成功，arm64／2.1.62／151，签名／native／字体／
Android版本元数据同bff4a51。DEX／baseline profile及Web生成产物不同；原npm install构建
不读取bun.lock，不能称Web可重复或全部依赖相同。本轮未下载APK，性能实机待验。报告及
验收清单见benchmarks/results/212b93e-terminal-long-history-acceptance.md及对应acceptance.json。
新RENDER选项只显式选择，默认及KEYS不变；推荐TUI分块图层优先实测。不再自动验收
或扩展范围，原2c1f1ef阻塞报告／原失败保持；不重做被否定的行作用域方案。

用户最新实机反馈：长历史TUI分块图层收益有限／无明显提升，不能继续称其已证明手机提速。
已明确要求独立新增RENDER“全量虚拟化”，覆盖普通／TUI／IME／SEL／MOUSE／全网格／ALT，
原有选项与默认不变。此为新专项授权，基线09d863449e2e16cf8fd1eac95d5e11264934156b
（生产等同212b93e）。新选项可使用独立可见行视口／跨离屏选择模型，保留完整历史、真实文字
几何、原COPY全文／KEYS／输入／鼠标／清屏／备用屏幕语义及唯一滚动执行器。旧虚拟模式的
兼容门控保持，不通过删除旧门控冒充全场景完成。目标为体感提升，但不得保证所有设备零卡顿。
协议见benchmarks/TERMINAL_FULL_VIRTUAL.md；先完整实现／一次集中验收及必要一次汇总修正，
Release必须在新模式、旧模式回归和同机证据通过后构建，实机仍待用户复测。不本地编译／
升级依赖／下载APK／启子代理，保留既有artifacts/与历史失败。

全量虚拟化首轮c07100d／生产树c4da501312976a99776dc99fcd2d7db78bc1256a：483JVM
40272600220、真实页面／PTY40272615096通过；视口40272608906原173及新增5通过，新增
两项选择用例失败。原菜单line157 Required value was null；拖动line192
"Drag never recycled the start row"。首次30性能／30卸载及45场景原件已取回，六项性能
判据均通过，但不是手机体感。正在使用原协议内唯一汇总修正：只修测试驱动的Popup窗口／
屏幕坐标及Compose帧推进，生产源码不改，原性能不重采；fullVirtualSelection仅两项选择。
不得将原失败抹去或把前后两个run说成同次全量通过。此定向轮仍失败时不自动第三轮／Release。

上述唯一汇总修正已完成并仍受阻：测试提交fa16a20c9fa2c1387d5c7519bcb2826dae3bacfd，
run40620489127两项均失败，生产树仍c4da501312976a99776dc99fcd2d7db78bc1256a。
单词复制在TerminalFullVirtualInstrumentedTest.kt:222等待精确剪贴板内容5000ms超时；
已找到并点击系统Copy菜单，但实际剪贴板值／选区端点未记录，SelectAll尚未到达。
拖动在同文件:263仍为"Drag never recycled the start row"；原件确认屏幕坐标47,88→213,339，
220次MOVE／222事件／222帧，不能再将失败归结为已证明的单一坐标或测试时钟问题。
日志未记录每次handler命中／边缘请求／滚动消费，未离屏也不等于完全没有滚动。
两轮预算已用完，无第三轮、生产修复／回滚或Release；须明确追加选择链路诊断／修复授权。
两轮JUnit、局部日志、首轮30性能和页面原件已复核；页面通过的SEL切换及原COPY全文不
覆盖系统选区复制。首轮45布局记录含36实际光标矩形；新页面18记录含16光标矩形、12内核
查询及8精确字节窗口。性能实机待验。结案见TERMINAL_FULL_VIRTUAL.md末节，本地证据目录
/workspace/terminal-review/full-virtual/；最新正式应用仍212b93e/run40066315546。

用户在上述结案后明确回答“好的，继续”，追加授权仅诊断／修复SEL单词复制和选择柄跨屏
拖动两条链路及相关回归；基线62d00ca2cda005b87d1ca92fe505bd0c14f5ce20。允许一次显式
非验收诊断（新旧选择路径对照、有界标量／哈希探针），据实作最小生产修复，再集中验证
两项原断言及受影响IME／TUI／MOUSE／ALT／切换。原失败保留，不重采性能来筛好成绩；
不得改变预期单词、删除离屏复制／回收断言、SEL转全历史eager或增加滚动执行器。协议见
benchmarks/TERMINAL_SELECTION_REPAIR.md。最终全功能门禁满足后才续接原Release／独立校验，
不本地编译／Debug／升级依赖／下载APK／启子代理。此授权不重标旧两轮，也不允许无限验收。

选择追加诊断ae71d82/run41025419455已完成4观察case，原件证明静止长按触发边缘滚动、
改变选区；拖动输入和唯一执行器实际均工作。仅drag摘要超限使工作流失败，原JSON已中转
取回，不重跑诊断。候选347d17a修复静止／方向意图及Popup实际宽度；页面41028517457通过，
旧173视口＋4新几何／切换＋3新选择回归通过。487JVM测试执行无失败但报告仍预期10而非14，
保留41028489789的报告失败。原全选推进后暴露Binder2525220B样式膨胀；原拖动line263仍失败。
正在用本追加专项唯一汇总修正：同样式跨行复制合并（不丢文本／注解）、菜单避让实测柄高度、
JVM计数17／总490并增源码清单测试；全组原断言不改，原性能不重采。结果未出前不称验收通过。

追加专项已结束仍部分阻塞：最终生产9b718019bc582026d7f9139bfc507f399260e733，main树
3ccc04c2f05e4506b22ed2e897f6ee887785447c。490JVM41033789881、实际页面／PTY41033824934
通过；视口41033807694原173＋新增正确性7通过（180/182），原跨屏拖动line263仍失败，
新增stationary-bottom检查明确发现Copy菜单覆盖实际选择柄中心。原单词＋10024行全选复制
已通过，319650B/SHA2565c4acf6976bacf0d923251c00ec27932e4c04779966f2d1760f94b9a2c719e3f；
Binder样式膨胀已修复。底部菜单覆盖和顶部原drag失败不是同一现场，不能直接认定同因。
本授权一次诊断＋候选＋唯一修正已用完，无后续自动验收／生产变更／Release。仅归档与
交付，若继续须明确授权剩余菜单定位／触摸分发问题及相关回归。原性能不重采，不据
c07100d数据宣称9b71801同量提升；实机未验。完整证据在/workspace/terminal-review/selection-repair/。

最新用户明确授权：“后续都追加授权，按你的建议来执行”。自180c2c461ca6df7f0a34865027769eb1df8d27cf
起，在当前终端／全量虚拟化剩余问题范围内持续授权证据驱动诊断、最小修复、必要回归及最终
Release／独立校验，不再逐轮索取授权；此前逐次配额已结束的结论仅作为历史记录，不阻断本
次持续授权。不得随机重跑／挑选性能成绩／修改旧预期断言、截断历史、改变默认和旧KEYS输入
语义、增加垂直滚动所有者或本地编译。保留全部失败，不因授权持续就宣称功能或实机已通过。
先分别追踪顶部原drag与底部native菜单覆盖，再据证据修复。全部门禁通过才构建正式Release，
只交报告／证据不交APK。必要的范围扩展或对真实用户数据／外部系统的高风险操作仍先确认。

最终已通过并正式构建：生产55db111129c662067345aa99540b506de305da37，main树
8caf50d5c40e228f1d5bcc09d3db505757568b68。492Release JVM41043335339、182/182视口
41043354068（旧173+针对性9）、实际页面/PTY41043363910全部成功。原220MOVE/222事件/222帧
未改，拖动500→545行、首可见538，1414B逐字复制通过；10024行全选319650B与13安全菜单
检查全部通过。诊断6d365de/run41041104334分别证明顶部状态栏抢走DOWN、底部ComposeView
裁剪菜单矩形。修复只在VirtualSelectionUi：触摸框避系统区域、实际pointer与逻辑端点分离、
原生TYPE_FLOATING菜单挂完整根窗口并避让实测柄矩形；没有第二滚动执行器或全历史eager。
Release41045773895、独立校验41047320055成功，arm64/non-debuggable，2.1.62/151，SHA256
5fdaceacfbfaff5d378f9b2ac23070f009c6bf347be42d1561fb53e6080c6f4c。对比212b93e签名/manifest身份/
字体/native一致，版本元数据无依赖变化；Web生成物11项不同，不声称完全可重复。旧c07100d
30性能样本未重采，不代表新树或手机FPS。手机体感仍待验。所有历史失败保留；两次诊断
中转权限失败仅为取证，不是诊断重跑，最终relay41049879739成功原件已取回。不要再派发测试
或Release。证据工作目录/workspace/terminal-review/native-selection/，final-capsule/由make_final.py
生成，verify_final.py离线复核；交付名terminal-full-virtual-55db111-{report.md,acceptance.json,evidence.tar.gz}。

用户随后询问后续优化方案并回答“继续”：新P0性能专项获授权，不是继续修复已结案问题。
冻结55db111/main8caf50d5c40e228f1d5bcc09d3db505757568b68为新基线。先仅加测试/CI报告，
真实页面全虚拟ordinary/TUI×1k/10k，每case六工作负载×(2预热+20正式)，共528保留样本，
含实际FrameMetrics与Perfetto；不得用旧c07100d或旧路径36回显冒充新全虚拟数据。
此新基线不宣称优化收益/P99/实机FPS；报告包含所有失败、丢帧回调和探针边界。
先据数据判断P1光标/局部更新是否值得改；不提前改生产或重新构建正式Release。
协议benchmarks/TERMINAL_FULL_VIRTUAL_LATENCY.md，工作流terminal-pipeline-tests.yml/fullVirtualLatency。

P0v2已完成：测试源码beaf5ab752a671600a7d039e9224ae0109853987/run41064244386通过；生产仍
55db111/main8caf50d5c40e228f1d5bcc09d3db505757568b68。528样本/24无输出对照/2888FrameMetrics
全部保留，源/原日志/摘要已复核。旧396样本所属41061360774运行因初始32→25网格与重复注销
监听器失败；另41058225696编译失败、41059663096采样前Perfetto失败均保留。
关键校准：无输出GetTextLayoutResult会换wrapper但Paragraph全复用；光标/单行只换1Paragraph，
不要误报全屏重新排版。下一步优先分解发布/渲染队列等待，不盲改脏行/解析器；没有生产优化。
Perfetto启动有PID但结束No such process，采集器未取回文件，故无SurfaceFlinger呈现证据；不要
宣称设备不支持。修通轨迹通路应用独立短预检，不为了取轨迹重采528样本。内存含探针，冷入
数据含seed/700ms稳定门禁，SwiftShader指标不代表手机GPU。工作目录/workspace/terminal-review/full-virtual-latency/。

用户新增实机反馈：“55db111版APK运行时整体有一点点缓慢的感觉”，并要求继续。记录为有效
定性反馈；未指定渲染模式/页面/设备/对比版本，不能归因于全虚拟或用模拟器成绩否定它。
默认仍CHUNKED_LAYERS。下一步tracePreflight只在一次性Android14的Settings窗口测试3种固定
2秒采集通路，完整保留启动错误并无条件尝试取回文件，不改生产/构建APK/重采528基线。
协议benchmarks/TERMINAL_TRACE_PREFLIGHT.md，工作目录/workspace/terminal-review/trace-preflight/。

用户又明确对照：“感觉55db111版本是在212b93e版本上加了一个轻微减速器”。后续应按
212b93e04980b1087118007bd59da010a15435d8→55db111的实机性能回退排查，不仅做新模式优化。
先审两版18个终端生产文件差异与共用路径；解析器/进程管理/native/依赖源码未变不等于
排除了共享UI、编译产物或运行环境因素。未指定模式/具体页面，不能虚构量化回退幅度。

Trace预检d166bfc/run41070952490已通过：3种固定2秒通路均取得Settings的SurfaceFrame，
foreground-file24、foreground-buffer38、background-wait37完整帧。后台PID已结束也能取回
4745351B轨迹，证明旧采集器不能用kill失败跳过pull；不等于取得了旧P0的应用轨迹。
接下来versionRegression固定212b93e/55db111、同一212驱动APK和同次构建环境，普通1k历史
三个共有模式、4组AB/BA，288测量+72预热。非原正式APK二进制/手机/全局UI对比，不重采528；
成绩变慢也如实保留。协议benchmarks/TERMINAL_VERSION_REGRESSION.md，生产仍55db111。

用户进一步确认：略慢涉及聊天/设置等所有交互页；两版终端都默认渲染，IME开/关也可感知。
因此优先考虑全局UI/RenderThread及正式APK/ART安装优化状态；共有终端源码A/B仍保留但不能
排除原正式包全局问题。新增只读正式APK profile与DEX一致性审计，固定已验证的两包SHA，
仅云端读取原件，不安装/重建/清数据；profile文件改变不等于错误，匹配也不证明手机已AOT。

跨版本A/B3ab0917/run41071712739成功：288测量+72预热+30transport，默认IMEvisible配对
差值中位+13.197ms(3/4变慢)，imeHidden+2.326ms；这是键盘状态下回显，不是IME动画计时。
波动/负值全部保留，不能据此证明全局同幅慢。正式profile审计7743a18/run41075540623通过，
两包CRC/method/type均匹配；41075099086初版将APK位置CRC误当DEXheaderAdler32的脚本失败
保留。全局问题根因仍未确认，生产仍55db111、无新Release。只读手机采集器已提供但未在
用户设备运行，不清数据/安装/强制编译/改动画。下一步应取受影响手机ART与页面帧状态，
而不是盲改解析器/虚拟化；现有两包版本号同为2.1.62/151，必须按APK SHA辨认。

最新用户反馈：“使用一段时间后，好像没有之前那种缓慢感觉了！”并要求继续终端优化。
记为实机感知改善，不认定ART/预热根因已证实；暂停持续全局回退修复，不再要求手机采集。
下一项为全虚拟BAR/UNDERLINE光标的隔离对照：仅testFixture默认false适配现有光标叠加，
生产仍55db111/main8caf50d5c40e228f1d5bcc09d3db505757568b68。40像素case+40AB/BA窗口，
直接验证Paragraph/文字/宽度工作量，不把包装对象变化算重排；旧528/396/30样本不重采。
协议benchmarks/TERMINAL_FULL_VIRTUAL_CURSOR_EXPERIMENT.md；cursorExperiment工作流入口。

薄光标隔离实验1e5f20a/run41085513057完成：40像素case/40窗口通过，24moves工作量48→0，
但16正式配对全部更慢，原2.80s→叠加3.27–3.31s。追加定点诊断c74286e/run41087209082仅
16publication、手动测试时钟，不重采40窗口；两组均1帧，发布到绘制中位23.506→41.197ms。
决定不接入生产，不能靠工作计数减少宣称提速。生产仍55db111/main8caf50d5c40e228f1d5bcc09d3db505757568b68，
无新Release。后续优先排查输入/发布/首帧等待链路；用户实机缓慢感已消退但根因未证明。
完整本轮原件/workspace/terminal-review/cursor-virtual-experiment/，40窗口与16诊断分开保留。

最新“继续”启动新的realClock诊断：ActivityScenario原生时钟+无障碍SetText/IME_ENTER，
默认/全虚拟×普通/TUI、1k、键盘隐藏/显示/再隐藏，共144(24预热)；不重采旧窗口。
关联窗口OnDraw与FrameMetrics、gfxinfo真实vsyncId、Surface/DisplayFrame；歧义/缺失显式保留。
测量区间无ComposeTestRule/mainClock/waitForIdle/语义遍历/JSON，先定位等待再改生产。
协议benchmarks/TERMINAL_REAL_CLOCK.md，工作流terminal-pipeline-tests.yml/realClock；生产仍55db111。

RealClock各次测试准备问题和失败详见benchmarks/TERMINAL_REAL_CLOCK.md。关键已证事实：
OnDraw.drawingTime可指前一帧；应按gfxinfo的DrawStart<=真实drawNs<=SyncQueued唯一匹配，
再关联VsyncId/Surface/Display；原72默认样本的同次轨迹已离线验证此关联，不重采它们。
2e2a48a/run41124715935仅启动诊断4/4就绪并可见，无性能样本；无障碍连接提前初始化。
接下来v5只读exact FRAME_DRAWN+完整字节，计时后等待原生可见性（异步缓存），不再用
viewport.geometryReady/window缓存当绘制完成事件。生产仍55db111，未采用任何调度优化。

RealClockv5完成：2fdaf0e/run41126703241成功，144窗口(24预热/120正式)，144均唯一匹配
实际gfxinfo绘制区间及Surface/Display token；725FrameMetrics/0丢回调/4原始轨迹/750目标
SurfaceFrame/0丢调度bundle。完整JSON哈希113d25ae718edc947f242c0b81e529ee6ce180a8a74360674955f72af6c01447。
离线同次atrace×sched显示默认TUI发布→draw的93%–95%为主线程运行，applyChanges约132–145ms；
不是单纯等帧。不急删withFrameNanos，下一项先以现有TUI_CHUNKED做单因素对照，定位长历史
UI结构更新；不据不同模式单次数据宣称手机提速。生产仍55db111。所有准备失败单独保留，
本轮工作目录/workspace/terminal-review/real-clock/；无手机采集要求，无新正式Release。

最新用户明确“继续优化终端全量虚拟化模式”，覆盖上一项默认TUI分块计划。
当前候选只针对FULL_VIRTUAL备用屏幕每帧整屏测宽：benchmarks/terminal-full-virtual-alt-width/
candidate.patch三文件实验补丁，不改live生产。显式fullVirtualWidth限定owned ALT元数据、
相同字体/owner/屏幕代次与Text才复用，最多物理行数的Text+宽度；旧width()规则不变。
新增6JVM、42原生字体oracle、真实页面4对AB/BA(288窗口/48预热)；性能不靠工作计数判定。
协议benchmarks/TERMINAL_FULL_VIRTUAL_ALT_WIDTH.md；workflow terminal-alt-width.yml，仅云端Release。
工作目录/workspace/terminal-review/full-virtual-alt-width/，生产仍55db111。不要重跑旧薄光标/默认TUI实验。

ALT宽度候选结论不采用：f1211a5/run41152305179，43JVM+42字体oracle通过，3完整对照+
第4组候选完成252个页面窗口，但1个缺FrameMetrics回调导致唯一系统帧关联门禁失败，
第4组原版未运行。键盘隐藏前三组延迟比1.065/0.908/1.134，已有两组更慢，数学上无法
满足预设>=3/4改善，不补跑不放宽。原轨迹测宽CPU约1.1–1.8→0.3–0.4ms，整体收益未成立。
所有源补丁/数据/7原始轨迹保留在full-virtual-alt-width，live生产仍55db111，无新正式包。
下一独立假设是全虚拟ALT列表内容捕获新frame导致重复子组合；先验证内容身份/重组，
不叠加已拒绝宽度缓存、不改默认模式或选择/鼠标行为。

用户已确认总体方案，当前只推进FULL_VIRTUAL P0机制验证：生产仍55db111。
Workflow terminal-viewport-interactions.yml/fullVirtualMechanism，只在generated/viewportSources
的一份FullVirtualTranscript副本加非可观察计数；跟踪内容定义/区间/item提交/行组合/
真实测量及Paragraph复用，25固定窗口，无性能时间指标、不含任何已拒绝缓存。
同时CI保存正式Release/派生目标/驱动及fixture的5个运行时依赖图，不能以BOM推定实际版本。
协议benchmarks/TERMINAL_FULL_VIRTUAL_MECHANISM.md；工作目录/workspace/terminal-review/full-virtual-mechanism/。

P0设备机制验证790a1b1/run41165306118：25窗口/1JUnit通过；报告要求驱动自带Foundation
的错误导致job最终failure。驱动从目标APK使用Foundation，已离线修正报告并保留原失败，
不重跑场景。4次单行编辑均同screen身份、1次内容/区间重建、23item执行，其中22未变；
未变测量Row组合0、Paragraph变化0。不是整屏重排，仅外层item失效。5运行时图确认本轮
目标Foundation/Runtime/UI/ui-text=1.11.0-alpha06，驱动公共版本一致；未改依赖。
下一步可进入独立P1内容稳定候选，但必须守住SEL/MOUSE/结构/字体/代次失效，并实测收益；
当前仍无优化合入/正式Release，生产55db111。P0报告恢复在full-virtual-mechanism/recovered-report.json。

已进入P1外层ALT内容身份候选：benchmarks/terminal-full-virtual-alt-content/candidate.patch，
只改一份FullVirtualTranscript测试副本，live生产仍55db111。限定owned ALT/零历史/PAN/无SEL，
按screen引用+owner/代次/度量保留内容，callback不捕获frame/pass/bound，文字仍可观察。
第一轮workflow terminal-viewport-interactions.yml/fullVirtualContent：25机制+2边界+原6交互
方法，不计时；要求未变item提交/内容重建归零及所有必要失效。之后才无探针真实PTY对照。
协议benchmarks/TERMINAL_FULL_VIRTUAL_CONTENT.md，工作目录/workspace/terminal-review/full-virtual-alt-content/。

使用 Android Studio 或命令行 Gradle：

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器上的仪器测试
./gradlew lint                   # 运行 Android Lint
```

构建应用需要在 `app/` 下提供 `google-services.json`（用于 Firebase）。

## Coding Style & Naming Conventions

本仓库使用 `.editorconfig` 统一格式：

- Kotlin/Gradle 脚本：4 空格缩进，最大行长 120。
- XML/JSON：2 空格缩进。
- Markdown/YAML：2 空格缩进，允许尾随空格（用于对齐）。

命名习惯：模块名为小写目录（如 `ai/`、`tts/`），Kotlin 类遵循 PascalCase，测试类以 `*Test` 结尾。

## Testing Guidelines

终端优化约束：不得改变终端状态栏或 KEYS 中任何功能、入口、行为和语义。若确认必须调整，先向用户提交具体方案及影响，获得明确确认后再执行；性能优化不构成此类调整的授权。

已获用户明确授权的例外：新增状态栏 `RENDER` 渲染选择按钮，默认保留当前渲染方式，允许用户自主切换实测；该授权不扩展到其他状态栏／KEYS 功能的修改。

后续用户已明确要求继续推进虚拟历史；虚拟历史只能作为 `RENDER` 的独立可选模式接入，必须在 TUI、备用屏幕、MOUSE、SEL、IME 等不满足验证条件时回退到原有 eager 路径，不得改变其他状态栏／KEYS 分支。

最新授权：不改变原有操作习惯的终端优化可作为状态栏 `RENDER` 的独立选项加入，原方案仍为默认。
`VIRTUAL_HISTORY_IME`（虚拟历史·键盘稳定）允许在已验证的 IME 避让条件下保留虚拟行树；原
`VIRTUAL_HISTORY` 的 IME 锁存回退及显式重试不变。新模式仍须保留 SEL、MOUSE、TUI、备用屏幕及
禁用 IME 避让时的兼容回退；不得借此修改其他状态栏／KEYS 功能。

测试框架以 JUnit/AndroidX Test 为主。未设定强制覆盖率门槛，但新逻辑应配套新增/更新测试。测试文件命名建议：

- 单元测试：`FooTest.kt`
- 仪器测试：`FooInstrumentedTest.kt` 或 `*Test.kt`

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, and PPTX files
- **highlight**: Code syntax highlighting implementation
- **search**: Search functionality SDK (Exa, Tavily, Zhipu)
- **tts**: Text-to-speech implementation for different providers
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  and pin status. Conversations can be truncated at a specific index and maintain chat suggestions. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources located in `app/src/main/res/values-*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
