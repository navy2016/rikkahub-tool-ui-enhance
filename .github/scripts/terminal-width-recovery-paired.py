"""P2 fixed native checkpoint A/B after correctness; one isolated three-file candidate, no P1 mix."""
import argparse,hashlib,importlib.util,json,os,shutil,statistics,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sp=importlib.util.spec_from_file_location('recovery_pair_wire',Path(__file__).with_name('run-terminal-pipeline-paired.py'))
pair=importlib.util.module_from_spec(sp);sp.loader.exec_module(pair)
sp=importlib.util.spec_from_file_location('recovery_costs',Path(__file__).with_name('terminal-width-recovery.py'))
baseline=importlib.util.module_from_spec(sp);sp.loader.exec_module(baseline)
ci=pair.ci;real=ci.real_clock;require=real.require
BASE=ROOT/'benchmarks/terminal-width-recovery-candidate';PATCH=BASE/'candidate.patch'
OUT=ROOT/'artifacts/terminal-width-recovery-paired';BUILD=ROOT/'artifacts/width-recovery-paired-build'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalWidthRecoveryPairedTest.kt'
CLASS='me.rerere.rikkahub.pipeline.TerminalWidthRecoveryPairedTest'
METHODS=('recoveryHiddenOneThousand','recoveryShownOneThousand','recoveryHiddenTenThousand','recoveryShownTenThousand')
CASES=tuple(f'widthRecovery-{size}-{ime}' for size in (1000,10000) for ime in ('hidden','shown'))
GATE_RUN=41180293887;GATE_SHA='32c07c8b6cf504cbd62a8c07a7e795cdf3f2acb8'
SETUP_RUN=41184883013;SETUP_SHA='4dc2bf59ee161b73c9cd65dcdbd5b7c7ab217ead'
PAIRS=4;SUITE='P2-width-checkpoint-paired-v2-configured-mount'
original_sources=ci.sources
def spec():return json.loads((BASE/'sources.json').read_bytes())
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def guard():
    baseline.guard();s=spec()
    require(git('show',GATE_SHA+':benchmarks/terminal-width-recovery-candidate/candidate.patch')==PATCH.read_bytes(),
        'Candidate changed since correctness gate')
    for p,h in s['files'].items():require(ci.digest(ROOT/p)==h['base'],'Production no longer baseline')
    require(len(s['files'])==3,'Not the fixed P2 three-file candidate')
    require(git('show',SETUP_SHA+':'+TEST)==(ROOT/TEST).read_bytes(),'Driver differs from successful first-mount preflight')
    return s
def sources():
    result=original_sources()
    for p in (TEST,baseline.TEST,'.github/scripts/terminal-width-recovery.py','.github/scripts/terminal-width-recovery-paired.py',
        'benchmarks/terminal-width-recovery-candidate/candidate.patch','benchmarks/terminal-width-recovery-candidate/sources.json',
        'benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','benchmarks/TERMINAL_WIDTH_RECOVERY_PAIRED.md'):
        result[p]=ci.digest(ROOT/p)
    return result
def verify_sources(manifests):
    require(set(manifests)=={'legacy','candidate'},'Missing both targets')
    a,b=manifests['legacy']['sources'],manifests['candidate']['sources'];s=spec()
    require(set(a)==set(b) and {p for p in a if a[p]!=b[p]}==set(s['files']),'Unexpected target source differences')
    for p,h in s['files'].items():require(a[p]==h['base'] and b[p]==h['candidate'],'Wrong candidate source hash')
def configure():ci.sources=sources;pair.OUT=OUT;pair.BUILD=BUILD;pair.verify_sources=verify_sources
def build():
    pair.require_ci();guard();configure();require(not git('diff','--name-only','HEAD').strip(),'Dirty tracked checkout')
    gate=json.loads(subprocess.check_output(['gh','api',f'repos/navy2016/rikkahub-tool-ui-enhance/actions/runs/{GATE_RUN}'],text=True,timeout=30))
    require(gate['head_sha']==GATE_SHA and gate['conclusion']=='success','Correctness gate not passed')
    setup=json.loads(subprocess.check_output(['gh','api',f'repos/navy2016/rikkahub-tool-ui-enhance/actions/runs/{SETUP_RUN}'],text=True,timeout=30))
    require(setup['head_sha']==SETUP_SHA and setup['conclusion']=='success','Configured first-mount preflight not passed')
    OUT.mkdir(parents=True,exist_ok=False);BUILD.mkdir(parents=True,exist_ok=False);save(OUT/'correctness-run.json',gate)
    save(OUT/'setup-run.json',setup)
    originals={p:(ROOT/p).read_bytes() for p in spec()['files']};manifests={}
    try:
        subprocess.run(['git','apply','--check',str(PATCH)],cwd=ROOT,check=True);subprocess.run(['git','apply',str(PATCH)],cwd=ROOT,check=True)
        for p,h in spec()['files'].items():require(ci.digest(ROOT/p)==h['candidate'],'Candidate overlay differs')
        for arm in ('candidate','legacy'):
            if arm=='legacy':
                for p,raw in originals.items():(ROOT/p).write_bytes(raw)
            command=['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PterminalViewportTests=true','-PincludeX86_64AbiForTests=true']
            if arm=='candidate':command+=['-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','terminalMechanismDependencies']
            command+=[':app:assembleTerminaltest']+([':app:assembleTerminaltestAndroidTest'] if arm=='candidate' else [])
            subprocess.run(command+['--stacktrace'],cwd=ROOT,check=True,timeout=1800)
            ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes())
            for role,apk in manifest['apks'].items():
                dest=BUILD/('common-test.apk' if role=='test' else arm+'/target.apk');dest.parent.mkdir(parents=True,exist_ok=True)
                if role=='test' and arm=='legacy':require(ci.digest(dest)==apk['sha256'],'Common driver was rebuilt differently')
                else:shutil.copyfile(ROOT/apk['file'],dest)
                apk['file']=str(dest.relative_to(ROOT))
            manifests[arm]=manifest
    finally:
        for p,raw in originals.items():(ROOT/p).write_bytes(raw)
        guard();require(not git('diff','--name-only','HEAD').strip(),'Source overlay not restored')
    assets=pair.verify_bundles(manifests);sha,run=ci.identity()
    shutil.copyfile(ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json',OUT/'resolved-dependencies.json')
    plan=dict(suite=SUITE,sourceSha=sha,runId=run,pairs=PAIRS,gateRun=GATE_RUN,gateSha=GATE_SHA,setupRun=SETUP_RUN,setupSha=SETUP_SHA,
        previousFailedRun=41181696048,productionChanged=False,
        manifests=manifests,patchSha256=ci.digest(PATCH),sharedAssetInventorySha256=assets)
    save(OUT/'plan.json',plan);save(ci.OUT/'manifest.json',manifests['legacy'])
def validate(text):
    previous=real.CASES
    try:real.CASES=CASES;cases=real.case_records(text)
    finally:real.CASES=previous
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP')
    require(len(cleanup)==4 and {x['case'] for x in cleanup}==set(CASES) and all(x['closed'] and x['threadStopped'] for x in cleanup),'Incomplete paired cleanup')
    for c in cases:
        _,size,ime=c['case'].split('-');size=int(size);shown=ime=='shown'
        require(c['historySize']==size and c['expectedIme']==shown and c['baselineOnly'] is False and c['passed'] and
            c['mode']=='fullVirtual' and c['clock']=='real-android-no-compose-test-clock','Incorrect native paired case')
        preparation=c['preparation']
        require([x['stage'] for x in preparation]==['before-write','after-write','persisted-and-flow-confirmed','before-mount'] and
            all(x['configured'] and not x['init'] for x in preparation[-2:]),'First-mount configuration evidence missing')
        expected=[(r,pos,condition) for r in range(5) for pos,condition in enumerate(baseline.conditions(r))]
        require([(s['index'],s['position'],s['condition']) for s in c['samples']]==expected,'Fixed per-launch transition order differs')
        require([d['phase'] for d in c['phaseDumps']]==[f'r{r}-{a}' for r,p,a in expected],'Missing exact per-window gfxinfo')
        require(len(c['gates'])==11 and len({g['rows'] for g in c['gates']})==1 and all(g['ime']==shown and g['virtual'] and
            g['columns']==80 and g['rows']>=24 for g in c['gates']),'IME/kernel grid mismatch')
        for s in c['samples']:
            marker=f'ECHO:{c["case"]}:{s["index"]:02d}:{s["position"]}'
            raw=(('\x1b[?1049l' if s['condition']=='return' else '')+'\x1b[24;1H\x1b[2K'+marker+'\r').encode()
            require(s['warmup'] is (s['index']==0) and s['sameInitialHistory'] and s['virtual'] and s['visibleAfterMeasurement'],'Recovery state changed')
            require(s['outputBytes']==len(raw) and s['outputSha256']==hashlib.sha256(raw).hexdigest(),'Native protocol differs')
            require(s['beganNs']<=s['uiNs']<=s['publishedNs']<=s['drawNs']<=s['observedNs'] and s['fedRevision']<=s['publishedRevision']<=s['drawRevision'],'Reversed sample ordering')
            events=s['events'];require(all(a['seq']<b['seq'] and a['ns']<=b['ns'] for a,b in zip(events,events[1:])),'Nonmonotonic pipeline events')
            require(sum(e['stage']=='UI_INPUT' for e in events)==1 and any(e['stage']=='FRAME_DRAWN' and e['ns']==s['drawNs'] for e in events),'Missing response draw')
            for stage in ('OUTPUT_READ','EMULATOR_FED'):require(sum(e['bytes'] for e in events if e['stage']==stage)==len(raw),'Incomplete finite response')
        require(c['metrics'] and sum(x[9] for x in c['metrics'])==c['droppedFrameMetrics'],'FrameMetrics inventory differs')
    return cases,cleanup
def parse(text,manifest,device,installed):
    cases,cleanup=validate(text);traces=[];complete=True
    for method,c in zip(METHODS,cases):
        raw=(ci.OUT/'latency-traces'/(method+'.pftrace')).read_bytes();require(0<len(raw)<100663296,'Missing/capped raw trace')
        tr=real.trace_data(raw,c['pid']);corr=real.correlate(c,tr);widths,open_width=baseline.width_slices(raw,c['pid'])
        costs=baseline.attribute(c,tr,widths)
        matched=len(corr)==10 and all(x['match']=='exact-token' for x in corr) and open_width==0
        complete=complete and matched
        traces.append(dict(case=c['case'],sha256=hashlib.sha256(raw).hexdigest(),bytes=len(raw),rows=corr,widthWork=costs,
            systemEvidenceComplete=matched,openWidthSlices=open_width))
    result=dict(cases=cases,traces=traces,cleanup=cleanup,device=device,installedApkHashes=installed,systemEvidenceComplete=complete)
    save(ci.OUT/'result.json',result)
    if not complete:ci.notice('warning','P2 paired missing system evidence','Originals retained;only finish fixed matrix. No adoption or replacement samples.')
    return result
def summarize(invocations):
    require([(x['pair'],x['position'],x['arm']) for x in invocations]==[(p,i,a) for p in range(PAIRS) for i,a in enumerate(pair.order(p))],'Complete4AB/BA launch pairs required')
    rows=[]
    for case in CASES:
        for condition in ('refresh','return'):
            row=dict(case=case,condition=condition,pairs=PAIRS)
            for key in ('inputToDrawNs','publishToDrawNs','inputToDisplayEndNs','widthWorkerRunningNs','widthWallUnionNs'):
                values={a:[] for a in pair.ARMS}
                for p in range(PAIRS):
                    for arm in pair.ARMS:
                        result=next(x['result'] for x in invocations if x['pair']==p and x['arm']==arm)
                        c=next(x for x in result['cases'] if x['case']==case);t=next(x for x in result['traces'] if x['case']==case)
                        selected=[dict(r,**{k:v for k,v in w.items() if k.startswith('width')}) for s,r,w in zip(c['samples'],t['rows'],t['widthWork'])
                            if s['condition']==condition and not s['warmup']]
                        require(len(selected)==4,'Four measured transitions required per condition')
                        values[arm].append(statistics.median(s[key] for s in selected) if all(key in s for s in selected) else None)
                ratios=[(b/a if a>0 else (1 if b==0 else None)) if a is not None and b is not None else None
                    for a,b in zip(values['legacy'],values['candidate'])]
                deltas=[b-a if a is not None and b is not None else None for a,b in zip(values['legacy'],values['candidate'])]
                all_present=all(x is not None for x in ratios)
                row[key]=dict(arms=values,pairedRatios=ratios,medianRatio=statistics.median(ratios) if all_present else None,
                    medianDeltaNs=statistics.median(deltas) if all(x is not None for x in deltas) else None,
                    wins=sum(x<1 for x in ratios if x is not None),complete=all_present)
            rows.append(row)
    passed=all(x['result']['systemEvidenceComplete'] for x in invocations) and all(
        r['inputToDrawNs']['complete'] and r['inputToDisplayEndNs']['complete'] and
        r['inputToDrawNs']['medianRatio'] <= (.80 if r['condition']=='return' else 1.05) and
        (r['condition']!='return' or r['inputToDrawNs']['wins']>=3) and r['inputToDisplayEndNs']['medianRatio']<=1.05 for r in rows)
    return rows,passed
def experiment():
    pair.require_ci();guard();configure();sha,run=ci.identity();plan=json.loads((OUT/'plan.json').read_bytes())
    require(plan['sourceSha']==sha and plan['runId']==run and plan['patchSha256']==ci.digest(PATCH),'Wrong paired plan')
    require(pair.verify_bundles(plan['manifests'])==plan['sharedAssetInventorySha256'],'Runtime bundle differs')
    original_out=ci.OUT;original_report=ci.real_clock
    def operation():
        doc=dict(suite=SUITE,sourceSha=sha,runId=run,plan=plan,boot=pair.read_boot(),invocations=[])
        try:
            for p in range(PAIRS):
                for pos,arm in enumerate(pair.order(p)):
                    label=f'pair-{p}-{pos}-{arm}';ci.OUT=OUT/label;ci.OUT.mkdir(exist_ok=False)
                    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS=CLASS,METHODS=METHODS,report=parse)
                    require(pair.read_boot()==doc['boot'],'Native guest rebooted')
                    try:result=ci.run_verified_bundle(plan['manifests'][arm],'realClock',emit_evidence=False)
                    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
                        for name in ('instrumentation.log','pipeline-logcat.txt'):
                            f=ci.OUT/name
                            if f.exists():ci.notice('error','P2 '+label,ci.failure_excerpt(f))
                        raise
                    require(pair.read_boot()==doc['boot'],'Native guest rebooted during invocation')
                    doc['invocations'].append(dict(pair=p,position=pos,arm=arm,result=result));save(OUT/'progress.json',doc)
            rows,passed=summarize(doc['invocations']);doc.update(summary=rows,passesFrozenPerformanceScreen=passed)
            save(OUT/'paired-results.json',doc)
            summary=dict(sourceSha=sha,runId=run,productionChanged=False,windows=320,warmups=64,pairs=PAIRS,
                summary=rows,passesFrozenPerformanceScreen=passed,resultSha256=ci.digest(OUT/'paired-results.json'),
                note='Same-transition,unchanged resources/shared driver,4AB/BAlaunchpairs. No phoneFPS/first-visible-frame or automatic production adoption.')
            save(OUT/'summary.json',summary);real.wire.legacy.compact('P2 width recovery paired',json.dumps(summary,separators=(',',':')).encode())
            require(all(x['result']['systemEvidenceComplete'] for x in doc['invocations']),'Incomplete exact system evidence; no replacement sampling')
        finally:ci.OUT=original_out;ci.real_clock=original_report
    ci.emulator(operation=operation)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','build','experiment'));a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        elif a.action=='build':build()
        else:experiment()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','P2 paired stopped',type(e).__name__+': '+str(e)[:1800]);raise SystemExit(1)
