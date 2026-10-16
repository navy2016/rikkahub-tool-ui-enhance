import copy
import importlib.util
import json
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('bundle_reports', Path(__file__).with_name('terminal-bundle-reports.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


def samples():
    return [dict(suite=report.SOAK_SUITE, mode='chunkedLayers', requestedSeconds=1800,
                 launch='11111111-2222-4333-8444-555555555555', bucket=i, final=i == 29,
                 elapsedMs=(i + 1) * 60000, cycles=(i + 1) * 30, bucketCycles=30,
                 remounts=i // 5, readBytes=(i + 1) * 30000, fedBytes=(i + 1) * 30000,
                 published=(i + 1) * 30, drawn=(i + 1) * 30, traceDropped=0, historyRows=10000,
                 pssKb=200000, javaUsedBytes=120000000, nativeAllocatedBytes=40000000,
                 gcCount=i, gcStart=0, cycleMeanMs=1000, cycleMaxMs=1500) for i in range(30)]


def text(rows):
    return '\n'.join('SOAK_SAMPLE ' + json.dumps(row) for row in rows)


def settled(mode='lazyHistoryIme', pressure=False, remounts=0):
    phases = ['mounted', 'imeVisible', 'imeHidden', 'remount']
    if pressure:
        phases += ['pressureStart'] + ['pressureRemount'] * remounts
    result = []
    for index, phase in enumerate(phases):
        virtual = mode != 'chunkedLayers' and (mode == 'lazyHistoryIme' or phase not in ('imeVisible', 'imeHidden'))
        ime = phase == 'imeVisible'
        viewport = dict(draw=index * 10 + 3, timeNanos=index * 1000 + 500, revision=10 + index,
            preferred=mode, applied=mode, historyRows=10000, hasChunks=True, pan=True, selection=False,
            tui=False, alternate=False, ime=ime, avoidIme=ime, imeFallback=mode == 'lazyHistory' and not virtual,
            wantsVirtual=virtual, virtual=virtual, bindingVirtual=virtual, sameFrame=True, initialized=True,
            following=True, gestureId=None, effectId=None, writers=0, scrolling=False, geometryReady=True,
            widthReady=virtual, tailSatisfied=True)
        result.append(dict(suite=report.SETTLE_CONTRACT, mode=mode, phase=phase,
            launch='11111111-2222-4333-8444-555555555555', expectedVirtual=virtual, expectedIme=ime,
            settleMs=40.0, afterDraw=index * 10 + 1, firstStableDraw=index * 10 + 2, confirmations=2,
            minRevision=10 + index, viewport=viewport))
    return result


def settle_text(rows):
    return '\n'.join('SETTLE_RECEIPT ' + json.dumps(row) for row in rows)


class BundleReportsTest(unittest.TestCase):
    def test_complete_convergence_contract_keeps_each_modes_independent_expectation(self):
        for mode in ('chunkedLayers', 'lazyHistory', 'lazyHistoryIme'):
            self.assertEqual(4, len(report.settlements(settle_text(settled(mode)), mode)))
        raw = samples()
        raw = [dict(row, mode='lazyHistoryIme') for row in raw]
        receipts = settled(pressure=True, remounts=raw[-1]['remounts'])
        result = report.soak(text(raw) + '\n' + settle_text(receipts), 'lazyHistoryIme', 1800, require_settled=True)
        self.assertEqual(report.SETTLE_CONTRACT, result['settlementContract'])
        self.assertEqual(receipts, result['settlements'])
        with self.assertRaises(ValueError):
            report.soak(text(raw), 'lazyHistoryIme', 1800, require_settled=True)

    def test_missing_permanent_fallback_or_repeated_draw_receipts_fail(self):
        for change in ('missing', 'duplicate', 'fallback', 'sameDraw', 'stale', 'wrongFrame', 'effect', 'oneDraw'):
            receipts = settled()
            if change == 'missing': receipts.pop()
            elif change == 'duplicate': receipts.append(receipts[0])
            elif change == 'fallback': receipts[0]['viewport']['virtual'] = False
            elif change == 'sameDraw': receipts[0]['viewport']['draw'] = receipts[0]['firstStableDraw']
            elif change == 'stale': receipts[0]['viewport']['revision'] = 1
            elif change == 'wrongFrame': receipts[0]['viewport']['sameFrame'] = False
            elif change == 'effect': receipts[0]['viewport']['effectId'] = 9
            else: receipts[0]['confirmations'] = 1
            with self.subTest(change=change), self.assertRaises(ValueError):
                report.settlements(settle_text(receipts), 'lazyHistoryIme')

    def test_all_pending_gates_and_mistyped_scalar_receipts_are_rejected(self):
        for field, value in (('widthReady', False), ('geometryReady', False), ('tailSatisfied', False),
                             ('wantsVirtual', False), ('pan', False), ('selection', True), ('tui', True),
                             ('alternate', True), ('ime', True), ('imeFallback', True), ('writers', True),
                             ('scrolling', True), ('historyRows', 0), ('bindingVirtual', False)):
            receipts = settled()
            receipts[0]['viewport'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                report.settlements(settle_text(receipts), 'lazyHistoryIme')
        receipts = settled()
        receipts[1]['viewport']['avoidIme'] = False
        with self.assertRaises(ValueError):
            report.settlements(settle_text(receipts), 'lazyHistoryIme')

    def test_cross_phase_reused_draw_or_missing_null_field_cannot_pass(self):
        for change in ('draw', 'time', 'source', 'gesture', 'effect'):
            receipts = settled()
            second = receipts[1]
            if change == 'draw': second['afterDraw'] = 0
            elif change == 'time': second['viewport']['timeNanos'] = 1
            elif change == 'source':
                second['viewport']['revision'] = 9
                second['minRevision'] = 9
            elif change == 'gesture': del second['viewport']['gestureId']
            else: del second['viewport']['effectId']
            with self.subTest(change=change), self.assertRaises(ValueError):
                report.settlements(settle_text(receipts), 'lazyHistoryIme')

    def test_complete_pressure_keeps_all_samples_and_increases(self):
        rows = samples()
        rows[-1]['pssKb'] += 100
        result = report.soak(text(rows), 'chunkedLayers', 1800)
        self.assertEqual(900, result['cycles'])
        self.assertEqual(30, len(result['samples']))
        self.assertEqual(200100, result['peakPssKb'])

    def test_missing_duplicate_short_and_mixed_runs_fail(self):
        for rows in (samples()[:-1], samples() + samples()[-1:], []):
            with self.assertRaises(ValueError):
                report.soak(text(rows), 'chunkedLayers', 1800)
        for key, value in (('mode', 'lazyHistory'), ('final', False), ('readBytes', 1), ('historyRows', 9999),
                           ('traceDropped', 1), ('pssKb', 0), ('cycles', True), ('cycleMaxMs', float('nan')),
                           ('elapsedMs', 1700000), ('remounts', 0)):
            rows = samples()
            rows[-1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                report.soak(text(rows), 'chunkedLayers', 1800)

    def test_runaway_retention_is_a_failure_not_a_faster_result(self):
        rows = samples()
        for row in rows[-5:]: row['pssKb'] += 200000
        with self.assertRaisesRegex(ValueError, 'PSS grew'):
            report.soak(text(rows), 'chunkedLayers', 1800)

    def test_service_requires_both_platform_and_tail_cases(self):
        rows = [dict(case='foreground', suite='terminal-bundle-service-v1', cycles=20, promotions=22, pending=False, live=False),
                dict(case='orderedTail', suite='terminal-bundle-service-v1', lines=500, bytes=5000)]
        lines = '\n'.join('SERVICE_SAMPLE ' + json.dumps(row) for row in rows)
        self.assertEqual(rows, report.service(lines))
        for bad in (rows[:1], rows * 2, [dict(rows[0], pending=True), rows[1]], [rows[0], dict(rows[1], bytes=4999)]):
            with self.assertRaises(ValueError):
                report.service('\n'.join('SERVICE_SAMPLE ' + json.dumps(row) for row in bad))


if __name__ == '__main__':
    unittest.main()
