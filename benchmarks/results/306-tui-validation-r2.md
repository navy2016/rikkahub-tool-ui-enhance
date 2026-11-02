# 306四项TUI报告R2：宽度页面通过，鼠标字形错位/边框错位复现

执行源码`bb6c14da2a47e9dc74f34913c4dafc35213567fd`，运行`41515588411`success。
同次6个原生方法、22次触屏手势、42张原截图、输入FIFO和所有回执已离线独立复算，完全匹配CI。
所有方法要求运行时`tui=true`和SEL关闭；`fullVirtual`/`chunkedLayers`仅指渲染方式，均为TUI。

## 四项状态

1. **贴底间距增长仍未复现。** 本组mixed MAIN→IME开→IME内重绘→IME关→ALT→MAIN，物理PTY始终80×28；
   内容底部至viewport底部均28px。IME关闭/MAIN返回相对同条件mixed MAIN增量均0。
   保存了viewport、可用window和完整window的原坐标，不把viewport边界冒充屏幕底边。
   这组固定布局测试不能排除其他TUI、流式输出、动态栏或不同手机上的间距增长。

2. **R1宽度候选通过本轮真实TUI页面门，尚未采用。** 两种渲染均保持80→100前的LOCK，随后100→120前的AUTO。
   行数没有改变。候选两个文件与R1的518项JVM通过版本逐字节相同；未加入C1或鼠标/字体修复。
   它覆盖本次有限宽度调整+SIGWINCH重绘流程，不代表所有迟到重绘、快速连续resize及完整功能回归已经验证。

3. **已复现实际字形的鼠标列偏移，但“复制”完整原因仍未证明。** 两种渲染中，4项CJK/CJK混排点击失败：
   模型和CSI绝对定位证明`+`在逻辑48列，实际字形中心点击后PTY收到47列的PRESS和RELEASE。
   行号正确。全部22次手势剪贴板哨兵不变、本地非编辑选区为0，显式OSC52正对照成功。
   因而不能将命中偏移直接写成已证实的“本地选择抢事件”或“复制由OSC52导致”。

4. **已量化复现混合文字同列边框不齐。** 全部12个几何阶段（2renderer×6phase）中，
   同一逻辑24/48/80列的marker中心横坐标跨度均为36px。该差异出现在同一行起点和真实布局内，
   不是简单viewport裁剪；用户原截图尚未到达，不能断言其所有阶梯像素都只由此产生。

## 可复算的坐标链

guest为Android34、1440×2560、density560，12sp打包字体、名义cellWidth25px；字体和依赖未改。
相同逻辑48列在代表行上的布局中心如下（坐标相对行起点）：

| 行内容类型 | 实际中心px | 相对ASCII差值px | 旧`floor(x/25)+1` |
|---|---:|---:|---:|
| ASCII |1187.5|0|48|
| 4个CJK |1155.5|-32|47|
| ASCII/CJK混排 |1171.5|-16|47|
| emoji |1191.5|+4|48|
| italic |1189.4921875|+1.9921875|48|

`TerminalEmulator.appendStyledLine`按照逻辑cell输出原文但不为不同fallback glyph纠正像素推进；
`TerminalRenderedRow`使用自然Text布局，`TerminalPointerMap.hit`却用`terminalPointerColumn(localX,cellWidth,columns)`。
R2的输入字节直接证明1155.5/25和1171.5/25均被算成零基46（一基47），与实际glyph的模型列不一致。
下一修复必须让渲染、鼠标、选择、宽度/高度证明共享一致的cell↔像素映射，不能按总行宽缩放或裁掉真实字符救分。

## 原件与边界

- artifact`13175374293`，9687063字节；SHA256`24015d9c727ebc00e129fad54c6237cf8cd9268e0eab11df73631a886aede0a5`。
- 只读relay`41518010280`success，无APK下载或替代采样。
- report SHA256`fcd8bec87d5bbdf40f4be884617037fc0facfd27ec79e54a19eb1e1c6cecd505`。
- protocol SHA256`8b6a9b0ac8e2f3364b574b48266ebec3021e95df41b7026a4d82cc6729722deb`。
- 工作目录`/workspace/terminal-review/306-tui-validation-r2`，`verify_r2.py`和`verified-report.json`可重新复算。
- R1宽度候选Page SHA256`4552335ec6121b5215e8c6a926be705878aa0193c567b509b8ea37104ce0062c`，
  controller SHA256`b75aab524007dc5a0558160e227e8f76201624679735194e8217c4e79fff9bc2`。

无性能结论、未完成完整最终回归、未自动采用或构建正式Release。生产仍306/main`ac97798416997a8230c9fa2a5a8569cf0ed0a911`。
R1/R2及历史A1/B1/C1/生命周期的原规则和结果保持不变。当前无待完成CI。
