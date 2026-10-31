import importlib.util,json,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('alt_contracts',Path(__file__).with_name('terminal-alt-width.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class AlternateWidthContracts(unittest.TestCase):
    def test_production_still_frozen_and_patch_only_three_files(self):
        s=m.guard();self.assertEqual(3,len(s['files']))
        patch=m.PATCH.read_text();self.assertEqual(3,patch.count('\n+++ b/'))
        self.assertNotIn('ProcessSessionPage.kt',patch)
        self.assertIn('fullVirtual = true',patch)
        self.assertIn('widthInternal(frame, metricKey, false, measure)',patch)
        self.assertIn('alt.owner === oldAlt.owner',patch)
        self.assertIn('alt.revision >= oldAlt.revision',patch)
    def test_measured_driver_uses_real_clock_and_exact_finite_response(self):
        code=(m.ROOT/m.PAGE).read_text()
        for forbidden in ('mainClock','waitForIdle','performSemanticsAction','createComposeRule','createAndroidComposeRule'):
            self.assertNotIn(forbidden,code)
        self.assertIn('ACTION_IME_ENTER',code);self.assertIn('p.historyRows==0',code)
        self.assertIn('p.alternate',code);self.assertIn('post-draw native visibility',code)
        self.assertIn('\\u001b[24;1H\\u001b[2K$expected\\r',code)
        self.assertEqual(1,code.count('@Test fun '))
    def test_fixed_pair_order_and_shared_driver_guards(self):
        self.assertEqual([('legacy','candidate'),('candidate','legacy'),('legacy','candidate'),('candidate','legacy')],[m.pair.order(i) for i in range(m.PAIRS)])
        code=Path(m.__file__).read_text()
        self.assertIn('Shared driver APK changed',code)
        self.assertIn('Not all36system-frame matches valid',code)
        self.assertIn('Build overlay not restored',code)
    def test_native_font_oracle_is_separate_and_legacy_unit_file_unchanged(self):
        font=(m.ROOT/m.FONT).read_text();unit=(m.BASE/'TerminalAlternateWidthCandidateTest.kt').read_text()
        self.assertIn('assertEquals(42,checks)',font);self.assertEqual(6,unit.count('@Test fun '))
        self.assertIn('font failure',unit);self.assertIn('acceptComputed',unit)
        p='app/src/test/java/me/rerere/rikkahub/data/container/TerminalTranscriptWidthIndexTest.kt'
        self.assertEqual(m.git('show','55db111129c662067345aa99540b506de305da37:'+p),(m.ROOT/p).read_bytes())
if __name__=='__main__':unittest.main()
