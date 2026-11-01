#!/usr/bin/env python3
"""Bounded, opt-in API37 shell raw-window diagnostic; never application acceptance."""
import argparse
import base64
import copy
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import selectors
import signal
import struct
import zlib
import subprocess
import time
import zipfile

import window_infos_capability as common
import build_window_infos_raw as dex_build
import third_party_smoke as devices

REPOSITORY = 'ecojust/Agent-OS'
WORKFLOW = 'window-infos-raw.yml'
KIND = 'window-infos-raw'
API = 37
LABEL = '37.0'
PACKAGE = 'dev.agentos.compat'
ACTIVITY = PACKAGE + '/.RawWindowFixtureActivity'
MAIN_CLASS = 'dev.agentos.raw.WindowInfosRawMain'
READY = 'WINDOW_INFOS_RAW_READY_V1 '
MARKER = 'WINDOW_INFOS_RAW_RESULT_V1 '
MAX_MARKER = 131072
MAX_OUTPUT = 262144
MAX_PNG = 1048576
MAX_JAR = 2097152
MAX_SETUP_OUTPUT = 65536
MAX_TOTAL_OUTPUT = 2 * 1024 * 1024
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 16 * 1024 * 1024
MAX_COMMANDS = 40
FILES = {'report.json', 'commands.json', 'capture.json', 'raw-result.json', 'fixture.json',
         'probe-build.json', 'probe.jar', 'screenshot.png'}
PROPERTIES = ('ro.kernel.qemu', 'ro.boot.qemu', 'ro.build.version.sdk', 'ro.product.cpu.abi',
              'sys.boot_completed', 'ro.build.fingerprint')
SOURCE_PATHS = ('scripts/window_infos_raw.py', 'scripts/window_infos_raw_archive.py',
                'scripts/build_window_infos_raw.py', 'scripts/window_infos_capability.py',
                'scripts/third_party_smoke.py', 'scripts/third_party_smoke_archive.py',
                'compat-smoke/src/main/java/dev/agentos/compat/RawWindowFixtureActivity.java',
                'compat-smoke/src/main/AndroidManifest.xml', 'compat-smoke/build.gradle',
                '.github/workflows/window-infos-raw.yml')
SCOPE = {'diagnosticOnly': True, 'executionContext': 'DEDICATED_EMULATOR_SHELL_APP_PROCESS',
         'rawRegistrationLimit': 1, 'showImeRequestLimit': 1, 'forTestListener': False,
         'hiddenApiPolicyMutation': False, 'rootRequested': False, 'fixtureScreenshot': True,
         'agentOrNotesInstalled': False, 'originalNotesCasesRerun': 0,
         'applicationCompatibilityAcceptance': False,
         'permissionPrecheck': 'NOT_INDEPENDENTLY_QUERIED',
         'nativeUnregisterReturnIsSfRemovalAcknowledgement': False}
PRE_IDS = ('devices', *('property_' + p for p in PROPERTIES), 'extra_vm_options', 'current_user',
           'shell_uid', 'timeout_available', 'installed_packages', 'ime_before', 'ime_list',
           'ime_uid', 'apk_badging', 'install_fixture', 'fixture_paths', 'fixture_uid',
           'push_probe', 'probe_digest')
POST_IDS = ('screenshot', 'fixture_receipt', 'ime_after', 'ime_uid_after', 'cleanup_fixture', 'cleanup_probe')
COMMAND_IDS = PRE_IDS + ('raw_probe', 'fixture_start') + POST_IDS
CANCELLED = False
Rejected = common.Rejected
require = common.require
sha = common.sha
now = common.now
write_json = common.write_json
read_bounded = common.read_bounded
validate_source = common.validate_source
decode_json = common.decode_json


def exact(value, keys, reason):
    require(type(value) is dict and set(value) == set(keys), reason)


def integer(value, low=0, high=2**63 - 1):
    return type(value) is int and low <= value <= high


def identity(data):
    return {'bytes': len(data), 'sha256': sha(data)}


def source_from_environment(env=None):
    env = os.environ if env is None else env
    copied = dict(env)
    require(copied.get('GITHUB_WORKFLOW_REF') == f'{REPOSITORY}/.github/workflows/{WORKFLOW}@refs/heads/master',
            'WORKFLOW_REJECTED')
    copied['GITHUB_WORKFLOW_REF'] = f'{REPOSITORY}/.github/workflows/{common.WORKFLOW}@refs/heads/master'
    return common.source_from_environment(copied)


def source_files(repo=None):
    repo = Path(repo) if repo is not None else Path(__file__).resolve().parents[1]
    extra = sorted(str(p.relative_to(repo)) for p in (repo / 'tools/window-infos-raw').rglob('*.java'))
    require(extra and len(extra) <= 32, 'JAVA_SOURCES_MISSING')
    return {name: identity(read_bounded(repo / name, MAX_FILE_BYTES)) for name in (*SOURCE_PATHS, *extra)}


def verify_source_files(report, repo):
    require(report.get('sourceFiles') == source_files(repo), 'SOURCE_FILES_MISMATCH')
    return True


def read_payloads(directory):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'BUNDLE_DIRECTORY')
    result = {}; total = 0
    for p in sorted(directory.iterdir()):
        require(p.name in FILES and p.is_file() and not p.is_symlink(), 'BUNDLE_FILESET')
        limit = MAX_PNG if p.name == 'screenshot.png' else MAX_JAR if p.name == 'probe.jar' else MAX_FILE_BYTES
        data = read_bounded(p, limit); total += len(data)
        result[p.name] = decode_json(data) if p.suffix == '.json' else data
    require(total <= MAX_TOTAL_BYTES, 'BUNDLE_TOTAL_LIMIT')
    return result


def empty_report(source, identities=None):
    return {'schemaVersion': 1, 'kind': KIND, 'source': copy.deepcopy(validate_source(source)),
            'apiLevel': API, 'matrixLabel': LABEL, 'scope': copy.deepcopy(SCOPE),
            'sourceFiles': source_files() if identities is None else copy.deepcopy(identities),
            'status': 'unverified', 'stage': 'prepared', 'failureReason': 'NOT_EXECUTED',
            'createdAt': now(), 'completedAt': None, 'emulatorStepOutcome': None,
            'device': None, 'tools': None, 'inputs': {}, 'ime': None,
            'rawAttempted': False, 'fixtureAttempted': False,
            'positiveCallbackCount': 0, 'remoteProcessExit': 'NOT_OBSERVED',
            'cleanup': {'fixture': 'NOT_ATTEMPTED', 'probeFile': 'NOT_ATTEMPTED'},
            'emulatorTeardown': 'WORKFLOW_RESPONSIBILITY_NOT_OBSERVED'}


def empty_capture(source):
    return {'schemaVersion': 1, 'kind': KIND + '-capture', 'source': source,
            'apiLevel': API, 'status': 'not_attempted', 'file': None,
            'scope': 'One dedicated synthetic editor screenshot; not proof of raw window identity',
            'secureWindowFlagsChanged': False}


def prepare(directory, source):
    directory = Path(directory); require(not directory.is_symlink(), 'OUTPUT_SYMLINK')
    directory.mkdir(parents=True, exist_ok=True); require(not list(directory.iterdir()), 'OUTPUT_NOT_EMPTY')
    write_json(directory / 'report.json', empty_report(source))
    write_json(directory / 'commands.json', {'schemaVersion': 1, 'kind': KIND + '-commands', 'source': source, 'commands': []})
    write_json(directory / 'capture.json', empty_capture(source))


def package_name(value):
    require(isinstance(value, str) and len(value) <= 128
            and re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', value), 'IME_PACKAGE')
    return value


def ime_component(value):
    require(isinstance(value, str) and len(value) <= 320 and value.count('/') == 1, 'IME_COMPONENT')
    pkg, cls = value.split('/')
    package_name(pkg)
    require(re.fullmatch(r'\.?[A-Za-z_$][A-Za-z0-9_.$]*', cls), 'IME_COMPONENT_CLASS')
    return pkg


def parse_uid(output, package):
    matches = re.findall(r'^package:([^\s]+) uid:([0-9]+)\s*$', output, re.M)
    require(len(matches) == len(output.splitlines()), 'PACKAGE_UID_FORMAT')
    values = [int(uid) for name, uid in matches if name == package]
    require(len(values) == 1 and 0 < values[0] < 100000, 'PACKAGE_UID_UNIQUE')
    return values[0]


def remote_jar(source):
    return f"/data/local/tmp/agentos-window-infos-raw-{source['runId']}-{source['attempt']}.jar"


def raw_command(adb, serial, source, ime):
    return [adb, '-s', serial, 'shell', 'CLASSPATH=' + remote_jar(source), '/system/bin/timeout',
            '-s', 'KILL', '40s', 'app_process', '/system/bin', MAIN_CLASS,
            source['commit'], str(source['runId']), str(source['attempt']), ime['package'],
            str(ime['uid']), '0']


def fixture_command(adb, serial, source):
    return [adb, '-s', serial, 'shell', 'am', 'start', '-W', '--user', '0', '-n', ACTIVITY,
            '--es', 'sourceSha', source['commit'], '--es', 'runId', str(source['runId']),
            '--es', 'runAttempt', str(source['attempt']), '--ez', 'rawWindowProbe', 'true']


def validate_ready(value, source, ime):
    exact(value, {'schemaVersion', 'source', 'imePackage', 'imeUid', 'displayId', 'registered',
                  'elapsedRealtimeMs'}, 'READY_FIELDS')
    require(value['schemaVersion'] == 1 and type(value['schemaVersion']) is int
            and common.equal(value['source'], source) and value['imePackage'] == ime['package']
            and value['imeUid'] == ime['uid'] and type(value['imeUid']) is int
            and value['displayId'] == 0 and type(value['displayId']) is int
            and value['registered'] is True and integer(value['elapsedRealtimeMs']), 'READY_BINDING')
    return value


def stream_capture(argv, seconds, limit, on_ready, source, ime, progress=None, reader=os.read):
    """Drain this process group, invoke stimulus once after a bound READY, retain failure prefixes."""
    output = bytearray(); pending = bytearray(); process = None; status = 'completed'; exception = None; code = None
    seen = 0; deadline = time.monotonic() + seconds
    try:
        if CANCELLED: return b'', {'status': 'cancelled', 'exitCode': None, 'exceptionType': None}
        process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                if CANCELLED or time.monotonic() >= deadline:
                    status = 'cancelled' if CANCELLED else 'timed_out'; break
                for key, _ in selector.select(min(.1, max(0, deadline - time.monotonic()))):
                    chunk = reader(key.fd, min(65536, limit - len(output) + 1))
                    if not chunk: selector.unregister(key.fileobj); continue
                    available = limit - len(output); kept = chunk[:available]; output.extend(kept); pending.extend(kept)
                    if progress is not None: progress(bytes(output))
                    if len(chunk) > available or len(output) >= limit: status = 'output_limit'; break
                    while b'\n' in pending:
                        line, _, rest = pending.partition(b'\n'); pending = bytearray(rest)
                        if line.startswith(READY.encode()):
                            seen += 1; require(seen == 1 and len(line) <= MAX_MARKER, 'READY_COUNT_OR_SIZE')
                            ready = validate_ready(decode_json(bytes(line[len(READY):])), source, ime)
                            on_ready(ready)
                if status != 'completed': break
        while status == 'completed':
            if CANCELLED or time.monotonic() >= deadline:
                status = 'cancelled' if CANCELLED else 'timed_out'; break
            try: code = process.wait(timeout=min(.1, deadline - time.monotonic())); break
            except subprocess.TimeoutExpired: pass
    except Exception as failure:
        status, exception = 'exception', type(failure).__name__
    finally:
        if process is not None:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            try: process.stdout.close()
            except OSError: pass
            try: code = process.wait(timeout=1)
            except subprocess.TimeoutExpired: code = None
    return bytes(output), {'status': status, 'exitCode': code, 'exceptionType': exception}


def command_limits(name):
    if name == 'raw_probe': return 60, MAX_OUTPUT
    if name == 'screenshot': return 10, MAX_PNG
    return (60 if name == 'install_fixture' else 10), MAX_SETUP_OUTPUT


class CommandRunner:
    def __init__(self, directory, source, execute=common.bounded_capture, stream=stream_capture):
        self.directory, self.source, self.execute, self.stream = Path(directory), source, execute, stream
        self.commands = []

    def flush(self):
        write_json(self.directory / 'commands.json', {'schemaVersion': 1, 'kind': KIND + '-commands',
                   'source': self.source, 'commands': self.commands})

    def call(self, name, argv, on_ready=None, ime=None):
        require(name in COMMAND_IDS and len(self.commands) < MAX_COMMANDS
                and name not in [c['id'] for c in self.commands], 'COMMAND_ID_OR_COUNT')
        seconds, limit = command_limits(name)
        require(sum(c['bytes'] for c in self.commands) + limit <= MAX_TOTAL_OUTPUT, 'COMMAND_TOTAL_LIMIT')
        started, tick = now(), time.monotonic(); slot = len(self.commands)
        item = {'ordinal': slot + 1, 'id': name, 'argv': list(argv), 'timeoutSeconds': seconds,
                'byteLimit': limit, 'startedAt': started, 'endedAt': None, 'elapsedMillis': 0,
                'status': 'running', 'exitCode': None, 'exceptionType': None,
                **identity(b''), 'outputBase64': ''}
        self.commands.append(item); self.flush()
        def progress(data):
            require(len(data) <= limit, 'COMMAND_LIMIT')
            item.update(**identity(data), outputBase64=base64.b64encode(data).decode('ascii'),
                        elapsedMillis=int((time.monotonic() - tick) * 1000)); self.flush()
        try:
            if name == 'raw_probe':
                data, result = self.stream(argv, seconds, limit, on_ready, self.source, ime, progress)
            else: data, result = self.execute(argv, seconds, limit)
        except Exception as failure:
            data, result = b'', {'status': 'exception', 'exitCode': None, 'exceptionType': type(failure).__name__}
        require(type(data) is bytes and len(data) <= limit, 'COMMAND_OUTPUT')
        item.update(**identity(data), outputBase64=base64.b64encode(data).decode('ascii'), endedAt=now(),
                    elapsedMillis=int((time.monotonic() - tick) * 1000), status=result['status'],
                    exitCode=result.get('exitCode'), exceptionType=result.get('exceptionType'))
        self.flush(); return data, item

    def text(self, name, argv):
        data, item = self.call(name, argv)
        require(command_ok(item), 'COMMAND_FAILED_' + name)
        try: return data.decode('utf-8').strip()
        except UnicodeError: raise Rejected('COMMAND_UTF8') from None


def command_ok(item):
    return item['status'] == 'completed' and item['exitCode'] == 0 and item['elapsedMillis'] <= item['timeoutSeconds'] * 1000


def parse_markers(data, source, ime):
    require(type(data) is bytes and len(data) <= MAX_OUTPUT, 'RAW_OUTPUT_SIZE')
    try: lines = data.decode('utf-8').splitlines()
    except UnicodeError: raise Rejected('RAW_OUTPUT_UTF8') from None
    ready_lines = [line for line in lines if line.startswith(READY)]
    result_lines = [line for line in lines if line.startswith(MARKER)]
    require(len(ready_lines) <= 1 and len(result_lines) == 1, 'RAW_MARKER_COUNT')
    require(sum(READY in line for line in lines) == len(ready_lines)
            and sum(MARKER in line for line in lines) == 1, 'RAW_MARKER_UNBOUND')
    require(all(len(line.encode()) + 1 <= MAX_MARKER for line in ready_lines + result_lines), 'RAW_MARKER_LIMIT')
    require(all((line + '\n').encode() in data or (line + '\r\n').encode() in data for line in ready_lines + result_lines), 'RAW_MARKER_LINE_INCOMPLETE')
    ready = validate_ready(decode_json(ready_lines[0][len(READY):]), source, ime) if ready_lines else None
    result = validate_raw(decode_json(result_lines[0][len(MARKER):]), source, ime)
    if ready is not None:
        require(lines.index(ready_lines[0]) < lines.index(result_lines[0]), 'RAW_MARKER_ORDER')
        require(result['times']['readyElapsedMs'] == ready['elapsedRealtimeMs'], 'RAW_READY_TIME')
    return ready, result


def validate_fixture(value, source, uid):
    exact(value, {'schemaVersion', 'kind', 'source', 'phase', 'sdkInt', 'uid', 'displayId',
                  'startedElapsedMs', 'showImeAttempts', 'showImeAccepted', 'showRequestedElapsedMs',
                  'imeVisibleObserved', 'firstImeVisibleElapsedMs', 'receiptWrites', 'receiptWrittenElapsedMs'}, 'FIXTURE_FIELDS')
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1
            and value['kind'] == 'agent-os-raw-window-fixture' and common.equal(value['source'], source)
            and type(value['sdkInt']) is int and value['sdkInt'] == API
            and type(value['uid']) is int and value['uid'] == uid, 'FIXTURE_IDENTITY')
    require(value['phase'] in {'created', 'show_requested', 'ime_visible', 'focus_lost', 'post_rejected'}
            and value['displayId'] in {-1, 0} and type(value['displayId']) is int
            and type(value['showImeAttempts']) is int and value['showImeAttempts'] in {0, 1}
            and type(value['imeVisibleObserved']) is bool and integer(value['receiptWrites'], 1, 3), 'FIXTURE_STATE')
    require(integer(value['startedElapsedMs']) and integer(value['receiptWrittenElapsedMs'], value['startedElapsedMs']), 'FIXTURE_TIME')
    if value['showImeAttempts']:
        require(type(value['showImeAccepted']) is bool and integer(value['showRequestedElapsedMs'], value['startedElapsedMs'], value['receiptWrittenElapsedMs']), 'FIXTURE_SHOW')
    else:
        require(value['showImeAccepted'] is None and value['showRequestedElapsedMs'] is None
                and value['phase'] in {'created', 'focus_lost', 'post_rejected'} and not value['imeVisibleObserved'], 'FIXTURE_NO_SHOW')
    if value['imeVisibleObserved']:
        require(value['phase'] == 'ime_visible' and value['showImeAttempts'] == 1
                and integer(value['firstImeVisibleElapsedMs'], value['showRequestedElapsedMs'], value['receiptWrittenElapsedMs']), 'FIXTURE_VISIBLE')
    else: require(value['firstImeVisibleElapsedMs'] is None, 'FIXTURE_NO_VISIBLE')
    return value


def fixture_passed(value):
    return value['phase'] == 'ime_visible' and value['showImeAttempts'] == 1 and value['showImeAccepted'] is True \
        and value['imeVisibleObserved'] is True and value['displayId'] == 0


def validate_jar(data):
    require(type(data) is bytes and 0 < len(data) <= MAX_JAR, 'PROBE_JAR_SIZE')
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            require(archive.namelist() == ['classes.dex'], 'PROBE_JAR_ENTRIES')
            entry = archive.getinfo('classes.dex'); require(0 < entry.file_size <= MAX_JAR, 'DEX_SIZE')
            dex = archive.read('classes.dex')
            require(len(dex) == entry.file_size and dex.startswith(b'dex\n'), 'DEX_HEADER')
            return identity(dex)
    except (zipfile.BadZipFile, RuntimeError, OSError): raise Rejected('PROBE_JAR_INVALID') from None


def run_probe(args, source, runner_factory=CommandRunner):
    directory = Path(args.output); payloads = read_payloads(directory); report = payloads['report.json']
    require(report['source'] == source and report['status'] == 'unverified', 'RUN_SOURCE_OR_STATE')
    require(report['sourceFiles'] == source_files(), 'RUN_SOURCE_FILES')
    runner = runner_factory(directory, source); capture = empty_capture(source)
    report['failureReason'] = None; report['stage'] = 'inputs'
    adb = str(Path(args.sdk_root).resolve() / 'platform-tools/adb')
    aapt = str(Path(args.sdk_root).resolve() / 'build-tools/35.0.0/aapt')
    serial = None; installed = False; pushed = False; raw = None; fixture = None
    report['tools'] = {'adb': adb, 'aapt': aapt, 'fixtureApk': str(Path(args.fixture_apk).resolve()),
                       'probeJar': str(Path(args.probe_jar).resolve())}
    def save(): write_json(directory / 'report.json', report)
    try:
        apk = read_bounded(args.fixture_apk, 32 * 1024 * 1024)
        jar = read_bounded(args.probe_jar, MAX_JAR); validate_jar(jar)
        build = decode_json(read_bounded(Path(str(args.probe_jar) + '.json'), MAX_SETUP_OUTPUT))
        report['inputs'] = {'fixtureApk': identity(apk), 'probeJar': identity(jar)}
        (directory / 'probe.jar').write_bytes(jar); write_json(directory / 'probe-build.json', build)
        validate_build(build, jar, report['sourceFiles'])
        save()
        serial = devices.select_emulator(runner.text('devices', [adb, 'devices']))
        prefix = [adb, '-s', serial]
        properties = {p: runner.text('property_' + p, prefix + ['shell', 'getprop', p]) for p in PROPERTIES}
        require(properties['ro.build.version.sdk'] == '37' and properties['ro.product.cpu.abi'] == 'x86_64'
                and properties['sys.boot_completed'] == '1'
                and '1' in [properties['ro.kernel.qemu'], properties['ro.boot.qemu']], 'DEVICE_SCOPE')
        require(0 < len(properties['ro.build.fingerprint']) <= 512, 'FINGERPRINT_BOUND')
        report['device'] = {'serial': serial, 'properties': properties}; save()
        require(runner.text('extra_vm_options', prefix + ['shell', 'getprop', 'dalvik.vm.extra-opts']) == '', 'EXTRA_VM_OPTIONS')
        require(runner.text('current_user', prefix + ['shell', 'am', 'get-current-user']) == '0', 'USER_SCOPE')
        require(runner.text('shell_uid', prefix + ['shell', 'id', '-u']) == '2000', 'SHELL_UID')
        runner.text('timeout_available', prefix + ['shell', 'test', '-x', '/system/bin/timeout'])
        packages = runner.text('installed_packages', prefix + ['shell', 'pm', 'list', 'packages']).splitlines()
        require(all('package:' + p not in packages for p in [PACKAGE, PACKAGE + '.test', 'dev.agentos.app', 'org.fossify.notes']), 'DEDICATED_FRESH_INSTALL')
        component = runner.text('ime_before', prefix + ['shell', 'settings', '--user', '0', 'get', 'secure', 'default_input_method'])
        pkg = ime_component(component)
        require(component in runner.text('ime_list', prefix + ['shell', 'ime', 'list', '-s']).splitlines(), 'CONFIGURED_IME_NOT_LISTED')
        uid = parse_uid(runner.text('ime_uid', prefix + ['shell', 'pm', 'list', 'packages', '--user', '0', '-U', pkg]), pkg)
        report['ime'] = {'componentBefore': component, 'componentAfter': None, 'package': pkg, 'uid': uid, 'uidAfter': None, 'displayId': 0}; save()
        badging = common.parse_internal_badging(runner.text('apk_badging', [aapt, 'dump', 'badging', report['tools']['fixtureApk']]))
        require(badging == {'package': PACKAGE, 'minSdk': 30, 'targetSdk': 35}, 'FIXTURE_APK_IDENTITY')
        report['inputs']['fixtureApk'].update(badging); save()
        installed = True
        result = runner.text('install_fixture', prefix + ['install', '-t', report['tools']['fixtureApk']])
        require(re.search(r'^Success\s*$', result, re.M), 'FIXTURE_INSTALL_RESULT'); installed = True
        paths = runner.text('fixture_paths', prefix + ['shell', 'pm', 'path', PACKAGE]).splitlines()
        require(paths and len(paths) <= 4 and all(re.fullmatch(r'package:/data/app/[A-Za-z0-9_~./=+-]+\.apk', p) for p in paths), 'FIXTURE_INSTALLED_PATHS')
        report['inputs']['fixtureApk']['installedPaths'] = [p[len('package:'):] for p in paths]
        fixture_uid = parse_uid(runner.text('fixture_uid', prefix + ['shell', 'pm', 'list', 'packages', '--user', '0', '-U', PACKAGE]), PACKAGE)
        require(10000 <= fixture_uid < 100000, 'FIXTURE_UID'); report['inputs']['fixtureApk']['uid'] = fixture_uid; save()
        pushed = True
        runner.text('push_probe', prefix + ['push', report['tools']['probeJar'], remote_jar(source)]); pushed = True
        require(runner.text('probe_digest', prefix + ['shell', 'sha256sum', remote_jar(source)]) == sha(jar) + '  ' + remote_jar(source), 'REMOTE_JAR_DIGEST')
        report['stage'] = 'raw_probe'; report['rawAttempted'] = True; save()
        def start_fixture(ready):
            require(not report['fixtureAttempted'], 'FIXTURE_ALREADY_ATTEMPTED')
            report['fixtureAttempted'] = True; save()
            result = runner.text('fixture_start', fixture_command(adb, serial, source))
            require(re.search(r'^Status: ok\s*$', result, re.M)
                    and 'Error:' not in result and 'Warning:' not in result, 'FIXTURE_START_RESULT')
        data, command = runner.call('raw_probe', raw_command(adb, serial, source, report['ime']),
                                    start_fixture, report['ime'])
        report['remoteProcessExit'] = 'NORMAL' if command_ok(command) else 'NONZERO_OR_UNOBSERVED'
        try:
            _, raw = parse_markers(data, source, report['ime']); write_json(directory / 'raw-result.json', raw)
        except Rejected:
            raw = None
        if report['fixtureAttempted']:
            report['stage'] = 'fixture_capture'; save()
            png, shot = runner.call('screenshot', prefix + ['exec-out', 'screencap', '-p'])
            capture['status'] = 'unavailable'
            if command_ok(shot):
                try:
                    capture['file'] = {'path': 'screenshot.png', **png_identity(png)}
                    (directory / 'screenshot.png').write_bytes(png); capture['status'] = 'captured'
                except Rejected: pass
            write_json(directory / 'capture.json', capture)
            data, receipt_command = runner.call('fixture_receipt', prefix + ['shell', 'run-as', PACKAGE, 'cat', 'files/raw-window-fixture.json'])
            if command_ok(receipt_command):
                try:
                    fixture = validate_fixture(decode_json(data), source, fixture_uid)
                    write_json(directory / 'fixture.json', fixture)
                except Rejected: fixture = None
        report['stage'] = 'postcheck'; save()
        report['ime']['componentAfter'] = runner.text('ime_after', prefix + ['shell', 'settings', '--user', '0', 'get', 'secure', 'default_input_method'])
        report['ime']['uidAfter'] = parse_uid(runner.text('ime_uid_after', prefix + ['shell', 'pm', 'list', 'packages', '--user', '0', '-U', pkg]), pkg)
        require(command_ok(command), 'RAW_PROCESS_NOT_NORMAL')
        require(raw is not None, 'RAW_RESULT_INVALID_OR_MISSING')
        require(fixture is not None and fixture_passed(fixture), 'FIXTURE_POSITIVE_CONTROL_NOT_OBSERVED')
        require(report['ime']['componentAfter'] == component and report['ime']['uidAfter'] == uid, 'IME_CONFIGURATION_CHANGED')
        require(raw['process']['fingerprintSha256'] == sha(properties['ro.build.fingerprint'].encode()), 'RAW_FINGERPRINT_BINDING')
        count = positive_callbacks(raw, fixture)
        report['positiveCallbackCount'] = count
        require(raw_passed(raw) and count > 0, 'RAW_POSITIVE_CALLBACK_NOT_OBSERVED')
        require(capture['status'] == 'captured', 'FIXTURE_CAPTURE_UNAVAILABLE')
        report['status'], report['stage'], report['failureReason'] = 'passed', 'complete', None
    except Exception as failure:
        report['status'] = 'failed'
        report['failureReason'] = str(failure) if isinstance(failure, Rejected) else 'HOST_' + type(failure).__name__
    finally:
        report['positiveCallbackCount'] = positive_callbacks(raw, fixture) if raw is not None and fixture is not None else 0
        if serial is not None:
            prefix = [adb, '-s', serial]
            for enabled, key, name, tail in [(installed, 'fixture', 'cleanup_fixture', ['shell', 'am', 'force-stop', '--user', '0', PACKAGE]),
                                           (pushed, 'probeFile', 'cleanup_probe', ['shell', 'rm', '-f', remote_jar(source)])]:
                if enabled:
                    try:
                        _, cleanup = runner.call(name, prefix + tail)
                        report['cleanup'][key] = 'RETURNED_ZERO' if command_ok(cleanup) else 'FAILED_OR_UNOBSERVED'
                    except Exception: report['cleanup'][key] = 'FAILED_OR_UNOBSERVED'
            if report['status'] == 'passed' and any(v != 'RETURNED_ZERO' for v in report['cleanup'].values()):
                report['status'], report['failureReason'] = 'failed', 'HOST_CLEANUP_NOT_NORMAL'
        report['completedAt'] = now(); save()
    validate_evidence(read_payloads(directory), source)
    return report['status'] == 'passed'


def finalize(directory, source, outcome):
    payloads = read_payloads(directory); report = payloads['report.json']
    require(report['source'] == source, 'FINALIZE_SOURCE')
    if report['status'] == 'passed' and outcome != 'success':
        report['status'], report['failureReason'] = 'failed', 'EMULATOR_STEP_' + outcome.upper()
    report['emulatorStepOutcome'] = outcome; report['completedAt'] = report['completedAt'] or now()
    write_json(Path(directory) / 'report.json', report)
    return validate_evidence(read_payloads(directory), source)


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='command', required=True)
    for name in ['prepare', 'run', 'finalize']:
        child = sub.add_parser(name); child.add_argument('--output', type=Path, required=True)
        if name == 'run':
            child.add_argument('--sdk-root', type=Path, required=True)
            child.add_argument('--fixture-apk', type=Path, required=True)
            child.add_argument('--probe-jar', type=Path, required=True)
        if name == 'finalize': child.add_argument('--outcome', choices=['success', 'failure', 'cancelled', 'skipped', 'unknown'], required=True)
    args = parser.parse_args(); source = source_from_environment()
    def cancel(_signum, _frame):
        global CANCELLED
        CANCELLED = True; common.CANCELLED = True
    signal.signal(signal.SIGINT, cancel); signal.signal(signal.SIGTERM, cancel)
    if args.command == 'prepare': prepare(args.output, source)
    elif args.command == 'run': return 0 if run_probe(args, source) else 1
    else: finalize(args.output, source, args.outcome)
    return 0

ADMISSION_IDS = ('class.raw', 'class.handle', 'class.display', 'class.inputConfig', 'constructor.raw',
                 'method.raw.register', 'method.raw.unregister', 'method.raw.callback',
                 'method.handle.getWindowToken', 'field.handle.inputConfig', 'field.handle.frame',
                 'field.handle.touchableRegion', 'field.handle.layoutParamsType', 'field.handle.ownerUid',
                 'field.handle.packageName', 'field.handle.displayId', 'constant.inputConfig.NOT_VISIBLE',
                 'constant.inputConfig.CLONE')
LIMITS = {'admissionMs': 5000, 'observationMs': 12000, 'maxWindows': 256, 'maxCandidateRows': 16,
          'maxBinderIds': 16, 'maxCallbacks': 128, 'maxMarkerBytes': MAX_MARKER}
SAMPLE_ISSUES = {'NULL_ARRAY', 'HANDLES_LIMIT', 'NULL_HANDLE', 'NULL_PACKAGE', 'CANDIDATE_LIMIT',
                 'IDENTITY_LIMIT', 'FIELD_READ_FAILED'}


def validate_sample(sample, raw, kind, source_start, source_end):
    keys = {'kind', 'sequence', 'elapsedRealtimeMs', 'rawWindowCount', 'typeCount', 'displayTypeCount',
            'uidTypeDisplayCount', 'nullPackageCount', 'candidateRowCount', 'nullTokenCount',
            'distinctBinderCount', 'complete', 'issues', 'rows'}
    exact(sample, keys, 'SAMPLE_FIELDS')
    require(sample['kind'] == kind and integer(sample['sequence'], 0 if kind == 'INITIAL' else 1)
            and (kind != 'INITIAL' or sample['sequence'] == 0)
            and integer(sample['elapsedRealtimeMs'], source_start, source_end), 'SAMPLE_IDENTITY')
    counters = ['rawWindowCount', 'typeCount', 'displayTypeCount', 'uidTypeDisplayCount', 'nullPackageCount',
                'candidateRowCount', 'nullTokenCount', 'distinctBinderCount']
    require(all(integer(sample[k], -1, 2**31 - 1) for k in counters), 'SAMPLE_COUNTS')
    issues = sample['issues']; rows = sample['rows']
    require(type(issues) is list and len(issues) <= len(SAMPLE_ISSUES) and len(set(issues)) == len(issues)
            and all(v in SAMPLE_ISSUES for v in issues) and type(sample['complete']) is bool
            and sample['complete'] == (not issues), 'SAMPLE_ISSUES')
    require(type(rows) is list and len(rows) <= 16, 'SAMPLE_ROW_BOUND')
    previous = -1; ids = []
    for row in rows:
        exact(row, {'rowIndex', 'binderId', 'inputConfig', 'frame', 'regionEmpty', 'notVisible', 'isClone',
                    'frameEmpty', 'passesFourPredicates', 'ownerUid', 'layoutParamsType', 'displayId'}, 'ROW_FIELDS')
        require(integer(row['rowIndex'], previous + 1, min(255, sample['rawWindowCount'] - 1)), 'ROW_ORDER')
        previous = row['rowIndex']
        require(integer(row['binderId'], -1, raw['binderIdsAssigned'])
                and integer(row['inputConfig'], -(2**31), 2**31 - 1), 'ROW_VALUES')
        require(row['ownerUid'] == raw['imeUid'] and type(row['ownerUid']) is int
                and row['layoutParamsType'] == 2011 and type(row['layoutParamsType']) is int
                and row['displayId'] == 0 and type(row['displayId']) is int, 'ROW_SELECTOR')
        frame = row['frame']
        require(type(frame) is list and len(frame) == 4 and all(integer(v, -(2**31), 2**31 - 1) for v in frame), 'ROW_FRAME')
        require(all(type(row[k]) is bool for k in ['regionEmpty', 'notVisible', 'isClone', 'frameEmpty', 'passesFourPredicates']), 'ROW_BOOLEAN')
        visible = bool(row['inputConfig'] & 2); clone = bool(row['inputConfig'] & 65536)
        empty = frame[0] >= frame[2] or frame[1] >= frame[3]
        require(row['notVisible'] == visible and row['isClone'] == clone and row['frameEmpty'] == empty
                and row['passesFourPredicates'] == (not visible and not clone and not empty and not row['regionEmpty']), 'ROW_PREDICATES')
        ids.append(row['binderId'])
    if sample['rawWindowCount'] >= 0 and sample['typeCount'] >= 0:
        require(sample['rawWindowCount'] >= sample['typeCount'] >= sample['displayTypeCount']
                >= sample['uidTypeDisplayCount'] >= sample['candidateRowCount'] + sample['nullPackageCount'], 'SAMPLE_SELECTOR_COUNTS')
    if sample['candidateRowCount'] >= 0: require(len(rows) <= sample['candidateRowCount'], 'SAMPLE_ROW_COUNT')
    if sample['complete']:
        require(all(sample[k] >= 0 for k in counters) and sample['rawWindowCount'] <= 256
                and sample['candidateRowCount'] <= 16 and len(rows) == sample['candidateRowCount']
                and sample['nullPackageCount'] == 0 and -1 not in ids
                and sample['nullTokenCount'] == ids.count(0)
                and sample['distinctBinderCount'] == len(set(ids) - {0}), 'SAMPLE_COMPLETE_COUNTS')
    if -1 in ids: require('IDENTITY_LIMIT' in issues, 'SAMPLE_IDENTITY_OVERFLOW')
    if sample['rawWindowCount'] > 256: require('HANDLES_LIMIT' in issues and not rows, 'SAMPLE_HANDLES_OVERFLOW')
    if sample['candidateRowCount'] > 16: require('CANDIDATE_LIMIT' in issues and not rows, 'SAMPLE_CANDIDATE_OVERFLOW')
    return sample


def validate_raw(value, source, ime):
    keys = {'schemaVersion', 'source', 'imePackage', 'imeUid', 'displayId', 'process', 'limits', 'admission',
            'constructionStatus', 'registrationStatus', 'unregistrationStatus', 'resultStatus',
            'constructAttempts', 'registerAttempts', 'unregisterAttempts', 'times', 'collectionFrozen',
            'callbacksSeen', 'callbacksStored', 'callbacksDropped', 'callbacksOutsideWindow',
            'binderIdsAssigned', 'outputTruncated', 'outputOmittedCallbacks', 'initial', 'samples'}
    exact(value, keys, 'RAW_FIELDS')
    require(value['schemaVersion'] == 1 and type(value['schemaVersion']) is int and common.equal(value['source'], source)
            and value['imePackage'] == ime['package'] and value['imeUid'] == ime['uid'] and type(value['imeUid']) is int
            and value['displayId'] == 0 and type(value['displayId']) is int, 'RAW_SOURCE_SELECTOR')
    require(common.equal(value['limits'], LIMITS), 'RAW_LIMITS')
    p = value['process']; exact(p, {'sdk', 'uid', 'startedElapsedMs', 'finishedElapsedMs', 'fingerprintSha256'}, 'RAW_PROCESS')
    require(integer(p['sdk'], 1, 1000) and integer(p['uid'], 0, 2**31 - 1)
            and integer(p['startedElapsedMs']) and integer(p['finishedElapsedMs'], p['startedElapsedMs'])
            and re.fullmatch('[0-9a-f]{64}', str(p['fingerprintSha256'])), 'RAW_PROCESS_VALUES')
    admission = value['admission']; require(type(admission) is list and len(admission) == len(ADMISSION_IDS), 'ADMISSION_COUNT')
    statuses = {'MATCHED', 'NOT_FOUND_OR_FILTERED', 'TYPE_MISMATCH', 'ACCESS_DENIED', 'LINKAGE_ERROR',
                'ERROR', 'DEADLINE_EXCEEDED', 'NOT_ATTEMPTED'}
    for i, entry in enumerate(admission):
        exact(entry, {'id', 'status'}, 'ADMISSION_FIELDS')
        require(entry['id'] == ADMISSION_IDS[i] and entry['status'] in statuses, 'ADMISSION_VALUE')
    status = value['resultStatus']
    require(status in {'RECORDED', 'ENVIRONMENT_REJECTED', 'ADMISSION_FAILED', 'CONSTRUCTION_FAILED',
                      'REGISTRATION_FAILED', 'OBSERVATION_INTERRUPTED', 'EXECUTION_FAILED', 'CLEANUP_FAILED', 'INCOMPLETE'}, 'RAW_RESULT_STATUS')
    for key in ['constructAttempts', 'registerAttempts', 'unregisterAttempts']:
        require(integer(value[key], 0, 1), 'RAW_ATTEMPT_BUDGET')
    c, r, u = value['constructionStatus'], value['registrationStatus'], value['unregistrationStatus']
    require(c in {'NOT_ATTEMPTED', 'CONSTRUCTED', 'FAILED'} and r in {'NOT_ATTEMPTED', 'REGISTERED', 'FAILED'}
            and u in {'NOT_ATTEMPTED', 'RETURNED', 'FAILED'}, 'RAW_STAGE_STATUS')
    require(value['constructAttempts'] == (c != 'NOT_ATTEMPTED')
            and value['registerAttempts'] == (r != 'NOT_ATTEMPTED')
            and value['unregisterAttempts'] == (u != 'NOT_ATTEMPTED'), 'RAW_ATTEMPT_STATUS')
    if value['constructAttempts']:
        require(all(a['status'] == 'MATCHED' for a in admission) and p['sdk'] == 37 and p['uid'] == 2000, 'RAW_ADMISSION_BEFORE_CONSTRUCT')
    if value['registerAttempts']: require(c == 'CONSTRUCTED', 'RAW_CONSTRUCT_BEFORE_REGISTER')
    if value['unregisterAttempts']: require(value['registerAttempts'] == 1, 'RAW_REGISTER_BEFORE_UNREGISTER')
    times = value['times']; exact(times, {'constructedElapsedMs', 'registeredElapsedMs', 'readyElapsedMs',
             'observationDeadlineElapsedMs', 'freezeElapsedMs', 'unregisteredElapsedMs'}, 'RAW_TIMES')
    for key, tick in times.items():
        if tick is not None: require(integer(tick, p['startedElapsedMs'], p['finishedElapsedMs'] if key != 'observationDeadlineElapsedMs' else 2**63 - 1), 'RAW_TIME_VALUE')
    require(value['collectionFrozen'] is True and times['freezeElapsedMs'] is not None, 'RAW_FREEZE')
    for state, good, key in [(c, 'CONSTRUCTED', 'constructedElapsedMs'), (r, 'REGISTERED', 'registeredElapsedMs'), (u, 'RETURNED', 'unregisteredElapsedMs')]:
        require((times[key] is not None) == (state == good), 'RAW_STAGE_TIME')
    if r == 'REGISTERED':
        require(times['constructedElapsedMs'] <= times['registeredElapsedMs'] <= times['freezeElapsedMs'], 'RAW_REGISTER_ORDER')
    if times['readyElapsedMs'] is not None:
        require(r == 'REGISTERED' and times['registeredElapsedMs'] <= times['readyElapsedMs'] <= times['freezeElapsedMs']
                and times['observationDeadlineElapsedMs'] == times['readyElapsedMs'] + 12000, 'RAW_READY_ORDER')
    else: require(times['observationDeadlineElapsedMs'] is None, 'RAW_NO_DEADLINE')
    if u == 'RETURNED': require(times['unregisteredElapsedMs'] >= times['freezeElapsedMs'], 'RAW_FREEZE_BEFORE_UNREGISTER')
    require(all(integer(value[k], 0, 2**31 - 1) for k in ['callbacksSeen', 'callbacksStored', 'callbacksDropped', 'callbacksOutsideWindow'])
            and value['callbacksStored'] <= 128 and value['callbacksSeen'] == value['callbacksStored'] + value['callbacksDropped'] + value['callbacksOutsideWindow']
            and integer(value['binderIdsAssigned'], 0, 16) and integer(value['outputOmittedCallbacks'], 0, value['callbacksStored'])
            and type(value['outputTruncated']) is bool and value['outputTruncated'] == (value['outputOmittedCallbacks'] > 0), 'RAW_COUNTERS')
    require(type(value['samples']) is list and len(value['samples']) == value['callbacksStored'] - value['outputOmittedCallbacks'], 'RAW_SAMPLE_COUNT')
    initial = value['initial']
    if initial is not None: validate_sample(initial, value, 'INITIAL', p['startedElapsedMs'], times['freezeElapsedMs'])
    previous_sequence = 0
    for sample in value['samples']:
        validate_sample(sample, value, 'CALLBACK', p['startedElapsedMs'], times['freezeElapsedMs'])
        require(previous_sequence < sample['sequence'] <= value['callbacksSeen'], 'RAW_SAMPLE_SEQUENCE')
        if times['observationDeadlineElapsedMs'] is not None:
            require(sample['elapsedRealtimeMs'] < times['observationDeadlineElapsedMs'], 'RAW_SAMPLE_AFTER_DEADLINE')
        previous_sequence = sample['sequence']
    if status == 'RECORDED':
        require(c == 'CONSTRUCTED' and r == 'REGISTERED' and u == 'RETURNED'
                and times['readyElapsedMs'] is not None and times['freezeElapsedMs'] >= times['observationDeadlineElapsedMs']
                and initial is not None and initial['complete'] and value['samples']
                and all(s['complete'] for s in value['samples']) and value['callbacksDropped'] == 0
                and not value['outputTruncated'], 'RAW_RECORDED_COMPLETE')
    return value


def raw_passed(raw):
    return raw['resultStatus'] == 'RECORDED' and raw['process']['sdk'] == 37 and raw['process']['uid'] == 2000


def positive_callbacks(raw, fixture):
    requested = fixture['showRequestedElapsedMs']
    if requested is None: return 0
    return sum(s['complete'] and s['elapsedRealtimeMs'] >= requested and s['candidateRowCount'] > 0
               and s['nullTokenCount'] == 0 and s['distinctBinderCount'] == 1
               and any(row['passesFourPredicates'] for row in s['rows']) for s in raw['samples'])


def command_data(item):
    try: return base64.b64decode(item['outputBase64'], validate=True)
    except (ValueError, TypeError): raise Rejected('COMMAND_BASE64') from None


def command_text(item):
    try: return command_data(item).decode('utf-8').strip()
    except UnicodeError: raise Rejected('COMMAND_UTF8') from None


def validate_command_argv(item, report, source, commands):
    name, argv = item['id'], item['argv']; tools = report['tools']
    require(tools is not None, 'COMMAND_TOOLS')
    adb = tools['adb']
    if name == 'devices': require(argv == [adb, 'devices'], 'DEVICES_ARGV'); return
    if name == 'apk_badging':
        require(argv == [tools['aapt'], 'dump', 'badging', tools['fixtureApk']], 'BADGING_ARGV'); return
    require(len(argv) >= 4 and argv[0] == adb and argv[1] == '-s'
            and re.fullmatch(r'emulator-[0-9]{4,5}', argv[2]), 'COMMAND_SERIAL')
    serial = argv[2]
    if 'devices' in commands and command_ok(commands['devices']):
        require(serial == devices.select_emulator(command_text(commands['devices'])), 'COMMAND_SERIAL_SOURCE')
    if report['device'] is not None: require(serial == report['device']['serial'], 'COMMAND_SERIAL_REPORT')
    if name.startswith('property_'): tail = ['shell', 'getprop', name[len('property_'):]]
    elif name == 'extra_vm_options': tail = ['shell', 'getprop', 'dalvik.vm.extra-opts']
    elif name == 'current_user': tail = ['shell', 'am', 'get-current-user']
    elif name == 'shell_uid': tail = ['shell', 'id', '-u']
    elif name == 'timeout_available': tail = ['shell', 'test', '-x', '/system/bin/timeout']
    elif name == 'installed_packages': tail = ['shell', 'pm', 'list', 'packages']
    elif name in {'ime_before', 'ime_after'}: tail = ['shell', 'settings', '--user', '0', 'get', 'secure', 'default_input_method']
    elif name == 'ime_list': tail = ['shell', 'ime', 'list', '-s']
    elif name in {'ime_uid', 'ime_uid_after'}:
        require('ime_before' in commands, 'IME_QUERY_SOURCE')
        pkg = ime_component(command_text(commands['ime_before']))
        tail = ['shell', 'pm', 'list', 'packages', '--user', '0', '-U', pkg]
    elif name == 'fixture_uid': tail = ['shell', 'pm', 'list', 'packages', '--user', '0', '-U', PACKAGE]
    elif name == 'install_fixture': tail = ['install', '-t', tools['fixtureApk']]
    elif name == 'fixture_paths': tail = ['shell', 'pm', 'path', PACKAGE]
    elif name == 'push_probe': tail = ['push', tools['probeJar'], remote_jar(source)]
    elif name == 'probe_digest': tail = ['shell', 'sha256sum', remote_jar(source)]
    elif name == 'raw_probe':
        require(report['ime'] is not None and argv == raw_command(adb, serial, source, report['ime']), 'RAW_ARGV'); return
    elif name == 'fixture_start':
        require(argv == fixture_command(adb, serial, source), 'FIXTURE_ARGV'); return
    elif name == 'screenshot': tail = ['exec-out', 'screencap', '-p']
    elif name == 'fixture_receipt': tail = ['shell', 'run-as', PACKAGE, 'cat', 'files/raw-window-fixture.json']
    elif name == 'cleanup_fixture': tail = ['shell', 'am', 'force-stop', '--user', '0', PACKAGE]
    elif name == 'cleanup_probe': tail = ['shell', 'rm', '-f', remote_jar(source)]
    else: raise Rejected('COMMAND_UNKNOWN')
    require(argv[3:] == tail, 'COMMAND_SCOPE')


def validate_commands(value, report, source):
    exact(value, {'schemaVersion', 'kind', 'source', 'commands'}, 'COMMANDS_FIELDS')
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1
            and value['kind'] == KIND + '-commands' and common.equal(value['source'], source), 'COMMANDS_IDENTITY')
    entries = value['commands']; require(type(entries) is list and len(entries) <= MAX_COMMANDS, 'COMMANDS_BOUND')
    by_id = {}; total = 0; previous_position = -1; previous_start = common.timestamp(report['createdAt'])
    end = common.timestamp(report['completedAt']) if report['completedAt'] else None
    for index, item in enumerate(entries):
        exact(item, {'ordinal', 'id', 'argv', 'timeoutSeconds', 'byteLimit', 'startedAt', 'endedAt',
                     'elapsedMillis', 'status', 'exitCode', 'exceptionType', 'bytes', 'sha256', 'outputBase64'}, 'COMMAND_FIELDS')
        require(integer(item['ordinal'], 1, MAX_COMMANDS) and item['ordinal'] == index + 1
                and item['id'] in COMMAND_IDS and item['id'] not in by_id, 'COMMAND_ID')
        position = COMMAND_IDS.index(item['id']); require(position > previous_position, 'COMMAND_ORDER'); previous_position = position
        require(type(item['argv']) is list and 1 <= len(item['argv']) <= 32
                and all(isinstance(v, str) and 0 < len(v) <= 1024 and '\x00' not in v for v in item['argv']), 'COMMAND_ARGV_SHAPE')
        seconds, limit = command_limits(item['id'])
        require(type(item['timeoutSeconds']) is int and item['timeoutSeconds'] == seconds
                and type(item['byteLimit']) is int and item['byteLimit'] == limit, 'COMMAND_LIMITS')
        require(item['status'] in {'running', 'completed', 'timed_out', 'output_limit', 'cancelled', 'exception'}, 'COMMAND_STATUS')
        require(item['exitCode'] is None or integer(item['exitCode'], -(2**31), 2**31 - 1), 'COMMAND_EXIT')
        require((item['status'] == 'exception') == (item['exceptionType'] is not None)
                and (item['exceptionType'] is None or re.fullmatch('[A-Za-z][A-Za-z0-9_]{0,79}', str(item['exceptionType']))), 'COMMAND_EXCEPTION')
        require(integer(item['elapsedMillis']), 'COMMAND_ELAPSED')
        started = common.timestamp(item['startedAt']); require(started >= previous_start, 'COMMAND_START_ORDER'); previous_start = started
        if item['endedAt'] is None: require(item['status'] == 'running' and report['status'] != 'passed', 'COMMAND_UNFINISHED')
        else:
            ended = common.timestamp(item['endedAt']); require(started <= ended and (end is None or ended <= end), 'COMMAND_END_ORDER')
        require(isinstance(item['outputBase64'], str) and len(item['outputBase64']) <= 4 * ((limit + 2) // 3), 'COMMAND_OUTPUT_BOUND')
        data = command_data(item); require(len(data) <= limit and integer(item['bytes'], 0, limit) and identity(data) == {'bytes': item['bytes'], 'sha256': item['sha256']}, 'COMMAND_BYTES')
        total += len(data); by_id[item['id']] = item
        validate_command_argv(item, report, source, by_id)
    require(total <= MAX_TOTAL_OUTPUT, 'COMMAND_TOTAL')
    for index, item in enumerate(entries):
        started = common.timestamp(item['startedAt'])
        for earlier in entries[:index]:
            if earlier['endedAt'] is not None and started < common.timestamp(earlier['endedAt']):
                require(earlier['id'] == 'raw_probe' and item['id'] == 'fixture_start'
                        and (item['endedAt'] is None or common.timestamp(item['endedAt']) <= common.timestamp(earlier['endedAt'])), 'COMMAND_UNEXPECTED_OVERLAP')
    return by_id


def validate_passed_setup(commands, report):
    require(tuple(commands) == COMMAND_IDS and all(command_ok(c) for c in commands.values()), 'COMPLETE_COMMAND_SEQUENCE')
    output = lambda name: command_text(commands[name])
    device = report['device']; require(device is not None, 'PASSED_DEVICE')
    require(devices.select_emulator(output('devices')) == device['serial'], 'DEVICE_RECEIPT')
    require(all(output('property_' + p) == device['properties'][p] for p in PROPERTIES), 'PROPERTY_RECEIPT')
    props = device['properties']; require(props['ro.build.version.sdk'] == '37' and props['ro.product.cpu.abi'] == 'x86_64'
            and props['sys.boot_completed'] == '1' and '1' in [props['ro.kernel.qemu'], props['ro.boot.qemu']], 'DEVICE_SCOPE')
    require(output('extra_vm_options') == '' and output('current_user') == '0' and output('shell_uid') == '2000', 'ENVIRONMENT_RECEIPT')
    installed = output('installed_packages').splitlines()
    require(all('package:' + p not in installed for p in [PACKAGE, PACKAGE + '.test', 'dev.agentos.app', 'org.fossify.notes']), 'FRESH_INSTALL_RECEIPT')
    ime = report['ime']; require(ime is not None, 'IME_RECEIPT')
    require(output('ime_before') == output('ime_after') == ime['componentBefore'] == ime['componentAfter']
            and ime_component(ime['componentBefore']) == ime['package']
            and ime['componentBefore'] in output('ime_list').splitlines()
            and parse_uid(output('ime_uid'), ime['package']) == parse_uid(output('ime_uid_after'), ime['package']) == ime['uid'] == ime['uidAfter'], 'IME_STABILITY_RECEIPT')
    apk = report['inputs']['fixtureApk']; badging = common.parse_internal_badging(output('apk_badging'))
    require(badging == {'package': PACKAGE, 'minSdk': 30, 'targetSdk': 35}
            and all(apk[k] == v for k, v in badging.items()), 'APK_METADATA_RECEIPT')
    require(re.search(r'^Success\s*$', output('install_fixture'), re.M)
            and output('fixture_paths').splitlines() == ['package:' + p for p in apk['installedPaths']]
            and parse_uid(output('fixture_uid'), PACKAGE) == apk['uid'], 'APK_INSTALL_RECEIPT')
    require(output('probe_digest') == report['inputs']['probeJar']['sha256'] + '  ' + remote_jar(report['source']), 'JAR_REMOTE_RECEIPT')
    require(re.search(r'^Status: ok\s*$', output('fixture_start'), re.M)
            and 'Error:' not in output('fixture_start') and 'Warning:' not in output('fixture_start'), 'FIXTURE_START_RECEIPT')
    require(report['rawAttempted'] is True and report['fixtureAttempted'] is True and report['remoteProcessExit'] == 'NORMAL'
            and report['cleanup'] == {'fixture': 'RETURNED_ZERO', 'probeFile': 'RETURNED_ZERO'}, 'PASSED_EXECUTION')


def validate_evidence(payloads, source=None):
    require(type(payloads) is dict and set(payloads) <= FILES
            and {'report.json', 'commands.json', 'capture.json'} <= set(payloads), 'PAYLOAD_FILESET')
    report = payloads['report.json']
    exact(report, {'schemaVersion', 'kind', 'source', 'apiLevel', 'matrixLabel', 'scope', 'sourceFiles',
                  'status', 'stage', 'failureReason', 'createdAt', 'completedAt', 'emulatorStepOutcome',
                  'device', 'tools', 'inputs', 'ime', 'rawAttempted', 'fixtureAttempted',
                  'positiveCallbackCount', 'remoteProcessExit', 'cleanup', 'emulatorTeardown'}, 'REPORT_FIELDS')
    validate_source(report['source']); source = report['source'] if source is None else validate_source(source)
    require(report['source'] == source and report['schemaVersion'] == 1 and type(report['schemaVersion']) is int
            and report['kind'] == KIND and report['apiLevel'] == 37 and type(report['apiLevel']) is int
            and report['matrixLabel'] == LABEL and common.equal(report['scope'], SCOPE), 'REPORT_CONTRACT')
    require(report['status'] in {'passed', 'failed', 'unverified'}
            and report['stage'] in {'prepared', 'inputs', 'raw_probe', 'fixture_capture', 'postcheck', 'complete'}
            and (report['failureReason'] is None or (isinstance(report['failureReason'], str)
                 and re.fullmatch('[A-Z][A-Za-z0-9_]{0,127}', report['failureReason'])))
            and report['emulatorStepOutcome'] in {None, 'success', 'failure', 'cancelled', 'skipped', 'unknown'}, 'REPORT_STATUS')
    require(type(report['rawAttempted']) is bool and type(report['fixtureAttempted']) is bool
            and integer(report['positiveCallbackCount'], 0, 128)
            and report['remoteProcessExit'] in {'NOT_OBSERVED', 'NORMAL', 'NONZERO_OR_UNOBSERVED'}
            and report['emulatorTeardown'] == 'WORKFLOW_RESPONSIBILITY_NOT_OBSERVED', 'REPORT_EXECUTION')
    exact(report['cleanup'], {'fixture', 'probeFile'}, 'CLEANUP_FIELDS')
    require(all(v in {'NOT_ATTEMPTED', 'RETURNED_ZERO', 'FAILED_OR_UNOBSERVED'} for v in report['cleanup'].values()), 'CLEANUP_VALUES')
    start = common.timestamp(report['createdAt'])
    if report['completedAt'] is not None: require(common.timestamp(report['completedAt']) >= start, 'REPORT_TIME')
    require(type(report['sourceFiles']) is dict and 10 <= len(report['sourceFiles']) <= 64, 'SOURCE_FILES')
    require(set(SOURCE_PATHS) <= set(report['sourceFiles']), 'SOURCE_PATHS')
    for path, ident in report['sourceFiles'].items():
        require(isinstance(path, str) and re.fullmatch('[A-Za-z0-9_./-]+', path)
                and '..' not in Path(path).parts and not path.startswith('/'), 'SOURCE_PATH')
        common.validate_identity(ident)
    if report['tools'] is not None:
        exact(report['tools'], {'adb', 'aapt', 'fixtureApk', 'probeJar'}, 'TOOLS_FIELDS')
        require(all(isinstance(v, str) and 0 < len(v) <= 1024 and v.startswith('/') and '\x00' not in v
                    for v in report['tools'].values()) and Path(report['tools']['adb']).name == 'adb'
                and Path(report['tools']['aapt']).name == 'aapt', 'TOOLS_VALUES')
    if report['device'] is not None:
        exact(report['device'], {'serial', 'properties'}, 'DEVICE_FIELDS')
        require(re.fullmatch(r'emulator-[0-9]{4,5}', str(report['device']['serial'])), 'DEVICE_SERIAL')
        exact(report['device']['properties'], PROPERTIES, 'PROPERTY_FIELDS')
        require(all(isinstance(v, str) and len(v) <= 512 for v in report['device']['properties'].values()), 'PROPERTY_VALUES')
    if report['ime'] is not None:
        ime = report['ime']; exact(ime, {'componentBefore', 'componentAfter', 'package', 'uid', 'uidAfter', 'displayId'}, 'IME_FIELDS')
        require(ime_component(ime['componentBefore']) == ime['package'] and integer(ime['uid'], 1, 99999)
                and (ime['uidAfter'] is None or integer(ime['uidAfter'], 1, 99999))
                and ime['displayId'] == 0 and type(ime['displayId']) is int, 'IME_VALUES')
        if ime['componentAfter'] is not None: require(isinstance(ime['componentAfter'], str) and len(ime['componentAfter']) <= 320, 'IME_AFTER')
    require(type(report['inputs']) is dict and set(report['inputs']) <= {'fixtureApk', 'probeJar'}, 'INPUTS_FIELDS')
    for name, ident in report['inputs'].items():
        require(type(ident) is dict and {'bytes', 'sha256'} <= set(ident), 'INPUT_IDENTITY')
        common.validate_identity({k: ident[k] for k in ['bytes', 'sha256']}, 32 * 1024 * 1024 if name == 'fixtureApk' else MAX_JAR)
    commands = validate_commands(payloads['commands.json'], report, source)
    capture = payloads['capture.json']; exact(capture, {'schemaVersion', 'kind', 'source', 'apiLevel', 'status', 'file', 'scope', 'secureWindowFlagsChanged'}, 'CAPTURE_FIELDS')
    expected = empty_capture(source)
    require(all(capture[k] == expected[k] and type(capture[k]) == type(expected[k]) for k in expected if k not in {'status', 'file'})
            and capture['status'] in {'not_attempted', 'unavailable', 'captured'}, 'CAPTURE_CONTRACT')
    if capture['status'] == 'captured':
        require('screenshot.png' in payloads and 'screenshot' in commands and command_ok(commands['screenshot']), 'CAPTURE_ORIGINAL_MISSING')
        png = payloads['screenshot.png']; require(png == command_data(commands['screenshot'])
                and common.equal(capture['file'], {'path': 'screenshot.png', **png_identity(png)}), 'CAPTURE_BYTES')
    else: require(capture['file'] is None and 'screenshot.png' not in payloads, 'CAPTURE_UNAVAILABLE')
    require(('probe.jar' in payloads) == ('probe-build.json' in payloads), 'BUILD_FILES')
    if 'probe.jar' in payloads:
        require('probeJar' in report['inputs'] and identity(payloads['probe.jar']) == report['inputs']['probeJar'], 'JAR_BYTES')
        validate_build(payloads['probe-build.json'], payloads['probe.jar'], report['sourceFiles'])
    raw = None; ready = None; fixture = None
    if 'raw-result.json' in payloads:
        require('raw_probe' in commands and report['ime'] is not None, 'RAW_ORIGINAL_MISSING')
        ready, raw = parse_markers(command_data(commands['raw_probe']), source, report['ime'])
        require(common.equal(raw, payloads['raw-result.json']), 'RAW_DERIVED_MISMATCH')
    if 'fixture_start' in commands:
        require('raw_probe' in commands and report['ime'] is not None, 'FIXTURE_WITHOUT_PROBE')
        raw_lines = command_data(commands['raw_probe']).splitlines()
        ready_lines = [line for line in raw_lines if line.startswith(READY.encode())]
        require(len(ready_lines) == 1, 'FIXTURE_WITHOUT_UNIQUE_READY')
        ready = validate_ready(decode_json(ready_lines[0][len(READY):]), source, report['ime'])
        require(common.timestamp(commands['raw_probe']['startedAt']) <= common.timestamp(commands['fixture_start']['startedAt']), 'FIXTURE_BEFORE_PROBE')
    if 'fixture.json' in payloads:
        require('fixture_receipt' in commands and command_ok(commands['fixture_receipt'])
                and 'uid' in report['inputs'].get('fixtureApk', {}), 'FIXTURE_RECEIPT_ORIGINAL')
        fixture = validate_fixture(decode_json(command_data(commands['fixture_receipt'])), source, report['inputs']['fixtureApk']['uid'])
        require(common.equal(fixture, payloads['fixture.json']), 'FIXTURE_DERIVED_MISMATCH')
    require(report['positiveCallbackCount'] == (positive_callbacks(raw, fixture) if raw is not None and fixture is not None else 0), 'POSITIVE_COUNT_DERIVATION')
    if report['status'] == 'passed':
        require(report['completedAt'] is not None and report['stage'] == 'complete' and report['failureReason'] is None
                and report['emulatorStepOutcome'] in {None, 'success'}, 'PASSED_REPORT_STATUS')
        validate_passed_setup(commands, report)
        require('probe.jar' in payloads and raw is not None and ready is not None and fixture is not None
                and raw_passed(raw) and fixture_passed(fixture) and capture['status'] == 'captured'
                and raw['process']['finishedElapsedMs'] - raw['process']['startedElapsedMs'] < 40000, 'PASSED_PROOF')
        require(raw['process']['fingerprintSha256'] == sha(report['device']['properties']['ro.build.fingerprint'].encode()), 'PASSED_FINGERPRINT')
        require(raw['times']['readyElapsedMs'] <= fixture['startedElapsedMs'] <= fixture['showRequestedElapsedMs']
                <= fixture['firstImeVisibleElapsedMs'] <= raw['process']['finishedElapsedMs'], 'PASSED_STIMULUS_WINDOW')
        require(report['positiveCallbackCount'] == positive_callbacks(raw, fixture) > 0, 'PASSED_POSITIVE_CALLBACK')
    return report



def validate_build(value, jar, sources):
    keys = {'schemaVersion', 'kind', 'jarBytes', 'jarSha256', 'classesDexBytes', 'classesDexSha256',
            'packagedClasses', 'compileOnlyStubClasses', 'sourceFiles', 'androidJar', 'javacVersion',
            'd8Version', 'commands', 'runtimeHiddenPolicyMutation', 'compileOnlyStubsPackaged'}
    exact(value, keys, 'BUILD_FIELDS')
    require(value['schemaVersion'] == 1 and type(value['schemaVersion']) is int
            and value['kind'] == 'window-infos-raw-dex-build'
            and value['runtimeHiddenPolicyMutation'] is False and value['compileOnlyStubsPackaged'] is False, 'BUILD_SCOPE')
    dex_identity = validate_jar(jar)
    require(type(value['jarBytes']) is int and value['jarBytes'] == len(jar) and value['jarSha256'] == sha(jar)
            and type(value['classesDexBytes']) is int and value['classesDexBytes'] == dex_identity['bytes']
            and value['classesDexSha256'] == dex_identity['sha256'], 'BUILD_BINARY_BINDING')
    with zipfile.ZipFile(io.BytesIO(jar)) as archive: dex = archive.read('classes.dex')
    require(len(dex) >= 112 and dex[12:32] == hashlib.sha1(dex[32:]).digest()
            and struct.unpack_from('<I', dex, 8)[0] == zlib.adler32(dex[12:]), 'DEX_CHECKSUM')
    try: classes = dex_build.dex_classes(dex)
    except (ValueError, UnicodeError, struct.error): raise Rejected('DEX_DEFINITIONS') from None
    require(value['packagedClasses'] == classes and {MAIN_CLASS, 'dev.agentos.raw.RawCollector', 'dev.agentos.raw.RawSession', 'dev.agentos.raw.RawListenerSession'} <= set(classes), 'BUILD_PACKAGED_CLASSES')
    require(value['compileOnlyStubClasses'] == ['android.view.InputWindowHandle', 'android.window.WindowInfosListener',
                                              'android.window.WindowInfosListener$DisplayInfo'], 'BUILD_COMPILE_ONLY_STUBS')
    expected = sorted(name for name in sources if name.startswith(('tools/window-infos-raw/src/', 'tools/window-infos-raw/stubs/'))
                      or name == 'scripts/build_window_infos_raw.py')
    require(type(value['sourceFiles']) is list and len(value['sourceFiles']) == len(expected), 'BUILD_SOURCE_COUNT')
    observed = {}
    for row in value['sourceFiles']:
        exact(row, {'path', 'bytes', 'sha256'}, 'BUILD_SOURCE_FIELDS')
        require(row['path'] in expected and row['path'] not in observed
                and common.equal({'bytes': row['bytes'], 'sha256': row['sha256']}, sources[row['path']]), 'BUILD_SOURCE_BINDING')
        observed[row['path']] = row
    require(sorted(observed) == expected, 'BUILD_SOURCE_CATALOG')
    exact(value['androidJar'], {'api', 'bytes', 'sha256'}, 'BUILD_ANDROID_JAR')
    require(type(value['androidJar']['api']) is int and value['androidJar']['api'] == 35, 'BUILD_ANDROID_API')
    common.validate_identity({k: value['androidJar'][k] for k in ['bytes', 'sha256']}, 64 * 1024 * 1024)
    require(isinstance(value['javacVersion'], str) and re.fullmatch(r'javac 17\.[0-9A-Za-z.+_-]{1,80}', value['javacVersion'])
            and isinstance(value['d8Version'], str) and 0 < len(value['d8Version']) <= 1024, 'BUILD_TOOL_VERSIONS')
    commands = value['commands']; require(type(commands) is list and len(commands) == 3, 'BUILD_COMMAND_COUNT')
    for command in commands:
        require(type(command) is list and 5 <= len(command) <= 24
                and all(isinstance(v, str) and 0 < len(v) <= 1024 and '\x00' not in v for v in command), 'BUILD_COMMAND_ARGV')
    require(all(Path(commands[n][0]).name == 'javac' and commands[n][1:5] == ['--release', '8', '-encoding', 'UTF-8']
                and commands[n][5] == '-cp' and commands[n][7] == '-d' for n in [0, 1]), 'BUILD_JAVAC_ARGS')
    require(sorted(p.rsplit('/tools/window-infos-raw/', 1)[-1] for p in commands[0][9:])
            == sorted(p.split('tools/window-infos-raw/', 1)[1] for p in expected if '/stubs/' in p), 'BUILD_STUB_INPUTS')
    require(sorted(p.rsplit('/tools/window-infos-raw/', 1)[-1] for p in commands[1][9:])
            == sorted(p.split('tools/window-infos-raw/', 1)[1] for p in expected if '/src/' in p), 'BUILD_OWNED_INPUTS')
    require(Path(commands[2][0]).name == 'd8' and commands[2][1] == '--lib'
            and commands[2][3:6] == ['--min-api', '30', '--output'] and len(commands[2]) == 8
            and Path(commands[2][-1]).name == 'owned-classes.jar', 'BUILD_D8_ARGS')
    return value


# A real PNG must have verified chunks, a complete zlib stream and valid scanline sizes.
# This is byte-format verification, not an assertion about the meaning of its pixels.
def png_identity(data):
    require(type(data) is bytes and 57 <= len(data) <= MAX_PNG and data[:8] == b'\x89PNG\r\n\x1a\n', 'SCREENSHOT_PNG')
    offset = 8; chunks = []; compressed = bytearray(); width = height = channels = None
    while offset < len(data):
        require(offset + 12 <= len(data), 'PNG_CHUNK_HEADER')
        length = int.from_bytes(data[offset:offset + 4], 'big'); kind = data[offset + 4:offset + 8]
        end = offset + 12 + length; require(end <= len(data), 'PNG_CHUNK_BOUND')
        body = data[offset + 8:offset + 8 + length]; crc = int.from_bytes(data[end - 4:end], 'big')
        require(zlib.crc32(kind + body) == crc, 'PNG_CHUNK_CRC'); chunks.append(kind)
        if kind == b'IHDR':
            require(len(chunks) == 1 and length == 13, 'PNG_IHDR')
            width, height, depth, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', body)
            require(1 <= width <= 4096 and 1 <= height <= 4096 and depth == 8 and color in {2, 6}
                    and compression == filtering == interlace == 0, 'PNG_LAYOUT'); channels = 3 if color == 2 else 4
        elif kind == b'IDAT': compressed.extend(body)
        elif kind == b'IEND': require(length == 0 and end == len(data), 'PNG_IEND')
        offset = end
    require(chunks[0] == b'IHDR' and chunks[-1] == b'IEND' and chunks.count(b'IHDR') == chunks.count(b'IEND') == 1
            and b'IDAT' in chunks and channels is not None, 'PNG_REQUIRED_CHUNKS')
    expected = (width * channels + 1) * height; require(expected <= 32 * 1024 * 1024, 'PNG_DECODE_LIMIT')
    decoder = zlib.decompressobj()
    try: pixels = decoder.decompress(bytes(compressed), expected + 1)
    except zlib.error: raise Rejected('PNG_ZLIB') from None
    require(len(pixels) == expected and decoder.eof and not decoder.unused_data and not decoder.unconsumed_tail,
            'PNG_INCOMPLETE_OR_EXCESS_PIXELS')
    require(all(pixels[n * (width * channels + 1)] <= 4 for n in range(height)), 'PNG_FILTER')
    return {**identity(data), 'width': width, 'height': height}


if __name__ == '__main__':
    raise SystemExit(main())
