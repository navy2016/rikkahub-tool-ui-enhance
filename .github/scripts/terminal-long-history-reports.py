"""Complete non-acceptance diagnosis with exact production and installed-APK identity."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[2]
BASE = '7218dd7e50577bc6fa94575aa476b2a436c71f61'
TREE = 'c1463f057fda3bbc693f7e8164065da4baebcdb1'
CLASS = 'me.rerere.rikkahub.viewporttest.TerminalLongHistoryDiagnosticTest'
CASES = ('longHistoryOneThousand', 'longHistoryFiveThousand', 'longHistoryTenThousand')
TEST = 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalLongHistoryDiagnosticTest.kt'
SIZES = (1000, 5000, 10000)
PROFILES = ('ordinary', 'tui')
PHASES = ('cold', 'streamIme', 'reentry')
TIMES = ('stateCreateNs', 'initialFrameNs', 'firstDrawNs', 'completedNs', 'rootMeasureNs', 'rootPlaceNs', 'rootDrawNs',
         'commitSpanNs', 'feedCaptureNs', 'rowSyncNs')
COUNTS = ('commits', 'rootMeasures', 'rootPlacements', 'rootDraws', 'syncVisited', 'syncSkipped', 'fifoUses',
          'ticks', 'createdNodes', 'rowMeasures', 'heightVisits', 'retainedRows', 'writers')


def require(ok, message):
    if not ok:
        raise ValueError(message)


def records(text, marker):
    return [json.loads(line.split(marker, 1)[1]) for line in text.splitlines() if marker in line]


def validate(text):
    samples = records(text, 'LONG_SAMPLE ')
    expected = [(size, profile, round_, phase) for size in SIZES for round_ in range(4)
                for profile in (PROFILES if round_ % 2 == 0 else PROFILES[::-1]) for phase in PHASES]
    require(len(samples) == 72, 'Expected all18warmup/54formal samples')
    seen, by_size = set(), {n: [] for n in SIZES}
    for row in samples:
        require(type(row) is dict, 'Invalid sample')
        key = row.get('size'), row.get('profile'), row.get('round'), row.get('phase')
        require(key in expected and key not in seen, 'Duplicate/unknown sample identity')
        seen.add(key)
        size, profile, round_, phase = key
        require(type(size) is int and type(round_) is int and row.get('warmup') is (round_ == 0), 'Wrong warmup identity')
        by_size[size].append(key)
        for field in TIMES + COUNTS:
            require(type(row.get(field)) is int and row[field] >= 0, 'Invalid scalar ' + field)
        require(row['completedNs'] > 0 and 0 < row['firstDrawNs'] <= row['completedNs'], 'Invalid completed/first-draw time')
        require(row['commits'] > 0 and row['rootDraws'] >= 2 and row['retainedRows'] == size + 24, 'Missing row tree/draws')
        require(row['writers'] <= 1, 'Multiple scroll writers')
        ticks = 0 if phase == 'cold' else 24
        require(row['ticks'] == ticks and type(row.get('publicationNs')) is list and len(row['publicationNs']) == ticks,
                'Incomplete finite output')
        require(all(type(n) is int and n > 0 for n in row['publicationNs']), 'Invalid publication times')
        require(sum(row['publicationNs']) == row['feedCaptureNs'] + row['rowSyncNs'], 'Publication spans differ')
        require(row['syncVisited'] + row['syncSkipped'] == ticks * (size + 24), 'Incomplete row sync accounting')
        require(row['fifoUses'] <= ticks, 'Invalid FIFO count')
        if phase == 'cold':
            require(row['createdNodes'] == size + 24, 'Wrong cold node inventory')
        if phase == 'streamIme':
            require(row['stateCreateNs'] == row['initialFrameNs'] == 0, 'Hot phase includes cold creation')
        for field in ('cursorTop', 'cursorBottom'):
            require(type(row.get(field)) in (float, int) and abs(row[field]) < 1e8, 'Invalid actual rectangle')
        require(type(row.get('rowHeight')) is int and row['rowHeight'] > 0, 'Invalid row height')
        require(type(row.get('viewportHeight')) is int and row['viewportHeight'] > 0, 'Invalid viewport height')
        require(abs(row['cursorBottom'] - row['cursorTop'] - row['rowHeight']) < .001, 'Inconsistent rectangle')
        require(row['cursorTop'] >= -1 and row['cursorBottom'] <= row['viewportHeight'] + 1, 'Cursor clipped')
    for size in SIZES:
        require(by_size[size] == [k for k in expected if k[0] == size], 'Profile/phase order changed')
    devices = records(text, 'LONG_DEVICE ')
    require(len(devices) == 3 and {d.get('size') for d in devices} == set(SIZES), 'Missing device identities')
    for d in devices:
        require(type(d.get('pid')) is int and d['pid'] > 0 and bool(d.get('fingerprint')), 'Incomplete device identity')
        require(d.get('density') == 1.33 and d.get('fontScale') == 1.3, 'Different fixture metrics')
        require(all(re.fullmatch('[a-f0-9]{64}', d.get(k, '')) for k in ('targetApk', 'testApk')), 'Invalid installed APK hash')
    require(len({(d['targetApk'], d['testApk'], d['fingerprint']) for d in devices}) == 1, 'Mixed APKs/devices')
    return samples, devices


def summary(samples):
    return [dict(size=size, profile=profile, phase=phase, formal=3,
        **{field: statistics.median(r[field] for r in samples if
            (r['size'], r['profile'], r['phase'], r['warmup']) == (size, profile, phase, False))
            for field in TIMES + COUNTS}) for size in SIZES for profile in PROFILES for phase in PHASES]


def publish(title, data):
    require(len(data) < 2900, 'Annotation exceeds budget')
    print('::notice title=' + title + '::' + data.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))


def main():
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[0-9]+', run), 'Missing workflow identity')
    actual_tree = subprocess.check_output(['git', 'rev-parse', 'HEAD:app/src/main'], cwd=ROOT, text=True).strip()
    require(actual_tree == TREE, 'Baseline diagnosis changed production tree')
    base = ROOT / 'benchmarks/terminal-viewport-tests'
    found = set()
    for file in (base / 'build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for case in ET.parse(file).getroot().iter('testcase'):
            require(case.get('classname') == CLASS and case.get('name') in CASES, 'Unexpected diagnosis case')
            require(case.get('name') not in found and not any(case.find(tag) is not None for tag in ('error', 'failure', 'skipped')),
                    'Duplicate/failed/skipped diagnosis case')
            found.add(case.get('name'))
    require(found == set(CASES), 'Missing diagnosis cases')
    samples, devices = validate((ROOT / 'artifacts/terminal-long-history/diagnostic-logcat.txt').read_text())
    hashes = {}
    generated = base / 'build/generated/viewportSources'
    for path in generated.rglob('*.kt'):
        original = ROOT / 'app/src/main/java' / path.relative_to(generated)
        require(path.read_bytes() == original.read_bytes(), 'Generated source differs')
        hashes[str(original.relative_to(ROOT))] = hashlib.sha256(original.read_bytes()).hexdigest()
    require(len(hashes) >= 30, 'Missing fixture production inventory')
    apk_hashes = {}
    for role, folder in (('targetApk', 'release'), ('testApk', 'androidTest/release')):
        files = list((base / 'build/outputs/apk' / folder).glob('*.apk'))
        require(len(files) == 1, 'Missing built APK')
        apk_hashes[role] = hashlib.sha256(files[0].read_bytes()).hexdigest()
        require(all(d[role] == apk_hashes[role] for d in devices), 'Installed APK differs')
    report = dict(suite='terminal-long-history-diagnostic-v1', acceptance=False, sourceSha=sha, runId=run,
        baselineSha=BASE, productionTree=TREE, productionHashes=hashes, testSha256=hashlib.sha256((ROOT / TEST).read_bytes()).hexdigest(),
        junit=sorted(found), apkHashes=apk_hashes, devices=devices, samples=samples, summary=summary(samples),
        note='Component diagnosis, unchanged production,18warmup54formal; no FPS/speedup claim. Root spans exclude independently '
             'remeasured descendants; commitSpan includes apply bookkeeping, not pure composition CPU. Completion contains '
             'frame/poll waits. State creation is separate; initial model seeding excluded. streamIme is finite frame-paced '
             'model output plus viewport contraction, not real Android IME/native PTY. Reentry creates new row state/tree.')
    raw = json.dumps(report, separators=(',', ':')).encode()
    (ROOT / 'artifacts/terminal-long-history/diagnostic.json').write_bytes(raw)
    publish('Long history diagnosis (not acceptance)', json.dumps(dict(sourceSha=sha, runId=run, samples=72,
        warmup=18, formal=54, sha256=hashlib.sha256(raw).hexdigest())))
    for size in SIZES:
        rows = [r for r in report['summary'] if r['size'] == size]
        publish(f'Long history diagnostic {size}', '\n'.join(
            f'{r["profile"]}/{r["phase"]}: state={r["stateCreateNs"]/1e6:.2f}ms firstDraw={r["firstDrawNs"]/1e6:.2f}ms '
            f'complete={r["completedNs"]/1e6:.2f}ms sync={r["rowSyncNs"]/1e6:.2f}ms '
            f'measure={r["rootMeasureNs"]/1e6:.2f}ms rowVisits={r["syncVisited"]} rowMeasures={r["rowMeasures"]}' for r in rows))
    encoded = base64.b64encode(zlib.compress(raw, 9)).decode()
    parts = [encoded[i:i+2600] for i in range(0, len(encoded), 2600)]
    require(len(parts) <= 16, 'Diagnostic evidence exceeds budget')
    for i, part in enumerate(parts, 1):
        publish(f'Long history evidence {i}/{len(parts)}', 'zlib-base64 sha256=' + hashlib.sha256(raw).hexdigest() + '\n' + part)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, ET.ParseError, subprocess.SubprocessError) as error:
        text = str(error)[:2300].replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print('::error title=Long history diagnosis rejected::' + text)
        raise SystemExit(1)
