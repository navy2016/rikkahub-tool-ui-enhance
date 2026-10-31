import importlib.util,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('identity_qualification_tests',Path(__file__).with_name('terminal-frame-identity-qualification.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class QualificationContracts(unittest.TestCase):
    def test_protocol_preregistered_and_production_unmodified(self):
        p=m.guard();self.assertEqual('65c42cae1292ef4bcd16b83ba5159650c071a495',m.FREEZE_SHA)
        self.assertEqual(32,p['matrix']['windows']);self.assertEqual(4,len(m.METHODS))
    def test_pipeline_clock_injected_not_just_driver_timestamp_renamed(self):
        source=(m.ROOT/m.TEST).read_text()
        self.assertIn('TerminalPipelineTrace.start(id,4096, clock=SystemClock::elapsedRealtimeNanos)',source)
        self.assertNotIn('System.nanoTime()',source)
        self.assertIn('val closed=tailEnd',source)
        self.assertIn('val events=trace.snapshot().filter{it.sequence>before}',source)
        self.assertIn('.put("virtual",e.virtual ?: JSONObject.NULL)',source)
    def test_no_qualification_candidate_or_performance_acceptance(self):
        source=Path(m.__file__).read_text()
        self.assertNotIn("'git','apply'",source)
        self.assertIn('performanceAccepted=False,optimizationMeasured=False',source)
        self.assertIn('require(qualified,',source)
        self.assertIn("save(OUT/'report.json',value)",source)
    def test_startup_barrier_and_no_artificial_tail_redraw(self):
        source=(m.ROOT/m.TEST).read_text()
        self.assertLess(source.index('val startup = startupReceipt()'),source.index('settings.update{'))
        start=source.index('val payload=output.toByteArray();val before=trace.beginWindow()')
        window=source[source.index('val began=SystemClock.elapsedRealtimeNanos()',start):source.index('// Queries and history identity checks',start)]
        self.assertNotIn('requestViewportDraw',window);self.assertNotIn('registerFrameCommitCallback',source)
        self.assertNotIn('visibleText(',window);self.assertIn('for(round in 0..3)',source)
    def test_manifest_inventory_stable_under_reporter_swap(self):
        import types
        before=m.sources();old=m.ci.real_clock
        try:
            m.ci.real_clock=types.SimpleNamespace(TEST=m.real.TEST)
            self.assertEqual(before,m.sources())
        finally:m.ci.real_clock=old
if __name__=='__main__':unittest.main()
