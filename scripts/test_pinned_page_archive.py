"""Fixed-profile archive checks; generated parser fixtures are not Android/UI evidence."""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace
import unittest
import zlib

import pinned_calculator_page as pinned
import publish_test_evidence as publisher
import third_party_smoke as base

BINDING = {"repository": "ecojust/Agent-OS", "runId": 123, "attempt": 2, "commit": "a" * 40}


def source_run():
    return {"id": 123, "run_attempt": 2, "repository": {"id": 1, "full_name": BINDING["repository"]},
            "head_repository": {"id": 1, "full_name": BINDING["repository"]}, "event": "workflow_dispatch",
            "head_branch": "master", "head_sha": BINDING["commit"], "status": "completed",
            "path": publisher.PINNED_WORKFLOW, "conclusion": "failure"}


def artifact(label="34"):
    return {"id": 456, "name": f"screenshots-api-{label}-attempt-2", "expired": False,
            "size_in_bytes": 1234, "created_at": "2026-10-27T00:01:00Z",
            "workflow_run": {"id": 123, "head_sha": BINDING["commit"], "head_branch": "master",
                             "repository_id": 1, "head_repository_id": 1}}


def job(label="34"):
    return {"name": f"page-predicate ({label})", "status": "completed", "run_id": 123,
            "run_attempt": 2, "head_sha": BINDING["commit"], "conclusion": "failure",
            "started_at": "2026-10-27T00:00:00Z", "completed_at": "2026-10-27T00:02:00Z"}


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value) + "\n")


def png():
    # An actual small encoded PNG used only to exercise hash/dimension parsing.
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress((b"\0" + b"\x40\x90\xd0" * 64) * 64)) + chunk(b"IEND", b""))


def write_lane(root, label="34"):
    folder = root / "incoming" / artifact(label)["name"]
    report = pinned.empty_report(BINDING, label)
    report.update(status="passed", device={"serial": "emulator-5554", "api": pinned.APIS[label], "properties": {
        "ro.kernel.qemu": "1", "ro.boot.qemu": "1", "ro.build.version.sdk": str(pinned.APIS[label]),
        "ro.product.cpu.abi": "x86_64", "sys.boot_completed": "1"}}, verifiedDownload={
        "package": base.PACKAGE, "versionCode": "10", "versionName": "1.4.0", "minSdk": 26,
        "targetSdk": 36, "abis": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"], "sizeBytes": base.PINNED_SIZE,
        "sha256": base.PINNED_SHA, "certificateSha256": base.PINNED_CERT})
    standards = []
    for index, name in enumerate(pinned.CASES):
        stages = []
        for stage, outcome in pinned.CASES[name]:
            item = {"stage": stage, "predicate": outcome}
            if outcome == "rejected": item["reason"] = "UNKNOWN_CONTENT"
            stages.append(item)
        stages.insert(0, {"stage": "independent-diagnostic", "metadata": name + "-0.json", "captureStatus": "blocked",
                          "captureReason": "platform_refused_or_secure", "secureFlagsCleared": False})
        dump(folder / "pinned-page" / (name + "-0.json"), {"stage": "independent-diagnostic", "nodes": []})
        if index == 0:
            data = png(); image = name + "-1.png"; (folder / "pinned-page" / image).write_bytes(data)
            stages[0].update(captureStatus="captured", image=image, imageBytes=len(data),
                             imageSha256=hashlib.sha256(data).hexdigest(), imageWidth=64, imageHeight=64)
            del stages[0]["captureReason"]
        record = {"schemaVersion": 1, "kind": "pinned-calculator-page-predicate", "testClass": pinned.TEST_CLASS,
                  "testName": name, "api": pinned.APIS[label], "sourceSha": BINDING["commit"], "runId": "123",
                  "runAttempt": "2", "deviceSerial": "emulator-5554", "status": "passed", "stages": stages,
                  "installedArtifactVerified": True, "captureCapabilityAbsent": True, "imageAndGestureToolsAbsent": True,
                  "modelRequests": 0}
        dump(folder / "pinned-page" / (name + ".case.json"), record)
        report["cases"][index] = {"name": name, "status": "passed", "record": "pinned-page/" + name + ".case.json"}
        standards.append({"api": pinned.APIS[label], "testClass": pinned.TEST_CLASS, "testName": name,
                          "testResult": "pass", "captures": [{"status": "blocked", "captureError": "protected"}]})
    dump(folder / "screenshots/manifest.json", {"schemaVersion": 1, "api": pinned.APIS[label], "tests": standards})
    log = json.dumps({"runner": "direct_adb_instrumentation", "passed": True, "exitCode": 0,
                      "stdout": "INSTRUMENTATION_RESULT: stream=\nOK (5 tests)\n"}).encode()
    (folder / "instrumentation.json").write_bytes(log)
    report["instrumentation"] = {"selectedClass": pinned.TEST_CLASS, "expectedTests": 5, "exitCode": 0, "passed": True,
                                 "log": "instrumentation.json", "bytes": len(log), "sha256": hashlib.sha256(log).hexdigest()}
    dump(folder / "report.json", report)
    capture = pinned.empty_capture(label)
    capture.update(captureStatus="collected", actualSdkInt=pinned.APIS[label], instrumentationExitCode=0,
                   files=["screenshots/manifest.json"])
    dump(folder / "capture.json", capture)
    return folder


def archive(root, run=None, artifacts=None, jobs=None):
    run = source_run() if run is None else run
    dump(root / "artifacts.json", {"artifacts": artifacts if artifacts is not None else [artifact()]})
    dump(root / "jobs.json", {"jobs": jobs if jobs is not None else [job()]})
    args = SimpleNamespace(repository=BINDING["repository"], artifacts=root / "artifacts.json", jobs=root / "jobs.json",
                           incoming=root / "incoming")
    previous = Path.cwd()
    try:
        os.chdir(root)
        with contextlib.redirect_stdout(io.StringIO()): publisher.archive(args, run, 123, 2)
    finally: os.chdir(previous)
    return json.loads((root / "screenshots/123-2/manifest.json").read_text())


class PinnedPageArchiveTests(unittest.TestCase):
    def test_only_fixed_dispatch_profile_is_added_and_default_keeps_eight_lanes(self):
        self.assertEqual((123, 2), publisher.verify_source(source_run(), BINDING["repository"]))
        for change in ({"event": "push"}, {"path": ".github/workflows/pinned-other.yml"}, {"head_branch": "feature"}):
            value = source_run(); value.update(change)
            with self.assertRaises(ValueError): publisher.verify_source(value, BINDING["repository"])
        with tempfile.TemporaryDirectory() as temp:
            run = source_run(); run.update(path=".github/workflows/android.yml", event="push")
            self.assertEqual((123, 2), publisher.verify_source(run, BINDING["repository"]))
            result = archive(Path(temp), run, [], [])
            self.assertEqual("android-emulator-test-evidence", result["kind"])
            self.assertEqual(publisher.EXPECTED_MATRIX, result["expectedMatrixLabels"])
            self.assertEqual(8, len(result["apis"]))
            self.assertEqual(8, len(result["performanceDiagnostics"]["apis"]))

    def test_artifact_origin_attempt_and_job_times_are_not_interchangeable(self):
        self.assertEqual([artifact()], publisher.pinned_page_artifacts({"artifacts": [artifact(), artifact("30")]}, source_run()))
        for change in ({"expired": None}, {"size_in_bytes": True}, {"id": 0},
                       {"workflow_run": dict(artifact()["workflow_run"], head_sha="b" * 40)}):
            value = artifact(); value.update(change)
            with self.assertRaises(ValueError): publisher.pinned_page_artifacts({"artifacts": [value]}, source_run())
        with self.assertRaises(ValueError): publisher.pinned_page_artifacts({"artifacts": [artifact(), artifact()]}, source_run())
        for change in ({"head_sha": "b" * 40}, {"run_attempt": 1}, {"completed_at": "2026-10-27T00:00:30Z"}):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); write_lane(root); current = job(); current.update(change)
                with self.assertRaises(ValueError): archive(root, jobs=[current])

    def test_archive_preserves_real_record_bytes_and_fixed_twenty_case_denominator(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); source = write_lane(root)
            raw = (source / "screenshots/manifest.json").read_bytes()
            result = archive(root)
            self.assertEqual("pinned-calculator-page-predicate-evidence", result["kind"])
            self.assertEqual(["34", "35", "36", "37.0"], result["expectedMatrixLabels"])
            self.assertEqual(20, result["expectedTests"])
            self.assertEqual({"passed": 5, "failed": 0, "unverified": 15}, result["caseCounts"])
            self.assertEqual("failure", result["sourceRun"]["conclusion"])
            self.assertEqual(1, len(result["apis"][0]["verifiedPinnedImages"]))
            self.assertEqual("not_produced", result["junit"]["status"])
            self.assertEqual("not_run", result["performanceDiagnostics"]["status"])
            self.assertEqual(raw, (root / "screenshots/123-2/api-34/screenshots/manifest.json").read_bytes())
            self.assertIn("No production screenshot capability", result["scope"])
            for item in result["apis"][0]["files"]:
                self.assertEqual(item["sha256"], hashlib.sha256((source / item["path"]).read_bytes()).hexdigest())

    def test_failed_and_missing_cases_remain_failed_or_unverified_without_dropping_lane(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); source = write_lane(root); names = list(pinned.CASES)
            report = json.loads((source / "report.json").read_text()); report["status"] = "failed"
            first = source / "pinned-page" / (names[0] + ".case.json")
            record = json.loads(first.read_text()); record.update(status="failed", failureCode="STALE_OBSERVATION"); dump(first, record)
            report["cases"][0]["status"] = "failed"
            (source / "pinned-page" / (names[1] + ".case.json")).unlink()
            report["cases"][1] = {"name": names[1], "status": "unverified"}
            standards = json.loads((source / "screenshots/manifest.json").read_text())
            standards["tests"][0]["testResult"] = "fail"; standards["tests"].pop(1)
            dump(source / "screenshots/manifest.json", standards); dump(source / "report.json", report)
            result = archive(root)
            self.assertEqual({"passed": 3, "failed": 1, "unverified": 16}, result["caseCounts"])
            self.assertEqual("failed", result["apis"][0]["status"])
            self.assertEqual(1, len(result["apis"][0]["verifiedPinnedImages"]))

    def test_case_identity_apk_and_nonpage_refusal_cannot_be_promoted_to_pass(self):
        mutators = [
            lambda r, c: r["source"].update(commit="b" * 40),
            lambda r, c: r["verifiedDownload"].update(sizeBytes=base.PINNED_SIZE + 1),
            lambda r, c: r["verifiedDownload"].update(certificateSha256="b" * 64),
            lambda r, c: c.update(sourceSha="b" * 40),
            lambda r, c: c.update(captureCapabilityAbsent=False),
            lambda r, c: c["stages"][-1].update(reason="STALE_OBSERVATION"),
        ]
        for change in mutators:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temp:
                source = write_lane(Path(temp)); name = list(pinned.CASES)[1]
                path = source / "pinned-page" / (name + ".case.json")
                report = json.loads((source / "report.json").read_text()); case = json.loads(path.read_text())
                change(report, case); dump(path, case); dump(source / "report.json", report)
                with self.assertRaises(ValueError): publisher.pinned_page_report(source, source_run(), "34")

    def test_corrupt_or_secure_cleared_png_and_forged_success_log_are_rejected(self):
        for kind in ("hash", "secure", "missing", "wrong_log"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                source = write_lane(Path(temp)); name = next(iter(pinned.CASES))
                case_path = source / "pinned-page" / (name + ".case.json")
                case = json.loads(case_path.read_text()); stage = case["stages"][0]
                if kind == "hash": stage["imageSha256"] = "0" * 64
                elif kind == "secure": stage["secureFlagsCleared"] = True
                elif kind == "missing": (source / "pinned-page" / stage["image"]).unlink()
                else: (source / "instrumentation.json").write_text("{}")
                dump(case_path, case)
                with self.assertRaises(ValueError): publisher.pinned_page_report(source, source_run(), "34")

    def test_missing_standard_record_does_not_become_a_pass_or_rewrite_driver(self):
        with tempfile.TemporaryDirectory() as temp:
            source = write_lane(Path(temp))
            standard_path = source / "screenshots/manifest.json"
            standard = json.loads(standard_path.read_text())
            standard_path.unlink()
            result = publisher.pinned_page_report(source, source_run(), "34")
            self.assertEqual("passed", result["driverStatus"])
            self.assertEqual("unverified", result["status"])
            self.assertTrue(all(case["status"] == "unverified" for case in result["cases"]))
            self.assertTrue(all(case["pageCaseStatus"] == "passed" for case in result["cases"]))
            # A failure in the ordinary evidence rule can occur after the page
            # harness passed; its independent result must take precedence.
            standard["tests"][0]["testResult"] = "fail"
            dump(standard_path, standard)
            result = publisher.pinned_page_report(source, source_run(), "34")
            self.assertEqual("failed", result["status"])
            self.assertEqual("failed", result["cases"][0]["status"])
            self.assertEqual("passed", result["cases"][0]["pageCaseStatus"])
            self.assertEqual(4, sum(case["status"] == "passed" for case in result["cases"]))

    def test_environment_failure_before_device_or_install_keeps_all_five_unverified(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); source = root / "incoming" / artifact()["name"]
            dump(source / "report.json", pinned.empty_report(BINDING, "34"))
            dump(source / "capture.json", pinned.empty_capture("34"))
            result = archive(root)
            self.assertEqual({"passed": 0, "failed": 0, "unverified": 20}, result["caseCounts"])
            self.assertEqual("environment_failed", result["apis"][0]["driverStatus"])
            self.assertEqual("not_collected", result["apis"][0]["captureStatus"])
            self.assertEqual([], result["apis"][0]["verifiedPinnedImages"])
            # A real preflight assertion can precede creation of the page record.
            # Its ordinary instrumentation failure must still stay failed.
            dump(source / "screenshots/manifest.json", {"tests": [{"api": 34, "testClass": pinned.TEST_CLASS,
                "testName": next(iter(pinned.CASES)), "testResult": "fail", "captures": [{"status": "blocked"}]}]})
            value = publisher.pinned_page_report(source, source_run(), "34")
            self.assertEqual("failed", value["cases"][0]["status"])
            self.assertEqual("unverified", value["cases"][0]["pageCaseStatus"])


if __name__ == "__main__":
    unittest.main()
