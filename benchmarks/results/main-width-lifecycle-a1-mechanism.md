# A1 MAIN历史宽度生命周期：机制通过，尚无性能／采用结论

## 身份

- 生产仍`306e0b80b508c961bc00f2d4b34a2688e5857a1a`，main树`ac97798416997a8230c9fa2a5a8569cf0ed0a911`。
- 候选冻结提交`c4113db212a52a77a9321fa096ca7f4e0aa03b48`。
- 执行源码`d9980abe1e974220d3d7726d5e14a1d3fc98eb55`，只在前提交基础上修正workflow入口。
- 机制run`41275351431`，两个实际job均success；无构建／测试失败后重跑。
- 协议SHA256：`e9a9aa76c80e19f243a2095624f8662e1aae62764b726e7e9849af78e9bc2994`。
- 候选身份SHA256：`f417e3fb3fe81d92fb4846f43b4e73ab225cf64b15594ed677ae54a944eaa422`。

## 设计范围

FULL_VIRTUAL通过显式开关给共用PreparedWidth传入原packaged-font proof；旧虚拟历史不启用。
Index只有一个历史最大值队列，historyMetrics/proof独立于当前frame proof。真实owned ALT原位保留
MAIN标量状态但从不把隐藏历史计入ALT宽度，没有第二个停车checkpoint／ALT Text缓存。
新增owned rows的不可变alternateScreen来源位由emulator构帧填入，防止MAIN的无历史view经copy伪造ALT。
Page/helper、PTY解析和字节顺序、默认、真实高度、滚动／选择／IME／SYNC及blank保护均未改。
详细状态图与边界见`benchmarks/terminal-main-width-lifecycle/README.md`。

## 验证结果

- 14项本地Python守卫通过：字节范围、旧调用者、测试清单、覆盖恢复、CLI、错误回执拒绝等；无本地Kotlin编译。
- **533 Release JVM**：原504方法原样保留＋新29项；按精确class/method检查，无失败／skip／重复／漏项。
- 新JVM含1k/5k/10k各4次往返、360步固定随机差分、并发worker与ALT、epoch取消／释放、FIFO／owner／字体proof失效。
- **20真实字体组件方法**：新5＋原6全虚拟正确性＋原9intrinsic正确性；不运行原配对性能方法。
- **37生命周期检查点**完整。水平实际range逐步对齐完整自然宽度oracle，rowStates≤80、measuredRows≤128、writer≤1。

| 场景 | 新增历史测量 |
|---|---:|
| 1k／10k MAIN→ALT→MAIN，各3轮设备往返 | 0 |
| 两种规模MAIN追加1行并FIFO裁剪 | 1 |
| MAIN未发布的12行尾部，在ALT回来后 | 12 |
| ALT中裁到128行，回来后的幸存历史 | 0 |
| 同会话在ALT释放／重建后返回MAIN | 0 |
| 字体／fontScale／RTL／列数在ALT改变后返回1k MAIN | 1000（正确失效） |
| Serif未知字体129行返回MAIN | 129（兼容重测） |

同revision新frame获得自己的ready身份；伪造ALT、owner替换、旧lease、异常／负宽度及未完成worker均不能获得有效历史／ready证明。
剪贴板菜单、跨屏手柄、鼠标物理原点、字体／RTL／IME／重入和旧模式切换由原六方法覆盖。

## 证据

原artifacts：JVM`13071238037`，组件`13071536619`。Azure直接下载失败后使用既有只读中转，
relay runs`41276147317`／`41276112987`均success，仅传输同批原件，不构建／重采。
本地选取41份JVM XML、1份20方法组件XML、两份原report、真实lifecycle log及fixture源码hash。
逐方法、37场景、全部选中原件SHA/CRC、原始annotation与report内容完成离线交叉核验。
校验器另验证错误run SHA、漏JVM方法、篡改scene计数和候选hash全部拒绝。

入口说明：首次按新文件名dispatch返回HTTP404，API确认c4113db为0次运行。通过已注册
`terminal-pipeline-tests.yml`的`mainWidthLifecycle`调用新workflow，未修改默认分支，未重复设备采样。

离线命令：`python3 -B verify_evidence.py`（交付包解压目录），不调用GitHub/Gradle/设备。
仓库同一校验器：`.github/scripts/verify-main-width-lifecycle-evidence.py <capsule-root>`。
工作目录：`/workspace/terminal-review/full-virtual-main-width-lifecycle/`。

## 限定与下一步

这不是端到端性能，也不是完整182视口／真实PTY整页回归；没有A1正式Release、没有APK下载／附件、没有生产采用。
机制降低了重复工作，不据此宣称手机或普通刷新提速。当前用户设备仍以已核验306正式包为基线。
下一步冻结新的306↔A1同机配对协议，独立验证return、refresh、SYNC及系统呈现；禁止用55或P2旧成绩计算提升。
随后才做缺少的完整视口／页面／生命周期回归与正式采用决定。普通刷新若回退，不以返回收益抵消，不叠B/C救分。
