"""Preserve segmented stress provenance, failed prefixes and the fixed denominator."""

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
import collect_stress_evidence as stress
import publish_test_evidence as publisher
import test_evidence_scripts as evidence_tests
import test_idle_pair_archive as paired_tests
import test_performance_evidence as legacy
import test_stress_evidence as fixtures


def source_run():
    return dict(legacy.source_run(), event="workflow_dispatch", display_title=publisher.STRESS_TITLE,
                conclusion="failure")


def artifact():
    return dict(legacy.artifact(), name="stress-reports-api-37.0-attempt-2")


def args_for(root, item=None, jobs=None):
    artifacts = root / "artifacts.json"
    artifacts.write_text(json.dumps({"artifacts": [item or artifact()]}))
    job_path = root / "jobs.json"
    job_path.write_text(json.dumps({"jobs": jobs if jobs is not None else [legacy.job()]}))
    return SimpleNamespace(repository=legacy.SOURCE["repository"], artifacts=artifacts,
                           jobs=job_path, incoming=root / "incoming")


def bind_capture(directory, status):
    data = (directory / stress.REPORT_NAME).read_bytes()
    capture = stress.empty_capture("37.0", legacy.SOURCE)
    capture.update(actualSdkInt=37, gradleExitCode=0 if status == "passed" else 1,
                   captureStatus="collected", captureError=None,
                   report={"path": stress.REPORT_NAME, "bytes": len(data),
                           "sha256": hashlib.sha256(data).hexdigest(), "status": status})
    (directory / "capture.json").write_text(json.dumps(capture))
    return capture


def write_artifact(root, status="failed", capture_status="collected", failed_segment=False):
    directory = root / "incoming" / artifact()["name"]
    directory.mkdir(parents=True)
    if capture_status == "collected":
        segments = None if status == "passed" else [fixtures.segment(0)]
        if failed_segment:
            segments[-1].update(status="failed", samples=[], active=None, stop=None, recovery=None)
        fixtures.write_bundle(directory, api=37, segments=segments, status=status)
        bind_capture(directory, status)
    else:
        capture = stress.empty_capture("37.0", legacy.SOURCE)
        capture.update(actualSdkInt=37, gradleExitCode=1, captureStatus=capture_status,
                       captureError="invalid_report", report=None)
        (directory / "capture.json").write_text(json.dumps(capture))
    return directory


class StressArchiveTests(unittest.TestCase):
    def test_scope_requires_the_fixed_title_and_exact_selected_case(self):
        self.assertEqual(android.STRESS_SCOPE, publisher.android_execution_scope(source_run()))
        self.assertEqual("dev.agentos.app.SegmentedStressInstrumentedTest#collectThirtyMinuteTaskStopAndServiceCycles",
                         android.test_selection(android.STRESS_SCOPE))
        self.assertEqual("full", publisher.android_execution_scope(legacy.source_run()))
        with self.assertRaises(ValueError):
            publisher.android_execution_scope(dict(source_run(), display_title=publisher.STRESS_TITLE + " extra"))
        with self.assertRaises(ValueError):
            android.verify_capture_scope({"executionScope": "full", "testSelection": "full-suite"}, android.STRESS_SCOPE)

    def test_runner_preserves_failure_and_collects_only_the_requested_stress_scope(self):
        helper = evidence_tests.EvidenceTests()
        helper.check_test_exit("30", 30, scope=android.STRESS_SCOPE)
        helper.check_test_exit("37.0", 37, scope=android.STRESS_SCOPE)

    def test_missing_numeric_reports_do_not_remove_any_of_eight_api_lanes(self):
        manifest, readme = evidence_tests.EvidenceTests().diagnostic_archive(
            {"30": "passed", "31": "failed", "32": "skipped"}, scope=android.STRESS_SCOPE)
        self.assertEqual({"passed": 1, "failed": 1, "unverified": 6}, manifest["diagnosticCaseCounts"])
        self.assertFalse(manifest["fullSuiteAcceptance"])
        section = manifest["segmentedStress"]
        self.assertEqual("segmented-stress-attachments", section["kind"])
        self.assertEqual(8, len(section["apis"]))
        self.assertTrue(all(row["captureStatus"] == "artifact_unavailable" for row in section["apis"]))
        self.assertFalse(section["budgetAcceptance"])
        self.assertIn("240 planned segments and 480 planned tasks", readme)
        self.assertIn("not counted as passed", readme)

    def test_old_scopes_do_not_acquire_segmented_evidence_requirements(self):
        manifest, _ = evidence_tests.EvidenceTests().diagnostic_archive({}, scope=android.ROTATION_SCOPE)
        self.assertNotIn("segmentedStress", manifest)
        self.assertEqual([], publisher.stress_artifacts({"artifacts": [artifact()]}, legacy.source_run()))
        self.assertEqual([], publisher.stress_artifacts({"artifacts": [artifact()]}, paired_tests.source_run()))
        self.assertEqual([], publisher.performance_artifacts({"artifacts": [artifact()]}, source_run()))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError):
                publisher.archive_stress(args_for(root), legacy.source_run(), [], root / "output", {"bytes": 0, "files": 0})

    def test_wrong_scope_or_test_identity_cannot_pass_selected_stress_case(self):
        helper = evidence_tests.EvidenceTests()
        for scope in ("full", android.IDLE_PAIR_SCOPE):
            with self.subTest(scope=scope), self.assertRaises(subprocess.CalledProcessError):
                helper.diagnostic_archive({"30": "passed"}, scope=android.STRESS_SCOPE, artifact_scope=scope)
        with self.assertRaises(subprocess.CalledProcessError):
            helper.diagnostic_archive({"30": "passed"}, scope=android.STRESS_SCOPE,
                                      manifest_selection=android.IDLE_PAIR_TEST)

    def test_artifact_requires_current_attempt_origin_and_bounded_size(self):
        item = artifact()
        self.assertEqual([item], publisher.stress_artifacts({"artifacts": [item]}, source_run()))
        for field, value in (("id", 999), ("head_sha", "b" * 40), ("head_branch", "other"),
                             ("repository_id", 2), ("head_repository_id", 2)):
            invalid = copy.deepcopy(item)
            invalid["workflow_run"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                publisher.stress_artifacts({"artifacts": [invalid]}, source_run())
        for change in ({"id": True}, {"size_in_bytes": stress.MAX_ARTIFACT_BYTES + 1}, {"expired": "false"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                publisher.stress_artifacts({"artifacts": [dict(item, **change)]}, source_run())
        with self.assertRaises(ValueError):
            publisher.stress_artifacts({"artifacts": [item, item]}, source_run())
        self.assertEqual([], publisher.stress_artifacts(
            {"artifacts": [dict(item, name="stress-reports-api-37.0-attempt-1")]}, source_run()))

    def test_passed_failed_and_incomplete_committed_segments_keep_original_results(self):
        for status in ("passed", "failed", "incomplete"):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                original = write_artifact(root, status, failed_segment=status == "failed")
                budget = {"bytes": 0, "files": 0}
                result = publisher.archive_stress(args_for(root), source_run(),
                                                  [dict(legacy.job(), conclusion="failure")], root / "output", budget)
                self.assertEqual(8, len(result["apis"]))
                row = result["apis"][-1]
                self.assertEqual(status, row["diagnosticStatus"])
                self.assertEqual("failure", row["jobConclusion"])
                self.assertEqual(0 if status == "passed" else 1, row["gradleExitCode"])
                expected_files = 32 if status == "passed" else 3
                self.assertEqual(expected_files, budget["files"])
                self.assertEqual(expected_files, len(row["files"]))
                for reference in row["files"]:
                    data = (root / "output/segmented-stress/api-37.0" / reference["path"]).read_bytes()
                    self.assertEqual((original / reference["path"]).read_bytes(), data)
                    self.assertEqual(len(data), reference["bytes"])
                    self.assertEqual(hashlib.sha256(data).hexdigest(), reference["sha256"])

    def test_invalid_capture_is_preserved_without_unverified_payload(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = write_artifact(root, capture_status="invalid")
            result = publisher.archive_stress(args_for(root), source_run(), [legacy.job()],
                                              root / "output", {"bytes": 0, "files": 0})
            self.assertEqual("invalid", result["apis"][-1]["captureStatus"])
            self.assertIsNone(result["apis"][-1]["diagnosticStatus"])
            self.assertEqual(["capture.json"], [ref["path"] for ref in result["apis"][-1]["files"]])
            (directory / "segment-000.json").write_text("{}")
            with self.assertRaises(ValueError):
                publisher.archive_stress(args_for(root), source_run(), [legacy.job()],
                                          root / "output-2", {"bytes": 0, "files": 0})

    def test_stress_segment_limit_preserves_large_failure_payload_without_raising_old_limits(self):
        for size in (2 * 1024 * 1024 + 1, stress.MAX_SEGMENT_BYTES + 1):
            with self.subTest(size=size), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory = write_artifact(root, "failed", failed_segment=True)
                segment_path = directory / "segment-000.json"
                original = segment_path.read_bytes()
                data = original + b" " * (size - len(original))
                segment_path.write_bytes(data)
                report_path = directory / stress.REPORT_NAME
                report = json.loads(report_path.read_text())
                report["segments"][0].update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                report_path.write_text(json.dumps(report))
                bind_capture(directory, "failed")
                if size > stress.MAX_SEGMENT_BYTES:
                    with self.assertRaises(ValueError):
                        publisher.archive_stress(args_for(root), source_run(), [legacy.job()],
                                                  root / "output", {"bytes": 0, "files": 0})
                else:
                    result = publisher.archive_stress(args_for(root), source_run(), [legacy.job()],
                                                      root / "output", {"bytes": 0, "files": 0})
                    self.assertEqual("failed", result["apis"][-1]["diagnosticStatus"])
                    archived = root / "output/segmented-stress/api-37.0/segment-000.json"
                    self.assertEqual(data, archived.read_bytes())
                    with self.assertRaises(ValueError):
                        publisher.copy_files(directory, root / "old-copy", {"bytes": 0, "files": 0})

    def test_archive_rejects_root_segment_hash_missing_orphan_and_path_tampering(self):
        for case in ("root_hash", "segment_hash", "missing", "orphan", "symlink", "directory", "wrong_index", "false_pass"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory = write_artifact(root, "incomplete")
                report_path = directory / stress.REPORT_NAME
                if case == "root_hash":
                    with report_path.open("a") as stream:
                        stream.write(" ")
                elif case == "segment_hash":
                    with (directory / "segment-000.json").open("a") as stream:
                        stream.write(" ")
                elif case == "missing":
                    (directory / "segment-000.json").unlink()
                elif case == "orphan":
                    (directory / "segment-001.json").write_text("{}")
                elif case == "symlink":
                    (directory / "segment-000.json").unlink()
                    (directory / "segment-000.json").symlink_to(directory / "capture.json")
                elif case == "directory":
                    (directory / "extra").mkdir()
                else:
                    report = json.loads(report_path.read_text())
                    if case == "wrong_index":
                        report["segments"][0]["index"] = 1
                    else:
                        report["status"] = "passed"
                    report_path.write_text(json.dumps(report))
                    bind_capture(directory, report["status"])
                with self.assertRaises(ValueError):
                    publisher.archive_stress(args_for(root), source_run(), [legacy.job()],
                                              root / "output", {"bytes": 0, "files": 0})

    def test_archive_rejects_source_job_time_and_global_budget_mismatch(self):
        for case in ("source", "job_attempt", "time", "duplicate_job", "budget"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory = write_artifact(root)
                item, jobs = artifact(), [legacy.job()]
                budget = {"bytes": 0, "files": 0}
                if case == "source":
                    path = directory / "capture.json"
                    capture = json.loads(path.read_text())
                    capture["sourceRun"]["commit"] = "b" * 40
                    path.write_text(json.dumps(capture))
                elif case == "job_attempt":
                    jobs[0]["run_attempt"] = 1
                elif case == "time":
                    item["created_at"] = "2026-10-25T00:01:00Z"
                elif case == "duplicate_job":
                    jobs.append(dict(jobs[0]))
                else:
                    budget["bytes"] = publisher.MAX_TOTAL_BYTES
                with self.assertRaises(ValueError):
                    publisher.archive_stress(args_for(root, item=item), source_run(), jobs, root / "output", budget)

    def test_old_numeric_archive_still_rejects_a_third_segment_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = paired_tests.write_artifact(root)
            (directory / "segment-000.json").write_text("{}")
            with self.assertRaises(ValueError):
                publisher.archive_idle_pairs(paired_tests.args_for(root), paired_tests.source_run(), [legacy.job()],
                                              root / "output", {"bytes": 0, "files": 0})

    def test_end_to_end_manifest_keeps_interrupted_run_and_exact_payload_count(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write_artifact(root, "incomplete")
            args = args_for(root, jobs=[dict(legacy.job(), conclusion="cancelled")])
            previous = Path.cwd()
            try:
                os.chdir(root)
                with contextlib.redirect_stdout(io.StringIO()):
                    publisher.archive(args, dict(source_run(), conclusion="cancelled"), 123, 2)
            finally:
                os.chdir(previous)
            output = root / "screenshots/123-2"
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertEqual("cancelled", manifest["sourceRun"]["conclusion"])
            self.assertEqual({"passed": 0, "failed": 0, "unverified": 8}, manifest["diagnosticCaseCounts"])
            self.assertEqual(3, manifest["fileCount"])
            self.assertEqual("incomplete", manifest["segmentedStress"]["apis"][-1]["diagnosticStatus"])
            self.assertEqual(0, manifest["apis"][-1]["screenshotCount"])
            self.assertIn("segmented-stress/api-37.0/stress-diagnostics.json", (output / "README.md").read_text())

    def test_verify_outputs_only_exact_verified_stress_artifact_ids(self):
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
            self.assertEqual("456", values["stress_artifact_ids"])
            self.assertEqual("", values["idle_pair_artifact_ids"])
            self.assertEqual("", values["performance_artifact_ids"])
            self.assertEqual("false", values["has_artifacts"])


if __name__ == "__main__":
    unittest.main()
