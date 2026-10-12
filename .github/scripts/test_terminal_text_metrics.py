import importlib.util
import re
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'metrics_report', Path(__file__).with_name('report-terminal-text-metrics.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


class TextMetricsReportTest(unittest.TestCase):
    def test_exact_queries_and_identity_counts_are_accepted(self):
        self.assertEqual(report.EXPECTED, report.validate([dict(report.EXPECTED)]))

    def test_missing_duplicate_or_invalid_work_is_rejected(self):
        for records in ([], [report.EXPECTED, report.EXPECTED], [None], [{}], [dict(report.EXPECTED, extra=1)]):
            with self.subTest(records=records), self.assertRaises(ValueError):
                report.validate(records)
        for name, expected in report.EXPECTED.items():
            missing = dict(report.EXPECTED)
            del missing[name]
            with self.subTest(missing=name), self.assertRaises(ValueError):
                report.validate([missing])
            for value in (True, None, float(expected), expected + 1, -1):
                with self.subTest(name=name, value=value), self.assertRaises(ValueError):
                    report.validate([dict(report.EXPECTED, **{name: value})])

    def test_all_six_exact_junit_cases_including_async_edges_are_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / 'TEST-metrics.xml'
            cases = [f'<testcase classname="{report.CLASS}" name="{name}"/>' for name in sorted(report.CASES)]
            file.write_text('<testsuite>' + ''.join(cases) + '</testsuite>')
            self.assertEqual(sorted(report.CASES), report.common.require_junit(root, report.CLASS, report.CASES))
            invalid = [''.join(cases[:-1]), ''.join(cases + cases[:1])]
            invalid += [''.join(cases).replace('/>', f'><{tag}/></testcase>', 1)
                        for tag in ('failure', 'error', 'skipped')]
            for body in invalid:
                file.write_text('<testsuite>' + body + '</testsuite>')
                with self.subTest(body=body), self.assertRaises(ValueError):
                    report.common.require_junit(root, report.CLASS, report.CASES)

    def test_case_names_and_production_call_sites_remain_bound(self):
        source = (report.common.TEST_ROOT / 'src/androidTest/java/me/rerere/rikkahub/viewporttest/'
                  'TerminalTextMetricsInstrumentedTest.kt').read_text()
        self.assertEqual(report.CASES, set(re.findall(r'@Test\s+fun\s+(\w+)\(', source)))
        folder = report.common.ROOT / 'app/src/main/java/me/rerere/rikkahub/ui/pages/container'
        source = (folder / 'TerminalTranscriptWidth.kt').read_text()
        self.assertEqual(2, source.count('val metrics = terminalTextMetrics(style)'))
        self.assertIn('family === JetbrainsMono', source)
        self.assertIn('FontLoadingStrategy.Blocking', source)
        for index, name in enumerate(('normal', 'italic', 'bold', 'boldItalic')):
            self.assertIn(f'val {name} = states[{index}].value', source)
        self.assertNotIn('Snapshot.withoutReadObservation', source)
        self.assertIn('val pass = rememberTerminalLazyLayoutPass(frame, style)',
                      (folder / 'TerminalViewportBinding.kt').read_text())
        self.assertIn('rememberTerminalTranscriptWidth(frame, style, widthIndex)',
                      (folder / 'TerminalVirtualHistoryTranscript.kt').read_text())


if __name__ == '__main__':
    unittest.main()
