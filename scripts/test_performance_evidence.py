"""Keep numeric diagnostics bounded, attributable and separate from UI evidence."""

import copy
import contextlib
import hashlib
import json
import io
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import collect_performance_evidence as performance
import publish_test_evidence as publisher

SOURCE = {"repository": "ecojust/Agent-OS", "runId": 123, "attempt": 2, "commit": "a" * 40}


def report(api=37):
    value = {
        "schemaVersion": 1, "kind": performance.KIND, "diagnosticOnly": True,
        "budgetAcceptance": False, "status": "passed", "appProcessPid": 420,
        "device": {"sdkInt": api, "manufacturer": "synthetic", "model": "unit-test",
                   "hardware": "synthetic", "fingerprint": "synthetic-fixture", "emulatorHeuristic": True},
        "conditions": {"buildType": "debug", "processScope": "instrumented_app_with_sampler",
                       "localModelScope": "same_process_C_and_STOP_only", "fixtureProcessIncluded": False,
                       "screenshotSamplingIncluded": False, "forcedGc": False, "systemAnimationOverrides": False,
                       "windowMillis": 6000, "sampleIntervalMillis": 1000, "order": "A_B_C_STOP"},
        "modes": [{"mode": name, "status": "passed", "startedNanos": start, "endedNanos": start + 90,
                   "sampleLimitReached": False, "traceOverwrittenEvents": 0, "traceCapacity": 4096,
                   "stopEnqueuedNanos": -1, "stopStateAppliedNanos": -1,
                   "stopRequestToReceivedNanos": -1, "stopReceivedToInvalidatedNanos": -1,
                   "stopRequestToUiAppliedNanos": -1,
                   "samples": [{"elapsedRealtimeNanos": start + 10, "processCpuMillis": start, "totalPssKiB": 300,
                                "dalvikPssKiB": 100, "nativePssKiB": 100, "otherPssKiB": 100, "collectionNanos": 1}],
                   "trace": [{"stage": "MAIN_QUEUE", "startNanos": start + 5, "durationNanos": 1,
                              "mainThread": True, "nodeCount": -1, "snapshotJsonBytes": -1,
                              "truncated": False, "epoch": 1, "dispatchAttempts": 0}]}
                  for name, start in zip(performance.MODES, (100, 200, 300, 400))]}
    stop = value["modes"][-1]
    stop.update(stopEnqueuedNanos=420, stopStateAppliedNanos=447,
                stopRequestToReceivedNanos=5, stopReceivedToInvalidatedNanos=5,
                stopRequestToUiAppliedNanos=27)
    template = stop["trace"][0]
    stop["trace"] = [dict(template, stage=stage, startNanos=started, durationNanos=duration)
                     for stage, started, duration in (("STOP_RECEIVED", 425, 3),
                                                       ("STOP_EPOCH_INVALIDATED", 430, 1),
                                                       ("STOP_UI_APPLIED", 445, 2))]
    return value


def source_run():
    return {"id": 123, "run_attempt": 2, "repository": {"id": 1, "full_name": "ecojust/Agent-OS"},
            "head_repository": {"id": 1, "full_name": "ecojust/Agent-OS"}, "event": "push",
            "head_branch": "master", "head_sha": "a" * 40, "status": "completed",
            "path": ".github/workflows/android.yml", "conclusion": "success"}


def artifact():
    return {"id": 456, "name": "performance-reports-api-37.0-attempt-2", "expired": False,
            "size_in_bytes": 1000, "created_at": "2026-10-26T00:01:00Z",
            "workflow_run": {"id": 123, "head_sha": "a" * 40, "head_branch": "master",
                             "repository_id": 1, "head_repository_id": 1}}


def job():
    return {"name": "emulator-tests (37.0)", "status": "completed", "run_id": 123, "run_attempt": 2,
            "head_sha": "a" * 40, "conclusion": "success", "started_at": "2026-10-26T00:00:00Z",
            "completed_at": "2026-10-26T00:02:00Z"}


def write_artifact(root, status="passed"):
    source = root / "incoming" / artifact()["name"]
    source.mkdir(parents=True)
    value = report()
    value["status"] = status
    data = (json.dumps(value) + "\n").encode()
    (source / performance.REPORT_NAME).write_bytes(data)
    capture = performance.empty_capture("37.0", SOURCE)
    capture.update({"actualSdkInt": 37, "captureStatus": "collected", "captureError": None,
                    "gradleExitCode": 0 if status == "passed" else 1,
                    "report": {"path": performance.REPORT_NAME, "bytes": len(data),
                               "sha256": hashlib.sha256(data).hexdigest(), "status": status}})
    (source / "capture.json").write_text(json.dumps(capture))
    return source


class PerformanceEvidenceTests(unittest.TestCase):
    def test_report_keeps_raw_values_and_failed_partial_diagnostics(self):
        value = report()
        self.assertIs(value, performance.validate_report(value, 37))
        value["status"] = "failed"
        value["modes"] = value["modes"][:1]
        value["modes"][0]["status"] = "failed"
        value["modes"][0]["samples"] = []
        performance.validate_report(value, 37)
        value["status"] = "passed"
        with self.assertRaises(ValueError):
            performance.validate_report(value, 37)

    def test_schema_rejects_payload_strings_unknown_events_and_nonfinite_or_unbounded_numbers(self):
        for reason in ("LOCAL_CONTROL_STALE", "OWNED_WINDOW_MISMATCH", "OBSERVATION_REVISION_MISMATCH"):
            value = report()
            value["modes"][0]["trace"][0].update(stage=reason)
            performance.validate_report(value, 37)
            value["modes"][0]["trace"][0].update(windowText="never archive content")
            with self.assertRaises(ValueError):
                performance.validate_report(value, 37)
        changes = [
            lambda value: value.update(kind="screenshot"),
            lambda value: value.update(diagnosticOnly=False),
            lambda value: value.update(budgetAcceptance=True),
            lambda value: value.update(schemaVersion=True),
            lambda value: value.update(apiKey="must-not-be-accepted"),
            lambda value: value["device"].update(sdkInt=36),
            lambda value: value["device"].update(fingerprint="x" * 1025),
            lambda value: value["conditions"].update(forcedGc=0),
            lambda value: value["modes"].append(copy.deepcopy(value["modes"][0])),
            lambda value: value["modes"][0].update(samples=[{}] * 129),
            lambda value: value["modes"][0].update(trace=[{}] * 4097),
            lambda value: value["modes"][0]["trace"][0].update(stage="HTTP_REQUEST_BODY"),
            lambda value: value["modes"][0]["trace"][0].update(nodeCount=301),
            lambda value: value["modes"][0]["trace"][0].update(snapshotJsonBytes=65537),
            lambda value: value["modes"][0]["samples"][0].update(totalPssKiB=float("nan")),
            lambda value: value["modes"][0]["samples"][0].update(processCpuMillis=True),
            lambda value: value["modes"][0]["samples"][0].update(collectionNanos=2 ** 63),
            lambda value: value["modes"][0]["samples"][0].update(windowText="never archive content"),
        ]
        for index, change in enumerate(changes):
            with self.subTest(index=index):
                value = report()
                change(value)
                with self.assertRaises(ValueError):
                    performance.validate_report(value, 37)

    def test_resource_samples_are_monotonic_and_inside_sequential_mode_windows(self):
        value = report()
        first = value["modes"][0]["samples"][0]
        value["modes"][0]["samples"].append(dict(first, elapsedRealtimeNanos=120, processCpuMillis=101))
        performance.validate_report(value, 37)
        changes = [
            lambda data: data["modes"][0]["samples"][1].update(elapsedRealtimeNanos=109),
            lambda data: data["modes"][0]["samples"][1].update(processCpuMillis=99),
            lambda data: data["modes"][1]["samples"][0].update(processCpuMillis=90),
            lambda data: data["modes"][0]["samples"][0].update(elapsedRealtimeNanos=99),
            lambda data: data["modes"][0]["samples"][1].update(elapsedRealtimeNanos=191),
            lambda data: data["modes"][0]["samples"][1].update(collectionNanos=71),
            lambda data: data["modes"][1].update(startedNanos=189),
        ]
        for index, change in enumerate(changes):
            with self.subTest(index=index):
                invalid = copy.deepcopy(value)
                change(invalid)
                with self.assertRaises(ValueError):
                    performance.validate_report(invalid, 37)

    def test_trace_completion_order_can_differ_but_events_cannot_escape_mode(self):
        value = report()
        event = value["modes"][0]["trace"][0]
        value["modes"][0]["trace"] = [dict(event, startNanos=120, durationNanos=2),
                                      dict(event, startNanos=110, durationNanos=20)]
        performance.validate_report(value, 37)
        for change in ({"startNanos": 99}, {"startNanos": 191}, {"durationNanos": 71}):
            with self.subTest(change=change):
                invalid = copy.deepcopy(value)
                invalid["modes"][0]["trace"][0].update(change)
                with self.assertRaises(ValueError):
                    performance.validate_report(invalid, 37)

    def test_passed_stop_requires_unique_ordered_stages_and_exact_derived_durations(self):
        changes = [
            lambda value: value["modes"][-1].update(stopRequestToReceivedNanos=0),
            lambda value: value["modes"][-1].update(stopReceivedToInvalidatedNanos=-1),
            lambda value: value["modes"][-1].update(stopRequestToUiAppliedNanos=26),
            lambda value: value["modes"][-1].update(stopStateAppliedNanos=446),
            lambda value: value["modes"][-1].update(stopEnqueuedNanos=500),
            lambda value: value["modes"][-1]["trace"].pop(0),
            lambda value: value["modes"][-1]["trace"].append(copy.deepcopy(value["modes"][-1]["trace"][0])),
            lambda value: value["modes"][-1]["trace"][0].update(durationNanos=6),
            lambda value: value["modes"][-1]["trace"][1].update(durationNanos=16),
            lambda value: value["modes"][0].update(stopReceivedToInvalidatedNanos=0),
            lambda value: value["modes"][0].update(traceOverwrittenEvents=1),
            lambda value: value["modes"][0].update(sampleLimitReached=True),
        ]
        for index, change in enumerate(changes):
            with self.subTest(index=index):
                value = report()
                change(value)
                with self.assertRaises(ValueError):
                    performance.validate_report(value, 37)

    def test_failed_stop_keeps_missing_stage_sentinels_without_inventing_zero_timings(self):
        value = report()
        value["status"] = "failed"
        stop = value["modes"][-1]
        stop.update(status="failed", stopStateAppliedNanos=-1, stopRequestToUiAppliedNanos=-1)
        stop["trace"].pop()
        performance.validate_report(value, 37)
        stop.update(stopRequestToReceivedNanos=-1, stopReceivedToInvalidatedNanos=-1)
        stop["trace"] = []
        performance.validate_report(value, 37)
        stop["status"] = "passed"
        with self.assertRaises(ValueError):
            performance.validate_report(value, 37)
        stop["status"] = "failed"
        stop["stopRequestToReceivedNanos"] = 0
        with self.assertRaises(ValueError):
            performance.validate_report(value, 37)

    def test_json_reader_rejects_duplicates_nan_oversize_and_symlinks(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            for data in (b'{"a":1,"a":2}', b'{"sample":NaN}', b'{"sample":Infinity}'):
                path.write_bytes(data)
                with self.assertRaises(ValueError):
                    performance.read_json(path)
            path.write_bytes(b" " * (performance.MAX_REPORT_BYTES + 1))
            with self.assertRaises(ValueError):
                performance.read_json(path)
            link = Path(temporary) / "link.json"
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                performance.read_json(link)

    def test_collect_preserves_failed_raw_json_without_creating_screenshots(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            value = report()
            value["status"] = "failed"
            raw = json.dumps(value, indent=3).encode()

            def fake_adb(command, **kwargs):
                if command[1] == "shell":
                    return subprocess.CompletedProcess(command, 0, stdout="37\n")
                kwargs["stdout"].write(raw)
                return subprocess.CompletedProcess(command, 0)

            with patch.object(performance.subprocess, "run", side_effect=fake_adb):
                capture = performance.collect("37.0", SOURCE, 7, root)
            performance.validate_capture(capture, "37.0", SOURCE)
            self.assertEqual("collected", capture["captureStatus"])
            self.assertEqual("failed", capture["report"]["status"])
            self.assertEqual(7, capture["gradleExitCode"])
            self.assertEqual(raw, (root / performance.REPORT_NAME).read_bytes())
            self.assertEqual([performance.REPORT_NAME], [entry.name for entry in root.iterdir()])

    def test_collect_records_missing_and_invalid_data_without_reusing_old_reports(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / performance.REPORT_NAME).write_text("stale")

            def fake_adb(command, **kwargs):
                if command[1] == "shell":
                    return subprocess.CompletedProcess(command, 0, stdout="37\n")
                kwargs["stdout"].write(b'{"key":"untrusted-content"}')
                return subprocess.CompletedProcess(command, 0)

            with patch.object(performance.subprocess, "run", side_effect=fake_adb):
                capture = performance.collect("37.0", SOURCE, 1, root)
            self.assertEqual("invalid", capture["captureStatus"])
            self.assertEqual("unexpected_schema_fields", capture["captureError"])
            self.assertFalse((root / performance.REPORT_NAME).exists())
            with patch.object(performance.subprocess, "run", side_effect=OSError("private-detail")):
                capture = performance.collect("37.0", SOURCE, 1, root)
            self.assertEqual("unavailable", capture["captureStatus"])
            self.assertNotIn("private-detail", json.dumps(capture))

    def test_artifact_source_attempt_expiration_size_and_ambiguity_are_checked(self):
        origin_changes = ({"id": 124}, {"head_sha": "b" * 40}, {"head_branch": "feature"},
                          {"repository_id": 2}, {"head_repository_id": 2})
        for change in origin_changes:
            item = artifact()
            item["workflow_run"].update(change)
            with self.assertRaises(ValueError):
                publisher.performance_artifacts({"artifacts": [item]}, source_run())
        for change in ({"id": True}, {"size_in_bytes": performance.MAX_ARTIFACT_BYTES + 1}):
            item = artifact()
            item.update(change)
            with self.assertRaises(ValueError):
                publisher.performance_artifacts({"artifacts": [item]}, source_run())
        with self.assertRaises(ValueError):
            publisher.performance_artifacts({"artifacts": [artifact(), artifact()]}, source_run())
        item = artifact()
        item["name"] = item["name"].replace("attempt-2", "attempt-1")
        self.assertEqual([], publisher.performance_artifacts({"artifacts": [item]}, source_run()))
        item = artifact()
        item["expired"] = True
        self.assertEqual([], publisher.performance_artifacts({"artifacts": [item]}, source_run()))

    def test_performance_archive_is_separate_and_keeps_missing_lanes_and_raw_report(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = write_artifact(root, "failed")
            artifacts = root / "artifacts.json"
            artifacts.write_text(json.dumps({"artifacts": [artifact()]}))
            args = SimpleNamespace(repository=SOURCE["repository"], artifacts=artifacts, incoming=root / "incoming")
            budget = {"bytes": 0, "files": 0}
            result = publisher.archive_performance(args, source_run(), [job()], root / "output", budget)
            self.assertEqual(False, result["budgetAcceptance"])
            self.assertEqual("artifact_unavailable", result["apis"][0]["captureStatus"])
            self.assertEqual("failed", result["apis"][-1]["diagnosticStatus"])
            self.assertNotIn("screenshotCount", result["apis"][-1])
            self.assertNotIn("recordedTests", result["apis"][-1])
            self.assertEqual(2, budget["files"])
            self.assertEqual((source / performance.REPORT_NAME).read_bytes(),
                             (root / "output/performance/api-37.0" / performance.REPORT_NAME).read_bytes())

    def test_archive_rejects_changed_report_foreign_capture_wrong_job_time_and_extra_files(self):
        for case in ("hash", "source", "time", "extra", "symlink"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = write_artifact(root)
                item = artifact()
                if case == "hash":
                    with (source / performance.REPORT_NAME).open("a") as output:
                        output.write(" ")
                elif case == "source":
                    path = source / "capture.json"
                    capture = json.loads(path.read_text())
                    capture["sourceRun"]["attempt"] = 1
                    path.write_text(json.dumps(capture))
                elif case == "time":
                    item["created_at"] = "2026-10-25T00:01:00Z"
                elif case == "extra":
                    (source / "payload.py").write_text("raise Exception('never execute')")
                else:
                    (source / performance.REPORT_NAME).unlink()
                    (source / performance.REPORT_NAME).symlink_to(source / "capture.json")
                artifacts = root / "artifacts.json"
                artifacts.write_text(json.dumps({"artifacts": [item]}))
                args = SimpleNamespace(repository=SOURCE["repository"], artifacts=artifacts, incoming=root / "incoming")
                with self.assertRaises(ValueError):
                    publisher.archive_performance(args, source_run(), [job()], root / "output", {"bytes": 0, "files": 0})

    def test_combined_archive_preserves_ui_and_test_counts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_artifact(root)
            ui = root / "incoming/screenshots-api-37.0-attempt-2"
            (ui / "screenshots").mkdir(parents=True)
            (ui / "screenshots/manifest.json").write_text(json.dumps({"tests": [{"captures": []}]}))
            (ui / "capture.json").write_text(json.dumps({"schemaVersion": 1, "apiLevel": 37,
                "matrixLabel": "37.0", "actualSdkInt": 37, "gradleExitCode": 0,
                "captureStatus": "collected", "junit": {"tests": 1, "failures": 0}}))
            artifacts = root / "artifacts.json"
            artifacts.write_text(json.dumps({"artifacts": [artifact(),
                {"name": ui.name, "expired": False}]}))
            jobs = root / "jobs.json"
            jobs.write_text(json.dumps({"jobs": [job()]}))
            args = SimpleNamespace(repository=SOURCE["repository"], artifacts=artifacts,
                                   jobs=jobs, incoming=root / "incoming")
            previous = Path.cwd()
            try:
                os.chdir(root)
                with contextlib.redirect_stdout(io.StringIO()):
                    publisher.archive(args, source_run(), 123, 2)
            finally:
                os.chdir(previous)
            manifest = json.loads((root / "screenshots/123-2/manifest.json").read_text())
            self.assertEqual(1, manifest["apis"][-1]["recordedTests"])
            self.assertEqual(0, manifest["apis"][-1]["screenshotCount"])
            self.assertEqual(0, manifest["apis"][-1]["unrecordedTests"])
            self.assertEqual("passed", manifest["performanceDiagnostics"]["apis"][-1]["diagnosticStatus"])


if __name__ == "__main__":
    unittest.main()
