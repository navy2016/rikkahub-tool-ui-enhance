"""Full-virtual acceptance: exact scene inventories, bounded visible UI, source identity and all paired samples."""
import argparse
import base64
import hashlib
import io
import importlib.util
import json
import math
import os
from pathlib import Path
from pathlib import PurePosixPath
import re
import statistics
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[2]
BASE = '09d863449e2e16cf8fd1eac95d5e11264934156b'
MODE = 'fullVirtual'
MAIN = 'app/src/main/java/me/rerere/rikkahub/'
ALLOWED = tuple(MAIN + x for x in (
    'data/container/TerminalItemViewport.kt', 'data/container/TerminalPipelineTrace.kt',
    'data/container/TerminalRenderMode.kt', 'data/container/TerminalViewportController.kt',
    'data/container/TerminalFullVirtualGeometry.kt', 'data/container/TerminalVirtualSelection.kt',
    'ui/pages/container/ProcessSessionPage.kt', 'ui/pages/container/TerminalLazyItemExecutor.kt',
    'ui/pages/container/TerminalLazyItemMeasurements.kt', 'ui/pages/container/TerminalPointerMap.kt',
    'ui/pages/container/TerminalRenderControls.kt', 'ui/pages/container/TerminalRenderedRows.kt',
    'ui/pages/container/TerminalRowStore.kt', 'ui/pages/container/TerminalTranscriptViewport.kt',
    'ui/pages/container/TerminalViewportBinding.kt', 'ui/pages/container/TerminalFullVirtualTranscript.kt',
    'ui/pages/container/TerminalPhysicalMeasurements.kt', 'ui/pages/container/TerminalVirtualSelectionUi.kt'))
VIEW_CLASS = 'me.rerere.rikkahub.viewporttest.TerminalFullVirtualInstrumentedTest'
VIEW_FILE = 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalFullVirtualInstrumentedTest.kt'
VIEW_METHODS = (
    'fullVirtualAllScenesRemainVirtualWithRealGeometry',
    'fullVirtualLockedFontsRtlImeAndReentryPreserveTheStableAnchor',
    'fullVirtualSelectionSystemMenuCopiesAWordAndOffscreenSelectAll',
    'fullVirtualSelectionHandleCrossesViewportWithoutLosingOffscreenText',
    'fullVirtualMouseMappingIncludesOffscreenPhysicalOrigin',
    'fullVirtualModeSwitchKeepsOldChoicesAndMeasuredReadPosition',
    'fullVirtualPairedColdAndStreamingMeasurementsKeepEverySample',
)
PAGE_FILE = 'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalFullVirtualPageTest.kt'
PAGE_METHODS = ('fullVirtualPageOrdinaryKeyboardSelectionMouseAndReentry',
                'fullVirtualPageTuiKeyboardSelectionMouseAndReentry')
PAGE_PHASES = ('initial','imeOpen','imeClosed','selection','reentry','mouse','alternate','main','input')
BURSTS = ('imeOpen','imeClosed','selection','reentry')
FIRST_SHA = 'c07100dfb9be25c42e502cb5e0a429fc08cc9006'
FIRST_RUN = 40272608906
FIRST_ARTIFACT = 12629136625
SELECTION_METHODS = (VIEW_METHODS[2], VIEW_METHODS[3])
FIRST_DIGEST = 'a48669cba4a9ca69fa856020fa4e611c8c43c1fef8dfb2a453cb279f6539d729'
FIRST_BYTES = 10343406


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
    return mod


legacy = load('full_virtual_legacy', 'terminal-tui-history-reports.py')
require, integer, records = legacy.require, legacy.integer, legacy.records


def git(*args):
    return subprocess.check_output(['git', '-c', 'core.fileMode=false', *args], cwd=ROOT, timeout=30)


def source_guard(working=False):
    changed = git('diff', '--name-only', BASE, *([] if working else ['HEAD']), '--', 'app/src/main').decode().splitlines()
    if working:
        changed += git('ls-files', '--others', '--exclude-standard', 'app/src/main').decode().splitlines()
    require(set(changed) == set(ALLOWED), 'Full virtual production changed outside its frozen18file scope')
    if not working:
        require(not git('diff', '--name-only', 'HEAD', '--', 'app/src/main'), 'Uncommitted production input')
    return dict(baseline=BASE, productionTree=git('rev-parse', 'HEAD:app/src/main').decode().strip(),
                scope='full-virtual-v1', changed=sorted(changed), preservesDefault=True)


def compare(samples):
    summary, decisions = [], []
    for size in (1000, 5000, 10000):
        old = {r['round']:r for r in samples if r['size']==size and not r['warmup'] and r['mode']=='tuiChunkedLayers'}
        new = {r['round']:r for r in samples if r['size']==size and not r['warmup'] and r['mode']==MODE}
        require(set(old)==set(new)=={1,2,3,4}, 'Missing formal pairing')
        for metric in ('coldNs', 'streamNs'):
            control=statistics.median(x[metric] for x in old.values())
            candidate=statistics.median(x[metric] for x in new.values())
            deltas=[new[n][metric]-old[n][metric] for n in (1,2,3,4)]
            summary.append(dict(size=size, metric=metric, controlMedianNs=control, candidateMedianNs=candidate,
                                pairedDeltaNs=deltas))
            # Frozen before first measurement. Cold includes exact-width discovery, not only lazy layout.
            stream_large = size>=5000 and metric=='streamNs'
            accepted = (candidate<=.8*control and all(d<0 for d in deltas)) if stream_large else candidate<=1.15*control+20_000_000
            decisions.append(dict(size=size,metric=metric,passed=accepted,
                criterion='all4faster,median<=80%control' if stream_large else 'median<=115%control+20ms'))
    return summary, decisions


def validate_page(text):
    require('FULL_VIRTUAL_PAGE_FAILURE ' not in text, 'Full virtual page reported failure')
    cases=records(text,'FULL_VIRTUAL_PAGE ')
    require(len(cases)==2 and {c.get('kind') for c in cases}=={'ordinary','tui'},'Missing ordinary/TUI real page')
    rects, kernels, flows, sizes = [], [], [], []
    for c in cases:
        kind=c['kind']; rows=integer(c,'physicalRows',6)
        require(rows<=80 and c.get('mode')==MODE and c.get('suite')=='terminal-full-virtual-page-v1','Wrong full virtual page identity')
        require(c.get('ptyMode')==('raw' if kind=='tui' else 'cooked') and
                c.get('peerInputDiscipline')=='canonical-test-peer','Wrong independent PTY/peer setup')
        require(all(c.get(k) is True for k in ('nativePty','rawInput','statusAndKeys','survivingHistoryRetained')),
                'Page setup/history contract changed')
        require(integer(c,'traceDropped')==0 and integer(c,'historyRows')==10000 and
                integer(c,'disposalsBeforeCleanup',1)>=1 and c.get('inputEcho')=='ACK:bridge','Incomplete page IO/cleanup')
        require(c.get('gridContract')=='measured-target-ack-kernel-v1','Missing measured grid contract')
        baseline=integer(c,'baselineRequest')
        values=c.get('rectangles')
        require(type(values) is list and tuple(r.get('phase') for r in values)==PAGE_PHASES,'Incomplete/reordered page rectangles')
        draw,stamp,request=0,0,baseline
        for r in values:
            phase=r['phase']; alt=phase=='alternate'
            require(r.get('kind')==kind and r.get('mode')==MODE and r.get('virtual') is True and r.get('fullVirtual') is True,
                    'Real page fell back')
            require(r.get('ime') is (phase=='imeOpen') and r.get('selection') is (phase=='selection') and
                    r.get('pan') is (phase!='mouse') and r.get('alternate') is alt and r.get('tui') is (kind=='tui' or alt),
                    'Wrong real page branch')
            require(r.get('cursorChecked') is (not alt),'Cursor check omitted')
            if not alt: legacy.rectangle(r)
            require(integer(r,'historyRows')==(0 if alt else 10000) and integer(r,'rows')==rows and
                    integer(r,'rowStates')==rows and 0<integer(r,'measuredUiRows')<=128,'History/layout/grid dimensions differ')
            require(integer(r,'columns')==integer(r,'ackColumns')==integer(r,'snapshotColumns')==80 and
                    integer(r,'ackRows')==integer(r,'snapshotRows')==integer(r,'desiredRows')==rows and
                    r.get('pendingResize') is False,'Unacknowledged grid in rectangle')
            measured=min(80,max(6,integer(r,'viewport',1)//integer(r,'cellHeight',1)))
            require(integer(r,'measuredRows')==measured,'Measured target differs from physical viewport/cell')
            if not r['ime']:require(measured==rows,'No-IME target grid did not converge')
            require(r.get('gridSource')==('measured' if phase=='initial' else 'preserved'),'Wrong grid origin')
            require(integer(r,'draw',1)>draw and integer(r,'firstStableDraw',1)<r['draw'] and integer(r,'confirmations')>=2,
                    'Repeated poll or omitted real draw')
            require(integer(r,'timeNs',1)>stamp and integer(r,'request')>=request,'Non-monotonic rectangle provenance')
            draw,stamp,request=r['draw'],r['timeNs'],r['request']
        require(values[0]['request']==baseline,'Baseline was not frozen from initial measured rectangle')
        readings=c.get('kernelReadings')
        require(type(readings) is list and [r.get('phase') for r in readings]==['initial',*BURSTS,'input'],'Missing independent kernel checks')
        stamp=0
        for r in readings:
            require(r.get('kind')==kind and r.get('mode')==MODE and r.get('ime') is (r['phase']=='imeOpen'),'Wrong kernel query scope')
            require(integer(r,'beforeNs',1)>stamp and integer(r,'afterNs',1)>r['beforeNs'],'Kernel times reversed')
            require(integer(r,'rows')==integer(r,'modelRows')==integer(r,'snapshotRows')==rows and
                    integer(r,'columns')==integer(r,'modelColumns')==integer(r,'snapshotColumns')==80,'Kernel/model/snapshot differs')
            require(integer(r,'beforeRequest')==integer(r,'request')>=baseline and r.get('pendingResize') is False,'Resize changed during kernel query')
            require(r.get('envRows')==24 and r.get('envColumns')==80 and
                    r.get('reply')==f'SIZE:{r["phase"]}:{rows} 80;ENV:24 80\r\n','Wrong kernel reply/inherited environment')
            stamp=r['afterNs']
        overlaps=c.get('overlaps')
        require(type(overlaps) is list and [r.get('phase') for r in overlaps]==list(BURSTS),'Missing sustained-output interaction')
        for r in overlaps:
            require(r.get('kind')==kind and r.get('mode')==MODE and r.get('producerStillRunning') is True,'Wrong output overlap source')
            require(integer(r,'beforeTick')<integer(r,'afterTick')<160 and integer(r,'afterDraw')>integer(r,'beforeDraw') and
                    integer(r,'afterRevision')>integer(r,'beforeRevision'),'No new native output/draw during interaction')
            require(integer(r,'beforeRows')==integer(r,'afterRows')==rows and integer(r,'historyRows')==10000,'Output/grid scope changed')
            require(integer(r,'afterRequest')>=integer(r,'beforeRequest')>=baseline,'Applied resize order changed')
            if r['phase']!='reentry':require(r['afterRequest']==r['beforeRequest'],'IME/SEL resized PTY')
            require(integer(r,'detachedTicks')>=(3 if r['phase']=='reentry' else 0),'No output while detached')
        windows=c.get('outputWindows')
        require(type(windows) is list and [r.get('phase') for r in windows]==list(BURSTS),'Missing exact-byte windows')
        for r in windows:
            p=r['phase']; count=sum(len(f'LIVE:{p}:{i:03d} 中文 e\u0301 👩‍💻\r\nbody:{p}:{i:03d}\r\n'.encode()) for i in range(160))+len(f'DONE:{p}\r\n'.encode())
            require(all(integer(r,k)==count for k in ('readBytes','fedBytes','expectedBytes')),'Lost/extra native output bytes')
            require(all(integer(r,k,1)>0 for k in ('windows','published','drawn')),'No exact trace progress')
        timeline=c.get('resizeObservations')
        require(type(timeline) is list and 1<=len(timeline)<=128,'Missing bounded size provenance')
        stamp,request=0,0;detached=False
        for r in timeline:
            require(r.get('kind')==kind and r.get('mode')==MODE and integer(r,'timeNs',1)>stamp and
                    integer(r,'request')>=request,'Invalid size provenance order')
            require(6<=integer(r,'rows')<=80 and integer(r,'columns')==80,'Invalid observed grid')
            stamp,request=r['timeNs'],r['request']
            detached |= r.get('phase')=='reentry' and r.get('mounted') is False
        require(detached,'Missing real page detachment observation')
        rects+=values;kernels+=readings;flows+=overlaps;sizes+=timeline
    for marker,values in (('RECT',rects),('KERNEL',kernels),('OVERLAP',flows),('SIZE',sizes)):
        require(records(text,'FULL_VIRTUAL_PAGE_'+marker+' ')==values,'Different duplicate page evidence: '+marker)
    cleanups=records(text,'FULL_VIRTUAL_PAGE_CLEANUP ');copies=records(text,'FULL_VIRTUAL_PAGE_COPY ')
    require(len(cleanups)==len(copies)==2 and {r.get('kind') for r in cleanups}=={r.get('kind') for r in copies}=={'ordinary','tui'},
            'Missing cleanup/COPY receipts')
    require(all(r.get('sessionClosed') is True and r.get('captureClosed') is True and integer(r,'disposals')>=2 for r in cleanups),
            'Session/capture/page retained')
    require(all(r.get('source')=='unchanged-output-COPY' and r.get('fullVirtual') is True and
                integer(r,'bytes',1)>0 and re.fullmatch('[a-f0-9]{64}',r.get('sha256','')) for r in copies),'COPY contract failed')
    return dict(cases=cases,cleanups=cleanups,copies=copies)


def validate_viewport(text):
    cases=records(text,'FULL_VIRTUAL_CASE ')
    require(len(cases)==7 and {c.get('case') for c in cases}=={'scenes','anchors','copy','drag','mouse','handoff','performance'} and
            all(c.get('passed') is True for c in cases),'Missing/failed full virtual methods')
    rects=records(text,'FULL_VIRTUAL_RECT ')
    expected={(f'{s}-{kind}',p) for s in (1000,5000,10000) for kind in ('tui','ordinary')
              for p in ('initial','stream24','sel','mouse','fullGrid','alt','main')}
    expected|={('locked','ime'),('locked','remount'),('pointer','offscreen-origin')}
    require(len(rects)==len(expected) and {(r.get('case'),r.get('phase')) for r in rects}==expected,'Incomplete scene rectangles')
    for r in rects:
        require(r.get('mode')==MODE and r.get('virtual') is True,'New scene fell back from virtualization')
        require(0<integer(r,'rowStates')<=80 and 0<integer(r,'measuredRows')<=128 and integer(r,'writers')<=1,'Whole history/UI or multiple writers')
        require(integer(r,'screenRows')<=80 and r['rowStates']==r['screenRows'],'Sparse store dimensions differ')
        if r['case'].split('-')[0].isdigit():
            size=int(r['case'].split('-')[0]); phase=r['phase']
            require(integer(r,'historyRows')==(0 if phase=='alt' else size),'History was lost or ALT invented history')
            require(r.get('selection') is (phase=='sel') and r.get('pan') is (phase not in ('mouse','fullGrid','alt')),'Wrong scene branch')
            require(r.get('alternate') is (phase=='alt'),'Wrong alternate state')
        if r.get('cursorChecked') is True:
            legacy.rectangle(r)
    copies=records(text,'FULL_VIRTUAL_COPY ');drag=records(text,'FULL_VIRTUAL_DRAG ')
    require(len(copies)==len(drag)==1,'Missing actual clipboard/handle proof')
    for c in copies+drag:
        require(c.get('virtual') is True and integer(c,'measuredRows')<=128 and integer(c,'bytes',1)>0 and
                re.fullmatch('[a-f0-9]{64}',c.get('sha256','')),'Invalid clipboard proof')
    require(copies[0].get('word') is True and copies[0].get('selectAll') is True and copies[0].get('allRows')==10024,'Select all lost offscreen rows')
    d=drag[0];require(integer(d,'startRow')<integer(d,'firstVisible')<=integer(d,'endRow') and integer(d,'writers')<=1,'Selection never crossed viewport')
    samples=records(text,'FULL_VIRTUAL_PERF ')
    order=[(s,r,m) for s in (1000,5000,10000) for r in range(5)
           for m in (('tuiChunkedLayers',MODE) if r%2==0 else (MODE,'tuiChunkedLayers'))]
    require([(r.get('size'),r.get('round'),r.get('mode')) for r in samples]==order,'Missing/reordered/extra fixed samples')
    for r in samples:
        require(r.get('warmup') is (r['round']==0),'Wrong warmup identity')
        require(integer(r,'stateNs',1)<integer(r,'coldNs',1) and integer(r,'streamNs',1)>0,'Incomplete timing')
        require(integer(r,'historyRows')==r['size'] and integer(r,'writers')<=1,'Performance workload changed')
        require(integer(r,'rowStates')==(24 if r['mode']==MODE else r['size']+24),'Wrong performance row state branch')
        require(0<integer(r,'measuredRows')<=(128 if r['mode']==MODE else r['size']+24),'Invalid UI retention')
        legacy.rectangle(dict(top=r['cursorTop'],bottom=r['cursorBottom'],rowHeight=r['rowHeight'],viewport=r['viewport']))
    disposals=records(text,'FULL_VIRTUAL_DISPOSE ')
    require([(d.get('size'),d.get('round'),d.get('mode')) for d in disposals]==order and
            all(d.get('measuredRows')==d.get('pointerRows')==0 for d in disposals),'Incomplete disposal')
    devices=records(text,'FULL_VIRTUAL_DEVICE ')
    require(len(devices)==1 and integer(devices[0],'pid',1)>0 and bool(devices[0].get('fingerprint')) and
            all(re.fullmatch('[a-f0-9]{64}',devices[0].get(k,'')) for k in ('targetApk','testApk')),'Missing same-process/APK evidence')
    summaries,decisions=compare(samples)
    return dict(cases=cases,rectangles=rects,copies=copies,drag=drag,samples=samples,disposals=disposals,devices=devices,
                summary=summaries,decisions=decisions,performanceAccepted=all(d['passed'] for d in decisions))


def viewport(old=False):
    guard=source_guard()
    cls=legacy.VIEW_CLASS if old else VIEW_CLASS
    methods=legacy.VIEW_METHODS if old else VIEW_METHODS
    base=ROOT/'benchmarks/terminal-viewport-tests'
    found=[]
    for path in (base/'build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(path).getroot().iter('testcase'):
            if c.get('classname')==cls:
                require(not any(c.find(k) is not None for k in ('error','failure','skipped')),'Failed virtual/legacy case')
                found.append(c.get('name'))
    require(len(found)==len(methods) and set(found)==set(methods),'Missing/duplicate JUnit matrix')
    path=ROOT/'artifacts/terminal-validation'/('tui-history-logcat.txt' if old else 'full-virtual-logcat.txt')
    data=legacy.validate_viewport(path.read_text()) if old else validate_viewport(path.read_text())
    hashes={name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in ALLOWED}
    for path in (base/'build/generated/viewportSources').rglob('*.kt'):
        original=ROOT/'app/src/main/java'/path.relative_to(base/'build/generated/viewportSources')
        require(path.read_bytes()==original.read_bytes(),'Generated production source mismatch')
        hashes[str(original.relative_to(ROOT))]=hashlib.sha256(original.read_bytes()).hexdigest()
    apks={}
    for role,folder in (('targetApk','release'),('testApk','androidTest/release')):
        paths=list((base/'build/outputs/apk'/folder).glob('*.apk'));require(len(paths)==1,'Expected one fixture APK pair')
        apks[role]=hashlib.sha256(paths[0].read_bytes()).hexdigest()
    if not old:require(all(data['devices'][0][k]==v for k,v in apks.items()),'Installed/built test APK pair differs')
    data.update(suite='terminal-tui-history-regression-v1' if old else 'terminal-full-virtual-viewport-v1',
        sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],sourceGuard=guard,
        productionHashes=hashes,apkHashes=apks,junit=sorted(found),
        testSha256=hashlib.sha256((ROOT/(legacy.VIEW_FILE if old else VIEW_FILE)).read_bytes()).hexdigest(),
        note='Old TUI assertions preserved; full virtual is a separate candidate. Inclusive timing includes exact cold width; not phone FPS.')
    name='tui-history-regression' if old else 'full-virtual'
    legacy.publish('Terminal '+name,data,ROOT/'artifacts/terminal-validation'/(name+'.json'),emit_compact=False)
    if not old:require(data['performanceAccepted'],'Fixed performance criteria failed; retain every sample')


def correction_source_guard():
    guard=source_guard()
    require(git('rev-parse','HEAD:app/src/main')==git('rev-parse',FIRST_SHA+':app/src/main'),
            'Selection driver correction changed production; cannot reuse prior gates')
    permitted={VIEW_FILE,'.github/scripts/terminal-full-virtual-reports.py','.github/scripts/test_terminal_full_virtual.py',
        '.github/scripts/test_terminal_viewport_shards.py','.github/workflows/terminal-viewport-interactions.yml',
        'benchmarks/terminal-viewport-tests/run-ci.sh','benchmarks/TERMINAL_FULL_VIRTUAL.md','AGENTS.md'}
    changed=set(git('diff','--name-only',FIRST_SHA,'HEAD').decode().splitlines())
    require(changed<=permitted,'Selection driver correction changed another gate/build input')
    current=(ROOT/VIEW_FILE).read_text();old=git('show',FIRST_SHA+':'+VIEW_FILE).decode()
    for method in set(VIEW_METHODS)-set(SELECTION_METHODS):
        start='    @Test fun '+method+'() {'
        def body(source):
            content=source.split(start,1)[1]
            return content.split('\n    @Test ',1)[0].split('\n    private fun sha256',1)[0]
        require(body(current)==body(old),'Frozen already-measured test method changed: '+method)
    def fixture(source):
        return source.split('    private fun terminal(',1)[1].split('\n    private fun done(',1)[0]
    require(fixture(current)==fixture(old),'Frozen fixture/render/settle method changed')
    return guard


def prepare_first():
    require(os.environ.get('GITHUB_ACTIONS')=='true' and
            os.environ.get('GITHUB_REF')=='refs/heads/opt/terminal-verified-a79e7b6','Existing artifact retrieval is CI-only')
    correction_source_guard()
    base='repos/navy2016/rikkahub-tool-ui-enhance/'
    def api(path):return json.loads(subprocess.check_output(['gh','api',base+path],timeout=30))
    run=api(f'actions/runs/{FIRST_RUN}');artifact=api(f'actions/artifacts/{FIRST_ARTIFACT}')
    require(run['head_sha']==FIRST_SHA and run['head_branch']=='opt/terminal-verified-a79e7b6' and
            run['path']=='.github/workflows/terminal-viewport-interactions.yml' and
            run['status']=='completed' and run['conclusion']=='failure' and run['run_attempt']==1,
            'First run identity/conclusion changed')
    require(artifact['workflow_run']['id']==FIRST_RUN and artifact['workflow_run']['head_sha']==FIRST_SHA and
            artifact['digest']=='sha256:'+FIRST_DIGEST and artifact['size_in_bytes']==FIRST_BYTES and not artifact['expired'],
            'First artifact identity/digest changed')
    raw=subprocess.check_output(['gh','api',base+f'actions/artifacts/{FIRST_ARTIFACT}/zip'],timeout=180)
    require(len(raw)==FIRST_BYTES and hashlib.sha256(raw).hexdigest()==FIRST_DIGEST,'First ZIP byte/hash mismatch')
    target=ROOT/'artifacts/full-virtual-first';require(not target.exists(),'Refusing to replace first artifact evidence')
    target.mkdir(parents=True)
    selected={}
    with zipfile.ZipFile(io.BytesIO(raw)) as bundle:
        for m in bundle.infolist():
            p=PurePosixPath(m.filename)
            require(not p.is_absolute() and '..' not in p.parts,'Unsafe first artifact path')
            keep=(m.filename=='artifacts/terminal-validation/full-virtual-logcat.txt' or
                  m.filename.startswith('benchmarks/terminal-viewport-tests/build/outputs/androidTest-results/') and m.filename.endswith('.xml') or
                  m.filename.startswith('benchmarks/terminal-viewport-tests/build/outputs/apk/') and m.filename.endswith('.apk'))
            if not keep:continue
            require(m.file_size<=64*1024*1024 and m.filename not in selected,'Unsafe duplicate/unbounded evidence')
            data=bundle.read(m);file=target/m.filename;file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(data)
            selected[m.filename]=dict(bytes=len(data),sha256=hashlib.sha256(data).hexdigest())
    require(len(selected)==4,'Expected only first log/JUnit/two APKs in CI')
    receipt=dict(run={k:run[k] for k in ('id','head_sha','head_branch','path','status','conclusion','run_attempt')},
        artifact={k:artifact[k] for k in ('id','name','size_in_bytes','digest','workflow_run')},
        archiveSha256=FIRST_DIGEST,files=selected,downloadLocation='cloud-runner-only')
    (target/'source-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print('::notice title=Original failed checkpoint retained::'+json.dumps(dict(run=FIRST_RUN,artifact=FIRST_ARTIFACT,
        sha256=FIRST_DIGEST,bytes=FIRST_BYTES,selectedMembers=len(selected),performanceResampled=False)))


def merge_selection_correction(first_text,current_text):
    cases=records(first_text,'FULL_VIRTUAL_CASE ')
    require(len(cases)==5 and {c.get('case') for c in cases}=={'scenes','anchors','mouse','handoff','performance'} and
            not records(first_text,'FULL_VIRTUAL_COPY ') and not records(first_text,'FULL_VIRTUAL_DRAG '),
            'Original partial success inventory changed')
    corrected=records(current_text,'FULL_VIRTUAL_CASE ')
    require(len(corrected)==2 and {c.get('case') for c in corrected}=={'copy','drag'},'Correction must complete exactly two selection methods')
    for marker in ('FULL_VIRTUAL_PERF ','FULL_VIRTUAL_RECT ','FULL_VIRTUAL_DISPOSE ','FULL_VIRTUAL_DEVICE '):
        require(not records(current_text,marker),'Correction resampled or reran unrelated gate: '+marker)
    frames=records(current_text,'FULL_VIRTUAL_HANDLE_FRAMES ')
    require(len(frames)==1 and frames[0]==dict(moves=220,events=222,frames=222,screenCoordinates=True),
            'Missing fixed platform gesture/frame contract')
    touch=records(current_text,'FULL_VIRTUAL_HANDLE_INPUT ')
    require(len(touch)==1 and all(type(touch[0].get(k)) in (int,float) and math.isfinite(touch[0][k])
        for k in ('screenStartX','screenStartY','screenEndX','screenEndY','popupWindowX','popupWindowY')) and
        touch[0]['screenEndY']>touch[0]['screenStartY'],'Missing screen-coordinate injection receipt')
    devices=records(current_text,'FULL_VIRTUAL_SELECTION_DEVICE ')
    require(len(devices)==2 and {r.get('case') for r in devices}=={'copy','drag'} and
        all(integer(r,'pid',1)>0 and bool(r.get('fingerprint')) and all(re.fullmatch('[a-f0-9]{64}',r.get(k,''))
            for k in ('targetApk','testApk')) for r in devices),'Missing installed selection APK identity')
    data=validate_viewport(first_text+'\n'+current_text)
    data.update(selectionDevices=devices,selectionTouch=touch,selectionFrames=frames)
    return data


def selection_correction():
    guard=correction_source_guard()
    original=ROOT/'artifacts/full-virtual-first'
    first_text=(original/'artifacts/terminal-validation/full-virtual-logcat.txt').read_text()
    current_text=(ROOT/'artifacts/terminal-validation/full-virtual-selection-logcat.txt').read_text()
    first_cases={}
    for p in (original/'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(p).getroot().iter('testcase'):
            if c.get('classname')==VIEW_CLASS:
                require(c.get('name') not in first_cases,'Duplicated original test')
                first_cases[c.get('name')]=any(c.find(t) is not None for t in ('failure','error','skipped'))
    require(set(first_cases)==set(VIEW_METHODS) and {k for k,v in first_cases.items() if v}==set(SELECTION_METHODS),
            'Original failure inventory changed')
    found=[]
    for p in (ROOT/'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(p).getroot().iter('testcase'):
            if c.get('classname')==VIEW_CLASS:
                require(not any(c.find(t) is not None for t in ('failure','error','skipped')),'Corrected selection failed')
                found.append(c.get('name'))
    require(len(found)==2 and set(found)==set(SELECTION_METHODS),'Correction must run exactly the two selection methods')
    data=merge_selection_correction(first_text,current_text)
    require(data['performanceAccepted'],'Preserved first performance decisions failed')
    # Read the actual source copied into both APKs, not a timing-based substitution.
    function='    @Test fun fullVirtualPairedColdAndStreamingMeasurementsKeepEverySample() {'
    current=(ROOT/VIEW_FILE).read_text();old=git('show',FIRST_SHA+':'+VIEW_FILE).decode()
    timed=lambda s:s.split(function,1)[1].split('\n    private fun sha256',1)[0]
    require(timed(current)==timed(old),'Frozen measured workload changed')
    for role,folder in (('targetApk','release'),('testApk','androidTest/release')):
        paths=list((original/'benchmarks/terminal-viewport-tests/build/outputs/apk'/folder).glob('*.apk'))
        require(len(paths)==1 and hashlib.sha256(paths[0].read_bytes()).hexdigest()==data['devices'][0][role],
                'Original measured APK identity differs')
    hashes={name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in ALLOWED}
    for p in (ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources').rglob('*.kt'):
        name=MAIN+p.relative_to(ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources/me/rerere/rikkahub').as_posix()
        require(p.read_bytes()==(ROOT/name).read_bytes(),'Copied current production input differs')
        hashes[name]=hashlib.sha256(p.read_bytes()).hexdigest()
    require(len(hashes)>=44,'Missing generated production source inventory')
    current_apks={}
    for role,folder in (('targetApk','release'),('testApk','androidTest/release')):
        paths=list((ROOT/'benchmarks/terminal-viewport-tests/build/outputs/apk'/folder).glob('*.apk'))
        require(len(paths)==1,'Missing corrected fixture APK pair')
        current_apks[role]=hashlib.sha256(paths[0].read_bytes()).hexdigest()
        require(all(r[role]==current_apks[role] for r in data['selectionDevices']),'Installed selection APK differs from corrected build')
    receipt=json.loads((original/'source-receipt.json').read_text())
    require(receipt['run']['id']==FIRST_RUN and receipt['run']['head_sha']==FIRST_SHA and
            receipt['artifact']['id']==FIRST_ARTIFACT and receipt['artifact']['workflow_run']['id']==FIRST_RUN,
            'Reused artifact ownership differs')
    data.update(suite='terminal-full-virtual-selection-correction-v1',sourceSha=os.environ['GITHUB_SHA'],
        runId=os.environ['GITHUB_RUN_ID'],sourceGuard=guard,originalSourceSha=FIRST_SHA,originalRun=FIRST_RUN,
        originalArtifact=FIRST_ARTIFACT,originalFailures=sorted(SELECTION_METHODS),correctedMethods=sorted(found),
        originalRawSha256=hashlib.sha256(first_text.encode()).hexdigest(),
        correctedRawSha256=hashlib.sha256(current_text.encode()).hexdigest(),performanceResampled=False,
        productionHashes=hashes,correctedApkHashes=current_apks,testSha256=hashlib.sha256((ROOT/VIEW_FILE).read_bytes()).hexdigest(),
        originalArtifactSha256=receipt['archiveSha256'],
        note='Original5methods/30performance samples preserved;2corrected selection methods from a different run. Production tree identical; not one all-success initial run.')
    legacy.publish('Terminal full-virtual-selection-correction',data,
        ROOT/'artifacts/terminal-validation/full-virtual-selection-correction.json',emit_compact=False)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('action',choices=('guard','viewport','oldViewport','prepareFirst','selectionCorrection','evidence'))
    parser.add_argument('--working',action='store_true')
    parser.add_argument('--page',type=int,choices=(1,2),default=1)
    parser.add_argument('--report',choices=('full-virtual','tui-history-regression','full-virtual-page','full-virtual-selection-correction'),default='full-virtual')
    args=parser.parse_args()
    try:
        if args.action=='guard':print(json.dumps(source_guard(args.working)))
        elif args.action=='evidence':
            folder='terminal-pipeline' if args.report=='full-virtual-page' else 'terminal-validation'
            path=ROOT/'artifacts'/folder/(args.report+'.json');raw=path.read_bytes();data=json.loads(raw)
            require(data['sourceSha']==os.environ['GITHUB_SHA'] and data['runId']==os.environ['GITHUB_RUN_ID'],'Wrong compact report identity')
            legacy.compact('Terminal '+args.report,raw,args.page)
        elif args.action=='prepareFirst':prepare_first()
        elif args.action=='selectionCorrection':selection_correction()
        else:viewport(args.action=='oldViewport')
    except (ValueError,OSError,subprocess.SubprocessError,KeyError,ET.ParseError) as e:
        print('::error title=Full virtual evidence rejected::'+str(e)[:2400].replace('%','%25').replace('\n','%0A'))
        raise SystemExit(1)
