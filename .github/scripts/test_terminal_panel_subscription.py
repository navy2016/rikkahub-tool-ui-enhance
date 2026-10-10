import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('panel_report', Path(__file__).with_name('report-terminal-panel-subscription.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


class PanelSubscriptionReportTest(unittest.TestCase):
    def test_binding_projection_requires_real_layout_changes_and_no_panel_recomposition(self):
        good = dict(layoutChanges=24, legacyCompositions=24, projectedCompositions=0, bottomChecks=24, bindingStable=True)
        self.assertEqual(good, report.validate_binding([good]))
        for key, value in (('layoutChanges', 0), ('bottomChecks', 23), ('projectedCompositions', 1),
                           ('legacyCompositions', 23), ('legacyCompositions', True), ('bindingStable', 1)):
            with self.subTest(key=key), self.assertRaises(ValueError):
                report.validate_binding([dict(good, **{key: value})])
        for rows in ([], [good, good], [None], [dict(good, extra=1)]):
            with self.assertRaises(ValueError):
                report.validate_binding(rows)

    def test_binding_identity_tui_and_layout_tests_are_all_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'TEST-binding.xml'
            cases = [f'<testcase classname="{report.BINDING_CLASS}" name="{name}"/>' for name in sorted(report.BINDING_CASES)]
            path.write_text('<testsuite>' + ''.join(cases) + '</testsuite>')
            self.assertEqual(sorted(report.BINDING_CASES), report.require_junit(root, report.BINDING_CLASS, report.BINDING_CASES))
            path.write_text('<testsuite>' + ''.join(cases[:-1]) + '</testsuite>')
            with self.assertRaises(ValueError):
                report.require_junit(root, report.BINDING_CLASS, report.BINDING_CASES)

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
