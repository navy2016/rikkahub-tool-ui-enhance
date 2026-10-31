"""Frozen three-file alternate-width candidate; Release-only CI builds and native-clock paired runs."""
import argparse,hashlib,importlib.util,json,os,re,shutil,statistics,subprocess,types
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
SPEC=importlib.util.spec_from_file_location('alt_pair_helpers',Path(__file__).with_name('run-terminal-pipeline-paired.py'))
pair=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(pair)
ci=pair.ci;real=ci.real_clock
OUT=ROOT/'artifacts/terminal-alt-width';BUILD=ROOT/'artifacts/alt-width-build'
BASE=ROOT/'benchmarks/terminal-full-virtual-alt-width';PATCH=BASE/'candidate.patch'
PAGE='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalAlternateWidthPageTest.kt'
FONT='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalAlternateWidthFontTest.kt'
UNIT='app/src/test/java/me/rerere/rikkahub/data/container/TerminalAlternateWidthCandidateTest.kt'
METHOD='fullVirtualAlternateRealClock';CLASS='me.rerere.rikkahub.pipeline.TerminalAlternateWidthPageTest'
SUITE='full-virtual-alt-width-paired-v1';PAIRS=4
def spec():return json.loads((BASE/'sources.json').read_bytes())
def save(path,obj):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(obj,indent=2)+'\n')
def require(ok,message):
    if not ok:raise ValueError(message)
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def guard():
    s=spec();require(git('rev-parse','HEAD:app/src/main').decode().strip()==s['baseTree'],'Main tree no longer frozen')
    for p,v in s['files'].items():require(ci.digest(ROOT/p)==v['base'],'Unexpected live production edit:'+p)
    return s
original_sources=ci.sources
def sources():
    result=original_sources()
    for p in (PAGE,FONT,str(PATCH.relative_to(ROOT)),str((BASE/'sources.json').relative_to(ROOT)),str((BASE/'TerminalAlternateWidthCandidateTest.kt').relative_to(ROOT)),'.github/scripts/terminal-alt-width.py'):
        result[p]=ci.digest(ROOT/p)
    return result
def verify_sources(manifests):
    require(set(manifests)=={'legacy','candidate'},'Missing two targets')
    a,b=manifests['legacy']['sources'],manifests['candidate']['sources'];s=spec()
    require(set(a)==set(b) and {p for p in a if a[p]!=b[p]}==set(s['files']),'Source difference not exact three-file candidate')
    for p,v in s['files'].items():require(a[p]==v['base'] and b[p]==v['candidate'],'Wrong candidate bytes')
def configure():
    ci.sources=sources;pair.OUT=OUT;pair.BUILD=BUILD;pair.verify_sources=verify_sources
def build():
    pair.require_ci();guard();configure();require(not git('diff','--name-only','HEAD').strip(),'Tracked checkout dirty')
    OUT.mkdir(parents=True,exist_ok=False);BUILD.mkdir(parents=True,exist_ok=False);manifests={};before={p:(ROOT/p).read_bytes() for p in spec()['files']}
    try:
        subprocess.run(['git','apply','--check',str(PATCH)],cwd=ROOT,check=True)
        subprocess.run(['git','apply',str(PATCH)],cwd=ROOT,check=True)
        for p,v in spec()['files'].items():require(ci.digest(ROOT/p)==v['candidate'],'Candidate patch differs')
        shutil.copyfile(BASE/'TerminalAlternateWidthCandidateTest.kt',ROOT/UNIT)
        unit_classes=('TerminalAlternateWidthCandidateTest','TerminalTranscriptWidthIndexTest','TerminalFullVirtualTest')
        tasks=[':app:testReleaseUnitTest']
        for name in unit_classes:tasks+=['--tests','me.rerere.rikkahub.data.container.'+name]
        subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalReleaseTests=true',*tasks,'--stacktrace'],cwd=ROOT,check=True,timeout=1600)
        cases=[]
        for p in (ROOT/'app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'):
            dest=OUT/'unit'/p.name;dest.parent.mkdir(exist_ok=True);shutil.copyfile(p,dest)
            for c in ET.parse(p).getroot().iter('testcase'):
                require(not any(c.find(k) is not None for k in ('failure','error','skipped')),'Candidate JVM gate failed')
                cases.append((c.get('classname'),c.get('name')))
        require(sum(c.rsplit('.',1)[-1]=='TerminalAlternateWidthCandidateTest' for c,n in cases)==6,'Missing6new width contracts')
        save(OUT/'unit-gates.json',dict(cases=cases,originalAssertionsUnchanged=True))
        (ROOT/UNIT).unlink()
        for arm in ('candidate','legacy'):
            if arm=='legacy':
                for p,raw in before.items():(ROOT/p).write_bytes(raw)
            tasks=[':app:assembleTerminaltest']+([':app:assembleTerminaltestAndroidTest'] if arm=='candidate' else [])
            subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PincludeX86_64AbiForTests=true',*tasks,'--stacktrace'],cwd=ROOT,check=True,timeout=1600)
            ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes())
            for role,apk in manifest['apks'].items():
                dest=BUILD/('common-test.apk' if role=='test' else arm+'/target.apk');dest.parent.mkdir(parents=True,exist_ok=True)
                if role=='test' and arm=='legacy':require(ci.digest(dest)==apk['sha256'],'Shared driver APK changed')
                else:shutil.copyfile(ROOT/apk['file'],dest)
                apk['file']=str(dest.relative_to(ROOT))
            manifests[arm]=manifest
    finally:
        for p,raw in before.items():(ROOT/p).write_bytes(raw)
        (ROOT/UNIT).unlink(missing_ok=True)
        guard();require(not git('diff','--name-only','HEAD').strip(),'Build overlay not restored')
    assets=pair.verify_bundles(manifests)
    sha,run=ci.identity();plan=dict(suite=SUITE,sourceSha=sha,runId=run,pairs=PAIRS,manifests=manifests,sharedAssetInventorySha256=assets,
        patchSha256=ci.digest(PATCH),productionChanged=False,baseProductionSha=spec()['baseProductionSha'])
    save(OUT/'plan.json',plan);save(ci.OUT/'manifest.json',manifests['legacy'])
    ci.notice('notice','Alternate width paired build verified',json.dumps(dict(sourceSha=sha,run=run,unitCases=len(cases),sharedDriver=manifests['legacy']['apks']['test']['sha256'])))
def validate_case(c):
    require(c.get('passed') is True and c.get('alternate') is True and c['case']=='fullVirtual-alt','Incomplete alternate real page')
    ss=c['samples'];require([(s['phase'],s['index']) for s in ss]==[(p,i) for p in real.PHASES for i in range(12)],'36-window order changed')
    require(len({g['rows'] for g in c['gates']})==1 and all(g['columns']==80 and g['virtual'] for g in c['gates']),'Kernel grid changed')
    for s in ss:
        raw=f'\x1b[24;1H\x1b[2KECHO:fullVirtual-alt:{s["phase"]}:{s["index"]:02d}\r'.encode()
        require(s['outputBytes']==len(raw) and s['outputSha256']==hashlib.sha256(raw).hexdigest(),'Wrong exact PTY output')
        require(s['warmup'] is (s['index']<2) and s['virtual'] is True and s['visibleAfterMeasurement'] is True,'Wrong window state')
        es=s['events'];require(all(a['seq']<b['seq'] and a['ns']<=b['ns'] for a,b in zip(es,es[1:])),'Nonmonotonic trace')
        require(s['uiNs']<=s['publishedNs']<=s['drawNs']<=s['observedNs'] and s['fedRevision']<=s['publishedRevision']<=s['drawRevision'],'Reversed sample')
        for stage in ('OUTPUT_READ','EMULATOR_FED'):require(sum(e['bytes'] for e in es if e['stage']==stage)==len(raw),'Missing finite bytes')
        require(sum(e['stage']=='UI_INPUT' for e in es)==1 and any(e['stage']=='FRAME_DRAWN' and e['ns']==s['drawNs'] for e in es),'Missing actual first revision draw')
    return c
def parse(text,manifest,device,installed):
    old=real.CASES
    try:real.CASES=('fullVirtual-alt',);cases=real.case_records(text)
    finally:real.CASES=old
    require(len(cases)==1,'Wrong case count');c=validate_case(cases[0])
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP');require(len(cleanup)==1 and cleanup[0]['closed'] and cleanup[0]['threadStopped'],'Cleanup incomplete')
    path=ci.OUT/'latency-traces'/(METHOD+'.pftrace');raw=path.read_bytes();require(0<len(raw)<100663296,'Trace absent/capped')
    tr=real.trace_data(raw,c['pid']);matched=real.correlate(c,tr)
    require(len(matched)==36 and all(s['match']=='exact-token' for s in matched),'Not all36system-frame matches valid')
    require(tr['lostFtraceBundles']==0 and tr['compactSchedBundles']==0 and tr['ftraceClocks']==[0],'Scheduler evidence incomplete')
    result=dict(case=c,correlation=matched,device=device,installedApkHashes=installed,traceSha256=hashlib.sha256(raw).hexdigest(),traceBytes=len(raw))
    save(ci.OUT/'result.json',result);return result
def font_result(text,manifest,device,installed):
    require(text.count('ALT_WIDTH_FONT_CHECKS 42')==1,'Missing42realfont oracle checks');return dict(realFontChecks=42,device=device,installedApkHashes=installed)
def experiment():
    pair.require_ci();guard();configure();plan=json.loads((OUT/'plan.json').read_bytes());sha,run=ci.identity()
    require(plan['sourceSha']==sha and plan['runId']==run and plan['patchSha256']==ci.digest(PATCH),'Plan identity changed')
    require(pair.verify_bundles(plan['manifests'])==plan['sharedAssetInventorySha256'],'Runtime asset mismatch')
    original_out=ci.OUT;original_real=ci.real_clock
    def run_all():
        boot=pair.read_boot();data=dict(suite=SUITE,sourceSha=sha,runId=run,boot=boot,plan=plan,invocations=[])
        def one(arm,label,font=False):
            ci.OUT=OUT/label;ci.OUT.mkdir(exist_ok=False)
            ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS='me.rerere.rikkahub.pipeline.TerminalAlternateWidthFontTest' if font else CLASS,
                METHODS=('alternateCacheEqualsRealFontFullLayout',) if font else (METHOD,),report=font_result if font else parse)
            try:
                require(pair.read_boot()==boot,'Guest rebooted')
                result=ci.run_verified_bundle(plan['manifests'][arm],'realClock',emit_evidence=False)
                require(pair.read_boot()==boot,'Guest rebooted mid-case');return result
            except (ValueError,KeyError,OSError,subprocess.SubprocessError) as error:
                for name in ('instrumentation.log','pipeline-logcat.txt'):
                    p=ci.OUT/name
                    if p.exists():ci.notice('error','Alternate width '+label,ci.failure_excerpt(p))
                raise
        try:
            data['fontOracle']=one('candidate','font-oracle',True);save(OUT/'progress.json',data)
            for p in range(PAIRS):
                for pos,arm in enumerate(pair.order(p)):
                    result=one(arm,f'pair-{p}-{pos}-{arm}')
                    data['invocations'].append(dict(pair=p,position=pos,arm=arm,result=result));save(OUT/'progress.json',data)
            summary=[]
            for phase in real.PHASES:
                entry=dict(phase=phase,pairs=PAIRS)
                for metric in ('inputToDrawNs','publishToDrawNs','inputToDisplayEndNs','mainRunningPublishToDrawNs'):
                    values={a:[] for a in pair.ARMS}
                    for p in range(PAIRS):
                        for a in pair.ARMS:
                            inv=next(i for i in data['invocations'] if i['pair']==p and i['arm']==a)
                            values[a].append(statistics.median(s[metric] for s in inv['result']['correlation'] if s['phase']==phase and not s['warmup']))
                    ratios=[b/a for a,b in zip(values['legacy'],values['candidate'])]
                    entry[metric]=dict(arms=values,pairedRatios=ratios,medianRatio=statistics.median(ratios),allPairsLower=all(r<1 for r in ratios))
                summary.append(entry)
            screen=all(s['inputToDrawNs']['medianRatio']<=.90 and
                sum(r<1 for r in s['inputToDrawNs']['pairedRatios'])>=3 and
                s['inputToDisplayEndNs']['medianRatio']<=1.05 for s in summary)
            data['summary']=summary;data['passesFrozenPerformanceScreen']=screen;save(OUT/'paired-results.json',data)
            final=dict(sourceSha=sha,runId=run,productionChanged=False,windows=288,warmups=48,pairs=PAIRS,summary=summary,
                resultSha256=ci.digest(OUT/'paired-results.json'),passesFrozenPerformanceScreen=screen,
                note='Same-boot AB/BA shared driver and runtime. 4launchpairs/phase,not phoneFPS. No automatic adoption from work counts.')
            save(OUT/'summary.json',final);real.wire.legacy.compact('Full virtual alternate width',json.dumps(final,separators=(',',':')).encode())
        finally:ci.OUT=original_out;ci.real_clock=original_real
    ci.emulator(operation=run_all)
if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('action',choices=('guard','build','experiment'));args=a.parse_args()
    try:
        if args.action=='guard':print(json.dumps(guard()))
        elif args.action=='build':build()
        else:experiment()
    except (ValueError,OSError,KeyError,subprocess.SubprocessError) as e:
        ci.notice('error','Alternate width stopped',type(e).__name__+': '+str(e)[:1400]);raise SystemExit(1)
