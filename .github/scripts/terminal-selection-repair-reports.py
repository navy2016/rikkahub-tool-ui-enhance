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
ACCEPT_CLASS = 'me.rerere.rikkahub.viewporttest.TerminalSelectionAcceptanceTest'
ACCEPT_FILE = 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalSelectionAcceptanceTest.kt'
ACCEPT_METHODS = ('selectionRepairStationaryEdgesKeepExactWords', 'selectionRepairReverseGraphemesAndPopupGeometry',
                  'selectionRepairSceneLifecycleAndClipboardCompatibility')
ORIGINAL_CLASS = 'me.rerere.rikkahub.viewporttest.TerminalFullVirtualInstrumentedTest'
ORIGINAL_METHODS = ('fullVirtualAllScenesRemainVirtualWithRealGeometry',
    'fullVirtualLockedFontsRtlImeAndReentryPreserveTheStableAnchor',
    'fullVirtualSelectionSystemMenuCopiesAWordAndOffscreenSelectAll',
    'fullVirtualSelectionHandleCrossesViewportWithoutLosingOffscreenText',
    'fullVirtualMouseMappingIncludesOffscreenPhysicalOrigin',
    'fullVirtualModeSwitchKeepsOldChoicesAndMeasuredReadPosition')
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


def validate_repair(text):
    cases=legacy.records(text,'FULL_VIRTUAL_CASE ')
    require(len(cases)==6 and {r.get('case') for r in cases}=={'scenes','anchors','copy','drag','mouse','handoff'} and
            all(r.get('passed') is True for r in cases),'Missing original full virtual correctness methods')
    require(not legacy.records(text,'FULL_VIRTUAL_PERF ') and not legacy.records(text,'FULL_VIRTUAL_DISPOSE '),
            'Performance was resampled in selection repair')
    rects=legacy.records(text,'FULL_VIRTUAL_RECT ')
    expected={(f'{s}-{kind}',p) for s in (1000,5000,10000) for kind in ('ordinary','tui')
        for p in ('initial','stream24','sel','mouse','fullGrid','alt','main')}
    expected|={('locked','ime'),('locked','remount'),('pointer','offscreen-origin')}
    require(len(rects)==45 and {(r.get('case'),r.get('phase')) for r in rects}==expected,'Missing affected scene matrix')
    for r in rects:
        require(r.get('mode')=='fullVirtual' and r.get('virtual') is True and 0<r['rowStates']<=80 and
                0<r['measuredRows']<=128 and r['writers']<=1,'Affected scene fell back/materialized history')
        if r.get('cursorChecked') is True:legacy.rectangle(r)
    copies=legacy.records(text,'FULL_VIRTUAL_COPY ');drags=legacy.records(text,'FULL_VIRTUAL_DRAG ')
    require(len(copies)==len(drags)==1,'Original clipboard/drag assertions not completed')
    c=copies[0];d=drags[0]
    require(c.get('word') is True and c.get('selectAll') is True and c.get('allRows')==10024,'Original word/full selection failed')
    require(d['startRow']<d['firstVisible']<=d['endRow'] and d['writers']<=1,'Original drag did not recycle start row')
    for r in copies+drags:
        require(r.get('virtual') is True and 0<r['measuredRows']<=128 and r['bytes']>0 and
                re.fullmatch('[a-f0-9]{64}',r.get('sha256','')),'Missing exact original clipboard data')
    regression=legacy.records(text,'SELECTION_REPAIR_CASE ')
    require(len(regression)==3 and {r.get('case') for r in regression}=={'stationary','reverse','scenes'} and
            all(r.get('passed') is True for r in regression),'New selection regressions incomplete')
    rows=legacy.records(text,'SELECTION_REPAIR_ROW ')
    phases=('stationary-top','stationary-bottom','reverse-ltr','reverse-rtl',
            'ordinary','tui','ime','mouse','fullGrid','alternate','main','handoff','reentry')
    require(len(rows)==13 and {r.get('phase') for r in rows}==set(phases),'Selection branches omitted')
    for r in rows:
        p=r['phase']
        require(r.get('virtual') is True and r.get('selection') is True and
                r.get('pan') is (p!='mouse') and r.get('ime') is (p=='ime') and
                r.get('alternate') is (p=='alternate') and r.get('tui') is (p=='tui') and
                r.get('fullGrid') is (p=='fullGrid'),'Wrong selection branch')
        require(r['historyRows']==(0 if p=='alternate' else 10000) and 0<r['rowStates']<=80 and
                0<r['measuredRows']<=128 and r['writers']<=1 and r['length']>0 and
                re.fullmatch('[a-f0-9]{64}',r.get('sha256','')),'Selection proof invalid')
        legacy.rectangle(r)
    frames=legacy.records(text,'FULL_VIRTUAL_HANDLE_FRAMES ')
    require(frames==[dict(moves=220,events=222,frames=222,screenCoordinates=True)],'Original fixed gesture changed')
    devices=legacy.records(text,'FULL_VIRTUAL_SELECTION_DEVICE ')
    require(len(devices)==2 and {r.get('case') for r in devices}=={'copy','drag'},'Original installed APK identity absent')
    return dict(cases=cases,rectangles=rects,copies=copies,drags=drags,regression=regression,selectionRows=rows,
                frames=frames,selectionDevices=devices,performanceResampled=False)


def repair_report():
    scope=guard();scope.update(observational=False,acceptance=True)
    # The former failed tests and their corpus/assertions have not been edited to obtain a pass.
    original='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalFullVirtualInstrumentedTest.kt'
    require((ROOT/original).read_bytes()==git('show',BASE+':'+original),'Original failing test changed')
    text=(OUT/'repair-logcat.txt').read_text();data=validate_repair(text)
    base=ROOT/'benchmarks/terminal-viewport-tests'
    found=[]
    for p in (base/'build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(p).getroot().iter('testcase'):
            require(not any(c.find(k) is not None for k in ('failure','error','skipped')),'Affected selection case failed')
            found.append((c.get('classname'),c.get('name')))
    expected={(ORIGINAL_CLASS,m) for m in ORIGINAL_METHODS}|{(ACCEPT_CLASS,m) for m in ACCEPT_METHODS}
    require(len(found)==9 and set(found)==expected,'Repair must execute exactly nine affected correctness cases')
    hashes={}
    for p in (base/'build/generated/viewportSources').rglob('*.kt'):
        source=ROOT/'app/src/main/java'/p.relative_to(base/'build/generated/viewportSources')
        require(source.read_bytes()==p.read_bytes(),'Copied repair source differs')
        hashes[str(source.relative_to(ROOT))]=hashlib.sha256(p.read_bytes()).hexdigest()
    require(len(hashes)>=40,'Missing repair production source inventory')
    apks={}
    for key,folder in (('targetApk','release'),('testApk','androidTest/release')):
        paths=list((base/'build/outputs/apk'/folder).glob('*.apk'));require(len(paths)==1,'Missing repair APK pair')
        apks[key]=hashlib.sha256(paths[0].read_bytes()).hexdigest()
        require(all(r[key]==apks[key] for r in data['selectionDevices']),'Original tests installed wrong repair APK')
    data.update(suite='terminal-selection-repair-v1',sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],
        sourceGuard=scope,productionHashes=hashes,apkHashes=apks,junit=[list(v) for v in sorted(found)],
        originalTestSha256=hashlib.sha256((ROOT/original).read_bytes()).hexdigest(),
        addedTestSha256=hashlib.sha256((ROOT/ACCEPT_FILE).read_bytes()).hexdigest(),
        diagnosticRun=41025419455,priorFailedRuns=[40272608906,40620489127],
        note='Original six correctness cases plus three targeted regressions; no rerun of first performance. Prior failures remain failed.')
    legacy.publish('Terminal selection repair',data,OUT/'repair-report.json',emit_compact=False)


if __name__ == '__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','diagnostic','repair','repairEvidence','evidence'));p.add_argument('--working',action='store_true')
    p.add_argument('--case',choices=CASES,default='word-fullVirtual');p.add_argument('--page',type=int,choices=(1,2),default=1)
    args=p.parse_args()
    try:
        if args.action=='guard':print(json.dumps(guard(args.working)))
        elif args.action=='diagnostic':report()
        elif args.action=='repair':repair_report()
        elif args.action=='repairEvidence':
            raw=(OUT/'repair-report.json').read_bytes();d=json.loads(raw)
            require(d['sourceSha']==os.environ['GITHUB_SHA'] and d['runId']==os.environ['GITHUB_RUN_ID'],'Wrong repair evidence owner')
            legacy.compact('Terminal selection repair',raw,args.page)
        else:
            path=OUT/(args.case+'.json');raw=path.read_bytes();d=json.loads(raw)
            require(d['sourceSha']==os.environ['GITHUB_SHA'] and d['runId']==os.environ['GITHUB_RUN_ID'],'Wrong diagnostic evidence owner')
            legacy.compact('Selection diagnostic '+args.case,raw,args.page)
    except (ValueError,OSError,KeyError,subprocess.SubprocessError,ET.ParseError) as e:
        print('::error title=Selection diagnosis rejected::'+str(e)[:2400].replace('%','%25').replace('\n','%0A'))
        raise SystemExit(1)
