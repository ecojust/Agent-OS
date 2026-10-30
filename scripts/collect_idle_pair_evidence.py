#!/usr/bin/env python3
"""Collect bounded alternating idle diagnostics separately from legacy three-state data."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

# These common scalar/file helpers do not alter the legacy report contract.
from collect_performance_evidence import (LABELS, MAX_REPORT_BYTES, MAX_CAPTURE_BYTES,
    MAX_ARTIFACT_BYTES, STAGES, LONG_MAX, fields, integer, boolean, text_value,
    positive_int, strict_json, read_json, source_identity, limit_report_output)

REPORT_NAME = "idle-paired-diagnostics.json"
KIND = "agent-idle-paired-diagnostics"
CAPTURE_KIND = "agent-idle-paired-evidence-capture"
MODES = ("A_SERVICE_OFF_RESIDENT_PROCESS", "B_SERVICE_ON_IDLE")
PAIR_COUNT = 6
WINDOW_COUNT = PAIR_COUNT * 2
CONDITIONS = {
    "buildType": "debug", "processScope": "instrumented_app_with_sampler",
    "fixtureProcessIncluded": False, "screenshotSamplingIncluded": False,
    "forcedGc": False, "systemAnimationOverrides": False,
    "windowMillis": 20000, "settleMillis": 2000, "sampleIntervalMillis": 1000,
    "pairCount": PAIR_COUNT, "order": "AB_BA_AB_BA_AB_BA",
    "fixtureReset": "once_before_all_windows", "modelUsed": False,
    "stateChecks": "before_and_after_window",
}
STATE_FLAGS = ("servicePresent", "serviceConnected", "controllerRunning",
               "controlAvailable", "fixtureFocused")


def expected_mode(index):
    pair, position = divmod(index, 2)
    return MODES[position if pair % 2 == 0 else 1 - position]


def validate_state(state, mode, passed, minimum, maximum):
    fields(state, ("checkedNanos", *STATE_FLAGS))
    integer(state["checkedNanos"], minimum, maximum)
    for key in STATE_FLAGS:
        boolean(state[key])
    if passed:
        connected = mode == MODES[1]
        if (state["servicePresent"] != connected or state["serviceConnected"] != connected
                or state["controllerRunning"] or state["controlAvailable"]
                or not state["fixtureFocused"]):
            raise ValueError("passed_window_has_wrong_state")


def validate_report(value, api):
    fields(value, ("schemaVersion", "kind", "diagnosticOnly", "budgetAcceptance", "status",
                   "appProcessPid", "device", "conditions", "windows"))
    if (type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or value["kind"] != KIND or value["diagnosticOnly"] is not True
            or value["budgetAcceptance"] is not False or value["status"] not in ("passed", "failed")):
        raise ValueError("invalid_idle_pair_identity")
    integer(value["appProcessPid"], 1, 2 ** 31 - 1)
    device = value["device"]
    fields(device, ("sdkInt", "manufacturer", "model", "hardware", "fingerprint", "emulatorHeuristic"))
    integer(device["sdkInt"], api, api)
    for key in ("manufacturer", "model", "hardware", "fingerprint"):
        text_value(device[key], 1024 if key == "fingerprint" else 256)
    boolean(device["emulatorHeuristic"])
    fields(value["conditions"], CONDITIONS)
    for key, expected in CONDITIONS.items():
        actual = value["conditions"][key]
        if type(actual) is not type(expected) or actual != expected:
            raise ValueError("unexpected_idle_pair_conditions")
    windows = value["windows"]
    if type(windows) is not list or len(windows) > WINDOW_COUNT:
        raise ValueError("invalid_idle_window_count")
    if value["status"] == "passed" and len(windows) != WINDOW_COUNT:
        raise ValueError("incomplete_passed_idle_pairs")
    previous_boundary, previous_sample_time, previous_cpu = 0, 0, 0
    failed_window = False
    for index, window in enumerate(windows):
        fields(window, ("pairIndex", "position", "mode", "status", "startedNanos", "endedNanos",
                        "samples", "sampleLimitReached", "trace", "traceOverwrittenEvents",
                        "traceCapacity", "stateBefore", "stateAfter"))
        if failed_window:
            raise ValueError("window_after_failed_prefix")
        pair, position = divmod(index, 2)
        integer(window["pairIndex"], pair, pair)
        integer(window["position"], position, position)
        if window["mode"] != expected_mode(index) or window["status"] not in ("passed", "failed"):
            raise ValueError("invalid_idle_pair_order_or_status")
        passed = window["status"] == "passed"
        if value["status"] == "passed" and not passed:
            raise ValueError("passed_report_has_failed_window")
        failed_window = not passed
        integer(window["startedNanos"], previous_boundary)
        integer(window["endedNanos"], window["startedNanos"])
        validate_state(window["stateBefore"], window["mode"], passed,
                       previous_boundary, window["startedNanos"])
        if window["stateAfter"] is None:
            if passed:
                raise ValueError("passed_window_missing_after_state")
            previous_boundary = window["endedNanos"]
        else:
            validate_state(window["stateAfter"], window["mode"], passed, window["endedNanos"], LONG_MAX)
            previous_boundary = window["stateAfter"]["checkedNanos"]
        if passed and window["endedNanos"] - window["startedNanos"] < CONDITIONS["windowMillis"] * 1_000_000:
            raise ValueError("passed_window_shorter_than_planned")
        integer(window["traceOverwrittenEvents"])
        integer(window["traceCapacity"], 4096, 4096)
        boolean(window["sampleLimitReached"])
        if passed and (window["sampleLimitReached"] or window["traceOverwrittenEvents"]):
            raise ValueError("passed_window_lost_diagnostic_data")
        samples = window["samples"]
        if type(samples) is not list or len(samples) > 128 or passed and len(samples) < 2:
            raise ValueError("invalid_idle_sample_count")
        for sample in samples:
            fields(sample, ("elapsedRealtimeNanos", "processCpuMillis", "totalPssKiB", "dalvikPssKiB",
                            "nativePssKiB", "otherPssKiB", "collectionNanos"))
            for measurement in sample.values():
                integer(measurement)
            sampled_at = sample["elapsedRealtimeNanos"]
            if (not window["startedNanos"] <= sampled_at <= window["endedNanos"]
                    or sampled_at + sample["collectionNanos"] > window["endedNanos"]
                    or sampled_at < previous_sample_time or sample["processCpuMillis"] < previous_cpu):
                raise ValueError("inconsistent_resource_sample_order_or_window")
            previous_sample_time, previous_cpu = sampled_at, sample["processCpuMillis"]
        if passed and samples[-1]["elapsedRealtimeNanos"] <= samples[0]["elapsedRealtimeNanos"]:
            raise ValueError("passed_window_has_no_resource_sample_span")
        trace = window["trace"]
        if type(trace) is not list or len(trace) > 4096:
            raise ValueError("invalid_trace_count")
        for event in trace:
            fields(event, ("stage", "startNanos", "durationNanos", "mainThread", "nodeCount",
                           "snapshotJsonBytes", "truncated", "epoch", "dispatchAttempts"))
            if type(event["stage"]) is not str or event["stage"] not in STAGES:
                raise ValueError("unexpected_trace_stage")
            integer(event["startNanos"])
            integer(event["durationNanos"])
            boolean(event["mainThread"])
            integer(event["nodeCount"], -1, 300)
            integer(event["snapshotJsonBytes"], -1, 65536)
            boolean(event["truncated"])
            integer(event["epoch"], -1)
            integer(event["dispatchAttempts"], -1)
            if (event["startNanos"] < window["startedNanos"]
                    or event["startNanos"] + event["durationNanos"] > window["endedNanos"]):
                raise ValueError("trace_event_outside_window")
        if passed and trace:
            raise ValueError("passed_idle_window_requires_empty_trace")
    return value


def empty_capture(label, source):
    return {"schemaVersion": 1, "kind": CAPTURE_KIND, "diagnosticOnly": True,
            "budgetAcceptance": False, "matrixLabel": label,
            "apiLevel": int(label.split(".")[0]), "actualSdkInt": None,
            "sourceRun": source, "capturedAt": datetime.now(timezone.utc).isoformat(),
            "declaredCiProfile": "default_x86_64_swiftshader_animation_one",
            "captureStatus": "not_collected", "captureError": "emulator_script_did_not_finish",
            "gradleExitCode": None, "report": None}


def collect(label, source, exit_code, output):
    (output / REPORT_NAME).unlink(missing_ok=True)
    record = empty_capture(label, source)
    record["gradleExitCode"] = exit_code
    record["captureStatus"] = "unavailable"
    record["captureError"] = "device_or_report_unavailable"
    try:
        actual = subprocess.run(["adb", "shell", "getprop", "ro.build.version.sdk"],
                                capture_output=True, text=True, check=True, timeout=5).stdout.strip()
        if not actual.isdigit() or int(actual) != record["apiLevel"]:
            raise ValueError("device_sdk_mismatch")
        record["actualSdkInt"] = int(actual)
        with tempfile.TemporaryDirectory(prefix="agent-idle-pair-") as temporary:
            path = Path(temporary) / REPORT_NAME
            with path.open("wb") as destination:
                result = subprocess.run(
                    ["adb", "exec-out", "run-as", "dev.agentos.app", "cat",
                     "files/performance/" + REPORT_NAME], stdout=destination,
                    stderr=subprocess.DEVNULL, check=False, timeout=20,
                    preexec_fn=limit_report_output)
            if path.stat().st_size > MAX_REPORT_BYTES:
                raise ValueError("report_exceeds_collection_budget")
            if result.returncode != 0:
                return record
            value = read_json(path)
            validate_report(value, record["apiLevel"])
            data = path.read_bytes()
            target = output / REPORT_NAME
            target.write_bytes(data)
            record.update({"captureStatus": "collected", "captureError": None,
                           "report": {"path": REPORT_NAME, "bytes": len(data),
                                      "sha256": hashlib.sha256(data).hexdigest(),
                                      "status": value["status"]}})
    except ValueError as error:
        record["captureStatus"] = "invalid"
        # Validation errors are fixed codes, never report content or exception dumps.
        record["captureError"] = str(error) if re.fullmatch(r"[a-z_]{1,80}", str(error)) else "invalid_report"
    except (OSError, subprocess.SubprocessError):
        pass
    return record


def validate_capture(value, label, source):
    expected = {"schemaVersion", "kind", "diagnosticOnly", "budgetAcceptance", "matrixLabel",
                "apiLevel", "actualSdkInt", "sourceRun", "capturedAt", "declaredCiProfile",
                "captureStatus", "captureError", "gradleExitCode", "report"}
    if type(value) is dict and "emulatorStepOutcome" in value:
        expected.add("emulatorStepOutcome")
        if value["emulatorStepOutcome"] not in ("success", "failure", "cancelled", "skipped", "unknown"):
            raise ValueError("invalid_emulator_outcome")
    fields(value, expected)
    api = int(label.split(".")[0])
    fields(value["sourceRun"], source)
    if any(type(value["sourceRun"][key]) is not type(expected) for key, expected in source.items()):
        raise ValueError("capture_source_type_mismatch")
    if (type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or value["kind"] != CAPTURE_KIND or value["diagnosticOnly"] is not True
            or value["budgetAcceptance"] is not False or value["sourceRun"] != source
            or value["matrixLabel"] != label
            or value["declaredCiProfile"] != "default_x86_64_swiftshader_animation_one"):
        raise ValueError("capture_identity_mismatch")
    integer(value["apiLevel"], api, api)
    if value["actualSdkInt"] is not None:
        integer(value["actualSdkInt"], api, api)
    if value["gradleExitCode"] is not None:
        integer(value["gradleExitCode"], 0, 255)
    if (type(value["capturedAt"]) is not str or len(value["capturedAt"]) > 40
            or datetime.fromisoformat(value["capturedAt"]).tzinfo is None):
        raise ValueError("invalid_capture_timestamp")
    if value["captureStatus"] == "collected":
        report = value["report"]
        fields(report, ("path", "bytes", "sha256", "status"))
        integer(report["bytes"], 1, MAX_REPORT_BYTES)
        if (value["actualSdkInt"] != api or value["captureError"] is not None
                or report["path"] != REPORT_NAME or report["status"] not in ("passed", "failed")
                or type(report["sha256"]) is not str or not re.fullmatch(r"[0-9a-f]{64}", report["sha256"])):
            raise ValueError("invalid_report_reference")
    elif (value["captureStatus"] not in ("not_collected", "unavailable", "invalid")
          or value["report"] is not None or type(value["captureError"]) is not str
          or not re.fullmatch(r"[a-z_]{1,80}", value["captureError"])):
        raise ValueError("invalid_capture_outcome")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("collect", "finalize"))
    parser.add_argument("--matrix-label", required=True, choices=LABELS)
    parser.add_argument("--exit-code", type=int, default=1)
    parser.add_argument("--step-outcome", choices=("success", "failure", "cancelled", "skipped", "unknown"), default="unknown")
    args = parser.parse_args()
    source = source_identity(os.environ)
    output = Path("idle-pair-evidence") / ("api-" + args.matrix_label)
    output.mkdir(parents=True, exist_ok=True)
    path = output / "capture.json"
    if args.mode == "collect":
        record = collect(args.matrix_label, source, args.exit_code, output)
    elif path.exists():
        record = read_json(path, MAX_CAPTURE_BYTES)
        if record.get("sourceRun") != source or record.get("matrixLabel") != args.matrix_label:
            raise ValueError("capture_source_mismatch")
    else:
        record = empty_capture(args.matrix_label, source)
    if args.mode == "finalize":
        record["emulatorStepOutcome"] = args.step_outcome
    validate_capture(record, args.matrix_label, source)
    path.write_text(json.dumps(record, indent=2) + "\n")
    print(f"API {args.matrix_label}: idle paired diagnostics {record['captureStatus']}; "
          f"Gradle exit={record['gradleExitCode']}; not a performance budget acceptance")


if __name__ == "__main__":
    main()
