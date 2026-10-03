import json
import os
import tempfile
import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

import production_ci as ci


class ProductionCiTest(unittest.TestCase):
    def test_install_separates_transfer_and_guest_package_manager(self):
        with tempfile.TemporaryDirectory() as directory:
            def complete(args, **kwargs):
                kwargs['stdout'].write('Success\n' if 'pm' in args else '1 file pushed\n')
                return subprocess.CompletedProcess(args, 0)
            with patch.object(ci.subprocess, 'run', side_effect=complete) as run:
                ci.install_apk('target', Path(directory))
                calls = [call.args[0] for call in run.call_args_list]
                self.assertEqual(['adb', 'push'], calls[0][:2])
                self.assertEqual(['adb', 'shell', 'pm', 'install', '-r', '-t'], calls[1][:6])
                self.assertEqual(calls[0][-1], calls[1][-1])
            self.assertTrue(Path(directory, 'target-install.log').exists())

    def test_transfer_timeout_prevents_install_and_preserves_phase(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(ci.subprocess, 'run', side_effect=subprocess.TimeoutExpired('adb', 180)) as run:
                with self.assertRaisesRegex(ValueError, 'target transfer: TimeoutExpired'):
                    ci.install_apk('target', Path(directory))
                self.assertEqual(1, run.call_count)

    def test_package_manager_rejection_is_not_treated_as_successful_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            def reject(args, **kwargs):
                kwargs['stdout'].write('Failure [INSTALL_FAILED_TEST_ONLY]\n' if 'pm' in args else 'pushed\n')
                return subprocess.CompletedProcess(args, 0)
            with patch.object(ci.subprocess, 'run', side_effect=reject):
                with self.assertRaisesRegex(ValueError, 'INSTALL_FAILED_TEST_ONLY'):
                    ci.install_apk('target', Path(directory))

    def test_native_runner_must_finish_the_exact_case_count_without_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory, 'run.log')
            good = 'Time: 5.0\nOK (6 tests)\n\nINSTRUMENTATION_CODE: -1\n'
            log.write_text(good)
            ci.require_instrumentation_success(log, 6)
            for bad in (good.replace('6 tests', '5 tests'), good.replace('-1', '0'),
                        good + 'INSTRUMENTATION_FAILED', good + 'FAILURES!!!',
                        good + 'INSTRUMENTATION_RESULT: shortMsg=Process crashed'):
                log.write_text(bad)
                with self.subTest(bad=bad), self.assertRaises(ValueError):
                    ci.require_instrumentation_success(log, 6)

    def test_source_manifest_covers_production_rendering_controller_measurement_and_font(self):
        sources = ci.source_files()
        for suffix in ('TerminalViewportBinding.kt', 'TerminalTranscriptViewport.kt',
                       'TerminalLazyItemMeasurements.kt', 'TerminalTranscriptWidth.kt',
                       'TerminalViewportController.kt', 'TerminalEagerGeometryCache.kt', 'jetbrains_mono.ttf'):
            matches = [value for path, value in sources.items() if path.endswith(suffix)]
            self.assertEqual(1, len(matches), suffix)
            self.assertEqual(64, len(matches[0]))

    def test_bundle_checks_both_apks_and_exact_source_hashes(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '123'}):
            root = Path(directory)
            apks = {}
            for role in ('target', 'driver'):
                path = root / f'{role}.apk'
                path.write_bytes(role.encode())
                apks[role] = {'file': path.name, 'sha256': ci.digest(path), 'bytes': path.stat().st_size}
            manifest = {'sourceSha': 'a' * 40, 'buildRun': '123', 'suite': ci.VERSION,
                        'sources': {'production.kt': 'hash'}, 'apks': apks}
            path = root / 'manifest.json'
            path.write_text(json.dumps(manifest))
            with patch.object(ci, 'BUNDLE', root), patch.object(ci, 'source_files', return_value={'production.kt': 'hash'}):
                self.assertEqual(manifest, ci.verify_bundle())
                for field in ('sourceSha', 'buildRun', 'suite', 'sources'):
                    broken = dict(manifest, **{field: 'wrong'})
                    path.write_text(json.dumps(broken))
                    with self.subTest(field=field), self.assertRaises(ValueError):
                        ci.verify_bundle()
                path.write_text(json.dumps(manifest))
                for role in ('target', 'driver'):
                    apk = root / f'{role}.apk'
                    apk.write_bytes(b'changed')
                    with self.subTest(role=role), self.assertRaises(ValueError):
                        ci.verify_bundle()
                    apk.write_bytes(role.encode())

    def test_missing_identity_fails_before_running_device_commands(self):
        with patch.dict(os.environ, {'GITHUB_SHA': 'unknown', 'GITHUB_RUN_ID': '123'}):
            with self.assertRaises(ValueError):
                ci.env_identity()


if __name__ == '__main__':
    unittest.main()
