"""Malformed external evidence and fixed-denominator archive boundary tests."""

import copy
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import apk_upgrade_smoke_archive as archive
import apk_upgrade_smoke as probe


def source_run():
    return {"id": 123, "run_attempt": 2, "repository": {"id": 42, "full_name": "ecojust/Agent-OS"},
            "head_repository": {"id": 42, "full_name": "ecojust/Agent-OS"}, "event": "workflow_dispatch",
            "head_branch": "master", "head_sha": "a" * 40, "status": "completed", "conclusion": "success",
            "path": ".github/workflows/apk-upgrade-smoke.yml", "display_title": "APK upgrade smoke (test)",
            "run_started_at": "2026-10-28T09:00:00Z", "updated_at": "2026-10-28T09:10:00Z"}


def artifact(label="30", attempt=2):
    return {"id": 100 + int(label.split(".")[0]), "name": f"apk-upgrade-smoke-api-{label}-attempt-{attempt}",
            "size_in_bytes": 4096, "expired": False, "digest": "sha256:" + "b" * 64,
            "created_at": "2026-10-28T09:05:00Z",
            "workflow_run": {"id": 123, "head_sha": "a" * 40, "head_branch": "master",
                             "repository_id": 42, "head_repository_id": 42}}


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def identity(data):
    return {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def passing_payload(label='30', certificate=b'synthetic parser certificate, not an APK signing key'):
    """External protocol fixture, never installation or device-test evidence."""
    source = archive.verify_source(source_run())
    pin = hashlib.sha256(certificate).hexdigest()
    apks = {name: {'bytes': 1024 + index, 'sha256': str(index + 1) * 64}
            for index, name in enumerate(('baseline.apk', 'candidate.apk', 'fixture.apk', 'probe.apk'))}
    checked = {'schemaVersion': 1, 'kind': 'agentos-offline-apk-upgrade-preflight', 'status': 'passed',
               'signingPolicy': 'same-current-certificate-pinned', 'lineageEvaluated': False,
               'installationPerformed': False, 'deviceCompatibilityEvaluated': False,
               'configurationMigrationEvaluated': False, 'trustedCertificateSha256': pin,
               'tools': {'apksigner': '0.9', 'aapt2': 'Android Asset Packaging Tool (aapt) 2.19-11948709'}}
    for role, version in (('baseline', 1), ('candidate', 2)):
        checked[role] = {**apks[role + '.apk'], 'package': 'dev.agentos.app', 'versionCode': version,
                         'minSdk': 30, 'targetSdk': 35, 'currentSignerCount': 1, 'currentSignerSha256': pin}
    payload = {'preflight.json': encoded(checked), 'signing-certificate.der': certificate}
    build = {'schemaVersion': 1, 'kind': 'agentos-apk-upgrade-build', 'source': source,
             'baselineSource': archive.BASELINE_SOURCE, 'signingPolicy': 'temporary-development-certificate',
             'certificateSha256': pin, 'files': {**apks, **{name: identity(data) for name, data in payload.items()}}}
    payload['build-manifest.json'] = encoded(build)
    report = probe.empty_report(source, label)
    report.update(status='passed', executionAttempted=True, workflowOutcome='success',
                  baselineInstallAttempts=1, candidateInstallAttempts=1, requestsAfterSeed=1, requestsAfterVerify=1,
                  nonceSha256='e' * 64, baselinePidBeforeReplacement=111, candidatePid=202,
                  serviceSettingsBefore={'enabledServices': 'null', 'accessibilityEnabled': '0'}, serviceSettingsRestored=True,
                  device={'serial': 'emulator-5554', 'apiLevel': int(label.split('.')[0]), 'abi': 'x86_64',
                          'fingerprint': 'synthetic/emulator/fixture:11/BUILD/123:userdebug/test-keys',
                          'kernelQemu': '1', 'bootQemu': '1', 'emulatorVersion': '37.2.12', 'ownerUser': 0})
    for phase in report['phases']: phase['status'] = 'passed'
    receipts = {}
    for phase, version in (('seedBaseline', 1), ('verifyCandidate', 2)):
        value = {'schemaVersion': 1, 'phase': phase, 'runId': '123', 'runAttempt': '2',
                 'baselineSource': archive.BASELINE_SOURCE, 'candidateSource': 'a' * 40,
                 'api': int(label.split('.')[0]), 'nonceSha256': 'e' * 64, 'targetVersionCode': version,
                 'pid': 101 if version == 1 else 202, 'targetUid': 10123, 'firstInstallTime': 123456789,
                 'envelopeSha256': '7' * 64, 'revisionSha256': '8' * 64, 'grantSha256': '9' * 64,
                 'profileSha256': 'f' * 64, 'settingsVerified': True, 'fixtureGrantValid': True,
                 'secureWindowVerified': True, 'captureStatus': 'blocked',
                 'captureReason': 'secure_apk_upgrade_capture_not_attempted', 'workerDrained': True}
        if version == 1:
            value.update(approvalObserved=True, approvalGranted=False, taskStopped=True,
                         stoppedUsageRetained=True, dispatchAttempts=0)
        else:
            value.update(freshController=True, noTaskOrApproval=True, noTaskHistory=True, noTaskInput=True)
        receipts[phase] = value
        payload[('seed' if version == 1 else 'verify') + '-receipt.json'] = encoded(value)
    for role, version in (('baseline', 1), ('candidate', 2)):
        report['installed' + role.capitalize()] = {'package': 'dev.agentos.app', 'versionCode': version,
            'uid': 10123, 'firstInstallTime': '2026-10-28 09:01:00', 'apkSha256': apks[role + '.apk']['sha256'],
            'bytes': apks[role + '.apk']['bytes'], 'certificateSha256': pin}
    outputs = {'devices': 'List of devices attached\nemulator-5554\tdevice\n', 'property-0': '1', 'property-1': '1',
               'property-2': str(int(label.split('.')[0])), 'property-3': 'x86_64', 'property-4': '1',
               'property-5': report['device']['fingerprint'], 'current-user': '0',
               'users': 'Users:\n UserInfo{0:Owner:13} running\n', 'fresh-packages': 'package:android\n',
               'baseline-launch': 'Status: ok\n', 'baseline-pid': '111\n',
               'accessibility-list-before': 'null', 'accessibility-flag-before': '0',
               'accessibility-list-after': 'dev.agentos.app/dev.agentos.app.AgentAccessibilityService', 'accessibility-flag-after': '1',
               'accessibility-list-restored': 'null', 'accessibility-flag-restored': '0'}
    for name in ('install-baseline', 'install-fixture', 'install-probe', 'replace-candidate'): outputs[name] = 'Success\n'
    for role, version in (('baseline', 1), ('candidate', 2)):
        outputs[role + '-package'] = ('  Package [dev.agentos.app] (123a):\n'
            f'    versionCode={version} minSdk=30 targetSdk=35\n    userId=10123\n    firstInstallTime=2026-10-28 09:01:00\n')
        outputs[role + '-path'] = 'package:/data/app/synthetic/base.apk\n'
        outputs[role + '-pull'] = '1 file pulled\n'
    for method, name in (('seedBaseline', 'seed-instrumentation'), ('verifyCandidate', 'verify-instrumentation')):
        outputs[name] = ('INSTRUMENTATION_STATUS: numtests=1\n'
                         'INSTRUMENTATION_STATUS: class=dev.agentos.upgradesmoke.ApkUpgradeInstrumentedTest\n'
                         f'INSTRUMENTATION_STATUS: test={method}\n'
                         'INSTRUMENTATION_STATUS_CODE: 1\n'
                         'INSTRUMENTATION_STATUS: upgradeReceipt=' + json.dumps(receipts[method], separators=(',', ':')) + '\n'
                         'INSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n')
    for index, name in enumerate(archive.COMMAND_ORDER, 1):
        command = {'schemaVersion': 1, 'label': name, 'exitCode': 0, 'timeoutSeconds': 180,
                   'elapsedMillis': 25, 'reason': None, 'stdoutBase64': base64.b64encode(outputs.get(name, '').encode()).decode(),
                   'stderrBase64': ''}
        payload[f'commands/{index:02d}-{name}.json'] = encoded(command)
    report['artifactFiles'] = [dict(path=name, **identity(data)) for name, data in payload.items()]
    payload['report.json'] = encoded(report)
    return payload


class ApkUpgradeArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="apk-upgrade-archive-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.incoming = self.root / "incoming"
        self.incoming.mkdir()
        self.output = "screenshots/apk-upgrade-123-2"

    def run_archive(self, run=None, index=None):
        return archive.archive(self.incoming, self.output, run if run is not None else source_run(),
                               {"artifacts": []} if index is None else index, self.root)

    def lane(self, label='30', payload=None):
        directory = self.incoming / artifact(label)['name']; directory.mkdir()
        for name, data in (passing_payload(label) if payload is None else payload).items():
            target = directory / name; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(data)
        return directory

    def update_payload(self, directory, name, value, *, bundle=False):
        (directory / name).write_bytes(encoded(value))
        if bundle:
            build_path = directory / 'build-manifest.json'; build = json.loads(build_path.read_bytes())
            build['files'][name] = identity((directory / name).read_bytes()); build_path.write_bytes(encoded(build))
        report_path = directory / 'report.json'; report = json.loads(report_path.read_bytes())
        for item in report['artifactFiles']: item.update(identity((directory / item['path']).read_bytes()))
        report_path.write_bytes(encoded(report))

    def assert_lane_rejected(self, labels=('30',)):
        with self.assertRaises((ValueError, OSError)):
            self.run_archive(index={'artifacts': [artifact(label) for label in labels]})
        self.assertFalse((self.root / self.output).exists())

    def test_full_success_is_eight_continuity_cases_not_sixteen_instrumentation_cases(self):
        for label in archive.LABELS: self.lane(label)
        result = self.run_archive(index={'artifacts': [artifact(label) for label in archive.LABELS]})
        self.assertEqual({'passed': 8, 'failed': 0, 'unverified': 0}, result['stateCounts'])
        self.assertTrue(result['allUpgradeCasesPassed']); self.assertEqual(0, result['screenshotCount'])
        self.assertTrue(all(lane['captureCounts'] == {'blocked': 2} for lane in result['apis']))
        size, count = 0, 0
        for lane in result['apis']:
            for file in lane['files']:
                data = (self.root / self.output / ('api-' + lane['matrixLabel']) / file['path']).read_bytes()
                self.assertEqual(identity(data), {key: file[key] for key in ('bytes', 'sha256')})
                size += len(data); count += 1
        self.assertEqual((size, count), (result['totalBytes'], result['fileCount']))
        self.assertIs(False, result['scope']['releaseAccepted'])

    def test_successful_lane_cannot_turn_failed_source_run_into_acceptance(self):
        self.lane(); run = source_run(); run['conclusion'] = 'failure'
        result = self.run_archive(run=run, index={'artifacts': [artifact()]})
        self.assertEqual({'passed': 1, 'failed': 0, 'unverified': 7}, result['stateCounts'])
        self.assertFalse(result['allUpgradeCasesPassed'])

    def test_unattempted_and_failed_partial_reports_preserve_the_eight_case_denominator(self):
        for label, status in (('30', 'unverified'), ('31', 'failed')):
            report = probe.empty_report(archive.verify_source(source_run()), label)
            if status == 'failed':
                report.update(status=status, executionAttempted=True)
                report['phases'][0].update(status='failed', reason='BUNDLE_FILESET_INVALID')
            self.lane(label, {'report.json': encoded(report)})
        result = self.run_archive(index={'artifacts': [artifact('30'), artifact('31')]})
        self.assertEqual({'passed': 0, 'failed': 1, 'unverified': 7}, result['stateCounts'])
        self.assertFalse(result['apis'][0]['executionAttempted'])
        self.assertEqual('unverified', result['apis'][0]['phases'][0]['status'])

    def test_rehashed_receipt_different_source_or_skipped_assertions_cannot_pass(self):
        directory = self.lane(); original = json.loads((directory / 'verify-receipt.json').read_bytes())
        for key, value in (('candidateSource', 'b' * 40), ('runAttempt', '1'), ('api', 35),
                           ('nonceSha256', '0' * 64), ('envelopeSha256', '0' * 64), ('workerDrained', False),
                           ('freshController', False), ('schemaVersion', True)):
            modified = dict(original); modified[key] = value
            self.update_payload(directory, 'verify-receipt.json', modified)
            with self.subTest(field=key): self.assert_lane_rejected()

    def test_receipt_and_instrumentation_rehashed_together_still_require_continuity_assertions(self):
        directory = self.lane(); receipt = json.loads((directory / 'verify-receipt.json').read_bytes())
        receipt['noTaskHistory'] = False
        command_path = next((directory / 'commands').glob('*-verify-instrumentation.json'))
        command = json.loads(command_path.read_bytes()); stdout = base64.b64decode(command['stdoutBase64']).decode()
        stdout = '\n'.join('INSTRUMENTATION_STATUS: upgradeReceipt=' + json.dumps(receipt, separators=(',', ':'))
                           if line.startswith('INSTRUMENTATION_STATUS: upgradeReceipt=') else line for line in stdout.splitlines()) + '\n'
        command['stdoutBase64'] = base64.b64encode(stdout.encode()).decode()
        self.update_payload(directory, 'verify-receipt.json', receipt)
        self.update_payload(directory, command_path.relative_to(directory).as_posix(), command)
        self.assert_lane_rejected()

    def test_rehashed_preflight_cannot_change_version_certificate_or_offline_scope(self):
        directory = self.lane(); original = json.loads((directory / 'preflight.json').read_bytes())
        for mutation in (lambda v: v['candidate'].update(versionCode=1),
                         lambda v: v['baseline'].update(versionCode=True),
                         lambda v: v.update(trustedCertificateSha256='0' * 64),
                         lambda v: v.update(configurationMigrationEvaluated=True)):
            value = copy.deepcopy(original); mutation(value)
            self.update_payload(directory, 'preflight.json', value, bundle=True)
            self.assert_lane_rejected()

    def test_each_lane_cannot_use_a_different_valid_bundle(self):
        self.lane('30'); self.lane('31', passing_payload('31', certificate=b'a different synthetic parser certificate'))
        self.assert_lane_rejected(('30', '31'))

    def test_hash_mismatch_unreferenced_data_and_missing_phase_are_not_success(self):
        directory = self.lane(); path = directory / 'verify-receipt.json'; original = path.read_bytes()
        path.write_bytes(original + b' '); self.assert_lane_rejected(); path.write_bytes(original)
        report_path = directory / 'report.json'; report = json.loads(report_path.read_bytes())
        report['artifactFiles'] = [item for item in report['artifactFiles'] if item['path'] != 'verify-receipt.json']
        report_path.write_bytes(encoded(report)); self.assert_lane_rejected()
        path.unlink(); self.assert_lane_rejected()

    def test_passed_report_requires_one_successful_instrumentation_result_not_exit_zero(self):
        directory = self.lane(); path = next((directory / 'commands').glob('*-verify-instrumentation.json'))
        original = json.loads(path.read_bytes())
        for suffix in ('FAILURES!!!\n', 'INSTRUMENTATION_STATUS_CODE: -2\n',
                       'INSTRUMENTATION_STATUS_CODE: -3\n', 'INSTRUMENTATION_STATUS_CODE: -4\n'):
            value = dict(original)
            value['stdoutBase64'] = base64.b64encode(base64.b64decode(value['stdoutBase64']) + suffix.encode()).decode()
            self.update_payload(directory, path.relative_to(directory).as_posix(), value)
            self.assert_lane_rejected()
        value = dict(original); value['stdoutBase64'] = base64.b64encode(b'OK (0 tests)\nINSTRUMENTATION_CODE: -1\n').decode()
        self.update_payload(directory, path.relative_to(directory).as_posix(), value); self.assert_lane_rejected()

    def test_protected_policy_rejects_a_flag_clear_even_when_json_boolean_equals_zero(self):
        directory = self.lane(); path = directory / 'report.json'; report = json.loads(path.read_bytes())
        for value in (True, 0, 'false'):
            report['captures'][0]['secureFlagsCleared'] = value; path.write_bytes(encoded(report)); self.assert_lane_rejected()

    def test_reordered_replayed_or_invalid_encoded_commands_are_rejected(self):
        directory = self.lane(); path = next((directory / 'commands').glob('*-devices.json'))
        value = json.loads(path.read_bytes()); value['stdoutBase64'] = 'not base64!'
        self.update_payload(directory, path.relative_to(directory).as_posix(), value); self.assert_lane_rejected()
        value['stdoutBase64'] = ''; value['timeoutSeconds'] = True
        self.update_payload(directory, path.relative_to(directory).as_posix(), value); self.assert_lane_rejected()

    def test_service_failure_cleanup_without_model_reverse_remains_a_failed_case(self):
        payload = passing_payload(); report = json.loads(payload['report.json'])
        retained = {name: data for name, data in payload.items() if not name.startswith('commands/')
                    and name not in ('report.json', 'seed-receipt.json', 'verify-receipt.json')}
        stop = archive.MAIN_COMMANDS.index('accessibility-list-after') + 1
        labels = list(archive.MAIN_COMMANDS[:stop]) + list(archive.RESTORE_COMMANDS)
        original = {json.loads(data)['label']: json.loads(data) for name, data in payload.items() if name.startswith('commands/')}
        for index, label in enumerate(labels, 1):
            command = original[label]
            if label == 'accessibility-list-after': command['stdoutBase64'] = base64.b64encode(b'null').decode()
            retained[f'commands/{index:02d}-{label}.json'] = encoded(command)
        report.update(status='failed', installedCandidate=None, candidatePid=None, baselinePidBeforeReplacement=None,
                      candidateInstallAttempts=0, requestsAfterSeed=None, requestsAfterVerify=None, nonceSha256=None,
                      workflowOutcome='failure')
        after_failure = False
        for phase in report['phases']:
            if phase['name'] == 'service_setup':
                phase.update(status='failed', reason='SERVICE_SETTINGS_NOT_APPLIED'); after_failure = True
            elif after_failure and phase['name'] != 'cleanup': phase['status'] = 'unverified'
        report['artifactFiles'] = [dict(path=name, **identity(data)) for name, data in retained.items()]
        retained['report.json'] = encoded(report); self.lane(payload=retained)
        result = self.run_archive(index={'artifacts': [artifact()]})
        self.assertEqual({'passed': 0, 'failed': 1, 'unverified': 7}, result['stateCounts'])
        self.assertEqual('passed', result['apis'][0]['phases'][-1]['status'])

    def test_command_order_remains_required_after_names_and_hashes_are_recomputed(self):
        payload = passing_payload()
        first = payload.pop('commands/01-devices.json')
        second = payload.pop('commands/02-property-0.json')
        payload['commands/01-property-0.json'] = second
        payload['commands/02-devices.json'] = first
        report = json.loads(payload.pop('report.json'))
        report['artifactFiles'] = [dict(path=name, **identity(data)) for name, data in payload.items()]
        payload['report.json'] = encoded(report)
        self.lane(payload=payload); self.assert_lane_rejected()

    def test_rehashed_launch_and_installed_path_output_still_require_runtime_success(self):
        directory = self.lane()
        for label, outputs in (('baseline-launch', ('Status: timeout\n', '')),
                               ('baseline-path', ('package:/data/app/a/../base.apk\n',)),
                               ('candidate-path', ('package:/data/app/a/base.apk\npackage:/data/app/a/split.apk\n',))):
            path = next((directory / 'commands').glob('*-' + label + '.json'))
            original = json.loads(path.read_bytes())
            for output in outputs:
                value = dict(original); value['stdoutBase64'] = base64.b64encode(output.encode()).decode()
                self.update_payload(directory, path.relative_to(directory).as_posix(), value)
                with self.subTest(command=label): self.assert_lane_rejected()
            self.update_payload(directory, path.relative_to(directory).as_posix(), original)

    def test_partial_report_cannot_invent_passed_stages_or_hide_known_failure(self):
        report = probe.empty_report(archive.verify_source(source_run()), '30')
        directory = self.lane(payload={'report.json': encoded(report)})
        variants = []
        invented = copy.deepcopy(report); invented.update(status='failed', executionAttempted=True)
        for phase in invented['phases']:
            if phase['name'] in ('bundle_preflight', 'seed_baseline'): phase['status'] = 'passed'
            if phase['name'] == 'verify_candidate': phase.update(status='failed', reason='INSTRUMENTATION_FAILED')
        variants.append(invented)
        hidden = copy.deepcopy(report); hidden['executionAttempted'] = True
        hidden['phases'][0].update(status='failed', reason='BUNDLE_FILESET_INVALID'); variants.append(hidden)
        unattempted = copy.deepcopy(report); unattempted['phases'][0]['status'] = 'passed'; variants.append(unattempted)
        malformed = copy.deepcopy(report); malformed['phases'] = [None]; variants.append(malformed)
        for index, value in enumerate(variants):
            (directory / 'report.json').write_bytes(encoded(value))
            with self.subTest(variant=index): self.assert_lane_rejected()

    def test_manifest_already_collected_before_bundle_copy_failure_remains_archivable(self):
        original = passing_payload(); data = original['build-manifest.json']
        report = probe.empty_report(archive.verify_source(source_run()), '30')
        report.update(status='failed', executionAttempted=True, workflowOutcome='failure',
                      artifactFiles=[dict(path='build-manifest.json', **identity(data))])
        report['phases'][0].update(status='failed', reason='ENVIRONMENT_OR_IO_REJECTED')
        self.lane(payload={'report.json': encoded(report), 'build-manifest.json': data})
        result = self.run_archive(index={'artifacts': [artifact()]})
        self.assertEqual({'passed': 0, 'failed': 1, 'unverified': 7}, result['stateCounts'])
        self.assertEqual(2, result['fileCount'])
        self.assertFalse(result['allUpgradeCasesPassed'])

    def test_symlink_ancestor_cannot_redirect_inputs_or_publication(self):
        actual = self.root / 'actual'; actual.mkdir()
        alias = self.root / 'alias'; alias.symlink_to(actual, target_is_directory=True)
        (actual / 'incoming').mkdir()
        with self.assertRaises(ValueError):
            archive.archive(alias / 'incoming', self.output, source_run(), {'artifacts': []}, self.root)
        with self.assertRaises(ValueError):
            archive.archive(self.incoming, self.output, source_run(), {'artifacts': []}, alias)
        self.assertFalse((actual / self.output).exists())

    def test_missing_artifacts_keep_eight_unverified_cases_and_create_no_fake_captures(self):
        result = self.run_archive()
        self.assertEqual({"passed": 0, "failed": 0, "unverified": 8}, result["stateCounts"])
        self.assertEqual(8, result["expectedCombinationCount"])
        self.assertEqual(list(archive.LABELS), [lane["matrixLabel"] for lane in result["apis"]])
        self.assertEqual((0, 0, 0), (result["fileCount"], result["totalBytes"], result["screenshotCount"]))
        self.assertFalse(result["allUpgradeCasesPassed"])
        self.assertTrue(all(lane["captureCounts"] == {} and lane["files"] == [] for lane in result["apis"]))
        self.assertTrue((self.root / self.output / "README.md").is_file())

    def test_source_rejects_archive_mode_fork_wrong_workflow_and_unfinished_runs(self):
        changes = [("display_title", "APK upgrade smoke (archive)"), ("event", "push"),
                   ("head_branch", "branch"), ("path", ".github/workflows/android.yml"),
                   ("status", "in_progress"), ("id", True), ("run_attempt", True),
                   ("head_sha", "A" * 40), ("conclusion", "unknown"),
                   ("run_started_at", "2026-10-28T09:11:00Z"), ("updated_at", "2026-10-28T09:10:00")]
        for key, value in changes:
            with self.subTest(field=key):
                invalid = source_run(); invalid[key] = value
                with self.assertRaises(ValueError): archive.verify_source(invalid)
        for key, value in (("id", 43), ("id", True), ("full_name", "fork/Agent-OS")):
            invalid = source_run(); invalid["head_repository"][key] = value
            with self.assertRaises(ValueError): archive.verify_source(invalid)

    def test_failed_source_is_archivable_without_changing_its_conclusion(self):
        run = source_run(); run["conclusion"] = "failure"
        result = self.run_archive(run=run)
        self.assertEqual("failure", result["sourceRun"]["conclusion"])
        self.assertFalse(result["allUpgradeCasesPassed"])

    def test_old_attempt_and_binary_bundle_artifacts_cannot_fill_current_lanes(self):
        old = artifact(attempt=1)
        bundle = dict(artifact(), name="apk-upgrade-smoke-bundle")
        result = self.run_archive(index={"artifacts": [old, bundle]})
        self.assertEqual(8, result["stateCounts"]["unverified"])

    def test_expired_artifact_is_retained_without_reading_its_payload(self):
        item = artifact(); item["expired"] = True
        result = self.run_archive(index={"artifacts": [item]})
        self.assertEqual("current_attempt_artifact_expired", result["apis"][0]["reason"])
        self.assertEqual(item["id"], result["apis"][0]["artifactId"])
        self.assertEqual(0, result["fileCount"])

    def test_artifact_lane_and_identity_cannot_be_reused(self):
        for items in ([artifact(), artifact()], [artifact(), dict(artifact("31"), id=130)],
                      [dict(artifact(), name="apk-upgrade-smoke-api-37-attempt-2")]):
            with self.subTest(items=items), self.assertRaises(ValueError):
                archive.eligible_artifacts({"artifacts": items}, source_run())

    def test_artifact_provenance_timestamp_digest_and_numeric_types_are_strict(self):
        changes = [("id", True), ("size_in_bytes", True), ("size_in_bytes", archive.MAX_TOTAL_BYTES + 1),
                   ("expired", "false"), ("digest", "sha256:" + "A" * 64),
                   ("created_at", "2026-10-28T08:59:59Z"), ("created_at", "2026-10-28T09:10:01Z")]
        for key, value in changes:
            invalid = artifact(); invalid[key] = value
            with self.subTest(field=key), self.assertRaises(ValueError):
                archive.eligible_artifacts({"artifacts": [invalid]}, source_run())
        for key, value in (("id", 124), ("id", True), ("head_sha", "b" * 40),
                           ("head_branch", "other"), ("repository_id", 43), ("head_repository_id", True)):
            invalid = artifact(); invalid["workflow_run"][key] = value
            with self.subTest(origin=key), self.assertRaises(ValueError):
                archive.eligible_artifacts({"artifacts": [invalid]}, source_run())

    def test_verified_but_undownloaded_artifact_is_not_silently_dropped(self):
        with self.assertRaises(ValueError): self.run_archive(index={"artifacts": [artifact()]})
        self.assertFalse((self.root / self.output).exists())

    def test_json_duplicate_nonfinite_invalid_encoding_and_depth_are_rejected(self):
        inputs = [b'{"status":"passed","status":"failed"}', b'{"x":NaN}', b'{"x":Infinity}',
                  b'{"x":"\xff"}', '\ufeff{}'.encode(), '{}'.encode('utf-16'), b'[] trailing',
                  b'[' * 34 + b'0' + b']' * 34]
        for data in inputs:
            with self.subTest(data=data[:40]), self.assertRaises(ValueError): archive.json_data(data)

    def test_exact_file_allowlist_rejects_png_apk_unknown_json_and_nested_paths(self):
        directory = self.incoming / artifact()["name"]; directory.mkdir()
        (directory / "report.json").write_text('{}')
        for name in ("screen.png", "screen.PNG", "candidate.apk", "extra.json", "../outside.json"):
            if name.startswith('..'):
                with self.assertRaises(ValueError): archive.evidence.relative_path(name)
                continue
            path = directory / name; path.write_bytes(b'{}')
            with self.subTest(name=name), self.assertRaises(ValueError):
                archive.scan_files(directory, {"files": 0, "bytes": 0})
            path.unlink()
        (directory / "unexpected").mkdir()
        with self.assertRaises(ValueError): archive.scan_files(directory, {"files": 0, "bytes": 0})

    def test_final_and_directory_symlinks_and_nonregular_files_are_rejected(self):
        directory = self.incoming / artifact()["name"]; directory.mkdir()
        outside = self.root / "outside.json"; outside.write_text('{}')
        (directory / "report.json").symlink_to(outside)
        with self.assertRaises(ValueError): archive.scan_files(directory, {"files": 0, "bytes": 0})
        (directory / "report.json").unlink()
        (directory / "commands").symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError): archive.scan_files(directory, {"files": 0, "bytes": 0})
        (directory / "commands").unlink()
        os.mkfifo(directory / "report.json")
        with self.assertRaises(ValueError): archive.scan_files(directory, {"files": 0, "bytes": 0})

    def test_byte_file_and_entry_budgets_reject_before_archive_publication(self):
        directory = self.incoming / artifact()["name"]; directory.mkdir()
        (directory / "report.json").write_text('{"bounded":true}')
        for key, value in (("MAX_FILE_BYTES", 5), ("MAX_TOTAL_BYTES", 5), ("MAX_FILES", 0), ("MAX_ENTRIES", 0)):
            with self.subTest(bound=key), patch.object(archive, key, value), self.assertRaises(ValueError):
                archive.scan_files(directory, {"files": 0, "bytes": 0})
        self.assertFalse((self.root / self.output).exists())

    def test_existing_destination_and_redirected_output_are_never_overwritten(self):
        for output in ("screenshots/apk-upgrade-124-2", "../archive", "/tmp/upgrade"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                archive.archive(self.incoming, output, source_run(), {"artifacts": []}, self.root)
        destination = self.root / self.output; destination.mkdir(parents=True)
        sentinel = destination / "sentinel"; sentinel.write_text('preserve')
        with self.assertRaises(ValueError): self.run_archive()
        self.assertEqual('preserve', sentinel.read_text())

    def test_source_cli_outputs_only_exact_current_artifact_ids(self):
        runfile, indexfile, output = (self.root / name for name in ('run.json', 'index.json', 'github-output'))
        runfile.write_text(json.dumps(source_run()))
        expired = artifact('31'); expired['expired'] = True
        indexfile.write_text(json.dumps({'artifacts': [artifact('37.0'), expired, artifact(attempt=1), artifact()]}))
        with patch('sys.stdout', new_callable=io.StringIO):
            code = archive.main(['validate-source', '--source-run-json', str(runfile), '--artifact-index', str(indexfile),
                                 '--github-output', str(output)])
        self.assertEqual(0, code)
        lines = dict(line.split('=', 1) for line in output.read_text().splitlines())
        self.assertEqual('130,137', lines['artifactIds'])
        self.assertEqual('a' * 40, lines['commit'])
        self.assertEqual(self.output, lines['evidencePath'])


if __name__ == '__main__':
    unittest.main()
