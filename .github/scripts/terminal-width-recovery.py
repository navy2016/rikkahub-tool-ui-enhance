"""Independent P2 BASELINE: accepted width-work counters + native-clock MAIN/ALT/MAIN path contrast."""
import argparse,hashlib,importlib.util,json,os,re,shutil,statistics,subprocess,types
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/terminal-width-recovery'
TREE='8caf50d5c40e228f1d5bcc09d3db505757568b68'
PRODUCTION='55db111129c662067345aa99540b506de305da37'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalWidthRecoveryPageTest.kt'
MECHANISM='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalWidthRecoveryMechanismTest.kt'
METHODS=('widthRecoveryOneThousand','widthRecoveryTenThousand')
CASES=('widthRecovery-1000','widthRecovery-10000')
CLASS='me.rerere.rikkahub.pipeline.TerminalWidthRecoveryPageTest'
MECHANISM_SCENES=('initial','mainText','enterAlt0','returnSame0','enterAlt1','returnSame1','fontMain',
    'enterAltFont','fontAlt','returnFont','enterAltColumns','columnsAlt','returnColumns','mainFifo','enterAltFifo','returnFifo')
sp=importlib.util.spec_from_file_location('width_recovery_wire',Path(__file__).with_name('run-terminal-pipeline.py'))
ci=importlib.util.module_from_spec(sp);sp.loader.exec_module(ci)
real=ci.real_clock
require=real.require
original_sources=ci.sources
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def guard():
    require(git('rev-parse','HEAD:app/src/main').decode().strip()==TREE,'P2 baseline production tree changed')
    require(not git('diff','--name-only',PRODUCTION,'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml').strip(),
        'P2 baseline cannot contain any held or rejected production/dependency change')
    source=(ROOT/TEST).read_text()
    require(all(x not in source for x in ('mainClock','waitForIdle','createAndroidComposeRule','performSemanticsAction')),
        'Test clock entered native recovery timing')
    return dict(productionSha=PRODUCTION,productionTree=TREE,productionChanged=False,baselineOnly=True)
def sources():
    data=original_sources()
    for name in (TEST,MECHANISM,'.github/scripts/terminal-width-recovery.py',
        'benchmarks/TERMINAL_WIDTH_RECOVERY.md','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle'):
        data[name]=ci.digest(ROOT/name)
    return data
def mechanism_parse(text):
    rows=real.records(text,'WIDTH_RECOVERY_MECHANISM');done=real.records(text,'WIDTH_RECOVERY_MECHANISM_DONE')
    disposed=real.records(text,'WIDTH_RECOVERY_DISPOSE')
    require([(r['size'],r['scene']) for r in rows]==[(n,s) for n in (1000,10000) for s in MECHANISM_SCENES],
        'Fixed32 mechanism records missing/reordered')
    require(done==[dict(records=32,passed=True,performanceMeasured=False)],'Missing mechanism completion')
    require(len(disposed)==2 and {d['size'] for d in disposed}=={1000,10000} and all(d['measuredRows']==0 for d in disposed),
        'Mechanism disposal evidence missing')
    full={'initial','returnSame0','returnSame1','fontMain','returnFont','returnColumns','returnFifo'}
    for r in rows:
        expected=r['size'] if r['scene'] in full else 1 if r['scene']=='mainFifo' else 0
        require(r['acceptedHistoryMeasurements']==expected and r['requiredHistoryAfterReady']==0,'Baseline accepted work differs')
        require(r['timingMeasured'] is False and 0<r['rowStates']<=80 and 0<r['measuredRows']<=128 and r['writers']<=1,'Bad mechanism scope/geometry')
        if r['scene'].startswith('returnSame'):require(r['sameInitialHistory'] is True,'History changed during same-history return')
        if r['alternate']:require(r['historyRows']==0 and r['scalarCandidates']==0 and r['hasHistoryCheckpoint'] is False,'ALT retained unexpected historical width state')
    return dict(rows=rows,done=done,disposed=disposed)
def mechanism_report():
    scope=guard();data=mechanism_parse((OUT/'mechanism-logcat.txt').read_text());found=[]
    generated=ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources';hashes={}
    for path in generated.rglob('*.kt'):
        original=ROOT/'app/src/main/java'/path.relative_to(generated)
        require(path.read_bytes()==original.read_bytes(),'P2 baseline fixture has an optimization/probe overlay')
        hashes[str(original.relative_to(ROOT))]=ci.digest(path)
    require(len(hashes)>=40,'Copied production inventory incomplete')
    for path in (ROOT/'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(path).getroot().iter('testcase'):
            require(not any(c.find(k) is not None for k in ('failure','error','skipped')),'P2 mechanism method failed')
            found.append((c.get('classname'),c.get('name')))
    require(found==[('me.rerere.rikkahub.viewporttest.TerminalWidthRecoveryMechanismTest','baselineMainAlternateWidthRecoveryCounters')],
        'Wrong mechanism JUnit inventory')
    value=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],**scope,**data,
        methods=found,generatedHashes=hashes,note='Accepted-index cumulative counters,not canceled-fork work or test-clock latency.')
    save(OUT/'mechanism-report.json',value)
    real.wire.legacy.compact('P2 width recovery mechanism',json.dumps({k:v for k,v in value.items() if k!='generatedHashes'},separators=(',',':')).encode())
def conditions(round):return ('refresh','return') if round%2==0 else ('return','refresh')
def validate_cases(text):
    old=real.CASES
    try:real.CASES=CASES;cases=real.case_records(text)
    finally:real.CASES=old
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP')
    require(len(cleanup)==2 and {x['case'] for x in cleanup}==set(CASES) and all(x['closed'] and x['threadStopped'] for x in cleanup),
        'Native baseline cleanup incomplete')
    for c in cases:
        size=int(c['case'].split('-')[1]);require(c['passed'] and c['baselineOnly'] and c['historySize']==size and
            c['mode']=='fullVirtual' and c['clock']=='real-android-no-compose-test-clock','Not the expected unchanged full-virtual baseline')
        expected=[(r,p,a) for r in range(5) for p,a in enumerate(conditions(r))]
        require([(s['index'],s['position'],s['condition']) for s in c['samples']]==expected,'Fixed ten-window condition order differs')
        require([d['phase'] for d in c['phaseDumps']]==[f'r{r}-{a}' for r,p,a in expected],'Missing per-window gfxinfo snapshots')
        require(len(c['gates'])==11 and all(g['virtual'] and g['columns']==80 and g['rows']>=24 for g in c['gates']) and
            len({g['rows'] for g in c['gates']})==1,'Native grid changed')
        for s in c['samples']:
            marker=f'ECHO:{c["case"]}:{s["index"]:02d}:{s["position"]}'
            payload=(('\x1b[?1049l' if s['condition']=='return' else '')+'\x1b[24;1H\x1b[2K'+marker+'\r').encode()
            require(s['outputBytes']==len(payload) and s['outputSha256']==hashlib.sha256(payload).hexdigest(),'Finite PTY bytes differ')
            require(s['warmup'] is (s['index']==0) and s['virtual'] and s['sameInitialHistory'] and s['visibleAfterMeasurement'],
                'Warmup/history identity/visibility differs')
            require(s['beganNs']<=s['uiNs']<=s['publishedNs']<=s['drawNs']<=s['observedNs'] and
                s['fedRevision']<=s['publishedRevision']<=s['drawRevision'],'Recovery event order differs')
            events=s['events'];require(all(a['seq']<b['seq'] and a['ns']<=b['ns'] for a,b in zip(events,events[1:])), 'Nonmonotonic trace')
            for stage in ('OUTPUT_READ','EMULATOR_FED'):require(sum(e['bytes'] for e in events if e['stage']==stage)==len(payload),'Incomplete response bytes')
            require(sum(e['stage']=='UI_INPUT' for e in events)==1 and any(e['stage']=='FRAME_DRAWN' and e['ns']==s['drawNs'] for e in events),'Missing exact response draw')
        require(c['metrics'] and all(len(x)==10 and x[9]>=0 for x in c['metrics']) and
            sum(x[9] for x in c['metrics'])==c['droppedFrameMetrics'],'FrameMetrics inventory differs')
    return cases,cleanup
def merged(intervals):
    out=[]
    for start,end in sorted(intervals):
        if end<=start:continue
        if out and start<=out[-1][1]:out[-1]=(out[-1][0],max(end,out[-1][1]))
        else:out.append((start,end))
    return out
def overlap(lo,hi,spans):return sum(max(0,min(hi,b)-max(lo,a)) for a,b in spans)
def width_slices(raw,pid):
    # real.trace_data retains only main-thread atrace; cold width runs on Dispatchers.Default.
    # Parse all process-owned B/E events, pairing on their ACTUAL tid, without nearest-time matching.
    events={}
    for field,typ,packet in real.pb(raw):
        if field!=1 or typ!=2:continue
        for f,w,bundle in real.pb(packet):
            if f!=1 or w!=2:continue
            for tag,wire,event in real.pb(bundle):
                if tag!=2 or wire!=2:continue
                e=real.fields(event)
                if 3 not in e:continue
                value=real.fields(e[3]).get(2,b'').decode(errors='replace');tid=e.get(2);ts=e.get(1)
                if not isinstance(tid,int) or not isinstance(ts,int):continue
                if value.startswith(f'B|{pid}|') or value.strip() in ('E',f'E|{pid}'):
                    events.setdefault(tid,[]).append((ts,value))
    result=[];open_width=0
    for tid,values in events.items():
        stack=[]
        for ts,value in sorted(values):
            if value.startswith(f'B|{pid}|'):
                stack.append((ts,value.split('|',2)[2].strip()))
            elif stack:
                start,name=stack.pop()
                if name=='Terminal.productionWidth':result.append(dict(tid=tid,startNs=start,endNs=ts))
        open_width+=sum(name=='Terminal.productionWidth' for ts,name in stack)
    return result,open_width
def attribute(case,tr,widths):
    require(not tr['lostFtraceBundles'] and not tr['compactSchedBundles'] and tr['ftraceClocks']==[0],'Width CPU evidence incomplete')
    tids={w['tid'] for w in widths};runs={tid:[] for tid in tids}
    for cpu in {x[0] for x in tr['switches']}:
        events=sorted((x for x in tr['switches'] if x[0]==cpu),key=lambda x:x[1])
        for a,b in zip(events,events[1:]):
            if a[3]==b[2] and a[3] in runs:runs[a[3]].append((a[1],b[1]))
    runs={tid:merged(value) for tid,value in runs.items()};result=[]
    for s in case['samples']:
        d=next(d for d in case['phaseDumps'] if d['phase']==s['phase'])
        before=d['clockBefore']['bootNs']-d['clockBefore']['monoNs'];after=d['clockAfter']['bootNs']-d['clockAfter']['monoNs']
        require(abs(after-before)<=1_000_000,'P2 clock offset drift; no nearest clock guess')
        offset=(before+after)//2;lo=s['uiNs']+offset;hi=s['drawNs']+offset
        selected=[dict(w,startNs=max(lo,w['startNs']),endNs=min(hi,w['endNs'])) for w in widths if w['startNs']<hi and w['endNs']>lo]
        row=dict(phase=s['phase'],index=s['index'],condition=s['condition'],warmup=s['warmup'],clockOffsetDriftNs=abs(after-before),
            widthSliceCount=len(selected),widthWallUnionNs=overlap(lo,hi,merged((w['startNs'],w['endNs']) for w in selected)),
            widthMainRunningNs=sum(overlap(w['startNs'],w['endNs'],runs[w['tid']]) for w in selected if w['tid']==case['pid']),
            widthWorkerRunningNs=sum(overlap(w['startNs'],w['endNs'],runs[w['tid']]) for w in selected if w['tid']!=case['pid']),
            widthWorkerSliceCount=sum(w['tid']!=case['pid'] for w in selected),slices=selected)
        result.append(row)
    return result
def native_report(text,manifest,device,installed):
    cases,cleanup=validate_cases(text);traces=[];all_complete=True
    for method,c in zip(METHODS,cases):
        path=ci.OUT/'latency-traces'/(method+'.pftrace');raw=path.read_bytes()
        require(0<len(raw)<100663296,'P2 trace missing/capped')
        tr=real.trace_data(raw,c['pid']);correlation=real.correlate(c,tr);widths,unfinished=width_slices(raw,c['pid'])
        work=attribute(c,tr,widths);complete=len(correlation)==10 and all(x['match']=='exact-token' for x in correlation) and unfinished==0
        all_complete=all_complete and complete
        traces.append(dict(case=c['case'],sha256=hashlib.sha256(raw).hexdigest(),bytes=len(raw),rows=correlation,widthWork=work,
            systemEvidenceComplete=complete,openWidthSlices=unfinished,lostFtraceBundles=tr['lostFtraceBundles'],
            note='Width wall union and CPU include all process-owned main/worker slices intersecting UI_INPUT→draw,not only final publication.'))
    value=dict(suite='P2-main-alt-width-baseline-v1',sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],
        **guard(),manifest=manifest,device=device,installedApkHashes=installed,cases=cases,cleanup=cleanup,traces=traces,
        windows=20,warmups=4,systemEvidenceComplete=all_complete,optimizationMeasured=False)
    save(OUT/'native-results.json',value)
    summary=[]
    for c,t in zip(cases,traces):
        for condition in ('refresh','return'):
            selected=[(s,r,w) for s,r,w in zip(c['samples'],t['rows'],t['widthWork']) if s['condition']==condition and not s['warmup']]
            require(len(selected)==4,'Four measured transitions per condition required')
            summary.append(dict(size=c['historySize'],condition=condition,n=4,
                inputToDrawNs=[r['inputToDrawNs'] for s,r,w in selected],publishToDrawNs=[r['publishToDrawNs'] for s,r,w in selected],
                inputToDisplayEndNs=[r.get('inputToDisplayEndNs') for s,r,w in selected],
                workerWidthRunningNs=[w['widthWorkerRunningNs'] for s,r,w in selected],mainWidthRunningNs=[w['widthMainRunningNs'] for s,r,w in selected],
                widthWallUnionNs=[w['widthWallUnionNs'] for s,r,w in selected]))
    compact=dict(sourceSha=value['sourceSha'],runId=value['runId'],productionChanged=False,baselineOnly=True,windows=20,warmups=4,
        systemEvidenceComplete=all_complete,summary=summary,resultSha256=ci.digest(OUT/'native-results.json'),
        note='Same unmodified app,refresh vs ALT return path contrast;not candidate A/B,phone benchmark or release acceptance. No speedup claimed.')
    save(OUT/'summary.json',compact);real.wire.legacy.compact('P2 width recovery native',json.dumps(compact,separators=(',',':')).encode())
    require(all_complete,'P2 missing exact system association; originals retained, no replacement sampling')
    return value
def fixtures():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android builds are CI-only');guard()
    subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalViewportTests=true',
        ':benchmarks:terminal-viewport-tests:assembleRelease',':benchmarks:terminal-viewport-tests:assembleReleaseAndroidTest',
        '--stacktrace'],cwd=ROOT,check=True,timeout=1800)
def mechanism():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android tests are CI-only');guard();ci.sources=sources
    sha,run=ci.identity()
    # Emulator bootstrap only, not an APK manifest or measurement result. ci.prepare replaces it.
    save(ci.OUT/'manifest.json',dict(sourceSha=sha,runId=run,sources=sources(),bootstrapMechanismOnly=True))
    def operation():
        env=dict(os.environ,TERMINAL_VIEWPORT_SUITE='widthRecovery',TERMINAL_VIEWPORT_GROUP='widthRecovery')
        subprocess.run(['python3','.github/scripts/run-terminal-with-timeout.py','520','bash',
            'benchmarks/terminal-viewport-tests/run-ci.sh'],cwd=ROOT,env=env,check=True,timeout=550)
        mechanism_report()
    ci.emulator(operation=operation)
def build():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android builds are CI-only');guard();ci.sources=sources
    mechanism=json.loads((OUT/'mechanism-report.json').read_bytes())
    require(mechanism['sourceSha']==os.environ['GITHUB_SHA'] and mechanism['runId']==os.environ['GITHUB_RUN_ID'],'P2 mechanism gate belongs to another source/run')
    subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalViewportTests=true','-PterminalPipelineTests=true','-PincludeX86_64AbiForTests=true',
        '-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','terminalMechanismDependencies',
        ':app:assembleTerminaltest',':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
    ci.prepare();shutil.copyfile(ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json',OUT/'resolved-dependencies.json')
    guard()
def native():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Native runs are CI-only');guard();ci.sources=sources
    manifest=json.loads((ci.OUT/'manifest.json').read_bytes());require(manifest['sources']==sources() and
        set(manifest.get('apks',{}))=={'target','test'} and not manifest.get('bootstrapMechanismOnly'), 'Native bundle sources differ or only bootstrap exists')
    old=ci.real_clock
    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS=CLASS,METHODS=METHODS,report=native_report)
    try:ci.emulator(operation=lambda:ci.run_verified_bundle(manifest,'realClock',emit_evidence=False))
    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
        for name in ('instrumentation.log','pipeline-logcat.txt'):
            path=ci.OUT/name
            if path.exists():ci.notice('error','P2 native '+name,ci.failure_excerpt(path))
        raise
    finally:ci.real_clock=old
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','fixtures','mechanism','mechanismReport','build','native'));a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        elif a.action=='fixtures':fixtures()
        elif a.action=='mechanism':mechanism()
        elif a.action=='mechanismReport':mechanism_report()
        elif a.action=='build':build()
        else:native()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','P2 width recovery stopped',type(e).__name__+': '+str(e)[:2000]);raise SystemExit(1)
