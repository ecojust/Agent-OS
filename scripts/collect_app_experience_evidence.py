#!/usr/bin/env python3
"""Collect bounded application experience shards and verify independent startup evidence."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import time
import zlib

from collect_performance_evidence import (LABELS, LONG_MAX, fields, integer, boolean, text_value,
                                          strict_json, source_identity)
from collect_active_pair_evidence import (read_json, digest, limited_output, remaining_timeout,
                                         validate_sample, RESULT_KINDS)

REPORT_NAME = 'experience-diagnostics.json'
KIND = 'agent-app-experience-diagnostics'
LAUNCH_KIND = 'agent-app-experience-launch'
TRIAL_KIND = 'agent-app-experience-trial'
CAPTURE_KIND = 'agent-app-experience-evidence-capture'
MAX_REPORT_BYTES = 262144
MAX_LAUNCH_BYTES = 32768
MAX_TRIAL_BYTES = 2097152
MAX_CAPTURE_BYTES = 131072
LEGACY_MAX_TRACE_BYTES = 64 * 1024 * 1024
LEGACY_MAX_GZIP_BYTES = 12 * 1024 * 1024
LEGACY_MAX_ARTIFACT_BYTES = 16 * 1024 * 1024
MAX_TRACE_BYTES = 256 * 1024 * 1024
MAX_GZIP_BYTES = 64 * 1024 * 1024
MAX_ARTIFACT_BYTES = 80 * 1024 * 1024
MAX_DERIVED_BYTES = 2 * 1024 * 1024
COLLECTION_SECONDS = 60
TARGET_PACKAGE = 'dev.agentos.fixture'
PROFILES = {'pilot': (2, 1), 'formal': (100, 6)}
SCOPES = {'app-experience-pilot': 'pilot', 'app-experience-diagnostic': 'formal'}
ARMS = ('A_SERVICE_OFF', 'B_SERVICE_ON_IDLE', 'C_SERVICE_ON_AGENT')
ORDERS = ('ABC', 'BCA', 'CAB', 'ACB', 'CBA', 'BAC')
STATUSES = ('passed', 'failed', 'incomplete')
STATE_FLAGS = ('servicePresent', 'serviceConnected', 'controllerRunning', 'controlAvailable', 'fixtureFocused')
HOST_NAMES = ('trace.pftrace.gz', 'trace-capture.json', 'startup-ttid.json', 'startup-ttid.csv')
CLEANUP = ('controllerCleared', 'servicesRestored', 'enabledRestored', 'automationFlagsRestored')
IDENTITY_FIELDS = ('schemaVersion', 'clockDomain', 'pid', 'uid', 'processInstance', 'activityInstance')
LIFECYCLE = ('create', 'start', 'resume', 'pause', 'stop', 'destroy', 'newIntent')
LIFECYCLE_TIMES = ('created', 'started', 'resumed', 'paused', 'stopped', 'destroyed', 'newIntent')
STATUS_BOOL = ('activityPresent', 'resumed', 'stopped', 'windowFocused', 'hardwareAccelerated',
               'drawFrameObserved', 'drawMatchAmbiguous', 'finalState', 'finalDrawObserved')
STATUS_INT = (*[x + 'Count' for x in LIFECYCLE], *[x + 'NanoTime' for x in LIFECYCLE_TIMES],
              'mutationVersion', 'drawVersion', 'drawVsyncNs', 'drawNanoTime', 'activityFrameCount',
              'activityDroppedReports', 'lastFrameVsyncNs', 'lastFrameCallbackNanoTime', 'displayChangeCount',
              'clicks', 'editCount', 'saveCount', 'incrementCount', 'drawTimeMillis', 'displayObservedNanoTime', 'unresolvedDrawCount')
SESSION_BOOL = ('ended', 'finalFrameObserved', 'drainComplete', 'drainTimedOut', 'displayStable')
SESSION_INT = ('beforePssKb', 'beginCpuMillis', 'endCpuMillis', 'beginNanoTime', 'endNanoTime',
               'beginMutationVersion', 'finalMutationVersion', 'finalDrawVersion', 'finalDrawVsyncNs',
               'frameCutoffVsyncNs', 'frameCount', 'frameOverflow', 'droppedReports', 'lateFrameReports', 'lateDroppedReports',
               'beginDisplayChangeCount', 'endDisplayChangeCount', 'beginDisplayObservedNanoTime', 'endDisplayObservedNanoTime',
               'beginUnresolvedDrawCount', 'unresolvedTaskDraws')
SESSION_HZ = ('beginRefreshHz', 'beginModeHz', 'endRefreshHz', 'endModeHz')
PROBE_BASE_FIELDS = (*IDENTITY_FIELDS, *STATUS_BOOL, *STATUS_INT, 'refreshHz', 'modeHz', 'displayId', 'editorText', 'savedText', 'drawMatchMethod')
PROBE_SESSION_FIELDS = ('sessionId', *SESSION_BOOL, *SESSION_INT, *SESSION_HZ, 'afterPssKb')
DRAW_METHODS = {1: 'post_OnDraw_View.getDrawingTime_equals_floor_FrameMetrics.VSYNC_TIMESTAMP_ms',
                2: 'front_post_OnDraw_View.getDrawingTime_equals_floor_FrameMetrics.VSYNC_TIMESTAMP_ms',
                3: 'front_post_OnDraw_View.getDrawingTime_equals_floor_FrameMetrics.VSYNC_TIMESTAMP_ms'}
DRAW_CAUSE_FIELDS = ('sequenceMismatchCount', 'zeroDrawingTimeCount')
FOCUS_FIELDS = ('focusGainCount', 'focusLossCount', 'firstFocusGainNanoTime', 'lastFocusGainNanoTime', 'lastFocusLossNanoTime')
READINESS_PROVIDER_FIELDS = ('pid', 'processInstance', 'activityInstance', *[name + 'Count' for name in LIFECYCLE],
                             *FOCUS_FIELDS, 'resumed', 'stopped', 'windowFocused')
READINESS_FIELDS = ('readinessStartedNanos', 'readinessEndedNanos', 'readinessVerified', 'readinessFailureReason', 'readinessObservations')
READINESS_REASONS = ('', 'provider_identity_changed', 'provider_lifecycle_changed', 'provider_focus_changed',
                     'provider_not_resumed_focused', 'agent_state_changed', 'a11y_timeout', 'observation_failed')
MEMORY_FIELDS = ('PssStartedNanoTime', 'PssEndedNanoTime', 'DalvikPssKb', 'NativePssKb', 'OtherPssKb', 'GraphicsSummaryKb')
FRAME_FIELDS = ('index', 'intendedVsyncNs', 'vsyncNs', 'totalDurationNs', 'firstDraw', 'gpuDurationNs',
                'deadlineNs', 'callbackNanoTime', 'droppedReports', 'refreshHz', 'modeHz', 'displayId', 'drawVersion',
                'drawNanoTime', 'crossesBegin', 'refreshObservedNanoTime')


def profile_for_scope(scope):
    if type(scope) is not str or scope not in SCOPES:
        raise ValueError('invalid_experience_scope')
    return SCOPES[scope]


def conditions(profile, schema_version=1):
    integer(schema_version, 1, 3)
    if type(profile) is not str or profile not in PROFILES:
        raise ValueError('invalid_experience_profile')
    pairs, repetitions = PROFILES[profile]
    result = dict(buildType='debug', fixturePackage=TARGET_PACKAGE,
                fixtureActivity='dev.agentos.fixture.PerformanceActivity', startupPairsPerMode=pairs,
                launchCount=pairs * 4, resourceRepetitions=repetitions, trialCount=repetitions * 3,
                startupOrder='AB_BA_alternating', resourceOrder='_'.join(ORDERS[:repetitions]),
                startupSettleMillis=250, resourceSettleMillis=2000, taskTimeoutMillis=30000,
                sequenceTimeoutMillis=2300000, baselineDriver='UiAutomation_accessibility_actions',
                localModelScope='same_process_all_arms_idle_in_A_B', sampling='target_provider_and_agent_endpoints',
                startupMetric='external_perfetto_android_startups_time_to_display', forcedGc=False,
                systemAnimationOverrides=False, screenshotSamplingIncluded=False)
    if schema_version >= 2:
        result.update(homeSettleMillis=1000, homePreparation='resolved_HOME_focused_stopped_settle_recheck')
    if schema_version == 3:
        result.update(a11yReadinessMaxObservations=16, a11yReadinessTimeoutMillis=3000,
                      a11yReadinessIntervalMillis=200, a11yReadinessPolicy='post_marker_read_only_identity_lifecycle_focus_guard')
    return result


def launch_name(index):
    return f'launch-{index:03d}.json'


def trial_name(index):
    return f'trial-{index:03d}.json'


def launch_identity(index, profile):
    pairs = PROFILES[profile][0]
    pair = index % (pairs * 2) // 2
    return pair, index % 2, ARMS[(index % 2) ^ (pair % 2)], 'cold' if index < pairs * 2 else 'hot'


def trial_arm(index):
    return ARMS[ord(ORDERS[index // 3][index % 3]) - ord('A')]


def exact(value, expected, reason='unexpected_experience_conditions'):
    fields(value, expected)
    if any(type(value[k]) is not type(v) or value[k] != v for k, v in expected.items()):
        raise ValueError(reason)


def bounded_text(value, maximum, empty=False):
    if type(value) is not str or len(value.encode('utf-8')) > maximum or (not empty and not value):
        raise ValueError('invalid_bounded_string')


def uuid(value):
    if type(value) is not str or not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', value):
        raise ValueError('invalid_instance_identifier')


def hz(value, minimum=-1):
    if type(value) not in (int, float) or not math.isfinite(value) or not minimum <= value <= 1000:
        raise ValueError('invalid_refresh_rate')


def validate_report(value, api, profile=None, artifact_limit=None):
    artifact_limit = MAX_ARTIFACT_BYTES if artifact_limit is None else artifact_limit
    fields(value, ('schemaVersion', 'kind', 'diagnosticOnly', 'budgetAcceptance', 'profile', 'status',
                   'appProcessPid', 'targetPackage', 'device', 'conditions', 'startedNanos', 'endedNanos',
                   'cleanup', 'launches', 'trials'))
    if (type(value['schemaVersion']) is not int or value['schemaVersion'] not in (1, 2, 3) or value['kind'] != KIND
            or value['diagnosticOnly'] is not True or value['budgetAcceptance'] is not False
            or type(value['profile']) is not str or value['profile'] not in PROFILES or value['status'] not in STATUSES
            or profile is not None and value['profile'] != profile or value['targetPackage'] != TARGET_PACKAGE):
        raise ValueError('invalid_experience_identity')
    expected = conditions(value['profile'], value['schemaVersion'])
    exact(value['conditions'], expected)
    integer(value['appProcessPid'], 1, 2 ** 31 - 1)
    fields(value['device'], ('sdkInt', 'manufacturer', 'model', 'hardware', 'fingerprint', 'emulatorHeuristic'))
    integer(value['device']['sdkInt'], api, api)
    for key in ('manufacturer', 'model', 'hardware', 'fingerprint'):
        text_value(value['device'][key], 1024 if key == 'fingerprint' else 256)
    boolean(value['device']['emulatorHeuristic'])
    integer(value['startedNanos'])
    integer(value['endedNanos'], -1 if value['status'] == 'incomplete' else value['startedNanos'],
            -1 if value['status'] == 'incomplete' else LONG_MAX)
    fields(value['cleanup'], CLEANUP)
    for flag in value['cleanup'].values():
        boolean(flag)
    if value['status'] == 'passed' and not all(value['cleanup'].values()):
        raise ValueError('passed_report_cleanup_incomplete')
    total = MAX_REPORT_BYTES + MAX_CAPTURE_BYTES
    previous_end, failed = value['startedNanos'], False
    for kind, count, maximum, namer in (('launches', expected['launchCount'], MAX_LAUNCH_BYTES, launch_name),
                                        ('trials', expected['trialCount'], MAX_TRIAL_BYTES, trial_name)):
        refs = value[kind]
        if type(refs) is not list or len(refs) > count or value['status'] == 'passed' and len(refs) != count:
            raise ValueError('invalid_experience_committed_count')
        if kind == 'trials' and refs and len(value['launches']) != expected['launchCount']:
            raise ValueError('resource_trials_before_launch_plan_complete')
        for index, ref in enumerate(refs):
            keys = ('index', 'path', 'bytes', 'sha256', 'status', 'startedNanos', 'endedNanos')
            if kind == 'launches':
                keys += ('marker', 'expectedPid', 'expectedStartupType', 'driverPid')
            fields(ref, keys)
            integer(ref['index'], index, index)
            if (ref['path'] != namer(index) or failed or ref['status'] not in
                    (('passed', 'failed', 'unavailable') if kind == 'launches' else ('passed', 'failed'))
                    or value['status'] == 'passed' and ref['status'] != 'passed'):
                raise ValueError('invalid_experience_committed_prefix')
            integer(ref['bytes'], 1, maximum)
            digest(ref['sha256'])
            integer(ref['startedNanos'], previous_end)
            integer(ref['endedNanos'], ref['startedNanos'])
            if value['endedNanos'] != -1 and ref['endedNanos'] > value['endedNanos']:
                raise ValueError('shard_outside_report')
            if kind == 'launches':
                if (ref['marker'] != f'aos.experience.launch.{index}'
                        or ref['expectedStartupType'] != launch_identity(index, value['profile'])[3]):
                    raise ValueError('invalid_launch_reference_identity')
                integer(ref['driverPid'], value['appProcessPid'], value['appProcessPid'])
                integer(ref['expectedPid'], 1 if ref['status'] == 'passed' else -1, 2 ** 31 - 1)
            total += ref['bytes']
            if total > artifact_limit:
                raise ValueError('experience_artifact_exceeds_budget')
            previous_end = ref['endedNanos']
            failed = ref['status'] == 'failed'
    return value


def validate_state(value, arm, passed, start, end, focused=True):
    fields(value, ('checkedNanos', *STATE_FLAGS))
    integer(value['checkedNanos'], start, end)
    for flag in STATE_FLAGS:
        boolean(value[flag])
    connected = arm != ARMS[0]
    if passed and (value['servicePresent'] != connected or value['serviceConnected'] != connected
                   or value['controllerRunning'] or value['controlAvailable']
                   or focused and not value['fixtureFocused']):
        raise ValueError('invalid_experience_endpoint_state')


def validate_probe(value, session=False, schema_version=None):
    if type(value) is not dict:
        raise ValueError('invalid_target_probe_identity')
    integer(value.get('schemaVersion'), 1, 3)
    version = value['schemaVersion']
    if schema_version is not None and version != schema_version:
        raise ValueError('target_probe_version_mismatch')
    memory_fields = tuple(prefix + key for prefix in ('before', *(['after'] if value.get('ended') is True else []))
                          for key in MEMORY_FIELDS) if session and type(value) is dict else ()
    fields(value, (*PROBE_BASE_FIELDS, *(DRAW_CAUSE_FIELDS if version >= 2 else ()), *(FOCUS_FIELDS if version == 3 else ()),
                   *(PROBE_SESSION_FIELDS if session else ()), *memory_fields))
    if version >= 2:
        for key in DRAW_CAUSE_FIELDS:
            integer(value[key])
        if sum(value[key] for key in DRAW_CAUSE_FIELDS) != value['unresolvedDrawCount']:
            raise ValueError('unresolved_draw_cause_total_mismatch')
    if version == 3:
        validate_focus(value)
    if value['clockDomain'] != 'CLOCK_MONOTONIC_System.nanoTime':
        raise ValueError('invalid_target_clock_domain')
    integer(value['pid'], 1, 2 ** 31 - 1)
    integer(value['uid'], 1, 2 ** 31 - 1)
    uuid(value['processInstance']); uuid(value['activityInstance'])
    for key in STATUS_BOOL:
        boolean(value[key])
    for key in STATUS_INT:
        integer(value[key])
    for key in ('refreshHz', 'modeHz'):
        hz(value[key])
    integer(value['displayId'], -1, 2 ** 31 - 1)
    for key in ('editorText', 'savedText'):
        bounded_text(value[key], 256, empty=True)
    if value['drawMatchMethod'] != DRAW_METHODS[version]:
        raise ValueError('invalid_draw_matching_method')
    if value['drawFrameObserved'] and value['drawVsyncNs'] // 1_000_000 != value['drawTimeMillis']:
        raise ValueError('draw_match_timestamp_mismatch')
    if value['drawVersion'] > value['mutationVersion']:
        raise ValueError('draw_version_exceeds_ui_version')
    if session:
        uuid(value['sessionId'])
        for key in SESSION_BOOL:
            boolean(value[key])
        for key in SESSION_INT:
            integer(value[key])
        integer(value['frameCount'], 0, 4096)
        integer(value['afterPssKb'], -1)
        for key in SESSION_HZ:
            hz(value[key])
        for prefix in ('before', *(['after'] if value['ended'] else [])):
            for key in MEMORY_FIELDS:
                integer(value[prefix + key], -1 if key == 'GraphicsSummaryKb' else 0)
            integer(value[prefix + 'PssEndedNanoTime'], value[prefix + 'PssStartedNanoTime'])
            if value[prefix + 'PssKb'] != sum(value[prefix + key] for key in ('DalvikPssKb', 'NativePssKb', 'OtherPssKb')):
                raise ValueError('target_pss_classification_total_mismatch')
        if value['beforePssEndedNanoTime'] > value['beginNanoTime']:
            raise ValueError('target_pss_overlaps_cpu_window')
        if value['ended']:
            if value['afterPssStartedNanoTime'] < value['endNanoTime']:
                raise ValueError('target_pss_overlaps_cpu_window')
            integer(value['endNanoTime'], value['beginNanoTime'])
            integer(value['endCpuMillis'], value['beginCpuMillis'])
            integer(value['afterPssKb'])
        elif value['afterPssKb'] != -1 or value['endNanoTime'] or value['endCpuMillis']:
            raise ValueError('unfinished_target_session_has_end_measurement')
    return value


def validate_focus(value, checked=None):
    for key in FOCUS_FIELDS:
        integer(value[key])
    boolean(value['windowFocused'])
    gains, losses = value['focusGainCount'], value['focusLossCount']
    first, last_gain, last_loss = (value[key] for key in FOCUS_FIELDS[2:])
    if gains != losses + int(value['windowFocused']):
        raise ValueError('invalid_focus_transition_counts')
    if ((gains == 0 and (first or last_gain)) or gains > 0 and not 0 < first <= last_gain
            or losses == 0 and last_loss or losses > 0 and last_loss <= 0):
        raise ValueError('invalid_focus_transition_times')
    if ((value['windowFocused'] and last_gain < last_loss)
            or not value['windowFocused'] and last_loss < last_gain
            or checked is not None and max(first, last_gain, last_loss) > checked):
        raise ValueError('invalid_focus_transition_order')


def validate_a11y(value):
    fields(value, ('windowCount', 'focusedWindowCount', 'focusedWindowId', 'focusedWindowType', 'rootPresent',
                   'packageName', 'editorMatchCount', 'editorVisible', 'ready', 'reason'))
    integer(value['windowCount'], -1, 2 ** 31 - 1)
    integer(value['focusedWindowCount'], 0, max(0, value['windowCount']))
    for key in ('focusedWindowId', 'focusedWindowType', 'editorMatchCount'):
        integer(value[key], -1, 2 ** 31 - 1)
    for key in ('rootPresent', 'editorVisible', 'ready'):
        boolean(value[key])
    bounded_text(value['packageName'], 256, empty=True)
    if value['windowCount'] == -1:
        reason = 'windows_unavailable'
    elif value['focusedWindowCount'] != 1:
        reason = 'focused_window_count'
    elif value['focusedWindowType'] != 1:
        reason = 'focused_window_not_application'
    elif not value['rootPresent']:
        reason = 'root_missing'
    elif value['packageName'] != TARGET_PACKAGE:
        reason = 'package_mismatch'
    elif value['editorMatchCount'] != 1:
        reason = 'editor_match_count'
    elif not value['editorVisible']:
        reason = 'editor_not_visible'
    else:
        reason = 'ready'
    if value['reason'] != reason or value['ready'] != (reason == 'ready'):
        raise ValueError('a11y_readiness_reason_mismatch')
    unique = value['focusedWindowCount'] == 1
    if unique:
        integer(value['focusedWindowId'], 0, 2 ** 31 - 1)
        integer(value['focusedWindowType'], 0, 2 ** 31 - 1)
    elif value['focusedWindowId'] != -1 or value['focusedWindowType'] != -1:
        raise ValueError('a11y_unavailable_window_identity')
    application = unique and value['focusedWindowType'] == 1
    if not application and value['rootPresent']:
        raise ValueError('a11y_root_without_application_window')
    if not value['rootPresent'] and value['packageName']:
        raise ValueError('a11y_package_without_root')
    searched = application and value['rootPresent'] and value['packageName'] == TARGET_PACKAGE
    if searched:
        integer(value['editorMatchCount'])
    elif value['editorMatchCount'] != -1:
        raise ValueError('a11y_editor_search_without_fixture')
    if value['editorVisible'] and value['editorMatchCount'] != 1:
        raise ValueError('a11y_visible_editor_not_unique')


def readiness_guard(provider, agent, launch):
    anchor = launch['afterLaunch']
    if anchor is None:
        return 'provider_identity_changed'
    expected = [anchor[key] for key in READINESS_PROVIDER_FIELDS]
    if provider[:3] != expected[:3]:
        return 'provider_identity_changed'
    if provider[3:10] != expected[3:10]:
        return 'provider_lifecycle_changed'
    before = launch['beforeLaunch']
    if launch['startupType'] == 'cold':
        focus_anchor = (anchor['focusGainCount'] == 1 and anchor['focusLossCount'] == 0
                        and anchor['firstFocusGainNanoTime'] == anchor['lastFocusGainNanoTime']
                        and anchor['firstFocusGainNanoTime'] >= launch['launchStartedNanoTime']
                        and anchor['lastFocusLossNanoTime'] == 0)
    else:
        focus_anchor = (before is not None and not before['windowFocused']
                        and anchor['focusGainCount'] == before['focusGainCount'] + 1
                        and anchor['focusLossCount'] == before['focusLossCount']
                        and anchor['firstFocusGainNanoTime'] == before['firstFocusGainNanoTime']
                        and anchor['lastFocusLossNanoTime'] == before['lastFocusLossNanoTime']
                        and anchor['lastFocusGainNanoTime'] >= launch['launchStartedNanoTime'])
    if not focus_anchor or provider[10:15] != expected[10:15]:
        return 'provider_focus_changed'
    if not provider[15] or provider[16] or not provider[17]:
        return 'provider_not_resumed_focused'
    connected = launch['arm'] != ARMS[0]
    if agent[:4] != [connected, connected, False, False]:
        return 'agent_state_changed'
    return ''


def validate_readiness(launch):
    start, end = launch['readinessStartedNanos'], launch['readinessEndedNanos']
    for point in (start, end):
        integer(point, -1)
        if point != -1:
            integer(point, launch['startedNanos'], launch['endedNanos'])
    boolean(launch['readinessVerified'])
    failure = launch['readinessFailureReason']
    if type(failure) is not str or failure not in READINESS_REASONS:
        raise ValueError('invalid_readiness_failure_reason')
    observations = launch['readinessObservations']
    if type(observations) is not list or len(observations) > 16:
        raise ValueError('invalid_readiness_observation_count')
    known = launch['status'] in ('passed', 'unavailable')
    if start == -1:
        if end != -1 or observations or launch['readinessVerified'] or failure or known:
            raise ValueError('missing_readiness_window')
        return
    if launch['launchEndedElapsedNanos'] == -1 or start < launch['launchEndedElapsedNanos']:
        raise ValueError('readiness_overlaps_launch_marker')
    if end != -1 and end < start:
        raise ValueError('invalid_readiness_window')
    deadline = start + 3_000_000_000
    previous_end = None
    # Provider checks use MONOTONIC; observation/Agent checks use BOOTTIME.
    # Compare each only to its own launch marker and subsequent same-clock reads.
    previous_provider_time = launch['launchEndedNanoTime']
    last_state = None
    last_ready = False
    for index, observation in enumerate(observations):
        fields(observation, ('index', 'startedNanos', 'endedNanos', 'providerBefore', 'providerAfter',
                             'agentBefore', 'agentAfter', 'a11y', 'reason'))
        integer(observation['index'], index, index)
        observation_start, observation_end = observation['startedNanos'], observation['endedNanos']
        integer(observation_start, start, min(deadline - 1, launch['endedNanos']))
        integer(observation_end, -1)
        if observation_end != -1:
            integer(observation_end, observation_start, end if end != -1 else launch['endedNanos'])
        if previous_end is not None and observation_start < previous_end + 200_000_000:
            raise ValueError('readiness_observation_interval_too_short')
        reason = observation['reason']
        if type(reason) is not str or reason not in READINESS_REASONS:
            raise ValueError('invalid_readiness_observation_reason')
        if index + 1 < len(observations) and reason:
            raise ValueError('readiness_continued_after_terminal_failure')
        upper = observation_end if observation_end != -1 else end if end != -1 else launch['endedNanos']
        for side in ('Before', 'After'):
            provider, agent = observation['provider' + side], observation['agent' + side]
            if provider is not None:
                if type(provider) is not list or len(provider) != 19:
                    raise ValueError('invalid_readiness_provider_snapshot')
                integer(provider[0], 1, 2 ** 31 - 1)
                uuid(provider[1]); uuid(provider[2])
                for point in provider[3:15]:
                    integer(point)
                for point in provider[15:18]:
                    boolean(point)
                integer(provider[18], max(0, previous_provider_time))
                validate_focus(dict(zip(READINESS_PROVIDER_FIELDS, provider[:18])), checked=provider[18])
                previous_provider_time = provider[18]
            if agent is not None:
                if type(agent) is not list or len(agent) != 5:
                    raise ValueError('invalid_readiness_agent_snapshot')
                for flag in agent[:4]:
                    boolean(flag)
                integer(agent[4], observation_start, upper)
        pb, pa = observation['providerBefore'], observation['providerAfter']
        ab, aa = observation['agentBefore'], observation['agentAfter']
        a11y = observation['a11y']
        # Preserve a partial final read, but never allow holes in the actual read order.
        if ((ab is not None and pb is None) or a11y is not None and (pb is None or ab is None)
                or aa is not None and a11y is None or pa is not None and aa is None):
            raise ValueError('readiness_snapshot_order_mismatch')
        if aa is not None and ab is not None and aa[4] < ab[4]:
            raise ValueError('readiness_agent_clock_regressed')
        if a11y is not None:
            validate_a11y(a11y)
        before_guard = readiness_guard(pb, ab, launch) if pb is not None and ab is not None else ''
        after_guard = readiness_guard(pa, aa, launch) if pa is not None and aa is not None else ''
        if before_guard and (a11y is not None or aa is not None or pa is not None):
            raise ValueError('readiness_read_after_failed_guard')
        guard = before_guard or after_guard
        complete = all(item is not None for item in (pb, ab, a11y, aa, pa)) and observation_end != -1
        if guard and reason != guard:
            raise ValueError('readiness_guard_failure_hidden')
        if reason in READINESS_REASONS[1:6] and reason != guard:
            raise ValueError('readiness_guard_reason_mismatch')
        if not reason and (not complete or guard):
            raise ValueError('incomplete_stable_readiness_observation')
        if reason == 'a11y_timeout' and (guard or not complete or end == -1 or end < deadline):
            raise ValueError('invalid_readiness_timeout')
        ready = a11y is not None and a11y['ready']
        if index + 1 < len(observations) and (not complete or ready or observation_end >= deadline):
            raise ValueError('readiness_continued_after_ready_or_deadline')
        if aa is not None or ab is not None:
            agent = aa if aa is not None else ab
            last_state = dict(zip(STATE_FLAGS[:4], agent[:4]))
            last_state.update(checkedNanos=agent[4], fixtureFocused=ready)
        previous_end = observation_end if observation_end != -1 else upper
        last_ready = bool(complete and not guard and ready and not reason)
    if last_state is not None and launch['stateAfter'] != last_state:
        raise ValueError('readiness_final_state_mismatch')
    if failure:
        if launch['readinessVerified'] or known or observations and observations[-1]['reason'] != failure:
            raise ValueError('readiness_terminal_failure_mismatch')
        if not observations and failure not in ('a11y_timeout', 'observation_failed'):
            raise ValueError('readiness_guard_without_observation')
        if failure == 'a11y_timeout' and (end == -1 or end < deadline):
            raise ValueError('readiness_timeout_before_deadline')
    elif any(observation['reason'] for observation in observations):
        raise ValueError('readiness_failure_reason_missing')
    if launch['readinessVerified']:
        if not observations or not last_ready or end == -1 or end >= deadline or failure:
            raise ValueError('invalid_verified_readiness')
    elif known or start != -1 and not failure:
        raise ValueError('launch_readiness_not_verified')


def validate_launch(value, index, report):
    version = report['schemaVersion']
    fields(value, ('schemaVersion', 'kind', 'index', 'pairIndex', 'position', 'arm', 'startupType', 'status',
                   'startedNanos', 'endedNanos', 'marker', 'driverPid', 'expectedPid', 'expectedStartupType',
                   'preLaunchPidAbsent', 'homeStoppedVerified', 'lifecycleVerified', 'newDrawVerified',
                   'stateBefore', 'stateAfter', 'beforeLaunch', 'afterLaunch', 'launchStartedElapsedNanos',
                   'launchEndedElapsedNanos', 'launchStartedNanoTime', 'launchEndedNanoTime', 'amStartOutput', 'unavailableReason',
                   *(('homePackage', 'homeFocusedVerified') if version >= 2 else ()),
                   *(READINESS_FIELDS if version == 3 else ())))
    pair, position, arm, startup_type = launch_identity(index, report['profile'])
    integer(value['schemaVersion'], version, version); integer(value['index'], index, index)
    integer(value['pairIndex'], pair, pair); integer(value['position'], position, position)
    if (value['kind'] != LAUNCH_KIND or value['arm'] != arm or value['startupType'] != startup_type
            or value['expectedStartupType'] != startup_type or value['marker'] != f'aos.experience.launch.{index}'
            or value['status'] not in ('passed', 'failed', 'unavailable')):
        raise ValueError('invalid_launch_identity')
    integer(value['driverPid'], report['appProcessPid'], report['appProcessPid'])
    integer(value['expectedPid'], -1, 2 ** 31 - 1)
    integer(value['startedNanos']); integer(value['endedNanos'], value['startedNanos'])
    for key in ('preLaunchPidAbsent', 'homeStoppedVerified', 'lifecycleVerified', 'newDrawVerified'):
        boolean(value[key])
    for prefix in ('launchStarted', 'launchEnded'):
        for suffix in ('ElapsedNanos', 'NanoTime'):
            integer(value[prefix + suffix], -1)
        elapsed = value[prefix + 'ElapsedNanos']
        if elapsed != -1:
            integer(elapsed, value['startedNanos'], value['endedNanos'])
    for suffix in ('ElapsedNanos', 'NanoTime'):
        start, end = value['launchStarted' + suffix], value['launchEnded' + suffix]
        if end != -1 and (start == -1 or end <= start):
            raise ValueError('invalid_launch_marker_order')
    bounded_text(value['amStartOutput'], 8192, empty=True)
    bounded_text(value['unavailableReason'], 128, empty=True)
    if version >= 2:
        boolean(value['homeFocusedVerified'])
        bounded_text(value['homePackage'], 256, empty=True)
        if value['homePackage'] and not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*', value['homePackage']):
            raise ValueError('invalid_home_package')
        if startup_type == 'cold' and (value['homePackage'] or value['homeFocusedVerified']):
            raise ValueError('cold_launch_cannot_claim_home_preparation')
    known = value['status'] in ('passed', 'unavailable')
    for key in ('stateBefore', 'stateAfter'):
        if value[key] is not None:
            validate_state(value[key], arm, known, value['startedNanos'], value['endedNanos'], focused=key == 'stateAfter')
        elif known:
            raise ValueError('missing_launch_endpoint')
    for key in ('beforeLaunch', 'afterLaunch'):
        if value[key] is not None:
            validate_probe(value[key], schema_version=version)
    after, before = value['afterLaunch'], value['beforeLaunch']
    if after is not None and value['expectedPid'] != after['pid']:
        raise ValueError('target_pid_does_not_match_launch')
    if known:
        if (after is None or not value['lifecycleVerified'] or any(value[k] < 0 for k in
                ('launchStartedElapsedNanos', 'launchEndedElapsedNanos', 'launchStartedNanoTime', 'launchEndedNanoTime'))
                or not all(after[k] for k in ('activityPresent', 'resumed', 'windowFocused', 'hardwareAccelerated')) or after['stopped']):
            raise ValueError('incomplete_launch_lifecycle')
        if startup_type == 'cold':
            if not value['preLaunchPidAbsent'] or value['homeStoppedVerified'] or before is not None or after['createCount'] != 1 or after['resumeCount'] != 1 or after['createdNanoTime'] < value['launchStartedNanoTime']:
                raise ValueError('cold_launch_requires_absent_process')
        else:
            if version >= 2 and (not value['homeFocusedVerified'] or not value['homePackage']):
                raise ValueError('hot_launch_requires_focused_home')
            if value['preLaunchPidAbsent'] or not value['homeStoppedVerified'] or before is None or not before['stopped'] or before['resumed']:
                raise ValueError('hot_launch_requires_stopped_activity')
            for key in ('pid', 'uid', 'processInstance', 'activityInstance', 'createCount'):
                if before[key] != after[key]:
                    raise ValueError('hot_launch_recreated_process_or_activity')
            if after['resumeCount'] <= before['resumeCount']:
                raise ValueError('hot_launch_did_not_resume')
        if value['status'] == 'passed':
            if (not value['newDrawVerified'] or after['unresolvedDrawCount'] != (before['unresolvedDrawCount'] if before else 0)
                    or after['drawMatchAmbiguous'] or not after['drawFrameObserved'] or after['drawVsyncNs'] <= (before['drawVsyncNs'] if before else 0)
                    or value['unavailableReason']):
                raise ValueError('passed_launch_missing_new_draw')
        elif version == 1:
            if value['newDrawVerified'] or value['unavailableReason'] != 'no_new_draw_frame_report':
                raise ValueError('invalid_unavailable_launch_reason')
        else:
            matched = (after['drawFrameObserved'] and not after['drawMatchAmbiguous']
                       and after['drawVsyncNs'] > (before['drawVsyncNs'] if before else 0))
            unresolved = after['unresolvedDrawCount'] > (before['unresolvedDrawCount'] if before else 0)
            if (value['newDrawVerified'] != matched
                    or value['unavailableReason'] == 'no_new_draw_frame_report' and matched
                    or value['unavailableReason'] == 'unresolved_draw_observation' and not (matched and unresolved)
                    or value['unavailableReason'] not in ('no_new_draw_frame_report', 'unresolved_draw_observation')):
                raise ValueError('invalid_unavailable_launch_reason')
    if version == 3:
        validate_readiness(value)
    return value


def validate_frames(frames, target, api, passed):
    if type(frames) is not list or len(frames) > 4096:
        raise ValueError('invalid_frame_count')
    if target is None:
        if frames:
            raise ValueError('frames_without_target_session')
        return
    if passed and len(frames) != target['frameCount']:
        raise ValueError('missing_target_frame_pages')
    if len(frames) > target['frameCount']:
        raise ValueError('excess_target_frames')
    for index, frame in enumerate(frames):
        fields(frame, FRAME_FIELDS)
        integer(frame['index'], index, index)
        for key in ('intendedVsyncNs', 'vsyncNs', 'totalDurationNs', 'callbackNanoTime', 'droppedReports', 'refreshObservedNanoTime'):
            integer(frame[key])
        integer(frame['drawVersion'], -1)
        integer(frame['drawNanoTime'], -1)
        boolean(frame['crossesBegin'])
        if (frame['drawVersion'] == -1) != (frame['drawNanoTime'] == -1):
            raise ValueError('incomplete_frame_draw_association')
        integer(frame['firstDraw'], 0, 1)
        for key in ('gpuDurationNs', 'deadlineNs'):
            integer(frame[key], -1, -1 if api == 30 else LONG_MAX)
        for key in ('refreshHz', 'modeHz'):
            hz(frame[key])
        integer(frame['displayId'], -1, 2 ** 31 - 1)
        crosses = frame['intendedVsyncNs'] < target['beginNanoTime'] and frame['drawNanoTime'] >= target['beginNanoTime']
        if (frame['crossesBegin'] != crosses or frame['intendedVsyncNs'] < target['beginNanoTime'] and not crosses
                or target['ended'] and frame['intendedVsyncNs'] > target['endNanoTime']
                or target['frameCutoffVsyncNs'] > 0 and frame['vsyncNs'] > target['frameCutoffVsyncNs']
                or frame['callbackNanoTime'] < frame['vsyncNs'] or frame['drawVersion'] > target['mutationVersion']):
            raise ValueError('frame_outside_target_task')
    if passed:
        if not frames or not any(frame['vsyncNs'] == target['finalDrawVsyncNs'] and frame['drawVersion'] == target['finalDrawVersion']
                                 and frame['drawNanoTime'] >= target['beginNanoTime'] for frame in frames):
            raise ValueError('final_draw_missing_matching_frame')
        if any(frame['refreshHz'] <= 0 or frame['modeHz'] <= 0 or frame['displayId'] < 0 for frame in frames):
            raise ValueError('passed_frame_missing_observed_display')
        if any(frame['droppedReports'] for frame in frames):
            raise ValueError('passed_trial_has_dropped_frame_reports')


def validate_trial(value, index, report):
    version = report['schemaVersion']
    fields(value, ('schemaVersion', 'kind', 'index', 'repetition', 'position', 'arm', 'status', 'startedNanos', 'endedNanos',
                   'sessionId', 'initialStateVerified', 'editVerified', 'savedTextVerified', 'counterVerified', 'drawingVerified',
                   'stateBefore', 'stateAfter', 'agentBeforeSample', 'agentAfterSample', 'agentTaskStartedNanos', 'agentTaskEndedNanos',
                   'agentTaskStartedCpuMillis', 'agentTaskEndedCpuMillis', 'targetBefore', 'targetAfter', 'frames', 'framePageChecks',
                   'manualActionsAttempted', 'manualActionsAccepted', 'modelRequests', 'modelAcceptedWrites', 'approvalCount',
                   'resultKind', 'resultCode', 'cleanupCompleted'))
    integer(value['schemaVersion'], version, version); integer(value['index'], index, index)
    integer(value['repetition'], index // 3, index // 3); integer(value['position'], index % 3, index % 3)
    if value['kind'] != TRIAL_KIND or value['arm'] != trial_arm(index) or value['status'] not in ('passed', 'failed'):
        raise ValueError('invalid_resource_trial_identity')
    passed, arm = value['status'] == 'passed', value['arm']
    integer(value['startedNanos']); integer(value['endedNanos'], value['startedNanos'])
    bounded_text(value['sessionId'], 36, empty=True)
    if value['sessionId']:
        uuid(value['sessionId'])
    flags = ('initialStateVerified', 'editVerified', 'savedTextVerified', 'counterVerified', 'drawingVerified', 'cleanupCompleted')
    for key in flags:
        boolean(value[key])
    for key in ('agentTaskStartedNanos', 'agentTaskEndedNanos', 'agentTaskStartedCpuMillis', 'agentTaskEndedCpuMillis',
                'manualActionsAttempted', 'manualActionsAccepted', 'modelRequests', 'modelAcceptedWrites', 'approvalCount'):
        integer(value[key], -1)
    for key in ('agentTaskStartedNanos', 'agentTaskEndedNanos'):
        if value[key] != -1:
            integer(value[key], value['startedNanos'], value['endedNanos'])
    for suffix in ('Nanos', 'CpuMillis'):
        start, end = value['agentTaskStarted' + suffix], value['agentTaskEnded' + suffix]
        if end != -1 and (start == -1 or end < start or suffix == 'Nanos' and end == start):
            raise ValueError('invalid_agent_task_markers')
    if value['manualActionsAccepted'] > value['manualActionsAttempted'] >= 0:
        raise ValueError('accepted_manual_actions_exceed_attempts')
    if type(value['resultKind']) is not str or value['resultKind'] not in RESULT_KINDS:
        raise ValueError('invalid_controller_result')
    if type(value['resultCode']) is not str or not re.fullmatch(r'[A-Z_]{0,80}', value['resultCode']):
        raise ValueError('invalid_controller_code')
    for key in ('stateBefore', 'stateAfter'):
        if value[key] is not None:
            validate_state(value[key], arm, passed, value['startedNanos'], value['endedNanos'])
        elif passed:
            raise ValueError('missing_trial_endpoint')
    for key in ('agentBeforeSample', 'agentAfterSample'):
        if value[key] is not None:
            validate_sample(value[key], value)
        elif passed:
            raise ValueError('missing_agent_resource_sample')
    before, after = value['agentBeforeSample'], value['agentAfterSample']
    task_start, task_end = value['agentTaskStartedNanos'], value['agentTaskEndedNanos']
    if before is not None:
        if (task_start != -1 and before['elapsedRealtimeNanos'] + before['collectionNanos'] > task_start
                or value['agentTaskStartedCpuMillis'] != -1 and before['processCpuMillis'] > value['agentTaskStartedCpuMillis']
                or value['stateBefore'] is not None and value['stateBefore']['checkedNanos'] > before['elapsedRealtimeNanos']):
            raise ValueError('before_probe_overlaps_agent_task')
    if after is not None:
        if (task_end != -1 and after['elapsedRealtimeNanos'] < task_end
                or value['agentTaskEndedCpuMillis'] != -1 and after['processCpuMillis'] < value['agentTaskEndedCpuMillis']
                or value['stateAfter'] is not None and after['elapsedRealtimeNanos'] + after['collectionNanos'] > value['stateAfter']['checkedNanos']):
            raise ValueError('after_probe_overlaps_agent_task')
    if before is not None and after is not None and (before['processCpuMillis'] > after['processCpuMillis']
            or before['elapsedRealtimeNanos'] + before['collectionNanos'] > after['elapsedRealtimeNanos']):
        raise ValueError('agent_samples_regress')
    target_before, target_after = value['targetBefore'], value['targetAfter']
    for point in (target_before, target_after):
        if point is not None:
            validate_probe(point, session=True, schema_version=version)
            if point['sessionId'] != value['sessionId']:
                raise ValueError('target_session_mismatch')
        elif passed:
            raise ValueError('missing_target_sample')
    if target_before is not None and target_before['ended']:
        raise ValueError('target_session_already_ended_at_begin')
    if target_before is not None and target_after is not None:
        for key in (*IDENTITY_FIELDS, 'sessionId', 'beginCpuMillis', 'beginNanoTime', 'beforePssKb', 'beginMutationVersion', 'beginRefreshHz', 'beginModeHz', 'beginDisplayChangeCount', 'beginDisplayObservedNanoTime', 'beginUnresolvedDrawCount',
                    *('before' + key for key in MEMORY_FIELDS)):
            if type(target_before[key]) is not type(target_after[key]) or target_before[key] != target_after[key]:
                raise ValueError('target_session_identity_changed')
    validate_frames(value['frames'], target_after, report['device']['sdkInt'], passed)
    checks = value['framePageChecks']
    if type(checks) is not list or len(checks) > 64:
        raise ValueError('invalid_frame_page_count')
    next_offset = 0
    for check in checks:
        fields(check, ('offset', 'nextOffset', 'totalFrames', 'drainComplete', 'lateFrameReports', 'lateDroppedReports'))
        for key in ('offset', 'nextOffset', 'totalFrames'):
            integer(check[key], 0, 4096)
        boolean(check['drainComplete']); integer(check['lateFrameReports']); integer(check['lateDroppedReports'])
        if passed and (check['offset'] != next_offset or check['totalFrames'] != target_after['frameCount']
                or check['nextOffset'] != min(check['offset'] + 64, check['totalFrames'])
                or check['nextOffset'] <= check['offset'] or not check['drainComplete'] or check['lateFrameReports'] or check['lateDroppedReports']):
            raise ValueError('invalid_or_late_frame_page')
        next_offset = check['nextOffset']
    if passed and next_offset != len(value['frames']):
        raise ValueError('incomplete_frame_page_coverage')
    if passed:
        if (not all(value[key] for key in flags) or not value['sessionId'] or min(task_start, task_end,
                value['agentTaskStartedCpuMillis'], value['agentTaskEndedCpuMillis']) < 0):
            raise ValueError('passed_resource_trial_incomplete')
        if (target_after['endNanoTime'] <= target_after['beginNanoTime'] or not all(target_after[k] for k in ('ended', 'finalState', 'finalDrawObserved', 'finalFrameObserved', 'drainComplete', 'hardwareAccelerated', 'displayStable'))
                or target_after['drawMatchAmbiguous'] or target_after['drainTimedOut'] or target_after['unresolvedTaskDraws']
                or target_after['beginDisplayChangeCount'] != target_after['endDisplayChangeCount'] or any(target_after[k] for k in ('frameOverflow', 'droppedReports', 'lateFrameReports', 'lateDroppedReports'))
                or target_after['finalMutationVersion'] != target_after['finalDrawVersion']
                or target_after['finalDrawVsyncNs'] != target_after['frameCutoffVsyncNs']):
            raise ValueError('passed_resource_trial_incomplete_frames')
        if (any(target_after[key] <= 0 for key in SESSION_HZ) or target_after['displayId'] < 0
                or target_after['beginRefreshHz'] != target_after['endRefreshHz']
                or target_after['beginModeHz'] != target_after['endModeHz']):
            raise ValueError('passed_target_display_unavailable_or_changed')
        if (target_before['editorText'] or target_before['savedText'] != 'Saved: empty' or target_before['clicks']
                or target_before['mutationVersion'] or target_before['beginMutationVersion']
                or target_after['editorText'] != 'Controller integration sample'
                or target_after['savedText'] != 'Saved: Controller integration sample'
                or any(target_after[k] != 1 for k in ('clicks', 'editCount', 'saveCount', 'incrementCount'))):
            raise ValueError('target_ui_result_mismatch')
        if arm == ARMS[2]:
            if (value['manualActionsAttempted'] != 0 or value['manualActionsAccepted'] != 0 or value['modelAcceptedWrites'] != 3
                    or value['modelRequests'] <= 0 or value['approvalCount'] < 3
                    or value['resultKind'] != 'MODEL_REPORT' or value['resultCode'] != 'MODEL_REPORT'):
                raise ValueError('invalid_agent_resource_actions')
        elif (value['manualActionsAttempted'] != 3 or value['manualActionsAccepted'] != 3 or value['modelRequests'] != 0
              or value['modelAcceptedWrites'] != 0 or value['approvalCount'] != 0 or value['resultKind'] or value['resultCode']):
            raise ValueError('invalid_manual_resource_actions')
    return value


def file_reference(path, status=None):
    path = Path(path)
    data = path.read_bytes()
    result = dict(path=path.name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
    if status is not None:
        result['status'] = status
    return result


def read_bounded(path, maximum):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= maximum:
        raise ValueError('invalid_bounded_payload')
    with path.open('rb') as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError('payload_grew_beyond_budget')
    return data


def _validate_raw_bundle(directory, report, api, profile=None, artifact_limit=None):
    directory = Path(directory)
    validate_report(report, api, profile, artifact_limit)
    root = read_bounded(directory / REPORT_NAME, MAX_REPORT_BYTES)
    if strict_json(root) != report:
        raise ValueError('root_report_does_not_match_bundle')
    payloads = {REPORT_NAME: root}
    previous_cpu = 0
    process_instances, last_launch = set(), None
    for kind in ('launches', 'trials'):
        for ref in report[kind]:
            data = read_bounded(directory / ref['path'], MAX_LAUNCH_BYTES if kind == 'launches' else MAX_TRIAL_BYTES)
            if len(data) != ref['bytes'] or hashlib.sha256(data).hexdigest() != ref['sha256']:
                raise ValueError('shard_digest_or_size_mismatch')
            value = strict_json(data)
            (validate_launch if kind == 'launches' else validate_trial)(value, ref['index'], report)
            for key in ('index', 'status', 'startedNanos', 'endedNanos', *(() if kind == 'trials' else ('marker', 'expectedPid', 'expectedStartupType', 'driverPid'))):
                if type(value[key]) is not type(ref[key]) or value[key] != ref[key]:
                    raise ValueError('shard_reference_mismatch')
            if kind == 'launches' and value['afterLaunch'] is not None and value['status'] != 'failed':
                current = value['afterLaunch']
                if value['startupType'] == 'cold':
                    if current['processInstance'] in process_instances:
                        raise ValueError('cold_launch_reused_process')
                    process_instances.add(current['processInstance'])
                elif last_launch is not None:
                    for key in ('pid', 'processInstance', 'activityInstance', 'createCount'):
                        if current[key] != last_launch[key]:
                            raise ValueError('hot_series_recreated_target')
                last_launch = current
            if kind == 'trials':
                points = [(value['agentBeforeSample']['processCpuMillis'] if value['agentBeforeSample'] else -1),
                          value['agentTaskStartedCpuMillis'], value['agentTaskEndedCpuMillis'],
                          (value['agentAfterSample']['processCpuMillis'] if value['agentAfterSample'] else -1)]
                for cpu in points:
                    if cpu != -1:
                        if cpu < previous_cpu:
                            raise ValueError('cross_trial_agent_cpu_regression')
                        previous_cpu = cpu
            payloads[ref['path']] = data
    return payloads


def limits_for_trace_capture(value):
    """Capacity belongs to recorded capture versions, never the current default."""
    if value is None:
        version = 1
    else:
        if type(value) is not dict:
            raise ValueError('invalid_trace_capture_identity')
        integer(value.get('schemaVersion'), 1, 4)
        version = value['schemaVersion']
        config_version = version - 1 if version >= 3 else 1
        if version >= 3:
            integer(value.get('configVersion'), config_version, config_version)
        from experience_trace import trace_config
        if value.get('config') != trace_config(version=config_version):
            raise ValueError('trace_config_mismatch')
    if version == 4:
        return dict(raw=MAX_TRACE_BYTES, gzip=MAX_GZIP_BYTES, lane=MAX_ARTIFACT_BYTES)
    return dict(raw=LEGACY_MAX_TRACE_BYTES, gzip=LEGACY_MAX_GZIP_BYTES, lane=LEGACY_MAX_ARTIFACT_BYTES)


def validate_trace_capture(value, payloads):
    if type(value) is not dict:
        raise ValueError('invalid_trace_capture_identity')
    integer(value.get('schemaVersion'), 1, 4)
    limits = limits_for_trace_capture(value)
    expected = ('schemaVersion', 'kind', 'status', 'pid', 'stopped', 'config', 'trace', 'compressed',
                'error', 'startMode', 'ready')
    if value['schemaVersion'] >= 2:
        expected += ('configDelivery', 'startStdout', 'startStderr', 'startExitCode')
    if value['schemaVersion'] >= 3:
        expected += ('configVersion',)
    fields(value, expected)
    if value['schemaVersion'] >= 3:
        expected_config = value['schemaVersion'] - 1
        integer(value['configVersion'], expected_config, expected_config)
    if value['schemaVersion'] >= 2:
        if value['configDelivery'] != 'stdin':
            raise ValueError('invalid_trace_config_delivery')
        for key in ('startStdout', 'startStderr'):
            if type(value[key]) is not str or len(value[key].encode('utf-8')) > 2048:
                raise ValueError('invalid_trace_start_output')
        if value['startExitCode'] is not None:
            integer(value['startExitCode'], -255, 255)
        if value['status'] in ('recording', 'collected') and value['startExitCode'] != 0:
            raise ValueError('trace_capture_start_did_not_succeed')
    if value['kind'] != 'agent-os-experience-trace-capture' or value['status'] not in ('recording', 'collected', 'failed'):
        raise ValueError('invalid_trace_capture_identity')
    if value['pid'] is not None:
        integer(value['pid'], 1, 999999999)
    for key in ('stopped', 'ready'):
        boolean(value[key])
    if value['startMode'] not in (None, '--background-wait', '--background'):
        raise ValueError('invalid_trace_start_mode')
    bounded_text(value['config'], 8192)
    from experience_trace import trace_config
    if value['config'] != trace_config(version=value['schemaVersion'] - 1 if value['schemaVersion'] >= 3 else 1):
        raise ValueError('trace_config_mismatch')
    if value['error'] is not None:
        bounded_text(value['error'], 2000, empty=True)
    if (value['trace'] is None) != (value['compressed'] is None):
        raise ValueError('incomplete_trace_file_references')
    if value['status'] == 'collected' and (value['pid'] is None or not value['stopped'] or not value['ready']
            or value['startMode'] is None or value['error'] is not None or value['trace'] is None):
        raise ValueError('trace_capture_cannot_be_collected')
    if value['trace'] is None:
        if 'trace.pftrace.gz' in payloads:
            raise ValueError('unreferenced_compressed_trace')
        return
    raw_maximum = limits['raw'] if value['status'] == 'failed' else limits['raw'] - 1
    for key, name, maximum in (('trace', 'trace.pftrace', raw_maximum),
                               ('compressed', 'trace.pftrace.gz', limits['gzip'])):
        ref = value[key]
        fields(ref, ('path', 'bytes', 'sha256'))
        if ref['path'] != name:
            raise ValueError('invalid_trace_reference_path')
        integer(ref['bytes'], 1, maximum); digest(ref['sha256'])
    data = payloads.get('trace.pftrace.gz')
    if data is None or len(data) != value['compressed']['bytes'] or hashlib.sha256(data).hexdigest() != value['compressed']['sha256']:
        raise ValueError('compressed_trace_reference_mismatch')
    total, sha = 0, hashlib.sha256()
    inflater = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        for offset in range(0, len(data), 65536):
            pending = data[offset:offset + 65536]
            while pending:
                block = inflater.decompress(pending, min(65536, limits['raw'] - total + 1))
                pending = inflater.unconsumed_tail
                total += len(block)
                if total > limits['raw'] or total == limits['raw'] and value['status'] != 'failed':
                    raise ValueError('uncompressed_trace_exceeds_budget')
                sha.update(block)
                if inflater.unused_data:
                    raise ValueError('multiple_or_trailing_gzip_members')
        if not inflater.eof:
            raise ValueError('truncated_gzip_trace')
    except zlib.error as error:
        raise ValueError('invalid_gzip_trace') from error
    if total != value['trace']['bytes'] or sha.hexdigest() != value['trace']['sha256']:
        raise ValueError('uncompressed_trace_reference_mismatch')


def validate_bundle(directory, value, api, profile=None):
    directory = Path(directory)
    # Read small versioned metadata before permitting any large gzip allocation.
    metadata_path = directory / 'trace-capture.json'
    metadata_bytes = read_bounded(metadata_path, 16384) if metadata_path.exists() or metadata_path.is_symlink() else None
    metadata = strict_json(metadata_bytes) if metadata_bytes is not None else None
    limits = limits_for_trace_capture(metadata)
    payloads = _validate_raw_bundle(directory, value, api, profile, limits['lane']) if value is not None else {}
    if metadata_bytes is not None:
        payloads['trace-capture.json'] = metadata_bytes
    for name in HOST_NAMES:
        if name == 'trace-capture.json':
            continue
        path = directory / name
        if path.exists() or path.is_symlink():
            maximum = limits['gzip'] if name.endswith('.gz') else MAX_DERIVED_BYTES
            remaining = limits['lane'] - MAX_CAPTURE_BYTES - sum(map(len, payloads.values()))
            if remaining <= 0:
                raise ValueError('experience_artifact_exceeds_budget')
            payloads[name] = read_bounded(path, min(maximum, remaining))
    expected = set(payloads) | {'capture.json'}
    if any(path.name not in expected or path.is_symlink() or not path.is_file() for path in directory.iterdir()):
        raise ValueError('unexpected_experience_bundle_file')
    if sum(map(len, payloads.values())) + MAX_CAPTURE_BYTES > limits['lane']:
        raise ValueError('experience_artifact_exceeds_budget')
    if 'trace-capture.json' in payloads:
        validate_trace_capture(strict_json(payloads['trace-capture.json']), payloads)
    elif 'trace.pftrace.gz' in payloads:
        raise ValueError('compressed_trace_missing_capture_record')
    if ('startup-ttid.json' in payloads) != ('startup-ttid.csv' in payloads):
        raise ValueError('incomplete_startup_derivation_files')
    if 'startup-ttid.json' in payloads:
        if value is None or 'trace.pftrace.gz' not in payloads:
            raise ValueError('derivation_missing_report_or_trace')
        if metadata['trace']['bytes'] >= limits['raw']:
            raise ValueError('startup_derivation_requires_unsaturated_trace')
        from experience_trace import validate_derivation
        derived = strict_json(payloads['startup-ttid.json'])
        if metadata['schemaVersion'] >= 3:
            integer(derived.get('schemaVersion') if type(derived) is dict else None, 2, 2)
        validate_derivation(derived, value, directory / 'trace.pftrace.gz', directory / 'startup-ttid.csv',
                            raw_limit=limits['raw'], gzip_limit=limits['gzip'])
        trace = derived['trace']
        data = payloads['trace.pftrace.gz']
        if (trace['path'] != 'trace.pftrace.gz' or trace['bytes'] != len(data)
                or trace['sha256'] != hashlib.sha256(data).hexdigest()
                or trace['uncompressedBytes'] != strict_json(payloads['trace-capture.json'])['trace']['bytes']):
            raise ValueError('derivation_trace_source_mismatch')
    return payloads


def diagnostic_status(report, payloads, gradle_exit):
    if report is None:
        return 'unavailable'
    if report['status'] == 'incomplete':
        return 'incomplete'
    if report['status'] != 'passed' or gradle_exit != 0:
        return 'failed'
    if not all(name in payloads for name in HOST_NAMES):
        return 'failed'
    if (strict_json(payloads['trace-capture.json'])['status'] != 'collected'
            or strict_json(payloads['startup-ttid.json'])['status'] != 'passed'):
        return 'failed'
    return 'passed'


def capture_status(report, payloads):
    if report is None:
        return 'unavailable'
    return 'collected' if all(name in payloads for name in HOST_NAMES) else 'partial'


def empty_capture(label, source, profile='formal'):
    scope = next((key for key, value in SCOPES.items() if value == profile), None)
    if scope is None:
        raise ValueError('invalid_experience_profile')
    return dict(schemaVersion=1, kind=CAPTURE_KIND, diagnosticOnly=True, budgetAcceptance=False,
                matrixLabel=label, apiLevel=int(label.split('.')[0]), actualSdkInt=None, sourceRun=dict(source),
                capturedAt=datetime.now(timezone.utc).isoformat(), declaredCiProfile='default_x86_64_swiftshader_animation_one',
                profile=profile, executionScope=scope, captureStatus='not_collected', captureError='emulator_script_did_not_finish',
                gradleExitCode=None, diagnosticStatus='unavailable', report=None, payloads=[])


def validate_capture(value, label, source, profile=None):
    expected = ('schemaVersion', 'kind', 'diagnosticOnly', 'budgetAcceptance', 'matrixLabel', 'apiLevel', 'actualSdkInt',
                'sourceRun', 'capturedAt', 'declaredCiProfile', 'profile', 'executionScope', 'captureStatus', 'captureError',
                'gradleExitCode', 'diagnosticStatus', 'report', 'payloads')
    if type(value) is dict and 'emulatorStepOutcome' in value:
        expected += ('emulatorStepOutcome',)
        if value['emulatorStepOutcome'] not in ('success', 'failure', 'cancelled', 'skipped', 'unknown'):
            raise ValueError('invalid_emulator_outcome')
    fields(value, expected)
    integer(value['schemaVersion'], 1, 1)
    exact(value['sourceRun'], source, 'capture_source_mismatch')
    if (value['kind'] != CAPTURE_KIND or value['diagnosticOnly'] is not True or value['budgetAcceptance'] is not False
            or value['matrixLabel'] != label or type(value['profile']) is not str or value['profile'] not in PROFILES
            or profile is not None and value['profile'] != profile
            or profile_for_scope(value['executionScope']) != value['profile']
            or value['declaredCiProfile'] != 'default_x86_64_swiftshader_animation_one'):
        raise ValueError('capture_identity_mismatch')
    api = int(label.split('.')[0]); integer(value['apiLevel'], api, api)
    if value['actualSdkInt'] is not None:
        integer(value['actualSdkInt'], api, api)
    if value['gradleExitCode'] is not None:
        integer(value['gradleExitCode'], 0, 255)
    if (type(value['capturedAt']) is not str or len(value['capturedAt']) > 40
            or datetime.fromisoformat(value['capturedAt']).tzinfo is None):
        raise ValueError('invalid_capture_timestamp')
    if value['captureStatus'] not in ('not_collected', 'unavailable', 'invalid', 'partial', 'collected'):
        raise ValueError('invalid_capture_status')
    if value['diagnosticStatus'] not in ('passed', 'failed', 'incomplete', 'unavailable'):
        raise ValueError('invalid_diagnostic_status')
    if value['captureError'] is not None and (type(value['captureError']) is not str or not re.fullmatch(r'[a-z_]{1,80}', value['captureError'])):
        raise ValueError('invalid_capture_error')
    if value['captureStatus'] == 'collected':
        if value['captureError'] is not None or value['report'] is None:
            raise ValueError('invalid_collected_capture')
    elif value['captureError'] is None:
        raise ValueError('missing_capture_error')
    if value['report'] is not None:
        ref = value['report']; fields(ref, ('path', 'bytes', 'sha256', 'status'))
        if ref['path'] != REPORT_NAME or ref['status'] not in STATUSES or value['actualSdkInt'] != api:
            raise ValueError('invalid_capture_report_reference')
        integer(ref['bytes'], 1, MAX_REPORT_BYTES); digest(ref['sha256'])
    elif value['diagnosticStatus'] != 'unavailable' or value['captureStatus'] in ('partial', 'collected'):
        raise ValueError('missing_capture_report')
    refs = value['payloads']
    if type(refs) is not list or len(refs) > 423:
        raise ValueError('invalid_capture_payload_count')
    names, total = set(), 0
    for ref in refs:
        fields(ref, ('path', 'bytes', 'sha256'))
        name = ref['path']
        if type(name) is not str or (name not in (REPORT_NAME, *HOST_NAMES) and not re.fullmatch(r'(?:launch|trial)-[0-9]{3}\.json', name)) or name in names:
            raise ValueError('invalid_capture_payload_path')
        if name.startswith('launch-'):
            integer(int(name[7:10]), 0, conditions(value['profile'])['launchCount'] - 1)
        if name.startswith('trial-'):
            integer(int(name[6:9]), 0, conditions(value['profile'])['trialCount'] - 1)
        integer(ref['bytes'], 1, MAX_ARTIFACT_BYTES); digest(ref['sha256'])
        names.add(name); total += ref['bytes']
    if total + MAX_CAPTURE_BYTES > MAX_ARTIFACT_BYTES:
        raise ValueError('experience_artifact_exceeds_budget')
    if value['report'] is not None:
        expected_ref = {key: value['report'][key] for key in ('path', 'bytes', 'sha256')}
        if expected_ref not in refs:
            raise ValueError('root_missing_from_capture_payloads')
    if value['diagnosticStatus'] == 'passed' and (value['captureStatus'] != 'collected' or value['report']['status'] != 'passed'
            or value['gradleExitCode'] != 0 or not all(name in names for name in HOST_NAMES)):
        raise ValueError('passed_capture_missing_evidence')
    return value


def validate_payloads(directory, capture, label, source, profile=None):
    validate_capture(capture, label, source, profile)
    directory = Path(directory)
    report = read_json(directory / REPORT_NAME, MAX_REPORT_BYTES) if capture['report'] is not None else None
    payloads = validate_bundle(directory, report, int(label.split('.')[0]), profile or capture['profile'])
    refs = {ref['path']: ref for ref in capture['payloads']}
    if set(refs) != set(payloads):
        raise ValueError('capture_payload_set_mismatch')
    for name, data in payloads.items():
        if refs[name]['bytes'] != len(data) or refs[name]['sha256'] != hashlib.sha256(data).hexdigest():
            raise ValueError('capture_payload_digest_mismatch')
    if report is not None and capture['report']['status'] != report['status']:
        raise ValueError('capture_raw_status_mismatch')
    if capture['diagnosticStatus'] != diagnostic_status(report, payloads, capture['gradleExitCode']):
        raise ValueError('capture_combined_status_mismatch')
    expected_status = capture_status(report, payloads)
    if capture['captureStatus'] != expected_status and not (report is None and capture['captureStatus'] in ('not_collected', 'invalid')):
        raise ValueError('capture_collection_status_mismatch')
    return payloads


def pull_file(name, target, maximum, deadline):
    with target.open('wb') as output:
        result = subprocess.run(['adb', 'exec-out', 'run-as', 'dev.agentos.app', 'cat',
                                 'files/performance/app-experience/' + name], stdout=output, stderr=subprocess.DEVNULL,
                                check=False, timeout=remaining_timeout(deadline), preexec_fn=limited_output(maximum))
    if target.stat().st_size > maximum:
        raise ValueError('report_exceeds_collection_budget')
    if result.returncode != 0:
        raise ValueError('committed_report_unavailable')


def pull_shards(report, folder, deadline):
    refs = {ref['path']: ref for kind in ('launches', 'trials') for ref in report[kind]}
    if not refs:
        return
    # One fixed-argument run-as read avoids hundreds of adb process startups.
    # Only validated manifest names are ever passed; no shell/glob extraction.
    limit = sum(ref['bytes'] + 1024 for ref in refs.values()) + 10240
    archive = folder / 'shards.tar'
    try:
        with archive.open('wb') as output:
            result = subprocess.run(['adb', 'exec-out', 'run-as', 'dev.agentos.app', 'tar', '-C',
                                     'files/performance/app-experience', '-cf', '-', *refs], stdout=output,
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
    output = Path(output); output.mkdir(parents=True, exist_ok=True)
    owned = (REPORT_NAME, *(launch_name(i) for i in range(400)), *(trial_name(i) for i in range(18)),
             'startup-ttid.json', 'startup-ttid.csv')
    for name in owned:
        (output / name).unlink(missing_ok=True)
    record = empty_capture(label, source, profile)
    record.update(gradleExitCode=exit_code, captureStatus='unavailable', captureError='device_or_report_unavailable')
    deadline, report = time.monotonic() + COLLECTION_SECONDS, None
    try:
        with tempfile.TemporaryFile() as stream:
            subprocess.run(['adb', 'shell', 'getprop', 'ro.build.version.sdk'], stdout=stream, stderr=subprocess.DEVNULL,
                           check=True, timeout=remaining_timeout(deadline), preexec_fn=limited_output(32))
            stream.seek(0); sdk = stream.read(33)
        if len(sdk) > 32:
            raise ValueError('sdk_output_exceeds_collection_budget')
        try:
            sdk = sdk.decode('ascii').strip()
        except UnicodeDecodeError as error:
            raise ValueError('invalid_sdk_output') from error
        if not sdk.isdigit() or int(sdk) != record['apiLevel']:
            raise ValueError('device_sdk_mismatch')
        record['actualSdkInt'] = int(sdk)
        metadata_path = output / 'trace-capture.json'
        metadata = read_json(metadata_path, 16384) if metadata_path.exists() else None
        limits = limits_for_trace_capture(metadata)
        with tempfile.TemporaryDirectory(prefix='agent-app-experience-') as temporary:
            folder = Path(temporary)
            pull_file(REPORT_NAME, folder / REPORT_NAME, MAX_REPORT_BYTES, deadline)
            candidate = validate_report(read_json(folder / REPORT_NAME, MAX_REPORT_BYTES), record['apiLevel'], profile, limits['lane'])
            pull_shards(candidate, folder, deadline)
            _validate_raw_bundle(folder, candidate, record['apiLevel'], profile, limits['lane'])
            remaining_timeout(deadline)
            for path in folder.iterdir():
                shutil.copyfile(path, output / path.name)
            report = candidate
            record['report'] = file_reference(output / REPORT_NAME, report['status'])
    except ValueError as error:
        record['captureStatus'] = 'unavailable' if str(error) in ('committed_report_unavailable', 'committed_shards_unavailable') else 'invalid'
        record['captureError'] = str(error) if re.fullmatch(r'[a-z_]{1,80}', str(error)) else 'invalid_report'
    except (OSError, subprocess.SubprocessError):
        pass
    if report is not None:
        record.update(captureStatus='partial', captureError='startup_trace_derivation_unavailable')
        try:
            # First validate trace metadata/hash/inflate bounds before invoking the
            # pinned processor. The processor enforces its own bounded execution.
            validate_bundle(output, report, record['apiLevel'], profile)
            if (output / 'trace.pftrace.gz').exists():
                from experience_trace import derive_trace, download_processor
                processor = download_processor(Path(os.environ.get('AGENT_OS_TRACE_PROCESSOR_CACHE', '/tmp/agent-os-perfetto-v59.1')))
                limits = limits_for_trace_capture(read_json(output / 'trace-capture.json', 16384))
                derived = derive_trace(output / 'trace.pftrace.gz', report, processor, output / 'startup-ttid.csv',
                                       raw_limit=limits['raw'], gzip_limit=limits['gzip'])
                (output / 'startup-ttid.json').write_text(json.dumps(derived, sort_keys=True, indent=2) + '\n')
        except (ValueError, OSError, subprocess.SubprocessError, RuntimeError):
            for name in ('startup-ttid.json', 'startup-ttid.csv'):
                (output / name).unlink(missing_ok=True)
    try:
        payloads = validate_bundle(output, report, record['apiLevel'], profile)
        record['payloads'] = [dict(path=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                              for name, data in sorted(payloads.items())]
        record['diagnosticStatus'] = diagnostic_status(report, payloads, exit_code)
        if report is not None:
            record['captureStatus'] = capture_status(report, payloads)
            record['captureError'] = None if record['captureStatus'] == 'collected' else 'startup_trace_derivation_unavailable'
    except (ValueError, OSError):
        record.update(captureStatus='invalid', captureError='invalid_experience_payload_bundle',
                      diagnosticStatus='failed' if report is not None else 'unavailable')
        # Still hash bounded raw files for failure provenance; strict archive
        # validation will reject malformed content, never silently relabel it.
        record['payloads'] = [file_reference(path) for path in sorted(output.iterdir())
                              if path.name != 'capture.json' and path.is_file() and not path.is_symlink()
                              and path.stat().st_size <= MAX_ARTIFACT_BYTES]
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('collect', 'finalize'))
    parser.add_argument('--matrix-label', choices=LABELS, required=True)
    parser.add_argument('--scope', choices=SCOPES, required=True)
    parser.add_argument('--exit-code', type=int, default=1)
    parser.add_argument('--step-outcome', choices=('success', 'failure', 'cancelled', 'skipped', 'unknown'), default='unknown')
    args = parser.parse_args()
    source, profile = source_identity(os.environ), profile_for_scope(args.scope)
    output = Path('app-experience-evidence') / ('api-' + args.matrix_label); output.mkdir(parents=True, exist_ok=True)
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
    path.write_text(json.dumps(record, indent=2, sort_keys=True) + '\n')
    print(f"API {args.matrix_label}: application experience {profile} {record['diagnosticStatus']}; capture={record['captureStatus']}; diagnostic only")
    if args.mode == 'collect' and (record['diagnosticStatus'] != 'passed' or record['captureStatus'] != 'collected'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
