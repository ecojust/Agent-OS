#!/usr/bin/env python3
"""One source-bound API35 busy-stop walkthrough, separate from performance sampling."""
import argparse
import json
from pathlib import Path
import re
import subprocess

import collect_android_evidence as captures
import demo_video as media
import pinned_calculator_page as source_binding
import probe_video
import third_party_smoke as bounded

WORKFLOW = '.github/workflows/busy-stop-video.yml'
PROFILE = 'busy-stop-platform'
JOB = 'record-busy-stop (API 35)'
TEST_CLASS = 'dev.agentos.app.BusyStopInstrumentedTest'
METHOD = 'recordBusyStopWalkthrough'
SELECTION = TEST_CLASS + '#' + METHOD
FLOW_PATH = 'files/busy-stop-video/flow-result.json'
MAX_FLOW = 64 * 1024
MAX_INSTRUMENTATION = 196608
MAX_CAPTURE_FILES = 48
MAX_PROBE_BYTES = 16 * 1024 * 1024
FAILURE_STAGES = (None, 'not_started', 'device_preflight', 'instrumentation',
                  'collect_flow', 'collect_captures', 'environment')
require = bounded.require


def source(env=None):
    return source_binding.source(env, workflow=WORKFLOW)


def empty_report(binding):
    probe_video._source(binding)
    return dict(schemaVersion=1, kind='agent-os-busy-stop-video-probe', source=binding,
                apiLevel=35, profile=PROFILE, status='unverified', expectedTests=1,
                expectedMatrixLabels=['35'],
                performanceSample=False, secureFlagsPreserved=True, testSelection=SELECTION,
                failureStage='not_started', instrumentation=None, flow=None, capture=None)


def write_json(path, value):
    require(not path.is_symlink(), 'Linked recording result')
    data = (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode()
    require(len(data) <= bounded.MAX_JSON, 'Recording result exceeded its limit')
    path.write_bytes(data)


def file_ref(path):
    return dict(path=path.name, **media.file_record(path))


def prepare(directory, binding):
    directory = bounded.ensure_directory(directory)
    require(not any(directory.iterdir()), 'Do not overwrite a recording attempt')
    write_json(directory / 'report.json', empty_report(binding))


def validate_flow(value, binding):
    media.exact(value, ('schemaVersion', 'kind', 'source', 'apiLevel', 'profile', 'scenario',
        'status', 'performanceSample', 'secureFlagsPreserved', 'startedNanoTime', 'endedNanoTime',
        'trial', 'failure'))
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1
            and value['kind'] == 'agent-os-busy-stop-walkthrough'
            and media.matches_source(value['source'], binding)
            and type(value['apiLevel']) is int and value['apiLevel'] == 35
            and value['profile'] == 'walkthrough' and value['scenario'] == 'DEVICE'
            and value['performanceSample'] is False and value['secureFlagsPreserved'] is True,
            'Foreign or unsafe busy-stop walkthrough')
    require(value['status'] in ('passed', 'failed', 'incomplete')
            and media.nonnegative(value['startedNanoTime']) and value['startedNanoTime'] > 0
            and (value['endedNanoTime'] is None or media.nonnegative(value['endedNanoTime'])
                 and value['endedNanoTime'] >= value['startedNanoTime']), 'Invalid walkthrough interval')
    if value['failure'] is not None:
        media.exact(value['failure'], ('stage', 'errorClass'))
        require(isinstance(value['failure']['stage'], str)
                and re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,79}', value['failure']['stage'])
                and isinstance(value['failure']['errorClass'], str)
                and re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_.$]{0,159}', value['failure']['errorClass']),
                'Unsafe walkthrough failure')
    trial = value['trial']
    if trial is not None:
        # This strict single-trial API does not admit a walkthrough root into
        # pilot/formal or change their fixed denominators.
        import collect_busy_stop_evidence as busy_stop
        busy_stop.validate_single_device_trial(trial, 35)
        require(trial['startedNanoTime'] >= value['startedNanoTime'], 'Trial precedes walkthrough')
        if value['endedNanoTime'] is not None and trial['endedNanoTime'] is not None:
            require(trial['endedNanoTime'] <= value['endedNanoTime'], 'Trial exceeds walkthrough')
    if value['status'] == 'passed':
        require(value['failure'] is None and value['endedNanoTime'] is not None
                and value['endedNanoTime'] - value['startedNanoTime'] <= 90_000_000_000
                and trial is not None and trial['status'] == 'passed',
                'Passed walkthrough lacks its one completed DEVICE trial')
    else:
        require(value['status'] != 'failed' or value['failure'] is not None,
                'Failed walkthrough lacks an original failure')
    return value


def instrumentation_result(value):
    media.exact(value, ('schemaVersion', 'runner', 'selectedClass', 'selectedMethod',
                       'expectedTests', 'status', 'exitCode', 'stdout'))
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1
            and value['runner'] == 'direct_adb_instrumentation' and value['selectedClass'] == TEST_CLASS
            and value['selectedMethod'] == METHOD and type(value['expectedTests']) is int
            and value['expectedTests'] == 1
            and value['status'] in ('completed', 'timed_out', 'output_limit', 'cancelled')
            and (value['exitCode'] is None or type(value['exitCode']) is int and -128 <= value['exitCode'] <= 255)
            and isinstance(value['stdout'], str)
            and len(value['stdout'].encode()) <= MAX_INSTRUMENTATION, 'Invalid busy-stop instrumentation record')
    text = value['stdout']
    pending, blocks = {}, []
    malformed = False
    for line in text.splitlines():
        field = re.fullmatch(r'INSTRUMENTATION_STATUS: ([^=]+)=(.*)', line)
        if field:
            malformed |= field[1] in pending
            pending[field[1]] = field[2]
        code = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
        if code:
            blocks.append((pending, int(code[1]))); pending = {}
    selected = [code for fields, code in blocks if fields.get('class') == TEST_CLASS
                and fields.get('test') == METHOD and fields.get('numtests') == '1']
    passed = (value['status'] == 'completed' and media.instrument_passed(text, value['exitCode'])
              and not malformed and not pending and len(blocks) == 2 and selected == [1, 0]
              and re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', text, re.M) == ['-1'])
    terminal_failure = any(code in (-1, -2) for code in selected)
    return dict(passed=passed, started=1 in selected, terminalFailure=terminal_failure)


def validate_capture(value, files):
    require(isinstance(value, dict) and type(value.get('schemaVersion')) is int
            and value['schemaVersion'] == 1 and type(value.get('apiLevel')) is int and value['apiLevel'] == 35
            and value.get('matrixLabel') == '35'
            and type(value.get('gradleExitCode')) is int
            and value.get('captureStatus') in ('collected', 'empty', 'unavailable'),
            'Invalid walkthrough screenshot capture')
    names = value.get('files')
    require(isinstance(names, list) and len(names) <= MAX_CAPTURE_FILES
            and all(isinstance(name, str) and re.fullmatch(r'screenshots/[A-Za-z0-9_.-]+\.(json|png)', name)
                    for name in names) and len(names) == len(set(names)),
            'Invalid bounded screenshot inventory')
    require(set(names) == {name for name in files if name.startswith('screenshots/')},
            'Screenshot payload differs from its original capture inventory')
    if value['captureStatus'] != 'collected':
        require(not names, 'Uncollected screenshots cannot carry unverified payloads')
        return dict(status=value['captureStatus'], casePassed=False, captured=0, blocked=0, failed=0)
    require(type(value.get('actualSdkInt')) is int and value['actualSdkInt'] == 35 and 'screenshots/manifest.json' in names,
            'Collected screenshots lack API or manifest')
    manifest = bounded.parse_json(files['screenshots/manifest.json']['data'])
    require(isinstance(manifest, dict) and type(manifest.get('schemaVersion')) is int and manifest['schemaVersion'] == 1 and manifest.get('api') == 35
            and isinstance(manifest.get('tests'), list) and len(manifest['tests']) == 1,
            'Walkthrough screenshots must have one original case')
    case_files = [name for name in names if name.endswith('.json') and name != 'screenshots/manifest.json']
    require(len(case_files) == 1, 'Missing or duplicate original walkthrough screenshot case')
    case = bounded.parse_json(files[case_files[0]]['data'])
    require(isinstance(case, dict) and case == manifest['tests'][0] and type(case.get('schemaVersion')) is int
            and case['schemaVersion'] == 1 and case.get('api') == 35
            and case.get('testClass') == TEST_CLASS and case.get('testName') == METHOD
            and case.get('status') in ('pass', 'fail')
            and isinstance(case.get('captures'), list) and len(case['captures']) <= 20,
            'Foreign or invalid walkthrough screenshot case')
    counts = {'pass': 0, 'blocked': 0, 'fail': 0}; image_paths = set()
    for shot in case['captures']:
        require(isinstance(shot, dict) and shot.get('status') in counts
                and shot.get('secureWindowTemporarilyCleared') is not True,
                'Unsafe or invalid walkthrough capture')
        counts[shot['status']] += 1
        if shot['status'] == 'pass':
            name = shot.get('png')
            require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9_.-]+\.png', name)
                    and shot.get('foregroundPackage') == 'dev.agentos.fixture'
                    and shot.get('secureWindowTemporarilyCleared') is False,
                    'Successful capture did not preserve the synthetic foreground/security contract')
            path = 'screenshots/' + name
            require(path in files and path not in image_paths, 'Missing or duplicate screenshot image')
            image_paths.add(path); raw = files[path]['data']
            require(len(raw) >= 24 and raw.startswith(captures.PNG_SIGNATURE)
                    and int.from_bytes(raw[16:20], 'big') == shot.get('width')
                    and int.from_bytes(raw[20:24], 'big') == shot.get('height'), 'Screenshot dimensions differ')
        else:
            require('png' not in shot, 'Blocked/failed capture cannot claim an image')
    require(image_paths == {name for name in names if name.endswith('.png')}, 'Unreferenced screenshot image')
    return dict(status='collected', casePassed=case.get('status') == 'pass'
                and case.get('testResult') == 'pass' and case.get('assertionResult') == 'pass',
                captured=counts['pass'], blocked=counts['blocked'], failed=counts['fail'])


def validate_probe(report, files, binding):
    media.exact(report, empty_report(binding).keys())
    fixed = empty_report(binding)
    for key in ('schemaVersion', 'kind', 'source', 'apiLevel', 'profile', 'expectedTests',
                'performanceSample', 'secureFlagsPreserved', 'testSelection', 'expectedMatrixLabels'):
        require(type(report[key]) is type(fixed[key]) and report[key] == fixed[key],
                'Foreign walkthrough probe identity or scope: ' + key)
    probe_video._source(report['source'])
    require(report['status'] in ('unverified', 'passed', 'failed')
            and report['failureStage'] in FAILURE_STAGES, 'Invalid probe status')
    allowed = {'report.json'}
    parsed = {}
    for key, filename, maximum in (('instrumentation', 'instrumentation.json', bounded.MAX_JSON),
                                    ('flow', 'flow-result.json', MAX_FLOW),
                                    ('capture', 'capture.json', 16 * 1024)):
        reference = report[key]
        require((reference is not None) == (filename in files), 'Missing/unreferenced probe file: ' + filename)
        if reference is None:
            continue
        media.exact(reference, ('path', 'bytes', 'sha256'))
        item = files[filename]
        require(reference['path'] == filename and type(reference['bytes']) is int
                and 0 < reference['bytes'] <= maximum and reference['bytes'] == item['bytes']
                and reference['sha256'] == item['sha256'], 'Probe file identity mismatch: ' + filename)
        parsed[key] = bounded.parse_json(item['data']); allowed.add(filename)
    require(all(name in allowed or name.startswith('screenshots/') for name in files)
            and len(files) <= MAX_CAPTURE_FILES + 4
            and sum(item['bytes'] for item in files.values()) <= MAX_PROBE_BYTES,
            'Unexpected or oversized walkthrough probe payload')
    original = instrumentation_result(parsed['instrumentation']) if 'instrumentation' in parsed else None
    flow = validate_flow(parsed['flow'], binding) if 'flow' in parsed else None
    pictures = validate_capture(parsed['capture'], files) if 'capture' in parsed else None
    if original and pictures:
        require(parsed['capture']['gradleExitCode'] == (parsed['instrumentation']['exitCode']
                if parsed['instrumentation']['exitCode'] is not None else 1),
                'Screenshot capture/instrumentation exit codes differ')
    require(pictures is not None or not any(name.startswith('screenshots/') for name in files),
            'Unbound screenshot evidence')
    complete = bool(original and original['passed'] and flow and flow['status'] == 'passed'
                    and pictures and pictures['casePassed'])
    require((report['status'] == 'passed') == complete
            and (report['failureStage'] is None) == complete, 'Probe status contradicts its original evidence')
    status = ('passed' if complete else 'failed' if (original and original['terminalFailure'])
              or (flow and flow['status'] == 'failed') else 'unverified')
    return dict(status=status, driverStatus=report['status'],
                cases=[dict(name=METHOD, status=status)], captureCounts=pictures,
                instrumentation=original, flowStatus=flow['status'] if flow else 'unavailable',
                performanceSample=False, fullSuiteAcceptance=False)


def record(directory, video_directory, binding):
    directory = bounded.ensure_directory(directory)
    path = directory / 'report.json'
    report = bounded.read_json(path)
    require(report == empty_report(binding) and {p.name for p in directory.iterdir()} == {'report.json'},
            'Do not replay or overwrite a walkthrough attempt')
    serial = None; stage = 'device_preflight'; first_failure = None

    def fail(where):
        nonlocal first_failure
        if first_failure is None: first_failure = where

    def adb(*arguments, **kwargs):
        return bounded.run_command(['adb', '-s', serial, *arguments], **kwargs)

    report.update(status='failed', failureStage=stage)
    write_json(path, report)
    try:
        serial = bounded.select_emulator(bounded.run_command(['adb', 'devices'], timeout=5))
        properties = {key: adb('shell', 'getprop', key, timeout=5).strip() for key in
                      ('ro.kernel.qemu', 'ro.boot.qemu', 'ro.build.version.sdk', 'ro.product.cpu.abi', 'sys.boot_completed')}
        bounded.validate_device(serial, properties, 35)
        for remote in ('files/busy-stop-video', 'files/screenshots'):
            adb('shell', 'run-as', 'dev.agentos.app', 'test', '!', '-e', remote, timeout=5)
        parameters = dict(**{'class': SELECTION}, busyStopDemoMarker='agent-os-busy-stop-video-v1',
                          expectedApi='35', sourceSha=binding['commit'], runId=str(binding['runId']),
                          runAttempt=str(binding['attempt']))
        command = ['adb', '-s', serial, 'shell', 'am', 'instrument', '-w', '-r']
        for key, value in parameters.items(): command.extend(['-e', key, value])
        command.append('dev.agentos.app.test/androidx.test.runner.AndroidJUnitRunner')
        stage = 'instrumentation'

        def invoke():
            from run_bounded_instrumentation import bounded_capture
            output, outcome = bounded_capture(command, 105, 64 * 1024)
            if outcome['status'] != 'completed':
                # Killing adb does not stop the remote instrumentation. This is
                # one dedicated synthetic device, not a replay or data reset.
                try: adb('shell', 'am', 'force-stop', 'dev.agentos.app', timeout=5)
                except (OSError, ValueError, subprocess.SubprocessError): fail('instrumentation')
            return dict(schemaVersion=1, runner='direct_adb_instrumentation', selectedClass=TEST_CLASS,
                        selectedMethod=METHOD, expectedTests=1, status=outcome['status'],
                        exitCode=outcome['exitCode'], stdout=output.decode('utf-8', 'replace').replace('\r\n', '\n'))

        result = probe_video.record_call(video_directory, binding, PROFILE, 35, 'adb', serial, invoke)
        write_json(directory / 'instrumentation.json', result)
        report['instrumentation'] = file_ref(directory / 'instrumentation.json')
        if not instrumentation_result(result)['passed']: fail(stage)
    except (OSError, ValueError, subprocess.SubprocessError):
        fail(stage)
    finally:
        if serial is not None:
            try:
                raw = adb('exec-out', 'run-as', 'dev.agentos.app', 'cat', FLOW_PATH,
                          timeout=10, binary=True, limit=MAX_FLOW)
                # Keep original bytes even if semantic verification fails.
                (directory / 'flow-result.json').write_bytes(raw)
                report['flow'] = file_ref(directory / 'flow-result.json')
                value = validate_flow(bounded.parse_json(raw), binding)
                if value['status'] != 'passed': fail('collect_flow')
            except (OSError, ValueError, subprocess.SubprocessError): fail('collect_flow')
            try:
                exit_code = result['exitCode'] if report['instrumentation'] else 1
                value = captures.collect(35, '35', exit_code if exit_code is not None else 1, directory)
                write_json(directory / 'capture.json', value)
                report['capture'] = file_ref(directory / 'capture.json')
                import third_party_smoke_archive as archive_bounds
                files = archive_bounds.scan_files(directory, dict(files=0, bytes=0))
                if not validate_capture(value, files)['casePassed']: fail('collect_captures')
            except (OSError, ValueError, subprocess.SubprocessError): fail('collect_captures')
        report['failureStage'] = first_failure
        report['status'] = 'passed' if first_failure is None else 'failed'
        write_json(path, report)
    return report['status'] == 'passed'


def finalize(directory, binding):
    directory = bounded.ensure_directory(directory)
    path = directory / 'report.json'
    if not path.exists(): prepare(directory, binding)
    value = bounded.read_json(path)
    require(value.get('source') == binding, 'Cannot finalize another recording source')
    if value.get('status') == 'unverified':
        value['failureStage'] = 'environment'
        write_json(path, value)
    return value


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('prepare', 'record', 'finalize'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--video-output', type=Path)
    args = parser.parse_args(argv)
    try:
        binding = source()
        if args.command == 'prepare': prepare(args.output, binding)
        elif args.command == 'finalize': finalize(args.output, binding)
        else:
            require(args.video_output is not None, 'Recording requires a separate video output directory')
            require(args.video_output.resolve() != args.output.resolve()
                    and args.output.resolve() not in args.video_output.resolve().parents
                    and args.video_output.resolve() not in args.output.resolve().parents,
                    'Video and original probe outputs must be separate')
            return 0 if record(args.output, args.video_output, binding) else 1
        return 0
    except (ValueError, OSError) as error:
        print('Busy-stop walkthrough rejected: ' + str(error))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
