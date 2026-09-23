#!/usr/bin/env python3
"""Summarize AndroidX Macrobenchmark 1.4 JSON without treating missing metrics as zero."""
import argparse
import json
import math
import re
import statistics
from pathlib import Path

SIZES = (1000, 5000, 10000)
SCENARIOS = ("initialCompose", "historyScroll", "activeRowUpdate", "appendAndTrim", "alternateScreenUpdate")


def single_runs(result, name):
    runs = result.get("metrics", {}).get(name, {}).get("runs", [])
    return [float(value) for value in runs if isinstance(value, (int, float)) and math.isfinite(value)]


def median(result, name):
    values = single_runs(result, name)
    return statistics.median(values) if values else None


def per_operation(result, prefix):
    sums = single_runs(result, prefix + "SumMs")
    counts = single_runs(result, prefix + "Count")
    if not sums or len(sums) != len(counts) or any(count <= 0 for count in counts):
        return None
    # Divide within each iteration before taking the median, not medians of unrelated arrays.
    return statistics.median(total / count for total, count in zip(sums, counts))


def percentile(result, name, key):
    value = result.get("sampledMetrics", {}).get(name, {}).get(key)
    return float(value) if isinstance(value, (int, float)) and math.isfinite(value) else None


def overrun_percent(result):
    runs = result.get("sampledMetrics", {}).get("frameOverrunMs", {}).get("runs", [])
    frames = [value for run in runs for value in run if isinstance(value, (int, float)) and math.isfinite(value)]
    return 100 * sum(value > 0 for value in frames) / len(frames) if frames else None


def load_results(root):
    results = {}
    contexts = []
    for path in sorted(root.rglob("*-benchmarkData.json")):
        document = json.loads(path.read_text())
        context = document.get("context", {})
        if context not in contexts:
            contexts.append(context)
        for result in document.get("benchmarks", []):
            if not result.get("className", "").endswith("TerminalScrollbackBenchmark"):
                continue
            name = result.get("name", "")
            match = re.search(r"(" + "|".join(SCENARIOS) + r")\[history=(\d+)\]$", name)
            if not match:
                raise ValueError(f"Unrecognized benchmark name: {name}")
            key = (int(match[2]), match[1])
            # AGP can copy a JSON into multiple output directories. Exact copies are fine, but
            # merging different runs or devices would make their percentiles meaningless.
            if key in results and results[key] != result:
                raise ValueError(f"Conflicting measurements for {key}; summarize one run/device at a time")
            results[key] = result
    if not results:
        raise ValueError(f"No terminal benchmark results found under {root}")
    if len(contexts) != 1:
        raise ValueError("Multiple device contexts; summarize each separately")
    return results, contexts[0]


def validate_complete(results):
    expected = {(size, scenario) for size in SIZES for scenario in SCENARIOS}
    if set(results) != expected:
        raise ValueError(f"Incomplete matrix: missing={expected - set(results)}, unexpected={set(results) - expected}")
    for key, result in results.items():
        iterations = result.get("repeatIterations")
        frame_counts = single_runs(result, "frameCount")
        if not frame_counts or len(frame_counts) != iterations or any(value <= 0 for value in frame_counts):
            raise ValueError(f"Missing frame samples for {key}")
        if percentile(result, "frameDurationCpuMs", "P95") is None:
            raise ValueError(f"Missing frame duration for {key}")
        if key[1] == "initialCompose" and median(result, "mountToDrawFirstMs") is None:
            raise ValueError(f"Missing mount-to-draw trace for {key}")
        if key[1] != "historyScroll":
            expected_count = 1 if key[1] == "initialCompose" else 30
            for prefix in ("renderFrame", "rowSync"):
                counts = single_runs(result, prefix + "Count")
                sums = single_runs(result, prefix + "SumMs")
                if (len(counts) != iterations or len(sums) != iterations
                        or any(count != expected_count for count in counts)
                        or any(total <= 0 for total in sums)):
                    raise ValueError(f"Missing/incomplete {prefix} traces for {key}")


def format_number(value):
    return "—" if value is None else f"{value:.2f}"


def render_summary(results, context, sha, environment):
    lines = [
        "# Terminal scrollback rendering baseline", "",
        f"- Commit: `{sha}`",
        f"- Environment: **{environment}**; compilation: `Full`; renderer: eager `Column`.",
        "- 80 columns × 24 active rows, JetBrains Mono 14sp, ANSI/ASCII/CJK; sizes below count history only.",
        "- Emulator numbers are diagnostic, **not physical-device FPS or a migration acceptance threshold**.",
        "- No shell/PTY startup, viewport controller, IME, text selection or app-wide startup in this harness.",
        "- Missing metrics are `—`, never zero. Times are ms; RSS anon is MiB (not total/PSS).", "",
        "| History | Scenario | Repeats | Mount→draw | renderFrame/op | row sync/op | CPU frame p50 | CPU frame p95 | Overrun p95 | Overrun frames % | RSS anon max¹ |",
        "| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for (size, scenario), result in sorted(results.items(), key=lambda item: (item[0][0], SCENARIOS.index(item[0][1]))):
        rss = median(result, "memoryRssAnonMaxKb")
        values = [
            str(size), scenario, str(result.get("repeatIterations", "?")),
            format_number(median(result, "mountToDrawFirstMs")),
            format_number(per_operation(result, "renderFrame")),
            format_number(per_operation(result, "rowSync")),
            format_number(percentile(result, "frameDurationCpuMs", "P50")),
            format_number(percentile(result, "frameDurationCpuMs", "P95")),
            format_number(percentile(result, "frameOverrunMs", "P95")),
            format_number(overrun_percent(result)),
            format_number(rss / 1024 if rss is not None else None),
        ]
        lines.append("| " + " | ".join(values) + " |")
    lines += [
        "", "¹ Median of per-iteration maxima. Mount and per-operation columns are medians across iterations;",
        "frame percentiles pool captured frames as AndroidX reports them, including the small native harness UI.",
        "Overrun % is the proportion of captured frames with positive `frameOverrunMs`, not inferred from a fixed 16ms threshold.",
        "Initial-compose p95 has few frames: use mount→draw and the raw traces, not p95 alone.",
        "`renderFrame/op` and `row sync/op` do **not** include asynchronous Compose recomposition/layout/draw.",
        "Updates: 30 separately drawn operations, nominally 33ms apart; slow frames extend the workload rather than drop updates.",
        "`alternateScreenUpdate` preloads the stated history but renders only the 24-row alternate screen.",
        "", "## Device context", "", "```json", json.dumps(context, ensure_ascii=False, indent=2), "```", "",
    ]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--environment", required=True, choices=("ci-emulator", "physical-device"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    results, context = load_results(args.root)
    if args.require_complete:
        validate_complete(results)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render_summary(results, context, args.sha, args.environment))


if __name__ == "__main__":
    main()
