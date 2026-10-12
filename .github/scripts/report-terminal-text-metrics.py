"""Validate paired text-metric work and all resolver/async compatibility cases, not phone FPS."""
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
    'text_metrics_common', Path(__file__).with_name('report-terminal-panel-subscription.py'))
common = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(common)
CLASS = 'me.rerere.rikkahub.viewporttest.TerminalTextMetricsInstrumentedTest'
CASES = frozenset((
    'textMetricsPackagedFontAvoids128RepeatedResolvesAndReusesIdentity',
    'textMetricsStyleDensityDirectionSynthesisAndResolverInvalidatePrecisely',
    'textMetricsEqualFamilyCopiesAndCustomFontsKeepLiveResolveBehavior',
    'textMetricsAsyncSuccessUpdatesWidthsAndRealHeightsWithoutOutput',
    'textMetricsAsyncFailureKeepsFallbackAndDoesNotRetryPermanentFailure',
    'textMetricsAsyncCancellationAllowsRetryWithReplacementResolver',
))
EXPECTED = dict(updates=32, legacyResolves=128, cachedResolves=0, legacyMetrics=32, cachedMetrics=0,
                legacyCompositions=32, cachedCompositions=32, identityChecks=32)


def validate(records):
    if len(records) != 1 or not isinstance(records[0], dict) or set(records[0]) != set(EXPECTED):
        raise ValueError('Expected exactly one complete text-metrics record')
    for name, expected in EXPECTED.items():
        if type(records[0][name]) is not int or records[0][name] != expected:
            raise ValueError('Incorrect paired text-metrics work: ' + name)
    return records[0]


def main():
    sha, run = os.environ.get('GITHUB_SHA', ''), os.environ.get('GITHUB_RUN_ID', '')
    if not re.fullmatch('[a-f0-9]{40}', sha) or not re.fullmatch('[0-9]+', run):
        raise ValueError('Expected exact CI source/run identity')
    junit = common.require_junit(common.TEST_ROOT / 'build/outputs/androidTest-results', CLASS, CASES)
    with (common.ROOT / 'artifacts/terminal-validation/text-metrics-logcat.txt').open() as stream:
        records = [json.loads(line.split('TEXT_METRICS_WORK ', 1)[1])
                   for line in stream if 'TEXT_METRICS_WORK ' in line]
    sample = validate(records)
    copied = (
        'me/rerere/rikkahub/ui/pages/container/TerminalTranscriptWidth.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalViewportBinding.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalRowWidthMeasurer.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalLazyItemMeasurements.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalTranscriptViewport.kt',
        'me/rerere/rikkahub/ui/pages/container/TerminalVirtualHistoryTranscript.kt',
        'me/rerere/rikkahub/data/container/TerminalTranscriptWidthIndex.kt',
        'me/rerere/rikkahub/data/container/TerminalEagerGeometryCache.kt',
        'me/rerere/rikkahub/ui/theme/Type.kt',
    )
    for path in copied:
        if (common.digest(common.ROOT / 'app/src/main/java' / path) !=
                common.digest(common.TEST_ROOT / 'build/generated/viewportSources' / path)):
            raise ValueError('Fixture differs from production text-metrics source: ' + path)
    paths = ['app/src/main/java/' + path for path in copied] + [
        'app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt',
        'app/src/main/res/font/jetbrains_mono.ttf', 'gradle/libs.versions.toml',
        'benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/'
        'TerminalTextMetricsInstrumentedTest.kt',
        'benchmarks/terminal-viewport-tests/build.gradle.kts', 'benchmarks/terminal-viewport-tests/run-ci.sh',
        '.github/scripts/report-terminal-text-metrics.py', '.github/scripts/report-terminal-panel-subscription.py',
        '.github/workflows/terminal-viewport-interactions.yml', '.github/workflows/terminal-emulator-tests.yml',
    ]
    apks = {}
    for role, directory in (('fixture', 'release'), ('tests', 'androidTest/release')):
        files = list((common.TEST_ROOT / 'build/outputs/apk' / directory).glob('*.apk'))
        if len(files) != 1:
            raise ValueError('Expected exactly one text-metrics ' + role + ' APK')
        apks[role] = common.digest(files[0])
    report = dict(suite='terminal-text-metrics-v1', sourceSha=sha, runId=run,
                  sourceHashes={path: common.digest(common.ROOT / path) for path in paths},
                  apkHashes=apks, junit=junit, sample=sample,
                  note='One resolver/device; 32 independently committed no-output recompositions per arm. '
                       'Initial font resolution excluded. Query calls and committed metric identities, not '
                       'font loads, allocation bytes, full-page latency or phone FPS. Only the exact built-in '
                       'blocking font retains lookup States; other fonts retain live resolution. Real-page PTY '
                       'and cache JVM regressions are separate, exact-SHA checks.')
    (common.ROOT / 'artifacts/terminal-validation/text-metrics.json').write_text(json.dumps(report, indent=2) + '\n')
    common.notice('Terminal text metrics verified', json.dumps(sample))
    data = json.dumps(report, separators=(',', ':')).encode()
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = [encoded[i:i + 2600] for i in range(0, len(encoded), 2600)]
    if len(parts) > 8:
        raise ValueError('Text-metrics evidence exceeds annotation budget')
    for index, part in enumerate(parts, 1):
        common.notice(f'Terminal text metrics evidence {index}/{len(parts)}',
                      'zlib-base64 sha256=' + hashlib.sha256(data).hexdigest() + '\n' + part)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, ET.ParseError) as error:
        common.notice('Terminal text metrics rejected', str(error)[:2800], level='error')
        raise SystemExit(1)
