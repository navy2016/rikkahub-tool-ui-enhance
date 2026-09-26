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
a completed effect; the coalesced frame observer is not used as a scroll-completion acknowledgement. Clock-window, direction, threshold,
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
original eager renderer, pixel metrics and single scroll-effect executor. Only gesture ownership has
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
