#!/usr/bin/env python3
"""Versioned two-application developer smoke sample; no Agent task or release acceptance."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error

import third_party_smoke as base

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SAMPLE = ROOT / 'docs/validation/third-party-app-set-lock.json'
APIS = {str(api): api for api in range(30, 37)} | {'37.0': 37}
APP_IDS = ('calculator-1.4.0', 'notes-1.7.0')
SAMPLE_ID = 'calculator-1.4.0-notes-1.7.0-v4'
KEYBOARD_POLICY = 'verified-system-ime-uncovered-targets'
NAVIGATION_POLICY = 'single-back-only-before-notes-create-confirm'
CONFIRMATION_POLICY = 'once-verified-create-dialog'
STATES = base.STATES
require = base.require
ProbeError = base.ProbeError
NOTE_TEXT = 'Agent OS offline note ({state})\nSynthetic sample: 2 + 3 = 5.\nNo personal data.'
NOTE_TITLE = 'Agent OS {state} sample'
NOTES = {'package': 'org.fossify.notes', 'versionName': '1.7.0', 'versionCode': '13',
         'url': 'https://github.com/FossifyOrg/Notes/releases/download/1.7.0/notes-13-foss-release.apk',
         'sizeBytes': 9528948, 'sha256': '5a56e0e39cc488e1f3b947d3801006d3b7450ec73c67f03195c64c5fd3b6bced',
         'certificateSha256': 'affdb124d3f4720c2f98dbca9eacba0514fba4306e20a2786c861c3c0d6ff292',
         'minSdk': 26, 'targetSdk': 36, 'abis': ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], 'license': 'GPL-3.0'}


def validate_lock(data):
    require(isinstance(data, dict) and type(data.get('schemaVersion')) is int and data['schemaVersion'] == 4
            and data.get('kind') == 'third-party-app-set-lock' and data.get('sampleId') == SAMPLE_ID, 'Unknown app-set lock')
    apps = data.get('apps')
    require(isinstance(apps, list) and len(apps) == 2 and [item.get('appId') for item in apps if isinstance(item, dict)] == list(APP_IDS), 'Frozen application set changed')
    calculator = base.load_lock()['app']
    require(apps[0].get('app') == calculator and apps[1].get('app') == NOTES, 'App-set release bytes, source or identity changed')
    require(data.get('apis') == list(range(30, 38)), 'Frozen API sample changed')
    scope = data.get('scope', {})
    require(isinstance(scope, dict) and scope.get('deviceKind') == 'dedicated_emulator' and scope.get('states') == list(STATES)
            and scope.get('keyboardPolicy') == KEYBOARD_POLICY and scope.get('navigationPolicy') == NAVIGATION_POLICY
            and scope.get('releaseAcceptance') is False and all(scope.get(key) == 'unverified' for key in ('executing', 'upgrade', 'physicalDevices')),
            'Smoke scope cannot certify Agent execution, upgrades or devices')
    return data


def load_lock(path=DEFAULT_SAMPLE):
    return validate_lock(base.read_json(path))


def lock_digest(lock):
    """SHA-256 of sorted, compact, ASCII JSON from the independently validated source lock."""
    validate_lock(lock)
    return hashlib.sha256(json.dumps(lock, sort_keys=True, ensure_ascii=True, separators=(',', ':')).encode('utf-8')).hexdigest()


def empty_report(lock, source, label):
    validate_lock(lock)
    require(label in APIS, 'Unsupported app-set API')
    return {'schemaVersion': 4, 'kind': 'third-party-app-set-smoke', 'sampleId': SAMPLE_ID,
            'lockSha256': lock_digest(lock), 'source': copy.deepcopy(source), 'matrixLabel': label,
            'device': {'serial': '', 'apiLevel': None}, 'scope': copy.deepcopy(lock['scope']),
            'status': 'environment_failed', 'driverSteps': [],
            'apps': [{'appId': item['appId'], 'app': copy.deepcopy(item['app']),
                      'actualInstalledIdentityVerified': False, 'installedIdentityFailureSeen': False,
                      'states': [base.unverified_state(state, 'Probe has not executed') for state in STATES]}
                     for item in lock['apps']]}


def write_report(directory, report):
    path = Path(directory) / 'report.json'
    if path.exists():
        old = base.read_json(path)
        require(all(old.get(key) == report.get(key) for key in ('schemaVersion', 'kind', 'sampleId', 'lockSha256', 'source', 'matrixLabel')),
                'Cannot overwrite a different locked sample or source report')
    base.write_report(directory, report)


def validate_passed_confirmation(value):
    """A successful v4 confirmation needs observed postconditions, not BACK acceptance alone.

    Failed and unverified states deliberately do not use this acceptance validator:
    their original partial/unknown or over-budget records must remain archivable.
    """
    fields = {'policy', 'stage', 'backAttempts', 'backDownAttempted', 'backDownAccepted',
              'backUpAttempted', 'backUpAccepted', 'backPreconditionVerified', 'visibleSamples',
              'hiddenSamples', 'elapsedMillis', 'originalDialogWindowId', 'finalDialogWindowId',
              'preBackTitleMatched', 'preBackTitleFocused', 'preBackTitleEditable',
              'postTitleMatched', 'postTitleFocused', 'postTitleEditable', 'imeHiddenVerified',
              'confirmTargetReached', 'confirmAttempts', 'confirmAccepted'}
    require(isinstance(value, dict) and set(value) == fields, 'Successful Notes confirmation evidence is incomplete')
    require(value['policy'] == CONFIRMATION_POLICY and value['stage'] == 'CONFIRM_ACCEPTED',
            'Notes confirmation used another navigation contract or did not finish')
    booleans = ('backDownAttempted', 'backUpAttempted', 'backPreconditionVerified', 'postTitleMatched',
                'postTitleFocused', 'postTitleEditable', 'imeHiddenVerified', 'confirmTargetReached', 'confirmAccepted')
    nullable = ('backDownAccepted', 'backUpAccepted', 'preBackTitleMatched', 'preBackTitleFocused', 'preBackTitleEditable')
    require(all(type(value[key]) is bool for key in booleans)
            and all(value[key] is None or type(value[key]) is bool for key in nullable),
            'Notes confirmation facts need explicit boolean or unknown values')
    limits = {'backAttempts': (0, 1), 'visibleSamples': (0, 4), 'hiddenSamples': (2, 4),
              'elapsedMillis': (0, 4999), 'confirmAttempts': (1, 1)}
    require(all(type(value[key]) is int and low <= value[key] <= high for key, (low, high) in limits.items())
            and value['visibleSamples'] + value['hiddenSamples'] <= 8,
            'Successful Notes confirmation exceeded its fixed action, time or sampling budget')
    require(type(value['originalDialogWindowId']) is int and value['originalDialogWindowId'] >= 0
            and type(value['finalDialogWindowId']) is int
            and value['finalDialogWindowId'] == value['originalDialogWindowId'],
            'Notes confirmation did not return to the same original dialog')
    require(all(value[key] is True for key in ('postTitleMatched', 'postTitleEditable', 'imeHiddenVerified',
                                              'confirmTargetReached', 'confirmAccepted')),
            'Successful Notes confirmation lacks verified hidden IME, title, target or submission')
    if value['backAttempts'] == 1:
        require(value['visibleSamples'] >= 2 and all(value[key] is True for key in
                ('backPreconditionVerified', 'preBackTitleMatched', 'preBackTitleFocused', 'preBackTitleEditable',
                 'backDownAttempted', 'backDownAccepted', 'backUpAttempted', 'backUpAccepted')),
                'Notes BACK lacks a verified precondition or fully accepted paired submission')
    else:
        require(all(value[key] is False for key in ('backPreconditionVerified', 'backDownAttempted', 'backUpAttempted'))
                and value['backDownAccepted'] is None and value['backUpAccepted'] is None,
                'Zero-BACK Notes confirmation cannot claim a BACK submission')


def validate_app_results(entries, lock, complete=True):
    """Validate the per-app records, preserving failed and absent outcomes independently."""
    require(isinstance(entries, list) and len(entries) <= 2, 'Invalid app results')
    expected = {item['appId']: item['app'] for item in lock['apps']}
    found, capture_paths = {}, set()
    for entry in entries:
        require(isinstance(entry, dict) and entry.get('appId') in expected and entry['appId'] not in found, 'Duplicate or foreign app results')
        app_id = entry['appId']; app = expected[app_id]
        require(entry.get('package') == app['package'], 'UI package differs from frozen application')
        require(type(entry.get('actualInstalledIdentityVerified')) is bool
                and type(entry.get('installedIdentityFailureSeen')) is bool, 'Missing application identity state')
        states = entry.get('states')
        require(isinstance(states, list) and len(states) <= 2, 'Invalid app states')
        names = set()
        for state in states:
            require(isinstance(state, dict) and state.get('state') in STATES and state['state'] not in names, 'Duplicate or unknown app state')
            names.add(state['state'])
            status = state.get('status'); require(status in ('passed', 'failed', 'unverified'), 'Invalid state status')
            steps, captures = state.get('steps'), state.get('captures')
            require(isinstance(steps, list) and len(steps) <= 64 and isinstance(captures, list) and len(captures) <= 8, 'Unbounded state data')
            for step in steps:
                require(isinstance(step, dict) and isinstance(step.get('name'), str) and 0 < len(step['name']) <= 96
                        and step.get('status') in ('passed', 'failed', 'unverified'), 'Invalid step record')
            for capture in captures:
                require(isinstance(capture, dict) and isinstance(capture.get('stage'), str) and 0 < len(capture['stage']) <= 120
                        and capture.get('status') in ('captured', 'blocked', 'unavailable'), 'Invalid capture record')
                if capture['status'] == 'captured':
                    relative = base.validate_relative_file(capture.get('path')); path = str(relative)
                    require(path not in capture_paths and relative.name.startswith(app_id + '-' + state['state'] + '-'), 'Screenshot reused or belongs to another app/state')
                    capture_paths.add(path)
                    require(re.fullmatch('[0-9a-f]{64}', str(capture.get('sha256', ''))) is not None
                            and type(capture.get('bytes')) is int and 33 <= capture['bytes'] <= base.MAX_CAPTURE, 'Invalid capture identity')
                else:
                    require(isinstance(capture.get('reason'), str) and 0 < len(capture['reason']) <= 2048
                            and not any(key in capture for key in ('path', 'sha256', 'bytes')), 'Uncaptured image needs a reason without an image reference')
            if status != 'passed':
                continue
            require(entry['actualInstalledIdentityVerified'] is True and state.get('installedIdentityVerified') is True, 'Passed state lacks installed identity')
            identity = state.get('installedIdentity', {})
            require(isinstance(identity, dict) and all(identity.get(key) == app[key] for key in
                    ('package', 'versionName', 'versionCode', 'certificateSha256', 'minSdk', 'targetSdk'))
                    and identity.get('apkSha256') == app['sha256'] and type(identity.get('uid')) is int and identity['uid'] >= 0,
                    'Installed identity differs from independently frozen release')
            expected_service = 'disabled' if state['state'] == 'off' else 'bound_idle'
            require(state.get('serviceState') == expected_service, 'Incorrect Agent service state')
            observations = state.get('serviceObservations')
            require(isinstance(observations, list) and len(observations) == 2, 'Before/after service evidence missing')
            for item, stage in zip(observations, ('before', 'after')):
                require(isinstance(item, dict) and item.get('stage') == stage and item.get('state') == expected_service and item.get('verified') is True,
                        'Agent state was not verified before and after the UI case')
            actions = state.get('serviceActionObservations')
            require(isinstance(actions, list) and 0 < len(actions) <= 64 and all(isinstance(item, dict)
                    and item.get('verified') is True and item.get('state') == expected_service for item in actions), 'Per-action service evidence missing')
            required = ('installed_identity', 'service_state_before', 'installed_identity_after', 'service_state_after', 'restore_service_settings',
                        'calculator_result' if app_id == APP_IDS[0] else 'notes_persisted_result')
            require(steps and all(step['status'] == 'passed' for step in steps)
                    and all(sum(step['name'] == key for step in steps) == 1 for key in required), 'Successful independent UI checks are incomplete')
            if app_id == APP_IDS[0]:
                result = {'expected': '5', 'actual': '5', 'resourceId': 'org.fossify.math:id/result'}
            else:
                text = NOTE_TEXT.format(state=state['state']); title = NOTE_TITLE.format(state=state['state'])
                result = {'resourceId': 'org.fossify.notes:id/text_note_view', 'expected': text, 'actual': text,
                          'titleExpected': title, 'titleActual': title, 'persistenceVerified': True, 'reopenedInFreshProcess': True,
                          'automaticBackPolicy': NAVIGATION_POLICY, 'homeAttempts': 1}
            if app_id == APP_IDS[1]:
                actual_result = state.get('result', {})
                require(isinstance(actual_result, dict) and actual_result.get('persistenceVerified') is True
                        and actual_result.get('reopenedInFreshProcess') is True, 'Notes persistence needs explicit boolean proof')
                require(actual_result.get('automaticBackPolicy') == NAVIGATION_POLICY
                        and type(actual_result.get('homeAttempts')) is int and actual_result['homeAttempts'] == 1,
                        'Version-four Notes must limit BACK to create confirmation and use one normal HOME')
                confirmation = actual_result.get('confirmationPreparation')
                validate_passed_confirmation(confirmation)
                result['confirmationPreparation'] = confirmation
            require(state.get('result') == result, 'App-specific independent result was not verified')
            require(captures, 'Passed UI state has no screenshot attempt')
        if complete: require(names == set(STATES), 'Missing UI app state')
        if names == set(STATES) and all(state['status'] == 'passed' for state in states):
            require(entry['installedIdentityFailureSeen'] is False, 'Successful app has an identity failure')
        found[app_id] = entry
    if complete: require(set(found) == set(APP_IDS), 'Missing UI application')
    return entries


def validate_ui_results(data, source, api, serial, lock):
    require(isinstance(data, dict) and type(data.get('schemaVersion')) is int and data['schemaVersion'] == 4
            and data.get('kind') == 'third-party-app-set-smoke' and data.get('sampleId') == SAMPLE_ID, 'Invalid app-set UI format')
    require(data.get('keyboardPolicy') == KEYBOARD_POLICY and data.get('navigationPolicy') == NAVIGATION_POLICY,
            'UI editing contract differs from the locked version-four profile')
    binding = data.get('source', {})
    require(isinstance(binding, dict) and binding.get('sha') == source['commit']
            and str(binding.get('runId')) == str(source['runId']) and str(binding.get('attempt')) == str(source['attempt'])
            and str(binding.get('expectedApi')) == str(api) and binding.get('deviceSerial') == serial, 'UI results belong to another source or device')
    entries = validate_app_results(data.get('apps'), lock)
    if any(state['status'] == 'passed' for entry in entries for state in entry['states']):
        require(data.get('dedicatedEmulatorVerified') is True and isinstance(data.get('device'), dict)
                and type(data['device'].get('sdkInt')) is int and data['device']['sdkInt'] == api, 'UI emulator identity not verified')
    return entries


def run_instrumentation(command, args, source, adb, serial):
    """Optionally record the unchanged four-case invocation into another artifact.

    Keep the callback result separately so even a recorder error after execution
    cannot replace the compatibility result or cause a second UI invocation.
    """
    called = False
    result = None
    failure = None

    def execute():
        nonlocal called, result, failure
        require(not called, 'Do not repeat the compatibility instrumentation')
        called = True
        try:
            result = base.run_command(command, timeout=600)
            return result
        except Exception as error:
            failure = error
            raise

    requested = getattr(args, 'video_output', None)
    if requested is None:
        return execute()
    try:
        require(args.api == '35', 'Only the representative API35 run may record')
        video = Path(requested)
        location, evidence = video.resolve(), Path(args.output).resolve()
        require(location != evidence and location not in evidence.parents and evidence not in location.parents,
                'Video and original probe evidence must use separate directories')
        from probe_video import record_call
    except (ValueError, OSError, ImportError):
        # No safe recording destination/module is available. The workflow's
        # independent video artifact remains missing; never turn that into a UI
        # retry or a different four-case result.
        print('Third-party video preparation unavailable; running the unchanged probe')
        return execute()
    try:
        record_call(video, source, 'third-party-app-set', 35, adb, serial, execute)
    except Exception:
        if failure is None:
            print('Third-party recording unavailable; preserving the original probe result')
    if not called:
        return execute()
    if failure is not None:
        raise failure
    return result


def run_probe(args, lock, source):
    report = empty_report(lock, source, args.api)
    directory = base.ensure_directory(args.output)
    write_report(directory, report)
    api = APIS[args.api]; stage = 'device_preflight'
    try:
        sdk = Path(args.sdk_root); adb = str(sdk / 'platform-tools/adb'); build_tools = sdk / 'build-tools/35.0.0'
        report['emulatorVersion'] = base.validate_emulator_version((sdk / 'emulator/source.properties').read_text(), api)
        serial = base.select_emulator(base.run_command([adb, 'devices']))
        def shell(*words, **kwargs):
            return base.run_command([adb, '-s', serial, 'shell', *words], **kwargs).strip()
        properties = {key: shell('getprop', key) for key in ('ro.kernel.qemu', 'ro.boot.qemu', 'ro.build.version.sdk',
                     'ro.product.cpu.abi', 'sys.boot_completed', 'ro.build.fingerprint')}
        base.validate_device(serial, properties, api)
        require(shell('am', 'get-current-user') == '0', 'Only the disposable emulator owner profile is supported')
        report['device'] = {'serial': serial, 'apiLevel': api, 'fingerprint': properties['ro.build.fingerprint'],
                            'abi': properties['ro.product.cpu.abi'], 'kernelQemu': properties['ro.kernel.qemu'], 'bootQemu': properties['ro.boot.qemu']}
        report['driverSteps'].append({'name': stage, 'status': 'passed'})
        paths = {APP_IDS[0]: args.calculator_apk, APP_IDS[1]: args.notes_apk}
        report['verifiedDownloads'] = {}
        for item in lock['apps']:
            stage = 'apk_verification_' + item['appId']
            report['verifiedDownloads'][item['appId']] = base.verify_apk(Path(paths[item['appId']]), item['app'], build_tools)
            report['driverSteps'].append({'name': stage, 'status': 'passed'})
        stage = 'fresh_install_precondition'
        installed = shell('pm', 'list', 'packages').splitlines()
        packages = [base.AGENT_PACKAGE, base.PROBE_PACKAGE, base.PROBE_PACKAGE + '.test'] + [item['app']['package'] for item in lock['apps']]
        require(all('package:' + package not in installed for package in packages), 'A probe package is already installed; refusing to clear existing data')
        report['driverSteps'].append({'name': stage, 'status': 'passed'})
        installs = [('install_agent', args.agent_apk), ('install_calculator', args.calculator_apk), ('install_notes', args.notes_apk),
                    ('install_probe', args.probe_apk), ('install_probe_tests', args.test_apk)]
        for name, path in installs:
            stage = name; path = Path(path)
            require(path.is_file() and not path.is_symlink(), 'Missing CI-built APK')
            flags = ['-t'] if name in ('install_probe', 'install_probe_tests') else []
            output = base.run_command([adb, '-s', serial, 'install', *flags, str(path)], timeout=120)
            require(re.search(r'^Success\s*$', output, re.M) is not None, 'Installation did not report success')
            report['driverSteps'].append({'name': name, 'status': 'passed', 'sha256': base.sha256_file(path)})
        stage = 'instrumentation'
        parameters = {'class': 'dev.agentos.compat.ApplicationSetCompatibilityInstrumentedTest',
                      'syntheticTestMarker': 'agent-os-third-party-smoke-v4',
                      'freshInstallMarker': 'agent-os-third-party-fresh-install-v4', 'sampleId': SAMPLE_ID,
                      'deviceSerial': serial, 'expectedApi': str(api), 'sourceSha': source['commit'],
                      'runId': str(source['runId']), 'runAttempt': str(source['attempt'])}
        command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r']
        for key, value in parameters.items(): command.extend(['-e', key, value])
        command.append(base.PROBE_PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
        try:
            output = run_instrumentation(command, args, source, adb, serial); command_completed = True
        except Exception as error:
            output = 'Instrumentation command failed: ' + type(error).__name__ + ': ' + str(error)[:1600]; command_completed = False
        (directory / 'instrumentation.json').write_text(json.dumps({'commandCompleted': command_completed, 'output': output}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        instrument_passed = command_completed and bool(re.search(r'\bOK \(4 tests\)', output)) and not any(
            token in output for token in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=', 'Process crashed'))
        report['driverSteps'].append({'name': stage, 'status': 'passed' if instrument_passed else 'failed'})
        stage = 'collect_ui_results'
        raw = base.run_command([adb, '-s', serial, 'exec-out', 'run-as', base.PROBE_PACKAGE,
                                'cat', 'files/third-party-smoke-v4/ui-results.json'], binary=True, limit=base.MAX_JSON)
        ui = base.parse_json(raw)
        entries = validate_ui_results(ui, source, api, serial, lock)
        report['uiResultsSha256'] = hashlib.sha256(raw).hexdigest()
        (directory / 'ui-results.json').write_bytes(raw)
        def fetch_capture(path):
            return base.run_command([adb, '-s', serial, 'exec-out', 'run-as', base.PROBE_PACKAGE, 'cat',
                                     'files/third-party-smoke-v4/' + path], binary=True, limit=base.MAX_CAPTURE)
        capture_errors = []
        for stored, observed in zip(report['apps'], entries):
            require(stored['appId'] == observed['appId'], 'UI application order changed')
            for key in ('states', 'actualInstalledIdentityVerified', 'installedIdentityFailureSeen'): stored[key] = observed[key]
            errors = base.collect_captures(stored['states'], directory, fetch_capture)
            capture_errors.extend(dict(item, appId=stored['appId']) for item in errors)
        report['driverSteps'].append({'name': stage, 'status': 'failed' if capture_errors else 'passed'})
        if capture_errors: report['captureCollectionErrors'] = capture_errors
        report['status'] = 'passed' if instrument_passed and not capture_errors and all(
            state['status'] == 'passed' for item in report['apps'] for state in item['states']) else 'failed'
        return report['status'] == 'passed'
    except Exception as error:
        report['status'] = 'failed' if stage in ('instrumentation', 'collect_ui_results') else 'environment_failed'
        report['driverSteps'].append({'name': stage, 'status': 'failed'})
        report['error'] = {'stage': stage, 'type': type(error).__name__, 'message': str(error)[:1600]}
        return False
    finally:
        write_report(directory, report)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sample', type=Path, default=DEFAULT_SAMPLE)
    commands = parser.add_subparsers(dest='command', required=True)
    download = commands.add_parser('download'); download.add_argument('--output', type=Path, required=True)
    download.add_argument('--app-id', choices=APP_IDS, required=True)
    for name in ('prepare', 'run', 'finalize'):
        command = commands.add_parser(name); command.add_argument('--api', choices=APIS, required=True)
        command.add_argument('--output', type=Path, required=True)
        if name == 'run':
            command.add_argument('--sdk-root', required=True)
            command.add_argument('--video-output', type=Path,
                                 help='Optional independent continuous recording artifact (API35 only)')
            for flag in ('agent-apk', 'probe-apk', 'test-apk', 'calculator-apk', 'notes-apk'):
                command.add_argument('--' + flag, type=Path, required=True)
        if name == 'finalize':
            command.add_argument('--outcome', choices=('success', 'failure', 'cancelled', 'skipped', 'unknown'), required=True)
    args = parser.parse_args(argv)
    try:
        lock = load_lock(args.sample)
        if args.command == 'download':
            app = next(item['app'] for item in lock['apps'] if item['appId'] == args.app_id)
            base.download_apk(app, args.output)
            print('Official release size and SHA-256 verified'); return 0
        source = base.source_from_environment()
        if args.command == 'run': return 0 if run_probe(args, lock, source) else 1
        if args.command == 'prepare': report = empty_report(lock, source, args.api)
        else:
            path = args.output / 'report.json'
            report = base.read_json(path) if path.exists() else empty_report(lock, source, args.api)
            expected = empty_report(lock, source, args.api)
            require(all(report.get(key) == expected[key] for key in ('schemaVersion', 'kind', 'sampleId', 'lockSha256', 'source', 'matrixLabel')),
                    'Cannot finalize a different sample or source')
            report['emulatorStepOutcome'] = args.outcome
            if args.outcome != 'success' and report['status'] == 'passed':
                report['status'] = 'environment_failed'; report['error'] = {'stage': 'emulator_runner', 'message': 'Emulator step did not complete successfully'}
        write_report(args.output, report); return 0
    except (ProbeError, OSError, ValueError, urllib.error.URLError) as error:
        print('Third-party app-set probe: ' + str(error)); return 2


if __name__ == '__main__':
    raise SystemExit(main())
