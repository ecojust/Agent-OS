"""Synthetic orchestration and failure evidence; these tests never run Android."""
import base64
import copy
import json
import shlex
import subprocess
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import window_infos_reset_trace as capture
import window_infos_keyboard as keyboard
import window_infos_raw as raw
import test_window_infos_keyboard as keyboard_tests
import test_window_infos_raw as raw_tests

SOURCE = copy.deepcopy(keyboard_tests.SOURCE)
KEYBOARD_IDENTITIES = copy.deepcopy(keyboard_tests.IDENTITIES)
RAW_IDENTITIES = {name: value for name, value in KEYBOARD_IDENTITIES.items()
                  if name not in keyboard.EXTRA_SOURCES}
IDENTITIES = dict(KEYBOARD_IDENTITIES, **{
    name: {'bytes': 1, 'sha256': 'e' * 64} for name in capture.EXTRA_SOURCES})
TRACE = b'\x0a\x12synthetic-perfetto-original\x00\xff'
# Original API37 --help output only: source 9ee0233a0db1fd79402f26eb13b80e40992828ae,
# run 41579567857 attempt 1, commands.json id=perfetto_help, archive
# fc9a81123bdf964b5706f8a1902ed3b1fceaea91. This fixture is not new device-success evidence.
HELP_FIXTURE = Path(__file__).resolve().parent / 'fixtures/perfetto-api37-help.txt'
HELP_SHA256 = 'afbf6306421fa8b494d154535cf845cfa43f62f16e9b45f77094a135308613d2'


def help_bytes():
    return HELP_FIXTURE.read_bytes()


def help_command(data=None, *, exit_code=1, status='completed'):
    data = help_bytes() if data is None else data
    return {'id': 'perfetto_help', 'status': status, 'exitCode': exit_code,
            'exceptionType': 'RuntimeError' if status == 'exception' else None,
            **raw.identity(data), 'outputBase64': base64.b64encode(data).decode()}


def proc_stat(pid=5731, start=271828, state='S', comm='perfetto'):
    # Fields 4 through 52 from proc(5); starttime is field 22, not the PID.
    values = ['0'] * 49
    values[18] = str(start)
    return (f'{pid} ({comm}) {state} ' + ' '.join(values) + '\n').encode()


class SyntheticCapture:
    """Fake only device transport; execute the existing keyboard/raw host code."""
    def __init__(self, root, *, fault=None, enabled_positive=True, restore_wrong=False,
                 help_output=None, help_exit=1, help_status='completed'):
        self.root = Path(root)
        self.keyboard = keyboard_tests.SyntheticDriver(
            root, enabled_positive=enabled_positive, restore_wrong=restore_wrong)
        self.output = self.root / 'reset-output'
        self.keyboard.output = self.output / 'comparison'
        self.args = SimpleNamespace(**vars(self.keyboard.args))
        self.args.output = self.output
        self.fault = fault
        self.help_output = help_bytes() if help_output is None else help_output
        self.help_exit, self.help_status = help_exit, help_status
        self.commands = []
        self.comparison_calls = 0
        self.setting_restore_preceded_stop = False

    def report(self):
        return json.loads((self.output / 'report.json').read_text())

    def native_cmdline(self):
        paths = capture.remote_paths(SOURCE)
        words = ['/system/bin/perfetto', '--txt', '-c', '-',
                 '-o', paths['trace'], '--background-wait']
        return b'\0'.join(word.encode() for word in words) + b'\0'

    def execute(self, argv, seconds, limit):
        entry = json.loads((self.output / 'commands.json').read_text())['commands'][-1]
        name = entry['id']
        self.commands.append(name)
        data = b''
        code = 0
        if name == 'devices': data = b'List of devices attached\nemulator-5554\tdevice\n'
        elif name == 'api': data = b'37\n'
        elif name == 'qemu': data = b'1\n'
        elif name == 'current_user': data = b'0\n'
        elif name == 'shell_uid': data = b'2000\n'
        elif name == 'perfetto_help':
            data = b'--background\n' if self.fault == 'unsupported' else self.help_output
            return data, {'status': self.help_status, 'exitCode': self.help_exit,
                          'exceptionType': 'RuntimeError' if self.help_status == 'exception' else None}
        elif name == 'config_digest':
            data = (raw.sha(capture.trace_config()) + '  ' + capture.remote_paths(SOURCE)['config'] + '\n').encode()
        elif name == 'start_trace':
            data = b'5731\n5732\n' if self.fault == 'ambiguous_pid' else b'5731\n'
            if self.fault == 'start_exit_one': code = 1
        elif name == 'start_stat':
            data = proc_stat(pid=5732 if self.fault == 'wrong_start_pid' else 5731)
        elif name == 'start_cmdline':
            data = b'/system/bin/unrelated\0' if self.fault == 'wrong_start_cmdline' else self.native_cmdline()
        elif name == 'stop_stat':
            if self.fault == 'early_exit': data = b'ABSENT\n'
            else: data = proc_stat(start=271829 if self.fault == 'reused_pid' else 271828)
        elif name == 'stop_cmdline':
            data = b'/system/bin/unrelated\0' if self.fault == 'wrong_stop_cmdline' else self.native_cmdline()
        elif name == 'stop_trace':
            journal = json.loads((self.keyboard.output / 'journal.json').read_text())
            self.setting_restore_preceded_stop = journal['restoreAttempted']
            if self.fault == 'stop_guard_failed': code = 1
        elif name.startswith('stop_poll_'):
            data = proc_stat() if self.fault == 'stop_unconfirmed' else b'ABSENT\n'
        elif name == 'trace_size':
            size = (capture.MAX_TRACE_BYTES if self.fault == 'at_file_limit' else
                    0 if self.fault == 'zero_trace' else len(TRACE))
            data = f'{size}\n'.encode()
        elif name == 'trace_digest':
            digest = '0' * 64 if self.fault == 'remote_digest' else raw.sha(TRACE)
            data = (digest + '  ' + capture.remote_paths(SOURCE)['trace'] + '\n').encode()
        elif name == 'cleanup_absent' and self.fault == 'cleanup_failed': code = 1
        return data, {'status': 'completed', 'exitCode': code, 'exceptionType': None}

    def stream(self, argv, seconds, limit, destination):
        self.commands.append('trace_pull')
        partial = self.fault in {'short_pull', 'pull_failed'}
        Path(destination).write_bytes(b'' if self.fault == 'zero_trace' else TRACE[:-4] if partial else TRACE)
        return b'', {'status': 'completed', 'exitCode': 1 if self.fault == 'pull_failed' else 0,
                     'exceptionType': None}

    def runner(self, directory, source):
        return capture.CommandRunner(directory, source, self.execute, self.stream)

    def compare(self, args, source):
        self.comparison_calls += 1
        if self.fault == 'comparison_raises': raise RuntimeError('synthetic comparison interruption')
        return keyboard.run_probe(args, source, self.keyboard.top_factory, self.keyboard.arm_factory)

    def run(self):
        return capture.run_probe(self.args, SOURCE, self.runner, self.compare, sleep=lambda _: None)


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='reset-trace-synthetic-')
        self.addCleanup(self.temporary.cleanup)
        for module, name, value in (
                (capture, 'source_files', IDENTITIES),
                (keyboard, 'source_files', KEYBOARD_IDENTITIES),
                (raw, 'source_files', RAW_IDENTITIES),
                (raw_tests, 'SOURCE', SOURCE),
                (raw_tests, 'IDENTITIES', RAW_IDENTITIES)):
            manager = patch.object(module, name, return_value=value) if name == 'source_files' else patch.object(module, name, value)
            manager.start(); self.addCleanup(manager.stop)

    def driver(self, **options):
        driver = SyntheticCapture(self.temporary.name, **options)
        capture.prepare(driver.output, SOURCE)
        return driver

    def values(self, driver):
        values = capture.read_payloads(driver.output)
        capture.validate_evidence(values, SOURCE)
        return values

    def test_complete_capture_preserves_original_arm_failure_and_restores_before_stop(self):
        driver = self.driver()
        self.assertTrue(driver.run())
        values = self.values(driver)
        report = values['report.json']
        self.assertEqual('recorded', report['status'])
        help_result = next(item for item in values['commands.json']['commands'] if item['id'] == 'perfetto_help')
        self.assertEqual(1, help_result['exitCode'])
        self.assertEqual(help_bytes(), raw.command_data(help_result))
        self.assertTrue(capture.perfetto_help_accepted(help_result))
        self.assertTrue(capture.command_accepted(help_result))
        self.assertEqual('failed', report['comparison']['arms']['baseline']['status'])
        self.assertEqual('passed', report['comparison']['arms']['enabled']['status'])
        self.assertTrue(driver.setting_restore_preceded_stop)
        self.assertEqual(1, driver.comparison_calls)
        self.assertEqual(1, driver.commands.count('start_trace'))
        self.assertEqual(1, driver.commands.count('stop_trace'))
        self.assertEqual(TRACE, values['trace.pftrace'])
        for arm in ('baseline', 'enabled'):
            self.assertEqual(1, values[f'comparison/{arm}/fixture.json']['showImeAttempts'])
            self.assertEqual(12000, values[f'comparison/{arm}/raw-result.json']['limits']['observationMs'])

    def test_negative_enabled_arm_is_not_promoted_when_capture_completes(self):
        driver = self.driver(enabled_positive=False)
        self.assertTrue(driver.run())
        report = self.values(driver)['report.json']
        self.assertEqual('recorded', report['status'])
        self.assertEqual('failed', report['comparison']['status'])
        self.assertEqual('RAW_POSITIVE_CALLBACK_NOT_OBSERVED', report['comparison']['arms']['enabled']['failureReason'])
        self.assertEqual(0, report['comparison']['arms']['enabled']['positiveCallbackCount'])

    def test_unsupported_background_wait_has_no_fallback_or_comparison(self):
        driver = self.driver(fault='unsupported')
        self.assertFalse(driver.run())
        self.assertEqual(0, driver.comparison_calls)
        self.assertNotIn('start_trace', driver.commands)
        self.assertNotIn('stop_trace', driver.commands)
        self.assertEqual('RESET_PERFETTO_HELP_UNSUPPORTED_OR_INCOMPLETE', self.values(driver)['report.json']['failureReason'])

    def test_observed_help_fixture_is_exact_and_has_no_screenshot_dependency(self):
        self.assertEqual(4425, len(help_bytes()))
        self.assertEqual(HELP_SHA256, raw.sha(help_bytes()))
        self.assertEqual('fixtures', HELP_FIXTURE.parent.name)
        self.assertEqual(Path(__file__).resolve().parent, HELP_FIXTURE.parent.parent)

    def test_complete_help_exit_zero_is_also_accepted_across_capture_and_validation(self):
        driver = self.driver(help_exit=0)
        self.assertTrue(driver.run())
        values = self.values(driver)
        command = next(item for item in values['commands.json']['commands'] if item['id'] == 'perfetto_help')
        self.assertEqual(0, command['exitCode'])
        self.assertTrue(capture.perfetto_help_accepted(command))
        self.assertTrue(capture.command_accepted(command))
        self.assertEqual('recorded', values['report.json']['status'])

    def test_help_acceptance_is_semantic_not_an_exact_runtime_fixture_hash(self):
        changed = help_bytes().replace(b'Exits immediately', b'Exits promptly')
        self.assertNotEqual(HELP_SHA256, raw.sha(changed))
        for data in (changed, changed.replace(b'\n', b'\r\n')):
            with self.subTest(crlf=b'\r\n' in data):
                command = help_command(data)
                self.assertTrue(capture.perfetto_help_accepted(command))
                self.assertTrue(capture.command_accepted(command))
        driver = self.driver(help_output=changed)
        self.assertTrue(driver.run())
        self.assertEqual('recorded', self.values(driver)['report.json']['status'])

    def test_help_command_state_and_output_identity_are_required(self):
        original = help_command()
        changes = ({'status': 'timed_out'}, {'status': 'output_limit'},
                   {'status': 'cancelled'}, {'status': 'running'},
                   {'status': 'exception', 'exceptionType': 'RuntimeError'},
                   {'exitCode': 2}, {'exitCode': None}, {'exitCode': True},
                   {'exceptionType': 'RuntimeError'}, {'bytes': 4424},
                   {'sha256': '0' * 64}, {'outputBase64': 'not base64'})
        for change in changes:
            with self.subTest(change=change):
                command = dict(original, **change)
                self.assertFalse(capture.perfetto_help_accepted(command))
                self.assertFalse(capture.command_accepted(command))

    def test_invalid_help_has_no_start_or_comparison_and_is_preserved(self):
        original = help_bytes()
        without_line = lambda start: b'\n'.join(line for line in original.split(b'\n') if not line.startswith(start))
        cases = {
            'unknown_exit': {'help_exit': 2},
            'timeout': {'help_status': 'timed_out', 'help_exit': None},
            'output_limit': {'help_status': 'output_limit', 'help_exit': None},
            'transport_error': {'help_status': 'exception', 'help_exit': None},
            'error_prefix': {'help_output': b'adb: error: connection closed\n' + original},
            'error_suffix': {'help_output': original + b'Error: permission denied\n'},
            'embedded_error': {'help_output': original.replace(b'\nLight configuration flags:', b'\n  Error: permission denied\n\nLight configuration flags:')},
            'truncated': {'help_output': original[:-50]},
            'missing_final_newline': {'help_output': original.rstrip(b'\n')},
            'missing_wait': {'help_output': without_line(b'  --background-wait ')},
            'missing_config': {'help_output': without_line(b'  --config ')},
            'missing_output': {'help_output': without_line(b'  --out ')},
            'missing_text_format': {'help_output': without_line(b'  --txt ')},
            'missing_section': {'help_output': without_line(b'Statsd-specific ')},
            'wrong_usage': {'help_output': original.replace(b'Usage: /system/bin/perfetto', b'Usage: /vendor/bin/perfetto')},
            'wrong_footer': {'help_output': original.replace(b'Exit code:  0:Yes, 2:No, 1:Error.', b'Exit code: unknown.')},
        }
        for name, options in cases.items():
            with self.subTest(case=name), tempfile.TemporaryDirectory() as root:
                driver = SyntheticCapture(root, **options); capture.prepare(driver.output, SOURCE)
                self.assertFalse(driver.run())
                values = self.values(driver)
                self.assertEqual(0, driver.comparison_calls)
                self.assertNotIn('start_trace', driver.commands)
                self.assertNotIn('stop_trace', driver.commands)
                self.assertEqual('unverified', values['report.json']['status'])
                self.assertEqual('RESET_PERFETTO_HELP_UNSUPPORTED_OR_INCOMPLETE', values['report.json']['failureReason'])
                command = next(item for item in values['commands.json']['commands'] if item['id'] == 'perfetto_help')
                self.assertFalse(capture.perfetto_help_accepted(command))
                self.assertFalse(capture.command_accepted(command))

    def test_recorded_receipt_rejects_forged_help_success(self):
        driver = self.driver(); self.assertTrue(driver.run())
        original = self.values(driver)
        bad_text = help_bytes().replace(b'--background-wait', b'--background-unsupported')
        cases = ({'exitCode': 2}, {'status': 'timed_out', 'exitCode': None},
                 {**raw.identity(bad_text), 'outputBase64': base64.b64encode(bad_text).decode()})
        for change in cases:
            with self.subTest(change=list(change)):
                values = copy.deepcopy(original)
                command = next(item for item in values['commands.json']['commands'] if item['id'] == 'perfetto_help')
                command.update(change)
                with self.assertRaises(ValueError): capture.validate_evidence(values, SOURCE)

    def test_start_exit_one_with_valid_pid_is_not_a_help_exception(self):
        driver = self.driver(fault='start_exit_one')
        self.assertFalse(driver.run())
        values = self.values(driver)
        command = next(item for item in values['commands.json']['commands'] if item['id'] == 'start_trace')
        self.assertEqual(b'5731\n', raw.command_data(command))
        self.assertEqual(1, command['exitCode'])
        self.assertFalse(capture.command_accepted(command))
        self.assertEqual(0, driver.comparison_calls)
        self.assertNotIn('start_stat', driver.commands)
        self.assertNotIn('stop_trace', driver.commands)
        self.assertEqual('unverified', values['report.json']['status'])

    def test_start_identity_errors_never_signal_an_unidentified_process(self):
        for fault in ('ambiguous_pid', 'wrong_start_pid', 'wrong_start_cmdline'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as root:
                driver = SyntheticCapture(root, fault=fault); capture.prepare(driver.output, SOURCE)
                self.assertFalse(driver.run())
                self.assertEqual(0, driver.comparison_calls)
                self.assertNotIn('stop_trace', driver.commands)
                self.assertEqual('unverified', self.values(driver)['report.json']['status'])

    def test_early_exit_or_pid_reuse_retains_trace_without_signalling_replacement(self):
        for fault in ('early_exit', 'reused_pid'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as root:
                driver = SyntheticCapture(root, fault=fault); capture.prepare(driver.output, SOURCE)
                self.assertFalse(driver.run())
                self.assertEqual(1, driver.comparison_calls)
                self.assertNotIn('stop_trace', driver.commands)
                self.assertIn('trace_pull', driver.commands)
                values = self.values(driver)
                self.assertEqual(TRACE, values['trace.pftrace'])
                self.assertEqual('BEFORE_STOP', values['report.json']['trace']['exitReason'])
                self.assertFalse(values['report.json']['trace']['stopConfirmed'])
                self.assertEqual('unverified', values['report.json']['status'])

    def test_same_pid_wrong_command_line_is_neither_signalled_nor_pulled(self):
        driver = self.driver(fault='wrong_stop_cmdline')
        self.assertFalse(driver.run())
        self.assertEqual(1, driver.comparison_calls)
        self.assertNotIn('stop_trace', driver.commands)
        self.assertNotIn('trace_pull', driver.commands)
        self.assertEqual('unverified', self.values(driver)['report.json']['status'])

    def test_signal_or_exit_confirmation_failure_does_not_pull(self):
        for fault in ('stop_guard_failed', 'stop_unconfirmed'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as root:
                driver = SyntheticCapture(root, fault=fault); capture.prepare(driver.output, SOURCE)
                self.assertFalse(driver.run())
                self.assertEqual(1, driver.commands.count('stop_trace'))
                self.assertNotIn('trace_pull', driver.commands)
                self.assertFalse(self.values(driver)['report.json']['trace']['stopConfirmed'])

    def test_reaching_trace_file_limit_retains_prefix_but_cannot_record_success(self):
        driver = self.driver(fault='at_file_limit')
        self.assertFalse(driver.run())
        self.assertIn('trace_pull', driver.commands)
        self.assertNotIn('trace_digest', driver.commands)
        self.assertEqual(TRACE, self.values(driver)['trace.pftrace'])
        report = self.values(driver)['report.json']
        self.assertEqual(capture.MAX_TRACE_BYTES, report['trace']['remoteBytes'])
        self.assertEqual('RESET_TRACE_RESOURCE_LIMIT', report['failureReason'])

    def test_empty_original_is_archivable_as_unverified_resource_failure(self):
        driver = self.driver(fault='zero_trace')
        self.assertFalse(driver.run())
        self.assertIn('trace_pull', driver.commands)
        self.assertNotIn('trace_digest', driver.commands)
        values = self.values(driver)
        self.assertEqual(b'', values['trace.pftrace'])
        self.assertEqual(0, values['report.json']['trace']['file']['bytes'])
        self.assertEqual(raw.sha(b''), values['report.json']['trace']['file']['sha256'])
        self.assertEqual('RESET_TRACE_RESOURCE_LIMIT', values['report.json']['failureReason'])

    def test_forged_exit_observation_cannot_authorize_reading_a_live_trace(self):
        driver = self.driver(fault='wrong_stop_cmdline')
        self.assertFalse(driver.run())
        values = self.values(driver)
        values['report.json']['trace'].update(exitObserved=True, exitReason='BEFORE_STOP')
        with self.assertRaises(ValueError): capture.validate_evidence(values, SOURCE)

    def test_comparison_exception_runs_setting_restoration_before_trace_stop(self):
        driver = self.driver(fault='comparison_raises')
        events = []
        original_restore = keyboard.restore
        original_execute = driver.execute
        def restoration(*args, **kwargs):
            events.append('setting_restore')
            return original_restore(*args, **kwargs)
        def execute(*args, **kwargs):
            name = json.loads((driver.output / 'commands.json').read_text())['commands'][-1]['id']
            if name == 'stop_trace': events.append('trace_stop')
            return original_execute(*args, **kwargs)
        with patch.object(keyboard, 'restore', side_effect=restoration), patch.object(driver, 'execute', side_effect=execute):
            self.assertFalse(driver.run())
        self.assertIn('trace_stop', events)
        self.assertLess(events.index('setting_restore'), events.index('trace_stop'))
        self.assertEqual([], driver.keyboard.calls)
        report = self.values(driver)['report.json']
        self.assertEqual('HOST_RuntimeError', report['failureReason'])
        self.assertTrue(report['trace']['stopConfirmed'])
        self.assertEqual('unverified', report['status'])

    def test_incomplete_pull_preserves_actual_bounded_bytes(self):
        for fault in ('short_pull', 'pull_failed'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as root:
                driver = SyntheticCapture(root, fault=fault); capture.prepare(driver.output, SOURCE)
                self.assertFalse(driver.run())
                values = self.values(driver)
                self.assertEqual(TRACE[:-4], values['trace.pftrace'])
                self.assertEqual(raw.sha(TRACE[:-4]), values['report.json']['trace']['file']['sha256'])
                self.assertEqual('unverified', values['report.json']['status'])

    def test_wrong_remote_digest_or_cleanup_failure_cannot_record_success(self):
        for fault in ('remote_digest', 'cleanup_failed'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as root:
                driver = SyntheticCapture(root, fault=fault); capture.prepare(driver.output, SOURCE)
                self.assertFalse(driver.run())
                self.assertEqual('unverified', self.values(driver)['report.json']['status'])

    def test_setting_restore_failure_still_stops_owned_trace_without_recording_success(self):
        driver = self.driver(restore_wrong=True)
        self.assertFalse(driver.run())
        report = self.values(driver)['report.json']
        self.assertEqual('failed_or_unobserved', report['comparison']['restoration']['status'])
        self.assertTrue(driver.setting_restore_preceded_stop)
        self.assertTrue(report['trace']['stopConfirmed'])
        self.assertEqual('unverified', report['status'])

    def test_finalization_never_repeats_stop_or_upgrades_failed_comparison(self):
        driver = self.driver(enabled_positive=False)
        self.assertTrue(driver.run())
        before = list(driver.commands)
        capture.finalize(driver.output, SOURCE, 'success', driver.runner)
        self.assertEqual(before, driver.commands)
        report = self.values(driver)['report.json']
        self.assertEqual('recorded', report['status'])
        self.assertEqual('failed', report['comparison']['status'])

    def test_workflow_failure_downgrades_complete_capture(self):
        driver = self.driver(); self.assertTrue(driver.run())
        capture.finalize(driver.output, SOURCE, 'failure', driver.runner)
        self.assertEqual('unverified', self.values(driver)['report.json']['status'])

    def test_trace_bytes_owner_identity_and_command_tampering_are_rejected(self):
        driver = self.driver(); self.assertTrue(driver.run())
        original = self.values(driver)
        edits = [
            lambda value: value.__setitem__('trace.pftrace', TRACE + b'changed'),
            lambda value: value['report.json']['trace']['owner'].__setitem__('startTimeTicks', 271829),
            lambda value: value['report.json']['trace'].__setitem__('stopConfirmed', False),
            lambda value: value['commands.json']['commands'][0].__setitem__('sha256', '0' * 64),
            lambda value: next(item for item in value['commands.json']['commands'] if item['id'] == 'stop_trace')['argv'].__setitem__(-1, 'kill -INT 99999'),
        ]
        for index, edit in enumerate(edits):
            with self.subTest(edit=index):
                values = copy.deepcopy(original); edit(values)
                with self.assertRaises(ValueError): capture.validate_evidence(values, SOURCE)

    def test_source_bytes_are_verified_separately_against_executing_checkout(self):
        driver = self.driver(); values = self.values(driver)
        capture.verify_source_files(values['report.json'], self.temporary.name)
        values['report.json']['sourceFiles'][capture.EXTRA_SOURCES[0]]['sha256'] = '0' * 64
        with self.assertRaises(ValueError): capture.verify_source_files(values['report.json'], self.temporary.name)

    def test_partial_analysis_is_preserved_without_becoming_capture_evidence(self):
        driver = self.driver(); self.assertTrue(driver.run())
        path = driver.output / 'analysis'; path.mkdir()
        (path / 'trace-slices.csv').write_bytes(b'partial,query\n')
        (path / 'analysis.json').write_bytes(b'{"interrupted":')
        values = self.values(driver)
        self.assertEqual(b'partial,query\n', values['analysis/trace-slices.csv'])
        self.assertEqual(b'{"interrupted":', values['analysis/analysis.json'])
        (path / 'unexpected.csv').write_bytes(b'not allowed')
        with self.assertRaises(ValueError): capture.read_payloads(driver.output)

    def test_symlink_cannot_substitute_an_original(self):
        driver = self.driver(); self.assertTrue(driver.run())
        trace = driver.output / 'trace.pftrace'
        moved = self.root_file('outside.pftrace', TRACE)
        trace.unlink(); trace.symlink_to(moved)
        with self.assertRaises(ValueError): capture.read_payloads(driver.output)

    def root_file(self, name, data):
        path = Path(self.temporary.name) / name; path.write_bytes(data); return path

    def test_adb_remote_shell_join_keeps_each_script_as_one_argument(self):
        report = capture.empty_report(SOURCE, IDENTITIES)
        report['tools'] = {'adb': '/synthetic/adb', 'sdkRoot': '/synthetic',
                           'config': '/synthetic/trace-config.pbtxt'}
        report['device'] = {'serial': 'emulator-5554'}
        report['trace']['owner'] = {'pid': 5731, 'startTimeTicks': 271828,
                                    'cmdlineSha256': 'a' * 64}
        scripts = {}
        for name in ('start_trace', 'start_stat', 'stop_stat', 'stop_poll_0',
                     'stop_trace', 'cleanup_absent'):
            with self.subTest(command=name):
                argv = capture.command_argv(name, report)
                self.assertEqual('shell', argv[3])
                # adb joins its command arguments before the remote shell parses
                # them. Merely passing an unquoted Python string is insufficient.
                remote = shlex.split(' '.join(argv[4:]))
                self.assertEqual(['sh', '-c'], remote[:2])
                self.assertEqual(3, len(remote))
                scripts[name] = remote[2]
                parsed = subprocess.run(['/bin/sh', '-n', '-c', remote[2]],
                                        capture_output=True, timeout=2)
                self.assertEqual(0, parsed.returncode, parsed.stderr.decode())
        start = shlex.split(scripts['start_trace'])
        self.assertEqual('exec', start[0])
        redirect = start.index('<')
        self.assertEqual(['/system/bin/perfetto', '--txt', '-c', '-', '-o',
                          capture.remote_paths(SOURCE)['trace'], '--background-wait'],
                         start[1:redirect])
        self.assertEqual([capture.remote_paths(SOURCE)['config']], start[redirect + 1:])

    def test_local_stream_uses_os_file_size_bound_and_retains_prefix(self):
        destination = Path(self.temporary.name) / 'bounded.pftrace'
        command = [sys.executable, '-c', 'import os; data = b"x" * 131072; written = 0\nwhile written < len(data): written += os.write(1, data[written:])']
        with patch.object(capture, 'MAX_TRACE_BYTES', 32768):
            stderr, outcome = capture.stream_trace(command, 3, 4096, destination)
        self.assertLessEqual(destination.stat().st_size, 32768)
        self.assertEqual(b'x' * destination.stat().st_size, destination.read_bytes())
        self.assertFalse(outcome['status'] == 'completed' and outcome['exitCode'] == 0)

    def test_stream_never_overwrites_an_existing_file(self):
        destination = self.root_file('existing.pftrace', b'keep original')
        with self.assertRaises(ValueError):
            capture.stream_trace([sys.executable, '-c', 'print("new")'], 2, 1024, destination)
        self.assertEqual(b'keep original', destination.read_bytes())


if __name__ == '__main__':
    unittest.main()
