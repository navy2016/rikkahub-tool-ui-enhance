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
collect_diagnostics() {
  result=$?
  adb logcat -d > "$output/logcat.txt" || true
  adb shell dumpsys meminfo me.rerere.rikkahub.terminalbenchmark > "$output/meminfo.txt" || true
  if [ "$result" != 0 ]; then
    python3 - <<'PYERROR'
from pathlib import Path
import xml.etree.ElementTree as ET
messages = []
for path in Path("benchmarks/terminal-macrobenchmark/build/outputs").rglob("TEST-*.xml"):
    for failure in ET.parse(path).getroot().iter("failure"):
        messages.append((failure.get("message", "") + "\n" + (failure.text or ""))[:2000])
lines = Path("artifacts/terminal-scrollback/logcat.txt").read_text().splitlines()
for index, line in enumerate(lines):
    if "FATAL EXCEPTION" in line:
        messages.append("\n".join(lines[index:index + 28]))
message = "\n\n".join(messages)[:6000] or "See instrumentation reports and logcat in the artifact"
print("::error title=Benchmark instrumentation failed::" + message.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D"))
PYERROR
  fi
}
trap collect_diagnostics EXIT
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
