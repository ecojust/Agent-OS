#!/usr/bin/env python3
"""Collect and validate bounded, source-bound busy-stop diagnostic shards."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import time

from collect_performance_evidence import (LABELS, LONG_MAX, fields, integer,
    boolean, text_value, strict_json, source_identity, STAGES)

REPORT_NAME = 'busy-stop-diagnostics.json'
KIND = 'agent-os-busy-stop-diagnostics'
TRIAL_KIND = 'agent-os-busy-stop-trial'
CAPTURE_KIND = 'agent-busy-stop-evidence-capture'
SCOPES = ('busy-stop-pilot', 'busy-stop-diagnostic')
PROFILES = {'pilot': 2, 'formal': 100}
SCENARIOS = ('DEVICE', 'MODEL')
SCHEMA_VERSIONS = (1, 2)
FRAME_REPORT_CAPACITY = 8
FRAME_V2_DURATIONS = ('unknownDelayDurationNanos', 'inputHandlingDurationNanos',
                      'animationDurationNanos', 'layoutMeasureDurationNanos')
FRAME_REPORT_COLUMNS = ('intendedVsyncNanoTime', 'vsyncNanoTime', 'totalDurationNanos', 'firstDraw',
                        'gpuDurationNanos', 'deadlineNanos', 'callbackNanoTime', 'droppedReports',
                        *FRAME_V2_DURATIONS, 'drawStartNanoTime')
MAX_REPORT_BYTES = 64 * 1024
MAX_CAPTURE_BYTES = 16 * 1024
MAX_TRIAL_BYTES = 32 * 1024
MAX_ARTIFACT_BYTES = 8 * 1024 * 1024
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_TRACE_EVENTS = 128
COLLECTION_SECONDS = 60
CI_PROFILE = 'default_x86_64_swiftshader_animation_one'
STATUSES = ('passed', 'failed', 'incomplete')
CLEANUP_FIELDS = ('controllerCleared', 'servicesRestored', 'enabledRestored', 'automationFlagsRestored')


def profile_for_scope(scope):
    if scope not in SCOPES:
        raise ValueError('unsupported_busy_stop_scope')
    return 'pilot' if scope == SCOPES[0] else 'formal'


def trial_count(profile):
    if type(profile) is not str or profile not in PROFILES:
        raise ValueError('unsupported_busy_stop_profile')
    return 2 * PROFILES[profile]


def scope_for_profile(profile):
    trial_count(profile)
    return SCOPES[0] if profile == 'pilot' else SCOPES[1]


def trial_name(index):
    integer(index, 0, 199)
    return f'trial-{index:03d}.json'


def trial_identity(index, profile):
    integer(index, 0, trial_count(profile) - 1)
    repetition, position = divmod(index, 2)
    return dict(index=index, round=repetition, position=position,
                order='DEVICE_MODEL' if repetition % 2 == 0 else 'MODEL_DEVICE',
                scenario=SCENARIOS[position ^ (repetition % 2)])


def digest(value):
    if type(value) is not str or re.fullmatch(r'[0-9a-f]{64}', value) is None:
        raise ValueError('invalid_file_digest')


def bounded_text(value, maximum, empty=False):
    if (type(value) is not str or not empty and not value
            or len(value.encode('utf-8')) > maximum or any(ord(c) < 32 for c in value)):
        raise ValueError('invalid_bounded_text')


def checked_path(path):
    path = Path(path).absolute()
    if '..' in path.parts or any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('unsafe_busy_stop_path')
    return path


def read_json(path, maximum=MAX_REPORT_BYTES):
    path = checked_path(path)
    if not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ValueError('invalid_bounded_json_file')
    return strict_json(path.read_bytes())


def validate_device(device, api):
    fields(device, ('manufacturer', 'model', 'fingerprint'))
    for key in ('manufacturer', 'model', 'fingerprint'):
        bounded_text(device[key], 1024 if key == 'fingerprint' else 256)


def diagnostic_status(report, gradle_exit):
    if report is None:
        return 'unavailable'
    if report['status'] == 'incomplete':
        return 'incomplete'
    return 'passed' if report['status'] == 'passed' and gradle_exit == 0 else 'failed'


def validate_report(value, api, profile=None):
    fields(value, ('schemaVersion', 'kind', 'diagnosticOnly', 'budgetAcceptance', 'fullSuiteAcceptance',
                   'profile', 'status', 'api', 'appProcessPid', 'device', 'conditions', 'startedNanoTime',
                   'endedNanoTime', 'cleanup', 'trials'))
    if (type(value['schemaVersion']) is not int or value['schemaVersion'] not in SCHEMA_VERSIONS or value['kind'] != KIND
            or value['diagnosticOnly'] is not True or value['budgetAcceptance'] is not False
            or value['fullSuiteAcceptance'] is not False or type(value['profile']) is not str or value['profile'] not in PROFILES
            or profile is not None and value['profile'] != profile or value['status'] not in STATUSES):
        raise ValueError('invalid_busy_stop_report_identity')
    integer(value['appProcessPid'], 1, 2 ** 31 - 1)
    integer(value['api'], api, api)
    validate_device(value['device'], api)
    validate_conditions(value['conditions'], value['profile'], value['schemaVersion'])
    integer(value['startedNanoTime'])
    integer(value['endedNanoTime'], -1 if value['status'] == 'incomplete' else value['startedNanoTime'])
    if value['status'] == 'incomplete' and value['endedNanoTime'] != -1:
        raise ValueError('incomplete_root_has_final_time')
    fields(value['cleanup'], CLEANUP_FIELDS)
    for flag in value['cleanup'].values():
        boolean(flag)
    refs = value['trials']
    if type(refs) is not list or len(refs) > trial_count(value['profile']):
        raise ValueError('invalid_busy_stop_trial_count')
    failed = False
    for index, ref in enumerate(refs):
        fields(ref, ('index', 'round', 'position', 'order', 'scenario', 'path', 'bytes', 'sha256', 'status'))
        for key, expected in trial_identity(index, value['profile']).items():
            if type(ref[key]) is not type(expected) or ref[key] != expected:
                raise ValueError('invalid_busy_stop_trial_identity')
        integer(ref['bytes'], 1, MAX_TRIAL_BYTES)
        digest(ref['sha256'])
        if ref['path'] != trial_name(index) or ref['status'] not in ('passed', 'failed') or failed:
            raise ValueError('invalid_busy_stop_trial_prefix')
        failed = ref['status'] == 'failed'
    if value['status'] == 'passed' and (len(refs) != trial_count(value['profile']) or failed
                                      or not all(value['cleanup'].values())):
        raise ValueError('passed_busy_stop_root_incomplete')
    return value


def validate_conditions(value, profile, schema_version=1):
    expected = conditions(profile, schema_version)
    fields(value, expected)
    if any(type(value[key]) is not type(item) or value[key] != item for key, item in expected.items()):
        raise ValueError('unexpected_busy_stop_conditions')


def conditions(profile, schema_version=1):
    integer(schema_version, 1, 2)
    result = dict(clock='System.nanoTime', traceClock='SystemClock.elapsedRealtimeNanos',
                rounds=PROFILES[profile], trialsPerRound=2, trialCount=trial_count(profile),
                orders=['DEVICE_MODEL', 'MODEL_DEVICE'], quietMillis=500, frameWaitMillis=3000,
                fixtureBarrierMillis=3000, modelBarrierMillis=20000, frameCandidateCapacity=16,
                probeCapacity=16, traceCapacity=128, input='UiAutomation.injectInputEvent',
                frameMetric='INTENDED_VSYNC_TIMESTAMP+TOTAL_DURATION', forcedInvalidate=False,
                secureFlagsCleared=False)
    if schema_version == 2:
        result.update(frameAssociation='VSYNC_MS_AND_DRAW_START_BRACKET_V2',
                      frameCandidateReportCapacity=FRAME_REPORT_CAPACITY,
                      frameCandidateReportColumns=list(FRAME_REPORT_COLUMNS))
    return result


SNAPSHOTS = ('beforeStop', 'afterStop', 'beforeRelease', 'afterSettle', 'finalQuiet')
TRIAL_CLEANUP = ('controllerCleared', 'settingsRestored', 'serverClosed', 'fixtureCleared',
                 'frameProbeClosed', 'touchListenerRemoved', 'activityClosed')
DRAW_FLAGS = ('uiVerified', 'resumed', 'focused', 'attached', 'hardwareAccelerated', 'secureFlagPresent',
              'windowIdentityMatched', 'taskIdentityMatched')
FRAME_ERRORS = ('candidateOverflow', 'droppedReports', 'sequenceMismatchCount', 'zeroDrawingTimeCount',
                'ambiguousMatchCount', 'lateFrameReports', 'lateDroppedReports', 'invalidMetricCount', 'callbackFailureCount')
FRAME_FLAGS = ('finalFrameObserved', 'listenersRemoved', 'drainComplete', 'drainTimedOut',
               'secureFlagsPreserved', 'drainBarrierObserved', 'frameThreadStopped')


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def maybe_text(value, maximum=64, empty=False):
    if value is not None:
        bounded_text(value, maximum, empty=empty)


def mono(value, trial, unknown=True):
    integer(value, -1 if unknown else trial['startedNanoTime'])
    require(value == -1 or trial['startedNanoTime'] <= value <= trial['endedNanoTime'],
            'busy_stop_timestamp_outside_trial')


def ordered(*values):
    return all(v >= 0 for v in values) and all(a <= b for a, b in zip(values, values[1:]))


def validate_snapshot(value, trial):
    if value is None:
        return
    fields(value, ('sampleNanoTime', 'cancelled', 'epoch', 'dispatchAttempts', 'inFlight',
                   'controllerRunning', 'resultStopped', 'usageTaskMatches', 'controlAvailable',
                   'ownerTaskId', 'usageTaskId', 'resultKind', 'resultCode'))
    mono(value['sampleNanoTime'], trial, False)
    integer(value['epoch']); integer(value['dispatchAttempts'])
    for key in ('cancelled', 'inFlight', 'controllerRunning', 'resultStopped', 'usageTaskMatches', 'controlAvailable'):
        boolean(value[key])
    for key in ('ownerTaskId', 'usageTaskId', 'resultKind', 'resultCode'):
        maybe_text(value[key], 128 if key == 'resultCode' else 32 if key == 'resultKind' else 64, empty=key == 'resultCode')
    if trial['status'] != 'passed':
        return
    require(value['ownerTaskId'] == trial['taskId'] and trial['taskId'] is not None,
            'busy_stop_snapshot_owner_mismatch')
    require(value['usageTaskMatches'] == (value['usageTaskId'] == trial['taskId'])
            and value['resultStopped'] == (value['resultKind'] == 'STOPPED' and value['resultCode'] == 'TASK_STOPPED'),
            'busy_stop_snapshot_derived_identity_mismatch')


def validate_frame_v1(value, trial, report):
    if value is None:
        return
    fields(value, ('clockDomain', 'status', 'pid', 'activityIdentity', 'windowIdentity', 'decorIdentity',
                   'armedNanoTime', 'baseline', 'firstStoppedDraw', 'targetFrame', 'completedNanoTime', 'quality'))
    require(value['clockDomain'] == 'CLOCK_MONOTONIC_System.nanoTime'
            and value['status'] in ('not_started', 'incomplete', 'passed'), 'invalid_frame_identity')
    for key in ('pid', 'activityIdentity', 'windowIdentity', 'decorIdentity'):
        integer(value[key], -1, 2**31 - 1)
    for key in ('armedNanoTime', 'completedNanoTime'):
        mono(value[key], trial)
    baseline = value['baseline']; quality = value['quality']
    fields(baseline, ('draw', 'frame', 'matchCount', 'droppedReports', 'complete', 'timedOut'))
    for key in ('matchCount', 'droppedReports'): integer(baseline[key])
    for key in ('complete', 'timedOut'): boolean(baseline[key])
    fields(quality, ('receivedReports', 'observedDrawCount', 'discardedNonTargetCandidates',
                    *FRAME_ERRORS, 'callbackFailureCode', 'targetMatchCount', *FRAME_FLAGS, 'drainStartedNanoTime', 'drainEndedNanoTime'))
    for key, item in quality.items():
        if key == 'callbackFailureCode':
            require(type(item) is str and item in ('', 'draw', 'draw_read', 'draw_read_post', 'frame'), 'invalid_frame_callback_failure')
        elif key in FRAME_FLAGS: boolean(item)
        elif key.endswith('NanoTime'): mono(item, trial)
        else: integer(item)
    def draw_shape(draw):
        if draw is None: return
        fields(draw, ('sequence', 'drawNanoTime', 'drawingTimeMillis', 'readNanoTime', 'statusBounds', 'statusWidth', 'statusHeight', *DRAW_FLAGS))
        integer(draw['sequence']); integer(draw['drawingTimeMillis'], -1)
        for key in ('drawNanoTime', 'readNanoTime'): mono(draw[key], trial)
        for key in DRAW_FLAGS: boolean(draw[key])
        require(type(draw['statusBounds']) is list and len(draw['statusBounds']) == 4, 'invalid_status_bounds')
        for coordinate in draw['statusBounds']: integer(coordinate, -2**31, 2**31-1)
        integer(draw['statusWidth'], 0, 2**31-1); integer(draw['statusHeight'], 0, 2**31-1)
    def metric_shape(frame):
        if frame is None: return
        fields(frame, ('intendedVsyncNanoTime', 'vsyncNanoTime', 'totalDurationNanos', 'firstDraw',
                       'gpuDurationNanos', 'deadlineNanos', 'callbackNanoTime', 'droppedReports'))
        # A frame may start before the trial/UP in the same active vsync. Only its
        # measured callback must fall inside the trial's MONOTONIC interval.
        for key, item in frame.items(): integer(item, -1 if key != 'droppedReports' else 0)
        integer(frame['firstDraw'], -1, 1)
        mono(frame['callbackNanoTime'], trial)
    for draw in (baseline['draw'], value['firstStoppedDraw']): draw_shape(draw)
    for frame in (baseline['frame'], value['targetFrame']): metric_shape(frame)
    if value['status'] != 'passed' or trial['status'] != 'passed':
        return
    require((quality['callbackFailureCount'] == 0) == (quality['callbackFailureCode'] == ''), 'frame_callback_failure_mismatch')
    def valid_pair(draw, frame):
        require(draw is not None and frame is not None, 'passed_frame_missing_raw_pair')
        end = frame['intendedVsyncNanoTime'] + frame['totalDurationNanos']
        require(frame['intendedVsyncNanoTime'] > 0 and frame['totalDurationNanos'] > 0
                and frame['intendedVsyncNanoTime'] <= frame['vsyncNanoTime'] <= end <= frame['callbackNanoTime'] <= LONG_MAX
                and frame['droppedReports'] == 0 and frame['firstDraw'] in (0, 1)
                and draw['drawingTimeMillis'] == frame['vsyncNanoTime'] // 1_000_000
                and ordered(draw['drawNanoTime'], draw['readNanoTime'])
                and draw['drawNanoTime'] <= end and all(draw[k] for k in DRAW_FLAGS), 'invalid_frame_raw_pair')
        left, top, right, bottom = draw['statusBounds']
        require(draw['statusWidth'] > 0 and draw['statusHeight'] > 0
                and right-left == draw['statusWidth'] and bottom-top == draw['statusHeight'], 'stopped_status_not_fully_visible')
        return end
    valid_pair(baseline['draw'], baseline['frame'])
    end = valid_pair(value['firstStoppedDraw'], value['targetFrame'])
    require(value['pid'] > 0 and all(value[k] >= 0 for k in ('pid', 'activityIdentity', 'windowIdentity', 'decorIdentity'))
            and ('appProcessPid' not in report or value['pid'] == report['appProcessPid'])
            and baseline['complete'] and not baseline['timedOut'] and baseline['matchCount'] == 1
            and baseline['droppedReports'] == 0
            and baseline['draw']['readNanoTime'] <= value['armedNanoTime']
            and value['firstStoppedDraw']['readNanoTime'] <= quality['drainStartedNanoTime']
            and ordered(baseline['frame']['callbackNanoTime'], value['armedNanoTime'], trial['input']['upIssuedNanoTime'],
                        value['firstStoppedDraw']['drawNanoTime'], end)
            and value['firstStoppedDraw']['sequence'] > baseline['draw']['sequence']
            and value['targetFrame']['firstDraw'] == 0 and value['completedNanoTime'] == end
            and quality['targetMatchCount'] == 1 and quality['receivedReports'] >= 1 and quality['observedDrawCount'] >= 1
            and not any(quality[k] for k in FRAME_ERRORS)
            and all(quality[k] for k in FRAME_FLAGS if k != 'drainTimedOut') and not quality['drainTimedOut']
            and ordered(value['targetFrame']['callbackNanoTime'], quality['drainStartedNanoTime'], quality['drainEndedNanoTime']),
            'passed_frame_quality_or_order_invalid')


def checked_draw_start(frame):
    """Checked signed stage sum; invalid start/overflow/negative final stays -1."""
    result = frame['intendedVsyncNanoTime']
    if result <= 0:
        return -1
    for key in FRAME_V2_DURATIONS:
        part = frame[key]
        result += part
        if result < -2**63 or result > LONG_MAX:
            return -1
    return result if result >= 0 else -1


def validate_frame_v2(value, trial, report):
    if value is None:
        return
    fields(value, ('clockDomain', 'status', 'pid', 'activityIdentity', 'windowIdentity', 'decorIdentity',
                   'armedNanoTime', 'baseline', 'firstStoppedDraw', 'targetFrame', 'completedNanoTime', 'quality',
                   'candidateReports'))
    quality = value['quality']
    require(type(quality) is dict and {'candidateReportsObserved', 'candidateReportsNotRetained'} <= set(quality),
            'missing_v2_frame_candidate_counts')
    for key in ('candidateReportsObserved', 'candidateReportsNotRetained'):
        integer(quality[key])
    rows = value['candidateReports']
    require(type(rows) is list and len(rows) <= FRAME_REPORT_CAPACITY, 'invalid_v2_frame_candidate_capacity')
    def metric_shape(frame):
        if frame is None:
            return
        fields(frame, FRAME_REPORT_COLUMNS)
        for key, item in frame.items():
            integer(item, -2**63 if key in FRAME_V2_DURATIONS else 0 if key == 'droppedReports' else -1)
        integer(frame['firstDraw'], -1, 1)
        mono(frame['callbackNanoTime'], trial)
    candidates = []
    for row in rows:
        require(type(row) is list and len(row) == len(FRAME_REPORT_COLUMNS), 'invalid_v2_frame_candidate_columns')
        metric = dict(zip(FRAME_REPORT_COLUMNS, row)); metric_shape(metric); candidates.append(metric)
    baseline = value['baseline']
    require(type(baseline) is dict and 'frame' in baseline, 'invalid_v2_frame_baseline')
    for metric in (baseline['frame'], value['targetFrame']):
        metric_shape(metric)
    # Preserve the v1 validator's exact raw domains and common safety gates.
    # Only v2-only additions are projected away; no v1 evidence is reinterpreted.
    legacy_keys = FRAME_REPORT_COLUMNS[:8]
    def original_metric(metric):
        return None if metric is None else {key: metric[key] for key in legacy_keys}
    original = {key: item for key, item in value.items() if key != 'candidateReports'}
    original['baseline'] = dict(baseline, frame=original_metric(baseline['frame']))
    original['targetFrame'] = original_metric(value['targetFrame'])
    original['quality'] = {key: item for key, item in quality.items()
                           if key not in ('candidateReportsObserved', 'candidateReportsNotRetained')}
    validate_frame_v1(original, trial, report)
    # Failed originals retain contradictory observations for investigation. The
    # derived label never authorizes a passed frame or a statistical sample.
    if value['status'] != 'passed' or trial['status'] != 'passed':
        return
    observed, omitted = quality['candidateReportsObserved'], quality['candidateReportsNotRetained']
    require(len(rows) == min(observed, FRAME_REPORT_CAPACITY) and observed == len(rows) + omitted,
            'v2_frame_candidate_prefix_accounting_mismatch')
    for metric in (baseline['frame'], value['targetFrame'], *candidates):
        require(metric is not None and metric['drawStartNanoTime'] == checked_draw_start(metric),
                'v2_frame_draw_start_arithmetic_mismatch')
    for draw, metric in ((baseline['draw'], baseline['frame']), (value['firstStoppedDraw'], value['targetFrame'])):
        start = checked_draw_start(metric)
        end = metric['intendedVsyncNanoTime'] + metric['totalDurationNanos']
        require(all(metric[key] >= 0 for key in FRAME_V2_DURATIONS)
                and start > metric['vsyncNanoTime']
                and draw['drawNanoTime'] <= start <= draw['readNanoTime'] and start <= end,
                'v2_frame_draw_start_outside_frozen_draw_bracket')
    target_draw, target = value['firstStoppedDraw'], value['targetFrame']
    require(all(metric['callbackNanoTime'] >= target_draw['drawNanoTime'] for metric in candidates),
            'v2_candidate_precedes_frozen_stopped_draw')
    matches = [metric for metric in candidates
               if metric['vsyncNanoTime'] // 1_000_000 == target_draw['drawingTimeMillis']
               and (checked_draw_start(metric) < 0
                    or target_draw['drawNanoTime'] <= checked_draw_start(metric) <= target_draw['readNanoTime'])]
    require(len(matches) <= 1 and all(metric == target for metric in matches),
            'v2_candidate_contradicts_unique_original_target')
    if omitted == 0:
        require(len(matches) == 1 and candidates.count(target) == 1, 'v2_complete_candidate_prefix_missing_target')


def validate_frame(value, trial, report):
    if trial['schemaVersion'] == 1:
        return validate_frame_v1(value, trial, report)
    return validate_frame_v2(value, trial, report)


def validate_trial(value, index, report):
    fields(value, ('schemaVersion', 'kind', 'index', 'round', 'position', 'order', 'scenario', 'status',
                   'phase', 'failure', 'startedNanoTime', 'endedNanoTime', 'taskId', 'epochBefore', 'input',
                   *SNAPSHOTS, 'probe', 'fixture', 'model', 'frames', 'trace', 'checks', 'cleanup', 'feedback',
                   'releaseRequestedNanoTime', 'gateProbe', 'oldEpochProbe'))
    require(type(value['schemaVersion']) is int and value['schemaVersion'] in SCHEMA_VERSIONS
            and type(report.get('schemaVersion', 1)) is int and value['schemaVersion'] == report.get('schemaVersion', 1)
            and value['kind'] == TRIAL_KIND
            and value['status'] in ('passed', 'failed'), 'invalid_busy_stop_trial_identity')
    for key, expected in trial_identity(index, report['profile']).items():
        require(type(value[key]) is type(expected) and value[key] == expected, 'invalid_busy_stop_trial_identity')
    integer(value['startedNanoTime']); integer(value['endedNanoTime'], value['startedNanoTime'])
    bounded_text(value['phase'], 100); maybe_text(value['failure'], 500); maybe_text(value['taskId'])
    integer(value['epochBefore'], -1)
    mono(value['releaseRequestedNanoTime'], value)
    touch = value['input']
    fields(touch, ('bounds', 'point', 'downIssuedNanoTime', 'downReturnedNanoTime', 'upIssuedNanoTime',
                   'upIssuedElapsedNanos', 'upReturnedNanoTime', 'downAccepted', 'upAccepted',
                   'touchUpNanoTime', 'touchUpElapsedNanos', 'touchUpCount'))
    for key, length in (('bounds', 4), ('point', 2)):
        if touch[key] is not None:
            require(type(touch[key]) is list and len(touch[key]) == length, 'invalid_input_coordinates')
            for coordinate in touch[key]: integer(coordinate, -2**31, 2**31-1)
    for key, item in touch.items():
        if key.endswith('NanoTime'): mono(item, value)
        elif key.endswith('ElapsedNanos') or key == 'touchUpCount': integer(item, -1 if key != 'touchUpCount' else 0)
        elif key.endswith('Accepted') and item is not None: boolean(item)
    for key in SNAPSHOTS: validate_snapshot(value[key], value)
    fields(value['cleanup'], TRIAL_CLEANUP)
    for item in value['cleanup'].values(): boolean(item)
    feedback = value['feedback']
    fields(feedback, ('controlGoneObservedNanoTime', 'quietStartedNanoTime', 'quietEndedNanoTime', 'overlayBefore', 'overlayAfter'))
    for key in ('controlGoneObservedNanoTime', 'quietStartedNanoTime', 'quietEndedNanoTime'): mono(feedback[key], value)
    for key, shape in (('overlayBefore', ('originalAttached', 'currentMatchesOriginal', 'controlAvailable')),
                       ('overlayAfter', ('originalAttached', 'currentOverlayPresent', 'controlAvailable'))):
        if feedback[key] is not None:
            fields(feedback[key], shape)
            for item in feedback[key].values(): boolean(item)
    checks = value['checks']
    fields(checks, ('initialCounter', 'finalCounter', 'approvalCount', 'frozenUsageUnchanged', 'frozenResultUnchanged',
                   'workerDrained', 'quietMillis', 'noFurtherRequests', 'workerDrainedNanoTime', 'networkDrained',
                   'networkDrainedNanoTime', 'closerDrained', 'closerDrainedNanoTime', 'requestCountBeforeStop'))
    for key, item in checks.items():
        if key.endswith('NanoTime'): mono(item, value)
        elif key in ('initialCounter', 'finalCounter', 'requestCountBeforeStop'): integer(item, -1)
        elif key in ('approvalCount', 'quietMillis'): integer(item)
        else: boolean(item)
    require(checks['quietMillis'] == 500, 'unexpected_busy_stop_quiet_window')
    probe = value['probe']; trace = value['trace']
    fields(probe, ('events', 'overflow', 'ownerMatched')); integer(probe['overflow']); boolean(probe['ownerMatched'])
    require(type(probe['events']) is list and len(probe['events']) <= 16, 'invalid_probe_capacity')
    for event in probe['events']:
        require(type(event) is list and len(event) == 5, 'invalid_probe_event')
        integer(event[0], 1, 7); mono(event[1], value, False)
        integer(event[2]); integer(event[3]); integer(event[4], 0, 1)
    fields(trace, ('events', 'overwritten')); integer(trace['overwritten'])
    require(type(trace['events']) is list and len(trace['events']) <= MAX_TRACE_EVENTS, 'invalid_trace_capacity')
    for event in trace['events']:
        require(type(event) is list and len(event) == 6 and type(event[0]) is str and event[0] in STAGES, 'invalid_trace_event')
        integer(event[1]); integer(event[2], 0, LONG_MAX-event[1]); boolean(event[3]); integer(event[4], -1); integer(event[5], -1)
    fixture = value['fixture']
    if fixture is not None:
        fields(fixture, ('beforeStop', 'beforeRelease', 'afterSettle'))
        for item in fixture.values():
            if item is None: continue
            fields(item, ('token', 'pid', 'claimed', 'released', 'returned', 'expired', 'barrierEnteredNanoTime',
                          'barrierExitedNanoTime', 'effectWrittenNanoTime', 'effectCount', 'startedElapsedNanos', 'finishedElapsedNanos'))
            bounded_text(item['token'], 64); integer(item['pid'], 1, 2**31-1)
            for key in ('claimed', 'released', 'returned', 'expired'): boolean(item[key])
            for key in ('barrierEnteredNanoTime', 'barrierExitedNanoTime', 'effectWrittenNanoTime'): mono(item[key], value)
            for key in ('effectCount', 'startedElapsedNanos', 'finishedElapsedNanos'): integer(item[key], -1)
    model = value['model']
    if model is not None:
        fields(model, ('requests', 'enteredNanoTime', 'releaseNanoTime', 'writeAttemptNanoTime', 'writeEndNanoTime',
                       'peerClosed', 'writeSucceeded', 'heldExpired'))
        integer(model['requests'])
        for key in ('enteredNanoTime', 'releaseNanoTime', 'writeAttemptNanoTime', 'writeEndNanoTime'): mono(model[key], value)
        for key in ('peerClosed', 'writeSucceeded', 'heldExpired'): boolean(model[key])
    gate = value['gateProbe']
    if gate is not None:
        fields(gate, ('sampleNanoTime', 'replacementTaskId', 'replacementEpoch', 'code', 'submitted',
                      'dispatchSubmitted', 'dispatchAccepted', 'attempts'))
        mono(gate['sampleNanoTime'], value); maybe_text(gate['replacementTaskId']); maybe_text(gate['code'])
        integer(gate['replacementEpoch'], -1); integer(gate['attempts'], -1)
        for key in ('submitted', 'dispatchSubmitted', 'dispatchAccepted'):
            if gate[key] is not None: boolean(gate[key])
    old = value['oldEpochProbe']
    if old is not None:
        fields(old, ('sampleNanoTime', 'code', 'attempts'))
        mono(old['sampleNanoTime'], value); maybe_text(old['code']); integer(old['attempts'], -1)
    validate_frame(value['frames'], value, report)
    require((value['scenario'] == 'DEVICE' and value['frames'] is None)
            or (value['scenario'] == 'MODEL' and fixture is None and gate is None), 'unexpected_scenario_evidence')
    # Failed originals retain bounded raw observations, including the very
    # invariant violation under investigation. They never contribute latency samples.
    if value['status'] == 'failed':
        return value
    device = value['scenario'] == 'DEVICE'; attempts = 1 if device else 0
    require(value['phase'] == 'complete' and value['failure'] is None and value['taskId'] is not None
            and value['epochBefore'] >= 0 and all(value['cleanup'].values())
            and checks['initialCounter'] == 0 and checks['finalCounter'] == attempts
            and all(checks[k] for k in ('frozenUsageUnchanged', 'frozenResultUnchanged', 'workerDrained',
                                       'noFurtherRequests', 'networkDrained', 'closerDrained')),
            'passed_busy_stop_incomplete_checks')
    require(feedback['overlayBefore'] is not None and all(feedback['overlayBefore'].values())
            and feedback['overlayAfter'] is not None and not any(feedback['overlayAfter'].values()),
            'busy_stop_original_overlay_not_detached')
    require(touch['bounds'] is not None and touch['point'] is not None
            and touch['downAccepted'] is True and touch['upAccepted'] is True and touch['touchUpCount'] == 1
            and ordered(touch['downIssuedNanoTime'], touch['downReturnedNanoTime'], touch['upIssuedNanoTime'], touch['upReturnedNanoTime'])
            and ordered(touch['upIssuedNanoTime'], touch['touchUpNanoTime'])
            and ordered(touch['upIssuedElapsedNanos'], touch['touchUpElapsedNanos']), 'passed_busy_stop_input_invalid')
    left, top, right, bottom = touch['bounds']; x, y = touch['point']
    require(left < right and top < bottom and x == (left+right)//2 and y == (top+bottom)//2
            and left <= x < right and top <= y < bottom, 'input_misses_bound_stop_button')
    events = probe['events']; expected_types = [1,5,6,7,2,4] if device else [5,6,7]
    require([e[0] for e in events] == expected_types and probe['overflow'] == 0 and probe['ownerMatched']
            and ordered(*(e[1] for e in events)), 'passed_busy_stop_probe_incomplete')
    points = {e[0]:e for e in events}
    for e in events:
        expected_epoch = value['epochBefore'] + (1 if e[0] in (6,7) else 0)
        require(e[2] == expected_epoch and e[3] == attempts, 'busy_stop_probe_epoch_or_attempt_mismatch')
        if e[0] in (1,5): require(e[4] == 0, 'invalid_probe_initial_detail')
        if e[0] in (4,6,7): require(e[4] == 1, 'invalid_probe_completed_detail')
    require(ordered(touch['touchUpNanoTime'], points[5][1], points[6][1], points[7][1]), 'stop_precedes_real_up')
    for position, key in enumerate(SNAPSHOTS):
        snap = value[key]
        require(snap is not None and snap['dispatchAttempts'] == attempts, 'passed_busy_stop_missing_snapshot')
        require(snap['cancelled'] == (position != 0) and snap['epoch'] == value['epochBefore'] + (position != 0)
                and snap['controllerRunning'] == (position == 0)
                and snap['inFlight'] == (device and position < 3), 'busy_stop_ticket_or_owner_changed')
        if position == 0:
            require(snap['controlAvailable'] and not snap['resultStopped'], 'busy_stop_initial_control_invalid')
        else:
            require(snap['resultStopped'] and snap['usageTaskMatches'], 'busy_stop_late_result_revived')
            if position >= 2: require(not snap['controlAvailable'], 'busy_stop_control_returned')
    require(ordered(*(value[k]['sampleNanoTime'] for k in SNAPSHOTS))
            and value['beforeStop']['sampleNanoTime'] <= touch['downIssuedNanoTime']
            and points[6][1] <= value['afterStop']['sampleNanoTime']
            and ordered(value['afterStop']['sampleNanoTime'], feedback['controlGoneObservedNanoTime'],
                        value['beforeRelease']['sampleNanoTime'], value['releaseRequestedNanoTime'],
                        checks['workerDrainedNanoTime'], checks['networkDrainedNanoTime'], checks['closerDrainedNanoTime'],
                        value['afterSettle']['sampleNanoTime'], feedback['quietStartedNanoTime'], feedback['quietEndedNanoTime'],
                        value['finalQuiet']['sampleNanoTime'])
            and feedback['quietEndedNanoTime'] - feedback['quietStartedNanoTime'] >= 500_000_000,
            'busy_stop_release_drain_quiet_order_invalid')
    require(old is not None and old['code'] == 'TASK_STOPPED' and old['attempts'] == attempts
            and ordered(value['afterSettle']['sampleNanoTime'], old['sampleNanoTime'], feedback['quietStartedNanoTime']),
            'busy_stop_old_epoch_not_rejected')
    require(model is not None and model['requests'] == checks['requestCountBeforeStop']
            and not model['heldExpired'], 'busy_stop_unexpected_network_request')
    require(trace['overwritten'] == 0, 'busy_stop_trace_lost_events')
    dispatch = [e for e in trace['events'] if e[0] == 'DEVICE_DISPATCH_ATTEMPT']
    require(len(dispatch) == attempts and all(e[1] <= touch['upIssuedElapsedNanos'] and e[4] == value['epochBefore'] and e[5] == 1 for e in dispatch),
            'busy_stop_post_stop_or_extra_dispatch')
    stages = {s:[e for e in trace['events'] if e[0] == s] for s in ('STOP_RECEIVED','STOP_EPOCH_INVALIDATED','STOP_UI_APPLIED')}
    require(all(len(e) == 1 for e in stages.values()), 'busy_stop_stop_trace_incomplete')
    received, invalidated, ui = (stages[k][0] for k in stages)
    require(ordered(touch['touchUpElapsedNanos'], received[1], received[1]+received[2], invalidated[1],
                    invalidated[1]+invalidated[2], ui[1], ui[1]+ui[2])
            and invalidated[4] == value['epochBefore']+1 and invalidated[5] == attempts,
            'busy_stop_legacy_stop_trace_invalid')
    if device:
        require(fixture is not None and all(fixture.values()) and gate is not None
                and 1 <= checks['approvalCount'] <= 4 and 1 <= model['requests'] <= 4
                and all(model[k] == -1 for k in ('enteredNanoTime','releaseNanoTime','writeAttemptNanoTime','writeEndNanoTime'))
                and not model['writeSucceeded'] and not model['peerClosed'], 'busy_stop_device_setup_invalid')
        first, held, final = (fixture[k] for k in ('beforeStop','beforeRelease','afterSettle'))
        for state in (first, held):
            require(state['claimed'] and not any(state[k] for k in ('released','returned','expired'))
                    and state['effectCount'] == -1 and state['barrierEnteredNanoTime'] >= 0
                    and all(state[k] == -1 for k in ('barrierExitedNanoTime','effectWrittenNanoTime'))
                    and state['finishedElapsedNanos'] == 0,
                    'busy_stop_fixture_not_held')
        require(all(first[k] == held[k] == final[k] for k in ('token','pid','barrierEnteredNanoTime','startedElapsedNanos'))
                and final['claimed'] and final['released'] and final['returned'] and not final['expired'] and final['effectCount'] == 1
                and ('appProcessPid' not in report or final['pid'] != report['appProcessPid'])
                and ordered(points[1][1], first['barrierEnteredNanoTime'], value['beforeStop']['sampleNanoTime'])
                and ordered(value['releaseRequestedNanoTime'], final['barrierExitedNanoTime'], final['effectWrittenNanoTime'], points[2][1], points[4][1], checks['workerDrainedNanoTime'])
                and ordered(first['startedElapsedNanos'], final['finishedElapsedNanos']), 'busy_stop_fixture_effect_return_settle_invalid')
        require(gate['replacementTaskId'] is not None and gate['replacementTaskId'] != value['taskId']
                and gate['replacementEpoch'] >= 0 and gate['code'] == 'CAPABILITY_UNAVAILABLE' and gate['attempts'] == 0
                and all(gate[k] is False for k in ('submitted','dispatchSubmitted','dispatchAccepted'))
                and ordered(feedback['controlGoneObservedNanoTime'], gate['sampleNanoTime'], value['beforeRelease']['sampleNanoTime']),
                'busy_stop_replacement_gate_released_early')
    else:
        require(checks['approvalCount'] == 0 and model['requests'] == 1 and model['writeSucceeded'] != model['peerClosed']
                and ordered(model['enteredNanoTime'], value['beforeStop']['sampleNanoTime'])
                and ordered(value['releaseRequestedNanoTime'], model['releaseNanoTime'], model['writeAttemptNanoTime'], model['writeEndNanoTime'], checks['workerDrainedNanoTime'])
                and value['frames'] is not None and value['frames']['status'] == 'passed'
                and points[7][1] <= value['frames']['firstStoppedDraw']['drawNanoTime']
                and value['frames']['quality']['drainEndedNanoTime'] <= value['beforeRelease']['sampleNanoTime'],
                'busy_stop_model_release_or_frame_invalid')
    return value


def validate_single_device_trial(value, api):
    integer(api, 30, 37)
    context = dict(profile='pilot', api=api, schemaVersion=value.get('schemaVersion') if type(value) is dict else None)
    return validate_trial(value, 0, context)


def validate_bundle(directory, report, api, profile=None):
    directory = checked_path(directory)
    if not directory.is_dir():
        raise ValueError('invalid_busy_stop_bundle_directory')
    validate_report(report, api, profile)
    expected = {REPORT_NAME, *(r['path'] for r in report['trials'])}
    actual = set()
    for path in directory.iterdir():
        checked_path(path)
        if not path.is_file():
            raise ValueError('invalid_busy_stop_bundle_entry')
        actual.add(path.name)
    if actual - {'capture.json'} != expected:
        raise ValueError('busy_stop_bundle_file_set_mismatch')
    raw = (directory / REPORT_NAME).read_bytes()
    if len(raw) > MAX_REPORT_BYTES or strict_json(raw) != report:
        raise ValueError('busy_stop_bundle_root_differs')
    payloads = {REPORT_NAME: raw}
    previous_end = report['startedNanoTime']
    for ref in report['trials']:
        path = directory / ref['path']
        if path.stat().st_size != ref['bytes']:
            raise ValueError('busy_stop_trial_size_mismatch')
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != ref['sha256']:
            raise ValueError('busy_stop_trial_hash_mismatch')
        trial = validate_trial(strict_json(data), ref['index'], report)
        for key in ('index', 'round', 'position', 'order', 'scenario', 'status'):
            if type(trial[key]) is not type(ref[key]) or trial[key] != ref[key]:
                raise ValueError('busy_stop_trial_reference_mismatch')
        if (trial['startedNanoTime'] < previous_end
                or report['endedNanoTime'] != -1 and trial['endedNanoTime'] > report['endedNanoTime']):
            raise ValueError('busy_stop_cross_trial_time_regression')
        previous_end = trial['endedNanoTime']
        payloads[ref['path']] = data
    if sum(map(len, payloads.values())) + MAX_CAPTURE_BYTES > MAX_ARTIFACT_BYTES:
        raise ValueError('busy_stop_bundle_exceeds_budget')
    return payloads


def empty_capture(label, source, profile='formal'):
    if label not in LABELS:
        raise ValueError('unsupported_matrix_label')
    return dict(schemaVersion=1, kind=CAPTURE_KIND, diagnosticOnly=True, budgetAcceptance=False,
                fullSuiteAcceptance=False, profile=profile, executionScope=scope_for_profile(profile),
                matrixLabel=label, apiLevel=int(label.split('.')[0]), actualSdkInt=None,
                sourceRun=source, capturedAt=datetime.now(timezone.utc).isoformat(),
                declaredCiProfile=CI_PROFILE, captureStatus='not_collected',
                captureError='emulator_script_did_not_finish', diagnosticStatus='unavailable',
                gradleExitCode=None, report=None)


def validate_capture(value, label, source, profile=None):
    keys = ('schemaVersion', 'kind', 'diagnosticOnly', 'budgetAcceptance', 'fullSuiteAcceptance', 'profile',
            'executionScope', 'matrixLabel', 'apiLevel', 'actualSdkInt', 'sourceRun', 'capturedAt',
            'declaredCiProfile', 'captureStatus', 'captureError', 'diagnosticStatus', 'gradleExitCode', 'report')
    if type(value) is dict and 'emulatorStepOutcome' in value:
        keys += ('emulatorStepOutcome',)
        if value['emulatorStepOutcome'] not in ('success', 'failure', 'cancelled', 'skipped', 'unknown'):
            raise ValueError('invalid_emulator_outcome')
    fields(value, keys)
    fields(value['sourceRun'], source)
    if (type(value['schemaVersion']) is not int or value['schemaVersion'] != 1 or value['kind'] != CAPTURE_KIND
            or value['diagnosticOnly'] is not True or value['budgetAcceptance'] is not False
            or value['fullSuiteAcceptance'] is not False or type(value['profile']) is not str or value['profile'] not in PROFILES
            or profile is not None and value['profile'] != profile
            or value['executionScope'] != scope_for_profile(value['profile']) or value['matrixLabel'] != label
            or value['sourceRun'] != source or value['declaredCiProfile'] != CI_PROFILE
            or any(type(value['sourceRun'][key]) is not type(expected) for key, expected in source.items())):
        raise ValueError('busy_stop_capture_identity_mismatch')
    api = int(label.split('.')[0])
    integer(value['apiLevel'], api, api)
    if value['actualSdkInt'] is not None:
        integer(value['actualSdkInt'], api, api)
    if value['gradleExitCode'] is not None:
        integer(value['gradleExitCode'], 0, 255)
    if (type(value['capturedAt']) is not str or len(value['capturedAt']) > 40
            or datetime.fromisoformat(value['capturedAt']).tzinfo is None):
        raise ValueError('invalid_capture_timestamp')
    if value['captureStatus'] == 'collected':
        ref = value['report']
        fields(ref, ('path', 'bytes', 'sha256', 'status'))
        integer(ref['bytes'], 1, MAX_REPORT_BYTES)
        digest(ref['sha256'])
        if (value['actualSdkInt'] != api or value['captureError'] is not None
                or ref['path'] != REPORT_NAME or ref['status'] not in STATUSES
                or value['diagnosticStatus'] != diagnostic_status({'status': ref['status']}, value['gradleExitCode'])):
            raise ValueError('invalid_busy_stop_capture_report')
    elif (value['captureStatus'] not in ('not_collected', 'unavailable', 'invalid') or value['report'] is not None
          or value['diagnosticStatus'] != 'unavailable' or type(value['captureError']) is not str
          or re.fullmatch(r'[a-z_]{1,80}', value['captureError']) is None):
        raise ValueError('invalid_busy_stop_capture_outcome')
    return value


def validate_payloads(directory, capture, label, source, profile=None):
    validate_capture(capture, label, source, profile)
    directory = checked_path(directory)
    if not directory.is_dir():
        raise ValueError('invalid_busy_stop_payload_directory')
    actual_capture = read_json(directory / 'capture.json', MAX_CAPTURE_BYTES)
    validate_capture(actual_capture, label, source, profile)
    if actual_capture != capture:
        raise ValueError('busy_stop_capture_file_differs')
    if capture['captureStatus'] != 'collected':
        if {p.name for p in directory.iterdir()} != {'capture.json'}:
            raise ValueError('uncollected_busy_stop_payloads_present')
        return {}
    report = read_json(directory / REPORT_NAME)
    payloads = validate_bundle(directory, report, int(label.split('.')[0]), profile or capture['profile'])
    raw = payloads[REPORT_NAME]
    ref = capture['report']
    if (ref['bytes'] != len(raw) or ref['sha256'] != hashlib.sha256(raw).hexdigest()
            or ref['status'] != report['status']
            or capture['diagnosticStatus'] != diagnostic_status(report, capture['gradleExitCode'])):
        raise ValueError('busy_stop_capture_root_binding_mismatch')
    return payloads


def limited_output(maximum):
    def apply():
        import resource
        resource.setrlimit(resource.RLIMIT_FSIZE, (maximum + 1, maximum + 1))
    return apply


def remaining_timeout(deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ValueError('collection_deadline_exceeded')
    return min(15, remaining)


def pull_file(name, target, maximum, deadline):
    with target.open('wb') as output:
        result = subprocess.run(['adb', 'exec-out', 'run-as', 'dev.agentos.app', 'cat',
                                 'files/performance/busy-stop/' + name], stdout=output,
                                stderr=subprocess.DEVNULL, check=False, timeout=remaining_timeout(deadline),
                                preexec_fn=limited_output(maximum))
    if target.stat().st_size > maximum:
        raise ValueError('report_exceeds_collection_budget')
    if result.returncode != 0:
        raise ValueError('committed_report_unavailable')


def pull_shards(report, folder, deadline):
    refs = {ref['path']: ref for ref in report['trials']}
    if not refs:
        return
    limit = sum(ref['bytes'] + 1024 for ref in refs.values()) + 10240
    archive = folder / 'shards.tar'
    try:
        with archive.open('wb') as output:
            result = subprocess.run(['adb', 'exec-out', 'run-as', 'dev.agentos.app', 'tar', '-C',
                                     'files/performance/busy-stop', '-cf', '-', *refs], stdout=output,
                                    stderr=subprocess.DEVNULL, check=False, timeout=remaining_timeout(deadline),
                                    preexec_fn=limited_output(limit))
        if archive.stat().st_size > limit or result.returncode != 0:
            raise ValueError('committed_shards_unavailable')
        seen = set()
        with tarfile.open(archive, mode='r:') as stream:
            for member in stream:
                remaining_timeout(deadline)
                if (member.name not in refs or member.name in seen or not member.isfile() or member.linkname
                        or member.pax_headers or member.size != refs[member.name]['bytes']):
                    raise ValueError('invalid_committed_shard_archive')
                source = stream.extractfile(member)
                data = source.read(member.size + 1)
                if len(data) != member.size:
                    raise ValueError('truncated_committed_shard')
                (folder / member.name).write_bytes(data)
                seen.add(member.name)
        if seen != set(refs):
            raise ValueError('missing_committed_shards')
    except tarfile.TarError as error:
        raise ValueError('invalid_committed_shard_archive') from error
    finally:
        archive.unlink(missing_ok=True)


def collect(label, source, exit_code, output, profile='formal'):
    output = checked_path(output)
    output.mkdir(parents=True, exist_ok=True)
    owned = (REPORT_NAME, *(trial_name(index) for index in range(200)))
    for name in owned:
        (output / name).unlink(missing_ok=True)
    record = empty_capture(label, source, profile)
    record.update(gradleExitCode=exit_code, captureStatus='unavailable', captureError='device_or_report_unavailable')
    deadline = time.monotonic() + COLLECTION_SECONDS
    try:
        with tempfile.TemporaryFile() as stream:
            subprocess.run(['adb', 'shell', 'getprop', 'ro.build.version.sdk'], stdout=stream,
                           stderr=subprocess.DEVNULL, check=True, timeout=remaining_timeout(deadline),
                           preexec_fn=limited_output(32))
            stream.seek(0)
            raw_sdk = stream.read(33)
        if len(raw_sdk) > 32:
            raise ValueError('sdk_output_exceeds_collection_budget')
        try:
            actual = raw_sdk.decode('ascii').strip()
        except UnicodeDecodeError as error:
            raise ValueError('invalid_sdk_output') from error
        if not actual.isdigit() or int(actual) != record['apiLevel']:
            raise ValueError('device_sdk_mismatch')
        record['actualSdkInt'] = int(actual)
        with tempfile.TemporaryDirectory(prefix='agent-busy-stop-') as temporary:
            folder = Path(temporary)
            pull_file(REPORT_NAME, folder / REPORT_NAME, MAX_REPORT_BYTES, deadline)
            report = validate_report(read_json(folder / REPORT_NAME), record['apiLevel'], profile)
            pull_shards(report, folder, deadline)
            payloads = validate_bundle(folder, report, record['apiLevel'], profile)
            remaining_timeout(deadline)
            for name, data in payloads.items():
                (output / name).write_bytes(data)
            raw = payloads[REPORT_NAME]
            record.update(captureStatus='collected', captureError=None,
                          diagnosticStatus=diagnostic_status(report, exit_code),
                          report=dict(path=REPORT_NAME, bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest(),
                                      status=report['status']))
    except ValueError as error:
        if str(error) == 'committed_report_unavailable':
            record['captureError'] = str(error)
        else:
            record.update(captureStatus='invalid', captureError=str(error)
                          if re.fullmatch(r'[a-z_]{1,80}', str(error)) else 'invalid_report')
    except (OSError, subprocess.SubprocessError):
        pass
    if record['captureStatus'] != 'collected':
        for name in owned:
            (output / name).unlink(missing_ok=True)
    return record


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('collect', 'finalize'))
    parser.add_argument('--matrix-label', required=True, choices=LABELS)
    parser.add_argument('--scope', required=True, choices=SCOPES)
    parser.add_argument('--exit-code', type=int, default=1)
    parser.add_argument('--step-outcome', choices=('success', 'failure', 'cancelled', 'skipped', 'unknown'), default='unknown')
    args = parser.parse_args(argv)
    source, profile = source_identity(os.environ), profile_for_scope(args.scope)
    output = checked_path(Path('busy-stop-evidence') / ('api-' + args.matrix_label))
    output.mkdir(parents=True, exist_ok=True)
    path = output / 'capture.json'
    if args.mode == 'collect':
        record = collect(args.matrix_label, source, args.exit_code, output, profile)
    elif path.exists():
        record = read_json(path, MAX_CAPTURE_BYTES)
    else:
        record = empty_capture(args.matrix_label, source, profile)
    if args.mode == 'finalize':
        record['emulatorStepOutcome'] = args.step_outcome
    validate_capture(record, args.matrix_label, source, profile)
    raw = json.dumps(record, indent=2) + '\n'
    if len(raw.encode()) > MAX_CAPTURE_BYTES:
        raise ValueError('capture_exceeds_budget')
    path.write_text(raw)
    print(f"API {args.matrix_label}: busy stop {record['captureStatus']}/{record['diagnosticStatus']}; "
          f"Gradle exit={record['gradleExitCode']}; diagnostic only")
    return 0 if args.mode == 'finalize' or record['diagnosticStatus'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
