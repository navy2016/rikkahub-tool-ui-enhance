"""Candidate production-mode comparison; all fixed samples, unchanged compatibility core, no phone FPS claim."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rules = load('tui_history_rules', 'terminal-tui-history-reports.py')
probe = load('tui_history_probe', 'terminal-long-history-probe.py')
ROOT, require, records, integer = rules.ROOT, rules.require, rules.records, rules.integer
MODES = ('chunkedLayers', *rules.MODES)
PHASES = ('cold', 'streamIme3')
TIMES, COUNTS = probe.TIMES, probe.COUNTS


def validate(text):
    require(not any(m in text for m in ('LONG_TIMEOUT ', 'LONG_SAMPLE ', 'LONG_PROBE_SAMPLE ', 'LONG_PROBE_STACK ')),
            'Cannot mix diagnosis/profiler/failure with performance')
    rows = records(text, 'LONG_PERF_SAMPLE ')
    expected = [(s, r, m, p) for s in rules.SIZES for r in range(5)
                for m in (MODES if r % 2 == 0 else MODES[::-1]) for p in PHASES]
    require(len(rows) == 90, 'Expected18warmup72formal performance samples')
    ordered, seen, shapes = {s: [] for s in rules.SIZES}, set(), {}
    for row in rows:
        key = row.get('size'), row.get('round'), row.get('mode'), row.get('phase')
        require(key in expected and key not in seen, 'Unexpected/duplicate performance sample')
        seen.add(key)
        size, round_, mode, phase = key
        integer(row, 'size', 1)
        integer(row, 'round')
        ordered[size].append(key)
        require(row.get('warmup') is (round_ == 0) and row.get('productionModes') is True and row.get('tui') is True,
                'Wrong production mode/warmup identity')
        grouped = mode in rules.MODES
        require(row.get('historyAllowed') is grouped and row.get('effectiveMode') == (mode if grouped else 'eager'),
                'Production gate did not select expected renderer')
        require(0 < integer(row, 'chunks') <= (size + 127)//128 + 1 if grouped else integer(row, 'chunks') == 0,
                'Invalid archival grouping')
        for field in TIMES + COUNTS:
            integer(row, field)
        require(0 < row['firstDrawNs'] <= row['completedNs'] and row['commits'] >= 1 and row['rootDraws'] >= 2,
                'Missing actual draw/commit')
        require(row['retainedRows'] == size + 24 and row['writers'] <= 1, 'Wrong retained history/writers')
        ticks = 0 if phase == 'cold' else 3
        require(row['ticks'] == ticks and row['syncVisited'] == ticks * (size+24) and
                row['syncSkipped'] == row['fifoUses'] == 0, 'Changed TUI synchronization or workload')
        pub = row.get('publicationNs')
        require(type(pub) is list and len(pub) == ticks and all(type(v) is int and v > 0 for v in pub), 'Incomplete output')
        require(sum(pub) == row['feedCaptureNs'] + row['rowSyncNs'], 'Publication accounting differs')
        if phase == 'cold':
            require(row['createdNodes'] == size+24 and row['stateCreateNs'] > 0 and row['initialFrameNs'] > 0, 'Missing cold state/tree')
        else:
            require(row['stateCreateNs'] == row['initialFrameNs'] == 0, 'Hot sample includes cold state')
        signature = row.get('shapeSha256', '')
        require(re.fullmatch('[a-f0-9]{64}', signature) and shapes.setdefault((size, phase), signature) == signature,
                'Actual natural heights/scroll ranges differ between modes')
        rules.rectangle(dict(top=row.get('cursorTop'), bottom=row.get('cursorBottom'),
                             rowHeight=row.get('rowHeight'), viewport=row.get('viewportHeight')))
    for size in rules.SIZES:
        require(ordered[size] == [r for r in expected if r[0] == size], 'Fixed ABC/CBA order changed')
    disposals = records(text, 'LONG_PERF_DISPOSE ')
    require(len(disposals) == 45 and {(r.get('size'), r.get('round'), r.get('mode')) for r in disposals} ==
            {(s,r,m) for s in rules.SIZES for r in range(5) for m in MODES}, 'Missing disposal inventory')
    for row in disposals:
        require(integer(row, 'retainedRows') == integer(row, 'retainedHeightRows') == 0, 'Disposed measurements retained')
    devices = records(text, 'LONG_PERF_DEVICE ')
    require(len(devices) == 3 and {d.get('size') for d in devices} == set(rules.SIZES), 'Missing device inventory')
    for d in devices:
        integer(d, 'pid', 1)
        require(bool(d.get('fingerprint')) and all(re.fullmatch('[a-f0-9]{64}', d.get(k, '')) for k in ('targetApk','testApk')),
                'Missing device/APK identity')
    require(len({(d['pid'],d['fingerprint'],d['targetApk'],d['testApk']) for d in devices}) == 1, 'Mixed comparison process/device/APKs')
    return dict(samples=rows, disposals=disposals, devices=devices)


def compare(rows):
    summaries, decisions = [], []
    for size in rules.SIZES:
        for phase in PHASES:
            controls = {r['round']: r for r in rows if r['size'] == size and r['phase'] == phase and
                        r['mode'] == MODES[0] and not r['warmup']}
            require(set(controls) == {1,2,3,4}, 'Missing four compatibility observations')
            for mode in MODES:
                current = sorted((r for r in rows if r['size'] == size and r['phase'] == phase and r['mode'] == mode and not r['warmup']),
                                 key=lambda r: r['round'])
                require(len(current) == 4, 'Missing four candidate observations')
                summary = dict(size=size, phase=phase, mode=mode, formal=4,
                    **{k: statistics.median(r[k] for r in current) for k in TIMES + COUNTS},
                    completionDeltaNs=[r['completedNs'] - controls[r['round']]['completedNs'] for r in current],
                    commitDeltaNs=[r['commitSpanNs'] - controls[r['round']]['commitSpanNs'] for r in current])
                summaries.append(summary)
                if mode == MODES[0]:
                    continue
                control_complete = statistics.median(r['completedNs'] for r in controls.values())
                control_commit = statistics.median(r['commitSpanNs'] for r in controls.values())
                if phase == 'streamIme3' and size >= 5000:
                    passed = (summary['completedNs'] <= .8*control_complete and summary['commitSpanNs'] <= .8*control_commit and
                              all(d < 0 for d in summary['completionDeltaNs']))
                    criterion = 'all4completeFaster,medianCompleteAndCommit<=80%control'
                else:
                    # Predeclared guard for small-load/cold jitter; does not select/delete a slow sample.
                    passed = summary['completedNs'] <= 1.15*control_complete + 20_000_000
                    criterion = 'medianComplete<=115%control+20ms'
                decisions.append(dict(size=size, phase=phase, mode=mode, passed=passed, criterion=criterion))
    return summaries, decisions


def main():
    require(os.environ.get('TERMINAL_LONG_HISTORY_PERFORMANCE') == 'true', 'Explicit production comparison required')
    guard = rules.source_guard()
    sha, run = os.environ.get('GITHUB_SHA',''), os.environ.get('GITHUB_RUN_ID','')
    require(re.fullmatch('[a-f0-9]{40}',sha) and re.fullmatch('[0-9]+',run), 'Missing source/run')
    base = ROOT / 'benchmarks/terminal-viewport-tests'
    out = ROOT / 'artifacts/terminal-long-history'
    cases = set()
    for path in (base / 'build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for row in ET.parse(path).getroot().iter('testcase'):
            require(row.get('classname') == probe.baseline.CLASS and row.get('name') in probe.baseline.CASES and
                    row.get('name') not in cases, 'Wrong/duplicate comparison case')
            require(not any(row.find(k) is not None for k in ('error','failure','skipped')), 'Comparison case failed')
            cases.add(row.get('name'))
    require(cases == set(probe.baseline.CASES), 'Missing comparison cases')
    data = validate((out / 'diagnostic-logcat.txt').read_text())
    generated, hashes = base / 'build/generated/viewportSources', {}
    for path in generated.rglob('*.kt'):
        original = ROOT / 'app/src/main/java' / path.relative_to(generated)
        require(path.read_bytes() == original.read_bytes(), 'Copied source drift')
        hashes[str(original.relative_to(ROOT))] = hashlib.sha256(original.read_bytes()).hexdigest()
    require(len(hashes) >= 38, 'Missing source inventory')
    for name in rules.CHANGED_MAIN:
        hashes[name] = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
    apk_hashes = {}
    for role, folder in (('targetApk','release'),('testApk','androidTest/release')):
        apks = list((base / 'build/outputs/apk' / folder).glob('*.apk'))
        require(len(apks) == 1, 'Missing built APK pair')
        apk_hashes[role] = hashlib.sha256(apks[0].read_bytes()).hexdigest()
        require(all(d[role] == apk_hashes[role] for d in data['devices']), 'Built/installed APK differs')
    summaries, decisions = compare(data['samples'])
    data.update(suite='terminal-tui-history-performance-v1', sourceSha=sha, runId=run,
        sourceGuard=guard, productionHashes=hashes, apkHashes=apk_hashes, junit=sorted(cases),
        testSha256=hashlib.sha256((ROOT / probe.baseline.TEST).read_bytes()).hexdigest(),
        summary=summaries, decisions=decisions, performanceAccepted=all(d['passed'] for d in decisions),
        note='Same APK/process/device: unchanged default TUI compatibility core vs exact optional production modes. '
             'Not a separate baseline APK or full app benchmark.3ticks*2lines/model IME240->140->240, cold mount separate; '
             '18warmup72formal with fixed ABC/CBA reversals. Core render/sync/controller/metrics/fonts remain baseline. '
             'All timings retained; state creation separate, initial seeding/semantics checks excluded. Inclusive root/commit spans '
             'not exhaustive CPU; completion contains frame/poll waits. Must combine24tick/realPTY/actualIME/reentry correctness '
             'before Release; not phoneFPS. Original failed24tick diagnostic remains failed.')
    rules.publish('Terminal TUI history performance', data, out / 'tui-history-performance.json', emit_compact=False)
    for size in rules.SIZES:
        lines = [f'{r["mode"]}/{r["phase"]} complete={r["completedNs"]/1e6:.2f}ms commit={r["commitSpanNs"]/1e6:.2f}ms '
                 f'pairedDeltaMs={[round(n/1e6,2) for n in r["completionDeltaNs"]]}' for r in summaries if r['size']==size]
        probe.publish(f'TUI production modes {size}', '\n'.join(lines))
    require(data['performanceAccepted'], 'Predeclared performance guard failed; preserve all samples')


if __name__ == '__main__':
    try:
        main()
    except (ValueError,OSError,subprocess.SubprocessError,ET.ParseError) as error:
        probe.failures()
        print('::error title=Terminal TUI history performance rejected::' + str(error)[:2400]
              .replace('%','%25').replace('\r','%0D').replace('\n','%0A'))
        raise SystemExit(1)
