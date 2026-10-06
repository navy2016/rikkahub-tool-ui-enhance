# a79e7b6：键盘稳定虚拟历史完整 V3 基线

`a79e7b6` 仅在已验证的应用提交 `2b0f259` 之上归档文档；应用、生产视口、基准驱动、字体和
构建输入完全相同。默认仍为 `CHUNKED_LAYERS`，`lazyHistoryIme` 仍是状态栏 `RENDER` 中按命令
显式选择的“虚拟历史·键盘稳定”，不改变原虚拟模式、状态栏其他功能、KEYS 或 PTY 语义。

完整矩阵确认了新模式的适用边界。10k 历史下：

| 场景 | 默认 | 原虚拟 | 键盘稳定虚拟 | 结论 |
| --- | ---: | ---: | ---: | --- |
| 首次组合 | 3364.35 ms | 811.86 ms | 1188.77 ms | 虚拟模式避免创建完整 eager 行树，但冷宽度扫描仍很重 |
| 30 次活动行更新 | 4609.23 ms | 4945.35 ms | 4896.77 ms | 默认略快；新模式不适合宣称所有场景都更快 |
| 30 次追加／裁剪 | 6377.37 ms | 5708.21 ms | 5818.67 ms | 两种虚拟模式均降低长历史追加成本 |
| 顶部／尾部语义跳转 | 1471.94 ms | 984.84 ms | 956.87 ms | 虚拟跳转避免 eager 长历史滚动成本 |
| 完整 IME 往返 | 3778.21 ms | 10658.95 ms | 3708.66 ms | 新模式消除原虚拟模式的全量 eager 回退 |
| 分离／恢复 | 7347.65 ms | 1470.78 ms | 1732.31 ms | 虚拟恢复显著低于 eager，但冷宽度扫描仍占主要部分 |

以上每行只能在所属场景的同一设备／调用内比较。场景之间使用独立 Runner，不合并样本，也不据此
宣称实机 FPS。`lazyHistory` 与 `lazyHistoryIme` 在非 IME 条件下共享同一虚拟渲染实现；两者在
首屏、追加和恢复中的差异不能归因于新策略，应视为顺序、设备状态和三次样本波动。

来源：[37456883236](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37456883236)；SHA：`a79e7b693d42f22a086959e9fac0525b88515663`；协议：`production-viewport-v3`。

6 场景 × 三种历史长度 × 3 种生产模式 = 54 个用例，每例 3 次，共 162 次测量；全部成功。
每个场景内共享 APK、设备和仪器调用；不同场景／Runner／协议的帧样本不得合并，也不计算跨运行提升率。

原始设备／输入法上下文、源文件哈希和 APK 哈希由[只读导出](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37459976695)传回并校验完整性，保存在同名 JSON。
目标 APK SHA-256：`c9682da6eff63fab2ebe1cf7ed2f83f3af02bf2ebf0486629a4fedb39b647356`。

表格来源为通过完整校验后的 CI 摘要，保留两位小数；不是原始 Perfetto 帧样本。缺失项为 —，不补零。
时间单位 ms；RSS 为匿名 RSS 采样峰值的迭代中位数（MiB），不是 PSS／Java／Native／GPU 分项峰值。
输出/次包含生产协调和两次稳定绘制确认；不是无限速 PTY 吞吐。各阶段重叠，不相减分解耗时。

| 场景 | 历史行 | 模式 | 次数 | 首屏 | 总操作 | 输出/次 | CPU p95 | 宽度/次 | RSS anon |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| initialCompose | 1000 | chunkedLayers | 3 | 336.52 | — | — | 173.83 | — | 103.25 |
| initialCompose | 1000 | lazyHistory | 3 | 335.33 | — | — | 145.19 | 16.50 | 93.52 |
| initialCompose | 1000 | lazyHistoryIme | 3 | 313.55 | — | — | 148.68 | 17.02 | 93.57 |
| initialCompose | 5000 | chunkedLayers | 3 | 2162.44 | — | — | 1976.70 | — | 246.15 |
| initialCompose | 5000 | lazyHistory | 3 | 897.75 | — | — | 673.69 | 443.20 | 234.54 |
| initialCompose | 5000 | lazyHistoryIme | 3 | 770.81 | — | — | 568.36 | 344.73 | 225.03 |
| initialCompose | 10000 | chunkedLayers | 3 | 3364.35 | — | — | 149.88 | — | 502.34 |
| initialCompose | 10000 | lazyHistory | 3 | 811.86 | — | — | 459.00 | 420.02 | 204.93 |
| initialCompose | 10000 | lazyHistoryIme | 3 | 1188.77 | — | — | 930.35 | 711.68 | 363.73 |
| activeRowUpdate | 1000 | chunkedLayers | 3 | — | 4615.02 | 153.83 | 120.99 | — | 105.98 |
| activeRowUpdate | 1000 | lazyHistory | 3 | — | 4801.23 | 160.04 | 111.17 | 3.61 | 101.12 |
| activeRowUpdate | 1000 | lazyHistoryIme | 3 | — | 4839.73 | 161.32 | 119.08 | 2.56 | 101.10 |
| activeRowUpdate | 5000 | chunkedLayers | 3 | — | 4477.63 | 149.25 | 113.69 | — | 163.85 |
| activeRowUpdate | 5000 | lazyHistory | 3 | — | 4978.26 | 165.94 | 114.28 | 2.50 | 121.73 |
| activeRowUpdate | 5000 | lazyHistoryIme | 3 | — | 5034.51 | 167.81 | 116.45 | 2.25 | 140.16 |
| activeRowUpdate | 10000 | chunkedLayers | 3 | — | 4609.23 | 153.64 | 115.38 | — | 256.45 |
| activeRowUpdate | 10000 | lazyHistory | 3 | — | 4945.35 | 164.84 | 115.22 | 2.84 | 155.40 |
| activeRowUpdate | 10000 | lazyHistoryIme | 3 | — | 4896.77 | 163.22 | 113.55 | 3.14 | 201.43 |
| appendAndTrim | 1000 | chunkedLayers | 3 | — | 4647.58 | 154.92 | 100.48 | — | 112.51 |
| appendAndTrim | 1000 | lazyHistory | 3 | — | 5625.54 | 187.51 | 103.57 | 2.37 | 104.62 |
| appendAndTrim | 1000 | lazyHistoryIme | 3 | — | 5718.46 | 190.61 | 106.25 | 3.90 | 104.59 |
| appendAndTrim | 5000 | chunkedLayers | 3 | — | 5361.05 | 178.70 | 110.05 | — | 187.59 |
| appendAndTrim | 5000 | lazyHistory | 3 | — | 5641.02 | 188.03 | 105.66 | 2.59 | 140.23 |
| appendAndTrim | 5000 | lazyHistoryIme | 3 | — | 5706.53 | 190.21 | 103.59 | 2.08 | 143.97 |
| appendAndTrim | 10000 | chunkedLayers | 3 | — | 6377.37 | 212.58 | 131.53 | — | 325.49 |
| appendAndTrim | 10000 | lazyHistory | 3 | — | 5708.21 | 190.27 | 102.79 | 2.07 | 166.44 |
| appendAndTrim | 10000 | lazyHistoryIme | 3 | — | 5818.67 | 193.95 | 103.88 | 3.14 | 162.45 |
| semanticJump | 1000 | chunkedLayers | 3 | — | 1352.33 | — | 124.93 | — | 104.00 |
| semanticJump | 1000 | lazyHistory | 3 | — | 1007.47 | — | 185.54 | — | 109.25 |
| semanticJump | 1000 | lazyHistoryIme | 3 | — | 899.03 | — | 135.68 | — | 109.15 |
| semanticJump | 5000 | chunkedLayers | 3 | — | 1426.80 | — | 124.17 | — | 163.13 |
| semanticJump | 5000 | lazyHistory | 3 | — | 1124.07 | — | 184.98 | — | 131.04 |
| semanticJump | 5000 | lazyHistoryIme | 3 | — | 982.93 | — | 154.79 | — | 131.82 |
| semanticJump | 10000 | chunkedLayers | 3 | — | 1471.94 | — | 128.24 | — | 266.29 |
| semanticJump | 10000 | lazyHistory | 3 | — | 984.84 | — | 159.08 | — | 177.78 |
| semanticJump | 10000 | lazyHistoryIme | 3 | — | 956.87 | — | 142.92 | — | 162.77 |
| imeRoundTrip | 1000 | chunkedLayers | 3 | — | 3671.57 | 195.16 | 205.38 | — | 105.26 |
| imeRoundTrip | 1000 | lazyHistory | 3 | — | 4274.25 | 204.61 | 291.77 | 3.82 | 127.09 |
| imeRoundTrip | 1000 | lazyHistoryIme | 3 | — | 3845.01 | 203.47 | 213.91 | 1.29 | 97.73 |
| imeRoundTrip | 5000 | chunkedLayers | 3 | — | 3783.78 | 197.83 | 217.80 | — | 163.73 |
| imeRoundTrip | 5000 | lazyHistory | 3 | — | 7518.16 | 198.19 | 195.10 | 0.71 | 223.32 |
| imeRoundTrip | 5000 | lazyHistoryIme | 3 | — | 3803.17 | 218.62 | 218.46 | 1.97 | 136.67 |
| imeRoundTrip | 10000 | chunkedLayers | 3 | — | 3778.21 | 207.13 | 244.14 | — | 269.39 |
| imeRoundTrip | 10000 | lazyHistory | 3 | — | 10658.95 | 197.99 | 208.96 | 0.69 | 328.36 |
| imeRoundTrip | 10000 | lazyHistoryIme | 3 | — | 3708.66 | 199.62 | 199.64 | 1.98 | 150.64 |
| detachRestore | 1000 | chunkedLayers | 3 | — | 720.00 | — | 492.20 | — | 136.31 |
| detachRestore | 1000 | lazyHistory | 3 | — | 491.85 | — | 201.73 | 72.40 | 101.92 |
| detachRestore | 1000 | lazyHistoryIme | 3 | — | 423.44 | — | 172.73 | 49.78 | 101.73 |
| detachRestore | 5000 | chunkedLayers | 3 | — | 3663.29 | — | 1374.45 | — | 245.74 |
| detachRestore | 5000 | lazyHistory | 3 | — | 1094.74 | — | 914.93 | 723.33 | 144.39 |
| detachRestore | 5000 | lazyHistoryIme | 3 | — | 863.60 | — | 629.19 | 490.89 | 144.10 |
| detachRestore | 10000 | chunkedLayers | 3 | — | 7347.65 | — | 238.38 | — | 458.43 |
| detachRestore | 10000 | lazyHistory | 3 | — | 1470.78 | — | 1197.30 | 1099.53 | 180.07 |
| detachRestore | 10000 | lazyHistoryIme | 3 | — | 1732.31 | — | 1493.82 | 1359.00 | 205.08 |

## initialCompose — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | — | 15.86 | 2.20 | — | — | 0.21 | 63.24 | 2.42 | 0.30 |
| 1000 | lazyHistory | — | 15.48 | 2.95 | 16.50 | — | 0.15 | 3.48 | 0.06 | 44.65 |
| 1000 | lazyHistoryIme | — | 13.95 | 3.80 | 17.02 | — | 0.13 | 4.49 | 0.07 | 37.40 |
| 5000 | chunkedLayers | — | 94.19 | 2.93 | — | — | 0.18 | 824.75 | 123.98 | 0.30 |
| 5000 | lazyHistory | — | 108.25 | 4.39 | 443.20 | — | 0.21 | 17.58 | 0.17 | 49.55 |
| 5000 | lazyHistoryIme | — | 126.57 | 7.79 | 344.73 | — | 0.54 | 13.52 | 0.15 | 40.89 |
| 10000 | chunkedLayers | — | 94.69 | 5.19 | — | — | 0.17 | 1880.64 | 125.23 | 0.33 |
| 10000 | lazyHistory | — | 51.84 | 3.78 | 420.02 | — | 0.13 | 35.77 | 0.13 | 53.73 |
| 10000 | lazyHistoryIme | — | 79.45 | 5.02 | 711.68 | — | 0.14 | 17.40 | 0.17 | 56.66 |

## activeRowUpdate — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.11 | 0.75 | 0.12 | — | — | 0.03 | — | 0.01 | — |
| 1000 | lazyHistory | 0.07 | 0.29 | 0.10 | 3.61 | — | 0.11 | — | 0.01 | 73.17 |
| 1000 | lazyHistoryIme | 0.07 | 0.47 | 0.10 | 2.56 | — | 0.11 | — | 0.01 | 85.14 |
| 5000 | chunkedLayers | 0.17 | 0.24 | 0.11 | — | — | 0.03 | — | 0.01 | — |
| 5000 | lazyHistory | 0.13 | 0.41 | 0.10 | 2.50 | — | 0.10 | — | 0.01 | 76.52 |
| 5000 | lazyHistoryIme | 0.08 | 0.36 | 0.09 | 2.25 | — | 0.10 | — | 0.01 | 63.53 |
| 10000 | chunkedLayers | 0.18 | 0.51 | 0.11 | — | — | 0.03 | — | 0.01 | — |
| 10000 | lazyHistory | 0.07 | 0.36 | 0.10 | 2.84 | — | 0.14 | — | 0.01 | 76.05 |
| 10000 | lazyHistoryIme | 0.08 | 0.38 | 0.11 | 3.14 | — | 0.14 | — | 0.01 | 71.17 |

## appendAndTrim — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.09 | 0.36 | 0.34 | — | — | 0.03 | — | 0.01 | — |
| 1000 | lazyHistory | 0.07 | 0.26 | 0.25 | 2.37 | — | 0.17 | — | 0.01 | 79.67 |
| 1000 | lazyHistoryIme | 0.07 | 0.25 | 0.27 | 3.90 | — | 0.13 | — | 0.01 | 71.70 |
| 5000 | chunkedLayers | 0.09 | 0.37 | 0.46 | — | — | 0.03 | — | 0.01 | — |
| 5000 | lazyHistory | 0.07 | 0.60 | 0.30 | 2.59 | — | 0.15 | — | 0.01 | 80.46 |
| 5000 | lazyHistoryIme | 0.07 | 0.49 | 0.70 | 2.08 | — | 0.19 | — | 0.01 | 68.52 |
| 10000 | chunkedLayers | 0.09 | 0.81 | 0.30 | — | — | 0.03 | — | 0.01 | — |
| 10000 | lazyHistory | 0.07 | 0.47 | 0.23 | 2.07 | — | 0.15 | — | 0.01 | 87.45 |
| 10000 | lazyHistoryIme | 0.07 | 0.34 | 0.56 | 3.14 | — | 0.30 | — | 0.01 | 88.34 |

## semanticJump — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | — | — | — | — | — | 0.02 | — | 0.02 | 566.27 |
| 1000 | lazyHistory | — | — | — | — | — | 0.05 | — | 0.01 | 431.85 |
| 1000 | lazyHistoryIme | — | — | — | — | — | 0.05 | — | 0.01 | 339.46 |
| 5000 | chunkedLayers | — | — | — | — | — | 0.02 | — | 0.01 | 608.42 |
| 5000 | lazyHistory | — | — | — | — | — | 0.04 | — | 0.01 | 466.53 |
| 5000 | lazyHistoryIme | — | — | — | — | — | 0.05 | — | 0.01 | 408.10 |
| 10000 | chunkedLayers | — | — | — | — | — | 0.02 | — | 0.01 | 629.82 |
| 10000 | lazyHistory | — | — | — | — | — | 0.05 | — | 0.01 | 422.19 |
| 10000 | lazyHistoryIme | — | — | — | — | — | 0.05 | — | 0.01 | 461.37 |

### Transitions (ms)

| History | Mode | jumpTop | jumpTail |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 676.06 | 628.77 |
| 1000 | lazyHistory | 446.00 | 507.79 |
| 1000 | lazyHistoryIme | 464.15 | 434.86 |
| 5000 | chunkedLayers | 717.24 | 709.54 |
| 5000 | lazyHistory | 566.54 | 521.23 |
| 5000 | lazyHistoryIme | 477.82 | 546.00 |
| 10000 | chunkedLayers | 724.72 | 735.78 |
| 10000 | lazyHistory | 523.99 | 456.20 |
| 10000 | lazyHistoryIme | 430.80 | 415.25 |

## imeRoundTrip — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.07 | 4.70 | 0.16 | — | — | 0.04 | 0.20 | 0.01 | — |
| 1000 | lazyHistory | 0.08 | 0.42 | 0.12 | 3.82 | 0.05 | 0.11 | 60.68 | 1.80 | 0.04 |
| 1000 | lazyHistoryIme | 0.09 | 0.25 | 0.13 | 1.29 | — | 0.07 | 1.33 | 0.03 | — |
| 5000 | chunkedLayers | 0.08 | 0.20 | 0.13 | — | — | 0.04 | 1.39 | 0.01 | — |
| 5000 | lazyHistory | 0.07 | 0.20 | 0.12 | 0.71 | 0.20 | 0.37 | 298.97 | 26.25 | 0.05 |
| 5000 | lazyHistoryIme | 0.08 | 0.20 | 0.43 | 1.97 | — | 0.07 | 2.75 | 0.04 | — |
| 10000 | chunkedLayers | 0.08 | 0.84 | 0.17 | — | — | 0.04 | 2.82 | 0.01 | — |
| 10000 | lazyHistory | 0.08 | 0.20 | 0.11 | 0.69 | 0.10 | 0.17 | 371.57 | 8.04 | 0.04 |
| 10000 | lazyHistoryIme | 0.08 | 0.24 | 0.14 | 1.98 | — | 0.09 | 1.45 | 0.04 | — |

### Transitions (ms)

| History | Mode | imeShow | imeHide | explicitRetry |
| ---: | --- | ---: | ---: | ---: |
| 1000 | chunkedLayers | 1099.67 | 835.79 | 194.77 |
| 1000 | lazyHistory | 1521.04 | 818.79 | 309.09 |
| 1000 | lazyHistoryIme | 1106.02 | 914.99 | 181.43 |
| 5000 | chunkedLayers | 1267.33 | 753.87 | 177.88 |
| 5000 | lazyHistory | 4767.81 | 772.33 | 317.85 |
| 5000 | lazyHistoryIme | 1245.98 | 882.53 | 172.97 |
| 10000 | chunkedLayers | 1113.81 | 868.16 | 195.63 |
| 10000 | lazyHistory | 7727.75 | 893.98 | 375.24 |
| 10000 | lazyHistoryIme | 1104.87 | 769.12 | 171.31 |

## detachRestore — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.55 | 0.61 | 3.98 | — | — | 0.10 | 223.63 | 13.21 | 0.09 |
| 1000 | lazyHistory | 1.48 | 0.50 | 0.97 | 72.40 | — | 0.09 | 16.96 | 0.13 | 137.45 |
| 1000 | lazyHistoryIme | 0.87 | 0.35 | 0.82 | 49.78 | — | 0.10 | 15.38 | 0.24 | 129.30 |
| 5000 | chunkedLayers | 0.55 | 0.32 | 4.99 | — | — | 0.18 | 2292.34 | 103.30 | 0.06 |
| 5000 | lazyHistory | 0.58 | 0.33 | 5.48 | 723.33 | — | 0.11 | 11.26 | 0.17 | 128.72 |
| 5000 | lazyHistoryIme | 0.55 | 0.35 | 5.59 | 490.89 | — | 0.12 | 12.78 | 0.15 | 143.92 |
| 10000 | chunkedLayers | 3.84 | 0.34 | 19.75 | — | — | 0.42 | 4133.37 | 310.21 | 0.07 |
| 10000 | lazyHistory | 0.53 | 0.37 | 13.56 | 1099.53 | — | 0.12 | 78.31 | 0.31 | 145.99 |
| 10000 | lazyHistoryIme | 0.81 | 0.45 | 18.88 | 1359.00 | — | 0.16 | 32.96 | 0.18 | 132.82 |

### Transitions (ms)

| History | Mode | detach | restore |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 54.12 | 665.27 |
| 1000 | lazyHistory | 48.18 | 440.19 |
| 1000 | lazyHistoryIme | 51.01 | 370.70 |
| 5000 | chunkedLayers | 117.04 | 3552.10 |
| 5000 | lazyHistory | 47.59 | 1050.05 |
| 5000 | lazyHistoryIme | 47.41 | 815.53 |
| 10000 | chunkedLayers | 249.05 | 7089.78 |
| 10000 | lazyHistory | 53.04 | 1417.76 |
| 10000 | lazyHistoryIme | 45.40 | 1675.72 |

## 验收边界

API 34 x86_64 KVM 模拟器；Full 编译；非 debuggable、profileable、未混淆的 Release 派生目标。
复用真实生产渲染、视口控制器和系统键盘；不含完整页面、实际 shell/PTY、输入回显延迟或手机长期稳定性。
本报告不宣称真实手机 FPS，不能把历史版本的其他 Runner 测量当成受控前后对照。

## 结论与下一步

- 新模式的核心目标已闭环：10k IME 显示阶段为 `1104.87 ms`，原虚拟模式为 `7727.75 ms`；
  完整往返为 `3708.66 ms`，默认为 `3778.21 ms`。这仍是组件场景，不是单次键盘弹出延迟。
- 10k 新模式 IME 每轮只新建 8 个行测量节点，原虚拟模式为 10,051 个；三次迭代均未建立 eager
  历史前缀，保留测量行小于 128，滚动写入者最多为 1。
- 默认在活动行更新场景仍更快：10k 每次输出 `153.64 ms`，新模式 `163.22 ms`。默认必须继续保留，
  不应自动把既有用户迁移到虚拟模式。
- 下一项已证实的瓶颈是冷启动／重挂载的精确宽度扫描。10k 新模式首次组合的宽度阶段为
  `711.68 ms`，分离恢复为 `1359.00 ms`；当前 mounted-session 宽度保留只能消除同一绑定内的重扫。
- 下一轮应在不缓存 Paragraph/TextLayoutResult、不猜测宽度的前提下，验证由不可变历史块携带
  字体／样式绑定的标量宽度摘要，或在会话所有权层安全转移标量索引。随后才做完整页面／PTY、
  输入到回显、实机 IME 和 30 分钟内存／GC 验收。

## 来源完整性

- 完整基准：[run 37456883236](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37456883236)，
  54 个用例、162 次测量全部通过。
- 原始证据：[run 37459976695](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37459976695)，
  六个独立场景上下文、结果文件哈希、42 个源码／字体／构建哈希全部匹配。
- 完整矩阵使用的 profileable 基准目标 SHA-256 为
  `c9682da6eff63fab2ebe1cf7ed2f83f3af02bf2ebf0486629a4fedb39b647356`，不是应用 APK。
- 可安装应用仍为 `2b0f259` 的独立验签 Release：
  [artifact 11388351346](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37407976738/artifacts/11388351346)，
  APK SHA-256 `930ff71141c46c061364b21a2f02011ba22ca9d48770665a6d040846978551fc`。
  `a79e7b6` 与 `2b0f259` 之间只有本地验证报告文件变化，应用／基准输入无差异。
