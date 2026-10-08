"""SHA-bound full-page/native-PTY diagnostics, not a physical-device benchmark."""
import argparse
import base64
import hashlib
import json
import math
import os
import re
import statistics
import subprocess
import sys
import zipfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'artifacts/terminal-pipeline'
PACKAGE = 'me.rerere.rikkahub.dev.next.terminaltest'
TEST_PACKAGE = PACKAGE + '.test'
CLASS = 'me.rerere.rikkahub.pipeline.TerminalPipelineInstrumentedTest'
MODES = ('chunkedLayers', 'lazyHistory', 'lazyHistoryIme')
PHASES = ('mounted', 'imeVisible', 'imeHidden', 'remount')
STAGES = ('INPUT_ENQUEUE_STARTED', 'INPUT_WRITE_STARTED', 'OUTPUT_READ', 'EMULATOR_FED',
          'UI_OUTPUT_RECEIVED', 'FRAME_PUBLISHED', 'FRAME_DRAWN')


def notice(level, title, message):
    text = message[:2800].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::{level} title={title}::{text}', flush=True)


def identity():
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    if not re.fullmatch('[a-f0-9]{40}', sha) or not re.fullmatch('[0-9]+', run):
        raise ValueError('Expected exact CI SHA and run ID')
    return sha, run


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def sources():
    paths = [
        'app/src/main/java/me/rerere/rikkahub/data/container/BackgroundProcessManager.kt',
        'app/src/main/java/me/rerere/rikkahub/data/container/NativePtyBridge.kt',
        'app/src/main/java/me/rerere/rikkahub/data/container/NativePtyProcess.kt',
        'app/src/main/java/me/rerere/rikkahub/data/container/PRootManager.kt',
        'app/src/main/java/me/rerere/rikkahub/data/container/TerminalPipelineTrace.kt',
        'app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalPipelineInstrumentation.kt',
        'app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt',
        'app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalViewportBinding.kt',
        'app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalLazyItemExecutor.kt',
        'app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalViewportScrollEffects.kt',
        'app/src/main/java/me/rerere/rikkahub/utils/TerminalEmulator.kt',
        'app/src/main/cpp/rikkahub_pty.cpp', 'app/build.gradle.kts',
        'app/src/terminaltest/AndroidManifest.xml',
        'app/src/terminaltest/java/me/rerere/rikkahub/pipeline/TerminalPipelineTestActivity.kt',
        'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalPipelineInstrumentedTest.kt',
        'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalPtyTransportInstrumentedTest.kt',
        'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalPipelineWorkload.kt',
        '.github/scripts/run-terminal-pipeline.py',
        '.github/scripts/build-terminal-proot.py',
        'benchmarks/proot-x86_64/fork-to-clone.patch',
        'benchmarks/production_emulator.py',
        'gradle/libs.versions.toml', 'app/compose_compiler_config.conf',
        'app/src/main/res/font/jetbrains_mono.ttf',
    ]
    return {path: digest(ROOT / path) for path in paths}


def output(command, seconds=30):
    return subprocess.check_output(command, text=True, stderr=subprocess.STDOUT, timeout=seconds)


def prepare():
    sha, run = identity()
    OUT.mkdir(parents=True, exist_ok=True)
    overlay = json.loads((OUT / 'proot-overlay.json').read_text())
    if overlay['source_sha'] != sha or overlay['build_run'] != run or not overlay['overlay_only']:
        raise ValueError('Test-only PRoot overlay provenance mismatch')
    tools = sorted((Path(os.environ['ANDROID_HOME']) / 'build-tools').glob('*/aapt'),
                   key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.parent.name)))[-1].parent
    apks = {}
    for role, folder, package in (('target', 'terminaltest', PACKAGE),
                                  ('test', 'androidTest/terminaltest', TEST_PACKAGE)):
        files = list((ROOT / 'app/build/outputs/apk' / folder).glob('*.apk'))
        if len(files) != 1:
            raise ValueError(f'Expected exactly one {role} APK, found {len(files)}')
        apk = files[0]
        manifest = output([str(tools / 'aapt'), 'dump', 'badging', str(apk)])
        if not manifest.startswith(f"package: name='{package}'"):
            raise ValueError(f'Wrong {role} package')
        if role == 'target' and 'application-debuggable' in manifest:
            raise ValueError('Pipeline target must be Release-derived, not debuggable')
        signing = output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], 60)
        certificates = re.findall(r'^.*?\bcertificate SHA-256 digest: ([a-fA-F0-9]{64})\s*$', signing, re.M)
        if not certificates or not re.search(r'Verified using v[23](?:\.1)? scheme .*: true', signing):
            raise ValueError('Unsigned pipeline APK')
        with zipfile.ZipFile(apk) as archive:
            if archive.testzip() is not None:
                raise ValueError('Corrupt pipeline APK')
            if role == 'target':
                revision = archive.read('META-INF/version-control-info.textproto').decode()
                if re.findall(r'revision:\s*"([a-f0-9]{40})"', revision) != [sha]:
                    raise ValueError('Embedded target revision mismatch')
                if 'lib/x86_64/librikkahub-pty.so' not in archive.namelist():
                    raise ValueError('Missing x86_64 native PTY')
                if hashlib.sha256(archive.read('assets/proot/proot-x86_64')).hexdigest() != overlay['overlay_sha256']:
                    raise ValueError('Missing test-only PRoot fork compatibility overlay')
                for name in ('proot-aarch64', 'loader-x86_64', 'loader32-x86_64', 'libtalloc-x86_64.so.2'):
                    if hashlib.sha256(archive.read('assets/proot/' + name)).hexdigest() != digest(ROOT / 'app/src/main/assets/proot' / name):
                        raise ValueError('Unexpected production runtime asset modification: ' + name)
        apks[role] = dict(file=str(apk.relative_to(ROOT)), sha256=digest(apk), bytes=apk.stat().st_size,
                          package=package, certificate_sha256=certificates)
    if apks['target']['certificate_sha256'] != apks['test']['certificate_sha256']:
        raise ValueError('Instrumentation signer differs from target')
    manifest = dict(suite='terminal-pipeline-v1', sourceSha=sha, runId=run, sources=sources(), apks=apks,
                    target_debuggable=False, target_minified=False, target_release_derived=True, proot_overlay=overlay)
    (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    notice('notice', 'Terminal pipeline APK identity', json.dumps({k: v for k, v in manifest.items() if k not in ('sources', 'proot_overlay')}))


def validate_samples(lines):
    rows, seen, launches = [], set(), {}
    for line in lines:
        if 'PIPELINE_SAMPLE ' not in line:
            continue
        row = json.loads(line.split('PIPELINE_SAMPLE ', 1)[1])
        key = (row.get('mode'), row.get('phase'), row.get('iteration'))
        if key in seen or key[0] not in MODES or key[1] not in PHASES or type(key[2]) is not int or key[2] not in range(3):
            raise ValueError('Duplicate or unknown pipeline sample')
        launch = row.get('launch', '')
        if not re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', launch):
            raise ValueError('Invalid pipeline launch identity')
        if launches.setdefault(key[0], launch) != launch:
            raise ValueError('Mixed launches for one renderer')
        expected_virtual = key[0] == 'lazyHistoryIme' or (key[0] == 'lazyHistory' and key[1] in ('mounted', 'remount'))
        if type(row.get('virtual')) is not bool or row['virtual'] != expected_virtual:
            raise ValueError('Incorrect pipeline backend')
        if type(row.get('frameRevision')) is not int or row['frameRevision'] < 0:
            raise ValueError('Invalid pipeline frame revision')
        expected_bytes = len(f'ECHO:{key[1]}-{key[2]}\r\n'.encode())
        if type(row.get('outputBytes')) is not int or row['outputBytes'] != expected_bytes:
            raise ValueError('Incorrect echo byte count')
        for name in (*STAGES, 'inputToDrawMs', 'inputToVisibleCheckMs'):
            value = row.get(name)
            if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
                raise ValueError('Missing or invalid pipeline duration: ' + name)
        # Queue-return/emit-return events are NOT an ordering guarantee; actor/UI may run first.
        for a, b in (('INPUT_ENQUEUE_STARTED', 'INPUT_WRITE_STARTED'), ('INPUT_WRITE_STARTED', 'OUTPUT_READ'),
                     ('OUTPUT_READ', 'EMULATOR_FED'), ('FRAME_PUBLISHED', 'FRAME_DRAWN'),
                     ('inputToDrawMs', 'inputToVisibleCheckMs')):
            if row[a] > row[b]:
                raise ValueError(f'Invalid pipeline order: {a}/{b}')
        if row['inputToDrawMs'] != row['FRAME_DRAWN']:
            raise ValueError('Draw latency differs from traced event')
        rows.append(row)
        seen.add(key)
    if seen != {(mode, phase, i) for mode in MODES for phase in PHASES for i in range(3)}:
        raise ValueError(f'Incomplete pipeline sample matrix: {len(seen)}/36')
    if len(set(launches.values())) != len(MODES):
        raise ValueError('Launch identity reused across renderers')
    return rows


def require_instrumentation(text, expected=3):
    label = 'test' if expected == 1 else 'tests'
    if not re.search(rf'^OK \({expected} {label}\)\s*$', text, re.M) or not re.search(r'^INSTRUMENTATION_CODE: -1\s*$', text, re.M):
        raise ValueError(f'Pipeline instrumentation did not finish exactly {expected} {label}')
    if any(marker in text for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=',
                                        'INSTRUMENTATION_STATUS_CODE: -2', 'INSTRUMENTATION_STATUS_CODE: -3',
                                        'INSTRUMENTATION_STATUS_CODE: -4')):
        raise ValueError('Pipeline instrumentation failed or skipped a case')


def run(suite=None):
    sha, run_id = identity()
    manifest = json.loads((OUT / 'manifest.json').read_text())
    if manifest['sourceSha'] != sha or manifest['runId'] != run_id or manifest['sources'] != sources():
        raise ValueError('Pipeline bundle does not match the requested source')
    for role, apk in manifest['apks'].items():
        path = ROOT / apk['file']
        if digest(path) != apk['sha256']:
            raise ValueError('Pipeline APK hash mismatch')
        remote = f'/data/local/tmp/terminal-pipeline-{role}.apk'
        output(['adb', 'push', str(path), remote], 180)
        install = output(['adb', 'shell', 'pm', 'install', '-r', '-t', remote], 180)
        if 'Success' not in install:
            raise ValueError('Pipeline install rejected: ' + install[-500:])
    # Target SDK 28 delegates the first notification prompt to Android 13+. A foreground-service
    # channel can pause the test Activity after its first draw and remove RESUMED Compose roots.
    # Preset ONLY this disposable test package; never change the release app or production policy.
    output(['adb', 'shell', 'pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS'])
    permissions = output(['adb', 'shell', 'dumpsys', 'package', PACKAGE])
    if not re.search(r'android\.permission\.POST_NOTIFICATIONS: granted=true', permissions):
        raise ValueError('Test notification permission not granted')
    compile_result = output(['adb', 'shell', 'cmd', 'package', 'compile', '-f', '-m', 'speed', PACKAGE], 180)
    if 'Success' not in compile_result:
        raise ValueError('Full target compilation failed: ' + compile_result[-500:])
    output(['adb', 'shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1'])
    output(['adb', 'logcat', '-c'])
    device = {label: output(['adb', 'shell', *args]).strip() for label, args in (
        ('fingerprint', ['getprop', 'ro.build.fingerprint']), ('size', ['wm', 'size']),
        ('density', ['wm', 'density']), ('ime', ['settings', 'get', 'secure', 'default_input_method']))}
    (OUT / 'device.json').write_text(json.dumps(device, indent=2) + '\n')
    transport = (suite or os.environ.get('TERMINAL_PIPELINE_SUITE', 'pipeline')) == 'transport'
    methods = ('transportNativeAndProotProduceExactFixedBytes',) if transport else (
        'pipelineDefaultPtyEchoKeyboardAndRemount', 'pipelineVirtualPtyEchoKeyboardAndRemount',
        'pipelineStableVirtualPtyEchoKeyboardAndRemount')
    test_class = 'me.rerere.rikkahub.pipeline.TerminalPtyTransportInstrumentedTest' if transport else CLASS
    logs, failed = [], []
    try:
        for method in methods:
            # One target process per mode. A prior failed test's pending Service teardown must
            # not consume another mode's samples or make its Activity fail before test startup.
            output(['adb', 'shell', 'am', 'force-stop', PACKAGE])
            output(['adb', 'shell', 'am', 'force-stop', TEST_PACKAGE])
            log = OUT / (method + '.log')
            with log.open('w') as stream:
                result = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', test_class + '#' + method,
                                         TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'],
                                        stdout=stream, stderr=subprocess.STDOUT, timeout=300)
            text = log.read_text()
            logs.append(text)
            try:
                if result.returncode != 0:
                    raise ValueError(f'Instrumentation exit {result.returncode}')
                require_instrumentation(text, expected=1)
            except ValueError as error:
                failed.append(method + ': ' + str(error))
    finally:
        (OUT / 'instrumentation.log').write_text('\n'.join(logs))
        logcat = output(['adb', 'logcat', '-d', '-v', 'threadtime', 'TerminalPipelineTest:I',
                         'TerminalPipelineActivity:I', 'NativePtyProcess:W', 'AndroidRuntime:E', 'TestRunner:I', 'RikkahubPty:E', '*:S'])
        (OUT / 'pipeline-logcat.txt').write_text(logcat)
        windows = output(['adb', 'shell', 'dumpsys', 'window', 'windows'])
        (OUT / 'window-focus.txt').write_text('\n'.join(line.strip() for line in windows.splitlines()
            if 'mCurrentFocus=' in line or 'mFocusedApp=' in line) + '\n')
    if failed:
        raise ValueError('; '.join(failed))
    if transport:
        probes = [json.loads(line.split('TRANSPORT_PROBE ', 1)[1]) for line in logcat.splitlines() if 'TRANSPORT_PROBE ' in line]
        expected_probes = {'androidPty', 'prootPipe', 'prootPty', 'cookedLogin', 'directWorkload', 'managerPrintf', 'managerWorkload'}
        expected_probes.update(('cookedBuiltin', 'rawLogin', 'cookedLoginOnly', 'pipeLogin', 'cookedSttyOnly',
                                'rawExternal', 'pipePrefix', 'redirectOnly'))
        if len(probes) != len(expected_probes) or {p['probe'] for p in probes} != expected_probes:
            raise ValueError('Missing transport probe matrix')
        if not all(p['matched'] and not p['timedOut'] and p['readerFailure'] is None for p in probes):
            raise ValueError('Transport probes failed')
        (OUT / 'transport-results.json').write_text(json.dumps(dict(manifest=manifest, device=device, probes=probes), indent=2) + '\n')
        for start in range(0, len(probes), 4):
            notice('notice', f'Terminal transport probes verified {start // 4 + 1}', json.dumps(probes[start:start + 4]))
        return
    rows = validate_samples(logcat.splitlines())
    report = dict(manifest=manifest, device=device, samples=rows,
                  note='Full production page + native PTY in an emulator; synthetic input, traced first draw, '
                       'and separately timed test visibility confirmation. Neither GPU presentation nor phone FPS.')
    data = json.dumps(report, separators=(',', ':')).encode()
    (OUT / 'pipeline-results.json').write_text(json.dumps(report, indent=2) + '\n')
    summary = []
    for mode in MODES:
        for phase in PHASES:
            group = [r for r in rows if r['mode'] == mode and r['phase'] == phase]
            summary.append(f'{mode}/{phase}: firstDraw={statistics.median(r["inputToDrawMs"] for r in group):.2f}ms '
                           f'visibleCheck={statistics.median(r["inputToVisibleCheckMs"] for r in group):.2f}ms')
    notice('notice', 'Terminal pipeline 36 samples verified', '\n'.join(summary))
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    if len(parts) > 8:
        raise ValueError('Pipeline compact evidence exceeds annotation budget')
    for number, part in enumerate(parts, 1):
        notice('notice', f'Terminal pipeline evidence {number}/{len(parts)}',
               'zlib-base64 sha256=' + hashlib.sha256(data).hexdigest() + '\n' + part)


def emulator():
    """Use the same verified non-root KVM primitives as the component benchmark."""
    import pwd
    sys.path.insert(0, str(ROOT / 'benchmarks'))
    import production_emulator as guest

    sha, run_id = identity()
    manifest = json.loads((OUT / 'manifest.json').read_text())
    if manifest['sourceSha'] != sha or manifest['runId'] != run_id or manifest['sources'] != sources():
        raise ValueError('Pipeline emulator source mismatch')
    state = ROOT / 'artifacts/pipeline-emulator-state'
    state.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    sdk = Path(env['ANDROID_HOME'])
    env.update(ANDROID_SDK_ROOT=str(sdk), ANDROID_AVD_HOME=str(state / 'avds'),
               ANDROID_USER_HOME=str(state / 'android'), ANDROID_SERIAL=guest.SERIAL)
    Path(env['ANDROID_AVD_HOME']).mkdir(exist_ok=True)
    Path(env['ANDROID_USER_HOME']).mkdir(exist_ok=True)
    env['PATH'] = str(sdk / 'platform-tools') + os.pathsep + env['PATH']
    user = pwd.getpwuid(os.getuid()).pw_name
    tools = sdk / 'cmdline-tools/latest/bin'
    process = None
    try:
        guest.logged([str(tools / 'sdkmanager'), '--install', 'emulator', guest.IMAGE],
                     OUT / 'sdk-install.log', env, 300)
        binary = sdk / 'emulator/emulator'
        acceleration = subprocess.run(guest.group_command(binary, ['-accel-check'], env, user),
                                      capture_output=True, text=True, timeout=30)
        acceleration.stdout = (acceleration.stdout or '') + (acceleration.stderr or '')
        (OUT / 'acceleration.txt').write_text(acceleration.stdout)
        guest.require_acceleration(acceleration)
        guest.logged([str(tools / 'avdmanager'), 'create', 'avd', '--force', '-n', 'terminalPipeline',
                      '--package', guest.IMAGE, '--device', 'Nexus 6'], OUT / 'avd-create.log', env, 90, 'no\n')
        config = Path(env['ANDROID_AVD_HOME']) / 'terminalPipeline.avd/config.ini'
        with config.open('a') as stream:
            stream.write('\nhw.cpu.ncore=2\nhw.ramSize=4096M\nhw.heapSize=768M\nhw.keyboard=yes\ndisk.dataPartition.size=8G\n')
        options = ['-port', '5554', '-avd', 'terminalPipeline', '-no-window', '-accel', 'on',
                   '-gpu', 'swiftshader_indirect', '-noaudio', '-no-boot-anim', '-no-snapshot', '-camera-back', 'none']
        with (OUT / 'emulator.log').open('w') as log:
            process = subprocess.Popen(guest.group_command(binary, options, env, user), env=env,
                                       stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            guest.wait_for_boot(process, env)
            os.environ.update(PATH=env['PATH'], ANDROID_SERIAL=guest.SERIAL)
            output(['adb', 'shell', 'input', 'keyevent', '82'])
            if os.environ.get('TERMINAL_PIPELINE_SUITE') == 'all':
                run('transport')
                # Keep the successful preflight's logs; the page run has independent lifecycle,
                # fresh captures and completion receipts but uses the very same APK and guest.
                import shutil
                preflight = OUT / 'transport-preflight'
                preflight.mkdir(exist_ok=True)
                for name in ('instrumentation.log', 'pipeline-logcat.txt', 'device.json', 'window-focus.txt'):
                    shutil.copyfile(OUT / name, preflight / name)
                run('pipeline')
            else:
                run()
    finally:
        guest.stop_emulator(process, env)


def failure_excerpt(path):
    """Keep first causal messages and fixed lifecycle probes, not only the tail of a Java stack."""
    from collections import deque
    contexts, lifecycle, tail = [], deque(maxlen=8), deque(maxlen=5)
    remaining, blocks = 0, 0
    with path.open(errors='replace') as stream:
        for raw in stream:
            line = raw.rstrip()[:550]
            tail.append(line)
            if any(word in line for word in ('TerminalPipelineActivity', 'PIPELINE_READY', 'PIPELINE_DIAGNOSTIC', 'PIPELINE_GEOMETRY',
                                             'TRANSPORT_PROBE', 'NativePtyProcess')):
                lifecycle.append(line)
            if remaining == 0 and blocks < 2 and any(marker in line for marker in
                ('Error in ', 'stack=', 'java.lang.', 'FATAL EXCEPTION')):
                remaining, blocks = 4, blocks + 1
            if remaining:
                contexts.append(line)
                remaining -= 1
    return '\n'.join(contexts)[:1000] + '\nLifecycle/geometry:\n' + '\n'.join(lifecycle)[-1300:] + '\nTail:\n' + '\n'.join(tail)[-400:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'run', 'emulator'))
    args = parser.parse_args()
    try:
        {'prepare': prepare, 'run': run, 'emulator': emulator}[args.action]()
        return 0
    except (ValueError, OSError, subprocess.SubprocessError, KeyError) as error:
        details = []
        for name in ('instrumentation.log', 'pipeline-logcat.txt'):
            path = OUT / name
            if path.exists():
                details.append((name, failure_excerpt(path)))
        notice('error', 'Terminal pipeline failure', type(error).__name__ + ': ' + str(error)[:400])
        for name, excerpt in details:
            notice('error', 'Terminal pipeline ' + name, excerpt)
        path = OUT / 'pipeline-logcat.txt'
        if path.exists():
            with path.open(errors='replace') as stream:
                probes = [line.strip() for line in stream if 'TRANSPORT_PROBE {' in line]
            for start in range(0, len(probes), 4):
                notice('error', f'Terminal transport probe values {start // 4 + 1}', '\n'.join(probes[start:start + 4]))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
