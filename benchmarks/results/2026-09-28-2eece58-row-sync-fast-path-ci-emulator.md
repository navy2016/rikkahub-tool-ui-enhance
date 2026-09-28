# 活动屏幕增量 row-sync 快路径

- 被测提交：`2eece58b236deee89295c3e7fef908eb0c214535`。
- [工作流 #36386263073](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36386263073)：**success**。
- 45 项矩阵全部通过：3 个历史规模 × 5 个场景 × 3 个 renderer，每项 3 次；同一 APK、同一 API 34 x86_64 CI 模拟器，三臂顺序轮换。
- [终端回归 #36386263044](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36386263044)：248/248；
  [视口回归 #36386263068](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36386263068)：32/32。
- renderer：`eager` 平铺控制、`chunkedEager` 归档序号分块、`chunkedLayers` 分块加绘制层。
- 时间单位 ms；CPU frame p95 或 annotation 标注的中位数，不是 FPS；RSS anon 不是总 RSS/PSS。

增量 row-sync 元数据在共享生产同步器和 benchmark target 中启用。活动屏幕文本更新只访问 24 个物理屏幕行；
历史结构变化、归档裁剪、调色板/默认颜色/reverse-video、列 resize 和不完整元数据都回退完整同步。
benchmark fixture 对访问行数和回退状态有硬断言，不仅依赖耗时推断。

## Terminal benchmark alternateScreenUpdate

```text
Terminal alternateScreenUpdate — commit 2eece58b236deee89295c3e7fef908eb0c214535; environment=ci-emulator
ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.
History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB
1000 | eager | 3 | — | 0.53 | 0.27 | 76.57 | 98.19 | 0.15 | — | 38.00 | 88.35
1000 | chunkedEager | 3 | — | 0.60 | 0.12 | 69.31 | 99.16 | 0.09 | — | 37.00 | 88.41
1000 | chunkedLayers | 3 | — | 0.44 | 0.10 | 69.30 | 99.88 | 0.23 | — | 36.00 | 88.64
5000 | eager | 3 | — | 0.43 | 0.21 | 69.96 | 92.85 | 0.21 | — | 37.00 | 101.04
5000 | chunkedEager | 3 | — | 0.56 | 0.13 | 67.88 | 100.65 | 0.09 | — | 35.00 | 101.23
5000 | chunkedLayers | 3 | — | 0.36 | 0.14 | 75.54 | 94.64 | 0.12 | — | 33.00 | 100.92
10000 | eager | 3 | — | 0.33 | 0.12 | 70.55 | 93.37 | 0.08 | — | 35.00 | 134.81
10000 | chunkedEager | 3 | — | 0.51 | 0.14 | 72.89 | 94.93 | 0.33 | — | 36.00 | 134.79
10000 | chunkedLayers | 3 | — | 0.46 | 0.14 | 70.00 | 94.17 | 0.09 | — | 35.00 | 134.80
All arms use the same flat 24-row grid here. Differences are NOT a TUI speedup.
```

## Terminal benchmark appendAndTrim

```text
Terminal appendAndTrim — commit 2eece58b236deee89295c3e7fef908eb0c214535; environment=ci-emulator
ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.
History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB
1000 | eager | 3 | — | 0.56 | 0.57 | 123.80 | 149.31 | 0.79 | — | 32.00 | 148.24
1000 | chunkedEager | 3 | — | 0.62 | 0.50 | 66.08 | 94.87 | 0.38 | — | 31.00 | 148.39
1000 | chunkedLayers | 3 | — | 1.04 | 0.89 | 61.71 | 92.74 | 0.56 | — | 32.00 | 132.97
5000 | eager | 3 | — | 1.38 | 1.95 | 1653.13 | 1732.55 | 2.32 | — | 31.00 | 248.12
5000 | chunkedEager | 3 | — | 1.52 | 1.92 | 169.85 | 208.84 | 0.88 | — | 32.00 | 248.88
5000 | chunkedLayers | 3 | — | 0.93 | 1.73 | 77.79 | 99.78 | 1.06 | — | 32.00 | 200.84
10000 | eager | 3 | — | 2.03 | 3.55 | 6318.45 | 6441.78 | 4.07 | — | 31.00 | 348.50
10000 | chunkedEager | 3 | — | 3.35 | 4.08 | 303.49 | 343.49 | 1.55 | — | 31.00 | 346.85
10000 | chunkedLayers | 3 | — | 2.00 | 2.83 | 126.30 | 155.35 | 1.56 | — | 31.00 | 279.46
```

## Terminal benchmark activeRowUpdate

```text
Terminal activeRowUpdate — commit 2eece58b236deee89295c3e7fef908eb0c214535; environment=ci-emulator
ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.
History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB
1000 | eager | 3 | — | 1.07 | 0.17 | 71.11 | 109.99 | 0.69 | — | 41.00 | 148.30
1000 | chunkedEager | 3 | — | 1.08 | 0.71 | 69.52 | 99.04 | 0.30 | — | 39.00 | 149.09
1000 | chunkedLayers | 3 | — | 1.63 | 0.15 | 79.13 | 98.02 | 0.54 | — | 41.00 | 108.50
5000 | eager | 3 | — | 1.15 | 0.12 | 143.07 | 175.24 | 2.60 | — | 38.00 | 245.29
5000 | chunkedEager | 3 | — | 1.36 | 0.11 | 130.43 | 159.90 | 0.90 | — | 39.00 | 265.93
5000 | chunkedLayers | 3 | — | 4.64 | 0.19 | 75.68 | 96.96 | 1.80 | — | 43.00 | 168.60
10000 | eager | 3 | — | 2.20 | 0.13 | 281.83 | 329.44 | 4.16 | — | 38.00 | 356.93
10000 | chunkedEager | 3 | — | 2.34 | 0.33 | 229.51 | 284.06 | 1.87 | — | 40.00 | 343.65
10000 | chunkedLayers | 3 | — | 8.97 | 0.59 | 82.42 | 109.20 | 5.94 | — | 42.00 | 280.77
```

## Terminal benchmark historyScroll

```text
Terminal historyScroll — commit 2eece58b236deee89295c3e7fef908eb0c214535; environment=ci-emulator
ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.
History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB
1000 | eager | 3 | — | — | — | 80.79 | 99.42 | — | — | 51.00 | 115.33
1000 | chunkedEager | 3 | — | — | — | 81.17 | 104.04 | — | — | 50.00 | 115.25
1000 | chunkedLayers | 3 | — | — | — | 80.46 | 101.84 | — | — | 53.00 | 105.79
5000 | eager | 3 | — | — | — | 74.39 | 104.08 | — | — | 45.00 | 195.86
5000 | chunkedEager | 3 | — | — | — | 78.33 | 101.06 | — | — | 52.00 | 195.51
5000 | chunkedLayers | 3 | — | — | — | 80.81 | 100.46 | — | — | 53.00 | 164.77
10000 | eager | 3 | — | — | — | 95.53 | 112.32 | — | — | 30.00 | 285.85
10000 | chunkedEager | 3 | — | — | — | 79.64 | 103.37 | — | — | 50.00 | 287.90
10000 | chunkedLayers | 3 | — | — | — | 84.91 | 104.44 | — | — | 49.00 | 263.78
```

## Terminal benchmark initialCompose

```text
Terminal initialCompose — commit 2eece58b236deee89295c3e7fef908eb0c214535; environment=ci-emulator
ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.
History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB
1000 | eager | 3 | 313.02 | 29.77 | 0.88 | 135.95 | 235.37 | 123.39 | 41.49 | 4.00 | 116.61
1000 | chunkedEager | 3 | 282.92 | 24.99 | 1.38 | 105.99 | 227.85 | 106.37 | 42.02 | 4.00 | 116.59
1000 | chunkedLayers | 3 | 262.71 | 21.23 | 1.06 | 139.80 | 209.21 | 116.23 | 18.72 | 5.00 | 103.58
5000 | eager | 3 | 1357.13 | 91.61 | 1.75 | 131.60 | 1282.54 | 392.79 | 634.75 | 4.00 | 250.10
5000 | chunkedEager | 3 | 1315.44 | 76.93 | 2.06 | 140.04 | 1188.24 | 416.06 | 615.39 | 5.00 | 259.27
5000 | chunkedLayers | 3 | 817.62 | 85.31 | 1.71 | 125.47 | 661.75 | 408.41 | 103.72 | 5.00 | 251.71
10000 | eager | 3 | 3812.51 | 125.91 | 2.72 | 129.11 | 3628.89 | 786.29 | 2489.80 | 5.00 | 449.50
10000 | chunkedEager | 3 | 3727.59 | 112.65 | 2.85 | 134.11 | 3561.10 | 800.81 | 2516.80 | 5.00 | 456.14
10000 | chunkedLayers | 3 | 1513.54 | 130.81 | 3.00 | 146.02 | 1428.27 | 790.63 | 214.37 | 4.00 | 455.27
Few initial frames: inspect mount latency, not p95 alone.
```

## 结论

- 10k `activeRowUpdate` 的 rowSync/op：eager `0.13`、chunkedEager `0.33`、chunkedLayers `0.59`；
  对照前一轮约 4ms 级别的全历史同步，说明活动屏幕更新已跳过历史 Text 扫描。
- 10k `activeRowUpdate` CPU p95：eager `329.44`、chunkedEager `284.06`、chunkedLayers `109.20`。
  快路径降低的是 row-sync，不把剩余 UI 绘制成本误报成同步收益。
- 10k `appendAndTrim` CPU p95：eager `6441.78`、chunkedEager `343.49`、chunkedLayers `155.35`；
  追加/裁剪没有错误使用快路径，分块和绘制层收益保持。
- 10k `historyScroll` CPU p95：eager `112.32`、chunkedEager `103.37`、chunkedLayers `104.44`，没有回归信号。
- alternate screen 三臂都保持 24 行平铺物理网格；差异不解释为 TUI 优化。
- 本次只在 CI 模拟器上测量，仍需实体设备/不同 GPU 复测；不启用 LazyColumn。
