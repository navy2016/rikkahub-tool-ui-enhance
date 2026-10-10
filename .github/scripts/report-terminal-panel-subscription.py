"""Accept exact paired subscription work only after all lifecycle/identity tests pass."""
import argparse
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
BINDING_CLASS = 'me.rerere.rikkahub.viewporttest.TerminalBindingProjectionInstrumentedTest'
BINDING_CASES = frozenset((
    'bindingProjectionLayoutChangesKeepActualBottomWithoutRecomposingPanel',
    'bindingProjectionUnrelatedRecompositionsRetainBoundObjectsAndSupplierPolicyStaysLive',
    'bindingProjectionTuiFontFrameAndControllerChangesInvalidateTheCorrectObjects',
))


def validate(records):
    if len(records) != 1 or not isinstance(records[0], dict) or set(records[0]) != set(EXPECTED):
        raise ValueError('Expected exactly one complete paired panel-subscription record')
    for name, expected in EXPECTED.items():
        if type(records[0][name]) is not int or records[0][name] != expected:
            raise ValueError('Incorrect paired panel-subscription work: ' + name)
    return records[0]


def validate_binding(records):
    expected = dict(layoutChanges=24, projectedCompositions=0, bottomChecks=24, bindingStable=True)
    if len(records) != 1 or not isinstance(records[0], dict) or set(records[0]) != {*expected, 'legacyCompositions'}:
        raise ValueError('Expected exactly one complete binding projection record')
    row = records[0]
    for key, value in expected.items():
        if type(row[key]) is not type(value) or row[key] != value:
            raise ValueError('Invalid binding projection work: ' + key)
    if type(row['legacyCompositions']) is not int or row['legacyCompositions'] < 24:
        raise ValueError('Legacy full-metric subscription was not exercised')
    return row


def require_junit(folder, test_class=CLASS, cases=CASES):
    found = set()
    for path in folder.rglob('TEST-*.xml'):
        for case in ET.parse(path).getroot().iter('testcase'):
            if case.get('classname') != test_class:
                continue
            name = case.get('name')
            if name not in cases or name in found:
                raise ValueError('Unexpected or duplicated panel-subscription JUnit case')
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('Panel-subscription test did not pass: ' + name)
            found.add(name)
    if found != cases:
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


def main(binding=False):
    sha, run_id = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    if not re.fullmatch('[a-f0-9]{40}', sha) or not re.fullmatch('[0-9]+', run_id):
        raise ValueError('Expected exact CI source and run identity')
    junit = require_junit(TEST_ROOT / 'build/outputs/androidTest-results',
                          BINDING_CLASS if binding else CLASS, BINDING_CASES if binding else CASES)
    log = ROOT / 'artifacts/terminal-validation/gesture-logcat.txt'
    marker = 'BINDING_WORK ' if binding else 'FOLLOW_WORK '
    with log.open() as stream:
        records = [json.loads(line.split(marker, 1)[1]) for line in stream if marker in line]
    sample = validate_binding(records) if binding else validate(records)
    copied = (
        'me/rerere/rikkahub/ui/pages/container/TerminalViewportFollowState.kt',
        'me/rerere/rikkahub/data/container/TerminalViewportController.kt',
        'me/rerere/rikkahub/data/container/TerminalViewportState.kt',
        'me/rerere/rikkahub/data/container/TerminalViewportReducer.kt',
    )
    if binding:
        copied += ('me/rerere/rikkahub/ui/pages/container/TerminalViewportBinding.kt',
                   'me/rerere/rikkahub/ui/pages/container/TerminalLazyItemMeasurements.kt',
                   'me/rerere/rikkahub/ui/pages/container/TerminalTranscriptWidth.kt',
                   'me/rerere/rikkahub/ui/pages/container/TerminalTranscriptViewport.kt')
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
    if binding:
        paths.append('benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalBindingProjectionInstrumentedTest.kt')
    hashes = {path: digest(ROOT / path) for path in paths}
    apks = {}
    for role, folder in (('fixture', 'release'), ('tests', 'androidTest/release')):
        matches = list((TEST_ROOT / 'build/outputs/apk' / folder).glob('*.apk'))
        if len(matches) != 1:
            raise ValueError('Expected exactly one panel-subscription ' + role + ' APK')
        apks[role] = digest(matches[0])
    label = 'binding projection' if binding else 'panel subscription'
    suite = 'terminal-binding-projection-v1' if binding else 'terminal-panel-subscription-v1'
    report = dict(suite=suite, sourceSha=sha, runId=run_id,
                  sourceHashes=hashes, apkHashes=apks, junit=junit, sample=sample,
                  note='Both subscriptions observe the same controller in one mounted Compose test. '
                       'Each anchor/gesture update settles separately. Counts exclude mounting. '
                       'This is eliminated subscription work, not full-page latency or phone FPS.')
    if binding:
        report['note'] = ('Legacy reads the full metrics supplier in composition; the actual production binding projects '
                          'only TUI policy. 24 real Text viewport resizes preserve the visible last row without '
                          'recomposing the panel. Counts exclude mounting; this is not old/new full-page timing.')
    name = 'binding-projection.json' if binding else 'panel-subscription.json'
    (ROOT / 'artifacts/terminal-validation' / name).write_text(json.dumps(report, indent=2) + '\n')
    notice('Terminal ' + label + ' verified', json.dumps(sample))
    data = json.dumps(report, separators=(',', ':')).encode()
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    if len(parts) > 8:
        raise ValueError('Panel-subscription evidence exceeds annotation budget')
    for index, part in enumerate(parts, 1):
        notice(f'Terminal {label} evidence {index}/{len(parts)}',
               'zlib-base64 sha256=' + hashlib.sha256(data).hexdigest() + '\n' + part)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binding', action='store_true')
    args = parser.parse_args()
    try:
        main(args.binding)
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        notice('Terminal panel subscription rejected', str(error)[:2800], level='error')
        raise SystemExit(1)
