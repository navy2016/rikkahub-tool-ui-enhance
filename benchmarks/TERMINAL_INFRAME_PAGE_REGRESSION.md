# 帧内发布候选：原真实页面交互回归

候选固定bfa2990的Page+helper，无追踪探针，不含P2，不改生产。前置49 JVM及32机制窗口已对同批原件恢复；
原CI失败回执保持不变，不能声称它已是性能或完整交互通过。

本阶段只运行原`TerminalFullVirtualPageTest`的ordinary/cooked与tui/raw两方法：
10k历史、真实IME、四段各160tick持续输出、选择复制、重入、鼠标、ALT/MAIN、实际输入桥、物理网格与释放。
新测试类仅改名并在写设置前等待同PID现有启动完成日志。原行为、操作、断言、超时及输出协议不变。
这不是性能计时；Compose测试框架仅用于原交互正确性，不能把其结果当新A/B样本。

CI构建无probe的Release派生候选，核验候选文件hash及环境还原，全部输出/安装hash保留。
失败停止，不重跑到通过，不修改原断言。通过后才能另行冻结新完整性能对照；仍不自动合入或发布APK。

## 结果86c5b411/run41248446341：原交互通过

无probe候选的两项原测试全部完成并离线重算原`validate_page`：ordinary/cooked和tui/raw各9个几何阶段、
4段×160tick输出、10k保留历史、25物理行/80列。选择复制、IME、重入、鼠标、ALT/MAIN及ACK:bridge输入均通过。
所有原始安装hash、候选两文件hash和未修改的TerminalPipelineTrace源码hash相符；原断言不变。
原报告SHA256c832a9e0f76742c15dcf7ad659076a4ccdb91914d9f79ab52c1fd5647f05ce4e。

这补齐候选的原真实页面交互检查，但不是全部492/182回归或性能A/B。候选仍未采用。
下一步须在新性能数据前单独冻结主终点、对照矩阵、缺失处理和采用门槛，不复用旧P2成绩。
