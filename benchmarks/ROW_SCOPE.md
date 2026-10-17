# Single measured-row restart scope

**Archived experiment — not adopted.** The candidate at `98f27e2` passed correctness gates but
did not improve the paired cold-row-tree measurements. Production and the active viewport/JVM
test wiring have been restored to `b7a93c9`; no new Release was built. Candidate sources, assertions
and results remain in the original commits and the evidence archive. See the
[decision and complete results](results/98f27e2-row-scope-rejected.md).
Everything below describes the historical experiment, not the current production implementation
or currently enabled CI suite. In particular, the conditional Release step was not taken.

Baseline: `b7a93c9b5734ce1386d919d3be6085ef725124bb`; branch `opt/terminal-verified-a79e7b6`.
The user asked to continue terminal optimization. This narrowly targets the repeated measured-row
composition work within default eager cold mounts, not the complete O(history) row-tree cost.

## Production change

`TerminalLazyItemMeasurements.Row` formerly read `row.text` to key its measurement modifier, then
called restartable `TerminalRenderedRow`, which read the same state again. It now passes that one
immutable text value to `TerminalRowText`, a non-restartable forwarding function. The Row function
still owns its own restart scope; an edited row must not invalidate its parent chunk or row loop.
Unmeasured/TUI `TerminalRenderedRow` still owns a restart scope and forwards its own state read.

The same BasicText/Material Text predicate, parameters, style, annotations and modifier are used.
`rowModifier` exposes the existing factory internally so the test-only old measured path uses the
exact same node. From `record` onwards, measurement registration, invalidation, owner release,
intrinsic queries, cached history-block tokens and geometry code are byte-identical to baseline.
No row-height estimate, text-layout cache, hidden read observation, new scroll writer, mode change,
history truncation, status/KEYS/selection/mouse/IME change or dependency upgrade is introduced.

## Controlled evidence and its limits

`TerminalRowScopeInstrumentedTest` mounts the SAME eager chunk/layer tree with either the frozen
baseline dual-scope row or the candidate measured Row. Source checks compare both frozen function
bodies against b7a93c9 and require the current measurement-node tail to match. Compiler reports
must show `TerminalRowText` as non-restartable and measured `Row`, unmeasured `TerminalRenderedRow`,
and both legacy functions as restartable. No manually counted scopes stand in for compiler output.

The paired row-tree fixture uses 1k/5k/10k immutable history frames +24 physical rows, JetBrains Mono
14sp, fixed density/fontScale=1, 320x240 viewport, 8dp padding and natural Text layout. Each size
has one AB warmup pair and four AB/BA measurement pairs: 6 warmup and 24 formal samples in total.
Fresh composition trees, measurements and scroll states are allocated for every arm. Model seeding
and row-state construction are OUTSIDE timing. Timers include composition/layout/draw, the test's
idle waits, exact geometry aggregation and two new draw confirmations. The semantics traversal and
disposal checks follow outside timing. The fixture starts ScrollState at Int.MAX_VALUE, relying on
the unchanged scroll layout's natural clamp; it never performs scrolling repair and has NO production
follow controller. Thus this is not full-page mount, first-install font load, PTY or phone-FPS timing.
Font and VM caches may remain warm between fresh-tree mounts; AB/BA reduces order bias but does not
make four pairs a device-population estimate. Every sample is preserved, including slower candidates.

Every mount retains/measures all rows, verifies identical paired height/scroll-range hashes, checks
the actual last Text is visible and releases all measurement/chunk ownership on disposal. APK hashes
are computed in the instrumentation process before timing and compared to the built target/test pair;
each size reports the same process/device identity. A changed historical row with a taller font is
tested separately for single-row recomposition, rejected old-frame geometry and committed new height.
That test does not enforce tail following because the fixture has no follow controller. Existing
production-bound default-bottom and IME tests remain responsible for real follow/lock behavior.

## Batched verification

1. Local changed-line whitespace, Python AST/reporter tests, workflow YAML and embedded shell syntax.
   No local Android/Kotlin/native compilation or project dependency installation.
2. `terminal-viewport-interactions.yml suite=staticChecks`: fixture `lintRelease`, Release target/test
   compilation, frozen-source and compiled-scope checks; no device tests.
   `terminal-emulator-tests.yml suite=rowScope`: 98 JVM cases across 7 classes, one invocation:
   source3, rows29, snapshot8, eager-cache18, invalidations5, UI-source13, smoothness-source22.
3. `terminal-viewport-interactions.yml suite=rowScope`: three disjoint shards, 50 cases:
   `rowScopes` 6 new cases (3 cold-size paired + local subscription + styled geometry + unmeasured TUI);
   `rowText` 26 (8 measurement-node, 3 text compatibility/selection, 6 metric/async-font,
   7 chunk-view geometry/FIFO/copy/lifetime, 2 render controls);
   `rowViewport` 18 (7 default-bottom, 6 real IME, 4 selected stability/FIFO/restore/fallback,
   1 active-row/history recomposition). Full viewport inventory grows from158 to164; existing
   scoped entries retain their old counts. Each assigned test and original paired sample is required.
4. `terminal-pipeline-tests.yml suite=pipeline`: 36 real-page PTY echoes and 12 settled viewport
   receipts, source inventory includes BOTH changed production files. The existing transport,
   byte-buffer and service implementations are not changed or rebenchmarked in this row-only batch.
5. After functional evidence and measured tradeoffs are reviewed, `release.yml build_type=release`
   then `verify-release-apk.yml` with the exact source/run; reference Release39005664006 / b7a93c9.
   No APK attachment/download; report and evidence only. Physical-device acceptance remains pending.

Use `[skip ci]` commits and explicit branch dispatch; preserve failed runs. Two validation rounds
maximum (first plus one consolidated affected correction), never rerun to select favorable timing.
Observe Actions in a foreground resumable900-second window, no background watcher. Observation
timeout is unfinished work, not a test verdict. Old V6/bundle timing and pressures remain archived,
not relabelled or used as uncontrolled before/after ratios for this change.

## First static-check failure (kept as failure)

At c4e3ad5, JVM run39008777701 passed98/98 and static run39008772606 passed lint and both Release
compilations. Its final report guard failed for `TerminalRowText`. The original artifact12092147600
contains ONLY the androidTest functions' report and uses fully qualified names. Kotlin2.3.10's
ComposeCompilerSubplugin sends one reportsDestination for all compilations; same-module report
filenames let the later test compilation overwrite production evidence. The first parser also
expected short names. Relay39009346532 preserved the original ZIP digest; local bounded-range
extraction read only report text/CSV/log, not APKs. No device tests ran and no failed run is relabelled.

The consolidated correction sets reportsDestination separately for EACH KotlinCompile task, declares
that directory as a task output, and requires every exact qualified function in its correct task's
report. Duplicate/missing/wrong-module/restartable mismatches still fail; report hashes are included.
No app source, measurement workload, assertion or dependency changes accompany this correction.
