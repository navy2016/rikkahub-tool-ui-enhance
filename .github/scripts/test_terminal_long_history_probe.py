"""The small grouping intervention has its own workload, identities and failure contract."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('grouping_probe', Path(__file__).with_name('terminal-long-history-probe.py'))
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def fixture():
    data = dict(samples=[], disposals=[], devices=[], stacks=[dict(size=5000, index=0, count=2,
        stack='androidx.compose.runtime.ComposerImpl.test:1')],
        sampler=dict(size=5000, observations=5, intervalNs=20_000_000, parts=1))
    for size in probe.SIZES:
        data['devices'].append(dict(size=size, pid=12, fingerprint='synthetic-host-input', targetApk='a'*64, testApk='b'*64))
        for round_ in range(3):
            for mode in probe.MODES if round_ % 2 == 0 else probe.MODES[::-1]:
                for phase in probe.PHASES:
                    row = dict.fromkeys(probe.TIMES + probe.COUNTS, 0)
                    ticks = 0 if phase == 'cold' else 3
                    row.update(size=size, round=round_, mode=mode, phase=phase, warmup=round_ == 0, tui=True,
                        completedNs=10000+round_, firstDrawNs=1000, commits=2, rootDraws=3,
                        stateCreateNs=100 if phase == 'cold' else 0, initialFrameNs=100 if phase == 'cold' else 0,
                        ticks=ticks, publicationNs=[10]*ticks, rowSyncNs=6*ticks, feedCaptureNs=4*ticks,
                        syncVisited=(size+24)*ticks, createdNodes=size+24 if phase == 'cold' else 10,
                        rowMeasures=size+24, retainedRows=size+24, writers=1, shapeSha256='c'*64,
                        cursorTop=281, cursorBottom=308, rowHeight=27, viewportHeight=319)
                    data['samples'].append(row)
                data['disposals'].append(dict(size=size, round=round_, mode=mode, retainedRows=0, retainedHeightRows=0))
    return data


def text(data):
    groups = [('SAMPLE', data['samples']), ('DISPOSE', data['disposals']), ('DEVICE', data['devices']),
              ('STACK_INFO', [data['sampler']]), ('STACK', data['stacks'])]
    return '\n'.join('LONG_PROBE_' + kind + ' ' + json.dumps(row) for kind, rows in groups for row in rows)


class LongHistoryProbeTest(unittest.TestCase):
    def test_complete_three_way_order_and_all_slow_samples_kept(self):
        data = fixture()
        data['samples'][10]['completedNs'] = 40000000000
        checked = probe.validate(text(data))
        self.assertEqual(54, len(checked['samples']))
        self.assertEqual(18, sum(r['warmup'] for r in checked['samples']))
        self.assertEqual(18, len(probe.summary(checked['samples'])))
        self.assertIn(40000000000, [r['completedNs'] for r in checked['samples']])

    def test_cannot_relabel_v1_failure(self):
        for suffix in ('\nLONG_SAMPLE {}', '\nLONG_TIMEOUT {}'):
            with self.assertRaises(ValueError):
                probe.validate(text(fixture()) + suffix)

    def test_rejects_missing_duplicate_misordered_samples(self):
        values = fixture()
        for rows in (values['samples'][:-1], values['samples']+[values['samples'][0]], values['samples'][::-1]):
            data = copy.deepcopy(values)
            data['samples'] = rows
            with self.assertRaises(ValueError):
                probe.validate(text(data))

    def test_rejects_changed_tui_workload_geometry_and_counters(self):
        for field, value in (('tui', False), ('ticks', 24), ('fifoUses', 1), ('syncSkipped', 1),
                             ('syncVisited', 1), ('warmup', False), ('retainedRows', 24), ('writers', 2),
                             ('shapeSha256', 'd'*64), ('completedNs', True), ('rowSyncNs', -1),
                             ('cursorBottom', 400), ('publicationNs', [1]), ('stateCreateNs', 1)):
            data = fixture()
            data['samples'][1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                probe.validate(text(data))

    def test_requires_disposal_device_and_separate_sampler(self):
        for key in ('disposals', 'devices', 'stacks'):
            data = fixture()
            data[key] = data[key][:-1]
            with self.subTest(key=key), self.assertRaises(ValueError):
                probe.validate(text(data))
        data = fixture()
        data['devices'][1]['targetApk'] = 'd'*64
        with self.assertRaises(ValueError):
            probe.validate(text(data))
        data = fixture()
        data['disposals'][0]['retainedRows'] = 1
        with self.assertRaises(ValueError):
            probe.validate(text(data))
        data = fixture()
        data['stacks'][0]['count'] = 10
        with self.assertRaises(ValueError):
            probe.validate(text(data))

    def test_pair_deltas_use_matching_round_not_uncontrolled_medians(self):
        data = fixture()
        for row in data['samples']:
            if row['mode'] == 'chunkedEager' and not row['warmup']:
                row['completedNs'] += 40 if row['round'] == 1 else -20
        checked = probe.validate(text(data))
        rows = [r for r in probe.summary(checked['samples']) if r['mode'] == 'chunkedEager']
        self.assertTrue(all(r['completionDeltaNs'] == [40, -20] for r in rows))

    def test_intervention_wiring_cannot_change_production_or_relax_wait(self):
        source = (probe.ROOT / probe.baseline.TEST).read_text()
        self.assertIn('if (probeMode == null) 24 else 3', source)
        self.assertIn('compose.waitUntil(45_000)', source)
        self.assertIn('throw error', source)
        self.assertIn('tui && (probeMode == null || probeMode == TerminalRenderMode.FLAT)', source)
        self.assertIn('usesTuiViewport = tui', source)
        self.assertIn('store.publish(frame, false, tui, false', source)
        self.assertIn('probe(size) else diagnose(size)', source)
        self.assertIn('val sampler = MainThreadSampler()', source)
        self.assertIn('sampler.finish()', source)
        workflow = (probe.ROOT / '.github/workflows/terminal-long-history.yml').read_text()
        self.assertIn('git diff --exit-code ' + probe.baseline.BASE, workflow)
        self.assertIn('terminal-long-history-probe.py', workflow)
        self.assertIn('terminal-long-history-reports.py', workflow)
        entry = (probe.ROOT / '.github/workflows/terminal-viewport-interactions.yml').read_text()
        self.assertIn("probe: ${{ inputs.suite == 'longHistoryProbe' }}", entry)
        self.assertIn("inputs.suite != 'longHistoryDiagnostic' && inputs.suite != 'longHistoryProbe'", entry)


if __name__ == '__main__':
    unittest.main()
