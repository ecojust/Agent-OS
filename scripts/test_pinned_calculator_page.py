"""Trust/denominator checks for the fixed real-APK predicate runner (no Android simulation)."""
import copy
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import zlib

import pinned_calculator_page as page
import third_party_smoke as base

BINDING = {'repository': 'ecojust/Agent-OS', 'commit': 'a' * 40, 'runId': 123, 'attempt': 2}
SERIAL = 'emulator-5554'


def environment():
    return {'GITHUB_ACTIONS': 'true', 'GITHUB_REPOSITORY': 'ecojust/Agent-OS',
            'GITHUB_REF': 'refs/heads/master', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
            'GITHUB_WORKFLOW_REF': 'ecojust/Agent-OS/.github/workflows/pinned-calculator-page.yml@refs/heads/master',
            'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2'}


def png():
    # File-parser fixture only; never used or labelled as a UI screenshot.
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 64, 64, 8, 2, 0, 0, 0)) + \
        chunk(b'IDAT', zlib.compress((b'\x00' + b'\x22\x33\x44' * 64) * 64)) + chunk(b'IEND', b'')


def records():
    files = {}
    for name, expected in page.CASES.items():
        stages = []
        for i, (stage, outcome) in enumerate(expected):
            metadata = name + '-' + str(i) + '.json'
            sample = stage + '-before-predicate'
            files[metadata] = json.dumps({'stage': sample, 'nodes': [], 'sampleRole': 'Independent diagnostic'}).encode()
            stages += [{'stage': sample, 'metadata': metadata, 'captureStatus': 'blocked',
                        'captureReason': 'platform_refused_or_secure', 'secureFlagsCleared': False},
                       dict(stage=stage, predicate=outcome, **({'reason': 'UNKNOWN_CONTENT'} if outcome == 'rejected' else {}))]
        case = {'schemaVersion': 1, 'kind': 'pinned-calculator-page-predicate', 'testClass': page.TEST_CLASS,
                'testName': name, 'api': 34, 'sourceSha': BINDING['commit'], 'runId': '123', 'runAttempt': '2',
                'deviceSerial': SERIAL, 'status': 'passed', 'stages': stages,
                'installedArtifactVerified': True, 'captureCapabilityAbsent': True,
                'imageAndGestureToolsAbsent': True, 'modelRequests': 0}
        files[name + '.case.json'] = json.dumps(case).encode()
    return files


def collect(files, directory):
    def command(argv, **kwargs):
        if argv[-2:] == ['ls', 'files/pinned-calculator-page']:
            return '\n'.join(files)
        return files[argv[-1].rsplit('/', 1)[-1]]
    return page.collect_cases('adb', SERIAL, directory, BINDING, 34, command=command)


class PinnedPageDriverTests(unittest.TestCase):
    def test_recording_opt_in_is_only_standard_api35_and_keeps_evidence_separate(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'cases'; video = Path(temp) / 'video'
            args = SimpleNamespace(api='35', output=output, video_output=video)
            self.assertEqual(video, page.video_output(args, page))
            self.assertIsNone(page.video_output(SimpleNamespace(api='34', output=output), page))
            for api in ('34', '36', '37.0'):
                with self.subTest(api=api), self.assertRaises(base.ProbeError):
                    page.video_output(SimpleNamespace(api=api, output=output, video_output=video), page)
            with self.assertRaises(base.ProbeError):
                page.video_output(args, SimpleNamespace(KIND='pinned-window-disclosure'))
            for target in (output, output / 'video', Path(temp)):
                with self.subTest(target=target), self.assertRaises(base.ProbeError):
                    page.video_output(SimpleNamespace(api='35', output=output, video_output=target), page)
            link = Path(temp) / 'linked-cases'; link.symlink_to(output, target_is_directory=True)
            with self.assertRaises(base.ProbeError):
                page.video_output(SimpleNamespace(api='35', output=output, video_output=link), page)
            self.assertFalse(output.exists()); self.assertFalse(video.exists())

    def test_invalid_recording_scope_is_rejected_before_device_or_evidence_operations(self):
        args = SimpleNamespace(api='34', output=Path('/unused/cases'), video_output=Path('/unused/video'))
        with patch.object(page.base, 'ensure_directory') as directory, patch.object(page.base, 'run_command') as command:
            with self.assertRaises(base.ProbeError): page.run(args, BINDING)
        directory.assert_not_called(); command.assert_not_called()

    def test_recording_wraps_the_unchanged_bounded_call_and_preserves_success_or_failure_output(self):
        command = ['python3', 'run_bounded_instrumentation.py', '--timeout-seconds', '360', '--', 'adb', 'shell', 'am', 'instrument']
        for code, stdout in ((0, b'OK (5 tests)\n'), (1, b'FAILURES!!!\nTests run: 5, Failures: 1\n')):
            original = subprocess.CompletedProcess(command, code, stdout=stdout)
            recorder = Mock(side_effect=lambda *args: args[-1]())
            with self.subTest(code=code), patch.dict(sys.modules, {'probe_video': SimpleNamespace(record_call=recorder)}), \
                    patch.object(page.subprocess, 'run', return_value=original) as invoke:
                result = page.run_instrumentation(command, BINDING, 35, '/sdk/adb', SERIAL, Path('/separate/video'))
            self.assertIs(original, result)
            self.assertEqual(code == 0, page.instrument_result(result.stdout.decode(), result.returncode))
            invoke.assert_called_once_with(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=440, check=False)
            self.assertEqual(1, recorder.call_count)
            self.assertEqual((Path('/separate/video'), BINDING, 'pinned-calculator-page', 35, '/sdk/adb', SERIAL),
                             recorder.call_args.args[:-1])

    def test_recording_preserves_the_original_instrumentation_exception(self):
        original = subprocess.TimeoutExpired(['original-command'], 440)
        recorder = Mock(side_effect=lambda *args: args[-1]())
        with patch.dict(sys.modules, {'probe_video': SimpleNamespace(record_call=recorder)}), \
                patch.object(page.subprocess, 'run', side_effect=original) as invoke:
            with self.assertRaises(subprocess.TimeoutExpired) as caught:
                page.run_instrumentation(['original-command'], BINDING, 35, '/sdk/adb', SERIAL, Path('/separate/video'))
        self.assertIs(original, caught.exception); invoke.assert_called_once(); recorder.assert_called_once()

    def test_default_runner_never_initializes_a_recorder(self):
        original = subprocess.CompletedProcess(['original-command'], 0, stdout=b'OK (5 tests)\n')
        recorder = Mock(side_effect=AssertionError('No recording was requested'))
        with patch.dict(sys.modules, {'probe_video': SimpleNamespace(record_call=recorder)}), \
                patch.object(page.subprocess, 'run', return_value=original) as invoke:
            self.assertIs(original, page.run_instrumentation(['original-command'], BINDING, 35, '/sdk/adb', SERIAL))
        invoke.assert_called_once(); recorder.assert_not_called()

    def test_cli_passes_recording_opt_in_without_changing_the_fixed_case_selection(self):
        arguments = ['run', '--api', '35', '--output', '/cases', '--sdk-root', '/sdk', '--agent-apk', '/agent.apk',
                     '--fixture-apk', '/fixture.apk', '--test-apk', '/tests.apk', '--calculator-apk', '/calculator.apk',
                     '--video-output', '/video']
        with patch.dict(os.environ, environment(), clear=True), patch.object(page, 'run', return_value=True) as invoke:
            self.assertEqual(0, page.main(arguments))
        self.assertEqual(Path('/video'), invoke.call_args.args[0].video_output)
        self.assertEqual('35', invoke.call_args.args[0].api)
        self.assertEqual(BINDING, invoke.call_args.args[1]); self.assertEqual(5, len(page.CASES))

    def test_only_the_explicit_trusted_dispatch_may_operate_devices(self):
        self.assertEqual(BINDING, page.source(environment()))
        for key, value in [('GITHUB_ACTIONS', 'false'), ('GITHUB_REPOSITORY', 'foreign/fork'),
                           ('GITHUB_EVENT_NAME', 'push'), ('GITHUB_REF', 'refs/heads/feature'),
                           ('GITHUB_WORKFLOW_REF', 'ecojust/Agent-OS/.github/workflows/android.yml@refs/heads/master'),
                           ('GITHUB_SHA', 'missing'), ('GITHUB_RUN_ATTEMPT', '0')]:
            with self.subTest(key=key), self.assertRaises(base.ProbeError):
                page.source(dict(environment(), **{key: value}))

    def test_json_metadata_and_blocked_images_preserve_all_five_actual_outcomes(self):
        files = records()
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            result = collect(files, output)
            self.assertEqual(list(page.CASES), [case['name'] for case in result])
            self.assertTrue(all(case['status'] == 'passed' for case in result))
            self.assertEqual(set(files), {path.name for path in (output / 'pinned-page').iterdir()})
            for name, raw in files.items():
                self.assertEqual(raw, (output / 'pinned-page' / name).read_bytes())

    def test_missing_and_failed_cases_stay_in_the_denominator(self):
        files = records(); names = list(page.CASES)
        del files[names[0] + '.case.json']
        failed = json.loads(files[names[1] + '.case.json']); failed.update(status='failed', stages=[])
        files[names[1] + '.case.json'] = json.dumps(failed).encode()
        with tempfile.TemporaryDirectory() as temp:
            result = collect(files, Path(temp))
        self.assertEqual(5, len(result))
        self.assertEqual(['unverified', 'failed', 'passed', 'passed', 'passed'], [case['status'] for case in result])

    def test_stale_busy_timeout_and_wrong_revision_are_not_successful_page_rejections(self):
        files = records(); name = 'actualSettingsPageIsRejected'
        record = json.loads(files[name + '.case.json'])
        rejection = next(stage for stage in record['stages'] if stage.get('predicate') == 'rejected')
        for reason in ('STALE_OBSERVATION', 'BUSY', 'EXPIRED', 'CONFIGURATION_CHANGED'):
            rejection['reason'] = reason
            with self.subTest(reason=reason), self.assertRaises(base.ProbeError):
                page.validate_case(record, BINDING, 34, SERIAL, name)

    def test_foreign_source_case_or_model_contact_cannot_be_marked_passed(self):
        files = records(); name = next(iter(page.CASES)); record = json.loads(files[name + '.case.json'])
        for key, value in [('sourceSha', 'b' * 40), ('runAttempt', '1'), ('api', 35), ('testClass', 'other.Class'),
                           ('deviceSerial', 'emulator-5556'), ('modelRequests', 1), ('modelRequests', False),
                           ('captureCapabilityAbsent', False), ('installedArtifactVerified', False)]:
            with self.subTest(key=key), self.assertRaises(base.ProbeError):
                page.validate_case(dict(record, **{key: value}), BINDING, 34, SERIAL, name)

    def test_image_bytes_hash_dimensions_and_secure_flag_are_bound(self):
        files = records(); name = next(iter(page.CASES)); record = json.loads(files[name + '.case.json'])
        stage = record['stages'][0]; image_name = name + '-99.png'; raw = png(); files[image_name] = raw
        stage.pop('captureReason'); stage.update(captureStatus='captured', image=image_name, imageBytes=len(raw),
            imageSha256=hashlib.sha256(raw).hexdigest(), imageWidth=64, imageHeight=64)
        page.validate_case_files(record, files)
        for field, wrong in [('imageBytes', len(raw) + 1), ('imageSha256', '0' * 64), ('imageWidth', 65), ('secureFlagsCleared', True)]:
            bad = copy.deepcopy(record); bad['stages'][0][field] = wrong
            with self.subTest(field=field), self.assertRaises(base.ProbeError):
                page.validate_case_files(bad, files)
        bad = copy.deepcopy(record); bad['stages'].append(copy.deepcopy(stage))
        with self.assertRaises(base.ProbeError): page.validate_case_files(bad, files)

    def test_blocked_records_cannot_smuggle_images_or_cross_case_metadata(self):
        files = records(); name = next(iter(page.CASES)); record = json.loads(files[name + '.case.json'])
        for key, value in [('image', name + '-99.png'), ('imageBytes', 1), ('secureFlagsCleared', True),
                           ('metadata', 'actualSettingsPageIsRejected-0.json')]:
            bad = copy.deepcopy(record); bad['stages'][0][key] = value
            with self.subTest(key=key), self.assertRaises(base.ProbeError): page.validate_case_files(bad, files)
        files['../escape.case.json'] = b'{}'
        with tempfile.TemporaryDirectory() as temp, self.assertRaises(base.ProbeError): collect(files, Path(temp))

    def test_actual_instrumentation_completion_requires_five_tests_and_success_exit(self):
        self.assertTrue(page.instrument_result('Time: 1\nOK (5 tests)\n', 0))
        for text, code in [('OK (4 tests)', 0), ('OK (5 tests)', 1), ('OK (5 tests)\nshortMsg=Process crashed', 0),
                           ('FAILURES!!!\nOK (5 tests)', 0), ('', 0)]:
            self.assertFalse(page.instrument_result(text, code))

    def test_finalize_of_an_unrun_emulator_keeps_five_unverified_and_no_junit(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, environment(), clear=True):
            output = Path(temp)
            self.assertEqual(0, page.main(['prepare', '--api', '37.0', '--output', str(output)]))
            self.assertEqual(0, page.main(['finalize', '--api', '37.0', '--output', str(output), '--outcome', 'skipped']))
            report = json.loads((output / 'report.json').read_text()); capture = json.loads((output / 'capture.json').read_text())
            self.assertEqual('environment_failed', report['status'])
            self.assertEqual(['unverified'] * 5, [case['status'] for case in report['cases']])
            self.assertEqual(37, capture['apiLevel']); self.assertIsNone(capture['gradleExitCode'])
            self.assertEqual('not_collected', capture['captureStatus']); self.assertNotIn('junit', capture)
            report['source']['commit'] = 'b' * 40; (output / 'report.json').write_text(json.dumps(report))
            with self.assertRaises(base.ProbeError):
                page.main(['finalize', '--api', '37.0', '--output', str(output), '--outcome', 'success'])


if __name__ == '__main__': unittest.main()
