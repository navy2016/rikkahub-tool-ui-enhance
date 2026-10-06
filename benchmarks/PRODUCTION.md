# Production viewport benchmark

This is the current production-component benchmark, distinct from the archived renderer-only
experiments in README.md. Contract: `production-viewport-v3`. No old measurements are relabelled.

## What is measured

The target compiles the actual production `TerminalTranscriptViewport`, `TerminalViewportBinding`,
compatibility policy, gestures, row synchronizer, width index and height measurements from app sources.
It runs the default `chunkedLayers`, original `lazyHistory`, and opt-in `lazyHistoryIme` preferences. All follow,
top/tail jumps, IME fallback and restore scrolling is performed by the production controller's sole
executor. The harness never calls ScrollState/LazyListState scrolling APIs or updates the controller's
layout observer. Completion checks read the eager cache without populating it.

Six scenario groups each contain three adjacent modes at 1k, 5k and 10k historical rows. The starting
mode rotates by size/scenario. One workflow builds a non-debuggable, profileable, unminified Release-derived
target and a benchmark test driver. Each scenario runs all modes in ONE instrumentation invocation,
APK pair and emulator; different scenarios can run on different runners and must NOT be pooled.

| Scenario | Operation inside timing |
| --- | --- |
| `initialCompose` | Cold render frame, row states, production composition, layout, follow reconciliation and stable draws. Seeding and app launch are outside timing. |
| `activeRowUpdate` | 30 separately drawn last-line rewrites; history identity stays unchanged. |
| `appendAndTrim` | 30 separately drawn single-line archives at the scrollback cap; production follows the evolving semantic tail. |
| `semanticJump` | Production animated top jump and tail jump, including exact final positioning. Not an equal-distance/equal-duration fling comparison. |
| `imeRoundTrip` | Actual system keyboard show, 8 active updates, hide, explicit same-mode Apply. Original `lazyHistory` retains eager fallback/retry; `lazyHistoryIme` stays virtual and its final Apply is a no-op. A mid-history ID and clip are captured during setup; positions and physical terminal rows are checked. |
| `detachRestore` | Dispose viewport; append/trim 12 lines while detached; recreate row states/controller/rendering and restore a captured mid-history anchor. This is composition recreation, NOT Android process death. |

Geometry is 80 columns / 24 physical rows, JetBrains Mono 14sp, mixed ASCII/CJK/ANSI, 8dp tail padding.
Updates nominally target 33ms intervals but await every settled draw; slow operations lengthen the run,
never drop output. This tests individual operation latency, NOT unrestricted PTY throughput.

Completion requires the published frame to be drawn, the actual production layout to describe it,
the effect writer and gestures to be idle, and the follow/lock target to be satisfied on two stable
draw confirmations. The wait/validation cost is included equally and is not subtracted. Parent draw
callbacks are not proof of GPU presentation. Default eager retains its existing cell-based position
semantics; no benchmark-only real-height table is added to the control. Existing natural-height
instrumented tests remain separate acceptance coverage.

V3 keeps V2's completion protocol: a per-launch, nonce-bound, signature-permission-protected broadcast to
the driver. The receiver is installed before launch and removed between launches and after the test;
its durable latches reject out-of-order phases, stale launches and fixture failures. Only validated
completion sends `mounted` / `done`; UIAutomator still clicks the native controls, but an accessibility
cache can no longer hide a completed status text. This changes completion-observer overhead and
requires a separate V2 baseline. V1 run `37220612385` failed its 5k IME case even though target PID 4037
logged `done` at 17:46:16.811 and the driver timed out at 17:48:36.477. That failed invocation remains
diagnostic evidence, not an accepted baseline. No timeout, production scroll or IME policy was changed.

V3 adds the third mode and rotates three-mode order. Old two-mode results remain V2 evidence; a V3
result is accepted only if every selected size contains all three modes. No old results are relabelled.
The new preference does not replace the default or change original `lazyHistory` behavior. It retains
virtual rows during IME only when the production policy permits avoidance; SEL, MOUSE, TUI, alternate
screen and offset-preserving/no-avoidance paths remain eager. Status/KEYS, PTY input/resize and natural
text geometry are unchanged. Real-keyboard tests separately cover follow, lock, font/RTL and disposal.

The detach continuation completes before the replacement is allocated; only the scalar saved viewport
state crosses that boundary. The harness does not intentionally retain the old row tree/controller
through the remount. This is not a forced-GC or object-collection proof; heap acceptance stays separate.

## Metrics and provenance

The frame-history builder now shares immutable 128-ordinal blocks. A normal one-line archive reads
one new source row; a head-only trim reads none. Changed head/tail blocks copy at most 256 retained/new
entries, while directory construction and exact content-bound aggregation still visit O(H / 128)
blocks. Cold creation, color/style/column invalidation and disjoint bursts still read all required
rows. Persistent UI-list edits and eager height-prefix changes have separate costs; this does not
make the entire append pipeline O(1). The baseline at `16ab7c0` predates this block sharing.

- Frame CPU duration and overrun samples (including the small native fixture controls).
- Cold mount and full operation latency; output-to-settled-draw, top/tail jump, keyboard show/hide,
  explicit retry, detach and restore async traces, with required per-iteration counts.
- Feed, renderFrame, rowSync, production width calculation, production eager geometry reads, viewport
  updates, root measure/draw and sole scroll-effect durations/counts. Eager geometry reads include
  cache hits, not only prefix construction; root measure can reuse a fixed parent during child work.
- Sampled anonymous RSS maximum. No Java/native/GPU/PSS peaks or GC-pause claims; missing counters
  remain absent. `meminfo-after.txt` is a post-run diagnostic, not a per-case peak measurement.
- Source SHA, run ID, attempt/scenario group ID, target APK hash, copied production source/font hashes,
  AndroidX device context, display size/density, heap and input-method identity.
- `Prod.createdHeightRecords`, `Prod.measurementNotifications` and `Prod.coalescedMeasurements` are
  cumulative work counters, not time samples. `IME_WORK` log lines bind per-round node/notification
  deltas to the Activity's launch token. Stable IME requires zero eager history builds and fewer than
  256 newly created measurement nodes per round; the original mode must coalesce its bulk fallback.

Results are accepted only with the complete three-mode/size matrix for that invocation, matching positive
repetition count, successful instrumentation, required traces and exact provenance. Exact duplicate
JSON copies are accepted; different documents, checkpoint prefixes or independent runs are rejected.
Trace durations overlap and cannot be subtracted from frame percentiles. Emulator results are not FPS
or a percentage speedup relative to an earlier build on another runner.

## CI

`Terminal Production Viewport Benchmark` automatically runs a **smoke-only** 1k/one-repeat triple for
each scenario on relevant pushes (18 cases). This validates the fixture and trace contract, not a
performance baseline. The original `Terminal Scrollback Benchmark` stays manually runnable.

After smoke and existing regression tests pass, request all sizes / three repeats:

```bash
gh workflow run terminal-production-benchmark.yml \
  --ref fix/terminal-viewport-semantic-reducer \
  -f scenario=all -f smoke=false -f iterations=3
```

Full V3 collection is 54 cases / 162 measured iterations; each scenario has a 15-minute instrumentation
bound and uploads its raw JSON/traces/summary immediately when its job ends. Partial artifacts remain
diagnostics, never a passed baseline. Failed-job reruns reuse the build job's original named/hash-checked
bundle; a new source SHA requires a new build. No background Actions monitoring service is used.

Only the selected test class is invoked directly through ADB with benchmark `CompilationMode.Full()`;
there is no Gradle rebuild on the scenario runners. The emulator requires hardware acceleration and
only the `EMULATOR` benchmark warning is suppressed. The emulator stays under the non-root runner UID,
using the runner's existing `kvm` group for that process. The runner's root sudo grant invokes `setpriv`
to drop to the runner UID / KVM GID before executing the emulator with a clean SDK-only environment;
no emulator code runs as root. A successful `-accel-check` is mandatory;
the launcher uses `-accel on` with no automatic software downgrade. No device modes or group membership
files are changed. Startup/acceleration logs are retained and guest shutdown is bounded. Separate scenario
runners isolate crashes/shutdowns; they do not make cross-run comparisons valid.

## Not covered yet

Full `ProcessSessionPage`, actual shell/PTY scheduling, input-to-echo delay, full-page selection/clipboard,
process-death recovery, representative physical-device memory/GC and 30-minute stress remain follow-up
work. This benchmark does not claim those end-to-end checks or change any status/KEYS behavior.

## Verified checkpoints

- [42caa6d session-owned width summaries](results/42caa6d-detach-v3.md):
  343 application Release JVM cases, 106 viewport instrumentation cases and 18 three-mode smoke
  cases passed. Full detach/restore run `37497601322` passed all 9 cases / 27 iterations, with
  original hash-checked evidence from `37499482837`. Every virtual iteration measures exactly the
  12 rows archived while detached plus the 24-row screen; 10k width work is 2.28 / 4.85 ms and total
  restore operation is 312.12 / 319.74 ms for original/stable virtual. The default remains eager.
  Session removal clears the cache; weak font identities and all source/style/geometry changes
  conservatively revoke reuse. The Release APK passed independent verification `37495475988`.

- [a79e7b6 complete V3 baseline](results/a79e7b6-production-viewport-ci.md), run `37456883236`:
  all 54 cases / 162 measured iterations passed across six independent scenario groups; original
  manifests, six device/input-method contexts, result-file hashes and all 42 source/font/build hashes
  were checked through evidence run `37459976695`. The application and benchmark inputs are identical
  to `2b0f259`; `a79e7b6` adds documentation only. At 10k, stable virtual IME is 3708.66 ms versus
  default 3778.21 ms and original virtual 10658.95 ms. Stable virtual also reduces default's cold mount,
  append/trim, semantic-jump and remount cost in this run, but default remains faster for active-row
  updates (153.64 vs 163.22 ms/output). Cold/remount exact-width work remains 711.68 / 1359.00 ms.
  Do not pool independent scenario runners or interpret this component benchmark as real-device FPS.

- [2b0f259 opt-in stable IME and notification coalescing](results/2b0f259-ime-v3.md):
  341 application Release JVM cases, 106 viewport instrumentation cases, 25 fixture JVM cases and
  18 three-mode smoke cases passed. Full V3 IME run `37454340924` passed all 9 cases / 27 iterations,
  with original hash-checked evidence from `37455592563`. At 10k, original virtual IME total is
  9178.10 ms, stable virtual 3245.22 ms and default 3260.08 ms on the same scenario device; show is
  6783.77 / 932.94 / 961.06 ms respectively. Each stable-mode iteration creates 8 measurement nodes
  versus 10051 in the original virtual mode. This is a complete IME scenario, not all 54 V3 cases.
  The Release APK passed independent verification `37454360334`. Default/status/KEYS behavior and
  original virtual IME latch remain unchanged. A first-measured-row fix also covers restored anchors
  missing their capture height; later font changes now scale from that known height without drift.

- [16ab7c0 full baseline](results/16ab7c0-production-viewport-ci.md), run `37172835069`:
  all 36 cases / 108 measured iterations passed. Each scenario has its own device/invocation;
  the archive preserves rounded validated notices and their source check-run links.
- `7a1d711` immutable history blocks: 322 application Release JVM tests, 87 viewport instrumented
  tests and 12 production-scenario smoke cases passed. This establishes behavior and incremental
  source-read counts, not a controlled before/after timing or memory improvement. Its snapshot
  directory remains O(H / 128), and cold/style invalidations still read all required rows.
- [7a1d711 full V1 baseline](results/7a1d711-production-viewport-ci.md), run `37219099548`:
  36 cases / 108 measured iterations passed. The JSON archive includes each scenario's original
  manifest, source/APK hashes, AndroidX device context and input-method identity, recovered by a
  read-only evidence workflow and integrity-checked before archiving. Do not mix it with V2.
- `11d96be` mounted-session width retention: 328 application Release JVM tests, 90 viewport
  instrumented tests and 12 production V1 smoke cases passed. Real-keyboard tests assert unchanged
  history is not remeasured after explicit retry, fallback performs no virtual width measurement,
  new archives are measured once, and disposal releases candidates/font/source ownership.
- `64df33d` changes only benchmark/diagnostic code relative to `11d96be`; production app and build
  inputs are unchanged. Its V2 completion protocol passes 25 fixture JVM cases and 12 production
  smoke cases. The Release APK also passed independent manifest/signature/ZIP/ABI/hash verification.
- [64df33d full V2 baseline](results/64df33d-production-viewport-ci.md), run `37255295641`:
  all 36 cases / 108 measured iterations passed, including the formerly missed 5k IME completion.
  The archive includes original phase/transition tables, result-file hashes and all six device
  contexts, verified through evidence run `37256826428`. At 10k, virtual IME retry width calculation
  is 0.97 ms per call and the unchanged-history measurement counter does not advance. The full
  IME operation is still 12435.12 ms: show/fallback is 9246.28 ms and explicit retry is 570.21 ms
  (each reported separately as an iteration median; do not add/subtract to decompose the total).
  The default control's IME operation is 4297.59 ms on that same scenario device. Width retention
  alone therefore does not resolve the expensive eager fallback mount or justify enabling virtual
  history by default. Future work should instrument that handoff before changing its layout work.

The verified application APK for `64df33d` is available in [Release run 37254521258](
https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37254521258/artifacts/11322680567).
Its SHA-256 is `fd8bd1f60dda05ce25a14cec2694f447fa8a37f35e08c6fcf54d5695b7866252`;
[verification run 37255299199](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/37255299199)
confirms release signing, non-debuggable manifest, ZIP integrity and arm64-v8a. This is the app APK,
not the separate profileable benchmark target. Actions artifact download requires GitHub sign-in.

The scalar width index now belongs to the interactive terminal session when one exists; isolated
callers keep a page-local fallback. Compatibility
fallback retains only width candidates and source/font validity metadata; it measures no virtual
widths. Committed fallback frames prune retired FIFO candidates but do not advance the measured
end past unmeasured output. Explicit retry measures surviving new archives and the active screen;
unchanged history requires zero additional measurements. Owner/generation/render revision/columns,
font resolution/style/density/direction changes and replaced rows revoke reuse. Page disposal keeps
only the session-owned scalar candidates and weak font identities; deleting the process record clears
them. No full Text layout or Paragraph is cached, and IME fallback remains latched
until explicit retry. Production IME and detach scenarios assert these properties through cumulative
work counters and disposal checks; `Prod.widthMeasuredHistory` / `Prod.widthMeasuredScreen` are trace
counters, not timing estimates. First virtual mount still requires a full width scan; a same-session
remount measures only surviving new archives and the active screen.

The follow-up measurement-node optimization is recorded in [419636a IME results](results/419636a-ime-v2.md).
It removes the per-row eager measurement wrapper and passes 95 viewport tests, including geometry,
intrinsic, owner replacement and pooled-content release. Its 10k IME run reports 0.55 ms width work,
but 6122.77 ms show/fallback and 8360.06 ms total virtual operation on an independent Runner. The
remaining bottleneck is the eager fallback row-tree measurement/layout and handoff, not the width index.
