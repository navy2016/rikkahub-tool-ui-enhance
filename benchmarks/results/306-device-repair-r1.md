# 306实机功能回归R1：原件核验完成，未采用修复

执行源码`6881d80bbe2ab60bbc2c0852d76079abc1855095`，运行`41483158974`success。
518项Release JVM、4个原生页面方法、6次真实触屏手势和12张截图已独立核验，完全匹配同次CI报告。
四项用户报告全部来自TUI；本轮fullVirtual/default是两种渲染方式，不是TUI与普通Shell对比。

## 已证明

- 宽度候选仅两个文件，504原测试+14新增测试通过；AUTO/LOCK、实测锚点、取消和旧清屏策略均有边界测试。
- 未改306页面的两种渲染方式都复现80→100列后LOCK变AUTO，行数始终28，screenGeneration由4变5。
  后续100→120列原先AUTO仍AUTO。源码链路与该结果一致：SIGWINCH引发ED2换行ID，旧屏幕锚点失效后controller改为跟随。
- 未改306页面MOUSEon/SELoff的点击、横向拖动、800ms长按拖动共6次，PTY原始SGR字节完全一致，
  两层输入FIFO核对通过，剪贴板哨兵不变，本地非编辑选区数为0。显式OSC52对照能改变剪贴板。
  **本组未复现MOUSE复制，不等于否认实机报告或证明已修复。**
- 18次底部记录均为viewport内28px（该guest为8dp）；不是物理屏幕边缘距离，也未覆盖真实TUI操作中的空隙增长。

## 尚未证明

候选只用于JVM，原生页面并未安装候选。贴底、SEL关闭时复制、右缘阶梯仍未定位。
页面采用RAW PTY、自定义sh TUI、整屏寻址、SIGWINCH/ED2和1002/1006，但R1回执未显式输出`tui`字段。
下一轮新驱动补运行时TUI断言，不回写或改判R1；用原字节候选做实际页面验证，再补混合字符边框和IME几何。
不得把普通ASCII合成TUI的结果外推为用户所有TUI程序已经正常。没有性能结论、生产采用或正式Release。

## 证据

- JVM artifact`13162130156`，70491字节，SHA256`98bc35b7a7a87057e801c1491fd3ace3cbf1bff291d59546c689e32cb512a8e9`；relay`41511036211`。
- 页面artifact`13160433799`，2252592字节，SHA256`27fdf179c46f65f12caea93b5d57eb158f65d673c4ca3adf982fdf5141f3bc7f`；relay`41511113802`。
- 页面report SHA256`c1c965d3c283b245303a806a432d1a893c39e608c14881f3fc229f8fae6561e4`。
- identity SHA256`4cc50efc5c1ca3a8e0174a29e24a67ea138d40f9fe5fb0bac141e9a659bdba17`。
- 工作目录`/workspace/terminal-review/306-device-repair-r1`；`verify_r1.py`、`verified-report.json`、`jvm-original/`、`page-original/`。

生产仍306/main`ac97798416997a8230c9fa2a5a8569cf0ed0a911`，C1及历史实验保持冻结。
