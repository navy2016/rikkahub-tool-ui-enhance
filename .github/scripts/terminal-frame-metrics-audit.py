"""Collector diagnosis only: raw FrameMetrics tokens, gfxinfo intervals, commits, system frames."""
import argparse,base64,hashlib,importlib.util,json,os,subprocess,types,zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'artifacts/terminal-frame-metrics-audit'
s=importlib.util.spec_from_file_location('metrics_audit_wire',Path(__file__).with_name('run-terminal-pipeline.py'))
ci=importlib.util.module_from_spec(s);s.loader.exec_module(ci)
real=ci.real_clock;require=real.require;old_sources=ci.sources
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalFrameMetricsAuditTest.kt'
METHOD='auditWindowRawMetricsAndCommitCallbacks'
PHASES=('paced','vsync')
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def guard():
    require(subprocess.check_output(['git','rev-parse','HEAD:app/src/main'],cwd=ROOT,text=True).strip()=='8caf50d5c40e228f1d5bcc09d3db505757568b68','Production tree changed')
    require(not subprocess.check_output(['git','-c','core.fileMode=false','diff','--name-only','55db111129c662067345aa99540b506de305da37',
        '--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml'],cwd=ROOT).strip(),'Frame audit cannot change app/dependencies')
def sources():
    result=old_sources()
    for p in (TEST,'.github/scripts/terminal-frame-metrics-audit.py','benchmarks/TERMINAL_FRAME_METRICS_AUDIT.md'):
        result[p]=ci.digest(ROOT/p)
    return result
def build():
    require(os.environ.get('GITHUB_ACTIONS')=='true','CI-only builds');guard();ci.sources=sources
    subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PincludeX86_64AbiForTests=true',
        ':app:assembleTerminaltest',':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
    ci.prepare()
def decode(text):
    rows=real.records(text,'FRAME_AUDIT_PART');parts={};identity=None
    for r in rows:
        require(type(r['part']) is int and 1<=r['part']<=r['parts']<=128,'Invalid audit fragment')
        proof=(r['parts'],r['sha256']);require(identity is None or identity==proof,'Mixed audit fragments');identity=proof
        require(r['part'] not in parts,'Repeated audit fragment');parts[r['part']]=r['data']
    require(identity is not None and set(parts)==set(range(1,identity[0]+1)),'Missing audit fragments')
    raw=zlib.decompress(base64.b64decode(''.join(parts[i] for i in sorted(parts)),validate=True))
    require(len(raw)<8*1024*1024 and hashlib.sha256(raw).hexdigest()==identity[1],'Audit raw digest/size differs')
    return json.loads(raw)
def validate(d):
    require(d['passed'] is True and d['performanceMeasured'] is False and d['noTerminalCandidate'] is True,'Not collector-only evidence')
    expected=[(p,i) for p in PHASES for i in range(96)]
    for key in ('requests','draws'):
        require([(r['phase'],r['index']) for r in d[key]]==expected,'192 fixed '+key+' required')
    require(all(q['ns']<=r['ns'] for q,r in zip(d['requests'],d['draws'])),'Draw preceded request')
    require([p['phase'] for p in d['phases']]==list(PHASES),'Missing per-phase frame ring')
    require(len(d['commits'])<=192 and len({(r['phase'],r['index']) for r in d['commits']})==len(d['commits']) and
        all((r['phase'],r['index']) in expected for r in d['commits']),'Commit IDs duplicated or unrequested')
    require(0<len(d['metrics'])<4096 and all(len(r['raw'])==23 and r['dropped']>=0 for r in d['metrics']),'Unexpected API34 FrameInfo shape')
    return d
def join(d,trace):
    # These are diagnostic categories, NOT replacement rules for old terminal performance gates.
    rows=[]
    for draw in d['draws']:
        phase=next(p for p in d['phases'] if p['phase']==draw['phase'])
        gfx=[g for g in real.gfx_frames(phase['gfxinfo']) if g.get('Flags')==0 and
            g.get('DrawStart',0)<=draw['ns']<=g.get('SyncQueued',-1)]
        commits=[x for x in d['commits'] if (x['phase'],x['index'])==(draw['phase'],draw['index'])]
        row=dict(**draw,gfxMatches=len(gfx),commitMatches=len(commits),commits=commits)
        if len(gfx)!=1:row['category']='gfx-missing-or-ambiguous';rows.append(row);continue
        g=gfx[0];by_vsync=[x for x in d['metrics'] if x['publicVsync']==g['Vsync']]
        by_token=[x for x in d['metrics'] if x['raw'][1]==g['FrameTimelineVsyncId']]
        surface=[x for x in trace['surfaces'] if x['pid']==d['pid'] and x['token']==g['FrameTimelineVsyncId']]
        displays=[x for x in trace['displays'] if len(surface)==1 and x['token']==surface[0]['displayToken']]
        row.update(gfx=g,publicVsyncMatches=len(by_vsync),rawTokenMatches=len(by_token),
            metricByVsync=by_vsync,metricByToken=by_token,surfaces=surface,displays=displays)
        if len(by_vsync)==1 and len(by_token)==1 and by_vsync[0]==by_token[0]:row['category']='both-exact-same-metric'
        elif not by_vsync and len(by_token)==1:row['category']='raw-token-only'
        elif len(by_vsync)==1 and not by_token:row['category']='public-vsync-only'
        elif not by_vsync and not by_token:row['category']='no-metric-by-either-exact-id'
        else:row['category']='ambiguous-or-disagreeing-metric'
        rows.append(row)
    return rows
def report(text,manifest,device,installed):
    decoded=decode(text);save(OUT/'original.json',decoded);d=validate(decoded)
    raw=(ci.OUT/'latency-traces'/(METHOD+'.pftrace')).read_bytes();require(0<len(raw)<100663296,'Audit trace absent/capped')
    trace=real.trace_data(raw,d['pid']);rows=join(d,trace)
    summary=[]
    for phase in PHASES:
        r=[x for x in rows if x['phase']==phase]
        summary.append(dict(phase=phase,draws=len(r),commits=sum(x['commitMatches'] for x in r),
            categories={key:sum(x['category']==key for x in r) for key in sorted({x['category'] for x in r})}))
    value=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,
        performanceMeasured=False,collectorDiagnosisOnly=True,manifest=manifest,device=device,installedApkHashes=installed,
        original=d,rows=rows,summary=summary,traceSha256=hashlib.sha256(raw).hexdigest(),traceBytes=len(raw),
        publicGetterDisagreements=sum(r['raw'][3]!=r['publicVsync'] or r['raw'][2]!=r['publicIntended'] for r in d['metrics']),
        callbackDropped=sum(r['dropped'] for r in d['metrics']),lostFtraceBundles=trace['lostFtraceBundles'],
        note='192 fixed synthetic View draws,not terminal/Compose/optimization samples. Missing metrics remain missing;no guarantee failure reproduces.')
    save(OUT/'report.json',value)
    compact={k:v for k,v in value.items() if k not in ('manifest','original','rows')};compact['reportSha256']=ci.digest(OUT/'report.json')
    real.wire.legacy.compact('Window raw frame metrics audit',json.dumps(compact,separators=(',',':')).encode())
    return value
def run():
    require(os.environ.get('GITHUB_ACTIONS')=='true','CI-only audit');guard();ci.sources=sources
    manifest=json.loads((ci.OUT/'manifest.json').read_bytes());old=ci.real_clock
    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS='me.rerere.rikkahub.pipeline.TerminalFrameMetricsAuditTest',
        METHODS=(METHOD,),setup_report=report)
    try:ci.emulator(operation=lambda:ci.run_verified_bundle(manifest,'realClockSetup',emit_evidence=False))
    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
        for name in ('instrumentation.log','pipeline-logcat.txt'):
            path=ci.OUT/name
            if path.exists():ci.notice('error','Frame audit '+name,ci.failure_excerpt(path))
        raise
    finally:ci.real_clock=old
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('build','run'));a=p.parse_args()
    try:
        if a.action=='build':build()
        else:run()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','Frame metrics audit stopped',type(e).__name__+': '+str(e)[:1800]);raise SystemExit(1)
