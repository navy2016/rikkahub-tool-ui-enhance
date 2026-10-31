import importlib.util,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('inframe_page_contracts',Path(__file__).with_name('terminal-inframe-page-regression.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class InFramePageContracts(unittest.TestCase):
    def test_only_startup_and_classname_changed_in_original_driver(self):
        m.guard();self.assertEqual((m.ROOT/m.ORIGINAL_TEST).read_text(),m.driver_base((m.ROOT/m.TEST).read_text()))
    def test_candidate_is_exact_frozen_uninstrumented_two_file_source(self):
        files=m.candidate.candidate();self.assertEqual(2,len(files));self.assertNotIn(m.candidate.TRACE,files)
        self.assertFalse(m.protocol()['traceProbeOverlay'])
    def test_is_not_performance_or_new_candidate(self):
        p=m.protocol();self.assertEqual(2,len(p['methods']));self.assertFalse(p['performanceMeasured']);self.assertFalse(p['automaticAdoption'])
        source=Path(m.__file__).read_text();self.assertIn('page.validate_page(text)',source);self.assertNotIn('candidate.instrument(',source)
if __name__=='__main__':unittest.main()
