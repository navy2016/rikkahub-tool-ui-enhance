"""P2 scalar MAIN checkpoint candidate correctness gate; no performance or production adoption."""
import argparse,hashlib,importlib.util,json,os,shutil,subprocess
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2];BASE=ROOT/'benchmarks/terminal-width-recovery-candidate'
OUT=ROOT/'artifacts/terminal-width-recovery-candidate';PATCH=BASE/'candidate.patch'
UNIT='app/src/test/java/me/rerere/rikkahub/data/container/TerminalWidthRecoveryCandidateTest.kt'
TEST='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalWidthRecoveryCandidateViewTest.kt'
sp=importlib.util.spec_from_file_location('recovery_baseline',Path(__file__).with_name('terminal-width-recovery.py'))
baseline=importlib.util.module_from_spec(sp);sp.loader.exec_module(baseline)
ci=baseline.ci;require=baseline.require;save=baseline.save
def spec():return json.loads((BASE/'sources.json').read_bytes())
def guard():
    baseline.guard();require(spec()['baseTree']==baseline.TREE and len(spec()['files'])==3,'P2 candidate scope differs')
    for path,values in spec()['files'].items():require(ci.digest(ROOT/path)==values['base'],'Live source not unchanged base:'+path)
    return spec()
def install(directory):
    guard();directory=Path(directory).resolve()
    require(directory==(ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources').resolve(),'Not generated fixture path')
    for path,values in spec()['files'].items():require(ci.digest(directory/Path(path).relative_to('app/src/main/java'))==values['base'],'Copied source already modified')
    command=['git','apply','-p5','--directory='+str(directory.relative_to(ROOT)),str(PATCH)]
    subprocess.run(command[:2]+['--check']+command[2:],cwd=ROOT,check=True);subprocess.run(command,cwd=ROOT,check=True)
    copies={}
    for path,values in spec()['files'].items():
        name=Path(path).relative_to('app/src/main/java');f=directory/name
        require(ci.digest(f)==values['candidate'],'Candidate generated hash differs')
        destination=OUT/'generated'/name;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,destination)
        copies[path]=values
    save(OUT/'source-receipt.json',dict(sourceSha=os.environ.get('GITHUB_SHA'),runId=os.environ.get('GITHUB_RUN_ID'),
        productionChanged=False,productionTree=baseline.TREE,files=copies))
def units():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android/JVM compilation is CI-only');guard()
    before={p:(ROOT/p).read_bytes() for p in spec()['files']}
    try:
        subprocess.run(['git','apply','--check',str(PATCH)],cwd=ROOT,check=True);subprocess.run(['git','apply',str(PATCH)],cwd=ROOT,check=True)
        shutil.copyfile(BASE/'TerminalWidthRecoveryCandidateTest.kt',ROOT/UNIT)
        command=['bash','.github/scripts/run-terminal-gradle.sh','-PterminalReleaseTests=true',':app:testReleaseUnitTest']
        for name in ('TerminalWidthRecoveryCandidateTest','TerminalTranscriptWidthIndexTest','TerminalFullVirtualTest'):
            command+=['--tests','me.rerere.rikkahub.data.container.'+name]
        subprocess.run(command+['--stacktrace'],cwd=ROOT,check=True,timeout=1800)
        cases=[]
        for path in (ROOT/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'):
            dest=OUT/'unit'/path.name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,dest)
            for c in ET.parse(path).getroot().iter('testcase'):
                require(not any(c.find(k) is not None for k in ('failure','error','skipped')),'Candidate JVM contract failed')
                cases.append((c.get('classname'),c.get('name')))
        require(len(cases)==45 and sum(c.endswith('.TerminalWidthRecoveryCandidateTest') for c,n in cases)==8,'45JVM gate inventory differs')
        save(OUT/'unit-report.json',dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],cases=cases))
    finally:
        for path,raw in before.items():(ROOT/path).write_bytes(raw)
        (ROOT/UNIT).unlink(missing_ok=True);guard()
def fixtures():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android compilation is CI-only');guard()
    require((OUT/'unit-report.json').is_file(),'Candidate JVM gate not complete')
    subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalViewportTests=true','-PterminalPipelineTests=true',
        '-PterminalWidthRecoveryCandidate=true','-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle',
        'terminalMechanismDependencies',':benchmarks:terminal-viewport-tests:assembleRelease',
        ':benchmarks:terminal-viewport-tests:assembleReleaseAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
    shutil.copyfile(ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json',OUT/'resolved-dependencies.json')
def validate(text):
    rows=baseline.real.records(text,'WIDTH_RECOVERY_CANDIDATE');done=baseline.real.records(text,'WIDTH_RECOVERY_CANDIDATE_DONE')
    disposed=baseline.real.records(text,'WIDTH_RECOVERY_CANDIDATE_DISPOSE')
    require([(r['size'],r['scene']) for r in rows]==[(n,s) for n in (1000,10000) for s in baseline.MECHANISM_SCENES],'32candidate records required')
    require(done==[dict(records=32,passed=True,performanceMeasured=False)] and len(disposed)==2,'Candidate completion/cleanup missing')
    for r in rows:
        expected=r['size'] if r['scene'] in ('initial','fontMain','returnFont','returnColumns') else 1 if r['scene']=='mainFifo' else 0
        require(r['acceptedHistoryMeasurements']==expected and r['requiredHistoryAfterReady']==0,'Candidate reuse/invalidation work differs:'+r['scene'])
        require(r['timingMeasured'] is False and 0<r['rowStates']<=80 and 0<r['measuredRows']<=128 and r['writers']<=1,'Invalid candidate geometry')
        if r['scene'].startswith('enterAlt'):require(0<r['parkedCandidates']<=r['size'] and r['scalarCandidates']==0,'No bounded parked MAIN checkpoint')
        else:require(r['parkedCandidates']==0,'Parked state not cleared after restore/invalidation')
        if r['alternate']:require(r['screenWidthKeys']==0,'Rejected ALT row-width cache was added')
    require({d['size'] for d in disposed}=={1000,10000} and all(d['measuredRows']==0 and d['parkedAfterDispose']==0 and
        0<d['heldBeforeDispose']<=d['size'] for d in disposed),'ALT disposal leaked parked checkpoint')
    return dict(rows=rows,done=done,disposed=disposed)
def report():
    guard();data=validate((OUT/'mechanism-logcat.txt').read_text());found=[];hashes={}
    generated=ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources'
    for path in generated.rglob('*.kt'):
        rel='app/src/main/java/'+path.relative_to(generated).as_posix()
        expected=spec()['files'].get(rel,{}).get('candidate',ci.digest(ROOT/rel))
        require(ci.digest(path)==expected,'Generated candidate source mismatch:'+rel);hashes[rel]=expected
    for path in (ROOT/'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(path).getroot().iter('testcase'):
            require(not any(c.find(k) is not None for k in ('failure','error','skipped')),'Candidate device method failed')
            found.append((c.get('classname'),c.get('name')))
    expected={'candidateMainAlternateWidthRecoveryCounters','fullVirtualAllScenesRemainVirtualWithRealGeometry',
        'fullVirtualLockedFontsRtlImeAndReentryPreserveTheStableAnchor','fullVirtualSelectionSystemMenuCopiesAWordAndOffscreenSelectAll',
        'fullVirtualSelectionHandleCrossesViewportWithoutLosingOffscreenText','fullVirtualMouseMappingIncludesOffscreenPhysicalOrigin',
        'fullVirtualModeSwitchKeepsOldChoicesAndMeasuredReadPosition'}
    require(len(found)==7 and {n for c,n in found}==expected,'Seven candidate device methods required')
    value=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,performanceMeasured=False,
        candidateSpec=spec(),generatedHashes=hashes,methods=found,**data)
    save(OUT/'report.json',value)
    compact={k:v for k,v in value.items() if k not in ('generatedHashes','candidateSpec')};compact['reportSha256']=ci.digest(OUT/'report.json')
    save(OUT/'summary.json',compact);baseline.real.wire.legacy.compact('P2 checkpoint candidate',json.dumps(compact,separators=(',',':')).encode())
def emulator():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android tests are CI-only');guard();ci.sources=baseline.sources
    sha,run=ci.identity();save(ci.OUT/'manifest.json',dict(sourceSha=sha,runId=run,sources=baseline.sources(),bootstrapMechanismOnly=True))
    def operation():
        env=dict(os.environ,TERMINAL_VIEWPORT_SUITE='widthRecoveryCandidate',TERMINAL_VIEWPORT_GROUP='widthRecoveryCandidate')
        subprocess.run(['python3','.github/scripts/run-terminal-with-timeout.py','900','bash','benchmarks/terminal-viewport-tests/run-ci.sh'],
            cwd=ROOT,env=env,check=True,timeout=930)
        report()
    ci.emulator(operation=operation)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','instrument','units','fixtures','emulator','report'));p.add_argument('--directory');a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        elif a.action=='instrument':require(a.directory is not None,'Directory required');install(a.directory)
        elif a.action=='units':units()
        elif a.action=='fixtures':fixtures()
        elif a.action=='emulator':emulator()
        else:report()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','P2 checkpoint candidate stopped',type(e).__name__+': '+str(e)[:1800]);raise SystemExit(1)
