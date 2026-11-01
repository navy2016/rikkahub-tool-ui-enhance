import copy,hashlib,importlib.util,json,os,unittest
from pathlib import Path
from unittest.mock import patch
s=importlib.util.spec_from_file_location('finite_tail',Path(__file__).with_name('terminal-trace-finite-tail.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

class FiniteTailTests(unittest.TestCase):
    def test_frozen_eight_finite_cases_and_unchanged306(self):
        p=m.guard();self.assertEqual(p['order'],['baseline','wait','periodic','volume','volume','periodic','wait','baseline'])
        self.assertEqual(p['baseTree'],'ac97798416997a8230c9fa2a5a8569cf0ed0a911');self.assertTrue(all(p['invariants'].values()))
    def test_no_swipe_or_privileged_composite_in_new_driver(self):
        source=Path(m.__file__).read_text();p=m.protocol()
        for value in ("'swipe'","'SurfaceFlinger'","'1004'",'setenforce','assembleRelease','pm install'):
            self.assertNotIn(value,source)
        self.assertEqual(p['stimulus']['command'],['am','start','-W','-a','android.settings.SETTINGS'])
        self.assertEqual(p['variants']['volume']['command'],['input','keyevent','KEYCODE_VOLUME_UP'])
    def test_prior_collector_and_config_remain_byte_identical(self):
        p=m.protocol();self.assertEqual(hashlib.sha256(Path(m.transport.__file__).read_bytes()).hexdigest(),p['transportSourceSha256'])
        for f in ('baseline.pbtxt','periodic.pbtxt'):
            self.assertEqual((m.BASE/f).read_bytes(),(m.ROOT/'benchmarks/terminal-trace-tail-diagnostic'/f).read_bytes())
    def test_native_execution_is_ci_only(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            with self.assertRaisesRegex(ValueError,'CI-only'):m.run()
    def trace(self):
        spans=[]
        for token,start in ((11,100),(12,200),(13,410)):
            spans.extend([dict(tid=42,name=f'Choreographer#doFrame {token}',startNs=start,endNs=start+20),
                dict(tid=43,name=f'DrawFrames {token}',startNs=start+5,endNs=start+40),
                dict(tid=43,name=f'setFrameTimelineInfo(VRI[Settings]#0) frameNumber: {token} vsyncId: {token}',startNs=start+10,endNs=start+11),
                dict(tid=43,name=f'acquireNextBufferLocked - VRI[Settings]#0(f:0,a:1)frame={token}',startNs=start+25,endNs=start+26),
                dict(tid=43,name='Drawing  0 0 10 10',startNs=start+6,endNs=start+39),
                dict(tid=43,name='eglSwapBuffersWithDamageKHR',startNs=start+15,endNs=start+35)])
        surfaces=[dict(cookie=[3,token],kind=4,startNs=start,clock=6,token=token,displayToken=token+100,pid=42,present=1,isBuffer=1,
            layer='Settings',complete=True,endNs=start+40) for token,start in ((11,100),(12,200),(13,410))]
        displays=[dict(cookie=[3,token+100],kind=2,startNs=start,clock=6,token=token+100,displayToken=token+100,pid=99,present=1,isBuffer=None,
            layer='',complete=True,endNs=start+50) for token,start in ((11,100),(12,200),(13,410))]
        return dict(pid=42,spans=spans,surfaces=surfaces,displays=displays,faults=[],openSpans=[])
    def analyze(self,trace):
        with patch.object(m.identity,'parse_trace',return_value=trace),patch.object(m.transport,'trace_wire',return_value={'timelineKindCounts':{4:3},'payloadCounts':{76:3}}):
            return m.analyze(b'fixture',42,dict(upperBoundNs=90),dict(lowerBoundNs=300,upperBoundNs=310),dict(lowerBoundNs=400))
    def test_latest_structural_target_is_from_fixed_selection_not_cut(self):
        value=self.analyze(self.trace());self.assertEqual(value['target']['ui']['token'],12)
        self.assertEqual(len(value['candidates']),2);self.assertTrue(value['targetEvidenceBeforeCut'])
        self.assertFalse(value['qualificationPassed']);self.assertFalse(value['performanceMeasured'])
    def test_removing_latest_timeline_does_not_fall_back_to_older_target(self):
        trace=self.trace();trace['surfaces']=[x for x in trace['surfaces'] if x['token']!=12]
        value=self.analyze(trace);self.assertEqual(value['target']['ui']['token'],12);self.assertFalse(value['targetEvidenceBeforeCut'])
    def test_missing_end_keeps_same_target_and_explicit_missing_evidence(self):
        trace=self.trace();trace['surfaces'][1].update(complete=False,endNs=None,error='missing-cookie-end')
        value=self.analyze(trace);self.assertEqual(value['target']['ui']['token'],12);self.assertFalse(value['targetEvidenceBeforeCut'])
        self.assertEqual(value['selectedIntervalMissingEnds'],1)
    def test_post_cut_or_exact_cut_display_end_cannot_repair_target(self):
        for end in (400,401,900):
            trace=self.trace();trace['displays'][1]['endNs']=end
            self.assertFalse(self.analyze(trace)['targetEvidenceBeforeCut'])
    def test_presentation_between_selection_end_and_cut_is_allowed(self):
        trace=self.trace();trace['displays'][1]['endNs']=350
        self.assertEqual(self.analyze(trace)['target']['ui']['token'],12)
        self.assertTrue(self.analyze(trace)['targetEvidenceBeforeCut'])
    def test_dropped_nonbuffer_duplicate_and_conflicting_end_are_not_complete(self):
        for variant in ('dropped','nonbuffer','duplicate-surface','duplicate-display','preceding-end'):
            trace=self.trace()
            if variant=='dropped':trace['surfaces'][1]['present']=4
            elif variant=='nonbuffer':trace['surfaces'][1]['isBuffer']=0
            elif variant=='duplicate-surface':trace['surfaces'].append(copy.deepcopy(trace['surfaces'][1]))
            elif variant=='duplicate-display':trace['displays'].append(copy.deepcopy(trace['displays'][1]))
            else:trace['surfaces'][1]['endNs']=220
            self.assertFalse(self.analyze(trace)['targetEvidenceBeforeCut'],variant)
    def test_trace_fault_still_invalidates_target_evidence(self):
        trace=self.trace();trace['faults']=['packet-loss'];value=self.analyze(trace)
        self.assertFalse(value['targetEvidenceBeforeCut']);self.assertEqual(value['traceFaults'],['packet-loss'])
    def test_no_buffer_ui_is_recorded_without_claiming_no_submission(self):
        trace=self.trace();trace['spans']=[x for x in trace['spans'] if x['tid']==42]
        value=self.analyze(trace);self.assertIsNone(value['target']);self.assertEqual(len(value['candidates']),2)
        self.assertTrue(all(x['reason']=='RT_TASK_NOT_UNIQUE' for x in value['candidates']))
    def test_structural_buffer_conflicts_are_recorded(self):
        trace=self.trace();target=next(x for x in trace['spans'] if 'frameNumber: 12' in x['name']);target['name']=target['name'].replace('vsyncId: 12','vsyncId: 99')
        value=self.analyze(trace);bad=value['candidates'][1];self.assertFalse(bad['eligible']);self.assertEqual(bad['reason'],'STRUCTURAL_BUFFER_IDENTITY_CONFLICT')
    def test_counter_keys_are_json_stable_at_producer_boundary(self):
        value=self.analyze(self.trace());self.assertEqual(value['timelineKindCounts'],{'4':3});self.assertEqual(value['payloadCounts'],{'76':3})
        self.assertEqual(value,json.loads(json.dumps(value)))
    @staticmethod
    def clock(ns):return dict(clock='CLOCK_BOOTTIME',resolutionNs=10_000_000,lowerBoundNs=ns,upperBoundNs=ns+10_000_000)
    def case(self,variant='wait'):
        result=dict(variant=variant,stimulusBegin=self.clock(1_000_000_000),stimulusReturned=self.clock(2_000_000_000),
            selectionEnd=self.clock(2_270_000_000),observationCut=self.clock(2_790_000_000),stopRequestedAt=self.clock(3_900_000_000))
        if variant=='volume':result['pulse']=dict(before=self.clock(2_850_000_000),result=dict(exit=0,stdout='',stderr=''))
        return result
    def test_bounds_require_separate_selection_observation_and_post_cut_input(self):
        m.validate_bounds(self.case());m.validate_bounds(self.case('volume'))
        for name,time in (('selectionEnd',2_050_000_000),('observationCut',2_600_000_000),('stopRequestedAt',2_790_000_000)):
            c=self.case();c[name]=self.clock(time)
            with self.assertRaises(ValueError):m.validate_bounds(c)
        c=self.case('volume');c['pulse']['before']=self.clock(2_789_000_000)
        with self.assertRaises(ValueError):m.validate_bounds(c)
    def test_empty_selection_is_not_repaired_from_later_ui(self):
        trace=self.trace();trace['spans']=[x for x in trace['spans'] if x['startNs']>=410]
        value=self.analyze(trace);self.assertIsNone(value['target']);self.assertFalse(value['targetEvidenceBeforeCut'])
    def test_failed_cases_and_traces_are_not_retried_or_omitted(self):
        source=Path(m.__file__).read_text()
        for expected in ("case['cleanupPull']","save(folder/'receipt.json',case)",'no replacement cases','no retrospective target change'):
            self.assertIn(expected,source)
    def test_guard_leaves_previous_candidate_and_parser_unmodified(self):
        p=m.guard();self.assertEqual(p['identitySourceSha256'],hashlib.sha256(Path(m.identity.__file__).read_bytes()).hexdigest())

if __name__=='__main__':unittest.main()
