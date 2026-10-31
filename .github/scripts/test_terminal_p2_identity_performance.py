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
