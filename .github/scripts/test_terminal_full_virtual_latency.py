"""Synthetic contract tests only; values never constitute runtime performance evidence."""
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('latency_contract',Path(__file__).with_name('terminal-full-virtual-latency.py'))
gate=importlib.util.module_from_spec(spec);spec.loader.exec_module(gate)

def fixture():
    groups={k:[] for k in ('CASE','SAMPLE','FRAME','MOUNT','MEMORY','CLEANUP')}
    for number,c in enumerate(gate.CASES):
        launch=f'00000000-0000-4000-8000-{number:012d}'
        groups['CASE'].append(dict(case=c,launch=launch,passed=True,samples=132,frames=1,frameBufferOverflow=0,frameReportsDropped=0))
        groups['FRAME'].append(dict(case=c,launch=launch,index=0,totalNs=10,dropped=0))
        for seeded in (True,False):groups['MOUNT'].append(dict(case=c,launch=launch,seedIncluded=seeded,rows=25,columns=80,elapsedNs=100))
        for phase in ('ready',*gate.PHASES):groups['MEMORY'].append(dict(case=c,launch=launch,phase=phase))
        groups['CLEANUP'].append(dict(case=c,launch=launch,closed=True,frameThreadStopped=True))
        for phase in gate.PHASES:
            for i in range(22):
                raw=gate.output_for(c,phase,i,25).encode()
                es=[dict(sequence=1,stage='UI_INPUT',timeNs=2,bytes=1),dict(sequence=2,stage='OUTPUT_READ',timeNs=3,bytes=len(raw)),
                    dict(sequence=3,stage='EMULATOR_FED',timeNs=4,bytes=len(raw)),dict(sequence=4,stage='FRAME_DRAWN',timeNs=5,revision=2,virtual=True)]
                groups['SAMPLE'].append(dict(case=c,launch=launch,phase=phase,iteration=i,warmup=i<2,virtual=True,tui=c.startswith('tui-'),
                    historyRows=int(c.rsplit('-',1)[1]),ime=phase=='imeEcho',outputBytes=len(raw),outputSha256=hashlib.sha256(raw).hexdigest(),
                    startedNs=1,uiNs=2,drawNs=5,checkedNs=6,inputToDrawNs=3,inputToCheckNs=4,beforeRevision=1,fedRevision=2,publishedRevision=2,drawnRevision=2,
                    retainedRows=25,measuredRows=10,writers=0,visibleBefore=10,visibleAfter=10,commonLayouts=10,replacedLayouts=1,changedTexts=1,panelCompositions=1,
                    spans={k:1 for k in ('readToLockNs','captureNs','rowSyncNs','parseWindowNs','uiToNativeReadNs','feedToPublishNs','publishToDrawNs')},events=es))
    return groups
def encode(g):return '\n'.join('FV_LATENCY_'+k+' '+json.dumps(r) for k,rows in g.items() for r in rows)

class LatencyContracts(unittest.TestCase):
    def test_exact_baseline_keeps_all_warmups_and_no_p99_claim(self):
        data=gate.validate(encode(fixture()))
        self.assertEqual(528,len(data['samples']));self.assertEqual(48,sum(r['warmup'] for r in data['samples']))
        values=gate.summary(data);self.assertEqual(24,len(values));self.assertTrue(all(r['n']==20 and r['p99NotEstimated'] for r in values))
    def test_missing_duplicate_bytes_virtual_or_timing_rejected(self):
        for mutate in (lambda g:g['SAMPLE'].pop(),lambda g:g['SAMPLE'].append(g['SAMPLE'][0]),
            lambda g:g['SAMPLE'][0].update(outputBytes=1),lambda g:g['SAMPLE'][0].update(virtual=False),
            lambda g:g['SAMPLE'][0].update(inputToDrawNs=-1),lambda g:g['CLEANUP'][0].update(closed=False)):
            g=fixture();mutate(g)
            with self.assertRaises(ValueError):gate.validate(encode(g))
    def test_listener_and_trace_are_test_only_and_original_production_unchanged(self):
        self.assertEqual(gate.TREE,gate.guard()['productionTree'])
        text=(gate.ROOT/gate.TEST).read_text()
        self.assertIn('repeat(22)',text);self.assertIn('removeOnFrameMetricsAvailableListener',text)
        self.assertIn('frameThread.quitSafely()',text);self.assertIn('ready(ime)',text)
        self.assertNotIn('System.gc()',text)
        self.assertNotIn('after.cursorColumn',text)
        self.assertIn('synchronized(terminal){terminal.cursorColumn()}',text)

    def test_protobuf_frame_reader_requires_complete_target_pid_pair(self):
        def var(n):
            out=bytearray()
            while n>=128:out.append((n&127)|128);n>>=7
            out.append(n);return bytes(out)
        def number(f,n):return var(f<<3)+var(n)
        def message(f,raw):return var((f<<3)|2)+var(len(raw))+raw
        first=number(8,100)+message(76,message(4,number(1,7)+number(4,123)+number(6,1)))
        last=number(8,150)+message(76,message(5,number(1,7)))
        raw=message(1,first)+message(1,last)
        parsed=gate.frame_timeline(raw,123)
        self.assertEqual(1,parsed['completeFrames']);self.assertEqual(50,parsed['frames'][0]['durationNs'])
        self.assertEqual(0,gate.frame_timeline(raw,124)['completeFrames'])
        self.assertEqual(1,gate.frame_timeline(message(1,first),123)['unmatchedStarts'])
        with self.assertRaises(ValueError):gate.frame_timeline(raw[:-1],123)

    def test_system_trace_failure_must_be_retained_not_used_as_fake_presentation(self):
        text=(gate.ROOT/'.github/scripts/run-terminal-pipeline.py').read_text()
        self.assertIn("'-perfetto-start.txt'",text)
        self.assertIn("rawTraceComplete=False",text)
        self.assertIn("'stop-or-retrieval-failed'",text)

if __name__=='__main__':unittest.main()
