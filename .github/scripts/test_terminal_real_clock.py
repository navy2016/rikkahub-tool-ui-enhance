import base64,copy,hashlib,importlib.util,json,zlib
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('real_clock_contract',Path(__file__).with_name('terminal-real-clock.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
def fixture():
    cases=[];cleanup=[]
    for c in m.CASES:
        samples=[]
        for p in m.PHASES:
            for i in range(12):
                output=f'ECHO:{c}:{p}:{i:02d}\r\n'.encode()
                samples.append(dict(phase=p,index=i,warmup=i<2,virtual=c.startswith('fullVirtual'),beganNs=1,uiNs=2,drawNs=5,observedNs=6,
                    fedRevision=1,publishedRevision=1,drawRevision=1,outputBytes=len(output),outputSha256=hashlib.sha256(output).hexdigest(),
                    events=[dict(seq=1,stage='UI_INPUT',ns=2,bytes=0),dict(seq=2,stage='OUTPUT_READ',ns=3,bytes=len(output)),
                            dict(seq=3,stage='EMULATOR_FED',ns=4,bytes=len(output)),dict(seq=4,stage='FRAME_DRAWN',ns=5,bytes=0,revision=1)]))
        cases.append(dict(case=c,mode=c.split('-')[0],tui=c.endswith('-tui'),clock='real-android-no-compose-test-clock',passed=True,pid=123,
            samples=samples,gates=[dict(phase=p,rows=25,columns=80) for p in m.PHASES],metrics=[[1,2,3,4,5,6,7,8,9,0]],droppedFrameMetrics=0,phaseDumps=[dict(phase=p) for p in m.PHASES]))
        cleanup.append(dict(case=c,closed=True,threadStopped=True))
    return cases,cleanup
def text(cases,cleanup):
    fragments=[]
    for case in cases:
        raw=json.dumps(case).encode();parts=base64.b64encode(zlib.compress(raw)).decode();chunks=[parts[i:i+2600] for i in range(0,len(parts),2600)]
        fragments.extend('REAL_CLOCK_PART '+json.dumps(dict(case=case['case'],part=i+1,parts=len(chunks),sha256=hashlib.sha256(raw).hexdigest(),data=chunk)) for i,chunk in enumerate(chunks))
    return '\n'.join(fragments+['REAL_CLOCK_CLEANUP '+json.dumps(c) for c in cleanup])
class RealClockContracts(unittest.TestCase):
    def test_complete_matrix_and_wrong_bytes_missing_cleanup(self):
        cases,cleanup=fixture();r=m.validate(text(cases,cleanup));self.assertEqual(144,sum(len(c['samples']) for c in r['cases']))
        for mutate in (lambda c,d:c[0]['samples'].pop(),lambda c,d:c[0]['samples'][0].update(virtual=True),lambda c,d:c[0]['samples'][0].update(outputBytes=1),lambda c,d:d.pop()):
            c,d=fixture();mutate(c,d)
            with self.assertRaises(ValueError):m.validate(text(c,d))
    def test_gfxinfo_requires_named_vsync_id(self):
        rows=m.gfx_frames('Window: app\nFlags,IntendedVsync,Vsync,FrameTimelineVsyncId,FrameCompleted,\n0,1,2,3,4,\n')
        self.assertEqual(3,rows[0]['FrameTimelineVsyncId'])
        self.assertEqual([],m.gfx_frames('Flags,Vsync,FrameCompleted\n0,2,4'))
    def test_ambiguous_matching_never_guesses_presentation(self):
        case=dict(pid=1,phaseDumps=[dict(phase='hidden')],samples=[dict(phase='hidden',index=0,warmup=False,drawNs=3000000,uiNs=1000000,publishedNs=2000000,
            events=[dict(stage='EMULATOR_FED',ns=1500000)])],draws=[dict(ns=2100000,drawingTimeMs=2)],metrics=[[0,0,2000000,1,1,1,1],[0,0,2100000,1,1,1,1]])
        trace=dict(switches=[])
        row=m.correlate(case,trace)[0];self.assertEqual('ambiguous-window-vsync',row['match']);self.assertNotIn('inputToDisplayEndNs',row)
    def test_driver_has_no_compose_clock_or_idle(self):
        self.assertEqual(m.TREE,m.guard()['productionTree'])
        source=(m.ROOT/m.TEST).read_text();self.assertIn('ActivityScenario.launch',source)
        self.assertIn('ACTION_IME_ENTER',source);self.assertIn('OnDrawListener',source)
        self.assertIn('observer.windowFinishedAfter(view.timeNanos)',source)
        self.assertIn('No eligible FRAME_DRAWN for completed response',source)
        self.assertNotIn('Thread.sleep',source)
    def test_exact_token_join_and_cpu_interval_then_reject_duplicate_surface(self):
        s=dict(phase='hidden',index=0,warmup=False,drawNs=3_000_000,uiNs=1_000_000,publishedNs=2_000_000,events=[dict(stage='EMULATOR_FED',ns=1_500_000)])
        case=dict(pid=1,samples=[s],draws=[dict(ns=2_100_000,drawingTimeMs=2)],metrics=[[0,0,2_000_000,1,1,1,1,1,1,0]],
            phaseDumps=[dict(phase='hidden',gfxinfo='Flags,Vsync,FrameTimelineVsyncId,FrameCompleted,\n0,2000000,42,5000000,',
                clockBefore=dict(monoNs=0,bootNs=500_000_000),clockAfter=dict(monoNs=1_000_000_000,bootNs=1_500_000_000))])
        sf=dict(pid=1,token=42,displayToken=99,clock=6,present=2)
        trace=dict(switches=[(0,502_000_000,0,1,0,'main'),(0,503_000_000,1,0,0,'idle')],surfaces=[sf],
            displays=[dict(token=99,clock=6,present=2,endNs=505_000_000)],lostFtraceBundles=0,ftraceClocks=[0],compactSchedBundles=0)
        row=m.correlate(case,trace)[0];self.assertEqual('exact-token',row['match']);self.assertEqual(4_000_000,row['inputToDisplayEndNs'])
        self.assertEqual(1_000_000,row['mainRunningPublishToDrawNs'])
        trace['surfaces'].append(dict(sf));row=m.correlate(case,trace)[0]
        self.assertEqual('missing-or-ambiguous-SurfaceFrame',row['match']);self.assertNotIn('inputToDisplayEndNs',row)
if __name__=='__main__':unittest.main()
