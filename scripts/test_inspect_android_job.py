"""Read-only job inspection must never trust unfinished or foreign reports."""

import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

import inspect_android_job as inspector
from test_evidence_scripts import source_run


def records():
    run = source_run()
    run["status"] = "in_progress"
    job = {"name": "emulator-tests (31)", "run_id": 123, "run_attempt": 2,
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
            with patch.object(inspector.subprocess, "run") as process:
                inspector.inspect_reports(root)
                self.assertEqual(1, process.call_count)
                command = process.call_args.args[0]
                self.assertEqual("print_android_test_failures.py", Path(command[1]).name)
                self.assertNotEqual(root, Path(command[2]))


if __name__ == "__main__":
    unittest.main()
