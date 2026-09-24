#!/usr/bin/env bash
set -euo pipefail
mkdir -p artifacts/terminal-validation
adb logcat -c
# Do not disable animations: the cancellation tests require a real in-flight jump.
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
trap 'adb logcat -d -v threadtime > artifacts/terminal-validation/gesture-logcat.txt || true' EXIT
bash .github/scripts/run-terminal-gradle.sh -PterminalViewportTests=true \
  :benchmarks:terminal-viewport-tests:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.viewporttest.TerminalViewportGestureInstrumentedTest \
  --stacktrace
