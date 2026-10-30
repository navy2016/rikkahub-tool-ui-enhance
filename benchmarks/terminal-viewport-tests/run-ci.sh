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
      -Pandroid.testInstrumentationRunnerArguments.bottomVisibilityControl="${TERMINAL_BOTTOM_CONTROL:-false}" \
      --stacktrace || arm_status=$?

  find "$results_root" -mindepth 1 -maxdepth 1 ! -name 'viewport-*' -exec cp -a {} "$stage"/ \;
  find "$results_root" -mindepth 1 -maxdepth 1 ! -name 'viewport-*' -exec rm -rf {} +

  if [ "$arm_status" -ne 0 ]; then
    overall_status="$arm_status"
  fi
}

if [ "${TERMINAL_VIEWPORT_SUITE:-all}" = chunkViews ]; then
  case "${TERMINAL_VIEWPORT_GROUP:-}" in
    chunksRender)
      run_arm chunk-views TerminalChunkViewsInstrumentedTest 360
      adb logcat -d -v threadtime TerminalChunkViews:I '*:S' > artifacts/terminal-validation/chunk-views-logcat.txt
      run_arm geometry TerminalTranscriptGeometryInstrumentedTest 180
      run_arm text-compatibility TerminalTextCompatibilityInstrumentedTest 180
      run_arm measurement-node TerminalRowMeasurementInstrumentedTest 180
      ;;
    chunksViewport)
      run_arm default-bottom TerminalDefaultBottomInstrumentedTest 240
      run_arm ime-stable TerminalImeVirtualHistoryInstrumentedTest 240
      run_arm chunk-fifo TerminalViewportStabilityInstrumentedTest#stabilityDefaultTenThousandFifoUpdatesReadOnlyBoundaryHeightsAndKeepLockedAnchor 180
      run_arm chunk-restore TerminalViewportStabilityInstrumentedTest#stabilityDetachAndRecreateRestoresMeasuredAnchorAfterBackgroundTrim 120
      run_arm chunk-prefix TerminalViewportStabilityInstrumentedTest#stabilityTenThousandRowFallbackSharesHistoryAndReleasesPrefixOnReturn 180
      run_arm chunk-modes TerminalViewportStabilityInstrumentedTest#stabilityOutputDuringRepeatedModeAndImeFallbackChangesSettlesAtLatestTail 180
      run_arm chunk-active TerminalItemViewportInstrumentedTest#itemNativeEagerFallbackDoesNotRecomposeArchivedHistoryOnActiveUpdates 120
      ;;
    *) echo 'chunkViews requires its explicit render/viewport shard' >&2; exit 2 ;;
  esac
  exit "$overall_status"
fi

if [ "${TERMINAL_VIEWPORT_SUITE:-all}" = textMetrics ]; then
  run_arm text-metrics TerminalTextMetricsInstrumentedTest 240
  adb logcat -d -v threadtime TerminalTextMetrics:I '*:S' > artifacts/terminal-validation/text-metrics-logcat.txt
  run_arm intrinsic TerminalIntrinsicWidthInstrumentedTest 240
  adb logcat -d -v threadtime TerminalRowWidth:I '*:S' > artifacts/terminal-validation/row-width-logcat.txt
  run_arm measurement-node TerminalRowMeasurementInstrumentedTest 180
  run_arm binding-projection TerminalBindingProjectionInstrumentedTest 180
  run_arm default-bottom TerminalDefaultBottomInstrumentedTest 240
  run_arm ime-stable TerminalImeVirtualHistoryInstrumentedTest 240
  exit "$overall_status"
fi

if [ "${TERMINAL_VIEWPORT_SUITE:-all}" = renderSnapshot ]; then
  run_arm render-snapshot TerminalRenderSnapshotInstrumentedTest 240
  adb logcat -d -v threadtime TerminalRenderSnapshot:I '*:S' > artifacts/terminal-validation/render-snapshot-logcat.txt
  run_arm binding-projection TerminalBindingProjectionInstrumentedTest 180
  run_arm default-bottom TerminalDefaultBottomInstrumentedTest 240
  run_arm ime-stable TerminalImeVirtualHistoryInstrumentedTest 240
  run_arm stability TerminalViewportStabilityInstrumentedTest 240
  exit "$overall_status"
fi

if [ "${TERMINAL_VIEWPORT_SUITE:-all}" = defaultBottom ]; then
  run_arm default-bottom TerminalDefaultBottomInstrumentedTest 240
  exit "$overall_status"
fi

group="${TERMINAL_VIEWPORT_GROUP:-all}"
if [ "$group" = selectionRepair ]; then
  mkdir -p artifacts/terminal-selection-repair
  adb logcat -G 8M
  # Original correctness methods, byte-for-byte unchanged; never invoke its performance method.
  run_arm repair-scenes TerminalFullVirtualInstrumentedTest#fullVirtualAllScenesRemainVirtualWithRealGeometry 240
  run_arm repair-anchors TerminalFullVirtualInstrumentedTest#fullVirtualLockedFontsRtlImeAndReentryPreserveTheStableAnchor 180
  run_arm repair-copy TerminalFullVirtualInstrumentedTest#fullVirtualSelectionSystemMenuCopiesAWordAndOffscreenSelectAll 240
  run_arm repair-drag TerminalFullVirtualInstrumentedTest#fullVirtualSelectionHandleCrossesViewportWithoutLosingOffscreenText 240
  run_arm repair-mouse TerminalFullVirtualInstrumentedTest#fullVirtualMouseMappingIncludesOffscreenPhysicalOrigin 120
  run_arm repair-handoff TerminalFullVirtualInstrumentedTest#fullVirtualModeSwitchKeepsOldChoicesAndMeasuredReadPosition 180
  run_arm repair-selection-regressions TerminalSelectionAcceptanceTest 360
  adb logcat -d -v threadtime TerminalFullVirtual:I TerminalSelectionAcceptance:I '*:S' > artifacts/terminal-selection-repair/repair-logcat.txt
  exit "$overall_status"
fi
if [ "${TERMINAL_VIEWPORT_SUITE:-all}" = selectionDiagnostic ]; then
  [ "$group" = selectionDiagnostic ]
  mkdir -p artifacts/terminal-selection-repair
  adb logcat -G 8M
  run_arm selection-diagnostic TerminalSelectionRepairTest 850
  adb logcat -d -v threadtime TerminalSelectionTrace:I '*:S' > artifacts/terminal-selection-repair/diagnostic-logcat.txt
  exit "$overall_status"
fi
if [ "${TERMINAL_VIEWPORT_SUITE:-all}" = fullVirtualSelection ]; then
  [ "$group" = fullVirtualSelection ]
  run_arm selection-copy TerminalFullVirtualInstrumentedTest#fullVirtualSelectionSystemMenuCopiesAWordAndOffscreenSelectAll 240
  run_arm selection-drag TerminalFullVirtualInstrumentedTest#fullVirtualSelectionHandleCrossesViewportWithoutLosingOffscreenText 240
  adb logcat -d -v threadtime TerminalFullVirtual:I '*:S' > artifacts/terminal-validation/full-virtual-selection-logcat.txt
  exit "$overall_status"
fi
if [ "$group" = bottomControl ]; then
  run_arm bottom-control-cursor TerminalBottomVisibilityInstrumentedTest#bottomVisibilityBlankCursorIsEntirelyVisible 180
  run_arm bottom-control-history TerminalBottomVisibilityInstrumentedTest#bottomVisibilityTuiHistoryGrowthNeverAccumulatesClipping 300
  adb logcat -d -v threadtime TerminalBottomVisibility:I '*:S' > artifacts/terminal-validation/bottom-visibility-logcat.txt
  exit "$overall_status"
fi
case "$group" in all|geometry|interaction|bundle|tuiDisplay|bottomVisibility|tuiHistory|fullVirtual) ;; *) echo "Unknown viewport group: $group" >&2; exit 2 ;; esac

if [ "$group" = all ] || [ "$group" = geometry ]; then
run_arm chunk-views TerminalChunkViewsInstrumentedTest 360
adb logcat -d -v threadtime TerminalChunkViews:I '*:S' > artifacts/terminal-validation/chunk-views-logcat.txt
run_arm text-metrics TerminalTextMetricsInstrumentedTest 240
adb logcat -d -v threadtime TerminalTextMetrics:I '*:S' > artifacts/terminal-validation/text-metrics-logcat.txt
run_arm false
run_arm true
run_arm geometry TerminalTranscriptGeometryInstrumentedTest
run_arm natural-layout TerminalLazyNaturalGeometryInstrumentedTest
run_arm natural-intrinsic TerminalLazyNaturalGeometryInstrumentedTest
run_arm intrinsic TerminalIntrinsicWidthInstrumentedTest 240
# Preserve this early suite's fixed scalar evidence before later tests fill the logcat ring.
adb logcat -d -v threadtime TerminalRowWidth:I '*:S' > artifacts/terminal-validation/row-width-logcat.txt
run_arm render-mode TerminalRenderModeInstrumentedTest
run_arm measurement-node TerminalRowMeasurementInstrumentedTest
run_arm text-compatibility TerminalTextCompatibilityInstrumentedTest
fi

if [ "$group" = all ] || [ "$group" = interaction ]; then
run_arm item-viewport TerminalItemViewportInstrumentedTest
run_arm ime-stable TerminalImeVirtualHistoryInstrumentedTest 240
run_arm default-bottom TerminalDefaultBottomInstrumentedTest 240
run_arm stability TerminalViewportStabilityInstrumentedTest 240
run_arm scroll-completion TerminalScrollCompletionInstrumentedTest 180
run_arm panel-subscription TerminalPanelSubscriptionInstrumentedTest 120
run_arm binding-projection TerminalBindingProjectionInstrumentedTest 180
run_arm render-snapshot TerminalRenderSnapshotInstrumentedTest 240
adb logcat -d -v threadtime TerminalRenderSnapshot:I '*:S' > artifacts/terminal-validation/render-snapshot-logcat.txt
fi
if [ "$group" = all ] || [ "$group" = bundle ]; then
run_arm bundle TerminalBundleInstrumentedTest 600
fi
if [ "$group" = all ] || [ "$group" = tuiDisplay ]; then
run_arm tui-display TerminalTuiDisplayInstrumentedTest 480
adb logcat -d -v threadtime TerminalTuiDisplay:I '*:S' > artifacts/terminal-validation/tui-display-logcat.txt
fi
if [ "$group" = all ] || [ "$group" = bottomVisibility ]; then
run_arm bottom-visibility TerminalBottomVisibilityInstrumentedTest 600
adb logcat -d -v threadtime TerminalBottomVisibility:I '*:S' > artifacts/terminal-validation/bottom-visibility-logcat.txt
fi
if [ "$group" = all ] || [ "$group" = tuiHistory ]; then
run_arm tui-history TerminalTuiHistoryInstrumentedTest 600
adb logcat -d -v threadtime TerminalTuiHistory:I '*:S' > artifacts/terminal-validation/tui-history-logcat.txt
fi
if [ "$group" = all ] || [ "$group" = fullVirtual ]; then
run_arm full-virtual TerminalFullVirtualInstrumentedTest 850
adb logcat -d -v threadtime TerminalFullVirtual:I '*:S' > artifacts/terminal-validation/full-virtual-logcat.txt
fi
exit "$overall_status"
