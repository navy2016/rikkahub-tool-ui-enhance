import importlib.util,json
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('cursor_experiment',Path(__file__).with_name('terminal-full-virtual-cursor-experiment.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def fixture():
    data={'SAMPLE':[],'DISPOSE':[],'PIXEL':[],'DEVICE':[dict(pid=1,fingerprint='fixture',targetApk='a'*64,testApk='b'*64)]}
    for size in (1000,10000):
        for shape in (6,4):
            for r in range(5):
                for overlay in ((False,True) if r%2==0 else (True,False)):
                    row=dict(size=size,shape=shape,round=r,overlay=overlay)
                    data['SAMPLE'].append(dict(row,warmup=r==0,moves=24,elapsedNs=200 if overlay else 300,paragraphChanges=0 if overlay else 48,
                        textChanges=0 if overlay else 48,widthMeasurements=0 if overlay else 48,rowMeasurements=24,textSha256='c'*64,
                        retainedRows=24,measuredRows=20,writers=1))
                    data['DISPOSE'].append(dict(row,retainedLayouts=0,pointerRows=0))
    for rtl in (False,True):
        for scale in (1,1.3):
            for shape in (6,4):
                for col in (6,8,13,15,18):data['PIXEL'].append(dict(rtl=rtl,scale=scale,shape=shape,column=col,changedPixels=20,paragraphReused=True,virtual=True))
    return data
def text(data):return '\n'.join('CURSOR_VIRTUAL_'+k+' '+json.dumps(row) for k,rows in data.items() for row in rows)
class CursorExperimentTest(unittest.TestCase):
    def test_fixed_samples_and_pixels(self):
        result=m.validate(text(fixture()));self.assertEqual(40,len(result['samples']));self.assertEqual(40,len(result['pixels']))
        self.assertTrue(all(r['workReductionDemonstrated'] for r in result['summary']))
    def test_missing_order_corrupt_geometry_and_retention_rejected(self):
        for change in (lambda d:d['SAMPLE'].pop(),lambda d:d['SAMPLE'].reverse(),lambda d:d['PIXEL'][0].update(changedPixels=0),
                       lambda d:d['DISPOSE'][0].update(retainedLayouts=1),lambda d:d['SAMPLE'][0].update(textSha256='d'*64)):
            d=fixture();change(d)
            with self.assertRaises(ValueError):m.validate(text(d))
    def test_no_work_gain_is_recorded_not_filtered(self):
        d=fixture()
        for row in d['SAMPLE']:
            if row['overlay']:row.update(paragraphChanges=2,elapsedNs=500)
        result=m.validate(text(d));self.assertFalse(any(r['workReductionDemonstrated'] for r in result['summary']))
        self.assertTrue(all(all(v>0 for v in r['pairedElapsedDeltaNs']) for r in result['summary']))
    def test_experiment_does_not_opt_in_existing_fixture_calls(self):
        source=(m.ROOT/m.FIXTURE).read_text()
        self.assertIn('private val cursorOverlayExperiment: Boolean = false',source)
        self.assertIn('if (cursorOverlayExperiment)',source)
    def test_report_step_is_after_tests_and_artifact_preserved(self):
        text=(m.ROOT/'.github/workflows/terminal-viewport-interactions.yml').read_text()
        report=text.split('- name: Require every case assigned to this shard',1)[1].split('- name:',1)[0]
        self.assertIn('cursorExperiment',report)
        self.assertIn('artifacts/terminal-cursor-experiment/**',text)
        self.assertIn('cursorTiming',report)
if __name__=='__main__':unittest.main()
