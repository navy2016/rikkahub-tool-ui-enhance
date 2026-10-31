import importlib.util,json,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('p1_contracts',Path(__file__).with_name('terminal-full-virtual-content.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture():
    sp=importlib.util.spec_from_file_location('p0_fixture',Path(__file__).with_name('test_terminal_full_virtual_mechanism.py'))
    p=importlib.util.module_from_spec(sp);sp.loader.exec_module(p);rows=p.fixture()
    for r in rows:
        r['alternate']=r['scene']!='main';r['fastPath']=r['alternate'] and r['scene'] not in ('selectionOn','mouseOn')
        if r['scene'] in ('insert','delete','font','scale','rtl','selectionOn','selectionOff','mouseOn','mouseOff','main','alternateAgain','resize'):r['definitionChanges']=1
    return p.log(rows).replace('FULL_MECHANISM_','FULL_CONTENT_')+'\n'+'\n'.join('FULL_CONTENT_BOUNDARY '+json.dumps(dict(case=n,passed=True)) for n in ('unownedFallback','sameFrameBlankGrace'))
class ContentContracts(unittest.TestCase):
    def test_candidate_is_single_scoped_source_and_original_unchanged(self):
        s=m.guard();self.assertEqual(1,len(s['files']))
        text=(m.BASE/'candidate.patch').read_text();self.assertEqual(1,text.count('\n+++ b/'))
        self.assertNotIn('fullVirtualWidth',text);self.assertNotIn('rememberTerminalPreparedWidth(',text)
        self.assertIn('panEnabled && !selectionEnabled',text);self.assertIn('rows.frame === frame',text)
        helper=text.split('+private fun terminalFullVirtualPhysicalItems(',1)[1]
        for forbidden in ('frame.','bound.','physicalRows','lazy.','scrollTo'):self.assertNotIn(forbidden,helper)
        self.assertIn('screen[index].lineId',helper);self.assertIn('measurements, false)',helper)
    def test_fixed_probe_and_invalidation_guards(self):
        rows,done,boundaries=m.parse(fixture());self.assertEqual(25,len(rows));self.assertEqual(2,len(boundaries))
        text=fixture().replace('"unchangedItemCommits": 0','"unchangedItemCommits": 2',1)
        with self.assertRaises(ValueError):m.parse(text)
        text=fixture().replace('"fastPath": true','"fastPath": false',1)
        with self.assertRaises(ValueError):m.parse(text)
    def test_old_tests_remain_and_second_build_keeps_overlay(self):
        path='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalFullVirtualInstrumentedTest.kt'
        import subprocess
        self.assertEqual((m.ROOT/path).read_bytes(),subprocess.check_output(['git','show','55db111129c662067345aa99540b506de305da37:'+path],cwd=m.ROOT))
        run=(m.ROOT/'benchmarks/terminal-viewport-tests/run-ci.sh').read_text()
        self.assertIn('extra+=(-PterminalFullVirtualContent=true -PterminalPipelineTests=true)',run)
        test=(m.ROOT/m.TEST).read_text();self.assertIn('f.publish(true,1050L)',test);self.assertIn('assertArrayEquals(expected.second,actual.second)',test)
    def test_probe_receipt_has_both_branch_sites(self):
        code=Path(m.__file__).read_text();self.assertIn("text.count(marker)==2",code)
        self.assertIn("p0.guard()",code);self.assertIn("git','apply','-p5'",code)
if __name__=='__main__':unittest.main()
