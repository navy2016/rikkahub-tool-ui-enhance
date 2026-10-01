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
RENDERERS = ("eager", "lazyHistory", "chunkedEager", "chunkedLayers", "lazyIntrinsic")
LAZY_RENDERERS = ("lazyHistory", "lazyIntrinsic")
SUITE_RENDERERS = {
    "baseline": ("eager",),
    "ab": ("eager", "lazyHistory"),
    "chunked-eager": ("eager", "chunkedEager"),
    "chunked-layers": ("eager", "chunkedEager", "chunkedLayers"),
    "production-lazy": ("chunkedLayers", "lazyHistory"),
    "width-intrinsics": ("chunkedLayers", "lazyHistory", "lazyIntrinsic"),
}


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


def result_key(name):
    scenarios = "|".join(SCENARIOS)
    renderers = "|".join(RENDERERS)
    match = re.search(r"render\[history=(\d+),scenario=(" + scenarios + r"),renderer=(" + renderers + r")\]$", name)
    if match:
        return int(match[1]), match[2], match[3]
    # The archived pre-A/B baseline must remain readable; do not infer an absent B arm from it.
    match = re.search(r"(" + scenarios + r")\[history=(\d+)\]$", name)
    if match:
        return int(match[2]), match[1], "eager"
    raise ValueError(f"Unrecognized benchmark name: {name}")


def load_results(root):
    results = {}
    contexts = []
    source_document = None
    for path in sorted(root.rglob("*-benchmarkData.json")):
        document = json.loads(path.read_text())
        relevant = [result for result in document.get("benchmarks", [])
                    if result.get("className", "").endswith("TerminalScrollbackBenchmark")]
        if not relevant:
            continue
        if source_document is not None and relevant != source_document:
            raise ValueError("Different result documents; do not assemble A/B from independent runs")
        source_document = relevant
        for result in relevant:
            context = document.get("context", {})
            if context not in contexts:
                contexts.append(context)
            key = result_key(result.get("name", ""))
            # AGP can copy a JSON into multiple output directories. Exact copies are fine, but
            # merging different runs/devices would make their percentiles meaningless.
            if key in results and results[key] != result:
                raise ValueError(f"Conflicting measurements for {key}; summarize one run/device at a time")
            results[key] = result
    if not results:
        raise ValueError(f"No terminal benchmark results found under {root}")
    if len(contexts) != 1:
        raise ValueError("Multiple device contexts; summarize each separately")
    return results, contexts[0]


def validate_complete(results, suite="baseline", require_width_index=False):
    if suite not in SUITE_RENDERERS:
        raise ValueError(f"Unknown benchmark suite: {suite}")
    renderers = SUITE_RENDERERS[suite]
    expected = {(size, scenario, renderer) for size in SIZES for scenario in SCENARIOS for renderer in renderers}
    if set(results) != expected:
        raise ValueError(f"Incomplete matrix: missing={expected - set(results)}, unexpected={set(results) - expected}")
    repeats = {result.get("repeatIterations") for result in results.values()}
    if len(repeats) != 1 or not isinstance(next(iter(repeats)), int) or next(iter(repeats)) <= 0:
        raise ValueError("All cases must use the same positive repetition count")
    for key, result in results.items():
        iterations = result["repeatIterations"]
        frame_counts = single_runs(result, "frameCount")
        if len(frame_counts) != iterations or any(value <= 0 for value in frame_counts):
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
        if suite != "baseline":
            # Pin every ordinary lazy update: both key movement and styled-line height changes
            # can leave the changing screen offscreen. Alternate/full-grid controls stay eager.
            expected_count = 30 if key[2] in LAZY_RENDERERS and key[1] in ("activeRowUpdate", "appendAndTrim") else 0
            counts = single_runs(result, "followTailCount")
            if len(counts) != iterations or any(count != expected_count for count in counts):
                raise ValueError(f"Incorrect follow-tail trace count for {key}")
        if require_width_index and key[2] in LAZY_RENDERERS and key[1] in (
                "initialCompose", "activeRowUpdate", "appendAndTrim"):
            # Width discovery lives in composition, not rowSync. Require the cost rather than
            # reporting the old, horizontally incomplete candidate under a new compatibility label.
            minimum_count = 1 if key[1] == "initialCompose" else 30
            counts = single_runs(result, "widthIndexCount")
            sums = single_runs(result, "widthIndexSumMs")
            maxima = single_runs(result, "widthIndexMaxMs")
            if (len(counts) != iterations or len(sums) != iterations or len(maxima) != iterations
                    or any(count < minimum_count for count in counts)
                    or any(value <= 0 for value in sums + maxima)):
                raise ValueError(f"Missing/incomplete width-index traces for {key}")


def format_number(value):
    return "—" if value is None else f"{value:.2f}"


def ordered_results(results):
    return sorted(results.items(), key=lambda item: (
        item[0][0], SCENARIOS.index(item[0][1]), RENDERERS.index(item[0][2]),
    ))


def candidate_renderers(results):
    if {key[2] for key in results} == set(SUITE_RENDERERS["width-intrinsics"]):
        return LAZY_RENDERERS
    if {key[2] for key in results} == set(SUITE_RENDERERS["production-lazy"]):
        return ("lazyHistory",)
    candidates = tuple(renderer for renderer in RENDERERS[1:] if any(key[2] == renderer for key in results))
    if len(candidates) > 1 and candidates != ("chunkedEager", "chunkedLayers"):
        raise ValueError("Do not mix lazy and chunk experiments or assemble independent runs")
    return candidates


def comparison_lines(results):
    candidates = candidate_renderers(results)
    production_lazy = {key[2] for key in results} == set(SUITE_RENDERERS["production-lazy"])
    pairs = [("chunkedLayers", "lazyHistory", "P/L")] if production_lazy else [
        ("eager", candidate, {"lazyHistory": "E/L", "chunkedEager": "E/Ch", "chunkedLayers": "E/La", "lazyIntrinsic": "E/I"}[candidate])
        for candidate in candidates]
    if candidates == LAZY_RENDERERS:
        pairs = [("chunkedLayers", "lazyHistory", "P/L"), ("chunkedLayers", "lazyIntrinsic", "P/I"),
                 ("lazyHistory", "lazyIntrinsic", "L/I")]
    if candidates == ("chunkedEager", "chunkedLayers"):
        pairs.append(("chunkedEager", "chunkedLayers", "Ch/La"))
    lines = [
        "", "## Same-run renderer comparison", "",
        "Only adjacent ordinary-history arms from ONE run are compared; ratios are **not FPS**.",
        "Do not compare ratios across different hosts/runs or subtract differently aggregated statistics.",
    ]

    def ratio(a, b):
        return format_number(a / b) + "×" if a is not None and b is not None and b > 0 else "—"

    for numerator, denominator, label in pairs:
        lines += ["", f"### {label}: {numerator} / {denominator}", "",
                  f"| History | Scenario | Mount {label} | CPU frame p95 {label} | row sync {label} |",
                  "| ---: | --- | ---: | ---: | ---: |"]
        for size in SIZES:
            for scenario in SCENARIOS:
                if scenario == "alternateScreenUpdate":
                    continue  # Identical flat backend: noise is not a TUI speedup.
                a = results.get((size, scenario, numerator))
                b = results.get((size, scenario, denominator))
                if a is None or b is None:
                    continue
                values = [str(size), scenario,
                          ratio(median(a, "mountToDrawFirstMs"), median(b, "mountToDrawFirstMs")),
                          ratio(percentile(a, "frameDurationCpuMs", "P95"), percentile(b, "frameDurationCpuMs", "P95")),
                          ratio(per_operation(a, "rowSync"), per_operation(b, "rowSync"))]
                lines.append("| " + " | ".join(values) + " |")
    return lines


def render_summary(results, context, sha, environment):
    candidates = candidate_renderers(results)
    candidate = candidates[-1] if candidates else None
    title = {
        "lazyIntrinsic": "production / full-layout width / intrinsic width (three-arm)",
        "lazyHistory": "history-only LazyColumn A/B",
        "chunkedEager": "stable-chunk eager A/B",
        "chunkedLayers": "archival chunks / isolated layers (three-arm)",
        None: "rendering baseline",
    }[candidate]
    if {key[2] for key in results} == set(SUITE_RENDERERS["production-lazy"]):
        title = "production chunks/layers vs history-only LazyColumn A/B"
    lines = [
        f"# Terminal scrollback {title}", "",
        f"- Commit: `{sha}`",
        f"- Environment: **{environment}**; target compilation configured as `Full`.",
        "- 80 columns × 24 active rows, JetBrains Mono 14sp, ANSI/ASCII/CJK; sizes below count history only.",
        "- Emulator numbers are diagnostic, **not physical-device FPS or a migration acceptance threshold**.",
        "- No shell/PTY startup, viewport controller, IME, text selection or app-wide startup in this harness.",
        "- Missing metrics are `—`, never zero. Times are ms; RSS anon is MiB (not total/PSS).", "",
        "| History | Scenario | Renderer | Repeats | Mount→draw | renderFrame/op | row sync/op | CPU frame p50 | CPU frame p95 | Overrun p95 | Overrun frames % | RSS anon max¹ |",
        "| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for (size, scenario, renderer), result in ordered_results(results):
        rss = median(result, "memoryRssAnonMaxKb")
        values = [
            str(size), scenario, renderer, str(result.get("repeatIterations", "?")),
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
        "`alternateScreenUpdate` preloads the stated history but renders only 24 physical rows; BOTH renderer arms use eager here.",
    ]
    if candidate in LAZY_RENDERERS:
        lines += [
            "Inspect the renderer column: production-lazy uses chunkedLayers as control, archived ab uses flat eager.",
            "The lazy candidate has one item per history line, ONE whole active-screen grid item, and an 8dp tail item.",
            "The lazy arm requests the tail before each ordinary update draw; eager corrects a changed range after layout.",
            "Any eager correction is followed by another draw and included in the measured window, not hidden in setup.",
            "Draw-time checks reject drift/offscreen updates or a fragmented screen. Mixed-style row heights are not assumed constant.",
            "Both arms pan six viewport heights using identical 1-second `animateScrollBy` animations, then return to the tail.",
            "Pairs use the same APK/device, with A/B order alternating per case; this is not a statistical confidence interval.",
        ]
        if candidate == "lazyIntrinsic":
            lines += [
                "The two lazy arms have identical row trees and FIFO width indices; only the width measurer differs.",
                "lazyHistory constructs a full TextLayoutResult; lazyIntrinsic uses ceil(maxIntrinsicWidth) with the same resolved style.",
                "Font shaping/spans/fallback/bidi still use Compose. No character-count estimate or history-sized paragraph cache.",
                "All THREE arms run adjacent in one invocation with rotating order. L/I isolates width discovery within this run.",
                "Width work stays in the measured mount/update window. Cold history is still scanned; no zero-cost claim.",
            ]
    elif candidate in ("chunkedEager", "chunkedLayers"):
        lines += [
            "Chunked eager retains ALL history rows, using stable composition buckets, not pixel estimates.",
            "Current sources group 128 FIFO archival ordinals (legacy 7d95aa7 used ID buckets); inspect the measured SHA.",
            "Only the composition/layout tree changes; both arms use ScrollState, shared row sync and production Text.",
            "The physical screen remains whole. TUI/alternate/full-grid cases use the unchanged flat eager backend.",
            "Both arms correct the actual measured tail after output and include any correction draw in timing.",
            "Both arms pan six viewport heights using identical 1-second animations, then return to the tail.",
            "Runtime probes reject missing history chunks/rows, split screens or unexpected candidate fallback.",
            "The current chunk arms call the shared production transcript; eager is the legacy flat control.",
            "This harness alone is not a production selection/viewport acceptance test.",
        ]
    if candidate == "chunkedLayers":
        lines += [
            "All THREE arms run in one invocation; their adjacent order rotates between size/scenario groups.",
            "chunkedEager and chunkedLayers use identical archival grouping; ONLY graphicsLayer boundaries differ.",
            "Ch/La isolates that drawing choice in this run. Independent CI runs are not a controlled comparison.",
            "The measured commit determines production defaults; this report does not automatically switch renderers.",
        ]
    if candidate:
        lines += comparison_lines(results)
    lines += [
        "", "## Trace phase details", "",
        "Per-call values below are medians of iteration averages. These traces do not measure all recomposition/placement work.",
        "Native frame scheduling, untraced work and GC also prevent subtracting these values from frame p95.", "",
        "| History | Scenario | Renderer | Measure/call | Draw/call | row sync max¹ | lazy tail request/call | eager correction/call | Frames¹ | Width index/call | Width index max¹ |",
        "| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for (size, scenario, renderer), result in ordered_results(results):
        values = [str(size), scenario, renderer,
                  format_number(per_operation(result, "measure")), format_number(per_operation(result, "draw")),
                  format_number(median(result, "rowSyncMaxMs")), format_number(per_operation(result, "followTail")),
                  format_number(per_operation(result, "eagerTailCorrection")), format_number(median(result, "frameCount")),
                  format_number(per_operation(result, "widthIndex")), format_number(median(result, "widthIndexMaxMs"))]
        lines.append("| " + " | ".join(values) + " |")
    lines += [
        "", "Width-index work, when present, runs inside composition and is included in mount-to-draw/CPU frames.",
        "It is NOT part of rowSync. Width/call is not width/output: recomposition can cause multiple calls.",
        "Cold/font/style/column invalidation scans history; owned warm updates only measure the new FIFO suffix and screen.",
    ]
    lines += [
        "", "## Device context", "",
        "`context.compilationMode` describes the self-instrumenting test driver, not the separately compiled renderer target.",
        "", "```json", json.dumps(context, ensure_ascii=False, indent=2), "```", "",
    ]
    return "\n".join(lines)


def render_hot_path_summary(results, sha, environment):
    """Keep all sizes' update metrics visible within one check-run annotation."""
    lines = [
        f"Terminal hot paths — commit {sha}; environment={environment}",
        "Full matrix, traces and device context: job summary/artifact. CPU timings are not FPS.",
        "Both arms share row sync; these are NOT old/new synchronizer comparisons.",
        "History | Scenario | Renderer | n | rowSync/op ms | rowSync max ms | CPU p95 ms | RSS anon MiB",
    ]
    for (size, scenario, renderer), result in ordered_results(results):
        if scenario not in ("activeRowUpdate", "appendAndTrim"):
            continue
        rss = median(result, "memoryRssAnonMaxKb")
        lines.append(" | ".join([
            str(size), scenario, renderer, str(result.get("repeatIterations", "?")),
            format_number(per_operation(result, "rowSync")), format_number(median(result, "rowSyncMaxMs")),
            format_number(percentile(result, "frameDurationCpuMs", "P95")),
            format_number(rss / 1024 if rss is not None else None),
        ]))
    return "\n".join(lines)


def render_scenario_summary(results, scenario, sha, environment):
    """One bounded annotation per scenario: complete matrix access even if blobs are unreachable."""
    if scenario not in SCENARIOS:
        raise ValueError(f"Unknown scenario: {scenario}")
    lines = [
        f"Terminal {scenario} — commit {sha}; environment={environment}",
        "ms; CPU percentiles pool frames; other values are medians of iteration values/averages. Not FPS.",
        "History | Renderer | n | Mount | render/op | sync/op | CPU p50 | CPU p95 | Measure/call | Draw/call | Frames | RSS anon MiB",
    ]
    for (size, current, renderer), result in ordered_results(results):
        if current != scenario:
            continue
        rss = median(result, "memoryRssAnonMaxKb")
        lines.append(" | ".join([
            str(size), renderer, str(result.get("repeatIterations", "?")),
            format_number(median(result, "mountToDrawFirstMs")),
            format_number(per_operation(result, "renderFrame")), format_number(per_operation(result, "rowSync")),
            format_number(percentile(result, "frameDurationCpuMs", "P50")),
            format_number(percentile(result, "frameDurationCpuMs", "P95")),
            format_number(per_operation(result, "measure")), format_number(per_operation(result, "draw")),
            format_number(median(result, "frameCount")), format_number(rss / 1024 if rss is not None else None),
        ]))
    if scenario == "initialCompose":
        lines.append("Few initial frames: inspect mount latency, not p95 alone.")
    if scenario == "alternateScreenUpdate":
        lines.append("All arms use the same flat 24-row grid here. Differences are NOT a TUI speedup.")
    return "\n".join(lines)


def render_width_summary(results, sha, environment):
    lines = [
        f"Terminal width index — commit {sha}; environment={environment}",
        "ms; medians of iteration values. Included in mount/CPU frames, NOT rowSync. Missing is not zero.",
        "History | Scenario | Renderer | n | Width/call | Width max | Width calls | Width total",
    ]
    for (size, scenario, renderer), result in ordered_results(results):
        if renderer not in LAZY_RENDERERS or scenario not in ("initialCompose", "activeRowUpdate", "appendAndTrim"):
            continue
        lines.append(" | ".join([
            str(size), scenario, renderer, str(result.get("repeatIterations", "?")),
            format_number(per_operation(result, "widthIndex")), format_number(median(result, "widthIndexMaxMs")),
            format_number(median(result, "widthIndexCount")), format_number(median(result, "widthIndexSumMs")),
        ]))
    return "\n".join(lines)


def github_annotations(results, sha, environment):
    # At most seven notices, below GitHub's ten-notices-per-step limit. Never truncate 10k cases.
    notices = [("Terminal rendering hot paths", render_hot_path_summary(results, sha, environment))] + [
        (f"Terminal benchmark {scenario}", render_scenario_summary(results, scenario, sha, environment))
        for scenario in SCENARIOS
    ]
    if any("widthIndexCount" in result.get("metrics", {}) for result in results.values()):
        notices.append(("Terminal width index", render_width_summary(results, sha, environment)))
    return notices


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--environment", required=True, choices=("ci-emulator", "physical-device"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--suite", choices=tuple(SUITE_RENDERERS), default="baseline")
    parser.add_argument("--require-complete", action="store_true")
    parser.add_argument("--require-width-index", action="store_true")
    parser.add_argument("--github-annotation", action="store_true")
    args = parser.parse_args()
    results, context = load_results(args.root)
    payload = context.get("payload", {})
    if payload.get("sourceSha", args.sha) != args.sha or payload.get("suite", args.suite) != args.suite:
        raise ValueError("Source SHA/suite does not match the measurement payload")
    if args.require_complete:
        validate_complete(results, args.suite, require_width_index=args.require_width_index)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render_summary(results, context, args.sha, args.environment))
    if args.github_annotation:
        for title, message in github_annotations(results, args.sha, args.environment):
            escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print(f"::notice title={title}::" + escaped)


if __name__ == "__main__":
    main()
