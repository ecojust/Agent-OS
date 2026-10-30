"""Keep the paired idle diagnostic independently attributable and failure-inclusive."""

import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest

import collect_android_evidence as android
import collect_idle_pair_evidence as paired
import publish_test_evidence as publisher
import test_evidence_scripts as evidence_tests
import test_performance_evidence as legacy


def source_run():
    return dict(legacy.source_run(), event="workflow_dispatch", display_title=publisher.IDLE_PAIR_TITLE,
                conclusion="failure")


def artifact():
    return dict(legacy.artifact(), name="idle-pair-reports-api-37.0-attempt-2")


def failed_report():
    # No window completed. This is valid failure evidence, never twelve passing windows.
    return {"schemaVersion": 1, "kind": "agent-idle-paired-diagnostics", "diagnosticOnly": True,
            "budgetAcceptance": False, "status": "failed", "appProcessPid": 420,
            "device": legacy.report()["device"],
            "conditions": {"buildType": "debug", "processScope": "instrumented_app_with_sampler",
                           "fixtureProcessIncluded": False, "screenshotSamplingIncluded": False,
                           "forcedGc": False, "systemAnimationOverrides": False,
                           "windowMillis": 20000, "settleMillis": 2000, "sampleIntervalMillis": 1000,
                           "pairCount": 6, "order": "AB_BA_AB_BA_AB_BA",
                           "fixtureReset": "once_before_all_windows", "modelUsed": False,
                           "stateChecks": "before_and_after_window"},
            "windows": []}


def write_artifact(root, capture_status="collected"):
    directory = root / "incoming" / artifact()["name"]
    directory.mkdir(parents=True)
    capture = paired.empty_capture("37.0", legacy.SOURCE)
    capture.update(actualSdkInt=37, gradleExitCode=1, captureStatus=capture_status)
    if capture_status == "collected":
        data = (json.dumps(failed_report()) + "\n").encode()
        (directory / paired.REPORT_NAME).write_bytes(data)
        capture.update(captureError=None,
                       report={"path": paired.REPORT_NAME, "bytes": len(data),
                               "sha256": hashlib.sha256(data).hexdigest(), "status": "failed"})
    else:
        capture.update(captureError="invalid_report", report=None)
    (directory / "capture.json").write_text(json.dumps(capture))
    return directory


def args_for(root, item=None, jobs=None):
    artifacts = root / "artifacts.json"
    artifacts.write_text(json.dumps({"artifacts": [item or artifact()]}))
    job_path = root / "jobs.json"
    job_path.write_text(json.dumps({"jobs": jobs if jobs is not None else [legacy.job()]}))
    return SimpleNamespace(repository=legacy.SOURCE["repository"], artifacts=artifacts,
                           jobs=job_path, incoming=root / "incoming")


class IdlePairArchiveTests(unittest.TestCase):
    def test_fixed_scope_is_explicit_and_selects_only_the_new_case(self):
        self.assertEqual(android.IDLE_PAIR_SCOPE, publisher.android_execution_scope(source_run()))
        self.assertEqual("dev.agentos.app.IdlePairedPerformanceInstrumentedTest#collectAlternatingServiceOffAndIdlePairs",
                         android.test_selection(android.IDLE_PAIR_SCOPE))
        self.assertEqual("full", publisher.android_execution_scope(legacy.source_run()))
        with self.assertRaises(ValueError):
            android.verify_capture_scope({"executionScope": "full", "testSelection": "full-suite"}, android.IDLE_PAIR_SCOPE)
        with self.assertRaises(ValueError):
            publisher.android_execution_scope(dict(source_run(), display_title=publisher.IDLE_PAIR_TITLE + " extra"))

    def test_shell_preserves_exit_and_collects_only_the_requested_paired_scope(self):
        helper = evidence_tests.EvidenceTests()
        helper.check_test_exit("30", 30, scope=android.IDLE_PAIR_SCOPE)
        helper.check_test_exit("37.0", 37, scope=android.IDLE_PAIR_SCOPE)

    def test_missing_reports_and_mixed_test_outcomes_keep_both_denominators(self):
        manifest, readme = evidence_tests.EvidenceTests().diagnostic_archive(
            {"30": "passed", "31": "failed", "32": "skipped"}, scope=android.IDLE_PAIR_SCOPE)
        self.assertEqual({"passed": 1, "failed": 1, "unverified": 6}, manifest["diagnosticCaseCounts"])
        self.assertFalse(manifest["fullSuiteAcceptance"])
        diagnostics = manifest["idlePairedPerformance"]
        self.assertEqual("idle-paired-performance-attachments", diagnostics["kind"])
        self.assertEqual(8, len(diagnostics["apis"]))
        self.assertTrue(all(item["captureStatus"] == "artifact_unavailable" for item in diagnostics["apis"]))
        self.assertFalse(diagnostics["budgetAcceptance"])
        self.assertIn("48 planned pairs and 96 windows", readme)
        self.assertIn("not counted as passed", readme)

    def test_old_scope_does_not_acquire_paired_attachments(self):
        manifest, _ = evidence_tests.EvidenceTests().diagnostic_archive({}, scope=android.ROTATION_SCOPE)
        self.assertNotIn("idlePairedPerformance", manifest)
        self.assertEqual([], publisher.idle_pair_artifacts({"artifacts": [artifact()]}, legacy.source_run()))
        self.assertEqual([], publisher.performance_artifacts({"artifacts": [artifact()]}, source_run()))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = args_for(root)
            with self.assertRaises(ValueError):
                publisher.archive_idle_pairs(args, legacy.source_run(), [], root / "output", {"bytes": 0, "files": 0})

    def test_wrong_test_or_scope_cannot_certify_the_pair_diagnostic(self):
        helper = evidence_tests.EvidenceTests()
        for scope in ("full", android.ROTATION_SCOPE):
            with self.subTest(scope=scope), self.assertRaises(subprocess.CalledProcessError):
                helper.diagnostic_archive({"30": "passed"}, scope=android.IDLE_PAIR_SCOPE, artifact_scope=scope)
        with self.assertRaises(subprocess.CalledProcessError):
            helper.diagnostic_archive({"30": "passed"}, scope=android.IDLE_PAIR_SCOPE,
                                      manifest_selection=android.ROTATION_TEST)

    def test_artifact_requires_current_run_attempt_origin_and_bounded_size(self):
        item = artifact()
        self.assertEqual([item], publisher.idle_pair_artifacts({"artifacts": [item]}, source_run()))
        for field, value in (("id", 999), ("head_sha", "b" * 40), ("head_branch", "other"),
                             ("repository_id", 2), ("head_repository_id", 2)):
            invalid = copy.deepcopy(item)
            invalid["workflow_run"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                publisher.idle_pair_artifacts({"artifacts": [invalid]}, source_run())
        for change in ({"id": True}, {"size_in_bytes": paired.MAX_ARTIFACT_BYTES + 1}, {"expired": "false"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                publisher.idle_pair_artifacts({"artifacts": [dict(item, **change)]}, source_run())
        with self.assertRaises(ValueError):
            publisher.idle_pair_artifacts({"artifacts": [item, item]}, source_run())
        self.assertEqual([], publisher.idle_pair_artifacts(
            {"artifacts": [dict(item, name="idle-pair-reports-api-37.0-attempt-1")]}, source_run()))

    def test_failed_and_invalid_reports_are_archived_without_inventing_success(self):
        for status in ("collected", "invalid"):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory = write_artifact(root, status)
                args = args_for(root)
                budget = {"bytes": 0, "files": 0}
                result = publisher.archive_idle_pairs(args, source_run(), [dict(legacy.job(), conclusion="failure")],
                                                      root / "output", budget)
                self.assertEqual(8, len(result["apis"]))
                self.assertTrue(all(item["captureStatus"] == "artifact_unavailable" for item in result["apis"][:-1]))
                lane = result["apis"][-1]
                self.assertEqual(status, lane["captureStatus"])
                self.assertEqual("failure", lane["jobConclusion"])
                self.assertEqual(1, lane["gradleExitCode"])
                self.assertEqual("failed" if status == "collected" else None, lane["diagnosticStatus"])
                self.assertEqual(2 if status == "collected" else 1, budget["files"])
                for record in lane["files"]:
                    original = (directory / record["path"]).read_bytes()
                    archived = (root / "output/idle-paired-performance/api-37.0" / record["path"]).read_bytes()
                    self.assertEqual(original, archived)
                    self.assertEqual(hashlib.sha256(archived).hexdigest(), record["sha256"])

    def test_archive_rejects_hash_source_job_time_structure_and_symlink_tampering(self):
        for case in ("hash", "source", "time", "job_attempt", "extra", "symlink", "legacy_kind"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory = write_artifact(root)
                item, job = artifact(), legacy.job()
                if case == "hash":
                    with (directory / paired.REPORT_NAME).open("a") as output:
                        output.write(" ")
                elif case == "source":
                    capture = json.loads((directory / "capture.json").read_text())
                    capture["sourceRun"]["attempt"] = 1
                    (directory / "capture.json").write_text(json.dumps(capture))
                elif case == "time":
                    item["created_at"] = "2026-10-25T00:01:00Z"
                elif case == "job_attempt":
                    job["run_attempt"] = 1
                elif case == "extra":
                    (directory / "extra.json").write_text("{}")
                elif case == "symlink":
                    (directory / paired.REPORT_NAME).unlink()
                    (directory / paired.REPORT_NAME).symlink_to(directory / "capture.json")
                else:
                    value = failed_report()
                    value["kind"] = "agent-performance-diagnostics"
                    data = json.dumps(value).encode()
                    (directory / paired.REPORT_NAME).write_bytes(data)
                    capture = json.loads((directory / "capture.json").read_text())
                    capture["report"].update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                    (directory / "capture.json").write_text(json.dumps(capture))
                args = args_for(root, item=item)
                with self.assertRaises(ValueError):
                    publisher.archive_idle_pairs(args, source_run(), [job], root / "output", {"bytes": 0, "files": 0})

    def test_end_to_end_manifest_counts_raw_payload_and_preserves_failed_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_artifact(root)
            args = args_for(root, jobs=[dict(legacy.job(), conclusion="failure")])
            previous = Path.cwd()
            try:
                os.chdir(root)
                with contextlib.redirect_stdout(io.StringIO()):
                    publisher.archive(args, source_run(), 123, 2)
            finally:
                os.chdir(previous)
            output = root / "screenshots/123-2"
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertEqual("failure", manifest["sourceRun"]["conclusion"])
            self.assertEqual({"passed": 0, "failed": 0, "unverified": 8}, manifest["diagnosticCaseCounts"])
            self.assertEqual(2, manifest["fileCount"])
            self.assertEqual("failed", manifest["idlePairedPerformance"]["apis"][-1]["diagnosticStatus"])
            self.assertEqual(0, manifest["apis"][-1]["screenshotCount"])
            self.assertIn("idle-paired-performance/api-37.0/idle-paired-diagnostics.json", (output / "README.md").read_text())

    def test_verify_outputs_only_the_exact_verified_paired_artifact_ids(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = args_for(root)
            run = root / "run.json"
            run.write_text(json.dumps(source_run()))
            output = root / "github-output"
            subprocess.run(["python3", str(evidence_tests.SCRIPTS / "publish_test_evidence.py"), "verify",
                            "--run", str(run), "--repository", legacy.SOURCE["repository"],
                            "--artifacts", str(args.artifacts), "--github-output", str(output)],
                           check=True, capture_output=True, text=True)
            values = dict(line.split("=", 1) for line in output.read_text().splitlines())
            self.assertEqual("456", values["idle_pair_artifact_ids"])
            self.assertEqual("", values["performance_artifact_ids"])
            self.assertEqual("false", values["has_artifacts"])


if __name__ == "__main__":
    unittest.main()
