import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('byte_buffer_reports',
    Path(__file__).with_name('terminal-byte-buffer-reports.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)
SHA = '1' * 40
RUN = '12345'


def benchmark():
    env = dict(suite=report.BENCH, sourceSha=SHA, runId=RUN,
               environment={'java.version': '21-test', 'java.vm.name': 'fixture-vm', 'os.name': 'Linux', 'os.arch': 'test'})
    rows = []
    for scenario in report.SCENARIOS:
        for phase, pairs in (('warmup', 2), ('measurement', 4)):
            iterations = 64 if phase == 'warmup' else 256
            digest, checksum = report.benchmark_expected(scenario, iterations)
            for pair in range(pairs):
                for position, arm in enumerate(('legacy', 'ring') if pair % 2 == 0 else ('ring', 'legacy')):
                    rows.append(dict(suite=report.BENCH, scenario=scenario, phase=phase, pair=pair, arm=arm,
                                     position=position, capacity=report.CAPACITY, chunkBytes=4096, readBytes=64,
                                     iterations=iterations, retainedBytes=report.CAPACITY, checksum=checksum,
                                     sha256=digest, elapsedNanos=100000 * iterations,
                                     allocatedBytes=(600000 if arm == 'legacy' else 400) * iterations))
    return env, rows


def bench_text(env, rows):
    return 'BUFFER_BENCH_ENV ' + json.dumps(env) + '\n' + '\n'.join(
        'BUFFER_BENCH_SAMPLE ' + json.dumps(row) for row in rows)


def pty_text(rows, final):
    return '\n'.join('BYTE_BUFFER_PTY ' + json.dumps(row) for row in rows) + '\nBYTE_BUFFER_EXIT ' + json.dumps(final)


class TerminalByteBufferReportsTest(unittest.TestCase):
    def test_complete_abba_records_preserve_all_warmup_and_measurements(self):
        env, rows = benchmark()
        result = report.validate_benchmark(bench_text(env, rows), SHA, RUN)
        self.assertEqual(8, result['warmupSamples'])
        self.assertEqual(16, result['measurementSamples'])
        self.assertEqual(rows, result['samples'])
        self.assertEqual(400, result['summary']['smallRead']['ring']['allocatedBytesPerOperation'])

    def test_warmup_is_excluded_not_mixed_into_summary(self):
        env, rows = benchmark()
        for row in rows:
            if row['phase'] == 'warmup':
                row['elapsedNanos'] *= 100
                row['allocatedBytes'] *= 20
        result = report.validate_benchmark(bench_text(env, rows), SHA, RUN)
        self.assertEqual(100000, result['summary']['saturatedAppend']['legacy']['nsPerOperation'])

    def test_missing_duplicate_reordered_or_relabelled_samples_fail(self):
        env, rows = benchmark()
        for bad in (rows[:-1], rows + rows[:1], rows[1:] + rows[:1], list(reversed(rows))):
            with self.assertRaises(ValueError):
                report.validate_benchmark(bench_text(env, bad), SHA, RUN)
        changed = copy.deepcopy(rows)
        changed[4]['phase'] = 'warmup'
        with self.assertRaises(ValueError):
            report.validate_benchmark(bench_text(env, changed), SHA, RUN)

    def test_equal_arm_hashes_are_not_enough_if_both_are_wrong(self):
        env, rows = benchmark()
        for row in rows:
            row['sha256'] = '0' * 64
        with self.assertRaises(ValueError):
            report.validate_benchmark(bench_text(env, rows), SHA, RUN)

    def test_exact_bytes_checksum_types_and_allocation_reduction_are_required(self):
        env, rows = benchmark()
        for name, value in (('checksum', 1), ('capacity', 1), ('iterations', 0), ('pair', False),
                            ('elapsedNanos', float('nan')), ('allocatedBytes', None),
                            ('retainedBytes', report.CAPACITY - 1)):
            changed = copy.deepcopy(rows)
            changed[0][name] = value
            with self.subTest(name=name), self.assertRaises(ValueError):
                report.validate_benchmark(bench_text(env, changed), SHA, RUN)
        for row in rows:
            if row['arm'] == 'ring' and row['phase'] == 'measurement':
                row['allocatedBytes'] = 600000 * row['iterations']
        with self.assertRaises(ValueError):
            report.validate_benchmark(bench_text(env, rows), SHA, RUN)

    def test_slow_ring_timing_is_reported_not_filtered_for_a_good_result(self):
        env, rows = benchmark()
        for row in rows:
            if row['arm'] == 'ring':
                row['elapsedNanos'] *= 100
        result = report.validate_benchmark(bench_text(env, rows), SHA, RUN)
        self.assertGreater(result['summary']['smallRead']['ring']['nsPerOperation'],
                           result['summary']['smallRead']['legacy']['nsPerOperation'])

    def test_jvm_source_and_run_cannot_be_mixed(self):
        env, rows = benchmark()
        for name, value in (('sourceSha', '2' * 40), ('runId', '12346'), ('suite', 'other'), ('environment', {})):
            changed = dict(env, **{name: value})
            with self.subTest(name=name), self.assertRaises(ValueError):
                report.validate_benchmark(bench_text(changed, rows), SHA, RUN)

    def test_three_real_pty_rounds_have_known_byte_counts_and_hashes(self):
        rows, final = report.pty_expected()
        result = report.validate_pty(pty_text(rows, final))
        self.assertEqual(1365059, final['totalBytes'])
        self.assertEqual([455029, 910044, 1365059], [row['totalBytes'] for row in rows])
        self.assertEqual([46, 91, 136], [row['readChecks'] for row in rows])
        self.assertEqual(137, result['final']['readChecks'])
        self.assertTrue(all(row['payloadBytes'] == 455015 for row in rows))

    def test_pty_rejects_partial_duplicate_reordered_or_incorrect_tail(self):
        rows, final = report.pty_expected()
        for bad in (rows[:-1], rows + rows[:1], list(reversed(rows))):
            with self.assertRaises(ValueError):
                report.validate_pty(pty_text(bad, final))
        for field, value in (('sha256', '0' * 64), ('baseOffset', 0), ('backend', 'pipe'),
                             ('historyRows', 24), ('readChecks', 1), ('retainedBytes', True)):
            changed = copy.deepcopy(rows)
            changed[1][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                report.validate_pty(pty_text(changed, final))

    def test_pty_requires_successful_exit_and_removed_session(self):
        rows, final = report.pty_expected()
        for name, value in (('exitCode', 1), ('removed', False), ('removed', 1), ('readChecks', 136), ('totalBytes', 0)):
            with self.subTest(name=name), self.assertRaises(ValueError):
                report.validate_pty(pty_text(rows, dict(final, **{name: value})))

    def test_missing_or_skipped_benchmark_junit_is_not_measurement_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                report.from_junit(root)
            path = root / ('TEST-' + report.BENCH_CLASS + '.xml')
            path.write_text(f'<testsuite><testcase classname="{report.BENCH_CLASS}" name="one"/>'
                            '<system-out>hello</system-out></testsuite>')
            self.assertEqual('hello', report.from_junit(root))
            path.write_text(f'<testsuite><testcase classname="{report.BENCH_CLASS}"><skipped/></testcase></testsuite>')
            with self.assertRaises(ValueError):
                report.from_junit(root)

    def test_frozen_reference_is_verified_and_pipeline_includes_new_sources(self):
        proof = report.source_proof()
        self.assertTrue(proof['oracleVerified'])
        spec = importlib.util.spec_from_file_location('ring_pipeline', Path(__file__).with_name('run-terminal-pipeline.py'))
        pipeline = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pipeline)
        sources = pipeline.sources()
        self.assertTrue(any(name.endswith('TerminalByteRingBuffer.kt') for name in sources))
        self.assertTrue(any(name.endswith('TerminalByteBufferInstrumentedTest.kt') for name in sources))
        workflow = (report.ROOT / '.github/workflows/terminal-emulator-tests.yml').read_text()
        for cls in ('TerminalByteRingBufferTest', 'TerminalByteBufferWiringTest', 'TerminalByteBufferBenchmarkTest'):
            self.assertIn('--tests ' + report.PACKAGE + cls, workflow)
            self.assertIn('--require-suite ' + report.PACKAGE + cls, workflow)


if __name__ == '__main__':
    unittest.main()
