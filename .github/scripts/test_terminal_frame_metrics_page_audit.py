import importlib.util,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('dual_metrics_contracts',Path(__file__).with_name('terminal-frame-metrics-page-audit.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture():
    raw=[0]*23;raw[1]=7;raw[2]=100;raw[3]=100
    metric=[105,100,100,1,2,3,4,5,6,0]
    return dict(pid=1,samples=[dict(phase='r0-refresh',index=0,condition='refresh',drawNs=103)],
        phaseDumps=[dict(phase='r0-refresh',gfxinfo='Window: test\nFlags,FrameTimelineVsyncId,Vsync,DrawStart,SyncQueued,\n0,7,100,102,104,')],
        metrics=[metric],metricsRaw=[raw],mirrorMetrics=[metric.copy()],mirrorRaw=[raw.copy()])
class DualMetricsContracts(unittest.TestCase):
    def test_independent_queues_distinguish_upstream_gap_from_one_listener(self):
        c=fixture();tr=dict(surfaces=[],displays=[])
        self.assertEqual('both-observers-exact',m.classify(c,tr)[0]['category'])
        c['mirrorMetrics']=[];c['mirrorRaw']=[]
        self.assertEqual('primary-only-exact',m.classify(c,tr)[0]['category'])
        c['metrics']=[];c['metricsRaw']=[]
        self.assertEqual('both-observers-no-metric',m.classify(c,tr)[0]['category'])
    def test_previous_vsync_never_substituted(self):
        c=fixture();c['metrics'][0][2]=90;c['metricsRaw'][0][1]=6;c['mirrorMetrics'][0][2]=90;c['mirrorRaw'][0][1]=6
        self.assertEqual('both-observers-no-metric',m.classify(c,dict(surfaces=[],displays=[]))[0]['category'])
    def test_driver_pins_fixed_inventory_and_fences_before_writing(self):
        m.guard();source=(m.ROOT/m.TEST).read_text()
        self.assertEqual(2,source.count('@Test fun '));self.assertIn('for(round in 0..19)',source)
        self.assertLess(source.index('val startup = startupReceipt()'),source.index('settings.update{'))
        self.assertIn('mirrorThread=HandlerThread',source);self.assertIn('mirror.readRawMetrics()',source)
        self.assertIn('performanceMeasured",false',source)
        for token in ('mainClock','createAndroidComposeRule','Thread.sleep(1000)'):self.assertNotIn(token,source)
if __name__=='__main__':unittest.main()
