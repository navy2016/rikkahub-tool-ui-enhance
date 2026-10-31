import copy,importlib.util,json,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('identity_tests',Path(__file__).with_name('terminal-frame-identity.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

def span(name,a,b,tid):return dict(name=name,startNs=a,endNs=b,tid=tid)
def trace_fixture():
    return dict(pid=11,clock='CLOCK_BOOTTIME',faults=[],openSpans=[],spans=[
        span('Choreographer#doFrame 7',10,25,11),span('DrawFrames 7',22,40,12),span('syncFrameState',22,24,12),
        span('Drawing  0 0 100 100',25,29,12),span('setFrameTimelineInfo(VRI[TerminalPipelineTestActivity]#0) frameNumber: 3 vsyncId: 7',29,30,12),
        span('eglSwapBuffersWithDamageKHR',30,39,12),span('acquireNextBufferLocked - VRI[TerminalPipelineTestActivity]#0(f:0,a:1)frame=3',33,34,12)],
        surfaces=[dict(pid=11,token=7,displayToken=70,isBuffer=1,present=1,complete=True,startNs=10,endNs=41,clock=6,layer='TX - app/TerminalPipelineTestActivity#1')],
        displays=[dict(token=70,present=1,complete=True,startNs=40,endNs=50,clock=6)])
def metric(token=7):
    raw=[0]*23;raw[1]=token;raw[2]=100;raw[3]=100;raw[8]=102;raw[12]=105
    return dict(raw=raw,publicIntended=100,publicVsync=100)
def event(seq=3,ns=20,revision=5):return dict(seq=seq,ns=ns,revision=revision,stage='FRAME_DRAWN')
def window(events=None):
    return dict(clock='CLOCK_BOOTTIME',fedRevision=5,publishedNs=15,closedBootNs=500000020,
        events=[dict(seq=1,ns=1,revision=-1,stage='UI_INPUT'),dict(seq=2,ns=15,revision=5,stage='FRAME_PUBLISHED')]+(events or [event()]))
def varint(n):
    out=bytearray()
    while n>127:out.append((n&127)|128);n>>=7
    out.append(n);return bytes(out)
def field(k,v):
    if isinstance(v,int):return varint(k<<3)+varint(v)
    if isinstance(v,str):v=v.encode()
    return varint((k<<3)|2)+varint(len(v))+v
def pe(ns,tid,text):return field(2,field(1,ns)+field(2,tid)+field(3,field(2,text)))

class IdentityContractTest(unittest.TestCase):
    def test_frozen_protocol_not_old_performance_gate(self):
        p=m.protocol();self.assertEqual(32,p['matrix']['windows']);self.assertEqual(500000000,p['matrix']['naturalObservationTailNs'])
        self.assertTrue(p['boundaries']['noRetroactiveP2Acceptance']);self.assertEqual('collector-qualification-not-optimization',p['stage'])
    def test_complete_exact_chain_on_same_boot_clock(self):
        r=m.classify_window(window(),m.FrameIndex(trace_fixture()),[[metric()],[metric()]])
        self.assertTrue(r['identityDecidable']);self.assertEqual(19,r['inputToAttemptNs']);self.assertEqual(32,r['inputToBufferEvidenceNs'])
        self.assertEqual(49,r['inputToDisplayEndNs']);self.assertFalse(r['performanceAccepted'])
    def test_absent_metrics_is_not_absent_submission_or_old_gate_pass(self):
        r=m.classify_window(window(),m.FrameIndex(trace_fixture()),[[],[]]);a=r['attempts'][0]
        self.assertEqual('SUBMITTED',a['submission']);self.assertEqual('ABSENT',a['metrics']['state']);self.assertFalse(r['performanceAccepted'])
    def test_sync_only_has_null_endpoints(self):
        t=trace_fixture();t['spans']=t['spans'][:3];t['surfaces']=[];t['displays']=[]
        r=m.classify_window(window(),m.FrameIndex(t),[[],[]])
        self.assertTrue(r['identityDecidable']);self.assertEqual('NO_SUBMISSION',r['attempts'][0]['submission'])
        self.assertIsNone(r['inputToBufferEvidenceNs']);self.assertIsNone(r['inputToDisplayEndNs'])
    def test_later_rt_animation_is_not_borrowed(self):
        t=trace_fixture();t['spans']=t['spans'][:3]
        extra=trace_fixture()
        for s in extra['spans'][1:]:
            s['name']=s['name'].replace('DrawFrames 7','DrawFrames 8').replace('vsyncId: 7','vsyncId: 8');s['startNs']+=100;s['endNs']+=100
        t['spans']+=extra['spans'][1:];t['surfaces'][0]['token']=8
        r=m.classify_window(window(),m.FrameIndex(t),[[],[]]);self.assertEqual('NO_SUBMISSION',r['attempts'][0]['submission']);self.assertTrue(r['noSubmittedAttempt'])
    def test_new_qualified_attempt_keeps_previous_skip(self):
        t=trace_fixture();t['spans']=t['spans'][:3];t['surfaces']=[];t['displays']=[]
        later=trace_fixture()
        for s in later['spans']:
            s['name']=s['name'].replace('doFrame 7','doFrame 8').replace('DrawFrames 7','DrawFrames 8').replace('vsyncId: 7','vsyncId: 8')
            s['startNs']+=100;s['endNs']+=100
        later['surfaces'][0].update(token=8,endNs=141);later['displays'][0]['endNs']=150
        t['spans']+=later['spans'];t['surfaces']+=later['surfaces'];t['displays']+=later['displays']
        r=m.classify_window(window([event(),event(4,120)]),m.FrameIndex(t),[[],[]])
        self.assertEqual(['NO_SUBMISSION','SUBMITTED'],[x['submission'] for x in r['attempts']]);self.assertEqual(4,r['firstSubmittedAttemptEventSeq'])
    def test_other_revision_not_eligible_even_if_it_has_buffer(self):
        t=trace_fixture();w=window([event(3,16,4),event(4,20)])
        r=m.classify_window(w,m.FrameIndex(t),[[],[]]);self.assertEqual('OTHER_REVISION',r['attempts'][0]['attempt']);self.assertEqual(4,r['firstAttemptEventSeq'])
    def test_duplicate_ui_rt_cookie_or_mapping_never_looks_unique(self):
        for kind in ('ui','rt','mapping','surface'):
            t=trace_fixture()
            if kind=='surface':t['surfaces'].append(copy.deepcopy(t['surfaces'][0]))
            else:t['spans'].append(copy.deepcopy(t['spans'][{'ui':0,'rt':1,'mapping':4}[kind]]))
            r=m.classify_window(window(),m.FrameIndex(t),[[],[]]);self.assertFalse(r['identityDecidable']);self.assertEqual('AMBIGUOUS',r['attempts'][0]['submission'])
    def test_wrong_buffer_number_or_queue_fails(self):
        for replacement in ('frame=4','frame=3-bogus'):
            t=trace_fixture();t['spans'][-1]['name']=t['spans'][-1]['name'].replace('frame=3',replacement)
            self.assertFalse(m.classify_window(window(),m.FrameIndex(t),[[],[]])['identityDecidable'])
    def test_lost_trace_is_unknown_not_skip(self):
        t=trace_fixture();t['faults']=['lost-ftrace-bundle']
        r=m.classify_window(window(),m.FrameIndex(t),[[],[]]);self.assertFalse(r['identityDecidable']);self.assertEqual('UNKNOWN',r['attempts'][0]['submission'])
    def test_metrics_disagreement_or_duplicate_is_conflict(self):
        bad=metric();bad['publicVsync']=99
        self.assertEqual('CONFLICT',m.metrics_state(7,[[bad],[metric()]])['state'])
        self.assertEqual('CONFLICT',m.metrics_state(7,[[metric(),metric()],[]])['state'])
        other=metric();other['raw'][8]=200
        self.assertEqual('CONFLICT',m.metrics_state(7,[[metric()],[other]])['state'])
    def test_present_drop_unknown_and_missing_end_preserved(self):
        for kind,expected in (('drop','DROPPED'),('unknown','UNOBSERVED'),('missing','UNOBSERVED')):
            t=trace_fixture()
            if kind=='missing':t['displays'][0]['complete']=False
            else:t['surfaces'][0]['present']=4 if kind=='drop' else 5
            r=m.classify_window(window(),m.FrameIndex(t),[[],[]]);self.assertEqual(expected,r['attempts'][0]['presentation']);self.assertIsNone(r['inputToDisplayEndNs'])
    def test_after_cutoff_not_selected(self):
        t=trace_fixture();t['displays'][0]['endNs']=600000000
        r=m.classify_window(window(),m.FrameIndex(t),[[],[]]);self.assertIsNone(r['inputToDisplayEndNs']);self.assertFalse(r['noSubmittedAttempt'])
    def test_clock_and_tail_contracts_not_guessed(self):
        for change in (dict(clock='MONOTONIC'),dict(closedBootNs=499999999),dict(closedBootNs=500000021)):
            w=window();w.update(change)
            with self.assertRaises(ValueError):m.classify_window(w,m.FrameIndex(trace_fixture()),[[],[]])
    def test_pb_nested_frame_spans_on_own_tid(self):
        raw=field(1,field(1,field(1,0)+pe(10,11,'B|11|Choreographer#doFrame 7')+pe(20,11,'E')))
        t=m.parse_trace(raw,11);self.assertEqual([],t['faults']);self.assertEqual(1,len(t['spans']));self.assertEqual(20,t['spans'][0]['endNs'])
    def test_pb_lost_bad_clock_and_open_relevant_span(self):
        raw=field(1,field(1,field(1,0)+field(3,1)+field(5,2)+pe(10,11,'B|11|DrawFrames 7')))
        t=m.parse_trace(raw,11)
        for fault in ('lost-ftrace-bundle','ftrace-clock-not-boot','unclosed-relevant-frame-span'):self.assertIn(fault,t['faults'])
    def test_pb_cookie_duplicates_not_overwritten(self):
        start=field(1,9)+field(2,7)+field(3,70)+field(4,11)+field(5,'x')+field(6,1)+field(11,1)
        a=field(1,field(8,10)+field(10,1)+field(76,field(4,start)))
        end=field(1,field(8,50)+field(10,1)+field(76,field(5,field(1,9))))
        t=m.parse_trace(a+a+end,11);self.assertEqual(2,len(t['surfaces']));self.assertTrue(all(x['error']=='duplicate-cookie' for x in t['surfaces']))
if __name__=='__main__':unittest.main()
