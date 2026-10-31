import base64,copy,hashlib,importlib.util,json,unittest,zlib
from pathlib import Path
s=importlib.util.spec_from_file_location('p2_recovery',Path(__file__).with_name('terminal-width-recovery.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def log_cases(cases,cleanup):
    lines=[]
    for c in cases:
        raw=json.dumps(c).encode();data=base64.b64encode(zlib.compress(raw)).decode()
        lines.append('REAL_CLOCK_PART '+json.dumps(dict(case=c['case'],part=1,parts=1,sha256=hashlib.sha256(raw).hexdigest(),data=data)))
    return '\n'.join(lines+['REAL_CLOCK_CLEANUP '+json.dumps(c) for c in cleanup])
def native_fixture():
    cases=[];cleanup=[]
    for size in (1000,10000):
        name=f'widthRecovery-{size}';samples=[];dumps=[];gates=[dict(phase='initial',virtual=True,rows=25,columns=80)]
        for r in range(5):
            for pos,condition in enumerate(m.conditions(r)):
                phase=f'r{r}-{condition}';marker=f'ECHO:{name}:{r:02d}:{pos}'
                raw=(('\x1b[?1049l' if condition=='return' else '')+'\x1b[24;1H\x1b[2K'+marker+'\r').encode()
                samples.append(dict(phase=phase,index=r,position=pos,condition=condition,warmup=r==0,
                    outputBytes=len(raw),outputSha256=hashlib.sha256(raw).hexdigest(),virtual=True,sameInitialHistory=True,
                    visibleAfterMeasurement=True,beganNs=1,uiNs=2,publishedNs=4,drawNs=5,observedNs=6,
                    fedRevision=1,publishedRevision=1,drawRevision=1,events=[
                        dict(seq=1,stage='UI_INPUT',ns=2,bytes=0),dict(seq=2,stage='OUTPUT_READ',ns=3,bytes=len(raw)),
                        dict(seq=3,stage='EMULATOR_FED',ns=3,bytes=len(raw)),dict(seq=4,stage='FRAME_DRAWN',ns=5,bytes=0)]))
                dumps.append(dict(phase=phase));gates.append(dict(phase='prepare-'+phase,virtual=True,rows=25,columns=80))
        cases.append(dict(case=name,passed=True,baselineOnly=True,historySize=size,mode='fullVirtual',
            clock='real-android-no-compose-test-clock',samples=samples,phaseDumps=dumps,gates=gates,
            metrics=[[1,2,3,4,5,6,7,8,9,0]],droppedFrameMetrics=0))
        cleanup.append(dict(case=name,closed=True,threadStopped=True))
    return cases,cleanup
def varint(value):
    out=bytearray()
    while value>127:out.append((value&127)|128);value>>=7
    out.append(value);return bytes(out)
def field(tag,value):
    if isinstance(value,int):return varint(tag<<3)+varint(value)
    if isinstance(value,str):value=value.encode()
    return varint((tag<<3)|2)+varint(len(value))+value
def trace_event(ts,tid,text):return field(2,field(1,ts)+field(2,tid)+field(3,field(2,text)))
class WidthRecoveryContracts(unittest.TestCase):
    def test_baseline_does_not_patch_or_change_dependency_or_production(self):
        m.guard();source=Path(m.__file__).read_text()
        self.assertNotIn('git\',\'apply',source)
        self.assertNotIn('-PterminalFullVirtualContent=true',source)
        self.assertNotIn('-PterminalFullVirtualMechanism=true',source)
        driver=(m.ROOT/m.TEST).read_text()
        for word in ('mainClock','waitForIdle','createAndroidComposeRule','performSemanticsAction'):
            self.assertNotIn(word,driver)
        self.assertIn('assertSame("Unchanged MAIN history was replaced"',driver)
        self.assertIn('SystemClock.sleep(180)',driver)
    def test_native_inventory_bytes_and_history_proof(self):
        c,d=native_fixture();self.assertEqual(2,len(m.validate_cases(log_cases(c,d))[0]))
        for mutate in (lambda x:x[0]['samples'].pop(),lambda x:x[0]['samples'][3].update(sameInitialHistory=False),
            lambda x:x[0]['samples'][3].update(outputBytes=1),lambda x:x[0]['samples'][0].update(warmup=False)):
            changed=copy.deepcopy(c);mutate(changed)
            with self.assertRaises(ValueError):m.validate_cases(log_cases(changed,d))
    def test_width_trace_pairs_actual_worker_thread_and_nested_spans(self):
        events=b''.join((trace_event(10,100,'B|100|Terminal.productionWidth'),trace_event(11,200,'B|100|Terminal.productionWidth'),
            trace_event(12,200,'B|100|nested'),trace_event(13,200,'E'),trace_event(15,300,'B|999|Terminal.productionWidth'),
            trace_event(16,300,'E'),trace_event(18,200,'E|100'),trace_event(20,100,'E')))
        raw=field(1,field(1,field(1,0)+events));rows,open_count=m.width_slices(raw,100)
        self.assertEqual(0,open_count)
        self.assertEqual({(100,10,20),(200,11,18)}, {(r['tid'],r['startNs'],r['endNs']) for r in rows})
    def test_cpu_is_not_wall_and_main_thread_filter_does_not_lose_worker(self):
        case=dict(pid=100,samples=[dict(phase='r1-return',index=1,condition='return',warmup=False,uiNs=10,drawNs=30)],
            phaseDumps=[dict(phase='r1-return',clockBefore=dict(monoNs=0,bootNs=0),clockAfter=dict(monoNs=0,bootNs=0))])
        tr=dict(lostFtraceBundles=0,compactSchedBundles=0,ftraceClocks=[0],switches=[
            (0,0,0,200,0,''),(0,20,200,100,0,''),(0,25,100,0,0,'')])
        widths=[dict(tid=200,startNs=12,endNs=22),dict(tid=100,startNs=21,endNs=27)]
        r=m.attribute(case,tr,widths)[0]
        self.assertEqual(15,r['widthWallUnionNs']);self.assertEqual(8,r['widthWorkerRunningNs']);self.assertEqual(4,r['widthMainRunningNs'])
    def test_open_worker_width_is_not_silently_completed(self):
        raw=field(1,field(1,trace_event(1,200,'B|100|Terminal.productionWidth')))
        self.assertEqual(([],1),m.width_slices(raw,100))
    def test_new_namespace_preserves_manifest_inventory(self):
        before=m.sources();old=m.ci.real_clock
        try:
            import types
            m.ci.real_clock=types.SimpleNamespace(TEST=m.real.TEST)
            self.assertEqual(before,m.sources())
        finally:m.ci.real_clock=old
    def test_mechanism_uses_cumulative_not_last_measurement_count(self):
        text=(m.ROOT/m.MECHANISM).read_text()
        self.assertIn('width.measuredHistoryRows - before',text)
        self.assertNotIn('lastMeasuredHistoryRows',text)
        self.assertEqual(16,len(m.MECHANISM_SCENES))
        wf=(m.ROOT/'.github/workflows/terminal-width-recovery.yml').read_text()
        self.assertLess(wf.index('terminal-width-recovery.py mechanism'),wf.index('terminal-width-recovery.py build'))
if __name__=='__main__':unittest.main()
