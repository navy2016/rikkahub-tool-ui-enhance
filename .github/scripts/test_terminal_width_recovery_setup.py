import base64,copy,hashlib,importlib.util,json,unittest,zlib
from pathlib import Path
s=importlib.util.spec_from_file_location('p2_setup_contracts',Path(__file__).with_name('terminal-width-recovery-setup.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture():
    cases=[];lines=[]
    for name in m.p.CASES:
        size=int(name.split('-')[1]);shown=name.endswith('-shown')
        pref=dict(forcedTerminalColumns=80,terminalPanMode=True,maxScrollbackLines=size,rawInputMode=False)
        c=dict(case=name,setupOnly=True,acceptance=False,passed=True,samples=[],modelColumns=80,modelRows=25,modelHistory=size,expectedIme=shown,
            gates=[dict(ime=shown,virtual=True)],preparation=[dict(stage=stage,configured=True,init=False,decoded=pref) for stage in
            ('before-write','after-write','persisted-and-flow-confirmed','before-mount')])
        cases.append(c)
    return cases
def text(cases):
    lines=[]
    for c in cases:
        raw=json.dumps(c).encode();lines.append('REAL_CLOCK_PART '+json.dumps(dict(case=c['case'],part=1,parts=1,
            sha256=hashlib.sha256(raw).hexdigest(),data=base64.b64encode(zlib.compress(raw)).decode())))
        lines.append('REAL_CLOCK_CLEANUP '+json.dumps(dict(case=c['case'],closed=True,threadStopped=True)))
    return '\n'.join(lines)
class SetupContracts(unittest.TestCase):
    def test_all_four_configured_without_performance(self):self.assertEqual(4,len(m.validate(text(fixture()))[0]))
    def test_wrong_first_mount_or_performance_data_is_rejected(self):
        for mutate in (lambda c:c[3].update(modelColumns=55),lambda c:c[0].update(samples=[{}]),
            lambda c:c[3]['preparation'][-1].update(configured=False),lambda c:c[1].update(passed=False)):
            cases=fixture();mutate(cases)
            with self.assertRaises(ValueError):m.validate(text(cases))
    def test_test_only_atomic_write_and_exact_local_first_mount(self):
        source=(m.ROOT/m.p.TEST).read_text()
        self.assertIn('updatedTerminalRenderPreferences(it.terminalRenderPreferences,peer,mode)',source)
        self.assertNotIn('settings.setTerminalRenderer(peer,mode)',source)
        self.assertIn('settings.settingsFlowRaw.first',source)
        self.assertIn('firstPanelCommitted ||',source)
        self.assertIn('getDeclaredMethod("terminalCommandPreference"',source)
        self.assertLess(source.index('localSettingsReady.await'),source.index('snapshotSetup("before-mount");mounted=true'))
    def test_candidate_and_thresholds_are_unchanged(self):
        m.p.guard()
        self.assertIn("(.80 if r['condition']=='return' else 1.05)",Path(m.p.__file__).read_text())
if __name__=='__main__':unittest.main()
