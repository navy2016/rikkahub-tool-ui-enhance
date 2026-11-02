"""306 functional work: candidate width-intent JVM tests and unchanged-page touch diagnosis.

These are separate arms, not a performance comparison or production adoption.
"""
import argparse, collections, contextlib, difflib, hashlib, importlib.util, json, os, re, shutil, subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'benchmarks/terminal-306-device-repair-r1'
OUT = ROOT / 'artifacts/terminal-306-device-repair-r1'
IDENTITY_SHA = '4cc50efc5c1ca3a8e0174a29e24a67ea138d40f9fe5fb0bac141e9a659bdba17'


def load(name, file):
    s = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    m = importlib.util.module_from_spec(s)
    s.loader.exec_module(m)
    return m


ci = load('device306_ci', 'run-terminal-pipeline.py')
base_sources = ci.sources


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def git(*args):
    return subprocess.check_output(['git', '-c', 'core.fileMode=false', *args], cwd=ROOT, timeout=40)


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def spec():
    raw = (BASE / 'identity.json').read_bytes()
    require(digest(raw) == IDENTITY_SHA, 'R1 identity changed')
    identity = json.loads(raw)
    raw = (BASE / 'protocol.json').read_bytes()
    require(digest(raw) == identity['protocolSha256'], 'R1 protocol changed')
    return identity, json.loads(raw)


def guard():
    identity, p = spec()
    require(git('rev-parse', 'HEAD:app/src/main').decode().strip() == p['baseTree'], 'Live main is not306')
    require(not git('diff', '--name-only', p['baseProductionSha'], '--', 'app/src/main', 'app/build.gradle.kts',
                    'gradle/libs.versions.toml', 'benchmarks/terminal-viewport-tests/build.gradle.kts').strip(), 'Production/build modified')
    require(not git('ls-files', '--others', '--exclude-standard', 'app/src/main').strip(), 'Untracked main source')
    patch = []
    for name, item in identity['files'].items():
        original = git('show', p['baseProductionSha'] + ':' + name)
        raw = (ROOT / item['asset']).read_bytes()
        require(digest(original) == item['base'] and digest(raw) == item['candidate'], 'Candidate hash mismatch:' + name)
        patch.extend(difflib.unified_diff(original.decode().splitlines(True), raw.decode().splitlines(True),
                                         fromfile='a/' + name, tofile='b/' + name))
    require(''.join(patch).encode() == (BASE / 'candidate.patch').read_bytes(), 'Candidate patch differs')
    require(digest((BASE / 'candidate.patch').read_bytes()) == identity['patchSha256'], 'Candidate patch hash differs')
    require(set(Path(n).name for n in identity['files']) == {'ProcessSessionPage.kt', 'TerminalViewportController.kt'}, 'Unexpected candidate scope')
    for item in p['unitInventory'] + [p['candidateUnit'], p['diagnosticTest']]:
        raw = (ROOT / item.get('asset', item['file'])).read_bytes()
        require(digest(raw) == item['sha256'], 'Test source differs:' + item['file'])
        methods = re.findall(r'@Test(?:\([^)]*\))?\s+fun\s+(\w+)\(', raw.decode())
        require(len(item['methods']) == len(set(item['methods'])) and set(item['methods']) <= set(methods), 'Bad method inventory')
    require(sum(len(i['methods']) for i in p['unitInventory']) == 504 and len(p['candidateUnit']['methods']) == 14, 'Wrong JVM inventory')
    require(p['expectedJVM'] == 518 and p['expectedDiagnosticMethods'] == 4, 'Wrong R1 execution counts')
    for item in (p['candidateUnit'], p['diagnosticTest']):
        require(not (ROOT / item['file']).exists(), 'Temporary source leaked:' + item['file'])
    require(not git('diff', '--name-only', p['priorSourceSha'], '--', 'benchmarks/terminal-geometry-c1',
                    'benchmarks/terminal-main-width-lifecycle', 'benchmarks/terminal-physical-metrics-b1',
                    'benchmarks/terminal-trace-lifecycle-v3', '.github/scripts/terminal-frame-identity.py',
                    '.github/scripts/run-terminal-pipeline.py', 'app/src/test', 'app/src/terminaltestAndroidTest').strip(),
            'R1 silently changed prior experiment/assertions')
    page = (BASE / 'candidate/ProcessSessionPage.kt').read_text()
    original = (ROOT / p['pageFile']).read_text()
    expected = original.replace('        resizeAwaitingTuiRedraw.set(false)\n        forceBlankPending.set(hasDeferredGridBlank)',
        '        viewportController.completeWidthChange(frame)\n        resizeAwaitingTuiRedraw.set(false)\n        forceBlankPending.set(hasDeferredGridBlank)', 1)
    expected = expected.replace('        val requestedRows = terminalRows\n',
        '        val requestedRows = terminalRows\n        val widthChangeRequest = if (columnsChanged && !rowsChanged) {\n'
        '            viewportController.beginWidthChange(requestedColumns)\n        } else null\n', 1)
    expected = expected.replace('        }.onFailure {\n            resizeRenderFallbackJob',
        '        }.onFailure {\n            viewportController.cancelWidthChange(widthChangeRequest)\n            resizeRenderFallbackJob', 1)
    require(page == expected, 'R1 page changes beyond width hooks; MOUSE/SEL remains diagnosis-only')
    return identity, p


@contextlib.contextmanager
def overlay(kind):
    identity, p = guard()
    require(kind in ('unit', 'page'), 'Unknown overlay')
    # The device diagnostic NEVER receives the width repair candidate.
    before = {name: (ROOT / name).read_bytes() for name in identity['files']} if kind == 'unit' else {}
    item = p['candidateUnit' if kind == 'unit' else 'diagnosticTest']
    try:
        for name in before:
            (ROOT / name).write_bytes((ROOT / identity['files'][name]['asset']).read_bytes())
        (ROOT / item['file']).write_bytes((ROOT / item['asset']).read_bytes())
        yield identity, p
    finally:
        for name, raw in before.items():
            (ROOT / name).write_bytes(raw)
        (ROOT / item['file']).unlink(missing_ok=True)
        guard()


def ci_only():
    require(os.environ.get('GITHUB_ACTIONS') == 'true', 'Kotlin/Android work is CI-only')


def junit(paths, inventory):
    actual = []; seen = set(); errors = []
    for path in sorted(paths):
        for case in ET.parse(path).getroot().iter('testcase'):
            key = (case.get('classname'), case.get('name'))
            if key in seen: errors.append('duplicate:' + str(key))
            seen.add(key); actual.append(dict(classname=key[0], name=key[1]))
            for tag in ('failure', 'error', 'skipped'):
                e = case.find(tag)
                if e is not None: errors.append(str(key) + ':' + tag + ':' + (e.text or e.get('message', ''))[:800])
    for error in errors[:8]: ci.notice('error', '306 R1 JVM', error)
    require(not errors, 'R1 failed/duplicate/skipped JVM test')
    expected = {(i['classname'], method) for i in inventory for method in i['methods']}
    require(seen == expected and len(actual) == len(seen), 'R1 JVM inventory differs')
    return actual


def unit():
    ci_only(); target = OUT / 'unit'; target.mkdir(parents=True, exist_ok=False)
    with overlay('unit') as (identity, p):
        cmd = ['bash', '.github/scripts/run-terminal-gradle.sh', '-PterminalReleaseTests=true', ':app:testReleaseUnitTest']
        for item in p['unitInventory'] + [p['candidateUnit']]: cmd += ['--tests', item['classname']]
        result = subprocess.run(cmd + ['--stacktrace'], cwd=ROOT, timeout=1800)
        paths = list((ROOT / 'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
        for path in paths: shutil.copyfile(path, target / path.name)
        cases = junit(paths, p['unitInventory'] + [p['candidateUnit']])
        require(result.returncode == 0, 'R1 Release JVM failed')
        report = dict(sourceSha=os.environ['GITHUB_SHA'], runId=os.environ['GITHUB_RUN_ID'], identitySha256=IDENTITY_SHA,
            protocolSha256=identity['protocolSha256'], candidateHashes={n: v['candidate'] for n,v in identity['files'].items()},
            cases=cases, count=len(cases), passed=True, productionAdopted=False, formalReleaseBuilt=False,
            realDeviceRepairVerified=False, performanceMeasured=False)
        save(target / 'report.json', report)
    ci.notice('notice', '306 R1 width intent JVM', json.dumps({k:v for k,v in report.items() if k not in ('cases','candidateHashes')}, separators=(',', ':')))


def sources():
    _, p = spec(); values = base_sources()
    for name in (str(Path(__file__).relative_to(ROOT)), 'benchmarks/terminal-306-device-repair-r1/identity.json',
                 'benchmarks/terminal-306-device-repair-r1/protocol.json', p['diagnosticTest']['asset']):
        values[name] = ci.digest(ROOT / name)
    values[p['diagnosticTest']['file']] = p['diagnosticTest']['sha256']
    return values


def page_build():
    ci_only(); guard(); target = OUT / 'page'; target.mkdir(parents=True, exist_ok=False)
    ci.sources = sources
    with overlay('page'):
        subprocess.run(['bash', '.github/scripts/run-terminal-gradle.sh', '-PterminalPipelineTests=true',
            '-PterminalViewportTests=true', '-PincludeX86_64AbiForTests=true', ':app:assembleTerminaltest',
            ':app:assembleTerminaltestAndroidTest', '--stacktrace'], cwd=ROOT, check=True, timeout=1800)
        ci.prepare(); manifest = json.loads((ci.OUT / 'manifest.json').read_bytes())
    require(manifest['sources'] == sources(), 'Unchanged-page manifest differs')
    require(manifest['target_release_derived'] and not manifest['target_debuggable'], 'Not Release-derived nondebuggable')
    save(target / 'manifest.json', manifest)


def install(manifest):
    source, run = ci.identity()
    require(manifest['sourceSha'] == source and manifest['runId'] == run and manifest['sources'] == sources(), 'Wrong diagnostic bundle')
    require(set(manifest['apks']) == {'target', 'test'}, 'Missing target/test bundle'); installed = {}
    for role in ('target', 'test'):
        a = manifest['apks'][role]; package = ci.PACKAGE if role == 'target' else ci.TEST_PACKAGE
        require(a['package'] == package and ci.digest(ROOT / a['file']) == a['sha256'], 'Diagnostic APK differs')
        remote = f'/data/local/tmp/device306-{role}.apk'; ci.output(['adb','push',str(ROOT/a['file']),remote],180)
        require('Success' in ci.output(['adb','shell','pm','install','-r','-t',remote],180), 'Install failed')
        files = ci.output(['adb','shell','pm','path',package]).strip().splitlines()
        require(len(files) == 1 and files[0].startswith('package:/data/app/'), 'Ambiguous package path')
        value = ci.output(['adb','shell','sha256sum',files[0][8:]]).split()[0]
        require(value == a['sha256'], 'Installed APK identity differs'); installed[role] = value
    ci.output(['adb','shell','pm','grant',ci.PACKAGE,'android.permission.POST_NOTIFICATIONS'])
    require('Success' in ci.output(['adb','shell','cmd','package','compile','-f','-m','speed',ci.PACKAGE],180), 'Speed compilation failed')
    ci.output(['adb','shell','settings','put','secure','show_ime_with_hard_keyboard','1'])
    return installed


def decode(text):
    return [json.loads(line.split('DEVICE306_CASE ', 1)[1]) for line in text.splitlines() if 'DEVICE306_CASE ' in line]


def validate_diagnostic(records, p, installed):
    reports = [r for r in records if 'executionComplete' in r]; cleanup = [r for r in records if r.get('cleanup') is True]
    require(collections.Counter(r['case'] for r in reports) == collections.Counter(p['requiredCases']), 'Missing/extra diagnostic cases')
    require(collections.Counter(r['case'] for r in cleanup) == collections.Counter(p['requiredCases']) and all(r['closed'] for r in cleanup), 'Diagnostic cleanup incomplete')
    for r in reports:
        require(r['executionComplete'] is True and r['nativePty'] is True and r['productionChanged'] is False and
                r['performanceMeasured'] is False and r['allReportedDeviceBugsFixed'] is False, 'Wrong diagnostic scope')
        require(r['targetApkSha256'] == installed['target'], 'Native report uses another target')
        phases = [x['phase'] for x in r['observations']]
        mouse = r['case'].startswith('mouse-')
        expected = ['initial','tap','drag','hold-drag','explicit-osc52-control'] if mouse else ['initial','before-locked-width','after-locked-width','before-auto-width','after-auto-width']
        require(phases == expected, 'Diagnostic phase inventory differs')
        findings = []
        if mouse:
            for v in r['observations'][1:-1]:
                row = v['row']; phase = v['phase']; require(type(row) is int and 1 <= row <= 80, 'Bad mouse row')
                raw = f'\x1b[<0;3;{row}M'
                if phase != 'tap': raw += ''.join(f'\x1b[<32;{column};{row}M' for column in range(4,8))
                raw += f'\x1b[<0;{3 if phase=="tap" else 7};{row}m'
                require(v['expectedHex'] == raw.encode().hex(), 'Expected mouse bytes changed')
                require(re.fullmatch(r'(?:[0-9a-f]{2})*',v['receivedHex']) is not None, 'Malformed byte receipt')
                require(v['wireMatches'] is (v['receivedHex'] == v['expectedHex']), 'Mouse comparison not reproducible')
                require(v['traceDropped'] == 0 and 0 <= v['writers'] <= 1, 'Trace/writer overflow')
                events = v['inputEvents']
                require(all(type(e['seq']) is int and type(e['bytes']) is int and e['bytes'] >= 0 and
                    e['stage'] in ('UI_INPUT','INPUT_ENQUEUED','INPUT_WRITTEN') for e in events), 'Unexpected input event')
                require(all(a['seq'] < b['seq'] for a,b in zip(events,events[1:])), 'Input sequence order differs')
                enqueued = [e for e in events if e['stage'] == 'INPUT_ENQUEUED']
                written = [e for e in events if e['stage'] == 'INPUT_WRITTEN']
                ui_count = sum(e['stage'] == 'UI_INPUT' for e in events)
                require(v['uiInputCount'] == ui_count and len(enqueued) == len(written) == ui_count + 1,
                    'Unfenced UI/manager input or missing direct terminator')
                require([e['bytes'] for e in enqueued] == [e['bytes'] for e in written] and written[-1]['bytes'] == 1,
                    'Input chunk FIFO/terminator changed')
                require(sum(e['bytes'] for e in written) == v['inputWrittenBytes'] == len(bytes.fromhex(v['receivedHex'])) + 1,
                    'PTY byte receipt and written inventory differ')
                sentinel = 'device306-' + r['case'] + '-' + phase + '-untouched'
                require(v['clipboardUnchanged'] is (v['clipboardSha256'] == digest(sentinel.encode())), 'Clipboard sentinel mismatch')
                if not v['wireMatches']: findings.append(phase + ':wire-mismatch')
                if not v['clipboardUnchanged']: findings.append(phase + ':unexpected-clipboard')
                if v['localNonEditableSelections'] != 0: findings.append(phase + ':local-selection')
                if v['pan'] or v['selection']: findings.append(phase + ':mode-not-mouse-seloff')
            require(r['observations'][-1]['clipboardMatches'] is True, 'OSC52positive control missing')
        else:
            v = r['observations']
            require(v[1]['following'] is False and v[3]['following'] is True, 'Wrong pre-width intent')
            require([v[i]['columns'] for i in (1,2,3,4)] == [80,100,100,120], 'Wrong actual306column presets')
            require(v[2]['preservedPriorMode'] is (not v[2]['following']) and v[4]['preservedPriorMode'] is v[4]['following'], 'Width outcome differs')
            if v[2]['following']: findings.append('width:LOCK-became-AUTO')
            if not v[4]['following']: findings.append('width:AUTO-became-LOCK')
        require(findings == r['findings'], 'Findings are not exactly reproduced')
    return reports, cleanup


def page_run():
    ci_only(); identity, p = guard(); ci.sources = sources
    target = OUT / 'page'; ci.OUT = target; manifest = json.loads((target / 'manifest.json').read_bytes())
    def operation():
        installed = install(manifest)
        device = {k:ci.output(['adb','shell',*args]).strip() for k,args in (
            ('fingerprint',['getprop','ro.build.fingerprint']),('size',['wm','size']),('density',['wm','density']))}
        save(target / 'device.json', device); save(target / 'installed.json', installed)
        logs = []; failed = []; shots = target/'screenshots'; shots.mkdir(exist_ok=True)
        for method in p['diagnosticTest']['methods']:
            ci.output(['adb','shell','am','force-stop',ci.PACKAGE]); ci.output(['adb','shell','am','force-stop',ci.TEST_PACKAGE])
            path = target / (method + '.log')
            try:
                with path.open('w') as stream:
                    r = subprocess.run(['adb','shell','am','instrument','-w','-r','-e','class',p['diagnosticTest']['classname']+'#'+method,
                        ci.TEST_PACKAGE+'/androidx.test.runner.AndroidJUnitRunner'],stdout=stream,stderr=subprocess.STDOUT,timeout=300)
                require(r.returncode == 0, 'Instrumentation exit failed')
                text = path.read_text(); ci.require_instrumentation(text, expected=1)
            except (ValueError,OSError,subprocess.SubprocessError) as error:
                failed.append(method+': '+str(error)[:700])
                if path.exists(): ci.notice('error','306 diagnostic '+method,ci.failure_excerpt(path))
            finally:
                text = path.read_text() if path.exists() else ''; logs.append(text)
                pull = subprocess.run(['adb','pull',f'/sdcard/Android/data/{ci.PACKAGE}/files/device306',str(shots)],capture_output=True,text=True,timeout=90)
                save(target/(method+'-screenshots-pull.json'),dict(exit=pull.returncode,stdout=pull.stdout,stderr=pull.stderr))
        (target/'instrumentation.log').write_text('\n'.join(logs))
        records = decode('\n'.join(logs)); save(target/'original-records.json',records)
        require(not failed, '; '.join(failed))
        reports, cleanup = validate_diagnostic(records, p, installed)
        screenshot_hashes = {}
        for r in reports:
            for remote in r['screenshots']:
                files = list(shots.rglob(Path(remote).name)); require(len(files)==1,'Screenshot missing or duplicate')
                screenshot_hashes[Path(remote).name] = ci.digest(files[0])
        report = dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],identitySha256=IDENTITY_SHA,
            protocolSha256=identity['protocolSha256'],manifest=manifest,installedApkHashes=installed,device=device,
            cases=reports,cleanup=cleanup,screenshotHashes=screenshot_hashes,diagnosticExecutionComplete=True,
            widthCandidateIncluded=False,productionChanged=False,productionAdopted=False,allReportedDeviceBugsFixed=False,performanceMeasured=False)
        save(target/'report.json',report)
        summary = dict(sourceSha=report['sourceSha'],runId=report['runId'],reportSha256=ci.digest(target/'report.json'),
            diagnosticExecutionComplete=True,widthCandidateIncluded=False,productionChanged=False,
            cases=[dict(case=r['case'],findings=r['findings']) for r in reports],allReportedDeviceBugsFixed=False)
        save(target/'summary.json',summary); ci.notice('notice','306 unchanged-page diagnosis',json.dumps(summary,separators=(',',':')))
    ci.emulator(operation=operation)


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('action', choices=('guard','unit','page-build','page-run')); a=parser.parse_args()
    if a.action=='guard':
        identity,p=guard(); print(json.dumps(dict(identitySha256=IDENTITY_SHA,protocolSha256=identity['protocolSha256'],
            candidateFiles=list(identity['files']),expectedJVM=p['expectedJVM'],diagnosticMethods=p['expectedDiagnosticMethods'],productionChanged=False),indent=2))
    elif a.action=='unit': unit()
    elif a.action=='page-build': page_build()
    else: page_run()


if __name__=='__main__':
    try: main()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError,ET.ParseError) as error:
        ci.notice('error','306 R1 validation stopped',type(error).__name__+': '+str(error)[:2400]); raise SystemExit(1)
