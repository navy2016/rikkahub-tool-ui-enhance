"""Fixed no-APK Android34 FrameTimeline tail diagnosis; never qualifies old terminal windows."""
import argparse,base64,collections,decimal,hashlib,importlib.util,json,os,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'benchmarks/terminal-trace-tail-diagnostic'
OUT=ROOT/'artifacts/terminal-trace-tail-diagnostic'
PROTOCOL_SHA='e2a08b5f2ba50403f0865d21684afeebd9e1d463ab0fafcdace9daef70701e21'
def load(name,file):
    s=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file));m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
ci=load('tail_diagnostic_ci','run-terminal-pipeline.py');identity=load('tail_diagnostic_identity','terminal-frame-identity.py');old_sources=ci.sources
require=identity.require

def sha(raw):return hashlib.sha256(raw).hexdigest()
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def protocol():
    raw=(BASE/'protocol.json').read_bytes();require(sha(raw)==PROTOCOL_SHA,'Tail diagnosis protocol changed');return json.loads(raw)
def guard():
    p=protocol();require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['baseTree'],'Not unchanged306')
    require(not git('diff','--name-only',p['baseProductionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml').strip(),'Production/dependency changed')
    require(not git('diff','--name-only',p['parentSourceSha'],'--','benchmarks/terminal-trace-lifecycle-v3',
        '.github/scripts/terminal-trace-lifecycle-v3.py','.github/scripts/terminal-frame-identity.py','benchmarks/terminal-frame-identity',
        'benchmarks/terminal-geometry-c1').strip(),'Tail diagnosis changed frozen candidate/collector/parser')
    original=(ROOT/'benchmarks/terminal-trace-lifecycle-v3/trace.pbtxt').read_bytes();require(sha(original)==p['originalConfigSha256'],'V3recipe changed')
    baseline=(BASE/'baseline.pbtxt').read_bytes();periodic=(BASE/'periodic.pbtxt').read_bytes()
    require(sha(baseline)==p['baselineConfigSha256'] and sha(periodic)==p['periodicConfigSha256'],'Diagnostic recipe changed')
    needle=b'atrace_apps: "me.rerere.rikkahub.dev.next.terminaltest"'
    require(baseline==original.replace(needle,needle+b'\n      atrace_apps: "com.android.settings"') and periodic==baseline+b'flush_period_ms: 250\n','Unexpected diagnostic recipe edits')
    require(p['order']==['baseline','wait','periodic','composite','composite','periodic','wait','baseline'],'Fixed8case inventory changed')
    require(all(p['invariants'].values()),'Diagnostic safety scope changed');return p

def sources():
    d=old_sources()
    for f in (Path(__file__),BASE/'protocol.json',BASE/'baseline.pbtxt',BASE/'periodic.pbtxt',Path(__file__).with_name('terminal-frame-identity.py')):
        d[str(f.relative_to(ROOT))]=sha(f.read_bytes())
    return d

def call(args,input=b'',timeout=40):
    r=subprocess.run(['adb','shell',*args],input=input,capture_output=True,timeout=timeout)
    return dict(exit=r.returncode,stdout=r.stdout.decode(errors='replace'),stderr=r.stderr.decode(errors='replace'))
def shell(text):return call(['sh'],(text+'\n').encode())
def checked(result,label):
    require(result['exit']==0,label+': '+result['stderr'][:800]+' '+result['stdout'][:800]);return result['stdout'].strip()
def pause(ms):checked(call(['sleep',str(decimal.Decimal(ms)/1000)]),'Native diagnostic wait')
def boot_cut():
    r=call(['cat','/proc/uptime']);parts=checked(r,'BOOTTIME read').split();require(parts and re.fullmatch(r'\d+\.\d{2}',parts[0]),'Unexpected proc uptime precision')
    lower=int(decimal.Decimal(parts[0])*1_000_000_000)
    return dict(raw=r,lowerBoundNs=lower,upperBoundNs=lower+10_000_000,clock='CLOCK_BOOTTIME',resolutionNs=10_000_000)
def proc_start(stat,pid):
    require(stat.startswith(str(pid)+' (perfetto) '),'Unexpected trace process')
    parts=stat.rsplit(') ',1)[-1].split();require(len(parts)>=20 and int(parts[19])>0,'Invalid process start ticks');return int(parts[19])
def start_trace(remote,config):
    checked(call(['rm','-f',remote]),'Remove diagnostic output')
    program="printf %s '"+base64.b64encode(config).decode()+"' | base64 -d | perfetto --txt --background-wait -c - -o "+remote
    result=shell(program);value=checked(result,'Perfetto startup');pids=[x for x in value.splitlines() if x.isdigit()]
    require(len(pids)==1,'Ambiguous tracing PID');pid=int(pids[0]);stat=checked(call(['cat',f'/proc/{pid}/stat']),'Readable trace proc')
    return dict(pid=pid,startTicks=proc_start(stat,pid),procStat=stat,start=result,remote=remote,configSha256=sha(config))
def stop_trace(start):
    pid=start['pid'];pre=checked(call(['cat',f'/proc/{pid}/stat']),'Pre-stop trace proc');require(proc_start(pre,pid)==start['startTicks'],'Trace PID reused')
    result=shell(f'''kill -INT {pid} || exit 71
n=0
while [ -d /proc/{pid} ]; do
 if data=$(cat /proc/{pid}/stat 2>/dev/null); then printf '%s\\n' "$data";
 else [ ! -d /proc/{pid} ] && break; exit 73; fi
 n=$((n+1)); [ "$n" -lt 100 ] || exit 72; sleep 0.1
done
[ ! -d /proc/{pid} ] || exit 74
printf 'PROC_ABSENT\\n' ''')
    output=checked(result,'SIGINT and race-safe proc absence');lines=output.splitlines();require(lines and lines[-1]=='PROC_ABSENT','No actual proc disappearance')
    require(all(proc_start(x,pid)==start['startTicks'] for x in lines[:-1]),'Trace PID reused during stop')
    remote=start['remote'];file=checked(shell('sha256sum '+remote+' && stat -c %s '+remote),'Stopped raw file receipt').splitlines()
    require(len(file)==2 and re.fullmatch('[a-f0-9]{64}',file[0].split()[0]),'Bad stopped file hash')
    size=int(file[1]);require(0<size<100663296,'Invalid raw file bound')
    return dict(preStopStat=pre,result=result,procAbsent=True,bytes=size,sha256=file[0].split()[0])

def trace_wire(raw):
    service=[];stats=[];kinds=collections.Counter();payloads=collections.Counter()
    for f,z,packet in identity.wire.pb(raw):
        if f!=1 or z!=2:continue
        fields=list(identity.wire.pb(packet));meta={f:v for f,z,v in fields if z==0}
        for f,z,value in fields:
            if z!=2:continue
            payloads[f]+=1
            if f==69:service.append(dict(timestamp=meta.get(8),fields={str(f):v for f,z,v in identity.wire.pb(value) if z==0}))
            if f==35:stats.append(dict(timestamp=meta.get(8),fields={str(f):v for f,z,v in identity.wire.pb(value) if z==0},
                buffers=[{str(a):b for a,t,b in identity.wire.pb(v) if t==0} for k,t,v in identity.wire.pb(value) if k==1 and t==2]))
            if f==76:kinds.update(k for k,t,v in identity.wire.pb(value) if t==2)
    return dict(serviceEvents=service,stats=stats,timelineKindCounts=dict(kinds),payloadCounts=dict(payloads),
        flushedEventIsNotSuccessProof=True)

def analyze(raw,pid,cut):
    trace=identity.parse_trace(raw,pid);index=identity.FrameIndex(trace);lo=cut['lowerBoundNs'];hi=cut['upperBoundNs']
    ui=[x for x in index.ui if x['endNs']<lo];tail=max(ui,key=lambda x:x['endNs']) if ui else None
    before=[x for x in trace['surfaces'] if type(x['startNs']) is int and x['startNs']<lo]
    def chain(surface):
        displays=index.displays[surface['displayToken']]
        valid=surface['complete'] and surface['present'] in (1,2,3) and surface['isBuffer']==1 and surface['endNs']<lo and \
            len(displays)==1 and displays[0]['complete'] and displays[0]['present'] in (1,2,3) and \
            surface['endNs']<=displays[0]['endNs']<lo
        return dict(surface=surface,displays=displays,completeBeforeCut=bool(valid))
    tail_surfaces=[chain(x) for x in index.surfaces[tail['token']] if x['pid']==pid] if tail else []
    return dict(traceSha256=sha(raw),traceBytes=len(raw),pid=pid,cut=cut,traceFaults=trace['faults'],openSpans=trace['openSpans'],
        preCutUIFrames=len(ui),tailUI=tail,tailUISurfaces=tail_surfaces,
        tailUIEvidenceBeforeCut=not trace['faults'] and len(tail_surfaces)==1 and tail_surfaces[0]['completeBeforeCut'],
        preCutSurfaces=len(before),preCutMissingEnds=sum(not x['complete'] for x in before),
        preCutCompleteEnds=sum(x['complete'] and x['endNs']<lo for x in before),
        surfaceStartsInCutUncertainty=sum(type(x['startNs']) is int and lo<=x['startNs']<hi for x in trace['surfaces']),
        postCutSurfaces=sum(type(x['startNs']) is int and x['startNs']>=hi for x in trace['surfaces']),
        **trace_wire(raw),diagnosticOnly=True,qualificationPassed=False,performanceMeasured=False)

def run():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android tail diagnosis is CI-only');p=guard();OUT.mkdir(parents=True,exist_ok=False)
    ci.OUT=OUT;ci.sources=sources
    manifest=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],sources=sources(),noApkBuilt=True,apks={})
    save(OUT/'manifest.json',manifest);save(OUT/'protocol.json',p)
    def operation():
        fingerprint=checked(call(['getprop','ro.build.fingerprint']),'Fingerprint');version=checked(call(['perfetto','--version']),'Perfetto version')
        require(fingerprint==p['expectedFingerprint'] and version==p['expectedPerfetto'],'Diagnostic guest differs from V3')
        device=dict(fingerprint=fingerprint,perfetto=version,identity=call(['id']),boot=checked(call(['cat','/proc/sys/kernel/random/boot_id']),'Guest boot'))
        save(OUT/'device.json',device);cases=[];failed=[]
        for n,variant in enumerate(p['order']):
            folder=OUT/f'case-{n}-{variant}';folder.mkdir();case=dict(index=n,variant=variant,passed=False);start=None;stopped=None
            try:
                package=p['targetPackage'];checked(call(['am','force-stop',package]),'Reset built-in Settings')
                launched=call(['am','start','-W','-a',p['systemAction']]);text=checked(launched,'Launch built-in Settings');require('Status: ok' in text,'Settings did not launch')
                pause(p['setup']['initialSettleMs']);raw_pid=checked(call(['pidof',package]),'Settings PID');require(re.fullmatch('[0-9]+',raw_pid),'Ambiguous Settings PID');pid=int(raw_pid)
                case.update(targetPid=pid,launch=launched);config=(BASE/('periodic.pbtxt' if variant=='periodic' else 'baseline.pbtxt')).read_bytes()
                start=start_trace(f'/data/misc/perfetto-traces/tail-diagnostic-{n}.pftrace',config);case['start']=start
                checked(call(['input','swipe',*map(str,p['setup']['swipe'])]),'Fixed built-in UI swipe')
                pause(p['setup']['afterSwipeQuietMs']);cut=boot_cut();case['cut']=cut
                v=p['variants'][variant];pulses=[]
                case['pulses']=pulses
                if variant=='composite':
                    pause(v['prePulseQuietMs'])
                    for pulse in range(v['pulses']):
                        before=boot_cut();reply=call(v['command']);pulses.append(dict(index=pulse,before=before,result=reply))
                        require(checked(reply,'SurfaceFlinger diagnostic composite')==v['requiredReply'],'Composite was denied or not acknowledged; never elevate permissions')
                        pause(v['pulseIntervalMs'])
                    pause(v['postPulseQuietMs'])
                else:pause(v['preStopQuietMs'])
                case['stopRequestedAt']=boot_cut();stopped=stop_trace(start);case['stop']=stopped
                output=folder/'trace.pftrace';pull=subprocess.run(['adb','pull',start['remote'],str(output)],capture_output=True,text=True,timeout=90)
                case['pull']=dict(exit=pull.returncode,stdout=pull.stdout,stderr=pull.stderr);require(pull.returncode==0,'Stopped trace pull failed')
                raw=output.read_bytes();require(sha(raw)==stopped['sha256'] and len(raw)==stopped['bytes'],'Pulled trace differs')
                analysis=analyze(raw,pid,cut);save(folder/'analysis.json',analysis);case['analysis']=analysis
                require(analysis['preCutUIFrames']>0,'No settings pre-cut UI anchor; diagnostic inconclusive')
                case['passed']=True
            except (ValueError,OSError,subprocess.SubprocessError) as error:
                case['error']=str(error)[:1800];failed.append(n)
            finally:
                if start is not None and stopped is None:
                    try:case['cleanupStop']=stop_trace(start)
                    except (ValueError,OSError,subprocess.SubprocessError) as error:case['cleanupError']=str(error)[:500]
                # Preserve even an execution-failed diagnostic's existing trace. No second capture.
                if start is not None and not (folder/'trace.pftrace').exists():
                    try:
                        pulled=subprocess.run(['adb','pull',start['remote'],str(folder/'trace.pftrace')],capture_output=True,text=True,timeout=90)
                        case['cleanupPull']=dict(exit=pulled.returncode,stdout=pulled.stdout,stderr=pulled.stderr)
                    except (OSError,subprocess.SubprocessError) as error:case['cleanupPullError']=str(error)[:500]
                save(folder/'receipt.json',case);cases.append(case);save(OUT/'progress.json',cases)
        logs=call(['logcat','-d','-b','all','-v','threadtime']);lines=[x for x in (logs['stdout']+'\n'+logs['stderr']).splitlines() if 'avc:' in x and any(s in x.lower() for s in ('perfetto','surfaceflinger','shell'))]
        (OUT/'selinux-relevant.txt').write_text('\n'.join(lines[-150:])+'\n')
        report=dict(sourceSha=manifest['sourceSha'],runId=manifest['runId'],protocolSha256=PROTOCOL_SHA,device=device,cases=cases,
            diagnosticExecutionComplete=not failed,failedCases=failed,noApkBuilt=True,nativeTerminalWindows=0,
            qualificationPassed=False,performanceMeasured=False,productionChanged=False,oldV3Unchanged=True)
        save(OUT/'report.json',report)
        compact=dict(sourceSha=manifest['sourceSha'],runId=manifest['runId'],reportSha256=sha((OUT/'report.json').read_bytes()),
            diagnosticExecutionComplete=not failed,rows=[dict(index=c['index'],variant=c['variant'],passed=c['passed'],error=c.get('error'),
                tailUIEvidenceBeforeCut=c.get('analysis',{}).get('tailUIEvidenceBeforeCut'),preCutMissingEnds=c.get('analysis',{}).get('preCutMissingEnds'),
                traceFaults=c.get('analysis',{}).get('traceFaults')) for c in cases],noApkBuilt=True,qualificationPassed=False,performanceMeasured=False)
        save(OUT/'summary.json',compact);ci.notice('notice','FrameTimeline tail diagnostic',json.dumps(compact,separators=(',',':')))
        require(not failed,'Tail diagnostic has execution gaps; originals retained,no replacement cases')
    ci.emulator(operation=operation)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=('guard','run'));a=parser.parse_args()
    if a.action=='guard':print(json.dumps(guard(),indent=2))
    else:run()
if __name__=='__main__':
    try:main()
    except (ValueError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','Tail diagnostic stopped',str(e)[:2000]);raise SystemExit(1)
