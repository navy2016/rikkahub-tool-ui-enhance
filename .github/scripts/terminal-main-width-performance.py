"""Frozen306-versus-A1 end-to-end comparison. No historical guard weakening or automatic adoption."""
import argparse,base64,collections,hashlib,importlib.util,json,math,os,re,shutil,statistics,subprocess,types,zipfile,zlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'benchmarks/terminal-main-width-performance';PROTOCOL=BASE/'protocol.json'
PROTOCOL_SHA256='3a941a884f7dbbd5261a2feb8c13c417ef41e0bcfe2bd235b7d2200aecae32eb'
FREEZE_SHA='f543171cda98411a9f65c8227f538a3307339472'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalMainWidthPerformanceTest.kt'
CLASS='me.rerere.rikkahub.pipeline.TerminalMainWidthPerformanceTest'
METHODS=('mainWidthPerfOneThousandHidden','mainWidthPerfOneThousandShown','mainWidthPerfTenThousandHidden','mainWidthPerfTenThousandShown')
METRICS=('inputToBufferEvidenceNs','inputToAttemptNs','inputToDisplayEndNs')
OUT=ROOT/'artifacts/terminal-main-width-performance';BUILD=ROOT/'artifacts/main-width-performance-build'
BUILD_CONTRACT=BASE/'build-contract.json'
BUILD_CONTRACT_SHA256='48380bd3f7e51e5fa589d13c5f76d8c8b0855cc8317a94b90ae58bc47888cc67'
def load(name,filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
identity=load('a1_perf_identity','terminal-frame-identity.py')
candidate=load('a1_perf_candidate','terminal-main-width-lifecycle.py')
pair=load('a1_perf_pair','run-terminal-pipeline-paired.py')
ci=pair.ci;real=ci.real_clock;old_sources=ci.sources;old_inventory=pair.inventory;require=identity.require
def sha(raw):return hashlib.sha256(raw).hexdigest()
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def api(path):return json.loads(subprocess.check_output(['gh','api','repos/navy2016/rikkahub-tool-ui-enhance/'+path],text=True,timeout=40))
def protocol():
    raw=PROTOCOL.read_bytes();require(sha(raw)==PROTOCOL_SHA256,'A1 performance protocol changed');return json.loads(raw)
def build_contract():
    raw=BUILD_CONTRACT.read_bytes();require(sha(raw)==BUILD_CONTRACT_SHA256,'A1 typed profile build contract changed')
    value=json.loads(raw);require(value['timingProtocolSha256']==PROTOCOL_SHA256 and value['beforeAnyDeviceSampling'],'Wrong timing/build contract binding')
    require(ci.digest(ROOT/'.github/scripts/terminal-paired-profile-audit.py')==value['auditSourceSha256'],'Typed profile audit implementation changed')
    return value
def candidate_files():return candidate.spec()[0]['files']
def guard():
    p=protocol();spec,_=candidate.guard()
    require(candidate.IDENTITY_SHA==p['candidateIdentitySha256'] and spec['protocolSha256']==p['candidateProtocolSha256'],'Different A1 mechanism identity')
    for name in ('identity.json','protocol.json','candidate.patch','TerminalMainWidthLifecycleTest.kt','TerminalMainWidthLifecycleViewTest.kt'):
        relative='benchmarks/terminal-main-width-lifecycle/'+name
        require(git('show',p['candidateFreezeSha']+':'+relative)==(ROOT/relative).read_bytes(),'Frozen A1 evidence source changed:'+name)
    for item in spec['files'].values():require(git('show',p['candidateFreezeSha']+':'+item['asset'])==(ROOT/item['asset']).read_bytes(),'A1 frozen candidate asset changed')
    for name in (str(PROTOCOL.relative_to(ROOT)),TEST):
        require(git('show',FREEZE_SHA+':'+name)==(ROOT/name).read_bytes(),'Performance driver/protocol changed after freeze')
    require(ci.digest(ROOT/TEST)==p['driverSha256'],'Wrong shared driver')
    require(git('show',p['identityParserSha']+':.github/scripts/terminal-frame-identity.py')==(ROOT/'.github/scripts/terminal-frame-identity.py').read_bytes(),'Qualified identity parser changed')
    require(identity.PROTOCOL_SHA256==p['identityProtocolSha256'],'Wrong identity protocol')
    text=(ROOT/TEST).read_text()
    for forbidden in ('createAndroidComposeRule','mainClock','waitForIdle','INFRAME_CALLBACK','prepareFullVirtualHistory'):
        require(forbidden not in text,'Driver contains unapproved clock/probe:'+forbidden)
    require(len(spec['files'])==5 and 'for(round in 0..20)' in text and 'clock=SystemClock::elapsedRealtimeNanos' in text,'Wrong matrix/clock')
    return p
def sources():
    result=old_sources()
    paths=[TEST,'.github/scripts/terminal-main-width-performance.py','.github/scripts/terminal-main-width-lifecycle.py',
        '.github/scripts/terminal-frame-identity.py','.github/scripts/terminal-paired-profile-audit.py',
        '.github/scripts/audit-release-profiles.py','benchmarks/terminal-frame-identity/protocol.json',
        'benchmarks/terminal-main-width-performance/protocol.json','benchmarks/terminal-main-width-performance/build-contract.json',
        'benchmarks/terminal-main-width-lifecycle/identity.json',
        'benchmarks/terminal-main-width-lifecycle/protocol.json','benchmarks/terminal-main-width-lifecycle/candidate.patch',
        'benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle',
        'app/src/main/java/me/rerere/rikkahub/data/container/TerminalInFramePublication.kt']
    paths+=list(candidate_files())+[item['asset'] for item in candidate_files().values()]
    for name in paths:result[name]=ci.digest(ROOT/name)
    return result
def verify_sources(manifests):
    require(set(manifests)=={'legacy','candidate'},'Missing306/A1 target arm')
    a,b=manifests['legacy']['sources'],manifests['candidate']['sources'];spec=candidate_files()
    require(set(a)==set(b) and {k for k in a if a[k]!=b[k]}==set(spec),'Source difference is not exactly the five A1 files')
    for name,item in spec.items():require(a[name]==item['base'] and b[name]==item['candidate'],'A1 source bytes differ:'+name)
    require(a[TEST]==b[TEST]==protocol()['driverSha256'],'Different frozen common driver source')
def configure():ci.sources=sources;pair.OUT=OUT;pair.BUILD=BUILD;pair.verify_sources=verify_sources;pair.inventory=old_inventory
def preserve_bundle_diagnostics(manifests):
    """Hash-only exact package differences before the strict existing guard; never relax it."""
    inventories={}
    for arm,manifest in manifests.items():
        save(OUT/('built-'+arm+'-manifest.json'),manifest)
        path=ROOT/manifest['apks']['target']['file']
        with zipfile.ZipFile(path) as archive:
            require(len(archive.namelist())==len(set(archive.namelist())),'Duplicate target archive members')
            entries={}
            for entry in archive.infolist():
                if entry.is_dir():continue
                name=entry.filename
                if name.startswith(('assets/','lib/','res/','static/')) or name in ('AndroidManifest.xml','resources.arsc'):
                    raw=archive.read(entry);entries[name]=dict(bytes=len(raw),sha256=sha(raw))
            inventories[arm]=entries;save(OUT/('built-'+arm+'-assets.json'),entries)
    a,b=inventories['legacy'],inventories['candidate'];changed=[]
    for name in sorted(set(a)|set(b)):
        if a.get(name)!=b.get(name):changed.append(dict(name=name,legacy=a.get(name),candidate=b.get(name),
            strictGuardMember=name.startswith(('assets/','lib/','res/')) or name in ('AndroidManifest.xml','resources.arsc')))
    report=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],originalFailureRun=41278159851,
        originalFailedApksRetained=False,theseAreNewBuildsNotOriginalApks=True,changed=changed,
        strictGuardDifferences=sum(x['strictGuardMember'] for x in changed),candidateUnchanged=True,deviceSamples=0)
    save(OUT/'bundle-differences.json',report)
    for chunk in range(0,len(changed),4):
        ci.notice('notice','A1 exact bundle difference',json.dumps(changed[chunk:chunk+4],separators=(',',':')))
    # Diagnostic only: verify generated profile identity before the SAME strict byte-equality
    # gate rejects it. This does not grant a profile exception or authorize measurement.
    profile_audit=load('a1_paired_profile_audit','terminal-paired-profile-audit.py');profiles={}
    for arm,manifest in manifests.items():
        target=manifest['apks']['target']
        profiles[arm],symbols=profile_audit.audit(ROOT/target['file'],target['sha256'],manifest['sourceSha'])
        save(OUT/('profile-'+arm+'.json'),profiles[arm]);save(OUT/('profile-'+arm+'-symbols.json'),symbols)
    result=profile_audit.compare(profiles['legacy'],profiles['candidate'])
    save(OUT/'profile-comparison.json',dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],
        diagnosticOnly=True,assetsEqualExceptionGranted=False,comparison=result,profiles=profiles))
    ci.notice('notice','A1 read-only profile consistency',json.dumps(result,separators=(',',':')))
    return report
def verify_typed_bundles(manifests):
    """Retain the original source/signing/files gate; explicitly type the profile inventory.
    Every target byte hash is revalidated, with own-DEX+complete symbolic profile equivalence.
    No APK rewriting, no mutation of the historical verifier module on disk.
    """
    contract=build_contract();audit=load('a1_typed_profile_gate','terminal-paired-profile-audit.py');profiles={};assets={}
    for arm,manifest in manifests.items():
        target=manifest['apks']['target'];path=ROOT/target['file']
        profiles[arm],_=audit.audit(path,target['sha256'],manifest['sourceSha'])
        with zipfile.ZipFile(path) as archive:
            assets[arm]={name:dict(bytes=archive.getinfo(name).file_size,sha256=sha(archive.read(name))) for name in archive.namelist()
                if not name.endswith('/') and (name.startswith(('assets/','lib/','res/','static/')) or name in ('AndroidManifest.xml','resources.arsc'))}
    qualification=audit.qualify(profiles,assets)
    slots={(ROOT/m['apks']['target']['file']).resolve():arm for arm,m in manifests.items()}
    def typed_inventory(path):
        arm=slots.get(path.resolve());require(arm is not None,'Typed inventory path outside validated target slots')
        require(ci.digest(path)==manifests[arm]['apks']['target']['sha256'],'Target changed after profile audit')
        value=dict(assets[arm]);value['assets/dexopt/baseline.prof']=dict(kind='own-dex-validated-prof010-symbolic-v1',symbolicContentSha256=profiles[arm]['symbolicContentSha256'])
        return value
    previous=pair.inventory
    try:
        pair.inventory=typed_inventory
        inventory_hash=pair.verify_bundles(manifests)
    finally:pair.inventory=previous
    report=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],buildContractSha256=BUILD_CONTRACT_SHA256,
        qualification=qualification,profiles=profiles,sharedAssetInventorySha256=inventory_hash,
        originalByteEqualityRunsRemainFailure=[41278159851,41279081126,41280242644])
    save(OUT/'typed-build-qualification.json',report)
    ci.notice('notice','A1 typed package qualification',json.dumps(dict(buildContractSha256=BUILD_CONTRACT_SHA256,
        rawAssetsByteIdentical=qualification['rawAssetsByteIdentical'],nonProfileAssetsByteIdentical=True,
        sameSymbolicMethodsFlagsAndClasses=True,bothMatchOwnDex=True,notAnApkRewrite=True)))
    return inventory_hash
def prerequisites():
    pair.require_ci();p=guard();q=p['prerequisites'];OUT.mkdir(parents=True,exist_ok=False)
    run=api(f'actions/runs/{q["run"]}');jobs=api(f'actions/runs/{q["run"]}/jobs?per_page=100')['jobs']
    require(run['head_sha']==q['sourceSha'] and run['conclusion']==q['conclusion'],'A1 mechanism run differs')
    executed=[x for x in jobs if x['conclusion']!='skipped'];require(len(executed)==2 and all(x['conclusion']=='success' for x in executed),'A1 mechanism jobs missing')
    save(OUT/'mechanism-run.json',run);save(OUT/'mechanism-jobs.json',jobs);reports={}
    _,mechanism=candidate.spec()
    for arm,inventory in (('jvm',mechanism['unitInventory']+[mechanism['candidateUnit']]),('component',mechanism['deviceInventory'])):
        artifact=api(f'actions/artifacts/{q[arm+"Artifact"]}');save(OUT/(arm+'-artifact.json'),artifact)
        require(artifact['name']==q[arm+'ArtifactName'] and artifact['workflow_run']['id']==q['run'] and
            artifact['workflow_run']['head_sha']==q['sourceSha'] and not artifact['expired'],'Wrong A1 mechanism artifact')
        folder=OUT/(arm+'-original')
        subprocess.run(['gh','run','download',str(q['run']),'--repo','navy2016/rikkahub-tool-ui-enhance','--name',artifact['name'],'--dir',str(folder)],check=True,timeout=180)
        require(not any(x.suffix.lower() in ('.apk','.aab') for x in folder.rglob('*')),'Unexpected mechanism APK')
        found=list(folder.rglob('report.json'));require(len(found)==1,'Ambiguous mechanism report')
        raw=found[0].read_bytes();require(sha(raw)==q[arm+'ReportSha256'],'Original mechanism report hash differs')
        report=json.loads(raw);require(report['sourceSha']==q['sourceSha'] and int(report['runId'])==q['run'] and report['passed'],'Mechanism report did not pass')
        require(report['identitySha256']==p['candidateIdentitySha256'] and report['candidateHashes']=={n:x['candidate'] for n,x in candidate_files().items()},'Mechanism candidate changed')
        rows=candidate.read_junit(folder.rglob('*TEST-*.xml'));candidate.verify_inventory(rows,inventory)
        require({(x['classname'],x['name']) for x in rows}=={(x['classname'],x['name']) for x in report['cases']} and len(rows)==report['count'],'Mechanism XML/report disagree')
        if arm=='component':
            paths=list(folder.rglob('lifecycle-logcat.txt'));require(len(paths)==1,'Missing mechanism lifecycle log')
            require(candidate.scenes(paths[0].read_text(),mechanism)==report['scenes'] and len(report['scenes'])==q['expectedLifecycleScenes'],'Mechanism lifecycle log differs')
        reports[arm]=dict(count=len(rows),reportSha256=sha(raw))
    require(reports['jvm']['count']==533 and reports['component']['count']==20,'Mechanism inventory differs')
    save(OUT/'prerequisites.json',dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],originalSource=q['sourceSha'],originalRun=q['run'],noNewDeviceSamples=True,reports=reports))
    ci.notice('notice','A1 prerequisite verified','Same originals533JVM/20component/37scenes independently rechecked before any new build.')
def build(typed_assets=False):
    pair.require_ci();p=guard();configure();pre=json.loads((OUT/'prerequisites.json').read_bytes())
    require(pre['sourceSha']==os.environ['GITHUB_SHA'] and pre['runId']==os.environ['GITHUB_RUN_ID'],'Prerequisites not verified for this run')
    require(not git('diff','--name-only','HEAD').strip(),'Dirty build checkout');BUILD.mkdir(parents=True,exist_ok=False)
    spec=candidate_files();before={name:(ROOT/name).read_bytes() for name in spec};manifests={}
    try:
        for arm in ('candidate','legacy'):
            for name,item in spec.items():(ROOT/name).write_bytes((ROOT/item['asset']).read_bytes() if arm=='candidate' else before[name])
            require({n for n in git('diff','--name-only','HEAD').decode().splitlines()}==(set(spec) if arm=='candidate' else set()),'Unexpected build overlay')
            command=['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PterminalViewportTests=true','-PincludeX86_64AbiForTests=true']
            if arm=='candidate':command+=['-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','terminalMechanismDependencies']
            command+=[':app:assembleTerminaltest']+([':app:assembleTerminaltestAndroidTest'] if arm=='candidate' else [])
            subprocess.run(command+['--stacktrace'],cwd=ROOT,check=True,timeout=1800);ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes())
            for role,apk in manifest['apks'].items():
                dest=BUILD/('common-test.apk' if role=='test' else arm+'/target.apk');dest.parent.mkdir(parents=True,exist_ok=True)
                if role=='test' and arm=='legacy':require(ci.digest(dest)==apk['sha256'],'Common driver binary differs')
                else:shutil.copyfile(ROOT/apk['file'],dest)
                apk['file']=str(dest.relative_to(ROOT))
            manifests[arm]=manifest
    finally:
        for name,raw in before.items():(ROOT/name).write_bytes(raw)
        guard();require(not git('diff','--name-only','HEAD').strip(),'A1 build overlay leaked into production')
    preserve_bundle_diagnostics(manifests)
    assets=verify_typed_bundles(manifests) if typed_assets else pair.verify_bundles(manifests);source,run=ci.identity()
    shutil.copyfile(ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json',OUT/'resolved-dependencies.json')
    save(OUT/'plan.json',dict(sourceSha=source,runId=run,freezeSha=FREEZE_SHA,protocol=p,protocolSha256=PROTOCOL_SHA256,
        productionChanged=False,manifests=manifests,sharedAssetInventorySha256=assets,candidateSpec=candidate.spec()[0],
        buildContractSha256=BUILD_CONTRACT_SHA256 if typed_assets else None))
    save(ci.OUT/'manifest.json',manifests['legacy'])
def case_records(text):
    p=protocol();bounds=p['serialization'];groups={}
    for row in real.records(text,'REAL_CLOCK_PART'):
        require(row['case'] in p['matrix']['cases'] and type(row['part']) is int and type(row['parts']) is int and
            1<=row['part']<=row['parts']<=bounds['casePartsMax'] and len(row['data'])<=bounds['casePartChars'],'Invalid A1 case fragment')
        entry=groups.setdefault(row['case'],dict(parts=row['parts'],sha256=row['sha256'],data={}))
        require(entry['parts']==row['parts'] and entry['sha256']==row['sha256'] and row['part'] not in entry['data'],'Mixed/duplicate A1 case fragment')
        entry['data'][row['part']]=row['data']
    require(set(groups)==set(p['matrix']['cases']),'Missing original A1 cases');result=[]
    for name in p['matrix']['cases']:
        entry=groups[name];require(set(entry['data'])==set(range(1,entry['parts']+1)),'Incomplete A1 case fragments')
        packed=base64.b64decode(''.join(entry['data'][n] for n in sorted(entry['data'])),validate=True)
        stream=zlib.decompressobj();raw=stream.decompress(packed,bounds['caseRawBytesMax']+1)
        require(stream.eof and not stream.unused_data and not stream.unconsumed_tail and len(raw)<=bounds['caseRawBytesMax'] and sha(raw)==entry['sha256'],'A1 case content size/hash differs')
        value=json.loads(raw);require(value['case']==name,'Mislabeled A1 case');result.append(value)
    return result
def observers(case):
    return [[dict(raw=raw,publicIntended=m[1],publicVsync=m[2],callbackBootNs=m[0],dropped=m[9]) for m,raw in zip(case[key],case[rawkey])]
        for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw'))]
def validate_cases(text):
    p=protocol();matrix=p['matrix'];cases=case_records(text);cleanup=real.records(text,'REAL_CLOCK_CLEANUP')
    require(len(cleanup)==4 and {x['case'] for x in cleanup}==set(matrix['cases']) and all(x['closed'] and x['threadStopped'] for x in cleanup),'A1 cleanup incomplete')
    for c in cases:
        _,size,ime=c['case'].split('-');size=int(size);shown=ime=='shown'
        require(c['passed'] and c['performanceMeasured'] is True and c['collectorDiagnosisOnly'] is False and c['protocolId']==p['id'],'Wrong performance scope')
        require(c['clock']=='CLOCK_BOOTTIME' and c['mode']=='fullVirtual' and c['historySize']==size and c['expectedIme']==shown,'Wrong native branch/clock')
        require('incrementLaunchCount:' in c['startupReceipt'] and [x['stage'] for x in c['preparation']]==['before-write','after-write','persisted-and-flow-confirmed','before-mount'] and
            all(x['configured'] and not x['init'] for x in c['preparation'][-2:]),'Configured first mount not proven')
        expected=[(n,pos,cond) for n,order in enumerate(matrix['conditionOrder']) for pos,cond in enumerate(order)]
        require([(w['index'],w['position'],w['condition']) for w in c['samples']]==expected,'Frozen63-window case order differs')
        require(len(c['gates'])==len(expected)+1 and len({g['rows'] for g in c['gates']})==1 and all(g['columns']==80 and g['rows']>=24 and g['virtual'] and g['ime']==shown for g in c['gates']),'Grid/IME drift')
        for w in c['samples']:
            cond=w['condition'];kind='SYNC' if cond=='sync' else 'ECHO';marker=f'{kind}:{c["case"]}:{w["index"]:02d}:{w["position"]}'
            raw=(('\x1b[?1049l' if cond=='return' else '\x1b[?2026h' if cond=='sync' else '')+'\x1b[24;1H\x1b[2K'+marker+'\r'+('\x1b[?2026l' if cond=='sync' else '')).encode()
            require(w['outputBytes']==len(raw) and w['outputSha256']==sha(raw),'Finite response bytes differ')
            require(w['warmup'] is (w['index'] in matrix['warmupRounds']) and w['clock']=='CLOCK_BOOTTIME','Clock/warmup changed')
            require(w['sameInitialHistory'] and w['visibleAfterMeasurement'] and w['virtual'],'History/visibility changed')
            require(w['fedRevision']==w['publishedRevision']==w['drawRevision'],'Final revision mismatch')
            require(w['beganNs']<=w['uiNs']<=w['publishedNs']<=w['drawNs']<=w['closedBootNs']<=w['observedNs'],'Timestamp order changed')
            require(w['drawNs']-w['uiNs']<=matrix['responseDeadlineNs'] and w['closedBootNs']==w['drawNs']+matrix['naturalObservationTailNs'],'Response deadline or fixed tail changed')
            events=w['events'];require(len(events)==w['eventCount'],'Event inventory mismatch')
            require(all(e.get('virtual') is True for e in events if e['stage']=='FRAME_DRAWN'),'Wrong attempt renderer')
            for stage in ('OUTPUT_READ','EMULATOR_FED'):require(sum(e['bytes'] for e in events if e['stage']==stage)==len(raw),'Incomplete finite bytes')
            feeds=[e for e in events if e['stage']=='EMULATOR_FED'];require(feeds and feeds[-1]['revision']==w['fedRevision'],'Final feed differs')
            require(sum(e['stage']=='UI_INPUT' for e in events)==1 and any(e['stage']=='FRAME_PUBLISHED' and e['ns']==w['publishedNs'] and e['revision']==w['fedRevision'] for e in events),'Input/publication proof missing')
        for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw')):
            require(0<len(c[key])<p['serialization']['observerRowsMax'] and len(c[key])==len(c[rawkey]) and all(len(v)==10 and v[9]>=0 for v in c[key]) and all(len(v)==23 for v in c[rawkey]),'Observer inventory missing')
    return cases,cleanup
def interval_union(spans,start,end):
    merged=[]
    for a,b in sorted((max(start,x['startNs']),min(end,x['endNs'])) for x in spans if x['startNs']<end and x['endNs']>start):
        if merged and a<=merged[-1][1]:merged[-1]=(merged[-1][0],max(b,merged[-1][1]))
        else:merged.append((a,b))
    return sum(b-a for a,b in merged)
def diagnostics(window,trace):
    lo,hi=window['uiNs'],window['closedBootNs']
    main=[s for s in trace['spans'] if s['tid']==trace['pid'] and identity.UI_RE.fullmatch(s['name']) and s['startNs']<hi and s['endNs']>lo]
    return dict(mainFrameLongWallSpans=sum(s['endNs']-s['startNs']>=protocol()['diagnostics']['mainFrameLongWallThresholdNs'] for s in main),
        longestMainFrameWallNs=max((s['endNs']-s['startNs'] for s in main),default=0),
        widthWallUnionNs=interval_union([s for s in trace['spans'] if s['name']=='Terminal.productionWidth'],lo,hi),notCpuTime=True)
def parse(text,manifest,device,installed):
    cases,cleanup=validate_cases(text);groups=[]
    for method,c in zip(METHODS,cases):
        raw=(ci.OUT/'latency-traces'/(method+'.pftrace')).read_bytes();tr=identity.parse_trace(raw,c['pid']);index=identity.FrameIndex(tr);queues=observers(c)
        rows=[dict(phase=w['phase'],index=w['index'],position=w['position'],condition=w['condition'],warmup=w['warmup'],
            diagnostics=diagnostics(w,tr),**identity.classify_window(w,index,queues)) for w in c['samples']]
        groups.append(dict(case=c['case'],rows=rows,traceSha256=tr['traceSha256'],traceBytes=tr['traceBytes'],traceFaults=tr['faults'],sequenceInitialMarkers=tr['sequenceInitialMarkers'],callbackDropped=[sum(x['dropped'] for x in q) for q in queues]))
    result=dict(cases=cases,cleanup=cleanup,classifications=groups,device=device,installedApkHashes=installed,dataComplete=all(complete_window(r) for g in groups for r in g['rows']))
    save(ci.OUT/'result.json',result)
    if not result['dataComplete']:ci.notice('warning','A1 incomplete frame identity','Nulls and every original retained; finish frozen matrix only,no replacement samples or adoption.')
    return result
def complete_window(row):
    return row['identityDecidable'] and not row['noSubmittedAttempt'] and not row['noPresentedAttempt'] and all(type(row.get(k)) is int and row[k]>0 for k in METRICS)
def statistics_for(values):
    if not all(type(v) is int and v>0 for v in values):return dict(median=None,p95=None,maximum=None)
    ordered=sorted(values);return dict(median=statistics.median(ordered),p95=ordered[math.ceil(len(values)*.95)-1],maximum=ordered[-1])
def ratio_summary(a,b):
    ratios=[y/x if x is not None and y is not None else None for x,y in zip(a,b)];complete=all(x is not None for x in ratios)
    return dict(arms=dict(legacy=a,candidate=b),pairedRatios=ratios,medianRatio=statistics.median(ratios) if complete else None,complete=complete,wins=sum(x<1 for x in ratios if x is not None))
def aggregate(invocations):
    p=protocol();matrix=p['matrix'];g=p['gates'];ordered=[(n,pos,arm) for n,order in enumerate(matrix['armOrder']) for pos,arm in enumerate(order)]
    require([(x['pair'],x['position'],x['arm']) for x in invocations]==ordered,'Four complete ordered306/A1 pairs required');rows=[]
    expected=[(n,pos,cond) for n,order in enumerate(matrix['conditionOrder']) for pos,cond in enumerate(order)]
    for inv in invocations:
        groups=inv['result']['classifications'];require([x['case'] for x in groups]==matrix['cases'],'All four ordered cases required')
        for group in groups:
            require([(x['index'],x['position'],x['condition']) for x in group['rows']]==expected,'Per-case fixed window inventory changed')
            require(all(r['warmup'] is (r['index'] in matrix['warmupRounds']) for r in group['rows']),'Warmup labels changed');rows+=group['rows']
    require(len(rows)==matrix['windows'],'Complete2016window matrix required');summary=[];failures=[]
    data_complete=all(complete_window(r) for r in rows)
    if not data_complete:failures.append('IDENTITY_OR_ENDPOINT_INCOMPLETE')
    for case in matrix['cases']:
        for condition in matrix['conditions']:
            item=dict(case=case,condition=condition,pairs=len(matrix['armOrder']))
            for metric in METRICS:
                values={arm:[] for arm in ('legacy','candidate')}
                for n in range(len(matrix['armOrder'])):
                    for arm in values:
                        result=next(x['result'] for x in invocations if x['pair']==n and x['arm']==arm)
                        group=next(x for x in result['classifications'] if x['case']==case)
                        samples=[r.get(metric) for r in group['rows'] if r['condition']==condition and not r['warmup']]
                        require(len(samples)==matrix['formalPerConditionPerCasePerArmPerPair'],'Exactly20formal arm windows required')
                        values[arm].append(statistics_for(samples))
                stats={name:ratio_summary([v[name] for v in values['legacy']],[v[name] for v in values['candidate']]) for name in ('median','p95','maximum')}
                label=case+'/'+condition+':'+metric
                is_system=metric=='inputToDisplayEndNs';is_return=condition=='return'
                limit=g['systemRatioMaxAllConditions'] if is_system else g['returnPrimaryRatioMax'] if is_return and metric==METRICS[0] else \
                    g['returnUiRatioMax'] if is_return else g['refreshAndSyncPrimaryRatioMax'] if metric==METRICS[0] else g['refreshAndSyncUiRatioMax']
                wins=0 if is_system or not is_return else g['returnPrimaryWinningPairsMin'] if metric==METRICS[0] else g['returnUiWinningPairsMin']
                if not stats['median']['complete']:failures.append(label+':MISSING')
                elif stats['median']['medianRatio']>limit or stats['median']['wins']<wins:failures.append(label+':MEDIAN_GATE')
                if not stats['p95']['complete']:failures.append(label+':P95_MISSING')
                elif stats['p95']['medianRatio']>g['sampleP95RatioMaxAllEndpointsConditions']:failures.append(label+':P95_GATE')
                decisions=[]
                for n,(a,b) in enumerate(zip(stats['maximum']['arms']['legacy'],stats['maximum']['arms']['candidate'])):
                    bound=max(a*g['worstPairRatioAllowance'],a+g['worstPairAbsoluteAllowanceNs']) if a is not None else None
                    ok=b is not None and bound is not None and b<=bound;decisions.append(dict(pair=n,limitNs=bound,passed=ok))
                    if not ok:failures.append(label+':WORST_PAIR_'+str(n))
                stats['maximum']['perPairGate']=decisions;item[metric]=stats
            summary.append(item)
    return dict(summary=summary,windows=len(rows),warmups=sum(r['warmup'] for r in rows),formalWindows=sum(not r['warmup'] for r in rows),
        dataComplete=data_complete,passesFrozenPerformanceScreen=not failures,gateFailures=failures,productionAdopted=False)
def experiment():
    pair.require_ci();p=guard();configure();source,run=ci.identity();plan=json.loads((OUT/'plan.json').read_bytes())
    require(plan['sourceSha']==source and plan['runId']==run and plan['protocolSha256']==PROTOCOL_SHA256,'Wrong frozen A1 plan')
    require(plan.get('buildContractSha256')==BUILD_CONTRACT_SHA256,'Performance requires the separately frozen typed build qualification')
    require(verify_typed_bundles(plan['manifests'])==plan['sharedAssetInventorySha256'],'Typed shared runtime asset proof differs')
    original_out,original_report=ci.OUT,ci.real_clock
    def operation():
        doc=dict(sourceSha=source,runId=run,plan=plan,boot=pair.read_boot(),invocations=[])
        try:
            for n,order in enumerate(p['matrix']['armOrder']):
                for position,arm in enumerate(order):
                    label=f'pair-{n}-{position}-{arm}';ci.OUT=OUT/label;ci.OUT.mkdir(exist_ok=False)
                    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS=CLASS,METHODS=METHODS,report=parse)
                    require(pair.read_boot()==doc['boot'],'Guest boot changed')
                    try:result=ci.run_verified_bundle(plan['manifests'][arm],'realClock',emit_evidence=False)
                    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
                        for name in ('instrumentation.log','pipeline-logcat.txt'):
                            path=ci.OUT/name
                            if path.exists():ci.notice('error','A1 performance '+label,ci.failure_excerpt(path))
                        raise
                    require(pair.read_boot()==doc['boot'],'Guest rebooted during invocation')
                    file=ci.OUT/'result.json';doc['invocations'].append(dict(pair=n,position=position,arm=arm,fullResultFile=str(file.relative_to(OUT)),
                        fullResultSha256=ci.digest(file),fullResultBytes=file.stat().st_size,result={k:v for k,v in result.items() if k!='cases'}));save(OUT/'progress.json',doc)
                    # Plain progress must not consume the step's limited check-annotation budget.
                    print('A1 fixed-matrix progress: '+label+':252assigned windows retained; no acceptance decision yet.',flush=True)
            result=aggregate(doc['invocations']);doc.update(result);save(OUT/'results.json',doc)
            compact=dict(sourceSha=source,runId=run,protocolSha256=PROTOCOL_SHA256,productionChanged=False,resultSha256=ci.digest(OUT/'results.json'),**result)
            save(OUT/'summary.json',compact);real.wire.legacy.compact('A1 MAIN width performance',json.dumps(compact,separators=(',',':')).encode())
            require(result['dataComplete'],'A1 frame identity evidence incomplete; originals kept,no replacement sampling')
        finally:ci.OUT=original_out;ci.real_clock=original_report
    ci.emulator(operation=operation)
def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=('guard','prerequisites','build','experiment'));parser.add_argument('--typed-assets',action='store_true');args=parser.parse_args()
    require(not args.typed_assets or args.action=='build','Typed flag is only a build qualification choice')
    if args.action=='guard':print(json.dumps(guard(),indent=2))
    elif args.action=='prerequisites':prerequisites()
    elif args.action=='build':build(args.typed_assets)
    else:experiment()
if __name__=='__main__':
    try:main()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as error:
        ci.notice('error','A1 performance stopped',type(error).__name__+': '+str(error)[:1900]);raise SystemExit(1)
