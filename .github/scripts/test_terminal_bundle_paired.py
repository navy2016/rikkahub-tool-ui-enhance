"""Validate whole-batch evidence without compiling, starting a device, or changing source overlays."""
import copy
import importlib.util
import os
import unittest
from pathlib import Path
from unittest.mock import patch

from test_terminal_pipeline_paired import document as old_document

SPEC = importlib.util.spec_from_file_location('bundle_comparison',
    Path(__file__).with_name('run-terminal-bundle-paired.py'))
bundle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bundle)


def document():
    value = old_document()
    value['suite'] = value['plan']['suite'] = bundle.SUITE
    value['plan']['controlSha'] = bundle.CONTROL
    common = {bundle.TEST_ROOT + 'test.kt': '9' * 64}
    path = bundle.MAIN + 'ui/pages/container/ProcessSessionPage.kt'
    for arm, manifest in value['plan']['manifests'].items():
        manifest.update(productionSha=bundle.CONTROL if arm == 'legacy' else manifest['sourceSha'],
                        changedProductionFiles=[path], commonTestSources=common)
        manifest['sources'].update(common)
    return value


class BundlePairedTest(unittest.TestCase):
    def test_all_288_samples_and_72_excluded_warmups_keep_their_source_identities(self):
        value = bundle.validate(document())
        self.assertEqual(288, sum(len(row['samples']) for row in value['invocations']))
        self.assertEqual(72, sum(len(row['samples']) for row in value['warmups'].values()))

    def test_wrong_baseline_unlisted_production_changes_and_driver_drift_fail(self):
        value = document()
        mutations = (
            lambda v: v['plan'].update(controlSha='0' * 40),
            lambda v: v['plan']['manifests']['legacy'].update(productionSha='0' * 40),
            lambda v: v['plan']['manifests']['candidate']['sources'].update({'other.kt': '8' * 64}),
            lambda v: v['plan']['manifests']['candidate'].update(changedProductionFiles=[]),
            lambda v: v['plan']['manifests']['candidate'].update(commonTestSources={}),
            lambda v: v['plan']['manifests']['candidate']['apks']['test'].update(sha256='8' * 64),
        )
        for mutation in mutations:
            changed = copy.deepcopy(value)
            mutation(changed)
            with self.assertRaises(ValueError):
                bundle.validate(changed)

    def test_missing_duplicate_order_device_or_timestamps_cannot_pass(self):
        for change in ('missing', 'duplicate', 'order', 'device', 'time'):
            value = document()
            entries = value['invocations']
            if change == 'missing':
                entries.pop()
            elif change == 'duplicate':
                entries[1] = copy.deepcopy(entries[0])
            elif change == 'order':
                entries[:2] = reversed(entries[:2])
            elif change == 'device':
                entries[0]['device'] = dict(entries[0]['device'], density='different')
            else:
                entries[1]['startedNanos'] = entries[0]['endedNanos']
            with self.subTest(change=change), self.assertRaises(ValueError):
                bundle.validate(value)

    def test_summary_keeps_regressions_and_does_not_modify_the_frozen_experiment(self):
        value = bundle.validate(document())
        for entry in value['invocations']:
            if entry['arm'] == 'candidate':
                for row in entry['samples']:
                    row.update(inputToDrawMs=20.0, FRAME_DRAWN=20.0, inputToVisibleCheckMs=25.0)
        result = bundle.legacy.summary(bundle.validate(value))
        self.assertTrue(all(row['inputToDrawMs']['pairedMedianDelta'] == 12 for row in result))
        from test_terminal_pipeline_paired import paired
        self.assertEqual('terminal-pipeline-paired-v1', paired.SUITE)
        self.assertNotEqual(paired.SUITE, bundle.legacy.SUITE)

    def test_local_build_is_refused_before_source_changes(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(bundle, 'restore') as restore:
            with self.assertRaises(ValueError):
                bundle.build()
            restore.assert_not_called()


if __name__ == '__main__':
    unittest.main()
