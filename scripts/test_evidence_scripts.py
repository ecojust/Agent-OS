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

    def test_file_budget_is_shared_across_api_directories_and_rejects_next_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for label in ("30", "31"):
                source = root / label
                source.mkdir()
                (source / "capture.json").write_text("{}")
            budget = {"bytes": 0, "files": publisher.MAX_FILES - 1}
            copied = publisher.copy_files(root / "30", root / "out30", budget)
            self.assertEqual(1, len(copied))
            self.assertEqual(publisher.MAX_FILES, budget["files"])
            with self.assertRaisesRegex(ValueError, "archive budget"):
                publisher.copy_files(root / "31", root / "out31", budget)
            self.assertFalse((root / "out31/capture.json").exists())

    def test_larger_file_count_does_not_relax_shared_byte_budget(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "input"
            source.mkdir()
            (source / "capture.json").write_text("{}")
            budget = {"bytes": publisher.MAX_TOTAL_BYTES - 1, "files": 0}
            with self.assertRaisesRegex(ValueError, "archive budget"):
                publisher.copy_files(source, root / "output", budget)
            self.assertFalse((root / "output/capture.json").exists())

    def test_test_command_exit_is_preserved_when_capture_is_unavailable(self):
        self.check_test_exit("30", 30)

    def test_full_api_label_checks_package_metadata_without_launching_another_emulator(self):
        self.check_test_exit("37.0", 37)

    def test_unverified_animation_baseline_prevents_test_execution(self):
        self.check_test_exit("30", 30, animation_value="0.0", expected_exit=2)

    def check_test_exit(self, matrix_label, sdk_int, animation_value="1.0", expected_exit=7):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "scripts").mkdir()
            for name in ("run_android_tests.sh", "run_bounded_instrumentation.py", "collect_android_evidence.py", "print_android_test_failures.py",
                         "collect_performance_evidence.py"):
                (root / "scripts" / name).write_bytes((SCRIPTS / name).read_bytes())
            (root / "bin").mkdir()
            adb = root / "bin" / "adb"
            adb.write_text(f'#!/bin/sh\n'
                f'if [ "$1 $2" = "shell getprop" ]; then echo {sdk_int}; exit 0; fi\n'
                'if [ "$1 $2 $3 $4" = "shell settings put global" ]; then '
                'printf "%s\\n" "$5=$6" >> "$FAKE_SETTINGS_LOG"; exit 0; fi\n'
                f'if [ "$1 $2 $3 $4" = "shell settings get global" ]; then echo {animation_value}; exit 0; fi\n'
                'exit 1\n')
            adb.chmod(0o755)
            gradle = root / "gradlew"
            gradle.write_text("#!/bin/sh\ntouch gradle-started\nexit 7\n")
            gradle.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = str(root / "bin") + os.pathsep + env["PATH"]
            env["FAKE_SETTINGS_LOG"] = str(root / "animation-settings.txt")
            (root / "sdk/emulator").mkdir(parents=True)
            (root / "sdk/emulator/source.properties").write_text("Pkg.Revision=37.2.12\n")
            env["ANDROID_HOME"] = str(root / "sdk")
            env.update({"GITHUB_REPOSITORY": "ecojust/Agent-OS", "GITHUB_SHA": "a" * 40,
                        "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2"})
            result = subprocess.run(["bash", "scripts/run_android_tests.sh", matrix_label], cwd=root,
                                    env=env, capture_output=True, text=True, check=False)
            self.assertEqual(expected_exit, result.returncode, result.stderr)
            record = json.loads((root / f"test-evidence/api-{matrix_label}/capture.json").read_text())
            self.assertEqual(expected_exit, record["gradleExitCode"])
            self.assertEqual("unavailable", record["captureStatus"])
            performance = json.loads((root / f"performance-evidence/api-{matrix_label}/capture.json").read_text())
            self.assertEqual(expected_exit, performance["gradleExitCode"])
            self.assertEqual("unavailable", performance["captureStatus"])
            self.assertIn("No Android XML reports were produced", result.stdout)
            self.assertEqual(expected_exit == 7, (root / "gradle-started").exists())
            if expected_exit == 7:
                self.assertEqual(["window_animation_scale=1.0", "transition_animation_scale=1.0",
                    "animator_duration_scale=1.0"], (root / "animation-settings.txt").read_text().splitlines())
                self.assertEqual(3, result.stdout.count("Emulator baseline:"))

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
