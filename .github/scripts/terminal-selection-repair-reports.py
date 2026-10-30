"""Bounded selection diagnosis. Observed errors are NOT successful acceptance."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zlib

ROOT = Path(__file__).resolve().parents[2]
BASE = '62d00ca2cda005b87d1ca92fe505bd0c14f5ce20'
PREFIX = 'app/src/main/java/me/rerere/rikkahub/ui/pages/container/'
DIAGNOSTIC_SOURCE = tuple(PREFIX + p for p in ('TerminalVirtualSelectionUi.kt', 'TerminalViewportBinding.kt', 'TerminalLazyItemExecutor.kt'))
TEST_FILE = 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalSelectionRepairTest.kt'
TEST_CLASS = 'me.rerere.rikkahub.viewporttest.TerminalSelectionRepairTest'
METHODS = ('selectionDiagnosticWordWithLegacyControl', 'selectionDiagnosticHandleWithLegacyControl')
CASES = tuple(f'{kind}-{mode}' for kind in ('word', 'drag') for mode in ('chunkedLayers', 'fullVirtual'))
spec = importlib.util.spec_from_file_location('selection_legacy_report', Path(__file__).with_name('terminal-tui-history-reports.py'))
legacy = importlib.util.module_from_spec(spec); spec.loader.exec_module(legacy)
require = legacy.require
OUT = ROOT / 'artifacts/terminal-selection-repair'


def git(*args):
    return subprocess.check_output(['git', '-c', 'core.fileMode=false', *args], cwd=ROOT, timeout=30)


def guard(working=False):
    paths = git('diff', '--name-only', BASE, *([] if working else ['HEAD']), '--', 'app/src/main').decode().splitlines()
    require(set(paths) == set(DIAGNOSTIC_SOURCE), 'Diagnostic exceeded its three observational files')
    require(not git('diff', '--name-only', BASE, *([] if working else ['HEAD']), '--', 'app/build.gradle.kts',
                    'gradle/libs.versions.toml', 'app/src/main/res'), 'Build/font/dependency changed')
    if not working:
        require(not git('diff', '--name-only', 'HEAD', '--', 'app/src/main'), 'Dirty production checkout')
    return dict(baseline=BASE, productionTree=git('rev-parse', 'HEAD:app/src/main').decode().strip(), changed=paths,
                observational=True, acceptance=False)


def decode(text):
    parts = legacy.records(text, 'SELECTION_DIAGNOSTIC_PART ')
    inventory = {}
    for p in parts:
        name = p.get('case')
        require(name in CASES and type(p.get('part')) is int and type(p.get('parts')) is int and
                1 <= p['part'] <= p['parts'] <= 128 and re.fullmatch('[a-f0-9]{64}', p.get('sha256', '')), 'Invalid diagnostic fragment')
        entry = inventory.setdefault(name, dict(parts=p['parts'], sha256=p['sha256'], chunks={}))
        require(entry['parts'] == p['parts'] and entry['sha256'] == p['sha256'] and p['part'] not in entry['chunks'],
                'Duplicated or mixed diagnostic fragments')
        entry['chunks'][p['part']] = p['data']
    require(set(inventory) == set(CASES), 'Missing diagnostic control/candidate case')
    values = {}
    for name in CASES:
        e = inventory[name]
        require(set(e['chunks']) == set(range(1, e['parts'] + 1)), 'Incomplete diagnostic trace')
        raw = zlib.decompress(base64.b64decode(''.join(e['chunks'][n] for n in sorted(e['chunks'])), validate=True))
        require(len(raw) <= 8 * 1024 * 1024 and hashlib.sha256(raw).hexdigest() == e['sha256'], 'Diagnostic content hash mismatch')
        value = json.loads(raw)
        require(value.get('case') == name and value.get('suite') == 'terminal-selection-diagnostic-v1' and
                value.get('acceptance') is False and value.get('overflow') == 0, 'Invalid observational identity')
        require(type(value.get('events')) is list and 1 <= len(value['events']) <= 8192, 'Unbounded/missing diagnostic events')
        previous = 0
        for index, event in enumerate(value['events']):
            require(event.get('sequence') == index and type(event.get('timeNs')) is int and event['timeNs'] >= previous,
                    'Non-monotonic diagnostic record')
            previous = event['timeNs']
        result = value['result']
        require(result.get('mode') == name.split('-', 1)[1] and result.get('historyRows') == 10000 and
                re.fullmatch('[a-f0-9]{64}', result.get('textSha256', '')) and result.get('finished') is True,
                'Incomplete diagnostic workload')
        require(result.get('virtual') is name.endswith('fullVirtual') and result.get('retainedRows') == result.get('pointerRows') == 0,
                'Wrong path or diagnostic tree retained')
        require(value['events'][-1]['stage'] == 'disposed', 'Missing cleanup event')
        require(type(value.get('pid')) is int and value['pid'] > 0 and value.get('fingerprint') and
                all(re.fullmatch('[a-f0-9]{64}', value.get(k, '')) for k in ('targetApk', 'testApk')), 'Missing diagnostic APK identity')
        if name.startswith('word-'):
            require(type(result.get('wordMatches')) is bool and type(result.get('copyMenu')) is bool, 'No word result')
        else:
            require(type(result.get('handleFound')) is bool, 'No handle result')
            if result['handleFound']:
                drivers = [e for e in value['events'] if e['stage'] == 'driver-finished']
                require(len(drivers) == 1 and all(drivers[0].get(k) == v for k, v in dict(moves=220,events=222,frames=222).items()),
                        'Different gesture/frame inventory')
                require(type(result.get('startRecycled')) is bool, 'No measured recycle outcome')
        values[name] = value
    require(len({v['result']['textSha256'] for v in values.values()}) == 1, 'Different diagnostic corpus')
    require(len({v['pid'] for v in values.values()}) <= 2, 'Unexpected diagnostic processes')
    require(len({(v['fingerprint'],v['targetApk'],v['testApk']) for v in values.values()}) == 1, 'Mixed device/APK')
    return values


def report():
    source = guard()
    text = (OUT/'diagnostic-logcat.txt').read_text()
    values = decode(text)
    found = []
    base = ROOT/'benchmarks/terminal-viewport-tests'
    for path in (base/'build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(path).getroot().iter('testcase'):
            if c.get('classname') == TEST_CLASS:
                require(not any(c.find(k) is not None for k in ('failure','error','skipped')), 'Diagnostic did not complete')
                found.append(c.get('name'))
    require(len(found) == 2 and set(found) == set(METHODS), 'Diagnostic method inventory differs')
    hashes = {}
    for path in (base/'build/generated/viewportSources').rglob('*.kt'):
        src = ROOT/'app/src/main/java'/path.relative_to(base/'build/generated/viewportSources')
        require(src.read_bytes() == path.read_bytes(), 'Generated diagnostic production differs')
        hashes[str(src.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    require(len(hashes) >= 40, 'Missing actual production source inventory')
    for key, folder in (('targetApk','release'),('testApk','androidTest/release')):
        files = list((base/'build/outputs/apk'/folder).glob('*.apk')); require(len(files)==1,'Missing fixture APK pair')
        sha = hashlib.sha256(files[0].read_bytes()).hexdigest()
        require(all(value[key] == sha for value in values.values()), 'Installed/built diagnostic APK differs')
    overview = {}
    for name, value in values.items():
        case = dict(value, sourceSha=os.environ['GITHUB_SHA'], runId=os.environ['GITHUB_RUN_ID'])
        (OUT/(name+'.json')).write_text(json.dumps(case,separators=(',',':')))
        summary = dict(result=value['result'], stages={stage:sum(e['stage']==stage for e in value['events'])
            for stage in sorted({e['stage'] for e in value['events']})})
        overview[name] = summary
        print('::notice title=Selection diagnostic '+name+'::'+json.dumps(summary,separators=(',',':'))[:3200])
    data = dict(suite='terminal-selection-diagnostic-v1', sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],
        sourceGuard=source, productionHashes=hashes, testSha256=hashlib.sha256((ROOT/TEST_FILE).read_bytes()).hexdigest(),
        acceptance=False, cases=overview, note='Four observed old/new outcomes; never a pass for selection semantics or phone performance.')
    legacy.publish('Terminal selection diagnosis',data,OUT/'diagnostic-report.json',emit_compact=False)


if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','diagnostic','evidence'));p.add_argument('--working',action='store_true')
    p.add_argument('--case',choices=CASES,default='word-fullVirtual');p.add_argument('--page',type=int,choices=(1,2),default=1)
    args=p.parse_args()
    try:
        if args.action=='guard':print(json.dumps(guard(args.working)))
        elif args.action=='diagnostic':report()
        else:
            path=OUT/(args.case+'.json');raw=path.read_bytes();d=json.loads(raw)
            require(d['sourceSha']==os.environ['GITHUB_SHA'] and d['runId']==os.environ['GITHUB_RUN_ID'],'Wrong diagnostic evidence owner')
            legacy.compact('Selection diagnostic '+args.case,raw,args.page)
    except (ValueError,OSError,KeyError,subprocess.SubprocessError,ET.ParseError) as e:
        print('::error title=Selection diagnosis rejected::'+str(e)[:2400].replace('%','%25').replace('\n','%0A'))
        raise SystemExit(1)
