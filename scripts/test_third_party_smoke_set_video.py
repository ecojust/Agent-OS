"""Synthetic orchestration tests; no device operation or video acceptance evidence."""
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import third_party_smoke_set as probe
from test_third_party_smoke_set import SOURCE, ui_results


class OptionalThirdPartyVideoTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.args = SimpleNamespace(api='35', output=self.root / 'probe',
                                    video_output=self.root / 'video')
        self.command = ['adb-test', '-s', 'emulator-5554', 'shell', 'am', 'instrument']

    def invoke(self):
        return probe.run_instrumentation(self.command, self.args, SOURCE, 'adb-test', 'emulator-5554')

    def recorder_module(self, callback=None):
        recorder = mock.Mock(side_effect=callback or (lambda *values: values[-1]()))
        return recorder, mock.patch.dict(sys.modules, {'probe_video': SimpleNamespace(record_call=recorder)})

    def test_no_video_option_keeps_the_original_single_600_second_invocation(self):
        for present in (False, True):
            with self.subTest(present=present), mock.patch.object(probe.base, 'run_command', return_value='original') as command:
                if present:
                    self.args.video_output = None
                else:
                    self.args.__dict__.pop('video_output', None)
                recorder, module = self.recorder_module()
                with module:
                    self.assertEqual('original', self.invoke())
                command.assert_called_once_with(self.command, timeout=600)
                recorder.assert_not_called()

    def test_recording_wraps_one_unchanged_invocation_with_exact_scope(self):
        marker = object()
        recorder, module = self.recorder_module()
        with module, mock.patch.object(probe.base, 'run_command', return_value=marker) as command:
            self.assertIs(marker, self.invoke())
        command.assert_called_once_with(self.command, timeout=600)
        self.assertEqual(1, recorder.call_count)
        self.assertEqual((self.args.video_output.resolve(), SOURCE, 'third-party-app-set', 35,
                          'adb-test', 'emulator-5554'), recorder.call_args.args[:-1])

    def test_original_callback_exception_is_not_replayed_or_replaced(self):
        failure = OSError('synthetic instrumentation transport failure')
        recorder, module = self.recorder_module()
        with module, mock.patch.object(probe.base, 'run_command', side_effect=failure) as command:
            with self.assertRaises(OSError) as raised:
                self.invoke()
        self.assertIs(failure, raised.exception)
        command.assert_called_once_with(self.command, timeout=600)
        self.assertEqual(1, recorder.call_count)

    def test_recording_internal_failure_keeps_the_completed_result_without_a_retry(self):
        # Even an unexpected recorder failure after a completed callback must
        # preserve that exact result without replaying the UI.
        def recorder_failure(*args):
            args[-1]()
            raise ValueError('synthetic recorder implementation error')
        recorder, module = self.recorder_module(recorder_failure)
        with module, mock.patch.object(probe.base, 'run_command', return_value='original') as command:
            self.assertEqual('original', self.invoke())
        command.assert_called_once_with(self.command, timeout=600)

    def test_recorder_failure_before_execution_still_runs_the_ui_exactly_once(self):
        recorder, module = self.recorder_module(mock.Mock(side_effect=OSError('synthetic recorder failure')))
        with module, mock.patch.object(probe.base, 'run_command', return_value='original') as command:
            self.assertEqual('original', self.invoke())
        command.assert_called_once_with(self.command, timeout=600)

    def test_recorder_cannot_repeat_the_original_ui_operation(self):
        def duplicate(*args):
            args[-1]()
            args[-1]()
        recorder, module = self.recorder_module(duplicate)
        with module, mock.patch.object(probe.base, 'run_command', return_value='original') as command:
            self.assertEqual('original', self.invoke())
        command.assert_called_once_with(self.command, timeout=600)

    def test_missing_callback_cannot_skip_the_compatibility_probe(self):
        recorder, module = self.recorder_module(lambda *args: 'not the probe result')
        with module, mock.patch.object(probe.base, 'run_command', return_value='original') as command:
            self.assertEqual('original', self.invoke())
        command.assert_called_once_with(self.command, timeout=600)

    def test_recorder_cannot_swallow_a_probe_failure_and_claim_success(self):
        failure = OSError('synthetic original failure')
        def swallowing(*args):
            try:
                args[-1]()
            except OSError:
                return 'OK (4 tests)'
        recorder, module = self.recorder_module(swallowing)
        with module, mock.patch.object(probe.base, 'run_command', side_effect=failure) as command:
            with self.assertRaises(OSError) as raised:
                self.invoke()
        self.assertIs(failure, raised.exception)
        command.assert_called_once_with(self.command, timeout=600)

    def test_overlapping_output_paths_disable_video_without_touching_original_artifact(self):
        self.args.output.mkdir()
        original = self.args.output / 'report.json'
        original.write_bytes(b'original evidence sentinel')
        for destination in (self.args.output, self.args.output / 'video', self.root):
            with self.subTest(destination=destination):
                self.args.video_output = destination
                recorder, module = self.recorder_module()
                with module, mock.patch.object(probe.base, 'run_command', return_value='original') as command:
                    self.assertEqual('original', self.invoke())
                command.assert_called_once_with(self.command, timeout=600)
                recorder.assert_not_called()
                self.assertEqual(b'original evidence sentinel', original.read_bytes())

    def test_symlink_alias_cannot_put_video_inside_the_original_artifact(self):
        self.args.output.mkdir()
        alias = self.root / 'alias'
        alias.symlink_to(self.args.output, target_is_directory=True)
        self.args.video_output = alias / 'video'
        recorder, module = self.recorder_module()
        with module, mock.patch.object(probe.base, 'run_command', return_value='original') as command:
            self.assertEqual('original', self.invoke())
        command.assert_called_once_with(self.command, timeout=600)
        recorder.assert_not_called()

    def test_nonrepresentative_api_keeps_probe_but_never_starts_video(self):
        for api in ('30', '31', '32', '33', '34', '36', '37.0'):
            with self.subTest(api=api):
                self.args.api = api
                recorder, module = self.recorder_module()
                with module, mock.patch.object(probe.base, 'run_command', return_value='original') as command:
                    self.assertEqual('original', self.invoke())
                command.assert_called_once_with(self.command, timeout=600)
                recorder.assert_not_called()

    def test_missing_optional_module_does_not_prevent_original_probe(self):
        with mock.patch.dict(sys.modules, {'probe_video': None}), \
                mock.patch.object(probe.base, 'run_command', return_value='original') as command:
            self.assertEqual('original', self.invoke())
        command.assert_called_once_with(self.command, timeout=600)

    def make_full_probe(self, directory, video, fail_ui=False):
        lock = probe.load_lock()
        sdk = self.root / 'sdk'
        (sdk / 'emulator').mkdir(parents=True, exist_ok=True)
        (sdk / 'emulator/source.properties').write_text('Pkg.Revision=37.2.12\n')
        apk = self.root / 'synthetic.apk'
        apk.write_bytes(b'synthetic APK fixture; no installation')
        args = SimpleNamespace(api='35', output=directory, video_output=video, sdk_root=sdk,
                               agent_apk=apk, calculator_apk=apk, notes_apk=apk,
                               probe_apk=apk, test_apk=apk)
        ui = ui_results(lock, captures=False)
        output = 'OK (4 tests)\n'
        if fail_ui:
            ui['apps'][1]['states'][1]['status'] = 'failed'
            output = 'FAILURES!!!\nTests run: 4, Failures: 1\n'
        properties = {'ro.kernel.qemu': '1', 'ro.boot.qemu': '1', 'ro.build.version.sdk': '35',
                      'ro.product.cpu.abi': 'x86_64', 'sys.boot_completed': '1',
                      'ro.build.fingerprint': 'synthetic/emulator/API35'}
        calls = []
        def command(argv, **options):
            calls.append((argv, options))
            if argv[-1] == 'devices': return 'List of devices attached\nemulator-5554\tdevice\n'
            if 'getprop' in argv: return properties[argv[-1]] + '\n'
            if argv[-2:] == ['am', 'get-current-user']: return '0\n'
            if argv[-3:] == ['pm', 'list', 'packages']: return ''
            if 'install' in argv: return 'Success\n'
            if 'instrument' in argv: return output
            if argv[-1] == 'files/third-party-smoke-v4/ui-results.json': return json.dumps(ui).encode()
            self.fail('Unexpected command in synthetic fixture: ' + repr(argv))
        with mock.patch.object(probe.base, 'run_command', side_effect=command), \
                mock.patch.object(probe.base, 'verify_apk', return_value={'verified': 'synthetic-host-only'}):
            result = probe.run_probe(args, lock, SOURCE)
        return result, (directory / 'report.json').read_bytes(), calls

    def test_full_four_case_success_report_is_unchanged_by_recording(self):
        plain = self.make_full_probe(self.root / 'plain', None)
        recorder, module = self.recorder_module()
        with module:
            recorded = self.make_full_probe(self.root / 'recorded', self.root / 'video')
        self.assertTrue(plain[0]); self.assertTrue(recorded[0])
        self.assertEqual(plain[1], recorded[1])
        self.assertEqual(1, recorder.call_count)
        instrumentation = [(argv, options) for argv, options in recorded[2] if 'instrument' in argv]
        self.assertEqual(1, len(instrumentation))
        self.assertEqual(600, instrumentation[0][1]['timeout'])
        self.assertIn('dev.agentos.compat.ApplicationSetCompatibilityInstrumentedTest', instrumentation[0][0])
        self.assertFalse(any('#' in word for word in instrumentation[0][0]))
        report = json.loads(recorded[1])
        self.assertEqual(4, sum(len(app['states']) for app in report['apps']))
        self.assertEqual({'passed'}, {state['status'] for app in report['apps'] for state in app['states']})

    def test_full_failed_case_result_is_preserved_without_retry_or_omission(self):
        plain = self.make_full_probe(self.root / 'plain', None, fail_ui=True)
        recorder, module = self.recorder_module()
        with module:
            recorded = self.make_full_probe(self.root / 'recorded', self.root / 'video', fail_ui=True)
        self.assertFalse(plain[0]); self.assertFalse(recorded[0])
        self.assertEqual(plain[1], recorded[1])
        self.assertEqual(1, len([argv for argv, _ in recorded[2] if 'instrument' in argv]))
        report = json.loads(recorded[1])
        self.assertEqual('failed', report['status'])
        self.assertEqual(['passed', 'passed', 'passed', 'failed'],
                         [state['status'] for app in report['apps'] for state in app['states']])


if __name__ == '__main__':
    unittest.main()
