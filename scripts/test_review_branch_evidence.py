"""Development evidence requires explicit same-repository branch and source consent."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import collect_android_evidence as collector
import publish_test_evidence as publisher
from test_evidence_scripts import PNG, source_run

REPOSITORY = 'ecojust/Agent-OS'
BRANCH = 'agent/control-layout-regression'
SHA = 'a' * 40
SCRIPT = Path(publisher.__file__).resolve()


def run():
    value = source_run()
    value.update(head_branch=BRANCH, head_sha=SHA, event='workflow_dispatch',
                 display_title=publisher.CONTROL_STATUS_TITLE)
    return value


def artifact():
    return {'id': 456, 'name': 'screenshots-api-30-attempt-2', 'expired': False,
            'size_in_bytes': 1000, 'created_at': '2026-10-28T00:01:00Z',
            'workflow_run': {'id': 123, 'head_sha': SHA, 'head_branch': BRANCH,
                             'repository_id': 1, 'head_repository_id': 1}}


def job():
    return {'name': 'emulator-tests (30)', 'status': 'completed', 'run_id': 123,
            'run_attempt': 2, 'head_sha': SHA, 'conclusion': 'success',
            'started_at': '2026-10-28T00:00:00Z', 'completed_at': '2026-10-28T00:02:00Z'}


class ReviewBranchEvidenceTests(unittest.TestCase):
    def test_same_branch_explicit_source_accepts_older_commit_and_failed_run(self):
        # The archive workflow revision is deliberately not a verification input:
        # source SHA may be an older commit on the same branch, never checked out.
        self.assertEqual((123, 2), publisher.verify_source(run(), REPOSITORY, BRANCH, SHA))
        self.assertEqual('failure', run()['conclusion'])

    def test_missing_opt_in_or_explicit_sha_never_accepts_development_source(self):
        for branch, sha in ((None, None), (None, SHA), (BRANCH, None), (BRANCH, ''),
                            (BRANCH, 'b' * 40), (BRANCH, SHA[:-1]), (BRANCH, '../source')):
            with self.subTest(branch=branch, sha=sha), self.assertRaises(ValueError):
                publisher.verify_source(run(), REPOSITORY, branch, sha)

    def test_other_branch_event_workflow_fork_and_missing_id_are_rejected(self):
        changes = ({'head_branch': 'agent/another'}, {'head_branch': 'master'},
                   {'event': 'push'}, {'event': 'pull_request'}, {'status': 'in_progress'},
                   {'path': publisher.PINNED_WORKFLOW}, {'path': publisher.DISCLOSURE_WORKFLOW},
                   {'head_repository': {'id': 2, 'full_name': REPOSITORY}},
                   {'head_repository': {'id': 1, 'full_name': 'other/Agent-OS'}},
                   {'repository': {'full_name': REPOSITORY}, 'head_repository': {'full_name': REPOSITORY}},
                   {'display_title': 'Android checks [arbitrary]'}, {'run_attempt': 0})
        for change in changes:
            candidate = run(); candidate.update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                publisher.verify_source(candidate, REPOSITORY, BRANCH, SHA)
        for branch in ('master', 'feature/test', 'agent/', 'agent/../master', 'agent/test\nmaster'):
            candidate = run(); candidate['head_branch'] = branch
            with self.subTest(branch=branch), self.assertRaises(ValueError):
                publisher.verify_source(candidate, REPOSITORY, branch, SHA)

    def test_review_artifacts_require_exact_origin_size_and_unique_identity(self):
        self.assertEqual([artifact()], publisher.screenshot_artifacts({'artifacts': [artifact()]}, run()))
        for changes in ({'id': 124}, {'head_sha': 'b' * 40}, {'head_branch': 'agent/other'},
                        {'repository_id': 2}, {'head_repository_id': 2}):
            candidate = artifact(); candidate['workflow_run'].update(changes)
            with self.subTest(origin=changes), self.assertRaises(ValueError):
                publisher.screenshot_artifacts({'artifacts': [candidate]}, run())
        for changes in ({'id': 0}, {'size_in_bytes': 0}, {'size_in_bytes': publisher.MAX_TOTAL_BYTES + 1},
                        {'workflow_run': {}}):
            candidate = artifact(); candidate.update(changes)
            with self.subTest(artifact=changes), self.assertRaises(ValueError):
                publisher.screenshot_artifacts({'artifacts': [candidate]}, run())
        with self.assertRaises(ValueError):
            publisher.screenshot_artifacts({'artifacts': [artifact(), artifact()]}, run())
        old = artifact(); old['name'] = 'screenshots-api-30-attempt-1'
        self.assertEqual([], publisher.screenshot_artifacts({'artifacts': [old]}, run()))

    def test_review_jobs_match_attempt_sha_and_artifact_upload_time(self):
        publisher.verify_review_jobs([job()], [artifact()], run())
        for changes in ({'run_id': 124}, {'run_attempt': 1}, {'head_sha': 'b' * 40},
                        {'status': 'in_progress'}, {'completed_at': '2026-10-28T00:00:30Z'},
                        {'started_at': None}):
            candidate = job(); candidate.update(changes)
            with self.subTest(job=changes), self.assertRaises(ValueError):
                publisher.verify_review_jobs([candidate], [artifact()], run())
        for jobs in ([], [job(), job()]):
            with self.subTest(jobs=jobs), self.assertRaises(ValueError):
                publisher.verify_review_jobs(jobs, [artifact()], run())

    def fixture(self, root, scope=collector.CONTROL_STATUS_SCOPE):
        source = root / 'incoming' / artifact()['name']
        (source / 'screenshots').mkdir(parents=True)
        (source / 'screenshots/frame.png').write_bytes(PNG)
        selection = collector.test_selection(scope)
        test_class, method = selection.split('#')
        capture = {'schemaVersion': 1, 'apiLevel': 30, 'matrixLabel': '30', 'actualSdkInt': 30,
                   'gradleExitCode': 0, 'captureStatus': 'collected', 'executionScope': scope,
                   'testSelection': selection, 'junit': {'reports': 1, 'tests': 1, 'failures': 0,
                   'errors': 0, 'skipped': 0, 'unreadableReports': 0, 'selection': selection,
                   'selectedTests': 1, 'unexpectedTests': 0}}
        (source / 'capture.json').write_text(json.dumps(capture))
        (source / 'screenshots/manifest.json').write_text(json.dumps({'tests': [
            {'testClass': test_class, 'testName': method, 'testResult': 'pass',
             'captures': [{'status': 'pass', 'png': 'frame.png'}]}]}))
        for name, value in (('run', run()), ('artifacts', {'artifacts': [artifact()]}),
                            ('jobs', {'jobs': [job()]})):
            (root / (name + '.json')).write_text(json.dumps(value))
        return source

    def command(self, root, mode='archive', opt_in=True):
        command = ['python3', str(SCRIPT), mode, '--repository', REPOSITORY,
                   '--run', str(root / 'run.json'), '--artifacts', str(root / 'artifacts.json')]
        if opt_in:
            command += ['--review-branch', BRANCH, '--expected-source-sha', SHA]
        if mode == 'archive':
            command += ['--jobs', str(root / 'jobs.json'), '--incoming', str(root / 'incoming')]
        else:
            command += ['--github-output', str(root / 'outputs')]
        return subprocess.run(command, cwd=root, capture_output=True, text=True)

    def test_cli_archives_real_branch_and_hashes_without_claiming_mainline_acceptance(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            verified = self.command(root, 'verify')
            self.assertEqual(0, verified.returncode, verified.stderr)
            self.assertIn('screenshot_artifact_ids=456', (root / 'outputs').read_text())
            result = self.command(root)
            self.assertEqual(0, result.returncode, result.stderr)
            output = root / 'screenshots/123-2'
            manifest = json.loads((output / 'manifest.json').read_text())
            self.assertEqual(BRANCH, manifest['sourceRun']['branch'])
            self.assertEqual(SHA, manifest['sourceRun']['commit'])
            self.assertEqual('review_branch', manifest['archiveMode'])
            self.assertFalse(manifest['mainlineAcceptance'])
            self.assertFalse(manifest['fullSuiteAcceptance'])
            self.assertEqual({'passed': 1, 'failed': 0, 'unverified': 7}, manifest['diagnosticCaseCounts'])
            self.assertIn('awaiting review; not mainline acceptance', (output / 'README.md').read_text())
            for record in manifest['apis'][0]['files']:
                content = (output / 'api-30' / record['path']).read_bytes()
                self.assertEqual(len(content), record['bytes'])
                self.assertEqual(hashlib.sha256(content).hexdigest(), record['sha256'])

    def test_cli_requires_opt_in_for_verify_and_archive_and_rejects_scope_relabel(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root)
            for mode in ('verify', 'archive'):
                result = self.command(root, mode, opt_in=False)
                self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / 'screenshots').exists())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); self.fixture(root, collector.QUALIFICATION_SCOPE)
            result = self.command(root)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('capture scope differs', result.stderr)

    def test_review_payload_cannot_introduce_source_code_or_symlinks(self):
        for kind in ('code', 'symlink'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temp:
                root = Path(temp); source = self.fixture(root)
                if kind == 'code':
                    (source / 'source.py').write_text('raise RuntimeError("must not execute")')
                else:
                    (source / 'linked.json').symlink_to(root / 'run.json')
                result = self.command(root)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root / 'screenshots/123-2/manifest.json').exists())


if __name__ == '__main__':
    unittest.main()
