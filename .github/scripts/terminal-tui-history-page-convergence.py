"""Explicit v2 real-page gate: measured initial grid, acknowledged model, kernel queries and ordered size observations."""
import copy
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess

SPEC = importlib.util.spec_from_file_location('tui_page_v1', Path(__file__).with_name('terminal-tui-history-reports.py'))
common = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(common)
ROOT, require, records, integer = common.ROOT, common.require, common.records, common.integer
SUITE = 'terminal-tui-history-page-v2'
PRODUCTION_TREE = '71b1b14fde6b0bc3ec413b8024012060c404858b'
PHASES = ('initial', 'imeOpen', 'imeClosed', 'reentry', 'input')


def validate_page(text):
    require('TUI_HISTORY_PAGE_FAILURE ' not in text, 'Convergence page reported failure')
    cases = records(text, 'TUI_HISTORY_PAGE ')
    require(len(cases) == 2 and {c.get('mode') for c in cases} == set(common.MODES), 'Missing v2 modes')
    require(all(c.get('suite') == SUITE for c in cases), 'Cannot mix or relabel v1 evidence')
    # Reuse the UNCHANGED v1 payload/rectangle/overlap/cleanup checks as pure checks.
    # This temporary input is never saved or published; return original v2 records only.
    normalized = copy.deepcopy(cases)
    for c in normalized:
        c['suite'] = 'terminal-tui-history-page-v1'
    fixed = [('TUI_HISTORY_PAGE', normalized)]
    fixed += [(marker, records(text, marker + ' ')) for marker in
              ('TUI_HISTORY_PAGE_RECT', 'TUI_HISTORY_PAGE_OVERLAP', 'TUI_HISTORY_PAGE_CLEANUP')]
    common.validate_page('\n'.join(marker + ' ' + json.dumps(r) for marker, values in fixed for r in values))

    kernels, observations = [], []
    for c in cases:
        mode, rows = c['mode'], integer(c, 'physicalRows', 6)
        require(c.get('gridContract') == 'measured-target-ack-kernel-v1', 'Missing measured-grid contract')
        baseline = integer(c, 'baselineRequest')
        rects = c['rectangles']
        initial = rects[0]
        require(initial['phase'] == 'initial' and initial.get('request') == baseline, 'Baseline was not captured from the initial rectangle')
        previous_time, previous_request = 0, baseline
        for r in rects:
            integer(r, 'request')
            require(r['request'] >= previous_request, 'Receipt order went backwards')
            previous_request = r['request']
            require(integer(r, 'timeNs', 1) > previous_time, 'Rectangle times are not ordered')
            previous_time = r['timeNs']
            require(integer(r, 'firstStableDraw', 1) < r['draw'], 'Initial/final grid is not two different actual draws')
            require(integer(r, 'cellHeight', 1) > 0, 'Missing measured grid-cell height')
            measured = min(80, max(6, r['viewport'] // r['cellHeight']))
            require(integer(r, 'measuredRows') == measured, 'Measured row target differs from viewport/cell geometry')
            require(integer(r, 'desiredRows') == rows and integer(r, 'ackRows') == rows and
                    integer(r, 'snapshotRows') == rows and integer(r, 'rows') == rows, 'Grid/model/receipt/snapshot differs')
            require(all(integer(r, k) == 80 for k in ('columns', 'ackColumns', 'snapshotColumns')), 'Columns changed')
            require(r.get('pendingResize') is False and r.get('gridSource') == ('measured' if r['phase'] == 'initial' else 'preserved'),
                    'Unconverged or wrong grid-source record')
            if not r['ime']:
                require(measured == rows, 'No-IME grid did not converge to its actual viewport target')

        readings = c.get('kernelReadings')
        require(type(readings) is list and [r.get('phase') for r in readings] == list(PHASES), 'Missing/reordered kernel queries')
        for index, reading in enumerate(readings):
            phase = reading['phase']
            require(reading.get('mode') == mode and reading.get('ime') is (phase == 'imeOpen'), 'Kernel mode/IME differs')
            require(integer(reading, 'beforeNs', 1) > rects[index]['timeNs'] and
                    integer(reading, 'afterNs', 1) > reading['beforeNs'], 'Kernel query predates measured geometry')
            if index + 1 < len(rects):
                require(reading['afterNs'] < rects[index+1]['timeNs'], 'Kernel query order differs')
            require(integer(reading, 'beforeRequest') == integer(reading, 'request') == rects[index]['request'],
                    'Applied size changed during query')
            require(all(integer(reading, k) == rows for k in ('rows', 'modelRows', 'snapshotRows')), 'Kernel/model/snapshot row disagreement')
            require(all(integer(reading, k) == 80 for k in ('columns', 'modelColumns', 'snapshotColumns', 'envColumns')) and
                    integer(reading, 'envRows') == 24, 'Kernel columns or inherited session environment differs')
            require(reading.get('pendingResize') is False, 'Kernel query overlapped a pending resize')
            require(reading.get('reply') == f'SIZE:{phase}:{rows} 80;ENV:24 80\r\n', 'Independent kernel reply differs')
        timeline = c.get('resizeObservations')
        require(type(timeline) is list and 1 <= len(timeline) <= 128, 'Missing/unbounded resize observations')
        time, request = 0, 0
        seen_phases, detached = set(), False
        for r in timeline:
            phase = r.get('phase')
            require(phase in ('mount', 'seed', *PHASES), 'Unknown resize phase')
            seen_phases.add(phase)
            require(r.get('mode') == mode and type(r.get('mounted')) is bool and type(r.get('ime')) is bool and
                    type(r.get('pending')) is bool, 'Invalid resize mode/lifecycle flags')
            require(integer(r, 'timeNs', 1) > time and integer(r, 'request') >= request, 'Size observations are not monotonic')
            time, request = r['timeNs'], r['request']
            integer(r, 'draw')
            require(type(r.get('revision')) is int and r['revision'] >= -1, 'Invalid observed revision')
            require(all(6 <= integer(r, k) <= 80 for k in ('rows', 'modelRows')) and
                    all(integer(r, k) == 80 for k in ('columns', 'modelColumns')), 'Invalid observed size')
            if phase in ('imeOpen', 'imeClosed'):
                require(r['rows'] == r['modelRows'] == rows and not r['pending'], 'IME observation changed the converged physical grid')
            if phase == 'reentry' and not r['mounted']:
                detached = True
        require(set(PHASES) <= seen_phases and detached, 'Missing phase/detached size provenance')
        require(timeline[0]['timeNs'] < initial['timeNs'] and timeline[-1]['timeNs'] < readings[-1]['afterNs'],
                'Size provenance does not cover the initial/final query windows')
        for flow in c['overlaps']:
            require(integer(flow, 'beforeRows') == rows and integer(flow, 'afterRows') == rows, 'Ongoing-output grid differs')
            require(integer(flow, 'beforeRequest') >= baseline and integer(flow, 'afterRequest') >= flow['beforeRequest'],
                    'Ongoing-output receipt order differs')
            if flow['phase'] != 'reentry':
                require(flow['beforeRequest'] == flow['afterRequest'], 'IME generated a new applied resize request')
        kernels += readings
        observations += timeline
    require(records(text, 'TUI_HISTORY_PAGE_KERNEL ') == kernels, 'Kernel record copies differ')
    require(records(text, 'TUI_HISTORY_PAGE_SIZE ') == observations, 'Size record copies differ')
    return dict(cases=cases, cleanups=records(text, 'TUI_HISTORY_PAGE_CLEANUP '))


def source_guard():
    tree = subprocess.check_output(['git', 'rev-parse', 'HEAD:app/src/main'], cwd=ROOT, text=True, timeout=30).strip()
    require(tree == PRODUCTION_TREE, 'Page-only correction changed the production tree')
    require(not subprocess.check_output(['git', '-c', 'core.fileMode=false', 'diff', '--name-only', 'HEAD', '--', 'app/src/main'],
                                        cwd=ROOT, timeout=30), 'Uncommitted production source')
    return dict(productionTree=tree, originalFailedPageRun=39436622755,
                baselineContract='measured-target-ack-kernel-v1', productionUnchanged=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', nargs='?', choices=('guard', 'evidence'), default='guard')
    parser.add_argument('--page', type=int, choices=(1, 2), default=1)
    args = parser.parse_args()
    try:
        guard = source_guard()
        if args.action == 'guard':
            print(json.dumps(guard))
        else:
            raw = (ROOT / 'artifacts/terminal-pipeline/tui-history-page-v2.json').read_bytes()
            report = json.loads(raw)
            require(report['sourceSha'] == os.environ['GITHUB_SHA'] and report['runId'] == os.environ['GITHUB_RUN_ID'],
                    'V2 report SHA/run mismatch')
            require(report['suite'] == SUITE and report['sourceGuard'] == guard, 'Wrong completed convergence report')
            common.compact('Terminal TUI history page v2', raw, page=args.page)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print('::error title=TUI page convergence source rejected::' + str(error)[:2400])
        raise SystemExit(1)
