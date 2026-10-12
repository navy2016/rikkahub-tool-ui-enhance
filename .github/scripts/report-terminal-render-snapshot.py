"""Exact publication reuse counts with source/APK/JUnit identity, not a phone-speed claim."""
import base64
import hashlib
import importlib.util
import json
import os
import re
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    'snapshot_report_common', Path(__file__).with_name('report-terminal-panel-subscription.py'))
common = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(common)
CLASS = 'me.rerere.rikkahub.viewporttest.TerminalRenderSnapshotInstrumentedTest'
CASES = frozenset((
    'renderSnapshotRetainedChildRejectsLiveFrameButAcceptsItsCapturedPair',
    'renderSnapshotDefaultStreamingAndRemountPreserveCompletePublication',
    'renderSnapshotVirtualImeFallbackAndRemountPreserveCompletePublication',
    'renderSnapshotStableVirtualImeAndRemountPreserveCompletePublication',
    'renderSnapshotNoOutputRecompositionsReusePublicationAndMeasureRetainedChildWork',
    'renderSnapshotSameFrameAndSameRevisionPublicationsStillReachTheChild',
))
EXPECTED = dict(updates=32, legacyCaptures=32, publishedCaptures=0,
                legacyPanelCompositions=32, publishedPanelCompositions=32,
                sameSnapshotChecks=32, sameRowViewChecks=32)
CHILD_FIELDS = ('legacyChildCompositions', 'publishedChildCompositions')


def validate(records):
    if len(records) != 1 or not isinstance(records[0], dict):
        raise ValueError('Expected exactly one snapshot reuse record')
    row = records[0]
    if set(row) != {*EXPECTED, *CHILD_FIELDS}:
        raise ValueError('Incomplete or extra snapshot reuse fields')
    for name, value in EXPECTED.items():
        if type(row[name]) is not int or row[name] != value:
            raise ValueError('Incorrect snapshot reuse work: ' + name)
    for name in CHILD_FIELDS:
        if type(row[name]) is not int or row[name] < 0:
            raise ValueError('Invalid child composition count: ' + name)
    # Do not demand fewer child compositions: factory reuse does not prove subtree/FPS gains.
    return row


def main():
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    if not re.fullmatch('[a-f0-9]{40}', sha) or not re.fullmatch('[0-9]+', run):
        raise ValueError('Expected exact CI source/run identity')
    junit = common.require_junit(common.TEST_ROOT / 'build/outputs/androidTest-results', CLASS, CASES)
    log = common.ROOT / 'artifacts/terminal-validation/render-snapshot-logcat.txt'
    with log.open() as stream:
        records = [json.loads(line.split('SNAPSHOT_WORK ', 1)[1]) for line in stream if 'SNAPSHOT_WORK ' in line]
    sample = validate(records)
    copied = (
        'me/rerere/rikkahub/ui/pages/container/TerminalRenderSnapshot.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalRenderedRows.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalViewportBinding.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalTranscriptViewport.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalVirtualHistoryTranscript.kt',
        'me/rerere/rikkahub/utils/TerminalEmulator.kt',
        'me/rerere/rikkahub/utils/TerminalFrameSnapshot.kt',
    )
    for path in copied:
        if (common.digest(common.ROOT / 'app/src/main/java' / path) !=
                common.digest(common.TEST_ROOT / 'build/generated/viewportSources' / path)):
            raise ValueError('Fixture differs from production publication source: ' + path)
    files = ['app/src/main/java/' + path for path in copied] + [
        'app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt',
        'app/src/test/java/me/rerere/rikkahub/data/container/TerminalRenderSnapshotTest.kt',
        'app/src/test/java/me/rerere/rikkahub/data/container/TerminalUiSourceTest.kt',
        'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/'
        'TerminalRenderSnapshotInstrumentedTest.kt',
        'benchmarks/terminal-viewport-tests/build.gradle.kts',
        'benchmarks/terminal-viewport-tests/run-ci.sh',
        '.github/scripts/report-terminal-render-snapshot.py',
        '.github/scripts/report-terminal-panel-subscription.py',
        '.github/workflows/terminal-viewport-interactions.yml',
        '.github/workflows/terminal-emulator-tests.yml',
        'gradle/libs.versions.toml',
    ]
    apks = {}
    for role, directory in (('fixture', 'release'), ('tests', 'androidTest/release')):
        matches = list((common.TEST_ROOT / 'build/outputs/apk' / directory).glob('*.apk'))
        if len(matches) != 1:
            raise ValueError('Expected exactly one snapshot ' + role + ' APK')
        apks[role] = common.digest(matches[0])
    report = dict(suite='terminal-render-snapshot-publication-v1', sourceSha=sha, runId=run,
                  sourceHashes={path: common.digest(common.ROOT / path) for path in files},
                  apkHashes=apks, junit=junit, sample=sample,
                  note='One device, two mounted production renderer/binding fixtures, 32 separately committed '
                       'no-output panel updates. Control captures per composition; candidate reads one publication. '
                       'Counts exclude initial mount and include actual child commits. Not an allocation-byte, '
                       'full-page latency, GC or phone FPS benchmark. Real-page PTY verification is separate.')
    output = common.ROOT / 'artifacts/terminal-validation/render-snapshot.json'
    output.write_text(json.dumps(report, indent=2) + '\n')
    common.notice('Terminal render snapshot verified', json.dumps(sample))
    data = json.dumps(report, separators=(',', ':')).encode()
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    if len(parts) > 8:
        raise ValueError('Snapshot evidence exceeds annotation budget')
    for number, part in enumerate(parts, 1):
        common.notice(f'Terminal render snapshot evidence {number}/{len(parts)}',
                      'zlib-base64 sha256=' + hashlib.sha256(data).hexdigest() + '\n' + part)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        common.notice('Terminal render snapshot rejected', str(error)[:2800], level='error')
        raise SystemExit(1)
