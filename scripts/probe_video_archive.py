#!/usr/bin/env python3
"""Archive source-bound API35 real-APK probe recordings and their original results.

This does not operate devices or update progress. A recording and the fixed probe
have separate outcomes; neither certifies the other APIs or a release matrix.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile

import demo_video
import pinned_calculator_page as pinned
import probe_video
import publish_test_evidence as publisher
import third_party_smoke_archive as bounded
import third_party_smoke_set as appset
import third_party_smoke_set_archive as appset_archive

REPOSITORY = 'ecojust/Agent-OS'
MAX_VIDEO_BYTES = 100_000_000
MAX_RAW_BYTES = 70_000_000
MAX_TOTAL_BYTES = 200_000_000
PROFILES = {
    '.github/workflows/third-party-smoke.yml': {
        'profile': 'third-party-app-set', 'job': 'probe (35)',
        'probePrefix': 'third-party-smoke-v4-api-35-attempt-', 'expectedTests': 4,
        'matrixExpectedTests': 32,
        'scope': 'Actual official Calculator 1.4.0 and Notes 1.7.0 UI operations on API35 in Agent off/idle states; no Agent execution, upgrade, physical-device or release acceptance.'},
    '.github/workflows/pinned-calculator-page.yml': {
        'profile': 'pinned-calculator-page', 'job': 'page-predicate (35)',
        'probePrefix': 'screenshots-api-35-attempt-', 'expectedTests': 5,
        'matrixExpectedTests': 20,
        'scope': 'Actual official Calculator 1.4.0 pages and production accessibility observe/page predicate on API35; no production image capability, visualAcceptance build, model task or physical-device acceptance.'},
}
require = bounded.require
APPSET_CLASS = 'dev.agentos.compat.ApplicationSetCompatibilityInstrumentedTest'
APPSET_METHODS = {
    ('calculator-1.4.0', 'off'): 'calculatorWithAgentDisabled',
    ('calculator-1.4.0', 'idle'): 'calculatorWithAgentBoundAndIdle',
    ('notes-1.7.0', 'off'): 'notesWithAgentDisabled',
    ('notes-1.7.0', 'idle'): 'notesWithAgentBoundAndIdle',
}


def entries(value, key):
    pages = value if isinstance(value, list) else [value]
    require(len(pages) <= 20, 'Too many metadata pages')
    result = []
    for page in pages:
        require(isinstance(page, dict) and isinstance(page.get(key), list), 'Invalid metadata page: ' + key)
        result.extend(page[key])
    require(len(result) <= 2000 and all(isinstance(item, dict) for item in result), 'Invalid metadata entries')
    return result


def select(run, jobs, artifacts):
    require(isinstance(run, dict) and run.get('path') in PROFILES, 'Only the two fixed recording workflows are supported')
    profile = PROFILES[run['path']]
    repository, head = run.get('repository', {}), run.get('head_repository', {})
    require(isinstance(repository, dict) and isinstance(head, dict)
            and repository.get('full_name') == head.get('full_name') == REPOSITORY
            and bounded.positive_int(repository.get('id')) and repository['id'] == head.get('id'),
            'Recording source must be this repository, not a fork')
    require(run.get('event') == 'workflow_dispatch' and run.get('head_branch') == 'master'
            and run.get('status') == 'completed' and bounded.text(run.get('conclusion'), 64)
            and re.fullmatch(r'[0-9a-f]{40}', str(run.get('head_sha', '')))
            and bounded.positive_int(run.get('id')) and bounded.positive_int(run.get('run_attempt')),
            'Invalid completed manual recording source')
    start, end = bounded.timestamp(run.get('run_started_at')), bounded.timestamp(run.get('updated_at'))
    require(start <= end, 'Source run time interval is reversed')
    source = {'repository': REPOSITORY, 'commit': run['head_sha'], 'runId': run['id'], 'attempt': run['run_attempt']}
    candidates = [job for job in entries(jobs, 'jobs') if job.get('name') == profile['job']]
    require(len(candidates) == 1, 'Missing or duplicate fixed API35 job')
    job = candidates[0]
    require(job.get('status') == 'completed' and bounded.positive_int(job.get('id'))
            and job.get('run_id') == source['runId'] and job.get('run_attempt') == source['attempt']
            and job.get('head_sha') == source['commit'] and bounded.text(job.get('conclusion'), 64),
            'Recording job source or attempt differs')
    job_start, job_end = bounded.timestamp(job.get('started_at')), bounded.timestamp(job.get('completed_at'))
    require(start <= job_start <= job_end <= end, 'Recording job is outside the source attempt')
    expected = {'probe': profile['probePrefix'] + str(source['attempt']),
                'video': f"probe-video-{profile['profile']}-api-35-attempt-{source['attempt']}"}
    selected = {}
    for item in entries(artifacts, 'artifacts'):
        name = item.get('name')
        if name not in expected.values():
            if isinstance(name, str) and name.startswith('probe-video-') and name.endswith('-attempt-' + str(source['attempt'])):
                raise ValueError('Foreign profile or API video artifact in the recording attempt')
            continue
        kind = next(kind for kind, fixed in expected.items() if fixed == name)
        require(kind not in selected, 'Duplicate current-attempt recording artifact')
        origin = item.get('workflow_run', {})
        require(isinstance(origin, dict) and origin.get('id') == source['runId']
                and origin.get('head_sha') == source['commit'] and origin.get('head_branch') == 'master'
                and origin.get('repository_id') == repository['id'] and origin.get('head_repository_id') == repository['id'],
                'Artifact origin differs from the verified recording source')
        limit = MAX_TOTAL_BYTES if kind == 'video' else bounded.MAX_TOTAL_BYTES
        require(bounded.positive_int(item.get('id')) and bounded.positive_int(item.get('size_in_bytes'))
                and item['size_in_bytes'] <= limit and type(item.get('expired')) is bool,
                'Invalid recording artifact size, id or expiration state')
        require(job_start <= bounded.timestamp(item.get('created_at')) <= job_end,
                'Recording artifact was not uploaded by the fixed API35 job')
        selected[kind] = item
    require(len({item['id'] for item in selected.values()}) == len(selected), 'Artifact id reused across probe/video')
    return {'source': source, 'profile': profile, 'job': job, 'artifacts': selected,
            'evidencePath': f"screenshots/demos/probes-{source['runId']}-{source['attempt']}"}


def public_file(item, prefix):
    return {'path': prefix + '/' + item['path'], 'bytes': item['bytes'], 'sha256': item['sha256']}


def video_files(folder, source, profile):
    require(folder.is_dir() and not folder.is_symlink(), 'Missing or linked video artifact directory')
    found = {}
    for path in folder.iterdir():
        require(path.is_file() and not path.is_symlink() and path.name in ('recording.json', 'demo.mp4', 'raw.mp4'),
                'Unexpected video artifact payload')
        size = path.stat().st_size
        limit = bounded.MAX_JSON_BYTES if path.name == 'recording.json' else MAX_RAW_BYTES if path.name == 'raw.mp4' else MAX_VIDEO_BYTES
        require(0 < size < limit, 'Video payload violates its size bound')
        data = path.read_bytes()
        require(len(data) == size, 'Video payload changed while reading')
        found[path.name] = {'path': path.name, 'bytes': size, 'sha256': hashlib.sha256(data).hexdigest(), 'data': data}
    require('recording.json' in found, 'Video artifact lacks its original recording report')
    report = probe_video.validate_recording_report(bounded.json_data(found['recording.json']['data']), source, profile)
    for name, metadata, retained in (('demo.mp4', report['video'], report['video'] is not None),
                                     ('raw.mp4', report['rawVideo'], report['rawRetained'])):
        require((name in found) == retained, 'Recording report/file presence differs: ' + name)
        if not retained:
            continue
        require(isinstance(metadata, dict) and all(found[name][key] == metadata.get(key) for key in ('bytes', 'sha256')),
                'Recording report/file digest differs: ' + name)
        if name == 'demo.mp4':
            require(demo_video.video_metadata(folder / name, True) == metadata, 'Actual video metadata differs from recording report')
            demo_video.bounded.run_command(['ffmpeg', '-nostdin', '-v', 'error', '-xerror', '-i', str(folder / name),
                '-map', '0:v:0', '-f', 'null', '-'], timeout=90, limit=64 * 1024)
        # A failed raw encode/probe may preserve undecodable raw bytes. Its exact
        # bytes remain diagnostic evidence; it never counts as a passed video.
    return report, found


def appset_receipts(report, states, files, lock):
    """Cross-check original command/install receipts without changing their format."""
    steps = report.get('driverSteps', [])
    require(isinstance(steps, list) and all(isinstance(step, dict) for step in steps), 'Invalid driver receipts')
    names = [step.get('name') for step in steps]
    require(all(isinstance(name, str) for name in names) and len(names) == len(set(names)), 'Duplicate driver receipt')
    by_name = {step['name']: step for step in steps}
    downloads = report.get('verifiedDownloads', {})
    require(isinstance(downloads, dict) and set(downloads) <= set(appset.APP_IDS), 'Unexpected verified APK download')
    for entry, install in zip(lock['apps'], ('install_calculator', 'install_notes')):
        app = entry['app']; app_id = entry['appId']
        if app_id in downloads:
            require(downloads[app_id] == {key: app[key] for key in ('package', 'versionCode', 'versionName', 'minSdk',
                    'targetSdk', 'abis', 'certificateSha256', 'sha256')}, 'Downloaded APK receipt differs from frozen official release')
        if install in by_name and by_name[install].get('status') == 'passed':
            require(by_name[install].get('sha256') == app['sha256'], 'Installed official APK digest differs from lock')
    if report['status'] == 'passed':
        require(set(downloads) == set(appset.APP_IDS), 'Successful probe lacks both official download receipts')
        for name in ('device_preflight', 'apk_verification_calculator-1.4.0', 'apk_verification_notes-1.7.0',
                     'fresh_install_precondition', 'install_agent', 'install_calculator', 'install_notes',
                     'install_probe', 'install_probe_tests', 'instrumentation', 'collect_ui_results'):
            require(by_name.get(name, {}).get('status') == 'passed', 'Successful probe lacks passed driver receipt: ' + name)
        for name in ('install_agent', 'install_probe', 'install_probe_tests'):
            require(re.fullmatch(r'[0-9a-f]{64}', str(by_name[name].get('sha256', ''))), 'Invalid installed CI-built APK digest')
    terminals, starts, pending = {}, set(), {}
    value = None
    if 'instrumentation.json' in files:
        value = bounded.json_data(files['instrumentation.json']['data'])
        require(type(value.get('commandCompleted')) is bool and isinstance(value.get('output'), str), 'Invalid original app-set command record')
        for line in value['output'].splitlines():
            field = re.fullmatch(r'INSTRUMENTATION_STATUS: ([^=]+)=(.*)', line)
            if field:
                require(field[1] not in pending, 'Duplicate instrumentation field')
                pending[field[1]] = field[2]
                continue
            code = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
            if code:
                require(pending.get('class') == APPSET_CLASS and pending.get('test') in APPSET_METHODS.values()
                        and pending.get('numtests') == '4', 'Foreign app-set instrumentation case or denominator')
                method, status = pending['test'], int(code[1])
                if status == 1:
                    require(method not in starts, 'Duplicate instrumentation case start')
                    starts.add(method)
                else:
                    require(method in starts and method not in terminals and status in (0, -1, -2, -3, -4),
                            'Duplicate or malformed instrumentation terminal')
                    terminals[method] = 'passed' if status == 0 else 'unverified' if status in (-3, -4) else 'failed'
                pending = {}
    for app, app_states in states.items():
        for state, status in app_states.items():
            method = APPSET_METHODS[(app, state)]
            require(status != 'passed' or terminals.get(method) == 'passed', 'Claimed app-set pass lacks an original instrumentation pass')
            require(method not in terminals or status == terminals[method], 'Original instrumentation and UI result disagree')
    if report['status'] == 'passed':
        require(value is not None and value['commandCompleted'] and not pending and len(terminals) == 4
                and all(status == 'passed' for status in terminals.values())
                and re.search(r'\bOK \(4 tests\)', value['output'])
                and not any(token in value['output'] for token in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=', 'Process crashed'))
                and re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', value['output'], re.M) == ['-1'],
                'Successful app-set summary contradicts its original instrumentation')
    return {'available': value is not None, 'terminalCases': terminals}


def validate_probe(folder, source, profile, run):
    files = bounded.scan_files(folder, {'files': 0, 'bytes': 0})
    require('report.json' in files, 'Original probe report is missing')
    if profile == 'third-party-app-set':
        require(all(name in ('report.json', 'ui-results.json', 'instrumentation.json') or name.endswith('.png') for name in files),
                'Unexpected app-set probe payload')
        report = bounded.json_data(files['report.json']['data'])
        lock = appset.load_lock()
        states, captures = appset_archive.validate_report(report, '35', source, lock, files)
        receipts = appset_receipts(report, states, files, lock)
        cases = [{'name': app + '/' + state, 'appId': app, 'state': state,
                  'status': states.get(app, {}).get(state, 'unverified')}
                 for app in appset.APP_IDS for state in appset.STATES]
        counts = Counter(case['status'] for case in cases)
        status = 'failed' if counts['failed'] else 'passed' if report['status'] == 'passed' and counts['passed'] == 4 else 'unverified'
        return {'status': status, 'driverStatus': report['status'], 'cases': cases,
                'captureCounts': captures, 'sampleId': appset.SAMPLE_ID, 'instrumentation': receipts}, files
    require(profile == 'pinned-calculator-page', 'Unsupported original probe profile')
    require(all(name in ('report.json', 'capture.json', 'instrumentation.json')
                or name.startswith('pinned-page/') or name.startswith('screenshots/') for name in files),
            'Unexpected pinned-page probe payload')
    for name, item in files.items():
        if name.startswith('screenshots/') and name != 'screenshots/manifest.json':
            require(name.count('/') == 1 and name.endswith('.json'), 'Unexpected standard screenshot payload')
            case = bounded.json_data(item['data'])
            require(case.get('api') == 35 and case.get('testClass') == pinned.TEST_CLASS
                    and case.get('testName') in pinned.CASES
                    and all(c.get('secureWindowTemporarilyCleared') is not True for c in case.get('captures', [])),
                    'Foreign or unsafe standard screenshot case')
    return publisher.pinned_page_report(folder, run, '35', pinned), files


def archive(run, jobs, artifacts, incoming, repo=None):
    selected = select(run, jobs, artifacts)
    source, contract = selected['source'], selected['profile']
    root = Path(repo or Path.cwd()).resolve()
    destination = root / selected['evidencePath']
    require(not destination.exists() and not destination.is_symlink(), 'This recording archive already exists')
    for parent in (root / 'screenshots', root / 'screenshots/demos'):
        require(not parent.is_symlink(), 'Recording archive parent cannot be linked')
    incoming = Path(incoming)
    available = {kind: item for kind, item in selected['artifacts'].items() if not item['expired']}
    if incoming.exists():
        require(incoming.is_dir() and not incoming.is_symlink(), 'Invalid incoming artifact root')
        require({p.name for p in incoming.iterdir()} == {item['name'] for item in available.values()},
                'Downloaded artifact set differs from selected current-attempt artifacts')
    else:
        require(not available, 'Selected artifacts were not downloaded')
    original, recording, payloads = None, None, {}
    for kind, artifact in available.items():
        folder = incoming / artifact['name']
        if kind == 'video':
            recording, files = video_files(folder, source, contract['profile'])
        else:
            original, files = validate_probe(folder, source, contract['profile'], run)
        payloads[kind] = files
    cases = original['cases'] if original else ([{'name': app + '/' + state, 'appId': app, 'state': state, 'status': 'unverified'}
        for app in appset.APP_IDS for state in appset.STATES] if contract['profile'] == 'third-party-app-set' else
        [{'name': name, 'status': 'unverified'} for name in pinned.CASES])
    counts = Counter(case['status'] for case in cases)
    require(len(cases) == contract['expectedTests'] and sum(counts.values()) == contract['expectedTests'], 'API35 fixed denominator changed')
    probe_status = original['status'] if original else 'unverified'
    recording_status = recording['status'] if recording else 'unavailable'
    status = 'passed' if recording_status == probe_status == 'passed' and selected['job']['conclusion'] == 'success' else 'failed'
    all_files = [public_file(item, kind) for kind, files in payloads.items() for item in files.values()]
    total = sum(item['bytes'] for item in all_files)
    require(total <= MAX_TOTAL_BYTES, 'Combined recording evidence exceeds archive budget')
    manifest = {'schemaVersion': 1, 'kind': 'agent-os-probe-video-archive',
        'archivedAt': datetime.now(timezone.utc).isoformat(), 'source': source,
        'sourceRunUrl': f"https://github.com/{REPOSITORY}/actions/runs/{source['runId']}",
        'sourceWorkflow': run['path'], 'sourceBranch': 'master', 'sourceRunConclusion': run['conclusion'],
        'job': {'id': selected['job']['id'], 'name': selected['job']['name'], 'conclusion': selected['job']['conclusion']},
        'profile': contract['profile'], 'apiLevel': 35, 'scope': contract['scope'],
        'status': status, 'recordingStatus': recording_status, 'probeStatus': probe_status,
        'fullSuiteAcceptance': False, 'expectedTests': contract['expectedTests'],
        'matrixExpectedTests': contract['matrixExpectedTests'], 'recordedMatrixLabels': ['35'],
        'caseCounts': {name: counts[name] for name in ('passed', 'failed', 'unverified')}, 'cases': cases,
        'probe': original, 'recording': recording, 'video': recording['video'] if recording else None,
        'videoPath': 'video/demo.mp4' if recording and recording['video'] else None,
        'artifacts': {kind: {'name': contract['probePrefix'] + str(source['attempt']) if kind == 'probe'
                            else f"probe-video-{contract['profile']}-api-35-attempt-{source['attempt']}",
                            'id': selected['artifacts'].get(kind, {}).get('id'),
                            'status': 'archived' if kind in available else 'expired' if kind in selected['artifacts'] else 'unavailable'}
                      for kind in ('probe', 'video')},
        'fileCount': len(all_files), 'totalBytes': total, 'files': all_files}
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.probe-video-archive-', dir=destination.parent))
    try:
        for kind, files in payloads.items():
            for name, item in files.items():
                path = temporary / kind / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(item['data'])
                path.chmod(0o644)
        (temporary / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        require(not destination.exists() and not destination.is_symlink(), 'Archive destination appeared during validation')
        temporary.rename(destination)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('select', 'archive'))
    for name in ('run', 'jobs', 'artifacts'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--github-output', type=Path)
    parser.add_argument('--incoming', type=Path)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    args = parser.parse_args(argv)
    try:
        run, jobs, artifacts = (bounded.read_json(getattr(args, name)) for name in ('run', 'jobs', 'artifacts'))
        selection = select(run, jobs, artifacts)
        if args.command == 'select':
            source = selection['source']
            result = {'source_run': source['runId'], 'attempt': source['attempt'], 'source_sha': source['commit'],
                      'profile': selection['profile']['profile'], 'evidence_path': selection['evidencePath'],
                      'artifact_ids': ','.join(str(item['id']) for item in selection['artifacts'].values() if not item['expired'])}
            if args.github_output:
                require(not args.github_output.is_symlink(), 'GitHub output cannot be linked')
                with args.github_output.open('a', encoding='utf-8') as stream:
                    for name, value in result.items():
                        stream.write(f'{name}={value}\n')
        else:
            require(args.incoming is not None, 'archive requires --incoming')
            result = archive(run, jobs, artifacts, args.incoming, args.repo)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, OSError) as error:
        print('Probe video archive rejected: ' + str(error))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
