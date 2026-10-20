"""CI-only negative arm: old production uses API-compatible test helpers, with the same rectangle oracle."""
import hashlib
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
COMPAT = '7218dd7e50577bc6fa94575aa476b2a436c71f61'
TESTS = 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/'
ORACLES = (TESTS + 'TerminalBottomVisibilityInstrumentedTest.kt', TESTS + 'TerminalDefaultBottomInstrumentedTest.kt')


def git(*args):
    return subprocess.check_output(['git', '-c', 'core.fileMode=false', *args], cwd=ROOT, timeout=30)


def main():
    assert os.environ.get('GITHUB_ACTIONS') == 'true'
    assert os.environ.get('GITHUB_REF') == 'refs/heads/opt/terminal-verified-a79e7b6'
    assert os.environ.get('TERMINAL_BOTTOM_CONTROL') == 'true' or os.environ.get('TERMINAL_OLD_DEFAULT') == 'true'
    oracles = {name: (ROOT / name).read_bytes() for name in ORACLES}
    assert all(raw == git('show', COMPAT + ':' + name) for name, raw in oracles.items()), 'Rectangle oracle changed; require explicit review'
    compatible = git('ls-tree', '-r', '--name-only', COMPAT, '--', TESTS).decode().splitlines()
    assert compatible and all(name.startswith(TESTS) and name.endswith('.kt') for name in compatible)
    assert not any('TerminalLongHistoryDiagnosticTest' in name or 'TerminalTuiHistoryInstrumentedTest' in name
                   for name in compatible), 'Candidate-only diagnostics must not compile against the old geometry API'
    for path in (ROOT / TESTS).glob('*.kt'):
        if str(path.relative_to(ROOT)) not in compatible:
            path.unlink()  # Disposable runner only: new candidate-only types do not exist in old production.
    for name in compatible:
        (ROOT / name).write_bytes(git('show', COMPAT + ':' + name))
    assert all((ROOT / name).read_bytes() == raw for name, raw in oracles.items())
    print('::notice title=Negative control test API compatibility::helpers=' + COMPAT +
          '; exact unchanged rectangle oracle sha256=' + hashlib.sha256(oracles[ORACLES[0]]).hexdigest())


if __name__ == '__main__':
    main()
