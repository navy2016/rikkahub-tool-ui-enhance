"""Full original terminal regressions against the exact performance-qualified in-frame candidate."""
import argparse,base64,collections,hashlib,importlib.util,json,os,re,shutil,subprocess,sys,zlib
from contextlib import contextmanager
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2];BASE=ROOT/'benchmarks/terminal-inframe-final-regression';OUT=ROOT/'artifacts/terminal-inframe-final-regression'
PLAN_SHA='984b4fef43095ceeca9ca4e3dff6d2cae434bf7df43bc0dc60bbe26bf2e36aaa'
s=importlib.util.spec_from_file_location('final_inframe_candidate',Path(__file__).with_name('terminal-inframe-publication.py'))
candidate=importlib.util.module_from_spec(s);s.loader.exec_module(candidate)
ci=candidate.ci;require=candidate.require
def sha(raw):return hashlib.sha256(raw).hexdigest()
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def plan():
    raw=(BASE/'plan.json').read_bytes();require(sha(raw)==PLAN_SHA,'Final regression inventory changed');return json.loads(raw)
def guard():
    p=plan();candidate.guard()
    for item in p['unitInventory']:require(sha((ROOT/item['file']).read_bytes())==item['sha256'],'Unit source inventory changed:'+item['file'])
    for path,digest in p['viewportTestSources'].items():require(sha((ROOT/path).read_bytes())==digest,'Viewport assertions changed:'+path)
    return p
def api(path):return json.loads(subprocess.check_output(['gh','api','repos/navy2016/rikkahub-tool-ui-enhance/'+path],text=True,timeout=30))
def gate():
    require(os.environ.get('GITHUB_ACTIONS')=='true','CI-only gate');p=guard();run=api(f'actions/runs/{p["performanceRun"]}')
    require(run['head_sha']==p['performanceSourceSha'] and run['conclusion']=='success','Performance run not successful')
    chunks={};proof=None
    for job in api(f'actions/runs/{p["performanceRun"]}/jobs?per_page=100')['jobs']:
        if job['conclusion']=='skipped':continue
        for page_no in range(1,6):
            annotations=api(f'check-runs/{job["id"]}/annotations?per_page=100&page={page_no}')
            for item in annotations:
                match=re.fullmatch(r'In-frame publication performance evidence (\d+)/(\d+)',item.get('title',''))
                if not match:continue
                header,data=item['message'].split('\n',1);digest=re.fullmatch(r'zlib-base64 sha256=([a-f0-9]{64})',header)[1]
                current=(int(match[2]),digest);require(proof is None or proof==current,'Mixed performance evidence');proof=current
                require(int(match[1]) not in chunks,'Duplicate evidence fragment');chunks[int(match[1])]=data
            if len(annotations)<100:break
    require(proof is not None and set(chunks)==set(range(1,proof[0]+1)),'Incomplete performance receipt')
    raw=zlib.decompress(base64.b64decode(''.join(chunks[n] for n in sorted(chunks)),validate=True));require(sha(raw)==proof[1],'Evidence checksum mismatch')
    summary=json.loads(raw)
    require(summary['sourceSha']==p['performanceSourceSha'] and str(summary['runId'])==str(p['performanceRun']) and
        summary['resultSha256']==p['performanceResultSha256'] and summary['protocolSha256']==p['performanceProtocolSha256'],'Unexpected performance identity')
    require(summary['windows']==480 and summary['warmups']==96 and summary['dataComplete'] and summary['passesFrozenPerformanceScreen'] and not summary['gateFailures'],'Performance prerequisite did not pass')
    save(OUT/'gate.json',dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],performanceRun=run,performanceSummary=summary,productionChanged=False))
    ci.notice('notice','Final regression performance prerequisite','Exact480-window frozen performance passed; this gate does not adopt production.')
def junit(paths):
    rows=[];seen=set();failures=[]
    for path in sorted(paths):
        for case in ET.parse(path).getroot().iter('testcase'):
            key=(case.get('classname'),case.get('name'))
            if key in seen:failures.append('duplicate '+str(key))
            seen.add(key);rows.append(dict(classname=key[0],name=key[1]))
            for tag in ('failure','error','skipped'):
                element=case.find(tag)
                if element is not None:failures.append(str(key)+': '+tag+': '+(element.text or element.get('message',''))[:800])
    for failure in failures[:12]:ci.notice('error','Final regression JUnit failure',failure)
    require(not failures,'Final regression failures/errors/skips/duplicates')
    return rows
@contextmanager
def overlay(unit=False):
    guard();files=candidate.candidate();base=(ROOT/candidate.PAGE).read_bytes()
    require(not (ROOT/candidate.HELPER).exists(),'Helper collision')
    try:
        for name,text in files.items():(ROOT/name).write_text(text)
        if unit:(ROOT/candidate.UNIT).write_bytes((candidate.BASE/'TerminalInFramePublicationTest.kt').read_bytes())
        yield files
    finally:
        (ROOT/candidate.PAGE).write_bytes(base)
        for name in (candidate.HELPER,)+( (candidate.UNIT,) if unit else ()):
            if (ROOT/name).exists():(ROOT/name).unlink()
        guard();require(not candidate.git('diff','--name-only','HEAD','--','app/src/main').strip(),'Production overlay leaked')
def unit():
    require(os.environ.get('GITHUB_ACTIONS')=='true','JVM builds only in CI');p=guard();target=OUT/'unit';target.mkdir(parents=True,exist_ok=False)
    original_classes=[x['classname'] for x in p['unitInventory']];new_class='me.rerere.rikkahub.data.container.TerminalInFramePublicationTest'
    with overlay(unit=True) as files:
        command=['bash','.github/scripts/run-terminal-gradle.sh','-PterminalReleaseTests=true',':app:testReleaseUnitTest']
        for name in original_classes+[new_class]:command+=['--tests',name]
        result=subprocess.run(command+['--stacktrace'],cwd=ROOT,timeout=1800)
        xmls=list((ROOT/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
        for file in xmls:shutil.copyfile(file,target/file.name)
        try:rows=junit(xmls)
        finally:require(result.returncode==0,'Release JVM invocation failed')
        expected={(x['classname'],method) for x in p['unitInventory'] for method in x['methods']}
        new_methods=re.findall(r'@Test\s+fun\s+(\w+)\(', (candidate.BASE/'TerminalInFramePublicationTest.kt').read_text())
        expected|={(new_class,name) for name in new_methods};require(len(expected)==p['jvmMethods'] and {(r['classname'],r['name']) for r in rows}==expected,'Full504 JVM inventory differs')
        subprocess.run(['python3','-B','.github/scripts/terminal-tui-bundle-reports.py','parser'],cwd=ROOT,check=True,timeout=120)
        subprocess.run(['python3','-B','.github/scripts/terminal-byte-buffer-reports.py','app/build/test-results/testReleaseUnitTest','--output',str(target/'byte-buffer.json')],cwd=ROOT,check=True,timeout=120)
        save(target/'report.json',dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,
            candidateHashes={n:sha(t.encode()) for n,t in files.items()},cases=rows,originalCases=492,newCases=12,passed=True))
    ci.notice('notice','Final in-frame JVM inventory','504 Release JVM cases passed:492 original methods +12 candidate boundaries. No production adoption.')
def fixture_build():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android builds only in CI');guard()
    with overlay() as files:
        subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalViewportTests=true',':benchmarks:terminal-viewport-tests:assembleRelease',
            ':benchmarks:terminal-viewport-tests:assembleReleaseAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
        copied={}
        folder=ROOT/'benchmarks/terminal-viewport-tests/build/generated/viewportSources'
        for path in folder.rglob('*.kt'):
            source=ROOT/'app/src/main/java'/path.relative_to(folder);require(path.read_bytes()==source.read_bytes(),'Fixture source differs')
            copied[str(source.relative_to(ROOT))]=sha(path.read_bytes())
        require(len(copied)>=40 and not set(files)&set(copied),'Expected unchanged component fixture, not whole Page')
        save(OUT/'fixture-source.json',dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],candidateHashes={n:sha(t.encode()) for n,t in files.items()},
            copiedComponents=copied,pageSchedulerNotInFixture=True,productionChanged=False))
def viewport(group):
    require(os.environ.get('GITHUB_ACTIONS')=='true','Device regressions only in CI');p=guard();require(group in p['viewportGroups'],'Unknown viewport shard')
    env=dict(os.environ,TERMINAL_VIEWPORT_SUITE='selectionRepair',TERMINAL_VIEWPORT_GROUP=group)
    result=subprocess.run(['python3','.github/scripts/run-terminal-with-timeout.py','900','bash','benchmarks/terminal-viewport-tests/run-ci.sh'],cwd=ROOT,env=env,timeout=1000)
    require(result.returncode==0,'Original viewport runner failed')
def report_viewport(group):
    p=guard();require(group in p['viewportGroups'],'Unknown viewport shard')
    folder=ROOT/'benchmarks/terminal-viewport-tests/build/outputs/androidTest-results';rows=junit(folder.rglob('TEST-*.xml'))
    expected_count=sum(x['count'] for x in p['viewportGroups'][group]);require(len(rows)==expected_count,'Viewport shard count differs')
    expected=collections.Counter()
    for item in p['viewportGroups'][group]:
        for method in item['methods']:expected[('me.rerere.rikkahub.viewporttest.'+item['classname'],method)]+=2 if item['classname']=='TerminalTranscriptGeometryInstrumentedTest' else 1
    actual=collections.Counter((x['classname'],x['name'].split('[',1)[0]) for x in rows)
    require(actual==expected,'Missing or unexpected exact viewport method')
    scripts={
        'geometry':[['report-terminal-row-width.py'],['report-terminal-text-metrics.py'],['report-terminal-chunk-views.py']],
        'interaction':[['report-terminal-scroll-completion.py'],['report-terminal-panel-subscription.py'],['report-terminal-panel-subscription.py','--binding'],['report-terminal-render-snapshot.py']],
        'tuiDisplay':[['terminal-tui-bundle-reports.py','display']],
        'bottomVisibility':[['terminal-bottom-visibility-reports.py']],
        'tuiHistory':[['terminal-full-virtual-reports.py','oldViewport']],
    }
    for command in scripts.get(group,[]):subprocess.run(['python3','-B','.github/scripts/'+command[0],*command[1:]],cwd=ROOT,check=True,timeout=120)
    if group=='selectionRepair':
        s=importlib.util.spec_from_file_location('final_selection_checks',ROOT/'.github/scripts/terminal-selection-repair-reports.py');mod=importlib.util.module_from_spec(s);s.loader.exec_module(mod)
        data=mod.validate_repair((ROOT/'artifacts/terminal-selection-repair/repair-logcat.txt').read_text())
        save(OUT/'selection-repair-evidence.json',data)
    save(OUT/(group+'-report.json'),dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],group=group,cases=rows,count=len(rows),passed=True,productionChanged=False))
    ci.notice('notice','Final in-frame viewport '+group,f'{len(rows)} exact original methods passed; candidate unchanged, no performance reuse.')
def collect():
    """Read existing regression artifacts only: no Gradle, emulator, rerun or result replacement."""
    require(os.environ.get('GITHUB_ACTIONS')=='true','Evidence collection only in CI');p=guard()
    run_id=41258895634;source='8822e81b3a605e10fae1d8508282c0dc59fbc934';run=api(f'actions/runs/{run_id}')
    require(run['head_sha']==source and run['conclusion']=='success','Original final regression not successful')
    jobs=api(f'actions/runs/{run_id}/jobs?per_page=100')['jobs'];executed=[j for j in jobs if j['conclusion']!='skipped']
    require(len(executed)==9 and all(j['conclusion']=='success' for j in executed),'Missing final regression jobs')
    artifact_list=api(f'actions/runs/{run_id}/artifacts?per_page=100')['artifacts']
    artifacts=[a for a in artifact_list if a['name'].startswith('terminal-inframe-final-') and source in a['name']]
    require(len(artifacts)==9 and all(not a['expired'] for a in artifacts),'Missing original final artifacts')
    folder=OUT/'collected'
    subprocess.run(['gh','run','download',str(run_id),'--repo','navy2016/rikkahub-tool-ui-enhance','--pattern','terminal-inframe-final-*','--dir',str(folder)],check=True,timeout=240)
    require(not any(f.suffix.lower() in ('.apk','.aab') for f in folder.rglob('*')),'Unexpected APK in evidence collection')
    unit_dirs=[d for d in folder.iterdir() if d.name.startswith('terminal-inframe-final-jvm-')];require(len(unit_dirs)==1,'Unit artifact ambiguous')
    files=list(unit_dirs[0].rglob('unit/TEST-*.xml'));unit_rows=junit(files)
    expected={(x['classname'],name) for x in p['unitInventory'] for name in x['methods']}
    extra=re.findall(r'@Test\s+fun\s+(\w+)\(', (candidate.BASE/'TerminalInFramePublicationTest.kt').read_text())
    expected|={('me.rerere.rikkahub.data.container.TerminalInFramePublicationTest',name) for name in extra}
    require(len(unit_rows)==504 and {(x['classname'],x['name']) for x in unit_rows}==expected,'Collected504 JVM differs')
    unit_reports=list(unit_dirs[0].rglob('unit/report.json'));require(len(unit_reports)==1,'Unit report missing');unit_report=json.loads(unit_reports[0].read_bytes())
    hashes={n:sha(t.encode()) for n,t in candidate.candidate().items()};require(unit_report['candidateHashes']==hashes,'Unit candidate differs')
    group_reports=[];total=0
    for group,inventory in p['viewportGroups'].items():
        roots=[d for d in folder.iterdir() if d.name.startswith('terminal-inframe-final-viewport-'+group+'-')];require(len(roots)==1,'Missing shard artifact:'+group)
        xmls=list(roots[0].rglob('TEST-*.xml'));rows=junit(xmls)
        expected=collections.Counter()
        for item in inventory:
            for name in item['methods']:expected[('me.rerere.rikkahub.viewporttest.'+item['classname'],name)]+=2 if item['classname']=='TerminalTranscriptGeometryInstrumentedTest' else 1
        require(collections.Counter((x['classname'],x['name'].split('[',1)[0]) for x in rows)==expected,'Collected viewport methods differ:'+group)
        reports=list(roots[0].rglob(group+'-report.json'));require(len(reports)==1,'Missing shard report');report=json.loads(reports[0].read_bytes())
        require(report['sourceSha']==source and str(report['runId'])==str(run_id) and report['passed'] and report['cases']==rows,'Shard report identity/data differs')
        copied=list(roots[0].rglob('fixture-source.json'));require(len(copied)==1,'Fixture source proof missing')
        receipt=json.loads(copied[0].read_bytes());require(receipt['candidateHashes']==hashes and receipt['pageSchedulerNotInFixture'],'Fixture/candidate source differs')
        total+=len(rows);group_reports.append(dict(group=group,count=len(rows),reportSha256=sha(reports[0].read_bytes())))
    require(total==182,'Collected182 viewport inventory differs')
    save(OUT/'verified-collection.json',dict(collectionSourceSha=os.environ['GITHUB_SHA'],collectionRunId=os.environ['GITHUB_RUN_ID'],
        originalRun=run_id,originalSourceSha=source,originalConclusion=run['conclusion'],jvmMethods=504,viewportMethods=182,
        groups=group_reports,candidateHashes=hashes,artifacts=artifacts,noNewTests=True,productionChanged=False))
    save(OUT/'original-run.json',run)
    ci.notice('notice','Final regression originals reverified','504 JVM +182 viewport exact originals rechecked; no rerun, no production adoption.')
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=('guard','gate','unit','fixture-build','viewport','viewport-report','collect'));parser.add_argument('--group');args=parser.parse_args()
    try:
        if args.action=='guard':print(json.dumps(dict(jvm=guard()['jvmMethods'],viewport=plan()['viewportMethods'])))
        elif args.action=='gate':gate()
        elif args.action=='unit':unit()
        elif args.action=='fixture-build':fixture_build()
        elif args.action=='viewport':viewport(args.group)
        elif args.action=='collect':collect()
        else:report_viewport(args.group)
    except (ValueError,KeyError,OSError,subprocess.SubprocessError,ET.ParseError) as error:
        ci.notice('error','Final in-frame regression stopped',type(error).__name__+': '+str(error)[:2400]);raise SystemExit(1)
