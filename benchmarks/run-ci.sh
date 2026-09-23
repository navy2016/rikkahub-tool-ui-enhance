#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
iterations="${TERMINAL_BENCHMARK_ITERATIONS:-3}"
[[ "$iterations" =~ ^[0-9]+$ ]] && (( iterations >= 1 && iterations <= 50 )) || {
  echo 'TERMINAL_BENCHMARK_ITERATIONS must be an integer in 1..50' >&2
  exit 1
}
output=artifacts/terminal-scrollback
mkdir -p "$output"
trap 'adb logcat -d > "$output/logcat.txt"; adb shell dumpsys meminfo me.rerere.rikkahub.terminalbenchmark > "$output/meminfo.txt" || true' EXIT
adb logcat -c
{
  printf 'sha=%s\niterations=%s\nenvironment=ci-emulator\n' "${GITHUB_SHA:-unknown}" "$iterations"
  adb shell getprop ro.build.fingerprint
  adb shell wm size
  adb shell wm density
  adb shell getprop dalvik.vm.heapsize
  sha256sum app/src/main/java/me/rerere/rikkahub/{utils/TerminalEmulator.kt,data/container/TerminalViewportReducer.kt,ui/pages/container/TerminalRenderedRows.kt,ui/theme/Type.kt} app/src/main/res/font/jetbrains_mono.ttf
} > "$output/environment.txt"
./gradlew -PterminalBenchmarks=true :benchmarks:terminal-macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.rerere.rikkahub.benchmark.TerminalScrollbackBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.terminalIterations="$iterations" \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR \
  --stacktrace
