"""Synthetic host contract fixtures; never native runtime evidence."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import unittest
import zlib

spec=importlib.util.spec_from_file_location('native_contract',Path(__file__).with_name('terminal-native-selection-reports.py'))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)


def fixture():
    rows=[]
    for name in gate.CASES:
        d=dict(case=name,suite='terminal-native-selection-diagnostic-v1',acceptance=False,overflow=0,pid=123,fingerprint='synthetic',
            targetApk='a'*64,testApk='b'*64,result=dict(mode=name.rsplit('-',1)[1],finished=True,virtual=name.endswith('fullVirtual'),
                historyRows=10000,retainedRows=0,pointerRows=0,handleFound=True,startRecycled=False,wordMatches=False,textSha256='c'*64),
            events=[dict(stage='native-geometry',phase='after-longpress',sequence=0,timeNs=1),
                dict(stage='driver-finished',sequence=1,timeNs=2,moves=220,events=222,frames=222),dict(stage='disposed',sequence=2,timeNs=3)])
        raw=json.dumps(d).encode();rows.append(dict(case=name,part=1,parts=1,sha256=hashlib.sha256(raw).hexdigest(),data=base64.b64encode(zlib.compress(raw)).decode()))
    return rows


def encoded(parts):return '\n'.join('NATIVE_SELECTION_PART '+json.dumps(p) for p in parts)


class NativeContracts(unittest.TestCase):
    def test_complete_four_outcomes_do_not_claim_acceptance(self):
        d=gate.decode(encoded(fixture()))
        self.assertEqual(set(gate.CASES),set(d));self.assertTrue(all(v['acceptance'] is False for v in d.values()))
    def test_missing_mixed_duplicate_or_bad_hash_is_rejected(self):
        for mutate in (lambda p:p.pop(),lambda p:p.append(p[0]),lambda p:p[0].update(sha256='d'*64),lambda p:p[0].update(parts=2)):
            p=fixture();mutate(p)
            with self.assertRaises(ValueError):gate.decode(encoded(p))
    def test_native_probes_in_correct_class_and_original_drivers_unchanged(self):
        s=(gate.ROOT/gate.SOURCE).read_text()
        self.assertNotIn('fun handlePositioned',s.split('internal class TerminalVirtualSelectionUi')[0])
        self.assertIn('native-menu-request',s);self.assertIn('handle-ended',s)
        test=(gate.ROOT/gate.FILE).read_text()
        self.assertEqual(set(gate.METHODS),set(re.findall(r'@Test fun (\w+)\(',test)))
        self.assertIn('repeat(220)',test);self.assertIn('nativeGeometry(c,"after-longpress")',test)
        self.assertIn('"copy" to copy?.visibleBounds',test)
        old='benchmarks/terminal-viewport-tests/src/androidTest/java/me/rerere/rikkahub/viewporttest/TerminalFullVirtualInstrumentedTest.kt'
        self.assertEqual(gate.git('show',gate.BASE+':'+old),(gate.ROOT/old).read_bytes())

    def test_window_host_and_pointer_separation_are_real_not_extra_scrollers(self):
        s=(gate.ROOT/gate.SOURCE).read_text()
        self.assertIn('owner.rootView.startActionMode(callback,ActionMode.TYPE_FLOATING)',s)
        self.assertIn('view.getWindowVisibleDisplayFrame(visible)',s)
        self.assertIn('terminalSelectionHandlePosition(proposed,popupContentSize,safe)',s)
        self.assertIn('ui.beginHandle(start, anchor, initial)',s)
        self.assertIn('ui.extend(it + adjustment,it)',s)
        self.assertIn('dragMotion.edgeDelta(pointer, view.height)',s)
        self.assertNotIn('lazy.scrollBy(',s)


if __name__=='__main__':unittest.main()
