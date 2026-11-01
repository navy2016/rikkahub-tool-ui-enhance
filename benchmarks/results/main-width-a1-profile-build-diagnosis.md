# A1性能构建资格诊断：不是性能回退，不追认原失败

生产始终306e0b80b508c961bc00f2d4b34a2688e5857a1a，A1候选仍c4113db冻结的五源文件。
计时协议冻结f543171：2016窗口/96预热/1920正式，中位、样本p95及逐pair最坏门槛没有修改。

## 实际执行链

1. source60b51ab的workflow派发被HTTP422拒绝，YAML1.1把on转成true；API确认0run/0样本。
2. 首个实际run41278159851/source98d53b2：机制原件复核与两个目标编译完成，
   在`Paired target manifests/resources/runtime assets differ`失败；设备experiment步骤未执行，0性能窗口。
3. build-only41279081126/sourcea201537：增加逐包清单，确认唯一差异`assets/dexopt/baseline.prof`；仍由原守卫拒绝。
4. build-only41280242644/source6e6b6eb：增加只读profile/DEX符号审计，原始byte-equality守卫仍失败。
   原artifact13072713766经relay41281198160取回，未下载APK或全部Gradle日志。
5. 在首次计时之前新增独立build-contract.json；source0fc3143/run41281700735使用该构建资格，
   只有该资格通过才能进入原2016窗口协议。本文件不代表该性能运行已完成或通过。

原始98d53b2失败时的两个APK未被作为artifact保存，因此后两个诊断是明确标记的新构建，不能冒称原二进制。
三个实际byte-equality失败保持failure；后续typed条件不追认它们，不存在性能样本重选。

## 准确原因

profile二进制19619→19625字节；baseline.profm相同，其他manifest/res/font/native/runtime/static Web资产相同。
classes7.dex方法总数从57557变成57559，CRC从3623117174变为2945741016。
两profile都正确匹配各自DEX的ZIP location CRC、method/type计数及内部SHA1/Adler32校验。
涉及6个DEX的完整42431个被标记方法（含H/S/P）与7799个profile类类型完全相等，metadata类定义也相等。
完整符号内容SHA256：`a91704d24311cfcc7851ca7dd8f950242243a78bc906f8c68b00721c8f829730`。

`onlyLocationChecksumsDiffer=false`：不能称为“只有CRC变化”。profile布局也随方法总数变化。
本地取回双方各约8MB的完整符号JSON进行逐项相等和hash复核；自身DEX二进制检查由源码绑定的CI在签名目标上执行，
不声称在未下载APK的本地重新解析了DEX。

## 新资格与旧规则的区别

构建契约SHA256：`48380bd3f7e51e5fa589d13c5f76d8c8b0855cc8317a94b90ae58bc47888cc67`。
它不是原始资产逐字节相等，必须明确报告`rawAssetsByteIdentical=false`。

- 所有资产成员名字相同；除准确命名的baseline.prof外，manifest/res/字体/native/runtime/static Web全部逐字节相等。
- profile必须匹配自身DEX且完整符号方法、H/S/P标志、类集合等价；profilem仍字节相等。
- 不忽略任意profile差异，不复制另一包profile，不删除、不改写、不重签APK；原驱动仍统一ART speed编译。
- 历史run-terminal-pipeline-paired.py不修改；typed inventory仅在新A1调用的局部作用域使用并finally恢复。
- 当前23项协议/来源检查、10项profile解析/资格拒绝测试通过；非profile、Web、来源、DEX或符号不等均拒绝。

全部故障、报告、源码、原manifest/asset清单与完整符号数据位于：
`/workspace/terminal-review/full-virtual-main-width-performance-v1/`。
后续先等待唯一运行41281700735，不派发重复矩阵；完成后重新核验全部提交/呈现/尾部终点，再决定后续完整回归。
