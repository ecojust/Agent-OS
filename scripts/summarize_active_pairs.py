#!/usr/bin/env python3
"""Summarize paired equivalent task costs and endpoint memory, not release budgets."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys

import collect_active_pair_evidence as evidence
import collect_performance_evidence as common_evidence

REPOSITORY = "ecojust/Agent-OS"
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
PAIR_COUNT = 6
REPETITIONS = 5
TRIAL_COUNT = PAIR_COUNT * 2 * REPETITIONS
MODES = ("A_SERVICE_OFF_SCRIPTED_UI", "C_SERVICE_ON_AGENT")
METRICS = ("taskCpuMillis", "taskWallMillis", "beforeTotalPssKiB", "afterTotalPssKiB",
           "withinTrialTotalPssChangeKiB")
LIMITS = [
    "This validates local archived identities and hash chains, not GitHub authenticity, UI evidence or physical-device budgets.",
    "A is a script using accessibility actions on real Fixture controls, not measured human touch, typing, IME use or human latency.",
    "Six alternating AC/CA pairs share one resident instrumented process per API; five fresh tasks form each arm, and neither tasks nor pairs are independent devices.",
    "Task CPU milliseconds and elapsed time cover the recorded task markers, including instrumentation and result verification; service preparation, Fixture reset, fixed settling, endpoint PSS reads and cleanup are outside those markers.",
    "Process CPU milliseconds are quantized; a measured zero is retained, not treated as missing. Different task durations are not converted into a CPU-percentage difference or net overhead claim.",
    "Both arms prepare a local model server in the measured process; it is idle in A and serves C, so C includes local model work. Fixture and other process resources are excluded.",
    "Two endpoint PSS reads do not establish active steady-state memory, instantaneous peaks or leak absence; endpoint read durations and raw values are retained.",
    "A after C may retain service/model allocations; alternation and fixed settling do not control cache, teardown, host load or temperature and do not identify a causal product cost.",
    "Only arms with all five tasks and pairs with both complete arms enter paired distributions. Failures and unavailable or unexecuted trials remain in every planned denominator.",
    "A platform-action acceptance and a C model-acknowledged write are separate counters; both require independent final UI assertions for a passed trial.",
    "An incomplete root proves only its committed prefix. Uncommitted trials are unavailable, not asserted never to have executed; a caught failed report retains a known unexecuted suffix.",
    "Complete pairs in a failed or incomplete report can be described without changing any report, workflow, job or Gradle failure conclusion. Summary complete means diagnostic data completeness, not workflow acceptance.",
    "No P95/P99, real-model acceptance, full-frame timing, target-process experience, causal effect or performance-budget acceptance is produced.",
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
    if type(references) is not list or len(references) > TRIAL_COUNT + 2:
        raise ValueError("invalid_active_pair_file_list")
    expected = {}
    for ref in references:
        common_evidence.fields(ref, ("path", "bytes", "sha256"))
        name = ref["path"]
        if name == "capture.json":
            maximum = evidence.MAX_CAPTURE_BYTES
        elif name == evidence.REPORT_NAME:
            maximum = evidence.MAX_REPORT_BYTES
        elif type(name) is str and re.fullmatch(r"trial-0[0-5][0-9]\.json", name):
            maximum = evidence.MAX_TRIAL_BYTES
        else:
            raise ValueError("unexpected_active_pair_path")
        if name in expected:
            raise ValueError("duplicate_active_pair_path")
        common_evidence.integer(ref["bytes"], 1, maximum)
        if type(ref["sha256"]) is not str or not re.fullmatch(r"[0-9a-f]{64}", ref["sha256"]):
            raise ValueError("invalid_active_pair_digest")
        path = checked_path(directory / name)
        if not path.is_file() or path.stat().st_size != ref["bytes"]:
            raise ValueError("active_pair_file_size_mismatch")
        if hashlib.sha256(path.read_bytes()).hexdigest() != ref["sha256"]:
            raise ValueError("active_pair_file_hash_mismatch")
        expected[name] = ref
    if sum(ref["bytes"] for ref in expected.values()) > evidence.MAX_ARTIFACT_BYTES:
        raise ValueError("active_pair_files_exceed_total_budget")
    directory = checked_path(directory)
    actual = set()
    if directory.exists():
        if not directory.is_dir():
            raise ValueError("invalid_active_pair_directory")
        for entry in directory.iterdir():
            checked_path(entry)
            if not entry.is_file():
                raise ValueError("unexpected_active_pair_directory_entry")
            actual.add(entry.name)
    if actual != set(expected):
        raise ValueError("active_pair_file_set_mismatch")
    return expected


def trial_identity(index):
    pair, within = divmod(index, REPETITIONS * 2)
    position, repetition = divmod(within, REPETITIONS)
    return {"index": index, "pairIndex": pair, "position": position, "repetition": repetition,
            "mode": MODES[position if pair % 2 == 0 else 1 - position]}


def planned_trials(reason, status="unavailable"):
    return [dict(trial_identity(index), status=status, reason=reason, eligible=False,
                 manualActionsAccepted=None, modelAcceptedWrites=None)
            for index in range(TRIAL_COUNT)]


def summarize_trial(value, reference, label):
    result = {key: item for key, item in value.items() if key != "trace"}
    start, end = value["taskStartedNanos"], value["taskEndedNanos"]
    cpu_start, cpu_end = value["taskStartedCpuMillis"], value["taskEndedCpuMillis"]
    wall = (end - start) / 1_000_000 if start >= 0 and end > start else None
    cpu = cpu_end - cpu_start if cpu_start >= 0 and cpu_end >= cpu_start else None
    before, after = value["beforeSample"], value["afterSample"]
    before_pss = before["totalPssKiB"] if before is not None else None
    after_pss = after["totalPssKiB"] if after is not None else None
    result.update(eligible=value["status"] == "passed",
                  reason=None if value["status"] == "passed" else "trial_failed",
                  rawRelativePath=f"active-task-pairs/api-{label}/{reference['path']}",
                  rawSha256=reference["sha256"], rawBytes=reference["bytes"],
                  trialWallMillis=(value["endedNanos"] - value["startedNanos"]) / 1_000_000,
                  taskWallMillis=wall, taskCpuMillis=cpu,
                  taskMeasurementUnavailableReason=None if wall is not None and cpu is not None else "missing_task_markers",
                  beforeTotalPssKiB=before_pss, afterTotalPssKiB=after_pss,
                  withinTrialTotalPssChangeKiB=after_pss - before_pss
                  if before_pss is not None and after_pss is not None else None,
                  endpointSampleCount=int(before is not None) + int(after is not None),
                  traceEventCount=len(value["trace"]),
                  deviceDispatchAttemptEventCount=sum(event["stage"] == "DEVICE_DISPATCH_ATTEMPT" for event in value["trace"]))
    return result


def load_archive(root, expected_source):
    root = checked_path(root)
    manifest, manifest_sha = checked_json(root / "manifest.json", MAX_MANIFEST_BYTES)
    if (type(manifest) is not dict or type(manifest.get("schemaVersion")) is not int
            or manifest["schemaVersion"] != 1 or manifest.get("kind") != "android-emulator-test-evidence"
            or manifest.get("executionScope") != "active-pair-diagnostic"
            or manifest.get("fullSuiteAcceptance") is not False
            or manifest.get("expectedMatrixLabels") != list(evidence.LABELS)):
        raise ValueError("not_an_eight_api_active_pair_archive")
    source = manifest.get("sourceRun")
    if type(source) is not dict:
        raise ValueError("invalid_archive_source")
    for key in ("runId", "attempt"):
        common_evidence.integer(source.get(key), 1)
    if (source.get("repository") != REPOSITORY or source.get("commit") != expected_source
            or source.get("branch") != "master"
            or source.get("url") != f"https://github.com/{REPOSITORY}/actions/runs/{source['runId']}"):
        raise ValueError("archive_source_mismatch")
    if source.get("conclusion") not in ("success", "failure", "cancelled", "timed_out", "neutral", "action_required"):
        raise ValueError("invalid_original_run_conclusion")
    identity = {key: source[key] for key in ("repository", "commit", "runId", "attempt")}
    section = manifest.get("activeTaskPairs")
    if (type(section) is not dict or section.get("kind") != "active-task-pair-attachments"
            or section.get("diagnosticOnly") is not True or section.get("budgetAcceptance") is not False):
        raise ValueError("invalid_active_pair_attachment_scope")
    records = section.get("apis")
    if (type(records) is not list or len(records) != len(evidence.LABELS)
            or any(type(record) is not dict for record in records)
            or [record.get("matrixLabel") for record in records] != list(evidence.LABELS)):
        raise ValueError("missing_duplicate_or_reordered_api_record")
    active_root = checked_path(root / "active-task-pairs")
    if active_root.exists():
        if not active_root.is_dir():
            raise ValueError("invalid_active_root")
        for path in active_root.iterdir():
            checked_path(path)
            if path.name not in {"api-" + label for label in evidence.LABELS} or not path.is_dir():
                raise ValueError("unexpected_active_pair_api_directory")
    archive = {"sourceRun": source, "manifestSha256": manifest_sha,
               "archiveDirectory": root.name, "manifestRelativePath": "manifest.json"}
    lanes = []
    for record in records:
        label = record["matrixLabel"]
        api = int(label.split(".")[0])
        common_evidence.integer(record.get("apiLevel"), api, api)
        if record.get("jobConclusion") not in ("success", "failure", "cancelled", "timed_out", "neutral",
                                               "action_required", "skipped", "not_scheduled", "startup_failure"):
            raise ValueError("invalid_original_job_conclusion")
        directory = active_root / ("api-" + label)
        files = verify_files(directory, record.get("files"))
        lane = {"matrixLabel": label, "apiLevel": api, "sourceRunConclusion": source["conclusion"],
                "jobConclusion": record.get("jobConclusion"), "captureStatus": record.get("captureStatus"),
                "diagnosticStatus": record.get("diagnosticStatus"), "gradleExitCode": record.get("gradleExitCode"),
                "artifactId": record.get("artifactId"), "captureError": record.get("captureError"),
                "reason": None, "files": list(files.values()), "trials": planned_trials("report_unavailable")}
        lanes.append(lane)
        if not files:
            if record.get("captureStatus") != "artifact_unavailable" or record.get("diagnosticStatus") is not None:
                raise ValueError("missing_capture_record")
            lane["reason"] = "artifact_unavailable"
            continue
        if "capture.json" not in files:
            raise ValueError("missing_capture_file")
        common_evidence.integer(record.get("artifactId"), 1)
        capture, unused = checked_json(directory / "capture.json", evidence.MAX_CAPTURE_BYTES)
        evidence.validate_capture(capture, label, identity)
        lane.update(captureRelativePath=f"active-task-pairs/api-{label}/capture.json",
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
        expected_files = {"capture.json", evidence.REPORT_NAME} | {ref["path"] for ref in report["trials"]}
        if set(files) != expected_files:
            raise ValueError("archive_root_trial_file_set_mismatch")
        validated_trials = evidence.validate_bundle(directory, report, api)
        tail_status = "unavailable" if report["status"] == "incomplete" else "not_executed"
        tail_reason = "not_committed_before_interruption" if report["status"] == "incomplete" else "not_executed_after_failure"
        lane.update(ciProfile=capture["declaredCiProfile"], device=report["device"], conditions=report["conditions"],
                    appProcessPid=report["appProcessPid"], cleanup=report["cleanup"],
                    startedNanos=report["startedNanos"], endedNanos=report["endedNanos"],
                    wallMillis=(report["endedNanos"] - report["startedNanos"]) / 1_000_000 if report["endedNanos"] >= 0 else None,
                    reportRelativePath=f"active-task-pairs/api-{label}/{evidence.REPORT_NAME}",
                    reportSha256=files[evidence.REPORT_NAME]["sha256"], trials=planned_trials(tail_reason, tail_status))
        if report["status"] != "passed":
            lane["reason"] = report["status"] + "_diagnostic_preserved"
        for reference, trial in zip(report["trials"], validated_trials):
            reference_file = {key: reference[key] for key in ("path", "bytes", "sha256")}
            if reference_file != files[reference["path"]]:
                raise ValueError("archive_root_trial_reference_mismatch")
            lane["trials"][reference["index"]] = summarize_trial(trial, reference, label)
    return archive, lanes



def distribution(values):
    available = [value for value in values if value is not None]
    if any(not math.isfinite(value) for value in available):
        raise ValueError("nonfinite_derived_measurement")
    return {"count": len(available), "missing": len(values) - len(available),
            "min": min(available) if available else None,
            "median": statistics.median(available) if available else None,
            "max": max(available) if available else None}


def counts(entries, derived=False):
    statuses = ("passed", "failed", "not_executed", "unavailable") + (("incomplete",) if derived else ())
    return {status: sum(entry["status"] == status for entry in entries) for status in statuses}


def aggregate_status(entries):
    statuses = {entry["status"] for entry in entries}
    if statuses == {"passed"}:
        return "passed", None
    if "failed" in statuses:
        return "failed", "trial_failed"
    if "unavailable" in statuses:
        return "unavailable", "trial_unavailable"
    if statuses == {"not_executed"}:
        return "not_executed", "trials_not_executed"
    return "incomplete", "partial_trials_not_eligible"


def add_pairs(lane):
    arms = []
    for offset in range(0, TRIAL_COUNT, REPETITIONS):
        trials = lane["trials"][offset:offset + REPETITIONS]
        first = trials[0]
        status, reason = aggregate_status(trials)
        eligible = status == "passed"
        means = {metric: statistics.mean(trial[metric] for trial in trials) for metric in METRICS} if eligible else None
        arms.append({"pairIndex": first["pairIndex"], "position": first["position"], "mode": first["mode"],
                     "trialIndices": [trial["index"] for trial in trials], "expectedTrialCount": REPETITIONS,
                     "trialCounts": counts(trials), "eligible": eligible, "status": status, "reason": reason,
                     "meansPerTask": means})
    pairs = []
    for pair_index in range(PAIR_COUNT):
        current = arms[pair_index * 2:pair_index * 2 + 2]
        status, reason = aggregate_status(current)
        eligible = status == "passed"
        by_mode = {arm["mode"]: arm for arm in current}
        differences = {metric: by_mode[MODES[1]]["meansPerTask"][metric] - by_mode[MODES[0]]["meansPerTask"][metric]
                       for metric in METRICS} if eligible else None
        pairs.append({"pairIndex": pair_index, "order": "AC" if pair_index % 2 == 0 else "CA",
                      "armIndices": [pair_index * 2, pair_index * 2 + 1], "eligible": eligible,
                      "status": status, "reason": reason, "differenceDirection": "C_minus_A", "differences": differences})
    lane.update(arms=arms, pairs=pairs,
                eligibleArmCount=sum(arm["eligible"] for arm in arms),
                excludedArmCount=PAIR_COUNT * 2 - sum(arm["eligible"] for arm in arms),
                eligiblePairCount=sum(pair["eligible"] for pair in pairs),
                excludedPairCount=PAIR_COUNT - sum(pair["eligible"] for pair in pairs))


def pair_distribution(lane, order=None):
    pairs = [pair for pair in lane["pairs"] if order is None or pair["order"] == order]
    eligible = [pair for pair in pairs if pair["eligible"]]
    modes = {}
    for mode in MODES:
        modes[mode] = {}
        for metric in METRICS:
            values = []
            for pair in pairs:
                arms = [lane["arms"][index] for index in pair["armIndices"]]
                value = next(arm for arm in arms if arm["mode"] == mode)["meansPerTask"][metric] if pair["eligible"] else None
                values.append(value)
            modes[mode][metric] = distribution(values)
    return {"expectedPairCount": len(pairs), "eligiblePairCount": len(eligible), "excludedPairCount": len(pairs) - len(eligible),
            "pairCounts": counts(pairs, derived=True), "modes": modes, "differenceDirection": "C_minus_A",
            "differences": {metric: distribution([pair["differences"][metric] if pair["eligible"] else None for pair in pairs])
                            for metric in METRICS}}


def summarize(root, expected_source):
    if type(expected_source) is not str or not re.fullmatch(r"[0-9a-f]{40}", expected_source):
        raise ValueError("exact_expected_source_required")
    archive, lanes = load_archive(root, expected_source)
    for lane in lanes:
        add_pairs(lane)
    trials = [trial for lane in lanes for trial in lane["trials"]]
    arms = [arm for lane in lanes for arm in lane["arms"]]
    pairs = [pair for lane in lanes for pair in lane["pairs"]]
    passed_reports = sum(lane["diagnosticStatus"] == "passed" for lane in lanes)
    acceptance_counts = {}
    for field, prefix in (("manualActionsAccepted", "ManualActionsAccepted"), ("modelAcceptedWrites", "ModelAcceptedWrites")):
        known = [trial[field] for trial in trials if trial.get(field) is not None and trial[field] >= 0]
        acceptance_counts["expected" + prefix] = len(evidence.LABELS) * PAIR_COUNT * REPETITIONS * 3
        acceptance_counts["verified" + prefix] = sum(trial[field] for trial in trials if trial["status"] == "passed")
        acceptance_counts["observed" + prefix] = sum(known)
        acceptance_counts["unknown" + prefix + "TrialCount"] = len(trials) - len(known)
    return {"schemaVersion": 1, "kind": "active-paired-task-summary", "diagnosticOnly": True,
            "budgetAcceptance": False, "sourceCommit": expected_source, "repository": REPOSITORY,
            "tool": {"summaryScript": "scripts/summarize_active_pairs.py",
                     "summaryScriptSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                     "reportValidatorScript": "scripts/collect_active_pair_evidence.py",
                     "reportValidatorSha256": hashlib.sha256(Path(evidence.__file__).read_bytes()).hexdigest(),
                     "commonValidatorScript": "scripts/collect_performance_evidence.py",
                     "commonValidatorSha256": hashlib.sha256(Path(common_evidence.__file__).read_bytes()).hexdigest()},
            "status": "complete" if passed_reports == len(evidence.LABELS) and all(pair["eligible"] for pair in pairs) else "incomplete",
            "expectedLaneCount": len(evidence.LABELS), "passedDiagnosticLaneCount": passed_reports,
            "expectedPairCount": len(evidence.LABELS) * PAIR_COUNT,
            "eligiblePairCount": sum(pair["eligible"] for pair in pairs),
            "excludedPairCount": sum(not pair["eligible"] for pair in pairs), "pairCounts": counts(pairs, derived=True),
            "expectedArmCount": len(evidence.LABELS) * PAIR_COUNT * 2,
            "eligibleArmCount": sum(arm["eligible"] for arm in arms),
            "excludedArmCount": sum(not arm["eligible"] for arm in arms), "armCounts": counts(arms, derived=True),
            "expectedTrialCount": len(evidence.LABELS) * TRIAL_COUNT,
            "recordedTrialCount": sum(trial["status"] in ("passed", "failed") for trial in trials),
            "trialCounts": counts(trials),
            "expectedScriptedTrialCount": len(evidence.LABELS) * PAIR_COUNT * REPETITIONS,
            "expectedAgentTrialCount": len(evidence.LABELS) * PAIR_COUNT * REPETITIONS,
            "modeTrialCounts": {mode: counts([trial for trial in trials if trial["mode"] == mode]) for mode in MODES},
            "endpointSampleCount": sum(trial.get("endpointSampleCount", 0) for trial in trials),
            **acceptance_counts,
            "counterSemantics": {"manualActionsAccepted": "Successful scripted platform-action returns; A=3 and C=0 for a passed trial.",
                                 "modelAcceptedWrites": "Writes acknowledged to the local model server; A=0 and C=3 for a passed trial."},
            "notMeasured": ["human_touch_or_typing", "ime_latency", "active_steady_state_memory", "instantaneous_memory_peak",
                            "application_startup", "full_frame_rendering", "fixture_process_resources", "energy", "thermal",
                            "physical_device_budgets", "real_model_latency", "causal_agent_overhead", "leak_absence"],
            "limitations": LIMITS, "archive": archive, "lanes": lanes,
            "groups": [{"matrixLabel": lane["matrixLabel"], "apiLevel": lane["apiLevel"],
                        "allPairs": pair_distribution(lane),
                        "byOrder": {order: pair_distribution(lane, order) for order in ("AC", "CA")}} for lane in lanes]}


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
    parser.add_argument("archive", type=Path, help="One audited eight-API active-pair-diagnostic archive")
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
        print(f"Active pair summary rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
