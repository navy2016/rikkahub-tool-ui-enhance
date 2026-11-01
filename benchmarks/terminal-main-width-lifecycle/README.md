# A1：独立MAIN历史宽度生命周期（306基线，机制候选，尚未采用）

用户在后续方案／检查点之后要求继续。本阶段只推进A的设计、Release JVM和真实字体组件机制验证。
生产仍306e0b80b508c961bc00f2d4b34a2688e5857a1a/main ac97798416997a8230c9fa2a5a8569cf0ed0a911。
不叠P2/B/C，不改Page帧发布、不更改默认模式、SYNC/resize/blank/IME/选择/滚动语义；无性能承诺和新正式APK。

## 所有权与状态转换

```
widthIndex（一个挂载绑定／worker副本）
 ├─ ONE history state: source(owner/gen/style/columns/measured first..end)
 │                    + historyMetrics + packaged proof + scalar FIFO maxima
 └─ current frame state: weak exact frame + metrics + bounded MAIN screen Text widths

FULL MAIN / exact proof → 更新history后缀+裁剪FIFO，合成当前screen宽度，最后发布当前frame ready
FULL owned ALT / proof同一 → 原history状态原位保留；不复制／停车／恢复；ALT只测当前screen
ALT文字变更 → 每次仍测所有物理ALT行，无ALT文本缓存
ALT来源/字体/列数/全局样式失效 → 撤销history；MAIN回来按当前实际历史重新测量
MAIN回来 / history证明相符 → 直接同一个history模型，只测尚未测量的后缀
非FULL / proof=null → 原兼容失效逻辑
clear/dispose → 清除history/current ownership；旧worker的expectedEpoch失效
```

history元数据与current frame指标分开。MAIN数据不会参与ALT最大宽度、horizontal range或ready身份。
不把ALT的archival end当作已经测量的end；ALT没有retained first信息，不能在ALT猜FIFO裁剪范围。
宽度提交必须完整完成全部测量；失败不发布ready/checkpoint，下一次重新验证。
保留现有fork/epoch和request token；冷worker仍串行，不修改其线程池／Mutex调度。
checkpoint在释放时复制标量，原lease阻止过期页写回；不能保留Activity/Layout/历史Text。

## 源码范围

1. TerminalTranscriptWidthIndex.kt：将historyMetrics/proof与当前frame状态分开，新增显式proof重载，保留旧API。
2. TerminalPreparedWidth.kt：新增默认false的independentMainHistory参数，仅FULL调用true；proof必须来自原checkpointProof。
   两处width调用、requiredHistoryMeasurements、remember/worker request keys一致传递独立模式。
3. TerminalFullVirtualTranscript.kt：唯一显式启用点。VirtualHistoryTranscript保持原字节，不能误改其共用入口。
4. TerminalFrameSnapshot.kt：owned rows新增不可变alternateScreen来源位，默认false；不放宽ownedRows()。
5. TerminalEmulator.kt：构建owned rows时填入真实alternateScreen；解析／屏幕／历史行为完全不动。

第4/5项是来源完整性：仅RenderFrame.isAlternateScreen可被copy伪造。MAIN的renderFrame(false).copy(isAlternateScreen=true)
必须不能成为隐藏MAIN宽度的ALT证明。只使用原owned元数据，不加入model调用或同步锁。

## 本阶段冻结验收

- 完整原504 Release JVM + 29候选边界 =533个准确方法，失败/跳过/重复/漏项均拒绝。
- 5个新真实字体组件方法 + 原6个全虚拟正确性 + 原9个intrinsic正确性 =20个准确设备方法。
- 不调用旧P2类；不运行原fullVirtual性能方法／intrinsic配对性能方法。
- scalar oracle覆盖1k/5k/10k、360固定种子转换、并发worker/ALT、FIFO、owner、proof、generation、字体失败、负宽度、lease、释放。
- 设备使用生产五文件副本，核对每个generated source hash；真实字体测量和自然水平范围与full scan oracle相等。
- 收集精确计数和生命周期log，不用Compose测试时钟计时；这不是端到端性能、真实PTY页面或完整182视口回归。
- 云端一次Release JVM和组件机制阶段；失败定点诊断，保留首次失败，禁止为成绩反复采样。

如果机制通过，下一阶段才冻结新的306↔A端到端性能协议，按原完整提交/呈现身份链验证。
若普通刷新回退，拒绝A，不用返回收益抵消；不自动合入生产。全量回归与用户采用授权仍在后续。

## 运行

本地只读检查：`python3 -B .github/scripts/terminal-main-width-lifecycle.py guard`
Python守卫测试：`python3 -B -m unittest discover -s .github/scripts -p test_terminal_main_width_lifecycle.py`
云端入口：terminal-pipeline-tests.yml，suite=mainWidthLifecycle，明确ref=opt/terminal-verified-a79e7b6。
它调用terminal-main-width-lifecycle.yml；新workflow未在默认分支注册，不能单独按文件名dispatch。
所有临时源码覆盖由context manager在finally逐文件原样恢复；app/src/main树不得被提交为候选。
identity.json/protocol.json按实际源码和方法清单冻结；修改候选必须产生新的证据身份，不能追改已执行结果。
