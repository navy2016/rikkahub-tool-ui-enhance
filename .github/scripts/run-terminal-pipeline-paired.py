"""Frozen one-site control versus production projection on one booted Android guest."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
import re
import shutil
import statistics
import subprocess
import time
import zipfile
import zlib
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('paired_pipeline', Path(__file__).with_name('run-terminal-pipeline.py'))
ci = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ci)
ROOT = ci.ROOT
OUT = ROOT / 'artifacts/terminal-pipeline-paired'
BUILD = ROOT / 'artifacts/pipeline-paired-build'
PAGE = 'app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt'
PATCH = ROOT / 'benchmarks/terminal-pipeline-paired/legacy-panel-subscription.patch'
PRODUCTION_PAGE_SHA = '38f252a5ef188a5e92f357288b6e8fea025151af69331760ad6be63fbda6a4b8'
CONTROL_PAGE_SHA = 'bb212a454b486fe1745bfa982e0f0ed6c52057e9c43dea6092f009d56c3c1050'
NEW = '    val autoScroll by rememberTerminalFollowEnabled(viewportController)\n'
OLD = ('    val viewportState by viewportController.state.collectAsStateWithLifecycle()\n'
       '    val autoScroll = viewportState.autoScroll\n')
ARMS = ('legacy', 'candidate')
PAIRS = 4
SUITE = 'terminal-pipeline-paired-v1'
PROBES = frozenset(('androidPty', 'prootPipe', 'prootPty', 'cookedLogin', 'directWorkload', 'managerPrintf',
                   'managerWorkload', 'cookedBuiltin', 'rawLogin', 'cookedLoginOnly', 'pipeLogin',
                   'cookedSttyOnly', 'rawExternal', 'pipePrefix', 'redirectOnly'))


def require_ci():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_OS') != 'Linux':
        raise ValueError('Paired Android builds/runs are allowed only in GitHub Actions')


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def command(args, timeout=60):
    subprocess.run(args, cwd=ROOT, check=True, timeout=timeout)


def original_bytes():
    data = (ROOT / PAGE).read_bytes()
    if hashlib.sha256(data).hexdigest() != PRODUCTION_PAGE_SHA or data.count(NEW.encode()) != 1:
        raise ValueError('Production page differs from the frozen paired experiment')
    if hashlib.sha256(data.replace(NEW.encode(), OLD.encode())).hexdigest() != CONTROL_PAGE_SHA:
        raise ValueError('Legacy subscription site no longer matches the frozen control')
    return data


def experiment_sources():
    paths = ('.github/scripts/run-terminal-pipeline-paired.py', '.github/scripts/run-terminal-pipeline.py',
             '.github/workflows/terminal-pipeline-paired.yml', str(PATCH.relative_to(ROOT)))
    return {path: ci.digest(ROOT / path) for path in paths}


def verify_sources(manifests):
    if set(manifests) != set(ARMS):
        raise ValueError('Missing paired source arm')
    old, new = manifests['legacy']['sources'], manifests['candidate']['sources']
    if (set(old) != set(new) or PAGE not in new or new[PAGE] != PRODUCTION_PAGE_SHA
            or old[PAGE] != CONTROL_PAGE_SHA or {p for p in new if new[p] != old[p]} != {PAGE}):
        raise ValueError('Paired source difference is not exactly the frozen panel subscription site')


def inventory(apk):
    with zipfile.ZipFile(apk) as archive:
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist()
                if not name.endswith('/') and (name.startswith(('assets/', 'lib/', 'res/'))
                or name in ('AndroidManifest.xml', 'resources.arsc'))}


def verify_bundles(manifests, check_files=True, expected_identity=None):
    verify_sources(manifests)
    sha, run_id = expected_identity if expected_identity is not None else ci.identity()
    if not re.fullmatch('[a-f0-9]{40}', sha) or not re.fullmatch('[0-9]+', run_id):
        raise ValueError('Invalid paired source/run identity')
    common_test, common_assets, common_signer, common_overlay = None, None, None, None
    targets = set()
    for arm in ARMS:
        manifest = manifests[arm]
        if (manifest['sourceSha'] != sha or manifest['runId'] != run_id or manifest['target_debuggable'] is not False
                or manifest['target_minified'] is not False or manifest['target_release_derived'] is not True):
            raise ValueError('Paired bundle identity or Release properties differ')
        if set(manifest['apks']) != {'target', 'test'}:
            raise ValueError('Incomplete paired APK bundle')
        target, driver = manifest['apks']['target'], manifest['apks']['test']
        if target['package'] != ci.PACKAGE or driver['package'] != ci.TEST_PACKAGE:
            raise ValueError('Incorrect paired target/test package')
        targets.add(target['sha256'])
        if not re.fullmatch('[a-f0-9]{64}', target['sha256']) or not re.fullmatch('[a-f0-9]{64}', driver['sha256']):
            raise ValueError('Invalid paired APK digest')
        signer = target['certificate_sha256']
        if (not isinstance(signer, list) or not signer or signer != driver['certificate_sha256']
                or any(not isinstance(value, str) or not re.fullmatch('[a-f0-9]{64}', value) for value in signer)):
            raise ValueError('Paired APK signer mismatch')
        overlay = manifest['proot_overlay']
        if overlay['source_sha'] != sha or overlay['build_run'] != run_id or overlay['overlay_only'] is not True:
            raise ValueError('Incorrect paired PRoot provenance')
        for name, value in (('test', driver['sha256']), ('signer', signer), ('overlay', overlay)):
            previous = {'test': common_test, 'signer': common_signer, 'overlay': common_overlay}[name]
            if previous is not None and previous != value:
                raise ValueError('Paired arms do not share the same ' + name)
        common_test, common_signer, common_overlay = driver['sha256'], signer, overlay
        if check_files:
            for role, apk in manifest['apks'].items():
                expected = BUILD / ('common-test.apk' if role == 'test' else arm + '/target.apk')
                if (ROOT / apk['file']).resolve() != expected.resolve():
                    raise ValueError('Paired APK path is outside its fixed build slot')
                if ci.digest(expected) != apk['sha256'] or expected.stat().st_size != apk['bytes']:
                    raise ValueError('Paired APK changed after packaging')
            assets = inventory(ROOT / target['file'])
            if not assets or (common_assets is not None and assets != common_assets):
                raise ValueError('Paired target manifests/resources/runtime assets differ')
            common_assets = assets
    if len(targets) != 2:
        raise ValueError('Legacy and candidate APKs unexpectedly have the same hash')
    return hashlib.sha256(json.dumps(common_assets, sort_keys=True).encode()).hexdigest() if check_files else None


def build():
    require_ci()
    sha, run_id = ci.identity()
    before = original_bytes()
    if subprocess.check_output(['git', '-c', 'core.fileMode=false', 'diff', '--name-only', 'HEAD'], cwd=ROOT).strip():
        raise ValueError('Paired build needs a clean tracked checkout')
    OUT.mkdir(parents=True, exist_ok=False)
    BUILD.mkdir(parents=True, exist_ok=False)
    manifests = {}
    patched = False
    try:
        for arm in ('candidate', 'legacy'):
            if arm == 'legacy':
                command(['git', 'apply', '--check', str(PATCH)])
                command(['git', 'apply', str(PATCH)])
                patched = True
                if (ROOT / PAGE).read_bytes() != before.replace(NEW.encode(), OLD.encode()):
                    raise ValueError('Control patch changed more than the frozen subscription')
                changed = subprocess.check_output(['git', '-c', 'core.fileMode=false', 'diff', '--name-only', 'HEAD'],
                                                  cwd=ROOT, text=True).splitlines()
                if changed != [PAGE]:
                    raise ValueError('Unexpected tracked changes while building legacy target')
            tasks = [':app:assembleTerminaltest']
            if arm == 'candidate':
                tasks.append(':app:assembleTerminaltestAndroidTest')
            command(['bash', '.github/scripts/run-terminal-gradle.sh', '-PterminalPipelineTests=true',
                     '-PincludeX86_64AbiForTests=true', *tasks, '--stacktrace'], timeout=1500)
            ci.prepare()
            manifest = json.loads((ci.OUT / 'manifest.json').read_text())
            slot = BUILD / arm
            slot.mkdir()
            for role, apk in manifest['apks'].items():
                dest = BUILD / 'common-test.apk' if role == 'test' else slot / 'target.apk'
                if role == 'test' and arm == 'legacy':
                    if ci.digest(dest) != apk['sha256']:
                        raise ValueError('Legacy target must reuse the exact candidate test APK')
                else:
                    shutil.copyfile(ROOT / apk['file'], dest)
                apk['file'] = str(dest.relative_to(ROOT))
            shutil.copyfile(ROOT / 'artifacts/terminal-validation/gradle.log', OUT / (arm + '-build.log'))
            manifests[arm] = manifest
    finally:
        if patched:
            command(['git', 'apply', '--reverse', '--check', str(PATCH)])
            command(['git', 'apply', '--reverse', str(PATCH)])
        if (ROOT / PAGE).read_bytes() != before:
            raise ValueError('Production page was not restored after paired build')
    asset_hash = verify_bundles(manifests)
    if manifests['candidate']['sources'] != ci.sources():
        raise ValueError('Candidate source provenance changed during build')
    plan = dict(suite=SUITE, sourceSha=sha, runId=run_id, pairs=PAIRS,
                manifests=manifests, experimentSources=experiment_sources(),
                controlPatchSha256=ci.digest(PATCH), sharedAssetInventorySha256=asset_hash,
                candidatePageSha256=PRODUCTION_PAGE_SHA, legacyPageSha256=CONTROL_PAGE_SHA)
    write(OUT / 'plan.json', plan)
    write(ci.OUT / 'manifest.json', manifests['candidate'])
    ci.notice('notice', 'Paired pipeline APKs verified', json.dumps(dict(sourceSha=sha, runId=run_id,
              targets={arm: manifests[arm]['apks']['target']['sha256'] for arm in ARMS},
              sharedTest=manifests['candidate']['apks']['test']['sha256'], assetInventory=asset_hash)))


def order(pair):
    return ARMS if pair % 2 == 0 else tuple(reversed(ARMS))


def check_samples(rows):
    ci.validate_samples(['PIPELINE_SAMPLE ' + json.dumps(row) for row in rows])
    if any(type(row.get('panelCompositions')) is not int or row['panelCompositions'] <= 0 for row in rows):
        raise ValueError('Paired run has missing panel composition measurements')
    return {row['launch'] for row in rows}


def validate(document):
    plan = document['plan']
    if (document.get('suite') != SUITE or plan.get('suite') != SUITE
            or type(plan.get('pairs')) is not int or plan['pairs'] != PAIRS):
        raise ValueError('Unknown paired experiment protocol')
    verify_bundles(plan['manifests'], check_files=False, expected_identity=(plan['sourceSha'], plan['runId']))
    boot, device = document.get('bootId', ''), document.get('device')
    if (not re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', boot) or not isinstance(device, dict)
            or set(device) != {'fingerprint', 'size', 'density', 'ime'}
            or any(not isinstance(value, str) or not value.strip() for value in device.values())):
        raise ValueError('Missing paired boot/device identity')
    if set(document['preflights']) != set(ARMS) or set(document['warmups']) != set(ARMS):
        raise ValueError('Both paired arms need transport and excluded warmup passes')
    launches, last_end = set(), 0

    def receipt(entry, arm, kind):
        nonlocal last_end
        target = plan['manifests'][arm]['apks']['target']['sha256']
        driver = plan['manifests'][arm]['apks']['test']['sha256']
        if (entry['arm'] != arm or entry['kind'] != kind or entry['targetSha256'] != target or entry['testSha256'] != driver
                or entry['installedApkHashes'] != {'target': target, 'test': driver}
                or entry['bootBefore'] != boot or entry['bootAfter'] != boot or entry['device'] != device):
            raise ValueError('Paired receipt does not match its arm, APK or booted device')
        start, end = entry['startedNanos'], entry['endedNanos']
        if type(start) is not int or type(end) is not int or not last_end < start < end:
            raise ValueError('Paired invocations overlap or lack monotonic receipts')
        last_end = end

    for arm in ARMS:
        entry = document['preflights'][arm]
        receipt(entry, arm, 'transport')
        probes = entry['probes']
        if len(probes) != 15 or {p['probe'] for p in probes} != PROBES:
            raise ValueError('Missing paired transport matrix')
        if any(p['matched'] is not True or p['timedOut'] is not False or p['readerFailure'] is not None for p in probes):
            raise ValueError('Paired transport preflight failed')
    for arm in ARMS:
        entry = document['warmups'][arm]
        receipt(entry, arm, 'warmup')
        ids = check_samples(entry['samples'])
        if ids & launches:
            raise ValueError('Paired warmup reused a launch identity')
        launches.update(ids)
    if len(document['invocations']) != PAIRS * 2:
        raise ValueError('Incomplete paired invocation matrix')
    for index, entry in enumerate(document['invocations']):
        pair, position = divmod(index, 2)
        arm = order(pair)[position]
        if type(entry.get('pair')) is not int or entry['pair'] != pair or type(entry.get('position')) is not int or entry['position'] != position:
            raise ValueError('Paired AB/BA order was not followed')
        receipt(entry, arm, 'measurement')
        ids = check_samples(entry['samples'])
        if ids & launches:
            raise ValueError('Paired samples reused a warmup or previous launch')
        launches.update(ids)
    return document


def read_boot():
    boot = ci.output(['adb', 'shell', 'cat', '/proc/sys/kernel/random/boot_id']).strip()
    if not re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', boot):
        raise ValueError('Android guest did not provide a valid boot ID')
    return boot


def measure(plan):
    original_out = ci.OUT
    document = dict(suite=SUITE, plan=plan, bootId=read_boot(), device=None,
                    preflights={}, warmups={}, invocations=[])

    def one(arm, label, transport=False, pair=None, position=None):
        manifest = plan['manifests'][arm]
        ci.OUT = OUT / label
        ci.OUT.mkdir(exist_ok=False)
        started, boot_before = time.monotonic_ns(), read_boot()
        if boot_before != document['bootId']:
            ci.OUT = original_out
            raise ValueError('Paired guest rebooted before invocation: ' + label)
        try:
            result = ci.run_verified_bundle(manifest, 'transport' if transport else 'pipeline', emit_evidence=False)
        except (ValueError, OSError, subprocess.SubprocessError, KeyError) as error:
            ci.notice('error', 'Paired pipeline arm failed', f'{label}: {type(error).__name__}: {str(error)[:350]}')
            for name in ('instrumentation.log', 'pipeline-logcat.txt'):
                path = ci.OUT / name
                if path.exists():
                    ci.notice('error', 'Paired ' + label + ' ' + name, ci.failure_excerpt(path))
            raise
        finally:
            ci.OUT = original_out
        receipt = dict(arm=arm, targetSha256=manifest['apks']['target']['sha256'],
                       testSha256=manifest['apks']['test']['sha256'], bootBefore=boot_before,
                       bootAfter=read_boot(), startedNanos=started, endedNanos=time.monotonic_ns(), device=result['device'],
                       installedApkHashes=result['installedApkHashes'],
                       kind='transport' if transport else ('measurement' if pair is not None else 'warmup'))
        if document['device'] is None:
            document['device'] = result['device']
        if receipt['bootAfter'] != document['bootId'] or receipt['device'] != document['device']:
            raise ValueError('Paired boot/device changed during invocation: ' + label)
        if transport:
            receipt['probes'] = result['probes']
        else:
            receipt['samples'] = result['samples']
        if pair is not None:
            receipt.update(pair=pair, position=position)
        write(OUT / label / 'receipt.json', receipt)
        print(f'PAIRED_COMPLETED {label}', flush=True)
        return receipt

    try:
        for arm in ARMS:
            document['preflights'][arm] = one(arm, 'preflight-' + arm, transport=True)
            write(OUT / 'progress.json', document)
        for arm in ARMS:
            document['warmups'][arm] = one(arm, 'warmup-' + arm)
            write(OUT / 'progress.json', document)
        for pair in range(PAIRS):
            for position, arm in enumerate(order(pair)):
                document['invocations'].append(one(arm, f'pair-{pair}-{position}-{arm}', pair=pair, position=position))
                write(OUT / 'progress.json', document)
        validate(document)
        write(OUT / 'paired-results.json', document)
    finally:
        ci.OUT = original_out


def load_plan():
    plan = json.loads((OUT / 'plan.json').read_text())
    sha, run_id = ci.identity()
    if (plan['sourceSha'] != sha or plan['runId'] != run_id or plan['suite'] != SUITE or plan['pairs'] != PAIRS
            or plan['experimentSources'] != experiment_sources() or plan['controlPatchSha256'] != ci.digest(PATCH)):
        raise ValueError('Paired plan does not belong to this source/run')
    original_bytes()
    if ci.sources() != plan['manifests']['candidate']['sources']:
        raise ValueError('Paired production source changed after building')
    if verify_bundles(plan['manifests']) != plan['sharedAssetInventorySha256']:
        raise ValueError('Paired runtime assets changed after building')
    return plan


def emulator():
    require_ci()
    plan = load_plan()
    ci.emulator(operation=lambda: measure(plan))


def summary(document):
    validate(document)
    rows = []
    metrics = ('panelCompositions', 'inputToDrawMs', 'inputToVisibleCheckMs')
    for mode in ci.MODES:
        for phase in ci.PHASES:
            values = {metric: {arm: [] for arm in ARMS} for metric in metrics}
            for pair in range(PAIRS):
                for arm in ARMS:
                    entry = next(e for e in document['invocations'] if e['pair'] == pair and e['arm'] == arm)
                    samples = [s for s in entry['samples'] if s['mode'] == mode and s['phase'] == phase]
                    for metric in metrics:
                        values[metric][arm].append(statistics.median(s[metric] for s in samples))
            row = dict(mode=mode, phase=phase, pairs=PAIRS, samplesPerArm=PAIRS * 3)
            for metric, arms in values.items():
                delta = [new - old for old, new in zip(arms['legacy'], arms['candidate'])]
                row[metric] = dict(legacyMedian=statistics.median(arms['legacy']),
                                   candidateMedian=statistics.median(arms['candidate']),
                                   pairedMedianDelta=statistics.median(delta), pairedDeltas=delta)
            rows.append(row)
    return rows


def evidence(title, value):
    data = json.dumps(value, separators=(',', ':')).encode()
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    if len(parts) > 8:
        raise ValueError('Paired evidence exceeds the per-step annotation budget')
    for index, part in enumerate(parts, 1):
        ci.notice('notice', f'{title} {index}/{len(parts)}', 'zlib-base64 sha256=' + hashlib.sha256(data).hexdigest() + '\n' + part)


def result_digest(document):
    return hashlib.sha256(json.dumps(document, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def report(pair=None, setup=False):
    plan = load_plan()
    document = validate(json.loads((OUT / 'paired-results.json').read_text()))
    if document['plan'] != plan:
        raise ValueError('Paired results refer to another build plan')
    identity = dict(suite=SUITE, sourceSha=plan['sourceSha'], runId=plan['runId'],
                    resultSha256=result_digest(document), bootId=document['bootId'], device=document['device'])
    if setup:
        evidence('Terminal paired pipeline setup', dict(identity, preflights=document['preflights'], warmups=document['warmups']))
        return
    if pair is not None:
        evidence(f'Terminal paired samples pair={pair}', dict(identity, pair=pair,
                 invocations=[e for e in document['invocations'] if e['pair'] == pair]))
        return
    rows = summary(document)
    write(OUT / 'paired-summary.json', rows)
    text = []
    for row in rows:
        work = row['panelCompositions']
        draw = row['inputToDrawMs']
        text.append(f'{row["mode"]}/{row["phase"]}: panel {work["legacyMedian"]:g}→{work["candidateMedian"]:g}; '
                    f'draw paired Δ={draw["pairedMedianDelta"]:+.2f}ms; n={PAIRS} launch pairs')
    ci.notice('notice', 'Terminal paired pipeline verified', '\n'.join(text))
    evidence('Terminal paired pipeline provenance', dict(identity, plan=plan, summary=rows,
             preflightPassed={arm: len(document['preflights'][arm]['probes']) for arm in ARMS},
             excludedWarmupSamples={arm: len(document['warmups'][arm]['samples']) for arm in ARMS},
             note='Four same-boot AB/BA launch pairs per mode/phase. Differences are candidate minus legacy '
                  'of within-launch medians; echoes are not independent device trials. Synthetic semantics '
                  'input and traced draw are not physical typing, GPU presentation, phone FPS or cold startup.'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('build', 'emulator', 'report'))
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--pair', type=int, choices=range(PAIRS))
    group.add_argument('--setup', action='store_true')
    args = parser.parse_args()
    try:
        if args.action == 'build':
            build()
        elif args.action == 'emulator':
            emulator()
        else:
            report(args.pair, args.setup)
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as error:
        ci.notice('error', 'Paired terminal pipeline failed', type(error).__name__ + ': ' + str(error)[:2000])
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
