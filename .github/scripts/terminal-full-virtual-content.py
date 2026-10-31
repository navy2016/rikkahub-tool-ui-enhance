"""P1 one-file ALT item-content identity candidate. Generated-fixture mechanism gate first."""
import argparse,hashlib,importlib.util,json,os,subprocess
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'benchmarks/terminal-full-virtual-alt-content'
OUT=ROOT/'artifacts/terminal-full-virtual-content'
sp=importlib.util.spec_from_file_location('p1_mechanism',Path(__file__).with_name('terminal-full-virtual-mechanism.py'))
p0=importlib.util.module_from_spec(sp);sp.loader.exec_module(p0)
SOURCE=p0.SOURCE
TEST='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalFullVirtualContentTest.kt'
require=p0.require
def spec():return json.loads((BASE/'sources.json').read_bytes())
def digest(b):return hashlib.sha256(b).hexdigest()
def guard():
    p0.guard();require(len(spec()['files'])==1 and 'app/src/main/java/'+SOURCE in spec()['files'],'Candidate must remain one scoped production file')
    return spec()
def instrument_candidate(text):
    expected=spec()['files']['app/src/main/java/'+SOURCE]['candidate']
    require(digest(text.encode())==expected,'P1 candidate bytes changed')
    text=text.replace('                LazyColumn(\n',
        '                SideEffect { TerminalFullVirtualMechanismProbe.definitionCommitted(itemContent, frame, screen) }\n                SideEffect { TerminalFullVirtualMechanismProbe.branchCommitted(ownedAlternate != null) }\n                LazyColumn(\n')
    text=text.replace('                    {\n                        items(count = frame.rows.size',
        '                    {\n                        TerminalFullVirtualMechanismProbe.intervalBuilt()\n                        items(count = frame.rows.size')
    text=text.replace('): LazyListScope.() -> Unit = {\n    items(',
        '): LazyListScope.() -> Unit = {\n    TerminalFullVirtualMechanismProbe.intervalBuilt()\n    items(')
    text=text.replace('}, contentType = { "terminal-virtual-row" }) { index ->\n',
        '}, contentType = { "terminal-virtual-row" }) { index ->\n                            TerminalFullVirtualMechanismProbe.itemEntered()\n')
    text=text.replace('contentType = { "terminal-virtual-row" }) { index ->\n        val row',
        'contentType = { "terminal-virtual-row" }) { index ->\n        TerminalFullVirtualMechanismProbe.itemEntered()\n        val row')
    text=text.replace('                            key(bound.pass.metricKey, row.lineId) {\n',
        '                            SideEffect { TerminalFullVirtualMechanismProbe.itemCommitted(row.lineId) }\n                            key(bound.pass.metricKey, row.lineId) {\n')
    text=text.replace('        key(metric, row.lineId) {\n',
        '        SideEffect { TerminalFullVirtualMechanismProbe.itemCommitted(row.lineId) }\n        key(metric, row.lineId) {\n')
    for marker in ('intervalBuilt()','itemEntered()','itemCommitted(row.lineId)'):require(text.count(marker)==2,'Missing two branch probes:'+marker)
    return text
def install(directory):
    guard();directory=Path(directory).resolve();expected=(ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources').resolve()
    require(directory==expected,'Not generated fixture destination');target=directory/SOURCE
    require(digest(target.read_bytes())==p0.BASE_SHA,'Generated input is not base production')
    # Strip a/app/src/main/java only; apply to the generated copy, never the live checkout.
    command=['git','apply','-p5','--directory='+str(directory.relative_to(ROOT)),str(BASE/'candidate.patch')]
    subprocess.run(command[:2]+['--check']+command[2:],cwd=ROOT,check=True)
    subprocess.run(command,cwd=ROOT,check=True)
    candidate=target.read_text();instrumented=instrument_candidate(candidate).encode();target.write_bytes(instrumented)
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'candidate-source.kt').write_text(candidate);(OUT/'instrumented-source.kt').write_bytes(instrumented)
    (OUT/'source-receipt.json').write_text(json.dumps(dict(sourceSha=os.environ.get('GITHUB_SHA'),runId=os.environ.get('GITHUB_RUN_ID'),
        productionTree=p0.TREE,productionChanged=False,baseSha256=p0.BASE_SHA,candidateSha256=digest(candidate.encode()),instrumentedSha256=digest(instrumented)),indent=2)+'\n')
def parse(text):
    normalized=text.replace('FULL_CONTENT_','FULL_MECHANISM_')
    rows,done=p0.parse_samples(normalized)
    for r in rows:
        expected=r['alternate'] and r['scene'] not in ('selectionOn','mouseOn')
        require(r['fastPath']==expected,'Wrong bounded-content branch:'+r['scene'])
        if r['scene'] in ('sameFrame','republish','text'):
            require(r['definitionChanges']==r['intervalBuilds']==r['unchangedItemCommits']==r['unchangedRowCommits']==r['changedParagraphs']==0,'Unchanged work not eliminated:'+r['scene'])
        if r['scene'] in ('insert','delete','font','scale','rtl','selectionOn','selectionOff','mouseOn','mouseOff','main','alternateAgain','resize'):
            require(r['definitionChanges']>0,'Required content invalidation missing:'+r['scene'])
    special=[json.loads(l.split('FULL_CONTENT_BOUNDARY ',1)[1]) for l in text.splitlines() if 'FULL_CONTENT_BOUNDARY ' in l]
    require(len(special)==2 and {s['case'] for s in special}=={'unownedFallback','sameFrameBlankGrace'} and all(s['passed'] for s in special),'Missing extra boundary checks')
    return rows,done,special
def report():
    guard();rows,done,boundaries=parse((OUT/'content-logcat.txt').read_text())
    versions=p0.dependency_summary(json.loads((OUT/'resolved-dependencies.json').read_bytes()))
    saved=json.loads((OUT/'source-receipt.json').read_bytes());candidate=(OUT/'candidate-source.kt').read_text();expected=instrument_candidate(candidate).encode()
    require(saved['instrumentedSha256']==digest(expected) and (OUT/'instrumented-source.kt').read_bytes()==expected,'Generated probe receipt changed')
    generated=ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources'
    for f in generated.rglob('*.kt'):
        require(f.read_bytes()==(expected if f==generated/SOURCE else (ROOT/'app/src/main/java'/f.relative_to(generated)).read_bytes()),'Unexpected generated-source delta')
    methods=[]
    for f in (ROOT/'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(f).getroot().iter('testcase'):
            require(not any(c.find(k) is not None for k in ('failure','error','skipped')),'P1 test failed/skipped')
            methods.append((c.get('classname'),c.get('name')))
    expected_methods={'fullVirtualContentIdentityAndWork','fullVirtualContentBoundaryChecks',
        'fullVirtualAllScenesRemainVirtualWithRealGeometry','fullVirtualLockedFontsRtlImeAndReentryPreserveTheStableAnchor',
        'fullVirtualSelectionSystemMenuCopiesAWordAndOffscreenSelectAll','fullVirtualSelectionHandleCrossesViewportWithoutLosingOffscreenText',
        'fullVirtualMouseMappingIncludesOffscreenPhysicalOrigin','fullVirtualModeSwitchKeepsOldChoicesAndMeasuredReadPosition'}
    require(len(methods)==8 and {n for c,n in methods}==expected_methods,'Wrong8method inventory')
    data=dict(suite='full-virtual-content-p1-mechanism',sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],
        productionChanged=False,performanceMeasured=False,sourceReceipt=saved,rows=rows,cleanup=done,boundaries=boundaries,
        methods=methods,dependencyVersions=versions,note='Candidate present only in generated fixture; counts/pixels/geometry are not phone or performance evidence. No width/cursor optimization.')
    (OUT/'report.json').write_text(json.dumps(data,indent=2)+'\n')
    summary={k:v for k,v in data.items() if k not in ('rows','dependencyVersions')};summary['textUpdates']=[r for r in rows if r['scene']=='text'];summary['reportSha256']=digest((OUT/'report.json').read_bytes())
    (OUT/'summary.json').write_text(json.dumps(summary,separators=(',',':')))
    sp=importlib.util.spec_from_file_location('p1_evidence',Path(__file__).with_name('terminal-tui-history-reports.py'));legacy=importlib.util.module_from_spec(sp);sp.loader.exec_module(legacy)
    legacy.compact('Full virtual P1 content',(OUT/'summary.json').read_bytes())
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','instrument','report'));p.add_argument('--directory');a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        elif a.action=='instrument':require(a.directory is not None,'Generated destination required');install(a.directory)
        else:report()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        message=(type(e).__name__+': '+str(e))[:2300].replace('%','%25').replace('\n','%0A').replace('\r','%0D')
        print('::error title=Full virtual P1 stopped::'+message);raise SystemExit(1)
