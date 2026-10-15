"""Whole terminal batch AB/BA with a frozen driver. Does not change the archived one-site experiment."""
import argparse
import importlib.util
import json
import os
import shutil
import subprocess
from pathlib import Path


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


legacy = module('bundle_legacy_helpers', 'run-terminal-pipeline-paired.py')
ci = legacy.ci
ROOT = ci.ROOT
OUT = ROOT / 'artifacts/terminal-bundle-paired'
BUILD = ROOT / 'artifacts/bundle-paired-build'
CONTROL = 'fe43ec19e6e565acda8b8f3dbc6c530938609b32'
SUITE = 'terminal-bundle-paired-v1'
ARMS = ('legacy', 'candidate')
MAIN = 'app/src/main/java/me/rerere/rikkahub/'
ALLOWED = {MAIN + name for name in (
    'data/container/BackgroundProcessManager.kt', 'data/container/TerminalForegroundLifecycle.kt',
    'data/container/TerminalOutputNotifications.kt', 'data/container/TerminalWidthCheckpointStore.kt',
    'data/container/TerminalTranscriptWidthIndex.kt', 'service/ContainerProcessForegroundService.kt',
    'utils/TerminalEmulator.kt', 'ui/pages/container/ProcessSessionPage.kt',
    'ui/pages/container/TerminalPreparedWidth.kt', 'ui/pages/container/TerminalRowStore.kt',
    'ui/pages/container/TerminalRenderSnapshot.kt', 'ui/pages/container/TerminalRenderedRows.kt',
    'ui/pages/container/TerminalTranscriptViewport.kt', 'ui/pages/container/TerminalViewportBinding.kt',
    'ui/pages/container/TerminalVirtualHistoryTranscript.kt', 'ui/pages/container/TerminalPipelineInstrumentation.kt',
)}
TEST_ROOT = 'app/src/terminaltestAndroidTest/'


def require_ci():
    legacy.require_ci()
    if os.environ.get('GITHUB_REF') != 'refs/heads/opt/terminal-verified-a79e7b6':
        raise ValueError('Whole-batch comparison requires the authorized optimization branch')


def git(*args):
    return subprocess.check_output(['git', '-c', 'core.fileMode=false', *args], cwd=ROOT, timeout=60)


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def tree_sources():
    return {str(path.relative_to(ROOT)): ci.digest(path)
            for path in sorted((ROOT / 'app/src/main').rglob('*')) if path.is_file()}


def restore(values):
    for name, data in values.items():
        path = ROOT / name
        if data is None:
            path.unlink(missing_ok=True)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)


def verify_sources(manifests):
    if set(manifests) != set(ARMS):
        raise ValueError('Missing whole-batch arm')
    old, new = manifests['legacy'], manifests['candidate']
    if old.get('productionSha') != CONTROL or new.get('productionSha') != new.get('sourceSha'):
        raise ValueError('Wrong control/candidate source identity')
    names = set(old['sources']) | set(new['sources'])
    changed = {name for name in names if old['sources'].get(name) != new['sources'].get(name)}
    if not changed or not changed <= ALLOWED:
        raise ValueError('Whole-batch diff exceeded the explicit terminal source allowlist')
    if (set(old.get('changedProductionFiles', [])) != changed or
            old['changedProductionFiles'] != new.get('changedProductionFiles')):
        raise ValueError('Missing or inconsistent whole-batch source difference')
    common = old.get('commonTestSources')
    if not common or common != new.get('commonTestSources') or any(not p.startswith(TEST_ROOT) for p in common):
        raise ValueError('Missing byte-identical frozen driver source inventory')
    if any(arm['sources'].get(p) != value for arm in manifests.values() for p, value in common.items()):
        raise ValueError('Driver source changed between comparison arms')


def configure_helpers():
    # Reuse only the already-tested APK/device/receipt validators and AB/BA execution engine.
    # This is an independent loaded module; no v1 file, hash or archived result is changed.
    legacy.SUITE = SUITE
    legacy.OUT = OUT
    legacy.BUILD = BUILD
    legacy.verify_sources = verify_sources


def build():
    require_ci()
    sha, run_id = ci.identity()
    if git('diff', '--name-only', 'HEAD').strip():
        raise ValueError('Whole-batch comparison requires a clean tracked tree')
    git('fetch', '--no-tags', '--depth=1', 'origin', CONTROL)
    changed = set(git('diff', '--name-only', CONTROL, 'HEAD', '--', 'app/src/main').decode().splitlines())
    if not changed or not changed <= ALLOWED:
        raise ValueError('Production diff outside the terminal batch: ' + str(sorted(changed - ALLOWED)))
    old_main = set(git('ls-tree', '-r', '--name-only', CONTROL, '--', 'app/src/main').decode().splitlines())
    tests = set(git('ls-tree', '-r', '--name-only', 'HEAD', '--', TEST_ROOT).decode().splitlines())
    old_tests = set(git('ls-tree', '-r', '--name-only', CONTROL, '--', TEST_ROOT).decode().splitlines())
    before = {p: (ROOT / p).read_bytes() if (ROOT / p).exists() else None for p in changed | tests | old_tests}
    frozen_tests = {p: git('show', CONTROL + ':' + p) if p in old_tests else None for p in tests | old_tests}
    old_production = {p: git('show', CONTROL + ':' + p) if p in old_main else None for p in changed}
    original_inventory = ci.sources
    OUT.mkdir(parents=True, exist_ok=False)
    BUILD.mkdir(parents=True, exist_ok=False)
    manifests = {}
    try:
        restore(frozen_tests)
        common = {p: ci.digest(ROOT / p) for p in sorted(old_tests)}
        for arm in ('candidate', 'legacy'):
            if arm == 'legacy':
                restore(old_production)
            bound = tree_sources()
            bound.update(common)
            ci.sources = lambda: bound
            tasks = [':app:assembleTerminaltest']
            if arm == 'candidate':
                tasks.append(':app:assembleTerminaltestAndroidTest')
            subprocess.run(['bash', '.github/scripts/run-terminal-gradle.sh', '-PterminalPipelineTests=true',
                '-PincludeX86_64AbiForTests=true', *tasks, '--stacktrace'], cwd=ROOT, check=True, timeout=1800)
            ci.prepare()
            manifest = json.loads((ci.OUT / 'manifest.json').read_text())
            manifest.update(productionSha=CONTROL if arm == 'legacy' else sha,
                            changedProductionFiles=sorted(changed), commonTestSources=common)
            for role, apk in manifest['apks'].items():
                dest = BUILD / ('common-test.apk' if role == 'test' else arm + '/target.apk')
                dest.parent.mkdir(parents=True, exist_ok=True)
                if role == 'test' and arm == 'legacy':
                    if ci.digest(dest) != apk['sha256']:
                        raise ValueError('Control must use the exact same test APK')
                else:
                    shutil.copyfile(ROOT / apk['file'], dest)
                apk['file'] = str(dest.relative_to(ROOT))
            manifests[arm] = manifest
    finally:
        ci.sources = original_inventory
        restore(before)
        if git('diff', '--name-only', 'HEAD').strip():
            raise ValueError('Disposable comparison overlays were not fully restored')
    configure_helpers()
    asset_hash = legacy.verify_bundles(manifests)
    plan = dict(suite=SUITE, sourceSha=sha, runId=run_id, controlSha=CONTROL, pairs=legacy.PAIRS,
                manifests=manifests, sharedAssetInventorySha256=asset_hash,
                note='Whole production batch versus fixed pre-batch tree; common pre-batch driver in both arms.')
    write(OUT / 'plan.json', plan)
    # Bootstrap verifies current sources; measurement itself verifies each frozen arm independently.
    bootstrap = dict(manifests['candidate'], environmentSources=original_inventory())
    write(ci.OUT / 'manifest.json', bootstrap)
    ci.notice('notice', 'Whole-batch APKs verified', json.dumps(dict(suite=SUITE, controlSha=CONTROL,
        sourceSha=sha, commonDriver=manifests['candidate']['apks']['test']['sha256'], sharedAssets=asset_hash)))


def validate(document):
    configure_helpers()
    if document.get('plan', {}).get('controlSha') != CONTROL:
        raise ValueError('Whole-batch result has a different baseline')
    return legacy.validate(document)


def report():
    document = validate(json.loads((OUT / 'paired-results.json').read_text()))
    rows = legacy.summary(document)
    write(OUT / 'paired-summary.json', rows)
    ci.notice('notice', 'Whole-batch paired pipeline verified', '\n'.join(
        f'{row["mode"]}/{row["phase"]}: draw paired delta={row["inputToDrawMs"]["pairedMedianDelta"]:+.2f}ms'
        for row in rows))
    ci.notice('notice', 'Whole-batch result identity', json.dumps(dict(suite=SUITE,
        controlSha=CONTROL, sourceSha=document['plan']['sourceSha'], runId=document['plan']['runId'],
        measured=288, excludedWarmup=72, resultSha256=legacy.result_digest(document))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('build', 'emulator', 'report'))
    args = parser.parse_args()
    require_ci()
    configure_helpers()
    if args.action == 'build':
        build()
    elif args.action == 'report':
        report()
    else:
        plan = json.loads((OUT / 'plan.json').read_text())
        if (plan['sourceSha'], plan['runId']) != ci.identity() or plan['controlSha'] != CONTROL:
            raise ValueError('Whole-batch plan belongs to another run/source')
        legacy.verify_bundles(plan['manifests'])
        ci.emulator(operation=lambda: legacy.measure(plan))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        ci.notice('error', 'Whole-batch comparison failed', str(error)[:2000])
        raise SystemExit(1)
