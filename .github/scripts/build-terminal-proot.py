"""GitHub-only optimized x86_64 test asset; never edits production runtime binaries."""
import hashlib
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_SHA = '58aad2cb1c36ea6af7b32d76ccd5bf8d0a967939'
HEADER_SHA = 'e01fb092aaed2b431be26674e2b791c77fb5984537c29b514e957582c6b31465'
LIB_SHA = '77be445f4ec245fff9c19e9874ebcf99618244cf48737f5fca938316daaa70da'
NDK = '27.2.12479018'
WORK = ROOT / 'artifacts/pipeline-proot-build'
OUT = ROOT / 'artifacts/terminal-pipeline'
PATCH = ROOT / 'benchmarks/proot-x86_64/fork-to-clone.patch'
HEADER_PATCH = ROOT / 'benchmarks/proot-x86_64/ndk-string-header.patch'
OVERLAY = ROOT / 'app/build/generated/pipelineAssets/proot/proot-x86_64'


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def command(args, cwd=ROOT, env=None, timeout=180):
    result = subprocess.run(args, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=timeout)
    with (WORK / 'build.log').open('a') as log:
        log.write(result.stdout)
    if result.returncode:
        errors = [line for line in result.stdout.splitlines() if any(key in line.lower() for key in ('error:', 'fatal:', 'undefined', 'no rule', 'not found'))]
        raise RuntimeError('\n'.join(errors)[-2300:] or result.stdout[-2300:])
    return result.stdout


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_OS') != 'Linux':
        raise RuntimeError('Android builds run only in GitHub Actions')
    WORK.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ['ANDROID_HOME'])
    command([str(sdk / 'cmdline-tools/latest/bin/sdkmanager'), '--install', 'ndk;' + NDK], timeout=300)
    tool = sdk / 'ndk' / NDK / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    compiler = tool / 'x86_64-linux-android26-clang'
    source = WORK / 'source'
    command(['git', 'clone', '--quiet', '--depth=1', '--branch', 'v5.1.107.72', 'https://github.com/termux/proot.git', str(source)])
    if command(['git', 'rev-parse', 'HEAD'], cwd=source).strip() != SOURCE_SHA:
        raise RuntimeError('Unexpected PRoot source SHA')
    for patch in (PATCH, HEADER_PATCH):
        command(['git', 'apply', '--check', str(patch)], cwd=source)
        command(['git', 'apply', str(patch)], cwd=source)
    include = WORK / 'include'
    include.mkdir(exist_ok=True)
    header = include / 'talloc.h'
    command(['curl', '-fsSL', '--retry', '3', '--max-time', '45',
             'https://raw.githubusercontent.com/samba-team/samba/77229f73c20af69ab0f3c96efbb229ff64a9dfe4/lib/talloc/talloc.h',
             '-o', str(header)])
    library = ROOT / 'app/src/main/assets/proot/libtalloc-x86_64.so.2'
    if digest(header) != HEADER_SHA or digest(library) != LIB_SHA:
        raise RuntimeError('Pinned talloc header/library hash mismatch')
    env = dict(os.environ, PATH=str(tool) + os.pathsep + os.environ['PATH'])
    # Existing loader/loader32 are supplied by the app. The make -o option marks the loader
    # up-to-date; source edits are the reviewed syscall mapping and missing string.h include.
    args = ['make', '-C', str(source / 'src'), '-j2', 'V=1', 'proot',
            f'CC={compiler}', f'STRIP={tool / "llvm-strip"}', f'OBJCOPY={tool / "llvm-objcopy"}',
            f'OBJDUMP={tool / "llvm-objdump"}', 'HAS_LOADER_32BIT=',
            'PROOT_UNBUNDLE_LOADER=/unused-pipeline-loader',
            f'CPPFLAGS=-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -I. -I{include}',
            "CFLAGS=-O2 -fPIE -Wall -Wextra '-DPROOT_UNBUNDLE_LOADER=\"/unused-pipeline-loader\"'",
            f'LDFLAGS={library} -fPIE -pie -Wl,-z,noexecstack -Wl,-rpath,\'$$ORIGIN\'',
            '-o', 'loader/loader']
    command(args, env=env, timeout=240)
    binary = source / 'src/proot'
    command([str(tool / 'llvm-strip'), '--strip-unneeded', str(binary)])
    dynamic = command([str(tool / 'llvm-readelf'), '-d', str(binary)])
    headers = command([str(tool / 'llvm-readelf'), '-h', '-l', str(binary)])
    if '$ORIGIN' not in dynamic or 'libtalloc.so.2' not in dynamic or '/system/bin/linker64' not in headers:
        raise RuntimeError('Not the expected Android PIE/runpath')
    OVERLAY.parent.mkdir(parents=True, exist_ok=True)
    command(['cp', str(binary), str(OVERLAY)])
    provenance = dict(proot_commit=SOURCE_SHA, patch_sha256=digest(PATCH), ndk=NDK,
                      header_patch_sha256=digest(HEADER_PATCH),
                      talloc_header_sha256=HEADER_SHA, talloc_library_sha256=LIB_SHA,
                      overlay_sha256=digest(OVERLAY), source_sha=os.environ['GITHUB_SHA'],
                      build_run=os.environ['GITHUB_RUN_ID'], overlay_only=True,
                      compile_command=args, compiler=command([str(compiler), '--version']).splitlines()[0])
    (OUT / 'proot-overlay.json').write_text(json.dumps(provenance, indent=2) + '\n')
    message = json.dumps({k: v for k, v in provenance.items() if k != 'compile_command'})
    print('::notice title=Test-only PRoot fork overlay::' + message)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        message = str(error)[-2800:].replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print('::error title=Test-only PRoot build failed::' + message)
        raise SystemExit(1)
