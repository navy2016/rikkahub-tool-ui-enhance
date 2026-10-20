"""Separate three-tick eager grouping intervention; never relabels the failed 24-tick diagnosis."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET
import zlib

SPEC = importlib.util.spec_from_file_location('long_baseline', Path(__file__).with_name('terminal-long-history-reports.py'))
baseline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(baseline)
ROOT, require, records, publish = baseline.ROOT, baseline.require, baseline.records, baseline.publish
SIZES = baseline.SIZES
MODES = ('eager', 'chunkedEager', 'chunkedLayers')
PHASES = ('cold', 'streamIme3')
TIMES = baseline.TIMES
COUNTS = tuple(k for k in baseline.COUNTS if k not in ('rootMeasures', 'rootPlacements'))


def validate(text):
    require('LONG_SAMPLE ' not in text, 'Do not mix old diagnosis with grouping intervention')
    require('LONG_TIMEOUT ' not in text, 'Timed out intervention cannot pass')
    samples = records(text, 'LONG_PROBE_SAMPLE ')
    require(len(samples) == 54, 'Expected18warmup/36formal grouping samples')
    expected = [(n, r, mode, phase) for n in SIZES for r in range(3)
                for mode in (MODES if r % 2 == 0 else MODES[::-1]) for phase in PHASES]
    observed = {n: [] for n in SIZES}
    shapes, seen = {}, set()
    for row in samples:
        key = (row.get('size'), row.get('round'), row.get('mode'), row.get('phase'))
        require(key in expected and key not in seen, 'Duplicate/unknown intervention sample')
        seen.add(key)
        size, round_, mode, phase = key
        require(type(size) is int and type(round_) is int and row.get('warmup') is (round_ == 0), 'Wrong warmup flag')
        require(row.get('tui') is True, 'TUI semantics were not preserved')
        observed[size].append(key)
        for field in TIMES + COUNTS:
            require(type(row.get(field)) is int and row[field] >= 0, 'Invalid scalar ' + field)
        require(0 < row['firstDrawNs'] <= row['completedNs'], 'Invalid first draw/completion')
        require(row['commits'] > 0 and row['rootDraws'] >= 2, 'Missing committed draws')
        require(row['retainedRows'] == size + 24 and row['writers'] <= 1, 'Wrong retained rows/writers')
        ticks = 0 if phase == 'cold' else 3
        require(row['ticks'] == ticks and row.get('fifoUses') == 0 and row.get('syncSkipped') == 0,
                'Changed baseline TUI synchronization')
        require(row['syncVisited'] == ticks * (size + 24), 'Incomplete baseline row synchronization')
        publications = row.get('publicationNs')
        require(type(publications) is list and len(publications) == ticks and
                all(type(n) is int and n > 0 for n in publications), 'Incomplete finite output')
        require(sum(publications) == row['feedCaptureNs'] + row['rowSyncNs'], 'Publication spans differ')
        if phase == 'cold':
            require(row['createdNodes'] == size + 24, 'Cold mount omitted rows')
        else:
            require(row['stateCreateNs'] == row['initialFrameNs'] == 0, 'Hot phase counted state creation')
        signature = row.get('shapeSha256', '')
        require(re.fullmatch('[a-f0-9]{64}', signature), 'Missing actual height/scroll-range identity')
        require(shapes.setdefault((size, phase), signature) == signature, 'Paired natural geometry differs')
        for field in ('cursorTop', 'cursorBottom'):
            require(type(row.get(field)) in (int, float) and abs(row[field]) < 1e8, 'Invalid cursor rectangle')
        require(type(row.get('rowHeight')) is int and row['rowHeight'] > 0 and
                type(row.get('viewportHeight')) is int and row['viewportHeight'] > 0, 'Invalid layout size')
        require(abs(row['cursorBottom'] - row['cursorTop'] - row['rowHeight']) < .001 and
                row['cursorTop'] >= -1 and row['cursorBottom'] <= row['viewportHeight'] + 1, 'Cursor clipped')
    for size in SIZES:
        require(observed[size] == [k for k in expected if k[0] == size], 'Changed fixed order')
    disposals = records(text, 'LONG_PROBE_DISPOSE ')
    expected_disposals = {(n, r, mode) for n in SIZES for r in range(3) for mode in MODES}
    require(len(disposals) == 27 and {(r.get('size'), r.get('round'), r.get('mode')) for r in disposals} == expected_disposals,
            'Missing/duplicate disposal receipt')
    require(all(r.get('retainedRows') == 0 and r.get('retainedHeightRows') == 0 for r in disposals), 'Disposed row tree retained')
    devices = records(text, 'LONG_PROBE_DEVICE ')
    require(len(devices) == 3 and {d.get('size') for d in devices} == set(SIZES), 'Missing device identity')
    for d in devices:
        require(type(d.get('pid')) is int and d['pid'] > 0 and bool(d.get('fingerprint')), 'Invalid device identity')
        require(all(re.fullmatch('[a-f0-9]{64}', d.get(role, '')) for role in ('targetApk', 'testApk')), 'Missing installed APK hashes')
    require(len({(d['pid'], d['fingerprint'], d['targetApk'], d['testApk']) for d in devices}) == 1, 'Mixed installed APK/process/device')
    infos, stacks = records(text, 'LONG_PROBE_STACK_INFO '), records(text, 'LONG_PROBE_STACK ')
    require(len(infos) == 1, 'Missing separate stack sampler identity')
    info = infos[0]
    require(info.get('size') == 5000 and info.get('intervalNs') == 20_000_000 and
            type(info.get('observations')) is int and info['observations'] > 0, 'Invalid stack sampler')
    require(type(info.get('parts')) is int and 1 <= info['parts'] <= 12 and len(stacks) == info['parts'], 'Missing stack parts')
    require({r.get('index') for r in stacks} == set(range(len(stacks))), 'Duplicate stack parts')
    require(all(r.get('size') == 5000 and type(r.get('count')) is int and r['count'] > 0 and
                isinstance(r.get('stack'), str) and 0 < len(r['stack']) < 2600 for r in stacks), 'Invalid stack sample')
    require(sum(r['count'] for r in stacks) <= info['observations'], 'Invalid sample counts')
    return dict(samples=samples, disposals=disposals, devices=devices, sampler=info, stacks=stacks)


def summary(samples):
    rows = []
    for size in SIZES:
        for phase in PHASES:
            ref = {r['round']: r for r in samples if r['size'] == size and r['mode'] == 'eager' and
                   r['phase'] == phase and not r['warmup']}
            for mode in MODES:
                values = sorted((r for r in samples if r['size'] == size and r['phase'] == phase and
                                 r['mode'] == mode and not r['warmup']), key=lambda r: r['round'])
                require(len(values) == 2 and set(ref) == {1, 2}, 'Incomplete formal comparison')
                rows.append(dict(size=size, phase=phase, mode=mode, formal=2,
                    **{k: statistics.median(r[k] for r in values) for k in TIMES + COUNTS},
                    completionDeltaNs=[r['completedNs'] - ref[r['round']]['completedNs'] for r in values],
                    commitDeltaNs=[r['commitSpanNs'] - ref[r['round']]['commitSpanNs'] for r in values]))
    return rows


def failures():
    """Publish actual first exception and last checkpoint, never a full Gradle failure log."""
    out = ROOT / 'artifacts/terminal-long-history'
    xmlroot = ROOT / 'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results'
    for path in sorted(xmlroot.rglob('TEST-*.xml')):
        for case in ET.parse(path).getroot().iter('testcase'):
            for tag in ('error', 'failure', 'skipped'):
                node = case.find(tag)
                if node is not None:
                    publish('Long history original exception', (case.get('name', '') + '\n' +
                        (node.text or node.get('message', '')))[:2500])
    if (out / 'diagnostic-logcat.txt').exists():
        lines = (out / 'diagnostic-logcat.txt').read_text().splitlines()
        selected = [l for l in lines if 'LONG_TIMEOUT ' in l or 'LONG_WAIT ' in l][-3:]
        for i, line in enumerate(selected):
            publish(f'Long history last checkpoint {i}', line[-2500:])


def main():
    require(os.environ.get('TERMINAL_LONG_HISTORY_PROBE') == 'true', 'Explicit grouping probe required')
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[0-9]+', run), 'Missing source/run identity')
    require(subprocess.check_output(['git', 'rev-parse', 'HEAD:app/src/main'], cwd=ROOT, text=True).strip() == baseline.TREE,
            'Grouping probe changed baseline production tree')
    out = ROOT / 'artifacts/terminal-long-history'
    base = ROOT / 'benchmarks/terminal-viewport-tests'
    cases = set()
    for file in (base / 'build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for case in ET.parse(file).getroot().iter('testcase'):
            require(case.get('classname') == baseline.CLASS and case.get('name') in baseline.CASES and
                    case.get('name') not in cases, 'Unknown/duplicate test case')
            require(not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')), 'Grouping probe test failed')
            cases.add(case.get('name'))
    require(cases == set(baseline.CASES), 'Missing grouping test cases')
    data = validate((out / 'diagnostic-logcat.txt').read_text())
    generated, hashes = base / 'build/generated/viewportSources', {}
    for path in sorted(generated.rglob('*.kt')):
        original = ROOT / 'app/src/main/java' / path.relative_to(generated)
        require(path.read_bytes() == original.read_bytes(), 'Copied production source differs')
        hashes[str(original.relative_to(ROOT))] = hashlib.sha256(original.read_bytes()).hexdigest()
    require(len(hashes) >= 30, 'Missing production source inventory')
    apk_hashes = {}
    for role, folder in (('targetApk', 'release'), ('testApk', 'androidTest/release')):
        files = list((base / 'build/outputs/apk' / folder).glob('*.apk'))
        require(len(files) == 1, 'Missing built APK pair')
        apk_hashes[role] = hashlib.sha256(files[0].read_bytes()).hexdigest()
        require(all(d[role] == apk_hashes[role] for d in data['devices']), 'Installed/built APK differs')
    data.update(suite='terminal-long-history-grouping-probe-v1', acceptance=False, sourceSha=sha, runId=run,
        baselineSha=baseline.BASE, productionTree=baseline.TREE, originalFailedRun=39195471176,
        productionHashes=hashes, testSha256=hashlib.sha256((ROOT / baseline.TEST).read_bytes()).hexdigest(),
        apkHashes=apk_hashes, junit=sorted(cases), summary=summary(data['samples']),
        note='Separate3tick/6line grouping intervention; not24tick diagnosis success. TUI row sync, real heights, '
             'screen policy and sole executor unchanged. Both modified plans exist only in fixture.18warmup36formal '
             'samples, ABC/CBA order reversal, two formal observations per configuration (not population evidence). '
             'Root and commit spans inclusive/not exhaustiveCPU. State allocation separate, model seeding excluded. '
             'No realIME/nativePTY/reentry/phoneFPS/long-term-memory acceptance. Stack sampler only on separate5k flat3tick run, '
             'first128distinct stack keys/top12frequencies, sampling may perturb that run and is not exact CPU attribution.')
    raw = json.dumps(data, separators=(',', ':'), ensure_ascii=True).encode()
    (out / 'grouping-probe.json').write_bytes(raw)
    digest = hashlib.sha256(raw).hexdigest()
    publish('Long history grouping probe (not acceptance)', json.dumps(dict(sourceSha=sha, runId=run,
        samples=54, warmup=18, formal=36, sha256=digest, originalFailedRun=39195471176)))
    for size in SIZES:
        rows = [r for r in data['summary'] if r['size'] == size]
        publish(f'Long history grouping {size}', '\n'.join(
            f'{r["mode"]}/{r["phase"]}: complete={r["completedNs"]/1e6:.2f}ms commit={r["commitSpanNs"]/1e6:.2f}ms '
            f'sync={r["rowSyncNs"]/1e6:.2f}ms rootMeasure={r["rootMeasureNs"]/1e6:.2f}ms '
            f'pairedDelta={[round(d/1e6,2) for d in r["completionDeltaNs"]]}' for r in rows))
    for stack in data['stacks'][:4]:
        publish(f'Long history main stack {stack["index"]}', f'{stack["count"]}/{data["sampler"]["observations"]}\n' + stack['stack'])
    encoded = base64.b64encode(zlib.compress(raw, 9)).decode()
    parts = [encoded[i:i+2600] for i in range(0, len(encoded), 2600)]
    require(len(parts) <= 16, 'Probe compact evidence exceeds budget')
    for i, part in enumerate(parts, 1):
        publish(f'Long history grouping evidence {i}/{len(parts)}', f'zlib-base64 sha256={digest}\n{part}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, ET.ParseError, subprocess.SubprocessError) as error:
        failures()
        print('::error title=Long history grouping probe rejected::' + str(error)[:2300]
              .replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
        raise SystemExit(1)
