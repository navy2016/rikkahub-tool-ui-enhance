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
TUI streamIme commit spans have median2.570s versus row-sync11.718ms; these are component work spans,
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

## Candidate selected after grouping evidence

The grouping probe passed3methods/54samples/27disposals at c7caf0a/run39383512446. Original report
SHA256445021ad4c79dd61d96f9979c153c0e7cf25b9374aaa13554259f50d0ca4b473 was relayed by39384983992,
artifact12246527399 (60882B); all samples/source/APK identities/geometry hashes were independently
recomputed.5k separate sampler saw166/236 samples with SlotWriter.moveGroup in the retained top
stacks; this is stack frequency, not exact CPU percentage.10k three-tick completed medians were
17688.14ms flat,1698.23ms chunked,496.39ms layered. Two formal order-reversed observations support
an intervention, not a general FPS claim. No report changes the failed24tick run's conclusion.

Selected production scope: two new explicit per-command RENDER choices `tuiChunkedEager` and
`tuiChunkedLayers`. Original default and every existing renderer's eligibility remain. Only owned,
nonempty, main-screen TUI history with pan enabled and SEL/mouse/ALT/full-grid absent may group.
Unknown main-screen commands do not get transcript semantics: SCREEN-following stays SCREEN,
RAW stays RAW, the entire physical screen remains eager, and all natural row heights are required.
This does not record ALT history, virtualize TUI, change parser/row-sync, or retain disposed UI.
Outside TUI, new modes use existing default ordinary behavior; invalid TUI geometry falls back flat.

Candidate verification must exercise these exact production modes: original24tick/2line viewport
contraction and reentry workload at1k/5k/10k; same-device controlled small comparison; locked reading,
font/column changes and source invalidation; mode/SEL/mouse/ALT/fullgrid handoffs; actual page/native
PTY finite continuing output during realIME/reentry with status+KEYS. Existing bottom regressions and
default/virtual behavior remain separate gates. No formal Release until all candidate gates pass.

### Frozen first-candidate gates

The production-mode comparison uses the SAME fixture APK/process/device for current default TUI
compatibility versus each new opt-in mode. Baseline core renderer/synchronizer/controller/parser/font
files are byte-identical to7218dd7; a five-file allowlist binds the intervention. This is not an old-APK
comparison.1k/5k/10k x default/TUIchunked/TUIlayers x cold/3tick-stream x one warmup/four formal
ABC/CBA rounds =90samples (18warmup72formal). Every sample stays, including slower candidates.
For5k/10k streams, all four paired completions must improve and median completion AND commit spans
must be at most80% of the control. Other cold/small-load medians must be at most115% of control+20ms.
These fixed criteria apply before the first candidate measurement and are not changed after a failure.

Correctness uses5 new viewport methods (31actual cursor rectangles,6pairs of24tick/48line streams
and reentries,10compatibility gates,locked anchors/font/column/IME transitions). Full viewport inventory
becomes173.6new JVM methods check opt-in/persistence/owned-source/gates/FIFO/wiring. Old negative
control remains exact old production with byte-identical bottom oracle and c7caf0a API-compatible
test helpers; candidate-only types are excluded ONLY in that disposable old-source runner.

Two real-page nativePTY methods use each new mode with1万 retained rows/status/KEYS. The page first
applies its history limit, then seeds10100lines. Three finite160tick producers emit two lines every
100ms while actual keyboard opening/closing and page detach/reentry occur. New native output and
new matching page draws must precede DONE; detached output advances at least3ticks. Exact native
read/parsed byte sums are accumulated through bounded trace windows; initial seeding is excluded.
Final real Text rectangles and unchanged grid/history are checked after each output burst; RAW UI
submission and page/session/capture cleanup are required. The test peer explicitly sets canonical
reads inside a recorded RAW session; it does not claim an account-backed Pi or a full raw-peer test.

Only first candidate acceptance plus one consolidated correction is authorized. No new candidate run
has been dispatched at the time this protocol was frozen. Baseline and probe failures remain original.

### First candidate and consolidated test-only correction

Candidate `5eecb476370f22e6474f8f54e3bbf5ee5c898363`, production tree
`71b1b14fde6b0bc3ec413b8024012060c404858b`:473JVM (39413045004),173viewport (39413066710),
90production-mode comparison samples (39413082782) passed. All12predeclared performance decisions
passed.10k three-tick completion medians: unchanged default18198.57ms, TUIchunked1959.07ms,
TUIlayers567.57ms.10k cold medians:2131.00/2191.87/969.97ms respectively; lightweight grouping is
not a cold-mount improvement. These inclusive component scopes remain unrelated to phoneFPS.

Two first-round jobs failed BEFORE instrumentation: bottomControl39413076265 incorrectly included
c7caf0a's diagnostic `followTarget` calls in the old-source test pair; page39413103263 referenced
UiAutomator absent from that module. Original compilation failures remain failures, not device
regressions or successful page coverage. The consolidated correction changes only test sources:
old-source API-compatible helpers now come from7218dd7 (same bottom/default oracle bytes), excluding
new diagnostics; real-page touch/BACK use platform UiAutomation input injection without adding a
dependency. Existing output regex was already correct and is now checked by host regression.
No production source, timing/geometry assertion, threshold, workload size or font is changed.
Only these two jobs are reverified; successful first-round gates are not rerun to select faster data.
This consumes the one consolidated correction round when dispatched. Release remains blocked until
the real-page/nativePTY/IME/reentry and old-source controls actually finish successfully.
