# V3取证生命周期资格：完整复算，仍拒绝

唯一原生运行`41343554975`／源码`a5ccea022cd53f2a6595e6e84887f6899ee9f39d`已failure。
840窗口、24份原轨迹、两个launch及所有native case／停止／pull回执已独立复算，完全匹配冻结CI分类。
**qualificationPassed=false；不补采、不修改解析器、计时窗或门槛。C1性能尚未开始，生产仍306。**

## 结果

| 项目 | 原件复算 |
|---|---:|
| 窗口 |840＝40预热＋800正式|
| 完整窗口 |824＝40预热＋784正式|
| 不完整窗口 |16，全部正式、全部各场景最后的r20|
| 合格UI尝试 |1201|
| 证明提交／呈现 |1198／1185|
| FrameMetrics交叉核验 |1200BOTH_EXACT＋1ABSENT，无冲突|
| 原解析器traceFaults |24份均为空|

16个尾窗对应16个不完整尝试：3个`BUFFER_CHAIN_INCOMPLETE`有UI／RT／mapping／acquire／swap，
但缺SurfaceFrame；13个`missing-cookie-end`已证明提交，缺对应SurfaceFrame结束记录。
其中5个窗口已有更早的完整终点，却因后来合格尝试缺证据而`identityDecidable=false`，按原规则仍拒绝。
不能将UNKNOWN写成NO_SUBMISSION，也不能将UNOBSERVED写成实际掉帧、晚呈现或延迟数值。

## 已证明与未证明

匿名pipe、PID／comm／start ticks一致、race-safe proc消失及原文件SHA回执通过；原解析器没有
发现非首次丢包或未闭合相关UI span。但这些事实不保证FrameTimeline所有终点已经交付。
两份额外raw-wire示例未发现压缩packet隐藏相关记录，一个缺结束cookie示例跨sequence仍无对应end。
这不是所有额外wire审计都完成的声明；完整身份解析已覆盖全部24轨迹。

Perfetto v34的SIGINT路径本来就执行`Flush()->DisableTracing()`；不能直接归因为未调用Flush。
服务`all_data_sources_flushed`事件包括成功或错误，不能替代生产者交付证明。AOSP／SDK参考显示
FrameTimeline直接使用TracePacket、OnStop为空，SDK提示最后事件的可读性与线程本地writer有关。
**这些是下一诊断线索，不是当前guest缺口的完整根因证明。**

下一步是独立、有限、无APK的尾部交付诊断，不重跑V3。任何切点后的新合成／新帧只能用于诊断，
不能变成旧窗口终点，不能扩大原500ms截止或修补历史记录。C1仍只有机制528／44／17通过。

## 身份与恢复

- 原artifact`13099142636`，112712201字节，SHA256`7b229270b5dc9556d3589ab24168cc7112984b783a65cb35f1c80e5f2509657b`。
- 只读relay`41347058077`成功；没有APK或替代采样。
- 原results SHA256`3e863d05b4718c4a79ba80154580ed31c7855908135f178baf3423655136a2d8`。
- 协议SHA256`3b1565f113c9eff14ceb11e71c1bf1ad3a6c799537838fb92981c62a3bca3288`。
- 未改验证器SHA256`db8b565caa3fd1382b479e253e2e9f9c9eb019e64027749e229489188edc97c0`。
- 工作目录`/workspace/terminal-review/full-virtual-trace-lifecycle-v3-infra-retry`。
- `verified-report.json`、`verified-windows.csv`、`verified-tail-gaps.json`；原件在`original/terminal-trace-lifecycle-v3/`。
- 完整离线命令：`python3 -B verify_qualification.py original/terminal-trace-lifecycle-v3 --output verified-report.json`。
- 原V1／V2及V3首次JitPack401构建失败继续保留。当前没有待完成原生资格运行。
