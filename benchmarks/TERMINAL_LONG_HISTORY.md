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

## Original failure and independent grouping intervention

At `ab703f95e0cbbb74a2f8a8b7319521f02fc9d73a`, run39195471176 compiled both Release fixture APKs
but failed two of three diagnosis methods. The exact XML reports `Condition still not satisfied after
45000 ms` in `settle`, called by `diagnose` line295 (streamIme), for5k/10k.1k completed all24 samples;
5k/10k each retained four warmup samples before the failure. No timeout checkpoint was recorded, so
the original run alone cannot prove whether output was still processing or a settle condition was stale.
It is neither a complete baseline nor production acceptance. All32 original samples stay archived.

Original artifact12161624116 (55328B, SHA256
871c0022c091052050d2afa5ade1db6f605f6185a400dc278ff8d038fd3147cb) was relayed unchanged by39364166881.
Selected original XML/logcat are archived locally; no APK or full build log was downloaded.1k formal
TUI streamIme commit spans have median2.560s versus row-sync11.718ms; these are component work spans,
not phone performance or a controlled candidate improvement. The composition span includes apply
bookkeeping, so attributing it to Text alone is not justified.

`suite=longHistoryProbe` is a separate non-acceptance intervention, not a shortened passing v1 run.
The same unchanged production classes are invoked with fixture-only FLAT/CHUNKED/CHUNKED_LAYERS
archival plans while TUI metrics, screen-following, dense row store, FIFO guard, natural Text heights
and scroll executor remain unchanged.1k/5k/10k; one warmup then two formal ABC/CBA order reversals;
cold and3tick (2lines/tick)240->140->240dp phases:18warmup+36formal samples. Each phase requires
latest complete geometry and two stable new draws; actual cursor rectangles and all-row height/scroll
range hashes must agree, and each mount must release all measurement ownership on disposal.

The original24tick methods, all assertions and45s bound remain. Periodic/timeout checkpoints now
record exact ticks, frame/pass/draw revisions, geometry, effect/writer, offsets and work counters.
An additional5k FLAT3tick run samples the main-thread stack every20ms, separately from the timed
observations. At most128 distinct keys and the12 most frequent stacks are retained; this perturbs
that extra run and is not exhaustive/exact CPU accounting. These results cannot stand in for actual
keyboard/nativePTY, repeated reentry, the original24tick matrix or final candidate acceptance.
