# 帧内安全发布：明确授权后的正式采用

用户在完整性能/最终回归报告之后明确回复“好的，继续”，授权合入已冻结两文件并构建/核验正式Release。
采用候选bfa2990，不叠加P2或其他拒绝候选，不改默认CHUNKED_LAYERS、状态栏/KEYS、输入FIFO或任何等待保护。

仅两个生产文件：ProcessSessionPage.kt，以及新增TerminalInFramePublication.kt。
字节必须等于已通过480窗口性能、原两页面交互、504 JVM/182视口的候选hash；12个边界测试同时保留。
旧源码契约的语义检查已在最终504用例中通过，采用时不再改断言或候选。

源审计命令：`python3 -B .github/scripts/verify-inframe-adoption.py`。
正式构建使用既有release.yml，build_type=release；独立核验使用verify-release-apk.yml绑定明确run/SHA。
只读比较参考为55db111、run41045773895；APK仅在CI下载/核验，不落到本地或聊天附件。
旧profile审计脚本仅针对其固定212/55包，不将它当作通用新包审计器。

历史实验的baseline guards保持原样。采用后不要在新生产树上重跑这些需要55基线的历史试验；
离线证据包内保留各自冻结源码，可继续复核原件。采用不改变任何历史失败结论。

性能证据范围仍是API34/KVM/SwiftShader测试环境，不承诺实机同幅提速；正式APK是否改善仍待用户实机复核。
本文不是已完成Release构建或已在用户设备安装的声明；具体构建/校验状态在独立采用回执记录。
