"""306-bound detached visible geometry proof candidate. Local guards only; Kotlin/Android work is CI-only."""
import argparse,base64,collections,difflib,hashlib,json,os,re,shutil,subprocess,zlib
from contextlib import contextmanager
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'benchmarks/terminal-geometry-c1'
OUT=ROOT/'artifacts/terminal-geometry-c1'
IDENTITY_SHA='4b7e565a88d3f531c7c261ec9e3cb1f9ec2bfb3bf2e981d653863591fc9a4ab9'
def digest(raw):return hashlib.sha256(raw).hexdigest()
def require(ok,message):
    if not ok:raise ValueError(message)
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def save(path,value):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def notice(level,title,text):print(f'::{level} title={title}::'+str(text).replace('%','%25').replace('\n','%0A').replace('\r','%0D'),flush=True)
def compact(title,value):
    raw=json.dumps(value,separators=(',',':')).encode();text=base64.b64encode(zlib.compress(raw,9)).decode()
    parts=[text[i:i+2900] for i in range(0,len(text),2900)];require(len(parts)<=8,'Compact receipt exceeds annotation budget')
    for i,part in enumerate(parts,1):notice('notice',f'{title} {i}/{len(parts)}','zlib-base64 sha256='+digest(raw)+'\n'+part)
def spec():
    raw=(BASE/'identity.json').read_bytes();require(digest(raw)==IDENTITY_SHA,'C1 identity changed')
    identity=json.loads(raw);raw=(BASE/'protocol.json').read_bytes()
    require(digest(raw)==identity['protocolSha256'],'C1 protocol changed');return identity,json.loads(raw)
def validate_assets():
    identity,p=spec();patch=[]
    for name,item in identity['files'].items():
        original=git('show',p['baseProductionSha']+':'+name);raw=(ROOT/item['asset']).read_bytes()
        require(digest(original)==item['base'] and digest(raw)==item['candidate'],'C1 candidate hash differs:'+name)
        patch.extend(difflib.unified_diff(original.decode().splitlines(keepends=True),raw.decode().splitlines(keepends=True),fromfile='a/'+name,tofile='b/'+name))
    require(''.join(patch).encode()==(BASE/'candidate.patch').read_bytes(),'C1 patch/assets disagree')
    require(digest((BASE/'candidate.patch').read_bytes())==identity['patchSha256'],'C1 patch changed')
    require(digest((ROOT/'benchmarks/terminal-inframe-final-regression/plan.json').read_bytes())==identity['baselineTestsSha256'],'Historical baseline changed')
    for item in p['unitInventory']+p['deviceInventory']+[p['candidateUnit']]:
        raw=(ROOT/item.get('asset',item['file'])).read_bytes();require(digest(raw)==item['sha256'],'C1 test hash differs:'+item['file'])
        methods=re.findall(r'@Test(?:\([^)]*\))?\s+fun\s+(\w+)\(',raw.decode())
        require(len(item['methods'])==len(set(item['methods'])) and set(item['methods'])<=set(methods),'C1 method missing/duplicate')
    require(sum(len(x['methods']) for x in p['unitInventory'])==504 and len(p['candidateUnit']['methods'])==24 and p['expectedJVM']==528,'C1 JVM inventory differs')
    require(sum(len(x['methods']) for x in p['deviceInventory'])==44 and p['expectedDevice']==44,'C1 device inventory differs')
    texts={Path(n).name:(ROOT/v['asset']).read_text() for n,v in identity['files'].items()}
    require(set(texts)=={'TerminalItemViewport.kt','TerminalViewportController.kt','TerminalViewportBinding.kt','TerminalLazyItemMeasurements.kt'},'C1 source scope differs')
    original={Path(n).name:git('show',p['baseProductionSha']+':'+n).decode() for n in identity['files']}
    controller=original['TerminalViewportController.kt']
    for before,after in (
        ('current.copy(fullVirtual = full.copy(retainedScreenBottom = fullImeScreenBottom))','current.withFullVirtualPolicy(full.copy(retainedScreenBottom = fullImeScreenBottom))'),
        ('it.copy(fullVirtual = it.fullVirtual.copy(retainedScreenBottom = fullImeScreenBottom))','it.withFullVirtualPolicy(it.fullVirtual.copy(retainedScreenBottom = fullImeScreenBottom))'),
        ('observation.copy(fullVirtual = full.copy(retainedScreenBottom = fullImeScreenBottom))','observation.withFullVirtualPolicy(full.copy(retainedScreenBottom = fullImeScreenBottom))')):
        require(controller.count(before)==1,'C1 controller anchor differs');controller=controller.replace(before,after,1)
    require(texts['TerminalViewportController.kt']==controller,'C1 changed controller intent instead of copy transport')
    require(texts['TerminalViewportBinding.kt']==original['TerminalViewportBinding.kt'].replace(
        'observation.copy(fullVirtual = policy.copy(avoidIme = exactAvoidance))',
        'observation.withFullVirtualPolicy(policy.copy(avoidIme = exactAvoidance))',1),'C1 changed scheduling/live observation')
    old=original['TerminalLazyItemMeasurements.kt'];new=texts['TerminalLazyItemMeasurements.kt']
    expected=old.replace('import me.rerere.rikkahub.data.container.TerminalVisibleRow\n',
        'import me.rerere.rikkahub.data.container.TerminalVisibleRow\nimport me.rerere.rikkahub.data.container.TerminalVisibleRowSnapshot\n',1)
    expected=expected.replace('        if (rows.isEmpty() || rows.zipWithNext().any { (a, b) -> a.index >= b.index || a.bottomPx > b.topPx }) return null\n        return TerminalItemViewport(frame, pass.metricKey, rows, info.viewportSize.height,\n            cellHeightPx, tailPaddingPx, state.canScrollBackward, state.canScrollForward, policy)',
        '        if (rows.isEmpty()) return null\n        val geometry = TerminalVisibleRowSnapshot.capture(rows, frame.rows.size) ?: return null\n        return TerminalItemViewport(frame, pass.metricKey, geometry, info.viewportSize.height,\n            cellHeightPx, tailPaddingPx, state.canScrollBackward, state.canScrollForward, policy)',1)
    require(new==expected,'C1 changed live key/height/text/physical checks or old backend')
    value=texts['TerminalItemViewport.kt'];snapshot=value[value.index('internal class TerminalVisibleRowSnapshot'):value.index('/**\n * One completed layout')]
    for token in ('private constructor','private val values: Array<TerminalVisibleRow>','private val frameRowCount: Int',
        'rows.toTypedArray()','previous.index >= row.index || previous.bottomPx > row.topPx','row.index >= rowCount'):
        require(token in snapshot,'C1 scalar proof invariant missing:'+token)
    for token in ('WeakReference','RenderFrame','LazyListState','mutableStateOf','remember','TextLayoutResult','MutableList','lazy('):
        require(token not in snapshot,'C1 geometry snapshot retains/reuses forbidden ownership:'+token)
    require('fullVirtual == null || rows !is TerminalVisibleRowSnapshot || !rows.validatedFor(frame.rows.size)' in value,'C1 bypasses legacy/bounds validation')
    require('fullVirtual == policy) return this' in value and 'return copy(fullVirtual = policy)' in value,'C1 policy transition changed')
    require(value[value.index('    fun lineId('):]==original['TerminalItemViewport.kt'][original['TerminalItemViewport.kt'].index('    fun lineId('):],
        'C1 changed target,anchor,geometry math or frame identity')
    require(not git('diff','--name-only',p['priorClosureSha'],'--','benchmarks/terminal-main-width-lifecycle',
        'benchmarks/terminal-main-width-performance','benchmarks/terminal-physical-metrics-b1','benchmarks/terminal-physical-metrics-performance',
        '.github/scripts/terminal-frame-identity.py','.github/scripts/run-terminal-pipeline.py','.github/scripts/run-terminal-pipeline-paired.py',
        '.github/scripts/terminal-physical-metrics-b1.py','.github/scripts/terminal-physical-metrics-performance.py','app/src/terminaltestAndroidTest').strip(),
        'C1 altered frozen historical candidate,driver or parser')
    return identity,p
def guard():
    identity,p=validate_assets()
    require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['baseTree'],'C1 production tree is not306')
    require(not git('diff','--name-only',p['baseProductionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml','benchmarks/terminal-viewport-tests/build.gradle.kts').strip(),'C1 live production/build modified')
    for item in (p['candidateUnit'],p['candidateView']):require(not (ROOT/item['file']).exists(),'Temporary C1 test leaked into live tree')
    require(not git('ls-files','--others','--exclude-standard','app/src/main').strip(),'Untracked production source')
    return identity,p
@contextmanager
def overlay(kind):
    identity,p=guard();require(kind in ('unit','device'),'Unknown overlay kind')
    additions=[p['candidateUnit']] if kind=='unit' else [p['candidateView']]
    before={name:(ROOT/name).read_bytes() for name in identity['files']}
    try:
        for name,item in identity['files'].items():(ROOT/name).write_bytes((ROOT/item['asset']).read_bytes())
        for item in additions:(ROOT/item['file']).write_bytes((ROOT/item['asset']).read_bytes())
        for name,item in identity['files'].items():require(digest((ROOT/name).read_bytes())==item['candidate'],'Bad live overlay bytes')
        yield identity,p
    finally:
        for name,raw in before.items():(ROOT/name).write_bytes(raw)
        for item in additions:(ROOT/item['file']).unlink(missing_ok=True)
        guard()
def ci_only():require(os.environ.get('GITHUB_ACTIONS')=='true','Kotlin/Android execution is CI-only')
def identity_receipt(identity,p):
    return dict(sourceSha=os.environ.get('GITHUB_SHA',git('rev-parse','HEAD').decode().strip()),
        runId=os.environ.get('GITHUB_RUN_ID'),baseProductionSha=p['baseProductionSha'],baseTree=p['baseTree'],
        identitySha256=IDENTITY_SHA,protocolSha256=identity['protocolSha256'],candidateHashes={n:v['candidate'] for n,v in identity['files'].items()},
        productionChanged=False,performanceMeasured=False,formalReleaseBuilt=False)
def read_junit(paths):
    rows=[];seen=set();problems=[]
    for path in sorted(paths):
        for case in ET.parse(path).getroot().iter('testcase'):
            key=(case.get('classname'),case.get('name'))
            if key in seen:problems.append('Duplicate method '+str(key))
            seen.add(key);rows.append(dict(classname=key[0],name=key[1]))
            for tag in ('failure','error','skipped'):
                element=case.find(tag)
                if element is not None:problems.append(str(key)+': '+tag+': '+(element.text or element.get('message',''))[:900])
    for failure in problems[:8]:notice('error','C1 JUnit case rejected',failure)
    require(not problems,'C1 has failed/skipped/duplicate methods');return rows
def verify_inventory(rows,inventory):
    expected={(x['classname'],method) for x in inventory for method in x['methods']};actual={(x['classname'],x['name']) for x in rows}
    require(actual==expected,'C1 method inventory mismatch: missing='+str(sorted(expected-actual))[:1200]+' unexpected='+str(sorted(actual-expected))[:800])
def unit():
    ci_only();folder=OUT/'unit';folder.mkdir(parents=True,exist_ok=False)
    with overlay('unit') as (identity,p):
        command=['bash','.github/scripts/run-terminal-gradle.sh','-PterminalReleaseTests=true',':app:testReleaseUnitTest']
        for item in p['unitInventory']+[p['candidateUnit']]:command+=['--tests',item['classname']]
        result=subprocess.run(command+['--stacktrace'],cwd=ROOT,timeout=1800)
        paths=list((ROOT/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
        for path in paths:shutil.copyfile(path,folder/path.name)
        rows=read_junit(paths);verify_inventory(rows,p['unitInventory']+[p['candidateUnit']]);require(result.returncode==0,'C1 Release JVM invocation failed')
        report=dict(identity_receipt(identity,p),cases=rows,count=len(rows),passed=True,originalMethods=504,newMethods=24,
            oracle='scalar-only; not Android shaping or performance');save(folder/'report.json',report)
    compact('C1 geometry JVM evidence',{k:v for k,v in report.items() if k!='cases'})
def build_fixture():
    ci_only()
    with overlay('device') as (identity,p):
        subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalViewportTests=true',
            ':benchmarks:terminal-viewport-tests:assembleRelease',':benchmarks:terminal-viewport-tests:assembleReleaseAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
        generated=ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources';copied={}
        for path in generated.rglob('*.kt'):
            source=ROOT/'app/src/main/java'/path.relative_to(generated);require(path.read_bytes()==source.read_bytes(),'C1 fixture source mismatch:'+str(source))
            copied[str(source.relative_to(ROOT))]=digest(path.read_bytes())
        require(set(identity['files'])<=set(copied),'C1 component fixture missed candidate file')
        save(OUT/'fixture-source.json',dict(identity_receipt(identity,p),copiedSources=copied,pageSchedulerNotInFixture=True))
def scenes(log,p):
    records=[]
    for line in log.splitlines():
        marker='GEOMETRY_C1_VIEW '
        if marker in line:records.append(json.loads(line.split(marker,1)[1]))
    require(collections.Counter(x['scene'] for x in records)==collections.Counter(p['requiredDeviceScenes']),'C1 scenes missing/duplicate/unexpected')
    for r in records:
        scene=r['scene'];require(type(r['maximumWriters']) is int and 0<=r['maximumWriters']<=1 and r['geometryChecked'] is True and r['performanceMeasured'] is False,'C1 writer/geometry/claim mismatch:'+scene)
        for key in ('history','visibleRows','measuredRows','reusedObservations','rejectedReads'):
            require(type(r[key]) is int and r[key]>=0,'Invalid C1 counter:'+scene)
        legacy=scene in ('legacy-history','default-eager')
        require(r['fullVirtual'] is (not legacy),'Wrong C1 backend:'+scene)
        require(r['virtual'] is (scene!='default-eager'),'Wrong C1 virtual state:'+scene)
        require(r['alternate'] is (scene in ('alt-enter','alt-redraw')),'Wrong C1 screen identity:'+scene)
        if not legacy:require(0<r['visibleRows']<=128 and r['measuredRows']<=128,'Unbounded C1 visible geometry:'+scene)
        require(r['reusedObservations']==(256 if scene=='same-layout-policy' else 0),'C1 no-op identity proof differs')
        require(r['rejectedReads']==(4 if scene=='rejected-live-inputs' else 0),'C1 stale live-input rejection differs')
        expected=0 if scene in ('alt-enter','alt-redraw') else 10000 if scene in ('main-edit','main-return','fifo') else 500 if scene=='session-replacement' else 1000
        require(r['history']==expected,'C1 history changed:'+scene)
    return records
def device():
    ci_only();folder=OUT/'device';folder.mkdir(parents=True,exist_ok=False)
    with overlay('device') as (identity,p):
        classes=','.join(x['classname']+'#'+m for x in p['deviceInventory'] for m in x['methods'])
        for args in (['wait-for-device'],['logcat','-G','8M'],['logcat','-c']):subprocess.run(['adb',*args],check=True,timeout=40)
        for name in ('window_animation_scale','transition_animation_scale','animator_duration_scale'):
            subprocess.run(['adb','shell','settings','put','global',name,'1'],check=True,timeout=20)
        subprocess.run(['adb','shell','settings','put','secure','show_ime_with_hard_keyboard','1'],check=True,timeout=20)
        result=None
        try:
            result=subprocess.run(['python3','-B','.github/scripts/run-terminal-with-timeout.py','1500','bash','.github/scripts/run-terminal-gradle.sh',
                '-PterminalViewportTests=true',':benchmarks:terminal-viewport-tests:connectedReleaseAndroidTest',
                '-Pandroid.testInstrumentationRunnerArguments.class='+classes,'--stacktrace'],cwd=ROOT,timeout=1600)
        finally:
            with (folder/'lifecycle-logcat.txt').open('w') as stream:
                subprocess.run(['adb','logcat','-d','-v','threadtime','TerminalGeometryC1:I','TerminalFullVirtual:I','AndroidRuntime:E','*:S'],stdout=stream,timeout=40)
            results=ROOT/'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results'
            for n,path in enumerate(sorted(results.rglob('TEST-*.xml'))):shutil.copyfile(path,folder/(str(n)+'-'+path.name))
        rows=read_junit(folder.glob('*-TEST-*.xml'));verify_inventory(rows,p['deviceInventory'])
        require(result is not None and result.returncode==0,'C1 Release device invocation failed')
        records=scenes((folder/'lifecycle-logcat.txt').read_text(),p)
        report=dict(identity_receipt(identity,p),cases=rows,count=len(rows),scenes=records,passed=True,newMethods=7,originalMethods=37,
            realPTYPageTested=False,full182ViewportRegression=False);save(folder/'report.json',report)
    compact('C1 geometry device evidence',{k:v for k,v in report.items() if k!='cases'})
def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=('guard','unit','fixture-build','device'));args=parser.parse_args()
    if args.action=='guard':
        identity,p=guard();print(json.dumps(identity_receipt(identity,p),indent=2))
    elif args.action=='unit':unit()
    elif args.action=='fixture-build':build_fixture()
    else:device()
if __name__=='__main__':
    try:main()
    except (ValueError,OSError,subprocess.SubprocessError,ET.ParseError) as error:
        notice('error','C1 geometry validation rejected',str(error)[:3000]);raise SystemExit(1)
