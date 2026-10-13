"""Require exact chunk-view construction/entry evidence plus geometry, replacement and lifecycle tests."""
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
    'chunk_views_common', Path(__file__).with_name('report-terminal-panel-subscription.py'))
common = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(common)
CLASS = 'me.rerere.rikkahub.viewporttest.TerminalChunkViewsInstrumentedTest'
CASES = frozenset((
    'chunkViewsOneThousandRepeatedEntriesDoNotAllocateSlices',
    'chunkViewsFiveThousandRepeatedEntriesDoNotAllocateSlices',
    'chunkViewsTenThousandRepeatedEntriesDoNotAllocateSlices',
    'chunkViewsActiveTextAndFontChangesKeepViewsButUpdateRealRows',
    'chunkViewsSameIdReplacementFifoAndClearMatchUncachedGeometry',
    'chunkViewsFlatVirtualDetachAndRemountReleaseTheCurrentCacheEntry',
    'chunkViewsCachedSelectionCopiesTheSameWordThroughTheSystemMenu',
))
SIZES = (1000, 5000, 10000)
UPDATES = 32
FIRST_SEQUENCE = 125


def validate(records):
    seen = set()
    for row in records:
        if not isinstance(row, dict):
            raise ValueError('Invalid chunk-view work record')
        size, cached = row.get('size'), row.get('cached')
        key = size, cached
        if type(size) is not int or size not in SIZES or type(cached) is not bool or key in seen:
            raise ValueError('Invalid or duplicate chunk-view sample')
        chunks = (FIRST_SEQUENCE % 128 + size + 127) // 128
        expected = dict(chunks=chunks, updates=UPDATES, firstSequence=FIRST_SEQUENCE, frameRows=size + 6,
                        entries=UPDATES, cachedEntries=UPDATES if cached else 0,
                        wrappers=0 if cached else UPDATES * chunks,
                        slices=0 if cached else UPDATES * (chunks + 1), sourceIdentityChecks=UPDATES)
        counts = ('historyRowCompositions', 'screenRowCompositions')
        if set(row) != {'size', 'cached', *expected, *counts}:
            raise ValueError('Missing or extra chunk-view work fields')
        for name, value in expected.items():
            if type(row[name]) is not int or row[name] != value:
                raise ValueError('Incorrect chunk-view work: ' + name)
        for name in counts:
            if type(row[name]) is not int or row[name] < 0:
                raise ValueError('Invalid row composition count: ' + name)
        if cached and (size, False) not in seen:
            raise ValueError('Control must precede cached work for each size')
        seen.add(key)
    if seen != {(size, cached) for size in SIZES for cached in (False, True)}:
        raise ValueError('Incomplete chunk-view matrix: expected six samples')
    return records


def main():
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    if not re.fullmatch('[a-f0-9]{40}', sha) or not re.fullmatch('[0-9]+', run):
        raise ValueError('Expected exact CI source/run identity')
    junit = common.require_junit(common.TEST_ROOT / 'build/outputs/androidTest-results', CLASS, CASES)
    with (common.ROOT / 'artifacts/terminal-validation/chunk-views-logcat.txt').open() as stream:
        records = [json.loads(line.split('CHUNK_VIEWS_WORK ', 1)[1])
                   for line in stream if 'CHUNK_VIEWS_WORK ' in line]
    validate(records)
    copied = (
        'me/rerere/rikkahub/ui/pages/container/TerminalRenderedRows.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalRenderSnapshot.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalRenderControls.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalTranscriptViewport.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalViewportBinding.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalLazyItemMeasurements.kt',
        'me/rerere/rikkahub/utils/TerminalEmulator.kt',
        'me/rerere/rikkahub/utils/TerminalFrameSnapshot.kt',
    )
    for path in copied:
        if (common.digest(common.ROOT / 'app/src/main/java' / path) !=
                common.digest(common.TEST_ROOT / 'build/generated/viewportSources' / path)):
            raise ValueError('Fixture differs from production chunk-view source: ' + path)
    test_dir = 'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/'
    paths = ['app/src/main/java/' + path for path in copied] + [
        'app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt',
        'app/src/test/java/me/rerere/rikkahub/data/container/TerminalChunkViewsTest.kt',
        'app/src/test/java/me/rerere/rikkahub/data/container/TerminalUiSourceTest.kt',
        test_dir + 'TerminalChunkViewsInstrumentedTest.kt', test_dir + 'TerminalTranscriptGeometryInstrumentedTest.kt',
        test_dir + 'TerminalItemViewportInstrumentedTest.kt',
        'benchmarks/terminal-viewport-tests/build.gradle.kts', 'benchmarks/terminal-viewport-tests/run-ci.sh',
        '.github/scripts/report-terminal-chunk-views.py', '.github/scripts/report-terminal-panel-subscription.py',
        '.github/scripts/run-terminal-pipeline.py', '.github/workflows/terminal-viewport-interactions.yml',
        '.github/workflows/terminal-emulator-tests.yml', 'app/src/main/res/font/jetbrains_mono.ttf',
        'gradle/libs.versions.toml',
    ]
    apks = {}
    for role, directory in (('fixture', 'release'), ('tests', 'androidTest/release')):
        files = list((common.TEST_ROOT / 'build/outputs/apk' / directory).glob('*.apk'))
        if len(files) != 1:
            raise ValueError('Expected exactly one chunk-view ' + role + ' APK')
        apks[role] = common.digest(files[0])
    report = dict(suite='terminal-chunk-views-v1', sourceSha=sha, runId=run,
                  sourceHashes={path: common.digest(common.ROOT / path) for path in paths},
                  apkHashes=apks, junit=junit, samples=records,
                  note='One mounted production renderer per size; uncached then cached; mount excluded. '
                       'A new test observer identity forces 32 actual transcript entries per arm without changing '
                       'row structure/style. Counts track created chunk wrappers/slices and committed row compositions. '
                       'This is not elapsed-time, cold-start, GC, heap-retention or phone FPS evidence. '
                       'Cache-entry disposal checks composition lifetime, not garbage collection.')
    (common.ROOT / 'artifacts/terminal-validation/chunk-views.json').write_text(json.dumps(report, indent=2) + '\n')
    common.notice('Terminal chunk views verified', '\n'.join(
        f'{size} history: entries={UPDATES} each, wrappers=' +
        '/'.join(str(next(row['wrappers'] for row in records if (row['size'], row['cached']) == (size, cached)))
                 for cached in (False, True)) for size in SIZES))
    data = json.dumps(report, separators=(',', ':')).encode()
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    if len(parts) > 8:
        raise ValueError('Chunk-view evidence exceeds annotation budget')
    for index, part in enumerate(parts, 1):
        common.notice(f'Terminal chunk views evidence {index}/{len(parts)}',
                      'zlib-base64 sha256=' + hashlib.sha256(data).hexdigest() + '\n' + part)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        common.notice('Terminal chunk views rejected', str(error)[:2800], level='error')
        raise SystemExit(1)
