"""Strict physical Text/viewport rectangle evidence, not a shared nominal-height assertion."""
import argparse
import base64
import hashlib
import json
import math
import os
import re
import subprocess
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = '8ed4f06e10039233511c4ef43bb39ed717ef6ef6'
CLASS = 'me.rerere.rikkahub.viewporttest.TerminalBottomVisibilityInstrumentedTest'
TEST_FILE = 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalBottomVisibilityInstrumentedTest.kt'
CASES = (
    'bottomVisibilityBlankCursorIsEntirelyVisible',
    'bottomVisibilityTuiHistoryGrowthNeverAccumulatesClipping',
    'bottomVisibilityVirtualAndImeModesKeepBlankCursorAfterRoundTrip',
    'bottomVisibilityTuiFontAlternateAndMainReturnKeepMeasuredTail',
    'bottomVisibilityHiddenCursorAndPromptDoNotFollowUnusedBlankRows',
    'bottomVisibilityTuiUpdatesReuseHistoryAndDetachMeasurements',
)
PAGE_METHODS = ('bottomPageBlankCursorWithStatusKeysImeAndRemount',
                'bottomPageTuiGrowingNaturalHistoryWithStatusKeysAndIme')
PRODUCTION = (
    'data/container/TerminalViewportGeometry.kt', 'data/container/TerminalEagerViewportGeometry.kt',
    'data/container/TerminalEagerGeometryCache.kt', 'data/container/TerminalItemViewport.kt',
    'data/container/TerminalViewportController.kt', 'ui/pages/container/TerminalViewportBinding.kt',
    'ui/pages/container/TerminalLazyItemMeasurements.kt', 'ui/pages/container/TerminalTranscriptViewport.kt',
    'ui/pages/container/TerminalRenderedRows.kt', 'ui/pages/container/TerminalRenderSnapshot.kt',
    'ui/pages/container/TerminalRenderControls.kt', 'ui/pages/container/ProcessSessionPage.kt',
)
MAIN = 'app/src/main/java/me/rerere/rikkahub/'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def records(text, marker):
    values = [json.loads(line.split(marker, 1)[1]) for line in text.splitlines() if marker in line]
    require(all(type(v) is dict for v in values), 'Invalid rectangle record')
    return values


def geometry(row, clipped=False):
    for name in ('top', 'bottom'):
        require(type(row.get(name)) in (int, float) and math.isfinite(row[name]), 'Invalid measured ' + name)
    for name in ('rowHeight', 'viewport'):
        require(type(row.get(name)) is int and row[name] > 0, 'Missing actual ' + name)
    require(abs(row['bottom'] - row['top'] - row['rowHeight']) < 0.001, 'Rectangle height mismatch')
    if clipped:
        require(row['bottom'] > row['viewport'] + 1, 'Old-source negative control did not expose clipping')
    else:
        require(row['top'] >= -1 and row['bottom'] <= row['viewport'] + 1, 'Actual target row remains clipped')


def validate_rectangles(text, control=False):
    rows = records(text, 'BOTTOM_RECT ')
    expected = {('cursor', 'initial'), *[('tuiGrowth', n) for n in ('128', '512', '1024')]}
    if not control:
        expected |= {('cursorIme', p) for p in ('initial', 'open', 'chunkedLayers', 'lazyHistoryIme', 'closed')}
        expected |= {('tuiFont', p) for p in ('12', '21', '14', 'alt', 'main')}
        expected |= {('cursorState', p) for p in ('hidden', 'prompt')}
        expected |= {('tuiReuse', str(p)) for p in range(8)}
    require(len(rows) == len(expected), 'Missing/duplicate rectangle matrix')
    seen = set()
    for row in rows:
        key = row.get('case'), row.get('phase')
        require(key in expected and key not in seen, 'Unexpected/duplicate rectangle')
        seen.add(key)
        require(row.get('control') is control and row.get('clippedExpected') is control, 'Wrong control identity')
        for field in ('historyRows', 'row', 'scroll', 'writers'):
            require(type(row.get(field)) is int and row[field] >= 0, 'Invalid rectangle counter')
        require(row['writers'] <= 1, 'Multiple scroll writers')
        require(type(row.get('virtual')) is bool and type(row.get('tui')) is bool, 'Missing backend identity')
        geometry(row, control)
        if key[0] == 'tuiGrowth':
            require(row['historyRows'] == int(key[1]) and row['tui'] and not row['virtual'], 'Wrong TUI history workload')
    if control:
        first = next(r for r in rows if (r['case'], r['phase']) == ('tuiGrowth', '128'))
        last = next(r for r in rows if (r['case'], r['phase']) == ('tuiGrowth', '1024'))
        require(last['bottom'] - last['viewport'] > first['bottom'] - first['viewport'], 'No accumulated control drift')
    return rows


def validate_page(text):
    records_ = records(text, 'BOTTOM_PAGE ')
    require(len(records_) == 2 and {r.get('case') for r in records_} == {'cursor', 'tui'}, 'Missing real-page cases')
    flat = records(text, 'BOTTOM_PAGE_RECT ')
    collected = []
    for case in records_:
        tui = case['case'] == 'tui'
        require(case.get('suite') == 'terminal-bottom-page-v1', 'Wrong page contract')
        require(all(case.get(k) is True for k in ('nativePty', 'defaultRenderer', 'statusAndKeys')), 'Wrong real-page setup')
        require(type(case.get('initialRows')) is int and 6 <= case['initialRows'] <= 80, 'Invalid PTY dimensions')
        expected = ['history160'] + (['history560', 'history1160'] if tui else [])
        expected += ['imeOpen', 'imeClosed', 'statusHidden', 'statusShown', 'remount']
        rows = case.get('geometry')
        require(type(rows) is list and [r.get('phase') for r in rows] == expected, 'Missing/reordered actual page rectangles')
        previous_draw = 0
        for row in rows:
            geometry(row)
            require(row.get('tui') is tui and row.get('ime') is (row['phase'] == 'imeOpen'), 'Wrong page viewport mode')
            for key in ('draw', 'revision', 'historyRows', 'cursorRow', 'confirmations'):
                require(type(row.get(key)) is int and row[key] >= 0, 'Invalid page scalar')
            require(row['draw'] > previous_draw and row['confirmations'] >= 2, 'Repeated polling is not two draws')
            previous_draw = row['draw']
        collected += rows
    require(flat == collected, 'Page rectangles differ from receipt copies')
    return records_


def publish(title, data, path):
    raw = json.dumps(data, separators=(',', ':')).encode()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    summary = dict(sourceSha=data['sourceSha'], runId=data['runId'], productionSha=data['productionSha'],
                   control=data.get('control', False), rectangles=len(data.get('rectangles', [])),
                   cases=len(data.get('cases', [])), sha256=hashlib.sha256(raw).hexdigest())
    print('::notice title=' + title + ' verified::' + json.dumps(summary))
    encoded = base64.b64encode(zlib.compress(raw, 9)).decode()
    parts = [encoded[i:i + 2400] for i in range(0, len(encoded), 2400)]
    require(len(parts) <= 8, 'Rectangle evidence exceeds annotation budget')
    for index, part in enumerate(parts, 1):
        print(f'::notice title={title} evidence {index}/{len(parts)}::zlib-base64 sha256={summary["sha256"]}%0A{part}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--control', action='store_true')
    args = parser.parse_args()
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[0-9]+', run), 'Missing source/run identity')
    base = ROOT / 'benchmarks/terminal-viewport-tests'
    production = BASELINE if args.control else sha
    observed = {}
    cases = set(CASES[:2] if args.control else CASES)
    for path in (base / 'build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for row in ET.parse(path).getroot().iter('testcase'):
            if row.get('classname') != CLASS:
                continue
            name = row.get('name')
            require(name in cases and name not in observed, 'Unexpected/duplicate JUnit case')
            require(not any(row.find(tag) is not None for tag in ('failure', 'error', 'skipped')), 'Failed rectangle case')
            observed[name] = True
    require(set(observed) == cases, 'Missing rectangle JUnit matrix')
    hashes = {}
    for name in PRODUCTION:
        path = MAIN + name
        raw = (ROOT / path).read_bytes()
        if args.control:
            require(raw == subprocess.check_output(['git', 'show', BASELINE + ':' + path], cwd=ROOT), 'Old production overlay differs')
        hashes[path] = hashlib.sha256(raw).hexdigest()
        generated = base / 'build/generated/viewportSources/me/rerere/rikkahub' / name
        if name != 'ui/pages/container/ProcessSessionPage.kt':
            require(generated.exists(), 'Missing production source in viewport fixture: ' + name)
            require(generated.read_bytes() == raw, 'Copied viewport source drift')
    apks = {}
    for role, folder in (('target', 'release'), ('test', 'androidTest/release')):
        files = list((base / 'build/outputs/apk' / folder).glob('*.apk'))
        require(len(files) == 1, 'Missing fixture APK identity')
        apks[role] = hashlib.sha256(files[0].read_bytes()).hexdigest()
    rectangles = validate_rectangles((ROOT / 'artifacts/terminal-validation/bottom-visibility-logcat.txt').read_text(), args.control)
    document = dict(suite='terminal-bottom-rectangles-v1', sourceSha=sha, runId=run, productionSha=production,
        control=args.control, rectangles=rectangles, cases=sorted(cases), productionHashes=hashes, apkHashes=apks,
        testSha256=hashlib.sha256((ROOT / TEST_FILE).read_bytes()).hexdigest(),
        note='Independent actual Text/viewport rectangles; control intentionally proves old clipping. Not phone performance.')
    publish('Terminal bottom visibility', document, ROOT / 'artifacts/terminal-validation/bottom-visibility.json')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        message = str(error)[:2500].replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print('::error title=Bottom visibility evidence rejected::' + message)
        raise SystemExit(1)
