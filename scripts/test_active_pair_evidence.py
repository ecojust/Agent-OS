"""Boundary tests and synthetic builders for committed active-pair evidence."""

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import collect_active_pair_evidence as active
import collect_stress_evidence as legacy

SOURCE = {"repository": "ecojust/Agent-OS", "runId": 123, "attempt": 2, "commit": "a" * 40}


def encoded(value):
    return (json.dumps(value, separators=(',', ':')) + '\n').encode()


def event(stage, start, duration=1_000_000, dispatches=0):
    return {"stage": stage, "startNanos": start, "durationNanos": duration,
            "mainThread": False, "nodeCount": -1, "snapshotJsonBytes": -1,
            "truncated": False, "epoch": 1, "dispatchAttempts": dispatches}


def state(checked, mode=active.MODES[0]):
    connected = mode == active.MODES[1]
    return {"checkedNanos": checked, "servicePresent": connected, "serviceConnected": connected,
            "controllerRunning": False, "controlAvailable": False, "fixtureFocused": True}


def sample(when, cpu, pss=20000):
    return {"elapsedRealtimeNanos": when, "processCpuMillis": cpu, "totalPssKiB": pss,
            "dalvikPssKiB": 6000, "nativePssKiB": 4000, "otherPssKiB": 10000,
            "collectionNanos": 1_000_000}


def trial(index=0):
    start = 1_000_000_000 + index * 5_000_000_000
    mode = active.expected_mode(index)
    agent = mode == active.MODES[1]
    cpu = 1000 + index * 1000
    return {"schemaVersion": 1, "kind": active.TRIAL_KIND, "index": index, "pairIndex": index // 10,
            "position": index // 5 % 2, "repetition": index % 5, "mode": mode, "status": "passed",
            "startedNanos": start, "endedNanos": start + 4_000_000_000,
            "preparedNanos": start + 2_000_000_000, "initialStateVerified": True,
            "stateBefore": state(start + 2_001_000_000, mode), "stateAfter": state(start + 3_600_000_000, mode),
            "beforeSample": sample(start + 2_010_000_000, cpu),
            "afterSample": sample(start + 3_500_000_000, cpu + (120 if agent else 20), 21000 + index),
            "taskStartedNanos": start + 2_020_000_000, "taskEndedNanos": start + 3_400_000_000,
            "taskStartedCpuMillis": cpu + 10, "taskEndedCpuMillis": cpu + (110 if agent else 10),
            "manualActionsAttempted": 0 if agent else 3, "manualActionsAccepted": 0 if agent else 3,
            "modelRequests": 4 if agent else 0, "approvalCount": 3 if agent else 0,
            "modelAcceptedWrites": 3 if agent else 0, "editVerified": True, "savedTextVerified": True,
            "counterVerified": True, "resultKind": "MODEL_REPORT" if agent else "",
            "resultCode": "MODEL_REPORT" if agent else "", "traceStartedNanos": start + 2_015_000_000,
            "traceEndedNanos": start + 3_450_000_000,
            "trace": [event('DEVICE_DISPATCH_ATTEMPT', start + 2_100_000_000 + i * 100_000_000, dispatches=i + 1)
                      for i in range(3)] if agent else [],
            "traceCapacity": 4096, "traceOverwrittenEvents": 0, "cleanupCompleted": True}


def report(api=37, trials=None):
    values = [trial(i) for i in range(60)] if trials is None else trials
    refs = []
    for item in values:
        data = encoded(item)
        refs.append({"index": item['index'], "path": active.trial_name(item['index']), "bytes": len(data),
                     "sha256": hashlib.sha256(data).hexdigest(), "status": item['status'],
                     "startedNanos": item['startedNanos'], "endedNanos": item['endedNanos']})
    return {"schemaVersion": 1, "kind": active.KIND, "diagnosticOnly": True, "budgetAcceptance": False,
            "status": "passed", "appProcessPid": 420,
            "device": {"sdkInt": api, "manufacturer": "synthetic", "model": "unit-test", "hardware": "synthetic",
                       "fingerprint": "synthetic-fixture", "emulatorHeuristic": True},
            "conditions": dict(active.CONDITIONS), "startedNanos": 1_000_000_000,
            "endedNanos": values[-1]['endedNanos'] + 1 if values else 1_000_000_000,
            "cleanup": {"controllerCleared": True, "servicesRestored": True, "enabledRestored": True,
                        "automationFlagsRestored": True}, "trials": refs}


def write_bundle(directory, api=37, trials=None, status='passed'):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    values = [trial(i) for i in range(60)] if trials is None else trials
    value = report(api, values)
    value['status'] = status
    if status == 'incomplete':
        value['endedNanos'] = -1
        value['cleanup'] = {key: False for key in value['cleanup']}
    for item in values:
        (directory / active.trial_name(item['index'])).write_bytes(encoded(item))
    (directory / active.REPORT_NAME).write_bytes(encoded(value))
    return value


def collected_capture(value, label='37.0'):
    raw = encoded(value)
    capture = active.empty_capture(label, SOURCE)
    capture.update(actualSdkInt=int(label.split('.')[0]), captureStatus='collected', captureError=None,
                   gradleExitCode=0 if value['status'] == 'passed' else 1,
                   report={'path': active.REPORT_NAME, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(), 'status': value['status']})
    return capture, raw


def unstarted_failed_trial(index):
    value = trial(index)
    value.update(status='failed', initialStateVerified=False, editVerified=False, savedTextVerified=False,
                 counterVerified=False, cleanupCompleted=False, stateBefore=None, stateAfter=None,
                 beforeSample=None, afterSample=None, trace=[], resultKind='', resultCode='',
                 manualActionsAttempted=0, manualActionsAccepted=0, modelRequests=-1, approvalCount=0, modelAcceptedWrites=-1)
    for key in ('preparedNanos', 'taskStartedNanos', 'taskEndedNanos', 'taskStartedCpuMillis', 'taskEndedCpuMillis',
                'traceStartedNanos', 'traceEndedNanos'):
        value[key] = -1
    return value


class ActivePairEvidenceTests(unittest.TestCase):
    def test_complete_bundle_preserves_fixed_order_and_zero_cpu_quantization(self):
        with tempfile.TemporaryDirectory() as temp:
            value = write_bundle(temp)
            original = copy.deepcopy(value)
            parsed = active.validate_bundle(temp, value, 37)
            self.assertEqual(60, len(parsed))
            self.assertEqual([active.MODES[0]] * 5 + [active.MODES[1]] * 10 + [active.MODES[0]] * 5,
                             [item['mode'] for item in parsed[:20]])
            self.assertEqual(30, sum(item['taskStartedCpuMillis'] == item['taskEndedCpuMillis'] for item in parsed))
            self.assertEqual(original, value)
            (Path(temp) / 'capture.json').write_text('{}')
            active.validate_bundle(temp, value, 37)

    def test_incomplete_committed_prefix_never_becomes_a_completed_plan(self):
        for count in (0, 1, 9, 10, 59, 60):
            with self.subTest(count=count), tempfile.TemporaryDirectory() as temp:
                value = write_bundle(temp, trials=[trial(i) for i in range(count)], status='incomplete')
                self.assertEqual(count, len(active.validate_bundle(temp, value, 37)))
                value['status'] = 'passed'
                with self.assertRaises(ValueError): active.validate_report(value, 37)
        value = report();value['status'] = 'failed';value['cleanup']['automationFlagsRestored'] = False
        active.validate_report(value, 37)
        value['status'] = 'passed'
        with self.assertRaisesRegex(ValueError, 'cleanup'): active.validate_report(value, 37)

    def test_failure_preserves_unknown_markers_and_stops_after_first_failed_trial(self):
        values = [trial(0), unstarted_failed_trial(1)]
        with tempfile.TemporaryDirectory() as temp:
            value = write_bundle(temp, trials=values, status='failed')
            parsed = active.validate_bundle(temp, value, 37)
            self.assertEqual(-1, parsed[-1]['taskStartedCpuMillis'])
            value['trials'].append(report(trials=[trial(2)])['trials'][0])
            with self.assertRaisesRegex(ValueError, 'prefix'): active.validate_report(value, 37)
        partial = trial(5)
        partial.update(status='failed', resultKind='FAILED', resultCode='STALE_OBSERVATION',
                       modelAcceptedWrites=1, savedTextVerified=False, counterVerified=False, traceOverwrittenEvents=2)
        active.validate_trial(partial, 5)

    def test_manual_and_model_acceptance_cannot_be_substituted(self):
        for index, changes in ((0, [{'manualActionsAttempted': 4}, {'manualActionsAccepted': 2},
                                   {'modelAcceptedWrites': 3}, {'modelRequests': 1}, {'approvalCount': 1},
                                   {'resultKind': 'MODEL_REPORT'}, {'resultCode': 'MODEL_REPORT'}]),
                               (5, [{'manualActionsAttempted': 1}, {'manualActionsAccepted': 1},
                                    {'modelAcceptedWrites': 0}, {'modelRequests': 0}, {'approvalCount': 2},
                                    {'resultKind': 'VERIFIED'}, {'resultCode': ''}])):
            for change in changes:
                with self.subTest(index=index, change=change):
                    value = trial(index);value.update(change)
                    with self.assertRaises(ValueError): active.validate_trial(value, index)
        value = trial(5);value['approvalCount'] = 5
        value['trace'].append(event('DEVICE_DISPATCH_ATTEMPT', value['taskStartedNanos'] + 1, dispatches=0))
        active.validate_trial(value, 5)  # A safe unsubmitted replan is not a fourth accepted write.

    def test_initial_final_ui_and_cleanup_are_required_for_both_arms(self):
        for index in (0, 5):
            for flag in ('initialStateVerified', 'editVerified', 'savedTextVerified', 'counterVerified', 'cleanupCompleted'):
                value = trial(index);value[flag] = False
                with self.assertRaisesRegex(ValueError, 'verification'): active.validate_trial(value, index)
            for side in ('stateBefore', 'stateAfter'):
                for flag in active.STATE_FLAGS:
                    value = trial(index);value[side][flag] = not value[side][flag]
                    with self.assertRaisesRegex(ValueError, 'endpoint_state'): active.validate_trial(value, index)

    def test_task_cpu_markers_exclude_both_pss_probes_and_are_monotonic(self):
        mutations = [lambda v: v.update(taskStartedNanos=v['beforeSample']['elapsedRealtimeNanos']),
                     lambda v: v.update(taskEndedNanos=v['taskStartedNanos']),
                     lambda v: v.update(taskStartedCpuMillis=v['beforeSample']['processCpuMillis'] - 1),
                     lambda v: v.update(taskEndedCpuMillis=v['taskStartedCpuMillis'] - 1),
                     lambda v: v['afterSample'].update(processCpuMillis=v['taskEndedCpuMillis'] - 1),
                     lambda v: v['afterSample'].update(elapsedRealtimeNanos=v['traceEndedNanos'] - 1),
                     lambda v: v.update(traceStartedNanos=v['taskStartedNanos'] + 1),
                     lambda v: v.update(traceEndedNanos=v['taskEndedNanos'] - 1)]
        for mutate in mutations:
            value = trial(5);mutate(value)
            with self.assertRaises(ValueError): active.validate_trial(value, 5)

    def test_endpoint_state_and_sample_timing_cannot_escape_trial(self):
        mutations = [lambda v: v.update(preparedNanos=v['stateBefore']['checkedNanos'] + 1),
                     lambda v: v['stateBefore'].update(checkedNanos=v['beforeSample']['elapsedRealtimeNanos'] + 1),
                     lambda v: v['stateAfter'].update(checkedNanos=v['afterSample']['elapsedRealtimeNanos']),
                     lambda v: v['afterSample'].update(collectionNanos=v['endedNanos']),
                     lambda v: v['beforeSample'].update(elapsedRealtimeNanos=v['startedNanos'] - 1),
                     lambda v: v['stateAfter'].update(checkedNanos=v['endedNanos'] + 1)]
        for mutate in mutations:
            value = trial();mutate(value)
            with self.assertRaises(ValueError): active.validate_trial(value, 0)

    def test_known_failed_measurements_still_have_bounds_without_fabricated_missing_values(self):
        value = unstarted_failed_trial(0)
        active.validate_trial(value, 0)
        value['taskEndedNanos'] = value['endedNanos']
        with self.assertRaisesRegex(ValueError, 'without_start'): active.validate_trial(value, 0)
        value = trial(5);value['status'] = 'failed';value['taskEndedCpuMillis'] = -1
        active.validate_trial(value, 5)
        value['beforeSample']['processCpuMillis'] = value['afterSample']['processCpuMillis'] + 1
        with self.assertRaises(ValueError): active.validate_trial(value, 5)

    def test_trace_integrity_is_mode_specific_and_cannot_lose_events(self):
        manual = trial();manual['trace'].append(event('SNAPSHOT_OBSERVE', manual['taskStartedNanos']))
        with self.assertRaisesRegex(ValueError, 'manual_trial'): active.validate_trial(manual, 0)
        for mutate in (lambda v: v['trace'].pop(), lambda v: v.update(traceOverwrittenEvents=1),
                       lambda v: v.update(traceCapacity=4095), lambda v: v['trace'][0].update(stage='PRIVATE_BODY'),
                       lambda v: v['trace'][0].update(startNanos=v['traceStartedNanos'] - 1),
                       lambda v: v['trace'][0].update(nodeCount=301)):
            value = trial(5);mutate(value)
            with self.assertRaises(ValueError): active.validate_trial(value, 5)
        value = trial(5);value['trace'].reverse()
        active.validate_trial(value, 5)  # Completion-list order is not event-start order.

    def test_manifest_scope_conditions_paths_counts_and_cleanup_are_strict(self):
        for mutate in (lambda v: v.update(kind=legacy.KIND), lambda v: v.update(schemaVersion=True),
                       lambda v: v.update(budgetAcceptance=True), lambda v: v.update(private='extra'),
                       lambda v: v['device'].update(sdkInt=36), lambda v: v['conditions'].update(pairCount=5),
                       lambda v: v['trials'][0].update(path='../trial-000.json'),
                       lambda v: v['trials'][0].update(index=True),
                       lambda v: v['trials'][0].update(bytes=active.MAX_TRIAL_BYTES + 1),
                       lambda v: v['trials'][0].update(sha256='wrong'), lambda v: v['trials'].pop(),
                       lambda v: v.update(endedNanos=v['startedNanos'] + 1)):
            value = report();mutate(value)
            with self.assertRaises(ValueError): active.validate_report(value, 37)

    def test_strict_fields_types_order_and_bounds_apply_to_failed_records_too(self):
        for mutate in (lambda v: v.update(index=True), lambda v: v.update(pairIndex=1),
                       lambda v: v.update(position=1), lambda v: v.update(repetition=1),
                       lambda v: v.update(mode=active.MODES[1]), lambda v: v.update(extra='private'),
                       lambda v: v.update(cleanupCompleted=1), lambda v: v.update(taskStartedCpuMillis=True),
                       lambda v: v['beforeSample'].update(totalPssKiB=True),
                       lambda v: v.update(manualActionsAttempted=2, manualActionsAccepted=3)):
            value = trial();value['status'] = 'failed';mutate(value)
            with self.assertRaises(ValueError): active.validate_trial(value, 0)

    def test_rehashed_cpu_regression_between_trials_is_rejected(self):
        values = [trial(i) for i in range(60)]
        for key in ('beforeSample', 'afterSample'): values[1][key]['processCpuMillis'] -= 1000
        for key in ('taskStartedCpuMillis', 'taskEndedCpuMillis'): values[1][key] -= 1000
        with tempfile.TemporaryDirectory() as temp:
            value = write_bundle(temp, trials=values)
            with self.assertRaisesRegex(ValueError, 'cross_trial'): active.validate_bundle(temp, value, 37)

    def test_bundle_hash_length_exact_file_set_and_symlink_are_checked(self):
        for change in ('hash', 'size', 'missing', 'extra', 'symlink', 'directory', 'root'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temp:
                root = Path(temp);value = write_bundle(root);target = root / active.trial_name(5)
                if change == 'hash':target.write_bytes(target.read_bytes().replace(b'MODEL_REPORT', b'MODEL_REPORX'))
                elif change == 'size':target.write_bytes(target.read_bytes() + b' ')
                elif change == 'missing':target.unlink()
                elif change == 'extra':(root / 'trial-060.json').write_text('{}')
                elif change == 'symlink':target.unlink();target.symlink_to(root / active.trial_name(0))
                elif change == 'directory':target.unlink();target.mkdir()
                else:(root / active.REPORT_NAME).write_text('{}')
                with self.assertRaises(ValueError): active.validate_bundle(root, value, 37)

    def test_capture_preserves_incomplete_and_exact_source(self):
        for status in active.STATUSES:
            value = report();value['status'] = status
            if status == 'incomplete':value['endedNanos'] = -1
            capture, unused = collected_capture(value)
            active.validate_capture(capture, '37.0', SOURCE)
        for change in ({'kind': legacy.CAPTURE_KIND}, {'budgetAcceptance': True}, {'matrixLabel': '36'},
                       {'sourceRun': dict(SOURCE, attempt=3)}, {'sourceRun': dict(SOURCE, runId=True)}):
            capture, unused = collected_capture(report());capture.update(change)
            with self.assertRaises(ValueError): active.validate_capture(capture, '37.0', SOURCE)

    def test_collector_only_fetches_committed_prefix_and_keeps_interruption(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);device=root/'device';out=root/'out';out.mkdir()
            write_bundle(device, trials=[trial(0)], status='incomplete')
            (device / 'trial-001.json').write_text('orphan not committed')
            calls=[]
            def run(args, **kwargs):
                calls.append((args, kwargs))
                if args[1:3] == ['shell','getprop']:kwargs['stdout'].write(b'37\n')
                else:kwargs['stdout'].write((device / args[-1].split('/')[-1]).read_bytes())
                return subprocess.CompletedProcess(args,0)
            with patch.object(active.subprocess,'run',side_effect=run):capture=active.collect('37.0',SOURCE,124,out)
            self.assertEqual('collected',capture['captureStatus']);self.assertEqual('incomplete',capture['report']['status'])
            self.assertEqual(124,capture['gradleExitCode']);self.assertEqual(3,len(calls))
            self.assertEqual({active.REPORT_NAME,'trial-000.json'},{p.name for p in out.iterdir()})
            self.assertTrue(all(0 < options['timeout'] <= 5 and 'preexec_fn' in options for unused,options in calls))
            self.assertTrue(all(args[-1].startswith('files/performance/active-pairs/') for args,unused in calls[1:]))

    def test_timeout_nonzero_cat_or_bad_hash_cannot_leave_passed_payload(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);device=root/'device';out=root/'out';out.mkdir()
            write_bundle(device,trials=[trial(0)],status='failed');(device/'trial-000.json').write_text('{}')
            def run(args,**kwargs):
                if args[1:3] == ['shell','getprop']:kwargs['stdout'].write(b'37\n')
                else:kwargs['stdout'].write((device / args[-1].split('/')[-1]).read_bytes())
                return subprocess.CompletedProcess(args,0)
            with patch.object(active.subprocess,'run',side_effect=run):capture=active.collect('37.0',SOURCE,1,out)
            self.assertEqual('invalid',capture['captureStatus']);self.assertIsNone(capture['report']);self.assertFalse(list(out.iterdir()))
            with patch.object(active.subprocess,'run',side_effect=subprocess.TimeoutExpired('adb',5)):
                capture=active.collect('37.0',SOURCE,124,out)
            self.assertEqual('unavailable',capture['captureStatus']);self.assertEqual(124,capture['gradleExitCode'])
            with patch.object(active,'pull_file',side_effect=ValueError('committed_report_unavailable')),patch.object(active.subprocess,'run',side_effect=run):
                capture=active.collect('37.0',SOURCE,1,out)
            self.assertEqual('unavailable',capture['captureStatus']);self.assertEqual('committed_report_unavailable',capture['captureError'])
        with patch.object(active.time,'monotonic',return_value=60):
            with self.assertRaisesRegex(ValueError,'deadline'):active.remaining_timeout(60)

    def test_sdk_output_is_bounded_before_report_download(self):
        for data in (b'3'*33,b'\xff\n',b'36\n'):
            with self.subTest(data=data),tempfile.TemporaryDirectory() as temp:
                calls=[]
                def run(args,**kwargs):
                    calls.append(args);self.assertIn('preexec_fn',kwargs);self.assertNotIn('capture_output',kwargs)
                    kwargs['stdout'].write(data);return subprocess.CompletedProcess(args,0)
                with patch.object(active.subprocess,'run',side_effect=run):capture=active.collect('37.0',SOURCE,1,temp)
                self.assertEqual('invalid',capture['captureStatus']);self.assertEqual(1,len(calls))
                self.assertIsNone(capture['report']);self.assertFalse(list(Path(temp).iterdir()))

    def test_json_duplicate_nonfinite_and_oversized_files_are_rejected(self):
        for raw in ('{"a":1,"a":2}','{"a":NaN}','{"a":Infinity}'):
            with self.assertRaises(ValueError):active.strict_json(raw)
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'root.json';path.write_bytes(b' '*(active.MAX_REPORT_BYTES+1))
            with self.assertRaises(ValueError):active.read_json(path)


if __name__ == '__main__':
    unittest.main()
