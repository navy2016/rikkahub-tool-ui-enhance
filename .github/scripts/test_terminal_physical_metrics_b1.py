import copy,importlib.util,json,os,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
s=importlib.util.spec_from_file_location('physical_metrics_b1',Path(__file__).with_name('terminal-physical-metrics-b1.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

class PhysicalMetricsB1GuardTests(unittest.TestCase):
    def test_exact306_independent_candidate_inventory(self):
        identity,p=m.guard();self.assertEqual(len(identity['files']),5)
        self.assertEqual(p['baseProductionSha'],'306e0b80b508c961bc00f2d4b34a2688e5857a1a')
        self.assertEqual(sum(len(x['methods']) for x in p['unitInventory']),504)
        self.assertEqual(p['expectedJVM'],529);self.assertEqual(p['expectedDevice'],28)
    def test_no_performance_or_adoption_permission(self):
        _,p=m.spec()
        for key in ('performanceClaimAllowed','productionAdoptionAllowed','formalApkBuildAllowed','localApkDownload'):self.assertFalse(p[key])
    def test_unchanged_parser_frame_scheduler_selection_and_old_renderers(self):
        for tail in ('ui/pages/container/ProcessSessionPage.kt','data/container/TerminalInFramePublication.kt',
            'data/container/BackgroundProcessManager.kt','utils/TerminalEmulator.kt','utils/TerminalFrameSnapshot.kt',
            'ui/pages/container/TerminalLazyItemExecutor.kt','ui/pages/container/TerminalVirtualHistoryTranscript.kt',
            'ui/pages/container/TerminalVirtualSelectionUi.kt','ui/pages/container/TerminalRenderedRows.kt',
            'ui/pages/container/TerminalTranscriptWidth.kt','ui/pages/container/TerminalRowWidthMeasurer.kt'):
            name='app/src/main/java/me/rerere/rikkahub/'+tail
            self.assertEqual((m.ROOT/name).read_bytes(),m.git('show','306e0b80b508c961bc00f2d4b34a2688e5857a1a:'+name))
    def test_frozen_A1_rejection_assets_are_not_changed(self):
        result=m.git('diff','--name-only','62c242e1268be171cffa75b06890fcc41442f824','--',
            'benchmarks/terminal-main-width-lifecycle','benchmarks/terminal-main-width-performance',
            '.github/scripts/terminal-main-width-lifecycle.py','.github/scripts/terminal-main-width-performance.py',
            'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalMainWidthPerformanceTest.kt')
        self.assertFalse(result.strip())
    def test_production_default_mode_and_physical_budget_unchanged(self):
        text=(m.ROOT/'app/src/main/java/me/rerere/rikkahub/data/container/TerminalRenderMode.kt').read_text()
        self.assertIn('val DEFAULT = CHUNKED_LAYERS',text)
        physical=(m.BASE/'candidate/TerminalPhysicalMeasurements.kt').read_text()
        self.assertIn('rows.size <= TerminalEmulator.MAX_ROWS',physical)
        self.assertEqual(physical.count('HashMap<Long, TerminalPhysicalRowMeasurement>'),1)
    def test_lifecycle_and_legacy_width_api_are_not_repurposed(self):
        text=(m.BASE/'candidate/TerminalTranscriptWidthIndex.kt').read_text()
        self.assertIn('width(frame, metricKey, null, measure)',text)
        for forbidden in ('independentMainHistory','parkedMainHistory','historyProof','historyMetrics'):self.assertNotIn(forbidden,text)
        self.assertIn('if (!reuse) widest.clear()',text)
    def test_product_is_current_frame_metric_text_and_width_intrinsic_bound(self):
        text=(m.BASE/'candidate/TerminalPhysicalMeasurements.kt').read_text()
        for required in ('WeakReference(frame)','frameReference.get() === frame','metricKey == metric',
            'value.text != text','intrinsic.maxIntrinsicWidth','intrinsic.hasStaleResolvedFonts',
            'input.fontFamilyResolver !== metrics.resolver','fontMetrics?.checkpointProof()'):self.assertIn(required,text)
    def test_cold_worker_keys_are_not_canceled_by_every_active_row_output(self):
        text=(m.BASE/'candidate/TerminalPreparedWidth.kt').read_text()
        self.assertIn('LaunchedEffect(index, key, metrics, restored, completed)',text)
        self.assertNotIn('LaunchedEffect(index, key, metrics, restored, completed, screenWidths)',text)
        self.assertIn('metrics, completed, screenWidths)',text)
    def test_unit_overlay_recovers_from_failure_without_compilation(self):
        identity,p=m.guard();before={name:(m.ROOT/name).read_bytes() for name in identity['files']}
        with self.assertRaisesRegex(RuntimeError,'intentional B1 host test'):
            with m.overlay('unit'):
                for name,item in identity['files'].items():self.assertEqual(m.digest((m.ROOT/name).read_bytes()),item['candidate'])
                self.assertTrue((m.ROOT/p['candidateUnit']['file']).exists());raise RuntimeError('intentional B1 host test')
        self.assertFalse((m.ROOT/p['candidateUnit']['file']).exists())
        for name,raw in before.items():self.assertEqual((m.ROOT/name).read_bytes(),raw)
        m.guard()
    def test_device_overlay_recovers_and_copies_only_the_new_test(self):
        _,p=m.guard()
        with m.overlay('device'):
            self.assertEqual((m.ROOT/p['candidateView']['file']).read_bytes(),(m.ROOT/p['candidateView']['asset']).read_bytes())
            self.assertFalse((m.ROOT/p['candidateUnit']['file']).exists())
        self.assertFalse((m.ROOT/p['candidateView']['file']).exists());m.guard()
    def test_no_local_android_or_kotlin_execution(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            for action in (m.unit,m.build_fixture,m.device):
                with self.assertRaisesRegex(ValueError,'CI-only'):action()
    def test_method_inventory_rejects_incomplete_or_extra(self):
        inventory=[dict(classname='A',methods=['one','two'])]
        for rows in ([],[dict(classname='A',name='one')],[dict(classname='A',name='third')]):
            with self.assertRaises(ValueError):m.verify_inventory(rows,inventory)
    def test_failed_skipped_or_duplicate_junit_never_passes(self):
        parent=m.ROOT/'artifacts/physical-metrics-host-tests';parent.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as directory:
            path=Path(directory)/'TEST-example.xml'
            for body in ('<testcase classname="A" name="one"><failure message="bad"/></testcase>',
                '<testcase classname="A" name="one"><skipped/></testcase>',
                '<testcase classname="A" name="one"/><testcase classname="A" name="one"/>'):
                path.write_text('<testsuite>'+body+'</testsuite>')
                with patch.object(m,'notice'),self.assertRaises(ValueError):m.read_junit([path])
    def records(self):
        _,p=m.spec();records=[]
        for scene in p['requiredDeviceScenes']:
            history=int(scene.split('-')[0]) if scene.endswith('-main-return') else 1000 if scene in ('font-size','density-scale','rtl','columns','custom-font','packaged-return') else 1 if scene.endswith('-fifo') else 0
            shared=1 if scene.endswith('-fifo') else 24 if scene.endswith(('-main-edit','-alt-enter','-alt-redraw','-main-return')) or scene in ('font-size','density-scale','rtl','columns','packaged-return') else 0
            comparisons=288 if scene=='glyph-metrics-oracle' else 30 if scene=='compatibility-oracle' else 24
            rejected=30 if scene=='compatibility-oracle' else 1 if scene in ('blank-held','same-frame-forced-blank','remount') else 0
            records.append(dict(scene=scene,historyDelta=history,sharedScreenDelta=shared,fallbackScreenDelta=0,
                oracleComparisons=comparisons,rejectedProducts=rejected,physicalRows=24,writers=1,performanceMeasured=False))
        return p,records
    @staticmethod
    def log(records):return '\n'.join('PHYSICAL_METRICS_VIEW '+json.dumps(x) for x in records)
    def test_all24scenes_are_required_exactly_once(self):
        p,records=self.records();self.assertEqual(len(m.scenes(self.log(records),p)),24)
        for rows in (records[:-1],records+[records[0]]):
            with self.assertRaises(ValueError):m.scenes(self.log(rows),p)
    def test_counts_and_claims_cannot_be_relaxed(self):
        p,records=self.records()
        for key,value in [('historyDelta',9),('writers',2),('physicalRows',10000),('oracleComparisons',1),
            ('performanceMeasured',True),('sharedScreenDelta',-1)]:
            bad=copy.deepcopy(records);bad[0][key]=value
            with self.assertRaises(ValueError):m.scenes(self.log(bad),p)
    def test_return_must_keep306_full_history_work(self):
        p,records=self.records();bad=copy.deepcopy(records)
        next(x for x in bad if x['scene']=='10000-main-return')['historyDelta']=0
        with self.assertRaisesRegex(ValueError,'changed306history'):m.scenes(self.log(bad),p)
    def test_glyph_and_compatibility_oracles_are_not_just_hit_counters(self):
        p,records=self.records();bad=copy.deepcopy(records)
        next(x for x in bad if x['scene']=='compatibility-oracle')['rejectedProducts']=0
        with self.assertRaises(ValueError):m.scenes(self.log(bad),p)
    def test_cli_guard_does_not_adopt_the_candidate(self):
        result=subprocess.run(['python3','-B',m.__file__,'guard'],capture_output=True,text=True,timeout=45)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertFalse(json.loads(result.stdout)['productionChanged'])

if __name__=='__main__':unittest.main()
