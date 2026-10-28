"""Boundary tests only; temporary bytes are never presented as recorded UI evidence."""
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import sys
import time
import unittest
from types import SimpleNamespace
from unittest import mock

import demo_video as demo

SOURCE = {"repository": demo.REPO, "commit": "a" * 40, "runId": 123, "attempt": 1}


def flow():
    return {"schemaVersion": 1, "kind": "agent-os-synthetic-controller-demo", "source": dict(SOURCE),
            "device": {"api": 35, "emulator": True}, "model": {"kind": "local_synthetic_http", "externalModelUsed": False},
            "startedAt": 1000, "finishedAt": 31000, "startedElapsedMs": 100, "finishedElapsedMs": 30100,
            "status": "passed", "failure": None,
            "stages": [{"name": name, "status": "passed", "atElapsedMs": i * 1000, "wallTimeMs": 1000 + i * 1000,
                        "controllerRunning": True, "controllerPaused": False, "pendingApproval": False,
                        "requestCount": i % 5, "dispatchAttempts": i % 3, "approvalOrdinal": 0}
                       for i, name in enumerate(demo.STAGES)]}


def provenance():
    run = {"repository": {"full_name": demo.REPO, "id": 20}, "head_repository": {"full_name": demo.REPO, "id": 20},
           "id": 123, "run_attempt": 1, "head_sha": "a" * 40, "event": "workflow_dispatch", "head_branch": "master",
           "path": demo.WORKFLOW, "status": "completed"}
    job = {"name": demo.JOB, "status": "completed", "conclusion": "success", "run_id": 123, "run_attempt": 1,
           "head_sha": "a" * 40, "started_at": "2026-10-27T00:00:00Z", "completed_at": "2026-10-27T00:05:00Z"}
    artifact = {"id": 100, "name": "demo-video-api-35-attempt-1", "expired": False, "size_in_bytes": 10000,
                "created_at": "2026-10-27T00:04:59Z", "workflow_run": {"id": 123, "head_sha": "a" * 40,
                "head_branch": "master", "repository_id": 20, "head_repository_id": 20}}
    return run, {"jobs": [job]}, {"artifacts": [artifact]}


def workflow_env(branch="master", review=False):
    ref = "refs/heads/" + branch
    return {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux", "GITHUB_REPOSITORY": demo.REPO,
            "GITHUB_REF": ref, "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_WORKFLOW_REF": demo.REPO + "/" + demo.WORKFLOW + "@" + ref,
            "GITHUB_SHA": "b" * 40, "GITHUB_RUN_ID": "456", "GITHUB_RUN_ATTEMPT": "1",
            "DEMO_REVIEW_BRANCH": "true" if review else "false"}


def review_provenance():
    run, jobs, artifacts = provenance()
    run["head_branch"] = artifacts["artifacts"][0]["workflow_run"]["head_branch"] = "agent/demo-review"
    scope = {"review_branch": True, "expected_source_sha": SOURCE["commit"],
             "env": workflow_env("agent/demo-review", True)}
    return run, jobs, artifacts, scope


class DemoBoundaries(unittest.TestCase):
    def setUp(self):
        # Running these boundary tests in a review workflow must not opt the
        # legacy master fixtures into that workflow's archive policy.
        clean_scope = mock.patch.dict(os.environ, {"DEMO_REVIEW_BRANCH": "false", "DEMO_EXPECTED_SOURCE_SHA": ""})
        clean_scope.start(); self.addCleanup(clean_scope.stop)

    def test_source_requires_exact_workflow_repository_branch_and_event(self):
        env = {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux", "GITHUB_REPOSITORY": demo.REPO,
               "GITHUB_REF": "refs/heads/master", "GITHUB_EVENT_NAME": "workflow_dispatch",
               "GITHUB_WORKFLOW_REF": demo.REPO + "/" + demo.WORKFLOW + "@refs/heads/master",
               "GITHUB_SHA": "a" * 40, "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}
        self.assertEqual(SOURCE, demo.identity(env))
        for key, invalid in (("GITHUB_REF", "refs/pull/1/merge"), ("GITHUB_REPOSITORY", "other/repo"),
                             ("GITHUB_EVENT_NAME", "push"), ("GITHUB_WORKFLOW_REF", "arbitrary"),
                             ("RUNNER_OS", "Windows"), ("GITHUB_RUN_ID", "1; echo hidden")):
            with self.subTest(key=key), self.assertRaises(ValueError): demo.identity(dict(env, **{key: invalid}))

    def test_review_recording_requires_explicit_opt_in_and_exact_current_workflow(self):
        env = workflow_env("agent/demo-review", True)
        self.assertEqual({"repository": demo.REPO, "commit": "b" * 40, "runId": 456, "attempt": 1}, demo.identity(env))
        for key, invalid in (("DEMO_REVIEW_BRANCH", "false"), ("DEMO_REVIEW_BRANCH", "yes"),
                             ("GITHUB_REF", "refs/heads/feature/demo-review"), ("GITHUB_REF", "refs/pull/1/merge"),
                             ("GITHUB_EVENT_NAME", "pull_request"), ("GITHUB_EVENT_NAME", "push"),
                             ("GITHUB_REPOSITORY", "fork/Agent-OS"),
                             ("GITHUB_WORKFLOW_REF", demo.REPO + "/" + demo.WORKFLOW + "@refs/heads/master")):
            with self.subTest(key=key, invalid=invalid), self.assertRaises(ValueError):
                demo.identity(dict(env, **{key: invalid}))
        env.pop("DEMO_REVIEW_BRANCH")
        with self.assertRaises(ValueError): demo.identity(env)
        for branch in ("agent/", "agent//other", "agent/../other", "agent/demo.lock", "unknown/demo"):
            with self.subTest(branch=branch), self.assertRaises(ValueError): demo.identity(workflow_env(branch, True))

    def test_review_archive_is_explicit_same_branch_and_pins_source_not_current_head(self):
        run, jobs, artifacts, scope = review_provenance()
        self.assertNotEqual(run["head_sha"], scope["env"]["GITHUB_SHA"])
        self.assertEqual(SOURCE, demo.select_artifact(run, jobs, artifacts, **scope)[0])
        with self.assertRaises(ValueError): demo.select_artifact(run, jobs, artifacts)
        with self.assertRaises(ValueError): demo.select_artifact(run, jobs, artifacts, expected_source_sha="a" * 40)
        for sha in (None, "", "a" * 39, "A" * 40, "b" * 40, "unknown", True):
            with self.subTest(sha=sha), self.assertRaises(ValueError):
                demo.select_artifact(run, jobs, artifacts, **dict(scope, expected_source_sha=sha))
        for env in (workflow_env("agent/other", True), workflow_env("master", True),
                    workflow_env("feature/demo-review", True), dict(scope["env"], GITHUB_EVENT_NAME="pull_request")):
            with self.subTest(env=env), self.assertRaises(ValueError):
                demo.select_artifact(run, jobs, artifacts, **dict(scope, env=env))

    def test_review_archive_keeps_exact_repository_attempt_job_and_artifact_checks(self):
        mutations = (lambda r,j,a: r["head_repository"].update(id=21),
                     lambda r,j,a: r["head_repository"].update(full_name="fork/Agent-OS"),
                     lambda r,j,a: r.update(event="pull_request"), lambda r,j,a: r.update(event="unknown"),
                     lambda r,j,a: r.update(head_branch="master"),
                     lambda r,j,a: r.update(path=".github/workflows/android.yml"),
                     lambda r,j,a: j["jobs"][0].update(run_attempt=2),
                     lambda r,j,a: j["jobs"][0].update(head_sha="b"*40),
                     lambda r,j,a: j["jobs"][0].update(conclusion="unknown"),
                     lambda r,j,a: j["jobs"][0].update(run_attempt=True),
                     lambda r,j,a: a["artifacts"][0]["workflow_run"].update(head_branch="agent/other"),
                     lambda r,j,a: a["artifacts"][0]["workflow_run"].update(head_sha="b"*40),
                     lambda r,j,a: a["artifacts"][0]["workflow_run"].update(head_repository_id=21),
                     lambda r,j,a: a["artifacts"][0].update(created_at="2026-10-26T23:59:59Z"))
        for mutate in mutations:
            run, jobs, artifacts, scope = review_provenance(); mutate(run, jobs, artifacts)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                demo.select_artifact(run, jobs, artifacts, **scope)

    def test_master_archive_default_remains_compatible_but_explicit_sha_is_checked(self):
        run, jobs, artifacts = provenance()
        self.assertEqual(SOURCE, demo.select_artifact(run, jobs, artifacts)[0])
        self.assertEqual(SOURCE, demo.select_artifact(run, jobs, artifacts, expected_source_sha="a"*40)[0])
        for sha in ("b"*40, "not-a-sha"):
            with self.assertRaises(ValueError): demo.select_artifact(run, jobs, artifacts, expected_source_sha=sha)

    def test_select_cli_uses_explicit_review_workflow_context(self):
        run, jobs, artifacts, scope = review_provenance()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name, value in (("run", run), ("jobs", jobs), ("artifacts", artifacts)):
                (root / (name + ".json")).write_text(json.dumps(value))
            output = root / "output"
            argv = ["demo_video.py", "select", "--run", str(root / "run.json"), "--jobs", str(root / "jobs.json"),
                    "--artifacts", str(root / "artifacts.json"), "--github-output", str(output)]
            env = dict(scope["env"], DEMO_EXPECTED_SOURCE_SHA=SOURCE["commit"])
            with mock.patch.object(sys, "argv", argv), mock.patch.dict(os.environ, env, clear=True):
                self.assertEqual(0, demo.main())
            self.assertIn("artifact_id=100\nsource_run=123\nattempt=1\n", output.read_text())
            output.unlink()
            env.pop("DEMO_EXPECTED_SOURCE_SHA")
            with mock.patch.object(sys, "argv", argv), mock.patch.dict(os.environ, env, clear=True), self.assertRaises(ValueError):
                demo.main()
            self.assertFalse(output.exists())

    def test_review_prepare_rejects_missing_opt_in_before_writing_any_record(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "recording"
            argv = ["demo_video.py", "prepare", "--output", str(output)]
            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.dict(os.environ, workflow_env("agent/demo-review"), clear=True), self.assertRaises(ValueError):
                demo.main()
            self.assertFalse(output.exists())
            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.dict(os.environ, workflow_env("agent/demo-review", True), clear=True):
                self.assertEqual(0, demo.main())
            report = json.loads((output / "report.json").read_text())
            self.assertEqual("unverified", report["status"])
            self.assertEqual("b" * 40, report["source"]["commit"])

    def test_flow_preserves_real_per_task_counter_resets(self):
        self.assertEqual("passed", demo.validate_flow(flow(), SOURCE)["status"])

    def test_failed_partial_flow_and_unverified_emulator_are_retained(self):
        value = flow(); value.update(status="failed", stages=value["stages"][:2], failure={"stage": "text_entered", "errorClass": "java.lang.AssertionError"})
        value["device"]["emulator"] = False
        self.assertEqual("failed", demo.validate_flow(value, SOURCE)["status"])

    def test_success_cannot_omit_or_reorder_steps_or_claim_external_model(self):
        variants = []
        value = flow(); value["stages"].pop(); variants.append(value)
        value = flow(); value["stages"][1]["name"] = "ready"; variants.append(value)
        value = flow(); value["model"]["externalModelUsed"] = True; variants.append(value)
        value = flow(); value["device"]["emulator"] = False; variants.append(value)
        for value in variants:
            with self.assertRaises(ValueError): demo.validate_flow(value, SOURCE)

    def test_flow_rejects_freeform_text_foreign_identity_boolean_counter_and_extra_fields(self):
        variants = []
        value = flow(); value["source"]["attempt"] = True; variants.append(value)
        value = flow(); value["source"]["commit"] = "b" * 40; variants.append(value)
        value = flow(); value["stages"][0]["requestCount"] = True; variants.append(value)
        value = flow(); value["stages"][0]["prompt"] = "must not be archived"; variants.append(value)
        value = flow(); value["notes"] = "arbitrary"; variants.append(value)
        for value in variants:
            with self.assertRaises(ValueError): demo.validate_flow(value, SOURCE)

    def test_flow_time_and_count_limits_are_enforced(self):
        for edit in (lambda x: x.update(finishedElapsedMs=90101), lambda x: x["stages"][1].update(atElapsedMs=31000),
                     lambda x: x["stages"].extend([copy.deepcopy(x["stages"][0])] * 40)):
            value = flow(); edit(value)
            with self.assertRaises(ValueError): demo.validate_flow(value, SOURCE)

    def test_confirmation_ordinal_is_unique_but_failed_retry_record_can_preserve_same_ordinal(self):
        value = flow(); stage = dict(value["stages"][0], name="confirmation_waiting", approvalOrdinal=1)
        value["stages"].insert(1, stage)
        demo.validate_flow(value, SOURCE)
        value["stages"].insert(2, dict(stage))
        with self.assertRaises(ValueError): demo.validate_flow(value, SOURCE)
        value["stages"][2]["status"] = "failed"; value["status"] = "failed"
        value["failure"] = {"stage": "confirmation_waiting", "errorClass": "java.lang.AssertionError"}
        demo.validate_flow(value, SOURCE)

    def test_instrumentation_exit_zero_alone_is_not_success(self):
        self.assertTrue(demo.instrument_passed("OK (1 test)", 0))
        for text, code in (("", 0), ("OK (2 tests)", 0), ("OK (1 test)\nFAILURES!!!", 0), ("OK (1 test)", 1)):
            self.assertFalse(demo.instrument_passed(text, code))

    def test_video_limit_is_strictly_less_than_decimal_100mb(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "video.mp4"
            with path.open("wb") as stream: stream.truncate(100_000_000)
            command = mock.Mock()
            with self.assertRaises(ValueError): demo.video_metadata(path, True, command)
            command.assert_not_called()

    def test_probe_rejects_wrong_codec_format_audio_dimensions_and_nonfinite_duration(self):
        valid = {"streams": [{"codec_type": "video", "codec_name": "h264", "pix_fmt": "yuv420p", "width": 640, "height": 1280}], "format": {"duration": "20.0"}}
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "video.mp4"; path.write_bytes(b"unit-test-only")
            probe = lambda value: lambda *a, **k: json.dumps(value).encode()
            self.assertEqual(20000, demo.video_metadata(path, True, probe(valid))["durationMs"])
            mutations = [lambda x: x["streams"][0].update(codec_name="hevc"), lambda x: x["streams"][0].update(pix_fmt="yuv444p"),
                         lambda x: x["streams"].append({"codec_type": "audio"}), lambda x: x["streams"][0].update(width=641),
                         lambda x: x["format"].update(duration="NaN"), lambda x: x["format"].update(duration="126")]
            for edit in mutations:
                value = copy.deepcopy(valid); edit(value)
                with self.assertRaises(ValueError): demo.video_metadata(path, True, probe(value))

    def test_transcode_failure_deletes_partial_output_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as temp:
            raw = Path(temp) / "raw.mp4"; output = Path(temp) / "demo.mp4"; raw.write_bytes(b"unit-test-only")
            meta = {"durationMs": 10000}
            def failed(*args, **kwargs): output.write_bytes(b"partial"); raise ValueError("encoder failed")
            with mock.patch.object(demo, "video_metadata", return_value=meta), self.assertRaises(ValueError): demo.compress(raw, output, failed)
            self.assertFalse(output.exists())
            output.write_bytes(b"existing")
            with self.assertRaises(ValueError): demo.compress(raw, output, failed)
            self.assertEqual(b"existing", output.read_bytes())

    def test_recorder_stops_only_owned_child_and_closes_pipe(self):
        recorder = mock.Mock(); recorder.poll.return_value = None; recorder.stdout = io.BytesIO(); adb = mock.Mock()
        self.assertTrue(demo.stop_recorder(recorder, "1234", adb))
        adb.assert_called_once_with("shell", "kill", "-2", "1234", timeout=5)
        self.assertTrue(recorder.stdout.closed); recorder.kill.assert_not_called()

    def test_recorder_timeout_is_not_success_and_still_cleans_local_process(self):
        recorder = mock.Mock(); recorder.poll.return_value = None; recorder.stdout = io.BytesIO()
        recorder.wait.side_effect = [subprocess.TimeoutExpired("adb", 12), 1]
        self.assertFalse(demo.stop_recorder(recorder, "1234", mock.Mock()))
        recorder.kill.assert_called_once(); self.assertTrue(recorder.stdout.closed)

    def test_early_finished_recorder_is_not_success_and_does_not_send_device_signal(self):
        recorder = mock.Mock(); recorder.poll.return_value = 0; recorder.stdout = io.BytesIO(); adb = mock.Mock()
        self.assertFalse(demo.stop_recorder(recorder, "1234", adb)); adb.assert_not_called()

    def test_original_instrumentation_identifies_exact_class_method_and_count(self):
        value={"schemaVersion":1,"runner":"direct_adb_instrumentation","selectedClass":demo.TEST,
               "selectedMethod":demo.METHOD,"expectedTests":1,"status":"completed","exitCode":0,
               "stdout":f"INSTRUMENTATION_STATUS: class={demo.TEST}\nINSTRUMENTATION_STATUS: test={demo.METHOD}\nINSTRUMENTATION_STATUS: numtests=1\nOK (1 test)\n"}
        self.assertTrue(demo.instrument_case_passed(value))
        for field, text in (("status","timed_out"),("stdout","OK (1 test)"),
                            ("stdout",value["stdout"]+"INSTRUMENTATION_STATUS: class=another.Case\n")):
            changed=dict(value,**{field:text});self.assertFalse(demo.instrument_case_passed(changed))

    def test_timeout_partial_instrumentation_is_retained_as_non_success(self):
        value={"schemaVersion":1,"runner":"direct_adb_instrumentation","selectedClass":demo.TEST,
               "selectedMethod":demo.METHOD,"expectedTests":1,"status":"timed_out","exitCode":-9,
               "stdout":"INSTRUMENTATION_STATUS: class="+demo.TEST+"\npartial diagnostic"}
        self.assertFalse(demo.instrument_case_passed(value))
        value["stdout"]="x"*196609
        with self.assertRaises(ValueError):demo.instrument_case_passed(value)

    def test_provenance_accepts_actual_failed_test_job_but_not_archive_mode_or_duplicate_job(self):
        run, jobs, artifacts = provenance(); jobs["jobs"][0]["conclusion"] = "failure"
        self.assertEqual(100, demo.select_artifact(run, jobs, artifacts)[2]["id"])
        jobs["jobs"][0]["conclusion"] = "skipped"
        with self.assertRaises(ValueError): demo.select_artifact(run, jobs, artifacts)
        run, jobs, artifacts = provenance(); jobs["jobs"].append(dict(jobs["jobs"][0]))
        with self.assertRaises(ValueError): demo.select_artifact(run, jobs, artifacts)

    def test_provenance_rejects_fork_wrong_workflow_wrong_attempt_and_expired_artifact(self):
        for mutate in (lambda r,j,a: r["head_repository"].update(id=21), lambda r,j,a: r.update(path=".github/workflows/android.yml"),
                       lambda r,j,a: j["jobs"][0].update(run_attempt=2), lambda r,j,a: a["artifacts"][0].update(expired=True),
                       lambda r,j,a: a["artifacts"][0]["workflow_run"].update(head_sha="b"*40),
                       lambda r,j,a: a["artifacts"][0].update(created_at="2026-10-26T23:59:59Z")):
            run,jobs,artifacts=provenance(); mutate(run,jobs,artifacts)
            with self.assertRaises(ValueError): demo.select_artifact(run,jobs,artifacts)

    def test_other_artifact_names_are_not_downloaded_and_duplicate_fixed_name_is_rejected(self):
        run,jobs,artifacts=provenance(); artifacts["artifacts"][0]["name"]="arbitrary-upload"
        self.assertIsNone(demo.select_artifact(run,jobs,artifacts)[2])
        run,jobs,artifacts=provenance(); artifacts["artifacts"].append(copy.deepcopy(artifacts["artifacts"][0]))
        with self.assertRaises(ValueError): demo.select_artifact(run,jobs,artifacts)

    def test_failed_empty_record_is_retained_and_extra_files_or_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp); report=demo.prepare(directory,SOURCE)
            report.update(status="failed",failureStage="environment");demo.write_report(directory,report)
            self.assertEqual("failed",demo.inspect_bundle(directory,SOURCE)[0]["status"])
            (directory/"secret.txt").write_text("not evidence")
            with self.assertRaises(ValueError):demo.inspect_bundle(directory,SOURCE)
            (directory/"secret.txt").unlink();(directory/"capture.json").symlink_to(directory/"report.json")
            with self.assertRaises(ValueError):demo.inspect_bundle(directory,SOURCE)

    def test_success_cannot_be_forged_by_changing_status_without_flow_video_and_screenshots(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);report=demo.prepare(directory,SOURCE)
            report.update(status="passed",failureStage=None,instrumentationPassed=True,instrumentationExitCode=0)
            demo.write_report(directory,report)
            with self.assertRaises(ValueError):demo.inspect_bundle(directory,SOURCE)

    def test_preflight_failure_still_writes_failed_report_without_device_cleanup(self):
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);demo.prepare(directory,SOURCE)
            with mock.patch.object(demo.bounded,"run_command",side_effect=ValueError("unavailable")):
                self.assertFalse(demo.record(directory,SOURCE))
            report=json.loads((directory/"report.json").read_text())
            self.assertEqual("failed",report["status"]);self.assertEqual("device_preflight",report["failureStage"])


    def failed_video_bundle(self, directory):
        # Schema-boundary bytes only: they must never be called a decoded UI recording.
        report = demo.prepare(directory, SOURCE)
        video = directory / "demo.mp4"; video.write_bytes(b"unit-test-only-not-a-real-video")
        metadata = dict(demo.file_record(video), durationMs=1000, width=720, height=1280,
                        codec="h264", pixelFormat="yuv420p")
        report.update(status="failed", failureStage="instrumentation", recordingStatus="recorded",
                      video=metadata, rawVideo=dict(metadata))
        demo.write_report(directory, report)
        return report

    def test_failed_raw_preservation_keeps_hash_but_does_not_claim_decode(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp); self.failed_video_bundle(directory)
            with mock.patch.object(demo, "video_metadata", side_effect=AssertionError("must not probe")), \
                    mock.patch.object(demo.bounded, "run_command", side_effect=AssertionError("must not decode")):
                report, files = demo.inspect_bundle(directory, SOURCE, failed_source=True)
            self.assertEqual("failed", report["status"])
            self.assertEqual(demo.file_record(directory / "demo.mp4"),
                             {k: report["video"][k] for k in ("bytes", "sha256")})
            self.assertEqual({"report.json", "demo.mp4"}, {f["path"] for f in files})

    def test_failed_raw_preservation_still_rejects_changed_bytes_and_unsafe_metadata(self):
        for change in (lambda r: r["video"].update(sha256="b" * 64),
                       lambda r: r["video"].update(width=721),
                       lambda r: r["video"].update(durationMs=True),
                       lambda r: r["video"].update(bytes=100_000_000),
                       lambda r: r["video"].update(extra="arbitrary")):
            with tempfile.TemporaryDirectory() as temp:
                directory = Path(temp); report = self.failed_video_bundle(directory)
                change(report); demo.write_report(directory, report)
                with self.assertRaises(ValueError): demo.inspect_bundle(directory, SOURCE, failed_source=True)

    def test_failed_source_cannot_preserve_passed_or_unverified_report_without_validation(self):
        for status in ("passed", "unverified"):
            with tempfile.TemporaryDirectory() as temp:
                directory = Path(temp); report = self.failed_video_bundle(directory)
                report["status"] = status; demo.write_report(directory, report)
                with self.assertRaises(ValueError): demo.inspect_bundle(directory, SOURCE, failed_source=True)

    def test_default_archive_path_still_requires_real_video_probe(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp); self.failed_video_bundle(directory)
            with mock.patch.object(demo, "video_metadata", side_effect=RuntimeError("decoder unavailable")) as probe:
                with self.assertRaises(RuntimeError): demo.inspect_bundle(directory, SOURCE)
                probe.assert_called_once()

    def archive_fixture(self, root, conclusion):
        run, jobs, artifacts = provenance(); jobs["jobs"][0]["conclusion"] = conclusion
        for name, value in (("run", run), ("jobs", jobs), ("artifacts", artifacts)):
            (root / (name + ".json")).write_text(json.dumps(value))
        incoming = root / "incoming"; incoming.mkdir(); self.failed_video_bundle(incoming)
        (root / "docs").mkdir(); (root / "docs/progress.json").write_bytes(b'{"existing":true}\n')
        return SimpleNamespace(run=root / "run.json", jobs=root / "jobs.json", artifacts=root / "artifacts.json", incoming=incoming)

    def test_failed_source_archive_is_explicitly_failed_and_keeps_previous_progress(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); args = self.archive_fixture(root, "failure")
            before = (root / "docs/progress.json").read_bytes(); previous = Path.cwd()
            try:
                os.chdir(root)
                with mock.patch.object(demo, "video_metadata", side_effect=AssertionError("must not probe")):
                    demo.archive(args)
            finally: os.chdir(previous)
            manifest = json.loads((root / "screenshots/demos/123-1/manifest.json").read_text())
            self.assertEqual("failed", manifest["status"])
            self.assertEqual("not_revalidated", manifest["videoValidation"])
            self.assertEqual(before, (root / "docs/progress.json").read_bytes())
            self.assertEqual((args.incoming / "demo.mp4").read_bytes(),
                             (root / "screenshots/demos/123-1/demo.mp4").read_bytes())

    def test_review_archive_records_real_branch_without_changing_legacy_source_schema(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); args = self.archive_fixture(root, "failure")
            run, jobs, artifacts, scope = review_provenance(); jobs["jobs"][0]["conclusion"] = "failure"
            for name, value in (("run", run), ("jobs", jobs), ("artifacts", artifacts)):
                (root / (name + ".json")).write_text(json.dumps(value))
            before = (root / "docs/progress.json").read_bytes(); previous = Path.cwd()
            try:
                os.chdir(root)
                with mock.patch.dict(os.environ, dict(scope["env"], DEMO_EXPECTED_SOURCE_SHA=SOURCE["commit"]), clear=True), \
                        mock.patch.object(demo.bounded, "run_command", side_effect=AssertionError("must not execute downloaded content")):
                    demo.archive(args)
            finally: os.chdir(previous)
            manifest = json.loads((root / "screenshots/demos/123-1/manifest.json").read_text())
            self.assertEqual("agent/demo-review", manifest["sourceBranch"])
            self.assertEqual(SOURCE, manifest["source"])
            self.assertEqual("failed", manifest["status"])
            self.assertEqual(before, (root / "docs/progress.json").read_bytes())

    def test_review_archive_revalidates_scope_after_artifact_selection(self):
        run, jobs, artifacts, scope = review_provenance(); jobs["jobs"][0]["conclusion"] = "failure"
        self.assertEqual(100, demo.select_artifact(run, jobs, artifacts, **scope)[2]["id"])
        env = dict(scope["env"], DEMO_EXPECTED_SOURCE_SHA=SOURCE["commit"])
        for field, value in (("DEMO_EXPECTED_SOURCE_SHA", ""), ("DEMO_EXPECTED_SOURCE_SHA", "b"*40),
                             ("DEMO_REVIEW_BRANCH", "false"), ("GITHUB_REF", "refs/heads/agent/other")):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); args = self.archive_fixture(root, "failure")
                for name, data in (("run", run), ("jobs", jobs), ("artifacts", artifacts)):
                    (root / (name + ".json")).write_text(json.dumps(data))
                previous = Path.cwd()
                try:
                    os.chdir(root)
                    with mock.patch.dict(os.environ, dict(env, **{field:value}), clear=True), \
                            mock.patch.object(demo, "inspect_bundle", side_effect=AssertionError("reject before bundle reads")) as inspect, \
                            self.assertRaises(ValueError):
                        demo.archive(args)
                    inspect.assert_not_called()
                finally: os.chdir(previous)
                self.assertFalse((root / "screenshots").exists())

    def test_success_or_cancelled_source_does_not_activate_failed_raw_exception(self):
        for conclusion in ("success", "cancelled", "timed_out"):
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp); args = self.archive_fixture(root, conclusion); previous = Path.cwd()
                try:
                    os.chdir(root)
                    with mock.patch.object(demo, "video_metadata", side_effect=RuntimeError("decoder unavailable")) as probe:
                        with self.assertRaises(RuntimeError): demo.archive(args)
                        probe.assert_called_once()
                finally: os.chdir(previous)



    def test_recorder_stderr_drains_real_child_but_retains_only_bounded_bytes(self):
        child = subprocess.Popen([sys.executable, "-c", "import os; os.write(2,b'x'*100000)"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        reader = demo.RecorderStderr(child.stderr)
        try:
            self.assertEqual(0, child.wait(timeout=5)); value = reader.finish()
            self.assertEqual("x" * 8192, value["text"])
            self.assertTrue(value["truncated"]); self.assertTrue(value["complete"])
            self.assertFalse(reader.thread.is_alive()); self.assertTrue(child.stderr.closed)
        finally:
            if child.poll() is None: child.kill(); child.wait(timeout=2)
            if reader.thread.is_alive(): reader.finish()

    def test_recorder_stderr_cleanup_is_bounded_if_writer_is_still_alive(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(10)"],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        reader = demo.RecorderStderr(child.stderr)
        try:
            start = time.monotonic(); value = reader.finish()
            self.assertLess(time.monotonic() - start, 1)
            self.assertFalse(value["complete"]); self.assertFalse(reader.thread.is_alive())
        finally:
            child.kill(); child.wait(timeout=2)

    def test_primary_start_failure_survives_secondary_collection_and_video_failures(self):
        def command(args, **kwargs):
            if args == ["adb", "devices"]: return "List of devices attached\nemulator-5554\tdevice\n"
            if "getprop" in args:
                return {"ro.kernel.qemu":"1", "ro.boot.qemu":"1", "ro.build.version.sdk":"35",
                        "ro.product.cpu.abi":"x86_64", "sys.boot_completed":"1"}[args[-1]] + "\n"
            if "exec-out" in args or "stat" in args: raise subprocess.TimeoutExpired("fixed-test-command", 5)
            return ""
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp); demo.prepare(directory, SOURCE)
            with mock.patch.object(demo.bounded, "run_command", side_effect=command), \
                    mock.patch.object(demo.subprocess, "Popen", side_effect=PermissionError("do not record this message")), \
                    mock.patch("collect_android_evidence.collect", side_effect=OSError("not persisted")):
                self.assertFalse(demo.record(directory, SOURCE))
            report = json.loads((directory / "report.json").read_text())
            diagnostics = json.loads((directory / "recording-diagnostics.json").read_text())
            self.assertEqual("recording_start", report["failureStage"])
            self.assertEqual(["recording_start", "collect_flow", "collect_screenshots", "raw_stat"],
                             [v["stage"] for v in diagnostics["failures"]])
            self.assertEqual("PermissionError", diagnostics["failures"][0]["errorClass"])
            self.assertNotIn("do not record", json.dumps(diagnostics))
            self.assertEqual("failed", demo.inspect_bundle(directory, SOURCE, failed_source=True)[0]["status"])

    def test_compression_diagnostics_distinguish_each_media_stage_without_relaxing_success(self):
        cases = ("raw_probe", "video_encode", "encoded_probe", "encoded_policy", "video_decode")
        for failure in cases:
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp:
                raw = Path(temp) / "raw.mp4"; output = Path(temp) / "demo.mp4"; raw.write_bytes(b"unit-only")
                stages = []; good = {"durationMs":1000, "bytes":10}
                def metadata(path, *args, **kwargs):
                    if failure == "raw_probe" and path == raw or failure == "encoded_probe" and path == output:
                        raise ValueError("not persisted")
                    return dict(good, durationMs=2000) if failure == "encoded_policy" and path == output else good
                def command(args, **kwargs):
                    if stages[-1] == failure: raise subprocess.TimeoutExpired("fixed-test-command", 1)
                    if stages[-1] == "video_encode": output.write_bytes(b"unit-only")
                    return ""
                with mock.patch.object(demo, "video_metadata", side_effect=metadata), self.assertRaises((ValueError, subprocess.TimeoutExpired)):
                    demo.compress(raw, output, command, stages.append)
                self.assertEqual(failure, stages[-1]); self.assertFalse(output.exists())

    def test_diagnostic_schema_rejects_content_extra_fields_and_unbounded_stderr(self):
        valid = {"schemaVersion":1, "kind":"agent-os-demo-recording-diagnostics", "source":dict(SOURCE),
                 "failures":[{"stage":"video_decode", "errorClass":"ValueError"}],
                 "recorder":{"started":True,"exitCode":0,"stderr":{"text":"bounded", "truncated":False,"complete":True}}}
        demo.validate_diagnostics(valid, SOURCE)
        for edit in (lambda v:v["failures"][0].update(message="secret"),
                     lambda v:v["failures"][0].update(stage="arbitrary"),
                     lambda v:v["recorder"]["stderr"].update(text="x"*24577),
                     lambda v:v["source"].update(attempt=True)):
            value = copy.deepcopy(valid); edit(value)
            with self.assertRaises(ValueError): demo.validate_diagnostics(value, SOURCE)

    def test_legacy_original_report_remains_strictly_readable(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp); report = demo.prepare(directory, SOURCE)
            report.pop("diagnostics"); report.update(schemaVersion=1,status="failed",failureStage="environment")
            demo.write_report(directory, report)
            self.assertEqual(1, demo.inspect_bundle(directory, SOURCE, failed_source=True)[0]["schemaVersion"])
            report["diagnostics"] = None; demo.write_report(directory, report)
            with self.assertRaises(ValueError): demo.inspect_bundle(directory, SOURCE, failed_source=True)



    def test_successful_archive_requires_complete_stderr_diagnostics(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp); report = demo.prepare(directory, SOURCE)
            value = {"schemaVersion":1, "kind":"agent-os-demo-recording-diagnostics", "source":dict(SOURCE),
                     "failures":[], "recorder":{"started":True,"exitCode":0,
                     "stderr":{"text":"", "truncated":False,"complete":False}}}
            path = directory / "recording-diagnostics.json"; path.write_text(json.dumps(value))
            report.update(status="passed",failureStage=None,diagnostics=demo.file_record(path))
            demo.write_report(directory, report)
            with self.assertRaisesRegex(ValueError, "Missing successful recorder diagnostics"):
                demo.inspect_bundle(directory, SOURCE)



    def test_runner_failure_is_recorded_before_a_failing_force_stop_cleanup(self):
        # Local child only to exercise real stdout/stderr ownership; no Android success is simulated.
        child = subprocess.Popen([sys.executable, "-c", "import os,time; print(os.getpid(),flush=True); time.sleep(10)"],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        def command(args, **kwargs):
            if args == ["adb", "devices"]: return "List of devices attached\nemulator-5554\tdevice\n"
            if "getprop" in args:
                return {"ro.kernel.qemu":"1", "ro.boot.qemu":"1", "ro.build.version.sdk":"35",
                        "ro.product.cpu.abi":"x86_64", "sys.boot_completed":"1"}[args[-1]] + "\n"
            if "force-stop" in args: raise OSError("cleanup message must not replace the runner failure")
            if "exec-out" in args or "stat" in args: raise ValueError("no synthetic UI capture")
            if "kill" in args: child.terminate()
            return ""
        try:
            with tempfile.TemporaryDirectory() as temp:
                directory = Path(temp); demo.prepare(directory, SOURCE)
                with mock.patch.object(demo.bounded, "run_command", side_effect=command), \
                        mock.patch.object(demo.subprocess, "Popen", return_value=child), \
                        mock.patch("run_bounded_instrumentation.bounded_capture", return_value=(
                            b"INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: original\n",
                            {"status":"timed_out", "exitCode":-9})), \
                        mock.patch("collect_android_evidence.collect", side_effect=OSError("unavailable")):
                    self.assertFalse(demo.record(directory, SOURCE))
                report = json.loads((directory / "report.json").read_text())
                diagnostics = json.loads((directory / "recording-diagnostics.json").read_text())
                self.assertEqual("instrumentation", report["failureStage"])
                self.assertEqual({"stage":"instrumentation", "errorClass":"java.lang.AssertionError"}, diagnostics["failures"][0])
                self.assertEqual({"stage":"instrumentation_cleanup", "errorClass":"OSError"}, diagnostics["failures"][1])
                demo.inspect_bundle(directory, SOURCE, failed_source=True)
        finally:
            if child.poll() is None: child.kill(); child.wait(timeout=2)



    def test_installed_video_tools_are_reused_without_network_or_privilege(self):
        helper = Path(__file__).with_name("ensure_demo_video_tools.sh")
        with tempfile.TemporaryDirectory() as temp:
            for name in ("ffmpeg", "ffprobe"):
                file = Path(temp) / name; file.write_text("#!/bin/sh\nexit 0\n"); file.chmod(0o700)
            # PATH has neither sudo nor apt-get; this must succeed without them.
            result = subprocess.run(["/bin/bash", str(helper)], env={"PATH":temp}, capture_output=True, text=True)
            self.assertEqual(0, result.returncode)
            self.assertIn("Using installed", result.stdout)

    def test_missing_video_tools_cannot_install_outside_trusted_workflow(self):
        helper = Path(__file__).with_name("ensure_demo_video_tools.sh")
        with tempfile.TemporaryDirectory() as temp:
            result = subprocess.run(["/bin/bash", str(helper)], env={"PATH":temp}, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("restricted to the trusted Linux workflow", result.stderr)

    def test_one_installed_video_tool_is_not_mistaken_for_both(self):
        helper = Path(__file__).with_name("ensure_demo_video_tools.sh")
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "ffmpeg"; file.write_text("#!/bin/sh\nexit 0\n"); file.chmod(0o700)
            result = subprocess.run(["/bin/bash", str(helper)], env={"PATH":temp}, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("Using installed", result.stdout)



    def test_raw_screenrecord_allows_only_bounded_mett_metadata_alongside_one_h264_video(self):
        value = {"streams":[{"codec_type":"video","codec_name":"h264","pix_fmt":"yuv420p","width":320,"height":640},
                            {"codec_type":"data","codec_tag_string":"mett"},
                            {"codec_type":"data","codec_tag_string":"mett"}], "format":{"duration":"12.000000"}}
        self.assertEqual(12000, demo.parse_video_probe(json.dumps(value).encode(), False)["durationMs"])
        variants = []
        x = copy.deepcopy(value); x["streams"][1] = {"codec_type":"audio"}; variants.append(x)
        x = copy.deepcopy(value); x["streams"].append(copy.deepcopy(x["streams"][0])); variants.append(x)
        x = copy.deepcopy(value); x["streams"].append({"codec_type":"data","codec_tag_string":"mett"}); variants.append(x)
        x = copy.deepcopy(value); x["streams"][1]["codec_tag_string"] = "unknown"; variants.append(x)
        x = copy.deepcopy(value); x["streams"][0]["codec_name"] = "hevc"; variants.append(x)
        for invalid in variants:
            with self.assertRaises(ValueError): demo.parse_video_probe(json.dumps(invalid).encode(), False)
        with self.assertRaises(ValueError): demo.parse_video_probe(json.dumps(value).encode(), True)

    def test_video_probe_retains_actual_selected_stdout_separately_from_stderr(self):
        records = []
        result = demo.capture_video_probe([sys.executable, "-c",
            "import sys; sys.stdout.write('{\"streams\":[]}'); sys.stderr.write('diagnostic only')"], records.append)
        self.assertEqual(b'{"streams":[]}', result)
        self.assertEqual([{"status":"completed", "exitCode":0, "stdout":'{"streams":[]}'}], records)

    def test_video_probe_failure_preserves_actual_exit_code_before_raising(self):
        records = []
        with self.assertRaises(ValueError):
            demo.capture_video_probe([sys.executable, "-c", "import sys; print('{}'); sys.exit(7)"], records.append)
        self.assertEqual(7, records[0]["exitCode"]); self.assertEqual("completed", records[0]["status"])
        self.assertEqual("{}\n", records[0]["stdout"])

    def test_video_probe_bounds_and_reaps_excessive_child_output(self):
        records = []
        with self.assertRaises(ValueError):
            demo.capture_video_probe([sys.executable, "-c", "import os; os.write(1,b'x'*1000000)"], records.append)
        self.assertEqual("output_limit", records[0]["status"])
        self.assertLessEqual(len(records[0]["stdout"].encode()), 65536)
        self.assertIsNotNone(records[0]["exitCode"])

    def test_video_probe_diagnostics_reject_duplicate_stage_and_oversized_output(self):
        value = {"schemaVersion":2,"kind":"agent-os-demo-recording-diagnostics","source":dict(SOURCE),"failures":[],
                 "recorder":{"started":False,"exitCode":None,"stderr":None},
                 "probes":[{"stage":"raw_probe","status":"completed","exitCode":0,"stdout":"{}"}]}
        demo.validate_diagnostics(value, SOURCE)
        invalid = copy.deepcopy(value); invalid["probes"].append(dict(invalid["probes"][0]))
        with self.assertRaises(ValueError): demo.validate_diagnostics(invalid, SOURCE)
        invalid = copy.deepcopy(value); invalid["probes"][0]["stdout"] = "x"*196609
        with self.assertRaises(ValueError): demo.validate_diagnostics(invalid, SOURCE)


if __name__ == "__main__": unittest.main()
