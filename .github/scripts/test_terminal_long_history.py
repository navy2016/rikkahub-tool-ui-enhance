"""Host checks for the fixed long-history diagnostic matrix; no app build."""
import copy
import importlib.util
import json
from pathlib import Path
import re
import unittest

SPEC = importlib.util.spec_from_file_location('long_report', Path(__file__).with_name('terminal-long-history-reports.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


def samples():
    result = []
    for size in report.SIZES:
        for round_ in range(4):
            for profile in report.PROFILES if round_ % 2 == 0 else report.PROFILES[::-1]:
                for phase in report.PHASES:
                    ticks = 0 if phase == 'cold' else 24
                    row = dict.fromkeys(report.TIMES + report.COUNTS, 0)
                    row.update(size=size, profile=profile, round=round_, phase=phase, warmup=round_ == 0,
                        stateCreateNs=0 if phase == 'streamIme' else 100, firstDrawNs=1000, completedNs=9000,
                        commits=3, rootDraws=3, ticks=ticks, retainedRows=size+24, createdNodes=size+24,
                        publicationNs=[10] * ticks, feedCaptureNs=4*ticks, rowSyncNs=6*ticks,
                        syncVisited=(size+24)*ticks, writers=1, cursorTop=272, cursorBottom=299, rowHeight=27, viewportHeight=319)
                    result.append(row)
    return result


def text(rows, devices=None):
    if devices is None:
        devices = [dict(size=n, pid=123, fingerprint='synthetic-host-input', targetApk='a'*64, testApk='b'*64,
                        density=1.33, fontScale=1.3) for n in report.SIZES]
    return '\n'.join('LONG_SAMPLE ' + json.dumps(r) for r in rows) + '\n' + '\n'.join(
        'LONG_DEVICE ' + json.dumps(d) for d in devices)


class LongHistoryTest(unittest.TestCase):
    def test_complete_fixed_matrix_including_slow_values(self):
        values = samples()
        values[10]['completedNs'] *= 10000
        rows, _ = report.validate(text(values))
        self.assertEqual(72, len(rows))
        self.assertEqual(18, sum(r['warmup'] for r in rows))
        self.assertEqual(18, len(report.summary(rows)))

    def test_missing_duplicate_and_reordered_data_rejected(self):
        for values in (samples()[:-1], samples() + samples()[:1], list(reversed(samples()))):
            with self.assertRaises(ValueError):
                report.validate(text(values))

    def test_counters_partial_output_clipping_and_wrong_warmup_fail(self):
        for field, value in (('rowSyncNs', 1), ('ticks', 23), ('syncVisited', 100), ('warmup', False),
                             ('cursorBottom', 1000), ('writers', 2), ('stateCreateNs', -1), ('firstDrawNs', True)):
            values = samples()
            values[1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                report.validate(text(values))

    def test_mixed_apk_and_missing_device_fail(self):
        devices = [dict(size=n, pid=123, fingerprint='device', targetApk='a'*64, testApk='b'*64,
                        density=1.33, fontScale=1.3) for n in report.SIZES]
        with self.assertRaises(ValueError):
            report.validate(text(samples(), devices[:-1]))
        devices[0]['targetApk'] = 'c'*64
        with self.assertRaises(ValueError):
            report.validate(text(samples(), devices))

    def test_diagnostic_is_separate_from_acceptance_and_source_is_bound(self):
        source = (report.ROOT / report.TEST).read_text()
        self.assertEqual(set(report.CASES), set(re.findall(r'@Test\s+fun\s+(\w+)\(', source)))
        self.assertIn('TerminalRowStore(initial)', source)
        self.assertIn('rememberTerminalBoundViewport(', source)
        self.assertIn('TerminalTranscriptViewport(', source)
        self.assertIn('Snapshot.withMutableSnapshot', source)
        self.assertIn('peekEager', source)
        self.assertNotIn('eager.scrollTo', source)
        self.assertNotIn('animateScrollTo', source)
        workflow = (report.ROOT / '.github/workflows/terminal-long-history.yml').read_text()
        self.assertIn(report.TREE, workflow)
        self.assertIn('git diff --exit-code ' + report.BASE, workflow)
        self.assertNotIn('assembleDebug', workflow)
        self.assertIn('run-terminal-with-timeout.py 900', workflow)
        self.assertIn('acceptance=False', Path(report.__file__).read_text())


if __name__ == '__main__':
    unittest.main()
