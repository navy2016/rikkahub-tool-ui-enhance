"""306-bound physical row width/height product candidate. Local guards only; Kotlin/Android work is CI-only."""
import argparse,base64,collections,difflib,hashlib,json,os,re,shutil,subprocess,zlib
from contextlib import contextmanager
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'benchmarks/terminal-physical-metrics-b1'
OUT=ROOT/'artifacts/terminal-physical-metrics-b1'
IDENTITY_SHA='a7871aa871fc2508a654dc60aa1c07e90ca48626a9b29d877cbee6d45f5f75c1'
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
    raw=(BASE/'identity.json').read_bytes();require(digest(raw)==IDENTITY_SHA,'B1 identity changed')
    identity=json.loads(raw);raw=(BASE/'protocol.json').read_bytes()
    require(digest(raw)==identity['protocolSha256'],'B1 protocol changed');return identity,json.loads(raw)
def validate_assets():
    identity,p=spec();patch=[]
    for name,item in identity['files'].items():
        original=git('show',p['baseProductionSha']+':'+name);raw=(ROOT/item['asset']).read_bytes()
        require(digest(original)==item['base'] and digest(raw)==item['candidate'],'B1 candidate source hash differs:'+name)
        patch.extend(difflib.unified_diff(original.decode().splitlines(keepends=True),raw.decode().splitlines(keepends=True),fromfile='a/'+name,tofile='b/'+name))
    require(''.join(patch).encode()==(BASE/'candidate.patch').read_bytes(),'B1 patch/assets disagree')
    require(digest((BASE/'candidate.patch').read_bytes())==identity['patchSha256'],'B1 patch changed')
    require(digest((ROOT/'benchmarks/terminal-inframe-final-regression/plan.json').read_bytes())==identity['baselineTestsSha256'],'Historical plan changed')
    for item in p['unitInventory']+p['deviceInventory']+[p['candidateUnit']]:
        raw=(ROOT/item.get('asset',item['file'])).read_bytes();require(digest(raw)==item['sha256'],'B1 test source changed:'+item['file'])
        methods=re.findall(r'@Test(?:\([^)]*\))?\s+fun\s+(\w+)\(',raw.decode())
        require(len(item['methods'])==len(set(item['methods'])) and set(item['methods'])<=set(methods),'B1 test methods missing')
    require(sum(len(x['methods']) for x in p['unitInventory'])==504 and len(p['candidateUnit']['methods'])==25,'B1 JVM inventory differs')
    require(sum(len(x['methods']) for x in p['deviceInventory'])==28,'B1 device inventory differs')
    texts={Path(n).name:(ROOT/v['asset']).read_text() for n,v in identity['files'].items()}
    for symbol in ('parkedMainHistory','prepareFullVirtualHistory','independentMainHistory','historyMetrics','historyProof'):
        require(symbol not in ''.join(texts.values()),'Rejected lifecycle mutation present:'+symbol)
    require(set(texts)=={'TerminalTranscriptWidthIndex.kt','TerminalPhysicalMeasurements.kt','TerminalViewportBinding.kt',
        'TerminalPreparedWidth.kt','TerminalFullVirtualTranscript.kt'},'B1 source scope differs')
    full=texts['TerminalFullVirtualTranscript.kt'];name=next(n for n in identity['files'] if n.endswith('/TerminalFullVirtualTranscript.kt'))
    old=git('show',p['baseProductionSha']+':'+name).decode()
    require(full==old.replace('binding.widthCheckpoint)','binding.widthCheckpoint,\n        screenWidths = bound.physicalMeasurements)',1),
        'B1 changed rendering/blank/selection instead of only wiring the product')
    prepared=texts['TerminalPreparedWidth.kt']
    for marker in ('screenWidths: TerminalScreenWidthSource? = null','metrics.checkpointProof()',
        'candidate.width(frame, metrics, screenWidths)', 'index.width(frame, metrics, screenWidths, measurer::width)',
        'index.requiredHistoryMeasurements(frame, metrics)', 'latestRequest.value !== requestToken',
        'index.acceptComputed(candidate, expectedEpoch, frame)','terminalColdWidthWorker.withLock','return null',
        'LaunchedEffect(index, key, metrics, restored, completed)'):
        require(marker in prepared,'B1 prepared-width invariant missing:'+marker)
    index=texts['TerminalTranscriptWidthIndex.kt']
    require(index.count('private val widest = ArrayDeque<Candidate>()')==1,'B1 has multiple history queues')
    for method,end in (('private fun sourceOf','private fun canReuse'),('private fun canReuse','    /**\n     * A committed'),
        ('fun retainFor','    /** Existing callers')):
        original=git('show',p['baseProductionSha']+':'+next(n for n in identity['files'] if n.endswith('/TerminalTranscriptWidthIndex.kt'))).decode()
        right=original.index('    fun width(',original.index(method)) if method=='fun retainFor' else original.index(end,original.index(method))
        require(index[index.index(method):index.index(end,index.index(method))].strip()==original[original.index(method):right].strip(),
            'B1 changed306history lifecycle:'+method)
    physical=texts['TerminalPhysicalMeasurements.kt']
    for marker in ('private val frameReference = WeakReference(frame)','frameReference.get() === frame',
        'metricKey == metric','value.text != text','frame.rows[frame.historyCount + screenIndex].text != text',
        'intrinsic.hasStaleResolvedFonts','ceil(width).toInt()','Collections.unmodifiableMap(next)',
        'SideEffect { cache.product = product }','rememberTextMeasurer(cacheSize = 0)'):
        require(marker in physical,'B1 physical validity missing:'+marker)
    require('size.width' not in physical.replace('TextLayoutResult.size.width',''),'B1 uses a constraint-clamped width')
    require('val gestures: TerminalViewportGestures' in texts['TerminalViewportBinding.kt'] and
        'measurements.acknowledgeChanges()' in texts['TerminalViewportBinding.kt'],'B1 geometry/writer contract missing')
    return identity,p
def guard():
    identity,p=validate_assets()
    require(git('rev-parse','HEAD:app/src/main').decode().strip()==p['baseTree'],'B1 production tree is not306')
    require(not git('diff','--name-only',p['baseProductionSha'],'--','app/src/main','app/build.gradle.kts','gradle/libs.versions.toml','benchmarks/terminal-viewport-tests/build.gradle.kts').strip(),'B1 live production/build modified')
    for item in (p['candidateUnit'],p['candidateView']):require(not (ROOT/item['file']).exists(),'Temporary B1 test leaked into live tree')
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
    for failure in problems[:8]:notice('error','B1 JUnit case rejected',failure)
    require(not problems,'B1 has failed/skipped/duplicate methods');return rows
def verify_inventory(rows,inventory):
    expected={(x['classname'],method) for x in inventory for method in x['methods']};actual={(x['classname'],x['name']) for x in rows}
    require(actual==expected,'B1 method inventory mismatch: missing='+str(sorted(expected-actual))[:1200]+' unexpected='+str(sorted(actual-expected))[:800])
def unit():
    ci_only();folder=OUT/'unit';folder.mkdir(parents=True,exist_ok=False)
    with overlay('unit') as (identity,p):
        command=['bash','.github/scripts/run-terminal-gradle.sh','-PterminalReleaseTests=true',':app:testReleaseUnitTest']
        for item in p['unitInventory']+[p['candidateUnit']]:command+=['--tests',item['classname']]
        result=subprocess.run(command+['--stacktrace'],cwd=ROOT,timeout=1800)
        paths=list((ROOT/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
        for path in paths:shutil.copyfile(path,folder/path.name)
        rows=read_junit(paths);verify_inventory(rows,p['unitInventory']+[p['candidateUnit']]);require(result.returncode==0,'B1 Release JVM invocation failed')
        report=dict(identity_receipt(identity,p),cases=rows,count=len(rows),passed=True,originalMethods=504,newMethods=25,
            oracle='scalar-only; not Android shaping or performance');save(folder/'report.json',report)
    compact('B1 physical metrics JVM evidence',{k:v for k,v in report.items() if k!='cases'})
def build_fixture():
    ci_only()
    with overlay('device') as (identity,p):
        subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalViewportTests=true',
            ':benchmarks:terminal-viewport-tests:assembleRelease',':benchmarks:terminal-viewport-tests:assembleReleaseAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
        generated=ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources';copied={}
        for path in generated.rglob('*.kt'):
            source=ROOT/'app/src/main/java'/path.relative_to(generated);require(path.read_bytes()==source.read_bytes(),'B1 fixture source mismatch:'+str(source))
            copied[str(source.relative_to(ROOT))]=digest(path.read_bytes())
        require(set(identity['files'])<=set(copied),'B1 component fixture missed candidate file')
        save(OUT/'fixture-source.json',dict(identity_receipt(identity,p),copiedSources=copied,pageSchedulerNotInFixture=True))
def scenes(log,p):
    records=[]
    for line in log.splitlines():
        marker='PHYSICAL_METRICS_VIEW '
        if marker in line:records.append(json.loads(line.split(marker,1)[1]))
    names=[v['scene'] for v in records]
    require(collections.Counter(names)==collections.Counter(p['requiredDeviceScenes']),'B1 device lifecycle scenes missing/duplicate/unexpected')
    for record in records:
        scene=record['scene']
        expected=int(scene.split('-')[0]) if scene.endswith('-main-return') else \
            1000 if scene in ('font-size','density-scale','rtl','columns','custom-font','packaged-return') else 1 if scene.endswith('-fifo') else 0
        require(record['historyDelta']==expected,'B1 changed306history measurement:'+scene)
        require(0<=record['writers']<=1 and 0<=record['physicalRows']<=80 and record['performanceMeasured'] is False,
            'B1 retention/writer/performance invariant:'+scene)
        require(all(record[k]>=0 for k in ('sharedScreenDelta','fallbackScreenDelta','oracleComparisons','rejectedProducts')),'Negative B1 counter')
        if scene.endswith(('-main-edit','-alt-enter','-alt-redraw')) or scene in ('font-size','density-scale','rtl','columns','packaged-return'):
            require(record['sharedScreenDelta']==24 and record['fallbackScreenDelta']==0,'B1 expected physical-width reuse:'+scene)
        if scene.endswith('-main-return'):
            require(record['sharedScreenDelta']>=24 and record['fallbackScreenDelta']==0,'B1 return shares screen only')
        if scene.endswith('-fifo'):require(record['sharedScreenDelta']==1 and record['fallbackScreenDelta']==0,'B1 FIFO reuse differs')
        if scene in ('custom-font','same-revision'):require(record['sharedScreenDelta']==0,'B1 unexpected sharing:'+scene)
        if scene=='glyph-metrics-oracle':require(record['oracleComparisons']==288,'B1 glyph matrix incomplete')
        if scene=='compatibility-oracle':require(record['oracleComparisons']==30 and record['rejectedProducts']==30,'B1 compatibility matrix incomplete')
        if scene in ('blank-held','same-frame-forced-blank','remount'):require(record['rejectedProducts']>=1,'B1 stale/grace proof was not rejected')
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
            result=subprocess.run(['python3','-B','.github/scripts/run-terminal-with-timeout.py','900','bash','.github/scripts/run-terminal-gradle.sh',
                '-PterminalViewportTests=true',':benchmarks:terminal-viewport-tests:connectedReleaseAndroidTest',
                '-Pandroid.testInstrumentationRunnerArguments.class='+classes,'--stacktrace'],cwd=ROOT,timeout=1000)
        finally:
            with (folder/'lifecycle-logcat.txt').open('w') as stream:
                subprocess.run(['adb','logcat','-d','-v','threadtime','TerminalPhysicalMetrics:I','TerminalFullVirtual:I','AndroidRuntime:E','*:S'],stdout=stream,timeout=40)
            results=ROOT/'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results'
            for n,path in enumerate(sorted(results.rglob('TEST-*.xml'))):shutil.copyfile(path,folder/(str(n)+'-'+path.name))
        rows=read_junit(folder.glob('*-TEST-*.xml'));verify_inventory(rows,p['deviceInventory'])
        require(result is not None and result.returncode==0,'B1 Release device invocation failed')
        records=scenes((folder/'lifecycle-logcat.txt').read_text(),p)
        report=dict(identity_receipt(identity,p),cases=rows,count=len(rows),scenes=records,passed=True,newMethods=7,originalMethods=21,
            realPTYPageTested=False,full182ViewportRegression=False);save(folder/'report.json',report)
    compact('B1 physical metrics device evidence',{k:v for k,v in report.items() if k!='cases'})
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
        notice('error','B1 physical metrics validation rejected',str(error)[:3000]);raise SystemExit(1)
