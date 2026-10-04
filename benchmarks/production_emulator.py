#!/usr/bin/env python3
"""Run the CI-only production benchmark with verified KVM and a bounded guest lifetime."""
import argparse
import json
import os
import pwd
import signal
import subprocess
import sys
import time
from pathlib import Path

import production_ci as ci
from production_summary import SCENARIOS

IMAGE = 'system-images;android-34;default;x86_64'
AVD = 'productionViewport'
SERIAL = 'emulator-5554'


def group_command(emulator, arguments, env, user):
    if not user or user == 'root':
        raise ValueError('The CI emulator must retain the non-root runner user')
    # Hosted runners authorize sudo to root, not arbitrary Runas groups. Use that existing grant
    # only to set this child's UID/GID, then drop privileges BEFORE executing the emulator.
    # No device modes, group membership or host policy files are edited. The clean environment
    # passes only SDK/AVD variables; neither root nor GitHub credentials reach the emulator.
    names = ('HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_AVD_HOME', 'ANDROID_USER_HOME')
    return ['sudo', '-n', '--', 'setpriv', f'--reuid={user}', '--regid=kvm', '--init-groups',
            '--no-new-privs', '--', 'env', '-i', 'PATH=/usr/bin:/bin', 'LC_ALL=C',
            *(f'{name}={env[name]}' for name in names), str(emulator), *arguments]


def require_acceleration(result):
    text = result.stdout or ''
    if result.returncode != 0 or 'KVM' not in text or 'is installed and usable' not in text:
        raise ValueError('Hardware acceleration unavailable; refusing software emulation:\n' + text[-1800:])


def logged(args, path, env, timeout, input_text=None):
    with path.open('w') as output:
        result = subprocess.run(args, input=input_text, text=True, env=env, stdout=output,
                                stderr=subprocess.STDOUT, timeout=timeout, check=False)
    if result.returncode != 0:
        raise ValueError(f'{path.stem} exited {result.returncode}:\n{path.read_text(errors="replace")[-2000:]}')


def wait_for_boot(process, env, timeout=300):
    deadline = time.monotonic() + timeout
    last = 'No boot response'
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ValueError(f'Emulator exited before boot: {process.returncode}')
        try:
            result = subprocess.run(['adb', '-s', SERIAL, 'shell', 'getprop', 'sys.boot_completed'],
                                    env=env, capture_output=True, text=True, timeout=10, check=False)
            last = (result.stdout + result.stderr)[-1000:]
            if result.returncode == 0 and result.stdout.strip() == '1':
                package = subprocess.run(['adb', '-s', SERIAL, 'shell', 'pm', 'path', 'android'],
                                         env=env, capture_output=True, text=True, timeout=10, check=False)
                if package.returncode == 0 and 'package:' in package.stdout:
                    return
                last = (package.stdout + package.stderr)[-1000:]
        except subprocess.TimeoutExpired:
            last = 'ADB boot or PackageManager probe timed out'
        time.sleep(2)
    raise ValueError('Guest boot/PackageManager did not become ready: ' + last)


def stop_emulator(process, env):
    if process is None:
        return
    try:
        subprocess.run(['adb', '-s', SERIAL, 'emu', 'kill'], env=env,
                       capture_output=True, timeout=10, check=False)
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=10)


def run(scenario, iterations, smoke):
    ci.verify_bundle()  # Reject another SHA before installing SDK packages or starting a guest.
    _, run_id = ci.env_identity()
    attempt = os.environ.get('GITHUB_RUN_ATTEMPT', '1')
    if not attempt.isdecimal():
        raise ValueError('Invalid workflow attempt')
    kind = 'smoke' if smoke else 'full'
    tag = f'{run_id}-{attempt}-{scenario}-{kind}'
    evidence = ci.ROOT / 'artifacts/terminal-production' / scenario / (tag + '-environment')
    evidence.mkdir(parents=True, exist_ok=False)
    # Large mutable guest disks are deliberately outside the uploaded evidence directory.
    state = ci.ROOT / 'artifacts/production-emulator-state' / tag
    state.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    sdk = Path(env['ANDROID_HOME'])
    env.update(ANDROID_SDK_ROOT=str(sdk), ANDROID_AVD_HOME=str(state / 'avds'),
               ANDROID_USER_HOME=str(state / 'android'), ANDROID_SERIAL=SERIAL)
    Path(env['ANDROID_AVD_HOME']).mkdir()
    Path(env['ANDROID_USER_HOME']).mkdir()
    env['PATH'] = str(sdk / 'platform-tools') + os.pathsep + env['PATH']
    tools = sdk / 'cmdline-tools/latest/bin'
    user = pwd.getpwuid(os.getuid()).pw_name
    process = None
    emulator_log = evidence / 'emulator.log'
    try:
        logged([str(tools / 'sdkmanager'), '--install', 'emulator', IMAGE],
               evidence / 'sdk-install.log', env, timeout=300)
        emulator = sdk / 'emulator/emulator'
        acceleration = subprocess.run(group_command(emulator, ['-accel-check'], env, user),
                                      capture_output=True, text=True, timeout=30, check=False)
        acceleration.stdout = (acceleration.stdout or '') + (acceleration.stderr or '')
        (evidence / 'acceleration.txt').write_text(acceleration.stdout)
        require_acceleration(acceleration)
        ci.annotation('notice', 'Production benchmark acceleration', acceleration.stdout.strip())
        logged([str(tools / 'avdmanager'), 'create', 'avd', '--force', '-n', AVD,
                '--package', IMAGE, '--device', 'Nexus 6'], evidence / 'avd-create.log', env, 90, 'no\n')
        config = Path(env['ANDROID_AVD_HOME']) / f'{AVD}.avd/config.ini'
        with config.open('a') as stream:
            stream.write('\nhw.cpu.ncore=2\nhw.ramSize=4096M\nhw.heapSize=768M\nhw.keyboard=yes\n')
        options = ['-port', '5554', '-avd', AVD, '-no-window', '-accel', 'on', '-gpu', 'swiftshader_indirect',
                   '-noaudio', '-no-boot-anim', '-no-snapshot', '-camera-back', 'none']
        cmd = group_command(emulator, options, env, user)
        (evidence / 'launch.json').write_text(json.dumps({'command': cmd, 'uid': os.getuid(),
                                                       'runner': env.get('ImageVersion'), 'image': IMAGE}, indent=2))
        with emulator_log.open('w') as output:
            process = subprocess.Popen(cmd, env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            wait_for_boot(process, env)
            logged(['adb', '-s', SERIAL, 'shell', 'input', 'keyevent', '82'], evidence / 'unlock.log', env, 15)
            os.environ.update(PATH=env['PATH'], ANDROID_SERIAL=SERIAL)
            ci.run_group(scenario, iterations, smoke)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        ci.annotation('error', 'Production benchmark environment failed', str(error))
        if emulator_log.exists():
            with emulator_log.open('rb') as stream:
                stream.seek(max(0, emulator_log.stat().st_size - 8192))
                tail = stream.read(8192).decode(errors='replace')
            lines = [line for line in tail.splitlines() if any(word in line.lower() for word in
                     ('error', 'fatal', 'kvm', 'accel', 'crash', 'hang', 'permission', 'memory'))]
            if lines:
                ci.annotation('error', 'Production emulator diagnostics', '\n'.join(lines)[-2400:])
        raise
    finally:
        stop_emulator(process, env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=SCENARIOS, required=True)
    parser.add_argument('--iterations', type=int, required=True)
    parser.add_argument('--smoke', choices=('true', 'false'), required=True)
    args = parser.parse_args()
    if not 1 <= args.iterations <= 50:
        parser.error('iterations must be in 1..50')
    run(args.scenario, args.iterations, args.smoke == 'true')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.SubprocessError):
        sys.exit(1)
