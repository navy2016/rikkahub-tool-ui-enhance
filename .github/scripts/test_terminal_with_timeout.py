import subprocess
import sys
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("run-terminal-with-timeout.py")


class RunTerminalWithTimeoutTest(unittest.TestCase):
    def run_script(self, seconds, *command):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(seconds), *command],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_preserves_success_exit_code(self):
        result = self.run_script(2, sys.executable, "-c", "print('ok')")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("ok\n", result.stdout)

    def test_converts_timeout_to_bounded_failure(self):
        result = self.run_script(0.1, sys.executable, "-c", "import time; time.sleep(10)")
        self.assertEqual(124, result.returncode)
        self.assertIn("exceeded 0.1s", result.stderr)


if __name__ == "__main__":
    unittest.main()
