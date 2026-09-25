#!/usr/bin/env python3
"""Run terminal validation with a bounded process-group lifetime."""
from __future__ import annotations

import os
import signal
import subprocess
import sys


TERM_GRACE_SECONDS = 30


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

    process = subprocess.Popen(sys.argv[2:], start_new_session=True)
    try:
        return process.wait(timeout=timeout_seconds)
    except subprocess.TimeoutExpired:
        print(
            f"Terminal validation exceeded {timeout_seconds:g}s; sending SIGTERM",
            file=sys.stderr,
            flush=True,
        )
        kill_group(process, signal.SIGTERM)
        try:
            process.wait(timeout=TERM_GRACE_SECONDS)
            return 124
        except subprocess.TimeoutExpired:
            print("Terminal validation did not exit after SIGTERM; sending SIGKILL", file=sys.stderr)
            kill_group(process, signal.SIGKILL)
            process.wait()
            return 124


if __name__ == "__main__":
    raise SystemExit(main())
