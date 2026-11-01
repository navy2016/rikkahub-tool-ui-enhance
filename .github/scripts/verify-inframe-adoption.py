"""Read-only source adoption audit; no build, resampling or mutation of historical experiment guards."""
import argparse,hashlib,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASE='55db111129c662067345aa99540b506de305da37'
FREEZE='bfa29900798d6bb591c66da543859aaa37361dd7'
FINAL='8822e81b3a605e10fae1d8508282c0dc59fbc934'
SPECDIR='benchmarks/terminal-inframe-publication-candidate'
UNIT='app/src/test/java/me/rerere/rikkahub/data/container/TerminalInFramePublicationTest.kt'
def git(*args):return subprocess.check_output(['git','-c','core.fileMode=false',*args],cwd=ROOT,timeout=40)
def require(ok,message):
    if not ok:raise ValueError(message)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def audit():
    spec=json.loads((ROOT/SPECDIR/'sources.json').read_bytes());paths=set(spec['files'])
    for filename in ('candidate.patch','sources.json','protocol.json','TerminalInFramePublication.kt','TerminalInFramePublicationTest.kt'):
        relative=SPECDIR+'/'+filename;require((ROOT/relative).read_bytes()==git('show',FREEZE+':'+relative),'Frozen candidate asset changed:'+filename)
    changed=set(git('diff','--name-only',BASE,'--','app/src/main').decode().splitlines())
    require(changed==paths,'Production scope is not exactly the two approved files')
    require(not git('ls-files','--others','--exclude-standard','app/src/main').strip(),'Untracked production source outside audited index')
    require(not git('diff','--name-only',BASE,'--','app/build.gradle.kts','gradle/libs.versions.toml','app/src/main/res','app/src/main/cpp','app/src/main/assets').strip(),'Build/dependencies/fonts/native/assets changed')
    adopted={}
    for name,hashes in spec['files'].items():
        value=digest((ROOT/name).read_bytes());require(value==hashes['candidate'],'Adopted bytes differ from tested candidate:'+name);adopted[name]=value
    require((ROOT/UNIT).read_bytes()==(ROOT/SPECDIR/'TerminalInFramePublicationTest.kt').read_bytes(),'New boundary test differs from tested12 methods')
    final_plan=json.loads((ROOT/'benchmarks/terminal-inframe-final-regression/plan.json').read_bytes())
    for item in final_plan['unitInventory']:
        require(digest((ROOT/item['file']).read_bytes())==item['sha256'],'A final-regression unit source changed:'+item['file'])
        require((ROOT/item['file']).read_bytes()==git('show',FINAL+':'+item['file']),'Final JVM source identity mismatch')
    require(not git('diff','--name-only',FINAL,'--','benchmarks/terminal-viewport-tests/src','app/src/terminaltestAndroidTest').strip(),'Verified viewport/page/driver tests changed')
    return dict(baseProductionSha=BASE,candidateFreezeSha=FREEZE,finalRegressionSourceSha=FINAL,adoptedFiles=adopted,
        unitTestSha256=digest((ROOT/UNIT).read_bytes()),performanceRun=41253490506,finalRegressionRun=41258895634,
        performanceResultSha256='9fc3b988770040b7136605808369d1adc5471b3fbeacf2598b7aa94ab227fb54',
        exactCandidateBytes=True,defaultModeUnchanged=True,rejectedP2Included=False,historicalResultsUnchanged=True)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path);args=parser.parse_args()
    try:
        result=audit();print(json.dumps(result,indent=2))
        if args.output:args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(result,indent=2)+'\n')
    except (ValueError,OSError,subprocess.SubprocessError) as error:raise SystemExit('In-frame adoption audit failed: '+str(error))
