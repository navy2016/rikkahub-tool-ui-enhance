#!/usr/bin/env python3
"""Revalidate hash-bound V5 evidence; count a complete matrix without pooling independent runners."""
import argparse
import csv
import gzip
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

from production_ci import require_preflight, source_files
from production_summary import (CLASS, MODES, PUBLICATION, SCENARIOS, SIZES, VERSION, report, result_key,
                                runs, validate, validate_publications)

MAX_DOCUMENT_BYTES = 64 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def cases_from(document):
    require(isinstance(document, dict), 'Missing raw benchmark document')
    cases = document.get('benchmarks')
    require(isinstance(cases, list) and bool(cases), 'Missing benchmark cases')
    results = {}
    for case in cases:
        require(isinstance(case, dict) and case.get('className') == CLASS, 'Wrong benchmark class')
        key = result_key(case.get('name', ''))
        require(key not in results, 'Duplicate benchmark case')
        results[key] = case
    return results, document.get('context', {})


def verify_evidence(evidence, sha, run_id, sources, production_tree):
    require(isinstance(evidence, dict), 'Missing evidence document')
    request = evidence.get('request', {})
    manifest = evidence.get('manifest', {})
    require(manifest.get('sourceSha') == sha and manifest.get('buildRun') == run_id and
            manifest.get('suite') == VERSION and manifest.get('publication') == PUBLICATION and
            manifest.get('sources') == sources and manifest.get('productionTree') == production_tree,
            'Evidence does not match exact SHA/run/source tree')
    scenario = request.get('scenario')
    require(scenario in SCENARIOS and request.get('smoke') in ('true', 'false'), 'Invalid evidence scenario/kind')
    smoke = request['smoke'] == 'true'
    group = request.get('groupId')
    require(isinstance(group, str) and re.fullmatch(
        re.escape(run_id) + r'-[1-9][0-9]*-' + scenario + ('-smoke' if smoke else '-full'), group),
        'Invalid evidence invocation identity')
    iterations = request.get('iterations')
    require(type(iterations) is int and 1 <= iterations <= 50 and (not smoke or iterations == 1),
            'Invalid evidence repetition count')
    apks = manifest.get('apks', {})
    expected_hashes = {}
    for role, package in (('target', 'me.rerere.rikkahub.terminalbenchmark'),
                          ('driver', 'me.rerere.rikkahub.terminalbenchmark.test')):
        apk = apks.get(role, {})
        value = apk.get('sha256', '')
        require(isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) and
                apk.get('package') == package and apk.get('file') == role + '.apk' and
                type(apk.get('bytes')) is int and apk['bytes'] > 0, 'Invalid benchmark APK identity')
        expected_hashes[role] = value
    require(evidence.get('installedApkHashes') == expected_hashes, 'Installed APK identity mismatch')
    require(request.get('sourceSha') == sha and request.get('runId') == run_id and
            request.get('suite') == VERSION and request.get('publication') == PUBLICATION and
            request.get('targetApkSha256') == expected_hashes['target'] and
            request.get('driverApkSha256') == expected_hashes['driver'], 'Evidence request mismatch')
    results, context = cases_from(evidence.get('document'))
    validate(results, context, sha, run_id, group, scenario, iterations, smoke,
             expected_hashes['target'], expected_hashes['driver'])
    raw = evidence.get('rawDocuments')
    hashes = evidence.get('resultFiles')
    require(isinstance(raw, dict) and bool(raw) and isinstance(hashes, dict) and set(raw) == set(hashes),
            'Missing original benchmark JSON')
    for name, text in raw.items():
        require(isinstance(name, str) and not Path(name).is_absolute() and '..' not in Path(name).parts,
                'Unsafe result path')
        require(isinstance(text, str) and digest(text.encode()) == hashes[name], 'Raw benchmark JSON hash mismatch')
        raw_results, raw_context = cases_from(json.loads(text))
        require(raw_results == results and raw_context == context, 'Raw/normalized benchmark document mismatch')
    records = evidence.get('publicationRecords')
    require(isinstance(records, list) and all(isinstance(line, str) for line in records),
            'Missing publication records')
    publication = validate_publications(records, scenario, iterations, smoke)
    require(publication == evidence.get('publicationEvidence'), 'Publication receipt summary mismatch')
    device = evidence.get('device')
    require(isinstance(device, dict) and set(device) == {'bootId', 'fingerprint', 'size', 'density', 'heap', 'ime'} and
            all(isinstance(value, str) and value for value in device.values()), 'Missing device identity')
    receipt = evidence.get('validation', {})
    expected_cases = len(MODES) * (1 if smoke else len(SIZES))
    require(all(receipt.get(key) == value for key, value in request.items()) and
            receipt.get('verified') is True and type(receipt.get('cases')) is int and
            receipt['cases'] == expected_cases and type(receipt.get('measuredLaunches')) is int and
            receipt['measuredLaunches'] == expected_cases * iterations and receipt.get('device') == device,
            'Invalid completion receipt')
    preflight = evidence.get('preflight')
    if smoke:
        require(preflight is None and receipt.get('preflightGroup') is None, 'Smoke cannot claim a baseline')
    else:
        require_preflight(manifest, scenario, preflight)
        require(preflight['device'] == device and receipt.get('preflightGroup') == preflight['groupId'],
                'Preflight boot/invocation mismatch')
    require(evidence.get('summary') == report(results, context, sha, scenario, smoke), 'Rendered report mismatch')
    return scenario, smoke, results


def collect(root, sha, run_id, scenario, iterations, sources=None, production_tree=None):
    require(re.fullmatch('[a-f0-9]{40}', sha) and re.fullmatch('[0-9]+', run_id), 'Invalid SHA/run ID')
    require(scenario == 'all' or scenario in SCENARIOS, 'Invalid requested scenario')
    require(type(iterations) is int and 1 <= iterations <= 50, 'Invalid requested repetitions')
    sources = source_files() if sources is None else sources
    if production_tree is None:
        production_tree = subprocess.check_output(['git', 'rev-parse', 'HEAD:app/src/main'], text=True).strip()
    selected = SCENARIOS if scenario == 'all' else (scenario,)
    groups, identities, table = {}, set(), []
    for path in sorted(root.rglob('evidence.json.gz')):
        require(path.stat().st_size <= MAX_DOCUMENT_BYTES, 'Compressed evidence exceeds size bound')
        with gzip.open(path, 'rb') as stream:
            data = stream.read(MAX_DOCUMENT_BYTES + 1)
        require(len(data) <= MAX_DOCUMENT_BYTES, 'Decompressed evidence exceeds size bound')
        evidence = json.loads(data)
        integrity = json.loads(path.with_name('integrity.json').read_text())
        require(integrity.get('jsonSha256') == digest(data) and
                integrity.get('gzipSha256') == digest(path.read_bytes()), 'Evidence integrity mismatch')
        name, smoke, results = verify_evidence(evidence, sha, run_id, sources, production_tree)
        request = evidence['request']
        require(name in selected and (name, smoke) not in groups, 'Duplicate or unexpected scenario evidence')
        require(all(integrity.get(key) == request.get(key) for key in ('sourceSha', 'runId', 'groupId', 'suite')) and
                integrity.get('rawResultSha256') == evidence['resultFiles'] and
                integrity.get('measuredLaunches') == evidence['validation']['measuredLaunches'],
                'Integrity receipt does not match invocation')
        require(smoke or request['iterations'] == iterations, 'Baseline repetition count mismatch')
        groups[name, smoke] = dict(evidence=evidence, integrity=integrity, file=str(path.relative_to(root)))
        identities.add((evidence['manifest']['apks']['target']['sha256'],
                        evidence['manifest']['apks']['driver']['sha256']))
        if not smoke:
            for (size, _, mode), case in results.items():
                primary = 'mountSumMs' if name == 'initialCompose' else 'operationSumMs'
                for index, value in enumerate(runs(case, primary)):
                    table.append(dict(scenario=name, history=size, mode=mode, iteration=index,
                                      metric=primary, valueMs=value, groupId=request['groupId']))
    require(set(groups) == {(name, smoke) for name in selected for smoke in (False, True)},
            'Incomplete baseline: every scenario needs distinct smoke and full evidence')
    require(len(identities) == 1, 'Mixed APK pairs across scenarios')
    for name in selected:
        full = groups[name, False]['evidence']
        smoke = groups[name, True]['evidence']
        require(full['preflight'] == smoke['validation'] and full['device'] == smoke['device'],
                'Full baseline is not bound to its actual verified preflight')
    result = dict(suite=VERSION, sourceSha=sha, runId=run_id, complete=scenario == 'all', scenarios=list(selected),
                  cases=len(selected) * len(SIZES) * len(MODES), iterations=iterations,
                  measurements=len(table), preflightMeasurements=len(selected) * len(MODES),
                  productionTree=production_tree, groups={
                      name: dict(full=groups[name, False], smoke=groups[name, True]) for name in selected})
    return result, table


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--sha', required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--scenario', choices=('all', *SCENARIOS), default='all')
    parser.add_argument('--iterations', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result, samples = collect(args.root, args.sha, args.run_id, args.scenario, args.iterations)
    args.output.mkdir(parents=True, exist_ok=False)
    data = json.dumps(result, separators=(',', ':'), ensure_ascii=False).encode()
    (args.output / 'baseline.json.gz').write_bytes(gzip.compress(data, mtime=0))
    receipt = {key: value for key, value in result.items() if key != 'groups'}
    receipt['jsonSha256'] = digest(data)
    receipt['gzipSha256'] = digest((args.output / 'baseline.json.gz').read_bytes())
    (args.output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    with (args.output / 'operation-samples.csv').open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(samples[0]))
        writer.writeheader()
        writer.writerows(samples)
    text = (f'# V5 {"complete" if result["complete"] else "scenario"} component baseline\n\n'
            f'SHA `{args.sha}`, run `{args.run_id}`: {result["cases"]} cases / '
            f'{result["measurements"]} measurements; {result["preflightMeasurements"]} smoke measurements excluded.\n\n'
            'Same APK pair, separate scenario invocations. Do not pool devices or compare with archived V4 timings.\n'
            'Synthetic component operations, not real PTY latency, process startup, phone FPS or GPU presentation.\n\n')
    text += '\n\n'.join(result['groups'][name]['full']['evidence']['summary'] for name in result['scenarios'])
    (args.output / 'baseline.md').write_text(text)
    if step_summary := os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(step_summary, 'a') as stream:
            stream.write(text)
    print('::notice title=V5 baseline verified::' + json.dumps(receipt), flush=True)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        text = str(error)[:2200].replace('%', '%25').replace('\n', '%0A').replace('\r', '%0D')
        print('::error title=V5 baseline rejected::' + text, flush=True)
        sys.exit(1)
