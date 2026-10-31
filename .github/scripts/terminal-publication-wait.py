"""Trace-only publication scheduler diagnosis on55; no optimized scheduler or rejected P2 overlay."""
import argparse,hashlib,importlib.util,json,os,re,shutil,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'benchmarks/terminal-publication-wait';OUT=ROOT/'artifacts/terminal-publication-wait'
PROTOCOL_SHA='989f03de269a1cdd8ac1e73df4f477710b20b4c10d0ac35865009064c5ebccf1'
FREEZE_SHA='2434e29b498057a2f1670d1769224817cefba402'
PAGE='app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt'
TRACE='app/src/main/java/me/rerere/rikkahub/data/container/TerminalPipelineTrace.kt'
MANAGER='app/src/main/java/me/rerere/rikkahub/data/container/BackgroundProcessManager.kt'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalPublicationWaitTest.kt'
s=importlib.util.spec_from_file_location('publication_wait_ci',Path(__file__).with_name('run-terminal-pipeline.py'))
ci=importlib.util.module_from_spec(s);s.loader.exec_module(ci)
s=importlib.util.spec_from_file_location('publication_wait_identity',Path(__file__).with_name('terminal-frame-identity.py'))
identity=importlib.util.module_from_spec(s);s.loader.exec_module(identity)
real=ci.real_clock;require=real.require;old_sources=ci.sources
METHODS=('publicationOneThousandHidden','publicationOneThousandShown','publicationTenThousandHidden','publicationTenThousandShown')
TAG=' // PUBLICATION_WAIT_PROBE'
def protocol():
    raw=(BASE/'protocol.json').read_bytes();require(hashlib.sha256(raw).hexdigest()==PROTOCOL_SHA,'Frozen publication protocol changed')
    return json.loads(raw)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def guard():
    p=protocol();require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['baseTree'],'Production tree changed')
    require(git('show',FREEZE_SHA+':benchmarks/terminal-publication-wait/protocol.json')==(BASE/'protocol.json').read_bytes(),'Protocol changed after freeze')
    require(not git('diff','--name-only',p['baseProductionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml').strip(),'Live production/dependencies differ')
    return p
def mark(stage,indent=8,flag=None,process='processId',revision=None):
    args=(', virtual = '+flag) if flag is not None else ''
    if revision is not None:args+=', frameRevision = '+revision
    return ' '*indent+f'TerminalPipelineTrace.record({process}, TerminalPipelineStage.{stage}{args})'+TAG+'\n'
def strip_probe(name,text):
    if name==TRACE:
        return '\n'.join(line for line in text.split('\n') if TAG not in line)
    text='\n'.join(line for line in text.split('\n') if TAG not in line)
    if name==PAGE:
        text=text.replace('''        if (renderJob.get()?.isActive == true) {
            return
        }
''','''        if (renderJob.get()?.isActive == true) return
''')
        text=text.replace('''                withFrameNanos {
                } // One publication per display frame; no fixed 33ms polling cadence.
''','''                withFrameNanos { } // One publication per display frame; no fixed 33ms polling cadence.
''')
        text=text.replace('''                val publicationProbeResult = latestPublishTerminalFrame(forced, !syncCompleted)
                if (!publicationProbeResult) {
''','''                if (!latestPublishTerminalFrame(forced, !syncCompleted)) {
''')
    return text
def overlay():
    guard();original={p:(ROOT/p).read_text() for p in (PAGE,TRACE,MANAGER)};out=dict(original)
    def replace(name,old,new):
        require(out[name].count(old)==1,'Probe anchor not unique:'+old[:90]);out[name]=out[name].replace(old,new)
    replace(TRACE,'    ROW_SYNC_STARTED, ROW_SYNC_FINISHED,\n',
        '    ROW_SYNC_STARTED, ROW_SYNC_FINISHED,\n'+''.join('    '+stage+','+TAG+'\n' for stage in protocol()['markers']))
    replace(PAGE,'        val frame = synchronized(terminalEmulator) {\n',
        '        val frame = synchronized(terminalEmulator) {\n'+mark('PUBLICATION_MODEL_LOCK_ACQUIRED',12))
    start=out[PAGE].index('    fun scheduleTerminalRender() {\n');end=out[PAGE].index('\n    LaunchedEffect(processId, appliedTerminalRenderMode',start)
    old=out[PAGE][start:end];body=old
    body=body.replace('    fun scheduleTerminalRender() {\n','    fun scheduleTerminalRender() {\n'+mark('PUBLICATION_REQUEST'))
    body=body.replace('        if (renderJob.get()?.isActive == true) return\n',
        '        if (renderJob.get()?.isActive == true) {\n'+mark('PUBLICATION_COALESCED',12)+'            return\n        }\n')
    body=body.replace('        renderJob.set(scope.launch {\n',mark('PUBLICATION_ENQUEUED')+'        renderJob.set(scope.launch {\n'+mark('PUBLICATION_JOB_START',12))
    body=body.replace('            while (renderPending.getAndSet(false)) {\n',
        '            while (renderPending.getAndSet(false)) {\n'+mark('PUBLICATION_CYCLE_START',16)+mark('PUBLICATION_FRAME_WAIT_START',16))
    body=body.replace('                withFrameNanos { } // One publication per display frame; no fixed 33ms polling cadence.\n',
        '                withFrameNanos {\n'+mark('PUBLICATION_FRAME_CALLBACK',20)+'                } // One publication per display frame; no fixed 33ms polling cadence.\n'+
        mark('PUBLICATION_FRAME_WAIT_END',16)+mark('PUBLICATION_SYNC_CHECK',16,flag='terminalOutputUpdates?.value?.synchronizedOutput ?: terminalEmulator.isSynchronizedOutput()'))
    body=body.replace('                if (resizeAwaitingTuiRedraw.get()) {\n',mark('PUBLICATION_SYNC_DONE',16,flag='syncCompleted')+
        mark('PUBLICATION_RESIZE_CHECK',16,flag='resizeAwaitingTuiRedraw.get()')+'                if (resizeAwaitingTuiRedraw.get()) {\n')
    body=body.replace('                renderPending.set(false) // Capture includes all notifications received while awaiting this frame.\n',
        mark('PUBLICATION_RESIZE_DONE',16)+'                renderPending.set(false) // Capture includes all notifications received while awaiting this frame.\n')
    body=body.replace('                if (!latestPublishTerminalFrame(forced, !syncCompleted)) {\n',
        mark('PUBLICATION_CALL',16,flag='forced')+'                val publicationProbeResult = latestPublishTerminalFrame(forced, !syncCompleted)\n'+
        mark('PUBLICATION_RETURN',16,flag='publicationProbeResult')+'                if (!publicationProbeResult) {\n')
    body=body.replace('                    delay(TERMINAL_GRID_CLEAR_GRACE_MS)\n',mark('PUBLICATION_BLANK_WAIT_START',20)+
        '                    delay(TERMINAL_GRID_CLEAR_GRACE_MS)\n'+mark('PUBLICATION_BLANK_WAIT_END',20))
    body=body.replace('        })\n',mark('PUBLICATION_JOB_END',12)+'        })\n')
    replace(PAGE,old,body)
    replace(MANAGER,'                record.notifications.published(data.size, revision, System.nanoTime(),\n                    record.terminalEmulator.isSynchronizedOutput())\n',
        mark('OUTPUT_NOTIFY_START',16,process='record.processId',revision='revision')+
        '                record.notifications.published(data.size, revision, System.nanoTime(),\n                    record.terminalEmulator.isSynchronizedOutput())\n'+
        mark('OUTPUT_NOTIFY_END',16,process='record.processId',revision='revision'))
    for name in original:require(strip_probe(name,out[name])==original[name],'Probe inverse did not recover original bytes:'+name)
    return original,out

def chain(sample,index=None):
    """Match complete ordered cycles, including a proved pre-window creator; never guess a job."""
    events=sample['events'];prefix=sample.get('prefixEvents',[]);all_events=prefix+events
    require(all(a['seq']<b['seq'] and a['ns']<=b['ns'] for a,b in zip(all_events,all_events[1:])),'Probe event order differs')
    ui=next(e for e in events if e['stage']=='UI_INPUT');pub=next(e for e in events if e['stage']=='FRAME_PUBLISHED' and e['revision']==sample['fedRevision'])
    requests=[];jobs=[];active=None;cycle=None;request=None;enqueued=None
    for e in all_events:
        stage=e['stage']
        if stage=='PUBLICATION_REQUEST':
            request=dict(event=e,coalesced=False);requests.append(request)
        elif stage=='PUBLICATION_COALESCED':
            require(request is not None,'Coalescing without a request');request['coalesced']=True;request['eventCoalesced']=e
        elif stage=='PUBLICATION_ENQUEUED':enqueued=dict(event=e,request=request)
        elif stage=='PUBLICATION_JOB_START':
            active=dict(start=e,enqueue=enqueued,cycles=[]);jobs.append(active);cycle=None
        elif stage=='PUBLICATION_CYCLE_START':
            cycle=dict(start=e,events=[])
            if active is not None:active['cycles'].append(cycle)
        if cycle is not None:cycle['events'].append(e)
        if stage=='PUBLICATION_JOB_END':
            if active is not None:active['end']=e
            active=None;cycle=None
    found=[(j,c) for j in jobs for c in j['cycles'] if any(e['seq']==pub['seq'] for e in c['events'])]
    require(len(found)==1,'Published frame not in one fully identified job/cycle')
    job,cycle=found[0];ce=cycle['events']
    def only(stage):
        values=[e for e in ce if e['stage']==stage];require(len(values)==1,'Expected one cycle marker '+stage);return values[0]
    names=('PUBLICATION_CYCLE_START','PUBLICATION_FRAME_WAIT_START','PUBLICATION_FRAME_CALLBACK','PUBLICATION_FRAME_WAIT_END',
        'PUBLICATION_SYNC_CHECK','PUBLICATION_SYNC_DONE','PUBLICATION_RESIZE_CHECK','PUBLICATION_RESIZE_DONE','PUBLICATION_CALL',
        'FRAME_CAPTURE_STARTED','PUBLICATION_MODEL_LOCK_ACQUIRED','FRAME_CAPTURED','ROW_SYNC_STARTED','ROW_SYNC_FINISHED','FRAME_PUBLISHED','PUBLICATION_RETURN')
    ordered=[only(stage) for stage in names]
    require(all(a['seq']<b['seq'] and a['ns']<=b['ns'] for a,b in zip(ordered,ordered[1:])),'Publication cycle ordering invalid')
    require(only('PUBLICATION_RETURN').get('flag') is True,'Publication did not complete')
    enqueue=job['enqueue'];require(enqueue is not None and enqueue['request'] is not None,'Job creator outside preserved context')
    creator=enqueue['request']['event'];first_feed=next(e for e in events if e['stage']=='EMULATOR_FED')
    receive=next(e for e in events if e['stage']=='UI_OUTPUT_RECEIVED' and e['ns']>=first_feed['ns'])
    def delta(a,b):return only(b)['ns']-only(a)['ns']
    result=dict(jobStartSeq=job['start']['seq'],creatorRequestSeq=creator['seq'],cycleStartSeq=cycle['start']['seq'],publishedSeq=pub['seq'],
        creatorBeforeWindow=creator['seq']<ui['seq'],requests=[r for r in requests if r['event']['seq']>=ui['seq']],
        fedToReceiveNs=receive['ns']-first_feed['ns'],receiveToCaptureNs=only('FRAME_CAPTURE_STARTED')['ns']-receive['ns'],
        requestToEnqueueNs=enqueue['event']['ns']-creator['ns'],enqueueToJobNs=job['start']['ns']-enqueue['event']['ns'],
        jobToFrameRegistrationNs=only('PUBLICATION_FRAME_WAIT_START')['ns']-job['start']['ns'],
        registeredFrameWaitNs=delta('PUBLICATION_FRAME_WAIT_START','PUBLICATION_FRAME_CALLBACK'),
        callbackToContinuationNs=delta('PUBLICATION_FRAME_CALLBACK','PUBLICATION_FRAME_WAIT_END'),
        syncGuardNs=delta('PUBLICATION_SYNC_CHECK','PUBLICATION_SYNC_DONE'),resizeGuardNs=delta('PUBLICATION_RESIZE_CHECK','PUBLICATION_RESIZE_DONE'),
        modelLockWaitNs=delta('FRAME_CAPTURE_STARTED','PUBLICATION_MODEL_LOCK_ACQUIRED'),
        frameBuildNs=delta('PUBLICATION_MODEL_LOCK_ACQUIRED','FRAME_CAPTURED'),rowPublishNs=delta('ROW_SYNC_STARTED','ROW_SYNC_FINISHED'),
        syncActiveAtCheck=only('PUBLICATION_SYNC_CHECK').get('flag'),syncCompleted=only('PUBLICATION_SYNC_DONE').get('flag'),
        resizeActiveAtCheck=only('PUBLICATION_RESIZE_CHECK').get('flag'),forcedBlank=only('PUBLICATION_CALL').get('flag'),markers=ordered)
    require(all(v>=0 for k,v in result.items() if k.endswith('Ns')),'Negative probe segment')
    if index is not None:
        result['uiFrameMembership']={name:[dict(token=f['token'],startNs=f['startNs'],endNs=f['endNs']) for f in index.ui_for(only(name)['ns'])]
            for name in ('PUBLICATION_FRAME_WAIT_START','PUBLICATION_FRAME_CALLBACK','PUBLICATION_FRAME_WAIT_END','FRAME_CAPTURE_STARTED','FRAME_PUBLISHED')}
        lo=only('PUBLICATION_FRAME_WAIT_START')['ns'];hi=only('PUBLICATION_FRAME_CALLBACK')['ns']
        result['uiFramesStartedDuringRegisteredWait']=[dict(token=f['token'],startNs=f['startNs'],endNs=f['endNs']) for f in index.ui if lo<f['startNs']<=hi]
    return result

def sources():
    result=old_sources()
    for name in (TEST,'.github/scripts/terminal-publication-wait.py','benchmarks/terminal-publication-wait/protocol.json',
        'benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle'):
        result[name]=ci.digest(ROOT/name)
    return result
def build():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android build is CI-only');guard();ci.sources=sources
    original,modified=overlay();OUT.mkdir(parents=True,exist_ok=False)
    receipt=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,traceOnlyOverlay=True,
        baseTree=protocol()['baseTree'],files={name:dict(base=digest(original[name].encode()),probe=digest(modified[name].encode())) for name in original})
    for name,text in modified.items():
        path=OUT/'probe-source'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
    base_sources=sources()
    try:
        for name,text in modified.items():(ROOT/name).write_text(text)
        subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PterminalViewportTests=true','-PincludeX86_64AbiForTests=true',
            '-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','terminalMechanismDependencies',
            ':app:assembleTerminaltest',':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
        ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes())
        expected=dict(base_sources)
        for name,text in modified.items():expected[name]=digest(text.encode())
        require(manifest['sources']==expected,'Probe target sources differ from reversible overlay')
        manifest['environmentSources']=base_sources;save(ci.OUT/'manifest.json',manifest)
        shutil.copyfile(ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json',OUT/'resolved-dependencies.json')
        save(OUT/'source-receipt.json',receipt)
    finally:
        for name,text in original.items():(ROOT/name).write_text(text)
        guard();require(not git('diff','--name-only','HEAD').strip(),'Probe checkout not restored')

def validate_cases(text):
    p=protocol();old=real.CASES
    try:real.CASES=tuple(p['matrix']['cases']);cases=real.case_records(text)
    finally:real.CASES=old
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP')
    require(len(cleanup)==4 and all(x['closed'] and x['threadStopped'] for x in cleanup),'Cleanup incomplete')
    for c in cases:
        _,size,ime=c['case'].split('-');size=int(size);shown=ime=='shown'
        require(c['passed'] and c['protocolId']==p['id'] and c['clock']=='CLOCK_BOOTTIME' and c['performanceMeasured'] is False,'Wrong publication diagnosis')
        require(c['mode']=='fullVirtual' and c['expectedIme']==shown and c['historySize']==size,'Wrong page/IME')
        require('incrementLaunchCount:' in c['startupReceipt'] and all(x['configured'] for x in c['preparation'][-2:]),'Startup settings fence absent')
        order=[(r,pos,cond) for r in range(4) for pos,cond in enumerate(('refresh','sync') if r%2==0 else ('sync','refresh'))]
        require([(w['index'],w['position'],w['condition']) for w in c['samples']]==order,'Fixed32workload inventory differs')
        require(len(c['gates'])==9 and len({g['rows'] for g in c['gates']})==1 and all(g['columns']==80 and g['virtual'] and g['ime']==shown for g in c['gates']),'Grid/IME changed')
        for w in c['samples']:
            prefix='SYNC' if w['condition']=='sync' else 'ECHO';marker=f'{prefix}:{c["case"]}:{w["index"]:02d}:{w["position"]}'
            output=(('\x1b[?2026h' if prefix=='SYNC' else '')+'\x1b[24;1H\x1b[2K'+marker+'\r'+('\x1b[?2026l' if prefix=='SYNC' else '')).encode()
            require(w['outputBytes']==len(output) and w['outputSha256']==digest(output),'Protocol bytes differ')
            require(w['warmup'] is False and w['clock']=='CLOCK_BOOTTIME' and w['sameInitialHistory'] and w['visibleAfterMeasurement'] and w['virtual'],'Invalid scenario proof')
            require(w['closedBootNs']==w['drawNs']+p['matrix']['naturalObservationTailNs'],'Observation cutoff changed')
            require(w['fedRevision']==w['publishedRevision']==w['drawRevision'],'Final revision mismatched')
            for stage in ('OUTPUT_READ','EMULATOR_FED'):require(sum(e['bytes'] for e in w['events'] if e['stage']==stage)==len(output),'Raw bytes missing')
            require(all(e.get('flag')==e.get('virtual') for e in w['events']),'Diagnostic Boolean slot changed')
    return cases,cleanup

def cpu_intervals(tr,pid):
    out=[]
    for cpu in {r[0] for r in tr['switches']}:
        values=sorted((r for r in tr['switches'] if r[0]==cpu),key=lambda r:r[1])
        for a,b in zip(values,values[1:]):
            if a[3]==pid and b[2]==pid:out.append((a[1],b[1]))
    return out
def report(text,manifest,device,installed):
    p=guard();cases,cleanup=validate_cases(text);save(OUT/'original-cases.json',cases)
    groups=[];ordinary=[];sync=[];active_controls=0
    for method,c in zip(METHODS,cases):
        raw=(ci.OUT/'latency-traces'/(method+'.pftrace')).read_bytes();tr=identity.parse_trace(raw,c['pid']);require(not tr['faults'],'Trace identity/integrity fault')
        index=identity.FrameIndex(tr);sched=real.trace_data(raw,c['pid']);require(not sched['lostFtraceBundles'] and not sched['compactSchedBundles'],'Scheduler incomplete')
        cpu=cpu_intervals(sched,c['pid']);rows=[]
        for sample in c['samples']:
            r=chain(sample,index);r.update(phase=sample['phase'],condition=sample['condition'],index=sample['index'])
            markers={e['stage']:e for e in r['markers']}
            def cost(a,b):
                lo=markers[a]['ns'];hi=markers[b]['ns'];return sum(max(0,min(hi,y)-max(lo,x)) for x,y in cpu)
            r['mainCpuFrameWaitNs']=cost('PUBLICATION_FRAME_WAIT_START','PUBLICATION_FRAME_CALLBACK')
            r['mainCpuAfterCallbackToPublishNs']=cost('PUBLICATION_FRAME_CALLBACK','FRAME_PUBLISHED')
            rows.append(r)
            if sample['condition']=='refresh':ordinary.append(r)
            else:sync.append(r);active_controls+=r['syncActiveAtCheck'] is True
        groups.append(dict(case=c['case'],rows=rows,traceSha256=tr['traceSha256'],traceBytes=len(raw),sequenceInitialMarkers=tr['sequenceInitialMarkers']))
    import statistics,collections
    summary=[]
    for group in groups:
        for cond in ('refresh','sync'):
            selected=[r for r in group['rows'] if r['condition']==cond]
            summary.append(dict(case=group['case'],condition=cond,n=len(selected),
                mediansNs={k:statistics.median(r[k] for r in selected) for k in ('receiveToCaptureNs','requestToEnqueueNs','enqueueToJobNs','jobToFrameRegistrationNs',
                    'registeredFrameWaitNs','callbackToContinuationNs','syncGuardNs','resizeGuardNs','modelLockWaitNs','frameBuildNs','rowPublishNs','mainCpuFrameWaitNs','mainCpuAfterCallbackToPublishNs')},
                syncActive=sum(r['syncActiveAtCheck'] is True for r in selected),resizeActive=sum(r['resizeActiveAtCheck'] is True for r in selected),
                coalescedRequests=sum(x['coalesced'] for r in selected for x in r['requests']),
                callbackInsideUiFrame=sum(len(r['uiFrameMembership']['PUBLICATION_FRAME_CALLBACK'])==1 for r in selected),
                continuationInsideUiFrame=sum(len(r['uiFrameMembership']['PUBLICATION_FRAME_WAIT_END'])==1 for r in selected),
                frameStartsDuringWait=dict(collections.Counter(len(r['uiFramesStartedDuringRegisteredWait']) for r in selected))))
    receipt=json.loads((OUT/'source-receipt.json').read_bytes())
    value=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,traceOnlyOverlay=True,optimizationMeasured=False,
        protocol=p,protocolSha256=PROTOCOL_SHA,sourceReceipt=receipt,manifest=manifest,device=device,installedApkHashes=installed,cases=cases,cleanup=cleanup,
        groups=groups,summary=summary,ordinaryWindows=len(ordinary),syncWindows=len(sync),activeSyncControls=active_controls,
        note='Instrumented55 scheduler diagnosis,not optimization performance; fixed probe overhead, no deletion of waits or rejected candidate reuse.')
    save(OUT/'report.json',value);compact={k:v for k,v in value.items() if k not in ('sourceReceipt','manifest','cases','groups','protocol')}
    compact['reportSha256']=ci.digest(OUT/'report.json');save(OUT/'summary.json',compact)
    real.wire.legacy.compact('Terminal publication wait diagnosis',json.dumps(compact,separators=(',',':')).encode())
    require(len(ordinary)==16 and len(sync)==16 and active_controls>0,'Fixed inventory or active sync control coverage missing')
    return value
def run():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android diagnosis is CI-only');guard();ci.sources=sources
    manifest=json.loads((ci.OUT/'manifest.json').read_bytes());require(manifest.get('environmentSources')==sources(),'Restored environment differs')
    original,modified=overlay();expected=dict(sources())
    for name,text in modified.items():expected[name]=digest(text.encode())
    require(manifest['sources']==expected,'Probe APK source inventory changed')
    old=ci.real_clock
    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS='me.rerere.rikkahub.pipeline.TerminalPublicationWaitTest',METHODS=METHODS,report=report)
    try:ci.emulator(operation=lambda:ci.run_verified_bundle(manifest,'realClock',emit_evidence=False))
    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
        for name in ('instrumentation.log','pipeline-logcat.txt'):
            f=ci.OUT/name
            if f.exists():ci.notice('error','Publication diagnosis '+name,ci.failure_excerpt(f))
        raise
    finally:ci.real_clock=old
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','build','run'));a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        elif a.action=='build':build()
        else:run()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','Publication wait diagnosis stopped',type(e).__name__+': '+str(e)[:1900]);raise SystemExit(1)
