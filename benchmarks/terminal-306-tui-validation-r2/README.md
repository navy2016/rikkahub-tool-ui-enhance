# 306四项TUI报告：R2真实页面验证与几何诊断

所有病例必须是运行时`tui=true`、SEL关闭的原生PTY页面。`fullVirtual`与`chunkedLayers`是两种渲染方式，
不是普通Shell与TUI的对照。使用已在R1核验518项JVM的两个候选文件原字节，不加入其他修复或C1。

固定6个新进程方法：两种渲染各一项MOUSE、一项宽度、一项混合字形/IME/ALT几何。

- 宽度：仍采用真实COLS的80→100→120档；改宽度前LOCK保持LOCK，AUTO保持AUTO。两项均保持才算本轮宽度页面门通过。
- MOUSE：原点击/拖动/800ms长按拖动控制，再在8类混合样式行上直接点击实际`+`字形中心。
  `+`由PTY用CSI绝对列48画出，模型cell map单独证明列号；触点由真实TextLayoutResult取得，
  不能用`W*column`生成触点后再以同一公式证明“坐标准确”。保存真实SGR回执、两层FIFO和剪贴板哨兵。
- 几何：同一逻辑24、48与最右列的marker，分别记录ASCII、组合音标、CJK、emoji、bold和italic内容后的
  实际布局坐标。main→IME开→IME内重绘→IME关→ALT→MAIN全部保留。截图、模型revision、行高和底部位置单独记录。
  同列marker跨度超过1px记诊断发现；不通过修改字体、截字、拉伸整行来“隐藏”错位。

鼠标/几何新发现不被当成已修复，也不丢弃失败分支。没有PTY性能采样、历史轨迹重跑、Debug或正式Release。
只使用GitHub Actions构建Release派生测试目标；CI临时overlay在finally恢复。没有本地APK下载或设备安装。
R1原测试/协议/候选/结果冻结不变。宽度页面门通过后仍需完整功能回归，未自动采用。
