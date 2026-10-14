import base64
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
import zlib
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('export_v5', Path(__file__).with_name('export-terminal-v5-evidence.py'))
EXPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXPORT)


class V5EvidenceExportTest(unittest.TestCase):
    def source(self, root):
        folder = root / 'scenario'
        folder.mkdir()
        request = dict(sourceSha='a' * 40, runId='123', scenario='initialCompose', suite='production-viewport-v5')
        (folder / 'request.json').write_text(json.dumps(request))
        (folder / 'manifest.json').write_text(json.dumps(dict(sourceSha='a' * 40, buildRun='123')))
        (folder / 'instrumentation.log').write_text(
            'private diagnostic not exported\nPUBLICATION_WORK {"phase":"mounted"}\n'
            'MEASURED_LAUNCH {"iteration":0}\nOK (9 tests)\nINSTRUMENTATION_CODE: -1\n')
        (folder / 'x-benchmarkData.json').write_text('{"benchmarks": [], "context": {}}\n')
        return folder

    def test_exact_json_and_markers_survive_without_unrelated_log_text(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = self.source(root)
            EXPORT.prepare(root, root / 'out', 'a' * 40, '123', 'initialCompose')
            record = json.loads((root / 'out/data.json').read_text())['groups'][0]
            self.assertEqual((source / 'x-benchmarkData.json').read_text(),
                             record['rawDocuments']['x-benchmarkData.json'])
            self.assertEqual(['OK (9 tests)', 'INSTRUMENTATION_CODE: -1'], record['instrumentationResult'])
            self.assertEqual(2, len(record['publicationRecords']))
            self.assertNotIn('private diagnostic', json.dumps(record))
            self.assertIsNone(record['extra']['validated.json'])

    def test_export_roundtrips_bytes_and_hash_and_has_bounded_parts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.source(root)
            EXPORT.prepare(root, root / 'out', 'a' * 40, '123', 'initialCompose')
            parts = json.loads((root / 'out/parts.json').read_text())
            raw = zlib.decompress(base64.b64decode(''.join(parts['parts']), validate=True))
            self.assertEqual((root / 'out/data.json').read_bytes(), raw)
            self.assertEqual(EXPORT.hashlib.sha256(raw).hexdigest(), parts['sha256'])
            self.assertTrue(all(len(part) <= 2800 for part in parts['parts']))
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                for batch in range(4):
                    EXPORT.emit(root / 'out', 'initialCompose', batch)
            self.assertEqual(len(parts['parts']), len(output.getvalue().splitlines()))

    def test_wrong_source_or_empty_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, 'No matching'):
                EXPORT.prepare(root, root / 'out', 'a' * 40, '123', 'initialCompose')
            self.source(root)
            for sha, run_id, scenario in (('b' * 40, '123', 'initialCompose'),
                                          ('a' * 40, '124', 'initialCompose'), ('a' * 40, '123', 'semanticJump')):
                with self.assertRaisesRegex(ValueError, 'source mismatch'):
                    EXPORT.prepare(root, root / 'out', sha, run_id, scenario)


if __name__ == '__main__':
    unittest.main()
