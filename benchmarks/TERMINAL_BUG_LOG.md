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
  before freeze6ecaa60; all52host source/statistics/profile tests passed. Device execution pending.
  No candidate/production or already-frozen protocol changed.
