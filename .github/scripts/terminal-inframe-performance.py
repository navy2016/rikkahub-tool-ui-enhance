"""Independent frozen in-frame publication performance: refresh gain plus sync/return guards."""
import argparse,collections,hashlib,importlib.util,json,os,shutil,statistics,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
PROTOCOL=ROOT/'benchmarks/terminal-inframe-performance/protocol.json'
PROTOCOL_SHA256='92a30fc4b284b046fd310bf28170fdf2fe1c5280b600cbf0efa311f67b811c0e'
s=importlib.util.spec_from_file_location('inframe_perf_identity',Path(__file__).with_name('terminal-frame-identity.py'))
identity=importlib.util.module_from_spec(s);s.loader.exec_module(identity)
require=identity.require
METRICS=('inputToBufferEvidenceNs','inputToAttemptNs','inputToDisplayEndNs')
FREEZE_SHA='d1e5474188267d707018677514cfe99e2dca0695'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalInFramePerformanceTest.kt'
METHODS=('inframePerfOneThousandHidden','inframePerfOneThousandShown','inframePerfTenThousandHidden','inframePerfTenThousandShown')
OUT=ROOT/'artifacts/terminal-inframe-performance';BUILD=ROOT/'artifacts/inframe-performance-build'
s=importlib.util.spec_from_file_location('inframe_perf_candidate',Path(__file__).with_name('terminal-inframe-publication.py'))
candidate=importlib.util.module_from_spec(s);s.loader.exec_module(candidate)
s=importlib.util.spec_from_file_location('inframe_perf_page',Path(__file__).with_name('terminal-inframe-page-regression.py'))
page=importlib.util.module_from_spec(s);s.loader.exec_module(page)
s=importlib.util.spec_from_file_location('inframe_perf_pair',Path(__file__).with_name('run-terminal-pipeline-paired.py'))
pair=importlib.util.module_from_spec(s);s.loader.exec_module(pair)
ci=pair.ci;real=ci.real_clock;old_sources=ci.sources
def protocol():
    raw=PROTOCOL.read_bytes();require(hashlib.sha256(raw).hexdigest()==PROTOCOL_SHA256,'Frozen in-frame performance protocol changed')
    return json.loads(raw)
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def save(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n')
def api(path):return json.loads(subprocess.check_output(['gh','api','repos/navy2016/rikkahub-tool-ui-enhance/'+path],text=True,timeout=30))
def guard():
    p=protocol();candidate.guard()
    require(git('show',FREEZE_SHA+':benchmarks/terminal-inframe-performance/protocol.json')==PROTOCOL.read_bytes(),'Performance protocol changed after freeze')
    require(git('show',p['identityParserSha']+':.github/scripts/terminal-frame-identity.py')==(ROOT/'.github/scripts/terminal-frame-identity.py').read_bytes(),'Identity parser changed')
    return p
def sources():
    result=old_sources()
    for name in (TEST,'.github/scripts/terminal-inframe-performance.py','.github/scripts/terminal-inframe-publication.py','.github/scripts/terminal-frame-identity.py',
        'benchmarks/terminal-inframe-performance/protocol.json','benchmarks/terminal-frame-identity/protocol.json',
        'benchmarks/terminal-inframe-publication-candidate/candidate.patch','benchmarks/terminal-inframe-publication-candidate/sources.json',
        'benchmarks/terminal-inframe-publication-candidate/TerminalInFramePublication.kt','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle'):
        result[name]=ci.digest(ROOT/name)
    if (ROOT/candidate.HELPER).exists():result[candidate.HELPER]=ci.digest(ROOT/candidate.HELPER)
    return result
def verify_sources(manifests):
    require(set(manifests)=={'legacy','candidate'},'Both source arms required')
    a,b=manifests['legacy']['sources'],manifests['candidate']['sources'];spec=candidate.spec()['files']
    require({k for k in set(a)|set(b) if a.get(k)!=b.get(k)}==set(spec),'Unexpected candidate source delta')
    for name,expected in spec.items():
        require(a.get(name)==expected['base'] and b.get(name)==expected['candidate'],'Candidate source hash differs:'+name)
        if expected['base'] is None:require(name not in a,'New helper unexpectedly present in baseline')
def configure():ci.sources=sources;pair.OUT=OUT;pair.BUILD=BUILD;pair.verify_sources=verify_sources
def download_original(label,q):
    run=api(f'actions/runs/{q["run"]}');artifact=api(f'actions/artifacts/{q["artifact"]}')
    require(run['head_sha']==q['sha'] and run['conclusion']==q['conclusion'],'Prerequisite run mismatch:'+label)
    require(artifact['name']==q['name'] and artifact['workflow_run']['id']==q['run'] and not artifact['expired'],'Prerequisite artifact mismatch:'+label)
    save(OUT/(label+'-run.json'),run);save(OUT/(label+'-artifact.json'),artifact)
    folder=OUT/(label+'-original')
    subprocess.run(['gh','run','download',str(q['run']),'--repo','navy2016/rikkahub-tool-ui-enhance','--name',q['name'],'--dir',str(folder)],check=True,timeout=180)
    require(not any(f.suffix.lower() in ('.apk','.aab') for f in folder.rglob('*')),'Unexpected prerequisite APK')
    files=list(folder.rglob(q['report']));require(len(files)==1,'Prerequisite report ambiguous')
    raw=files[0].read_bytes();require(hashlib.sha256(raw).hexdigest()==q['digest'],'Prerequisite report digest differs')
    data=json.loads(raw);require(data['sourceSha']==q['sha'] and str(data['runId'])==str(q['run']),'Prerequisite source/run differs')
    require(data['installedApkHashes']=={k:v['sha256'] for k,v in data['manifest']['apks'].items()},'Prerequisite installed hashes differ')
    return folder,data
def prerequisites():
    pair.require_ci();p=guard();q=p['prerequisites'];OUT.mkdir(parents=True,exist_ok=False)
    folder,data=download_original('mechanism',dict(run=q['mechanismRun'],sha=q['mechanismSourceSha'],conclusion=q['mechanismOriginalConclusion'],
        artifact=q['mechanismArtifact'],name=q['mechanismArtifactName'],report='terminal-inframe-publication/report.json',digest=q['mechanismReportSha256']))
    require(data['boundaryMechanismPassed'] is False,'Original mechanism failure relabeled')
    frozen_files=candidate.spec()['files']
    require(data['sourceReceipt']['candidateFiles']=={name:entry['candidate'] for name,entry in frozen_files.items()},'Mechanism candidate source differs')
    for name,text in candidate.candidate().items():
        matches=list(folder.rglob('candidate-source/'+name));require(len(matches)==1 and matches[0].read_text()==text,'Mechanism candidate original missing')
    callback=fallback=clipped=0;proof=[]
    for method,c in zip(candidate.METHODS,data['cases']):
        logs=list(folder.rglob(method+'.log'));require(len(logs)==1,'Mechanism method log missing')
    original_logs='\n'.join(next(folder.rglob(method+'.log')).read_text() for method in candidate.METHODS)
    checked,cleanup=candidate.validate_cases(original_logs);require(checked==data['cases'] and cleanup==data['cleanup'],'Mechanism original records differ')
    for method,c in zip(candidate.METHODS,data['cases']):
        traces=list(folder.rglob(method+'.pftrace'));require(len(traces)==1,'Mechanism trace ambiguous')
        tr=identity.parse_trace(traces[0].read_bytes(),c['pid']);require(not tr['faults'],'Prerequisite trace fault');index=identity.FrameIndex(tr)
        rows=[candidate.classify(w,index) for w in c['samples']]
        callback+=sum(r['callbackPublication'] for r in rows);fallback+=sum(r['fallbackPublication'] for r in rows);clipped+=sum(len(r['clippedPrefixCallbackEnds']) for r in rows)
        proof.append(dict(case=c['case'],traceSha256=tr['traceSha256'],rows=rows))
    import xml.etree.ElementTree as ET
    jvm=[]
    for file in folder.rglob('TEST-*.xml'):
        for c in ET.parse(file).getroot().iter('testcase'):
            require(not any(c.find(k) is not None for k in ('failure','error','skipped')),'Prerequisite JVM failure');jvm.append((c.get('classname'),c.get('name')))
    require((len(jvm),callback,fallback,clipped)==(q['expectedJvmMethods'],q['expectedCallbackWindows'],q['expectedFallbackWindows'],q['expectedClippedPrefixEnds']),'Mechanism prerequisite inventory differs')
    page_folder,page_data=download_original('page',dict(run=q['pageRun'],sha=q['pageSourceSha'],conclusion=q['pageConclusion'],
        artifact=q['pageArtifact'],name=q['pageArtifactName'],report='terminal-inframe-page-regression/report.json',digest=q['pageReportSha256']))
    logs=[]
    for method in page.protocol()['methods']:
        files=list(page_folder.rglob(method+'.log'));require(len(files)==1,'Page prerequisite method missing');logs.append(files[0].read_text())
    checked=page.page.validate_page('\n'.join(logs));require(all(page_data[k]==v for k,v in checked.items()),'Original page assertion mismatch')
    for name,entry in frozen_files.items():require(page_data['manifest']['sources'][name]==entry['candidate'],'Unprobed page candidate differs')
    save(OUT/'prerequisites.json',dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],noNewDeviceSamples=True,
        mechanismOriginalConclusion=q['mechanismOriginalConclusion'],mechanismRecovered=True,jvmMethods=len(jvm),callback=callback,fallback=fallback,
        clippedPrefixEnds=clipped,mechanism=proof,pageOriginalConclusion=q['pageConclusion'],pageCases=len(checked['cases'])))
    ci.notice('notice','Frozen in-frame prerequisites verified','49JVM/16callback/16fallback/2clipped-prefix plus2originalpages; no new prerequisite samples or historical relabeling.')
def build():
    pair.require_ci();guard();configure();pre=json.loads((OUT/'prerequisites.json').read_bytes())
    require(pre['sourceSha']==os.environ['GITHUB_SHA'] and pre['runId']==os.environ['GITHUB_RUN_ID'],'Read-only prerequisites not verified in this run')
    require(not git('diff','--name-only','HEAD').strip(),'Dirty build checkout');BUILD.mkdir(parents=True,exist_ok=False)
    files=candidate.candidate();base=(ROOT/candidate.PAGE).read_bytes();manifests={}
    try:
        for arm in ('candidate','legacy'):
            if arm=='candidate':
                for name,text in files.items():(ROOT/name).write_text(text)
            else:
                (ROOT/candidate.PAGE).write_bytes(base);(ROOT/candidate.HELPER).unlink()
            command=['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PterminalViewportTests=true','-PincludeX86_64AbiForTests=true']
            if arm=='candidate':command+=['-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','terminalMechanismDependencies']
            command+=[':app:assembleTerminaltest']+([':app:assembleTerminaltestAndroidTest'] if arm=='candidate' else [])
            subprocess.run(command+['--stacktrace'],cwd=ROOT,check=True,timeout=1800);ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes())
            for role,apk in manifest['apks'].items():
                dest=BUILD/('common-test.apk' if role=='test' else arm+'/target.apk');dest.parent.mkdir(parents=True,exist_ok=True)
                if role=='test' and arm=='legacy':require(ci.digest(dest)==apk['sha256'],'Shared driver differs')
                else:shutil.copyfile(ROOT/apk['file'],dest)
                apk['file']=str(dest.relative_to(ROOT))
            manifests[arm]=manifest
    finally:
        (ROOT/candidate.PAGE).write_bytes(base)
        if (ROOT/candidate.HELPER).exists():(ROOT/candidate.HELPER).unlink()
        guard();require(not git('diff','--name-only','HEAD').strip(),'Candidate checkout not restored')
    assets=pair.verify_bundles(manifests);sha,run=ci.identity()
    shutil.copyfile(ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json',OUT/'resolved-dependencies.json')
    save(OUT/'plan.json',dict(sourceSha=sha,runId=run,freezeSha=FREEZE_SHA,protocol=protocol(),protocolSha256=PROTOCOL_SHA256,
        productionChanged=False,manifests=manifests,sharedAssetInventorySha256=assets,candidateSpec=candidate.spec()))
    save(ci.OUT/'manifest.json',manifests['legacy'])
def observers(case):
    return [[dict(raw=raw,publicIntended=m[1],publicVsync=m[2],callbackBootNs=m[0],dropped=m[9]) for m,raw in zip(case[key],case[rawkey])]
        for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw'))]
def validate_cases(text):
    p=protocol();matrix=p['matrix'];old=real.CASES
    try:real.CASES=tuple(matrix['cases']);cases=real.case_records(text)
    finally:real.CASES=old
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP');require(len(cleanup)==4 and {x['case'] for x in cleanup}==set(matrix['cases']) and all(x['closed'] and x['threadStopped'] for x in cleanup),'Cleanup incomplete')
    for c in cases:
        _,size,ime=c['case'].split('-');size=int(size);shown=ime=='shown'
        require(c['passed'] and c['performanceMeasured'] is True and c['collectorDiagnosisOnly'] is False and c['protocolId']==p['id'],'Wrong performance scope')
        require(c['clock']=='CLOCK_BOOTTIME' and c['mode']=='fullVirtual' and c['historySize']==size and c['expectedIme']==shown,'Wrong native branch/clock')
        require('incrementLaunchCount:' in c['startupReceipt'] and all(x['configured'] and not x['init'] for x in c['preparation'][-2:]),'Configured first mount not proven')
        expected=[(n,pos,cond) for n,order in enumerate(matrix['conditionOrder']) for pos,cond in enumerate(order)]
        require([(w['index'],w['position'],w['condition']) for w in c['samples']]==expected,'Frozen15window case order differs')
        require(len(c['gates'])==16 and len({g['rows'] for g in c['gates']})==1 and all(g['columns']==80 and g['rows']>=24 and g['virtual'] and g['ime']==shown for g in c['gates']),'Grid/IME drift')
        for w in c['samples']:
            cond=w['condition'];kind='SYNC' if cond=='sync' else 'ECHO';marker=f'{kind}:{c["case"]}:{w["index"]:02d}:{w["position"]}'
            raw=(('\x1b[?1049l' if cond=='return' else '\x1b[?2026h' if cond=='sync' else '')+'\x1b[24;1H\x1b[2K'+marker+'\r'+('\x1b[?2026l' if cond=='sync' else '')).encode()
            require(w['outputBytes']==len(raw) and w['outputSha256']==hashlib.sha256(raw).hexdigest(),'Response bytes differ')
            require(w['warmup'] is (w['index'] in matrix['warmupRounds']) and w['clock']=='CLOCK_BOOTTIME','Clock/warmup changed')
            require(w['sameInitialHistory'] and w['visibleAfterMeasurement'] and w['virtual'],'History/visibility changed')
            require(w['fedRevision']==w['publishedRevision']==w['drawRevision'],'Final revision mismatch')
            require(w['beganNs']<=w['uiNs']<=w['publishedNs']<=w['drawNs']<=w['closedBootNs']<=w['observedNs'],'Timestamp order changed')
            require(w['drawNs']-w['uiNs']<=matrix['responseDeadlineNs'],'Response deadline exceeded')
            require(w['closedBootNs']==w['drawNs']+matrix['naturalObservationTailNs'],'Natural cutoff extended')
            events=w['events'];require(len(events)==w['eventCount'],'Event inventory mismatch')
            require(all(e.get('virtual') is True for e in events if e['stage']=='FRAME_DRAWN'),'Wrong attempt renderer')
            for stage in ('OUTPUT_READ','EMULATOR_FED'):require(sum(e['bytes'] for e in events if e['stage']==stage)==len(raw),'Incomplete finite bytes')
            feeds=[e for e in events if e['stage']=='EMULATOR_FED'];require(feeds and feeds[-1]['revision']==w['fedRevision'],'Final feed differs')
            require(sum(e['stage']=='UI_INPUT' for e in events)==1 and any(e['stage']=='FRAME_PUBLISHED' and e['ns']==w['publishedNs'] and e['revision']==w['fedRevision'] for e in events),'Input/publication proof missing')
        for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw')):
            require(0<len(c[key])<8192 and len(c[key])==len(c[rawkey]) and all(len(v)==10 and v[9]>=0 for v in c[key]) and all(len(v)==23 for v in c[rawkey]),'Observer inventory missing')
    return cases,cleanup
def parse(text,manifest,device,installed):
    cases,cleanup=validate_cases(text);groups=[]
    for method,c in zip(METHODS,cases):
        raw=(ci.OUT/'latency-traces'/(method+'.pftrace')).read_bytes();tr=identity.parse_trace(raw,c['pid']);index=identity.FrameIndex(tr);queues=observers(c)
        rows=[dict(phase=w['phase'],index=w['index'],position=w['position'],condition=w['condition'],warmup=w['warmup'],**identity.classify_window(w,index,queues)) for w in c['samples']]
        groups.append(dict(case=c['case'],rows=rows,traceSha256=tr['traceSha256'],traceBytes=tr['traceBytes'],traceFaults=tr['faults'],sequenceInitialMarkers=tr['sequenceInitialMarkers'],callbackDropped=[sum(x['dropped'] for x in q) for q in queues]))
    result=dict(cases=cases,cleanup=cleanup,classifications=groups,device=device,installedApkHashes=installed,dataComplete=all(complete_window(r) for g in groups for r in g['rows']))
    save(ci.OUT/'result.json',result)
    if not result['dataComplete']:ci.notice('warning','In-frame missing identity evidence','Originals and nulls retained; finish only frozen matrix, final adoption fails.')
    return result
def experiment():
    pair.require_ci();p=guard();configure();sha,run=ci.identity();plan=json.loads((OUT/'plan.json').read_bytes())
    require(plan['sourceSha']==sha and plan['runId']==run and plan['protocolSha256']==PROTOCOL_SHA256,'Wrong frozen run plan')
    require(pair.verify_bundles(plan['manifests'])==plan['sharedAssetInventorySha256'],'Shared resources changed')
    original_out=ci.OUT;original_report=ci.real_clock
    def operation():
        doc=dict(sourceSha=sha,runId=run,plan=plan,boot=pair.read_boot(),invocations=[])
        try:
            for n,order in enumerate(p['matrix']['armOrder']):
                for pos,arm in enumerate(order):
                    label=f'pair-{n}-{pos}-{arm}';ci.OUT=OUT/label;ci.OUT.mkdir(exist_ok=False)
                    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS='me.rerere.rikkahub.pipeline.TerminalInFramePerformanceTest',METHODS=METHODS,report=parse)
                    require(pair.read_boot()==doc['boot'],'Guest rebooted')
                    try:result=ci.run_verified_bundle(plan['manifests'][arm],'realClock',emit_evidence=False)
                    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
                        for name in ('instrumentation.log','pipeline-logcat.txt'):
                            file=ci.OUT/name
                            if file.exists():ci.notice('error','In-frame performance '+label,ci.failure_excerpt(file))
                        raise
                    require(pair.read_boot()==doc['boot'],'Guest rebooted during invocation')
                    file=ci.OUT/'result.json';doc['invocations'].append(dict(pair=n,position=pos,arm=arm,fullResultFile=str(file.relative_to(OUT)),
                        fullResultSha256=ci.digest(file),fullResultBytes=file.stat().st_size,result={k:v for k,v in result.items() if k!='cases'}));save(OUT/'progress.json',doc)
            result=aggregate(doc['invocations']);doc.update(result);save(OUT/'results.json',doc)
            compact=dict(sourceSha=sha,runId=run,protocolSha256=PROTOCOL_SHA256,productionChanged=False,resultSha256=ci.digest(OUT/'results.json'),**result)
            save(OUT/'summary.json',compact);real.wire.legacy.compact('In-frame publication performance',json.dumps(compact,separators=(',',':')).encode())
            require(result['dataComplete'],'Complete in-frame identity evidence missing; no replacement sampling')
        finally:ci.OUT=original_out;ci.real_clock=original_report
    ci.emulator(operation=operation)
def complete_window(row):
    return row['identityDecidable'] and not row['noSubmittedAttempt'] and not row['noPresentedAttempt'] and all(
        type(row.get(key)) is int and row[key]>0 for key in METRICS)
def aggregate(invocations):
    p=protocol();matrix=p['matrix'];gates=p['gates']
    expected=[(n,pos,arm) for n,order in enumerate(matrix['armOrder']) for pos,arm in enumerate(order)]
    require([(x['pair'],x['position'],x['arm']) for x in invocations]==expected,'Four complete ordered launch pairs required')
    rows=[]
    for inv in invocations:
        groups=inv['result']['classifications'];require([x['case'] for x in groups]==matrix['cases'],'All four ordered cases required')
        for group in groups:
            expected=[(n,pos,cond) for n,order in enumerate(matrix['conditionOrder']) for pos,cond in enumerate(order)]
            require([(x['index'],x['position'],x['condition']) for x in group['rows']]==expected,'Per-case three-condition inventory changed')
            require(all(r['warmup'] is (r['index'] in matrix['warmupRounds']) for r in group['rows']),'Warmup labels changed')
            rows.extend(group['rows'])
    require(len(rows)==matrix['windows'],'Fixed480window matrix incomplete')
    summary=[]
    for case in matrix['cases']:
        for condition in matrix['conditions']:
            item=dict(case=case,condition=condition,pairs=len(matrix['armOrder']))
            for key in METRICS:
                arms={arm:[] for arm in ('legacy','candidate')}
                for pair in range(len(matrix['armOrder'])):
                    for arm in arms:
                        result=next(x['result'] for x in invocations if x['pair']==pair and x['arm']==arm)
                        group=next(g for g in result['classifications'] if g['case']==case)
                        values=[r.get(key) for r in group['rows'] if r['condition']==condition and not r['warmup']]
                        require(len(values)==matrix['formalPerConditionPerCasePerArmPerPair'],'Four formal samples required')
                        arms[arm].append(statistics.median(values) if all(type(x) is int and x>0 for x in values) else None)
                ratios=[b/a if a is not None and b is not None else None for a,b in zip(arms['legacy'],arms['candidate'])]
                complete=all(r is not None for r in ratios)
                item[key]=dict(arms=arms,pairedRatios=ratios,medianRatio=statistics.median(ratios) if complete else None,
                    complete=complete,wins=sum(r<1 for r in ratios if r is not None))
            summary.append(item)
    data_complete=all(complete_window(r) for r in rows);failures=[]
    if not data_complete:failures.append('IDENTITY_OR_ENDPOINT_INCOMPLETE')
    for item in summary:
        label=item['case']+'/'+item['condition']
        for key,refresh,other,wins in (
            ('inputToBufferEvidenceNs',gates['refreshPrimaryRatioMax'],gates['syncAndReturnPrimaryRatioMax'],gates['refreshPrimaryWinningPairsMin']),
            ('inputToAttemptNs',gates['refreshUiRatioMax'],gates['syncAndReturnUiRatioMax'],gates['refreshUiWinningPairsMin']),
            ('inputToDisplayEndNs',gates['systemRatioMaxAllConditions'],gates['systemRatioMaxAllConditions'],0)):
            value=item[key];limit=refresh if item['condition']=='refresh' else other
            if not value['complete']:failures.append(label+':'+key+':MISSING')
            elif value['medianRatio']>limit or (item['condition']=='refresh' and value['wins']<wins):failures.append(label+':'+key+':GATE')
    return dict(summary=summary,windows=len(rows),warmups=sum(r['warmup'] for r in rows),dataComplete=data_complete,
        passesFrozenPerformanceScreen=not failures,gateFailures=failures,productionAdopted=False)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','prerequisites','build','experiment'));a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        elif a.action=='prerequisites':prerequisites()
        elif a.action=='build':build()
        else:experiment()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','In-frame performance stopped',type(e).__name__+': '+str(e)[:1900]);raise SystemExit(1)
