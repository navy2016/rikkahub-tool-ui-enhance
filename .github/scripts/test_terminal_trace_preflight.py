import copy
import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('trace_preflight',Path(__file__).with_name('terminal-trace-preflight.py'))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

def report():
    return dict(productionTree=gate.TREE,productionSha=gate.PRODUCTION,noApkBuilt=True,baselineResampled=False,transportAvailable=True,
        cases=[dict(case=case,durationMs=2000,baselineSamples=0,collectionAttempted=True,captureVerified=i==1,
            trace=dict(completeFrames=12,bytes=100),pull=dict(exit=0)) for i,case in enumerate(gate.CASES)])

class TracePreflightTest(unittest.TestCase):
    def test_fixed_three_transport_controls_do_not_change_baseline(self):
        for case in gate.CASES:
            text=gate.config(case)
            self.assertIn('duration_ms: 2000',text);self.assertNotIn('540000',text)
            self.assertIn('com.android.settings',text)
            self.assertEqual(case=='foreground-buffer','write_into_file: false' in text)
        self.assertTrue(gate.validate(report())['transportAvailable'])
    def test_missing_retrieval_or_fake_frames_is_not_success(self):
        for change in (lambda r:r['cases'].pop(),lambda r:r['cases'][0].update(collectionAttempted=False),
            lambda r:r['cases'][1]['trace'].update(completeFrames=0),lambda r:r.update(baselineResampled=True),
            lambda r:r.update(noApkBuilt=False),lambda r:r.update(transportAvailable=False)):
            value=report();change(value)
            with self.assertRaises(ValueError):gate.validate(value)
    def test_foreground_diagnostics_and_unconditional_pull_are_retained(self):
        text=(gate.ROOT/'.github/scripts/terminal-trace-preflight.py').read_text()
        self.assertIn("finally:\n        # Crucial",text)
        self.assertIn("adb('pull',remote",text)
        self.assertIn('background-wait',text)
        self.assertNotIn('assembleTerminaltest',text)
        self.assertNotIn('fullVirtualLatency',text)

if __name__=='__main__':unittest.main()
