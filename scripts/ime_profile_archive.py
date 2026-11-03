#!/usr/bin/env python3
"""Archive a bounded IME profiling capability probe, without promoting its scope.

Source-run metadata, immutable Git bytes and executing Python sources are checked
before producer code is loaded. Failed and absent captures remain unverified.
"""
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from types import ModuleType

REPOSITORY = 'ecojust/Agent-OS'
WORKFLOW = '.github/workflows/ime-profile-probe.yml'
JOB = 'probe'
ARTIFACT_PREFIX = 'ime-profile-probe-api-37.0-attempt-'
MAX_JSON = 4 * 1024 * 1024
MAX_COMMAND_JSON = 12 * 1024 * 1024
MAX_TRACE = 16 * 1024 * 1024
MAX_PAYLOAD = 32 * 1024 * 1024
MAX_ARTIFACT = 36 * 1024 * 1024
MAX_TOTAL = MAX_PAYLOAD + 3 * MAX_JSON
CONCLUSIONS = frozenset({'success', 'failure', 'cancelled', 'skipped', 'timed_out',
                        'action_required', 'neutral', 'stale', 'startup_failure'})
SOURCE_PATHS = frozenset({'scripts/ime_profile_probe.py', 'scripts/ime_method_trace.py',
    'scripts/ime_profile_archive.py', 'scripts/configure_emulator_phone.py', WORKFLOW})
PYTHON_PRODUCERS = frozenset(path for path in SOURCE_PATHS if path.endswith('.py'))
PAYLOAD_FILES = frozenset({'report.json', 'commands.json', 'method.trace'})


def require(condition, message):
    if not condition:
        raise ValueError(message)


def positive(value):
    return type(value) is int and 0 < value <= 2**63 - 1


def timestamp(value):
    require(isinstance(value, str) and len(value) <= 64, 'invalid timestamp')
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(parsed.utcoffset() is not None, 'timestamp has no timezone')
    return parsed


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate JSON key')
        result[key] = value
    return result


def json_depth(value, level=0):
    require(level <= 32, 'JSON nesting exceeds bound')
    values = value.values() if type(value) is dict else value if type(value) is list else ()
    for child in values:
        json_depth(child, level + 1)


def json_bytes(data, limit=MAX_JSON):
    require(len(data) <= limit, 'JSON exceeds byte bound')
    def finite(value):
        result = float(value)
        require(math.isfinite(result), 'nonfinite JSON number')
        return result
    try:
        value = json.loads(data, object_pairs_hook=no_duplicates, parse_float=finite,
            parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON number')))
        json_depth(value)
        return value
    except (UnicodeError, RecursionError, json.JSONDecodeError) as error:
        raise ValueError('invalid bounded JSON') from error


def read_bytes(path, limit=MAX_JSON):
    path = Path(path)
    require('..' not in path.parts and all(not part.is_symlink() for part in (path, *path.parents)),
            'linked or noncanonical evidence path')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode), 'nonregular evidence file')
        require(info.st_size <= limit, 'evidence exceeds byte bound')
        data = stream.read(limit + 1)
        require(len(data) == info.st_size and len(data) <= limit, 'evidence changed or exceeds bound')
        return data


def identity(data):
    return {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def canonical(name):
    require(type(name) is str and 0 < len(name) <= 256 and '\\' not in name
            and re.fullmatch(r'[A-Za-z0-9._/-]+', name), 'noncanonical relative path')
    parts = name.split('/')
    require(all(part not in ('', '.', '..') for part in parts) and not name.startswith('/'),
            'noncanonical relative path')
    return PurePosixPath(name)


def no_links(root, relative):
    path = Path(root)
    require('..' not in path.parts and all(not part.is_symlink() for part in (path, *path.parents)),
            'linked or noncanonical root')
    for part in canonical(relative).parts:
        path /= part
        require(not path.is_symlink(), 'linked path component')
    return path


def verify_source(run):
    require(type(run) is dict, 'invalid source run')
    repo, head = run.get('repository'), run.get('head_repository')
    require(type(repo) is dict and type(head) is dict and positive(repo.get('id'))
            and type(head.get('id')) is int and head['id'] == repo['id']
            and repo.get('full_name') == head.get('full_name') == REPOSITORY,
            'source repository differs or is a fork')
    require(run.get('event') == 'workflow_dispatch' and run.get('head_branch') == 'master'
            and run.get('path') == WORKFLOW and run.get('status') == 'completed',
            'source must be a completed manual IME-profile run on master')
    require(positive(run.get('id')) and positive(run.get('run_attempt'))
            and type(run.get('head_sha')) is str and re.fullmatch('[0-9a-f]{40}', run['head_sha'])
            and run.get('conclusion') in CONCLUSIONS, 'invalid completed source identity')
    require(timestamp(run.get('run_started_at')) <= timestamp(run.get('updated_at')),
            'invalid source time interval')
    return {'repository': REPOSITORY, 'commit': run['head_sha'],
            'runId': run['id'], 'attempt': run['run_attempt']}


def source_job(jobs, run):
    source = verify_source(run)
    pages = jobs if type(jobs) is list else [jobs]
    require(1 <= len(pages) <= 4, 'invalid jobs pagination')
    entries = []
    for page in pages:
        require(type(page) is dict and type(page.get('jobs')) is list, 'invalid jobs page')
        entries.extend(page['jobs'])
    require(1 <= len(entries) <= 16 and all(type(job) is dict for job in entries), 'invalid jobs')
    require(all(job.get('name') in {JOB, 'archive'} for job in entries), 'unexpected workflow job')
    require(len({job['name'] for job in entries}) == len(entries), 'duplicate workflow job')
    for job in entries:
        require(positive(job.get('id')) and job.get('status') == 'completed'
                and job.get('conclusion') in CONCLUSIONS and type(job.get('run_id')) is int
                and job['run_id'] == source['runId'] and type(job.get('run_attempt')) is int
                and job['run_attempt'] == source['attempt'] and job.get('head_sha') == source['commit'],
                'job belongs to another source or attempt')
        if job['name'] == 'archive':
            require(job['conclusion'] == 'skipped', 'source archive mode executed')
        if job['conclusion'] != 'skipped':
            require(timestamp(run['run_started_at']) <= timestamp(job.get('started_at'))
                    <= timestamp(job.get('completed_at')) <= timestamp(run['updated_at']),
                    'job outside source attempt')
    matches = [job for job in entries if job['name'] == JOB and job['conclusion'] != 'skipped']
    require(len(matches) == 1, 'no attempted profile probe job')
    return matches[0]


def eligible_artifact(index, run, jobs):
    source = verify_source(run)
    job = source_job(jobs, run)
    pages = index if type(index) is list else [index]
    require(1 <= len(pages) <= 20, 'invalid artifact pagination')
    entries = []
    for page in pages:
        require(type(page) is dict and type(page.get('artifacts')) is list, 'invalid artifact page')
        entries.extend(page['artifacts'])
    require(len(entries) <= 256 and all(type(item) is dict for item in entries), 'invalid artifacts')
    matches = [item for item in entries if item.get('name') == ARTIFACT_PREFIX + str(source['attempt'])]
    require(len(matches) <= 1, 'duplicate current-attempt artifact')
    if not matches:
        return None
    item = matches[0]
    require(job is not None and job['conclusion'] != 'skipped', 'artifact has no attempted job')
    origin = item.get('workflow_run')
    require(positive(item.get('id')) and positive(item.get('size_in_bytes'))
            and item['size_in_bytes'] <= MAX_ARTIFACT and type(item.get('expired')) is bool
            and type(origin) is dict, 'invalid artifact metadata')
    require(type(origin.get('id')) is int and origin['id'] == source['runId']
            and origin.get('head_sha') == source['commit'] and origin.get('head_branch') == 'master'
            and type(origin.get('repository_id')) is int and type(origin.get('head_repository_id')) is int
            and origin['repository_id'] == origin['head_repository_id'] == run['repository']['id'],
            'artifact source differs')
    require(timestamp(job['started_at']) <= timestamp(item.get('created_at'))
            <= timestamp(job['completed_at']), 'artifact outside source job')
    if item.get('updated_at') is not None:
        require(timestamp(item['created_at']) <= timestamp(item['updated_at'])
                <= timestamp(job['completed_at']), 'artifact update outside source job')
    return item


def evidence_path(source):
    return f"screenshots/ime-profile-probe-{source['runId']}-{source['attempt']}"


def git_bytes(repo, commit, name, limit):
    canonical(name)
    tree = subprocess.run(['git', '-C', str(repo), 'ls-tree', commit, '--', name],
                          check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10).stdout
    match = re.fullmatch(rb'(100644|100755) blob [0-9a-f]{40}\t' + re.escape(name.encode()) + rb'\n', tree)
    require(match is not None, 'producer path is missing or not a regular Git blob')
    spec = commit + ':' + name
    size = subprocess.check_output(['git', '-C', str(repo), 'cat-file', '-s', spec], timeout=10)
    require(size.strip().isdigit() and int(size) <= limit, 'producer Git blob exceeds bound')
    data = subprocess.check_output(['git', '-C', str(repo), 'cat-file', 'blob', spec], timeout=10)
    require(len(data) == int(size), 'producer Git blob size differs')
    return data


def load_producer(report, source, repo):
    """Verify the whole catalog before compiling any producer from its source.

    Fresh compile avoids treating a preloaded module or timestamp-based .pyc as
    proof that the reviewed source bytes are what validation executes.
    """
    require(type(report) is dict and report.get('source') == source, 'probe source differs')
    declared = report.get('sourceFiles')
    require(type(declared) is dict and set(declared) == SOURCE_PATHS,
            'producer source catalog differs')
    scripts = Path(__file__).resolve().parent
    verified = {}
    for name in sorted(SOURCE_PATHS):
        expected = declared[name]
        require(type(expected) is dict and set(expected) == {'bytes', 'sha256'}
                and positive(expected['bytes']) and expected['bytes'] <= MAX_JSON
                and type(expected['sha256']) is str and re.fullmatch('[0-9a-f]{64}', expected['sha256']),
                'invalid producer identity')
        data = git_bytes(repo, source['commit'], name, MAX_JSON)
        require(identity(data) == expected and read_bytes(no_links(repo, name)) == data,
                'producer source bytes differ from Git')
        if name in PYTHON_PRODUCERS:
            module = Path(name).stem
            spec = importlib.util.find_spec(module)
            actual = scripts / (module + '.py')
            require(spec is not None and spec.origin is not None
                    and Path(spec.origin).resolve() == actual,
                    'producer module resolves outside verified scripts')
            require(read_bytes(actual) == data, 'executing producer bytes differ')
            if module in sys.modules:
                require(Path(sys.modules[module].__file__).resolve() == actual,
                        'loaded producer module differs')
            verified[module] = (actual, data, spec)
    loaded, saved = {}, {}
    try:
        # The producer imports only stdlib and this verified parser. The AVD
        # helper is independently source-bound because the workflow executes it.
        for name in ('ime_method_trace', 'ime_profile_probe'):
            actual, data, spec = verified[name]
            saved[name] = sys.modules.get(name)
            module = ModuleType(name)
            module.__file__ = str(actual)
            module.__spec__ = spec
            module.__package__ = ''
            sys.modules[name] = module
            exec(compile(data, str(actual), 'exec', dont_inherit=True), module.__dict__)
            loaded[name] = module
        probe = loaded['ime_profile_probe']
        require(set(probe.SOURCE_PATHS) == SOURCE_PATHS and set(probe.FILES) == PAYLOAD_FILES,
                'producer catalog differs from archive')
        require(probe.source_files(repo) == declared, 'producer checkout identity differs')
        return probe, loaded['ime_method_trace']
    finally:
        for name, previous in saved.items():
            if previous is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = previous


def file_limit(name):
    require(name in PAYLOAD_FILES, 'unexpected payload file')
    return MAX_TRACE if name == 'method.trace' else MAX_COMMAND_JSON if name == 'commands.json' else MAX_JSON


def scan_payload(directory):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'payload directory missing or linked')
    contents = {}
    total = 0
    for item in directory.iterdir():
        require(not item.is_symlink() and item.is_file() and item.name in PAYLOAD_FILES,
                'unexpected or linked payload file')
        data = read_bytes(item, file_limit(item.name))
        total += len(data)
        require(total <= MAX_PAYLOAD, 'payload exceeds total bound')
        contents[item.name] = data
    require('report.json' in contents, 'prepared probe report missing')
    return contents


def checked_destination(root, relative):
    root = Path(root)
    require(root.is_dir() and not root.is_symlink(), 'output root missing or linked')
    destination = no_links(root, relative)
    require(not destination.exists(), 'archive already exists')
    return destination


def archive(incoming, output, run, jobs, artifact_index, output_root=None, source_repo=None):
    source = verify_source(run)
    job = source_job(jobs, run)
    artifact = eligible_artifact(artifact_index, run, jobs)
    root = Path(output_root or Path.cwd())
    producer_repo = Path(source_repo or root)
    relative = evidence_path(source)
    require(str(output) == relative, 'archive path differs from source')
    destination = checked_destination(root, relative)
    incoming = Path(incoming)
    require('..' not in incoming.parts
            and all(not part.is_symlink() for part in (incoming, *incoming.parents)),
            'incoming container path is linked or noncanonical')
    available = artifact is not None and not artifact['expired']
    if incoming.exists():
        require(incoming.is_dir(), 'incoming is not a directory')
        expected = {artifact['name']} if available else set()
        require({item.name for item in incoming.iterdir()} == expected, 'incoming artifact set differs')
    else:
        require(not available, 'artifact download missing')
    contents, report = {}, None
    if available:
        directory = no_links(incoming, artifact['name'])
        initial = json_bytes(read_bytes(no_links(directory, 'report.json')))
        probe, _ = load_producer(initial, source, producer_repo)
        contents = scan_payload(directory)
        require(json_bytes(contents['report.json']) == initial, 'probe changed during source verification')
        report = probe.validate_evidence(contents, source)
        require(type(report) is dict and report == initial
                and report.get('status') in {'recorded', 'unverified'}, 'invalid probe status or validation mutation')
        for key in ('createdAt', 'completedAt'):
            if report.get(key) is not None:
                require(timestamp(job['started_at']) <= timestamp(report[key])
                        <= timestamp(artifact['created_at']), 'probe report outside job/artifact interval')
        if report.get('completedAt') is not None:
            require(timestamp(report['createdAt']) <= timestamp(report['completedAt']), 'probe times reversed')
        # Publish precisely the same bytes, including partial native files, that
        # the frozen producer validated. No source or derived receipt is repaired.
        require(scan_payload(directory) == contents, 'payload changed during validation')
    reported = 'unverified' if report is None else report['status']
    status = reported
    if run['conclusion'] != 'success' or job['conclusion'] != 'success':
        status = 'unverified'
    manifest = {
        'schemaVersion': 1, 'kind': 'ime-profile-probe-archive', 'source': source,
        'workflow': WORKFLOW,
        'runUrl': f"https://github.com/{REPOSITORY}/actions/runs/{source['runId']}",
        'sourceConclusion': run['conclusion'],
        'probeJob': {'id': job['id'], 'conclusion': job['conclusion']},
        'archivedAt': datetime.now(timezone.utc).isoformat(), 'status': status,
        'capture': {'reportedStatus': reported,
            'failureReason': None if report is None else report.get('failureReason'),
            'tracePresent': 'method.trace' in contents},
        'profile': None if report is None else copy.deepcopy(report.get('profile')),
        'analysis': None if report is None else copy.deepcopy(report.get('analysis')),
        'summary': {'expectedProbes': 1, 'recordedProbes': int(status == 'recorded'),
            'unverifiedProbes': int(status != 'recorded'), 'apiLevel': 37},
        'scope': {'diagnosticOnly': True, 'nonvisual': True, 'screenshotsExpected': 0,
            'partialTracePromoted': False, 'traceCaptureProvesResetCaller': False,
            'originalNotesCasesRerun': False, 'originalKeyboardComparisonRerun': False,
            'applicationCompatibilityAcceptance': False},
        'artifact': {'status': 'archived' if available else 'expired' if artifact else 'missing',
            'name': ARTIFACT_PREFIX + str(source['attempt']),
            'id': None if artifact is None else artifact['id']}, 'files': []}
    destination.parent.mkdir(parents=True, exist_ok=True)
    checked_destination(root, relative)
    temporary = Path(tempfile.mkdtemp(prefix='.ime-profile-probe-', dir=destination.parent))
    try:
        copied = dict(contents)
        for name, value in [('source-run.json', run), ('source-jobs.json', jobs),
                            ('source-artifacts.json', artifact_index)]:
            data = (json.dumps(value, ensure_ascii=True, indent=2, allow_nan=False) + '\n').encode()
            require(len(data) <= MAX_JSON, 'source metadata exceeds bound')
            copied[name] = data
        require(sum(map(len, copied.values())) <= MAX_TOTAL, 'archive exceeds total byte bound')
        for name, data in sorted(copied.items()):
            (temporary / name).write_bytes(data)
            manifest['files'].append({'path': name, **identity(data)})
        encoded = (json.dumps(manifest, ensure_ascii=True, indent=2, allow_nan=False) + '\n').encode()
        require(len(encoded) <= MAX_JSON, 'manifest exceeds bound')
        (temporary / 'manifest.json').write_bytes(encoded)
        checked_destination(root, relative)
        temporary.rename(destination)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['validate-source', 'archive'])
    parser.add_argument('--run-json', '--source-run-json', dest='run_json', type=Path, required=True)
    parser.add_argument('--jobs-json', '--source-jobs-json', dest='jobs_json', type=Path, required=True)
    parser.add_argument('--artifact-json', '--artifact-index', dest='artifact_json', type=Path, required=True)
    parser.add_argument('--artifact-dir', '--incoming', dest='artifact_dir', type=Path)
    parser.add_argument('--output-root', '--repo', dest='output_root', type=Path, default=Path.cwd())
    parser.add_argument('--source-repo', type=Path)
    parser.add_argument('--output')
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args(argv)
    try:
        run, jobs, index = (json_bytes(read_bytes(path)) for path in
                           (args.run_json, args.jobs_json, args.artifact_json))
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
            require(args.artifact_dir is not None, 'archive requires artifact directory')
            result = archive(args.artifact_dir, args.output or evidence_path(source), run, jobs, index,
                             args.output_root, args.source_repo)
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print('IME-profile archive rejected: ' + str(error))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
