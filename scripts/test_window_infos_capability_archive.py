"""Source/path/partial-evidence boundaries; fixtures are synthetic, nonvisual JSON."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import window_infos_capability as probe
import window_infos_capability_archive as archive
from test_window_infos_capability import recorded_payloads


def source_run():
    return {'id': 123, 'run_attempt': 2, 'repository': {'id': 42, 'full_name': archive.REPOSITORY},
            'head_repository': {'id': 42, 'full_name': archive.REPOSITORY}, 'event': 'workflow_dispatch',
            'head_branch': 'master', 'head_sha': 'a' * 40, 'status': 'completed', 'conclusion': 'success',
            'path': archive.WORKFLOW, 'run_started_at': '2026-10-31T10:00:00Z', 'updated_at': '2026-10-31T10:10:00Z'}


def artifact():
    return {'id': 456, 'name': archive.ARTIFACT_PREFIX + '2', 'expired': False, 'size_in_bytes': 1024,
            'created_at': '2026-10-31T10:05:00Z', 'updated_at': '2026-10-31T10:05:01Z',
            'workflow_run': {'id': 123, 'head_sha': 'a' * 40, 'head_branch': 'master',
                             'repository_id': 42, 'head_repository_id': 42}}


class CapabilityArchiveTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='capability-archive-')
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.incoming = self.repo / 'incoming'
        self.incoming.mkdir()
        for name in probe.SOURCE_PATHS:
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('Synthetic source fixture: ' + name + '\n')
        self.source = archive.verify_source(source_run())
        self.output = archive.evidence_path(self.source)

    def prepared(self):
        report = probe.empty_report(self.source, probe.source_files(self.repo))
        report['createdAt'] = '2026-10-31T10:01:00Z'
        return {'report.json': report, 'capture.json': probe.capture(self.source),
                'commands.json': {'schemaVersion': 1, 'kind': probe.KIND + '-commands',
                                  'source': copy.deepcopy(self.source), 'commands': []}}

    def write_payload(self, values=None):
        values = self.prepared() if values is None else values
        directory = self.incoming / artifact()['name']
        directory.mkdir()
        for name, value in values.items():
            (directory / name).write_text(json.dumps(value, ensure_ascii=True, indent=2) + '\n')
        return directory

    def run_archive(self, index=None, run=None, output=None):
        return archive.archive(self.incoming, output or self.output, run or source_run(),
                               {'artifacts': [artifact()]} if index is None else index, self.repo)

    def rejected(self, **kwargs):
        with self.assertRaises((ValueError, OSError)):
            self.run_archive(**kwargs)
        self.assertFalse((self.repo / self.output).exists())

    def test_completed_failure_is_valid_source_but_not_a_pass(self):
        run = source_run(); run['conclusion'] = 'failure'
        self.assertEqual(archive.verify_source(run), self.source)
        manifest = self.run_archive(run=run, index={'artifacts': []})
        self.assertEqual(manifest['sourceConclusion'], 'failure')
        self.assertEqual(manifest['summary']['status'], 'unverified')
        self.assertEqual(manifest['summary']['recordedTests'], 0)

    def test_wrong_event_branch_workflow_identity_and_incomplete_source_rejected(self):
        changes = [('event', 'pull_request'), ('head_branch', 'feature'), ('status', 'in_progress'),
                   ('path', '.github/workflows/third-party-smoke.yml'), ('head_sha', '../escape'),
                   ('run_attempt', True), ('id', '123'), ('conclusion', None)]
        for key, value in changes:
            with self.subTest(key=key):
                run = source_run(); run[key] = value
                with self.assertRaises(ValueError): archive.verify_source(run)
        for key, value in [('id', 43), ('full_name', 'other/fork')]:
            run = source_run(); run['head_repository'][key] = value
            with self.assertRaises(ValueError): archive.verify_source(run)

    def test_source_times_require_zone_and_order(self):
        for value in ['2026-10-31T09:00:00Z', '2026-10-31T10:10:00', 'bad-time']:
            run = source_run(); run['updated_at'] = value
            with self.assertRaises(ValueError): archive.verify_source(run)

    def test_old_attempt_ignored_and_duplicate_current_artifact_rejected(self):
        old = artifact(); old['name'] = archive.ARTIFACT_PREFIX + '1'
        self.assertEqual(archive.eligible_artifact({'artifacts': [old, artifact()]}, source_run()), artifact())
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            archive.eligible_artifact([{'artifacts': [artifact()]}, {'artifacts': [artifact()]}], source_run())

    def test_artifact_mismatched_origin_times_size_and_expiry_rejected(self):
        changes = [('id', False), ('expired', 'false'), ('size_in_bytes', archive.MAX_TOTAL_BYTES + 1),
                   ('created_at', '2026-10-31T09:00:00Z'), ('updated_at', '2026-10-31T10:11:00Z')]
        for key, value in changes:
            item = artifact(); item[key] = value
            with self.assertRaises(ValueError): archive.eligible_artifact({'artifacts': [item]}, source_run())
        for key, value in [('id', 124), ('head_sha', 'b' * 40), ('head_branch', 'feature'),
                           ('repository_id', 43), ('head_repository_id', True)]:
            item = artifact(); item['workflow_run'][key] = value
            with self.assertRaises(ValueError): archive.eligible_artifact({'artifacts': [item]}, source_run())

    def test_missing_artifact_keeps_one_unverified_case_and_nonvisual_manifest(self):
        result = self.run_archive(index={'artifacts': []})
        self.assertEqual(result['summary']['expectedTests'], 1)
        self.assertEqual(result['summary']['recordedTests'], 0)
        self.assertEqual(result['artifact']['status'], 'missing')
        self.assertEqual(result['capture'], {'status': 'nonvisual', 'screenshots': False, 'video': False})
        self.assertFalse(result['applicationCompatibilityAcceptance'])
        self.assertFalse((self.repo / self.output / 'payload').exists())

    def test_expired_artifact_is_preserved_as_unverified(self):
        item = artifact(); item['expired'] = True
        result = self.run_archive(index={'artifacts': [item]})
        self.assertEqual(result['artifact']['status'], 'expired')
        self.assertEqual(result['summary']['status'], 'unverified')

    def test_prepared_partial_payload_preserved_byte_for_byte_and_hashed(self):
        directory = self.write_payload()
        originals = {p.name: p.read_bytes() for p in directory.iterdir()}
        result = self.run_archive()
        self.assertEqual(result['summary']['status'], 'unverified')
        for item in result['files']:
            data = (self.repo / self.output / item['path']).read_bytes()
            self.assertEqual(item['bytes'], len(data))
            self.assertEqual(item['sha256'], hashlib.sha256(data).hexdigest())
            if item['path'].startswith('payload/'):
                self.assertEqual(data, originals[Path(item['path']).name])

    def test_complete_synthetic_driver_receipt_is_revalidated_and_archived(self):
        values = recorded_payloads(self.source, probe.source_files(self.repo),
                                   captured_at='2026-10-31T10:01:00+00:00')
        directory = self.write_payload(values)
        originals = {path.name: path.read_bytes() for path in directory.iterdir()}
        result = self.run_archive()
        self.assertEqual(result['summary']['status'], 'recorded')
        self.assertEqual(result['summary']['recordedTests'], 1)
        self.assertEqual(set(originals), archive.FILES)
        self.assertFalse(result['applicationCompatibilityAcceptance'])
        self.assertEqual(result['capture'], {'status': 'nonvisual', 'screenshots': False, 'video': False})
        for name, data in originals.items():
            self.assertEqual((self.repo / self.output / 'payload' / name).read_bytes(), data)

    def test_recorded_report_cannot_hide_changed_original_command_output(self):
        values = recorded_payloads(self.source, probe.source_files(self.repo),
                                   captured_at='2026-10-31T10:01:00+00:00')
        values['commands.json']['commands'][0]['outputBase64'] = 'Y29uZmxpY3Q='
        self.write_payload(values)
        self.rejected()

    def test_complete_receipt_does_not_erase_failed_source_conclusion(self):
        values = recorded_payloads(self.source, probe.source_files(self.repo),
                                   captured_at='2026-10-31T10:01:00+00:00')
        self.write_payload(values)
        run = source_run(); run['conclusion'] = 'failure'
        result = self.run_archive(run=run)
        self.assertEqual(result['summary']['status'], 'recorded')
        self.assertEqual(result['sourceConclusion'], 'failure')
        self.assertFalse(result['applicationCompatibilityAcceptance'])

    def test_report_only_partial_does_not_become_recorded(self):
        self.write_payload({'report.json': self.prepared()['report.json']})
        self.assertEqual(self.run_archive()['summary']['recordedTests'], 0)

    def test_failed_before_device_retains_failure(self):
        values = self.prepared()
        values['report.json'].update(status='failed', stage='device_preflight',
                                     failureReason='DEVICE_UNAVAILABLE', completedAt='2026-10-31T10:02:00Z')
        self.write_payload(values)
        result = self.run_archive()
        self.assertEqual(result['summary']['status'], 'failed')
        self.assertEqual(result['summary']['recordedTests'], 0)

    def test_partial_case_cannot_claim_recorded(self):
        values = self.prepared()
        values['report.json'].update(status='recorded', stage='completed', failureReason=None,
                                     instrumentationAttempted=True, completedAt='2026-10-31T10:02:00Z')
        self.write_payload(values)
        self.rejected()

    def test_payload_foreign_source_rejected(self):
        values = self.prepared(); values['report.json']['source']['commit'] = 'b' * 40
        self.write_payload(values); self.rejected()

    def test_source_file_digest_is_checked_against_exact_checkout(self):
        self.write_payload()
        (self.repo / probe.SOURCE_PATHS[0]).write_text('Changed source after report')
        self.rejected()

    def test_source_file_symlink_rejected(self):
        self.write_payload()
        path = self.repo / probe.SOURCE_PATHS[0]
        original = path.read_bytes(); path.unlink()
        target = self.repo / 'other-source'; target.write_bytes(original); path.symlink_to(target)
        self.rejected()

    def test_report_times_must_be_within_run_and_before_upload(self):
        for key, value in [('createdAt', '2026-10-31T09:59:59Z'), ('completedAt', '2026-10-31T10:06:00Z')]:
            with self.subTest(key=key):
                values = self.prepared(); values['report.json'][key] = value
                directory = self.write_payload(values)
                self.rejected()
                for item in directory.iterdir(): item.unlink()
                directory.rmdir()

    def test_unexpected_files_directories_and_flat_download_rejected(self):
        directory = self.write_payload()
        for name in ['image.png', 'instrumentation.txt', 'extra.json']:
            path = directory / name; path.write_text('{}')
            self.rejected(); path.unlink()
        path = directory / 'subdirectory'; path.mkdir(); self.rejected(); path.rmdir()
        extra = self.incoming / 'foreign-artifact'; extra.mkdir(); self.rejected()

    def test_linked_payload_and_linked_output_parent_rejected(self):
        directory = self.write_payload()
        original = directory / 'capture.json'; target = self.repo / 'capture-copy.json'
        target.write_bytes(original.read_bytes()); original.unlink(); original.symlink_to(target)
        self.rejected(); original.unlink(); original.write_bytes(target.read_bytes())
        parent = self.repo / 'docs/validation/diagnostics'
        parent.mkdir(parents=True, exist_ok=True); parent.rmdir(); parent.symlink_to(self.incoming)
        self.rejected()

    def test_malformed_duplicate_nonfinite_and_deep_json_rejected(self):
        directory = self.write_payload(); target = directory / 'capture.json'; old = target.read_bytes()
        for raw in [b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":1e999}', b'[' * 40 + b'0' + b']' * 40, b'not-json']:
            target.write_bytes(raw); self.rejected()
        target.write_bytes(old)

    def test_file_and_total_bounds_rejected_before_semantic_validation(self):
        directory = self.write_payload()
        with (directory / 'capture.json').open('wb') as stream:
            stream.truncate(archive.MAX_FILE_BYTES + 1)
        self.rejected()
        (directory / 'capture.json').write_text(json.dumps(probe.capture(self.source)))
        with patch.object(archive, 'MAX_TOTAL_BYTES', 1500): self.rejected()

    def test_wrong_output_and_missing_current_download_rejected(self):
        self.rejected(output='../escape')
        self.rejected()

    def test_output_is_never_overwritten(self):
        self.run_archive(index={'artifacts': []})
        manifest = self.repo / self.output / 'manifest.json'; before = manifest.read_bytes()
        with self.assertRaises(ValueError): self.run_archive(index={'artifacts': []})
        self.assertEqual(before, manifest.read_bytes())

    def test_expired_artifact_cannot_import_unverified_local_payload(self):
        self.write_payload(); item = artifact(); item['expired'] = True
        self.rejected(index={'artifacts': [item]})

    def test_validate_source_cli_emits_only_fixed_identity_outputs(self):
        source_path = self.repo / 'source.json'; source_path.write_text(json.dumps(source_run()))
        index = self.repo / 'artifacts.json'; index.write_text(json.dumps({'artifacts': [artifact()]}))
        out = self.repo / 'github-output'
        with patch('builtins.print'):
            result = archive.main(['validate-source', '--source-run-json', str(source_path),
                                   '--artifact-index', str(index), '--github-output', str(out)])
        self.assertEqual(result, 0)
        self.assertIn('commit=' + 'a' * 40 + '\n', out.read_text())
        self.assertIn('artifactIds=456\n', out.read_text())
        self.assertIn('evidencePath=' + self.output + '\n', out.read_text())


if __name__ == '__main__':
    unittest.main()
