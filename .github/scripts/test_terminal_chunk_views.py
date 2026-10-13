import importlib.util
import re
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'chunk_views_report', Path(__file__).with_name('report-terminal-chunk-views.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


def records():
    values = []
    for size in report.SIZES:
        chunks = (125 + size + 127) // 128
        for cached in (False, True):
            values.append(dict(size=size, cached=cached, chunks=chunks, updates=32, firstSequence=125,
                               frameRows=size + 6, entries=32, cachedEntries=32 if cached else 0,
                               wrappers=0 if cached else 32 * chunks, slices=0 if cached else 32 * (chunks + 1),
                               historyRowCompositions=32, screenRowCompositions=32, sourceIdentityChecks=32))
    return values


class ChunkViewReportTest(unittest.TestCase):
    def test_complete_matrix_requires_actual_entries_not_assumed_speedup(self):
        rows = records()
        self.assertEqual(rows, report.validate(rows))
        for row in rows:
            if row['cached']:
                row['historyRowCompositions'] = 64
        self.assertEqual(rows, report.validate(rows)) # Never pretend wrapper counts prove fewer row compositions.

    def test_partial_duplicate_wrong_path_order_and_mistyped_counts_are_rejected(self):
        for rows in ([], records()[:-1], records() + records()[:1], list(reversed(records())), [None]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                report.validate(rows)
        for name, value in (('size', True), ('cached', 0), ('chunks', 0), ('entries', 0), ('cachedEntries', 32),
                            ('updates', 31), ('wrappers', 0), ('slices', 0), ('firstSequence', 126),
                            ('frameRows', 1000), ('historyRowCompositions', -1), ('screenRowCompositions', False),
                            ('sourceIdentityChecks', 0)):
            rows = records()
            rows[0][name] = value
            with self.subTest(name=name, value=value), self.assertRaises(ValueError):
                report.validate(rows)
        rows = records()
        rows[0]['extra'] = 1
        with self.assertRaises(ValueError):
            report.validate(rows)

    def test_all_seven_exact_cases_must_pass_with_no_skips(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'TEST-chunk-views.xml'
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

    def test_instrumented_names_and_full_production_provenance_chain_match(self):
        tests = report.common.TEST_ROOT / 'src/androidTest/java/me/rerere/rikkahub/viewporttest'
        text = (tests / 'TerminalChunkViewsInstrumentedTest.kt').read_text()
        self.assertEqual(report.CASES, set(re.findall(r'@Test\s+fun\s+(\w+)\(', text)))
        folder = report.common.ROOT / 'app/src/main/java/me/rerere/rikkahub/ui/pages/container'
        self.assertIn('rowSource = renderSnapshot', (folder / 'ProcessSessionPage.kt').read_text())
        self.assertIn('rowSource = rowSource', (folder / 'TerminalTranscriptViewport.kt').read_text())
        self.assertIn('rowSource = rowSource', (folder / 'TerminalRenderControls.kt').read_text())
        text = (folder / 'TerminalRenderedRows.kt').read_text()
        self.assertIn('remember(identity)', text)
        self.assertIn('rows === other.rows && plan === other.plan', text)
        self.assertIn('require(source.rows === rows)', text)
        for name in ('TerminalItemViewportInstrumentedTest.kt', 'TerminalTranscriptGeometryInstrumentedTest.kt'):
            self.assertIn('rowSource = source', (tests / name).read_text())


if __name__ == '__main__':
    unittest.main()
