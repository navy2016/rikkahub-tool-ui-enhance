import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('scroll_report', Path(__file__).with_name('report-terminal-scroll-completion.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


def rows():
    return [dict(case=case, pair=pair, legacy=old, first=(pair % 2 == 0) if old else (pair % 2 == 1),
                 waits=int(old), nanos=100_000) for case in report.CASES for pair in range(10) for old in (True, False)]


class ScrollCompletionReportTest(unittest.TestCase):
    def test_complete_pairs_are_accepted(self):
        self.assertEqual(40, len(report.validate(rows())))

    def test_missing_duplicate_invalid_order_or_waits_are_rejected(self):
        for data in (rows()[:-1], rows() + rows()[:1]):
            with self.assertRaises(ValueError):
                report.validate(data)
        for name, value in (('case', 'unknown'), ('pair', True), ('legacy', 1), ('waits', 0),
                             ('waits', True), ('first', False), ('nanos', 0)):
            data = rows()
            data[0][name] = value
            with self.subTest(name=name), self.assertRaises(ValueError):
                report.validate(data)

    def test_junit_passes_required_before_accepting_logged_samples(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tests = [f'<testcase classname="{report.CLASS}" name="scrollCompletion{i}"/>' for i in range(7)]
            path = root / 'TEST-fixture.xml'
            path.write_text('<testsuite>' + ''.join(tests) + '</testsuite>')
            self.assertEqual(7, len(report.require_junit(root)))
            path.write_text('<testsuite>' + ''.join(tests[:-1]) + '</testsuite>')
            with self.assertRaises(ValueError):
                report.require_junit(root)
            path.write_text('<testsuite>' + ''.join(tests).replace('/>', '><failure/></testcase>', 1) + '</testsuite>')
            with self.assertRaises(ValueError):
                report.require_junit(root)


if __name__ == '__main__':
    unittest.main()
