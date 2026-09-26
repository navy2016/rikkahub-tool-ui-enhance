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

    def test_long_child_log_cannot_truncate_last_lazy_checkpoint_or_stack(self):
        with tempfile.TemporaryDirectory() as directory:
            logcat = Path(directory) / "artifacts/terminal-validation/gesture-logcat.txt"
            logcat.parent.mkdir(parents=True)
            probe_lines = [
                "I TerminalViewportProbe: slow swipe 1 clock begin lazy=true",
                *[f"E TerminalViewportProbe: main stack lazy=true at method{i} " + "x" * 120
                  for i in range(40)],
                "I TerminalViewportProbe: JUnit finished lazy=true case=slowSwipes",
            ]
            logcat.write_text(
                "I TerminalViewportProbe: eager-only checkpoint lazy=false\n" + "\n".join(probe_lines)
            )
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "2", sys.executable, "-c",
                 "import sys; print('gradle task output\\n' * 400); sys.exit(1)"],
                cwd=directory, text=True, capture_output=True, check=False,
            )
            self.assertEqual(1, result.returncode, result.stderr)
            annotations = result.stdout.splitlines()
            probes = [line.split("::", 2)[2] for line in annotations
                      if line.startswith("::error title=Terminal viewport probes")]
            self.assertGreater(len(probes), 1)
            decoded = "".join(probes).replace("%0A", "\n").replace("%0D", "\r").replace("%25", "%")
            self.assertEqual("\n".join(probe_lines), decoded)
            self.assertNotIn("eager-only", decoded)
            for line in annotations:
                self.assertLess(len(line), 4_096, line)


    def test_probe_annotation_count_and_bytes_preserve_the_last_checkpoint(self):
        with tempfile.TemporaryDirectory() as directory:
            logcat = Path(directory) / "artifacts/terminal-validation/gesture-logcat.txt"
            logcat.parent.mkdir(parents=True)
            final = "TerminalViewportProbe: JUnit finished lazy=true case=trimmedAnchor"
            logcat.write_text("\n".join([
                *["TerminalViewportProbe: main stack lazy=true " + "终端%" * 600 for _ in range(60)],
                final,
            ]))
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "2", sys.executable, "-c", "raise SystemExit(1)"],
                cwd=directory, text=True, capture_output=True, check=False,
            )
            self.assertEqual(1, result.returncode, result.stderr)
            annotations = result.stdout.splitlines()
            self.assertLessEqual(len(annotations), 9)
            self.assertIn(final, annotations[-1])
            for line in annotations:
                self.assertLess(len(line.encode("utf-8")), 4_096)
                self.assertNotIn("\ufffd", line)


if __name__ == "__main__":
    unittest.main()
