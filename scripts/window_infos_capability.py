#!/usr/bin/env python3
"""One bounded, nonvisual API37 capability receipt; never application acceptance."""
import argparse
import base64
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import time

import third_party_smoke as device_rules

REPOSITORY = 'ecojust/Agent-OS'
WORKFLOW = 'window-infos-capability.yml'
KIND = 'window-infos-capability'
API = 37
LABEL = '37.0'
PACKAGE = 'dev.agentos.compat'
COMPONENT = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
TEST_CLASS = PACKAGE + '.WindowInfosCapabilityInstrumentedTest'
TEST_METHOD = 'inspectPublicTestApiSurface'
MARKER = 'WINDOW_INFOS_CAPABILITY_V1 '
MAX_OUTPUT = 262144
MAX_SETUP_OUTPUT = 65536
MAX_TOTAL_OUTPUT = 2 * 1024 * 1024
MAX_JSON = 4 * 1024 * 1024
MAX_COMMANDS = 24
MAX_MARKER = 16384
FILES = {'report.json', 'capture.json', 'commands.json', 'instrumentation.json'}
CANCELLED = False
SOURCE_PATHS = ('scripts/window_infos_capability.py', 'scripts/window_infos_capability_archive.py',
                'scripts/third_party_smoke.py', 'scripts/third_party_smoke_archive.py',
                'compat-smoke/src/androidTest/java/dev/agentos/compat/WindowInfosCapabilityInstrumentedTest.java',
                'compat-smoke/src/androidTest/java/dev/agentos/compat/WindowInfosCapabilityProbe.java',
                'compat-smoke/build.gradle', '.github/workflows/window-infos-capability.yml')
PROPERTIES = ('ro.kernel.qemu', 'ro.boot.qemu', 'ro.build.version.sdk', 'ro.product.cpu.abi',
              'sys.boot_completed', 'ro.build.fingerprint')
COMMAND_IDS = ('devices', *('property_' + key for key in PROPERTIES), 'current_user', 'installed_packages',
               'apk_badging_probe', 'apk_badging_tests', 'install_probe', 'installed_paths_probe',
               'install_tests', 'installed_paths_tests', 'instrumentation_registration', 'instrumentation')
SCOPE = {'diagnosticOnly': True, 'nonvisual': True, 'listenerRegistration': False,
         'callbacks': False, 'shellPermissionAdoption': False, 'hiddenApiPolicyMutation': False,
         'screenshots': False, 'systemDumps': False, 'agentOrNotesInstalled': False,
         'applicationCompatibilityAcceptance': False}
CATALOG = [
    ('class.forTest', 'CLASS', None, 'CLASS'), ('class.windowInfo', 'CLASS', None, 'CLASS'),
    ('class.displayInfo', 'CLASS', None, 'CLASS'), ('constructor.forTest', 'CONSTRUCTOR', 0, 'CONSTRUCTOR'),
    *[(f'method.{operation}.{parameter}', 'METHOD', 0, 'VOID')
      for operation in ['add', 'remove'] for parameter in ['consumer', 'biConsumer']],
    *[(f'field.windowInfo.{name}', 'FIELD', 1, kind) for name, kind in [
        ('windowToken', 'IBINDER'), ('name', 'STRING'), ('displayId', 'INT'), ('bounds', 'RECT'),
        ('touchableRegion', 'REGION'), ('transform', 'MATRIX'), ('isTrustedOverlay', 'BOOLEAN'),
        ('isVisible', 'BOOLEAN'), ('isTouchable', 'BOOLEAN'), ('isFocusable', 'BOOLEAN'),
        ('isDuplicateTouchToWallpaper', 'BOOLEAN'), ('isWatchOutsideTouch', 'BOOLEAN')]],
    ('field.displayInfo.displayId', 'FIELD', 2, 'INT'), ('field.displayInfo.transform', 'FIELD', 2, 'MATRIX'),
    *[(f'gap.windowInfo.{name}', 'GAP', 1, None)
      for name in ['inputConfig', 'isClone', 'frame', 'layoutParamsType', 'ownerUid']]]


class Rejected(ValueError):
    pass


def require(condition, reason):
    if not condition:
        raise Rejected(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def equal(left, right):
    return json.dumps(left, sort_keys=True, separators=(',', ':')) == json.dumps(right, sort_keys=True, separators=(',', ':'))


def now():
    return datetime.now(timezone.utc).isoformat()


def timestamp(value):
    require(isinstance(value, str) and len(value) <= 40, 'TIMESTAMP_INVALID')
    try:
        result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        raise Rejected('TIMESTAMP_INVALID') from None
    require(result.utcoffset() is not None and result.utcoffset().total_seconds() == 0,
            'TIMESTAMP_NOT_UTC')
    return result


def validate_source(value):
    require(type(value) is dict and set(value) == {'repository', 'commit', 'runId', 'attempt'}, 'SOURCE_FIELDS')
    require(value['repository'] == REPOSITORY and isinstance(value['commit'], str)
            and re.fullmatch('[0-9a-f]{40}', value['commit']) is not None, 'SOURCE_INVALID')
    require(all(type(value[k]) is int and 0 < value[k] < 10**19 for k in ['runId', 'attempt']), 'SOURCE_RUN_INVALID')
    return value


def source_from_environment(env=None):
    env = os.environ if env is None else env
    require(env.get('GITHUB_ACTIONS') == 'true' and env.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and env.get('RUNNER_OS') == 'Linux', 'DEDICATED_GITHUB_LINUX_REQUIRED')
    require(env.get('GITHUB_REPOSITORY') == REPOSITORY and env.get('GITHUB_REF') == 'refs/heads/master'
            and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'SOURCE_SCOPE_REJECTED')
    require(env.get('GITHUB_WORKFLOW_REF') == f'{REPOSITORY}/.github/workflows/{WORKFLOW}@refs/heads/master',
            'WORKFLOW_REJECTED')
    for key in ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT']:
        require(re.fullmatch('[1-9][0-9]{0,18}', env.get(key, '')) is not None, 'SOURCE_RUN_INVALID')
    return validate_source({'repository': REPOSITORY, 'commit': env.get('GITHUB_SHA', ''),
                            'runId': int(env['GITHUB_RUN_ID']), 'attempt': int(env['GITHUB_RUN_ATTEMPT'])})


def unique_object(pairs):
    value = {}
    for key, item in pairs:
        require(key not in value, 'JSON_DUPLICATE_KEY')
        value[key] = item
    return value


def decode_json(data):
    require(len(data) <= MAX_JSON, 'JSON_SIZE_LIMIT')
    try:
        return json.loads(data, object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(Rejected('JSON_NONFINITE')))
    except (ValueError, UnicodeError, RecursionError):
        raise Rejected('JSON_INVALID') from None


def read_bounded(path, limit):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as stream:
        metadata = os.fstat(stream.fileno())
        require(stat.S_ISREG(metadata.st_mode) and 0 <= metadata.st_size <= limit, 'INPUT_FILE_LIMIT')
        data = stream.read(limit + 1)
        require(len(data) <= limit and len(data) == metadata.st_size, 'INPUT_FILE_CHANGED_OR_LIMIT')
        return data


def write_json(path, value):
    path = Path(path)
    require(not path.is_symlink() and path.parent.is_dir() and not path.parent.is_symlink(), 'OUTPUT_PATH_INVALID')
    data = (json.dumps(value, ensure_ascii=True, indent=2) + '\n').encode()
    require(len(data) <= MAX_JSON, 'JSON_SIZE_LIMIT')
    temporary = path.with_suffix('.pending')
    require(not temporary.exists() and not temporary.is_symlink(), 'OUTPUT_PENDING_EXISTS')
    try:
        temporary.write_bytes(data)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def read_payloads(directory):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'EVIDENCE_DIRECTORY_INVALID')
    require(all(p.name in FILES and p.is_file() and not p.is_symlink() for p in directory.iterdir()), 'EVIDENCE_FILESET_INVALID')
    return {p.name: decode_json(read_bounded(p, MAX_JSON)) for p in directory.iterdir()}


def source_files(repo=None):
    repo = Path(repo) if repo is not None else Path(__file__).resolve().parents[1]
    result = {}
    for name in SOURCE_PATHS:
        path = repo / name
        require(path.is_file() and not path.is_symlink(), 'SOURCE_FILE_MISSING')
        data = read_bounded(path, MAX_JSON)
        result[name] = {'bytes': len(data), 'sha256': sha(data)}
    return result


def verify_source_files(report, repo):
    require(report.get('sourceFiles') == source_files(repo), 'SOURCE_FILES_MISMATCH')
    return True


def empty_report(source, identities=None):
    return {'schemaVersion': 1, 'kind': KIND, 'source': copy.deepcopy(validate_source(source)),
            'matrixLabel': LABEL, 'apiLevel': API, 'scope': copy.deepcopy(SCOPE),
            'sourceFiles': source_files() if identities is None else copy.deepcopy(identities),
            'expectedTest': {'class': TEST_CLASS, 'method': TEST_METHOD, 'count': 1},
            'status': 'unverified', 'stage': 'prepared', 'createdAt': now(), 'completedAt': None,
            'device': None, 'apks': {}, 'capability': None, 'failureReason': 'NOT_EXECUTED',
            'instrumentationAttempted': False, 'emulatorStepOutcome': None}


def capture(source):
    return {'schemaVersion': 1, 'kind': KIND + '-capture', 'source': copy.deepcopy(source),
            'apiLevel': API, 'captureStatus': 'nonvisual', 'reason': 'metadata_only_no_visual_capture', 'files': []}


def prepare(directory, source):
    directory = Path(directory)
    require(not directory.is_symlink(), 'OUTPUT_PATH_INVALID')
    directory.mkdir(parents=True, exist_ok=True)
    require(not list(directory.iterdir()), 'OUTPUT_NOT_EMPTY')
    write_json(directory / 'report.json', empty_report(source))
    write_json(directory / 'capture.json', capture(source))
    write_json(directory / 'commands.json', {'schemaVersion': 1, 'kind': KIND + '-commands',
               'source': copy.deepcopy(source), 'commands': []})


def bounded_capture(command, seconds, byte_limit, reader=os.read):
    """Bound this invocation and retain partial bytes even on a read failure."""
    output = bytearray(); process = None; code = None; status = 'completed'; failure_type = None
    deadline = time.monotonic() + seconds
    try:
        if CANCELLED:
            return b'', {'status': 'cancelled', 'exitCode': None, 'bytes': 0, 'exceptionType': None}
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                if CANCELLED or time.monotonic() >= deadline:
                    status = 'cancelled' if CANCELLED else 'timed_out'; break
                for key, _ in selector.select(min(.1, max(0, deadline - time.monotonic()))):
                    chunk = reader(key.fd, min(65536, byte_limit - len(output) + 1))
                    if not chunk:
                        selector.unregister(key.fileobj); continue
                    available = byte_limit - len(output)
                    output.extend(chunk[:available])
                    if len(chunk) > available or len(output) >= byte_limit:
                        status = 'output_limit'; break
                if status != 'completed': break
        if status == 'completed':
            while True:
                remaining = deadline - time.monotonic()
                if CANCELLED or remaining <= 0:
                    status = 'cancelled' if CANCELLED else 'timed_out'; break
                try:
                    code = process.wait(timeout=min(.1, remaining))
                    if CANCELLED: status = 'cancelled'
                    elif time.monotonic() >= deadline: status = 'timed_out'
                    break
                except subprocess.TimeoutExpired: pass
    except Exception as failure:
        status, failure_type = 'exception', type(failure).__name__
    finally:
        if process is not None:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            if process.stdout is not None:
                try: process.stdout.close()
                except OSError as failure:
                    if status == 'completed': status, failure_type = 'exception', type(failure).__name__
            try: code = process.wait(timeout=1)
            except subprocess.TimeoutExpired: code = None
    if status == 'completed' and CANCELLED: status = 'cancelled'
    return bytes(output), {'status': status, 'exitCode': code, 'bytes': len(output), 'exceptionType': failure_type}


class CommandRunner:
    def __init__(self, directory, source, execute=bounded_capture):
        self.directory, self.source, self.execute = Path(directory), source, execute
        self.commands = []
        self.total_bytes = 0

    def call(self, name, argv, seconds=10, limit=MAX_SETUP_OUTPUT):
        require(len(self.commands) < MAX_COMMANDS and self.total_bytes + limit <= MAX_TOTAL_OUTPUT, 'COMMAND_BUDGET')
        start, tick = now(), time.monotonic()
        failure_type = None
        try:
            data, result = self.execute(argv, seconds, limit)
            failure_type = result.get('exceptionType')
        except Exception as failure:
            data, result = b'', {'status': 'exception', 'exitCode': None, 'bytes': 0}
            failure_type = type(failure).__name__
        require(type(data) is bytes and len(data) <= limit, 'COMMAND_OUTPUT_INVALID')
        item = {'ordinal': len(self.commands) + 1, 'id': name, 'argv': list(argv),
                'timeoutSeconds': seconds, 'byteLimit': limit, 'startedAt': start, 'endedAt': now(),
                'elapsedMillis': int((time.monotonic() - tick) * 1000), 'status': result['status'],
                'exitCode': result.get('exitCode'), 'bytes': len(data), 'sha256': sha(data),
                'outputBase64': base64.b64encode(data).decode('ascii'), 'exceptionType': failure_type}
        self.total_bytes += len(data)
        self.commands.append(item)
        write_json(self.directory / 'commands.json', {'schemaVersion': 1, 'kind': KIND + '-commands',
                   'source': self.source, 'commands': self.commands})
        return data, item

    def text(self, name, argv, seconds=10, limit=MAX_SETUP_OUTPUT):
        data, item = self.call(name, argv, seconds, limit)
        require(item['status'] == 'completed' and item['exitCode'] == 0, 'COMMAND_FAILED_' + name)
        require(item['elapsedMillis'] <= (seconds + 5) * 1000, 'COMMAND_BUDGET_EXCEEDED_' + name)
        try:
            return data.decode('utf-8').strip()
        except UnicodeError:
            raise Rejected('COMMAND_UTF8_INVALID') from None


def instrument_command(adb, serial, source):
    command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r']
    for key, value in [('class', TEST_CLASS + '#' + TEST_METHOD), ('windowInfosCapability', 'true'),
                       ('sourceSha', source['commit']), ('runId', str(source['runId'])),
                       ('runAttempt', str(source['attempt'])), ('expectedApi', str(API)), ('deviceSerial', serial)]:
        command.extend(['-e', key, value])
    return command + [COMPONENT]


def parse_internal_badging(output):
    """Internal test APK versionCode may be empty; only this contract's fields bind."""
    require(isinstance(output, str) and len(output.encode()) <= MAX_SETUP_OUTPUT, 'APK_BADGING_SIZE')
    patterns = {'package': r"^package: name='([^']+)'(?:\s|$)",
                'minSdk': r"^sdkVersion:'([0-9]+)'\s*$", 'targetSdk': r"^targetSdkVersion:'([0-9]+)'\s*$"}
    result = {}
    for key, pattern in patterns.items():
        values = re.findall(pattern, output, re.M)
        require(len(values) == 1, 'APK_BADGING_' + key.upper())
        result[key] = values[0] if key == 'package' else int(values[0])
    require(re.fullmatch('[A-Za-z][A-Za-z0-9_.]{0,159}', result['package']) is not None, 'APK_BADGING_PACKAGE')
    return result


def parse_instrumentation(data, source, serial):
    """Explicit status-block binding only: no previous-case or OK-text fallback."""
    require(type(data) is bytes and len(data) <= MAX_OUTPUT, 'INSTRUMENTATION_SIZE')
    try:
        output = data.decode('utf-8')
    except UnicodeError:
        raise Rejected('INSTRUMENTATION_UTF8') from None
    require(output.count(MARKER) == 1, 'MARKER_COUNT')
    blocks, block, continuation, final_codes = [], {}, None, []
    for line in output.splitlines():
        require(not (line.startswith(('INSTRUMENTATION_STATUS:', 'INSTRUMENTATION_STATUS_CODE:')) and final_codes),
                'STATUS_AFTER_FINAL_CODE')
        require(not line.startswith(('INSTRUMENTATION_FAILED:', 'INSTRUMENTATION_ABORTED:', 'INSTRUMENTATION_RESULT: shortMsg=')),
                'INSTRUMENTATION_FAILURE_RESULT')
        match = re.fullmatch(r'INSTRUMENTATION_STATUS: ([A-Za-z][A-Za-z0-9_]*)=(.*)', line)
        if match:
            key, value = match.groups()
            require(key not in block, 'STATUS_DUPLICATE_KEY')
            block[key] = value
            continuation = key
            continue
        match = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?[0-9]+)', line)
        if match:
            blocks.append((int(match[1]), block)); block, continuation = {}, None
            continue
        match = re.fullmatch(r'INSTRUMENTATION_CODE: (-?[0-9]+)', line)
        if match:
            require(not block, 'STATUS_UNCLOSED')
            final_codes.append(int(match[1])); continuation = None
            continue
        if line.startswith('INSTRUMENTATION_'):
            require(not block, 'STATUS_UNCLOSED')
            continuation = None
        elif continuation is not None:
            block[continuation] += '\n' + line
        elif MARKER in line:
            raise Rejected('MARKER_UNBOUND')
    require(not block and final_codes == [-1], 'INSTRUMENTATION_TERMINAL')
    starts, ends, marked = [], [], []
    for code, fields in blocks:
        has_case = 'class' in fields or 'test' in fields
        has_marker = any(MARKER in value for value in fields.values())
        if has_case or has_marker or code in {0, 1, -1, -2, -3, -4}:
            require(fields.get('class') == TEST_CLASS and fields.get('test') == TEST_METHOD, 'TEST_BINDING')
        if code in {0, 1, -1, -2, -3, -4}:
            require(fields.get('numtests') == '1' and fields.get('current') == '1', 'TEST_COUNT')
        if code == 1: starts.append(fields)
        elif code in {0, -1, -2, -3, -4}: ends.append(code)
        if has_marker:
            require(code == 2, 'MARKER_STATUS_CODE')
            values = [v for v in fields.values() if MARKER in v]
            require(len(values) == 1 and values[0].startswith(MARKER), 'MARKER_SHAPE')
            raw = values[0][len(MARKER):]
            require(len(values[0]) <= MAX_MARKER and raw.isascii(), 'MARKER_SIZE_OR_ENCODING')
            marked.append(decode_json(raw))
    require(len(blocks) == 3 and len(starts) == 1 and ends == [0] and len(marked) == 1, 'SINGLE_TEST_NOT_SUCCESSFUL')
    require([code for code, fields in blocks if fields.get('test') == TEST_METHOD] == [1, 2, 0], 'TEST_ORDER')
    result = validate_capability(marked[0], source, serial)
    require(result['probeExecution'] == 'COMPLETE', 'CAPABILITY_EXECUTION_INCOMPLETE')
    return result


def validate_capability(value, source, serial):
    fields = {'schemaVersion', 'kind', 'catalogVersion', 'source', 'executionContext', 'reflectionPolicy',
              'sdkInt', 'build', 'process', 'permissions', 'clock', 'startedElapsedMillis', 'endedElapsedMillis',
              'betweenOperationDeadlineMillis', 'deadlineExceeded', 'probeExecution', 'lookupLimit',
              'lookupAttempts', 'lookups', 'constructor', 'capability', 'diagnosticTruncated'}
    require(type(value) is dict and set(value) == fields, 'CAPABILITY_FIELDS')
    constants = {'schemaVersion': 1, 'kind': 'window-infos-public-test-api-capability', 'catalogVersion': 1,
                 'executionContext': 'DEFAULT_INSTRUMENTATION_TEST_PROCESS',
                 'reflectionPolicy': 'PUBLIC_WHITELIST_ONLY_NO_ADDITIONAL_EXEMPTION_REQUEST', 'sdkInt': API,
                 'clock': 'SystemClock.elapsedRealtime', 'betweenOperationDeadlineMillis': 5000,
                 'lookupLimit': 27, 'diagnosticTruncated': False}
    require(all(equal(value[k], expected) for k, expected in constants.items()), 'CAPABILITY_CONTRACT')
    expected_source = {'sourceSha': source['commit'], 'runId': str(source['runId']), 'runAttempt': str(source['attempt']),
                       'expectedApi': str(API), 'deviceSerial': serial}
    require(equal(value['source'], expected_source), 'CAPABILITY_SOURCE')
    build = value['build']
    require(type(build) is dict and set(build) == {'buildId', 'valueRelease', 'fingerprintSha256',
            'buildIdStatus', 'releaseStatus', 'fingerprintStatus'}, 'BUILD_FIELDS')
    for name, status, limit in [('buildId', 'buildIdStatus', 128), ('valueRelease', 'releaseStatus', 32),
                                ('fingerprintSha256', 'fingerprintStatus', 64)]:
        require(build[status] in {'AVAILABLE', 'UNAVAILABLE'}, 'BUILD_STATUS')
        if build[status] == 'UNAVAILABLE': require(build[name] is None, 'UNAVAILABLE_BUILD_VALUE')
        else:
            pattern = '[0-9a-f]{64}' if name == 'fingerprintSha256' else '[A-Za-z0-9._+-]+'
            require(isinstance(build[name], str) and len(build[name]) <= limit
                    and re.fullmatch(pattern, build[name]) is not None, 'BUILD_VALUE')
    process = value['process']
    require(type(process) is dict and set(process) == {'uid', 'targetSdk', 'targetPackage', 'testPackage'}
            and type(process['uid']) is int and 10000 <= process['uid'] < 100000
            and type(process['targetSdk']) is int and process['targetSdk'] == 35
            and process['targetPackage'] == PACKAGE and process['testPackage'] == PACKAGE + '.test', 'PROCESS_IDENTITY')
    require(type(value['permissions']) is dict and set(value['permissions']) == {'accessSurfaceFlinger'}
            and value['permissions']['accessSurfaceFlinger'] in {'GRANTED', 'DENIED', 'CHECK_FAILED'}, 'PERMISSION_SHAPE')
    start, end = value['startedElapsedMillis'], value['endedElapsedMillis']
    require(type(start) is int and type(end) is int and 0 <= start <= end < 2**63, 'CAPABILITY_TIME')
    require(type(value['deadlineExceeded']) is bool and value['deadlineExceeded'] == (end - start >= 5000), 'CAPABILITY_DEADLINE')
    require(value['probeExecution'] == ('INCOMPLETE_DEADLINE' if value['deadlineExceeded'] else 'COMPLETE'), 'CAPABILITY_EXECUTION')
    lookups = value['lookups']
    require(type(lookups) is list and len(lookups) == len(CATALOG), 'LOOKUP_COUNT')
    observed_types = {'CLASS', 'CONSTRUCTOR', 'VOID', 'INT', 'BOOLEAN', 'STRING', 'IBINDER', 'RECT', 'REGION', 'MATRIX', 'OTHER'}
    outcomes = {'FOUND_MATCHED', 'FOUND_MISMATCH', 'FOUND_SOURCE_UNLISTED', 'NOT_FOUND_OR_FILTERED', 'ACCESS_DENIED',
                'LINKAGE_ERROR', 'REFLECTION_ERROR', 'SKIPPED_CLASS_UNAVAILABLE', 'SKIPPED_DEADLINE'}
    detail_keys = {'public', 'static', 'final', 'observedType', 'typeMatches', 'declaringClassMatches'}
    attempts, required_matched, deadline_seen = 0, True, False
    for index, (entry, spec) in enumerate(zip(lookups, CATALOG)):
        identifier, kind, owner, expected_type = spec
        require(type(entry) is dict and set(entry) == {'id', 'status', 'attempted'} | detail_keys
                and entry['id'] == identifier and entry['status'] in outcomes and type(entry['attempted']) is bool, 'LOOKUP_FIELDS')
        status = entry['status']
        if deadline_seen: require(status == 'SKIPPED_DEADLINE', 'LOOKUP_AFTER_DEADLINE')
        if status == 'SKIPPED_DEADLINE':
            require(value['deadlineExceeded'], 'LOOKUP_FALSE_DEADLINE'); deadline_seen = True
        elif owner is not None and lookups[owner]['status'] != 'FOUND_MATCHED':
            require(status == 'SKIPPED_CLASS_UNAVAILABLE', 'LOOKUP_MISSING_OWNER')
        else: require(status != 'SKIPPED_CLASS_UNAVAILABLE', 'LOOKUP_WRONG_SKIP')
        skipped = status in {'SKIPPED_DEADLINE', 'SKIPPED_CLASS_UNAVAILABLE'}
        require(entry['attempted'] == (not skipped), 'LOOKUP_ATTEMPT')
        attempts += int(entry['attempted'])
        found = status.startswith('FOUND_')
        if not found: require(all(entry[k] is None for k in detail_keys), 'LOOKUP_UNAVAILABLE_METADATA')
        else:
            require(all(type(entry[k]) is bool for k in ['public', 'static', 'final', 'declaringClassMatches'])
                    and entry['observedType'] in observed_types, 'LOOKUP_METADATA')
            if kind != 'CLASS': require(entry['public'] is True, 'PUBLIC_LOOKUP_NONPUBLIC_RESULT')
            if kind in {'METHOD', 'FIELD', 'GAP'}:
                require(entry['observedType'] not in {'CLASS', 'CONSTRUCTOR'}, 'MEMBER_TYPE_CLASSIFICATION')
            if kind in {'FIELD', 'GAP'}: require(entry['observedType'] != 'VOID', 'FIELD_VOID_TYPE')
            if kind == 'GAP':
                require(status == 'FOUND_SOURCE_UNLISTED' and entry['typeMatches'] is None,
                        'GAP_CANNOT_UPGRADE_REQUIRED_SURFACE')
            else:
                require(status != 'FOUND_SOURCE_UNLISTED' and type(entry['typeMatches']) is bool, 'LOOKUP_TYPE_MATCH')
                require(not entry['typeMatches'] or entry['observedType'] == expected_type, 'LOOKUP_OBSERVED_TYPE')
                if kind in {'CLASS', 'CONSTRUCTOR'}: require(entry['observedType'] == expected_type, 'LOOKUP_KIND')
                matched = entry['public'] and entry['static'] == (kind == 'CLASS' and index != 0)
                matched = matched and entry['typeMatches'] and entry['declaringClassMatches']
                if kind == 'FIELD': matched = matched and entry['final']
                require(status == ('FOUND_MATCHED' if matched else 'FOUND_MISMATCH'), 'LOOKUP_MATCH_STATUS')
        if kind != 'GAP' and status != 'FOUND_MATCHED': required_matched = False
    require(type(value['lookupAttempts']) is int and value['lookupAttempts'] == attempts, 'LOOKUP_ATTEMPT_COUNT')
    construction = value['constructor']
    require(type(construction) is dict and set(construction) == {'status', 'attempts', 'completed'}
            and type(construction['attempts']) is int and construction['attempts'] in {0, 1}
            and type(construction['completed']) is int and construction['completed'] in {0, 1}, 'CONSTRUCTOR_FIELDS')
    status = construction['status']
    require(status in {'CONSTRUCTED', 'NOT_ATTEMPTED_LOOKUP_UNAVAILABLE', 'SKIPPED_DEADLINE',
                      'ACCESS_DENIED', 'INVOCATION_FAILED', 'LINKAGE_ERROR', 'REFLECTION_ERROR'}, 'CONSTRUCTOR_STATUS')
    if status == 'SKIPPED_DEADLINE': require(value['deadlineExceeded'], 'CONSTRUCTOR_FALSE_DEADLINE')
    elif lookups[3]['status'] != 'FOUND_MATCHED': require(status == 'NOT_ATTEMPTED_LOOKUP_UNAVAILABLE', 'CONSTRUCTOR_WITHOUT_LOOKUP')
    else: require(status != 'NOT_ATTEMPTED_LOOKUP_UNAVAILABLE', 'CONSTRUCTOR_MISSING_ATTEMPT')
    if deadline_seen: require(status == 'SKIPPED_DEADLINE', 'CONSTRUCTOR_AFTER_DEADLINE')
    require(construction['attempts'] == int(status not in {'SKIPPED_DEADLINE', 'NOT_ATTEMPTED_LOOKUP_UNAVAILABLE'})
            and construction['completed'] == int(status == 'CONSTRUCTED'), 'CONSTRUCTOR_ACCOUNTING')
    capability = {'requiredSurface': 'UNVERIFIED_DEADLINE' if value['deadlineExceeded'] else
                  'MATCHED' if required_matched else 'NOT_FULLY_MATCHED',
                  'listenerRegistration': 'NOT_ATTEMPTED', 'windowData': 'NOT_READ'}
    require(equal(value['capability'], capability), 'CAPABILITY_SUMMARY')
    return value


def run_probe(args, source, runner_factory=CommandRunner):
    directory = Path(args.output)
    payloads = read_payloads(directory)
    report = payloads.get('report.json')
    require(report == empty_prepared_equivalent(report, source), 'REPORT_NOT_PREPARED')
    runner = runner_factory(directory, source)
    stage = 'device_preflight'
    try:
        sdk = Path(args.sdk_root); adb = str(sdk / 'platform-tools/adb')
        emulator_properties = read_bounded(sdk / 'emulator/source.properties', 4096).decode('utf-8')
        version = device_rules.validate_emulator_version(emulator_properties, API)
        serial = device_rules.select_emulator(runner.text('devices', [adb, 'devices']))
        def shell(name, *words): return runner.text(name, [adb, '-s', serial, 'shell', *words])
        props = {key: shell('property_' + key, 'getprop', key) for key in PROPERTIES}
        device_rules.validate_device(serial, props, API)
        require(shell('current_user', 'am', 'get-current-user') == '0', 'OWNER_PROFILE_REQUIRED')
        report['device'] = {'serial': serial, 'apiLevel': API, 'emulatorVersion': version,
                            'emulatorSourceProperties': emulator_properties, 'properties': props}
        installed = shell('installed_packages', 'pm', 'list', 'packages').splitlines()
        require(all('package:' + p not in installed for p in [PACKAGE, PACKAGE + '.test', 'dev.agentos.app', 'org.fossify.notes']),
                'FRESH_ISOLATED_EMULATOR_REQUIRED')
        stage = 'apk_preflight'
        for role, path, expected_package in [('probe', args.probe_apk, PACKAGE), ('tests', args.test_apk, PACKAGE + '.test')]:
            path = Path(path)
            require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= 128 * 1024 * 1024, 'APK_PATH_INVALID')
            data = read_bounded(path, 128 * 1024 * 1024)
            badging = runner.text('apk_badging_' + role, [str(sdk / 'build-tools/35.0.0/aapt'), 'dump', 'badging', str(path)])
            metadata = parse_internal_badging(badging)
            require(metadata['package'] == expected_package and metadata['minSdk'] == 30 and metadata['targetSdk'] == 35, 'APK_PACKAGE_OR_SDK_MISMATCH')
            report['apks'][role] = {'package': expected_package, 'bytes': len(data), 'sha256': sha(data),
                                     'minSdk': metadata['minSdk'], 'targetSdk': metadata['targetSdk'], 'installedPaths': []}
        for role, path in [('probe', args.probe_apk), ('tests', args.test_apk)]:
            stage = 'install_' + role
            require(sha(read_bounded(path, 128 * 1024 * 1024)) == report['apks'][role]['sha256'], 'APK_CHANGED_BEFORE_INSTALL')
            result = runner.text(stage, [adb, '-s', serial, 'install', '-t', str(path)], seconds=60)
            require(re.search(r'^Success\s*$', result, re.M) is not None, 'INSTALL_NOT_SUCCESSFUL')
            paths = shell('installed_paths_' + role, 'pm', 'path', report['apks'][role]['package']).splitlines()
            require(len(paths) == 1 and re.fullmatch(r'package:/data/app/[A-Za-z0-9_./=+~-]+/base\.apk', paths[0]),
                    'INSTALLED_APK_PATH_INVALID')
            report['apks'][role]['installedPaths'] = [paths[0][len('package:'):]]
        registration = shell('instrumentation_registration', 'pm', 'list', 'instrumentation', PACKAGE)
        require(registration.splitlines() == ['instrumentation:' + COMPONENT + ' (target=' + PACKAGE + ')'],
                'INSTALLED_INSTRUMENTATION_MISMATCH')
        stage = 'instrumentation'
        report['instrumentationAttempted'] = True
        report['stage'] = stage
        write_json(directory / 'report.json', report)
        data, command = runner.call('instrumentation', instrument_command(adb, serial, source), 60, MAX_OUTPUT)
        output = data.decode('utf-8', 'replace')
        receipt = {'schemaVersion': 1, 'kind': KIND + '-instrumentation', 'source': source,
                   'commandCompleted': command['status'] == 'completed', 'commandOrdinal': command['ordinal'],
                   'output': output, 'bytes': len(data), 'sha256': sha(data),
                   'utf8Exact': output.encode('utf-8') == data}
        write_json(directory / 'instrumentation.json', receipt)
        require(command['status'] == 'completed' and command['exitCode'] == 0, 'INSTRUMENTATION_COMMAND_FAILED')
        require(command['elapsedMillis'] <= 65000, 'INSTRUMENTATION_BUDGET_EXCEEDED')
        report['capability'] = parse_instrumentation(data, source, serial)
        report['status'], report['failureReason'], stage = 'recorded', None, 'completed'
    except Exception as failure:
        report['status'] = 'failed' if report['instrumentationAttempted'] else 'unverified'
        report['failureReason'] = str(failure) if isinstance(failure, Rejected) else 'ENVIRONMENT_' + type(failure).__name__
        report['capability'] = None
    finally:
        report['stage'], report['completedAt'] = stage, now()
        write_json(directory / 'report.json', report)
        write_json(directory / 'capture.json', capture(source))
    return report['status'] == 'recorded'


def empty_prepared_equivalent(report, source):
    require(type(report) is dict, 'REPORT_MISSING')
    expected = empty_report(source, report.get('sourceFiles'))
    expected['createdAt'] = report.get('createdAt')
    timestamp(expected['createdAt'])
    return expected


def validate_evidence(payloads, source):
    validate_source(source)
    require(type(payloads) is dict and set(payloads) <= FILES and 'report.json' in payloads, 'EVIDENCE_FILESET_INVALID')
    require(sum(len(json.dumps(v, ensure_ascii=True).encode()) for v in payloads.values()) <= 8 * 1024 * 1024,
            'EVIDENCE_TOTAL_SIZE')
    report = payloads['report.json']
    expected = empty_report(source, {})
    require(type(report) is dict and set(report) == set(expected), 'REPORT_FIELDS')
    for key in ['schemaVersion', 'kind', 'source', 'matrixLabel', 'apiLevel', 'scope', 'expectedTest']:
        require(equal(report[key], expected[key]), 'REPORT_CONTRACT_' + key)
    require(type(report['sourceFiles']) is dict and set(report['sourceFiles']) == set(SOURCE_PATHS), 'SOURCE_FILES_SHAPE')
    for identity in report['sourceFiles'].values(): validate_identity(identity)
    require(report['status'] in {'recorded', 'failed', 'unverified'}, 'REPORT_STATUS')
    require(report['stage'] in {'prepared', 'device_preflight', 'apk_preflight', 'install_probe', 'install_tests',
                                'instrumentation', 'completed'}, 'REPORT_STAGE')
    require(type(report['instrumentationAttempted']) is bool, 'ATTEMPT_TYPE')
    require(report['emulatorStepOutcome'] in {None, 'success', 'failure', 'cancelled', 'skipped', 'unknown'}, 'STEP_OUTCOME')
    start = timestamp(report['createdAt']); end = timestamp(report['completedAt']) if report['completedAt'] is not None else None
    require(end is None or end >= start, 'REPORT_TIME_ORDER')
    require(report['failureReason'] is None or (isinstance(report['failureReason'], str)
            and re.fullmatch('[A-Z_][A-Za-z0-9_.]*', report['failureReason']) and len(report['failureReason']) <= 180), 'FAILURE_REASON')
    if report['status'] == 'recorded':
        require(set(payloads) == FILES and report['failureReason'] is None and report['stage'] == 'completed'
                and end is not None and report['instrumentationAttempted'] and report['emulatorStepOutcome'] in {None, 'success'}, 'RECORDED_INCOMPLETE')
    else:
        require(report['capability'] is None and report['failureReason'] is not None, 'UNVERIFIED_CAPABILITY')
    device = report['device']
    if device is not None:
        require(type(device) is dict and set(device) == {'serial', 'apiLevel', 'emulatorVersion', 'emulatorSourceProperties', 'properties'}
                and type(device['apiLevel']) is int and device['apiLevel'] == API and type(device['properties']) is dict
                and set(device['properties']) == set(PROPERTIES), 'DEVICE_SHAPE')
        require(all(isinstance(v, str) and len(v) <= 1024 for v in device['properties'].values()), 'DEVICE_PROPERTY_BOUND')
        device_rules.validate_device(device['serial'], device['properties'], API)
        require(isinstance(device['emulatorSourceProperties'], str) and len(device['emulatorSourceProperties'].encode()) <= 4096,
                'EMULATOR_PROPERTIES_BOUND')
        require(device_rules.validate_emulator_version(device['emulatorSourceProperties'], API) == device['emulatorVersion'], 'EMULATOR_VERSION_MISMATCH')
    else: require(report['status'] != 'recorded', 'RECORDED_DEVICE_MISSING')
    require(type(report['apks']) is dict and set(report['apks']) <= {'probe', 'tests'}, 'APK_SET')
    for role, identity in report['apks'].items():
        require(type(identity) is dict and set(identity) == {'package', 'bytes', 'sha256', 'minSdk', 'targetSdk', 'installedPaths'}, 'APK_FIELDS')
        validate_identity({k: identity[k] for k in ['bytes', 'sha256']}, 128 * 1024 * 1024)
        require(identity['package'] == PACKAGE + ('.test' if role == 'tests' else '')
                and type(identity['minSdk']) is int and identity['minSdk'] == 30
                and type(identity['targetSdk']) is int and identity['targetSdk'] == 35, 'APK_METADATA')
        require(type(identity['installedPaths']) is list and len(identity['installedPaths']) <= 1
                and all(isinstance(p, str) and re.fullmatch(r'/data/app/[A-Za-z0-9_./=+~-]+/base\.apk', p) for p in identity['installedPaths']), 'APK_INSTALLED_PATHS')
    if 'capture.json' in payloads: require(equal(payloads['capture.json'], capture(source)), 'CAPTURE_NOT_NONVISUAL')
    commands = validate_commands(payloads.get('commands.json'), source, report, start, end)
    instrumentation = payloads.get('instrumentation.json')
    command = next((c for c in commands if c['id'] == 'instrumentation'), None)
    parsed = None
    if instrumentation is not None:
        require(type(instrumentation) is dict and set(instrumentation) == {'schemaVersion', 'kind', 'source',
                'commandCompleted', 'commandOrdinal', 'output', 'bytes', 'sha256', 'utf8Exact'}, 'INSTRUMENTATION_FIELDS')
        require(type(instrumentation['schemaVersion']) is int and instrumentation['schemaVersion'] == 1
                and instrumentation['kind'] == KIND + '-instrumentation' and equal(instrumentation['source'], source), 'INSTRUMENTATION_SOURCE')
        require(command is not None and instrumentation['commandOrdinal'] == command['ordinal']
                and type(instrumentation['commandOrdinal']) is int and type(instrumentation['commandCompleted']) is bool
                and instrumentation['commandCompleted'] == (command['status'] == 'completed'), 'INSTRUMENTATION_COMMAND_BINDING')
        data = base64.b64decode(command['outputBase64'], validate=True)
        require(instrumentation['output'] == data.decode('utf-8', 'replace') and instrumentation['bytes'] == len(data)
                and type(instrumentation['bytes']) is int and instrumentation['sha256'] == sha(data)
                and type(instrumentation['utf8Exact']) is bool and instrumentation['utf8Exact'] == (instrumentation['output'].encode() == data),
                'INSTRUMENTATION_BYTES_MISMATCH')
        if command['status'] == 'completed' and command['exitCode'] == 0:
            try: parsed = parse_instrumentation(data, source, device['serial'])
            except (ValueError, TypeError): pass
    if report['status'] == 'recorded':
        require(len(commands) == len(COMMAND_IDS) and all(c['status'] == 'completed' and c['exitCode'] == 0
                and c['elapsedMillis'] <= (c['timeoutSeconds'] + 5) * 1000 for c in commands),
                'RECORDED_COMMANDS_INCOMPLETE')
        require(set(report['apks']) == {'probe', 'tests'} and all(len(v['installedPaths']) == 1 for v in report['apks'].values()), 'RECORDED_APKS_MISSING')
        validate_recorded_setup(commands, report)
        require(parsed is not None and equal(parsed, report['capability']), 'RECORDED_CAPABILITY_MISMATCH')
        if parsed['build']['fingerprintStatus'] == 'AVAILABLE':
            require(parsed['build']['fingerprintSha256'] == sha(device['properties']['ro.build.fingerprint'].encode()), 'BUILD_FINGERPRINT_MISMATCH')
    return {'status': report['status'], 'expectedTests': 1, 'recordedTests': int(report['status'] == 'recorded'),
            'apiLevel': API, 'nonvisual': True, 'capability': report['capability']}


def validate_identity(value, limit=MAX_JSON):
    require(type(value) is dict and set(value) == {'bytes', 'sha256'} and type(value['bytes']) is int
            and 0 < value['bytes'] <= limit and isinstance(value['sha256'], str)
            and re.fullmatch('[0-9a-f]{64}', value['sha256']) is not None, 'FILE_IDENTITY_INVALID')


def validate_commands(value, source, report, start, end):
    if value is None:
        require(report['status'] != 'recorded', 'COMMANDS_MISSING'); return []
    require(type(value) is dict and set(value) == {'schemaVersion', 'kind', 'source', 'commands'}
            and type(value['schemaVersion']) is int and value['schemaVersion'] == 1
            and value['kind'] == KIND + '-commands' and equal(value['source'], source), 'COMMANDS_SOURCE')
    commands = value['commands']
    require(type(commands) is list and len(commands) <= len(COMMAND_IDS), 'COMMANDS_COUNT')
    total = 0; previous = start
    for index, item in enumerate(commands):
        require(type(item) is dict and set(item) == {'ordinal', 'id', 'argv', 'timeoutSeconds', 'byteLimit',
                'startedAt', 'endedAt', 'elapsedMillis', 'status', 'exitCode', 'bytes', 'sha256', 'outputBase64', 'exceptionType'}, 'COMMAND_FIELDS')
        require(type(item['ordinal']) is int and item['ordinal'] == index + 1 and item['id'] == COMMAND_IDS[index], 'COMMAND_ORDER')
        require(type(item['argv']) is list and 1 <= len(item['argv']) <= 40
                and all(isinstance(v, str) and 0 < len(v) <= 1024 and '\x00' not in v for v in item['argv']), 'COMMAND_ARGV')
        expected_seconds = 60 if item['id'] in {'install_probe', 'install_tests', 'instrumentation'} else 10
        expected_bytes = MAX_OUTPUT if item['id'] == 'instrumentation' else MAX_SETUP_OUTPUT
        require(type(item['timeoutSeconds']) is int and item['timeoutSeconds'] == expected_seconds
                and type(item['byteLimit']) is int and item['byteLimit'] == expected_bytes, 'COMMAND_LIMITS')
        require(item['status'] in {'completed', 'timed_out', 'output_limit', 'cancelled', 'exception'}, 'COMMAND_STATUS')
        require(item['exitCode'] is None or type(item['exitCode']) is int, 'COMMAND_EXIT_CODE')
        require((item['status'] == 'exception') == (item['exceptionType'] is not None)
                and (item['exceptionType'] is None or (isinstance(item['exceptionType'], str)
                and re.fullmatch('[A-Za-z][A-Za-z0-9_]{0,79}', item['exceptionType']))), 'COMMAND_EXCEPTION')
        require(type(item['elapsedMillis']) is int and 0 <= item['elapsedMillis'] < 2**63, 'COMMAND_TIME_INVALID')
        started, ended = timestamp(item['startedAt']), timestamp(item['endedAt'])
        require(previous <= started <= ended and (end is None or ended <= end), 'COMMAND_TIME_ORDER'); previous = ended
        require(isinstance(item['outputBase64'], str) and len(item['outputBase64']) <= 4 * ((expected_bytes + 2) // 3), 'COMMAND_OUTPUT_SIZE')
        try: data = base64.b64decode(item['outputBase64'], validate=True)
        except ValueError: raise Rejected('COMMAND_OUTPUT_ENCODING') from None
        require(len(data) <= expected_bytes and type(item['bytes']) is int and item['bytes'] == len(data)
                and item['sha256'] == sha(data), 'COMMAND_OUTPUT_IDENTITY')
        total += len(data)
        validate_command_argv(item, report, source)
    require(total <= MAX_TOTAL_OUTPUT, 'COMMAND_TOTAL_OUTPUT_LIMIT')
    return commands


def validate_command_argv(item, report, source):
    argv, name = item['argv'], item['id']
    if name == 'devices':
        require(len(argv) == 2 and Path(argv[0]).name == 'adb' and argv[1] == 'devices', 'COMMAND_DEVICES'); return
    if name.startswith('apk_badging_'):
        require(len(argv) == 4 and Path(argv[0]).name == 'aapt' and argv[1:3] == ['dump', 'badging'], 'COMMAND_BADGING')
        return
    device = report['device']
    serial = device['serial'] if device is not None else (argv[2] if len(argv) >= 3 else '')
    require(re.fullmatch(r'emulator-[0-9]{4,5}', serial) is not None and argv[:3] == [argv[0], '-s', serial]
            and Path(argv[0]).name == 'adb', 'COMMAND_DEVICE_BINDING')
    if name == 'instrumentation':
        require(argv == instrument_command(argv[0], serial, source), 'INSTRUMENTATION_ARGV'); return
    if name.startswith('property_'): tail = ['shell', 'getprop', name[len('property_'):]]
    elif name == 'current_user': tail = ['shell', 'am', 'get-current-user']
    elif name == 'installed_packages': tail = ['shell', 'pm', 'list', 'packages']
    elif name.startswith('install_'):
        require(len(argv) == 6 and argv[3:5] == ['install', '-t'], 'INSTALL_FLAGS'); return
    elif name.startswith('installed_paths_'):
        role = name[len('installed_paths_'):]; tail = ['shell', 'pm', 'path', PACKAGE + ('.test' if role == 'tests' else '')]
    elif name == 'instrumentation_registration': tail = ['shell', 'pm', 'list', 'instrumentation', PACKAGE]
    else: raise Rejected('COMMAND_UNKNOWN')
    require(argv[3:] == tail, 'COMMAND_SCOPE')


def validate_recorded_setup(commands, report):
    by_id = {item['id']: item for item in commands}
    def output(name):
        try: return base64.b64decode(by_id[name]['outputBase64'], validate=True).decode('utf-8').strip()
        except UnicodeError: raise Rejected('SETUP_OUTPUT_UTF8') from None
    device = report['device']
    require(device_rules.select_emulator(output('devices')) == device['serial'], 'RECORDED_SERIAL_MISMATCH')
    for key in PROPERTIES:
        require(output('property_' + key) == device['properties'][key], 'RECORDED_PROPERTY_MISMATCH')
    require(output('current_user') == '0', 'RECORDED_USER_MISMATCH')
    installed = output('installed_packages').splitlines()
    require(all('package:' + p not in installed for p in [PACKAGE, PACKAGE + '.test', 'dev.agentos.app', 'org.fossify.notes']),
            'RECORDED_FRESH_INSTALL_MISMATCH')
    for role in ['probe', 'tests']:
        identity = report['apks'][role]
        metadata = parse_internal_badging(output('apk_badging_' + role))
        require(all(metadata[key] == identity[key] for key in ['package', 'minSdk', 'targetSdk']), 'RECORDED_BADGING_MISMATCH')
        require(by_id['apk_badging_' + role]['argv'][-1] == by_id['install_' + role]['argv'][-1], 'APK_COMMAND_PATH_MISMATCH')
        require(re.search(r'^Success\s*$', output('install_' + role), re.M) is not None, 'RECORDED_INSTALL_FAILURE')
        require(output('installed_paths_' + role).splitlines() == ['package:' + p for p in identity['installedPaths']], 'RECORDED_INSTALLED_PATH_MISMATCH')
    require(output('instrumentation_registration').splitlines() == ['instrumentation:' + COMPONENT + ' (target=' + PACKAGE + ')'],
            'RECORDED_RUNNER_REGISTRATION_MISMATCH')


def finalize(directory, source, outcome):
    payloads = read_payloads(directory)
    report = payloads.get('report.json')
    require(type(report) is dict and report.get('source') == source, 'FINALIZE_SOURCE_MISMATCH')
    if outcome != 'success' and report.get('status') == 'recorded':
        report['status'], report['failureReason'], report['capability'] = 'failed', 'EMULATOR_STEP_' + outcome.upper(), None
    report['emulatorStepOutcome'] = outcome
    report['completedAt'] = report.get('completedAt') or now()
    write_json(Path(directory) / 'report.json', report)
    write_json(Path(directory) / 'capture.json', capture(source))
    return validate_evidence(read_payloads(directory), source)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ['prepare', 'run', 'finalize']:
        child = sub.add_parser(name); child.add_argument('--output', type=Path, required=True)
        if name == 'run':
            child.add_argument('--sdk-root', type=Path, required=True)
            child.add_argument('--probe-apk', type=Path, required=True)
            child.add_argument('--test-apk', type=Path, required=True)
        if name == 'finalize': child.add_argument('--outcome', choices=['success', 'failure', 'cancelled', 'skipped', 'unknown'], required=True)
    args = parser.parse_args(); source = source_from_environment()
    def cancel(_signum, _frame):
        global CANCELLED
        CANCELLED = True
    signal.signal(signal.SIGTERM, cancel); signal.signal(signal.SIGINT, cancel)
    if args.command == 'prepare': prepare(args.output, source)
    elif args.command == 'run': return 0 if run_probe(args, source) else 1
    else: print(json.dumps(finalize(args.output, source, args.outcome)))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
