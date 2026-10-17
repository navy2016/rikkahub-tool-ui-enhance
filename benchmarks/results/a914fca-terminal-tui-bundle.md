# 终端四项总批次：Release 验收完成

受验提交：`a914fca4594ccf9a75290e4acc3880f02d442e5e`，分支 `opt/terminal-verified-a79e7b6`。
生产源码树：`639ee4f3286cb647a0b09cfa61a3dc1659586e1d`。

四项总批次实现、云端门禁、正式 Release 和独立 APK 校验已完成；**云端验证，实机待验**。
遵照要求，不下载或交付 APK。报告及证据包不含 APK、账号密钥或用户实际终端正文。

## 正式 Release

[Release 构建 39036798535](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39036798535)
及[独立校验 39037487624](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39037487624)均成功。

| 项目 | 结果 |
| --- | --- |
| 源码 | `a914fca4594ccf9a75290e4acc3880f02d442e5e` |
| 应用 ID | `me.rerere.rikkahub.dev.next.mod` |
| 版本 | `2.1.62`，versionCode `151` |
| APK 大小 | 83,730,691 字节 |
| 类型 | 正式 Release，非 debuggable／非 testOnly |
| ABI／签名 | 仅 `arm64-v8a`，v2/v3 签名及 ZIP 完整性通过 |
| 上一版对照 | 与 `b7a93c9` 签名、manifest 身份、字体内容、依赖版本和 native／PRoot 资产相同 |

APK SHA-256：`f7659d732c754e934ff3764d9f9a28733c3f514f1c39c2ccb3e05ca70fab0712`。
签名证书 SHA-256：`f136fff34c01d34edd2f625e77df5d408f6e09431b2e46e6d58cc5654a722265`。

云端 artifact：`rikkahub-release-a914fca4594ccf9a75290e4acc3880f02d442e5e`，ID `12102408409`。
artifact ZIP 为81,471,653字节，SHA-256
`dce656aa447098b5097dde2a63f6bb525719c94c55fd05edf5f504bc6e8ef2ee`；ZIP hash 与解压后 APK hash 不同。

独立对照记录2个DEX、2个生成的dexopt profile、1个源码元数据和11个Web归档条目变化
（包含重命名的新增／删除），未把这些生成物差异隐去。未改动生产资源／字体／native资产。
本地只复核云端校验回执及哈希绑定的打包对照，不声称本地验证或安装过 APK。

## 本次实现

- 协议：CSI 按前缀／中间字符分派，Kitty 探测不再误恢复光标，xterm modifyOtherKeys
  不再误当作 SGR；CSI 2J 清屏不再强制光标回原点。未宣称完整 Kitty 支持。
- 输入：IME 组合字符完整发送；文字、粘贴、鼠标字节和控制键进入一个 UI FIFO，仍由
  原会话写入器串行写 PTY。X10 前缀、1005 UTF-8、鼠标移动／修饰键／像素编码得到修正。
- 几何与状态：MOUSE 命中使用真实屏幕行布局；resize 与解析共用事务边界，成功才确认
  已应用尺寸。工具快照一次冻结屏幕／光标／模式，用单元格到文本的映射处理中文／组合字符。
- 性能：复用有界 UTF-8 解码暂存，普通字符直接解析，复用不可变样式／空单元格，历史
  保留独立数组并共享不可变 Cell；巨大 CSI 计数有界处理且保留最终行身份与历史。
- 发布：同步输出采用完成通知与原有有界保护；长期输出任务读取最新 RENDER 选择，
  保持 frame／rows／可选光标状态原子发布及唯一垂直滚动执行器。

默认仍为“分块图层”，状态栏／KEYS 入口和原操作保持；协议修正会有意改变此前错误的报文字节。
COPY 仍读取 emulator；原始字节尾部仍为 256KiB，历史容量及备用屏幕／CSI 3J 语义不变。
没有升级依赖、修改字体／native／PRoot 生产资产，没有本地 Android／Kotlin／native 编译。

## 两个新增 RENDER 选项

| 选项 | 接入范围与回退 |
| --- | --- |
| Pi regular 历史分块 | 仅直接、显式 `pi --tui-mode regular` 或 `--tui-mode=regular`，可带 resume／continue。RAW 输入不变；使用自然行高 eager 分块，不是自动虚拟历史。未知参数／shell 包装、ALT、全网格、SEL、MOUSE 保留兼容路径。 |
| TUI 光标叠加 | 竖线／下划线光标画在原 Text 上，不替换字符；位置来自实际字形布局。块光标沿用原绘制，SEL／MOUSE 回退。 |

进入现有 RENDER 选择，按命令保存；新选项不是默认，也不是“所有设备更快”的保证。
regular 的真实页面验收使用隔离的合成 peer，未使用真实 Pi／Claude／OpenCode 账号对话。

## 云端门禁

| 检查 | 结果 | Run ID |
| --- | --- | --- |
| Release JVM | 457 项通过，其中新增30项 | [39031384622](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39031384622) |
| 视口 lint／Release 类型编译 | 通过 | [39031390295](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39031390295) |
| 真实页面测试包编译 | 通过 | [39031395701](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39031395701) |
| 完整视口 | 162 项：90几何、61交互、7后端、4新增显示 | [39031817089](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39031817089) |
| 首次 PTY 集成 | resize 测试失败，原失败保留 | [39032047858](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39032047858) |
| 汇总修正后 PTY／页面 | 15传输、2服务、137读取、6TUI、36回显、12收敛回执通过 | [39033990454](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39033990454) |
| 正式 Release | 成功 | [39036798535](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39036798535) |
| 独立 APK 校验／上一版对照 | 成功 | [39037487624](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/39037487624) |

最后一次修改仅测试查询／报告，`a914fca` 与 `d86ee64` 的生产源码、应用 JVM 输入及视口
构建输入完全相同。沿用这些未变化的已通过结果，不称为在同一新 SHA 重跑全部测试。
114项本地 Python 校验通过；工作流 YAML、嵌入 shell 和改动行检查通过。

## 解析器同 JVM AB／BA

对照为功能修复提交 `917995852c9e5eb195cb44262f41a1238f422bca` 的解析器；参考文件只改
package，固定哈希和允许的分配改动区域均已核对。不是用错误协议版本作为速度基线。

环境：Linux amd64、OpenJDK 21.0.12.1。每负载2对预热、4对正式 AB／BA，共12个预热、
24个正式样本；构造和首次解码排除。下表是各批每chunk均值的中位数，不是单操作 P50。

| 固定负载 | 旧／新分配 B/chunk | 旧／新耗时 µs/chunk |
| --- | ---: | ---: |
| ASCII | 1,057,175.25 ／ 141,772.25 | 257.70 ／ 166.93 |
| Unicode | 1,323,607.71 ／ 250,479.00 | 276.55 ／ 192.52 |
| 重绘 | 5,502,712.50 ／ 160,840.50 | 1479.09 ／ 1018.14 |

这三种负载中分配减少约81%—97%，各四对耗时均下降。全部样本保留，没有按耗时门槛筛选。
不代表解析器零分配、手机整体加速百分比、完整终端吞吐或 FPS；新解码暂存每会话保留4096字符。
默认 eager 全历史冷组合的 O(历史行数) 成本仍存在。

## 首轮失败与修正依据

失败用例为 `tuiPtyResizeReceiptMatchesKernelAndAtomicSnapshot`，原测试第209行等待
`SIZE:10 60` 超过15秒。BusyBox `stty size` 的共享尺寸函数会优先使用继承的
`LINES=24 / COLUMNS=80`，即使 ioctl 已返回其他尺寸。

独立本地 PTY 诊断复现此差异；第二轮 Android 测试同时记录继承值与去掉这两个变量后的
查询值：内核／快照依次为10×60、20×100、24×80，父环境始终24×80。修正仅在查询子进程
执行 `env -u LINES -u COLUMNS stty size`，不更改生产环境、不删除尺寸断言、不延长等待。
先等完整回执，再直接断言真实尺寸，保留唯一请求ID与失败诊断。

原 Android 首次失败没有捕获当时的子进程正文，不补造该缺失观察；局部诊断及第二轮读数
单独记录。首轮已通过的五项 TUI 不被扩写成完整通过，当时旧页面36回显／12收敛尚未开始。

## 原始证据与边界

解析器和新增显示原始 JSON 已从完整压缩分片复算；第二轮页面摘要未出现在 Check API，
分页仍缺失，因此用 github-artifact-relay 中转同一次运行的原件，无测试重跑。页面JSON与
36条原始日志样本、12份原始仪器回执完全相符，TUI JSON 与全部3个API分片逐字节一致。

| 原件 | SHA-256 |
| --- | --- |
| 解析器样本 | `abbe2ccb7dab5afb2e6e2c27114b3cb3aa38e819071145516e81dff371d8d2ec` |
| 新显示证据 | `0c6d31250bad6d74918e37dde5ac641e066dfef6a7f97cf9f0025fe0e1b349e4` |
| 第二轮 TUI | `3447481316e7835c9ea7d615bfe51d698f2b249651299e779af4af7e332b1506` |
| 第二轮页面原件 | `5ed6599c9b7e37a5053618e8eb6c71e52db06bee7c340e72494ea05d72fcfd9a` |
| Release 打包对照 | `f90a5ec2f8e646af814c4c75663440dd0b93c54fabb3bd8c804c10821182228a` |

证据包附选定源码、完整变更补丁、参考解析器、原始回执和 `verify_package.py`；可用
`python3 -B verify_package.py` 离线复核，无需联网、编译或 APK。它不是可独立构建的完整 Android 仓库。

没有在本轮运行新的完整 V6、整页旧新版 AB／BA 或30分钟压力测试，不把旧数据重标或合并。
云端模拟器不替代实机触摸／键盘、GPU呈现、温升及长期内存验收。未实现全屏聊天历史录像。
apply-patch 用于编辑；github-artifact-relay 仅取回既有证据，不含 APK 附件或用户真实终端正文。
