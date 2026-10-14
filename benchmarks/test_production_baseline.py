import copy
import gzip
import json
import tempfile
import unittest
from pathlib import Path

import production_baseline as baseline
from production_summary import CAPTURE, PUBLICATION, SCENARIOS, VERSION, report, validate_publications
from test_production_summary import APK_SHA, DRIVER_SHA, RUN, SHA, context, matrix, publication_lines

SOURCES = {'production.kt': 'd' * 64}
TREE = 'e' * 40


def evidence(scenario, smoke, iterations=3, preflight=None):
    count = 1 if smoke else iterations
    results = matrix(scenario, smoke)
    for case in results.values():
        case['repeatIterations'] = count
        for kind in ('metrics', 'sampledMetrics'):
            for metric in case[kind].values():
                metric['runs'] = [copy.deepcopy(metric['runs'][0]) for _ in range(count)]
    ctx = context(scenario, smoke)
    group = f'{RUN}-1-{scenario}-{"smoke" if smoke else "full"}'
    ctx['payload']['groupId'] = group
    request = dict(ctx['payload'], iterations=count)
    manifest = dict(sourceSha=SHA, buildRun=RUN, suite=VERSION, publication=PUBLICATION, capture=CAPTURE,
                    sources=SOURCES, productionTree=TREE, apks={
                        role: dict(file=role + '.apk', sha256=sha, bytes=123, package=package)
                        for role, sha, package in (
                            ('target', APK_SHA, 'me.rerere.rikkahub.terminalbenchmark'),
                            ('driver', DRIVER_SHA, 'me.rerere.rikkahub.terminalbenchmark.test'))})
    device = dict(bootId='00000000-0000-4000-8000-000000000000', fingerprint='android-test',
                  size='1080x1920', density='420', heap='768m', ime='test-ime')
    records = publication_lines(scenario, count, smoke)
    publication = validate_publications(records, scenario, count, smoke)
    doc = dict(context=ctx, benchmarks=list(results.values()))
    raw = json.dumps(doc, indent=2) + '\n'
    files = {'measurements/production-benchmarkData.json': baseline.digest(raw.encode())}
    receipt = dict(request, cases=len(results), verified=True, measuredLaunches=len(results) * count,
                   device=device, preflightGroup=preflight['groupId'] if preflight else None)
    return dict(request=request, manifest=manifest, device=device, validation=receipt, preflight=preflight,
                installedApkHashes=dict(target=APK_SHA, driver=DRIVER_SHA),
                publicationRecords=records, publicationEvidence=publication, document=doc,
                resultFiles=files, rawDocuments={next(iter(files)): raw},
                summary=report(results, ctx, SHA, scenario, smoke))


def write_evidence(root, value):
    request = value['request']
    folder = root / request['groupId']
    folder.mkdir(parents=True)
    data = json.dumps(value, separators=(',', ':')).encode()
    compressed = gzip.compress(data, mtime=0)
    (folder / 'evidence.json.gz').write_bytes(compressed)
    integrity = {key: request[key] for key in ('sourceSha', 'runId', 'groupId', 'suite')}
    integrity.update(jsonSha256=baseline.digest(data), gzipSha256=baseline.digest(compressed),
                     rawResultSha256=value['resultFiles'], measuredLaunches=value['validation']['measuredLaunches'])
    (folder / 'integrity.json').write_text(json.dumps(integrity))
    return folder


def write_pair(root, scenario, iterations=3):
    smoke = evidence(scenario, True)
    full = evidence(scenario, False, iterations, smoke['validation'])
    return [write_evidence(root, item) for item in (smoke, full)]


class ProductionBaselineTest(unittest.TestCase):
    def collect(self, root, scenario='all', iterations=3):
        return baseline.collect(root, SHA, RUN, scenario, iterations, SOURCES, TREE)

    def test_complete_baseline_requires_54_cases_162_measurements_and_excludes_18_smoke(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for scenario in SCENARIOS:
                write_pair(root, scenario)
            result, samples = self.collect(root)
            self.assertTrue(result['complete'])
            self.assertEqual((54, 162, 18), (result['cases'], result['measurements'], result['preflightMeasurements']))
            self.assertEqual(162, len(samples))
            self.assertEqual(set(SCENARIOS), set(result['groups']))
            self.assertTrue(all(row['groupId'].endswith('-full') for row in samples))

    def test_one_complete_scenario_is_not_a_complete_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_pair(root, 'initialCompose')
            result, samples = self.collect(root, 'initialCompose')
            self.assertFalse(result['complete'])
            self.assertEqual(27, len(samples))
            with self.assertRaisesRegex(ValueError, 'Incomplete baseline'):
                self.collect(root)

    def test_missing_smoke_or_wrong_repetitions_cannot_be_hidden(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            smoke = evidence('initialCompose', True)
            write_evidence(root, evidence('initialCompose', False, 3, smoke['validation']))
            with self.assertRaisesRegex(ValueError, 'Incomplete baseline'):
                self.collect(root, 'initialCompose')
            write_evidence(root, smoke)
            with self.assertRaisesRegex(ValueError, 'repetition'):
                self.collect(root, 'initialCompose', iterations=2)

    def test_bad_compression_hash_or_duplicate_input_never_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folders = write_pair(root, 'initialCompose')
            integrity = folders[0] / 'integrity.json'
            original = integrity.read_text()
            record = json.loads(original)
            record['jsonSha256'] = '0' * 64
            integrity.write_text(json.dumps(record))
            with self.assertRaisesRegex(ValueError, 'integrity'):
                self.collect(root, 'initialCompose')
            integrity.write_text(original)
            write_pair(root / 'duplicate', 'initialCompose')
            with self.assertRaisesRegex(ValueError, 'Duplicate'):
                self.collect(root, 'initialCompose')

    def test_changed_source_installation_receipt_and_raw_json_are_rejected(self):
        good = evidence('initialCompose', True)
        mutations = (
            lambda value: value['manifest'].update(sourceSha='f' * 40),
            lambda value: value['manifest'].update(productionTree='0' * 40),
            lambda value: value['manifest'].update(capture='old-window'),
            lambda value: value['installedApkHashes'].update(target='0' * 64),
            lambda value: value['validation'].update(verified=False),
            lambda value: value['validation'].update(measuredLaunches=0),
            lambda value: value.update(publicationRecords=[]),
            lambda value: value.update(rawDocuments={}),
            lambda value: value['resultFiles'].update({next(iter(value['resultFiles'])): '0' * 64}),
            lambda value: value.update(summary='invented results'),
        )
        for index, mutate in enumerate(mutations):
            value = copy.deepcopy(good)
            mutate(value)
            with self.subTest(index=index), self.assertRaises(ValueError):
                baseline.verify_evidence(value, SHA, RUN, SOURCES, TREE)

    def test_rehashed_modified_raw_samples_do_not_match_the_reported_measurements(self):
        value = evidence('initialCompose', True)
        name = next(iter(value['rawDocuments']))
        raw = json.loads(value['rawDocuments'][name])
        raw['benchmarks'][0]['metrics']['mountSumMs']['runs'][0] += 100
        value['rawDocuments'][name] = json.dumps(raw)
        value['resultFiles'][name] = baseline.digest(value['rawDocuments'][name].encode())
        with self.assertRaisesRegex(ValueError, 'Raw/normalized'):
            baseline.verify_evidence(value, SHA, RUN, SOURCES, TREE)

    def test_actual_preflight_receipt_and_boot_must_match(self):
        smoke = evidence('initialCompose', True)
        value = evidence('initialCompose', False, 3, copy.deepcopy(smoke['validation']))
        value['preflight']['device']['bootId'] = 'different-boot'
        with self.assertRaisesRegex(ValueError, 'Preflight boot'):
            baseline.verify_evidence(value, SHA, RUN, SOURCES, TREE)


if __name__ == '__main__':
    unittest.main()
