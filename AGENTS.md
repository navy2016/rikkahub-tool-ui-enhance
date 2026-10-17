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
Release `39005664006`、独立校验 `39006232031` 成功。用户继续优化后的本轮基线为
`b7a93c9b5734ce1386d919d3be6085ef725124bb`：测量行只读取一次文本，使用无独立 restart scope 的
纯绘制转发函数，未测量 TUI 行仍保留自己的 scope。仅两个生产行绘制文件变化；测量节点／
几何缓存／滚动控制器不变。范围和验证见 `benchmarks/ROW_SCOPE.md`；旧轮验收不得重标本轮成功。

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
