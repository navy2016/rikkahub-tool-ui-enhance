# Terminal scrollback rendering benchmark

## Scope

Measure **1,000 / 5,000 / 10,000 history rows + 24 active rows** before choosing a lazy history renderer.
This step does **not** migrate history to `LazyColumn`, change the viewport reducer/controller, or change
TUI physical-grid semantics.

The opt-in `terminal-target` APK compiles the production `TerminalEmulator`, stable-ID helper,
`TerminalRenderedRows` and font sources via Gradle `Sync` tasks. Generated copies are only in `build/`;
there is no second row-diff/Text implementation to drift away from production. `ProcessSessionPage`
uses the same extracted helpers, still inside its existing atomic snapshot and scroll container.

The target has a **separate application ID**, no shell/rootfs/native dependencies, no network permission,
and no production user data. It is non-debuggable, profileable, debug-key signed, and unminified. Like
the production app it uses `largeHeap=true`. Macrobenchmark uses `CompilationMode.Full()`.
Normal builds do not include either benchmark module unless `-PterminalBenchmarks=true` is supplied.

### Fixed workload

- 80 physical columns, 24 active rows, JetBrains Mono 14sp, no font padding, 8dp tail padding.
- Deterministic unique lines with ANSI colors/bold and mixed ASCII/CJK, narrower than 80 cells.
- A fresh process/emulator model per measured iteration; shell-like fixture seeding is **outside** timing.
- Native control/status views above the terminal. Accessibility traversal of the Compose subtree is
  disabled only in the fixture to avoid timing a 10k-node UiAutomator tree walk; Text/layout/draw remain.
- First composition measures the **cold** `renderFrame` cache. Other scenarios mount first, outside the
  timed block, then measure **warm** cached-history work.

| Scenario | Measured work |
| --- | --- |
| `initialCompose` | Cold `renderFrame`, row-state creation, eager composition/layout/draw. Async mount→draw trace ends after two real draw callbacks. Not app launch time or GPU presentation time. |
| `historyScroll` | Pan six viewport heights away from the tail and back, two 1-second linear `ScrollState` animations. Fixed distance/time, not gesture/fling physics. |
| `activeRowUpdate` | Rewrite the last active line 30 times; all stable IDs and history remain unchanged. |
| `appendAndTrim` | Append one line 30 times at the history cap; oldest rows trim, surviving stable IDs/states move. Already at the tail; extent remains constant. |
| `alternateScreenUpdate` | Preload the same history, enter alternate screen, update the last line 30 times. Only 24 physical rows render; the history stays hidden. |

Updates target 33ms intervals and await a draw for every operation. If rendering is slow, duration grows
rather than silently coalescing/dropping benchmark updates. This is an isolated renderer throughput test,
**not** an end-to-end measurement of the production output scheduler/controller/IME/selection behavior.

## Collected data

AndroidX Macrobenchmark JSON + Perfetto traces, with:

- `FrameTimingMetric`: CPU frame p50/p95, frame overrun p95 and sampled frame counts.
- `Terminal.mountToDraw`: first mount latency, excluding fixture feed and app startup.
- `Terminal.renderFrame` / `Terminal.rowSync`: sums and call counts; the summary divides each iteration's
  sum by its count before taking the median. Row synchronization includes snapshot application, but
  **not** asynchronous Compose layout/draw. `Terminal.feed` is also traced for inspection.
- `Terminal.measure` / `Terminal.draw` trace sections and `rowSyncMaxMs` for diagnosis in raw data.
- `MemoryUsageMetric(Max)`: sampled memory counters. RSS anonymous is not total RSS/PSS; heap counters
  can be absent if no GC sampling occurred. Missing measurements are shown as `—`, never as zero.
- Git SHA, production-source/font SHA-256, Android/device context, viewport size/density, heap limit,
  iteration count, logcat, test reports and both benchmark APKs.

The summary fails on incomplete size/scenario matrices, missing frame samples, or missing/incorrect
render/sync trace counts. A test with no trace data is not a successful performance measurement.

## Run

The **Terminal Scrollback Benchmark** Actions workflow builds and measures on one API 34 x86_64 emulator
(Nexus 6 profile, 2 cores, 4GiB RAM, 768MiB heap, SwiftShader). CI runs three repetitions per case by default.
It suppresses **only** AndroidX's `EMULATOR` warning, not debuggable/profileable failures.

To run on a dedicated, authorized physical test device from a normal Android SDK development host:

```bash
./gradlew -PterminalBenchmarks=true \
  :benchmarks:terminal-target:testBenchmarkUnitTest \
  :benchmarks:terminal-macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.benchmark.TerminalScrollbackBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.terminalIterations=5

python3 benchmarks/summarize.py benchmarks/terminal-macrobenchmark/build/outputs \
  --sha "$(git rev-parse HEAD)" --environment physical-device --require-complete \
  --output /tmp/terminal-scrollback-summary.md
```

Do not pass `suppressErrors=EMULATOR` for a physical-device acceptance run. Keep device, OS, font,
orientation, display refresh rate, build variant, thermal state and workload constant for before/after
comparisons. Do not combine artifacts from different devices/runs in one summary input directory.

Python report checks (no Gradle/APK build required):

```bash
python3 -m unittest discover -s benchmarks -p 'test_*.py'
```

## Decision rule

CI/emulator results are a **diagnostic baseline**, not real-device FPS. Initial mounting has very few
frames; do not use its p95 alone. Inspect mount latency, scaling from 1k→5k→10k, append/trim frames,
row-sync traces and memory together, then reproduce on a representative physical device.

- If warm `renderFrame`/row synchronization dominates, fix that path rather than assuming `LazyColumn`
  will help. A lazy UI alone does not eliminate an O(history) row-state update.
- If eager composition/layout/draw or retained UI state dominates at larger histories, prototype lazy
  **ordinary history only**. Keep TUI/alternate-screen physical-grid rendering and coordinate ownership
  intact; rerun the existing viewport event-sequence tests and this same benchmark.
- Do not merge a lazy migration based solely on emulator timings or on a JVM `renderFrame` microbenchmark.

## Recorded baselines

- [2026-09-23: eager Column, 1k / 5k / 10k](results/2026-09-23-da85ea9-ci-emulator.md)
  — measured commit `da85ea9`, API 34 CI emulator, 3 repetitions for each of 15 cases;
  benchmark and terminal regression workflows both passed on that SHA.

This baseline shows particularly poor scaling for append/trim, while the 24-row alternate-screen
control stays roughly flat. The next experiment is a **benchmark-only, ordinary-history LazyColumn
A/B candidate**, not a production viewport migration. No lazy-renderer speedup has been measured yet.
The report includes limitations, raw-artifact provenance and the integration checks required later.
