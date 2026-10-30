"""Boundary tests for committed, bounded thirty-minute stress evidence."""

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import collect_stress_evidence as stress
import collect_performance_evidence as legacy

SOURCE = {"repository": "ecojust/Agent-OS", "runId": 123, "attempt": 2, "commit": "a" * 40}


def encoded(value):
    return (json.dumps(value, separators=(',', ':')) + '\n').encode()


def event(stage, start, duration=1_000_000, dispatches=0):
    return {"stage": stage, "startNanos": start, "durationNanos": duration,
            "mainThread": False, "nodeCount": -1, "snapshotJsonBytes": -1,
            "truncated": False, "epoch": 1, "dispatchAttempts": dispatches}


def state(checked):
    return {"checkedNanos": checked, "servicePresent": False, "serviceConnected": False,
            "controllerRunning": False, "controlAvailable": False, "fixtureFocused": True}


def segment(index=0):
    start = 1_000_000_000 + index * 61_000_000_000
    trace = {"trace": [], "traceCapacity": 4096, "traceOverwrittenEvents": 0}
    active = dict(trace, status="passed", startedNanos=start + 1_000_000_000, endedNanos=start + 4_000_000_000,
                  resultKind="MODEL_REPORT", resultCode="MODEL_REPORT", acceptedWrites=3, finalStateObserved=True,
                  counterVerified=True, savedTextVerified=True)
    active['trace'] = [event('DEVICE_DISPATCH_ATTEMPT', start + (i + 1) * 1_000_000_000, dispatches=i + 1) for i in range(3)]
    stop = dict(trace, status="passed", startedNanos=start + 5_000_000_000, endedNanos=start + 7_000_000_000,
                resultKind="STOPPED", resultCode="TASK_STOPPED", acceptedWrites=0, approvalReached=True,
                counterVerified=True, stopEnqueuedNanos=start + 5_100_000_000, stopStateAppliedNanos=start + 5_401_000_000)
    stop['trace'] = [event('STOP_RECEIVED', start + 5_200_000_000), event('STOP_EPOCH_INVALIDATED', start + 5_300_000_000),
                     event('STOP_UI_APPLIED', start + 5_400_000_000)]
    recovery = dict(trace, status="passed", startedNanos=start + 10_000_000_000,
                    endedNanos=start + 60_050_000_000, stateBefore=state(start + 9_999_000_000),
                    stateAfter=state(start + 60_051_000_000))
    samples = []
    for point in range(13):
        samples.append({"elapsedRealtimeNanos": start + 10_000_000 + point * 5_000_000_000,
                        "processCpuMillis": 1000 + index * 10000 + point * 50,
                        "totalPssKiB": 10000 + index * 10 + point * 2, "dalvikPssKiB": 3000,
                        "nativePssKiB": 4000 + index * 5 + point, "otherPssKiB": 3000,
                        "collectionNanos": 1_000_000, "phase": "setup" if point == 0 else "stop" if point == 1 else "recovery"})
    return {"schemaVersion": 1, "kind": stress.SEGMENT_KIND, "index": index, "status": "passed",
            "startedNanos": start, "endedNanos": start + 60_100_000_000, "samples": samples,
            "sampleLimitReached": False, "active": active, "stop": stop, "recovery": recovery}


def report(api=37, segments=None):
    values = [segment(i) for i in range(30)] if segments is None else segments
    refs = []
    for item in values:
        data = encoded(item)
        refs.append({"index": item['index'], "path": stress.segment_name(item['index']), "bytes": len(data),
                     "sha256": hashlib.sha256(data).hexdigest(), "status": item['status'],
                     "startedNanos": item['startedNanos'], "endedNanos": item['endedNanos']})
    return {"schemaVersion": 1, "kind": stress.KIND, "diagnosticOnly": True, "budgetAcceptance": False,
            "status": "passed", "appProcessPid": 420,
            "device": {"sdkInt": api, "manufacturer": "synthetic", "model": "unit-test", "hardware": "synthetic",
                       "fingerprint": "synthetic-fixture", "emulatorHeuristic": True},
            "conditions": dict(stress.CONDITIONS), "startedNanos": 1_000_000_000,
            "endedNanos": values[-1]['endedNanos'] + 1 if values else 1_000_000_000,
            "cleanup": {"controllerCleared": True, "servicesRestored": True, "enabledRestored": True}, "segments": refs}


def write_bundle(directory, api=37, segments=None, status='passed'):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    values = [segment(i) for i in range(30)] if segments is None else segments
    value = report(api, values)
    value['status'] = status
    if status == 'incomplete':
        value['endedNanos'] = -1
        value['cleanup'] = {key: False for key in value['cleanup']}
    for item in values:
        (directory / stress.segment_name(item['index'])).write_bytes(encoded(item))
    (directory / stress.REPORT_NAME).write_bytes(encoded(value))
    return value


def collected_capture(value, label='37.0'):
    raw = encoded(value)
    capture = stress.empty_capture(label, SOURCE)
    capture.update(actualSdkInt=int(label.split('.')[0]), captureStatus='collected', captureError=None,
                   gradleExitCode=0 if value['status'] == 'passed' else 1,
                   report={'path': stress.REPORT_NAME, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest(), 'status': value['status']})
    return capture, raw


class StressEvidenceTests(unittest.TestCase):
    def test_complete_bundle_has_thirty_bounded_real_duration_segments(self):
        with tempfile.TemporaryDirectory() as temp:
            value = write_bundle(temp)
            original = copy.deepcopy(value)
            parsed = stress.validate_bundle(temp, value, 37)
            self.assertEqual(30, len(parsed))
            self.assertEqual(390, sum(len(x['samples']) for x in parsed))
            self.assertEqual(original, value)
            (Path(temp) / 'capture.json').write_text('{}')
            stress.validate_bundle(temp, value, 37)

    def test_incomplete_committed_prefix_is_not_promoted_to_pass(self):
        for count in (0, 1, 12, 30):
            with self.subTest(count=count), tempfile.TemporaryDirectory() as temp:
                value = write_bundle(temp, segments=[segment(i) for i in range(count)], status='incomplete')
                stress.validate_bundle(temp, value, 37)
                value['status'] = 'passed'
                with self.assertRaises(ValueError):
                    stress.validate_report(value, 37)
        value = report()
        value['cleanup']['servicesRestored'] = False
        value['status'] = 'failed'
        stress.validate_report(value, 37)
        value['status'] = 'passed'
        with self.assertRaisesRegex(ValueError, 'cleanup'):
            stress.validate_report(value, 37)

    def test_first_failed_segment_preserves_missing_phases_and_remaining_plan(self):
        failed = segment(1)
        failed.update(status='failed', active=None, stop=None, recovery=None, samples=[])
        with tempfile.TemporaryDirectory() as temp:
            value = write_bundle(temp, segments=[segment(0), failed], status='failed')
            self.assertEqual(2, len(stress.validate_bundle(temp, value, 37)))
            value['segments'].append(report(segments=[segment(2)])['segments'][0])
            with self.assertRaisesRegex(ValueError, 'prefix'):
                stress.validate_report(value, 37)
        failed['active'] = segment(1)['active']
        failed['active']['status'] = 'failed'
        failed['stop'] = segment(1)['stop']
        with self.assertRaisesRegex(ValueError, 'phase_after'):
            stress.validate_segment(failed, 1)

    def test_true_task_outcomes_and_saved_result_are_required(self):
        changes = [{'resultKind': 'VERIFIED'}, {'resultCode': 'STALE_OBSERVATION'}, {'acceptedWrites': 2},
                   {'finalStateObserved': False}, {'counterVerified': False}, {'savedTextVerified': False}]
        for change in changes:
            value = segment()
            value['active'].update(change)
            with self.assertRaisesRegex(ValueError, 'active_task'):
                stress.validate_segment(value, 0)
        value = segment()
        value.update(status='failed', stop=None, recovery=None)
        value['active'].update(status='failed', resultKind='FAILED', resultCode='STALE_OBSERVATION', acceptedWrites=1)
        stress.validate_segment(value, 0)

    def test_stop_requires_zero_dispatch_even_if_events_are_reordered(self):
        value = segment()
        value['stop']['trace'].reverse()
        stress.validate_segment(value, 0)
        value['stop']['trace'].append(event('DEVICE_DISPATCH_ATTEMPT', value['stop']['startedNanos'] + 1))
        with self.assertRaisesRegex(ValueError, 'zero_dispatches'):
            stress.validate_segment(value, 0)
        for change in ({'resultKind': 'FAILED'}, {'resultCode': 'CONFIGURATION_CHANGED'}, {'acceptedWrites': 1},
                       {'approvalReached': False}, {'counterVerified': False}):
            value = segment()
            value['stop'].update(change)
            with self.assertRaises(ValueError):
                stress.validate_segment(value, 0)

    def test_stop_events_have_unique_coherent_timing_and_zero_invalidated_count(self):
        for mutation in (lambda p: p.update(stopEnqueuedNanos=-1),
                         lambda p: p.update(stopStateAppliedNanos=p['endedNanos']),
                         lambda p: p['trace'].append(copy.deepcopy(p['trace'][0])),
                         lambda p: p['trace'][1].update(dispatchAttempts=1),
                         lambda p: p['trace'][1].update(startNanos=p['trace'][0]['startNanos'] - 1)):
            value = segment(); mutation(value['stop'])
            with self.assertRaises(ValueError):
                stress.validate_segment(value, 0)

    def test_recovery_duration_states_and_empty_trace_are_not_optional(self):
        for side in ('stateBefore', 'stateAfter'):
            for flag in stress.STATE_FLAGS:
                value = segment()
                value['recovery'][side][flag] = not value['recovery'][side][flag]
                with self.assertRaisesRegex(ValueError, 'wrong_state'):
                    stress.validate_segment(value, 0)
        for mutation in (lambda p: p.update(stateAfter=None),
                         lambda p: p.update(endedNanos=p['startedNanos'] + 9_999_999_999),
                         lambda p: p['trace'].append(event('SNAPSHOT_OBSERVE', p['startedNanos'] + 1))):
            value = segment(); mutation(value['recovery'])
            with self.assertRaises(ValueError):
                stress.validate_segment(value, 0)

    def test_phase_and_state_boundaries_cannot_overlap_or_escape_segment(self):
        for mutation in (lambda x: x['stop'].update(startedNanos=x['active']['endedNanos'] - 1),
                         lambda x: x['active'].update(startedNanos=x['startedNanos'] - 1),
                         lambda x: x['recovery']['stateBefore'].update(checkedNanos=x['recovery']['startedNanos'] + 1),
                         lambda x: x['recovery']['stateAfter'].update(checkedNanos=x['endedNanos'] + 1)):
            value = segment(); mutation(value)
            with self.assertRaises(ValueError):
                stress.validate_segment(value, 0)

    def test_sparse_short_or_lost_resource_samples_cannot_pass(self):
        for mutation in (lambda x: x.update(samples=x['samples'][:9]),
                         lambda x: x.update(samples=x['samples'][:10]),
                         lambda x: x.update(sampleLimitReached=True),
                         lambda x: x['samples'][-1].update(processCpuMillis=0),
                         lambda x: x['samples'][0].update(elapsedRealtimeNanos=x['startedNanos'] - 1),
                         lambda x: x['samples'][-1].update(collectionNanos=1_000_000_000),
                         lambda x: x['samples'][0].update(phase='private_text'),
                         lambda x: x['samples'][0].update(totalPssKiB=True)):
            value = segment(); mutation(value)
            with self.assertRaises(ValueError):
                stress.validate_segment(value, 0)
        value = segment();value.update(status='failed', samples=[], active=None, stop=None, recovery=None)
        stress.validate_segment(value, 0)

    def test_trace_caps_and_numeric_fields_are_strict_even_for_failures(self):
        for mutation in (lambda p: p.update(traceCapacity=4095), lambda p: p.update(traceOverwrittenEvents=1),
                         lambda p: p['trace'][0].update(stage='HTTP_BODY'),
                         lambda p: p['trace'][0].update(startNanos=p['startedNanos'] - 1),
                         lambda p: p['trace'][0].update(nodeCount=301)):
            value = segment();mutation(value['active'])
            with self.assertRaises(ValueError):
                stress.validate_segment(value, 0)
        value = segment();value.update(status='failed', stop=None, recovery=None)
        value['active'].update(status='failed', traceOverwrittenEvents=5)
        stress.validate_segment(value, 0)

    def test_manifest_identity_timing_counts_and_paths_are_bound(self):
        for mutation in (lambda x: x.update(kind=legacy.KIND), lambda x: x.update(schemaVersion=True),
                         lambda x: x.update(budgetAcceptance=True), lambda x: x.update(secret='no'),
                         lambda x: x['device'].update(sdkInt=36),
                         lambda x: x['conditions'].update(minimumDurationMillis=60000),
                         lambda x: x['segments'][0].update(path='../segment-000.json'),
                         lambda x: x['segments'][0].update(index=True),
                         lambda x: x['segments'][0].update(bytes=stress.MAX_SEGMENT_BYTES + 1),
                         lambda x: x['segments'][0].update(sha256='b'),
                         lambda x: x['segments'].pop(),
                         lambda x: x.update(endedNanos=x['startedNanos'] + 1)):
            value = report();mutation(value)
            with self.assertRaises(ValueError):
                stress.validate_report(value, 37)

    def test_rehashed_cross_segment_cpu_regression_is_rejected(self):
        values = [segment(i) for i in range(30)]
        for sample in values[1]['samples']:
            sample['processCpuMillis'] -= 10000
        with tempfile.TemporaryDirectory() as temp:
            value = write_bundle(temp, segments=values)
            with self.assertRaisesRegex(ValueError, 'cross_segment'):
                stress.validate_bundle(temp, value, 37)

    def test_bundle_ref_hash_length_exact_set_and_symlink_are_checked(self):
        for change in ('hash', 'size', 'missing', 'extra', 'symlink', 'directory', 'root'):
            with self.subTest(change=change), tempfile.TemporaryDirectory() as temp:
                root = Path(temp);value = write_bundle(root)
                target = root / stress.segment_name(0)
                if change == 'hash':
                    raw = target.read_bytes();target.write_bytes(raw.replace(b'MODEL_REPORT', b'MODEL_REPORX'))
                elif change == 'size':target.write_bytes(target.read_bytes() + b' ')
                elif change == 'missing':target.unlink()
                elif change == 'extra':(root / 'uncommitted.tmp').write_text('{}')
                elif change == 'symlink':target.unlink();target.symlink_to(root / stress.segment_name(1))
                elif change == 'directory':target.unlink();target.mkdir()
                else:(root / stress.REPORT_NAME).write_text('{}')
                with self.assertRaises(ValueError):stress.validate_bundle(root, value, 37)

    def test_capture_keeps_incomplete_failure_and_exact_source(self):
        for status in stress.STATUSES:
            value = report();value['status'] = status
            if status == 'incomplete':value['endedNanos'] = -1
            capture, unused = collected_capture(value)
            stress.validate_capture(capture, '37.0', SOURCE)
        for change in ({'kind': legacy.CAPTURE_KIND}, {'budgetAcceptance': True},
                       {'matrixLabel': '36'}, {'sourceRun': dict(SOURCE, attempt=3)}):
            capture, unused = collected_capture(report());capture.update(change)
            with self.assertRaises(ValueError):stress.validate_capture(capture, '37.0', SOURCE)

    def test_collect_fetches_only_checkpoint_listed_segments_and_preserves_status(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);device = root / 'device';out = root / 'out';out.mkdir()
            value = write_bundle(device, segments=[segment(0)], status='incomplete')
            (device / 'segment-001.json').write_text('uncommitted bytes not referenced')
            calls = []
            def run(args, **kwargs):
                calls.append((args, kwargs))
                if args[1:3] == ['shell', 'getprop']:
                    kwargs['stdout'].write(b'37\n')
                    return subprocess.CompletedProcess(args, 0)
                kwargs['stdout'].write((device / args[-1].split('/')[-1]).read_bytes())
                return subprocess.CompletedProcess(args, 0)
            with patch.object(stress.subprocess, 'run', side_effect=run):
                capture = stress.collect('37.0', SOURCE, 124, out)
            self.assertEqual('collected', capture['captureStatus'])
            self.assertEqual('incomplete', capture['report']['status'])
            self.assertEqual(124, capture['gradleExitCode'])
            self.assertEqual({stress.REPORT_NAME, 'segment-000.json'}, {p.name for p in out.iterdir()})
            self.assertEqual(3, len(calls))
            self.assertTrue(all(0 < options['timeout'] <= 5 for unused, options in calls))
            self.assertTrue(all('preexec_fn' in options for unused, options in calls))

    def test_collection_timeout_or_bad_hash_never_leaves_a_passed_payload(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp);device=root/'device';out=root/'out';out.mkdir()
            write_bundle(device, segments=[segment(0)], status='failed')
            (device/'segment-000.json').write_text('{}')
            def run(args, **kwargs):
                if args[1:3] == ['shell','getprop']:
                    kwargs['stdout'].write(b'37\n')
                    return subprocess.CompletedProcess(args,0)
                kwargs['stdout'].write((device / args[-1].split('/')[-1]).read_bytes())
                return subprocess.CompletedProcess(args,0)
            with patch.object(stress.subprocess,'run',side_effect=run):capture=stress.collect('37.0',SOURCE,1,out)
            self.assertEqual('invalid',capture['captureStatus']);self.assertIsNone(capture['report']);self.assertFalse(list(out.iterdir()))
            with patch.object(stress.subprocess,'run',side_effect=subprocess.TimeoutExpired('adb',5)):
                capture=stress.collect('37.0',SOURCE,124,out)
            self.assertEqual('unavailable',capture['captureStatus']);self.assertEqual(124,capture['gradleExitCode'])
        with patch.object(stress.time,'monotonic',return_value=60):
            with self.assertRaisesRegex(ValueError,'deadline'):stress.remaining_timeout(60)

    def test_sdk_discovery_is_bounded_before_any_report_fetch(self):
        for data in (b'3' * 33, b'\xff\n', b'36\n'):
            with self.subTest(data=data), tempfile.TemporaryDirectory() as temp:
                calls = []
                def run(args, **kwargs):
                    calls.append(args)
                    self.assertIn('preexec_fn', kwargs)
                    self.assertNotIn('capture_output', kwargs)
                    kwargs['stdout'].write(data)
                    return subprocess.CompletedProcess(args, 0)
                with patch.object(stress.subprocess, 'run', side_effect=run):
                    capture = stress.collect('37.0', SOURCE, 1, temp)
                self.assertEqual('invalid', capture['captureStatus'])
                self.assertEqual(1, len(calls))
                self.assertIsNone(capture['report'])
                self.assertFalse(list(Path(temp).iterdir()))

    def test_json_rejects_duplicate_keys_and_nonfinite_values(self):
        for data in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":Infinity}'):
            with self.assertRaises(ValueError):stress.strict_json(data)


if __name__ == '__main__':
    unittest.main()
