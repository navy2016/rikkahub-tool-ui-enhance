import importlib.util,json,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('p2_candidate_contracts',Path(__file__).with_name('terminal-width-recovery-candidate.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class RecoveryCandidateContracts(unittest.TestCase):
    def test_three_file_scope_preserves_alt_width_and_original_content_definition(self):
        spec=m.guard();self.assertEqual(3,len(spec['files']))
        patch=(m.BASE/'candidate.patch').read_text()
        self.assertNotIn('terminalFullVirtualPhysicalItems',patch)
        self.assertNotIn('fullVirtualWidth(',patch)
        self.assertIn('preserveMainAcrossAlternate: Boolean = false',patch)
        self.assertIn('preserveMainAcrossAlternate = true',patch)
        self.assertIn('it.proof == proof',patch)
        self.assertIn('it.source.owner === owned.history.owner',patch)
    def test_parked_checkpoint_not_copied_into_worker_or_unbounded_history_chain(self):
        patch=(m.BASE/'candidate.patch').read_text()
        self.assertIn('private var parkedMainHistory: Checkpoint? = null',patch)
        self.assertNotIn('it.parkedMainHistory',patch)
        self.assertIn('fullVirtualRestoreVersion',patch)
        self.assertIn('DisposableEffect(index)',patch)
    def test_invalidation_unit_inventory_covers_failure_fork_and_lease(self):
        source=(m.BASE/'TerminalWidthRecoveryCandidateTest.kt').read_text()
        self.assertEqual(8,source.count('@Test fun '))
        for key in ('unknown-font','render-style','synthetic','main-only-frame','screen font failure','acceptComputed','store.save(old,null)'):
            self.assertIn(key,source)
    def test_fixture_keeps_source_compatible_and_tests_disposal_in_alt(self):
        source=(m.ROOT/m.TEST).read_text()
        self.assertIn('getMethod("getRetainedParkedHistoryCandidates")',source)
        self.assertIn('assertTrue(heldBeforeDispose in 1..size)',source)
        self.assertIn('assertEquals(0, parked())',source)
        self.assertIn('step("returnFont", size.toLong())',source)
        self.assertIn('step("returnSame$round", 0)',source)
    def test_workflows_do_not_reuse_baseline_data_as_candidate_results(self):
        source=Path(m.__file__).read_text()
        self.assertIn('finally:',source);self.assertIn('(ROOT/path).write_bytes(raw)',source)
        self.assertIn("len(cases)==45",source)
        self.assertIn("len(found)==7",source)
        run=(m.ROOT/'benchmarks/terminal-viewport-tests/run-ci.sh').read_text()
        self.assertIn('extra+=(-PterminalWidthRecoveryCandidate=true -PterminalPipelineTests=true)',run)
if __name__=='__main__':unittest.main()
