"""Host-side failure contracts for the one added convergence round; no Android/Kotlin build."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import unittest


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


gate = load('page_convergence_gate', 'terminal-tui-history-page-convergence.py')
fixtures = load('page_convergence_base_fixtures', 'test_terminal_tui_history.py')
ROOT = gate.ROOT


def pages():
    base = fixtures.pages()
    cases = base[0][1]
    kernels, timeline = [], []
    for c in cases:
        c.update(suite=gate.SUITE, baselineRequest=3, gridContract='measured-target-ack-kernel-v1')
        readings = []
        observations = [dict(mode=c['mode'], phase='mount', timeNs=1, request=1, columns=80, rows=32,
            modelColumns=80, modelRows=32, pending=False, mounted=True, ime=False, draw=1, revision=1)]
        observations.append(dict(observations[0], phase='seed', timeNs=2, request=3, rows=24, modelRows=24, draw=2))
        for index, r in enumerate(c['rectangles']):
            stamp = (index+1)*1_000_000
            phase = r['phase']
            r.update(request=3, timeNs=stamp, firstStableDraw=r['draw']-1, cellHeight=13,
                desiredRows=24, measuredRows=10 if r['ime'] else 24, gridSource='measured' if index==0 else 'preserved',
                columns=80, ackColumns=80, ackRows=24, snapshotRows=24, snapshotColumns=80, pendingResize=False)
            if r['ime']:
                r.update(viewport=130, top=90, bottom=117)
            readings.append(dict(mode=c['mode'], phase=phase, ime=r['ime'], beforeNs=stamp+100, afterNs=stamp+200,
                request=3, beforeRequest=3, rows=24, columns=80, modelRows=24, modelColumns=80, snapshotRows=24,
                snapshotColumns=80, envRows=24, envColumns=80, pendingResize=False,
                reply=f'SIZE:{phase}:24 80;ENV:24 80\r\n'))
            observations.append(dict(mode=c['mode'], phase=phase, timeNs=stamp-100, request=3, columns=80, rows=24,
                modelColumns=80, modelRows=24, pending=False, mounted=True, ime=r['ime'], draw=r['draw']-2, revision=r['revision']))
            if phase == 'reentry':
                observations.append(dict(observations[-1], timeNs=stamp-80, mounted=False))
                observations.append(dict(observations[-1], timeNs=stamp-60, mounted=True))
        for flow in c['overlaps']:
            flow.update(beforeRequest=3, afterRequest=3, beforeRows=24, afterRows=24)
        c['kernelReadings'] = readings
        c['resizeObservations'] = observations
        kernels += readings
        timeline += observations
    return base + [('TUI_HISTORY_PAGE_KERNEL', kernels), ('TUI_HISTORY_PAGE_SIZE', timeline)]


class TuiPageConvergenceTest(unittest.TestCase):
    def test_complete_two_modes_have_ten_kernel_queries_and_real_grid_rectangles(self):
        values = gate.validate_page(fixtures.text(pages()))
        self.assertEqual(2, len(values['cases']))
        self.assertEqual(10, sum(len(c['kernelReadings']) for c in values['cases']))
        self.assertEqual(6, sum(len(c['overlaps']) for c in values['cases']))
        self.assertTrue(all(c['suite'] == gate.SUITE for c in values['cases']))

    def test_original_v1_and_partial_failed_page_cannot_be_relabelled(self):
        with self.assertRaises(ValueError): gate.validate_page(fixtures.text(fixtures.pages()))
        with self.assertRaises(ValueError): gate.validate_page(fixtures.text(pages())+'\nTUI_HISTORY_PAGE_FAILURE {}')
        data = pages(); data[0][1].pop()
        with self.assertRaises(ValueError): gate.validate_page(fixtures.text(data))
        data = pages(); data[0][1][0]['suite'] = 'terminal-tui-history-page-v1'
        with self.assertRaises(ValueError): gate.validate_page(fixtures.text(data))

    def test_rejects_unconverged_initial_rows_pending_and_repeated_poll(self):
        for field, value in (('ackRows',32), ('desiredRows',32), ('measuredRows',25), ('snapshotColumns',81),
                             ('pendingResize',True), ('request',True), ('firstStableDraw',100),
                             ('gridSource','preserved'), ('cellHeight',0)):
            data=pages(); data[0][1][0]['rectangles'][0][field]=value
            with self.subTest(field=field), self.assertRaises(ValueError): gate.validate_page(fixtures.text(data))

    def test_rejects_missing_wrong_kernel_readings_and_query_before_geometry(self):
        for field, value in (('rows',32), ('envRows',25), ('snapshotRows',32), ('beforeRequest',2),
                             ('beforeNs',1), ('reply','SIZE:initial:32 80;ENV:24 80\r\n'), ('pendingResize',True)):
            data=pages(); data[0][1][0]['kernelReadings'][0][field]=value
            with self.subTest(field=field), self.assertRaises(ValueError): gate.validate_page(fixtures.text(data))
        data=pages(); data[0][1][0]['kernelReadings'].pop()
        with self.assertRaises(ValueError): gate.validate_page(fixtures.text(data))
        data=pages(); data[4][1].pop()
        with self.assertRaises(ValueError): gate.validate_page(fixtures.text(data))

    def test_rejects_ime_resize_missing_detach_and_wrong_timeline_order(self):
        for field, value in (('rows',32), ('modelRows',32), ('pending',True), ('timeNs',0), ('columns',81)):
            data=pages(); row=next(r for r in data[0][1][0]['resizeObservations'] if r['phase']=='imeOpen'); row[field]=value
            with self.subTest(field=field), self.assertRaises(ValueError): gate.validate_page(fixtures.text(data))
        data=pages()
        for r in data[0][1][0]['resizeObservations']: r['mounted']=True
        with self.assertRaises(ValueError): gate.validate_page(fixtures.text(data))
        data=pages(); data[5][1].pop()
        with self.assertRaises(ValueError): gate.validate_page(fixtures.text(data))
        data=pages(); data[0][1][0]['overlaps'][0]['afterRequest']=4
        with self.assertRaises(ValueError): gate.validate_page(fixtures.text(data))

    def test_sources_keep_original_payload_row_assertions_and_no_compilation(self):
        body=(ROOT/gate.common.PAGE_FILE).read_text()
        self.assertLess(body.index('val initial = rectangle("initial", false)'),body.index('val physicalRows = baseline.rows'))
        for clause in ('terminal.rows != desiredRows', 'snapshot.terminalRows != desiredRows',
                       'cellHeight, desiredRows, terminal.rows, terminal.columns, ack.request',
                       'assertEquals("IME/reentry resized the PTY grid", physicalRows, terminal.rows)',
                       'while [ "${\'$\'}i" -lt 160 ]', 'sleep 0.1', 'env -u LINES -u COLUMNS stty size',
                       'assertEquals("Finite peer output bytes differ", expectedBytes.toLong(), readBytes)',
                       'assertEquals("Parser did not receive all output", readBytes, fedBytes)'):
            self.assertIn(clause,body)
        self.assertNotIn('synchronized(terminal@',body)
        self.assertNotIn('terminal.resize(',body)
        self.assertNotIn('manager.resizeInteractiveSession(',body)
        self.assertNotIn('uiautomator',body)
        self.assertEqual(set(gate.common.PAGE_METHODS), set(re.findall(r'@Test\s+fun\s+(\w+)\(',body)))
        production=subprocess.check_output(['git','rev-parse','HEAD:app/src/main'],cwd=ROOT,text=True).strip()
        self.assertEqual(gate.PRODUCTION_TREE, production)
        legacy_report=subprocess.check_output(['git','show','2c1f1efa9660d0601ee1d6db65565694858b624d:.github/scripts/terminal-tui-history-reports.py'],cwd=ROOT)
        self.assertEqual(hashlib.sha256(legacy_report).hexdigest(),hashlib.sha256((ROOT/'.github/scripts/terminal-tui-history-reports.py').read_bytes()).hexdigest())
        run=(ROOT/'.github/scripts/run-terminal-pipeline.py').read_text()
        focus=run.split("== 'tuiHistoryCompletion':",1)[1].split("elif os.environ",1)[0]
        self.assertEqual(1,focus.count("run('tuiHistoryOnly')"))
        self.assertEqual(1,focus.count("run('pipeline')"))
        self.assertNotIn("run('transport')",focus)
        self.assertNotIn('performance',focus.split("import shutil",1)[1].split("run('tuiHistoryOnly')",1)[1])


if __name__ == '__main__': unittest.main()
