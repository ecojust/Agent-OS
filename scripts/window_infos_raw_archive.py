#!/usr/bin/env python3
"""Archive one trusted API37 raw-window diagnostic without changing its result.

This module performs no device operation, network request, or Git mutation.
The fixture screenshot is a separate, later UI observation, not a raw-window snapshot.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile

import third_party_smoke_archive as bounds
import window_infos_raw as probe

REPOSITORY = 'ecojust/Agent-OS'
WORKFLOW = '.github/workflows/window-infos-raw.yml'
JOB = 'probe'
ARTIFACT_PREFIX = 'window-infos-raw-api-37.0-attempt-'
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
MAX_ARTIFACT_BYTES = 20 * 1024 * 1024
MAX_TOTAL_BYTES = MAX_PAYLOAD_BYTES + 3 * MAX_FILE_BYTES
CONCLUSIONS = frozenset({'success', 'failure', 'cancelled', 'skipped', 'timed_out',
                        'action_required', 'neutral', 'stale', 'startup_failure'})
require = bounds.require


def json_bytes(data):
    require(len(data) <= MAX_FILE_BYTES, 'JSON exceeds size limit')
    def finite_float(value):
        number = float(value)
        require(math.isfinite(number), 'nonfinite JSON number')
        return number
    try:
        value = json.loads(data, object_pairs_hook=bounds.no_duplicates,
                           parse_float=finite_float,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON')))
        bounds.json_depth(value)
        return value
    except (UnicodeError, RecursionError, json.JSONDecodeError) as error:
        raise ValueError('invalid bounded JSON') from error


def read_bytes(path, limit=MAX_FILE_BYTES):
    path = Path(path)
    require(not path.is_symlink(), 'linked files are not evidence')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode), 'evidence must be a regular file')
        require(info.st_size <= limit, 'evidence file exceeds size limit')
        data = stream.read(limit + 1)
        require(len(data) == info.st_size and len(data) <= limit,
                'evidence changed or exceeded limit')
    return data


def read_json(path):
    return json_bytes(read_bytes(path))


def verify_source(run):
    require(type(run) is dict, 'invalid source run')
    repository, head = run.get('repository'), run.get('head_repository')
    require(type(repository) is dict and type(head) is dict
            and repository.get('full_name') == head.get('full_name') == REPOSITORY
            and bounds.positive_int(repository.get('id'))
            and repository['id'] == head.get('id') and type(head.get('id')) is int,
            'source repository differs or is a fork')
    require(run.get('event') == 'workflow_dispatch' and run.get('head_branch') == 'master'
            and run.get('path') == WORKFLOW and run.get('status') == 'completed',
            'source must be a completed manual raw-window run on master')
    require(bounds.positive_int(run.get('id')) and bounds.positive_int(run.get('run_attempt'))
            and isinstance(run.get('head_sha'), str) and re.fullmatch('[0-9a-f]{40}', run['head_sha']),
            'invalid source identity')
    require(run.get('conclusion') in CONCLUSIONS, 'completed source conclusion missing or unknown')
    require(bounds.timestamp(run.get('run_started_at')) <= bounds.timestamp(run.get('updated_at')),
            'invalid source time interval')
    return {'repository': REPOSITORY, 'runId': run['id'], 'attempt': run['run_attempt'],
            'commit': run['head_sha']}


def source_job(jobs, run):
    source = verify_source(run)
    pages = jobs if isinstance(jobs, list) else [jobs]
    require(1 <= len(pages) <= 4, 'invalid job pagination')
    entries = []
    for page in pages:
        require(type(page) is dict and type(page.get('jobs')) is list, 'invalid jobs page')
        entries.extend(page['jobs'])
    require(len(entries) <= 32 and all(type(job) is dict for job in entries), 'invalid job entries')
    test_jobs = [job for job in entries if job.get('name') in {'build', JOB}]
    require(len({job['name'] for job in test_jobs}) == len(test_jobs), 'duplicate test-mode job')
    archive_jobs = [job for job in entries if job.get('name') == 'archive']
    require(len(archive_jobs) <= 1 and all(job.get('conclusion') == 'skipped' for job in archive_jobs),
            'archive-only run is not a diagnostic source')
    require(test_jobs and any(job.get('conclusion') != 'skipped' for job in test_jobs),
            'no executed test-mode job in source run')
    for candidate in test_jobs:
        require(bounds.positive_int(candidate.get('id')) and candidate.get('status') == 'completed'
                and candidate.get('conclusion') in CONCLUSIONS
                and type(candidate.get('run_id')) is int and candidate['run_id'] == source['runId']
                and type(candidate.get('run_attempt')) is int and candidate['run_attempt'] == source['attempt']
                and candidate.get('head_sha') == source['commit'], 'test-mode job identity differs')
        if candidate['conclusion'] != 'skipped':
            require(bounds.timestamp(run['run_started_at']) <= bounds.timestamp(candidate.get('started_at'))
                    <= bounds.timestamp(candidate.get('completed_at')) <= bounds.timestamp(run['updated_at']),
                    'test-mode job outside source attempt')
    matches = [job for job in entries if job.get('name') == JOB]
    require(len(matches) <= 1, 'duplicate probe job')
    if not matches:
        return None
    job = matches[0]
    require(bounds.positive_int(job.get('id')) and job.get('status') == 'completed'
            and job.get('conclusion') in CONCLUSIONS,
            'probe job must be completed')
    require(type(job.get('run_id')) is int and job['run_id'] == source['runId']
            and type(job.get('run_attempt')) is int and job['run_attempt'] == source['attempt']
            and job.get('head_sha') == source['commit'], 'probe job belongs to another source or attempt')
    # GitHub skipped jobs can have completed_at earlier than started_at; they did not execute.
    if job['conclusion'] != 'skipped':
        require(bounds.timestamp(run['run_started_at']) <= bounds.timestamp(job.get('started_at'))
                <= bounds.timestamp(job.get('completed_at')) <= bounds.timestamp(run['updated_at']),
                'probe job is outside source attempt')
    return job


def eligible_artifact(index, run, jobs):
    source = verify_source(run)
    job = source_job(jobs, run)
    expected = ARTIFACT_PREFIX + str(source['attempt'])
    matches = []
    for item in bounds.artifact_entries(index):
        require(type(item) is dict, 'invalid artifact metadata')
        if item.get('name') != expected:
            continue
        require(not matches, 'duplicate current-attempt raw-window artifact')
        require(job is not None, 'artifact has no current-attempt probe job')
        require(job['conclusion'] != 'skipped', 'skipped probe job cannot produce an artifact')
        origin = item.get('workflow_run')
        require(type(origin) is dict and bounds.positive_int(item.get('id'))
                and bounds.positive_int(item.get('size_in_bytes')) and item['size_in_bytes'] <= MAX_ARTIFACT_BYTES
                and type(item.get('expired')) is bool, 'invalid artifact identity, size or expiry')
        require(type(origin.get('id')) is int and origin.get('id') == source['runId']
                and origin.get('head_sha') == source['commit'] and origin.get('head_branch') == 'master'
                and type(origin.get('repository_id')) is int and type(origin.get('head_repository_id')) is int
                and origin.get('repository_id') == origin.get('head_repository_id') == run['repository']['id'],
                'artifact origin differs from trusted source')
        require(bounds.timestamp(job['started_at']) <= bounds.timestamp(item.get('created_at'))
                <= bounds.timestamp(job['completed_at']), 'artifact outside current probe job')
        if item.get('updated_at') is not None:
            require(bounds.timestamp(item['created_at']) <= bounds.timestamp(item['updated_at'])
                    <= bounds.timestamp(job['completed_at']), 'invalid artifact update time')
        matches.append(item)
    return matches[0] if matches else None


def evidence_path(source):
    return f"screenshots/window-infos-raw-{source['runId']}-{source['attempt']}"


def scan_payload(directory):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'artifact directory missing or linked')
    entries = list(directory.iterdir())
    require(len(entries) <= len(probe.FILES), 'unexpected payload entries')
    payloads, contents, total = {}, {}, 0
    for item in entries:
        require(item.name in probe.FILES and not item.is_symlink() and item.is_file(),
                'unexpected or linked payload path')
        limit = probe.MAX_PNG if item.name == 'screenshot.png' else probe.MAX_JAR if item.name == 'probe.jar' else MAX_FILE_BYTES
        data = read_bytes(item, limit)
        total += len(data)
        require(total <= MAX_PAYLOAD_BYTES, 'payload exceeds total byte limit')
        contents[item.name] = data
        payloads[item.name] = json_bytes(data) if item.name.endswith('.json') else data
    require('report.json' in payloads, 'prepared report is missing')
    return payloads, contents


def checked_destination(repo, relative):
    repo = Path(repo).resolve()
    destination = repo / relative
    current = repo
    for part in Path(relative).parts:
        require(part not in ('', '.', '..'), 'invalid archive path')
        current = current / part
        require(not current.is_symlink(), 'archive path contains a symlink')
    require(not destination.exists(), 'archive output already exists')
    return destination


def archive(incoming, output, run, jobs, artifact_index, repo=None):
    source = verify_source(run)
    job = source_job(jobs, run)
    artifact = eligible_artifact(artifact_index, run, jobs)
    repo = Path(repo or Path.cwd()).resolve()
    relative = evidence_path(source)
    require(str(output) == relative, 'archive path does not match source run')
    destination = checked_destination(repo, relative)
    incoming = Path(incoming)
    require(not incoming.is_symlink(), 'incoming directory is linked')
    available = artifact is not None and not artifact['expired']
    if incoming.exists():
        require(incoming.is_dir(), 'incoming is not a directory')
        children = list(incoming.iterdir())
        expected = {artifact['name']} if available else set()
        require({child.name for child in children} == expected,
                'unexpected incoming artifact or missing current artifact')
    else:
        require(not available, 'current artifact download missing')
    contents = {}
    report_status = 'unverified'
    if available:
        payloads, contents = scan_payload(incoming / artifact['name'])
        report = probe.validate_evidence(payloads, source)
        probe.verify_source_files(report, repo)
        require(type(report) is dict and report.get('status') in {'passed', 'failed', 'unverified'},
                'invalid validated raw-window report status')
        report_status = report['status']
        if report.get('createdAt') is not None:
            require(bounds.timestamp(job['started_at']) <= bounds.timestamp(report['createdAt'])
                    <= bounds.timestamp(artifact['created_at']), 'report outside probe job interval')
        if report.get('completedAt') is not None:
            require(bounds.timestamp(report['createdAt']) <= bounds.timestamp(report['completedAt'])
                    <= bounds.timestamp(artifact['created_at']), 'report completed after artifact upload')
        artifact_status = 'archived'
    else:
        artifact_status = 'expired' if artifact is not None else 'missing'
    status = report_status
    if status == 'passed' and (run['conclusion'] != 'success' or job['conclusion'] != 'success'):
        status = 'failed'
    manifest = {
        'schemaVersion': 1, 'kind': 'window-infos-raw-archive', 'source': source,
        'workflow': WORKFLOW, 'runUrl': f"https://github.com/{REPOSITORY}/actions/runs/{source['runId']}",
        'sourceConclusion': run['conclusion'],
        'probeJob': None if job is None else {'id': job['id'], 'conclusion': job['conclusion']},
        'archivedAt': datetime.now(timezone.utc).isoformat(),
        'status': status, 'diagnosticReportStatus': report_status,
        'summary': {'expectedCases': 1, 'passedCases': int(status == 'passed'), 'apiLevel': 37},
        'scope': {'rawWindowDiagnosticOnly': True, 'applicationCompatibilityAcceptance': False,
                  'originalNotesCasesRerun': False, 'productionApkCapabilityAcceptance': False},
        'capture': {'status': 'recorded' if 'screenshot.png' in contents else 'not_recorded',
                    'screenshots': 'screenshot.png' in contents, 'video': False,
                    'meaning': 'Later synthetic fixture UI capture; not an atomic raw-window snapshot or Binder identity proof.'},
        'artifact': {'status': artifact_status, 'name': ARTIFACT_PREFIX + str(source['attempt']),
                     'id': None if artifact is None else artifact['id']}, 'files': []}
    destination.parent.mkdir(parents=True, exist_ok=True)
    checked_destination(repo, relative)
    temporary = Path(tempfile.mkdtemp(prefix='.window-infos-raw-', dir=destination.parent))
    try:
        for name, data in sorted(contents.items()):
            (temporary / name).write_bytes(data)
            manifest['files'].append({'path': name, 'bytes': len(data),
                                      'sha256': hashlib.sha256(data).hexdigest()})
        for name, value in [('source-run.json', run), ('source-jobs.json', jobs),
                            ('source-artifacts.json', artifact_index)]:
            data = (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + '\n').encode()
            require(len(data) <= MAX_FILE_BYTES, 'source metadata exceeds bound')
            (temporary / name).write_bytes(data)
            manifest['files'].append({'path': name, 'bytes': len(data),
                                      'sha256': hashlib.sha256(data).hexdigest()})
        require(sum(item['bytes'] for item in manifest['files']) <= MAX_TOTAL_BYTES,
                'archive payload and source metadata exceed total bound')
        encoded = (json.dumps(manifest, ensure_ascii=True, indent=2, allow_nan=False) + '\n').encode()
        require(len(encoded) <= MAX_FILE_BYTES, 'archive manifest exceeds bound')
        (temporary / 'manifest.json').write_bytes(encoded)
        checked_destination(repo, relative)
        temporary.rename(destination)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['validate-source', 'archive'])
    parser.add_argument('--source-run-json', type=Path, required=True)
    parser.add_argument('--source-jobs-json', type=Path, required=True)
    parser.add_argument('--artifact-index', type=Path, required=True)
    parser.add_argument('--incoming', type=Path)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--output')
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args(argv)
    try:
        run, jobs, index = read_json(args.source_run_json), read_json(args.source_jobs_json), read_json(args.artifact_index)
        source = verify_source(run)
        artifact = eligible_artifact(index, run, jobs)
        if args.command == 'validate-source':
            result = {**source, 'evidencePath': evidence_path(source),
                      'artifactIds': str(artifact['id']) if artifact is not None and not artifact['expired'] else ''}
            if args.github_output:
                require(not args.github_output.is_symlink(), 'GitHub output is linked')
                with args.github_output.open('a', encoding='utf-8') as stream:
                    for key, value in result.items():
                        stream.write(f'{key}={value}\n')
        else:
            require(args.incoming is not None, 'archive requires incoming directory')
            result = archive(args.incoming, args.output or evidence_path(source), run, jobs, index, args.repo)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except (ValueError, OSError) as error:
        print('Raw-window archive rejected: ' + str(error))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
