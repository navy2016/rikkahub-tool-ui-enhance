import importlib.util,json,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('startup_contracts',Path(__file__).with_name('terminal-startup-settings.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture():
    return [dict(case=name,passed=True,performanceMeasured=False,controlledReplay=name=='controlled-replay',
        fenced=name.startswith('fenced') or name=='controlled-replay',retained=name!='controlled-replay',
        launchReceipt='incrementLaunchCount: 1',notes=[dict(stage=x) for x in ('before-write','confirmed','after-startup')]) for name in m.CASES]
def text(rows):return '\n'.join('STARTUP_SETTINGS_CASE '+json.dumps(r) for r in rows)
class StartupContracts(unittest.TestCase):
    def test_fixed_unfenced_fenced_replay_observations(self):
        rows=fixture();self.assertEqual(9,len(m.validate(text(rows))))
        rows[0]['retained']=False;self.assertEqual(9,len(m.validate(text(rows))))
        rows[4]['retained']=False
        with self.assertRaises(ValueError):m.validate(text(rows))
    def test_replay_must_not_be_claimed_as_natural(self):
        rows=fixture();rows[-1]['controlledReplay']=False
        with self.assertRaises(ValueError):m.validate(text(rows))
    def test_no_terminal_or_measurement_operations(self):
        m.guard();source=(m.ROOT/m.TEST).read_text()
        self.assertIn('store.update(saved.copy(launchCount = saved.launchCount + 1))',source)
        for x in ('ActivityScenario','ProcessSessionPage','manager.startInteractiveSession','mainClock'):self.assertNotIn(x,source)
        self.assertIn('logcat -d -v brief --pid $pid',source)
if __name__=='__main__':unittest.main()
