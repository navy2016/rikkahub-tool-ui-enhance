"""Exact-row-width correctness and alternating uncached-pass evidence, not phone FPS."""
import base64
import hashlib
import importlib.util
import json
import os
import re
import statistics
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('width_common', Path(__file__).with_name('report-terminal-panel-subscription.py'))
common = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(common)
ROOT, TEST_ROOT = common.ROOT, common.TEST_ROOT
CLASS = 'me.rerere.rikkahub.viewporttest.TerminalIntrinsicWidthInstrumentedTest'
CASES = frozenset((
    'intrinsicWidthAnsiCjkCombiningAndEmojiMatchFullLayout',
    'intrinsicWidthMixedFontsSpansAndGeometricTransformsMatchFullLayout',
    'intrinsicWidthParagraphDirectionsAndIndentsMatchFullLayout',
    'intrinsicWidthEmptyWhitespaceAndBoundaryLengthsMatchFullLayout',
    'intrinsicWidthPaletteResizeCursorAndGenerationFramesMatchFullLayout',
    'intrinsicWidthScreenCacheMatchesUncachedFontsAnsiCursorAndResize',
    'intrinsicWidthFifoIndexMatchesFullMaximumAfterTrimAndMetricChange',
    'intrinsicWidthOwnedSingleParagraphMatchesFullAndMultiAtAllMetrics',
    'intrinsicWidthSingleParagraphFallbackPreservesComplexAnnotationsAndSeparators',
    'intrinsicWidthSingleParagraphPairedPassesVisitEveryRowWithoutWidthCaches',
))
SIZES = (1000, 5000, 10000)
PAIRS = 4
CONTROL = 'benchmarks/terminal-target/src/main/java/me/rerere/rikkahub/benchmark/TerminalIntrinsicWidthMeasurer.kt'
CONTROL_SHA = 'ac47787cf2939a91779f2563fa40bb8b610a07e7'
CONTROL_HASH = 'c215a3537a3824ecfecba7bb2645f117a0d32fc97246e17cdbf619ac726a30a2'


def validate(records):
    seen, vectors = set(), {}
    for row in records:
        size, pair, old = row.get('size'), row.get('pair'), row.get('legacy')
        key = size, pair, old
        if (type(size) is not int or size not in SIZES or type(pair) is not int or pair not in range(PAIRS)
                or type(old) is not bool or key in seen):
            raise ValueError('Invalid or duplicate row-width sample')
        if type(row.get('first')) is not bool or row['first'] != (old == (pair % 2 == 0)):
            raise ValueError('Row-width pass order did not alternate')
        expected = dict(rows=size + 6, singleCalls=0 if old else size + 6, multiCalls=size + 6 if old else 0)
        if any(type(row.get(name)) is not int or row[name] != value for name, value in expected.items()):
            raise ValueError('Row-width pass did not visit every row on the expected path')
        if any(type(row.get(name)) is not int or row[name] <= 0 for name in ('nanos', 'widthSum')):
            raise ValueError('Missing positive row-width measurement')
        digest = row.get('widthSha256')
        if not isinstance(digest, str) or not re.fullmatch('[a-f0-9]{64}', digest):
            raise ValueError('Missing full width-vector digest')
        identity = row['widthSum'], digest
        if vectors.setdefault(size, identity) != identity:
            raise ValueError('Width vectors differ across candidate/control or repeats')
        seen.add(key)
    if seen != {(size, pair, old) for size in SIZES for pair in range(PAIRS) for old in (True, False)}:
        raise ValueError(f'Incomplete row-width matrix: {len(seen)}/24')
    return records


def summarize(records):
    validate(records)
    result = []
    for size in SIZES:
        arms = {old: [next(r['nanos'] / 1e6 for r in records if (r['size'], r['pair'], r['legacy']) ==
                          (size, pair, old)) for pair in range(PAIRS)] for old in (True, False)}
        deltas = [new - old for old, new in zip(arms[True], arms[False])]
        result.append(dict(size=size, rows=size + 6, pairs=PAIRS,
                           legacyMedianMs=statistics.median(arms[True]), candidateMedianMs=statistics.median(arms[False]),
                           pairedDeltaMedianMs=statistics.median(deltas), pairedDeltasMs=deltas))
    return result


def main():
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    if not re.fullmatch('[a-f0-9]{40}', sha) or not re.fullmatch('[0-9]+', run):
        raise ValueError('Expected exact CI source/run identity')
    junit = common.require_junit(TEST_ROOT / 'build/outputs/androidTest-results', CLASS, CASES)
    if common.digest(ROOT / CONTROL) != CONTROL_HASH:
        raise ValueError('Frozen original MultiParagraph width oracle changed')
    with (ROOT / 'artifacts/terminal-validation/row-width-logcat.txt').open() as stream:
        records = [json.loads(line.split('ROW_WIDTH_SAMPLE ', 1)[1]) for line in stream if 'ROW_WIDTH_SAMPLE ' in line]
    summary = summarize(records)
    copied = ('me/rerere/rikkahub/ui/pages/container/TerminalRowWidthMeasurer.kt',
              'me/rerere/rikkahub/ui/pages/container/TerminalTranscriptWidth.kt',
              'me/rerere/rikkahub/data/container/TerminalTranscriptWidthIndex.kt',
              'me/rerere/rikkahub/utils/TerminalEmulator.kt', 'me/rerere/rikkahub/utils/TerminalFrameSnapshot.kt')
    for path in copied:
        if common.digest(ROOT / 'app/src/main/java' / path) != common.digest(TEST_ROOT / 'build/generated/viewportSources' / path):
            raise ValueError('Fixture does not contain the actual production width source: ' + path)
    sources = ['app/src/main/java/' + path for path in copied] + [CONTROL,
        'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalIntrinsicWidthInstrumentedTest.kt',
        'benchmarks/terminal-viewport-tests/build.gradle.kts', 'benchmarks/terminal-viewport-tests/run-ci.sh',
        '.github/scripts/report-terminal-row-width.py', '.github/workflows/terminal-viewport-interactions.yml',
        'app/src/main/res/font/jetbrains_mono.ttf', 'gradle/libs.versions.toml']
    apks = {}
    for role, folder in (('fixture', 'release'), ('tests', 'androidTest/release')):
        files = list((TEST_ROOT / 'build/outputs/apk' / folder).glob('*.apk'))
        if len(files) != 1:
            raise ValueError('Expected exactly one row-width ' + role + ' APK')
        apks[role] = common.digest(files[0])
    report = dict(suite='terminal-row-width-v1', sourceSha=sha, runId=run, controlSha=CONTROL_SHA,
        controlFileSha256=CONTROL_HASH, sourceHashes={path: common.digest(ROOT / path) for path in sources},
        apkHashes=apks, junit=junit, samples=records, summary=summary,
        note='Same immutable frame and device, four alternating passes per size; full per-row widths asserted equal. '
             'Typeface warmup excluded for both, but Android font caches remain normal. No width-index/Paragraph cache. '
             'These are uncached scalar measurement passes, not cold process startup, full-page rendering or phone FPS.')
    (ROOT / 'artifacts/terminal-validation/row-width.json').write_text(json.dumps(report, indent=2) + '\n')
    common.notice('Terminal row width verified', '\n'.join(
        f'{r["size"]} history: rows={r["rows"]} n={PAIRS} old={r["legacyMedianMs"]:.3f}ms '
        f'new={r["candidateMedianMs"]:.3f}ms pairedDelta={r["pairedDeltaMedianMs"]:+.3f}ms' for r in summary))
    data = json.dumps(report, separators=(',', ':')).encode()
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    if len(parts) > 8:
        raise ValueError('Row-width evidence exceeds annotation budget')
    for index, part in enumerate(parts, 1):
        common.notice(f'Terminal row width evidence {index}/{len(parts)}',
                      'zlib-base64 sha256=' + hashlib.sha256(data).hexdigest() + '\n' + part)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        common.notice('Terminal row width rejected', str(error)[:2800], level='error')
        raise SystemExit(1)
