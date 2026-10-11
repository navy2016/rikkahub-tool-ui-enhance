import importlib.util
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('row_width_report', Path(__file__).with_name('report-terminal-row-width.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


def records():
    return [dict(size=size, pair=pair, legacy=old, first=old == (pair % 2 == 0), rows=size + 6,
                 singleCalls=0 if old else size + 6, multiCalls=size + 6 if old else 0,
                 nanos=(10 + pair + int(not old)) * 1_000_000, widthSum=100 * (size + 6), widthSha256='a' * 64)
            for size in report.SIZES for pair in range(report.PAIRS) for old in (True, False)]


class RowWidthReportTest(unittest.TestCase):
    def test_complete_matrix_accepts_correct_widths_even_if_candidate_is_slower(self):
        self.assertEqual(24, len(report.validate(records())))
        self.assertTrue(all(r['pairedDeltaMedianMs'] == 1 for r in report.summarize(records())))

    def test_partial_duplicate_wrong_order_and_wrong_path_are_rejected(self):
        for rows in (records()[:-1], records() + records()[:1]):
            with self.assertRaises(ValueError):
                report.validate(rows)
        for field, value in (('size', True), ('size', 50), ('pair', 4), ('legacy', 1), ('first', False),
                             ('rows', 1000), ('singleCalls', 1), ('multiCalls', False), ('nanos', 0),
                             ('nanos', float('nan')), ('widthSum', 0), ('widthSha256', 'not-a-digest')):
            rows = records()
            rows[0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                report.validate(rows)

    def test_full_vector_not_just_sum_must_match_and_survive_all_pairs(self):
        for index in (1, 6):
            for field, value in (('widthSum', 99), ('widthSha256', 'b' * 64)):
                rows = records()
                rows[index][field] = value
                with self.subTest(index=index, field=field), self.assertRaises(ValueError):
                    report.validate(rows)

    def test_all_ten_correctness_and_paired_test_names_must_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            file = root / 'TEST-width.xml'
            cases = [f'<testcase classname="{report.CLASS}" name="{name}"/>' for name in sorted(report.CASES)]
            file.write_text('<testsuite>' + ''.join(cases) + '</testsuite>')
            self.assertEqual(10, len(report.common.require_junit(root, report.CLASS, report.CASES)))
            for body in (''.join(cases[:-1]), ''.join(cases + cases[:1]),
                         ''.join(cases).replace('/>', '><failure/></testcase>', 1),
                         ''.join(cases).replace('/>', '><skipped/></testcase>', 1)):
                file.write_text('<testsuite>' + body + '</testsuite>')
                with self.assertRaises(ValueError):
                    report.common.require_junit(root, report.CLASS, report.CASES)

    def test_reference_measurer_is_frozen(self):
        self.assertEqual(report.CONTROL_HASH, report.common.digest(report.ROOT / report.CONTROL))


if __name__ == '__main__':
    unittest.main()
