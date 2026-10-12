"""Keep bounded viewport shards exhaustive; never turn missing instrumentation into success."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TESTS = ROOT / 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest'


class ViewportShardTest(unittest.TestCase):
    def test_all_test_classes_are_assigned_once_except_explicit_parameter_arms(self):
        source = (ROOT / 'benchmarks/terminal-viewport-tests/run-ci.sh').read_text()
        geometry = source.split('if [ "$group" = all ] || [ "$group" = geometry ]; then\n', 1)[1].split('\nfi', 1)[0]
        interaction = source.split('if [ "$group" = all ] || [ "$group" = interaction ]; then\n', 1)[1].split('\nfi', 1)[0]
        assignments = re.findall(r'^run_arm (\S+)(?: (\w+))?', geometry + '\n' + interaction, re.M)
        classes = [name or 'TerminalViewportGestureInstrumentedTest' for _, name in assignments]
        expected = {p.stem for p in TESTS.glob('*InstrumentedTest.kt')}
        self.assertEqual(expected, set(classes))
        self.assertEqual(len(assignments), len({arm for arm, _ in assignments}))
        for name in expected:
            self.assertEqual(2 if name in {'TerminalViewportGestureInstrumentedTest',
                                         'TerminalLazyNaturalGeometryInstrumentedTest'} else 1, classes.count(name))
        workflow = (ROOT / '.github/workflows/terminal-viewport-interactions.yml').read_text()
        required = set(re.findall(r'--require-suite me\.rerere\.rikkahub\.viewporttest\.(\w+)', workflow))
        self.assertEqual(expected, required)

    def test_counts_matrix_and_report_routing_stay_complete(self):
        workflow = (ROOT / '.github/workflows/terminal-viewport-interactions.yml').read_text()
        self.assertIn('fail-fast: false', workflow)
        self.assertIn('["geometry","interaction"]', workflow)
        self.assertIn('TERMINAL_VIEWPORT_GROUP: ${{ matrix.group }}', workflow)
        self.assertIn('${{ matrix.group }}-${{ github.run_attempt }}', workflow)
        self.assertIn('run-terminal-with-timeout.py 900', workflow)
        tokens = set(re.findall(r"--require-case-group '([^']+)'", workflow))
        self.assertEqual(144, sum(int(token.rsplit(':', 1)[1]) for token in tokens))
        # Most groups use a shared method prefix; render controls retain two distinct names.
        methods = [name for path in TESTS.glob('*InstrumentedTest.kt')
                   for name in re.findall(r'@Test\s+fun\s+(\w+)\(', path.read_text())]
        for token in tokens:
            name, expected = token.rsplit(':', 1)
            if '=' not in name:  # Parameter labels are supplied by JUnit, not method names.
                self.assertEqual(int(expected), sum(name in method for method in methods), token)
        for script, group in (('report-terminal-scroll-completion.py', 'interaction'),
                              ('report-terminal-panel-subscription.py', 'interaction'),
                              ('report-terminal-row-width.py', 'geometry')):
            for prefix in workflow.split('run: python3 .github/scripts/' + script)[:-1]:
                self.assertIn("matrix.group == '" + group + "'", prefix.rsplit('- name:', 1)[1])

    def test_publication_entry_runs_only_its_five_classes_and_is_separate_from_static_checks(self):
        source = (ROOT / 'benchmarks/terminal-viewport-tests/run-ci.sh').read_text()
        entry = source.split('" = renderSnapshot ]; then\n', 1)[1].split('\nfi', 1)[0]
        classes = re.findall(r'^  run_arm \S+ (\w+)', entry, re.M)
        self.assertEqual(['TerminalRenderSnapshotInstrumentedTest', 'TerminalBindingProjectionInstrumentedTest',
                          'TerminalDefaultBottomInstrumentedTest', 'TerminalImeVirtualHistoryInstrumentedTest',
                          'TerminalViewportStabilityInstrumentedTest'], classes)
        self.assertNotIn('run_arm false', entry)
        self.assertIn('render-snapshot-logcat.txt', entry)
        workflow = (ROOT / '.github/workflows/terminal-viewport-interactions.yml').read_text()
        reporting = workflow.split('- name: Require every case assigned to this shard', 1)[1]
        checks = reporting.split('if [ "$TERMINAL_VIEWPORT_SUITE" = renderSnapshot ]; then', 1)[1].split('\n          fi', 1)[0]
        self.assertEqual(set(classes), set(re.findall(r'--require-suite me\.rerere\.rikkahub\.viewporttest\.(\w+)', checks)))
        self.assertIn(':benchmarks:terminal-viewport-tests:lintRelease', workflow)
        self.assertIn("if: ${{ matrix.group != 'staticChecks' }}", workflow)
        unit = (ROOT / '.github/workflows/terminal-emulator-tests.yml').read_text()
        focused = unit.split('- name: Run publication and consumer contracts once', 1)[1].split('- name:', 1)[0]
        self.assertEqual(4, len(re.findall(r'--tests ', focused)))
        self.assertNotIn('attempt', focused)
        self.assertNotIn('for ', focused)

    def test_text_metrics_entry_covers_only_its_six_related_suites_and_has_no_jvm_retries(self):
        script = (ROOT / 'benchmarks/terminal-viewport-tests/run-ci.sh').read_text()
        entry = script.split('" = textMetrics ]; then\n', 1)[1].split('\nfi', 1)[0]
        classes = re.findall(r'^  run_arm \S+ (\w+)', entry, re.M)
        self.assertEqual(['TerminalTextMetricsInstrumentedTest', 'TerminalIntrinsicWidthInstrumentedTest',
                          'TerminalRowMeasurementInstrumentedTest', 'TerminalBindingProjectionInstrumentedTest',
                          'TerminalDefaultBottomInstrumentedTest', 'TerminalImeVirtualHistoryInstrumentedTest'], classes)
        self.assertIn('text-metrics-logcat.txt', entry)
        self.assertIn('row-width-logcat.txt', entry)
        workflow = (ROOT / '.github/workflows/terminal-viewport-interactions.yml').read_text()
        self.assertNotIn('report-terminal-tests.py', workflow.split('- name: Test pointer gestures', 1)[0])
        reporting = workflow.split('- name: Require every case assigned to this shard', 1)[1]
        checks = reporting.split('if [ "$TERMINAL_VIEWPORT_SUITE" = textMetrics ]; then', 1)[1].split('\n          fi', 1)[0]
        self.assertEqual(set(classes), set(re.findall(r'--require-suite me\.rerere\.rikkahub\.viewporttest\.(\w+)', checks)))
        self.assertIn('report-terminal-text-metrics.py', workflow)
        unit = (ROOT / '.github/workflows/terminal-emulator-tests.yml').read_text()
        focused = unit.split('- name: Run text metric cache invalidation contracts once', 1)[1].split('- name:', 1)[0]
        self.assertEqual(2, focused.count('--tests '))
        self.assertNotIn('attempt', focused)


if __name__ == '__main__':
    unittest.main()
