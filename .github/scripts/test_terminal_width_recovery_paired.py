import importlib.util,copy,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('p2_pair_contracts',Path(__file__).with_name('terminal-width-recovery-paired.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture(returnRatio=.6,refreshRatio=1.):
    invocations=[]
    for p in range(4):
        for pos,arm in enumerate(m.pair.order(p)):
            cases=[];traces=[]
            for name in m.CASES:
                samples=[];rows=[];widths=[]
                for r in range(5):
                    for condition in m.baseline.conditions(r):
                        value=1000 if condition=='return' else 100
                        if arm=='candidate':value*=returnRatio if condition=='return' else refreshRatio
                        samples.append(dict(index=r,condition=condition,warmup=r==0))
                        rows.append(dict(inputToDrawNs=value,publishToDrawNs=value,inputToDisplayEndNs=value))
                        widths.append(dict(widthWorkerRunningNs=0,widthWallUnionNs=1))
                cases.append(dict(case=name,samples=samples));traces.append(dict(case=name,rows=rows,widthWork=widths))
            invocations.append(dict(pair=p,position=pos,arm=arm,result=dict(cases=cases,traces=traces,systemEvidenceComplete=True)))
    return invocations
class RecoveryPairedContracts(unittest.TestCase):
    def test_candidate_stays_the_passed_three_file_patch(self):
        self.assertEqual(3,len(m.guard()['files']));self.assertEqual(41180293887,m.GATE_RUN)
        self.assertEqual(41184883013,m.SETUP_RUN)
        code=Path(m.__file__).read_text();self.assertIn('Common driver was rebuilt differently',code)
        self.assertIn('Source overlay not restored',code)
    def test_frozen_return_and_refresh_guards(self):
        summary,passed=m.summarize(fixture());self.assertTrue(passed);self.assertEqual(8,len(summary))
        self.assertFalse(m.summarize(fixture(returnRatio=.9))[1])
        self.assertFalse(m.summarize(fixture(refreshRatio=1.1))[1])
    def test_missing_system_data_is_not_dropped(self):
        rows=fixture();rows[0]['result']['systemEvidenceComplete']=False
        index=next(i for i,s in enumerate(rows[0]['result']['cases'][0]['samples']) if s['condition']=='return' and not s['warmup'])
        del rows[0]['result']['traces'][0]['rows'][index]['inputToDisplayEndNs']
        summary,passed=m.summarize(rows);self.assertFalse(passed)
        self.assertIsNone(summary[1]['inputToDisplayEndNs']['medianRatio'])
    def test_driver_is_real_clock_and_ime_grid_stabilizes_before_keyboard(self):
        source=(m.ROOT/m.TEST).read_text()
        for word in ('mainClock','waitForIdle','createAndroidComposeRule','performSemanticsAction'):self.assertNotIn(word,source)
        self.assertLess(source.index('initial hidden grid stable'),source.index('if(expectedIme) {'))
        self.assertIn('IME changed the physical PTY grid',source)
        self.assertEqual(4,source.count('@Test fun'))
    def test_no_incomplete_or_reordered_launches(self):
        for rows in (fixture()[:-1],list(reversed(fixture()))):
            with self.assertRaises(ValueError):m.summarize(rows)
if __name__=='__main__':unittest.main()
