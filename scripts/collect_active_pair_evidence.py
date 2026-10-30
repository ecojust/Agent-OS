#!/usr/bin/env python3
"""Collect bounded active task pairs with separate CPU task markers and PSS endpoints."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

from collect_performance_evidence import (LABELS, STAGES, LONG_MAX, fields, integer,
    boolean, text_value, positive_int, strict_json, source_identity)

REPORT_NAME = "active-paired-diagnostics.json"
KIND = "agent-active-paired-diagnostics"
TRIAL_KIND = "agent-active-paired-trial"
CAPTURE_KIND = "agent-active-paired-evidence-capture"
MAX_REPORT_BYTES = 65536
MAX_CAPTURE_BYTES = 16384
MAX_TRIAL_BYTES = 4 * 1024 * 1024
MAX_ARTIFACT_BYTES = 256 * 1024 * 1024
COLLECTION_SECONDS = 60
TRIAL_COUNT = 60
STATUSES = ("passed", "failed", "incomplete")
MODES = ("A_SERVICE_OFF_SCRIPTED_UI", "C_SERVICE_ON_AGENT")
CONDITIONS = {
    "buildType": "debug", "processScope": "instrumented_app_with_endpoint_probes",
    "fixtureProcessIncluded": False, "localModelScope": "same_process_both_arms_idle_in_A",
    "pairCount": 6, "repetitionsPerArm": 5, "trialCount": TRIAL_COUNT,
    "order": "AC_CA_AC_CA_AC_CA", "fixtureReset": "per_trial", "settleMillis": 2000,
    "sampling": "pre_post_outside_task", "taskTimeoutMillis": 30000,
    "maxTraceEventsPerTask": 4096, "task": "SAVE_AND_COUNT",
    "baselineDriver": "UiAutomation_accessibility_actions", "setupIncludedInTask": False,
    "screenshotSamplingIncluded": False, "forcedGc": False, "systemAnimationOverrides": False,
}

STATE_FLAGS = ("servicePresent", "serviceConnected", "controllerRunning", "controlAvailable", "fixtureFocused")
TRACE_FIELDS = ("trace", "traceCapacity", "traceOverwrittenEvents")
RESULT_KINDS = ("", "MODEL_REPORT", "VERIFIED", "STOPPED", "FAILED", "BUDGET_EXCEEDED")


def read_json(path, maximum=MAX_REPORT_BYTES):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > maximum:
        raise ValueError("invalid_bounded_json_file")
    return strict_json(path.read_bytes())


def digest(value):
    if type(value) is not str or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("invalid_file_digest")


def trial_name(index):
    return f"trial-{index:03d}.json"


def expected_mode(index):
    pair, in_pair = divmod(index, 10)
    position = in_pair // 5
    return MODES[position ^ (pair % 2)]


def validate_report(value, api):
    fields(value, ("schemaVersion", "kind", "diagnosticOnly", "budgetAcceptance", "status", "appProcessPid",
                   "device", "conditions", "startedNanos", "endedNanos", "cleanup", "trials"))
    if (type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or value["kind"] != KIND or value["diagnosticOnly"] is not True
            or value["budgetAcceptance"] is not False or value["status"] not in STATUSES):
        raise ValueError("invalid_active_pair_identity")
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
            raise ValueError("unexpected_active_pair_conditions")
    integer(value["startedNanos"])
    if value["status"] == "incomplete":
        integer(value["endedNanos"], -1, -1)
    else:
        integer(value["endedNanos"], value["startedNanos"])
    fields(value["cleanup"], ("controllerCleared", "servicesRestored", "enabledRestored", "automationFlagsRestored"))
    for flag in value["cleanup"].values():
        boolean(flag)
    if value["status"] == "passed" and not all(value["cleanup"].values()):
        raise ValueError("passed_report_cleanup_incomplete")
    refs = value["trials"]
    if type(refs) is not list or len(refs) > TRIAL_COUNT:
        raise ValueError("invalid_trial_count")
    if value["status"] == "passed" and len(refs) != TRIAL_COUNT:
        raise ValueError("incomplete_passed_active_pairs")
    previous_end = value["startedNanos"]
    failed = False
    total = MAX_REPORT_BYTES + MAX_CAPTURE_BYTES
    for index, ref in enumerate(refs):
        fields(ref, ("index", "path", "bytes", "sha256", "status", "startedNanos", "endedNanos"))
        integer(ref["index"], index, index)
        if (ref["path"] != trial_name(index) or ref["status"] not in ("passed", "failed")
                or failed or value["status"] == "passed" and ref["status"] != "passed"):
            raise ValueError("invalid_trial_prefix")
        integer(ref["bytes"], 1, MAX_TRIAL_BYTES)
        digest(ref["sha256"])
        integer(ref["startedNanos"], previous_end)
        integer(ref["endedNanos"], ref["startedNanos"])
        if value["endedNanos"] != -1 and ref["endedNanos"] > value["endedNanos"]:
            raise ValueError("trial_outside_report")
        total += ref["bytes"]
        if total > MAX_ARTIFACT_BYTES:
            raise ValueError("bundle_exceeds_collection_budget")
        previous_end = ref["endedNanos"]
        failed = ref["status"] == "failed"
    return value


def validate_state(state, mode, passed, minimum, maximum):
    fields(state, ("checkedNanos", *STATE_FLAGS))
    integer(state["checkedNanos"], minimum, maximum)
    for flag in STATE_FLAGS:
        boolean(state[flag])
    connected = mode == MODES[1]
    if passed and (state["servicePresent"] != connected or state["serviceConnected"] != connected
                   or state["controllerRunning"] or state["controlAvailable"] or not state["fixtureFocused"]):
        raise ValueError("passed_trial_has_wrong_endpoint_state")


def validate_sample(sample, trial):
    fields(sample, ("elapsedRealtimeNanos", "processCpuMillis", "totalPssKiB", "dalvikPssKiB",
                    "nativePssKiB", "otherPssKiB", "collectionNanos"))
    for value in sample.values():
        integer(value)
    if (sample["elapsedRealtimeNanos"] < trial["startedNanos"]
            or sample["elapsedRealtimeNanos"] + sample["collectionNanos"] > trial["endedNanos"]):
        raise ValueError("resource_sample_outside_trial")


def validate_trace(trial):
    integer(trial["traceCapacity"], 4096, 4096)
    integer(trial["traceOverwrittenEvents"])
    trace = trial["trace"]
    if type(trace) is not list or len(trace) > 4096:
        raise ValueError("invalid_trace_count")
    if trial["status"] == "passed" and trial["traceOverwrittenEvents"]:
        raise ValueError("passed_trial_lost_trace")
    if trace and (trial["traceStartedNanos"] == -1 or trial["traceEndedNanos"] == -1):
        raise ValueError("trace_missing_boundaries")
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
        if (event["startNanos"] < trial["traceStartedNanos"]
                or event["startNanos"] + event["durationNanos"] > trial["traceEndedNanos"]):
            raise ValueError("trace_event_outside_task_trace")


def validate_trial(trial, index):
    fields(trial, ("schemaVersion", "kind", "index", "pairIndex", "position", "repetition", "mode", "status",
                   "startedNanos", "endedNanos", "preparedNanos", "initialStateVerified", "stateBefore", "stateAfter",
                   "beforeSample", "afterSample", "taskStartedNanos", "taskEndedNanos", "taskStartedCpuMillis",
                   "taskEndedCpuMillis", "manualActionsAttempted", "manualActionsAccepted", "modelRequests",
                   "approvalCount", "modelAcceptedWrites", "editVerified", "savedTextVerified", "counterVerified",
                   "resultKind", "resultCode", "traceStartedNanos", "traceEndedNanos", "trace", "traceCapacity",
                   "traceOverwrittenEvents", "cleanupCompleted"))
    integer(index, 0, TRIAL_COUNT - 1)
    if (type(trial["schemaVersion"]) is not int or trial["schemaVersion"] != 1
            or trial["kind"] != TRIAL_KIND or trial["mode"] != expected_mode(index)
            or trial["status"] not in ("passed", "failed")):
        raise ValueError("invalid_trial_identity")
    for field, expected in (("index", index), ("pairIndex", index // 10), ("position", index // 5 % 2),
                            ("repetition", index % 5)):
        integer(trial[field], expected, expected)
    passed = trial["status"] == "passed"
    integer(trial["startedNanos"])
    integer(trial["endedNanos"], trial["startedNanos"])
    marker_names = ("preparedNanos", "taskStartedNanos", "taskEndedNanos", "traceStartedNanos", "traceEndedNanos")
    for key in marker_names:
        integer(trial[key], -1)
        if trial[key] != -1 and not trial["startedNanos"] <= trial[key] <= trial["endedNanos"]:
            raise ValueError("task_marker_outside_trial")
    for key in ("taskStartedCpuMillis", "taskEndedCpuMillis", "manualActionsAttempted", "manualActionsAccepted",
                "modelRequests", "approvalCount", "modelAcceptedWrites"):
        integer(trial[key], -1)
    flags = ("initialStateVerified", "editVerified", "savedTextVerified", "counterVerified", "cleanupCompleted")
    for key in flags:
        boolean(trial[key])
    if type(trial["resultKind"]) is not str or trial["resultKind"] not in RESULT_KINDS:
        raise ValueError("invalid_task_result_kind")
    if type(trial["resultCode"]) is not str or not re.fullmatch(r"[A-Z0-9_]{0,80}", trial["resultCode"]):
        raise ValueError("invalid_task_result_code")
    # Known partial measurements retain the same bounds. Unknown markers on a
    # failed trial stay -1 and are never filled from an adjacent trial.
    def ordered(start, end, strict=False):
        if start != -1 and end != -1 and (end < start or strict and end == start):
            raise ValueError("inconsistent_task_marker_order")
    for start, end in (("preparedNanos", "taskStartedNanos"), ("traceStartedNanos", "taskStartedNanos"),
                       ("taskEndedNanos", "traceEndedNanos"), ("traceStartedNanos", "traceEndedNanos")):
        ordered(trial[start], trial[end])
    ordered(trial["taskStartedNanos"], trial["taskEndedNanos"], strict=True)
    ordered(trial["taskStartedCpuMillis"], trial["taskEndedCpuMillis"])
    if trial["taskEndedNanos"] != -1 and trial["taskStartedNanos"] == -1:
        raise ValueError("task_end_without_start")
    if trial["traceEndedNanos"] != -1 and trial["traceStartedNanos"] == -1:
        raise ValueError("trace_end_without_start")
    if (trial["manualActionsAttempted"] >= 0 and trial["manualActionsAccepted"] >= 0
            and trial["manualActionsAccepted"] > trial["manualActionsAttempted"]):
        raise ValueError("accepted_manual_actions_exceed_attempts")
    for key in ("stateBefore", "stateAfter"):
        if trial[key] is not None:
            validate_state(trial[key], trial["mode"], passed, trial["startedNanos"], trial["endedNanos"])
    for key in ("beforeSample", "afterSample"):
        if trial[key] is not None:
            validate_sample(trial[key], trial)
    before, after = trial["beforeSample"], trial["afterSample"]
    if trial["stateBefore"] is not None:
        ordered(trial["preparedNanos"], trial["stateBefore"]["checkedNanos"])
    if before is not None:
        ordered(before["elapsedRealtimeNanos"] + before["collectionNanos"], trial["taskStartedNanos"])
        ordered(before["processCpuMillis"], trial["taskStartedCpuMillis"])
        if trial["stateBefore"] is not None:
            ordered(trial["stateBefore"]["checkedNanos"], before["elapsedRealtimeNanos"])
    if after is not None:
        ordered(trial["taskEndedNanos"], after["elapsedRealtimeNanos"])
        ordered(trial["traceEndedNanos"], after["elapsedRealtimeNanos"])
        ordered(trial["taskEndedCpuMillis"], after["processCpuMillis"])
        if trial["stateAfter"] is not None:
            ordered(after["elapsedRealtimeNanos"] + after["collectionNanos"], trial["stateAfter"]["checkedNanos"])
    if before is not None and after is not None:
        ordered(before["elapsedRealtimeNanos"] + before["collectionNanos"], after["elapsedRealtimeNanos"])
        ordered(before["processCpuMillis"], after["processCpuMillis"])
    validate_trace(trial)
    if passed:
        if (any(trial[key] == -1 for key in (*marker_names, "taskStartedCpuMillis", "taskEndedCpuMillis"))
                or before is None or after is None or trial["stateBefore"] is None or trial["stateAfter"] is None
                or not all(trial[key] for key in flags)):
            raise ValueError("passed_trial_missing_measurement_or_verification")
        if trial["mode"] == MODES[0]:
            if (trial["manualActionsAttempted"] != 3 or trial["manualActionsAccepted"] != 3
                    or trial["modelRequests"] != 0 or trial["approvalCount"] != 0 or trial["modelAcceptedWrites"] != 0
                    or trial["resultKind"] != "" or trial["resultCode"] != "" or trial["trace"]):
                raise ValueError("passed_manual_trial_contract_mismatch")
        elif (trial["manualActionsAttempted"] != 0 or trial["manualActionsAccepted"] != 0
                or trial["modelRequests"] <= 0 or trial["approvalCount"] < 3 or trial["modelAcceptedWrites"] != 3
                or trial["resultKind"] != "MODEL_REPORT" or trial["resultCode"] != "MODEL_REPORT"
                or sum(event["stage"] == "DEVICE_DISPATCH_ATTEMPT" for event in trial["trace"]) < 3):
            raise ValueError("passed_agent_trial_contract_mismatch")
    return trial


def validate_bundle(directory, value, api):
    directory = Path(directory)
    if any(p.is_symlink() for p in (directory, *directory.parents)) or not directory.is_dir():
        raise ValueError("invalid_bundle_directory")
    validate_report(value, api)
    expected = {REPORT_NAME, *(ref["path"] for ref in value["trials"])}
    actual = set()
    for path in directory.iterdir():
        if path.is_symlink() or not path.is_file():
            raise ValueError("invalid_bundle_entry")
        actual.add(path.name)
    if actual - {"capture.json"} != expected:
        raise ValueError("bundle_file_set_mismatch")
    if read_json(directory / REPORT_NAME) != value:
        raise ValueError("bundle_root_differs")
    total = (directory / REPORT_NAME).stat().st_size
    parsed = []
    previous_time, previous_cpu = 0, 0
    for ref in value["trials"]:
        path = directory / ref["path"]
        if path.stat().st_size != ref["bytes"]:
            raise ValueError("trial_file_size_mismatch")
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != ref["sha256"]:
            raise ValueError("trial_file_hash_mismatch")
        trial = strict_json(data)
        validate_trial(trial, ref["index"])
        for key in ("index", "status", "startedNanos", "endedNanos"):
            if trial[key] != ref[key] or type(trial[key]) is not type(ref[key]):
                raise ValueError("trial_reference_mismatch")
        measurements = []
        if trial["beforeSample"] is not None:
            point = trial["beforeSample"]
            measurements.append((point["elapsedRealtimeNanos"], point["processCpuMillis"]))
        for prefix in ("taskStarted", "taskEnded"):
            if trial[prefix + "Nanos"] != -1 and trial[prefix + "CpuMillis"] != -1:
                measurements.append((trial[prefix + "Nanos"], trial[prefix + "CpuMillis"]))
        if trial["afterSample"] is not None:
            point = trial["afterSample"]
            measurements.append((point["elapsedRealtimeNanos"], point["processCpuMillis"]))
        for measured_at, cpu in measurements:
            if measured_at < previous_time or cpu < previous_cpu:
                raise ValueError("cross_trial_resource_counter_regression")
            previous_time, previous_cpu = measured_at, cpu
        total += len(data)
        if total + MAX_CAPTURE_BYTES > MAX_ARTIFACT_BYTES:
            raise ValueError("bundle_exceeds_collection_budget")
        parsed.append(trial)
    return parsed


def empty_capture(label, source):
    return {"schemaVersion": 1, "kind": CAPTURE_KIND, "diagnosticOnly": True, "budgetAcceptance": False,
            "matrixLabel": label, "apiLevel": int(label.split(".")[0]), "actualSdkInt": None,
            "sourceRun": source, "capturedAt": datetime.now(timezone.utc).isoformat(),
            "declaredCiProfile": "default_x86_64_swiftshader_animation_one",
            "captureStatus": "not_collected", "captureError": "emulator_script_did_not_finish", "gradleExitCode": None, "report": None}


def limited_output(maximum):
    def apply():
        import resource
        resource.setrlimit(resource.RLIMIT_FSIZE, (maximum + 1, maximum + 1))
    return apply


def remaining_timeout(deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ValueError("collection_deadline_exceeded")
    return min(5, remaining)


def pull_file(name, target, maximum, deadline):
    with target.open("wb") as output:
        result = subprocess.run(["adb", "exec-out", "run-as", "dev.agentos.app", "cat", "files/performance/active-pairs/" + name],
                                stdout=output, stderr=subprocess.DEVNULL, check=False,
                                timeout=remaining_timeout(deadline), preexec_fn=limited_output(maximum))
    if target.stat().st_size > maximum:
        raise ValueError("report_exceeds_collection_budget")
    if result.returncode != 0:
        raise ValueError("committed_report_unavailable")


def collect(label, source, exit_code, output):
    output = Path(output)
    # Remove only this collector's fixed output names, never arbitrary files.
    for name in (REPORT_NAME, *(trial_name(i) for i in range(TRIAL_COUNT))):
        (output / name).unlink(missing_ok=True)
    record = empty_capture(label, source)
    record.update(gradleExitCode=exit_code, captureStatus="unavailable", captureError="device_or_report_unavailable")
    deadline = time.monotonic() + COLLECTION_SECONDS
    try:
        # Bound SDK discovery too: a broken adb/device must not allocate unbounded
        # capture_output memory before the committed evidence reads even begin.
        with tempfile.TemporaryFile() as sdk_output:
            subprocess.run(["adb", "shell", "getprop", "ro.build.version.sdk"], stdout=sdk_output,
                           stderr=subprocess.DEVNULL, check=True, timeout=remaining_timeout(deadline),
                           preexec_fn=limited_output(32))
            sdk_output.seek(0)
            sdk_bytes = sdk_output.read(33)
        if len(sdk_bytes) > 32:
            raise ValueError("sdk_output_exceeds_collection_budget")
        try:
            actual = sdk_bytes.decode("ascii").strip()
        except UnicodeDecodeError as error:
            raise ValueError("invalid_sdk_output") from error
        if not actual.isdigit() or int(actual) != record["apiLevel"]:
            raise ValueError("device_sdk_mismatch")
        record["actualSdkInt"] = int(actual)
        with tempfile.TemporaryDirectory(prefix="agent-active-pair-") as temporary:
            folder = Path(temporary)
            pull_file(REPORT_NAME, folder / REPORT_NAME, MAX_REPORT_BYTES, deadline)
            report = validate_report(read_json(folder / REPORT_NAME), record["apiLevel"])
            for ref in report["trials"]:
                pull_file(ref["path"], folder / ref["path"], MAX_TRIAL_BYTES, deadline)
            validate_bundle(folder, report, record["apiLevel"])
            remaining_timeout(deadline)
            for path in folder.iterdir():
                shutil.copyfile(path, output / path.name)
            data = (folder / REPORT_NAME).read_bytes()
            record.update(captureStatus="collected", captureError=None,
                          report={"path": REPORT_NAME, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "status": report["status"]})
    except ValueError as error:
        if str(error) == "committed_report_unavailable":
            record["captureError"] = str(error)
        else:
            record["captureStatus"] = "invalid"
            record["captureError"] = str(error) if re.fullmatch(r"[a-z_]{1,80}", str(error)) else "invalid_report"
    except (OSError, subprocess.SubprocessError):
        pass
    if record["captureStatus"] != "collected":
        for name in (REPORT_NAME, *(trial_name(i) for i in range(TRIAL_COUNT))):
            (output / name).unlink(missing_ok=True)
    return record


def validate_capture(value, label, source):
    expected = {"schemaVersion", "kind", "diagnosticOnly", "budgetAcceptance", "matrixLabel", "apiLevel", "actualSdkInt",
                "sourceRun", "capturedAt", "declaredCiProfile", "captureStatus", "captureError", "gradleExitCode", "report"}
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
            or value["kind"] != CAPTURE_KIND or value["diagnosticOnly"] is not True or value["budgetAcceptance"] is not False
            or value["sourceRun"] != source or value["matrixLabel"] != label
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
        digest(report["sha256"])
        if (value["actualSdkInt"] != api or value["captureError"] is not None
                or report["path"] != REPORT_NAME or report["status"] not in STATUSES):
            raise ValueError("invalid_report_reference")
    elif (value["captureStatus"] not in ("not_collected", "unavailable", "invalid") or value["report"] is not None
          or type(value["captureError"]) is not str or not re.fullmatch(r"[a-z_]{1,80}", value["captureError"])):
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
    output = Path("active-pair-evidence") / ("api-" + args.matrix_label)
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
    print(f"API {args.matrix_label}: active task pairs {record['captureStatus']}; Gradle exit={record['gradleExitCode']}; diagnostic only")


if __name__ == "__main__":
    main()
