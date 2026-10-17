"""Synthetic row-scope evidence only; no Android/Kotlin compilation or guest execution."""
import copy
import importlib.util
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('row_scope_report',
    Path(__file__).with_name('report-terminal-row-scope.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


def samples():
    result = []
    for size in report.SIZES:
        sequence = 0
        for phase, pairs in (('warmup', 1), ('measurement', 4)):
            for pair in range(pairs):
                arms = ('legacy', 'single') if pair % 2 == 0 else ('single', 'legacy')
                for position, arm in enumerate(arms):
                    result.append(dict(size=size, phase=phase, pair=pair, position=position, arm=arm,
                        sequence=sequence, nodes=size + 24, retainedRows=size + 24, disposedRows=0,
                        historyChunks=(size + 127) // 128, elapsedNanos=100_000_000,
                        rowCommits=size + 24, heightPx=(size + 24) * 20, draws=3,
                        heightSha256='b' * 64))
                    sequence += 1
    return result


def devices():
    return [dict(size=size, pid=1234, fingerprint='fixture', sdk=34, density=1, fontScale=1,
                 targetPackage='me.rerere.rikkahub.terminalviewporttest',
                 testPackage='me.rerere.rikkahub.terminalviewporttest.test',
                 targetApkSha256='a' * 64, testApkSha256='b' * 64) for size in report.SIZES]


class RowScopeReportTest(unittest.TestCase):
    def test_complete_matrix_keeps_all_measured_pairs_and_excludes_warmup(self):
        records = samples()
        for row in records:
            if row['phase'] == 'warmup':
                row['elapsedNanos'] = 10_000_000_000
        self.assertEqual(records, report.validate(records))
        summary = report.summary(records)
        for size in report.SIZES:
            self.assertEqual(100, summary[size]['single']['medianMs'])
            self.assertEqual([0, 0, 0, 0], summary[size]['pairedDifferenceMs'])

    def test_measurement_can_be_slower_without_being_filtered(self):
        records = samples()
        for row in records:
            if row['arm'] == 'single':
                row['elapsedNanos'] *= 2
        report.validate(records)
        self.assertEqual([100] * 4, report.summary(records)[10000]['pairedDifferenceMs'])

    def test_partial_duplicate_reordered_or_cross_size_samples_are_rejected(self):
        records = samples()
        bad = [records[:-1], records + records[:1], list(reversed(records)), None, []]
        changed = copy.deepcopy(records)
        changed[0]['size'] = 5000
        bad.append(changed)
        for value in bad:
            with self.subTest(value_type=type(value)), self.assertRaises(ValueError):
                report.validate(value)

    def test_wrong_geometry_missing_nodes_or_nonfinite_metrics_fail(self):
        for name, value in (('nodes', 0), ('retainedRows', 0), ('disposedRows', 1), ('rowCommits', 0),
                            ('historyChunks', 0), ('draws', 2), ('heightSha256', 'a' * 64),
                            ('heightPx', 1), ('elapsedNanos', float('nan')), ('elapsedNanos', True),
                            ('phase', 'measurement'), ('pair', True), ('extra', 1)):
            records = samples()
            records[0][name] = value
            with self.subTest(name=name), self.assertRaises(ValueError):
                report.validate(records)

    def test_devices_bind_single_process_and_exact_test_packages(self):
        self.assertEqual(devices(), report.validate_devices(devices()))
        for value in ([], devices()[:-1], devices() + devices()[:1], [None] * 3):
            with self.assertRaises(ValueError):
                report.validate_devices(value)
        for key, value in (('pid', 1235), ('sdk', 33), ('size', True), ('fingerprint', 'other'),
                           ('density', 2), ('fontScale', 1.0), ('targetPackage', 'other'),
                           ('testPackage', 'other'), ('testApkSha256', 'c' * 64), ('targetApkSha256', None)):
            values = devices()
            values[1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                report.validate_devices(values)

    def test_compiler_reports_require_five_exact_scope_decisions(self):
        headers = [(task, ('restartable skippable ' if restartable else '') +
                    'scheme("[androidx.compose.ui.UiComposable]") fun ' + qualified + '(')
                   for qualified, restartable, task in report.SCOPE_NAMES.values()]
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            def write(values):
                for task in {value[2] for value in report.SCOPE_NAMES.values()}:
                    path = root / task / 'fixture-composables.txt'
                    path.parent.mkdir(exist_ok=True)
                    path.write_text('\n'.join(text for selected, text in values if selected == task))
            write(headers)
            result = report.compiler_scopes(root)
            self.assertFalse(result['TerminalRowText']['restartable'])
            self.assertTrue(result['Row']['restartable'])
            self.assertTrue(all(re.fullmatch('[a-f0-9]{64}', row['reportSha256']) for row in result.values()))
            bad_headers = [headers[:-1], headers + headers[:1],
                [(headers[0][0], 'restartable ' + headers[0][1])] + headers[1:],
                headers[:2] + [(headers[2][0], headers[2][1].replace('restartable ', ''))] + headers[3:],
                [(headers[3][0], headers[0][1])] + headers[1:],
                [(task, re.sub(r'fun [\w.]+\.', 'fun ', text)) for task, text in headers],
                [(task, text.replace('me.rerere.rikkahub.ui.pages.container.', 'unrelated.')) for task, text in headers]]
            for bad in bad_headers:
                write(bad)
                with self.assertRaises(ValueError):
                    report.compiler_scopes(root)

    def test_compilation_reports_are_separate_task_outputs_not_one_overwritten_extension_path(self):
        text = (report.ROOT / 'benchmarks/terminal-viewport-tests/build.gradle.kts').read_text()
        self.assertIn('tasks.withType<KotlinCompile>().configureEach', text)
        self.assertIn('layout.buildDirectory.dir("compose-reports/$name")', text)
        self.assertIn('outputs.dir(reports).withPropertyName("terminalComposeReports")', text)
        self.assertNotIn('reportsDestination.set(', text)

    def test_frozen_bodies_and_current_measurement_node_match_baseline(self):
        proof = report.source_proof()
        self.assertEqual('b7a93c9b5734ce1386d919d3be6085ef725124bb', proof['baselineSha'])
        self.assertTrue(proof['frozenBodiesVerified'])
        self.assertTrue(proof['measurementNodeUnchanged'])
        self.assertEqual(2, len(proof['baselineSources']))

    def test_junit_inventory_requires_all_six_cases_with_no_skips(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = root / 'TEST-scope.xml'
            cases = [f'<testcase classname="{report.CLASS}" name="{name}"/>' for name in sorted(report.CASES)]
            path.write_text('<testsuite>' + ''.join(cases) + '</testsuite>')
            self.assertEqual(sorted(report.CASES), report.common.require_junit(root, report.CLASS, report.CASES))
            for text in (''.join(cases[:-1]), ''.join(cases + cases[:1]),
                         ''.join(cases).replace('/>', '><skipped/></testcase>', 1)):
                path.write_text('<testsuite>' + text + '</testsuite>')
                with self.assertRaises(ValueError):
                    report.common.require_junit(root, report.CLASS, report.CASES)

    def test_built_apks_require_one_artifact_per_role(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(report.common, 'TEST_ROOT', Path(folder)):
            root = Path(folder)
            with self.assertRaises(ValueError):
                report.built_apks()
            for directory in ('release', 'androidTest/release'):
                path = root / 'build/outputs/apk' / directory / 'target.apk'
                path.parent.mkdir(parents=True)
                path.write_bytes(b'synthetic-not-an-apk')
            self.assertEqual({'fixture', 'tests'}, set(report.built_apks()))
            (root / 'build/outputs/apk/release/duplicate.apk').write_bytes(b'other')
            with self.assertRaises(ValueError):
                report.built_apks()

    def test_instrumented_case_names_and_production_restart_boundaries_match(self):
        source = (report.ROOT / report.TESTS / 'TerminalRowScopeInstrumentedTest.kt').read_text()
        self.assertEqual(report.CASES, set(re.findall(r'@Test\s+fun\s+(\w+)\(', source)))
        self.assertIn('apkHash(instrumentation.targetContext)', source)
        self.assertIn('apkHash(instrumentation.context)', source)
        self.assertNotIn('.scrollTo(', source)
        self.assertNotIn('.animateScrollTo(', source)
        rendered = (report.ROOT / report.MAIN / 'TerminalRenderedRows.kt').read_text()
        measured = (report.ROOT / report.MAIN / 'TerminalLazyItemMeasurements.kt').read_text()
        self.assertIn('@Composable\n@NonRestartableComposable\ninternal fun TerminalRowText(', rendered)
        self.assertIn('@Composable\ninternal fun TerminalRenderedRow(', rendered)
        self.assertNotIn('@NonRestartableComposable', measured)
        self.assertIn('TerminalRowText(text, style, rowModifier(metricKey, row.lineId, text))', measured)


if __name__ == '__main__':
    unittest.main()
