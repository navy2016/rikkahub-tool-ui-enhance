# Terminal viewport gesture compatibility fixture

This is an **opt-in correctness fixture**, not a performance benchmark or a production lazy renderer.
Normal app/release builds do not include this APK. It injects real Compose pointer gestures into an
eager Column and a history-only LazyColumn, using generated copies of the **production** viewport
controller, reducer, gesture helper and row Text renderer. There is no shell, PTY or production data.

## Contract under test

Both renderers have 1,000 stable-ID history rows, one complete 24-row active-screen item, an 8px tail,
and the same fixed status strip, parent nested-scroll connection and child fling behavior. The fixture
has one effect executor; gestures can only ask the production controller to jump. Assertions inspect
actual top/tail placement, controller follow intent and concurrent writer count, not just method calls.

Twelve interaction cases run in **each** arm (24 total, no skipped/missing arm accepted):

- two fast upward swipes → actual bottom, TAIL mode, one composed screen grid;
- two fast downward swipes → actual top, LOCKED mode;
- configured count of three → no jump on the second swipe;
- slow swipes → normal pan, no jump or spurious reducer correction;
- non-animated follow → fresh completion measurement, no synchronous scroll retry loop;
- selection mode → pan enabled, fast jump disabled;
- mouse mode → Compose pan and fast jump disabled;
- horizontal swipes → horizontal pan, no vertical jump;
- 30 append/trim updates during a bottom jump → final tail still visible;
- 30 append/trim updates during a top jump → no accidental follow resumption;
- a new pointer drag during a jump → cancellation without stale completion pulling it back;
- measured `LazyListLayoutInfo` items → stable `lineId` anchor capture and restore.

The recognizer's clock is injected, so software-GPU CI delays between input calls cannot turn a
simulated quick sequence into an expired 700ms window. Pointer dispatch, velocity recognition and
scroll animations are real; animation scales are **not disabled**. Lazy scroll callbacks sample the
current measured layout synchronously, before the controller captures a consumed delta or reconciles
a completed effect; the coalesced frame observer is not used as a scroll-completion acknowledgement.
Effect boundaries also publish the latest frame metadata, so a trimmed anchor is replaced by the
production reducer before resolving another lazy item target. An unresolvable target fails explicitly
instead of silently completing and retrying a no-op. Clock-window, direction, threshold,
count 1–5, count-setting reset and cancellation cases additionally have deterministic JVM tests in
`TerminalFastFlingTest`, alongside the existing reducer/controller regression suite.

## Important limits — do not enable production LazyColumn based on this test alone

Rows have **explicit 64px boxes** in this fixture, making the row renderer deterministic. Because
every fixture item has a fixed measured height, the fixture also knows its synthetic total range
while the tail is off-screen; the production tracker still reports an unknown range until it sees
the real tail. This does NOT validate variable ANSI/CJK/fallback-font heights, production anchor restoration, font changes,
IME avoidance, mouse-cell coordinates, renderer transitions, or off-screen selection/copy. The small
per-row SelectionContainers here test the input-mode gate, not cross-item text selection fidelity.

The earlier runtime draft's `rowCount * cellHeight` approximation was removed: the A/B measurements
already showed styled updates can alter the true scroll range. No such approximate adapter or
history-size auto-enable threshold is present in `ProcessSessionPage`. Production still uses its
fully eager ScrollState renderer (ordinary history in archival chunks), pixel metrics and single
scroll-effect executor. Only gesture ownership has
been extracted so this fixture cannot silently test a different fast-fling implementation.

## Run

On an authorized SDK host/device (or the `Terminal Viewport Interaction Tests` GitHub workflow):

```bash
./gradlew -PterminalViewportTests=true \
  :benchmarks:terminal-viewport-tests:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.viewporttest.TerminalViewportGestureInstrumentedTest

python3 .github/scripts/report-terminal-tests.py \
  benchmarks/terminal-viewport-tests/build/outputs/androidTest-results \
  --require-suite me.rerere.rikkahub.viewporttest.TerminalViewportGestureInstrumentedTest \
  --require-case-group 'lazyHistory=false:12' --require-case-group 'lazyHistory=true:12'
```

Do not merge these debug correctness results with the 30-case Macrobenchmark A/B JSON. There are no
performance acceptance thresholds here. APKs, JUnit XML and logcat are retained for 14 days by CI.


## Natural transcript geometry regression

`TerminalTranscriptGeometryInstrumentedTest` uses the actual production Text and shared transcript,
**without** fixed row boxes. Four cases run with chunk layers off and on (8 cases):

- all ANSI/CJK rows, their un-clipped positions and natural widths/heights, plus both scroll ranges;
- partial-head and whole-head-bucket removal, append/style updates and actual measured tail;
- a single production-shaped SelectionContainer, font-scale/size changes, viewport contraction,
  terminal resize, RTL alignment and horizontal/vertical offsets;
- TUI fallback, alternate-screen entry/exit, history clear and return to chunked history.

Each case compares flat vs chunked layouts on the same frame and checks actual composition counts.
CI requires these 8 **in addition to** the 24 pointer cases. They run in a separate instrumentation
invocation with the same 180-second watchdog, so a failure cannot erase the completed gesture results.
This is geometry evidence only: it does not inject a real IME, drag selection handles across chunks,
verify clipboard contents, measure production throughput or enable production drawing layers.

To run just the geometry cases on an authorized SDK host/device:

```bash
./gradlew -PterminalViewportTests=true \
  :benchmarks:terminal-viewport-tests:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.viewporttest.TerminalTranscriptGeometryInstrumentedTest
```
