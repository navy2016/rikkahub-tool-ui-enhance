import importlib.util,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('publication_wait_contracts',Path(__file__).with_name('terminal-publication-wait.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def sample():
    names=['UI_INPUT','EMULATOR_FED','UI_OUTPUT_RECEIVED','PUBLICATION_REQUEST','PUBLICATION_ENQUEUED','PUBLICATION_JOB_START',
        'PUBLICATION_CYCLE_START','PUBLICATION_FRAME_WAIT_START','PUBLICATION_FRAME_CALLBACK','PUBLICATION_FRAME_WAIT_END',
        'PUBLICATION_SYNC_CHECK','PUBLICATION_SYNC_DONE','PUBLICATION_RESIZE_CHECK','PUBLICATION_RESIZE_DONE','PUBLICATION_CALL',
        'FRAME_CAPTURE_STARTED','PUBLICATION_MODEL_LOCK_ACQUIRED','FRAME_CAPTURED','ROW_SYNC_STARTED','ROW_SYNC_FINISHED','FRAME_PUBLISHED',
        'PUBLICATION_RETURN','PUBLICATION_JOB_END']
    flags={'PUBLICATION_SYNC_CHECK':False,'PUBLICATION_SYNC_DONE':True,'PUBLICATION_RESIZE_CHECK':False,'PUBLICATION_CALL':False,'PUBLICATION_RETURN':True}
    return dict(fedRevision=7,prefixEvents=[],events=[dict(seq=i+1,ns=(i+1)*100,stage=name,revision=7,flag=flags.get(name),bytes=0) for i,name in enumerate(names)])
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
    def test_ordered_job_and_cycle_measure_distinct_segments(self):
        r=m.chain(sample());self.assertEqual(100,r['registeredFrameWaitNs']);self.assertEqual(100,r['callbackToContinuationNs'])
        self.assertEqual(100,r['enqueueToJobNs']);self.assertFalse(r['syncActiveAtCheck'])
    def test_missing_or_reordered_marker_is_not_guessed(self):
        for name in ('PUBLICATION_FRAME_CALLBACK','PUBLICATION_MODEL_LOCK_ACQUIRED','PUBLICATION_ENQUEUED'):
            s=sample();s['events']=[e for e in s['events'] if e['stage']!=name]
            with self.assertRaises(ValueError):m.chain(s)
    def test_coalesced_request_does_not_become_new_job(self):
        s=sample();events=s['events'];where=next(i for i,e in enumerate(events) if e['stage']=='PUBLICATION_SYNC_DONE')
        events[where:where]=[dict(stage='PUBLICATION_REQUEST',revision=7,bytes=0,flag=None),dict(stage='PUBLICATION_COALESCED',revision=7,bytes=0,flag=None)]
        for i,e in enumerate(events):e.update(seq=i+1,ns=(i+1)*100)
        r=m.chain(s);self.assertEqual(2,len(r['requests']));self.assertEqual(1,sum(x['coalesced'] for x in r['requests']));self.assertEqual(6,r['jobStartSeq'])
    def test_driver_does_not_touch_production_clock_or_force_draw_in_window(self):
        source=(m.ROOT/m.TEST).read_text()
        self.assertIn('clock=SystemClock::elapsedRealtimeNanos',source);self.assertIn('sleep 0.060',source)
        self.assertIn('val closed=tailEnd',source);self.assertIn('.put("flag",e.virtual ?: JSONObject.NULL)',source)
        start=source.index('val before=trace.beginWindow()');end=source.index('// Queries and history identity checks',start)
        self.assertNotIn('requestViewportDraw',source[start:end]);self.assertNotIn('condition=="return"',source)
if __name__=='__main__':unittest.main()
