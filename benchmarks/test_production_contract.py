import re
import unittest
from pathlib import Path

from production_summary import MODES, SCENARIOS, SIZES, VERSION


ROOT = Path(__file__).resolve().parent
TARGET = ROOT / 'terminal-target/src/main/java/me/rerere/rikkahub/benchmark/ProductionTerminalBenchmarkActivity.kt'


class ProductionContractTest(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
