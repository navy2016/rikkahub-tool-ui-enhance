# Reuse immutable terminal chunk views

## Scope and provenance

The eager transcript already skips unchanged history Text, but entering its parent renderer
still allocated a sublist and `TerminalHistoryChunkContent` per history chunk plus a screen
sublist. This batch reuses those views ONLY when both row-list and partition identities survive.

`ProcessSessionPage` passes the existing published `TerminalRenderSnapshot` through viewport
and configured renderer. Its private constructor proves that `rows` came from the O(1) immutable
`SnapshotStateList.toList()` view. The partition generator returns a private immutable List
wrapper carrying the original history count and archival ordinal. Arbitrary mutable lists,
copied plans and callers without a snapshot stay on the uncached path. A provided but mismatched
row/frame/partition capability throws; mismatches are never silently ignored.

Cache equality is two reference comparisons, not List equality or a scan of history IDs/Text.
A new frame with the same immutable row structure and plan reuses the views. FIFO changes,
same-ID replacement objects, a different plan or clear invalidate them. Row text remains live
observable state. Stable line/bucket keys, Text/style, natural heights, layer drawing, selection,
scroll owner and IME/TUI/virtual fallback policies are unchanged.

Conditional `remember` owns one current entry within the eager transcript composition. Flat,
virtual, absent-source and detached branches drop that entry. Each new entry replaces the old;
there is no linked cache history, retained frame, global cache or copied Text. The slices do
reference the current immutable row structure; this is not a heap/GC reduction claim.

## Validation contract

Seven new JVM cases cover exact row coverage at small boundaries and 1k/5k/10k; no structural
equals/hashCode in cache keys; active/same-revision updates; same-ID new objects; FIFO crossing
whole buckets/clear; untrusted sources; and discarded snapshot transactions. Existing row-sync,
publication and page source-contract suites remain required.

Seven instrumented cases cover three history sizes, active Text/font changes, replacement and
FIFO geometry, flat/virtual/IME/remount cache lifetime, and actual system-menu selection/copy.
For each size, one mounted production renderer first runs uncached, then cached. Mounting is
excluded. A fresh observer identity forces 32 actual transcript entries per arm without changing
rows or styles. Both entry counts must be 32; cached chunk wrapper and slice creation must be 0.
Uncached counts must match all chunks and the screen slice. Actual history/screen row composition
counts are reported without demanding a reduction; this deliberately measures wrapper work,
not timing, allocation bytes, cold start, phone FPS or the number of GC-reachable objects.

At first archival sequence 125, history sizes 1k/5k/10k have 9/41/80 chunks respectively.
Expected uncached wrapper totals are 288/1312/2560 over 32 entries, versus cached 0. These are
acceptance targets until a same-SHA run has passed and its raw evidence is verified.

The existing geometry and shared item/IME/default-bottom fixture now publish and consume the
same immutable source capability so relevant regressions exercise the optimized branch. The
legacy no-source path remains the comparison control. Lifecycle counters track committed cache
entries, not weak-reference collection or physical memory. No counters are installed by the app.

`report-terminal-chunk-views.py` requires all seven exact passing cases and all six old/new
samples. It rejects skipped/absent/duplicate samples, false entry counts and invalid types,
verifies production source copies plus APK hashes, and exports compressed hash-bound evidence.

## Authorized checks and CI

All edits are batched before validation. Local checks: changed `.editorconfig` lines, `git -c
core.fileMode=false diff --check`, shell `bash -n`, Python AST and workflow YAML. No local Kotlin,
native or Android compilation; no dependency installs/upgrades or full-file formatting.

Use explicit branch `opt/terminal-verified-a79e7b6`, pin each run to the resulting SHA:

1. Existing `terminal-viewport-interactions.yml`, `suite=staticChecks`: fixture-only `lintRelease`,
   then Release target/test type compilation; no emulator/test execution.
2. After static checks, local Python `test_terminal_chunk_views`, `test_terminal_viewport_shards`,
   `test_terminal_pipeline`, `test_terminal_pipeline_paired`.
3. `terminal-emulator-tests.yml`, `suite=chunkViews`: chunk views, rendered rows, render snapshot,
   UI-source and smoothness-source contracts, one invocation, no automatic retry loop.
4. `terminal-viewport-interactions.yml`, `suite=chunkViews`: 26 render-side tests (7 new, 8 geometry,
   3 text compatibility, 8 row measurement) and 18 viewport-side tests (7 default bottom, 6 IME,
   4 selected FIFO/restore/fallback stability tests and 1 active-row/history isolation test).
   Two bounded shards; no unrelated full V4 or full gesture/width benchmark.
5. `terminal-pipeline-tests.yml`, `suite=pipeline`: 36 actual-page/native-PTY echoes. Source inventory
   now explicitly covers the changed row and configured-renderer files. No transport-only preflight.
6. Only after all gates pass: `release.yml`, `build_type=release`, followed by the exact
   release-run/SHA in `verify-release-apk.yml`. No Debug build.

Maximum two validation rounds: initial checks then one batched correction/affected retry.
Wait using a foreground automatic polling command, at most 15 minutes per wait window. Timeout
is reported as unfinished, not success/failure. Preserve original run identities and old evidence.
The independent foreground-service preflight issue is outside this batch.
