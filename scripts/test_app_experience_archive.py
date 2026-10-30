"""Keep experience archives attributable, bounded and separate from prior scopes."""

import copy
import contextlib
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import publish_test_evidence as publisher
import test_evidence_scripts as evidence_tests
import test_performance_evidence as legacy


def source_run(profile="formal"):
    title = publisher.APP_EXPERIENCE_TITLE if profile == "formal" else publisher.APP_EXPERIENCE_PILOT_TITLE
    return dict(legacy.source_run(), event="workflow_dispatch", display_title=title, conclusion="failure")


def artifact():
    return dict(legacy.artifact(), name="app-experience-reports-api-37.0-attempt-2")


def args_for(root, item=None, jobs=None):
    artifacts = root / "artifacts.json"
    artifacts.write_text(json.dumps({"artifacts": [item or artifact()]}))
    job_path = root / "jobs.json"
    job_path.write_text(json.dumps({"jobs": jobs if jobs is not None else [legacy.job()]}))
    return SimpleNamespace(repository=legacy.SOURCE["repository"], artifacts=artifacts,
                           jobs=job_path, incoming=root / "incoming")


def trace_reference(raw):
    return {"path": "trace.pftrace", "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def write_artifact(root, profile="pilot", status="passed", with_trace=True, schema_version=1):
    import test_collect_app_experience_evidence as fixtures
    directory = root / "incoming" / artifact()["name"]
    options = {} if status == "passed" else {"launches": [fixtures.launch(profile=profile, schema_version=schema_version)], "trials": []}
    value = fixtures.write_bundle(directory, profile=profile, status=status, with_trace=with_trace,
                                  schema_version=schema_version, **options)
    capture = fixtures.collected_capture(directory, value, exit_code=0 if status == "passed" else 124)
    (directory / "capture.json").write_bytes(fixtures.encoded(capture))
    return directory, value


def rebind_capture(directory):
    """Rebind hashes without validating semantics, to test archive revalidation."""
    import collect_app_experience_evidence as experience
    path = directory / "capture.json"
    capture = json.loads(path.read_text())
    capture["payloads"] = [{"path": item.name, "bytes": len(item.read_bytes()),
                            "sha256": hashlib.sha256(item.read_bytes()).hexdigest()}
                           for item in sorted(directory.iterdir()) if item.name != "capture.json"]
    capture["report"] = dict(next(ref for ref in capture["payloads"] if ref["path"] == experience.REPORT_NAME),
                             status=json.loads((directory / experience.REPORT_NAME).read_text())["status"])
    path.write_text(json.dumps(capture))
    return capture


class AppExperienceArchiveTests(unittest.TestCase):
    def test_version_two_reports_archive_with_v3_capture_and_v2_derivation(self):
        import experience_trace
        for profile, file_count in (("pilot", 17), ("formal", 424)):
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory, report = write_artifact(root, profile=profile, schema_version=2)
                trace_path = directory / "trace-capture.json"
                trace = json.loads(trace_path.read_text())
                derived = json.loads((directory / "startup-ttid.json").read_text())
                self.assertEqual((2, 3, 2, 2), (report["schemaVersion"], trace["schemaVersion"],
                                               trace["configVersion"], derived["schemaVersion"]))
                self.assertEqual(experience_trace.trace_config(version=2), trace["config"])
                with patch("subprocess.run", side_effect=AssertionError("archive executes data")):
                    result = publisher.archive_app_experience(args_for(root), source_run(profile), [legacy.job()],
                                                             root / "output", {"bytes": 0, "files": 0})
                row = result["apis"][-1]
                self.assertEqual(("collected", "passed", "passed"), (row["captureStatus"], row["diagnosticStatus"], row["reportStatus"]))
                self.assertEqual(file_count, len(row["files"]))
                for ref in row["files"]:
                    data = (root / "output/app-experience/api-37.0" / ref["path"]).read_bytes()
                    self.assertEqual((directory / ref["path"]).read_bytes(), data)
                    self.assertEqual(len(data), ref["bytes"])
                    self.assertEqual(hashlib.sha256(data).hexdigest(), ref["sha256"])
                # Rebound hashes cannot replace the new configuration with the old one.
                trace["config"] = experience_trace.trace_config(version=1)
                trace_path.write_text(json.dumps(trace))
                rebind_capture(directory)
                with self.assertRaisesRegex(ValueError, "trace_config_mismatch"):
                    publisher.archive_app_experience(args_for(root), source_run(profile), [legacy.job()],
                                                     root / "mixed-versions", {"bytes": 0, "files": 0})

    def test_complete_pilot_and_formal_payloads_are_revalidated_and_copied_verbatim(self):
        for profile, file_count in (("pilot", 17), ("formal", 424)):
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory, _ = write_artifact(root, profile)
                budget = {"bytes": 0, "files": 0}
                with patch("subprocess.run", side_effect=AssertionError("archive executes data")):
                    result = publisher.archive_app_experience(args_for(root), source_run(profile), [legacy.job()], root / "output", budget)
                self.assertEqual(8, len(result["apis"]))
                row = result["apis"][-1]
                self.assertEqual(("collected", "passed", "passed"), (row["captureStatus"], row["diagnosticStatus"], row["reportStatus"]))
                self.assertEqual(file_count, len(row["files"]))
                self.assertEqual(file_count, budget["files"])
                for ref in row["files"]:
                    data = (root / "output/app-experience/api-37.0" / ref["path"]).read_bytes()
                    self.assertEqual((directory / ref["path"]).read_bytes(), data)
                    self.assertEqual(len(data), ref["bytes"])
                    self.assertEqual(hashlib.sha256(data).hexdigest(), ref["sha256"])
                    self.assertEqual(0o644, (root / "output/app-experience/api-37.0" / ref["path"]).stat().st_mode & 0o777)

    def test_failed_incomplete_and_trace_missing_results_keep_raw_status(self):
        for status, with_trace, combined in (("failed", True, "failed"), ("incomplete", True, "incomplete"),
                                             ("passed", False, "failed")):
            with self.subTest(status=status, with_trace=with_trace), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                write_artifact(root, status=status, with_trace=with_trace)
                result = publisher.archive_app_experience(args_for(root), source_run("pilot"),
                         [dict(legacy.job(), conclusion="cancelled")], root / "output", {"bytes": 0, "files": 0})
                row = result["apis"][-1]
                self.assertEqual(status, row["reportStatus"])
                self.assertEqual(combined, row["diagnosticStatus"])
                self.assertEqual("collected" if with_trace else "partial", row["captureStatus"])
                self.assertEqual("cancelled", row["jobConclusion"])

    def test_valid_ttid_failure_cannot_make_instrumented_pass_complete(self):
        import test_collect_app_experience_evidence as fixtures
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory, value = write_artifact(root)
            fixtures.write_trace(directory, value, lambda raw: raw["startups"][0].update(ttidPid=9999))
            capture = fixtures.collected_capture(directory, value)
            (directory / "capture.json").write_bytes(fixtures.encoded(capture))
            result = publisher.archive_app_experience(args_for(root), source_run("pilot"), [legacy.job()], root / "output", {"bytes": 0, "files": 0})
            row = result["apis"][-1]
            self.assertEqual(("collected", "failed", "passed"), (row["captureStatus"], row["diagnosticStatus"], row["reportStatus"]))
            capture["diagnosticStatus"] = "passed"
            (directory / "capture.json").write_bytes(fixtures.encoded(capture))
            with self.assertRaisesRegex(ValueError, "combined_status"):
                publisher.archive_app_experience(args_for(root), source_run("pilot"), [legacy.job()], root / "forged", {"bytes": 0, "files": 0})

    def test_archive_rejects_hash_tampering_and_rebound_ttid_or_resource_forgery(self):
        import collect_app_experience_evidence as experience
        for case in ("root_hash", "trial_hash", "missing", "extra", "ttid", "csv", "model_acceptance"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory, report = write_artifact(root)
                if case in ("root_hash", "trial_hash"):
                    name = experience.REPORT_NAME if case == "root_hash" else "trial-000.json"
                    with (directory / name).open("ab") as stream:
                        stream.write(b" ")
                elif case == "missing":
                    (directory / "launch-000.json").unlink()
                elif case == "extra":
                    (directory / "launch-008.json").write_text("{}")
                elif case == "ttid":
                    path = directory / "startup-ttid.json"
                    value = json.loads(path.read_text())
                    value["launches"][0]["ttidNanos"] += 1
                    path.write_text(json.dumps(value))
                    rebind_capture(directory)
                elif case == "csv":
                    path = directory / "startup-ttid.csv"
                    path.write_bytes(path.read_bytes() + b"extra\n")
                    derived_path = directory / "startup-ttid.json"
                    value = json.loads(derived_path.read_text())
                    value["extraction"].update(bytes=len(path.read_bytes()), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
                    derived_path.write_text(json.dumps(value))
                    rebind_capture(directory)
                else:
                    path = directory / "trial-000.json"
                    value = json.loads(path.read_text())
                    value["modelAcceptedWrites"] = 3
                    data = json.dumps(value).encode(); path.write_bytes(data)
                    report["trials"][0].update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                    (directory / experience.REPORT_NAME).write_text(json.dumps(report))
                    rebind_capture(directory)
                with self.assertRaises((ValueError, FileNotFoundError)):
                    publisher.archive_app_experience(args_for(root), source_run("pilot"), [legacy.job()],
                                                     root / "output", {"bytes": 0, "files": 0})

    def test_runner_preserves_failed_test_and_separates_formal_and_pilot_arguments(self):
        helper = evidence_tests.EvidenceTests()
        for scope in publisher.APP_EXPERIENCE_SCOPES:
            for label in ("30", "37.0"):
                with self.subTest(scope=scope, label=label):
                    helper.check_test_exit(label, int(label.split(".")[0]), scope=scope)

    def test_scopes_are_exact_manual_titles_and_do_not_change_other_runs(self):
        for profile, expected in (("formal", publisher.APP_EXPERIENCE_SCOPE),
                                  ("pilot", publisher.APP_EXPERIENCE_PILOT_SCOPE)):
            run = source_run(profile)
            self.assertEqual(expected, publisher.android_execution_scope(run))
            self.assertEqual("full", publisher.android_execution_scope(dict(run, event="push")))
            with self.assertRaises(ValueError):
                publisher.android_execution_scope(dict(run, display_title=run["display_title"] + " extra"))
        self.assertEqual("full", publisher.android_execution_scope(legacy.source_run()))

    def test_artifacts_keep_source_attempt_size_and_unique_lane_checks(self):
        item = artifact()
        for profile in ("formal", "pilot"):
            self.assertEqual([item], publisher.app_experience_artifacts({"artifacts": [item]}, source_run(profile)))
        for field, value in (("id", 999), ("head_sha", "b" * 40), ("head_branch", "other"),
                             ("repository_id", 2), ("head_repository_id", 2)):
            invalid = copy.deepcopy(item)
            invalid["workflow_run"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                publisher.app_experience_artifacts({"artifacts": [invalid]}, source_run())
        for change in ({"id": True}, {"size_in_bytes": publisher.APP_EXPERIENCE_MAX_LANE_BYTES + 1},
                       {"size_in_bytes": 0}, {"expired": "false"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                publisher.app_experience_artifacts({"artifacts": [dict(item, **change)]}, source_run())
        with self.assertRaises(ValueError):
            publisher.app_experience_artifacts({"artifacts": [item, item]}, source_run())
        with self.assertRaises(ValueError):
            publisher.app_experience_artifacts({"artifacts": [item, dict(item, name="app-experience-reports-api-30-attempt-2")]}, source_run())
        for name in ("app-experience-reports-api-37-attempt-2", "app-experience-reports-api-38-attempt-2",
                     "app-experience-reports-api-37.0-attempt-1"):
            self.assertEqual([], publisher.app_experience_artifacts({"artifacts": [dict(item, name=name)]}, source_run()))

    def test_old_scopes_never_download_experience_artifacts(self):
        for run in (legacy.source_run(), dict(source_run(), display_title=publisher.ACTIVE_PAIR_TITLE),
                    dict(source_run(), path=publisher.PINNED_WORKFLOW)):
            self.assertEqual([], publisher.app_experience_artifacts({"artifacts": [artifact()]}, run))

    def test_missing_reports_retain_all_eight_lanes_and_original_selected_cases(self):
        for scope, profile in ((publisher.APP_EXPERIENCE_SCOPE, "formal"),
                               (publisher.APP_EXPERIENCE_PILOT_SCOPE, "pilot")):
            with self.subTest(scope=scope):
                manifest, readme = evidence_tests.EvidenceTests().diagnostic_archive(
                    {"30": "passed", "31": "failed", "32": "skipped"}, scope=scope)
                self.assertEqual({"passed": 1, "failed": 1, "unverified": 6}, manifest["diagnosticCaseCounts"])
                self.assertFalse(manifest["fullSuiteAcceptance"])
                section = manifest["appExperience"]
                self.assertEqual("app-experience-attachments", section["kind"])
                self.assertEqual(profile, section["profile"])
                self.assertEqual(publisher.EXPECTED_MATRIX, [row["matrixLabel"] for row in section["apis"]])
                self.assertTrue(all(row["captureStatus"] == "artifact_unavailable" for row in section["apis"]))
                self.assertFalse(section["budgetAcceptance"])
                self.assertIn("Pilot data does not count toward the formal denominator", readme)
                self.assertIn("not counted as passed", readme)

    def test_same_selected_class_cannot_relabel_pilot_as_formal(self):
        helper = evidence_tests.EvidenceTests()
        with self.assertRaises(subprocess.CalledProcessError):
            helper.diagnostic_archive({"30": "passed"}, scope=publisher.APP_EXPERIENCE_SCOPE,
                                      artifact_scope=publisher.APP_EXPERIENCE_PILOT_SCOPE)

    def test_unavailable_capture_is_retained_and_no_downloaded_program_is_executed(self):
        import collect_app_experience_evidence as experience
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "incoming" / artifact()["name"]
            directory.mkdir(parents=True)
            capture = experience.empty_capture("37.0", legacy.SOURCE, "formal")
            capture.update(actualSdkInt=37, gradleExitCode=7, captureStatus="unavailable",
                           captureError="device_or_report_unavailable")
            original = json.dumps(capture).encode()
            (directory / "capture.json").write_bytes(original)
            args = args_for(root)
            previous = Path.cwd()
            try:
                os.chdir(root)
                with contextlib.redirect_stdout(io.StringIO()), patch("subprocess.run", side_effect=AssertionError("archive executes data")):
                    publisher.archive(args, dict(source_run(), conclusion="cancelled"), 123, 2)
            finally:
                os.chdir(previous)
            output = root / "screenshots/123-2"
            manifest = json.loads((output / "manifest.json").read_text())
            self.assertEqual("cancelled", manifest["sourceRun"]["conclusion"])
            self.assertEqual({"passed": 0, "failed": 0, "unverified": 8}, manifest["diagnosticCaseCounts"])
            self.assertEqual(1, manifest["fileCount"])
            row = manifest["appExperience"]["apis"][-1]
            self.assertEqual("unavailable", row["diagnosticStatus"])
            self.assertIsNone(row["reportStatus"])
            self.assertEqual(original, (output / "app-experience/api-37.0/capture.json").read_bytes())

    def test_source_job_window_and_unverified_extra_files_are_rejected(self):
        import collect_app_experience_evidence as experience
        for case in ("source", "profile", "job_attempt", "time", "duplicate_job", "orphan", "symlink", "directory"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                directory = root / "incoming" / artifact()["name"]
                directory.mkdir(parents=True)
                capture = experience.empty_capture("37.0", legacy.SOURCE, "pilot" if case == "profile" else "formal")
                capture.update(actualSdkInt=37, gradleExitCode=7, captureStatus="unavailable", captureError="report_unavailable")
                item, jobs = artifact(), [legacy.job()]
                if case == "source":
                    capture["sourceRun"] = dict(capture["sourceRun"], commit="b" * 40)
                elif case == "job_attempt":
                    jobs[0]["run_attempt"] = 1
                elif case == "time":
                    item["created_at"] = "2026-10-25T00:01:00Z"
                elif case == "duplicate_job":
                    jobs.append(dict(jobs[0]))
                elif case == "orphan":
                    (directory / "launch-000.json").write_text("{}")
                elif case == "symlink":
                    (directory / "launch-000.json").symlink_to("capture.json")
                elif case == "directory":
                    (directory / "nested").mkdir()
                (directory / "capture.json").write_text(json.dumps(capture))
                with self.assertRaises(ValueError):
                    publisher.archive_app_experience(args_for(root, item=item), source_run(), jobs,
                                                     root / "output", {"bytes": 0, "files": 0})

    def test_failed_trace_capture_survives_when_device_report_is_absent(self):
        import collect_app_experience_evidence as experience
        import experience_trace
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "incoming" / artifact()["name"]
            directory.mkdir(parents=True)
            trace = dict(schemaVersion=1, kind="agent-os-experience-trace-capture", status="failed",
                         pid=None, stopped=False, config=experience_trace.trace_config(version=1), trace=None,
                         compressed=None, error="perfetto_unavailable", startMode=None, ready=False)
            trace_data = json.dumps(trace).encode()
            (directory / "trace-capture.json").write_bytes(trace_data)
            capture = experience.empty_capture("37.0", legacy.SOURCE, "formal")
            capture.update(actualSdkInt=37, gradleExitCode=7, captureStatus="unavailable",
                           captureError="report_unavailable", payloads=[dict(path="trace-capture.json",
                           bytes=len(trace_data), sha256=hashlib.sha256(trace_data).hexdigest())])
            (directory / "capture.json").write_text(json.dumps(capture))
            with patch("subprocess.run", side_effect=AssertionError("archive executes data")):
                result = publisher.archive_app_experience(args_for(root), source_run(), [legacy.job()],
                                                         root / "output", {"bytes": 0, "files": 0})
            row = result["apis"][-1]
            self.assertEqual("unavailable", row["captureStatus"])
            self.assertEqual("unavailable", row["diagnosticStatus"])
            self.assertEqual(["capture.json", "trace-capture.json"], [ref["path"] for ref in row["files"]])
            self.assertEqual(trace_data, (root / "output/app-experience/api-37.0/trace-capture.json").read_bytes())

    def test_trace_gzip_is_crc_checked_and_bound_to_raw_length_and_digest(self):
        raw = b"synthetic trace bytes for host boundary test" * 3000
        compressed = gzip.compress(raw, mtime=0)
        publisher.verify_app_experience_gzip(compressed, trace_reference(raw))
        for changed in (compressed[:-1], compressed + b"trailing", compressed + gzip.compress(b"other"),
                        compressed[:-8] + b"\x00" * 8, b"not-gzip"):
            with self.subTest(length=len(changed)), self.assertRaises(ValueError):
                publisher.verify_app_experience_gzip(changed, trace_reference(raw))
        for change in ({"bytes": len(raw) - 1}, {"bytes": len(raw) + 1}, {"bytes": True},
                       {"sha256": "b" * 64}, {"path": "../../trace.pftrace"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                publisher.verify_app_experience_gzip(compressed, dict(trace_reference(raw), **change))

    def test_trace_gzip_inflation_and_compressed_size_are_bounded(self):
        with patch.object(publisher, "APP_EXPERIENCE_MAX_TRACE_BYTES", 65536):
            raw = b"x" * 65535
            publisher.verify_app_experience_gzip(gzip.compress(raw), trace_reference(raw))
            raw += b"x"
            publisher.verify_app_experience_gzip(gzip.compress(raw), trace_reference(raw))
            raw += b"x"
            with self.assertRaises(ValueError):
                publisher.verify_app_experience_gzip(gzip.compress(raw), trace_reference(raw))
            # A dishonest small length must still stop a much larger inflation.
            with self.assertRaises(ValueError):
                publisher.verify_app_experience_gzip(gzip.compress(raw * 2), trace_reference(b"x"))
        with patch.object(publisher, "APP_EXPERIENCE_MAX_GZIP_BYTES", 20):
            with self.assertRaises(ValueError):
                publisher.verify_app_experience_gzip(gzip.compress(b"test"), trace_reference(b"test"))

    def test_exact_trace_capacity_is_preserved_only_as_failed_evidence(self):
        import collect_app_experience_evidence as experience
        import test_collect_app_experience_evidence as fixtures
        with tempfile.TemporaryDirectory() as temporary, \
                patch.object(experience, "MAX_TRACE_BYTES", 1024), \
                patch.object(publisher, "APP_EXPERIENCE_MAX_TRACE_BYTES", 1024):
            root = Path(temporary)
            directory, report = write_artifact(root)
            for name in ("startup-ttid.json", "startup-ttid.csv"):
                (directory / name).unlink()
            trace_path = directory / "trace-capture.json"
            trace = json.loads(trace_path.read_text())
            raw = b"x" * 1024
            compressed = gzip.compress(raw, mtime=0)
            (directory / "trace.pftrace.gz").write_bytes(compressed)
            trace.update(status="failed", error="trace_reached_size_limit", trace=trace_reference(raw),
                         compressed={"path": "trace.pftrace.gz", "bytes": len(compressed),
                                     "sha256": hashlib.sha256(compressed).hexdigest()})
            trace_path.write_bytes(fixtures.encoded(trace))
            capture = fixtures.collected_capture(directory, report)
            (directory / "capture.json").write_bytes(fixtures.encoded(capture))
            result = publisher.archive_app_experience(args_for(root), source_run("pilot"), [legacy.job()],
                                                     root / "output", {"bytes": 0, "files": 0})
            row = result["apis"][-1]
            self.assertEqual(("partial", "failed", "passed"), (row["captureStatus"], row["diagnosticStatus"], row["reportStatus"]))
            self.assertEqual(compressed, (root / "output/app-experience/api-37.0/trace.pftrace.gz").read_bytes())
            # An exact-capacity file may be preserved, but cannot claim complete collection.
            trace.update(status="collected", error=None)
            trace_path.write_bytes(fixtures.encoded(trace))
            rebind_capture(directory)
            with self.assertRaises(ValueError):
                publisher.archive_app_experience(args_for(root), source_run("pilot"), [legacy.job()],
                                                 root / "false-complete", {"bytes": 0, "files": 0})
            # Retaining a failure never grants one additional byte of raw capacity.
            raw += b"x"
            compressed = gzip.compress(raw, mtime=0)
            (directory / "trace.pftrace.gz").write_bytes(compressed)
            trace.update(status="failed", error="trace_reached_size_limit", trace=trace_reference(raw),
                         compressed={"path": "trace.pftrace.gz", "bytes": len(compressed),
                                     "sha256": hashlib.sha256(compressed).hexdigest()})
            trace_path.write_bytes(fixtures.encoded(trace))
            rebind_capture(directory)
            with self.assertRaises(ValueError):
                publisher.archive_app_experience(args_for(root), source_run("pilot"), [legacy.job()],
                                                 root / "over-capacity", {"bytes": 0, "files": 0})

    def test_new_attachment_budget_does_not_relax_the_old_copy_budget(self):
        self.assertEqual(64 * 1024 * 1024, publisher.MAX_TOTAL_BYTES)
        self.assertEqual(160 * 1024 * 1024, publisher.APP_EXPERIENCE_MAX_TOTAL_BYTES)
        self.assertEqual(16 * 1024 * 1024, publisher.APP_EXPERIENCE_MAX_LANE_BYTES)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "capture.json").write_text("{}")
            with self.assertRaises(ValueError):
                publisher.copy_files(source, root / "legacy", {"bytes": publisher.MAX_TOTAL_BYTES, "files": 0})
            budget = {"bytes": publisher.MAX_TOTAL_BYTES, "files": 0}
            copied = publisher.copy_app_experience_payloads({"capture.json": b"{}"}, root / "experience", budget)
            self.assertEqual(1, len(copied))
            self.assertEqual(publisher.MAX_TOTAL_BYTES + 2, budget["bytes"])
            budget = {"bytes": publisher.APP_EXPERIENCE_MAX_TOTAL_BYTES - 2, "files": 0}
            publisher.copy_app_experience_payloads({"capture.json": b"{}"}, root / "at-limit", budget)
            with self.assertRaises(ValueError):
                publisher.copy_app_experience_payloads({"capture.json": b"{}"}, root / "over-limit", budget)
            self.assertFalse((root / "over-limit").exists())

    def test_new_lane_and_shared_file_count_caps_apply_before_any_copy(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for payloads, budget in (({"a.json": b"x" * (publisher.APP_EXPERIENCE_MAX_LANE_BYTES + 1)}, {"bytes": 0, "files": 0}),
                                     ({"capture.json": b"{}"}, {"bytes": 0, "files": publisher.MAX_FILES})):
                with self.assertRaises(ValueError):
                    publisher.copy_app_experience_payloads(payloads, root / "output", budget)
                self.assertFalse((root / "output").exists())

    def test_verify_outputs_only_verified_experience_artifact_ids(self):
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
                self.assertEqual("456", values["app_experience_artifact_ids"])
                for field in ("active_pair_artifact_ids", "idle_pair_artifact_ids", "performance_artifact_ids", "stress_artifact_ids"):
                    self.assertEqual("", values[field])


if __name__ == "__main__":
    unittest.main()
