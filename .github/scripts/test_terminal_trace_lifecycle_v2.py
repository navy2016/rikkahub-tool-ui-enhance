import copy,importlib.util,json,os,subprocess,unittest
from pathlib import Path
from unittest.mock import patch
s=importlib.util.spec_from_file_location('trace_lifecycle',Path(__file__).with_name('terminal-trace-lifecycle-v2.py'))
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)

class TraceLifecycleTests(unittest.TestCase):
    def test_protocol_is_unchanged306_and_two_fixed_launches(self):
        p=m.guard();self.assertEqual(p['baseProductionSha'],'306e0b80b508c961bc00f2d4b34a2688e5857a1a')
        self.assertEqual(len(m.METHODS),12);self.assertEqual(p['matrix']['launches'],2)
        self.assertEqual(sum(s['windows'] for s in p['matrix']['caseSpecs'].values()),420)
        self.assertEqual(p['matrix']['windows'],840);self.assertEqual(p['matrix']['formalWindows'],800)
    def test_no_candidate_performance_or_adoption_permission(self):
        p=m.protocol()
        for name in ('candidateIncluded','productionChanged','optimizationMeasured','performanceAccepted','formalReleaseBuilt','localApkDownload'):
            self.assertIs(p[name],False)
        self.assertTrue(p['acceptance']['allTraceFaultsFatal']);self.assertTrue(p['acceptance']['noAutomaticPerformanceDispatch'])
    def test_only_transport_version_metadata_differs_from_V1(self):
        old=json.loads((m.ROOT/'benchmarks/terminal-trace-lifecycle/protocol.json').read_bytes());new=m.protocol()
        allowed={'id','priorClosureSha','driverSha256','transportCorrection'}
        self.assertEqual({k:v for k,v in old.items() if k not in allowed},{k:v for k,v in new.items() if k not in allowed})
        self.assertEqual(new['transportCorrection']['previousObservedWindows'],0)
        self.assertEqual(new['transportCorrection']['previousNativeTraceStarts'],0)
        self.assertEqual((m.BASE/'trace.pbtxt').read_bytes(),(m.ROOT/'benchmarks/terminal-trace-lifecycle/trace.pbtxt').read_bytes())
    def test_recipe_only_removes_process_stats_not_frame_clock_or_buffer_rules(self):
        p=m.protocol();before=(m.ROOT/'benchmarks/terminal-real-clock/trace.pbtxt').read_text();after=(m.BASE/'trace.pbtxt').read_text()
        line='data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }\n'
        self.assertEqual(before.replace(line,''),after)
        for token in ('size_kb: 32768','duration_ms: 240000','max_file_size_bytes: 100663296','file_write_period_ms: 1000','android.surfaceflinger.frametimeline','sched/sched_switch'):
            self.assertIn(token,after)
    def test_native_start_after_setup_and_stop_before_teardown(self):
        text=m.ASSET.read_text();self.assertLess(text.index('settle(alternateCohort,"initial")'),text.index('requireNotNull(perfetto).start()'))
        self.assertLess(text.index('val traceReceipt=requireNotNull(perfetto).stop()'),text.index('val finalMain=terminal.renderFrame()'))
        self.assertLess(text.index('val traceReceipt=requireNotNull(perfetto).stop()'),text.index('owner.onActivity{activity->listener?'))
        self.assertIn('perfetto=TraceCapture(case)\n            requireNotNull(perfetto).start()',text)
        self.assertIn('executeShellCommandRwe',text);self.assertIn('pool.shutdownNow()',text)
    def test_native_shell_exit_receipt_survives_inner_exit(self):
        body='(exit 72); rc=$?; printf "\\nTRACE_SHELL_EXIT:%s\\n" "$rc"; exit "$rc"'
        result=subprocess.run(['sh','-c',body],capture_output=True,text=True,timeout=5)
        self.assertEqual(result.returncode,72);self.assertEqual(result.stdout,'\nTRACE_SHELL_EXIT:72\n')
    @staticmethod
    def shell_program(command,config=''):
        delimiter='TRACE_CONFIG_EOF_306_2026'
        assert delimiter not in config.splitlines()
        invocation=command if not config else command+" <<'"+delimiter+"'\n"+config+('' if config.endswith('\n') else '\n')+delimiter
        return '(\n'+invocation+'\n); rc=$?; printf "\\nTRACE_SHELL_EXIT:%s\\n" "$rc"; exit "$rc"\n'
    def test_original_Runtime_exec_tokenization_reproduces_quote_failure(self):
        result=subprocess.run("sh -c 'printf example'".split(),capture_output=True,text=True,timeout=5)
        self.assertNotEqual(result.returncode,0);self.assertIn('quote',result.stderr.lower())
    def test_V2_sends_only_sh_as_command_and_program_over_stdin(self):
        source=m.ASSET.read_text();self.assertIn('executeShellCommandRwe("sh")',source)
        self.assertNotIn('executeShellCommandRwe(wrapped)',source)
        self.assertIn('it.write(program.toByteArray(Charsets.UTF_8))',source)
        result=subprocess.run(['sh'],input=self.shell_program('printf example'),capture_output=True,text=True,timeout=5)
        self.assertEqual(result.returncode,0);self.assertEqual(result.stdout,'example\nTRACE_SHELL_EXIT:0\n')
    def test_V2_quoted_heredoc_delivers_exact_config_without_expansion(self):
        for config in ((m.BASE/'trace.pbtxt').read_text(),'"quotes" $HOME `uname` 中文\n'):
            result=subprocess.run(['sh'],input=self.shell_program('cat',config),capture_output=True,text=True,timeout=5)
            self.assertEqual(result.returncode,0);self.assertEqual(result.stdout,config+'\nTRACE_SHELL_EXIT:0\n')
    def test_V2_program_records_failure_status_and_rejects_delimiter_collision(self):
        result=subprocess.run(['sh'],input=self.shell_program('exit 72'),capture_output=True,text=True,timeout=5)
        self.assertEqual(result.returncode,72);self.assertEqual(result.stdout,'\nTRACE_SHELL_EXIT:72\n')
        with self.assertRaises(AssertionError):self.shell_program('cat','TRACE_CONFIG_EOF_306_2026\n')
    def test_no_local_build_or_android_execution(self):
        with patch.dict(os.environ,{'GITHUB_ACTIONS':'false'}):
            for action in (m.build,m.run):
                with self.assertRaisesRegex(ValueError,'CI-only'):action()
    def test_driver_only_overlay_restores_on_error(self):
        before=m.sources()
        with self.assertRaisesRegex(RuntimeError,'qualification host failure'):
            with m.driver_overlay():
                self.assertEqual((m.ROOT/m.TEST).read_bytes(),m.ASSET.read_bytes())
                self.assertEqual(before,m.sources());raise RuntimeError('qualification host failure')
        self.assertFalse((m.ROOT/m.TEST).exists());self.assertEqual(before,m.sources());m.guard()
    def test_all_payloads_and_permutation_counts_are_bounded(self):
        p=m.protocol();windows=0
        for name,spec in p['matrix']['caseSpecs'].items():
            self.assertEqual(len(m.case_order(name)),spec['windows'])
            for n,pos,condition in m.case_order(name):
                raw=m.response(name,n,pos,condition);self.assertLessEqual(len(raw),4096);windows+=1
                if condition=='redraw':self.assertEqual(raw.count(b'\x1b[2K'),24)
        self.assertEqual(windows,420)
    def test_invalid_case_or_response_not_accepted(self):
        for args in (('unknown',0,0,'redraw'),(next(iter(m.protocol()['matrix']['caseSpecs'])),99,0,'redraw')):
            with self.assertRaises(ValueError):m.response(*args)
    def lifecycle(self):
        p=m.protocol();cases=[];starts=[];stops=[];cleanup=[]
        for case in p['matrix']['cases']:
            remote='/data/misc/perfetto-traces/lifecycle-'+case+'.pftrace'
            stop=dict(case=case,remote=remote,tracePid=123,startExit=0,stopExit=0,stoppedBeforeTeardown=True,
                configSha256=p['traceConfigSha256'],startBeginBootNs=1,startedBootNs=2,stopBeginBootNs=110,
                stoppedBootNs=120,bytes=1024,sha256='a'*64)
            start=dict(case=case,remote=remote,pid=123,exit=0,configSha256=p['traceConfigSha256'],beginBootNs=1,startedBootNs=2)
            samples=[dict(beganNs=3,closedBootNs=90,observedNs=100)]
            cases.append(dict(case=case,traceCapture=stop.copy(),samples=samples,historyVerifiedAtBootNs=130))
            starts.append(start);stops.append(stop);cleanup.append(dict(case=case,traceStopped=True,teardownStartedBootNs=140))
        return cases,cleanup,starts,stops
    @staticmethod
    def text(starts,stops):return '\n'.join('REAL_CLOCK_'+tag+' '+json.dumps(x) for tag,rows in (('TRACE_START',starts),('TRACE_STOP',stops)) for x in rows)
    def test_complete_trace_lifecycle_receipts_are_required(self):
        cases,cleanup,starts,stops=self.lifecycle();a,b=m.validate_lifecycle(cases,cleanup,self.text(starts,stops));self.assertEqual(len(a),12)
        for rows in (stops[:-1],stops+[stops[0]]):
            with self.assertRaises(ValueError):m.validate_lifecycle(cases,cleanup,self.text(starts,rows))
    def test_stop_after_teardown_or_before_window_is_rejected(self):
        for key,value in (('stopBeginBootNs',89),('stoppedBootNs',131),('startedBootNs',4),('startExit',1),('stopExit',72),
            ('stoppedBeforeTeardown',False),('bytes',100663296),('sha256','bad'),('configSha256','bad')):
            cases,cleanup,starts,stops=self.lifecycle();cases[0]['traceCapture'][key]=value;stops[0][key]=value
            with self.assertRaises(ValueError):m.validate_lifecycle(cases,cleanup,self.text(starts,stops))
    def test_cleanup_must_happen_after_stop(self):
        for key,value in (('traceStopped',False),('teardownStartedBootNs',119)):
            cases,cleanup,starts,stops=self.lifecycle();cleanup[0][key]=value
            with self.assertRaises(ValueError):m.validate_lifecycle(cases,cleanup,self.text(starts,stops))
    def test_real_trace_faults_are_not_exempted_by_window_position(self):
        for fault in ('packet-loss','unclosed-relevant-frame-span'):
            index=m.identity.FrameIndex(dict(pid=1,spans=[],surfaces=[],displays=[],faults=[fault]))
            result=m.identity.attempt(index,dict(seq=1,revision=1,ns=100),[[],[]])
            self.assertEqual(result['reason'],'TRACE_INTEGRITY');self.assertEqual(result['submission'],'UNKNOWN')
    def test_no_callback_or_partial_chain_can_replace_endpoint(self):
        row=dict(identityDecidable=True,noSubmittedAttempt=False,noPresentedAttempt=False,**{k:10 for k in m.METRICS})
        self.assertTrue(m.complete_window(row))
        for key,value in [('identityDecidable',False),('noSubmittedAttempt',True),('noPresentedAttempt',True)]+[(k,None) for k in m.METRICS]:
            bad=copy.deepcopy(row);bad[key]=value;self.assertFalse(m.complete_window(bad))
    def test_originals_never_trimmed_and_single_bounded_summary(self):
        source=Path(m.__file__).read_text()
        self.assertIn('identity.parse_trace(raw,case[\'pid\'])',source)
        self.assertNotIn('trace[\'faults\'] =',source)
        self.assertNotIn('filter_packets',source)
        self.assertIn("ci.notice('notice','Trace lifecycle qualification'",source)
        self.assertNotIn('legacy.compact(',source)

if __name__=='__main__':unittest.main()
