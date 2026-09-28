# 三臂稳定归档分块与绘制层基准

- 被测提交：`de57926d1f719dce59f5b49e62d78df07ab85dd4`。
- [工作流 #36369972798](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36369972798)：**success**。
- 45 项矩阵全部通过：3 个历史规模 × 5 个场景 × 3 个 renderer，每项 3 次；同一 APK、同一 API 34 x86_64 CI 模拟器内相邻运行并轮换三臂顺序。
- renderer：`eager` 平铺控制、`chunkedEager` 归档序号分块、`chunkedLayers` 相同分块加默认 `graphicsLayer()`。
- 以下表格来自 check-run annotation；时间是 CPU frame p95 或 annotation 标注的中位数，单位 ms，不是 FPS。
- `alternateScreenUpdate` 三臂都使用 24 行平铺物理网格，只作为控制，不计算为 TUI 收益。

## Terminal benchmark alternateScreenUpdate

```text
Terminal alternateScreenUpdate — commit de57926d1f719dce59f5b49e62d78df07ab85dd4; environment=ci-emulator
ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.
History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB
1000 | eager | 3 | — | 0.31 | 0.14 | 69.96 | 95.77 | 0.20 | — | 38.00 | 88.59
1000 | chunkedEager | 3 | — | 0.31 | 0.26 | 59.59 | 86.11 | 0.36 | — | 35.00 | 88.75
1000 | chunkedLayers | 3 | — | 0.62 | 0.16 | 65.70 | 96.62 | 0.19 | — | 38.00 | 88.55
5000 | eager | 3 | — | 1.41 | 0.27 | 67.13 | 89.88 | 0.09 | — | 34.00 | 100.94
5000 | chunkedEager | 3 | — | 0.57 | 0.34 | 70.45 | 96.65 | 0.19 | — | 37.00 | 101.14
5000 | chunkedLayers | 3 | — | 0.49 | 0.13 | 67.45 | 96.30 | 0.20 | — | 35.00 | 101.18
10000 | eager | 3 | — | 0.33 | 0.20 | 71.00 | 91.70 | 0.14 | — | 35.00 | 134.94
10000 | chunkedEager | 3 | — | 0.42 | 0.29 | 64.77 | 94.81 | 0.14 | — | 36.00 | 134.77
10000 | chunkedLayers | 3 | — | 0.30 | 0.16 | 69.50 | 88.81 | 0.13 | — | 35.00 | 134.54
All arms use the same flat 24-row grid here. Differences are NOT a TUI speedup.
```

## Terminal benchmark appendAndTrim

```text
Terminal appendAndTrim — commit de57926d1f719dce59f5b49e62d78df07ab85dd4; environment=ci-emulator
ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.
History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB
1000 | eager | 3 | — | 0.38 | 0.39 | 116.03 | 142.66 | 0.72 | — | 31.00 | 147.62
1000 | chunkedEager | 3 | — | 0.92 | 0.58 | 64.84 | 85.55 | 1.28 | — | 33.00 | 148.35
1000 | chunkedLayers | 3 | — | 0.90 | 0.69 | 62.38 | 83.98 | 0.97 | — | 33.00 | 132.32
5000 | eager | 3 | — | 1.22 | 2.44 | 1667.61 | 1700.67 | 2.18 | — | 31.00 | 250.96
5000 | chunkedEager | 3 | — | 0.99 | 2.04 | 156.19 | 177.81 | 1.32 | — | 30.00 | 249.88
5000 | chunkedLayers | 3 | — | 1.20 | 1.91 | 71.42 | 89.93 | 1.07 | — | 30.00 | 183.93
10000 | eager | 3 | — | 2.49 | 4.78 | 6302.55 | 6435.24 | 4.21 | — | 30.00 | 350.96
10000 | chunkedEager | 3 | — | 2.77 | 5.47 | 291.91 | 333.53 | 1.75 | — | 30.00 | 352.46
10000 | chunkedLayers | 3 | — | 2.27 | 3.66 | 111.49 | 140.02 | 1.61 | — | 31.00 | 281.08
```

## Terminal benchmark activeRowUpdate

```text
Terminal activeRowUpdate — commit de57926d1f719dce59f5b49e62d78df07ab85dd4; environment=ci-emulator
ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.
History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB
1000 | eager | 3 | — | 1.02 | 0.72 | 65.54 | 91.94 | 0.92 | — | 38.00 | 148.30
1000 | chunkedEager | 3 | — | 1.24 | 1.22 | 69.80 | 93.16 | 0.50 | — | 40.00 | 148.80
1000 | chunkedLayers | 3 | — | 1.79 | 0.72 | 72.15 | 98.90 | 0.68 | — | 43.00 | 109.30
5000 | eager | 3 | — | 1.17 | 1.99 | 139.92 | 167.20 | 2.60 | — | 38.00 | 244.90
5000 | chunkedEager | 3 | — | 1.24 | 2.61 | 125.24 | 158.50 | 0.85 | — | 38.00 | 245.26
5000 | chunkedLayers | 3 | — | 3.51 | 7.19 | 72.32 | 92.00 | 3.34 | — | 42.00 | 171.27
10000 | eager | 3 | — | 2.25 | 4.35 | 276.95 | 305.20 | 4.11 | — | 38.00 | 339.09
10000 | chunkedEager | 3 | — | 2.54 | 5.04 | 225.81 | 256.51 | 2.30 | — | 38.00 | 343.89
10000 | chunkedLayers | 3 | — | 6.61 | 11.09 | 84.62 | 109.63 | 3.24 | — | 40.00 | 284.73
```

## Terminal benchmark historyScroll

```text
Terminal historyScroll — commit de57926d1f719dce59f5b49e62d78df07ab85dd4; environment=ci-emulator
ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.
History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB
1000 | eager | 3 | — | — | — | 76.09 | 96.14 | — | — | 55.00 | 115.57
1000 | chunkedEager | 3 | — | — | — | 78.02 | 100.72 | — | — | 52.00 | 116.38
1000 | chunkedLayers | 3 | — | — | — | 76.69 | 95.36 | — | — | 54.00 | 106.09
5000 | eager | 3 | — | — | — | 71.48 | 91.79 | — | — | 47.00 | 196.46
5000 | chunkedEager | 3 | — | — | — | 76.52 | 91.85 | — | — | 53.00 | 196.47
5000 | chunkedLayers | 3 | — | — | — | 80.24 | 96.95 | — | — | 53.00 | 169.56
10000 | eager | 3 | — | — | — | 94.75 | 118.29 | — | — | 31.00 | 288.46
10000 | chunkedEager | 3 | — | — | — | 77.21 | 96.35 | — | — | 51.00 | 311.10
10000 | chunkedLayers | 3 | — | — | — | 82.67 | 99.19 | — | — | 52.00 | 261.46
```

## Terminal benchmark initialCompose

```text
Terminal initialCompose — commit de57926d1f719dce59f5b49e62d78df07ab85dd4; environment=ci-emulator
ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.
History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB
1000 | eager | 3 | 270.73 | 18.11 | 0.73 | 161.59 | 214.03 | 70.55 | 34.34 | 5.00 | 116.72
1000 | chunkedEager | 3 | 255.87 | 20.75 | 0.84 | 126.25 | 189.28 | 77.36 | 35.52 | 5.00 | 116.75
1000 | chunkedLayers | 3 | 270.22 | 24.89 | 1.28 | 103.21 | 196.82 | 118.60 | 16.70 | 4.00 | 103.68
5000 | eager | 3 | 1071.32 | 60.47 | 1.61 | 134.61 | 964.95 | 377.41 | 455.26 | 4.00 | 301.75
5000 | chunkedEager | 3 | 1124.96 | 71.97 | 2.52 | 94.62 | 964.99 | 353.19 | 464.34 | 4.00 | 290.51
5000 | chunkedLayers | 3 | 728.83 | 59.96 | 1.63 | 181.46 | 618.78 | 329.68 | 147.85 | 4.00 | 337.61
10000 | eager | 3 | 2951.53 | 118.59 | 3.42 | 132.55 | 2769.21 | 636.13 | 1766.11 | 4.00 | 467.23
10000 | chunkedEager | 3 | 2864.34 | 135.89 | 2.96 | 135.07 | 2674.56 | 710.81 | 1759.17 | 4.00 | 509.87
10000 | chunkedLayers | 3 | 1356.77 | 130.72 | 3.76 | 142.98 | 1202.10 | 700.15 | 229.24 | 5.00 | 469.10
Few initial frames: inspect mount latency, not p95 alone.
```

## 结论

- 10k `appendAndTrim`：`6435.24 -> 333.53 -> 140.02 ms`，分块消除平铺 keyed 组合组的主要异常开销，绘制层再降低约 58%。
- 10k `activeRowUpdate`：`305.20 -> 256.51 -> 109.63 ms`，绘制层使活动行更新接近模拟器的基线。
- 10k `historyScroll`：`118.29 -> 96.35 -> 99.19 ms`，没有回归信号；三组都保留 ScrollState 和自然高度。
- 10k `initialCompose` mount：`2951.53 -> 2864.34 -> 1356.77 ms`。初始帧只有少量样本，不能把 p95 单独当作启动性能结论。
- 10k `RSS anon`：追加/裁剪 `350.96 -> 352.46 -> 281.08 MiB`，活动行 `339.09 -> 343.89 -> 284.73 MiB`。内存下降需要在实体设备复测。
- 三臂共享 row sync；这些是 renderer 对照，不是旧/新同步器对照。CI 模拟器也不是实体设备。

生产因此启用普通历史的分块绘制层。TUI、alternate screen、full-grid 因没有有效普通历史分块，继续使用原平铺物理网格；LazyColumn 不启用。

## 残余风险

- 未完成实体设备、多轮热状态和长时间持续输出验证。
- benchmark 的 graphicsLayer 是默认显示列表层，不是离屏 bitmap；不同 GPU/系统版本的收益可能不同。
- 仍需依赖回归测试验证选择、IME、恢复锚点和快速连续滑动；本次 244 项终端单元测试与 32 项模拟器布局/手势测试均通过。


## 独立重复运行

- [工作流 #36376311408](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/36376311408)
  在提交 `ed013f0ff56ec4799a34143553cf79b0cfd9a20e` 上再次完成 45 项三臂矩阵。
- 该提交只把生产 `ProcessSessionPage` 的 chunk 计划放进 `remember`；隔离 benchmark 直接接收布局计划，
  所以这不是缓存优化的 before/after。不同 CI 主机的数据也不能相减。
- 定性结论重复：10k `appendAndTrim` CPU p95 为 `7565.36 / 427.19 / 182.53 ms`，
  10k `activeRowUpdate` 为 `394.96 / 358.79 / 112.23 ms`（flat / chunks / chunks+layers）。
  三臂完整性、尾部校验和各场景 annotation 均通过。
