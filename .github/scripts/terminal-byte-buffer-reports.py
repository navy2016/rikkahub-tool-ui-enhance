#!/usr/bin/env python3
"""Validate complete byte-buffer evidence, without interpreting it as device FPS or PTY throughput."""
import argparse
import base64
import hashlib
import json
import os
import re
import statistics
import subprocess
import xml.etree.ElementTree as ET
import zlib
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = '6e81739d8790c524ae25b010dcd6e21ee0ee0a4d'
CAPACITY = 256 * 1024
CHUNK = 4096
BENCH = 'terminal-byte-buffer-bench-v1'
PTY = 'terminal-byte-buffer-pty-v1'
SCENARIOS = ('saturatedAppend', 'smallRead')
PACKAGE = 'me.rerere.rikkahub.data.container.'
BENCH_CLASS = PACKAGE + 'TerminalByteBufferBenchmarkTest'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def records(text, marker):
    values = []
    for line in text.splitlines():
        if marker not in line:
            continue
        value = json.loads(line.split(marker, 1)[1])
        require(type(value) is dict, 'Evidence record must be an object')
        values.append(value)
    return values


def integer(record, name, minimum=0):
    value = record.get(name)
    require(type(value) is int and value >= minimum, 'Invalid integer: ' + name)
    return value


def equal(record, name, expected):
    require(type(record.get(name)) is type(expected) and record[name] == expected, 'Incorrect field: ' + name)


@lru_cache(maxsize=4)
def benchmark_expected(scenario, iterations):
    seed = bytes(33 + i % 80 for i in range(CAPACITY))
    chunk = bytes(33 + i % 80 for i in range(CHUNK))
    retained = (seed + chunk * (iterations + 1 if scenario == 'saturatedAppend' else 1))[-CAPACITY:]
    checksum = 0
    if scenario == 'smallRead':
        mask = (1 << 64) - 1
        for i in range(iterations):
            start = i * 97 % (CAPACITY - 64)
            java_hash = 0
            for byte in retained[start:start + 64]:
                java_hash = (31 * java_hash + byte) & 0xffffffff
            if java_hash >= 1 << 31:
                java_hash -= 1 << 32
            checksum = (((checksum << 1) | (checksum >> 63)) ^ java_hash ^ (CHUNK + start)) & mask
        if checksum >= 1 << 63:
            checksum -= 1 << 64
    return hashlib.sha256(retained).hexdigest(), checksum


def validate_benchmark(text, sha, run):
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[0-9]+', str(run)), 'Missing exact source/run')
    envs = records(text, 'BUFFER_BENCH_ENV ')
    require(len(envs) == 1, 'Expected one JVM environment')
    env = envs[0]
    equal(env, 'suite', BENCH)
    equal(env, 'sourceSha', sha)
    equal(env, 'runId', str(run))
    require(type(env.get('environment')) is dict, 'Missing JVM environment')
    require(set(env['environment']) == {'java.version', 'java.vm.name', 'os.name', 'os.arch'}, 'Wrong JVM fields')
    require(all(type(v) is str and 0 < len(v) <= 200 for v in env['environment'].values()), 'Invalid JVM values')
    samples = records(text, 'BUFFER_BENCH_SAMPLE ')
    expected_keys = [(scenario, phase, pair, arm, position)
                     for scenario in SCENARIOS
                     for phase, pairs in (('warmup', 2), ('measurement', 4))
                     for pair in range(pairs)
                     for position, arm in enumerate(('legacy', 'ring') if pair % 2 == 0 else ('ring', 'legacy'))]
    require(len(samples) == len(expected_keys), 'Expected exactly 8 warmup and 16 measurement samples')
    for row, (scenario, phase, pair, arm, position) in zip(samples, expected_keys):
        for name, value in dict(suite=BENCH, scenario=scenario, phase=phase, pair=pair, arm=arm,
                                position=position, capacity=CAPACITY, chunkBytes=CHUNK, readBytes=64,
                                iterations=64 if phase == 'warmup' else 256, retainedBytes=CAPACITY).items():
            equal(row, name, value)
        integer(row, 'elapsedNanos', 1)
        integer(row, 'allocatedBytes')
        expected_hash, expected_checksum = benchmark_expected(scenario, row['iterations'])
        equal(row, 'sha256', expected_hash)
        equal(row, 'checksum', expected_checksum)
    summary = {}
    for scenario in SCENARIOS:
        measured = [r for r in samples if r['scenario'] == scenario and r['phase'] == 'measurement']
        for pair in range(4):
            arms = {r['arm']: r for r in measured if r['pair'] == pair}
            require(arms['ring']['allocatedBytes'] < arms['legacy']['allocatedBytes'] / 2,
                    'Measured ring allocation did not improve for every assigned pair')
        summary[scenario] = {arm: dict(
            nsPerOperation=statistics.median(r['elapsedNanos'] / r['iterations'] for r in measured if r['arm'] == arm),
            allocatedBytesPerOperation=statistics.median(
                r['allocatedBytes'] / r['iterations'] for r in measured if r['arm'] == arm),
        ) for arm in ('legacy', 'ring')}
    return dict(suite=BENCH, environment=env, samples=samples, summary=summary,
                warmupSamples=8, measurementSamples=16,
                note='Same JVM AB/BA saturated-buffer microbenchmark; construction excluded. Not Android/phone FPS or PTY throughput.')


def pty_expected():
    data = bytearray(b'BUFFER_READY\r\n')
    rows = []
    for round_number in range(3):
        payload = ''.join(f'{round_number}:{i:05d}:abcdefghijklmnopqrstuvwxyz0123456789\x1b[32m中文🙂\x1b[0m\r\n'
                          for i in range(7000)).encode() + f'BUFFER_DONE:{round_number}\r\n'.encode()
        data.extend(payload)
        tail = data[-CAPACITY:]
        rows.append(dict(suite=PTY, round=round_number, capacity=CAPACITY, payloadBytes=len(payload),
                         totalBytes=len(data), baseOffset=len(data) - CAPACITY, retainedBytes=CAPACITY,
                         readChecks=1 + 45 * (round_number + 1), backend='native-pty', historyRows=1000,
                         sha256=hashlib.sha256(tail).hexdigest()))
    final = dict(suite=PTY, readChecks=137, totalBytes=len(data), exitCode=0, removed=True)
    return rows, final


def validate_pty(text):
    rows = records(text, 'BYTE_BUFFER_PTY ')
    exits = records(text, 'BYTE_BUFFER_EXIT ')
    expected, final = pty_expected()
    require(len(rows) == 3 and len(exits) == 1, 'Missing/duplicate PTY rounds or exit receipt')
    for row, target in zip(rows, expected):
        for name, value in target.items():
            equal(row, name, value)
    for name, value in final.items():
        equal(exits[0], name, value)
    return dict(suite=PTY, rounds=rows, final=exits[0],
                note='Actual manager/native-PTY buffer reads, offsets, UTF-8 and exit tail; no performance claim.')


def source_proof():
    manager = 'app/src/main/java/me/rerere/rikkahub/data/container/BackgroundProcessManager.kt'
    baseline = subprocess.check_output(['git', 'show', BASELINE + ':' + manager], cwd=ROOT, timeout=20)
    old = baseline.decode().split('private class SessionOutputBuffer(', 1)[1].split('\n    data class BufferRead(', 1)[0]
    old_body = old.split(') {', 1)[1].strip().replace('BufferRead', 'TerminalByteRingBuffer.Read')
    oracle_path = ROOT / 'app/src/test/java/me/rerere/rikkahub/data/container/LegacyTerminalByteBuffer.kt'
    oracle_body = oracle_path.read_text().split('internal class LegacyTerminalByteBuffer(', 1)[1].split(') {', 1)[1].strip()
    require(re.sub(r'\s+', '', old_body) == re.sub(r'\s+', '', oracle_body), 'Frozen reference differs from baseline')
    paths = [manager, 'app/src/main/java/me/rerere/rikkahub/data/container/TerminalByteRingBuffer.kt',
             *['app/src/test/java/me/rerere/rikkahub/data/container/' + name + '.kt' for name in
               ('TerminalByteRingBufferTest', 'LegacyTerminalByteBuffer', 'TerminalByteBufferWiringTest',
                'TerminalByteBufferBenchmarkTest')],
             '.github/scripts/terminal-byte-buffer-reports.py', '.github/scripts/test_terminal_byte_buffer.py',
             '.github/workflows/terminal-emulator-tests.yml']
    return dict(baselineSha=BASELINE, oracleVerified=True, baselineManagerSha256=hashlib.sha256(baseline).hexdigest(),
                sources={name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in paths})


def from_junit(root):
    path = root / ('TEST-' + BENCH_CLASS + '.xml')
    require(path.is_file(), 'Missing benchmark JUnit XML')
    tree = ET.parse(path)
    cases = list(tree.getroot().iter('testcase'))
    require(len(cases) == 1 and cases[0].get('classname') == BENCH_CLASS, 'Incorrect benchmark test inventory')
    require(not any(list(tree.getroot().iter(tag)) for tag in ('failure', 'error', 'skipped')), 'Benchmark JUnit failed/skipped')
    return '\n'.join(''.join(node.itertext()) for node in tree.getroot().iter('system-out'))


def publish(title, data):
    value = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
    require(len(value) <= 2800, 'Evidence notice exceeds budget')
    print('::notice title=' + title + '::' + value.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        report = validate_benchmark(from_junit(args.root), os.environ.get('GITHUB_SHA', ''),
                                    os.environ.get('GITHUB_RUN_ID', ''))
        report['provenance'] = source_proof()
        raw = json.dumps(report, ensure_ascii=False, separators=(',', ':')).encode()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(raw)
        publish('Terminal byte-buffer benchmark verified', dict(
            sourceSha=report['environment']['sourceSha'], runId=report['environment']['runId'],
            warmupSamples=8, measurementSamples=16, summary=report['summary'], note=report['note'],
            sha256=hashlib.sha256(raw).hexdigest()))
        encoded = base64.b64encode(zlib.compress(raw, 9)).decode()
        parts = [encoded[i:i + 2400] for i in range(0, len(encoded), 2400)]
        require(len(parts) <= 8, 'Benchmark evidence exceeds annotation budget')
        for index, part in enumerate(parts, 1):
            publish(f'Terminal byte-buffer evidence {index}/{len(parts)}',
                    dict(codec='zlib-base64', sha256=hashlib.sha256(raw).hexdigest(), data=part))
        return 0
    except (ValueError, OSError, subprocess.SubprocessError, ET.ParseError) as error:
        message = str(error)[:1600].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::error title=Terminal byte-buffer evidence failed::' + message)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
