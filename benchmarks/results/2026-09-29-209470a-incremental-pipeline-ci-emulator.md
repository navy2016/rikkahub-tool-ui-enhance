# 终端增量链路优化：实现与验收

被测应用提交：`209470a6568b32a48a30dd4c5020814fcf86e21e`。
分支：`fix/terminal-viewport-semantic-reducer`。其后提交仅涉及 APK 校验工作流和验收文档，未改变应用或被测夹具。

## 完成范围

1. `a6b8b71`：缓存不可变历史行、ID 和内容边界；历史＋屏幕使用随机访问分段视图，活动屏幕更新不扫描历史。全局样式、列宽、历史结构及清空按原语义失效，旧帧保持不变。
2. `279ecd4`：捕获回看锚点只读取目标 ID；控制器缓存一个已校验的归档位置，命中时核对真实 ID。保留裁剪替换、非单调 ID、generation、测量坐标和单一滚动执行器的原行为。
3. `209470a`：以同一终端所有者、FIFO 区间、样式与屏幕身份及已发布行列表证明保留历史未变；只同步新归档行和屏幕。缓存证明随 Compose 快照原子提交，丢弃快照不会留下提前更新的缓存。生产帧发布采用引用相等策略，避免整个帧的结构比较。

重排、不完整／合成元数据、不同终端、待提交空白、resize 和全局样式变化保留完整回退。没有切换生产 LazyColumn，也没有改动 TUI 网格、文本选择容器、鼠标坐标或手势／滚动执行器。

## GitHub Actions 验收

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| Release 单元回归 | 261 项，失败／错误／跳过／缺失套件均为 0 | [36511150680](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36511150680) |
| Release 视口夹具 | 24 项手势＋8 项自然行高几何，全部通过 | [36511150621](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36511150621) |
| 性能矩阵 | 45 项，每项 3 次，完整性检查通过 | [36511150618](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36511150618) |
| 生产 Release 构建 | success | [36511182444](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36511182444) |
| APK 独立校验 | 来源 SHA、v2 签名、包名、非 Debug、ABI、ZIP 完整性通过 | [36659348361](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36659348361) |

新增 13 项 JVM 回归覆盖缓存复用／失效、旧帧不可变、回看访问次数、FIFO 状态复用、混合终端更新与完整同步器对照、空白提交和快照丢弃。另有 23 项报告／超时脚本测试通过；本地未编译 Android/Kotlin，未构建 Debug APK。

基准对每次更新有工作量硬断言：活动更新访问 0 个帧历史行、24 个同步文本行；单行追加／裁剪跳过 H−1 个历史文本行、同步 25 行，但帧快照仍访问 H 个历史行。追加／裁剪的历史快照重建与持久列表结构编辑仍有 O(H) 成本，不能称为整条链路 O(24)。

## 同次运行数据

环境：API 34 x86_64 / Nexus 6 profile、2 cores、4 GiB RAM、768 MiB heap、SwiftShader；同一 Release 派生 APK、同一模拟器，三个 renderer 相邻轮换，每项 3 次，`CompilationMode.Full()`。
基准运行时间：2026-09-29 02:07:56–02:56:38 UTC。

以下为生产所用 `chunkedLayers` 路径；单位 ms，RSS 为匿名 RSS（MiB），不是 PSS。
render/op 与 sync/op 为每次迭代的 sum/count 再取中位数；CPU p95 聚合采样帧。二者不能相减来估计 UI 成本。

| 历史行数 | 场景 | render/op | sync/op | CPU p95 | RSS anon |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1,000 | 活动更新 | 1.29 | 0.26 | 116.36 | 107.73 |
| 5,000 | 活动更新 | 0.50 | 0.32 | 119.43 | 171.36 |
| 10,000 | 活动更新 | 0.33 | 0.24 | 121.74 | 250.11 |
| 1,000 | 追加／裁剪 | 1.28 | 0.43 | 105.67 | 132.23 |
| 5,000 | 追加／裁剪 | 1.33 | 0.27 | 122.90 | 193.20 |
| 10,000 | 追加／裁剪 | 2.07 | 0.49 | 191.12 | 286.36 |

10k 首次挂载仍为 **1681.85 ms**，该场景匿名 RSS **344.58 MiB**；全部历史 Text 的首次组合／布局成本仍在。本轮不宣称首屏、真机 FPS 或懒渲染上线达标。

三个 renderer 共用新同步器，所以同次结果比较的是 renderer，不是旧／新同步器 A/B。不得将上一轮不同 CI 主机的结果算作受控提升比例。回看缓存由 JVM／控制器回归验证，不在隔离 renderer 的性能计时范围。

全矩阵与原始 Perfetto／APK artifact：`terminal-scrollback-benchmark-chunkedLayers-209470a6568b32a48a30dd4c5020814fcf86e21e`，ID `11010980099`，保留 14 天。完整 45 项摘要另归档为同目录 CSV，来源为该次 check-run annotations，不冒充原始逐帧数据。

## 已验证的 Release APK

- [下载构建产物](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36511182444/artifacts/11009209719)（GitHub 登录后下载 ZIP，内含 `app-release.apk`）。
- 版本 `2.1.62`，versionCode `151`，包名 `me.rerere.rikkahub.dev.next.mod`。
- ABI `arm64-v8a`，`debuggable=false`，APK 大小 `83,660,815` 字节。
- APK SHA-256：`6606a82088675824da5357128b69f8f8e5925df60d6aea0e8e6da8e0c7ed1121`。
- 签名证书 SHA-256：`f136fff34c01d34edd2f625e77df5d408f6e09431b2e46e6d58cc5654a722265`。
- GitHub 外层 artifact ZIP SHA-256：`7b2cf4b6a36234822f7d119e5b212aeb2aee57d96186bad449faca00dfa9a18e`，不要与 APK 哈希混淆。

校验阶段遇到的失败来自辅助工作流注册／自动选包和 Build Tools 37 证书输出标签兼容；明确指定同一构建后已全部通过，没有重新构建或更换 APK。
当前助手环境到 GitHub artifact 的 Azure 下载地址持续连接超时，APK 未复制到聊天附件；以上链接指向已由 CI 下载并验证的原始产物。

仍未覆盖的真机验收：实际 IME、跨块选择拖拽／剪贴板、真实 shell 输出压力与不同 GPU。现有自然行高测试覆盖样式、字体缩放、视口收缩和 TUI 切换几何，不替代这些真机交互。
