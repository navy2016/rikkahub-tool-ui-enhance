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


class BundleReportsTest(unittest.TestCase):
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
