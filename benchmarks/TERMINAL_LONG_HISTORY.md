# Long history, TUI reentry and IME during output

Authorized after the user confirmed both bottom-visibility fixes on their phone. Baseline:
`7218dd7e50577bc6fa94575aa476b2a436c71f61`; production `bff4a51`, tree
`c1463f057fda3bbc693f7e8164065da4baebcdb1`. Branch `opt/terminal-verified-a79e7b6` only.
User reports long-history TUI reentry/IME slow, especially while messages are still being generated.

## First diagnose, then optimize

- Diagnose 1k/5k/10k history using the existing exact row store, Text, height measurements,
  controller and sole scroll executor. Separate state creation, composition commit, root measure/
  placement/draw, first draw, row sync and completion waiting. State seeding is excluded.
- Ordinary default and main-screen TUI are separate profiles. Workloads: cold mount; fixed finite
  output during viewport contraction/expansion; output across detach/reentry. The isolated viewport
  contraction is an IME-layout model, not actual system IME or native PTY latency.
- The baseline diagnostic changes no production source and is explicitly non-acceptance. Fixed
  samples are all retained, including slow ones. Do not calculate gains from unrelated old runners.
- Candidate scope will be based on the observed work/timings. The existing TUI FIFO rejection and
  flat history/screen row loop are hypotheses, not proof of their share of phone frame time.
- User proposed extending ordinary-history renderers to TUI. Evaluate eager history grouping/layers
  first; keep the physical screen and SCREEN-following policy intact. Virtualization requires separate
  TUI/IME/mouse/selection/ALT proof; simply deleting !tui guards is not an approved implementation.
- For candidate acceptance, controlled same-run baseline/candidate measurements and full-page
  native PTY plus actual keyboard/reentry under finite ongoing output must complement the component
  measurements. Model-only contraction cannot replace real IME coverage.

## Invariants

No local Android/Kotlin/native compilation, Debug APK, dependency/font changes, or APK download.
Use apply_patch, explicit branch dispatch and [skip ci]. No subagents. Keep original failures.
Default remains CHUNKED_LAYERS. Keep all retained history, natural Text metrics, annotations,
COPY/selection/mouse/ALT behavior, RAW/IME bytes, complete frame/rows publication and one scroll
writer. The bff4a51 cursor/actual-height fixes and their negative controls remain regression gates.
No fixed heights/padding, scrolling repairs, dropped parser bytes, re-labelled old evidence, or
retained disposed Compose tree to hide cold cost. Extra memory must be bounded and explained.

After non-acceptance baseline diagnosis: at most one candidate verification and one consolidated
correction round. Adopt only supported improvements; preserve/revert an unsupported candidate.
Formal Release and independent APK check follow successful correctness/performance gates; report
scope honestly, no universal FPS promise or zero-leak claim. Reference Release39094656675/bff4a51.

User's phone has confirmed prior bottom fixes; this new performance task remains unverified on
their phone until they retest. Earlier row-scope experiment is rejected and will not be rerun.

## Baseline component diagnostic v1

`terminal-long-history.yml` runs `TerminalLongHistoryDiagnosticTest` against the exact unchanged
baseline production tree. Three size methods, ordinary/TUI profiles, one warmup and three formal
rounds, phases cold/streamIme/reentry:18warmup +54formal samples. Each streaming phase publishes
24 display-frame-paced ticks of2 lines, with FIFO trim retaining exactly the configured history.
streamIme shrinks240dp to140dp for ticks8..15, then restores240dp. Reentry discards the old store/tree,
feeds8 detached lines, creates a new store/tree and publishes another24ticks. This is not a wall-clock
unrestricted producer and not native-PTY/real-keyboard evidence. Completed samples require full measured
geometry, latest drawn revision, tail satisfaction, no active writer/effect and two fresh stable draws.
Actual blank cursor/Text bounds are checked after timing. No fixed wait is a completion proof.

State creation/frame capture are timed separately before mounting; initial model seeding and final
semantics scans are outside the viewport timer. Root measure/place/draw spans are inclusive scopes,
not exhaustive per-node CPU attribution; independent descendant passes need not enter the root probe.
Composition-entry-to-SideEffect includes apply bookkeeping; completion includes frame/poll waits.
Every sample and work counter is retained, no latency threshold or favorable re-run selection.
