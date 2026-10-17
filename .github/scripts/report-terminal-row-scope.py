"""Same-device eager-row mount evidence and compiler scope proof; not a full-page/FPS benchmark."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('row_scope_common',
    Path(__file__).with_name('report-terminal-panel-subscription.py'))
common = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(common)
ROOT = common.ROOT
BASELINE = 'b7a93c9b5734ce1386d919d3be6085ef725124bb'
CLASS = 'me.rerere.rikkahub.viewporttest.TerminalRowScopeInstrumentedTest'
CASES = frozenset((
    'rowScopeColdOneThousandPairedMounts', 'rowScopeColdFiveThousandPairedMounts',
    'rowScopeColdTenThousandPairedMounts', 'rowScopeActiveTextAndAbortedSnapshotKeepRowLocalSubscription',
    'rowScopeBothPathsMatchStyledGeometryAtFontScaleAndRtl',
    'rowScopeUnmeasuredTuiRowDoesNotInvalidateTranscriptParent',
))
SIZES = (1000, 5000, 10000)
MAIN = 'app/src/main/java/me/rerere/rikkahub/ui/pages/container/'
TESTS = 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/'


def require(value, message):
    if not value:
        raise ValueError(message)


def function_body(text, signature):
    start = text.index('{', text.index(signature))
    depth = 0
    for end in range(start, len(text)):
        depth += (text[end] == '{') - (text[end] == '}')
        if depth == 0:
            return text[start:end + 1]
    raise ValueError('Unterminated frozen function')


def normalized(text):
    text = re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)
    return re.sub(r'\s+', '', text)


def source_proof():
    files = ('TerminalRenderedRows.kt', 'TerminalLazyItemMeasurements.kt')
    old = {name: subprocess.check_output(['git', 'show', BASELINE + ':' + MAIN + name], cwd=ROOT,
                                        timeout=20).decode() for name in files}
    frozen = (ROOT / TESTS / 'LegacyMeasuredTerminalRow.kt').read_text()
    require(normalized(function_body(old[files[0]], 'fun TerminalRenderedRow(')) ==
            normalized(function_body(frozen, 'fun LegacyTerminalRenderedRow(')), 'Frozen text renderer changed')
    previous_row = function_body(old[files[1]], 'fun Row(')
    previous_row = previous_row.replace('TerminalRenderedRow(', 'LegacyTerminalRenderedRow(')
    previous_row = previous_row.replace('onRowComposed', 'measurements.onRowComposed')
    previous_row = previous_row.replace('RowMeasurementElement(this, metricKey, row.lineId, text)',
                                        'measurements.rowModifier(metricKey, row.lineId, text)')
    require(normalized(previous_row) == normalized(function_body(frozen, 'fun LegacyMeasuredTerminalRow(')),
            'Frozen measured row is not the original dual-scope path')
    current = (ROOT / MAIN / files[1]).read_text()
    require(old[files[1]].split('private fun record(', 1)[1] == current.split('private fun record(', 1)[1],
            'Measurement registration/node/geometry changed beyond this experiment')
    new_text = (ROOT / MAIN / files[0]).read_text()
    require(normalized(function_body(new_text, 'fun TerminalRowText(')) == normalized(
        function_body(old[files[0]], 'fun TerminalRenderedRow(').replace('val text = state.text', '')),
        'The new forwarding function changed Text parameters or dispatch')
    return dict(baselineSha=BASELINE, frozenBodiesVerified=True, measurementNodeUnchanged=True,
                baselineSources={MAIN + name: hashlib.sha256(value.encode()).hexdigest() for name, value in old.items()})


SCOPE_NAMES = {
    'TerminalRowText': ('me.rerere.rikkahub.ui.pages.container.TerminalRowText', False, 'compileReleaseKotlin'),
    'TerminalRenderedRow': ('me.rerere.rikkahub.ui.pages.container.TerminalRenderedRow', True, 'compileReleaseKotlin'),
    'Row': ('me.rerere.rikkahub.ui.pages.container.TerminalLazyItemMeasurements.Row', True, 'compileReleaseKotlin'),
    'LegacyMeasuredTerminalRow': ('me.rerere.rikkahub.viewporttest.LegacyMeasuredTerminalRow',
                                  True, 'compileReleaseAndroidTestKotlin'),
    'LegacyTerminalRenderedRow': ('me.rerere.rikkahub.viewporttest.LegacyTerminalRenderedRow',
                                  True, 'compileReleaseAndroidTestKotlin'),
}


def compiler_scopes(folder):
    by_qualified = {value[0]: key for key, value in SCOPE_NAMES.items()}
    found = {key: [] for key in SCOPE_NAMES}
    for path in folder.rglob('*composables.txt'):
        for line in path.read_text().splitlines():
            match = re.search(r'^(.*?)\bfun ([\w.]+)\(', line)
            if match and match[2] in by_qualified:
                name = by_qualified[match[2]]
                relative = path.relative_to(folder)
                require(len(relative.parts) == 2 and relative.parts[0] == SCOPE_NAMES[name][2],
                        'Report came from the wrong compilation: ' + name)
                found[name].append(dict(report=str(relative), header=line,
                                        restartable='restartable' in match[1].split(),
                                        reportSha256=common.digest(path)))
    for name, (_, restartable, _) in SCOPE_NAMES.items():
        require(len(found[name]) == 1 and found[name][0]['restartable'] is restartable,
                'Missing or incorrect compiled restart scope: ' + name)
    return {name: rows[0] for name, rows in found.items()}


def validate(samples):
    require(type(samples) is list and len(samples) == 30, 'Expected six warmup and 24 measured cold mounts')
    grouped = {size: [] for size in SIZES}
    for row in samples:
        require(type(row) is dict and type(row.get('size')) is int and row['size'] in SIZES, 'Unknown size')
        grouped[row['size']].append(row)
    for size, group in grouped.items():
        keys = [(phase, pair, position, arm) for phase, pairs in (('warmup', 1), ('measurement', 4))
                for pair in range(pairs)
                for position, arm in enumerate(('legacy', 'single') if pair % 2 == 0 else ('single', 'legacy'))]
        require(len(group) == len(keys), 'Incomplete size matrix')
        for sequence, (row, (phase, pair, position, arm)) in enumerate(zip(group, keys)):
            exact = dict(size=size, phase=phase, pair=pair, position=position, arm=arm, sequence=sequence,
                         nodes=size + 24, retainedRows=size + 24, disposedRows=0, historyChunks=(size + 127) // 128)
            require(set(row) == {*exact, 'elapsedNanos', 'rowCommits', 'heightSha256', 'heightPx', 'draws'},
                    'Incomplete or extra cold-mount fields')
            for name, value in exact.items():
                require(type(row[name]) is type(value) and row[name] == value, 'Wrong mount field: ' + name)
            for name, minimum in (('elapsedNanos', 1), ('rowCommits', size + 24), ('heightPx', size + 24), ('draws', 3)):
                require(type(row[name]) is int and row[name] >= minimum, 'Invalid mount work: ' + name)
            require(type(row['heightSha256']) is str and re.fullmatch('[a-f0-9]{64}', row['heightSha256']),
                    'Invalid geometry hash')
        for before, after in zip(group[::2], group[1::2]):
            require(before['heightSha256'] == after['heightSha256'] and before['heightPx'] == after['heightPx'],
                    'Paired natural geometry or scroll ranges differ')
    return samples


def summary(samples):
    result = {}
    for size in SIZES:
        group = [r for r in samples if r['size'] == size and r['phase'] == 'measurement']
        result[size] = {arm: dict(medianMs=statistics.median(r['elapsedNanos'] / 1e6 for r in group if r['arm'] == arm),
                                 samplesMs=[r['elapsedNanos'] / 1e6 for r in group if r['arm'] == arm])
                        for arm in ('legacy', 'single')}
        result[size]['pairedDifferenceMs'] = [
            (next(r['elapsedNanos'] for r in group if r['pair'] == i and r['arm'] == 'single') -
             next(r['elapsedNanos'] for r in group if r['pair'] == i and r['arm'] == 'legacy')) / 1e6 for i in range(4)]
    return result


def validate_devices(devices):
    require(type(devices) is list and len(devices) == 3 and all(type(d) is dict for d in devices),
            'Missing device receipts')
    require(all(type(d.get('size')) is int for d in devices) and
            {d['size'] for d in devices} == set(SIZES), 'Missing device sizes')
    for device in devices:
        require(set(device) == {'size', 'pid', 'fingerprint', 'sdk', 'density', 'fontScale',
                               'targetPackage', 'testPackage', 'targetApkSha256', 'testApkSha256'}, 'Invalid device fields')
        for key, minimum in (('size', 1), ('pid', 1), ('sdk', 29)):
            require(type(device[key]) is int and device[key] >= minimum, 'Invalid device scalar: ' + key)
        require(type(device['fingerprint']) is str and device['fingerprint'], 'Missing fingerprint')
        require(type(device['density']) is int and device['density'] == 1 and
                type(device['fontScale']) is int and device['fontScale'] == 1, 'Different fixture text density')
        require(device['targetPackage'] == 'me.rerere.rikkahub.terminalviewporttest' and
                device['testPackage'] == 'me.rerere.rikkahub.terminalviewporttest.test', 'Wrong measured packages')
        for key in ('targetApkSha256', 'testApkSha256'):
            require(type(device[key]) is str and re.fullmatch('[a-f0-9]{64}', device[key]), 'Missing installed APK hash')
    require(len({(d['pid'], d['fingerprint'], d['sdk']) for d in devices}) == 1, 'Mixed cold-mount devices/processes')
    require(len({(d['targetApkSha256'], d['testApkSha256']) for d in devices}) == 1, 'Mixed measured APKs')
    return devices


def built_apks():
    apks = {}
    for role, directory in (('fixture', 'release'), ('tests', 'androidTest/release')):
        files = list((common.TEST_ROOT / 'build/outputs/apk' / directory).glob('*.apk'))
        require(len(files) == 1, 'Expected one ' + role + ' APK')
        apks[role] = common.digest(files[0])
    return apks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler-only', action='store_true')
    args = parser.parse_args()
    proof = source_proof()
    compiled = compiler_scopes(common.TEST_ROOT / 'build/compose-reports')
    if args.compiler_only:
        common.notice('Terminal row scopes compiled', json.dumps(compiled))
        return
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[0-9]+', run), 'Missing SHA/run identity')
    junit = common.require_junit(common.TEST_ROOT / 'build/outputs/androidTest-results', CLASS, CASES)
    log = ROOT / 'artifacts/terminal-validation/row-scope-logcat.txt'
    lines = log.read_text().splitlines()
    samples = [json.loads(line.split('ROW_SCOPE_COLD ', 1)[1]) for line in lines
               if 'ROW_SCOPE_COLD ' in line]
    validate(samples)
    devices = validate_devices([json.loads(line.split('ROW_SCOPE_DEVICE ', 1)[1]) for line in lines
                                if 'ROW_SCOPE_DEVICE ' in line])
    copied = ('TerminalRenderedRows.kt', 'TerminalLazyItemMeasurements.kt', 'TerminalRenderControls.kt',
              'TerminalTranscriptViewport.kt', 'TerminalViewportBinding.kt', 'TerminalTranscriptWidth.kt', 'TerminalRenderSnapshot.kt')
    for name in copied:
        require(common.digest(ROOT / MAIN / name) == common.digest(common.TEST_ROOT /
                'build/generated/viewportSources/me/rerere/rikkahub/ui/pages/container' / name), 'Source copy mismatch: ' + name)
    paths = [MAIN + name for name in copied] + [TESTS + 'TerminalRowScopeInstrumentedTest.kt',
        TESTS + 'LegacyMeasuredTerminalRow.kt', 'app/src/main/res/font/jetbrains_mono.ttf',
        'benchmarks/terminal-viewport-tests/build.gradle.kts', 'benchmarks/terminal-viewport-tests/run-ci.sh',
        '.github/scripts/report-terminal-row-scope.py', '.github/scripts/test_terminal_row_scope.py',
        '.github/workflows/terminal-viewport-interactions.yml',
        'gradle/libs.versions.toml']
    apks = built_apks()
    installed = dict(fixture=devices[0]['targetApkSha256'], tests=devices[0]['testApkSha256'])
    require(installed == apks, 'Missing or mismatched installed APK identity')
    report = dict(suite='terminal-row-scope-v1', sourceSha=sha, runId=run, provenance=proof,
                  sourceHashes={path: common.digest(ROOT / path) for path in paths},
                  apkHashes=apks, installedApkHashes=installed, devices=devices,
                  junit=junit, compilerScopes=compiled, samples=samples, summary=summary(samples),
                  note='Same-device eager row-tree mount AB/BA; seeded immutable frame and row-state allocation excluded. '
                       'Timers include test idle/draw confirmation and geometry scan, not production follow or PTY. '
                       'Six warmup samples excluded; 24 measurements retained, no timing pass filter. Not phone FPS.')
    raw = json.dumps(report, separators=(',', ':')).encode()
    (ROOT / 'artifacts/terminal-validation/row-scope.json').write_bytes(raw)
    common.notice('Terminal row scopes verified', json.dumps(dict(summary=report['summary'],
                  warmupSamples=6, measuredSamples=24, baselineSha=BASELINE, sha256=hashlib.sha256(raw).hexdigest())))
    encoded = base64.b64encode(zlib.compress(raw, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    require(len(parts) <= 8, 'Evidence annotation budget exceeded')
    for number, part in enumerate(parts, 1):
        common.notice(f'Terminal row scope evidence {number}/{len(parts)}',
                      f'zlib-base64 sha256={hashlib.sha256(raw).hexdigest()}\n{part}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.SubprocessError, ET.ParseError) as error:
        common.notice('Terminal row scope rejected', str(error)[:2400], level='error')
        raise SystemExit(1)
