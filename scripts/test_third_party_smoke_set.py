"""Pure parser/archive fixtures, never device execution or screenshot evidence."""

import base64
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

import third_party_smoke as v1_probe
import third_party_smoke_archive as v1_archive
import third_party_smoke_set as probe
import third_party_smoke_set_archive as archive


LABELS = tuple(str(api) for api in range(30, 37)) + ("37.0",)
STATES = ("off", "idle")
SOURCE = {"repository": "ecojust/Agent-OS", "runId": 123, "attempt": 2, "commit": "a" * 40}
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ9kAAAAASUVORK5CYII=")
PNG_SHA = hashlib.sha256(PNG).hexdigest()


def source_run():
    return {"id": 123, "run_attempt": 2, "repository": {"id": 42, "full_name": SOURCE["repository"]},
            "head_repository": {"id": 42, "full_name": SOURCE["repository"]}, "event": "workflow_dispatch",
            "head_branch": "master", "head_sha": SOURCE["commit"], "status": "completed", "conclusion": "success",
            "path": ".github/workflows/third-party-smoke.yml", "run_started_at": "2026-10-27T09:00:00Z",
            "updated_at": "2026-10-27T09:10:00Z"}


def artifact(label, attempt=2, version=3):
    prefix = "third-party-smoke" if version == 1 else f"third-party-smoke-v{version}"
    return {"id": 200 + int(label.split(".")[0]), "name": f"{prefix}-api-{label}-attempt-{attempt}",
            "expired": False, "size_in_bytes": 4096, "created_at": "2026-10-27T09:05:00Z",
            "workflow_run": {"id": 123, "head_sha": SOURCE["commit"], "head_branch": "master",
                             "repository_id": 42, "head_repository_id": 42}}


def installed_identity(app):
    return {"package": app["package"], "versionName": app["versionName"], "versionCode": app["versionCode"],
            "certificateSha256": app["certificateSha256"], "apkSha256": app["sha256"],
            "minSdk": app["minSdk"], "targetSdk": app["targetSdk"], "uid": 10042}


def passed_state(entry, name, captures=True):
    app, app_id = entry["app"], entry["appId"]
    service = "disabled" if name == "off" else "bound_idle"
    if app["package"] == "org.fossify.math":
        check = "calculator_result"
        result = {"resourceId": "org.fossify.math:id/result", "expected": "5", "actual": "5"}
    elif app["package"] == "org.fossify.notes":
        check = "notes_persisted_result"
        body = f"Agent OS offline note ({name})\nSynthetic sample: 2 + 3 = 5.\nNo personal data."
        title = f"Agent OS {name} sample"
        result = {"resourceId": "org.fossify.notes:id/text_note_view", "expected": body, "actual": body,
                  "titleExpected": title, "titleActual": title,
                  "persistenceVerified": True, "reopenedInFreshProcess": True,
                  "automaticBackPolicy": "prohibited", "homeAttempts": 1}
    else:
        raise AssertionError("The fixture cannot manufacture a pass for an unknown app")
    steps = ("installed_identity", "service_state_before", check, "installed_identity_after",
             "service_state_after", "restore_service_settings")
    images = ([{"stage": "after", "status": "captured", "path": f"frames/{app_id}-{name}-after.png",
                "sha256": PNG_SHA, "bytes": len(PNG)}] if captures else
              [{"stage": "after", "status": "blocked", "reason": "synthetic_parser_fixture_no_device"}])
    return {"state": name, "status": "passed", "serviceState": service,
            "installedIdentityVerified": True, "installedIdentity": installed_identity(app),
            "serviceObservations": [{"stage": stage, "state": service, "verified": True} for stage in ("before", "after")],
            "serviceActionObservations": [{"stage": "after_interaction", "state": service, "verified": True}],
            "steps": [{"name": step, "status": "passed"} for step in steps],
            "captures": images, "result": result}


def ui_results(lock, label="35", passed=True, captures=False):
    apps = []
    for entry in lock["apps"]:
        apps.append({"appId": entry["appId"], "package": entry["app"]["package"],
                     "actualInstalledIdentityVerified": passed, "installedIdentityFailureSeen": False,
                     "states": [passed_state(entry, name, captures) if passed else
                                {"state": name, "status": "unverified", "steps": [], "captures": [],
                                 "reason": "synthetic probe did not execute"} for name in STATES]})
    api = int(label.split(".")[0])
    return {"schemaVersion": 3, "kind": "third-party-app-set-smoke", "sampleId": lock["sampleId"],
            "keyboardPolicy": probe.KEYBOARD_POLICY, "navigationPolicy": probe.NAVIGATION_POLICY,
            "lockSha256": probe.lock_digest(lock), "matrixLabel": label,
            "source": {"sha": SOURCE["commit"], "runId": 123, "attempt": 2,
                       "expectedApi": api, "deviceSerial": "emulator-5554"},
            "dedicatedEmulatorVerified": True, "device": {"sdkInt": api}, "apps": apps}


def report(lock, label="30", passed=True, captures=True):
    value = probe.empty_report(lock, SOURCE, label)
    if not passed:
        return value
    value["status"] = "passed"
    value["device"] = {"apiLevel": int(label.split(".")[0]), "serial": "emulator-5554", "abi": "x86_64"}
    for item, entry in zip(value["apps"], lock["apps"]):
        item.update(actualInstalledIdentityVerified=True, installedIdentityFailureSeen=False,
                    states=[passed_state(entry, name, captures) for name in STATES])
    return value


def ui_from_report(lock, value):
    """A complete raw UI fixture; absent report combinations remain explicitly unverified."""
    ui = ui_results(lock, value["matrixLabel"], passed=False)
    expected = {entry["appId"]: entry for entry in ui["apps"]}
    for app in value["apps"]:
        target = expected[app["appId"]]
        target["actualInstalledIdentityVerified"] = app["actualInstalledIdentityVerified"]
        target["installedIdentityFailureSeen"] = app["installedIdentityFailureSeen"]
        supplied = {state["state"]: state for state in app["states"]}
        target["states"] = [copy.deepcopy(supplied.get(state["state"], state)) for state in target["states"]]
    return ui


def notes_preparation_failure(lock, diagnostic=True):
    """Synthetic failed state with the bounded same-observation diagnostic schema."""
    value = report(lock, captures=False)
    value["status"] = "failed"
    failed = value["apps"][1]["states"][0]
    error = ("AssertionError: Notes v3 observation timed out: "
             "reason=notes_target_covered_by_verified_input_method "
             "automaticBackPolicy=prohibited imeSamples=0 lastImeSurface=NOT_SAMPLED")
    failed.update(status="failed", stage="create_note", error=error,
                  steps=[{"name": "create_note", "status": "failed", "details": error}],
                  captures=[{"stage": "failure", "status": "blocked",
                             "reason": "synthetic_parser_fixture_no_device"}],
                  result={"automaticBackPolicy": "prohibited", "homeAttempts": 0})
    if diagnostic:
        failed["notesPreparationDiagnostic"] = {
            "schemaVersion": 1, "snapshotKind": "LAST_OBSERVATION",
            "operationStage": "CONFIRM_CREATE", "resourceClass": "CONFIRM_BUTTON",
            "readPhase": "TARGET_CHECK", "appWindowId": 12,
            "appBounds": [0, 24, 320, 616], "displayId": 0,
            "displayBounds": [0, 0, 320, 640], "imeWindowId": 14,
            "imeBounds": [0, 390, 320, 640], "matchedCount": 1,
            "visibleMatchCount": 1, "matchScanComplete": True,
            "targetPresent": True, "targetBounds": [220, 400, 300, 448],
            "targetVisible": True, "targetEnabled": True, "targetWindowId": 12}
    return value


class ThirdPartySmokeSetTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="third-party-set-parser-", dir="/tmp")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.lock = probe.load_lock()

    def write_lock(self, value):
        path = self.root / "lock.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def assert_ui_rejected(self, value, label="35"):
        with self.assertRaises(ValueError):
            probe.validate_ui_results(value, SOURCE, int(label.split(".")[0]), "emulator-5554", self.lock)

    def test_v1_defaults_remain_six_states_and_v3_has_exactly_thirty_two(self):
        old = v1_probe.load_lock()
        self.assertEqual(1, old["schemaVersion"])
        self.assertEqual([30, 35, 37], old["apis"])
        self.assertEqual({"30": 30, "35": 35, "37.0": 37}, v1_probe.APIS)
        self.assertEqual(("30", "35", "37.0"), v1_archive.LABELS)
        self.assertEqual(3, self.lock["schemaVersion"])
        self.assertEqual("third-party-app-set-lock", self.lock["kind"])
        self.assertEqual("calculator-1.4.0-notes-1.7.0-v3", self.lock["sampleId"])
        self.assertEqual(list(range(30, 38)), self.lock["apis"])
        self.assertEqual({label: int(label.split(".")[0]) for label in LABELS}, probe.APIS)
        self.assertEqual(["off", "idle"], self.lock["scope"]["states"])
        self.assertFalse(self.lock["scope"]["releaseAcceptance"])
        apps = {entry["app"]["package"]: entry["app"] for entry in self.lock["apps"]}
        self.assertEqual({"org.fossify.math", "org.fossify.notes"}, set(apps))
        self.assertEqual(old["app"], apps["org.fossify.math"])
        notes = apps["org.fossify.notes"]
        self.assertEqual(("1.7.0", "13", 9528948), (notes["versionName"], notes["versionCode"], notes["sizeBytes"]))
        self.assertEqual("5a56e0e39cc488e1f3b947d3801006d3b7450ec73c67f03195c64c5fd3b6bced", notes["sha256"])
        self.assertEqual(old["app"]["certificateSha256"], notes["certificateSha256"])
        self.assertEqual("https://github.com/FossifyOrg/Notes/releases/download/1.7.0/notes-13-foss-release.apk", notes["url"])
        self.assertEqual((26, 36), (notes["minSdk"], notes["targetSdk"]))
        self.assertEqual(old["app"]["abis"], notes["abis"])

    def test_v3_contract_cannot_relabel_v2_or_change_keyboard_navigation_rules(self):
        for mutate in (lambda v: v.update(schemaVersion=2),
                       lambda v: v.update(sampleId="calculator-1.4.0-notes-1.7.0-v2"),
                       lambda v: v["scope"].pop("keyboardPolicy"),
                       lambda v: v["scope"].update(navigationPolicy="repeat-back")):
            value = copy.deepcopy(self.lock); mutate(value)
            with self.assertRaises(ValueError): probe.validate_lock(value)
        for key in ("keyboardPolicy", "navigationPolicy"):
            for changed in (None, "old-profile"):
                value = ui_results(self.lock); value[key] = changed
                self.assert_ui_rejected(value)
        for key, changed in (("automaticBackPolicy", "allowed"), ("automaticBackPolicy", False),
                             ("automaticBackPolicy", 0), ("automaticBackPolicy", None), ("homeAttempts", 0),
                             ("homeAttempts", 2), ("homeAttempts", True), ("homeAttempts", 1.0)):
            value = ui_results(self.lock); value["apps"][1]["states"][0]["result"][key] = changed
            self.assert_ui_rejected(value)
        value = ui_results(self.lock); value["apps"][1]["states"][0]["result"].pop("automaticBackPolicy")
        self.assert_ui_rejected(value)

    def test_archive_source_routing_keeps_v1_v2_v3_distinct(self):
        for version in (1, 2, 3):
            item = artifact("35", version=version)
            self.assertEqual({"35": item}, v1_archive.eligible_run_artifacts({"artifacts": [item]}, source_run()))
        for left, right in ((1, 2), (1, 3), (2, 3)):
            with self.assertRaises(ValueError):
                v1_archive.eligible_run_artifacts({"artifacts": [artifact("30", version=left),
                    artifact("35", version=right)]}, source_run())

    def test_lock_version_matrix_and_each_app_identity_are_frozen(self):
        changes = [lambda v: v.update(schemaVersion=1), lambda v: v.update(schemaVersion=3.0),
                   lambda v: v.update(schemaVersion="3"), lambda v: v.update(schemaVersion=True),
                   lambda v: v.update(kind="third-party-smoke-lock"), lambda v: v.update(sampleId="latest"),
                   lambda v: v["apis"].pop(), lambda v: v["apis"].append(38),
                   lambda v: v["apps"].pop(), lambda v: v["apps"].append(copy.deepcopy(v["apps"][0])),
                   lambda v: v["apps"][1].update(appId=v["apps"][0]["appId"]),
                   lambda v: v["scope"].update(states=["off", "executing"]),
                   lambda v: v["scope"].update(releaseAcceptance=True)]
        for index in (0, 1):
            for key, changed in (("package", "other.app"), ("versionName", "latest"), ("versionCode", 13),
                                 ("sha256", "d" * 64), ("certificateSha256", "e" * 64),
                                 ("url", "https://example.invalid/app.apk"), ("sizeBytes", 1), ("minSdk", 1)):
                changes.append(lambda v, index=index, key=key, changed=changed: v["apps"][index]["app"].update({key: changed}))
        for index, mutate in enumerate(changes):
            with self.subTest(variant=index):
                value = copy.deepcopy(self.lock)
                mutate(value)
                with self.assertRaises(ValueError):
                    probe.load_lock(self.write_lock(value))
        with self.assertRaises(ValueError):
            probe.load_lock(v1_probe.DEFAULT_SAMPLE)
        with self.assertRaises(ValueError):
            v1_probe.load_lock(self.write_lock(self.lock))

    def test_lock_digest_has_one_independent_canonical_json_definition(self):
        expected = hashlib.sha256(json.dumps(self.lock, sort_keys=True, ensure_ascii=True,
                                             separators=(",", ":")).encode("utf-8")).hexdigest()
        self.assertEqual(expected, probe.lock_digest(self.lock))
        reversed_keys = dict(reversed(list(self.lock.items())))
        self.assertEqual(expected, probe.lock_digest(reversed_keys))
        for mutate in (lambda v: v["apis"].pop(), lambda v: v["apps"][1]["app"].update(sha256="0" * 64),
                       lambda v: v["scope"].update(states=["off"]), lambda v: v.update(sampleId="changed")):
            altered = copy.deepcopy(self.lock)
            mutate(altered)
            altered_digest = hashlib.sha256(json.dumps(altered, sort_keys=True, ensure_ascii=True,
                                                      separators=(",", ":")).encode("utf-8")).hexdigest()
            self.assertNotEqual(expected, altered_digest)
            with self.assertRaises(ValueError):
                probe.lock_digest(altered)

    def test_empty_report_keeps_both_apps_and_both_unverified_states_in_each_api(self):
        count = 0
        for label in LABELS:
            value = probe.empty_report(self.lock, SOURCE, label)
            self.assertEqual((3, "third-party-app-set-smoke", label),
                             (value["schemaVersion"], value["kind"], value["matrixLabel"]))
            self.assertEqual(SOURCE, value["source"])
            self.assertEqual(self.lock["sampleId"], value["sampleId"])
            self.assertEqual(probe.lock_digest(self.lock), value["lockSha256"])
            self.assertEqual("environment_failed", value["status"])
            self.assertIsNone(value["device"]["apiLevel"])
            self.assertEqual(2, len(value["apps"]))
            for app, locked in zip(value["apps"], self.lock["apps"]):
                self.assertEqual(locked["appId"], app["appId"])
                self.assertEqual(locked["app"], app["app"])
                self.assertIs(False, app["actualInstalledIdentityVerified"])
                self.assertEqual(set(STATES), {state["state"] for state in app["states"]})
                self.assertEqual(2, len(app["states"]))
                self.assertTrue(all(state["status"] == "unverified" for state in app["states"]))
                count += len(app["states"])
        self.assertEqual(32, count)
        for label in ("37", "29", "38", "35.0"):
            with self.assertRaises(ValueError):
                probe.empty_report(self.lock, SOURCE, label)

    def test_valid_bound_ui_results_accept_both_independent_app_checks(self):
        for label in LABELS:
            value = ui_results(self.lock, label)
            apps = probe.validate_ui_results(value, SOURCE, int(label.split(".")[0]), "emulator-5554", self.lock)
            self.assertEqual(value["apps"], apps)
            self.assertEqual(4, sum(len(app["states"]) for app in apps))

    def test_ui_source_device_versions_and_duplicate_app_state_are_rejected(self):
        changes = [lambda v: v.update(schemaVersion=1), lambda v: v.update(schemaVersion=3.0),
                   lambda v: v.update(kind="third-party-app-smoke"),
                   lambda v: v.update(sampleId="another-sample"),
                   lambda v: v["source"].update(sha="b" * 40), lambda v: v["source"].update(attempt=1),
                   lambda v: v["source"].update(expectedApi=30), lambda v: v["source"].update(deviceSerial="emulator-5556"),
                   lambda v: v.update(dedicatedEmulatorVerified=False), lambda v: v["device"].update(sdkInt=30),
                   lambda v: v["apps"].pop(), lambda v: v["apps"].append(copy.deepcopy(v["apps"][0])),
                   lambda v: v["apps"][1].update(appId=v["apps"][0]["appId"]),
                   lambda v: v["apps"][1].update(package=v["apps"][0]["package"]),
                   lambda v: v["apps"][0]["states"].append(copy.deepcopy(v["apps"][0]["states"][0])),
                   lambda v: v["apps"][0]["states"][1].update(state="off")]
        for index, mutate in enumerate(changes):
            with self.subTest(variant=index):
                value = ui_results(self.lock)
                mutate(value)
                self.assert_ui_rejected(value)

    def test_passing_states_require_actual_installed_identity_and_service_checks(self):
        changes = [lambda v: v.update(installedIdentityVerified=False),
                   lambda v: v["installedIdentity"].update(uid=-1), lambda v: v["installedIdentity"].update(uid=True),
                   lambda v: v["installedIdentity"].update(versionCode=13),
                   lambda v: v["installedIdentity"].update(apkSha256="d" * 64),
                   lambda v: v["installedIdentity"].update(certificateSha256="e" * 64),
                   lambda v: v["installedIdentity"].update(package="other.app"),
                   lambda v: v["steps"].pop(), lambda v: v["steps"].append(copy.deepcopy(v["steps"][0])),
                   lambda v: v["steps"][0].update(status="unverified"),
                   lambda v: v["serviceObservations"][1].update(verified=1),
                   lambda v: v["serviceObservations"].pop(),
                   lambda v: v.update(serviceActionObservations=[]),
                   lambda v: v["serviceActionObservations"][0].update(verified=False),
                   lambda v: v["serviceActionObservations"][0].update(state="enabled_only")]
        for app_index in (0, 1):
            for index, mutate in enumerate(changes):
                with self.subTest(app=app_index, variant=index):
                    value = ui_results(self.lock)
                    mutate(value["apps"][app_index]["states"][0])
                    self.assert_ui_rejected(value)

    def test_notes_need_exact_persisted_text_title_and_fresh_process(self):
        changes = [lambda v: v.update(actual="wrong text"), lambda v: v.update(titleActual="wrong title"),
                   lambda v: v.update(resourceId="org.fossify.math:id/result"),
                   lambda v: v.update(persistenceVerified=False), lambda v: v.update(reopenedInFreshProcess=False),
                   lambda v: v.update(reopenedInFreshProcess=1), lambda v: v.update(persistenceVerified=1)]
        notes_index = next(i for i, entry in enumerate(self.lock["apps"]) if entry["app"]["package"] == "org.fossify.notes")
        for index, mutate in enumerate(changes):
            with self.subTest(variant=index):
                value = ui_results(self.lock)
                mutate(value["apps"][notes_index]["states"][0]["result"])
                self.assert_ui_rejected(value)
        value = ui_results(self.lock)
        notes = value["apps"][notes_index]["states"][0]
        notes["result"] = {"resourceId": "org.fossify.math:id/result", "expected": "5", "actual": "5"}
        self.assert_ui_rejected(value)

    def test_later_identity_failure_preserves_earlier_independently_verified_state(self):
        value = ui_results(self.lock)
        app = value["apps"][1]
        app["installedIdentityFailureSeen"] = True
        failed = app["states"][1]
        failed.update(status="failed", installedIdentityVerified=False)
        failed["installedIdentity"]["certificateSha256"] = "0" * 64
        failed["steps"][0]["status"] = "failed"
        apps = probe.validate_ui_results(value, SOURCE, 35, "emulator-5554", self.lock)
        self.assertEqual("passed", apps[1]["states"][0]["status"])
        self.assertEqual("failed", apps[1]["states"][1]["status"])
        self.assertIs(True, apps[1]["installedIdentityFailureSeen"])
        for bad_flag in (1, "true", None):
            invalid = copy.deepcopy(value)
            invalid["apps"][1]["installedIdentityFailureSeen"] = bad_flag
            self.assert_ui_rejected(invalid)
        contradictory = ui_results(self.lock)
        contradictory["apps"][1]["installedIdentityFailureSeen"] = True
        self.assert_ui_rejected(contradictory)

    def test_report_write_cannot_replace_another_sample_lock_source_or_api(self):
        value = probe.empty_report(self.lock, SOURCE, "35")
        probe.write_report(self.root, value)
        for key, changed in (("schemaVersion", 1), ("kind", "third-party-app-smoke"),
                             ("sampleId", "other"), ("lockSha256", "d" * 64),
                             ("matrixLabel", "30"), ("source", dict(SOURCE, attempt=1))):
            with self.subTest(field=key):
                invalid = copy.deepcopy(value)
                invalid[key] = changed
                with self.assertRaises(ValueError):
                    probe.write_report(self.root, invalid)
                self.assertEqual(value, json.loads((self.root / "report.json").read_text()))


class ThirdPartySmokeSetArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="third-party-set-archive-", dir="/tmp")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.incoming = self.root / "incoming"
        self.incoming.mkdir()
        self.output = "screenshots/third-party-123-2"
        self.lock = probe.load_lock()

    def lane(self, label="30", value=None):
        value = copy.deepcopy(report(self.lock, label) if value is None else value)
        directory = self.incoming / artifact(label)["name"]
        directory.mkdir(exist_ok=True)
        for app in value["apps"]:
            for state in app["states"]:
                for capture in state.get("captures", []):
                    if capture["status"] == "captured":
                        target = directory / capture["path"]
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(PNG)
        self.write_ui(directory, ui_from_report(self.lock, value))
        self.write_report(directory, value)
        return directory

    @staticmethod
    def write_report(directory, value):
        value = copy.deepcopy(value)
        raw_ui = directory / "ui-results.json"
        if "uiResultsSha256" not in value and raw_ui.is_file():
            value["uiResultsSha256"] = hashlib.sha256(raw_ui.read_bytes()).hexdigest()
        (directory / "report.json").write_text(json.dumps(value), encoding="utf-8")

    @staticmethod
    def write_ui(directory, value):
        raw = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        (directory / "ui-results.json").write_bytes(raw)
        return hashlib.sha256(raw).hexdigest()

    def run_archive(self, labels=("30",), run=None, index=None, lock=None):
        return archive.archive(self.incoming, self.output, source_run() if run is None else run,
                               {"artifacts": [artifact(label) for label in labels]} if index is None else index,
                               self.lock if lock is None else lock, self.root)

    def assert_rejected(self, **kwargs):
        with self.assertRaises(ValueError):
            self.run_archive(**kwargs)
        self.assertFalse((self.root / self.output).exists())

    def test_no_artifacts_retains_all_thirty_two_unverified_combinations(self):
        result = self.run_archive(labels=())
        self.assertEqual(3, result["schemaVersion"])
        self.assertEqual({"passed": 0, "failed": 0, "unverified": 32}, result["stateCounts"])
        self.assertEqual(32, result["expectedCombinationCount"])
        self.assertEqual(8, len(result["apis"]))
        self.assertEqual(list(LABELS), [lane["matrixLabel"] for lane in result["apis"]])
        for lane in result["apis"]:
            self.assertEqual({entry["appId"]: {state: "unverified" for state in STATES}
                              for entry in self.lock["apps"]}, lane["apps"])
        self.assertFalse(result["allProbeStatesPassed"])
        self.assertEqual(0, result["fileCount"])

    def test_full_matrix_archives_real_file_hashes_and_keeps_scope_unaccepted(self):
        for label in LABELS:
            self.lane(label)
        result = self.run_archive(labels=LABELS)
        self.assertEqual({"passed": 32, "failed": 0, "unverified": 0}, result["stateCounts"])
        self.assertEqual(32, result["expectedCombinationCount"])
        self.assertTrue(result["allProbeStatesPassed"])
        self.assertEqual(32, result["screenshotCount"])
        count = size = 0
        for lane in result["apis"]:
            self.assertEqual({entry["appId"]: {state: "passed" for state in STATES}
                              for entry in self.lock["apps"]}, lane["apps"])
            copied = self.root / self.output / ("api-" + lane["matrixLabel"])
            copied_report = json.loads((copied / "report.json").read_bytes())
            self.assertEqual(hashlib.sha256((copied / "ui-results.json").read_bytes()).hexdigest(),
                             copied_report["uiResultsSha256"])
            for item in lane["files"]:
                data = (self.root / self.output / ("api-" + lane["matrixLabel"]) / item["path"]).read_bytes()
                self.assertEqual(len(data), item["bytes"])
                self.assertEqual(hashlib.sha256(data).hexdigest(), item["sha256"])
                count += 1
                size += len(data)
        self.assertEqual((count, size), (result["fileCount"], result["totalBytes"]))
        self.assertEqual(probe.lock_digest(self.lock), result["lockSha256"])
        for key in ("releaseAccepted", "executingStateValidated", "physicalDeviceValidated", "upgradeValidated"):
            self.assertIs(False, result["scope"][key])

    def test_missing_app_and_single_state_do_not_disappear_from_denominator(self):
        value = report(self.lock)
        value["status"] = "failed"
        value["apps"] = [value["apps"][0]]
        value["apps"][0]["states"] = [value["apps"][0]["states"][0]]
        self.lane(value=value)
        result = self.run_archive()
        self.assertEqual({"passed": 1, "failed": 0, "unverified": 31}, result["stateCounts"])
        self.assertEqual({self.lock["apps"][0]["appId"]: {"off": "passed", "idle": "unverified"},
                          self.lock["apps"][1]["appId"]: {"off": "unverified", "idle": "unverified"}},
                         result["apis"][0]["apps"])
        self.assertFalse(result["allProbeStatesPassed"])

    def test_one_complete_lane_is_four_passes_and_twenty_eight_unverified(self):
        self.lane()
        result = self.run_archive()
        self.assertEqual({"passed": 4, "failed": 0, "unverified": 28}, result["stateCounts"])
        self.assertFalse(result["allProbeStatesPassed"])

    def test_v1_old_attempt_and_expired_files_cannot_fill_v3_combinations(self):
        self.lane()
        # Current-attempt protocols must never be mixed or silently guessed.
        self.assert_rejected(index={"artifacts": [artifact("35", version=1)]})
        self.assert_rejected(index={"artifacts": [artifact("35", version=2)]})
        self.assert_rejected(index={"artifacts": [artifact("30"), artifact("35", version=2)]})
        self.assert_rejected(index={"artifacts": [artifact("30"), artifact("35", version=1)]})
        expired = artifact("30")
        expired["expired"] = True
        result = self.run_archive(index={"artifacts": [expired, artifact("31", attempt=1),
                                                      artifact("35", attempt=1, version=1)]})
        self.assertEqual({"passed": 0, "failed": 0, "unverified": 32}, result["stateCounts"])
        self.assertEqual(0, result["fileCount"])
        self.assertFalse(result["allProbeStatesPassed"])

    def test_current_verified_artifact_missing_download_is_not_silently_unverified(self):
        self.assert_rejected()

    def test_artifact_source_attempt_window_and_duplicate_identity_are_guarded(self):
        self.lane()
        mutations = [lambda v: v.update(id=True), lambda v: v.update(expired="false"),
                     lambda v: v.update(created_at="2026-10-27T08:59:59Z"),
                     lambda v: v.update(created_at="2026-10-27T09:10:01Z"),
                     lambda v: v["workflow_run"].update(id=124),
                     lambda v: v["workflow_run"].update(head_sha="b" * 40),
                     lambda v: v["workflow_run"].update(repository_id=43),
                     lambda v: v["workflow_run"].update(head_repository_id=43),
                     lambda v: v["workflow_run"].update(head_branch="other")]
        for index, mutate in enumerate(mutations):
            with self.subTest(variant=index):
                item = artifact("30")
                mutate(item)
                self.assert_rejected(index={"artifacts": [item]})
        self.assert_rejected(index={"artifacts": [artifact("30"), artifact("30")]})
        self.lane("31")
        reused = artifact("31")
        reused["id"] = artifact("30")["id"]
        self.assert_rejected(index={"artifacts": [artifact("30"), reused]})

    def test_schema_lane_sample_hash_and_locked_app_swaps_fail_before_copy(self):
        directory = self.lane()
        mutations = [lambda v: v.update(schemaVersion=1), lambda v: v.update(schemaVersion=3.0),
                     lambda v: v.update(kind="third-party-app-smoke"), lambda v: v.update(matrixLabel="31"),
                     lambda v: v.update(sampleId="another-sample"), lambda v: v.update(lockSha256="0" * 64),
                     lambda v: v["source"].update(attempt=1), lambda v: v["source"].update(commit="b" * 40),
                     lambda v: v["device"].update(apiLevel=31),
                     lambda v: v["apps"][1].update(appId=v["apps"][0]["appId"]),
                     lambda v: v["apps"][1].update(app=copy.deepcopy(v["apps"][0]["app"])),
                     lambda v: v["apps"][0]["app"].update(certificateSha256="d" * 64),
                     lambda v: v["apps"][1]["app"].update(sha256="e" * 64)]
        for index, mutate in enumerate(mutations):
            with self.subTest(variant=index):
                value = report(self.lock)
                mutate(value)
                self.write_report(directory, value)
                self.assert_rejected()

        original_ui = (directory / "ui-results.json").read_bytes()
        original_value = json.loads(original_ui)
        self.write_report(directory, report(self.lock))
        original_report = json.loads((directory / "report.json").read_bytes())
        for key in (None, "0" * 64):
            value = copy.deepcopy(original_report)
            value["uiResultsSha256"] = key
            self.write_report(directory, value)
            self.assert_rejected()
        without_digest = {key: value for key, value in original_report.items() if key != "uiResultsSha256"}
        (directory / "report.json").write_text(json.dumps(without_digest), encoding="utf-8")
        self.assert_rejected()
        self.write_report(directory, original_report)
        (directory / "ui-results.json").unlink()
        self.assert_rejected()
        (directory / "ui-results.json").write_bytes(original_ui + b" ")
        self.assert_rejected()  # Even whitespace changes raw bytes and must invalidate the saved hash.

        ui_changes = [lambda v: v.update(schemaVersion=1), lambda v: v.update(dedicatedEmulatorVerified=False),
                      lambda v: v["source"].update(sha="b" * 40), lambda v: v["source"].update(attempt=1),
                      lambda v: v["device"].update(sdkInt=31),
                      lambda v: v["apps"][0]["states"][0]["installedIdentity"].update(uid=10043),
                      lambda v: v["apps"][0]["states"][0].update(status="failed")]
        for index, mutate in enumerate(ui_changes):
            with self.subTest(raw_ui_variant=index):
                changed = copy.deepcopy(original_value)
                mutate(changed)
                self.write_ui(directory, changed)
                # Recompute the raw hash: provenance/assertion comparison must still reject it.
                self.write_report(directory, report(self.lock))
                self.assert_rejected()
        (directory / "ui-results.json").write_bytes(original_ui)
        changed_report = report(self.lock)
        changed_report["apps"][0]["states"][0]["extraAssertion"] = "not present in the raw UI result"
        self.write_report(directory, changed_report)
        self.assert_rejected()

    def test_self_consistent_changed_lock_and_report_hash_still_cannot_replace_reviewed_sample(self):
        changed = copy.deepcopy(self.lock)
        changed["apps"][1]["app"]["sha256"] = "0" * 64
        value = report(self.lock)
        value["apps"][1]["app"] = copy.deepcopy(changed["apps"][1]["app"])
        value["lockSha256"] = hashlib.sha256(json.dumps(changed, sort_keys=True, ensure_ascii=True,
                                                       separators=(",", ":")).encode("utf-8")).hexdigest()
        self.lane(value=value)
        self.assert_rejected(lock=changed)

    def test_failed_identity_in_later_state_is_archived_without_erasing_prior_success(self):
        value = report(self.lock, captures=False)
        value["status"] = "failed"
        app = value["apps"][1]
        app["installedIdentityFailureSeen"] = True
        failed = app["states"][1]
        failed.update(status="failed", installedIdentityVerified=False)
        failed["installedIdentity"]["certificateSha256"] = "0" * 64
        failed["steps"][0]["status"] = "failed"
        self.lane(value=value)
        result = self.run_archive()
        self.assertEqual({"passed": 3, "failed": 1, "unverified": 28}, result["stateCounts"])
        self.assertFalse(result["allProbeStatesPassed"])

    def test_failed_notes_diagnostic_error_and_existing_instrumentation_are_preserved(self):
        value = notes_preparation_failure(self.lock)
        directory = self.lane(value=value)
        output = ("Synthetic parser fixture failure\n" + value["apps"][1]["states"][0]["error"] + "\n" +
                  "at dev.agentos.compat.SyntheticParserFixture.sameRead(Fixture.java:1)\n" * 40 +
                  "FAILURES!!!\nTests run: 4, Failures: 1\nsynthetic-complete-output-end\n")
        self.assertGreater(len(output), 1024)
        (directory / "instrumentation.json").write_text(
            json.dumps({"commandCompleted": True, "output": output}) + "\n", encoding="utf-8")
        originals = {path.name: path.read_bytes() for path in directory.iterdir()}
        run = source_run()
        run["conclusion"] = "failure"
        result = self.run_archive(run=run)
        self.assertEqual({"passed": 3, "failed": 1, "unverified": 28}, result["stateCounts"])
        self.assertEqual(32, result["expectedCombinationCount"])
        self.assertFalse(result["allProbeStatesPassed"])
        self.assertEqual("failure", result["sourceRun"]["conclusion"])
        self.assertEqual(0, result["screenshotCount"])
        self.assertEqual({"blocked": 4}, result["apis"][0]["captureCounts"])
        copied = self.root / self.output / "api-30"
        files = {item["path"]: item for item in result["apis"][0]["files"]}
        self.assertEqual({"report.json", "ui-results.json", "instrumentation.json"}, set(files))
        for name, raw in originals.items():
            with self.subTest(file=name):
                self.assertEqual(raw, (copied / name).read_bytes())
                self.assertEqual(len(raw), files[name]["bytes"])
                self.assertEqual(hashlib.sha256(raw).hexdigest(), files[name]["sha256"])
        for name in ("report.json", "ui-results.json"):
            failed = json.loads((copied / name).read_bytes())["apps"][1]["states"][0]
            self.assertEqual(value["apps"][1]["states"][0], failed)
        self.assertEqual(output, json.loads((copied / "instrumentation.json").read_bytes())["output"])

    def test_failed_notes_report_cannot_alter_remove_or_truncate_raw_diagnostic(self):
        directory = self.lane(value=notes_preparation_failure(self.lock))
        original_ui = (directory / "ui-results.json").read_bytes()
        original_report = json.loads((directory / "report.json").read_bytes())
        changes = {
            "alter": lambda state: state["notesPreparationDiagnostic"].update(targetVisible=False),
            "remove": lambda state: state.pop("notesPreparationDiagnostic"),
            "truncate": lambda state: state["notesPreparationDiagnostic"].update(targetBounds=[220, 400, 300]),
            "error": lambda state: state.update(error=state["error"][:-1])}
        for name, change in changes.items():
            with self.subTest(change=name):
                changed = copy.deepcopy(original_report)
                change(changed["apps"][1]["states"][0])
                self.write_report(directory, changed)
                with self.assertRaisesRegex(ValueError, "Report assertions differ from collected UI result"):
                    self.run_archive()
                self.assertFalse((self.root / self.output).exists())
                self.assertEqual(original_ui, (directory / "ui-results.json").read_bytes())

    def test_failed_notes_without_optional_diagnostic_remains_failed(self):
        value = notes_preparation_failure(self.lock, diagnostic=False)
        self.lane(value=value)
        result = self.run_archive()
        self.assertEqual({"passed": 3, "failed": 1, "unverified": 28}, result["stateCounts"])
        self.assertEqual(32, result["expectedCombinationCount"])
        self.assertFalse(result["allProbeStatesPassed"])
        self.assertEqual("failed", result["apis"][0]["apps"]["notes-1.7.0"]["off"])
        for name in ("report.json", "ui-results.json"):
            failed = json.loads((self.root / self.output / "api-30" / name).read_bytes())["apps"][1]["states"][0]
            self.assertNotIn("notesPreparationDiagnostic", failed)
            self.assertEqual(value["apps"][1]["states"][0], failed)

    def test_capture_reuse_across_apps_digest_drift_and_unreferenced_files_are_rejected(self):
        directory = self.lane()
        mutations = [lambda v: v["apps"][1]["states"][0]["captures"][0].update(
                        path=v["apps"][0]["states"][0]["captures"][0]["path"]),
                     lambda v: v["apps"][0]["states"][0]["captures"][0].update(sha256="d" * 64),
                     lambda v: v["apps"][0]["states"][0]["captures"][0].update(bytes=len(PNG) + 1),
                     lambda v: v["apps"][0]["states"][0]["captures"][0].update(path="../outside.png"),
                     lambda v: v["apps"][0]["states"][0]["captures"].append(
                         {"stage": "invented_attempt", "status": "blocked", "reason": "synthetic_added_attempt"})]
        for index, mutate in enumerate(mutations):
            with self.subTest(variant=index):
                value = report(self.lock)
                mutate(value)
                self.write_report(directory, value)
                self.assert_rejected()
        original_ui = json.loads((directory / "ui-results.json").read_bytes())
        replacement = report(self.lock)
        capture = replacement["apps"][0]["states"][0]["captures"][0]
        original_path = directory / capture["path"]
        another_png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==")
        original_path.write_bytes(another_png)
        capture.update(sha256=hashlib.sha256(another_png).hexdigest(), bytes=len(another_png))
        self.write_report(directory, replacement)
        self.assert_rejected()  # The new file matches report metadata, but is not the UI-captured image.
        original_path.write_bytes(PNG)
        for status in ("blocked", "unavailable"):
            with self.subTest(original_capture=status):
                altered_ui = copy.deepcopy(original_ui)
                altered_ui["apps"][0]["states"][0]["captures"] = [
                    {"stage": "after", "status": status, "reason": "synthetic UI could not capture"}]
                self.write_ui(directory, altered_ui)
                self.write_report(directory, report(self.lock))
                self.assert_rejected()  # Cannot upgrade the raw UI capture by inventing a file.
        self.write_ui(directory, original_ui)
        original_path.unlink()
        invalid_downgrades = [
            ("passed", {"stage": "after", "status": "unavailable", "reason": "screenshot_collection_failed"}),
            ("failed", {"stage": "after", "status": "unavailable", "reason": "other_reason"}),
            ("failed", {"stage": "changed", "status": "unavailable", "reason": "screenshot_collection_failed"}),
            ("failed", {"stage": "after", "status": "unavailable", "reason": "screenshot_collection_failed", "detail": "x" * 1601}),
            ("failed", {"stage": "after", "status": "unavailable", "reason": "screenshot_collection_failed", "invented": True})]
        for overall_status, downgraded_capture in invalid_downgrades:
            downgraded_capture.setdefault("detail", "synthetic transfer failure")
            value = report(self.lock)
            value["status"] = overall_status
            value["apps"][0]["states"][0]["captures"] = [downgraded_capture]
            self.write_report(directory, value)
            self.assert_rejected()
        original_path.write_bytes(PNG)
        self.write_report(directory, report(self.lock))
        (directory / "unreferenced.png").write_bytes(PNG)
        self.assert_rejected()

    def test_failed_source_run_never_becomes_success_even_with_thirty_two_passed_states(self):
        for label in LABELS:
            self.lane(label)
        run = source_run()
        run["conclusion"] = "failure"
        result = self.run_archive(labels=LABELS, run=run)
        self.assertEqual(32, result["stateCounts"]["passed"])
        self.assertFalse(result["allProbeStatesPassed"])
        self.assertEqual("failure", result["sourceRun"]["conclusion"])

    def test_blocked_screens_keep_reasons_without_invented_files(self):
        self.lane(value=report(self.lock, captures=False))
        result = self.run_archive()
        self.assertEqual(4, result["stateCounts"]["passed"])
        self.assertEqual(0, result["screenshotCount"])
        self.assertEqual({"blocked": 4}, result["apis"][0]["captureCounts"])

        # A collection failure can downgrade captured -> unavailable while preserving every assertion.
        directory = self.lane("31", value=report(self.lock, "31"))
        downgraded = report(self.lock, "31")
        downgraded["status"] = "failed"
        capture = downgraded["apps"][0]["states"][0]["captures"][0]
        (directory / capture["path"]).unlink()
        capture.clear()
        capture.update(stage="after", status="unavailable", reason="screenshot_collection_failed",
                       detail="synthetic transfer failure")
        self.write_report(directory, downgraded)
        separate = self.root / "downgrade-archive"
        separate.mkdir()
        after = archive.archive(self.incoming, self.output, source_run(), {"artifacts": [artifact("31")]},
                                self.lock, separate)
        self.assertEqual({"passed": 4, "failed": 0, "unverified": 28}, after["stateCounts"])
        self.assertEqual(3, after["screenshotCount"])
        self.assertEqual({"captured": 3, "unavailable": 1}, after["apis"][1]["captureCounts"])
        self.assertFalse(after["allProbeStatesPassed"])


if __name__ == "__main__":
    unittest.main()
