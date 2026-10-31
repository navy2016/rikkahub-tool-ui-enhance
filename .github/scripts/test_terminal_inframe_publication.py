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
    def test_native_driver_has_same_inputs_and_timing_as_diagnosis(self):
        old=(m.ROOT/'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalPublicationWaitTest.kt').read_text()
        expected=old.replace('class TerminalPublicationWaitTest :','class TerminalInFramePublicationTest :')
        for suffix in ('OneThousandHidden','OneThousandShown','TenThousandHidden','TenThousandShown'):
            expected=expected.replace('@Test fun publication'+suffix+'()', '@Test fun inframe'+suffix+'()')
        expected=expected.replace('val case="publication-$size-', 'val case="inframe-$size-')
        expected=expected.replace('.put("protocolId","terminal-publication-wait-v1")','.put("protocolId","terminal-inframe-publication-candidate-v1")')
        self.assertEqual(expected,(m.ROOT/m.TEST).read_text())
    def test_compile_source_inventory_only_adds_helper_when_overlay_exists(self):
        import types
        source=m.sources();self.assertNotIn(m.HELPER,source);old=m.ci.real_clock
        try:
            m.ci.real_clock=types.SimpleNamespace(TEST=m.real.TEST)
            self.assertEqual(source,m.sources())
        finally:m.ci.real_clock=old
    def test_cli_after_definitions(self):
        import ast
        tree=ast.parse(Path(m.__file__).read_text());self.assertIsInstance(tree.body[-1],ast.If)
    def test_release_jvm_does_not_enable_overriding_android_test_build_type(self):
        import ast
        tree=ast.parse(Path(m.__file__).read_text())
        commands=[n.value for n in ast.walk(tree) if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='command' for t in n.targets)]
        self.assertEqual(1,len(commands));values=ast.literal_eval(commands[0])
        self.assertIn('-PterminalReleaseTests=true',values);self.assertIn(':app:testReleaseUnitTest',values)
        self.assertNotIn('-PterminalPipelineTests=true',values)
    def test_ordinary_publication_must_be_inside_its_own_callback(self):
        class Index:
            def ui_for(self,ns):return [dict(token=7,startNs=0,endNs=200)] if 0<=ns<=200 else []
        stages=['INFRAME_CALLBACK_START','INFRAME_ELIGIBLE','INFRAME_PUBLISH_CALL','FRAME_PUBLISHED','INFRAME_PUBLISH_RESULT','INFRAME_CALLBACK_END']
        events=[dict(stage=name,seq=i+1,ns=(i+1)*10,revision=5,flag=True) for i,name in enumerate(stages)]
        sample=dict(events=events,fedRevision=5,phase='r0-refresh',condition='refresh')
        self.assertTrue(m.classify(sample,Index())['callbackPublication'])
        events[3]['ns']=300
        with self.assertRaises(ValueError):m.classify(sample,Index())
    def test_sync_must_keep_active_original_guard(self):
        class Index:
            def ui_for(self,ns):return []
        names=['INFRAME_CALLBACK_START','INFRAME_ELIGIBLE','INFRAME_CALLBACK_END','INFRAME_FALLBACK_SYNC_START','INFRAME_FALLBACK_SYNC_END','INFRAME_FALLBACK_CALL','FRAME_PUBLISHED']
        events=[dict(stage=n,seq=i+1,ns=(i+1)*10,revision=5,flag=n in ('INFRAME_FALLBACK_SYNC_START','INFRAME_FALLBACK_SYNC_END')) for i,n in enumerate(names)]
        sample=dict(events=events,fedRevision=5,phase='r0-sync',condition='sync')
        self.assertTrue(m.classify(sample,Index())['fallbackPublication'])
        events[3]['flag']=False
        with self.assertRaises(ValueError):m.classify(sample,Index())
    def test_only_a_capped_leading_prefix_end_can_be_retained_without_start(self):
        class Index:
            def ui_for(self,ns):return [dict(token=7,startNs=0,endNs=10000)]
        names=['INFRAME_CALLBACK_START','INFRAME_ELIGIBLE','INFRAME_PUBLISH_CALL','FRAME_PUBLISHED','INFRAME_PUBLISH_RESULT','INFRAME_CALLBACK_END']
        events=[dict(stage=n,seq=300+i,ns=3000+i,revision=5,flag=True) for i,n in enumerate(names)]
        prefix=[dict(stage='OLD',seq=i+1,ns=i+1,revision=1,flag=None) for i in range(256)]
        prefix[1]['stage']='INFRAME_CALLBACK_END'
        sample=dict(events=events,prefixEvents=prefix,fedRevision=5,phase='r0-refresh',condition='refresh')
        self.assertEqual(1,len(m.classify(sample,Index())['clippedPrefixCallbackEnds']))
        for change in ('uncapped','current-end','nested'):
            import copy
            value=copy.deepcopy(sample)
            if change=='uncapped':value['prefixEvents']=value['prefixEvents'][1:]
            elif change=='current-end':value['events'][0]['stage']='INFRAME_CALLBACK_END'
            else:value['events'][1]['stage']='INFRAME_CALLBACK_START'
            with self.assertRaises(ValueError):m.classify(value,Index())
if __name__=='__main__':unittest.main()
