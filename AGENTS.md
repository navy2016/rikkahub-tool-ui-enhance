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
