#!/usr/bin/env python3
"""Validate ONE three-mode production-viewport invocation; never merge independent runs."""
import argparse
import json
import math
import re
import statistics
from pathlib import Path

from summarize import format_number, median, overrun_percent, per_operation, percentile

VERSION = "production-viewport-v5"
PUBLICATION = "atomic-snapshot-chunk-provenance-v1"
SIZES = (1000, 5000, 10000)
MODES = ("chunkedLayers", "lazyHistory", "lazyHistoryIme")
SCENARIOS = ("initialCompose", "activeRowUpdate", "appendAndTrim", "semanticJump", "imeRoundTrip", "detachRestore")
CLASS = "me.rerere.rikkahub.benchmark.ProductionTerminalBenchmark"


def result_key(name):
    match = re.fullmatch(r"production\[history=(1000|5000|10000),scenario=([A-Za-z]+),mode=([A-Za-z]+)\]", name)
    if not match or match[2] not in SCENARIOS or match[3] not in MODES:
        raise ValueError(f"Unrecognized production benchmark name: {name}")
    return int(match[1]), match[2], match[3]


def load(root):
    source = None
    for path in sorted(root.rglob("*-benchmarkData.json")):
        document = json.loads(path.read_text())
        cases = [case for case in document.get("benchmarks", []) if case.get("className") == CLASS]
        if not cases:
            continue
        current = {"context": document.get("context", {}), "benchmarks": cases}
        if source is not None and current != source:
            raise ValueError("Different result documents; do not combine runs or checkpoint prefixes")
        source = current
    if source is None:
        raise ValueError("No production viewport benchmark document; legacy renderer results are not substitutes")
    results = {}
    for case in source["benchmarks"]:
        key = result_key(case.get("name", ""))
        if key in results:
            raise ValueError(f"Duplicate case inside one document: {key}")
        results[key] = case
    return results, source["context"]


def runs(case, metric):
    values = case.get("metrics", {}).get(metric, {}).get("runs")
    expected = case["repeatIterations"]
    if (not isinstance(values, list) or len(values) != expected or
            any(type(value) not in (int, float) or not math.isfinite(value) for value in values)):
        raise ValueError(f"Missing/invalid per-iteration metric {metric}: {case['name']}")
    return values


def trace(case, prefix, count=None, minimum=None):
    counts = runs(case, prefix + "Count")
    if count is not None and any(value != count for value in counts):
        raise ValueError(f"Incorrect {prefix} count, expected {count}: {case['name']}")
    if minimum is not None and any(value < minimum for value in counts):
        raise ValueError(f"Missing {prefix} calls: {case['name']}")
    if any(value < 0 or value != int(value) for value in counts):
        raise ValueError(f"Invalid {prefix} count: {case['name']}")
    if count != 0:
        for suffix in ("SumMs", "MaxMs"):
            if any(value <= 0 for value in runs(case, prefix + suffix)):
                raise ValueError(f"Missing {prefix} duration: {case['name']}")


def validate(results, context, sha, run_id, group_id, scenario, iterations, smoke=False, apk_sha=None,
             driver_sha=None):
    if scenario not in SCENARIOS or not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("Invalid scenario/SHA")
    payload = context.get("payload", {})
    if any(not isinstance(value, str) or not re.fullmatch(r"[a-f0-9]{64}", value)
           for value in (apk_sha, driver_sha)):
        raise ValueError("Expected verified target and driver APK hashes")
    expected_payload = {"sourceSha": sha, "runId": str(run_id), "groupId": group_id,
                        "suite": VERSION, "scenario": scenario, "smoke": str(smoke).lower(),
                        "targetApkSha256": apk_sha, "driverApkSha256": driver_sha, "publication": PUBLICATION}
    if any(payload.get(key) != value for key, value in expected_payload.items()):
        raise ValueError(f"Missing/mismatched provenance payload; expected {expected_payload}")
    expected = {(size, scenario, mode) for size in ((1000,) if smoke else SIZES) for mode in MODES}
    if set(results) != expected:
        raise ValueError(f"Incomplete production mode matrix: missing={expected - set(results)}, extra={set(results) - expected}")
    for (_, _, mode), case in results.items():
        if type(case.get("repeatIterations")) is not int or case["repeatIterations"] != iterations or iterations <= 0:
            raise ValueError(f"Repetition mismatch: {case['name']}")
        if any(value <= 0 or int(value) != value for value in runs(case, "frameCount")):
            raise ValueError(f"No frame samples: {case['name']}")
        for metric in ("frameDurationCpuMs", "frameOverrunMs"):
            samples = case.get("sampledMetrics", {}).get(metric, {}).get("runs")
            if (not isinstance(samples, list) or len(samples) != iterations or
                    any(not isinstance(sample, list) or not sample or
                        any(type(value) not in (int, float) or not math.isfinite(value) for value in sample)
                        for sample in samples) or percentile(case, metric, "P95") is None):
                raise ValueError(f"Incomplete frame metric {metric}: {case['name']}")
            if metric == "frameDurationCpuMs" and any(value < 0 for sample in samples for value in sample):
                raise ValueError(f"Negative CPU duration: {case['name']}")
        for metric in ("memoryRssAnonMaxKb",):
            if metric in case.get("metrics", {}) and any(value < 0 for value in runs(case, metric)):
                raise ValueError(f"Invalid optional memory metric: {case['name']}")
        # Fixed-size parents can reuse their root measurement during updates/scrolls. A zero
        # root-measure count is not evidence that children performed no layout work.
        if scenario in ("initialCompose", "imeRoundTrip", "detachRestore"):
            trace(case, "measure", minimum=1)
        trace(case, "draw", minimum=1)
        # Initial/update/remount MUST exercise the production layout observer. Jumps exercise
        # production effects without necessarily changing the eager layout-observer inputs.
        if scenario != "semanticJump":
            trace(case, "viewport", minimum=1)
        updates = {"activeRowUpdate": 30, "appendAndTrim": 30, "imeRoundTrip": 8}.get(scenario, 0)
        frame_work = 1 if scenario in ("initialCompose", "detachRestore") else updates
        trace(case, "renderFrame", count=frame_work)
        trace(case, "rowSync", count=frame_work)
        trace(case, "feed", count=1 if scenario == "detachRestore" else updates)
        trace(case, "output", count=updates)
        trace(case, "mount", count=int(scenario == "initialCompose"))
        trace(case, "operation", count=int(scenario != "initialCompose"))
        validations = {"initialCompose": 1, "activeRowUpdate": 30, "appendAndTrim": 30,
                       "semanticJump": 2, "imeRoundTrip": 11, "detachRestore": 1}[scenario]
        trace(case, "validation", count=validations)
        for prefix, owner in (("jumpTop", "semanticJump"), ("jumpTail", "semanticJump"),
                              ("imeShow", "imeRoundTrip"), ("imeHide", "imeRoundTrip"),
                              ("explicitRetry", "imeRoundTrip"), ("detach", "detachRestore"),
                              ("restore", "detachRestore")):
            trace(case, prefix, count=int(scenario == owner))
        if scenario == "semanticJump":
            trace(case, "scrollEffect", minimum=2)
        if mode in ("lazyHistory", "lazyHistoryIme"):
            if scenario in ("initialCompose", "activeRowUpdate", "appendAndTrim", "imeRoundTrip", "detachRestore"):
                minimum = (30 if scenario in ("activeRowUpdate", "appendAndTrim") else
                           8 if mode == "lazyHistoryIme" and scenario == "imeRoundTrip" else 1)
                trace(case, "width", minimum=minimum)
            if scenario == "imeRoundTrip" and mode == "lazyHistory":
                trace(case, "eagerGeometry", minimum=1)
            if mode == "lazyHistoryIme":
                trace(case, "eagerGeometry", count=0)
        else:
            trace(case, "width", count=0)
            trace(case, "eagerGeometry", minimum=1)


WORK_FIELDS = ('publications', 'panelCommits', 'pairingChecks', 'transcriptEntries', 'cachedEntries',
               'viewBuilds', 'chunkWrappers', 'rowSlices', 'liveViews')


def validate_publications(lines, scenario, iterations, smoke=False):
    """Bind complete target receipts to driver-marked MEASURED launches, not unmarked setup work."""
    target, measured = {}, {}
    for line in lines:
        marker = next((value for value in ('PUBLICATION_WORK ', 'MEASURED_LAUNCH ') if value in line), None)
        if marker is None:
            continue
        row = json.loads(line.split(marker, 1)[1])
        if not isinstance(row, dict):
            raise ValueError('Invalid publication record')
        token = row.get('token')
        if not isinstance(token, str) or not re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', token):
            raise ValueError('Invalid publication launch token')
        if row.get('suite') != VERSION or row.get('scenario') != scenario:
            raise ValueError('Publication protocol or scenario mismatch')
        if type(row.get('size')) is not int or row['size'] not in ((1000,) if smoke else SIZES):
            raise ValueError('Publication size mismatch')
        if row.get('mode') not in MODES:
            raise ValueError('Publication mode mismatch')
        if marker == 'MEASURED_LAUNCH ':
            if set(row) != {'suite', 'scenario', 'size', 'mode', 'token', 'iteration'}:
                raise ValueError('Invalid measured launch fields')
            if token in measured or type(row['iteration']) is not int or row['iteration'] not in range(iterations):
                raise ValueError('Duplicate or invalid measured launch')
            measured[token] = row
        else:
            if set(row) != {'suite', 'publication', 'scenario', 'size', 'mode', 'token', 'phase',
                            'viewportGeneration', 'virtual', 'work', 'detached'} or row['publication'] != PUBLICATION:
                raise ValueError('Invalid publication receipt fields')
            if row['phase'] not in ('mounted', 'done') or (token, row['phase']) in target:
                raise ValueError('Duplicate or invalid publication phase')
            target[token, row['phase']] = row
    expected = {(size, mode, iteration) for size in ((1000,) if smoke else SIZES)
                for mode in MODES for iteration in range(iterations)}
    actual = [(r['size'], r['mode'], r['iteration']) for r in measured.values()]
    if set(actual) != expected or len(actual) != len(expected):
        raise ValueError('Incomplete or duplicate measured publication matrix')

    def work(value):
        if not isinstance(value, dict) or set(value) != set(WORK_FIELDS):
            raise ValueError('Incomplete publication work')
        if any(type(value[k]) is not int or value[k] < 0 for k in WORK_FIELDS):
            raise ValueError('Invalid publication counter')
        if (value['liveViews'] not in (0, 1) or value['panelCommits'] < value['publications'] or
                value['transcriptEntries'] != value['cachedEntries'] or
                value['rowSlices'] != value['chunkWrappers'] + value['viewBuilds'] or
                value['viewBuilds'] > value['chunkWrappers']):
            raise ValueError('Inconsistent publication/cache work')
        return value

    def phase(token, name, driver):
        row = target.get((token, name))
        if row is None or (row['size'], row['mode']) != (driver['size'], driver['mode']):
            raise ValueError('Missing or mismatched target publication receipt')
        virtual = driver['mode'] != 'chunkedLayers'
        if type(row['virtual']) is not bool or row['virtual'] != virtual:
            raise ValueError('Publication renderer mismatch')
        if type(row['viewportGeneration']) is not int:
            raise ValueError('Invalid viewport generation')
        current = work(row['work'])
        if current['publications'] < 1 or current['pairingChecks'] < 1:
            raise ValueError('Missing publication or pairing checks')
        if current['liveViews'] != int(not virtual) or (not virtual and current['cachedEntries'] < 1):
            raise ValueError('Publication did not use the correct chunk cache path')
        return row

    receipts = []
    for token, driver in measured.items():
        mounted = phase(token, 'mounted', driver)
        before = mounted['work']
        if (mounted['viewportGeneration'] != 1 or mounted['detached'] is not None or
                before['publications'] != 1 or before['pairingChecks'] != 1):
            raise ValueError('Invalid initial publication receipt')
        if scenario == 'initialCompose':
            if (token, 'done') in target:
                raise ValueError('Cold mount includes an unexpected operation')
            done = None
        else:
            done = phase(token, 'done', driver)
            after = done['work']
            if scenario == 'detachRestore':
                detached = work(done['detached'])
                if (done['viewportGeneration'] != 2 or after['publications'] != 1 or after['pairingChecks'] != 1 or
                        detached['publications'] != 1 or detached['pairingChecks'] != 1 or detached['liveViews'] != 0):
                    raise ValueError('Detached publication not released before replacement')
            else:
                updates = {'activeRowUpdate': 30, 'appendAndTrim': 30, 'imeRoundTrip': 8}.get(scenario, 0)
                checks = {'activeRowUpdate': 30, 'appendAndTrim': 30, 'imeRoundTrip': 11, 'semanticJump': 2}[scenario]
                if (done['viewportGeneration'] != 1 or done['detached'] is not None or
                        after['publications'] != 1 + updates or after['pairingChecks'] != 1 + checks):
                    raise ValueError('Incorrect publication/update validation counts')
                if any(after[k] < before[k] for k in WORK_FIELDS if k != 'liveViews'):
                    raise ValueError('Publication counters went backwards')
                if scenario == 'activeRowUpdate' and driver['mode'] == 'chunkedLayers':
                    if any(after[k] != before[k] for k in ('viewBuilds', 'chunkWrappers', 'rowSlices')):
                        raise ValueError('Active output rebuilt unchanged chunk views')
                if scenario == 'imeRoundTrip' and driver['mode'] == 'lazyHistory' and after['cachedEntries'] <= before['cachedEntries']:
                    raise ValueError('IME fallback bypassed the provenance path')
        receipts.append(dict(driver=driver, mounted=mounted, done=done))
    return dict(measured=receipts, excludedTokens=sorted({token for token, _ in target} - set(measured)))


def ordered(results):
    return sorted(results.items(), key=lambda item: (item[0][0], MODES.index(item[0][2])))


def report(results, context, sha, scenario, smoke):
    lines = [f"# Production viewport: {scenario}", "",
             f"Commit: `{sha}`. Contract: `{VERSION}`. Smoke-only: `{str(smoke).lower()}`.", "",
             "All three actual production modes share one APK, device and instrumentation invocation in this scenario.",
             "Other scenarios may use different runners; do not aggregate their samples or calculate cross-group ratios.",
             "This is an unminified Release-derived component harness, not ProcessSessionPage, PTY/input echo or app startup.",
             "Updates include production reconciliation and two stable draw confirmations. Validation/wait overhead is included.",
             "V5 publishes one immutable frame/row snapshot and exercises chunk provenance; V4/V3 are not relabelled.",
             "Root measure includes lazy subcomposition. Phase durations overlap; do not subtract them from frame percentiles.",
             "Missing memory is —, not zero. RSS anon is sampled anonymous RSS, not PSS/Java/native/GPU peak memory.", "",
             "| History | Mode | n | Mount ms | Operation ms | Output/op ms | CPU p95 ms | Overrun % | RSS anon MiB |",
             "| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for (size, _, mode), case in ordered(results):
        rss = median(case, "memoryRssAnonMaxKb")
        values = [str(size), mode, str(case["repeatIterations"]), format_number(median(case, "mountSumMs")
                  if scenario == "initialCompose" else None),
                  format_number(median(case, "operationSumMs") if scenario != "initialCompose" else None),
                  format_number(per_operation(case, "output")),
                  format_number(percentile(case, "frameDurationCpuMs", "P95")), format_number(overrun_percent(case)),
                  format_number(rss / 1024 if rss is not None else None)]
        lines.append("| " + " | ".join(values) + " |")
    lines += ["", "## Primary operation samples (ms, not a reliable latency P95 at small n)", "",
              "| History | Mode | Raw iterations | Median | Min | Max |",
              "| ---: | --- | --- | ---: | ---: | ---: |"]
    for (size, _, mode), case in ordered(results):
        values = runs(case, 'mountSumMs' if scenario == 'initialCompose' else 'operationSumMs')
        lines.append('| ' + ' | '.join([str(size), mode, ', '.join(format_number(v) for v in values),
                     format_number(statistics.median(values)), format_number(min(values)),
                     format_number(max(values))]) + ' |')
    lines += ["", "CPU p95 above describes sampled frames, not independent launch/operation latency percentiles.",
              "Root measure excludes some child-only layout; trace maxima are scoped phases, not all UI long tasks."]
    lines += ["", "## Phase means per invocation (median across iterations)", "",
              "| History | Mode | Feed | Frame | Row sync | Width | Eager geometry | Viewport | Measure | Draw | Scroll effect max |",
              "| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for (size, _, mode), case in ordered(results):
        values = [str(size), mode] + [format_number(per_operation(case, prefix)) for prefix in
                   ("feed", "renderFrame", "rowSync", "width", "eagerGeometry", "viewport", "measure", "draw")]
        values.append(format_number(median(case, "scrollEffectMaxMs")))
        lines.append("| " + " | ".join(values) + " |")
    if scenario in ("semanticJump", "imeRoundTrip", "detachRestore"):
        prefixes = {"semanticJump": ("jumpTop", "jumpTail"), "imeRoundTrip": ("imeShow", "imeHide", "explicitRetry"),
                    "detachRestore": ("detach", "restore")}[scenario]
        lines += ["", "## Transitions (ms)", "", "| History | Mode | " + " | ".join(prefixes) + " |",
                  "| ---: | --- | " + " | ".join("---:" for _ in prefixes) + " |"]
        for (size, _, mode), case in ordered(results):
            lines.append("| " + " | ".join([str(size), mode] +
                         [format_number(median(case, prefix + "SumMs")) for prefix in prefixes]) + " |")
    lines += ["", "No FPS/improvement claim is derived from this diagnostic run. Old renderer-harness results are not a baseline pair.",
              "", "## Device and provenance", "", "```json", json.dumps(context, indent=2, ensure_ascii=False), "```", ""]
    return "\n".join(lines)


def notices(results, sha, scenario, smoke):
    lines = [f"{VERSION} {scenario} sha={sha} smoke={smoke}; one scenario/device/invocation, not FPS.",
             "History | Mode | n | Mount ms | Operation ms | Output/op | CPU p95 | Width/call | RSS anon MiB"]
    for (size, _, mode), case in ordered(results):
        rss = median(case, "memoryRssAnonMaxKb")
        lines.append(" | ".join([str(size), mode, str(case["repeatIterations"]),
            format_number(median(case, "mountSumMs") if scenario == "initialCompose" else None),
            format_number(median(case, "operationSumMs") if scenario != "initialCompose" else None),
            format_number(per_operation(case, "output")), format_number(percentile(case, "frameDurationCpuMs", "P95")),
            format_number(per_operation(case, "width")), format_number(rss / 1024 if rss is not None else None)]))
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--group-id", required=True)
    parser.add_argument("--apk-sha", required=True)
    parser.add_argument("--driver-sha", required=True)
    parser.add_argument("--publication-log", type=Path, required=True)
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    parser.add_argument("--iterations", required=True, type=int)
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--github-annotation", action="store_true")
    args = parser.parse_args()
    results, context = load(args.root)
    validate(results, context, args.sha, args.run_id, args.group_id, args.scenario, args.iterations,
             args.smoke, args.apk_sha, args.driver_sha)
    with args.publication_log.open(errors='replace') as stream:
        validate_publications(stream, args.scenario, args.iterations, args.smoke)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report(results, context, args.sha, args.scenario, args.smoke))
    if args.github_annotation:
        message = notices(results, args.sha, args.scenario, args.smoke)
        escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::notice title=Production viewport {args.scenario}::" + escaped)


if __name__ == "__main__":
    main()
