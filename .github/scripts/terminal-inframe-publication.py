"""Isolated safe in-frame publication candidate: boundaries and mechanism before performance."""
import argparse,hashlib,importlib.util,json,os,re,shutil,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];BASE=ROOT/'benchmarks/terminal-inframe-publication-candidate'
OUT=ROOT/'artifacts/terminal-inframe-publication'
PROTOCOL_SHA='884495008f0760ece6b96de9293dcb320e0cabfd7d924cae8c18e8a42b4549c2'
FREEZE_SHA='bfa29900798d6bb591c66da543859aaa37361dd7'
PAGE='app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt'
HELPER='app/src/main/java/me/rerere/rikkahub/data/container/TerminalInFramePublication.kt'
UNIT='app/src/test/java/me/rerere/rikkahub/data/container/TerminalInFramePublicationTest.kt'
TRACE='app/src/main/java/me/rerere/rikkahub/data/container/TerminalPipelineTrace.kt'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalInFramePublicationTest.kt'
TAG=' // INFRAME_PUBLICATION_PROBE'
STAGES=('INFRAME_WAIT_START','INFRAME_CALLBACK_START','INFRAME_CALLBACK_END','INFRAME_ELIGIBLE','INFRAME_PUBLISH_CALL','INFRAME_PUBLISH_RESULT',
    'INFRAME_CONTINUATION','INFRAME_FALLBACK_SYNC_START','INFRAME_FALLBACK_SYNC_END','INFRAME_FALLBACK_RESIZE_START',
    'INFRAME_FALLBACK_RESIZE_END','INFRAME_FALLBACK_CALL','INFRAME_FALLBACK_RETURN','INFRAME_BLANK_START','INFRAME_BLANK_END')
s=importlib.util.spec_from_file_location('inframe_ci',Path(__file__).with_name('run-terminal-pipeline.py'))
ci=importlib.util.module_from_spec(s);s.loader.exec_module(ci)
s=importlib.util.spec_from_file_location('inframe_identity',Path(__file__).with_name('terminal-frame-identity.py'))
identity=importlib.util.module_from_spec(s);s.loader.exec_module(identity)
real=ci.real_clock;require=real.require;old_sources=ci.sources
METHODS=('inframeOneThousandHidden','inframeOneThousandShown','inframeTenThousandHidden','inframeTenThousandShown')
def sha(raw):return hashlib.sha256(raw).hexdigest()
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def protocol():
    raw=(BASE/'protocol.json').read_bytes();require(sha(raw)==PROTOCOL_SHA,'In-frame boundary protocol changed');return json.loads(raw)
def spec():return json.loads((BASE/'sources.json').read_bytes())
def guard():
    p=protocol();require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['baseTree'],'Production tree changed')
    require(git('show',FREEZE_SHA+':'+str((BASE/'protocol.json').relative_to(ROOT)))==(BASE/'protocol.json').read_bytes(),'Boundary protocol changed since freeze')
    for name in ('candidate.patch','sources.json','TerminalInFramePublication.kt','TerminalInFramePublicationTest.kt'):
        require(git('show',FREEZE_SHA+':'+str((BASE/name).relative_to(ROOT)))==(BASE/name).read_bytes(),'Candidate changed after freeze:'+name)
    require(not git('diff','--name-only',p['baseProductionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml').strip(),'Production/dependencies dirty')
    require(not (ROOT/HELPER).exists() and not (ROOT/UNIT).exists(),'Temporary candidate/helper test left in production tree')
    require(sha((ROOT/PAGE).read_bytes())==spec()['files'][PAGE]['base'],'Base Page changed')
    return p
def patch_page(original):
    lines=(BASE/'candidate.patch').read_text().splitlines(keepends=True);source=original.splitlines(keepends=True);out=[];cursor=0;i=0
    require(lines[:2]==['--- a/'+PAGE+'\n','+++ b/'+PAGE+'\n'],'Patch scope is not exactly Page')
    i=2
    while i<len(lines):
        hit=re.fullmatch(r'@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*\n',lines[i]);require(hit is not None,'Invalid Page hunk')
        start=int(hit[1])-1;require(start>=cursor,'Overlapping patch hunks');out.extend(source[cursor:start]);cursor=start;i+=1;old_count=0;new_count=0
        while i<len(lines) and not lines[i].startswith('@@ '):
            line=lines[i];require(line[:1] in (' ','+','-'),'Unexpected patch operation')
            if line[0] in ' -':require(cursor<len(source) and source[cursor]==line[1:],'Patch base context differs');cursor+=1;old_count+=1
            if line[0] in ' +':out.append(line[1:]);new_count+=1
            i+=1
        require(old_count==int(hit[2] or 1) and new_count==int(hit[4] or 1),'Patch hunk counts differ')
    out.extend(source[cursor:]);return ''.join(out)
def strip_candidate(page):
    imports=('import kotlinx.coroutines.currentCoroutineContext\n','import kotlinx.coroutines.isActive\n',
        'import me.rerere.rikkahub.data.container.terminalInFramePublicationAllowed\n','import me.rerere.rikkahub.data.container.tryTerminalInFramePublication\n')
    for line in imports:require(page.count(line)==1,'Unexpected candidate import');page=page.replace(line,'')
    page=page.replace('    val publicationAttached = remember(processId) { AtomicBoolean(false) }\n','')
    a=page.index('    val latestTryPublishInFrame by rememberUpdatedState<() -> Boolean>({\n');b=page.index('    fun scheduleTerminalRender() {\n',a)
    page=page[:a]+page[b:]
    start=page.index('                val publicationContext = currentCoroutineContext()\n',page.index('    fun scheduleTerminalRender() {'))
    body=page.index('                    val syncCompleted = if (terminalOutputUpdates != null) {\n',start)
    end=page.index('                if (forceBlankPending.get()) {\n',body)
    fallback=page[body:end];require(fallback.endswith('                }\n'),'Fallback branch not closed')
    fallback=fallback[:-len('                }\n')]
    fallback=''.join(line[4:] if line.strip() else line for line in fallback.splitlines(keepends=True))
    page=page[:start]+'                withFrameNanos { } // One publication per display frame; no fixed 33ms polling cadence.\n'+fallback+page[end:]
    page=page.replace('        publicationAttached.set(true)\n','').replace('            publicationAttached.set(false)\n','')
    return page
def candidate():
    guard();base=(ROOT/PAGE).read_text();page=patch_page(base);helper=(BASE/'TerminalInFramePublication.kt').read_text()
    require(strip_candidate(page)==base,'Candidate changed original publication/fallback beyond declared structure')
    require(sha(page.encode())==spec()['files'][PAGE]['candidate'] and sha(helper.encode())==spec()['files'][HELPER]['candidate'],'Candidate digest mismatch')
    return {PAGE:page,HELPER:helper}
def marker(name,spaces,flag=None):
    return ' '*spaces+f'TerminalPipelineTrace.record(processId, TerminalPipelineStage.{name}'+(', virtual = '+flag if flag else '')+')'+TAG+'\n'
def instrument(files):
    out=dict(files);page=out[PAGE]
    a='''        tryTerminalInFramePublication(allowed, renderPending) {
            latestPublishTerminalFrame(false, false)
        }
'''
    b=marker('INFRAME_ELIGIBLE',8,'allowed')+'''        tryTerminalInFramePublication(allowed, renderPending) {
'''+marker('INFRAME_PUBLISH_CALL',12)+'''            val inFrameProbeResult = latestPublishTerminalFrame(false, false)
'''+marker('INFRAME_PUBLISH_RESULT',12,'inFrameProbeResult')+'''            inFrameProbeResult
        }
'''
    require(page.count(a)==1,'Missing helper call');page=page.replace(a,b)
    a='''                val publishedInFrame = withFrameNanos {
                    publicationContext.isActive && latestTryPublishInFrame()
                }
'''
    b=marker('INFRAME_WAIT_START',16)+'''                val publishedInFrame = withFrameNanos {
'''+marker('INFRAME_CALLBACK_START',20)+'''                    val inFrameProbeAllowed = publicationContext.isActive && latestTryPublishInFrame()
'''+marker('INFRAME_CALLBACK_END',20,'inFrameProbeAllowed')+'''                    inFrameProbeAllowed
                }
'''+marker('INFRAME_CONTINUATION',16,'publishedInFrame')
    require(page.count(a)==1,'Missing callback');page=page.replace(a,b)
    page=page.replace('''                if (!publishedInFrame) {
''','''                if (!publishedInFrame) {
'''+marker('INFRAME_FALLBACK_SYNC_START',20,'terminalOutputUpdates?.value?.synchronizedOutput ?: terminalEmulator.isSynchronizedOutput()'))
    page=page.replace('''                    if (resizeAwaitingTuiRedraw.get()) {
''',marker('INFRAME_FALLBACK_SYNC_END',20,'syncCompleted')+marker('INFRAME_FALLBACK_RESIZE_START',20,'resizeAwaitingTuiRedraw.get()')+'''                    if (resizeAwaitingTuiRedraw.get()) {
''')
    page=page.replace('''                    renderPending.set(false) // Capture includes all notifications received while awaiting this frame.
''',marker('INFRAME_FALLBACK_RESIZE_END',20)+'''                    renderPending.set(false) // Capture includes all notifications received while awaiting this frame.
''')
    page=page.replace('''                    if (!latestPublishTerminalFrame(forced, !syncCompleted)) {
''',marker('INFRAME_FALLBACK_CALL',20,'forced')+'''                    val fallbackProbeResult = latestPublishTerminalFrame(forced, !syncCompleted)
'''+marker('INFRAME_FALLBACK_RETURN',20,'fallbackProbeResult')+'''                    if (!fallbackProbeResult) {
''')
    page=page.replace('''                    delay(TERMINAL_GRID_CLEAR_GRACE_MS)
''',marker('INFRAME_BLANK_START',20)+'''                    delay(TERMINAL_GRID_CLEAR_GRACE_MS)
'''+marker('INFRAME_BLANK_END',20))
    trace=(ROOT/TRACE).read_text();trace=trace.replace('    ROW_SYNC_STARTED, ROW_SYNC_FINISHED,\n',
        '    ROW_SYNC_STARTED, ROW_SYNC_FINISHED,\n'+''.join('    '+stage+','+TAG+'\n' for stage in STAGES))
    out[PAGE]=page;out[TRACE]=trace
    require(strip_probe(PAGE,page)==files[PAGE] and strip_probe(TRACE,trace)==(ROOT/TRACE).read_text(),'Trace-only overlay did not roundtrip')
    return out
def strip_probe(name,text):
    text='\n'.join(line for line in text.split('\n') if TAG not in line)
    if name==PAGE:
        text=text.replace('''            val inFrameProbeResult = latestPublishTerminalFrame(false, false)
            inFrameProbeResult
''','''            latestPublishTerminalFrame(false, false)
''')
        text=text.replace('''                    val inFrameProbeAllowed = publicationContext.isActive && latestTryPublishInFrame()
                    inFrameProbeAllowed
''','''                    publicationContext.isActive && latestTryPublishInFrame()
''')
        text=text.replace('''                    val fallbackProbeResult = latestPublishTerminalFrame(forced, !syncCompleted)
                    if (!fallbackProbeResult) {
''','''                    if (!latestPublishTerminalFrame(forced, !syncCompleted)) {
''')
    return text

def sources():
    data=old_sources()
    for name in (TEST,'.github/scripts/terminal-inframe-publication.py','benchmarks/terminal-inframe-publication-candidate/protocol.json',
        'benchmarks/terminal-inframe-publication-candidate/candidate.patch','benchmarks/terminal-inframe-publication-candidate/sources.json',
        'benchmarks/terminal-inframe-publication-candidate/TerminalInFramePublication.kt',
        'benchmarks/terminal-inframe-publication-candidate/TerminalInFramePublicationTest.kt',
        'benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle'):
        data[name]=ci.digest(ROOT/name)
    if (ROOT/HELPER).exists():data[HELPER]=ci.digest(ROOT/HELPER)
    return data
def junit_receipt():
    import xml.etree.ElementTree as ET
    expected={'me.rerere.rikkahub.data.container.TerminalInFramePublicationTest':12,
        'me.rerere.rikkahub.data.container.TerminalRenderSnapshotTest':8,'me.rerere.rikkahub.data.container.TerminalRenderedRowsTest':29}
    found={};rows=[]
    for file in (ROOT/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'):
        suite=ET.parse(file).getroot();name=suite.get('name')
        if name not in expected:continue
        tests=list(suite.iter('testcase'))
        require(len(tests)==expected[name],'JVM count differs:'+str(name))
        for test in tests:
            require(not any(test.find(k) is not None for k in ('failure','error','skipped')),'JVM boundary failure')
            rows.append(dict(classname=name,name=test.get('name')))
        found[name]=len(tests);path=OUT/'jvm'/file.name;path.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,path)
    require(found==expected and len(rows)==protocol()['jvm']['totalMethods'],'Incomplete49-method JVM boundary run')
    return rows
def build():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android/Kotlin builds only in CI');guard();ci.sources=sources
    c=candidate();probe=instrument(c);base={PAGE:(ROOT/PAGE).read_bytes(),TRACE:(ROOT/TRACE).read_bytes()};base_sources=sources()
    OUT.mkdir(parents=True,exist_ok=False)
    try:
        for name,text in c.items():(ROOT/name).write_text(text)
        (ROOT/UNIT).write_bytes((BASE/'TerminalInFramePublicationTest.kt').read_bytes())
        # AGP creates host tests for testBuildType. terminalPipelineTests overrides it to
        # terminaltest, so the true Release JVM gate must be a separate Gradle invocation.
        command=['bash','.github/scripts/run-terminal-gradle.sh','-PterminalReleaseTests=true',':app:testReleaseUnitTest']
        for name in ('TerminalInFramePublicationTest','TerminalRenderSnapshotTest','TerminalRenderedRowsTest'):
            command+=['--tests','me.rerere.rikkahub.data.container.'+name]
        subprocess.run(command+['--stacktrace'],cwd=ROOT,check=True,timeout=1800);jvm=junit_receipt()
        for name,text in probe.items():(ROOT/name).write_text(text)
        subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PterminalViewportTests=true','-PincludeX86_64AbiForTests=true',
            '-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','terminalMechanismDependencies',
            ':app:assembleTerminaltest',':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
        ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes());expected=dict(base_sources)
        for name,text in probe.items():expected[name]=sha(text.encode())
        require(manifest['sources']==expected,'Compiled probe source differs');manifest['environmentSources']=base_sources;save(ci.OUT/'manifest.json',manifest)
        for label,files in (('candidate-source',c),('probe-source',probe)):
            for name,text in files.items():
                p=OUT/label/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
        save(OUT/'source-receipt.json',dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],baseTree=protocol()['baseTree'],productionChanged=False,
            candidateFiles={n:sha(t.encode()) for n,t in c.items()},probeFiles={n:sha(t.encode()) for n,t in probe.items()},jvm=jvm,protocolSha256=PROTOCOL_SHA))
        shutil.copyfile(ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json',OUT/'resolved-dependencies.json')
    finally:
        for name,raw in base.items():(ROOT/name).write_bytes(raw)
        for name in (HELPER,UNIT):
            if (ROOT/name).exists():(ROOT/name).unlink()
        guard();require(not git('diff','--name-only','HEAD').strip(),'Candidate/probe checkout not restored')

def validate_cases(text):
    p=protocol();old=real.CASES
    try:real.CASES=tuple(p['device']['cases']);cases=real.case_records(text)
    finally:real.CASES=old
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP');require(len(cleanup)==4 and all(x['closed'] and x['threadStopped'] for x in cleanup),'Incomplete native cleanup')
    for c in cases:
        _,size,ime=c['case'].split('-');size=int(size);shown=ime=='shown'
        require(c['passed'] and c['protocolId']==p['id'] and c['clock']=='CLOCK_BOOTTIME' and c['performanceMeasured'] is False,'Not candidate mechanism evidence')
        require(c['mode']=='fullVirtual' and c['expectedIme']==shown and c['historySize']==size,'Wrong scenario')
        require('incrementLaunchCount:' in c['startupReceipt'] and all(x['configured'] for x in c['preparation'][-2:]),'Startup fence absent')
        order=[(r,pos,cond) for r in range(4) for pos,cond in enumerate(('refresh','sync') if r%2==0 else ('sync','refresh'))]
        require([(w['index'],w['position'],w['condition']) for w in c['samples']]==order,'Fixed mechanism window inventory differs')
        require(len(c['gates'])==9 and len({g['rows'] for g in c['gates']})==1 and all(g['columns']==80 and g['virtual'] and g['ime']==shown for g in c['gates']),'Grid/IME altered')
        for w in c['samples']:
            prefix='SYNC' if w['condition']=='sync' else 'ECHO';marker=f'{prefix}:{c["case"]}:{w["index"]:02d}:{w["position"]}'
            output=(('\x1b[?2026h' if prefix=='SYNC' else '')+'\x1b[24;1H\x1b[2K'+marker+'\r'+('\x1b[?2026l' if prefix=='SYNC' else '')).encode()
            require(w['outputBytes']==len(output) and w['outputSha256']==sha(output),'Byte protocol differs')
            require(w['sameInitialHistory'] and w['visibleAfterMeasurement'] and w['virtual'] and w['warmup'] is False,'Page/history response mismatch')
            require(w['clock']=='CLOCK_BOOTTIME' and w['closedBootNs']==w['drawNs']+p['device']['naturalTailNs'],'Clock or cutoff changed')
            require(w['fedRevision']==w['publishedRevision']==w['drawRevision'],'Final revision mismatch')
            require(all(a['seq']<b['seq'] and a['ns']<=b['ns'] for a,b in zip(w['events'],w['events'][1:])),'Nonmonotonic marker events')
            for stage in ('OUTPUT_READ','EMULATOR_FED'):require(sum(e['bytes'] for e in w['events'] if e['stage']==stage)==len(output),'Input/output bytes incomplete')
    return cases,cleanup
def classify(sample,index):
    events=sample['events'];target=sample['fedRevision'];published=[e for e in events if e['stage']=='FRAME_PUBLISHED' and e['revision']==target]
    require(len(published)==1,'Duplicate/missing response-revision publication')
    pub=published[0];callbacks=[];active=None;prefix=sample.get('prefixEvents',[]);clipped=[];seen_start=False
    require(events and all(a['seq']<b['seq'] for a,b in zip(prefix+events,(prefix+events)[1:])),'Callback event sequence invalid')
    for e in prefix+events:
        if e['stage']=='INFRAME_CALLBACK_START':
            require(active is None,'Nested or unclosed callback start');active=e;seen_start=True
        elif e['stage']=='INFRAME_CALLBACK_END':
            if active is None:
                # takeLast(256) may begin INSIDE an older callback. This is not a missing
                # marker in the measured window. Preserve only that one leading prefix end.
                require(len(prefix)==256 and not seen_start and not clipped and e['seq']<events[0]['seq'],'Callback end without start')
                clipped.append(e);continue
            callbacks.append((active,e));active=None
    containing=[(a,b) for a,b in callbacks if a['seq']<pub['seq']<b['seq']]
    result=dict(phase=sample['phase'],condition=sample['condition'],publishedSeq=pub['seq'],publishedBootNs=pub['ns'],
        publicationUiFrames=index.ui_for(pub['ns']),callbackPublication=False,fallbackPublication=False,clippedPrefixCallbackEnds=clipped)
    def exactly(stage,lo,hi):
        matches=[e for e in events if e['stage']==stage and lo<e['seq']<hi];require(len(matches)==1,'Expected marker '+stage);return matches[0]
    if sample['condition']=='refresh':
        require(len(containing)==1,'Ordinary response was not published in its callback')
        a,b=containing[0];eligible=exactly('INFRAME_ELIGIBLE',a['seq'],b['seq']);call=exactly('INFRAME_PUBLISH_CALL',a['seq'],pub['seq'])
        done=exactly('INFRAME_PUBLISH_RESULT',pub['seq'],b['seq'])
        require(eligible.get('flag') is True and done.get('flag') is True and b.get('flag') is True,'Ordinary callback did not succeed')
        frames=index.ui_for(a['ns']);require(len(frames)==1 and len(result['publicationUiFrames'])==1 and frames[0]['token']==result['publicationUiFrames'][0]['token'],'Publication not in same Choreographer callback frame')
        require(not any(e['stage']=='INFRAME_FALLBACK_CALL' for e in events),'Duplicate ordinary fallback call')
        result.update(callbackPublication=True,callbackToken=frames[0]['token'],callbackStart=a['ns'],callbackEnd=b['ns'],callSeq=call['seq'])
    else:
        require(not containing,'SYNC control published in callback')
        starts=[e for e in events if e['stage']=='INFRAME_FALLBACK_SYNC_START' and e['seq']<pub['seq']]
        require(len(starts)==1 and starts[0].get('flag') is True,'SYNC control did not take active original guard')
        start=starts[0];done=exactly('INFRAME_FALLBACK_SYNC_END',start['seq'],pub['seq']);call=exactly('INFRAME_FALLBACK_CALL',done['seq'],pub['seq'])
        require(done.get('flag') is True,'Original synchronization guard did not finish')
        require(not any(e['stage']=='INFRAME_PUBLISH_CALL' for e in events),'SYNC bypassed callback guard')
        result.update(fallbackPublication=True,syncGuardNs=done['ns']-start['ns'],callSeq=call['seq'])
    return result
def report(text,manifest,device,installed):
    guard();cases,cleanup=validate_cases(text);save(OUT/'original-cases.json',cases);groups=[];passed=True
    for method,c in zip(METHODS,cases):
        raw=(ci.OUT/'latency-traces'/(method+'.pftrace')).read_bytes();tr=identity.parse_trace(raw,c['pid']);require(not tr['faults'],'Trace integrity failed');index=identity.FrameIndex(tr);rows=[]
        for w in c['samples']:
            try:rows.append(dict(passed=True,**classify(w,index)))
            except ValueError as error:rows.append(dict(phase=w['phase'],condition=w['condition'],passed=False,error=str(error)));passed=False
        groups.append(dict(case=c['case'],rows=rows,traceSha256=tr['traceSha256'],traceBytes=len(raw),sequenceInitialMarkers=tr['sequenceInitialMarkers']))
    receipt=json.loads((OUT/'source-receipt.json').read_bytes());require(len(receipt['jvm'])==49,'JVM prerequisite missing')
    summary=[dict(case=g['case'],windows=len(g['rows']),passed=sum(r['passed'] for r in g['rows']),
        callbackPublications=sum(r.get('callbackPublication',False) for r in g['rows']),fallbackPublications=sum(r.get('fallbackPublication',False) for r in g['rows'])) for g in groups]
    value=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,candidateIsolated=True,optimizationPerformanceMeasured=False,
        boundaryMechanismPassed=passed,protocolSha256=PROTOCOL_SHA,sourceReceipt=receipt,manifest=manifest,device=device,installedApkHashes=installed,
        cases=cases,cleanup=cleanup,groups=groups,summary=summary,note='Candidate+trace-only probe;49 JVM and fixed32 native mechanism windows,not a performance A/B or production adoption.')
    save(OUT/'report.json',value);compact={k:v for k,v in value.items() if k not in ('sourceReceipt','manifest','cases','groups')};compact['jvmMethods']=len(receipt['jvm']);compact['reportSha256']=ci.digest(OUT/'report.json')
    save(OUT/'summary.json',compact);real.wire.legacy.compact('In-frame publication boundaries',json.dumps(compact,separators=(',',':')).encode())
    require(passed,'Candidate mechanism/boundary failure; all original windows kept, no retries')
    return value
def run():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android boundary verification only in CI');guard();ci.sources=sources
    manifest=json.loads((ci.OUT/'manifest.json').read_bytes());require(manifest.get('environmentSources')==sources(),'Restored environment differs')
    files=instrument(candidate());expected=dict(sources())
    for name,text in files.items():expected[name]=sha(text.encode())
    require(manifest['sources']==expected,'Candidate/probe APK source mismatch')
    old=ci.real_clock;ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS='me.rerere.rikkahub.pipeline.TerminalInFramePublicationTest',METHODS=METHODS,report=report)
    try:ci.emulator(operation=lambda:ci.run_verified_bundle(manifest,'realClock',emit_evidence=False))
    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
        for name in ('instrumentation.log','pipeline-logcat.txt'):
            p=ci.OUT/name
            if p.exists():ci.notice('error','In-frame boundary '+name,ci.failure_excerpt(p))
        raise
    finally:ci.real_clock=old
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','build','run'));a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        elif a.action=='build':build()
        else:run()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','In-frame publication stopped',type(e).__name__+': '+str(e)[:1900]);raise SystemExit(1)
