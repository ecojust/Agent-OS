#!/usr/bin/env python3
"""Summarize attributable repeated emulator diagnostics, never performance acceptance."""

import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys

import collect_performance_evidence as evidence

MAX_MANIFEST_BYTES = 8 * 1024 * 1024
MAX_RUNS = 32
REPOSITORY = "ecojust/Agent-OS"
METRICS = ("cpuPercentOneCore", "sampleMeanTotalPssKiB", "sampleMaxTotalPssKiB",
           "sampleMeanNativePssKiB", "sampleMaxNativePssKiB", "wallMillis",
           "resourceSampleSpanMillis", "sampleCollectionMaxMillis")
LIMITS = [
    "Local archive identity and hash chains are checked; this command does not authenticate GitHub or re-audit UI results.",
    "A/B/C/STOP have fixed order, unequal durations and different workloads; differences are descriptive, not causal or alternating paired trials.",
    "A is a resident instrumented process with its service off, not a process-free or equivalent manual-task baseline.",
    "CPU uses one logical core = 100%; the instrumented app includes its sampler and C/STOP local model, excluding fixture processes.",
    "PSS means are arithmetic means of recorded samples; sampled maxima do not establish instantaneous peaks.",
    "STOP timing excludes physical touch and full display rendering. No startup, frame, energy, thermal or physical-device budgets are evaluated.",
    "Cross-run distributions are min/median/max only, with one value per run; no P95/P99 or confidence claim is produced.",
    "Grouping matches recorded device/profile/conditions, not unrecorded host load, cache state or thermal conditions.",
]


def checked_path(path):
    """Do not resolve through a symlink, including an existing parent directory."""
    path = Path(path).absolute()
    if ".." in path.parts:
        raise ValueError("path_traversal_is_not_supported")
    if any(part.is_symlink() for part in (path, *path.parents)):
        raise ValueError("symlink_in_evidence_or_output_path")
    return path


def checked_json(path, maximum):
    path = checked_path(path)
    data = path.read_bytes() if path.is_file() and path.stat().st_size <= maximum else None
    if data is None:
        raise ValueError("missing_or_oversized_json")
    return evidence.strict_json(data), hashlib.sha256(data).hexdigest()


def verify_files(directory, references):
    if type(references) is not list or len(references) > 2:
        raise ValueError("invalid_performance_file_list")
    expected = {}
    for ref in references:
        evidence.fields(ref, ("path", "bytes", "sha256"))
        name = ref["path"]
        if name not in ("capture.json", evidence.REPORT_NAME) or name in expected:
            raise ValueError("unexpected_or_duplicate_performance_path")
        maximum = evidence.MAX_CAPTURE_BYTES if name == "capture.json" else evidence.MAX_REPORT_BYTES
        evidence.integer(ref["bytes"], 1, maximum)
        if type(ref["sha256"]) is not str or not re.fullmatch(r"[0-9a-f]{64}", ref["sha256"]):
            raise ValueError("invalid_performance_digest")
        path = checked_path(directory / name)
        if not path.is_file() or path.stat().st_size != ref["bytes"]:
            raise ValueError("performance_file_size_mismatch")
        if hashlib.sha256(path.read_bytes()).hexdigest() != ref["sha256"]:
            raise ValueError("performance_file_hash_mismatch")
        expected[name] = ref
    directory = checked_path(directory)
    actual = set()
    if directory.exists():
        if not directory.is_dir():
            raise ValueError("invalid_performance_directory")
        for entry in directory.iterdir():
            checked_path(entry)
            if not entry.is_file():
                raise ValueError("unexpected_performance_directory_entry")
            actual.add(entry.name)
    if actual != set(expected):
        raise ValueError("performance_file_set_mismatch")
    return expected


def load_archive(root, expected_source):
    root = checked_path(root)
    manifest, manifest_sha = checked_json(root / "manifest.json", MAX_MANIFEST_BYTES)
    if (type(manifest) is not dict
            or manifest.get("schemaVersion") != 1 or type(manifest.get("schemaVersion")) is not int
            or manifest.get("kind") != "android-emulator-test-evidence"
            or manifest.get("executionScope") != "full"
            or manifest.get("expectedMatrixLabels") != list(evidence.LABELS)):
        raise ValueError("not_a_full_eight_api_archive")
    source = manifest.get("sourceRun", {})
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
    section = manifest.get("performanceDiagnostics", {})
    if (type(section) is not dict or section.get("kind") != "performance-diagnostic-attachments"
            or section.get("diagnosticOnly") is not True or section.get("budgetAcceptance") is not False):
        raise ValueError("invalid_performance_attachment_scope")
    records = section.get("apis")
    if (type(records) is not list or len(records) != len(evidence.LABELS)
            or any(type(record) is not dict for record in records)
            or [record.get("matrixLabel") for record in records] != list(evidence.LABELS)):
        raise ValueError("missing_duplicate_or_reordered_api_record")
    perf_root = checked_path(root / "performance")
    if perf_root.exists():
        if not perf_root.is_dir():
            raise ValueError("invalid_performance_root")
        for path in perf_root.iterdir():
            checked_path(path)
            if path.name not in {"api-" + label for label in evidence.LABELS} or not path.is_dir():
                raise ValueError("unexpected_performance_api_directory")
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
        lane = {"runId": identity["runId"], "attempt": identity["attempt"],
                "matrixLabel": label, "apiLevel": api, "fullRunConclusion": source["conclusion"],
                "jobConclusion": record.get("jobConclusion"), "captureStatus": record.get("captureStatus"),
                "diagnosticStatus": record.get("diagnosticStatus"), "eligible": False,
                "gradleExitCode": record.get("gradleExitCode"), "artifactId": record.get("artifactId"),
                "captureError": record.get("captureError"),
                "reason": None, "files": list(files.values())}
        lanes.append(lane)
        if not files:
            if (record.get("captureStatus") != "artifact_unavailable"
                    or record.get("diagnosticStatus") is not None):
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
        if evidence.REPORT_NAME not in files or capture["report"] != dict(files[evidence.REPORT_NAME], status=record.get("diagnosticStatus")):
            raise ValueError("archive_capture_report_reference_mismatch")
        report, unused = checked_json(perf_root / ("api-" + label) / evidence.REPORT_NAME, evidence.MAX_REPORT_BYTES)
        evidence.validate_report(report, api)
        if report["status"] != record.get("diagnosticStatus"):
            raise ValueError("archive_report_status_mismatch")
        lane.update(ciProfile=capture["declaredCiProfile"], device=report["device"], conditions=report["conditions"],
                    reportRelativePath=f"performance/api-{label}/{evidence.REPORT_NAME}",
                    reportSha256=files[evidence.REPORT_NAME]["sha256"])
        if report["status"] != "passed":
            lane["reason"] = "failed_diagnostic_preserved_not_aggregated"
            continue
        lane.update(eligible=True, modes={mode["mode"]: summarize_mode(mode) for mode in report["modes"]})
        lane["differences"] = {}
        for target, name in ((evidence.MODES[1], "B_MINUS_A"), (evidence.MODES[2], "C_MINUS_A")):
            baseline = lane["modes"][evidence.MODES[0]]
            current = lane["modes"][target]
            lane["differences"][name] = {
                "cpuPercentagePoints": subtract(current["cpuPercentOneCore"], baseline["cpuPercentOneCore"]),
                "sampleMeanTotalPssKiB": current["sampleMeanTotalPssKiB"] - baseline["sampleMeanTotalPssKiB"],
                "sampleMaxTotalPssKiB": current["sampleMaxTotalPssKiB"] - baseline["sampleMaxTotalPssKiB"],
            }
    return archive, lanes


def subtract(value, baseline):
    return None if value is None or baseline is None else value - baseline


def summarize_mode(mode):
    samples = mode["samples"]
    first, last = samples[0], samples[-1]
    span = last["elapsedRealtimeNanos"] - first["elapsedRealtimeNanos"]
    cpu = (last["processCpuMillis"] - first["processCpuMillis"]) * 100_000_000 / span if span > 0 else None
    result = {"wallMillis": (mode["endedNanos"] - mode["startedNanos"]) / 1_000_000,
              "resourceSampleCount": len(samples), "resourceSampleSpanMillis": span / 1_000_000,
              "cpuPercentOneCore": cpu, "cpuUnavailableReason": None if span > 0 else "insufficient_sample_span",
              "sampleMeanTotalPssKiB": statistics.mean(sample["totalPssKiB"] for sample in samples),
              "sampleMaxTotalPssKiB": max(sample["totalPssKiB"] for sample in samples),
              "sampleMeanNativePssKiB": statistics.mean(sample["nativePssKiB"] for sample in samples),
              "sampleMaxNativePssKiB": max(sample["nativePssKiB"] for sample in samples),
              "sampleCollectionMaxMillis": max(sample["collectionNanos"] for sample in samples) / 1_000_000,
              "resourceSamples": samples}
    if mode["mode"] == evidence.MODES[3]:
        result["stopMillis"] = {key.removesuffix("Nanos"): mode[key] / 1_000_000 for key in evidence.STOP_DURATIONS}
    return result


def distribution(values):
    available = [value for value in values if value is not None]
    if any(not math.isfinite(value) for value in available):
        raise ValueError("nonfinite_derived_measurement")
    return {"count": len(available), "missing": len(values) - len(available),
            "min": min(available) if available else None,
            "median": statistics.median(available) if available else None,
            "max": max(available) if available else None}


def summarize(roots, expected_source):
    if not re.fullmatch(r"[0-9a-f]{40}", expected_source) or not 2 <= len(roots) <= MAX_RUNS:
        raise ValueError("expected_source_and_two_to_32_archives_required")
    archives, lanes, seen = [], [], set()
    for root in roots:
        archive, samples = load_archive(root, expected_source)
        run_id = archive["sourceRun"]["runId"]
        # Attempts of the same run are not independent repetitions.
        if run_id in seen:
            raise ValueError("duplicate_source_run_even_if_attempt_differs")
        seen.add(run_id)
        archives.append(archive)
        lanes.extend(samples)
    lanes.sort(key=lambda lane: (lane["apiLevel"], lane["runId"]))
    groups = defaultdict(list)
    for lane in lanes:
        if lane["eligible"]:
            identity = {key: lane[key] for key in ("matrixLabel", "apiLevel", "ciProfile", "device", "conditions")}
            key = json.dumps(identity, sort_keys=True, separators=(",", ":"))
            lane["groupId"] = hashlib.sha256(key.encode()).hexdigest()
            groups[key].append(lane)
    summaries = []
    for key in sorted(groups):
        members = groups[key]
        group = {"groupId": members[0]["groupId"], **json.loads(key), "runIds": [lane["runId"] for lane in members],
                 "runCount": len(members), "repeatedGroup": len(members) >= 2,
                 "modes": {}, "differences": {}}
        for mode in evidence.MODES:
            group["modes"][mode] = {metric: distribution([lane["modes"][mode][metric] for lane in members]) for metric in METRICS}
        group["stopMillis"] = {key: distribution([lane["modes"][evidence.MODES[3]]["stopMillis"][key] for lane in members])
                               for key in members[0]["modes"][evidence.MODES[3]]["stopMillis"]}
        for diff in ("B_MINUS_A", "C_MINUS_A"):
            group["differences"][diff] = {metric: distribution([lane["differences"][diff][metric] for lane in members])
                                          for metric in members[0]["differences"][diff]}
        summaries.append(group)
    eligible = sum(lane["eligible"] for lane in lanes)
    return {"schemaVersion": 1, "kind": "repeated-performance-diagnostics", "diagnosticOnly": True,
            "budgetAcceptance": False, "sourceCommit": expected_source, "repository": REPOSITORY,
            "tool": {"summaryScript": "scripts/summarize_performance_runs.py",
                     "summaryScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                     "reportValidatorScript": "scripts/collect_performance_evidence.py",
                     "reportValidatorSha256": hashlib.sha256(Path(evidence.__file__).read_bytes()).hexdigest()},
            "status": "complete" if eligible == len(lanes) else "incomplete",
            "inputRunCount": len(archives), "expectedLaneCount": len(archives) * len(evidence.LABELS),
            "eligibleLaneCount": eligible, "excludedLaneCount": len(lanes) - eligible,
            "notMeasured": ["application_startup", "full_frame_rendering", "instantaneous_memory_peak",
                            "fixture_process_resources", "energy", "thermal", "physical_touch_latency",
                            "physical_device_budgets"],
            "limitations": LIMITS, "archives": sorted(archives, key=lambda item: item["sourceRun"]["runId"]),
            "lanes": lanes, "groups": summaries}


def write_output(path, roots, text):
    path = checked_path(path)
    for root in roots:
        root = checked_path(root)
        if path == root or root in path.parents:
            raise ValueError("output_must_not_modify_input_evidence")
    if path.exists():
        raise ValueError("output_already_exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive create also refuses an output symlink installed after the check.
    with path.open("x", encoding="utf-8") as stream:
        stream.write(text)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archives", type=Path, nargs="+", help="Full archived run directories (2–32 distinct runs)")
    parser.add_argument("--source", required=True, help="Exact expected tested commit; different commits are rejected")
    parser.add_argument("--output", type=Path, help="New JSON outside all input archives; default stdout")
    args = parser.parse_args(argv)
    try:
        result = summarize(args.archives, args.source)
        text = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        if args.output:
            write_output(args.output, args.archives, text)
        else:
            sys.stdout.write(text)
        return 0 if result["status"] == "complete" else 1
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"Performance summary rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
