import copy
import importlib.util
import os
import unittest
from pathlib import Path
from unittest.mock import patch

s = importlib.util.spec_from_file_location('tui306_r2', Path(__file__).with_name('terminal-306-tui-validation-r2.py'))
m = importlib.util.module_from_spec(s)
s.loader.exec_module(m)


class Tui306R2Tests(unittest.TestCase):
    def test_frozen_R1_candidate_and_six_TUI_cases(self):
        identity, p = m.guard()
        self.assertEqual(p['expectedMethods'], 6)
        self.assertEqual(p['expectedScreenshots'], 42)
        self.assertEqual(p['candidateHashes'], {n:v['candidate'] for n,v in identity['files'].items()})
        self.assertTrue(all(p['scope'].values()))

    def test_no_local_Kotlin_or_Android_execution(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS':'false'}):
            for fn in (m.build, m.run):
                with self.assertRaisesRegex(ValueError, 'CI-only'):
                    fn()

    def test_overlay_exact_bytes_and_restoration_after_failure(self):
        identity, p = m.guard()
        before = {n:(m.ROOT/n).read_bytes() for n in identity['files']}
        with self.assertRaisesRegex(RuntimeError, 'intentional'):
            with m.overlay():
                for name, item in identity['files'].items():
                    self.assertEqual(m.ci.digest(m.ROOT/name), item['candidate'])
                self.assertEqual(m.ci.digest(m.ROOT/p['test']['file']), p['test']['sha256'])
                raise RuntimeError('intentional')
        for name, raw in before.items():
            self.assertEqual((m.ROOT/name).read_bytes(), raw)
        self.assertFalse((m.ROOT/p['test']['file']).exists())
        m.guard()

    def test_runtime_TUI_and_SEL_off_are_independent_assertions(self):
        text = (m.BASE/'Terminal306TuiValidationTest.kt').read_text()
        for value in ('v.tui &&', 'state.tui)', 'state.selection)', '.put("runtimeTuiRequired", true)',
                      'PtyMode.RAW', 'terminalCustomTuiCommands = "sh"'):
            self.assertIn(value, text)

    def test_new_glyph_targets_use_layout_and_model_not_nominal_cell_formula(self):
        text = (m.BASE/'Terminal306TuiValidationTest.kt').read_text()
        body = text.split('val localX = if (glyph) {',1)[1].split('} else width * 2.5f',1)[0]
        for value in ('captureScreenSnapshot()', 'line.offset(logicalColumn - 1)',
                      'line.column(offset)', 'GetTextLayoutResult', 'layout.getBoundingBox(offset).center.x'):
            self.assertIn(value, body)
        self.assertNotIn('width *', body)
        self.assertEqual(m.protocol()['mouse']['actualGlyphLogicalColumn'],48)

    def test_geometry_keeps_revision_and_all_mixed_markers(self):
        text = (m.BASE/'Terminal306TuiValidationTest.kt').read_text()
        for value in ('assertEquals(snapshot.revision, frame.revision)', 'assertEquals(originalGrid,',
                      'listOf(24, 48, snapshot.columns)', 'assertEquals(snapshot.revision, terminal.revision())'):
            self.assertIn(value,text)

    def input_row(self, phase='glyph-2'):
        glyph=phase.startswith('glyph-')
        row=10 if glyph else 2
        start=48 if glyph else 3
        dragging=phase in ('drag','hold-drag')
        end=7 if dragging else start
        chunks=[f'\x1b[<0;{start};{row}M']
        if dragging: chunks += [f'\x1b[<32;{c};{row}M' for c in range(4,8)]
        chunks += [f'\x1b[<0;{end};{row}m']
        events=[]
        for chunk in chunks:
            for stage in ('UI_INPUT','INPUT_ENQUEUED','INPUT_WRITTEN'):
                events.append(dict(seq=len(events)+1,stage=stage,bytes=0 if stage=='UI_INPUT' else len(chunk)))
        for stage in ('INPUT_ENQUEUED','INPUT_WRITTEN'):
            events.append(dict(seq=len(events)+1,stage=stage,bytes=1))
        case='mouse-fullVirtual'
        data=''.join(chunks).encode()
        return dict(phase=phase,row=row,physicalRows=28,pointerColumnRange=[start,end],expectedHex=data.hex(),
            receivedHex=data.hex(),wireMatches=True,touchTargetSource='actual-glyph-bounds' if glyph else 'nominal-cell-control',
            cellWidth=25,targetLocalX=1187.5 if glyph else 62.5,nominalColumnAtGlyph=start,traceDropped=0,
            clipboardUnchanged=True,clipboardSha256=m.digest(('device306-'+case+'-'+phase+'-untouched').encode()),
            inputEvents=events,uiInputCount=len(chunks),inputWrittenBytes=len(data)+1,localNonEditableSelections=0,
            pan=False,selection=False,tui=True,writers=0)

    def test_nominal_control_and_actual_glyph_input_can_both_verify(self):
        for phase in ('tap','drag','hold-drag','glyph-2'):
            self.assertEqual(m.validate_input(self.input_row(phase),'mouse-fullVirtual'), [])

    def test_wrong_actual_column_is_a_preserved_finding_not_resampled(self):
        v=self.input_row()
        wrong=bytes.fromhex(v['receivedHex']).replace(b';48;',b';46;')
        v['receivedHex']=wrong.hex()
        v['wireMatches']=False
        v['targetLocalX']=1142.5
        v['nominalColumnAtGlyph']=46
        self.assertEqual(m.validate_input(v,'mouse-fullVirtual'), ['glyph-2:wire-mismatch'])

    def test_actual_target_source_cannot_be_forged_as_control(self):
        v=self.input_row()
        v['touchTargetSource']='nominal-cell-control'
        with self.assertRaises(ValueError):m.validate_input(v,'mouse-fullVirtual')

    def test_wrong_counter_clipboard_or_wire_claim_rejected(self):
        for k,val in (('uiInputCount',9),('inputWrittenBytes',900),('clipboardUnchanged',False),
                      ('wireMatches',False),('traceDropped',1),('nominalColumnAtGlyph',1)):
            v=self.input_row();v[k]=val
            with self.assertRaises(ValueError):m.validate_input(v,'mouse-fullVirtual')

    def geom(self):
        p=m.protocol()
        rows=[]
        for row in range(1,9):
            markers=[]
            for column in p['geometry']['markerColumns']:
                left=(column-1)*25.0
                markers.append(dict(column=column,offset=column-1,left=left,right=left+25,center=left+12.5,width=25.0))
            rows.append(dict(row=row,caseModulo=row%8,rowHeight=44,layoutHeight=44,layoutWidth=2000,
                rowLeftOnScreen=10,rowTopOnScreen=50+44*(row-1),textSha256='a'*64,markers=markers))
        return dict(phase='mixed-main',gridUnchanged=True,modelColumns=80,columns=80,modelRows=28,rows=28,
            modelRevision=10,revision=10,measuredRows=rows,bottomGap=28,ime=False,alternate=False,
            tui=True,selection=False,following=True,writers=0,viewportHeight=1232,viewportBottom=1500,
            contentBottom=1472)

    def test_same_cell_alignment_is_measured_not_guessed(self):
        g=m.validate_geometry(self.geom(),m.protocol())
        self.assertFalse(g['hasMisalignedColumns'])
        self.assertEqual(g['sameLogicalColumnCenterSpreadPx'],{'24':0,'48':0,'80':0})

    def test_real_stair_step_geometry_is_retained_as_finding(self):
        v=self.geom()
        for item in v['measuredRows'][2]['markers']:
            item['left']-=15;item['right']-=15;item['center']-=15
        result=m.validate_geometry(v,m.protocol())
        self.assertTrue(result['hasMisalignedColumns'])
        self.assertEqual(result['sameLogicalColumnCenterSpreadPx'],{'24':15,'48':15,'80':15})

    def test_duplicate_row_missing_marker_or_bad_revision_rejected(self):
        variants=[]
        a=self.geom();a['measuredRows'].append(copy.deepcopy(a['measuredRows'][0]));variants.append(a)
        a=self.geom();a['measuredRows'][0]['markers'].pop();variants.append(a)
        a=self.geom();a['modelRevision']=9;variants.append(a)
        a=self.geom();a['measuredRows'][0]['layoutHeight']=45;variants.append(a)
        for v in variants:
            with self.assertRaises(ValueError):m.validate_geometry(v,m.protocol())

    def records(self):
        p=m.protocol();records=[]
        common=self.geom()
        for name in p['requiredCases']:
            initial={k:v for k,v in common.items() if k not in ('measuredRows','gridUnchanged','modelRows','modelColumns','modelRevision')}
            initial['phase']='initial'
            if name.startswith('mouse-'):
                obs=[initial]
                for phase in p['mouse']['phases']:
                    v=self.input_row(phase)
                    if phase.startswith('glyph-'):
                        before=v['row'];row=8+int(phase.split('-')[1]);v['row']=row
                        v['expectedHex']=bytes.fromhex(v['expectedHex']).replace(f';{before}M'.encode(),f';{row}M'.encode()).replace(f';{before}m'.encode(),f';{row}m'.encode()).hex()
                        v['receivedHex']=v['expectedHex']
                        lengths=[len(bytes.fromhex(v['expectedHex']))//2]*2
                        for stage in ('INPUT_ENQUEUED','INPUT_WRITTEN'):
                            matches=[e for e in v['inputEvents'] if e['stage']==stage]
                            for e,count in zip(matches,lengths):e['bytes']=count
                        v['inputWrittenBytes']=len(bytes.fromhex(v['receivedHex']))+1
                    v['clipboardSha256']=m.digest(('device306-'+name+'-'+phase+'-untouched').encode())
                    obs.append(v)
                obs.append(dict(phase='explicit-osc52-control',clipboardMatches=True,tui=True,selection=False))
            elif name.startswith('width-'):
                obs=[]
                for index,phase in enumerate(p['width']['phases']):
                    v=copy.deepcopy(initial);v.update(phase=phase,columns=[80,80,100,100,120][index],following=index not in (1,2))
                    if index in (2,4):v['preservedPriorMode']=True
                    obs.append(v)
            else:
                obs=[initial]
                for index,phase in enumerate(p['geometry']['phases']):
                    v=self.geom();v.update(phase=phase,ime=index in (1,2),alternate=index==4)
                    obs.append(v)
            records.append(dict(case=name,mode=name.split('-',1)[1],executionComplete=True,nativePty=True,
                runtimeTuiRequired=True,widthCandidateIncluded=True,productionChanged=False,performanceMeasured=False,
                allReportedDeviceBugsFixed=False,targetApkSha256='b'*64,observations=obs,findings=[]))
        records += [dict(case=name,cleanup=True,closed=True) for name in p['requiredCases']]
        return records

    def test_complete_six_case_inventory_and_width_gate(self):
        result=m.validate(self.records(),m.protocol(),{'target':'b'*64})
        self.assertEqual(len(result[0]),6);self.assertTrue(result[3])

    def test_plain_shell_or_SEL_true_cannot_pass_as_TUI(self):
        for key,value in (('tui',False),('selection',True)):
            records=self.records();records[0]['observations'][1][key]=value
            with self.assertRaisesRegex(ValueError,'TUI/SEL-off'):m.validate(records,m.protocol(),{'target':'b'*64})

    def test_width_native_failure_remains_failure_with_original_evidence(self):
        records=self.records();v=records[2]['observations'][2];v.update(following=True,preservedPriorMode=False)
        records[2]['findings']=['width:LOCK-became-AUTO']
        result=m.validate(records,m.protocol(),{'target':'b'*64});self.assertFalse(result[3])

    def test_IME_cannot_resize_physical_grid_or_be_silently_skipped(self):
        for key,value in (('rows',18),('ime',False)):
            records=self.records();records[4]['observations'][2][key]=value
            with self.assertRaises(ValueError):m.validate(records,m.protocol(),{'target':'b'*64})

    def test_missing_native_case_or_cleanup_rejected(self):
        for records in (self.records()[:-1],self.records()[1:]):
            with self.assertRaises(ValueError):m.validate(records,m.protocol(),{'target':'b'*64})


if __name__=='__main__':unittest.main()
