#!/usr/bin/env python3
"""Recompute one eight-API Fixture experience archive without pooling profiles."""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys

import collect_app_experience_evidence as evidence
import collect_performance_evidence as common
import experience_trace as trace

REPOSITORY = "ecojust/Agent-OS"
MAX_MANIFEST_BYTES = 8 * 1024 * 1024
MAX_ARCHIVE_BYTES = 160 * 1024 * 1024
RESOURCE_METRICS = (
    "targetCpuDeltaMillis", "targetWallMillis", "targetOneCorePercent",
    "agentCpuDeltaMillis", "agentWallMillis", "agentOneCorePercent",
    "targetBeforePssKiB", "targetAfterPssKiB", "targetPssChangeKiB",
    "agentBeforePssKiB", "agentAfterPssKiB", "agentPssChangeKiB",
    "twoProcessBeforePssSumKiB", "twoProcessAfterPssSumKiB", "twoProcessPssSumChangeKiB")
PAIRED_RESOURCE_METRICS = tuple(key for key in RESOURCE_METRICS if not key.endswith("OneCorePercent"))
LIMITATIONS = [
    "One source, run, attempt and named profile; APIs, cold/hot starts and pilot/formal samples are never pooled for startup quantiles.",
    "TTID is official pinned Perfetto startup-to-RenderThread DrawFrame completion, not physical display presentation or am start -W TotalTime.",
    "P95 uses nearest rank ceil(0.95*n) on at least 100 valid original TTIDs in each reported group. Pilot and 50-pair order subgroups cannot produce P95.",
    "B-minus-A group median/P95 differences and the distribution of matched-pair B-minus-A values are distinct statistics; paired differences have no reported P95.",
    "The same emulator and resident instrumented process supply repeated samples; sample counts do not count independent devices or establish causal product cost.",
    "CPU is cumulative process CPU milliseconds over each process's own positive wall interval. Zero quantized CPU deltas are valid; one logical core is 100%. Percentage differences are not computed as net overhead.",
    "Target and Agent intervals and PSS endpoints are sampled separately. Two-process PSS sums combine different sampling instants and are neither simultaneous system memory nor a peak or steady state.",
    "PSS category readings are preserved separately and are not summed with overlapping Graphics summaries. Probe/Binder, instrumentation, local-model, cache/GC and host effects remain.",
    "Frame counts describe received HWUI reports, not presented screen frames. End-snapshot dropped reports, late-callback dropped reports, buffer overflow and late received callbacks are distinct counters. Late cumulative snapshots use each task's maximum before summing tasks; received-row drop counters overlap the end-snapshot counters and are not added again. Missing late-drop fields remain unavailable.",
    "FIRST_DRAW_FRAME is separate from non-first timing and exceedance denominators. FrameMetrics is not TTID, physical presentation, sustained scrolling or complete system composition.",
    "API31+ TOTAL_DURATION-minus-DEADLINE is a named HWUI diagnostic, not Macrobenchmark frameOverrunMs. Only positive known deadline budgets enter comparison: platform -1, other non-positive values and the HWUI INT64_MAX-derived unknown sentinel are excluded with separate reasons. API30 has no GPU/deadline value and uses a separately labelled nominal-refresh-period comparison only when attribution is stable.",
    "Per-frame refreshHz is the platform-reported application-rate snapshot, not measured physical panel frequency. Mode rate, observation times and display changes remain in raw evidence.",
    "A failed root retains a known unexecuted suffix; an incomplete or missing root retains unknown uncommitted samples. No failed sample is replaced and partial traces cannot establish complete startup evidence.",
    "Local archive hash validation and archived SQL-result recomputation do not authenticate GitHub or independently execute the binary against the trace; raw trace and tool identities are retained for that audit.",
    "No third-party-app, physical-device, ROM, real-model, energy, thermal, performance-budget or full-suite acceptance follows from these controlled Fixture diagnostics.",
]


def checked_path(path):
    path = Path(path).absolute()
    if ".." in path.parts or any(part.is_symlink() for part in (path, *path.parents)):
        raise ValueError("unsafe_evidence_or_output_path")
    return path


def checked_json(path, maximum):
    data = evidence.read_bounded(checked_path(path), maximum)
    return common.strict_json(data), hashlib.sha256(data).hexdigest()


def allowed_files(profile):
    plan = evidence.conditions(profile)
    return {"capture.json": evidence.MAX_CAPTURE_BYTES, evidence.REPORT_NAME: evidence.MAX_REPORT_BYTES,
            "trace-capture.json": 16384, "trace.pftrace.gz": evidence.MAX_GZIP_BYTES,
            "startup-ttid.json": evidence.MAX_DERIVED_BYTES, "startup-ttid.csv": evidence.MAX_DERIVED_BYTES,
            **{evidence.launch_name(i): evidence.MAX_LAUNCH_BYTES for i in range(plan["launchCount"])},
            **{evidence.trial_name(i): evidence.MAX_TRIAL_BYTES for i in range(plan["trialCount"])}}


def verify_files(directory, references, profile):
    allowed, expected = allowed_files(profile), {}
    if type(references) is not list or len(references) > len(allowed):
        raise ValueError("invalid_experience_archive_file_list")
    for ref in references:
        common.fields(ref, ("path", "bytes", "sha256"))
        name = ref["path"]
        if type(name) is not str or name not in allowed or name in expected:
            raise ValueError("unexpected_or_duplicate_archive_path")
        common.integer(ref["bytes"], 1, allowed[name]); evidence.digest(ref["sha256"])
        data = evidence.read_bounded(checked_path(directory / name), allowed[name])
        if len(data) != ref["bytes"] or hashlib.sha256(data).hexdigest() != ref["sha256"]:
            raise ValueError("archive_file_digest_or_size_mismatch")
        expected[name] = ref
    if sum(ref["bytes"] for ref in expected.values()) > evidence.MAX_ARTIFACT_BYTES:
        raise ValueError("archive_lane_exceeds_budget")
    directory = checked_path(directory)
    actual = set()
    if directory.exists():
        if not directory.is_dir():
            raise ValueError("invalid_experience_api_directory")
        for entry in directory.iterdir():
            checked_path(entry)
            if not entry.is_file():
                raise ValueError("unexpected_experience_directory_entry")
            actual.add(entry.name)
    if actual != set(expected):
        raise ValueError("archive_file_set_mismatch")
    return expected


def planned(profile, kind, reason, status="unavailable"):
    result = []
    for index in range(evidence.conditions(profile)["launchCount" if kind == "launches" else "trialCount"]):
        value = dict(index=index, status=status, reason=reason, eligible=False, recorded=False)
        if kind == "launches":
            pair, position, arm, startup_type = evidence.launch_identity(index, profile)
            value.update(pairIndex=pair, position=position, arm=arm, startupType=startup_type,
                         order="AB" if pair % 2 == 0 else "BA", ttidMillis=None, observedTtidMillis=None)
        else:
            value.update(repetition=index // 3, position=index % 3, arm=evidence.trial_arm(index),
                         order=evidence.ORDERS[index // 3], metrics=dict.fromkeys(RESOURCE_METRICS))
        result.append(value)
    return result


def raw_reference(reference, label):
    return dict(rawRelativePath=f"app-experience/api-{label}/{reference['path']}",
                rawBytes=reference["bytes"], rawSha256=reference["sha256"])


def summarize_launch(value, ref, label, row, trace_complete):
    result = dict(value, **raw_reference(ref, label), recorded=True, driverStatus=value["status"],
                  order="AB" if value["pairIndex"] % 2 == 0 else "BA", traceMeasurement=row,
                  ttidMillis=None, observedTtidMillis=row["ttidNanos"] / 1e6 if row and row["ttidNanos"] is not None else None)
    if value["status"] != "passed":
        status, reason = value["status"], value["unavailableReason"] or "driver_launch_failed"
    elif row is None:
        status, reason = "unavailable", "startup_derivation_unavailable"
    elif row["status"] != "passed":
        status, reason = "failed", row["reason"]
    elif not trace_complete:
        status, reason = "unavailable", "trace_capture_not_complete"
    else:
        status, reason = "passed", None
        result["ttidMillis"] = row["ttidNanos"] / 1e6
    result.update(status=status, reason=reason, eligible=status == "passed")
    return result


def cpu_interval(start, end, cpu_start, cpu_end):
    valid = min(start, end, cpu_start, cpu_end) >= 0 and end > start and cpu_end >= cpu_start
    if not valid:
        return None, None, None
    wall, cpu = (end - start) / 1e6, cpu_end - cpu_start
    return cpu, wall, 100 * cpu / wall


def difference(after, before):
    return after - before if after is not None and before is not None else None


def sum_endpoints(first, second):
    return first + second if first is not None and second is not None else None


def summarize_trial(value, ref, label):
    result = {key: item for key, item in value.items() if key != "frames"}
    result.update(**raw_reference(ref, label), recorded=True, eligible=value["status"] == "passed",
                  reason=None if value["status"] == "passed" else "resource_trial_failed", order=evidence.ORDERS[value["repetition"]])
    before, after = value["targetBefore"], value["targetAfter"]
    agent_before, agent_after = value["agentBeforeSample"], value["agentAfterSample"]
    target_cpu = cpu_interval(after["beginNanoTime"], after["endNanoTime"], after["beginCpuMillis"], after["endCpuMillis"]) if after and after["ended"] else (None,) * 3
    agent_cpu = cpu_interval(value["agentTaskStartedNanos"], value["agentTaskEndedNanos"],
                             value["agentTaskStartedCpuMillis"], value["agentTaskEndedCpuMillis"])
    metrics = dict(zip(RESOURCE_METRICS[:6], (*target_cpu, *agent_cpu)))
    metrics.update(targetBeforePssKiB=before["beforePssKb"] if before else None,
                   targetAfterPssKiB=after["afterPssKb"] if after and after["ended"] else None,
                   agentBeforePssKiB=agent_before["totalPssKiB"] if agent_before else None,
                   agentAfterPssKiB=agent_after["totalPssKiB"] if agent_after else None)
    for prefix in ("target", "agent"):
        metrics[prefix + "PssChangeKiB"] = difference(metrics[prefix + "AfterPssKiB"], metrics[prefix + "BeforePssKiB"])
    for endpoint in ("Before", "After"):
        metrics["twoProcess" + endpoint + "PssSumKiB"] = sum_endpoints(metrics["target" + endpoint + "PssKiB"], metrics["agent" + endpoint + "PssKiB"])
    metrics["twoProcessPssSumChangeKiB"] = difference(metrics["twoProcessAfterPssSumKiB"], metrics["twoProcessBeforePssSumKiB"])
    if result["eligible"] and any(metrics[key] is None for key in RESOURCE_METRICS):
        raise ValueError("passed_trial_missing_resource_interval")
    result.update(metrics=metrics, twoProcessPssSampling="sum_of_endpoints_at_different_times",
                  frameSummary=frame_summary([value], int(label.split('.')[0])))
    return result


def distribution(values, p95=False):
    available = sorted(value for value in values if value is not None)
    if any(type(value) not in (int, float) or not math.isfinite(value) for value in available):
        raise ValueError("nonfinite_derived_measurement")
    result = dict(expectedCount=len(values), count=len(available), missingCount=len(values) - len(available),
                  min=available[0] if available else None, median=statistics.median(available) if available else None,
                  max=available[-1] if available else None)
    if p95:
        result.update(p95=available[math.ceil(0.95 * len(available)) - 1] if len(available) >= 100 else None,
                      p95Method="nearest_rank_ceil_0.95_times_n", p95MinimumCount=100,
                      p95UnavailableReason=None if len(available) >= 100 else "fewer_than_100_valid_original_samples")
    return result


def counts(values):
    return {status: sum(value["status"] == status for value in values)
            for status in ("passed", "failed", "unavailable", "not_executed")}


def startup_group(launches, profile):
    modes = {}
    for arm in evidence.ARMS[:2]:
        current = [value for value in launches if value["arm"] == arm]
        stats = distribution([value["ttidMillis"] if value["eligible"] else None for value in current], p95=True)
        if profile == "pilot":
            stats.update(p95=None, p95UnavailableReason="pilot_profile_not_formal")
        modes[arm] = dict(ttidMillis=stats, statuses=counts(current))
    pairs = []
    for pair in sorted({value["pairIndex"] for value in launches}):
        values = [value for value in launches if value["pairIndex"] == pair]
        by_arm = {value["arm"]: value for value in values}
        eligible = len(values) == 2 and all(value["eligible"] for value in values)
        pairs.append(dict(pairIndex=pair, order="AB" if pair % 2 == 0 else "BA", eligible=eligible,
                          launchIndices=[value["index"] for value in values],
                          bMinusAMillis=by_arm[evidence.ARMS[1]]["ttidMillis"] - by_arm[evidence.ARMS[0]]["ttidMillis"] if eligible else None))
    a, b = (modes[arm]["ttidMillis"] for arm in evidence.ARMS[:2])
    return dict(expectedLaunchCount=len(launches), recordedLaunchCount=sum(v["recorded"] for v in launches),
                statuses=counts(launches), modes=modes,
                groupQuantileDifferenceBMinusAMillis={key: difference(b[key], a[key]) for key in ("median", "p95")},
                pairedBMinusAMillis=distribution([pair["bMinusAMillis"] for pair in pairs]), pairs=pairs)


def exceedance(values, expected):
    available = [value for value in values if value is not None]
    return dict(nonFirstReportCount=expected, comparableReportCount=len(available),
                unavailableReportCount=expected - len(available), exceededReportCount=sum(value > 0 for value in available),
                exceededFraction=sum(value > 0 for value in available) / len(available) if available else None,
                differenceMillis=distribution(values))


def frame_summary(trials, api):
    """All received rows remain visible; comparators exclude first-draw reports."""
    rows = [(frame, trial.get("targetAfter")) for trial in trials for frame in trial.get("frames", [])]
    nonfirst = [(frame, target) for frame, target in rows if frame["firstDraw"] == 0]
    first = [frame for frame, unused in rows if frame["firstDraw"] == 1]
    targets = [trial["targetAfter"] for trial in trials if trial.get("targetAfter") is not None]
    end_dropped = sum(target["droppedReports"] for target in targets)
    late_dropped = []
    for trial in trials:
        snapshots = [*([] if trial.get("targetAfter") is None else [trial["targetAfter"]]),
                     *trial.get("framePageChecks", [])]
        known = [snapshot["lateDroppedReports"] for snapshot in snapshots if "lateDroppedReports" in snapshot]
        late_dropped.append(max(known) if known else None)
    late_dropped_total = sum(late_dropped) if late_dropped and all(value is not None for value in late_dropped) else None
    # FrameMetrics.DEADLINE is FrameDeadline - IntendedVsync. HWUI's
    # UNKNOWN_DEADLINE is INT64_MAX, so its derived positive value is not a
    # usable budget. Public getMetric documents -1 as unavailable; zero or
    # other non-positive values are simply not comparable, not an invented
    # platform unknown encoding.
    deadline_reasons = [
        "unsupported_api_30" if api == 30 else
        "platform_unavailable" if frame["deadlineNs"] == -1 else
        "non_positive_budget" if frame["deadlineNs"] <= 0 else
        "unknown_deadline_sentinel" if frame["deadlineNs"] + frame["intendedVsyncNs"] == common.LONG_MAX else None
        for frame, unused in nonfirst]
    deadline = [(frame["totalDurationNs"] - frame["deadlineNs"]) / 1e6 if reason is None else None
                for (frame, unused), reason in zip(nonfirst, deadline_reasons)]
    def attributed_refresh(frame, target):
        return (target is not None and target["displayStable"] and frame["refreshHz"] > 0
                and frame["refreshHz"] == target["beginRefreshHz"] == target["endRefreshHz"]
                and frame["modeHz"] > 0 and frame["modeHz"] == target["beginModeHz"] == target["endModeHz"]
                and 0 < frame["refreshObservedNanoTime"] <= frame["vsyncNs"])
    nominal = [(frame["totalDurationNs"] / 1e6 - 1000 / frame["refreshHz"])
               if attributed_refresh(frame, target) else None for frame, target in nonfirst]
    return dict(
        observedTaskCount=len(trials), targetSessionCount=len(targets), receivedReportCount=len(rows),
        targetBufferedReportCount=sum(target["frameCount"] for target in targets),
        firstDrawReportCount=len(first), nonFirstReportCount=len(nonfirst),
        droppedReportCount=end_dropped,
        lateDroppedReportCount=late_dropped_total,
        knownLateDroppedReportCount=sum(value for value in late_dropped if value is not None),
        lateDroppedReportUnavailableTaskCount=sum(value is None for value in late_dropped),
        observedDroppedReportCount=end_dropped + late_dropped_total
        if late_dropped_total is not None and len(targets) == len(trials) else None,
        droppedReportSemantics="end_snapshot_plus_task_max_late_cumulative_counts_received_row_counts_overlap_not_added",
        receivedRowDroppedReportCount=sum(frame["droppedReports"] for frame, unused in rows),
        bufferOverflowCount=sum(target["frameOverflow"] for target in targets),
        endSnapshotLateReportCount=sum(target["lateFrameReports"] for target in targets),
        lateReportCount=sum(max([trial["targetAfter"]["lateFrameReports"] if trial.get("targetAfter") else 0,
                                 *[check["lateFrameReports"] for check in trial.get("framePageChecks", [])]]) for trial in trials),
        pageObservedIncompleteDrainTaskCount=sum(any(not check["drainComplete"] for check in trial.get("framePageChecks", [])) for trial in trials),
        endSnapshotIncompleteDrainTaskCount=sum(not target["drainComplete"] for target in targets),
        incompleteDrainTaskCount=sum((trial.get("targetAfter") is not None and not trial["targetAfter"]["drainComplete"])
                                    or any(not check["drainComplete"] for check in trial.get("framePageChecks", [])) for trial in trials),
        drainTimedOutTaskCount=sum(target["drainTimedOut"] for target in targets),
        finalFrameMissingTaskCount=sum(not target["finalFrameObserved"] for target in targets),
        displayUnstableTaskCount=sum(not target["displayStable"] for target in targets),
        refreshAttributionUncertainReportCount=sum(not attributed_refresh(frame, target) for frame, target in rows),
        taskDisplayChangeCount=sum(max(0, target["endDisplayChangeCount"] - target["beginDisplayChangeCount"]) for target in targets),
        crossesBeginReportCount=sum(frame["crossesBegin"] for frame, unused in rows),
        unknownDrawAssociationReportCount=sum(frame["drawVersion"] < 0 for frame, unused in rows),
        firstDrawTotalDurationMillis=distribution([frame["totalDurationNs"] / 1e6 for frame in first]),
        nonFirstTotalDurationMillis=distribution([frame["totalDurationNs"] / 1e6 for frame, unused in nonfirst]),
        nonFirstGpuDurationMillis=dict(availability="unavailable_on_api_30" if api == 30 else "platform_reported",
            **distribution([frame["gpuDurationNs"] / 1e6 if frame["gpuDurationNs"] >= 0 else None for frame, unused in nonfirst])),
        nonFirstTotalMinusDeadlineMillis=dict(availability="unavailable_on_api_30" if api == 30 else "platform_deadline",
            unavailableReasonCounts={reason: deadline_reasons.count(reason) for reason in
                ("unsupported_api_30", "platform_unavailable", "non_positive_budget", "unknown_deadline_sentinel")},
            **exceedance(deadline, len(nonfirst))),
        api30NominalRefreshPeriodComparison=dict(availability="nominal_period_only" if api == 30 else "not_used_api_31_plus",
            **exceedance(nominal if api == 30 else [None] * len(nonfirst), len(nonfirst))),
        reportedApplicationRefreshHz=distribution([frame["refreshHz"] if frame["refreshHz"] > 0 else None for frame, unused in rows]),
        reportedActiveModeRefreshHz=distribution([frame["modeHz"] if frame["modeHz"] > 0 else None for frame, unused in rows]),
        refreshSnapshotValuesHz=sorted({frame["refreshHz"] for frame, unused in rows if frame["refreshHz"] > 0}),
        refreshSemantics="platform_application_snapshot_not_physical_panel_measurement",
        qualityScope="received_reports_only_no_inference_that_missing_reports_were_fast")


def resource_group(trials):
    modes = {}
    for arm in evidence.ARMS:
        current = [value for value in trials if value["arm"] == arm]
        modes[arm] = dict(expectedTrialCount=len(current), statuses=counts(current),
                         metrics={key: distribution([value["metrics"][key] if value["eligible"] else None for value in current])
                                  for key in RESOURCE_METRICS})
    paired = {}
    for arm in evidence.ARMS[1:]:
        pairs = []
        for repetition in sorted({value["repetition"] for value in trials}):
            by_arm = {value["arm"]: value for value in trials if value["repetition"] == repetition}
            a, b = by_arm.get(evidence.ARMS[0]), by_arm.get(arm)
            eligible = a is not None and b is not None and a["eligible"] and b["eligible"]
            pairs.append(dict(repetition=repetition, order=evidence.ORDERS[repetition], eligible=eligible,
                              trialIndices=[v["index"] for v in (a, b) if v is not None],
                              differences={key: b["metrics"][key] - a["metrics"][key] for key in PAIRED_RESOURCE_METRICS} if eligible else None))
        paired[arm[0] + "MinusA"] = dict(expectedPairCount=len(pairs), eligiblePairCount=sum(p["eligible"] for p in pairs),
            metrics={key: distribution([pair["differences"][key] if pair["eligible"] else None for pair in pairs])
                     for key in PAIRED_RESOURCE_METRICS}, pairs=pairs)
    return dict(expectedTrialCount=len(trials), recordedTrialCount=sum(v["recorded"] for v in trials),
                statuses=counts(trials), modes=modes, paired=paired)


def load_archive(root, expected_source):
    root = checked_path(root)
    manifest, manifest_sha = checked_json(root / "manifest.json", MAX_MANIFEST_BYTES)
    if (type(manifest) is not dict or type(manifest.get("schemaVersion")) is not int
            or manifest["schemaVersion"] != 1 or manifest.get("kind") != "android-emulator-test-evidence"
            or manifest.get("executionScope") not in evidence.SCOPES or manifest.get("fullSuiteAcceptance") is not False
            or manifest.get("expectedMatrixLabels") != list(evidence.LABELS)):
        raise ValueError("not_an_eight_api_experience_archive")
    profile = evidence.profile_for_scope(manifest["executionScope"])
    source = manifest.get("sourceRun")
    if type(source) is not dict:
        raise ValueError("invalid_archive_source")
    for key in ("runId", "attempt"):
        common.integer(source.get(key), 1)
    if (source.get("repository") != REPOSITORY or source.get("commit") != expected_source or source.get("branch") != "master"
            or source.get("url") != f"https://github.com/{REPOSITORY}/actions/runs/{source['runId']}"):
        raise ValueError("archive_source_mismatch")
    conclusions = ("success", "failure", "cancelled", "timed_out", "neutral", "action_required")
    if source.get("conclusion") not in conclusions:
        raise ValueError("invalid_original_run_conclusion")
    identity = {key: source[key] for key in ("repository", "commit", "runId", "attempt")}
    section = manifest.get("appExperience")
    if (type(section) is not dict or section.get("kind") != "app-experience-attachments"
            or section.get("diagnosticOnly") is not True or section.get("budgetAcceptance") is not False
            or section.get("profile") != profile):
        raise ValueError("invalid_experience_attachment_scope")
    records = section.get("apis")
    if (type(records) is not list or len(records) != len(evidence.LABELS)
            or any(type(record) is not dict for record in records)
            or [record.get("matrixLabel") for record in records] != list(evidence.LABELS)):
        raise ValueError("missing_duplicate_or_reordered_api_record")
    active_root = checked_path(root / "app-experience")
    if active_root.exists():
        if not active_root.is_dir():
            raise ValueError("invalid_experience_root")
        for path in active_root.iterdir():
            checked_path(path)
            if path.name not in {"api-" + label for label in evidence.LABELS} or not path.is_dir():
                raise ValueError("unexpected_experience_api_directory")
    archive = dict(sourceRun=source, manifestSha256=manifest_sha, archiveDirectory=root.name,
                   manifestRelativePath="manifest.json", executionScope=manifest["executionScope"])
    lanes, archive_bytes = [], 0
    for record in records:
        label, api = record["matrixLabel"], int(record["matrixLabel"].split('.')[0])
        common.integer(record.get("apiLevel"), api, api)
        if record.get("profile") != profile or record.get("jobConclusion") not in conclusions + ("skipped", "not_scheduled", "startup_failure"):
            raise ValueError("invalid_archive_profile_or_job_conclusion")
        directory = active_root / ("api-" + label)
        files = verify_files(directory, record.get("files"), profile)
        archive_bytes += sum(ref["bytes"] for ref in files.values())
        if archive_bytes > MAX_ARCHIVE_BYTES:
            raise ValueError("experience_archive_exceeds_budget")
        lane = dict(record, sourceRunConclusion=source["conclusion"], reason=None,
                    launches=planned(profile, "launches", "report_unavailable"),
                    trials=planned(profile, "trials", "report_unavailable"),
                    traceCapture=None, startupDerivation=None)
        lanes.append(lane)
        if not files:
            if (record.get("captureStatus") != "artifact_unavailable" or record.get("diagnosticStatus") is not None
                    or record.get("reportStatus") is not None):
                raise ValueError("missing_capture_record")
            lane["reason"] = "artifact_unavailable"
            continue
        if "capture.json" not in files:
            raise ValueError("missing_capture_file")
        common.integer(record.get("artifactId"), 1)
        capture, unused = checked_json(directory / "capture.json", evidence.MAX_CAPTURE_BYTES)
        payloads = evidence.validate_payloads(directory, capture, label, identity, profile)
        if set(files) != {"capture.json", *payloads}:
            raise ValueError("archive_capture_payload_set_mismatch")
        for ref in capture["payloads"]:
            if files[ref["path"]] != ref:
                raise ValueError("archive_capture_payload_reference_mismatch")
        for field in ("profile", "captureStatus", "captureError", "actualSdkInt", "gradleExitCode", "declaredCiProfile", "diagnosticStatus"):
            if not trace.same_typed(record.get(field), capture[field]):
                raise ValueError("archive_capture_field_mismatch")
        lane.update(capture=capture, captureRelativePath=f"app-experience/api-{label}/capture.json",
                    captureSha256=files["capture.json"]["sha256"])
        for name, key in (("trace-capture.json", "traceCapture"), ("startup-ttid.json", "startupDerivation")):
            if name in payloads:
                lane[key] = common.strict_json(payloads[name])
        if capture["report"] is None:
            if record.get("reportStatus") is not None:
                raise ValueError("archive_report_status_mismatch")
            lane["reason"] = "report_unavailable"
            continue
        report = common.strict_json(payloads[evidence.REPORT_NAME])
        if record.get("reportStatus") != report["status"]:
            raise ValueError("archive_report_status_mismatch")
        tail_status = "not_executed" if report["status"] == "failed" else "unavailable"
        tail_reason = "not_executed_after_failure" if report["status"] == "failed" else "not_committed_before_interruption"
        lane.update(report=report, **raw_reference(files[evidence.REPORT_NAME], label),
                    reason=None if capture["diagnosticStatus"] == "passed" else "original_diagnostic_" + capture["diagnosticStatus"],
                    launches=planned(profile, "launches", tail_reason, tail_status),
                    trials=planned(profile, "trials", tail_reason, tail_status))
        derived = lane["startupDerivation"]
        rows = {row["index"]: row for row in derived["launches"]} if derived else {}
        trace_complete = lane["traceCapture"] is not None and lane["traceCapture"]["status"] == "collected"
        for ref in report["launches"]:
            value = common.strict_json(payloads[ref["path"]])
            lane["launches"][ref["index"]] = summarize_launch(value, ref, label, rows.get(ref["index"]), trace_complete)
        raw_trials = [common.strict_json(payloads[ref["path"]]) for ref in report["trials"]]
        for ref, value in zip(report["trials"], raw_trials):
            lane["trials"][ref["index"]] = summarize_trial(value, ref, label)
        lane["frames"] = dict(allRecordedTasks=frame_summary(raw_trials, api),
                              passedTasks=frame_summary([value for value in raw_trials if value["status"] == "passed"], api),
                              byArm={arm: frame_summary([value for value in raw_trials if value["arm"] == arm], api) for arm in evidence.ARMS})
    archive["attachmentBytes"] = archive_bytes
    return archive, profile, lanes


def summarize(root, expected_source):
    if type(expected_source) is not str or not re.fullmatch(r"[0-9a-f]{40}", expected_source):
        raise ValueError("exact_expected_source_required")
    archive, profile, lanes = load_archive(root, expected_source)
    for lane in lanes:
        lane["startupGroups"] = {}
        for mode in ("cold", "hot"):
            launches = [value for value in lane["launches"] if value["startupType"] == mode]
            lane["startupGroups"][mode] = dict(allOrders=startup_group(launches, profile),
                byOrder={order: startup_group([value for value in launches if value["order"] == order], profile) for order in ("AB", "BA")})
        lane["resourceGroups"] = dict(allOrders=resource_group(lane["trials"]),
            byOrder={order: resource_group([value for value in lane["trials"] if value["order"] == order])
                     for order in evidence.ORDERS[:evidence.PROFILES[profile][1]]})
    launches = [value for lane in lanes for value in lane["launches"]]
    trials = [value for lane in lanes for value in lane["trials"]]
    complete = all(lane.get("diagnosticStatus") == "passed" for lane in lanes) and all(value["eligible"] for value in (*launches, *trials))
    originals_success = archive["sourceRun"]["conclusion"] == "success" and all(lane["jobConclusion"] == "success" for lane in lanes)
    acceptance = {}
    for field, prefix, per_repetition in (("manualActionsAccepted", "ManualActionsAccepted", 6), ("modelAcceptedWrites", "ModelAcceptedWrites", 3)):
        known = [value[field] for value in trials if value.get(field) is not None and value[field] >= 0]
        acceptance.update({"expected" + prefix: 8 * evidence.PROFILES[profile][1] * per_repetition,
                           "verified" + prefix: sum(value[field] for value in trials if value["eligible"]),
                           "observed" + prefix: sum(known), "unknown" + prefix + "TrialCount": len(trials) - len(known)})
    return dict(schemaVersion=1, kind="app-experience-summary", diagnosticOnly=True, budgetAcceptance=False,
                fullSuiteAcceptance=False, repository=REPOSITORY, sourceCommit=expected_source, experienceProfile=profile,
                status="complete" if complete else "incomplete", formalDiagnosticComplete=profile == "formal" and complete and originals_success,
                originalConclusionsSuccessful=originals_success, expectedLaneCount=8,
                passedDiagnosticLaneCount=sum(lane.get("diagnosticStatus") == "passed" for lane in lanes),
                expectedLaunchCount=len(launches), recordedLaunchCount=sum(v["recorded"] for v in launches),
                validTtidCount=sum(v["eligible"] for v in launches), launchCounts=counts(launches),
                expectedResourceTrialCount=len(trials), recordedResourceTrialCount=sum(v["recorded"] for v in trials),
                resourceTrialCounts=counts(trials), **acceptance,
                counterSemantics=dict(manualActionsAccepted="Successful platform-action returns in A/B; C=0.",
                                      modelAcceptedWrites="Local-model acknowledged writes in C; A/B=0; separate from attempts and final UI verification."),
                tool={Path(module.__file__).stem: dict(path="scripts/" + Path(module.__file__).name,
                                           sha256=hashlib.sha256(Path(module.__file__).read_bytes()).hexdigest())
                      for module in (sys.modules[__name__], evidence, common, trace)},
                limitations=LIMITATIONS, archive=archive, lanes=lanes,
                documentNumbers=dict(profile=profile, complete=complete, formalDiagnosticComplete=profile == "formal" and complete and originals_success,
                    launches=f"{sum(v['eligible'] for v in launches)}/{len(launches)} valid TTID",
                    resourceTasks=f"{sum(v['eligible'] for v in trials)}/{len(trials)} passed tasks",
                    apis=[dict(matrixLabel=lane["matrixLabel"], diagnosticStatus=lane.get("diagnosticStatus"),
                               validTtidCount=sum(v["eligible"] for v in lane["launches"]),
                               passedResourceTaskCount=sum(v["eligible"] for v in lane["trials"])) for lane in lanes]))


def write_output(path, root, content):
    path, root = checked_path(path), checked_path(root)
    if path == root or root in path.parents:
        raise ValueError("output_must_not_modify_input_evidence")
    if path.exists():
        raise ValueError("output_already_exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as stream:
        stream.write(content)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="One eight-API pilot or formal archive")
    parser.add_argument("--source", required=True, help="Exact expected tested commit")
    parser.add_argument("--output", type=Path, help="New JSON outside input evidence; default stdout")
    args = parser.parse_args(argv)
    try:
        result = summarize(args.archive, args.source)
        content = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        if args.output:
            write_output(args.output, args.archive, content)
        else:
            sys.stdout.write(content)
        return 0 if result["status"] == "complete" else 1
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(f"App experience summary rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
