import importlib.util,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('inframe_contracts',Path(__file__).with_name('terminal-inframe-publication.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class InFrameSourceContracts(unittest.TestCase):
    def test_candidate_and_probe_both_roundtrip(self):
        c=m.candidate();p=m.instrument(c)
        self.assertEqual((m.ROOT/m.PAGE).read_text(),m.strip_candidate(c[m.PAGE]))
        self.assertEqual(c[m.PAGE],m.strip_probe(m.PAGE,p[m.PAGE]))
        self.assertEqual((m.ROOT/m.TRACE).read_text(),m.strip_probe(m.TRACE,p[m.TRACE]))
    def test_original_publisher_is_byte_identical(self):
        base=(m.ROOT/m.PAGE).read_text();candidate=m.candidate()[m.PAGE]
        def block(text):return text[text.index('    fun renderTerminalFrame('):text.index('    val latestPublishTerminalFrame')]
        self.assertEqual(block(base),block(candidate))
    def test_pending_is_not_cleared_after_a_successful_callback(self):
        page=m.candidate()[m.PAGE]
        self.assertIn('if (!publishedInFrame) {',page)
        self.assertIn('publicationContext.isActive && latestTryPublishInFrame()',page)
        helper=m.candidate()[m.HELPER];self.assertLess(helper.index('pending.set(false)'),helper.index('val completed = publish()'))
        self.assertNotIn('pending.set(false)',helper.split('val completed = publish()',1)[1])
    def test_mount_lifetime_and_main_thread_are_explicit(self):
        page=m.candidate()[m.PAGE]
        self.assertIn('val publicationAttached = remember(processId) { AtomicBoolean(false) }',page)
        self.assertIn('publicationAttached.set(true)',page);self.assertIn('publicationAttached.set(false)',page)
        self.assertIn('android.os.Looper.myLooper() === android.os.Looper.getMainLooper()',page)
    def test_only_main_page_and_helper_are_candidate_not_trace_vocabulary(self):
        self.assertEqual({m.PAGE,m.HELPER},set(m.spec()['files']))
        self.assertIsNone(m.spec()['files'][m.HELPER]['base']);self.assertFalse((m.ROOT/m.HELPER).exists())
        self.assertEqual(49,m.protocol()['jvm']['totalMethods'])
if __name__=='__main__':unittest.main()
