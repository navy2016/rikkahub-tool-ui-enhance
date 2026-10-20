"""Strict candidate TUI grouping correctness: real rectangles, finite output overlap and disposal."""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[2]
MODES = ('tuiChunkedEager', 'tuiChunkedLayers')
SIZES = (1000, 5000, 10000)
VIEW_CLASS = 'me.rerere.rikkahub.viewporttest.TerminalTuiHistoryInstrumentedTest'
VIEW_METHODS = ('tuiHistoryOneThousandContinuousOutputAndRemount', 'tuiHistoryFiveThousandContinuousOutputAndRemount',
                'tuiHistoryTenThousandContinuousOutputAndRemount', 'tuiHistoryLockedAnchorFontColumnsAndRealImeRemainStable',
                'tuiHistorySelectionMouseFullGridAlternateAndDefaultKeepFallback')
PAGE_METHODS = ('tuiHistoryPageChunkedKeyboardAndReentryDuringNativeOutput',
                'tuiHistoryPageLayersKeyboardAndReentryDuringNativeOutput')
VIEW_FILE = 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalTuiHistoryInstrumentedTest.kt'
PAGE_FILE = 'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalTuiHistoryPageTest.kt'
MAIN = 'app/src/main/java/me/rerere/rikkahub/'
CHANGED_MAIN = tuple(MAIN + f for f in ('data/container/TerminalDisplayPolicy.kt', 'data/container/TerminalRenderMode.kt',
    'ui/pages/container/ProcessSessionPage.kt', 'ui/pages/container/TerminalRenderControls.kt',
    'ui/pages/container/TerminalTranscriptViewport.kt'))
BASELINE = '7218dd7e50577bc6fa94575aa476b2a436c71f61'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def records(text, marker):
    values = [json.loads(l.split(marker, 1)[1]) for l in text.splitlines() if marker in l]
    require(all(type(r) is dict for r in values), 'Invalid record ' + marker)
    return values


def integer(row, key, low=0):
    require(type(row.get(key)) is int and row[key] >= low, 'Invalid integer ' + key)
    return row[key]


def rectangle(row):
    require(all(type(row.get(k)) in (int, float) and math.isfinite(row[k]) for k in ('top', 'bottom')), 'Invalid rectangle')
    h, v = integer(row, 'rowHeight', 1), integer(row, 'viewport', 1)
    require(abs(row['bottom'] - row['top'] - h) < .001 and row['top'] >= -1 and row['bottom'] <= v + 1,
            'Actual cursor row clipped')


def validate_viewport(text):
    cases = records(text, 'TUI_HISTORY_CASE ')
    names = {f'stream{s}' for s in SIZES} | {'lockedFontIme', 'fallbacks'}
    require(len(cases) == 5 and {r.get('case') for r in cases} == names and all(r.get('passed') is True for r in cases),
            'Missing/failed viewport case')
    rows = records(text, 'TUI_HISTORY_RECT ')
    expected = {(f'stream{s}', p, m) for s in SIZES for p in ('initial', 'after24', 'reentry24') for m in MODES}
    expected |= {('lockedFontIme', 'follow', m) for m in MODES}
    expected |= {('fallbacks', f'restore{g}', m) for g in range(5) for m in MODES}
    expected.add(('fallbacks', 'default', 'chunkedLayers'))
    require(len(rows) == 31, 'Expected31candidate rectangles')
    seen = set()
    for row in rows:
        key = row.get('case'), row.get('phase'), row.get('mode')
        require(key in expected and key not in seen, 'Unexpected/duplicate candidate rectangle')
        seen.add(key)
        rectangle(row)
        require(row.get('tui') is True and row.get('virtual') is False and integer(row, 'writers') <= 1,
                'Changed TUI semantics or scroll writer')
        grouped = key[2] in MODES
        require(row.get('grouped') is grouped and row.get('effective') == (key[2] if grouped else 'eager'), 'Wrong grouping branch')
        integer(row, 'frameRevision')
        size = int(key[0][6:]) if key[0].startswith('stream') else 1000 if key[0] == 'lockedFontIme' else 128
        require(integer(row, 'historyRows') == size, 'History lost')
    streams = records(text, 'TUI_HISTORY_STREAM ')
    require(len(streams) == 6 and {(r.get('size'), r.get('mode')) for r in streams} == {(s,m) for s in SIZES for m in MODES},
            'Missing24tick/reentry workloads')
    for row in streams:
        for k, value in dict(initialLines=48, reentryLines=48, detachedLines=8, historyRows=row['size'],
            oldRowsRetained=0, newRowsRetained=0, oldHeightsRetained=0, newHeightsRetained=0).items():
            require(integer(row, k) == value, 'Incomplete stream/disposal ' + k)
    gates = records(text, 'TUI_HISTORY_GATE ')
    require(len(gates) == 10 and {(r.get('mode'), r.get('gate')) for r in gates} == {(m,g) for m in MODES for g in range(5)},
            'Missing fallback transitions')
    for row in gates:
        require(row.get('grouped') is False and row.get('effective') == 'eager' and integer(row, 'chunks') == 0,
                'Compatibility gate bypassed')
    return dict(cases=cases, rectangles=rows, streams=streams, gates=gates)


def validate_page(text):
    require('TUI_HISTORY_PAGE_FAILURE ' not in text, 'Real page reported failure')
    cases = records(text, 'TUI_HISTORY_PAGE ')
    require(len(cases) == 2 and {r.get('mode') for r in cases} == set(MODES), 'Missing page modes')
    all_rects, all_overlaps = [], []
    for case in cases:
        mode = case['mode']
        require(case.get('suite') == 'terminal-tui-history-page-v1', 'Wrong page suite')
        require(all(case.get(k) is True for k in ('nativePty', 'rawInput', 'statusAndKeys', 'survivingHistoryRetained')),
                'Changed page setup/history')
        require(case.get('ptyMode') == 'raw' and case.get('peerInputDiscipline') == 'canonical-test-peer', 'Wrong peer input contract')
        require(integer(case, 'historyRows') == 10000 and 6 <= integer(case, 'physicalRows') <= 80, 'Wrong history/grid')
        require(integer(case, 'traceDropped') == 0 and integer(case, 'disposalsBeforeCleanup') >= 1, 'Missing lifecycle/trace provenance')
        require(case.get('inputEcho') == 'ACK:bridge', 'RAW UI input did not reach PTY')
        rects = case.get('rectangles')
        require(type(rects) is list and [r.get('phase') for r in rects] == ['initial', 'imeOpen', 'imeClosed', 'reentry', 'input'],
                'Missing/reordered real page rectangles')
        previous_draw = 0
        for row in rects:
            rectangle(row)
            require(row.get('mode') == mode and row.get('ime') is (row['phase'] == 'imeOpen'), 'Wrong page rectangle mode/IME')
            require(integer(row, 'historyRows') == 10000 and integer(row, 'rows') == case['physicalRows'], 'History/grid changed')
            require(integer(row, 'draw', 1) > previous_draw and integer(row, 'confirmations') >= 2, 'Polling is not two actual draws')
            integer(row, 'revision')
            previous_draw = row['draw']
        overlaps = case.get('overlaps')
        require(type(overlaps) is list and [r.get('phase') for r in overlaps] == ['imeOpen', 'imeClosed', 'reentry'],
                'Missing output/interaction overlaps')
        for row in overlaps:
            require(row.get('mode') == mode and row.get('ime') is (row['phase'] == 'imeOpen'), 'Wrong overlap mode/IME')
            require(row.get('producerStillRunning') is True and integer(row, 'historyRows') == 10000, 'Output was not ongoing')
            require(integer(row, 'beforeTick') < integer(row, 'afterTick') <= 159, 'No new native output during interaction')
            require(integer(row, 'beforeDraw') < integer(row, 'afterDraw') and
                    integer(row, 'beforeRevision') < integer(row, 'afterRevision'), 'No new matching page draw during interaction')
            integer(row, 'elapsedNs', 1)
            detached = integer(row, 'detachedTicks')
            require(detached >= 3 if row['phase'] == 'reentry' else detached == 0,
                    'Output did not continue while page was detached')
            after = next(r for r in rects if r['phase'] == row['phase'])
            require(after['draw'] > row['afterDraw'] and after['revision'] >= row['afterRevision'], 'Final rectangle predates overlap')
        all_rects += rects
        all_overlaps += overlaps
        windows = case.get('outputWindows')
        require(type(windows) is list and [r.get('phase') for r in windows] == ['imeOpen', 'imeClosed', 'reentry'],
                'Missing bounded trace windows')
        for row in windows:
            phase = row['phase']
            expected_bytes = sum(len(f'LIVE:{phase}:{tick:03d} 中文 e\u0301 👩‍💻\r\nbody:{phase}:{tick:03d}\r\n'.encode())
                                 for tick in range(160)) + len(f'DONE:{phase}\r\n'.encode())
            require(integer(row, 'readBytes') == integer(row, 'fedBytes') == integer(row, 'expectedBytes') == expected_bytes,
                    'Native/parser output bytes differ')
            require(all(integer(row, k, 1) > 0 for k in ('windows', 'published', 'drawn')), 'Missing trace progress')
    require(records(text, 'TUI_HISTORY_PAGE_RECT ') == all_rects, 'Rectangle copies differ')
    require(records(text, 'TUI_HISTORY_PAGE_OVERLAP ') == all_overlaps, 'Overlap copies differ')
    cleanups = records(text, 'TUI_HISTORY_PAGE_CLEANUP ')
    require(len(cleanups) == 2 and {r.get('mode') for r in cleanups} == set(MODES), 'Missing cleanup')
    require(all(integer(r, 'disposals') >= 2 and r.get('sessionClosed') is True and r.get('captureClosed') is True
                for r in cleanups), 'Page/session/capture retained')
    return dict(cases=cases, cleanups=cleanups)


def source_guard():
    changed = subprocess.check_output(['git', '-c', 'core.fileMode=false', 'diff', '--name-only', BASELINE, 'HEAD', '--', 'app/src/main'],
                                      cwd=ROOT, text=True).splitlines()
    require(set(changed) == set(CHANGED_MAIN), 'Candidate exceeds approved five production files')
    require(not subprocess.check_output(['git', '-c', 'core.fileMode=false', 'diff', '--name-only', 'HEAD', '--', 'app/src/main'], cwd=ROOT),
            'Uncommitted production source')
    return dict(baseline=BASELINE, changed=changed, unchangedCore=True)


def compact(title, raw, page=None):
    digest = hashlib.sha256(raw).hexdigest()
    encoded = base64.b64encode(zlib.compress(raw, 9)).decode()
    parts = [encoded[i:i+2500] for i in range(0, len(encoded), 2500)]
    require(len(parts) <= 12, 'Evidence exceeds compact budget')
    for n, part in enumerate(parts, 1):
        if page is None or (n-1)//6 + 1 == page:
            print(f'::notice title={title} evidence {n}/{len(parts)}::zlib-base64 sha256={digest}%0A{part}')


def publish(title, document, path, emit_compact=True):
    raw = json.dumps(document, separators=(',', ':'), ensure_ascii=True).encode()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    digest = hashlib.sha256(raw).hexdigest()
    info = dict(sourceSha=document['sourceSha'], runId=document['runId'], sha256=digest,
                suite=document['suite'], rectangles=len(document.get('rectangles', [])), cases=len(document.get('cases', [])))
    print(f'::notice title={title} verified::' + json.dumps(info))
    if emit_compact:
        compact(title, raw)


def viewport():
    source_guard()
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[0-9]+', run), 'Missing source/run')
    base = ROOT / 'benchmarks/terminal-viewport-tests'
    found = set()
    for path in (base / 'build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for case in ET.parse(path).getroot().iter('testcase'):
            if case.get('classname') != VIEW_CLASS:
                continue
            require(case.get('name') in VIEW_METHODS and case.get('name') not in found, 'Unknown/duplicate viewport method')
            require(not any(case.find(k) is not None for k in ('failure', 'error', 'skipped')), 'Viewport method failed')
            found.add(case.get('name'))
    require(found == set(VIEW_METHODS), 'Missing viewport methods')
    data = validate_viewport((ROOT / 'artifacts/terminal-validation/tui-history-logcat.txt').read_text())
    hashes = {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in CHANGED_MAIN}
    for path in (base / 'build/generated/viewportSources').rglob('*.kt'):
        original = ROOT / 'app/src/main/java' / path.relative_to(base / 'build/generated/viewportSources')
        require(path.read_bytes() == original.read_bytes(), 'Generated production source differs')
        hashes[str(original.relative_to(ROOT))] = hashlib.sha256(original.read_bytes()).hexdigest()
    require(len(hashes) >= 39, 'Missing production source inventory')
    apks = {}
    for role, folder in (('target', 'release'), ('test', 'androidTest/release')):
        paths = list((base / 'build/outputs/apk' / folder).glob('*.apk'))
        require(len(paths) == 1, 'Missing Release fixture APK')
        apks[role] = hashlib.sha256(paths[0].read_bytes()).hexdigest()
    data.update(suite='terminal-tui-history-viewport-v1', sourceSha=sha, runId=run, productionHashes=hashes, apkHashes=apks,
        testSha256=hashlib.sha256((ROOT / VIEW_FILE).read_bytes()).hexdigest(), junit=sorted(found),
        note='Candidate correctness with actual natural rectangles and complete24tick/reentry streams; not phoneFPS.')
    publish('Terminal TUI history viewport', data, ROOT / 'artifacts/terminal-validation/tui-history.json')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('viewport', 'guard', 'performanceEvidence'))
    parser.add_argument('--page', type=int, choices=(1,2), default=1)
    try:
        args = parser.parse_args()
        if args.action == 'guard':
            print(json.dumps(source_guard()))
        elif args.action == 'performanceEvidence':
            path = ROOT / 'artifacts/terminal-long-history/tui-history-performance.json'
            require(path.exists(), 'No completed performance report to publish')
            data = json.loads(path.read_bytes())
            require(data['sourceSha'] == os.environ['GITHUB_SHA'] and data['runId'] == os.environ['GITHUB_RUN_ID'], 'Report identity mismatch')
            compact('Terminal TUI history performance', path.read_bytes(), args.page)
        else:
            viewport()
    except (ValueError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        text = str(error)[:2400].replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print('::error title=Terminal TUI history rejected::' + text)
        raise SystemExit(1)
