# 独立候选：仅安全条件下在既有frame callback发布

基础为55db111/main8caf50d5c40e228f1d5bcc09d3db505757568b68，不包含P2或其他拒绝候选。
来源：publication-wait-v1/run41221492776，32/32 callback在Choreographer内，但continuation/capture在外。
这是提出候选的机制证据，不是性能收益；先做JVM边界与真实页面机制验证，不先运行优化A/B。

## 计划变更

交付形式为Page的`candidate.patch`与新增helper源码`TerminalInFramePublication.kt`，两者均在`sources.json`固定hash。
新增helper和JUnit测试只在CI临时安装，不留在live生产或普通测试source set中；构建后恢复Page并移除临时新文件。

- 只在FULL_VIRTUAL、原生通知owner存在、当前发布job仍active、仍挂载、主线程、非选择状态、
  没有未完成resize／TUI redraw／forced blank、通知快照非SYNC且当前Snapshot可写时，尝试回调内发布。
- 同步调用原`latestPublishTerminalFrame(false, false)`；保留模型monitor内的SYNC复检和原Snapshot事务。
- 拒绝、取消、旧模式、选择状态及所有等待保护走原异步路径，不在frame callback里挂起。
- pending只在capture前清理；capture中／之后的新通知留待下一次frame。返回false恢复pending。
- 成功后跳过原continuation发布块；不能在其后再次清pending。50ms空白延迟和重试循环仍保留。
- 不改输入FIFO、模型字节、通知合并、历史、渲染分支、鼠标／选择接口或唯一滚动执行器。

## 首轮边界门禁（性能另行冻结）

JVM验证：全部安全条件及逐项拒绝、取消、失去挂载、选择、resize、SYNC、forced blank；
publish仅一次；拒绝不得清pending或调用publish；false恢复pending；捕获中新通知不得被成功路径清掉；异常不吞。
源码结构验证：原renderTerminalFrame、同步输出/resize/blank原路径保留，只有时机选择与轻量helper新增。
真实Release派生页面：1k/10k×IME，普通刷新与同步控制；普通条件应证明FRAME_PUBLISHED在原callback及其UI帧内，
同步控制必须保留原guard。检查全部字节、最终revision、原历史身份、80列物理网格和原始迹线。
任何无法证明的边界停止，不删断言、不补样、不自动采用。后续完整回归和独立性能对照仍是前置要求。

## 首轮结果与原件恢复（候选未采用）

候选冻结于bfa29900798d6bb591c66da543859aaa37361dd7，协议SHA256
884495008f0760ece6b96de9293dcb320e0cabfd7d924cae8c18e8a42b4549c2。
候选Page+helper始终保持冻结hash，没有把探针变成候选逻辑。

首次952edc6/run41245660245在任务选择前置阶段失败：terminalPipelineTests覆盖testBuildType，
`testReleaseUnitTest`不存在；0JVM/0设备窗口。修正构建入口为独立-PterminalReleaseTests=true调用，
再以terminalPipelineTests构建设备目标；不是改候选或重采性能。

02be6ce7d47aadfd50bda6b6562a9e4d3f9ac2e1/run41245994937：49 Release JVM通过，4设备方法/32窗口完成。
原汇总30/32，通过的为14普通callback+16SYNC fallback；两处10k/r0-refresh报`Callback end without start`。
原因是takeLast(256)前缀从旧回调中途开始，解析器把前缀的孤立旧END误当当前窗口缺事件。
两个当前响应都有完整START/ELIGIBLE/CALL/FRAME_PUBLISHED/RESULT/END，均在同一Choreographer帧中。

修正只接纳“长度恰256的前缀、尚未见START、唯一领先END、seq在当前窗口之前”的截断历史，原样记录；
当前窗口缺START/END、嵌套START或未证明截断仍失败。没有重跑Android或修改候选/协议/断言。
离线同批原件恢复16普通帧内+16SYNC原保护回退，49JVM全部通过。
原报告SHA256a14d74bef6eb72fb1bbab48cfc45071bd26d3881641ca780c24f7e17c1569154，原workflow仍failure。

这是机制/选定边界，不是性能采用。下一步应先补齐真实页面选择/鼠标/IME/重入等原交互回归，再冻结新性能A/B；
不叠加P2，不用这32窗口估计优化百分比，不交付正式APK。
