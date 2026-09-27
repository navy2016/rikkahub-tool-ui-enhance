# 行同步优化后的终端热点测量

- 被测提交：`85e26966fa241e39a6c3c05fc91a3867a68fa8d0`。
- [基准工作流 #36287937387](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36287937387)：
  **success**，2026-09-27 02:14–02:49 UTC；30 项矩阵、每项 3 次重复，完整性校验通过。
- API 34 x86_64 / Nexus 6 profile、2 cores、4 GiB RAM、768 MiB heap、SwiftShader；
  同 APK、同设备、相邻 eager/lazy 配对并交替顺序，目标 `CompilationMode.Full()`。
- Artifact：`terminal-scrollback-benchmark-85e26966fa241e39a6c3c05fc91a3867a68fa8d0`，
  ID `10921534053`，保留 14 天，含完整 30 项报告、原始 JSON、Perfetto、APK 和环境信息。
- 本文件仅归档 check-run annotation 中的 **12 项更新热点摘要**，不是完整逐帧数据或全矩阵表。
- `0de32ea` 只调整了旧实现字符串的源码测试检查，生产代码与被测提交相同；
  [236 项终端回归](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36288544370)
  和 [24 项交互回归](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36288545058) 均通过。

## 同次运行的热点数据

时间单位 ms。rowSync/op 是各 iteration 的 sum/count 再取中位数；rowSync max 是各
iteration 最大值的中位数；CPU p95 是采集窗口内的帧统计。RSS 是匿名 RSS，不是总 RSS/PSS。

| History | Scenario | Renderer | n | rowSync/op | rowSync max | CPU-frame p95 | RSS anon MiB |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1000 | activeRowUpdate | eager | 3 | 0.40 | 1.55 | 106.98 | 148.41 |
| 1000 | activeRowUpdate | lazyHistory | 3 | 0.75 | 3.43 | 106.44 | 91.97 |
| 1000 | appendAndTrim | eager | 3 | 0.41 | 0.85 | 158.96 | 147.66 |
| 1000 | appendAndTrim | lazyHistory | 3 | 1.34 | 11.80 | 106.67 | 94.15 |
| 5000 | activeRowUpdate | eager | 3 | 2.13 | 5.43 | 210.93 | 256.58 |
| 5000 | activeRowUpdate | lazyHistory | 3 | 4.46 | 25.23 | 104.05 | 112.67 |
| 5000 | appendAndTrim | eager | 3 | 1.96 | 6.80 | 2002.17 | 255.67 |
| 5000 | appendAndTrim | lazyHistory | 3 | 7.38 | 38.11 | 109.97 | 115.75 |
| 10000 | activeRowUpdate | eager | 3 | 3.63 | 9.70 | 391.59 | 344.40 |
| 10000 | activeRowUpdate | lazyHistory | 3 | 9.46 | 39.82 | 106.87 | 157.30 |
| 10000 | appendAndTrim | eager | 3 | 4.10 | 11.98 | 7419.64 | 341.20 |
| 10000 | appendAndTrim | lazyHistory | 3 | 9.87 | 46.02 | 104.32 | 161.07 |

## 判读

- 10k eager 的追加/裁剪 rowSync/op 为 **4.10 ms**，但 CPU-frame p95 仍为 **7419.64 ms**；
  活动行更新分别为 **3.63 ms / 391.59 ms**。
- 同次运行 lazy 的 10k 追加/裁剪分别为 **9.87 ms / 104.32 ms**，活动行更新为
  **9.46 ms / 106.87 ms**。两臂共用此次优化后的同步器，不是旧/新同步算法的 A/B。
- 不同聚合口径不能相减；也不能把跨主机的历史报告当作受控 before/after。
  这些数据支持把下一轮验证重心放在 eager 的 UI 重组/布局路径，而不是只继续压缩 row sync。
- 不能据此认定某个 Compose 内部调用就是根因，也不能宣称真机 FPS 或默认 Lazy 上线达标。
  此次没有实体设备测量。

## 下一项隔离实验

验证 **稳定分块的 eager history**，作为单独标记的候选，与 flat eager 在同次运行配对：

1. 仍组合全部历史行、仍使用 ScrollState/verticalScroll，不使用 LazyColumn。
2. 按稳定 ID 分组，限制单个 Column/key group 的规模；分组只管理组合树，不推算像素坐标。
3. 复用生产行同步/Text，保留真实 ANSI/CJK 行高、完整 active screen、尾部校验、固定距离滚动。
4. 原有 eager/lazy `ab` 保留；分块实验使用独立 suite/renderer 名称，禁止混标或混合结果。
5. 先验证主机分组测试和全部输出更新断言，再收集相同 1k/5k/10k × 5 场景 × 2 臂矩阵。

这仍是 benchmark-only 实验。生产 renderer、IME、选择、鼠标坐标和 viewport owner 暂不改变。
