import copy,importlib.util,json,os,subprocess,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
s=importlib.util.spec_from_file_location('main_width_a1',Path(__file__).with_name('terminal-main-width-lifecycle.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class MainWidthGuardTests(unittest.TestCase):
    def test_frozen_candidate_and_existing_regression_inventory(self):
        identity,p=m.guard()
        self.assertEqual(len(identity['files']),5)
        self.assertEqual(sum(len(x['methods']) for x in p['unitInventory']),504)
        self.assertEqual(p['expectedJVM'],533);self.assertEqual(p['expectedDevice'],20)
    def test_base_is306_not_historical55(self):
        _,p=m.spec();self.assertEqual(p['baseProductionSha'],'306e0b80b508c961bc00f2d4b34a2688e5857a1a')
        self.assertEqual(p['baseTree'],'ac97798416997a8230c9fa2a5a8569cf0ed0a911')
    def test_no_performance_or_adoption_authority(self):
        _,p=m.spec()
        for key in ('performanceClaimAllowed','productionAdoptionAllowed','formalApkBuildAllowed','localApkDownload'):self.assertFalse(p[key])
    def test_shared_prepared_width_does_not_opt_legacy_history_in(self):
        path=m.ROOT/'app/src/main/java/me/rerere/rikkahub/ui/pages/container/TerminalVirtualHistoryTranscript.kt'
        self.assertNotIn('independentMainHistory',path.read_text())
        self.assertEqual(path.read_bytes(),m.git('show','306e0b80b508c961bc00f2d4b34a2688e5857a1a:'+str(path.relative_to(m.ROOT))))
    def test_page_publication_helpers_and_input_source_are_untouched(self):
        for tail in ('ui/pages/container/ProcessSessionPage.kt','data/container/TerminalInFramePublication.kt',
            'data/container/BackgroundProcessManager.kt','ui/pages/container/TerminalViewportBinding.kt',
            'ui/pages/container/TerminalLazyItemExecutor.kt','ui/pages/container/TerminalPhysicalMeasurements.kt'):
            name='app/src/main/java/me/rerere/rikkahub/'+tail
            self.assertEqual((m.ROOT/name).read_bytes(),m.git('show','306e0b80b508c961bc00f2d4b34a2688e5857a1a:'+name))
    def test_only_one_history_queue_no_parked_p2_symbols(self):
        text=(m.BASE/'candidate/TerminalTranscriptWidthIndex.kt').read_text()
        self.assertEqual(text.count('ArrayDeque<Candidate>()'),1)
        for symbol in ('parkedMainHistory','prepareFullVirtualHistory','restoreMainHistory'):self.assertNotIn(symbol,text)
    def test_candidate_overlay_restores_after_exception_without_compiling(self):
        identity,p=m.guard();before={name:(m.ROOT/name).read_bytes() for name in identity['files']}
        with self.assertRaisesRegex(RuntimeError,'intentional host guard test'):
            with m.overlay('unit'):
                for name,item in identity['files'].items():self.assertEqual(m.digest((m.ROOT/name).read_bytes()),item['candidate'])
                self.assertTrue((m.ROOT/p['candidateUnit']['file']).exists())
                raise RuntimeError('intentional host guard test')
        for name,raw in before.items():self.assertEqual((m.ROOT/name).read_bytes(),raw)
        self.assertFalse((m.ROOT/p['candidateUnit']['file']).exists());m.guard()
    def test_kotlin_and_android_actions_are_not_permitted_locally(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            for action in (m.unit,m.build_fixture,m.device):
                with self.assertRaisesRegex(ValueError,'CI-only'):action()
    def test_cli_guard_is_executable(self):
        completed=subprocess.run(['python3','-B',str(Path(m.__file__)),'guard'],capture_output=True,text=True,timeout=45)
        self.assertEqual(completed.returncode,0,completed.stdout+completed.stderr)
        self.assertFalse(json.loads(completed.stdout)['productionChanged'])
    def test_exact_inventory_rejects_missing_and_unexpected(self):
        inventory=[{'classname':'A','methods':['first','second']}]
        m.verify_inventory([{'classname':'A','name':'first'},{'classname':'A','name':'second'}],inventory)
        for rows in ([],[{'classname':'A','name':'first'}],[{'classname':'A','name':'third'}]):
            with self.assertRaisesRegex(ValueError,'inventory mismatch'):m.verify_inventory(rows,inventory)
    def test_junit_errors_skips_duplicates_are_not_success(self):
        parent=m.ROOT/'artifacts/main-width-host-tests';parent.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as directory:
            path=Path(directory)/'TEST-example.xml'
            for body in ('<testcase classname="A" name="one"><failure message="bad"/></testcase>',
                '<testcase classname="A" name="one"><skipped/></testcase>',
                '<testcase classname="A" name="one"/><testcase classname="A" name="one"/>'):
                path.write_text('<testsuite>'+body+'</testsuite>')
                with patch.object(m,'notice'), self.assertRaises(ValueError):m.read_junit([path])
    def scene_records(self):
        _,p=m.spec();records=[]
        for scene in p['requiredDeviceScenes']:
            count=1000 if scene in ('font-return','scale-return','rtl-return','columns-return') else \
                129 if scene in ('custom-main','custom-return') else 12 if scene=='suffix-return' else 1 if scene.endswith('-fifo') else 0
            records.append(dict(scene=scene,measuredHistoryDelta=count,writers=1,rowStates=24,measuredRows=30,
                scalarCandidates=3,screenWidthKeys=0,alternate=True,performanceMeasured=False))
        return p,records
    def test_scene_inventory_is_fixed_no_duplicates_or_missing(self):
        p,records=self.scene_records()
        def text(rows):return '\n'.join('MAIN_WIDTH_VIEW '+json.dumps(x) for x in rows)
        self.assertEqual(len(m.scenes(text(records),p)),37)
        for rows in (records[:-1],records+[records[0]]):
            with self.assertRaisesRegex(ValueError,'scenes missing/duplicate/unexpected'):m.scenes(text(rows),p)
    def test_scene_counters_and_retention_are_mandatory(self):
        p,records=self.scene_records()
        for key,value in [('measuredHistoryDelta',999),('writers',2),('rowStates',10001),('measuredRows',10001),
            ('scalarCandidates',10001),('screenWidthKeys',1),('performanceMeasured',True)]:
            bad=copy.deepcopy(records);bad[0][key]=value
            with self.assertRaises(ValueError):m.scenes('\n'.join('MAIN_WIDTH_VIEW '+json.dumps(x) for x in bad),p)
    def test_device_inventory_excludes_rejected_and_performance_methods(self):
        _,p=m.spec();names=' '.join(x['classname']+' '+ ' '.join(x['methods']) for x in p['deviceInventory'])
        for word in ('RecoveryCandidate','Performance','PairedPasses','cursorExperiment'):self.assertNotIn(word,names)
        self.assertEqual(len(p['candidateView']['methods']),5)
if __name__=='__main__':unittest.main()
