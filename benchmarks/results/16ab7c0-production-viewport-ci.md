# 生产终端视口基准：16ab7c0

来源：[37172835069](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37172835069)；完整 SHA：`16ab7c053d7a5decc3c1ea6e4dce9e454cf5375a`。

六个场景 × 三种历史长度 × 两种生产模式 = 36 个用例，每例三次，共 108 次测量；全部成功。
每个场景内使用同一 APK／模拟器／仪器进程对照；不同场景使用独立 Runner，禁止跨场景合并帧样本或计算比例。

此归档来自 CI 已通过校验的 check-run 数值摘要，精度为小数点后两位；不是原始 JSON 或 Perfetto。
原始帧样本、阶段细分、设备上下文和 trace 在该运行的独立场景 artifacts 中。未获取的指标不推算、不补零。

## 主要观察

- 10k 冷首屏：默认分块 4062.74 ms，虚拟历史 1031.29 ms；同场景匿名 RSS 采样峰值的迭代中位数分别为 416.36 / 198.52 MiB。
- 10k 单行追加／裁剪：等待稳定绘制后的操作均值中位数分别为 267.17 / 215.52 ms。该时间包含生产协调与两次稳定绘制确认，不是无限速 PTY 吞吐。
- 10k IME 回退／八次输出／隐藏／显式重试整个场景为 4306.49 / 12412.42 ms。虚拟模式在该交互中仍付出完整兼容布局与重新进入成本；不应默认推广。
- 10k 重新挂载：虚拟模式宽度扫描 1693.83 ms；冷宽度与 IME 回退值得独立优化。
- 此次两种渲染模式都使用分块快照优化前的实现。不能拿后续不同 Runner 的运行当成快照优化的受控前后对照。

## 全量摘要

时间单位 ms；RSS 为匿名 RSS MiB，不是总 PSS，也不是 Java／Native／GPU 独立峰值。
CPU p95 汇总捕获帧；其他字段按迭代中位数或迭代内平均再取中位数，不可相减分解耗时。

| 场景 | 历史行 | 模式 | 次数 | 首屏 | 总操作 | 输出/次 | CPU p95 | 宽度/次 | RSS anon |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| initialCompose | 1000 | chunkedLayers | 3 | 309.01 | — | — | 166.50 | — | 104.81 |
| initialCompose | 1000 | lazyHistory | 3 | 289.47 | — | — | 133.33 | 18.07 | 94.06 |
| initialCompose | 5000 | chunkedLayers | 3 | 1675.52 | — | — | 1344.82 | — | 221.09 |
| initialCompose | 5000 | lazyHistory | 3 | 542.40 | — | — | 280.01 | 208.16 | 136.53 |
| initialCompose | 10000 | chunkedLayers | 3 | 4062.74 | — | — | 186.68 | — | 416.36 |
| initialCompose | 10000 | lazyHistory | 3 | 1031.29 | — | — | 774.60 | 664.10 | 198.52 |
| activeRowUpdate | 1000 | chunkedLayers | 3 | — | 4070.84 | 135.69 | 107.87 | — | 109.17 |
| activeRowUpdate | 1000 | lazyHistory | 3 | — | 4577.23 | 152.57 | 107.81 | 2.91 | 103.39 |
| activeRowUpdate | 5000 | chunkedLayers | 3 | — | 4194.23 | 139.80 | 107.38 | — | 162.64 |
| activeRowUpdate | 5000 | lazyHistory | 3 | — | 4475.73 | 149.19 | 109.08 | 1.83 | 141.88 |
| activeRowUpdate | 10000 | chunkedLayers | 3 | — | 4395.78 | 146.52 | 107.75 | — | 259.61 |
| activeRowUpdate | 10000 | lazyHistory | 3 | — | 4540.21 | 151.34 | 104.74 | 2.98 | 181.33 |
| appendAndTrim | 1000 | chunkedLayers | 3 | — | 5419.32 | 180.64 | 113.69 | — | 132.04 |
| appendAndTrim | 1000 | lazyHistory | 3 | — | 6333.45 | 211.11 | 113.53 | 3.94 | 119.04 |
| appendAndTrim | 5000 | chunkedLayers | 3 | — | 6414.36 | 213.81 | 124.90 | — | 186.06 |
| appendAndTrim | 5000 | lazyHistory | 3 | — | 6367.80 | 212.26 | 114.11 | 5.12 | 132.27 |
| appendAndTrim | 10000 | chunkedLayers | 3 | — | 8015.31 | 267.17 | 175.64 | — | 306.60 |
| appendAndTrim | 10000 | lazyHistory | 3 | — | 6465.76 | 215.52 | 118.48 | 4.83 | 209.62 |
| semanticJump | 1000 | chunkedLayers | 3 | — | 1243.04 | — | 116.44 | — | 106.51 |
| semanticJump | 1000 | lazyHistory | 3 | — | 946.06 | — | 146.57 | — | 125.34 |
| semanticJump | 5000 | chunkedLayers | 3 | — | 1366.04 | — | 111.62 | — | 161.34 |
| semanticJump | 5000 | lazyHistory | 3 | — | 972.17 | — | 166.40 | — | 159.21 |
| semanticJump | 10000 | chunkedLayers | 3 | — | 1405.08 | — | 108.35 | — | 252.79 |
| semanticJump | 10000 | lazyHistory | 3 | — | 886.48 | — | 138.23 | — | 188.28 |
| imeRoundTrip | 1000 | chunkedLayers | 3 | — | 4239.30 | 236.51 | 262.33 | — | 107.74 |
| imeRoundTrip | 1000 | lazyHistory | 3 | — | 4987.44 | 217.26 | 253.87 | 56.73 | 139.73 |
| imeRoundTrip | 5000 | chunkedLayers | 3 | — | 4166.10 | 210.43 | 258.89 | — | 166.54 |
| imeRoundTrip | 5000 | lazyHistory | 3 | — | 10579.35 | 223.14 | 253.74 | 207.52 | 239.65 |
| imeRoundTrip | 10000 | chunkedLayers | 3 | — | 4306.49 | 216.11 | 281.39 | — | 283.03 |
| imeRoundTrip | 10000 | lazyHistory | 3 | — | 12412.42 | 211.28 | 290.33 | 367.35 | 296.48 |
| detachRestore | 1000 | chunkedLayers | 3 | — | 793.25 | — | 462.37 | — | 140.35 |
| detachRestore | 1000 | lazyHistory | 3 | — | 363.73 | — | 125.40 | 73.75 | 102.53 |
| detachRestore | 5000 | chunkedLayers | 3 | — | 4657.80 | — | 129.55 | — | 258.24 |
| detachRestore | 5000 | lazyHistory | 3 | — | 1055.34 | — | 742.13 | 666.99 | 144.91 |
| detachRestore | 10000 | chunkedLayers | 3 | — | 7326.90 | — | 247.39 | — | 477.80 |
| detachRestore | 10000 | lazyHistory | 3 | — | 2073.97 | — | 1703.10 | 1693.83 | 230.15 |

## 验收边界

API 34 x86_64 KVM 模拟器；非 debuggable、profileable、未混淆的 Release 派生目标；Full 编译。
使用生产渲染／滚动组件，但不包含完整 ProcessSessionPage、真实 shell/PTY、输入到回显或手机长期稳定性验收。
首屏帧样本很少，不能仅凭 p95 判断首屏体验。本报告不宣称实机 FPS、普遍性内存降幅或修复所有卡死。
