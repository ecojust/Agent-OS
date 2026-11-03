"""Synthetic reset archive boundaries; these fixtures are not Android evidence.

Policy tests stub semantic host/parser validation, while source-byte tests use real
temporary Git repositories. Host and trace parsing semantics have separate tests.
"""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import window_infos_reset_trace_archive as archive


def run_info():
    return {'id': 123, 'run_attempt': 2, 'repository': {'id': 42, 'full_name': archive.REPOSITORY},
        'head_repository': {'id': 42, 'full_name': archive.REPOSITORY},
        'head_sha': 'a' * 40, 'head_branch': 'master', 'event': 'workflow_dispatch',
        'path': archive.WORKFLOW, 'status': 'completed', 'conclusion': 'success',
        'run_started_at': '2026-11-03T10:00:00Z', 'updated_at': '2026-11-03T10:10:00Z'}


def jobs_info():
    base = {'run_id': 123, 'run_attempt': 2, 'head_sha': 'a' * 40, 'status': 'completed'}
    return {'jobs': [dict(base, id=91, name='build', conclusion='success',
            started_at='2026-11-03T10:00:00Z', completed_at='2026-11-03T10:01:00Z'),
        dict(base, id=92, name=archive.JOB, conclusion='success',
            started_at='2026-11-03T10:01:00Z', completed_at='2026-11-03T10:08:00Z'),
        *[dict(base, id=number, name=name, conclusion='skipped',
               started_at='2026-11-03T10:00:02Z', completed_at='2026-11-03T10:00:01Z')
          for number, name in enumerate(('probe', 'keyboard', 'archive', 'archive_keyboard',
                                        'archive_reset_trace'), 93)]]}


def artifact_info():
    return {'id': 456, 'name': archive.ARTIFACT_PREFIX + '2', 'size_in_bytes': 1024, 'expired': False,
        'created_at': '2026-11-03T10:06:00Z', 'updated_at': '2026-11-03T10:06:01Z',
        'workflow_run': {'id': 123, 'head_sha': 'a' * 40, 'head_branch': 'master',
                         'repository_id': 42, 'head_repository_id': 42}}


class ResetArchivePolicyTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='reset-archive-policy-')
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.incoming = self.repo / 'incoming'
        self.incoming.mkdir()
        self.source = archive.verify_source(run_info())
        self.output = archive.evidence_path(self.source)
        self.files = {'report.json', 'commands.json', 'trace-config.pbtxt', 'trace.pftrace',
            'comparison/report.json', 'comparison/commands.json', 'comparison/journal.json',
            'comparison/baseline/raw-result.json', 'comparison/enabled/raw-result.json'} | {
                'analysis/' + name for name in archive.ANALYSIS_FILES}
        self.host = SimpleNamespace(FILES=self.files, file_limit=self.limit,
                                   validate_evidence=self.validate_capture)
        self.keyboard = SimpleNamespace(validate_evidence=self.validate_comparison,
                                        verify_source_files=lambda report, root: True)
        self.parser = SimpleNamespace(validate_analysis=lambda *args: True,
                                      load_context=lambda *args: {'source': self.source})
        guard = patch.object(archive, 'load_producer', return_value=(self.host, self.keyboard, self.parser))
        self.addCleanup(guard.stop)
        self.producer = guard.start()

    @staticmethod
    def limit(name):
        if name == 'trace.pftrace':
            return 32 * 1024 * 1024
        if name == 'commands.json':
            return archive.MAX_COMMAND_JSON
        if name == 'trace-config.pbtxt':
            return 64 * 1024
        return archive.MAX_JSON

    def validate_capture(self, payloads, source):
        report = payloads['report.json']
        self.assertEqual(report['source'], source)
        # This is explicitly an archive-policy double, not the production validator.
        if report['status'] == 'recorded':
            archive.require('trace.pftrace' in payloads and report['trace'] ==
                            archive.identity(payloads['trace.pftrace']), 'recorded trace missing or changed')
        return copy.deepcopy(report)

    def validate_comparison(self, payloads, source):
        self.assertEqual(payloads['report.json']['source'], source)
        return copy.deepcopy(payloads['report.json'])

    def payload(self, capture='recorded', comparison='failed'):
        child = archive.empty_comparison()
        child['status'] = comparison
        child['source'] = self.source
        child['sourceFiles'] = {}
        child['arms']['baseline']['status'] = 'failed'
        child['arms']['enabled']['status'] = 'passed'
        summary = {key: copy.deepcopy(child[key]) for key in ('status', 'failureReason', 'arms', 'restoration')}
        trace = b'SYNTHETIC TRACE BYTES; NOT A DEVICE RECORDING\n'
        report = {'source': self.source, 'sourceFiles': {}, 'status': capture,
            'failureReason': None if capture == 'recorded' else 'SYNTHETIC_CAPTURE_LOSS',
            'createdAt': '2026-11-03T10:02:00Z', 'completedAt': '2026-11-03T10:05:00Z',
            'comparison': summary, 'trace': archive.identity(trace)}
        return {'report.json': report, 'commands.json': {'synthetic': True},
            'trace-config.pbtxt': b'Synthetic non-executable config\n', 'trace.pftrace': trace,
            'comparison/report.json': child, 'comparison/commands.json': {'synthetic': True},
            'comparison/journal.json': {'synthetic': True}}

    def write_payload(self, values=None):
        values = self.payload() if values is None else values
        directory = self.incoming / artifact_info()['name']
        directory.mkdir()
        for name, value in values.items():
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(value if isinstance(value, bytes) else (json.dumps(value) + '\n').encode())
        return directory

    def run_archive(self, run=None, jobs=None, index=None, output=None):
        return archive.archive(self.incoming, self.output if output is None else output,
            run_info() if run is None else run, jobs_info() if jobs is None else jobs,
            {'artifacts': [artifact_info()]} if index is None else index, self.repo, self.repo)

    def reject(self, **kwargs):
        with self.assertRaises((ValueError, OSError)):
            self.run_archive(**kwargs)
        self.assertFalse((self.repo / self.output).exists())

    def test_recorded_trace_does_not_promote_failed_comparison(self):
        directory = self.write_payload()
        originals = {p.relative_to(directory).as_posix(): p.read_bytes()
                     for p in directory.rglob('*') if p.is_file()}
        result = self.run_archive()
        self.assertEqual(result['status'], 'recorded')
        self.assertEqual(result['comparison']['status'], 'failed')
        self.assertEqual(result['summary']['passedComparisons'], 0)
        self.assertEqual(result['summary']['failedArms'], 1)
        self.assertEqual(result['summary']['passedArms'], 1)
        self.assertEqual(result['analysis']['status'], 'missing')
        self.assertEqual(len(result['files']), len(originals) + 3)
        for item in result['files']:
            data = (self.repo / self.output / item['path']).read_bytes()
            self.assertEqual(archive.identity(data), {k: item[k] for k in ('bytes', 'sha256')})
            if item['path'] in originals:
                self.assertEqual(data, originals[item['path']])

    def test_failed_capture_can_preserve_original_passed_comparison(self):
        self.write_payload(self.payload(capture='unverified', comparison='passed'))
        result = self.run_archive()
        self.assertEqual(result['status'], 'unverified')
        self.assertEqual(result['summary']['recordedCaptures'], 0)
        self.assertEqual(result['summary']['passedComparisons'], 1)
        self.assertEqual(result['capture']['failureReason'], 'SYNTHETIC_CAPTURE_LOSS')

    def test_interrupted_parent_summary_does_not_replace_original_child(self):
        values = self.payload(capture='unverified', comparison='passed')
        values['report.json'].update(stage='comparison', completedAt=None,
                                     comparison=archive.empty_comparison())
        directory = self.write_payload(values)
        original = (directory / 'report.json').read_bytes()
        result = self.run_archive()
        self.assertEqual(result['comparison']['status'], 'passed')
        self.assertFalse(result['capture']['reportedComparisonMatchesChild'])
        self.assertEqual(result['status'], 'unverified')
        self.assertEqual((self.repo / self.output / 'report.json').read_bytes(), original)

    def test_failed_job_does_not_mark_capture_verified(self):
        self.write_payload()
        run = run_info(); run['conclusion'] = 'failure'
        jobs = jobs_info(); jobs['jobs'][1]['conclusion'] = 'failure'
        result = self.run_archive(run=run, jobs=jobs)
        self.assertEqual(result['status'], 'unverified')
        self.assertEqual(result['capture']['reportedStatus'], 'recorded')

    def test_partial_analysis_and_partial_trace_preserved_without_interpretation(self):
        values = self.payload(capture='unverified')
        values['trace.pftrace'] = b'partial synthetic trace'
        values['analysis/trace-slices.csv'] = b'id,name\n1,partial\n'
        self.write_payload(values)
        with patch.object(self.parser, 'validate_analysis') as validate:
            result = self.run_archive()
            validate.assert_not_called()
        self.assertEqual(result['analysis']['status'], 'partial')
        self.assertFalse(result['analysis']['completeFileSet'])
        self.assertEqual((self.repo / self.output / 'trace.pftrace').read_bytes(), values['trace.pftrace'])

    def test_complete_unknown_analysis_does_not_become_caller_proof(self):
        values = self.payload()
        for name in archive.ANALYSIS_FILES:
            values['analysis/' + name] = {'status': 'unknown'} if name == 'analysis.json' else b'id\n'
        self.write_payload(values)
        with patch.object(self.parser, 'validate_analysis', return_value={'status': 'unknown'}) as validate:
            result = self.run_archive()
            validate.assert_called_once()
        self.assertEqual(result['analysis']['reportedStatus'], 'unknown')
        self.assertFalse(result['scope']['resetCallerEstablishedByCapture'])

    def test_missing_recorded_trace_is_not_repaired(self):
        values = self.payload(); values.pop('trace.pftrace'); self.write_payload(values)
        self.reject()

    def test_tampered_caller_is_preserved_as_invalid_not_validated(self):
        values = self.payload()
        for name in archive.ANALYSIS_FILES:
            values['analysis/' + name] = {'status': 'recorded', 'callerClassification': 'settings_observer'} \
                if name == 'analysis.json' else b'id\n'
        directory = self.write_payload(values)
        original = (directory / 'analysis/analysis.json').read_bytes()
        with patch.object(self.parser, 'validate_analysis', side_effect=ValueError('ANALYSIS_DERIVATION_MISMATCH')):
            result = self.run_archive()
        self.assertEqual(result['analysis']['status'], 'invalid')
        self.assertEqual(result['analysis']['validationError'], 'ANALYSIS_DERIVATION_MISMATCH')
        self.assertIsNone(result['analysis']['reportedStatus'])
        self.assertEqual((self.repo / self.output / 'analysis/analysis.json').read_bytes(), original)
        self.assertEqual(result['comparison']['status'], 'failed')

    def test_empty_and_truncated_analysis_json_preserve_original_evidence(self):
        for data in (b'', b'{"status":"recorded",'):
            values = self.payload()
            for name in archive.ANALYSIS_FILES:
                values['analysis/' + name] = data if name == 'analysis.json' else b'id\n'
            directory = self.write_payload(values)
            with patch.object(self.parser, 'validate_analysis') as validate:
                result = self.run_archive()
                validate.assert_not_called()
            self.assertEqual(result['analysis']['status'], 'invalid')
            self.assertEqual(result['analysis']['validationError'], 'ANALYSIS_JSON_INVALID')
            self.assertEqual((self.repo / self.output / 'trace.pftrace').read_bytes(), values['trace.pftrace'])
            self.assertEqual((self.repo / self.output / 'analysis/analysis.json').read_bytes(), data)
            shutil.rmtree(self.repo / self.output)
            shutil.rmtree(directory)

    def test_analysis_context_identity_and_io_errors_are_not_tolerated(self):
        values = self.payload()
        for name in archive.ANALYSIS_FILES:
            values['analysis/' + name] = {'status': 'unknown'} if name == 'analysis.json' else b'id\n'
        self.write_payload(values)
        with patch.object(self.parser, 'load_context', side_effect=ValueError('CONTEXT_SOURCE_MISMATCH')):
            self.reject()
        with patch.object(self.parser, 'validate_analysis', side_effect=OSError('synthetic I/O error')):
            self.reject()

    def test_missing_and_expired_artifacts_keep_unverified_denominators(self):
        for expired in (False, True):
            item = artifact_info(); item['expired'] = True
            result = self.run_archive(index={'artifacts': [item] if expired else []})
            self.assertEqual(result['status'], 'unverified')
            self.assertEqual(result['summary']['unverifiedArms'], 2)
            self.assertEqual(result['artifact']['status'], 'expired' if expired else 'missing')
            self.producer.assert_not_called()
            shutil.rmtree(self.repo / self.output)

    def test_source_mismatch_and_wrong_attempt_rejected_before_payload(self):
        for key, value in [('head_branch', 'feature'), ('event', 'push'), ('head_sha', '../bad'),
                           ('run_attempt', True), ('status', 'in_progress'), ('path', 'bad.yml')]:
            with self.subTest(key=key):
                run = run_info(); run[key] = value; self.reject(run=run)
        jobs = jobs_info(); jobs['jobs'][1]['run_attempt'] = 1; self.reject(jobs=jobs)
        item = artifact_info(); item['workflow_run']['head_sha'] = 'b' * 40
        self.reject(index={'artifacts': [item]})
        self.producer.assert_not_called()

    def test_competing_modes_and_duplicate_metadata_rejected(self):
        jobs = jobs_info(); jobs['jobs'][3]['conclusion'] = 'success'; self.reject(jobs=jobs)
        jobs = jobs_info(); jobs['jobs'].append(copy.deepcopy(jobs['jobs'][1])); self.reject(jobs=jobs)
        self.reject(index={'artifacts': [artifact_info(), artifact_info()]})

    def test_skipped_job_cannot_own_an_artifact(self):
        run = run_info(); run['conclusion'] = 'failure'
        jobs = jobs_info(); jobs['jobs'][0]['conclusion'] = 'failure'; jobs['jobs'][1]['conclusion'] = 'skipped'
        self.reject(run=run, jobs=jobs)

    def test_artifact_bounds_and_time_binding(self):
        for key, value in [('size_in_bytes', archive.MAX_ARTIFACT + 1), ('expired', 0),
                           ('created_at', '2026-11-03T10:09:00Z')]:
            item = artifact_info(); item[key] = value
            self.reject(index={'artifacts': [item]})

    def test_symlink_unknown_nested_file_and_oversized_trace_rejected(self):
        directory = self.write_payload()
        unknown = directory / 'comparison' / 'baseline' / 'unexpected'
        unknown.parent.mkdir(); unknown.write_bytes(b'x'); self.reject(); unknown.unlink()
        trace = directory / 'trace.pftrace'; trace.unlink(); trace.symlink_to('/dev/null')
        self.reject(); trace.unlink()
        with trace.open('wb') as stream:
            stream.truncate(32 * 1024 * 1024 + 1)
        self.reject()

    def test_source_and_output_paths_cannot_escape_or_overwrite(self):
        self.write_payload()
        for name in ('../escape', '/tmp/escape', self.output + '/../escape', self.output + '/'):
            self.reject(output=name)
        link = self.repo / 'screenshots'; link.symlink_to(self.incoming, target_is_directory=True)
        self.reject(); link.unlink()
        self.run_archive()
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.run_archive()

    def test_duplicate_nonfinite_and_deep_json_rejected(self):
        directory = self.write_payload()
        for raw in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":1e999}', b'[' * 34 + b'0' + b']' * 34):
            (directory / 'commands.json').write_bytes(raw)
            self.reject()

    def test_command_json_has_its_own_larger_bound(self):
        values = self.payload()
        values['commands.json'] = {'syntheticOutput': 'x' * (archive.MAX_JSON + 100)}
        directory = self.write_payload(values)
        result = self.run_archive()
        item = next(item for item in result['files'] if item['path'] == 'commands.json')
        self.assertGreater(item['bytes'], archive.MAX_JSON)
        shutil.rmtree(self.repo / self.output)
        with (directory / 'commands.json').open('wb') as stream:
            stream.truncate(archive.MAX_COMMAND_JSON + 1)
        self.reject()

    def test_aggregate_limit_rejects_before_publishing(self):
        self.write_payload()
        with patch.object(archive, 'MAX_PAYLOAD', 64):
            self.reject()

    def test_mutation_while_parser_reads_is_rejected(self):
        values = self.payload()
        for name in archive.ANALYSIS_FILES:
            values['analysis/' + name] = {'status': 'unknown'} if name == 'analysis.json' else b'id\n'
        directory = self.write_payload(values)
        def change(*args):
            (directory / 'analysis/trace-clocks.csv').write_bytes(b'changed\n')
            return True
        with patch.object(self.parser, 'validate_analysis', side_effect=change):
            self.reject()


class ResetArchiveGitBindingTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='reset-archive-git-')
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.name = 'scripts/window_infos_reset_trace_archive.py'
        path = self.repo / self.name; path.parent.mkdir()
        path.write_bytes(Path(archive.__file__).read_bytes())
        self.git('init', '-q')
        self.git('add', '.')
        self.git('-c', 'user.name=agent', '-c', 'user.email=agent@example.invalid', 'commit', '-qm', 'synthetic')
        self.commit = self.git('rev-parse', 'HEAD').strip()
        self.source = dict(archive.verify_source(run_info()), commit=self.commit)
        self.report = {'source': self.source,
                       'sourceFiles': {self.name: archive.identity(path.read_bytes())}}

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True)

    def test_git_and_executing_bytes_are_verified_before_import(self):
        fake = SimpleNamespace(verify_source_files=lambda *args: True)
        with patch.object(archive, 'PYTHON_PRODUCERS', {self.name}), \
                patch.object(archive.importlib, 'import_module', return_value=fake) as imported:
            archive.load_producer(self.report, self.source, self.repo)
            self.assertEqual(imported.call_count, 3)
            imported.reset_mock()
            (self.repo / self.name).write_bytes(b'changed checkout')
            with self.assertRaisesRegex(ValueError, 'differ from Git'):
                archive.load_producer(self.report, self.source, self.repo)
            imported.assert_not_called()

    def test_forged_declared_digest_or_missing_catalog_is_rejected(self):
        with patch.object(archive.importlib, 'import_module') as imported:
            with self.assertRaisesRegex(ValueError, 'incomplete'):
                archive.load_producer(self.report, self.source, self.repo)
            with patch.object(archive, 'PYTHON_PRODUCERS', {self.name}):
                self.report['sourceFiles'][self.name]['sha256'] = '0' * 64
                with self.assertRaisesRegex(ValueError, 'differ from Git'):
                    archive.load_producer(self.report, self.source, self.repo)
            imported.assert_not_called()

    def test_foreign_commit_and_git_symlink_are_not_producers(self):
        with self.assertRaises((ValueError, subprocess.SubprocessError)):
            archive.git_bytes(self.repo, 'b' * 40, self.name, archive.MAX_JSON)
        path = self.repo / 'scripts/link.py'; path.symlink_to('window_infos_reset_trace_archive.py')
        self.git('add', '.')
        self.git('-c', 'user.name=agent', '-c', 'user.email=agent@example.invalid', 'commit', '-qm', 'link')
        head = self.git('rev-parse', 'HEAD').strip()
        with self.assertRaisesRegex(ValueError, 'regular Git blob'):
            archive.git_bytes(self.repo, head, 'scripts/link.py', archive.MAX_JSON)

    def test_module_resolution_outside_checked_scripts_rejected(self):
        with patch.object(archive, 'PYTHON_PRODUCERS', {self.name}), \
                patch.object(archive.importlib.util, 'find_spec', return_value=SimpleNamespace(origin='/tmp/foreign.py')), \
                patch.object(archive.importlib, 'import_module') as imported:
            with self.assertRaisesRegex(ValueError, 'outside verified scripts'):
                archive.load_producer(self.report, self.source, self.repo)
            imported.assert_not_called()


class ResetArchiveProducerIntegrationTests(unittest.TestCase):
    def test_real_prepared_wrapper_and_old_comparison_validate_against_git(self):
        # Actual producer/keyboard validators, but preparation only: no command
        # runner, emulator, tracing binary, network or Android execution occurs.
        import window_infos_reset_trace as host
        producer_root = Path(host.__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix='reset-archive-integration-') as temporary:
            repo = Path(temporary)
            for name in host.source_files(producer_root):
                target = repo / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((producer_root / name).read_bytes())
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args], text=True)
            git('init', '-q')
            git('add', '.')
            git('-c', 'user.name=agent', '-c', 'user.email=agent@example.invalid',
                'commit', '-qm', 'synthetic producer snapshot')
            commit = git('rev-parse', 'HEAD').strip()
            run = run_info(); run['head_sha'] = commit
            jobs = jobs_info()
            for job in jobs['jobs']:
                job['head_sha'] = commit
            item = artifact_info(); item['workflow_run']['head_sha'] = commit
            source = archive.verify_source(run)
            incoming = repo / 'incoming'
            incoming.mkdir()
            payload = incoming / item['name']
            with patch.object(host, 'now', return_value='2026-11-03T10:02:00Z'), \
                    patch.object(host.k, 'now', return_value='2026-11-03T10:02:00Z'):
                host.prepare(payload, source)
            before = {p.relative_to(payload).as_posix(): p.read_bytes()
                      for p in payload.rglob('*') if p.is_file()}
            result = archive.archive(incoming, archive.evidence_path(source), run, jobs,
                                     {'artifacts': [item]}, repo, repo)
            self.assertEqual(result['status'], 'unverified')
            self.assertEqual(result['capture']['failureReason'], 'NOT_EXECUTED')
            self.assertEqual(result['summary']['unverifiedArms'], 2)
            self.assertTrue(result['capture']['reportedComparisonMatchesChild'])
            self.assertEqual(result['analysis']['status'], 'missing')
            destination = repo / archive.evidence_path(source)
            for name, data in before.items():
                self.assertEqual((destination / name).read_bytes(), data)
            self.assertEqual(len(result['files']), len(before) + 3)


if __name__ == '__main__':
    unittest.main()
