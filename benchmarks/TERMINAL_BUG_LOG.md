# Terminal optimization bug log

Record newly found defects and risks with source, evidence, fix and validation. Fix small issues
directly when their scope is clear. Do not silently alter a frozen candidate/protocol; version such
changes separately. Performance rejection by itself is not proof of a specific software defect.

## BUG-B1-001 — FIFO test forgot hidden-cursor reservation [fixed]
- Location: `benchmarks/terminal-physical-metrics-b1/TerminalPhysicalMetricsTest.kt`,
  `fifoArchivedRowsAreMeasuredAsHistoryNotOldPhysicalProducts`.
- Evidence: run41294632502/source6b831072 failed `expected:<1> but was:<2>`; component28/24 passed.
- Cause: cursor stays at row end; the former row loses its reserved blank and the appended row
  gains one. Two physical Text values change, not one. Existing306 cursor-reservation test proves it.
- Fix: commitf9c7e036; assert exact old/new Text and stable ID,2shared/4reused. Candidate code unchanged.
- Validation: run41295383061,529JVM/28device/24scenes; originals independently verified. Closed.

## RISK-B1PERF-001 — capture MAIN identity before IME setup settles [fixed before freeze]
- Location: `TerminalPhysicalMetricsPerformanceTest.kt`, `exercise` initialHistory capture.
- Found by source inspection while adapting the native driver; not an observed device failure.
- Risk: capturing history immediately after opening IME precedes the existing stable-grid/IME
  handshake. A later setup transition could invalidate the stored provenance before timing starts.
- Fix: call `settle(false,"initial-main")` before capture, record the extra setup gate explicitly.
- Validation: native-driver/protocol source guards verified the order and full gate inventory
  before freeze6ecaa60; all52host source/statistics/profile tests passed. Run41300006846's96native
  cases, setup gates and final MAIN history identities were independently rechecked from originals.
  The separate trace-integrity/performance rejection does not invalidate these native assertions.
  No candidate/production or already-frozen protocol changed.

## BUG-TRANSPORT-B1-001 —400MiB relay cap rejects the complete96trace archive [fixed]
- Location: `.github/workflows/relay-artifact.yml`, artifact ownership/size guard.
- Evidence: relay41316773111 failed `Artifact ownership, size, expiry or digest is invalid`;
  artifact13084527235 is542237998bytes, exceeds400MiB, unexpired and exactly bound to completed
  run41300006846/sourcea5ed6075853a155028c213a015de500ddc07672f.
- Fix: one exact run/source/id/name/digest/size permit. Every other archive keeps400MiB, and
  downloaded/uploaded bytes still require the original SHA256. No experiment,APK or threshold change.
- Original failed relay stays failed. Retry is transport only; no repeated device samples.
- Validation: commit770ae1d,7exact-bound policy checks passed. Transport-only relay41317096277
  succeeded; original542237998bytes/digest validated in CI and selected originals retained locally.

## INCIDENT-B1PERF-002 — native run complete but frame evidence not fully decidable [V1 closed/rejected]
- Run41300006846/sourcea5ed607 failed `B1 frame identity evidence incomplete; originals kept,no replacement sampling`.
- Build and typed qualification passed; raw asset bytes identical. Full frozen matrix report emitted.
- All96raw traces/3360windows independently replayed, exactly matching the frozen CI classification.
  Eight traces invalidate462windows (440formal): seven noninitial `previous_packet_dropped=1`
  markers with `first_packet_on_sequence=0` and prior observed packets, plus two unclosed relevant
  Choreographer spans (one trace overlaps). All dropped-marker packets contain ProcessTree field2.
- Both unclosed UI spans start after the final timed cutoff (2734530007ns and5783747877ns), but
  frozen `terminal-frame-identity.py:attempt` rejects any trace fault before following the frame chain.
  This is NOT evidence that462windows actually failed to submit. No per-window exemption is introduced.
- All96Perfetto start/stop/wait/pull receipts succeeded. No trace reaches96MiB. These receipts do not
  prove packet completeness; the exact reason producers emitted the dropped flags remains unproven.
- B1V1 is rejected for integrity AND30numerically decidable gate failures. No sampler/parser/threshold
  change, no replacement data or APK. Production stays306; no production rendering defect is inferred.

## BUG-EVIDENCE-B1-003 — multipart check summary is not fully delivered [artifact recovery complete]
- `terminal-physical-metrics-performance.py:experiment` emits typed qualification plus10summary notices
  in one step. Only summary parts1..9 are present in the completed check-run annotations.
- Originalsummary.json canonical SHA2565ebf52073cf7efb6368eaf8b796c0f2e67930427ae30ee30dc678c179b0f9032
  and all9available parts independently match. Part10 was not fabricated; full artifact is authoritative.
- Historical emitter stays frozen. Future protocols must reserve per-step annotation capacity or use
  separately bounded publication steps; this is a delivery defect,not a reason to repeat sampling.

## BUG-TRANSPORT-B1-004 — rapid small CDN range retries ignore429 backoff [fixed locally]
- Local evidence reader hit `HTTP/1.1 429 Too Many Requests` fetching many small trace-status records;
  the original immediate4retry loop did not respect rate limiting. All96raw traces were already verified.
- The B1-only workspace reader now paces network ranges≥3s and honors numeric Retry-After with≥60s
  backoff; retains verified caches and bounded206/Content-Range/CRC/SHA checks. No timing code changed.
- Validation: resumed only the same metadata byte ranges; all195additional provenance members retrieved,
  including96successful trace start/stop/wait/pull receipts. No full ZIP/APK download or new device run.

## RISK-GEOMETRY-C1-001 — live LazyList items are not immutable cache keys [avoided before sampling]
- Source: inspected Compose1.11.0-alpha06 `LazyListMeasureResult.copyWithScrollDeltaWithoutRemeasure`
  calls `LazyListMeasuredItem.applyScrollDelta` and shares the visibleItemsInfo list. Individual offsets mutate.
- A retained layout/item reference is not immutable coordinate evidence. This was found during C1
  design inspection, not an observed306production failure. No layout-identity cache was introduced.
- C1 keeps every live readFull key/Text/height/physical check and detaches a private scalar array.
  Only that immutable geometry's validation proof is shared during policy copies; no across-read reuse.
- Validation: C1 source17host checks and run41335095300 passed528JVM/44component/17new scenes;
  actual synchronous-scroll, metric/key/Text/height rejection, MAIN/ALT/FIFO and session-release
  original records were independently checked. C1 remains mechanism-only,not adopted.

## BUG-HARNESS-C1-002 — copied mechanism harness retained its old identity pin [fixed before dispatch]
- During local scaffold validation, `terminal-geometry-c1.py guard` stopped with `C1 identity changed`.
- The scripted replacement did not match the old literal, leaving the prior harness identity pin.
- Rebound exactly once to the new C1 identity JSON SHA256; guard and17host tests pass. No CI run,
  Android build, frozen candidate mutation or sample was produced under the wrong pin.

## GUARD-LIFECYCLE-001 — old qualification glob includes a different frozen production baseline [corrected before dispatch]
- The new workflow's initial `test_terminal_frame_identity*.py` glob also selected the historical
  qualification guard, which correctly failed `Identity qualification changed production` on306.
- Its parser tests and its historical production qualification are distinct. The new workflow now
  runs exact`test_terminal_frame_identity.py` plus`test_terminal_trace_lifecycle.py`, whose306source
  guard is separately frozen. The old qualification test/guard/parser are unchanged,not bypassed in
  their historical experiment. No device run was dispatched under the incorrect selection.

## BUG-LIFECYCLE-002 — UiAutomation Runtime.exec does not preserve sh-c quoting [quote transport fixed; V1 stays failed]
- V1run41338176466/source2f1535c built successfully, then all12assigned first-launch methods failed
  before tracing: `Trace shell exit receipt missing` / `f: no closing quote`; cleanup reports `Trace PID absent`.
- Same-run artifact13096382095 was relayed by41339387919; all12original case errors verified with
  empty samples and no TRACE_START records. Actual observed windows=0, traces started=0; no second launch.
- Android14UiAutomationConnection.java directly calls`Runtime.getRuntime().exec(command)`, so the
  quoted script was split into multiple arguments. This is a new harness defect,not a rendering bug.
- New isolated V2 passes only`sh` and writes the program via stdin, with a quoted heredoc for exact
  config bytes. No change to306, raw trace recipe,840window matrix, frame parser or acceptance gates.
- Original failure remains failed. Native changes are in`benchmarks/terminal-trace-lifecycle-v2/v1-transport-fix.patch`;
  host tests reproduce broken tokenization and verify fixed stdin/exit handling. V2reached Perfetto,
  proving shell invocation works, then failed the separate heredoc-read defect below. No qualification pass.

## BUG-LIFECYCLE-003 — V2heredoc does not deliver readable config to Android Perfetto [SELinux cause confirmed; V3qualification pending]
- Run41339783414/source90815fa built, then all12first-launch native cases failed at Perfetto startup:
  `The TraceConfig is empty`. The sh command now executes and returns its exit receipt; quote parsing is fixed.
- Original artifact13096254355/relay41341253419 independently confirms12empty sample arrays and no
  TRACE_START. Actual windows=0, trace starts=0, second launch not executed. V2 stays failed.
- Host heredoc/cat success did not prove Android Perfetto could read that descriptor after its startup.
  The lower-level reason is not yet established; do not label it a parser/production failure.
- Before any further840window run, use a no-APK Android34direct-stdin/heredoc/pipe comparison with
  byte hashes, process exit status and available SELinux diagnostics. No resampling or gate relaxation.
- Diagnostic41341668403/source5e46c113 independently reproduced the cause: all3cat SHA256 values
  equal the572byte config; directstdin/pipe start successfully, heredoc reports empty config. Original
  SELinux audit denies Perfetto read of`/data/local/tmp/sh*.tmp` with`shell_data_file`label. Do not chmod
  or disable SELinux; use actual pipe transport. Artifact13097224397/relay41341886904 originals checked.

## BUG-LIFECYCLE-004 — kill0 permission denial is mistaken for process exit [stop proof under diagnosis]
- Same no-APK Android34diagnostic originals contain SELinux `denied { signull }` from shell to Perfetto.
  The legacy `while kill -0 ...` loop can return success on permission denial before the process exits.
- Successful SIGINT/wait/pull status alone is therefore not a trustworthy stop-completion proof.
  This does not establish which historical frame/packet was affected and does not change A1/B1 results.
- Preserve frozen collectors. Separately verify `/proc/PID/stat` readability/liveness and disappearance
  after SIGINT before a new acquisition recipe. No root/SELinux weakening or replacement measurements.
- No-APKstop diagnostic41342164946/source86b07f5 proves `/proc/PID/stat` readable while the exact
  live Perfetto PID rejects kill0 with Permission denied. After SIGINT, proc disappears. Its first
  directory-test/read loop hit the expected exit race (read returned ENOENT after directory test),
  preserved as stopExit73. Race-safe diagnostic must accept ENOENT only after rechecking proc absence,
  not conflate it with persistent permission/read errors. Still no terminal-window samples.
- Race-safe no-APK diagnostic41342417785/sourcec59fb528 checks readable live proc and denied kill0,
  then returns SIGINT/proc-absence stopExit0. V3adds matching PID/comm/start ticks before/during
  stop and observed disappearance,never treating signal permission denial as completion. Native
  full-page qualification still required; diagnostic success is not840window qualification.

## INFRA-LIFECYCLE-005 — JitPack401 blocks V3 before any device window [build recovered; native qualification completed/rejected separately]
- Run41342838188/source9985cfc fails `:app:mergeTerminaltestNativeLibs` resolving unchanged
  `com.github.rikkahub:sqlite-android:-SNAPSHOT`: GETmaven-metadata.xml returns401Unauthorized.
- Qualification step never started; no app/device window or trace sample exists from this run.
  Exact check annotations were retained; no full build-log download or dependency/version edit.
- The same public metadata URL subsequently returns200 without credentials, naming snapshot
  `-80cedc8888-1`; upstream repository is public. No token is sent to JitPack.
- Keep original failed run. One separately identified infrastructure retry may use unchangedV3
  source files/protocol/driver/config; it is not replacement sampling. If unresolved, retain the failure.
- Retry41343554975/sourcea5ccea0 (documentation-only difference) successfully completed the unchanged
  target/collector build and entered native840window qualification. Dependency declarations and versions
  were not changed. Native results are not yet retrieved; this fixes no historical performance verdict.
- Final: all840windows/24traces were independently replayed. Qualification fails on16formal tail windows,
  separately from this recovered dependency access error. No further infrastructure/sample retry authorized.

## INCIDENT-LIFECYCLE-006 — final-round FrameTimeline endpoints missing despite orderly stop [V3closed/rejected; cause under investigation]
- Run41343554975/sourcea5ccea0 completed840windows and24traces; original artifact13099142636/relay41347058077.
- All raw identities/native cases/stop and pull receipts independently match the frozen CI classifications.
  16formal windows fail,allr20:3BUFFER_CHAIN_INCOMPLETE with no SurfaceFrame;13missing-cookie-end after submission.
  Five have earlier complete endpoints but a later qualified incomplete attempt; the original gate rejects them.
- 1201qualified attempts/1198submitted/1185presented,1200BOTH_EXACT+1ABSENT. No original-parser trace faults.
  Missing evidence is not proof of actual nonsubmission or dropped/late display; no null becomes a duration.
- Perfetto v34CLI already flushes on SIGINT. Service all_data_sources_flushed can include errors; process exit
  and this event are not complete-producer-data proofs. AOSP/SDK writer-tail references are diagnostic leads,
  not binary/source identity or a proven cause in this guest. No historical parser/data change or resampling.
- Next: independently freeze a small no-APK tail-delivery diagnostic; keep timing cutoffs and all missing
  endpoints intact. CurrentV3 remainsfailed; C1performance cannot begin until a new acquisition qualifies.

## INCIDENT-TAIL-DIAG-001 — compositor request denied and moving-tail cutoff is inconclusive [V1closed/incomplete]
- Fixed8case no-APK run41351430986/source351d6b5 failed. All8raw traces/receipts verified from
  artifact13100653852/relay41351888266; six normal analyses match CI, two failed branches are not replaced.
- Bothcomposite requests return exit0 but explicit Parcel `Operation not permitted`; the guard correctly
  refuses success. Never change permissions or bypass the restriction.
- Baseline has missing final records+unclosed spans; wait/periodic have no parser faults or pre-cut missing
  SurfaceFrame ends, but the chosen tail frame's display endpoint is after the fixed logical cut.
  This is not no-submission or a demonstration that flush is ineffective. Sliding Settings is not a
  controlled stationary terminal response. New diagnosis needs finite stimulus and separate target/cut
  bounds; no cutoff/target selection change is applied retroactively toV1orV3.

## BUG-TAIL-REPLAY-002 — integer protobuf histogram keys compared to JSON string keys [fixed locally]
- Firstoffline tail replay asserted in-memory `timelineKindCounts`/`payloadCounts` maps equal to
  decoded JSON maps. Their counters matched but JSON stringifies integer object keys.
- Normalize only the two known maps' keys in`verify_diagnostic.py`, then compare complete structures.
  All6normal analyses match; all8originaltrace hashes and receipts verified. No native/parser change.

## OBSERVATION-FINITE-TAIL-003 — finite-launch targets complete but terminal tail remains unqualified
- Fixed8case run41353833781/source573341f890 succeeded. Artifact13101954048/relay41354159180,
  all8raw hashes/receipts/analyses independently matched. No APK or terminal timing samples.
- All fixed selected targets have complete pre-cut chains,including both baselines,so no improvement
  can be attributed to extra wait/periodicflush/volume input. HistoricalV3isnot repaired by this result.
- Ancillary replay shows6..22later structural UI attempts and7/8last structural tokens without a
  SurfaceFrame. These are not the frozen selected targets and are not used to change the verdict.
- Built-in Settings launch still cannot establish the cause of static terminal final-buffer absence.
  A separate small native306experiment is needed; no current native qualification or C1adoption.

## DEVICE-306-001 — TUI bottom gap grows during operations [user reproduced; investigation]
- User tested the formal306APK and reports intermittent failure to remain bottom-aligned when following is expected.
- Source paths: ProcessSessionPage viewport/grid/IME sizing; TerminalViewportBinding; TerminalItemViewport.followDelta;
  TerminalEagerViewportGeometry.followTarget and controller effect lifecycle. Exact triggering sequence is not yet isolated.
- Preserve AUTO/LOCK intent, real measured rows,IME/blank-grace/FIFO and single-writer protections. Do not label a
  root cause or claim a fix before a reproduced geometry assertion. Device screenshot/operation context remains useful.

## DEVICE-306-002 — width-only change changes LOCK to AUTO [user reproduced; source path located]
- TerminalEmulator.resize retains IDs on a columns-only resize. Many TUI SIGWINCH redraws then issue ED2;
  eraseInDisplay(2) calls invalidateActiveScreenLineIds. A prior physical-row anchor is now invalid.
- TerminalViewportController.reconcileItems treats an unresolved anchor as followMode; eager reconciliation
  also changes to followMode after an unresolved measured anchor. This provides a concrete path for the report,
  but must be reproduced with columns-change+TUI-clear, not silently change all historical clear policies.
- Plan: bounded user-width-change intent preservation and exact reanchor after valid layout; explicit user
  AUTO/jump/drag must supersede it. No stale-generation anchor reuse or synthetic history coordinates.

## DEVICE-306-003 — MOUSE touch enters copy while SEL is OFF [user clarified; root cause NOT established]
- User explicitly confirms MOUSEon/SELoff. The earlier SEL+MOUSE contradictory-state hypothesis does NOT
  explain this report. Its enum/transition draft was removed from the R1candidate; do not count it as a fix.
- Source inspection finds no page-parent SelectionContainer, and local virtual selection is disabled when SELoff.
  Two independent paths need evidence: pointer coordinate/capture dispatch, and the application's OSC52clipboard
  requests after receiving mouse input. Neither is yet proven as the reported cause.
- Next: unchanged306native page with raw PTY mouse-byte receipt, touch press/drag/release (including long hold),
  MOUSEon/SELoff assertions and clipboard sentinel/explicit OSC52control. Existing page test only toggled MOUSE
  and checked geometry; it did not deliver and verify a mouse drag. Preserve FIFO and release/cancel behavior.

## DEVICE-306-004 — some row right edges form stair-step jaggies [user reproduced; geometry diagnosis pending]
- Current physical TUI rows use natural Text layout; TerminalEmulator cell widths/continuations and rounded
  W cell hints are not an assertion that shaped mixed-script run advances are exact integer cell multiples.
- Need distinguish glyph/border alignment,background endpoints and clipping using screenshot plus real-font
  equal-column mixed-ASCII/CJK/emoji/style cases. Do not replace font,clip valid text or call a guessed fix complete.

## WORK-PRIORITY-306 — functional repair precedes new collector/performance work
- User's four formal306device regressions supersede pending optimization diagnostics. C1/old protocols stay frozen.
- Untracked benchmarks/terminal-native-tail-diagnostic is an uncommitted draft,never dispatched; leave it parked.
- Functional changes must use a separately identified306repair candidate with scoped tests/evidence. No automatic
  adoption or formalRelease until correctness validation; production currently remains306.
- Latest clarification: width changes must preserve whichever AUTO/LOCKstate existed before the change.
  MOUSEfailure occurs without SEL. Screenshot has not appeared as a real attachment in the accessible files.

## HARNESS-306-R1-001 — pre-dispatch preset and input-fence corrections
- Draft diagnostic assumed80→120, but production presets are80,100,120,160,200. Corrected to80→100→120 before any CI run.
- Direct manager terminator could overtake pending UI queue writes. Added UI_INPUT/enqueued count and enqueue/written byte fences before the terminator.
- Empty initial preferences now mean not-configured, not JSON failure; startup+raw-flow+localsettings gates retained.
- Screen-coordinate native touch tests retain DOWN/MOVE/UP and guaranteed CANCEL on failure; clipboard is restored in cleanup.
- All corrections are confined to new R1tests/harness;16host tests pass. Kotlin/native execution pending, no production change.
