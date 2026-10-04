# Production viewport benchmark

This is the current production-component benchmark, distinct from the archived renderer-only
experiments in README.md. Contract: `production-viewport-v1`. No old measurements are relabelled.

## What is measured

The target compiles the actual production `TerminalTranscriptViewport`, `TerminalViewportBinding`,
compatibility policy, gestures, row synchronizer, width index and height measurements from app sources.
It runs either the default `chunkedLayers` or explicitly selected `lazyHistory` preference. All follow,
top/tail jumps, IME fallback and restore scrolling is performed by the production controller's sole
executor. The harness never calls ScrollState/LazyListState scrolling APIs or updates the controller's
layout observer. Completion checks read the eager cache without populating it.

Six scenario groups each contain an adjacent pair of modes at 1k, 5k and 10k historical rows. Pair
order alternates. One workflow builds a single non-debuggable, profileable, unminified Release-derived
target and a benchmark test driver. Each scenario runs both modes in ONE instrumentation invocation,
APK pair and emulator; different scenarios can run on different runners and must NOT be pooled.

| Scenario | Operation inside timing |
| --- | --- |
| `initialCompose` | Cold render frame, row states, production composition, layout, follow reconciliation and stable draws. Seeding and app launch are outside timing. |
| `activeRowUpdate` | 30 separately drawn last-line rewrites; history identity stays unchanged. |
| `appendAndTrim` | 30 separately drawn single-line archives at the scrollback cap; production follows the evolving semantic tail. |
| `semanticJump` | Production animated top jump and tail jump, including exact final positioning. Not an equal-distance/equal-duration fling comparison. |
| `imeRoundTrip` | Actual system keyboard show, 8 active updates in compatibility fallback, hide, explicit same-mode retry. A mid-history ID and clip are captured during setup; positions and unchanged physical terminal rows are checked. |
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

Results are accepted only with the complete pair/size matrix for that invocation, matching positive
repetition count, successful instrumentation, required traces and exact provenance. Exact duplicate
JSON copies are accepted; different documents, checkpoint prefixes or independent runs are rejected.
Trace durations overlap and cannot be subtracted from frame percentiles. Emulator results are not FPS
or a percentage speedup relative to an earlier build on another runner.

## CI

`Terminal Production Viewport Benchmark` automatically runs a **smoke-only** 1k/one-repeat pair for
each scenario on relevant pushes (12 cases). This validates the fixture and trace contract, not a
performance baseline. The original `Terminal Scrollback Benchmark` stays manually runnable.

After smoke and existing regression tests pass, request all sizes / three repeats:

```bash
gh workflow run terminal-production-benchmark.yml \
  --ref fix/terminal-viewport-semantic-reducer \
  -f scenario=all -f smoke=false -f iterations=3
```

Full collection is 36 cases / 108 measured iterations; each scenario has a 15-minute instrumentation
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

Full `ProcessSessionPage`, actual shell/PTY scheduling, input-to-echo delay, selection/clipboard,
process-death recovery, representative physical-device memory/GC and 30-minute stress remain follow-up
work. This benchmark does not claim those end-to-end checks or change any status/KEYS behavior.

## Verified checkpoints

- [16ab7c0 full baseline](results/16ab7c0-production-viewport-ci.md), run `37172835069`:
  all 36 cases / 108 measured iterations passed. Each scenario has its own device/invocation;
  the archive preserves rounded validated notices and their source check-run links.
- `7a1d711` immutable history blocks: 322 application Release JVM tests, 87 viewport instrumented
  tests and 12 production-scenario smoke cases passed. This establishes behavior and incremental
  source-read counts, not a controlled before/after timing or memory improvement. Its snapshot
  directory remains O(H / 128), and cold/style invalidations still read all required rows.

Next measured optimization: retain the scalar width index across an IME compatibility fallback
without retaining full Text layouts or changing the explicit retry policy. A hidden widest row,
FIFO trim, font/density/direction changes, source replacement and page disposal must remain valid.
This is a follow-up plan, not implemented by the block-sharing checkpoint.
