import base64
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import window_infos_capability as w

SOURCE = {'repository': w.REPOSITORY, 'commit': 'a' * 40, 'runId': 123, 'attempt': 1}
IDENTITIES = {name: {'bytes': 1, 'sha256': 'b' * 64} for name in w.SOURCE_PATHS}
SERIAL = 'emulator-5554'


def capability(source=SOURCE):
    entries = []
    for index, (identifier, kind, _owner, expected_type) in enumerate(w.CATALOG):
        entry = {'id': identifier, 'status': 'FOUND_MATCHED', 'attempted': True, 'public': True,
                 'static': kind == 'CLASS' and index != 0, 'final': kind == 'FIELD',
                 'observedType': expected_type, 'typeMatches': True, 'declaringClassMatches': True}
        if kind == 'GAP':
            entry.update(status='NOT_FOUND_OR_FILTERED', **{k: None for k in ['public', 'static', 'final', 'observedType', 'typeMatches', 'declaringClassMatches']})
        entries.append(entry)
    return {'schemaVersion': 1, 'kind': 'window-infos-public-test-api-capability', 'catalogVersion': 1,
            'source': {'sourceSha': source['commit'], 'runId': str(source['runId']), 'runAttempt': str(source['attempt']), 'expectedApi': '37', 'deviceSerial': SERIAL},
            'executionContext': 'DEFAULT_INSTRUMENTATION_TEST_PROCESS', 'reflectionPolicy': 'PUBLIC_WHITELIST_ONLY_NO_ADDITIONAL_EXEMPTION_REQUEST',
            'sdkInt': 37, 'build': {'buildId': 'TEST.1', 'valueRelease': '17', 'fingerprintSha256': w.sha(b'synthetic/fingerprint'),
                 'buildIdStatus': 'AVAILABLE', 'releaseStatus': 'AVAILABLE', 'fingerprintStatus': 'AVAILABLE'},
            'process': {'uid': 10123, 'targetSdk': 35, 'targetPackage': w.PACKAGE, 'testPackage': w.PACKAGE + '.test'},
            'permissions': {'accessSurfaceFlinger': 'DENIED'}, 'clock': 'SystemClock.elapsedRealtime',
            'startedElapsedMillis': 1000, 'endedElapsedMillis': 1010, 'betweenOperationDeadlineMillis': 5000,
            'deadlineExceeded': False, 'probeExecution': 'COMPLETE', 'lookupLimit': 27, 'lookupAttempts': 27,
            'lookups': entries, 'constructor': {'status': 'CONSTRUCTED', 'attempts': 1, 'completed': 1},
            'capability': {'requiredSurface': 'MATCHED', 'listenerRegistration': 'NOT_ATTEMPTED', 'windowData': 'NOT_READ'},
            'diagnosticTruncated': False}


def fake_execute(source, instrument=None, mutate=None):
    properties = dict(zip(w.PROPERTIES, ['1', '1', '37', 'x86_64', '1', 'synthetic/fingerprint']))
    def execute(argv, seconds, limit):
        if argv[-1] == 'devices': output = b'List of devices attached\nemulator-5554\tdevice\n'
        elif 'getprop' in argv: output = (properties[argv[-1]] + '\n').encode()
        elif argv[-1] == 'get-current-user': output = b'0\n'
        elif argv[-3:] == ['pm', 'list', 'packages']: output = b'package:android\n'
        elif 'badging' in argv:
            package = w.PACKAGE + ('.test' if Path(argv[-1]).name == 'tests.apk' else '')
            version = "versionCode='' versionName=''" if package.endswith('.test') else "versionCode='1' versionName='1.0'"
            output = ("package: name='" + package + "' " + version + "\nsdkVersion:'30'\ntargetSdkVersion:'35'\n").encode()
        elif 'install' in argv: output = b'Performing Streamed Install\nSuccess\n'
        elif 'path' in argv: output = ('package:/data/app/fixture/' + argv[-1] + '/base.apk\n').encode()
        elif 'instrumentation' in argv: output = ('instrumentation:' + w.COMPONENT + ' (target=' + w.PACKAGE + ')\n').encode()
        elif 'instrument' in argv: output = instrumentation(capability(source)) if instrument is None else instrument
        else: raise AssertionError(argv)
        if mutate is not None: output = mutate(argv, output)
        return output, {'status': 'completed', 'exitCode': 0, 'bytes': len(output)}
    return execute


def recorded_payloads(source=SOURCE, identities=IDENTITIES, captured_at='2026-10-31T09:00:00+00:00', instrument=None, mutate=None):
    """Synthetic full driver receipt for host/archive tests, never device evidence."""
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary); sdk = root / 'sdk'; (sdk / 'emulator').mkdir(parents=True)
        (sdk / 'emulator/source.properties').write_text('Pkg.Revision = 37.2.11\n')
        for name in ['probe.apk', 'tests.apk']: (root / name).write_bytes(b'synthetic-' + name.encode())
        output = root / 'evidence'
        with patch.object(w, 'source_files', return_value=identities), patch.object(w, 'now', return_value=captured_at):
            w.prepare(output, source)
            args = SimpleNamespace(output=output, sdk_root=sdk, probe_apk=root / 'probe.apk', test_apk=root / 'tests.apk')
            w.run_probe(args, source, lambda directory, bound: w.CommandRunner(directory, bound, fake_execute(bound, instrument, mutate)))
            w.finalize(output, source, 'success')
            return w.read_payloads(output)


def status_block(code, fields):
    return ''.join('INSTRUMENTATION_STATUS: ' + k + '=' + str(v) + '\n' for k, v in fields.items()) + 'INSTRUMENTATION_STATUS_CODE: ' + str(code) + '\n'


def instrumentation(value):
    common = {'class': w.TEST_CLASS, 'test': w.TEST_METHOD}
    return (status_block(1, dict(common, current=1, numtests=1)) + status_block(2, dict(common,
            windowInfosCapabilityDiagnostic=w.MARKER + json.dumps(value, separators=(',', ':')))) +
            status_block(0, dict(common, current=1, numtests=1)) + 'INSTRUMENTATION_CODE: -1\n').encode()


class SourceAndBindingTest(unittest.TestCase):
    def setUp(self):
        self.env = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux',
                    'GITHUB_REPOSITORY': w.REPOSITORY, 'GITHUB_REF': 'refs/heads/master',
                    'GITHUB_EVENT_NAME': 'workflow_dispatch',
                    'GITHUB_WORKFLOW_REF': f'{w.REPOSITORY}/.github/workflows/{w.WORKFLOW}@refs/heads/master',
                    'GITHUB_SHA': SOURCE['commit'], 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}

    def test_exact_ci_identity_and_fixture_env_not_ambient(self):
        self.assertEqual(SOURCE, w.source_from_environment(self.env))
        for key, value in [('GITHUB_ACTIONS', 'false'), ('RUNNER_ENVIRONMENT', 'self-hosted'),
                           ('GITHUB_REF', 'refs/pull/1/merge'), ('GITHUB_EVENT_NAME', 'push'),
                           ('GITHUB_WORKFLOW_REF', 'wrong'), ('GITHUB_RUN_ID', '0'),
                           ('GITHUB_RUN_ATTEMPT', '01'), ('GITHUB_SHA', 'a' * 39), ('RUNNER_OS', 'Windows')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                w.source_from_environment(dict(self.env, **{key: value}))

    @patch.object(w, 'validate_capability', side_effect=lambda value, source, serial: value)
    def test_exact_single_test_block_and_marker(self, _):
        value = {'probeExecution': 'COMPLETE'}
        self.assertEqual(value, w.parse_instrumentation(instrumentation(value), SOURCE, SERIAL))

    @patch.object(w, 'validate_capability', side_effect=lambda value, source, serial: value)
    def test_no_previous_case_binding_summary_or_ok_fallback(self, _):
        output = instrumentation({'probeExecution': 'COMPLETE'})
        variants = [b'OK (1 test)\n', output.replace(b'INSTRUMENTATION_STATUS_CODE: 2', b'INSTRUMENTATION_STATUS_CODE: 77'),
                    output.replace(b'INSTRUMENTATION_STATUS: class=' + w.TEST_CLASS.encode() + b'\n', b'', 1),
                    output.replace(w.TEST_METHOD.encode(), b'otherMethod', 1),
                    output.replace(b'INSTRUMENTATION_STATUS: numtests=1', b'INSTRUMENTATION_STATUS: numtests=2'),
                    output.replace(b'INSTRUMENTATION_STATUS_CODE: 0', b'INSTRUMENTATION_STATUS_CODE: -2'),
                    output.replace(b'INSTRUMENTATION_CODE: -1', b'INSTRUMENTATION_CODE: 0'),
                    b'INSTRUMENTATION_CODE: -1\n' + output.replace(b'INSTRUMENTATION_CODE: -1\n', b''),
                    output + b'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n',
                    output + b'INSTRUMENTATION_STATUS_CODE: 77\n',
                    output + (w.MARKER + '{}\n').encode()]
        for data in variants:
            with self.subTest(data=data), self.assertRaises(ValueError): w.parse_instrumentation(data, SOURCE, SERIAL)

    @patch.object(w, 'validate_capability', side_effect=lambda value, source, serial: value)
    def test_missing_conflicting_duplicate_and_malformed_marker_are_rejected(self, _):
        good = instrumentation({'probeExecution': 'COMPLETE'})
        marker = w.MARKER.encode()
        for raw in [b'{', b'{"x":1,"x":2}', b'{"x":NaN}', b'[]trailing', b'{"x":"' + b'x' * w.MAX_MARKER + b'"}']:
            start = good.index(marker) + len(marker); end = good.index(b'\n', start)
            data = good[:start] + raw + good[end:]
            with self.subTest(raw=raw[:40]), self.assertRaises(ValueError): w.parse_instrumentation(data, SOURCE, SERIAL)
        block = status_block(2, {'windowInfosCapabilityDiagnostic': w.MARKER + '{}'})
        data = status_block(1, {'class': w.TEST_CLASS, 'test': w.TEST_METHOD, 'current': 1, 'numtests': 1}) + block
        with self.assertRaises(ValueError): w.parse_instrumentation(data.encode(), SOURCE, SERIAL)

    @patch.object(w, 'validate_capability', side_effect=lambda value, source, serial: value)
    def test_incomplete_execution_never_recorded_even_with_success_terminal(self, _):
        with self.assertRaises(ValueError):
            w.parse_instrumentation(instrumentation({'probeExecution': 'INCOMPLETE_DEADLINE'}), SOURCE, SERIAL)

    def test_exact_no_extra_policy_or_ui_instrumentation_flags(self):
        argv = w.instrument_command('/sdk/adb', SERIAL, SOURCE)
        self.assertEqual(['/sdk/adb', '-s', SERIAL, 'shell', 'am', 'instrument', '-w', '-r'], argv[:8])
        self.assertIn(w.TEST_CLASS + '#' + w.TEST_METHOD, argv)
        self.assertNotIn('--no-hidden-api-checks', argv)
        self.assertNotIn('--no-test-api-access', argv)
        self.assertEqual(w.COMPONENT, argv[-1])


class RealBoundedCommandTest(unittest.TestCase):
    def test_actual_subprocess_timeout_prefix_limit_and_launch_exception(self):
        with tempfile.TemporaryDirectory() as temporary:
            runner = w.CommandRunner(temporary, SOURCE)
            start = time.monotonic()
            data, receipt = runner.call('synthetic_timeout', [sys.executable, '-c', 'import time; print("prefix", flush=True); time.sleep(60)'], .1, 100)
            self.assertEqual('timed_out', receipt['status'])
            self.assertIn(b'prefix', data)
            self.assertLess(time.monotonic() - start, 2)
            data, receipt = runner.call('synthetic_size', [sys.executable, '-c', 'import sys;sys.stdout.write("x"*1000000)'], 1, 100)
            self.assertEqual('output_limit', receipt['status']); self.assertEqual(100, len(data))
            self.assertEqual(w.sha(data), receipt['sha256'])
            data, receipt = runner.call('synthetic_missing', ['/nonexistent-window-infos-probe-test'], 1, 100)
            self.assertEqual('exception', receipt['status']); self.assertEqual('FileNotFoundError', receipt['exceptionType'])
            stored = w.decode_json((Path(temporary) / 'commands.json').read_bytes())
            self.assertEqual(3, len(stored['commands']))
            self.assertEqual(data, base64.b64decode(stored['commands'][-1]['outputBase64']))

    def test_actual_nonzero_exit_and_output_retained(self):
        with tempfile.TemporaryDirectory() as temporary:
            runner = w.CommandRunner(temporary, SOURCE)
            data, receipt = runner.call('synthetic_nonzero', [sys.executable, '-c', 'print("failure");raise SystemExit(7)'], 1, 100)
            self.assertEqual('completed', receipt['status']); self.assertEqual(7, receipt['exitCode'])
            self.assertEqual(b'failure\n', data)

    def test_read_exception_retains_prefix_and_kills_reaps_actual_child(self):
        reads = 0
        def read(fd, count):
            nonlocal reads
            reads += 1
            if reads > 1: raise OSError('synthetic read failure')
            return os.read(fd, count)
        command = [sys.executable, '-c', 'import os,time; print(os.getpid(),flush=True); time.sleep(.04); print("second",flush=True); time.sleep(60)']
        start = time.monotonic(); data, outcome = w.bounded_capture(command, 1, 1024, reader=read)
        self.assertEqual('exception', outcome['status']); self.assertEqual('OSError', outcome['exceptionType'])
        pid = int(data.strip())
        with self.assertRaises(ProcessLookupError): os.kill(pid, 0)
        self.assertLess(time.monotonic() - start, 1)

    def test_cancellation_does_not_launch_another_process(self):
        with patch.object(w, 'CANCELLED', True), patch.object(w.subprocess, 'Popen') as launch:
            data, outcome = w.bounded_capture(['/not-launched'], 1, 100)
            self.assertEqual('cancelled', outcome['status']); self.assertEqual(b'', data)
            launch.assert_not_called()

    def test_cancellation_after_stdout_eof_interrupts_wait_and_reaps_child(self):
        def cancel(): w.CANCELLED = True
        timer = threading.Timer(.08, cancel)
        command = [sys.executable, '-c', 'import os,time; print(os.getpid(),flush=True); os.close(1); os.close(2); time.sleep(60)']
        try:
            timer.start(); start = time.monotonic()
            data, outcome = w.bounded_capture(command, 2, 1024)
            self.assertEqual('cancelled', outcome['status'])
            with self.assertRaises(ProcessLookupError): os.kill(int(data.strip()), 0)
            self.assertLess(time.monotonic() - start, 1)
        finally:
            timer.cancel(); timer.join(); w.CANCELLED = False


class EvidenceBoundaryTest(unittest.TestCase):
    def test_prepare_finalize_missing_execution_stays_unverified_nonvisual(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(w, 'source_files', return_value=IDENTITIES):
            w.prepare(temporary, SOURCE)
            result = w.finalize(temporary, SOURCE, 'failure')
            self.assertEqual('unverified', result['status']); self.assertEqual(0, result['recordedTests'])
            payloads = w.read_payloads(temporary)
            self.assertEqual('nonvisual', payloads['capture.json']['captureStatus'])
            self.assertNotIn('instrumentation.json', payloads)
            self.assertEqual([], payloads['capture.json']['files'])

    def test_source_rebinding_nonvisual_forgery_and_duplicate_json_rejected(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(w, 'source_files', return_value=IDENTITIES):
            w.prepare(temporary, SOURCE); data = w.read_payloads(temporary)
            for mutate in [lambda d: d['report.json']['source'].update(attempt=2),
                           lambda d: d['capture.json'].update(captureStatus='captured'),
                           lambda d: d['report.json'].update(status='recorded'),
                           lambda d: d['report.json']['scope'].update(listenerRegistration=True),
                           lambda d: d['report.json']['scope'].update(nonvisual=1)]:
                changed = copy.deepcopy(data); mutate(changed)
                with self.assertRaises(ValueError): w.validate_evidence(changed, SOURCE)
            with self.assertRaises(ValueError): w.decode_json('{"a":1,"a":1}')

    def test_existing_output_extra_files_and_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(w, 'source_files', return_value=IDENTITIES):
            w.prepare(temporary, SOURCE)
            with self.assertRaises(ValueError): w.prepare(temporary, SOURCE)
            (Path(temporary) / 'screen.png').write_bytes(b'fake')
            with self.assertRaises(ValueError): w.read_payloads(temporary)

    def test_source_files_must_match_exact_checkout(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in w.SOURCE_PATHS:
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(b'fixture')
            report = {'sourceFiles': w.source_files(root)}
            self.assertTrue(w.verify_source_files(report, root))
            (root / w.SOURCE_PATHS[0]).write_bytes(b'changed')
            with self.assertRaises(ValueError): w.verify_source_files(report, root)

    def test_bounded_file_read_rejects_oversize_before_loading(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'large.json'
            with path.open('wb') as stream: stream.truncate(w.MAX_JSON + 1)
            with self.assertRaises(ValueError): w.read_bounded(path, w.MAX_JSON)


class CapabilitySchemaTest(unittest.TestCase):
    def test_fixed27_catalog_constructor_and_no_registration_are_recorded_only(self):
        value = capability()
        self.assertEqual(value, w.validate_capability(value, SOURCE, SERIAL))
        self.assertEqual(value, w.parse_instrumentation(instrumentation(value), SOURCE, SERIAL))
        self.assertEqual('MATCHED', value['capability']['requiredSurface'])
        self.assertEqual('DENIED', value['permissions']['accessSurfaceFlinger'])

    def test_source_types_catalog_duplicates_unknown_members_and_overbudget_rejected(self):
        mutations = [lambda x: x['source'].update(runId='124'), lambda x: x['source'].update(expectedApi='36'),
                     lambda x: x.update(schemaVersion=True), lambda x: x.update(lookupLimit=28),
                     lambda x: x.update(lookupAttempts=26), lambda x: x['lookups'].append(x['lookups'][-1]),
                     lambda x: x['lookups'][4].update(id='method.invoke.anything'),
                     lambda x: x['constructor'].update(attempts=2), lambda x: x['constructor'].update(completed=True),
                     lambda x: x['capability'].update(listenerRegistration='REGISTERED'),
                     lambda x: x['process'].update(targetPackage='dev.agentos.app'),
                     lambda x: x['lookups'][8].update(final=False), lambda x: x['lookups'][8].update(observedType='OTHER'),
                     lambda x: x['lookups'][9].update(status='FOUND_MISMATCH')]
        for mutate in mutations:
            value = capability(); mutate(value)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError): w.validate_capability(value, SOURCE, SERIAL)

    def test_missing_class_dependency_and_constructor_unavailable_remain_observed(self):
        value = capability(); entry = value['lookups'][0]
        entry.update(status='NOT_FOUND_OR_FILTERED', **{k: None for k in ['public', 'static', 'final', 'observedType', 'typeMatches', 'declaringClassMatches']})
        for index in range(3, 8):
            value['lookups'][index].update(status='SKIPPED_CLASS_UNAVAILABLE', attempted=False,
                 **{k: None for k in ['public', 'static', 'final', 'observedType', 'typeMatches', 'declaringClassMatches']})
        value['lookupAttempts'] = 22; value['constructor'] = {'status': 'NOT_ATTEMPTED_LOOKUP_UNAVAILABLE', 'attempts': 0, 'completed': 0}
        value['capability']['requiredSurface'] = 'NOT_FULLY_MATCHED'
        self.assertEqual(value, w.validate_capability(value, SOURCE, SERIAL))
        value['constructor'] = {'status': 'CONSTRUCTED', 'attempts': 1, 'completed': 1}
        with self.assertRaises(ValueError): w.validate_capability(value, SOURCE, SERIAL)

    def test_source_unlisted_gap_has_no_expected_type_or_capability_upgrade(self):
        value = capability(); entry = value['lookups'][22]
        entry.update(status='FOUND_SOURCE_UNLISTED', public=True, static=False, final=False,
                     observedType='OTHER', typeMatches=None, declaringClassMatches=True)
        self.assertEqual(value, w.validate_capability(value, SOURCE, SERIAL))
        for field, changed in [('typeMatches', True), ('status', 'FOUND_MATCHED'), ('public', False),
                               ('observedType', 'CONSTRUCTOR'), ('observedType', 'VOID')]:
            other = copy.deepcopy(value); other['lookups'][22][field] = changed
            with self.assertRaises(ValueError): w.validate_capability(other, SOURCE, SERIAL)

    def test_deadline_suffix_and_partial_metadata_never_recorded(self):
        value = capability(); value.update(endedElapsedMillis=6000, deadlineExceeded=True, probeExecution='INCOMPLETE_DEADLINE', lookupAttempts=0)
        for entry in value['lookups']:
            entry.update(status='SKIPPED_DEADLINE', attempted=False,
                         **{k: None for k in ['public', 'static', 'final', 'observedType', 'typeMatches', 'declaringClassMatches']})
        value['constructor'] = {'status': 'SKIPPED_DEADLINE', 'attempts': 0, 'completed': 0}
        value['capability']['requiredSurface'] = 'UNVERIFIED_DEADLINE'
        self.assertEqual(value, w.validate_capability(value, SOURCE, SERIAL))
        with self.assertRaises(ValueError): w.parse_instrumentation(instrumentation(value), SOURCE, SERIAL)
        value['lookups'][-1]['observedType'] = 'INT'
        with self.assertRaises(ValueError): w.validate_capability(value, SOURCE, SERIAL)


class CompleteDriverTest(unittest.TestCase):
    def test_internal_test_apk_empty_version_is_valid_without_relaxing_package_or_sdk(self):
        actual_shape = "package: name='dev.agentos.compat.test' versionCode='' versionName=''\nsdkVersion:'30'\ntargetSdkVersion:'35'\n"
        self.assertEqual({'package': w.PACKAGE + '.test', 'minSdk': 30, 'targetSdk': 35}, w.parse_internal_badging(actual_shape))
        for bad in [actual_shape + "sdkVersion:'30'\n", actual_shape.replace("sdkVersion:'30'", "sdkVersion:''"),
                    actual_shape + "package: name='other' versionCode='1'\n"]:
            with self.assertRaises(ValueError): w.parse_internal_badging(bad)

    def test_synthetic_full_driver_all17_commands_metadata_and_archive_revalidation(self):
        data = recorded_payloads()
        summary = w.validate_evidence(data, SOURCE)
        self.assertEqual('recorded', summary['status']); self.assertEqual(1, summary['recordedTests'])
        self.assertEqual(list(w.COMMAND_IDS), [c['id'] for c in data['commands.json']['commands']])
        self.assertEqual(w.FILES, set(data))

    def test_failed_terminal_missing_marker_and_corrupt_output_preserve_failure(self):
        good = instrumentation(capability())
        for body in [good.replace(b'INSTRUMENTATION_STATUS_CODE: 0', b'INSTRUMENTATION_STATUS_CODE: -2'),
                     b'OK (1 test)\n', good + b'\xff']:
            data = recorded_payloads(instrument=body)
            self.assertEqual('failed', w.validate_evidence(data, SOURCE)['status'])
            self.assertEqual(body, base64.b64decode(data['commands.json']['commands'][-1]['outputBase64']))

    def test_deeply_nested_raw_marker_is_preserved_failed_without_archive_crash(self):
        original = instrumentation(capability()); prefix = w.MARKER.encode(); start = original.index(prefix) + len(prefix)
        end = original.index(b'\n', start); malformed = b'[' * 2000 + b'0' + b']' * 2000
        body = original[:start] + malformed + original[end:]
        data = recorded_payloads(instrument=body)
        self.assertEqual('failed', w.validate_evidence(data, SOURCE)['status'])
        self.assertEqual(body.decode(), data['instrumentation.json']['output'])

    def test_rehashed_false_setup_and_report_mismatch_cannot_be_recorded(self):
        data = recorded_payloads()
        for name, replacement in [('property_ro.build.version.sdk', b'36\n'), ('current_user', b'10\n'),
                                  ('installed_packages', b'package:org.fossify.notes\n'), ('install_probe', b'Failure\n'),
                                  ('installed_paths_tests', b'package:/data/app/other/base.apk\n'),
                                  ('instrumentation_registration', b'wrong\n')]:
            changed = copy.deepcopy(data); row = next(c for c in changed['commands.json']['commands'] if c['id'] == name)
            row.update(bytes=len(replacement), sha256=w.sha(replacement), outputBase64=base64.b64encode(replacement).decode())
            with self.subTest(name=name), self.assertRaises(ValueError): w.validate_evidence(changed, SOURCE)
        changed = copy.deepcopy(data); changed['report.json']['device']['emulatorVersion'] = '99.0.0'
        with self.assertRaises(ValueError): w.validate_evidence(changed, SOURCE)
        changed = copy.deepcopy(data); next(c for c in changed['commands.json']['commands'] if c['id'] == 'install_probe')['argv'][-1] = '/wrong.apk'
        with self.assertRaises(ValueError): w.validate_evidence(changed, SOURCE)

    def test_environment_failure_never_runs_instrumentation(self):
        def mutate(argv, output): return b'36\n' if argv[-1] == 'ro.build.version.sdk' else output
        data = recorded_payloads(mutate=mutate)
        self.assertEqual('unverified', w.validate_evidence(data, SOURCE)['status'])
        self.assertFalse(data['report.json']['instrumentationAttempted'])
        self.assertNotIn('instrumentation.json', data)

    def test_budget_overrun_remains_archivable_failed_evidence(self):
        data = recorded_payloads(); data['commands.json']['commands'][-1]['elapsedMillis'] = 90000
        with self.assertRaises(ValueError): w.validate_evidence(data, SOURCE)
        data['report.json'].update(status='failed', capability=None, failureReason='INSTRUMENTATION_BUDGET_EXCEEDED')
        self.assertEqual('failed', w.validate_evidence(data, SOURCE)['status'])


if __name__ == '__main__': unittest.main()
