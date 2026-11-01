#!/usr/bin/env python3
"""One bounded A→B IME setting experiment; each arm keeps the original raw contract."""
import argparse
import base64
import copy
import os
from pathlib import Path
import re
import signal
import time
from types import SimpleNamespace

import configure_emulator_phone as avd
import window_infos_raw as w
import window_infos_input_state as input_state

KIND = 'window-infos-keyboard'
SETTING = 'show_ime_with_hard_keyboard'
ARMS = ('baseline', 'enabled')
TOP_FILES = {'report.json', 'commands.json', 'journal.json', 'input-devices.json', 'avd-config.ini', 'fixture.apk'}
FILES = TOP_FILES | {arm + '/' + name for arm in ARMS for name in w.FILES}
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 36 * 1024 * 1024
MAX_TOTAL_OUTPUT = 2 * 1024 * 1024
MAX_COMMANDS = 16
MAX_APK = 2 * 1024 * 1024
MAX_AVD = 1024 * 1024
EXTRA_SOURCES = ('scripts/window_infos_keyboard.py', 'scripts/window_infos_keyboard_archive.py',
                 'scripts/window_infos_input_state.py', 'scripts/configure_emulator_phone.py')
COMMAND_IDS = ('devices', 'api', 'qemu', 'current_user', 'shell_uid', 'input_dump', 'settings_before',
               'uninstall_fixture', 'packages_after_uninstall', 'enable_setting', 'settings_enabled',
               'restore_setting', 'settings_restored')
SCOPE = {'diagnosticOnly': True, 'order': ['baseline', 'enabled'], 'sameEmulator': True,
         'rawContractChanged': False, 'perArmRegistrationLimit': 1, 'perArmShowRequestLimit': 1,
         'settingUserId': 0, 'setting': SETTING, 'settingMutationLimit': 1, 'settingRestoreLimit': 1,
         'nativeInputDumpLimit': 1, 'nativeInputDumpPhase': 'BEFORE_BASELINE',
         'avdSnapshotReadOnly': True, 'avdHwKeyboardNoProvesNoNativeKeyboard': False,
         'secureSettingSnapshotIsContinuousMonitoring': False,
         'sameFixtureApkBytes': True, 'sameProbeJarBytes': True,
         'fixtureReinstalledBetweenArms': True, 'fixtureUidMayDiffer': True,
         'warmStateConfound': True, 'fixedOrderConfound': True, 'reinstallConfound': True,
         'causalIsolationClaimed': False, 'applicationCompatibilityAcceptance': False,
         'topCommandLimit': MAX_COMMANDS, 'topOutputByteLimit': MAX_TOTAL_OUTPUT,
         'perArmCommandLimit': w.MAX_COMMANDS, 'perArmOutputByteLimit': w.MAX_TOTAL_OUTPUT,
         'effectiveTreeCommandCatalogLimit': 80, 'treeOutputByteLimit': 6 * 1024 * 1024}
require, Rejected = w.require, w.Rejected
now, equal = w.now, w.common.equal


def file_limit(name):
    if name == 'fixture.apk': return MAX_APK
    if name == 'avd-config.ini': return MAX_AVD
    if name.endswith('/screenshot.png'): return w.MAX_PNG
    if name.endswith('/probe.jar'): return w.MAX_JAR
    return MAX_FILE_BYTES


def source_files(repo=None):
    repo = Path(repo) if repo is not None else Path(__file__).resolve().parents[1]
    result = w.source_files(repo)
    result.update({name: w.identity(w.read_bounded(repo / name, MAX_FILE_BYTES)) for name in EXTRA_SOURCES})
    return result


def verify_source_files(report, repo):
    require(equal(report['sourceFiles'], source_files(repo)), 'KEYBOARD_SOURCE_FILES_MISMATCH')
    return True


def tree_read(directory):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'KEYBOARD_DIRECTORY')
    result = {}; total = 0; arm_totals = {arm: 0 for arm in ARMS}
    for p in sorted(directory.iterdir()):
        require(not p.is_symlink(), 'KEYBOARD_SYMLINK')
        if p.name in ARMS:
            require(p.is_dir(), 'KEYBOARD_ARM_DIRECTORY')
            entries = list(p.iterdir())
        else:
            entries = [p]
        for entry in entries:
            name = entry.relative_to(directory).as_posix()
            require(name in FILES and entry.is_file() and not entry.is_symlink(), 'KEYBOARD_FILESET')
            data = w.read_bounded(entry, file_limit(name)); total += len(data)
            if '/' in name: arm_totals[name.split('/')[0]] += len(data)
            result[name] = w.decode_json(data) if name.endswith('.json') else data
    require(total <= MAX_TOTAL_BYTES, 'KEYBOARD_TOTAL_BYTES')
    require(all(total <= w.MAX_TOTAL_BYTES for total in arm_totals.values()), 'KEYBOARD_ARM_TOTAL_BYTES')
    return result


read_payloads = tree_read


def arm_payloads(payloads, arm):
    return {name[len(arm) + 1:]: value for name, value in payloads.items() if name.startswith(arm + '/')}


def arm_summary(payloads):
    report = payloads['report.json']
    return {k: report[k] for k in ('status', 'failureReason', 'positiveCallbackCount', 'imeStateStatus')} | {
        'comparable': arm_comparable(payloads)}


def empty_report(source, identities=None):
    return {'schemaVersion': 1, 'kind': KIND, 'source': copy.deepcopy(w.validate_source(source)),
            'sourceFiles': source_files() if identities is None else copy.deepcopy(identities),
            'scope': copy.deepcopy(SCOPE), 'status': 'unverified', 'stage': 'prepared',
            'failureReason': 'NOT_EXECUTED', 'createdAt': now(), 'completedAt': None,
            'emulatorStepOutcome': None, 'runAttempted': False, 'baselineAttempted': False,
            'enabledAttempted': False, 'tools': None, 'device': None, 'inputs': {}, 'avd': None,
            'inputDevicesStatus': 'not_attempted',
            'arms': {a: {'status': 'unverified', 'failureReason': 'NOT_EXECUTED',
                        'positiveCallbackCount': 0, 'imeStateStatus': 'not_attempted', 'comparable': False} for a in ARMS},
            'restoration': {'status': 'not_needed', 'mutationAttempted': False, 'restoreAttempted': False},
            'emulatorTeardown': 'WORKFLOW_RESPONSIBILITY_NOT_OBSERVED'}


def empty_journal(source):
    return {'schemaVersion': 1, 'kind': KIND + '-journal', 'source': copy.deepcopy(source),
            'original': None, 'mutationAttempted': False, 'mutationAttemptedAt': None,
            'restoreAttempted': False, 'restoreAttemptedAt': None}


def durable_journal(directory, journal):
    """Publish and fsync the intent before either setting write. Single workflow writer."""
    directory = Path(directory); w.write_json(directory / 'journal.json', journal)
    descriptor = os.open(directory / 'journal.json', os.O_RDONLY | os.O_NOFOLLOW)
    try: os.fsync(descriptor)
    finally: os.close(descriptor)
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try: os.fsync(descriptor)
    finally: os.close(descriptor)


def prepare(directory, source):
    directory = Path(directory)
    require(not directory.is_symlink(), 'KEYBOARD_OUTPUT_SYMLINK')
    directory.mkdir(parents=True, exist_ok=True)
    require(not list(directory.iterdir()), 'KEYBOARD_OUTPUT_NOT_EMPTY')
    w.write_json(directory / 'report.json', empty_report(source))
    w.write_json(directory / 'commands.json', {'schemaVersion': 1, 'kind': KIND + '-commands', 'source': source, 'commands': []})
    durable_journal(directory, empty_journal(source))
    for arm in ARMS: w.prepare(directory / arm, source)


def parse_settings(data):
    require(type(data) is bytes and len(data) <= w.MAX_SETUP_OUTPUT, 'SETTING_OUTPUT_BOUND')
    try: text = data.decode('utf-8')
    except UnicodeError: raise Rejected('SETTING_UTF8') from None
    require(not any(ord(c) < 32 and c not in '\n\r' for c in text), 'SETTING_CONTROL_CHARACTER')
    require(text and text.endswith('\n'), 'SETTING_EMPTY_OR_INCOMPLETE_LINE')
    values = {}; lines = text.splitlines()
    for line in lines:
        require('=' in line, 'SETTING_LIST_FORMAT')
        key, value = line.split('=', 1)
        require(re.fullmatch(r'[A-Za-z0-9_.:-]{1,200}', key) and key not in values, 'SETTING_LIST_KEY')
        values[key] = value
    require(SETTING not in values or len(values[SETTING]) <= 128, 'SETTING_VALUE_BOUND')
    require('default_input_method' in values, 'SETTING_CONTEXT_MISSING')
    w.ime_component(values['default_input_method'])
    return values


def parse_setting(data, expected_component=None):
    values = parse_settings(data)
    if expected_component is not None:
        require(values['default_input_method'] == expected_component, 'SETTING_CONTEXT_CHANGED')
    return {'present': SETTING in values, 'value': values.get(SETTING)}


def eligible_original(original):
    return original == {'present': False, 'value': None} or original == {'present': True, 'value': '0'}


def command_limits(name):
    if name == 'input_dump': return 12, 262144
    if name == 'uninstall_fixture': return 30, w.MAX_SETUP_OUTPUT
    return 10, w.MAX_SETUP_OUTPUT


def command_argv(name, report, journal):
    adb = report['tools']['adb']
    if name == 'devices': return [adb, 'devices']
    require(report['device'] is not None, 'KEYBOARD_COMMAND_DEVICE')
    prefix = [adb, '-s', report['device']['serial']]
    secure = ['shell', 'settings', '--user', '0']
    tails = {
        'api': ['shell', 'getprop', 'ro.build.version.sdk'],
        'qemu': ['shell', 'getprop', 'ro.kernel.qemu'],
        'current_user': ['shell', 'am', 'get-current-user'], 'shell_uid': ['shell', 'id', '-u'],
        'input_dump': ['shell', '/system/bin/timeout', '-s', 'KILL', '8s', '/system/bin/dumpsys', '-t', '5', 'input'],
        'settings_before': secure + ['list', 'secure'], 'settings_enabled': secure + ['list', 'secure'],
        'settings_restored': secure + ['list', 'secure'],
        'uninstall_fixture': ['uninstall', w.PACKAGE],
        'packages_after_uninstall': ['shell', 'pm', 'list', 'packages', '--user', '0'],
        'enable_setting': secure + ['put', 'secure', SETTING, '1'],
    }
    if name == 'restore_setting':
        require(eligible_original(journal['original']), 'RESTORE_ORIGINAL_NOT_ELIGIBLE')
        return prefix + secure + (['put', 'secure', SETTING, '0'] if journal['original']['present']
                                  else ['delete', 'secure', SETTING])
    return prefix + tails[name]


class CommandRunner:
    def __init__(self, directory, source, execute=w.common.bounded_capture):
        self.directory, self.source, self.execute = Path(directory), source, execute
        self.commands = w.decode_json(w.read_bounded(self.directory / 'commands.json', MAX_FILE_BYTES))['commands']

    def flush(self):
        w.write_json(self.directory / 'commands.json', {'schemaVersion': 1, 'kind': KIND + '-commands',
                                                      'source': self.source, 'commands': self.commands})

    def call(self, name, argv):
        require(name in COMMAND_IDS and name not in [c['id'] for c in self.commands]
                and len(self.commands) < MAX_COMMANDS, 'KEYBOARD_COMMAND_ID')
        seconds, limit = command_limits(name)
        require(sum(c['bytes'] for c in self.commands) + limit <= MAX_TOTAL_OUTPUT, 'KEYBOARD_COMMAND_BUDGET')
        tick = time.monotonic()
        item = {'ordinal': len(self.commands) + 1, 'id': name, 'argv': list(argv), 'timeoutSeconds': seconds,
                'byteLimit': limit, 'startedAt': now(), 'endedAt': None, 'elapsedMillis': 0,
                'status': 'running', 'exitCode': None, 'exceptionType': None,
                **w.identity(b''), 'outputBase64': ''}
        self.commands.append(item); self.flush()
        try: data, result = self.execute(argv, seconds, limit)
        except Exception as error: data, result = b'', {'status': 'exception', 'exitCode': None, 'exceptionType': type(error).__name__}
        require(type(data) is bytes and len(data) <= limit, 'KEYBOARD_COMMAND_OUTPUT')
        item.update(**w.identity(data), outputBase64=base64.b64encode(data).decode(), endedAt=now(),
                    elapsedMillis=int((time.monotonic() - tick) * 1000), status=result['status'],
                    exitCode=result.get('exitCode'), exceptionType=result.get('exceptionType'))
        self.flush(); return data, item


def command_metadata(command):
    return {k: command[k] for k in ('id', 'bytes', 'sha256', 'startedAt', 'endedAt', 'elapsedMillis', 'status', 'exitCode')}


def derive_input(command, source):
    analysis = None
    if w.command_ok(command):
        try: analysis = input_state.analyze(w.command_data(command))
        except Exception as failure:
            analysis = {'status': 'unavailable', 'reason': 'PARSER_EXCEPTION', 'exceptionType': type(failure).__name__}
    return {'schemaVersion': 1, 'kind': KIND + '-input-devices', 'source': copy.deepcopy(source),
            'scope': 'ONE_NATIVE_INPUT_DUMP_BEFORE_BASELINE_NOT_CONTINUOUS_MONITORING',
            'command': command_metadata(command), 'analysis': analysis,
            'status': analysis['status'] if analysis is not None else 'unavailable',
            'reason': analysis['reason'] if analysis is not None else 'COMMAND_NOT_COMPLETE'}


def arm_comparable(payloads):
    try:
        report = w.validate_evidence(payloads)
        require(report['status'] == 'passed' or (report['status'] == 'failed'
                and report['failureReason'] == 'RAW_POSITIVE_CALLBACK_NOT_OBSERVED'), 'ARM_PRIMARY_FAILURE')
        commands = w.validate_commands(payloads['commands.json'], report, report['source'])
        w.validate_passed_setup(commands, report)
        raw, fixture = payloads['raw-result.json'], payloads['fixture.json']
        require(w.raw_passed(raw) and w.fixture_passed(fixture)
                and payloads['capture.json']['status'] == 'captured'
                and report['imeStateStatus'] == 'recorded' and 'ime-state.json' in payloads, 'ARM_COMPLETE_PROOF')
        require(raw['times']['readyElapsedMs'] <= fixture['startedElapsedMs'] <= fixture['showRequestedElapsedMs']
                <= fixture['firstImeVisibleElapsedMs'] <= raw['process']['finishedElapsedMs']
                and raw['process']['finishedElapsedMs'] - raw['process']['startedElapsedMs'] < 40000,
                'ARM_STIMULUS_TIMING')
        require(raw['process']['fingerprintSha256'] == w.sha(report['device']['properties']['ro.build.fingerprint'].encode()), 'ARM_FINGERPRINT')
        return True
    except (ValueError, KeyError, TypeError): return False


def setting_restoration(journal, commands):
    status = 'not_needed'
    if journal['mutationAttempted']:
        status = 'not_attempted'
        if journal['restoreAttempted']:
            status = 'failed_or_unobserved'
            if all(n in commands and w.command_ok(commands[n]) for n in ('restore_setting', 'settings_restored')):
                try:
                    component = parse_settings(w.command_data(commands['settings_before']))['default_input_method']
                    if parse_setting(w.command_data(commands['settings_restored']), component) == journal['original']:
                        status = 'confirmed'
                except Rejected: pass
    return {'status': status, 'mutationAttempted': journal['mutationAttempted'], 'restoreAttempted': journal['restoreAttempted']}


def validate_commands(value, report, journal):
    w.exact(value, {'schemaVersion', 'kind', 'source', 'commands'}, 'KEYBOARD_COMMANDS_FIELDS')
    require(value['schemaVersion'] == 1 and type(value['schemaVersion']) is int
            and value['kind'] == KIND + '-commands' and equal(value['source'], report['source']), 'KEYBOARD_COMMANDS_IDENTITY')
    entries = value['commands']; require(type(entries) is list and len(entries) <= MAX_COMMANDS, 'KEYBOARD_COMMAND_COUNT')
    previous = -1; prior_end = w.common.timestamp(report['createdAt']); result = {}; total = 0
    keys = {'ordinal', 'id', 'argv', 'timeoutSeconds', 'byteLimit', 'startedAt', 'endedAt', 'elapsedMillis',
            'status', 'exitCode', 'exceptionType', 'bytes', 'sha256', 'outputBase64'}
    for ordinal, item in enumerate(entries, 1):
        w.exact(item, keys, 'KEYBOARD_COMMAND_FIELDS'); name = item['id']
        require(name in COMMAND_IDS and COMMAND_IDS.index(name) > previous and item['ordinal'] == ordinal
                and type(item['ordinal']) is int, 'KEYBOARD_COMMAND_ORDER')
        previous = COMMAND_IDS.index(name)
        require(equal(item['argv'], command_argv(name, report, journal)), 'KEYBOARD_COMMAND_ARGV')
        seconds, limit = command_limits(name)
        require(type(item['timeoutSeconds']) is int and item['timeoutSeconds'] == seconds
                and type(item['byteLimit']) is int and item['byteLimit'] == limit, 'KEYBOARD_COMMAND_LIMITS')
        require(item['status'] in {'running', 'completed', 'timed_out', 'output_limit', 'cancelled', 'exception'}
                and (item['exitCode'] is None or w.integer(item['exitCode'], -(2**31), 2**31 - 1))
                and w.integer(item['elapsedMillis']), 'KEYBOARD_COMMAND_STATUS')
        require((item['status'] == 'exception') == (item['exceptionType'] is not None)
                and (item['exceptionType'] is None or re.fullmatch('[A-Za-z][A-Za-z0-9_]{0,79}', str(item['exceptionType']))), 'KEYBOARD_COMMAND_EXCEPTION')
        start = w.common.timestamp(item['startedAt']); require(start >= prior_end, 'KEYBOARD_COMMAND_OVERLAP')
        if item['endedAt'] is None:
            require(item['status'] == 'running' and report['status'] != 'passed', 'KEYBOARD_COMMAND_UNFINISHED')
            prior_end = start
        else:
            prior_end = w.common.timestamp(item['endedAt']); require(prior_end >= start, 'KEYBOARD_COMMAND_TIME')
            if report['completedAt'] is not None: require(prior_end <= w.common.timestamp(report['completedAt']), 'KEYBOARD_COMMAND_AFTER_REPORT')
        require(type(item['outputBase64']) is str and len(item['outputBase64']) <= 4 * ((limit + 2) // 3), 'KEYBOARD_COMMAND_BASE64_LIMIT')
        data = w.command_data(item)
        require(len(data) <= limit and w.integer(item['bytes'], 0, limit)
                and equal(w.identity(data), {'bytes': item['bytes'], 'sha256': item['sha256']}), 'KEYBOARD_COMMAND_BYTES')
        result[name] = item; total += len(data)
    require(total <= MAX_TOTAL_OUTPUT, 'KEYBOARD_COMMAND_TOTAL')
    return result


def validate_journal(journal, source, commands):
    w.exact(journal, set(empty_journal(source)), 'KEYBOARD_JOURNAL_FIELDS')
    require(type(journal['schemaVersion']) is int and journal['schemaVersion'] == 1
            and journal['kind'] == KIND + '-journal' and equal(journal['source'], source), 'KEYBOARD_JOURNAL_SOURCE')
    for key in ('mutationAttempted', 'restoreAttempted'): require(type(journal[key]) is bool, 'KEYBOARD_JOURNAL_FLAG')
    if journal['original'] is not None:
        require('settings_before' in commands and w.command_ok(commands['settings_before'])
                and equal(journal['original'], parse_setting(w.command_data(commands['settings_before']))), 'KEYBOARD_ORIGINAL_SETTING')
    for prefix, command_name in [('mutation', 'enable_setting'), ('restore', 'restore_setting')]:
        attempted, stamp = journal[prefix + 'Attempted'], journal[prefix + 'AttemptedAt']
        require(attempted == (stamp is not None), 'KEYBOARD_INTENT_TIME')
        if attempted:
            require(eligible_original(journal['original']), 'KEYBOARD_INTENT_ORIGINAL')
            tick = w.common.timestamp(stamp)
            require(tick >= w.common.timestamp(commands['settings_before']['endedAt']), 'KEYBOARD_INTENT_BEFORE_ORIGINAL')
            if command_name in commands: require(tick <= w.common.timestamp(commands[command_name]['startedAt']), 'KEYBOARD_INTENT_AFTER_WRITE')
        else: require(command_name not in commands, 'KEYBOARD_WRITE_WITHOUT_INTENT')
    require(not journal['restoreAttempted'] or journal['mutationAttempted'], 'KEYBOARD_RESTORE_WITHOUT_MUTATION')
    if journal['restoreAttempted']:
        require(w.common.timestamp(journal['restoreAttemptedAt']) >= w.common.timestamp(journal['mutationAttemptedAt']), 'KEYBOARD_RESTORE_ORDER')
    if 'settings_enabled' in commands: require('enable_setting' in commands, 'KEYBOARD_READ_WITHOUT_WRITE')
    if 'settings_restored' in commands: require('restore_setting' in commands, 'KEYBOARD_RESTORE_READ_WITHOUT_WRITE')


def validate_preflight(report, commands):
    require(all(name in commands and w.command_ok(commands[name]) for name in COMMAND_IDS[:5]), 'KEYBOARD_PREFLIGHT_COMMANDS')
    text = lambda name: w.command_text(commands[name])
    require(w.devices.select_emulator(text('devices')) == report['device']['serial']
            and text('api') == '37' and text('qemu') == '1'
            and text('current_user') == '0' and text('shell_uid') == '2000', 'KEYBOARD_PREFLIGHT_ENVIRONMENT')


def enabled_setting_confirmed(commands):
    try:
        component = parse_settings(w.command_data(commands['settings_before']))['default_input_method']
        return all(name in commands and w.command_ok(commands[name]) for name in ('enable_setting', 'settings_enabled')) \
            and parse_setting(w.command_data(commands['settings_enabled']), component) == {'present': True, 'value': '1'}
    except (Rejected, KeyError): return False


def baseline_cache_false(payloads):
    try: return payloads['ime-state.json']['analysis']['fields']['settingsObserver']['value']['cacheMeaning'] == 'FALSE'
    except (KeyError, TypeError): return False


def enabled_view_confirmed(payloads):
    try:
        fields = payloads['ime-state.json']['analysis']['fields']
        return fields['settingsObserver']['value']['cacheMeaning'] == 'TRUE' and fields['mIsInputViewShown']['value'] is True
    except (KeyError, TypeError): return False


def sync_report(directory):
    payloads = tree_read(directory); report = payloads['report.json']; journal = payloads['journal.json']
    commands = {c['id']: c for c in payloads['commands.json']['commands']}
    report['arms'] = {arm: arm_summary(arm_payloads(payloads, arm)) for arm in ARMS}
    report['restoration'] = setting_restoration(journal, commands)
    report['inputDevicesStatus'] = payloads.get('input-devices.json', {}).get(
        'status', 'unavailable' if 'input_dump' in commands else 'not_attempted')
    w.write_json(Path(directory) / 'report.json', report)
    return report


def _arm_source_subset(sources):
    return {name: ident for name, ident in sources.items() if name not in EXTRA_SOURCES}


def validate_evidence(payloads, source=None):
    require(type(payloads) is dict and set(payloads) <= FILES
            and {'report.json', 'commands.json', 'journal.json'} <= set(payloads), 'KEYBOARD_PAYLOADS')
    report = payloads['report.json']; source = w.validate_source(report['source'] if source is None else source)
    w.exact(report, set(empty_report(source, {})), 'KEYBOARD_REPORT_FIELDS')
    require(type(report['schemaVersion']) is int and report['schemaVersion'] == 1 and report['kind'] == KIND
            and equal(report['source'], source) and equal(report['scope'], SCOPE), 'KEYBOARD_REPORT_CONTRACT')
    require(report['status'] in {'unverified', 'passed', 'failed'} and report['stage'] in
            {'prepared', 'preflight', 'baseline', 'reinstall', 'enabling', 'enabled', 'restoring', 'complete', 'finalized'}
            and report['emulatorStepOutcome'] in {None, 'success', 'failure', 'cancelled', 'skipped', 'unknown'}, 'KEYBOARD_REPORT_STATUS')
    require(report['failureReason'] is None or (type(report['failureReason']) is str
            and re.fullmatch('[A-Z][A-Za-z0-9_]{0,127}', report['failureReason'])), 'KEYBOARD_FAILURE_REASON')
    for name in ('runAttempted', 'baselineAttempted', 'enabledAttempted'): require(type(report[name]) is bool, 'KEYBOARD_ATTEMPT_FLAG')
    require(report['emulatorTeardown'] == 'WORKFLOW_RESPONSIBILITY_NOT_OBSERVED', 'KEYBOARD_TEARDOWN_SCOPE')
    created = w.common.timestamp(report['createdAt'])
    if report['completedAt'] is not None: require(w.common.timestamp(report['completedAt']) >= created, 'KEYBOARD_REPORT_TIME')
    require(type(report['sourceFiles']) is dict and set(EXTRA_SOURCES) <= set(report['sourceFiles']), 'KEYBOARD_SOURCE_FILES')
    for name, ident in report['sourceFiles'].items():
        require(type(name) is str and re.fullmatch('[A-Za-z0-9_./-]+', name) and not name.startswith('/')
                and '..' not in Path(name).parts, 'KEYBOARD_SOURCE_PATH')
        w.common.validate_identity(ident)
    if report['tools'] is not None:
        w.exact(report['tools'], {'adb', 'sdkRoot', 'fixtureApk', 'probeJar'}, 'KEYBOARD_TOOLS')
        require(all(type(v) is str and 0 < len(v) <= 1024 and v.startswith('/') and '\x00' not in v for v in report['tools'].values())
                and report['tools']['adb'] == str(Path(report['tools']['sdkRoot']) / 'platform-tools/adb'), 'KEYBOARD_TOOL_PATH')
    if report['device'] is not None:
        w.exact(report['device'], {'serial'}, 'KEYBOARD_DEVICE')
        require(re.fullmatch(r'emulator-[0-9]{4,5}', str(report['device']['serial'])), 'KEYBOARD_SERIAL')
    journal = payloads['journal.json']; commands = validate_commands(payloads['commands.json'], report, journal)
    validate_journal(journal, source, commands)
    require(equal(report['restoration'], setting_restoration(journal, commands)), 'KEYBOARD_RESTORATION_DERIVED')
    require(type(report['inputs']) is dict and set(report['inputs']) <= {'fixtureApk', 'probeJar'}, 'KEYBOARD_INPUTS')
    for name, ident in report['inputs'].items(): w.common.validate_identity(ident, MAX_APK if name == 'fixtureApk' else w.MAX_JAR)
    if 'fixture.apk' in payloads:
        require(type(payloads['fixture.apk']) is bytes and 0 < len(payloads['fixture.apk']) <= MAX_APK
                and equal(w.identity(payloads['fixture.apk']), report['inputs'].get('fixtureApk')), 'KEYBOARD_APK_ORIGINAL')
    else: require('fixtureApk' not in report['inputs'], 'KEYBOARD_APK_MISSING')
    if 'avd-config.ini' in payloads:
        data = payloads['avd-config.ini']; item = report['avd']
        require(type(data) is bytes and len(data) <= MAX_AVD and type(item) is dict, 'KEYBOARD_AVD_ORIGINAL')
        w.exact(item, {'name', 'path', 'bytes', 'sha256', 'hwKeyboard'}, 'KEYBOARD_AVD_FIELDS')
        try: values = avd.keyboard_values(data.decode('utf-8').splitlines())
        except UnicodeError: raise Rejected('KEYBOARD_AVD_UTF8') from None
        require(values == ['no'] and item['hwKeyboard'] == 'no' and equal(w.identity(data), {k:item[k] for k in ('bytes','sha256')})
                and re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]{0,63}', str(item['name']))
                and type(item['path']) is str and 0 < len(item['path']) <= 1024
                and item['path'].startswith('/') and '\x00' not in item['path'] and '..' not in Path(item['path']).parts
                and Path(item['path']).parts[-2:] == (item['name'] + '.avd', 'config.ini'), 'KEYBOARD_AVD_BASELINE')
    else: require(report['avd'] is None, 'KEYBOARD_AVD_MISSING')
    if 'input_dump' in commands: validate_preflight(report, commands)
    if 'input-devices.json' in payloads:
        require('input_dump' in commands and equal(payloads['input-devices.json'], derive_input(commands['input_dump'], source)), 'KEYBOARD_INPUT_DERIVATION')
    expected_input = payloads.get('input-devices.json', {}).get(
        'status', 'unavailable' if 'input_dump' in commands else 'not_attempted')
    require(report['inputDevicesStatus'] == expected_input, 'KEYBOARD_INPUT_STATUS')
    arm_values = {}
    for arm in ARMS:
        values = arm_payloads(payloads, arm); child = w.validate_evidence(values, source); arm_values[arm] = values
        require(child['schemaVersion'] == 2 and equal(child['sourceFiles'], _arm_source_subset(report['sourceFiles'])), 'KEYBOARD_ARM_SOURCE_FILES')
        require(equal(report['arms'][arm], arm_summary(values)), 'KEYBOARD_ARM_SUMMARY')
        entries = values['commands.json']['commands']
        require(not entries or report[arm + 'Attempted'], 'KEYBOARD_ARM_ATTEMPT')
        if child['tools'] is not None:
            require(report['tools'] is not None and all(child['tools'][k] == report['tools'][k] for k in ('adb','fixtureApk','probeJar')), 'KEYBOARD_ARM_TOOLS')
        if child['device'] is not None: require(report['device'] == {'serial': child['device']['serial']}, 'KEYBOARD_ARM_DEVICE')
        for name in child['inputs']:
            require(equal({k:child['inputs'][name][k] for k in ('bytes','sha256')}, report['inputs'].get(name)), 'KEYBOARD_ARM_INPUT_BYTES')
        if entries:
            require('settings_before' in commands and 'input_dump' in commands and 'avd-config.ini' in payloads
                    and eligible_original(journal['original']), 'KEYBOARD_ARM_PREREQUISITES')
            validate_preflight(report, commands)
            require(all(commands[name]['endedAt'] is not None and w.common.timestamp(commands[name]['endedAt']) <= w.common.timestamp(entries[0]['startedAt'])
                        for name in ('input_dump','settings_before')), 'KEYBOARD_ARM_BEFORE_CONTEXT')
    baseline, enabled = arm_values['baseline'], arm_values['enabled']
    if 'uninstall_fixture' in commands or journal['mutationAttempted'] or report['enabledAttempted']:
        require(arm_comparable(baseline) and baseline_cache_false(baseline), 'KEYBOARD_BASELINE_NOT_COMPARABLE')
        parse_setting(w.command_data(commands['settings_before']), baseline['report.json']['ime']['componentBefore'])
        last = baseline['commands.json']['commands'][-1]
        for name in ('uninstall_fixture','enable_setting'):
            if name in commands: require(w.common.timestamp(commands[name]['startedAt']) >= w.common.timestamp(last['endedAt']), 'KEYBOARD_MUTATION_BEFORE_BASELINE_END')
    if journal['mutationAttempted'] or report['enabledAttempted']:
        require(all(name in commands and w.command_ok(commands[name]) for name in ('uninstall_fixture','packages_after_uninstall')), 'KEYBOARD_REINSTALL_NOT_CONFIRMED')
        require(w.command_text(commands['uninstall_fixture']) == 'Success', 'KEYBOARD_UNINSTALL_RESULT')
        packages = w.command_text(commands['packages_after_uninstall']).splitlines()
        require(all(re.fullmatch(r'package:[A-Za-z][A-Za-z0-9_.]*', line) for line in packages)
                and 'package:' + w.PACKAGE not in packages, 'KEYBOARD_FIXTURE_STILL_INSTALLED')
        require(w.common.timestamp(journal['mutationAttemptedAt'])
                >= w.common.timestamp(commands['packages_after_uninstall']['endedAt']), 'KEYBOARD_INTENT_BEFORE_REINSTALL_CHECK')
    if report['enabledAttempted']:
        require(enabled_setting_confirmed(commands), 'KEYBOARD_ENABLED_SETTING_NOT_CONFIRMED')
        entries = enabled['commands.json']['commands']
        if entries: require(w.common.timestamp(entries[0]['startedAt']) >= w.common.timestamp(commands['settings_enabled']['endedAt']), 'KEYBOARD_ENABLED_BEFORE_SETTING')
        left, right = baseline['report.json'], enabled['report.json']
        if right['device'] is not None: require(equal(left['device'], right['device']), 'KEYBOARD_CROSS_ARM_DEVICE')
        if right['ime'] is not None:
            require(all(left['ime'][k] == right['ime'][k] for k in ('componentBefore','package','uid','displayId')), 'KEYBOARD_CROSS_ARM_IME')
        if 'raw-result.json' in enabled:
            require(enabled['raw-result.json']['process']['startedElapsedMs']
                    > baseline['raw-result.json']['process']['finishedElapsedMs'], 'KEYBOARD_CROSS_ARM_ELAPSED_ORDER')
        if 'restore_setting' in commands:
            for item in entries:
                require(w.common.timestamp(commands['restore_setting']['startedAt']) >= w.common.timestamp(item['endedAt'] or item['startedAt']), 'KEYBOARD_RESTORE_BEFORE_ENABLED_END')
        if journal['restoreAttempted']:
            require(all(w.common.timestamp(journal['restoreAttemptedAt'])
                        >= w.common.timestamp(item['endedAt'] or item['startedAt']) for item in entries),
                    'KEYBOARD_RESTORE_INTENT_BEFORE_ENABLED_END')
    if report['status'] == 'passed':
        require(report['failureReason'] is None and report['runAttempted'] and report['baselineAttempted'] and report['enabledAttempted']
                and report['completedAt'] is not None and report['stage'] in {'complete','finalized'}
                and report['emulatorStepOutcome'] in {None,'success'}, 'KEYBOARD_PASSED_STATE')
        require(arm_comparable(baseline) and baseline_cache_false(baseline)
                and enabled['report.json']['status'] == 'passed' and enabled_view_confirmed(enabled)
                and report['restoration']['status'] == 'confirmed' and expected_input == 'recorded', 'KEYBOARD_PASSED_PROOF')
        require(equal(baseline['ime-state.json']['analysis']['fields']['configuration'],
                      enabled['ime-state.json']['analysis']['fields']['configuration']), 'KEYBOARD_CONFIGURATION_CHANGED')
    return report


def restore(directory, source, runner_factory=CommandRunner):
    directory = Path(directory); payloads = tree_read(directory); report = payloads['report.json']; journal = payloads['journal.json']
    require(equal(report['source'], source) and equal(report['sourceFiles'], source_files()), 'KEYBOARD_RESTORE_SOURCE')
    commands = validate_commands(payloads['commands.json'], report, journal); validate_journal(journal, source, commands)
    if not journal['mutationAttempted'] or journal['restoreAttempted']:
        return sync_report(directory)['restoration']
    require(arm_comparable(arm_payloads(payloads, 'baseline')), 'KEYBOARD_RESTORE_BASELINE_PROOF')
    parse_setting(w.command_data(commands['settings_before']), payloads['baseline/report.json']['ime']['componentBefore'])
    validate_preflight(report, commands)
    report['stage'] = 'restoring'; report['completedAt'] = None; w.write_json(directory / 'report.json', report)
    journal['restoreAttempted'] = True; journal['restoreAttemptedAt'] = now(); durable_journal(directory, journal)
    runner = runner_factory(directory, source)
    # A cancelled arm must not erase its cancellation; this separate finite restoration
    # attempt is allowed to use its own original command deadlines, with no retry.
    cancelled = w.common.CANCELLED; w.common.CANCELLED = False
    try:
        runner.call('restore_setting', command_argv('restore_setting', report, journal))
        runner.call('settings_restored', command_argv('settings_restored', report, journal))
    finally: w.common.CANCELLED = cancelled
    return sync_report(directory)['restoration']


def run_probe(args, source, runner_factory=CommandRunner, arm_runner_factory=w.CommandRunner):
    directory = Path(args.output); payloads = tree_read(directory); report = payloads['report.json']; journal = payloads['journal.json']
    validate_evidence(payloads, source)
    require(not report['runAttempted'] and report['status'] == 'unverified'
            and equal(report['sourceFiles'], source_files()), 'KEYBOARD_RUN_STATE_OR_SOURCE')
    report.update(runAttempted=True, stage='preflight', failureReason=None)
    sdk = Path(args.sdk_root).resolve()
    report['tools'] = {'adb':str(sdk/'platform-tools/adb'), 'sdkRoot':str(sdk),
                       'fixtureApk':str(Path(args.fixture_apk).resolve()), 'probeJar':str(Path(args.probe_jar).resolve())}
    def save(): w.write_json(directory / 'report.json', report)
    save(); runner = runner_factory(directory, source)
    def call(name): return runner.call(name, command_argv(name, report, journal))
    def checked(name):
        data, item = call(name); require(w.command_ok(item), 'KEYBOARD_COMMAND_FAILED_' + name)
        return data
    try:
        apk = w.read_bounded(args.fixture_apk, MAX_APK); jar = w.read_bounded(args.probe_jar, w.MAX_JAR)
        require(apk, 'KEYBOARD_EMPTY_APK'); w.validate_jar(jar)
        (directory/'fixture.apk').write_bytes(apk)
        report['inputs'] = {'fixtureApk':w.identity(apk), 'probeJar':w.identity(jar)}; save()
        config = avd.config_path(getattr(args, 'avd_home', None) or os.environ.get('ANDROID_AVD_HOME'), args.avd_name)
        config_bytes = w.read_bounded(config, MAX_AVD)
        require(avd.keyboard_values(config_bytes.decode('utf-8').splitlines()) == ['no'], 'KEYBOARD_AVD_BASELINE')
        (directory/'avd-config.ini').write_bytes(config_bytes)
        report['avd'] = {'name':args.avd_name,'path':str(config),**w.identity(config_bytes),'hwKeyboard':'no'}; save()
        report['device'] = {'serial':w.devices.select_emulator(checked('devices').decode('utf-8').strip())}; save()
        for name, expected in [('api','37'),('qemu','1'),('current_user','0'),('shell_uid','2000')]:
            require(checked(name).decode('utf-8').strip() == expected, 'KEYBOARD_PREFLIGHT_' + name.upper())
        _, command = call('input_dump')
        state = derive_input(command, source); w.write_json(directory/'input-devices.json', state)
        report['inputDevicesStatus'] = state['status']; save()
        journal['original'] = parse_setting(checked('settings_before')); durable_journal(directory, journal)
        require(eligible_original(journal['original']), 'KEYBOARD_ORIGINAL_SETTING_NOT_ELIGIBLE')
        for arm in ARMS:
            require(w.identity(w.read_bounded(args.fixture_apk, MAX_APK)) == report['inputs']['fixtureApk']
                    and w.identity(w.read_bounded(args.probe_jar, w.MAX_JAR)) == report['inputs']['probeJar'], 'KEYBOARD_INPUT_CHANGED')
            report['stage'] = arm; report[arm + 'Attempted'] = True; save()
            child_args = SimpleNamespace(output=directory/arm, sdk_root=args.sdk_root,
                                         fixture_apk=args.fixture_apk, probe_jar=args.probe_jar)
            passed = w.run_probe(child_args, source, arm_runner_factory)
            w.finalize(directory/arm, source, 'success' if passed else 'failure')
            report = sync_report(directory); save()
            child = w.read_payloads(directory/arm)
            if arm == 'baseline':
                require(arm_comparable(child) and baseline_cache_false(child), 'KEYBOARD_BASELINE_NOT_COMPARABLE')
                parse_setting(w.command_data(next(c for c in runner.commands if c['id'] == 'settings_before')),
                              child['report.json']['ime']['componentBefore'])
                report['stage'] = 'reinstall'; save()
                require(checked('uninstall_fixture').decode('utf-8').strip() == 'Success', 'KEYBOARD_UNINSTALL_RESULT')
                packages = checked('packages_after_uninstall').decode('utf-8').strip().splitlines()
                require(all(re.fullmatch(r'package:[A-Za-z][A-Za-z0-9_.]*', line) for line in packages)
                        and 'package:' + w.PACKAGE not in packages, 'KEYBOARD_FIXTURE_STILL_INSTALLED')
                report['stage'] = 'enabling'; save()
                journal['mutationAttempted'] = True; journal['mutationAttemptedAt'] = now(); durable_journal(directory, journal)
                call('enable_setting'); call('settings_enabled')
                require(enabled_setting_confirmed({c['id']:c for c in runner.commands}), 'KEYBOARD_ENABLED_SETTING_NOT_CONFIRMED')
            else:
                require(child['report.json']['status'] == 'passed', 'KEYBOARD_ENABLED_ARM_NOT_PASSED')
                require(enabled_view_confirmed(child), 'KEYBOARD_ENABLED_VIEW_NOT_CONFIRMED')
        left = w.read_payloads(directory/'baseline'); right = w.read_payloads(directory/'enabled')
        require(equal(left['ime-state.json']['analysis']['fields']['configuration'], right['ime-state.json']['analysis']['fields']['configuration']), 'KEYBOARD_CONFIGURATION_CHANGED')
        require(report['inputDevicesStatus'] == 'recorded', 'KEYBOARD_INPUT_DEVICES_NOT_RECORDED')
        report['status'], report['stage'], report['failureReason'] = 'passed', 'complete', None
    except Exception as failure:
        report['status'] = 'failed'; report['failureReason'] = str(failure) if isinstance(failure, Rejected) else 'HOST_' + type(failure).__name__
    finally:
        save()
        try: restore(directory, source, runner_factory)
        except Exception:
            if report['failureReason'] is None: report['failureReason'] = 'KEYBOARD_RESTORE_NOT_CONFIRMED'
        updated = sync_report(directory)
        for key in ('arms','restoration','inputDevicesStatus'): report[key] = updated[key]
        if report['status'] == 'passed' and report['restoration']['status'] != 'confirmed':
            report['status'], report['failureReason'] = 'failed', 'KEYBOARD_RESTORE_NOT_CONFIRMED'
        report['completedAt'] = now(); save()
    validate_evidence(tree_read(directory), source)
    return report['status'] == 'passed'


def finalize(directory, source, outcome, runner_factory=CommandRunner):
    directory = Path(directory)
    try: restore(directory, source, runner_factory)
    except Exception: pass
    for arm in ARMS:
        child = w.read_payloads(directory/arm)['report.json']
        if child['emulatorStepOutcome'] is None: w.finalize(directory/arm, source, outcome)
    report = sync_report(directory)
    if report['status'] == 'passed' and (outcome != 'success' or report['restoration']['status'] != 'confirmed'):
        report['status'], report['failureReason'] = 'failed', 'KEYBOARD_FINALIZATION_NOT_CONFIRMED'
    if report['runAttempted'] and report['status'] == 'unverified':
        report['status'], report['failureReason'] = 'failed', 'KEYBOARD_RUN_INTERRUPTED'
    report['emulatorStepOutcome'] = outcome; report['completedAt'] = now(); report['stage'] = 'finalized'
    payloads = tree_read(directory); payloads['report.json'] = report
    result = validate_evidence(payloads, source); w.write_json(directory/'report.json', report)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='command', required=True)
    for name in ('prepare','run','restore','finalize'):
        child = sub.add_parser(name); child.add_argument('--output', type=Path, required=True)
        if name in ('run','restore','finalize'): child.add_argument('--sdk-root', type=Path, required=name == 'run')
        if name == 'run':
            child.add_argument('--fixture-apk', type=Path, required=True); child.add_argument('--probe-jar', type=Path, required=True)
            child.add_argument('--avd-name', required=True); child.add_argument('--avd-home', type=Path)
        if name == 'finalize': child.add_argument('--outcome', choices=['success','failure','cancelled','skipped','unknown'], required=True)
    args = parser.parse_args(); source = w.source_from_environment()
    def cancel(_signal, _frame): w.CANCELLED = True; w.common.CANCELLED = True
    signal.signal(signal.SIGINT, cancel); signal.signal(signal.SIGTERM, cancel)
    if args.command == 'prepare': prepare(args.output, source)
    elif args.command == 'run': return 0 if run_probe(args, source) else 1
    elif args.command == 'restore': restore(args.output, source)
    else: finalize(args.output, source, args.outcome)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
