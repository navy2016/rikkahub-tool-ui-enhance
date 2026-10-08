import re
import unittest
from pathlib import Path

from production_summary import MODES, SCENARIOS, SIZES, VERSION


ROOT = Path(__file__).resolve().parent
TARGET = ROOT / 'terminal-target/src/main/java/me/rerere/rikkahub/benchmark/ProductionTerminalBenchmarkActivity.kt'


class ProductionContractTest(unittest.TestCase):
    def test_height_work_checks_boundaries_and_is_exported_without_driving_layout(self):
        source = TARGET.read_text()
        self.assertIn('check(historyReads == 0L)', source)
        self.assertIn('check(historyReads in 1L..512L)', source)
        self.assertIn('HEIGHT_WORK scenario=$scenario', source)
        self.assertIn('directoryVisits=${heights.eagerVisitedHistoryBlocks - heightDirectoryBefore}', source)
        self.assertNotIn('.readEager(', source)
        evidence = (ROOT.parent / '.github/workflows/terminal-production-evidence.yml').read_text()
        for marker in (' IME_WORK ', ' WIDTH_WORK ', ' HEIGHT_WORK '):
            self.assertIn(marker, evidence)

    def test_default_completion_requires_real_geometry_without_nominal_cell_oracle(self):
        source = TARGET.read_text()
        self.assertIn('check(viewport.bound.eagerMeasurement != null)', source)
        self.assertIn('if (bound.eagerMeasurement == null) return false', source)
        self.assertIn('val geometry = measurements.peekEager(bound.pass) ?: return false', source)
        self.assertNotIn('terminalImeAnchorScrollTarget', source)
        self.assertNotIn('captureViewportAnchor', source)
        self.assertIn('check(!viewport.bound.binding.widthIndex.hasRetainedState)', source)

    def test_screen_width_work_is_checked_without_populating_the_cache_from_the_harness(self):
        source = TARGET.read_text()
        self.assertIn('check(measured == updates)', source)
        self.assertIn('check(measured in updates..updates * 2)', source)
        self.assertIn('widths.reusedScreenRows - reusedBefore >= updates * (TerminalBenchmarkWorkload.SCREEN_ROWS - 2)', source)
        self.assertIn('widthIndex.measuredScreenRows - measuredScreen == ProductionBenchmarkSpec.IME_UPDATE_COUNT.toLong()', source)
        self.assertIn('if (!expected) check(viewport.bound.binding.widthIndex.retainedScreenRows == 0)', source)
        self.assertNotIn('widthIndex.width(', source)
        self.assertIn('WIDTH_WORK scenario=$scenario', source)

    def test_stable_ime_uses_production_policy_and_checks_bounded_nodes_without_eager_history(self):
        source = TARGET.read_text()
        self.assertIn('val wants = mode.isVirtualHistory', source)
        self.assertIn('policy.allows(chunks.isNotEmpty(), true, false, false, ime, avoidIme = ime)', source)
        self.assertIn('check(viewport.bound.virtualHistoryEnabled == stableIme)', source)
        self.assertIn('check(measurements.eagerHistoryBuildCount == 0L)', source)
        self.assertIn('check(measurements.createdRowNodes - nodesBefore < 256)', source)
        self.assertIn('viewport.policy.reapplied(false)', source)

    def test_harness_never_drives_backend_scroll_or_populates_eager_geometry(self):
        source = TARGET.read_text()
        for call in ('.scrollTo(', '.animateScrollTo(', '.scrollToItem(', '.animateScrollToItem(',
                     '.requestScrollToItem(', '.updateViewport(', '.restoreItemAnchor(', '.eagerGeometry('):
            self.assertNotIn(call, source)
        self.assertIn('measurements.peekEager(bound.pass)', source)
        self.assertIn('rememberTerminalBoundViewport(', source)
        self.assertIn('TerminalTranscriptViewport(frame, rows.toList()', source)

    def test_remount_callers_do_not_hold_the_detached_viewport(self):
        source = TARGET.read_text()
        dispatch = source.split('private suspend fun runScenario()', 1)[1].split('private suspend fun imeRoundTrip', 1)[0]
        self.assertLess(dispatch.index('detachRestore()'), dispatch.index('val viewport = checkNotNull(current)'))
        self.assertIn('return@productionAsyncTrace', dispatch[:dispatch.index('val viewport =')])
        detach = source.split('private suspend fun detachAndCapture(): TerminalViewportState', 1)[1].split(
            'private suspend fun detachRestore()', 1)[0]
        self.assertIn('current = null', detach)
        self.assertIn('return saved', detach)
        restore = source.split('private suspend fun detachRestore()', 1)[1].split('private suspend fun awaitCondition', 1)[0]
        self.assertIn('val saved = detachAndCapture()', restore)
        self.assertNotIn('val viewport', restore)
        self.assertNotIn('System.gc', source)

    def test_kotlin_and_report_contracts_stay_in_sync(self):
        source = (ROOT / 'production-contract/src/main/kotlin/me/rerere/rikkahub/benchmark/ProductionBenchmarkSpec.kt').read_text()
        self.assertIn(f'const val VERSION = "{VERSION}"', source)
        for name, expected in (('scenarios', SCENARIOS), ('modes', MODES)):
            body = re.search(r'val ' + name + r' = listOf\((.*?)\)', source, re.S)[1]
            self.assertEqual(list(expected), re.findall(r'"([^"]+)"', body))
        body = re.search(r'val sizes = listOf\((.*?)\)', source, re.S)[1]
        self.assertEqual(list(SIZES), [int(number.replace('_', '')) for number in body.split(',')])
        driver = (ROOT / 'terminal-macrobenchmark/src/main/java/me/rerere/rikkahub/benchmark/ProductionTerminalBenchmark.kt').read_text()
        self.assertIn('compilationMode = CompilationMode.Full()', driver)
        self.assertNotIn('CompilationMode.None', driver)

    def test_completion_uses_launch_scoped_receipts_after_actual_validation(self):
        source = TARGET.read_text()
        driver = (ROOT / 'terminal-macrobenchmark/src/main/java/me/rerere/rikkahub/benchmark/ProductionTerminalBenchmark.kt').read_text()
        self.assertIn('withTimeout(120_000) { block() }\n                publishPhase(done)', source)
        self.assertIn('awaitSettled(viewport)', source)
        self.assertIn('val token = UUID.randomUUID().toString()', driver)
        self.assertIn('val receipt = ProductionBenchmarkProgress(token)', driver)
        self.assertIn('receipt.accept(', driver)
        self.assertIn('progress.await(phase, 150_000)', driver)
        self.assertNotIn('benchmark_status', driver)
        self.assertIn('closePhaseReceiver()', driver)
        self.assertIn('ProductionBenchmarkSpec.PHASE_PERMISSION, handler', driver)
        self.assertIn('.setPackage(ProductionBenchmarkSpec.DRIVER_PACKAGE)', source)
        self.assertNotIn('ResultReceiver', source + driver)  # am start drops Parcelable extras.
        manifest = (ROOT / 'terminal-target/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android:protectionLevel="signature"', manifest)


if __name__ == '__main__':
    unittest.main()
