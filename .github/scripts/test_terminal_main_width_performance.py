import ast,base64,copy,hashlib,importlib.util,json,random,re,statistics,types,unittest,zlib
from pathlib import Path
from unittest.mock import patch
s=importlib.util.spec_from_file_location('main_width_perf_tests',Path(__file__).with_name('terminal-main-width-performance.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture():
    p=m.protocol();out=[]
    for pair,order in enumerate(p['matrix']['armOrder']):
        for position,arm in enumerate(order):
            groups=[]
            for case in p['matrix']['cases']:
                rows=[]
                for round,conditions in enumerate(p['matrix']['conditionOrder']):
                    for pos,condition in enumerate(conditions):
                        value={'refresh':30_000_000,'sync':120_000_000,'return':300_000_000}[condition]
                        if arm=='candidate' and condition=='return':value=int(value*.6)
                        rows.append(dict(index=round,position=pos,condition=condition,warmup=round==0,identityDecidable=True,
                            noSubmittedAttempt=False,noPresentedAttempt=False,**{key:value for key in m.METRICS}))
                groups.append(dict(case=case,rows=rows))
            out.append(dict(pair=pair,position=position,arm=arm,result=dict(classifications=groups)))
    return out
def mutate(data,metric,condition,callback):
    for inv in data:
        if inv['arm']=='candidate':
            for group in inv['result']['classifications']:
                for row in group['rows']:
                    if row['condition']==condition and not row['warmup']:row[metric]=callback(row,inv)
class MainWidthPerformanceTests(unittest.TestCase):
    def test_frozen2016_inventory_and_no_adoption(self):
        r=m.aggregate(fixture());self.assertEqual((2016,96,1920),(r['windows'],r['warmups'],r['formalWindows']))
        self.assertTrue(r['dataComplete']);self.assertTrue(r['passesFrozenPerformanceScreen']);self.assertFalse(r['productionAdopted'])
    def test_return_requires_both_buffer_and_ui_gain(self):
        for metric in m.METRICS[:2]:
            data=fixture();mutate(data,metric,'return',lambda row,inv:300_000_000)
            self.assertFalse(m.aggregate(data)['passesFrozenPerformanceScreen'])
    def test_return_gain_cannot_offset_refresh_or_sync_regression(self):
        for condition in ('refresh','sync'):
            for metric in m.METRICS:
                data=fixture();mutate(data,metric,condition,lambda row,inv:int(row[metric]*1.06))
                self.assertTrue(any('MEDIAN_GATE' in x for x in m.aggregate(data)['gateFailures']))
    def test_system_return_is_also_a_guard(self):
        data=fixture();mutate(data,m.METRICS[2],'return',lambda row,inv:320_000_000)
        self.assertFalse(m.aggregate(data)['passesFrozenPerformanceScreen'])
    def test_three_winning_pairs_cannot_be_replaced_by_two_large_gains(self):
        data=fixture();mutate(data,m.METRICS[0],'return',lambda row,inv:10_000_000 if inv['pair']<2 else 301_000_000)
        self.assertFalse(m.aggregate(data)['passesFrozenPerformanceScreen'])
    def test_p95_nearest_rank_is19of20_not_interpolated(self):
        self.assertEqual(dict(median=10.5,p95=19,maximum=20),m.statistics_for(list(range(1,21))))
    def test_p95_failure_with_unchanged_median(self):
        data=fixture();mutate(data,m.METRICS[0],'refresh',lambda row,inv:36_000_000 if row['index']>=19 else 30_000_000)
        r=m.aggregate(data);self.assertTrue(any(':P95_GATE' in s for s in r['gateFailures']))
        first=r['summary'][0][m.METRICS[0]];self.assertEqual(first['median']['medianRatio'],1);self.assertEqual(first['p95']['medianRatio'],1.2)
    def test_single_worst_pair_cannot_be_hidden_by_p95_or_medians(self):
        data=fixture();mutate(data,m.METRICS[0],'refresh',lambda row,inv:200_000_000 if row['index']==20 and inv['pair']==3 else 30_000_000)
        r=m.aggregate(data);self.assertTrue(any(':WORST_PAIR_3' in s for s in r['gateFailures']))
        self.assertEqual(r['summary'][0][m.METRICS[0]]['p95']['medianRatio'],1)
    def test_formal_null_invalidates_all_statistics(self):
        data=fixture();data[0]['result']['classifications'][0]['rows'][3][m.METRICS[0]]=None
        r=m.aggregate(data);self.assertFalse(r['dataComplete'])
        v=next(x for x in r['summary'] if x['case']==m.protocol()['matrix']['cases'][0] and x['condition']=='return')[m.METRICS[0]]
        for name in ('median','p95','maximum'):self.assertIsNone(v[name]['medianRatio'])
        self.assertFalse(r['passesFrozenPerformanceScreen'])
    def test_warmup_missing_invalidates_integrity_not_formal_statistics(self):
        data=fixture();data[0]['result']['classifications'][0]['rows'][0][m.METRICS[2]]=None
        r=m.aggregate(data);self.assertFalse(r['dataComplete']);self.assertTrue(r['summary'][0][m.METRICS[2]]['median']['complete'])
    def test_boolean_is_not_a_timing(self):
        for value in (True,False,0,-1,3.5,None):
            self.assertIsNone(m.statistics_for([value]+[10]*19)['median'])
    def test_missing_reordered_or_relabeled_rows_rejected(self):
        for how in ('launch','case','window','warmup'):
            data=fixture()
            if how=='launch':data.reverse()
            elif how=='case':data[0]['result']['classifications'].reverse()
            elif how=='window':data[0]['result']['classifications'][0]['rows'].pop()
            else:data[0]['result']['classifications'][0]['rows'][0]['warmup']=False
            with self.assertRaises(ValueError):m.aggregate(data)
    def test_ratio_of_paired_statistics_not_pooled_windows(self):
        data=fixture()
        for inv in data:
            multiplier=(1,100,2,200)[inv['pair']]
            for group in inv['result']['classifications']:
                for row in group['rows']:
                    for metric in m.METRICS:row[metric]*=multiplier
        self.assertEqual(next(x for x in m.aggregate(data)['summary'] if x['condition']=='return')[m.METRICS[0]]['median']['medianRatio'],.6)
    def test_frozen_guard_and_source_scope(self):
        m.guard();p=m.protocol();a={'shared':'same',m.TEST:p['driverSha256']};b=dict(a)
        for name,item in m.candidate_files().items():a[name]=item['base'];b[name]=item['candidate']
        manifests=dict(legacy=dict(sources=a),candidate=dict(sources=b));m.verify_sources(manifests)
        b['unauthorized']='change'
        with self.assertRaises(ValueError):m.verify_sources(manifests)
    def test_manifest_source_map_stable_when_reporter_switched(self):
        before=m.sources();old=m.ci.real_clock
        try:
            m.ci.real_clock=types.SimpleNamespace(TEST=m.real.TEST)
            self.assertEqual(before,m.sources())
        finally:m.ci.real_clock=old
    def test_driver_is_exact_old_mechanism_with_frozen_matrix_only_changes(self):
        old=m.ROOT/'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalInFramePerformanceTest.kt'
        text=old.read_text().replace('TerminalInFramePerformanceTest','TerminalMainWidthPerformanceTest').replace('inframePerf','mainWidthPerf').replace('inframeperf-','mainwidthperf-').replace('terminal-inframe-performance-v1','terminal-main-width-performance-v1')
        text=text.replace('for(round in 0..4) {','for(round in 0..20) {').replace('val conditions=when(round) {','val conditions=when(round % 6) {')
        text=text.replace('                    else -> error("Outside frozen five-round inventory")','                    5 -> listOf("sync","return","refresh")\n                    else -> error("Outside frozen condition permutation")')
        text=text.replace('/** ActivityScenario + platform accessibility only: no Compose test owner/clock/idling resources. */','/** A1 versus306,2016frozen windows. Native ActivityScenario/accessibility,never a Compose test clock. */')
        text=text.replace('check(parts.size<=256)','check(parts.size<=1024)')
        self.assertEqual(text,(m.ROOT/m.TEST).read_text())
        self.assertIn('sleep 0.060',text);self.assertIn('val closed=tailEnd',text)
    def test_each_declared_permutation_matches_driver(self):
        p=m.protocol();text=(m.ROOT/m.TEST).read_text()
        self.assertEqual(len(p['matrix']['conditionOrder']),21)
        for i,conditions in enumerate(p['matrix']['conditionOrder']):
            self.assertIn(str(i%6)+' -> listOf('+','.join('"'+v+'"' for v in conditions)+')',text)
    def test_cli_last_and_no_local_builds(self):
        self.assertIsInstance(ast.parse(Path(m.__file__).read_text()).body[-1],ast.If)
        import os
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            for func in (m.prerequisites,m.build,m.experiment):
                with self.assertRaises(ValueError):func()
    def test_case_transport_accepts_more_than256_but_rejects_duplicates(self):
        rng=random.Random(17);records=[];expected=[]
        for case in m.protocol()['matrix']['cases']:
            obj=dict(case=case,payload=''.join(chr(rng.randrange(33,126)) for _ in range(4000)))
            raw=json.dumps(obj).encode();encoded=base64.b64encode(zlib.compress(raw)).decode();parts=[encoded[i:i+15] for i in range(0,len(encoded),15)]
            self.assertGreater(len(parts),256);self.assertLessEqual(len(parts),1024);expected.append(obj)
            records.extend(dict(case=case,part=i+1,parts=len(parts),sha256=m.sha(raw),data=part) for i,part in enumerate(parts))
        text='\n'.join('REAL_CLOCK_PART '+json.dumps(x) for x in records);self.assertEqual(m.case_records(text),expected)
        with self.assertRaises(ValueError):m.case_records(text+'\nREAL_CLOCK_PART '+json.dumps(records[0]))
    def test_interval_union_is_not_double_counted_cpu_time(self):
        self.assertEqual(m.interval_union([dict(startNs=2,endNs=6),dict(startNs=4,endNs=9)],0,10),7)
        self.assertEqual(m.interval_union([dict(startNs=0,endNs=20)],5,10),5)
    def test_reusable_workflow_has_an_on_key_not_yaml11_boolean(self):
        text=(m.ROOT/'.github/workflows/terminal-main-width-performance.yml').read_text()
        self.assertIn("\n'on':\n  workflow_call:",text)
        self.assertNotRegex(text,r'(?m)^true:')
        self.assertIn('mainWidthPerformance',(m.ROOT/'.github/workflows/terminal-pipeline-tests.yml').read_text())
    def test_package_diagnostics_precede_unrelaxed_equality_guard(self):
        text=Path(m.__file__).read_text()
        self.assertIn('preserve_bundle_diagnostics(manifests)\n    assets=pair.verify_bundles(manifests)',text)
        self.assertNotIn('check_files=False',text)
if __name__=='__main__':unittest.main()
