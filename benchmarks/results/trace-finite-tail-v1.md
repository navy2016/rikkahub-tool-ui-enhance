# 有限启动尾部诊断V1：8原件通过，不代表原生终端资格

源码`573341f8902e787d50966120a195ac8d90242717`／运行`41353833781`success。
固定8场景的原trace、回执、边界和分析已独立复算，全部与CI相等。没有APK、终端性能窗口或候选。

## 结果与界限

- 单次内置Settings启动，返回后250ms封闭目标选择区间，再单独观察500ms。
- 目标只按UI／RT／mapping／acquire／Drawing／swap结构选择，不查询FrameTimeline成败。
- baseline／wait／periodic／volume正反各一次，8个固定目标均有截止前完整有序Surface/Display终点；traceFaults均空。
- **基线也2/2成功**，因此没有证明额外等待、周期flush或音量键改善交付，更不能宣称修复V3。
- 辅助原件审计发现选择区间后仍有6至22个结构性UI缓冲尝试；7/8轨迹最后一个结构性UI token没有对应SurfaceFrame。
  该辅助末帧不是协议目标，不能替换目标或反向改本次结果；记录缺失也不等于实际没有提交。
- 说明目标/观察区间分离有助于避免上一诊断的“目标终点本来就晚于cut”，但内置Settings仍有后续UI工作，
  不能模拟终端停止输出后的最终buffer交付。小型真实306终端诊断才可进一步检验该边界。

没有宣称`qualificationPassed`或`performanceMeasured`；C1仍只通过528JVM／44组件／17场景机制验证，未采用。
V3保持840窗口完全复算后拒绝；滑动诊断V1的两个权限失败及其他不完整结果继续保留，不补采、不改旧规则。

## 原件

- artifact`13101954048`，3806390字节，SHA256`9acb700fbd7bce10f2b7f89b473021ebb11f8791dcf2b33f71b78c9b365661a7`。
- 只读relay`41354159180`成功。
- report SHA256`2516b401555e64c2b41f6c6014a1fb635a2cfcc7c6502c60a1ef071df0d882fe`。
- 协议SHA256`6cc80118213857a51e3d020591b2b0b33438b9b739d4d5af516bf6cc92e4b105`。
- 工作目录`/workspace/terminal-review/full-virtual-trace-finite-tail`，`verify_finite.py`／`verified-report.json`／`original/`。

下一步：独立冻结少量真实306native PTY单窗口诊断，保留原500ms截止及所有合格尝试；若比较截止后的
等待/普通音量键，只能检验旧token原始时间戳的交付，不能引入新的窗口终点。不直接重跑840矩阵。
