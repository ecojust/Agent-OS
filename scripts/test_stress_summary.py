"""Known arithmetic, committed-prefix semantics and segmented archive identities."""

import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import collect_stress_evidence as evidence
import summarize_stress_runs as summary

SHA = "a" * 40
NANO = 1_000_000_000


def segment(index):
    start = (index * 65 + 10) * NANO
    def trace(stage, at, dispatch=0):
        return dict(stage=stage, startNanos=at, durationNanos=1, mainThread=False,
                    nodeCount=-1, snapshotJsonBytes=-1, truncated=False, epoch=1, dispatchAttempts=dispatch)
    active_start, stop_start, recovery_start = start + 2 * NANO, start + 6 * NANO, start + 10 * NANO
    active = dict(status="passed", startedNanos=active_start, endedNanos=start + 5 * NANO,
                  resultKind="MODEL_REPORT", resultCode="MODEL_REPORT", acceptedWrites=3, finalStateObserved=True,
                  counterVerified=True, savedTextVerified=True, traceCapacity=4096, traceOverwrittenEvents=0,
                  trace=[trace("DEVICE_DISPATCH_ATTEMPT", active_start + n * 10, n) for n in (1, 2, 3)])
    stop = dict(status="passed", startedNanos=stop_start, endedNanos=start + 9 * NANO,
                resultKind="STOPPED", resultCode="TASK_STOPPED", acceptedWrites=0,
                approvalReached=True, counterVerified=True, stopEnqueuedNanos=stop_start + 10,
                stopStateAppliedNanos=stop_start + 41, traceCapacity=4096, traceOverwrittenEvents=0,
                trace=[trace("STOP_RECEIVED", stop_start + 20), trace("STOP_EPOCH_INVALIDATED", stop_start + 30),
                       trace("STOP_UI_APPLIED", stop_start + 40)])
    state = dict(servicePresent=False, serviceConnected=False, controllerRunning=False,
                 controlAvailable=False, fixtureFocused=True)
    recovery = dict(status="passed", startedNanos=recovery_start, endedNanos=start + 60 * NANO,
                    stateBefore=dict(state, checkedNanos=recovery_start - 1),
                    stateAfter=dict(state, checkedNanos=start + 60 * NANO + 1),
                    trace=[], traceCapacity=4096, traceOverwrittenEvents=0)
    samples = [dict(elapsedRealtimeNanos=start + (1 + n * 5) * NANO,
                    processCpuMillis=index * 1_000_000 + n * 50 * (index + 1),
                    totalPssKiB=1000 + index * 100 + n * 10, dalvikPssKiB=400,
                    nativePssKiB=100 + n, otherPssKiB=500 + index * 100 + n * 9,
                    collectionNanos=1_000_000, phase="setup" if n == 0 else "stop" if n == 1 else "recovery")
               for n in range(12)]
    return dict(schemaVersion=1, kind="agent-stress-segment", index=index, status="passed",
                startedNanos=start, endedNanos=start + 61 * NANO, samples=samples,
                sampleLimitReached=False, active=active, stop=stop, recovery=recovery)


def save_json(path, value):
    data = (json.dumps(value) + "\n").encode()
    path.write_bytes(data)
    return dict(path=path.name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())


def write_archive(parent, run_id=101, alter=None, conclusion="success", gradle_exit=0):
    root = parent / f"{run_id}-1"
    root.mkdir()
    identity = dict(repository=summary.REPOSITORY, commit=SHA, runId=run_id, attempt=1)
    records = []
    for label in evidence.LABELS:
        api = int(label.split('.')[0])
        directory = root / "segmented-stress" / ("api-" + label)
        directory.mkdir(parents=True)
        values = [segment(index) for index in range(30)]
        report = dict(schemaVersion=1, kind="agent-segmented-stress-diagnostics", diagnosticOnly=True,
                      budgetAcceptance=False, status="passed", appProcessPid=1234,
                      device=dict(sdkInt=api, manufacturer="test", model="sdk", hardware="ranchu",
                                  fingerprint="synthetic-test-fingerprint", emulatorHeuristic=True),
                      conditions=dict(evidence.CONDITIONS), startedNanos=values[0]["startedNanos"] - 1,
                      endedNanos=values[-1]["endedNanos"] + 1,
                      cleanup=dict(controllerCleared=True, servicesRestored=True, enabledRestored=True), segments=[])
        if alter:
            alter(report, values)
        files = []
        for value in values:
            file_ref = save_json(directory / f"segment-{value['index']:03d}.json", value)
            report["segments"].append(dict(file_ref, **{key: value[key] for key in ("index", "status", "startedNanos", "endedNanos")}))
            files.append(file_ref)
        report_ref = save_json(directory / evidence.REPORT_NAME, report)
        capture = evidence.empty_capture(label, identity)
        capture.update(actualSdkInt=api, captureStatus="collected", captureError=None, gradleExitCode=gradle_exit,
                       report=dict(report_ref, status=report["status"]), emulatorStepOutcome=conclusion)
        capture_ref = save_json(directory / "capture.json", capture)
        records.append(dict(apiLevel=api, matrixLabel=label, jobConclusion=conclusion, captureStatus="collected",
                            captureError=None, actualSdkInt=api, diagnosticStatus=report["status"], gradleExitCode=gradle_exit,
                            declaredCiProfile=capture["declaredCiProfile"], artifactId=run_id * 100 + api,
                            files=[capture_ref, report_ref, *files]))
    manifest = dict(schemaVersion=1, kind="android-emulator-test-evidence", executionScope="stress-diagnostic",
                    fullSuiteAcceptance=False,
                    sourceRun=dict(identity, branch="master", conclusion=conclusion,
                                   url=f"https://github.com/{summary.REPOSITORY}/actions/runs/{run_id}"),
                    expectedMatrixLabels=list(evidence.LABELS),
                    segmentedStress=dict(kind="segmented-stress-attachments", diagnosticOnly=True,
                                         budgetAcceptance=False, scope="fixed stress diagnostics", apis=records))
    save_json(root / "manifest.json", manifest)
    return root


def edit_manifest(root, change):
    path = root / 'manifest.json'
    value = json.loads(path.read_text())
    change(value)
    save_json(path, value)


class StressSummaryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.archive = write_archive(self.root)

    def result(self, archive=None):
        return summary.summarize(archive or self.archive, SHA)

    def test_known_resource_arithmetic_and_fixed_denominators(self):
        result = self.result()
        self.assertEqual(("complete", 8, 240, 480, 720, 2880),
                         (result["status"], result["passedDiagnosticLaneCount"], result["expectedSegmentCount"],
                          result["expectedTaskCount"], result["verifiedCompletedTaskAcceptedWrites"], result["resourceSampleCount"]))
        self.assertEqual(dict(passed=240, failed=0, not_executed=0, unavailable=0), result["segmentCounts"])
        self.assertEqual(dict(passed=480, failed=0, not_executed=0, unavailable=0), result["taskCounts"])
        self.assertEqual(720, result["observedAcceptedWrites"])
        self.assertEqual(0, result["unknownAcceptedWriteTaskCount"])
        first = result["lanes"][0]["segments"][0]
        self.assertEqual((61000, 55000, 1.0, 1055, 1110, 10, 1065),
                         (first["wallMillis"], first["resourceSampleSpanMillis"], first["cpuPercentOneCore"],
                          first["sampleMeanTotalPssKiB"], first["sampleMaxTotalPssKiB"],
                          first["recoveryTaggedSampleCount"], first["recoveryTaggedSampleMeanTotalPssKiB"]))
        self.assertEqual(segment(0)["samples"], first["resourceSamples"])
        self.assertEqual("MODEL_REPORT", first["tasks"]["active"]["resultKind"])
        self.assertEqual("TASK_STOPPED", first["tasks"]["stop"]["resultCode"])
        self.assertAlmostEqual(0.000031, first["tasks"]["stop"]["timingsMillis"]["requestToUiApplied"])
        self.assertEqual(dict(count=30, missing=0, min=1, median=15.5, max=30),
                         result["groups"][0]["metrics"]["cpuPercentOneCore"])
        self.assertEqual({"recoveryTaggedSampleMeanTotalPssKiB": 2900,
                          "recoveryTaggedSampleMaxTotalPssKiB": 2900},
                         result["groups"][0]["recoveryFirstToLast"]["differencesKiB"])
        self.assertTrue(result["diagnosticOnly"])
        self.assertFalse(result["budgetAcceptance"])

    def test_failed_stop_keeps_completed_active_task_and_failed_segment(self):
        def failure(report, values):
            del values[2:]
            report['status'] = 'failed'
            values[-1]['status'] = 'failed'
            values[-1]['stop'].update(status='failed', resultKind='FAILED', resultCode='TIMEOUT',
                                      acceptedWrites=-1, approvalReached=False)
            values[-1]['recovery'] = None
        result = self.result(write_archive(self.root, 102, failure, 'failure', 1))
        self.assertEqual('incomplete', result['status'])
        self.assertEqual(dict(passed=8, failed=8, not_executed=224, unavailable=0), result['segmentCounts'])
        self.assertEqual(dict(passed=24, failed=8, not_executed=448, unavailable=0), result['taskCounts'])
        self.assertEqual(48, result['verifiedCompletedTaskAcceptedWrites'])
        self.assertEqual(480, result['expectedTaskCount'])
        self.assertEqual(448 + 8, result['unknownAcceptedWriteTaskCount'])
        self.assertEqual(1, result['groups'][0]['metrics']['cpuPercentOneCore']['count'])
        self.assertFalse(result['groups'][0]['recoveryFirstToLast']['available'])
        self.assertEqual('failure', result['lanes'][0]['sourceRunConclusion'])
        self.assertEqual(1, result['lanes'][0]['gradleExitCode'])

    def test_incomplete_checkpoint_does_not_assert_uncommitted_tasks_never_executed(self):
        def interrupted(report, values):
            del values[2:]
            report.update(status='incomplete', endedNanos=-1,
                          cleanup=dict(controllerCleared=False, servicesRestored=False, enabledRestored=False))
        result = self.result(write_archive(self.root, 102, interrupted, 'cancelled', 143))
        self.assertEqual(dict(passed=16, failed=0, not_executed=0, unavailable=224), result['segmentCounts'])
        self.assertEqual(dict(passed=32, failed=0, not_executed=0, unavailable=448), result['taskCounts'])
        tail = result['lanes'][0]['segments'][2]
        self.assertEqual('not_committed_before_interruption', tail['reason'])
        self.assertEqual('unavailable', tail['tasks']['active']['status'])
        self.assertIsNone(result['lanes'][0]['wallMillis'])
        self.assertEqual('incomplete', result['status'])

    def test_empty_checkpoint_and_interrupted_finalization_keep_report_incomplete(self):
        for length in (0, 30):
            with self.subTest(committed=length):
                def interrupted(report, values):
                    del values[length:]
                    report.update(status='incomplete', endedNanos=-1,
                                  cleanup=dict(controllerCleared=False, servicesRestored=False, enabledRestored=False))
                result = self.result(write_archive(self.root, 102 + length, interrupted, 'cancelled', 143))
                self.assertEqual('incomplete', result['status'])
                self.assertEqual(0, result['passedDiagnosticLaneCount'])
                self.assertEqual(length * 8, result['segmentCounts']['passed'])
                self.assertEqual((30 - length) * 8, result['segmentCounts']['unavailable'])
                self.assertEqual(0, result['segmentCounts']['not_executed'])
                self.assertEqual('cancelled', result['lanes'][0]['emulatorStepOutcome'])

    def test_restore_failure_and_run_failure_cannot_be_changed_by_complete_segments(self):
        def failed_restore(report, values):
            report.update(status='failed', cleanup=dict(controllerCleared=True, servicesRestored=False, enabledRestored=True))
        result = self.result(write_archive(self.root, 102, failed_restore, 'failure', 1))
        self.assertEqual(240, result['segmentCounts']['passed'])
        self.assertEqual(0, result['passedDiagnosticLaneCount'])
        self.assertEqual('incomplete', result['status'])
        self.assertFalse(result['lanes'][0]['cleanup']['servicesRestored'])
        # Complete diagnostics do not rewrite a failed workflow/build result.
        complete = self.result(write_archive(self.root, 103, conclusion='failure', gradle_exit=1))
        self.assertEqual('complete', complete['status'])
        self.assertEqual('failure', complete['archive']['sourceRun']['conclusion'])
        self.assertTrue(all(lane['jobConclusion'] == 'failure' for lane in complete['lanes']))

    def test_missing_capture_or_report_retains_unavailable_denominator(self):
        manifest = json.loads((self.archive / 'manifest.json').read_text())
        row = manifest['segmentedStress']['apis'][0]
        directory = self.archive / 'segmented-stress/api-30'
        for p in directory.iterdir(): p.unlink()
        row.update(captureStatus='artifact_unavailable', diagnosticStatus=None, files=[],
                   captureError='no_current_stress_artifact')
        save_json(self.archive / 'manifest.json', manifest)
        result = self.result()
        self.assertEqual(30, result['segmentCounts']['unavailable'])
        self.assertEqual(60, result['taskCounts']['unavailable'])
        self.assertEqual(240, result['expectedSegmentCount'])
        self.assertEqual(480, result['expectedTaskCount'])
        self.assertEqual(30, result['groups'][0]['metrics']['cpuPercentOneCore']['missing'])

    def test_declared_invalid_capture_stays_unavailable_without_invented_samples(self):
        manifest = json.loads((self.archive / 'manifest.json').read_text())
        row = manifest['segmentedStress']['apis'][0]
        directory = self.archive / 'segmented-stress/api-30'
        for p in directory.iterdir(): p.unlink()
        identity = {key: manifest['sourceRun'][key] for key in ('repository', 'commit', 'runId', 'attempt')}
        capture = evidence.empty_capture('30', identity)
        capture.update(captureStatus='invalid', captureError='invalid_report', gradleExitCode=1)
        ref = save_json(directory / 'capture.json', capture)
        row.update(captureStatus='invalid', diagnosticStatus=None, captureError='invalid_report',
                   actualSdkInt=None, gradleExitCode=1, files=[ref])
        save_json(self.archive / 'manifest.json', manifest)
        result = self.result()
        self.assertEqual('capture_invalid', result['lanes'][0]['reason'])
        self.assertEqual(30, result['segmentCounts']['unavailable'])
        self.assertNotIn('resourceSamples', result['lanes'][0]['segments'][0])

    def test_failed_single_sample_is_preserved_with_null_cpu_not_zero(self):
        def failure(report, values):
            del values[1:]
            report['status'] = 'failed'
            value = values[0]
            value.update(status='failed', samples=value['samples'][:1], active=None, stop=None, recovery=None)
        result = self.result(write_archive(self.root, 102, failure, 'failure', 1))
        first = result['lanes'][0]['segments'][0]
        self.assertIsNone(first['cpuPercentOneCore'])
        self.assertEqual('insufficient_sample_span', first['cpuUnavailableReason'])
        self.assertEqual(1000, first['sampleMeanTotalPssKiB'])
        self.assertEqual(480, result['taskCounts']['not_executed'])
        self.assertEqual(0, result['groups'][0]['metrics']['cpuPercentOneCore']['count'])

    def test_source_scope_api_profile_and_status_identity_are_bound(self):
        mutations = [lambda m: m['sourceRun'].update(commit='b' * 40),
                     lambda m: m['sourceRun'].update(branch='agent/other'),
                     lambda m: m.update(executionScope='full'),
                     lambda m: m.update(fullSuiteAcceptance=True),
                     lambda m: m['segmentedStress'].update(budgetAcceptance=True),
                     lambda m: m['segmentedStress']['apis'][0].update(apiLevel=31),
                     lambda m: m['segmentedStress']['apis'][0].update(diagnosticStatus='failed'),
                     lambda m: m['segmentedStress']['apis'][0].update(declaredCiProfile='different'),
                     lambda m: m['segmentedStress']['apis'].reverse(),
                     lambda m: m['segmentedStress']['apis'].pop()]
        original = (self.archive / 'manifest.json').read_bytes()
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                (self.archive / 'manifest.json').write_bytes(original)
                edit_manifest(self.archive, mutate)
                with self.assertRaises(ValueError): self.result()
        (self.archive / 'manifest.json').write_bytes(original)
        with self.assertRaises(ValueError): summary.summarize(self.archive, SHA[:8])

    def test_segment_corruption_is_rejected_even_if_outer_hash_is_rewritten(self):
        directory = self.archive / 'segmented-stress/api-30'
        segment_path = directory / 'segment-000.json'
        value = json.loads(segment_path.read_text())
        value['samples'][0]['totalPssKiB'] += 1
        ref = save_json(segment_path, value)
        def rehash_outer(manifest):
            refs = manifest['segmentedStress']['apis'][0]['files']
            refs[refs.index(next(item for item in refs if item['path'] == ref['path']))] = ref
        edit_manifest(self.archive, rehash_outer)
        with self.assertRaises(ValueError): self.result()

    def test_rehashed_unsafe_success_is_not_accepted(self):
        def wrong_task(report, values):
            values[0]['stop']['acceptedWrites'] = 1
        invalid = write_archive(self.root, 102, wrong_task)
        with self.assertRaises(ValueError): self.result(invalid)

    def test_old_report_schema_is_rejected_even_with_consistent_hashes(self):
        def old_report(report, values):
            report['kind'] = 'agent-performance-diagnostics'
        invalid = write_archive(self.root, 102, old_report)
        with self.assertRaises(ValueError): self.result(invalid)

    def test_extra_missing_duplicate_and_traversal_files_are_rejected(self):
        path = self.archive / 'segmented-stress/api-30/extra.json'
        path.write_text('{}')
        with self.assertRaises(ValueError): self.result()
        path.unlink()
        original = (self.archive / 'manifest.json').read_bytes()
        for change in (lambda refs: refs.append(dict(refs[0])), lambda refs: refs[0].update(path='../capture.json'),
                       lambda refs: refs.pop()):
            (self.archive / 'manifest.json').write_bytes(original)
            edit_manifest(self.archive, lambda m: change(m['segmentedStress']['apis'][0]['files']))
            with self.assertRaises(ValueError): self.result()
        (self.archive / 'manifest.json').write_bytes(original)
        linked = self.root / 'linked'
        linked.symlink_to(self.archive, target_is_directory=True)
        with self.assertRaises(ValueError): self.result(linked)

    def test_duplicate_json_keys_and_nonfinite_numbers_fail_before_summary(self):
        path = self.archive / 'manifest.json'
        original = path.read_bytes()
        path.write_bytes(original.replace(b'"schemaVersion": 1', b'"schemaVersion": 1, "schemaVersion": 1', 1))
        with self.assertRaises(ValueError): self.result()
        path.write_bytes(original.replace(b'"schemaVersion": 1', b'"schemaVersion": NaN', 1))
        with self.assertRaises(ValueError): self.result()

    def test_cli_output_is_exclusive_and_outside_evidence(self):
        target = self.root / 'summary.json'
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(0, summary.main([str(self.archive), '--source', SHA, '--output', str(target)]))
            saved = target.read_bytes()
            self.assertEqual(2, summary.main([str(self.archive), '--source', SHA, '--output', str(target)]))
            self.assertEqual(saved, target.read_bytes())
            self.assertEqual(2, summary.main([str(self.archive), '--source', SHA,
                                              '--output', str(self.archive / 'derived.json')]))
        self.assertFalse((self.archive / 'derived.json').exists())
        self.assertEqual('complete', json.loads(saved)['status'])
        self.assertEqual(64, len(json.loads(saved)['tool']['summaryScriptSha256']))


if __name__ == '__main__':
    unittest.main()
