# Terminal optimization bundle B1–B6

Authorized branch: `opt/terminal-verified-a79e7b6`. Pre-batch control:
`fe43ec19e6e565acda8b8f3dbc6c530938609b32` (production tree equals the delivered `55784d4`).
Implementation, tests and CI are edited together, then validated as one batch. No local Android,
Kotlin or native compilation; no project dependency upgrade or installation. Two validation rounds
maximum: initial run, then one consolidated correction/affected rerun. Original failures are retained.

## Production scope

- B1: main-thread foreground-service request/acknowledgement state, early platform promotion,
  pending stop deferred until the corresponding start command is promoted. Failed requests retry
  on the next existing manager refresh. No claim that the older failure's underlying cause was proved.
- B2: immutable cells inside versioned line owners; a line-local screen rendering cache covers
  text, blank/occupied bounds, cursor reservation/visibility/shape/color and global style revision.
  Changed lines rebuild; untouched lines reuse Text. Both physical screens remain bounded by MAX_ROWS.
  A synchronous uncached screen path is retained for differential tests, not called by production UI.
- B3: parse/buffer/protocol processing remains serialized and lossless. Scalar cumulative byte/revision
  display updates may conflate. The page publishes at a display-frame boundary with the existing
  synchronized-output/resize/grace protections. Original raw consumers retain a separate byte stream.
- B4: one scalar width checkpoint per session, newest-mount lease, weak resolver/font identity tokens,
  strict source/generation/style/columns/metrics invalidation. Only the exact packaged blocking-font
  style is eligible. A cold pass works on an immutable frame and worker-local index; cancellation and
  request identity guard acceptance. Other fonts use the original synchronous path. First exact
  maximum-width discovery still scans all history. Only complete, current widths are displayed.
- B5: virtual history publications retain physical-screen row states plus an immutable history view.
  Visible/precomposed history items allocate their own states; an eager compatibility branch creates
  its live block states. Normal DEFAULT retains its existing dense row tree and actual height index.
- B6: complete terminal regressions, V6 component baseline, whole-batch same-device full-page AB/BA,
  and separately labelled 30-minute, 10k-history closed-loop 32-line PTY burst/input/remount pressure.
  The pressure workload is sustained closed-loop output, not unrestricted producer throughput.

DEFAULT remains CHUNKED_LAYERS. No status/KEYS action changes, nominal-cell replacement, history
truncation, eager selection/mouse/TUI policy change, extra scroll executor or font approximation.
No expected phone FPS, universal latency percentage, or zero-leak claim is an acceptance criterion.

## Verification

1. Local changed-line format/whitespace, Python AST, workflow YAML and shell syntax checks.
2. `terminal-viewport-interactions.yml`, `suite=staticChecks`: fixture lint then Release compilation.
   `terminal-production-benchmark.yml`, `checks_only=true`: benchmark lint, Release-derived pair,
   focused report/receipt tests. App Release/terminaltest type compilation runs on Actions as well.
3. `terminal-emulator-tests.yml`, `suite=all`: explicit terminal test classes exactly once; the old
   three-attempt loop and duplicate class invocations are removed. No unrelated application suites.
4. Complete viewport test inventory plus sparse/async geometry, checkpoint remount, cancellation,
   selection/mouse/IME handoff tests. Every assigned case and raw work report is required.
5. `terminal-pipeline-tests.yml`, `suite=all`: original 15 transport probes, two service/tail tests,
   then 36 real-page echoes. Original preflight failure cannot be covered by a page-only pass.
6. V6 six scenarios, three sizes and modes, three repeats: 54 cases / 162 formal measurements,
   18 smoke measurements kept separately. Sparse retention and cold/warm width counts are required.
   V5 and its tail-drain results remain archived; no old result is relabelled V6.
7. `suite=bundlePaired`: fixed pre-batch production vs candidate, identical pre-batch test APK,
   resources, native assets, signer and PRoot overlay. Each installed APK is hash checked; four AB/BA
   pairs give 288 measured echoes and 72 excluded warmup echoes on one boot. Reuse only the validated
   old protocol primitives in an independent module; do not change the frozen v1 experiment files.
8. `suite=soak`, `soak_mode=<each existing mode>`, `soak_seconds=1800`: bounded native PTY bursts
   entered through real field actions and checked visibly. Time buckets retain read/fed bytes, drawn
   revisions, actual elapsed time, PSS/Java/native memory and available GC counts. Missing memory/GC
   fields are not zero. Guardrail: post-warmup median PSS growth over 128MiB fails, not a no-leak proof.
9. Only after cloud gates pass, `release.yml build_type=release` and `verify-release-apk.yml` bind
   the formal arm64 APK to exact run/SHA and independently check signature, archive, manifest and ABI.

All dispatch/push operations name the branch explicitly. CI waiting uses a single foreground,
resumable 900-second observation window, no background watcher. Read bounded failure contexts only.
An observation timeout is unfinished work, not a test failure. Do not rerun merely to obtain a pass.

## Physical-device boundary

Cloud emulator evidence is not phone input latency/FPS, physical keyboard behavior, GPU presentation,
or 30-minute on-device thermal/GC acceptance. No physical device is currently provided. Delivery must
say **cloud verified, physical-device acceptance pending** until an authorized device is supplied.
The existing default cold eager O(history) composition cost is not eliminated by this bundle.

## Authorized stable-mount follow-up

The user authorized one additional targeted diagnosis/fix/retest round after the `2723a4c` report.
Original stable-virtual pressure run `38838668624` failed its `mounted` backend assertion before
the sustained loop. Diagnostic-only `bc713e7` run `38845564879` did not reproduce that assertion:
it observed correct preferences/policy, virtual selection before exact width/geometry completion,
and eventual complete virtual layout. This is not proof of the original run's exact root cause.

The old driver waited for visible text and Compose idle but not the asynchronous backend handoff.
The targeted correction waits for the independently expected mode/IME, current frame identity and
revision, actual geometry/width, exact tail, no gesture/effect/writer and two new terminal draws of
the same completed state. A 15-second limit is a failure bound, not a fixed startup delay. Test-only
draw requests invalidate only the existing opt-in draw probe; they never scroll or change mode.
The original per-echo byte/revision/backend assertions remain; pressure cycles also assert backend.
This new `terminal-settled-viewport-v1` receipt contract does not relabel old pipeline/soak evidence.

A controlled pending-fling test holds the unchanged eager-to-virtual gate while the real eager tail
is visible; acceptance must reject it and accept only after the gesture ends and handoff completes
without any new output. JVM and report tests reject stale/repeated draws, missing geometry, wrong
preference/IME/backend, live effects, and missing or reused receipts. `stableMount` selects only the
related JVM contracts and seven sparse/width/handoff instrumented tests. Full inventory is now 158.
Real-page regression and one stable-virtual 1800s pressure run follow the static gates. Production
rendering, viewport control, protocol parsing, input and mouse behavior are not changed by this fix.

The user's subsequent TUI-history request was inspected but is not a small related fix: current
alternate screen excludes normal history, alternate scrolling does not archive lines, and CSI 3J
clears scrollback. Recovering arbitrary full-screen redraws requires a separate bounded history
design, including mouse coordinate and alternate-screen/clear restoration tests. This follow-up
does not silently change those protocol semantics or claim pi/Claude Code/OpenCode transcript retention.
