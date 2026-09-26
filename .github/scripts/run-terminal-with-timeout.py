#!/usr/bin/env python3
"""Run terminal validation with a bounded process-group lifetime."""
from __future__ import annotations

import os
import signal
import subprocess
import sys
from pathlib import Path


TERM_GRACE_SECONDS = 30
DEFAULT_LOG_PATH = Path("artifacts/terminal-validation/instrumentation-command.log")


def publish_error(title: str, message: str) -> None:
    escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title={title}::{escaped}", flush=True)


def publish_failure_log(log_path: Path, return_code: int) -> None:
    try:
        text = log_path.read_text(errors="replace")
    except OSError as error:
        text = f"Unable to read {log_path}: {error}"
    tail = "\n".join(text.splitlines()[-80:])[-2_700:]
    logcat_path = Path("artifacts/terminal-validation/gesture-logcat.txt")
    probe_lines: list[str] = []
    try:
        all_probe_lines = [
            line for line in logcat_path.read_text(errors="replace").splitlines()
            if "TerminalViewportProbe" in line and "lazy=true" in line
        ]
        probe_lines = all_probe_lines[-80:]
    except OSError:
        pass
    publish_error(
        "Terminal instrumentation command failed",
        f"Instrumentation command exited {return_code}.\n{tail}",
    )
    # A combined Gradle tail + logcat exceeded the check-run API's 4096-character message
    # limit and hid the LAST checkpoint (even cutting a diagnostics line in the middle).
    # Publish probes separately, in bounded chunks, including failure/main-thread stacks.
    probes = "\n".join(probe_lines)
    for offset in range(0, len(probes), 1_000):
        publish_error(f"Terminal viewport probes {offset // 1_000 + 1}", probes[offset:offset + 1_000])


def kill_group(process: subprocess.Popen[object], sig: signal.Signals) -> None:
    try:
        os.killpg(process.pid, sig)
    except ProcessLookupError:
        pass


def main() -> int:
    if len(sys.argv) < 3:
        print(
            "usage: run-terminal-with-timeout.py TIMEOUT_SECONDS COMMAND [ARGS...]",
            file=sys.stderr,
        )
        return 2

    try:
        timeout_seconds = float(sys.argv[1])
    except ValueError:
        print(f"invalid timeout: {sys.argv[1]}", file=sys.stderr)
        return 2
    if timeout_seconds <= 0:
        print("timeout must be positive", file=sys.stderr)
        return 2

    log_path = Path(os.environ.get("TERMINAL_TIMEOUT_LOG_PATH", DEFAULT_LOG_PATH))
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8", errors="replace") as log:
        process = subprocess.Popen(
            sys.argv[2:],
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            return_code = process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            print(
                f"Terminal validation exceeded {timeout_seconds:g}s; sending SIGTERM",
                file=sys.stderr,
                flush=True,
            )
            kill_group(process, signal.SIGTERM)
            try:
                process.wait(timeout=TERM_GRACE_SECONDS)
            except subprocess.TimeoutExpired:
                print("Terminal validation did not exit after SIGTERM; sending SIGKILL", file=sys.stderr)
                kill_group(process, signal.SIGKILL)
                process.wait()
            return_code = 124

    if return_code != 0:
        publish_failure_log(log_path, return_code)
    return return_code


if __name__ == "__main__":
    raise SystemExit(main())
