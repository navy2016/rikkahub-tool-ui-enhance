"""Native menu/handle diagnostics: preserve all records, publish bounded excerpts, never claim acceptance."""
import argparse
import base64
from collections import Counter
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zlib

ROOT=Path(__file__).resolve().parents[2]
BASE='180c2c461ca6df7f0a34865027769eb1df8d27cf'
SOURCE='app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalVirtualSelectionUi.kt'
FILE='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalNativeSelectionTest.kt'
CLASS='me.rerere.rikkahub.viewporttest.TerminalNativeSelectionTest'
METHODS=('nativeSelectionTopDragAndTouchDispatch','nativeSelectionBottomMenuPlacement')
CASES=tuple(f'{kind}-{mode}' for kind in ('top-drag','bottom-word') for mode in ('chunkedLayers','fullVirtual'))
OUT=ROOT/'artifacts/terminal-native-selection'
spec=importlib.util.spec_from_file_location('native_legacy',Path(__file__).with_name('terminal-tui-history-reports.py'))
legacy=importlib.util.module_from_spec(spec);spec.loader.exec_module(legacy)
require=legacy.require


def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=30)


def guard(working=False):
    changes=git('diff','--name-only',BASE,*([] if working else ['HEAD']),'--','app/src/main').decode().splitlines()
    require(changes==[SOURCE],'Native diagnostic changed outside its single selection UI file')
    require(not git('diff','--name-only',BASE,*([] if working else ['HEAD']),'--','app/build.gradle.kts','gradle/libs.versions.toml',
                    'benchmarks/terminal-viewport-tests/build.gradle.kts','app/src/main/res'),'Diagnostic build/dependencies changed')
    return dict(baseline=BASE,productionTree=git('rev-parse','HEAD:app/src/main').decode().strip(),changes=changes,
                acceptance=False,standingAuthorization=True)


def decode(text):
    grouped={}
    for p in legacy.records(text,'NATIVE_SELECTION_PART '):
        require(p.get('case') in CASES and type(p.get('parts')) is int and type(p.get('part')) is int and
                1<=p['part']<=p['parts']<=128 and re.fullmatch('[a-f0-9]{64}',p.get('sha256','')),'Invalid native fragment')
        g=grouped.setdefault(p['case'],dict(parts=p['parts'],sha256=p['sha256'],chunks={}))
        require((g['parts'],g['sha256'])==(p['parts'],p['sha256']) and p['part'] not in g['chunks'],'Mixed/duplicate native fragment')
        g['chunks'][p['part']]=p['data']
    require(set(grouped)==set(CASES),'Native diagnostic must contain four independent controls/scenes')
    values={}
    for name,g in grouped.items():
        require(set(g['chunks'])==set(range(1,g['parts']+1)),'Incomplete native diagnostic')
        raw=zlib.decompress(base64.b64decode(''.join(g['chunks'][i] for i in sorted(g['chunks'])),validate=True))
        require(len(raw)<=8*1024*1024 and hashlib.sha256(raw).hexdigest()==g['sha256'],'Native source hash mismatch')
        d=json.loads(raw);events=d['events'];result=d['result']
        require(d.get('case')==name and d.get('suite')=='terminal-native-selection-diagnostic-v1' and d.get('acceptance') is False and
                d.get('overflow')==0,'Native diagnosis mislabeled or overflowed')
        require(1<=len(events)<=8192 and [r.get('sequence') for r in events]==list(range(len(events))) and
                all(a['timeNs']<=b['timeNs'] for a,b in zip(events,events[1:])),'Missing/out-of-order native events')
        require(result.get('finished') is True and result.get('historyRows')==10000 and result.get('retainedRows')==result.get('pointerRows')==0,
                'Native diagnostic did not complete/clean up')
        require(result.get('virtual') is name.endswith('fullVirtual') and result.get('mode')==name.rsplit('-',1)[1],'Wrong scene renderer')
        require(events[-1]['stage']=='disposed' and any(e['stage']=='native-geometry' and e.get('phase')=='after-longpress' for e in events),
                'Missing real native geometry')
        if name.startswith('top-drag'):
            require(result.get('handleFound') is True and type(result.get('startRecycled')) is bool,'Missing handle observation')
            done=[e for e in events if e['stage']=='driver-finished']
            require(len(done)==1 and all(done[0].get(k)==v for k,v in dict(moves=220,events=222,frames=222).items()),'Changed original drag workload')
        else:require(type(result.get('wordMatches')) is bool,'No bottom word observation')
        require(type(d.get('pid')) is int and d['pid']>0 and d.get('fingerprint') and all(re.fullmatch('[a-f0-9]{64}',d.get(k,''))
                for k in ('targetApk','testApk')),'Unidentified installed diagnostic APKs')
        values[name]=d
    require(len({d['result']['textSha256'] for d in values.values()})==1,'Corpus changed between native scenes')
    require(len({(d['fingerprint'],d['targetApk'],d['testApk']) for d in values.values()})==1,'Mixed device/APK diagnostics')
    return values


def report():
    scope=guard();values=decode((OUT/'diagnostic-logcat.txt').read_text())
    folder=ROOT/'benchmarks/terminal-viewport-tests'
    methods=[]
    for p in (folder/'build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(p).getroot().iter('testcase'):
            if c.get('classname')!=CLASS:continue
            require(not any(c.find(k) is not None for k in ('failure','error','skipped')),'Native diagnostic failed to complete')
            methods.append(c.get('name'))
    require(len(methods)==2 and set(methods)==set(METHODS),'Native diagnostic method inventory differs')
    for role,path in (('targetApk','release'),('testApk','androidTest/release')):
        apks=list((folder/'build/outputs/apk'/path).glob('*.apk'));require(len(apks)==1,'No exact native fixture APK pair')
        require(all(d[role]==hashlib.sha256(apks[0].read_bytes()).hexdigest() for d in values.values()),'Installed native diagnostic differs from built APK')
    hashes={}
    for p in (folder/'build/generated/viewportSources').rglob('*.kt'):
        source=ROOT/'app/src/main/java'/p.relative_to(folder/'build/generated/viewportSources')
        require(p.read_bytes()==source.read_bytes(),'Generated diagnostic production mismatch')
        hashes[str(source.relative_to(ROOT))]=hashlib.sha256(p.read_bytes()).hexdigest()
    require(len(hashes)>=40,'Missing diagnostic source inventory')
    for name,d in values.items():
        raw=json.dumps(d,separators=(',',':')).encode();(OUT/(name+'.json')).write_bytes(raw)
        es=d['events'];cons=[e for e in es if e['stage']=='scroll-consumed']
        excerpt=[e for e in es if e['stage'] in ('before','after-drag','native-geometry','handle-platform-down','handle-down','handle-start','handle-ended','driver-finished')]
        requests=[e for e in es if e['stage']=='native-menu-request']
        excerpt+=requests[:2]+requests[-2:]
        summary=dict(suite=d['suite'],sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],case=name,
            acceptance=False,result=d['result'],rawSha256=hashlib.sha256(raw).hexdigest(),events=len(es),
            stages=dict(Counter(e['stage'] for e in es)),consumedPixels=sum(e['consumed'] for e in cons),
            consumptionEndpoints=cons[:2]+cons[-2:],excerpts=excerpt,
            note='Bounded causal excerpts. Full original event stream remains in artifact; not successful selection acceptance.')
        (OUT/(name+'-excerpt.json')).write_text(json.dumps(summary,separators=(',',':')))
        print('::notice title=Native selection '+name+'::'+json.dumps({k:v for k,v in summary.items() if k not in ('excerpts','consumptionEndpoints')})[:3200])
    document=dict(suite='terminal-native-selection-diagnostic-v1',sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],
        sourceGuard=scope,productionHashes=hashes,testSha256=hashlib.sha256((ROOT/FILE).read_bytes()).hexdigest(),
        acceptance=False,cases={k:v['result'] for k,v in values.items()})
    legacy.publish('Terminal native selection diagnosis',document,OUT/'diagnostic-report.json',emit_compact=False)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','diagnostic','evidence'));p.add_argument('--working',action='store_true')
    p.add_argument('--case',choices=CASES,default='top-drag-fullVirtual');p.add_argument('--page',type=int,choices=(1,2),default=1);a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard(a.working)))
        elif a.action=='diagnostic':report()
        else:
            raw=(OUT/(a.case+'-excerpt.json')).read_bytes();d=json.loads(raw)
            require(d['sourceSha']==os.environ['GITHUB_SHA'] and d['runId']==os.environ['GITHUB_RUN_ID'],'Wrong native diagnostic excerpt owner')
            legacy.compact('Native selection '+a.case,raw,a.page)
    except (ValueError,KeyError,OSError,subprocess.SubprocessError,ET.ParseError) as e:
        print('::error title=Native selection evidence rejected::'+str(e)[:2400].replace('%','%25').replace('\n','%0A'));raise SystemExit(1)
