"""Host synthetic failure contracts only. Never Android acceptance or fabricated evidence."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
import re
import zlib

spec=importlib.util.spec_from_file_location('selection_repair',Path(__file__).with_name('terminal-selection-repair-reports.py'))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)


def fixture():
    parts=[]
    for name in gate.CASES:
        result=dict(mode=name.split('-',1)[1],historyRows=10000,textSha256='a'*64,finished=True,
            virtual=name.endswith('fullVirtual'),retainedRows=0,pointerRows=0,
            wordMatches=False,copyMenu=True,handleFound=True,startRecycled=False)
        events=[dict(stage='driver-finished',sequence=0,timeNs=1,moves=220,events=222,frames=222),
                dict(stage='disposed',sequence=1,timeNs=2)]
        v=dict(case=name,suite='terminal-selection-diagnostic-v1',acceptance=False,overflow=0,events=events,result=result,
            pid=1,fingerprint='synthetic-contract',targetApk='b'*64,testApk='c'*64)
        raw=json.dumps(v).encode();parts.append(dict(case=name,part=1,parts=1,sha256=hashlib.sha256(raw).hexdigest(),data=base64.b64encode(zlib.compress(raw)).decode()))
    return parts


def encode(parts):return '\n'.join('SELECTION_DIAGNOSTIC_PART '+json.dumps(v) for v in parts)


def repair_fixture():
    from test_terminal_full_virtual import viewport
    groups=viewport()
    groups=[(marker,items) for marker,items in groups if marker not in ('FULL_VIRTUAL_PERF','FULL_VIRTUAL_DISPOSE','FULL_VIRTUAL_DEVICE')]
    groups[0]=(groups[0][0],[v for v in groups[0][1] if v['case']!='performance'])
    groups += [('FULL_VIRTUAL_HANDLE_FRAMES',[dict(moves=220,events=222,frames=222,screenCoordinates=True)]),
               ('FULL_VIRTUAL_SELECTION_DEVICE',[dict(case=case,pid=3,fingerprint='synthetic',targetApk='b'*64,testApk='c'*64) for case in ('copy','drag')]),
               ('SELECTION_REPAIR_CASE',[dict(case=case,passed=True) for case in ('stationary','reverse','scenes')])]
    phases=('stationary-top','stationary-bottom','reverse-ltr','reverse-rtl','ordinary','tui','ime','mouse','fullGrid','alternate','main','handoff','reentry')
    rows=[dict(phase=p,virtual=True,selection=True,pan=p!='mouse',ime=p=='ime',alternate=p=='alternate',tui=p=='tui',fullGrid=p=='fullGrid',
        historyRows=0 if p=='alternate' else 10000,rowStates=24,measuredRows=12,writers=1,length=9,sha256='e'*64,
        top=0,bottom=29,rowHeight=29,viewport=319) for p in phases]
    groups.append(('SELECTION_REPAIR_ROW',rows))
    groups.append(('SELECTION_REPAIR_MENU',[dict(avoidsHandles=True,handles=2,expectedLength=9) for _ in phases]))
    return groups


def repair_encode(groups):return '\n'.join(marker+' '+json.dumps(r) for marker,rows in groups for r in rows)


class SelectionDiagnosticContracts(unittest.TestCase):
    def test_repair_requires_original_clipboard_recycle_and_all_scene_records(self):
        groups=repair_fixture();d=gate.validate_repair(repair_encode(groups))
        self.assertEqual(45,len(d['rectangles']));self.assertEqual(13,len(d['selectionRows']))
        self.assertFalse(d['performanceResampled'])
        for i in range(len(groups)):
            groups=repair_fixture();groups[i][1].pop()
            with self.subTest(group=i),self.assertRaises(ValueError):gate.validate_repair(repair_encode(groups))

    def test_repair_does_not_silence_old_failures_or_resample_performance(self):
        groups=repair_fixture();groups[2][1][0]['word']=False
        with self.assertRaises(ValueError):gate.validate_repair(repair_encode(groups))
        groups=repair_fixture();groups[3][1][0]['firstVisible']=500
        with self.assertRaises(ValueError):gate.validate_repair(repair_encode(groups))
        groups=repair_fixture();groups.append(('FULL_VIRTUAL_PERF',[dict(fake=True)]))
        with self.assertRaises(ValueError):gate.validate_repair(repair_encode(groups))
        groups=repair_fixture();groups[-2][1][0]['virtual']=False
        with self.assertRaises(ValueError):gate.validate_repair(repair_encode(groups))

    def test_repair_nine_method_ci_inventory_and_old_assertions_remain(self):
        source=(gate.ROOT/'benchmarks/terminal-viewport-tests/run-ci.sh').read_text()
        branch=source.split('if [ "$group" = selectionRepair ]; then',1)[1].split('\nfi',1)[0]
        self.assertEqual(set(gate.ORIGINAL_METHODS),set(re.findall(r'TerminalFullVirtualInstrumentedTest#(\w+)',branch)))
        self.assertNotIn('PairedCold',branch)
        methods=set(re.findall(r'@Test fun (\w+)\(', (gate.ROOT/gate.ACCEPT_FILE).read_text()))
        self.assertEqual(set(gate.ACCEPT_METHODS),methods)
        original='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalFullVirtualInstrumentedTest.kt'
        self.assertEqual((gate.ROOT/original).read_bytes(),gate.git('show',gate.BASE+':'+original))

    def test_observed_failure_is_not_selection_acceptance(self):
        values=gate.decode(encode(fixture()))
        self.assertEqual(set(gate.CASES),set(values))
        self.assertTrue(all(v['acceptance'] is False and v['result']['wordMatches'] is False for v in values.values()))

    def test_missing_duplicate_mixed_or_corrupt_records_fail(self):
        samples=fixture();samples.pop()
        with self.assertRaises(ValueError):gate.decode(encode(samples))
        samples=fixture();samples.append(samples[0])
        with self.assertRaises(ValueError):gate.decode(encode(samples))
        samples=fixture();samples[0]['sha256']='d'*64
        with self.assertRaises(ValueError):gate.decode(encode(samples))
        samples=fixture();samples[0]['parts']=2
        with self.assertRaises(ValueError):gate.decode(encode(samples))

    def test_no_failure_or_leak_is_hidden_by_diagnostic_success(self):
        for field,value in (('acceptance',True),('overflow',1)):
            samples=fixture();v=json.loads(zlib.decompress(base64.b64decode(samples[0]['data'])))
            v[field]=value;raw=json.dumps(v).encode();samples[0].update(sha256=hashlib.sha256(raw).hexdigest(),data=base64.b64encode(zlib.compress(raw)).decode())
            with self.subTest(field=field),self.assertRaises(ValueError):gate.decode(encode(samples))

    def test_diagnostic_only_observer_and_exact_original_corpus(self):
        root=gate.ROOT
        ui=(root/(gate.PREFIX+'TerminalVirtualSelectionUi.kt')).read_text()
        self.assertIn('val observer = binding.onSelectionTrace ?: return',ui)
        self.assertIn('private fun hit(',ui)
        self.assertIn('val accepted = binding.controller.scrollSelectionBy(delta)',ui)
        page=(root/(gate.PREFIX+'ProcessSessionPage.kt')).read_text()
        self.assertNotIn('onSelectionTrace =',page)
        test=(root/gate.TEST_FILE).read_text()
        self.assertIn('repeat(220)',test);self.assertIn('TerminalRenderMode.CHUNKED_LAYERS, TerminalRenderMode.FULL_VIRTUAL',test)
        self.assertIn(r'word$it 中文 e\u0301 👩‍💻',test)
        self.assertIn('val expected = text.text.substringBefore',test)
        self.assertNotIn('TerminalFullVirtualInstrumentedTest#fullVirtualPaired',test)

    def test_motion_guard_has_no_stationary_or_opposite_edge_scroll(self):
        text=(gate.ROOT/(gate.PREFIX+'TerminalVirtualSelectionUi.kt')).read_text()
        self.assertIn('dragMotion.begin(position)',text)
        self.assertIn('dragMotion.move(position)',text)
        self.assertIn('dragMotion.edgeDelta(point, view.height)',text)
        self.assertIn('verticalDirection < 0 && position.y < edge',text)
        self.assertIn('verticalDirection > 0 && position.y > viewportHeight-edge',text)
        self.assertIn('fun end() { previous = null; verticalDirection = 0 }',text)
        self.assertIn('popupContentSize.width',text)
        self.assertIn('points.maxOf { it.y } + handleHeight + 1',text)

    def test_jvm_inventory_matches_the_actual_added_methods(self):
        source=(gate.ROOT/'app/src/test/java/me/rerere/rikkahub/data/container/TerminalFullVirtualTest.kt').read_text()
        methods=re.findall(r'@Test fun (fullVirtual\w+)\(',source)
        workflow=(gate.ROOT/'.github/workflows/terminal-emulator-tests.yml').read_text()
        values=re.findall(r"--require-case-group 'fullVirtual:(\d+)'",workflow)
        self.assertEqual([str(len(methods))],values)
        self.assertEqual(17,len(methods))


if __name__=='__main__':unittest.main()
