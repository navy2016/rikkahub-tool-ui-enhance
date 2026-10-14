"""Read three original cold-mount traces; export only bounded counters and phase aggregates."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

TARGET = 'me.rerere.rikkahub.terminalbenchmark'
QUERY = f"""
SELECT 'counter' AS kind, track.name AS name, COUNT(*) AS samples,
       MAX(value) AS maximum, NULL AS total_ns
FROM counter LEFT JOIN process_counter_track AS track ON counter.track_id = track.id
LEFT JOIN process USING (upid)
WHERE process.name = '{TARGET}' AND
      (track.name LIKE 'mem.rss%' OR track.name = 'Heap size (KB)' OR track.name = 'GPU Memory')
GROUP BY track.name
UNION ALL
SELECT 'slice', slice.name, COUNT(*), MAX(slice.dur), SUM(slice.dur)
FROM slice LEFT JOIN thread_track ON slice.track_id = thread_track.id
LEFT JOIN thread USING (utid) LEFT JOIN process_track ON slice.track_id = process_track.id
LEFT JOIN process ON process.upid = COALESCE(thread.upid, process_track.upid)
WHERE process.name = '{TARGET}' AND slice.dur >= 0 AND
      (slice.name GLOB 'Prod.*' OR slice.name GLOB 'Terminal.production*')
GROUP BY slice.name;
"""
HEALTH_QUERY = """
SELECT 'stat' AS kind, name, idx AS identifier, value, severity AS detail
FROM stats WHERE value != 0 AND
    (severity != 'info' OR name GLOB '*overrun*' OR name GLOB '*drop*' OR name GLOB '*lost*' OR
     name GLOB '*discard*' OR name GLOB '*flush*')
UNION ALL
SELECT 'boundary', slice.name, slice.ts, slice.dur, COALESCE(process.name, '')
FROM slice LEFT JOIN thread_track ON slice.track_id = thread_track.id
LEFT JOIN thread USING (utid) LEFT JOIN process_track ON slice.track_id = process_track.id
LEFT JOIN process ON process.upid = COALESCE(thread.upid, process_track.upid)
WHERE slice.name IN ('Prod.mountToSettledDraw', 'Prod.validation', 'measureBlock',
                     'Perfetto - preparing to stop');
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--processor', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    traces = sorted(args.root.rglob('*history_5000_scenario_initialCompose_mode_chunkedLayers__iter*.perfetto-trace'))
    if len(traces) != 3:
        raise ValueError(f'Expected exactly three original traces, found {len(traces)}')
    args.out.mkdir(parents=True, exist_ok=False)
    query = args.out / 'query.sql'
    query.write_text(QUERY)
    health_query = args.out / 'health.sql'
    health_query.write_text(HEALTH_QUERY)
    evidence = []
    for index, trace in enumerate(traces):
        result = subprocess.run([str(args.processor.resolve()), '-q', str(query.resolve()), str(trace.resolve())],
                                capture_output=True, text=True, timeout=60, check=False)
        if result.returncode or len(result.stdout.encode()) > 12000:
            raise ValueError(f'Bounded trace query failed: {result.stderr[-1600:]}')
        digest = hashlib.sha256(trace.read_bytes()).hexdigest()
        item = dict(trace=trace.name, sha256=digest, csv=result.stdout)
        evidence.append(item)
        message = json.dumps(item, separators=(',', ':'))
        if len(message.encode()) > 3800:
            raise ValueError('Trace diagnostic exceeds annotation bound')
        message = message.replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print(f'::notice title=V5 original trace {index}::{message}', flush=True)
        health = subprocess.run(
            [str(args.processor.resolve()), '-q', str(health_query.resolve()), str(trace.resolve())],
            capture_output=True, text=True, timeout=60, check=False)
        if health.returncode:
            raise ValueError(f'Trace health query failed: {health.stderr[-1600:]}')
        item['healthCsv'] = health.stdout
        message = json.dumps(dict(trace=trace.name, sha256=digest, healthCsv=health.stdout), separators=(',', ':'))
        if len(message.encode()) > 3800:
            raise ValueError('Trace health exceeds annotation bound')
        message = message.replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print(f'::notice title=V5 trace health {index}::{message}', flush=True)
    (args.out / 'trace-diagnostics.json').write_text(json.dumps(evidence, indent=2) + '\n')


if __name__ == '__main__':
    main()
