"""Keyboard archive provenance/path policy, isolated from Android result validation.

Policy tests explicitly stub the host validator and source-file verifier. Integration
tests use the actual validators and exact source bytes. All execution fixtures remain
synthetic and are not evidence of Android keyboard behavior.
"""
import base64
import copy
import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import window_infos_raw as raw
import window_infos_keyboard_archive as archive

POLICY_FILES = {'report.json', 'commands.json', 'journal.json', 'input-devices.json',
                'avd-config.ini', 'fixture.apk'} | {
    arm + '/' + name for arm in ('baseline', 'enabled') for name in raw.FILES}


def policy_file_limit(name):
    """State the payload-size contract independently of the host validator."""
    if Path(name).name in {'screenshot.png', 'avd-config.ini'}:
        return 1024 * 1024
    if Path(name).name in {'probe.jar', 'fixture.apk'}:
        return 2 * 1024 * 1024
    return 4 * 1024 * 1024


def source_run():
    return {'id': 123, 'run_attempt': 2, 'repository': {'id': 42, 'full_name': archive.REPOSITORY},
            'head_repository': {'id': 42, 'full_name': archive.REPOSITORY},
            'event': 'workflow_dispatch', 'head_branch': 'master', 'head_sha': 'a' * 40,
            'status': 'completed', 'conclusion': 'success', 'path': archive.WORKFLOW,
            'run_started_at': '2026-11-01T10:00:00Z', 'updated_at': '2026-11-01T10:10:00Z'}


def source_jobs():
    base = {'run_id': 123, 'run_attempt': 2, 'head_sha': 'a' * 40, 'status': 'completed'}
    return {'jobs': [
        dict(base, id=91, name='build', conclusion='success',
             started_at='2026-11-01T10:00:30Z', completed_at='2026-11-01T10:01:00Z'),
        dict(base, id=92, name='keyboard', conclusion='success',
             started_at='2026-11-01T10:01:00Z', completed_at='2026-11-01T10:08:00Z'),
        # Inverted timestamps are an observed GitHub representation of skipped jobs.
        *[dict(base, id=number, name=name, conclusion='skipped',
               started_at='2026-11-01T10:00:02Z', completed_at='2026-11-01T10:00:01Z')
          for number, name in [(93, 'probe'), (94, 'archive'), (95, 'archive_keyboard')]]]}


def artifact():
    return {'id': 456, 'name': archive.ARTIFACT_PREFIX + '2', 'expired': False, 'size_in_bytes': 1024,
            'created_at': '2026-11-01T10:06:00Z', 'updated_at': '2026-11-01T10:06:01Z',
            'workflow_run': {'id': 123, 'head_sha': 'a' * 40, 'head_branch': 'master',
                             'repository_id': 42, 'head_repository_id': 42}}


class KeyboardArchivePolicyTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='keyboard-archive-policy-')
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.incoming = self.repo / 'incoming'
        self.incoming.mkdir()
        self.source = archive.verify_source(source_run())
        self.output = archive.evidence_path(self.source)
        files = patch.object(archive.probe, 'FILES', POLICY_FILES)
        self.addCleanup(files.stop); files.start()
        limits = patch.object(archive.probe, 'file_limit', side_effect=policy_file_limit, create=True)
        self.addCleanup(limits.stop); limits.start()
        validator = patch.object(archive.probe, 'validate_evidence', side_effect=self.accept_policy_payload)
        self.addCleanup(validator.stop); self.validator = validator.start()
        verifier = patch.object(archive.probe, 'verify_source_files', return_value=True)
        self.addCleanup(verifier.stop); self.verifier = verifier.start()

    def accept_policy_payload(self, values, source):
        """Only an archive-layer test double; does not certify a real host receipt."""
        self.assertEqual(values['report.json']['source'], source)
        return copy.deepcopy(values['report.json'])

    def policy_payload(self, status='unverified'):
        arm = {'status': 'unverified', 'failureReason': None, 'positiveCallbackCount': 0,
               'imeStateStatus': 'not_attempted', 'comparable': False}
        report = {'schemaVersion': 1, 'kind': 'synthetic-archive-policy-fixture',
                  'source': copy.deepcopy(self.source), 'status': status,
                  'createdAt': '2026-11-01T10:02:00Z', 'completedAt': None,
                  'sourceFiles': {}, 'arms': {'baseline': copy.deepcopy(arm), 'enabled': copy.deepcopy(arm)},
                  'restoration': {'status': 'not_needed', 'mutationAttempted': False,
                                  'restoreAttempted': False}}
        return {'report.json': report, 'commands.json': {'synthetic': True},
                'journal.json': {'synthetic': True}}

    def write_payload(self, values=None):
        values = self.policy_payload() if values is None else values
        directory = self.incoming / artifact()['name']
        directory.mkdir()
        for name, value in values.items():
            path = directory / name; path.parent.mkdir(parents=True, exist_ok=True)
            data = value if isinstance(value, bytes) else (json.dumps(value, indent=2) + '\n').encode()
            path.write_bytes(data)
        return directory

    def run_archive(self, *, index=None, run=None, jobs=None, output=None):
        return archive.archive(self.incoming, self.output if output is None else output,
                               source_run() if run is None else run,
                               source_jobs() if jobs is None else jobs,
                               {'artifacts': [artifact()]} if index is None else index, self.repo)

    def rejected(self, **kwargs):
        with self.assertRaises((ValueError, OSError)):
            self.run_archive(**kwargs)
        self.assertFalse((self.repo / self.output).exists())

    def test_fixed_identity_path_and_workflow_contract(self):
        self.assertEqual(archive.WORKFLOW, '.github/workflows/window-infos-raw.yml')
        self.assertEqual(archive.JOB, 'keyboard')
        self.assertEqual(archive.ARTIFACT_PREFIX, 'window-infos-keyboard-api-37.0-attempt-')
        self.assertEqual(self.output, 'screenshots/window-infos-keyboard-123-2')
        self.assertEqual(archive.source_job(source_jobs(), source_run())['name'], 'keyboard')

    def test_foreign_branch_event_workflow_identity_and_incomplete_run_rejected(self):
        for key, value in [('event', 'pull_request'), ('head_branch', 'feature'), ('status', 'in_progress'),
                           ('path', '.github/workflows/demo-video.yml'), ('head_sha', '../escape'),
                           ('run_attempt', True), ('id', '123'), ('conclusion', None)]:
            with self.subTest(key=key):
                run = source_run(); run[key] = value
                with self.assertRaises(ValueError): archive.verify_source(run)
        for key, value in [('id', 43), ('id', True), ('full_name', 'other/fork')]:
            run = source_run(); run['head_repository'][key] = value
            with self.assertRaises(ValueError): archive.verify_source(run)

    def test_run_timestamps_require_timezone_and_chronology(self):
        for value in ['2026-11-01T09:00:00Z', '2026-11-01T10:10:00', 'invalid']:
            run = source_run(); run['updated_at'] = value
            with self.assertRaises(ValueError): archive.verify_source(run)

    def test_test_job_identity_attempt_time_and_duplicates_rejected(self):
        for job_index in (0, 1):
            for key, value in [('run_id', 124), ('run_attempt', True), ('run_attempt', 1),
                               ('head_sha', 'b' * 40), ('status', 'in_progress'), ('conclusion', None),
                               ('started_at', '2026-11-01T09:00:00Z'),
                               ('completed_at', '2026-11-01T10:11:00Z')]:
                with self.subTest(job=job_index, key=key):
                    jobs = source_jobs(); jobs['jobs'][job_index][key] = value
                    with self.assertRaises(ValueError): archive.source_job(jobs, source_run())
            jobs = source_jobs(); jobs['jobs'].append(copy.deepcopy(jobs['jobs'][job_index]))
            with self.assertRaises(ValueError): archive.source_job(jobs, source_run())

    def test_raw_or_archive_execution_cannot_be_keyboard_source(self):
        for name in ('probe', 'archive', 'archive_keyboard'):
            for conclusion in ('success', 'failure', 'cancelled'):
                with self.subTest(name=name, conclusion=conclusion):
                    jobs = source_jobs()
                    next(job for job in jobs['jobs'] if job['name'] == name)['conclusion'] = conclusion
                    with self.assertRaises(ValueError): archive.source_job(jobs, source_run())
            jobs = source_jobs()
            jobs['jobs'].append(copy.deepcopy(next(j for j in jobs['jobs'] if j['name'] == name)))
            with self.assertRaises(ValueError): archive.source_job(jobs, source_run())

    def test_successful_build_and_run_cannot_omit_or_skip_keyboard(self):
        for missing in (False, True):
            jobs = source_jobs()
            if missing: jobs['jobs'].pop(1)
            else: jobs['jobs'][1]['conclusion'] = 'skipped'
            with self.assertRaises(ValueError): archive.source_job(jobs, source_run())
        with self.assertRaises(ValueError): archive.source_job({'jobs': []}, source_run())

    def test_failed_prerequisite_keeps_absent_or_skipped_keyboard_unverified(self):
        for missing in (False, True):
            with self.subTest(missing=missing):
                jobs = source_jobs(); jobs['jobs'][0]['conclusion'] = 'failure'
                if missing: jobs['jobs'].pop(1)
                else:
                    jobs['jobs'][1]['conclusion'] = 'skipped'
                    jobs['jobs'][1]['completed_at'] = '2026-11-01T10:00:59Z'
                run = source_run(); run['conclusion'] = 'failure'
                result = self.run_archive(index={'artifacts': []}, run=run, jobs=jobs)
                self.assertEqual(result['status'], 'unverified')
                self.assertEqual(result['summary']['passedComparisons'], 0)
                self.assertEqual(result['summary']['unverifiedArms'], 2)
                shutil.rmtree(self.repo / self.output)

    def test_cancelled_run_with_successful_build_preserves_missing_keyboard(self):
        run = source_run(); run['conclusion'] = 'cancelled'
        jobs = source_jobs(); jobs['jobs'].pop(1)
        result = self.run_archive(index={'artifacts': []}, run=run, jobs=jobs)
        self.assertEqual(result['sourceConclusion'], 'cancelled')
        self.assertEqual(result['status'], 'unverified')
        self.assertEqual(result['summary']['unverifiedArms'], 2)

    def test_old_artifact_attempt_ignored_and_duplicate_current_rejected(self):
        old = artifact(); old['name'] = archive.ARTIFACT_PREFIX + '1'
        self.assertEqual(archive.eligible_artifact({'artifacts': [old, artifact()]}, source_run(), source_jobs()), artifact())
        with self.assertRaises(ValueError):
            archive.eligible_artifact([{'artifacts': [artifact()]}, {'artifacts': [artifact()]}], source_run(), source_jobs())

    def test_artifact_identity_size_expiry_and_job_time_rejected(self):
        for key, value in [('id', False), ('expired', 'false'), ('size_in_bytes', archive.MAX_ARTIFACT_BYTES + 1),
                           ('created_at', '2026-11-01T10:00:50Z'), ('created_at', '2026-11-01T10:09:00Z'),
                           ('updated_at', '2026-11-01T10:09:00Z')]:
            with self.subTest(key=key, value=value):
                item = artifact(); item[key] = value
                with self.assertRaises(ValueError): archive.eligible_artifact({'artifacts': [item]}, source_run(), source_jobs())
        for key, value in [('id', 124), ('head_sha', 'b' * 40), ('head_branch', 'feature'),
                           ('repository_id', 43), ('head_repository_id', True)]:
            item = artifact(); item['workflow_run'][key] = value
            with self.assertRaises(ValueError): archive.eligible_artifact({'artifacts': [item]}, source_run(), source_jobs())

    def test_artifact_requires_an_executed_current_keyboard_job(self):
        for missing in (False, True):
            jobs = source_jobs(); jobs['jobs'][0]['conclusion'] = 'failure'
            if missing: jobs['jobs'].pop(1)
            else: jobs['jobs'][1]['conclusion'] = 'skipped'
            run = source_run(); run['conclusion'] = 'failure'
            with self.assertRaises(ValueError): archive.eligible_artifact({'artifacts': [artifact()]}, run, jobs)

    def test_missing_and_expired_artifacts_keep_two_unverified_arms(self):
        for expired in (False, True):
            with self.subTest(expired=expired):
                item = artifact(); item['expired'] = True
                result = self.run_archive(index={'artifacts': [item] if expired else []})
                self.assertEqual(result['status'], 'unverified')
                self.assertEqual(result['summary'], {'expectedComparisons': 1, 'passedComparisons': 0,
                                                     'expectedArms': 2, 'passedArms': 0,
                                                     'failedArms': 0, 'unverifiedArms': 2, 'apiLevel': 37})
                self.assertEqual(result['artifact']['status'], 'expired' if expired else 'missing')
                self.assertEqual(result['capture']['recordedArmCount'], 0)
                self.assertFalse(result['scope']['applicationCompatibilityAcceptance'])
                self.assertEqual(result['restoration']['status'], 'not_observed')
                shutil.rmtree(self.repo / self.output)

    def test_policy_payload_bytes_revalidated_hashed_and_preserved(self):
        values = self.policy_payload(); directory = self.write_payload(values)
        originals = {p.relative_to(directory).as_posix(): p.read_bytes() for p in directory.rglob('*') if p.is_file()}
        result = self.run_archive()
        self.validator.assert_called_once()
        self.verifier.assert_called_once_with(values['report.json'], self.repo.resolve())
        self.assertEqual(result['status'], 'unverified')
        self.assertEqual(len(result['files']), len(originals) + 3)
        for item in result['files']:
            data = (self.repo / self.output / item['path']).read_bytes()
            self.assertEqual(item['bytes'], len(data))
            self.assertEqual(item['sha256'], hashlib.sha256(data).hexdigest())
            if item['path'] in originals: self.assertEqual(data, originals[item['path']])

    def test_nested_arm_bytes_remain_separate_and_capture_paths_are_retained(self):
        values = self.policy_payload()
        values['fixture.apk'] = b'Synthetic policy bytes, not an installable APK.'
        values['avd-config.ini'] = b'hw.keyboard=yes\n'
        for arm in ('baseline', 'enabled'):
            values[arm + '/report.json'] = {'syntheticArm': arm, 'status': 'failed' if arm == 'baseline' else 'passed'}
            values[arm + '/screenshot.png'] = b'Synthetic policy bytes, not a PNG: ' + arm.encode()
            values[arm + '/probe.jar'] = b'Synthetic policy bytes, not a dex jar: ' + arm.encode()
        directory = self.write_payload(values)
        originals = {p.relative_to(directory).as_posix(): p.read_bytes() for p in directory.rglob('*') if p.is_file()}
        result = self.run_archive()
        self.assertEqual(result['capture']['recordedArmCount'], 2)
        self.assertEqual(set(result['capture']['screenshots']), {'baseline/screenshot.png', 'enabled/screenshot.png'})
        for name, data in originals.items(): self.assertEqual((self.repo / self.output / name).read_bytes(), data)
        self.assertNotEqual((self.repo / self.output / 'baseline/report.json').read_bytes(),
                            (self.repo / self.output / 'enabled/report.json').read_bytes())

    def test_comparison_pass_preserves_a_failed_baseline_and_restoration_summary(self):
        values = self.policy_payload(status='passed')
        report = values['report.json']
        report['arms']['baseline'].update(status='failed',
                                         failureReason='RAW_POSITIVE_CALLBACK_NOT_OBSERVED',
                                         imeStateStatus='recorded', comparable=True)
        report['arms']['enabled'].update(status='passed', positiveCallbackCount=3,
                                        imeStateStatus='recorded', comparable=True)
        report['restoration'].update(status='restored', mutationAttempted=True, restoreAttempted=True)
        self.write_payload(values)
        result = self.run_archive()
        self.assertEqual(result['status'], 'passed')
        self.assertEqual(result['summary']['passedComparisons'], 1)
        self.assertEqual(result['summary']['passedArms'], 1)
        self.assertEqual(result['summary']['failedArms'], 1)
        for arm in ('baseline', 'enabled'):
            self.assertEqual(result['arms'][arm], dict(report['arms'][arm], reported=True))
        self.assertEqual(result['restoration'], report['restoration'])
        self.assertFalse(result['scope']['baselineResultPromotedByComparison'])
        self.assertFalse(result['scope']['originalNotesCasesRerun'])

    def test_host_or_source_verification_rejection_never_creates_archive(self):
        self.write_payload()
        self.validator.side_effect = ValueError('synthetic host contract refusal')
        self.rejected(); self.verifier.assert_not_called()
        self.validator.side_effect = self.accept_policy_payload
        self.verifier.side_effect = ValueError('synthetic source hash mismatch')
        self.rejected()

    def test_failed_source_or_keyboard_job_cannot_promote_a_stubbed_parent_pass(self):
        self.write_payload(self.policy_payload(status='passed'))
        for where in ('run', 'job'):
            run = source_run(); jobs = source_jobs()
            if where == 'run': run['conclusion'] = 'failure'
            else: jobs['jobs'][1]['conclusion'] = 'failure'
            result = self.run_archive(run=run, jobs=jobs)
            self.assertEqual(result['diagnosticReportStatus'], 'passed')
            self.assertEqual(result['status'], 'failed')
            self.assertEqual(result['summary']['passedComparisons'], 0)
            shutil.rmtree(self.repo / self.output)

    def test_report_times_must_be_inside_keyboard_job_and_precede_upload(self):
        for key, value in [('createdAt', '2026-11-01T10:00:59Z'), ('completedAt', '2026-11-01T10:07:00Z')]:
            values = self.policy_payload(); values['report.json'][key] = value
            directory = self.write_payload(values); self.rejected(); shutil.rmtree(directory)

    def test_unknown_nested_root_and_deep_paths_rejected_before_host(self):
        directory = self.write_payload()
        for name in ('extra.json', 'baseline/extra.json', 'enabled/nested/report.json',
                     'unexpected/report.json', 'baseline/input-devices.json'):
            with self.subTest(path=name):
                target = directory / name; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('{}'); self.rejected(); self.validator.assert_not_called()
                target.unlink()
                for parent in list(target.parents):
                    if parent == directory: break
                    if parent.exists() and not list(parent.iterdir()): parent.rmdir()
        extra = self.incoming / 'foreign-artifact'; extra.mkdir(); self.rejected()

    def test_linked_root_arm_nested_file_and_output_parent_rejected(self):
        directory = self.write_payload()
        target = self.repo / 'external.json'; target.write_text('{}')
        report = directory / 'report.json'; original = report.read_bytes()
        report.unlink(); report.symlink_to(target); self.rejected()
        report.unlink(); report.write_bytes(original)
        arm = directory / 'baseline'; arm.symlink_to(self.repo, target_is_directory=True); self.rejected(); arm.unlink()
        arm.mkdir(); nested = arm / 'report.json'; nested.symlink_to(target); self.rejected(); nested.unlink(); arm.rmdir()
        parent = self.repo / 'screenshots'; parent.symlink_to(self.incoming, target_is_directory=True); self.rejected()

    def test_nonregular_payload_is_rejected_without_blocking(self):
        import os
        directory = self.write_payload()
        fifo = directory / 'input-devices.json'; os.mkfifo(fifo)
        self.rejected(); self.validator.assert_not_called()

    def test_incoming_and_artifact_directory_links_are_rejected(self):
        directory = self.write_payload()
        moved = self.repo / 'moved-artifact'
        directory.rename(moved)
        directory.symlink_to(moved, target_is_directory=True)
        self.rejected(); self.validator.assert_not_called()
        directory.unlink(); moved.rename(directory)
        moved_incoming = self.repo / 'moved-incoming'
        self.incoming.rename(moved_incoming)
        self.incoming.symlink_to(moved_incoming, target_is_directory=True)
        self.rejected(); self.validator.assert_not_called()

    def test_malformed_duplicate_nonfinite_and_deep_json_rejected_before_host(self):
        directory = self.write_payload(); target = directory / 'journal.json'
        for data in [b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":1e999}', b'[' * 40 + b'0' + b']' * 40, b'not-json']:
            target.write_bytes(data); self.rejected(); self.validator.assert_not_called()

    def test_json_png_jar_apk_ini_and_total_payload_limits_precede_host_validation(self):
        directory = self.write_payload()
        for name, limit in [('baseline/report.json', archive.MAX_FILE_BYTES),
                            ('enabled/screenshot.png', 1048576), ('baseline/probe.jar', 2097152),
                            ('fixture.apk', 2097152), ('avd-config.ini', 1048576)]:
            target = directory / name; target.parent.mkdir(exist_ok=True)
            with target.open('wb') as stream: stream.truncate(limit + 1)
            self.rejected(); self.validator.assert_not_called(); target.unlink()
        with patch.object(archive, 'MAX_PAYLOAD_BYTES', 100): self.rejected()
        self.validator.assert_not_called()

    def test_each_raw_arm_keeps_its_original_total_bound(self):
        values = self.policy_payload()
        values['baseline/report.json'] = {'padding': 'x' * 70}
        values['baseline/commands.json'] = {'padding': 'y' * 70}
        directory = self.write_payload(values)
        self.assertLess(sum(path.stat().st_size for path in directory.rglob('*') if path.is_file()),
                        archive.MAX_PAYLOAD_BYTES)
        with patch.object(archive, 'MAX_ARM_BYTES', 100):
            self.rejected()
        self.validator.assert_not_called()

    def test_each_raw_arm_has_an_independent_total_budget(self):
        values = self.policy_payload()
        values['baseline/report.json'] = {'padding': 'x' * 60}
        values['enabled/report.json'] = {'padding': 'y' * 60}
        directory = self.write_payload(values)
        self.assertGreater(sum((directory / arm / 'report.json').stat().st_size
                               for arm in ('baseline', 'enabled')), 100)
        with patch.object(archive, 'MAX_ARM_BYTES', 100):
            result = self.run_archive()
        self.assertEqual(result['status'], 'unverified')

    def test_wrong_output_missing_download_flat_or_expired_payload_rejected(self):
        self.rejected(output='../escape'); self.rejected()
        directory = self.write_payload()
        item = artifact(); item['expired'] = True; self.rejected(index={'artifacts': [item]})
        report = directory / 'report.json'; report.rename(self.incoming / 'report.json')
        shutil.rmtree(directory); self.rejected()

    def test_output_is_never_overwritten(self):
        self.run_archive(index={'artifacts': []})
        manifest = self.repo / self.output / 'manifest.json'; original = manifest.read_bytes()
        with self.assertRaises(ValueError): self.run_archive(index={'artifacts': []})
        self.assertEqual(manifest.read_bytes(), original)

    def test_validate_source_cli_outputs_only_fixed_identity(self):
        for name, value in [('run.json', source_run()), ('jobs.json', source_jobs()),
                            ('artifacts.json', {'artifacts': [artifact()]})]:
            (self.repo / name).write_text(json.dumps(value))
        output = self.repo / 'github-output'
        with patch('builtins.print'):
            status = archive.main(['validate-source', '--source-run-json', str(self.repo / 'run.json'),
                                   '--source-jobs-json', str(self.repo / 'jobs.json'),
                                   '--artifact-index', str(self.repo / 'artifacts.json'),
                                   '--github-output', str(output)])
        self.assertEqual(status, 0)
        self.assertIn('commit=' + 'a' * 40 + '\n', output.read_text())
        self.assertIn('artifactIds=456\n', output.read_text())
        self.assertIn('evidencePath=' + self.output + '\n', output.read_text())


class KeyboardArchiveIntegrationTests(unittest.TestCase):
    """Use the actual host validators and byte hashes; execution fixtures remain synthetic."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='keyboard-archive-integration-')
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.source = archive.verify_source(source_run())
        self.output = archive.evidence_path(self.source)
        self.incoming = self.repo / 'incoming'
        self.incoming.mkdir()
        self.directory = self.incoming / artifact()['name']
        for name in archive.probe.source_files(Path(__file__).resolve().parents[1]):
            path = self.repo / name; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('Synthetic source bytes for archive validation: ' + name + '\n')
        self.identities = archive.probe.source_files(self.repo)
        self.raw_identities = raw.source_files(self.repo)

    def prepare_real_contract(self):
        with patch.object(archive.probe, 'source_files', return_value=self.identities), \
                patch.object(raw, 'source_files', return_value=self.raw_identities), \
                patch.object(archive.probe, 'now', return_value='2026-11-01T10:02:00Z'), \
                patch.object(raw, 'now', return_value='2026-11-01T10:02:00Z'):
            archive.probe.prepare(self.directory, self.source)

    def run_archive(self):
        return archive.archive(self.incoming, self.output, source_run(), source_jobs(),
                               {'artifacts': [artifact()]}, self.repo)

    def full_payload(self, **options):
        import test_window_infos_keyboard as producer
        with patch.object(producer, 'SOURCE', self.source), \
                patch.object(producer, 'IDENTITIES', self.identities), \
                patch.object(archive.probe, 'now', return_value='2026-11-01T10:02:00Z'), \
                patch.object(raw, 'now', return_value='2026-11-01T10:02:00Z'):
            return producer.passed_payloads(**options)

    def write_payload(self, values):
        self.directory.mkdir()
        for name, value in values.items():
            target = self.directory / name; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(value if isinstance(value, bytes) else (json.dumps(value, indent=2) + '\n').encode())

    def test_complete_comparison_preserves_negative_baseline_and_every_original_byte(self):
        values = self.full_payload(); self.write_payload(values)
        originals = {p.relative_to(self.directory).as_posix(): p.read_bytes()
                     for p in self.directory.rglob('*') if p.is_file()}
        self.assertEqual(set(originals), archive.probe.FILES)
        manifest = self.run_archive()
        self.assertEqual(manifest['status'], 'passed')
        self.assertEqual(manifest['summary'], {'expectedComparisons': 1, 'passedComparisons': 1,
                         'expectedArms': 2, 'passedArms': 1, 'failedArms': 1, 'unverifiedArms': 0, 'apiLevel': 37})
        self.assertEqual(manifest['arms']['baseline']['failureReason'], 'RAW_POSITIVE_CALLBACK_NOT_OBSERVED')
        self.assertEqual(manifest['arms']['baseline']['positiveCallbackCount'], 0)
        self.assertEqual(manifest['arms']['enabled']['positiveCallbackCount'], 1)
        self.assertEqual(manifest['restoration'], {'status': 'confirmed', 'mutationAttempted': True, 'restoreAttempted': True})
        self.assertEqual(values['journal.json']['original'], {'present': False, 'value': None})
        self.assertEqual(manifest['capture']['screenshots'], ['baseline/screenshot.png', 'enabled/screenshot.png'])
        self.assertEqual(len(manifest['files']), len(originals) + 3)
        for name, original in originals.items():
            self.assertEqual((self.repo / self.output / name).read_bytes(), original)
        for entry in manifest['files']:
            data = (self.repo / self.output / entry['path']).read_bytes()
            self.assertEqual(entry['bytes'], len(data)); self.assertEqual(entry['sha256'], hashlib.sha256(data).hexdigest())

    def test_original_zero_keeps_put_zero_restoration_evidence(self):
        values = self.full_payload(original='0'); self.write_payload(values)
        manifest = self.run_archive()
        self.assertEqual(manifest['status'], 'passed')
        self.assertEqual(values['journal.json']['original'], {'present': True, 'value': '0'})
        command = next(c for c in values['commands.json']['commands'] if c['id'] == 'restore_setting')
        self.assertEqual(command['argv'][-4:], ['put', 'secure', archive.probe.SETTING, '0'])
        self.assertEqual(manifest['restoration']['status'], 'confirmed')

    def test_enabled_failure_keeps_both_raw_failures_in_denominator(self):
        values = self.full_payload(enabled_positive=False); self.write_payload(values)
        manifest = self.run_archive()
        self.assertEqual(manifest['status'], 'failed')
        self.assertEqual(manifest['summary']['failedArms'], 2)
        self.assertEqual(manifest['summary']['passedComparisons'], 0)
        self.assertEqual(manifest['restoration']['status'], 'confirmed')
        self.assertTrue(all(a['failureReason'] == 'RAW_POSITIVE_CALLBACK_NOT_OBSERVED' for a in manifest['arms'].values()))

    def test_failed_restore_is_archived_even_when_enabled_raw_passed(self):
        values = self.full_payload(restore_wrong=True); self.write_payload(values)
        manifest = self.run_archive()
        self.assertEqual(manifest['status'], 'failed')
        self.assertEqual(manifest['summary']['passedComparisons'], 0)
        self.assertEqual(manifest['summary']['passedArms'], 1)
        self.assertEqual(manifest['summary']['failedArms'], 1)
        self.assertEqual(manifest['restoration']['status'], 'failed_or_unobserved')
        self.assertEqual(manifest['arms']['enabled']['status'], 'passed')
        self.assertEqual(values['report.json']['failureReason'], 'KEYBOARD_RESTORE_NOT_CONFIRMED')

    def test_unknown_input_diagnostic_is_retained_without_promoting_comparison(self):
        values = self.full_payload(input_unknown=True); self.write_payload(values)
        manifest = self.run_archive()
        self.assertEqual(manifest['status'], 'failed')
        self.assertEqual(values['input-devices.json']['status'], 'unavailable')
        self.assertEqual(manifest['arms']['enabled']['status'], 'passed')
        self.assertEqual(manifest['restoration']['status'], 'confirmed')

    def test_incomplete_baseline_preserves_unexecuted_enabled_arm(self):
        values = self.full_payload(baseline_incomplete=True); self.write_payload(values)
        manifest = self.run_archive()
        self.assertEqual(manifest['status'], 'failed')
        self.assertEqual(manifest['summary']['failedArms'], 1)
        self.assertEqual(manifest['summary']['unverifiedArms'], 1)
        self.assertEqual(manifest['arms']['enabled']['status'], 'unverified')
        self.assertEqual(manifest['restoration']['status'], 'not_needed')

    def test_complete_originals_cannot_be_replaced_or_rehashed_to_forge_acceptance(self):
        original = self.full_payload(); variants = []
        changed = copy.deepcopy(original); changed.pop('fixture.apk'); variants.append(changed)
        changed = copy.deepcopy(original); changed['fixture.apk'] += b'changed'; variants.append(changed)
        changed = copy.deepcopy(original)
        changed['avd-config.ini'] = changed['avd-config.ini'].replace(b'hw.keyboard=no', b'hw.keyboard=yes'); variants.append(changed)
        changed = copy.deepcopy(original)
        changed['journal.json']['original'] = {'present': True, 'value': '0'}; variants.append(changed)
        changed = copy.deepcopy(original)
        command = next(c for c in changed['commands.json']['commands'] if c['id'] == 'settings_restored')
        data = base64.b64decode(command['outputBase64']) + (archive.probe.SETTING + '=1\n').encode()
        command.update(outputBase64=base64.b64encode(data).decode(), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        variants.append(changed)
        changed = copy.deepcopy(original)
        command = next(c for c in changed['enabled/commands.json']['commands'] if c['id'] == 'raw_probe')
        data = base64.b64decode(command['outputBase64']).replace(b'a' * 40, b'b' * 40)
        command.update(outputBase64=base64.b64encode(data).decode(), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        variants.append(changed)
        changed = copy.deepcopy(original)
        changed['enabled/ime-state.json']['analysis']['fields']['mIsInputViewShown']['value'] = False
        variants.append(changed)
        for index, values in enumerate(variants):
            with self.subTest(variant=index):
                self.write_payload(values)
                with self.assertRaises(ValueError): self.run_archive()
                self.assertFalse((self.repo / self.output).exists())
                shutil.rmtree(self.directory)

    def test_real_prepared_tree_keeps_both_arms_unverified_and_original_bytes(self):
        self.prepare_real_contract()
        originals = {p.relative_to(self.directory).as_posix(): p.read_bytes()
                     for p in self.directory.rglob('*') if p.is_file()}
        self.assertEqual(len(originals), 9)
        manifest = self.run_archive()
        self.assertEqual(manifest['status'], 'unverified')
        self.assertEqual(manifest['summary']['unverifiedArms'], 2)
        self.assertEqual(manifest['summary']['passedComparisons'], 0)
        self.assertEqual(manifest['restoration']['status'], 'not_needed')
        self.assertEqual(len(manifest['files']), 12)
        for name, original in originals.items():
            self.assertEqual((self.repo / self.output / name).read_bytes(), original)
        for entry in manifest['files']:
            data = (self.repo / self.output / entry['path']).read_bytes()
            self.assertEqual(entry['bytes'], len(data))
            self.assertEqual(entry['sha256'], hashlib.sha256(data).hexdigest())

    def test_real_validator_rejects_changed_coordinator_source(self):
        self.prepare_real_contract()
        (self.repo / 'scripts/window_infos_keyboard.py').write_text('Source changed after preparation')
        with self.assertRaisesRegex(ValueError, 'KEYBOARD_SOURCE_FILES_MISMATCH'): self.run_archive()
        self.assertFalse((self.repo / self.output).exists())

    def test_real_validator_binds_each_arm_source_hashes_to_parent(self):
        self.prepare_real_contract()
        target = self.directory / 'enabled/report.json'
        report = json.loads(target.read_bytes())
        report['sourceFiles']['scripts/window_infos_raw.py']['sha256'] = 'b' * 64
        target.write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, 'KEYBOARD_ARM_SOURCE_FILES'): self.run_archive()
        self.assertFalse((self.repo / self.output).exists())


if __name__ == '__main__':
    unittest.main()
