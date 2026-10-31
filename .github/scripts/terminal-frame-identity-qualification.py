"""One fixed BOOTTIME identity qualification on unchanged production; no optimization pairing."""
import argparse,collections,hashlib,importlib.util,json,os,shutil,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/terminal-frame-identity-qualification'
s=importlib.util.spec_from_file_location('identity_ci_wire',Path(__file__).with_name('run-terminal-pipeline.py'))
ci=importlib.util.module_from_spec(s);s.loader.exec_module(ci)
s=importlib.util.spec_from_file_location('frame_identity_engine',Path(__file__).with_name('terminal-frame-identity.py'))
identity=importlib.util.module_from_spec(s);s.loader.exec_module(identity)
real=ci.real_clock;require=identity.require;old_sources=ci.sources
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalFrameIdentityQualificationTest.kt'
METHODS=('identityOneThousandHidden','identityOneThousandShown','identityTenThousandHidden','identityTenThousandShown')
CASES=tuple(identity.protocol()['matrix']['cases'])
FREEZE_SHA='65c42cae1292ef4bcd16b83ba5159650c071a495'

def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def guard():
    p=identity.protocol()
    require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['baseProductionTree'],'Identity qualification changed production')
    require(not git('diff','--name-only',p['baseProductionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml').strip(),'Unexpected production or dependency change')
    require(git('show',FREEZE_SHA+':benchmarks/terminal-frame-identity/protocol.json')==identity.PROTOCOL.read_bytes(),'Protocol changed since independent freeze')
    driver=(ROOT/TEST).read_text()
    require('clock=SystemClock::elapsedRealtimeNanos' in driver and 'System.nanoTime()' not in driver,'Driver/pipeline clock mismatch')
    require('val closed=tailEnd' in driver and 'firstAttempt.timeNanos+500_000_000L' in driver,'Natural cutoff changed')
    for forbidden in ('createAndroidComposeRule','mainClock','registerFrameCommitCallback','waitForIdle'):
        require(forbidden not in driver,'Disallowed sampling helper:'+forbidden)
    return p
def sources():
    data=old_sources()
    for name in (TEST,'.github/scripts/terminal-frame-identity.py','.github/scripts/terminal-frame-identity-qualification.py',
        'benchmarks/terminal-frame-identity/protocol.json','benchmarks/TERMINAL_FRAME_IDENTITY.md',
        'benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle'):
        data[name]=ci.digest(ROOT/name)
    return data
def build():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android builds only in CI');guard();ci.sources=sources
    subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PterminalViewportTests=true','-PincludeX86_64AbiForTests=true',
        '-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','terminalMechanismDependencies',
        ':app:assembleTerminaltest',':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
    ci.prepare();OUT.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json',OUT/'resolved-dependencies.json')
    save(OUT/'protocol.json',identity.protocol());guard()

def decode(text):
    old=real.CASES
    try:real.CASES=CASES;return real.case_records(text)
    finally:real.CASES=old
def validate(cases):
    p=identity.protocol();require(tuple(c['case'] for c in cases)==CASES,'Four frozen cases required')
    expected=[(r,pos,condition) for r in range(p['matrix']['roundsPerCase']) for pos,condition in enumerate(('refresh','return') if r%2==0 else ('return','refresh'))]
    for c in cases:
        _,size,ime=c['case'].split('-');size=int(size);shown=ime=='shown'
        require(c['passed'] is True and c['protocolId']==p['id'] and c['clock']=='CLOCK_BOOTTIME' and
            c['collectorDiagnosisOnly'] is True and c['performanceMeasured'] is False,'Wrong protocol/clock/scope')
        require(c['mode']=='fullVirtual' and c['historySize']==size and c['expectedIme']==shown and not c['baselineOnly'],'Wrong actual page branch')
        require('incrementLaunchCount:' in c['startupReceipt'],'Startup write fence absent')
        require([x['stage'] for x in c['preparation']]==['before-write','after-write','persisted-and-flow-confirmed','before-mount'] and
            all(x['configured'] and not x['init'] for x in c['preparation'][-2:]),'First-mount configuration not proven')
        require([(x['index'],x['position'],x['condition']) for x in c['samples']]==expected,'Fixed eight-window inventory changed')
        require(len(c['gates'])==9 and len({g['rows'] for g in c['gates']})==1 and all(g['columns']==80 and g['rows']>=24 and
            g['virtual'] and g['ime']==shown for g in c['gates']),'Unstable page/grid/IME')
        require([d['phase'] for d in c['phaseDumps']]==[f'r{r}-{cond}' for r,pos,cond in expected],'Original diagnostic dumps missing')
        for sample in c['samples']:
            marker=f'ECHO:{c["case"]}:{sample["index"]:02d}:{sample["position"]}'
            raw=(('\x1b[?1049l' if sample['condition']=='return' else '')+'\x1b[24;1H\x1b[2K'+marker+'\r').encode()
            require(sample['clock']=='CLOCK_BOOTTIME' and sample['warmup'] is False,'Clock/warmup classification changed')
            require(sample['sameInitialHistory'] and sample['visibleAfterMeasurement'] and sample['virtual'],'Missing history/visibility evidence')
            require(sample['outputBytes']==len(raw) and sample['outputSha256']==hashlib.sha256(raw).hexdigest(),'Protocol response differs')
            require(sample['fedRevision']==sample['publishedRevision']==sample['drawRevision'],'Exact response revision not preserved')
            require(sample['beganNs']<=sample['uiNs']<=sample['publishedNs']<=sample['drawNs']<=sample['closedBootNs']<=sample['observedNs'],'Window order invalid')
            require(sample['drawNs']-sample['uiNs']<=p['matrix']['responseAndFirstAttemptDeadlineNs'],'Response attempt deadline exceeded')
            require(sample['closedBootNs']==sample['drawNs']+p['matrix']['naturalObservationTailNs'],'Fixed tail changed')
            events=sample['events'];require(len(events)==sample['eventCount'],'Event inventory length differs')
            require(all(e.get('virtual') is True for e in events if e['stage']=='FRAME_DRAWN'),'Attempt from an unexpected backend')
            for stage in ('OUTPUT_READ','EMULATOR_FED'):require(sum(e['bytes'] for e in events if e['stage']==stage)==len(raw),'Incomplete finite bytes')
            feeds=[e for e in events if e['stage']=='EMULATOR_FED'];require(feeds and feeds[-1]['revision']==sample['fedRevision'],'Final feed identity mismatch')
            require(sum(e['stage']=='UI_INPUT' for e in events)==1 and any(e['stage']=='FRAME_PUBLISHED' and e['ns']==sample['publishedNs'] and
                e['revision']==sample['fedRevision'] for e in events),'Input/publication proof missing')
        for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw')):
            require(0<len(c[key])<8192 and len(c[key])==len(c[rawkey]),'Raw observer inventory mismatch')
            for value,raw in zip(c[key],c[rawkey]):
                require(len(value)==10 and len(raw)==23 and value[9]>=0,'Unexpected FrameMetrics schema')
                require(value[1]==raw[2] and value[2]==raw[3],'Public/raw getter conflict')
    return cases
def observers(c):
    return [[dict(raw=raw,publicIntended=value[1],publicVsync=value[2],callbackBootNs=value[0],dropped=value[9])
        for value,raw in zip(c[key],c[rawkey])] for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw'))]
def report(text,manifest,device,installed):
    p=guard();cases=decode(text);save(OUT/'original-cases.json',cases);validate(cases)
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP')
    require(len(cleanup)==4 and {x['case'] for x in cleanup}==set(CASES) and all(x['closed'] and x['threadStopped'] for x in cleanup),'Cleanup incomplete')
    classified=[];summary=[]
    for method,c in zip(METHODS,cases):
        tracepath=ci.OUT/'latency-traces'/(method+'.pftrace');raw=tracepath.read_bytes()
        status=json.loads((tracepath.parent/(method+'-trace-status.json')).read_bytes());require(status['status']=='captured','Trace retrieval incomplete')
        tr=identity.parse_trace(raw,c['pid']);index=identity.FrameIndex(tr);obs=observers(c)
        rows=[dict(phase=w['phase'],index=w['index'],condition=w['condition'],**identity.classify_window(w,index,obs)) for w in c['samples']]
        info=dict(case=c['case'],rows=rows,traceSha256=tr['traceSha256'],traceBytes=tr['traceBytes'],traceFaults=tr['faults'],
            openSpans=tr['openSpans'],sequenceInitialMarkers=tr['sequenceInitialMarkers'],callbackDropped=[sum(x['dropped'] for x in queue) for queue in obs])
        classified.append(info)
        attempts=[a for r in rows for a in r['attempts'] if a['attempt']=='QUALIFIED']
        summary.append(dict(case=c['case'],windows=len(rows),attempts=len(attempts),
            submissions=dict(collections.Counter(a['submission'] for a in attempts)),presentations=dict(collections.Counter(a['presentation'] for a in attempts)),
            metrics=dict(collections.Counter(a.get('metrics',{}).get('state','UNLINKED') for a in attempts)),
            decidableWindows=sum(r['identityDecidable'] for r in rows),withoutSubmission=sum(r['noSubmittedAttempt'] for r in rows),
            withoutPresentation=sum(r['noPresentedAttempt'] for r in rows),traceFaults=tr['faults']))
    qualified=all(all(r['identityDecidable'] for r in group['rows']) for group in classified)
    value=dict(protocol=p,protocolSha256=identity.PROTOCOL_SHA256,freezeSha=FREEZE_SHA,sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],
        productionChanged=False,performanceAccepted=False,optimizationMeasured=False,qualificationPassed=qualified,
        manifest=manifest,device=device,installedApkHashes=installed,cases=cases,cleanup=cleanup,classifications=classified,summary=summary,
        note='Independent fixed32window BOOTTIME identity qualification. No P2 candidate or retroactive historical acceptance. All attempts/missing/no-submission states retained.')
    save(OUT/'report.json',value)
    compact={k:v for k,v in value.items() if k not in ('manifest','cases','classifications','protocol')};compact['reportSha256']=ci.digest(OUT/'report.json')
    save(OUT/'summary.json',compact);real.wire.legacy.compact('Frame identity qualification',json.dumps(compact,separators=(',',':')).encode())
    require(qualified,'Identity qualification has unknown/ambiguous/conflicting evidence; originals retained, no replacement sampling')
    return value
def run():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Qualification only in CI');guard();ci.sources=sources
    manifest=json.loads((ci.OUT/'manifest.json').read_bytes());require(manifest['sources']==sources(),'Qualification source manifest mismatch')
    old=ci.real_clock
    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS='me.rerere.rikkahub.pipeline.TerminalFrameIdentityQualificationTest',METHODS=METHODS,report=report)
    try:ci.emulator(operation=lambda:ci.run_verified_bundle(manifest,'realClock',emit_evidence=False))
    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
        for name in ('instrumentation.log','pipeline-logcat.txt'):
            path=ci.OUT/name
            if path.exists():ci.notice('error','Identity qualification '+name,ci.failure_excerpt(path))
        raise
    finally:ci.real_clock=old
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','build','run'));a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        elif a.action=='build':build()
        else:run()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','Identity qualification stopped',type(e).__name__+': '+str(e)[:1800]);raise SystemExit(1)
