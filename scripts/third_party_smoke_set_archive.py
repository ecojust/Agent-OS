#!/usr/bin/env python3
"""Archive all 32 app/API/state outcomes with source-bound, bounded evidence."""
from collections import Counter
from datetime import datetime, timezone
import copy
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile

import third_party_smoke_archive as base
import third_party_smoke_set as probe

require = base.require
LABELS = tuple(probe.APIS)
STATES = probe.STATES
APP_IDS = probe.APP_IDS


def validate_report(report, label, identity, lock, files):
    require(isinstance(report, dict) and type(report.get('schemaVersion')) is int and report['schemaVersion'] == 4
            and report.get('kind') == 'third-party-app-set-smoke', 'Invalid app-set report schema')
    require(report.get('source') == identity and all(type(report['source'].get(key)) is int for key in ('runId', 'attempt')),
            'Report source differs from trusted run')
    require(report.get('matrixLabel') == label and report.get('sampleId') == probe.SAMPLE_ID
            and report.get('lockSha256') == probe.lock_digest(lock), 'Report lane or source-lock digest differs')
    require(report.get('scope') == lock['scope'], 'Report scope differs from frozen sample')
    require(report.get('status') in ('passed', 'failed', 'environment_failed'), 'Invalid report status')
    device = report.get('device'); expected_api = probe.APIS[label]
    require(isinstance(device, dict), 'Missing device metadata')
    api = device.get('apiLevel')
    require(api is None or type(api) is int and api == expected_api, 'Report API differs from artifact lane')
    require(api is not None or report['status'] == 'environment_failed', 'Missing device cannot pass')
    raw_apps = report.get('apps')
    require(isinstance(raw_apps, list) and len(raw_apps) <= 2, 'Invalid app records')
    locked = {item['appId']: item['app'] for item in lock['apps']}
    apps = []
    for item in raw_apps:
        require(isinstance(item, dict) and item.get('appId') in locked and item.get('app') == locked[item['appId']], 'Report application differs from frozen identity')
        apps.append(dict(item, package=item['app']['package']))
    probe.validate_app_results(apps, lock, complete=False)
    passed = any(state['status'] == 'passed' for app in apps for state in app['states'])
    if passed:
        require(api == expected_api and re.fullmatch(r'emulator-[0-9]{4,5}', str(device.get('serial', ''))) is not None,
                'Passed app state lacks actual emulator identity')
    referenced, captures = set(), Counter()
    for app in apps:
        for state in app['states']:
            for capture in state['captures']:
                captures[capture['status']] += 1
                if capture['status'] == 'captured':
                    path = str(base.relative_path(capture['path']))
                    require(path in files and path not in referenced and files[path]['bytes'] == capture['bytes']
                            and files[path]['sha256'] == capture['sha256'], 'Screenshot file, size or SHA-256 differs')
                    referenced.add(path)
    require({name for name in files if name.endswith('.png')} == referenced, 'Unreferenced screenshot file')
    # A passed state must be linked to the collected, source-bound UI report;
    # report.json alone cannot invent an independent installed identity/result.
    if 'uiResultsSha256' in report or passed:
        require('ui-results.json' in files and report.get('uiResultsSha256') == files['ui-results.json']['sha256'], 'Collected UI manifest digest differs')
        ui = base.json_data(files['ui-results.json']['data'])
        observed = probe.validate_ui_results(ui, identity, expected_api, device['serial'], lock)
        ui_map = {entry['appId']: entry for entry in observed}
        for app in apps:
            original = ui_map[app['appId']]
            for key in ('actualInstalledIdentityVerified', 'installedIdentityFailureSeen'):
                require(app[key] == original[key], 'Report identity state differs from collected UI result')
            original_states = {state['state']: state for state in original['states']}
            for state in app['states']:
                # Collection may downgrade a capture to unavailable, not rewrite
                # a UI assertion, result, service observation or its pass/fail state.
                observed_state = original_states[state['state']]
                require({key: value for key, value in state.items() if key != 'captures'} ==
                        {key: value for key, value in observed_state.items() if key != 'captures'},
                        'Report assertions differ from collected UI result')
                require(len(state['captures']) == len(observed_state['captures']), 'Report changed screenshot attempts')
                for captured, original_capture in zip(state['captures'], observed_state['captures']):
                    if captured == original_capture:
                        continue
                    require(original_capture['status'] == 'captured' and captured['status'] == 'unavailable'
                            and captured['stage'] == original_capture['stage']
                            and captured.get('reason') == 'screenshot_collection_failed'
                            and set(captured) == {'stage', 'status', 'reason', 'detail'}
                            and isinstance(captured['detail'], str) and len(captured['detail']) <= 1600
                            and report['status'] != 'passed',
                            'Report may only downgrade an original capture after collection failure')
    if report['status'] == 'passed':
        require(len(apps) == 2 and all(len(app['states']) == 2 and all(state['status'] == 'passed' for state in app['states']) for app in apps),
                'Passed report is missing successful app states')
    return {app['appId']: {state['state']: state['status'] for state in app['states']} for app in apps}, dict(captures)


def archive(incoming, output, run, artifact_index, sample, repo=None):
    identity = base.verify_source(run)
    probe.validate_lock(sample)
    selected = base.eligible_artifacts(artifact_index, run, 4)
    require(not any(base.eligible_artifacts(artifact_index, run, version) for version in (1, 2, 3)),
            'Older evidence cannot fill a version-four app-set run')
    ids = [item['id'] for item in selected.values()]
    require(len(ids) == len(set(ids)), 'Artifact ID reused across app-set lanes')
    repo = Path(repo or Path.cwd()).resolve()
    expected = f"screenshots/third-party-{identity['runId']}-{identity['attempt']}"
    require(str(output) == expected, 'Archive output must match source run')
    destination = repo / expected
    require(not (repo / 'screenshots').is_symlink() and not destination.exists() and not destination.is_symlink(), 'Archive output exists or is linked')
    incoming = Path(incoming); require(not incoming.is_symlink(), 'Incoming directory cannot be linked')
    records, staged, budget = [], [], {'files': 0, 'bytes': 0}
    for label in LABELS:
        record = {'matrixLabel': label, 'apiLevel': probe.APIS[label], 'status': 'environment_failed',
                  'reason': 'current_attempt_artifact_unavailable',
                  'apps': {app: {state: 'unverified' for state in STATES} for app in APP_IDS},
                  'captureCounts': {}, 'screenshotCount': 0, 'files': []}
        artifact = selected.get(label)
        if artifact is None or artifact['expired']:
            if artifact is not None: record.update({'reason': 'current_attempt_artifact_expired', 'artifactId': artifact['id']})
            records.append(record); continue
        source = incoming / artifact['name']
        require(source.is_dir() and not source.is_symlink(), 'Verified artifact was not downloaded')
        files = base.scan_files(source, budget)
        require('report.json' in files, 'Artifact has no report.json')
        report = base.json_data(files['report.json']['data'])
        states, captures = validate_report(report, label, identity, sample, files)
        record.update({'status': report['status'], 'reason': report.get('reason'), 'artifactId': artifact['id'],
                       'device': report['device'], 'captureCounts': captures, 'screenshotCount': captures.get('captured', 0),
                       'files': [{key: value for key, value in item.items() if key != 'data'} for item in files.values()]})
        for app, app_states in states.items(): record['apps'][app].update(app_states)
        records.append(record); staged.append((label, files))
    counts = Counter(status for record in records for states in record['apps'].values() for status in states.values())
    require(sum(counts.values()) == 32, 'App-set denominator changed')
    manifest = {'schemaVersion': 4, 'kind': 'third-party-emulator-app-set-evidence',
                'archivedAt': datetime.now(timezone.utc).isoformat(),
                'sourceRun': {**identity, 'url': f"https://github.com/{base.REPOSITORY}/actions/runs/{identity['runId']}",
                              'conclusion': run['conclusion'], 'event': 'workflow_dispatch', 'branch': 'master'},
                'scope': {'deviceKind': 'dedicated_emulator', 'states': list(STATES), 'executingStateValidated': False,
                          'upgradeValidated': False, 'physicalDeviceValidated': False, 'releaseAccepted': False,
                          'note': 'Two exact application releases in off/idle states; not Agent execution, upgrades, a 50-app release pool or general phone compatibility.'},
                'keyboardPolicy': probe.KEYBOARD_POLICY, 'navigationPolicy': probe.NAVIGATION_POLICY,
                'sampleId': sample['sampleId'], 'lockSha256': probe.lock_digest(sample), 'apps': copy.deepcopy(sample['apps']),
                'expectedMatrixLabels': list(LABELS), 'expectedCombinationCount': 32, 'apis': records,
                'stateCounts': {name: counts[name] for name in ('passed', 'failed', 'unverified')},
                'allProbeStatesPassed': run['conclusion'] == 'success' and all(record['status'] == 'passed' for record in records)
                                       and counts['passed'] == 32,
                'fileCount': budget['files'], 'totalBytes': budget['bytes'],
                'screenshotCount': sum(record['screenshotCount'] for record in records)}
    (repo / 'screenshots').mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.third-party-set-archive-', dir=repo / 'screenshots'))
    try:
        for label, files in staged:
            for name, item in files.items():
                target = temporary / f'api-{label}' / name; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(item['data']); target.chmod(0o644)
        (temporary / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        require(not destination.exists() and not destination.is_symlink(), 'Archive destination appeared during validation')
        temporary.rename(destination)
    finally:
        if temporary.exists(): shutil.rmtree(temporary)
    return manifest
