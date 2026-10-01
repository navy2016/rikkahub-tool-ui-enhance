import json
import tempfile
import unittest
from pathlib import Path

from summarize import (RENDERERS, SCENARIOS, SIZES, SUITE_RENDERERS, comparison_lines, github_annotations, load_results, overrun_percent,
                       per_operation, render_hot_path_summary, render_summary, result_key, validate_complete)


def measurement(size=1000, scenario="initialCompose", renderer="eager", legacy=False):
    count = 1 if scenario == "initialCompose" else 30
    follow_count = 30 if renderer == "lazyHistory" and scenario in ("activeRowUpdate", "appendAndTrim") else 0
    result = {
        "className": "me.rerere.rikkahub.benchmark.TerminalScrollbackBenchmark",
        "name": f"{scenario}[history={size}]" if legacy else
                f"render[history={size},scenario={scenario},renderer={renderer}]",
        "repeatIterations": 2,
        "metrics": {
            "frameCount": {"runs": [2, 2]}, "mountToDrawFirstMs": {"runs": [100, 200]},
            "renderFrameSumMs": {"runs": [10, 20]}, "renderFrameCount": {"runs": [count, count]},
            "rowSyncSumMs": {"runs": [2, 4]}, "rowSyncCount": {"runs": [count, count]},
            "followTailCount": {"runs": [follow_count, follow_count]},
        },
        "sampledMetrics": {
            "frameDurationCpuMs": {"P50": 10, "P95": 20, "runs": [[5, 10], [10, 20]]},
            "frameOverrunMs": {"P95": 2, "runs": [[-3, 0], [1, 2]]},
        },
    }
    if scenario != "initialCompose":
        del result["metrics"]["mountToDrawFirstMs"]
    return result


def matrix(renderers=SUITE_RENDERERS["ab"]):
    return {(size, scenario, renderer): measurement(size, scenario, renderer)
            for size in SIZES for scenario in SCENARIOS for renderer in renderers}


class SummaryTest(unittest.TestCase):
    def test_per_operation_divides_paired_runs(self):
        result = measurement()
        result["metrics"]["renderFrameSumMs"]["runs"] = [10, 80]
        result["metrics"]["renderFrameCount"]["runs"] = [1, 4]
        self.assertEqual(15, per_operation(result, "renderFrame"))
        self.assertIsNone(per_operation({}, "renderFrame"))

    def test_missing_memory_is_not_reported_as_zero(self):
        output = render_summary({(1000, "initialCompose", "eager"): measurement()}, {}, "sha", "ci-emulator")
        self.assertIn("**ci-emulator**", output)
        self.assertIn("| 1000 | initialCompose | eager | 2 | 150.00 |", output)
        self.assertIn("| 50.00 | — |", output)
        self.assertEqual(50, overrun_percent(measurement()))

    def test_hot_path_annotation_keeps_all_sizes_and_both_arms_without_truncation(self):
        summary = render_hot_path_summary(matrix(), "a" * 40, "ci-emulator")
        lines = [line for line in summary.splitlines() if line[:1].isdigit()]
        self.assertEqual(12, len(lines))
        for size in SIZES:
            for scenario in ("activeRowUpdate", "appendAndTrim"):
                for renderer in SUITE_RENDERERS["ab"]:
                    self.assertIn(f"{size} | {scenario} | {renderer} | 2 | 0.10 | — | 20.00 | —", summary)
        self.assertNotIn("alternateScreenUpdate", summary)
        self.assertIn("NOT old/new", summary)
        escaped = summary.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
        self.assertLess(len(escaped.encode("utf-8")), 3_500)

    def test_chunked_eager_is_a_distinct_complete_pair_not_a_relabelled_lazy_arm(self):
        results = matrix(SUITE_RENDERERS["chunked-eager"])
        validate_complete(results, "chunked-eager")
        with self.assertRaises(ValueError):
            validate_complete(results, "ab")
        with self.assertRaises(ValueError):
            validate_complete(matrix(), "chunked-eager")
        with self.assertRaises(ValueError):
            validate_complete(matrix(), "typo")
        output = render_summary(results, {}, "sha", "ci-emulator")
        self.assertIn("stable-chunk eager A/B", output)
        self.assertIn("retains ALL history rows", output)
        self.assertIn("E/Ch", output)
        self.assertNotIn("ONE whole active-screen grid item", output)
        self.assertEqual((10000, "appendAndTrim", "chunkedEager"), result_key(
            "render[history=10000,scenario=appendAndTrim,renderer=chunkedEager]"))
        mixed = matrix(RENDERERS)
        with self.assertRaises(ValueError):
            render_summary(mixed, {}, "sha", "ci-emulator")
        results[(10000, "appendAndTrim", "chunkedEager")]["metrics"]["followTailCount"]["runs"] = [30, 30]
        with self.assertRaises(ValueError):
            validate_complete(results, "chunked-eager")

    def test_layer_experiment_requires_three_complete_arms(self):
        results = matrix(SUITE_RENDERERS["chunked-layers"])
        validate_complete(results, "chunked-layers")
        for other in ("baseline", "ab", "chunked-eager"):
            with self.assertRaises(ValueError):
                validate_complete(results, other)
        for missing in SUITE_RENDERERS["chunked-layers"]:
            partial = {key: value for key, value in results.items() if key[2] != missing}
            with self.assertRaises(ValueError):
                validate_complete(partial, "chunked-layers")
        self.assertEqual((10000, "appendAndTrim", "chunkedLayers"), result_key(
            "render[history=10000,scenario=appendAndTrim,renderer=chunkedLayers]"))
        results[(1000, "appendAndTrim", "eager")]["sampledMetrics"]["frameDurationCpuMs"]["P95"] = 200
        results[(1000, "appendAndTrim", "chunkedEager")]["sampledMetrics"]["frameDurationCpuMs"]["P95"] = 100
        text = render_summary(results, {}, "sha", "ci-emulator")
        self.assertIn("three-arm", text)
        self.assertIn("Ch/La: chunkedEager / chunkedLayers", text)
        self.assertIn("| 1000 | appendAndTrim | — | 10.00× | 1.00× |", text)
        self.assertIn("| 1000 | appendAndTrim | — | 5.00× | 1.00× |", text)
        results[(10000, "appendAndTrim", "chunkedLayers")]["metrics"]["followTailCount"]["runs"] = [30, 30]
        with self.assertRaises(ValueError):
            validate_complete(results, "chunked-layers")

    def test_scenario_annotations_cover_every_case_without_truncation_or_fake_zeroes(self):
        for suite in ("ab", "chunked-layers", "production-lazy"):
            results = matrix(SUITE_RENDERERS[suite])
            notices = github_annotations(results, "a" * 40, "ci-emulator")
            self.assertEqual(6, len(notices))
            for scenario, (title, text) in zip(SCENARIOS, notices[1:]):
                self.assertIn(scenario, title)
                data = [line for line in text.splitlines() if line[:1].isdigit()]
                self.assertEqual(3 * len(SUITE_RENDERERS[suite]), len(data))
                for size in SIZES:
                    for renderer in SUITE_RENDERERS[suite]:
                        self.assertIn(f"{size} | {renderer} | 2 |", text)
                self.assertIn(" | — | — | 2.00 | —", text)  # No measure/draw/RSS sample: NOT zero.
            for _, text in notices:
                escaped = text.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D")
                self.assertLess(len(escaped.encode("utf-8")), 3_500)
            self.assertIn("NOT a TUI speedup", notices[-1][1])

    def test_legacy_names_remain_readable(self):
        self.assertEqual((1000, "initialCompose", "eager"), result_key(measurement(legacy=True)["name"]))
        self.assertEqual((10000, "appendAndTrim", "lazyHistory"), result_key(
            measurement(10000, "appendAndTrim", "lazyHistory")["name"]))
        with self.assertRaises(ValueError):
            result_key("render[history=1000,scenario=initialCompose,renderer=typo]")

    def test_width_index_traces_cannot_be_omitted_or_counted_as_row_sync(self):
        results = matrix(SUITE_RENDERERS["production-lazy"])
        validate_complete(results, "production-lazy")  # Archived pre-width runs remain readable.
        with self.assertRaisesRegex(ValueError, "width-index"):
            validate_complete(results, "production-lazy", require_width_index=True)
        for (_, scenario, renderer), result in results.items():
            if renderer == "lazyHistory" and scenario in ("initialCompose", "activeRowUpdate", "appendAndTrim"):
                count = 1 if scenario == "initialCompose" else 30
                result["metrics"].update({
                    "widthIndexCount": {"runs": [count, count]},
                    "widthIndexSumMs": {"runs": [12, 24]},
                    "widthIndexMaxMs": {"runs": [3, 6]},
                })
        validate_complete(results, "production-lazy", require_width_index=True)
        notices = github_annotations(results, "a" * 40, "ci-emulator")
        self.assertEqual(7, len(notices))
        self.assertEqual("Terminal width index", notices[-1][0])
        width = notices[-1][1]
        self.assertIn("NOT rowSync", width)
        self.assertIn("10000 | initialCompose | lazyHistory | 2 | 18.00 | 4.50 | 1.00 | 18.00", width)
        self.assertIn("10000 | appendAndTrim | lazyHistory | 2 | 0.60 | 4.50 | 30.00 | 18.00", width)
        self.assertLess(len(width.replace("\n", "%0A").encode()), 3500)
        self.assertIn("Width index/call", render_summary(results, {}, "sha", "ci-emulator"))
        results[(10000, "appendAndTrim", "lazyHistory")]["metrics"]["widthIndexCount"]["runs"] = [1, 30]
        with self.assertRaisesRegex(ValueError, "width-index"):
            validate_complete(results, "production-lazy", require_width_index=True)

    def test_production_lazy_pair_cannot_substitute_the_old_flat_control(self):
        results = matrix(SUITE_RENDERERS["production-lazy"])
        validate_complete(results, "production-lazy")
        for other in ("ab", "chunked-eager", "chunked-layers"):
            with self.assertRaises(ValueError):
                validate_complete(matrix(SUITE_RENDERERS[other]), "production-lazy")
        for missing in SUITE_RENDERERS["production-lazy"]:
            with self.assertRaises(ValueError):
                validate_complete({key: value for key, value in results.items() if key[2] != missing}, "production-lazy")
        results[(1000, "appendAndTrim", "chunkedLayers")]["sampledMetrics"]["frameDurationCpuMs"]["P95"] = 100
        output = render_summary(results, {}, "sha", "ci-emulator")
        self.assertIn("production chunks/layers vs history-only LazyColumn A/B", output)
        self.assertIn("P/L: chunkedLayers / lazyHistory", output)
        self.assertIn("| 1000 | appendAndTrim | — | 5.00× | 1.00× |", output)
        self.assertNotIn("### E/L", output)
        results[(1000, "appendAndTrim", "lazyHistory")]["metrics"]["followTailCount"]["runs"] = [0, 30]
        with self.assertRaises(ValueError):
            validate_complete(results, "production-lazy")

    def test_requires_both_complete_arms_and_the_same_repetition_count(self):
        results = matrix()
        validate_complete(results, "ab")
        validate_complete(matrix(("eager",)), "baseline")
        with self.assertRaises(ValueError):
            validate_complete(matrix(("eager",)), "ab")
        results[(10000, "appendAndTrim", "lazyHistory")]["repeatIterations"] = 1
        with self.assertRaises(ValueError):
            validate_complete(results, "ab")

    def test_requires_all_update_and_follow_tail_traces(self):
        for key, metric, runs in [
            ((10000, "appendAndTrim", "lazyHistory"), "followTailCount", [0, 30]),
            ((10000, "activeRowUpdate", "lazyHistory"), "followTailCount", [0, 30]),
            ((10000, "appendAndTrim", "eager"), "rowSyncCount", [0, 30]),
            ((10000, "alternateScreenUpdate", "lazyHistory"), "followTailCount", [30, 30]),
        ]:
            results = matrix()
            results[key]["metrics"][metric]["runs"] = runs
            with self.assertRaises(ValueError):
                validate_complete(results, "ab")
        with self.assertRaises(ValueError):
            validate_complete({}, "ab")

    def test_comparison_only_uses_pairs_and_never_calls_tui_noise_a_speedup(self):
        results = matrix()
        results[(1000, "appendAndTrim", "eager")]["sampledMetrics"]["frameDurationCpuMs"]["P95"] = 100
        output = "\n".join(comparison_lines(results))
        self.assertIn("| 1000 | appendAndTrim | — | 5.00× | 1.00× |", output)
        self.assertNotIn("alternateScreenUpdate", output)
        self.assertNotIn("| 1000 |", "\n".join(comparison_lines(matrix(("eager",)))))

    def test_deduplicates_exact_copies_but_rejects_different_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            document = {"context": {"device": "emulator"}, "benchmarks": [measurement()]}
            for name in ("one", "two"):
                (root / f"{name}-benchmarkData.json").write_text(json.dumps(document))
            results, _ = load_results(root)
            self.assertEqual(1, len(results))
            document["benchmarks"][0]["metrics"]["frameCount"]["runs"] = [5, 5]
            (root / "two-benchmarkData.json").write_text(json.dumps(document))
            with self.assertRaises(ValueError):
                load_results(root)

    def test_independent_half_runs_cannot_be_assembled_even_with_identical_devices(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for renderer in RENDERERS:
                document = {"context": {"device": "same-model"}, "benchmarks": [measurement(renderer=renderer)]}
                (root / f"{renderer}-benchmarkData.json").write_text(json.dumps(document))
            with self.assertRaises(ValueError):
                load_results(root)

    def test_different_device_contexts_cannot_be_combined_for_ab(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for renderer in RENDERERS:
                document = {"context": {"device": renderer}, "benchmarks": [measurement(renderer=renderer)]}
                (root / f"{renderer}-benchmarkData.json").write_text(json.dumps(document))
            with self.assertRaises(ValueError):
                load_results(root)


if __name__ == "__main__":
    unittest.main()
