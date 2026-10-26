"""Synthetic records exercise accounting/integrity, never third-party app acceptance."""

import base64
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stderr

import app_compatibility as compat


PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ9kAAAAASUVORK5CYII=")
FROZEN_AT = "2026-01-01T00:00:00Z"
TESTED_AT = "2026-01-02T00:00:00Z"


def sample(count=2):
    apps = []
    for index in range(count):
        apps.append({"package": "dev.example.synthetic.app" + str(index), "version_name": "fixture-only",
                     "version_code": "1", "certificate_sha256": "0" * 64,
                     "source": {"url": "https://example.invalid/synthetic", "channel": "synthetic-test-only",
                                "authorization": "Unit fixture, not a real application or distribution claim"},
                     "category": "synthetic", "ordinary": index == 0, "critical": index == 0,
                     "dependencies": [] if index == 0 else ["synthetic account service"],
                     "flows": [{"id": "install", "description": "Synthetic install/update flow",
                                "dimension": "installation", "capture_policy": "nonvisual", "states": list(compat.STATES)},
                               {"id": "daily", "description": "Synthetic daily flow",
                                "dimension": "daily", "capture_policy": "nonvisual", "states": list(compat.STATES)},
                               {"id": "agent", "description": "Synthetic automation flow",
                                "dimension": "agent", "capture_policy": "nonvisual", "states": ["executing"]}]})
    return {"schema_version": 1, "sample_id": "synthetic-unit-test", "evidence_kind": "synthetic",
            "frozen_by": "unit test", "environment": {"device_model": "synthetic; no device tested",
            "device_kind": "emulator", "android_api": 30, "build_fingerprint": "synthetic",
            "agent_version": "synthetic", "model_id": "synthetic", "region": "synthetic", "services": "synthetic",
            "network": "wifi", "physical_sim": False, "enabled_esim": False}, "apps": apps}


class AppCompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "screenshots" / "unit").mkdir(parents=True)
        self.report_ref = self.artifact("report.txt", b"Synthetic parser unit fixture. No app or device was tested.")
        self.image_ref = self.artifact("screen.png", PNG)
        self.frozen = compat.freeze(sample(), FROZEN_AT)

    def artifact(self, name, data):
        path = self.root / "screenshots" / "unit" / name
        path.write_bytes(data)
        return {"path": path.relative_to(self.root).as_posix(), "sha256": hashlib.sha256(data).hexdigest()}

    def result(self, app=0, flow="daily", state="off", status="passed", identifier=None):
        item = self.frozen["sample"]["apps"][app]
        return {"id": identifier or f"{app}-{flow}-{state}", "package": item["package"],
                "version_name": item["version_name"], "version_code": item["version_code"],
                "flow_id": flow, "state": state, "status": status, "tested_at": TESTED_AT,
                "operator": "unit test", "method": "synthetic record validation only", "notes": "No real device tested",
                "dependency_status": {dep: "met" for dep in item["dependencies"]},
                "evidence": {"capture_status": "nonvisual", "reason": "Synthetic nonvisual parser test",
                             "screenshots": [], "reports": [self.report_ref]}}

    def batch(self, results):
        return {"schema_version": 1, "sample_sha256": self.frozen["sample_sha256"],
                "evidence_kind": self.frozen["sample"]["evidence_kind"],
                "environment": copy.deepcopy(self.frozen["sample"]["environment"]), "results": results}

    def ledger(self, results, old=None):
        return compat.import_results(self.frozen, self.batch(results), old, self.root)

    def all_results(self, include_agent=True):
        return [self.result(index, flow["id"], state) for index, app in enumerate(self.frozen["sample"]["apps"])
                for flow in app["flows"] if include_agent or flow["dimension"] != "agent" for state in flow["states"]]

    def test_freeze_hash_detects_removed_apps_and_changed_groups(self):
        for change in (lambda f: f["sample"]["apps"].pop(),
                       lambda f: f["sample"]["apps"][0].update(critical=False)):
            frozen = copy.deepcopy(self.frozen)
            change(frozen)
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                compat.validate_frozen(frozen)

    def test_samples_require_exact_versions_sources_groups_and_three_states(self):
        for change in (lambda s: s["apps"][0].pop("critical"),
                       lambda s: s["apps"][0].update(version_name=""),
                       lambda s: s["apps"][0]["source"].update(authorization=""),
                       lambda s: s["apps"][0]["flows"][0].update(states=["off"]),
                       lambda s: s["environment"].update(enabled_esim=True),
                       lambda s: s["apps"].append(copy.deepcopy(s["apps"][0]))):
            value = sample()
            change(value)
            with self.assertRaises(ValueError):
                compat.freeze(value)

    def test_unrun_apps_and_empty_groups_never_pass(self):
        report = compat.summarize(self.frozen)
        self.assertEqual(2, report["groups"]["complete"]["denominator"])
        self.assertEqual(2, report["groups"]["complete"]["counts"]["unverified"])
        self.assertEqual(1, report["groups"]["critical"]["denominator"])
        self.assertEqual(7, len(report["apps"][0]["missing_results"]))
        self.assertFalse(report["ready_for_human_gate_review"])
        self.assertFalse(report["release_accepted"])
        empty = sample(1)
        empty["apps"][0].update(ordinary=False, critical=False)
        report = compat.summarize(compat.freeze(empty, FROZEN_AT))
        self.assertIsNone(report["groups"]["critical"]["pass_rate"])
        self.assertFalse(report["gate_checks"]["critical_100_percent"])

    def test_partial_results_keep_full_denominator(self):
        report = compat.summarize(self.frozen, self.ledger([self.result()]), self.root)
        self.assertEqual({"passed": 0, "limited": 1, "failed": 0, "unverified": 1}, report["groups"]["complete"]["counts"])
        self.assertEqual(0, report["groups"]["ordinary"]["pass_rate"])
        self.assertEqual(2, report["by_agent_state"]["idle"]["counts"]["unverified"])

    def test_agent_failure_is_separate_from_normal_app_use(self):
        results = self.all_results()
        results[-1]["status"] = "failed"
        report = compat.summarize(self.frozen, self.ledger(results), self.root)
        self.assertEqual(2, report["groups"]["complete"]["counts"]["passed"])
        self.assertEqual(1, report["groups"]["agent_operation"]["counts"]["failed"])
        self.assertEqual(2, report["by_dimension"]["installation"]["counts"]["passed"])

    def test_missing_dependencies_stay_in_complete_and_special_groups(self):
        result = self.result(app=1, status="unverified")
        result["dependency_status"]["synthetic account service"] = "missing"
        report = compat.summarize(self.frozen, self.ledger([result]), self.root)
        self.assertEqual(2, report["groups"]["complete"]["denominator"])
        self.assertEqual(1, report["groups"]["special_dependencies"]["counts"]["unverified"])
        self.assertEqual(["synthetic account service"], report["apps"][1]["unmet_or_unknown_dependencies"])
        result["status"] = "passed"
        with self.assertRaisesRegex(ValueError, "dependencies cannot pass"):
            self.ledger([result])

    def test_all_declared_dependencies_must_be_reported(self):
        result = self.result(app=1)
        result["dependency_status"] = {}
        with self.assertRaisesRegex(ValueError, "every frozen dependency"):
            self.ledger([result])

    def test_environment_version_package_and_sample_mismatches_are_rejected(self):
        for field, value in (("version_name", "other"), ("version_code", "2"), ("package", "other.package"),
                             ("flow_id", "unknown"), ("state", "unknown"), ("tested_at", "2025-01-01T00:00:00Z")):
            result = self.result()
            result[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.ledger([result])
        batch = self.batch([self.result()])
        batch["environment"]["build_fingerprint"] = "different"
        with self.assertRaisesRegex(ValueError, "configuration mismatch"):
            compat.import_results(self.frozen, batch, root=self.root)
        batch = self.batch([self.result()])
        batch["sample_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "sample mismatch"):
            compat.import_results(self.frozen, batch, root=self.root)

    def test_explicit_retests_preserve_failed_attempts(self):
        old = self.ledger([self.result(status="failed", identifier="first")])
        retry = self.result(identifier="second")
        with self.assertRaisesRegex(ValueError, "explicitly supersede"):
            self.ledger([retry], old)
        retry["supersedes"] = "first"
        new = self.ledger([retry], old)
        self.assertEqual("failed", new["results"][0]["status"])
        self.assertEqual(1, len(old["results"]))
        report = compat.summarize(self.frozen, new, self.root)
        self.assertEqual((2, 1), (report["attempts"], report["current_results"]))
        self.assertEqual("limited", report["apps"][0]["status"])

    def test_duplicate_ids_and_wrong_supersedes_are_rejected(self):
        old = self.ledger([self.result(identifier="first")])
        for identifier, supersedes in (("first", "first"), ("second", "unknown")):
            retry = self.result(identifier=identifier)
            retry["supersedes"] = supersedes
            with self.assertRaises(ValueError):
                self.ledger([retry], old)

    def test_tampered_ledger_is_not_accepted(self):
        ledger = self.ledger([self.result(status="failed")])
        ledger["results"][0]["status"] = "passed"
        with self.assertRaisesRegex(ValueError, "ledger hash mismatch"):
            compat.summarize(self.frozen, ledger, self.root)

    def test_captured_screenshot_is_hashed_but_does_not_replace_report(self):
        result = self.result()
        result["evidence"].update(capture_status="captured", screenshots=[self.image_ref], reports=[])
        with self.assertRaisesRegex(ValueError, "need a report"):
            self.ledger([result])
        result["evidence"]["reports"] = [self.report_ref]
        self.ledger([result])
        (self.root / self.image_ref["path"]).write_bytes(PNG + b"changed")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            self.ledger([result])

    def test_protected_and_nonvisual_results_need_reports_not_fake_screenshots(self):
        for capture in ("protected", "nonvisual"):
            result = self.result()
            result["evidence"]["capture_status"] = capture
            self.ledger([result])
        for capture in ("unavailable", "not_collected"):
            result = self.result()
            result["evidence"].update(capture_status=capture, reports=[])
            with self.assertRaisesRegex(ValueError, "remain unverified"):
                self.ledger([result])
            result["status"] = "unverified"
            self.ledger([result])

    def test_ui_capture_policy_is_frozen_and_cannot_be_bypassed_as_nonvisual(self):
        data = sample()
        data["apps"][0]["flows"][1]["capture_policy"] = "ui"
        self.frozen = compat.freeze(data, FROZEN_AT)
        result = self.result()
        with self.assertRaisesRegex(ValueError, "frozen capture_policy"):
            self.ledger([result])
        result["evidence"].update(capture_status="protected", reason="Synthetic protected-window case; no real device tested")
        self.ledger([result])
        result["evidence"]["reports"] = []
        with self.assertRaisesRegex(ValueError, "need a report"):
            self.ledger([result])
        result["evidence"].update(capture_status="not_collected", reports=[self.report_ref])
        with self.assertRaisesRegex(ValueError, "remain unverified"):
            self.ledger([result])
        result["status"] = "unverified"
        self.ledger([result])
        result["status"] = "passed"
        result["evidence"].update(capture_status="captured", screenshots=[self.image_ref])
        self.ledger([result])

    def test_capture_policy_must_be_declared_before_freeze(self):
        for value in (None, "optional", []):
            data = sample()
            data["apps"][0]["flows"][0]["capture_policy"] = value
            with self.assertRaisesRegex(ValueError, "capture_policy"):
                compat.freeze(data)
        data = sample()
        del data["apps"][0]["flows"][0]["capture_policy"]
        with self.assertRaisesRegex(ValueError, "missing fields"):
            compat.freeze(data)

    def test_version_code_is_canonical_nonnegative_signed_long(self):
        for code in ("0", "1", "9223372036854775807"):
            data = sample()
            data["apps"][0]["version_code"] = code
            compat.freeze(data)
        for code in ("01", "-1", "+1", " 1", "1 ", "1.0", "1e3", "１２", "9223372036854775808", 1, True):
            data = sample()
            data["apps"][0]["version_code"] = code
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "version_code"):
                compat.freeze(data)

    def test_source_url_requires_valid_https_hostname_and_no_credentials(self):
        for url in ("https://example.invalid/package", "https://example.invalid:443/path", "https://[::1]/package"):
            compat.validate_source_url(url)
        for url in ("https://", "https:///path", "http://example.invalid", "https://user:secret@example.invalid",
                    "https://@example.invalid", "https://bad host/path", "https://bad_host/path",
                    "https://-bad.example/path", "https://example.invalid:99999/", "https://[broken/path",
                    "https://example.invalid\\path", "https://example.invalid\n/path"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                compat.validate_source_url(url)

    def test_screenshot_path_escape_absolute_missing_and_symlinks_are_rejected(self):
        for path in ("screenshots/../outside.png", "/tmp/screen.png", "docs/screen.png", "screenshots/missing.png"):
            reference = dict(self.image_ref, path=path)
            with self.subTest(path=path), self.assertRaises(ValueError):
                compat.evidence_file(reference, self.root, screenshot=True)
        outside = self.root / "outside.png"
        outside.write_bytes(PNG)
        link = self.root / "screenshots" / "linked.png"
        link.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, "symlink"):
            compat.evidence_file(dict(self.image_ref, path="screenshots/linked.png"), self.root, screenshot=True)
        (self.root / "screenshots" / "dirlink").symlink_to(self.root / "screenshots" / "unit", target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink"):
            compat.evidence_file(dict(self.image_ref, path="screenshots/dirlink/screen.png"), self.root, screenshot=True)

    def test_nonimage_file_cannot_be_a_screenshot(self):
        with self.assertRaisesRegex(ValueError, "PNG or JPEG"):
            compat.evidence_file(self.report_ref, self.root, screenshot=True)

    def test_small_or_synthetic_samples_cannot_meet_release_gates(self):
        self.frozen = compat.freeze(sample(50), FROZEN_AT)
        ledger = self.ledger(self.all_results())
        report = compat.summarize(self.frozen, ledger, self.root)
        self.assertTrue(report["gate_checks"]["at_least_50_apps"])
        self.assertEqual(1, report["groups"]["complete"]["pass_rate"])
        self.assertFalse(report["ready_for_human_gate_review"])
        self.assertFalse(report["release_accepted"])
        actual = sample(1)
        actual["evidence_kind"] = "actual"
        actual["environment"]["device_kind"] = "physical_device"
        self.frozen = compat.freeze(actual, FROZEN_AT)
        report = compat.summarize(self.frozen, self.ledger(self.all_results()), self.root)
        self.assertFalse(report["ready_for_human_gate_review"])

    def test_report_never_grants_release_even_with_self_reported_thresholds(self):
        data = sample(50)
        data["evidence_kind"] = "actual"
        data["environment"]["device_kind"] = "physical_device"
        self.frozen = compat.freeze(data, FROZEN_AT)
        report = compat.summarize(self.frozen, self.ledger(self.all_results()), self.root)
        self.assertTrue(report["ready_for_human_gate_review"])
        self.assertFalse(report["release_accepted"])
        self.assertIn("self-reported", report["notice"])

    def test_cli_freeze_report_and_overwrite_protection(self):
        source, frozen, report = [self.root / name for name in ("sample.json", "frozen.json", "report.json")]
        source.write_text(json.dumps(sample()), encoding="utf-8")
        self.assertEqual(0, compat.main(["freeze", "--sample", str(source), "--output", str(frozen)]))
        with redirect_stderr(io.StringIO()):
            self.assertEqual(2, compat.main(["freeze", "--sample", str(source), "--output", str(frozen)]))
        self.assertEqual(0, compat.main(["report", "--frozen", str(frozen), "--output", str(report)]))
        self.assertEqual(2, json.loads(report.read_text())["groups"]["complete"]["denominator"])

    def test_cli_failed_import_does_not_create_output(self):
        frozen, batch, output = [self.root / name for name in ("frozen.json", "batch.json", "ledger.json")]
        frozen.write_text(json.dumps(self.frozen))
        invalid = self.batch([self.result()])
        invalid["results"][0]["version_code"] = "999"
        batch.write_text(json.dumps(invalid))
        with redirect_stderr(io.StringIO()):
            status = compat.main(["--repo", str(self.root), "import", "--frozen", str(frozen), "--batch", str(batch), "--output", str(output)])
        self.assertEqual(2, status)
        self.assertFalse(output.exists())

    def test_documented_examples_remain_explicitly_synthetic_and_unverified(self):
        examples = Path(__file__).resolve().parents[1] / "docs" / "validation" / "examples"
        self.frozen = compat.freeze(compat.read_json(examples / "app-compatibility-sample.json"), FROZEN_AT)
        batch = compat.read_json(examples / "app-compatibility-results.json")
        batch["sample_sha256"] = self.frozen["sample_sha256"]
        batch["results"][0]["tested_at"] = TESTED_AT
        ledger = compat.import_results(self.frozen, batch, root=self.root)
        report = compat.summarize(self.frozen, ledger, self.root)
        self.assertEqual("synthetic", report["evidence_kind"])
        self.assertEqual(2, report["groups"]["complete"]["counts"]["unverified"])
        self.assertFalse(report["ready_for_human_gate_review"])

    def test_cli_malformed_nested_inputs_fail_with_exit_2_without_output(self):
        frozen_path, batch_path, ledger_path, output = [self.root / name for name in
                                                     ("frozen.json", "batch.json", "ledger.json", "output.json")]
        frozen_path.write_text(json.dumps(self.frozen))
        invalid_batches = [None, [], {}, dict(self.batch([self.result()]), results=[None]),
                           dict(self.batch([self.result()]), results=[{"id": "missing-fields"}])]
        for change in (lambda r: r.update(evidence=[]), lambda r: r.update(dependency_status=[]),
                       lambda r: r["evidence"].update(reports=[None]),
                       lambda r: r["evidence"].update(screenshots="invalid")):
            result = self.result()
            change(result)
            invalid_batches.append(self.batch([result]))
        for batch in invalid_batches:
            batch_path.write_text(json.dumps(batch))
            with self.subTest(batch=batch), redirect_stderr(io.StringIO()):
                self.assertEqual(2, compat.main(["--repo", str(self.root), "import", "--frozen", str(frozen_path),
                                               "--batch", str(batch_path), "--output", str(output)]))
            self.assertFalse(output.exists())
        for frozen in (None, [], {}, dict(self.frozen, sample={}), dict(self.frozen, sample={"apps": [None]})):
            frozen_path.write_text(json.dumps(frozen))
            with self.subTest(frozen=frozen), redirect_stderr(io.StringIO()):
                self.assertEqual(2, compat.main(["report", "--frozen", str(frozen_path), "--output", str(output)]))
        frozen_path.write_text(json.dumps(self.frozen))
        for ledger in (None, [], {}):
            ledger_path.write_text(json.dumps(ledger))
            with self.subTest(ledger=ledger), redirect_stderr(io.StringIO()):
                # An explicitly supplied null ledger is invalid, not an omitted ledger.
                status = compat.main(["report", "--frozen", str(frozen_path), "--ledger", str(ledger_path), "--output", str(output)])
                self.assertEqual(2, status)
            self.assertFalse(output.exists())

    def test_cli_excessively_deep_json_is_a_validation_error(self):
        deep, output = self.root / "deep.json", self.root / "output.json"
        for depth in (compat.MAX_JSON_DEPTH + 1, 1500):
            deep.write_text("[" * depth + "0" + "]" * depth)
            with self.subTest(depth=depth), redirect_stderr(io.StringIO()) as errors:
                status = compat.main(["report", "--frozen", str(deep), "--output", str(output)])
            self.assertEqual(2, status)
            self.assertIn("nesting limit", errors.getvalue())
            self.assertNotIn("Traceback", errors.getvalue())
            self.assertFalse(output.exists())

    def test_json_duplicate_keys_and_nonfinite_numbers_are_rejected(self):
        path = self.root / "invalid.json"
        for content in ('{"x":1,"x":2}', '{"x":NaN}'):
            path.write_text(content)
            with self.assertRaises(ValueError):
                compat.read_json(path)


if __name__ == "__main__":
    unittest.main()
