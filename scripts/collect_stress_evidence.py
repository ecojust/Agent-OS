#!/usr/bin/env python3
"""Collect bounded, committed stress segments without promoting partial runs to passes."""

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

REPORT_NAME = "stress-diagnostics.json"
KIND = "agent-segmented-stress-diagnostics"
SEGMENT_KIND = "agent-stress-segment"
CAPTURE_KIND = "agent-segmented-stress-evidence-capture"
MAX_REPORT_BYTES = 65536
MAX_CAPTURE_BYTES = 16384
MAX_SEGMENT_BYTES = 4 * 1024 * 1024
MAX_ARTIFACT_BYTES = 128 * 1024 * 1024
COLLECTION_SECONDS = 60
SEGMENT_COUNT = 30
STATUSES = ("passed", "failed", "incomplete")
CONDITIONS = {
    "buildType": "debug", "processScope": "instrumented_app_with_sampler",
    "fixtureProcessIncluded": False, "localModelScope": "same_process",
    "minimumDurationMillis": 1800000, "segmentCount": SEGMENT_COUNT,
    "minimumSegmentMillis": 60000, "recoveryMinimumMillis": 10000,
    "sampleIntervalMillis": 5000, "maxSamplesPerSegment": 128,
    "maxTraceEventsPerTask": 4096,
    "taskOrder": "SAVE_AND_COUNT_STOP_WAITING_APPROVAL_SERVICE_OFF",
    "fixtureReset": "per_task", "forcedGc": False,
    "systemAnimationOverrides": False, "screenshotSamplingIncluded": False,
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


def segment_name(index):
    return f"segment-{index:03d}.json"


def validate_report(value, api):
    fields(value, ("schemaVersion", "kind", "diagnosticOnly", "budgetAcceptance", "status", "appProcessPid",
                   "device", "conditions", "startedNanos", "endedNanos", "cleanup", "segments"))
    if (type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1
            or value["kind"] != KIND or value["diagnosticOnly"] is not True
            or value["budgetAcceptance"] is not False or value["status"] not in STATUSES):
        raise ValueError("invalid_stress_identity")
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
            raise ValueError("unexpected_stress_conditions")
    integer(value["startedNanos"])
    if value["status"] == "incomplete":
        integer(value["endedNanos"], -1, -1)
    else:
        integer(value["endedNanos"], value["startedNanos"])
    fields(value["cleanup"], ("controllerCleared", "servicesRestored", "enabledRestored"))
    for flag in value["cleanup"].values():
        boolean(flag)
    if value["status"] == "passed" and not all(value["cleanup"].values()):
        raise ValueError("passed_stress_cleanup_incomplete")
    refs = value["segments"]
    if type(refs) is not list or len(refs) > SEGMENT_COUNT:
        raise ValueError("invalid_segment_count")
    if value["status"] == "passed" and len(refs) != SEGMENT_COUNT:
        raise ValueError("incomplete_passed_stress")
    previous_end = value["startedNanos"]
    duration = 0
    failed = False
    for index, ref in enumerate(refs):
        fields(ref, ("index", "path", "bytes", "sha256", "status", "startedNanos", "endedNanos"))
        integer(ref["index"], index, index)
        if (ref["path"] != segment_name(index) or ref["status"] not in ("passed", "failed")
                or failed or value["status"] == "passed" and ref["status"] != "passed"):
            raise ValueError("invalid_segment_prefix")
        integer(ref["bytes"], 1, MAX_SEGMENT_BYTES)
        digest(ref["sha256"])
        integer(ref["startedNanos"], previous_end)
        integer(ref["endedNanos"], ref["startedNanos"])
        if value["endedNanos"] != -1 and ref["endedNanos"] > value["endedNanos"]:
            raise ValueError("segment_outside_report")
        elapsed = ref["endedNanos"] - ref["startedNanos"]
        if ref["status"] == "passed" and elapsed < CONDITIONS["minimumSegmentMillis"] * 1_000_000:
            raise ValueError("passed_segment_shorter_than_planned")
        duration += elapsed
        previous_end = ref["endedNanos"]
        failed = ref["status"] == "failed"
    minimum = CONDITIONS["minimumDurationMillis"] * 1_000_000
    if value["status"] == "passed" and (value["endedNanos"] - value["startedNanos"] < minimum or duration < minimum):
        raise ValueError("passed_stress_shorter_than_thirty_minutes")
    return value


def validate_trace(phase):
    integer(phase["traceCapacity"], 4096, 4096)
    integer(phase["traceOverwrittenEvents"])
    trace = phase["trace"]
    if type(trace) is not list or len(trace) > 4096:
        raise ValueError("invalid_trace_count")
    if phase["status"] == "passed" and phase["traceOverwrittenEvents"]:
        raise ValueError("passed_phase_lost_trace")
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
        if (event["startNanos"] < phase["startedNanos"]
                or event["startNanos"] + event["durationNanos"] > phase["endedNanos"]):
            raise ValueError("trace_event_outside_phase")


def validate_state(value, passed, minimum, maximum):
    fields(value, ("checkedNanos", *STATE_FLAGS))
    integer(value["checkedNanos"], minimum, maximum)
    for name in STATE_FLAGS:
        boolean(value[name])
    if passed and (any(value[name] for name in STATE_FLAGS[:-1]) or not value["fixtureFocused"]):
        raise ValueError("passed_recovery_has_wrong_state")


def validate_phase(phase, name, segment, previous_end):
    common = ("status", "startedNanos", "endedNanos", *TRACE_FIELDS)
    if name == "active":
        expected = (*common, "resultKind", "resultCode", "acceptedWrites", "finalStateObserved", "counterVerified", "savedTextVerified")
    elif name == "stop":
        expected = (*common, "resultKind", "resultCode", "acceptedWrites", "approvalReached", "counterVerified", "stopEnqueuedNanos", "stopStateAppliedNanos")
    else:
        expected = (*common, "stateBefore", "stateAfter")
    fields(phase, expected)
    if phase["status"] not in ("passed", "failed") or segment["status"] == "passed" and phase["status"] != "passed":
        raise ValueError("invalid_phase_status")
    passed = phase["status"] == "passed"
    integer(phase["startedNanos"], previous_end, segment["endedNanos"])
    integer(phase["endedNanos"], phase["startedNanos"], segment["endedNanos"])
    validate_trace(phase)
    if name in ("active", "stop"):
        if phase["resultKind"] not in RESULT_KINDS or type(phase["resultKind"]) is not str:
            raise ValueError("invalid_task_result_kind")
        if type(phase["resultCode"]) is not str or not re.fullmatch(r"[A-Z0-9_]{0,80}", phase["resultCode"]):
            raise ValueError("invalid_task_result_code")
        integer(phase["acceptedWrites"], -1)
        boolean(phase["counterVerified"])
    if name == "active":
        boolean(phase["finalStateObserved"])
        boolean(phase["savedTextVerified"])
        if passed and (phase["resultKind"] != "MODEL_REPORT" or phase["resultCode"] != "MODEL_REPORT"
                       or phase["acceptedWrites"] != 3 or not phase["finalStateObserved"]
                       or not phase["counterVerified"] or not phase["savedTextVerified"]):
            raise ValueError("passed_active_task_contract_mismatch")
    elif name == "stop":
        boolean(phase["approvalReached"])
        for key in ("stopEnqueuedNanos", "stopStateAppliedNanos"):
            integer(phase[key], -1)
            if phase[key] != -1 and not phase["startedNanos"] <= phase[key] <= phase["endedNanos"]:
                raise ValueError("stop_timestamp_outside_phase")
        groups = {stage: [e for e in phase["trace"] if e["stage"] == stage]
                  for stage in ("STOP_RECEIVED", "STOP_EPOCH_INVALIDATED", "STOP_UI_APPLIED")}
        applied = phase["stopStateAppliedNanos"]
        if applied != -1 and not any(e["startNanos"] + e["durationNanos"] == applied for e in groups["STOP_UI_APPLIED"]):
            raise ValueError("stop_ui_timestamp_missing_from_trace")
        if passed:
            if (phase["resultKind"] != "STOPPED" or phase["resultCode"] != "TASK_STOPPED"
                    or phase["acceptedWrites"] != 0 or not phase["approvalReached"] or not phase["counterVerified"]
                    or phase["stopEnqueuedNanos"] < 0 or applied < 0 or any(len(events) != 1 for events in groups.values())):
                raise ValueError("passed_stop_task_contract_mismatch")
            received, invalidated, ui = (groups[name][0] for name in ("STOP_RECEIVED", "STOP_EPOCH_INVALIDATED", "STOP_UI_APPLIED"))
            if (received["startNanos"] < phase["stopEnqueuedNanos"]
                    or invalidated["startNanos"] < received["startNanos"] + received["durationNanos"]
                    or ui["startNanos"] < invalidated["startNanos"] + invalidated["durationNanos"]):
                raise ValueError("inconsistent_stop_event_order")
            if invalidated["dispatchAttempts"] != 0 or any(e["stage"] == "DEVICE_DISPATCH_ATTEMPT" for e in phase["trace"]):
                raise ValueError("passed_stop_requires_zero_dispatches")
    else:
        validate_state(phase["stateBefore"], passed, previous_end, phase["startedNanos"])
        if phase["stateAfter"] is None:
            if passed:
                raise ValueError("passed_recovery_missing_after_state")
        else:
            validate_state(phase["stateAfter"], passed, phase["endedNanos"], segment["endedNanos"])
        if passed and (phase["endedNanos"] - phase["startedNanos"] < CONDITIONS["recoveryMinimumMillis"] * 1_000_000
                       or phase["trace"]):
            raise ValueError("passed_recovery_contract_mismatch")
    return phase["stateAfter"]["checkedNanos"] if name == "recovery" and phase["stateAfter"] is not None else phase["endedNanos"]


def validate_segment(value, index):
    fields(value, ("schemaVersion", "kind", "index", "status", "startedNanos", "endedNanos", "samples", "sampleLimitReached", "active", "stop", "recovery"))
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] != 1 or value["kind"] != SEGMENT_KIND or value["status"] not in ("passed", "failed"):
        raise ValueError("invalid_segment_identity")
    integer(value["index"], index, index)
    integer(value["startedNanos"])
    integer(value["endedNanos"], value["startedNanos"])
    passed = value["status"] == "passed"
    if passed and value["endedNanos"] - value["startedNanos"] < CONDITIONS["minimumSegmentMillis"] * 1_000_000:
        raise ValueError("passed_segment_shorter_than_planned")
    boolean(value["sampleLimitReached"])
    if passed and value["sampleLimitReached"]:
        raise ValueError("passed_segment_lost_samples")
    samples = value["samples"]
    if type(samples) is not list or len(samples) > 128 or passed and len(samples) < 10:
        raise ValueError("invalid_resource_sample_count")
    prior_time, prior_cpu = 0, 0
    for sample in samples:
        fields(sample, ("elapsedRealtimeNanos", "processCpuMillis", "totalPssKiB", "dalvikPssKiB", "nativePssKiB", "otherPssKiB", "collectionNanos", "phase"))
        for name, measurement in sample.items():
            if name != "phase":
                integer(measurement)
        if sample["phase"] not in ("setup", "active", "stop", "recovery"):
            raise ValueError("invalid_resource_sample_phase")
        sampled_at = sample["elapsedRealtimeNanos"]
        if (sampled_at < prior_time or sample["processCpuMillis"] < prior_cpu
                or sampled_at < value["startedNanos"] or sampled_at + sample["collectionNanos"] > value["endedNanos"]):
            raise ValueError("invalid_resource_sample_order_or_window")
        prior_time, prior_cpu = sampled_at, sample["processCpuMillis"]
    if passed and samples[-1]["elapsedRealtimeNanos"] - samples[0]["elapsedRealtimeNanos"] < 55_000_000_000:
        raise ValueError("passed_segment_insufficient_sample_span")
    boundary, terminal = value["startedNanos"], False
    for name in ("active", "stop", "recovery"):
        phase = value[name]
        if phase is None:
            if passed:
                raise ValueError("passed_segment_missing_phase")
            terminal = True
            continue
        if terminal:
            raise ValueError("phase_after_failed_or_missing_phase")
        boundary = validate_phase(phase, name, value, boundary)
        terminal = phase["status"] == "failed"
    return value


def validate_bundle(directory, value, api):
    directory = Path(directory)
    if any(p.is_symlink() for p in (directory, *directory.parents)) or not directory.is_dir():
        raise ValueError("invalid_bundle_directory")
    validate_report(value, api)
    expected = {REPORT_NAME, *(ref["path"] for ref in value["segments"])}
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
    for ref in value["segments"]:
        path = directory / ref["path"]
        if path.stat().st_size != ref["bytes"]:
            raise ValueError("segment_file_size_mismatch")
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != ref["sha256"]:
            raise ValueError("segment_file_hash_mismatch")
        segment = strict_json(data)
        validate_segment(segment, ref["index"])
        for key in ("index", "status", "startedNanos", "endedNanos"):
            if segment[key] != ref[key] or type(segment[key]) is not type(ref[key]):
                raise ValueError("segment_reference_mismatch")
        for sample in segment["samples"]:
            if sample["elapsedRealtimeNanos"] < previous_time or sample["processCpuMillis"] < previous_cpu:
                raise ValueError("cross_segment_resource_counter_regression")
            previous_time, previous_cpu = sample["elapsedRealtimeNanos"], sample["processCpuMillis"]
        total += len(data)
        if total + MAX_CAPTURE_BYTES > MAX_ARTIFACT_BYTES:
            raise ValueError("bundle_exceeds_collection_budget")
        parsed.append(segment)
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
        result = subprocess.run(["adb", "exec-out", "run-as", "dev.agentos.app", "cat", "files/performance/stress/" + name],
                                stdout=output, stderr=subprocess.DEVNULL, check=False,
                                timeout=remaining_timeout(deadline), preexec_fn=limited_output(maximum))
    if target.stat().st_size > maximum:
        raise ValueError("report_exceeds_collection_budget")
    if result.returncode != 0:
        raise ValueError("committed_report_unavailable")


def collect(label, source, exit_code, output):
    output = Path(output)
    # Remove only this collector's fixed output names, never arbitrary files.
    for name in (REPORT_NAME, *(segment_name(i) for i in range(SEGMENT_COUNT))):
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
        with tempfile.TemporaryDirectory(prefix="agent-stress-") as temporary:
            folder = Path(temporary)
            pull_file(REPORT_NAME, folder / REPORT_NAME, MAX_REPORT_BYTES, deadline)
            report = validate_report(read_json(folder / REPORT_NAME), record["apiLevel"])
            for ref in report["segments"]:
                pull_file(ref["path"], folder / ref["path"], MAX_SEGMENT_BYTES, deadline)
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
        for name in (REPORT_NAME, *(segment_name(i) for i in range(SEGMENT_COUNT))):
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
    output = Path("stress-evidence") / ("api-" + args.matrix_label)
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
    print(f"API {args.matrix_label}: segmented stress {record['captureStatus']}; Gradle exit={record['gradleExitCode']}; diagnostic only")


if __name__ == "__main__":
    main()
