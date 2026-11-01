# 无APK尾部诊断V1：原件已复算，尚无可采用结论

源码`351d6b57be81290487b59bb80a425c52deca52b0`／运行`41351430986`已failure。
固定8场景全部有原轨迹和回执，6个正常分支的冻结分析逐字匹配；两个composite分支执行失败，
其清理轨迹另外保留为诊断，不补造正常分支结果。没有APK、终端计时窗口、资格通过或性能结论。

## 已证实

- 两次`service call SurfaceFlinger 1004`返回shell exit0，但内容为
  `Result: Parcel(Error: 0xffffffffffffffff "Operation not permitted")`。
  不是格式差异，不认为操作成功，也不通过root／权限／SELinux修改绕过。
- 两次baseline各有一个缺结束记录，且原解析器报告`unclosed-relevant-frame-span`。
- 两次wait和两次periodic没有原解析器完整性错误，也没有pre-cut SurfaceFrame缺end。
  但选定尾部UI的实际Surface／Display终点在逻辑切点之后，因此冻结判据仍为false。
  wait的DisplayEnd比floor晚35.5／188.3ms；periodic晚20.3／39.0ms。不能写成“没有提交”或“flush无效”。
- 没有任何分支获得终端资格或C1性能授权。Settings滑动尾部仍有运动／回调，不能把这种
  通用系统界面的结果直接外推为V3终端静止尾窗的完整因果证明。

## 证据与复算

- artifact`13100653852`，1968213字节，SHA256`f856a626848b7b171732108df9004dd8e28ea834b925ee6a57c8420c1e412165`。
- 只读relay`41351888266`成功。report SHA256`4cf68f0a50b1528ad717a824484989a7045f6e8907a10087b64bb609ed6b3de1`。
- `/workspace/terminal-review/full-virtual-trace-tail-diagnostic/verify_diagnostic.py`复算全部8份原trace，
  正常6份与CI分析相等；失败2份只核对原错误／停止／pull与诊断性分析。
- 本地首次比较误把内存protobuf计数器的整数键与JSON字符串键直接比较，现仅规范化
  `timelineKindCounts`／`payloadCounts`两张计数表的键；未修改原数据、诊断器或门槛。
- V3原生840窗口资格仍closed/rejected；C1仍只有528／44／17机制通过，生产保持306。

下一实验必须区分有限UI刺激、固定目标选择时间与后续观察截止，避免滑动惯性造成目标终点
本就越过切点；需要新的可用普通用户操作时单独冻结，不使用已确认无权限的1004路线。
