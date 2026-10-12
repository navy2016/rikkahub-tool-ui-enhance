import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'snapshot_report', Path(__file__).with_name('report-terminal-render-snapshot.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


class SnapshotReportTest(unittest.TestCase):
    def test_exact_reuse_is_accepted_without_demanding_subtree_speedup(self):
        row = dict(report.EXPECTED, legacyChildCompositions=0, publishedChildCompositions=32)
        self.assertEqual(row, report.validate([row]))

    def test_incomplete_duplicate_mistyped_and_invented_work_is_rejected(self):
        good = dict(report.EXPECTED, legacyChildCompositions=32, publishedChildCompositions=0)
        for rows in ([], [good, good], [None], [dict(good, extra=1)]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                report.validate(rows)
        for key in good:
            row = dict(good)
            del row[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                report.validate([row])
            for value in (True, None, -1, float(good[key])):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    report.validate([dict(good, **{key: value})])
        for key in report.EXPECTED:
            with self.subTest(changed=key), self.assertRaises(ValueError):
                report.validate([dict(good, **{key: good[key] + 1})])

    def test_all_six_exact_instrumented_cases_must_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            path = root / 'TEST-snapshot.xml'
            cases = [f'<testcase classname="{report.CLASS}" name="{name}"/>' for name in sorted(report.CASES)]
            path.write_text('<testsuite>' + ''.join(cases) + '</testsuite>')
            self.assertEqual(sorted(report.CASES), report.common.require_junit(root, report.CLASS, report.CASES))
            invalid = [''.join(cases[:-1]), ''.join(cases + cases[:1]),
                       ''.join(cases).replace(sorted(report.CASES)[0], 'unknownCase')]
            invalid += [''.join(cases).replace('/>', f'><{tag}/></testcase>', 1)
                        for tag in ('failure', 'error', 'skipped')]
            for body in invalid:
                path.write_text('<testsuite>' + body + '</testsuite>')
                with self.subTest(body=body), self.assertRaises(ValueError):
                    report.common.require_junit(root, report.CLASS, report.CASES)


if __name__ == '__main__':
    unittest.main()
