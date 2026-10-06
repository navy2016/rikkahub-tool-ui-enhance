# Terminal scrollback rendering benchmark

> Current production viewport/controller measurements use [PRODUCTION.md](PRODUCTION.md) and the
> `Terminal Production Viewport Benchmark` workflow. The renderer-only experiments and historical
> decisions below are archived context, not a description of today's virtual-history integration.
> In particular, production now offers explicit virtual history; its current interaction/IME/height
> binding is measured only by the new suite. Do not mix the two contracts or their measurements.
> After the `16ab7c0` baseline, the frame-history builder gained immutable block sharing: normal
> single-line archives read one source row, not H. Older O(H) frame-scan descriptions below refer
> to the recorded implementation at that time; current complexity is documented in PRODUCTION.md.

## Scope

Measure **1,000 / 5,000 / 10,000 history rows + 24 active rows** on one device/APK. Current default
is `width-intrinsics` (`candidate=widthIntrinsics`): current production chunks/layers, the previous
full-layout-width lazy candidate, and the intrinsic-width lazy candidate (45 cases). Adjacent order
rotates across sizes/scenarios. The two lazy arms have identical rows and FIFO index; only scalar
width measurement changes. All arms use the same incremental frame and row synchronizer.
The `production-lazy` pair remains available as `candidate=productionVsLazy` (30 cases).
The archived three-arm `chunked-layers` and two-arm `ab` / `chunked-eager` formats remain available;
never merge independent runs or substitute the legacy flat eager control for current production.

Production ordinary history now uses the shared **fully eager transcript with isolated history display lists**. It keeps
ScrollState/verticalScroll, all rows, natural Text geometry, selection and the single scroll-effect
owner; TUI/alternate/full-grid modes stay flat. Neither LazyColumn nor isolated layers are enabled in
the TUI/physical-grid path; ordinary history uses layers but not LazyColumn. Source changes still
require unit and interaction/geometry regressions, not just timings.

The opt-in `terminal-target` APK compiles the production `TerminalEmulator`, stable-ID helper,
`TerminalRenderedRows` and font sources via Gradle `Sync` tasks. Generated copies are only in `build/`;
there is no second row-diff/Text implementation to drift away from production. `ProcessSessionPage`
uses the same extracted helpers, still inside its existing atomic snapshot and scroll container.
Both chunked benchmark arms call `TerminalRenderedTranscript` directly; they cannot fork its row tree.

The target has a **separate application ID**, no shell/rootfs/native dependencies, no network permission,
and no production user data. It is non-debuggable, profileable, debug-key signed, and unminified. Like
the production app it uses `largeHeap=true`. Macrobenchmark uses `CompilationMode.Full()`.
Normal builds do not include either benchmark module unless `-PterminalBenchmarks=true` is supplied.

### Fixed workload

- 80 physical columns, 24 active rows, JetBrains Mono 14sp, no font padding, 8dp tail padding.
- Deterministic unique lines with ANSI colors/bold and mixed ASCII/CJK, narrower than 80 cells.
- A fresh process/emulator model per measured iteration; shell-like fixture seeding is **outside** timing.
- All selected renderers are compiled into one APK and run on one device in adjacent size/scenario groups.
  Two-arm order alternates; three-arm order rotates. Every arm has the same repetition count.
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
All candidates share the same frame snapshot and row synchronizer, including their incremental paths.

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

Two-arm collection has **30 cases**; `chunked-layers` has **45 cases** (3 sizes × 5 scenarios × 3 arms). The summary must reject a missing arm,
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
1k/one-repeat preflight of all output-update scenarios in the selected arms. Only if it passes does the full
matrix run in a fresh instrumentation invocation. Preflight output is archived separately and never
included in the A/B summary. All measured arms run in one invocation; do not merge separate runs to
manufacture a paired comparison.

To run on a dedicated, authorized physical test device from a normal Android SDK development host:

```bash
./gradlew -PterminalBenchmarks=true \
  :benchmarks:terminal-target:testBenchmarkUnitTest \
  :benchmarks:terminal-macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.benchmark.TerminalScrollbackBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.terminalIterations=5 \
  -Pandroid.testInstrumentationRunnerArguments.terminalCandidateRenderer=widthIntrinsics

python3 benchmarks/summarize.py benchmarks/terminal-macrobenchmark/build/outputs \
  --sha "$(git rev-parse HEAD)" --environment physical-device --suite width-intrinsics --require-complete --require-width-index \
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

## Current row-sync follow-up

`renderFrame` now reuses an immutable owned history snapshot (Text, IDs and content bounds). Its
random-access history/screen concatenation does not flatten the retained prefix. Active updates
visit **zero history rows** during frame creation; initial render, append/trim and style/column
invalidations still rebuild the history snapshot. This rebuild and persistent-list structural edits
remain O(history), so the whole append pipeline is **not** claimed to be O(screen).

The shared synchronizer binds its proof to the last published immutable row-list view and emulator
owner. Stable frame identity skips retained Text for screen-only updates. A FIFO ordinal overlap,
same generation/style/geometry and verified old-screen/new-ID suffix allow ordinary append/trim to
skip retained history too. No assumption of contiguous or sorted history IDs is made. Reorders,
synthetic/incomplete frames, another emulator, pending TUI blanks, screen generation changes and
resize/style invalidation retain full reconciliation. The proof participates in the caller's mutable
snapshot but is never read by composition; discarded snapshots cannot commit it ahead of Text.

Benchmark hard assertions: active updates visit 24 Text rows and 0 frame-history rows; one-line
append/trim visits 25 Text rows and skips H-1, while frame-history visits remain H. Alternate screen
still renders the complete 24-row grid with 0 history visits. These are work counts, not FPS claims.

Viewport capture now indexes one ID without building a history-sized combined list. The controller
caches one owned archival anchor, verifies the actual ID, and preserves all reducer generation,
trim, pixel and single-writer semantics. This is covered by host/controller tests, **not timed by
the isolated renderer benchmark**, which does not host the production viewport controller.

Production frame publication uses reference equality rather than structurally comparing 10k rows.
The target directly receives its chunk plan, so `ProcessSessionPage`'s `remember`/publication wiring
is not a separately timed benchmark phase. No production LazyColumn migration is enabled.

Use the unchanged full matrix to inspect `rowSync/op`, row-sync maxima and frame timings after
this change. The workflow publishes a compact all-size hot-path annotation (to avoid truncating
10k results), while the job summary/artifact retains the complete 45-case report and raw traces.
All renderer arms use the new synchronizer: their same-run ratios compare renderers, **not** old
versus new synchronization. Do not infer a before/after speedup from separate CI hosts/runs.

## Lazy-history migration investigation (not enabled in production)

The `lazyIntrinsic` arm obtains `ceil(maxIntrinsicWidth)` from Compose MultiParagraphIntrinsics
with the same resolved style/density/direction/font resolver used by the full-layout control. This
is the width TextMeasurer chooses with unbounded constraints, Clip and no soft wrap. Shaping, spans,
fallback fonts and bidi are retained; only the following MultiParagraph/TextLayoutResult creation
is omitted from width discovery. Visible Text still performs its own unchanged layout/draw. No
glyph-width approximation, full-history paragraph cache or forced GC is introduced. The scalar
FIFO index and cold O(H) scan are unchanged. Allocation-path counters reject a mislabeled arm.

The correctness fixture runs all 11 natural geometry cases against **both** width paths plus six
exact integer-width tests spanning styles, scripts, emoji, fallback, density, direction, paragraph
boundaries, live/archived cursor text and FIFO invalidations. Geometry references are never fed
to the timed candidate. These tests and measured same-run data, not cross-host ratios, decide whether
the new width path is an improvement. The terminal status bar and KEYS features/semantics are out
of scope and unchanged; any adjustment requires a user-approved proposal first.

The `TerminalLazyNaturalGeometryInstrumentedTest` correctness suite compiles the exact same
`TerminalBenchmarkViewport` / partition sources as this target, not a second lazy renderer. It
compares real Text/spans, widths, heights and viewport positions at clipped history anchors and the
physical screen. It covers repeated FIFO trims, font/scale/row-resize changes, disjoint ID restore,
styled tail updates, TUI/alternate/full-grid fallback and clearing history. Its synthetic large-font
spans are explicitly a renderer stress case, not a claim that ANSI feed emits font-size spans.

The original `8eb1ead` candidate's offscreen-widest-row case characterized a horizontal-range
blocker: measuring only composed rows loses the maximum width of retained history. The current
candidate now calculates exact scalar widths with the same TextStyle/density/direction/resolver as
production Text. Cold, font/style/column/owner invalidations measure all history; owned warm updates
measure only newly archived rows plus the physical screen. A monotonic deque expires trimmed widths
and stores at most H scalar/ordinal candidates, no Paragraph or LayoutResult per history row.

This is a deliberate O(H) cold-width cost, NOT free metadata. `Terminal.widthIndex` is included in
mount-to-draw and CPU frames, reported separately from rowSync. CI requires its trace counts/sums/max
for ordinary lazy updates and rejects timing data which omits the width calculation. The fixture
checks that cold creation measures H history widths, screen-only updates measure zero history widths,
and each one-line archival measures exactly one. Arbitrary synthetic frames use full measurement.
Source font resolution is observed for all normal/bold × regular/italic variants emitted by the
terminal. `TextMeasurer` uses no LRU paragraph cache. Width/span/RTL correctness is tested against the
production tree, but reference measurements are never provided to the lazy arm.

The 11 natural-layout cases now require offscreen-width equality and cover maximum-row trim,
horizontal-offset clamping, font scale/RTL alignment, screen-to-history maximum-width migration and
clear. These tests still do not approve production migration, cross-item selection/clipboard, real
IME or a complete variable-height controller integration. Production remains eager chunks/layers.

The opt-in lazy measurement tracker now retains only the immediately previous visible items,
avoiding stale absolute positions and session-lifetime growth after browsing many rows. Its JVM
tests visit 10k distinct IDs. Screen-to-history anchors also retain reducer generation semantics.
Neither helper is invoked by the current production eager terminal.

## Shared archival chunks and opt-in drawing layers

`chunkedEager` now calls the production transcript helper. It keeps **every** history row composed,
uses the original `verticalScroll`/ScrollState, and leaves the entire active screen together. Each
history group contains at most 128 FIFO archival ordinals. Head trims only change the first group,
appends the last; stable line IDs still key Text rows and viewport anchors. There are no fixed row
heights, pixel estimates or lazy items. The ordinal never consumes IDs for screen/TUI operations and
survives resize; clearing history does not recycle ordinals. Null/unsafe archival metadata and
TUI/alternate/full-grid modes retain the flat backend. Host tests cover these gates and 1k/5k/10k trims.

The older `7d95aa7` benchmark-only implementation grouped line IDs rather than archival ordinals and
fell back on non-monotonic IDs. Inspect the source SHA before interpreting an archived report.

`chunkedLayers` uses the **same** plan, row list and Text, adding only default `graphicsLayer()` to each
history Column (no clipping, alpha or forced offscreen buffer). The three-arm run passed, so this is
now the production ordinary-history default; TUI/alternate/full-grid paths still produce no chunks. Both chunked arms include measured tail correction and identical six-viewport pans. Optional
non-observable composition probes in the shared helper verify every history row/chunk is retained and
the active screen is whole; probes are absent in production.

Workflow `candidate` inputs:

| candidate | suite | renderers |
| --- | --- | --- |
| `lazyHistory` | `ab` | flat eager / lazy history |
| `chunkedEager` | `chunked-eager` | flat eager / production archival chunks |
| `chunkedLayers` (default) | `chunked-layers` | flat eager / archival chunks / identical chunks + layers |

The three-arm report includes `E/Ch`, `E/La` and **`Ch/La`** same-run ratios. The last isolates drawing
isolation rather than conflating it with chunking or a change in row-sync implementation. The production switch is guarded by the 32-case geometry/gesture regression workflow and the 244-case
terminal regression workflow; it does not enable LazyColumn. The independent natural-geometry fixture compares all Text nodes,
scroll ranges/offsets and selection-wrapper/resize/RTL transitions, in addition to the existing 24
real-pointer gesture cases; it is still not complete end-to-end IME/selection-copy or real-device acceptance.

CI publishes six baseline notices (the existing update hot paths plus one complete all-size/all-renderer
table per scenario) and a seventh width-index notice when measured, including mount, CPU,
measure/draw, frame count, width cost and memory. This keeps the full
matrix accessible via the check-run API when artifact/blob downloads fail. Missing values remain `—`.

## Recorded baselines

- [a79e7b6: complete production V3 three-mode baseline](results/a79e7b6-production-viewport-ci.md)
  — all six scenarios, three sizes and three production modes passed: 54 cases / 162 measured
  iterations, with six original device contexts and 42 source/font/build hashes verified. At 10k,
  `lazyHistoryIme` is close to default for IME (3708.66 vs 3778.21 ms), while original `lazyHistory`
  is 10658.95 ms because it mounts eager history. Virtual modes improve cold mount, semantic jumps,
  append/trim and remount in this component harness, but default remains faster for active-row updates.
  Cold exact-width work remains expensive. The app/benchmark inputs are identical to `2b0f259`;
  `a79e7b6` adds only archived evidence. This is not a full-page/PTY or real-device FPS result.

- [2b0f259: keyboard-stable virtual history, complete three-mode IME scenario](results/2b0f259-ime-v3.md)
  — 341 application JVM tests, 106 viewport tests, 18 production smoke cases and 9 full IME cases /
  27 measured iterations passed. The opt-in `lazyHistoryIme` keeps virtual rows through IME avoidance;
  10k IME total is 3245.22 ms versus original `lazyHistory` 9178.10 ms on that scenario's same device.
  Default rendering and the original mode's IME latch remain unchanged. This is not a full six-scenario
  V3 baseline or a real-device FPS claim; source/APK verification and raw phase provenance are included.

- [419636a: reusable row measurement node](results/419636a-ime-v2.md)
  — 95 viewport tests and a complete 1k/5k/10k IME scenario passed. The per-row eager measurement
  wrapper was replaced while preserving natural geometry and owner-release semantics. The report is
  a diagnostic single-scenario run, not a cross-Runner performance comparison.

- [64df33d: session width retention, complete production V2 baseline](results/64df33d-production-viewport-ci.md)
  — 36 cases / 108 measured iterations passed, with launch-bound completion receipts and original
  per-scenario phase/transition tables and provenance. Unchanged history is not remeasured on IME
  retry; the 10k width call is 0.97 ms, while show/eager-fallback remains expensive (9246.28 ms).
  No cross-run improvement percentage or real-device FPS claim is made. Default rendering is unchanged.

- [7a1d711: immutable snapshot blocks, complete production V1 baseline](results/7a1d711-production-viewport-ci.md)
  — 36 cases / 108 measured iterations passed, including original per-scenario source/APK hashes
  and device/input-method context in the JSON archive. This is not a V2 completion-protocol baseline
  and cannot be used for a cross-run percentage comparison.

- [16ab7c0: complete production viewport/controller baseline](results/16ab7c0-production-viewport-ci.md)
  — 36 cases / 108 measured iterations across six independently paired scenario groups passed.
  Includes real IME fallback/retry and remount; predates immutable historical block sharing.
  The archived CSV/JSON contain validated, rounded check-run notices, not raw Perfetto samples.

- [2026-10-01: intrinsic width vs full width vs production](results/2026-10-01-75d1031-intrinsic-width-ci-emulator.md)
  — 60 viewport/width tests, 19 fixture tests and 45 performance cases passed. Same-run 10k lazy
  cold-width cost fell 908.41→423.87 ms, mount 1064.42→638.39 ms. Peak anonymous RSS was unchanged;
  1k mount did not improve. Production status bar/KEYS and the entire app tree remain unchanged.

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


- [2026-09-27: row-sync 优化后的热点摘要](results/2026-09-27-85e2696-row-sync-hotpaths-ci-emulator.md)
  — 30 项矩阵通过，归档 12 项更新热点；数据支持下一步隔离验证 eager 的组合/布局组织。

- [2026-09-28: activity-screen row-sync fast path](results/2026-09-28-2eece58-row-sync-fast-path-ci-emulator.md)
  — 45 cases, 248 terminal tests and 32 viewport tests passed; 10k active-update rowSync/op was 0.13 / 0.33 / 0.59 ms.

- [2026-09-28: three-arm archival chunk/layer matrix](results/2026-09-28-de57926-chunked-layers-ci-emulator.md)
  — 45 cases passed; 10k append/trim CPU p95 was 6435.24 / 333.53 / 140.02 ms for flat/chunked/chunked+layers.

- [2026-09-27: initial stable-chunk eager comparison and lazy regression](results/2026-09-27-7d95aa7-chunked-eager-ci-emulator.md)
  — both 30-case runs passed; 10k append/trim CPU p95 was 7414.46 / 394.85 ms in the chunked run.
  Only annotation-accessible hot paths are archived; no cross-run three-arm or real-device claim.
