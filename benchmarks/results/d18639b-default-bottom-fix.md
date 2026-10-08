# d18639b：修复全新默认终端的底行遮挡与松手回弹

修复代码与 Release APK 均对应 `d18639bb6f219704ee78f6d34d140357807877ea`。
分支：`opt/terminal-verified-a79e7b6`。未合并、变基或改写原远端优化分支。

## 根因与修复

用户最终确认：`2b0f259` 和 `807d2f0` 都存在这一旧问题。新开交互会话使用默认分块图层时，
最后一行被截断，手动滑到底后松手又被拉回；切到任一虚拟历史模式后恢复，切回默认仍正常，
但另开会话再次复现。因此不能继续把 `2b0f259` 当作这一缺陷的正常基线。

`rememberTerminalBoundViewport()` 原先只在 `wantsVirtual || visitedVirtual` 时启用 eager 行高测量。
新默认会话没有访问过虚拟历史，`bottomTarget()` 使用 `行号 × nominal cell 高度`。
实际 Text 的自然高度会受 fallback 字体、span 和像素取整影响，累积位置不等于该估算。
手动滚动末尾重新启用跟随后，`endUserScroll → reconcile → scrollEffect` 又把视口拉到偏高的目标。
访问虚拟模式使 `visitedVirtual` 锁存为 true；返回默认后实测几何生效，掩盖了新会话缺陷。

本次普通 eager 视口从首次布局就使用原有实测行高注册和历史前缀缓存，不再以虚拟访问作为前提。
缺失／过期几何继续暂停协调。恢复的旧阅读锚点没有实测高度时，只在首次有效布局绑定一次，
后续字体变化保留原始缩放基准。默认仍为 `CHUNKED_LAYERS`；没有切换偏好、加补偿空白、
改自然行高、删除帧等待或增加滚动写入者。TUI／全网格路径、PTY、状态栏与 KEYS 不变。

此前怀疑的 BasicText 不是本轮隔离出的版本差异，未回退它。已有 APK 对比任务
[37613943269](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37613943269)
确认两版签名、manifest 身份、依赖版本记录、字体、资源和原生库一致；这不是实机行为验收。

## 复现与验证

同一套 `d18639b` 新测试用独立 Runner 分别执行修复版源码与明确指定的旧 `807d2f0` 生产源码。
这是正确性失败／通过对照，不是同设备性能 A/B。测试包含加大字号的 span 压力样本，
用于确定性暴露自然行高与 nominal cell 的不一致；下列像素差不是用户手机的半行遮挡量。

| 验证 | 结果 | Run |
| --- | --- | --- |
| 旧生产源码＋新鲜默认会话测试 | 6 项全部在可见性／滚动位置断言失败 | [37717951119](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37717951119) |
| 修复后完整 Release 视口回归 | 113 项通过，含相同 6 项新测试 | [37717908734](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37717908734) |
| 应用 Release JVM | 353 项通过 | [37717908492](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37717908492) |
| 夹具 JVM＋V4 六场景三模式冒烟 | 25 项 JVM、18 用例通过 | [37717908565](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37717908565) |
| 应用 Release 构建 | 成功 | [37717908544](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37717908544) |
| APK 独立校验 | 签名、manifest、ZIP、ABI、SHA-256 通过 | [37719382330](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37719382330) |

旧代码的确定性失败示例：新默认会话最后一行 bottom=2359，实际 viewport=770；手动拖动
用例要求停在物理末尾 1947，松手后实际为 1338。修复版使用相同断言通过，未放宽可见性或位置要求。
六项新测试覆盖首次默认、完整拖动后静置、两种虚拟模式往返及另一新会话、字体／密度／RTL／追加、
系统键盘往返、无历史屏幕与清屏。实际 Text 边界与视口边界直接比较，避免控制器自证。

更早测试 run `37716410797` 使用暂停帧时钟的手势夹具，超时且 0 项完成；不计入复现证据。
随后改为帧时钟正常运行时的完整手势，并保留严格末尾断言。失败细节、来源 SHA、源码哈希、
成功组计数及 APK 信息保存在 [d18639b-default-bottom-verification.json](d18639b-default-bottom-verification.json)。
本地只执行 55 项基准脚本、15 项诊断脚本测试及静态检查；未本地编译 Android。

## 验收边界与成本

视口测试使用真实生产组件、手势与系统键盘，但不是用户手机的完整 ProcessSessionPage＋PTY 实测。
应用 Release APK 经过构建和包校验，仍需用户在新会话中确认最终显示；不能宣称已经在手机复现并验收。

默认首次 eager 布局现在会登记 O(H) 行高并保留标量前缀；这项成本明确计入 V4。
可信历史在活动行改写时复用，FIFO／字体／来源变化仍按既有规则重建必要前缀。
本轮不宣称性能提升，未执行 V4 完整 54 用例／162 次矩阵或实机长期内存测试。
旧 V3 默认完成判定使用同一 nominal cell 公式，不能证明底行完整可见，不重标为 V4。

## Release 交付

[下载 Release ZIP（含 app-release.apk）](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37717908544/artifacts/11524529073)。
需要 GitHub 登录。交付时 artifact 未过期；API 到期时间 `2026-10-22T02:34:00Z`。

- 包名：`me.rerere.rikkahub.dev.next.mod`；版本：`2.1.62`／`151`
- APK：83,696,743 bytes；`arm64-v8a`；非 debuggable
- APK SHA-256：`375cc48327706594eb2e58bb55b2f170e49a72202274981402d540b96c9a93ab`
- 签名证书 SHA-256：`f136fff34c01d34edd2f625e77df5d408f6e09431b2e46e6d58cc5654a722265`

复测请新建交互会话，保持默认“分块图层”，输出到需要滚动后检查最后一行和手动到底松手；
再检查键盘开关，并另开一个新会话。无需先切虚拟模式，也不要清除应用数据。
