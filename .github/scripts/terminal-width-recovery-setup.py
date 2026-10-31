"""P2 first-mount configuration preflight only; unchanged production, no performance windows."""
import argparse,importlib.util,json,os,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'artifacts/terminal-width-recovery-setup'
s=importlib.util.spec_from_file_location('recovery_setup_wire',Path(__file__).with_name('terminal-width-recovery-paired.py'))
p=importlib.util.module_from_spec(s);s.loader.exec_module(p)
ci=p.ci;real=p.real;require=p.require
def sources():
    result=p.sources();result['.github/scripts/terminal-width-recovery-setup.py']=ci.digest(Path(__file__))
    return result
def build():
    p.pair.require_ci();p.guard();ci.sources=sources
    subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PincludeX86_64AbiForTests=true',
        ':app:assembleTerminaltest',':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
    ci.prepare();p.guard()
def validate(text):
    old=real.CASES
    try:real.CASES=p.CASES;cases=real.case_records(text)
    finally:real.CASES=old
    cleanup=real.records(text,'REAL_CLOCK_CLEANUP')
    require(len(cleanup)==4 and {c['case'] for c in cleanup}==set(p.CASES) and all(c['closed'] and c['threadStopped'] for c in cleanup),'Setup cleanup incomplete')
    for c in cases:
        size=int(c['case'].split('-')[1]);shown=c['case'].endswith('-shown')
        require(c['setupOnly'] and c['acceptance'] is False and c['passed'] and c['samples']==[], 'Setup did not pass without sampling')
        require(c['modelColumns']==80 and c['modelRows']>=24 and c['modelHistory']==size and c['expectedIme']==shown,'Incorrect configured grid/history')
        require(len(c['gates'])==1 and c['gates'][0]['ime']==shown and c['gates'][0]['virtual'],'Native keyboard branch missing')
        notes=c['preparation'];require([n['stage'] for n in notes]==['before-write','after-write','persisted-and-flow-confirmed','before-mount'],'Incomplete setup checkpoints')
        require(all(n['configured'] and not n['init'] for n in notes[-2:]),'Configured state did not reach first mount')
        require(all(n['decoded']['forcedTerminalColumns']==80 and n['decoded']['terminalPanMode'] is True and
            n['decoded']['maxScrollbackLines']==size and n['decoded']['rawInputMode'] is False for n in notes[-2:]), 'Wrong production decoded preference')
    return cases,cleanup
def report(text,manifest,device,installed):
    OUT.mkdir(parents=True,exist_ok=True)
    # Save decoded failures too; instrumentation success alone cannot approve preflight.
    old=real.CASES
    try:real.CASES=p.CASES;original=real.case_records(text)
    finally:real.CASES=old
    p.save(OUT/'observed-cases.json',original)
    cases,cleanup=validate(text)
    value=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,
        productionSha=p.baseline.PRODUCTION,productionTree=p.baseline.TREE,performanceMeasured=False,
        cases=cases,cleanup=cleanup,manifest=manifest,device=device,installedApkHashes=installed,
        priorFailedRun=41181696048,note='First-mount preparation preflight on unmodified baseline only. No paired performance results or root-cause timing claimed.')
    p.save(OUT/'report.json',value)
    compact={k:v for k,v in value.items() if k!='manifest'};compact['reportSha256']=ci.digest(OUT/'report.json')
    real.wire.legacy.compact('P2 first mount setup',json.dumps(compact,separators=(',',':')).encode())
    return value
def run():
    p.pair.require_ci();p.guard();ci.sources=sources
    manifest=json.loads((ci.OUT/'manifest.json').read_bytes());require(manifest['sources']==sources(),'Setup bundle source mismatch')
    old=ci.real_clock
    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=p.guard,CLASS=p.CLASS,METHODS=p.METHODS,setup_report=report)
    try:ci.emulator(operation=lambda:ci.run_verified_bundle(manifest,'realClockSetup',emit_evidence=False))
    except (ValueError,KeyError,OSError,subprocess.SubprocessError):
        for name in ('instrumentation.log','pipeline-logcat.txt'):
            f=ci.OUT/name
            if f.exists():ci.notice('error','P2 setup '+name,ci.failure_excerpt(f))
        raise
    finally:ci.real_clock=old
if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('action',choices=('build','run'));args=a.parse_args()
    try:
        if args.action=='build':build()
        else:run()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','P2 first mount preflight stopped',type(e).__name__+': '+str(e)[:1700]);raise SystemExit(1)
