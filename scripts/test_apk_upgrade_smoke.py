"""Synthetic host-boundary fixtures; no APK install or device acceptance evidence."""
import copy
import base64
import hashlib
import http.client
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import apk_upgrade_smoke as smoke


SOURCE = {'repository': 'ecojust/Agent-OS', 'runId': 123, 'attempt': 1, 'commit': 'a' * 40}
PIN = 'c' * 64
NONCE_SHA = hashlib.sha256(b'synthetic-host-parser-fixture').hexdigest()


def environment():
    return {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted',
            'GITHUB_REPOSITORY': SOURCE['repository'], 'GITHUB_REF': 'refs/heads/master',
            'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_SHA': SOURCE['commit'],
            'GITHUB_WORKFLOW': 'Development APK in-place upgrade',
            'GITHUB_WORKFLOW_REF': SOURCE['repository'] + '/.github/workflows/apk-upgrade-smoke.yml@refs/heads/master',
            'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'}


def build_manifest():
    files = {name: {'bytes': 128, 'sha256': str(index + 1) * 64}
             for index, name in enumerate(('baseline.apk', 'candidate.apk', 'fixture.apk', 'probe.apk', 'preflight.json'))}
    files['signing-certificate.der'] = {'bytes': 512, 'sha256': PIN}
    return {'schemaVersion': 1, 'kind': 'agentos-apk-upgrade-build', 'source': copy.deepcopy(SOURCE),
            'baselineSource': smoke.BASELINE_SOURCE, 'signingPolicy': 'temporary-development-certificate',
            'certificateSha256': PIN, 'files': files}


def preflight_result():
    manifest = build_manifest()
    result = {'schemaVersion': 1, 'kind': 'agentos-offline-apk-upgrade-preflight', 'status': 'passed',
              'signingPolicy': 'same-current-certificate-pinned', 'lineageEvaluated': False,
              'installationPerformed': False, 'trustedCertificateSha256': PIN}
    for role, version in (('baseline', 1), ('candidate', 2)):
        result[role] = {**manifest['files'][role + '.apk'], 'package': 'dev.agentos.app',
                        'versionCode': version, 'minSdk': 30, 'targetSdk': 35,
                        'currentSignerCount': 1, 'currentSignerSha256': PIN}
    return result


def receipt(phase='seedBaseline'):
    value = {'schemaVersion': 1, 'phase': phase, 'runId': '123', 'runAttempt': '1',
             'baselineSource': smoke.BASELINE_SOURCE, 'candidateSource': SOURCE['commit'], 'api': 35,
             'nonceSha256': NONCE_SHA, 'targetVersionCode': 1 if phase == 'seedBaseline' else 2,
             'pid': 401 if phase == 'seedBaseline' else 402, 'targetUid': 10042,
             'firstInstallTime': 1700000000000, 'envelopeSha256': 'd' * 64,
             'revisionSha256': 'e' * 64, 'grantSha256': 'f' * 64, 'profileSha256': '9' * 64,
             'settingsVerified': True, 'fixtureGrantValid': True, 'secureWindowVerified': True,
             'captureStatus': 'blocked', 'captureReason': 'secure_apk_upgrade_capture_not_attempted'}
    if phase == 'seedBaseline':
        value.update(approvalObserved=True, approvalGranted=False, taskStopped=True,
                     stoppedUsageRetained=True, dispatchAttempts=0, workerDrained=True)
    else:
        value.update(freshController=True, noTaskOrApproval=True, noTaskHistory=True, noTaskInput=True,
                     workerDrained=True)
    return value


def instrumentation(method='seedBaseline', observed=None):
    observed = receipt(method) if observed is None else observed
    return (f'INSTRUMENTATION_STATUS: class={smoke.TEST_CLASS}\n'
            f'INSTRUMENTATION_STATUS: test={method}\n'
            'INSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: numtests=1\n'
            'INSTRUMENTATION_STATUS_CODE: 1\n'
            'INSTRUMENTATION_STATUS: upgradeReceipt=' + json.dumps(observed, separators=(',', ':')) + '\n'
            'INSTRUMENTATION_STATUS_CODE: 0\n'
            f'INSTRUMENTATION_STATUS: class={smoke.TEST_CLASS}\n'
            f'INSTRUMENTATION_STATUS: test={method}\n'
            'INSTRUMENTATION_STATUS_CODE: 0\n'
            'INSTRUMENTATION_RESULT: stream=\nTime: 0.1\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n')


def installed(version=1):
    return {'package': 'dev.agentos.app', 'versionCode': version,
            'uid': 10042, 'firstInstallTime': '2026-10-28 10:00:00'}


class UpgradeHostBoundaryTests(unittest.TestCase):
    def test_only_exact_hosted_dispatch_source_can_operate_devices(self):
        self.assertEqual(SOURCE, smoke.source_from_environment(environment()))
        for field, value in [('GITHUB_ACTIONS', 'false'), ('RUNNER_ENVIRONMENT', 'self-hosted'),
                             ('GITHUB_REPOSITORY', 'fork/Agent-OS'), ('GITHUB_REF', 'refs/pull/1/merge'),
                             ('GITHUB_EVENT_NAME', 'push'), ('GITHUB_SHA', 'A' * 40),
                             ('GITHUB_WORKFLOW', 'Other workflow'),
                             ('GITHUB_WORKFLOW_REF', 'ecojust/Agent-OS/.github/workflows/android.yml@refs/heads/master'),
                             ('GITHUB_RUN_ID', '0'), ('GITHUB_RUN_ATTEMPT', '-1')]:
            with self.subTest(field=field):
                changed = environment(); changed[field] = value
                with self.assertRaises(smoke.Rejected):
                    smoke.source_from_environment(changed)

    def test_build_manifest_binds_every_file_to_the_independent_certificate_and_source(self):
        manifest = build_manifest()
        self.assertEqual(manifest, smoke.validate_build_manifest(manifest, SOURCE, manifest['files']))
        for mutation in [lambda m: m.update(baselineSource='b' * 40),
                         lambda m: m['source'].update(commit='b' * 40),
                         lambda m: m.update(certificateSha256='0' * 64),
                         lambda m: m['files'].pop('fixture.apk'),
                         lambda m: m['files'].update({'extra.apk': {'bytes': 10, 'sha256': '1' * 64}}),
                         lambda m: m['files']['probe.apk'].update(bytes=True),
                         lambda m: m['files']['signing-certificate.der'].update(bytes=16385)]:
            changed = copy.deepcopy(manifest); mutation(changed)
            with self.assertRaises(smoke.Rejected):
                smoke.validate_build_manifest(changed, SOURCE, manifest['files'])
        changed_files = copy.deepcopy(manifest['files']); changed_files['candidate.apk']['sha256'] = '0' * 64
        with self.assertRaisesRegex(smoke.Rejected, 'BUILD_FILE_CHANGED'):
            smoke.validate_build_manifest(manifest, SOURCE, changed_files)

    def test_preflight_must_describe_the_exact_v1_to_v2_pair_and_pin(self):
        manifest, checked = build_manifest(), preflight_result()
        smoke.validate_preflight(checked, manifest)
        for mutation in [lambda p: p.update(status='rejected'),
                         lambda p: p.update(lineageEvaluated=True),
                         lambda p: p.update(installationPerformed=True),
                         lambda p: p.update(trustedCertificateSha256='0' * 64),
                         lambda p: p['candidate'].update(versionCode=1),
                         lambda p: p['baseline'].update(versionCode=2),
                         lambda p: p['candidate'].update(package='other.app'),
                         lambda p: p['candidate'].update(minSdk=31),
                         lambda p: p['candidate'].update(sha256='0' * 64),
                         lambda p: p['baseline'].update(currentSignerSha256='0' * 64),
                         lambda p: p['baseline'].update(currentSignerCount=2)]:
            changed = copy.deepcopy(checked); mutation(changed)
            with self.assertRaises(smoke.Rejected):
                smoke.validate_preflight(changed, manifest)

    def test_booleans_are_not_numeric_schema_or_apk_version_values(self):
        manifest = build_manifest(); manifest['schemaVersion'] = True
        with self.assertRaises(smoke.Rejected):
            smoke.validate_build_manifest(manifest, SOURCE)
        for mutation in [lambda p: p.update(schemaVersion=True),
                         lambda p: p['baseline'].update(versionCode=True),
                         lambda p: p['baseline'].update(currentSignerCount=True)]:
            checked = preflight_result(); mutation(checked)
            with self.assertRaises(smoke.Rejected):
                smoke.validate_preflight(checked, build_manifest())
        value = receipt(); value['schemaVersion'] = True
        with self.assertRaises(smoke.Rejected):
            smoke.validate_receipt(value, 'seedBaseline', SOURCE, '35', NONCE_SHA)

    def test_package_dump_needs_one_exact_identity_without_duplicate_metadata(self):
        dump = ('Packages:\n  Package [dev.agentos.app] (synthetic):\n'
                '    userId=10042\n    versionCode=1 minSdk=30 targetSdk=35\n'
                '    firstInstallTime=2026-10-28 10:00:00\n')
        self.assertEqual(installed(), smoke.parse_package_dump(dump, 1))
        self.assertEqual(installed(), smoke.parse_package_dump(dump.replace('userId=', 'appId='), 1))
        for changed in [dump + '    userId=10043\n', dump + '    appId=10042\n',
                        dump.replace('userId=', 'appId=') + '    appId=10042\n',
                        dump.replace('versionCode=1', 'versionCode=2'),
                        dump.replace('userId=10042', 'userId=999'), dump.replace('dev.agentos.app', 'other.app'),
                        dump.replace('firstInstallTime=', 'missingFirstInstallTime=')]:
            with self.assertRaises(smoke.Rejected):
                smoke.parse_package_dump(changed, 1)

    def test_upgrade_requires_same_installation_and_new_process(self):
        smoke.validate_installed_transition(installed(1), installed(2), 401, 402)
        for field, value in [('uid', 10043), ('firstInstallTime', '2026-10-28 10:00:01'),
                             ('package', 'other.app'), ('versionCode', 1)]:
            changed = installed(2); changed[field] = value
            with self.assertRaises(smoke.Rejected):
                smoke.validate_installed_transition(installed(1), changed, 401, 402)
        for old, new in [(401, 401), (0, 402), (401, True), ('401', 402)]:
            with self.assertRaises(smoke.Rejected):
                smoke.validate_installed_transition(installed(1), installed(2), old, new)

    def test_one_junit_pass_and_one_receipt_are_both_required(self):
        for method in ('seedBaseline', 'verifyCandidate'):
            self.assertEqual(receipt(method), smoke.parse_instrumentation(instrumentation(method), method))
        valid = instrumentation()
        for changed in [valid.replace('OK (1 test)', 'OK (2 tests)'),
                        valid.replace('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'),
                        valid + 'INSTRUMENTATION_CODE: -1\n',
                        valid.replace('INSTRUMENTATION_STATUS_CODE: 1\n', ''),
                        valid + 'INSTRUMENTATION_STATUS_CODE: 1\n',
                        valid.replace('INSTRUMENTATION_STATUS: numtests=1\n', ''),
                        valid.replace('INSTRUMENTATION_STATUS: numtests=1', 'INSTRUMENTATION_STATUS: numtests=2'),
                        valid.replace(smoke.TEST_CLASS, 'other.Test'),
                        valid.replace('test=seedBaseline', 'test=verifyCandidate'),
                        valid.replace('upgradeReceipt=', 'ignoredReceipt='),
                        valid + 'INSTRUMENTATION_STATUS: upgradeReceipt={}\n']:
            with self.assertRaises(smoke.Rejected):
                smoke.parse_instrumentation(changed, 'seedBaseline')
        for marker in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED',
                       'shortMsg=synthetic', 'Process crashed', 'INSTRUMENTATION_STATUS_CODE: -2',
                       'INSTRUMENTATION_STATUS_CODE: -3', 'INSTRUMENTATION_STATUS_CODE: -4']:
            with self.assertRaises(smoke.Rejected):
                smoke.parse_instrumentation(valid + marker + '\n', 'seedBaseline')

    def test_receipt_json_duplicates_and_overflow_cannot_hide_another_result(self):
        duplicate = instrumentation().replace('"schemaVersion":1', '"schemaVersion":1,"schemaVersion":1')
        with self.assertRaises(smoke.Rejected):
            smoke.parse_instrumentation(duplicate, 'seedBaseline')
        oversized = instrumentation(observed={'data': 'x' * 4096})
        with self.assertRaises(smoke.Rejected):
            smoke.parse_instrumentation(oversized, 'seedBaseline')

    def test_receipt_is_bound_to_source_run_api_nonce_and_phase(self):
        seed = receipt()
        self.assertEqual(seed, smoke.validate_receipt(seed, 'seedBaseline', SOURCE, '35', NONCE_SHA))
        for field, value in [('phase', 'verifyCandidate'), ('baselineSource', 'b' * 40),
                             ('candidateSource', 'b' * 40), ('runId', 124), ('runAttempt', 2),
                             ('api', 36), ('nonceSha256', '0' * 64), ('targetVersionCode', 2),
                             ('pid', 0), ('targetUid', True), ('envelopeSha256', 'not-a-digest')]:
            changed = copy.deepcopy(seed); changed[field] = value
            with self.assertRaises(smoke.Rejected):
                smoke.validate_receipt(changed, 'seedBaseline', SOURCE, '35', NONCE_SHA)
        changed = copy.deepcopy(seed); changed['untrustedExtra'] = True
        with self.assertRaises(smoke.Rejected):
            smoke.validate_receipt(changed, 'seedBaseline', SOURCE, '35', NONCE_SHA)

    def test_seed_requires_actual_unapproved_stopped_task_and_drained_worker(self):
        for field, value in [('approvalObserved', False), ('approvalGranted', True), ('taskStopped', False),
                             ('stoppedUsageRetained', False), ('workerDrained', False), ('dispatchAttempts', 1),
                             ('dispatchAttempts', False), ('secureWindowVerified', False), ('captureStatus', 'captured')]:
            changed = receipt(); changed[field] = value
            with self.assertRaises(smoke.Rejected):
                smoke.validate_receipt(changed, 'seedBaseline', SOURCE, '35', NONCE_SHA)

    def test_candidate_requires_unchanged_settings_identity_and_no_restored_runtime(self):
        seed, verified = receipt(), receipt('verifyCandidate')
        self.assertEqual(verified, smoke.validate_receipt(verified, 'verifyCandidate', SOURCE, '35', NONCE_SHA, seed))
        for field, value in [('envelopeSha256', '0' * 64), ('revisionSha256', '0' * 64),
                             ('grantSha256', '0' * 64), ('profileSha256', '0' * 64),
                             ('targetUid', 10043), ('firstInstallTime', 1700000000001), ('pid', seed['pid']),
                             ('freshController', False), ('noTaskOrApproval', False),
                             ('noTaskHistory', False), ('noTaskInput', False), ('workerDrained', False)]:
            changed = copy.deepcopy(verified); changed[field] = value
            with self.assertRaises(smoke.Rejected):
                smoke.validate_receipt(changed, 'verifyCandidate', SOURCE, '35', NONCE_SHA, seed)
        with self.assertRaises(smoke.Rejected):
            smoke.validate_receipt(verified, 'verifyCandidate', SOURCE, '35', NONCE_SHA)

    def test_prepared_report_keeps_all_phases_and_all_eight_apis_unverified(self):
        value = smoke.empty_report(SOURCE, '37.0')
        self.assertEqual(['30', '31', '32', '33', '34', '35', '36', '37.0'], value['apis'])
        self.assertEqual('unverified', value['status'])
        self.assertFalse(value['executionAttempted'])
        self.assertEqual(list(smoke.PHASES), [item['name'] for item in value['phases']])
        self.assertTrue(all(item['status'] == 'unverified' for item in value['phases']))
        self.assertEqual(0, value['baselineInstallAttempts'])
        self.assertEqual(0, value['candidateInstallAttempts'])
        self.assertTrue(all(capture['status'] == 'blocked' and not capture['secureFlagsCleared']
                            and 'path' not in capture for capture in value['captures']))
        for invalid in ['37', '29', 35]:
            with self.assertRaises(smoke.Rejected):
                smoke.empty_report(SOURCE, invalid)

    def test_unexecuted_report_cannot_be_promoted_to_pass_or_change_its_capture_policy(self):
        value = smoke.empty_report(SOURCE, '35')
        self.assertEqual('unverified', smoke.validate_report(value, SOURCE, '35', {}))
        for mutation in [lambda r: r.update(status='passed'),
                         lambda r: r['captures'][0].update(secureFlagsCleared=True),
                         lambda r: r['apis'].remove('30'),
                         lambda r: r.update(candidateInstallAttempts=2),
                         lambda r: r.update(executionAttempted=1)]:
            changed = copy.deepcopy(value); mutation(changed)
            with self.assertRaises(smoke.Rejected):
                smoke.validate_report(changed, SOURCE, '35', {})

    def test_failed_command_payload_stays_hash_bound_when_archived(self):
        value = smoke.empty_report(SOURCE, '35'); value.update(status='failed', executionAttempted=True)
        value['phases'][0].update(status='passed')
        value['phases'][1].update(status='failed', reason='COMMAND_FAILED')
        # A devices command is reachable only after a complete bundle preflight.
        # These are parser fixtures, not claims of verified APKs or a real device.
        certificate = b'SYNTHETIC CERTIFICATE FOR PARTIAL REPORT'
        pin = hashlib.sha256(certificate).hexdigest()
        manifest, checked = build_manifest(), preflight_result()
        manifest['certificateSha256'] = checked['trustedCertificateSha256'] = pin
        manifest['files']['signing-certificate.der'] = {'bytes': len(certificate), 'sha256': pin}
        for role in ('baseline', 'candidate'):
            checked[role]['currentSignerSha256'] = pin
        raw_checked = json.dumps(checked).encode()
        manifest['files']['preflight.json'] = {'bytes': len(raw_checked),
                                               'sha256': hashlib.sha256(raw_checked).hexdigest()}
        command = {'schemaVersion': 1, 'label': 'devices', 'exitCode': 1, 'timeoutSeconds': 30,
                   'elapsedMillis': 1, 'reason': 'COMMAND_FAILED',
                   'stdoutBase64': base64.b64encode(b'synthetic partial output').decode(), 'stderrBase64': ''}
        raw = json.dumps(command).encode(); name = 'commands/01-devices.json'
        files = {'build-manifest.json': {'data': json.dumps(manifest).encode()},
                 'preflight.json': {'data': raw_checked}, 'signing-certificate.der': {'data': certificate},
                 name: {'data': raw}}
        value['artifactFiles'] = [{'path': path, 'bytes': len(item['data']),
                                   'sha256': hashlib.sha256(item['data']).hexdigest()} for path, item in files.items()]
        self.assertEqual('failed', smoke.validate_report(value, SOURCE, '35', files))
        with self.assertRaisesRegex(smoke.Rejected, 'ARTIFACT_HASH_MISMATCH'):
            smoke.validate_report(value, SOURCE, '35', {**files, name: {'data': raw + b' '}})
        with self.assertRaises(smoke.Rejected):
            smoke.validate_report(value, SOURCE, '35', {**files, 'unlisted.json': {'data': b'{}'}})

    def test_failed_report_replacement_preserves_previous_bytes_and_removes_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / 'report.json'
            original = b'{"syntheticPreviousReport":true}\n'
            destination.write_bytes(original)
            with mock.patch.object(Path, 'replace', side_effect=OSError('synthetic replacement failure')):
                with self.assertRaises(OSError):
                    smoke.write_json(destination, {'syntheticNewReport': True})
            self.assertEqual(original, destination.read_bytes())
            self.assertEqual([destination], list(Path(directory).iterdir()))

    def test_orphaned_published_manifest_is_recovered_without_promoting_a_phase(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = smoke.empty_report(SOURCE, '35')
            report['executionAttempted'] = True
            report['phases'][0]['status'] = 'running'
            original_phases = copy.deepcopy(report['phases'])
            manifest = root / 'build-manifest.json'
            manifest.write_text(json.dumps(build_manifest()))
            (root / '.partial-report').write_bytes(b'{incomplete synthetic JSON')
            (root / 'commands').mkdir()
            (root / 'commands' / '.partial-01-devices.json').write_bytes(b'{partial')
            smoke.recover_published_payloads(root, report)
            self.assertEqual('failed', report['status'])
            self.assertEqual(original_phases, report['phases'])
            self.assertEqual([{'path': 'build-manifest.json', **smoke.file_identity(manifest)}],
                             report['artifactFiles'])
            frozen = copy.deepcopy(report)
            smoke.recover_published_payloads(root, report)
            self.assertEqual(frozen, report)


class BoundedCommandEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.report = smoke.empty_report(SOURCE, '35')
        self.runner = smoke.CommandRunner(self.root, self.report)

    def record(self, index, label):
        path = self.root / 'commands' / f'{index:02d}-{label}.json'
        result = json.loads(path.read_text())
        return result, base64.b64decode(result['stdoutBase64']), base64.b64decode(result['stderrBase64'])

    def test_failed_child_keeps_its_bounded_original_stdout_and_stderr(self):
        command = [sys.executable, '-c', "import sys; print('synthetic stdout'); print('synthetic stderr', file=sys.stderr); sys.exit(7)"]
        with self.assertRaisesRegex(smoke.Rejected, 'COMMAND_FAILED'):
            self.runner.run('synthetic-failure', command)
        record, stdout, stderr = self.record(1, 'synthetic-failure')
        self.assertEqual(7, record['exitCode'])
        self.assertEqual(b'synthetic stdout\n', stdout)
        self.assertEqual(b'synthetic stderr\n', stderr)
        self.assertEqual('COMMAND_FAILED', record['reason'])
        saved = json.loads((self.root / 'report.json').read_text())
        self.assertEqual(1, len(saved['artifactFiles']))
        self.assertEqual(smoke.file_identity(self.root / saved['artifactFiles'][0]['path'])['sha256'],
                         saved['artifactFiles'][0]['sha256'])

    def test_timeout_preserves_output_emitted_before_deadline(self):
        command = [sys.executable, '-c', "import time; print('synthetic before timeout', flush=True); time.sleep(10)"]
        with self.assertRaisesRegex(smoke.Rejected, 'COMMAND_TIMEOUT'):
            self.runner.run('synthetic-timeout', command, timeout=0.3)
        record, stdout, _ = self.record(1, 'synthetic-timeout')
        self.assertEqual(b'synthetic before timeout\n', stdout)
        self.assertEqual('COMMAND_TIMEOUT', record['reason'])
        self.assertLess(record['elapsedMillis'], 3000)

    def test_output_limit_is_applied_while_reading_and_retains_bounded_prefix(self):
        # Keep room for report metadata while exercising the bounded stream reader.
        with mock.patch.object(smoke, 'MAX_OUTPUT', 4096):
            with self.assertRaisesRegex(smoke.Rejected, 'COMMAND_OUTPUT_LIMIT'):
                self.runner.run('synthetic-flood', [sys.executable, '-c', "import os; os.write(1,b'x'*16384)"])
        record, stdout, stderr = self.record(1, 'synthetic-flood')
        self.assertEqual('COMMAND_OUTPUT_LIMIT', record['reason'])
        self.assertEqual(4096, len(stdout) + len(stderr))
        self.assertEqual(b'x' * 4096, stdout)

    def test_combined_command_budget_cannot_be_reset_by_another_child(self):
        with mock.patch.object(smoke, 'MAX_TOTAL_OUTPUT', 20):
            self.runner.run('synthetic-first', [sys.executable, '-c', "import os; os.write(1,b'a'*10)"])
            with self.assertRaisesRegex(smoke.Rejected, 'COMMAND_OUTPUT_LIMIT'):
                self.runner.run('synthetic-second', [sys.executable, '-c', "import os; os.write(2,b'b'*11)"])
        _, stdout, stderr = self.record(2, 'synthetic-second')
        self.assertEqual(10, len(stdout) + len(stderr))
        self.assertEqual(20, self.runner.total)

    def test_missing_executable_is_preserved_as_a_failed_command(self):
        with self.assertRaisesRegex(smoke.Rejected, 'COMMAND_UNAVAILABLE'):
            self.runner.run('synthetic-missing', [str(self.root / 'not-an-executable')])
        record, stdout, stderr = self.record(1, 'synthetic-missing')
        self.assertEqual('COMMAND_UNAVAILABLE', record['reason'])
        self.assertEqual((b'', b''), (stdout, stderr))


class SyntheticModelBoundaryTests(unittest.TestCase):
    @staticmethod
    def post(server, body=None, path='/v1/chat/completions', key='synthetic-upgrade-key'):
        connection = http.client.HTTPConnection('127.0.0.1', server.port, timeout=3)
        try:
            encoded = json.dumps({'model': 'synthetic-upgrade-model'} if body is None else body).encode()
            connection.request('POST', path, body=encoded,
                               headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_only_one_valid_request_can_propose_the_unapproved_fixture_launch(self):
        with smoke.ModelServer() as server:
            status, value = self.post(server)
            self.assertEqual(200, status)
            tool = value['choices'][0]['message']['tool_calls'][0]
            self.assertEqual('launch_app', tool['function']['name'])
            self.assertEqual({'packageName': 'dev.agentos.fixture'}, json.loads(tool['function']['arguments']))
            self.assertEqual((1, 0), server.snapshot())
            status, value = self.post(server)
            self.assertEqual(503, status)
            self.assertEqual({'error': {'message': 'synthetic request rejected'}}, value)
            self.assertEqual((2, 1), server.snapshot())

    def test_wrong_request_identity_is_rejected_without_reflecting_request_content(self):
        for arguments in [dict(path='/untrusted'), dict(key='untrusted-key'),
                          dict(body={'model': 'other-model'}),
                          dict(body={'model': 'synthetic-upgrade-model', 'stream': True})]:
            with self.subTest(arguments=arguments), smoke.ModelServer() as server:
                status, value = self.post(server, **arguments)
                self.assertEqual(503, status)
                self.assertEqual({'error': {'message': 'synthetic request rejected'}}, value)
                self.assertEqual((1, 1), server.snapshot())

    def test_json_array_is_rejected_as_invalid_request_not_an_unrecorded_handler_crash(self):
        with smoke.ModelServer() as server:
            status, value = self.post(server, body=[])
            self.assertEqual(503, status)
            self.assertEqual({'error': {'message': 'synthetic request rejected'}}, value)
            self.assertEqual((1, 1), server.snapshot())


class UpgradeDriverSequenceTests(unittest.TestCase):
    """A recorded fake transport checks host decisions, never claims an installed APK."""
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bundle = self.root / 'bundle'; self.bundle.mkdir()
        self.sdk = self.root / 'sdk'; (self.sdk / 'emulator').mkdir(parents=True)
        (self.sdk / 'emulator' / 'source.properties').write_text('Pkg.Revision=37.2.11\n')
        for name in smoke.APK_NAMES:
            (self.bundle / name).write_bytes(b'SYNTHETIC UNIT FIXTURE, NOT AN APK: ' + name.encode())
        (self.bundle / 'signing-certificate.der').write_bytes(b'SYNTHETIC CERTIFICATE FIXTURE')
        pin = smoke.file_identity(self.bundle / 'signing-certificate.der')['sha256']
        self.checked = preflight_result(); self.checked['trustedCertificateSha256'] = pin
        for role in ('baseline', 'candidate'):
            self.checked[role].update(smoke.file_identity(self.bundle / (role + '.apk')),
                                      currentSignerSha256=pin)
        (self.bundle / 'preflight.json').write_text(json.dumps(self.checked))
        self.manifest = {'schemaVersion': 1, 'kind': 'agentos-apk-upgrade-build', 'source': SOURCE,
                         'baselineSource': smoke.BASELINE_SOURCE, 'signingPolicy': 'temporary-development-certificate',
                         'certificateSha256': pin,
                         'files': {name: smoke.file_identity(self.bundle / name)
                                   for name in (*smoke.APK_NAMES, 'signing-certificate.der', 'preflight.json')}}
        (self.bundle / 'build-manifest.json').write_text(json.dumps(self.manifest))
        self.calls = []
        self.server_events = []
        self.args = SimpleNamespace(bundle=self.bundle, output=self.root / 'output', sdk_root=self.sdk, api='35')

    def run_synthetic(self, scenario='success'):
        calls, bundle, server_events = self.calls, self.bundle, self.server_events
        properties = {'ro.kernel.qemu': '1', 'ro.boot.qemu': '1', 'ro.build.version.sdk': '35',
                      'ro.product.cpu.abi': 'x86_64', 'sys.boot_completed': '1',
                      'ro.build.fingerprint': 'Android/sdk_phone64_x86_64/synthetic:15/test/test:userdebug/test-keys'}
        if scenario == 'not-qemu': properties['ro.kernel.qemu'] = '0'
        if scenario == 'wrong-abi': properties['ro.product.cpu.abi'] = 'arm64-v8a'

        class FakeRunner:
            def __init__(self, directory, report):
                self.private = None
                self.services = ('synthetic.other/Service' if scenario == 'existing-service'
                                 else '' if scenario == 'empty-services-null-flag' else 'null')
                self.accessibility_flag = ('1' if scenario == 'existing-accessibility-flag'
                                           else 'null' if scenario == 'empty-services-null-flag' else '0')

            def run(self, label, argv, timeout=30):
                calls.append((label, list(argv)))
                if label == 'devices':
                    return 'List of devices attached\n' + ('physical-123' if scenario == 'physical' else 'emulator-5554') + '\tdevice\n'
                if label.startswith('property-'): return properties[argv[-1]] + '\n'
                if label == 'current-user': return '10\n' if scenario == 'other-user' else '0\n'
                if label == 'users':
                    return 'Users:\n UserInfo{0:Owner:13}\n' + (' UserInfo{10:Work:30}\n' if scenario == 'extra-user' else '')
                if label == 'fresh-packages':
                    return 'package:android\n' + ('package:dev.agentos.app\n' if scenario == 'existing-app' else '')
                if label in ('install-baseline', 'install-fixture', 'install-probe'):
                    self.private = Path(argv[-1]).parent
                    return 'Success\n'
                if label == 'replace-candidate':
                    if scenario == 'install-failed': raise smoke.Rejected('COMMAND_FAILED')
                    return 'Success\n'
                if label in ('baseline-package', 'candidate-package'):
                    version = 1 if label == 'baseline-package' else 2
                    if label == 'candidate-package' and scenario == 'old-version': version = 1
                    uid = 10043 if label == 'candidate-package' and scenario == 'uid-changed' else 10042
                    first = '2026-10-28 10:00:01' if label == 'candidate-package' and scenario == 'install-time-changed' else '2026-10-28 10:00:00'
                    return f'Packages:\n Package [dev.agentos.app] (synthetic):\n  appId={uid}\n  versionCode={version} minSdk=30 targetSdk=35\n  firstInstallTime={first}\n'
                if label in ('baseline-path', 'candidate-path'):
                    return 'package:/data/app/~~syntheticToken/dev.agentos.app-anotherToken/base.apk\n'
                if label in ('baseline-pull', 'candidate-pull'):
                    data = (bundle / ('baseline.apk' if label == 'baseline-pull' else 'candidate.apk')).read_bytes()
                    if label == 'candidate-pull' and scenario == 'installed-bytes-changed': data += b' altered'
                    Path(argv[-1]).write_bytes(data)
                    return '1 file pulled\n'
                if label in ('wake-screen', 'dismiss-keyguard'): return ''
                if label in ('accessibility-list-before', 'accessibility-list-after', 'accessibility-list-restored'):
                    return self.services + '\n'
                if label in ('accessibility-flag-before', 'accessibility-flag-after', 'accessibility-flag-restored'):
                    return self.accessibility_flag + '\n'
                if label == 'accessibility-list-enable':
                    if scenario != 'service-readback-mismatch': self.services = argv[-1]
                    if scenario == 'service-list-enable-failed': raise smoke.Rejected('COMMAND_FAILED')
                    return ''
                if label == 'accessibility-flag-enable':
                    self.accessibility_flag = argv[-1]
                    if scenario == 'service-flag-enable-failed': raise smoke.Rejected('COMMAND_FAILED')
                    return ''
                if label == 'accessibility-list-restore':
                    if scenario == 'service-restore-failed': raise smoke.Rejected('COMMAND_FAILED')
                    if scenario != 'service-restore-readback-mismatch':
                        self.services = 'null' if 'delete' in argv else '' if argv[-1] == '""' else argv[-1]
                    return ''
                if label == 'accessibility-flag-restore':
                    self.accessibility_flag = 'null' if 'delete' in argv else argv[-1]
                    return ''
                if label == 'reverse-add':
                    if scenario == 'reverse-add-failed': raise smoke.Rejected('COMMAND_FAILED')
                    return ''
                if label == 'reverse-remove': return ''
                if label in ('seed-instrumentation', 'verify-instrumentation'):
                    method = 'seedBaseline' if label == 'seed-instrumentation' else 'verifyCandidate'
                    value = receipt(method)
                    nonce = argv[argv.index('nonce') + 1]
                    value['nonceSha256'] = hashlib.sha256(nonce.encode()).hexdigest()
                    if method == 'verifyCandidate' and scenario == 'same-pid': value['pid'] = 500
                    if method == 'verifyCandidate' and scenario == 'settings-changed': value['envelopeSha256'] = '0' * 64
                    if method == 'seedBaseline' and scenario == 'seed-failed':
                        return instrumentation(method, value) + 'FAILURES!!!\n'
                    return instrumentation(method, value)
                if label == 'baseline-launch': return 'Starting: Intent { synthetic }\nStatus: ok\n'
                if label == 'baseline-pid':
                    if scenario == 'private-input-changed':
                        target = self.private / 'candidate.apk'
                        target.chmod(0o600); target.write_bytes(b'ALTERED PRIVATE SNAPSHOT')
                    return '500\n'
                raise AssertionError('Unexpected synthetic command label: ' + label)

        class FakeServer:
            port = 18080
            def __init__(self):
                self.samples = 0
                server_events.append('created')
            def __enter__(self):
                server_events.append('entered')
                return self
            def __exit__(self, *unused): server_events.append('closed')
            def snapshot(self):
                self.samples += 1
                return (2 if self.samples > 1 and scenario == 'candidate-request' else 1, 0)

        with mock.patch.object(smoke.preflight, 'preflight', return_value=self.checked), \
                mock.patch.object(smoke, 'helper_identity'):
            accepted = smoke.run_probe(self.args, SOURCE, runner_factory=FakeRunner, server_factory=FakeServer)
        return accepted, json.loads((self.args.output / 'report.json').read_text())

    def assert_no_data_reset(self):
        for _, argv in self.calls:
            self.assertNotIn('uninstall', argv)
            self.assertNotIn('clear', argv)
            self.assertNotIn('-d', argv)

    def assert_final_service_restoration(self):
        labels = [label for label, _ in self.calls]
        restoration = ['accessibility-list-restore', 'accessibility-flag-restore',
                       'accessibility-list-restored', 'accessibility-flag-restored']
        self.assertEqual(restoration, labels[-4:])
        for label in restoration:
            self.assertEqual(1, labels.count(label))

    def test_one_normal_install_then_one_replacement_and_two_exact_sessions(self):
        accepted, report = self.run_synthetic()
        self.assertTrue(accepted)
        labels = [label for label, _ in self.calls]
        self.assertEqual(1, labels.count('install-baseline'))
        self.assertEqual(1, labels.count('replace-candidate'))
        self.assertLess(labels.index('seed-instrumentation'), labels.index('replace-candidate'))
        self.assertLess(labels.index('replace-candidate'), labels.index('verify-instrumentation'))
        baseline = next(argv for label, argv in self.calls if label == 'install-baseline')
        candidate = next(argv for label, argv in self.calls if label == 'replace-candidate')
        self.assertNotIn('-r', baseline); self.assertIn('-r', candidate)
        for label, method in [('seed-instrumentation', 'seedBaseline'), ('verify-instrumentation', 'verifyCandidate')]:
            argv = next(argv for name, argv in self.calls if name == label)
            self.assertIn(smoke.TEST_CLASS + '#' + method, argv)
            self.assertEqual(smoke.COMPONENT, argv[-1])
        self.assertEqual((1, 1), (report['baselineInstallAttempts'], report['candidateInstallAttempts']))
        self.assertEqual(list(smoke.COMMAND_LABELS), labels)
        self.assertLess(labels.index('accessibility-flag-after'), labels.index('seed-instrumentation'))
        self.assertLess(labels.index('verify-instrumentation'), labels.index('accessibility-list-restore'))
        self.assertEqual({'enabledServices': 'null', 'accessibilityEnabled': '0'}, report['serviceSettingsBefore'])
        self.assertTrue(report['serviceSettingsRestored'])
        self.assertEqual(['created', 'entered', 'closed'], self.server_events)
        self.assert_final_service_restoration()
        self.assert_no_data_reset()

    def test_failed_replacement_is_not_retried_and_later_phases_stay_unverified(self):
        accepted, report = self.run_synthetic('install-failed')
        self.assertFalse(accepted)
        labels = [label for label, _ in self.calls]
        self.assertEqual(1, labels.count('replace-candidate'))
        self.assertNotIn('verify-instrumentation', labels)
        phases = {item['name']: item['status'] for item in report['phases']}
        self.assertEqual('failed', phases['replace_candidate'])
        self.assertEqual('unverified', phases['candidate_identity'])
        self.assertEqual('unverified', phases['verify_candidate'])
        self.assertEqual(1, report['candidateInstallAttempts'])
        self.assertLess(labels.index('replace-candidate'), labels.index('accessibility-list-restore'))
        self.assertTrue(report['serviceSettingsRestored'])
        self.assert_final_service_restoration()
        self.assert_no_data_reset()

    def test_existing_accessibility_state_refuses_seed_without_overwriting_it(self):
        for scenario in ['existing-service', 'existing-accessibility-flag']:
            with self.subTest(scenario=scenario):
                self.args.output = self.root / scenario; self.calls.clear(); self.server_events.clear()
                accepted, report = self.run_synthetic(scenario)
                self.assertFalse(accepted)
                labels = [label for label, _ in self.calls]
                self.assertIn('baseline-pull', labels)
                self.assertNotIn('accessibility-list-enable', labels)
                self.assertNotIn('accessibility-list-restore', labels)
                self.assertNotIn('seed-instrumentation', labels)
                self.assertEqual([], self.server_events)
                phases = {item['name']: item for item in report['phases']}
                self.assertEqual('EXISTING_ACCESSIBILITY_STATE_REFUSED', phases['service_setup']['reason'])
                self.assertEqual('unverified', phases['model_setup']['status'])
                self.assertEqual((1, 0), (report['baselineInstallAttempts'], report['candidateInstallAttempts']))
                self.assertFalse(report['serviceSettingsRestored'])
                self.assert_no_data_reset()

    def test_partial_service_enable_and_failed_readback_restore_even_without_a_model(self):
        for scenario in ['service-list-enable-failed', 'service-flag-enable-failed', 'service-readback-mismatch']:
            with self.subTest(scenario=scenario):
                self.args.output = self.root / scenario; self.calls.clear(); self.server_events.clear()
                accepted, report = self.run_synthetic(scenario)
                self.assertFalse(accepted)
                labels = [label for label, _ in self.calls]
                self.assertNotIn('seed-instrumentation', labels)
                self.assertNotIn('reverse-add', labels)
                self.assertEqual([], self.server_events)
                self.assertTrue(report['serviceSettingsRestored'])
                phases = {item['name']: item['status'] for item in report['phases']}
                self.assertEqual('failed', phases['service_setup'])
                self.assertEqual('unverified', phases['model_setup'])
                self.assertEqual('passed', phases['cleanup'])
                self.assert_final_service_restoration()
                self.assertEqual(0, report['candidateInstallAttempts'])
                self.assert_no_data_reset()

    def test_failed_reverse_setup_still_removes_the_possibly_created_mapping_once(self):
        accepted, report = self.run_synthetic('reverse-add-failed')
        self.assertFalse(accepted)
        labels = [label for label, _ in self.calls]
        self.assertEqual(1, labels.count('reverse-add'))
        self.assertEqual(1, labels.count('reverse-remove'))
        self.assertLess(labels.index('reverse-add'), labels.index('reverse-remove'))
        self.assertEqual('reverse-remove', labels[-5])
        self.assertNotIn('seed-instrumentation', labels)
        self.assertNotIn('replace-candidate', labels)
        self.assertEqual(['created', 'entered', 'closed'], self.server_events)
        phases = {item['name']: item for item in report['phases']}
        self.assertEqual('COMMAND_FAILED', phases['model_setup']['reason'])
        self.assertEqual('unverified', phases['seed_baseline']['status'])
        self.assertEqual('passed', phases['cleanup']['status'])
        self.assertTrue(report['serviceSettingsRestored'])
        self.assertEqual(0, report['candidateInstallAttempts'])
        self.assert_final_service_restoration()
        self.assert_no_data_reset()

    def test_service_cleanup_failure_cannot_be_hidden_by_successful_candidate_checks(self):
        for scenario in ['service-restore-failed', 'service-restore-readback-mismatch']:
            with self.subTest(scenario=scenario):
                self.args.output = self.root / scenario; self.calls.clear(); self.server_events.clear()
                accepted, report = self.run_synthetic(scenario)
                self.assertFalse(accepted)
                phases = {item['name']: item for item in report['phases']}
                self.assertEqual('passed', phases['verify_candidate']['status'])
                self.assertEqual('CLEANUP_FAILED', phases['cleanup']['reason'])
                self.assertFalse(report['serviceSettingsRestored'])
                self.assertEqual((1, 1), (report['baselineInstallAttempts'], report['candidateInstallAttempts']))
                self.assert_final_service_restoration()
                self.assert_no_data_reset()

    def test_empty_and_absent_original_service_settings_are_restored_exactly(self):
        accepted, report = self.run_synthetic('empty-services-null-flag')
        self.assertTrue(accepted)
        self.assertEqual({'enabledServices': '', 'accessibilityEnabled': 'null'}, report['serviceSettingsBefore'])
        self.assertTrue(report['serviceSettingsRestored'])
        commands = dict(self.calls)
        self.assertEqual(['settings', 'put', 'secure', 'enabled_accessibility_services', '""'],
                         commands['accessibility-list-restore'][-5:])
        self.assertEqual(['settings', 'delete', 'secure', 'accessibility_enabled'],
                         commands['accessibility-flag-restore'][-4:])
        self.assert_final_service_restoration()
        self.assert_no_data_reset()

    def test_unsafe_or_nonfresh_device_is_refused_before_any_install(self):
        for scenario in ['physical', 'not-qemu', 'wrong-abi', 'other-user', 'extra-user', 'existing-app']:
            with self.subTest(scenario=scenario):
                self.args.output = self.root / scenario; self.calls.clear()
                accepted, report = self.run_synthetic(scenario)
                self.assertFalse(accepted)
                self.assertFalse(any('install' in argv for _, argv in self.calls))
                self.assertEqual((0, 0), (report['baselineInstallAttempts'], report['candidateInstallAttempts']))
                self.assert_no_data_reset()

    def test_post_install_identity_and_runtime_discontinuities_cannot_pass(self):
        for scenario in ['uid-changed', 'install-time-changed', 'installed-bytes-changed',
                         'old-version', 'same-pid', 'settings-changed', 'candidate-request']:
            with self.subTest(scenario=scenario):
                self.args.output = self.root / scenario; self.calls.clear()
                accepted, report = self.run_synthetic(scenario)
                self.assertFalse(accepted)
                self.assertEqual('failed', report['status'])
                self.assertEqual(1, report['candidateInstallAttempts'])
                self.assertEqual(1, sum(label == 'replace-candidate' for label, _ in self.calls))
                self.assert_no_data_reset()

    def test_seed_failure_and_changed_candidate_snapshot_prevent_replacement(self):
        for scenario in ['seed-failed', 'private-input-changed']:
            with self.subTest(scenario=scenario):
                self.args.output = self.root / scenario; self.calls.clear()
                accepted, report = self.run_synthetic(scenario)
                self.assertFalse(accepted)
                self.assertNotIn('replace-candidate', [label for label, _ in self.calls])
                self.assertEqual(0, report['candidateInstallAttempts'])
                self.assert_no_data_reset()

    def test_same_output_cannot_trigger_a_second_device_attempt(self):
        self.run_synthetic('install-failed')
        before = list(self.calls)
        with self.assertRaisesRegex(smoke.Rejected, 'RUN_ALREADY_ATTEMPTED'):
            self.run_synthetic()
        self.assertEqual(before, self.calls)


if __name__ == '__main__':
    unittest.main()
