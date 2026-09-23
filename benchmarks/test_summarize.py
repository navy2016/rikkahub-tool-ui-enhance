import copy
import json
import tempfile
import unittest
from pathlib import Path

from summarize import (SCENARIOS, SIZES, load_results, overrun_percent, per_operation,
                       render_summary, validate_complete)


def measurement(size=1000, scenario="initialCompose"):
    count = 1 if scenario == "initialCompose" else 30
    return {
        "className": "me.rerere.rikkahub.benchmark.TerminalScrollbackBenchmark",
        "name": f"{scenario}[history={size}]", "repeatIterations": 2,
        "metrics": {
            "frameCount": {"runs": [2, 2]}, "mountToDrawFirstMs": {"runs": [100, 200]},
            "renderFrameSumMs": {"runs": [10, 20]}, "renderFrameCount": {"runs": [count, count]},
            "rowSyncSumMs": {"runs": [2, 4]}, "rowSyncCount": {"runs": [count, count]},
        },
        "sampledMetrics": {
            "frameDurationCpuMs": {"P50": 10, "P95": 20, "runs": [[5, 10], [10, 20]]},
            "frameOverrunMs": {"P95": 2, "runs": [[-3, 0], [1, 2]]},
        },
    }


class SummaryTest(unittest.TestCase):
    def test_per_operation_divides_paired_runs(self):
        result = measurement()
        result["metrics"]["renderFrameSumMs"]["runs"] = [10, 80]
        result["metrics"]["renderFrameCount"]["runs"] = [1, 4]
        self.assertEqual(15, per_operation(result, "renderFrame"))
        self.assertIsNone(per_operation({}, "renderFrame"))

    def test_missing_memory_is_not_reported_as_zero(self):
        output = render_summary({(1000, "initialCompose"): measurement()}, {}, "sha", "ci-emulator")
        self.assertIn("**ci-emulator**", output)
        self.assertIn("| 1000 | initialCompose | 2 | 150.00 |", output)
        self.assertIn("| 50.00 | — |", output)
        self.assertEqual(50, overrun_percent(measurement()))

    def test_requires_the_full_matrix_and_trace_counts(self):
        results = {(size, scenario): measurement(size, scenario) for size in SIZES for scenario in SCENARIOS}
        validate_complete(results)
        broken = copy.deepcopy(results)
        broken[(10000, "appendAndTrim")]["metrics"]["rowSyncCount"]["runs"] = [0, 30]
        with self.assertRaises(ValueError):
            validate_complete(broken)
        with self.assertRaises(ValueError):
            validate_complete({})

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


if __name__ == "__main__":
    unittest.main()
