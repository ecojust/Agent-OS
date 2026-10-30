#!/usr/bin/env python3
"""Summarize fixed same-device idle pairs without claiming performance acceptance."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys

import collect_idle_pair_evidence as evidence
import collect_performance_evidence as common_evidence

REPOSITORY = "ecojust/Agent-OS"
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
PAIR_COUNT = 6
MODES = ("A_SERVICE_OFF_RESIDENT_PROCESS", "B_SERVICE_ON_IDLE")
METRICS = ("cpuPercentOneCore", "sampleMeanTotalPssKiB", "sampleMaxTotalPssKiB",
           "sampleMeanNativePssKiB", "sampleMaxNativePssKiB", "wallMillis",
           "resourceSampleSpanMillis", "sampleCollectionMaxMillis")
DIFFERENCES = ("cpuPercentagePoints", "sampleMeanTotalPssKiB")
LIMITS = [
    "This checks local archived identities and hash chains, not GitHub authenticity, UI acceptance or physical-device budgets.",
    "Six ordered AB/BA pairs share one resident instrumented process and one fixture launch; pairs are not independent devices or process restarts.",
    "A disables the service but leaves the instrumented process resident; BA may retain allocations from the preceding enabled service.",
    "CPU includes instrumentation and the sampler, using one logical core = 100%; fixture and other processes are excluded.",
    "State and focused-fixture checks occur before and after each window, not continuously throughout it.",
    "The fixed settling interval and alternation do not control unrecorded cache, host load, temperature or service teardown effects.",
    "PSS means are arithmetic sample means; observed maxima do not establish instantaneous peaks or memory-leak absence.",
    "Differences are computed within each pair before min/median/max; all planned pairs remain in the denominator, including failure and absence.",
    "Valid completed pairs in a failed report may be described, but the original diagnostic, run and job failure conclusions remain unchanged.",
    "No P95/P99, uncertainty estimate, causal effect or CPU/PSS budget acceptance is produced.",
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
    if type(references) is not list or len(references) > 2:
        raise ValueError("invalid_idle_pair_file_list")
    expected = {}
    for ref in references:
        evidence.fields(ref, ("path", "bytes", "sha256"))
        name = ref["path"]
        if name not in ("capture.json", evidence.REPORT_NAME) or name in expected:
            raise ValueError("unexpected_or_duplicate_idle_pair_path")
        maximum = evidence.MAX_CAPTURE_BYTES if name == "capture.json" else evidence.MAX_REPORT_BYTES
        evidence.integer(ref["bytes"], 1, maximum)
        if type(ref["sha256"]) is not str or not re.fullmatch(r"[0-9a-f]{64}", ref["sha256"]):
            raise ValueError("invalid_idle_pair_digest")
        path = checked_path(directory / name)
        if not path.is_file() or path.stat().st_size != ref["bytes"]:
            raise ValueError("idle_pair_file_size_mismatch")
        if hashlib.sha256(path.read_bytes()).hexdigest() != ref["sha256"]:
            raise ValueError("idle_pair_file_hash_mismatch")
        expected[name] = ref
    directory = checked_path(directory)
    actual = set()
    if directory.exists():
        if not directory.is_dir():
            raise ValueError("invalid_idle_pair_directory")
        for entry in directory.iterdir():
            checked_path(entry)
            if not entry.is_file():
                raise ValueError("unexpected_idle_pair_directory_entry")
            actual.add(entry.name)
    if actual != set(expected):
        raise ValueError("idle_pair_file_set_mismatch")
    return expected


def summarize_window(window):
    samples = window["samples"]
    span = samples[-1]["elapsedRealtimeNanos"] - samples[0]["elapsedRealtimeNanos"] if samples else 0
    cpu = ((samples[-1]["processCpuMillis"] - samples[0]["processCpuMillis"]) * 100_000_000 / span
           if span > 0 else None)
    result = {key: window[key] for key in ("pairIndex", "position", "mode", "status", "startedNanos", "endedNanos",
                                          "stateBefore", "stateAfter", "sampleLimitReached", "traceOverwrittenEvents")}
    result.update(wallMillis=(window["endedNanos"] - window["startedNanos"]) / 1_000_000,
                  resourceSampleCount=len(samples), resourceSampleSpanMillis=span / 1_000_000,
                  cpuPercentOneCore=cpu, cpuUnavailableReason=None if span > 0 else "insufficient_sample_span",
                  resourceSamples=samples, executionTrace=window["trace"],
                  sampleCollectionMaxMillis=max((sample["collectionNanos"] for sample in samples), default=0) / 1_000_000
                  if samples else None)
    for prefix, key in (("Total", "totalPssKiB"), ("Native", "nativePssKiB")):
        result["sampleMean" + prefix + "PssKiB"] = statistics.mean(sample[key] for sample in samples) if samples else None
        result["sampleMax" + prefix + "PssKiB"] = max((sample[key] for sample in samples), default=None)
    return result


def planned_windows(reason, status="not_executed"):
    return [{"pairIndex": index // 2, "position": index % 2,
             "mode": MODES[(index % 2) ^ ((index // 2) % 2)],
             "status": status, "reason": reason}
            for index in range(PAIR_COUNT * 2)]


def add_pairs(lane):
    pairs = []
    for pair_index in range(PAIR_COUNT):
        windows = lane["windows"][pair_index * 2:pair_index * 2 + 2]
        pair = {"pairIndex": pair_index, "order": "AB" if pair_index % 2 == 0 else "BA",
                "windowIndices": [pair_index * 2, pair_index * 2 + 1], "eligible": False,
                "status": "incomplete", "reason": None, "differences": None}
        if any(window["status"] == "failed" for window in windows):
            pair.update(status="failed", reason="window_failed")
        elif any(window["status"] != "passed" for window in windows):
            pair["reason"] = "window_not_executed_or_unavailable"
        else:
            by_mode = {window["mode"]: window for window in windows}
            a, b = (by_mode[mode] for mode in MODES)
            pair.update(eligible=True, status="passed", differences={
                "cpuPercentagePoints": b["cpuPercentOneCore"] - a["cpuPercentOneCore"],
                "sampleMeanTotalPssKiB": b["sampleMeanTotalPssKiB"] - a["sampleMeanTotalPssKiB"],
            })
        pairs.append(pair)
    lane["pairs"] = pairs
    lane["eligiblePairCount"] = sum(pair["eligible"] for pair in pairs)
    lane["excludedPairCount"] = PAIR_COUNT - lane["eligiblePairCount"]


def load_archive(root, expected_source):
    root = checked_path(root)
    manifest, manifest_sha = checked_json(root / "manifest.json", MAX_MANIFEST_BYTES)
    if (type(manifest) is not dict or type(manifest.get("schemaVersion")) is not int
            or manifest["schemaVersion"] != 1 or manifest.get("kind") != "android-emulator-test-evidence"
            or manifest.get("executionScope") != "idle-pair-diagnostic"
            or manifest.get("expectedMatrixLabels") != list(evidence.LABELS)):
        raise ValueError("not_an_eight_api_idle_pair_archive")
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
    section = manifest.get("idlePairedPerformance")
    if (type(section) is not dict or section.get("kind") != "idle-paired-performance-attachments"
            or section.get("diagnosticOnly") is not True or section.get("budgetAcceptance") is not False):
        raise ValueError("invalid_idle_pair_attachment_scope")
    records = section.get("apis")
    if (type(records) is not list or len(records) != len(evidence.LABELS)
            or any(type(record) is not dict for record in records)
            or [record.get("matrixLabel") for record in records] != list(evidence.LABELS)):
        raise ValueError("missing_duplicate_or_reordered_api_record")
    perf_root = checked_path(root / "idle-paired-performance")
    if perf_root.exists():
        if not perf_root.is_dir():
            raise ValueError("invalid_idle_pair_root")
        for path in perf_root.iterdir():
            checked_path(path)
            if path.name not in {"api-" + label for label in evidence.LABELS} or not path.is_dir():
                raise ValueError("unexpected_idle_pair_api_directory")
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
        files = verify_files(perf_root / ("api-" + label), record.get("files"))
        lane = {"matrixLabel": label, "apiLevel": api, "sourceRunConclusion": source["conclusion"],
                "jobConclusion": record.get("jobConclusion"), "captureStatus": record.get("captureStatus"),
                "diagnosticStatus": record.get("diagnosticStatus"), "gradleExitCode": record.get("gradleExitCode"),
                "artifactId": record.get("artifactId"), "captureError": record.get("captureError"),
                "reason": None, "files": list(files.values()), "windows": planned_windows("report_unavailable", "unavailable")}
        lanes.append(lane)
        if not files:
            if (record.get("captureStatus") != "artifact_unavailable" or record.get("diagnosticStatus") is not None):
                raise ValueError("missing_capture_record")
            lane["reason"] = "artifact_unavailable"
            continue
        if "capture.json" not in files:
            raise ValueError("missing_capture_file")
        evidence.integer(record.get("artifactId"), 1)
        capture, unused = checked_json(perf_root / ("api-" + label) / "capture.json", evidence.MAX_CAPTURE_BYTES)
        evidence.validate_capture(capture, label, identity)
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
        report, unused = checked_json(perf_root / ("api-" + label) / evidence.REPORT_NAME, evidence.MAX_REPORT_BYTES)
        evidence.validate_report(report, api)
        if report["status"] != record.get("diagnosticStatus"):
            raise ValueError("archive_report_status_mismatch")
        lane.update(ciProfile=capture["declaredCiProfile"], device=report["device"], conditions=report["conditions"],
                    appProcessPid=report["appProcessPid"],
                    reportRelativePath=f"idle-paired-performance/api-{label}/{evidence.REPORT_NAME}",
                    reportSha256=files[evidence.REPORT_NAME]["sha256"], windows=planned_windows("not_executed_after_failure"))
        if report["status"] != "passed":
            lane["reason"] = "failed_diagnostic_preserved"
        for index, window in enumerate(report["windows"]):
            lane["windows"][index] = summarize_window(window)
    for lane in lanes:
        add_pairs(lane)
    return archive, lanes


def distribution(values):
    available = [value for value in values if value is not None]
    if any(not math.isfinite(value) for value in available):
        raise ValueError("nonfinite_derived_measurement")
    return {"count": len(available), "missing": len(values) - len(available),
            "min": min(available) if available else None,
            "median": statistics.median(available) if available else None,
            "max": max(available) if available else None}


def pair_distribution(lane, order=None):
    pairs = [pair for pair in lane["pairs"] if order is None or pair["order"] == order]
    eligible = [pair for pair in pairs if pair["eligible"]]
    modes = {}
    for mode in MODES:
        modes[mode] = {}
        for metric in METRICS:
            values = []
            for pair in pairs:
                windows = [lane["windows"][index] for index in pair["windowIndices"]]
                value = next(window for window in windows if window["mode"] == mode).get(metric) if pair["eligible"] else None
                values.append(value)
            modes[mode][metric] = distribution(values)
    return {"expectedPairCount": len(pairs), "eligiblePairCount": len(eligible), "excludedPairCount": len(pairs) - len(eligible),
            "modes": modes,
            "differences": {metric: distribution([pair["differences"][metric] if pair["eligible"] else None for pair in pairs])
                            for metric in DIFFERENCES}}


def summarize(root, expected_source):
    if type(expected_source) is not str or not re.fullmatch(r"[0-9a-f]{40}", expected_source):
        raise ValueError("exact_expected_source_required")
    archive, lanes = load_archive(root, expected_source)
    groups = []
    for lane in lanes:
        group = {"matrixLabel": lane["matrixLabel"], "apiLevel": lane["apiLevel"],
                 "allPairs": pair_distribution(lane),
                 "byOrder": {order: pair_distribution(lane, order) for order in ("AB", "BA")}}
        groups.append(group)
    pair_count = sum(lane["eligiblePairCount"] for lane in lanes)
    passed_reports = sum(lane["diagnosticStatus"] == "passed" for lane in lanes)
    return {"schemaVersion": 1, "kind": "idle-paired-performance-summary", "diagnosticOnly": True,
            "budgetAcceptance": False, "sourceCommit": expected_source, "repository": REPOSITORY,
            "tool": {"summaryScript": "scripts/summarize_idle_pairs.py",
                     "summaryScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                     "reportValidatorScript": "scripts/collect_idle_pair_evidence.py",
                     "reportValidatorSha256": hashlib.sha256(Path(evidence.__file__).read_bytes()).hexdigest(),
                     "commonValidatorScript": "scripts/collect_performance_evidence.py",
                     "commonValidatorSha256": hashlib.sha256(Path(common_evidence.__file__).read_bytes()).hexdigest()},
            "status": "complete" if pair_count == len(evidence.LABELS) * PAIR_COUNT and passed_reports == len(evidence.LABELS) else "incomplete",
            "expectedLaneCount": len(evidence.LABELS), "passedDiagnosticLaneCount": passed_reports,
            "expectedWindowCount": len(evidence.LABELS) * PAIR_COUNT * 2,
            "recordedWindowCount": sum(window["status"] in ("passed", "failed") for lane in lanes for window in lane["windows"]),
            "passedWindowCount": sum(window["status"] == "passed" for lane in lanes for window in lane["windows"]),
            "failedWindowCount": sum(window["status"] == "failed" for lane in lanes for window in lane["windows"]),
            "notExecutedWindowCount": sum(window["status"] == "not_executed" for lane in lanes for window in lane["windows"]),
            "unavailableWindowCount": sum(window["status"] == "unavailable" for lane in lanes for window in lane["windows"]),
            "expectedPairCount": len(evidence.LABELS) * PAIR_COUNT, "eligiblePairCount": pair_count,
            "excludedPairCount": len(evidence.LABELS) * PAIR_COUNT - pair_count,
            "notMeasured": ["active_task_overhead", "application_startup", "full_frame_rendering", "instantaneous_memory_peak",
                            "fixture_process_resources", "energy", "thermal", "physical_device_budgets", "long_duration_stress"],
            "limitations": LIMITS, "archive": archive, "lanes": lanes, "groups": groups}


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
    parser.add_argument("archive", type=Path, help="One audited eight-API idle-pair-diagnostic archive")
    parser.add_argument("--source", required=True, help="Exact expected tested commit")
    parser.add_argument("--output", type=Path, help="New JSON outside the input archive; default stdout")
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
        print(f"Idle pair summary rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
