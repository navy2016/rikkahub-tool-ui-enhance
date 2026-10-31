"""P0 mechanism counters only: generated fixture overlay, not a production/performance candidate."""
import argparse,hashlib,importlib.util,json,os,re,subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'artifacts/terminal-full-virtual-mechanism'
SOURCE='me/rerere/rikkahub/ui/pages/container/TerminalFullVirtualTranscript.kt'
BASE_SHA='a09f5ff88732e304633226edda5673c28b91786cdca10a2e5321b6f41be067a6'
TREE='8caf50d5c40e228f1d5bcc09d3db505757568b68'
TEST='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalFullVirtualMechanismTest.kt'
PROBE='benchmarks/terminal-viewport-tests/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalFullVirtualMechanismProbe.kt'
SCENES=[('sameFrame',i) for i in range(3)]+[('republish',i) for i in range(3)]+[('text',i) for i in range(4)]+[(k,0) for k in (
    'cursor','insert','delete','font','scale','rtl','ime','imeHidden','selectionOn','selectionOff','mouseOn','mouseOff','main','alternateAgain','resize')]
def digest(raw):return hashlib.sha256(raw).hexdigest()
def require(ok,message):
    if not ok:raise ValueError(message)
def instrument_text(source):
    require(digest(source.encode())==BASE_SHA,'Unexpected production source before probe insertion')
    prefix='            CompositionLocalProvider(LocalTerminalPointerScope provides pointerScope) {\n'
    begin=source.index(prefix)+len(prefix)
    lazy=source.index('                LazyColumn(\n',begin)
    body_start=source.index('                ) {\n',lazy)+len('                ) {\n')
    tail='                }\n            }\n        }\n    }\n}\n'
    require(source.endswith(tail),'Full virtual terminal closure differs')
    body_end=len(source)-len(tail)
    body=source[body_start:body_end]
    body='                    TerminalFullVirtualMechanismProbe.intervalBuilt()\n'+body
    body=body.replace('                    }, contentType = { "terminal-virtual-row" }) { index ->\n',
        '                    }, contentType = { "terminal-virtual-row" }) { index ->\n                        TerminalFullVirtualMechanismProbe.itemEntered()\n')
    body=body.replace('                        key(bound.pass.metricKey, row.lineId) {\n',
        '                        SideEffect { TerminalFullVirtualMechanismProbe.itemCommitted(row.lineId) }\n                        key(bound.pass.metricKey, row.lineId) {\n')
    require(body.count('itemEntered()')==body.count('itemCommitted(row.lineId)')==1,'Probe sites changed')
    content='                val observedContent: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {\n'+body+'                }\n'
    content+='                SideEffect { TerminalFullVirtualMechanismProbe.definitionCommitted(observedContent, frame, screen) }\n'
    call=source[lazy:body_start].replace('                ) {\n','                    content = observedContent,\n                )\n')
    return source[:begin]+content+call+'            }\n        }\n    }\n}\n'
def guard():
    sha=subprocess.check_output(['git','-c','core.fileMode=false','rev-parse','HEAD:app/src/main'],cwd=ROOT,text=True).strip()
    require(sha==TREE and digest((ROOT/'app/src/main/java'/SOURCE).read_bytes())==BASE_SHA,'Live production not frozen')
    return sha
def instrument(directory):
    guard();directory=Path(directory).resolve()
    require(directory==(ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources').resolve(),'Probe destination outside generated fixture')
    target=directory/SOURCE;original=(ROOT/'app/src/main/java'/SOURCE).read_bytes()
    require(target.read_bytes()==original,'Generated source was already modified')
    generated=instrument_text(original.decode()).encode();target.write_bytes(generated)
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'generated-source-receipt.json').write_text(json.dumps(dict(sourceSha=os.environ.get('GITHUB_SHA'),runId=os.environ.get('GITHUB_RUN_ID'),
        productionTree=TREE,repoPath='app/src/main/java/'+SOURCE,baseSha256=BASE_SHA,generatedSha256=digest(generated),
        productionChanged=False,optimizationEnabled=False),indent=2)+'\n')
    (OUT/'instrumented-TerminalFullVirtualTranscript.kt').write_bytes(generated)
    print('P0 generated fixture counters installed; live production unchanged')
def parse_samples(text):
    samples=[json.loads(l.split('FULL_MECHANISM_SAMPLE ',1)[1]) for l in text.splitlines() if 'FULL_MECHANISM_SAMPLE ' in l]
    done=[json.loads(l.split('FULL_MECHANISM_DONE ',1)[1]) for l in text.splitlines() if 'FULL_MECHANISM_DONE ' in l]
    require([(s['scene'],s['repeat']) for s in samples]==SCENES,'Fixed25scene inventory missing/reordered')
    require(len(done)==1 and done[0]==dict(samples=25,retainedRows=0,pointerRows=0,performanceMeasured=False,simulatedIme=True,passed=True),'Missing cleanup/non-performance marker')
    counters=('definitions','definitionChanges','screenChanges','frameChanges','intervalBuilds','itemAttempts','unchangedItemCommits','unchangedRowCommits',
        'rowMeasurements','notifications','scrollEffects','eligibleParagraphs','changedParagraphs')
    for s in samples:
        require(all(type(s[k]) is int and s[k]>=0 for k in counters),'Invalid counter')
        require(0<s['rowStates']<=80 and 0<s['measuredRows']<=128 and s['maxWriters']<=1,'Full virtualization bounds changed')
        require(re.fullmatch('[a-f0-9]{64}',s['physicalTextSha256']),'Missing content proof')
        if s['scene'] in ('sameFrame','republish','text'):
            require(s['sameScreenIdentity'] is True and s['sameIds'] is True,'Physical structure changed unexpectedly')
        if s['scene'] in ('sameFrame','republish'):
            require(not s['changedRows'] and s['changedParagraphs']==0 and s['eligibleParagraphs']>0,'No-output control changed layout')
        if s['scene']=='text':require(len(s['changedRows'])==1,'One-row update no longer isolates text')
        require(s['sameFrameIdentity'] is (s['scene'] in ('sameFrame','font','scale','rtl','ime','imeHidden','selectionOn','selectionOff','mouseOn','mouseOff')),'Unexpected frame identity')
    return samples,done
def dependency_summary(value):
    expected={(':app',x) for x in ('releaseRuntimeClasspath','terminaltestRuntimeClasspath','terminaltestAndroidTestRuntimeClasspath')}|{
        (':benchmarks:terminal-viewport-tests',x) for x in ('releaseRuntimeClasspath','releaseAndroidTestRuntimeClasspath')}
    configs=value['configurations'];require(len(configs)==5 and {(c['project'],c['configuration']) for c in configs}==expected,'Five APK runtime dependency graphs required')
    result=[]
    for c in configs:
        versions={v['group']+':'+v['module']:v['version'] for v in c['modules'] if v['group'].startswith('androidx.compose')}
        require('androidx.compose.foundation:foundation' in versions and 'androidx.compose.runtime:runtime' in versions,'Compose graph incomplete')
        result.append(dict(project=c['project'],configuration=c['configuration'],versions=versions))
    return result
def report():
    guard();raw=(OUT/'mechanism-logcat.txt').read_text();samples,done=parse_samples(raw)
    receipt=json.loads((OUT/'generated-source-receipt.json').read_bytes())
    expected=instrument_text((ROOT/'app/src/main/java'/SOURCE).read_text()).encode()
    generated=ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources'
    require(receipt['baseSha256']==BASE_SHA and receipt['generatedSha256']==digest(expected) and (generated/SOURCE).read_bytes()==expected,'Generated probe provenance differs')
    source_inventory={}
    for p in generated.rglob('*.kt'):
        path=p.relative_to(generated);original=ROOT/'app/src/main/java'/path
        require(p==generated/SOURCE or p.read_bytes()==original.read_bytes(),'Unexpected production-source instrumentation')
        source_inventory[str(path)]=dict(original=digest(original.read_bytes()),generated=digest(p.read_bytes()))
    tests=[]
    for p in (ROOT/'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results').rglob('TEST-*.xml'):
        for c in ET.parse(p).getroot().iter('testcase'):
            require(not any(c.find(k) is not None for k in ('error','failure','skipped')),'P0 method failed/skipped')
            tests.append((c.get('classname'),c.get('name')))
    require(tests==[('me.rerere.rikkahub.viewporttest.TerminalFullVirtualMechanismTest','fullVirtualIdentityAndActualItemWork')],'Unexpected JUnit inventory')
    deps=json.loads((OUT/'resolved-dependencies.json').read_bytes());versions=dependency_summary(deps)
    require(deps['sourceSha']==os.environ['GITHUB_SHA'] and deps['runId']==os.environ['GITHUB_RUN_ID'],'Dependency graph identity differs')
    text=[s for s in samples if s['scene']=='text']
    findings=dict(stablePhysicalList=all(s['sameScreenIdentity'] for s in text),
        contentChangedEveryTextUpdate=all(s['definitionChanges']>0 for s in text),
        unchangedItemsReentered=any(s['unchangedItemCommits']>0 for s in text),
        unchangedRowsRecomposed=any(s['unchangedRowCommits']>0 for s in text),
        unchangedParagraphsRebuilt=any(s['changedParagraphs']>0 for s in text))
    value=dict(suite='full-virtual-mechanism-p0-v1',sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionTree=TREE,
        productionChanged=False,optimizationEnabled=False,performanceMeasured=False,sourceInventory=source_inventory,
        probeSources={p:digest((ROOT/p).read_bytes()) for p in (TEST,PROBE)},dependencyVersions=versions,samples=samples,cleanup=done,findings=findings,
        note='Generated-source counters with same captures/body,not uninstrumented performance or first-visible-frame evidence. Simulated IME metric changes. '
             'itemAttempts include discarded compositions; items/rows count committed SideEffects. No candidate or speedup is accepted by counts.')
    (OUT/'report.json').write_text(json.dumps(value,indent=2)+'\n')
    compact=dict(sourceSha=value['sourceSha'],runId=value['runId'],productionChanged=False,performanceMeasured=False,findings=findings,
        textSamples=text,dependencyVersions=versions,reportSha256=digest((OUT/'report.json').read_bytes()))
    (OUT/'summary.json').write_text(json.dumps(compact,separators=(',',':')))
    sp=importlib.util.spec_from_file_location('mechanism_evidence',Path(__file__).with_name('terminal-tui-history-reports.py'))
    legacy=importlib.util.module_from_spec(sp);sp.loader.exec_module(legacy)
    legacy.compact('Full virtual P0 mechanism',(OUT/'summary.json').read_bytes())
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','instrument','report'));p.add_argument('--directory');a=p.parse_args()
    if a.action=='guard':print(guard())
    elif a.action=='report':report()
    else:require(a.directory is not None,'Generated directory required');instrument(a.directory)
