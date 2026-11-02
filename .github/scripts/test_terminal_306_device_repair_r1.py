import copy, importlib.util, json, os, tempfile, unittest
from pathlib import Path
from unittest.mock import patch

s=importlib.util.spec_from_file_location('device306_r1',Path(__file__).with_name('terminal-306-device-repair-r1.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)


class Device306R1Tests(unittest.TestCase):
    def test_frozen_scope_and_inventory(self):
        i,p=m.guard();self.assertEqual(len(i['files']),2);self.assertEqual(p['expectedJVM'],518)
        self.assertEqual(len(p['diagnosticTest']['methods']),4)
        self.assertFalse(p['scope']['candidatePageTested']);self.assertFalse(p['scope']['productionAdoptionAllowed'])
    def test_local_kotlin_and_android_forbidden(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            for f in (m.unit,m.page_build,m.page_run):
                with self.assertRaisesRegex(ValueError,'CI-only'):f()
    def test_unit_overlay_restores_production_on_exception(self):
        i,p=m.guard();before={n:(m.ROOT/n).read_bytes() for n in i['files']}
        with self.assertRaisesRegex(RuntimeError,'intended'):
            with m.overlay('unit'):
                for n,v in i['files'].items():self.assertEqual(m.digest((m.ROOT/n).read_bytes()),v['candidate'])
                self.assertTrue((m.ROOT/p['candidateUnit']['file']).exists());raise RuntimeError('intended')
        for n,raw in before.items():self.assertEqual((m.ROOT/n).read_bytes(),raw)
        self.assertFalse((m.ROOT/p['candidateUnit']['file']).exists());m.guard()
    def test_page_overlay_is_test_only_not_width_candidate(self):
        i,p=m.guard();before={n:(m.ROOT/n).read_bytes() for n in i['files']}
        with m.overlay('page'):
            for n,raw in before.items():self.assertEqual((m.ROOT/n).read_bytes(),raw)
            self.assertEqual((m.ROOT/p['diagnosticTest']['file']).read_bytes(),(m.ROOT/p['diagnosticTest']['asset']).read_bytes())
        self.assertFalse((m.ROOT/p['diagnosticTest']['file']).exists());m.guard()
    def test_candidate_leaves_mouse_and_selection_byte_identical(self):
        source=(m.BASE/'candidate/ProcessSessionPage.kt').read_text();base=(m.ROOT/'app/src/main/java/me/rerere/rikkahub/ui/pages/container/ProcessSessionPage.kt').read_text()
        self.assertEqual(source[source.index('    val viewportGestures ='):],base[base.index('    val viewportGestures ='):])
        self.assertNotIn('TerminalPointerInputMode',source)
    def test_candidate_requires_known_geometry_and_explicit_columns_request(self):
        s=(m.BASE/'candidate/TerminalViewportController.kt').read_text()
        for word in ('fun beginWidthChange(columns: Int)','state.value.autoScroll || !ready()','columns == owned.columns',
            'fun completeWidthChange','fun cancelWidthChange','pending.completedRevision ?: return','pending.owner !== owned.history.owner'):
            self.assertIn(word,s)
        self.assertNotIn('TerminalVisibleRowSnapshot',s)
    def test_native_diagnostic_uses_touch_not_semantic_click_for_mouse(self):
        s=(m.BASE/'Terminal306DeviceDiagnosticTest.kt').read_text()
        for word in ('InputDevice.SOURCE_TOUCHSCREEN','MotionEvent.ACTION_MOVE','MotionEvent.ACTION_CANCEL',
            'hold-drag','SystemClock.sleep(800)','if (!mounted && !values.init','runCatching { JSONArray(raw) }'):
            self.assertIn(word,s)
        self.assertNotIn('terminalPointerInputMode',s)
    def test_two_fifo_fences_precede_direct_terminator(self):
        s=(m.BASE/'Terminal306DeviceDiagnosticTest.kt').read_text();a=s.index('// Fence BOTH input queues');b=s.index('// Complete the independent byte-reading peer',a)
        body=s[a:b]
        for token in ('UI_INPUT','INPUT_ENQUEUED','INPUT_WRITTEN'):self.assertIn(token,body)
        self.assertLess(b,s.index('val receipt = Regex',b))
    def test_failed_skipped_duplicate_and_missing_junit_rejected(self):
        parent=m.ROOT/'artifacts/device306-host-tests';parent.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as folder:
            path=Path(folder)/'TEST-example.xml'
            for body in ('<testcase classname="A" name="one"><failure/></testcase>',
                '<testcase classname="A" name="one"><skipped/></testcase>',
                '<testcase classname="A" name="one"/><testcase classname="A" name="one"/>',''):
                path.write_text('<testsuite>'+body+'</testsuite>')
                with patch.object(m.ci,'notice'),self.assertRaises(ValueError):m.junit([path],[dict(classname='A',methods=['one'])])
    def records(self):
        _,p=m.spec();reports=[];installed={'target':'a'*64}
        for name in p['requiredCases']:
            mouse=name.startswith('mouse-');obs=[dict(phase='initial')]
            if mouse:
                for phase in ('tap','drag','hold-drag'):
                    row=4;strings=[f'\x1b[<0;3;{row}M']
                    if phase!='tap':strings += [f'\x1b[<32;{c};{row}M' for c in range(4,8)]
                    strings += [f'\x1b[<0;{3 if phase=="tap" else 7};{row}m']
                    events=[];seq=0
                    for text in strings:
                        for stage in ('UI_INPUT','INPUT_ENQUEUED','INPUT_WRITTEN'):
                            seq+=1;events.append(dict(seq=seq,stage=stage,bytes=0 if stage=='UI_INPUT' else len(text)))
                    for stage in ('INPUT_ENQUEUED','INPUT_WRITTEN'):seq+=1;events.append(dict(seq=seq,stage=stage,bytes=1))
                    raw=''.join(strings).encode();sentinel='device306-'+name+'-'+phase+'-untouched'
                    obs.append(dict(phase=phase,row=row,expectedHex=raw.hex(),receivedHex=raw.hex(),wireMatches=True,
                        clipboardUnchanged=True,clipboardSha256=m.digest(sentinel.encode()),traceDropped=0,writers=0,
                        inputEvents=events,uiInputCount=len(strings),inputWrittenBytes=len(raw)+1,
                        localNonEditableSelections=0,pan=False,selection=False))
                obs.append(dict(phase='explicit-osc52-control',clipboardMatches=True))
            else:
                obs += [dict(phase='before-locked-width',following=False,columns=80),
                    dict(phase='after-locked-width',following=False,columns=100,preservedPriorMode=True),
                    dict(phase='before-auto-width',following=True,columns=100),
                    dict(phase='after-auto-width',following=True,columns=120,preservedPriorMode=True)]
            reports.append(dict(case=name,mode='fullVirtual' if name.endswith('fullVirtual') else 'chunkedLayers',
                executionComplete=True,nativePty=True,productionChanged=False,performanceMeasured=False,allReportedDeviceBugsFixed=False,
                targetApkSha256=installed['target'],observations=obs,findings=[]))
        return p,reports+[dict(case=n,cleanup=True,closed=True) for n in p['requiredCases']],installed
    def test_complete_diagnostic_inventory_is_verified(self):
        p,r,i=self.records();reports,cleanup=m.validate_diagnostic(r,p,i);self.assertEqual(len(reports),4);self.assertEqual(len(cleanup),4)
    def test_missing_extra_or_false_success_diagnostic_cannot_pass(self):
        p,r,i=self.records()
        for wrong in (r[:-1],r+[r[0]]):
            with self.assertRaises(ValueError):m.validate_diagnostic(wrong,p,i)
        for key,value in (('executionComplete',False),('productionChanged',True),('allReportedDeviceBugsFixed',True),('targetApkSha256','wrong')):
            wrong=copy.deepcopy(r);wrong[0][key]=value
            with self.assertRaises(ValueError):m.validate_diagnostic(wrong,p,i)
    def test_byte_and_clipboard_claims_require_original_evidence(self):
        p,r,i=self.records()
        for key,value in (('wireMatches',False),('receivedHex','00'),('inputWrittenBytes',999),('uiInputCount',9),('clipboardSha256','bad')):
            wrong=copy.deepcopy(r);wrong[0]['observations'][1][key]=value
            with self.assertRaises(ValueError):m.validate_diagnostic(wrong,p,i)
    def test_real_width_regression_is_reported_not_discarded(self):
        p,r,i=self.records();case=next(v for v in r if v.get('case')=='width-fullVirtual' and 'observations' in v)
        case['observations'][2].update(following=True,preservedPriorMode=False);case['findings']=['width:LOCK-became-AUTO']
        m.validate_diagnostic(r,p,i)
        case['findings']=[]
        with self.assertRaises(ValueError):m.validate_diagnostic(r,p,i)
    def test_local_selection_finding_is_not_misreported_as_sel_enabled(self):
        p,r,i=self.records();v=r[0]['observations'][1];v['localNonEditableSelections']=1;r[0]['findings']=['tap:local-selection']
        m.validate_diagnostic(r,p,i);self.assertFalse(v['selection'])
    def test_fifo_order_and_terminator_are_mandatory(self):
        p,r,i=self.records();r[0]['observations'][1]['inputEvents'][-1]['bytes']=2
        with self.assertRaises(ValueError):m.validate_diagnostic(r,p,i)
    def test_wrong_column_presets_cannot_be_used_as_product_failure(self):
        p,r,i=self.records();r[2]['observations'][2]['columns']=120
        with self.assertRaises(ValueError):m.validate_diagnostic(r,p,i)


if __name__=='__main__':unittest.main()
