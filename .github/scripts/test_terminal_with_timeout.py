import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().with_name("run-terminal-with-timeout.py")


class RunTerminalWithTimeoutTest(unittest.TestCase):
    def run_script(self, seconds, *command):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(seconds), *command],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_preserves_success_exit_code(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "2", sys.executable, "-c", "print('ok')"],
                cwd=directory,
                text=True,
                capture_output=True,
                check=False,
            )
            log = Path(directory) / "artifacts/terminal-validation/instrumentation-command.log"
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("ok\n", log.read_text())

    def test_converts_timeout_to_bounded_failure(self):
        result = self.run_script(0.1, sys.executable, "-c", "import time; time.sleep(10)")
        self.assertEqual(124, result.returncode)
        self.assertIn("exceeded 0.1s", result.stderr)

    def test_publishes_child_log_when_command_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "2", sys.executable, "-c",
                 "import sys; print('adb diagnostic'); sys.exit(1)"],
                cwd=directory,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(1, result.returncode)
            self.assertIn("::error title=Terminal instrumentation command failed::", result.stdout)
            self.assertIn("adb diagnostic", result.stdout)


if __name__ == "__main__":
    unittest.main()
