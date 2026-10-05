# 生产终端视口基准：7a1d711

来源：[37219099548](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37219099548)；SHA：`7a1d71189c1ea7c2dff6f77eccdab0f3383f2483`；协议：`production-viewport-v1`。

六场景 × 三种历史长度 × 两种生产模式 = 36 个用例，每例三次，共 108 次测量；全部成功。
每个场景内共享 APK、设备和仪器调用；不同场景／Runner／协议的帧样本不得合并，也不计算跨运行提升率。

原始设备／输入法上下文、源文件哈希和 APK 哈希由[只读导出](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37254522411)传回并校验完整性，保存在同名 JSON。
目标 APK SHA-256：`ad0c8f96c6f92c3123e7a6ac195b5c4eb855370c7a88c97c0360e67b81bf7719`。

表格来源为通过完整校验后的 CI 摘要，保留两位小数；不是原始 Perfetto 帧样本。缺失项为 —，不补零。
时间单位 ms；RSS 为匿名 RSS 采样峰值的迭代中位数（MiB），不是 PSS／Java／Native／GPU 分项峰值。
输出/次包含生产协调和两次稳定绘制确认；不是无限速 PTY 吞吐。各阶段重叠，不相减分解耗时。

| 场景 | 历史行 | 模式 | 次数 | 首屏 | 总操作 | 输出/次 | CPU p95 | 宽度/次 | RSS anon |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| initialCompose | 1000 | chunkedLayers | 3 | 634.56 | — | — | 417.23 | — | 104.93 |
| initialCompose | 1000 | lazyHistory | 3 | 418.89 | — | — | 213.43 | 74.05 | 93.89 |
| initialCompose | 5000 | chunkedLayers | 3 | 3806.51 | — | — | 221.28 | — | 206.85 |
| initialCompose | 5000 | lazyHistory | 3 | 649.90 | — | — | 350.11 | 232.85 | 136.80 |
| initialCompose | 10000 | chunkedLayers | 3 | 7442.31 | — | — | 231.93 | — | 339.75 |
| initialCompose | 10000 | lazyHistory | 3 | 1724.41 | — | — | 1302.77 | 1204.50 | 189.01 |
| activeRowUpdate | 1000 | chunkedLayers | 3 | — | 4522.55 | 150.75 | 117.34 | — | 109.12 |
| activeRowUpdate | 1000 | lazyHistory | 3 | — | 4969.66 | 165.65 | 117.82 | 2.19 | 103.42 |
| activeRowUpdate | 5000 | chunkedLayers | 3 | — | 4665.68 | 155.52 | 117.04 | — | 173.12 |
| activeRowUpdate | 5000 | lazyHistory | 3 | — | 4936.01 | 164.53 | 114.52 | 2.97 | 123.93 |
| activeRowUpdate | 10000 | chunkedLayers | 3 | — | 4748.22 | 158.27 | 118.75 | — | 259.66 |
| activeRowUpdate | 10000 | lazyHistory | 3 | — | 5111.50 | 170.38 | 116.34 | 2.80 | 155.70 |
| appendAndTrim | 1000 | chunkedLayers | 3 | — | 5314.18 | 177.14 | 114.13 | — | 132.16 |
| appendAndTrim | 1000 | lazyHistory | 3 | — | 6174.52 | 205.81 | 114.34 | 5.47 | 118.16 |
| appendAndTrim | 5000 | chunkedLayers | 3 | — | 6460.94 | 215.36 | 126.25 | — | 187.77 |
| appendAndTrim | 5000 | lazyHistory | 3 | — | 6421.94 | 214.06 | 117.49 | 4.47 | 130.32 |
| appendAndTrim | 10000 | chunkedLayers | 3 | — | 7998.89 | 266.63 | 166.27 | — | 312.95 |
| appendAndTrim | 10000 | lazyHistory | 3 | — | 6442.29 | 214.74 | 114.66 | 4.48 | 161.00 |
| semanticJump | 1000 | chunkedLayers | 3 | — | 1299.50 | — | 124.38 | — | 106.11 |
| semanticJump | 1000 | lazyHistory | 3 | — | 948.82 | — | 171.83 | — | 125.12 |
| semanticJump | 5000 | chunkedLayers | 3 | — | 1422.75 | — | 124.50 | — | 164.48 |
| semanticJump | 5000 | lazyHistory | 3 | — | 1025.86 | — | 184.29 | — | 134.97 |
| semanticJump | 10000 | chunkedLayers | 3 | — | 1453.55 | — | 123.28 | — | 264.27 |
| semanticJump | 10000 | lazyHistory | 3 | — | 1035.43 | — | 167.95 | — | 170.02 |
| imeRoundTrip | 1000 | chunkedLayers | 3 | — | 4201.06 | 236.16 | 277.67 | — | 107.55 |
| imeRoundTrip | 1000 | lazyHistory | 3 | — | 5215.17 | 218.04 | 238.48 | 76.40 | 139.98 |
| imeRoundTrip | 5000 | chunkedLayers | 3 | — | 3990.07 | 213.14 | 258.15 | — | 169.23 |
| imeRoundTrip | 5000 | lazyHistory | 3 | — | 10795.35 | 230.86 | 283.16 | 230.87 | 231.82 |
| imeRoundTrip | 10000 | chunkedLayers | 3 | — | 4138.14 | 213.36 | 276.43 | — | 274.54 |
| imeRoundTrip | 10000 | lazyHistory | 3 | — | 12624.55 | 219.84 | 260.29 | 367.12 | 296.34 |
| detachRestore | 1000 | chunkedLayers | 3 | — | 465.89 | — | 229.02 | — | 140.76 |
| detachRestore | 1000 | lazyHistory | 3 | — | 292.07 | — | 114.65 | 46.60 | 102.87 |
| detachRestore | 5000 | chunkedLayers | 3 | — | 2663.30 | — | 2198.12 | — | 266.51 |
| detachRestore | 5000 | lazyHistory | 3 | — | 778.70 | — | 431.77 | 402.85 | 140.07 |
| detachRestore | 10000 | chunkedLayers | 3 | — | 4677.87 | — | 166.64 | — | 495.98 |
| detachRestore | 10000 | lazyHistory | 3 | — | 1311.31 | — | 948.28 | 962.45 | 227.68 |

## 验收边界

API 34 x86_64 KVM 模拟器；Full 编译；非 debuggable、profileable、未混淆的 Release 派生目标。
复用真实生产渲染、视口控制器和系统键盘；不含完整页面、实际 shell/PTY、输入回显延迟或手机长期稳定性。
本报告不宣称真实手机 FPS，不能把历史版本的其他 Runner 测量当成受控前后对照。
