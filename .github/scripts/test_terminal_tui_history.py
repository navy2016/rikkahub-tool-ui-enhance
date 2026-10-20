"""Reject missing, forged and misrouted TUI-history candidate evidence without building Android."""
import copy
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import unittest


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rules = load('history_reports', 'terminal-tui-history-reports.py')
perf = load('history_perf', 'terminal-tui-history-performance.py')
ROOT = rules.ROOT


def text(groups):
    return '\n'.join(marker + ' ' + json.dumps(row) for marker, rows in groups for row in rows)


def viewport():
    names = [f'stream{s}' for s in rules.SIZES] + ['lockedFontIme', 'fallbacks']
    rects, streams, gates = [], [], []
    for case in names:
        phases = ('initial', 'after24', 'reentry24') if case.startswith('stream') else ('follow',) if case == 'lockedFontIme' else tuple(f'restore{g}' for g in range(5))
        size = int(case[6:]) if case.startswith('stream') else 1000 if case == 'lockedFontIme' else 128
        for mode in rules.MODES:
            for phase in phases:
                rects.append(dict(case=case, phase=phase, mode=mode, top=281, bottom=308, rowHeight=27, viewport=319,
                    historyRows=size, frameRevision=10, tui=True, virtual=False, grouped=True, effective=mode, writers=1))
    rects.append(dict(rects[-1], phase='default', mode='chunkedLayers', grouped=False, effective='eager'))
    for size in rules.SIZES:
        for mode in rules.MODES:
            streams.append(dict(size=size, mode=mode, initialLines=48, reentryLines=48, detachedLines=8, historyRows=size,
                oldRowsRetained=0, newRowsRetained=0, oldHeightsRetained=0, newHeightsRetained=0))
    gates = [dict(mode=m, gate=g, grouped=False, effective='eager', chunks=0) for m in rules.MODES for g in range(5)]
    return [('TUI_HISTORY_CASE', [dict(case=n, passed=True) for n in names]), ('TUI_HISTORY_RECT', rects),
            ('TUI_HISTORY_STREAM', streams), ('TUI_HISTORY_GATE', gates)]


def pages():
    cases, rectangles, overlaps, cleanups = [], [], [], []
    for mode in rules.MODES:
        rects = [dict(mode=mode, phase=p, ime=p=='imeOpen', top=281, bottom=308, rowHeight=27, viewport=319,
            historyRows=10000, rows=24, draw=(i+1)*100, revision=(i+1)*100, confirmations=2)
            for i,p in enumerate(('initial','imeOpen','imeClosed','reentry','input'))]
        flows = [dict(mode=mode, phase=p, ime=p=='imeOpen', beforeTick=2, afterTick=6, detachedTicks=3 if p=='reentry' else 0,
            elapsedNs=10000, beforeDraw=(i+1)*100, afterDraw=(i+1)*100+10,
            beforeRevision=(i+1)*100, afterRevision=(i+1)*100+10, producerStillRunning=True, historyRows=10000)
            for i,p in enumerate(('imeOpen','imeClosed','reentry'))]
        windows = []
        for p in ('imeOpen','imeClosed','reentry'):
            count = sum(len(f'LIVE:{p}:{i:03d} 中文 e\u0301 👩‍💻\r\nbody:{p}:{i:03d}\r\n'.encode()) for i in range(160)) + len(f'DONE:{p}\r\n'.encode())
            windows.append(dict(phase=p, windows=10, readBytes=count, fedBytes=count, expectedBytes=count, published=10, drawn=10))
        cases.append(dict(suite='terminal-tui-history-page-v1', mode=mode, nativePty=True, rawInput=True, statusAndKeys=True,
            survivingHistoryRetained=True, ptyMode='raw', peerInputDiscipline='canonical-test-peer', historyRows=10000,
            physicalRows=24, traceDropped=0, disposalsBeforeCleanup=1, inputEcho='ACK:bridge', rectangles=rects,
            overlaps=flows, outputWindows=windows))
        rectangles += rects
        overlaps += flows
        cleanups.append(dict(mode=mode, disposals=2, sessionClosed=True, captureClosed=True))
    return [('TUI_HISTORY_PAGE',cases), ('TUI_HISTORY_PAGE_RECT',rectangles), ('TUI_HISTORY_PAGE_OVERLAP',overlaps),
            ('TUI_HISTORY_PAGE_CLEANUP',cleanups)]


def performance():
    samples, disposals, devices = [], [], []
    for size in rules.SIZES:
        devices.append(dict(size=size,pid=8,fingerprint='synthetic-host-input',targetApk='a'*64,testApk='b'*64))
        for round_ in range(5):
            for mode in perf.MODES if round_ % 2 == 0 else perf.MODES[::-1]:
                grouped = mode in rules.MODES
                for phase in perf.PHASES:
                    n = 0 if phase=='cold' else 3
                    r = dict.fromkeys(perf.TIMES+perf.COUNTS,0)
                    r.update(size=size,round=round_,mode=mode,phase=phase,warmup=round_==0,productionModes=True,tui=True,
                        historyAllowed=grouped,effectiveMode=mode if grouped else 'eager',chunks=1 if grouped else 0,
                        firstDrawNs=1000,completedNs=100000000 if grouped else 200000000,
                        commitSpanNs=10000000 if grouped else 30000000,commits=2,rootDraws=3,retainedRows=size+24,writers=1,
                        ticks=n,syncVisited=n*(size+24),publicationNs=[10]*n,feedCaptureNs=4*n,rowSyncNs=6*n,
                        createdNodes=size+24 if phase=='cold' else 8,stateCreateNs=1 if phase=='cold' else 0,
                        initialFrameNs=1 if phase=='cold' else 0,shapeSha256='c'*64,cursorTop=281,cursorBottom=308,rowHeight=27,viewportHeight=319)
                    samples.append(r)
                disposals.append(dict(size=size,round=round_,mode=mode,retainedRows=0,retainedHeightRows=0))
    return [('LONG_PERF_SAMPLE',samples),('LONG_PERF_DISPOSE',disposals),('LONG_PERF_DEVICE',devices)]


class TuiHistoryReportsTest(unittest.TestCase):
    def test_complete_viewport_and_page_evidence(self):
        v = rules.validate_viewport(text(viewport()))
        self.assertEqual((5,31,6,10), tuple(len(v[k]) for k in ('cases','rectangles','streams','gates')))
        p = rules.validate_page(text(pages()))
        self.assertEqual(2,len(p['cases']))
        self.assertEqual(6,sum(len(c['overlaps']) for c in p['cases']))

    def test_missing_duplicate_clipped_or_wrong_renderer_fails(self):
        for group in range(4):
            data=viewport(); data[group][1].pop()
            with self.subTest(group=group), self.assertRaises(ValueError): rules.validate_viewport(text(data))
        for field,value in (('bottom',500),('virtual',True),('grouped',False),('writers',2),('historyRows',24)):
            data=viewport(); data[1][1][0][field]=value
            with self.subTest(field=field), self.assertRaises(ValueError): rules.validate_viewport(text(data))
        data=viewport(); data[2][1][0]['reentryLines']=47
        with self.assertRaises(ValueError): rules.validate_viewport(text(data))
        data=viewport(); data[3][1][0]['chunks']=1
        with self.assertRaises(ValueError): rules.validate_viewport(text(data))

    def test_no_page_success_without_native_overlap_cleanups_and_exact_bytes(self):
        for field,value in (('nativePty',False),('ptyMode','cooked'),('traceDropped',1),('inputEcho','bad'),('physicalRows',25)):
            data=pages(); data[0][1][0][field]=value
            with self.subTest(field=field), self.assertRaises(ValueError): rules.validate_page(text(data))
        for field,value in (('afterTick',160),('afterDraw',100),('producerStillRunning',False),('beforeRevision',999)):
            data=pages(); data[0][1][0]['overlaps'][0][field]=value
            with self.subTest(field=field), self.assertRaises(ValueError): rules.validate_page(text(data))
        data=pages(); data[0][1][0]['overlaps'][2]['detachedTicks']=0
        with self.assertRaises(ValueError): rules.validate_page(text(data))
        data=pages(); data[0][1][0]['outputWindows'][0]['fedBytes']-=1
        with self.assertRaises(ValueError): rules.validate_page(text(data))
        data=pages(); data[3][1].pop()
        with self.assertRaises(ValueError): rules.validate_page(text(data))
        with self.assertRaises(ValueError): rules.validate_page(text(pages())+'\nTUI_HISTORY_PAGE_FAILURE {}')

    def test_performance_keeps_all_fixed_samples_and_checks_four_matching_rounds(self):
        data=perf.validate(text(performance()))
        self.assertEqual((90,45,3),tuple(len(data[k]) for k in ('samples','disposals','devices')))
        summary,decisions=perf.compare(data['samples'])
        self.assertEqual(18,len(summary)); self.assertEqual(12,len(decisions))
        self.assertTrue(all(d['passed'] for d in decisions))
        data['samples'][10]['completedNs']=40000000000
        self.assertIn(40000000000,[r['completedNs'] for r in data['samples']])

    def test_performance_fails_if_any_pair_loses_or_cold_median_regresses(self):
        data=perf.validate(text(performance()))
        row=next(r for r in data['samples'] if (r['size'],r['round'],r['mode'],r['phase'])==(5000,1,rules.MODES[0],'streamIme3'))
        row['completedNs']=201000000
        self.assertFalse(all(d['passed'] for d in perf.compare(data['samples'])[1]))
        data=perf.validate(text(performance()))
        for r in data['samples']:
            if r['mode']==rules.MODES[0] and r['phase']=='cold': r['completedNs']=300000000
        self.assertFalse(all(d['passed'] for d in perf.compare(data['samples'])[1]))

    def test_performance_rejects_wrong_gates_sources_order_and_partial_output(self):
        for field,value in (('effectiveMode','eager'),('historyAllowed',False),('productionModes',False),('ticks',24),
                             ('syncSkipped',5),('shapeSha256','d'*64),('writers',2),('completedNs',True)):
            data=performance(); data[0][1][2][field]=value
            with self.subTest(field=field), self.assertRaises(ValueError): perf.validate(text(data))
        for data in (performance()[:-1],):
            with self.assertRaises(ValueError): perf.validate(text(data))
        data=performance(); data[0][1].reverse()
        with self.assertRaises(ValueError): perf.validate(text(data))
        data=performance(); data[1][1][0]['retainedRows']=1
        with self.assertRaises(ValueError): perf.validate(text(data))
        with self.assertRaises(ValueError): perf.validate(text(performance())+'\nLONG_PROBE_SAMPLE {}')

    def test_all_candidate_methods_wired_without_relaxing_old_oracles(self):
        methods=lambda p:set(re.findall(r'@Test\s+fun\s+(\w+)\(', (ROOT/p).read_text()))
        self.assertEqual(set(rules.VIEW_METHODS),methods(rules.VIEW_FILE))
        self.assertEqual(set(rules.PAGE_METHODS),methods(rules.PAGE_FILE))
        unit=(ROOT/'app/src/test/java/me/rerere/rikkahub/data/container/TerminalTuiHistoryTest.kt').read_text()
        self.assertEqual(6,len(re.findall(r'@Test\s+fun',unit)))
        workflow=(ROOT/'.github/workflows/terminal-emulator-tests.yml').read_text()
        self.assertEqual(1,workflow.count('--tests me.rerere.rikkahub.data.container.TerminalTuiHistoryTest'))
        self.assertEqual(1,workflow.count('--require-suite me.rerere.rikkahub.data.container.TerminalTuiHistoryTest'))
        run=(ROOT/'benchmarks/terminal-viewport-tests/run-ci.sh').read_text()
        self.assertEqual(1,run.count('run_arm tui-history TerminalTuiHistoryInstrumentedTest'))
        pipe=(ROOT/'.github/scripts/run-terminal-pipeline.py').read_text()
        for value in (rules.PAGE_FILE,"methods = history_reports.PAGE_METHODS","run('tuiHistoryOnly')"):
            self.assertIn(value,pipe)
        long=(ROOT/'.github/workflows/terminal-long-history.yml').read_text()
        self.assertIn('terminal-tui-history-performance.py',long)
        self.assertIn('performanceEvidence --page 2',long)
        self.assertIn('git diff --exit-code '+rules.BASELINE,long)
        page=(ROOT/rules.PAGE_FILE).read_text()
        self.assertLess(page.index('terminal.maxScrollbackLines() == 10000'),page.index('send("SEED")'))
        self.assertIn('assertFalse("Interaction happened after output ended"',page)
        self.assertIn('synchronized(trace)',page)
        self.assertIn('assertEquals("Trace overflow invalidates phase provenance", 0L, trace.dropped)',page)
        prod=(ROOT/'app/src/main/java/me/rerere/rikkahub/data/container/TerminalRenderMode.kt').read_text()
        self.assertIn('val DEFAULT = CHUNKED_LAYERS',prod)
        self.assertEqual(5,len(rules.CHANGED_MAIN))

    def test_correction_keeps_compatible_old_sources_and_uses_platform_input(self):
        negative=load('history_negative','terminal-negative-control-sources.py')
        self.assertEqual(rules.BASELINE,negative.COMPAT)
        names=subprocess.check_output(['git','ls-tree','-r','--name-only',negative.COMPAT,'--',negative.TESTS],cwd=ROOT,text=True).splitlines()
        self.assertFalse(any('TerminalLongHistoryDiagnosticTest' in p or 'TerminalTuiHistoryInstrumentedTest' in p for p in names))
        for name in negative.ORACLES:
            self.assertEqual((ROOT/name).read_bytes(),subprocess.check_output(['git','show',negative.COMPAT+':'+name],cwd=ROOT))
        page=(ROOT/rules.PAGE_FILE).read_text()
        self.assertNotIn('uiautomator',page)
        self.assertNotIn('UiDevice',page)
        self.assertIn('automation.injectInputEvent(event, true)',page)
        self.assertIn('finally { event.recycle() }',page)
        pattern=re.search(r'val ticks = Regex\("(.*?)"\)',page)[1]
        regex=pattern.replace('$phase','imeOpen').replace('\\\\','\\')
        self.assertEqual('003',re.search(regex,'LIVE:imeOpen:003 中文')[1])


if __name__ == '__main__': unittest.main()
