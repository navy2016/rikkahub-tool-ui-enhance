# 终端底部遮挡专项修复：Release 验收完成

受验源码：`bff4a51f762d33ac9164b4139f0a64d34a902283`。分支：`opt/terminal-verified-a79e7b6`。
生产树：`c1463f057fda3bbc693f7e8164065da4baebcdb1`。基线 `8ed4f06` 的生产源码与上一版 Release `a914fca` 相同。

专项实现、云端回归、正式 Release 及独立 APK 校验全部完成。**云端通过，原设备实机复测待确认。**
本轮没有下载或交付 APK；报告与证据包不含账号、签名密钥或真实聊天正文。

## 正式 Release 身份

[正式构建 39094656675](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39094656675)
与[独立校验 39095178074](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39095178074)均成功。

| 项目 | 结果 |
| --- | --- |
| 受验源码 | `bff4a51f762d33ac9164b4139f0a64d34a902283` |
| 应用 ID | `me.rerere.rikkahub.dev.next.mod` |
| 版本 | `2.1.62` / versionCode `151`（与上一版相同，请按构建来源区分） |
| APK 字节数 | 83,731,523 |
| 构建类型 | 正式 Release，非 debuggable／非 testOnly |
| ABI／完整性 | 仅 `arm64-v8a`；v2/v3 签名与 ZIP 完整性通过 |
| 上一版对照 | 与 `a914fca` 的签名、应用身份、字体内容、依赖版本和 native／PRoot 资产一致 |

APK SHA-256：`89393bf342ffc15f71979d25758db28f3a2c258c8a3c684f04508a093de21efa`。
签名证书 SHA-256：`f136fff34c01d34edd2f625e77df5d408f6e09431b2e46e6d58cc5654a722265`。

云端 artifact：`rikkahub-release-bff4a51f762d33ac9164b4139f0a64d34a902283`，ID `12124407430`。
artifact ZIP 为81,471,582字节，SHA-256 `0549ce240f4e8a281463962a2c1785a8a66fe0be6562161efc5c43e17a8cccce`。
该 ZIP hash 不等于解压后的 APK hash。

打包对照保留1个源码元数据、2个dexopt profile、2个DEX、5个Web归档条目变化（包含重命名的新增／删除）。
没有把这些生成物差异隐藏为“全部字节一致”。本地仅核对云端原始校验回执及哈希绑定对照，没有安装或读取APK。

## 修复内容

- **Python 半个光标行被挡**：原普通历史／虚拟历史只跟随最后非空文本行，遗漏换行后停留的空白光标行。
  现在跟随目标包含有效的可见光标行，使用该行实测底边。隐藏光标、未使用空白行不强制拉到底部。
- **Pi 随历史增长遮挡加重**：TUI 原屏幕起点用 `historyCount × 标称行高`，与自然 Text 高度的差额累积。
  现在所有 eager 路径（含 TUI）都等待有效实测几何，用真实历史高度前缀和屏幕行高度计算目标。
- **相关视口时序**：输出子视口直接报告扣除状态栏／KEYS／输入区后的真实高度；状态条或字体改变而父尺寸未变时，
  网格提示仍会更新。eager 的 IME 自动避让使用同份实测几何，不再依赖标称内容高度。

保持默认“分块图层”、原 8dp 尾部留白、自然行高、KEYS 操作、RAW／输入协议、历史容量与唯一滚动执行器。
没有通过增大固定留白、固定行高、截断历史或更换默认渲染掩盖问题。输入法开关仍不调整 PTY 物理行数。
解析器、字体、依赖、native 和 PRoot 生产资产未改动。

核心代码位于：`TerminalViewportGeometry.kt`、`TerminalEagerViewportGeometry.kt`、`TerminalItemViewport.kt`、
`TerminalViewportController.kt`、`TerminalViewportBinding.kt`、`ProcessSessionPage.kt`，共6个生产文件。
`terminalFollowRowIndex()` 统一文本／光标的跟随行；`followTarget()` 处理实测屏幕／历史边界；
`measuredEager = !virtual` 使 TUI 同样受完整实测几何门控，缺失／过期测量不发布猜测目标。

## 云端验证

本专项只用首次候选验证即通过，未使用汇总修正轮。不把上批次已通过结果重标为本轮结果。

| 检查 | 结果 | Run |
| --- | --- | --- |
| Release JVM：13个受影响测试类 | 210项通过，含新增10项 | [39092649808](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39092649808) |
| 视口 Release lint／类型编译 | 通过 | [39092656181](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39092656181) |
| 页面 Release-derived 测试包编译 | 通过 | [39092662487](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39092662487) |
| 完整视口：90几何＋61交互＋7后端＋4显示＋6专项 | 168项通过 | [39093035783](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39093035783) |
| 旧生产源码负对照 | 2项成功复现裁切，不代表旧版可见性通过 | [39093041553](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39093041553) |
| 实际页面＋native PTY | 15传输、2服务、137读取、6TUI、2专项、36回显／12收敛通过 | [39093214081](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39093214081) |
| 正式 Release | 成功 | [39094656675](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39094656675) |
| 独立 APK 校验／上一版对照 | 成功 | [39095178074](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39095178074) |

新增视口6用例保存24份实际矩形，涵盖空白光标、隐藏光标／sh样式提示符、虚拟模式和键盘往返、TUI增长、字体／ALT往返、
历史前缀复用与卸载。JVM另验证过期测量拒绝、锁定锚点、FIFO变化和实测 IME 阈值。

真实页面两用例保存14份光标行矩形，使用正式页面、默认渲染、显示状态栏与KEYS：
光标6阶段／TUI8阶段，含历史输出、键盘开关、状态栏切换及重进页面。
每份要求两次新的实际绘制确认同一帧／几何，且尺寸提交完成，不用固定等待代替完成条件。
真实页面使用合成子程序；TUI 策略通过相同命令匹配入口启用，不是实际 Pi 账号或聊天验收。

## 旧版负对照与候选实测矩形

同一份测试源码，旧臂只替换为精确基线生产源码。以下是**云端夹具**的实际矩形，不是用户手机像素：

| 场景 | 视口高 | 原版目标行底部超出视口 | 候选超出 |
| --- | ---: | ---: | ---: |
| 空白光标（27px高） | 293px | 16px | 0px |
| TUI，128历史行 | 293px | 907px | 0px |
| TUI，512历史行 | 293px | 3212px | 0px |
| TUI，1024历史行 | 293px | 6283px | 0px |

TUI 对照包含刻意增加部分文字字号的变高行夹具，放大累计误差以证明旧公式错误，不应把这些数字当作 Pi 实际遮挡量。
旧／新分属独立 Runner，输入与目标行／视口几何核对相同；不是性能 AB 测试，不计算提升率。

实际页面另以未改动的12sp字体验证：56px光标行在键盘关闭时底边1390／视口1418px；
键盘打开时357／385px；隐藏状态栏时1502／1530px。14份均完整可见，IME 往返物理网格25行保持。
这些也仅是云端页面观测值。

## 原始证据

所有记录绑定 SHA、用例清单与测试安装包哈希。矩形已从完整API分片解码复算。
页面 Check API 缺少 TUI 后的底部／回显摘要；用 github-artifact-relay 中转**同一次**运行的原始 artifact，
只提取6个相关JSON／日志，不下载APK、不重跑筛选样本。底部14矩形和回显36样本与原始日志逐项一致。

| 原件 | SHA-256 |
| --- | --- |
| 候选24矩形 | `f2157e105a95f49343df5c377f173e522f9f7cc62344443ada90069c8ec039ab` |
| 旧版4矩形 | `2c5f5a3d2e58bcd8547b7b06e8580cc04133197cd876554e83ab8fa447ba0784` |
| 实际页面2专项／14矩形 | `4223e04606e166f0f670b330ac02efaaf2642b361cc19cddcbc075ab4e2a6e4a` |
| 页面36回显／12收敛 | `4f8fa442cf637639498a17d41d222ed74ac13973a0eb030aa491d0adfc86fc5f` |
| 正式 Release 打包对照 | `dc9771b2149495a686e76facbf161f0e7c9e0f584219cc4e04eea5e944876964` |

页面 artifact `12124111573`：99,360字节，SHA-256 `a9d7e79f171bc50c626f1f460a57593797106fa7116ba76dbe8e4bb9ada2da6c`；
中转运行 [39094400633](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39094400633)。
apply-patch 用于精确修改，120项本地Python证据／接线测试及YAML／shell语法检查通过，无本地Android/Kotlin/native编译。

证据包包含选定受验源码、基线源码、完整变更补丁、原始回执及 `verify_package.py`。
解包后运行 `python3 -B verify_package.py` 可离线校验（Python3.11+，无网络、无编译、无APK）。
它不是可独立构建的完整Android仓库；文件校验和也不等同独立机构的数字签名。

## 安装新包后的最小实机复测

1. 用新包运行原脚本 `python3 /workspace/rikkahub_apk_test.py`，停在菜单等待输入；确认最后的空白光标完整可见。
   同样从 sh 中进入脚本再看一次，开关输入法、显示／隐藏状态栏、离开页面重进。
2. Pi 使用你原来的 regular 启动方式；先保留原默认“分块图层”，逐步增加历史，确认底部不再随历史增加被推出视口。
   不需要切到新增 RENDER 才修好；“Pi regular 历史分块”仍是可选功能，自动回退规则保留。
3. 如仍有遮挡，发截图并标注启动命令、RENDER已选／实际模式、键盘是否打开、是否锁定阅读。
   不用发送APK、账号或真实聊天正文；原测试脚本日志可继续使用。

原测试脚本顶部标注的 `a914fca` 是脚本原目标，不是检测出的已安装版本；该脚本不读取APK身份。
复测时注明使用本次构建 `39094656675` 即可，无需改脚本样例来获得通过。

不声称这次修复提升FPS、温升或长期内存，也没有新增完整V6、整页性能AB或30分钟压力测试。
用户原先发来的截图在工具侧仅显示占位，报告没有从中编造测量。云端通过后仍需用户在原设备确认修复。
