# A1端到端性能V1：2016窗口完整，拒绝采用

## 结论

**A1拒绝采用。** run41281700735的CI执行成功、资料完整，并不意味着性能门槛通过。
同次原件离线复算全部2016窗口后，与CI冻结汇总一致：return收益通过，但refresh出现6项中位数与4项样本p95失败。
按预注册规则，不以返回收益抵消普通刷新回退，不补采、不重跑、不叠B/C救分。
生产仍为306e0b80b508c961bc00f2d4b34a2688e5857a1a/main ac97798416997a8230c9fa2a5a8569cf0ed0a911。
A1从未合入生产，无需回滚；停止为A1追加完整182视口/正式Release。此前533 JVM/20组件的正确性通过记录保留。

## 身份

- 执行源码：`0fc31431f637b47912ad5b7f8a688b0fe703b2e9`；run`41281700735`，success。
- 候选冻结：`c4113db212a52a77a9321fa096ca7f4e0aa03b48`；五源文件，仅benchmarks隔离候选。
- 候选身份：`f417e3fb3fe81d92fb4846f43b4e73ab225cf64b15594ed677ae54a944eaa422`。
- 性能协议冻结：`f543171cda98411a9f65c8227f538a3307339472`。
- 协议SHA256：`3a941a884f7dbbd5261a2feb8c13c417ef41e0bcfe2bd235b7d2200aecae32eb`。
- 构建契约SHA256：`48380bd3f7e51e5fa589d13c5f76d8c8b0855cc8317a94b90ae58bc47888cc67`。
- 原始results.json SHA256：`6687a55df37c5418e8b40475bcbcc996285ef3dd25258be4f99c42e16d98e4e0`。
- 原artifact`13075880259`，精确中转run`41287136178`；中转仅传输原件，无构建/采样。

## 完整性与身份

4组AB/BA、4场景、3条件、21轮：2016窗口=96预热+1920正式，每arm/pair/case/condition恰20正式窗口。
32份Perfetto原轨迹、32份方法log、8份完整invocation结果，与最终汇总/安装APK hash/typed资产证明交叉核验。
按8个invocation逐一独立重算后，核对相同验证器hash、完整槽位清单、原件hash和统一设备再合并；未删除任何窗口。

- 合格UI尝试3698次；真实提交3693次；真实呈现3693次。
- FrameMetrics：BOTH_EXACT=3682、ABSENT=16，ONE_EXACT=0、CONFLICT=0。
- ABSENT含11次有完整独立buffer/DisplayFrame证据的尝试、5次真实NO_SUBMISSION；缺FrameMetrics不被伪造为缺提交。
- 5次NO_SUBMISSION均为legacy返回窗口内**更晚**的无buffer同步尝试；同窗口此前已有自己的完整提交/呈现。
- 所有2016窗口均有独立合格终点；incompleteWindows为空。没有把其他帧贴给缺证据的尝试。

## 冻结门槛结果

下表为“candidate/306”的四个配对比值中位数，不是混池样本比；小于1更快。
return的主终点/UI须≤0.80且≥3/4配对改善；refresh/SYNC主/UI及全部系统中位数须≤1.05。

| 场景 | return提交比 | return UI比 | refresh提交比 | refresh UI比 | refresh系统结束比 |
|---|---:|---:|---:|---:|---:|
| 1k/IME隐藏 | 0.423580 | 0.407358 | **1.063286** | 1.032280 | **1.147002** |
| 1k/IME显示 | 0.322001 | 0.302382 | 0.953568 | 0.958996 | 0.982163 |
| 10k/IME隐藏 | 0.194045 | 0.156046 | **1.075528** | 1.048004 | 1.012313 |
| 10k/IME显示 | 0.142289 | 0.107533 | **1.155582** | **1.221333** | **1.089450** |

return主/UI各场景均4/4配对改善；全部return和SYNC中位数/p95/最坏守卫通过。
所有144个逐pair最坏窗口守卫通过，但不能抵消中位数/p95失败。

样本p95失败（20正式窗口nearest-rank第19项，再算四配对比值中位；门槛≤1.10）：

| 场景/条件 | 指标 | 比值 |
|---|---|---:|
| 1k隐藏/refresh | inputToBufferEvidenceNs | 1.117353 |
| 1k隐藏/refresh | inputToAttemptNs | 1.167389 |
| 1k隐藏/refresh | inputToDisplayEndNs | 1.103857 |
| 10k显示/refresh | inputToAttemptNs | 1.138973 |

中位数回退不是每一对都同方向。例如10k显示refresh提交配对比为1.304971、0.904790、1.006193、1.360257；
全部配对保留，仍按既定四比值中位判定失败。不改为另一种聚合或只挑改善配对。

## 后续链路判断

同批阶段记录的描述性复析另存same-run-stage-analysis.json，没有新测量。
10k显示refresh的capture墙钟中位约0.121→0.142ms、rowSync约0.288→0.299ms、测宽区间约0.062→0.105ms；
fed→published约6.707→9.247ms，published→首个UI尝试约6.598→7.313ms（均先按pair内统计，再取四中位的中位）。
这些阶段中位数不可相加，墙钟不是CPU；它们不能证明某段源码、profile或调度器就是回退根因。
因此不能宣称“只减少几次函数调用即可救回A1”，也不直接扩展既有帧内发布快路径。

建议后续仍从306单独研究B：复用现有物理行高度测量产生的精确自然宽度，关注多行/TUI重绘；
不把A1混入B，不复活P2，不先优化已经很短的单行刷新测宽。B目前未实现、未派发。

## 构建与范围限定

原始byte-equality失败41278159851/41279081126/41280242644保持failure且0性能窗口。
本次使用采样前新增typed profile构建资格，rawAssetsByteIdentical=false；除baseline.prof外全部资产含static Web字节相等。
profile必须匹配自身DEX并有相同完整符号方法/HSP/类集合，baseline.profm字节相等；APK未重写、未移除profile、未重新签名。
自身DEX二进制检查由源绑定CI执行，本地只复核完整符号原件/资产清单与回执，没有下载APK。
当前结论只适用于本轮API34/KVM/SwiftShader/Release派生非混淆目标，不是正式包或用户手机性能结论。

工作目录：`/workspace/terminal-review/full-virtual-main-width-performance-v1/`。
主要结果：`final-verification.json`、`all-windows.csv`、`same-run-stage-analysis.json`、`verified-shards/`。
独立复核：`python3 -B verify_experiment.py original/terminal-main-width-performance`；
也可`--slot 0`至`--slot 7`按固定8槽复核（每槽252窗口），最终必须完整合并，不能只验证通过的槽位。
