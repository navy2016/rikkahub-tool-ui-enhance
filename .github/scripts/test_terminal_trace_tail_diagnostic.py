import copy,hashlib,importlib.util,json,os,subprocess,unittest
from pathlib import Path
from unittest.mock import patch
s=importlib.util.spec_from_file_location('tail_diagnostic',Path(__file__).with_name('terminal-trace-tail-diagnostic.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

class TailDiagnosticTests(unittest.TestCase):
    def test_exact306_fixed_eight_case_order(self):
        p=m.guard();self.assertEqual(p['baseProductionSha'],'306e0b80b508c961bc00f2d4b34a2688e5857a1a')
        self.assertEqual(p['order'],['baseline','wait','periodic','composite','composite','periodic','wait','baseline'])
        self.assertTrue(all(p['invariants'].values()));self.assertEqual(p['originalV3Run'],41343554975)
    def test_original_recipe_only_adds_settings_atrace_and_periodic_variant(self):
        p=m.protocol();base=(m.BASE/'baseline.pbtxt').read_bytes();periodic=(m.BASE/'periodic.pbtxt').read_bytes()
        self.assertEqual(periodic,base+b'flush_period_ms: 250\n')
        self.assertEqual(hashlib.sha256(base).hexdigest(),p['baselineConfigSha256'])
        self.assertEqual(hashlib.sha256(periodic).hexdigest(),p['periodicConfigSha256'])
    def test_logical_cut_is_not_a_precise_or_terminal_endpoint(self):
        p=m.protocol();self.assertEqual(p['cut']['resolutionNs'],10_000_000)
        self.assertIn('diagnostic not a terminal input endpoint',p['cut']['rule'])
    def test_uptime_bounds_use_decimal_without_float_rounding(self):
        with patch.object(m,'call',return_value=dict(exit=0,stdout='123.45 0.00\n',stderr='')):
            cut=m.boot_cut()
        self.assertEqual(cut['lowerBoundNs'],123450000000);self.assertEqual(cut['upperBoundNs'],123460000000)
        with patch.object(m,'call',return_value=dict(exit=0,stdout='123.456 0.0\n',stderr='')):
            with self.assertRaises(ValueError):m.boot_cut()
    def test_proc_identity_requires_pid_name_and_start_ticks(self):
        good='123 (perfetto) S '+'0 '*18+'987 0'
        self.assertEqual(m.proc_start(good,123),987)
        for stat in (good.replace('perfetto','other'),good.replace('123 (','124 ('),'123 (perfetto) S 0',good.replace('987','0')):
            with self.assertRaises(ValueError):m.proc_start(stat,123)
    def test_no_local_android_execution(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            with self.assertRaisesRegex(ValueError,'CI-only'):m.run()
    def test_shell_program_sent_as_stdin_not_quoted_c_argument(self):
        with patch.object(m,'call',return_value=dict(exit=0,stdout='',stderr='')) as call:
            m.shell('printf test')
        call.assert_called_once_with(['sh'],b'printf test\n')
    def test_checked_does_not_accept_shell_permission_failure(self):
        with self.assertRaisesRegex(ValueError,'Permission denied'):
            m.checked(dict(exit=1,stdout='',stderr='Permission denied'),'native')
    def trace(self):
        surface=dict(cookie=[3,1],kind=4,startNs=10,clock=6,token=11,displayToken=20,pid=42,present=1,isBuffer=1,
            layer='Settings',complete=True,endNs=30)
        display=dict(cookie=[3,2],kind=2,startNs=10,clock=6,token=20,displayToken=20,pid=99,present=1,isBuffer=None,
            layer='',complete=True,endNs=50)
        return dict(pid=42,spans=[dict(tid=42,name='Choreographer#doFrame 11',startNs=10,endNs=20),
            dict(tid=42,name='Choreographer#doFrame 12',startNs=110,endNs=120)],surfaces=[surface],displays=[display],faults=[],openSpans=[])
    def analyze(self,trace):
        with patch.object(m.identity,'parse_trace',return_value=trace),patch.object(m,'trace_wire',return_value={}):
            return m.analyze(b'fixture',42,dict(lowerBoundNs=100,upperBoundNs=110))
    def test_only_pre_cut_ui_token_is_used(self):
        value=self.analyze(self.trace());self.assertEqual(value['tailUI']['token'],11)
        self.assertTrue(value['tailUIEvidenceBeforeCut']);self.assertEqual(value['preCutUIFrames'],1)
        self.assertFalse(value['qualificationPassed']);self.assertFalse(value['performanceMeasured'])
    def test_post_cut_or_uncertain_end_never_completes_old_evidence(self):
        for end in (100,105,110,1000):
            trace=self.trace();trace['displays'][0]['endNs']=end
            self.assertFalse(self.analyze(trace)['tailUIEvidenceBeforeCut'])
    def test_missing_end_remains_missing(self):
        trace=self.trace();trace['surfaces'][0].update(complete=False,endNs=None,error='missing-cookie-end')
        value=self.analyze(trace);self.assertFalse(value['tailUIEvidenceBeforeCut']);self.assertEqual(value['preCutMissingEnds'],1)
    def test_missing_surface_cannot_use_later_token(self):
        trace=self.trace();trace['surfaces'][0].update(token=12,startNs=110)
        value=self.analyze(trace);self.assertFalse(value['tailUIEvidenceBeforeCut']);self.assertEqual(value['postCutSurfaces'],1)
    def test_dropped_or_nonbuffer_or_duplicate_chain_is_not_complete(self):
        for kind in ('dropped','nonbuffer','surface-duplicate','display-duplicate','precedes-surface'):
            trace=self.trace()
            if kind=='dropped':trace['surfaces'][0]['present']=4
            elif kind=='nonbuffer':trace['surfaces'][0]['isBuffer']=0
            elif kind=='surface-duplicate':trace['surfaces'].append(copy.deepcopy(trace['surfaces'][0]))
            elif kind=='display-duplicate':trace['displays'].append(copy.deepcopy(trace['displays'][0]))
            else:trace['displays'][0]['endNs']=29
            self.assertFalse(self.analyze(trace)['tailUIEvidenceBeforeCut'],kind)
    def test_trace_faults_are_retained_not_removed(self):
        trace=self.trace();trace['faults']=['packet-loss'];value=self.analyze(trace)
        self.assertEqual(value['traceFaults'],['packet-loss']);self.assertFalse(value['qualificationPassed'])
        self.assertFalse(value['tailUIEvidenceBeforeCut'])
    def test_failed_cases_and_original_trace_pull_are_preserved(self):
        source=Path(m.__file__).read_text()
        self.assertIn("case['cleanupPull']",source);self.assertIn("save(folder/'receipt.json',case)",source)
        self.assertIn("case['pulses']=pulses",source);self.assertIn('no replacement cases',source)
    def test_no_install_build_root_or_selection_from_results(self):
        source=Path(m.__file__).read_text()
        for token in ('assembleRelease','assembleTerminaltest','adb root','setenforce','pm install','while not passed','continue # retry'):
            self.assertNotIn(token,source)
        p=m.protocol();self.assertEqual(p['variants']['composite']['command'],['service','call','SurfaceFlinger','1004'])
        self.assertEqual(p['variants']['composite']['requiredReply'],'Result: Parcel(NULL)')

if __name__=='__main__':unittest.main()
