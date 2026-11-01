# B1物理行宽高产物共享：机制验证通过，尚无性能结论

## 当前结论

独立从306出发的B1五文件候选，通过529项Release JVM（原504+新25）、28项真实字体组件（原21+新7）及24个场景。
已从同次原artifact取回XML、原logcat、fixture源码清单和报告，离线逐项核对源码hash、CI annotations、完整方法/场景清单。
**这不是端到端性能通过，不是生产采用，不构建B1正式Release。** 生产仍为306/main ac977984。
A1/P2仍拒绝；B1不包含其历史宽度恢复逻辑，不更改解析器、Page帧内发布、滚动执行器、选择、字体或依赖。

## 源码与运行身份

- 生产`306e0b80b508c961bc00f2d4b34a2688e5857a1a`，main tree `ac97798416997a8230c9fa2a5a8569cf0ed0a911`。
- 五候选文件冻结`6b83107251c9fcde4f1f77976243a139de3e3239`，位于`benchmarks/terminal-physical-metrics-b1/candidate/`。
- 校正测试后的执行源码`f9c7e03643be06625c164ee0c4ef8359063941c1`；run`41295383061` success。
- 身份SHA256 `c8c2475077b82b23afad953a7b038e2f060739d9ac2a960ee6908a437c29be9d`。
- 机制协议SHA256 `540761e47f28647d4bc85680871bb7908f85db7e4d5d9d2fd813a3fe71402ed3`。
- 原artifact：JVM`13078169318`、component`13078854911`；只读中转`41296051791`、`41296100335`。

## 实现边界

1. 扩展现有物理行高度缓存条目，保存既有TextMeasurer结果的`ceil(multiParagraph.intrinsics.maxIntrinsicWidth)`，不是受约束的`size.width`。
2. 一份至多80条的物理行map；仅标量宽高与已有Text键，不存Paragraph/TextLayoutResult/历史document。frame证明为WeakReference。
3. 只有owned当前frame、canonical打包字体、同metric/resolver/density/方向/style以及完整Text匹配才共享；未知来源/样式保留原宽度路径。
4. 通过BoundViewport直接把当前产物交给PreparedWidth；旧调用者默认null。原MAIN屏幕宽度缓存优先，命中时不额外查询来源。
5. 原历史source/canReuse/retainFor、ALT清空历史、worker epoch/request/cancellation均保留；后台只用捕获的不可变有界产物。
6. 原`deferredWidth`、空Box就绪门槛与blank grace不变；显示旧Text不能替代新frame空白的宽度证明。

## 机制证据

- 12种字体大小/密度/fontScale/方向组合×24行，共288逐行自然宽度/高度oracle对比通过，含ANSI/CJK/组合符/emoji/RTL/URL及空白。
- 30个未知来源/自定义样式/段落链接案例拒绝共享并保持原高度及宽度oracle。
- 1k/10k历史下MAIN多行修改、ALT进入与24行重绘均共享24行宽度，额外屏幕宽度measurer调用为0。
- 返回MAIN仍分别测量1000/10000历史行，证明未夹带A1；不是宣称消除了ALT返回冷历史开销。
- exact same-frame强制空白、同revision新frame、grace-held旧Text、字体/密度/RTL/列数变化、custom fallback、remount均保持正确失效。
- 原FULL_VIRTUAL选择/鼠标/模式切换6项、intrinsic宽度9项、字体解析与异步成功/失败/取消6项一起通过。
- 唯一writer/有限物理状态与测量登记的原断言保持。此轮不包含完整182视口或真实PTY页面性能验证。

## 首次失败完整保留

run`41294632502`/source`6b831072...`保持failure。设备28项/24场景通过，但新JVM
`fifoArchivedRowsAreMeasuredAsHistoryNotOldPhysicalProducts`误以为隐藏光标在列0：`expected:<1> but was:<2>`。
实际fixture光标留在行尾，旧行失去保留空格，新行增加空格，两个物理Text均变化；306既有
`hiddenCursorColumnReservationChangesBothMovedAndNewRows`已独立验证该语义。
只修正新测试为2shared+4reused，并增加稳定ID、旧行去尾空格、新行`append `的精确断言。
五候选源码/patch及设备测试字节未改，原504测试未改；新SHA重新运行正确性，不是性能补采或调整门槛。

## 下一阶段

从306与此B1候选单独冻结新的真实PTY多行/TUI重绘性能实验；原单行refresh、SYNC、IME、return作为回退守卫。
主/UI/系统终点及配对、中位/p95/最坏守卫须在采样前固定；少测几行只是机制证据，不代替提交时延或CPU收益。
不得重采A1、叠加C、放宽旧门槛、改写/移除profile或自动采用。正式采用仍需独立性能和完整回归。

工作目录`/workspace/terminal-review/full-virtual-physical-metrics-b1`；离线核验：
`python3 -B verify_evidence.py <verified-evidence-directory>`。包内没有APK。
