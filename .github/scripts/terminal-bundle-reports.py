"""Strict validation for real terminal service and sustained-output measurements."""
import json
import math
import re
import statistics

SOAK_SUITE = 'terminal-bundle-soak-v1'
SETTLE_CONTRACT = 'terminal-settled-viewport-v1'


def records(text, marker):
    return [json.loads(line.split(marker, 1)[1]) for line in text.splitlines() if marker in line]


def settlements(text, mode, pressure=False, launch=None, remounts=0):
    """New acceptance contract; expectations come from the workload, never from observed backend state."""
    if mode not in ('chunkedLayers', 'lazyHistory', 'lazyHistoryIme') or type(remounts) is not int or remounts < 0:
        raise ValueError('Invalid convergence request')
    values = records(text, 'SETTLE_RECEIPT ')
    values = [value for value in values if value.get('mode') == mode]
    phases = ['mounted', 'imeVisible', 'imeHidden', 'remount']
    if pressure:
        phases += ['pressureStart'] + ['pressureRemount'] * remounts
    if not values or [value.get('phase') for value in values] != phases:
        raise ValueError('Missing, duplicate or reordered viewport convergence receipts')
    expected_launch = launch or values[0].get('launch')
    if not isinstance(expected_launch, str) or not re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', expected_launch):
        raise ValueError('Invalid convergence launch identity')
    previous_draw, previous_time, previous_revision = 0, 0, 0
    for value in values:
        phase = value['phase']
        expected_virtual = mode != 'chunkedLayers' and (
            mode == 'lazyHistoryIme' or phase not in ('imeVisible', 'imeHidden'))
        expected_ime = phase == 'imeVisible'
        if (value.get('suite') != SETTLE_CONTRACT or value.get('launch') != expected_launch or
                value.get('expectedVirtual') is not expected_virtual or value.get('expectedIme') is not expected_ime):
            raise ValueError('Mislabeled convergence receipt')
        for field in ('afterDraw', 'firstStableDraw', 'confirmations', 'minRevision'):
            if type(value.get(field)) is not int or value[field] < 0:
                raise ValueError('Invalid convergence count')
        if type(value.get('settleMs')) not in (int, float) or not 0 <= value['settleMs'] <= 15000:
            raise ValueError('Convergence timed out or lacks monotonic duration')
        p = value.get('viewport')
        if not isinstance(p, dict):
            raise ValueError('Missing actual viewport state')
        if (type(p.get('draw')) is not int or not value['afterDraw'] < value['firstStableDraw'] < p['draw'] or
                value['confirmations'] < 2 or type(p.get('revision')) is not int or
                p['revision'] < value['minRevision'] or type(p.get('timeNanos')) is not int or p['timeNanos'] <= 0 or
                type(p.get('historyRows')) is not int or p['historyRows'] <= 0):
            raise ValueError('Repeated poll or stale frame cannot prove two completed draws')
        if value['afterDraw'] < previous_draw or p['timeNanos'] <= previous_time or p['revision'] < previous_revision:
            raise ValueError('Convergence receipts cannot reuse old draws or reverse time/source')
        previous_draw, previous_time, previous_revision = p['draw'], p['timeNanos'], p['revision']
        if p.get('preferred') != mode or p.get('applied') != mode:
            raise ValueError('Viewport renderer preference drift')
        for field in ('pan', 'hasChunks', 'sameFrame', 'initialized', 'following', 'geometryReady', 'tailSatisfied'):
            if p.get(field) is not True:
                raise ValueError('Incomplete viewport gate: ' + field)
        for field in ('selection', 'tui', 'alternate', 'scrolling'):
            if p.get(field) is not False:
                raise ValueError('Unexpected viewport gate: ' + field)
        for field in ('virtual', 'bindingVirtual', 'wantsVirtual'):
            if p.get(field) is not expected_virtual:
                raise ValueError('Observed backend does not match independent expectation')
        if (p.get('ime') is not expected_ime or 'gestureId' not in p or 'effectId' not in p or
                p['gestureId'] is not None or p['effectId'] is not None or
                type(p.get('writers')) is not int or p['writers'] != 0):
            raise ValueError('Viewport still owns a gesture/effect or wrong IME state')
        if expected_virtual and (p.get('widthReady') is not True or p.get('imeFallback') is not False or
                                 (expected_ime and p.get('avoidIme') is not True)):
            raise ValueError('Virtual width/IME eligibility incomplete')
    return values


def service(text):
    values = records(text, 'SERVICE_SAMPLE ')
    if (len(values) != 2 or {row.get('case') for row in values} != {'foreground', 'orderedTail'} or
            any(row.get('suite') != 'terminal-bundle-service-v1' for row in values)):
        raise ValueError('Missing service/ordered-tail evidence')
    for row in values:
        if row['case'] == 'foreground':
            if row.get('cycles') != 20 or row.get('promotions', 0) < 22 or row.get('pending') is not False or row.get('live') is not False:
                raise ValueError('Foreground lifecycle did not complete every rapid-start/stop cycle')
        elif row.get('lines') != 500 or row.get('bytes') != 5000:
            raise ValueError('Exited PTY tail was incomplete')
    return values


def soak(text, mode, seconds, require_settled=False):
    if mode not in ('chunkedLayers', 'lazyHistory', 'lazyHistoryIme') or type(seconds) is not int or not 60 <= seconds <= 1800:
        raise ValueError('Invalid sustained workload request')
    rows = records(text, 'SOAK_SAMPLE ')
    if len(rows) < seconds // 60:
        raise ValueError('Sustained workload has missing time buckets')
    launch = rows[0].get('launch') if rows else None
    if not isinstance(launch, str) or not re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', launch):
        raise ValueError('Missing sustained workload launch identity')
    previous = dict(elapsedMs=-1, cycles=0, readBytes=0, fedBytes=0, remounts=0, published=0, drawn=0)
    for index, row in enumerate(rows):
        if (row.get('suite') != SOAK_SUITE or row.get('launch') != launch or row.get('mode') != mode or
                row.get('requestedSeconds') != seconds or type(row.get('bucket')) is not int or row['bucket'] != index or
                row.get('final') is not (index == len(rows) - 1)):
            raise ValueError('Mixed, duplicate or unfinished sustained workload')
        for name in ('cycles', 'bucketCycles', 'remounts', 'readBytes', 'fedBytes', 'published', 'drawn',
                     'traceDropped', 'historyRows', 'pssKb', 'javaUsedBytes', 'nativeAllocatedBytes'):
            if type(row.get(name)) is not int or row[name] < 0:
                raise ValueError('Invalid sustained counter: ' + name)
        for name in ('elapsedMs', 'cycleMeanMs', 'cycleMaxMs'):
            if type(row.get(name)) not in (int, float) or not math.isfinite(row[name]) or row[name] < 0:
                raise ValueError('Invalid sustained duration: ' + name)
        if (row['traceDropped'] != 0 or row['historyRows'] != 10000 or row['readBytes'] != row['fedBytes'] or
                row['pssKb'] <= 0 or row['cycles'] - previous['cycles'] != row['bucketCycles'] or
                row['cycleMaxMs'] < row['cycleMeanMs']):
            raise ValueError('Sustained bytes/history/frame coverage mismatch')
        if any(row[key] < previous[key] for key in previous) or row['elapsedMs'] <= previous['elapsedMs']:
            raise ValueError('Sustained workload counters or time went backwards')
        previous = {key: row[key] for key in previous}
    last = rows[-1]
    if (last['elapsedMs'] < seconds * 1000 or last['elapsedMs'] > (seconds + 30) * 1000 or
            last['cycles'] <= 0 or last['drawn'] < last['cycles'] or last['published'] <= 0 or
            (seconds >= 600 and last['remounts'] < seconds // 300 - 1)):
        raise ValueError('Sustained workload ended early or omitted remount/draw checks')
    middle = [row['pssKb'] for row in rows if row['elapsedMs'] >= 300000]
    head = statistics.median(middle[:5]) if middle else rows[0]['pssKb']
    tail = statistics.median(middle[-5:]) if middle else last['pssKb']
    # A guardrail for runaway retention, not a proof of no leak. Preserve increases/decreases in the report.
    if seconds >= 1800 and tail - head > 128 * 1024:
        raise ValueError('Post-warmup PSS grew by more than the 128MiB acceptance budget')
    receipts = settlements(text, mode, pressure=True, launch=launch, remounts=last['remounts']) if require_settled else None
    return dict(suite=SOAK_SUITE, mode=mode, seconds=seconds, launch=launch, samples=rows,
                settlementContract=SETTLE_CONTRACT if require_settled else None, settlements=receipts,
                postWarmupPssDeltaKb=tail - head, peakPssKb=max(row['pssKb'] for row in rows),
                maximumCycleMs=max(row['cycleMaxMs'] for row in rows), cycles=last['cycles'],
                note='Closed-loop 32-line native-PTY bursts plus actual UI input and periodic remount; not phone FPS.')
