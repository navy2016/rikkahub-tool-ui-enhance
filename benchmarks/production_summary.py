#!/usr/bin/env python3
"""Validate ONE production-viewport scenario/pair invocation; never merge independent runs."""
import argparse
import json
import math
import re
from pathlib import Path

from summarize import format_number, median, overrun_percent, per_operation, percentile

VERSION = "production-viewport-v2"
SIZES = (1000, 5000, 10000)
MODES = ("chunkedLayers", "lazyHistory")
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


def validate(results, context, sha, run_id, group_id, scenario, iterations, smoke=False, apk_sha=None):
    if scenario not in SCENARIOS or not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("Invalid scenario/SHA")
    payload = context.get("payload", {})
    if not isinstance(apk_sha, str) or not re.fullmatch(r"[a-f0-9]{64}", apk_sha):
        raise ValueError("Expected verified target APK hash")
    expected_payload = {"sourceSha": sha, "runId": str(run_id), "groupId": group_id,
                        "suite": VERSION, "scenario": scenario, "smoke": str(smoke).lower(), "targetApkSha256": apk_sha}
    if any(payload.get(key) != value for key, value in expected_payload.items()):
        raise ValueError(f"Missing/mismatched provenance payload; expected {expected_payload}")
    expected = {(size, scenario, mode) for size in ((1000,) if smoke else SIZES) for mode in MODES}
    if set(results) != expected:
        raise ValueError(f"Incomplete production pair matrix: missing={expected - set(results)}, extra={set(results) - expected}")
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
        if mode == "lazyHistory":
            if scenario in ("initialCompose", "activeRowUpdate", "appendAndTrim", "imeRoundTrip", "detachRestore"):
                minimum = 30 if scenario in ("activeRowUpdate", "appendAndTrim") else 1
                trace(case, "width", minimum=minimum)
            if scenario == "imeRoundTrip":
                trace(case, "eagerGeometry", minimum=1)
        else:
            trace(case, "width", count=0)
            trace(case, "eagerGeometry", count=0)


def ordered(results):
    return sorted(results.items(), key=lambda item: (item[0][0], MODES.index(item[0][2])))


def report(results, context, sha, scenario, smoke):
    lines = [f"# Production viewport: {scenario}", "",
             f"Commit: `{sha}`. Contract: `{VERSION}`. Smoke-only: `{str(smoke).lower()}`.", "",
             "Both actual production modes share one APK, device and instrumentation invocation in this scenario.",
             "Other scenarios may use different runners; do not aggregate their samples or calculate cross-group ratios.",
             "This is an unminified Release-derived component harness, not ProcessSessionPage, PTY/input echo or app startup.",
             "Updates include production reconciliation and two stable draw confirmations. Validation/wait overhead is included.",
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
    parser.add_argument("--scenario", required=True, choices=SCENARIOS)
    parser.add_argument("--iterations", required=True, type=int)
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--github-annotation", action="store_true")
    args = parser.parse_args()
    results, context = load(args.root)
    validate(results, context, args.sha, args.run_id, args.group_id, args.scenario, args.iterations, args.smoke, args.apk_sha)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report(results, context, args.sha, args.scenario, args.smoke))
    if args.github_annotation:
        message = notices(results, args.sha, args.scenario, args.smoke)
        escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::notice title=Production viewport {args.scenario}::" + escaped)


if __name__ == "__main__":
    main()
