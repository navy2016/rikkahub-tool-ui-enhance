import ast,base64,copy,hashlib,importlib.util,json,os,random,re,subprocess,types,unittest,zlib
from pathlib import Path
from unittest.mock import patch
s=importlib.util.spec_from_file_location('b1_perf_tests',Path(__file__).with_name('terminal-physical-metrics-performance.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
def fixture():
    p=m.protocol();out=[]
    for pair,order in enumerate(p['matrix']['armOrder']):
        for position,arm in enumerate(order):
            groups=[]
            for case in p['matrix']['cases']:
                rows=[]
                for n,pos,condition in m.case_order(case):
                    value={'redraw':40_000_000,'refresh':30_000_000,'sync':120_000_000,'return':300_000_000}[condition]
                    if arm=='candidate' and condition=='redraw':value=int(value*.6)
                    rows.append(dict(index=n,position=pos,condition=condition,warmup=n==0,identityDecidable=True,
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
class PhysicalMetricsPerformanceTests(unittest.TestCase):
    def test_complete3360_160_3200_matrix_no_adoption(self):
        r=m.aggregate(fixture());self.assertEqual((3360,160,3200),(r['windows'],r['warmups'],r['formalWindows']))
        self.assertEqual(20,len(r['summary']));self.assertTrue(r['dataComplete']);self.assertTrue(r['passesFrozenPerformanceScreen']);self.assertFalse(r['productionAdopted'])
    def test_redraw_requires_primary_and_ui_gain_in_main_and_alt(self):
        for metric in m.METRICS[:2]:
            data=fixture();mutate(data,metric,'redraw',lambda r,i:40_000_000)
            self.assertFalse(m.aggregate(data)['passesFrozenPerformanceScreen'])
    def test_no_gain_offsets_any_single_sync_or_return_guard(self):
        for condition in ('refresh','sync','return'):
            for metric in m.METRICS:
                data=fixture();mutate(data,metric,condition,lambda r,i:int(r[metric]*1.06))
                self.assertTrue(any('MEDIAN_GATE' in x for x in m.aggregate(data)['gateFailures']))
    def test_system_end_is_guard_even_on_redraw(self):
        data=fixture();mutate(data,m.METRICS[2],'redraw',lambda r,i:43_000_000)
        self.assertFalse(m.aggregate(data)['passesFrozenPerformanceScreen'])
    def test_three_winning_pairs_cannot_be_replaced_by_two_large_gains(self):
        data=fixture();mutate(data,m.METRICS[0],'redraw',lambda r,i:10_000_000 if i['pair']<2 else 40_100_000)
        self.assertFalse(m.aggregate(data)['passesFrozenPerformanceScreen'])
    def test_p95_is_nearest_rank19_of20(self):
        self.assertEqual(m.statistics_for(list(range(1,21))),dict(median=10.5,p95=19,maximum=20))
    def test_tail_failure_despite_unchanged_median(self):
        data=fixture();mutate(data,m.METRICS[0],'refresh',lambda r,i:36_000_000 if r['index']>=19 else 30_000_000)
        self.assertTrue(any(':P95_GATE' in x for x in m.aggregate(data)['gateFailures']))
    def test_one_worst_pair_cannot_be_averaged_away(self):
        data=fixture();mutate(data,m.METRICS[0],'refresh',lambda r,i:200_000_000 if r['index']==20 and i['pair']==3 else 30_000_000)
        self.assertTrue(any(':WORST_PAIR_3' in x for x in m.aggregate(data)['gateFailures']))
    def test_formal_null_propagates_to_all_statistics(self):
        data=fixture();data[0]['result']['classifications'][0]['rows'][1][m.METRICS[0]]=None
        r=m.aggregate(data);self.assertFalse(r['dataComplete'])
        for key in ('median','p95','maximum'):self.assertIsNone(r['summary'][0][m.METRICS[0]][key]['medianRatio'])
    def test_warmup_missing_is_not_dropped_from_integrity(self):
        data=fixture();data[0]['result']['classifications'][0]['rows'][0][m.METRICS[2]]=None
        r=m.aggregate(data);self.assertFalse(r['dataComplete']);self.assertTrue(r['summary'][0][m.METRICS[2]]['median']['complete'])
    def test_invalid_scalar_times_are_not_numbers(self):
        for x in (True,False,None,0,-1,1.5):self.assertIsNone(m.statistics_for([x]+[10]*19)['median'])
    def test_wrong_slot_case_window_or_warmup_inventory_rejected(self):
        for how in ('slot','case','window','warmup'):
            d=fixture()
            if how=='slot':d.reverse()
            elif how=='case':d[0]['result']['classifications'].reverse()
            elif how=='window':d[0]['result']['classifications'][0]['rows'].pop()
            else:d[0]['result']['classifications'][0]['rows'][0]['warmup']=False
            with self.assertRaises(ValueError):m.aggregate(d)
    def test_statistics_are_paired_not_pooled(self):
        d=fixture()
        for inv in d:
            for group in inv['result']['classifications']:
                for row in group['rows']:
                    for key in m.METRICS:row[key]*=(1,100,2,200)[inv['pair']]
        self.assertEqual(.6,m.aggregate(d)['summary'][0][m.METRICS[0]]['median']['medianRatio'])
    def test_separate_short_redraw_and_guard_processes_keep_trace_caps(self):
        p=m.protocol();spec=p['matrix']['caseSpecs'];self.assertEqual(12,len(spec));self.assertEqual(96,p['matrix']['rawTraceFiles'])
        self.assertEqual([21]*8+[63]*4,[v['windows'] for v in spec.values()])
        self.assertEqual(100663296,p['serialization']['traceBytesMaxExclusive'])
        self.assertIn('duration_ms: 240000',(m.ROOT/'benchmarks/terminal-real-clock/trace.pbtxt').read_text())
    def test_all_redraw_bytes_fit_and_match_shell_printf_format(self):
        suffix=m.protocol()['payload']['suffix'];fmt=''.join(f'\\033[{row};1H\\033[2K'+
            ('%s\\r' if row==24 else f'\\033[1;3;{31+row%6}mr{row:02d} %s{suffix}\\033[0m\\r') for row in range(1,25))
        for case in m.protocol()['matrix']['cases'][:8]:
            for n in (0,20):
                marker=f'DRAW:{case}:{n:02d}:0'
                # Native shell output only; no Kotlin/local Android compilation.
                raw=subprocess.check_output(['sh','-c','printf "$1" '+ ' '.join(['"$2"']*24),'printf-probe',fmt,marker])
                expected=m.response(case,n,0,'redraw');self.assertEqual(expected,raw);self.assertLessEqual(len(raw),4096)
                self.assertEqual(24,raw.count(b'\x1b[2K'));self.assertNotIn(b'\n',raw)
    def test_guards_keep_old_finite_single_row_sync_and_return_protocol(self):
        for case in m.protocol()['matrix']['cases'][8:]:
            for n,pos,cond in m.case_order(case):
                marker=f'{"SYNC" if cond=="sync" else "ECHO"}:{case}:{n:02d}:{pos}'
                expected=(('\x1b[?1049l' if cond=='return' else '\x1b[?2026h' if cond=='sync' else '')+
                    '\x1b[24;1H\x1b[2K'+marker+'\r'+('\x1b[?2026l' if cond=='sync' else '')).encode()
                self.assertEqual(expected,m.response(case,n,pos,cond))
    def test_invalid_case_condition_or_round_cannot_generate_an_output(self):
        for args in (('unknown',0,0,'redraw'),('physicalmain-1000-hidden',21,0,'redraw'),('physicalalt-1000-hidden',1,0,'return')):
            with self.assertRaises(ValueError):m.response(*args)
    def test_native_driver_freezes_setup_and_does_not_claim_unobserved_alt_history(self):
        text=(m.ROOT/m.TEST).read_text()
        self.assertLess(text.index('settle(false,"initial-main")'),text.index('val initialHistory='))
        self.assertIn('.put("sameInitialHistory",if(alternateCohort)JSONObject.NULL else true)',text)
        self.assertIn('settle(false,"after-alt-history")',text)
        self.assertIn('if(redraw) for(row in 1..24)',text);self.assertIn('val closed=tailEnd',text)
        self.assertIn('DRAW:*) printf \'$redrawFormat\' $redrawArguments;;',text)
        self.assertIn('sleep 0.060',text)
        self.assertEqual(m.protocol()['payload']['suffix'],json.loads('"'+re.search(r'private val redrawSuffix = "(.*)"',text)[1]+'"'))
    def test_original_guard_order_matches_driver(self):
        text=(m.ROOT/m.TEST).read_text()
        for case,spec in m.protocol()['matrix']['caseSpecs'].items():
            if spec['cohort']=='guard':
                for i,conditions in enumerate(spec['conditionOrder']):self.assertIn(str(i%6)+' -> listOf('+','.join('"'+v+'"' for v in conditions)+')',text)
    def test_shared_driver_source_map_and_candidate_scope(self):
        m.guard();p=m.protocol();a={m.TEST:p['driverSha256']};b=dict(a)
        for name,item in m.candidate_files().items():a[name]=item['base'];b[name]=item['candidate']
        manifests=dict(legacy=dict(sources=a),candidate=dict(sources=b));m.verify_sources(manifests)
        b['unexpected']='bad'
        with self.assertRaises(ValueError):m.verify_sources(manifests)
    def test_no_local_build_device_or_auto_adoption(self):
        self.assertIsInstance(ast.parse(Path(m.__file__).read_text()).body[-1],ast.If)
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            for action in (m.prerequisites,m.build,m.experiment):
                with self.assertRaises(ValueError):action()
        self.assertFalse(m.protocol()['execution']['automaticProductionAdoption'])
    def test_typed_profile_bound_before_sampling_and_old_code_untouched(self):
        c=m.build_contract();self.assertEqual(c['timingProtocolSha256'],m.PROTOCOL_SHA256)
        self.assertEqual(c['candidateIdentitySha256'],m.protocol()['candidateIdentitySha256'])
        self.assertFalse(c['apkRewriteAllowed'])
        for name in ('.github/scripts/terminal-main-width-performance.py','.github/scripts/run-terminal-pipeline-paired.py'):
            self.assertEqual((m.ROOT/name).read_bytes(),m.git('show','f1192cfd4aaf442aec56dba2c727487a98df60e4:'+name))
    def test_case_fragment_duplicates_are_not_silently_replaced(self):
        rows=[];expected=[]
        for case in m.protocol()['matrix']['cases']:
            value=dict(case=case,payload='finite');raw=json.dumps(value).encode();expected.append(value)
            rows.append(dict(case=case,part=1,parts=1,sha256=m.sha(raw),data=base64.b64encode(zlib.compress(raw)).decode()))
        text='\n'.join('REAL_CLOCK_PART '+json.dumps(v) for v in rows);self.assertEqual(expected,m.case_records(text))
        with self.assertRaises(ValueError):m.case_records(text+'\nREAL_CLOCK_PART '+json.dumps(rows[0]))
    def test_interval_union_does_not_become_cpu_time(self):
        self.assertEqual(7,m.interval_union([dict(startNs=2,endNs=6),dict(startNs=4,endNs=9)],0,10))

if __name__=='__main__':unittest.main()
