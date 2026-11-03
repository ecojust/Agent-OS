#!/usr/bin/env python3
"""Bounded native observation around the unchanged keyboard comparison.

Capture completeness and the original comparison result are deliberately separate.
Only the uniquely named Perfetto process whose start time and command line were
recorded by this run may receive a stop signal. No trace is an application pass.
"""
import argparse
import base64
import copy
import json
import math
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
from types import SimpleNamespace

import window_infos_keyboard as k
import window_infos_raw as w

KIND = 'window-infos-reset-trace'
MIB = 1024 * 1024
MAX_TRACE_BYTES = 32 * MIB
MAX_FILE_BYTES = 4 * MIB
MAX_COMMAND_BYTES = 12 * MIB
MAX_CONFIG_BYTES = 65536
MAX_TOTAL_BYTES = 128 * MIB
MAX_TOTAL_OUTPUT = 8 * MIB
MAX_COMMANDS = 40
DURATION_MS = 120000
STOP_POLLS = 10
EXTRA_SOURCES = ('scripts/window_infos_reset_trace.py',
                 'scripts/window_infos_reset_trace_analysis.py',
                 'scripts/window_infos_reset_trace_archive.py', 'scripts/experience_trace.py')
ANALYSIS_NAMES = ('analysis.json', 'trace-slices.csv', 'trace-clocks.csv',
                  'trace-processes.csv', 'trace-stats.csv', 'trace-bounds.csv', 'trace-metadata.csv')
TOP_FILES = {'report.json', 'commands.json', 'trace-config.pbtxt', 'trace.pftrace'}
FILES = TOP_FILES | {'comparison/' + name for name in k.FILES} | {'analysis/' + name for name in ANALYSIS_NAMES}
COMMAND_IDS = ('devices', 'api', 'qemu', 'current_user', 'shell_uid', 'perfetto_help',
               'config_absent', 'trace_absent', 'push_config', 'config_digest', 'start_trace',
               'start_stat', 'start_cmdline', 'ready_file', 'stop_stat', 'stop_cmdline',
               'stop_trace', *('stop_poll_' + str(i) for i in range(STOP_POLLS)),
               'trace_size', 'trace_pull', 'trace_digest', 'cleanup_trace', 'cleanup_config',
               'cleanup_absent')
SCOPE = {'diagnosticOnly': True, 'originalNotesCasesRerun': 0,
         'comparisonContractChanged': False, 'rawObservationMs': 12000,
         'perArmShowRequestLimit': 1, 'rootRequested': False,
         'hiddenApiPolicyMutation': False, 'globalTraceCleared': False,
         'captureDoesNotProveLossFreeOrCallerIdentity': True,
         'durationMs': DURATION_MS, 'traceByteLimit': MAX_TRACE_BYTES,
         'bufferKiB': [4096, 4096], 'bufferPolicy': 'RING_BUFFER',
         'maxCommands': MAX_COMMANDS, 'maxCommandOutputBytes': MAX_TOTAL_OUTPUT,
         'perfettoHelpExitCodes': [0, 1], 'perfettoHelpRequiresCompleteUsage': True,
         'configDelivery': 'HOST_STDIN_PIPE', 'completeConfigWriteAndEofRequired': True,
         'backgroundWaitRequired': True, 'traceStartLimit': 1, 'traceStopSignalLimit': 1,
         'stopPollLimit': STOP_POLLS, 'applicationCompatibilityAcceptance': False}
require, Rejected = w.require, w.Rejected
equal, now = w.common.equal, w.now


def trace_config():
    return ('''buffers { size_kb: 4096 fill_policy: RING_BUFFER }
buffers { size_kb: 4096 fill_policy: RING_BUFFER }
data_sources {
  config {
    name: "linux.ftrace"
    target_buffer: 0
    ftrace_config {
      atrace_categories: "wm"
      atrace_categories: "am"
      atrace_categories: "view"
      ftrace_events: "sched/sched_process_exit"
      ftrace_events: "sched/sched_process_free"
      ftrace_events: "task/task_newtask"
      ftrace_events: "task/task_rename"
    }
  }
}
data_sources {
  config {
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config { scan_all_processes_on_start: true record_thread_names: true proc_stats_poll_ms: 1000 }
  }
}
duration_ms: 120000
flush_period_ms: 1000
write_into_file: true
file_write_period_ms: 500
max_file_size_bytes: 33554432
''').encode()


def source_files(repo=None):
    repo = Path(repo) if repo is not None else Path(__file__).resolve().parents[1]
    result = k.source_files(repo)
    result.update({name: w.identity(w.read_bounded(repo / name, MAX_FILE_BYTES)) for name in EXTRA_SOURCES})
    return result


def verify_source_files(report, repo):
    require(equal(report['sourceFiles'], source_files(repo)), 'RESET_TRACE_SOURCE_FILES_MISMATCH')
    return True


def file_limit(name):
    if name.startswith('comparison/'): return k.file_limit(name[len('comparison/'):])
    if name == 'trace.pftrace': return MAX_TRACE_BYTES
    if name == 'trace-config.pbtxt': return MAX_CONFIG_BYTES
    if name == 'commands.json': return MAX_COMMAND_BYTES
    return MAX_FILE_BYTES


def decode_json(data):
    def finite(value):
        number = float(value); require(math.isfinite(number), 'RESET_JSON_NONFINITE'); return number
    try:
        return json.loads(data, object_pairs_hook=w.common.unique_object, parse_float=finite,
                          parse_constant=lambda _: (_ for _ in ()).throw(Rejected('RESET_JSON_NONFINITE')))
    except (UnicodeError, ValueError, RecursionError) as error:
        raise Rejected('RESET_JSON_INVALID') from error


def read_payloads(directory):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'RESET_DIRECTORY')
    result = {}; total = 0; comparison_total = 0
    for path in sorted(directory.rglob('*')):
        require(not path.is_symlink(), 'RESET_SYMLINK')
        name = path.relative_to(directory).as_posix()
        if path.is_dir():
            require(name in {'comparison', 'comparison/baseline', 'comparison/enabled', 'analysis'}, 'RESET_DIRECTORY_SET')
            continue
        require(name in FILES and path.is_file(), 'RESET_FILESET')
        data = w.read_bounded(path, file_limit(name)); total += len(data)
        if name.startswith('comparison/'): comparison_total += len(data)
        # Derived analysis may have been interrupted mid-write. Preserve those
        # bytes independently; only its own validator interprets that subtree.
        result[name] = decode_json(data) if name.endswith('.json') and not name.startswith('analysis/') else data
    require(total <= MAX_TOTAL_BYTES and comparison_total <= k.MAX_TOTAL_BYTES, 'RESET_TOTAL_BYTES')
    return result


def comparison_payloads(payloads):
    return {name[len('comparison/'):]: value for name, value in payloads.items() if name.startswith('comparison/')}


def comparison_summary(report):
    return {key: copy.deepcopy(report[key]) for key in ('status', 'failureReason', 'arms', 'restoration')}


def remote_paths(source):
    stem = 'agentos-reset-trace-' + str(source['runId']) + '-' + str(source['attempt'])
    return {'config': '/data/local/tmp/' + stem + '.pbtxt',
            'trace': '/data/misc/perfetto-traces/' + stem + '.pftrace'}


def empty_report(source, identities=None):
    return {'schemaVersion': 1, 'kind': KIND, 'source': copy.deepcopy(w.validate_source(source)),
            'sourceFiles': source_files() if identities is None else copy.deepcopy(identities),
            'scope': copy.deepcopy(SCOPE), 'createdAt': now(), 'completedAt': None,
            'status': 'unverified', 'stage': 'prepared', 'failureReason': 'NOT_EXECUTED',
            'runAttempted': False, 'emulatorStepOutcome': None, 'device': None, 'tools': None,
            'comparison': None, 'trace': {'paths': remote_paths(source), 'config': w.identity(trace_config()),
            'startAttempted': False, 'startAttemptedAt': None, 'owner': None,
            'stopAttempted': False, 'stopAttemptedAt': None, 'stopConfirmed': False,
            'exitObserved': False, 'exitReason': None,
            'pullAttempted': False, 'file': None, 'remoteBytes': None,
            'cleanupConfirmed': False}, 'emulatorTeardown': 'WORKFLOW_RESPONSIBILITY_NOT_OBSERVED'}


def write_json(path, value, durable=False):
    path = Path(path); limit = MAX_COMMAND_BYTES if path.name == 'commands.json' else MAX_FILE_BYTES
    require(path.name in {'commands.json', 'report.json'} and not path.is_symlink(), 'RESET_WRITE_PATH')
    data = (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + '\n').encode()
    require(len(data) <= limit, 'RESET_JSON_OUTPUT_LIMIT')
    temporary = path.with_suffix('.pending')
    require(not temporary.exists() and not temporary.is_symlink(), 'RESET_PENDING_EXISTS')
    try:
        with temporary.open('xb') as stream:
            stream.write(data)
            if durable: stream.flush(); os.fsync(stream.fileno())
        temporary.replace(path)
        if durable:
            fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try: os.fsync(fd)
            finally: os.close(fd)
    finally: temporary.unlink(missing_ok=True)


def prepare(directory, source):
    directory = Path(directory)
    require(not directory.is_symlink(), 'RESET_OUTPUT_SYMLINK')
    directory.mkdir(parents=True, exist_ok=True)
    require(not list(directory.iterdir()), 'RESET_OUTPUT_NOT_EMPTY')
    k.prepare(directory / 'comparison', source)
    report = empty_report(source)
    report['comparison'] = comparison_summary(k.tree_read(directory / 'comparison')['report.json'])
    (directory / 'trace-config.pbtxt').write_bytes(trace_config())
    write_json(directory / 'report.json', report, True)
    write_json(directory / 'commands.json', {'schemaVersion': 1, 'kind': KIND + '-commands',
                                           'source': copy.deepcopy(source), 'commands': []})


def parse_pid(data):
    lines = re.findall(rb'(?m)^([1-9][0-9]{0,8})\r?$', data)
    require(len(lines) == 1 and int(lines[0]) > 1, 'RESET_START_PID')
    return int(lines[0])


def parse_stat(data, pid):
    if data.strip() == b'ABSENT': return None
    match = re.fullmatch(rb'([1-9][0-9]*) \(([^\r\n()]*)\) ([A-Za-z]) (.+)\n?', data.strip())
    require(match is not None and int(match[1]) == pid, 'RESET_PROC_STAT')
    values = match[4].split()
    require(len(values) >= 19 and values[18].isdigit(), 'RESET_PROC_START_TIME')
    return {'pid': pid, 'comm': match[2].decode('ascii'), 'state': match[3].decode('ascii'),
            'startTimeTicks': int(values[18])}


def command_limits(name):
    if name == 'start_trace': return 15, 65536
    if name == 'trace_pull': return 30, 65536
    if name == 'perfetto_help': return 10, 262144
    return 10, 65536


def perfetto_help_accepted(command):
    """Perfetto prints complete --help usage with exit 1 on the observed API37.

    This exception is confined to this read-only command. A timeout, output
    limit, transport failure or a usage prefix is never evidence of support.
    Unknown help layouts remain unavailable instead of silently falling back.
    """
    try:
        require(command['id'] == 'perfetto_help' and command['status'] == 'completed'
                and type(command['exitCode']) is int and command['exitCode'] in (0, 1)
                and command['exceptionType'] is None, 'RESET_HELP_COMMAND_STATE')
        data = w.command_data(command)
        require(0 < len(data) <= command_limits('perfetto_help')[1]
                and equal(w.identity(data), {'bytes': command['bytes'], 'sha256': command['sha256']}), 'RESET_HELP_BYTES')
        text = data.decode('utf-8').replace('\r\n', '\n')
        require(text.endswith('\n') and not any(ord(char) < 32 and char not in '\n\t' for char in text), 'RESET_HELP_TEXT')
        lines = [line for line in text.splitlines() if line.strip()]
        require(10 <= len(lines) <= 256 and lines[0] == 'Usage: /system/bin/perfetto'
                and lines[-1].strip() == 'Exit code:  0:Yes, 2:No, 1:Error.', 'RESET_HELP_BOUNDARIES')
        headers = ('Light configuration flags: (only when NOT using -c/--config)',
                   'Statsd-specific and other Android-only flags:',
                   'Detach mode. DISCOURAGED, read https://perfetto.dev/docs/concepts/detached-mode')
        require(all(lines.count(header) == 1 for header in headers)
                and [lines.index(header) for header in headers] == sorted(lines.index(header) for header in headers)
                and all(line.startswith('  ') or line in headers for line in lines[1:]), 'RESET_HELP_SECTIONS')
        body_line = r'  --[A-Za-z0-9_-]+.*|  (?:FTRACE_GROUP/FTRACE_NAME|ATRACE_CAT)\s+: .+| {20,}\S.*'
        require(all(line in headers or re.fullmatch(body_line, line) is not None
                    for line in lines[1:]), 'RESET_HELP_UNEXPECTED_OUTPUT')
        options = (r'  --background\s+-d\s+: .+', r'  --background-wait\s+-D\s+: .+',
                   r'  --config\s+-c\s+: /path/to/trace/config/file or - for stdin',
                   r'  --out\s+-o\s+: /path/to/out/trace/file or - for stdout',
                   r'  --txt\s+: Parse config as pbtxt\..+', r'  --help\s+-h',
                   r'  --detach=key\s+: .+', r'  --attach=key \[--stop\]\s+: .+',
                   r'  --is_detached=key\s+: Check if the session can be re-attached\.')
        require(all(sum(re.fullmatch(pattern, line) is not None for line in lines) == 1
                    for pattern in options), 'RESET_HELP_OPTIONS')
        return True
    except (KeyError, TypeError, UnicodeError, ValueError):
        return False


def command_accepted(command):
    if command.get('id') == 'perfetto_help': return perfetto_help_accepted(command)
    if command.get('id') == 'start_trace': return w.command_ok(command) and complete_config_input(command)
    return w.command_ok(command)


def complete_config_input(command):
    expected = {'path': 'trace-config.pbtxt', **w.identity(trace_config()),
                'writtenBytes': len(trace_config()), 'eofSent': True}
    return type(command.get('stdin')) is dict and equal(command['stdin'], expected)


def command_argv(name, report):
    adb = report['tools']['adb']; paths = report['trace']['paths']
    if name == 'devices': return [adb, 'devices']
    require(report['device'] is not None, 'RESET_COMMAND_DEVICE')
    prefix = [adb, '-s', report['device']['serial']]
    tails = {
        'api': ['shell', 'getprop', 'ro.build.version.sdk'],
        'qemu': ['shell', 'getprop', 'ro.kernel.qemu'],
        'current_user': ['shell', 'am', 'get-current-user'], 'shell_uid': ['shell', 'id', '-u'],
        'perfetto_help': ['shell', '/system/bin/perfetto', '--help'],
        'config_absent': ['shell', 'sh', '-c', shlex.quote('test ! -e ' + paths['config'] + ' && test ! -L ' + paths['config'])],
        'trace_absent': ['shell', 'sh', '-c', shlex.quote('test ! -e ' + paths['trace'] + ' && test ! -L ' + paths['trace'])],
        'push_config': ['push', report['tools']['config'], paths['config']],
        'config_digest': ['shell', 'sha256sum', paths['config']],
        'start_trace': ['shell', '/system/bin/perfetto', '--txt', '-c', '-',
                        '-o', paths['trace'], '--background-wait'],
        'ready_file': ['shell', 'sh', '-c', shlex.quote('test -f ' + paths['trace'] + ' && test ! -L ' + paths['trace'])],
        'trace_size': ['shell', 'sh', '-c', shlex.quote('test -f ' + paths['trace'] + ' && test ! -L ' + paths['trace'] + ' && stat -c %s ' + paths['trace'])],
        'trace_pull': ['exec-out', 'cat', paths['trace']],
        'trace_digest': ['shell', 'sha256sum', paths['trace']],
        'cleanup_trace': ['shell', 'rm', '-f', paths['trace']],
        'cleanup_config': ['shell', 'rm', '-f', paths['config']],
        'cleanup_absent': ['shell', 'sh', '-c', shlex.quote('test ! -e ' + paths['config'] + ' && test ! -L ' + paths['config']
                                                       + ' && test ! -e ' + paths['trace'] + ' && test ! -L ' + paths['trace'])],
    }
    if name in tails: return prefix + tails[name]
    owner = report['trace']['owner']; require(owner is not None, 'RESET_COMMAND_OWNER')
    pid = owner['pid']; proc = '/proc/' + str(pid)
    if name in {'start_stat', 'stop_stat'} or name.startswith('stop_poll_'):
        return prefix + ['shell', 'sh', '-c', shlex.quote('if test -e ' + proc + '/stat; then cat ' + proc + '/stat; else printf "ABSENT\\n"; fi')]
    if name in {'start_cmdline', 'stop_cmdline'}: return prefix + ['exec-out', 'cat', proc + '/cmdline']
    require(name == 'stop_trace' and owner['startTimeTicks'] is not None and owner['cmdlineSha256'] is not None, 'RESET_STOP_OWNER')
    # Recheck in the same finite shell invocation immediately before signaling.
    # Linux PID identity can still race; no claim of an atomic pidfd operation is made.
    script = ('test "$(cat ' + proc + '/comm)" = perfetto && '
              'test "$(cut -d " " -f 22 ' + proc + '/stat)" = ' + str(owner['startTimeTicks']) + ' && '
              'test "$(sha256sum ' + proc + '/cmdline)" = "' + owner['cmdlineSha256'] + '  ' + proc + '/cmdline" && '
              'kill -INT ' + str(pid))
    return prefix + ['shell', 'sh', '-c', shlex.quote(script)]


def expected_cmdline(report):
    return [part.encode() for part in ['/system/bin/perfetto', '--txt', '-c', '-', '-o',
                                     report['trace']['paths']['trace'], '--background-wait']]


def validate_owner_cmdline(data, report):
    require(data.endswith(b'\0') and data.split(b'\0')[:-1] == expected_cmdline(report), 'RESET_OWNER_CMDLINE')


def bounded_input_capture(argv, seconds, limit, input_data):
    """Write the finite config to a real pipe, close EOF, and drain output.

    Both directions share the original command deadline. An inherited regular
    file descriptor is not used: the Android consumer receives adb's stdin
    stream, as in the previously verified app-experience capture interface.
    writtenBytes counts host pipe writes, not a claim about device consumption.
    """
    require(type(input_data) is bytes and 0 < len(input_data) <= MAX_CONFIG_BYTES, 'RESET_STDIN_BOUND')
    receipt = {'writtenBytes': 0, 'eofSent': False}
    if w.common.CANCELLED:
        return b'', {'status': 'cancelled', 'exitCode': None, 'exceptionType': None}, receipt
    output = bytearray(); process = None; outcome = 'completed'; exception = None; code = None
    deadline = time.monotonic() + seconds
    try:
        process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, start_new_session=True, bufsize=0)
        os.set_blocking(process.stdin.fileno(), False)
        os.set_blocking(process.stdout.fileno(), False)
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdin, selectors.EVENT_WRITE, 'stdin')
            selector.register(process.stdout, selectors.EVENT_READ, 'stdout')
            abort = False
            while selector.get_map() or process.poll() is None:
                if w.common.CANCELLED or time.monotonic() >= deadline:
                    outcome = 'cancelled' if w.common.CANCELLED else 'timed_out'
                    exception = None; break
                for key, _ in selector.select(min(.1, max(0, deadline - time.monotonic()))):
                    if key.data == 'stdin':
                        try:
                            written = os.write(key.fd, input_data[receipt['writtenBytes']:])
                            if written <= 0: raise BrokenPipeError('stdin write made no progress')
                            receipt['writtenBytes'] += written
                            if receipt['writtenBytes'] == len(input_data):
                                selector.unregister(key.fileobj); process.stdin.close()
                                receipt['eofSent'] = True
                        except BlockingIOError:
                            continue
                        except OSError as error:
                            # Keep draining available child diagnostics after a
                            # failed write, within the same time/output budget.
                            outcome = 'exception'; exception = type(error).__name__
                            selector.unregister(key.fileobj); process.stdin.close()
                    else:
                        try: chunk = os.read(key.fd, 16384)
                        except BlockingIOError: continue
                        if not chunk: selector.unregister(key.fileobj); continue
                        left = limit - len(output); output.extend(chunk[:left])
                        if len(chunk) > left:
                            outcome = 'output_limit'; exception = None; abort = True; break
                if abort: break
    except Exception as error:
        outcome = 'exception'; exception = type(error).__name__
    finally:
        if process is not None:
            # A group member can retain stdout after the leader exits. Always
            # clean up our group, rather than checking only the leader's poll.
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            except OSError as error:
                if outcome == 'completed': outcome, exception = 'exception', type(error).__name__
            for stream in (process.stdin, process.stdout):
                if stream is not None and not stream.closed:
                    try: stream.close()
                    except OSError as error:
                        if outcome == 'completed': outcome, exception = 'exception', type(error).__name__
            try: code = process.wait(timeout=1)
            except subprocess.TimeoutExpired: code = None
    if outcome == 'completed' and w.common.CANCELLED: outcome = 'cancelled'
    return bytes(output), {'status': outcome, 'exitCode': code, 'exceptionType': exception}, receipt


def stream_trace(argv, seconds, limit, destination):
    """Retain a bounded original file; the adb writer itself gets RLIMIT_FSIZE."""
    destination = Path(destination); require(not destination.exists() and not destination.is_symlink(), 'RESET_TRACE_OUTPUT_EXISTS')
    diagnostic = bytearray(); process = None; outcome = 'completed'; exception = None; code = None
    started = time.monotonic()
    def restrict(): resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_TRACE_BYTES, MAX_TRACE_BYTES))
    try:
        with destination.open('xb') as output:
            process = subprocess.Popen(argv, stdout=output, stderr=subprocess.PIPE, start_new_session=True, preexec_fn=restrict)
            with selectors.DefaultSelector() as selector:
                selector.register(process.stderr, selectors.EVENT_READ)
                while selector.get_map():
                    if w.common.CANCELLED or time.monotonic() - started >= seconds:
                        outcome = 'cancelled' if w.common.CANCELLED else 'timed_out'; break
                    for key, _ in selector.select(.1):
                        chunk = os.read(key.fd, 16384)
                        if not chunk: selector.unregister(key.fd); continue
                        left = limit - len(diagnostic); diagnostic.extend(chunk[:left])
                        if len(chunk) > left: outcome = 'output_limit'; break
                    if outcome != 'completed': break
            if outcome != 'completed' and process.poll() is None: os.killpg(process.pid, signal.SIGKILL)
            try: code = process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL); code = process.wait(timeout=2); outcome = 'timed_out'
            output.flush(); os.fsync(output.fileno())
    except Exception as error:
        outcome = 'exception'; exception = type(error).__name__
    finally:
        if process is not None:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=2)
            if process.stderr is not None: process.stderr.close()
    return bytes(diagnostic), {'status': outcome, 'exitCode': code, 'exceptionType': exception}


class CommandRunner:
    def __init__(self, directory, source, execute=w.common.bounded_capture, stream=stream_trace,
                 input_execute=bounded_input_capture):
        self.directory, self.source, self.execute, self.stream = Path(directory), source, execute, stream
        self.input_execute = input_execute
        self.commands = decode_json(w.read_bounded(self.directory / 'commands.json', MAX_COMMAND_BYTES))['commands']

    def flush(self):
        write_json(self.directory / 'commands.json', {'schemaVersion': 1, 'kind': KIND + '-commands',
                                                    'source': self.source, 'commands': self.commands})

    def call(self, name, argv):
        require(name in COMMAND_IDS and name not in [c['id'] for c in self.commands]
                and len(self.commands) < MAX_COMMANDS, 'RESET_COMMAND_ID')
        seconds, limit = command_limits(name)
        require(sum(c['bytes'] for c in self.commands) + limit <= MAX_TOTAL_OUTPUT, 'RESET_COMMAND_BUDGET')
        item = {'ordinal': len(self.commands) + 1, 'id': name, 'argv': list(argv),
                'timeoutSeconds': seconds, 'byteLimit': limit, 'startedAt': now(), 'endedAt': None,
                'elapsedMillis': 0, 'status': 'running', 'exitCode': None, 'exceptionType': None,
                **w.identity(b''), 'outputBase64': '', 'streamFile': None, 'stdin': None}
        if name == 'start_trace':
            item['stdin'] = {'path': 'trace-config.pbtxt', **w.identity(trace_config()),
                             'writtenBytes': 0, 'eofSent': False}
        self.commands.append(item); self.flush(); started = time.monotonic()
        try:
            if name == 'start_trace':
                config = w.read_bounded(self.directory / 'trace-config.pbtxt', MAX_CONFIG_BYTES)
                require(config == trace_config(), 'RESET_STDIN_CONFIG_CHANGED')
                data, result, receipt = self.input_execute(argv, seconds, limit, config)
                require(type(receipt) is dict and set(receipt) == {'writtenBytes', 'eofSent'}
                        and w.integer(receipt['writtenBytes'], 0, len(config))
                        and type(receipt['eofSent']) is bool
                        and (not receipt['eofSent'] or receipt['writtenBytes'] == len(config)), 'RESET_STDIN_RECEIPT')
                item['stdin'].update(receipt)
            elif name == 'trace_pull':
                data, result = self.stream(argv, seconds, limit, self.directory / 'trace.pftrace')
                if (self.directory / 'trace.pftrace').is_file():
                    item['streamFile'] = {'path': 'trace.pftrace', **w.identity(w.read_bounded(self.directory / 'trace.pftrace', MAX_TRACE_BYTES))}
            else: data, result = self.execute(argv, seconds, limit)
        except Exception as error:
            data, result = b'', {'status': 'exception', 'exitCode': None, 'exceptionType': type(error).__name__}
        require(type(data) is bytes and len(data) <= limit, 'RESET_COMMAND_OUTPUT')
        item.update(**w.identity(data), outputBase64=base64.b64encode(data).decode(), endedAt=now(),
                    elapsedMillis=int((time.monotonic() - started) * 1000), status=result['status'],
                    exitCode=result.get('exitCode'), exceptionType=result.get('exceptionType'))
        self.flush(); return data, item


def _summary(directory):
    return comparison_summary(k.tree_read(Path(directory) / 'comparison')['report.json'])


def _stop_and_collect(directory, source, report, runner, save, sleep=time.sleep):
    trace = report['trace']
    if not trace['startAttempted'] or trace['owner'] is None or trace['owner']['cmdlineSha256'] is None: return
    if trace['stopAttempted']: return
    def checked(name):
        data, command = runner.call(name, command_argv(name, report))
        require(w.command_ok(command), 'RESET_COMMAND_FAILED_' + name)
        return data
    owner = trace['owner']; report['stage'] = 'stopping'; save()
    before = parse_stat(checked('stop_stat'), owner['pid'])
    if before is None or before['startTimeTicks'] != owner['startTimeTicks'] or before['state'] == 'Z':
        # The producer ended before our stop. Preserve its bounded original
        # output without signaling a reused PID or calling this complete.
        trace['exitObserved'] = True; trace['exitReason'] = 'BEFORE_STOP'
        if report['failureReason'] is None: report['failureReason'] = 'RESET_PROCESS_EXITED_OR_REUSED_BEFORE_STOP'
        save()
    else:
        require(before['comm'] == 'perfetto', 'RESET_STOP_OWNER_NAME')
        validate_owner_cmdline(checked('stop_cmdline'), report)
        trace['stopAttempted'] = True; trace['stopAttemptedAt'] = now(); save(True)
        checked('stop_trace')
        for index in range(STOP_POLLS):
            current = parse_stat(checked('stop_poll_' + str(index)), owner['pid'])
            if current is None or current['startTimeTicks'] != owner['startTimeTicks'] or current['state'] == 'Z':
                trace['stopConfirmed'] = True; trace['exitObserved'] = True
                trace['exitReason'] = 'AFTER_OWN_STOP'; save(); break
            sleep(.5)
        require(trace['stopConfirmed'], 'RESET_STOP_NOT_CONFIRMED')
    size_text = checked('trace_size').strip()
    require(re.fullmatch(rb'0|[1-9][0-9]{0,18}', size_text), 'RESET_TRACE_SIZE')
    trace['remoteBytes'] = int(size_text); save()
    resource_limited = not 0 < trace['remoteBytes'] < MAX_TRACE_BYTES
    if resource_limited and report['failureReason'] is None:
        report['failureReason'] = 'RESET_TRACE_RESOURCE_LIMIT'; save()
    report['stage'] = 'pulling'; trace['pullAttempted'] = True; save(True)
    _, pull = runner.call('trace_pull', command_argv('trace_pull', report))
    trace['file'] = copy.deepcopy(pull['streamFile']); save()
    if not resource_limited:
        require(w.command_ok(pull) and trace['file'] is not None
                and trace['file']['bytes'] == trace['remoteBytes'], 'RESET_TRACE_PULL_INCOMPLETE')
        require(checked('trace_digest').decode().strip() == trace['file']['sha256'] + '  ' + trace['paths']['trace'], 'RESET_TRACE_DIGEST')
    checked('cleanup_trace'); checked('cleanup_config'); checked('cleanup_absent')
    trace['cleanupConfirmed'] = True; save()


def run_probe(args, source, runner_factory=CommandRunner, comparison_runner=k.run_probe, sleep=time.sleep):
    directory = Path(args.output); payloads = read_payloads(directory)
    report = validate_evidence(payloads, source)
    require(not report['runAttempted'] and report['status'] == 'unverified'
            and equal(report['sourceFiles'], source_files()), 'RESET_RUN_STATE_OR_SOURCE')
    sdk = Path(args.sdk_root).resolve()
    report.update(runAttempted=True, stage='preflight', failureReason=None,
                  tools={'adb': str(sdk / 'platform-tools/adb'), 'sdkRoot': str(sdk),
                         'config': str((directory / 'trace-config.pbtxt').resolve())})
    def save(durable=False): write_json(directory / 'report.json', report, durable)
    save(); runner = runner_factory(directory, source)
    def checked(name):
        data, command = runner.call(name, command_argv(name, report))
        require(command_accepted(command), 'RESET_PERFETTO_HELP_UNSUPPORTED_OR_INCOMPLETE'
                if name == 'perfetto_help' else 'RESET_COMMAND_FAILED_' + name)
        return data
    try:
        report['device'] = {'serial': w.devices.select_emulator(checked('devices').decode().strip())}; save()
        for name, expected in [('api', '37'), ('qemu', '1'), ('current_user', '0'), ('shell_uid', '2000')]:
            require(checked(name).decode().strip() == expected, 'RESET_PREFLIGHT_' + name.upper())
        require(b'--background-wait' in checked('perfetto_help'), 'RESET_BACKGROUND_WAIT_UNSUPPORTED')
        checked('config_absent'); checked('trace_absent'); checked('push_config')
        require(checked('config_digest').decode().strip() == w.sha(trace_config()) + '  ' + report['trace']['paths']['config'], 'RESET_CONFIG_DIGEST')
        report['stage'] = 'starting'; report['trace']['startAttempted'] = True
        report['trace']['startAttemptedAt'] = now(); save(True)
        pid = parse_pid(checked('start_trace'))
        report['trace']['owner'] = {'pid': pid, 'startTimeTicks': None, 'cmdlineSha256': None}; save(True)
        info = parse_stat(checked('start_stat'), pid)
        require(info is not None and info['comm'] == 'perfetto' and info['state'] != 'Z' and info['startTimeTicks'] > 0, 'RESET_START_IDENTITY')
        report['trace']['owner']['startTimeTicks'] = info['startTimeTicks']; save(True)
        cmdline = checked('start_cmdline'); validate_owner_cmdline(cmdline, report)
        report['trace']['owner']['cmdlineSha256'] = w.sha(cmdline); save(True)
        checked('ready_file')
        report['stage'] = 'comparison'; save()
        child_args = SimpleNamespace(**vars(args)); child_args.output = directory / 'comparison'
        passed = comparison_runner(child_args, source)
        k.finalize(directory / 'comparison', source, 'success' if passed else 'failure')
        report['comparison'] = _summary(directory); save()
    except Exception as error:
        report['failureReason'] = str(error) if isinstance(error, Rejected) else 'HOST_' + type(error).__name__
    finally:
        # Restoration runs while the emulator is still alive, even if the
        # comparison call escaped before returning its normal receipt.
        try:
            k.restore(directory / 'comparison', source)
            child = k.tree_read(directory / 'comparison')['report.json']
            if child['emulatorStepOutcome'] is None:
                k.finalize(directory / 'comparison', source, 'failure')
        except Exception as error:
            if report['failureReason'] is None:
                report['failureReason'] = str(error) if isinstance(error, Rejected) else 'HOST_' + type(error).__name__
        try: report['comparison'] = _summary(directory)
        except Exception:
            if report['failureReason'] is None: report['failureReason'] = 'RESET_COMPARISON_RECEIPT_UNREADABLE'
        cancelled = w.common.CANCELLED; w.common.CANCELLED = False
        try: _stop_and_collect(directory, source, report, runner, save, sleep)
        except Exception as error:
            if report['failureReason'] is None:
                report['failureReason'] = str(error) if isinstance(error, Rejected) else 'HOST_' + type(error).__name__
        finally: w.common.CANCELLED = cancelled
        try:
            report['comparison'] = _summary(directory)
            child = k.tree_read(directory / 'comparison')
            complete_arms = all(k.arm_comparable(k.arm_payloads(child, arm)) for arm in k.ARMS)
            if report['failureReason'] is None and not complete_arms: report['failureReason'] = 'RESET_COMPARISON_INCOMPLETE'
            if child['report.json']['restoration']['status'] != 'confirmed':
                if report['failureReason'] is None: report['failureReason'] = 'RESET_SETTING_RESTORATION_NOT_CONFIRMED'
        except Exception:
            if report['failureReason'] is None: report['failureReason'] = 'RESET_COMPARISON_RECEIPT_UNREADABLE'
        if not report['trace']['cleanupConfirmed'] and report['failureReason'] is None: report['failureReason'] = 'RESET_CAPTURE_NOT_COMPLETE'
        report['status'] = 'recorded' if report['failureReason'] is None else 'unverified'
        report['stage'] = 'complete'; report['completedAt'] = now(); save()
    validate_evidence(read_payloads(directory), source)
    return report['status'] == 'recorded'


def validate_commands(value, report):
    w.exact(value, {'schemaVersion', 'kind', 'source', 'commands'}, 'RESET_COMMANDS_FIELDS')
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1
            and value['kind'] == KIND + '-commands' and equal(value['source'], report['source']), 'RESET_COMMANDS_SOURCE')
    entries = value['commands']; require(type(entries) is list and len(entries) <= MAX_COMMANDS, 'RESET_COMMAND_COUNT')
    keys = {'ordinal', 'id', 'argv', 'timeoutSeconds', 'byteLimit', 'startedAt', 'endedAt',
            'elapsedMillis', 'status', 'exitCode', 'exceptionType', 'bytes', 'sha256', 'outputBase64', 'streamFile', 'stdin'}
    result = {}; position = -1; end = w.common.timestamp(report['createdAt']); total = 0
    for ordinal, item in enumerate(entries, 1):
        w.exact(item, keys, 'RESET_COMMAND_FIELDS'); name = item['id']
        require(name in COMMAND_IDS and COMMAND_IDS.index(name) > position
                and item['ordinal'] == ordinal and type(item['ordinal']) is int, 'RESET_COMMAND_ORDER')
        position = COMMAND_IDS.index(name)
        require(equal(item['argv'], command_argv(name, report)), 'RESET_COMMAND_ARGV')
        seconds, limit = command_limits(name)
        require(type(item['timeoutSeconds']) is int and item['timeoutSeconds'] == seconds
                and type(item['byteLimit']) is int and item['byteLimit'] == limit, 'RESET_COMMAND_LIMITS')
        require(item['status'] in {'running', 'completed', 'timed_out', 'output_limit', 'cancelled', 'exception'}
                and (item['exitCode'] is None or w.integer(item['exitCode'], -(2**31), 2**31 - 1))
                and w.integer(item['elapsedMillis']), 'RESET_COMMAND_STATUS')
        require((item['status'] == 'exception') == (item['exceptionType'] is not None)
                and (item['exceptionType'] is None or re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,79}', str(item['exceptionType']))), 'RESET_COMMAND_EXCEPTION')
        start = w.common.timestamp(item['startedAt']); require(start >= end, 'RESET_COMMAND_OVERLAP')
        if item['endedAt'] is None: require(item['status'] == 'running' and report['status'] != 'recorded', 'RESET_COMMAND_UNFINISHED'); end = start
        else: end = w.common.timestamp(item['endedAt']); require(end >= start, 'RESET_COMMAND_TIME')
        require(report['completedAt'] is None or end <= w.common.timestamp(report['completedAt']), 'RESET_COMMAND_AFTER_REPORT')
        require(type(item['outputBase64']) is str and len(item['outputBase64']) <= 4 * ((limit + 2) // 3), 'RESET_COMMAND_BASE64_LIMIT')
        data = w.command_data(item)
        require(len(data) <= limit and equal(w.identity(data), {'bytes': item['bytes'], 'sha256': item['sha256']}), 'RESET_COMMAND_BYTES')
        require(item['streamFile'] is None or name == 'trace_pull', 'RESET_COMMAND_STREAM')
        if name == 'start_trace':
            stdin = item['stdin']; w.exact(stdin, {'path', 'bytes', 'sha256', 'writtenBytes', 'eofSent'}, 'RESET_STDIN_FIELDS')
            require(stdin['path'] == 'trace-config.pbtxt'
                    and equal({key: stdin[key] for key in ('bytes', 'sha256')}, w.identity(trace_config()))
                    and w.integer(stdin['writtenBytes'], 0, stdin['bytes']) and type(stdin['eofSent']) is bool
                    and (not stdin['eofSent'] or stdin['writtenBytes'] == stdin['bytes']), 'RESET_STDIN_SOURCE')
        else: require(item['stdin'] is None, 'RESET_UNEXPECTED_STDIN')
        if item['streamFile'] is not None:
            w.exact(item['streamFile'], {'path', 'bytes', 'sha256'}, 'RESET_STREAM_FIELDS')
            require(item['streamFile']['path'] == 'trace.pftrace', 'RESET_STREAM_PATH')
            require(w.integer(item['streamFile']['bytes'], 0, MAX_TRACE_BYTES)
                    and re.fullmatch('[0-9a-f]{64}', str(item['streamFile']['sha256'])), 'RESET_STREAM_IDENTITY')
        total += len(data); result[name] = item
    require(total <= MAX_TOTAL_OUTPUT, 'RESET_COMMAND_TOTAL')
    return result


def validate_evidence(payloads, source=None):
    require(type(payloads) is dict and set(payloads) <= FILES
            and {'report.json', 'commands.json', 'trace-config.pbtxt'} <= set(payloads), 'RESET_PAYLOADS')
    report = payloads['report.json']; source = w.validate_source(report['source'] if source is None else source)
    w.exact(report, set(empty_report(source, {})), 'RESET_REPORT_FIELDS')
    require(type(report['schemaVersion']) is int and report['schemaVersion'] == 1 and report['kind'] == KIND
            and equal(report['source'], source) and equal(report['scope'], SCOPE), 'RESET_REPORT_CONTRACT')
    require(report['status'] in {'unverified', 'recorded'} and report['stage'] in
            {'prepared', 'preflight', 'starting', 'comparison', 'stopping', 'pulling', 'complete', 'finalized'}, 'RESET_REPORT_STATE')
    require(type(report['runAttempted']) is bool and report['emulatorStepOutcome'] in
            {None, 'success', 'failure', 'cancelled', 'skipped', 'unknown'}, 'RESET_REPORT_ATTEMPTS')
    require(report['failureReason'] is None or (type(report['failureReason']) is str and re.fullmatch(r'[A-Z][A-Za-z0-9_]{0,127}', report['failureReason'])), 'RESET_FAILURE_REASON')
    require(report['emulatorTeardown'] == 'WORKFLOW_RESPONSIBILITY_NOT_OBSERVED', 'RESET_TEARDOWN_SCOPE')
    created = w.common.timestamp(report['createdAt'])
    if report['completedAt'] is not None: require(w.common.timestamp(report['completedAt']) >= created, 'RESET_REPORT_TIME')
    require(type(report['sourceFiles']) is dict and set(report['sourceFiles']) == set(source_files()), 'RESET_SOURCE_FILES')
    for identity in report['sourceFiles'].values(): w.common.validate_identity(identity)
    children = comparison_payloads(payloads); child = k.validate_evidence(children, source)
    require(equal(child['sourceFiles'], {name: value for name, value in report['sourceFiles'].items() if name not in EXTRA_SOURCES}), 'RESET_CHILD_SOURCE_FILES')
    if report['completedAt'] is not None or report['stage'] not in {'comparison', 'stopping', 'pulling'}:
        require(equal(report['comparison'], comparison_summary(child)), 'RESET_COMPARISON_SUMMARY')
    if report['tools'] is not None:
        w.exact(report['tools'], {'adb', 'sdkRoot', 'config'}, 'RESET_TOOLS')
        require(all(type(v) is str and v.startswith('/') and len(v) <= 1024 and '\x00' not in v for v in report['tools'].values())
                and report['tools']['adb'] == str(Path(report['tools']['sdkRoot']) / 'platform-tools/adb')
                and Path(report['tools']['config']).name == 'trace-config.pbtxt', 'RESET_TOOL_PATHS')
    if report['device'] is not None:
        w.exact(report['device'], {'serial'}, 'RESET_DEVICE')
        require(re.fullmatch(r'emulator-[0-9]{4,5}', str(report['device']['serial'])), 'RESET_SERIAL')
    trace = report['trace']; w.exact(trace, set(empty_report(source, {})['trace']), 'RESET_TRACE_FIELDS')
    require(equal(trace['paths'], remote_paths(source)) and payloads['trace-config.pbtxt'] == trace_config()
            and equal(trace['config'], w.identity(trace_config())), 'RESET_CONFIG_SOURCE')
    for key in ('startAttempted', 'stopAttempted', 'stopConfirmed', 'exitObserved', 'pullAttempted', 'cleanupConfirmed'):
        require(type(trace[key]) is bool, 'RESET_TRACE_FLAG')
    require(trace['exitReason'] in {None, 'BEFORE_STOP', 'AFTER_OWN_STOP'}
            and trace['exitObserved'] == (trace['exitReason'] is not None), 'RESET_EXIT_REASON')
    for stem in ('start', 'stop'):
        require(trace[stem + 'Attempted'] == (trace[stem + 'AttemptedAt'] is not None), 'RESET_TRACE_INTENT_TIME')
        if trace[stem + 'AttemptedAt'] is not None: require(w.common.timestamp(trace[stem + 'AttemptedAt']) >= created, 'RESET_TRACE_INTENT_ORDER')
    owner = trace['owner']
    if owner is not None:
        w.exact(owner, {'pid', 'startTimeTicks', 'cmdlineSha256'}, 'RESET_OWNER_FIELDS')
        require(w.integer(owner['pid'], 2, 999999999) and (owner['startTimeTicks'] is None or w.integer(owner['startTimeTicks'], 1))
                and (owner['cmdlineSha256'] is None or re.fullmatch('[0-9a-f]{64}', str(owner['cmdlineSha256']))), 'RESET_OWNER_VALUES')
    commands = validate_commands(payloads['commands.json'], report)
    if commands: require(report['runAttempted'] and report['tools'] is not None, 'RESET_UNREPORTED_RUN')
    if trace['startAttempted']:
        needed = COMMAND_IDS[:COMMAND_IDS.index('start_trace')]
        require(all(name in commands and command_accepted(commands[name]) for name in needed), 'RESET_START_PREFLIGHT')
        require(w.devices.select_emulator(w.command_text(commands['devices'])) == report['device']['serial'], 'RESET_DEVICE_SOURCE')
        for name, expected in [('api', '37'), ('qemu', '1'), ('current_user', '0'), ('shell_uid', '2000')]:
            require(w.command_text(commands[name]) == expected, 'RESET_PREFLIGHT_SOURCE')
        require(b'--background-wait' in w.command_data(commands['perfetto_help'])
                and w.command_text(commands['config_digest']) == w.sha(trace_config()) + '  ' + trace['paths']['config'], 'RESET_READY_CONTRACT')
    for name in ('start_trace', 'stop_trace'):
        if name in commands:
            stem = name.split('_')[0]
            require(trace[stem + 'Attempted'] and w.common.timestamp(trace[stem + 'AttemptedAt']) <= w.common.timestamp(commands[name]['startedAt']), 'RESET_COMMAND_WITHOUT_INTENT')
    if owner is not None:
        require('start_trace' in commands and command_accepted(commands['start_trace']) and parse_pid(w.command_data(commands['start_trace'])) == owner['pid'], 'RESET_OWNER_PID_SOURCE')
        if owner['startTimeTicks'] is not None:
            require('start_stat' in commands and w.command_ok(commands['start_stat']), 'RESET_OWNER_STAT_SOURCE')
            info = parse_stat(w.command_data(commands['start_stat']), owner['pid'])
            require(info is not None and info['comm'] == 'perfetto' and info['state'] != 'Z' and info['startTimeTicks'] == owner['startTimeTicks'], 'RESET_OWNER_START_SOURCE')
        if owner['cmdlineSha256'] is not None:
            require('start_cmdline' in commands and w.command_ok(commands['start_cmdline']), 'RESET_OWNER_CMDLINE_SOURCE')
            data = w.command_data(commands['start_cmdline']); validate_owner_cmdline(data, report)
            require(w.sha(data) == owner['cmdlineSha256'], 'RESET_OWNER_DIGEST_SOURCE')
    if trace['stopAttempted']:
        require(owner is not None and owner['cmdlineSha256'] is not None and 'stop_stat' in commands and 'stop_cmdline' in commands
                and all(w.command_ok(commands[n]) for n in ('stop_stat', 'stop_cmdline')), 'RESET_STOP_IDENTITY_SOURCE')
        info = parse_stat(w.command_data(commands['stop_stat']), owner['pid'])
        require(info is not None and info['comm'] == 'perfetto' and info['state'] != 'Z' and info['startTimeTicks'] == owner['startTimeTicks'], 'RESET_STOP_OWNER_REUSED')
        validate_owner_cmdline(w.command_data(commands['stop_cmdline']), report)
    polls = [commands[n] for n in COMMAND_IDS if n.startswith('stop_poll_') and n in commands]
    if trace['stopConfirmed']:
        require(trace['stopAttempted'] and 'stop_trace' in commands and w.command_ok(commands['stop_trace']) and polls and w.command_ok(polls[-1]), 'RESET_STOP_CONFIRMATION')
        final = parse_stat(w.command_data(polls[-1]), owner['pid'])
        require(final is None or final['startTimeTicks'] != owner['startTimeTicks'] or final['state'] == 'Z', 'RESET_STOP_STILL_RUNNING')
        require(trace['exitObserved'] and trace['exitReason'] == 'AFTER_OWN_STOP', 'RESET_STOP_EXIT_SOURCE')
    if trace['exitReason'] == 'BEFORE_STOP':
        require(owner is not None and owner['cmdlineSha256'] is not None and 'stop_stat' in commands
                and w.command_ok(commands['stop_stat']) and not trace['stopAttempted']
                and not trace['stopConfirmed'] and report['status'] != 'recorded', 'RESET_EARLY_EXIT_SOURCE')
        before = parse_stat(w.command_data(commands['stop_stat']), owner['pid'])
        require(before is None or before['startTimeTicks'] != owner['startTimeTicks'] or before['state'] == 'Z', 'RESET_EARLY_EXIT_STILL_RUNNING')
    if trace['exitReason'] == 'AFTER_OWN_STOP': require(trace['stopConfirmed'], 'RESET_EXIT_WITHOUT_STOP')
    if trace['remoteBytes'] is not None:
        require(w.integer(trace['remoteBytes'], 0) and 'trace_size' in commands and w.command_ok(commands['trace_size'])
                and w.command_data(commands['trace_size']).strip() == str(trace['remoteBytes']).encode(), 'RESET_SIZE_SOURCE')
    if 'trace_pull' in commands: require(trace['pullAttempted'] and trace['exitObserved'], 'RESET_PULL_BEFORE_STOP')
    stream = commands.get('trace_pull', {}).get('streamFile')
    require(equal(trace['file'], stream), 'RESET_TRACE_FILE_RECEIPT')
    if 'trace.pftrace' in payloads:
        require(stream is not None and type(payloads['trace.pftrace']) is bytes and len(payloads['trace.pftrace']) <= MAX_TRACE_BYTES
                and equal(w.identity(payloads['trace.pftrace']), {key: stream[key] for key in ('bytes', 'sha256')}), 'RESET_TRACE_ORIGINAL_BYTES')
    else: require(stream is None, 'RESET_TRACE_ORIGINAL_MISSING')
    if trace['cleanupConfirmed']:
        require(trace['exitObserved'] and all(n in commands and w.command_ok(commands[n]) for n in ('cleanup_trace', 'cleanup_config', 'cleanup_absent')), 'RESET_CLEANUP_SOURCE')
    if report['status'] == 'recorded':
        required = set(COMMAND_IDS[:COMMAND_IDS.index('stop_trace') + 1]) | {'trace_size', 'trace_pull', 'trace_digest', 'cleanup_trace', 'cleanup_config', 'cleanup_absent'}
        require(report['runAttempted'] and report['failureReason'] is None and report['completedAt'] is not None
                and report['emulatorStepOutcome'] in {None, 'success'} and required <= set(commands)
                and all(command_accepted(item) for item in commands.values()), 'RESET_RECORDED_COMMANDS')
        require(trace['stopConfirmed'] and trace['cleanupConfirmed'] and trace['file'] is not None
                and 0 < trace['file']['bytes'] < MAX_TRACE_BYTES and trace['file']['bytes'] == trace['remoteBytes'], 'RESET_RECORDED_FILE')
        require(w.command_data(commands['trace_digest']).decode().strip() == trace['file']['sha256'] + '  ' + trace['paths']['trace'], 'RESET_RECORDED_REMOTE_DIGEST')
        require(all(k.arm_comparable(k.arm_payloads(children, arm)) for arm in k.ARMS)
                and child['restoration']['status'] == 'confirmed', 'RESET_RECORDED_COMPARISON')
        require(w.common.timestamp(commands['ready_file']['endedAt']) <= w.common.timestamp(children['commands.json']['commands'][0]['startedAt'])
                and child['completedAt'] is not None
                and w.common.timestamp(commands['stop_stat']['startedAt']) >= w.common.timestamp(child['completedAt']), 'RESET_TRACE_COMPARISON_ORDER')
    return report


def finalize(directory, source, outcome, runner_factory=CommandRunner):
    directory = Path(directory); payloads = read_payloads(directory)
    if payloads['report.json'].get('completedAt') is None:
        payloads['report.json']['comparison'] = comparison_summary(k.validate_evidence(comparison_payloads(payloads), source))
    report = validate_evidence(payloads, source)
    # Normal run_probe already restored the setting and stopped its trace. The
    # finalizer must retain the nested run outcome instead of replacing a failed
    # comparison merely because capture itself succeeded.
    child = k.tree_read(directory / 'comparison')['report.json']
    if child['emulatorStepOutcome'] is None:
        k.finalize(directory / 'comparison', source, outcome)
    report['comparison'] = _summary(directory)
    if report['runAttempted'] and report['completedAt'] is None:
        report['status'] = 'unverified'; report['failureReason'] = 'RESET_RUN_INTERRUPTED'
        if report['trace']['owner'] is not None and not report['trace']['stopAttempted']:
            runner = runner_factory(directory, source)
            def save(durable=False): write_json(directory / 'report.json', report, durable)
            cancelled = w.common.CANCELLED; w.common.CANCELLED = False
            try: _stop_and_collect(directory, source, report, runner, save)
            except Exception: pass
            finally: w.common.CANCELLED = cancelled
    if report['status'] == 'recorded' and outcome != 'success':
        report['status'] = 'unverified'; report['failureReason'] = 'RESET_WORKFLOW_NOT_SUCCESS'
    report['emulatorStepOutcome'] = outcome; report['stage'] = 'finalized'; report['completedAt'] = now()
    write_json(directory / 'report.json', report)
    return validate_evidence(read_payloads(directory), source)


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='command', required=True)
    for name in ('prepare', 'run', 'finalize'):
        child = sub.add_parser(name); child.add_argument('--output', type=Path, required=True)
        if name in ('run', 'finalize'): child.add_argument('--sdk-root', type=Path, required=name == 'run')
        if name == 'run':
            child.add_argument('--fixture-apk', type=Path, required=True); child.add_argument('--probe-jar', type=Path, required=True)
            child.add_argument('--avd-name', required=True); child.add_argument('--avd-home', type=Path)
        if name == 'finalize': child.add_argument('--outcome', choices=['success', 'failure', 'cancelled', 'skipped', 'unknown'], required=True)
    args = parser.parse_args(); source = w.source_from_environment()
    def cancel(_signal, _frame): w.CANCELLED = True; w.common.CANCELLED = True
    signal.signal(signal.SIGINT, cancel); signal.signal(signal.SIGTERM, cancel)
    if args.command == 'prepare': prepare(args.output, source)
    elif args.command == 'run': return 0 if run_probe(args, source) else 1
    else: finalize(args.output, source, args.outcome)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
