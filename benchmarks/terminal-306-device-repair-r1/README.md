# 306实机功能回归R1：宽度候选与独立原版页面诊断

最高优先级是用户正式306包四项报告，不再派发性能或取证实验。生产main保持306；本目录不是已采用修复。

候选仅两个文件：controller的显式宽度变更LOCK保护，加Page的begin/complete/cancel挂接。
只在已完成实测几何、同session/历史/屏幕类型/行数且用户LOCK时生效，columns-only请求后若
TUI全屏重绘更换行ID，按新的完成布局重新锚定。AUTO仍AUTO。匹配发布的完成布局后保护消失；
用户AUTO/跳转/拖动/选择或不同screen/session撤销旧意图。历史清屏与其他resize默认语义不改。

测试分两臂，禁止混淆：

- `unit`：候选overlay＋原504/新14共518项Release JVM。新测试使用真实emulator的resize/ED2行ID
  和精确标量几何，覆盖eager/fullVirtual、AUTO/LOCK、缺布局、取消、不同session与清屏边界。
- `page`：不overlay候选，构建未改306的Release派生目标，执行4项真实native PTY页面诊断。
  fullVirtual/default各测MOUSEon/SELoff触屏点击、drag、800ms长按drag以及80→100→120宽度前后状态。
  两层input FIFO写完后才发测试终止符，PTY回报真实hex；剪贴板哨兵、本地选择语义、显式OSC52control
  和截图区分本地选择与远端复制。诊断发现bug不是脚本执行失败，也绝不等于“全部已修复”。

用户明确MOUSE在SEL关闭时复制，所以之前模式互斥草稿已经移除，不能称作该问题根因或修复。
贴底/右缘异常尚未定位；页面诊断保留实际底部坐标/截图，不会以ASCII几何测试代替用户的混合字体截图。
本轮不测性能、不做APK交付、不自动采用；若宽度候选JVM通过，仍需实际候选页面/完整回归验证。
原A1/B1/C1/轨迹方案均不包含。本目录所有测试与原始结果独立绑定source/run，不回改旧门槛。
