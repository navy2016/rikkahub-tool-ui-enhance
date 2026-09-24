import json
import tempfile
import unittest
from pathlib import Path

from summarize import (RENDERERS, SCENARIOS, SIZES, comparison_lines, load_results, overrun_percent,
                       per_operation, render_summary, result_key, validate_complete)


def measurement(size=1000, scenario="initialCompose", renderer="eager", legacy=False):
    count = 1 if scenario == "initialCompose" else 30
    follow_count = 30 if (scenario, renderer) == ("appendAndTrim", "lazyHistory") else 0
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


def matrix(renderers=RENDERERS):
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

    def test_legacy_names_remain_readable(self):
        self.assertEqual((1000, "initialCompose", "eager"), result_key(measurement(legacy=True)["name"]))
        self.assertEqual((10000, "appendAndTrim", "lazyHistory"), result_key(
            measurement(10000, "appendAndTrim", "lazyHistory")["name"]))
        with self.assertRaises(ValueError):
            result_key("render[history=1000,scenario=initialCompose,renderer=typo]")

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
