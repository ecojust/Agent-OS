"""Parser/archive boundary tests; tiny PNGs are synthetic parser data, not UI evidence."""

import base64
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import third_party_smoke_archive as archive


PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ9kAAAAASUVORK5CYII=")
PNG_SHA = hashlib.sha256(PNG).hexdigest()


def source_run():
    return {"id": 123, "run_attempt": 2, "repository": {"id": 42, "full_name": "ecojust/Agent-OS"},
            "head_repository": {"id": 42, "full_name": "ecojust/Agent-OS"}, "event": "workflow_dispatch",
            "head_branch": "master", "head_sha": "a" * 40, "status": "completed", "conclusion": "success",
            "path": ".github/workflows/third-party-smoke.yml", "run_started_at": "2026-10-27T09:00:00Z",
            "updated_at": "2026-10-27T09:10:00Z"}


def sample():
    return {"schemaVersion": 1, "kind": "third-party-smoke-lock", "sampleId": "calculator-1.4.0",
            "app": {"package": "org.fossify.math", "versionName": "1.4.0", "versionCode": "10",
                    "sha256": "b" * 64, "certificateSha256": "c" * 64}, "apis": [30, 35, 37],
            "scope": {"deviceKind": "dedicated_emulator", "states": ["off", "idle"]}}


def artifact(label):
    return {"id": 100 + int(label.split(".")[0]),
            "name": f"third-party-smoke-api-{label}-attempt-2", "expired": False, "size_in_bytes": 4096,
            "created_at": "2026-10-27T09:05:00Z",
            "workflow_run": {"id": 123, "head_sha": "a" * 40, "head_branch": "master",
                             "repository_id": 42, "head_repository_id": 42}}


def report(label):
    identity = archive.verify_source(source_run())
    states = []
    for name in archive.STATES:
        service = "disabled" if name == "off" else "bound_idle"
        states.append({"state": name, "status": "passed", "serviceState": service, "installedIdentityVerified": True,
                       "serviceObservations": [{"stage": stage, "state": service, "verified": True}
                                               for stage in ("before", "after")],
                       "steps": [{"name": key, "status": "passed"} for key in archive.CORE_STEPS],
                       "result": {"expected": "5", "actual": "5", "resourceId": "org.fossify.math:id/result"},
                       "captures": [{"stage": stage, "status": "captured", "path": f"frames/{name}-{stage}.png",
                                     "sha256": PNG_SHA, "bytes": len(PNG)} for stage in ("before", "after")]})
    return {"schemaVersion": 1, "kind": "third-party-app-smoke", "source": identity,
            "device": {"apiLevel": int(label.split(".")[0]), "serial": "emulator-5554"},
            "app": sample()["app"], "actualInstalledIdentityVerified": True, "status": "passed", "states": states}


class ThirdPartyArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="third-party-archive-test-", dir="/tmp")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.incoming = self.root / "incoming"
        self.incoming.mkdir()
        self.output = "screenshots/third-party-123-2"

    def lane(self, label="30", value=None):
        data = copy.deepcopy(value if value is not None else report(label))
        directory = self.incoming / artifact(label)["name"]
        directory.mkdir()
        for state in data["states"]:
            for capture in state["captures"]:
                if capture["status"] == "captured" and ".." not in capture["path"] and not capture["path"].startswith("/"):
                    target = directory / capture["path"]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(PNG)
        (directory / "report.json").write_text(json.dumps(data))
        return directory

    def run_archive(self, labels=("30",), run=None, lock=None, index=None):
        return archive.archive(self.incoming, self.output, run or source_run(),
                               index if index is not None else {"artifacts": [artifact(label) for label in labels]},
                               lock or sample(), self.root)

    def assert_rejected(self, message=None, **kwargs):
        with self.assertRaises(ValueError) as error:
            self.run_archive(**kwargs)
        if message:
            self.assertIn(message, str(error.exception))
        self.assertFalse((self.root / self.output).exists())
        return error

    def test_completed_failed_source_is_valid_provenance(self):
        run = source_run()
        run["conclusion"] = "failure"
        self.assertEqual({"repository": "ecojust/Agent-OS", "runId": 123, "attempt": 2, "commit": "a" * 40},
                         archive.verify_source(run))

    def test_foreign_incomplete_and_wrong_workflow_sources_are_rejected(self):
        changes = (("event", "pull_request"), ("head_branch", "feature"), ("status", "in_progress"),
                   ("path", ".github/workflows/android.yml"), ("head_sha", "not-a-sha"),
                   ("run_attempt", True), ("id", "123"), ("conclusion", None))
        for key, value in changes:
            with self.subTest(field=key):
                run = source_run()
                run[key] = value
                with self.assertRaises(ValueError):
                    archive.verify_source(run)
        run = source_run()
        run["head_repository"]["id"] = 43
        with self.assertRaisesRegex(ValueError, "fork"):
            archive.verify_source(run)

    def test_source_requires_ordered_timezone_aware_timestamps(self):
        for value in ("2026-10-27T08:00:00Z", "2026-10-27T09:10:00", "not-a-time"):
            with self.subTest(value=value):
                run = source_run()
                run["updated_at"] = value
                with self.assertRaises(ValueError):
                    archive.verify_source(run)

    def test_old_attempt_artifacts_are_not_selected(self):
        old = artifact("30")
        old["name"] = "third-party-smoke-api-30-attempt-1"
        result = archive.eligible_artifacts({"artifacts": [old, artifact("37.0")]}, source_run())
        self.assertEqual(["37.0"], list(result))

    def test_artifact_origin_size_timestamp_and_duplicates_are_rejected(self):
        bad_values = []
        for key, value in (("id", False), ("size_in_bytes", archive.MAX_TOTAL_BYTES + 1),
                           ("expired", "false"), ("created_at", "2026-10-27T08:59:59Z")):
            item = artifact("30")
            item[key] = value
            bad_values.append(item)
        for key, value in (("id", 124), ("head_sha", "d" * 40), ("repository_id", 43),
                           ("head_repository_id", 43), ("head_branch", "other")):
            item = artifact("30")
            item["workflow_run"][key] = value
            bad_values.append(item)
        for item in bad_values:
            with self.subTest(item=item):
                with self.assertRaises(ValueError):
                    archive.eligible_artifacts({"artifacts": [item]}, source_run())
        with self.assertRaisesRegex(ValueError, "duplicate"):
            archive.eligible_artifacts({"artifacts": [artifact("30"), artifact("30")]}, source_run())

    def test_all_three_lanes_are_copied_and_independently_hashed(self):
        for label in archive.LABELS:
            self.lane(label)
        result = self.run_archive(labels=archive.LABELS)
        self.assertTrue(result["allProbeStatesPassed"])
        self.assertEqual({"passed": 6, "failed": 0, "unverified": 0}, result["stateCounts"])
        self.assertEqual(12, result["screenshotCount"])
        total = count = 0
        for lane in result["apis"]:
            for item in lane["files"]:
                data = (self.root / self.output / ("api-" + lane["matrixLabel"]) / item["path"]).read_bytes()
                self.assertEqual(len(data), item["bytes"])
                self.assertEqual(hashlib.sha256(data).hexdigest(), item["sha256"])
                total += len(data)
                count += 1
        self.assertEqual((count, total), (result["fileCount"], result["totalBytes"]))
        self.assertFalse(result["scope"]["releaseAccepted"])
        self.assertFalse(result["scope"]["executingStateValidated"])
        self.assertFalse(result["scope"]["physicalDeviceValidated"])
        self.assertFalse(result["scope"]["upgradeValidated"])

    def test_missing_lanes_remain_in_fixed_denominator(self):
        self.lane("30")
        result = self.run_archive()
        self.assertEqual({"passed": 2, "failed": 0, "unverified": 4}, result["stateCounts"])
        self.assertFalse(result["allProbeStatesPassed"])
        self.assertEqual(3, len(result["apis"]))
        self.assertEqual("environment_failed", result["apis"][2]["status"])

    def test_expired_artifact_is_unverified_without_reusing_downloaded_files(self):
        self.lane()
        expired = artifact("30")
        expired["expired"] = True
        result = self.run_archive(index={"artifacts": [expired]})
        self.assertEqual({"passed": 0, "failed": 0, "unverified": 6}, result["stateCounts"])
        self.assertEqual(0, result["fileCount"])
        self.assertEqual("current_attempt_artifact_expired", result["apis"][0]["reason"])

    def test_reported_passes_do_not_hide_failed_source_run(self):
        for label in archive.LABELS:
            self.lane(label)
        run = source_run()
        run["conclusion"] = "failure"
        result = self.run_archive(labels=archive.LABELS, run=run)
        self.assertEqual(6, result["stateCounts"]["passed"])
        self.assertFalse(result["allProbeStatesPassed"])
        self.assertEqual("failure", result["sourceRun"]["conclusion"])

    def test_protected_captures_are_preserved_separately_from_test_success(self):
        value = report("30")
        for state in value["states"]:
            state["captures"] = [{"stage": stage, "status": "blocked", "reason": "protected_window"}
                                 for stage in ("before", "after")]
        self.lane(value=value)
        result = self.run_archive()
        self.assertEqual(2, result["stateCounts"]["passed"])
        self.assertEqual(0, result["screenshotCount"])
        self.assertEqual({"blocked": 4}, result["apis"][0]["captureCounts"])

    def test_device_environment_failure_is_retained_without_fake_api_or_passes(self):
        value = report("30")
        value.update(status="environment_failed", actualInstalledIdentityVerified=False, states=[])
        value["device"] = {"apiLevel": None, "serial": ""}
        value["reason"] = "emulator_never_started"
        self.lane(value=value)
        result = self.run_archive()
        self.assertEqual(6, result["stateCounts"]["unverified"])
        self.assertEqual("emulator_never_started", result["apis"][0]["reason"])

    def test_failed_and_unverified_states_are_not_relabelled_as_passes(self):
        value = report("30")
        value["status"] = "failed"
        value["states"][0]["status"] = "failed"
        value["states"][0]["result"]["actual"] = "7"
        value["states"][0]["steps"][1]["status"] = "failed"
        value["states"][1]["status"] = "unverified"
        self.lane(value=value)
        result = self.run_archive()
        self.assertEqual({"passed": 0, "failed": 1, "unverified": 5}, result["stateCounts"])

    def test_changed_report_source_api_or_app_is_rejected_before_copy(self):
        values = []
        for group, key, changed in (("source", "commit", "d" * 40), ("source", "attempt", 1),
                                    ("source", "attempt", True),
                                    ("device", "apiLevel", 35), ("app", "versionCode", "11"),
                                    ("app", "certificateSha256", "d" * 64), ("app", "sha256", "e" * 64)):
            value = report("30")
            value[group][key] = changed
            values.append(value)
        directory = self.lane()
        for value in values:
            with self.subTest(value=value):
                (directory / "report.json").write_text(json.dumps(value))
                self.assert_rejected()

    def test_success_needs_actual_identity_real_emulator_and_two_service_observations(self):
        mutations = [lambda v: v.update(actualInstalledIdentityVerified=False),
                     lambda v: v["device"].update(serial="physical-phone"),
                     lambda v: v["device"].update(apiLevel=None),
                     lambda v: v["states"][1].update(serviceState="enabled_setting_only"),
                     lambda v: v["states"][1]["serviceObservations"].pop(),
                     lambda v: v["states"][1]["serviceObservations"][1].update(verified=1),
                     lambda v: v["states"][1]["serviceObservations"][1].update(state="disabled")]
        directory = self.lane()
        for mutate in mutations:
            value = report("30")
            mutate(value)
            (directory / "report.json").write_text(json.dumps(value))
            self.assert_rejected()

    def test_success_requires_independent_exact_result_and_core_steps(self):
        mutations = [lambda v: v["states"][0]["result"].update(actual="5.0"),
                     lambda v: v["states"][0]["result"].update(actual=5),
                     lambda v: v["states"][0]["result"].update(resourceId="other:id/result"),
                     lambda v: v["states"][0]["steps"].pop(),
                     lambda v: v["states"][0]["steps"].append({"name": "calculator_result", "status": "passed"}),
                     lambda v: v["states"][0]["steps"][0].update(status="unverified"),
                     lambda v: v["states"].pop(),
                     lambda v: v["states"][0].update(captures=[])]
        directory = self.lane()
        for mutate in mutations:
            value = report("30")
            mutate(value)
            (directory / "report.json").write_text(json.dumps(value))
            self.assert_rejected()

    def test_capture_digest_size_and_reuse_are_rejected(self):
        mutations = [lambda c: c[0].update(sha256="d" * 64),
                     lambda c: c[0].update(bytes=len(PNG) + 1),
                     lambda c: c[1].update(path=c[0]["path"]),
                     lambda c: c[0].update(status="blocked", reason="secure")]
        directory = self.lane()
        for mutate in mutations:
            value = report("30")
            mutate(value["states"][0]["captures"])
            (directory / "report.json").write_text(json.dumps(value))
            self.assert_rejected()

    def test_capture_path_traversal_and_unreferenced_images_are_rejected(self):
        directory = self.lane()
        value = report("30")
        value["states"][0]["captures"][0]["path"] = "../outside.png"
        (directory / "report.json").write_text(json.dumps(value))
        self.assert_rejected("escapes")
        (directory / "report.json").write_text(json.dumps(report("30")))
        (directory / "unused.png").write_bytes(PNG)
        self.assert_rejected("unreferenced")

    def test_linked_artifact_directories_or_files_are_rejected(self):
        directory = self.lane()
        outside = self.root / "outside.json"
        outside.write_text("{}")
        (directory / "linked.json").symlink_to(outside)
        self.assert_rejected("symlinks")
        (directory / "linked.json").unlink()
        linked = self.incoming / "other"
        directory.rename(linked)
        directory.symlink_to(linked, target_is_directory=True)
        self.assert_rejected("not downloaded")

    def test_unexpected_file_types_and_invalid_png_are_rejected(self):
        directory = self.lane()
        (directory / "run.sh").write_text("echo not-executed")
        self.assert_rejected("file type")
        (directory / "run.sh").unlink()
        (directory / "frames/off-before.png").write_bytes(b"not-a-png")
        self.assert_rejected("PNG")

    def test_archive_budget_failure_leaves_no_partial_output(self):
        directory = self.lane()
        actual_bytes = sum(path.stat().st_size for path in directory.rglob("*") if path.is_file())
        with patch.object(archive, "MAX_TOTAL_BYTES", actual_bytes - 1):
            item = artifact("30")
            item["size_in_bytes"] = 1
            self.assert_rejected("budget", index={"artifacts": [item]})

    def test_existing_output_is_not_overwritten(self):
        self.lane()
        path = self.root / self.output
        path.mkdir(parents=True)
        (path / "original").write_text("preserve")
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.run_archive()
        self.assertEqual("preserve", (path / "original").read_text())

    def test_output_escape_and_linked_screenshots_root_are_rejected(self):
        for output in ("../outside", "/tmp/outside", "screenshots/wrong-run"):
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, "source run"):
                archive.archive(self.incoming, output, source_run(), {"artifacts": []}, sample(), self.root)
        (self.root / "elsewhere").mkdir()
        (self.root / "screenshots").symlink_to(self.root / "elsewhere", target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "linked"):
            self.run_archive(labels=())

    def test_json_rejects_duplicate_keys_nonfinite_numbers_and_excessive_depth(self):
        for data in (b'{"x":1,"x":2}', b'{"x":NaN}', b'[' * 34 + b'0' + b']' * 34):
            with self.subTest(data=data), self.assertRaises(ValueError):
                archive.json_data(data)

    def test_source_validation_outputs_only_verified_download_ids(self):
        run_file, index_file = self.root / "source.json", self.root / "index.json"
        run_file.write_text(json.dumps(source_run()))
        expired = artifact("35")
        expired["expired"] = True
        index_file.write_text(json.dumps({"artifacts": [artifact("30"), expired]}))
        output = self.root / "github-output"
        with patch("builtins.print"):
            code = archive.main(["validate-source", "--source-run-json", str(run_file),
                                 "--artifact-index", str(index_file), "--github-output", str(output)])
        self.assertEqual(0, code)
        self.assertIn("artifactIds=130\n", output.read_text())
        self.assertIn("evidencePath=screenshots/third-party-123-2\n", output.read_text())
        self.assertFalse((self.root / "screenshots").exists())


if __name__ == "__main__":
    unittest.main()
