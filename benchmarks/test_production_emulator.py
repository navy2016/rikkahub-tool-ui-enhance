import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import production_emulator as emulator


class ProductionEmulatorTest(unittest.TestCase):
    def test_launch_uses_runner_uid_and_existing_group_without_software_fallback(self):
        env = {name: name.lower() for name in ('HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT',
                                             'ANDROID_AVD_HOME', 'ANDROID_USER_HOME')}
        env['GITHUB_TOKEN'] = 'never-pass-this'
        command = emulator.group_command(Path('/sdk/emulator'), ['-accel', 'on'], env, 'runner')
        self.assertEqual(['sudo', '-n', '--', 'setpriv', '--reuid=runner', '--regid=kvm',
                          '--init-groups', '--no-new-privs', '--', 'env', '-i'], command[:11])
        self.assertIn('LC_ALL=C', command)
        self.assertEqual(['/sdk/emulator', '-accel', 'on'], command[-3:])
        self.assertNotIn('never-pass-this', str(command))
        self.assertNotIn('--reuid=root', command)
        for user in ('root', ''):
            with self.assertRaises(ValueError):
                emulator.group_command(Path('/sdk/emulator'), [], env, user)

    def test_acceleration_requires_a_successful_positive_kvm_probe(self):
        ok = 'accel:\n0\nKVM (version 12) is installed and usable.\naccel\n'
        emulator.require_acceleration(subprocess.CompletedProcess([], 0, ok))
        for code, text in ((1, ok), (0, 'KVM is not usable'), (0, 'TCG installed'), (0, '')):
            with self.subTest(code=code, text=text), self.assertRaises(ValueError):
                emulator.require_acceleration(subprocess.CompletedProcess([], code, text))

    def test_boot_wait_requires_both_android_boot_and_package_manager(self):
        process = Mock()
        process.poll.return_value = None
        replies = [subprocess.CompletedProcess([], 0, '1\n', ''),
                   subprocess.CompletedProcess([], 0, 'package:/system/framework/framework-res.apk\n', '')]
        with patch.object(emulator.subprocess, 'run', side_effect=replies) as run:
            emulator.wait_for_boot(process, {})
        self.assertEqual(2, run.call_count)

    def test_dead_guest_fails_without_waiting_or_running_a_benchmark(self):
        process = Mock(returncode=1)
        process.poll.return_value = 1
        with patch.object(emulator.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'exited before boot'):
                emulator.wait_for_boot(process, {})
            run.assert_not_called()

    def test_timeout_does_not_accept_a_booted_guest_with_no_package_manager(self):
        process = Mock()
        process.poll.return_value = None
        with patch.object(emulator.time, 'monotonic', side_effect=[0, 1, 100]), \
                patch.object(emulator.time, 'sleep'), \
                patch.object(emulator.subprocess, 'run', side_effect=[
                    subprocess.CompletedProcess([], 0, '1\n', ''),
                    subprocess.CompletedProcess([], 1, '', 'package service missing')]):
            with self.assertRaisesRegex(ValueError, 'PackageManager did not become ready'):
                emulator.wait_for_boot(process, {}, timeout=20)

    def test_setup_failure_keeps_its_compact_output(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory, 'setup.log')
            def fail(args, **kwargs):
                kwargs['stdout'].write('missing system image')
                return subprocess.CompletedProcess(args, 1)
            with patch.object(emulator.subprocess, 'run', side_effect=fail):
                with self.assertRaisesRegex(ValueError, 'missing system image'):
                    emulator.logged(['tool'], log, {}, timeout=10)

    def test_shutdown_is_bounded_and_forces_only_its_child_process_group(self):
        process = Mock(pid=123)
        process.wait.side_effect = [subprocess.TimeoutExpired('emulator', 10), None]
        with patch.object(emulator.subprocess, 'run'), patch.object(emulator.os, 'killpg') as kill:
            emulator.stop_emulator(process, {})
            kill.assert_called_once_with(123, emulator.signal.SIGTERM)


if __name__ == '__main__':
    unittest.main()
