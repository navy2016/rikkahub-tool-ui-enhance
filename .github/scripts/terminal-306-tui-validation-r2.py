"""Fixed R1 width candidate on runtime-proven TUI pages; mouse/glyph/IME diagnosis is separate."""
import argparse
import collections
import contextlib
import hashlib
import importlib.util
import json
import math
import os
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'benchmarks/terminal-306-tui-validation-r2'
OUT = ROOT / 'artifacts/terminal-306-tui-validation-r2'
PROTOCOL_SHA = '8b6a9b0ac8e2f3364b574b48266ebec3021e95df41b7026a4d82cc6729722deb'
s = importlib.util.spec_from_file_location('fixed306_r1', Path(__file__).with_name('terminal-306-device-repair-r1.py'))
r1 = importlib.util.module_from_spec(s)
s.loader.exec_module(r1)
ci = r1.ci
require = r1.require
save = r1.save
digest = r1.digest


def protocol():
    raw = (BASE / 'protocol.json').read_bytes()
    require(digest(raw) == PROTOCOL_SHA, 'R2 protocol changed')
    return json.loads(raw)


def guard():
    identity, _ = r1.guard()
    p = protocol()
    require(p['r1IdentitySha256'] == r1.IDENTITY_SHA and p['candidateHashes'] ==
            {n:v['candidate'] for n,v in identity['files'].items()}, 'R1 candidate changed')
    require(not r1.git('diff','--name-only',p['priorSourceSha'],'--',
        'benchmarks/terminal-306-device-repair-r1','.github/scripts/terminal-306-device-repair-r1.py',
        '.github/scripts/test_terminal_306_device_repair_r1.py','.github/workflows/terminal-306-device-repair-r1.yml').strip(),
        'R2 changes frozen R1')
    for name, expected in p['helperHashes'].items():
        require(ci.digest(ROOT / name) == expected, 'R2 helper changed:' + name)
    test = p['test']
    raw = (ROOT / test['asset']).read_bytes()
    require(digest(raw) == test['sha256'], 'R2 native driver changed')
    require(re.findall(r'@Test\s+fun\s+(\w+)\(',raw.decode()) == test['methods'], 'R2 method inventory changed')
    require(not (ROOT / test['file']).exists(), 'R2 temporary driver leaked')
    require(len(test['methods']) == p['expectedMethods'] == 6, 'R2 fixed method count changed')
    require(all(p['scope'].values()), 'R2 scope changed')
    return identity, p


@contextlib.contextmanager
def overlay():
    identity, p = guard()
    before = {name:(ROOT/name).read_bytes() for name in identity['files']}
    path = ROOT / p['test']['file']
    try:
        for name, item in identity['files'].items():
            (ROOT/name).write_bytes((ROOT/item['asset']).read_bytes())
        path.write_bytes((ROOT/p['test']['asset']).read_bytes())
        yield identity, p
    finally:
        for name, raw in before.items():
            (ROOT/name).write_bytes(raw)
        path.unlink(missing_ok=True)
        guard()


def sources():
    identity, _ = r1.spec()
    p = protocol()
    values = r1.base_sources()
    for name, item in identity['files'].items():
        values[name] = item['candidate']
        values[item['asset']] = item['candidate']
    for name in p['helperHashes']:
        values[name] = ci.digest(ROOT/name)
    for name in ('.github/scripts/terminal-306-tui-validation-r2.py',
                 'benchmarks/terminal-306-tui-validation-r2/protocol.json',p['test']['asset']):
        values[name] = ci.digest(ROOT/name)
    values[p['test']['file']] = p['test']['sha256']
    return values


def build():
    r1.ci_only()
    guard()
    OUT.mkdir(parents=True, exist_ok=False)
    ci.sources = sources
    with overlay():
        subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true',
            '-PterminalViewportTests=true','-PincludeX86_64AbiForTests=true',':app:assembleTerminaltest',
            ':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
        ci.prepare()
        manifest = json.loads((ci.OUT/'manifest.json').read_bytes())
    require(manifest['sources'] == sources(), 'R2 manifest source mismatch')
    require(manifest['target_release_derived'] is True and manifest['target_debuggable'] is False, 'Not Release-derived')
    save(OUT/'manifest.json',manifest)
    save(OUT/'protocol.json',protocol())


def decode(text):
    return [json.loads(line.split('TUI306_R2_CASE ',1)[1]) for line in text.splitlines() if 'TUI306_R2_CASE ' in line]


def validate_input(v, case):
    phase = v['phase']
    glyph = phase.startswith('glyph-')
    row = v['row']
    require(type(row) is int and 1 <= row <= v['physicalRows'] <= 80, 'Invalid row')
    begin = 48 if glyph else 3
    dragging = phase in ('drag','hold-drag')
    end = 7 if dragging else begin
    expected = f'\x1b[<0;{begin};{row}M'
    if dragging:
        expected += ''.join(f'\x1b[<32;{column};{row}M' for column in range(4,8))
    expected += f'\x1b[<0;{end};{row}m'
    require(v['expectedHex'] == expected.encode().hex() and v['pointerColumnRange'] == [begin,end], 'Independent expected bytes differ')
    require(v['touchTargetSource'] == ('actual-glyph-bounds' if glyph else 'nominal-cell-control'), 'Wrong touch target source')
    if glyph:
        require(row % 8 == int(phase.split('-')[1]), 'Glyph case changed')
    require(type(v['cellWidth']) is int and v['cellWidth'] > 0 and math.isfinite(v['targetLocalX']) and v['targetLocalX'] >= 0,
            'Invalid actual glyph geometry')
    require(v['nominalColumnAtGlyph'] == int(v['targetLocalX'] / v['cellWidth']) + 1, 'Nominal-hit diagnostic differs')
    require(re.fullmatch(r'(?:[0-9a-f]{2})*',v['receivedHex']) is not None, 'Invalid PTY receipt')
    require(v['wireMatches'] is (v['expectedHex'] == v['receivedHex']), 'Wire comparison differs')
    events = v['inputEvents']
    require(all(type(e['seq']) is int and type(e['bytes']) is int and e['bytes'] >= 0 and
        e['stage'] in ('UI_INPUT','INPUT_ENQUEUED','INPUT_WRITTEN') for e in events), 'Bad input events')
    require(all(a['seq'] < b['seq'] for a,b in zip(events,events[1:])), 'Input sequence not monotonic')
    enqueued = [e['bytes'] for e in events if e['stage'] == 'INPUT_ENQUEUED']
    written = [e['bytes'] for e in events if e['stage'] == 'INPUT_WRITTEN']
    ui = sum(e['stage'] == 'UI_INPUT' for e in events)
    require(enqueued == written and written and written[-1] == 1 and len(written) == ui + 1 == v['uiInputCount'] + 1,
            'Two FIFO queues or sentinel differ')
    require(sum(written) == v['inputWrittenBytes'] == len(bytes.fromhex(v['receivedHex'])) + 1, 'PTY byte conservation differs')
    sentinel = 'device306-' + case + '-' + phase + '-untouched'
    require(v['clipboardUnchanged'] is (v['clipboardSha256'] == digest(sentinel.encode())), 'Clipboard receipt differs')
    require(v['traceDropped'] == 0, 'Missing trace input events')
    findings = []
    if not v['wireMatches']: findings.append(phase + ':wire-mismatch')
    if not v['clipboardUnchanged']: findings.append(phase + ':unexpected-clipboard')
    if v['localNonEditableSelections'] != 0: findings.append(phase + ':local-selection')
    if v['pan'] or v['selection']: findings.append(phase + ':mode-not-mouse-seloff')
    return findings


def validate_geometry(v, p):
    require(v['gridUnchanged'] is True and v['modelColumns'] == v['columns'] == 80 and
            v['modelRows'] == v['rows'] and v['modelRevision'] == v['revision'], 'Geometry model/grid drift')
    rows = v['measuredRows']
    require(rows and len({r['row'] for r in rows}) == len(rows), 'Empty/duplicate measured rows')
    by_column = collections.defaultdict(list)
    for r in rows:
        require(1 <= r['row'] <= v['rows'] and r['caseModulo'] == r['row'] % 8, 'Wrong mixed row ID')
        require(r['rowHeight'] == r['layoutHeight'] and r['rowHeight'] > 0 and r['layoutWidth'] > 0, 'Row height layout mismatch')
        require(re.fullmatch('[a-f0-9]{64}',r['textSha256']) is not None, 'No exact row Text proof')
        require([m['column'] for m in r['markers']] == p['geometry']['markerColumns'], 'Marker inventory differs')
        require(all(a['offset'] < b['offset'] for a,b in zip(r['markers'],r['markers'][1:])), 'Marker text order differs')
        for m in r['markers']:
            require(type(m['offset']) is int and m['offset'] >= 0 and all(math.isfinite(m[k]) for k in ('left','right','center','width')), 'Bad marker geometry')
            require(m['right'] > m['left'] and abs(m['width'] - (m['right'] - m['left'])) < 0.01 and
                    abs(m['center'] - (m['right'] + m['left']) / 2) < 0.01, 'Marker geometry inconsistent')
            by_column[m['column']].append(r['rowLeftOnScreen'] + m['center'])
    # Do not discard heights/positions that reveal the problem. They are diagnostic findings.
    spans = {str(c):max(xs)-min(xs) for c,xs in by_column.items()}
    return dict(phase=v['phase'],rows=len(rows),types=sorted({r['caseModulo'] for r in rows}),
        sameLogicalColumnCenterSpreadPx=spans,
        hasMisalignedColumns=any(x > p['geometry']['alignmentTolerancePx'] for x in spans.values()),
        bottomGap=v['bottomGap'],ime=v['ime'],alternate=v['alternate'])


def validate(records, p, installed):
    cases = [r for r in records if 'executionComplete' in r]
    cleanup = [r for r in records if r.get('cleanup') is True]
    expected = p['requiredCases']
    require(collections.Counter(r['case'] for r in cases) == collections.Counter(expected), 'R2 missing/duplicate native cases')
    require(collections.Counter(r['case'] for r in cleanup) == collections.Counter(expected) and all(r['closed'] for r in cleanup), 'R2 cleanup incomplete')
    summaries = []
    for r in cases:
        require(r['executionComplete'] is True and r['nativePty'] is True and r['runtimeTuiRequired'] is True and
                r['widthCandidateIncluded'] is True and r['productionChanged'] is False and r['performanceMeasured'] is False and
                r['allReportedDeviceBugsFixed'] is False, 'Wrong R2 scope')
        require(r['targetApkSha256'] == installed['target'], 'Native candidate APK identity differs')
        require(r['mode'] == r['case'].split('-',1)[1], 'Renderer differs')
        obs = r['observations']
        for v in obs:
            require(v['tui'] is True and v['selection'] is False, 'Not the user-required TUI/SEL-off scenario')
            if 'bottomGap' in v:
                require(0 <= v['writers'] <= 1 and v['viewportHeight'] > 0, 'Invalid viewport writer/size')
                if v['bottomGap'] is not None:
                    require(abs(v['bottomGap'] - (v['viewportBottom'] - v['contentBottom'])) < 0.01, 'Bottom gap not reproducible')
        findings = []
        geometry = None
        if r['case'].startswith('mouse-'):
            require([v['phase'] for v in obs] == ['initial'] + p['mouse']['phases'] + ['explicit-osc52-control'], 'R2 mouse phases differ')
            for v in obs[1:-1]: findings += validate_input(v, r['case'])
            require(obs[-1]['clipboardMatches'] is True, 'No explicit OSC52 control')
        elif r['case'].startswith('width-'):
            require([v['phase'] for v in obs] == p['width']['phases'], 'Width phases differ')
            require([v['columns'] for v in obs[1:]] == [80,100,100,120], 'Width presets differ')
            require(len({v['rows'] for v in obs}) == 1, 'Width-only test changed rows')
            require(obs[1]['following'] is False and obs[3]['following'] is True, 'Wrong prior AUTO/LOCK choices')
            require(obs[2]['preservedPriorMode'] is (not obs[2]['following']) and
                    obs[4]['preservedPriorMode'] is obs[4]['following'], 'Width verdict differs')
            if obs[2]['following']: findings.append('width:LOCK-became-AUTO')
            if not obs[4]['following']: findings.append('width:AUTO-became-LOCK')
        else:
            require([v['phase'] for v in obs] == ['initial'] + p['geometry']['phases'], 'Geometry phases differ')
            require([v['ime'] for v in obs[1:]] == [False,True,True,False,False,False], 'IME sequence differs')
            require([v['alternate'] for v in obs[1:]] == [False,False,False,False,True,False], 'ALT sequence differs')
            require(all(v['following'] for v in obs), 'TUI geometry is no longer AUTO')
            require(len({v['rows'] for v in obs}) == 1, 'IME changed the physical PTY grid')
            geometry = [validate_geometry(v,p) for v in obs[1:]]
            require(set(geometry[0]['types']) == set(range(8)), 'Mixed-main lacks one predefined glyph type')
        require(findings == r['findings'], 'Raw native findings do not match')
        summary = dict(case=r['case'],findings=findings,allRuntimeTui=True,geometry=geometry)
        if geometry is not None:
            gaps = {g['phase']:g['bottomGap'] for g in geometry}
            base = gaps['mixed-main']
            summary['bottomGapGrowthAfterReturnPx'] = {k:(gaps[k]-base if base is not None and gaps[k] is not None else None)
                for k in ('mixed-ime-closed','mixed-return')}
        summaries.append(summary)
    width_passed = all(not c['findings'] for c in summaries if c['case'].startswith('width-'))
    return cases, cleanup, summaries, width_passed


def run():
    r1.ci_only()
    identity, p = guard()
    ci.sources = sources
    ci.OUT = OUT
    manifest = json.loads((OUT/'manifest.json').read_bytes())
    require(manifest['sources'] == sources(), 'R2 bundle source mismatch')
    def operation():
        # Reuse ONLY frozen install/hash/signature helpers, bound to this exact expected source map.
        r1.sources = sources
        installed = r1.install(manifest)
        device = {k:ci.output(['adb','shell',*args]).strip() for k,args in (
            ('fingerprint',['getprop','ro.build.fingerprint']),('size',['wm','size']),('density',['wm','density']))}
        require(device['fingerprint'] == p['expectedFingerprint'], 'R2 guest differs from R1')
        save(OUT/'device.json',device)
        save(OUT/'installed.json',installed)
        shots = OUT/'screenshots'
        shots.mkdir(exist_ok=True)
        logs = []
        failures = []
        for method in p['test']['methods']:
            ci.output(['adb','shell','am','force-stop',ci.PACKAGE])
            ci.output(['adb','shell','am','force-stop',ci.TEST_PACKAGE])
            path = OUT/(method+'.log')
            try:
                with path.open('w') as stream:
                    r = subprocess.run(['adb','shell','am','instrument','-w','-r','-e','class',p['test']['classname']+'#'+method,
                        ci.TEST_PACKAGE+'/androidx.test.runner.AndroidJUnitRunner'],stdout=stream,stderr=subprocess.STDOUT,timeout=380)
                require(r.returncode == 0, 'R2 instrumentation exit failed')
                ci.require_instrumentation(path.read_text(),expected=1)
            except (ValueError,OSError,subprocess.SubprocessError) as error:
                failures.append(method+': '+str(error)[:600])
                if path.exists(): ci.notice('error','306 TUI R2 '+method,ci.failure_excerpt(path))
            finally:
                logs.append(path.read_text() if path.exists() else '')
                result = subprocess.run(['adb','pull',f'/sdcard/Android/data/{ci.PACKAGE}/files/tui306-r2',str(shots)],
                    capture_output=True,text=True,timeout=90)
                save(OUT/(method+'-screenshots-pull.json'),dict(exit=result.returncode,stdout=result.stdout,stderr=result.stderr))
                save(OUT/'original-records.json',decode('\n'.join(logs)))
        (OUT/'instrumentation.log').write_text('\n'.join(logs))
        require(not failures,'; '.join(failures))
        cases, cleanup, results, passed = validate(decode('\n'.join(logs)),p,installed)
        hashes = {}
        for case in cases:
            for remote in case['screenshots']:
                matches = list(shots.rglob(Path(remote).name))
                require(len(matches) == 1, 'R2 missing/duplicate screenshot')
                hashes[Path(remote).name] = ci.digest(matches[0])
        require(len(hashes) == p['expectedScreenshots'], 'R2 screenshot inventory differs')
        report = dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],protocolSha256=PROTOCOL_SHA,
            r1IdentitySha256=r1.IDENTITY_SHA,candidateHashes=p['candidateHashes'],manifest=manifest,installedApkHashes=installed,
            device=device,cases=cases,cleanup=cleanup,analysis=results,screenshotHashes=hashes,diagnosticExecutionComplete=True,
            widthPagePassed=passed,allReportedDeviceBugsFixed=False,productionChanged=False,productionAdopted=False,performanceMeasured=False)
        save(OUT/'report.json',report)
        compact = dict(sourceSha=report['sourceSha'],runId=report['runId'],protocolSha256=PROTOCOL_SHA,
            reportSha256=ci.digest(OUT/'report.json'),widthPagePassed=passed,diagnosticExecutionComplete=True,
            cases=[dict(case=c['case'],findings=c['findings'],geometryMisalignedPhases=[g['phase'] for g in c['geometry'] or [] if g['hasMisalignedColumns']]) for c in results],
            allReportedDeviceBugsFixed=False,productionChanged=False)
        save(OUT/'summary.json',compact)
        ci.notice('notice','306 TUI R2 width and geometry',json.dumps(compact,separators=(',',':')))
        require(passed,'R1 width candidate failed the TUI page gate; original evidence retained')
    ci.emulator(operation=operation)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action',choices=('guard','build','run'))
    args = parser.parse_args()
    if args.action == 'guard':
        _, p = guard()
        print(json.dumps(dict(protocolSha256=PROTOCOL_SHA,candidateHashes=p['candidateHashes'],methods=p['expectedMethods'],productionChanged=False),indent=2))
    elif args.action == 'build': build()
    else: run()


if __name__ == '__main__':
    try: main()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as error:
        ci.notice('error','306 TUI R2 stopped',type(error).__name__+': '+str(error)[:2400])
        raise SystemExit(1)
