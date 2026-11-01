# 有限启动尾部诊断V1（独立，不修补历史结果）

V3原生840窗口资格和滑动尾部诊断V1均已结案。此处不复跑，不修改它们的计时窗、目标选择或解析器。

每个场景先force-stop内置Settings、回HOME并等待4秒，然后启动原匿名pipe采集；只执行一次
`am start -W -a android.settings.SETTINGS`，没有swipe、fling、1004或权限提升。
启动返回后250ms固定“选择区间结束”，再经过500ms固定独立观察截止。所有proc uptime读数
保留10ms不确定区间；边界落在不确定区间内的数据不被伪装为精确时钟证据。

目标选择只看固定区间内的UI／RT／mapping／acquire／Drawing／swap结构，完全不检查FrameTimeline
是否有对应Surface／Display或end。保存所有UI候选，最后一个结构性buffer目标若缺timeline，
不退回早先成功目标。只有同一token/cookie的完整有序终点早于独立观察截止，才记为诊断证据。
无结构性buffer目标是诊断不完整，不等于NO_SUBMISSION。该通用系统UI分析不是终端资格解析器。

四分支正反各一次：baseline截止后50ms停；wait截止后1050ms停；periodic额外250msflush、
截止后1050ms停；volume截止后50ms发送一次普通VOLUME_UP，再等1050ms。音量键只影响一次性CI
模拟器，后续token永不替代原目标，也不把shell exit0当作“所有生产者已flush”的证明。

固定8场景，PID/comm/start ticks/真实proc消失与原文件hash回执，保留全部原trace、错误、null。
采集配置与上一诊断相同；新实验只变有限刺激和目标/截止分离。没有APK构建或安装，没有
C1/A1/B1候选、终端性能窗口、自动资格通过、性能采用或正式Release。
