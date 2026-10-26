#!/usr/bin/env python3
"""Collect bounded numeric diagnostics separately from UI screenshot evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

LABELS = tuple(str(api) for api in range(30, 37)) + ("37.0",)
REPORT_NAME = "three-state-diagnostics.json"
MAX_REPORT_BYTES = 2 * 1024 * 1024
MAX_CAPTURE_BYTES = 16 * 1024
MAX_ARTIFACT_BYTES = MAX_REPORT_BYTES + MAX_CAPTURE_BYTES
KIND = "agent-performance-diagnostics"
CAPTURE_KIND = "agent-performance-evidence-capture"
MODES = ("A_SERVICE_OFF_RESIDENT_PROCESS", "B_SERVICE_ON_IDLE", "C_LOCAL_MODEL_FIXTURE", "STOP_WAITING_APPROVAL")
STAGES = frozenset(("MAIN_QUEUE", "MAIN_CALLBACK", "SNAPSHOT_OBSERVE", "SNAPSHOT_PREPARE",
                   "SNAPSHOT_FINAL_LAUNCH", "SNAPSHOT_FINAL_MUTATION", "STOP_RECEIVED",
                   "STOP_EPOCH_INVALIDATED", "DEVICE_DISPATCH_ATTEMPT",
                   "UI_PUBLICATION_QUEUE", "UI_PUBLICATION_APPLIED", "STOP_UI_APPLIED"))
LONG_MAX = 2 ** 63 - 1
STOP_DURATIONS = ("stopRequestToReceivedNanos", "stopReceivedToInvalidatedNanos", "stopRequestToUiAppliedNanos")


def fields(value, expected):
    if type(value) is not dict or set(value) != set(expected):
        raise ValueError("unexpected_schema_fields")


def integer(value, minimum=0, maximum=LONG_MAX):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError("invalid_integer_measurement")


def boolean(value):
    if type(value) is not bool:
        raise ValueError("invalid_boolean_measurement")


def text_value(value, maximum):
    if type(value) is not str or not 1 <= len(value) <= maximum or any(ord(char) < 32 for char in value):
        raise ValueError("invalid_bounded_device_value")


def difference(end, start):
    return end - start if start >= 0 and end >= start else -1


def validate_stop_timing(mode):
    for key in STOP_DURATIONS:
        integer(mode[key], -1)
    for key in ("stopEnqueuedNanos", "stopStateAppliedNanos"):
        value = mode[key]
        if value != -1 and not mode["startedNanos"] <= value <= mode["endedNanos"]:
            raise ValueError("stop_timestamp_outside_mode")
    if mode["mode"] != "STOP_WAITING_APPROVAL":
        if any(mode[key] != -1 for key in (*STOP_DURATIONS, "stopEnqueuedNanos", "stopStateAppliedNanos")):
            raise ValueError("unexpected_non_stop_timing")
        return
    groups = {stage: [event for event in mode["trace"] if event["stage"] == stage]
              for stage in ("STOP_RECEIVED", "STOP_EPOCH_INVALIDATED", "STOP_UI_APPLIED")}
    received = groups["STOP_RECEIVED"][0]["startNanos"] if groups["STOP_RECEIVED"] else -1
    invalidated = groups["STOP_EPOCH_INVALIDATED"][0]["startNanos"] if groups["STOP_EPOCH_INVALIDATED"] else -1
    applied = mode["stopStateAppliedNanos"]
    if applied != -1 and not any(event["startNanos"] + event["durationNanos"] == applied
                                for event in groups["STOP_UI_APPLIED"]):
        raise ValueError("stop_ui_timestamp_missing_from_trace")
    expected = (difference(received, mode["stopEnqueuedNanos"]), difference(invalidated, received),
                difference(applied, mode["stopEnqueuedNanos"]))
    if tuple(mode[key] for key in STOP_DURATIONS) != expected:
        raise ValueError("stop_duration_does_not_match_trace")
    if mode["status"] == "passed":
        if any(value < 0 for value in expected) or any(len(events) != 1 for events in groups.values()):
            raise ValueError("passed_stop_requires_complete_unique_events")
        received_event = groups["STOP_RECEIVED"][0]
        invalidated_event = groups["STOP_EPOCH_INVALIDATED"][0]
        ui_event = groups["STOP_UI_APPLIED"][0]
        if (invalidated < received + received_event["durationNanos"]
                or ui_event["startNanos"] < invalidated + invalidated_event["durationNanos"]):
            raise ValueError("inconsistent_stop_event_order")


def validate_report(value, api):
    fields(value, ("schemaVersion", "kind", "diagnosticOnly", "budgetAcceptance", "status",
                   "appProcessPid", "device", "conditions", "modes"))
    if (type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or value["kind"] != KIND or value["diagnosticOnly"] is not True
            or value["budgetAcceptance"] is not False or value["status"] not in ("passed", "failed")):
        raise ValueError("invalid_diagnostic_identity")
    integer(value["appProcessPid"], 1, 2 ** 31 - 1)
    device = value["device"]
    fields(device, ("sdkInt", "manufacturer", "model", "hardware", "fingerprint", "emulatorHeuristic"))
    integer(device["sdkInt"], api, api)
    for key in ("manufacturer", "model", "hardware", "fingerprint"):
        text_value(device[key], 1024 if key == "fingerprint" else 256)
    boolean(device["emulatorHeuristic"])
    expected_conditions = {"buildType": "debug", "processScope": "instrumented_app_with_sampler",
                           "localModelScope": "same_process_C_and_STOP_only", "fixtureProcessIncluded": False,
                           "screenshotSamplingIncluded": False, "forcedGc": False,
                           "systemAnimationOverrides": False, "windowMillis": 6000,
                           "sampleIntervalMillis": 1000, "order": "A_B_C_STOP"}
    fields(value["conditions"], expected_conditions)
    for key, expected in expected_conditions.items():
        actual = value["conditions"][key]
        if type(actual) is not type(expected) or actual != expected:
            raise ValueError("unexpected_diagnostic_conditions")
    modes = value["modes"]
    if type(modes) is not list or len(modes) > len(MODES):
        raise ValueError("invalid_mode_count")
    if value["status"] == "passed" and len(modes) != len(MODES):
        raise ValueError("incomplete_passed_diagnostics")
    previous_mode_end, previous_sample_time, previous_cpu = 0, 0, 0
    for index, mode in enumerate(modes):
        fields(mode, ("mode", "status", "startedNanos", "endedNanos", "samples", "sampleLimitReached",
                      "trace", "traceOverwrittenEvents", "traceCapacity", "stopEnqueuedNanos", "stopStateAppliedNanos",
                      *STOP_DURATIONS))
        if (mode["mode"] != MODES[index] or mode["status"] not in ("passed", "failed")
                or value["status"] == "passed" and mode["status"] != "passed"):
            raise ValueError("invalid_mode_order_or_status")
        integer(mode["startedNanos"], previous_mode_end)
        integer(mode["endedNanos"], mode["startedNanos"])
        previous_mode_end = mode["endedNanos"]
        integer(mode["stopEnqueuedNanos"], -1)
        integer(mode["stopStateAppliedNanos"], -1)
        integer(mode["traceOverwrittenEvents"])
        integer(mode["traceCapacity"], 4096, 4096)
        boolean(mode["sampleLimitReached"])
        if mode["status"] == "passed" and (mode["sampleLimitReached"] or mode["traceOverwrittenEvents"]):
            raise ValueError("passed_mode_lost_diagnostic_data")
        samples = mode["samples"]
        if (type(samples) is not list or len(samples) > 128
                or mode["status"] == "passed" and not samples):
            raise ValueError("invalid_sample_count")
        for sample in samples:
            fields(sample, ("elapsedRealtimeNanos", "processCpuMillis", "totalPssKiB", "dalvikPssKiB",
                            "nativePssKiB", "otherPssKiB", "collectionNanos"))
            for measurement in sample.values():
                integer(measurement)
            sampled_at = sample["elapsedRealtimeNanos"]
            if (not mode["startedNanos"] <= sampled_at <= mode["endedNanos"]
                    or sampled_at + sample["collectionNanos"] > mode["endedNanos"]
                    or sampled_at < previous_sample_time or sample["processCpuMillis"] < previous_cpu):
                raise ValueError("inconsistent_resource_sample_order_or_window")
            previous_sample_time, previous_cpu = sampled_at, sample["processCpuMillis"]
        trace = mode["trace"]
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
            if (event["startNanos"] < mode["startedNanos"]
                    or event["startNanos"] + event["durationNanos"] > mode["endedNanos"]):
                raise ValueError("trace_event_outside_mode_window")
        validate_stop_timing(mode)
    return value


def positive_int(value):
    return type(value) is int and value > 0


def strict_json(data):
    def pairs(entries):
        value = {}
        for key, item in entries:
            if key in value:
                raise ValueError("duplicate_json_key")
            value[key] = item
        return value

    def invalid_constant(_):
        raise ValueError("nonfinite_json_number")

    try:
        return json.loads(data, object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (UnicodeDecodeError, RecursionError) as error:
        raise ValueError("invalid_json_encoding_or_depth") from error


def read_json(path, maximum=MAX_REPORT_BYTES):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > maximum:
        raise ValueError("invalid_bounded_json_file")
    return strict_json(path.read_bytes())


def source_identity(environment):
    repository = environment.get("GITHUB_REPOSITORY", "")
    commit = environment.get("GITHUB_SHA", "")
    run_id = environment.get("GITHUB_RUN_ID", "")
    attempt = environment.get("GITHUB_RUN_ATTEMPT", "")
    if (not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
            or not re.fullmatch(r"[0-9a-f]{40}", commit)
            or not re.fullmatch(r"[1-9][0-9]{0,19}", run_id)
            or not re.fullmatch(r"[1-9][0-9]{0,5}", attempt)):
        raise ValueError("source_identity_unavailable")
    return {"repository": repository, "commit": commit,
            "runId": int(run_id), "attempt": int(attempt)}


def limit_report_output():
    # Linux CI only. Bound stdout before adb can fill the runner filesystem.
    import resource
    maximum = MAX_REPORT_BYTES + 1
    resource.setrlimit(resource.RLIMIT_FSIZE, (maximum, maximum))


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
        with tempfile.TemporaryDirectory(prefix="agent-performance-") as temporary:
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
    output = Path("performance-evidence") / ("api-" + args.matrix_label)
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
    print(f"API {args.matrix_label}: performance diagnostics {record['captureStatus']}; "
          f"Gradle exit={record['gradleExitCode']}; not a performance budget acceptance")


if __name__ == "__main__":
    main()
