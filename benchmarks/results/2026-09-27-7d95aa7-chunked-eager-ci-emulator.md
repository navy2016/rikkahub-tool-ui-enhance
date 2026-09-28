# 稳定分块 eager 初次对照与 lazy 回归

- 被测提交：`7d95aa743308fddc6563f1ba84627b366388c19f`。
- 两次工作流均通过各自的 30 项矩阵（3 历史规模 × 5 场景 × 2 renderer），每项 3 次。
- API 34 x86_64、Nexus 6 profile、2 cores / 4 GiB RAM / 768 MiB heap / SwiftShader，`CompilationMode.Full()`。
- 每次运行内部使用同一 APK、同一设备、相邻两臂、交替顺序。两次运行属于不同 CI 主机，**不能把三种 renderer 的数字跨运行拼成同机三组对比**。
- 以下数据来自 check-run 的 `Terminal rendering hot paths` annotation。本环境访问 artifact/log 的 blob 存储 TLS 超时；
  因此只归档能读取的 12 项热点/运行，**没有复核本次 initialCompose、historyScroll 和 alternateScreenUpdate 的具体数值**。
- 时间单位 ms；rowSync/op 是逐 iteration 的 sum/count 再取中位数，max 是逐 iteration 最大值的中位数；
  CPU p95 是采集帧的分位数。不同聚合口径不能相减。RSS anon 不是总 RSS/PSS，数据不是实体设备 FPS。

## 同次运行：eager / chunkedEager

- [工作流 #36326784216](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36326784216)：**success**。
- Artifact ID `10934852137`，名称 `terminal-scrollback-benchmark-chunkedEager-7d95aa743308fddc6563f1ba84627b366388c19f`，保留 14 天。

| History | Scenario | Renderer | n | rowSync/op | rowSync max | CPU-frame p95 | RSS anon MiB |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1000 | activeRowUpdate | eager | 3 | 0.50 | 1.99 | 103.36 | 147.96 |
| 1000 | activeRowUpdate | chunkedEager | 3 | 1.47 | 20.46 | 105.94 | 148.16 |
| 1000 | appendAndTrim | eager | 3 | 0.42 | 0.93 | 163.79 | 147.45 |
| 1000 | appendAndTrim | chunkedEager | 3 | 0.63 | 2.95 | 93.67 | 149.59 |
| 5000 | activeRowUpdate | eager | 3 | 2.32 | 6.54 | 216.02 | 257.99 |
| 5000 | activeRowUpdate | chunkedEager | 3 | 2.87 | 21.56 | 198.01 | 249.14 |
| 5000 | appendAndTrim | eager | 3 | 2.32 | 7.27 | 1997.56 | 254.02 |
| 5000 | appendAndTrim | chunkedEager | 3 | 2.19 | 7.52 | 223.45 | 252.80 |
| 10000 | activeRowUpdate | eager | 3 | 3.83 | 7.80 | 375.47 | 343.48 |
| 10000 | activeRowUpdate | chunkedEager | 3 | 6.48 | 14.58 | 323.80 | 349.27 |
| 10000 | appendAndTrim | eager | 3 | 5.31 | 18.96 | 7414.46 | 345.08 |
| 10000 | appendAndTrim | chunkedEager | 3 | 4.48 | 11.39 | 394.85 | 343.86 |

## 同次运行：eager / lazyHistory

- [工作流 #36326773389](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36326773389)：**success**。
- Artifact ID `10935266475`，名称 `terminal-scrollback-benchmark-lazyHistory-7d95aa743308fddc6563f1ba84627b366388c19f`，保留 14 天。

| History | Scenario | Renderer | n | rowSync/op | rowSync max | CPU-frame p95 | RSS anon MiB |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1000 | activeRowUpdate | eager | 3 | 0.60 | 3.32 | 110.87 | 148.70 |
| 1000 | activeRowUpdate | lazyHistory | 3 | 0.84 | 9.67 | 110.51 | 91.63 |
| 1000 | appendAndTrim | eager | 3 | 0.58 | 3.64 | 156.34 | 147.39 |
| 1000 | appendAndTrim | lazyHistory | 3 | 1.82 | 25.26 | 107.06 | 93.79 |
| 5000 | activeRowUpdate | eager | 3 | 1.98 | 5.69 | 221.62 | 255.46 |
| 5000 | activeRowUpdate | lazyHistory | 3 | 4.02 | 18.04 | 105.72 | 112.42 |
| 5000 | appendAndTrim | eager | 3 | 2.18 | 9.81 | 1982.39 | 246.06 |
| 5000 | appendAndTrim | lazyHistory | 3 | 7.48 | 43.07 | 101.62 | 115.34 |
| 10000 | activeRowUpdate | eager | 3 | 4.03 | 12.42 | 381.72 | 344.03 |
| 10000 | activeRowUpdate | lazyHistory | 3 | 8.46 | 38.98 | 105.35 | 157.09 |
| 10000 | appendAndTrim | eager | 3 | 4.27 | 11.92 | 7415.15 | 344.02 |
| 10000 | appendAndTrim | lazyHistory | 3 | 10.33 | 38.35 | 108.07 | 161.20 |

## 判读与限制

- 在 **chunkedEager 同次运行**内，追加/裁剪 CPU-frame p95：
  1k `163.79 → 93.67 ms`，5k `1997.56 → 223.45 ms`，10k `7414.46 → 394.85 ms`。
  10k 比值约 `18.78×`，耗时下降约 `94.7%`，不是 FPS 提升倍数。
- 活动行更新改善较小；10k `375.47 → 323.80 ms`。匿名 RSS 仍约 344 MiB，分块保留所有行，没有虚拟化节省。
- 两臂共享同步器，并非旧/新 row-sync A/B。1k 分块活动行的同步均值/峰值反而更高，
  没有置信区间或原始 trace，不能直接把这些差异判成噪声或忽略。
- 数据支持优先消除大范围 keyed 组合组的头删/尾增成本；“Compose key 移动记账近似平方增长”
  以及“剩余成本主要来自整层重录制”都是待进一步验证的解释，尚未用本次 Perfetto 证实。
- 本次实验仍按 `floorDiv(lineId, 128)` 分块，ID 非严格递增时退回平铺。
  生产集成改用独立 FIFO 归档序号，避免真实 screen 插行/反向滚动/稀疏 ID 破坏分组。

## 下一轮验证

1. 将分块实现放到生产共用 transcript helper；普通历史使用分块，TUI/alternate/full-grid 保持平铺。
   原 `ScrollState`、选择容器、单一滚动执行器、快速连滑跳转和行 ID 锚点不变。
2. 独立绘制层只做实验：同一次 `chunked-layers` suite 比较 **flat eager / archival chunks / identical chunks + layers**，
   45 项矩阵、每项 3 次、三臂轮换顺序。不能拿本报告冒充这项新实验的收益。
3. 以真实 Text 的模拟器测试核对每行位置/大小、滚动范围、横向位移、选择容器、字体缩放、尺寸/RTL 与 renderer 切换；
   保留 24 项真实手势测试，补 FIFO 序号与分块主机测试。
4. 每个场景单独发布完整 annotation，后续无需下载 blob 也能复核首屏、滚动、备用屏幕及 measure/draw 数据。

尚未进行实体设备测试，也未据此开启生产 LazyColumn 或独立绘制层。
