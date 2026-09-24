import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class ReportTerminalTestsTest(unittest.TestCase):
    def report(self, cases, *arguments, duplicate=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            xml = '<testsuite>' + ''.join(cases) + '</testsuite>'
            (root / 'TEST-fixture.xml').write_text(xml)
            if duplicate:
                (root / 'TEST-copy.xml').write_text(xml)
            return subprocess.run(
                [sys.executable, str(Path(__file__).with_name('report-terminal-tests.py')), str(root), *arguments],
                text=True, capture_output=True, check=False,
            )

    def test_requires_both_parameterized_arms(self):
        result = self.report([
            '<testcase classname="Fixture" name="jump[lazyHistory=false]"/>',
            '<testcase classname="Fixture" name="jump[lazyHistory=true]"/>',
        ], '--require-suite', 'Fixture', '--require-case-group', 'lazyHistory=false:1',
            '--require-case-group', 'lazyHistory=true:1')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('2 JUnit cases', result.stdout)

    def test_one_arm_does_not_pass_as_a_complete_pair(self):
        result = self.report(['<testcase classname="Fixture" name="jump[lazyHistory=false]"/>'],
                             '--require-case-group', 'lazyHistory=true:1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('found 0', result.stdout)

    def test_missing_suite_does_not_pass_with_zero_tests(self):
        result = self.report([], '--require-suite', 'Fixture')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Required suite missing', result.stdout)

    def test_failures_errors_and_skips_fail_validation(self):
        for tag in ('failure', 'error', 'skipped'):
            with self.subTest(tag=tag):
                result = self.report([f'<testcase classname="Fixture" name="jump"><{tag}/></testcase>'])
                self.assertNotEqual(0, result.returncode)
                self.assertIn('::error', result.stdout)

    def test_duplicate_reports_cannot_inflate_required_counts(self):
        result = self.report(['<testcase classname="Fixture" name="jump"/>'], duplicate=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Duplicate JUnit case', result.stdout)


if __name__ == '__main__':
    unittest.main()
