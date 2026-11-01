import copy,importlib.util,json,os,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch

s=importlib.util.spec_from_file_location('geometry_c1',Path(__file__).with_name('terminal-geometry-c1.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

class GeometryC1GuardTests(unittest.TestCase):
    def test_exact306_four_file_candidate_and_fixed_inventories(self):
        identity,p=m.guard();self.assertEqual(len(identity['files']),4)
        self.assertEqual(p['baseProductionSha'],'306e0b80b508c961bc00f2d4b34a2688e5857a1a')
        self.assertEqual(sum(len(x['methods']) for x in p['unitInventory']),504)
        self.assertEqual(p['expectedJVM'],528);self.assertEqual(p['expectedDevice'],44)
        self.assertEqual(len(p['requiredDeviceScenes']),17)
    def test_no_performance_or_adoption_permission(self):
        _,p=m.spec()
        for key in ('performanceClaimAllowed','productionAdoptionAllowed','formalApkBuildAllowed','localApkDownload'):
            self.assertIs(p[key],False)
        self.assertTrue(p['nextPerformanceRequiresIndependentTraceLifecycleQualification'])
    def test_no_history_width_or_B1_P2_changes(self):
        i,_=m.spec();names={Path(n).name for n in i['files']}
        for name in ('TerminalPhysicalMeasurements.kt','TerminalTranscriptWidthIndex.kt','TerminalPreparedWidth.kt',
            'TerminalEmulator.kt','TerminalRowStore.kt','TerminalInFramePublication.kt','TerminalLazyItemExecutor.kt'):
            self.assertNotIn(name,names)
    def test_snapshot_has_private_detached_storage_not_layout_cache(self):
        text=(m.BASE/'candidate/TerminalItemViewport.kt').read_text()
        self.assertIn('private constructor',text);self.assertIn('rows.toTypedArray()',text)
        self.assertNotIn('LazyListState',text);self.assertNotIn('WeakReference',text)
        self.assertIn('rows.validatedFor(frame.rows.size)',text)
    def test_old_lists_and_nonfull_backends_do_not_use_proof_shortcut(self):
        text=(m.BASE/'candidate/TerminalItemViewport.kt').read_text()
        self.assertIn('fullVirtual == null || rows !is TerminalVisibleRowSnapshot',text)
        self.assertIn('require(rows.zipWithNext().all',text);self.assertIn('require(rows.all',text)
        self.assertIn('fullVirtual != null && rows is TerminalVisibleRowSnapshot',text)
    def test_actual_live_geometry_guards_and_invalidation_are_retained(self):
        text=(m.BASE/'candidate/TerminalLazyItemMeasurements.kt').read_text()
        for token in ('val info = state.layoutInfo','item.key != id || height(pass, item.index, displayed) != item.size',
            'policy.screen.rowHeight(item.index - frame.historyCount) != item.size','measurements.release(owner, lineId)',
            'fun acknowledgeChanges() = invalidations.acknowledge()'):
            self.assertIn(token,text)
        self.assertEqual(text.count('TerminalVisibleRowSnapshot.capture'),1)
    def test_scroll_writer_selection_and_frame_collector_are_not_altered(self):
        _,p=m.spec()
        for tail in ('ui/pages/container/TerminalLazyItemExecutor.kt','ui/pages/container/TerminalViewportScrollEffects.kt',
            'ui/pages/container/TerminalVirtualSelectionUi.kt','ui/pages/container/TerminalViewportGestures.kt',
            'ui/pages/container/ProcessSessionPage.kt','data/container/TerminalInFramePublication.kt'):
            name='app/src/main/java/me/rerere/rikkahub/'+tail
            self.assertEqual((m.ROOT/name).read_bytes(),m.git('show',p['baseProductionSha']+':'+name))
    def test_unit_overlay_finally_restores_exact_base_on_exception(self):
        identity,p=m.guard();before={n:(m.ROOT/n).read_bytes() for n in identity['files']}
        with self.assertRaisesRegex(RuntimeError,'C1 deliberate host failure'):
            with m.overlay('unit'):
                for name,item in identity['files'].items():self.assertEqual(m.digest((m.ROOT/name).read_bytes()),item['candidate'])
                self.assertTrue((m.ROOT/p['candidateUnit']['file']).exists())
                raise RuntimeError('C1 deliberate host failure')
        for n,raw in before.items():self.assertEqual((m.ROOT/n).read_bytes(),raw)
        self.assertFalse((m.ROOT/p['candidateUnit']['file']).exists());m.guard()
    def test_device_overlay_does_not_install_new_unit_source(self):
        _,p=m.guard()
        with m.overlay('device'):
            self.assertEqual((m.ROOT/p['candidateView']['file']).read_bytes(),(m.ROOT/p['candidateView']['asset']).read_bytes())
            self.assertFalse((m.ROOT/p['candidateUnit']['file']).exists())
        self.assertFalse((m.ROOT/p['candidateView']['file']).exists());m.guard()
    def test_local_kotlin_and_device_execution_forbidden(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            for action in (m.unit,m.build_fixture,m.device):
                with self.assertRaisesRegex(ValueError,'CI-only'):action()
    def test_missing_extra_junit_methods_rejected(self):
        expected=[dict(classname='A',methods=['one','two'])]
        for rows in ([],[dict(classname='A',name='one')],[dict(classname='A',name='other')]):
            with self.assertRaises(ValueError):m.verify_inventory(rows,expected)
    def test_failure_skip_error_and_duplicate_junit_rejected(self):
        folder=m.ROOT/'artifacts/geometry-c1-host-tests';folder.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as tmp:
            path=Path(tmp)/'TEST-host.xml'
            for body in ('<testcase classname="A" name="one"><failure message="bad"/></testcase>',
                '<testcase classname="A" name="one"><skipped/></testcase>',
                '<testcase classname="A" name="one"><error/></testcase>',
                '<testcase classname="A" name="one"/><testcase classname="A" name="one"/>'):
                path.write_text('<testsuite>'+body+'</testsuite>')
                with patch.object(m,'notice'),self.assertRaises(ValueError):m.read_junit([path])
    def records(self):
        _,p=m.spec();rows=[]
        for scene in p['requiredDeviceScenes']:
            legacy=scene in ('legacy-history','default-eager')
            history=0 if scene in ('alt-enter','alt-redraw') else 10000 if scene in ('main-edit','main-return','fifo') else 500 if scene=='session-replacement' else 1000
            rows.append(dict(scene=scene,maximumWriters=1,geometryChecked=True,performanceMeasured=False,history=history,
                visibleRows=18,measuredRows=24,fullVirtual=not legacy,virtual=scene!='default-eager',alternate=scene in ('alt-enter','alt-redraw'),
                reusedObservations=256 if scene=='same-layout-policy' else 0,rejectedReads=4 if scene=='rejected-live-inputs' else 0))
        return p,rows
    def log(self,rows):return '\n'.join('GEOMETRY_C1_VIEW '+json.dumps(r) for r in rows)
    def test_all17scenes_required_once(self):
        p,r=self.records();self.assertEqual(len(m.scenes(self.log(r),p)),17)
        for bad in (r[:-1],r+[r[0]]):
            with self.assertRaises(ValueError):m.scenes(self.log(bad),p)
    def test_no_hidden_writer_or_performance_or_proof_relaxation(self):
        p,r=self.records()
        for key,value in (('maximumWriters',2),('geometryChecked',False),('performanceMeasured',True),
            ('reusedObservations',255),('visibleRows',10001),('history',999),('fullVirtual',False)):
            bad=copy.deepcopy(r);bad[0][key]=value
            with self.assertRaises(ValueError):m.scenes(self.log(bad),p)
    def test_live_metric_text_height_and_key_rejection_cannot_be_omitted(self):
        p,r=self.records();next(x for x in r if x['scene']=='rejected-live-inputs')['rejectedReads']=3
        with self.assertRaises(ValueError):m.scenes(self.log(r),p)
    def test_alt_and_legacy_backend_inventory_is_exact(self):
        p,r=self.records()
        for scene,key in (('alt-enter','alternate'),('legacy-history','fullVirtual'),('default-eager','virtual')):
            bad=copy.deepcopy(r);item=next(x for x in bad if x['scene']==scene);item[key]=not item[key]
            with self.assertRaises(ValueError):m.scenes(self.log(bad),p)
    def test_cli_guard_leaves_production_unchanged(self):
        r=subprocess.run(['python3','-B',m.__file__,'guard'],capture_output=True,text=True,timeout=45)
        self.assertEqual(r.returncode,0,r.stdout+r.stderr);self.assertIs(json.loads(r.stdout)['productionChanged'],False)

if __name__=='__main__':unittest.main()
