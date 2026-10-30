#!/usr/bin/env python3
"""Recompute source-bound busy-stop evidence per API and original scenario."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys

import collect_busy_stop_evidence as evidence
import collect_performance_evidence as common

REPOSITORY = "ecojust/Agent-OS"
TEST_SELECTION = "dev.agentos.app.BusyStopInstrumentedTest#collectBusyStopDiagnostics"
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
MAX_FILES = 8000
CONCLUSIONS = ("success", "failure", "cancelled", "timed_out", "neutral", "action_required")
JOB_CONCLUSIONS = CONCLUSIONS + ("skipped", "not_scheduled", "startup_failure")
ENDPOINTS = {
    "StopOwnedEntry": "Original owner confirmed inside Controller stop lock (probe type 5).",
    "CancelReturnUpperBound": "RunControl.cancel returned including its hooks (probe type 6); upper bound on cancellation observation, not the epoch mutation instant.",
    "StopReturn": "Controller stop publication call completed, observed by the pre-return probe (type 7); not an observation after the method returned.",
    "ControlGoneObservedUpperBound": "Test observed the stop control gone; polling upper bound, not a complete UI frame.",
    "StoppedFrameCompletion": "MODEL only: FrameMetrics INTENDED_VSYNC_TIMESTAMP + TOTAL_DURATION. Schema 2 associates the first stopped traversal's UI FrameInfo using VSYNC and four-phase draw-start bracket. Schema 1 is legacy observed candidate arithmetic, not reliable evidence of that traversal's report. Neither establishes buffer swap, enqueue, or physical presentation.",
    "PlatformCallReturn": "DEVICE only: original platform API actually returned (probe type 2), distinct from target effect and ticket settlement.",
    "FixtureBarrierExit": "DEVICE only: target Fixture hold exited; this is not platform API return.",
    "TargetEffect": "DEVICE only: original target increment was written, possibly after stop.",
    "TicketSettled": "DEVICE only: original platform call returned and its dispatch ticket closed (probe type 4); the surrounding worker may still be returning through its call stack.",
    "ReplyWriteAttempt": "MODEL only: held server attempted its reply after explicit release; does not prove client consumption.",
    "ReplyWriteEnd": "MODEL only: held server write attempt ended; includes deliberate hold and is not model cancellation latency.",
}
METRICS = ("inputDeliveryMillis", *(
    start + "To" + end + "Millis" for end in ENDPOINTS for start in ("up", "touchUp")),
    "platformCallDurationMillis", "cancelReturnToPlatformCallReturnMillis",
    "cancelReturnToTargetEffectMillis", "cancelReturnToTicketSettledMillis")
DEVICE_ENDPOINTS = ("PlatformCallReturn", "FixtureBarrierExit", "TargetEffect", "TicketSettled")
MODEL_ENDPOINTS = ("StoppedFrameCompletion", "ReplyWriteAttempt", "ReplyWriteEnd")
LIMITATIONS = [
    "One source commit, run, attempt and named profile. Each API and DEVICE/MODEL scenario retains its own denominator and distributions; no pooled P95 is computed.",
    "Primary intervals start immediately before ACTION_UP injection and include injection/dispatch overhead. Intervals from the actual target button's touchUp receipt are reported separately. DOWN is preparation, not the response start.",
    "All derived intervals use System.nanoTime/CLOCK_MONOTONIC. Raw elapsedRealtime/BOOT clocks and PerformanceTrace events remain separate and are never subtracted from MONOTONIC timestamps.",
    "Cancel-return includes synchronous cancellation hooks and is an upper bound on cancellation observation, not the exact epoch linearization instant.",
    "Control disappearance is a polling observation. DEVICE overlay disappearance supplies no complete-frame latency. Schema 2 MODEL associates the first natural stopped traversal's UI FrameInfo passed to HWUI using VSYNC plus four raw phase durations and a draw-start bracket. The resulting FrameMetrics completion does not establish successful buffer swap, enqueue, or physical display presentation.",
    "Schema 1 preserves original trial/status and historical numeric distributions as legacy observed candidate values. Its millisecond VSYNC association does not establish the first stopped traversal FrameMetrics report. Its validCount retains the original functional result and does not enter schema 2 formal completion. Different root/trial schema versions never mix within a lane or run.",
    "The original in-flight DEVICE action may produce its one target effect after stop. Barrier exit, effect, API return, ticket settlement and eventual Controller/NETWORK/CLOSER worker drain are distinct. Late held-model writes do not prove that the client consumed the reply.",
    "Only strictly valid passed original trials enter distributions. Failed trials retain observed intervals separately; missing and unexecuted slots remain explicit and no trial is replaced.",
    "P95 is nearest rank ceil(0.95*n), only for formal groups with at least 100 valid original samples for that metric. Pilot data and a 99-sample prefix never supply P95.",
    "Original run, job, instrumented-case, report and collection conclusions remain separate. Valid numeric samples do not turn a failed source attempt into a completed formal diagnostic.",
    "Repeated samples on an emulator are not independent devices. Local file hashes and semantic validation do not authenticate GitHub independently. This is not physical-device, real-model, all-workload, full-suite, energy, thermal or performance-budget acceptance.",
]


def checked_path(path):
    path = Path(path).absolute()
    if ".." in path.parts or any(part.is_symlink() for part in (path, *path.parents)):
        raise ValueError("unsafe_busy_stop_summary_path")
    return path


def read_bytes(path, maximum):
    path = checked_path(path)
    if not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ValueError("missing_or_oversized_summary_input")
    data = path.read_bytes()
    if not 0 < len(data) <= maximum:
        raise ValueError("summary_input_changed_size")
    return data


def verify_files(directory, references, profile):
    allowed = {"capture.json": evidence.MAX_CAPTURE_BYTES, evidence.REPORT_NAME: evidence.MAX_REPORT_BYTES,
               **{evidence.trial_name(i): evidence.MAX_TRIAL_BYTES for i in range(evidence.trial_count(profile))}}
    if type(references) is not list or len(references) > len(allowed):
        raise ValueError("invalid_busy_stop_archive_file_list")
    result = {}
    for ref in references:
        common.fields(ref, ("path", "bytes", "sha256"))
        name = ref["path"]
        if type(name) is not str or name not in allowed or name in result:
            raise ValueError("unexpected_or_duplicate_busy_stop_path")
        common.integer(ref["bytes"], 1, allowed[name]); evidence.digest(ref["sha256"])
        data = read_bytes(directory / name, allowed[name])
        if len(data) != ref["bytes"] or hashlib.sha256(data).hexdigest() != ref["sha256"]:
            raise ValueError("busy_stop_archive_reference_mismatch")
        result[name] = ref
    if sum(ref["bytes"] for ref in result.values()) > evidence.MAX_ARTIFACT_BYTES:
        raise ValueError("busy_stop_lane_exceeds_budget")
    directory = checked_path(directory)
    actual = set()
    if directory.exists():
        if not directory.is_dir():
            raise ValueError("invalid_busy_stop_api_directory")
        for path in directory.iterdir():
            checked_path(path)
            if not path.is_file():
                raise ValueError("unexpected_busy_stop_directory_entry")
            actual.add(path.name)
    if actual != set(result):
        raise ValueError("busy_stop_archive_file_set_mismatch")
    return result


def interval(start, end):
    if type(start) is not int or type(end) is not int or not 0 <= start <= end <= common.LONG_MAX:
        return None
    return (end - start) / 1_000_000


def event_time(trial, kind):
    values = [row[1] for row in trial["probe"]["events"] if row[0] == kind]
    return values[0] if len(values) == 1 else None


def observed_metrics(trial):
    touch, feedback = trial["input"], trial["feedback"]
    endpoints = dict(StopOwnedEntry=event_time(trial, 5), CancelReturnUpperBound=event_time(trial, 6),
                     StopReturn=event_time(trial, 7),
                     ControlGoneObservedUpperBound=feedback["controlGoneObservedNanoTime"],
                     StoppedFrameCompletion=None, PlatformCallReturn=None, FixtureBarrierExit=None,
                     TargetEffect=None, TicketSettled=None, ReplyWriteAttempt=None, ReplyWriteEnd=None)
    if trial["scenario"] == "DEVICE":
        fixture = trial["fixture"]
        final = fixture.get("afterSettle") if fixture else None
        endpoints.update(PlatformCallReturn=event_time(trial, 2), TicketSettled=event_time(trial, 4),
                         FixtureBarrierExit=final["barrierExitedNanoTime"] if final else None,
                         TargetEffect=final["effectWrittenNanoTime"] if final else None)
    else:
        frame, model = trial["frames"], trial["model"]
        if frame and frame["targetFrame"]:
            original = frame["targetFrame"]
            start, duration = original["intendedVsyncNanoTime"], original["totalDurationNanos"]
            if type(start) is int and type(duration) is int and start >= 0 and duration > 0 and start + duration <= common.LONG_MAX:
                endpoints["StoppedFrameCompletion"] = start + duration
        if model:
            endpoints.update(ReplyWriteAttempt=model["writeAttemptNanoTime"], ReplyWriteEnd=model["writeEndNanoTime"])
    metrics = dict(inputDeliveryMillis=interval(touch["upIssuedNanoTime"], touch["touchUpNanoTime"]))
    for end, timestamp in endpoints.items():
        for prefix, key in (("up", "upIssuedNanoTime"), ("touchUp", "touchUpNanoTime")):
            metrics[prefix + "To" + end + "Millis"] = interval(touch[key], timestamp)
    metrics["platformCallDurationMillis"] = (interval(event_time(trial, 1), endpoints["PlatformCallReturn"])
                                              if trial["scenario"] == "DEVICE" else None)
    for endpoint in ("PlatformCallReturn", "TargetEffect", "TicketSettled"):
        metrics["cancelReturnTo" + endpoint + "Millis"] = interval(endpoints["CancelReturnUpperBound"], endpoints[endpoint])
    return metrics


def metric_applies(name, scenario):
    if scenario == "DEVICE":
        return not any(end in name for end in MODEL_ENDPOINTS)
    return name != "platformCallDurationMillis" and not any(end in name for end in DEVICE_ENDPOINTS)


def planned(profile, status="unavailable", reason="report_unavailable"):
    return [dict(**evidence.trial_identity(i, profile), status=status, reason=reason,
                 recorded=False, eligible=False, evidenceSchemaVersion=None, firstStoppedTraversalFrameMetricsEvidence=False,
                 metrics=dict.fromkeys(METRICS), observedMetrics=dict.fromkeys(METRICS))
            for i in range(evidence.trial_count(profile))]


def summarize_trial(value, reference, label):
    observed = observed_metrics(value)
    eligible = value["status"] == "passed"
    if eligible and any(observed[name] is None for name in METRICS if metric_applies(name, value["scenario"])):
        raise ValueError("passed_busy_stop_trial_missing_measurement")
    return dict(**{key: value[key] for key in ("index", "round", "position", "order", "scenario", "status")},
                reason=None if eligible else value["failure"] or "original_trial_failed", recorded=True, eligible=eligible,
                rawRelativePath=f"busy-stop/api-{label}/{reference['path']}",
                rawBytes=reference["bytes"], rawSha256=reference["sha256"],
                phase=value["phase"], taskId=value["taskId"], epochBefore=value["epochBefore"],
                evidenceSchemaVersion=value["schemaVersion"],
                firstStoppedTraversalFrameMetricsEvidence=eligible and value["scenario"] == "MODEL" and value["schemaVersion"] == 2,
                metrics=observed if eligible else dict.fromkeys(METRICS), observedMetrics=observed)


def distribution(values, profile, applicable=True):
    available = sorted(value for value in values if value is not None)
    if any(type(value) not in (int, float) or not math.isfinite(value) or value < 0 for value in available):
        raise ValueError("invalid_derived_busy_stop_interval")
    reason = ("scenario_not_applicable" if not applicable else "pilot_profile_not_formal" if profile == "pilot"
              else "fewer_than_100_valid_original_samples" if len(available) < 100 else None)
    if not applicable and available:
        raise ValueError("scenario_inapplicable_measurement")
    return dict(plannedCount=len(values), validCount=len(available), missingCount=len(values) - len(available),
                min=available[0] if available else None, median=statistics.median(available) if available else None,
                max=available[-1] if available else None,
                p95=available[math.ceil(.95 * len(available)) - 1] if reason is None else None,
                p95Method="nearest_rank_ceil_0.95_times_n", p95MinimumCount=100, p95UnavailableReason=reason,
                unit="milliseconds", applicable=applicable)


def frame_scope(scenario, version):
    if scenario == "DEVICE":
        return "unavailable_overlay_host_removal_only"
    return ("v2_first_stopped_traversal_draw_start_bracket" if version == 2 else
            "legacy_v1_observed_candidate_not_first_stopped_traversal" if version == 1 else "report_unavailable")


def group(trials, scenario, profile, evidence_schema_version):
    current = [value for value in trials if value["scenario"] == scenario]
    return dict(scenario=scenario, plannedCount=len(current), recordedCount=sum(v["recorded"] for v in current),
                validCount=sum(v["eligible"] for v in current),
                evidenceSchemaVersion=evidence_schema_version,
                firstStoppedTraversalFrameMetricsEvidence=scenario == "MODEL" and evidence_schema_version == 2 and all(v["eligible"] for v in current),
                firstStoppedTraversalFrameMetricsEvidenceScope="all planned trials in this API/scenario",
                statusCounts={status: sum(value["status"] == status for value in current)
                              for status in ("passed", "failed", "not_executed", "unavailable")},
                frameScope=frame_scope(scenario, evidence_schema_version),
                metrics={name: distribution([v["metrics"][name] for v in current], profile, metric_applies(name, scenario))
                         for name in METRICS})


def load_archive(root, expected_source):
    root = checked_path(root)
    data = read_bytes(root / "manifest.json", MAX_MANIFEST_BYTES)
    manifest = common.strict_json(data)
    if (type(manifest) is not dict or type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1
            or manifest.get("kind") != "android-emulator-test-evidence" or manifest.get("executionScope") not in evidence.SCOPES
            or manifest.get("fullSuiteAcceptance") is not False or manifest.get("expectedMatrixLabels") != list(evidence.LABELS)
            or manifest.get("testSelection") != TEST_SELECTION):
        raise ValueError("not_a_fixed_eight_api_busy_stop_archive")
    common.integer(manifest.get("expectedTestsPerApi"), 1, 1)
    common.integer(manifest.get("expectedTests"), 8, 8)
    common.integer(manifest.get("totalBytes"), 0, evidence.MAX_ARCHIVE_BYTES)
    common.integer(manifest.get("fileCount"), 0, MAX_FILES)
    profile = evidence.profile_for_scope(manifest["executionScope"])
    source = manifest.get("sourceRun")
    if type(source) is not dict:
        raise ValueError("invalid_busy_stop_archive_source")
    for key in ("runId", "attempt"): common.integer(source.get(key), 1)
    if (source.get("repository") != REPOSITORY or source.get("commit") != expected_source
            or source.get("branch") != "master" or source.get("event") != "workflow_dispatch"
            or source.get("url") != f"https://github.com/{REPOSITORY}/actions/runs/{source['runId']}"
            or source.get("conclusion") not in CONCLUSIONS):
        raise ValueError("busy_stop_archive_source_mismatch")
    identity = {key: source[key] for key in ("repository", "commit", "runId", "attempt")}
    section = manifest.get("busyStop")
    common.fields(section, ("kind", "diagnosticOnly", "budgetAcceptance", "profile", "scope", "apis"))
    if (section["kind"] != "busy-stop-attachments" or section["diagnosticOnly"] is not True
            or section["budgetAcceptance"] is not False or section["profile"] != profile):
        raise ValueError("invalid_busy_stop_attachment_scope")
    evidence.bounded_text(section["scope"], 8192)
    records, cases = section["apis"], manifest.get("apis")
    for values in (records, cases):
        if (type(values) is not list or len(values) != 8 or any(type(row) is not dict for row in values)
                or [row.get("matrixLabel") for row in values] != list(evidence.LABELS)):
            raise ValueError("missing_duplicate_or_reordered_busy_stop_api")
    active = checked_path(root / "busy-stop")
    if active.exists():
        if not active.is_dir(): raise ValueError("invalid_busy_stop_root")
        for path in active.iterdir():
            checked_path(path)
            if path.name not in {"api-" + label for label in evidence.LABELS} or not path.is_dir():
                raise ValueError("unexpected_busy_stop_api_directory")
    lanes, artifact_ids, total_bytes, total_files, report_versions = [], set(), 0, 0, set()
    for record, case in zip(records, cases):
        label, api = record["matrixLabel"], int(record["matrixLabel"].split(".")[0])
        common.integer(record.get("apiLevel"), api, api)
        if (record.get("profile") != profile or record.get("jobConclusion") not in JOB_CONCLUSIONS
                or case.get("jobConclusion") != record["jobConclusion"]
                or case.get("diagnosticResult") not in ("passed", "failed", "unverified")):
            raise ValueError("invalid_busy_stop_original_api_result")
        directory = active / ("api-" + label)
        files = verify_files(directory, record.get("files"), profile)
        total_bytes += sum(ref["bytes"] for ref in files.values()); total_files += len(files)
        if total_bytes > manifest["totalBytes"] or total_files > manifest["fileCount"]:
            raise ValueError("busy_stop_attachments_exceed_shared_archive_accounting")
        lane = dict(record, sourceRunConclusion=source["conclusion"], instrumentedCaseResult=case["diagnosticResult"],
                    reason="report_unavailable", evidenceSchemaVersion=None, trials=planned(profile))
        lanes.append(lane)
        if not files:
            if record.get("captureStatus") != "artifact_unavailable" or record.get("diagnosticStatus") is not None or record.get("reportStatus") is not None:
                raise ValueError("invalid_missing_busy_stop_capture")
            lane["reason"] = "artifact_unavailable"
            continue
        if "capture.json" not in files: raise ValueError("missing_busy_stop_capture_file")
        common.integer(record.get("artifactId"), 1)
        if record["artifactId"] in artifact_ids: raise ValueError("duplicate_busy_stop_artifact_id")
        artifact_ids.add(record["artifactId"])
        capture = evidence.validate_capture(common.strict_json(read_bytes(directory / "capture.json", evidence.MAX_CAPTURE_BYTES)), label, identity, profile)
        payloads = evidence.validate_payloads(directory, capture, label, identity, profile)
        if set(files) != {"capture.json", *payloads}: raise ValueError("busy_stop_archive_payload_set_mismatch")
        for name, raw in payloads.items():
            if files[name]["bytes"] != len(raw) or files[name]["sha256"] != hashlib.sha256(raw).hexdigest():
                raise ValueError("busy_stop_validated_payload_reference_mismatch")
        for key in ("profile", "captureStatus", "captureError", "actualSdkInt", "gradleExitCode", "declaredCiProfile", "diagnosticStatus"):
            if type(record.get(key)) is not type(capture[key]) or record.get(key) != capture[key]:
                raise ValueError("busy_stop_archive_capture_field_mismatch")
        lane.update(capture=capture, captureRelativePath=f"busy-stop/api-{label}/capture.json",
                    captureSha256=files["capture.json"]["sha256"])
        if capture["report"] is None:
            if record.get("reportStatus") is not None: raise ValueError("busy_stop_archive_report_status_mismatch")
            continue
        report = common.strict_json(payloads[evidence.REPORT_NAME])
        if record.get("reportStatus") != report["status"]: raise ValueError("busy_stop_archive_report_status_mismatch")
        report_versions.add(report["schemaVersion"])
        if len(report_versions) > 1:
            raise ValueError("busy_stop_run_mixes_evidence_schema_versions")
        # A failing shard/root commit may leave only earlier successful trials.
        # That first missing slot may already have run, so absence alone cannot
        # establish nonexecution. A committed failed trial is the fail-fast fence.
        failed_trial_committed = (report["status"] == "failed" and report["trials"]
                                  and report["trials"][-1]["status"] == "failed")
        tail_status = "not_executed" if failed_trial_committed else "unavailable"
        tail_reason = ("not_executed_after_failure" if failed_trial_committed else
                       "not_committed_before_failure" if report["status"] == "failed" else
                       "not_committed_before_interruption")
        lane.update(report=report, evidenceSchemaVersion=report["schemaVersion"], trials=planned(profile, tail_status, tail_reason),
                    reason=None if capture["diagnosticStatus"] == "passed" else "original_diagnostic_" + capture["diagnosticStatus"])
        for ref in report["trials"]:
            lane["trials"][ref["index"]] = summarize_trial(common.strict_json(payloads[ref["path"]]), ref, label)
    archive = dict(sourceRun=source, manifestRelativePath="manifest.json", manifestSha256=hashlib.sha256(data).hexdigest(),
                   archiveDirectory=root.name, executionScope=manifest["executionScope"], attachmentBytes=total_bytes,
                   attachmentFileCount=total_files, declaredArchiveBytes=manifest["totalBytes"],
                   declaredArchiveFileCount=manifest["fileCount"],
                   evidenceSchemaVersion=next(iter(report_versions)) if report_versions else None)
    return archive, profile, lanes


def summarize(root, expected_source):
    if type(expected_source) is not str or re.fullmatch(r"[0-9a-f]{40}", expected_source) is None:
        raise ValueError("exact_expected_source_required")
    archive, profile, lanes = load_archive(root, expected_source)
    for lane in lanes:
        lane["scenarios"] = {scenario: group(lane["trials"], scenario, profile, lane["evidenceSchemaVersion"])
                             for scenario in evidence.SCENARIOS}
    trials = [value for lane in lanes for value in lane["trials"]]
    evidence_complete = all(value["eligible"] for value in trials) and all(lane.get("diagnosticStatus") == "passed" for lane in lanes)
    originals_success = (archive["sourceRun"]["conclusion"] == "success"
                         and all(lane["jobConclusion"] == "success" and lane["instrumentedCaseResult"] == "passed" for lane in lanes))
    complete = evidence_complete and originals_success
    version = archive["evidenceSchemaVersion"]
    return dict(schemaVersion=1, kind="busy-stop-summary", diagnosticOnly=True, budgetAcceptance=False, fullSuiteAcceptance=False,
                sourceCommit=expected_source, profile=profile, status="complete" if complete else "incomplete",
                evidenceSchemaVersion=version, frameScope=frame_scope("MODEL", version),
                firstStoppedTraversalFrameMetricsEvidence=version == 2 and all(lane["scenarios"]["MODEL"]["firstStoppedTraversalFrameMetricsEvidence"] for lane in lanes),
                firstStoppedTraversalFrameMetricsEvidenceScope="all planned MODEL trials across eight APIs",
                evidenceComplete=evidence_complete, originalConclusionsSuccessful=originals_success,
                formalDiagnosticComplete=profile == "formal" and version == 2 and complete, plannedLaneCount=8,
                plannedTrialCount=len(trials), recordedTrialCount=sum(v["recorded"] for v in trials),
                validTrialCount=sum(v["eligible"] for v in trials),
                metricDefinitions=dict(unit="milliseconds", clock="CLOCK_MONOTONIC_System.nanoTime",
                    primaryStart="input.upIssuedNanoTime", receivedStart="input.touchUpNanoTime", endpoints=ENDPOINTS),
                tools={Path(module.__file__).stem: dict(path="scripts/" + Path(module.__file__).name,
                       sha256=hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest())
                       for module in (sys.modules[__name__], evidence, common)},
                limitations=LIMITATIONS, archive=archive, lanes=lanes)


def write_output(path, root, content):
    path, root = checked_path(path), checked_path(root)
    if path == root or root in path.parents: raise ValueError("output_must_not_modify_input_evidence")
    if path.exists(): raise ValueError("output_already_exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream: stream.write(content)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="One eight-API pilot or formal archive")
    parser.add_argument("--source", required=True, help="Exact expected tested commit")
    parser.add_argument("--output", type=Path, help="New JSON outside the input archive; default stdout")
    args = parser.parse_args(argv)
    try:
        result = summarize(args.archive, args.source)
        content = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        if args.output: write_output(args.output, args.archive, content)
        else: sys.stdout.write(content)
        return 0 if result["status"] == "complete" else 1
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"Busy-stop summary rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
