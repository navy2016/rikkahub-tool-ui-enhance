"""Exact212b93e/55db111 source comparison with one archived driver; not a phone/APK speed claim."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

spec=importlib.util.spec_from_file_location('version_bundle_helpers',Path(__file__).with_name('run-terminal-bundle-paired.py'))
helpers=importlib.util.module_from_spec(spec);spec.loader.exec_module(helpers)
ci=helpers.ci;legacy=helpers.legacy;ROOT=ci.ROOT
OUT=ROOT/'artifacts/terminal-version-regression'
BUILD=ROOT/'artifacts/version-regression-build'
REFERENCE='212b93e04980b1087118007bd59da010a15435d8'
CANDIDATE='55db111129c662067345aa99540b506de305da37'
TREE='8caf50d5c40e228f1d5bcc09d3db505757568b68'
SUITE='terminal-version-regression-v1'
MAIN='app/src/main/java/me/rerere/rikkahub/'
ALLOWED={MAIN+p for p in ('data/container/TerminalFullVirtualGeometry.kt','data/container/TerminalItemViewport.kt',
    'data/container/TerminalPipelineTrace.kt','data/container/TerminalRenderMode.kt','data/container/TerminalViewportController.kt',
    'data/container/TerminalVirtualSelection.kt','ui/pages/container/ProcessSessionPage.kt','ui/pages/container/TerminalFullVirtualTranscript.kt',
    'ui/pages/container/TerminalLazyItemExecutor.kt','ui/pages/container/TerminalLazyItemMeasurements.kt','ui/pages/container/TerminalPhysicalMeasurements.kt',
    'ui/pages/container/TerminalPointerMap.kt','ui/pages/container/TerminalRenderControls.kt','ui/pages/container/TerminalRenderedRows.kt',
    'ui/pages/container/TerminalRowStore.kt','ui/pages/container/TerminalTranscriptViewport.kt','ui/pages/container/TerminalViewportBinding.kt',
    'ui/pages/container/TerminalVirtualSelectionUi.kt')}
git=helpers.git;write=helpers.write

def verify_sources(manifests):
    if set(manifests)!=set(legacy.ARMS):raise ValueError('Missing exact version arm')
    old,new=manifests['legacy'],manifests['candidate']
    if old.get('productionSha')!=REFERENCE or new.get('productionSha')!=CANDIDATE:raise ValueError('Wrong version pair')
    if new.get('productionTree')!=TREE:raise ValueError('Candidate production tree differs')
    common=old.get('commonTestSources')
    if not common or common!=new.get('commonTestSources') or any(not p.startswith(helpers.TEST_ROOT) for p in common):
        raise ValueError('Common frozen driver source drift')
    names=set(old['sources'])|set(new['sources'])
    changed={p for p in names if old['sources'].get(p)!=new['sources'].get(p)}
    if changed!=ALLOWED or any(set(m.get('changedProductionFiles',[]))!=ALLOWED for m in manifests.values()):
        raise ValueError('Exact18file production delta changed')
    for m in manifests.values():
        if any(m['sources'].get(p)!=h for p,h in common.items()):raise ValueError('Driver source not in measured inventory')

def configure():
    legacy.SUITE=SUITE;legacy.OUT=OUT;legacy.BUILD=BUILD;legacy.verify_sources=verify_sources

def build():
    helpers.require_ci();configure();sha,run=ci.identity()
    if git('diff','--name-only','HEAD').strip():raise ValueError('Tracked checkout not clean')
    if git('rev-parse','HEAD:app/src/main').decode().strip()!=TREE:raise ValueError('Current source is not55db111 production')
    if git('diff','--name-only',REFERENCE,CANDIDATE,'--','app/build.gradle.kts','gradle/libs.versions.toml','app/src/terminaltest').strip():
        raise ValueError('Build environment/test host differs across exact versions')
    if set(git('diff','--name-only',REFERENCE,CANDIDATE,'--','app/src/main').decode().splitlines())!=ALLOWED:raise ValueError('Version source diff differs')
    tests=set(git('ls-tree','-r','--name-only','HEAD','--',helpers.TEST_ROOT).decode().splitlines())
    old_tests=set(git('ls-tree','-r','--name-only',REFERENCE,'--',helpers.TEST_ROOT).decode().splitlines())
    old_main=set(git('ls-tree','-r','--name-only',REFERENCE,'--','app/src/main').decode().splitlines())
    before={p:(ROOT/p).read_bytes() if (ROOT/p).is_file() else None for p in ALLOWED|tests|old_tests}
    frozen={p:git('show',REFERENCE+':'+p) if p in old_tests else None for p in tests|old_tests}
    previous={p:git('show',REFERENCE+':'+p) if p in old_main else None for p in ALLOWED}
    original_inventory=ci.sources;manifests={};OUT.mkdir(parents=True,exist_ok=False);BUILD.mkdir(parents=True,exist_ok=False)
    try:
        helpers.restore(frozen);common={p:ci.digest(ROOT/p) for p in sorted(old_tests)}
        for arm in ('candidate','legacy'):
            if arm=='legacy':helpers.restore(previous)
            bound=helpers.tree_sources();bound.update(common);ci.sources=lambda:bound
            tasks=[':app:assembleTerminaltest']+([':app:assembleTerminaltestAndroidTest'] if arm=='candidate' else [])
            subprocess.run(['bash','.github/scripts/run-terminal-gradle.sh','-PterminalPipelineTests=true','-PincludeX86_64AbiForTests=true',*tasks,'--stacktrace'],
                cwd=ROOT,check=True,timeout=1800)
            ci.prepare();manifest=json.loads((ci.OUT/'manifest.json').read_bytes())
            revision=REFERENCE if arm=='legacy' else CANDIDATE
            manifest.update(productionSha=revision,productionTree=git('rev-parse',revision+':app/src/main').decode().strip(),
                changedProductionFiles=sorted(ALLOWED),commonTestSources=common)
            for role,apk in manifest['apks'].items():
                dest=BUILD/('common-test.apk' if role=='test' else arm+'/target.apk');dest.parent.mkdir(parents=True,exist_ok=True)
                if role=='test' and arm=='legacy':
                    if ci.digest(dest)!=apk['sha256']:raise ValueError('Legacy must reuse byte-identical test APK')
                else:shutil.copyfile(ROOT/apk['file'],dest)
                apk['file']=str(dest.relative_to(ROOT))
            manifests[arm]=manifest
            shutil.copyfile(ROOT/'artifacts/terminal-validation/gradle.log',OUT/(arm+'-build.log'))
    finally:
        ci.sources=original_inventory;helpers.restore(before)
        if git('diff','--name-only','HEAD').strip():raise ValueError('Disposable source overlays not restored')
    assets=legacy.verify_bundles(manifests)
    plan=dict(suite=SUITE,sourceSha=sha,runId=run,referenceSha=REFERENCE,candidateSha=CANDIDATE,pairs=legacy.PAIRS,
        manifests=manifests,sharedAssetInventorySha256=assets,commonDriverSourceSha=REFERENCE,
        note='Same current build environment and frozen212b93edriver;not byte-identical originalReleaseAPKs. '
             'Ordinary nativePTY/three common modes only,notTUI/wholeapp/physicalphone;no fullVirtual528resampling.')
    write(OUT/'plan.json',plan);write(ci.OUT/'manifest.json',dict(manifests['candidate'],environmentSources=original_inventory()))
    ci.notice('notice','Exact version comparison bundles verified',json.dumps(dict(referenceSha=REFERENCE,candidateSha=CANDIDATE,
        productionTree=TREE,commonDriver=manifests['candidate']['apks']['test']['sha256'],sharedAssets=assets)))

def validate(document):
    configure();p=document.get('plan',{})
    if p.get('referenceSha')!=REFERENCE or p.get('candidateSha')!=CANDIDATE or p.get('commonDriverSourceSha')!=REFERENCE:
        raise ValueError('Reported version pair or driver source changed')
    return legacy.validate(document)

def report():
    doc=validate(json.loads((OUT/'paired-results.json').read_bytes()));summary=legacy.summary(doc)
    write(OUT/'summary.json',summary)
    compact=dict(suite=SUITE,sourceSha=doc['plan']['sourceSha'],runId=doc['plan']['runId'],referenceSha=REFERENCE,candidateSha=CANDIDATE,
        productionTree=TREE,bootId=doc['bootId'],device=doc['device'],measuredSamples=288,warmupSamples=72,pairs=4,
        summary=summary,resultSha256=legacy.result_digest(doc),
        note='Positive delta means55db111slower. Fixedfourlaunchpairs,not288independentdevice trials;no speedup threshold or green-metric filtering.',
        boundaries=['common modes only','ordinary history/nativePTY','same-source rebuilds not original signed APK binaries','no phone/P99/GPU presentation claim'])
    write(OUT/'report.json',compact);legacy.evidence('Terminal exact version comparison',compact)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=('build','emulator','report'));a=p.parse_args()
    try:
        helpers.require_ci();configure()
        if a.action=='build':build()
        elif a.action=='report':report()
        else:
            plan=json.loads((OUT/'plan.json').read_bytes())
            if (plan['sourceSha'],plan['runId'])!=ci.identity():raise ValueError('Wrong CI invocation')
            legacy.verify_bundles(plan['manifests']);ci.emulator(operation=lambda:legacy.measure(plan))
    except (ValueError,OSError,KeyError,subprocess.SubprocessError) as e:
        ci.notice('error','Exact version comparison stopped',type(e).__name__+': '+str(e)[:1800]);raise SystemExit(1)
