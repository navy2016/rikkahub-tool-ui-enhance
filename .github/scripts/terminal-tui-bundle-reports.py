"""Strict TUI-bundle source/JUnit/raw-sample evidence. Never builds, retries or filters slow samples."""
import argparse
import base64
import hashlib
import json
import os
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = 'app/src/main/java/me/rerere/rikkahub/'
TEST = 'app/src/test/java/me/rerere/rikkahub/'
PARSER = 'me.rerere.rikkahub.utils.TerminalParserBundleTest'
DISPLAY = 'me.rerere.rikkahub.viewporttest.TerminalTuiDisplayInstrumentedTest'
SCENARIOS = ('ascii', 'unicode', 'redraw')
REFERENCE_SHA = '917995852c9e5eb195cb44262f41a1238f422bca'
REFERENCE_HASHES = {
    'TerminalEmulator.kt': '8b51d91e7da600db4d313578d1c961b7b33986debdb39a65efda15eeb26f5127',
    'TerminalFrameSnapshot.kt': 'a585ea0e91d090b5e2204f88bca04d414dfb32d0deeb033b0cfdc151e736b44b',
    'TerminalScreenSnapshot.kt': '0604335e67688f8b2ec0e40f93f1c9dff937ad9f4767b5edbfa280a6501b9614',
}
UNIT_FILES = {
    'utils/TerminalProtocolBundleTest.kt': 14,
    'utils/TerminalParserBundleTest.kt': 5,
    'data/container/TerminalConsistencyBundleTest.kt': 9,
    'data/container/TerminalDisplayPolicyTest.kt': 2,
}
DISPLAY_CASES = {
    'tuiDisplayCursorOverlayPreservesNaturalTextAndChangesOnlyCursorPixels',
    'tuiDisplayCursorMovesInvalidateDrawWithoutRecomposingTextOwner',
    'tuiDisplayMouseHitsMeasuredScreenRowsAndRejectsHistoryStatusAndNegativePixels',
    'tuiDisplayRegularHistoryUsesNaturalChunkGeometryAndKeepsEveryRow',
}
PTY_METHODS = (
    'tuiPtyProtocolHandshakeReturnsExactBytesAndKeepsCursor',
    'tuiPageImeCommitsAndDeletesWholeGraphemesInExactOrder',
    'tuiPageMouseClickUsesActualScreenRowBelowStatusStrip',
    'tuiPtyResizeReceiptMatchesKernelAndAtomicSnapshot',
    'tuiPageOverlayIsExplicitAndKeepsUnderlyingTextVisible',
    'tuiPageRegularHistoryKeepsRawInputAndRestoresAlternateFallback',
)
CHANGED_PRODUCTION = (
    'data/container/BackgroundProcessManager.kt', 'data/container/TerminalOutputNotifications.kt',
    'data/container/TerminalPipelineTrace.kt', 'data/container/TerminalRenderMode.kt',
    'data/container/TerminalResizeCoordinator.kt', 'data/container/TerminalUiInput.kt',
    'data/container/TerminalDisplayPolicy.kt', 'utils/TerminalEmulator.kt', 'utils/TerminalScreenSnapshot.kt',
    'ui/pages/container/ProcessSessionPage.kt', 'ui/pages/container/TerminalRenderedRows.kt',
    'ui/pages/container/TerminalRowStore.kt', 'ui/pages/container/TerminalRenderSnapshot.kt',
    'ui/pages/container/TerminalTranscriptViewport.kt', 'ui/pages/container/TerminalRenderControls.kt',
    'ui/pages/container/TerminalPointerMap.kt', 'ui/pages/container/TerminalCursorOverlayText.kt',
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def records(text, marker):
    result = [json.loads(line.split(marker, 1)[1]) for line in text.splitlines() if marker in line]
    require(all(type(item) is dict for item in result), 'Non-object evidence')
    return result


def identity():
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[0-9]+', run), 'Missing exact SHA/run')
    return sha, run


def notice(title, text):
    require(len(text) <= 2900, 'Evidence annotation exceeds budget')
    print('::notice title=' + title + '::' + text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))


def publish(title, report, path):
    raw = json.dumps(report, ensure_ascii=False, separators=(',', ':')).encode()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(raw)
    sha = hashlib.sha256(raw).hexdigest()
    notice(title + ' verified', json.dumps(dict(sourceSha=report['sourceSha'], runId=report['runId'],
        sha256=sha, summary=report.get('summary', report.get('cases'))), ensure_ascii=False))
    encoded = base64.b64encode(zlib.compress(raw, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    require(len(parts) <= 8, 'Too many evidence parts')
    for index, part in enumerate(parts, 1):
        notice(f'{title} evidence {index}/{len(parts)}', f'zlib-base64 sha256={sha}\n{part}')


def validate_pty(text):
    rows = records(text, 'TUI_PTY ')
    expected = {
        'protocol': dict(responseHex=b'\x1b[?1;2c\x1b[>4;2m\x1b[5;11R'.hex(), responseBytes=21),
        'ime': dict(inputHex='e\u0301👍🏽\x7f\x7f'.encode().hex(), inputBytes=13),
        'mouse': dict(inputHex=b'\x1b[<0;3;1M\x1b[<0;3;1m'.hex(), inputBytes=18),
        'resize': dict(kernelChecks=3),
        'overlay': dict(textIntact=True),
        'regular': dict(historyRows=1000, rawInput=True, alternateRoundTrip=True, peer='synthetic-pi-regular'),
    }
    require(len(rows) == len(expected), 'Missing/duplicate TUI PTY receipts')
    seen = set()
    for row in rows:
        case = row.get('case')
        require(case in expected and case not in seen, 'Unknown/duplicate TUI PTY case')
        seen.add(case)
        target = dict(expected[case], case=case, suite='terminal-tui-bundle-pty-v1', passed=True)
        require(set(row) == set(target), 'Wrong PTY receipt fields')
        require(all(type(row[k]) is type(v) and row[k] == v for k, v in target.items()), 'Incorrect PTY receipt: ' + case)
    return rows


def validate_display(text):
    rows = records(text, 'TUI_DISPLAY ')
    targets = {
        'cursorPixels': dict(variants=8), 'cursorMoves': dict(moves=24, parentExtra=0),
        'pointerLayout': dict(screenRows=6, disposedRows=0), 'regularHistory': dict(rows=24),
    }
    require(len(rows) == 4, 'Missing/duplicate display receipts')
    seen = set()
    for row in rows:
        case = row.get('case')
        require(case in targets and case not in seen, 'Wrong display case')
        seen.add(case)
        target = dict(targets[case], case=case, passed=True)
        require(set(row) == set(target) and all(type(row[k]) is type(v) and row[k] == v for k, v in target.items()),
                'Invalid display receipt')
    return rows


def validate_parser(text, sha, run):
    environments = records(text, 'PARSER_ENV ')
    require(len(environments) == 1, 'Expected one parser environment')
    env = environments[0]
    require(env.get('sourceSha') == sha and env.get('runId') == str(run), 'Parser SHA/run mismatch')
    require(set(env) == {'sourceSha', 'runId', 'environment'} and type(env['environment']) is dict,
            'Wrong environment fields')
    require(set(env['environment']) == {'java.version', 'java.vm.name', 'os.name', 'os.arch'} and
            all(type(v) is str and v for v in env['environment'].values()), 'Incomplete parser environment')
    samples = records(text, 'PARSER_SAMPLE ')
    keys = [(scenario, phase, pair, arm, position) for scenario in SCENARIOS
            for phase, pairs in (('warmup', 2), ('measurement', 4)) for pair in range(pairs)
            for position, arm in enumerate(('reference', 'candidate') if pair % 2 == 0 else ('candidate', 'reference'))]
    require(len(samples) == len(keys), 'Expected 12 warmup + 24 formal parser samples')
    chunks = dict(ascii=(('row:' + 'abc123 ' * 9 + '\r\n') * 48).encode(),
                  unicode=('中文 e\u0301 👩‍💻 🇨🇳\r\n' * 100).encode(),
                  redraw=(('\x1b[H\x1b[2J\x1b[31;1mstatus\x1b[0m\r\n' + 'x' * 64) * 32).encode())
    for row, (scenario, phase, pair, arm, position) in zip(samples, keys):
        expected = dict(scenario=scenario, phase=phase, pair=pair, arm=arm, position=position,
                        iterations=24 if phase == 'warmup' else 96, chunkBytes=len(chunks[scenario]))
        require(set(row) == {*expected, 'elapsedNanos', 'allocatedBytes', 'revision', 'historyRows', 'sha256'},
                'Unexpected parser fields')
        for name, value in expected.items():
            require(type(row[name]) is type(value) and row[name] == value, 'Wrong parser ' + name)
        for name in ('elapsedNanos', 'allocatedBytes', 'revision', 'historyRows'):
            require(type(row[name]) is int and row[name] >= (1 if name == 'elapsedNanos' else 0), 'Invalid parser scalar')
        require(row['revision'] == row['iterations'] + 1 and row['historyRows'] <= 128, 'Invalid parser final state')
        require(type(row['sha256']) is str and re.fullmatch('[a-f0-9]{64}', row['sha256']), 'Invalid parser hash')
    for a, b in zip(samples[::2], samples[1::2]):
        for field in ('sha256', 'revision', 'historyRows', 'chunkBytes', 'iterations'):
            require(a[field] == b[field], 'Paired parser final state mismatch')
        pair = {a['arm']: a, b['arm']: b}
        if a['phase'] == 'measurement':
            require(pair['candidate']['allocatedBytes'] < pair['reference']['allocatedBytes'], 'No allocation reduction')
    summary = {}
    for scenario in SCENARIOS:
        group = [s for s in samples if s['scenario'] == scenario and s['phase'] == 'measurement']
        summary[scenario] = {arm: dict(nsPerChunk=statistics.median(r['elapsedNanos'] / r['iterations'] for r in group if r['arm'] == arm),
            bytesPerChunk=statistics.median(r['allocatedBytes'] / r['iterations'] for r in group if r['arm'] == arm))
            for arm in ('reference', 'candidate')}
    return dict(environment=env, samples=samples, summary=summary)


def require_junit(root, klass, expected):
    cases, logs = {}, []
    for path in root.rglob('TEST-*.xml'):
        tree = ET.parse(path).getroot()
        selected = [case for case in tree.iter('testcase') if case.get('classname') == klass]
        for case in selected:
            name = case.get('name')
            require(name not in cases and not any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')),
                    'Failed/duplicate case: ' + str(name))
            cases[name] = True
        if selected:
            logs.extend(''.join(node.itertext()) for node in tree.iter('system-out'))
    require(set(cases) == set(expected), 'Missing/extra JUnit: ' + klass)
    return '\n'.join(logs)


def source_hashes():
    return {MAIN + name: digest(ROOT / MAIN / name) for name in CHANGED_PRODUCTION}


def test_methods(text):
    return set(re.findall(r'@Test(?:\([^)]*\))?\s+fun\s+(\w+)\(', text))


def reference_proof():
    reference = ROOT / TEST / 'utils/parserreference'
    actual = {p.name: digest(p) for p in reference.glob('*.kt')}
    require(actual == REFERENCE_HASHES, 'Frozen parser reference changed')
    for name in REFERENCE_HASHES:
        original = subprocess.check_output(['git', 'show', REFERENCE_SHA + ':' + MAIN + 'utils/' + name],
                                           cwd=ROOT, timeout=20).decode()
        frozen = (reference / name).read_text().replace('package me.rerere.rikkahub.utils.parserreference',
                                                       'package me.rerere.rikkahub.utils', 1)
        require(original == frozen, 'Reference is not the exact semantic-correct baseline: ' + name)
    old = (reference / 'TerminalEmulator.kt').read_text().replace('package me.rerere.rikkahub.utils.parserreference',
                                                               'package me.rerere.rikkahub.utils', 1)
    new = (ROOT / MAIN / 'utils/TerminalEmulator.kt').read_text()
    for line in ('        private val ASCII_TEXT = Array(128) { it.toChar().toString() }\n',
                 '    private var writeStyleBase: Style? = null\n', '    private var writeStyleLink: Hyperlink? = null\n',
                 '    private var cachedWriteStyle: Style? = null\n', '    private var cachedBlank: Cell? = null\n',
                 '    // A session-local bounded scratch, never retained with history or exposed through snapshots.\n',
                 '    private val decodedCharacters = CharBuffer.allocate(4096)\n'):
        require(new.count(line) == 1, 'Missing allocation-only field')
        new = new.replace(line, '', 1)
    regions = (
        ('    fun feed(bytes: ByteArray)', '    fun drainResponses()'),
        ('    private fun resizedLine(', '    private fun MutableList<CellLine>.resizeScreen('),
        ('    private fun putChar(', '    private fun repeatLastGraphic('),
        ('    private fun clearCellForWrite(', '    private fun shouldAppendToPreviousCell('),
        ('    private fun scrollUp()', '    private fun scrollDown()'),
        ('    private fun eraseLineRange(', '    private fun eraseRectangle('),
        ('    private fun fillRectangle(', '    private fun codePointToString('),
        ('    private fun scrollLeft(', '    private fun setScrollRegion('),
    )
    def mask(text, start, end):
        require(text.count(start) == 1 and text.count(end) == 1, 'Ambiguous allocation region')
        first, last = text.index(start), text.index(end)
        require(last > first, 'Reordered allocation regions')
        return text[:first] + '    ALLOCATION_REGION\n' + text[last:]
    for start, end in regions:
        old, new = mask(old, start, end), mask(new, start, end)
    old = mask(old, '    private fun blankLine()', '    private fun cursorGlyph()')
    new = mask(new, '    private fun styleForWrite()', '    private fun cursorGlyph()')
    require(old == new, 'Changes outside approved parser allocation regions')
    for name in ('TerminalFrameSnapshot.kt', 'TerminalScreenSnapshot.kt'):
        require((reference / name).read_text().replace('package me.rerere.rikkahub.utils.parserreference',
                'package me.rerere.rikkahub.utils', 1) == (ROOT / MAIN / 'utils' / name).read_text(),
                'Reference support types changed: ' + name)
    return actual


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('kind', choices=('parser', 'display'))
    kind = args.parse_args().kind
    sha, run = identity()
    report = dict(suite='terminal-tui-' + kind + '-v1', sourceSha=sha, runId=run, sourceHashes=source_hashes())
    if kind == 'parser':
        unit_inventory = {}
        text = ''
        for name, count in UNIT_FILES.items():
            test = ROOT / TEST / name
            expected = test_methods(test.read_text())
            require(len(expected) == count, 'Source method inventory mismatch: ' + name)
            klass = 'me.rerere.rikkahub.' + name.removesuffix('.kt').replace('/', '.')
            output = require_junit(ROOT / 'app/build/test-results/testReleaseUnitTest', klass, expected)
            unit_inventory[klass] = sorted(expected)
            if klass == PARSER:
                text = output
        report['junit'] = unit_inventory
        report.update(validate_parser(text, sha, run))
        report['referenceHashes'] = reference_proof()
        report['referenceSha'] = REFERENCE_SHA
        report['note'] = 'Same JVM, post-protocol reference vs allocation candidate; 12 warmup and 24 formal samples; no timing filter. Not phone FPS.'
    else:
        base = ROOT / 'benchmarks/terminal-viewport-tests'
        require_junit(base / 'build/outputs/androidTest-results', DISPLAY, DISPLAY_CASES)
        report['cases'] = validate_display((ROOT / 'artifacts/terminal-validation/tui-display-logcat.txt').read_text())
        copied = ('utils/TerminalEmulator.kt', 'utils/TerminalScreenSnapshot.kt',
            'data/container/TerminalDisplayPolicy.kt', 'ui/pages/container/TerminalCursorOverlayText.kt',
            'ui/pages/container/TerminalPointerMap.kt', 'ui/pages/container/TerminalRowStore.kt',
            'ui/pages/container/TerminalRenderSnapshot.kt', 'ui/pages/container/TerminalRenderedRows.kt',
            'ui/pages/container/TerminalTranscriptViewport.kt')
        for name in copied:
            require(digest(ROOT / MAIN / name) == digest(base / 'build/generated/viewportSources/me/rerere/rikkahub' / name),
                    'Copied source mismatch: ' + name)
        report['apkHashes'] = {}
        for role, folder in (('fixture', 'release'), ('tests', 'androidTest/release')):
            files = list((base / 'build/outputs/apk' / folder).glob('*.apk'))
            require(len(files) == 1, 'Missing ' + role + ' APK')
            report['apkHashes'][role] = digest(files[0])
        report['note'] = 'Real Text geometry/pixels and pointer map; synthetic regular policy fixture, not real Pi conversation or latency.'
    publish('Terminal TUI ' + kind, report, ROOT / ('artifacts/terminal-tui/' + kind + '.json'))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError, subprocess.SubprocessError) as error:
        message = str(error)[:2600].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::error title=Terminal TUI evidence rejected::' + message)
        raise SystemExit(1)
