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

## Build, Test, and Development Commands

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
