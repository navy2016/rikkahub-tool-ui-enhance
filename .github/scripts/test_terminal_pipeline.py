import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('terminal_pipeline', Path(__file__).with_name('run-terminal-pipeline.py'))
pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pipeline)


def rows():
    result = []
    for index, mode in enumerate(pipeline.MODES):
        for phase in pipeline.PHASES:
            for repeat in range(3):
                row = dict(mode=mode, phase=phase, iteration=repeat,
                           launch=f'00000000-0000-4000-8000-{index:012x}', frameRevision=12,
                           virtual=mode == 'lazyHistoryIme' or (mode == 'lazyHistory' and phase in ('mounted', 'remount')),
                           outputBytes=len(f'ECHO:{phase}-{repeat}\r\n'.encode()),
                           inputToDrawMs=8.0, inputToVisibleCheckMs=10.0)
                row.update({name: n + 2.0 for n, name in enumerate(pipeline.STAGES)})
                result.append(row)
    return result


def validate(values):
    return pipeline.validate_samples(['tag: PIPELINE_SAMPLE ' + json.dumps(row) for row in values])


class TerminalPipelineValidationTest(unittest.TestCase):
    def test_complete_matrix_is_exactly_36_samples(self):
        self.assertEqual(36, len(validate(rows())))

    def test_partial_or_duplicate_matrix_is_not_evidence(self):
        for values in (rows()[:-1], rows() + rows()[:1]):
            with self.assertRaises(ValueError):
                validate(values)

    def test_wrong_backend_identity_bytes_and_frame_are_rejected(self):
        for field, value in (('virtual', True), ('virtual', 0), ('launch', 'stale'), ('outputBytes', 10),
                             ('frameRevision', -1), ('iteration', True), ('mode', 'other')):
            values = rows()
            values[0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate(values)

    def test_nonfinite_missing_and_reversed_timestamps_fail(self):
        for value in (float('nan'), float('inf'), -1, True, None, 500):
            values = rows()
            values[0]['INPUT_ENQUEUE_STARTED'] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate(values)
        values = rows()
        del values[0]['FRAME_DRAWN']
        with self.assertRaises(ValueError):
            validate(values)

    def test_mixed_launches_and_shared_launches_fail(self):
        values = rows()
        values[1]['launch'] = 'ffffffff-ffff-4fff-8fff-ffffffffffff'
        with self.assertRaises(ValueError):
            validate(values)
        values = rows()
        for row in values:
            row['launch'] = values[0]['launch']
        with self.assertRaises(ValueError):
            validate(values)

    def test_success_marker_requires_three_tests_without_skips_or_failures(self):
        good = 'OK (3 tests)\n\nINSTRUMENTATION_CODE: -1\n'
        pipeline.require_instrumentation(good)
        for bad in (good.replace('3 tests', '2 tests'), good.replace('-1', '-10'), good + 'FAILURES!!!',
                    good + 'INSTRUMENTATION_STATUS_CODE: -3', good + 'shortMsg=crashed'):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                pipeline.require_instrumentation(bad)

    def test_sources_cover_real_page_session_native_io_and_trace(self):
        names = pipeline.sources()
        for suffix in ('ProcessSessionPage.kt', 'BackgroundProcessManager.kt', 'rikkahub_pty.cpp',
                       'TerminalPipelineTrace.kt', 'TerminalPipelineInstrumentedTest.kt'):
            self.assertTrue(any(name.endswith(suffix) for name in names), suffix)

    def test_failure_excerpt_preserves_initial_error_and_lifecycle_with_bounded_output(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'fixture.log'
            path.write_text('progress\n' * 1000 + 'stack=java.lang.IllegalStateException: first cause\n'
                            + 'at method\n' * 1000 + 'TerminalPipelineActivity paused finishing=false\n'
                            + 'tail\n' * 1000)
            text = pipeline.failure_excerpt(path)
            self.assertIn('first cause', text)
            self.assertIn('paused finishing=false', text)
            self.assertLess(len(text), 2400)


if __name__ == '__main__':
    unittest.main()
