# 帧内发布候选最终回归（采用前）

前置性能run41253490506，源码25afad0，480窗口全部完整且冻结门槛通过；原件已离线复核。
固定候选仍为bfa2990的两个文件，不增改生产，不改默认模式，不叠加P2。

本阶段清单在`terminal-inframe-final-regression/plan.json`逐方法/源码hash冻结：
- 原39类492个Release JVM用例，加原候选12个边界用例，总计504。
- 原选择验收口径182视口用例：geometry90、interaction61、bundle7、tuiDisplay4、bottomVisibility6、tuiHistory5、selectionRepair9。
- selectionRepair为原6个全虚拟正确性方法加3个选择回归；不重新跑旧全虚拟性能方法，也不误用180项入口。

唯一更新的原测试是TerminalSmoothnessSourceTest的帧调度源码契约：不再要求字面空callback，
而检查一个真实withFrameNanos、单job合并、active context、完整资格守卫、保护回退及blank延时。
原测试方法保留，未删除断言要求；其余491原用例与全部182视口用例不改。12新边界继续检验pending与取消。

JVM在CI临时应用精确候选及新增测试，运行后恢复原Page并移除临时helper/test。
视口fixture复制的43份组件不包含Page/helper，均与候选对应组件逐字节一致；这些是组件全量回归，
实际Page调度已由无probe原页面两方法及480窗口验证。报告不能把fixture误称为整个App。

任何失败、跳过、重复或漏方法都阻止采用，保留原件，不自动重试。
成功结果仍只表示最终回归通过，不自动提交生产，不自动构建/交付正式APK。

结果8822e81b3a605e10fae1d8508282c0dc59fbc934/run41258895634：9个工作job全部success，
504项JVM和全部7个视口分片182项通过。后续collect action只重新校验既有artifact的XML、方法清单及候选hash，不执行测试。
生产仍55，没有自动采用或正式APK。

只读取证355ab7437246b5d5b60f048986cbb9aba00a262b/run41260467243成功；
收集证明SHA25682cfb692a1a419d12c49e5163aecb932bda2caae5fd25b4279f960e322e05e0d。
本地再次按XML逐方法核验504+182、无失败/跳过/重复/漏项。七视口分片数为90/61/7/4/6/5/9。
不产生新测试样本；已具备明确采用步骤的前置证据，当前仍未合入生产。
