"""Independent finite launch/selection/observation tail diagnosis. No terminal performance claims."""
import argparse,base64,collections,hashlib,importlib.util,json,os,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];BASE=ROOT/'benchmarks/terminal-trace-finite-tail';OUT=ROOT/'artifacts/terminal-trace-finite-tail'
PROTOCOL_SHA='6cc80118213857a51e3d020591b2b0b33438b9b739d4d5af516bf6cc92e4b105'
s=importlib.util.spec_from_file_location('frozen_tail_transport',Path(__file__).with_name('terminal-trace-tail-diagnostic.py'));transport=importlib.util.module_from_spec(s);s.loader.exec_module(transport)
ci=transport.ci;identity=transport.identity;require=identity.require
call=transport.call;checked=transport.checked;pause=transport.pause;boot_cut=transport.boot_cut
start_trace=transport.start_trace;stop_trace=transport.stop_trace

def sha(raw):return hashlib.sha256(raw).hexdigest()
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def protocol():
    raw=(BASE/'protocol.json').read_bytes();require(sha(raw)==PROTOCOL_SHA,'Finite-tail protocol changed');return json.loads(raw)
def guard():
    p=protocol();require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['baseTree'],'Finite diagnosis changed306')
    require(not git('diff','--name-only',p['baseProductionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml').strip(),'Production or dependency changed')
    require(sha(Path(transport.__file__).read_bytes())==p['transportSourceSha256'] and sha(Path(identity.__file__).read_bytes())==p['identitySourceSha256'],'Frozen helper/parser changed')
    require(not git('diff','--name-only',p['parentSourceSha'],'--','benchmarks/terminal-trace-lifecycle-v3','benchmarks/terminal-trace-tail-diagnostic',
        'benchmarks/terminal-geometry-c1','.github/scripts/terminal-trace-lifecycle-v3.py','.github/scripts/terminal-trace-tail-diagnostic.py',
        '.github/scripts/test_terminal_trace_tail_diagnostic.py','.github/workflows/terminal-trace-tail-diagnostic.yml').strip(),'Historical experiment changed')
    for name,key in (('baseline.pbtxt','baselineConfigSha256'),('periodic.pbtxt','periodicConfigSha256')):
        raw=(BASE/name).read_bytes();require(sha(raw)==p[key] and raw==(ROOT/'benchmarks/terminal-trace-tail-diagnostic'/name).read_bytes(),'Collection config changed')
    require(p['order']==['baseline','wait','periodic','volume','volume','periodic','wait','baseline'],'Fixed finite case order changed')
    require(all(p['invariants'].values()),'Diagnostic boundary differs');return p

def sources():
    values=transport.sources()
    for file in (Path(__file__),BASE/'protocol.json',BASE/'baseline.pbtxt',BASE/'periodic.pbtxt'):values[str(file.relative_to(ROOT))]=sha(file.read_bytes())
    return values

def buffer_structure(index,ui):
    """Select using UI/RT/buffer structural identity only, never consult FrameTimeline or its ends."""
    tasks=index.tasks[ui['token']];record=dict(ui=ui,eligible=False,reason=None,tasks=tasks)
    if len(tasks)!=1:record['reason']='RT_TASK_NOT_UNIQUE';return record
    task=tasks[0];children=index.children(task);maps=[];acquires=[];draws=[];swaps=[]
    for span in children:
        match=identity.MAP_RE.fullmatch(span['name'])
        if match:maps.append(dict(span,queue=match[1],frameNumber=int(match[2]),token=int(match[3])))
        match=identity.ACQUIRE_RE.fullmatch(span['name'])
        if match:acquires.append(dict(span,queue=match[1],frameNumber=int(match[2])))
        if span['name'].startswith('Drawing '):draws.append(span)
        if span['name'].startswith('eglSwapBuffers'):swaps.append(span)
    record.update(mappings=maps,acquires=acquires,drawingSpans=draws,swapSpans=swaps)
    if len(maps)!=1 or len(acquires)!=1 or not draws or not swaps:
        record['reason']='NO_UNIQUE_STRUCTURAL_BUFFER_CHAIN';return record
    mapping=maps[0];acquire=acquires[0]
    if mapping['token']!=ui['token'] or mapping['queue']!=acquire['queue'] or mapping['frameNumber']!=acquire['frameNumber'] or mapping['startNs']>acquire['startNs'] or \
        not any(identity.inside(acquire,span) for span in swaps):
        record['reason']='STRUCTURAL_BUFFER_IDENTITY_CONFLICT';return record
    record.update(eligible=True,reason=None);return record

def analyze(raw,pid,stimulus_begin,selection_end,observation_cut):
    begin=stimulus_begin['upperBoundNs'];selected_before=selection_end['lowerBoundNs'];cut=observation_cut['lowerBoundNs']
    require(0<begin<selected_before<cut and selection_end['upperBoundNs']<cut,'Stimulus/selection/observation bounds overlap')
    trace=identity.parse_trace(raw,pid);index=identity.FrameIndex(trace)
    candidates=[buffer_structure(index,ui) for ui in index.ui if ui['startNs']>=begin and ui['endNs']<selected_before]
    eligible=[x for x in candidates if x['eligible']];eligible.sort(key=lambda x:(x['ui']['endNs'],x['ui']['startNs'],x['ui']['token']))
    target=eligible[-1] if eligible else None;rows=[]
    if target:
        for sf in index.surfaces[target['ui']['token']]:
            if sf['pid']!=pid:continue
            displays=index.displays[sf['displayToken']]
            complete=sf['complete'] and sf['isBuffer']==1 and sf['present'] in (1,2,3) and len(displays)==1 and displays[0]['complete'] and displays[0]['present'] in (1,2,3)
            ordered=bool(complete and target['acquires'][0]['startNs']<=sf['endNs']<=displays[0]['endNs'])
            rows.append(dict(surface=sf,displays=displays,complete=bool(complete),ordered=ordered,
                endpointsBeforeCut=bool(ordered and sf['endNs']<cut and displays[0]['endNs']<cut)))
    before=[s for s in trace['surfaces'] if type(s['startNs']) is int and begin<=s['startNs']<selected_before]
    raw_wire=transport.trace_wire(raw)
    # JSON has string keys; normalize these known protobuf histograms at the producer boundary.
    for key in ('timelineKindCounts','payloadCounts'):raw_wire[key]={str(k):v for k,v in raw_wire[key].items()}
    return dict(traceSha256=sha(raw),traceBytes=len(raw),pid=pid,stimulusBegin=stimulus_begin,selectionEnd=selection_end,observationCut=observation_cut,
        candidates=candidates,target=target,targetSurfaces=rows,selectionIndependentOfTimeline=True,
        targetEvidenceBeforeCut=bool(not trace['faults'] and len(rows)==1 and rows[0]['endpointsBeforeCut']),
        selectedIntervalSurfaces=len(before),selectedIntervalMissingEnds=sum(not s['complete'] for s in before),
        laterUIFrames=sum(ui['startNs']>=selected_before for ui in index.ui),
        traceFaults=trace['faults'],openSpans=trace['openSpans'],**raw_wire,diagnosticOnly=True,qualificationPassed=False,performanceMeasured=False)

def validate_bounds(case):
    p=protocol();begin=case['stimulusBegin'];returned=case['stimulusReturned'];selected=case['selectionEnd'];cut=case['observationCut']
    for r in (begin,returned,selected,cut,case['stopRequestedAt']):
        require(r['clock']=='CLOCK_BOOTTIME' and r['resolutionNs']==10_000_000 and r['upperBoundNs']-r['lowerBoundNs']==10_000_000,'Bad clock interval')
    require(begin['upperBoundNs']<=returned['lowerBoundNs'] and returned['upperBoundNs']<selected['lowerBoundNs']<selected['upperBoundNs']<cut['lowerBoundNs']<cut['upperBoundNs']<=case['stopRequestedAt']['lowerBoundNs'],'Fixed intervals overlap')
    require(selected['lowerBoundNs']-returned['upperBoundNs']>=p['selection']['afterLaunchReturnMs']*1_000_000-20_000_000,'Selection delay changed')
    require(cut['lowerBoundNs']-selected['upperBoundNs']>=p['observation']['afterSelectionEndMs']*1_000_000-20_000_000,'Observation gap changed')
    if case['variant']=='volume':
        require(case['pulse']['before']['lowerBoundNs']>=cut['upperBoundNs'],'Pulse occurred inside observed interval')
        require(case['pulse']['result']['exit']==0,'Ordinary key injection failed')
    else:require(case.get('pulse') is None,'Unexpected post-cut input')

def run():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android finite diagnosis is CI-only');p=guard();OUT.mkdir(parents=True,exist_ok=False)
    ci.OUT=OUT;ci.sources=sources;manifest=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],sources=sources(),noApkBuilt=True,apks={})
    save(OUT/'manifest.json',manifest);save(OUT/'protocol.json',p)
    def operation():
        fingerprint=checked(call(['getprop','ro.build.fingerprint']),'Fingerprint');version=checked(call(['perfetto','--version']),'Perfetto')
        require(fingerprint==p['expectedFingerprint'] and version==p['expectedPerfetto'],'Finite diagnostic guest differs')
        device=dict(fingerprint=fingerprint,perfetto=version,identity=call(['id']),boot=checked(call(['cat','/proc/sys/kernel/random/boot_id']),'Boot identity'))
        save(OUT/'device.json',device);cases=[];failed=[]
        for n,variant in enumerate(p['order']):
            folder=OUT/f'case-{n}-{variant}';folder.mkdir();case=dict(index=n,variant=variant,executionPassed=False);start=None;stopped=None
            try:
                package=p['targetPackage'];case['reset']=call(['am','force-stop',package]);checked(case['reset'],'Reset Settings')
                case['home']=call(['input','keyevent','KEYCODE_HOME']);checked(case['home'],'Finite HOME setup');pause(p['setup']['settleBeforeTraceMs'])
                absent=call(['pidof',package]);case['preLaunchPidQuery']=absent
                require(absent['exit']==1 and not absent['stdout'].strip() and not absent['stderr'].strip(),'Settings absence not proven before finite launch')
                config=(BASE/('periodic.pbtxt' if variant=='periodic' else 'baseline.pbtxt')).read_bytes()
                start=start_trace(f'/data/misc/perfetto-traces/finite-tail-{n}.pftrace',config);case['start']=start
                case['stimulusBegin']=boot_cut();launched=call(p['stimulus']['command']);case['stimulusReceipt']=launched
                text=checked(launched,'Single finite activity launch');require('Status: ok' in text,'Finite Settings launch failed')
                case['stimulusReturned']=boot_cut();pause(p['selection']['afterLaunchReturnMs'])
                case['selectionEnd']=boot_cut();pause(p['observation']['afterSelectionEndMs']);case['observationCut']=boot_cut()
                # Read PID only after the immutable selection/observation bounds. No timeline polling.
                raw_pid=checked(call(['pidof',package]),'Settings PID');require(re.fullmatch('[0-9]+',raw_pid),'Ambiguous Settings process');pid=int(raw_pid);case['targetPid']=pid
                v=p['variants'][variant]
                if variant=='volume':
                    pause(v['beforePulseQuietMs']);case['pulse']=dict(before=boot_cut(),result=call(v['command']))
                    checked(case['pulse']['result'],'Post-cut ordinary volume key');pause(v['afterPulseQuietMs'])
                else:pause(v['afterCutQuietMs'])
                case['stopRequestedAt']=boot_cut();validate_bounds(case);stopped=stop_trace(start);case['stop']=stopped
                file=folder/'trace.pftrace';pulled=subprocess.run(['adb','pull',start['remote'],str(file)],capture_output=True,text=True,timeout=90)
                case['pull']=dict(exit=pulled.returncode,stdout=pulled.stdout,stderr=pulled.stderr);require(pulled.returncode==0,'Trace pull failed')
                raw=file.read_bytes();require(sha(raw)==stopped['sha256'] and len(raw)==stopped['bytes'],'Stopped file hash differs')
                analysis=analyze(raw,pid,case['stimulusBegin'],case['selectionEnd'],case['observationCut'])
                require(len(json.dumps(analysis).encode())<=p['limits']['bodyResultPerCaseMaxBytes'],'Finite analysis exceeded bound')
                save(folder/'analysis.json',analysis);case['analysis']=analysis
                require(analysis['target'] is not None,'No structural buffer target in fixed selection interval; no retrospective target change')
                case['executionPassed']=True
            except (ValueError,OSError,subprocess.SubprocessError) as error:
                case['error']=str(error)[:1600];failed.append(n)
            finally:
                if start is not None and stopped is None:
                    try:case['cleanupStop']=stop_trace(start)
                    except (ValueError,OSError,subprocess.SubprocessError) as error:case['cleanupError']=str(error)[:500]
                if start is not None and not (folder/'trace.pftrace').exists():
                    try:
                        pull=subprocess.run(['adb','pull',start['remote'],str(folder/'trace.pftrace')],capture_output=True,text=True,timeout=90)
                        case['cleanupPull']=dict(exit=pull.returncode,stdout=pull.stdout,stderr=pull.stderr)
                    except (OSError,subprocess.SubprocessError) as error:case['cleanupPullError']=str(error)[:500]
                save(folder/'receipt.json',case);cases.append(case);save(OUT/'progress.json',cases)
        report=dict(sourceSha=manifest['sourceSha'],runId=manifest['runId'],protocolSha256=PROTOCOL_SHA,device=device,cases=cases,
            diagnosticExecutionComplete=not failed,failedCases=failed,noApkBuilt=True,nativeTerminalWindows=0,qualificationPassed=False,performanceMeasured=False,productionChanged=False)
        save(OUT/'report.json',report)
        rows=[]
        for case in cases:
            a=case.get('analysis',{});target=a.get('target')
            rows.append(dict(index=case['index'],variant=case['variant'],executionPassed=case['executionPassed'],error=case.get('error'),
                targetToken=target['ui']['token'] if target else None,targetEvidenceBeforeCut=a.get('targetEvidenceBeforeCut'),
                selectedIntervalMissingEnds=a.get('selectedIntervalMissingEnds'),traceFaults=a.get('traceFaults')))
        summary=dict(sourceSha=manifest['sourceSha'],runId=manifest['runId'],reportSha256=sha((OUT/'report.json').read_bytes()),rows=rows,
            diagnosticExecutionComplete=not failed,noApkBuilt=True,qualificationPassed=False,performanceMeasured=False)
        save(OUT/'summary.json',summary);ci.notice('notice','Finite FrameTimeline tail diagnostic',json.dumps(summary,separators=(',',':')))
        require(not failed,'Finite diagnosis has execution gaps; originals kept,no replacement cases')
    ci.emulator(operation=operation)

def main():
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','run'));a=p.parse_args()
    if a.action=='guard':print(json.dumps(guard(),indent=2))
    else:run()
if __name__=='__main__':
    try:main()
    except (ValueError,OSError,subprocess.SubprocessError) as error:
        ci.notice('error','Finite tail diagnostic stopped',str(error)[:2000]);raise SystemExit(1)
