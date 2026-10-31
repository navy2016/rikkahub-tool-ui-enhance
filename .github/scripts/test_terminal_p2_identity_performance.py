import copy,importlib.util,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('p2_identity_perf_tests',Path(__file__).with_name('terminal-p2-identity-performance.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture(return_ratio=.6,refresh_ratio=1.):
    p=m.protocol();invocations=[]
    for n,order in enumerate(p['matrix']['armOrder']):
        for pos,arm in enumerate(order):
            results=[]
            for case in p['matrix']['cases']:
                rows=[]
                for r in range(p['matrix']['rounds']):
                    for position,condition in enumerate(('refresh','return') if r%2==0 else ('return','refresh')):
                        baseline=100000 if condition=='return' else 10000
                        v=baseline if arm=='legacy' else int(baseline*(return_ratio if condition=='return' else refresh_ratio))
                        rows.append(dict(index=r,position=position,condition=condition,warmup=r==0,identityDecidable=True,
                            noSubmittedAttempt=False,noPresentedAttempt=False,inputToBufferEvidenceNs=v,inputToAttemptNs=v,inputToDisplayEndNs=v,
                            notificationState='ABSENT'))
                results.append(dict(case=case,rows=rows))
            invocations.append(dict(pair=n,position=pos,arm=arm,result=dict(classifications=results)))
    return invocations
class P2IdentityPerformanceContracts(unittest.TestCase):
    def test_frozen_complete_matrix_and_no_automatic_adoption(self):
        r=m.aggregate(fixture());self.assertTrue(r['passesFrozenPerformanceScreen']);self.assertEqual(320,r['windows'])
        self.assertEqual(64,r['warmups']);self.assertFalse(r['productionAdopted'])
    def test_primary_ui_and_system_are_all_required(self):
        for key in m.METRICS:
            inv=fixture()
            for i in inv:
                if i['arm']=='candidate':
                    for group in i['result']['classifications']:
                        for row in group['rows']:
                            if row['condition']=='return':row[key]=200000
            self.assertFalse(m.aggregate(inv)['passesFrozenPerformanceScreen'])
    def test_refresh_guard_is_not_sacrificed_for_return_win(self):
        self.assertFalse(m.aggregate(fixture(refresh_ratio=1.1))['passesFrozenPerformanceScreen'])
    def test_any_formal_null_propagates_instead_of_dropping(self):
        inv=fixture();inv[0]['result']['classifications'][0]['rows'][2]['inputToBufferEvidenceNs']=None
        r=m.aggregate(inv);self.assertFalse(r['dataComplete']);self.assertFalse(r['passesFrozenPerformanceScreen'])
        metric=r['summary'][1]['inputToBufferEvidenceNs'];self.assertIsNone(metric['medianRatio']);self.assertIsNone(metric['arms']['legacy'][0])
    def test_warmup_missing_also_blocks_integrity(self):
        inv=fixture();row=inv[0]['result']['classifications'][0]['rows'][0];row['inputToDisplayEndNs']=None;row['noPresentedAttempt']=True
        r=m.aggregate(inv);self.assertFalse(r['passesFrozenPerformanceScreen']);self.assertTrue(r['summary'][0]['inputToDisplayEndNs']['complete'])
    def test_unknown_identity_cannot_be_accepted_with_numeric_endpoints(self):
        inv=fixture();inv[0]['result']['classifications'][0]['rows'][3]['identityDecidable']=False
        self.assertFalse(m.aggregate(inv)['passesFrozenPerformanceScreen'])
    def test_protocol_and_parser_are_pinned_to_prior_commits(self):
        m.guard();self.assertEqual('3f4b031fb73dc16cdb41ae0775edf30fa664f17d',m.FREEZE_SHA)
        self.assertEqual('767756fdd1a3f069aee3adb98b1a09f014b3d3d2',m.protocol()['identityParserSha'])
    def test_driver_has_same_sampling_mechanism_as_qualified_driver(self):
        original=(m.ROOT/'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalFrameIdentityQualificationTest.kt').read_text()
        expected=original.replace('class TerminalFrameIdentityQualificationTest :','class TerminalP2IdentityPerformanceTest :')
        for name in ('OneThousandHidden','OneThousandShown','TenThousandHidden','TenThousandShown'):
            expected=expected.replace('@Test fun identity'+name+'()', '@Test fun p2Identity'+name+'()')
        expected=expected.replace('val case="identity-$size-', 'val case="p2identity-$size-').replace('for(round in 0..3)','for(round in 0..4)')
        expected=expected.replace('.put("warmup",false)', '.put("warmup",round==0)')
        expected=expected.replace('.put("collectorDiagnosisOnly",true).put("performanceMeasured",false).put("protocolId","terminal-frame-identity-v1")',
            '.put("collectorDiagnosisOnly",false).put("performanceMeasured",true).put("protocolId","terminal-p2-identity-performance-v1")')
        expected=expected.replace('// Qualification endpoint A is a UI attempt, not proof of a submitted buffer.',
            '// Endpoint A is a UI attempt; primary B requires its own exact buffer chain.')
        self.assertEqual(expected,(m.ROOT/m.TEST).read_text())
    def test_cli_entrypoint_after_all_functions(self):
        import ast
        tree=ast.parse(Path(m.__file__).read_text());self.assertIsInstance(tree.body[-1],ast.If)
    def test_swapped_reporter_does_not_change_source_inventory(self):
        import types
        before=m.sources();old=m.ci.real_clock
        try:
            m.ci.real_clock=types.SimpleNamespace(TEST=m.real.TEST)
            self.assertEqual(before,m.sources())
        finally:m.ci.real_clock=old
    def test_prerequisite_does_not_relabel_historical_failure(self):
        p=m.protocol();self.assertEqual('failure',p['qualification']['originalConclusion'])
        source=Path(m.__file__).read_text();self.assertIn('noNewDeviceSamples=True',source)
        self.assertIn('fullResultSha256=ci.digest(full)',source)
        self.assertIn("(ROOT/name).write_bytes(raw)",source)
    def test_missing_or_reordered_pairs_rejected(self):
        for inv in (fixture()[:-1],list(reversed(fixture()))):
            with self.assertRaises(ValueError):m.aggregate(inv)
    def test_warmup_and_window_inventory_cannot_be_altered(self):
        inv=fixture();inv[0]['result']['classifications'][0]['rows'][0]['warmup']=False
        with self.assertRaises(ValueError):m.aggregate(inv)
    def test_three_of_four_primary_wins_required_even_if_median_ratio_low(self):
        inv=fixture()
        for i in inv:
            if i['arm']=='candidate':
                for group in i['result']['classifications']:
                    for row in group['rows']:
                        if row['condition']=='return':row['inputToBufferEvidenceNs']=100 if i['pair']<2 else 101000
        self.assertFalse(m.aggregate(inv)['passesFrozenPerformanceScreen'])
if __name__=='__main__':unittest.main()
