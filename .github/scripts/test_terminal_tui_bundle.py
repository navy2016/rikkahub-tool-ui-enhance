"""Reject missing/forged TUI bundle samples and broken workflow/source wiring without compiling."""
import copy
import importlib.util
import json
import re
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('tui_reports', Path(__file__).with_name('terminal-tui-bundle-reports.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)
ROOT = report.ROOT
SHA = 'a' * 40


def records(marker, values):
    return '\n'.join(marker + json.dumps(value) for value in values)


def parser_samples():
    sizes = dict(ascii=len((('row:' + 'abc123 ' * 9 + '\r\n') * 48).encode()),
                 unicode=len(('中文 e\u0301 👩‍💻 🇨🇳\r\n' * 100).encode()),
                 redraw=len((('\x1b[H\x1b[2J\x1b[31;1mstatus\x1b[0m\r\n' + 'x' * 64) * 32).encode()))
    return [dict(scenario=s, phase=phase, pair=pair, arm=arm, position=position,
                 iterations=24 if phase == 'warmup' else 96, chunkBytes=sizes[s], elapsedNanos=10000,
                 allocatedBytes=5000 if arm == 'reference' else 3000,
                 revision=25 if phase == 'warmup' else 97, historyRows=0 if s == 'redraw' else 128,
                 sha256='b' * 64)
            for s in report.SCENARIOS for phase, pairs in (('warmup', 2), ('measurement', 4))
            for pair in range(pairs) for position, arm in enumerate(
                ('reference', 'candidate') if pair % 2 == 0 else ('candidate', 'reference'))]


def parser_text(samples):
    env = dict(sourceSha=SHA, runId='123', environment={'java.version': '21', 'java.vm.name': 'VM',
                                                      'os.name': 'Linux', 'os.arch': 'amd64'})
    return records('PARSER_ENV ', [env]) + '\n' + records('PARSER_SAMPLE ', samples)


def pty_samples():
    values = [dict(case='protocol', responseBytes=21, responseHex='1b5b3f313b32631b5b3e343b326d1b5b353b313152'),
              dict(case='ime', inputBytes=13, inputHex='65cc81f09f918df09f8fbd7f7f'),
              dict(case='mouse', inputBytes=18, inputHex='1b5b3c303b333b314d1b5b3c303b333b316d'),
              dict(case='resize', kernelChecks=3), dict(case='overlay', textIntact=True),
              dict(case='regular', historyRows=1000, rawInput=True, alternateRoundTrip=True, peer='synthetic-pi-regular')]
    return [dict(row, passed=True, suite='terminal-tui-bundle-pty-v1') for row in values]


def display_samples():
    return [dict(case='cursorPixels', variants=8, passed=True),
            dict(case='cursorMoves', moves=24, parentExtra=0, passed=True),
            dict(case='pointerLayout', screenRows=6, disposedRows=0, passed=True),
            dict(case='regularHistory', rows=24, passed=True)]


class TuiBundleReportsTest(unittest.TestCase):
    def test_frozen_reference_sources_have_not_changed(self):
        self.assertEqual(report.REFERENCE_HASHES, report.reference_proof())

    def test_parser_keeps_12_warmup_and_24_formal_samples_even_when_candidate_slower(self):
        samples = parser_samples()
        for row in samples:
            if row['arm'] == 'candidate':
                row['elapsedNanos'] *= 9
        checked = report.validate_parser(parser_text(samples), SHA, '123')
        self.assertEqual(36, len(checked['samples']))
        self.assertGreater(checked['summary']['ascii']['candidate']['nsPerChunk'],
                           checked['summary']['ascii']['reference']['nsPerChunk'])

    def test_parser_rejects_omission_duplicate_order_mixed_identity_and_invalid_scalars(self):
        good = parser_samples()
        invalid = [good[:-1], good + good[:1], list(reversed(good))]
        for field, value in (('revision', 99), ('allocatedBytes', -1), ('elapsedNanos', True), ('sha256', 'x'),
                             ('historyRows', 129), ('position', 2), ('iterations', 1), ('chunkBytes', 1)):
            rows = copy.deepcopy(good)
            rows[0][field] = value
            invalid.append(rows)
        for rows in invalid:
            with self.assertRaises(ValueError):
                report.validate_parser(parser_text(rows), SHA, '123')
        with self.assertRaises(ValueError):
            report.validate_parser(parser_text(good), 'c' * 40, '123')

    def test_parser_rejects_different_output_and_no_allocation_reduction(self):
        for field, value in (('sha256', 'c' * 64), ('allocatedBytes', 9000)):
            rows = parser_samples()
            rows[5][field] = value
            with self.assertRaises(ValueError):
                report.validate_parser(parser_text(rows), SHA, '123')

    def test_all_six_pty_cases_require_independent_exact_byte_vectors(self):
        self.assertEqual(6, len(report.validate_pty(records('TUI_PTY ', pty_samples()))))
        invalid = [pty_samples()[:-1], pty_samples() + pty_samples()[:1]]
        for field, value in (('responseBytes', 24), ('responseHex', '00'), ('passed', False), ('suite', 'old')):
            rows = pty_samples()
            rows[0][field] = value
            invalid.append(rows)
        for rows in invalid:
            with self.assertRaises(ValueError):
                report.validate_pty(records('TUI_PTY ', rows))

    def test_display_rejects_missing_disposal_and_extra_compositions(self):
        self.assertEqual(4, len(report.validate_display(records('TUI_DISPLAY ', display_samples()))))
        for index, field, value in ((0, 'variants', 4), (1, 'parentExtra', 1), (2, 'disposedRows', 1), (3, 'rows', True)):
            rows = display_samples()
            rows[index][field] = value
            with self.assertRaises(ValueError):
                report.validate_display(records('TUI_DISPLAY ', rows))

    def test_all_new_tests_are_wired_once_and_exhaustively(self):
        workflow = (ROOT / '.github/workflows/terminal-emulator-tests.yml').read_text()
        full = workflow.split('- name: Run complete terminal unit suites once', 1)[1].split('- name:', 1)[0]
        for path, count in report.UNIT_FILES.items():
            self.assertEqual(count, len(report.test_methods((ROOT / report.TEST / path).read_text())))
            name = 'me.rerere.rikkahub.' + path.removesuffix('.kt').replace('/', '.')
            self.assertEqual(1, full.count('--tests ' + name))
        source = ROOT / 'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalTuiBundleInstrumentedTest.kt'
        self.assertEqual(set(report.PTY_METHODS), report.test_methods(source.read_text()))
        display = ROOT / 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalTuiDisplayInstrumentedTest.kt'
        self.assertEqual(report.DISPLAY_CASES, report.test_methods(display.read_text()))

    def test_readme_scope_and_default_modes_are_not_relabelled(self):
        mode = (ROOT / report.MAIN / 'data/container/TerminalRenderMode.kt').read_text()
        self.assertIn('val DEFAULT = CHUNKED_LAYERS', mode)
        page = (ROOT / report.MAIN / 'ui/pages/container/ProcessSessionPage.kt').read_text()
        panel = page.index('private fun TerminalInteractivePanel(')
        for token in ('val regularHistory =', 'val commandIsTui =', 'val wantsCursorOverlay =', 'val terminalOutputUpdates ='):
            self.assertEqual(1, page.count(token))
            self.assertGreater(page.index(token), panel)
        self.assertEqual(1, page.count('terminalInputQueue.consume'))
        self.assertNotIn('cluster.codePointAt(0)', page)
        self.assertNotIn('rawMouseChannel', page)
        self.assertNotIn('currentScreenStartRow * terminalCellHeightPx', page)
        self.assertIn('latestPublishTerminalFrame(forced, !syncCompleted)', page)
        self.assertIn('record.outputMutex.withLock', (ROOT / report.MAIN / 'data/container/BackgroundProcessManager.kt').read_text())

    def test_pipeline_source_inventory_includes_every_changed_production_file(self):
        spec = importlib.util.spec_from_file_location('tui_pipeline', Path(__file__).with_name('run-terminal-pipeline.py'))
        pipeline = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pipeline)
        self.assertTrue(set(report.source_hashes()) <= set(pipeline.sources()))


if __name__ == '__main__':
    unittest.main()
