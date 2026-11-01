"""Trust, immutable bytes and partial-run boundaries for the raw-window archive."""
import base64
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import window_infos_raw as probe
import window_infos_raw_archive as archive
import test_window_infos_raw as host_fixtures


def source_run():
    return {'id': 123, 'run_attempt': 2, 'repository': {'id': 42, 'full_name': archive.REPOSITORY},
            'head_repository': {'id': 42, 'full_name': archive.REPOSITORY}, 'event': 'workflow_dispatch',
            'head_branch': 'master', 'head_sha': 'a' * 40, 'status': 'completed', 'conclusion': 'success',
            'path': archive.WORKFLOW, 'run_started_at': '2026-11-01T10:00:00Z',
            'updated_at': '2026-11-01T10:10:00Z'}


def source_jobs():
    return {'jobs': [{'id': 91, 'name': 'build', 'run_id': 123, 'run_attempt': 2,
                     'head_sha': 'a' * 40, 'status': 'completed', 'conclusion': 'success',
                     'started_at': '2026-11-01T10:00:30Z', 'completed_at': '2026-11-01T10:01:00Z'},
                    {'id': 92, 'name': 'probe', 'run_id': 123, 'run_attempt': 2,
                     'head_sha': 'a' * 40, 'status': 'completed', 'conclusion': 'success',
                     'started_at': '2026-11-01T10:01:00Z', 'completed_at': '2026-11-01T10:08:00Z'},
                    {'id': 93, 'name': 'archive', 'status': 'completed', 'conclusion': 'skipped'}]}


def artifact():
    return {'id': 456, 'name': archive.ARTIFACT_PREFIX + '2', 'expired': False, 'size_in_bytes': 1024,
            'created_at': '2026-11-01T10:06:00Z', 'updated_at': '2026-11-01T10:06:01Z',
            'workflow_run': {'id': 123, 'head_sha': 'a' * 40, 'head_branch': 'master',
                             'repository_id': 42, 'head_repository_id': 42}}


class RawArchiveTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='raw-window-archive-')
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.incoming = self.repo / 'incoming'
        self.incoming.mkdir()
        for name in (*probe.SOURCE_PATHS, 'tools/window-infos-raw/src/dev/agentos/raw/Synthetic.java'):
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('Synthetic source fixture: ' + name + '\n')
        self.source = archive.verify_source(source_run())
        self.output = archive.evidence_path(self.source)

    def prepared(self):
        report = probe.empty_report(self.source, probe.source_files(self.repo))
        report['createdAt'] = '2026-11-01T10:02:00Z'
        return {'report.json': report, 'capture.json': probe.empty_capture(self.source),
                'commands.json': {'schemaVersion': 1, 'kind': probe.KIND + '-commands',
                                  'source': copy.deepcopy(self.source), 'commands': []}}

    def write_payload(self, values=None):
        values = self.prepared() if values is None else values
        directory = self.incoming / artifact()['name']
        directory.mkdir()
        for name, value in values.items():
            data = value if isinstance(value, bytes) else (json.dumps(value, indent=2) + '\n').encode()
            (directory / name).write_bytes(data)
        return directory

    def run_archive(self, index=None, run=None, jobs=None, output=None):
        return archive.archive(self.incoming, output or self.output, run or source_run(),
                               source_jobs() if jobs is None else jobs,
                               {'artifacts': [artifact()]} if index is None else index, self.repo)

    def rejected(self, **kwargs):
        with self.assertRaises((ValueError, OSError)):
            self.run_archive(**kwargs)
        self.assertFalse((self.repo / self.output).exists())

    def complete_synthetic_payload(self, raw=None, expected='passed'):
        # All execution is a synthetic driver; no Android process or real screenshot is used.
        (self.repo / 'tools/window-infos-raw/src/dev/agentos/raw/Synthetic.java').unlink()
        for name in host_fixtures.JAVA:
            path = self.repo / name; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('Synthetic source bytes: ' + name + '\n')
        identities = probe.source_files(self.repo)
        with patch.object(host_fixtures, 'SOURCE', self.source), \
                patch.object(host_fixtures, 'IDENTITIES', identities), \
                patch.object(probe, 'now', return_value='2026-11-01T10:02:00Z'):
            result = host_fixtures.passed_payloads(raw=raw)
        self.assertEqual(result['report.json']['status'], expected)
        return result

    def test_complete_synthetic_driver_bundle_is_revalidated_and_preserved(self):
        values = self.complete_synthetic_payload(); directory = self.write_payload(values)
        originals = {p.name: p.read_bytes() for p in directory.iterdir()}
        manifest = self.run_archive()
        self.assertEqual(manifest['status'], 'passed')
        self.assertEqual(manifest['summary']['passedCases'], 1)
        self.assertEqual(set(originals), probe.FILES)
        self.assertEqual(manifest['capture']['status'], 'recorded')
        self.assertIn('not an atomic', manifest['capture']['meaning'])
        self.assertEqual(len(manifest['files']), len(probe.FILES) + 3)
        for name, original in originals.items():
            self.assertEqual((self.repo / self.output / name).read_bytes(), original)
        for entry in manifest['files']:
            data = (self.repo / self.output / entry['path']).read_bytes()
            self.assertEqual(entry['bytes'], len(data))
            self.assertEqual(entry['sha256'], hashlib.sha256(data).hexdigest())

    def test_complete_failed_driver_preserves_original_rejected_candidate(self):
        raw = host_fixtures.raw_result(); raw['source'] = self.source
        raw['samples'][0] = host_fixtures.sample(rows=[host_fixtures.row(config=2)])
        values = self.complete_synthetic_payload(raw=raw, expected='failed')
        directory = self.write_payload(values)
        original = (directory / 'raw-result.json').read_bytes()
        run = source_run(); run['conclusion'] = 'failure'
        jobs = source_jobs(); jobs['jobs'][1]['conclusion'] = 'failure'
        manifest = self.run_archive(run=run, jobs=jobs)
        self.assertEqual(manifest['status'], 'failed')
        self.assertEqual(manifest['diagnosticReportStatus'], 'failed')
        self.assertEqual(manifest['summary']['passedCases'], 0)
        self.assertEqual((self.repo / self.output / 'raw-result.json').read_bytes(), original)
        self.assertTrue(values['raw-result.json']['samples'][0]['rows'][0]['notVisible'])

    def test_complete_bundle_foreign_marker_or_changed_source_is_rejected(self):
        original = self.complete_synthetic_payload()
        values = copy.deepcopy(original)
        command = next(item for item in values['commands.json']['commands'] if item['id'] == 'raw_probe')
        data = base64.b64decode(command['outputBase64']).replace(b'a' * 40, b'b' * 40)
        command.update(outputBase64=base64.b64encode(data).decode(), bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        # Rehash the altered raw stream: the identity check, not a stale digest, must reject it.
        directory = self.write_payload(values); self.rejected()
        for p in directory.iterdir(): p.unlink()
        directory.rmdir(); self.write_payload(original)
        path = self.repo / host_fixtures.JAVA[0]
        path.write_text('Changed source after synthetic receipt')
        self.rejected()

    def test_wrong_event_branch_workflow_identity_and_incomplete_source_rejected(self):
        for key, value in [('event', 'pull_request'), ('head_branch', 'feature'), ('status', 'in_progress'),
                           ('path', '.github/workflows/demo-video.yml'), ('head_sha', '../escape'),
                           ('run_attempt', True), ('id', '123'), ('conclusion', None)]:
            with self.subTest(key=key):
                run = source_run(); run[key] = value
                with self.assertRaises(ValueError): archive.verify_source(run)
        for key, value in [('id', 43), ('full_name', 'other/fork')]:
            run = source_run(); run['head_repository'][key] = value
            with self.assertRaises(ValueError): archive.verify_source(run)

    def test_source_times_require_zone_and_order(self):
        for value in ['2026-11-01T09:00:00Z', '2026-11-01T10:10:00', 'bad-time']:
            run = source_run(); run['updated_at'] = value
            with self.assertRaises(ValueError): archive.verify_source(run)

    def test_job_attempt_identity_time_and_duplicate_rejected(self):
        for key, value in [('run_id', 124), ('run_attempt', 1), ('run_attempt', True),
                           ('head_sha', 'b' * 40), ('status', 'in_progress'), ('conclusion', None),
                           ('started_at', '2026-11-01T09:00:00Z'),
                           ('completed_at', '2026-11-01T10:11:00Z')]:
            jobs = source_jobs(); jobs['jobs'][1][key] = value
            with self.subTest(key=key, value=value):
                with self.assertRaises(ValueError): archive.source_job(jobs, source_run())
        jobs = source_jobs(); jobs['jobs'].append(copy.deepcopy(jobs['jobs'][1]))
        with self.assertRaises(ValueError): archive.source_job(jobs, source_run())
        jobs = source_jobs(); jobs['jobs'][0]['run_attempt'] = 1
        with self.assertRaises(ValueError): archive.source_job(jobs, source_run())

    def test_archive_only_run_cannot_be_a_test_source(self):
        jobs = source_jobs()
        for job in jobs['jobs']: job['conclusion'] = 'success' if job['name'] == 'archive' else 'skipped'
        with self.assertRaisesRegex(ValueError, 'archive-only'): archive.source_job(jobs, source_run())
        jobs['jobs'].pop()
        with self.assertRaisesRegex(ValueError, 'executed test-mode'): archive.source_job(jobs, source_run())
        with self.assertRaises(ValueError): archive.source_job({'jobs': []}, source_run())

    def test_build_failure_keeps_missing_probe_unverified(self):
        jobs = source_jobs(); jobs['jobs'][0]['conclusion'] = 'failure'; jobs['jobs'][1]['conclusion'] = 'skipped'
        # Observed GitHub skipped jobs may report completed before started.
        jobs['jobs'][1]['completed_at'] = '2026-11-01T10:00:59Z'
        run = source_run(); run['conclusion'] = 'failure'
        manifest = self.run_archive(run=run, jobs=jobs, index={'artifacts': []})
        self.assertEqual(manifest['sourceConclusion'], 'failure')
        self.assertEqual(manifest['status'], 'unverified')
        self.assertEqual(manifest['summary']['passedCases'], 0)

    def test_old_attempt_ignored_and_duplicate_current_artifact_rejected(self):
        old = artifact(); old['name'] = archive.ARTIFACT_PREFIX + '1'
        self.assertEqual(archive.eligible_artifact({'artifacts': [old, artifact()]}, source_run(), source_jobs()), artifact())
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            archive.eligible_artifact([{'artifacts': [artifact()]}, {'artifacts': [artifact()]}], source_run(), source_jobs())

    def test_artifact_mismatched_origin_job_time_size_and_expiry_rejected(self):
        for key, value in [('id', False), ('expired', 'false'), ('size_in_bytes', archive.MAX_ARTIFACT_BYTES + 1),
                           ('created_at', '2026-11-01T10:00:50Z'), ('created_at', '2026-11-01T10:09:00Z'),
                           ('updated_at', '2026-11-01T10:09:00Z')]:
            item = artifact(); item[key] = value
            with self.subTest(key=key):
                with self.assertRaises(ValueError): archive.eligible_artifact({'artifacts': [item]}, source_run(), source_jobs())
        for key, value in [('id', 124), ('head_sha', 'b' * 40), ('head_branch', 'feature'),
                           ('repository_id', 43), ('head_repository_id', True)]:
            item = artifact(); item['workflow_run'][key] = value
            with self.assertRaises(ValueError): archive.eligible_artifact({'artifacts': [item]}, source_run(), source_jobs())
        jobs = source_jobs(); jobs['jobs'].pop(1)
        with self.assertRaisesRegex(ValueError, 'no current-attempt'): archive.eligible_artifact({'artifacts': [artifact()]}, source_run(), jobs)
        jobs = source_jobs(); jobs['jobs'][1]['conclusion'] = 'skipped'
        with self.assertRaisesRegex(ValueError, 'skipped probe'): archive.eligible_artifact({'artifacts': [artifact()]}, source_run(), jobs)

    def test_missing_and_expired_artifacts_are_not_passes(self):
        result = self.run_archive(index={'artifacts': []})
        self.assertEqual(result['summary'], {'expectedCases': 1, 'passedCases': 0, 'apiLevel': 37})
        self.assertEqual(result['artifact']['status'], 'missing')
        self.assertFalse(result['capture']['screenshots'])
        self.assertFalse(result['scope']['applicationCompatibilityAcceptance'])
        other = copy.deepcopy(self.source); other['attempt'] = 3
        # A separate destination is required for every immutable attempt.
        run = source_run(); run['run_attempt'] = 3
        jobs = source_jobs()
        for job in jobs['jobs']:
            if job['name'] != 'archive': job['run_attempt'] = 3
        item = artifact(); item['name'] = archive.ARTIFACT_PREFIX + '3'; item['expired'] = True
        expired = self.run_archive(index={'artifacts': [item]}, run=run, jobs=jobs, output=archive.evidence_path(other))
        self.assertEqual(expired['artifact']['status'], 'expired')
        self.assertEqual(expired['status'], 'unverified')

    def test_prepared_payload_is_revalidated_preserved_and_hashed(self):
        directory = self.write_payload()
        originals = {p.name: p.read_bytes() for p in directory.iterdir()}
        result = self.run_archive()
        self.assertEqual(result['status'], 'unverified')
        self.assertEqual(result['diagnosticReportStatus'], 'unverified')
        self.assertEqual(result['capture']['status'], 'not_recorded')
        self.assertEqual(len(result['files']), 6)
        for item in result['files']:
            data = (self.repo / self.output / item['path']).read_bytes()
            self.assertEqual(item['bytes'], len(data))
            self.assertEqual(item['sha256'], hashlib.sha256(data).hexdigest())
            if item['path'] in originals: self.assertEqual(data, originals[item['path']])

    def test_fake_pass_without_raw_registration_is_rejected(self):
        values = self.prepared(); values['report.json']['status'] = 'passed'
        self.write_payload(values); self.rejected()

    def test_foreign_source_and_changed_source_bytes_rejected(self):
        values = self.prepared(); values['report.json']['source']['commit'] = 'b' * 40
        directory = self.write_payload(values); self.rejected()
        for child in directory.iterdir(): child.unlink()
        directory.rmdir(); self.write_payload()
        (self.repo / probe.SOURCE_PATHS[0]).write_text('Changed source after report')
        self.rejected()

    def test_source_job_failure_cannot_promote_a_valid_pass(self):
        # Isolates archive status policy; this stub is explicitly not a device receipt.
        values = self.prepared(); self.write_payload(values)
        valid = copy.deepcopy(values['report.json']); valid['status'] = 'passed'
        run = source_run(); run['conclusion'] = 'failure'
        with patch.object(probe, 'validate_evidence', return_value=valid):
            result = self.run_archive(run=run)
        self.assertEqual(result['diagnosticReportStatus'], 'passed')
        self.assertEqual(result['status'], 'failed')
        self.assertEqual(result['summary']['passedCases'], 0)

    def test_report_times_are_inside_probe_job_and_before_upload(self):
        for key, value in [('createdAt', '2026-11-01T10:00:59Z'), ('completedAt', '2026-11-01T10:07:00Z')]:
            values = self.prepared(); values['report.json'][key] = value
            directory = self.write_payload(values)
            # Isolates archive temporal policy even if the host rejects the partial report first.
            with patch.object(probe, 'validate_evidence', return_value=values['report.json']): self.rejected()
            for item in directory.iterdir(): item.unlink()
            directory.rmdir()

    def test_unexpected_files_directories_and_flat_download_rejected(self):
        directory = self.write_payload()
        for name in ['image.png', 'instrumentation.txt', 'extra.json']:
            path = directory / name; path.write_text('{}'); self.rejected(); path.unlink()
        path = directory / 'subdirectory'; path.mkdir(); self.rejected(); path.rmdir()
        extra = self.incoming / 'foreign-artifact'; extra.mkdir(); self.rejected()

    def test_linked_payload_or_output_parent_rejected(self):
        directory = self.write_payload()
        original = directory / 'capture.json'; target = self.repo / 'copy.json'
        target.write_bytes(original.read_bytes()); original.unlink(); original.symlink_to(target)
        self.rejected(); original.unlink(); original.write_bytes(target.read_bytes())
        parent = self.repo / 'screenshots'; parent.symlink_to(self.incoming); self.rejected()

    def test_malformed_duplicate_nonfinite_and_deep_json_rejected(self):
        directory = self.write_payload(); target = directory / 'capture.json'
        for raw in [b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":1e999}', b'[' * 40 + b'0' + b']' * 40, b'not-json']:
            target.write_bytes(raw); self.rejected()

    def test_individual_and_aggregate_payload_bounds_precede_validator(self):
        directory = self.write_payload(); target = directory / 'capture.json'
        with target.open('wb') as stream: stream.truncate(archive.MAX_FILE_BYTES + 1)
        self.rejected()
        target.write_text(json.dumps(probe.empty_capture(self.source)))
        with patch.object(archive, 'MAX_PAYLOAD_BYTES', 100): self.rejected()
        for name, size in [('screenshot.png', probe.MAX_PNG + 1), ('probe.jar', probe.MAX_JAR + 1)]:
            path = directory / name
            with path.open('wb') as stream: stream.truncate(size)
            self.rejected(); path.unlink()

    def test_wrong_output_missing_download_and_expired_payload_rejected(self):
        self.rejected(output='../escape'); self.rejected()
        self.write_payload(); item = artifact(); item['expired'] = True
        self.rejected(index={'artifacts': [item]})

    def test_output_is_never_overwritten(self):
        self.run_archive(index={'artifacts': []})
        manifest = self.repo / self.output / 'manifest.json'; original = manifest.read_bytes()
        with self.assertRaises(ValueError): self.run_archive(index={'artifacts': []})
        self.assertEqual(manifest.read_bytes(), original)

    def test_validate_source_cli_outputs_only_trusted_identity(self):
        for name, value in [('run.json', source_run()), ('jobs.json', source_jobs()),
                            ('artifacts.json', {'artifacts': [artifact()]})]:
            (self.repo / name).write_text(json.dumps(value))
        output = self.repo / 'github-output'
        with patch('builtins.print'):
            status = archive.main(['validate-source', '--source-run-json', str(self.repo/'run.json'),
                                   '--source-jobs-json', str(self.repo/'jobs.json'),
                                   '--artifact-index', str(self.repo/'artifacts.json'),
                                   '--github-output', str(output)])
        self.assertEqual(status, 0)
        self.assertIn('commit=' + 'a' * 40 + '\n', output.read_text())
        self.assertIn('artifactIds=456\n', output.read_text())
        self.assertIn('evidencePath=' + self.output + '\n', output.read_text())


if __name__ == '__main__':
    unittest.main()
