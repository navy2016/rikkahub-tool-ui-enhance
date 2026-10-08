# 生产终端视口基准：e8d3208

来源：[37720935305](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37720935305)；SHA：`e8d3208d09559ad65b7557d5b206dccdc71c82c6`；协议：`production-viewport-v4`。

6 场景 × 三种历史长度 × 3 种生产模式 = 54 个用例，每例 3 次，共 162 次测量；全部成功。
每个场景内共享 APK、设备和仪器调用；不同场景／Runner／协议的帧样本不得合并，也不计算跨运行提升率。

原始设备／输入法上下文、源文件哈希和 APK 哈希由[只读导出](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37724074854)传回并校验完整性，保存在同名 JSON。
目标 APK SHA-256：`087798bcf189d0592f02a63fd33723d345d701c38a582c1f4efac5afeb170827`。

表格来源为通过完整校验后的 CI 摘要，保留两位小数；不是原始 Perfetto 帧样本。缺失项为 —，不补零。
时间单位 ms；RSS 为匿名 RSS 采样峰值的迭代中位数（MiB），不是 PSS／Java／Native／GPU 分项峰值。
输出/次包含生产协调和两次稳定绘制确认；不是无限速 PTY 吞吐。各阶段重叠，不相减分解耗时。

| 场景 | 历史行 | 模式 | 次数 | 首屏 | 总操作 | 输出/次 | CPU p95 | 宽度/次 | RSS anon |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| initialCompose | 1000 | chunkedLayers | 3 | 488.15 | — | — | 375.75 | — | 104.02 |
| initialCompose | 1000 | lazyHistory | 3 | 400.40 | — | — | 219.96 | 38.32 | 93.38 |
| initialCompose | 1000 | lazyHistoryIme | 3 | 414.03 | — | — | 208.74 | 59.59 | 93.43 |
| initialCompose | 5000 | chunkedLayers | 3 | 5121.56 | — | — | 247.98 | — | 185.25 |
| initialCompose | 5000 | lazyHistory | 3 | 1205.74 | — | — | 1062.90 | 708.81 | 222.08 |
| initialCompose | 5000 | lazyHistoryIme | 3 | 1052.10 | — | — | 730.59 | 524.74 | 136.81 |
| initialCompose | 10000 | chunkedLayers | 3 | 8520.49 | — | — | 239.41 | — | 256.41 |
| initialCompose | 10000 | lazyHistory | 3 | 1823.53 | — | — | 1552.78 | 1362.45 | 186.42 |
| initialCompose | 10000 | lazyHistoryIme | 3 | 2330.34 | — | — | 1772.84 | 1796.06 | 185.51 |
| activeRowUpdate | 1000 | chunkedLayers | 3 | — | 5553.95 | 185.11 | 128.60 | — | 107.13 |
| activeRowUpdate | 1000 | lazyHistory | 3 | — | 5700.75 | 190.01 | 135.43 | 0.57 | 109.18 |
| activeRowUpdate | 1000 | lazyHistoryIme | 3 | — | 5639.84 | 187.98 | 130.30 | 2.27 | 109.16 |
| activeRowUpdate | 5000 | chunkedLayers | 3 | — | 5636.16 | 187.86 | 131.23 | — | 170.81 |
| activeRowUpdate | 5000 | lazyHistory | 3 | — | 5571.20 | 185.69 | 129.68 | 1.02 | 118.79 |
| activeRowUpdate | 5000 | lazyHistoryIme | 3 | — | 5653.36 | 188.43 | 128.69 | 0.64 | 137.33 |
| activeRowUpdate | 10000 | chunkedLayers | 3 | — | 5966.37 | 198.86 | 130.65 | — | 249.56 |
| activeRowUpdate | 10000 | lazyHistory | 3 | — | 5557.47 | 185.23 | 133.31 | 0.62 | 200.32 |
| activeRowUpdate | 10000 | lazyHistoryIme | 3 | — | 5656.37 | 188.54 | 128.00 | 0.56 | 200.38 |
| appendAndTrim | 1000 | chunkedLayers | 3 | — | 4959.02 | 165.29 | 105.47 | — | 132.38 |
| appendAndTrim | 1000 | lazyHistory | 3 | — | 5485.20 | 182.83 | 103.59 | 0.67 | 101.78 |
| appendAndTrim | 1000 | lazyHistoryIme | 3 | — | 5516.53 | 183.79 | 108.83 | 0.56 | 101.67 |
| appendAndTrim | 5000 | chunkedLayers | 3 | — | 5981.18 | 199.36 | 138.41 | — | 186.04 |
| appendAndTrim | 5000 | lazyHistory | 3 | — | 5588.12 | 186.04 | 105.23 | 1.13 | 122.78 |
| appendAndTrim | 5000 | lazyHistoryIme | 3 | — | 5624.79 | 187.48 | 102.42 | 0.46 | 122.68 |
| appendAndTrim | 10000 | chunkedLayers | 3 | — | 7941.77 | 264.72 | 190.80 | — | 286.30 |
| appendAndTrim | 10000 | lazyHistory | 3 | — | 5541.47 | 184.70 | 104.87 | 0.68 | 174.27 |
| appendAndTrim | 10000 | lazyHistoryIme | 3 | — | 5665.85 | 188.85 | 103.54 | 0.58 | 172.43 |
| semanticJump | 1000 | chunkedLayers | 3 | — | 1222.67 | — | 104.35 | — | 105.40 |
| semanticJump | 1000 | lazyHistory | 3 | — | 812.56 | — | 110.33 | — | 109.95 |
| semanticJump | 1000 | lazyHistoryIme | 3 | — | 759.76 | — | 98.63 | — | 109.73 |
| semanticJump | 5000 | chunkedLayers | 3 | — | 1317.33 | — | 94.63 | — | 163.50 |
| semanticJump | 5000 | lazyHistory | 3 | — | 745.22 | — | 100.67 | — | 148.47 |
| semanticJump | 5000 | lazyHistoryIme | 3 | — | 730.28 | — | 104.82 | — | 145.70 |
| semanticJump | 10000 | chunkedLayers | 3 | — | 1358.95 | — | 92.54 | — | 255.99 |
| semanticJump | 10000 | lazyHistory | 3 | — | 720.86 | — | 100.22 | — | 167.09 |
| semanticJump | 10000 | lazyHistoryIme | 3 | — | 767.31 | — | 101.67 | — | 178.81 |
| imeRoundTrip | 1000 | chunkedLayers | 3 | — | 3819.13 | 190.52 | 209.65 | — | 106.64 |
| imeRoundTrip | 1000 | lazyHistory | 3 | — | 4191.36 | 194.30 | 252.45 | 0.66 | 126.94 |
| imeRoundTrip | 1000 | lazyHistoryIme | 3 | — | 3740.22 | 192.84 | 231.08 | 1.21 | 97.05 |
| imeRoundTrip | 5000 | chunkedLayers | 3 | — | 3780.62 | 192.12 | 224.84 | — | 165.32 |
| imeRoundTrip | 5000 | lazyHistory | 3 | — | 7443.07 | 205.59 | 218.98 | 0.69 | 222.75 |
| imeRoundTrip | 5000 | lazyHistoryIme | 3 | — | 3738.19 | 205.32 | 226.98 | 0.78 | 117.53 |
| imeRoundTrip | 10000 | chunkedLayers | 3 | — | 3916.97 | 195.05 | 240.31 | — | 268.45 |
| imeRoundTrip | 10000 | lazyHistory | 3 | — | 10589.20 | 197.80 | 198.37 | 0.72 | 306.31 |
| imeRoundTrip | 10000 | lazyHistoryIme | 3 | — | 3773.43 | 196.24 | 234.68 | 0.36 | 149.60 |
| detachRestore | 1000 | chunkedLayers | 3 | — | 845.13 | — | 613.11 | — | 139.77 |
| detachRestore | 1000 | lazyHistory | 3 | — | 252.96 | — | 117.35 | 50.92 | 101.65 |
| detachRestore | 1000 | lazyHistoryIme | 3 | — | 425.08 | — | 154.38 | 65.48 | 101.95 |
| detachRestore | 5000 | chunkedLayers | 3 | — | 5267.25 | — | 131.95 | — | 266.07 |
| detachRestore | 5000 | lazyHistory | 3 | — | 1092.08 | — | 864.99 | 740.82 | 143.84 |
| detachRestore | 5000 | lazyHistoryIme | 3 | — | 902.50 | — | 673.14 | 538.68 | 144.00 |
| detachRestore | 10000 | chunkedLayers | 3 | — | 8321.19 | — | 335.24 | — | 295.69 |
| detachRestore | 10000 | lazyHistory | 3 | — | 1567.84 | — | 1357.57 | 1214.31 | 180.35 |
| detachRestore | 10000 | lazyHistoryIme | 3 | — | 1766.36 | — | 1645.61 | 1426.59 | 209.70 |

## initialCompose — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | — | 21.58 | 2.95 | — | 0.31 | 0.40 | 192.92 | 5.01 | — |
| 1000 | lazyHistory | — | 19.64 | 3.45 | 38.32 | — | 0.14 | 8.36 | 0.11 | 52.90 |
| 1000 | lazyHistoryIme | — | 13.65 | 3.31 | 59.59 | — | 0.15 | 10.84 | 0.14 | 58.74 |
| 5000 | chunkedLayers | — | 56.32 | 4.60 | — | 4.04 | 4.17 | 3212.30 | 65.86 | — |
| 5000 | lazyHistory | — | 99.99 | 7.34 | 708.81 | — | 0.15 | 37.84 | 0.82 | 65.02 |
| 5000 | lazyHistoryIme | — | 56.87 | 5.51 | 524.74 | — | 0.45 | 110.57 | 0.69 | 67.52 |
| 10000 | chunkedLayers | — | 113.96 | 11.06 | — | 2.47 | 2.62 | 1592.38 | 49.35 | — |
| 10000 | lazyHistory | — | 81.89 | 8.18 | 1362.45 | — | 0.15 | 14.56 | 0.37 | 70.63 |
| 10000 | lazyHistoryIme | — | 92.03 | 6.74 | 1796.06 | — | 0.17 | 49.25 | 0.38 | 49.92 |

## activeRowUpdate — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.22 | 0.83 | 0.23 | — | 0.04 | 0.09 | — | 0.11 | 0.46 |
| 1000 | lazyHistory | 0.36 | 0.81 | 0.14 | 0.57 | — | 0.13 | — | 0.01 | 69.44 |
| 1000 | lazyHistoryIme | 0.19 | 0.53 | 0.17 | 2.27 | — | 0.22 | — | 0.01 | 66.71 |
| 5000 | chunkedLayers | 0.20 | 0.42 | 0.23 | — | 0.08 | 0.13 | — | 0.01 | 0.75 |
| 5000 | lazyHistory | 0.09 | 0.52 | 0.11 | 1.02 | — | 0.15 | — | 0.01 | 76.36 |
| 5000 | lazyHistoryIme | 0.10 | 0.70 | 0.31 | 0.64 | — | 0.24 | — | 0.01 | 75.17 |
| 10000 | chunkedLayers | 0.10 | 0.30 | 0.35 | — | 0.03 | 0.09 | — | 0.01 | 0.57 |
| 10000 | lazyHistory | 0.14 | 1.48 | 0.20 | 0.62 | — | 0.23 | — | 0.01 | 70.28 |
| 10000 | lazyHistoryIme | 0.36 | 0.63 | 0.14 | 0.56 | — | 0.24 | — | 0.01 | 77.97 |

## appendAndTrim — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.08 | 0.58 | 0.32 | — | 0.29 | 0.34 | — | 0.01 | — |
| 1000 | lazyHistory | 0.07 | 0.39 | 0.45 | 0.67 | — | 0.17 | — | 0.01 | 81.83 |
| 1000 | lazyHistoryIme | 0.07 | 0.44 | 0.28 | 0.56 | — | 0.15 | — | 0.01 | 108.14 |
| 5000 | chunkedLayers | 0.08 | 0.28 | 0.35 | — | 0.97 | 1.00 | — | 0.01 | — |
| 5000 | lazyHistory | 0.07 | 0.70 | 0.63 | 1.13 | — | 0.18 | — | 0.01 | 64.69 |
| 5000 | lazyHistoryIme | 0.07 | 0.84 | 0.22 | 0.46 | — | 0.16 | — | 0.01 | 75.93 |
| 10000 | chunkedLayers | 0.09 | 0.24 | 0.45 | — | 2.71 | 2.74 | — | 0.01 | — |
| 10000 | lazyHistory | 0.07 | 0.37 | 0.52 | 0.68 | — | 0.31 | — | 0.01 | 69.05 |
| 10000 | lazyHistoryIme | 0.07 | 0.28 | 0.34 | 0.58 | — | 0.23 | — | 0.01 | 66.21 |

## semanticJump — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | — | — | — | — | 0.00 | 0.03 | — | 0.01 | 533.22 |
| 1000 | lazyHistory | — | — | — | — | — | 0.05 | — | 0.01 | 332.17 |
| 1000 | lazyHistoryIme | — | — | — | — | — | 0.05 | — | 0.01 | 310.42 |
| 5000 | chunkedLayers | — | — | — | — | 0.00 | 0.03 | — | 0.01 | 591.79 |
| 5000 | lazyHistory | — | — | — | — | — | 0.04 | — | 0.01 | 307.86 |
| 5000 | lazyHistoryIme | — | — | — | — | — | 0.04 | — | 0.02 | 298.76 |
| 10000 | chunkedLayers | — | — | — | — | 0.00 | 0.03 | — | 0.01 | 608.10 |
| 10000 | lazyHistory | — | — | — | — | — | 0.04 | — | 0.01 | 312.30 |
| 10000 | lazyHistoryIme | — | — | — | — | — | 0.04 | — | 0.01 | 317.12 |

### Transitions (ms)

| History | Mode | jumpTop | jumpTail |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 613.34 | 630.69 |
| 1000 | lazyHistory | 377.82 | 360.23 |
| 1000 | lazyHistoryIme | 377.51 | 386.74 |
| 5000 | chunkedLayers | 666.42 | 653.87 |
| 5000 | lazyHistory | 390.27 | 366.01 |
| 5000 | lazyHistoryIme | 374.04 | 343.86 |
| 10000 | chunkedLayers | 670.79 | 679.49 |
| 10000 | lazyHistory | 367.65 | 354.82 |
| 10000 | lazyHistoryIme | 378.48 | 384.34 |

## imeRoundTrip — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.07 | 0.24 | 0.11 | — | 0.02 | 0.11 | 0.20 | 0.02 | — |
| 1000 | lazyHistory | 0.08 | 0.22 | 0.11 | 0.66 | 0.05 | 0.13 | 44.88 | 3.40 | 0.03 |
| 1000 | lazyHistoryIme | 0.07 | 0.22 | 0.13 | 1.21 | — | 0.07 | 2.15 | 0.05 | — |
| 5000 | chunkedLayers | 0.08 | 0.24 | 0.12 | — | 0.02 | 0.07 | 2.27 | 0.02 | — |
| 5000 | lazyHistory | 0.07 | 0.58 | 0.12 | 0.69 | 0.21 | 0.26 | 342.04 | 27.08 | 0.05 |
| 5000 | lazyHistoryIme | 0.07 | 0.20 | 0.12 | 0.78 | — | 0.07 | 1.57 | 0.05 | — |
| 10000 | chunkedLayers | 0.08 | 0.74 | 0.17 | — | 0.03 | 0.06 | 4.29 | 0.01 | — |
| 10000 | lazyHistory | 0.08 | 0.22 | 0.12 | 0.72 | 0.12 | 0.19 | 333.09 | 10.10 | 0.04 |
| 10000 | lazyHistoryIme | 0.08 | 0.25 | 0.12 | 0.36 | — | 0.07 | 1.43 | 0.05 | — |

### Transitions (ms)

| History | Mode | imeShow | imeHide | explicitRetry |
| ---: | --- | ---: | ---: | ---: |
| 1000 | chunkedLayers | 1241.81 | 832.52 | 169.82 |
| 1000 | lazyHistory | 1509.20 | 747.76 | 331.98 |
| 1000 | lazyHistoryIme | 1206.95 | 852.16 | 166.69 |
| 5000 | chunkedLayers | 1197.71 | 924.68 | 192.81 |
| 5000 | lazyHistory | 4586.32 | 827.45 | 365.44 |
| 5000 | lazyHistoryIme | 972.40 | 880.89 | 173.23 |
| 10000 | chunkedLayers | 1294.39 | 893.36 | 182.07 |
| 10000 | lazyHistory | 7826.18 | 869.62 | 340.41 |
| 10000 | lazyHistoryIme | 1171.90 | 817.75 | 175.61 |

## detachRestore — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.55 | 0.32 | 4.50 | — | 1.68 | 3.44 | 351.28 | 10.36 | 0.07 |
| 1000 | lazyHistory | 0.79 | 0.32 | 0.73 | 50.92 | — | 0.10 | 8.60 | 0.11 | 56.58 |
| 1000 | lazyHistoryIme | 0.58 | 0.47 | 0.88 | 65.48 | — | 0.10 | 23.09 | 0.13 | 123.12 |
| 5000 | chunkedLayers | 0.51 | 0.36 | 4.91 | — | 1.09 | 2.28 | 2345.95 | 185.62 | 0.06 |
| 5000 | lazyHistory | 0.55 | 0.32 | 7.40 | 740.82 | — | 0.12 | 41.23 | 0.21 | 124.02 |
| 5000 | lazyHistoryIme | 0.50 | 0.34 | 3.07 | 538.68 | — | 0.11 | 14.95 | 0.19 | 117.53 |
| 10000 | chunkedLayers | 1.65 | 0.34 | 11.84 | — | 0.60 | 2.95 | 1825.35 | 40.85 | 0.05 |
| 10000 | lazyHistory | 0.53 | 0.33 | 8.27 | 1214.31 | — | 0.19 | 22.15 | 0.15 | 113.64 |
| 10000 | lazyHistoryIme | 0.75 | 0.35 | 14.04 | 1426.59 | — | 0.11 | 14.01 | 0.64 | 120.16 |

### Transitions (ms)

| History | Mode | detach | restore |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 76.32 | 792.03 |
| 1000 | lazyHistory | 53.12 | 211.72 |
| 1000 | lazyHistoryIme | 38.31 | 386.10 |
| 5000 | chunkedLayers | 143.18 | 5117.77 |
| 5000 | lazyHistory | 44.42 | 1048.57 |
| 5000 | lazyHistoryIme | 38.52 | 855.31 |
| 10000 | chunkedLayers | 285.24 | 8096.94 |
| 10000 | lazyHistory | 37.95 | 1526.70 |
| 10000 | lazyHistoryIme | 40.51 | 1695.98 |

## 验收边界

API 34 x86_64 KVM 模拟器；Full 编译；非 debuggable、profileable、未混淆的 Release 派生目标。
复用真实生产渲染、视口控制器和系统键盘；不含完整页面、实际 shell/PTY、输入回显延迟或手机长期稳定性。
本报告不宣称真实手机 FPS，不能把历史版本的其他 Runner 测量当成受控前后对照。
