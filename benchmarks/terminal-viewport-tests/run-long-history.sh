set -euo pipefail
case "${TERMINAL_LONG_HISTORY_PROBE:-false}" in true|false) ;; *) exit 2 ;; esac
mkdir -p artifacts/terminal-long-history
trap 'status=$?; adb logcat -d -v threadtime TerminalLongHistory:I AndroidRuntime:E TestRunner:I "*:S" > artifacts/terminal-long-history/diagnostic-logcat.txt || true; exit "$status"' EXIT
adb wait-for-device
adb logcat -c
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
TERMINAL_TIMEOUT_LOG_PATH=artifacts/terminal-long-history/instrumentation.log \
  python3 .github/scripts/run-terminal-with-timeout.py 840 \
  bash .github/scripts/run-terminal-gradle.sh -PterminalViewportTests=true \
    :benchmarks:terminal-viewport-tests:connectedReleaseAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.viewporttest.TerminalLongHistoryDiagnosticTest \
    -Pandroid.testInstrumentationRunnerArguments.longHistoryProbe="${TERMINAL_LONG_HISTORY_PROBE:-false}" \
    --stacktrace
