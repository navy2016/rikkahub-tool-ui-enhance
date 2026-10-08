# 生产终端视口基准：c9c082d

来源：[37724061734](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37724061734)；SHA：`c9c082deb59c03d21675fc9124556299e969eeb7`；协议：`production-viewport-v4`。

6 场景 × 三种历史长度 × 3 种生产模式 = 54 个用例，每例 3 次，共 162 次测量；全部成功。
每个场景内共享 APK、设备和仪器调用；不同场景／Runner／协议的帧样本不得合并，也不计算跨运行提升率。

原始设备／输入法上下文、源文件哈希和 APK 哈希由[只读导出](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37726277181)传回并校验完整性，保存在同名 JSON。
目标 APK SHA-256：`6b389b89ebcd72721fdeb7efb2a095f189732af7748139e0348c80cebfe44982`。

表格来源为通过完整校验后的 CI 摘要，保留两位小数；不是原始 Perfetto 帧样本。缺失项为 —，不补零。
时间单位 ms；RSS 为匿名 RSS 采样峰值的迭代中位数（MiB），不是 PSS／Java／Native／GPU 分项峰值。
输出/次包含生产协调和两次稳定绘制确认；不是无限速 PTY 吞吐。各阶段重叠，不相减分解耗时。

| 场景 | 历史行 | 模式 | 次数 | 首屏 | 总操作 | 输出/次 | CPU p95 | 宽度/次 | RSS anon |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| initialCompose | 1000 | chunkedLayers | 3 | 380.14 | — | — | 319.67 | — | 104.55 |
| initialCompose | 1000 | lazyHistory | 3 | 345.83 | — | — | 158.05 | 34.78 | 93.95 |
| initialCompose | 1000 | lazyHistoryIme | 3 | 331.02 | — | — | 145.50 | 35.45 | 93.96 |
| initialCompose | 5000 | chunkedLayers | 3 | 3792.20 | — | — | 164.93 | — | 213.80 |
| initialCompose | 5000 | lazyHistory | 3 | 818.88 | — | — | 596.09 | 365.01 | 230.08 |
| initialCompose | 5000 | lazyHistoryIme | 3 | 699.49 | — | — | 608.99 | 320.09 | 137.90 |
| initialCompose | 10000 | chunkedLayers | 3 | 7654.35 | — | — | 189.10 | — | 361.04 |
| initialCompose | 10000 | lazyHistory | 3 | 1359.27 | — | — | 1472.69 | 943.05 | 204.52 |
| initialCompose | 10000 | lazyHistoryIme | 3 | 1324.79 | — | — | 1172.35 | 801.48 | 185.49 |
| activeRowUpdate | 1000 | chunkedLayers | 3 | — | 4852.63 | 161.73 | 118.78 | — | 107.16 |
| activeRowUpdate | 1000 | lazyHistory | 3 | — | 5041.99 | 168.04 | 115.72 | 0.41 | 98.15 |
| activeRowUpdate | 1000 | lazyHistoryIme | 3 | — | 4914.14 | 163.78 | 114.91 | 0.36 | 98.10 |
| activeRowUpdate | 5000 | chunkedLayers | 3 | — | 4875.50 | 162.49 | 114.77 | — | 170.15 |
| activeRowUpdate | 5000 | lazyHistory | 3 | — | 4833.77 | 161.11 | 115.48 | 0.35 | 119.04 |
| activeRowUpdate | 5000 | lazyHistoryIme | 3 | — | 4850.00 | 161.65 | 116.51 | 0.35 | 118.86 |
| activeRowUpdate | 10000 | chunkedLayers | 3 | — | 5088.64 | 169.61 | 117.75 | — | 256.18 |
| activeRowUpdate | 10000 | lazyHistory | 3 | — | 4817.99 | 160.54 | 116.26 | 0.31 | 153.28 |
| activeRowUpdate | 10000 | lazyHistoryIme | 3 | — | 4956.72 | 165.13 | 115.75 | 0.47 | 151.16 |
| appendAndTrim | 1000 | chunkedLayers | 3 | — | 4327.61 | 144.16 | 94.10 | — | 132.40 |
| appendAndTrim | 1000 | lazyHistory | 3 | — | 4609.39 | 153.63 | 88.88 | 0.38 | 102.09 |
| appendAndTrim | 1000 | lazyHistoryIme | 3 | — | 4939.16 | 164.63 | 92.67 | 0.44 | 102.02 |
| appendAndTrim | 5000 | chunkedLayers | 3 | — | 4713.40 | 157.10 | 109.21 | — | 206.61 |
| appendAndTrim | 5000 | lazyHistory | 3 | — | 4690.40 | 156.34 | 89.82 | 0.46 | 123.02 |
| appendAndTrim | 5000 | lazyHistoryIme | 3 | — | 4837.50 | 161.23 | 90.73 | 0.53 | 140.89 |
| appendAndTrim | 10000 | chunkedLayers | 3 | — | 6039.05 | 201.29 | 146.38 | — | 279.33 |
| appendAndTrim | 10000 | lazyHistory | 3 | — | 4692.43 | 156.39 | 91.06 | 0.66 | 159.34 |
| appendAndTrim | 10000 | lazyHistoryIme | 3 | — | 4804.41 | 160.13 | 86.63 | 0.47 | 205.02 |
| semanticJump | 1000 | chunkedLayers | 3 | — | 1309.34 | — | 116.68 | — | 105.12 |
| semanticJump | 1000 | lazyHistory | 3 | — | 894.20 | — | 122.02 | — | 109.85 |
| semanticJump | 1000 | lazyHistoryIme | 3 | — | 954.73 | — | 163.40 | — | 109.53 |
| semanticJump | 5000 | chunkedLayers | 3 | — | 1371.59 | — | 118.13 | — | 177.24 |
| semanticJump | 5000 | lazyHistory | 3 | — | 967.36 | — | 126.59 | — | 133.10 |
| semanticJump | 5000 | lazyHistoryIme | 3 | — | 1143.30 | — | 140.80 | — | 131.81 |
| semanticJump | 10000 | chunkedLayers | 3 | — | 1430.62 | — | 115.65 | — | 273.74 |
| semanticJump | 10000 | lazyHistory | 3 | — | 951.96 | — | 144.36 | — | 170.14 |
| semanticJump | 10000 | lazyHistoryIme | 3 | — | 915.43 | — | 132.06 | — | 173.29 |
| imeRoundTrip | 1000 | chunkedLayers | 3 | — | 4130.21 | 219.05 | 224.89 | — | 106.37 |
| imeRoundTrip | 1000 | lazyHistory | 3 | — | 4902.36 | 213.90 | 308.37 | 3.30 | 126.82 |
| imeRoundTrip | 1000 | lazyHistoryIme | 3 | — | 4110.29 | 217.15 | 237.83 | 0.24 | 96.86 |
| imeRoundTrip | 5000 | chunkedLayers | 3 | — | 4262.08 | 222.88 | 269.07 | — | 174.54 |
| imeRoundTrip | 5000 | lazyHistory | 3 | — | 9437.50 | 208.34 | 210.24 | 1.41 | 222.64 |
| imeRoundTrip | 5000 | lazyHistoryIme | 3 | — | 3981.14 | 220.00 | 224.52 | 0.74 | 117.55 |
| imeRoundTrip | 10000 | chunkedLayers | 3 | — | 4312.26 | 223.79 | 293.63 | — | 257.54 |
| imeRoundTrip | 10000 | lazyHistory | 3 | — | 11683.56 | 214.36 | 230.17 | 0.99 | 254.25 |
| imeRoundTrip | 10000 | lazyHistoryIme | 3 | — | 4081.54 | 217.76 | 233.28 | 0.25 | 154.50 |
| detachRestore | 1000 | chunkedLayers | 3 | — | 769.59 | — | 578.19 | — | 139.79 |
| detachRestore | 1000 | lazyHistory | 3 | — | 313.78 | — | 126.58 | 43.06 | 101.79 |
| detachRestore | 1000 | lazyHistoryIme | 3 | — | 367.22 | — | 134.21 | 56.90 | 101.89 |
| detachRestore | 5000 | chunkedLayers | 3 | — | 6076.92 | — | 142.54 | — | 266.02 |
| detachRestore | 5000 | lazyHistory | 3 | — | 1096.56 | — | 882.27 | 598.03 | 164.95 |
| detachRestore | 5000 | lazyHistoryIme | 3 | — | 1074.92 | — | 954.17 | 750.86 | 143.67 |
| detachRestore | 10000 | chunkedLayers | 3 | — | 8530.40 | — | 256.65 | — | 337.53 |
| detachRestore | 10000 | lazyHistory | 3 | — | 1573.09 | — | 1331.23 | 1197.16 | 183.36 |
| detachRestore | 10000 | lazyHistoryIme | 3 | — | 1848.01 | — | 1650.68 | 1487.56 | 203.16 |

## initialCompose — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | — | 14.68 | 4.34 | — | 0.37 | 0.45 | 174.44 | 2.64 | — |
| 1000 | lazyHistory | — | 16.78 | 1.91 | 34.78 | — | 0.13 | 6.99 | 0.10 | 48.81 |
| 1000 | lazyHistoryIme | — | 14.49 | 2.62 | 35.45 | — | 0.14 | 6.97 | 0.09 | 60.27 |
| 5000 | chunkedLayers | — | 53.34 | 4.92 | — | 5.70 | 5.82 | 1565.72 | 163.93 | — |
| 5000 | lazyHistory | — | 73.00 | 4.86 | 365.01 | — | 0.12 | 41.29 | 0.81 | 80.82 |
| 5000 | lazyHistoryIme | — | 58.74 | 5.52 | 320.09 | — | 0.11 | 54.22 | 0.29 | 57.77 |
| 10000 | chunkedLayers | — | 86.76 | 8.19 | — | 2.55 | 2.64 | 2081.13 | 30.11 | — |
| 10000 | lazyHistory | — | 73.20 | 8.32 | 943.05 | — | 0.14 | 22.66 | 0.60 | 67.73 |
| 10000 | lazyHistoryIme | — | 96.04 | 9.11 | 801.48 | — | 0.16 | 25.78 | 0.70 | 70.65 |

## activeRowUpdate — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.08 | 0.49 | 0.14 | — | 0.03 | 0.07 | — | 0.01 | 0.48 |
| 1000 | lazyHistory | 0.07 | 0.82 | 0.11 | 0.41 | — | 0.12 | — | 0.01 | 66.22 |
| 1000 | lazyHistoryIme | 0.07 | 0.60 | 0.19 | 0.36 | — | 0.12 | — | 0.01 | 59.05 |
| 5000 | chunkedLayers | 0.08 | 0.25 | 0.13 | — | 0.03 | 0.07 | — | 0.01 | 0.38 |
| 5000 | lazyHistory | 0.07 | 0.60 | 0.10 | 0.35 | — | 0.10 | — | 0.01 | 64.62 |
| 5000 | lazyHistoryIme | 0.07 | 0.29 | 0.19 | 0.35 | — | 0.11 | — | 0.01 | 77.52 |
| 10000 | chunkedLayers | 0.07 | 0.49 | 0.11 | — | 0.07 | 0.12 | — | 0.01 | 0.50 |
| 10000 | lazyHistory | 0.14 | 0.44 | 0.11 | 0.31 | — | 0.10 | — | 0.01 | 56.07 |
| 10000 | lazyHistoryIme | 0.07 | 0.58 | 0.10 | 0.47 | — | 0.18 | — | 0.01 | 83.29 |

## appendAndTrim — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.12 | 0.24 | 0.57 | — | 0.16 | 0.24 | — | 0.01 | — |
| 1000 | lazyHistory | 0.06 | 0.46 | 0.41 | 0.38 | — | 0.15 | — | 0.01 | 71.07 |
| 1000 | lazyHistoryIme | 0.07 | 1.02 | 0.54 | 0.44 | — | 0.22 | — | 0.01 | 66.60 |
| 5000 | chunkedLayers | 0.08 | 0.23 | 0.38 | — | 0.09 | 0.12 | — | 0.01 | — |
| 5000 | lazyHistory | 0.07 | 0.38 | 0.31 | 0.46 | — | 0.13 | — | 0.01 | 53.40 |
| 5000 | lazyHistoryIme | 0.10 | 0.42 | 0.27 | 0.53 | — | 0.14 | — | 0.01 | 69.80 |
| 10000 | chunkedLayers | 0.08 | 0.35 | 0.44 | — | 0.08 | 0.11 | — | 0.01 | — |
| 10000 | lazyHistory | 0.08 | 0.57 | 0.25 | 0.66 | — | 0.16 | — | 0.01 | 71.07 |
| 10000 | lazyHistoryIme | 0.10 | 0.58 | 0.35 | 0.47 | — | 0.16 | — | 0.01 | 62.99 |

## semanticJump — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | — | — | — | — | 0.01 | 0.03 | — | 0.01 | 558.81 |
| 1000 | lazyHistory | — | — | — | — | — | 0.05 | — | 0.01 | 377.65 |
| 1000 | lazyHistoryIme | — | — | — | — | — | 0.05 | — | 0.01 | 390.67 |
| 5000 | chunkedLayers | — | — | — | — | 0.01 | 0.04 | — | 0.01 | 600.88 |
| 5000 | lazyHistory | — | — | — | — | — | 0.04 | — | 0.01 | 385.55 |
| 5000 | lazyHistoryIme | — | — | — | — | — | 0.05 | — | 0.01 | 612.16 |
| 10000 | chunkedLayers | — | — | — | — | 0.01 | 0.04 | — | 0.01 | 638.65 |
| 10000 | lazyHistory | — | — | — | — | — | 0.05 | — | 0.01 | 420.78 |
| 10000 | lazyHistoryIme | — | — | — | — | — | 0.05 | — | 0.01 | 378.27 |

### Transitions (ms)

| History | Mode | jumpTop | jumpTail |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 648.13 | 651.06 |
| 1000 | lazyHistory | 464.40 | 440.37 |
| 1000 | lazyHistoryIme | 489.12 | 426.22 |
| 5000 | chunkedLayers | 705.62 | 651.05 |
| 5000 | lazyHistory | 465.88 | 498.27 |
| 5000 | lazyHistoryIme | 701.38 | 479.34 |
| 10000 | chunkedLayers | 725.19 | 696.01 |
| 10000 | lazyHistory | 504.57 | 463.15 |
| 10000 | lazyHistoryIme | 475.67 | 384.82 |

## imeRoundTrip — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.08 | 0.28 | 0.14 | — | 0.03 | 0.07 | 0.21 | 0.02 | — |
| 1000 | lazyHistory | 0.08 | 0.42 | 0.12 | 3.30 | 0.05 | 0.10 | 68.53 | 2.70 | 0.04 |
| 1000 | lazyHistoryIme | 0.09 | 0.27 | 0.15 | 0.24 | — | 0.07 | 3.13 | 0.05 | — |
| 5000 | chunkedLayers | 0.09 | 0.39 | 0.14 | — | 0.02 | 0.07 | 0.59 | 0.02 | — |
| 5000 | lazyHistory | 0.09 | 0.68 | 0.13 | 1.41 | 0.15 | 0.21 | 450.76 | 40.31 | 0.06 |
| 5000 | lazyHistoryIme | 0.08 | 0.91 | 0.14 | 0.74 | — | 0.07 | 9.68 | 0.35 | — |
| 10000 | chunkedLayers | 0.09 | 0.54 | 0.13 | — | 0.02 | 0.06 | 4.30 | 0.01 | — |
| 10000 | lazyHistory | 0.08 | 0.27 | 0.12 | 0.99 | 0.11 | 0.16 | 203.65 | 12.60 | 0.04 |
| 10000 | lazyHistoryIme | 0.08 | 0.27 | 0.12 | 0.25 | — | 0.07 | 1.70 | 0.05 | — |

### Transitions (ms)

| History | Mode | imeShow | imeHide | explicitRetry |
| ---: | --- | ---: | ---: | ---: |
| 1000 | chunkedLayers | 1262.75 | 884.84 | 222.52 |
| 1000 | lazyHistory | 1711.74 | 967.79 | 401.20 |
| 1000 | lazyHistoryIme | 1244.53 | 952.96 | 216.92 |
| 5000 | chunkedLayers | 1178.21 | 922.40 | 205.12 |
| 5000 | lazyHistory | 6479.66 | 916.03 | 377.04 |
| 5000 | lazyHistoryIme | 1191.01 | 845.53 | 213.38 |
| 10000 | chunkedLayers | 1210.08 | 1005.59 | 220.98 |
| 10000 | lazyHistory | 8739.68 | 892.57 | 427.74 |
| 10000 | lazyHistoryIme | 1201.54 | 911.23 | 192.47 |

## detachRestore — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.51 | 0.38 | 0.85 | — | 0.10 | 0.26 | 347.95 | 15.66 | 0.06 |
| 1000 | lazyHistory | 0.56 | 0.34 | 2.82 | 43.06 | — | 0.08 | 11.00 | 0.12 | 101.87 |
| 1000 | lazyHistoryIme | 0.63 | 0.44 | 0.93 | 56.90 | — | 0.08 | 13.43 | 0.10 | 115.64 |
| 5000 | chunkedLayers | 0.66 | 0.37 | 5.21 | — | 0.48 | 1.04 | 2945.92 | 92.57 | 0.06 |
| 5000 | lazyHistory | 0.69 | 0.37 | 5.08 | 598.03 | — | 0.09 | 113.04 | 0.36 | 131.33 |
| 5000 | lazyHistoryIme | 0.54 | 0.35 | 7.17 | 750.86 | — | 0.10 | 22.68 | 0.17 | 117.15 |
| 10000 | chunkedLayers | 1.09 | 0.40 | 7.16 | — | 1.46 | 3.02 | 1408.60 | 48.19 | 0.08 |
| 10000 | lazyHistory | 0.53 | 0.36 | 13.84 | 1197.16 | — | 0.15 | 23.04 | 0.17 | 114.68 |
| 10000 | lazyHistoryIme | 0.54 | 0.33 | 18.12 | 1487.56 | — | 0.10 | 78.26 | 0.17 | 112.31 |

### Transitions (ms)

| History | Mode | detach | restore |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 48.71 | 718.85 |
| 1000 | lazyHistory | 45.72 | 267.40 |
| 1000 | lazyHistoryIme | 48.19 | 316.80 |
| 5000 | chunkedLayers | 163.04 | 5938.87 |
| 5000 | lazyHistory | 41.91 | 1030.37 |
| 5000 | lazyHistoryIme | 54.97 | 1033.06 |
| 10000 | chunkedLayers | 236.96 | 8204.66 |
| 10000 | lazyHistory | 41.49 | 1530.85 |
| 10000 | lazyHistoryIme | 36.92 | 1810.44 |

## 验收边界

API 34 x86_64 KVM 模拟器；Full 编译；非 debuggable、profileable、未混淆的 Release 派生目标。
复用真实生产渲染、视口控制器和系统键盘；不含完整页面、实际 shell/PTY、输入回显延迟或手机长期稳定性。
本报告不宣称真实手机 FPS，不能把历史版本的其他 Runner 测量当成受控前后对照。
