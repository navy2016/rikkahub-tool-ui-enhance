"""Accept exact paired subscription work only after all lifecycle/identity tests pass."""
import base64
import hashlib
import json
import os
import re
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST_ROOT = ROOT / 'benchmarks/terminal-viewport-tests'
CLASS = 'me.rerere.rikkahub.viewporttest.TerminalPanelSubscriptionInstrumentedTest'
CASES = frozenset((
    'panelSubscriptionPairsSuppressAnchorAndGestureRecompositionsButKeepBooleanTransitions',
    'panelSubscriptionControllerReplacementStartsWithItsOwnStateAndDetachesOldOne',
    'panelSubscriptionLifecycleRestartReadsLatestBooleanWithoutCollectingWhileStopped',
))
EXPECTED = dict(updates=54, legacyCompositions=54, projectedCompositions=0, booleanTransitions=3)


def validate(records):
    if len(records) != 1 or not isinstance(records[0], dict) or set(records[0]) != set(EXPECTED):
        raise ValueError('Expected exactly one complete paired panel-subscription record')
    for name, expected in EXPECTED.items():
        if type(records[0][name]) is not int or records[0][name] != expected:
            raise ValueError('Incorrect paired panel-subscription work: ' + name)
    return records[0]


def require_junit(folder):
    found = set()
    for path in folder.rglob('TEST-*.xml'):
        for case in ET.parse(path).getroot().iter('testcase'):
            if case.get('classname') != CLASS:
                continue
            name = case.get('name')
            if name not in CASES or name in found:
                raise ValueError('Unexpected or duplicated panel-subscription JUnit case')
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('Panel-subscription test did not pass: ' + name)
            found.add(name)
    if found != CASES:
        raise ValueError(f'Incomplete panel-subscription JUnit matrix: {len(found)}/3')
    return sorted(found)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def notice(title, text, level='notice'):
    if len(text) > 2900:
        raise ValueError('Panel-subscription annotation exceeds size limit')
    text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::{level} title={title}::{text}')


def main():
    sha, run_id = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    if not re.fullmatch('[a-f0-9]{40}', sha) or not re.fullmatch('[0-9]+', run_id):
        raise ValueError('Expected exact CI source and run identity')
    junit = require_junit(TEST_ROOT / 'build/outputs/androidTest-results')
    log = ROOT / 'artifacts/terminal-validation/gesture-logcat.txt'
    with log.open() as stream:
        records = [json.loads(line.split('FOLLOW_WORK ', 1)[1]) for line in stream if 'FOLLOW_WORK ' in line]
    sample = validate(records)
    copied = (
        'me/rerere/rikkahub/ui/pages/container/TerminalViewportFollowState.kt',
        'me/rerere/rikkahub/data/container/TerminalViewportController.kt',
        'me/rerere/rikkahub/data/container/TerminalViewportState.kt',
        'me/rerere/rikkahub/data/container/TerminalViewportReducer.kt',
    )
    for path in copied:
        if digest(ROOT / 'app/src/main/java' / path) != digest(TEST_ROOT / 'build/generated/viewportSources' / path):
            raise ValueError('Fixture differs from production subscription source: ' + path)
    paths = ['app/src/main/java/' + path for path in copied] + [
        'app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt',
        'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalPanelSubscriptionInstrumentedTest.kt',
        'benchmarks/terminal-viewport-tests/build.gradle.kts',
        'benchmarks/terminal-viewport-tests/run-ci.sh',
        '.github/scripts/report-terminal-panel-subscription.py',
        '.github/workflows/terminal-viewport-interactions.yml',
        'gradle/libs.versions.toml',
    ]
    hashes = {path: digest(ROOT / path) for path in paths}
    apks = {}
    for role, folder in (('fixture', 'release'), ('tests', 'androidTest/release')):
        matches = list((TEST_ROOT / 'build/outputs/apk' / folder).glob('*.apk'))
        if len(matches) != 1:
            raise ValueError('Expected exactly one panel-subscription ' + role + ' APK')
        apks[role] = digest(matches[0])
    report = dict(suite='terminal-panel-subscription-v1', sourceSha=sha, runId=run_id,
                  sourceHashes=hashes, apkHashes=apks, junit=junit, sample=sample,
                  note='Both subscriptions observe the same controller in one mounted Compose test. '
                       'Each anchor/gesture update settles separately. Counts exclude mounting. '
                       'This is eliminated subscription work, not full-page latency or phone FPS.')
    (ROOT / 'artifacts/terminal-validation/panel-subscription.json').write_text(json.dumps(report, indent=2) + '\n')
    notice('Terminal panel subscription verified', json.dumps(sample))
    data = json.dumps(report, separators=(',', ':')).encode()
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    if len(parts) > 8:
        raise ValueError('Panel-subscription evidence exceeds annotation budget')
    for index, part in enumerate(parts, 1):
        notice(f'Terminal panel subscription evidence {index}/{len(parts)}',
               'zlib-base64 sha256=' + hashlib.sha256(data).hexdigest() + '\n' + part)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        notice('Terminal panel subscription rejected', str(error)[:2800], level='error')
        raise SystemExit(1)
