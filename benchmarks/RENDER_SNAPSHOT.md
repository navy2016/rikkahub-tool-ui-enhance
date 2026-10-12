# Published terminal render snapshot reuse

## Scope and contract

The parent panel previously called `TerminalRenderSnapshot.capture` on each composition,
including input/layout recompositions without new output. The frame and immutable row-list
view now travel as one referentially compared state value. Initial mount captures once;
`renderTerminalFrame` captures again inside its existing mutable snapshot, AFTER row
synchronization and alongside viewport metadata. Parent binding and child drawing read the
same local value. No capture, row-list comparison or extra cache is added to composition.

Each publication creates a new wrapper even if frame identity/revision/row structure stays
equal. This is essential for forced TUI blank commits: row Text can change after the clear
grace period without a new emulator frame. Row objects remain observable and retain their
stable IDs; the immutable LIST structure is not a copy/freeze of Text. Aborted transactions
must roll back both the published wrapper and row structure/text. Existing mismatch, missing
measurement, cancellation, natural-height and sole-scroll-writer checks remain unchanged.

This change does not alter default CHUNKED_LAYERS, optional virtual modes, IME fallback,
selection/mouse/TUI behavior, viewport policy, status-bar/KEYS semantics or width measurement.
The separately observed foreground-service transport preflight failure is not fixed here.

## Acceptance

- Eight JVM snapshot tests cover O(1) immutable view reuse, 32 repeated reads, same-frame and
  same-revision replacement, FIFO/resize/clear, forced grid blanks, old/new snapshots and abort.
- Six snapshot instrumented tests retain stale-child negative control and three-mode streaming,
  IME and remount coverage. Two additional tests compare 32 independently settled no-output
  panel updates and verify same-frame/same-revision publications reaching child consumption.
- Both old/new fixture arms use the production snapshot factory, binding and Text renderer.
  Factory invocation counts must be control 32 / published 0, excluding mount. Both panels
  must commit all 32 updates; wrapper and row-view identity must survive every update.
- Child composition counts are measured and reported, not presumed to improve. These counts
  are not allocated bytes, GC time, complete-page latency, GPU presentation or phone FPS.
- Separate real-page instrumentation requires all 36 echoes across three modes and four
  mount/keyboard/remount phases, bound to the exact tested commit and installed APK hashes.

`report-terminal-render-snapshot.py` requires all six exact passing JUnit names and a single
complete integer work record. It rejects missing/duplicated samples, skips and invalid counts,
verifies production-to-fixture source copies and APK hashes, and emits compressed hash-checked
original evidence. The JVM source-contract tests bind the fixture's publication pattern to the
actual page; the component test alone is not represented as a complete-page measurement.

## Authorized focused verification

All modifications precede verification. No local Kotlin/Android compilation or dependency
installation. Check changed lines against `.editorconfig`, use `git -c core.fileMode=false
diff --check` and `bash -n benchmarks/terminal-viewport-tests/run-ci.sh`, then dispatch on the
explicit `opt/terminal-verified-a79e7b6` branch:

1. `terminal-viewport-interactions.yml`, `suite=staticChecks`: run ONLY fixture `lintRelease`,
   then `assembleRelease` / `assembleReleaseAndroidTest` for Kotlin type checking; no emulator
   or JUnit execution. No new formatter or app-wide lint is installed/run.
2. After static checks pass, run the following once each on the SAME commit:
   - Local Python modules: `test_terminal_viewport_shards`, `test_terminal_render_snapshot`,
     `test_terminal_panel_subscription`, `test_terminal_pipeline`, `test_terminal_pipeline_paired`.
   - `terminal-emulator-tests.yml`, `suite=renderSnapshot`: Release JVM tests only for snapshot,
     row synchronization, page UI source and smoothness source contracts. No retry loop.
   - `terminal-viewport-interactions.yml`, `suite=renderSnapshot`: snapshot (6), binding (3),
     default bottom (7), IME (6), stability (9): 31 cases, no unrelated geometry/width benchmark.
   - `terminal-pipeline-tests.yml`, `suite=pipeline`: actual page + native PTY, no transport-only
     preflight or frozen subscription AB/BA. The test target is Release-derived and non-debuggable.
3. Only after the required tests pass, `release.yml`, `build_type=release`, followed by
   `verify-release-apk.yml` with exact release run/SHA. Never dispatch Debug.

The first verification round may be followed by ONE batch correction and affected-case retry.
A second failure stops retries. A 15-minute foreground polling window reports incomplete CI
as still running, not as success/failure. Failure analysis reads bounded relevant annotations.
Old complete-run results and SHA identities remain archived and are not relabeled.

Implementation and tests exist; this document is a protocol, not an assertion that a new CI run
has passed. Actual run identities, counts and remaining limitations belong in the handoff report.
