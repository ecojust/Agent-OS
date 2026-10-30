"""Synthetic raw bundles exercise diagnostic evidence boundaries, never device acceptance."""

import copy
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import collect_app_experience_evidence as experience
import experience_trace as trace

SOURCE = dict(repository='ecojust/Agent-OS', runId=123, attempt=2, commit='a' * 40)


def encoded(value):
    return (json.dumps(value, separators=(',', ':')) + '\n').encode()


def identifier(number):
    return f'00000000-0000-4000-8000-{number:012d}'


def probe(number=1, now=1_000_000_000, schema_version=1):
    value = dict(schemaVersion=schema_version, clockDomain='CLOCK_MONOTONIC_System.nanoTime', pid=200 + number,
                 uid=10001, processInstance=identifier(number), activityInstance=identifier(number + 1000),
                 refreshHz=60.0, modeHz=60.0, displayId=0, editorText='', savedText='Saved: empty',
                 drawMatchMethod=experience.DRAW_METHODS[schema_version])
    if schema_version >= 2:
        value.update(sequenceMismatchCount=0, zeroDrawingTimeCount=0)
    value.update({key: False for key in experience.STATUS_BOOL})
    value.update({key: 0 for key in experience.STATUS_INT})
    value.update(activityPresent=True, resumed=True, windowFocused=True, hardwareAccelerated=True,
                 drawFrameObserved=True, createCount=1, startCount=1, resumeCount=1,
                 createdNanoTime=now, startedNanoTime=now, resumedNanoTime=now,
                 drawNanoTime=now + 10_000_000, drawVsyncNs=now + 9_000_000,
                 drawTimeMillis=(now + 9_000_000) // 1_000_000, displayObservedNanoTime=now,
                 activityFrameCount=1, lastFrameVsyncNs=now + 9_000_000, lastFrameCallbackNanoTime=now + 11_000_000)
    if schema_version == 3:
        value.update(focusGainCount=1, focusLossCount=0, firstFocusGainNanoTime=now,
                     lastFocusGainNanoTime=now, lastFocusLossNanoTime=0)
    return value


def state(when, arm, focused=True):
    return dict(checkedNanos=when, servicePresent=arm != experience.ARMS[0], serviceConnected=arm != experience.ARMS[0],
                controllerRunning=False, controlAvailable=False, fixtureFocused=focused)


def sample(when, cpu):
    return dict(elapsedRealtimeNanos=when, processCpuMillis=cpu, totalPssKiB=20000,
                dalvikPssKiB=10000, nativePssKiB=5000, otherPssKiB=5000, collectionNanos=1_000_000)


def launch(index=0, profile='pilot', schema_version=1):
    pair, position, arm, startup_type = experience.launch_identity(index, profile)
    start = 1_000_000_000 + index * 5_000_000_000
    marker_start = start + 1_000_000_000
    cold_count = experience.PROFILES[profile][0] * 2
    after = probe(index + 1 if startup_type == 'cold' else cold_count, marker_start + 1_000_000, schema_version)
    before = None
    if startup_type == 'hot':
        before = copy.deepcopy(after)
        before.update(stopped=True, resumed=False, windowFocused=False, resumeCount=index - cold_count + 1,
                      drawVsyncNs=marker_start - 1_000_000, drawTimeMillis=(marker_start - 1_000_000) // 1_000_000)
        after['resumeCount'] = before['resumeCount'] + 1
        if schema_version == 3:
            before.update(focusGainCount=1, focusLossCount=1, firstFocusGainNanoTime=marker_start - 100_000_000,
                          lastFocusGainNanoTime=marker_start - 100_000_000, lastFocusLossNanoTime=marker_start - 50_000_000)
            after.update(focusGainCount=2, focusLossCount=1, firstFocusGainNanoTime=before['firstFocusGainNanoTime'],
                         lastFocusLossNanoTime=before['lastFocusLossNanoTime'])
    value = dict(schemaVersion=schema_version, kind=experience.LAUNCH_KIND, index=index, pairIndex=pair, position=position,
                arm=arm, startupType=startup_type, status='passed', startedNanos=start, endedNanos=start + 4_000_000_000,
                marker=f'aos.experience.launch.{index}', driverPid=420, expectedPid=after['pid'], expectedStartupType=startup_type,
                preLaunchPidAbsent=startup_type == 'cold', homeStoppedVerified=startup_type == 'hot',
                lifecycleVerified=True, newDrawVerified=True, stateBefore=state(start + 500_000_000, arm, False),
                stateAfter=state(start + 3_500_000_000, arm), beforeLaunch=before, afterLaunch=after,
                launchStartedElapsedNanos=marker_start, launchEndedElapsedNanos=start + 3_000_000_000,
                launchStartedNanoTime=marker_start, launchEndedNanoTime=start + 3_000_000_000,
                amStartOutput='Status: ok\nTotalTime: 0\n', unavailableReason='')
    if schema_version >= 2:
        value.update(homePackage='com.android.launcher3' if startup_type == 'hot' else '',
                     homeFocusedVerified=startup_type == 'hot')
    if schema_version == 3:
        add_readiness(value)
    return value


def readiness_observation(value, index, start, ready=True):
    provider = [value['afterLaunch'][key] for key in experience.READINESS_PROVIDER_FIELDS]
    checked = value['launchEndedNanoTime'] + start - value['launchEndedElapsedNanos']
    connected = value['arm'] != experience.ARMS[0]
    return dict(index=index, startedNanos=start, endedNanos=start + 10_000_000,
                providerBefore=provider + [checked + 1_000_000], providerAfter=provider + [checked + 9_000_000],
                agentBefore=[connected, connected, False, False, start + 2_000_000],
                agentAfter=[connected, connected, False, False, start + 8_000_000],
                a11y=dict(windowCount=2, focusedWindowCount=1, focusedWindowId=3, focusedWindowType=1,
                          rootPresent=True, packageName=experience.TARGET_PACKAGE if ready else 'com.android.launcher3',
                          editorMatchCount=1 if ready else -1, editorVisible=ready, ready=ready,
                          reason='ready' if ready else 'package_mismatch'), reason='')


def add_readiness(value, pending=0):
    start = value['launchEndedElapsedNanos'] + 10_000_000
    observations = [readiness_observation(value, i, start + i * 210_000_000, ready=i == pending)
                    for i in range(pending + 1)]
    end = observations[-1]['endedNanos'] + 1_000_000
    value.update(readinessStartedNanos=start, readinessEndedNanos=end, readinessVerified=True,
                 readinessFailureReason='', readinessObservations=observations)
    value['endedNanos'] = max(value['endedNanos'], end + 1_000_000)
    value['stateAfter'] = state(observations[-1]['agentAfter'][4], value['arm'])
    return value


def trial(index=0, profile='pilot', api=37, schema_version=1):
    arm = experience.trial_arm(index)
    start = 1_000_000_000 + experience.conditions(profile)['launchCount'] * 5_000_000_000 + index * 6_000_000_000
    cpu = 1000 + index * 1000
    before = probe(500 + index, start + 1_000_000_000, schema_version)
    before.update({key: False for key in experience.SESSION_BOOL})
    before.update({key: 0 for key in experience.SESSION_INT})
    before.update({key: 60.0 for key in experience.SESSION_HZ})
    before.update(sessionId=identifier(2000 + index), beforePssKb=15000, afterPssKb=-1,
                  beginCpuMillis=100, beginNanoTime=start + 2_100_000_000,
                  beforePssStartedNanoTime=start + 2_080_000_000, beforePssEndedNanoTime=start + 2_090_000_000,
                  beforeDalvikPssKb=7000, beforeNativePssKb=4000, beforeOtherPssKb=4000, beforeGraphicsSummaryKb=-1)
    after = copy.deepcopy(before)
    after.update(ended=True, displayStable=True, finalFrameObserved=True, drainComplete=True, endCpuMillis=100,
                 endNanoTime=start + 3_000_000_000, afterPssKb=15020, frameCount=1,
                 finalMutationVersion=3, finalDrawVersion=3, finalDrawVsyncNs=start + 2_500_000_000,
                 frameCutoffVsyncNs=start + 2_500_000_000, mutationVersion=3, drawVersion=3,
                 drawVsyncNs=start + 2_500_000_000, drawNanoTime=start + 2_501_000_000, drawTimeMillis=(start + 2_500_000_000) // 1_000_000,
                 afterPssStartedNanoTime=start + 3_010_000_000, afterPssEndedNanoTime=start + 3_020_000_000,
                 afterDalvikPssKb=7000, afterNativePssKb=4000, afterOtherPssKb=4020, afterGraphicsSummaryKb=-1,
                 finalState=True, finalDrawObserved=True, editorText='Controller integration sample',
                 savedText='Saved: Controller integration sample', clicks=1, editCount=1, saveCount=1, incrementCount=1)
    frame = dict(index=0, intendedVsyncNs=start + 2_500_000_000, vsyncNs=start + 2_500_000_000,
                 totalDurationNs=10_000_000, firstDraw=0, gpuDurationNs=-1 if api == 30 else 2_000_000,
                 deadlineNs=-1 if api == 30 else 16_666_667, callbackNanoTime=start + 2_520_000_000,
                 droppedReports=0, refreshHz=60.0, modeHz=60.0, displayId=0, drawVersion=3,
                 drawNanoTime=start + 2_501_000_000, crossesBegin=False, refreshObservedNanoTime=start + 2_000_000_000)
    agent = arm == experience.ARMS[2]
    return dict(schemaVersion=schema_version, kind=experience.TRIAL_KIND, index=index, repetition=index // 3, position=index % 3,
                arm=arm, status='passed', startedNanos=start, endedNanos=start + 5_000_000_000,
                sessionId=before['sessionId'], initialStateVerified=True, editVerified=True, savedTextVerified=True,
                counterVerified=True, drawingVerified=True, cleanupCompleted=True,
                stateBefore=state(start + 2_000_000_000, arm), stateAfter=state(start + 4_000_000_000, arm),
                agentBeforeSample=sample(start + 2_010_000_000, cpu), agentAfterSample=sample(start + 3_100_000_000, cpu + 20),
                agentTaskStartedNanos=start + 2_200_000_000, agentTaskEndedNanos=start + 3_050_000_000,
                agentTaskStartedCpuMillis=cpu + 10, agentTaskEndedCpuMillis=cpu + 10,
                targetBefore=before, targetAfter=after, frames=[frame],
                framePageChecks=[dict(offset=0, nextOffset=1, totalFrames=1, drainComplete=True, lateFrameReports=0, lateDroppedReports=0)],
                manualActionsAttempted=0 if agent else 3,
                manualActionsAccepted=0 if agent else 3, modelRequests=4 if agent else 0, modelAcceptedWrites=3 if agent else 0,
                approvalCount=3 if agent else 0, resultKind='MODEL_REPORT' if agent else '', resultCode='MODEL_REPORT' if agent else '')


def report(api=37, profile='pilot', launches=None, trials=None, status='passed', schema_version=1):
    launches = [launch(i, profile, schema_version) for i in range(experience.conditions(profile)['launchCount'])] if launches is None else launches
    trials = [trial(i, profile, api, schema_version) for i in range(experience.conditions(profile)['trialCount'])] if trials is None else trials
    def refs(values, namer):
        result = []
        for value in values:
            data = encoded(value)
            ref = dict(index=value['index'], path=namer(value['index']), bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                       status=value['status'], startedNanos=value['startedNanos'], endedNanos=value['endedNanos'])
            if namer == experience.launch_name:
                ref.update({key: value[key] for key in ('marker', 'expectedPid', 'expectedStartupType', 'driverPid')})
            result.append(ref)
        return result
    return dict(schemaVersion=schema_version, kind=experience.KIND, diagnosticOnly=True, budgetAcceptance=False, profile=profile,
                status=status, appProcessPid=420, targetPackage=experience.TARGET_PACKAGE,
                device=dict(sdkInt=api, manufacturer='synthetic', model='unit-test', hardware='synthetic',
                            fingerprint='synthetic-fixture', emulatorHeuristic=True), conditions=experience.conditions(profile, schema_version),
                startedNanos=1_000_000_000, endedNanos=-1 if status == 'incomplete' else (trials or launches or [dict(endedNanos=1_000_000_000)])[-1]['endedNanos'] + 1,
                cleanup={key: status != 'incomplete' for key in experience.CLEANUP},
                launches=refs(launches, experience.launch_name), trials=refs(trials, experience.trial_name))


def write_bundle(directory, api=37, profile='pilot', launches=None, trials=None, status='passed', with_trace=True, schema_version=1, trace_config_version=None):
    directory = Path(directory); directory.mkdir(parents=True, exist_ok=True)
    launches = [launch(i, profile, schema_version) for i in range(experience.conditions(profile)['launchCount'])] if launches is None else launches
    trials = [trial(i, profile, api, schema_version) for i in range(experience.conditions(profile)['trialCount'])] if trials is None else trials
    value = report(api, profile, launches, trials, status, schema_version)
    for item in launches:
        (directory / experience.launch_name(item['index'])).write_bytes(encoded(item))
    for item in trials:
        (directory / experience.trial_name(item['index'])).write_bytes(encoded(item))
    (directory / experience.REPORT_NAME).write_bytes(encoded(value))
    if with_trace:
        write_trace(directory, value, config_version=trace_config_version)
    return value


def write_trace(directory, value, mutate=None, config_version=None):
    directory = Path(directory)
    trace_version = config_version if config_version is not None else value['schemaVersion'] if value['schemaVersion'] >= 2 else 1
    derived_version = 2 if trace_version >= 2 else 1
    raw_trace = b'SYNTHETIC UNIT TEST TRACE: not device evidence\n'
    zipped = gzip.compress(raw_trace, mtime=0)
    (directory / 'trace.pftrace.gz').write_bytes(zipped)
    capture = dict(schemaVersion=trace_version + 1, kind='agent-os-experience-trace-capture', status='collected', pid=900,
                   configDelivery='stdin', startStdout='900\n', startStderr='', startExitCode=0,
                   stopped=True, ready=True, startMode='--background-wait', config=trace.trace_config(version=trace_version), error=None,
                   trace=dict(path='trace.pftrace', bytes=len(raw_trace), sha256=hashlib.sha256(raw_trace).hexdigest()),
                   compressed=dict(path='trace.pftrace.gz', bytes=len(zipped), sha256=hashlib.sha256(zipped).hexdigest()))
    if trace_version >= 2:
        capture['configVersion'] = trace_version
    (directory / 'trace-capture.json').write_bytes(encoded(capture))
    raw = dict(markers=[], startups=[], stats=[], traceStartNanos=1, traceEndNanos=10 ** 15)
    for ref in value['launches']:
        start = ref['startedNanos'] + 1_000_000_000
        pid, index = ref['expectedPid'], ref['index']
        raw['markers'].append(dict(name=ref['marker'], ts=start, dur=2_000_000_000, pid=420))
        raw['startups'].append(dict(startupId=index, ts=start + 100_000_000, tsEnd=start + 900_000_000,
                                  startupType=ref['expectedStartupType'], processCount=1, startupPid=pid, startupUpid=pid,
                                  ttid=500_000_000, ttidFrameId=index, ttidUpid=pid, ttidPid=pid, drawFrameId=1000 + index,
                                  drawTs=start + 400_000_000, drawDur=200_000_000, uiTs=start + 300_000_000,
                                  uiDur=150_000_000, uiPid=pid, uiTid=pid, renderPid=pid, renderThreadName='RenderThread'))
    if mutate:
        mutate(raw)
    stream = io.StringIO(); writer = csv.writer(stream); writer.writerow(['result']); writer.writerow([encoded(raw).hex().upper()])
    data = stream.getvalue().encode(); (directory / 'startup-ttid.csv').write_bytes(data)
    rows = trace.derive_measurements(raw, value)
    derived = dict(schemaVersion=derived_version, kind=trace.KIND, diagnosticOnly=True, budgetAcceptance=False,
                   status='passed' if all(row['status'] == 'passed' for row in rows) else 'failed',
                   trace=trace.trace_identity(directory / 'trace.pftrace.gz'),
                   processor=trace.processor_identity(derived_version),
                   package=experience.TARGET_PACKAGE, launches=rows, traceErrors=raw['stats'],
                   extraction=dict(path='startup-ttid.csv', bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                                   sqlSha256=hashlib.sha256(trace.extraction_sql().encode()).hexdigest()))
    (directory / 'startup-ttid.json').write_bytes(encoded(derived))
    return derived


def collected_capture(directory, value, label='37.0', exit_code=0):
    directory = Path(directory)
    payloads = experience.validate_bundle(directory, value, int(label.split('.')[0]), value['profile'])
    capture = experience.empty_capture(label, SOURCE, value['profile'])
    capture.update(actualSdkInt=int(label.split('.')[0]), gradleExitCode=exit_code,
                   report=experience.file_reference(directory / experience.REPORT_NAME, value['status']),
                   payloads=[dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()) for name, data in sorted(payloads.items())],
                   captureStatus=experience.capture_status(value, payloads),
                   diagnosticStatus=experience.diagnostic_status(value, payloads, exit_code))
    capture['captureError'] = None if capture['captureStatus'] == 'collected' else 'startup_trace_derivation_unavailable'
    return capture


class AppExperienceEvidenceTests(unittest.TestCase):
    def test_pilot_and_formal_have_exact_orders_and_raw_denominators(self):
        for profile, counts in [('pilot', (8, 3)), ('formal', (400, 18))]:
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as temporary:
                value = write_bundle(temporary, profile=profile)
                original = copy.deepcopy(value)
                capture = collected_capture(temporary, value)
                payloads = experience.validate_payloads(temporary, capture, '37.0', SOURCE, profile)
                self.assertEqual(counts, (len(value['launches']), len(value['trials'])))
                self.assertEqual(sum(counts) + 5, len(payloads))
                self.assertEqual('passed', capture['diagnosticStatus'])
                self.assertEqual(original, value)
                other = 'formal' if profile == 'pilot' else 'pilot'
                with self.assertRaises(ValueError): experience.validate_report(value, 37, other)

    def test_v3_readiness_keeps_pending_reads_outside_the_original_startup_measurement(self):
        for profile in ('pilot', 'formal'):
            with self.subTest(profile=profile), tempfile.TemporaryDirectory() as temporary:
                launches = [launch(i, profile, 3) for i in range(experience.conditions(profile)['launchCount'])]
                marker = launches[0]['launchEndedElapsedNanos']
                original = copy.deepcopy(launches[0]['afterLaunch'])
                add_readiness(launches[0], pending=2)
                value = write_bundle(temporary, profile=profile, schema_version=3, launches=launches)
                capture = collected_capture(temporary, value)
                payloads = experience.validate_payloads(temporary, capture, '37.0', SOURCE, profile)
                self.assertEqual('passed', capture['diagnosticStatus'])
                self.assertEqual([False, False, True], [x['a11y']['ready'] for x in launches[0]['readinessObservations']])
                self.assertEqual(marker, launches[0]['launchEndedElapsedNanos'])
                self.assertEqual(original, launches[0]['afterLaunch'])
                self.assertEqual(500_000_000, json.loads(payloads['startup-ttid.json'])['launches'][0]['ttidNanos'])
                self.assertEqual(4, json.loads(payloads['trace-capture.json'])['schemaVersion'])
        for mutate in (lambda x: x.update(schemaVersion=2),
                       lambda x: x['conditions'].update(a11yReadinessMaxObservations=15),
                       lambda x: x['conditions'].update(a11yReadinessTimeoutMillis=3001),
                       lambda x: x['conditions'].update(a11yReadinessIntervalMillis=True)):
            value = report(schema_version=3); mutate(value)
            with self.assertRaises(ValueError): experience.validate_report(value, 37)
        value = launch(schema_version=3)
        for key in experience.FOCUS_FIELDS:
            invalid = copy.deepcopy(value); invalid['afterLaunch'].pop(key)
            with self.assertRaises(ValueError): experience.validate_launch(invalid, 0, report(schema_version=3))
        value = launch(schema_version=2); value.update({key: launch(schema_version=3)[key] for key in experience.READINESS_FIELDS})
        with self.assertRaises(ValueError): experience.validate_launch(value, 0, report(schema_version=2))

    def test_v3_partial_reads_preserve_the_real_prefix_without_inventing_ready(self):
        order = ('providerBefore', 'agentBefore', 'a11y', 'agentAfter', 'providerAfter')
        for retained in range(6):
            with self.subTest(retained=retained):
                value = launch(schema_version=3)
                value.update(status='failed', readinessVerified=False, readinessFailureReason='observation_failed')
                observation = value['readinessObservations'][0]
                observation['reason'] = 'observation_failed'
                for key in order[retained:]: observation[key] = None
                agent = observation['agentAfter'] or observation['agentBefore']
                value['stateAfter'] = (dict(zip(experience.STATE_FLAGS[:4], agent[:4])) if agent else None)
                if agent:
                    value['stateAfter'].update(checkedNanos=agent[4], fixtureFocused=bool(observation['a11y'] and observation['a11y']['ready']))
                experience.validate_launch(value, 0, report(schema_version=3))
                invalid = copy.deepcopy(value); invalid.update(status='passed', readinessVerified=True, readinessFailureReason='')
                invalid['readinessObservations'][0]['reason'] = ''
                if retained < 5:
                    with self.assertRaises(ValueError): experience.validate_launch(invalid, 0, report(schema_version=3))
        value = launch(schema_version=3); value['status'] = 'failed'
        experience.validate_launch(value, 0, report(schema_version=3))  # A later outer sequence check can fail.
        value = launch(schema_version=3)
        value.update(status='failed', readinessStartedNanos=-1, readinessEndedNanos=-1, readinessVerified=False,
                     readinessFailureReason='', readinessObservations=[], stateAfter=None)
        experience.validate_launch(value, 0, report(schema_version=3))
        value['readinessVerified'] = True
        with self.assertRaises(ValueError): experience.validate_launch(value, 0, report(schema_version=3))

    def test_v3_real_guard_failures_are_retained_and_cannot_be_relabelled_ready(self):
        reasons = ('provider_identity_changed', 'provider_lifecycle_changed', 'provider_focus_changed',
                   'provider_not_resumed_focused', 'agent_state_changed')
        for side in ('Before', 'After'):
            for reason in reasons:
                with self.subTest(side=side, reason=reason):
                    value = launch(schema_version=3)
                    value.update(status='failed', readinessVerified=False, readinessFailureReason=reason)
                    observation = value['readinessObservations'][0]; observation['reason'] = reason
                    provider, agent = observation['provider' + side], observation['agent' + side]
                    if reason == reasons[0]: provider[0] += 1
                    elif reason == reasons[1]: provider[4] += 1
                    elif reason == reasons[2]:
                        provider[11] += 1; provider[14] = provider[18]; provider[17] = False
                    elif reason == reasons[3]: provider[15] = False
                    else: agent[0] = not agent[0]
                    if side == 'Before':
                        observation.update(a11y=None, agentAfter=None, providerAfter=None)
                    last_agent = observation['agentAfter'] or observation['agentBefore']
                    value['stateAfter'] = dict(zip(experience.STATE_FLAGS[:4], last_agent[:4]))
                    value['stateAfter'].update(checkedNanos=last_agent[4], fixtureFocused=side == 'After')
                    experience.validate_launch(value, 0, report(schema_version=3))
                    invalid = copy.deepcopy(value); invalid.update(status='passed', readinessVerified=True, readinessFailureReason='')
                    invalid['readinessObservations'][0]['reason'] = ''
                    with self.assertRaises(ValueError): experience.validate_launch(invalid, 0, report(schema_version=3))
                    invalid = copy.deepcopy(value); invalid['readinessObservations'].append(readiness_observation(value, 1, observation['endedNanos'] + 200_000_000))
                    with self.assertRaises(ValueError): experience.validate_launch(invalid, 0, report(schema_version=3))

    def test_v3_focus_loss_before_anchor_cannot_pass_after_recovery_but_resource_scope_is_unchanged(self):
        for index in (0, 4):
            value = launch(index, schema_version=3)
            after = value['afterLaunch']; when = value['launchStartedNanoTime']
            after.update(focusGainCount=after['focusGainCount'] + 1, focusLossCount=after['focusLossCount'] + 1,
                         lastFocusLossNanoTime=when + 2_000_000, lastFocusGainNanoTime=when + 3_000_000)
            add_readiness(value)
            with self.assertRaisesRegex(ValueError, 'guard'): experience.validate_launch(value, index, report(schema_version=3))
            value.update(status='failed', readinessVerified=False, readinessFailureReason='provider_focus_changed')
            observation = value['readinessObservations'][0]
            observation.update(reason='provider_focus_changed', a11y=None, agentAfter=None, providerAfter=None)
            value['stateAfter'] = state(observation['agentBefore'][4], value['arm'], False)
            experience.validate_launch(value, index, report(schema_version=3))
        value = trial(schema_version=3)
        value['targetAfter'].update(focusGainCount=2, focusLossCount=1,
                                  lastFocusLossNanoTime=value['targetAfter']['endNanoTime'] - 1,
                                  lastFocusGainNanoTime=value['targetAfter']['endNanoTime'])
        experience.validate_trial(value, 0, report(schema_version=3))

    def test_v3_timeout_preserves_late_reads_but_never_accepts_late_success(self):
        for crossed in (False, True):
            value = launch(schema_version=3)
            deadline = value['readinessStartedNanos'] + 3_000_000_000
            observation = value['readinessObservations'][0]
            if crossed:
                observation['endedNanos'] = deadline + 100
                observation['agentAfter'][4] = deadline + 99
                value['stateAfter']['checkedNanos'] = deadline + 99
            else:
                observation['a11y'].update(editorVisible=False, ready=False, reason='editor_not_visible')
                value['stateAfter']['fixtureFocused'] = False
            value.update(status='failed', readinessVerified=False, readinessFailureReason='a11y_timeout',
                         readinessEndedNanos=deadline + 101, endedNanos=deadline + 102)
            observation['reason'] = 'a11y_timeout'
            experience.validate_launch(value, 0, report(schema_version=3))
            invalid = copy.deepcopy(value); invalid.update(status='passed', readinessVerified=True, readinessFailureReason='')
            invalid['readinessObservations'][0]['reason'] = ''
            with self.assertRaises(ValueError): experience.validate_launch(invalid, 0, report(schema_version=3))
        value = launch(schema_version=3)
        value['readinessEndedNanos'] = value['readinessStartedNanos'] + 3_000_000_000
        value['endedNanos'] = value['readinessEndedNanos'] + 1
        with self.assertRaisesRegex(ValueError, 'verified_readiness'): experience.validate_launch(value, 0, report(schema_version=3))
        value['readinessEndedNanos'] -= 1
        experience.validate_launch(value, 0, report(schema_version=3))

    def test_v3_clock_domains_are_independent_and_read_order_is_bounded(self):
        value = launch(schema_version=3); add_readiness(value, pending=2)
        # Suspend time offsets BOOTTIME from MONOTONIC. Never compare the two clocks directly.
        offset = 10_000_000_000
        for key in ('startedNanos', 'endedNanos', 'launchStartedElapsedNanos', 'launchEndedElapsedNanos',
                    'readinessStartedNanos', 'readinessEndedNanos'): value[key] += offset
        for key in ('stateBefore', 'stateAfter'): value[key]['checkedNanos'] += offset
        for observation in value['readinessObservations']:
            observation['startedNanos'] += offset; observation['endedNanos'] += offset
            observation['agentBefore'][4] += offset; observation['agentAfter'][4] += offset
        experience.validate_launch(value, 0, report(schema_version=3))
        mutations = (
            lambda x: x.update(readinessStartedNanos=x['launchEndedElapsedNanos'] - 1),
            lambda x: x['readinessObservations'][1].update(index=0),
            lambda x: x['readinessObservations'][1].update(startedNanos=x['readinessObservations'][0]['endedNanos'] + 199_999_999),
            lambda x: x['readinessObservations'][1]['providerBefore'].__setitem__(18, x['launchEndedNanoTime'] - 1),
            lambda x: x['readinessObservations'][1]['agentAfter'].__setitem__(4, x['readinessObservations'][1]['agentBefore'][4] - 1),
            lambda x: x['readinessObservations'][0]['providerBefore'].__setitem__(15, 1),
            lambda x: x['readinessObservations'][0]['agentBefore'].__setitem__(0, 0),
            lambda x: x['readinessObservations'][0].update(providerBefore=None),
            lambda x: x['readinessObservations'][0].update(a11y=copy.deepcopy(x['readinessObservations'][-1]['a11y'])),
            lambda x: x['stateAfter'].update(checkedNanos=x['stateAfter']['checkedNanos'] + 1),
            lambda x: x['readinessObservations'].extend([x['readinessObservations'][-1]] * 15),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                invalid = copy.deepcopy(value); mutate(invalid)
                with self.assertRaises(ValueError): experience.validate_launch(invalid, 0, report(schema_version=3))

    def test_v3_a11y_reason_is_recomputed_from_actual_window_and_editor_observations(self):
        base = readiness_observation(launch(schema_version=3), 0, 4_010_000_000)['a11y']
        cases = (
            dict(windowCount=-1, focusedWindowCount=0, focusedWindowId=-1, focusedWindowType=-1,
                 rootPresent=False, packageName='', editorMatchCount=-1, editorVisible=False, ready=False, reason='windows_unavailable'),
            dict(windowCount=0, focusedWindowCount=0, focusedWindowId=-1, focusedWindowType=-1,
                 rootPresent=False, packageName='', editorMatchCount=-1, editorVisible=False, ready=False, reason='focused_window_count'),
            dict(focusedWindowType=2, rootPresent=False, packageName='', editorMatchCount=-1, editorVisible=False, ready=False, reason='focused_window_not_application'),
            dict(rootPresent=False, packageName='', editorMatchCount=-1, editorVisible=False, ready=False, reason='root_missing'),
            dict(packageName='com.android.launcher3', editorMatchCount=-1, editorVisible=False, ready=False, reason='package_mismatch'),
            dict(editorMatchCount=0, editorVisible=False, ready=False, reason='editor_match_count'),
            dict(editorVisible=False, ready=False, reason='editor_not_visible'),
            dict(),
        )
        for changes in cases:
            observation = dict(base, **changes); experience.validate_a11y(observation)
            invalid = dict(observation, ready=not observation['ready'])
            with self.assertRaises(ValueError): experience.validate_a11y(invalid)
        for changes in (dict(windowCount=True), dict(editorMatchCount=1.0), dict(ready=1),
                        dict(rootPresent=False), dict(focusedWindowCount=3), dict(packageName='x' * 257)):
            with self.assertRaises(ValueError): experience.validate_a11y(dict(base, **changes))

    def test_v3_all_seven_lifecycle_counters_are_guarded_including_same_identity_new_intent(self):
        for offset in range(3, 10):
            value = launch(schema_version=3)
            observation = value['readinessObservations'][0]
            self.assertEqual(19, len(observation['providerBefore']))
            observation['providerBefore'][offset] += 1
            value.update(status='failed', readinessVerified=False, readinessFailureReason='provider_lifecycle_changed')
            observation.update(reason='provider_lifecycle_changed', a11y=None, agentAfter=None, providerAfter=None)
            value['stateAfter'] = state(observation['agentBefore'][4], value['arm'], False)
            experience.validate_launch(value, 0, report(schema_version=3))
            invalid = copy.deepcopy(value); invalid.update(status='passed', readinessVerified=True, readinessFailureReason='')
            invalid['readinessObservations'][0]['reason'] = ''
            with self.assertRaises(ValueError): experience.validate_launch(invalid, 0, report(schema_version=3))
        value = launch(schema_version=3)
        value['afterLaunch']['focusGainCount'] = True
        with self.assertRaises(ValueError): experience.validate_launch(value, 0, report(schema_version=3))

    def test_v3_ready_accessibility_does_not_upgrade_an_unavailable_startup(self):
        value = launch(schema_version=3)
        value.update(status='unavailable', unavailableReason='unresolved_draw_observation')
        value['afterLaunch'].update(unresolvedDrawCount=1, sequenceMismatchCount=1)
        experience.validate_launch(value, 0, report(schema_version=3))
        with tempfile.TemporaryDirectory() as temporary:
            root = write_bundle(temporary, schema_version=3, launches=[value], trials=[], status='failed')
            capture = collected_capture(temporary, root, exit_code=1)
            payloads = experience.validate_payloads(temporary, capture, '37.0', SOURCE, 'pilot')
            self.assertEqual('failed', capture['diagnosticStatus'])
            self.assertIsNone(json.loads(payloads['startup-ttid.json'])['launches'][0]['ttidNanos'])
            self.assertTrue(json.loads(payloads['launch-000.json'])['readinessVerified'])

    def test_v3_controlled_capacity_fits_without_increasing_the_launch_cap(self):
        # Capacity envelope, not a valid 16-read time sequence: the 3 s deadline can end it earlier.
        value = launch(399, profile='formal', schema_version=3); add_readiness(value, pending=15)
        value['amStartOutput'] = 'x' * 8192
        for observation in value['readinessObservations'][:-1]:
            observation['a11y']['packageName'] = 'a' * 256
        # Long device uptime: preserve timestamp relationships, canonical UUIDs and actual counter widths.
        shift = 10 ** 18
        def timestamp_widths(item):
            if type(item) is dict:
                for key, child in item.items():
                    if type(child) is int and child > 0 and key.endswith(('Nanos', 'NanoTime', 'Ns')):
                        item[key] += shift
                    else: timestamp_widths(child)
            elif type(item) is list:
                for child in item: timestamp_widths(child)
        timestamp_widths(value)
        for observation in value['readinessObservations']:
            for side in ('Before', 'After'):
                for offset in (12, 13, 14, 18):
                    if observation['provider' + side][offset] > 0: observation['provider' + side][offset] += shift
                observation['agent' + side][4] += shift
        self.assertLessEqual(len(encoded(value)), experience.MAX_LAUNCH_BYTES)
        self.assertEqual(32768, experience.MAX_LAUNCH_BYTES)
        # The byte cap remains authoritative for unusually escape-heavy output too.
        value['amStartOutput'] = '\0' * 8192
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'launch-399.json'; path.write_bytes(encoded(value))
            with self.assertRaises(ValueError): experience.read_bounded(path, experience.MAX_LAUNCH_BYTES)

    def test_raw_v2_home_preparation_and_probe_causes_are_versioned(self):
        for profile in ('pilot', 'formal'):
            with tempfile.TemporaryDirectory() as temporary:
                value = write_bundle(temporary, profile=profile, schema_version=2)
                capture = collected_capture(temporary, value)
                experience.validate_payloads(temporary, capture, '37.0', SOURCE, profile)
                self.assertEqual(1000, value['conditions']['homeSettleMillis'])
        for mutate in (lambda value: value['conditions'].update(homeSettleMillis=999),
                       lambda value: value['conditions'].pop('homePreparation'),
                       lambda value: value.update(schemaVersion=1)):
            value = report(schema_version=2); mutate(value)
            with self.assertRaises(ValueError): experience.validate_report(value, 37)
        value = report(); value['conditions'].update(homeSettleMillis=1000)
        with self.assertRaises(ValueError): experience.validate_report(value, 37)
        for index in (0, 4):
            for mutate in (lambda value: value['afterLaunch'].update(schemaVersion=1),
                           lambda value: value['afterLaunch'].update(drawMatchMethod=experience.DRAW_METHODS[1]),
                           lambda value: value['afterLaunch'].update(sequenceMismatchCount=True),
                           lambda value: value['afterLaunch'].update(sequenceMismatchCount=1),
                           lambda value: value.update(schemaVersion=1)):
                value = launch(index, schema_version=2); mutate(value)
                with self.assertRaises(ValueError): experience.validate_launch(value, index, report(schema_version=2))
        for mutate in (lambda value: value.update(homePackage=''),
                       lambda value: value.update(homePackage='../launcher'),
                       lambda value: value.update(homeFocusedVerified=False)):
            value = launch(4, schema_version=2); mutate(value)
            with self.assertRaises(ValueError): experience.validate_launch(value, 4, report(schema_version=2))
        value = launch(0, schema_version=2); value['homeFocusedVerified'] = True
        with self.assertRaises(ValueError): experience.validate_launch(value, 0, report(schema_version=2))
        value = launch(); value['afterLaunch'].update(sequenceMismatchCount=0, zeroDrawingTimeCount=0)
        with self.assertRaises(ValueError): experience.validate_launch(value, 0, report())
        value = trial(schema_version=2); value['targetAfter']['zeroDrawingTimeCount'] = 1
        with self.assertRaisesRegex(ValueError, 'cause_total'): experience.validate_trial(value, 0, report(schema_version=2))

    def test_v2_unresolved_reason_retains_a_real_new_frame_without_passing(self):
        for index in (0, 4):
            value = launch(index, schema_version=2)
            value.update(status='unavailable', newDrawVerified=True, unavailableReason='unresolved_draw_observation')
            value['afterLaunch'].update(unresolvedDrawCount=1, sequenceMismatchCount=1)
            experience.validate_launch(value, index, report(schema_version=2))
            for changes in (dict(newDrawVerified=False), dict(status='passed', unavailableReason=''),
                            dict(unavailableReason='no_new_draw_frame_report')):
                invalid = copy.deepcopy(value); invalid.update(changes)
                with self.assertRaises(ValueError): experience.validate_launch(invalid, index, report(schema_version=2))
            value['afterLaunch'].update(unresolvedDrawCount=0, sequenceMismatchCount=0)
            with self.assertRaises(ValueError): experience.validate_launch(value, index, report(schema_version=2))
        value = launch(4, schema_version=2)
        value.update(status='unavailable', newDrawVerified=False, unavailableReason='no_new_draw_frame_report')
        value['afterLaunch']['drawFrameObserved'] = False
        experience.validate_launch(value, 4, report(schema_version=2))
        legacy = launch(4); legacy.update(status='unavailable', newDrawVerified=True,
                                         unavailableReason='unresolved_draw_observation')
        with self.assertRaises(ValueError): experience.validate_launch(legacy, 4, report())

    def test_failed_and_incomplete_prefix_never_promoted(self):
        for status in ('failed', 'incomplete'):
            with tempfile.TemporaryDirectory() as temporary:
                value = write_bundle(temporary, launches=[launch()], trials=[], status=status)
                capture = collected_capture(temporary, value, exit_code=124)
                experience.validate_payloads(temporary, capture, '37.0', SOURCE)
                self.assertEqual(status, capture['diagnosticStatus'])
                value['status'] = 'passed'
                with self.assertRaises(ValueError): experience.validate_report(value, 37)
        value = report(); value['launches'][2]['status'] = 'failed'
        value['status'] = 'failed'
        with self.assertRaisesRegex(ValueError, 'prefix'): experience.validate_report(value, 37)

    def test_unavailable_draw_retains_lifecycle_and_remaining_denominator(self):
        launches = [launch(i) for i in range(8)]
        launches[5].update(status='unavailable', newDrawVerified=False, unavailableReason='no_new_draw_frame_report')
        launches[5]['afterLaunch']['drawFrameObserved'] = False
        with tempfile.TemporaryDirectory() as temporary:
            value = write_bundle(temporary, launches=launches, status='failed')
            capture = collected_capture(temporary, value)
            self.assertEqual('failed', capture['diagnosticStatus'])
            self.assertEqual(8, len(value['launches']))
            experience.validate_payloads(temporary, capture, '37.0', SOURCE)
        launches[5]['unavailableReason'] = ''
        with self.assertRaises(ValueError): experience.validate_launch(launches[5], 5, report())

    def test_strict_types_fields_profile_counts_and_cleanup(self):
        mutations = [lambda v: v.update(schemaVersion=True), lambda v: v.update(targetPackage='other'),
                     lambda v: v.update(extra='unknown'), lambda v: v['conditions'].update(startupPairsPerMode=100),
                     lambda v: v['conditions'].update(forcedGc=0), lambda v: v['cleanup'].update(servicesRestored=False),
                     lambda v: v['launches'].pop(), lambda v: v['trials'][0].update(path='../trial-000.json'),
                     lambda v: v['launches'][0].update(expectedPid=True)]
        for mutate in mutations:
            value = report(); mutate(value)
            with self.assertRaises(ValueError): experience.validate_report(value, 37)

    def test_cold_and_hot_lifecycle_require_actual_identity_and_new_draw(self):
        for index, mutations in [(0, [lambda v: v.update(preLaunchPidAbsent=False),
                                     lambda v: v['afterLaunch'].update(createCount=2),
                                     lambda v: v['afterLaunch'].update(createdNanoTime=1)]),
                                 (4, [lambda v: v['beforeLaunch'].update(stopped=False),
                                      lambda v: v['afterLaunch'].update(activityInstance=identifier(9999)),
                                      lambda v: v['afterLaunch'].update(resumeCount=1),
                                      lambda v: v['afterLaunch'].update(drawVsyncNs=1)])]:
            for mutate in mutations:
                value = launch(index); mutate(value)
                with self.assertRaises(ValueError): experience.validate_launch(value, index, report())
        value = launch(0); value['launchEndedNanoTime'] = value['launchStartedNanoTime']
        with self.assertRaises(ValueError): experience.validate_launch(value, 0, report())

    def test_all_three_resource_arms_separate_model_and_manual_acceptance(self):
        for index in range(3):
            value = trial(index); experience.validate_trial(value, index, report())
            for key in ('manualActionsAccepted', 'modelAcceptedWrites', 'modelRequests', 'approvalCount'):
                bad = copy.deepcopy(value); bad[key] = 0 if value[key] else 3
                with self.assertRaises(ValueError): experience.validate_trial(bad, index, report())
        value = trial(2); value['approvalCount'] = 5
        experience.validate_trial(value, 2, report())

    def test_cpu_zero_quantization_and_pss_endpoint_boundaries(self):
        value = trial(); experience.validate_trial(value, 0, report())
        self.assertEqual(value['agentTaskStartedCpuMillis'], value['agentTaskEndedCpuMillis'])
        self.assertEqual(value['targetBefore']['beginCpuMillis'], value['targetAfter']['endCpuMillis'])
        for mutate in (lambda v: v.update(agentTaskEndedCpuMillis=v['agentTaskStartedCpuMillis'] - 1),
                       lambda v: v['agentBeforeSample'].update(elapsedRealtimeNanos=v['agentTaskStartedNanos']),
                       lambda v: v['agentAfterSample'].update(processCpuMillis=0),
                       lambda v: v['targetAfter'].update(beforePssKb=15001),
                       lambda v: v['targetAfter'].update(endCpuMillis=99)):
            bad = copy.deepcopy(value); mutate(bad)
            with self.assertRaises(ValueError): experience.validate_trial(bad, 0, report())

    def test_failed_unknown_measurements_stay_unknown(self):
        value = trial(); value.update(status='failed', sessionId='', initialStateVerified=False, drawingVerified=False,
                                      targetBefore=None, targetAfter=None, frames=[], framePageChecks=[], agentBeforeSample=None,
                                      agentAfterSample=None, stateBefore=None, stateAfter=None)
        for key in ('agentTaskStartedNanos', 'agentTaskEndedNanos', 'agentTaskStartedCpuMillis', 'agentTaskEndedCpuMillis'):
            value[key] = -1
        experience.validate_trial(value, 0, report())
        value['agentTaskEndedCpuMillis'] = 0
        with self.assertRaises(ValueError): experience.validate_trial(value, 0, report())

    def test_frames_require_complete_pages_drain_final_vsync_and_no_lost_reports(self):
        mutations = [lambda v: v['frames'].clear(), lambda v: v['targetAfter'].update(frameCount=2),
                     lambda v: v['targetAfter'].update(droppedReports=1), lambda v: v['targetAfter'].update(lateFrameReports=1),
                     lambda v: v['targetAfter'].update(frameOverflow=1), lambda v: v['targetAfter'].update(drainComplete=False),
                     lambda v: v['frames'][0].update(vsyncNs=1), lambda v: v['frames'][0].update(index=True),
                     lambda v: v['frames'][0].update(totalDurationNs=-1), lambda v: v['frames'][0].update(refreshHz=float('nan'))]
        for mutate in mutations:
            value = trial(); mutate(value)
            with self.assertRaises(ValueError): experience.validate_trial(value, 0, report())
        value = trial(api=30); experience.validate_trial(value, 0, report(api=30))
        value['frames'][0]['gpuDurationNs'] = 0
        with self.assertRaises(ValueError): experience.validate_trial(value, 0, report(api=30))

    def test_boundary_crossing_and_unassociated_frames_remain_raw(self):
        value = trial()
        frame = value['frames'][0]
        frame.update(intendedVsyncNs=value['targetAfter']['beginNanoTime'] - 1, crossesBegin=True)
        experience.validate_trial(value, 0, report())
        frame['crossesBegin'] = False
        with self.assertRaisesRegex(ValueError, 'frame_outside'): experience.validate_trial(value, 0, report())
        value = trial()
        unknown = dict(value['frames'][0], index=0, vsyncNs=value['frames'][0]['vsyncNs'] - 1,
                       drawVersion=-1, drawNanoTime=-1)
        value['frames'][0]['index'] = 1; value['frames'].insert(0, unknown)
        value['targetAfter']['frameCount'] = 2
        value['framePageChecks'][0].update(nextOffset=2, totalFrames=2)
        experience.validate_trial(value, 0, report())
        value['frames'][1].update(drawVersion=-1, drawNanoTime=-1)
        with self.assertRaisesRegex(ValueError, 'matching_frame'): experience.validate_trial(value, 0, report())

    def test_late_page_checks_and_target_memory_or_display_boundaries(self):
        mutations = [lambda v: v['framePageChecks'].clear(),
                     lambda v: v['framePageChecks'][0].update(lateFrameReports=1),
                     lambda v: v['framePageChecks'][0].update(drainComplete=False),
                     lambda v: v['framePageChecks'][0].update(offset=1),
                     lambda v: v['targetAfter'].update(displayStable=False),
                     lambda v: v['targetAfter'].update(drawMatchAmbiguous=True),
                     lambda v: v['targetAfter'].update(endNanoTime=v['targetAfter']['beginNanoTime']),
                     lambda v: v['targetBefore'].update(beforePssEndedNanoTime=v['targetBefore']['beginNanoTime'] + 1),
                     lambda v: v['targetAfter'].update(afterPssStartedNanoTime=v['targetAfter']['endNanoTime'] - 1)]
        for mutate in mutations:
            value = trial(); mutate(value)
            with self.assertRaises(ValueError): experience.validate_trial(value, 0, report())
        value = trial(); value['status'] = 'failed'; value['framePageChecks'][0]['lateFrameReports'] = 1
        experience.validate_trial(value, 0, report())
        self.assertEqual(0, value['targetAfter']['lateFrameReports'])
        self.assertEqual(1, value['framePageChecks'][0]['lateFrameReports'])

    def test_unresolved_draw_associations_cannot_be_reported_as_passed(self):
        for index in (0, 4):
            value = launch(index); value['afterLaunch']['unresolvedDrawCount'] = 1
            with self.assertRaisesRegex(ValueError, 'passed_launch_missing_new_draw'):
                experience.validate_launch(value, index, report())
            value.update(status='unavailable', newDrawVerified=False, unavailableReason='no_new_draw_frame_report')
            experience.validate_launch(value, index, report())
        value = launch(4)
        value['beforeLaunch']['unresolvedDrawCount'] = value['afterLaunch']['unresolvedDrawCount'] = 2
        experience.validate_launch(value, 4, report())
        value = trial(); value['targetAfter']['unresolvedTaskDraws'] = 1
        with self.assertRaisesRegex(ValueError, 'incomplete_frames'):
            experience.validate_trial(value, 0, report())
        value['status'] = 'failed'
        experience.validate_trial(value, 0, report())
        value = trial(); value['targetAfter']['beginUnresolvedDrawCount'] = 1
        with self.assertRaisesRegex(ValueError, 'identity_changed'):
            experience.validate_trial(value, 0, report())

    def test_late_dropped_reports_remain_distinct_and_failed_samples_keep_counts(self):
        for key in ('targetAfter', 'page'):
            value = trial()
            point = value['targetAfter'] if key == 'targetAfter' else value['framePageChecks'][0]
            point['lateDroppedReports'] = 4
            with self.assertRaises(ValueError): experience.validate_trial(value, 0, report())
            value['status'] = 'failed'
            experience.validate_trial(value, 0, report())
            self.assertEqual(4, point['lateDroppedReports'])
            self.assertEqual(0, point['lateFrameReports'])
            for invalid in (True, -1, 1.0):
                point['lateDroppedReports'] = invalid
                with self.assertRaises(ValueError): experience.validate_trial(value, 0, report())

    def test_trace_capture_v2_start_outcome_is_strict_and_v1_remains_supported(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); value = write_bundle(root)
            state = json.loads((root / 'trace-capture.json').read_bytes())
            payloads = experience.validate_bundle(root, value, 37)
            legacy = {key: item for key, item in state.items()
                      if key not in ('configDelivery', 'startStdout', 'startStderr', 'startExitCode')}
            legacy['schemaVersion'] = 1
            experience.validate_trace_capture(legacy, payloads)
            for changes in (dict(schemaVersion=3), dict(schemaVersion=True), dict(configDelivery='path'),
                            dict(startStdout=None), dict(startStderr='x' * 2049), dict(startExitCode=True),
                            dict(startExitCode=1.0), dict(startExitCode=1), dict(startExitCode=None)):
                bad = copy.deepcopy(state); bad.update(changes)
                with self.assertRaises(ValueError): experience.validate_trace_capture(bad, payloads)
            state.update(startStdout='界' * 682 + 'ab', startStderr='')
            self.assertEqual(2048, len(state['startStdout'].encode('utf-8')))
            experience.validate_trace_capture(state, payloads)
            for output in ('界' * 682 + 'abc', '界' * 2048):
                bad = dict(state, startStdout=output)
                with self.assertRaisesRegex(ValueError, 'invalid_trace_start_output'):
                    experience.validate_trace_capture(bad, payloads)
            for code in (None, -9, 1):
                failed = dict(state, status='failed', pid=None, ready=False, stopped=False,
                              trace=None, compressed=None, error='startup failed', startExitCode=code)
                experience.validate_trace_capture(failed, {})
                self.assertEqual(code, failed['startExitCode'])
            legacy.update(status='failed', pid=None, ready=False, stopped=False, trace=None,
                          compressed=None, error='legacy preinstrumentation failure')
            experience.validate_trace_capture(legacy, {})
            legacy['startExitCode'] = 1
            with self.assertRaises(ValueError): experience.validate_trace_capture(legacy, {})

    def test_trace_v3_uses_only_versioned_ring_config_and_full_sort_derivation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); value = write_bundle(root, schema_version=2)
            payloads = experience.validate_bundle(root, value, 37)
            state = experience.strict_json(payloads['trace-capture.json'])
            derived = experience.strict_json(payloads['startup-ttid.json'])
            self.assertEqual((3, 2), (state['schemaVersion'], state['configVersion']))
            self.assertIn('fill_policy: RING_BUFFER', state['config'])
            self.assertEqual(['--full-sort'], derived['processor']['arguments'])
            for changes in (dict(configVersion=1), dict(configVersion=True),
                            dict(config=trace.trace_config(version=1)), dict(schemaVersion=2)):
                invalid = dict(state, **changes)
                with self.assertRaises(ValueError): experience.validate_trace_capture(invalid, payloads)
            legacy = {key: item for key, item in state.items() if key != 'configVersion'}
            legacy['schemaVersion'] = 2
            with self.assertRaisesRegex(ValueError, 'config_mismatch'):
                experience.validate_trace_capture(legacy, payloads)
            legacy['config'] = trace.trace_config(version=1)
            experience.validate_trace_capture(legacy, payloads)
            derived.update(schemaVersion=1, processor=trace.processor_identity(1))
            (root / 'startup-ttid.json').write_bytes(encoded(derived))
            with self.assertRaises(ValueError): experience.validate_bundle(root, value, 37)
        self.assertEqual('6646bb1c53eeafbcf95efba46636928a4e388d51b02360dd50577674512386ed',
                         hashlib.sha256(trace.trace_config(version=1).encode()).hexdigest())

    def test_capture_v4_has_separate_capacity_without_upgrading_legacy_evidence(self):
        mib = 1024 * 1024
        self.assertEqual(dict(raw=64*mib, gzip=12*mib, lane=16*mib), experience.limits_for_trace_capture(None))
        for config_version in (1, 2, 3):
            with self.subTest(config_version=config_version), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                value = write_bundle(root, schema_version=2, trace_config_version=config_version)
                metadata = experience.read_json(root / 'trace-capture.json')
                expected = dict(raw=256*mib, gzip=64*mib, lane=80*mib) if config_version == 3 else dict(raw=64*mib, gzip=12*mib, lane=16*mib)
                self.assertEqual(expected, experience.limits_for_trace_capture(metadata))
                with patch.object(trace, 'validate_derivation', wraps=trace.validate_derivation) as validate:
                    capture = collected_capture(root, value)
                    experience.validate_payloads(root, capture, '37.0', SOURCE)
                self.assertTrue(validate.call_args_list)
                self.assertTrue(all(call.kwargs == dict(raw_limit=expected['raw'], gzip_limit=expected['gzip'])
                                    for call in validate.call_args_list))
                if config_version == 3:
                    self.assertEqual((4, 3), (metadata['schemaVersion'], metadata['configVersion']))
                    self.assertIn('max_file_size_bytes: 268435456', metadata['config'])
                    for changes in (dict(configVersion=2), dict(configVersion=True),
                                    dict(schemaVersion=3), dict(config=trace.trace_config(version=2))):
                        invalid = dict(metadata, **changes)
                        with self.assertRaises(ValueError): experience.limits_for_trace_capture(invalid)
                else:
                    self.assertIn('max_file_size_bytes: 67108864', metadata['config'])
                    metadata['schemaVersion'] = 4
                    metadata['configVersion'] = 3
                    with self.assertRaisesRegex(ValueError, 'config_mismatch'):
                        experience.limits_for_trace_capture(metadata)
        self.assertEqual(dict(raw=256*mib, gzip=64*mib, lane=80*mib),
                         dict(raw=experience.MAX_TRACE_BYTES, gzip=experience.MAX_GZIP_BYTES,
                              lane=experience.MAX_ARTIFACT_BYTES))

    def test_legacy_gzip_and_lane_caps_apply_before_larger_reads(self):
        for config_version in (2, 3):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); value = write_bundle(root, schema_version=2, trace_config_version=config_version)
                zipped_size = (root / 'trace.pftrace.gz').stat().st_size
                with patch.object(experience, 'LEGACY_MAX_GZIP_BYTES', zipped_size - 1), \
                        patch.object(experience, 'read_bounded', wraps=experience.read_bounded) as read:
                    if config_version == 2:
                        with self.assertRaisesRegex(ValueError, 'invalid_bounded_payload'):
                            experience.validate_bundle(root, value, 37)
                    else:
                        experience.validate_bundle(root, value, 37)
                    gzip_reads = [call for call in read.call_args_list if Path(call.args[0]).name == 'trace.pftrace.gz']
                    self.assertEqual(1, len(gzip_reads))
                    self.assertEqual(zipped_size - 1 if config_version == 2 else experience.MAX_GZIP_BYTES, gzip_reads[0].args[1])
                with patch.object(experience, 'LEGACY_MAX_ARTIFACT_BYTES', experience.MAX_REPORT_BYTES + experience.MAX_CAPTURE_BYTES):
                    if config_version == 2:
                        with self.assertRaisesRegex(ValueError, 'artifact_exceeds_budget'):
                            experience.validate_bundle(root, value, 37)
                    else:
                        experience.validate_bundle(root, value, 37)

    def test_new_raw_capacity_still_preserves_only_failed_saturated_trace(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); value = write_bundle(root, schema_version=2, trace_config_version=3)
            metadata = experience.read_json(root / 'trace-capture.json')
            cap = metadata['trace']['bytes']
            metadata.update(status='failed', error='trace_reached_size_limit')
            (root / 'trace-capture.json').write_bytes(encoded(metadata))
            derived = {name: (root / name).read_bytes() for name in ('startup-ttid.json', 'startup-ttid.csv')}
            for name in derived: (root / name).unlink()
            with patch.object(experience, 'MAX_TRACE_BYTES', cap):
                capture = collected_capture(root, value)
                payloads = experience.validate_payloads(root, capture, '37.0', SOURCE)
                self.assertEqual(('partial', 'failed'), (capture['captureStatus'], capture['diagnosticStatus']))
                with self.assertRaises(ValueError):
                    experience.validate_trace_capture(dict(metadata, status='collected', error=None), payloads)
                for name, data in derived.items(): (root / name).write_bytes(data)
                with self.assertRaisesRegex(ValueError, 'unsaturated_trace'):
                    experience.validate_bundle(root, value, 37)
                oversized = gzip.compress(gzip.decompress(payloads['trace.pftrace.gz']) + b'x')
                metadata['compressed'].update(bytes=len(oversized), sha256=hashlib.sha256(oversized).hexdigest())
                with self.assertRaisesRegex(ValueError, 'uncompressed_trace_exceeds_budget'):
                    experience.validate_trace_capture(metadata, {'trace.pftrace.gz': oversized})

    def test_rehashed_cross_trial_cpu_and_cold_process_reuse_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            launches = [launch(i) for i in range(8)]
            launches[1]['afterLaunch']['processInstance'] = launches[0]['afterLaunch']['processInstance']
            value = write_bundle(temporary, launches=launches)
            with self.assertRaisesRegex(ValueError, 'reused_process'): experience.validate_bundle(temporary, value, 37)
        with tempfile.TemporaryDirectory() as temporary:
            trials = [trial(i) for i in range(3)]
            for key in ('agentBeforeSample', 'agentAfterSample'): trials[1][key]['processCpuMillis'] -= 1000
            for key in ('agentTaskStartedCpuMillis', 'agentTaskEndedCpuMillis'): trials[1][key] -= 1000
            value = write_bundle(temporary, trials=trials)
            with self.assertRaisesRegex(ValueError, 'cpu_regression'): experience.validate_bundle(temporary, value, 37)

    def test_missing_or_late_ttid_cannot_pass_even_with_passed_driver(self):
        for mutate in (lambda raw: raw['startups'][0].update(ttid=None),
                       lambda raw: raw['startups'][0].update(drawDur=10 ** 12),
                       lambda raw: raw['startups'][0].update(startupPid=999),
                       lambda raw: raw['stats'].append(dict(name='lost', idx=0, value=1, severity='data_loss', source='trace'))):
            with tempfile.TemporaryDirectory() as temporary:
                value = write_bundle(temporary); write_trace(temporary, value, mutate)
                capture = collected_capture(temporary, value)
                self.assertEqual('passed', capture['report']['status'])
                self.assertEqual('failed', capture['diagnosticStatus'])
                experience.validate_payloads(temporary, capture, '37.0', SOURCE)
                capture['diagnosticStatus'] = 'passed'
                with self.assertRaisesRegex(ValueError, 'combined_status'): experience.validate_payloads(temporary, capture, '37.0', SOURCE)

    def test_partial_missing_trace_and_nonzero_gradle_preserve_raw_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            value = write_bundle(temporary, with_trace=False)
            capture = collected_capture(temporary, value)
            self.assertEqual('partial', capture['captureStatus']); self.assertEqual('failed', capture['diagnosticStatus'])
            experience.validate_payloads(temporary, capture, '37.0', SOURCE)
        with tempfile.TemporaryDirectory() as temporary:
            value = write_bundle(temporary); capture = collected_capture(temporary, value, exit_code=124)
            self.assertEqual('passed', capture['report']['status']); self.assertEqual('failed', capture['diagnosticStatus'])
            self.assertEqual(124, capture['gradleExitCode'])
            experience.validate_payloads(temporary, capture, '37.0', SOURCE)

    def test_all_payload_hashes_exact_names_and_file_types_are_verified(self):
        for mode in ('hash', 'missing', 'extra', 'symlink', 'capture_hash', 'duplicate_ref', 'unknown_source'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); value = write_bundle(root); capture = collected_capture(root, value)
                path = root / 'trial-000.json'
                if mode == 'hash': path.write_bytes(path.read_bytes() + b' ')
                elif mode == 'missing': path.unlink()
                elif mode == 'extra': (root / 'private.json').write_text('{}')
                elif mode == 'symlink': path.unlink(); path.symlink_to(root / 'trial-001.json')
                elif mode == 'capture_hash': capture['payloads'][0]['sha256'] = '0' * 64
                elif mode == 'duplicate_ref': capture['payloads'].append(capture['payloads'][0])
                else: capture['sourceRun'] = dict(capture['sourceRun'], runId=True)
                with self.assertRaises(ValueError): experience.validate_payloads(root, capture, '37.0', SOURCE)

    def test_gzip_hash_integrity_and_inflate_limit_are_checked_before_parsing(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); value = write_bundle(root)
            data = (root / 'trace.pftrace.gz').read_bytes()
            (root / 'trace.pftrace.gz').write_bytes(data[:-1])
            with self.assertRaises(ValueError): experience.validate_bundle(root, value, 37)
        with tempfile.TemporaryDirectory() as temporary:
            value = write_bundle(temporary)
            with patch.object(experience, 'LEGACY_MAX_TRACE_BYTES', 10):
                with self.assertRaises(ValueError): experience.validate_bundle(temporary, value, 37)

    def test_exact_raw_trace_capacity_is_preserved_only_as_failed_without_derivation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); value = write_bundle(root)
            state_path = root / 'trace-capture.json'; state = json.loads(state_path.read_bytes())
            cap = state['trace']['bytes']
            derived = {name: (root / name).read_bytes() for name in ('startup-ttid.json', 'startup-ttid.csv')}
            for name in derived: (root / name).unlink()
            state.update(status='failed', error='trace_reached_size_limit')
            state_path.write_bytes(encoded(state))
            with patch.object(experience, 'LEGACY_MAX_TRACE_BYTES', cap):
                capture = collected_capture(root, value)
                payloads = experience.validate_payloads(root, capture, '37.0', SOURCE)
                self.assertEqual('partial', capture['captureStatus'])
                self.assertEqual('failed', capture['diagnosticStatus'])
                self.assertEqual(cap, experience.strict_json(payloads['trace-capture.json'])['trace']['bytes'])
                self.assertIn('trace.pftrace.gz', payloads)
                for status in ('collected', 'recording'):
                    invalid = dict(state, status=status, error=None)
                    with self.assertRaises(ValueError): experience.validate_trace_capture(invalid, payloads)
                for name, data in derived.items(): (root / name).write_bytes(data)
                with self.assertRaisesRegex(ValueError, 'unsaturated_trace'):
                    experience.validate_bundle(root, value, 37)
                for name in derived: (root / name).unlink()
                oversized = gzip.compress(gzip.decompress(payloads['trace.pftrace.gz']) + b'x')
                # A forged in-budget raw byte count must not permit an oversized
                # stream to allocate through a zero (unbounded) zlib max_length.
                state['compressed'].update(bytes=len(oversized), sha256=hashlib.sha256(oversized).hexdigest())
                with self.assertRaisesRegex(ValueError, 'uncompressed_trace_exceeds_budget'):
                    experience.validate_trace_capture(state, {'trace.pftrace.gz': oversized})
                state['trace']['bytes'] = cap + 1
                with self.assertRaises(ValueError):
                    experience.validate_trace_capture(state, {'trace.pftrace.gz': oversized})

    def test_rehashed_concatenated_gzip_and_corrupted_crc_are_rejected(self):
        for suffix in (gzip.compress(b'second member'), b'trailing garbage', None):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); value = write_bundle(root)
                path = root / 'trace.pftrace.gz'; data = path.read_bytes()
                data = data[:-8] + bytes([data[-8] ^ 1]) + data[-7:] if suffix is None else data + suffix
                path.write_bytes(data)
                state_path = root / 'trace-capture.json'; state = json.loads(state_path.read_bytes())
                state['compressed'].update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                state_path.write_bytes(encoded(state))
                with self.assertRaises(ValueError): experience.validate_bundle(root, value, 37)

    def test_shard_archive_rejects_traversal_symlinks_duplicates_and_missing_members(self):
        for mode in ('valid', 'traversal', 'symlink', 'duplicate', 'missing'):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary); device=root/'device'; out=root/'out'; out.mkdir()
                value=write_bundle(device, launches=[launch()], trials=[], status='incomplete', with_trace=False)
                data=(device/'launch-000.json').read_bytes()
                def run(args, **kwargs):
                    with tarfile.open(fileobj=kwargs['stdout'], mode='w') as archive:
                        member=tarfile.TarInfo('../launch-000.json' if mode=='traversal' else 'launch-000.json')
                        member.size=len(data)
                        if mode=='symlink': member.type=tarfile.SYMTYPE;member.linkname='elsewhere';member.size=0
                        if mode!='missing': archive.addfile(member, io.BytesIO(data))
                        if mode=='duplicate': archive.addfile(member, io.BytesIO(data))
                    return subprocess.CompletedProcess(args,0)
                with patch.object(experience.subprocess,'run',side_effect=run):
                    if mode=='valid': experience.pull_shards(value,out,experience.time.monotonic()+60)
                    else:
                        with self.assertRaises(ValueError): experience.pull_shards(value,out,experience.time.monotonic()+60)
                self.assertFalse((out/'shards.tar').exists())

    def test_collector_reads_only_manifest_files_and_preserves_trace_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);device=root/'device';out=root/'out';out.mkdir()
            value=write_bundle(device, launches=[launch()], trials=[], status='incomplete', with_trace=False)
            (device/'launch-001.json').write_text('uncommitted orphan')
            trace_state=dict(schemaVersion=1, kind='agent-os-experience-trace-capture', status='failed', pid=None,
                             stopped=False, ready=False, startMode=None, config=trace.trace_config(version=1), trace=None, compressed=None, error='fixture failure')
            (out/'trace-capture.json').write_bytes(encoded(trace_state))
            calls=[]
            def run(args,**kwargs):
                calls.append(args)
                self.assertIn('preexec_fn',kwargs);self.assertLessEqual(kwargs['timeout'],5)
                if args[1:3]==['shell','getprop']: kwargs['stdout'].write(b'37\n')
                elif 'tar' in args:
                    with tarfile.open(fileobj=kwargs['stdout'],mode='w') as archive:
                        data=(device/'launch-000.json').read_bytes(); info=tarfile.TarInfo('launch-000.json');info.size=len(data);archive.addfile(info,io.BytesIO(data))
                else: kwargs['stdout'].write((device/experience.REPORT_NAME).read_bytes())
                return subprocess.CompletedProcess(args,0)
            with patch.object(experience.subprocess,'run',side_effect=run): capture=experience.collect('37.0',SOURCE,124,out,'pilot')
            self.assertEqual('partial',capture['captureStatus']);self.assertEqual('incomplete',capture['diagnosticStatus'])
            self.assertEqual(124,capture['gradleExitCode']);self.assertEqual(3,len(calls))
            self.assertTrue((out/'trace-capture.json').exists());self.assertFalse((out/'launch-001.json').exists())
            experience.validate_payloads(out,capture,'37.0',SOURCE)

    def test_bounded_sdk_and_report_reads_fail_without_fetching_untrusted_paths(self):
        for data in (b'3'*33,b'\xff',b'36\n'):
            with tempfile.TemporaryDirectory() as temporary:
                calls=[]
                def run(args,**kwargs):
                    calls.append(args);kwargs['stdout'].write(data);return subprocess.CompletedProcess(args,0)
                with patch.object(experience.subprocess,'run',side_effect=run): capture=experience.collect('37.0',SOURCE,1,temporary,'pilot')
                self.assertEqual('invalid',capture['captureStatus']);self.assertIsNone(capture['report']);self.assertEqual(1,len(calls))
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'large';path.write_bytes(b' '*33)
            with self.assertRaises(ValueError):experience.read_bounded(path,32)
        for text in ('{"a":1,"a":2}','{"a":NaN}','{"a":Infinity}'):
            with self.assertRaises(ValueError):experience.strict_json(text)


if __name__ == '__main__':
    unittest.main()
