import importlib.util,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('publication_wait_contracts',Path(__file__).with_name('terminal-publication-wait.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class PublicationWaitContracts(unittest.TestCase):
    def test_trace_overlay_roundtrips_to_exact_production(self):
        before,after=m.overlay();self.assertEqual({m.PAGE,m.TRACE,m.MANAGER},set(after))
        for name in before:self.assertEqual(before[name],m.strip_probe(name,after[name]))
    def test_original_waits_and_atomic_transaction_are_untouched(self):
        before,after=m.overlay()
        for token in ('while (renderPending.getAndSet(false))','awaitTerminalSynchronizedOutput(terminalOutputUpdates, TERMINAL_SYNC_OUTPUT_MAX_WAIT_MS)',
            'delay(TERMINAL_GRID_CLEAR_GRACE_MS)','Snapshot.withMutableSnapshot','renderPending.set(false) // Capture includes all notifications'):
            self.assertEqual(before[m.PAGE].count(token),after[m.PAGE].count(token))
        self.assertNotIn('CoroutineStart.UNDISPATCHED',after[m.PAGE])
        self.assertNotIn('preserveMainAcrossAlternate',after[m.PAGE])
    def test_callback_and_continuation_markers_are_different(self):
        _,after=m.overlay();p=after[m.PAGE]
        self.assertLess(p.index('TerminalPipelineStage.PUBLICATION_FRAME_WAIT_START'),p.index('TerminalPipelineStage.PUBLICATION_FRAME_CALLBACK'))
        self.assertLess(p.index('TerminalPipelineStage.PUBLICATION_FRAME_CALLBACK'),p.index('TerminalPipelineStage.PUBLICATION_FRAME_WAIT_END'))
    def test_does_not_add_revision_or_foreign_clock_reads(self):
        before,after=m.overlay()
        for name in before:
            for token in ('terminalEmulator.revision()', 'System.nanoTime()', 'System.currentTimeMillis()', 'withFrameNanos'):
                self.assertEqual(before[name].count(token),after[name].count(token))
    def test_fixed_control_scope_is_not_performance_gate(self):
        p=m.protocol();self.assertEqual(32,p['matrix']['windows']);self.assertEqual(60,p['matrix']['syncPeerHoldMs'])
        self.assertTrue(p['integrity']['noPerformanceAcceptance']);self.assertEqual('diagnosis-not-optimization',p['stage'])
if __name__=='__main__':unittest.main()
