import importlib.util,re,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('final_inframe_tests',Path(__file__).with_name('terminal-inframe-final-regression.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class FinalRegressionContracts(unittest.TestCase):
    def test_original_and_added_inventories_are_exact(self):
        p=m.guard();self.assertEqual(492,sum(len(x['methods']) for x in p['unitInventory']));self.assertEqual(504,p['jvmMethods'])
        self.assertEqual(182,sum(x['count'] for g in p['viewportGroups'].values() for x in g));self.assertNotIn('fullVirtual',p['viewportGroups'])
        self.assertEqual(9,sum(x['count'] for x in p['viewportGroups']['selectionRepair']))
    def test_single_source_contract_changed_no_original_method_removed(self):
        path='app/src/test/java/me/rerere/rikkahub/data/container/TerminalSmoothnessSourceTest.kt'
        old=m.candidate.git('show','55db111129c662067345aa99540b506de305da37:'+path).decode();new=(m.ROOT/path).read_text()
        methods=lambda text:re.findall(r'@Test\s+fun\s+(\w+)\(',text)
        self.assertEqual(methods(old),methods(new))
        self.assertEqual(old.count('assertTrue(processSessionSource.contains("withFrameNanos { }"))'),1)
        self.assertIn('assertEquals("publicationContext.isActive && latestTryPublishInFrame()", callback)',new)
        self.assertIn('assertEquals(1, Regex(',new);self.assertIn('snapshotWritable = !Snapshot.current.readOnly',new)
    def test_fixture_is_not_falsely_claimed_to_include_page_scheduler(self):
        source=Path(m.__file__).read_text();self.assertIn('pageSchedulerNotInFixture=True',source);self.assertIn('not set(files)&set(copied)',source)
    def test_release_jvm_invocation_does_not_enable_terminaltest(self):
        source=Path(m.__file__).read_text();self.assertIn("'-PterminalReleaseTests=true',':app:testReleaseUnitTest'",source)
        unit=source.split('def unit():',1)[1].split('def fixture_build():',1)[0];self.assertNotIn('-PterminalPipelineTests=true',unit)
    def test_no_hidden_retry_or_adoption_in_final_runner(self):
        p=m.plan();self.assertTrue(p['noAutomaticRetry']);self.assertTrue(p['noAutomaticProductionMerge']);self.assertEqual(41253490506,p['performanceRun'])
    def test_final_workflow_preserves_original_seven_shards_and_reports_once(self):
        import yaml
        workflow=yaml.safe_load((m.ROOT/'.github/workflows/terminal-inframe-final-regression.yml').read_text())
        self.assertEqual(set(m.plan()['viewportGroups']),set(workflow['jobs']['viewport']['strategy']['matrix']['group']))
        self.assertFalse(workflow['jobs']['viewport']['strategy']['fail-fast'])
        source=Path(m.__file__).read_text();self.assertNotIn('report_viewport(group)',source.split('def viewport(group):',1)[1].split('def report_viewport(group):',1)[0])
if __name__=='__main__':unittest.main()
