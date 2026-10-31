"""No-performance diagnosis of the application startup whole-settings writer vs test preparation."""
import argparse,importlib.util,json,os,subprocess,types
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'artifacts/terminal-startup-settings'
s=importlib.util.spec_from_file_location('startup_settings_wire',Path(__file__).with_name('run-terminal-pipeline.py'))
ci=importlib.util.module_from_spec(s);s.loader.exec_module(ci)
real=ci.real_clock;require=real.require;old_sources=ci.sources
TEST='app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalStartupSettingsTest.kt'
METHODS=tuple(f'startup{label}{i}' for label in ('Unfenced','Fenced') for i in range(4))+('startupControlledStaleCopyReplay',)
CASES=tuple(f'{label}{i}' for label in ('unfenced','fenced') for i in range(4))+('controlled-replay',)
def guard():
    require(subprocess.check_output(['git','rev-parse','HEAD:app/src/main'],cwd=ROOT,text=True).strip()=='8caf50d5c40e228f1d5bcc09d3db505757568b68','Production changed')
    require(not subprocess.check_output(['git','-c','core.fileMode=false','diff','--name-only','55db111129c662067345aa99540b506de305da37','--','app/src/main'],cwd=ROOT).strip(),'Startup diagnosis cannot change production')
def sources():
    result=old_sources()
    for p in (TEST,'.github/scripts/terminal-startup-settings.py','app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt',
        'app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt','app/src/main/java/me/rerere/rikkahub/utils/CoroutineUtils.kt'):
        result[p]=ci.digest(ROOT/p)
    return result
def save(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n')
def build():
    require(os.environ.get('GITHUB_ACTIONS')=='true','CI-only builds');guard();ci.sources=sources
    subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PincludeX86_64AbiForTests=true',
        ':app:assembleTerminaltest',':app:assembleTerminaltestAndroidTest','--stacktrace'],cwd=ROOT,check=True,timeout=1800)
    ci.prepare()
def validate(text):
    rows=real.records(text,'STARTUP_SETTINGS_CASE')
    require(tuple(r['case'] for r in rows)==CASES,'Nine fixed startup cases required')
    for r in rows:
        replay=r['case']=='controlled-replay';fenced=r['case'].startswith('fenced') or replay
        require(r['passed'] and not r['performanceMeasured'] and r['controlledReplay']==replay and r['fenced']==fenced,'Startup case mislabeled')
        require('incrementLaunchCount:' in r['launchReceipt'],'No completed startup writer receipt')
        require([n['stage'] for n in r['notes']]==['before-write','confirmed','after-startup'],'Missing setting phases')
        if fenced:require(r['retained'] is (not replay),'Fenced/replay outcome differs')
    return rows
def report(text,manifest,device,installed):
    rows=validate(text);value=dict(sourceSha=os.environ['GITHUB_SHA'],runId=os.environ['GITHUB_RUN_ID'],productionChanged=False,
        performanceMeasured=False,manifest=manifest,device=device,installedApkHashes=installed,rows=rows,
        note='Unfenced observations and deterministic stale-copy replay are distinct. No terminal performance samples; prior runs remain failures.')
    save(OUT/'report.json',value);compact={k:v for k,v in value.items() if k!='manifest'};compact['reportSha256']=ci.digest(OUT/'report.json')
    real.wire.legacy.compact('Terminal startup settings',json.dumps(compact,separators=(',',':')).encode());return value
def run():
    require(os.environ.get('GITHUB_ACTIONS')=='true','CI-only diagnosis');guard();ci.sources=sources
    manifest=json.loads((ci.OUT/'manifest.json').read_bytes());old=ci.real_clock
    ci.real_clock=types.SimpleNamespace(TEST=real.TEST,guard=guard,CLASS='me.rerere.rikkahub.pipeline.TerminalStartupSettingsTest',METHODS=METHODS,setup_report=report)
    try:ci.emulator(operation=lambda:ci.run_verified_bundle(manifest,'realClockSetup',emit_evidence=False))
    finally:ci.real_clock=old
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('build','run'));a=p.parse_args()
    try:
        if a.action=='build':build()
        else:run()
    except (ValueError,KeyError,OSError,subprocess.SubprocessError) as e:
        ci.notice('error','Startup settings diagnosis stopped',type(e).__name__+': '+str(e)[:1700]);raise SystemExit(1)
