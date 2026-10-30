"""Synthetic parser fixtures only; these are not videos or Android test results."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import busy_stop_video as video
import collect_busy_stop_evidence as busy
import probe_video_archive as archive
import test_probe_video_archive as existing

SOURCE = existing.SOURCE


def instrument(code=0):
    lines = []
    for status in (1, code):
        lines += [f'INSTRUMENTATION_STATUS: class={video.TEST_CLASS}',
                  'INSTRUMENTATION_STATUS: numtests=1',
                  f'INSTRUMENTATION_STATUS: test={video.METHOD}',
                  f'INSTRUMENTATION_STATUS_CODE: {status}']
    lines += ['OK (1 test)' if code == 0 else 'FAILURES!!!\nTests run: 1, Failures: 1',
              'INSTRUMENTATION_CODE: -1']
    return dict(schemaVersion=1, runner='direct_adb_instrumentation', selectedClass=video.TEST_CLASS,
                selectedMethod=video.METHOD, expectedTests=1, status='completed', exitCode=0,
                stdout='\n'.join(lines) + '\n')


def flow(trial=None):
    # The minimal object exercises wrapper delegation. A separate integration
    # test uses the host validator's complete normal DEVICE fixture.
    trial = trial or dict(startedNanoTime=200, endedNanoTime=300, status='passed')
    return dict(schemaVersion=1, kind='agent-os-busy-stop-walkthrough', source=SOURCE,
                apiLevel=35, profile='walkthrough', scenario='DEVICE', status='passed',
                performanceSample=False, secureFlagsPreserved=True,
                startedNanoTime=trial['startedNanoTime'] - 100,
                endedNanoTime=trial['endedNanoTime'] + 100, trial=trial, failure=None)


def write(folder, name, value):
    path = folder / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value) + '\n')
    return video.file_ref(path)


def screenshots(folder, blocked=False, case_passed=True):
    image = b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' + (320).to_bytes(4, 'big') + (640).to_bytes(4, 'big') + b'\x00' * 9
    shot = dict(status='pass', png='synthetic.png', width=320, height=640,
                foregroundPackage='dev.agentos.fixture', secureWindowTemporarilyCleared=False)
    if blocked: shot = dict(status='blocked', captureError='capture_unavailable')
    case = dict(schemaVersion=1, api=35, testClass=video.TEST_CLASS, testName=video.METHOD,
                status='pass' if case_passed else 'fail', assertionResult='pass' if case_passed else 'fail',
                testResult='pass' if case_passed else 'fail', captures=[shot])
    write(folder, 'screenshots/case.json', case)
    write(folder, 'screenshots/manifest.json', dict(schemaVersion=1, api=35, tests=[case]))
    names = ['screenshots/case.json', 'screenshots/manifest.json']
    if not blocked:
        (folder / 'screenshots/synthetic.png').write_bytes(image)
        names.append('screenshots/synthetic.png')
    return dict(schemaVersion=1, apiLevel=35, matrixLabel='35', gradleExitCode=0,
                actualSdkInt=35, captureStatus='collected', files=names, captureError=None,
                capturedAt='2026-10-30T00:00:00+00:00')


def bundle(folder, blocked=False, trial=None):
    report = video.empty_report(SOURCE)
    report.update(status='passed', failureStage=None,
                  instrumentation=write(folder, 'instrumentation.json', instrument()),
                  flow=write(folder, 'flow-result.json', flow(trial)),
                  capture=write(folder, 'capture.json', screenshots(folder, blocked)))
    write(folder, 'report.json', report)
    return report


def scanned(folder):
    return archive.bounded.scan_files(folder, dict(files=0, bytes=0))


class BusyStopVideoBoundaries(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.inner = self.enterContext(mock.patch.object(busy, 'validate_single_device_trial'))

    def validate(self, report):
        write(self.root, 'report.json', report)
        return video.validate_probe(report, scanned(self.root), SOURCE)

    def test_workflow_identity_is_manual_master_and_exact_source(self):
        env = dict(GITHUB_ACTIONS='true', GITHUB_REPOSITORY=SOURCE['repository'],
                   GITHUB_REF='refs/heads/master', GITHUB_EVENT_NAME='workflow_dispatch',
                   GITHUB_WORKFLOW_REF=SOURCE['repository'] + '/' + video.WORKFLOW + '@refs/heads/master',
                   GITHUB_SHA=SOURCE['commit'], GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2')
        self.assertEqual(SOURCE, video.source(env))
        for key, value in [('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REF', 'refs/heads/agent/review'),
                           ('GITHUB_WORKFLOW_REF', 'other'), ('GITHUB_RUN_ATTEMPT', '0'),
                           ('GITHUB_REPOSITORY', 'fork/Agent-OS'), ('GITHUB_SHA', 'a' * 39)]:
            with self.assertRaises(ValueError): video.source(dict(env, **{key: value}))

    def test_success_delegates_one_normal_device_trial_and_keeps_separate_scope(self):
        report = bundle(self.root)
        result = self.validate(report)
        self.inner.assert_called_once_with(flow()['trial'], 35)
        self.assertEqual('passed', result['status'])
        self.assertEqual([dict(name=video.METHOD, status='passed')], result['cases'])
        self.assertFalse(result['performanceSample'])
        self.assertFalse(result['fullSuiteAcceptance'])

    def test_inner_trial_rejection_cannot_be_hidden_by_wrapper_success(self):
        self.inner.side_effect = ValueError('invalid_inner_device_trial')
        with self.assertRaisesRegex(ValueError, 'invalid_inner_device_trial'):
            video.validate_flow(flow(), SOURCE)

    def test_flow_rejects_foreign_source_profile_and_security(self):
        mutations = [dict(source=dict(SOURCE, attempt=3)), dict(apiLevel=34), dict(profile='formal'),
                     dict(scenario='MODEL'), dict(performanceSample=True), dict(secureFlagsPreserved=False),
                     dict(schemaVersion=True), dict(trial=None), dict(status='failed')]
        for change in mutations:
            with self.assertRaises(ValueError): video.validate_flow(dict(flow(), **change), SOURCE)

    def test_flow_bounds_trial_and_does_not_claim_wall_clock_as_monotonic(self):
        for change in [dict(startedNanoTime=250), dict(endedNanoTime=299), dict(endedNanoTime=None),
                       dict(endedNanoTime=90_000_000_101), dict(startedNanoTime=True)]:
            with self.assertRaises(ValueError): video.validate_flow(dict(flow(), **change), SOURCE)
        value = flow(); value['startedNanos'] = value.pop('startedNanoTime')
        with self.assertRaises(ValueError): video.validate_flow(value, SOURCE)

    def test_partial_failure_preserves_original_status(self):
        report = bundle(self.root)
        value = dict(flow(), status='failed', trial=None,
                     failure=dict(stage='setup', errorClass='java.lang.AssertionError'))
        report.update(flow=write(self.root, 'flow-result.json', value), status='failed', failureStage='collect_flow')
        self.assertEqual('failed', self.validate(report)['status'])
        self.inner.assert_not_called()

    def test_started_but_unfinished_instrumentation_remains_unverified(self):
        report = bundle(self.root)
        value = instrument(); value.update(status='timed_out', exitCode=None,
            stdout=value['stdout'].split('INSTRUMENTATION_STATUS_CODE: 1')[0] + 'INSTRUMENTATION_STATUS_CODE: 1\n')
        cap = json.loads((self.root / 'capture.json').read_text()); cap['gradleExitCode'] = 1
        report.update(instrumentation=write(self.root, 'instrumentation.json', value),
                      capture=write(self.root, 'capture.json', cap), status='failed', failureStage='instrumentation')
        self.assertEqual('unverified', self.validate(report)['status'])

    def test_instrumentation_requires_exactly_one_original_successful_case(self):
        self.assertTrue(video.instrumentation_result(instrument())['passed'])
        self.assertTrue(video.instrumentation_result(instrument(-2))['terminalFailure'])
        for replacement in [('numtests=1', 'numtests=2'), (video.METHOD, 'collectBusyStopDiagnostics'),
                            ('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'),
                            ('INSTRUMENTATION_STATUS: numtests=1',
                             'INSTRUMENTATION_STATUS: numtests=1\nINSTRUMENTATION_STATUS: numtests=1')]:
            value = instrument(); value['stdout'] = value['stdout'].replace(*replacement)
            self.assertFalse(video.instrumentation_result(value)['passed'])
        value = instrument(); value['stdout'] *= 2
        self.assertFalse(video.instrumentation_result(value)['passed'])

    def test_blocked_capture_does_not_invent_pixels_or_fail_the_functional_case(self):
        result = self.validate(bundle(self.root, blocked=True))
        self.assertEqual('passed', result['status'])
        self.assertEqual(0, result['captureCounts']['captured'])
        self.assertEqual(1, result['captureCounts']['blocked'])

    def test_secure_flag_clearing_or_foreign_foreground_are_rejected(self):
        for change in [dict(secureWindowTemporarilyCleared=True), dict(foregroundPackage='dev.agentos.app')]:
            report = bundle(self.root)
            case = json.loads((self.root / 'screenshots/case.json').read_text())
            case['captures'][0].update(change)
            write(self.root, 'screenshots/case.json', case)
            write(self.root, 'screenshots/manifest.json', dict(schemaVersion=1, api=35, tests=[case]))
            with self.assertRaises(ValueError): self.validate(report)

    def test_case_cannot_hide_wrong_method_failure_or_extra_image(self):
        report = bundle(self.root)
        case = json.loads((self.root / 'screenshots/case.json').read_text())
        case['testName'] = 'collectBusyStopDiagnostics'
        write(self.root, 'screenshots/case.json', case)
        write(self.root, 'screenshots/manifest.json', dict(schemaVersion=1, api=35, tests=[case]))
        with self.assertRaisesRegex(ValueError, 'walkthrough screenshot case'): self.validate(report)
        report = bundle(self.root)
        (self.root / 'screenshots/extra.png').write_bytes((self.root / 'screenshots/synthetic.png').read_bytes())
        with self.assertRaisesRegex(ValueError, 'inventory'): self.validate(report)

    def test_capture_validator_rejects_unrelated_or_nested_payloads_itself(self):
        bundle(self.root)
        files = scanned(self.root)
        cap = json.loads((self.root / 'capture.json').read_text())
        for name in ('screenshots/foo.txt', 'screenshots/nested/case.json', 'screenshots/../case.json'):
            value = copy.deepcopy(cap); value['files'].append(name)
            with self.assertRaisesRegex(ValueError, 'inventory'):
                video.validate_capture(value, dict(files, **{name: dict(data=b'other', bytes=5)}))

    def test_reference_hash_and_declared_scope_cannot_be_rewritten(self):
        report = bundle(self.root)
        report['flow']['sha256'] = 'b' * 64
        with self.assertRaisesRegex(ValueError, 'identity mismatch'): self.validate(report)
        for change in [dict(expectedTests=1600), dict(performanceSample=True), dict(status='failed'),
                       dict(secureFlagsPreserved=False), dict(source=dict(SOURCE, attempt=True))]:
            report = bundle(self.root); report.update(change)
            with self.assertRaises(ValueError): self.validate(report)

    def test_unexecuted_attempt_is_bounded_and_cannot_be_replayed(self):
        video.prepare(self.root, SOURCE)
        video.finalize(self.root, SOURCE)
        report = json.loads((self.root / 'report.json').read_text())
        self.assertEqual('unverified', self.validate(report)['status'])
        self.assertEqual('environment', report['failureStage'])
        with self.assertRaises(ValueError): video.prepare(self.root, SOURCE)
        with self.assertRaises(ValueError): video.record(self.root, self.root / 'video', SOURCE)

    def test_recording_failure_does_not_replace_or_repeat_original_instrumentation(self):
        video.prepare(self.root, SOURCE)
        calls = []
        def command(args, **kwargs):
            calls.append(args)
            if args == ['adb', 'devices']: return 'List of devices attached\nemulator-5554\tdevice\n'
            if 'getprop' in args:
                return {'ro.kernel.qemu':'1','ro.boot.qemu':'1','ro.build.version.sdk':'35',
                        'ro.product.cpu.abi':'x86_64','sys.boot_completed':'1'}[args[-1]]
            if 'cat' in args: return json.dumps(flow()).encode()
            return ''
        def recording(folder, source, profile, api, adb, serial, callback):
            self.assertEqual((video.PROFILE, 35), (profile, api))
            # An independent failed recorder still returns the original callback.
            return callback()
        with mock.patch.object(video.bounded, 'run_command', side_effect=command), \
                mock.patch.object(video.probe_video, 'record_call', side_effect=recording) as recorder, \
                mock.patch('run_bounded_instrumentation.bounded_capture', return_value=(
                    instrument()['stdout'].encode(), dict(status='completed', exitCode=0, bytes=100))) as invocation, \
                mock.patch.object(video.captures, 'collect', side_effect=lambda *args: screenshots(self.root)):
            self.assertTrue(video.record(self.root, self.root.parent / 'video', SOURCE))
        recorder.assert_called_once(); invocation.assert_called_once()
        command_args, seconds, maximum = invocation.call_args.args
        self.assertEqual((105, 65536), (seconds, maximum))
        self.assertIn(video.SELECTION, command_args)
        self.assertNotIn('collectBusyStopDiagnostics', ' '.join(command_args))
        self.assertEqual('passed', self.validate(json.loads((self.root / 'report.json').read_text()))['status'])


class BusyStopVideoArchive(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.incoming = self.root / 'incoming'; self.incoming.mkdir()
        self.run = existing.source_run(); self.run['path'] = video.WORKFLOW
        self.jobs = dict(jobs=[dict(existing.job(), name=video.JOB)])
        items = existing.artifacts(video.PROFILE)
        items[0]['name'] = 'busy-stop-walkthrough-api-35-attempt-2'
        self.index = dict(artifacts=items)
        self.enterContext(mock.patch.object(busy, 'validate_single_device_trial'))
        self.enterContext(mock.patch.object(archive.demo_video.bounded, 'run_command', return_value=''))
        self.enterContext(mock.patch.object(archive.demo_video, 'video_metadata', side_effect=lambda path, encoded:
            json.loads((Path(path).parent / 'recording.json').read_text())['video']))

    def prepare(self, failed=False):
        probe = self.incoming / self.index['artifacts'][0]['name']; probe.mkdir()
        bundle(probe)
        folder = self.incoming / self.index['artifacts'][1]['name']; folder.mkdir()
        write(folder, 'recording.json', existing.recording(video.PROFILE, failed))
        (folder / 'demo.mp4').write_bytes(existing.VIDEO)
        return probe, folder

    def execute(self):
        return archive.archive(self.run, self.jobs, self.index, self.incoming, self.root)

    def test_exact_busy_profile_keeps_one_case_and_original_bytes(self):
        probe, folder = self.prepare()
        result = self.execute()
        self.assertEqual(('passed', 1, 1), (result['status'], result['expectedTests'], result['matrixExpectedTests']))
        self.assertEqual(['35'], result['expectedMatrixLabels'])
        self.assertFalse(result['performanceSample'])
        self.assertFalse(result['probe']['performanceSample'])
        dest = self.root / 'screenshots/demos/probes-123-2'
        self.assertEqual(existing.VIDEO, (dest / 'video/demo.mp4').read_bytes())
        self.assertEqual((probe / 'flow-result.json').read_bytes(), (dest / 'probe/flow-result.json').read_bytes())
        with self.assertRaisesRegex(ValueError, 'already exists'): self.execute()

    def test_failed_recording_keeps_successful_original_case(self):
        self.prepare(failed=True)
        result = self.execute()
        self.assertEqual(('failed', 'passed', 'failed'),
                         (result['status'], result['probeStatus'], result['recordingStatus']))
        self.assertEqual(dict(passed=1, failed=0, unverified=0), result['caseCounts'])

    def test_missing_artifacts_keep_one_unverified_case_without_borrowing_formal_denominator(self):
        self.index['artifacts'] = []
        result = self.execute()
        self.assertEqual(dict(passed=0, failed=0, unverified=1), result['caseCounts'])
        self.assertIsNone(result['videoPath'])

    def test_foreign_job_attempt_or_video_profile_are_rejected(self):
        for mutation in ('job', 'attempt', 'profile'):
            jobs, index = copy.deepcopy(self.jobs), copy.deepcopy(self.index)
            if mutation == 'job': jobs['jobs'][0]['name'] = 'busy-stop (35)'
            if mutation == 'attempt': jobs['jobs'][0]['run_attempt'] = 1
            if mutation == 'profile': index['artifacts'][1]['name'] = 'probe-video-pinned-calculator-page-api-35-attempt-2'
            with self.assertRaises(ValueError): archive.select(self.run, jobs, index)


class BusyStopVideoStrictTrialIntegration(unittest.TestCase):
    """No validator mocks: the independent wrapper reuses ordinary DEVICE semantics."""
    def setUp(self):
        from test_busy_stop_evidence import valid_trial
        self.trial = valid_trial(index=0, api=35, profile='pilot')

    def test_one_valid_device_trial_has_no_pilot_root_or_missing_three_samples(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            report = bundle(folder, trial=self.trial)
            result = video.validate_probe(report, scanned(folder), SOURCE)
            self.assertEqual([dict(name=video.METHOD, status='passed')], result['cases'])
            self.assertFalse(result['performanceSample'])
            self.assertNotIn('profile', self.trial)
            self.assertEqual(['35'], report['expectedMatrixLabels'])
            with self.assertRaises(ValueError): busy.validate_report(flow(self.trial), 35)

    def test_wrapper_cannot_greenwash_wrong_owner_gate_effect_or_trial_identity(self):
        mutations = [lambda t: t['afterStop'].update(inFlight=False),
                     lambda t: t['fixture']['afterSettle'].update(expired=True),
                     lambda t: t['fixture']['afterSettle'].update(effectCount=2),
                     lambda t: t['gateProbe'].update(dispatchAccepted=True),
                     lambda t: t['oldEpochProbe'].update(code='OK'),
                     lambda t: t['cleanup'].update(settingsRestored=False),
                     lambda t: t.update(index=1), lambda t: t.update(scenario='MODEL')]
        for mutate in mutations:
            trial = copy.deepcopy(self.trial); mutate(trial)
            with self.assertRaises(ValueError): video.validate_flow(flow(trial), SOURCE)

    def test_failed_original_retains_the_busy_invariant_violation(self):
        trial = copy.deepcopy(self.trial)
        trial.update(status='failed', phase='blocked_gate', failure='AssertionError')
        trial['afterStop']['inFlight'] = False
        value = flow(trial)
        value.update(status='failed', failure=dict(stage='blocked_gate', errorClass='AssertionError'))
        self.assertIs(value, video.validate_flow(value, SOURCE))
        self.assertFalse(value['trial']['afterStop']['inFlight'])

    def test_before_trial_failure_and_interrupted_initial_flow_remain_explicit(self):
        value = flow()
        value.update(status='incomplete', endedNanoTime=None, trial=None)
        self.assertIs(value, video.validate_flow(value, SOURCE))
        value.update(status='failed', endedNanoTime=400,
                     failure=dict(stage='setup', errorClass='AssertionError'))
        self.assertIs(value, video.validate_flow(value, SOURCE))
        value.update(status='incomplete', endedNanoTime=-1, failure=None)
        with self.assertRaises(ValueError): video.validate_flow(value, SOURCE)


if __name__ == '__main__':
    unittest.main()
