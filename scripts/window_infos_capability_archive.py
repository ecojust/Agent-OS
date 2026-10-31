#!/usr/bin/env python3
"""Archive one source-bound, nonvisual WindowInfos capability receipt.

No device operation, network request, or Git mutation is performed here.
Missing and failed runs remain non-passing evidence.
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
import window_infos_capability as probe

REPOSITORY = 'ecojust/Agent-OS'
WORKFLOW = '.github/workflows/window-infos-capability.yml'
ARTIFACT_PREFIX = 'window-infos-capability-api-37.0-attempt-'
FILES = frozenset({'report.json', 'capture.json', 'commands.json', 'instrumentation.json'})
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 8 * 1024 * 1024
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


def read_bytes(path):
    path = Path(path)
    require(not path.is_symlink(), 'linked files are not evidence')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode), 'evidence must be a regular file')
        require(info.st_size <= MAX_FILE_BYTES, 'evidence file exceeds size limit')
        data = stream.read(MAX_FILE_BYTES + 1)
        require(len(data) == info.st_size and len(data) <= MAX_FILE_BYTES, 'evidence changed or exceeded limit')
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
            'source must be a completed manual capability run on master')
    require(bounds.positive_int(run.get('id')) and bounds.positive_int(run.get('run_attempt'))
            and isinstance(run.get('head_sha'), str) and re.fullmatch('[0-9a-f]{40}', run['head_sha']),
            'invalid source identity')
    require(run.get('conclusion') in {'success', 'failure', 'cancelled', 'skipped', 'timed_out',
                                     'action_required', 'neutral', 'stale', 'startup_failure'},
            'completed source conclusion missing or unknown')
    require(bounds.timestamp(run.get('run_started_at')) <= bounds.timestamp(run.get('updated_at')),
            'invalid source time interval')
    return {'repository': REPOSITORY, 'runId': run['id'], 'attempt': run['run_attempt'], 'commit': run['head_sha']}


def eligible_artifact(index, run):
    source = verify_source(run)
    expected = ARTIFACT_PREFIX + str(source['attempt'])
    matches = []
    for item in bounds.artifact_entries(index):
        require(type(item) is dict, 'invalid artifact metadata')
        if item.get('name') != expected:
            continue
        require(not matches, 'duplicate current-attempt capability artifact')
        origin = item.get('workflow_run')
        require(type(origin) is dict and bounds.positive_int(item.get('id'))
                and bounds.positive_int(item.get('size_in_bytes')) and item['size_in_bytes'] <= MAX_TOTAL_BYTES
                and type(item.get('expired')) is bool, 'invalid artifact identity, size or expiry')
        require(type(origin.get('id')) is int and origin.get('id') == source['runId']
                and origin.get('head_sha') == source['commit'] and origin.get('head_branch') == 'master'
                and type(origin.get('repository_id')) is int and type(origin.get('head_repository_id')) is int
                and origin.get('repository_id') == origin.get('head_repository_id') == run['repository']['id'],
                'artifact origin differs from trusted source')
        require(bounds.timestamp(run['run_started_at']) <= bounds.timestamp(item.get('created_at'))
                <= bounds.timestamp(run['updated_at']), 'artifact outside current source attempt')
        if item.get('updated_at') is not None:
            require(bounds.timestamp(item['created_at']) <= bounds.timestamp(item['updated_at'])
                    <= bounds.timestamp(run['updated_at']), 'invalid artifact update time')
        matches.append(item)
    return matches[0] if matches else None


def evidence_path(source):
    return f"docs/validation/diagnostics/window-infos-capability-{source['runId']}-{source['attempt']}"


def scan_payload(directory):
    require(directory.is_dir() and not directory.is_symlink(), 'artifact directory missing or linked')
    entries = list(directory.iterdir())
    require(len(entries) <= len(FILES), 'unexpected payload entries')
    payloads, contents, total = {}, {}, 0
    for item in entries:
        require(item.name in FILES and not item.is_symlink() and item.is_file(), 'unexpected or linked payload path')
        data = read_bytes(item)
        total += len(data)
        require(total <= MAX_TOTAL_BYTES, 'payload exceeds total byte limit')
        payloads[item.name], contents[item.name] = json_bytes(data), data
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


def archive(incoming, output, run, artifact_index, repo=None):
    source = verify_source(run)
    artifact = eligible_artifact(artifact_index, run)
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
        require({child.name for child in children} == expected, 'unexpected incoming artifact or missing current artifact')
    else:
        require(not available, 'current artifact download missing')
    contents = {}
    if available:
        payloads, contents = scan_payload(incoming / artifact['name'])
        summary = probe.validate_evidence(payloads, source)
        probe.verify_source_files(payloads['report.json'], repo)
        require(type(summary) is dict and summary.get('status') in {'recorded', 'failed', 'unverified'}
                and summary.get('expectedTests') == 1 and type(summary.get('expectedTests')) is int
                and summary.get('recordedTests') in (0, 1) and type(summary.get('recordedTests')) is int
                and summary.get('apiLevel') == 37 and summary.get('nonvisual') is True,
                'invalid validated capability summary')
        report = payloads['report.json']
        if report.get('createdAt') is not None:
            require(bounds.timestamp(run['run_started_at']) <= bounds.timestamp(report['createdAt'])
                    <= bounds.timestamp(run['updated_at']), 'report outside source run interval')
        if report.get('completedAt') is not None:
            require(bounds.timestamp(report['createdAt']) <= bounds.timestamp(report['completedAt'])
                    <= bounds.timestamp(artifact['created_at']), 'report completed after artifact upload')
        artifact_status = 'archived'
    else:
        summary = {'status': 'unverified', 'expectedTests': 1, 'recordedTests': 0,
                   'apiLevel': 37, 'nonvisual': True, 'capability': None}
        artifact_status = 'expired' if artifact is not None else 'missing'
    manifest = {'schemaVersion': 1, 'kind': 'window-infos-capability-archive', 'source': source,
                'workflow': WORKFLOW, 'runUrl': f"https://github.com/{REPOSITORY}/actions/runs/{source['runId']}",
                'sourceConclusion': run['conclusion'], 'archivedAt': datetime.now(timezone.utc).isoformat(),
                'summary': summary, 'applicationCompatibilityAcceptance': False,
                'capture': {'status': 'nonvisual', 'screenshots': False, 'video': False},
                'artifact': {'status': artifact_status, 'name': ARTIFACT_PREFIX + str(source['attempt']),
                             'id': None if artifact is None else artifact['id']}, 'files': []}
    destination.parent.mkdir(parents=True, exist_ok=True)
    checked_destination(repo, relative)
    temporary = Path(tempfile.mkdtemp(prefix='.window-infos-', dir=destination.parent))
    try:
        for name, data in sorted(contents.items()):
            target = temporary / 'payload' / name
            target.parent.mkdir(exist_ok=True)
            target.write_bytes(data)
            manifest['files'].append({'path': 'payload/' + name, 'bytes': len(data),
                                      'sha256': hashlib.sha256(data).hexdigest()})
        for name, value in [('source-run.json', run), ('source-artifacts.json', artifact_index)]:
            data = (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + '\n').encode()
            require(len(data) <= MAX_FILE_BYTES, 'source metadata exceeds bound')
            (temporary / name).write_bytes(data)
            manifest['files'].append({'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        require(sum(item['bytes'] for item in manifest['files']) <= MAX_TOTAL_BYTES,
                'archive payload and source metadata exceed total bound')
        encoded_manifest = (json.dumps(manifest, ensure_ascii=True, indent=2, allow_nan=False) + '\n').encode()
        require(len(encoded_manifest) <= MAX_FILE_BYTES, 'archive manifest exceeds bound')
        (temporary / 'manifest.json').write_bytes(encoded_manifest)
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
    parser.add_argument('--artifact-index', type=Path, required=True)
    parser.add_argument('--incoming', type=Path)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--output')
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args(argv)
    try:
        run, index = read_json(args.source_run_json), read_json(args.artifact_index)
        source = verify_source(run)
        artifact = eligible_artifact(index, run)
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
            result = archive(args.incoming, args.output or evidence_path(source), run, index, args.repo)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except (ValueError, OSError) as error:
        print('Capability archive rejected: ' + str(error))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
