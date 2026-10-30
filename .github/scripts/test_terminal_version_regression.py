import copy
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch
from test_terminal_bundle_paired import document as previous

spec=importlib.util.spec_from_file_location('version_comparison',Path(__file__).with_name('terminal-version-regression.py'))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

def document():
    d=previous();d['suite']=d['plan']['suite']=gate.SUITE
    d['plan'].update(referenceSha=gate.REFERENCE,candidateSha=gate.CANDIDATE,commonDriverSourceSha=gate.REFERENCE)
    for arm,m in d['plan']['manifests'].items():
        common=m['commonTestSources'];m['sources']={p:('1' if arm=='legacy' else '2')*64 for p in gate.ALLOWED};m['sources'].update(common)
        m.update(productionSha=gate.REFERENCE if arm=='legacy' else gate.CANDIDATE,productionTree=gate.TREE if arm=='candidate' else '3'*40,
                 changedProductionFiles=sorted(gate.ALLOWED))
    return d

class VersionRegressionTest(unittest.TestCase):
    def test_exact_288_samples_and_72_warmups(self):
        d=gate.validate(document());self.assertEqual(288,sum(len(x['samples']) for x in d['invocations']))
        self.assertEqual(72,sum(len(x['samples']) for x in d['warmups'].values()))
    def test_sources_driver_and_order_cannot_drift(self):
        for change in (lambda d:d['plan'].update(referenceSha='0'*40),
            lambda d:d['plan']['manifests']['candidate'].update(productionSha='0'*40),
            lambda d:d['plan']['manifests']['candidate']['sources'].update({'other.kt':'7'*64}),
            lambda d:d['plan']['manifests']['candidate']['apks']['test'].update(sha256='8'*64),
            lambda d:d['invocations'].reverse()):
            d=document();change(d)
            with self.assertRaises(ValueError):gate.validate(d)
    def test_real_regression_is_reported_not_filtered(self):
        d=document()
        for entry in d['invocations']:
            if entry['arm']=='candidate':
                for row in entry['samples']:row.update(inputToDrawMs=20.0,FRAME_DRAWN=20.0,inputToVisibleCheckMs=25.0)
        values=gate.legacy.summary(gate.validate(d));self.assertTrue(all(r['inputToDrawMs']['pairedMedianDelta']==12 for r in values))
    def test_local_build_forbidden_before_source_overlay(self):
        with patch.dict(os.environ,{},clear=True),patch.object(gate.helpers,'restore') as restore:
            with self.assertRaises(ValueError):gate.build()
            restore.assert_not_called()

if __name__=='__main__':unittest.main()
