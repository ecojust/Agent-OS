"""Busy-stop scope, immutable evidence provenance and shared archive boundaries."""

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import collect_android_evidence as android
import collect_busy_stop_evidence as busy_stop
import publish_test_evidence as publisher
import test_evidence_scripts as evidence_tests
import test_performance_evidence as legacy
import test_busy_stop_evidence as fixtures


def source_run(profile="formal"):
    title = publisher.BUSY_STOP_TITLE if profile == "formal" else publisher.BUSY_STOP_PILOT_TITLE
    return dict(legacy.source_run(), event="workflow_dispatch", display_title=title, conclusion="failure")


def artifact(label="37.0"):
    return dict(legacy.artifact(), name=f"busy-stop-reports-api-{label}-attempt-2")


def args_for(root, item=None):
    artifacts = root / "artifacts.json"
    artifacts.write_text(json.dumps({"artifacts": [item or artifact()]}))
    return SimpleNamespace(repository=legacy.SOURCE["repository"], artifacts=artifacts,
                           incoming=root / "incoming")


def unavailable_artifact(root, profile="pilot", label="37.0"):
    source = root / "incoming" / artifact(label)["name"]
    source.mkdir(parents=True)
    capture = busy_stop.empty_capture(label, legacy.SOURCE, profile)
    capture.update(actualSdkInt=int(label.split(".")[0]), gradleExitCode=124, captureStatus="unavailable",
                   captureError="committed_report_unavailable")
    (source / "capture.json").write_text(json.dumps(capture) + "\n")
    return source


class BusyStopArchiveTests(unittest.TestCase):
    def test_manual_scope_is_exact_and_cannot_relabel_pilot_or_select_another_case(self):
        self.assertEqual("dev.agentos.app.BusyStopInstrumentedTest#collectBusyStopDiagnostics", android.BUSY_STOP_TEST)
        for profile, scope in (("pilot", publisher.BUSY_STOP_PILOT_SCOPE),
                               ("formal", publisher.BUSY_STOP_SCOPE)):
            run = source_run(profile)
            self.assertEqual(scope, publisher.android_execution_scope(run))
            self.assertEqual(android.BUSY_STOP_TEST, android.test_selection(scope))
            self.assertEqual("full", publisher.android_execution_scope(dict(run, event="push")))
            with self.assertRaises(ValueError):
                publisher.android_execution_scope(dict(run, display_title=run["display_title"] + " extra"))
        helper = evidence_tests.EvidenceTests()
        with self.assertRaises(subprocess.CalledProcessError):
            helper.diagnostic_archive({"30": "passed"}, scope=publisher.BUSY_STOP_SCOPE,
                                      artifact_scope=publisher.BUSY_STOP_PILOT_SCOPE)
        with self.assertRaises(subprocess.CalledProcessError):
            helper.diagnostic_archive({"30": "passed"}, scope=publisher.BUSY_STOP_SCOPE,
                                      manifest_selection=android.EXPERIENCE_TEST)

    def test_runner_keeps_failed_exit_and_fixed_profile_on_first_and_last_api(self):
        helper = evidence_tests.EvidenceTests()
        for scope in publisher.BUSY_STOP_SCOPES:
            for label in ("30", "37.0"):
                with self.subTest(scope=scope, label=label):
                    helper.check_test_exit(label, int(label.split(".")[0]), scope=scope)

    def test_numeric_artifact_ids_bind_exact_source_attempt_and_unique_lane(self):
        item = artifact()
        for profile in ("pilot", "formal"):
            self.assertEqual([item], publisher.busy_stop_artifacts({"artifacts": [item]}, source_run(profile)))
        for field, value in (("id", 124), ("head_sha", "b" * 40), ("head_branch", "other"),
                             ("repository_id", 2), ("head_repository_id", 2)):
            invalid = copy.deepcopy(item)
            invalid["workflow_run"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                publisher.busy_stop_artifacts({"artifacts": [invalid]}, source_run())
        for change in ({"id": True}, {"size_in_bytes": 0}, {"expired": "false"},
                       {"size_in_bytes": publisher.BUSY_STOP_MAX_LANE_BYTES + 1}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                publisher.busy_stop_artifacts({"artifacts": [dict(item, **change)]}, source_run())
        for other in (item, dict(item, name="busy-stop-reports-api-30-attempt-2")):
            with self.assertRaises(ValueError):
                publisher.busy_stop_artifacts({"artifacts": [item, other]}, source_run())
        for name in ("busy-stop-reports-api-37-attempt-2", "busy-stop-reports-api-38-attempt-2",
                     "busy-stop-reports-api-37.0-attempt-1"):
            self.assertEqual([], publisher.busy_stop_artifacts({"artifacts": [dict(item, name=name)]}, source_run()))
        self.assertEqual([], publisher.busy_stop_artifacts({"artifacts": [dict(item, expired=True)]}, source_run()))

    def test_unrelated_scopes_do_not_acquire_busy_stop_requirements(self):
        for run in (legacy.source_run(), dict(source_run(), display_title=publisher.APP_EXPERIENCE_TITLE),
                    dict(source_run(), path=publisher.PINNED_WORKFLOW)):
            self.assertEqual([], publisher.busy_stop_artifacts({"artifacts": [artifact()]}, run))
        manifest, _ = evidence_tests.EvidenceTests().diagnostic_archive({}, scope=android.ROTATION_SCOPE)
        self.assertNotIn("busyStop", manifest)

    def test_missing_reports_keep_fixed_denominator_and_original_case_results(self):
        for profile, scope in (("pilot", publisher.BUSY_STOP_PILOT_SCOPE),
                               ("formal", publisher.BUSY_STOP_SCOPE)):
            manifest, readme = evidence_tests.EvidenceTests().diagnostic_archive(
                {"30": "passed", "31": "failed", "32": "skipped"}, scope=scope)
            self.assertEqual({"passed": 1, "failed": 1, "unverified": 6}, manifest["diagnosticCaseCounts"])
            self.assertFalse(manifest["fullSuiteAcceptance"])
            section = manifest["busyStop"]
            self.assertEqual("busy-stop-attachments", section["kind"])
            self.assertEqual(profile, section["profile"])
            self.assertEqual(publisher.EXPECTED_MATRIX, [row["matrixLabel"] for row in section["apis"]])
            self.assertTrue(all(row["captureStatus"] == "artifact_unavailable" for row in section["apis"]))
            self.assertTrue(all(row["diagnosticStatus"] is None for row in section["apis"]))
            self.assertFalse(section["budgetAcceptance"])
            self.assertIn("Pilot data does not count toward the formal denominator", readme)
            self.assertIn("overlay removal is not a complete-frame measurement", readme)

    def test_unavailable_capture_is_archived_verbatim_without_executing_artifact_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = unavailable_artifact(root)
            original = (source / "capture.json").read_bytes()
            with patch("subprocess.run", side_effect=AssertionError("archive executed downloaded data")):
                result = publisher.archive_busy_stop(args_for(root), source_run("pilot"), [legacy.job()],
                                                     root / "output", {"bytes": 0, "files": 0})
            row = result["apis"][-1]
            self.assertEqual(("unavailable", "unavailable", None),
                             (row["captureStatus"], row["diagnosticStatus"], row["reportStatus"]))
            self.assertEqual(124, row["gradleExitCode"])
            copied = root / "output/busy-stop/api-37.0/capture.json"
            self.assertEqual(original, copied.read_bytes())
            self.assertEqual({"path": "capture.json", "bytes": len(original),
                              "sha256": hashlib.sha256(original).hexdigest()}, row["files"][0])

    def test_complete_pilot_and_formal_bundles_archive_every_original_shard(self):
        for profile, version in ((p, v) for p in ("pilot", "formal") for v in (1, 2)):
            with self.subTest(profile=profile, schemaVersion=version), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = root / "incoming" / artifact()["name"]
                fixtures.write_bundle(source, profile=profile, schema_version=version)
                originals = {path.name: path.read_bytes() for path in source.iterdir()}
                budget = {"bytes": 123, "files": 2}
                with patch("subprocess.run", side_effect=AssertionError("executed downloaded artifact")):
                    result = publisher.archive_busy_stop(args_for(root), source_run(profile), [legacy.job()], root / "out", budget)
                row = result["apis"][-1]
                self.assertEqual(("collected", "passed", "passed"),
                                 (row["captureStatus"], row["diagnosticStatus"], row["reportStatus"]))
                self.assertEqual(busy_stop.trial_count(profile) + 2, len(row["files"]))
                for ref in row["files"]:
                    self.assertEqual(originals[ref["path"]], (root / "out/busy-stop/api-37.0" / ref["path"]).read_bytes())
                self.assertEqual({"bytes": 123 + sum(map(len, originals.values())), "files": 2 + len(originals)}, budget)

    def test_mixed_root_versions_across_lanes_are_rejected_before_any_copy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = args_for(root)
            artifacts, jobs = [], []
            for index, (label, version) in enumerate((("30", 1), ("37.0", 2))):
                item = dict(artifact(label), id=456 + index)
                artifacts.append(item)
                jobs.append(dict(legacy.job(), name=f"emulator-tests ({label})"))
                fixtures.write_bundle(args.incoming / item["name"], api=int(label.split(".")[0]), schema_version=version)
            args.artifacts.write_text(json.dumps({"artifacts": artifacts}))
            budget = {"bytes": 123, "files": 1}
            with self.assertRaisesRegex(ValueError, "cannot mix report schema versions"):
                publisher.archive_busy_stop(args, source_run("pilot"), jobs, root / "out", budget)
            self.assertFalse((root / "out").exists())
            self.assertEqual({"bytes": 123, "files": 1}, budget)

    def test_v2_root_cannot_rehash_a_v1_trial_as_current_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "incoming" / artifact()["name"]
            trials = [fixtures.valid_trial(i, schema_version=2) for i in range(4)]
            trials[0] = fixtures.valid_trial(0, schema_version=1)
            fixtures.write_bundle(source, trials=trials, schema_version=2)
            with self.assertRaises(ValueError):
                publisher.archive_busy_stop(args_for(root), source_run("pilot"), [legacy.job()], root / "out", {"bytes": 0, "files": 0})
            self.assertFalse((root / "out").exists())

    def test_failed_incomplete_and_nonzero_exit_keep_separate_original_statuses(self):
        for status, exit_code in (("failed", 1), ("incomplete", 124), ("passed", 23)):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = root / "incoming" / artifact()["name"]
                trials = None if status == "passed" else [fixtures.valid_trial(0)]
                if status == "failed": trials[-1].update(status="failed", phase="quiet", failure="AssertionError")
                capture, report = fixtures.write_bundle(source, trials=trials, status=status)
                capture.update(gradleExitCode=exit_code, diagnosticStatus=busy_stop.diagnostic_status(report, exit_code))
                (source / "capture.json").write_bytes(fixtures.encoded(capture))
                result = publisher.archive_busy_stop(args_for(root), source_run("pilot"),
                    [dict(legacy.job(), conclusion="failure")], root / "out", {"bytes": 0, "files": 0})
                row = result["apis"][-1]
                self.assertEqual(status, row["reportStatus"])
                self.assertEqual("incomplete" if status == "incomplete" else "failed", row["diagnosticStatus"])
                self.assertEqual(exit_code, row["gradleExitCode"])
                self.assertEqual("failure", row["jobConclusion"])
                self.assertEqual(6 if status == "passed" else 3, len(row["files"]))

    def test_rehashed_semantically_invalid_trial_cannot_enter_the_archive(self):
        for mutation in (lambda trial: trial["probe"].update(overflow=1),
                         lambda trial: trial["input"].update(touchUpCount=0),
                         lambda trial: trial["beforeRelease"].update(inFlight=False)):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = root / "incoming" / artifact()["name"]
                trials = [fixtures.valid_trial(i) for i in range(4)]
                mutation(trials[0])
                fixtures.write_bundle(source, trials=trials)
                with self.assertRaises(ValueError):
                    publisher.archive_busy_stop(args_for(root), source_run("pilot"), [legacy.job()], root / "out", {"bytes": 0, "files": 0})
                self.assertFalse((root / "out").exists())

    def test_job_and_upload_must_match_the_original_source_attempt(self):
        changes = ({"run_id": 999}, {"run_attempt": 1}, {"head_sha": "b" * 40},
                   {"status": "in_progress"}, {"started_at": "2026-10-26T00:01:01Z"},
                   {"completed_at": "2026-10-26T00:00:59Z"})
        for change in changes:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                unavailable_artifact(root)
                with self.assertRaises(ValueError):
                    publisher.archive_busy_stop(args_for(root), source_run("pilot"), [dict(legacy.job(), **change)],
                                                root / "output", {"bytes": 0, "files": 0})
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            unavailable_artifact(root)
            with self.assertRaises(ValueError):
                publisher.archive_busy_stop(args_for(root), source_run("pilot"), [legacy.job(), legacy.job()],
                                            root / "output", {"bytes": 0, "files": 0})

    def test_capture_profile_source_or_outcome_cannot_be_relabelled(self):
        mutations = (lambda value: value.update(profile="formal", executionScope=publisher.BUSY_STOP_SCOPE),
                     lambda value: value["sourceRun"].update(commit="b" * 40),
                     lambda value: value.update(diagnosticStatus="passed"),
                     lambda value: value.update(captureStatus="collected", captureError=None))
        for mutate in mutations:
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = unavailable_artifact(root)
                value = json.loads((source / "capture.json").read_text())
                mutate(value)
                (source / "capture.json").write_text(json.dumps(value))
                with self.assertRaises(ValueError):
                    publisher.archive_busy_stop(args_for(root), source_run("pilot"), [legacy.job()],
                                                root / "output", {"bytes": 0, "files": 0})

    def test_symlinks_directories_programs_or_orphan_shards_are_not_evidence(self):
        for kind in ("symlink", "directory", "program", "orphan", "out_of_profile"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = unavailable_artifact(root)
                if kind == "symlink":
                    (source / "trial-000.json").symlink_to(source / "capture.json")
                elif kind == "directory":
                    (source / "trial-000.json").mkdir()
                else:
                    name = {"program": "run.py", "orphan": "trial-000.json", "out_of_profile": "trial-004.json"}[kind]
                    (source / name).write_text("{}")
                with self.assertRaises(ValueError):
                    publisher.archive_busy_stop(args_for(root), source_run("pilot"), [legacy.job()],
                                                root / "output", {"bytes": 0, "files": 0})

    def test_capture_must_be_bounded_and_its_parent_cannot_be_a_symlink(self):
        for mutation in ("missing", "empty", "oversized", "linked_parent"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                source = unavailable_artifact(root)
                if mutation == "missing":
                    (source / "capture.json").unlink()
                elif mutation == "empty":
                    (source / "capture.json").write_bytes(b"")
                elif mutation == "oversized":
                    (source / "capture.json").write_bytes(b" " * (busy_stop.MAX_CAPTURE_BYTES + 1))
                else:
                    (root / "incoming").rename(root / "real-incoming")
                    (root / "incoming").symlink_to(root / "real-incoming", target_is_directory=True)
                with self.assertRaises(ValueError):
                    publisher.archive_busy_stop(args_for(root), source_run("pilot"), [legacy.job()],
                                                root / "output", {"bytes": 0, "files": 0})
                self.assertFalse((root / "output").exists())

    def test_lane_file_and_shared_budget_limits_are_kept_without_borrowing_experience_caps(self):
        self.assertEqual(64 * 1024 * 1024, publisher.MAX_TOTAL_BYTES)
        self.assertEqual(8 * 1024 * 1024, publisher.BUSY_STOP_MAX_LANE_BYTES)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payloads = {"capture.json": b"{}"}
            budget = {"bytes": publisher.MAX_TOTAL_BYTES - 2, "files": publisher.MAX_FILES - 1}
            publisher.copy_busy_stop_payloads(payloads, root / "at-limit", budget)
            self.assertEqual({"bytes": publisher.MAX_TOTAL_BYTES, "files": publisher.MAX_FILES}, budget)
            for budget in ({"bytes": publisher.MAX_TOTAL_BYTES - 1, "files": 0},
                           {"bytes": 0, "files": publisher.MAX_FILES}):
                with self.assertRaises(ValueError):
                    publisher.copy_busy_stop_payloads(payloads, root / "over-limit", budget)
            for name, limit in (("capture.json", busy_stop.MAX_CAPTURE_BYTES),
                                (busy_stop.REPORT_NAME, busy_stop.MAX_REPORT_BYTES),
                                ("trial-199.json", busy_stop.MAX_TRIAL_BYTES)):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    publisher.copy_busy_stop_payloads({name: b"x" * (limit + 1)}, root / "over-file", {"bytes": 0, "files": 0})
            for name in ("../outside.json", "trace.pftrace.gz", "trial-200.json"):
                with self.assertRaises(ValueError):
                    publisher.copy_busy_stop_payloads({name: b"{}"}, root / "bad-name", {"bytes": 0, "files": 0})
            with patch.object(publisher, "BUSY_STOP_MAX_LANE_BYTES", 1), self.assertRaises(ValueError):
                publisher.copy_busy_stop_payloads(payloads, root / "over-lane", {"bytes": 0, "files": 0})

    def test_all_lanes_are_preflighted_together_before_consuming_the_shared_budget(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = args_for(root)
            artifacts, jobs, expected_bytes = [], [], 0
            for index, label in enumerate(publisher.EXPECTED_MATRIX):
                source = unavailable_artifact(root, label=label)
                artifacts.append(dict(artifact(label), id=456 + index))
                jobs.append(dict(legacy.job(), name=f"emulator-tests ({label})"))
                expected_bytes += (source / "capture.json").stat().st_size
            args.artifacts.write_text(json.dumps({"artifacts": artifacts}))
            budget = {"bytes": publisher.MAX_TOTAL_BYTES - expected_bytes, "files": 7}
            result = publisher.archive_busy_stop(args, source_run("pilot"), jobs, root / "exact", budget)
            self.assertEqual(publisher.EXPECTED_MATRIX, [row["matrixLabel"] for row in result["apis"]])
            self.assertTrue(all(len(row["files"]) == 1 for row in result["apis"]))
            self.assertEqual({"bytes": publisher.MAX_TOTAL_BYTES, "files": 15}, budget)

            for budget in ({"bytes": publisher.MAX_TOTAL_BYTES - expected_bytes + 1, "files": 0},
                           {"bytes": 0, "files": publisher.MAX_FILES - 7}):
                original_budget = budget.copy()
                with self.assertRaises(ValueError):
                    publisher.archive_busy_stop(args, source_run("pilot"), jobs, root / "rejected", budget)
                self.assertEqual(original_budget, budget)
                self.assertFalse((root / "rejected").exists())

            (root / "incoming" / artifact()["name"] / "capture.json").write_text("{}")
            budget = {"bytes": 0, "files": 0}
            with self.assertRaises(ValueError):
                publisher.archive_busy_stop(args, source_run("pilot"), jobs, root / "invalid-last", budget)
            self.assertEqual({"bytes": 0, "files": 0}, budget)
            self.assertFalse((root / "invalid-last").exists())

    def test_verify_outputs_only_verified_busy_stop_artifact_ids(self):
        for profile in ("pilot", "formal"):
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                args = args_for(root)
                run = root / "run.json"
                run.write_text(json.dumps(source_run(profile)))
                output = root / "github-output"
                subprocess.run(["python3", str(evidence_tests.SCRIPTS / "publish_test_evidence.py"), "verify",
                                "--run", str(run), "--repository", legacy.SOURCE["repository"],
                                "--artifacts", str(args.artifacts), "--github-output", str(output)],
                               check=True, capture_output=True, text=True)
                values = dict(line.split("=", 1) for line in output.read_text().splitlines())
                self.assertEqual("456", values["busy_stop_artifact_ids"])
                for field in ("app_experience_artifact_ids", "active_pair_artifact_ids", "idle_pair_artifact_ids",
                              "performance_artifact_ids", "stress_artifact_ids"):
                    self.assertEqual("", values[field])


if __name__ == "__main__":
    unittest.main()
