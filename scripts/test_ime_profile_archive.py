"""Archive policy and trusted-source tests; synthetic fixtures are not device evidence."""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from types import ModuleType, SimpleNamespace
import unittest
from unittest.mock import patch

import ime_profile_archive as a


def run_info():
    return {'id': 123, 'run_attempt': 2, 'repository': {'id': 42, 'full_name': a.REPOSITORY},
        'head_repository': {'id': 42, 'full_name': a.REPOSITORY}, 'head_sha': 'a' * 40,
        'head_branch': 'master', 'event': 'workflow_dispatch', 'path': a.WORKFLOW,
        'status': 'completed', 'conclusion': 'success',
        'run_started_at': '2026-11-03T10:00:00Z', 'updated_at': '2026-11-03T10:10:00Z'}


def jobs_info():
    base = {'run_id': 123, 'run_attempt': 2, 'head_sha': 'a' * 40, 'status': 'completed'}
    return {'jobs': [dict(base, id=91, name=a.JOB, conclusion='success',
        started_at='2026-11-03T10:01:00Z', completed_at='2026-11-03T10:08:00Z'),
        dict(base, id=92, name='archive', conclusion='skipped',
        started_at='2026-11-03T10:00:01Z', completed_at='2026-11-03T10:00:01Z')]}


def artifact_info():
    return {'id': 456, 'name': a.ARTIFACT_PREFIX + '2', 'size_in_bytes': 1024, 'expired': False,
        'created_at': '2026-11-03T10:06:00Z', 'updated_at': '2026-11-03T10:06:01Z',
        'workflow_run': {'id': 123, 'head_sha': 'a' * 40, 'head_branch': 'master',
                         'repository_id': 42, 'head_repository_id': 42}}


class ArchivePolicyTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='ime-profile-archive-policy-')
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        self.incoming = self.repo / 'incoming'; self.incoming.mkdir()
        self.source = a.verify_source(run_info())
        self.output = a.evidence_path(self.source)
        self.probe = SimpleNamespace(validate_evidence=self.validate)
        guard = patch.object(a, 'load_producer', return_value=(self.probe, object()))
        self.addCleanup(guard.stop); self.load = guard.start()

    def validate(self, payloads, source):
        # Policy double only. Native trace/probe validation has its own tests.
        report = a.json_bytes(payloads['report.json'])
        self.assertEqual(source, report['source'])
        a.json_bytes(payloads['commands.json'], a.MAX_COMMAND_JSON)
        if report['status'] == 'recorded':
            a.require('method.trace' in payloads and report['profile']['traceFile'] ==
                {'path': 'method.trace', **a.identity(payloads['method.trace'])}, 'trace mismatch')
        return report

    def payload(self, status='recorded'):
        trace = b'SYNTHETIC-NOT-A-DEVICE-METHOD-TRACE\n'
        report = {'schemaVersion': 1, 'source': self.source, 'sourceFiles': {}, 'status': status,
            'failureReason': None if status == 'recorded' else 'SYNTHETIC_UNVERIFIED',
            'createdAt': '2026-11-03T10:02:00Z', 'completedAt': '2026-11-03T10:05:00Z',
            'profile': {'startAccepted': True, 'stopAccepted': True, 'globalSlotClear': True,
                'cleanupConfirmed': True, 'traceFile': {'path': 'method.trace', **a.identity(trace)}},
            'analysis': {'status': 'recorded' if status == 'recorded' else 'unverified',
                'reasons': [] if status == 'recorded' else ['SYNTHETIC_PARTIAL']}}
        return {'report.json': (json.dumps(report) + '\n').encode(),
                'commands.json': b'{"synthetic":true}\n', 'method.trace': trace}

    def write(self, values=None):
        directory = self.incoming / artifact_info()['name']; directory.mkdir()
        for name, data in (self.payload() if values is None else values).items():
            (directory / name).write_bytes(data)
        return directory

    def archive(self, run=None, jobs=None, index=None, output=None):
        return a.archive(self.incoming, self.output if output is None else output,
            run_info() if run is None else run, jobs_info() if jobs is None else jobs,
            {'artifacts': [artifact_info()]} if index is None else index, self.repo, self.repo)

    def reject(self, **kwargs):
        with self.assertRaises((ValueError, OSError)):
            self.archive(**kwargs)
        self.assertFalse((self.repo / self.output).exists())

    def test_recorded_originals_and_separate_states_are_preserved(self):
        values = self.payload(); self.write(values)
        result = self.archive()
        self.assertEqual('recorded', result['status'])
        self.assertEqual('recorded', result['analysis']['status'])
        self.assertTrue(result['profile']['cleanupConfirmed'])
        self.assertFalse(result['scope']['traceCaptureProvesResetCaller'])
        self.assertEqual(1, result['summary']['recordedProbes'])
        self.assertEqual(6, len(result['files']))
        for entry in result['files']:
            data = (self.repo / self.output / entry['path']).read_bytes()
            self.assertEqual(a.identity(data), {k: entry[k] for k in ('bytes', 'sha256')})
            if entry['path'] in values:
                self.assertEqual(values[entry['path']], data)

    def test_failed_job_never_upgrades_reported_success(self):
        self.write(); run = run_info(); run['conclusion'] = 'failure'
        jobs = jobs_info(); jobs['jobs'][0]['conclusion'] = 'failure'
        result = self.archive(run=run, jobs=jobs)
        self.assertEqual('unverified', result['status'])
        self.assertEqual('recorded', result['capture']['reportedStatus'])
        self.assertEqual('recorded', result['analysis']['status'])
        self.assertEqual(0, result['summary']['recordedProbes'])

    def test_partial_native_file_stays_unverified_with_its_original_bytes(self):
        values = self.payload('unverified'); values['method.trace'] = b'partial native data'
        self.write(values); result = self.archive()
        self.assertEqual('unverified', result['status'])
        self.assertEqual('unverified', result['analysis']['status'])
        self.assertTrue(result['capture']['tracePresent'])
        self.assertEqual(values['method.trace'], (self.repo / self.output / 'method.trace').read_bytes())

    def test_not_attempted_probe_and_missing_trace_remain_unverified(self):
        values = self.payload('unverified'); values.pop('method.trace')
        report = json.loads(values['report.json']); report['profile'] = None; report['analysis'] = None
        report['failureReason'] = 'NOT_EXECUTED'; report['completedAt'] = None
        values['report.json'] = json.dumps(report).encode(); self.write(values)
        result = self.archive()
        self.assertFalse(result['capture']['tracePresent'])
        self.assertEqual('NOT_EXECUTED', result['capture']['failureReason'])
        self.assertIsNone(result['profile'])
        self.assertEqual(1, result['summary']['unverifiedProbes'])

    def test_missing_or_expired_artifact_preserves_metadata_without_imports(self):
        for expired in (False, True):
            item = artifact_info(); item['expired'] = True
            result = self.archive(index={'artifacts': [item] if expired else []})
            self.assertEqual('unverified', result['status'])
            self.assertEqual('expired' if expired else 'missing', result['artifact']['status'])
            self.assertEqual(3, len(result['files']))
            self.assertIsNone(result['profile'])
            self.load.assert_not_called()
            shutil.rmtree(self.repo / self.output)

    def test_wrong_source_and_attempt_are_rejected_before_loading_code(self):
        for key, value in [('head_branch', 'feature'), ('event', 'push'), ('head_sha', '../bad'),
                           ('run_attempt', True), ('status', 'in_progress'), ('path', 'other.yml')]:
            with self.subTest(key=key):
                run = run_info(); run[key] = value; self.reject(run=run)
        jobs = jobs_info(); jobs['jobs'][0]['run_attempt'] = 1; self.reject(jobs=jobs)
        item = artifact_info(); item['workflow_run']['head_sha'] = 'b' * 40
        self.reject(index={'artifacts': [item]})
        self.load.assert_not_called()

    def test_fork_or_other_repository_identity_is_rejected(self):
        for target in ('repository', 'head_repository'):
            for key, value in [('full_name', 'other/Repo'), ('id', 99)]:
                run = run_info(); run[target][key] = value; self.reject(run=run)

    def test_archive_mode_unknown_and_duplicate_jobs_are_rejected(self):
        jobs = jobs_info(); jobs['jobs'][1]['conclusion'] = 'success'; self.reject(jobs=jobs)
        jobs = jobs_info(); jobs['jobs'][0]['conclusion'] = 'skipped'; self.reject(jobs=jobs)
        jobs = jobs_info(); jobs['jobs'].append(copy.deepcopy(jobs['jobs'][0])); self.reject(jobs=jobs)
        jobs = jobs_info(); jobs['jobs'][1]['name'] = 'untrusted'; self.reject(jobs=jobs)
        self.reject(jobs={'jobs': []})

    def test_artifact_identity_bounds_and_times_are_checked(self):
        for key, value in [('size_in_bytes', a.MAX_ARTIFACT + 1), ('expired', 0),
                           ('created_at', '2026-11-03T10:09:00Z')]:
            item = artifact_info(); item[key] = value; self.reject(index={'artifacts': [item]})
        self.reject(index={'artifacts': [artifact_info(), artifact_info()]})
        for key, value in [('id', 124), ('head_branch', 'feature'), ('repository_id', 99)]:
            item = artifact_info(); item['workflow_run'][key] = value
            self.reject(index={'artifacts': [item]})

    def test_payload_path_link_unknown_file_and_native_size_limits(self):
        directory = self.write()
        unknown = directory / 'extra'; unknown.mkdir(); self.reject(); unknown.rmdir()
        unknown.write_bytes(b'x'); self.reject(); unknown.unlink()
        trace = directory / 'method.trace'; trace.unlink(); trace.symlink_to('/dev/null')
        self.reject(); trace.unlink()
        with trace.open('wb') as stream:
            stream.truncate(a.MAX_TRACE + 1)
        self.reject()

    def test_incoming_exact_artifact_directory_and_output_path_are_required(self):
        directory = self.write()
        wrong = self.incoming / 'wrong'; directory.rename(wrong); self.reject(); wrong.rename(directory)
        for output in ('../escape', '/tmp/escape', self.output + '/../escape', self.output + '/'):
            self.reject(output=output)
        link = self.repo / 'screenshots'; link.symlink_to(self.incoming, target_is_directory=True)
        self.reject(); link.unlink()
        self.archive()
        with self.assertRaisesRegex(ValueError, 'already exists'):
            self.archive()

    def test_symlinked_ancestor_is_not_a_trusted_root_or_metadata_path(self):
        directory = self.write()
        alias = self.repo / 'alias'; alias.symlink_to(self.incoming, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'linked'):
            a.read_bytes(alias / directory.name / 'report.json')
        with self.assertRaisesRegex(ValueError, 'linked'):
            a.no_links(alias / directory.name, 'report.json')
        with self.assertRaisesRegex(ValueError, 'linked'):
            a.archive(alias / directory.name, self.output, run_info(), jobs_info(),
                      {'artifacts': [artifact_info()]}, self.repo, self.repo)
        self.assertFalse((self.repo / self.output).exists())

    def test_recorded_missing_or_changed_native_bytes_are_not_repaired(self):
        directory = self.write(); (directory / 'method.trace').unlink(); self.reject()
        (directory / 'method.trace').write_bytes(b'changed'); self.reject()

    def test_report_and_commands_malformed_json_are_not_repaired(self):
        directory = self.write()
        for data in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":1e999}', b'[' * 34 + b'0' + b']' * 34):
            (directory / 'commands.json').write_bytes(data); self.reject()

    def test_report_time_must_fall_inside_original_job(self):
        values = self.payload(); report = json.loads(values['report.json'])
        report['completedAt'] = '2026-11-03T10:07:00Z'
        values['report.json'] = json.dumps(report).encode(); self.write(values); self.reject()

    def test_command_json_and_aggregate_limits_are_separate(self):
        values = self.payload(); values['commands.json'] = json.dumps({'s': 'x' * (a.MAX_JSON + 100)}).encode()
        directory = self.write(values)
        result = self.archive()
        self.assertGreater(next(f['bytes'] for f in result['files'] if f['path'] == 'commands.json'), a.MAX_JSON)
        shutil.rmtree(self.repo / self.output)
        with patch.object(a, 'MAX_PAYLOAD', 64): self.reject()
        with (directory / 'commands.json').open('wb') as stream: stream.truncate(a.MAX_COMMAND_JSON + 1)
        self.reject()

    def test_validation_cannot_mutate_receipt_or_files(self):
        directory = self.write()
        with patch.object(self.probe, 'validate_evidence', return_value={'status': 'recorded'}): self.reject()
        def change(payloads, source):
            report = self.validate(payloads, source)
            (directory / 'method.trace').write_bytes(b'changed during validation')
            return report
        with patch.object(self.probe, 'validate_evidence', side_effect=change): self.reject()

    def test_metadata_pagination_accepts_only_bounded_valid_pages(self):
        self.write()
        self.assertEqual('recorded', self.archive(jobs=[jobs_info()], index=[{'artifacts': [artifact_info()]}])['status'])
        shutil.rmtree(self.repo / self.output)
        self.reject(jobs=[jobs_info()] * 5)
        self.reject(index=[{'artifacts': []}] * 21)


class GitBindingTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='ime-profile-archive-git-')
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name); scripts = self.repo / 'scripts'; scripts.mkdir()
        self.archive_file = scripts / 'ime_profile_archive.py'
        self.archive_file.write_bytes(Path(a.__file__).read_bytes())
        (scripts / 'ime_method_trace.py').write_text('VALUE = "fresh trusted parser"\n')
        (scripts / 'configure_emulator_phone.py').write_text('VALUE = "synthetic AVD helper"\n')
        (self.repo / '.github/workflows').mkdir(parents=True)
        (self.repo / a.WORKFLOW).write_text('name: synthetic source only\n')
        (scripts / 'ime_profile_probe.py').write_text(
            'import hashlib\nfrom pathlib import Path\nimport ime_method_trace as parser\n'
            f'SOURCE_PATHS = {tuple(sorted(a.SOURCE_PATHS))!r}\nFILES = {tuple(sorted(a.PAYLOAD_FILES))!r}\n'
            'def source_files(root):\n'
            '    return {n: {"bytes": len((Path(root)/n).read_bytes()), "sha256": hashlib.sha256((Path(root)/n).read_bytes()).hexdigest()} for n in SOURCE_PATHS}\n')
        self.git('init', '-q'); self.git('add', '.')
        self.git('-c', 'user.name=agent', '-c', 'user.email=agent@example.invalid', 'commit', '-qm', 'synthetic source')
        self.commit = self.git('rev-parse', 'HEAD').strip()
        self.source = dict(a.verify_source(run_info()), commit=self.commit)
        self.report = {'source': self.source,
            'sourceFiles': {n: a.identity((self.repo / n).read_bytes()) for n in a.SOURCE_PATHS}}
        guard = patch.object(a, '__file__', str(self.archive_file)); self.addCleanup(guard.stop); guard.start()
        guard = patch.object(a.importlib.util, 'find_spec', side_effect=lambda name:
            SimpleNamespace(origin=str(scripts / (name + '.py'))))
        self.addCleanup(guard.stop); guard.start()
        modules = {}
        for name in ('ime_method_trace', 'ime_profile_probe', 'configure_emulator_phone'):
            module = ModuleType(name); module.__file__ = str(scripts / (name + '.py'))
            module.VALUE = 'stale loaded code'; modules[name] = module
        guard = patch.dict(sys.modules, modules); self.addCleanup(guard.stop); guard.start()
        self.stale = modules

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True)

    def test_entire_git_catalog_is_verified_then_fresh_source_compiled(self):
        probe, parser = a.load_producer(self.report, self.source, self.repo)
        self.assertEqual('fresh trusted parser', parser.VALUE)
        self.assertIs(parser, probe.parser)
        self.assertIs(self.stale['ime_method_trace'], sys.modules['ime_method_trace'])
        self.assertEqual(self.report['sourceFiles'], probe.source_files(self.repo))

    def test_changed_checkout_or_forged_hash_rejected_before_compile(self):
        with patch('builtins.compile', side_effect=AssertionError('must not compile')):
            (self.repo / a.WORKFLOW).write_text('changed checkout')
            with self.assertRaisesRegex(ValueError, 'differ from Git'):
                a.load_producer(self.report, self.source, self.repo)
        (self.repo / a.WORKFLOW).write_text('name: synthetic source only\n')
        self.report['sourceFiles'][a.WORKFLOW]['sha256'] = '0' * 64
        with patch('builtins.compile', side_effect=AssertionError('must not compile')):
            with self.assertRaisesRegex(ValueError, 'differ from Git'):
                a.load_producer(self.report, self.source, self.repo)

    def test_missing_extra_or_foreign_source_identity_rejected(self):
        for change in ('missing', 'extra', 'foreign'):
            report = copy.deepcopy(self.report)
            if change == 'missing': report['sourceFiles'].pop(a.WORKFLOW)
            elif change == 'extra': report['sourceFiles']['other.py'] = a.identity(b'x')
            else: report['source']['commit'] = 'b' * 40
            with self.assertRaises(ValueError): a.load_producer(report, self.source, self.repo)

    def test_foreign_module_resolution_or_loaded_path_is_rejected(self):
        with patch.object(a.importlib.util, 'find_spec', return_value=SimpleNamespace(origin='/tmp/foreign.py')):
            with self.assertRaisesRegex(ValueError, 'outside verified scripts'):
                a.load_producer(self.report, self.source, self.repo)
        self.stale['ime_method_trace'].__file__ = '/tmp/foreign.py'
        with self.assertRaisesRegex(ValueError, 'loaded producer module differs'):
            a.load_producer(self.report, self.source, self.repo)

    def test_foreign_commit_symlink_and_oversized_git_object_are_rejected(self):
        with self.assertRaises((ValueError, subprocess.SubprocessError)):
            a.git_bytes(self.repo, 'b' * 40, a.WORKFLOW, a.MAX_JSON)
        link = self.repo / 'scripts/link.py'; link.symlink_to('ime_method_trace.py')
        self.git('add', '.'); self.git('-c', 'user.name=agent', '-c', 'user.email=agent@example.invalid', 'commit', '-qm', 'link')
        commit = self.git('rev-parse', 'HEAD').strip()
        with self.assertRaisesRegex(ValueError, 'regular Git blob'):
            a.git_bytes(self.repo, commit, 'scripts/link.py', a.MAX_JSON)
        with self.assertRaisesRegex(ValueError, 'exceeds bound'):
            a.git_bytes(self.repo, self.commit, a.WORKFLOW, 1)


class ProducerIntegrationTests(unittest.TestCase):
    def test_actual_prepared_probe_is_archived_against_temporary_git(self):
        # Real host parser and validator, preparation only; never executes adb.
        import ime_profile_probe as host
        original = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix='ime-profile-archive-integration-') as temporary:
            repo = Path(temporary)
            for name in host.SOURCE_PATHS:
                target = repo / name; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((original / name).read_bytes())
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args], text=True)
            git('init', '-q'); git('add', '.')
            git('-c', 'user.name=agent', '-c', 'user.email=agent@example.invalid', 'commit', '-qm', 'synthetic snapshot')
            commit = git('rev-parse', 'HEAD').strip()
            run = run_info(); run['head_sha'] = commit
            jobs = jobs_info()
            for job in jobs['jobs']: job['head_sha'] = commit
            item = artifact_info(); item['workflow_run']['head_sha'] = commit
            source = a.verify_source(run); incoming = repo / 'incoming'; incoming.mkdir()
            directory = incoming / item['name']
            with patch.object(host, 'now', return_value='2026-11-03T10:02:00Z'):
                host.prepare(directory, source)
            originals = {p.name: p.read_bytes() for p in directory.iterdir()}
            result = a.archive(incoming, a.evidence_path(source), run, jobs, {'artifacts': [item]}, repo, repo)
            self.assertEqual('unverified', result['status'])
            self.assertEqual('NOT_EXECUTED', result['capture']['failureReason'])
            self.assertEqual(1, result['summary']['unverifiedProbes'])
            for name, data in originals.items():
                self.assertEqual(data, (repo / a.evidence_path(source) / name).read_bytes())


if __name__ == '__main__':
    unittest.main()
