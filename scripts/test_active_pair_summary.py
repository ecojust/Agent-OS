"""Independent task arithmetic, pair weighting and committed-prefix evidence cases."""

import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

import collect_active_pair_evidence as evidence
import summarize_active_pairs as summary

SHA = "a" * 40
NANO = 1_000_000_000
MILLI = 1_000_000


def trial(index):
    pair, within = divmod(index, 10)
    position, repetition = divmod(within, 5)
    agent = position != pair % 2
    start = (index * 10 + 10) * NANO
    task_start = start + 2400 * MILLI
    wall_ms = 300 + repetition * 20 + pair * 2 if agent else 100 + repetition * 10 + pair
    cpu_ms = 30 + repetition * 3 + pair * 2 if agent else 10 + repetition * 2 + pair
    task_end = task_start + wall_ms * MILLI
    cpu_start = index * 1000 + 10
    pre_pss = (1100 + pair * 20 if agent else 1000 + pair * 10) + repetition
    post_pss = pre_pss + (150 + repetition * 2 if agent else 50 + repetition)
    state = dict(servicePresent=agent, serviceConnected=agent, controllerRunning=False,
                 controlAvailable=False, fixtureFocused=True)
    def sample(at, cpu, pss):
        return dict(elapsedRealtimeNanos=at, processCpuMillis=cpu, totalPssKiB=pss,
                    dalvikPssKiB=200, nativePssKiB=100, otherPssKiB=pss - 300, collectionNanos=MILLI)
    trace = [dict(stage="DEVICE_DISPATCH_ATTEMPT", startNanos=task_start + n * 10 * MILLI,
                  durationNanos=MILLI, mainThread=False, nodeCount=-1, snapshotJsonBytes=-1,
                  truncated=False, epoch=1, dispatchAttempts=n) for n in (1, 2, 3)] if agent else []
    return dict(schemaVersion=1, kind="agent-active-paired-trial", index=index, pairIndex=pair,
                position=position, repetition=repetition,
                mode="C_SERVICE_ON_AGENT" if agent else "A_SERVICE_OFF_SCRIPTED_UI", status="passed",
                startedNanos=start, endedNanos=start + 5 * NANO, preparedNanos=start + 2 * NANO,
                initialStateVerified=True, stateBefore=dict(state, checkedNanos=start + 2100 * MILLI),
                stateAfter=dict(state, checkedNanos=task_end + 40 * MILLI),
                beforeSample=sample(start + 2200 * MILLI, index * 1000 + 1, pre_pss),
                afterSample=sample(task_end + 20 * MILLI, cpu_start + cpu_ms + 5, post_pss),
                taskStartedNanos=task_start, taskEndedNanos=task_end,
                taskStartedCpuMillis=cpu_start, taskEndedCpuMillis=cpu_start + cpu_ms,
                manualActionsAttempted=0 if agent else 3, manualActionsAccepted=0 if agent else 3,
                modelRequests=4 if agent else 0, approvalCount=3 if agent else 0,
                modelAcceptedWrites=3 if agent else 0,
                editVerified=True, savedTextVerified=True, counterVerified=True,
                resultKind="MODEL_REPORT" if agent else "", resultCode="MODEL_REPORT" if agent else "",
                traceStartedNanos=start + 2300 * MILLI, traceEndedNanos=task_end + 10 * MILLI,
                trace=trace, traceCapacity=4096, traceOverwrittenEvents=0, cleanupCompleted=True)


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
        directory = root / "active-task-pairs" / ("api-" + label)
        directory.mkdir(parents=True)
        values = [trial(index) for index in range(60)]
        report = dict(schemaVersion=1, kind="agent-active-paired-diagnostics", diagnosticOnly=True,
                      budgetAcceptance=False, status="passed", appProcessPid=1234,
                      device=dict(sdkInt=api, manufacturer="test", model="sdk", hardware="ranchu",
                                  fingerprint="synthetic-test-fingerprint", emulatorHeuristic=True),
                      conditions=dict(evidence.CONDITIONS), startedNanos=values[0]["startedNanos"] - 1,
                      endedNanos=values[-1]["endedNanos"] + 1,
                      cleanup=dict(controllerCleared=True, servicesRestored=True, enabledRestored=True,
                                   automationFlagsRestored=True), trials=[])
        if alter:
            alter(report, values)
        files = []
        for value in values:
            ref = save_json(directory / f"trial-{value['index']:03d}.json", value)
            report["trials"].append(dict(ref, **{key: value[key] for key in ("index", "status", "startedNanos", "endedNanos")}))
            files.append(ref)
        report_ref = save_json(directory / evidence.REPORT_NAME, report)
        capture = evidence.empty_capture(label, identity)
        capture.update(actualSdkInt=api, captureStatus="collected", captureError=None, gradleExitCode=gradle_exit,
                       report=dict(report_ref, status=report["status"]), emulatorStepOutcome=conclusion)
        capture_ref = save_json(directory / "capture.json", capture)
        records.append(dict(apiLevel=api, matrixLabel=label, jobConclusion=conclusion, captureStatus="collected",
                            captureError=None, actualSdkInt=api, diagnosticStatus=report["status"], gradleExitCode=gradle_exit,
                            declaredCiProfile=capture["declaredCiProfile"], artifactId=run_id * 100 + api,
                            files=[capture_ref, report_ref, *files]))
    manifest = dict(schemaVersion=1, kind="android-emulator-test-evidence", executionScope="active-pair-diagnostic",
                    fullSuiteAcceptance=False,
                    sourceRun=dict(identity, branch="master", conclusion=conclusion,
                                   url=f"https://github.com/{summary.REPOSITORY}/actions/runs/{run_id}"),
                    expectedMatrixLabels=list(evidence.LABELS),
                    activeTaskPairs=dict(kind="active-task-pair-attachments", diagnosticOnly=True,
                                         budgetAcceptance=False, scope="equivalent task diagnostics", apis=records))
    save_json(root / "manifest.json", manifest)
    return root


def edit_manifest(root, change):
    path = root / 'manifest.json'
    value = json.loads(path.read_text())
    change(value)
    save_json(path, value)


class ActivePairSummaryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.archive = write_archive(self.root)

    def result(self, archive=None):
        return summary.summarize(archive or self.archive, SHA)

    def test_known_task_arithmetic_and_fixed_pair_arm_trial_denominators(self):
        result = self.result()
        self.assertEqual(("complete", 8, 48, 96, 480, 960),
                         (result["status"], result["passedDiagnosticLaneCount"], result["expectedPairCount"],
                          result["expectedArmCount"], result["expectedTrialCount"], result["endpointSampleCount"]))
        self.assertEqual(dict(passed=480, failed=0, not_executed=0, unavailable=0), result['trialCounts'])
        self.assertEqual((48, 96), (result['eligiblePairCount'], result['eligibleArmCount']))
        for key in ('ManualActionsAccepted', 'ModelAcceptedWrites'):
            self.assertEqual(720, result['expected' + key])
            self.assertEqual(720, result['verified' + key])
            self.assertEqual(720, result['observed' + key])
            self.assertEqual(0, result['unknown' + key + 'TrialCount'])
        first = result['lanes'][0]['trials'][0]
        self.assertEqual((10, 100, 1000, 1050, 50), tuple(first[k] for k in summary.METRICS))
        self.assertEqual(trial(0)['beforeSample'], first['beforeSample'])
        # Full trial duration and PSS-probe CPU must not replace the task markers.
        self.assertEqual(5000, first['trialWallMillis'])
        self.assertEqual(24, first['afterSample']['processCpuMillis'] - first['beforeSample']['processCpuMillis'])
        self.assertEqual(10, first['taskCpuMillis'])
        self.assertNotIn('cpuPercentOneCore', first)
        self.assertNotIn('sampleMaxTotalPssKiB', first)
        self.assertTrue(result['diagnosticOnly'])
        self.assertFalse(result['budgetAcceptance'])

    def test_average_five_tasks_before_pair_difference_and_keep_order_groups(self):
        result = self.result()
        lane = result['lanes'][0]
        self.assertEqual(dict(taskCpuMillis=14, taskWallMillis=120, beforeTotalPssKiB=1002,
                              afterTotalPssKiB=1054, withinTrialTotalPssChangeKiB=52), lane['arms'][0]['meansPerTask'])
        self.assertEqual(dict(taskCpuMillis=22, taskWallMillis=220, beforeTotalPssKiB=100,
                              afterTotalPssKiB=202, withinTrialTotalPssChangeKiB=102), lane['pairs'][0]['differences'])
        group = result['groups'][0]
        self.assertEqual(dict(count=6, missing=0, min=22, median=24.5, max=27),
                         group['allPairs']['differences']['taskCpuMillis'])
        self.assertEqual(dict(count=3, missing=0, min=22, median=24, max=26),
                         group['byOrder']['AC']['differences']['taskCpuMillis'])
        self.assertEqual(dict(count=3, missing=0, min=23, median=25, max=27),
                         group['byOrder']['CA']['differences']['taskCpuMillis'])
        self.assertEqual(6, group['allPairs']['modes'][summary.MODES[0]]['taskCpuMillis']['count'])

    def test_quantized_zero_task_cpu_is_valid_not_missing(self):
        def zero(report, values):
            for value in values:
                if value['mode'] == summary.MODES[0]:
                    value['taskEndedCpuMillis'] = value['taskStartedCpuMillis']
        result = self.result(write_archive(self.root, 102, zero))
        first = result['lanes'][0]['trials'][0]
        self.assertEqual(0, first['taskCpuMillis'])
        self.assertIsNone(first['taskMeasurementUnavailableReason'])
        self.assertEqual(0, result['groups'][0]['allPairs']['modes'][summary.MODES[0]]['taskCpuMillis']['max'])

    def test_partial_interruption_retains_full_denominators_and_no_partial_arm_mean(self):
        def interrupted(report, values):
            del values[13:]
            report.update(status='incomplete', endedNanos=-1,
                          cleanup=dict.fromkeys(report['cleanup'], False))
        result = self.result(write_archive(self.root, 102, interrupted, 'cancelled', 143))
        self.assertEqual(dict(passed=104, failed=0, not_executed=0, unavailable=376), result['trialCounts'])
        self.assertEqual((48, 8, 96, 16), (result['expectedPairCount'], result['eligiblePairCount'],
                                         result['expectedArmCount'], result['eligibleArmCount']))
        lane = result['lanes'][0]
        self.assertIsNone(lane['arms'][2]['meansPerTask'])
        self.assertEqual('unavailable', lane['trials'][13]['status'])
        self.assertEqual('not_committed_before_interruption', lane['trials'][13]['reason'])
        self.assertEqual(1, result['groups'][0]['allPairs']['differences']['taskCpuMillis']['count'])
        self.assertEqual(3, result['groups'][0]['byOrder']['CA']['differences']['taskCpuMillis']['missing'])
        self.assertEqual('cancelled', lane['sourceRunConclusion'])
        self.assertEqual(143, lane['gradleExitCode'])
        self.assertIsNone(lane['wallMillis'])

    def test_failed_task_keeps_known_values_but_excludes_its_pair(self):
        def failed(report, values):
            del values[14:]
            report['status'] = 'failed'
            values[-1].update(status='failed', modelAcceptedWrites=1, counterVerified=False)
        result = self.result(write_archive(self.root, 102, failed, 'failure', 1))
        self.assertEqual(dict(passed=104, failed=8, not_executed=368, unavailable=0), result['trialCounts'])
        self.assertEqual(8, result['eligiblePairCount'])
        lane = result['lanes'][0]
        self.assertEqual('failed', lane['pairs'][1]['status'])
        self.assertEqual(41, lane['trials'][13]['taskCpuMillis'])
        self.assertEqual(1, lane['trials'][13]['modelAcceptedWrites'])
        self.assertEqual('not_executed_after_failure', lane['trials'][14]['reason'])
        self.assertEqual('failure', result['archive']['sourceRun']['conclusion'])
        self.assertEqual(1, lane['gradleExitCode'])

    def test_failure_before_measurement_does_not_invent_zero_cost_or_endpoint_memory(self):
        def failed(report, values):
            del values[1:]
            report['status'] = 'failed'
            value = values[0]
            value.update(status='failed', preparedNanos=-1, initialStateVerified=False,
                         stateBefore=None, stateAfter=None, beforeSample=None, afterSample=None,
                         taskStartedNanos=-1, taskEndedNanos=-1, taskStartedCpuMillis=-1,
                         taskEndedCpuMillis=-1, traceStartedNanos=-1, traceEndedNanos=-1,
                         manualActionsAttempted=-1, manualActionsAccepted=-1, modelAcceptedWrites=-1,
                         cleanupCompleted=False, editVerified=False, savedTextVerified=False, counterVerified=False)
        result = self.result(write_archive(self.root, 102, failed, 'failure', 1))
        first = result['lanes'][0]['trials'][0]
        for metric in summary.METRICS:
            self.assertIsNone(first[metric])
        self.assertEqual(0, result['endpointSampleCount'])
        self.assertEqual(480, result['unknownManualActionsAcceptedTrialCount'])
        self.assertEqual('missing_task_markers', first['taskMeasurementUnavailableReason'])
        self.assertEqual(6, result['groups'][0]['allPairs']['differences']['taskCpuMillis']['missing'])

    def test_empty_checkpoint_and_interrupted_finalization_are_not_complete(self):
        for length in (0, 60):
            with self.subTest(committed=length):
                def interrupted(report, values):
                    del values[length:]
                    report.update(status='incomplete', endedNanos=-1,
                                  cleanup=dict.fromkeys(report['cleanup'], False))
                result = self.result(write_archive(self.root, 102 + length, interrupted, 'cancelled', 143))
                self.assertEqual('incomplete', result['status'])
                self.assertEqual(0, result['passedDiagnosticLaneCount'])
                self.assertEqual(length * 8, result['trialCounts']['passed'])
                self.assertEqual((60 - length) * 8, result['trialCounts']['unavailable'])
                self.assertEqual(0, result['trialCounts']['not_executed'])

    def test_cleanup_failure_and_workflow_failure_preserve_original_conclusions(self):
        def failed_restore(report, values):
            report.update(status='failed', cleanup=dict(report['cleanup'], servicesRestored=False))
        result = self.result(write_archive(self.root, 102, failed_restore, 'failure', 1))
        self.assertEqual(480, result['trialCounts']['passed'])
        self.assertEqual(48, result['eligiblePairCount'])
        self.assertEqual('incomplete', result['status'])
        self.assertFalse(result['lanes'][0]['cleanup']['servicesRestored'])
        complete = self.result(write_archive(self.root, 103, conclusion='failure', gradle_exit=1))
        self.assertEqual('complete', complete['status'])
        self.assertEqual('failure', complete['archive']['sourceRun']['conclusion'])
        self.assertTrue(all(lane['jobConclusion'] == 'failure' for lane in complete['lanes']))

    def test_missing_artifact_preserves_sixty_trials_and_six_pairs_unavailable(self):
        manifest = json.loads((self.archive / 'manifest.json').read_text())
        row = manifest['activeTaskPairs']['apis'][0]
        directory = self.archive / 'active-task-pairs/api-30'
        for p in directory.iterdir(): p.unlink()
        row.update(captureStatus='artifact_unavailable', diagnosticStatus=None, files=[],
                   captureError='no_current_active_pair_artifact')
        save_json(self.archive / 'manifest.json', manifest)
        result = self.result()
        self.assertEqual(60, result['trialCounts']['unavailable'])
        self.assertEqual(42, result['eligiblePairCount'])
        self.assertEqual(6, result['groups'][0]['allPairs']['differences']['taskCpuMillis']['missing'])
        self.assertEqual(60, result['unknownManualActionsAcceptedTrialCount'])

    def test_invalid_capture_preserves_unavailable_trials_without_measurements(self):
        manifest = json.loads((self.archive / 'manifest.json').read_text())
        row = manifest['activeTaskPairs']['apis'][0]
        directory = self.archive / 'active-task-pairs/api-30'
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
        self.assertEqual(60, result['trialCounts']['unavailable'])
        self.assertNotIn('beforeSample', result['lanes'][0]['trials'][0])

    def test_source_scope_api_profile_status_and_order_identity_are_bound(self):
        mutations = [lambda m: m['sourceRun'].update(commit='b' * 40),
                     lambda m: m['sourceRun'].update(branch='agent/other'),
                     lambda m: m.update(executionScope='full'),
                     lambda m: m.update(fullSuiteAcceptance=True),
                     lambda m: m['activeTaskPairs'].update(budgetAcceptance=True),
                     lambda m: m['activeTaskPairs']['apis'][0].update(apiLevel=31),
                     lambda m: m['activeTaskPairs']['apis'][0].update(diagnosticStatus='failed'),
                     lambda m: m['activeTaskPairs']['apis'][0].update(declaredCiProfile='different'),
                     lambda m: m['activeTaskPairs']['apis'].reverse(),
                     lambda m: m['activeTaskPairs']['apis'].pop()]
        original = (self.archive / 'manifest.json').read_bytes()
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                (self.archive / 'manifest.json').write_bytes(original)
                edit_manifest(self.archive, mutate)
                with self.assertRaises(ValueError): self.result()
        (self.archive / 'manifest.json').write_bytes(original)
        with self.assertRaises(ValueError): summary.summarize(self.archive, SHA[:8])

    def test_inner_trial_hash_rejects_corruption_even_if_outer_hash_is_rewritten(self):
        directory = self.archive / 'active-task-pairs/api-30'
        path = directory / 'trial-000.json'
        value = json.loads(path.read_text())
        value['beforeSample']['totalPssKiB'] += 1
        ref = save_json(path, value)
        def rehash_outer(manifest):
            refs = manifest['activeTaskPairs']['apis'][0]['files']
            refs[refs.index(next(item for item in refs if item['path'] == ref['path']))] = ref
        edit_manifest(self.archive, rehash_outer)
        with self.assertRaises(ValueError): self.result()

    def test_rehashed_manual_or_agent_counter_contradictions_cannot_pass(self):
        changes = [lambda v: v[0].update(modelAcceptedWrites=3),
                   lambda v: v[5].update(manualActionsAttempted=3, manualActionsAccepted=3),
                   lambda v: v[5].update(modelAcceptedWrites=2),
                   lambda v: v[0].update(initialStateVerified=False)]
        for index, change in enumerate(changes):
            def bad(report, values): change(values)
            with self.subTest(change=index):
                invalid = write_archive(self.root, 102 + index, bad)
                with self.assertRaises(ValueError): self.result(invalid)

    def test_old_report_schema_and_reordered_trial_modes_are_rejected(self):
        for index, change in enumerate((lambda r, v: r.update(kind='agent-segmented-stress-diagnostics'),
                                         lambda r, v: v[0].update(mode=summary.MODES[1]))):
            with self.subTest(change=index):
                invalid = write_archive(self.root, 102 + index, change)
                with self.assertRaises(ValueError): self.result(invalid)

    def test_extra_missing_duplicate_traversal_and_symlink_paths_are_rejected(self):
        path = self.archive / 'active-task-pairs/api-30/extra.json'
        path.write_text('{}')
        with self.assertRaises(ValueError): self.result()
        path.unlink()
        original = (self.archive / 'manifest.json').read_bytes()
        for change in (lambda refs: refs.append(dict(refs[0])), lambda refs: refs[0].update(path='../capture.json'),
                       lambda refs: refs.pop()):
            (self.archive / 'manifest.json').write_bytes(original)
            edit_manifest(self.archive, lambda m: change(m['activeTaskPairs']['apis'][0]['files']))
            with self.assertRaises(ValueError): self.result()
        (self.archive / 'manifest.json').write_bytes(original)
        linked = self.root / 'linked'
        linked.symlink_to(self.archive, target_is_directory=True)
        with self.assertRaises(ValueError): self.result(linked)

    def test_duplicate_json_keys_and_nonfinite_values_fail_before_summary(self):
        path = self.archive / 'manifest.json'
        original = path.read_bytes()
        path.write_bytes(original.replace(b'"schemaVersion": 1', b'"schemaVersion": 1, "schemaVersion": 1', 1))
        with self.assertRaises(ValueError): self.result()
        path.write_bytes(original.replace(b'"schemaVersion": 1', b'"schemaVersion": NaN', 1))
        with self.assertRaises(ValueError): self.result()

    def test_cli_output_is_exclusive_and_outside_original_evidence(self):
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
