import importlib.util,unittest,copy
from pathlib import Path
s=importlib.util.spec_from_file_location('p1_pairs',Path(__file__).with_name('terminal-full-virtual-content-paired.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture(ratio=.8):
    rows=[]
    for p in range(4):
        for pos,arm in enumerate(m.pair.order(p)):
            value=1000 if arm=='legacy' else 1000*ratio
            samples=[dict(phase=phase,index=i,warmup=i<2,inputToDrawNs=value,publishToDrawNs=value,inputToDisplayEndNs=value,mainRunningPublishToDrawNs=value) for phase in m.real.PHASES for i in range(12)]
            rows.append(dict(pair=p,position=pos,arm=arm,result=dict(correlation=samples,systemEvidenceComplete=True)))
    return rows
class ContentPairedContracts(unittest.TestCase):
    def test_single_candidate_is_the_one_that_passed_mechanism(self):
        self.assertEqual(1,len(m.guard()['files']))
        self.assertEqual(41167700810,m.GATE_RUN)
        code=Path(m.__file__).read_text()
        self.assertIn("Common driver changed",code);self.assertIn("Candidate overlay was not restored",code)
        self.assertNotIn('fontOracle',code)
    def test_frozen_ratio_gate_pass_and_fail(self):
        rows,ok=m.summarize(fixture());self.assertTrue(ok);self.assertEqual(3,len(rows))
        self.assertFalse(m.summarize(fixture(.96))[1])
        self.assertFalse(m.summarize(fixture(1.2))[1])
    def test_missing_system_metric_never_dropped_or_replaced(self):
        data=fixture();data[0]['result']['systemEvidenceComplete']=False
        del data[0]['result']['correlation'][7]['inputToDisplayEndNs']
        rows,ok=m.summarize(data);self.assertFalse(ok)
        self.assertIsNone(rows[0]['inputToDisplayEndNs']['medianRatio'])
        self.assertIsNone(rows[0]['inputToDisplayEndNs']['arms']['legacy'][0])
    def test_no_partial_or_reordered_matrix(self):
        for data in (fixture()[:-1],list(reversed(fixture()))):
            with self.assertRaises(ValueError):m.summarize(data)
if __name__=='__main__':unittest.main()
