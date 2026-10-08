"""Validate same-device executor geometry/frame-wait pairs; no phone latency claims."""
import argparse
import base64
import hashlib
import json
import os
import re
import statistics
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEST_ROOT = ROOT / 'benchmarks/terminal-viewport-tests'
CLASS = 'me.rerere.rikkahub.viewporttest.TerminalScrollCompletionInstrumentedTest'
CASES = ('historyAnchor', 'screenFollow')
CONTROL_SHA = '1487040d79944661e7083d6f25f4908e4c5ff7f1'
CONTROL_BODY_SHA256 = 'b9251aac034a350d430dd5f0afdb362f039a5cd6059cceb50f6eee6a4817cff6'


def validate(records):
    seen = set()
    for row in records:
        key = (row.get('case'), row.get('pair'), row.get('legacy'))
        if (key[0] not in CASES or type(key[1]) is not int or key[1] not in range(10)
                or type(key[2]) is not bool or key in seen):
            raise ValueError('Duplicate or invalid scroll-completion pair')
        expected_first = (key[1] % 2 == 0) if key[2] else (key[1] % 2 == 1)
        if type(row.get('first')) is not bool or row['first'] != expected_first:
            raise ValueError('Paired execution order did not alternate')
        if type(row.get('waits')) is not int or row['waits'] != int(key[2]):
            raise ValueError('Expected legacy=1, candidate=0 frame waits for synchronous correction')
        if type(row.get('nanos')) is not int or row['nanos'] <= 0:
            raise ValueError('Missing executor elapsed time')
        seen.add(key)
    if seen != {(case, pair, old) for case in CASES for pair in range(10) for old in (False, True)}:
        raise ValueError(f'Incomplete paired samples: {len(seen)}/40')
    return records


def require_junit(folder):
    cases = set()
    for path in folder.rglob('TEST-*.xml'):
        for case in ET.parse(path).getroot().iter('testcase'):
            if case.get('classname') != CLASS:
                continue
            name = case.get('name')
            if name in cases or not name or not name.startswith('scrollCompletion'):
                raise ValueError('Invalid or duplicated scroll-completion JUnit result')
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('Scroll-completion test did not pass: ' + name)
            cases.add(name)
    if len(cases) != 7:
        raise ValueError(f'Expected 7 successful scroll-completion cases, found {len(cases)}')
    return sorted(cases)


def notice(title, text, level='notice'):
    text = text[:2900].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::{level} title={title}::{text}')


def main():
    sha, run_id = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    if not re.fullmatch('[a-f0-9]{40}', sha) or not re.fullmatch('[0-9]+', run_id):
        raise ValueError('Expected exact CI source and run identity')
    cases = require_junit(TEST_ROOT / 'build/outputs/androidTest-results')
    control = TEST_ROOT / 'src/androidTest/java/me/rerere/rikkahub/viewporttest/LegacyTerminalLazyItemExecutor.kt'
    text = control.read_text()
    body = text[text.index('internal suspend fun executeLegacyTerminalLazyItemScroll('):].replace(
        'executeLegacyTerminalLazyItemScroll(', 'executeTerminalLazyItemScroll(', 1)
    if hashlib.sha256(body.encode()).hexdigest() != CONTROL_BODY_SHA256:
        raise ValueError('Frozen reference executor differs from its original production body')
    log = ROOT / 'artifacts/terminal-validation/gesture-logcat.txt'
    records = []
    with log.open() as stream:
        for line in stream:
            if 'SCROLL_COMPLETION_SAMPLE ' in line:
                records.append(json.loads(line.split('SCROLL_COMPLETION_SAMPLE ', 1)[1]))
    validate(records)
    paths = [control, TEST_ROOT / 'src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalScrollCompletionInstrumentedTest.kt',
             ROOT / 'app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalLazyItemExecutor.kt',
             ROOT / 'app/src/main/java/me/rerere/rikkahub/data/container/TerminalItemViewport.kt',
             ROOT / 'app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalLazyItemMeasurements.kt']
    files = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}
    apks = {}
    for role, folder in (('fixture', 'release'), ('tests', 'androidTest/release')):
        matches = list((TEST_ROOT / 'build/outputs/apk' / folder).glob('*.apk'))
        if len(matches) != 1:
            raise ValueError('Missing exact fixture/test APK')
        with matches[0].open('rb') as stream:
            apks[role] = hashlib.file_digest(stream, 'sha256').hexdigest()
    report = dict(sourceSha=sha, runId=run_id, suite='terminal-scroll-completion-v1',
                  controlSha=CONTROL_SHA, controlBodySha256=CONTROL_BODY_SHA256,
                  sourceHashes=files, apkHashes=apks, junit=cases, samples=records,
                  note='Same mounted real LazyList/Text; setup owns positioning, no controller during sampled executor. '
                       'Compose test-driven frame clock; elapsed values include test scheduling, not phone latency or FPS.')
    out = ROOT / 'artifacts/terminal-validation/scroll-completion.json'
    out.write_text(json.dumps(report, indent=2) + '\n')
    summary = []
    for case in CASES:
        for old in (True, False):
            group = [r for r in records if r['case'] == case and r['legacy'] == old]
            summary.append(f'{case} legacy={old}: n={len(group)} waits={int(old)} '
                           f'fixtureElapsedMedianMs={statistics.median(r["nanos"] for r in group) / 1e6:.3f}')
    notice('Terminal scroll completion verified', '\n'.join(summary))
    data = json.dumps(report, separators=(',', ':')).encode()
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    if len(parts) > 8:
        raise ValueError('Scroll completion evidence exceeds annotation budget')
    for i, part in enumerate(parts, 1):
        notice(f'Terminal scroll completion evidence {i}/{len(parts)}',
               f'zlib-base64 sha256={hashlib.sha256(data).hexdigest()}\n{part}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        notice('Terminal scroll completion rejected', str(error), level='error')
        raise SystemExit(1)
