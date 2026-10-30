"""Synthetic archive/statistics checks; none of these fixtures are device evidence."""

import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import collect_busy_stop_evidence as evidence
import publish_test_evidence as publisher
import summarize_busy_stop as summary
import test_busy_stop_archive as archive_tests
import test_busy_stop_evidence as fixtures
import test_performance_evidence as legacy

SHA = fixtures.SOURCE["commit"]


def save_manifest(root, manifest):
    (root / "manifest.json").write_bytes(fixtures.encoded(manifest))


def make_archive(root, profile="pilot", present=None, status="passed", trials=None, vary_input=False):
    """Use the real publisher and validator for independently synthetic lane bytes."""
    present = list(evidence.LABELS) if present is None else present
    incoming_root = root.parent / (root.name + "-downloads")
    incoming_root.mkdir(parents=True, exist_ok=True)
    args = archive_tests.args_for(incoming_root)
    artifacts, jobs = [], []
    for index, label in enumerate(evidence.LABELS):
        jobs.append(dict(legacy.job(), name=f"emulator-tests ({label})"))
        if label not in present:
            continue
        api = int(label.split(".")[0])
        item = dict(archive_tests.artifact(label), id=456 + index)
        artifacts.append(item)
        current = copy.deepcopy(trials)
        if vary_input:
            current = [fixtures.valid_trial(i, api, profile) for i in range(evidence.trial_count(profile))]
            for trial in current:
                offset = (api - 30) * 1_000_000 + trial["round"] * 1000
                trial["input"]["upIssuedNanoTime"] -= offset
                trial["input"]["upIssuedElapsedNanos"] -= offset
        fixtures.write_bundle(args.incoming / item["name"], api, profile, current, status)
    args.artifacts.write_bytes(fixtures.encoded({"artifacts": artifacts}))
    run = dict(archive_tests.source_run(profile), conclusion="success")
    budget = {"bytes": 0, "files": 0}
    section = publisher.archive_busy_stop(args, run, jobs, root, budget)
    root.mkdir(parents=True, exist_ok=True)
    manifest = dict(schemaVersion=1, kind="android-emulator-test-evidence", fullSuiteAcceptance=False,
        executionScope=evidence.scope_for_profile(profile), expectedMatrixLabels=list(evidence.LABELS),
        expectedTestsPerApi=1, expectedTests=8, testSelection=summary.TEST_SELECTION,
        totalBytes=budget["bytes"], fileCount=budget["files"], busyStop=section,
        sourceRun=dict(fixtures.SOURCE, branch="master", event="workflow_dispatch", conclusion="success",
            url=f"https://github.com/{summary.REPOSITORY}/actions/runs/{fixtures.SOURCE['runId']}"),
        apis=[dict(matrixLabel=label, jobConclusion="success", diagnosticResult="passed") for label in evidence.LABELS])
    save_manifest(root, manifest)
    return manifest


def refreshed_refs(root, manifest, label="37.0"):
    lane = next(row for row in manifest["busyStop"]["apis"] if row["matrixLabel"] == label)
    folder = root / "busy-stop" / ("api-" + label)
    lane["files"] = [dict(path=path.name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                     for path in sorted(folder.iterdir()) for data in [path.read_bytes()]]
    manifest["totalBytes"] = sum(ref["bytes"] for row in manifest["busyStop"]["apis"] for ref in row["files"])
    manifest["fileCount"] = sum(len(row["files"]) for row in manifest["busyStop"]["apis"])
    save_manifest(root, manifest)


class BusyStopSummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "archive"

    def test_primary_received_cancel_bound_and_distinct_completion_metrics(self):
        device = summary.observed_metrics(fixtures.valid_trial(0))
        self.assertEqual(1, device["inputDeliveryMillis"])
        self.assertEqual(3, device["upToCancelReturnUpperBoundMillis"])
        self.assertEqual(2, device["touchUpToCancelReturnUpperBoundMillis"])
        self.assertEqual(4, device["upToStopReturnMillis"])
        self.assertEqual(6, device["upToControlGoneObservedUpperBoundMillis"])
        self.assertEqual(160, device["upToFixtureBarrierExitMillis"])
        self.assertEqual(170, device["upToTargetEffectMillis"])
        self.assertEqual(180, device["upToPlatformCallReturnMillis"])
        self.assertEqual(190, device["upToTicketSettledMillis"])
        self.assertEqual(430, device["platformCallDurationMillis"])
        self.assertEqual(177, device["cancelReturnToPlatformCallReturnMillis"])
        self.assertIsNone(device["upToStoppedFrameCompletionMillis"])
        model_trial = fixtures.valid_trial(1)
        model = summary.observed_metrics(model_trial)
        self.assertEqual(10, model["upToStoppedFrameCompletionMillis"])
        self.assertEqual(9, model["touchUpToStoppedFrameCompletionMillis"])
        self.assertEqual(170, model["upToReplyWriteEndMillis"])
        self.assertIsNone(model["upToPlatformCallReturnMillis"])
        # Neither the later callback nor a copied completion label defines the metric.
        model_trial["frames"]["completedNanoTime"] += 7_000_000
        model_trial["frames"]["targetFrame"]["callbackNanoTime"] += 9_000_000
        model_trial["input"]["upIssuedElapsedNanos"] += 999_000_000_000
        model_trial["trace"]["events"] = []
        self.assertEqual(model, summary.observed_metrics(model_trial))

    def test_nearest_rank_p95_requires_one_hundred_formal_originals(self):
        complete = summary.distribution(list(range(100)), "formal")
        self.assertEqual((0, 49.5, 94, 99), (complete["min"], complete["median"], complete["p95"], complete["max"]))
        for profile, values, reason in (("formal", [*range(99), None], "fewer_than_100_valid_original_samples"),
                                        ("pilot", list(range(100)), "pilot_profile_not_formal")):
            result = summary.distribution(values, profile)
            self.assertIsNone(result["p95"])
            self.assertEqual(reason, result["p95UnavailableReason"])
        for value in (float("nan"), float("inf"), True, -1):
            with self.assertRaises(ValueError): summary.distribution([value], "formal")
        with self.assertRaises(ValueError): summary.distribution([1], "formal", applicable=False)

    def test_complete_pilot_keeps_thirty_two_trials_and_two_scenarios_per_api(self):
        make_archive(self.root)
        result = summary.summarize(self.root, SHA)
        self.assertEqual(("complete", 32, 32, 32), (result["status"], result["plannedTrialCount"],
                                                result["recordedTrialCount"], result["validTrialCount"]))
        self.assertFalse(result["formalDiagnosticComplete"])
        self.assertFalse(result["budgetAcceptance"])
        self.assertFalse(result["fullSuiteAcceptance"])
        for lane in result["lanes"]:
            self.assertEqual(["DEVICE", "MODEL", "MODEL", "DEVICE"], [trial["scenario"] for trial in lane["trials"]])
            for scenario, group in lane["scenarios"].items():
                self.assertEqual((2, 2, 2), (group["plannedCount"], group["recordedCount"], group["validCount"]))
                self.assertTrue(all(metric["p95"] is None for metric in group["metrics"].values()))
            frame = lane["scenarios"]["DEVICE"]["metrics"]["upToStoppedFrameCompletionMillis"]
            self.assertFalse(frame["applicable"])
            self.assertEqual(0, frame["validCount"])
            self.assertEqual("scenario_not_applicable", frame["p95UnavailableReason"])

    def test_formal_sixteen_hundred_do_not_pool_api_or_scenario_p95(self):
        make_archive(self.root, profile="formal", vary_input=True)
        result = summary.summarize(self.root, SHA)
        self.assertEqual((1600, 1600, True), (result["plannedTrialCount"], result["validTrialCount"], result["formalDiagnosticComplete"]))
        self.assertNotIn("metrics", result)
        for index, lane in enumerate(result["lanes"]):
            for scenario in evidence.SCENARIOS:
                group = lane["scenarios"][scenario]
                self.assertEqual(100, group["validCount"])
                cancel = group["metrics"]["upToCancelReturnUpperBoundMillis"]
                self.assertAlmostEqual(3 + index + .0495, cancel["median"])
                self.assertAlmostEqual(3 + index + .094, cancel["p95"])
                self.assertEqual(2, group["metrics"]["touchUpToCancelReturnUpperBoundMillis"]["p95"])
            self.assertIsNone(lane["scenarios"]["DEVICE"]["metrics"]["upToStoppedFrameCompletionMillis"]["p95"])
            self.assertAlmostEqual(10 + index + .094, lane["scenarios"]["MODEL"]["metrics"]["upToStoppedFrameCompletionMillis"]["p95"])

    def test_failed_prefix_excludes_observed_failed_trial_and_keeps_unexecuted_slots(self):
        trials = [fixtures.valid_trial(0), fixtures.valid_trial(1)]
        trials[-1].update(status="failed", phase="quiet", failure="AssertionError")
        make_archive(self.root, present=["37.0"], status="failed", trials=trials)
        result = summary.summarize(self.root, SHA)
        lane = result["lanes"][-1]
        self.assertEqual((32, 2, 1), (result["plannedTrialCount"], result["recordedTrialCount"], result["validTrialCount"]))
        self.assertEqual(["passed", "failed", "not_executed", "not_executed"], [v["status"] for v in lane["trials"]])
        failed = lane["trials"][1]
        self.assertEqual(10, failed["observedMetrics"]["upToStoppedFrameCompletionMillis"])
        self.assertIsNone(failed["metrics"]["upToStoppedFrameCompletionMillis"])
        self.assertEqual(0, lane["scenarios"]["MODEL"]["validCount"])
        self.assertEqual("failed", lane["reportStatus"])
        self.assertEqual("failed", lane["diagnosticStatus"])
        self.assertEqual("incomplete", result["status"])

    def test_formal_ninety_nine_sample_prefix_keeps_p95_unavailable(self):
        trials = [fixtures.valid_trial(i, profile="formal") for i in range(198)]
        make_archive(self.root, profile="formal", present=["37.0"], status="incomplete", trials=trials)
        result = summary.summarize(self.root, SHA)
        lane = result["lanes"][-1]
        self.assertEqual(["unavailable", "unavailable"], [v["status"] for v in lane["trials"][-2:]])
        self.assertEqual(1600, result["plannedTrialCount"])
        for group in lane["scenarios"].values():
            self.assertEqual((100, 99, 99), (group["plannedCount"], group["recordedCount"], group["validCount"]))
            metric = group["metrics"]["upToCancelReturnUpperBoundMillis"]
            self.assertIsNone(metric["p95"])
            self.assertEqual("fewer_than_100_valid_original_samples", metric["p95UnavailableReason"])

    def test_root_failed_after_commit_failure_does_not_claim_missing_trial_never_ran(self):
        make_archive(self.root, present=["37.0"], status="failed", trials=[fixtures.valid_trial(0)])
        result = summary.summarize(self.root, SHA)
        lane = result["lanes"][-1]
        self.assertEqual(["passed", "unavailable", "unavailable", "unavailable"], [v["status"] for v in lane["trials"]])
        self.assertTrue(all(v["reason"] == "not_committed_before_failure" for v in lane["trials"][1:]))
        self.assertEqual("failed", lane["reportStatus"])

    def test_original_failed_run_job_and_case_are_never_promoted_by_valid_samples(self):
        original = make_archive(self.root)
        for where in ("run", "job", "case"):
            manifest = copy.deepcopy(original)
            if where == "run": manifest["sourceRun"]["conclusion"] = "failure"
            elif where == "job":
                manifest["busyStop"]["apis"][-1]["jobConclusion"] = "timed_out"
                manifest["apis"][-1]["jobConclusion"] = "timed_out"
            else: manifest["apis"][-1]["diagnosticResult"] = "failed"
            save_manifest(self.root, manifest)
            result = summary.summarize(self.root, SHA)
            self.assertTrue(result["evidenceComplete"])
            self.assertFalse(result["originalConclusionsSuccessful"])
            self.assertEqual(("incomplete", 32), (result["status"], result["validTrialCount"]))
            self.assertEqual(manifest["sourceRun"], result["archive"]["sourceRun"])
            self.assertEqual(manifest["apis"][-1]["diagnosticResult"], result["lanes"][-1]["instrumentedCaseResult"])

    def test_manifest_identity_profile_api_order_shared_limits_and_source_are_strict(self):
        original = make_archive(self.root, present=[])
        mutations = (lambda m: m.update(expectedTests=1600), lambda m: m.update(fullSuiteAcceptance=True),
                     lambda m: m.update(testSelection="AnotherTest"), lambda m: m.update(totalBytes=64*1024*1024+1),
                     lambda m: m.update(fileCount=8001), lambda m: m["sourceRun"].update(commit="b"*40),
                     lambda m: m["sourceRun"].update(attempt=True), lambda m: m["sourceRun"].update(event="push"),
                     lambda m: m["busyStop"].update(profile="formal"), lambda m: m["busyStop"].update(budgetAcceptance=True),
                     lambda m: m["busyStop"]["apis"].reverse(), lambda m: m["apis"].pop(),
                     lambda m: m["busyStop"]["apis"][0].update(diagnosticStatus="passed"))
        for mutate in mutations:
            manifest = copy.deepcopy(original); mutate(manifest); save_manifest(self.root, manifest)
            with self.assertRaises(ValueError): summary.summarize(self.root, SHA)
        for expected in (SHA[:8], "b" * 40, None):
            with self.assertRaises(ValueError): summary.summarize(self.root, expected)

    def test_raw_hash_semantics_capture_status_and_inventory_cannot_be_relabelled(self):
        original = make_archive(self.root, present=["37.0"])
        folder = self.root / "busy-stop/api-37.0"
        path = folder / "trial-000.json"
        data = path.read_bytes(); path.write_bytes(data + b" ")
        with self.assertRaises(ValueError): summary.summarize(self.root, SHA)
        path.write_bytes(data)
        trials = [fixtures.valid_trial(i) for i in range(4)]
        trials[0]["probe"]["overflow"] = 1
        fixtures.write_bundle(folder, trials=trials)
        refreshed_refs(self.root, original)
        with self.assertRaises(ValueError): summary.summarize(self.root, SHA)
        fixtures.write_bundle(folder)
        refreshed_refs(self.root, original)
        original["busyStop"]["apis"][-1]["diagnosticStatus"] = "failed"
        save_manifest(self.root, original)
        with self.assertRaises(ValueError): summary.summarize(self.root, SHA)
        original["busyStop"]["apis"][-1]["diagnosticStatus"] = "passed"
        save_manifest(self.root, original)
        (folder / "trial-004.json").write_text("{}")
        with self.assertRaises(ValueError): summary.summarize(self.root, SHA)

    def test_missing_empty_and_linked_evidence_stay_unavailable_or_rejected(self):
        make_archive(self.root, present=[])
        result = summary.summarize(self.root, SHA)
        self.assertEqual((32, 0, 0), (result["plannedTrialCount"], result["recordedTrialCount"], result["validTrialCount"]))
        self.assertTrue(all(v["status"] == "unavailable" for lane in result["lanes"] for v in lane["trials"]))
        (self.root / "busy-stop").mkdir()
        (self.root / "busy-stop/api-37.0").symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError): summary.summarize(self.root, SHA)

    def test_cli_is_read_only_and_uses_incomplete_exit_without_overwriting(self):
        make_archive(self.root, present=[])
        output = self.root.parent / "result.json"
        before = (self.root / "manifest.json").read_bytes()
        self.assertEqual(1, summary.main([str(self.root), "--source", SHA, "--output", str(output)]))
        self.assertEqual("incomplete", json.loads(output.read_text())["status"])
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, summary.main([str(self.root), "--source", SHA, "--output", str(output)]))
            self.assertEqual(2, summary.main([str(self.root), "--source", SHA, "--output", str(self.root / "new.json")]))
        self.assertEqual(before, (self.root / "manifest.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
