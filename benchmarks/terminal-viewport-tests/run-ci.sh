#!/usr/bin/env bash
set -euo pipefail
mkdir -p artifacts/terminal-validation
trap 'status=$?; adb logcat -d -v threadtime > artifacts/terminal-validation/gesture-logcat.txt || true; exit "$status"' EXIT
adb wait-for-device
adb logcat -c
# Do not disable animations: the cancellation tests require a real in-flight jump.
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
adb shell settings put secure show_ime_with_hard_keyboard 1

results_root="benchmarks/terminal-viewport-tests/build/outputs/androidTest-results"
rm -rf "$results_root"
mkdir -p "$results_root"
overall_status=0

run_arm() {
  local arm="$1"
  local test_class="${2:-TerminalViewportGestureInstrumentedTest}"
  local timeout_seconds="${3:-180}"
  local arm_status=0
  local stage="$results_root/viewport-$arm"
  local log="artifacts/terminal-validation/instrumentation-$arm.log"

  # Keep each renderer's result tree independent. A stuck lazy arm must not erase the
  # completed eager arm or prevent the reporting step from seeing its 12 cases.
  find "$results_root" -mindepth 1 -maxdepth 1 ! -name 'viewport-*' -exec rm -rf {} +
  rm -rf "$stage"
  mkdir -p "$stage"

  TERMINAL_TIMEOUT_LOG_PATH="$log" \
    python3 .github/scripts/run-terminal-with-timeout.py "$timeout_seconds" \
    bash .github/scripts/run-terminal-gradle.sh -PterminalViewportTests=true \
      :benchmarks:terminal-viewport-tests:connectedReleaseAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class="me.rerere.rikkahub.viewporttest.$test_class" \
      -Pandroid.testInstrumentationRunnerArguments.viewportLazyHistory="$arm" \
      -Pandroid.testInstrumentationRunnerArguments.viewportWidthMode="$arm" \
      --stacktrace || arm_status=$?

  find "$results_root" -mindepth 1 -maxdepth 1 ! -name 'viewport-*' -exec cp -a {} "$stage"/ \;
  find "$results_root" -mindepth 1 -maxdepth 1 ! -name 'viewport-*' -exec rm -rf {} +

  if [ "$arm_status" -ne 0 ]; then
    overall_status="$arm_status"
  fi
}

if [ "${TERMINAL_VIEWPORT_SUITE:-all}" = defaultBottom ]; then
  run_arm default-bottom TerminalDefaultBottomInstrumentedTest 240
  exit "$overall_status"
fi

run_arm false
run_arm true
run_arm geometry TerminalTranscriptGeometryInstrumentedTest
run_arm natural-layout TerminalLazyNaturalGeometryInstrumentedTest
run_arm natural-intrinsic TerminalLazyNaturalGeometryInstrumentedTest
run_arm intrinsic TerminalIntrinsicWidthInstrumentedTest
run_arm render-mode TerminalRenderModeInstrumentedTest
run_arm measurement-node TerminalRowMeasurementInstrumentedTest
run_arm text-compatibility TerminalTextCompatibilityInstrumentedTest
run_arm item-viewport TerminalItemViewportInstrumentedTest
run_arm ime-stable TerminalImeVirtualHistoryInstrumentedTest 240
run_arm default-bottom TerminalDefaultBottomInstrumentedTest 240
run_arm stability TerminalViewportStabilityInstrumentedTest 240
exit "$overall_status"
