#!/usr/bin/env python3
"""Dedicated real-APK page predicate acceptance; no production image permission."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys

import third_party_smoke as base
import third_party_smoke_set as appset

APIS = {'34': 34, '35': 35, '36': 36, '37.0': 37}
TEST_CLASS = 'dev.agentos.app.PinnedCalculatorVisualPageInstrumentedTest'
CASES = {
    'ordinaryZeroAndComputedResultUseTheActualProductionSnapshot': [('basic-zero', 'accepted'), ('basic-result', 'accepted')],
    'actualOverflowMenuIsNotTheUnopenedToolbar': [('before-overflow', 'accepted'), ('overflow', 'rejected')],
    'nonemptyHistoryIsRejectedAndReturningNeedsANewObservation': [('before-history', 'accepted'), ('history', 'rejected'), ('returned-from-history-new-observation', 'accepted')],
    'actualSettingsPageIsRejected': [('before-settings', 'accepted'), ('settings', 'rejected')],
    'actualUnitConverterPageIsRejected': [('before-converter', 'accepted'), ('converter', 'rejected')],
}
MAX_TOTAL = 64 * 1024 * 1024
require = base.require


def source(env=None):
    env = os.environ if env is None else env
    require(env.get('GITHUB_ACTIONS') == 'true' and env.get('GITHUB_REPOSITORY') == base.REPOSITORY,
            'Only the dedicated repository workflow may operate devices')
    require(env.get('GITHUB_REF') == 'refs/heads/master' and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and env.get('GITHUB_WORKFLOW_REF') == base.REPOSITORY + '/.github/workflows/pinned-calculator-page.yml@refs/heads/master',
            'Unexpected branch, event or workflow')
    sha = env.get('GITHUB_SHA', '')
    require(re.fullmatch(r'[0-9a-f]{40}', sha) is not None, 'Invalid source SHA')
    for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
        require(re.fullmatch(r'[1-9][0-9]{0,18}', env.get(key, '')) is not None, 'Invalid run identity')
    return {'repository': base.REPOSITORY, 'commit': sha, 'runId': int(env['GITHUB_RUN_ID']), 'attempt': int(env['GITHUB_RUN_ATTEMPT'])}


def empty_report(binding, label):
    return {'schemaVersion': 1, 'kind': 'pinned-calculator-page-predicate', 'source': binding,
            'matrixLabel': label, 'status': 'environment_failed', 'expectedTests': len(CASES),
            'scope': 'Real pinned Calculator and production service page predicate only; capture permission disabled',
            'cases': [{'name': name, 'status': 'unverified'} for name in CASES], 'driverSteps': []}


def validate_case(data, binding, api, serial, expected):
    require(isinstance(data, dict) and data.get('schemaVersion') == 1 and data.get('kind') == 'pinned-calculator-page-predicate', 'Invalid case format')
    require(data.get('testClass') == TEST_CLASS and data.get('testName') == expected and expected in CASES, 'Foreign or duplicate case')
    require(data.get('api') == api and data.get('sourceSha') == binding['commit'] and data.get('runId') == str(binding['runId'])
            and data.get('runAttempt') == str(binding['attempt']) and data.get('deviceSerial') == serial, 'Case belongs to another source/device')
    require(data.get('status') in ('passed', 'failed', 'unverified'), 'Invalid case status')
    stages = data.get('stages')
    require(isinstance(stages, list) and len(stages) <= 32 and all(isinstance(item, dict) for item in stages), 'Invalid bounded case stages')
    if data['status'] == 'passed':
        require(data.get('installedArtifactVerified') is True and data.get('captureCapabilityAbsent') is True
                and data.get('imageAndGestureToolsAbsent') is True and type(data.get('modelRequests')) is int and data['modelRequests'] == 0,
                'Passed case lacks installed/no-capability evidence')
        outcomes = [(stage.get('stage'), stage.get('predicate')) for stage in stages if 'predicate' in stage]
        require(outcomes == CASES[expected], 'Passed case lacks its actual page outcomes')
        for stage in stages:
            if stage.get('predicate') == 'rejected':
                require(stage.get('reason') in ('UNKNOWN_CONTENT', 'INVALID_KEYPAD', 'UNSUPPORTED_GEOMETRY', 'SENSITIVE_CONTENT',
                                                'UNSUPPORTED_FORMAT', 'OCCLUDED', 'HIGH_IMPACT_CONTENT'), 'Non-page failure counted as rejection')
    return data


def validate_case_files(record, files):
    """Verify bounded metadata/image links without normalizing or replacing raw records."""
    name = record['testName']; used = set()
    for stage in record['stages']:
        if 'metadata' in stage:
            filename = stage['metadata']
            require(isinstance(filename, str) and filename in files and filename.startswith(name + '-') and filename.endswith('.json'),
                    'Missing or foreign node metadata')
            raw = files[filename]; require(len(raw) <= 96 * 1024, 'Metadata too large')
            data = base.parse_json(raw)
            require(isinstance(data, dict) and data.get('stage') == stage.get('stage')
                    and isinstance(data.get('nodes'), list) and len(data['nodes']) <= 300,
                    'Malformed independent diagnostic sample')
        if stage.get('captureStatus') == 'captured':
            image = stage.get('image')
            require(isinstance(image, str) and image in files and image.startswith(name + '-') and image.endswith('.png') and image not in used,
                    'Missing, reused or foreign image')
            used.add(image); raw = files[image]
            require(len(raw) >= 24 and raw.startswith(b'\x89PNG\r\n\x1a\n') and raw[12:16] == b'IHDR', 'Invalid image PNG')
            require(type(stage.get('imageBytes')) is int and 0 < len(raw) <= base.MAX_CAPTURE
                    and len(raw) == stage['imageBytes'] and hashlib.sha256(raw).hexdigest() == stage.get('imageSha256'), 'Image identity mismatch')
            dimensions = struct.unpack('>II', raw[16:24])
            require(all(64 <= size <= 4096 for size in dimensions)
                    and dimensions == (stage.get('imageWidth'), stage.get('imageHeight')), 'Image dimension mismatch')
            require(stage.get('secureFlagsCleared') is False, 'Secure behavior was changed for evidence')
        elif 'captureStatus' in stage:
            require(stage['captureStatus'] == 'blocked' and isinstance(stage.get('captureReason'), str)
                    and 0 < len(stage['captureReason']) <= 180 and not any(key in stage for key in
                    ('image', 'imageSha256', 'imageBytes', 'imageWidth', 'imageHeight'))
                    and stage.get('secureFlagsCleared') is False, 'Blocked evidence must not carry an image')
    return record


def empty_capture(label):
    return {'schemaVersion': 1, 'apiLevel': APIS[label], 'matrixLabel': label, 'actualSdkInt': None,
            'gradleExitCode': None, 'captureStatus': 'not_collected', 'captureError': 'Emulator script did not collect evidence',
            'capturedAt': datetime.now(timezone.utc).isoformat(), 'files': [], 'runnerKind': 'direct_adb_instrumentation',
            'instrumentationExitCode': None, 'junitStatus': 'not_produced_direct_adb'}


def write_capture(directory, value):
    directory = base.ensure_directory(directory)
    path = directory / 'capture.json'
    require(not path.is_symlink(), 'Capture cannot be a symbolic link')
    if path.exists():
        previous = base.read_json(path)
        require(previous.get('apiLevel') == value['apiLevel'] and previous.get('matrixLabel') == value['matrixLabel'], 'Cannot overwrite another API capture')
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def instrument_result(text, exit_code):
    return exit_code == 0 and bool(re.search(r'\bOK \(5 tests\)', text)) and not any(
        token in text for token in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=', 'Process crashed'))


def collect_cases(adb, serial, directory, binding, api, command=base.run_command):
    folder = directory / 'pinned-page'; folder.mkdir(exist_ok=True)
    names = command([adb, '-s', serial, 'shell', 'run-as', base.AGENT_PACKAGE, 'ls', 'files/pinned-calculator-page'], timeout=10).splitlines()
    require(len(names) <= 96 and len(names) == len(set(names)), 'Unexpected evidence file count')
    files = {}; total = 0
    for name in names:
        require(re.fullmatch(r'[A-Za-z][A-Za-z0-9]*-(?:[0-9]+)\.(?:json|png)|[A-Za-z][A-Za-z0-9]*\.case\.json', name) is not None,
                'Unexpected evidence filename')
        relative = Path(name)  # Single-component JSON or PNG name already checked above.
        owner = name.split('-', 1)[0].removesuffix('.case.json')
        require(owner in CASES, 'File belongs to a foreign case')
        limit = 96 * 1024 if name.endswith('.json') else base.MAX_CAPTURE
        raw = command([adb, '-s', serial, 'exec-out', 'run-as', base.AGENT_PACKAGE, 'cat', 'files/pinned-calculator-page/' + str(relative)],
                      timeout=10, binary=True, limit=limit)
        total += len(raw); require(total <= MAX_TOTAL, 'Evidence exceeds total budget')
        if name.endswith('.json'): base.parse_json(raw)
        else:
            require(len(raw) >= 24 and raw.startswith(b'\x89PNG\r\n\x1a\n') and raw[12:16] == b'IHDR', 'Invalid evidence PNG')
            width, height = struct.unpack('>II', raw[16:24]); require(64 <= width <= 4096 and 64 <= height <= 4096, 'Invalid PNG dimensions')
        (folder / name).write_bytes(raw); files[name] = raw
    results = []
    for name in CASES:
        filename = name + '.case.json'
        if filename not in files:
            results.append({'name': name, 'status': 'unverified', 'reason': 'Case record missing'}); continue
        record = validate_case(base.parse_json(files[filename]), binding, api, serial, name)
        validate_case_files(record, files)
        results.append({'name': name, 'status': record['status'], 'record': 'pinned-page/' + filename})
    return results


def run(args, binding):
    output = base.ensure_directory(args.output); report = empty_report(binding, args.api); base.write_report(output, report); write_capture(output, empty_capture(args.api))
    instrumentation_exit = None
    api = APIS[args.api]; stage = 'device_preflight'; adb = str(Path(args.sdk_root) / 'platform-tools/adb'); serial = None
    try:
        sdk = Path(args.sdk_root)
        report['emulatorVersion'] = base.validate_emulator_version((sdk / 'emulator/source.properties').read_text(), api)
        serial = base.select_emulator(base.run_command([adb, 'devices']))
        def shell(*words): return base.run_command([adb, '-s', serial, 'shell', *words], timeout=10).strip()
        props = {key: shell('getprop', key) for key in ('ro.kernel.qemu', 'ro.boot.qemu', 'ro.build.version.sdk', 'ro.product.cpu.abi', 'sys.boot_completed')}
        base.validate_device(serial, props, api); require(shell('am', 'get-current-user') == '0', 'Only the dedicated owner profile is supported')
        report['device'] = {'serial': serial, 'api': api, 'properties': props}
        report['animations'] = {}
        for setting in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
            shell('settings', 'put', 'global', setting, '1.0')
            value = shell('settings', 'get', 'global', setting)
            require(value == '1.0', 'Dedicated emulator animation baseline was not applied')
            report['animations'][setting] = value
        stage = 'verify_official_apk'
        lock = appset.load_lock(); app = next(item['app'] for item in lock['apps'] if item['appId'] == 'calculator-1.4.0')
        report['verifiedDownload'] = dict(base.verify_apk(args.calculator_apk, app, sdk / 'build-tools/35.0.0'), sizeBytes=args.calculator_apk.stat().st_size)
        stage = 'fresh_install'
        packages = [base.AGENT_PACKAGE, base.AGENT_PACKAGE + '.test', 'dev.agentos.fixture', app['package']]
        installed = shell('pm', 'list', 'packages').splitlines()
        require(all('package:' + package not in installed for package in packages), 'Refusing to clear or replace preexisting probe application data')
        for name, path, testing in [('agent', args.agent_apk, False), ('fixture', args.fixture_apk, True),
                                    ('tests', args.test_apk, True), ('calculator', args.calculator_apk, False)]:
            require(path.is_file() and not path.is_symlink(), 'APK missing or linked')
            text = base.run_command([adb, '-s', serial, 'install', *(['-t'] if testing else []), str(path)], timeout=120)
            require(re.search(r'^Success\s*$', text, re.M) is not None, 'Installation did not report success')
            report['driverSteps'].append({'name': 'install_' + name, 'status': 'passed', 'sha256': base.sha256_file(path)})
        stage = 'instrumentation'
        parameters = {'class': TEST_CLASS, 'pinnedPageMarker': 'agent-os-pinned-page-v1', 'freshInstallMarker': 'agent-os-pinned-page-fresh-v1',
                      'expectedApi': str(api), 'deviceSerial': serial, 'sourceSha': binding['commit'],
                      'runId': str(binding['runId']), 'runAttempt': str(binding['attempt'])}
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r']
        for key, value in parameters.items(): command.extend(['-e', key, value])
        command.append(base.AGENT_PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
        bounded = [sys.executable, str(base.ROOT / 'scripts/run_bounded_instrumentation.py'), '--matrix-label', args.api,
                   '--timeout-seconds', '360', '--termination-grace-seconds', '10', '--', *command]
        # The existing wrapper owns timeout/process cleanup; preserve its actual output
        # and exit code even on failure, rather than manufacturing a JUnit report.
        result = subprocess.run(bounded, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=440, check=False)
        require(len(result.stdout) <= 1024 * 1024, 'Instrumentation output exceeded its bound')
        text = result.stdout.decode('utf-8', 'replace'); instrumentation_exit = result.returncode
        instrument_ok = instrument_result(text, instrumentation_exit)
        log = json.dumps({'runner': 'direct_adb_instrumentation', 'exitCode': instrumentation_exit,
                          'passed': instrument_ok, 'stdout': text}, ensure_ascii=False).encode('utf-8')
        require(len(log) <= 2 * 1024 * 1024, 'Instrumentation JSON exceeded its bound')
        (output / 'instrumentation.json').write_bytes(log)
        report['instrumentation'] = {'selectedClass': TEST_CLASS, 'expectedTests': len(CASES),
                                     'exitCode': instrumentation_exit, 'passed': instrument_ok,
                                     'log': 'instrumentation.json', 'bytes': len(log), 'sha256': hashlib.sha256(log).hexdigest()}
        report['driverSteps'].append({'name': stage, 'status': 'passed' if instrument_ok else 'failed'})
        stage = 'collect_cases'
        report['cases'] = collect_cases(adb, serial, output, binding, api)
        report['status'] = 'passed' if instrument_ok and all(item['status'] == 'passed' for item in report['cases']) else 'failed'
    except (base.ProbeError, OSError, ValueError, subprocess.SubprocessError) as failure:
        report['status'] = 'environment_failed'; report['error'] = {'stage': stage, 'type': type(failure).__name__, 'message': str(failure)[:1000]}
    finally:
        capture = empty_capture(args.api)
        if serial is not None:
            try:
                from collect_android_evidence import collect
                capture = collect(api, args.api, 0 if report['status'] == 'passed' else 1, output)
                capture['gradleExitCode'] = None  # This driver invokes adb, not Gradle.
                capture['runnerKind'] = 'direct_adb_instrumentation'
                capture['junitStatus'] = 'not_produced_direct_adb'
            except Exception as unavailable: capture['captureError'] = type(unavailable).__name__
        capture['instrumentationExitCode'] = instrumentation_exit
        write_capture(output, capture)
        base.write_report(output, report)
    return report['status'] == 'passed'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__); commands = parser.add_subparsers(dest='command', required=True)
    for name in ('prepare', 'run', 'finalize'):
        child = commands.add_parser(name); child.add_argument('--api', choices=APIS, required=True); child.add_argument('--output', type=Path, required=True)
        if name == 'run':
            child.add_argument('--sdk-root', required=True)
            for flag in ('agent-apk', 'fixture-apk', 'test-apk', 'calculator-apk'): child.add_argument('--' + flag, type=Path, required=True)
        if name == 'finalize': child.add_argument('--outcome', choices=('success', 'failure', 'cancelled', 'skipped', 'unknown'), required=True)
    args = parser.parse_args(argv); binding = source()
    if args.command == 'run': return 0 if run(args, binding) else 1
    report = empty_report(binding, args.api)
    if args.command == 'finalize':
        path = args.output / 'report.json'
        if path.exists(): report = base.read_json(path)
        require(report.get('source') == binding and report.get('matrixLabel') == args.api and report.get('kind') == 'pinned-calculator-page-predicate', 'Cannot finalize foreign evidence')
        report['emulatorStepOutcome'] = args.outcome
        if args.outcome != 'success' and report['status'] == 'passed': report['status'] = 'environment_failed'
    base.write_report(args.output, report)
    capture_path = args.output / 'capture.json'
    capture = base.read_json(capture_path) if capture_path.exists() else empty_capture(args.api)
    require(capture.get('apiLevel') == APIS[args.api] and capture.get('matrixLabel') == args.api, 'Foreign capture record')
    if args.command == 'finalize': capture['emulatorStepOutcome'] = args.outcome
    write_capture(args.output, capture)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
