"""Regression checks for evidence trust boundaries and honest result reporting."""

import base64
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

import collect_android_evidence as collector
import publish_test_evidence as publisher


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ9kAAAAASUVORK5CYII=")
SCRIPTS = Path(__file__).resolve().parent


def source_run():
    return {"id": 123, "run_attempt": 2, "repository": {"id": 1, "full_name": "ecojust/Agent-OS"},
            "head_repository": {"id": 1, "full_name": "ecojust/Agent-OS"}, "event": "push",
            "head_branch": "master", "head_sha": "a" * 40, "status": "completed",
            "path": ".github/workflows/android.yml", "conclusion": "failure"}


class EvidenceTests(unittest.TestCase):
    def test_trusted_master_run_accepts_failure_without_claiming_success(self):
        self.assertEqual((123, 2), publisher.verify_source(source_run(), "ecojust/Agent-OS"))

    def test_untrusted_and_incomplete_runs_are_rejected(self):
        for key, value in (("event", "pull_request"), ("head_branch", "feature"),
                           ("status", "in_progress"), ("path", ".github/workflows/other.yml"),
                           ("head_sha", "not-a-commit"), ("run_attempt", "2")):
            with self.subTest(field=key):
                run = source_run()
                run[key] = value
                with self.assertRaises(ValueError):
                    publisher.verify_source(run, "ecojust/Agent-OS")
        run = source_run()
        run["head_repository"] = {"id": 2, "full_name": "other/fork"}
        with self.assertRaises(ValueError):
            publisher.verify_source(run, "ecojust/Agent-OS")

    def test_artifact_attempt_and_full_api_label_are_preserved(self):
        value = {"artifacts": [{"name": "screenshots-api-37.0-attempt-2", "expired": False},
                               {"name": "screenshots-api-30-attempt-1", "expired": False},
                               {"name": "screenshots-api-31-attempt-2", "expired": True}]}
        self.assertEqual([value["artifacts"][0]], publisher.eligible_artifacts(value, 2))

    def test_archive_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "bad.tar"
            with tarfile.open(archive, "w") as tar:
                item = tarfile.TarInfo("screenshots/../../outside.png")
                item.size = len(PNG)
                tar.addfile(item, io.BytesIO(PNG))
            with self.assertRaises(ValueError):
                collector.extract_evidence(archive, root / "output")
            self.assertFalse((root / "outside.png").exists())

    def test_symlink_and_executable_artifacts_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "input"
            source.mkdir()
            (source / "run.sh").write_text("exit 0")
            with self.assertRaises(ValueError):
                publisher.copy_files(source, root / "output", {"bytes": 0, "files": 0})
            (source / "run.sh").unlink()
            (root / "secret.json").write_text("{}")
            (source / "secret.json").symlink_to(root / "secret.json")
            with self.assertRaises(ValueError):
                publisher.copy_files(source, root / "output", {"bytes": 0, "files": 0})

    def test_junit_counts_failures_and_skips(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "results.xml").write_text('<testsuite><testcase name="ok"/><testcase name="bad"><failure>bad</failure></testcase><testcase name="skip"><skipped/></testcase></testsuite>')
            result = collector.junit_summary(root)
            self.assertEqual((3, 1, 1), (result["tests"], result["failures"], result["skipped"]))

    def test_test_command_exit_is_preserved_when_capture_is_unavailable(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "scripts").mkdir()
            for name in ("run_android_tests.sh", "collect_android_evidence.py"):
                (root / "scripts" / name).write_bytes((SCRIPTS / name).read_bytes())
            (root / "bin").mkdir()
            adb = root / "bin" / "adb"
            adb.write_text('#!/bin/sh\nif [ "$1 $2" = "shell getprop" ]; then echo 30; exit 0; fi\nexit 1\n')
            adb.chmod(0o755)
            gradle = root / "gradlew"
            gradle.write_text("#!/bin/sh\nexit 7\n")
            gradle.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = str(root / "bin") + os.pathsep + env["PATH"]
            result = subprocess.run(["bash", "scripts/run_android_tests.sh", "30"], cwd=root,
                                    env=env, capture_output=True, text=True, check=False)
            self.assertEqual(7, result.returncode, result.stderr)
            record = json.loads((root / "test-evidence/api-30/capture.json").read_text())
            self.assertEqual(7, record["gradleExitCode"])
            self.assertEqual("unavailable", record["captureStatus"])

    def test_archive_keeps_missing_lanes_and_source_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            incoming = root / "incoming/screenshots-api-37.0-attempt-2"
            (incoming / "screenshots").mkdir(parents=True)
            (incoming / "screenshots/frame.png").write_bytes(PNG)
            (incoming / "screenshots/manifest.json").write_text(json.dumps({"tests": [
                {"captures": [{"status": "pass", "png": "frame.png"}]}]}))
            (incoming / "capture.json").write_text(json.dumps({"schemaVersion": 1, "apiLevel": 37,
                "matrixLabel": "37.0", "actualSdkInt": 37, "gradleExitCode": 1,
                "captureStatus": "collected", "junit": {"tests": 2, "failures": 1}}))
            (root / "source.json").write_text(json.dumps(source_run()))
            (root / "artifacts.json").write_text(json.dumps({"artifacts": [
                {"name": "screenshots-api-37.0-attempt-2", "expired": False}]}))
            (root / "jobs.json").write_text(json.dumps({"jobs": [
                {"name": "emulator-tests (37.0)", "conclusion": "failure"}]}))
            subprocess.run(["python3", str(SCRIPTS / "publish_test_evidence.py"), "archive",
                            "--run", str(root / "source.json"), "--repository", "ecojust/Agent-OS",
                            "--artifacts", str(root / "artifacts.json"), "--jobs", str(root / "jobs.json"),
                            "--incoming", str(root / "incoming")], cwd=root, capture_output=True,
                           text=True, check=True)
            manifest = json.loads((root / "screenshots/123-2/manifest.json").read_text())
            self.assertEqual("failure", manifest["sourceRun"]["conclusion"])
            self.assertEqual("not_scheduled", manifest["apis"][0]["jobConclusion"])
            self.assertEqual("37.0", manifest["apis"][-1]["matrixLabel"])
            self.assertEqual(37, manifest["apis"][-1]["actualSdkInt"])
            self.assertEqual(1, manifest["apis"][-1]["unrecordedTests"])


if __name__ == "__main__":
    unittest.main()
