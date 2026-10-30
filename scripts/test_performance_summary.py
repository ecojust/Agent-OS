"""Check provenance, denominator retention and descriptive repeated-run statistics."""

import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import collect_performance_evidence as evidence
import summarize_performance_runs as summary
from test_performance_evidence import report as base_report

SHA = "a" * 40


def report(api):
    value = base_report(api)
    for index, mode in enumerate(value["modes"]):
        start = (index + 1) * 10_000_000_000
        mode.update(startedNanos=start, endedNanos=start + 2_000_000_000, trace=[])
        mean = (150, 200, 300, 400)[index]
        cpu = (10, 20, 50, 10)[index]
        mode["samples"] = [dict(elapsedRealtimeNanos=start + 100_000_000 + offset * 1_000_000_000,
                                 processCpuMillis=1000 * (index + 1) + offset * cpu,
                                 totalPssKiB=mean - 50 + offset * 100, dalvikPssKiB=10,
                                 nativePssKiB=20, otherPssKiB=mean - 80 + offset * 100,
                                 collectionNanos=1_000_000) for offset in range(2)]
    stop = value["modes"][-1]
    start = stop["startedNanos"]
    stop.update(stopEnqueuedNanos=start + 200, stopStateAppliedNanos=start + 247,
                stopRequestToReceivedNanos=25, stopReceivedToInvalidatedNanos=5,
                stopRequestToUiAppliedNanos=47)
    stop["trace"] = [dict(stage=stage, startNanos=start + at, durationNanos=duration,
                           mainThread=True, nodeCount=-1, snapshotJsonBytes=-1,
                           truncated=False, epoch=1, dispatchAttempts=0)
                     for stage, at, duration in (("STOP_RECEIVED", 225, 3),
                                                 ("STOP_EPOCH_INVALIDATED", 230, 1),
                                                 ("STOP_UI_APPLIED", 245, 2))]
    return value


def save_json(path, value):
    data = (json.dumps(value) + "\n").encode()
    path.write_bytes(data)
    return {"path": path.name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def write_archive(parent, run_id, *, attempt=1, mutate_report=None, conclusion="success"):
    root = parent / f"{run_id}-{attempt}"
    root.mkdir()
    identity = {"repository": summary.REPOSITORY, "commit": SHA, "runId": run_id, "attempt": attempt}
    records = []
    for label in evidence.LABELS:
        api = int(label.split(".")[0])
        directory = root / "performance" / ("api-" + label)
        directory.mkdir(parents=True)
        raw = report(api)
        if mutate_report:
            mutate_report(raw)
        ref = save_json(directory / evidence.REPORT_NAME, raw)
        capture = evidence.empty_capture(label, identity)
        capture.update(actualSdkInt=api, captureStatus="collected", captureError=None, gradleExitCode=0,
                       report=dict(ref, status=raw["status"]), emulatorStepOutcome="success")
        capture_ref = save_json(directory / "capture.json", capture)
        records.append({"apiLevel": api, "matrixLabel": label, "jobConclusion": conclusion,
                        "captureStatus": "collected", "captureError": None, "actualSdkInt": api,
                        "diagnosticStatus": raw["status"], "gradleExitCode": 0,
                        "declaredCiProfile": capture["declaredCiProfile"], "artifactId": run_id * 100 + api,
                        "files": [capture_ref, ref]})
    manifest = {"schemaVersion": 1, "kind": "android-emulator-test-evidence", "executionScope": "full",
                "sourceRun": dict(identity, branch="master", conclusion=conclusion,
                                  url=f"https://github.com/{summary.REPOSITORY}/actions/runs/{run_id}"),
                "expectedMatrixLabels": list(evidence.LABELS),
                "performanceDiagnostics": {"kind": "performance-diagnostic-attachments",
                                           "diagnosticOnly": True, "budgetAcceptance": False, "apis": records}}
    save_json(root / "manifest.json", manifest)
    return root


def edit_manifest(root, edit):
    path = root / "manifest.json"
    value = json.loads(path.read_text())
    edit(value)
    save_json(path, value)


class PerformanceSummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.first = write_archive(self.root, 101)
        self.second = write_archive(self.root, 102)

    def run_summary(self, *roots):
        return summary.summarize(list(roots or (self.first, self.second)), SHA)

    def test_values_use_sample_span_and_means_and_keep_original_samples(self):
        result = self.run_summary()
        self.assertEqual((16, 16, 0), (result["expectedLaneCount"], result["eligibleLaneCount"], result["excludedLaneCount"]))
        self.assertEqual(8, len(result["groups"]))
        lane = result["lanes"][0]
        a, b, c = (lane["modes"][key] for key in evidence.MODES[:3])
        self.assertEqual(2000, a["wallMillis"])
        self.assertEqual(1000, a["resourceSampleSpanMillis"])
        self.assertEqual([1, 2, 5], [mode["cpuPercentOneCore"] for mode in (a, b, c)])
        self.assertEqual([150, 200, 300], [mode["sampleMeanTotalPssKiB"] for mode in (a, b, c)])
        self.assertEqual(200, a["sampleMaxTotalPssKiB"])
        self.assertEqual(report(30)["modes"][0]["samples"], a["resourceSamples"])
        self.assertEqual({"cpuPercentagePoints": 1, "sampleMeanTotalPssKiB": 50, "sampleMaxTotalPssKiB": 50}, lane["differences"]["B_MINUS_A"])
        self.assertEqual(4, lane["differences"]["C_MINUS_A"]["cpuPercentagePoints"])
        self.assertEqual({"count": 2, "missing": 0, "min": 1, "median": 1.0, "max": 1}, result["groups"][0]["modes"][evidence.MODES[0]]["cpuPercentOneCore"])
        self.assertFalse(result["budgetAcceptance"])
        self.assertEqual(64, len(result["tool"]["summaryScriptSha256"]))

    def test_medians_are_per_run_not_pooled_samples_and_negative_differences_survive(self):
        third = write_archive(self.root, 103, mutate_report=lambda value: value["modes"][1]["samples"][1].update(processCpuMillis=2000))
        result = self.run_summary(self.first, self.second, third)
        actual = result["groups"][0]["differences"]["B_MINUS_A"]["cpuPercentagePoints"]
        self.assertEqual({"count": 3, "missing": 0, "min": -1, "median": 1, "max": 1}, actual)
        self.assertEqual(24, result["expectedLaneCount"])

    def test_duplicate_run_or_attempt_is_not_an_independent_repetition(self):
        for other in (self.first, write_archive(self.root, 101, attempt=2)):
            with self.subTest(other=other), self.assertRaisesRegex(ValueError, "duplicate_source_run"):
                self.run_summary(self.first, other)

    def test_different_fingerprint_is_separate_and_never_pooled(self):
        third = write_archive(self.root, 103, mutate_report=lambda value: value["device"].update(fingerprint="other-image"))
        result = self.run_summary(self.first, self.second, third)
        self.assertEqual(16, len(result["groups"]))
        self.assertEqual([1] * 8 + [2] * 8, sorted(group["runCount"] for group in result["groups"]))
        self.assertEqual(8, sum(group["repeatedGroup"] for group in result["groups"]))

    def test_insufficient_cpu_samples_are_null_and_counted_missing(self):
        third = write_archive(self.root, 103, mutate_report=lambda value: value["modes"][0].update(samples=value["modes"][0]["samples"][:1]))
        result = self.run_summary(self.first, third)
        actual = result["groups"][0]["modes"][evidence.MODES[0]]["cpuPercentOneCore"]
        self.assertEqual({"count": 1, "missing": 1, "min": 1, "median": 1, "max": 1}, actual)
        lane = next(lane for lane in result["lanes"] if lane["runId"] == 103)
        self.assertIsNone(lane["differences"]["B_MINUS_A"]["cpuPercentagePoints"])
        self.assertEqual("insufficient_sample_span", lane["modes"][evidence.MODES[0]]["cpuUnavailableReason"])

    def test_failed_full_run_does_not_relabel_a_valid_performance_subcase(self):
        third = write_archive(self.root, 103, conclusion="failure")
        result = self.run_summary(self.first, third)
        self.assertEqual("complete", result["status"])
        self.assertEqual("failure", result["archives"][1]["sourceRun"]["conclusion"])
        self.assertTrue(all(lane["fullRunConclusion"] == "failure" and lane["eligible"] for lane in result["lanes"] if lane["runId"] == 103))

    def test_failed_diagnostics_are_explicitly_excluded_but_kept_in_denominator(self):
        third = write_archive(self.root, 103, mutate_report=lambda value: value.update(status="failed"), conclusion="failure")
        result = self.run_summary(self.first, third)
        self.assertEqual(("incomplete", 16, 8, 8), (result["status"], result["expectedLaneCount"], result["eligibleLaneCount"], result["excludedLaneCount"]))
        self.assertEqual(8, len([lane for lane in result["lanes"] if lane["reason"] == "failed_diagnostic_preserved_not_aggregated"]))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, summary.main([str(self.first), str(third), "--source", SHA]))

    def test_missing_artifact_is_reported_and_not_treated_as_zero(self):
        directory = self.second / "performance/api-30"
        for path in directory.iterdir():
            path.unlink()
        edit_manifest(self.second, lambda data: data["performanceDiagnostics"]["apis"][0].update(files=[], captureStatus="artifact_unavailable", diagnosticStatus=None))
        result = self.run_summary()
        self.assertEqual(1, result["excludedLaneCount"])
        lane = next(lane for lane in result["lanes"] if lane["runId"] == 102 and lane["apiLevel"] == 30)
        self.assertEqual("artifact_unavailable", lane["reason"])
        self.assertNotIn("modes", lane)

    def test_source_matrix_and_binding_changes_are_rejected(self):
        original = (self.second / "manifest.json").read_bytes()
        changes = [lambda m: m["sourceRun"].update(commit="b" * 40),
                   lambda m: m["sourceRun"].update(runId=True),
                   lambda m: m["sourceRun"].update(repository="other/repository"),
                   lambda m: m["sourceRun"].update(url="https://example.test/102"),
                   lambda m: m["performanceDiagnostics"]["apis"].pop(),
                   lambda m: m["performanceDiagnostics"]["apis"].reverse(),
                   lambda m: m["performanceDiagnostics"]["apis"][0].update(actualSdkInt=31),
                   lambda m: m["performanceDiagnostics"]["apis"][0].update(gradleExitCode=1),
                   lambda m: m["performanceDiagnostics"].update(budgetAcceptance=True),
                   lambda m: m.update(executionScope="rotation-diagnostic")]
        for change in changes:
            with self.subTest(change=change):
                (self.second / "manifest.json").write_bytes(original)
                edit_manifest(self.second, change)
                with self.assertRaises(ValueError):
                    self.run_summary()

    def test_hash_tampering_extra_files_and_symlinks_are_rejected(self):
        directory = self.second / "performance/api-30"
        path = directory / evidence.REPORT_NAME
        original = path.read_bytes()
        path.write_bytes(original.replace(b'"passed"', b'"failed"', 1))
        with self.assertRaisesRegex(ValueError, "hash_mismatch"):
            self.run_summary()
        path.write_bytes(original)
        extra = directory / "unexpected.json"
        extra.write_text("{}")
        with self.assertRaisesRegex(ValueError, "file_set_mismatch"):
            self.run_summary()
        extra.unlink()
        copy_path = self.root / "copy.json"
        copy_path.write_bytes(original)
        path.unlink()
        path.symlink_to(copy_path)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.run_summary()

    def test_report_schema_rejects_inconsistent_timing_and_changed_conditions(self):
        changes = [lambda d: d["conditions"].update(order="C_B_A_STOP"),
                   lambda d: d["modes"][1].update(trace=[copy.deepcopy(d["modes"][-1]["trace"][0])]),
                   lambda d: d["modes"][-1].update(stopRequestToUiAppliedNanos=0),
                   lambda d: d["modes"][0].update(traceOverwrittenEvents=1)]
        for index, change in enumerate(changes):
            third = write_archive(self.root, 200 + index, mutate_report=change)
            with self.subTest(index=index), self.assertRaises(ValueError):
                self.run_summary(self.first, third)

    def test_traversal_and_unknown_profiles_rejected_even_with_valid_hashes(self):
        edit_manifest(self.second, lambda m: m["performanceDiagnostics"]["apis"][0]["files"][0].update(path="../capture.json"))
        with self.assertRaisesRegex(ValueError, "unexpected_or_duplicate_performance_path"):
            self.run_summary()
        with self.assertRaisesRegex(ValueError, "path_traversal"):
            summary.checked_path(self.root / ".." / "escape")
        third = write_archive(self.root, 103)
        directory = third / "performance/api-30"
        path = directory / "capture.json"
        capture = json.loads(path.read_text())
        capture["declaredCiProfile"] = "unknown_profile"
        reference = save_json(path, capture)
        edit_manifest(third, lambda m: m["performanceDiagnostics"]["apis"][0].update(declaredCiProfile="unknown_profile", files=[reference, m["performanceDiagnostics"]["apis"][0]["files"][1]]))
        with self.assertRaisesRegex(ValueError, "capture_identity_mismatch"):
            self.run_summary(self.first, third)

    def test_cli_never_overwrites_input_existing_output_or_output_symlink(self):
        original = (self.first / "manifest.json").read_bytes()
        for output in (self.first / "manifest.json", self.first / "new.json"):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, summary.main([str(self.first), str(self.second), "--source", SHA, "--output", str(output)]))
        self.assertEqual(original, (self.first / "manifest.json").read_bytes())
        output = self.root / "summary.json"
        self.assertEqual(0, summary.main([str(self.first), str(self.second), "--source", SHA, "--output", str(output)]))
        generated = output.read_bytes()
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, summary.main([str(self.first), str(self.second), "--source", SHA, "--output", str(output)]))
        self.assertEqual(generated, output.read_bytes())
        alias = self.root / "alias.json"
        alias.symlink_to(output)
        with self.assertRaisesRegex(ValueError, "symlink"):
            summary.write_output(alias, [self.first], "changed")

    def test_input_order_does_not_change_the_derived_report(self):
        self.assertEqual(self.run_summary(self.first, self.second), self.run_summary(self.second, self.first))

    def test_malformed_structures_and_duplicate_json_keys_exit_cleanly(self):
        original = json.loads((self.second / "manifest.json").read_text())
        variants = [None, [], dict(original, sourceRun=[]), dict(original, performanceDiagnostics=[]),
                    dict(original, performanceDiagnostics=dict(original["performanceDiagnostics"], apis=[None] * 8))]
        for value in variants:
            save_json(self.second / "manifest.json", value)
            error = io.StringIO()
            with self.subTest(value=type(value)), contextlib.redirect_stderr(error):
                self.assertEqual(2, summary.main([str(self.first), str(self.second), "--source", SHA]))
            self.assertIn("Performance summary rejected:", error.getvalue())
            self.assertNotIn("Traceback", error.getvalue())
        (self.second / "manifest.json").write_text('{"schemaVersion":1,"schemaVersion":1}')
        with self.assertRaisesRegex(ValueError, "duplicate_json_key"):
            self.run_summary()

    def test_matching_outer_hash_cannot_hide_capture_source_or_report_reference_mismatch(self):
        directory = self.second / "performance/api-30"
        capture_path = directory / "capture.json"
        original_capture = json.loads(capture_path.read_text())
        original_manifest = (self.second / "manifest.json").read_bytes()
        changes = [lambda c: c["sourceRun"].update(runId=101),
                   lambda c: c["sourceRun"].update(commit="b" * 40),
                   lambda c: c["report"].update(sha256="0" * 64),
                   lambda c: c["report"].update(status="failed")]
        for change in changes:
            capture = copy.deepcopy(original_capture)
            change(capture)
            ref = save_json(capture_path, capture)
            (self.second / "manifest.json").write_bytes(original_manifest)
            edit_manifest(self.second, lambda m: m["performanceDiagnostics"]["apis"][0]["files"].__setitem__(0, ref))
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.run_summary()

    def test_output_symlink_parent_and_input_alias_are_rejected(self):
        directory_link = self.root / "linked-archive"
        directory_link.symlink_to(self.first, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.run_summary(directory_link, self.second)
        with self.assertRaisesRegex(ValueError, "symlink"):
            summary.write_output(directory_link / "new-summary.json", [self.first, self.second], "{}")


if __name__ == "__main__":
    unittest.main()
