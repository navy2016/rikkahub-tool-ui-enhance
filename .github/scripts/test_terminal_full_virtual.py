"""Synthetic host contract fixtures only, never Android acceptance or performance evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import re
import unittest
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location('full_virtual_contract',Path(__file__).with_name('terminal-full-virtual-reports.py'))
gate=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(gate)
ROOT=gate.ROOT


def encode(groups):
    return '\n'.join(marker+' '+json.dumps(row) for marker,rows in groups for row in rows)


def viewport():
    rects=[]
    for s in (1000,5000,10000):
        for kind in ('ordinary','tui'):
            for phase in ('initial','stream24','sel','mouse','fullGrid','alt','main'):
                rects.append(dict(case=f'{s}-{kind}',phase=phase,mode='fullVirtual',virtual=True,tui=kind=='tui' or phase in ('fullGrid','alt'),
                    ime=False,selection=phase=='sel',pan=phase not in ('mouse','fullGrid','alt'),alternate=phase=='alt',
                    historyRows=0 if phase=='alt' else s,screenRows=24,rowStates=24,measuredRows=12,writers=1,
                    viewportMode='SCREEN' if kind=='tui' else 'TAIL',cursorChecked=phase!='alt',
                    top=200,bottom=224,rowHeight=24,viewport=240))
    for case,phase in (('locked','ime'),('locked','remount'),('pointer','offscreen-origin')):
        rects.append(dict(rects[0],case=case,phase=phase,cursorChecked=False))
    order=[(s,r,m) for s in (1000,5000,10000) for r in range(5)
           for m in (('tuiChunkedLayers','fullVirtual') if r%2==0 else ('fullVirtual','tuiChunkedLayers'))]
    samples=[dict(size=s,round=r,mode=m,warmup=r==0,coldNs=100000000 if m=='fullVirtual' else 200000000,
        stateNs=1000,streamNs=100000000 if m=='fullVirtual' else 200000000,historyRows=s,
        rowStates=24 if m=='fullVirtual' else s+24,measuredRows=12 if m=='fullVirtual' else s+24,writers=1,
        cursorTop=200,cursorBottom=224,rowHeight=24,viewport=240) for s,r,m in order]
    return [('FULL_VIRTUAL_CASE',[dict(case=k,passed=True) for k in ('scenes','anchors','copy','drag','mouse','handoff','performance')]),
        ('FULL_VIRTUAL_RECT',rects),('FULL_VIRTUAL_COPY',[dict(word=True,selectAll=True,allRows=10024,virtual=True,bytes=5000,sha256='a'*64,measuredRows=12)]),
        ('FULL_VIRTUAL_DRAG',[dict(startRow=500,endRow=530,firstVisible=510,bytes=400,sha256='b'*64,virtual=True,measuredRows=12,writers=1)]),
        ('FULL_VIRTUAL_PERF',samples),('FULL_VIRTUAL_DISPOSE',[dict(size=s,round=r,mode=m,measuredRows=0,pointerRows=0) for s,r,m in order]),
        ('FULL_VIRTUAL_DEVICE',[dict(pid=100,fingerprint='synthetic-host-contract',targetApk='c'*64,testApk='d'*64)])]


def pages():
    cases,rects,kernels,flows,sizes=[],[],[],[],[]
    for kind in ('ordinary','tui'):
        dims=[]
        for i,phase in enumerate(gate.PAGE_PHASES):
            alt=phase=='alternate';ime=phase=='imeOpen';height=130 if ime else 319
            dims.append(dict(kind=kind,mode='fullVirtual',phase=phase,virtual=True,fullVirtual=True,ime=ime,
                selection=phase=='selection',pan=phase!='mouse',alternate=alt,tui=kind=='tui' or alt,
                cursorChecked=not alt,top=0 if alt else 50,bottom=0 if alt else 77,rowHeight=0 if alt else 27,viewport=height,
                historyRows=0 if alt else 10000,rows=24,rowStates=24,measuredUiRows=8,columns=80,ackColumns=80,snapshotColumns=80,
                ackRows=24,snapshotRows=24,desiredRows=24,pendingResize=False,measuredRows=height//13,cellHeight=13,
                gridSource='measured' if i==0 else 'preserved',request=2,timeNs=(i+1)*1000000,
                draw=(i+1)*100,firstStableDraw=(i+1)*100-1,confirmations=2))
        readings=[]
        for phase in ('initial',*gate.BURSTS,'input'):
            d=next(d for d in dims if d['phase']==phase)
            readings.append(dict(kind=kind,mode='fullVirtual',phase=phase,ime=phase=='imeOpen',beforeNs=d['timeNs']+100,
                afterNs=d['timeNs']+200,rows=24,modelRows=24,snapshotRows=24,columns=80,modelColumns=80,snapshotColumns=80,
                beforeRequest=2,request=2,pendingResize=False,envRows=24,envColumns=80,reply=f'SIZE:{phase}:24 80;ENV:24 80\r\n'))
        overlaps=[dict(kind=kind,mode='fullVirtual',phase=phase,producerStillRunning=True,ime=phase=='imeOpen',beforeTick=2,afterTick=6,
            beforeDraw=10,afterDraw=20,beforeRevision=10,afterRevision=20,beforeRows=24,afterRows=24,historyRows=10000,
            beforeRequest=2,afterRequest=2,detachedTicks=3 if phase=='reentry' else 0) for phase in gate.BURSTS]
        windows=[]
        for phase in gate.BURSTS:
            count=sum(len(f'LIVE:{phase}:{i:03d} 中文 e\u0301 👩‍💻\r\nbody:{phase}:{i:03d}\r\n'.encode()) for i in range(160))+len(f'DONE:{phase}\r\n'.encode())
            windows.append(dict(phase=phase,readBytes=count,fedBytes=count,expectedBytes=count,windows=10,published=12,drawn=14))
        timeline=[dict(kind=kind,mode='fullVirtual',phase='initial',timeNs=1,request=1,rows=32,columns=80,mounted=True),
                  dict(kind=kind,mode='fullVirtual',phase='initial',timeNs=2,request=2,rows=24,columns=80,mounted=True),
                  dict(kind=kind,mode='fullVirtual',phase='reentry',timeNs=3,request=2,rows=24,columns=80,mounted=False)]
        cases.append(dict(kind=kind,mode='fullVirtual',suite='terminal-full-virtual-page-v1',physicalRows=24,
            ptyMode='raw' if kind=='tui' else 'cooked',peerInputDiscipline='canonical-test-peer',nativePty=True,rawInput=True,statusAndKeys=True,
            survivingHistoryRetained=True,traceDropped=0,historyRows=10000,disposalsBeforeCleanup=1,inputEcho='ACK:bridge',
            gridContract='measured-target-ack-kernel-v1',baselineRequest=2,rectangles=dims,kernelReadings=readings,
            overlaps=overlaps,outputWindows=windows,resizeObservations=timeline))
        rects+=dims;kernels+=readings;flows+=overlaps;sizes+=timeline
    return [('FULL_VIRTUAL_PAGE',cases),('FULL_VIRTUAL_PAGE_RECT',rects),('FULL_VIRTUAL_PAGE_KERNEL',kernels),
        ('FULL_VIRTUAL_PAGE_OVERLAP',flows),('FULL_VIRTUAL_PAGE_SIZE',sizes),
        ('FULL_VIRTUAL_PAGE_CLEANUP',[dict(kind=k,sessionClosed=True,captureClosed=True,disposals=2) for k in ('ordinary','tui')]),
        ('FULL_VIRTUAL_PAGE_COPY',[dict(kind=k,source='unchanged-output-COPY',fullVirtual=True,bytes=100000,sha256='f'*64) for k in ('ordinary','tui')])]


def correction():
    first=viewport()
    current=[('FULL_VIRTUAL_CASE',[c for c in first[0][1] if c['case'] in ('copy','drag')]), first[2], first[3]]
    first[0]=(first[0][0],[c for c in first[0][1] if c['case'] not in ('copy','drag')])
    first=[group for group in first if group[0] not in ('FULL_VIRTUAL_COPY','FULL_VIRTUAL_DRAG')]
    current += [('FULL_VIRTUAL_HANDLE_FRAMES',[dict(moves=220,events=222,frames=222,screenCoordinates=True)]),
                ('FULL_VIRTUAL_HANDLE_INPUT',[dict(screenStartX=40,screenStartY=100,screenEndX=90,screenEndY=340,popupWindowX=12,popupWindowY=12)]),
                ('FULL_VIRTUAL_SELECTION_DEVICE',[dict(case=k,pid=10,fingerprint='host-contract-only',targetApk='e'*64,testApk='f'*64) for k in ('copy','drag')])]
    return first,current


class FullVirtualReportTest(unittest.TestCase):
    def test_correction_uses_five_old_methods_two_new_and_original_samples(self):
        first,current=correction()
        data=gate.merge_selection_correction(encode(first),encode(current))
        self.assertEqual(30,len(data['samples']))
        self.assertEqual(2,len(data['selectionDevices']))
        self.assertTrue(data['performanceAccepted'])
        for group in range(len(current)):
            a,b=correction(); b[group][1].pop()
            with self.subTest(group=group), self.assertRaises(ValueError):gate.merge_selection_correction(encode(a),encode(b))

    def test_correction_cannot_resample_or_hide_a_different_initial_failure(self):
        first,current=correction()
        current.append(('FULL_VIRTUAL_PERF',[viewport()[4][1][0]]))
        with self.assertRaises(ValueError):gate.merge_selection_correction(encode(first),encode(current))
        first,current=correction();first[0][1].pop()
        with self.assertRaises(ValueError):gate.merge_selection_correction(encode(first),encode(current))
        first,current=correction();current[3][1][0]['frames']=223
        with self.assertRaises(ValueError):gate.merge_selection_correction(encode(first),encode(current))

    def test_correction_keeps_frozen_production_and_has_exact_two_case_ci(self):
        # Frozen historical gate: later authorized production fixes must not rewrite its conclusion.
        original_git=gate.git
        def archived_git(*args):
            if args==('diff','--name-only','HEAD','--','app/src/main'):return b''
            return original_git(*(v.replace('HEAD','fa16a20c9fa2c1387d5c7519bcb2826dae3bacfd') for v in args))
        with patch.object(gate,'git',side_effect=archived_git):gate.correction_source_guard()
        run=(ROOT/'benchmarks/terminal-viewport-tests/run-ci.sh').read_text()
        body=run.split('" = fullVirtualSelection ]; then\n',1)[1].split('\nfi',1)[0]
        self.assertEqual(set(gate.SELECTION_METHODS),set(re.findall(r'TerminalFullVirtualInstrumentedTest#(\w+)',body)))
        self.assertEqual(2,len(re.findall(r'run_arm ',body)))
        self.assertNotIn('PairedCold',body)
        source=(ROOT/gate.VIEW_FILE).read_text()
        self.assertIn('handle.positionOnScreen+Offset',source)
        self.assertIn('automation.injectInputEvent(input,true)',source)
        self.assertIn('compose.mainClock.advanceTimeByFrame()',source)
        self.assertIn('compose.mainClock.autoAdvance=prior',source)
        self.assertIn('Drag never recycled the start row',source)
        self.assertIn('clipboard.primaryClip?.getItemAt(0)?.text?.toString()==expected',source)
        workflow=(ROOT/'.github/workflows/terminal-viewport-interactions.yml').read_text()
        self.assertIn('actions: read',workflow)
        self.assertIn('terminal-full-virtual-reports.py prepareFirst',workflow)
        self.assertIn('terminal-full-virtual-reports.py selectionCorrection',workflow)

    def test_complete_separate_scene_and_paired_evidence(self):
        result=gate.validate_viewport(encode(viewport()))
        self.assertEqual((7,45,30,30),(len(result['cases']),len(result['rectangles']),len(result['samples']),len(result['disposals'])))
        self.assertTrue(result['performanceAccepted'])

    def test_omitted_fallback_clipped_retained_and_reordered_scene_fails(self):
        for group in range(7):
            data=viewport();data[group][1].pop()
            with self.subTest(group=group),self.assertRaises(ValueError):gate.validate_viewport(encode(data))
        for key,val in (('virtual',False),('rowStates',10024),('measuredRows',10024),('bottom',900),('historyRows',9000),('selection',True)):
            data=viewport();data[1][1][0][key]=val
            with self.subTest(key=key),self.assertRaises(ValueError):gate.validate_viewport(encode(data))

    def test_slow_samples_are_retained_and_fail_frozen_criteria(self):
        data=viewport();s=data[4][1]
        row=next(r for r in s if r['size']==10000 and r['round']==1 and r['mode']=='fullVirtual')
        row['streamNs']=1000000000
        result=gate.validate_viewport(encode(data))
        self.assertFalse(result['performanceAccepted'])
        self.assertIn(row,result['samples'])
        data=viewport();data[4][1].reverse()
        with self.assertRaises(ValueError):gate.validate_viewport(encode(data))

    def test_complete_native_page_both_kinds(self):
        result=gate.validate_page(encode(pages()))
        self.assertEqual(18,sum(len(c['rectangles']) for c in result['cases']))
        self.assertEqual(12,sum(len(c['kernelReadings']) for c in result['cases']))
        self.assertEqual(8,sum(len(c['outputWindows']) for c in result['cases']))

    def test_partial_wrong_size_fallback_and_missing_native_bytes_fail(self):
        for group in range(7):
            data=pages();data[group][1].pop()
            with self.subTest(group=group),self.assertRaises(ValueError):gate.validate_page(encode(data))
        for field,value in (('virtual',False),('fullVirtual',False),('measuredUiRows',10024),('measuredRows',32),('ackRows',32),
                             ('firstStableDraw',100),('cursorChecked',False),('tui',True)):
            data=pages();data[0][1][0]['rectangles'][0][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):gate.validate_page(encode(data))
        data=pages();data[0][1][0]['outputWindows'][0]['fedBytes']-=1
        with self.assertRaises(ValueError):gate.validate_page(encode(data))
        with self.assertRaises(ValueError):gate.validate_page(encode(pages())+'\nFULL_VIRTUAL_PAGE_FAILURE {}')

    def test_independent_kernel_order_detachment_and_copies_are_required(self):
        for key,value in (('rows',32),('envRows',25),('beforeRequest',1),('beforeNs',0),('reply','SIZE:initial:32 80;ENV:24 80\r\n')):
            data=pages();data[0][1][0]['kernelReadings'][0][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):gate.validate_page(encode(data))
        data=pages();data[0][1][0]['overlaps'][3]['detachedTicks']=0
        with self.assertRaises(ValueError):gate.validate_page(encode(data))
        data=pages();data[0][1][0]['resizeObservations'][-1]['mounted']=True
        with self.assertRaises(ValueError):gate.validate_page(encode(data))

    def test_new_methods_are_wired_and_measurements_are_in_the_right_class(self):
        methods=lambda p:set(re.findall(r'@Test\s+fun\s+(\w+)\(',p.read_text()))
        self.assertEqual(set(gate.VIEW_METHODS),methods(ROOT/gate.VIEW_FILE))
        self.assertEqual(set(gate.PAGE_METHODS),methods(ROOT/gate.PAGE_FILE))
        body=(ROOT/(gate.MAIN+'ui/pages/container/TerminalLazyItemMeasurements.kt')).read_text()
        self.assertNotIn('fun readFull',body.split('internal class TerminalLazyItemMeasurements')[0])
        self.assertIn('fun readFull',body.split('internal class TerminalLazyItemMeasurements')[1])
        ui=(ROOT/(gate.MAIN+'ui/pages/container/TerminalFullVirtualTranscript.kt')).read_text()
        self.assertIn('count = frame.rows.size',ui)
        self.assertIn('TerminalChunkViewIdentity(rows, frame)',ui)
        self.assertNotIn('SelectionContainer {',ui)
        wire=(ROOT/'.github/scripts/run-terminal-pipeline.py').read_text()
        self.assertIn('methods = full_reports.PAGE_METHODS',wire)
        self.assertIn("'fullVirtualOnly', 'pipeline'",wire)
        self.assertEqual(18,len(gate.ALLOWED))
        unit=(ROOT/'.github/workflows/terminal-emulator-tests.yml').read_text()
        self.assertEqual(1,unit.count('--tests me.rerere.rikkahub.data.container.TerminalFullVirtualTest'))


if __name__=='__main__':unittest.main()
