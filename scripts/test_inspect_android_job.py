"""Read-only job inspection must never trust unfinished or foreign reports."""

from contextlib import redirect_stdout
import io
import os
import tempfile
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

import inspect_android_job as inspector
from test_evidence_scripts import source_run

REAL_POPEN = subprocess.Popen
KNOWN_TESTS = {("dev.agentos.app.ExampleTest", "knownCase"),
               ("dev.agentos.app.OtherTest", "otherCase")}


def records():
    run = source_run()
    run["status"] = "in_progress"
    job = {"id": 789, "name": "emulator-tests (31)", "run_id": 123, "run_attempt": 2,
           "head_sha": "a" * 40, "status": "completed", "conclusion": "failure",
           "started_at": "2026-10-26T10:00:00Z", "completed_at": "2026-10-26T10:05:00Z"}
    artifact = {"id": 456, "name": "emulator-test-reports-api-31", "expired": False,
                "size_in_bytes": 1000, "created_at": "2026-10-26T10:04:00Z",
                "workflow_run": {"id": 123, "repository_id": 1, "head_repository_id": 1,
                                 "head_sha": "a" * 40, "head_branch": "master"}}
    return run, {"jobs": [job]}, {"artifacts": [artifact]}


class InspectionTests(unittest.TestCase):
    def test_completed_job_is_allowed_before_entire_matrix_completes(self):
        run, jobs, artifacts = records()
        self.assertEqual((456, "failure"), inspector.select_report(
            run, jobs, artifacts, "ecojust/Agent-OS", "31"))

    def test_missing_artifact_and_safely_empty_xml_directory_can_use_log_fallback(self):
        for has_artifact in (False, True):
            with self.subTest(has_artifact=has_artifact), tempfile.TemporaryDirectory() as temporary:
                run, jobs, artifacts = records()
                if not has_artifact:
                    artifacts = {"artifacts": []}
                root = Path(temporary)
                (root / "index.html").write_text("PRIVATE_CONTENT must never be printed or executed")
                tail = inspector.StructuredLogTail(KNOWN_TESTS)
                with patch.object(inspector, "fetch_structured_log", return_value=(tail, "complete", 0)) as fetch:
                    output = io.StringIO()
                    with redirect_stdout(output):
                        inspector.inspect_completed(run, jobs, artifacts, "ecojust/Agent-OS", "31", root)
                    fetch.assert_called_once()
                    self.assertEqual(("ecojust/Agent-OS", 789), fetch.call_args.args[:2])
                self.assertIn("JUnit unavailable", output.getvalue())
                self.assertIn("cannot establish that tests passed", output.getvalue())
                self.assertNotIn("PRIVATE_CONTENT", output.getvalue())

    def test_missing_report_never_weakens_job_or_metadata_validation(self):
        for kind in ("pr", "foreign", "branch", "workflow", "unfinished", "attempt", "sha",
                     "id", "bool_id", "missing_pages", "malformed_page", "malformed_jobs"):
            with self.subTest(kind=kind):
                run, jobs, _ = records()
                artifacts = {"artifacts": []}
                job = jobs["jobs"][0]
                if kind == "pr": run["event"] = "pull_request"
                if kind == "foreign": run["head_repository"]["id"] = 99
                if kind == "branch": run["head_branch"] = "feature"
                if kind == "workflow": run["path"] = ".github/workflows/other.yml"
                if kind == "unfinished": job["status"] = "in_progress"
                if kind == "attempt": job["run_attempt"] = 1
                if kind == "sha": job["head_sha"] = "b" * 40
                if kind == "id": job["id"] = "789"
                if kind == "bool_id": job["id"] = True
                if kind == "missing_pages": artifacts = []
                if kind == "malformed_page": artifacts = {"message": "Not Found"}
                if kind == "malformed_jobs": jobs = {"jobs": [None]}
                with patch.object(inspector, "fetch_structured_log") as fetch:
                    with self.assertRaises(ValueError):
                        inspector.inspect_completed(run, jobs, artifacts, "ecojust/Agent-OS", "31", None)
                    fetch.assert_not_called()

    def test_expired_old_duplicate_or_invalid_artifact_never_uses_fallback(self):
        for kind in ("expired", "old", "duplicate", "mixed_old", "mixed_expired", "invalid"):
            with self.subTest(kind=kind):
                run, jobs, artifacts = records()
                artifact = artifacts["artifacts"][0]
                if kind == "expired": artifact["expired"] = True
                if kind == "old": artifact["created_at"] = "2026-10-26T09:00:00Z"
                if kind == "invalid": artifact["workflow_run"]["head_sha"] = "b" * 40
                if kind in ("duplicate", "mixed_old", "mixed_expired"):
                    duplicate = dict(artifact, id=457)
                    if kind == "mixed_old": duplicate["created_at"] = "2026-10-26T09:00:00Z"
                    if kind == "mixed_expired": duplicate["expired"] = True
                    artifacts["artifacts"].append(duplicate)
                with patch.object(inspector, "fetch_structured_log") as fetch:
                    with self.assertRaises(ValueError):
                        inspector.inspect_completed(run, jobs, artifacts, "ecojust/Agent-OS", "31", None)
                    fetch.assert_not_called()

    def test_invalid_report_directory_never_uses_fallback(self):
        for kind in ("malformed", "entity", "symlink"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                run, jobs, artifacts = records()
                root = Path(temporary)
                path = root / "report.xml"
                if kind == "malformed": path.write_text("<testsuite>")
                if kind == "entity": path.write_text('<!DOCTYPE x [<!ENTITY x "secret">]><testsuite/>')
                if kind == "symlink": path.symlink_to("/etc/passwd")
                with patch.object(inspector, "fetch_structured_log") as fetch:
                    with self.assertRaises(ValueError):
                        inspector.inspect_completed(run, jobs, artifacts, "ecojust/Agent-OS", "31", root)
                    fetch.assert_not_called()

    def test_log_filter_reconstructs_known_pairs_and_never_echoes_untrusted_text(self):
        tail = inspector.StructuredLogTail(KNOWN_TESTS)
        text = "\n".join((
            "2026-10-27T03:20:30.123Z > Task :app:connectedDebugAndroidTest",
            "Starting 114 tests on agent-os-phone(AVD) - 11",
            "agent-os-phone(AVD) - 11 Tests 27/114 completed. (0 skipped) (1 failed)",
            "dev.agentos.app.ExampleTest > knownCase[agent-os-phone(AVD) - 11] FAILED",
            "TestRunner: started: otherCase(dev.agentos.app.OtherTest)",
            "27 tests completed, 1 failed", "BUILD FAILED in 1m 3s",
            "##[error]The operation was canceled.",
            "TOKEN=PRIVATE_SECRET", "https://example.invalid/log?sig=PRIVATE_SECRET",
            "::error title=PRIVATE_SECRET::injection", "> Task :app:PRIVATE_SECRET",
            "dev.agentos.app.ExampleTest > otherCase FAILED",
            "dev.agentos.app.ExampleTest > knownCase FAILED PRIVATE_SECRET",
            "TestRunner: started: knownCase(dev.agentos.app.OtherTest)",
            "\x1b[31mPRIVATE_SECRET", "\xffPRIVATE_SECRET", ""))
        data = text.encode("utf-8")
        for start in range(0, len(data), 17):
            tail.feed(data[start:start + 17])
        tail.finish()
        output = "".join(tail.lines)
        self.assertIn("2026-10-27T03:20:30.123Z Gradle task", output)
        self.assertIn("Test progress 27/114; skipped=0; failed=1", output)
        self.assertIn("ExampleTest.knownCase: FAILED", output)
        self.assertIn("OtherTest.otherCase: STARTED", output)
        self.assertNotIn("ExampleTest.otherCase", output)
        self.assertNotIn("OtherTest.knownCase", output)
        for forbidden in ("PRIVATE_SECRET", "::error", "https://", "TOKEN=", "\x1b"):
            self.assertNotIn(forbidden, output)

    def test_log_memory_and_retained_output_are_bounded_and_prefer_latest_progress(self):
        tail = inspector.StructuredLogTail(KNOWN_TESTS)
        for _ in range(50):
            tail.feed(b"PRIVATE_SECRET" * 1000)
            self.assertLessEqual(len(tail.pending), inspector.MAX_LOG_LINE_BYTES)
        tail.feed(b"\n")
        with patch.object(inspector, "LOG_PROGRESS_CHARS", 300):
            for _ in range(100):
                tail.feed(b"dev.agentos.app.ExampleTest > knownCase STARTED\n")
            tail.feed(b"dev.agentos.app.ExampleTest > knownCase FAILED\n")
        tail.finish()
        output = "".join(tail.lines)
        self.assertLessEqual(len(output), 300)
        self.assertTrue(tail.trimmed)
        self.assertTrue(output.endswith("ExampleTest.knownCase: FAILED\n"))
        self.assertNotIn("PRIVATE_SECRET", output)

    def test_log_transport_is_runner_only_fixed_endpoint_and_suppresses_stderr(self):
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}):
            with patch.object(inspector.subprocess, "Popen") as popen:
                with self.assertRaises(ValueError):
                    inspector.fetch_structured_log("ecojust/Agent-OS", 789, KNOWN_TESTS)
                popen.assert_not_called()
        def start(command, **kwargs):
            self.assertEqual(["gh", "api", "--hostname", "github.com",
                              "repos/ecojust/Agent-OS/actions/jobs/789/logs"], command)
            self.assertEqual(subprocess.DEVNULL, kwargs["stderr"])
            return REAL_POPEN([sys.executable, "-c",
                "import sys; print('BUILD FAILED in 1s'); "
                "print('PRIVATE_SECRET signed-url', file=sys.stderr); sys.exit(1)"], **kwargs)
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "ecojust/Agent-OS"}):
            with patch.object(inspector.subprocess, "Popen", side_effect=start):
                tail, status, _ = inspector.fetch_structured_log("ecojust/Agent-OS", 789, KNOWN_TESTS)
        self.assertEqual("unavailable", status)
        self.assertNotIn("PRIVATE_SECRET", "".join(tail.lines))

    def test_log_transport_caps_raw_bytes_and_timeout_also_covers_eof_before_exit(self):
        for kind in ("bytes", "eof_then_hang"):
            with self.subTest(kind=kind):
                code = ("import os; os.write(1, b'PRIVATE_SECRET' * 10000)" if kind == "bytes"
                        else "import os,time; os.close(1); time.sleep(5)")
                def start(command, **kwargs):
                    return REAL_POPEN([sys.executable, "-c", code], **kwargs)
                with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "ecojust/Agent-OS"}):
                    with patch.object(inspector.subprocess, "Popen", side_effect=start), \
                            patch.object(inspector, "MAX_LOG_BYTES", 128), \
                            patch.object(inspector, "MAX_LOG_SECONDS", 0.5):
                        tail, status, total = inspector.fetch_structured_log("ecojust/Agent-OS", 789, KNOWN_TESTS)
                self.assertEqual("byte_limit" if kind == "bytes" else "time_limit", status)
                self.assertLessEqual(total, 128)
                self.assertNotIn("PRIVATE_SECRET", "".join(tail.lines))

    def test_foreign_or_unfinished_or_old_attempt_reports_are_rejected(self):
        for kind in ("pr", "branch", "workflow", "unfinished", "attempt", "old", "foreign", "oversized"):
            with self.subTest(kind=kind):
                run, jobs, artifacts = records()
                job, artifact = jobs["jobs"][0], artifacts["artifacts"][0]
                if kind == "pr": run["event"] = "pull_request"
                if kind == "branch": run["head_branch"] = "feature"
                if kind == "workflow": run["path"] = ".github/workflows/other.yml"
                if kind == "unfinished": job["status"] = "in_progress"
                if kind == "attempt": job["run_attempt"] = 1
                if kind == "old": artifact["created_at"] = "2026-10-26T09:00:00Z"
                if kind == "foreign": artifact["workflow_run"]["repository_id"] = 2
                if kind == "oversized": artifact["size_in_bytes"] = inspector.MAX_ARTIFACT_BYTES + 1
                with self.assertRaises(ValueError):
                    inspector.select_report(run, jobs, artifacts, "ecojust/Agent-OS", "31")

    def test_reports_with_entities_or_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            xml = root / "report.xml"
            xml.write_text('<!DOCTYPE x [<!ENTITY x "unsafe">]><testsuite/>')
            with self.assertRaises(ValueError): inspector.inspect_reports(root)
            xml.unlink()
            xml.symlink_to("/etc/passwd")
            with self.assertRaises(ValueError): inspector.inspect_reports(root)

    def test_only_checked_in_printer_runs_with_xml_not_artifact_code(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "malicious.py").write_text("raise RuntimeError('must not execute')")
            (root / "result.xml").write_text('<testsuite><testcase name="case"><failure>diag=synthetic</failure></testcase></testsuite>')
            with patch.object(inspector.subprocess, "run",
                              return_value=subprocess.CompletedProcess([], 0, stdout="")) as process:
                inspector.inspect_reports(root)
                self.assertEqual(1, process.call_count)
                command = process.call_args.args[0]
                self.assertEqual("print_android_test_failures.py", Path(command[1]).name)
                self.assertNotEqual(root, Path(command[2]))

    def test_ime_summary_retains_late_state_without_echoing_other_values(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            report = root / "result.xml"
            report.write_text('<testsuite><testcase name="keyboard"><failure>'
                + ('unrelated history\n' * 1000)
                + 'TOKEN=secret-value\nUSER_TEXT=private-editor-content\n'
                + 'mInputViewStarted=true mWindowVisible=false extraKey=secret-value\n'
                + 'onEvaluateInputViewShown(): true\nreason=HIDE_SOFT_INPUT\n'
                + '</failure></testcase></testsuite>')
            summary = inspector.ime_summary([report])
            self.assertIn('mInputViewStarted=true', summary)
            self.assertIn('mWindowVisible=false', summary)
            self.assertIn('onEvaluateInputViewShown=true', summary)
            self.assertIn('reason=HIDE_SOFT_INPUT', summary)
            self.assertNotIn('secret-value', summary)
            self.assertNotIn('private-editor-content', summary)
            self.assertNotIn('TOKEN', summary)

    def test_ime_summary_is_bounded_and_favors_latest_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = Path(temporary) / "result.xml"
            failure = '<failure>' + ('mWindowVisible=false\n' * 1000) + 'mWindowVisible=true</failure>'
            report.write_text('<testsuite>' + ''.join(
                f'<testcase name="keyboard{n}">{failure}</testcase>' for n in range(100)) + '</testsuite>')
            summary = inspector.ime_summary([report])
            self.assertLessEqual(len(summary), inspector.MAX_IME_CHARS)
            self.assertIn('mWindowVisible=true', summary)


if __name__ == "__main__":
    unittest.main()
