# Terminal scrollback rendering benchmark

## Scope

Compare eager `Column` against a **benchmark-only, history-only `LazyColumn`** at
**1,000 / 5,000 / 10,000 history rows + 24 active rows**. This step does **not** change the production
terminal, viewport reducer/controller, or TUI physical-grid semantics.

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
- Both renderer arms are compiled into one APK and run on one device in adjacent size/scenario pairs.
  Eager/lazy order alternates between pairs. Each arm has the same repetition count.
- Native control/status views above the terminal. Accessibility traversal of the Compose subtree is
  disabled only in the fixture to avoid timing a 10k-node UiAutomator tree walk; Text/layout/draw remain.
- First composition measures the **cold** `renderFrame` cache. Other scenarios mount first, outside the
  timed block, then measure **warm** cached-history work.

| Scenario | Measured work |
| --- | --- |
| `initialCompose` | Cold `renderFrame`, row-state creation, eager composition/layout/draw. Async mount→draw trace ends after two real draw callbacks. Not app launch time or GPU presentation time. |
| `historyScroll` | Pan six viewport heights away from the tail and back, two identical 1-second linear `ScrollableState.animateScrollBy` animations in both arms. Fixed distance/time, not gesture/fling physics. |
| `activeRowUpdate` | Rewrite the last active line 30 times; all stable IDs and history remain unchanged. |
| `appendAndTrim` | Append one line 30 times at the history cap; oldest rows trim and surviving stable IDs/states move. Both arms must remain at the newly measured tail. |
| `alternateScreenUpdate` | Preload the same history, enter alternate screen, update the last line 30 times. Only 24 physical rows render; **both arms use the original eager backend** and the history stays hidden. |

Updates target 33ms intervals and await a draw for every operation. If rendering is slow, duration grows
rather than silently coalescing/dropping benchmark updates. This is an isolated renderer throughput test,
**not** an end-to-end measurement of the production output scheduler/controller/IME/selection behavior.

## Candidate and correctness guards

`TerminalBenchmarkViewport` exists only in the opt-in benchmark target. The `eager` arm uses the existing
shared production row loop. The `lazyHistory` arm uses:

1. One history item per stable `lineId`, reusing the **same** production row/Text helper.
2. **One** `Column` item containing all active-screen rows, not 24 independently lazy items.
3. A separate 8dp tail item, so seeking the final item clamps to the actual bottom even if the active
   screen is taller than the viewport.

The LazyColumn is height-bounded and is not nested in a verticalScroll. Alternate screen, configured
TUI commands and full-grid mode all fall back to the eager backend; host tests cover these gates.
The candidate still pays for the same full emulator snapshot and O(history) row-state synchronization.

After every ordinary update, the candidate calls `requestScrollToItem(tailItemIndex)` **before the next
draw**. Both stable-key movement during trim and changed row metrics during a styled/CJK rewrite can
move the tail. The eager arm checks the measured range after layout, scrolls to the new maximum if
necessary, and awaits the corrected draw. This cost remains inside the measured window and has its
own `Terminal.eagerTailCorrection` trace. We do not change the mixed-style workload or relax assertions
to hide drift. Without correct tail-follow, an old row could remain visible while the changing screen
moves partly offscreen, producing a false speedup. Runtime checks verify the actual tail position after
mount, every output update, and the return pan. Candidate checks also require the tail and active-screen
item to be visible, exactly one active-grid composition, and fewer history compositions than the whole
history. Composition counters are non-observable; they do not drive recomposition.

A/B collection has **30 cases** (3 sizes × 5 scenarios × 2 arms). The summary must reject a missing arm,
mismatched repetitions, or missing follow-tail requests. Fixture assertion failures are surfaced to the
test driver rather than reported as successful timing samples. These guards are **not** production
viewport or text-selection acceptance tests.

## Collected data

AndroidX Macrobenchmark JSON + Perfetto traces, with:

- `FrameTimingMetric`: CPU frame p50/p95, frame overrun p95 and sampled frame counts.
- `Terminal.mountToDraw`: first mount latency, excluding fixture feed and app startup.
- `Terminal.renderFrame` / `Terminal.rowSync`: sums and call counts; the summary divides each iteration's
  sum by its count before taking the median. Row synchronization includes snapshot application, but
  **not** asynchronous Compose layout/draw. `Terminal.feed` is also traced for inspection.
- `Terminal.measure` / `Terminal.draw` per-call durations, `rowSyncMaxMs`, frame counts and
  `Terminal.followTail` request counts/durations; the report includes a separate trace-phase table.
  The root-measure trace can include LazyColumn's on-demand subcomposition; it is **not** an isolated
  pure-layout timer comparable to eager composition. Neither trace covers every recomposition,
  placement, prefetch, scheduling or GC cost.
- `Terminal.lazyHistoryCompositions` counters in traces for retained (including prefetched) history
  compositions, not a claim that every retained row is currently visible.
- `MemoryUsageMetric(Max)`: sampled memory counters. RSS anonymous is not total RSS/PSS; heap counters
  can be absent if no GC sampling occurred. Missing measurements are shown as `—`, never as zero.
- Git SHA, production-source/font SHA-256, Android/device context, viewport size/density, heap limit,
  iteration count, logcat, test reports and both benchmark APKs.

The summary fails on incomplete size/scenario/renderer matrices, missing frame samples, different result
documents/devices, or missing/incorrect render/sync/follow-tail trace counts. CI embeds source SHA, run ID
and suite in the AndroidX JSON payload; the summarizer checks SHA and suite rather than just relabeling data. A test with no trace data is not a successful performance measurement.

## Run

The **Terminal Scrollback Benchmark** Actions workflow has a 90-minute job budget and measures on one
API 34 x86_64 emulator
(Nexus 6 profile, 2 cores, 4GiB RAM, 768MiB heap, SwiftShader). CI runs three repetitions per case by default.
It suppresses **only** AndroidX's `EMULATOR` warning, not debuggable/profileable failures. CI first runs a
1k/one-repeat preflight of all output-update scenarios in both arms. Only if it passes does the full
matrix run in a fresh instrumentation invocation. Preflight output is archived separately and never
included in the A/B summary. Both measured arms run in one invocation; do not merge separate runs to
manufacture a paired comparison.

To run on a dedicated, authorized physical test device from a normal Android SDK development host:

```bash
./gradlew -PterminalBenchmarks=true \
  :benchmarks:terminal-target:testBenchmarkUnitTest \
  :benchmarks:terminal-macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.benchmark.TerminalScrollbackBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.terminalIterations=5

python3 benchmarks/summarize.py benchmarks/terminal-macrobenchmark/build/outputs \
  --sha "$(git rev-parse HEAD)" --environment physical-device --suite ab --require-complete \
  --output /tmp/terminal-scrollback-summary.md
```

Do not pass `suppressErrors=EMULATOR` for a physical-device acceptance run. Keep device, OS, font,
orientation, display refresh rate, build variant, thermal state and workload constant for before/after
comparisons. Do not combine artifacts from different devices/runs in one summary input directory.
To re-summarize the archived eager-only format, use `--suite baseline` instead and supply the original
measured commit as `--sha`, not the current checkout's HEAD. Legacy JSON without a source-SHA payload
cannot independently verify a manually supplied commit label.

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
control stays roughly flat. The current experiment adds the **benchmark-only, ordinary-history
LazyColumn A/B candidate**, not a production viewport migration. Compare arms within the new run:
shared scrolling animations and validation probes have changed since that earlier baseline. Do not
use separate hosts/runs to claim a speedup. The [2026-09-24 A/B report](results/2026-09-24-36d9d56-lazy-history-ab-ci-emulator.md)
records the complete 30-case matrix and paired ratios. Results support continuing an isolated
ordinary-history production-integration prototype; they do **not** justify default rollout without
viewport/controller integration tests and representative physical-device measurements.
