# 生产终端视口基准：ac47787

来源：[38048241693](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/38048241693)；SHA：`ac47787cf2939a91779f2563fa40bb8b610a07e7`；协议：`production-viewport-v4`。

6 场景 × 三种历史长度 × 3 种生产模式 = 54 个用例，每例 3 次，共 162 次测量；全部成功。
每个场景内共享 APK、设备和仪器调用；不同场景／Runner／协议的帧样本不得合并，也不计算跨运行提升率。

原始设备／输入法上下文、源文件哈希和 APK 哈希由[只读导出](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/38049802921)传回并校验完整性，保存在同名 JSON。
目标 APK SHA-256：`b55891c784089ea5eee06a3609b8fd5e9b9b48919d9a70f4da296de2985b0afd`。

表格来源为通过完整校验后的 CI 摘要，保留两位小数；不是原始 Perfetto 帧样本。缺失项为 —，不补零。
时间单位 ms；RSS 为匿名 RSS 采样峰值的迭代中位数（MiB），不是 PSS／Java／Native／GPU 分项峰值。
输出/次包含生产协调和两次稳定绘制确认；不是无限速 PTY 吞吐。各阶段重叠，不相减分解耗时。

| 场景 | 历史行 | 模式 | 次数 | 首屏 | 总操作 | 输出/次 | CPU p95 | 宽度/次 | RSS anon |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| initialCompose | 1000 | chunkedLayers | 3 | 857.29 | — | — | 716.15 | — | 104.22 |
| initialCompose | 1000 | lazyHistory | 3 | 401.79 | — | — | 192.30 | 33.60 | 93.44 |
| initialCompose | 1000 | lazyHistoryIme | 3 | 405.13 | — | — | 209.24 | 60.51 | 93.55 |
| initialCompose | 5000 | chunkedLayers | 3 | 4713.82 | — | — | 234.81 | — | 202.54 |
| initialCompose | 5000 | lazyHistory | 3 | 1007.04 | — | — | 809.99 | 502.17 | 136.99 |
| initialCompose | 5000 | lazyHistoryIme | 3 | 723.69 | — | — | 662.73 | 416.29 | 136.99 |
| initialCompose | 10000 | chunkedLayers | 3 | 8246.65 | — | — | 166.92 | — | 261.68 |
| initialCompose | 10000 | lazyHistory | 3 | 2037.44 | — | — | 2025.02 | 1473.10 | 185.49 |
| initialCompose | 10000 | lazyHistoryIme | 3 | 2466.65 | — | — | 2324.56 | 1979.04 | 184.38 |
| activeRowUpdate | 1000 | chunkedLayers | 3 | — | 3513.13 | 117.09 | 83.93 | — | 107.69 |
| activeRowUpdate | 1000 | lazyHistory | 3 | — | 3575.91 | 119.18 | 86.25 | 0.51 | 98.60 |
| activeRowUpdate | 1000 | lazyHistoryIme | 3 | — | 3529.07 | 117.63 | 87.17 | 0.44 | 98.53 |
| activeRowUpdate | 5000 | chunkedLayers | 3 | — | 3582.53 | 119.41 | 84.94 | — | 169.98 |
| activeRowUpdate | 5000 | lazyHistory | 3 | — | 3738.29 | 124.60 | 88.20 | 0.31 | 118.88 |
| activeRowUpdate | 5000 | lazyHistoryIme | 3 | — | 3605.67 | 120.17 | 88.10 | 0.25 | 137.08 |
| activeRowUpdate | 10000 | chunkedLayers | 3 | — | 4006.11 | 133.52 | 92.78 | — | 256.97 |
| activeRowUpdate | 10000 | lazyHistory | 3 | — | 3759.27 | 125.29 | 93.61 | 0.58 | 151.34 |
| activeRowUpdate | 10000 | lazyHistoryIme | 3 | — | 3749.26 | 124.96 | 94.23 | 0.39 | 196.14 |
| appendAndTrim | 1000 | chunkedLayers | 3 | — | 5493.19 | 183.08 | 121.03 | — | 132.12 |
| appendAndTrim | 1000 | lazyHistory | 3 | — | 6377.39 | 212.56 | 115.58 | 1.84 | 112.80 |
| appendAndTrim | 1000 | lazyHistoryIme | 3 | — | 6436.66 | 214.53 | 118.32 | 1.23 | 112.54 |
| appendAndTrim | 5000 | chunkedLayers | 3 | — | 6895.50 | 229.83 | 156.94 | — | 178.83 |
| appendAndTrim | 5000 | lazyHistory | 3 | — | 6420.07 | 213.98 | 115.31 | 0.87 | 123.07 |
| appendAndTrim | 5000 | lazyHistoryIme | 3 | — | 6476.89 | 215.87 | 118.74 | 3.13 | 122.77 |
| appendAndTrim | 10000 | chunkedLayers | 3 | — | 8946.72 | 298.21 | 217.56 | — | 268.34 |
| appendAndTrim | 10000 | lazyHistory | 3 | — | 6526.84 | 217.55 | 112.18 | 0.98 | 156.01 |
| appendAndTrim | 10000 | lazyHistoryIme | 3 | — | 6228.26 | 207.58 | 111.42 | 1.19 | 165.78 |
| semanticJump | 1000 | chunkedLayers | 3 | — | 1298.06 | — | 120.76 | — | 105.23 |
| semanticJump | 1000 | lazyHistory | 3 | — | 787.65 | — | 166.65 | — | 108.25 |
| semanticJump | 1000 | lazyHistoryIme | 3 | — | 838.92 | — | 169.26 | — | 109.97 |
| semanticJump | 5000 | chunkedLayers | 3 | — | 1394.39 | — | 125.97 | — | 165.05 |
| semanticJump | 5000 | lazyHistory | 3 | — | 848.06 | — | 151.35 | — | 131.46 |
| semanticJump | 5000 | lazyHistoryIme | 3 | — | 898.41 | — | 135.99 | — | 148.05 |
| semanticJump | 10000 | chunkedLayers | 3 | — | 1421.27 | — | 127.35 | — | 246.06 |
| semanticJump | 10000 | lazyHistory | 3 | — | 845.13 | — | 168.99 | — | 163.02 |
| semanticJump | 10000 | lazyHistoryIme | 3 | — | 980.18 | — | 165.13 | — | 162.42 |
| imeRoundTrip | 1000 | chunkedLayers | 3 | — | 4116.86 | 204.82 | 246.33 | — | 106.77 |
| imeRoundTrip | 1000 | lazyHistory | 3 | — | 5080.24 | 219.82 | 272.82 | 1.14 | 127.21 |
| imeRoundTrip | 1000 | lazyHistoryIme | 3 | — | 3949.71 | 221.48 | 243.09 | 0.49 | 97.11 |
| imeRoundTrip | 5000 | chunkedLayers | 3 | — | 4084.64 | 218.42 | 253.00 | — | 164.48 |
| imeRoundTrip | 5000 | lazyHistory | 3 | — | 9350.62 | 219.11 | 222.95 | 0.94 | 222.16 |
| imeRoundTrip | 5000 | lazyHistoryIme | 3 | — | 4099.54 | 223.73 | 241.63 | 0.24 | 136.14 |
| imeRoundTrip | 10000 | chunkedLayers | 3 | — | 4219.31 | 216.20 | 258.51 | — | 262.09 |
| imeRoundTrip | 10000 | lazyHistory | 3 | — | 11816.15 | 214.44 | 237.24 | 0.89 | 255.73 |
| imeRoundTrip | 10000 | lazyHistoryIme | 3 | — | 3994.68 | 220.05 | 218.72 | 0.21 | 150.61 |
| detachRestore | 1000 | chunkedLayers | 3 | — | 420.36 | — | 223.49 | — | 140.08 |
| detachRestore | 1000 | lazyHistory | 3 | — | 204.07 | — | 90.31 | 42.60 | 102.08 |
| detachRestore | 1000 | lazyHistoryIme | 3 | — | 279.52 | — | 103.00 | 32.60 | 102.13 |
| detachRestore | 5000 | chunkedLayers | 3 | — | 2241.69 | — | 1908.53 | — | 234.75 |
| detachRestore | 5000 | lazyHistory | 3 | — | 450.20 | — | 272.86 | 172.15 | 165.95 |
| detachRestore | 5000 | lazyHistoryIme | 3 | — | 521.55 | — | 327.44 | 218.41 | 165.02 |
| detachRestore | 10000 | chunkedLayers | 3 | — | 5758.30 | — | 172.16 | — | 352.69 |
| detachRestore | 10000 | lazyHistory | 3 | — | 893.41 | — | 921.50 | 608.21 | 205.21 |
| detachRestore | 10000 | lazyHistoryIme | 3 | — | 653.10 | — | 517.10 | 412.36 | 205.03 |

## initialCompose — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | — | 24.86 | 5.60 | — | 0.47 | 0.70 | 423.78 | 10.05 | — |
| 1000 | lazyHistory | — | 16.81 | 5.31 | 33.60 | — | 0.14 | 7.95 | 0.10 | 14.03 |
| 1000 | lazyHistoryIme | — | 14.79 | 3.06 | 60.51 | — | 0.13 | 10.72 | 0.12 | 21.49 |
| 5000 | chunkedLayers | — | 66.84 | 6.68 | — | 1.11 | 1.21 | 2665.82 | 97.19 | — |
| 5000 | lazyHistory | — | 49.73 | 6.56 | 502.17 | — | 0.12 | 73.37 | 0.28 | 9.80 |
| 5000 | lazyHistoryIme | — | 60.80 | 4.75 | 416.29 | — | 0.18 | 27.04 | 0.54 | 13.71 |
| 10000 | chunkedLayers | — | 122.73 | 10.91 | — | 8.88 | 8.98 | 1900.36 | 39.32 | — |
| 10000 | lazyHistory | — | 99.50 | 9.69 | 1473.10 | — | 0.14 | 48.18 | 0.25 | 15.47 |
| 10000 | lazyHistoryIme | — | 108.59 | 10.44 | 1979.04 | — | 0.15 | 41.35 | 0.22 | 8.30 |

## activeRowUpdate — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.07 | 0.31 | 0.13 | — | 0.03 | 0.07 | — | 0.01 | 0.34 |
| 1000 | lazyHistory | 0.07 | 0.51 | 0.10 | 0.51 | — | 0.09 | — | 0.01 | 0.64 |
| 1000 | lazyHistoryIme | 0.06 | 0.32 | 0.11 | 0.44 | — | 0.10 | — | 0.01 | 0.74 |
| 5000 | chunkedLayers | 0.24 | 0.15 | 0.11 | — | 0.03 | 0.07 | — | 0.01 | 0.32 |
| 5000 | lazyHistory | 0.06 | 0.36 | 0.10 | 0.31 | — | 0.10 | — | 0.01 | 2.84 |
| 5000 | lazyHistoryIme | 0.06 | 0.34 | 0.10 | 0.25 | — | 0.14 | — | 0.01 | 2.45 |
| 10000 | chunkedLayers | 0.07 | 0.34 | 0.17 | — | 0.05 | 0.10 | — | 0.01 | 0.37 |
| 10000 | lazyHistory | 0.06 | 0.17 | 0.10 | 0.58 | — | 0.16 | — | 0.05 | 4.21 |
| 10000 | lazyHistoryIme | 0.06 | 0.34 | 0.11 | 0.39 | — | 0.11 | — | 0.01 | 6.68 |

## appendAndTrim — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.10 | 0.54 | 0.44 | — | 0.21 | 0.24 | — | 0.01 | — |
| 1000 | lazyHistory | 0.33 | 0.49 | 0.27 | 1.84 | — | 0.15 | — | 0.01 | 10.06 |
| 1000 | lazyHistoryIme | 0.10 | 0.96 | 0.20 | 1.23 | — | 0.17 | — | 0.02 | 5.10 |
| 5000 | chunkedLayers | 0.10 | 0.31 | 0.28 | — | 0.11 | 0.23 | — | 0.01 | — |
| 5000 | lazyHistory | 0.18 | 1.91 | 0.54 | 0.87 | — | 0.21 | — | 0.01 | 5.77 |
| 5000 | lazyHistoryIme | 0.10 | 0.63 | 0.52 | 3.13 | — | 0.14 | — | 0.01 | 19.69 |
| 10000 | chunkedLayers | 0.11 | 0.31 | 0.41 | — | 0.11 | 0.13 | — | 0.01 | — |
| 10000 | lazyHistory | 0.15 | 0.57 | 0.46 | 0.98 | — | 0.17 | — | 0.01 | 6.90 |
| 10000 | lazyHistoryIme | 0.09 | 0.53 | 0.31 | 1.19 | — | 0.14 | — | 0.01 | 45.80 |

## semanticJump — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | — | — | — | — | 0.00 | 0.03 | — | 0.01 | 552.64 |
| 1000 | lazyHistory | — | — | — | — | — | 0.06 | — | 0.01 | 359.16 |
| 1000 | lazyHistoryIme | — | — | — | — | — | 0.06 | — | 0.01 | 329.87 |
| 5000 | chunkedLayers | — | — | — | — | 0.00 | 0.03 | — | 0.01 | 619.12 |
| 5000 | lazyHistory | — | — | — | — | — | 0.05 | — | 0.01 | 320.43 |
| 5000 | lazyHistoryIme | — | — | — | — | — | 0.05 | — | 0.01 | 476.70 |
| 10000 | chunkedLayers | — | — | — | — | 0.00 | 0.04 | — | 0.01 | 612.74 |
| 10000 | lazyHistory | — | — | — | — | — | 0.05 | — | 0.01 | 385.58 |
| 10000 | lazyHistoryIme | — | — | — | — | — | 0.05 | — | 0.01 | 422.51 |

### Transitions (ms)

| History | Mode | jumpTop | jumpTail |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 647.91 | 644.81 |
| 1000 | lazyHistory | 407.97 | 452.59 |
| 1000 | lazyHistoryIme | 470.45 | 368.20 |
| 5000 | chunkedLayers | 683.87 | 711.58 |
| 5000 | lazyHistory | 429.69 | 410.48 |
| 5000 | lazyHistoryIme | 396.18 | 367.30 |
| 10000 | chunkedLayers | 707.51 | 718.47 |
| 10000 | lazyHistory | 467.15 | 443.44 |
| 10000 | lazyHistoryIme | 518.06 | 458.87 |

## imeRoundTrip — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.07 | 0.27 | 0.10 | — | 0.02 | 0.06 | 0.20 | 0.01 | — |
| 1000 | lazyHistory | 0.08 | 0.24 | 0.10 | 1.14 | 0.05 | 0.10 | 77.57 | 4.17 | 0.04 |
| 1000 | lazyHistoryIme | 0.07 | 0.26 | 0.11 | 0.49 | — | 0.07 | 3.77 | 0.04 | — |
| 5000 | chunkedLayers | 0.10 | 0.23 | 0.11 | — | 0.02 | 0.06 | 3.37 | 0.01 | — |
| 5000 | lazyHistory | 0.08 | 0.28 | 0.11 | 0.94 | 0.31 | 0.40 | 524.93 | 25.95 | 0.04 |
| 5000 | lazyHistoryIme | 0.08 | 0.25 | 0.13 | 0.24 | — | 0.18 | 1.47 | 0.05 | — |
| 10000 | chunkedLayers | 0.08 | 0.53 | 0.11 | — | 0.02 | 0.06 | 3.69 | 0.01 | — |
| 10000 | lazyHistory | 0.08 | 0.23 | 0.10 | 0.89 | 0.10 | 0.15 | 198.97 | 12.15 | 0.03 |
| 10000 | lazyHistoryIme | 0.08 | 0.79 | 0.14 | 0.21 | — | 0.06 | 3.67 | 0.36 | — |

### Transitions (ms)

| History | Mode | imeShow | imeHide | explicitRetry |
| ---: | --- | ---: | ---: | ---: |
| 1000 | chunkedLayers | 1227.44 | 937.68 | 203.28 |
| 1000 | lazyHistory | 1995.08 | 969.24 | 356.50 |
| 1000 | lazyHistoryIme | 1058.76 | 873.28 | 212.05 |
| 5000 | chunkedLayers | 1256.61 | 925.78 | 195.36 |
| 5000 | lazyHistory | 6292.37 | 939.70 | 412.02 |
| 5000 | lazyHistoryIme | 1158.39 | 945.71 | 201.06 |
| 10000 | chunkedLayers | 1405.78 | 938.63 | 207.85 |
| 10000 | lazyHistory | 8786.71 | 866.07 | 423.19 |
| 10000 | lazyHistoryIme | 1157.77 | 955.98 | 191.35 |

## detachRestore — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.32 | 0.28 | 0.48 | — | 0.09 | 0.24 | 94.22 | 3.85 | 0.10 |
| 1000 | lazyHistory | 0.35 | 0.29 | 0.43 | 42.60 | — | 0.07 | 3.74 | 0.10 | 39.21 |
| 1000 | lazyHistoryIme | 0.40 | 0.29 | 0.48 | 32.60 | — | 0.08 | 9.55 | 0.48 | 9.22 |
| 5000 | chunkedLayers | 0.33 | 0.21 | 2.92 | — | 0.30 | 0.68 | 821.10 | 62.35 | 0.05 |
| 5000 | lazyHistory | 0.31 | 0.23 | 4.60 | 172.15 | — | 0.08 | 7.55 | 0.10 | 61.14 |
| 5000 | lazyHistoryIme | 0.47 | 0.24 | 1.93 | 218.41 | — | 0.09 | 17.04 | 0.14 | 43.67 |
| 10000 | chunkedLayers | 0.32 | 0.41 | 7.27 | — | 0.58 | 1.26 | 1845.01 | 125.98 | 0.05 |
| 10000 | lazyHistory | 0.32 | 0.22 | 9.10 | 608.21 | — | 0.09 | 21.42 | 0.11 | 41.37 |
| 10000 | lazyHistoryIme | 0.31 | 0.26 | 3.89 | 412.36 | — | 0.10 | 9.92 | 0.10 | 51.26 |

### Transitions (ms)

| History | Mode | detach | restore |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 42.73 | 360.52 |
| 1000 | lazyHistory | 28.92 | 174.70 |
| 1000 | lazyHistoryIme | 33.69 | 245.31 |
| 5000 | chunkedLayers | 115.60 | 2166.78 |
| 5000 | lazyHistory | 36.58 | 413.11 |
| 5000 | lazyHistoryIme | 39.69 | 459.33 |
| 10000 | chunkedLayers | 162.42 | 5546.67 |
| 10000 | lazyHistory | 32.90 | 867.21 |
| 10000 | lazyHistoryIme | 33.68 | 619.92 |

## 验收边界

API 34 x86_64 KVM 模拟器；Full 编译；非 debuggable、profileable、未混淆的 Release 派生目标。
复用真实生产渲染、视口控制器和系统键盘；不含完整页面、实际 shell/PTY、输入回显延迟或手机长期稳定性。
本报告不宣称真实手机 FPS，不能把历史版本的其他 Runner 测量当成受控前后对照。
