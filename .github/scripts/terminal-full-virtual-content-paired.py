"""Independent P1 uninstrumented full-page pairing; no rejected width optimization or old resampling."""
import argparse,hashlib,importlib.util,json,os,shutil,statistics,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sp=importlib.util.spec_from_file_location('content_wire',Path(__file__).with_name('terminal-alt-width.py'))
wire=importlib.util.module_from_spec(sp);sp.loader.exec_module(wire)
ci=wire.ci;pair=wire.pair;real=wire.real;require=wire.require
BASE=ROOT/'benchmarks/terminal-full-virtual-alt-content';PATCH=BASE/'candidate.patch'
OUT=ROOT/'artifacts/terminal-content-paired';BUILD=ROOT/'artifacts/content-paired-build'
SUITE='full-virtual-content-paired-v1';PAIRS=4
GATE_RUN=41167700810;GATE_SHA='fae4f8ea771e24f3decd7b3b7c3548e42fb86a4e'
def spec():return json.loads((BASE/'sources.json').read_bytes())
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def guard():
    s=spec();require(git('rev-parse','HEAD:app/src/main').decode().strip()==s['baseTree'],'Production changed before pairing')
    require(len(s['files'])==1,'P1 must be one-file candidate')
    for p,v in s['files'].items():require(ci.digest(ROOT/p)==v['base'],'Live source differs from base:'+p)
    require(git('show',GATE_SHA+':benchmarks/terminal-full-virtual-alt-content/candidate.patch')==PATCH.read_bytes(),'Candidate changed since passed mechanism gate')
    return s
def sources():
    result=wire.original_sources()
    for p in (wire.PAGE,'.github/scripts/terminal-full-virtual-content-paired.py','.github/scripts/terminal-alt-width.py',
        'benchmarks/terminal-full-virtual-alt-content/candidate.patch','benchmarks/terminal-full-virtual-alt-content/sources.json',
        'benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle'):
        result[p]=ci.digest(ROOT/p)
    return result
def verify_sources(manifests):
    require(set(manifests)=={'legacy','candidate'},'Two source arms required')
    a,b=manifests['legacy']['sources'],manifests['candidate']['sources'];s=spec()
    require(set(a)==set(b) and {p for p in a if a[p]!=b[p]}==set(s['files']),'Not an exact one-file source difference')
    for p,v in s['files'].items():require(a[p]==v['base'] and b[p]==v['candidate'],'Wrong P1 candidate')
def configure():ci.sources=sources;pair.BUILD=BUILD;pair.OUT=OUT;pair.verify_sources=verify_sources
def build():
    pair.require_ci();guard();configure();require(not git('diff','--name-only','HEAD').strip(),'Tracked checkout dirty')
    r=json.loads(subprocess.check_output(['gh','api',f'repos/navy2016/rikkahub-tool-ui-enhance/actions/runs/{GATE_RUN}'],text=True,timeout=30))
    require(r['head_sha']==GATE_SHA and r['conclusion']=='success','P1 mechanism prerequisite not passed')
    OUT.mkdir(parents=True,exist_ok=False);BUILD.mkdir(parents=True,exist_ok=False)
    save(OUT/'mechanism-run-receipt.json',r);before={p:(ROOT/p).read_bytes() for p in spec()['files']};manifests={}
    try:
        subprocess.run(['git','apply','--check',str(PATCH)],cwd=ROOT,check=True);subprocess.run(['git','apply',str(PATCH)],cwd=ROOT,check=True)
        for p,v in spec()['files'].items():require(ci.digest(ROOT/p)==v['candidate'],'Candidate patch mismatch')
        for arm in ('candidate','legacy'):
            if arm=='legacy':
                for p,raw in before.items():(ROOT/p).write_bytes(raw)
            tasks=[':app:assembleTerminaltest']+([':app:assembleTerminaltestAndroidTest'] if arm=='candidate' else [])
            args=['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PincludeX86_64AbiForTests=true','-PterminalViewportTests=true']
            if arm=='candidate':args+=['-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','terminalMechanismDependencies']
            subprocess.run(args+tasks+['--stacktrace'],cwd=ROOT,check=True,timeout=1800)
            ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes())
            for role,apk in manifest['apks'].items():
                dest=BUILD/('common-test.apk' if role=='test' else arm+'/target.apk');dest.parent.mkdir(parents=True,exist_ok=True)
                if role=='test' and arm=='legacy':require(ci.digest(dest)==apk['sha256'],'Common driver changed')
                else:shutil.copyfile(ROOT/apk['file'],dest)
                apk['file']=str(dest.relative_to(ROOT))
            manifests[arm]=manifest
    finally:
        for p,raw in before.items():(ROOT/p).write_bytes(raw)
        guard();require(not git('diff','--name-only','HEAD').strip(),'Candidate overlay was not restored')
    assets=pair.verify_bundles(manifests);sha,run=ci.identity()
    deps=json.loads((ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json').read_bytes())
    save(OUT/'resolved-dependencies.json',deps)
    plan=dict(suite=SUITE,sourceSha=sha,runId=run,pairs=PAIRS,mechanismRun=GATE_RUN,mechanismSha=GATE_SHA,
        manifests=manifests,patchSha256=ci.digest(PATCH),sharedAssetInventorySha256=assets,productionChanged=False,baseProductionSha=spec()['baseProductionSha'])
    save(OUT/'plan.json',plan);save(ci.OUT/'manifest.json',manifests['legacy'])
    ci.notice('notice','P1 shared driver verified',json.dumps(dict(sourceSha=sha,run=run,driver=manifests['legacy']['apks']['test']['sha256'],mechanismRun=GATE_RUN)))
def parse(text,manifest,device,installed):
    # Same verified raw protocol/frame parser, but preserve all originals before strict matching gate.
    old=real.CASES
    try:real.CASES=('fullVirtual-alt',);cases=real.case_records(text)
    finally:real.CASES=old
    require(len(cases)==1,'Wrong one-case invocation');case=wire.validate_case(cases[0])
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP');require(len(cleanup)==1 and cleanup[0]['closed'] and cleanup[0]['threadStopped'],'Page cleanup incomplete')
    path=ci.OUT/'latency-traces'/(wire.METHOD+'.pftrace');raw=path.read_bytes();require(0<len(raw)<100663296,'Trace absent/capped')
    tr=real.trace_data(raw,case['pid']);rows=real.correlate(case,tr)
    result=dict(case=case,correlation=rows,device=device,installedApkHashes=installed,traceSha256=hashlib.sha256(raw).hexdigest(),traceBytes=len(raw),
        matched=sum(s['match']=='exact-token' for s in rows),systemEvidenceComplete=len(rows)==36 and all(s['match']=='exact-token' for s in rows))
    save(ci.OUT/'result.json',result)
    if not result['systemEvidenceComplete']:
        ci.notice('warning','P1 incomplete system association',json.dumps(dict(matched=result['matched'],required=36,
            note='Keep missing evidence and finish only the already-fixed matrix; final acceptance/CI still fails.')))
    require(tr['lostFtraceBundles']==0 and tr['compactSchedBundles']==0 and tr['ftraceClocks']==[0],'Scheduler evidence incomplete')
    return result
def summarize(invocations):
    require([(x['pair'],x['position'],x['arm']) for x in invocations]==[(p,i,a) for p in range(PAIRS) for i,a in enumerate(pair.order(p))],'Four complete interleaved pairs required')
    rows=[]
    for phase in real.PHASES:
        entry=dict(phase=phase,pairs=PAIRS)
        for key in ('inputToDrawNs','publishToDrawNs','inputToDisplayEndNs','mainRunningPublishToDrawNs'):
            values={a:[] for a in pair.ARMS}
            for p in range(PAIRS):
                for a in pair.ARMS:
                    r=next(x['result'] for x in invocations if x['pair']==p and x['arm']==a)
                    selected=[s for s in r['correlation'] if s['phase']==phase and not s['warmup']]
                    require(len(selected)==10,'Fixed per-phase sample count differs')
                    values[a].append(statistics.median(s[key] for s in selected) if all(key in s for s in selected) else None)
            ratios=[b/a if a is not None and b is not None else None for a,b in zip(values['legacy'],values['candidate'])]
            complete=all(r is not None for r in ratios)
            entry[key]=dict(arms=values,pairedRatios=ratios,medianRatio=statistics.median(ratios) if complete else None,
                wins=sum(r<1 for r in ratios if r is not None),complete=complete)
        rows.append(entry)
    accepted=all(i['result']['systemEvidenceComplete'] for i in invocations) and all(r['inputToDrawNs']['complete'] and
        r['inputToDrawNs']['medianRatio']<=.90 and r['inputToDrawNs']['wins']>=3 and
        r['inputToDisplayEndNs']['complete'] and r['inputToDisplayEndNs']['medianRatio']<=1.05 for r in rows)
    return rows,accepted
def experiment():
    pair.require_ci();guard();configure();plan=json.loads((OUT/'plan.json').read_bytes());sha,run=ci.identity()
    require(plan['sourceSha']==sha and plan['runId']==run and plan['patchSha256']==ci.digest(PATCH),'Plan identity changed')
    require(pair.verify_bundles(plan['manifests'])==plan['sharedAssetInventorySha256'],'Bundle resources differ')
    original_out=ci.OUT;original_reporter=ci.real_clock
    def run_all():
        data=dict(suite=SUITE,sourceSha=sha,runId=run,boot=pair.read_boot(),plan=plan,invocations=[])
        try:
            for p in range(PAIRS):
                for pos,arm in enumerate(pair.order(p)):
                    label=f'pair-{p}-{pos}-{arm}';ci.OUT=OUT/label;ci.OUT.mkdir(exist_ok=False)
                    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS=wire.CLASS,METHODS=(wire.METHOD,),report=parse)
                    require(pair.read_boot()==data['boot'],'Guest rebooted')
                    try:result=ci.run_verified_bundle(plan['manifests'][arm],'realClock',emit_evidence=False)
                    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
                        for name in ('instrumentation.log','pipeline-logcat.txt'):
                            f=ci.OUT/name
                            if f.exists():ci.notice('error','P1 '+label,ci.failure_excerpt(f))
                        raise
                    require(pair.read_boot()==data['boot'],'Guest rebooted mid-case')
                    data['invocations'].append(dict(pair=p,position=pos,arm=arm,result=result));save(OUT/'progress.json',data)
            rows,accepted=summarize(data['invocations']);data.update(summary=rows,passesFrozenPerformanceScreen=accepted)
            save(OUT/'paired-results.json',data)
            summary=dict(sourceSha=sha,runId=run,mechanismRun=GATE_RUN,productionChanged=False,windows=288,warmups=48,pairs=PAIRS,
                summary=rows,passesFrozenPerformanceScreen=accepted,resultSha256=ci.digest(OUT/'paired-results.json'),
                note='Uninstrumented one-file candidate; same-boot AB/BA; no work-count speed claim, phoneFPS or old-run mixing. Full regressions required before production.')
            save(OUT/'summary.json',summary);real.wire.legacy.compact('Full virtual P1 paired',json.dumps(summary,separators=(',',':')).encode())
            require(all(i['result']['systemEvidenceComplete'] for i in data['invocations']),
                'Incomplete system evidence: fixed matrix preserved, no adoption or replacement samples')
        finally:ci.OUT=original_out;ci.real_clock=original_reporter
    ci.emulator(operation=run_all)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','build','experiment'));a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        elif a.action=='build':build()
        else:experiment()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','Full virtual P1 paired stopped',type(e).__name__+': '+str(e)[:1800]);raise SystemExit(1)
