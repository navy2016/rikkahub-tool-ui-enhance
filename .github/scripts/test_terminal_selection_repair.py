"""Host synthetic failure contracts only. Never Android acceptance or fabricated evidence."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
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


class SelectionDiagnosticContracts(unittest.TestCase):
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


if __name__=='__main__':unittest.main()
