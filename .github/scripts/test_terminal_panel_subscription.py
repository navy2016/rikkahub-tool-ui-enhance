import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('panel_report', Path(__file__).with_name('report-terminal-panel-subscription.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


class PanelSubscriptionReportTest(unittest.TestCase):
    def test_exact_paired_work_is_accepted(self):
        self.assertEqual(report.EXPECTED, report.validate([dict(report.EXPECTED)]))

    def test_missing_duplicate_or_extra_records_are_not_evidence(self):
        for records in ([], [report.EXPECTED, report.EXPECTED], [None], [{}], [dict(report.EXPECTED, extra=0)]):
            with self.subTest(records=records), self.assertRaises(ValueError):
                report.validate(records)

    def test_missing_noninteger_and_incorrect_counts_are_rejected(self):
        for name, expected in report.EXPECTED.items():
            record = dict(report.EXPECTED)
            del record[name]
            with self.subTest(missing=name), self.assertRaises(ValueError):
                report.validate([record])
            for value in (None, False, True, float(expected), expected + 1, -1):
                record = dict(report.EXPECTED)
                record[name] = value
                with self.subTest(name=name, value=value), self.assertRaises(ValueError):
                    report.validate([record])

    def test_only_all_three_exact_successful_junit_cases_are_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'TEST-fixture.xml'
            cases = [f'<testcase classname="{report.CLASS}" name="{name}"/>' for name in sorted(report.CASES)]
            good = '<testsuite>' + ''.join(cases) + '</testsuite>'
            path.write_text(good)
            self.assertEqual(sorted(report.CASES), report.require_junit(root))
            wrong = ['<testsuite>' + ''.join(cases[:-1]) + '</testsuite>',
                     '<testsuite>' + ''.join(cases + cases[:1]) + '</testsuite>',
                     good.replace(sorted(report.CASES)[0], 'panelSubscriptionUnknown')]
            wrong += [good.replace('/>', f'><{tag}/></testcase>', 1) for tag in ('failure', 'error', 'skipped')]
            for text in wrong:
                path.write_text(text)
                with self.subTest(text=text), self.assertRaises(ValueError):
                    report.require_junit(root)

    def test_absent_or_different_junit_class_cannot_validate_logged_work(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                report.require_junit(root)
            cases = [f'<testcase classname="unrelated.Class" name="{name}"/>' for name in sorted(report.CASES)]
            (root / 'TEST-fixture.xml').write_text('<testsuite>' + ''.join(cases) + '</testsuite>')
            with self.assertRaises(ValueError):
                report.require_junit(root)


if __name__ == '__main__':
    unittest.main()
