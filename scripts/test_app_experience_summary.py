"""Independent synthetic fixtures test statistics and evidence loss, not devices."""

import contextlib
import copy
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import collect_app_experience_evidence as evidence
import experience_trace as trace
import summarize_app_experience as summary

SHA = "a" * 40
SOURCE = dict(repository="ecojust/Agent-OS", commit=SHA, runId=123, attempt=2)
ARMS = ("A_SERVICE_OFF", "B_SERVICE_ON_IDLE", "C_SERVICE_ON_AGENT")
ORDERS = ("ABC", "BCA", "CAB", "ACB", "CBA", "BAC")
M = 1_000_000
N = 1_000_000_000


def encoded(value):
    return (json.dumps(value, separators=(",", ":")) + "\n").encode()


def ref(path):
    data = path.read_bytes()
    return dict(path=path.name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def save(path, value):
    path.write_bytes(encoded(value))
    return ref(path)


def uid(number):
    return f"00000000-0000-4000-8000-{number:012d}"


def probe(number, now):
    result = dict(schemaVersion=1, clockDomain="CLOCK_MONOTONIC_System.nanoTime", pid=200 + number,
                  uid=10001, processInstance=uid(number), activityInstance=uid(number + 1000),
                  refreshHz=60.0, modeHz=60.0, displayId=0, editorText="", savedText="Saved: empty",
                  activityPresent=True, resumed=True, stopped=False, windowFocused=True,
                  hardwareAccelerated=True, drawFrameObserved=True, drawMatchAmbiguous=False,
                  finalState=False, finalDrawObserved=False, mutationVersion=0, drawVersion=0,
                  drawVsyncNs=now + 9 * M, drawNanoTime=now + 10 * M, activityFrameCount=1,
                  activityDroppedReports=0, lastFrameVsyncNs=now + 9 * M,
                  lastFrameCallbackNanoTime=now + 11 * M, displayChangeCount=0,
                  clicks=0, editCount=0, saveCount=0, incrementCount=0,
                  drawTimeMillis=(now + 9 * M) // M, displayObservedNanoTime=now, unresolvedDrawCount=0,
                  drawMatchMethod="post_OnDraw_View.getDrawingTime_equals_floor_FrameMetrics.VSYNC_TIMESTAMP_ms")
    for name in ("create", "start", "resume", "pause", "stop", "destroy", "newIntent"):
        result[name + "Count"] = int(name in ("create", "start", "resume"))
    for name in ("created", "started", "resumed", "paused", "stopped", "destroyed", "newIntent"):
        result[name + "NanoTime"] = now if name in ("created", "started", "resumed") else 0
    return result


def state(at, arm, focused=True):
    return dict(checkedNanos=at, servicePresent=arm != ARMS[0], serviceConnected=arm != ARMS[0],
                controllerRunning=False, controlAvailable=False, fixtureFocused=focused)


def launch(index, profile):
    pair_count = 2 if profile == "pilot" else 100
    pair = index % (pair_count * 2) // 2
    arm = ARMS[index % 2 ^ pair % 2]
    hot = index >= pair_count * 2
    start = N + index * 5 * N
    after = probe(pair_count * 2 if hot else index + 1, start + N + M)
    before = None
    if hot:
        before = copy.deepcopy(after)
        before.update(stopped=True, resumed=False, windowFocused=False,
                      resumeCount=index - pair_count * 2 + 1,
                      drawVsyncNs=start + N - M, drawTimeMillis=(start + N - M) // M)
        after["resumeCount"] = before["resumeCount"] + 1
    return dict(schemaVersion=1, kind="agent-app-experience-launch", index=index, pairIndex=pair,
                position=index % 2, arm=arm, startupType="hot" if hot else "cold", status="passed",
                startedNanos=start, endedNanos=start + 4 * N, marker=f"aos.experience.launch.{index}",
                driverPid=420, expectedPid=after["pid"], expectedStartupType="hot" if hot else "cold",
                preLaunchPidAbsent=not hot, homeStoppedVerified=hot, lifecycleVerified=True,
                newDrawVerified=True, stateBefore=state(start + 500 * M, arm, False),
                stateAfter=state(start + 3500 * M, arm), beforeLaunch=before, afterLaunch=after,
                launchStartedElapsedNanos=start + N, launchEndedElapsedNanos=start + 3 * N,
                launchStartedNanoTime=start + N, launchEndedNanoTime=start + 3 * N,
                amStartOutput="Status: ok\nTotalTime: 0\nWaitTime: 12\n", unavailableReason="")


def trial(index, profile, api):
    repetition, position = divmod(index, 3)
    arm_index = ord(ORDERS[repetition][position]) - ord("A")
    arm = ARMS[arm_index]
    refresh = 120.0 if arm_index == 1 else 60.0
    start = N + (8 if profile == "pilot" else 400) * 5 * N + index * 6 * N
    cpu = 1000 + index * 1000
    before = probe(500 + index, start + N)
    before.update(sessionId=uid(2000 + index), ended=False, finalFrameObserved=False, drainComplete=False,
                  drainTimedOut=False, displayStable=False, beforePssKb=15000 + 100 * arm_index,
                  afterPssKb=-1, beginCpuMillis=100, endCpuMillis=0, beginNanoTime=start + 2100 * M,
                  endNanoTime=0, beginMutationVersion=0, finalMutationVersion=0, finalDrawVersion=0,
                  finalDrawVsyncNs=0, frameCutoffVsyncNs=0, frameCount=0, frameOverflow=0,
                  droppedReports=0, lateFrameReports=0, lateDroppedReports=0,
                  beginDisplayChangeCount=0, endDisplayChangeCount=0,
                  beginDisplayObservedNanoTime=start + N, endDisplayObservedNanoTime=0,
                  beginUnresolvedDrawCount=0, unresolvedTaskDraws=0,
                  beginRefreshHz=refresh, beginModeHz=refresh, endRefreshHz=refresh, endModeHz=refresh,
                  beforePssStartedNanoTime=start + 2080 * M, beforePssEndedNanoTime=start + 2090 * M,
                  beforeDalvikPssKb=7000, beforeNativePssKb=4000, beforeOtherPssKb=4000 + 100 * arm_index,
                  beforeGraphicsSummaryKb=1000)
    after = copy.deepcopy(before)
    wall = 500 + arm_index * 100 + repetition * 10
    after.update(ended=True, displayStable=True, finalFrameObserved=True, drainComplete=True,
                 endCpuMillis=100 + 10 + 20 * arm_index + repetition, endNanoTime=before["beginNanoTime"] + wall * M,
                 afterPssKb=before["beforePssKb"] + 20 + 10 * arm_index, frameCount=3,
                 finalMutationVersion=3, finalDrawVersion=3, finalDrawVsyncNs=start + 2500 * M,
                 frameCutoffVsyncNs=start + 2500 * M, mutationVersion=3, drawVersion=3,
                 drawVsyncNs=start + 2500 * M, drawNanoTime=start + 2501 * M, drawTimeMillis=(start + 2500 * M) // M,
                 afterPssStartedNanoTime=start + 3000 * M, afterPssEndedNanoTime=start + 3010 * M,
                 afterDalvikPssKb=7000, afterNativePssKb=4000,
                 afterOtherPssKb=4020 + 110 * arm_index, afterGraphicsSummaryKb=1200,
                 finalState=True, finalDrawObserved=True, editorText="Controller integration sample",
                 savedText="Saved: Controller integration sample", clicks=1, editCount=1, saveCount=1, incrementCount=1,
                 endDisplayObservedNanoTime=start + 2900 * M)
    frames = []
    for i, duration in enumerate((100, 10, 20)):
        at = start + (2300 + i * 100) * M
        frames.append(dict(index=i, intendedVsyncNs=at, vsyncNs=at, totalDurationNs=duration * M,
                           firstDraw=int(i == 0), gpuDurationNs=-1 if api == 30 else 2 * M,
                           deadlineNs=-1 if api == 30 else (16 * M if i == 1 else 8 * M),
                           callbackNanoTime=at + 120 * M, droppedReports=0, refreshHz=refresh, modeHz=refresh,
                           displayId=0, drawVersion=i + 1, drawNanoTime=at + M, crossesBegin=False,
                           refreshObservedNanoTime=start + 2200 * M))
    def sample(at, cpu_value, pss):
        return dict(elapsedRealtimeNanos=at, processCpuMillis=cpu_value, totalPssKiB=pss,
                    dalvikPssKiB=10000, nativePssKiB=5000, otherPssKiB=pss - 15000, collectionNanos=M)
    agent_delta = 5 + 10 * arm_index + 2 * repetition
    return dict(schemaVersion=1, kind="agent-app-experience-trial", index=index, repetition=repetition,
                position=position, arm=arm, status="passed", startedNanos=start, endedNanos=start + 5 * N,
                sessionId=before["sessionId"], initialStateVerified=True, editVerified=True,
                savedTextVerified=True, counterVerified=True, drawingVerified=True, cleanupCompleted=True,
                stateBefore=state(start + 2000 * M, arm), stateAfter=state(start + 4000 * M, arm),
                agentBeforeSample=sample(start + 2010 * M, cpu, 20000 + 200 * arm_index),
                agentAfterSample=sample(start + 3300 * M, cpu + 100, 20050 + 220 * arm_index),
                agentTaskStartedNanos=start + 2200 * M, agentTaskEndedNanos=start + 3200 * M,
                agentTaskStartedCpuMillis=cpu + 10, agentTaskEndedCpuMillis=cpu + 10 + agent_delta,
                targetBefore=before, targetAfter=after, frames=frames,
                framePageChecks=[dict(offset=0, nextOffset=3, totalFrames=3, drainComplete=True,
                                      lateFrameReports=0, lateDroppedReports=0)],
                manualActionsAttempted=0 if arm_index == 2 else 3, manualActionsAccepted=0 if arm_index == 2 else 3,
                modelRequests=4 if arm_index == 2 else 0, modelAcceptedWrites=3 if arm_index == 2 else 0,
                approvalCount=3 if arm_index == 2 else 0, resultKind="MODEL_REPORT" if arm_index == 2 else "",
                resultCode="MODEL_REPORT" if arm_index == 2 else "")


def trace_files(directory, report, launches, mutate=None, trace_status="collected"):
    raw_trace = b"SYNTHETIC SUMMARY UNIT TEST DATA; NOT DEVICE EVIDENCE\n"
    (directory / "trace.pftrace.gz").write_bytes(gzip.compress(raw_trace, mtime=0))
    save(directory / "trace-capture.json", dict(schemaVersion=1, kind="agent-os-experience-trace-capture",
        status=trace_status, pid=900, stopped=True, ready=True, startMode="--background-wait",
        config=trace.trace_config(), error=None if trace_status == "collected" else "synthetic_stop_failure",
        trace=dict(path="trace.pftrace", bytes=len(raw_trace), sha256=hashlib.sha256(raw_trace).hexdigest()),
        compressed=ref(directory / "trace.pftrace.gz")))
    raw = dict(markers=[], startups=[], stats=[], traceStartNanos=1, traceEndNanos=10 ** 15)
    for value in launches:
        index, pid, start = value["index"], value["expectedPid"], value["startedNanos"] + N
        pair = value["pairIndex"]
        ttid_ms = (100 + pair if value["arm"] == ARMS[0] else 400 - pair) + (500 if value["startupType"] == "hot" else 0)
        startup = start + 100 * M
        raw["markers"].append(dict(name=value["marker"], ts=start, dur=2 * N, pid=420))
        raw["startups"].append(dict(startupId=index, ts=startup, tsEnd=start + 1500 * M,
            startupType=value["startupType"], processCount=1, startupPid=pid, startupUpid=pid,
            ttid=ttid_ms * M, ttidFrameId=index, ttidUpid=pid, ttidPid=pid, drawFrameId=1000 + index,
            drawTs=startup + ttid_ms * M - 20 * M, drawDur=20 * M,
            uiTs=startup + 10 * M, uiDur=20 * M, uiPid=pid, uiTid=pid, renderPid=pid, renderThreadName="RenderThread"))
    if mutate:
        mutate(raw)
    stream = io.StringIO(); writer = csv.writer(stream)
    writer.writerow(["result"]); writer.writerow([encoded(raw).hex().upper()])
    (directory / "startup-ttid.csv").write_bytes(stream.getvalue().encode())
    rows = trace.derive_measurements(raw, report)
    save(directory / "startup-ttid.json", dict(schemaVersion=1, kind=trace.KIND,
        diagnosticOnly=True, budgetAcceptance=False, status="passed" if rows and all(v["status"] == "passed" for v in rows) else "failed",
        trace=trace.trace_identity(directory / "trace.pftrace.gz"), package="dev.agentos.fixture", launches=rows,
        traceErrors=raw["stats"], processor=dict(version=trace.VERSION, sha256=trace.PROCESSOR_SHA256, url=trace.PROCESSOR_URL),
        extraction=dict(ref(directory / "startup-ttid.csv"), sqlSha256=hashlib.sha256(trace.extraction_sql().encode()).hexdigest())))


def write_archive(parent, profile="pilot", alter=None, trace_change=None, with_trace=True, trace_status="collected", conclusion="success", exit_code=0):
    root = Path(parent) / "archive"; root.mkdir()
    records = []
    for label in ("30", "31", "32", "33", "34", "35", "36", "37.0"):
        api = int(label.split('.')[0]); directory = root / "app-experience" / ("api-" + label)
        directory.mkdir(parents=True)
        launches = [launch(i, profile) for i in range(8 if profile == "pilot" else 400)]
        trials = [trial(i, profile, api) for i in range(3 if profile == "pilot" else 18)]
        report = dict(schemaVersion=1, kind="agent-app-experience-diagnostics", diagnosticOnly=True,
            budgetAcceptance=False, profile=profile, status="passed", appProcessPid=420, targetPackage="dev.agentos.fixture",
            device=dict(sdkInt=api, manufacturer="synthetic", model="summary-test", hardware="synthetic",
                        fingerprint="synthetic-fixture", emulatorHeuristic=True), conditions=evidence.conditions(profile),
            startedNanos=N, endedNanos=trials[-1]["endedNanos"] + 1,
            cleanup=dict(controllerCleared=True, servicesRestored=True, enabledRestored=True, automationFlagsRestored=True),
            launches=[], trials=[])
        if alter:
            alter(report, launches, trials)
        for kind, values in (("launches", launches), ("trials", trials)):
            for value in values:
                name = f"{'launch' if kind == 'launches' else 'trial'}-{value['index']:03d}.json"
                shard = save(directory / name, value)
                shard.update({key: value[key] for key in ("index", "status", "startedNanos", "endedNanos")})
                if kind == "launches":
                    shard.update({key: value[key] for key in ("marker", "expectedPid", "expectedStartupType", "driverPid")})
                report[kind].append(shard)
        report_ref = save(directory / evidence.REPORT_NAME, report)
        if with_trace:
            trace_files(directory, report, launches, trace_change, trace_status)
        payloads = {path.name: path.read_bytes() for path in directory.iterdir()}
        capture = evidence.empty_capture(label, SOURCE, profile)
        capture.update(actualSdkInt=api, gradleExitCode=exit_code, report=dict(report_ref, status=report["status"]),
            payloads=[ref(path) for path in sorted(directory.iterdir())], captureStatus=evidence.capture_status(report, payloads),
            diagnosticStatus=evidence.diagnostic_status(report, payloads, exit_code), emulatorStepOutcome=conclusion)
        capture["captureError"] = None if capture["captureStatus"] == "collected" else "startup_trace_derivation_unavailable"
        save(directory / "capture.json", capture)
        records.append(dict(matrixLabel=label, apiLevel=api, profile=profile, jobConclusion=conclusion,
            captureStatus=capture["captureStatus"], captureError=capture["captureError"], diagnosticStatus=capture["diagnosticStatus"],
            reportStatus=report["status"], actualSdkInt=api, gradleExitCode=exit_code,
            declaredCiProfile=capture["declaredCiProfile"], artifactId=1000 + api,
            files=[ref(path) for path in sorted(directory.iterdir())]))
    save(root / "manifest.json", dict(schemaVersion=1, kind="android-emulator-test-evidence",
        executionScope="app-experience-pilot" if profile == "pilot" else "app-experience-diagnostic",
        fullSuiteAcceptance=False, expectedMatrixLabels=["30", "31", "32", "33", "34", "35", "36", "37.0"],
        sourceRun=dict(SOURCE, branch="master", conclusion=conclusion,
                       url="https://github.com/ecojust/Agent-OS/actions/runs/123"),
        appExperience=dict(kind="app-experience-attachments", diagnosticOnly=True, budgetAcceptance=False, profile=profile, apis=records)))
    return root


def edit_manifest(root, alter):
    path = root / "manifest.json"; value = json.loads(path.read_text()); alter(value); save(path, value)


def rewrite_payload(root, name, alter, label="30", update_inner=False):
    directory = root / "app-experience" / ("api-" + label)
    path = directory / name; value = json.loads(path.read_text()); alter(value); save(path, value)
    if update_inner:
        capture_path = directory / "capture.json"; capture = json.loads(capture_path.read_text())
        capture["payloads"] = [ref(path) if item["path"] == name else item for item in capture["payloads"]]
        save(capture_path, capture)
    edit_manifest(root, lambda manifest: manifest["appExperience"]["apis"][0].update(
        files=[ref(path) for path in sorted(directory.iterdir())]))


class AppExperienceSummaryTests(unittest.TestCase):
    def test_pilot_counts_arithmetic_and_no_formal_p95(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary), SHA)
        self.assertEqual("complete", value["status"])
        self.assertFalse(value["formalDiagnosticComplete"])
        self.assertEqual((64, 64, 24), (value["expectedLaunchCount"], value["validTtidCount"], value["expectedResourceTrialCount"]))
        self.assertEqual((48, 24), (value["verifiedManualActionsAccepted"], value["verifiedModelAcceptedWrites"]))
        group = value["lanes"][0]["startupGroups"]["cold"]["allOrders"]
        self.assertEqual(100.5, group["modes"][ARMS[0]]["ttidMillis"]["median"])
        self.assertIsNone(group["modes"][ARMS[0]]["ttidMillis"]["p95"])
        trial_value = value["lanes"][0]["trials"][0]
        self.assertEqual((10, 500, 2), tuple(trial_value["metrics"][key] for key in ("targetCpuDeltaMillis", "targetWallMillis", "targetOneCorePercent")))
        self.assertEqual((5, 1000, 0.5), tuple(trial_value["metrics"][key] for key in ("agentCpuDeltaMillis", "agentWallMillis", "agentOneCorePercent")))
        self.assertEqual(35000, trial_value["metrics"]["twoProcessBeforePssSumKiB"])
        self.assertEqual(70, trial_value["metrics"]["twoProcessPssSumChangeKiB"])
        self.assertEqual("sum_of_endpoints_at_different_times", trial_value["twoProcessPssSampling"])
        self.assertEqual(1000, trial_value["targetBefore"]["beforeGraphicsSummaryKb"])

    def test_formal_nearest_rank_group_quantiles_are_not_pair_quantiles(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, profile="formal"), SHA)
        self.assertTrue(value["formalDiagnosticComplete"])
        self.assertEqual((3200, 144), (value["expectedLaunchCount"], value["expectedResourceTrialCount"]))
        self.assertEqual((288, 144), (value["expectedManualActionsAccepted"], value["expectedModelAcceptedWrites"]))
        for lane in value["lanes"]:
            for mode in ("cold", "hot"):
                groups = lane["startupGroups"][mode]; main = groups["allOrders"]
                offset = 500 if mode == "hot" else 0
                self.assertEqual(194 + offset, main["modes"][ARMS[0]]["ttidMillis"]["p95"])
                self.assertEqual(395 + offset, main["modes"][ARMS[1]]["ttidMillis"]["p95"])
                self.assertEqual(dict(median=201, p95=201), main["groupQuantileDifferenceBMinusAMillis"])
                self.assertEqual((100, 102, 201, 300), tuple(main["pairedBMinusAMillis"][key] for key in ("count", "min", "median", "max")))
                self.assertNotIn("p95", main["pairedBMinusAMillis"])
                for order in ("AB", "BA"):
                    self.assertEqual(50, groups["byOrder"][order]["modes"][ARMS[0]]["ttidMillis"]["count"])
                    self.assertIsNone(groups["byOrder"][order]["groupQuantileDifferenceBMinusAMillis"]["p95"])
            resources = lane["resourceGroups"]["allOrders"]
            self.assertEqual(6, resources["modes"][ARMS[0]]["metrics"]["targetCpuDeltaMillis"]["count"])
            self.assertEqual(20, resources["paired"]["BMinusA"]["metrics"]["targetCpuDeltaMillis"]["median"])
            self.assertEqual(40, resources["paired"]["CMinusA"]["metrics"]["targetCpuDeltaMillis"]["median"])
            self.assertNotIn("targetOneCorePercent", resources["paired"]["BMinusA"]["metrics"])
            self.assertEqual(list(ORDERS), list(lane["resourceGroups"]["byOrder"]))

    def test_one_invalid_formal_sample_removes_only_its_group_p95_not_denominator(self):
        def missing(raw):
            raw["startups"][0].update(ttid=None, ttidFrameId=None, drawFrameId=None)
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, profile="formal", trace_change=missing), SHA)
        self.assertEqual("incomplete", value["status"])
        self.assertEqual(3192, value["validTtidCount"])
        group = value["lanes"][0]["startupGroups"]["cold"]["allOrders"]
        self.assertEqual((100, 99, None), tuple(group["modes"][ARMS[0]]["ttidMillis"][key] for key in ("expectedCount", "count", "p95")))
        self.assertEqual(395, group["modes"][ARMS[1]]["ttidMillis"]["p95"])
        self.assertEqual(99, group["pairedBMinusAMillis"]["count"])
        self.assertEqual("missing_or_incomplete_frame", value["lanes"][0]["launches"][0]["reason"])

    def test_frame_first_draw_deadline_and_api30_nominal_are_separate(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary), SHA)
        frame30 = value["lanes"][0]["frames"]["allRecordedTasks"]
        frame31 = value["lanes"][1]["frames"]["allRecordedTasks"]
        self.assertEqual((9, 3, 6), tuple(frame30[key] for key in ("receivedReportCount", "firstDrawReportCount", "nonFirstReportCount")))
        self.assertEqual(15, frame30["nonFirstTotalDurationMillis"]["median"])
        self.assertEqual(100, frame30["firstDrawTotalDurationMillis"]["median"])
        self.assertEqual(0, frame30["nonFirstTotalMinusDeadlineMillis"]["comparableReportCount"])
        self.assertEqual(0, frame30["nonFirstGpuDurationMillis"]["count"])
        self.assertEqual(2 / 3, frame30["api30NominalRefreshPeriodComparison"]["exceededFraction"])
        self.assertEqual(0.5, frame31["nonFirstTotalMinusDeadlineMillis"]["exceededFraction"])
        self.assertEqual(3, frame31["nonFirstTotalMinusDeadlineMillis"]["differenceMillis"]["median"])
        self.assertEqual([60.0, 120.0], frame31["refreshSnapshotValuesHz"])
        self.assertEqual(0, frame31["api30NominalRefreshPeriodComparison"]["comparableReportCount"])

    def test_unknown_and_non_positive_deadlines_are_not_comparable_fast_frames(self):
        def alter(report, launches, trials):
            if report["device"]["sdkInt"] == 30:
                return
            # Unknown first draws are excluded before the non-first reasons.
            for trial_value in trials:
                frame = trial_value["frames"][0]
                frame["deadlineNs"] = evidence.LONG_MAX - frame["intendedVsyncNs"]
            unknown = trials[0]["frames"][1]
            unknown["deadlineNs"] = evidence.LONG_MAX - unknown["intendedVsyncNs"]
            trials[1]["frames"][1]["deadlineNs"] = 0
            trials[1]["frames"][2]["deadlineNs"] = -1
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, alter=alter), SHA)
        for lane in value["lanes"][1:]:
            frames = lane["frames"]["allRecordedTasks"]
            comparison = frames["nonFirstTotalMinusDeadlineMillis"]
            self.assertEqual((6, 3, 3, 2), tuple(comparison[key] for key in
                ("nonFirstReportCount", "comparableReportCount", "unavailableReportCount", "exceededReportCount")))
            self.assertEqual(2 / 3, comparison["exceededFraction"])
            self.assertEqual(12, comparison["differenceMillis"]["median"])
            self.assertEqual(dict(unsupported_api_30=0, platform_unavailable=1,
                                  non_positive_budget=1, unknown_deadline_sentinel=1), comparison["unavailableReasonCounts"])
            self.assertEqual(9, frames["receivedReportCount"])
        api30 = value["lanes"][0]["frames"]["allRecordedTasks"]["nonFirstTotalMinusDeadlineMillis"]
        self.assertEqual(6, api30["unavailableReasonCounts"]["unsupported_api_30"])
        self.assertEqual(0, api30["comparableReportCount"])

    def test_no_known_deadlines_has_no_exceedance_ratio_or_zero_substitution(self):
        def alter(report, launches, trials):
            if report["device"]["sdkInt"] == 30:
                return
            for trial_value in trials:
                for frame in trial_value["frames"]:
                    frame["deadlineNs"] = evidence.LONG_MAX - frame["intendedVsyncNs"]
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, alter=alter), SHA)
        comparison = value["lanes"][1]["frames"]["allRecordedTasks"]["nonFirstTotalMinusDeadlineMillis"]
        self.assertEqual((6, 0, 6), tuple(comparison[key] for key in
            ("nonFirstReportCount", "comparableReportCount", "unavailableReportCount")))
        self.assertEqual(6, comparison["unavailableReasonCounts"]["unknown_deadline_sentinel"])
        self.assertIsNone(comparison["exceededFraction"])
        self.assertIsNone(comparison["differenceMillis"]["median"])
        self.assertEqual(6, comparison["differenceMillis"]["missingCount"])

    def test_zero_cpu_is_valid_and_not_replaced_by_sample_cpu(self):
        def alter(report, launches, trials):
            trials[0]["targetAfter"]["endCpuMillis"] = trials[0]["targetAfter"]["beginCpuMillis"]
            trials[0]["agentTaskEndedCpuMillis"] = trials[0]["agentTaskStartedCpuMillis"]
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, alter=alter), SHA)
        measured = value["lanes"][0]["trials"][0]["metrics"]
        self.assertEqual((0, 0, 0, 0), tuple(measured[key] for key in ("targetCpuDeltaMillis", "targetOneCorePercent", "agentCpuDeltaMillis", "agentOneCorePercent")))

    def test_failed_and_incomplete_prefix_keep_different_suffixes(self):
        for status in ("failed", "incomplete"):
            def prefix(report, launches, trials):
                report["status"] = status
                report["endedNanos"] = -1 if status == "incomplete" else launches[2]["endedNanos"] + 1
                del launches[3:]; trials.clear()
            with self.subTest(status=status), tempfile.TemporaryDirectory() as temporary:
                value = summary.summarize(write_archive(temporary, alter=prefix), SHA)
            self.assertEqual((64, 24, 24), (value["expectedLaunchCount"], value["recordedLaunchCount"], value["expectedResourceTrialCount"]))
            self.assertEqual(40, value["launchCounts"]["not_executed" if status == "failed" else "unavailable"])
            self.assertEqual(24, value["resourceTrialCounts"]["not_executed" if status == "failed" else "unavailable"])
            self.assertEqual(1, value["lanes"][0]["startupGroups"]["cold"]["allOrders"]["pairedBMinusAMillis"]["count"])

    def test_failed_resource_keeps_measurements_frames_and_only_complete_pairs(self):
        def alter(report, launches, trials):
            report["status"] = "failed"; trials[-1]["status"] = "failed"
            trials[-1]["targetAfter"].update(droppedReports=4, frameOverflow=2, lateFrameReports=1, drainComplete=False)
            trials[-1]["framePageChecks"][0].update(lateFrameReports=2, drainComplete=False)
            trials[-1]["frames"][-1]["droppedReports"] = 4
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, alter=alter), SHA)
        self.assertEqual(8, value["resourceTrialCounts"]["failed"])
        self.assertEqual(24, value["observedModelAcceptedWrites"])
        self.assertEqual(0, value["verifiedModelAcceptedWrites"])
        lane = value["lanes"][0]; group = lane["resourceGroups"]["allOrders"]
        self.assertEqual(50, lane["trials"][2]["metrics"]["targetCpuDeltaMillis"])
        self.assertEqual(1, group["paired"]["BMinusA"]["eligiblePairCount"])
        self.assertEqual(0, group["paired"]["CMinusA"]["eligiblePairCount"])
        frames = lane["frames"]["allRecordedTasks"]
        self.assertEqual((4, 2, 2), (frames["droppedReportCount"], frames["bufferOverflowCount"], frames["lateReportCount"]))
        self.assertEqual(1, frames["endSnapshotLateReportCount"])
        self.assertEqual(1, frames["pageObservedIncompleteDrainTaskCount"])
        self.assertEqual(6, lane["frames"]["passedTasks"]["receivedReportCount"])

    def test_missing_trace_does_not_substitute_am_start_total_time(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, with_trace=False), SHA)
        self.assertEqual(0, value["validTtidCount"])
        self.assertEqual(64, value["launchCounts"]["unavailable"])
        self.assertEqual(24, value["resourceTrialCounts"]["passed"])
        self.assertIsNone(value["lanes"][0]["launches"][0]["ttidMillis"])
        self.assertIn("TotalTime: 0", value["lanes"][0]["launches"][0]["amStartOutput"])

    def test_after_endpoint_page_failure_is_counted_and_refresh_change_is_uncertain(self):
        def alter(report, launches, trials):
            report["status"] = "failed"; trials[-1]["status"] = "failed"
            trials[-1]["framePageChecks"][0].update(lateFrameReports=3, drainComplete=False)
            trials[-1]["frames"][-1]["refreshHz"] = 90.0
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, alter=alter), SHA)
        frames = value["lanes"][0]["frames"]["allRecordedTasks"]
        self.assertEqual((0, 3, 0, 1), tuple(frames[key] for key in ("endSnapshotLateReportCount", "lateReportCount", "endSnapshotIncompleteDrainTaskCount", "incompleteDrainTaskCount")))
        self.assertEqual(1, frames["refreshAttributionUncertainReportCount"])
        self.assertEqual((6, 5, 1), tuple(frames["api30NominalRefreshPeriodComparison"][key] for key in ("nonFirstReportCount", "comparableReportCount", "unavailableReportCount")))

    def test_late_dropped_reports_use_task_max_without_readding_rows_or_pages(self):
        def alter(report, launches, trials):
            report["status"] = "failed"; value = trials[-1]; value["status"] = "failed"
            value["targetAfter"].update(droppedReports=4, lateDroppedReports=2)
            value["frames"][-1]["droppedReports"] = 4
            value["framePageChecks"][0].update(lateDroppedReports=5)
            value["framePageChecks"].append(dict(offset=3, nextOffset=3, totalFrames=3,
                                                 drainComplete=True, lateFrameReports=0, lateDroppedReports=7))
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, alter=alter), SHA)
        for lane in value["lanes"]:
            frames = lane["frames"]["allRecordedTasks"]
            self.assertEqual((4, 4, 7, 11), tuple(frames[key] for key in
                ("droppedReportCount", "receivedRowDroppedReportCount", "lateDroppedReportCount", "observedDroppedReportCount")))
            self.assertEqual(0, frames["lateReportCount"])
            self.assertEqual(0, frames["lateDroppedReportUnavailableTaskCount"])
            self.assertEqual(0, lane["frames"]["passedTasks"]["observedDroppedReportCount"])

    def test_absent_late_dropped_counter_is_unavailable_not_zero(self):
        value = trial(0, "pilot", 31)
        value["targetAfter"].pop("lateDroppedReports")
        for page in value["framePageChecks"]: page.pop("lateDroppedReports")
        frames = summary.frame_summary([value], 31)
        self.assertIsNone(frames["lateDroppedReportCount"])
        self.assertIsNone(frames["observedDroppedReportCount"])
        self.assertEqual(1, frames["lateDroppedReportUnavailableTaskCount"])
        self.assertEqual(3, frames["receivedReportCount"])

    def test_failure_before_measurements_has_no_fabricated_zero_cpu_or_memory(self):
        def alter(report, launches, trials):
            report["status"] = "failed"; del trials[1:]
            value = trials[0]
            value.update(status="failed", targetBefore=None, targetAfter=None, agentBeforeSample=None, agentAfterSample=None,
                         frames=[], framePageChecks=[], agentTaskStartedNanos=-1, agentTaskEndedNanos=-1,
                         agentTaskStartedCpuMillis=-1, agentTaskEndedCpuMillis=-1,
                         manualActionsAttempted=-1, manualActionsAccepted=-1, modelAcceptedWrites=-1)
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, alter=alter), SHA)
        measured = value["lanes"][0]["trials"][0]
        self.assertTrue(all(metric is None for metric in measured["metrics"].values()))
        self.assertEqual(0, measured["frameSummary"]["receivedReportCount"])
        self.assertEqual(24, value["unknownManualActionsAcceptedTrialCount"])
        self.assertEqual(16, value["resourceTrialCounts"]["not_executed"])

    def test_trace_only_capture_keeps_missing_root_and_planned_denominators(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = write_archive(temporary)
            directory = root / "app-experience/api-30"
            for path in directory.iterdir():
                if path.name not in ("trace-capture.json", "trace.pftrace.gz"): path.unlink()
            capture = evidence.empty_capture("30", SOURCE, "pilot")
            capture.update(captureStatus="unavailable", captureError="committed_report_unavailable",
                           payloads=[ref(path) for path in sorted(directory.iterdir())], gradleExitCode=124)
            save(directory / "capture.json", capture)
            edit_manifest(root, lambda value: value["appExperience"]["apis"][0].update(
                captureStatus="unavailable", captureError="committed_report_unavailable", actualSdkInt=None,
                reportStatus=None, diagnosticStatus="unavailable", gradleExitCode=124,
                files=[ref(path) for path in sorted(directory.iterdir())]))
            value = summary.summarize(root, SHA)
        self.assertEqual((64, 56), (value["expectedLaunchCount"], value["validTtidCount"]))
        self.assertEqual("collected", value["lanes"][0]["traceCapture"]["status"])
        self.assertEqual("report_unavailable", value["lanes"][0]["reason"])
        self.assertNotIn("frames", value["lanes"][0])

    def test_partial_trace_keeps_observed_ttid_without_complete_acceptance(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, trace_status="failed"), SHA)
        self.assertEqual(0, value["validTtidCount"])
        launch_value = value["lanes"][0]["launches"][0]
        self.assertEqual(100, launch_value["observedTtidMillis"])
        self.assertIsNone(launch_value["ttidMillis"])
        self.assertEqual("trace_capture_not_complete", launch_value["reason"])

    def test_original_run_job_and_gradle_failure_are_not_rewritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = summary.summarize(write_archive(temporary, conclusion="failure", exit_code=1), SHA)
        self.assertEqual("failure", value["archive"]["sourceRun"]["conclusion"])
        self.assertEqual(("failure", 1, "failed"), tuple(value["lanes"][0][key] for key in ("jobConclusion", "gradleExitCode", "diagnosticStatus")))
        self.assertEqual((64, 24), (value["validTtidCount"], value["resourceTrialCounts"]["passed"]))
        self.assertFalse(value["formalDiagnosticComplete"])

    def test_missing_artifact_preserves_entire_lane_denominator(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = write_archive(temporary)
            directory = root / "app-experience/api-30"
            for path in directory.iterdir(): path.unlink()
            edit_manifest(root, lambda value: value["appExperience"]["apis"][0].update(files=[], captureStatus="artifact_unavailable", diagnosticStatus=None, reportStatus=None))
            value = summary.summarize(root, SHA)
        self.assertEqual((64, 56, 24), (value["expectedLaunchCount"], value["validTtidCount"], value["expectedResourceTrialCount"]))
        self.assertEqual(8, value["launchCounts"]["unavailable"])
        self.assertEqual(3, value["resourceTrialCounts"]["unavailable"])

    def test_source_profile_api_order_and_manifest_scope_are_strict(self):
        mutations = [lambda v: v["sourceRun"].update(commit="b" * 40),
                     lambda v: v["sourceRun"].update(runId=True),
                     lambda v: v.update(executionScope="full-suite"),
                     lambda v: v.update(fullSuiteAcceptance=True),
                     lambda v: v["appExperience"].update(profile="formal"),
                     lambda v: v["appExperience"]["apis"].reverse(),
                     lambda v: v["appExperience"]["apis"][0].update(apiLevel=True),
                     lambda v: v["appExperience"]["apis"][0].update(gradleExitCode=True)]
        with tempfile.TemporaryDirectory() as temporary:
            root = write_archive(temporary); original = (root / "manifest.json").read_bytes()
            for mutation in mutations:
                with self.subTest(mutation=mutation):
                    (root / "manifest.json").write_bytes(original); edit_manifest(root, mutation)
                    with self.assertRaises(ValueError): summary.summarize(root, SHA)

    def test_capture_payload_and_inner_shard_hash_chains(self):
        for update_inner in (False, True):
            with self.subTest(update_inner=update_inner), tempfile.TemporaryDirectory() as temporary:
                root = write_archive(temporary)
                rewrite_payload(root, "trial-000.json", lambda value: value.update(manualActionsAccepted=2), update_inner=update_inner)
                with self.assertRaises(ValueError): summary.summarize(root, SHA)

    def test_rehashed_derived_ttid_must_match_archived_csv(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = write_archive(temporary)
            rewrite_payload(root, "startup-ttid.json", lambda value: value["launches"][0].update(ttidNanos=1), update_inner=True)
            with self.assertRaisesRegex(ValueError, "recompute"): summary.summarize(root, SHA)

    def test_extra_duplicate_missing_traversal_and_symlink_paths_reject(self):
        for kind in ("extra", "duplicate", "missing", "traversal", "symlink"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                root = write_archive(temporary); directory = root / "app-experience/api-30"
                if kind == "extra": (directory / "extra.json").write_text("{}")
                elif kind == "duplicate": edit_manifest(root, lambda v: v["appExperience"]["apis"][0]["files"].append(v["appExperience"]["apis"][0]["files"][0]))
                elif kind == "missing": (directory / "launch-000.json").unlink()
                elif kind == "traversal": edit_manifest(root, lambda v: v["appExperience"]["apis"][0]["files"][0].update(path="../capture.json"))
                else:
                    path = directory / "launch-000.json"; data = path.read_bytes(); path.unlink()
                    other = Path(temporary) / "other.json"; other.write_bytes(data); path.symlink_to(other)
                with self.assertRaises((ValueError, OSError)): summary.summarize(root, SHA)

    def test_duplicate_json_and_nan_reject(self):
        for data in (b'{"schemaVersion":1,"schemaVersion":1}', b'{"value":NaN}'):
            with tempfile.TemporaryDirectory() as temporary:
                root = write_archive(temporary); (root / "manifest.json").write_bytes(data)
                with self.assertRaises(ValueError): summary.summarize(root, SHA)

    def test_cli_requires_source_new_output_outside_evidence_and_preserves_tool_hashes(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stderr(io.StringIO()):
            root = write_archive(temporary); output = Path(temporary) / "summary.json"
            self.assertEqual(0, summary.main([str(root), "--source", SHA, "--output", str(output)]))
            value = json.loads(output.read_text())
            self.assertEqual(hashlib.sha256(Path(summary.__file__).read_bytes()).hexdigest(), value["tool"]["summarize_app_experience"]["sha256"])
            self.assertEqual(2, summary.main([str(root), "--source", SHA, "--output", str(output)]))
            self.assertEqual(2, summary.main([str(root), "--source", SHA, "--output", str(root / "summary.json")]))
            with self.assertRaises(ValueError): summary.summarize(root, "a" * 39)


if __name__ == "__main__":
    unittest.main()
