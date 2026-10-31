import importlib.util,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('inframe_performance_tests',Path(__file__).with_name('terminal-inframe-performance.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture():
    p=m.protocol();out=[]
    for pair,order in enumerate(p['matrix']['armOrder']):
        for pos,arm in enumerate(order):
            groups=[]
            for case in p['matrix']['cases']:
                rows=[]
                for round,conditions in enumerate(p['matrix']['conditionOrder']):
                    for position,condition in enumerate(conditions):
                        base=100000 if condition=='return' else 120000 if condition=='sync' else 60000
                        value=int(base*.6) if arm=='candidate' and condition=='refresh' else base
                        rows.append(dict(index=round,position=position,condition=condition,warmup=round==0,identityDecidable=True,
                            noSubmittedAttempt=False,noPresentedAttempt=False,**{key:value for key in m.METRICS}))
                groups.append(dict(case=case,rows=rows))
            out.append(dict(pair=pair,position=pos,arm=arm,result=dict(classifications=groups)))
    return out
class InFramePerformanceContracts(unittest.TestCase):
    def test_frozen480_window_matrix_and_no_adoption(self):
        result=m.aggregate(fixture());self.assertEqual(480,result['windows']);self.assertEqual(96,result['warmups'])
        self.assertTrue(result['passesFrozenPerformanceScreen']);self.assertFalse(result['productionAdopted'])
    def test_sync_and_return_regressions_cannot_be_hidden_by_refresh(self):
        for condition in ('sync','return'):
            for metric in m.METRICS:
                data=fixture()
                for inv in data:
                    if inv['arm']=='candidate':
                        for g in inv['result']['classifications']:
                            for row in g['rows']:
                                if row['condition']==condition:row[metric]*=2
                self.assertFalse(m.aggregate(data)['passesFrozenPerformanceScreen'])
    def test_refresh_needs_both_primary_and_ui_gain(self):
        for key in m.METRICS:
            data=fixture()
            for inv in data:
                if inv['arm']=='candidate':
                    for group in inv['result']['classifications']:
                        for row in group['rows']:
                            if row['condition']=='refresh':row[key]=100000
            self.assertFalse(m.aggregate(data)['passesFrozenPerformanceScreen'])
    def test_formal_null_propagates(self):
        data=fixture();data[0]['result']['classifications'][0]['rows'][3]['inputToBufferEvidenceNs']=None
        result=m.aggregate(data);self.assertFalse(result['dataComplete']);self.assertFalse(result['passesFrozenPerformanceScreen'])
        target=next(r for r in result['summary'] if r['case']==m.protocol()['matrix']['cases'][0] and r['condition']=='return')
        self.assertIsNone(target['inputToBufferEvidenceNs']['medianRatio'])
    def test_warmup_missing_also_rejects_integrity(self):
        data=fixture();data[0]['result']['classifications'][0]['rows'][0]['inputToDisplayEndNs']=None
        result=m.aggregate(data);self.assertFalse(result['dataComplete']);self.assertTrue(result['summary'][0]['inputToDisplayEndNs']['complete'])
    def test_cannot_remove_reorder_or_relabel(self):
        data=fixture();data[0]['result']['classifications'][0]['rows'][0]['warmup']=False
        for value in (data,fixture()[:-1],list(reversed(fixture()))):
            with self.assertRaises(ValueError):m.aggregate(value)
    def test_three_refresh_pair_wins_required(self):
        data=fixture()
        for inv in data:
            if inv['arm']=='candidate':
                for group in inv['result']['classifications']:
                    for row in group['rows']:
                        if row['condition']=='refresh':row['inputToBufferEvidenceNs']=10 if inv['pair']<2 else 61000
        self.assertFalse(m.aggregate(data)['passesFrozenPerformanceScreen'])
    def test_source_scope_allows_only_one_changed_and_one_added_file(self):
        m.guard();spec=m.candidate.spec()['files'];shared={'common':'fixed'}
        a=dict(shared);b=dict(shared)
        for name,h in spec.items():
            if h['base'] is not None:a[name]=h['base']
            b[name]=h['candidate']
        manifests=dict(legacy=dict(sources=a),candidate=dict(sources=b));m.verify_sources(manifests)
        b['extra']='different'
        with self.assertRaises(ValueError):m.verify_sources(manifests)
    def test_old_failed_mechanism_status_is_not_relabeled(self):
        p=m.protocol();self.assertEqual('failure',p['prerequisites']['mechanismOriginalConclusion'])
        self.assertEqual('success',p['prerequisites']['pageConclusion']);self.assertFalse(p['rejectedP2Included'])
    def test_driver_has_exact_frozen_three_action_order_and_clock(self):
        text=(m.ROOT/m.TEST).read_text();p=m.protocol()
        for i,conditions in enumerate(p['matrix']['conditionOrder']):
            self.assertIn(str(i)+' -> listOf('+','.join('"'+c+'"' for c in conditions)+')',text)
        self.assertIn('sleep 0.060',text);self.assertIn('clock=SystemClock::elapsedRealtimeNanos',text)
        self.assertIn('val closed=tailEnd',text);self.assertIn('for(round in 0..4)',text)
        self.assertNotIn('mainClock',text);self.assertNotIn('INFRAME_CALLBACK',text)
    def test_cli_after_all_function_definitions(self):
        import ast
        self.assertIsInstance(ast.parse(Path(m.__file__).read_text()).body[-1],ast.If)
    def test_manifest_sources_stable_across_reporter_switch(self):
        import types
        before=m.sources();old=m.ci.real_clock
        try:
            m.ci.real_clock=types.SimpleNamespace(TEST=m.real.TEST)
            self.assertEqual(before,m.sources())
        finally:m.ci.real_clock=old
if __name__=='__main__':unittest.main()
