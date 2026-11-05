"""Host transport and ownership boundary tests for the isolated IME probe."""
import base64
import copy
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock

import ime_profile_probe as p
from test_ime_method_trace import fixture as method_fixture


SOURCE = {'repository': 'ecojust/Agent-OS', 'commit': 'a' * 40, 'runId': 456, 'attempt': 1}
TARGET = {'package': 'com.android.inputmethod.latin', 'uid': 10112, 'pid': 1661}
FROZEN_FILES = {name: p.identity(('source: ' + name).encode()) for name in p.SOURCE_PATHS}
PROPERTIES = {
    'api': b'37\n', 'qemu': b'1\n', 'shell_uid': b'2000\n', 'current_user': b'0\n',
    'debuggable': b'1\n', 'fingerprint': b'generic/sdk_phone/test-keys\n',
    'buffer_property': b'\n',
}


def process_stat(ticks=12345, pid=1661, state='S'):
    # Field 22 is the nineteenth value after the state field.
    values = ['0'] * 18 + [str(ticks)] + ['0'] * 8
    return (f'{pid} (inputmethod.lat) {state} ' + ' '.join(values) + '\n').encode()


def process_dump(profile_lines=()):
    # Complete global dump delimiters are specified by the reviewed AMS and
    # ProcessList source. An omitted profile section means the slot is empty.
    lines = [
        'ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)',
        '  Process LRU list:',
        '    Proc # 0: ProcessRecord{abcd 1661:com.android.inputmethod.latin/u0a112}',
        *profile_lines,
        '  Total persistent processes: 2',
        '  mProcessesReady=true mSystemReady=true mBooted=true mFactoryTest=0',
        '  mBooting=false mCallFinishBooting=false mBootAnimationComplete=true',
        '  mForceBackgroundCheck=false',
    ]
    return ('\n'.join(lines) + '\n').encode()


def owned_dump(target=TARGET, path=None):
    path = path or p.remote_path(SOURCE)
    return process_dump((
        f"  mProfileApp={target['package']} mProfileProc=ProcessRecord{{abcd {target['pid']}:{target['package']}/u0a{target['uid'] - 10000}}}",
        f'  mProfileFile={path} mProfileFd=null',
        '  mSamplingInterval=0 mAutoStopProfiler=false mStreamingOutput=false mProfilerFlags=0',
        '  mProfileType=0',
    ))


def rewrite_json(payloads, name, mutate):
    result = copy.deepcopy(payloads)
    value = json.loads(result[name])
    mutate(value)
    result[name] = (json.dumps(value, indent=2) + '\n').encode()
    return result


class SyntheticDevice:
    """Finite host-only responses; never offered as actual device evidence."""
    def __init__(self, overrides=None, trace=None):
        self.overrides = overrides or {}
        self.trace = method_fixture() if trace is None else trace
        self.calls = []
        self.runner = None

    def output(self, name):
        outputs = {
            **PROPERTIES,
            'devices': b'List of devices attached\nemulator-5554\tdevice\n\n',
            'shell_package': (b'Packages:\n  Package [com.android.shell] (abc):\n'
                              b'    install permissions:\n      android.permission.SET_ACTIVITY_WATCHER: granted=true\n'),
            'default_ime': b'com.android.inputmethod.latin/.LatinIME\n',
            'ime_uid': b'package:com.android.inputmethod.latin uid:10112\n',
            'ime_package': b'Packages:\n  Package [com.android.inputmethod.latin] (ab):\n    flags=[ SYSTEM ]\n',
            'ime_pid': b'1661\n',
            'absence_ps': b'  UID   PID  PPID NAME ARGS\n 1000   911     1 system_server system_server\n',
            'absence_input_method': b'Current Input Method Manager state:\n  mCurClient=null\n',
            'absence_pid_after': (b'', {'status': 'completed', 'exitCode': 1, 'exceptionType': None}),
            'target_stat': process_stat(), 'stop_stat': process_stat(),
            'target_status': b'Name:\tinputmethod.lat\nPid:\t1661\nUid:\t10112\t10112\t10112\t10112\n',
            'target_cmdline': TARGET['package'].encode() + b'\0',
            'stop_cmdline': TARGET['package'].encode() + b'\0',
            'slot_before': process_dump(), 'slot_started': owned_dump(),
            'slot_before_stop': owned_dump(), 'slot_after_stop': process_dump(),
            'path_absent': b'', 'profile_start': b'', 'profile_stop': b'',
            'size_poll_0': str(len(self.trace)).encode() + b'\n',
            'size_poll_1': str(len(self.trace)).encode() + b'\n',
            'size_poll_2': str(len(self.trace)).encode() + b'\n',
            'trace_digest': (p.identity(self.trace)['sha256'] + '  ' + p.remote_path(SOURCE) + '\n').encode(),
            'cleanup_trace': b'', 'cleanup_absent': b'',
        }
        value = self.overrides.get(name, outputs.get(name))
        if isinstance(value, BaseException):
            raise value
        if isinstance(value, tuple):
            return value
        if value is None:
            raise AssertionError('unexpected synthetic command: ' + name)
        return value, {'status': 'completed', 'exitCode': 0, 'exceptionType': None}

    def factory(self, directory, source):
        device = self

        class Runner(p.CommandRunner):
            def __init__(self):
                super().__init__(directory, source, execute=self.execute_fake, stream=self.stream_fake)
                self.current_name = None

            def call(self, name, argv):
                self.current_name = name
                device.calls.append(name)
                return super().call(name, argv)

            def execute_fake(self, argv, seconds, limit):
                return device.output(self.current_name)

            def stream_fake(self, argv, seconds, limit, destination):
                Path(destination).write_bytes(device.trace)
                return b'', {'status': 'completed', 'exitCode': 0, 'exceptionType': None}

        device.runner = Runner()
        return device.runner


class SourceAndParsingTests(unittest.TestCase):
    def test_only_one_online_emulator_is_accepted(self):
        self.assertEqual('emulator-5554', p.select_emulator(b'List of devices attached\nemulator-5554\tdevice\n\n'))
        for data in (
            b'List of devices attached\n',
            b'List of devices attached\nemulator-5554\toffline\n',
            b'List of devices attached\nphysical-phone\tdevice\n',
            b'List of devices attached\nemulator-5554\tdevice\nemulator-5556\tdevice\n',
            b'List of devices attached\nemulator-5554\tdevice\nphysical-phone\tdevice\n',
        ):
            with self.subTest(data=data), self.assertRaises(p.Rejected):
                p.select_emulator(data)

    def test_source_requires_the_dedicated_main_branch_workflow(self):
        env = dict(GITHUB_ACTIONS='true', RUNNER_ENVIRONMENT='github-hosted', RUNNER_OS='Linux',
                   GITHUB_REPOSITORY='ecojust/Agent-OS', GITHUB_REF='refs/heads/master',
                   GITHUB_EVENT_NAME='workflow_dispatch',
                   GITHUB_WORKFLOW_REF='ecojust/Agent-OS/.github/workflows/ime-profile-probe.yml@refs/heads/master',
                   GITHUB_RUN_ID='456', GITHUB_RUN_ATTEMPT='1', GITHUB_SHA='a' * 40)
        self.assertEqual(SOURCE, p.source_from_environment(env))
        replacements = {'RUNNER_ENVIRONMENT': 'self-hosted', 'GITHUB_REF': 'refs/heads/other',
                        'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_RUN_ATTEMPT': '01',
                        'GITHUB_SHA': 'main', 'GITHUB_WORKFLOW_REF':
                        'ecojust/Agent-OS/.github/workflows/other.yml@refs/heads/master'}
        for key, value in replacements.items():
            with self.subTest(key=key), self.assertRaises(p.Rejected):
                p.source_from_environment(dict(env, **{key: value}))

    def test_source_fingerprints_are_actual_bytes_and_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in p.SOURCE_PATHS:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(('source: ' + name).encode())
            self.assertEqual(FROZEN_FILES, p.source_files(root))
            first = root / p.SOURCE_PATHS[0]
            first.unlink()
            first.symlink_to(root / p.SOURCE_PATHS[1])
            with self.assertRaises(p.Rejected):
                p.source_files(root)

    def test_json_duplicate_keys_floats_and_nonfinite_values_are_rejected(self):
        for data in (b'{"a":1,"a":2}', b'{"value":NaN}', b'{"value":Infinity}', b'{"value":1.5}'):
            with self.subTest(data=data), self.assertRaises(p.Rejected):
                p.decode_json(data)

    def test_process_start_time_and_absence_are_distinct(self):
        value = p.parse_stat(process_stat(), TARGET['pid'])
        self.assertEqual(12345, value['startTimeTicks'])
        self.assertEqual('S', value['state'])
        self.assertIsNone(p.parse_stat(b'ABSENT\n', TARGET['pid']))
        with self.assertRaises(p.Rejected):
            p.parse_stat(process_stat(pid=1662), TARGET['pid'])

    def test_complete_empty_global_profile_slot_is_required(self):
        self.assertEqual({'state': 'empty'}, p.parse_profile_slot(process_dump()))
        for data in (
            b'', process_dump().rstrip(b'\n'),
            process_dump().replace(b'  mForceBackgroundCheck=false\n', b''),
            process_dump().replace(b'  Total persistent processes: 2\n', b''),
            process_dump().replace(b'mSystemReady=true', b'mSystemReady=false'),
            process_dump().replace(b'  Process LRU list:', b'  Permission Denial: process data unavailable'),
            process_dump(('  mProfileApp=null mProfileProc=null',)),
            process_dump(('  mProfileFile=/another.trace mProfileFd=null',)),
            process_dump(('  mSamplingInterval=0',)),
            process_dump(('  mProfilerFlags=0',)),
            process_dump(('  mProfileApp unavailable',)),
        ):
            with self.subTest(data=data), self.assertRaises(p.Rejected):
                p.parse_profile_slot(data)

    def test_owned_slot_requires_the_reviewed_closed_fd_and_all_owner_fields(self):
        value = p.parse_profile_slot(owned_dump(), TARGET, p.remote_path(SOURCE))
        self.assertEqual('owned', value['state'])
        variants = (
            owned_dump(dict(TARGET, pid=1777)),
            owned_dump(dict(TARGET, uid=10113)),
            owned_dump(path='/data/local/tmp/other.trace'),
            owned_dump().replace(b'mProfileFd=null', b'mProfileFd=android.os.ParcelFileDescriptor@123'),
            owned_dump().replace(b'mSamplingInterval=0', b'mSamplingInterval=1000'),
            owned_dump().replace(b'mStreamingOutput=false', b'mStreamingOutput=true'),
            owned_dump().replace(b'mProfilerFlags=0', b'mProfilerFlags=1'),
            owned_dump().replace(b'  mProfileType=0\n', b'  mProfileType=0\n  mProfileType=0\n'),
        )
        for data in variants:
            with self.subTest(data=data), self.assertRaises(p.Rejected):
                p.parse_profile_slot(data, TARGET, p.remote_path(SOURCE))

    def test_shell_permission_and_target_uid_must_be_explicit(self):
        allowed = (b'Packages:\n  Package [com.android.shell] (abc):\n'
                   b'    install permissions:\n      android.permission.SET_ACTIVITY_WATCHER: granted=true\n')
        self.assertTrue(p.shell_permission(allowed))
        for data in (allowed.replace(b'granted=true', b'granted=false'),
                     allowed.replace(b': granted=true', b''),
                     allowed.replace(b'com.android.shell', b'other.package')):
            with self.subTest(data=data), self.assertRaises(p.Rejected):
                p.shell_permission(data)
        self.assertEqual(10112, p.parse_uid(b'package:com.android.inputmethod.latin uid:10112\n'))
        for data in (b'package:other.package uid:10112\n',
                     b'package:com.android.inputmethod.latin uid:1000\n',
                     b'package:com.android.inputmethod.latin uid:10112\npackage:com.android.inputmethod.latin uid:10113\n'):
            with self.subTest(data=data), self.assertRaises(p.Rejected):
                p.parse_uid(data)

    def test_prepare_is_unverified_and_never_overwrites_existing_outputs(self):
        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(p, 'source_files', return_value=FROZEN_FILES):
            directory = Path(temporary) / 'probe'
            p.prepare(directory, SOURCE)
            report = p.validate_evidence(p.read_payloads(directory), SOURCE)
            self.assertEqual('unverified', report['status'])
            self.assertFalse(report['profile']['startAttempted'])
            self.assertEqual('NOT_EXECUTED', report['failureReason'])
            with self.assertRaises(p.Rejected):
                p.prepare(directory, SOURCE)


class ProbeFlowTests(unittest.TestCase):
    def run_device(self, overrides=None, trace=None, sleep=lambda _: None):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name) / 'probe'
        device = SyntheticDevice(overrides, trace)
        with mock.patch.object(p, 'source_files', return_value=FROZEN_FILES):
            p.prepare(directory, SOURCE)
            passed = p.run_probe(SimpleNamespace(output=str(directory), sdk_root='/fake/sdk'),
                                 SOURCE, runner_factory=device.factory, sleep=sleep)
        payloads = p.read_payloads(directory)
        report = p.validate_evidence(payloads, SOURCE)
        return passed, report, payloads, device, directory

    def test_complete_owned_probe_has_exactly_one_start_stop_and_cleanup(self):
        passed, report, payloads, device, _ = self.run_device()
        self.assertTrue(passed)
        self.assertEqual('recorded', report['status'])
        self.assertEqual(1, device.calls.count('profile_start'))
        self.assertEqual(1, device.calls.count('profile_stop'))
        self.assertTrue(report['profile']['globalSlotClear'])
        self.assertTrue(report['profile']['cleanupConfirmed'])
        self.assertEqual('recorded', report['analysis']['status'])
        self.assertEqual(method_fixture(), payloads['method.trace'])
        self.assertNotIn('size_poll_2', device.calls)

    def test_absent_pid_collects_only_three_context_reads_without_profile(self):
        missing = (b'', {'status': 'completed', 'exitCode': 1, 'exceptionType': None})
        passed, report, payloads, device, _ = self.run_device({'ime_pid': missing})
        self.assertFalse(passed)
        self.assertEqual('COMMAND_FAILED_ime_pid', report['failureReason'])
        self.assertEqual(list(p.PROFILE_COMMAND_IDS[:13]) + list(p.ABSENCE_COMMAND_IDS), device.calls)
        self.assertEqual(16, len(device.calls))
        self.assertIsNone(report['target'])
        self.assertEqual(p.empty_report(SOURCE, FROZEN_FILES)['profile'], report['profile'])
        self.assertNotIn('method.trace', payloads)
        records = json.loads(payloads['commands.json'])['commands']
        self.assertEqual(['shell', 'ps', '-A', '-w', '-o', 'UID,PID,PPID,NAME,ARGS'], records[-3]['argv'][3:])
        self.assertEqual(['shell', 'dumpsys', 'input_method'], records[-2]['argv'][3:])
        self.assertEqual(['shell', 'pidof', p.PACKAGE], records[-1]['argv'][3:])
        self.assertEqual([5, 5, 5], [c['timeoutSeconds'] for c in records[-3:]])
        self.assertEqual([1024 * 1024, 2 * 1024 * 1024, 65536], [c['byteLimit'] for c in records[-3:]])

    def test_second_pid_appearance_does_not_admit_profile_or_change_first_failure(self):
        missing = (b'', {'status': 'completed', 'exitCode': 1, 'exceptionType': None})
        passed, report, payloads, device, directory = self.run_device({
            'ime_pid': missing, 'absence_pid_after': b'1777\n'})
        self.assertFalse(passed)
        self.assertEqual('COMMAND_FAILED_ime_pid', report['failureReason'])
        self.assertIsNone(report['target'])
        self.assertNotIn('target_stat', device.calls)
        self.assertNotIn('profile_start', device.calls)
        self.assertEqual(b'1777\n', p.command_data(json.loads(payloads['commands.json'])['commands'][-1]))
        self.assertFalse(p.finalize(directory, SOURCE, 'success'))
        self.assertEqual('COMMAND_FAILED_ime_pid', p.validate_evidence(p.read_payloads(directory))['failureReason'])

    def test_only_exact_completed_empty_exit_one_triggers_context_reads(self):
        cases = [
            (b'\n', {'status': 'completed', 'exitCode': 1, 'exceptionType': None}),
            (b'permission denied\n', {'status': 'completed', 'exitCode': 1, 'exceptionType': None}),
            (b'', {'status': 'completed', 'exitCode': 0, 'exceptionType': None}),
            (b'', {'status': 'timed_out', 'exitCode': 1, 'exceptionType': None}),
            (b'', {'status': 'completed', 'exitCode': 1, 'exceptionType': 'OSError'}),
            b'1661 1777\n',
        ]
        for result in cases:
            with self.subTest(result=result):
                passed, report, _, device, _ = self.run_device({'ime_pid': result})
                self.assertFalse(passed)
                self.assertFalse(set(device.calls) & set(p.ABSENCE_COMMAND_IDS))
                self.assertFalse(report['profile']['startAttempted'])

    def test_failed_context_commands_preserve_raw_bytes_and_continue_independently(self):
        missing = (b'', {'status': 'completed', 'exitCode': 1, 'exceptionType': None})
        for status, code, exception, raw in [
            ('completed', 1, None, b'permission denied\n'),
            ('timed_out', -9, None, b'partial IMMS dump\n'),
            ('output_limit', -9, None, b'x' * (2 * 1024 * 1024)),
            ('exception', None, 'OSError', b''),
        ]:
            with self.subTest(status=status):
                passed, report, payloads, device, _ = self.run_device({
                    'ime_pid': missing,
                    'absence_ps': (b'partial ps\n', {'status': 'timed_out', 'exitCode': -9, 'exceptionType': None}),
                    'absence_input_method': (raw, {'status': status, 'exitCode': code, 'exceptionType': exception})})
                self.assertFalse(passed)
                self.assertEqual('COMMAND_FAILED_ime_pid', report['failureReason'])
                self.assertEqual(list(p.ABSENCE_COMMAND_IDS), device.calls[-3:])
                entries = json.loads(payloads['commands.json'])['commands']
                self.assertEqual(raw, p.command_data(entries[-2]))
                self.assertEqual(status, entries[-2]['status'])
                self.assertFalse(report['profile']['stopAttempted'])

    def test_cancelled_context_read_stops_later_queries_and_keeps_primary_failure(self):
        missing = (b'', {'status': 'completed', 'exitCode': 1, 'exceptionType': None})
        passed, report, payloads, device, _ = self.run_device({
            'ime_pid': missing,
            'absence_ps': (b'partial ps\n', {'status': 'cancelled', 'exitCode': -9, 'exceptionType': None})})
        self.assertFalse(passed)
        self.assertEqual('absence_ps', device.calls[-1])
        self.assertEqual('COMMAND_FAILED_ime_pid', report['failureReason'])
        self.assertNotIn('method.trace', payloads)

    def test_admission_deadline_and_cancel_flag_bound_additional_command_starts(self):
        for ticks, cancelled, expected in [
            ([0, 15], False, []),
            ([0, 0, 15], False, ['absence_ps']),
            ([0, 0, 0, 0], False, list(p.ABSENCE_COMMAND_IDS)),
            ([0], True, []),
        ]:
            with self.subTest(expected=expected, cancelled=cancelled):
                report = p.empty_report(SOURCE, FROZEN_FILES)
                report.update(tools={'adb': '/fake/sdk/platform-tools/adb'}, device={'serial': 'emulator-5554', 'properties': {}})
                runner = mock.Mock()
                runner.call.return_value = (b'', {'status': 'completed'})
                with mock.patch.object(p, 'CANCELLED', cancelled):
                    p._observe_pid_absence(report, runner, lambda: None, monotonic=mock.Mock(side_effect=ticks))
                self.assertEqual(expected, [c.args[0] for c in runner.call.call_args_list])
                self.assertEqual('COMMAND_FAILED_ime_pid', report['failureReason'])

    def test_absence_replay_rejects_changed_trigger_prerequisites_command_and_upgrade(self):
        missing = (b'', {'status': 'completed', 'exitCode': 1, 'exceptionType': None})
        _, _, originals, _, _ = self.run_device({'ime_pid': missing})
        def change_command(name, **changes):
            def change(value):
                next(c for c in value['commands'] if c['id'] == name).update(changes)
            return rewrite_json(originals, 'commands.json', change)
        def replace_bytes(name, raw):
            return change_command(name, **p.identity(raw), outputBase64=base64.b64encode(raw).decode())
        cases = [change_command('ime_pid', exitCode=0),
                 change_command('ime_pid', status='timed_out'),
                 replace_bytes('ime_pid', b'error\n'),
                 replace_bytes('debuggable', b'0\n'),
                 replace_bytes('default_ime', b'other/.IME\n'),
                 replace_bytes('shell_package', b'no permission\n'),
                 change_command('absence_ps', argv=['/fake/sdk/platform-tools/adb', 'shell', 'am', 'startservice']),
                 change_command('absence_ps', timeoutSeconds=6),
                 change_command('absence_ps', status='cancelled'),
                 rewrite_json(originals, 'report.json', lambda r: r.update(status='recorded', failureReason=None)),
                 rewrite_json(originals, 'report.json', lambda r: r['profile'].update(startAttempted=True)),
                 rewrite_json(originals, 'report.json', lambda r: r.update(failureReason='METHOD_TRACE_NOT_VERIFIED'))]
        for index, altered in enumerate(cases):
            with self.subTest(index=index), self.assertRaises(p.Rejected):
                p.validate_evidence(altered, SOURCE)

    def test_interruption_between_primary_receipt_and_reason_is_preserved_and_finalized(self):
        missing = (b'', {'status': 'completed', 'exitCode': 1, 'exceptionType': None})
        _, _, originals, device, directory = self.run_device({'ime_pid': missing})
        partial = rewrite_json(originals, 'commands.json', lambda r: r.update(commands=r['commands'][:13]))
        partial = rewrite_json(partial, 'report.json',
                               lambda r: r.update(failureReason=None, completedAt=None, stage='preflight'))
        self.assertEqual('unverified', p.validate_evidence(partial, SOURCE)['status'])
        count = len(device.calls)
        for name, raw in partial.items():
            (directory / name).write_bytes(raw)
        self.assertFalse(p.finalize(directory, SOURCE, 'failure'))
        final = p.read_payloads(directory)
        self.assertEqual(partial['commands.json'], final['commands.json'])
        self.assertEqual('COMMAND_FAILED_ime_pid', p.validate_evidence(final, SOURCE)['failureReason'])
        self.assertEqual(count, len(device.calls))
        self.assertFalse(p.finalize(directory, SOURCE, 'success'))
        for mutate in [lambda r: r.update(status='recorded'),
                       lambda r: r.update(completedAt=p.now()),
                       lambda r: r.update(stage='complete')]:
            with self.assertRaises(p.Rejected):
                p.validate_evidence(rewrite_json(partial, 'report.json', mutate), SOURCE)

    def test_interrupted_absence_capture_keeps_raw_partial_without_claiming_completion(self):
        missing = (b'', {'status': 'completed', 'exitCode': 1, 'exceptionType': None})
        _, _, originals, _, _ = self.run_device({'ime_pid': missing})
        def interrupted_commands(value):
            value['commands'] = value['commands'][:14]
            value['commands'][-1].update(endedAt=None, elapsedMillis=0, status='running', exitCode=None,
                                         outputBase64='', **p.identity(b''))
        altered = rewrite_json(originals, 'commands.json', interrupted_commands)
        altered = rewrite_json(altered, 'report.json', lambda r: r.update(completedAt=None, stage='preflight'))
        report = p.validate_evidence(altered, SOURCE)
        self.assertEqual('unverified', report['status'])
        self.assertEqual('COMMAND_FAILED_ime_pid', report['failureReason'])
        self.assertFalse(report['profile']['startAttempted'])

    def test_non_debuggable_device_does_not_start_any_profile(self):
        passed, report, _, device, _ = self.run_device({'debuggable': b'0\n'})
        self.assertFalse(passed)
        self.assertEqual('PREFLIGHT_DEBUGGABLE', report['failureReason'])
        self.assertNotIn('profile_start', device.calls)
        self.assertNotIn('profile_stop', device.calls)

    def test_target_process_uid_mismatch_prevents_start(self):
        passed, report, _, device, _ = self.run_device({
            'target_status': b'Name:\tinputmethod.lat\nPid:\t1661\nUid:\t10113\t10113\t10113\t10113\n'})
        self.assertFalse(passed)
        self.assertFalse(report['profile']['startAttempted'])
        self.assertNotIn('profile_start', device.calls)

    def test_existing_global_profiler_prevents_start_without_stopping_it(self):
        foreign = owned_dump(dict(TARGET, pid=1777), '/data/local/tmp/other.trace')
        passed, report, _, device, _ = self.run_device({'slot_before': foreign})
        self.assertFalse(passed)
        self.assertFalse(report['profile']['startAttempted'])
        self.assertNotIn('profile_start', device.calls)
        self.assertNotIn('profile_stop', device.calls)

    def test_preexisting_output_path_is_never_overwritten(self):
        denied = (b'', {'status': 'completed', 'exitCode': 1, 'exceptionType': None})
        passed, report, _, device, _ = self.run_device({'path_absent': denied})
        self.assertFalse(passed)
        self.assertEqual('COMMAND_FAILED_path_absent', report['failureReason'])
        self.assertNotIn('profile_start', device.calls)
        self.assertNotIn('cleanup_trace', device.calls)

    def test_start_failure_with_no_owned_slot_does_not_authorize_stop(self):
        denied = (b'profiling denied\n', {'status': 'completed', 'exitCode': 1, 'exceptionType': None})
        passed, report, payloads, device, _ = self.run_device({'profile_start': denied, 'slot_before_stop': process_dump()})
        self.assertFalse(passed)
        self.assertEqual('COMMAND_FAILED_profile_start', report['failureReason'])
        self.assertTrue(report['profile']['startAttempted'])
        self.assertFalse(report['profile']['startAccepted'])
        self.assertNotIn('profile_stop', device.calls)
        self.assertEqual(method_fixture(), payloads['method.trace'])
        self.assertFalse(report['profile']['cleanupConfirmed'])
        self.assertNotIn('cleanup_trace', device.calls)

    def test_failed_start_request_with_later_owned_slot_is_stopped_but_not_promoted(self):
        failed = (b'incomplete adb response\n', {'status': 'timed_out', 'exitCode': -9, 'exceptionType': None})
        passed, report, _, device, _ = self.run_device({'profile_start': failed})
        self.assertFalse(passed)
        self.assertFalse(report['profile']['startAccepted'])
        self.assertTrue(report['profile']['stopAccepted'])
        self.assertTrue(report['profile']['cleanupConfirmed'])
        self.assertEqual(1, device.calls.count('profile_stop'))

    def test_exception_after_owned_start_still_uses_finally_cleanup(self):
        count = []
        def failed_hold(seconds):
            count.append(seconds)
            if seconds == p.HOLD_SECONDS:
                raise RuntimeError('synthetic hold failure')
        passed, report, _, device, _ = self.run_device(sleep=failed_hold)
        self.assertFalse(passed)
        self.assertEqual('HOST_RuntimeError', report['failureReason'])
        self.assertTrue(report['profile']['cleanupConfirmed'])
        self.assertEqual(1, device.calls.count('profile_stop'))

    def test_pid_reuse_disappearance_or_cmdline_change_never_stops_target(self):
        cases = ({'stop_stat': process_stat(ticks=12346)}, {'stop_stat': b'ABSENT\n'},
                 {'stop_stat': process_stat(state='Z')}, {'stop_cmdline': b'com.other.app\0'})
        for override in cases:
            with self.subTest(override=override):
                passed, report, payloads, device, _ = self.run_device(override)
                self.assertFalse(passed)
                self.assertFalse(report['profile']['stopAttempted'])
                self.assertNotIn('profile_stop', device.calls)
                self.assertEqual(method_fixture(), payloads['method.trace'])
                self.assertFalse(report['profile']['globalSlotClear'])
                self.assertFalse(report['profile']['cleanupConfirmed'])
                self.assertNotIn('cleanup_trace', device.calls)

    def test_slot_changed_before_stop_never_stops_other_owner(self):
        foreign = owned_dump(dict(TARGET, pid=1777), '/data/local/tmp/other.trace')
        passed, report, _, device, _ = self.run_device({'slot_before_stop': foreign})
        self.assertFalse(passed)
        self.assertFalse(report['profile']['stopAttempted'])
        self.assertNotIn('profile_stop', device.calls)

    def test_stop_rejection_or_nonempty_slot_only_preserves_unverified_prefix(self):
        denied = (b'stop denied\n', {'status': 'completed', 'exitCode': 1, 'exceptionType': None})
        for override in ({'profile_stop': denied}, {'slot_after_stop': owned_dump()}):
            with self.subTest(override=override):
                passed, report, payloads, device, _ = self.run_device(override)
                self.assertFalse(passed)
                self.assertFalse(report['profile']['globalSlotClear'])
                self.assertEqual(method_fixture(), payloads['method.trace'])
                self.assertFalse(report['profile']['cleanupConfirmed'])
                self.assertNotIn('cleanup_trace', device.calls)

    def test_missing_trace_is_not_pulled_and_unstable_or_limited_prefix_is_not_promoted(self):
        cases = (
            {name: b'ABSENT\n' for name in ('size_poll_0', 'size_poll_1', 'size_poll_2')},
            {'size_poll_0': b'10\n', 'size_poll_1': b'20\n', 'size_poll_2': b'30\n'},
            {'size_poll_0': str(p.MAX_TRACE_BYTES + 1).encode() + b'\n'},
        )
        for override in cases:
            with self.subTest(override=override):
                passed, report, payloads, device, _ = self.run_device(override)
                self.assertFalse(passed)
                self.assertTrue(report['profile']['globalSlotClear'])
                self.assertTrue(report['profile']['cleanupConfirmed'])
                if override['size_poll_0'] == b'ABSENT\n':
                    self.assertNotIn('trace_pull', device.calls)
                    self.assertNotIn('method.trace', payloads)
                else:
                    self.assertIn('trace_pull', device.calls)
                    self.assertEqual(method_fixture(), payloads['method.trace'])
                    self.assertNotIn('trace_digest', device.calls)
                    self.assertEqual('unverified', report['status'])

    def test_invalid_trace_is_preserved_and_cleaned_without_capability_success(self):
        damaged = method_fixture()[:-1]
        passed, report, payloads, _, _ = self.run_device(trace=damaged)
        self.assertFalse(passed)
        self.assertEqual('METHOD_TRACE_NOT_VERIFIED', report['failureReason'])
        self.assertEqual('unverified', report['analysis']['status'])
        self.assertEqual(damaged, payloads['method.trace'])
        self.assertTrue(report['profile']['cleanupConfirmed'])

    def test_complete_trace_from_another_pid_is_not_capability_success(self):
        other_trace = method_fixture(overrides={'pid': '1777'})
        passed, report, payloads, _, _ = self.run_device(trace=other_trace)
        self.assertFalse(passed)
        self.assertEqual('TRACE_PID_MISMATCH', report['failureReason'])
        self.assertEqual('recorded', report['analysis']['status'])
        self.assertEqual(other_trace, payloads['method.trace'])
        self.assertTrue(report['profile']['cleanupConfirmed'])

    def test_remote_trace_digest_mismatch_preserves_original_and_cleans_only_owned_path(self):
        passed, report, payloads, device, _ = self.run_device({'trace_digest': b'bad digest\n'})
        self.assertFalse(passed)
        self.assertEqual('TRACE_DIGEST_MISMATCH', report['failureReason'])
        self.assertEqual(method_fixture(), payloads['method.trace'])
        self.assertIn('cleanup_trace', device.calls)
        self.assertTrue(report['profile']['cleanupConfirmed'])
        self.assertEqual('unverified', report['status'])

    def test_evidence_replay_rejects_modified_bytes_argv_identity_and_analysis(self):
        _, _, originals, _, _ = self.run_device()
        bad_sets = []
        changed = copy.deepcopy(originals)
        changed['method.trace'] += b'x'
        bad_sets.append(changed)
        bad_sets.append(rewrite_json(originals, 'commands.json',
            lambda value: value['commands'][0].update(outputBase64=base64.b64encode(b'changed').decode())))
        bad_sets.append(rewrite_json(originals, 'commands.json',
            lambda value: next(c for c in value['commands'] if c['id'] == 'profile_stop')['argv'].append('--sampling')))
        bad_sets.append(rewrite_json(originals, 'report.json',
            lambda value: value['target'].update(pid=1777)))
        bad_sets.append(rewrite_json(originals, 'report.json',
            lambda value: value['analysis']['records'].update(count=999)))
        bad_sets.append(rewrite_json(originals, 'report.json',
            lambda value: value['source'].update(commit='b' * 40)))
        for index, payloads in enumerate(bad_sets):
            with self.subTest(index=index), self.assertRaises(p.Rejected):
                p.validate_evidence(payloads, SOURCE)

    def test_rehashed_command_output_cannot_forge_empty_slot_or_unchanged_pid(self):
        _, _, originals, _, _ = self.run_device()
        for name, output in (('slot_before', owned_dump()),
                             ('stop_stat', process_stat(ticks=12346)),
                             ('slot_before_stop', process_dump())):
            def change(value):
                entry = next(item for item in value['commands'] if item['id'] == name)
                entry.update(**p.identity(output), outputBase64=base64.b64encode(output).decode())
            altered = rewrite_json(originals, 'commands.json', change)
            with self.subTest(name=name), self.assertRaises(p.Rejected):
                p.validate_evidence(altered, SOURCE)

    def test_finalization_is_nonvisual_does_not_repeat_stop_or_upgrade_failure(self):
        _, _, _, device, directory = self.run_device()
        count = len(device.calls)
        self.assertFalse(p.finalize(directory, SOURCE, 'failure'))
        self.assertFalse(p.finalize(directory, SOURCE, 'success'))
        report = p.validate_evidence(p.read_payloads(directory), SOURCE)
        self.assertEqual('unverified', report['status'])
        self.assertEqual('EMULATOR_STEP_FAILURE', report['failureReason'])
        self.assertEqual(count, len(device.calls))

    def test_changed_frozen_source_prevents_any_device_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary) / 'probe'
            with mock.patch.object(p, 'source_files', return_value=FROZEN_FILES):
                p.prepare(directory, SOURCE)
            changed = copy.deepcopy(FROZEN_FILES)
            changed[p.SOURCE_PATHS[0]] = p.identity(b'different source')
            device = SyntheticDevice()
            with mock.patch.object(p, 'source_files', return_value=changed), self.assertRaises(p.Rejected):
                p.run_probe(SimpleNamespace(output=str(directory), sdk_root='/fake/sdk'), SOURCE,
                            runner_factory=device.factory, sleep=lambda _: None)
            self.assertEqual([], device.calls)


class TransportTests(unittest.TestCase):
    def run_child(self, code, seconds=2, limit=4096):
        return p.bounded_capture([sys.executable, '-c', code], seconds, limit)

    def test_original_stdout_and_stderr_bytes_and_exit_code_are_retained(self):
        data, result = self.run_child("import os; os.write(1,b'alpha\\x00'); os.write(2,b'beta'); raise SystemExit(7)")
        self.assertEqual(b'alpha\0beta', data)
        self.assertEqual('completed', result['status'])
        self.assertEqual(7, result['exitCode'])

    def test_timeout_retains_partial_diagnostic_output(self):
        started = time.monotonic()
        data, result = self.run_child("import os,time; os.write(1,b'before timeout\\n'); time.sleep(20)", seconds=1)
        self.assertEqual(b'before timeout\n', data)
        self.assertEqual('timed_out', result['status'])
        self.assertLess(time.monotonic() - started, 5)

    def test_output_limit_preserves_only_the_original_bounded_prefix(self):
        data, result = self.run_child("import os; os.write(1,b'x'*10000)", limit=127)
        self.assertEqual(b'x' * 127, data)
        self.assertEqual('output_limit', result['status'])

    def test_cancel_before_start_does_not_create_a_process(self):
        with mock.patch.object(p, 'CANCELLED', True), mock.patch.object(p.subprocess, 'Popen') as spawn:
            data, result = self.run_child('raise SystemExit(0)')
        spawn.assert_not_called()
        self.assertEqual(b'', data)
        self.assertEqual('cancelled', result['status'])

    def test_trace_stream_is_file_limited_and_retains_the_original_prefix(self):
        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(p, 'MAX_TRACE_BYTES', 127):
            path = Path(temporary) / 'method.trace'
            _, result = p.stream_trace([sys.executable, '-c', "import os; os.write(1,b'x'*10000); os.write(1,b'y')"], 2, 4096, path)
            self.assertEqual(b'x' * 127, path.read_bytes())
            self.assertNotEqual(0, result['exitCode'])

    def test_trace_stream_cannot_overwrite_an_existing_original(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'method.trace'
            path.write_bytes(b'original')
            with self.assertRaises(p.Rejected):
                p.stream_trace([sys.executable, '-c', "print('replacement')"], 2, 4096, path)
            self.assertEqual(b'original', path.read_bytes())

    @unittest.skipUnless(hasattr(os, 'fork') and Path('/proc').is_dir(), 'Linux process-group test')
    def test_parent_exit_does_not_leave_its_stdout_holding_child_running(self):
        code = ("import os,time\n"
                "pid=os.fork()\n"
                "if pid:\n"
                " os.write(1,(str(pid)+'\\n').encode()); os._exit(0)\n"
                "time.sleep(20)\n")
        data, result = self.run_child(code, seconds=1)
        self.assertEqual('timed_out', result['status'])
        pid = int(data.strip())
        stat = Path('/proc') / str(pid) / 'stat'
        # A reparented zombie can remain briefly until init reaps it; it no
        # longer holds the pipe or executes. Presence alone is not liveness.
        for _ in range(20):
            if not stat.exists() or stat.read_text().split(') ', 1)[1].startswith('Z '):
                break
            time.sleep(.01)
        else:
            os.kill(pid, signal.SIGKILL)
            self.fail('the owned child process continued running after timeout')


if __name__ == '__main__':
    unittest.main()
