"""Exercise attribution, failed prefixes and the alternating idle measurement contract."""

import copy
import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import collect_idle_pair_evidence as idle
import collect_performance_evidence as legacy

SOURCE = {"repository": "ecojust/Agent-OS", "runId": 123, "attempt": 2, "commit": "a" * 40}


def report(api=37):
    result = {"schemaVersion": 1, "kind": idle.KIND, "diagnosticOnly": True,
              "budgetAcceptance": False, "status": "passed", "appProcessPid": 420,
              "device": {"sdkInt": api, "manufacturer": "synthetic", "model": "unit-test",
                         "hardware": "synthetic", "fingerprint": "synthetic-fixture", "emulatorHeuristic": True},
              "conditions": dict(idle.CONDITIONS), "windows": []}
    for index in range(12):
        pair, position = divmod(index, 2)
        mode = idle.expected_mode(index)
        enabled = mode == idle.MODES[1]
        start = 1_000_000_000 + index * 25_000_000_000
        end = start + 20_000_000_000
        state = {"checkedNanos": start - 1, "servicePresent": enabled, "serviceConnected": enabled,
                 "controllerRunning": False, "controlAvailable": False, "fixtureFocused": True}
        sample = {"elapsedRealtimeNanos": start + 100_000_000, "processCpuMillis": 1000 + index * 1000,
                  "totalPssKiB": 12000 if enabled else 10000, "dalvikPssKiB": 4000,
                  "nativePssKiB": 3000, "otherPssKiB": 5000 if enabled else 3000,
                  "collectionNanos": 1_000_000}
        result["windows"].append({"pairIndex": pair, "position": position, "mode": mode,
            "status": "passed", "startedNanos": start, "endedNanos": end,
            "sampleLimitReached": False, "traceOverwrittenEvents": 0, "traceCapacity": 4096,
            "stateBefore": state, "stateAfter": dict(state, checkedNanos=end + 1), "trace": [],
            "samples": [sample, dict(sample, elapsedRealtimeNanos=end - 100_000_000,
                                     processCpuMillis=sample["processCpuMillis"] + (300 if enabled else 100))]})
    return result


def event(window, stage="SNAPSHOT_OBSERVE"):
    return {"stage": stage, "startNanos": window["startedNanos"] + 10,
            "durationNanos": 1, "mainThread": False, "nodeCount": -1,
            "snapshotJsonBytes": -1, "truncated": False, "epoch": 1, "dispatchAttempts": 0}


def collected_capture(value, label="37.0"):
    data = json.dumps(value, indent=3).encode()
    capture = idle.empty_capture(label, SOURCE)
    capture.update(actualSdkInt=int(label.split('.')[0]), captureStatus="collected", captureError=None,
                   gradleExitCode=0 if value["status"] == "passed" else 1,
                   report={"path": idle.REPORT_NAME, "bytes": len(data),
                           "sha256": hashlib.sha256(data).hexdigest(), "status": value["status"]})
    return capture, data


class IdlePairEvidenceTests(unittest.TestCase):
    def test_passed_report_preserves_six_ordered_pairs_and_measurements(self):
        value = report()
        original = copy.deepcopy(value)
        self.assertIs(value, idle.validate_report(value, 37))
        self.assertEqual(original, value)
        self.assertEqual(["AB", "BA"] * 3,
                         [''.join(w["mode"][0] for w in value["windows"][i:i + 2]) for i in range(0, 12, 2)])

    def test_failed_empty_and_partial_reports_keep_missing_windows_missing(self):
        for count in (0, 1, 7, 12):
            with self.subTest(count=count):
                value = report()
                value["status"] = "failed"
                value["windows"] = value["windows"][:count]
                if count:
                    value["windows"][-1].update(status="failed", samples=[], stateAfter=None)
                idle.validate_report(value, 37)
                value["status"] = "passed"
                with self.assertRaises(ValueError):
                    idle.validate_report(value, 37)
        # A restore failure can follow twelve valid completed windows.
        value = report()
        value["status"] = "failed"
        idle.validate_report(value, 37)

    def test_pair_identity_order_and_failure_prefix_are_strict(self):
        changes = [lambda x: x["windows"].pop(0),
                   lambda x: x["windows"].reverse(),
                   lambda x: x["windows"][0].update(pairIndex=True),
                   lambda x: x["windows"][1].update(position=0),
                   lambda x: x["windows"][2].update(mode=idle.MODES[0]),
                   lambda x: x["windows"].append(copy.deepcopy(x["windows"][-1])),
                   lambda x: (x.update(status="failed"), x["windows"][0].update(status="failed"))]
        for change in changes:
            value = report()
            change(value)
            with self.assertRaises(ValueError):
                idle.validate_report(value, 37)

    def test_passed_states_require_correct_service_idle_task_and_foreground(self):
        for index in (0, 1):
            for side in ("stateBefore", "stateAfter"):
                for flag in idle.STATE_FLAGS:
                    with self.subTest(index=index, side=side, flag=flag):
                        value = report()
                        value["windows"][index][side][flag] = not value["windows"][index][side][flag]
                        with self.assertRaisesRegex(ValueError, "wrong_state"):
                            idle.validate_report(value, 37)
        value = report()
        value["windows"][0]["stateAfter"] = None
        with self.assertRaisesRegex(ValueError, "missing_after_state"):
            idle.validate_report(value, 37)

    def test_failed_state_mismatches_are_retained_without_becoming_passed(self):
        value = report()
        value.update(status="failed", windows=value["windows"][:1])
        window = value["windows"][0]
        window.update(status="failed")
        window["stateAfter"].update(servicePresent=True, serviceConnected=True,
                                    fixtureFocused=False, controllerRunning=True)
        original = copy.deepcopy(value)
        idle.validate_report(value, 37)
        self.assertEqual(original, value)

    def test_checks_and_windows_have_non_overlapping_monotonic_boundaries(self):
        changes = [lambda x: x["windows"][0]["stateBefore"].update(checkedNanos=x["windows"][0]["startedNanos"] + 1),
                   lambda x: x["windows"][0]["stateAfter"].update(checkedNanos=x["windows"][0]["endedNanos"] - 1),
                   lambda x: x["windows"][1]["stateBefore"].update(checkedNanos=x["windows"][0]["stateAfter"]["checkedNanos"] - 1),
                   lambda x: x["windows"][1].update(startedNanos=x["windows"][0]["endedNanos"] - 1)]
        for change in changes:
            value = report()
            change(value)
            with self.assertRaises(ValueError):
                idle.validate_report(value, 37)

    def test_resource_span_cpu_and_sample_collection_are_checked(self):
        changes = [lambda w: w.update(samples=w["samples"][:1]),
                   lambda w: w["samples"][1].update(elapsedRealtimeNanos=w["samples"][0]["elapsedRealtimeNanos"]),
                   lambda w: w["samples"][1].update(processCpuMillis=w["samples"][0]["processCpuMillis"] - 1),
                   lambda w: w["samples"][0].update(elapsedRealtimeNanos=w["startedNanos"] - 1),
                   lambda w: w["samples"][1].update(collectionNanos=101_000_000),
                   lambda w: w.update(endedNanos=w["startedNanos"] + 19_999_999_999)]
        for change in changes:
            value = report()
            change(value["windows"][0])
            with self.assertRaises(ValueError):
                idle.validate_report(value, 37)
        value = report()
        value["windows"][1]["samples"][0]["processCpuMillis"] = 0
        with self.assertRaises(ValueError):
            idle.validate_report(value, 37)

    def test_no_execution_trace_can_be_certified_as_passed_in_either_state(self):
        for index in (0, 1):
            value = report()
            value["windows"][index]["trace"] = [event(value["windows"][index])]
            with self.assertRaisesRegex(ValueError, "requires_empty_trace"):
                idle.validate_report(value, 37)
            value["status"] = "failed"
            with self.assertRaisesRegex(ValueError, "requires_empty_trace"):
                idle.validate_report(value, 37)
            value["windows"] = value["windows"][:index + 1]
            value["windows"][-1]["status"] = "failed"
            idle.validate_report(value, 37)

    def test_failed_trace_keeps_events_but_bounds_and_schema_still_apply(self):
        value = report()
        value.update(status="failed", windows=value["windows"][:1])
        window = value["windows"][0]
        window.update(status="failed", trace=[event(window)])
        idle.validate_report(value, 37)
        for delta in ({"stage": "HTTP_BODY"}, {"startNanos": window["startedNanos"] - 1},
                      {"durationNanos": 20_000_000_001}, {"nodeCount": 301}, {"payload": "private"}):
            invalid = copy.deepcopy(value)
            invalid["windows"][0]["trace"][0].update(delta)
            with self.assertRaises(ValueError):
                idle.validate_report(invalid, 37)

    def test_report_does_not_accept_unmeasured_conditions_or_unbounded_payloads(self):
        changes = [lambda x: x.update(kind=legacy.KIND), lambda x: x.update(schemaVersion=True),
                   lambda x: x.update(budgetAcceptance=True), lambda x: x.update(apiKey="private"),
                   lambda x: x["device"].update(sdkInt=36),
                   lambda x: x["device"].update(fingerprint="x" * 1025),
                   lambda x: x["conditions"].update(windowMillis=6000),
                   lambda x: x["conditions"].update(forcedGc=0),
                   lambda x: x["conditions"].update(order="AB_AB_AB_AB_AB_AB"),
                   lambda x: x["conditions"].update(fixtureReset="every_window"),
                   lambda x: x["windows"][0].update(samples=[{}] * 129),
                   lambda x: x["windows"][0].update(trace=[{}] * 4097),
                   lambda x: x["windows"][0]["samples"][0].update(totalPssKiB=float('nan')),
                   lambda x: x["windows"][0]["samples"][0].update(processCpuMillis=True),
                   lambda x: x["windows"][0]["samples"][0].update(collectionNanos=2 ** 63)]
        for change in changes:
            value = report()
            change(value)
            with self.assertRaises(ValueError):
                idle.validate_report(value, 37)

    def test_overwritten_or_capped_data_cannot_pass(self):
        for change in ({"traceOverwrittenEvents": 1}, {"sampleLimitReached": True}, {"traceCapacity": 4095}):
            value = report()
            value["windows"][0].update(change)
            with self.assertRaises(ValueError):
                idle.validate_report(value, 37)

    def test_capture_identity_and_reference_are_bound_to_new_report(self):
        capture, unused = collected_capture(report())
        self.assertIs(capture, idle.validate_capture(capture, "37.0", SOURCE))
        for change in (lambda c: c.update(kind=legacy.CAPTURE_KIND),
                       lambda c: c["report"].update(path=legacy.REPORT_NAME),
                       lambda c: c["sourceRun"].update(attempt=1),
                       lambda c: c.update(declaredCiProfile="unknown"),
                       lambda c: c.update(actualSdkInt=36),
                       lambda c: c.update(capturedAt="2026-10-30T00:00:00")):
            invalid = copy.deepcopy(capture)
            change(invalid)
            with self.assertRaises(ValueError):
                idle.validate_capture(invalid, "37.0", SOURCE)
        with self.assertRaises(ValueError):
            legacy.validate_capture(capture, "37.0", SOURCE)

    def test_collect_keeps_failed_report_bytes_and_original_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = report()
            value.update(status="failed", windows=value["windows"][:1])
            value["windows"][0].update(status="failed", stateAfter=None)
            expected, raw = collected_capture(value)
            output = Path(temporary)
            commands = []
            def fake_adb(command, **kwargs):
                commands.append(command)
                if command[1] == "shell":
                    return subprocess.CompletedProcess(command, 0, stdout="37\n")
                kwargs["stdout"].write(raw)
                return subprocess.CompletedProcess(command, 0)
            with patch.object(idle.subprocess, "run", side_effect=fake_adb):
                capture = idle.collect("37.0", SOURCE, 7, output)
            idle.validate_capture(capture, "37.0", SOURCE)
            self.assertEqual(7, capture["gradleExitCode"])
            self.assertEqual("failed", capture["report"]["status"])
            self.assertEqual(expected["report"], capture["report"])
            self.assertEqual(raw, (output / idle.REPORT_NAME).read_bytes())
            self.assertEqual("files/performance/" + idle.REPORT_NAME, commands[-1][-1])

    def test_collect_drops_stale_report_and_reports_invalid_unavailable_and_wrong_sdk(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            for case in ("invalid", "missing", "sdk", "exception"):
                (output / idle.REPORT_NAME).write_text("old report")
                def fake_adb(command, **kwargs):
                    if case == "exception":
                        raise OSError("private-detail")
                    if command[1] == "shell":
                        return subprocess.CompletedProcess(command, 0, stdout="36\n" if case == "sdk" else "37\n")
                    kwargs["stdout"].write(b'{"secret":"private-detail"}')
                    return subprocess.CompletedProcess(command, 1 if case == "missing" else 0)
                with patch.object(idle.subprocess, "run", side_effect=fake_adb):
                    capture = idle.collect("37.0", SOURCE, 1, output)
                self.assertFalse((output / idle.REPORT_NAME).exists())
                self.assertEqual("invalid" if case in ("invalid", "sdk") else "unavailable", capture["captureStatus"])
                self.assertNotIn("private-detail", json.dumps(capture))
                idle.validate_capture(capture, "37.0", SOURCE)

    def test_json_boundaries_are_inherited_without_relaxing_old_report_schema(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "test.json"
            for raw in (b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":Infinity}', b' ' * (idle.MAX_REPORT_BYTES + 1)):
                path.write_bytes(raw)
                with self.assertRaises(ValueError):
                    idle.read_json(path)
            link = path.with_name("link.json")
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                idle.read_json(link)
        from test_performance_evidence import report as legacy_report
        legacy.validate_report(legacy_report(), 37)
        with self.assertRaises(ValueError):
            idle.validate_report(legacy_report(), 37)
        with self.assertRaises(ValueError):
            legacy.validate_report(report(), 37)

    def test_finalize_materializes_missing_capture_with_original_source(self):
        source_environment = {"GITHUB_REPOSITORY": SOURCE["repository"], "GITHUB_SHA": SOURCE["commit"],
                              "GITHUB_RUN_ID": str(SOURCE["runId"]), "GITHUB_RUN_ATTEMPT": str(SOURCE["attempt"])}
        with tempfile.TemporaryDirectory() as temporary, patch.object(idle.os, "environ", source_environment):
            output = Path(temporary) / "idle-pair-evidence"
            with patch.object(idle, "Path", side_effect=lambda name: output if name == "idle-pair-evidence" else Path(name)):
                with patch('sys.argv', ['collect_idle_pair_evidence.py', 'finalize', '--matrix-label', '37.0', '--step-outcome', 'failure']):
                    with contextlib.redirect_stdout(io.StringIO()):
                        idle.main()
            capture = idle.read_json(output / "api-37.0/capture.json")
            self.assertEqual("not_collected", capture["captureStatus"])
            self.assertEqual("failure", capture["emulatorStepOutcome"])
            self.assertEqual(SOURCE, capture["sourceRun"])
            self.assertIsNone(capture["gradleExitCode"])


if __name__ == '__main__':
    unittest.main()
