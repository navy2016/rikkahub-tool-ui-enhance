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
