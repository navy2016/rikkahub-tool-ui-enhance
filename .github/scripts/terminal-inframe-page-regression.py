"""Original native-page correctness on the uninstrumented frozen in-frame candidate."""
import argparse,hashlib,importlib.util,json,os,shutil,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'artifacts/terminal-inframe-page-regression'
PROTOCOL=ROOT/'benchmarks/terminal-inframe-publication-candidate/page-regression.json'
PROTOCOL_SHA='41df3363f0e5c649de14a57bb0c8e3ddbe590ee0dd3cda0a892ed52d46ec72a0'
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalInFramePageRegressionTest.kt'
ORIGINAL_TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalFullVirtualPageTest.kt'
s=importlib.util.spec_from_file_location('inframe_page_candidate',Path(__file__).with_name('terminal-inframe-publication.py'))
candidate=importlib.util.module_from_spec(s);s.loader.exec_module(candidate)
ci=candidate.ci;real=ci.real_clock;require=real.require;old_sources=ci.sources
s=importlib.util.spec_from_file_location('inframe_page_validation',Path(__file__).with_name('terminal-full-virtual-reports.py'))
page=importlib.util.module_from_spec(s);s.loader.exec_module(page)
def protocol():
    raw=PROTOCOL.read_bytes();require(hashlib.sha256(raw).hexdigest()==PROTOCOL_SHA,'Page regression protocol changed');return json.loads(raw)
def save(path,value):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value,indent=2)+'\n')
def driver_base(text):
    text=text.replace('class TerminalInFramePageRegressionTest :','class TerminalFullVirtualPageTest :')
    a=text.index('    private fun awaitStartupCompleted(): String {\n');b=text.index('    private fun exercise(tui: Boolean) {\n',a)
    text=text[:a]+text[b:]
    added='''        val startupReceipt = awaitStartupCompleted()
        emit("INFRAME_PAGE_STARTUP", JSONObject().put("receipt", startupReceipt).put("beforeSettingsWrite", true))
'''
    require(text.count(added)==1,'Startup receipt insertion differs');return text.replace(added,'')
def guard():
    p=protocol();candidate.guard()
    require(driver_base((ROOT/TEST).read_text())==(ROOT/ORIGINAL_TEST).read_text(),'Original page test assertions or workload changed')
    return p
def sources():
    data=old_sources()
    for name in (TEST,ORIGINAL_TEST,'.github/scripts/terminal-inframe-page-regression.py','.github/scripts/terminal-inframe-publication.py',
        '.github/scripts/terminal-full-virtual-reports.py','benchmarks/terminal-inframe-publication-candidate/page-regression.json',
        'benchmarks/terminal-inframe-publication-candidate/candidate.patch','benchmarks/terminal-inframe-publication-candidate/sources.json',
        'benchmarks/terminal-inframe-publication-candidate/TerminalInFramePublication.kt','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle'):
        data[name]=ci.digest(ROOT/name)
    if (ROOT/candidate.HELPER).exists():data[candidate.HELPER]=ci.digest(ROOT/candidate.HELPER)
    return data
def build():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Android build CI-only');guard();ci.sources=sources
    files=candidate.candidate();base=(ROOT/candidate.PAGE).read_bytes();env_sources=sources();OUT.mkdir(parents=True,exist_ok=False)
    try:
        for name,text in files.items():(ROOT/name).write_text(text)
        subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PterminalViewportTests=true','-PincludeX86_64AbiForTests=true',
            '-I','benchmarks/terminal-full-virtual-mechanism/dependencies.init.gradle','terminalMechanismDependencies',
            ':app:assembleTerminaltest',':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
        ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes());expected=dict(env_sources)
        for name,text in files.items():expected[name]=hashlib.sha256(text.encode()).hexdigest()
        require(manifest['sources']==expected,'Candidate APK source inventory differs')
        manifest['environmentSources']=env_sources;save(ci.OUT/'manifest.json',manifest)
        for name,text in files.items():
            path=OUT/'candidate-source'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
        save(OUT/'source-receipt.json',dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,candidateIsolated=True,
            traceProbeOverlay=False,candidateFiles={n:hashlib.sha256(t.encode()).hexdigest() for n,t in files.items()},protocolSha256=PROTOCOL_SHA))
        shutil.copyfile(ROOT/'artifacts/terminal-full-virtual-mechanism/resolved-dependencies.json',OUT/'resolved-dependencies.json')
    finally:
        (ROOT/candidate.PAGE).write_bytes(base)
        if (ROOT/candidate.HELPER).exists():(ROOT/candidate.HELPER).unlink()
        guard();require(not candidate.git('diff','--name-only','HEAD').strip(),'Source overlay not restored')
def report(text,manifest,device,installed):
    original=page.validate_page(text);startup=real.records(text,'INFRAME_PAGE_STARTUP')
    require(len(startup)==2 and {r['kind'] for r in startup}=={'ordinary','tui'} and all(r['beforeSettingsWrite'] and 'incrementLaunchCount:' in r['receipt'] for r in startup),'Startup proof missing')
    value=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,candidateIsolated=True,
        traceProbeOverlay=False,performanceMeasured=False,protocol=protocol(),protocolSha256=PROTOCOL_SHA,
        manifest=manifest,device=device,installedApkHashes=installed,startupReceipts=startup,originalPageAssertionsUnchanged=True,**original,
        note='Original ordinary/TUI page regression on frozen candidate; no performance A/B or production adoption.')
    save(OUT/'report.json',value)
    compact=dict(sourceSha=value['sourceSha'],runId=value['runId'],productionChanged=False,performanceMeasured=False,
        originalPageAssertionsUnchanged=True,protocolSha256=PROTOCOL_SHA,reportSha256=ci.digest(OUT/'report.json'),
        originalResultKeys=sorted(original),methods=protocol()['methods'],startupReceipts=startup)
    save(OUT/'summary.json',compact);real.wire.legacy.compact('In-frame native page regression',json.dumps(compact,separators=(',',':')).encode())
    return value
def run():
    require(os.environ.get('GITHUB_ACTIONS')=='true','Native regression only in CI');guard();ci.sources=sources
    manifest=json.loads((ci.OUT/'manifest.json').read_bytes());expected=dict(sources())
    for name,text in candidate.candidate().items():expected[name]=hashlib.sha256(text.encode()).hexdigest()
    require(manifest['sources']==expected and manifest['environmentSources']==sources(),'Candidate/checkout identity mismatch')
    old=ci.real_clock;ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS='me.rerere.rikkahub.pipeline.TerminalInFramePageRegressionTest',METHODS=tuple(protocol()['methods']),report=report)
    try:ci.emulator(operation=lambda:ci.run_verified_bundle(manifest,'realClock',emit_evidence=False))
    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
        for name in ('instrumentation.log','pipeline-logcat.txt'):
            f=ci.OUT/name
            if f.exists():ci.notice('error','In-frame original page '+name,ci.failure_excerpt(f))
        raise
    finally:ci.real_clock=old
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('guard','build','run'));a=p.parse_args()
    try:
        if a.action=='guard':print(json.dumps(guard()))
        elif a.action=='build':build()
        else:run()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','In-frame page regression stopped',type(e).__name__+': '+str(e)[:1900]);raise SystemExit(1)
