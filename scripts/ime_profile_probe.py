#!/usr/bin/env python3
"""One short, nonvisual method-trace capability probe on a dedicated API37 AVD.

This is not an A/B run, a caller diagnosis, or a timing-equivalent observation.
No APK, setting, permission or system property is changed by this script.
"""
import argparse
import base64
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import selectors
import shlex
import signal
import stat
import subprocess
import time

import ime_method_trace as method_trace

KIND = 'ime-profile-probe'
WORKFLOW = 'ime-profile-probe.yml'
REPOSITORY = 'ecojust/Agent-OS'
PACKAGE = 'com.android.inputmethod.latin'
API = 37
MAX_TRACE_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_JSON_BYTES = 12 * 1024 * 1024
MAX_REPORT_BYTES = 4 * 1024 * 1024
MAX_COMMAND_OUTPUT = 2 * 1024 * 1024
MAX_TOTAL_OUTPUT = 8 * 1024 * 1024
MAX_COMMANDS = 32
HOLD_SECONDS = 1
ABSENCE_ADMISSION_SECONDS = 20
ABSENCE_COMMAND_SECONDS = 5
ABSENCE_CLEANUP_RESERVE_SECONDS = 1
ABSENCE_COMMAND_IDS = ('absence_ps', 'absence_input_method', 'absence_pid_after')
CANCELLED = False
FILES = {'report.json', 'commands.json', 'method.trace'}
SOURCE_PATHS = ('scripts/ime_profile_probe.py', 'scripts/ime_method_trace.py',
                'scripts/ime_profile_archive.py', 'scripts/configure_emulator_phone.py',
                '.github/workflows/ime-profile-probe.yml')
PROFILE_COMMAND_IDS = ('devices', 'api', 'qemu', 'shell_uid', 'current_user', 'debuggable',
               'fingerprint', 'buffer_property', 'shell_package', 'default_ime',
               'ime_uid', 'ime_package', 'ime_pid', 'target_stat', 'target_status', 'target_cmdline',
               'slot_before', 'path_absent', 'profile_start', 'slot_started',
               'stop_stat', 'stop_cmdline', 'slot_before_stop', 'profile_stop',
               'slot_after_stop', 'size_poll_0', 'size_poll_1', 'size_poll_2',
               'trace_pull', 'trace_digest', 'cleanup_trace', 'cleanup_absent')
_PID_POSITION = PROFILE_COMMAND_IDS.index('ime_pid')
COMMAND_IDS = (PROFILE_COMMAND_IDS[:_PID_POSITION + 1] + ABSENCE_COMMAND_IDS
               + PROFILE_COMMAND_IDS[_PID_POSITION + 1:])
START_PREFLIGHT_IDS = PROFILE_COMMAND_IDS[:PROFILE_COMMAND_IDS.index('profile_start')]
SCOPE = {'diagnosticOnly': True, 'nonvisual': True, 'originalNotesCasesRerun': 0,
         'comparisonArmsRun': 0, 'showRequests': 0, 'apkInstalledOrModified': False,
         'rootRequested': False, 'settingsWritten': False, 'propertiesWritten': False,
         'permissionsChanged': False, 'startLimit': 1, 'stopLimit': 1,
         'samplingInterval': 0, 'streaming': False, 'profilerFlags': 0,
         'holdAfterOwnedSlotSeconds': HOLD_SECONDS, 'maxCommands': MAX_COMMANDS,
         'traceFileByteLimit': MAX_TRACE_BYTES, 'artifactByteLimit': MAX_TOTAL_BYTES,
         'profilerMayPauseThreadsAndChangeJitState': True,
         'stopDoesNotRestorePriorCompiledCode': True,
         'methodTraceThreadIdsAreNotClaimedLinuxTids': True,
         'methodTraceClockNotMappedToBoottime': True,
         'callerIdentityEstablished': False, 'longWindowCapacityEstablished': False,
         'exclusiveSyntheticAvdRequired': True, 'profileOwnershipChecksAtomic': False,
         'stopTargetRecheckedInRemoteShell': True,
         'applicationCompatibilityAcceptance': False,
         'pidAbsenceDiagnostics': {
             'trigger': 'completed_exit_1_empty_output', 'maxAdditionalCommands': 3,
             'commandTimeoutSeconds': ABSENCE_COMMAND_SECONDS,
             'admissionBudgetSeconds': ABSENCE_ADMISSION_SECONDS,
             'cleanupReservePerCommandSeconds': ABSENCE_CLEANUP_RESERVE_SECONDS,
             'hardWallClockDeadlineClaimed': False, 'bindingSemanticsInferred': False,
             'latePidAllowsProfiling': False,
             'outputByteLimits': {'absence_ps': 1024 * 1024,
                                  'absence_input_method': MAX_COMMAND_OUTPUT,
                                  'absence_pid_after': 65536}}}


class Rejected(ValueError):
    pass


def require(ok, reason):
    if not ok: raise Rejected(reason)


def identity(data):
    return {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def equal(a, b):
    return json.dumps(a, sort_keys=True, separators=(',', ':')) == json.dumps(b, sort_keys=True, separators=(',', ':'))


def now():
    return datetime.now(timezone.utc).isoformat()


def timestamp(value):
    require(type(value) is str and len(value) <= 40, 'TIMESTAMP_INVALID')
    try: parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError: raise Rejected('TIMESTAMP_INVALID') from None
    require(parsed.utcoffset() is not None and parsed.utcoffset().total_seconds() == 0, 'TIMESTAMP_NOT_UTC')
    return parsed


def integer(v, lower=0, upper=2**63-1):
    return type(v) is int and lower <= v <= upper


def exact(value, keys, reason):
    require(type(value) is dict and set(value) == set(keys), reason)


def validate_source(source):
    exact(source, {'repository', 'commit', 'runId', 'attempt'}, 'SOURCE_FIELDS')
    require(source['repository'] == REPOSITORY and type(source['commit']) is str
            and re.fullmatch('[0-9a-f]{40}', source['commit'])
            and integer(source['runId'], 1, 10**19-1) and integer(source['attempt'], 1, 10**19-1), 'SOURCE_INVALID')
    return source


def source_from_environment(env=None):
    env = os.environ if env is None else env
    require(env.get('GITHUB_ACTIONS') == 'true' and env.get('RUNNER_ENVIRONMENT') == 'github-hosted'
            and env.get('RUNNER_OS') == 'Linux', 'DEDICATED_RUNNER_REQUIRED')
    require(env.get('GITHUB_REPOSITORY') == REPOSITORY and env.get('GITHUB_REF') == 'refs/heads/master'
            and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and env.get('GITHUB_WORKFLOW_REF') == f'{REPOSITORY}/.github/workflows/{WORKFLOW}@refs/heads/master', 'WORKFLOW_SOURCE_REJECTED')
    for name in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
        require(re.fullmatch('[1-9][0-9]{0,18}', env.get(name, '')), 'SOURCE_RUN_INVALID')
    return validate_source(dict(repository=REPOSITORY, commit=env.get('GITHUB_SHA'),
                                runId=int(env['GITHUB_RUN_ID']), attempt=int(env['GITHUB_RUN_ATTEMPT'])))


def read_bounded(path, limit):
    path = Path(path)
    require(not any(p.is_symlink() for p in (path, *path.parents)), 'SYMLINK_PATH')
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and 0 <= info.st_size <= limit, 'FILE_LIMIT_OR_TYPE')
        data = stream.read(limit + 1)
        require(len(data) == info.st_size and len(data) <= limit, 'FILE_CHANGED_OR_LIMIT')
    return data


def decode_json(data):
    require(type(data) is bytes and len(data) <= MAX_JSON_BYTES, 'JSON_LIMIT')
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'JSON_DUPLICATE_KEY'); result[key] = value
        return result
    try:
        value = json.loads(data, object_pairs_hook=pairs,
                           parse_constant=lambda _: (_ for _ in ()).throw(Rejected('JSON_NONFINITE')))
        pending = [(value, 0)]; count = 0
        while pending:
            item, depth = pending.pop(); count += 1
            require(depth <= 48 and count <= 200000, 'JSON_STRUCTURE_LIMIT')
            if isinstance(item, dict): pending.extend((v, depth + 1) for v in item.values())
            elif isinstance(item, list): pending.extend((v, depth + 1) for v in item)
            elif isinstance(item, float): raise Rejected('JSON_FLOAT_NOT_SUPPORTED')
        return value
    except (ValueError, UnicodeError, RecursionError) as error:
        if isinstance(error, Rejected): raise
        raise Rejected('JSON_FORMAT') from None


def write_json(path, value):
    path = Path(path)
    require(path.name in {'report.json', 'commands.json'} and not path.is_symlink(), 'OUTPUT_PATH')
    data = (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + '\n').encode()
    require(len(data) <= (MAX_REPORT_BYTES if path.name == 'report.json' else MAX_JSON_BYTES), 'JSON_OUTPUT_LIMIT')
    temp = path.with_suffix('.pending')
    require(not temp.exists() and not temp.is_symlink(), 'PENDING_EXISTS')
    try:
        with temp.open('xb') as stream: stream.write(data); stream.flush(); os.fsync(stream.fileno())
        temp.replace(path)
    finally: temp.unlink(missing_ok=True)


def source_files(repo=None):
    repo = Path(repo) if repo is not None else Path(__file__).resolve().parents[1]
    return {name: identity(read_bounded(repo / name, 4 * 1024 * 1024)) for name in SOURCE_PATHS}


def read_payloads(directory):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'EVIDENCE_DIRECTORY')
    result = {}
    for path in directory.iterdir():
        require(path.name in FILES and path.is_file() and not path.is_symlink(), 'EVIDENCE_FILESET')
        limit = MAX_TRACE_BYTES if path.name == 'method.trace' else MAX_REPORT_BYTES if path.name == 'report.json' else MAX_JSON_BYTES
        result[path.name] = read_bounded(path, limit)
    require(sum(map(len, result.values())) <= MAX_TOTAL_BYTES, 'EVIDENCE_TOTAL_LIMIT')
    return result


def remote_path(source):
    validate_source(source)
    return f"/data/local/tmp/agentos-ime-profile-{source['runId']}-{source['attempt']}.trace"


def empty_report(source, identities=None):
    return {'schemaVersion': 1, 'kind': KIND, 'source': copy.deepcopy(validate_source(source)),
            'sourceFiles': source_files() if identities is None else copy.deepcopy(identities),
            'apiLevel': API, 'scope': copy.deepcopy(SCOPE), 'createdAt': now(), 'completedAt': None,
            'status': 'unverified', 'stage': 'prepared', 'failureReason': 'NOT_EXECUTED',
            'runAttempted': False, 'emulatorStepOutcome': None, 'device': None, 'target': None,
            'tools': None, 'profile': {'path': remote_path(source), 'startAttempted': False,
            'startRequestedAt': None, 'startAccepted': False, 'slotObservedOwned': False,
            'stopAttempted': False, 'stopRequestedAt': None, 'stopAccepted': False,
            'globalSlotClear': False, 'traceFile': None, 'remoteBytes': None,
            'fileStable': False, 'fileLimited': False, 'cleanupConfirmed': False},
            'analysis': None, 'emulatorTeardown': 'WORKFLOW_RESPONSIBILITY_NOT_OBSERVED'}


def prepare(directory, source):
    directory = Path(directory); require(not directory.is_symlink(), 'OUTPUT_SYMLINK')
    directory.mkdir(parents=True, exist_ok=True); require(not list(directory.iterdir()), 'OUTPUT_NOT_EMPTY')
    write_json(directory / 'report.json', empty_report(source))
    write_json(directory / 'commands.json', {'schemaVersion': 1, 'kind': KIND + '-commands',
                                          'source': copy.deepcopy(source), 'commands': []})


def command_data(command):
    try: return base64.b64decode(command['outputBase64'], validate=True)
    except (ValueError, TypeError): raise Rejected('COMMAND_BASE64') from None


def command_ok(command):
    return command['status'] == 'completed' and type(command['exitCode']) is int and command['exitCode'] == 0 and command['exceptionType'] is None


def clean_text(data):
    try: text = data.decode('utf-8')
    except UnicodeError: raise Rejected('COMMAND_TEXT_ENCODING') from None
    require(not any(ord(c) < 32 and c not in '\r\n\t' for c in text), 'COMMAND_TEXT_CONTROL')
    return text.replace('\r\n', '\n')


def select_emulator(data):
    text = clean_text(data)
    require(text.startswith('List of devices attached\n'), 'DEVICE_LIST_HEADER')
    rows = [line for line in text.splitlines()[1:] if line.strip()]
    require(len(rows) == 1 and re.fullmatch(r'emulator-[0-9]{4,5}\s+device', rows[0]), 'DEDICATED_EMULATOR_REQUIRED')
    return rows[0].split()[0]


def parse_stat(data, pid):
    if data.strip() == b'ABSENT': return None
    m = re.fullmatch(rb'([1-9][0-9]*) \(([^\r\n()]*)\) ([A-Za-z]) (.+)', data.strip())
    require(m is not None and int(m[1]) == pid, 'PROC_STAT_FORMAT')
    values = m[4].split(); require(len(values) >= 19 and values[18].isdigit(), 'PROC_STAT_START')
    return {'pid': pid, 'comm': m[2].decode('ascii'), 'state': m[3].decode('ascii'), 'startTimeTicks': int(values[18])}


def parse_profile_slot(data, target=None, path=None):
    text = clean_text(data); lines = text.splitlines()
    header = 'ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)'
    require(text.endswith('\n') and lines and lines[0] == header and lines.count(header) == 1,
            'PROFILE_DUMP_HEADER')
    nonempty = [line for line in lines if line.strip()]
    require(re.fullmatch(r'  mForceBackgroundCheck=(true|false)', nonempty[-1]) is not None,
            'PROFILE_DUMP_END')
    for pattern in (r'  Total persistent processes: [0-9]+',
                    r'  mProcessesReady=true mSystemReady=true mBooted=true mFactoryTest=[0-9]+',
                    r'  mBooting=(?:true|false) mCallFinishBooting=(?:true|false) mBootAnimationComplete=true'):
        require(sum(re.fullmatch(pattern, line) is not None for line in lines) == 1, 'PROFILE_DUMP_COMPLETENESS')
    require(not re.search(r'(?i)(permission denial|dump timeout|error:|exception:|deadobjectexception|dumpsys timed out)', text), 'PROFILE_DUMP_ERROR')
    # The reviewed unfiltered -a processes command always reaches the footer.
    # Empty AMS slots are omitted, not printed as explicit null fields.
    marked = [line for line in lines if re.search(r'\bm(?:Profile\w*|SamplingInterval|AutoStopProfiler|StreamingOutput|ProfilerFlags)\b', line)]
    if not marked: return {'state': 'empty'}
    require(target is not None and path is not None, 'PROFILE_SLOT_NOT_EMPTY')
    require(len(marked) == 4, 'PROFILE_SLOT_PARTIAL_OR_DUPLICATE')
    uid = target['uid']; user = uid // 100000; app = uid % 100000
    uid_label = f'u{user}a{app - 10000}' if app >= 10000 else str(uid)
    process = (r'ProcessRecord\{[0-9a-f]+ ' + str(target['pid']) + ':' + re.escape(target['package'])
               + '/' + re.escape(uid_label) + r'\}')
    patterns = [r'  mProfileApp=' + re.escape(target['package']) + r' mProfileProc=' + process,
                r'  mProfileFile=' + re.escape(path) + r' mProfileFd=null',
                r'  mSamplingInterval=0 mAutoStopProfiler=false mStreamingOutput=false mProfilerFlags=0',
                r'  mProfileType=0']
    require(all(re.fullmatch(pattern, line) is not None for pattern, line in zip(patterns, marked)), 'PROFILE_SLOT_OWNER')
    return {'state': 'owned', 'pid': target['pid'], 'package': target['package'], 'uid': target['uid'], 'path': path}


def shell_permission(data):
    text = clean_text(data)
    require(text.endswith('\n') and len(re.findall(r'^\s*Package \[com\.android\.shell\] \(', text, re.M)) == 1,
            'SHELL_PACKAGE_IDENTITY')
    values = re.findall(r'^\s+android\.permission\.SET_ACTIVITY_WATCHER: granted=(true|false)(?:,.*)?$', text, re.M)
    require(values and all(v == 'true' for v in values), 'SHELL_PROFILE_PERMISSION_NOT_CONFIRMED')
    return True


def parse_uid(data):
    lines = [line for line in clean_text(data).splitlines() if line.strip()]
    require(len(lines) == 1, 'IME_UID_NOT_UNIQUE')
    m = re.fullmatch(r'package:' + re.escape(PACKAGE) + r' uid:([1-9][0-9]{0,8})', lines[0])
    require(m is not None and 10000 <= int(m[1]) < 100000, 'IME_UID_INVALID')
    return int(m[1])


def parse_process_uid(data, pid):
    text = clean_text(data)
    require(text.endswith('\n'), 'PROC_STATUS_INCOMPLETE')
    pids = re.findall(r'^Pid:\s+([1-9][0-9]*)$', text, re.M)
    uids = re.findall(r'^Uid:\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)\s+([0-9]+)$', text, re.M)
    require(pids == [str(pid)] and len(uids) == 1 and len(set(uids[0])) == 1, 'PROC_STATUS_IDENTITY')
    return int(uids[0][0])


def command_limits(name):
    if name in ABSENCE_COMMAND_IDS:
        return ABSENCE_COMMAND_SECONDS, SCOPE['pidAbsenceDiagnostics']['outputByteLimits'][name]
    if name.startswith('slot_') or name in ('shell_package', 'ime_package'): return 10, MAX_COMMAND_OUTPUT
    if name == 'trace_pull': return 15, 65536
    return 10, 65536


def command_argv(name, report):
    adb = report['tools']['adb']
    if name == 'devices': return [adb, 'devices']
    prefix = [adb, '-s', report['device']['serial']]
    properties = {'api': 'ro.build.version.sdk', 'qemu': 'ro.kernel.qemu', 'debuggable': 'ro.debuggable',
                  'fingerprint': 'ro.build.fingerprint', 'buffer_property': 'debug.traceview-buffer-size-mb'}
    if name in properties: return prefix + ['shell', 'getprop', properties[name]]
    path = report['profile']['path']
    simple = {'shell_uid': ['shell', 'id', '-u'], 'current_user': ['shell', 'am', 'get-current-user'],
              'shell_package': ['shell', 'dumpsys', 'package', 'com.android.shell'],
              'default_ime': ['shell', 'settings', '--user', '0', 'get', 'secure', 'default_input_method'],
              'ime_uid': ['shell', 'cmd', 'package', 'list', 'packages', '-U', '--user', '0', PACKAGE],
              'ime_package': ['shell', 'dumpsys', 'package', PACKAGE],
              'ime_pid': ['shell', 'pidof', PACKAGE],
              'absence_ps': ['shell', 'ps', '-A', '-w', '-o', 'UID,PID,PPID,NAME,ARGS'],
              'absence_input_method': ['shell', 'dumpsys', 'input_method'],
              'absence_pid_after': ['shell', 'pidof', PACKAGE],
              'path_absent': ['shell', 'sh', '-c', shlex.quote('test ! -e ' + path + ' && test ! -L ' + path)],
              'trace_pull': ['exec-out', 'cat', path], 'trace_digest': ['shell', 'sha256sum', path],
              'cleanup_trace': ['shell', 'rm', '-f', path],
              'cleanup_absent': ['shell', 'sh', '-c', shlex.quote('test ! -e ' + path + ' && test ! -L ' + path)]}
    if name in simple: return prefix + simple[name]
    if name.startswith('slot_'): return prefix + ['shell', 'dumpsys', 'activity', '-a', 'processes']
    if name.startswith('size_poll_'):
        return prefix + ['shell', 'sh', '-c', shlex.quote('if test -f ' + path + ' && test ! -L ' + path
                + '; then stat -c %s ' + path + '; else printf "ABSENT\\n"; fi')]
    target = report['target']; require(target is not None, 'TARGET_MISSING')
    proc = '/proc/' + str(target['pid'])
    if name in ('target_stat', 'stop_stat'):
        return prefix + ['shell', 'sh', '-c', shlex.quote('if test -f ' + proc + '/stat; then cat ' + proc + '/stat; else printf "ABSENT\\n"; fi')]
    if name in ('target_cmdline', 'stop_cmdline'): return prefix + ['exec-out', 'cat', proc + '/cmdline']
    if name == 'target_status': return prefix + ['exec-out', 'cat', proc + '/status']
    if name == 'profile_start': return prefix + ['shell', 'am', 'profile', 'start', '--user', '0', str(target['pid']), path]
    if name == 'profile_stop':
        require(target['process'] is not None and target['cmdlineSha256'] is not None, 'STOP_TARGET_INCOMPLETE')
        uid = str(target['uid'])
        script = ('test -r ' + proc + '/stat && '
                  'test "$(cut -d " " -f 22 ' + proc + '/stat)" = ' + str(target['process']['startTimeTicks']) + ' && '
                  'test "$(sha256sum ' + proc + '/cmdline)" = "' + target['cmdlineSha256'] + '  ' + proc + '/cmdline" && '
                  'test "$(awk \'/^Uid:/{print $2,$3,$4,$5}\' ' + proc + '/status)" = "' + ' '.join([uid]*4) + '" && '
                  'exec /system/bin/am profile stop --user 0 ' + str(target['pid']))
        # AMS resolves the numeric PID again; this narrows but cannot atomically
        # eliminate the remaining target-exit/global-slot race in that service.
        return prefix + ['shell', 'sh', '-c', shlex.quote(script)]
    raise Rejected('COMMAND_ID_UNKNOWN')


def bounded_capture(argv, seconds, limit, reader=os.read):
    output = bytearray(); process = None; code = None; status = 'completed'; exception = None
    deadline = time.monotonic() + seconds
    try:
        if CANCELLED: return b'', dict(status='cancelled', exitCode=None, exceptionType=None)
        process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map() or process.poll() is None:
                if CANCELLED or time.monotonic() >= deadline:
                    status = 'cancelled' if CANCELLED else 'timed_out'; break
                for key, _ in selector.select(min(.1, max(0, deadline - time.monotonic()))):
                    chunk = reader(key.fd, min(65536, limit - len(output) + 1))
                    if not chunk: selector.unregister(key.fileobj); continue
                    left = limit - len(output); output.extend(chunk[:left])
                    if len(chunk) > left: status = 'output_limit'; break
                if status != 'completed': break
    except Exception as error: status, exception = 'exception', type(error).__name__
    finally:
        if process is not None:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            if process.stdout is not None: process.stdout.close()
            try: code = process.wait(timeout=1)
            except subprocess.TimeoutExpired: code = None
    return bytes(output), dict(status=status, exitCode=code, exceptionType=exception)


def stream_trace(argv, seconds, limit, destination):
    destination = Path(destination); require(not destination.exists() and not destination.is_symlink(), 'TRACE_OUTPUT_EXISTS')
    process = None; diagnostic = bytearray(); code = None; status = 'completed'; exception = None
    deadline = time.monotonic() + seconds
    def restricted(): resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_TRACE_BYTES, MAX_TRACE_BYTES))
    try:
        with destination.open('xb') as output:
            process = subprocess.Popen(argv, stdout=output, stderr=subprocess.PIPE, start_new_session=True, preexec_fn=restricted)
            with selectors.DefaultSelector() as selector:
                selector.register(process.stderr, selectors.EVENT_READ)
                while selector.get_map() or process.poll() is None:
                    if CANCELLED or time.monotonic() >= deadline:
                        status = 'cancelled' if CANCELLED else 'timed_out'; break
                    for key, _ in selector.select(min(.1, max(0, deadline - time.monotonic()))):
                        chunk = os.read(key.fd, min(65536, limit - len(diagnostic) + 1))
                        if not chunk: selector.unregister(key.fileobj); continue
                        left = limit - len(diagnostic); diagnostic.extend(chunk[:left])
                        if len(chunk) > left: status = 'output_limit'; break
                    if status != 'completed': break
    except Exception as error: status, exception = 'exception', type(error).__name__
    finally:
        if process is not None:
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            if process.stderr is not None: process.stderr.close()
            try: code = process.wait(timeout=1)
            except subprocess.TimeoutExpired: code = None
    return bytes(diagnostic), dict(status=status, exitCode=code, exceptionType=exception)


class CommandRunner:
    def __init__(self, directory, source, execute=bounded_capture, stream=stream_trace):
        self.directory, self.source, self.execute, self.stream = Path(directory), source, execute, stream
        self.commands = decode_json(read_bounded(self.directory / 'commands.json', MAX_JSON_BYTES))['commands']

    def save(self):
        write_json(self.directory / 'commands.json', dict(schemaVersion=1, kind=KIND + '-commands', source=self.source, commands=self.commands))

    def call(self, name, argv):
        require(name in COMMAND_IDS and name not in [v['id'] for v in self.commands] and len(self.commands) < MAX_COMMANDS, 'COMMAND_ID_OR_COUNT')
        seconds, limit = command_limits(name)
        # Reserve only this command's bounded output; binary bytes have a separate file cap.
        require(sum(v['bytes'] for v in self.commands) + limit <= MAX_TOTAL_OUTPUT, 'COMMAND_TOTAL_LIMIT')
        entry = dict(ordinal=len(self.commands)+1, id=name, argv=list(argv), timeoutSeconds=seconds,
                     byteLimit=limit, startedAt=now(), endedAt=None, elapsedMillis=0, status='running',
                     exitCode=None, exceptionType=None, outputBase64='', streamFile=None, **identity(b''))
        self.commands.append(entry); self.save(); tick = time.monotonic()
        try:
            if name == 'trace_pull':
                data, result = self.stream(argv, seconds, limit, self.directory / 'method.trace')
                if (self.directory / 'method.trace').is_file():
                    entry['streamFile'] = dict(path='method.trace', **identity(read_bounded(self.directory / 'method.trace', MAX_TRACE_BYTES)))
            else: data, result = self.execute(argv, seconds, limit)
        except Exception as error: data, result = b'', dict(status='exception', exitCode=None, exceptionType=type(error).__name__)
        require(type(data) is bytes and len(data) <= limit, 'COMMAND_OUTPUT_LIMIT')
        entry.update(**identity(data), outputBase64=base64.b64encode(data).decode(), endedAt=now(),
                     elapsedMillis=int((time.monotonic()-tick)*1000), **result)
        self.save(); return data, entry


def _check_target(report, stat_data, cmdline):
    target = report['target']; observed = parse_stat(stat_data, target['pid'])
    require(observed is not None and observed['state'] != 'Z' and observed['startTimeTicks'] > 0,
            'TARGET_NOT_LIVE')
    require(cmdline.endswith(b'\0') and cmdline.rstrip(b'\0') == PACKAGE.encode(), 'TARGET_CMDLINE')
    if target['process'] is not None:
        require(observed['startTimeTicks'] == target['process']['startTimeTicks']
                and observed['comm'] == target['process']['comm'], 'TARGET_REUSED_OR_CHANGED')
    return observed


def _preserve_unverified(directory, report, runner, save):
    """Retain only our attempted path's bounded bytes; never signal or clean it.

    A live/unknown native producer may still be writing. Its snapshot is not a
    complete trace or proof of a stopped profiler, regardless of parser output.
    """
    profile = report['profile']; used = {item['id'] for item in runner.commands}
    if not profile['startAttempted'] or 'trace_pull' in used: return
    if any(name.startswith('size_poll_') for name in used): return
    data, command = runner.call('size_poll_0', command_argv('size_poll_0', report))
    if not command_ok(command): return
    value = data.strip()
    if not re.fullmatch(rb'0|[1-9][0-9]{0,18}', value): return
    size = int(value); require(size < 2**63, 'TRACE_SIZE_RANGE')
    profile.update(remoteBytes=size, fileStable=False, fileLimited=size > MAX_TRACE_BYTES); save()
    _, pulled = runner.call('trace_pull', command_argv('trace_pull', report))
    profile['traceFile'] = copy.deepcopy(pulled['streamFile']); save()
    if profile['traceFile'] is not None:
        report['analysis'] = method_trace.parse_trace(read_bounded(directory / 'method.trace', MAX_TRACE_BYTES)); save()


def _stop_collect(directory, report, runner, save, sleep):
    profile = report['profile']
    if not profile['startAttempted'] or profile['stopAttempted']: return
    def checked(name):
        data, command = runner.call(name, command_argv(name, report))
        require(command_ok(command), 'COMMAND_FAILED_' + name); return data
    report['stage'] = 'stopping'; save()
    _check_target(report, checked('stop_stat'), checked('stop_cmdline'))
    # The reviewed AMS closes its original ParcelFileDescriptor after sending
    # the duplicate to the target; a complete owned slot normally has fd=null.
    require(parse_profile_slot(checked('slot_before_stop'), report['target'], profile['path'])['state'] == 'owned', 'PROFILE_SLOT_NOT_OWNED_AT_STOP')
    profile['stopAttempted'] = True; profile['stopRequestedAt'] = now(); save()
    checked('profile_stop'); profile['stopAccepted'] = True; save()
    require(parse_profile_slot(checked('slot_after_stop'))['state'] == 'empty', 'PROFILE_SLOT_NOT_CLEAR')
    profile['globalSlotClear'] = True; save()
    # File completion is independently checked by the bounded native-format parser.
    # Preserve an available bounded prefix even if size stability/capacity fails.
    sizes = []
    resource_reason = None
    for index in range(3):
        text = checked('size_poll_' + str(index)).strip()
        require(text == b'ABSENT' or re.fullmatch(rb'0|[1-9][0-9]{0,18}', text), 'TRACE_SIZE_FORMAT')
        size = None if text == b'ABSENT' else int(text); sizes.append(size)
        if size is not None and size > MAX_TRACE_BYTES:
            resource_reason = 'TRACE_FILE_TOO_LARGE'; break
        if len(sizes) > 1 and size is not None and size > 0 and sizes[-2] == size: break
        if index < 2: sleep(.5)
    profile['fileStable'] = len(sizes) > 1 and sizes[-1] is not None and sizes[-1] > 0 and sizes[-2] == sizes[-1]
    profile['remoteBytes'] = sizes[-1]
    profile['fileLimited'] = sizes[-1] is not None and sizes[-1] > MAX_TRACE_BYTES
    if not profile['fileStable'] and resource_reason is None: resource_reason = 'TRACE_FILE_NOT_STABLE'
    if resource_reason and report['failureReason'] is None: report['failureReason'] = resource_reason
    report['stage'] = 'pulling'; save()
    try:
        if sizes[-1] is not None and sizes[-1] > 0:
            _, pulled = runner.call('trace_pull', command_argv('trace_pull', report))
            profile['traceFile'] = copy.deepcopy(pulled['streamFile']); save()
            if profile['traceFile'] is not None:
                report['analysis'] = method_trace.parse_trace(read_bounded(directory / 'method.trace', MAX_TRACE_BYTES)); save()
            if resource_reason is None:
                require(command_ok(pulled) and profile['traceFile'] is not None and profile['traceFile']['bytes'] == profile['remoteBytes'], 'TRACE_PULL_INCOMPLETE')
                require(checked('trace_digest').decode().strip() == profile['traceFile']['sha256'] + '  ' + profile['path'], 'TRACE_DIGEST_MISMATCH')
    finally:
        # The unique path was absent before our attempt and its AMS slot is
        # now clear; cleanup does not assert successful or complete capture.
        checked('cleanup_trace'); checked('cleanup_absent'); profile['cleanupConfirmed'] = True; save()


def pid_absent(command):
    """Only the exact observed pidof absence result authorizes extra reads."""
    return (command['status'] == 'completed' and type(command['exitCode']) is int
            and command['exitCode'] == 1 and command['exceptionType'] is None
            and command_data(command) == b'')


def _observe_pid_absence(report, runner, save, monotonic=time.monotonic):
    """Read context once, without retrying profile admission or inferring binding.

    Twenty seconds bounds command admission using a monotonic clock. Each call
    reserves its five-second execution budget plus one second of child cleanup.
    Host scheduling and JSON persistence are not a hard wall-clock guarantee.
    Only transport outcomes are recorded; even exit zero is not a claim that an
    IMMS client/service dump was semantically complete or that an IME is bound.
    """
    report['failureReason'] = 'COMMAND_FAILED_ime_pid'; save()
    deadline = monotonic() + ABSENCE_ADMISSION_SECONDS
    for name in ABSENCE_COMMAND_IDS:
        if CANCELLED or deadline - monotonic() < ABSENCE_COMMAND_SECONDS + ABSENCE_CLEANUP_RESERVE_SECONDS:
            break
        try:
            _, entry = runner.call(name, command_argv(name, report))
        except Exception:
            # A host/persistence failure cannot replace the primary absence or
            # authorize more calls without a reliable original command receipt.
            break
        if entry['status'] == 'cancelled': break


def _validate_preflight_outputs(data):
    """Replay prerequisites shared by profile admission and absence-only reads."""
    for name, expected in [('api', '37'), ('qemu', '1'), ('shell_uid', '2000'), ('current_user', '0'), ('debuggable', '1')]:
        require(clean_text(data(name)).strip() == expected, 'START_PREFLIGHT')
    require(clean_text(data('buffer_property')).strip() in ('', '8'), 'START_BUFFER_PROPERTY')
    shell_permission(data('shell_package'))
    require(0 < len(clean_text(data('fingerprint')).strip()) <= 512, 'START_FINGERPRINT')
    component = clean_text(data('default_ime')).strip()
    require(re.fullmatch(re.escape(PACKAGE) + r'/(?:\.LatinIME|' + re.escape(PACKAGE) + r'\.LatinIME)', component), 'DEFAULT_IME_UNEXPECTED')
    parse_uid(data('ime_uid'))
    package_dump = clean_text(data('ime_package'))
    require(package_dump.endswith('\n') and len(re.findall(r'^\s*Package \[' + re.escape(PACKAGE) + r'\] \(', package_dump, re.M)) == 1, 'START_PACKAGE_DUMP')


def run_probe(args, source, runner_factory=CommandRunner, sleep=time.sleep):
    global CANCELLED
    directory = Path(args.output); report = validate_evidence(read_payloads(directory), source)
    require(not report['runAttempted'] and report['sourceFiles'] == source_files(), 'RUN_STATE_OR_SOURCE')
    report.update(runAttempted=True, stage='preflight', failureReason=None,
                  tools={'adb': str(Path(args.sdk_root).resolve() / 'platform-tools/adb')})
    def save(): write_json(directory / 'report.json', report)
    save(); runner = runner_factory(directory, source)
    def checked(name):
        data, command = runner.call(name, command_argv(name, report))
        require(command_ok(command), 'COMMAND_FAILED_' + name); return data
    try:
        report['device'] = {'serial': select_emulator(checked('devices')), 'properties': {}}; save()
        for name, expected in [('api', '37'), ('qemu', '1'), ('shell_uid', '2000'), ('current_user', '0'), ('debuggable', '1')]:
            value = clean_text(checked(name)).strip(); report['device']['properties'][name] = value; save()
            require(value == expected, 'PREFLIGHT_' + name.upper())
        for name in ('fingerprint', 'buffer_property'):
            value = clean_text(checked(name)).strip(); report['device']['properties'][name] = value; save()
        require(0 < len(report['device']['properties']['fingerprint']) <= 512, 'FINGERPRINT_INVALID')
        # Do not alter the buffer property. Unknown or enlarged values are not admitted.
        require(report['device']['properties']['buffer_property'] in ('', '8'), 'NONDEFAULT_TRACE_BUFFER')
        shell_permission(checked('shell_package'))
        component = clean_text(checked('default_ime')).strip()
        require(re.fullmatch(re.escape(PACKAGE) + r'/(?:\.LatinIME|' + re.escape(PACKAGE) + r'\.LatinIME)', component), 'DEFAULT_IME_UNEXPECTED')
        uid = parse_uid(checked('ime_uid')); package_dump = clean_text(checked('ime_package'))
        require(package_dump.endswith('\n') and len(re.findall(r'^\s*Package \[' + re.escape(PACKAGE) + r'\] \(', package_dump, re.M)) == 1, 'IME_PACKAGE_DUMP_IDENTITY')
        pid_output, pid_command = runner.call('ime_pid', command_argv('ime_pid', report))
        if pid_absent(pid_command):
            _observe_pid_absence(report, runner, save)
        require(command_ok(pid_command), 'COMMAND_FAILED_ime_pid')
        pid_text = clean_text(pid_output).strip()
        require(re.fullmatch(r'[1-9][0-9]{0,8}', pid_text) and int(pid_text) > 1, 'IME_PID_NOT_UNIQUE')
        report['target'] = {'component': component, 'package': PACKAGE, 'uid': uid, 'pid': int(pid_text), 'process': None, 'processUid': None, 'cmdlineSha256': None}; save()
        before = checked('target_stat')
        report['target']['processUid'] = parse_process_uid(checked('target_status'), int(pid_text)); save()
        require(report['target']['processUid'] == uid, 'TARGET_UID_MISMATCH')
        cmdline = checked('target_cmdline')
        report['target']['process'] = _check_target(report, before, cmdline)
        report['target']['cmdlineSha256'] = identity(cmdline)['sha256']; save()
        require(parse_profile_slot(checked('slot_before'))['state'] == 'empty', 'PROFILE_SLOT_NOT_EMPTY')
        checked('path_absent')
        report['stage'] = 'starting'; report['profile']['startAttempted'] = True; report['profile']['startRequestedAt'] = now(); save()
        checked('profile_start'); report['profile']['startAccepted'] = True; save()
        require(parse_profile_slot(checked('slot_started'), report['target'], report['profile']['path'])['state'] == 'owned', 'PROFILE_START_SLOT_NOT_OWNED')
        report['profile']['slotObservedOwned'] = True; report['stage'] = 'observing'; save(); sleep(HOLD_SECONDS)
        require(not CANCELLED, 'CANCELLED')
    except Exception as error:
        if report['failureReason'] is None:
            report['failureReason'] = str(error) if isinstance(error, Rejected) else 'HOST_' + type(error).__name__
    finally:
        cancelled = CANCELLED; CANCELLED = False
        if cancelled and report['failureReason'] is None: report['failureReason'] = 'CANCELLED'
        try: _stop_collect(directory, report, runner, save, sleep)
        except Exception as error:
            if report['failureReason'] is None: report['failureReason'] = str(error) if isinstance(error, Rejected) else 'HOST_' + type(error).__name__
            try: _preserve_unverified(directory, report, runner, save)
            except Exception:
                # The original command/ownership error remains the reason;
                # any partial file and attempted commands remain in originals.
                pass
        finally: CANCELLED = cancelled
        if report['failureReason'] is None and not report['profile']['cleanupConfirmed']: report['failureReason'] = 'PROFILE_COLLECTION_INCOMPLETE'
        if report['failureReason'] is None and (report['analysis'] is None or report['analysis'].get('status') != 'recorded'): report['failureReason'] = 'METHOD_TRACE_NOT_VERIFIED'
        if report['failureReason'] is None and report['analysis'].get('summary', {}).get('pid') != report['target']['pid']: report['failureReason'] = 'TRACE_PID_MISMATCH'
        report.update(status='recorded' if report['failureReason'] is None else 'unverified', stage='complete', completedAt=now()); save()
    validate_evidence(read_payloads(directory), source)
    return report['status'] == 'recorded'


def validate_evidence(payloads, source=None):
    require(type(payloads) is dict and {'report.json', 'commands.json'} <= set(payloads) <= FILES, 'EVIDENCE_REQUIRED_FILES')
    require(all(type(data) is bytes for data in payloads.values()) and sum(map(len, payloads.values())) <= MAX_TOTAL_BYTES, 'EVIDENCE_TOTAL_LIMIT')
    for name,data in payloads.items():
        require(len(data) <= (MAX_TRACE_BYTES if name=='method.trace' else MAX_REPORT_BYTES if name=='report.json' else MAX_JSON_BYTES),'EVIDENCE_FILE_LIMIT')
    report = decode_json(payloads['report.json']); commands = decode_json(payloads['commands.json'])
    exact(report, {'schemaVersion','kind','source','sourceFiles','apiLevel','scope','createdAt','completedAt','status','stage','failureReason','runAttempted','emulatorStepOutcome','device','target','tools','profile','analysis','emulatorTeardown'}, 'REPORT_FIELDS')
    validate_source(report['source']); source = report['source'] if source is None else validate_source(source)
    require(equal(report['source'], source) and type(report['schemaVersion']) is int and report['schemaVersion'] == 1
            and report['kind'] == KIND and report['apiLevel'] == API and type(report['apiLevel']) is int
            and equal(report['scope'], SCOPE), 'REPORT_IDENTITY')
    require(report['status'] in ('recorded','unverified') and report['stage'] in ('prepared','preflight','starting','observing','stopping','pulling','complete','finalized')
            and type(report['runAttempted']) is bool and report['emulatorStepOutcome'] in (None,'success','failure','cancelled','skipped','unknown'), 'REPORT_STATE')
    created = timestamp(report['createdAt'])
    if report['completedAt'] is not None: require(timestamp(report['completedAt']) >= created, 'REPORT_TIME_ORDER')
    require(report['failureReason'] is None or type(report['failureReason']) is str and 0 < len(report['failureReason']) <= 512, 'FAILURE_REASON')
    require(type(report['sourceFiles']) is dict and set(report['sourceFiles']) == set(SOURCE_PATHS), 'SOURCE_FILES_SET')
    for ident in report['sourceFiles'].values():
        exact(ident, {'bytes','sha256'}, 'SOURCE_FILE_FIELDS')
        require(integer(ident['bytes'],1,4*1024*1024) and type(ident['sha256']) is str and re.fullmatch('[0-9a-f]{64}', ident['sha256']), 'SOURCE_FILE_IDENTITY')
    exact(commands, {'schemaVersion','kind','source','commands'}, 'COMMANDS_FIELDS')
    require(type(commands['schemaVersion']) is int and commands['schemaVersion'] == 1 and commands['kind'] == KIND+'-commands' and equal(commands['source'],source), 'COMMANDS_SOURCE')
    items = commands['commands']; require(type(items) is list and len(items) <= MAX_COMMANDS, 'COMMAND_COUNT')
    entries = {}; position = -1; total = 0; previous_end = created
    for ordinal, entry in enumerate(items,1):
        exact(entry, {'ordinal','id','argv','timeoutSeconds','byteLimit','startedAt','endedAt','elapsedMillis','status','exitCode','exceptionType','outputBase64','streamFile','bytes','sha256'}, 'COMMAND_FIELDS')
        name=entry['id']; require(name in COMMAND_IDS and name not in entries and COMMAND_IDS.index(name)>position, 'COMMAND_ORDER')
        position=COMMAND_IDS.index(name); entries[name]=entry
        require(type(entry['ordinal']) is int and entry['ordinal']==ordinal and equal(entry['argv'],command_argv(name,report)), 'COMMAND_BINDING')
        seconds,limit=command_limits(name)
        require(type(entry['timeoutSeconds']) is int and entry['timeoutSeconds']==seconds and type(entry['byteLimit']) is int and entry['byteLimit']==limit, 'COMMAND_LIMITS')
        start=timestamp(entry['startedAt']); require(start>=previous_end,'COMMAND_TIME_ORDER')
        require(entry['status'] in ('running','completed','timed_out','output_limit','cancelled','exception') and integer(entry['elapsedMillis'],0,(seconds+5)*1000), 'COMMAND_STATUS')
        require(entry['exitCode'] is None or type(entry['exitCode']) is int, 'COMMAND_EXIT_CODE')
        require(entry['exceptionType'] is None or type(entry['exceptionType']) is str and re.fullmatch('[A-Za-z][A-Za-z0-9_]{0,79}',entry['exceptionType']), 'COMMAND_EXCEPTION')
        if entry['endedAt'] is None: require(entry['status']=='running' and ordinal==len(items),'COMMAND_END_MISSING')
        else: previous_end=timestamp(entry['endedAt']);require(previous_end>=start,'COMMAND_TIME_ORDER')
        data=command_data(entry);require(len(data)<=limit and equal(identity(data),{k:entry[k] for k in ('bytes','sha256')}),'COMMAND_BYTES');total+=len(data)
        require(entry['streamFile'] is None or name=='trace_pull','COMMAND_STREAM_FIELD')
    require(total<=MAX_TOTAL_OUTPUT,'COMMAND_TOTAL_LIMIT')
    if items: require(report['runAttempted'] and report['tools'] is not None,'COMMAND_WITHOUT_RUN')
    if report['tools'] is not None:
        exact(report['tools'], {'adb'}, 'TOOLS_FIELDS');require(type(report['tools']['adb']) is str and report['tools']['adb'].startswith('/') and report['tools']['adb'].endswith('/platform-tools/adb'),'ADB_PATH')
    def ok(name): return name in entries and command_ok(entries[name])
    def data(name): require(ok(name),'REQUIRED_COMMAND_'+name);return command_data(entries[name])
    if report['device'] is not None:
        exact(report['device'], {'serial','properties'},'DEVICE_FIELDS');require(report['device']['serial']==select_emulator(data('devices')),'DEVICE_SERIAL')
        properties=report['device']['properties'];require(type(properties) is dict and set(properties)<=set(COMMAND_IDS[1:9]),'DEVICE_PROPERTIES')
        for name,value in properties.items(): require(value==clean_text(data(name)).strip(),'DEVICE_PROPERTY_SOURCE')
    target=report['target']
    if target is not None:
        exact(target, {'component','package','uid','pid','process','processUid','cmdlineSha256'},'TARGET_FIELDS')
        require(target['package']==PACKAGE and target['component']==clean_text(data('default_ime')).strip()
                and re.fullmatch(re.escape(PACKAGE)+r'/(?:\.LatinIME|'+re.escape(PACKAGE)+r'\.LatinIME)',target['component'])
                and target['uid']==parse_uid(data('ime_uid')) and integer(target['pid'],2,999999999)
                and clean_text(data('ime_pid')).strip()==str(target['pid']),'TARGET_SOURCE')
        if target['process'] is not None:
            require(equal(target['process'],parse_stat(data('target_stat'),target['pid'])) and target['process']['state']!='Z' and target['process']['startTimeTicks']>0,'TARGET_STAT_SOURCE')
        if target['cmdlineSha256'] is not None:
            original_cmdline=data('target_cmdline')
            require(original_cmdline.endswith(b'\0') and original_cmdline.rstrip(b'\0')==PACKAGE.encode()
                    and target['cmdlineSha256']==identity(original_cmdline)['sha256'],'TARGET_CMDLINE_SOURCE')
        if target['processUid'] is not None: require(target['processUid']==parse_process_uid(data('target_status'),target['pid']),'TARGET_PROCESS_UID_SOURCE')
    profile=report['profile']; exact(profile,{'path','startAttempted','startRequestedAt','startAccepted','slotObservedOwned','stopAttempted','stopRequestedAt','stopAccepted','globalSlotClear','traceFile','remoteBytes','fileStable','fileLimited','cleanupConfirmed'},'PROFILE_FIELDS')
    require(profile['path']==remote_path(source),'PROFILE_PATH')
    for key in ('startAttempted','startAccepted','slotObservedOwned','stopAttempted','stopAccepted','globalSlotClear','fileStable','fileLimited','cleanupConfirmed'): require(type(profile[key]) is bool,'PROFILE_BOOLEAN')
    for name, flag in [('profile_start','startAttempted'),('profile_stop','stopAttempted')]:
        if name in entries: require(profile[flag] and timestamp(profile['startRequestedAt' if name=='profile_start' else 'stopRequestedAt'])<=timestamp(entries[name]['startedAt']),'COMMAND_WITHOUT_INTENT')
    absence_names = [name for name in entries if name in ABSENCE_COMMAND_IDS]
    first_pid_absent = 'ime_pid' in entries and pid_absent(entries['ime_pid'])
    if first_pid_absent or absence_names:
        # The primary command is durably written just before the failure reason.
        # Preserve that interruption window, but never allow added reads there.
        interrupted_before_reason = (not absence_names and report['failureReason'] is None
                                     and report['completedAt'] is None and report['stage'] == 'preflight')
        require(first_pid_absent and report['status'] == 'unverified'
                and (report['failureReason'] == 'COMMAND_FAILED_ime_pid' or interrupted_before_reason),
                'ABSENCE_TRIGGER_OR_RESULT')
        require(all(ok(name) for name in PROFILE_COMMAND_IDS[:_PID_POSITION]), 'ABSENCE_PREFLIGHT_INCOMPLETE')
        _validate_preflight_outputs(data)
        require(target is None and report['analysis'] is None and 'method.trace' not in payloads
                and equal(profile, empty_report(source, report['sourceFiles'])['profile']), 'ABSENCE_PROFILE_FORBIDDEN')
        require(set(entries) <= set(PROFILE_COMMAND_IDS[:_PID_POSITION + 1]) | set(ABSENCE_COMMAND_IDS)
                and absence_names == list(ABSENCE_COMMAND_IDS[:len(absence_names)]), 'ABSENCE_COMMAND_SEQUENCE')
        require(not any(entries[name]['status'] in ('cancelled', 'running') for name in absence_names[:-1]), 'ABSENCE_AFTER_INTERRUPTION')
    if profile['startAttempted']:
        require(all(ok(name) for name in START_PREFLIGHT_IDS), 'START_PREFLIGHT_COMMANDS_INCOMPLETE')
        require(target is not None and target['process'] is not None and target['cmdlineSha256'] is not None and target['processUid']==target['uid'],'START_WITHOUT_TARGET')
        _validate_preflight_outputs(data)
        require(parse_profile_slot(data('slot_before'))['state']=='empty' and ok('path_absent'),'START_SLOT_OR_PATH')
    require(not profile['startAccepted'] or ok('profile_start'),'START_ACCEPTED_SOURCE')
    if profile['slotObservedOwned']:require(parse_profile_slot(data('slot_started'),target,profile['path'])['state']=='owned','START_SLOT_SOURCE')
    if profile['stopAttempted']:
        require(profile['startAttempted'],'STOP_WITHOUT_START')
        _check_target(report,data('stop_stat'),data('stop_cmdline'))
        require(parse_profile_slot(data('slot_before_stop'),target,profile['path'])['state']=='owned','STOP_SLOT_SOURCE')
    require(not profile['stopAccepted'] or profile['stopAttempted'] and ok('profile_stop'),'STOP_ACCEPTED_SOURCE')
    if profile['globalSlotClear']:require(profile['stopAccepted'] and parse_profile_slot(data('slot_after_stop'))['state']=='empty','STOP_CLEAR_SOURCE')
    if profile['traceFile'] is not None:
        require('method.trace' in payloads and len(payloads['method.trace'])<=MAX_TRACE_BYTES and equal(profile['traceFile'],dict(path='method.trace',**identity(payloads['method.trace'])))
                and 'trace_pull' in entries and equal(entries['trace_pull']['streamFile'],profile['traceFile']),'TRACE_FILE_SOURCE')
    elif 'method.trace' in payloads: require('trace_pull' in entries and report['status']=='unverified','UNREPORTED_TRACE')
    if profile['remoteBytes'] is not None:
        require(integer(profile['remoteBytes'],0,2**63-1),'REMOTE_TRACE_SIZE')
        sizes=[command_data(entries[n]).strip() for n in COMMAND_IDS if n.startswith('size_poll_') and ok(n)]
        require(sizes and sizes[-1]==str(profile['remoteBytes']).encode(),'TRACE_SIZE_SOURCE')
        require(profile['fileLimited']==(profile['remoteBytes']>MAX_TRACE_BYTES),'TRACE_LIMIT_SOURCE')
        require(profile['fileStable']==(len(sizes)>1 and profile['remoteBytes']>0 and sizes[-1]==sizes[-2]),'TRACE_STABILITY_SOURCE')
    else: require(not profile['fileStable'] and not profile['fileLimited'],'TRACE_SIZE_UNKNOWN')
    if report['analysis'] is not None: require('method.trace' in payloads and equal(report['analysis'],method_trace.parse_trace(payloads['method.trace'])),'TRACE_ANALYSIS_SOURCE')
    if profile['cleanupConfirmed']:
        require(profile['globalSlotClear'] and ok('cleanup_trace') and ok('cleanup_absent'),'TRACE_CLEANUP_SOURCE')
    if report['status']=='recorded':
        require(report['failureReason'] is None and report['completedAt'] is not None and report['emulatorStepOutcome'] in (None,'success')
                and all(profile[k] for k in ('startAttempted','startAccepted','slotObservedOwned','stopAttempted','stopAccepted','globalSlotClear','cleanupConfirmed'))
                and profile['fileStable'] and not profile['fileLimited'] and profile['traceFile'] is not None
                and profile['traceFile']['bytes']==profile['remoteBytes'] and ok('trace_pull')
                and clean_text(data('trace_digest')).strip()==profile['traceFile']['sha256']+'  '+profile['path']
                and report['analysis'] is not None and report['analysis'].get('status')=='recorded'
                and report['analysis'].get('summary',{}).get('pid')==target['pid'],'RECORDED_INCOMPLETE')
    else: require(report['failureReason'] is not None or report['runAttempted'] and report['completedAt'] is None,'UNVERIFIED_REASON_MISSING')
    return report


def finalize(directory, source, outcome):
    require(outcome in ('success','failure','cancelled','skipped','unknown'),'EMULATOR_OUTCOME')
    directory=Path(directory);payloads=read_payloads(directory);report=validate_evidence(payloads,source)
    if report['failureReason'] is None:
        entries = decode_json(payloads['commands.json'])['commands']
        if any(entry['id'] == 'ime_pid' and pid_absent(entry) for entry in entries):
            # Recover only the already persisted primary absence after an
            # interruption; finalization does not retry any device operation.
            report['failureReason'] = 'COMMAND_FAILED_ime_pid'
    report['emulatorStepOutcome']=outcome
    if outcome!='success' or report['status']!='recorded':
        report['status']='unverified'
        if report['failureReason'] is None:report['failureReason']='EMULATOR_STEP_'+outcome.upper()
    report['stage']='finalized';report['completedAt']=now();write_json(directory/'report.json',report)
    validate_evidence(read_payloads(directory),source)
    return report['status']=='recorded'


def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    for name in ('prepare','run','finalize'):
        command=sub.add_parser(name);command.add_argument('--output',required=True)
        if name=='run':command.add_argument('--sdk-root',required=True)
        if name=='finalize':command.add_argument('--outcome',required=True)
    args=parser.parse_args();source=source_from_environment()
    def cancelled(signum, frame):
        global CANCELLED
        CANCELLED=True
    signal.signal(signal.SIGTERM,cancelled);signal.signal(signal.SIGINT,cancelled)
    if args.command=='prepare':prepare(args.output,source);return 0
    if args.command=='run':return 0 if run_probe(args,source) else 1
    finalize(args.output,source,args.outcome);return 0


if __name__=='__main__':
    try:raise SystemExit(main())
    except (Rejected,OSError) as error:
        print(json.dumps({'status':'unverified','reason':str(error)[:500]}));raise SystemExit(1)
