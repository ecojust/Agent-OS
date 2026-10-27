import copy
import hashlib
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
import urllib.request

import third_party_smoke as probe
import third_party_smoke_archive as archive


SOURCE = {"repository": "ecojust/Agent-OS", "runId": 123, "attempt": 1, "commit": "a" * 40}


def environment():
    return {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": SOURCE["repository"],
            "GITHUB_REF": "refs/heads/master", "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_WORKFLOW_REF": "ecojust/Agent-OS/.github/workflows/third-party-smoke.yml@refs/heads/master",
            "GITHUB_SHA": SOURCE["commit"], "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1"}


def state(name):
    service = "disabled" if name == "off" else "bound_idle"
    return {"state": name, "status": "passed", "serviceState": service, "installedIdentityVerified": True,
            "serviceObservations": [{"stage": stage, "state": service, "verified": True} for stage in ("before", "after")],
            "steps": [{"name": step, "status": "passed"} for step in
                      ("service_state_before", "calculator_result", "service_state_after")],
            "captures": [{"stage": name + "-after", "status": "blocked", "reason": "synthetic_parser_test_no_device"}],
            "result": {"expected": "5", "actual": "5", "resourceId": probe.RESULT_ID}}


def ui_results():
    return {"schemaVersion": 1, "source": {"sha": SOURCE["commit"], "runId": 123, "attempt": 1,
                                          "expectedApi": 35, "deviceSerial": "emulator-5554"},
            "actualInstalledIdentityVerified": True, "states": [state("off"), state("idle")]}


def png_parser_bytes(width=100, height=100):
    # Header fixture for parser checks only; never published as a screenshot.
    return b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR" + struct.pack(">II", width, height) + b"\x08\x02\x00\x00\x00" + b"\x00" * 4


class Response(io.BytesIO):
    status = 200
    def __init__(self, data, length=None):
        super().__init__(data)
        self.headers = {} if length is None else {"Content-Length": str(length)}


class Opener:
    def __init__(self, data, length=None):
        self.data, self.length = data, length
    def open(self, request, timeout):
        return Response(self.data, self.length)


class ThirdPartySmokeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="agent-os-third-party-parser-")
        self.root = Path(self.temp.name)
        self.addCleanup(self.temp.cleanup)

    def test_reviewed_lock_matches_fixed_release(self):
        lock = probe.load_lock()
        self.assertEqual(lock["app"]["sha256"], probe.PINNED_SHA)
        self.assertFalse(lock["scope"]["releaseAcceptance"])

    def test_changed_lock_cannot_silently_expand_package_or_source(self):
        for key, value in (("package", "other.app"), ("url", "https://example.org/app.apk"),
                           ("certificateSha256", "b" * 64), ("versionName", "latest")):
            lock = probe.load_lock()
            lock["app"][key] = value
            path = self.root / "lock.json"
            path.write_text(json.dumps(lock))
            with self.assertRaises(probe.ProbeError):
                probe.load_lock(path)

    def test_only_manual_master_workflow_may_control_devices(self):
        self.assertEqual(probe.source_from_environment(environment()), SOURCE)
        for key, value in (("GITHUB_ACTIONS", "false"), ("GITHUB_REF", "refs/pull/1/merge"),
                           ("GITHUB_EVENT_NAME", "push"), ("GITHUB_REPOSITORY", "other/repo"),
                           ("GITHUB_SHA", "a" * 39), ("GITHUB_RUN_ATTEMPT", "0"),
                           ("GITHUB_WORKFLOW_REF", "ecojust/Agent-OS/.github/workflows/android.yml@refs/heads/master")):
            changed = environment()
            changed[key] = value
            with self.assertRaises(probe.ProbeError):
                probe.source_from_environment(changed)

    def test_physical_tcp_offline_and_multiple_devices_are_rejected(self):
        self.assertEqual(probe.select_emulator("List of devices attached\nemulator-5554\tdevice\n"), "emulator-5554")
        for line in ("012345\tdevice", "127.0.0.1:5555\tdevice", "emulator-5554\toffline", "",
                     "emulator-5554\tdevice\nemulator-5556\tdevice"):
            with self.assertRaises(probe.ProbeError):
                probe.select_emulator("List of devices attached\n" + line)

    def test_emulator_serial_alone_does_not_authorize_execution(self):
        props = {"ro.kernel.qemu": "1", "ro.boot.qemu": "1", "ro.build.version.sdk": "35",
                 "ro.product.cpu.abi": "x86_64", "sys.boot_completed": "1"}
        probe.validate_device("emulator-5554", props, 35)
        for key, value in (("ro.kernel.qemu", "0"), ("ro.boot.qemu", "0"), ("ro.build.version.sdk", "30"),
                           ("ro.product.cpu.abi", "arm64-v8a"), ("sys.boot_completed", "0")):
            changed = dict(props, **{key: value})
            with self.assertRaises(probe.ProbeError):
                probe.validate_device("emulator-5554", changed, 35)

    def test_signature_requires_success_and_exactly_one_signer(self):
        good = "Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: " + probe.PINNED_CERT + "\n"
        self.assertEqual(probe.parse_signature(good), probe.PINNED_CERT)
        for bad in (good.replace("Verifies", "DOES NOT VERIFY"), good.replace("signers: 1", "signers: 2"),
                    good + "Signer #2 certificate SHA-256 digest: " + "a" * 64):
            with self.assertRaises(probe.ProbeError):
                probe.parse_signature(bad)

    def test_api_37_requires_a_supported_actual_emulator_tool(self):
        self.assertEqual(probe.validate_emulator_version("Pkg.Revision=37.2.11\n", 37), "37.2.11")
        probe.validate_emulator_version("Pkg.Revision=35.1.9\n", 35)
        for properties in ("Pkg.Revision=37.2.10\n", "Pkg.Revision=36.9.99\n", "",
                           "Pkg.Revision=37.2.11\nPkg.Revision=37.2.12\n"):
            with self.assertRaises(probe.ProbeError):
                probe.validate_emulator_version(properties, 37)

    def test_actual_apk_metadata_is_not_inferred_from_filename(self):
        text = "package: name='org.fossify.math' versionCode='10' versionName='1.4.0'\nsdkVersion:'26'\ntargetSdkVersion:'36'\nnative-code: 'x86_64' 'arm64-v8a'\n"
        parsed = probe.parse_badging(text)
        self.assertEqual(parsed["abis"], ["arm64-v8a", "x86_64"])
        self.assertEqual(parsed["minSdk"], 26)
        for bad in (text + "sdkVersion:'35'\n", text + "package: name='other.app' versionCode='1' versionName='x'\n",
                    text.replace("'x86_64' 'arm64-v8a'", "'x86_64' 'x86_64'")):
            with self.assertRaises(probe.ProbeError):
                probe.parse_badging(bad)

    def test_file_size_and_hash_are_both_checked(self):
        data = b"synthetic APK transport test"
        apk = self.root / "test.apk"
        apk.write_bytes(data)
        app = {"sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        probe.validate_apk_bytes(apk, app)
        apk.write_bytes(b"x" * len(data))
        with self.assertRaises(probe.ProbeError):
            probe.validate_apk_bytes(apk, app)
        apk.write_bytes(data + b"x")
        with self.assertRaises(probe.ProbeError):
            probe.validate_apk_bytes(apk, app)

    def test_download_finishes_only_after_verified_size_and_digest(self):
        data = b"synthetic transport data"
        app = {"url": "https://github.com/test", "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        output = self.root / "package.apk"
        probe.download_apk(app, output, Opener(data, len(data)))
        self.assertEqual(output.read_bytes(), data)
        with self.assertRaises(probe.ProbeError):
            probe.download_apk(app, output, Opener(data))

    def test_failed_download_does_not_leave_a_completed_apk(self):
        data = b"synthetic transport data"
        app = {"url": "https://github.com/test", "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        for payload, length in ((data[:-1], None), (data + b"x", None), (b"x" * len(data), None), (data, 9000)):
            output = self.root / "failed.apk"
            with self.assertRaises(probe.ProbeError):
                probe.download_apk(app, output, Opener(payload, length))
            self.assertFalse(output.exists())
            self.assertEqual(list(self.root.iterdir()), [])

    def test_download_rejects_tls_downgrade_and_unrelated_hosts(self):
        handler = probe.ReleaseRedirects()
        request = urllib.request.Request("https://github.com/example")
        for target in ("http://release-assets.githubusercontent.com/x", "https://example.org/a.apk", "https://u:p@github.com/x",
                       "https://github.com:444/x", "https://github.com.attacker.example/x"):
            with self.assertRaises(probe.ProbeError):
                handler.redirect_request(request, None, 302, "Found", {}, target)
        result = handler.redirect_request(request, None, 302, "Found", {}, "https://release-assets.githubusercontent.com/x")
        self.assertEqual(result.full_url, "https://release-assets.githubusercontent.com/x")

    def test_reports_cannot_replace_another_source_or_api(self):
        report = probe.empty_report(probe.load_lock(), SOURCE, "35")
        probe.write_report(self.root, report)
        for changed in (dict(report, matrixLabel="30"), dict(report, source=dict(SOURCE, attempt=2))):
            with self.assertRaises(probe.ProbeError):
                probe.write_report(self.root, changed)
        self.assertEqual(probe.read_json(self.root / "report.json"), report)

    def test_json_duplicate_keys_constants_and_deep_nesting_are_rejected(self):
        for data in (b'{"state":"passed","state":"failed"}', b'{"value":NaN}', b'[' * 35 + b'0' + b']' * 35):
            with self.assertRaises(probe.ProbeError):
                probe.parse_json(data)

    def test_only_bound_ui_results_are_accepted(self):
        value = ui_results()
        self.assertEqual(len(probe.validate_ui_results(value, SOURCE, 35, "emulator-5554")), 2)
        for key, changed in (("sha", "b" * 40), ("attempt", 2), ("expectedApi", 30), ("deviceSerial", "emulator-5556")):
            bad = ui_results()
            bad["source"][key] = changed
            with self.assertRaises(probe.ProbeError):
                probe.validate_ui_results(bad, SOURCE, 35, "emulator-5554")

    def test_passing_requires_an_independent_exact_result_and_installed_identity(self):
        for mutation in (lambda x: x.update(actualInstalledIdentityVerified=False),
                         lambda x: x["states"][0].update(installedIdentityVerified=False),
                         lambda x: x["states"][0]["result"].update(actual="0"),
                         lambda x: x["states"][0]["result"].update(resourceId="other:id/result")):
            bad = ui_results()
            mutation(bad)
            with self.assertRaises(probe.ProbeError):
                probe.validate_ui_results(bad, SOURCE, 35, "emulator-5554")

    def test_services_must_be_proven_before_and_after_not_just_enabled(self):
        for mutation in (lambda x: x["states"][1].update(serviceState="enabled"),
                         lambda x: x["states"][1]["serviceObservations"][1].update(verified=False),
                         lambda x: x["states"][1]["serviceObservations"].pop(),
                         lambda x: x["states"][1]["steps"].pop()):
            bad = ui_results()
            mutation(bad)
            with self.assertRaises(probe.ProbeError):
                probe.validate_ui_results(bad, SOURCE, 35, "emulator-5554")

    def test_duplicate_or_missing_states_do_not_shrink_the_sample(self):
        for states in ([state("off")], [state("off"), state("off")], [state("off"), state("executing")]):
            bad = dict(ui_results(), states=states)
            with self.assertRaises(probe.ProbeError):
                probe.validate_ui_results(bad, SOURCE, 35, "emulator-5554")

    def test_protected_images_are_not_replaced_or_counted_as_missing_tests(self):
        good = ui_results()
        probe.validate_ui_results(good, SOURCE, 35, "emulator-5554")
        good["states"][0]["captures"][0]["path"] = "replacement.png"
        with self.assertRaises(probe.ProbeError):
            probe.validate_ui_results(good, SOURCE, 35, "emulator-5554")

    def test_capture_paths_must_stay_bounded_and_normalized(self):
        self.assertEqual(str(probe.validate_relative_file("captures/off-before.png")), "captures/off-before.png")
        for path in ("../outside.png", "/tmp/x.png", "a/../x.png", "a//x.png", "a/b/c.png", "x;touch.png", "x.txt", "a\\x.png"):
            with self.assertRaises(probe.ProbeError):
                probe.validate_relative_file(path)

    def test_collected_image_requires_declared_identity_and_format(self):
        # Parser-only bytes in a temporary directory, never device screenshot evidence.
        data = png_parser_bytes()
        capture = {"path": "off.png", "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
        probe.collect_capture(data, self.root, capture)
        self.assertEqual((self.root / "off.png").read_bytes(), data)
        with self.assertRaises(probe.ProbeError):
            probe.collect_capture(data, self.root, capture)
        with self.assertRaises(probe.ProbeError):
            probe.collect_capture(data[:-1] + b"x", self.root, dict(capture, path="new.png"))

    def test_failed_capture_transfer_preserves_results_without_dangling_image_references(self):
        states = [state("off"), state("idle")]
        data = png_parser_bytes()
        for entry in states:
            entry["captures"] = [{"stage": "result", "status": "captured", "path": entry["state"] + ".png",
                                  "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}]
        def fetch(path):
            if path == "idle.png":
                raise probe.ProbeError("Disconnected synthetic test transport")
            return data
        errors = probe.collect_captures(states, self.root, fetch)
        self.assertEqual(len(errors), 1)
        self.assertEqual(errors[0]["state"], "idle")
        self.assertTrue((self.root / "off.png").is_file())
        self.assertFalse((self.root / "idle.png").exists())
        self.assertEqual(states[1]["status"], "passed")
        self.assertEqual(states[1]["captures"][0]["status"], "unavailable")
        self.assertNotIn("path", states[1]["captures"][0])
        probe.validate_ui_results(dict(ui_results(), states=states), SOURCE, 35, "emulator-5554")

    def test_malformed_or_oversized_png_dimensions_are_not_collected(self):
        for data in (b"\x89PNG\r\n\x1a\n" + b"fake PNG", png_parser_bytes(0, 100), png_parser_bytes(4097, 100)):
            capture = {"path": "bad.png", "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
            with self.assertRaises(probe.ProbeError):
                probe.collect_capture(data, self.root, capture)
            self.assertFalse((self.root / "bad.png").exists())

    def test_driver_results_with_partial_capture_failure_remain_archivable(self):
        lock = probe.load_lock()
        report = probe.empty_report(lock, SOURCE, "35")
        report.update(device={"serial": "emulator-5554", "apiLevel": 35},
                      status="failed", actualInstalledIdentityVerified=True, states=ui_results()["states"])
        data = png_parser_bytes()
        for entry in report["states"]:
            entry["captures"] = [{"stage": "result", "status": "captured", "path": entry["state"] + ".png",
                                  "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}]
        def fetch(path):
            return data if path == "off.png" else b"truncated"
        report["captureCollectionErrors"] = probe.collect_captures(report["states"], self.root, fetch)
        probe.write_report(self.root, report)
        (self.root / "instrumentation.json").write_text(json.dumps({"commandCompleted": True, "output": "OK (2 tests)"}))
        (self.root / "ui-results.json").write_text(json.dumps(ui_results()))
        files = archive.scan_files(self.root, {"files": 0, "bytes": 0})
        state_map, counts = archive.validate_report(report, "35", SOURCE, archive.validate_sample(lock), files)
        self.assertEqual({entry["status"] for entry in state_map.values()}, {"passed"})
        self.assertEqual(counts, {"captured": 1, "unavailable": 1})
        self.assertEqual(report["status"], "failed")

    def test_completed_first_state_does_not_disappear_when_second_state_is_unexecuted(self):
        value = ui_results()
        value["states"][1] = probe.unverified_state("idle", "Synthetic parser runner interruption")
        probe.validate_ui_results(value, SOURCE, 35, "emulator-5554")
        lock = probe.load_lock()
        report = probe.empty_report(lock, SOURCE, "35")
        report.update(device={"serial": "emulator-5554", "apiLevel": 35}, status="failed",
                      actualInstalledIdentityVerified=True, states=value["states"])
        states, _ = archive.validate_report(report, "35", SOURCE, archive.validate_sample(lock), {})
        self.assertEqual(states["off"]["status"], "passed")
        self.assertEqual(states["idle"]["status"], "unverified")

    def test_other_state_identity_failure_cannot_discard_prior_independently_verified_success(self):
        value = ui_results()
        value["states"][1] = dict(probe.unverified_state("idle", "Synthetic identity failure"), status="failed",
                                  installedIdentityVerified=False)
        value["installedIdentityFailureSeen"] = True
        probe.validate_ui_results(value, SOURCE, 35, "emulator-5554")
        lock = probe.load_lock()
        report = probe.empty_report(lock, SOURCE, "35")
        report.update(device={"serial": "emulator-5554", "apiLevel": 35}, status="failed",
                      actualInstalledIdentityVerified=True, states=value["states"])
        states, _ = archive.validate_report(report, "35", SOURCE, archive.validate_sample(lock), {})
        self.assertEqual(states["off"]["status"], "passed")
        self.assertEqual(states["idle"]["status"], "failed")

    def test_linked_output_directories_and_inputs_are_rejected(self):
        real = self.root / "real"
        real.mkdir()
        link = self.root / "link"
        link.symlink_to(real, target_is_directory=True)
        with self.assertRaises(probe.ProbeError):
            probe.ensure_directory(link / "child")
        (real / "lock.json").write_text("{}")
        file_link = self.root / "linked.json"
        file_link.symlink_to(real / "lock.json")
        with self.assertRaises(probe.ProbeError):
            probe.read_json(file_link)


if __name__ == "__main__":
    unittest.main()
