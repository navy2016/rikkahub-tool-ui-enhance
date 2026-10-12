# Reuse terminal font lookups and text metric identities

## Scope

`terminalTextMetrics` is called by layout-pass binding and virtual-history width measurement.
Previously each invocation queried the resolver for Normal, Italic, Bold and BoldItalic, then
allocated a new Density, font-value list and metric key. Resolver queries are NOT font loads.

This change retains the four returned `State<Any>` objects only for the EXACT built-in
`JetbrainsMono` family whose declared fonts all use `FontLoadingStrategy.Blocking`. Structurally
equal copies, system, optional, custom and async families keep live queries on every invocation.
Resolver identity and font synthesis invalidate the retained blocking-font states. No global
cache, preloading, network access or dependency change is introduced.

Every state's `.value` is still read in composition, outside `remember`. Full TextStyle,
density/fontScale, layout direction, resolver identity and the four current font values key the
reused metric object. State changes therefore invalidate BOTH width and natural-height caches;
same-revision frame identity, cache failure/partial-measurement gates and cancellation remain.
Text drawing, row widths, default renderer, status/KEYS, selection/mouse/TUI and IME semantics
are unchanged. This batch does not fix the independent foreground-service preflight failure.

## Tests and evidence

Six `TerminalTextMetricsInstrumentedTest` cases cover:

1. One real resolver and two consumers, 32 independently committed recompositions each, with
   mount excluded: old queries 128 / new queries 0; old metric identities 32 / new identities 0.
   The production helper's optional query observer is never installed by either production caller.
2. Style size, line height, color, spacing, weight, italic, density, fontScale, layout direction,
   synthesis, resolver and font-family changes invalidate only the relevant cache identities.
3. Equal-but-not-identical built-in family copies, serif and default fonts keep live queries.
4. Local gated async font success, without new output, updates actual production width and eager
   row geometry. Every width/height is compared with uncached platform TextMeasurer; the final
   visible row is checked in the real layout.
5. Async failure preserves fallback geometry and the framework's permanent failure caching.
6. Resolver-scope cancellation followed by a fresh resolver can retry and complete loading.

The async tests use the framework's public `AndroidFont` extension point, distinct per-test font
identities and a local CompletableDeferred. They do not implement the sealed Resolver interface,
preload fonts, call internal text APIs or fetch files. Font completion alone must refresh metric
consumers. All original assertion, measured geometry and sole-scroll-writer protections stay.

`report-terminal-text-metrics.py` requires all six exact successful JUnit cases and the one
complete integer counter record, verifies production-to-fixture source copies and APK hashes,
and emits hash-bound evidence chunks. Counts demonstrate avoided queries/metric allocations,
not font-load savings, allocation bytes, full-page latency, GC or physical-device FPS.

## Focused verification (GitHub Actions only for compilation)

After all edits, check changed `.editorconfig` lines, `git -c core.fileMode=false diff --check`,
`bash -n benchmarks/terminal-viewport-tests/run-ci.sh`, Python AST and workflow YAML. Then use
explicit branch `opt/terminal-verified-a79e7b6` and a single exact source SHA:

- `terminal-viewport-interactions.yml`, `suite=staticChecks`: existing fixture `lintRelease`
  followed by Release target/test compilation. No JUnit/emulator in this stage.
- Once static checks pass, local Python modules `test_terminal_viewport_shards`,
  `test_terminal_text_metrics`, `test_terminal_row_width`, `test_terminal_panel_subscription`,
  `test_terminal_pipeline`, `test_terminal_pipeline_paired`; no unrelated full test discovery.
- `terminal-emulator-tests.yml`, `suite=textMetrics`: only `TerminalTranscriptWidthIndexTest`
  and `TerminalEagerGeometryCacheTest`, once; no inherited three-attempt retry loop.
- `terminal-viewport-interactions.yml`, `suite=textMetrics`: 40 cases: metrics 6, intrinsic
  widths 10, row measurement 8, binding 3, default bottom 7, IME 6. Existing evidence is preserved.
- `terminal-pipeline-tests.yml`, `suite=pipeline`: 36 real-page PTY echoes, no transport preflight.
- Only after these gates pass, build `release.yml`, `build_type=release`, then independently
  verify with `verify-release-apk.yml` using the exact build/run SHA. No Debug or local build.

Maximum two validation rounds (first, then one batch correction/affected retry if needed).
Single foreground polling command, at most 15 minutes; timeout is not a test verdict. Original
older run identities stay frozen. This protocol is not a claim that a future CI run has passed.
