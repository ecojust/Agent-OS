"""Regression checks for evidence trust boundaries and honest result reporting."""

import base64
import copy
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

    def test_explicit_full_and_fixed_diagnostic_keep_the_original_exit_and_collectors(self):
        self.check_test_exit("30", 30, scope="full")
        self.check_test_exit("30", 30, scope=collector.DIAGNOSTIC_SCOPE)

    def test_arbitrary_test_filters_and_unknown_scopes_do_not_start_devices_or_gradle(self):
        for scope in ("SomeClass#someMethod", "owned-control-diagnostic --class AnotherClass", "task-qualification-diagnostic --class AnotherClass",
                      "control-status-diagnostic --class AnotherClass",
                      "screen-off-diagnostic --class AnotherClass", "future-scope"):
            with self.subTest(scope=scope):
                self.check_test_exit("30", 30, scope=scope, expected_exit=2, invalid_scope=True)

    def check_test_exit(self, matrix_label, sdk_int, animation_value="1.0", expected_exit=7,
                        scope=None, invalid_scope=False):
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
            gradle.write_text("#!/bin/sh\ntouch gradle-started\nprintf '%s\\n' \"$@\" > gradle-args.txt\nexit 7\n")
            gradle.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = str(root / "bin") + os.pathsep + env["PATH"]
            env["FAKE_SETTINGS_LOG"] = str(root / "animation-settings.txt")
            (root / "sdk/emulator").mkdir(parents=True)
            (root / "sdk/emulator/source.properties").write_text("Pkg.Revision=37.2.12\n")
            env["ANDROID_HOME"] = str(root / "sdk")
            env.update({"GITHUB_REPOSITORY": "ecojust/Agent-OS", "GITHUB_SHA": "a" * 40,
                        "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "2"})
            command = ["bash", "scripts/run_android_tests.sh", matrix_label]
            if scope is not None:
                command.append(scope)
            result = subprocess.run(command, cwd=root,
                                    env=env, capture_output=True, text=True, check=False)
            self.assertEqual(expected_exit, result.returncode, result.stderr)
            if invalid_scope:
                self.assertFalse((root / "gradle-started").exists())
                self.assertFalse((root / "animation-settings.txt").exists())
                self.assertFalse((root / "test-evidence").exists())
                return
            record = json.loads((root / f"test-evidence/api-{matrix_label}/capture.json").read_text())
            self.assertEqual(expected_exit, record["gradleExitCode"])
            self.assertEqual(scope or "full", record["executionScope"])
            self.assertEqual(collector.test_selection(scope or "full"), record["testSelection"])
            self.assertEqual("unavailable", record["captureStatus"])
            performance = json.loads((root / f"performance-evidence/api-{matrix_label}/capture.json").read_text())
            self.assertEqual(expected_exit, performance["gradleExitCode"])
            self.assertEqual("unavailable", performance["captureStatus"])
            self.assertIn("No Android XML reports were produced", result.stdout)
            self.assertEqual(expected_exit == 7, (root / "gradle-started").exists())
            if expected_exit == 7:
                arguments = (root / "gradle-args.txt").read_text().splitlines()
                selectors = [value for value in arguments if value.startswith("-Pandroid.testInstrumentationRunnerArguments.")]
                self.assertEqual(1, len(selectors))
                if scope in collector.DIAGNOSTIC_SELECTIONS:
                    self.assertEqual("-Pandroid.testInstrumentationRunnerArguments.class=" + collector.test_selection(scope), selectors[0])
                else:
                    self.assertEqual("-Pandroid.testInstrumentationRunnerArguments.notClass=dev.agentos.app.PinnedCalculatorVisualPageInstrumentedTest,dev.agentos.app.DemoWalkthroughInstrumentedTest,dev.agentos.app.PinnedWindowDisclosureInstrumentedTest", selectors[0])
                self.assertEqual(["window_animation_scale=1.0", "transition_animation_scale=1.0",
                    "animator_duration_scale=1.0"], (root / "animation-settings.txt").read_text().splitlines())
                self.assertEqual(3, result.stdout.count("Emulator baseline:"))

    def test_diagnostic_junit_selection_uses_actual_case_identity_and_rejects_extra_cases(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            test_class, method = collector.DIAGNOSTIC_TEST.split("#")
            xml = path / "results.xml"
            xml.write_text(f'<testsuite><testcase classname="{test_class}" name="{method}"/></testsuite>')
            selected = collector.junit_summary(path, collector.DIAGNOSTIC_TEST)
            self.assertEqual((1, 1, 0), (selected["tests"], selected["selectedTests"], selected["unexpectedTests"]))
            xml.write_text(f'<testsuite><testcase classname="{test_class}" name="{method}"/><testcase classname="Other" name="other"/></testsuite>')
            extra = collector.junit_summary(path, collector.DIAGNOSTIC_TEST)
            self.assertEqual((2, 1, 1), (extra["tests"], extra["selectedTests"], extra["unexpectedTests"]))
            with self.assertRaises(ValueError):
                publisher.diagnostic_result({"junit": extra}, [], {"conclusion": "success"})
            xml.unlink()
            for index in range(25):
                (path / f"{index:02d}.xml").write_text("<testsuite/>" if index else
                    f'<testsuite><testcase classname="{test_class}" name="{method}"/></testsuite>')
            bounded = collector.junit_summary(path, collector.DIAGNOSTIC_TEST)
            self.assertEqual((1, 1), (bounded["selectedTests"], bounded["unreadableReports"]))
            self.assertEqual("unverified", publisher.diagnostic_result({"junit": bounded, "gradleExitCode": 0},
                [{"testClass": test_class, "testName": method, "testResult": "pass"}], {"conclusion": "success"}))

    def test_finalize_retains_explicit_diagnostic_scope_and_refuses_scope_relabel(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            command = ["python3", str(SCRIPTS / "collect_android_evidence.py"), "finalize",
                       "--matrix-label", "31", "--scope", collector.DIAGNOSTIC_SCOPE]
            subprocess.run(command, cwd=root, check=True, capture_output=True, text=True)
            path = root / "test-evidence/api-31/capture.json"
            original = path.read_bytes()
            record = json.loads(original)
            self.assertEqual(collector.DIAGNOSTIC_SCOPE, record["executionScope"])
            self.assertEqual(collector.DIAGNOSTIC_TEST, record["testSelection"])
            self.assertEqual(0, record["junit"]["selectedTests"])
            wrong = subprocess.run(command[:-1] + ["full"], cwd=root, capture_output=True, text=True)
            self.assertNotEqual(0, wrong.returncode)
            self.assertEqual(original, path.read_bytes())

    def test_diagnostic_scope_comes_from_trusted_run_not_a_small_test_count(self):
        run = source_run()
        self.assertEqual("full", publisher.android_execution_scope(run))
        run.update(event="workflow_dispatch", display_title=publisher.DIAGNOSTIC_TITLE)
        self.assertEqual(collector.DIAGNOSTIC_SCOPE, publisher.android_execution_scope(run))
        run["display_title"] = "Android checks [unknown]"
        with self.assertRaises(ValueError):
            publisher.android_execution_scope(run)
        with self.assertRaises(ValueError):
            collector.verify_capture_scope({}, collector.DIAGNOSTIC_SCOPE)
        collector.verify_capture_scope({}, "full")  # Original full captures remain readable.

    def diagnostic_archive(self, lanes, scope=collector.DIAGNOSTIC_SCOPE, artifact_scope=None, manifest_selection=None):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "incoming").mkdir()
            run = source_run()
            run.update(event="workflow_dispatch", display_title=next(title for title, value in publisher.DIAGNOSTIC_TITLES.items() if value == scope))
            artifact_scope = artifact_scope or scope
            selection = collector.test_selection(artifact_scope)
            artifacts, jobs = [], []
            test_class, method = (manifest_selection or collector.test_selection(scope)).split("#")
            for label, status in lanes.items():
                folder = root / f"incoming/screenshots-api-{label}-attempt-2"
                (folder / "screenshots").mkdir(parents=True)
                capture = {"schemaVersion": 1, "apiLevel": int(label.split('.')[0]), "matrixLabel": label,
                           "actualSdkInt": int(label.split('.')[0]), "gradleExitCode": int(status == "failed"),
                           "executionScope": artifact_scope, "testSelection": selection,
                           "captureStatus": "collected", "junit": {"reports": 1, "tests": 1,
                           "failures": int(status == "failed"), "errors": 0, "skipped": int(status == "skipped"),
                           "unreadableReports": 0, "selection": selection,
                           "selectedTests": 1, "unexpectedTests": 0}}
                tests = [] if status == "skipped" else [{"testClass": test_class, "testName": method,
                          "testResult": "fail" if status == "failed" else "pass", "captures": []}]
                (folder / "capture.json").write_text(json.dumps(capture))
                (folder / "screenshots/manifest.json").write_text(json.dumps({"tests": tests}))
                artifacts.append({"name": folder.name, "expired": False})
                jobs.append({"name": f"emulator-tests ({label})", "conclusion": "failure" if status == "failed" else "success"})
            (root / "source.json").write_text(json.dumps(run))
            (root / "artifacts.json").write_text(json.dumps({"artifacts": artifacts}))
            (root / "jobs.json").write_text(json.dumps({"jobs": jobs}))
            subprocess.run(["python3", str(SCRIPTS / "publish_test_evidence.py"), "archive",
                            "--run", str(root / "source.json"), "--repository", "ecojust/Agent-OS",
                            "--artifacts", str(root / "artifacts.json"), "--jobs", str(root / "jobs.json"),
                            "--incoming", str(root / "incoming")], cwd=root, check=True, capture_output=True, text=True)
            output = root / "screenshots/123-2"
            return json.loads((output / "manifest.json").read_text()), (output / "README.md").read_text()

    def test_diagnostic_archive_keeps_eight_unverified_when_all_artifacts_are_missing(self):
        manifest, readme = self.diagnostic_archive({})
        self.assertEqual(collector.DIAGNOSTIC_SCOPE, manifest["executionScope"])
        self.assertEqual((1, 8), (manifest["expectedTestsPerApi"], manifest["expectedTests"]))
        self.assertEqual({"passed": 0, "failed": 0, "unverified": 8}, manifest["diagnosticCaseCounts"])
        self.assertFalse(manifest["fullSuiteAcceptance"])
        self.assertIn("not counted as passed", readme)

    def test_diagnostic_archive_counts_only_exact_actual_case_and_keeps_failure_skip_and_missing(self):
        manifest, readme = self.diagnostic_archive({"30": "passed", "31": "failed", "32": "skipped"})
        self.assertEqual({"passed": 1, "failed": 1, "unverified": 6}, manifest["diagnosticCaseCounts"])
        self.assertEqual(collector.DIAGNOSTIC_TEST, manifest["testSelection"])
        self.assertEqual(8, len(manifest["apis"]))
        self.assertIn("not run by this scope", readme)

    def test_diagnostic_success_cannot_be_inferred_from_a_green_job_or_wrong_case(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            test_class, method = collector.DIAGNOSTIC_TEST.split("#")
            (root / "result.xml").write_text(f'<testsuite><testcase classname="{test_class}" name="{method}"/></testsuite>')
            capture = {"junit": collector.junit_summary(root, collector.DIAGNOSTIC_TEST), "gradleExitCode": 0}
            tests = [{"testClass": test_class, "testName": method, "testResult": "pass"}]
            self.assertEqual("passed", publisher.diagnostic_result(capture, tests, {"conclusion": "success"}))
            self.assertEqual("unverified", publisher.diagnostic_result(capture, [], {"conclusion": "success"}))
            tests[0]["testName"] = "differentMethod"
            with self.assertRaises(ValueError):
                publisher.diagnostic_result(capture, tests, {"conclusion": "success"})
            wrong = copy.deepcopy(capture)
            wrong["junit"]["selectedTests"] = 0
            wrong["junit"]["unexpectedTests"] = 1
            with self.assertRaises(ValueError):
                publisher.diagnostic_result(wrong, [], {"conclusion": "success"})

    def test_task_qualification_shell_selects_only_its_fixed_case_and_preserves_failure(self):
        self.check_test_exit("33", 33, scope=collector.QUALIFICATION_SCOPE)

    def test_task_qualification_scope_requires_its_exact_manual_title_and_matching_capture(self):
        run = source_run()
        run["display_title"] = publisher.QUALIFICATION_TITLE
        self.assertEqual("full", publisher.android_execution_scope(run))
        run["event"] = "workflow_dispatch"
        self.assertEqual(collector.QUALIFICATION_SCOPE, publisher.android_execution_scope(run))
        run["display_title"] += " --class Other"
        with self.assertRaises(ValueError):
            publisher.android_execution_scope(run)
        for record in ({}, {"executionScope": collector.DIAGNOSTIC_SCOPE,
                            "testSelection": collector.DIAGNOSTIC_TEST},
                       {"executionScope": collector.QUALIFICATION_SCOPE,
                        "testSelection": collector.DIAGNOSTIC_TEST}):
            with self.subTest(record=record), self.assertRaises(ValueError):
                collector.verify_capture_scope(record, collector.QUALIFICATION_SCOPE)
        self.assertEqual(collector.DIAGNOSTIC_TEST, collector.test_selection(collector.DIAGNOSTIC_SCOPE))
        self.assertEqual("full-suite", collector.test_selection("full"))

    def test_task_qualification_finalize_preserves_scope_without_reports_and_cannot_relabel(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            command = ["python3", str(SCRIPTS / "collect_android_evidence.py"), "finalize",
                       "--matrix-label", "33", "--scope", collector.QUALIFICATION_SCOPE]
            subprocess.run(command, cwd=root, check=True, capture_output=True, text=True)
            path = root / "test-evidence/api-33/capture.json"
            original = path.read_bytes()
            record = json.loads(original)
            self.assertEqual(collector.QUALIFICATION_TEST, record["testSelection"])
            self.assertEqual(collector.QUALIFICATION_TEST, record["junit"]["selection"])
            self.assertEqual(0, record["junit"]["selectedTests"])
            for scope in ("full", collector.DIAGNOSTIC_SCOPE):
                result = subprocess.run(command[:-1] + [scope], cwd=root, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(original, path.read_bytes())

    def test_task_qualification_archive_keeps_all_missing_and_mixed_lanes_in_its_eight_case_scope(self):
        for lanes, counts in (({}, {"passed": 0, "failed": 0, "unverified": 8}),
                              ({"30": "passed", "33": "failed", "37.0": "skipped"},
                               {"passed": 1, "failed": 1, "unverified": 6})):
            with self.subTest(lanes=lanes):
                manifest, readme = self.diagnostic_archive(lanes, collector.QUALIFICATION_SCOPE)
                self.assertEqual(collector.QUALIFICATION_SCOPE, manifest["executionScope"])
                self.assertEqual(collector.QUALIFICATION_TEST, manifest["testSelection"])
                self.assertEqual((1, 8), (manifest["expectedTestsPerApi"], manifest["expectedTests"]))
                self.assertEqual(8, len(manifest["apis"]))
                self.assertEqual(counts, manifest["diagnosticCaseCounts"])
                self.assertFalse(manifest["fullSuiteAcceptance"])
                self.assertIn("# Task Qualification Diagnostic Evidence", readme)
                self.assertIn(collector.QUALIFICATION_TEST, readme)
                self.assertIn("not counted as passed", readme)
                self.assertNotIn(collector.DIAGNOSTIC_TEST, readme)

    def test_task_qualification_archive_rejects_other_scope_and_wrong_manifest_case(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.diagnostic_archive({"33": "passed"}, collector.QUALIFICATION_SCOPE,
                                    artifact_scope=collector.DIAGNOSTIC_SCOPE)
        with self.assertRaises(subprocess.CalledProcessError):
            self.diagnostic_archive({"33": "passed"}, collector.QUALIFICATION_SCOPE,
                                    manifest_selection=collector.DIAGNOSTIC_TEST)

    def test_task_qualification_original_xml_is_required_and_cannot_mix_cases_or_borrow_old_selection(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            test_class, method = collector.QUALIFICATION_TEST.split("#")
            xml = root / "result.xml"
            case = f'<testcase classname="{test_class}" name="{method}"/>'
            xml.write_text('<testsuite>' + case + '</testsuite>')
            capture = {"junit": collector.junit_summary(root, collector.QUALIFICATION_TEST), "gradleExitCode": 0}
            tests = [{"testClass": test_class, "testName": method, "testResult": "pass"}]
            job = {"conclusion": "success"}
            self.assertEqual("passed", publisher.diagnostic_result(capture, tests, job, collector.QUALIFICATION_SCOPE))
            self.assertEqual("unverified", publisher.diagnostic_result(capture, [], job, collector.QUALIFICATION_SCOPE))
            with self.assertRaises(ValueError):
                publisher.diagnostic_result(capture, tests, job, collector.DIAGNOSTIC_SCOPE)
            old_class, old_method = collector.DIAGNOSTIC_TEST.split("#")
            for body in (f'<testcase classname="{old_class}" name="{old_method}"/>',
                         case + f'<testcase classname="{old_class}" name="{old_method}"/>',
                         case + case):
                xml.write_text('<testsuite>' + body + '</testsuite>')
                capture["junit"] = collector.junit_summary(root, collector.QUALIFICATION_TEST)
                with self.subTest(body=body), self.assertRaises(ValueError):
                    publisher.diagnostic_result(capture, tests, job, collector.QUALIFICATION_SCOPE)

    def test_control_status_shell_selects_only_its_fixed_case_and_preserves_failure(self):
        self.assertEqual("dev.agentos.app.OwnedControlWindowInstrumentedTest#ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow",
                         collector.CONTROL_STATUS_TEST)
        self.check_test_exit("33", 33, scope=collector.CONTROL_STATUS_SCOPE)

    def test_control_status_scope_requires_its_exact_manual_title_and_matching_capture(self):
        run = source_run()
        run["display_title"] = publisher.CONTROL_STATUS_TITLE
        self.assertEqual("full", publisher.android_execution_scope(run))
        run["event"] = "workflow_dispatch"
        self.assertEqual(collector.CONTROL_STATUS_SCOPE, publisher.android_execution_scope(run))
        run["display_title"] += " --class Other"
        with self.assertRaises(ValueError):
            publisher.android_execution_scope(run)
        for record in ({}, {"executionScope": collector.DIAGNOSTIC_SCOPE,
                            "testSelection": collector.DIAGNOSTIC_TEST},
                       {"executionScope": collector.CONTROL_STATUS_SCOPE,
                        "testSelection": collector.DIAGNOSTIC_TEST}):
            with self.subTest(record=record), self.assertRaises(ValueError):
                collector.verify_capture_scope(record, collector.CONTROL_STATUS_SCOPE)
        self.assertEqual(collector.DIAGNOSTIC_TEST, collector.test_selection(collector.DIAGNOSTIC_SCOPE))
        self.assertEqual("full-suite", collector.test_selection("full"))

    def test_control_status_finalize_preserves_scope_without_reports_and_cannot_relabel(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            command = ["python3", str(SCRIPTS / "collect_android_evidence.py"), "finalize",
                       "--matrix-label", "33", "--scope", collector.CONTROL_STATUS_SCOPE]
            subprocess.run(command, cwd=root, check=True, capture_output=True, text=True)
            path = root / "test-evidence/api-33/capture.json"
            original = path.read_bytes()
            record = json.loads(original)
            self.assertEqual(collector.CONTROL_STATUS_TEST, record["testSelection"])
            self.assertEqual(collector.CONTROL_STATUS_TEST, record["junit"]["selection"])
            self.assertEqual(0, record["junit"]["selectedTests"])
            for scope in ("full", collector.DIAGNOSTIC_SCOPE, collector.QUALIFICATION_SCOPE):
                result = subprocess.run(command[:-1] + [scope], cwd=root, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(original, path.read_bytes())

    def test_control_status_archive_keeps_all_missing_and_mixed_lanes_in_its_eight_case_scope(self):
        for lanes, counts in (({}, {"passed": 0, "failed": 0, "unverified": 8}),
                              ({"30": "passed", "33": "failed", "37.0": "skipped"},
                               {"passed": 1, "failed": 1, "unverified": 6})):
            with self.subTest(lanes=lanes):
                manifest, readme = self.diagnostic_archive(lanes, collector.CONTROL_STATUS_SCOPE)
                self.assertEqual(collector.CONTROL_STATUS_SCOPE, manifest["executionScope"])
                self.assertEqual(collector.CONTROL_STATUS_TEST, manifest["testSelection"])
                self.assertEqual((1, 8), (manifest["expectedTestsPerApi"], manifest["expectedTests"]))
                self.assertEqual(8, len(manifest["apis"]))
                self.assertEqual(counts, manifest["diagnosticCaseCounts"])
                self.assertFalse(manifest["fullSuiteAcceptance"])
                self.assertIn("# Control Status Diagnostic Evidence", readme)
                self.assertIn(collector.CONTROL_STATUS_TEST, readme)
                self.assertIn("not counted as passed", readme)
                self.assertNotIn(collector.DIAGNOSTIC_TEST, readme)

    def test_control_status_archive_rejects_other_scope_and_wrong_manifest_case(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.diagnostic_archive({"33": "passed"}, collector.CONTROL_STATUS_SCOPE,
                                    artifact_scope=collector.DIAGNOSTIC_SCOPE)
        with self.assertRaises(subprocess.CalledProcessError):
            self.diagnostic_archive({"33": "passed"}, collector.CONTROL_STATUS_SCOPE,
                                    manifest_selection=collector.DIAGNOSTIC_TEST)

    def test_control_status_original_xml_is_required_and_cannot_mix_cases_or_borrow_old_selection(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            test_class, method = collector.CONTROL_STATUS_TEST.split("#")
            xml = root / "result.xml"
            case = f'<testcase classname="{test_class}" name="{method}"/>'
            xml.write_text('<testsuite>' + case + '</testsuite>')
            capture = {"junit": collector.junit_summary(root, collector.CONTROL_STATUS_TEST), "gradleExitCode": 0}
            tests = [{"testClass": test_class, "testName": method, "testResult": "pass"}]
            job = {"conclusion": "success"}
            self.assertEqual("passed", publisher.diagnostic_result(capture, tests, job, collector.CONTROL_STATUS_SCOPE))
            self.assertEqual("unverified", publisher.diagnostic_result(capture, [], job, collector.CONTROL_STATUS_SCOPE))
            with self.assertRaises(ValueError):
                publisher.diagnostic_result(capture, tests, job, collector.DIAGNOSTIC_SCOPE)
            old_class, old_method = collector.DIAGNOSTIC_TEST.split("#")
            for body in (f'<testcase classname="{old_class}" name="{old_method}"/>',
                         case + f'<testcase classname="{old_class}" name="{old_method}"/>',
                         case + case):
                xml.write_text('<testsuite>' + body + '</testsuite>')
                capture["junit"] = collector.junit_summary(root, collector.CONTROL_STATUS_TEST)
                with self.subTest(body=body), self.assertRaises(ValueError):
                    publisher.diagnostic_result(capture, tests, job, collector.CONTROL_STATUS_SCOPE)

    def test_screen_off_shell_selects_only_its_fixed_case_and_preserves_failure(self):
        self.assertEqual("dev.agentos.app.AgentControllerInstrumentedTest#actualScreenOffStopsPendingApprovalAndWakeDoesNotResumeIt",
                         collector.SCREEN_OFF_TEST)
        self.check_test_exit("33", 33, scope=collector.SCREEN_OFF_SCOPE)

    def test_screen_off_scope_requires_its_exact_manual_title_and_matching_capture(self):
        run = source_run()
        run["display_title"] = publisher.SCREEN_OFF_TITLE
        self.assertEqual("full", publisher.android_execution_scope(run))
        run["event"] = "workflow_dispatch"
        self.assertEqual(collector.SCREEN_OFF_SCOPE, publisher.android_execution_scope(run))
        run["display_title"] += " --class Other"
        with self.assertRaises(ValueError):
            publisher.android_execution_scope(run)
        for record in ({}, {"executionScope": collector.DIAGNOSTIC_SCOPE,
                            "testSelection": collector.DIAGNOSTIC_TEST},
                       {"executionScope": collector.SCREEN_OFF_SCOPE,
                        "testSelection": collector.DIAGNOSTIC_TEST}):
            with self.subTest(record=record), self.assertRaises(ValueError):
                collector.verify_capture_scope(record, collector.SCREEN_OFF_SCOPE)
        self.assertEqual(collector.DIAGNOSTIC_TEST, collector.test_selection(collector.DIAGNOSTIC_SCOPE))
        self.assertEqual("full-suite", collector.test_selection("full"))

    def test_screen_off_finalize_preserves_scope_without_reports_and_cannot_relabel(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            command = ["python3", str(SCRIPTS / "collect_android_evidence.py"), "finalize",
                       "--matrix-label", "33", "--scope", collector.SCREEN_OFF_SCOPE]
            subprocess.run(command, cwd=root, check=True, capture_output=True, text=True)
            path = root / "test-evidence/api-33/capture.json"
            original = path.read_bytes()
            record = json.loads(original)
            self.assertEqual(collector.SCREEN_OFF_TEST, record["testSelection"])
            self.assertEqual(collector.SCREEN_OFF_TEST, record["junit"]["selection"])
            self.assertEqual(0, record["junit"]["selectedTests"])
            for scope in ("full", collector.DIAGNOSTIC_SCOPE, collector.QUALIFICATION_SCOPE, collector.CONTROL_STATUS_SCOPE):
                result = subprocess.run(command[:-1] + [scope], cwd=root, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(original, path.read_bytes())

    def test_screen_off_archive_keeps_all_missing_and_mixed_lanes_in_its_eight_case_scope(self):
        for lanes, counts in (({}, {"passed": 0, "failed": 0, "unverified": 8}),
                              ({"30": "passed", "33": "failed", "37.0": "skipped"},
                               {"passed": 1, "failed": 1, "unverified": 6})):
            with self.subTest(lanes=lanes):
                manifest, readme = self.diagnostic_archive(lanes, collector.SCREEN_OFF_SCOPE)
                self.assertEqual(collector.SCREEN_OFF_SCOPE, manifest["executionScope"])
                self.assertEqual(collector.SCREEN_OFF_TEST, manifest["testSelection"])
                self.assertEqual((1, 8), (manifest["expectedTestsPerApi"], manifest["expectedTests"]))
                self.assertEqual(8, len(manifest["apis"]))
                self.assertEqual(counts, manifest["diagnosticCaseCounts"])
                self.assertFalse(manifest["fullSuiteAcceptance"])
                self.assertIn("# Screen Off Diagnostic Evidence", readme)
                self.assertIn(collector.SCREEN_OFF_TEST, readme)
                self.assertIn("not counted as passed", readme)
                self.assertNotIn(collector.DIAGNOSTIC_TEST, readme)

    def test_screen_off_archive_rejects_other_scope_and_wrong_manifest_case(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.diagnostic_archive({"33": "passed"}, collector.SCREEN_OFF_SCOPE,
                                    artifact_scope=collector.DIAGNOSTIC_SCOPE)
        with self.assertRaises(subprocess.CalledProcessError):
            self.diagnostic_archive({"33": "passed"}, collector.SCREEN_OFF_SCOPE,
                                    manifest_selection=collector.DIAGNOSTIC_TEST)

    def test_screen_off_original_xml_is_required_and_cannot_mix_cases_or_borrow_old_selection(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            test_class, method = collector.SCREEN_OFF_TEST.split("#")
            xml = root / "result.xml"
            case = f'<testcase classname="{test_class}" name="{method}"/>'
            xml.write_text('<testsuite>' + case + '</testsuite>')
            capture = {"junit": collector.junit_summary(root, collector.SCREEN_OFF_TEST), "gradleExitCode": 0}
            tests = [{"testClass": test_class, "testName": method, "testResult": "pass"}]
            job = {"conclusion": "success"}
            self.assertEqual("passed", publisher.diagnostic_result(capture, tests, job, collector.SCREEN_OFF_SCOPE))
            self.assertEqual("unverified", publisher.diagnostic_result(capture, [], job, collector.SCREEN_OFF_SCOPE))
            with self.assertRaises(ValueError):
                publisher.diagnostic_result(capture, tests, job, collector.DIAGNOSTIC_SCOPE)
            old_class, old_method = collector.DIAGNOSTIC_TEST.split("#")
            for body in (f'<testcase classname="{old_class}" name="{old_method}"/>',
                         case + f'<testcase classname="{old_class}" name="{old_method}"/>',
                         case + case):
                xml.write_text('<testsuite>' + body + '</testsuite>')
                capture["junit"] = collector.junit_summary(root, collector.SCREEN_OFF_TEST)
                with self.subTest(body=body), self.assertRaises(ValueError):
                    publisher.diagnostic_result(capture, tests, job, collector.SCREEN_OFF_SCOPE)

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
