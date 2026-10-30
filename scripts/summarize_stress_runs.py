#!/usr/bin/env python3
"""Summarize committed segmented stress evidence without claiming release budgets."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys

import collect_stress_evidence as evidence
import collect_performance_evidence as common_evidence

REPOSITORY = "ecojust/Agent-OS"
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
SEGMENT_COUNT = 30
TASK_PHASES = ("active", "stop")
METRICS = ("wallMillis", "resourceSampleSpanMillis", "cpuPercentOneCore", "sampleMeanTotalPssKiB",
           "sampleMaxTotalPssKiB", "sampleMeanNativePssKiB", "sampleMaxNativePssKiB",
           "sampleCollectionMaxMillis", "recoveryTaggedSampleMeanTotalPssKiB",
           "recoveryTaggedSampleMaxTotalPssKiB")
LIMITS = [
    "This validates local archived identities and hash chains, not GitHub authenticity, UI evidence or physical-device budgets.",
    "Thirty segments share one instrumented resident process per API; segments are not independent devices or process restarts.",
    "The measured interval mixes task setup, a completed synthetic task, a pending-approval stop and service-off recovery; it is not thirty minutes of CPU saturation.",
    "CPU uses actual first/last sample times and one logical core = 100%; instrumentation, sampler, local model and segment recording overhead are included; Fixture and system processes are excluded.",
    "Recovery phase tags are sampled at collection start and are not atomic proof of the service or task state throughout collection.",
    "PSS means are arithmetic sample means, maxima are observed samples, and endpoint differences do not prove absence or cause of memory leaks.",
    "Complete passed segments in a failed or incomplete report may be described; the report, workflow and job conclusions remain unchanged.",
    "An incomplete checkpoint proves only its committed prefix; uncommitted slots are unavailable, not asserted never to have executed.",
    "Failed, not-executed and unavailable segments and tasks stay in their fixed denominators; no replacement segments are selected.",
    "Service-off endpoint checks and bounded stop traces do not prove continuous system state or zero actions for all future time.",
    "No P95/P99, causal effect, target-application experience, system-wide ANR absence or performance-budget acceptance is produced.",
]


def checked_path(path):
    path = Path(path).absolute()
    if ".." in path.parts:
        raise ValueError("path_traversal_is_not_supported")
    if any(part.is_symlink() for part in (path, *path.parents)):
        raise ValueError("symlink_in_evidence_or_output_path")
    return path


def checked_json(path, maximum):
    path = checked_path(path)
    if not path.is_file() or path.stat().st_size > maximum:
        raise ValueError("missing_or_oversized_json")
    data = path.read_bytes()
    return evidence.strict_json(data), hashlib.sha256(data).hexdigest()


def verify_files(directory, references):
    if type(references) is not list or len(references) > SEGMENT_COUNT + 2:
        raise ValueError("invalid_stress_file_list")
    expected = {}
    for ref in references:
        evidence.fields(ref, ("path", "bytes", "sha256"))
        name = ref["path"]
        if name == "capture.json":
            maximum = evidence.MAX_CAPTURE_BYTES
        elif name == evidence.REPORT_NAME:
            maximum = evidence.MAX_REPORT_BYTES
        elif type(name) is str and re.fullmatch(r"segment-0[0-2][0-9]\.json", name):
            maximum = evidence.MAX_SEGMENT_BYTES
        else:
            raise ValueError("unexpected_stress_path")
        if name in expected:
            raise ValueError("duplicate_stress_path")
        evidence.integer(ref["bytes"], 1, maximum)
        if type(ref["sha256"]) is not str or not re.fullmatch(r"[0-9a-f]{64}", ref["sha256"]):
            raise ValueError("invalid_stress_digest")
        path = checked_path(directory / name)
        if not path.is_file() or path.stat().st_size != ref["bytes"]:
            raise ValueError("stress_file_size_mismatch")
        if hashlib.sha256(path.read_bytes()).hexdigest() != ref["sha256"]:
            raise ValueError("stress_file_hash_mismatch")
        expected[name] = ref
    if sum(ref["bytes"] for ref in expected.values()) > evidence.MAX_ARTIFACT_BYTES:
        raise ValueError("stress_files_exceed_total_budget")
    directory = checked_path(directory)
    actual = set()
    if directory.exists():
        if not directory.is_dir():
            raise ValueError("invalid_stress_directory")
        for entry in directory.iterdir():
            checked_path(entry)
            if not entry.is_file():
                raise ValueError("unexpected_stress_directory_entry")
            actual.add(entry.name)
    if actual != set(expected):
        raise ValueError("stress_file_set_mismatch")
    return expected


def task_placeholder(phase, status, reason):
    return {"phase": phase, "status": status, "reason": reason, "acceptedWrites": None}


def planned_segments(reason, status="unavailable"):
    return [{"index": index, "status": status, "reason": reason, "eligible": False,
             "tasks": {phase: task_placeholder(phase, status, reason) for phase in TASK_PHASES}}
            for index in range(SEGMENT_COUNT)]


def summarize_task(phase, value):
    if value is None:
        return task_placeholder(phase, "not_executed", "phase_not_started")
    result = {key: item for key, item in value.items() if key != "trace"}
    result.update(phase=phase, reason=None if value["status"] == "passed" else "phase_failed",
                  traceEventCount=len(value["trace"]))
    if phase == "stop":
        def unique_start(stage):
            matching = [event for event in value["trace"] if event["stage"] == stage]
            return matching[0]["startNanos"] if len(matching) == 1 else -1
        def elapsed(end, start):
            return (end - start) / 1_000_000 if start >= 0 and end >= start else None
        received, invalidated = (unique_start(stage) for stage in ("STOP_RECEIVED", "STOP_EPOCH_INVALIDATED"))
        result["timingsMillis"] = {
            "requestToReceived": elapsed(received, value["stopEnqueuedNanos"]),
            "receivedToInvalidated": elapsed(invalidated, received),
            "requestToUiApplied": elapsed(value["stopStateAppliedNanos"], value["stopEnqueuedNanos"]),
        }
    return result


def summarize_segment(value, reference, label):
    samples = value["samples"]
    span = samples[-1]["elapsedRealtimeNanos"] - samples[0]["elapsedRealtimeNanos"] if samples else 0
    cpu = ((samples[-1]["processCpuMillis"] - samples[0]["processCpuMillis"]) * 100_000_000 / span
           if span > 0 else None)
    recovery = [sample for sample in samples if sample["phase"] == "recovery"]
    result = {key: value[key] for key in ("index", "status", "startedNanos", "endedNanos", "sampleLimitReached")}
    result.update(eligible=value["status"] == "passed",
                  reason=None if value["status"] == "passed" else "segment_failed",
                  rawRelativePath=f"segmented-stress/api-{label}/{reference['path']}",
                  rawSha256=reference["sha256"], rawBytes=reference["bytes"],
                  wallMillis=(value["endedNanos"] - value["startedNanos"]) / 1_000_000,
                  resourceSampleCount=len(samples), resourceSampleSpanMillis=span / 1_000_000,
                  cpuPercentOneCore=cpu, cpuUnavailableReason=None if span > 0 else "insufficient_sample_span",
                  resourceSamples=samples,
                  sampleCollectionMaxMillis=max((sample["collectionNanos"] for sample in samples), default=0) / 1_000_000 if samples else None,
                  recoveryTaggedSampleCount=len(recovery),
                  recoveryTaggedSampleMeanTotalPssKiB=statistics.mean(sample["totalPssKiB"] for sample in recovery) if recovery else None,
                  recoveryTaggedSampleMaxTotalPssKiB=max((sample["totalPssKiB"] for sample in recovery), default=None),
                  tasks={phase: summarize_task(phase, value[phase]) for phase in TASK_PHASES},
                  recovery=None if value["recovery"] is None else {key: item for key, item in value["recovery"].items() if key != "trace"})
    if result["recovery"] is not None:
        result["recovery"]["traceEventCount"] = len(value["recovery"]["trace"])
    for prefix, key in (("Total", "totalPssKiB"), ("Native", "nativePssKiB")):
        result["sampleMean" + prefix + "PssKiB"] = statistics.mean(sample[key] for sample in samples) if samples else None
        result["sampleMax" + prefix + "PssKiB"] = max((sample[key] for sample in samples), default=None)
    return result


def load_archive(root, expected_source):
    root = checked_path(root)
    manifest, manifest_sha = checked_json(root / "manifest.json", MAX_MANIFEST_BYTES)
    if (type(manifest) is not dict or type(manifest.get("schemaVersion")) is not int
            or manifest["schemaVersion"] != 1 or manifest.get("kind") != "android-emulator-test-evidence"
            or manifest.get("executionScope") != "stress-diagnostic"
            or manifest.get("fullSuiteAcceptance") is not False
            or manifest.get("expectedMatrixLabels") != list(evidence.LABELS)):
        raise ValueError("not_an_eight_api_stress_archive")
    source = manifest.get("sourceRun")
    if type(source) is not dict:
        raise ValueError("invalid_archive_source")
    for key in ("runId", "attempt"):
        evidence.integer(source.get(key), 1)
    if (source.get("repository") != REPOSITORY or source.get("commit") != expected_source
            or source.get("branch") != "master"
            or source.get("url") != f"https://github.com/{REPOSITORY}/actions/runs/{source['runId']}"):
        raise ValueError("archive_source_mismatch")
    if source.get("conclusion") not in ("success", "failure", "cancelled", "timed_out", "neutral", "action_required"):
        raise ValueError("invalid_original_run_conclusion")
    identity = {key: source[key] for key in ("repository", "commit", "runId", "attempt")}
    section = manifest.get("segmentedStress")
    if (type(section) is not dict or section.get("kind") != "segmented-stress-attachments"
            or section.get("diagnosticOnly") is not True or section.get("budgetAcceptance") is not False):
        raise ValueError("invalid_stress_attachment_scope")
    records = section.get("apis")
    if (type(records) is not list or len(records) != len(evidence.LABELS)
            or any(type(record) is not dict for record in records)
            or [record.get("matrixLabel") for record in records] != list(evidence.LABELS)):
        raise ValueError("missing_duplicate_or_reordered_api_record")
    stress_root = checked_path(root / "segmented-stress")
    if stress_root.exists():
        if not stress_root.is_dir():
            raise ValueError("invalid_stress_root")
        for path in stress_root.iterdir():
            checked_path(path)
            if path.name not in {"api-" + label for label in evidence.LABELS} or not path.is_dir():
                raise ValueError("unexpected_stress_api_directory")
    archive = {"sourceRun": source, "manifestSha256": manifest_sha,
               "archiveDirectory": root.name, "manifestRelativePath": "manifest.json"}
    lanes = []
    for record in records:
        label = record["matrixLabel"]
        api = int(label.split(".")[0])
        evidence.integer(record.get("apiLevel"), api, api)
        if record.get("jobConclusion") not in ("success", "failure", "cancelled", "timed_out", "neutral",
                                               "action_required", "skipped", "not_scheduled", "startup_failure"):
            raise ValueError("invalid_original_job_conclusion")
        directory = stress_root / ("api-" + label)
        files = verify_files(directory, record.get("files"))
        lane = {"matrixLabel": label, "apiLevel": api, "sourceRunConclusion": source["conclusion"],
                "jobConclusion": record.get("jobConclusion"), "captureStatus": record.get("captureStatus"),
                "diagnosticStatus": record.get("diagnosticStatus"), "gradleExitCode": record.get("gradleExitCode"),
                "artifactId": record.get("artifactId"), "captureError": record.get("captureError"),
                "reason": None, "files": list(files.values()), "segments": planned_segments("report_unavailable")}
        lanes.append(lane)
        if not files:
            if record.get("captureStatus") != "artifact_unavailable" or record.get("diagnosticStatus") is not None:
                raise ValueError("missing_capture_record")
            lane["reason"] = "artifact_unavailable"
            continue
        if "capture.json" not in files:
            raise ValueError("missing_capture_file")
        evidence.integer(record.get("artifactId"), 1)
        capture, unused = checked_json(directory / "capture.json", evidence.MAX_CAPTURE_BYTES)
        evidence.validate_capture(capture, label, identity)
        lane.update(captureRelativePath=f"segmented-stress/api-{label}/capture.json",
                    captureSha256=files["capture.json"]["sha256"], capturedAt=capture["capturedAt"],
                    emulatorStepOutcome=capture.get("emulatorStepOutcome"))
        for field in ("captureStatus", "captureError", "actualSdkInt", "gradleExitCode", "declaredCiProfile"):
            if record.get(field) != capture[field] or type(record.get(field)) is not type(capture[field]):
                raise ValueError("archive_capture_field_mismatch")
        if capture["captureStatus"] != "collected":
            if len(files) != 1 or record.get("diagnosticStatus") is not None:
                raise ValueError("uncollected_report_present")
            lane["reason"] = "capture_" + capture["captureStatus"]
            continue
        if (evidence.REPORT_NAME not in files
                or capture["report"] != dict(files[evidence.REPORT_NAME], status=record.get("diagnosticStatus"))):
            raise ValueError("archive_capture_report_reference_mismatch")
        report, unused = checked_json(directory / evidence.REPORT_NAME, evidence.MAX_REPORT_BYTES)
        evidence.validate_report(report, api)
        if report["status"] != record.get("diagnosticStatus"):
            raise ValueError("archive_report_status_mismatch")
        expected_files = {"capture.json", evidence.REPORT_NAME} | {ref["path"] for ref in report["segments"]}
        if set(files) != expected_files:
            raise ValueError("archive_root_segment_file_set_mismatch")
        validated_segments = evidence.validate_bundle(directory, report, api)
        tail_status = "unavailable" if report["status"] == "incomplete" else "not_executed"
        tail_reason = "not_committed_before_interruption" if report["status"] == "incomplete" else "not_executed_after_failure"
        lane.update(ciProfile=capture["declaredCiProfile"], device=report["device"], conditions=report["conditions"],
                    appProcessPid=report["appProcessPid"], cleanup=report["cleanup"],
                    startedNanos=report["startedNanos"], endedNanos=report["endedNanos"],
                    wallMillis=(report["endedNanos"] - report["startedNanos"]) / 1_000_000 if report["endedNanos"] >= 0 else None,
                    reportRelativePath=f"segmented-stress/api-{label}/{evidence.REPORT_NAME}",
                    reportSha256=files[evidence.REPORT_NAME]["sha256"], segments=planned_segments(tail_reason, tail_status))
        if report["status"] != "passed":
            lane["reason"] = report["status"] + "_diagnostic_preserved"
        for reference, segment in zip(report["segments"], validated_segments):
            reference_file = {key: reference[key] for key in ("path", "bytes", "sha256")}
            if reference_file != files[reference["path"]]:
                raise ValueError("archive_root_segment_reference_mismatch")
            lane["segments"][reference["index"]] = summarize_segment(segment, reference, label)
    return archive, lanes


def distribution(values):
    available = [value for value in values if value is not None]
    if any(not math.isfinite(value) for value in available):
        raise ValueError("nonfinite_derived_measurement")
    return {"count": len(available), "missing": len(values) - len(available),
            "min": min(available) if available else None,
            "median": statistics.median(available) if available else None,
            "max": max(available) if available else None}


def counts(entries):
    return {status: sum(entry["status"] == status for entry in entries)
            for status in ("passed", "failed", "not_executed", "unavailable")}


def lane_group(lane):
    segments = lane["segments"]
    eligible = [segment for segment in segments if segment["eligible"]]
    first, last = segments[0], segments[-1]
    endpoint_metrics = ("recoveryTaggedSampleMeanTotalPssKiB", "recoveryTaggedSampleMaxTotalPssKiB")
    endpoint_ok = all(segment["eligible"] and all(segment.get(metric) is not None for metric in endpoint_metrics)
                      for segment in (first, last))
    differences = {metric: last[metric] - first[metric] if endpoint_ok else None for metric in endpoint_metrics}
    return {"matrixLabel": lane["matrixLabel"], "apiLevel": lane["apiLevel"],
            "expectedSegmentCount": SEGMENT_COUNT, "eligibleSegmentCount": len(eligible),
            "excludedSegmentCount": SEGMENT_COUNT - len(eligible), "segmentCounts": counts(segments),
            "metrics": {metric: distribution([segment.get(metric) if segment["eligible"] else None for segment in segments])
                        for metric in METRICS},
            "recoveryFirstToLast": {"firstSegmentIndex": 0, "lastSegmentIndex": SEGMENT_COUNT - 1,
                                     "available": endpoint_ok,
                                     "reason": None if endpoint_ok else "planned_endpoint_unavailable",
                                     "differencesKiB": differences},
            "taskCounts": {phase: counts([segment["tasks"][phase] for segment in segments]) for phase in TASK_PHASES}}


def summarize(root, expected_source):
    if type(expected_source) is not str or not re.fullmatch(r"[0-9a-f]{40}", expected_source):
        raise ValueError("exact_expected_source_required")
    archive, lanes = load_archive(root, expected_source)
    segments = [segment for lane in lanes for segment in lane["segments"]]
    tasks = [segment["tasks"][phase] for segment in segments for phase in TASK_PHASES]
    passed_reports = sum(lane["diagnosticStatus"] == "passed" for lane in lanes)
    verified_writes = sum(task["acceptedWrites"] for task in tasks if task["phase"] == "active" and task["status"] == "passed")
    observed_writes = [task["acceptedWrites"] for task in tasks if task.get("acceptedWrites") is not None and task["acceptedWrites"] >= 0]
    return {"schemaVersion": 1, "kind": "segmented-stress-summary", "diagnosticOnly": True,
            "budgetAcceptance": False, "sourceCommit": expected_source, "repository": REPOSITORY,
            "tool": {"summaryScript": "scripts/summarize_stress_runs.py",
                     "summaryScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                     "reportValidatorScript": "scripts/collect_stress_evidence.py",
                     "reportValidatorSha256": hashlib.sha256(Path(evidence.__file__).read_bytes()).hexdigest(),
                     "commonValidatorScript": "scripts/collect_performance_evidence.py",
                     "commonValidatorSha256": hashlib.sha256(Path(common_evidence.__file__).read_bytes()).hexdigest()},
            "status": "complete" if passed_reports == len(evidence.LABELS) and all(segment["eligible"] for segment in segments) else "incomplete",
            "expectedLaneCount": len(evidence.LABELS), "passedDiagnosticLaneCount": passed_reports,
            "expectedSegmentCount": len(evidence.LABELS) * SEGMENT_COUNT,
            "recordedSegmentCount": sum(segment["status"] in ("passed", "failed") for segment in segments),
            "segmentCounts": counts(segments), "expectedTaskCount": len(evidence.LABELS) * SEGMENT_COUNT * 2,
            "taskCounts": counts(tasks), "expectedCompletedTaskCount": len(evidence.LABELS) * SEGMENT_COUNT,
            "expectedStoppedTaskCount": len(evidence.LABELS) * SEGMENT_COUNT,
            "expectedCompletedTaskAcceptedWrites": len(evidence.LABELS) * SEGMENT_COUNT * 3,
            "verifiedCompletedTaskAcceptedWrites": verified_writes,
            "observedAcceptedWrites": sum(observed_writes),
            "unknownAcceptedWriteTaskCount": len(tasks) - len(observed_writes),
            "resourceSampleCount": sum(segment.get("resourceSampleCount", 0) for segment in segments),
            "notMeasured": ["active_task_equivalent_baseline", "application_startup", "full_frame_rendering",
                            "instantaneous_memory_peak", "fixture_process_resources", "energy", "thermal",
                            "physical_device_budgets", "system_wide_anr_or_crash_absence", "leak_absence"],
            "limitations": LIMITS, "archive": archive, "lanes": lanes,
            "groups": [lane_group(lane) for lane in lanes]}


def write_output(path, root, text):
    path, root = checked_path(path), checked_path(root)
    if path == root or root in path.parents:
        raise ValueError("output_must_not_modify_input_evidence")
    if path.exists():
        raise ValueError("output_already_exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        stream.write(text)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="One audited eight-API stress-diagnostic archive")
    parser.add_argument("--source", required=True, help="Exact expected tested commit")
    parser.add_argument("--output", type=Path, help="New JSON outside input evidence; default stdout")
    args = parser.parse_args(argv)
    try:
        result = summarize(args.archive, args.source)
        text = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        if args.output:
            write_output(args.output, args.archive, text)
        else:
            sys.stdout.write(text)
        return 0 if result["status"] == "complete" else 1
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"Stress summary rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
