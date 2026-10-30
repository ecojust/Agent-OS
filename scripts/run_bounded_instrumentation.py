#!/usr/bin/env python3
"""Bound one Linux CI instrumentation invocation; never synthesize test results."""

import argparse
import ctypes
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import time

DIAGNOSTIC_SECONDS = 60
MAX_COMMAND_BYTES = 256 * 1024
MAX_DIAGNOSTIC_BYTES = 2 * 1024 * 1024
cancel_signal = 0


def cancelled(signum, _frame):
    global cancel_signal
    cancel_signal = cancel_signal or signum


def descendants(parent):
    """PIDs only: do not read or publish command lines or environment variables."""
    parents = {}
    for path in Path('/proc').glob('[0-9]*/stat'):
        try:
            fields = path.read_text().rsplit(')', 1)[1].split()
            parents[int(path.parent.name)] = int(fields[1])
        except (OSError, ValueError, IndexError):
            continue
    found = set()
    pending = {parent}
    while pending:
        children = {pid for pid, ppid in parents.items() if ppid in pending} - found
        found.update(children)
        pending = children
    return found


def send_signal(pid, signum, group=False):
    try:
        (os.killpg if group else os.kill)(pid, signum)
    except ProcessLookupError:
        pass


def stop_processes(process, grace):
    # Subreaper adoption retains even detached single-use Gradle daemons. Recheck
    # during grace so a terminating parent cannot leave newly orphaned children.
    started = time.monotonic()
    known = descendants(os.getpid())
    send_signal(process.pid, signal.SIGTERM, group=True)
    for pid in known:
        send_signal(pid, signal.SIGTERM)
    while time.monotonic() - started < grace:
        process.poll()
        while True:
            try:
                reaped, _ = os.waitpid(-1, os.WNOHANG)
            except ChildProcessError:
                break
            if not reaped:
                break
        current = descendants(os.getpid())
        if not current:
            break
        for pid in current - known:
            send_signal(pid, signal.SIGTERM)
        known.update(current)
        time.sleep(0.05)
    send_signal(process.pid, signal.SIGKILL, group=True)
    for pid in descendants(os.getpid()):
        send_signal(pid, signal.SIGKILL)
    try:
        process.wait(timeout=1)
    except subprocess.TimeoutExpired:
        pass
    # Reap adopted children without waiting indefinitely on a kernel-blocked task.
    reap_deadline = time.monotonic() + 1
    while time.monotonic() < reap_deadline:
        try:
            pid, _ = os.waitpid(-1, os.WNOHANG)
        except ChildProcessError:
            break
        if not pid:
            time.sleep(0.02)
    return sorted(descendants(os.getpid()))


def bounded_capture(command, seconds, byte_limit):
    """Drain bounded bytes and terminate the diagnostic itself on time/size limits."""
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               start_new_session=True)
    output = bytearray()
    status = 'completed'
    deadline = time.monotonic() + seconds
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        while selector.get_map():
            if cancel_signal or time.monotonic() >= deadline:
                status = 'cancelled' if cancel_signal else 'timed_out'
                break
            for key, _ in selector.select(min(0.1, max(0, deadline - time.monotonic()))):
                chunk = os.read(key.fd, min(65536, byte_limit - len(output) + 1))
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                available = byte_limit - len(output)
                output.extend(chunk[:available])
                if len(chunk) > available or len(output) >= byte_limit:
                    status = 'output_limit'
                    break
            if status != 'completed':
                break
    process.stdout.close()
    if status == 'completed':
        try:
            process.wait(timeout=max(0.001, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            status = 'timed_out'
    send_signal(process.pid, signal.SIGKILL, group=True)
    try:
        # At most one extra second for reaping the final diagnostic command.
        code = process.wait(timeout=1)
    except subprocess.TimeoutExpired:
        code = None
    return bytes(output), {'status': status, 'exitCode': code, 'bytes': len(output)}


def timeout_diagnostics(directory):
    """Only ephemeral synthetic GitHub emulators; no bugreport, env or UI tree."""
    result = {'scope': 'synthetic_ci_emulator_only', 'status': 'not_collected', 'commands': []}
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_OS') != 'Linux':
        result['reason'] = 'not_github_linux_ci'
        return result
    deadline = time.monotonic() + DIAGNOSTIC_SECONDS
    remaining_bytes = MAX_DIAGNOSTIC_BYTES
    try:
        value, check = bounded_capture(['adb', 'shell', 'getprop', 'ro.kernel.qemu'], 5, 64)
        if check['status'] != 'completed' or check['exitCode'] != 0 or value.strip() != b'1':
            result['reason'] = 'synthetic_emulator_not_verified'
            return result
        commands = [
            ('activity-processes', ['adb', 'shell', 'dumpsys', 'activity', 'processes']),
            ('window-state', ['adb', 'shell', 'dumpsys', 'window', 'windows']),
            ('accessibility-state', ['adb', 'shell', 'dumpsys', 'accessibility']),
            ('device-threads', ['adb', 'shell', 'ps', '-A', '-T', '-o', 'PID,TID,PPID,S,NAME']),
            ('test-logcat', ['adb', 'logcat', '-d', '-t', '1500', '-v', 'threadtime',
                             'AndroidRuntime:E', 'ActivityManager:W', 'ActivityTaskManager:W',
                             'WindowManager:W', 'TestRunner:I', 'InstrumentationResultPrinter:I', '*:S']),
        ]
        # Only the supervised process tree, at most two JVMs; never jcmd -l
        # (which would expose all host Java command lines).
        for pid in sorted(descendants(os.getpid())):
            try:
                if Path(f'/proc/{pid}/comm').read_text().strip() == 'java':
                    commands.append((f'gradle-threads-{pid}', ['jcmd', str(pid), 'Thread.print', '-l']))
            except OSError:
                pass
            if len(commands) == 7:
                break
        for name, command in commands:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not remaining_bytes or cancel_signal:
                result['reason'] = 'collection_budget_or_cancellation'
                break
            try:
                data, record = bounded_capture(command, min(7, remaining),
                                               min(MAX_COMMAND_BYTES, remaining_bytes))
                (directory / (name + '.txt')).write_bytes(data)
                remaining_bytes -= len(data)
                record['file'] = name + '.txt'
            except OSError as error:
                record = {'status': 'unavailable', 'errorType': type(error).__name__}
            record['name'] = name
            result['commands'].append(record)
        result['status'] = 'attempted'
    except (OSError, subprocess.SubprocessError) as error:
        result['reason'] = type(error).__name__
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--matrix-label', required=True)
    parser.add_argument('--timeout-profile', choices=('standard', 'segmented-stress', 'app-experience', 'busy-stop'), default='standard')
    parser.add_argument('--timeout-seconds', type=int, default=1200)
    parser.add_argument('--termination-grace-seconds', type=int, default=10)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not re.fullmatch(r'3[0-6]|37\.0', args.matrix_label):
        parser.error('unsupported API label')
    maximum_timeout = 2400 if args.timeout_profile in ('segmented-stress', 'app-experience', 'busy-stop') else 1200
    if not 1 <= args.timeout_seconds <= maximum_timeout or not 1 <= args.termination_grace_seconds <= 10:
        parser.error(f'timeout must be 1..{maximum_timeout} seconds and grace 1..10 seconds')
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('missing command')
    directory = Path('instrumentation-diagnostics') / ('api-' + args.matrix_label)
    directory.mkdir(parents=True, exist_ok=True)
    record = {'schemaVersion': 1, 'kind': 'instrumentation-execution',
              'matrixLabel': args.matrix_label, 'status': 'start_failed', 'exitCode': 2,
              'timeoutSeconds': args.timeout_seconds, 'timeoutProfile': args.timeout_profile,
              'terminationGraceSeconds': args.termination_grace_seconds,
              'diagnosticBudgetSeconds': DIAGNOSTIC_SECONDS,
              'diagnosticFinalReapAllowanceSeconds': 1,
              'startedAt': datetime.now(timezone.utc).isoformat(), 'diagnostics': None}
    record['source'] = {}
    for name, pattern in (('GITHUB_SHA', r'[0-9a-f]{40}'), ('GITHUB_RUN_ID', r'[0-9]+'),
                          ('GITHUB_RUN_ATTEMPT', r'[0-9]+')):
        value = os.environ.get(name, '')
        if re.fullmatch(pattern, value):
            record['source'][name] = value
    process = None
    started = time.monotonic()
    signal.signal(signal.SIGINT, cancelled)
    signal.signal(signal.SIGTERM, cancelled)
    try:
        # Linux CI only. Retain ownership of double-forked Gradle descendants.
        if ctypes.CDLL(None, use_errno=True).prctl(36, 1, 0, 0, 0) != 0:
            raise OSError('could not establish child subreaper')
        process = subprocess.Popen(command, start_new_session=True)
        while True:
            result = process.poll()
            if result is not None:
                record.update(status='completed', exitCode=result if result >= 0 else 128 - result,
                              processExitCode=result)
                break
            if cancel_signal:
                record.update(status='cancelled', exitCode=128 + cancel_signal)
                break
            if time.monotonic() - started >= args.timeout_seconds:
                record.update(status='timed_out', exitCode=124)
                print(f'::error::API {args.matrix_label} instrumentation exceeded '
                      f'{args.timeout_seconds}s; exit 124. Partial test results remain partial.', flush=True)
                try:
                    record['diagnostics'] = timeout_diagnostics(directory)
                except Exception as error:
                    record['diagnostics'] = {'status': 'failed', 'errorType': type(error).__name__}
                break
            time.sleep(0.05)
    except (OSError, subprocess.SubprocessError) as error:
        # Do not print command arguments or environment values on infrastructure errors.
        record['errorType'] = type(error).__name__
    finally:
        if process is not None:
            try:
                remaining = stop_processes(process, args.termination_grace_seconds)
                record['remainingProcessIds'] = remaining
                if remaining and record['exitCode'] == 0:
                    record.update(status='cleanup_failed', exitCode=2)
            except Exception as error:
                # Cleanup failure cannot turn a known timeout/test failure into success.
                record['cleanupErrorType'] = type(error).__name__
                if record['exitCode'] == 0:
                    record.update(status='cleanup_failed', exitCode=2)
        record['finishedAt'] = datetime.now(timezone.utc).isoformat()
        record['elapsedSeconds'] = round(time.monotonic() - started, 3)
        try:
            (directory / 'execution.json').write_text(json.dumps(record, indent=2) + '\n')
        except OSError:
            print('Could not save instrumentation execution metadata', flush=True)
    return record['exitCode']


if __name__ == '__main__':
    raise SystemExit(main())
