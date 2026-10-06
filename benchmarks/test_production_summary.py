import copy
import json
import tempfile
import unittest
from pathlib import Path

from production_summary import CLASS, MODES, SCENARIOS, SIZES, VERSION, load, notices, report, result_key, validate


SHA = "a" * 40
APK_SHA = "b" * 64
RUN = "12345"
GROUP = "12345-1-initialCompose-full"


def context(scenario="initialCompose", smoke=False):
    return {"device": "same-emulator", "payload": {"sourceSha": SHA, "runId": RUN, "groupId": GROUP,
            "suite": VERSION, "scenario": scenario, "smoke": str(smoke).lower(), "targetApkSha256": APK_SHA}}


def measurement(size, scenario, mode):
    counts = {name: 0 for name in ("mount", "operation", "output", "feed", "renderFrame", "rowSync", "measure",
              "draw", "validation", "viewport", "scrollEffect", "width", "eagerGeometry", "jumpTop", "jumpTail",
              "imeShow", "imeHide", "explicitRetry", "detach", "restore")}
    counts.update(measure=2, draw=3, viewport=2)
    updates = {"activeRowUpdate": 30, "appendAndTrim": 30, "imeRoundTrip": 8}.get(scenario, 0)
    counts.update(renderFrame=1 if scenario in ("initialCompose", "detachRestore") else updates,
                  rowSync=1 if scenario in ("initialCompose", "detachRestore") else updates,
                  feed=1 if scenario == "detachRestore" else updates, output=updates,
                  mount=int(scenario == "initialCompose"), operation=int(scenario != "initialCompose"),
                  validation={"initialCompose": 1, "activeRowUpdate": 30, "appendAndTrim": 30,
                              "semanticJump": 2, "imeRoundTrip": 11, "detachRestore": 1}[scenario])
    for name, owner in (("jumpTop", "semanticJump"), ("jumpTail", "semanticJump"), ("imeShow", "imeRoundTrip"),
                        ("imeHide", "imeRoundTrip"), ("explicitRetry", "imeRoundTrip"), ("detach", "detachRestore"),
                        ("restore", "detachRestore")):
        counts[name] = int(scenario == owner)
    if scenario == "semanticJump":
        counts["scrollEffect"] = 2
    if mode in ("lazyHistory", "lazyHistoryIme") and scenario != "semanticJump":
        counts["width"] = (30 if scenario in ("activeRowUpdate", "appendAndTrim") else
                           8 if mode == "lazyHistoryIme" and scenario == "imeRoundTrip" else 1)
    if mode == "lazyHistory" and scenario == "imeRoundTrip":
        counts["eagerGeometry"] = 5
    metrics = {"frameCount": {"runs": [3, 3]}}
    for name, count in counts.items():
        metrics[name + "Count"] = {"runs": [count, count]}
        # No duration metric when there are no matching slices is valid.
        if count:
            metrics[name + "SumMs"] = {"runs": [count * 2, count * 4]}
            metrics[name + "MaxMs"] = {"runs": [2, 4]}
    return {"className": CLASS, "name": f"production[history={size},scenario={scenario},mode={mode}]",
            "repeatIterations": 2, "metrics": metrics,
            "sampledMetrics": {"frameDurationCpuMs": {"P50": 10, "P95": 20, "runs": [[5, 10, 20], [5, 10, 20]]},
                               "frameOverrunMs": {"P95": 2, "runs": [[-3, 0, 2], [-3, 0, 2]]}}}


def matrix(scenario="initialCompose", smoke=False):
    return {(size, scenario, mode): measurement(size, scenario, mode)
            for size in ((1000,) if smoke else SIZES) for mode in MODES}


class ProductionSummaryTest(unittest.TestCase):
    def valid(self, results, scenario="initialCompose", ctx=None, smoke=False):
        validate(results, ctx or context(scenario, smoke), SHA, RUN, GROUP, scenario, 2, smoke, APK_SHA)

    def test_complete_six_scenarios_have_distinct_contracts(self):
        for scenario in SCENARIOS:
            with self.subTest(scenario=scenario):
                self.valid(matrix(scenario), scenario)
                self.valid(matrix(scenario, True), scenario, smoke=True)

    def test_smoke_cannot_be_reported_as_full_or_hide_missing_modes(self):
        for results, smoke in ((matrix(smoke=True), False), (matrix(), True)):
            with self.assertRaisesRegex(ValueError, "matrix"):
                self.valid(results, smoke=smoke)
        results = matrix()
        del results[(10000, "initialCompose", "lazyHistory")]
        with self.assertRaisesRegex(ValueError, "matrix"):
            self.valid(results)

    def test_every_provenance_key_is_required_and_bound(self):
        for field in context()["payload"]:
            for wrong in (None, "another-run"):
                ctx = context()
                if wrong is None:
                    del ctx["payload"][field]
                else:
                    ctx["payload"][field] = wrong
                with self.subTest(field=field, wrong=wrong), self.assertRaisesRegex(ValueError, "provenance"):
                    self.valid(matrix(), ctx=ctx)

    def test_traces_must_have_exact_update_counts_and_all_positive_durations(self):
        for scenario, name, values in (("appendAndTrim", "outputCount", [29, 30]),
                ("activeRowUpdate", "rowSyncCount", [30, 31]), ("imeRoundTrip", "feedCount", [0, 8]),
                ("initialCompose", "validationCount", [0, 1]), ("detachRestore", "restoreSumMs", [1, 0]),
                ("semanticJump", "scrollEffectCount", [1, 2])):
            results = matrix(scenario)
            results[(10000, scenario, "lazyHistory")]["metrics"][name] = {"runs": values}
            with self.subTest(scenario=scenario, metric=name), self.assertRaises(ValueError):
                self.valid(results, scenario)

    def test_default_renderer_cannot_silently_use_virtual_measurements(self):
        for name in ("widthCount", "eagerGeometryCount"):
            results = matrix("imeRoundTrip")
            results[(1000, "imeRoundTrip", "chunkedLayers")]["metrics"][name] = {"runs": [1, 1]}
            with self.assertRaisesRegex(ValueError, "count"):
                self.valid(results, "imeRoundTrip")

    def test_keyboard_stable_mode_cannot_use_eager_fallback_or_skip_live_width_updates(self):
        for metric, value in (("eagerGeometryCount", 1), ("widthCount", 1)):
            results = matrix("imeRoundTrip")
            results[(10000, "imeRoundTrip", "lazyHistoryIme")]["metrics"][metric] = {"runs": [value, value]}
            with self.subTest(metric=metric), self.assertRaises(ValueError):
                self.valid(results, "imeRoundTrip")

    def test_old_two_mode_result_is_not_a_complete_v3_matrix(self):
        results = {key: value for key, value in matrix().items() if key[2] != "lazyHistoryIme"}
        with self.assertRaisesRegex(ValueError, "matrix"):
            self.valid(results)

    def test_required_production_width_and_viewport_work_cannot_be_omitted(self):
        for name in ("widthCount", "viewportSumMs", "widthMaxMs"):
            results = matrix("appendAndTrim")
            del results[(10000, "appendAndTrim", "lazyHistory")]["metrics"][name]
            with self.assertRaisesRegex(ValueError, "metric"):
                self.valid(results, "appendAndTrim")

    def test_no_root_remeasurement_is_valid_for_updates_and_jumps(self):
        for scenario in ("activeRowUpdate", "appendAndTrim", "semanticJump"):
            results = matrix(scenario)
            for case in results.values():
                case["metrics"]["measureCount"] = {"runs": [0, 0]}
                del case["metrics"]["measureSumMs"]
                del case["metrics"]["measureMaxMs"]
            self.valid(results, scenario)

    def test_nonfinite_wrong_repeat_and_missing_frame_samples_are_rejected(self):
        for value in (float("nan"), float("inf"), True, -1, 0, 1.5):
            results = matrix()
            results[(1000, "initialCompose", "chunkedLayers")]["metrics"]["frameCount"]["runs"] = [value, 3]
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.valid(results)
        results = matrix()
        results[(1000, "initialCompose", "chunkedLayers")]["repeatIterations"] = 1
        with self.assertRaisesRegex(ValueError, "Repetition"):
            self.valid(results)
        results = matrix()
        results[(1000, "initialCompose", "chunkedLayers")]["sampledMetrics"]["frameDurationCpuMs"]["runs"] = [[], [10]]
        with self.assertRaisesRegex(ValueError, "frame metric"):
            self.valid(results)

    def test_old_benchmark_names_and_unknown_modes_never_pass(self):
        for name in ("render[history=1000,scenario=initialCompose,renderer=lazyHistory]",
                     "production[history=1000,scenario=initialCompose,mode=lazyIntrinsic]",
                     "production[history=1000,scenario=bad,mode=lazyHistory]"):
            with self.assertRaises(ValueError):
                result_key(name)

    def test_duplicate_copies_are_allowed_but_partial_or_independent_runs_are_not(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            doc = {"context": context(), "benchmarks": list(matrix().values())}
            for name in ("a", "b"):
                (root / f"{name}-benchmarkData.json").write_text(json.dumps(doc))
            results, ctx = load(root)
            self.valid(results, ctx=ctx)
            for change in ("partial", "different-context", "different-values"):
                other = copy.deepcopy(doc)
                if change == "partial":
                    other["benchmarks"].pop()
                elif change == "different-context":
                    other["context"]["device"] = "another-runner"
                else:
                    other["benchmarks"][0]["metrics"]["frameCount"]["runs"] = [4, 4]
                (root / "b-benchmarkData.json").write_text(json.dumps(other))
                with self.subTest(change=change), self.assertRaisesRegex(ValueError, "Different result"):
                    load(root)

    def test_duplicate_inside_document_is_not_counted_twice(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case = measurement(1000, "initialCompose", "chunkedLayers")
            (root / "x-benchmarkData.json").write_text(json.dumps({"context": context(), "benchmarks": [case, case]}))
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                load(root)

    def test_reports_keep_missing_memory_and_all_sizes_without_cross_run_ratios(self):
        for scenario in SCENARIOS:
            results = matrix(scenario)
            text = report(results, context(scenario), SHA, scenario, False)
            self.assertIn("not ProcessSessionPage, PTY", text)
            self.assertNotIn("×", text)
            self.assertIn("| 10000 | lazyHistory | 2 |", text)
            self.assertIn("RSS anon", text)
            notice = notices(results, SHA, scenario, False)
            self.assertEqual(9, sum(line[:1].isdigit() for line in notice.splitlines()))
            self.assertTrue(all(line.endswith(" | —") for line in notice.splitlines() if line[:1].isdigit()))
            self.assertLess(len(notice.replace("\n", "%0A").encode()), 3000)


if __name__ == "__main__":
    unittest.main()
