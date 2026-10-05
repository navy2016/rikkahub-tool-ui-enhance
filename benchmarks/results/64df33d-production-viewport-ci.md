# 生产终端视口基准：64df33d

来源：[37255295641](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37255295641)；SHA：`64df33d466965cd7851cc80954abd27251b03e07`；协议：`production-viewport-v2`。

六场景 × 三种历史长度 × 两种生产模式 = 36 个用例，每例三次，共 108 次测量；全部成功。
每个场景内共享 APK、设备和仪器调用；不同场景／Runner／协议的帧样本不得合并，也不计算跨运行提升率。

原始设备／输入法上下文、源文件哈希和 APK 哈希由[只读导出](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37256826428)传回并校验完整性，保存在同名 JSON。
目标 APK SHA-256：`c63ea46e4651ecf93f42b163fc01e7559800925fa1be8903a2808bf4baeeba05`。

表格来源为通过完整校验后的 CI 摘要，保留两位小数；不是原始 Perfetto 帧样本。缺失项为 —，不补零。
时间单位 ms；RSS 为匿名 RSS 采样峰值的迭代中位数（MiB），不是 PSS／Java／Native／GPU 分项峰值。
输出/次包含生产协调和两次稳定绘制确认；不是无限速 PTY 吞吐。各阶段重叠，不相减分解耗时。

| 场景 | 历史行 | 模式 | 次数 | 首屏 | 总操作 | 输出/次 | CPU p95 | 宽度/次 | RSS anon |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| initialCompose | 1000 | chunkedLayers | 3 | 459.48 | — | — | 313.70 | — | 104.51 |
| initialCompose | 1000 | lazyHistory | 3 | 402.85 | — | — | 177.25 | 43.26 | 93.75 |
| initialCompose | 5000 | chunkedLayers | 3 | 3488.54 | — | — | 1500.94 | — | 221.40 |
| initialCompose | 5000 | lazyHistory | 3 | 969.30 | — | — | 778.31 | 454.75 | 135.86 |
| initialCompose | 10000 | chunkedLayers | 3 | 7490.97 | — | — | 180.78 | — | 392.05 |
| initialCompose | 10000 | lazyHistory | 3 | 2224.43 | — | — | 2176.27 | 1623.14 | 185.27 |
| activeRowUpdate | 1000 | chunkedLayers | 3 | — | 5015.59 | 167.18 | 130.84 | — | 108.11 |
| activeRowUpdate | 1000 | lazyHistory | 3 | — | 5687.48 | 189.58 | 128.90 | 2.31 | 113.54 |
| activeRowUpdate | 5000 | chunkedLayers | 3 | — | 5105.94 | 170.19 | 128.05 | — | 168.33 |
| activeRowUpdate | 5000 | lazyHistory | 3 | — | 5550.05 | 185.00 | 130.59 | 3.98 | 122.09 |
| activeRowUpdate | 10000 | chunkedLayers | 3 | — | 5265.01 | 175.50 | 129.96 | — | 254.44 |
| activeRowUpdate | 10000 | lazyHistory | 3 | — | 5443.22 | 181.44 | 131.51 | 2.37 | 164.71 |
| appendAndTrim | 1000 | chunkedLayers | 3 | — | 5976.06 | 199.19 | 133.11 | — | 131.91 |
| appendAndTrim | 1000 | lazyHistory | 3 | — | 7265.55 | 242.18 | 127.37 | 4.16 | 115.10 |
| appendAndTrim | 5000 | chunkedLayers | 3 | — | 6873.60 | 229.11 | 142.64 | — | 184.51 |
| appendAndTrim | 5000 | lazyHistory | 3 | — | 7279.72 | 242.65 | 127.82 | 4.42 | 129.58 |
| appendAndTrim | 10000 | chunkedLayers | 3 | — | 8700.83 | 290.02 | 196.76 | — | 330.52 |
| appendAndTrim | 10000 | lazyHistory | 3 | — | 7401.06 | 246.70 | 129.05 | 6.06 | 213.19 |
| semanticJump | 1000 | chunkedLayers | 3 | — | 1209.77 | — | 95.69 | — | 106.18 |
| semanticJump | 1000 | lazyHistory | 3 | — | 695.47 | — | 99.46 | — | 119.87 |
| semanticJump | 5000 | chunkedLayers | 3 | — | 1330.46 | — | 93.25 | — | 203.53 |
| semanticJump | 5000 | lazyHistory | 3 | — | 690.56 | — | 106.61 | — | 149.88 |
| semanticJump | 10000 | chunkedLayers | 3 | — | 1354.84 | — | 94.09 | — | 272.90 |
| semanticJump | 10000 | lazyHistory | 3 | — | 732.65 | — | 103.41 | — | 207.23 |
| imeRoundTrip | 1000 | chunkedLayers | 3 | — | 4355.69 | 219.37 | 304.07 | — | 107.02 |
| imeRoundTrip | 1000 | lazyHistory | 3 | — | 5165.12 | 210.92 | 270.38 | 0.94 | 139.66 |
| imeRoundTrip | 5000 | chunkedLayers | 3 | — | 4192.35 | 218.50 | 241.47 | — | 169.00 |
| imeRoundTrip | 5000 | lazyHistory | 3 | — | 10621.77 | 233.87 | 229.86 | 1.31 | 233.82 |
| imeRoundTrip | 10000 | chunkedLayers | 3 | — | 4297.59 | 221.26 | 277.15 | — | 278.57 |
| imeRoundTrip | 10000 | lazyHistory | 3 | — | 12435.12 | 221.57 | 278.02 | 0.97 | 289.89 |
| detachRestore | 1000 | chunkedLayers | 3 | — | 821.19 | — | 574.41 | — | 140.17 |
| detachRestore | 1000 | lazyHistory | 3 | — | 405.89 | — | 175.39 | 61.31 | 102.45 |
| detachRestore | 5000 | chunkedLayers | 3 | — | 4050.85 | — | 117.98 | — | 263.37 |
| detachRestore | 5000 | lazyHistory | 3 | — | 1092.26 | — | 860.82 | 663.58 | 142.41 |
| detachRestore | 10000 | chunkedLayers | 3 | — | 7484.92 | — | 294.54 | — | 471.43 |
| detachRestore | 10000 | lazyHistory | 3 | — | 1901.64 | — | 1654.94 | 1511.96 | 177.42 |

## initialCompose — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | — | 20.40 | 3.97 | — | — | 0.23 | 118.16 | 3.34 | 0.32 |
| 1000 | lazyHistory | — | 16.29 | 4.69 | 43.26 | — | 0.13 | 11.67 | 0.13 | 47.87 |
| 5000 | chunkedLayers | — | 50.34 | 4.54 | — | — | 0.26 | 1985.96 | 143.34 | 0.38 |
| 5000 | lazyHistory | — | 68.96 | 3.95 | 454.75 | — | 0.13 | 43.73 | 0.21 | 49.88 |
| 10000 | chunkedLayers | — | 118.36 | 10.61 | — | — | 0.19 | 4887.72 | 117.33 | 0.42 |
| 10000 | lazyHistory | — | 105.22 | 9.80 | 1623.14 | — | 0.16 | 45.27 | 0.22 | 60.35 |

## activeRowUpdate — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.10 | 0.29 | 0.15 | — | — | 0.04 | — | 0.01 | — |
| 1000 | lazyHistory | 0.08 | 0.65 | 0.22 | 2.31 | — | 0.16 | — | 0.01 | 70.61 |
| 5000 | chunkedLayers | 0.08 | 0.46 | 0.12 | — | — | 0.03 | — | 0.01 | — |
| 5000 | lazyHistory | 0.07 | 0.34 | 0.12 | 3.98 | — | 0.13 | — | 0.01 | 69.51 |
| 10000 | chunkedLayers | 0.08 | 0.38 | 0.12 | — | — | 0.03 | — | 0.01 | — |
| 10000 | lazyHistory | 0.08 | 0.43 | 0.11 | 2.37 | — | 0.15 | — | 0.01 | 68.14 |

## appendAndTrim — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.12 | 0.42 | 0.34 | — | — | 0.04 | — | 0.01 | — |
| 1000 | lazyHistory | 0.30 | 0.73 | 0.36 | 4.16 | — | 0.24 | — | 0.01 | 78.89 |
| 5000 | chunkedLayers | 0.12 | 0.58 | 0.88 | — | — | 0.04 | — | 0.01 | — |
| 5000 | lazyHistory | 0.16 | 1.06 | 0.44 | 4.42 | — | 0.18 | — | 0.01 | 109.98 |
| 10000 | chunkedLayers | 0.12 | 0.53 | 0.58 | — | — | 0.04 | — | 0.01 | — |
| 10000 | lazyHistory | 0.19 | 0.68 | 1.13 | 6.06 | — | 0.26 | — | 0.01 | 86.55 |

## semanticJump — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | — | — | — | — | — | 0.03 | — | 0.01 | 542.05 |
| 1000 | lazyHistory | — | — | — | — | — | 0.04 | — | 0.01 | 309.21 |
| 5000 | chunkedLayers | — | — | — | — | — | 0.02 | — | 0.01 | 598.20 |
| 5000 | lazyHistory | — | — | — | — | — | 0.11 | — | 0.01 | 309.07 |
| 10000 | chunkedLayers | — | — | — | — | — | 0.03 | — | 0.04 | 606.79 |
| 10000 | lazyHistory | — | — | — | — | — | 0.04 | — | 0.01 | 293.78 |

### Transitions (ms)

| History | Mode | jumpTop | jumpTail |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 621.93 | 598.32 |
| 1000 | lazyHistory | 371.97 | 337.89 |
| 5000 | chunkedLayers | 667.20 | 663.24 |
| 5000 | lazyHistory | 378.42 | 312.12 |
| 10000 | chunkedLayers | 674.11 | 705.65 |
| 10000 | lazyHistory | 389.60 | 347.87 |

## imeRoundTrip — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.11 | 0.27 | 0.15 | — | — | 0.04 | 0.24 | 0.02 | — |
| 1000 | lazyHistory | 0.11 | 0.60 | 0.12 | 0.94 | 0.10 | 0.40 | 93.28 | 5.09 | 0.08 |
| 5000 | chunkedLayers | 0.09 | 0.24 | 0.17 | — | — | 0.03 | 1.73 | 0.01 | — |
| 5000 | lazyHistory | 0.08 | 0.29 | 0.13 | 1.31 | 0.34 | 0.39 | 350.89 | 41.65 | 0.07 |
| 10000 | chunkedLayers | 0.09 | 0.45 | 0.16 | — | — | 0.05 | 6.83 | 0.02 | — |
| 10000 | lazyHistory | 0.09 | 0.24 | 0.11 | 0.97 | 0.09 | 0.13 | 228.14 | 14.01 | 0.04 |

### Transitions (ms)

| History | Mode | imeShow | imeHide | explicitRetry |
| ---: | --- | ---: | ---: | ---: |
| 1000 | chunkedLayers | 1273.14 | 1115.35 | 227.99 |
| 1000 | lazyHistory | 2076.86 | 965.84 | 371.61 |
| 5000 | chunkedLayers | 1309.71 | 879.25 | 215.01 |
| 5000 | lazyHistory | 7372.72 | 948.65 | 419.24 |
| 10000 | chunkedLayers | 1280.43 | 923.07 | 221.88 |
| 10000 | lazyHistory | 9246.28 | 904.81 | 570.21 |

## detachRestore — 原始阶段与转换摘要

### Phase means per invocation (median across iterations)

| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1000 | chunkedLayers | 0.52 | 0.38 | 0.75 | — | — | 0.08 | 259.02 | 24.85 | 0.10 |
| 1000 | lazyHistory | 3.86 | 0.36 | 0.75 | 61.31 | — | 0.09 | 20.97 | 0.37 | 123.92 |
| 5000 | chunkedLayers | 0.56 | 0.31 | 15.03 | — | — | 0.21 | 2359.85 | 136.36 | 0.06 |
| 5000 | lazyHistory | 0.63 | 0.45 | 6.88 | 663.58 | — | 0.08 | 39.16 | 0.15 | 113.44 |
| 10000 | chunkedLayers | 0.70 | 3.66 | 11.49 | — | — | 0.47 | 4891.88 | 138.62 | 0.05 |
| 10000 | lazyHistory | 0.54 | 0.34 | 7.55 | 1511.96 | — | 0.14 | 14.15 | 0.15 | 117.64 |

### Transitions (ms)

| History | Mode | detach | restore |
| ---: | --- | ---: | ---: |
| 1000 | chunkedLayers | 96.10 | 738.39 |
| 1000 | lazyHistory | 38.80 | 364.14 |
| 5000 | chunkedLayers | 102.96 | 3916.17 |
| 5000 | lazyHistory | 44.51 | 1052.18 |
| 10000 | chunkedLayers | 289.58 | 7157.48 |
| 10000 | lazyHistory | 74.19 | 1861.73 |

## 验收边界

API 34 x86_64 KVM 模拟器；Full 编译；非 debuggable、profileable、未混淆的 Release 派生目标。
复用真实生产渲染、视口控制器和系统键盘；不含完整页面、实际 shell/PTY、输入回显延迟或手机长期稳定性。
本报告不宣称真实手机 FPS，不能把历史版本的其他 Runner 测量当成受控前后对照。
