"""Preregistered P2 exact-buffer performance, independent of old failed P2 matrices."""
import argparse,collections,hashlib,importlib.util,json,os,shutil,statistics,subprocess,types
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
PROTOCOL=ROOT/'benchmarks/terminal-p2-identity-performance/protocol.json'
PROTOCOL_SHA256='99a3281c6646b4cbe22964d293774d0f8cbb12091a2a1bc0558109f925b4368f'
s=importlib.util.spec_from_file_location('p2_identity',Path(__file__).with_name('terminal-frame-identity.py'))
identity=importlib.util.module_from_spec(s);s.loader.exec_module(identity)
require=identity.require
METRICS=('inputToBufferEvidenceNs','inputToAttemptNs','inputToDisplayEndNs')
FREEZE_SHA='3f4b031fb73dc16cdb41ae0775edf30fa664f17d'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalP2IdentityPerformanceTest.kt'
METHODS=('p2IdentityOneThousandHidden','p2IdentityOneThousandShown','p2IdentityTenThousandHidden','p2IdentityTenThousandShown')
CLASS='me.rerere.rikkahub.pipeline.TerminalP2IdentityPerformanceTest'
OUT=ROOT/'artifacts/terminal-p2-identity-performance';BUILD=ROOT/'artifacts/p2-identity-build'
BASE=ROOT/'benchmarks/terminal-width-recovery-candidate';PATCH=BASE/'candidate.patch'
s=importlib.util.spec_from_file_location('p2_identity_pair_wire',Path(__file__).with_name('run-terminal-pipeline-paired.py'))
pair=importlib.util.module_from_spec(s);s.loader.exec_module(pair)
ci=pair.ci;real=ci.real_clock;old_sources=ci.sources

def protocol():
    raw=PROTOCOL.read_bytes();require(hashlib.sha256(raw).hexdigest()==PROTOCOL_SHA256,'Frozen P2 performance protocol changed')
    return json.loads(raw)

def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def candidate_spec():return json.loads((BASE/'sources.json').read_bytes())
def api(path):return json.loads(subprocess.check_output(['gh','api','repos/navy2016/rikkahub-tool-ui-enhance/'+path],text=True,timeout=30))
def guard():
    p=protocol();require(identity.protocol()['id']=='terminal-frame-identity-v1','Wrong identity protocol')
    require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['productionTree'],'Production tree changed')
    require(not git('diff','--name-only',p['productionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml').strip(),'Production/dependency overlay present')
    require(git('show',FREEZE_SHA+':benchmarks/terminal-p2-identity-performance/protocol.json')==PROTOCOL.read_bytes(),'Performance protocol changed after freeze')
    require(git('show',p['candidateCorrectnessSha']+':'+str(PATCH.relative_to(ROOT)))==PATCH.read_bytes(),'Candidate differs from correctness gate')
    require(git('show',p['identityParserSha']+':.github/scripts/terminal-frame-identity.py')==(ROOT/'.github/scripts/terminal-frame-identity.py').read_bytes(),'Qualified identity parser changed')
    require(len(candidate_spec()['files'])==p['candidateFiles'],'Candidate scope differs')
    for name,h in candidate_spec()['files'].items():require(ci.digest(ROOT/name)==h['base'],'Live source is not baseline:'+name)
    return p
def sources():
    result=old_sources()
    for name in (TEST,'.github/scripts/terminal-p2-identity-performance.py','.github/scripts/terminal-frame-identity.py',
        'benchmarks/terminal-p2-identity-performance/protocol.json','benchmarks/terminal-frame-identity/protocol.json',
        'benchmarks/terminal-width-recovery-candidate/candidate.patch','benchmarks/terminal-width-recovery-candidate/sources.json',
        'benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle'):
        result[name]=ci.digest(ROOT/name)
    return result
def verify_sources(manifests):
    require(set(manifests)=={'legacy','candidate'},'Both target arms required')
    a,b=manifests['legacy']['sources'],manifests['candidate']['sources'];spec=candidate_spec()
    require(set(a)==set(b) and {k for k in a if a[k]!=b[k]}==set(spec['files']),'Not an exact three-file source difference')
    for name,h in spec['files'].items():require(a[name]==h['base'] and b[name]==h['candidate'],'Target patch digest differs')
def configure():ci.sources=sources;pair.OUT=OUT;pair.BUILD=BUILD;pair.verify_sources=verify_sources

def prerequisite():
    """Read-only old-artifact recovery, before any new Android builds; original run stays failure."""
    pair.require_ci();p=guard();q=p['qualification'];OUT.mkdir(parents=True,exist_ok=False)
    run=api(f'actions/runs/{q["run"]}');artifact=api(f'actions/artifacts/{q["artifactId"]}')
    require(run['head_sha']==q['deviceSourceSha'] and run['conclusion']==q['originalConclusion'],'Qualification run receipt changed')
    require(artifact['name']==q['artifactName'] and artifact['workflow_run']['id']==q['run'] and not artifact['expired'],'Qualification artifact unavailable or mislabeled')
    save(OUT/'qualification-run-receipt.json',run);save(OUT/'qualification-artifact-receipt.json',artifact)
    folder=OUT/'qualification-original'
    subprocess.run(['gh','run','download',str(q['run']),'--repo','navy2016/rikkahub-tool-ui-enhance','--name',q['artifactName'],'--dir',str(folder)],check=True,timeout=180)
    require(not any(x.suffix.lower() in ('.apk','.aab') for x in folder.rglob('*')),'Prerequisite artifact unexpectedly contains APK')
    candidates=list(folder.rglob('terminal-frame-identity-qualification/report.json'));require(len(candidates)==1,'Qualification original report ambiguous')
    report_path=candidates[0];raw=report_path.read_bytes();require(hashlib.sha256(raw).hexdigest()==q['originalReportSha256'],'Original report hash differs')
    report=json.loads(raw);require(report['sourceSha']==q['deviceSourceSha'] and str(report['runId'])==str(q['run']) and report['qualificationPassed'] is False,'Original failure relabeled')
    require(report['protocolSha256']==p['identityProtocolSha256'],'Qualification protocol differs')
    for role,apk in report['manifest']['apks'].items():require(report['installedApkHashes'][role]==apk['sha256'],'Qualification installed binary proof differs')
    methods=('identityOneThousandHidden','identityOneThousandShown','identityTenThousandHidden','identityTenThousandShown')
    windows=0;attempts=0;markers=0;recovered=[]
    for method,c in zip(methods,report['cases']):
        files=list(folder.rglob(method+'.pftrace'));require(len(files)==1,'Qualification trace missing or ambiguous')
        tr=identity.parse_trace(files[0].read_bytes(),c['pid']);index=identity.FrameIndex(tr);queues=observers(c)
        rows=[identity.classify_window(w,index,queues) for w in c['samples']]
        require(not tr['faults'] and all(r['identityDecidable'] for r in rows),'Read-only qualification recovery failed')
        valid=[a for r in rows for a in r['attempts'] if a['attempt']=='QUALIFIED']
        require(all(a['submission']=='SUBMITTED' and a['presentation']=='PRESENTED' and a['metrics']['state']=='BOTH_EXACT' for a in valid),'Qualification chain differs')
        windows+=len(rows);attempts+=len(valid);markers+=len(tr['sequenceInitialMarkers'])
        recovered.append(dict(case=c['case'],traceSha256=tr['traceSha256'],rows=rows,sequenceInitialMarkers=tr['sequenceInitialMarkers']))
    require((windows,attempts,markers)==(q['expectedRecoveredWindows'],q['expectedRecoveredAttempts'],q['expectedProvenSequenceStarts']),'Qualification inventory differs')
    save(OUT/'qualification-recovery.json',dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],originalRun=q['run'],
        originalRunConclusion=run['conclusion'],noNewDeviceSamples=True,windows=windows,attempts=attempts,sequenceStarts=markers,recovered=recovered))
    ci.notice('notice','Read-only identity prerequisite',f'{windows} windows/{attempts} exact chains from original run {q["run"]}; original CI remains failure.')

def build():
    pair.require_ci();p=guard();configure()
    prerequisite=json.loads((OUT/'qualification-recovery.json').read_bytes())
    require(prerequisite['sourceSha']==os.environ['GITHUB_SHA'] and prerequisite['runId']==os.environ['GITHUB_RUN_ID'],'Read-only prerequisite not completed in this run')
    gate=api(f'actions/runs/{p["candidateCorrectnessRun"]}')
    require(gate['head_sha']==p['candidateCorrectnessSha'] and gate['conclusion']=='success','P2 correctness gate not successful')
    save(OUT/'candidate-correctness-receipt.json',gate)
    require(not git('diff','--name-only','HEAD').strip(),'Dirty tracked checkout');BUILD.mkdir(parents=True,exist_ok=False)
    originals={name:(ROOT/name).read_bytes() for name in candidate_spec()['files']};manifests={}
    try:
        subprocess.run(['git','apply','--check',str(PATCH)],cwd=ROOT,check=True);subprocess.run(['git','apply',str(PATCH)],cwd=ROOT,check=True)
        for name,h in candidate_spec()['files'].items():require(ci.digest(ROOT/name)==h['candidate'],'Temporary candidate differs')
        for arm in ('candidate','legacy'):
            if arm=='legacy':
                for name,raw in originals.items():(ROOT/name).write_bytes(raw)
            command=['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PterminalViewportTests=true','-PincludeX86_64AbiForTests=true']
            if arm=='candidate':command+=['-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','terminalMechanismDependencies']
            command+=[':app:assembleTerminaltest']+([':app:assembleTerminaltestAndroidTest'] if arm=='candidate' else [])
            subprocess.run(command+['--stacktrace'],cwd=ROOT,check=True,timeout=1800)
            ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes())
            for role,apk in manifest['apks'].items():
                dest=BUILD/('common-test.apk' if role=='test' else arm+'/target.apk');dest.parent.mkdir(parents=True,exist_ok=True)
                if role=='test' and arm=='legacy':require(ci.digest(dest)==apk['sha256'],'Common driver differs between targets')
                else:shutil.copyfile(ROOT/apk['file'],dest)
                apk['file']=str(dest.relative_to(ROOT))
            manifests[arm]=manifest
    finally:
        for name,raw in originals.items():(ROOT/name).write_bytes(raw)
        guard();require(not git('diff','--name-only','HEAD').strip(),'Production overlay not restored')
    assets=pair.verify_bundles(manifests);sha,run=ci.identity()
    shutil.copyfile(ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json',OUT/'resolved-dependencies.json')
    save(OUT/'plan.json',dict(sourceSha=sha,runId=run,freezeSha=FREEZE_SHA,protocol=p,protocolSha256=PROTOCOL_SHA256,productionChanged=False,
        manifests=manifests,patchSha256=ci.digest(PATCH),sharedAssetInventorySha256=assets))
    save(ci.OUT/'manifest.json',manifests['legacy'])

def observers(case):
    return [[dict(raw=raw,publicIntended=v[1],publicVsync=v[2],callbackBootNs=v[0],dropped=v[9]) for v,raw in zip(case[key],case[rawkey])]
        for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw'))]

def validate_cases(text):
    p=protocol();matrix=p['matrix'];old=real.CASES
    try:real.CASES=tuple(matrix['cases']);cases=real.case_records(text)
    finally:real.CASES=old
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP')
    require(len(cleanup)==4 and {x['case'] for x in cleanup}==set(matrix['cases']) and all(x['closed'] and x['threadStopped'] for x in cleanup),'Performance invocation cleanup incomplete')
    for c in cases:
        _,size,ime=c['case'].split('-');size=int(size);shown=ime=='shown'
        require(c['passed'] and c['performanceMeasured'] is True and c['collectorDiagnosisOnly'] is False and c['protocolId']==p['id'],'Performance case mislabeled')
        require(c['clock']=='CLOCK_BOOTTIME' and c['mode']=='fullVirtual' and c['historySize']==size and c['expectedIme']==shown,'Wrong clock/page/IME')
        require('incrementLaunchCount:' in c['startupReceipt'],'Startup completion not proven')
        require([x['stage'] for x in c['preparation']]==['before-write','after-write','persisted-and-flow-confirmed','before-mount'] and
            all(x['configured'] and not x['init'] for x in c['preparation'][-2:]),'Configured first mount missing')
        expected=[(r,pos,cond) for r in range(matrix['rounds']) for pos,cond in enumerate(('refresh','return') if r%2==0 else ('return','refresh'))]
        require([(x['index'],x['position'],x['condition']) for x in c['samples']]==expected,'Per-case fixed matrix differs')
        require(len(c['gates'])==len(expected)+1 and len({g['rows'] for g in c['gates']})==1 and all(g['columns']==80 and g['rows']>=24 and
            g['ime']==shown and g['virtual'] for g in c['gates']),'Physical grid/IME drift')
        for sample in c['samples']:
            marker=f'ECHO:{c["case"]}:{sample["index"]:02d}:{sample["position"]}'
            raw=(('\x1b[?1049l' if sample['condition']=='return' else '')+'\x1b[24;1H\x1b[2K'+marker+'\r').encode()
            require(sample['warmup'] is (sample['index'] in matrix['warmupRounds']) and sample['clock']=='CLOCK_BOOTTIME','Sample clock/warmup changed')
            require(sample['sameInitialHistory'] and sample['visibleAfterMeasurement'] and sample['virtual'],'Page response proof missing')
            require(sample['outputBytes']==len(raw) and sample['outputSha256']==hashlib.sha256(raw).hexdigest(),'Finite response differs')
            require(sample['fedRevision']==sample['publishedRevision']==sample['drawRevision'],'Exact response revision differs')
            require(sample['beganNs']<=sample['uiNs']<=sample['publishedNs']<=sample['drawNs']<=sample['closedBootNs']<=sample['observedNs'],'Timestamp order differs')
            require(sample['closedBootNs']==sample['drawNs']+matrix['naturalObservationTailNs'],'Observation tail changed')
            events=sample['events'];require(len(events)==sample['eventCount'],'Event count differs')
            require(all(e.get('virtual') is True for e in events if e['stage']=='FRAME_DRAWN'),'Non-full-virtual attempt')
            for stage in ('OUTPUT_READ','EMULATOR_FED'):require(sum(e['bytes'] for e in events if e['stage']==stage)==len(raw),'Finite bytes incomplete')
            feeds=[e for e in events if e['stage']=='EMULATOR_FED'];require(feeds and feeds[-1]['revision']==sample['fedRevision'],'Final feed mismatch')
            require(sum(e['stage']=='UI_INPUT' for e in events)==1 and any(e['stage']=='FRAME_PUBLISHED' and e['ns']==sample['publishedNs'] and
                e['revision']==sample['fedRevision'] for e in events),'Input/publication mismatch')
        for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw')):
            require(0<len(c[key])<8192 and len(c[key])==len(c[rawkey]) and all(len(v)==10 and v[9]>=0 for v in c[key]) and
                all(len(v)==23 for v in c[rawkey]),'Raw observer inventory incomplete')
    return cases,cleanup

def parse(text,manifest,device,installed):
    cases,cleanup=validate_cases(text);classifications=[]
    for method,case in zip(METHODS,cases):
        raw=(ci.OUT/'latency-traces'/(method+'.pftrace')).read_bytes();tr=identity.parse_trace(raw,case['pid']);index=identity.FrameIndex(tr);queues=observers(case)
        rows=[dict(phase=w['phase'],index=w['index'],position=w['position'],condition=w['condition'],warmup=w['warmup'],
            **identity.classify_window(w,index,queues)) for w in case['samples']]
        classifications.append(dict(case=case['case'],rows=rows,traceSha256=tr['traceSha256'],traceBytes=tr['traceBytes'],traceFaults=tr['faults'],
            sequenceInitialMarkers=tr['sequenceInitialMarkers'],callbackDropped=[sum(x['dropped'] for x in q) for q in queues]))
    result=dict(cases=cases,cleanup=cleanup,classifications=classifications,device=device,installedApkHashes=installed,
        dataComplete=all(complete_window(r) for group in classifications for r in group['rows']))
    save(ci.OUT/'result.json',result)
    if not result['dataComplete']:ci.notice('warning','Incomplete P2 identity evidence','Keep all rows; finish only the frozen matrix, no imputation or replacement. Final integrity/acceptance will fail.')
    return result

def experiment():
    pair.require_ci();p=guard();configure();sha,run=ci.identity();plan=json.loads((OUT/'plan.json').read_bytes())
    require(plan['sourceSha']==sha and plan['runId']==run and plan['protocolSha256']==PROTOCOL_SHA256 and plan['patchSha256']==ci.digest(PATCH),'Wrong experiment plan')
    require(pair.verify_bundles(plan['manifests'])==plan['sharedAssetInventorySha256'],'Shared runtime resources differ')
    original_out=ci.OUT;original_reporter=ci.real_clock
    def operation():
        doc=dict(sourceSha=sha,runId=run,plan=plan,boot=pair.read_boot(),invocations=[])
        try:
            for n,order in enumerate(p['matrix']['armOrder']):
                for pos,arm in enumerate(order):
                    label=f'pair-{n}-{pos}-{arm}';ci.OUT=OUT/label;ci.OUT.mkdir(exist_ok=False)
                    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS=CLASS,METHODS=METHODS,report=parse)
                    require(pair.read_boot()==doc['boot'],'Guest reboot before invocation')
                    try:result=ci.run_verified_bundle(plan['manifests'][arm],'realClock',emit_evidence=False)
                    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
                        for name in ('instrumentation.log','pipeline-logcat.txt'):
                            path=ci.OUT/name
                            if path.exists():ci.notice('error','P2 identity '+label,ci.failure_excerpt(path))
                        raise
                    require(pair.read_boot()==doc['boot'],'Guest reboot during invocation')
                    # Raw cases stay in the per-invocation original, not duplicated into a huge aggregate.
                    full=ci.OUT/'result.json'
                    doc['invocations'].append(dict(pair=n,position=pos,arm=arm,
                        fullResultFile=str(full.relative_to(OUT)),fullResultSha256=ci.digest(full),fullResultBytes=full.stat().st_size,
                        result={k:v for k,v in result.items() if k!='cases'}))
                    save(OUT/'progress.json',doc)
            summary=aggregate(doc['invocations']);doc.update(summary)
            save(OUT/'results.json',doc)
            compact=dict(sourceSha=sha,runId=run,protocolSha256=PROTOCOL_SHA256,productionChanged=False,resultSha256=ci.digest(OUT/'results.json'),**summary)
            save(OUT/'summary.json',compact);real.wire.legacy.compact('P2 exact identity performance',json.dumps(compact,separators=(',',':')).encode())
            require(summary['dataComplete'],'Frozen P2 identity matrix incomplete; originals preserved, no resampling')
        finally:ci.OUT=original_out;ci.real_clock=original_reporter
    ci.emulator(operation=operation)

def complete_window(row):
    return row['identityDecidable'] and not row['noSubmittedAttempt'] and not row['noPresentedAttempt'] and all(
        isinstance(row.get(key),int) and not isinstance(row.get(key),bool) and row[key]>0 for key in METRICS)

def aggregate(invocations):
    """No deletion/imputation of missing values and no switching the primary after seeing results."""
    p=protocol();matrix=p['matrix'];gates=p['gates']
    expected=[(n,pos,arm) for n,order in enumerate(matrix['armOrder']) for pos,arm in enumerate(order)]
    require([(i['pair'],i['position'],i['arm']) for i in invocations]==expected,'Four complete launch pairs required')
    all_rows=[]
    for invocation in invocations:
        results=invocation['result']['classifications'];require([r['case'] for r in results]==matrix['cases'],'Four ordered case results required')
        for result in results:
            expected_windows=[(r,pos,cond) for r in range(matrix['rounds']) for pos,cond in enumerate(('refresh','return') if r%2==0 else ('return','refresh'))]
            require([(w['index'],w['position'],w['condition']) for w in result['rows']]==expected_windows,'Per-case fixed window inventory changed')
            require(all(w['warmup'] is (w['index'] in matrix['warmupRounds']) for w in result['rows']),'Warmup classification changed')
            all_rows.extend(result['rows'])
    require(len(all_rows)==matrix['windows'],'Full320window inventory required')
    summary=[]
    for case in matrix['cases']:
        for condition in matrix['conditions']:
            entry=dict(case=case,condition=condition,pairs=matrix['pairs'])
            for key in METRICS:
                arms={a:[] for a in ('legacy','candidate')}
                for n in range(matrix['pairs']):
                    for arm in arms:
                        result=next(i['result'] for i in invocations if i['pair']==n and i['arm']==arm)
                        group=next(r for r in result['classifications'] if r['case']==case)
                        selected=[r for r in group['rows'] if r['condition']==condition and not r['warmup']]
                        require(len(selected)==matrix['samplesPerConditionPerCasePerArmPerPair'],'Four formal windows required')
                        values=[r.get(key) for r in selected]
                        arms[arm].append(statistics.median(values) if all(isinstance(v,int) and not isinstance(v,bool) and v>0 for v in values) else None)
                ratios=[b/a if a is not None and b is not None else None for a,b in zip(arms['legacy'],arms['candidate'])]
                complete=all(r is not None for r in ratios)
                entry[key]=dict(arms=arms,pairedRatios=ratios,medianRatio=statistics.median(ratios) if complete else None,
                    wins=sum(r<1 for r in ratios if r is not None),complete=complete)
            summary.append(entry)
    data_complete=all(complete_window(r) for r in all_rows)
    failures=[]
    if not data_complete:failures.append('MATRIX_IDENTITY_OR_ENDPOINT_INCOMPLETE')
    for row in summary:
        label=row['case']+'/'+row['condition']
        for key,return_limit,refresh_limit,wins in (
            ('inputToBufferEvidenceNs',gates['returnPrimaryRatioMax'],gates['refreshPrimaryRatioMax'],gates['returnPrimaryWinningPairsMin']),
            ('inputToAttemptNs',gates['returnUiRatioMax'],gates['refreshUiRatioMax'],gates['returnUiWinningPairsMin']),
            ('inputToDisplayEndNs',gates['systemRatioMaxBothConditions'],gates['systemRatioMaxBothConditions'],0)):
            metric=row[key];limit=return_limit if row['condition']=='return' else refresh_limit
            if not metric['complete']:failures.append(label+':'+key+':MISSING')
            elif metric['medianRatio']>limit or (row['condition']=='return' and metric['wins']<wins):failures.append(label+':'+key+':GATE')
    return dict(summary=summary,windows=len(all_rows),warmups=sum(r['warmup'] for r in all_rows),dataComplete=data_complete,
        passesFrozenPerformanceScreen=not failures,gateFailures=failures,productionAdopted=False)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=('guard','prerequisite','build','experiment'));args=parser.parse_args()
    try:
        if args.action=='guard':print(json.dumps(guard()))
        elif args.action=='prerequisite':prerequisite()
        elif args.action=='build':build()
        else:experiment()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as error:
        ci.notice('error','P2 identity performance stopped',type(error).__name__+': '+str(error)[:1900]);raise SystemExit(1)
