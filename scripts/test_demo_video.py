"""Boundary tests only; temporary bytes are never presented as recorded UI evidence."""
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
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


class DemoBoundaries(unittest.TestCase):
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


if __name__ == "__main__": unittest.main()
