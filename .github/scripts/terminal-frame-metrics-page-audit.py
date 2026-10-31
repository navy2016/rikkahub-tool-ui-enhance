"""One fixed unmodified-terminal collector diagnosis with independent native observer queues."""
import argparse,hashlib,importlib.util,json,os,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'artifacts/terminal-frame-metrics-page-audit'
s=importlib.util.spec_from_file_location('page_frame_audit_wire',Path(__file__).with_name('run-terminal-pipeline.py'))
ci=importlib.util.module_from_spec(s);s.loader.exec_module(ci)
real=ci.real_clock;require=real.require;old_sources=ci.sources
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalFrameMetricsPageAuditTest.kt'
METHODS=('pageMetricsAuditHidden','pageMetricsAuditShown');CASES=('frameAuditPage-hidden','frameAuditPage-shown')
def guard():
    require(subprocess.check_output(['git','rev-parse','HEAD:app/src/main'],cwd=ROOT,text=True).strip()=='8caf50d5c40e228f1d5bcc09d3db505757568b68','Production changed')
    require(not subprocess.check_output(['git','-c','core.fileMode=false','diff','--name-only','55db111129c662067345aa99540b506de305da37',
        '--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml'],cwd=ROOT).strip(),'Page collector diagnosis cannot contain any candidate')
def save(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n')
def sources():
    result=old_sources()
    for p in (TEST,'.github/scripts/terminal-frame-metrics-page-audit.py','benchmarks/TERMINAL_FRAME_METRICS_PAGE_AUDIT.md'):
        result[p]=ci.digest(ROOT/p)
    return result
def build():
    require(os.environ.get('GITHUB_ACTIONS')=='true','CI-only build');guard();ci.sources=sources
    subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PincludeX86_64AbiForTests=true',
        ':app:assembleTerminaltest',':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
    ci.prepare()
def decode(text):
    previous=real.CASES
    try:real.CASES=CASES;return real.case_records(text)
    finally:real.CASES=previous
def validate(cases):
    require([c['case'] for c in cases]==list(CASES),'Two fixed page-audit cases required')
    for c in cases:
        require(c['passed'] and c['collectorDiagnosisOnly'] and c['performanceMeasured'] is False and c['mode']=='fullVirtual','Not raw collector diagnosis')
        require('incrementLaunchCount:' in c['startupReceipt'],'Startup write not fenced')
        require(c['historySize']==10000 and c['expectedIme']==c['case'].endswith('-shown'),'Wrong size/IME')
        expected=[(r,p,cond) for r in range(20) for p,cond in enumerate(('refresh','return') if r%2==0 else ('return','refresh'))]
        require([(s['index'],s['position'],s['condition']) for s in c['samples']]==expected,'Forty fixed windows required')
        require([x['phase'] for x in c['phaseDumps']]==[f'r{r}-{cond}' for r,p,cond in expected],'Incomplete per-window gfxinfo')
        require(len(c['gates'])==41 and len({g['rows'] for g in c['gates']})==1 and all(g['columns']==80 and g['virtual'] and
            g['ime']==c['expectedIme'] for g in c['gates']),'Native configuration not stable')
        require(all(n['configured'] and not n['init'] for n in c['preparation'][-2:]),'First-mount settings not confirmed')
        for sample in c['samples']:
            marker=f'ECHO:{c["case"]}:{sample["index"]:02d}:{sample["position"]}'
            raw=(('\x1b[?1049l' if sample['condition']=='return' else '')+'\x1b[24;1H\x1b[2K'+marker+'\r').encode()
            require(sample['outputBytes']==len(raw) and sample['outputSha256']==hashlib.sha256(raw).hexdigest(),'Response bytes changed')
            require(sample['sameInitialHistory'] and sample['visibleAfterMeasurement'] and sample['virtual'],'Page correctness failed')
            require(sample['uiNs']<=sample['publishedNs']<=sample['drawNs']<=sample['observedNs'],'Reversed pipeline ordering')
        for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw')):
            require(0<len(c[key])<8192 and len(c[key])==len(c[rawkey]) and all(len(v)==23 for v in c[rawkey]),'Invalid paired raw metrics arrays')
            require(all(len(v)==10 and v[9]>=0 for v in c[key]),'Metric callback shape differs')
    return cases
def classify(c,tr):
    rows=[]
    for sample in c['samples']:
        phase=next(p for p in c['phaseDumps'] if p['phase']==sample['phase'])
        gfx=[g for g in real.gfx_frames(phase['gfxinfo']) if g.get('Flags')==0 and g.get('DrawStart',0)<=sample['drawNs']<=g.get('SyncQueued',-1)]
        r=dict(phase=sample['phase'],index=sample['index'],condition=sample['condition'],gfxMatches=len(gfx))
        if len(gfx)!=1:r['category']='gfx-not-unique';rows.append(r);continue
        g=gfx[0];r['gfx']=g
        for name,metric,rawkey in (('primary','metrics','metricsRaw'),('mirror','mirrorMetrics','mirrorRaw')):
            by_token=[dict(callback=x,raw=y) for x,y in zip(c[metric],c[rawkey]) if y[1]==g['FrameTimelineVsyncId']]
            by_vsync=[dict(callback=x,raw=y) for x,y in zip(c[metric],c[rawkey]) if x[2]==g['Vsync']]
            r[name]=dict(byToken=by_token,byVsync=by_vsync)
        p=r['primary'];q=r['mirror']
        def exact(v):return len(v['byToken'])==1 and len(v['byVsync'])==1 and v['byToken'][0]==v['byVsync'][0]
        if exact(p) and exact(q):r['category']='both-observers-exact'
        elif not p['byToken'] and not p['byVsync'] and not q['byToken'] and not q['byVsync']:r['category']='both-observers-no-metric'
        elif exact(p):r['category']='primary-only-exact'
        elif exact(q):r['category']='mirror-only-exact'
        else:r['category']='token-vsync-ambiguity'
        r['surfaces']=[x for x in tr['surfaces'] if x['pid']==c['pid'] and x['token']==g['FrameTimelineVsyncId']]
        r['displays']=[x for x in tr['displays'] if len(r['surfaces'])==1 and x['token']==r['surfaces'][0]['displayToken']]
        rows.append(r)
    return rows
def report(text,manifest,device,installed):
    cases=decode(text);save(OUT/'original-cases.json',cases);validate(cases)
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP')
    require(len(cleanup)==2 and all(x['closed'] and x['threadStopped'] for x in cleanup),'Page audit cleanup incomplete')
    result=[]
    for method,c in zip(METHODS,cases):
        raw=(ci.OUT/'latency-traces'/(method+'.pftrace')).read_bytes();require(0<len(raw)<100663296,'Page audit trace missing/capped')
        tr=real.trace_data(raw,c['pid']);rows=classify(c,tr)
        result.append(dict(case=c['case'],rows=rows,traceSha256=hashlib.sha256(raw).hexdigest(),traceBytes=len(raw),lostFtraceBundles=tr['lostFtraceBundles'],
            categories={k:sum(r['category']==k for r in rows) for k in sorted({r['category'] for r in rows})},
            primaryDropped=sum(x[9] for x in c['metrics']),mirrorDropped=sum(x[9] for x in c['mirrorMetrics']),
            publicRawDisagreements=sum(x[2]!=y[3] or x[1]!=y[2] for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw')) for x,y in zip(c[key],c[rawkey]))))
    value=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,performanceMeasured=False,
        collectorDiagnosisOnly=True,manifest=manifest,device=device,installedApkHashes=installed,cases=cases,cleanup=cleanup,result=result,
        note='One fixed80window unmodified-terminal collector test with2independent observer queues. Not P2performance or resampling of failed windows.')
    save(OUT/'report.json',value)
    compact={k:v for k,v in value.items() if k not in ('manifest','cases','result')};compact['summary']=[{k:v for k,v in r.items() if k!='rows'} for r in result]
    compact['reportSha256']=ci.digest(OUT/'report.json');real.wire.legacy.compact('Terminal dual metrics audit',json.dumps(compact,separators=(',',':')).encode())
    return value
def run():
    require(os.environ.get('GITHUB_ACTIONS')=='true','CI-only collector test');guard();ci.sources=sources
    manifest=json.loads((ci.OUT/'manifest.json').read_bytes());old=ci.real_clock
    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS='me.rerere.rikkahub.pipeline.TerminalFrameMetricsPageAuditTest',METHODS=METHODS,report=report)
    try:ci.emulator(operation=lambda:ci.run_verified_bundle(manifest,'realClock',emit_evidence=False))
    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
        for name in ('instrumentation.log','pipeline-logcat.txt'):
            p=ci.OUT/name
            if p.exists():ci.notice('error','Dual frame metrics '+name,ci.failure_excerpt(p))
        raise
    finally:ci.real_clock=old
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('build','run'));a=p.parse_args()
    try:
        if a.action=='build':build()
        else:run()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','Dual frame audit stopped',type(e).__name__+': '+str(e)[:1700]);raise SystemExit(1)
