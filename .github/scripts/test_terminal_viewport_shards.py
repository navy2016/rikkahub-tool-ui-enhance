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
        bundle = source.split('if [ "$group" = all ] || [ "$group" = bundle ]; then\n', 1)[1].split('\nfi', 1)[0]
        tui = source.split('if [ "$group" = all ] || [ "$group" = tuiDisplay ]; then\n', 1)[1].split('\nfi', 1)[0]
        bottom = source.split('if [ "$group" = all ] || [ "$group" = bottomVisibility ]; then\n', 1)[1].split('\nfi', 1)[0]
        history = source.split('if [ "$group" = all ] || [ "$group" = tuiHistory ]; then\n', 1)[1].split('\nfi', 1)[0]
        full_virtual = source.split('if [ "$group" = all ] || [ "$group" = fullVirtual ]; then\n', 1)[1].split('\nfi', 1)[0]
        assignments = re.findall(r'^run_arm (\S+)(?: (\w+))?', '\n'.join((geometry, interaction, bundle, tui, bottom, history, full_virtual)), re.M)
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
        self.assertIn('["geometry","interaction","bundle","tuiDisplay","bottomVisibility","tuiHistory","fullVirtual"]', workflow)
        self.assertIn('TERMINAL_VIEWPORT_GROUP: ${{ matrix.group }}', workflow)
        self.assertIn('${{ matrix.group }}-${{ github.run_attempt }}', workflow)
        self.assertIn('run-terminal-with-timeout.py 900', workflow)
        # Full-suite branches only; focused entries may repeat names or choose individual methods.
        full = workflow.split('- name: Require every case assigned to this shard', 1)[1]
        full = re.sub(r'          if \[ "\$TERMINAL_VIEWPORT_GROUP" = bottomControl \]; then.*?\n          fi', '', full, flags=re.S)
        # Focused suite entries overlap the all-suite inventory; omit only these explicit groups.
        for suite in ('chunkViews', 'textMetrics', 'renderSnapshot', 'defaultBottom', 'fullVirtualSelection'):
            start = full.find('          if [ "$TERMINAL_VIEWPORT_SUITE" = ' + suite + ' ]; then')
            if start >= 0:
                end = full.index('\n          fi', start) + len('\n          fi')
                full = full[:start] + full[end:]
        tokens = set(re.findall(r"--require-case-group '([^']+)'", full))
        self.assertEqual(180, sum(int(token.rsplit(':', 1)[1]) for token in tokens))
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

    def test_chunk_views_shards_are_disjoint_complete_and_keep_case_timeouts(self):
        source = (ROOT / 'benchmarks/terminal-viewport-tests/run-ci.sh').read_text()
        entry = source.split('" = chunkViews ]; then\n', 1)[1].split('\nfi', 1)[0]
        assigned = re.findall(r'^      run_arm (\S+) (\S+) (\d+)', entry, re.M)
        self.assertEqual(11, len(assigned))
        self.assertEqual(11, len({arm for arm, _, _ in assigned}))
        self.assertTrue(all(0 < int(seconds) <= 360 for _, _, seconds in assigned))
        methods = {name.split('#')[1] for _, name, _ in assigned if '#' in name}
        self.assertEqual(5, len(methods))
        for _, name, _ in assigned:
            klass, _, method = name.partition('#')
            body = (TESTS / (klass + '.kt')).read_text()
            if method:
                self.assertIn('fun ' + method + '(', body)
        workflow = (ROOT / '.github/workflows/terminal-viewport-interactions.yml').read_text()
        self.assertIn('["chunksRender","chunksViewport"]', workflow)
        reporting = workflow.split('- name: Require every case assigned to this shard', 1)[1]
        checks = reporting.split('if [ "$TERMINAL_VIEWPORT_SUITE" = chunkViews ]; then', 1)[1]
        checks = checks.split('if [ "$TERMINAL_VIEWPORT_SUITE" = textMetrics ]; then', 1)[0]
        tokens = re.findall(r"--require-case-group '([^']+)'", checks)
        self.assertEqual(44, sum(int(token.rsplit(':', 1)[1]) for token in tokens))
        self.assertTrue(all(method + ':1' in tokens for method in methods))
        required = set(re.findall(r'--require-suite me\.rerere\.rikkahub\.viewporttest\.(\w+)', checks))
        self.assertEqual({name.split('#')[0] for _, name, _ in assigned}, required)
        unit = (ROOT / '.github/workflows/terminal-emulator-tests.yml').read_text()
        focused = unit.split('- name: Run chunk view identity and row publication contracts once', 1)[1]
        focused = focused.split('- name:', 1)[0]
        self.assertEqual(5, focused.count('--tests '))
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
