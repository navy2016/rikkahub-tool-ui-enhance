"""Independent unchanged306 trace acquisition qualification; no candidate, no relaxed parser."""
import argparse,base64,collections,difflib,hashlib,importlib.util,json,os,re,shutil,subprocess,types,zlib
from contextlib import contextmanager
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'benchmarks/terminal-trace-lifecycle-v3'
OUT=ROOT/'artifacts/terminal-trace-lifecycle-v3'
PROTOCOL_SHA256='3b1565f113c9eff14ceb11e71c1bf1ad3a6c799537838fb92981c62a3bca3288'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalTraceLifecycleQualificationTest.kt'
ASSET=BASE/'TerminalTraceLifecycleQualificationTest.kt'
CLASS='me.rerere.rikkahub.pipeline.TerminalTraceLifecycleQualificationTest'
def load(name,file):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
ci=load('lifecycle_ci','run-terminal-pipeline.py');identity=load('lifecycle_identity','terminal-frame-identity.py')
real=ci.real_clock;old_sources=ci.sources;require=identity.require
METRICS=('inputToBufferEvidenceNs','inputToAttemptNs','inputToDisplayEndNs')
def sha(raw):return hashlib.sha256(raw).hexdigest()
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def protocol():
    raw=(BASE/'protocol.json').read_bytes();require(sha(raw)==PROTOCOL_SHA256,'Trace lifecycle protocol changed');return json.loads(raw)
def case_spec(case):
    p=protocol();require(case in p['matrix']['caseSpecs'],'Unregistered lifecycle case');return p['matrix']['caseSpecs'][case]
def case_order(case):return [(n,pos,condition) for n,order in enumerate(case_spec(case)['conditionOrder']) for pos,condition in enumerate(order)]
METHODS=tuple(v['method'] for v in protocol()['matrix']['caseSpecs'].values())
def response(case,round,position,condition):
    require((round,position,condition) in case_order(case),'Response outside fixed matrix')
    kind='DRAW' if condition=='redraw' else 'SYNC' if condition=='sync' else 'ECHO';marker=f'{kind}:{case}:{round:02d}:{position}'
    if condition=='redraw':
        suffix=protocol()['payload']['suffix'];parts=[]
        for row in range(1,25):
            text=marker if row==24 else f'\x1b[1;3;{31+row%6}mr{row:02d} {marker}{suffix}\x1b[0m'
            parts.append(f'\x1b[{row};1H\x1b[2K'+text+'\r')
        raw=''.join(parts).encode()
    else:raw=(('\x1b[?1049l' if condition=='return' else '\x1b[?2026h' if condition=='sync' else '')+
        '\x1b[24;1H\x1b[2K'+marker+'\r'+('\x1b[?2026l' if condition=='sync' else '')).encode()
    require(len(raw)<=protocol()['payload']['bytesMax'],'Response exceeds finite bound');return raw

def guard():
    p=protocol();require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['baseTree'],'Lifecycle target is not306')
    require(not git('diff','--name-only',p['baseProductionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml').strip(),'Lifecycle changed production/build')
    require(not git('ls-files','--others','--exclude-standard','app/src/main').strip(),'Untracked production source')
    require(sha(ASSET.read_bytes())==p['driverSha256'] and sha((BASE/'trace.pbtxt').read_bytes())==p['traceConfigSha256'],'Driver/recipe hash mismatch')
    require(not (ROOT/TEST).exists(),'Temporary collector source leaked')
    old=(ROOT/p['historicalDriver']).read_bytes();require(sha(old)==p['historicalDriverSha256'],'Historical native driver changed')
    patch=''.join(difflib.unified_diff(old.decode().splitlines(True),ASSET.read_text().splitlines(True),fromfile='a/'+p['historicalDriver'],tofile='b/'+str(ASSET.relative_to(ROOT))))
    require(patch==(BASE/'driver.patch').read_text(),'Documented collector adaptation differs')
    require(git('show',p['identityParserSha']+':.github/scripts/terminal-frame-identity.py')==(ROOT/'.github/scripts/terminal-frame-identity.py').read_bytes(),'Qualified frame parser changed')
    require(identity.PROTOCOL_SHA256==p['identityProtocolSha256'],'Identity protocol changed')
    require(not git('diff','--name-only',p['priorClosureSha'],'--','benchmarks/terminal-geometry-c1','benchmarks/terminal-physical-metrics-b1',
        'benchmarks/terminal-physical-metrics-performance','benchmarks/terminal-main-width-lifecycle','benchmarks/terminal-main-width-performance',
        '.github/scripts/run-terminal-pipeline.py','.github/scripts/run-terminal-pipeline-paired.py','benchmarks/terminal-real-clock/trace.pbtxt',
        'benchmarks/terminal-trace-lifecycle','.github/scripts/terminal-trace-lifecycle.py',
        '.github/scripts/test_terminal_trace_lifecycle.py','.github/workflows/terminal-trace-lifecycle.yml',
        'benchmarks/terminal-trace-lifecycle-v2','.github/scripts/terminal-trace-lifecycle-v2.py',
        '.github/scripts/test_terminal_trace_lifecycle_v2.py','.github/workflows/terminal-trace-lifecycle-v2.yml',
        'app/src/terminaltestAndroidTest').strip(),'Lifecycle altered a prior candidate/driver/collector')
    text=ASSET.read_text()
    for token in ('createAndroidComposeRule','mainClock','waitForIdle','registerFrameCommitCallback','System.nanoTime()'):
        require(token not in text,'Disallowed identity/clock substitute:'+token)
    require(text.index('requireNotNull(perfetto).start()')>text.index('settle(alternateCohort,"initial")'),'Trace begins before stable native setup')
    require(text.index('val traceReceipt=requireNotNull(perfetto).stop()')>text.index('for(round in 0..20)') and
        text.index('val traceReceipt=requireNotNull(perfetto).stop()')<text.index('val finalMain=terminal.renderFrame()'),'Trace lifecycle order changed')
    old_config=(ROOT/'benchmarks/terminal-real-clock/trace.pbtxt').read_text()
    line='data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }\n'
    require(old_config.count(line)==1 and old_config.replace(line,'')==(BASE/'trace.pbtxt').read_text(),'Recipe changed beyond excluding process-stats producer')
    require(sum(v['windows'] for v in p['matrix']['caseSpecs'].values())==420 and p['matrix']['launches']==2 and p['matrix']['windows']==840,'Lifecycle matrix changed')
    return p

def sources():
    result=old_sources()
    for name in ('.github/scripts/terminal-trace-lifecycle-v3.py','.github/scripts/terminal-frame-identity.py',
        'benchmarks/terminal-frame-identity/protocol.json','benchmarks/terminal-trace-lifecycle-v3/protocol.json',
        'benchmarks/terminal-trace-lifecycle-v3/trace.pbtxt','benchmarks/terminal-trace-lifecycle-v3/driver.patch',
        'benchmarks/terminal-trace-lifecycle-v3/v2-transport-stop-fix.patch',str(ASSET.relative_to(ROOT))):result[name]=ci.digest(ROOT/name)
    result[TEST]=sha(ASSET.read_bytes());return result
@contextmanager
def driver_overlay():
    guard();path=ROOT/TEST;path.write_bytes(ASSET.read_bytes())
    try:yield
    finally:path.unlink(missing_ok=True);guard()
def ci_only():require(os.environ.get('GITHUB_ACTIONS')=='true','Native lifecycle work is CI-only')
def build():
    ci_only();p=guard();ci.sources=sources;OUT.mkdir(parents=True,exist_ok=False)
    with driver_overlay():
        subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PterminalViewportTests=true','-PincludeX86_64AbiForTests=true',
            ':app:assembleTerminaltest',':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
        ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes())
    require(manifest['sources']==sources(),'Build/driver provenance differs')
    require(manifest['target_release_derived'] is True and manifest['target_debuggable'] is False,'Target is not Release-derived nondebuggable')
    save(OUT/'manifest.json',manifest);save(OUT/'protocol.json',p);shutil.copyfile(BASE/'trace.pbtxt',OUT/'trace.pbtxt')
def case_records(text):
    p=protocol();bounds=p['serialization'];groups={}
    for row in real.records(text,'REAL_CLOCK_PART'):
        require(row['case'] in p['matrix']['cases'] and type(row['part']) is int and type(row['parts']) is int and
            1<=row['part']<=row['parts']<=bounds['casePartsMax'] and len(row['data'])<=bounds['casePartChars'],'Invalid lifecycle case fragment')
        entry=groups.setdefault(row['case'],dict(parts=row['parts'],sha256=row['sha256'],data={}))
        require(entry['parts']==row['parts'] and entry['sha256']==row['sha256'] and row['part'] not in entry['data'],'Mixed/duplicate lifecycle case fragment')
        entry['data'][row['part']]=row['data']
    require(set(groups)==set(p['matrix']['cases']),'Missing original lifecycle cases');result=[]
    for name in p['matrix']['cases']:
        entry=groups[name];require(set(entry['data'])==set(range(1,entry['parts']+1)),'Incomplete lifecycle case fragments')
        packed=base64.b64decode(''.join(entry['data'][n] for n in sorted(entry['data'])),validate=True)
        stream=zlib.decompressobj();raw=stream.decompress(packed,bounds['caseRawBytesMax']+1)
        require(stream.eof and not stream.unused_data and not stream.unconsumed_tail and len(raw)<=bounds['caseRawBytesMax'] and sha(raw)==entry['sha256'],'lifecycle case content size/hash differs')
        value=json.loads(raw);require(value['case']==name,'Mislabeled lifecycle case');result.append(value)
    return result
def observers(case):
    return [[dict(raw=raw,publicIntended=m[1],publicVsync=m[2],callbackBootNs=m[0],dropped=m[9]) for m,raw in zip(case[key],case[rawkey])]
        for key,rawkey in (('metrics','metricsRaw'),('mirrorMetrics','mirrorRaw'))]
def validate_cases(text):
    p=protocol();matrix=p['matrix'];cases=case_records(text);cleanup=real.records(text,'REAL_CLOCK_CLEANUP')
    require(len(cleanup)==12 and {x['case'] for x in cleanup}==set(matrix['cases']) and all(x['closed'] and x['threadStopped'] for x in cleanup),'lifecycle cleanup incomplete')
    for c in cases:
        spec=case_spec(c['case']);size=spec['historySize'];shown=spec['expectedIme'];alternate=spec['cohort']=='alt'
        require(c['cohort']==spec['cohort'],'Wrong redraw/guard process')
        require(c['passed'] and c['performanceMeasured'] is False and c['collectorDiagnosisOnly'] is True and c['baselineOnly'] is True and c['protocolId']==p['id'],'Wrong qualification scope')
        require(c['clock']=='CLOCK_BOOTTIME' and c['mode']=='fullVirtual' and c['historySize']==size and c['expectedIme']==shown,'Wrong native branch/clock')
        require('incrementLaunchCount:' in c['startupReceipt'] and [x['stage'] for x in c['preparation']]==['before-write','after-write','persisted-and-flow-confirmed','before-mount'] and
            all(x['configured'] and not x['init'] for x in c['preparation'][-2:]),'Configured first mount not proven')
        expected=case_order(c['case'])
        require([(w['index'],w['position'],w['condition']) for w in c['samples']]==expected,'Frozen21/63-window case order differs')
        gates=[('initial-main',False),('initial',alternate)]+[(f'prepare-r{n}-{cond}',alternate or cond=='return') for n,_,cond in expected]
        if alternate:gates.append(('after-alt-history',False))
        require([(g['phase'],g['alternate']) for g in c['gates']]==gates and len({g['rows'] for g in c['gates']})==1 and
            all(g['columns']==80 and g['rows']>=24 and g['virtual'] and g['ime']==shown for g in c['gates']),'Grid/IME/setup gate drift')
        require(c['mainHistoryVerifiedAfterAllWindows'] is True and c['historyVerifiedAtBootNs']>=c['samples'][-1]['observedNs'],'Hidden MAIN history not finally verified')
        for w in c['samples']:
            cond=w['condition'];raw=response(c['case'],w['index'],w['position'],cond)
            require(w['outputBytes']==len(raw) and w['outputSha256']==sha(raw),'Finite response bytes differ')
            require(w['warmup'] is (w['index'] in matrix['warmupRounds']) and w['clock']=='CLOCK_BOOTTIME','Clock/warmup changed')
            require(w['sameInitialHistory'] is (None if alternate else True) and w['visibleAfterMeasurement'] and w['virtual'] and
                w['alternate'] is alternate and w['screenRowsVerified']==(24 if cond=='redraw' else 1),'History/complete screen/visibility changed')
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

def validate_lifecycle(cases,cleanup,text):
    p=protocol();starts=real.records(text,'REAL_CLOCK_TRACE_START');stops=real.records(text,'REAL_CLOCK_TRACE_STOP')
    expected=set(p['matrix']['cases'])
    require(len(starts)==len(stops)==12 and {x['case'] for x in starts}=={x['case'] for x in stops}==expected,'Trace lifecycle receipt inventory differs')
    start_by={x['case']:x for x in starts};stop_by={x['case']:x for x in stops};cleanup_by={x['case']:x for x in cleanup}
    for case in cases:
        name=case['case'];receipt=case['traceCapture'];start=start_by[name];stop=stop_by[name];end=cleanup_by[name]
        require(receipt==stop and receipt['case']==name,'Native trace receipt changed')
        remote='/data/misc/perfetto-traces/lifecycle-'+name+'.pftrace'
        require(receipt['remote']==start['remote']==remote and type(receipt['tracePid']) is int and receipt['tracePid']>0 and start['pid']==receipt['tracePid'],'Wrong trace path/PID')
        require(receipt['startExit']==receipt['stopExit']==start['exit']==0 and receipt['stoppedBeforeTeardown'] is True and end['traceStopped'] is True,'Trace start/stop/cleanup failure')
        require(receipt['configSha256']==start['configSha256']==p['traceConfigSha256'],'Trace config provenance differs')
        require(receipt['startBeginBootNs']==start['beginBootNs'] and receipt['startedBootNs']==start['startedBootNs'],'Startup timestamps disagree')
        require(receipt['procAbsentAfterStop'] is True and receipt['processStat']==start['processStat'] and
            type(receipt['processStartTicks']) is int and receipt['processStartTicks']>0 and
            receipt['processStartTicks']==start['processStartTicks'],'Process exit/identity proof missing')
        def process_ticks(stat):
            require(stat.startswith(str(receipt['tracePid'])+' (perfetto) '),'Different traced process')
            fields=stat.rsplit(') ',1)[-1].split();require(len(fields)>=20,'Truncated proc stat')
            return int(fields[19])
        require(process_ticks(receipt['processStat'])==process_ticks(receipt['preStopProcessStat'])==receipt['processStartTicks'],'Trace PID reused before stop')
        stop_lines=receipt['stopStdout'].splitlines();require(stop_lines and stop_lines[-1]=='PROC_ABSENT','Stop did not observe proc disappearance')
        require(all(process_ticks(line)==receipt['processStartTicks'] for line in stop_lines[:-1]),'Trace PID reused during stop')
        require(0<receipt['startBeginBootNs']<=receipt['startedBootNs']<case['samples'][0]['beganNs'] and
            case['samples'][-1]['closedBootNs']<=case['samples'][-1]['observedNs']<=receipt['stopBeginBootNs']<=receipt['stoppedBootNs']<=
            case['historyVerifiedAtBootNs']<end['teardownStartedBootNs'],'Trace does not cover all fixed windows before teardown')
        require(type(receipt['bytes']) is int and 0<receipt['bytes']<100663296 and re.fullmatch('[a-f0-9]{64}',receipt['sha256']),'Trace file size/hash invalid')
    return starts,stops

def complete_window(row):
    return row['identityDecidable'] and not row['noSubmittedAttempt'] and not row['noPresentedAttempt'] and all(type(row.get(k)) is int and row[k]>0 for k in METRICS)

def classify_invocation(folder,text,manifest,device,installed):
    cases,cleanup=validate_cases(text);starts,stops=validate_lifecycle(cases,cleanup,text);groups=[]
    for method,case in zip(METHODS,cases):
        raw=(folder/'latency-traces'/(method+'.pftrace')).read_bytes();receipt=case['traceCapture']
        require(len(raw)==receipt['bytes'] and sha(raw)==receipt['sha256'],'Pulled trace differs from native stop receipt')
        trace=identity.parse_trace(raw,case['pid']);index=identity.FrameIndex(trace);queues=observers(case)
        rows=[dict(phase=sample['phase'],index=sample['index'],position=sample['position'],condition=sample['condition'],warmup=sample['warmup'],
            **identity.classify_window(sample,index,queues)) for sample in case['samples']]
        groups.append(dict(case=case['case'],method=method,traceSha256=sha(raw),traceBytes=len(raw),traceFaults=trace['faults'],openSpans=trace['openSpans'],
            sequenceInitialMarkers=trace['sequenceInitialMarkers'],callbackDropped=[sum(x['dropped'] for x in q) for q in queues],rows=rows))
    result=dict(cases=cases,cleanup=cleanup,traceStarts=starts,traceStops=stops,classifications=groups,device=device,installedApkHashes=installed,
        qualificationPassed=all(complete_window(row) for g in groups for row in g['rows']),optimizationMeasured=False)
    save(folder/'result.json',result);return result

def install(manifest):
    # Original package/signature/install/hash/permission/compiler preparation; no source overlays.
    sha_expected,run_expected=ci.identity()
    require(manifest['sourceSha']==sha_expected and manifest['runId']==run_expected and manifest['sources']==sources(),'Wrong lifecycle bundle source/run')
    require(set(manifest['apks'])=={'target','test'},'Missing target/driver bundle');installed={}
    for role in ('target','test'):
        apk=manifest['apks'][role];package=ci.PACKAGE if role=='target' else ci.TEST_PACKAGE
        path=ROOT/apk['file'];require(apk['package']==package and ci.digest(path)==apk['sha256'],'Lifecycle APK mismatch')
        remote=f'/data/local/tmp/terminal-lifecycle-{role}.apk';ci.output(['adb','push',str(path),remote],180)
        require('Success' in ci.output(['adb','shell','pm','install','-r','-t',remote],180),'Lifecycle APK installation failed')
        paths=ci.output(['adb','shell','pm','path',package]).strip().splitlines();require(len(paths)==1 and paths[0].startswith('package:/data/app/'),'Ambiguous installed APK')
        value=ci.output(['adb','shell','sha256sum',paths[0][len('package:'):]]).split()[0]
        require(value==apk['sha256'],'Installed lifecycle hash differs');installed[role]=value
    ci.output(['adb','shell','pm','grant',ci.PACKAGE,'android.permission.POST_NOTIFICATIONS'])
    require(re.search(r'android\.permission\.POST_NOTIFICATIONS: granted=true',ci.output(['adb','shell','dumpsys','package',ci.PACKAGE])),'Notification permission missing')
    require('Success' in ci.output(['adb','shell','cmd','package','compile','-f','-m','speed',ci.PACKAGE],180),'Target speed compilation failed')
    ci.output(['adb','shell','settings','put','secure','show_ime_with_hard_keyboard','1'])
    device={label:ci.output(['adb','shell',*args]).strip() for label,args in (
        ('fingerprint',['getprop','ro.build.fingerprint']),('size',['wm','size']),('density',['wm','density']),
        ('ime',['settings','get','secure','default_input_method']))}
    save(OUT/'device.json',device);save(OUT/'installed-apks.json',installed);return device,installed

def run():
    ci_only();p=guard();ci.sources=sources;original_out=ci.OUT;ci.OUT=OUT
    manifest=json.loads((OUT/'manifest.json').read_bytes());require(manifest['sources']==sources(),'Lifecycle environment manifest differs')
    def operation():
        device,installed=install(manifest);boot=ci.output(['adb','shell','cat','/proc/sys/kernel/random/boot_id']).strip()
        doc=dict(sourceSha=manifest['sourceSha'],runId=manifest['runId'],protocol=p,protocolSha256=PROTOCOL_SHA256,manifest=manifest,boot=boot,
            candidateIncluded=False,productionChanged=False,optimizationMeasured=False,performanceAccepted=False,invocations=[])
        config=base64.b64encode((BASE/'trace.pbtxt').read_bytes()).decode()
        for launch in range(p['matrix']['launches']):
            require(ci.output(['adb','shell','cat','/proc/sys/kernel/random/boot_id']).strip()==boot,'Guest boot changed')
            folder=OUT/f'launch-{launch}';folder.mkdir(exist_ok=False);traces=folder/'latency-traces';traces.mkdir()
            logs=[];failures=[];ci.output(['adb','logcat','-c'])
            try:
                for method,name in zip(METHODS,p['matrix']['cases']):
                    ci.output(['adb','shell','am','force-stop',ci.PACKAGE]);ci.output(['adb','shell','am','force-stop',ci.TEST_PACKAGE])
                    file=folder/(method+'.log');remote='/data/misc/perfetto-traces/lifecycle-'+name+'.pftrace'
                    completed=None;status=dict(method=method,case=name,traceOwnership='native-driver',pullSucceeded=False)
                    try:
                        with file.open('w') as stream:
                            completed=subprocess.run(['adb','shell','am','instrument','-w','-r','-e','class',CLASS+'#'+method,
                                '-e','traceLifecycleConfig',config,'-e','traceLifecycleConfigSha256',p['traceConfigSha256'],ci.TEST_PACKAGE+'/androidx.test.runner.AndroidJUnitRunner'],
                                stdout=stream,stderr=subprocess.STDOUT,timeout=520)
                    except (OSError,subprocess.SubprocessError) as error:failures.append(method+': '+str(error)[:500])
                    finally:
                        try:
                            output=ci.output(['adb','pull',remote,str(traces/(method+'.pftrace'))],120)
                            status.update(pullSucceeded=True,pullOutput=output)
                        except (OSError,subprocess.SubprocessError) as error:status['pullError']=str(error)[:600]
                        save(traces/(method+'-pull-status.json'),status)
                    text=file.read_text();logs.append(text)
                    try:
                        require(completed is not None and completed.returncode==0,'Instrumentation exit failed')
                        ci.require_instrumentation(text,expected=1);require(status['pullSucceeded'],'Native trace retrieval failed')
                    except ValueError as error:failures.append(method+': '+str(error))
                require(not failures,'; '.join(failures)[:2800])
                result=classify_invocation(folder,'\n'.join(logs),manifest,device,installed)
                require(ci.output(['adb','shell','cat','/proc/sys/kernel/random/boot_id']).strip()==boot,'Guest rebooted')
                doc['invocations'].append(dict(launch=launch,resultFile=str((folder/'result.json').relative_to(OUT)),resultSha256=ci.digest(folder/'result.json'),result=result))
                save(OUT/'progress.json',doc)
                print(f'Lifecycle fixed launch {launch}:420windows retained, qualification={result["qualificationPassed"]}; no retry.',flush=True)
            finally:
                (folder/'instrumentation.log').write_text('\n'.join(logs))
                (folder/'pipeline-logcat.txt').write_text(ci.output(['adb','logcat','-d','-v','threadtime','TerminalPipelineActivity:I','AndroidRuntime:E','TestRunner:I','*:S']))
                if failures:ci.notice('error','Trace lifecycle native failure',ci.failure_excerpt(folder/'instrumentation.log'))
        rows=[r for inv in doc['invocations'] for g in inv['result']['classifications'] for r in g['rows']]
        require(len(rows)==840,'Lifecycle fixed window count differs');passed=all(complete_window(r) for r in rows)
        doc.update(qualificationPassed=passed,windows=len(rows),formalWindows=sum(not r['warmup'] for r in rows),warmups=sum(r['warmup'] for r in rows),
            incompleteWindows=sum(not complete_window(r) for r in rows),rawTraces=24,noReplacementSamples=True)
        save(OUT/'results.json',doc)
        compact={k:v for k,v in doc.items() if k not in ('protocol','manifest','invocations')};compact['resultsSha256']=ci.digest(OUT/'results.json')
        compact['traceFaults']=[dict(launch=inv['launch'],case=g['case'],faults=g['traceFaults']) for inv in doc['invocations'] for g in inv['result']['classifications'] if g['traceFaults']]
        save(OUT/'summary.json',compact)
        # One small summary only. Large per-case/attempt evidence remains in original artifact.
        ci.notice('notice','Trace lifecycle qualification',json.dumps(compact,separators=(',',':')))
        require(passed,'Lifecycle qualification incomplete; original faults/nulls kept,no replacement sampling')
    try:ci.emulator(operation=operation)
    finally:ci.OUT=original_out

def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=('guard','build','run'));args=parser.parse_args()
    if args.action=='guard':print(json.dumps(guard(),indent=2))
    elif args.action=='build':build()
    else:run()
if __name__=='__main__':
    try:main()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as error:
        ci.notice('error','Trace lifecycle qualification stopped',type(error).__name__+': '+str(error)[:2400]);raise SystemExit(1)
