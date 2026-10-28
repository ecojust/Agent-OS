#!/usr/bin/env python3
"""One controlled, same-signer APK replacement on a fresh dedicated CI emulator."""
import argparse
import base64
import contextlib
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import signal
import subprocess
import tempfile
import threading
import time

import check_apk_upgrade as preflight
import third_party_smoke as device_rules

REPOSITORY = 'ecojust/Agent-OS'
WORKFLOW = 'apk-upgrade-smoke.yml'
BASELINE_SOURCE = 'b1fd337262775a505327088975a3320fe323f034'
APIS = {str(n): n for n in range(30, 37)} | {'37.0': 37}
PACKAGE = 'dev.agentos.app'
FIXTURE = 'dev.agentos.fixture'
PROBE = 'dev.agentos.upgradesmoke.test'
SERVICE = PACKAGE + '/dev.agentos.app.AgentAccessibilityService'
COMPONENT = PROBE + '/androidx.test.runner.AndroidJUnitRunner'
TEST_CLASS = 'dev.agentos.upgradesmoke.ApkUpgradeInstrumentedTest'
CASE_ID = 'same-signer-in-place-upgrade-v1'
MARKER = 'agent-os-apk-upgrade-v1'
PHASES = ('bundle_preflight', 'device_preflight', 'fresh_install_precondition',
          'install_baseline', 'install_fixture', 'install_probe', 'baseline_identity',
          'service_setup', 'model_setup', 'seed_baseline', 'baseline_main_process', 'replace_candidate',
          'candidate_identity', 'verify_candidate', 'cleanup')
APK_NAMES = ('baseline.apk', 'candidate.apk', 'fixture.apk', 'probe.apk')
BUNDLE_NAMES = (*APK_NAMES, 'signing-certificate.der', 'build-manifest.json', 'preflight.json')
MAX_OUTPUT = 1024 * 1024
MAX_COMMANDS = 80
MAX_TOTAL_OUTPUT = 16 * 1024 * 1024
CAPTURE_REASON = 'secure_apk_upgrade_capture_not_attempted'
SHA = re.compile(r'[0-9a-f]{64}')


class Rejected(ValueError):
    """Fixed reason codes only; command output is retained separately."""


def require(condition, reason):
    if not condition:
        raise Rejected(reason)


def source_from_environment(env=None):
    env = os.environ if env is None else env
    require(env.get('GITHUB_ACTIONS') == 'true' and env.get('RUNNER_ENVIRONMENT') == 'github-hosted', 'DEDICATED_CI_REQUIRED')
    require(env.get('GITHUB_REPOSITORY') == REPOSITORY and env.get('GITHUB_REF') == 'refs/heads/master'
            and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'SOURCE_SCOPE_REJECTED')
    require(env.get('GITHUB_WORKFLOW_REF') == f'{REPOSITORY}/.github/workflows/{WORKFLOW}@refs/heads/master', 'WORKFLOW_REJECTED')
    require(env.get('GITHUB_WORKFLOW') == 'Development APK in-place upgrade', 'WORKFLOW_REJECTED')
    commit = env.get('GITHUB_SHA', '')
    require(re.fullmatch('[0-9a-f]{40}', commit) is not None, 'SOURCE_SHA_INVALID')
    values = []
    for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
        value = env.get(key, '')
        require(re.fullmatch('[1-9][0-9]{0,18}', value) is not None, 'RUN_IDENTITY_INVALID')
        values.append(int(value))
    return dict(repository=REPOSITORY, runId=values[0], attempt=values[1], commit=commit)


def file_identity(path):
    return preflight.digest(Path(path))


def read_json(path):
    try:
        return device_rules.read_json(path)
    except (OSError, ValueError):
        raise Rejected('JSON_INVALID') from None


def write_payload(path, data):
    path = Path(path)
    require(not path.is_symlink(), 'OUTPUT_LINK_REJECTED')
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.upgrade-pending-', delete=False) as output:
            temporary = Path(output.name)
            output.write(data)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def write_json(path, value):
    data = (json.dumps(value, sort_keys=True, ensure_ascii=True, indent=2) + '\n').encode()
    require(len(data) <= 3 * MAX_OUTPUT, 'JSON_OUTPUT_LIMIT')
    write_payload(path, data)


def validate_build_manifest(value, source, identities=None):
    require(isinstance(value, dict) and set(value) == {'schemaVersion', 'kind', 'source', 'baselineSource',
            'signingPolicy', 'certificateSha256', 'files'}, 'BUILD_MANIFEST_FIELDS')
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1 and value['kind'] == 'agentos-apk-upgrade-build'
            and value['source'] == source and value['baselineSource'] == BASELINE_SOURCE
            and value['signingPolicy'] == 'temporary-development-certificate', 'BUILD_SOURCE_MISMATCH')
    require(isinstance(value['certificateSha256'], str) and SHA.fullmatch(value['certificateSha256']), 'CERTIFICATE_PIN_INVALID')
    require(isinstance(value['files'], dict) and set(value['files']) == {*APK_NAMES, 'signing-certificate.der', 'preflight.json'}, 'BUILD_FILESET_INVALID')
    for name, item in value['files'].items():
        require(isinstance(item, dict) and set(item) == {'bytes', 'sha256'} and type(item['bytes']) is int
                and 0 < item['bytes'] <= preflight.MAX_APK_BYTES and isinstance(item['sha256'], str)
                and SHA.fullmatch(item['sha256']), 'BUILD_FILE_IDENTITY_INVALID')
        if identities is not None:
            require(item == identities.get(name), 'BUILD_FILE_CHANGED')
    require(value['files']['signing-certificate.der']['sha256'] == value['certificateSha256']
            and value['files']['signing-certificate.der']['bytes'] <= 16384, 'CERTIFICATE_PIN_MISMATCH')
    return value


def validate_preflight(value, manifest):
    require(isinstance(value, dict) and type(value.get('schemaVersion')) is int and value.get('schemaVersion') == 1
            and value.get('kind') == 'agentos-offline-apk-upgrade-preflight'
            and value.get('status') == 'passed' and value.get('signingPolicy') == 'same-current-certificate-pinned'
            and value.get('lineageEvaluated') is False and value.get('installationPerformed') is False
            and value.get('trustedCertificateSha256') == manifest['certificateSha256'], 'PREFLIGHT_INVALID')
    for role, version in (('baseline', 1), ('candidate', 2)):
        item = value.get(role, {})
        require(item.get('package') == PACKAGE and type(item.get('versionCode')) is int and item.get('versionCode') == version
                and type(item.get('minSdk')) is int and item.get('minSdk') == 30
                and type(item.get('targetSdk')) is int and item.get('targetSdk') == 35, 'APK_VERSION_OR_SDK_MISMATCH')
        require({key: item.get(key) for key in ('bytes', 'sha256')} == manifest['files'][role + '.apk'], 'PREFLIGHT_APK_MISMATCH')
        require(type(item.get('currentSignerCount')) is int and item.get('currentSignerCount') == 1
                and item.get('currentSignerSha256') == manifest['certificateSha256'], 'PREFLIGHT_CERTIFICATE_MISMATCH')


def describe_build(bundle, sdk_root, source):
    bundle = Path(bundle)
    require(bundle.is_dir() and not bundle.is_symlink(), 'BUNDLE_INVALID')
    certificate = bundle / 'signing-certificate.der'
    require(certificate.is_file() and not certificate.is_symlink() and 0 < certificate.stat().st_size <= 16384, 'CERTIFICATE_INVALID')
    pin = file_identity(certificate)['sha256']  # Exported independently from the job's controlled JKS.
    tools = Path(sdk_root) / 'build-tools/35.0.0'
    checked = preflight.preflight(bundle / 'baseline.apk', bundle / 'candidate.apk', pin,
                                  tools / 'apksigner', tools / 'aapt2')
    write_json(bundle / 'preflight.json', checked)
    identities = {}
    for name in (*APK_NAMES, 'signing-certificate.der', 'preflight.json'):
        path = bundle / name
        require(path.is_file() and not path.is_symlink(), 'BUNDLE_FILE_INVALID')
        identities[name] = file_identity(path)
    result = dict(schemaVersion=1, kind='agentos-apk-upgrade-build', source=source,
                  baselineSource=BASELINE_SOURCE, signingPolicy='temporary-development-certificate',
                  certificateSha256=pin, files=identities)
    validate_build_manifest(result, source, identities)
    validate_preflight(checked, result)
    write_json(bundle / 'build-manifest.json', result)
    return result


def parse_package_dump(output, version):
    def one(pattern):
        values = re.findall(pattern, output, re.M)
        require(len(values) == 1, 'INSTALLED_PACKAGE_METADATA_AMBIGUOUS')
        return values[0]
    require(len(re.findall(r'^\s*Package \[dev\.agentos\.app\]', output, re.M)) == 1, 'INSTALLED_PACKAGE_MISMATCH')
    observed = int(one(r'^\s*versionCode=([0-9]+)\b'))
    require(observed == version, 'INSTALLED_VERSION_MISMATCH')
    # Settings.dumpPackageLPr calls this userId on Android 11, appId on 15/16.
    # The device gate permits only user 0; either is therefore the actual UID.
    uid = int(one(r'^\s*(?:userId|appId)=([0-9]+)\s*$'))
    require(10000 <= uid < 100000, 'INSTALLED_UID_INVALID')
    first = one(r'^\s*firstInstallTime=([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2})\s*$')
    return dict(package=PACKAGE, versionCode=version, uid=uid, firstInstallTime=first)


def validate_installed_transition(before, after, old_pid, new_pid):
    require(before['package'] == after['package'] == PACKAGE and before['versionCode'] == 1
            and after['versionCode'] == 2, 'UPGRADE_VERSION_INVALID')
    require(before['uid'] == after['uid'] and before['firstInstallTime'] == after['firstInstallTime'], 'INSTALLATION_NOT_CONTINUOUS')
    require(type(old_pid) is int and type(new_pid) is int and old_pid > 0 and new_pid > 0
            and old_pid != new_pid, 'PROCESS_NOT_REPLACED')


def parse_instrumentation(output, method):
    require(method in ('seedBaseline', 'verifyCandidate'), 'INSTRUMENTATION_METHOD_INVALID')
    require(not any(token in output for token in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'INSTRUMENTATION_ABORTED',
            'shortMsg=', 'Process crashed', 'INSTRUMENTATION_STATUS_CODE: -1', 'INSTRUMENTATION_STATUS_CODE: -2')),
            'INSTRUMENTATION_FAILED')
    statuses = re.findall(r'(?m)^INSTRUMENTATION_STATUS_CODE: (-?[0-9]+)\s*$', output)
    counts = re.findall(r'(?m)^INSTRUMENTATION_STATUS: numtests=([0-9]+)\s*$', output)
    require(statuses.count('1') == 1 and '0' in statuses and set(statuses) <= {'0', '1'}
            and counts and set(counts) == {'1'}, 'INSTRUMENTATION_NOT_ONE_PASS')
    require(len(re.findall(r'(?m)^OK \(1 test\)\s*$', output)) == 1
            and re.findall(r'(?m)^INSTRUMENTATION_CODE: (-?[0-9]+)\s*$', output) == ['-1'], 'INSTRUMENTATION_NOT_ONE_PASS')
    methods = re.findall(r'(?m)^INSTRUMENTATION_STATUS: test=(\S+)\s*$', output)
    classes = re.findall(r'(?m)^INSTRUMENTATION_STATUS: class=(\S+)\s*$', output)
    require(methods and set(methods) == {method} and classes and set(classes) == {TEST_CLASS}, 'INSTRUMENTATION_IDENTITY_MISMATCH')
    receipts = re.findall(r'(?m)^INSTRUMENTATION_STATUS: upgradeReceipt=(\{[^\r\n]*\})\s*$', output)
    require(len(receipts) == 1 and len(receipts[0].encode()) <= 4096, 'RECEIPT_MISSING_OR_AMBIGUOUS')
    try:
        return device_rules.parse_json(receipts[0].encode())
    except ValueError:
        raise Rejected('RECEIPT_JSON_INVALID') from None


COMMON_RECEIPT = {'schemaVersion', 'phase', 'runId', 'runAttempt', 'baselineSource', 'candidateSource', 'api',
                  'nonceSha256', 'targetVersionCode', 'pid', 'targetUid', 'firstInstallTime', 'envelopeSha256',
                  'revisionSha256', 'grantSha256', 'profileSha256', 'settingsVerified', 'fixtureGrantValid',
                  'secureWindowVerified', 'captureStatus', 'captureReason'}
SEED_RECEIPT = {'approvalObserved', 'approvalGranted', 'taskStopped', 'stoppedUsageRetained', 'dispatchAttempts', 'workerDrained'}
VERIFY_RECEIPT = {'freshController', 'noTaskOrApproval', 'noTaskHistory', 'noTaskInput', 'workerDrained'}


def validate_receipt(value, phase, source, label, nonce_sha, seed=None):
    require(phase in ('seedBaseline', 'verifyCandidate'), 'RECEIPT_PHASE_INVALID')
    require(isinstance(value, dict) and set(value) == COMMON_RECEIPT | (SEED_RECEIPT if phase == 'seedBaseline' else VERIFY_RECEIPT), 'RECEIPT_FIELDS_INVALID')
    require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1 and value['phase'] == phase
            and value['runId'] == str(source['runId']) and value['runAttempt'] == str(source['attempt']) and value['baselineSource'] == BASELINE_SOURCE
            and value['candidateSource'] == source['commit'] and type(value['api']) is int and value['api'] == APIS[label]
            and value['nonceSha256'] == nonce_sha, 'RECEIPT_SOURCE_MISMATCH')
    require(type(value['targetVersionCode']) is int and value['targetVersionCode'] == (1 if phase == 'seedBaseline' else 2), 'RECEIPT_VERSION_MISMATCH')
    require(all(type(value[key]) is int and value[key] > 0 for key in ('pid', 'targetUid', 'firstInstallTime')), 'RECEIPT_PROCESS_INVALID')
    for key in ('envelopeSha256', 'revisionSha256', 'grantSha256', 'profileSha256'):
        require(isinstance(value[key], str) and SHA.fullmatch(value[key]), 'RECEIPT_DIGEST_INVALID')
        if seed is not None:
            require(value[key] == seed[key], 'PRESERVED_SETTINGS_MISMATCH')
    require(all(value[key] is True for key in ('settingsVerified', 'fixtureGrantValid', 'secureWindowVerified'))
            and value['captureStatus'] == 'blocked' and value['captureReason'] == CAPTURE_REASON, 'RECEIPT_ASSERTIONS_MISSING')
    if phase == 'seedBaseline':
        require(all(value[key] is True for key in ('approvalObserved', 'taskStopped', 'stoppedUsageRetained', 'workerDrained'))
                and value['approvalGranted'] is False and type(value['dispatchAttempts']) is int and value['dispatchAttempts'] == 0, 'SEED_STOP_NOT_VERIFIED')
    else:
        require(seed is not None and all(value[key] is True for key in VERIFY_RECEIPT), 'CANDIDATE_RESTORATION_NOT_VERIFIED')
        require(value['targetUid'] == seed['targetUid'] and value['firstInstallTime'] == seed['firstInstallTime']
                and value['pid'] != seed['pid'], 'RECEIPT_INSTALLATION_DISCONTINUITY')
    return value


def empty_report(source, label):
    require(label in APIS, 'API_UNSUPPORTED')
    return dict(schemaVersion=1, kind='agentos-apk-upgrade-smoke', source=source,
                baselineSource=BASELINE_SOURCE, matrixLabel=label, apis=list(APIS), caseId=CASE_ID,
                status='unverified', executionAttempted=False, phases=[dict(name=name, status='unverified') for name in PHASES],
                device=None, artifactFiles=[], baselineInstallAttempts=0, candidateInstallAttempts=0,
                requestsAfterSeed=None, requestsAfterVerify=None, nonceSha256=None,
                installedBaseline=None, installedCandidate=None, baselinePidBeforeReplacement=None, candidatePid=None,
                serviceSettingsBefore=None, serviceSettingsRestored=False,
                captures=[dict(phase=phase, status='blocked', reason=CAPTURE_REASON, secureFlagsCleared=False)
                          for phase in ('seedBaseline', 'verifyCandidate')])


def register_file(directory, report, name):
    require(name in ('build-manifest.json', 'preflight.json', 'signing-certificate.der', 'seed-receipt.json', 'verify-receipt.json')
            or re.fullmatch(r'commands/[0-9]{2}-[a-z0-9-]{1,48}\.json', name), 'ARTIFACT_PATH_INVALID')
    require(not any(item['path'] == name for item in report['artifactFiles']), 'ARTIFACT_DUPLICATE')
    report['artifactFiles'].append(dict(path=name, **file_identity(Path(directory) / name)))


class CommandRunner:
    """Preserve bounded raw bytes even on command failure; never execute a shell string."""
    def __init__(self, directory, report):
        self.directory, self.report = Path(directory), report
        self.count, self.total = 0, 0
        (self.directory / 'commands').mkdir(exist_ok=False)

    def run(self, label, argv, timeout=30):
        require(re.fullmatch('[a-z0-9-]{1,48}', label) is not None and self.count < MAX_COMMANDS, 'COMMAND_LIMIT')
        require(isinstance(argv, list) and all(isinstance(item, str) and '\0' not in item for item in argv), 'COMMAND_INVALID')
        self.count += 1
        started = time.monotonic()
        streams = {1: bytearray(), 2: bytearray()}
        process, code, reason = None, None, None
        try:
            process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       start_new_session=True, env={**os.environ, 'LC_ALL': 'C'})
            with selectors.DefaultSelector() as watch:
                watch.register(process.stdout, selectors.EVENT_READ, 1)
                watch.register(process.stderr, selectors.EVENT_READ, 2)
                while watch.get_map():
                    remaining = timeout - (time.monotonic() - started)
                    require(remaining > 0, 'COMMAND_TIMEOUT')
                    for key, _ in watch.select(min(remaining, .1)):
                        size = sum(map(len, streams.values()))
                        chunk = os.read(key.fileobj.fileno(), min(65536, MAX_OUTPUT - size + 1))
                        if not chunk:
                            watch.unregister(key.fileobj)
                            continue
                        available = min(MAX_OUTPUT - size, MAX_TOTAL_OUTPUT - self.total)
                        streams[key.data].extend(chunk[:available]); self.total += min(len(chunk), available)
                        require(len(chunk) <= available, 'COMMAND_OUTPUT_LIMIT')
                remaining = timeout - (time.monotonic() - started)
                require(remaining > 0, 'COMMAND_TIMEOUT')
                try:
                    code = process.wait(timeout=remaining)
                except subprocess.TimeoutExpired:
                    raise Rejected('COMMAND_TIMEOUT') from None
                require(code == 0, 'COMMAND_FAILED')
        except Rejected as failure:
            reason = str(failure)
        except OSError:
            reason = 'COMMAND_UNAVAILABLE'
        finally:
            if process is not None:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                except OSError:
                    reason = reason or 'COMMAND_CLEANUP_FAILED'
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    reason = reason or 'COMMAND_CLEANUP_FAILED'
                process.stdout.close(); process.stderr.close()
            name = f'commands/{self.count:02d}-{label}.json'
            value = dict(schemaVersion=1, label=label, exitCode=code, timeoutSeconds=timeout,
                         elapsedMillis=int((time.monotonic() - started) * 1000), reason=reason,
                         stdoutBase64=base64.b64encode(streams[1]).decode(), stderrBase64=base64.b64encode(streams[2]).decode())
            write_json(self.directory / name, value)
            register_file(self.directory, self.report, name)
            write_json(self.directory / 'report.json', self.report)
        if reason:
            raise Rejected(reason)
        try:
            return bytes(streams[1]).decode('utf-8', 'strict')
        except UnicodeError:
            raise Rejected('COMMAND_ENCODING_INVALID') from None


class ModelServer:
    """One synthetic tool proposal. Request bodies and authorization headers are never logged."""
    def __init__(self):
        self.requests, self.errors = 0, 0
        self.lock = threading.Lock()
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def setup(self):
                super().setup(); self.connection.settimeout(5)

            def log_message(self, *args):
                pass

            def do_POST(self):
                with owner.lock:
                    owner.requests = min(1000, owner.requests + 1)
                    count = owner.requests
                valid = False
                try:
                    length = self.headers.get('Content-Length', '')
                    require(length.isdigit() and 0 < int(length) <= 65536, 'MODEL_BODY_LIMIT')
                    require(self.headers.get('Transfer-Encoding') is None, 'MODEL_TRANSFER_ENCODING')
                    body = self.rfile.read(int(length))
                    require(len(body) == int(length), 'MODEL_BODY_INCOMPLETE')
                    request = device_rules.parse_json(body)
                    valid = (isinstance(request, dict) and count == 1 and self.path == '/v1/chat/completions'
                             and self.headers.get('Authorization') == 'Bearer synthetic-upgrade-key'
                             and request.get('model') == 'synthetic-upgrade-model' and request.get('stream', False) is False)
                except (OSError, ValueError):
                    pass
                if not valid:
                    with owner.lock: owner.errors = min(1000, owner.errors + 1)
                response = ({'id': 'upgrade-seed', 'object': 'chat.completion', 'choices': [{'index': 0,
                             'message': {'role': 'assistant', 'tool_calls': [{'id': 'upgrade-launch-1', 'type': 'function',
                             'function': {'name': 'launch_app', 'arguments': '{"packageName":"dev.agentos.fixture"}'}}]},
                             'finish_reason': 'tool_calls'}]} if valid else {'error': {'message': 'synthetic request rejected'}})
                payload = json.dumps(response).encode()
                self.send_response(200 if valid else 503); self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload))); self.end_headers()
                try: self.wfile.write(payload)
                except OSError: pass

            def do_GET(self):
                with owner.lock: owner.errors = min(1000, owner.errors + 1)
                self.send_error(405)

        self.server = http.server.HTTPServer(('127.0.0.1', 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True, name='synthetic-upgrade-model')

    def __enter__(self):
        self.thread.start(); return self

    def snapshot(self):
        with self.lock: return self.requests, self.errors

    def __exit__(self, *unused):
        if self.thread.is_alive(): self.server.shutdown()
        self.server.server_close()
        if self.thread.ident is not None: self.thread.join(timeout=2)
        require(not self.thread.is_alive(), 'MODEL_SERVER_NOT_STOPPED')


def helper_identity(path, package, pin, tools, instrumentation=False):
    preflight.signature_metadata(preflight.run_tool([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', '-Werr', str(path)]), pin)
    dump = preflight.run_tool([str(tools / 'aapt2'), 'dump', 'xmltree', str(path), '--file', 'AndroidManifest.xml'])
    roots = preflight.xml_elements(dump)
    require(len(roots) == 1 and roots[0]['tag'] == 'manifest', 'HELPER_MANIFEST_INVALID')
    value = preflight.attribute(roots[0], 'package')
    require(value in (f'"{package}"', f'"{package}" (Raw: "{package}")'), 'HELPER_PACKAGE_INVALID')
    if instrumentation:
        items = [child for child in roots[0]['children'] if child['tag'] == 'instrumentation']
        require(len(items) == 1, 'HELPER_INSTRUMENTATION_INVALID')
        target = preflight.attribute(items[0], 'targetPackage', android=True)
        runner = preflight.attribute(items[0], 'name', android=True)
        require(target in (f'"{PACKAGE}"', f'"{PACKAGE}" (Raw: "{PACKAGE}")')
                and runner in ('"androidx.test.runner.AndroidJUnitRunner"',
                               '"androidx.test.runner.AndroidJUnitRunner" (Raw: "androidx.test.runner.AndroidJUnitRunner")'), 'HELPER_INSTRUMENTATION_INVALID')


def installed_apk_path(output):
    output = output.strip()
    require(re.fullmatch(r'package:(/data/app/[A-Za-z0-9_+=.~/-]+/base\.apk)', output) is not None, 'INSTALLED_SPLIT_OR_PATH_INVALID')
    remote = output.removeprefix('package:')
    require('/../' not in remote and '/./' not in remote, 'INSTALLED_PATH_INVALID')
    return remote


def installed_identity(runner, adb, serial, version, expected, work):
    role = 'baseline' if version == 1 else 'candidate'
    metadata = parse_package_dump(runner.run(role + '-package', [adb, '-s', serial, 'shell', 'dumpsys', 'package', PACKAGE]), version)
    remote = installed_apk_path(runner.run(role + '-path', [adb, '-s', serial, 'shell', 'pm', 'path', PACKAGE]))
    pulled = Path(work) / (role + '-installed.apk')
    runner.run(role + '-pull', [adb, '-s', serial, 'pull', remote, str(pulled)], timeout=60)
    require(file_identity(pulled) == {key: expected[key] for key in ('bytes', 'sha256')}, 'INSTALLED_APK_BYTES_MISMATCH')
    return {**metadata, 'apkSha256': expected['sha256'], 'bytes': expected['bytes'],
            'certificateSha256': expected['currentSignerSha256']}


def instrument(runner, adb, serial, method, source, label, nonce, port, seed=None):
    args = dict(upgradeMarker=MARKER, runId=str(source['runId']), runAttempt=str(source['attempt']),
                baselineSource=BASELINE_SOURCE, candidateSource=source['commit'], expectedApi=str(APIS[label]), nonce=nonce,
                modelPort=str(port), **{'class': TEST_CLASS + '#' + method})
    if seed:
        for key in ('Envelope', 'Revision', 'Grant', 'Profile'):
            args['expected' + key + 'Sha256'] = seed[key.lower() + 'Sha256']
    command = [adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r']
    for key, value in args.items(): command.extend(['-e', key, value])
    command.append(COMPONENT)
    output = runner.run('seed-instrumentation' if seed is None else 'verify-instrumentation', command, timeout=180)
    receipt = parse_instrumentation(output, method)
    return validate_receipt(receipt, method, source, label, hashlib.sha256(nonce.encode()).hexdigest(), seed)


def run_probe(args, source, runner_factory=CommandRunner, server_factory=ModelServer):
    directory = device_rules.ensure_directory(args.output)
    path = directory / 'report.json'
    report = read_json(path) if path.exists() else empty_report(source, args.api)
    require(report.get('source') == source and report.get('matrixLabel') == args.api and report.get('caseId') == CASE_ID, 'REPORT_SOURCE_MISMATCH')
    require(report.get('executionAttempted') is False, 'RUN_ALREADY_ATTEMPTED')
    require(report == empty_report(source, args.api), 'PREPARED_REPORT_CHANGED')
    report['executionAttempted'] = True
    write_json(path, report)
    runner = runner_factory(directory, report)
    stage, serial, adb, model, reverse = 'bundle_preflight', None, None, None, False
    service_touched = False

    def begin(name):
        nonlocal stage
        stage = name
        next(item for item in report['phases'] if item['name'] == name)['status'] = 'running'
        write_json(path, report)

    def passed():
        next(item for item in report['phases'] if item['name'] == stage)['status'] = 'passed'
        write_json(path, report)

    def shell(label, *words, timeout=30):
        return runner.run(label, [adb, '-s', serial, 'shell', *words], timeout=timeout).strip()

    def install(label, file, *, replace=False, test=False):
        if label == 'install-baseline': report['baselineInstallAttempts'] += 1
        if replace: report['candidateInstallAttempts'] += 1
        write_json(path, report)  # Consume the one attempt before invoking adb.
        output = runner.run(label, [adb, '-s', serial, 'install', *(['-r'] if replace else []),
                                   *(['-t'] if test else []), str(file)], timeout=120)
        require(re.findall(r'^(Success)\s*$', output, re.M) == ['Success'], 'INSTALL_NOT_SUCCESSFUL')

    try:
        with tempfile.TemporaryDirectory(prefix='agentos-upgrade-inputs-') as temporary:
            work = Path(temporary); os.chmod(work, 0o700)
            begin('bundle_preflight')
            bundle = Path(args.bundle)
            require(bundle.is_dir() and not bundle.is_symlink() and {p.name for p in bundle.iterdir()} == set(BUNDLE_NAMES), 'BUNDLE_FILESET_INVALID')
            identities = {}
            for name in BUNDLE_NAMES:
                identities[name] = preflight.snapshot(bundle / name, work / name)
            manifest = read_json(work / 'build-manifest.json')
            validate_build_manifest(manifest, source, identities)
            checked = read_json(work / 'preflight.json'); validate_preflight(checked, manifest)
            tools = Path(args.sdk_root) / 'build-tools/35.0.0'
            actual = preflight.preflight(work / 'baseline.apk', work / 'candidate.apk', manifest['certificateSha256'],
                                        tools / 'apksigner', tools / 'aapt2')
            require(actual == checked, 'PREFLIGHT_REVERIFICATION_MISMATCH')
            helper_identity(work / 'fixture.apk', FIXTURE, manifest['certificateSha256'], tools)
            helper_identity(work / 'probe.apk', PROBE, manifest['certificateSha256'], tools, instrumentation=True)
            for name in ('build-manifest.json', 'preflight.json', 'signing-certificate.der'):
                write_payload(directory / name, (work / name).read_bytes()); register_file(directory, report, name)
            passed()

            begin('device_preflight')
            sdk = Path(args.sdk_root); adb = str(sdk / 'platform-tools/adb')
            revision = device_rules.validate_emulator_version((sdk / 'emulator/source.properties').read_text(), APIS[args.api])
            serial = device_rules.select_emulator(runner.run('devices', [adb, 'devices']))
            properties = {key: shell('property-' + str(index), 'getprop', key) for index, key in enumerate(
                ('ro.kernel.qemu', 'ro.boot.qemu', 'ro.build.version.sdk', 'ro.product.cpu.abi', 'sys.boot_completed', 'ro.build.fingerprint'))}
            device_rules.validate_device(serial, properties, APIS[args.api])
            require(shell('current-user', 'am', 'get-current-user') == '0', 'OWNER_USER_REQUIRED')
            require(re.findall(r'UserInfo\{([0-9]+):', shell('users', 'pm', 'list', 'users')) == ['0'], 'SINGLE_SYNTHETIC_USER_REQUIRED')
            report['device'] = dict(serial=serial, apiLevel=APIS[args.api], abi=properties['ro.product.cpu.abi'],
                                    fingerprint=properties['ro.build.fingerprint'], kernelQemu=properties['ro.kernel.qemu'],
                                    bootQemu=properties['ro.boot.qemu'], emulatorVersion=revision, ownerUser=0)
            passed()
            begin('fresh_install_precondition')
            installed = shell('fresh-packages', 'pm', 'list', 'packages').splitlines()
            require(all('package:' + name not in installed for name in (PACKAGE, FIXTURE, PROBE)), 'EXISTING_PACKAGE_REFUSED')
            passed()
            for phase, name in (('install_baseline', 'baseline.apk'), ('install_fixture', 'fixture.apk'), ('install_probe', 'probe.apk')):
                begin(phase)
                require(file_identity(work / name) == identities[name], 'INSTALL_INPUT_CHANGED')
                install(phase.replace('_', '-'), work / name, test=name == 'probe.apk')
                require(file_identity(work / name) == identities[name], 'INSTALL_INPUT_CHANGED')
                passed()
            begin('baseline_identity')
            report['installedBaseline'] = installed_identity(runner, adb, serial, 1, checked['baseline'], work)
            passed()
            begin('service_setup')
            original_services = shell('accessibility-list-before', 'settings', 'get', 'secure', 'enabled_accessibility_services')
            original_flag = shell('accessibility-flag-before', 'settings', 'get', 'secure', 'accessibility_enabled')
            require(original_services in ('', 'null') and original_flag in ('0', 'null'), 'EXISTING_ACCESSIBILITY_STATE_REFUSED')
            report['serviceSettingsBefore'] = dict(enabledServices=original_services, accessibilityEnabled=original_flag)
            shell('wake-screen', 'input', 'keyevent', 'KEYCODE_WAKEUP')
            shell('dismiss-keyguard', 'wm', 'dismiss-keyguard')
            service_touched = True  # A failed command may still have changed the device.
            shell('accessibility-list-enable', 'settings', 'put', 'secure', 'enabled_accessibility_services', SERVICE)
            shell('accessibility-flag-enable', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
            require(shell('accessibility-list-after', 'settings', 'get', 'secure', 'enabled_accessibility_services') == SERVICE
                    and shell('accessibility-flag-after', 'settings', 'get', 'secure', 'accessibility_enabled') == '1', 'SERVICE_SETTINGS_NOT_APPLIED')
            passed()
            begin('model_setup')
            model = server_factory(); model.__enter__()
            reverse = True  # A failed reply does not prove the mapping was not installed.
            runner.run('reverse-add', [adb, '-s', serial, 'reverse', 'tcp:' + str(model.port), 'tcp:' + str(model.port)])
            nonce = secrets.token_hex(16); report['nonceSha256'] = hashlib.sha256(nonce.encode()).hexdigest()
            passed()
            begin('seed_baseline')
            seed = instrument(runner, adb, serial, 'seedBaseline', source, args.api, nonce, model.port)
            require(seed['targetUid'] == report['installedBaseline']['uid'], 'SEED_UID_MISMATCH')
            report['requestsAfterSeed'], errors = model.snapshot()
            require(report['requestsAfterSeed'] == 1 and errors == 0, 'SEED_MODEL_REQUESTS_INVALID')
            write_json(directory / 'seed-receipt.json', seed); register_file(directory, report, 'seed-receipt.json')
            passed()
            begin('baseline_main_process')
            launched = shell('baseline-launch', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity', timeout=60)
            require(re.findall(r'^Status: ([A-Za-z]+)\s*$', launched, re.M) == ['ok'], 'BASELINE_LAUNCH_FAILED')
            pid = shell('baseline-pid', 'pidof', PACKAGE)
            require(re.fullmatch('[1-9][0-9]{0,8}', pid) is not None, 'BASELINE_PID_INVALID')
            report['baselinePidBeforeReplacement'] = int(pid); passed()
            begin('replace_candidate')
            require(file_identity(work / 'candidate.apk') == identities['candidate.apk'], 'INSTALL_INPUT_CHANGED')
            install('replace-candidate', work / 'candidate.apk', replace=True)
            require(file_identity(work / 'candidate.apk') == identities['candidate.apk'], 'INSTALL_INPUT_CHANGED')
            passed()
            begin('candidate_identity')
            report['installedCandidate'] = installed_identity(runner, adb, serial, 2, checked['candidate'], work)
            require(report['installedCandidate']['uid'] == report['installedBaseline']['uid']
                    and report['installedCandidate']['firstInstallTime'] == report['installedBaseline']['firstInstallTime'], 'INSTALLATION_NOT_CONTINUOUS')
            passed()
            begin('verify_candidate')
            verified = instrument(runner, adb, serial, 'verifyCandidate', source, args.api, nonce, model.port, seed)
            require(verified['targetUid'] == report['installedCandidate']['uid'], 'CANDIDATE_UID_MISMATCH')
            report['candidatePid'] = verified['pid']
            validate_installed_transition(report['installedBaseline'], report['installedCandidate'], report['baselinePidBeforeReplacement'], report['candidatePid'])
            report['requestsAfterVerify'], errors = model.snapshot()
            require(report['requestsAfterVerify'] == 1 and errors == 0, 'CANDIDATE_MODEL_REQUEST_SEEN')
            write_json(directory / 'verify-receipt.json', verified); register_file(directory, report, 'verify-receipt.json')
            passed(); report['status'] = 'passed'
    except Exception as failure:
        report['status'] = 'failed'
        item = next(item for item in report['phases'] if item['name'] == stage)
        item['status'] = 'failed'
        item['reason'] = (str(failure) if isinstance(failure, (Rejected, preflight.Rejected)) else
                          'ENVIRONMENT_OR_IO_REJECTED' if isinstance(failure, (device_rules.ProbeError, OSError, UnicodeError))
                          else 'UNEXPECTED_HOST_ERROR')
    finally:
        cleanup = next(item for item in report['phases'] if item['name'] == 'cleanup')
        cleanup_failed = False
        if model is not None:
            try:
                model.__exit__(None, None, None)
            except (Rejected, OSError):
                cleanup_failed = True
        if reverse:
            try: runner.run('reverse-remove', [adb, '-s', serial, 'reverse', '--remove', 'tcp:' + str(model.port)])
            except (Rejected, OSError): cleanup_failed = True
        if service_touched:
            for key, previous, label in (('enabled_accessibility_services', original_services, 'accessibility-list-restore'),
                                          ('accessibility_enabled', original_flag, 'accessibility-flag-restore')):
                try:
                    if previous == 'null': shell(label, 'settings', 'delete', 'secure', key)
                    else: shell(label, 'settings', 'put', 'secure', key, previous if previous else '""')
                except (Rejected, OSError): cleanup_failed = True
            try:
                restored_services = shell('accessibility-list-restored', 'settings', 'get', 'secure', 'enabled_accessibility_services')
                restored_flag = shell('accessibility-flag-restored', 'settings', 'get', 'secure', 'accessibility_enabled')
                report['serviceSettingsRestored'] = restored_services == original_services and restored_flag == original_flag
                cleanup_failed |= not report['serviceSettingsRestored']
            except (Rejected, OSError): cleanup_failed = True
        if cleanup_failed:
            cleanup.update(status='failed', reason='CLEANUP_FAILED'); report['status'] = 'failed'
        elif service_touched or model is not None:
            cleanup['status'] = 'passed'
        elif report['status'] == 'passed':
            report['status'] = 'failed'; cleanup.update(status='failed', reason='CLEANUP_NOT_OBSERVED')
        write_json(path, report)
    return report['status'] == 'passed'


COMMAND_LABELS = ('devices', *(f'property-{n}' for n in range(6)), 'current-user', 'users', 'fresh-packages',
                  'install-baseline', 'install-fixture', 'install-probe', 'baseline-package', 'baseline-path',
                  'baseline-pull', 'accessibility-list-before', 'accessibility-flag-before', 'wake-screen', 'dismiss-keyguard',
                  'accessibility-list-enable', 'accessibility-flag-enable', 'accessibility-list-after', 'accessibility-flag-after',
                  'reverse-add', 'seed-instrumentation', 'baseline-launch', 'baseline-pid',
                  'replace-candidate', 'candidate-package', 'candidate-path', 'candidate-pull',
                  'verify-instrumentation', 'reverse-remove', 'accessibility-list-restore', 'accessibility-flag-restore',
                  'accessibility-list-restored', 'accessibility-flag-restored')


def payload_json(data):
    require(isinstance(data, bytes) and len(data) <= 3 * MAX_OUTPUT, 'PAYLOAD_SIZE_INVALID')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'PAYLOAD_DUPLICATE_KEY'); result[key] = value
        return result
    try:
        return json.loads(data, object_pairs_hook=unique,
                          parse_constant=lambda unused: (_ for _ in ()).throw(Rejected('PAYLOAD_NONFINITE')))
    except (UnicodeError, RecursionError, json.JSONDecodeError):
        raise Rejected('PAYLOAD_JSON_INVALID') from None


def validate_report(report, source, label, files):
    """Archive-facing validation of the original payloads, not merely a host success flag."""
    expected = empty_report(source, label)
    require(isinstance(report, dict) and set(expected) <= set(report)
            and set(report) <= set(expected) | {'workflowOutcome'}, 'REPORT_FIELDS_INVALID')
    for key in ('schemaVersion', 'kind', 'source', 'baselineSource', 'matrixLabel', 'apis', 'caseId', 'captures'):
        require(report[key] == expected[key], 'REPORT_IDENTITY_MISMATCH')
    require(type(report['schemaVersion']) is int and type(report['executionAttempted']) is bool, 'REPORT_TYPES_INVALID')
    require(report['status'] in ('passed', 'failed', 'unverified'), 'REPORT_STATUS_INVALID')
    require('workflowOutcome' not in report or report['workflowOutcome'] in ('success', 'failure', 'cancelled', 'skipped', 'unknown'), 'WORKFLOW_OUTCOME_INVALID')
    phases = report['phases']
    require(isinstance(phases, list) and len(phases) == len(PHASES) and all(isinstance(p, dict) for p in phases)
            and [p.get('name') for p in phases] == list(PHASES), 'PHASE_SET_INVALID')
    for phase in phases:
        require(set(phase) <= {'name', 'status', 'reason'} and phase.get('status') in ('passed', 'failed', 'unverified', 'running'), 'PHASE_INVALID')
        require('reason' not in phase or isinstance(phase['reason'], str) and re.fullmatch('[A-Z][A-Z0-9_]{0,95}', phase['reason']), 'PHASE_REASON_INVALID')
    states = {p['name']: p['status'] for p in phases}
    stopped = False
    for phase in phases[:-1]:
        require(not stopped or phase['status'] == 'unverified', 'PHASE_ORDER_INVALID')
        stopped |= phase['status'] != 'passed'
    require(not any(p['status'] == 'failed' for p in phases) or report['status'] == 'failed', 'FAILED_PHASE_HIDDEN')
    if report['executionAttempted'] is False:
        require({key: report[key] for key in expected} == expected and not (set(files) - {'report.json'}), 'UNEXECUTED_REPORT_CHANGED')
        return 'unverified'
    for key in ('baselineInstallAttempts', 'candidateInstallAttempts'):
        require(type(report[key]) is int and 0 <= report[key] <= 1, 'INSTALL_ATTEMPT_INVALID')
    listed = report['artifactFiles']
    require(isinstance(listed, list) and len(listed) <= MAX_COMMANDS + 5, 'ARTIFACT_COUNT_INVALID')
    names, decoded, commands, command_order, raw_total = set(), {}, {}, [], 0
    for item in listed:
        require(isinstance(item, dict) and set(item) == {'path', 'bytes', 'sha256'}, 'ARTIFACT_RECORD_INVALID')
        name = item['path']
        require(isinstance(name, str) and name not in names and name in files, 'ARTIFACT_MISSING_OR_DUPLICATE')
        require(name in ('build-manifest.json', 'preflight.json', 'signing-certificate.der', 'seed-receipt.json', 'verify-receipt.json')
                or re.fullmatch(r'commands/[0-9]{2}-[a-z0-9-]{1,48}\.json', name), 'ARTIFACT_NAME_INVALID')
        data = files[name]['data']
        require(type(item['bytes']) is int and item['bytes'] == len(data)
                and item['sha256'] == hashlib.sha256(data).hexdigest(), 'ARTIFACT_HASH_MISMATCH')
        names.add(name)
        if name == 'signing-certificate.der':
            require(0 < len(data) <= 16384, 'CERTIFICATE_SIZE_INVALID'); continue
        value = payload_json(data); decoded[name] = value
        if name.startswith('commands/'):
            require(isinstance(value, dict) and set(value) == {'schemaVersion', 'label', 'exitCode', 'timeoutSeconds',
                    'elapsedMillis', 'reason', 'stdoutBase64', 'stderrBase64'}, 'COMMAND_RECORD_INVALID')
            require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1 and value['label'] in COMMAND_LABELS
                    and value['label'] not in commands and name.endswith('-' + value['label'] + '.json'), 'COMMAND_IDENTITY_INVALID')
            require(value['exitCode'] is None or type(value['exitCode']) is int, 'COMMAND_EXIT_INVALID')
            require(type(value['timeoutSeconds']) in (int, float) and 0 < value['timeoutSeconds'] <= 180
                    and type(value['elapsedMillis']) is int and value['elapsedMillis'] >= 0, 'COMMAND_TIME_INVALID')
            require(value['reason'] is None or value['reason'] in ('COMMAND_TIMEOUT', 'COMMAND_OUTPUT_LIMIT', 'COMMAND_FAILED',
                    'COMMAND_UNAVAILABLE', 'COMMAND_CLEANUP_FAILED'), 'COMMAND_REASON_INVALID')
            try:
                stdout = base64.b64decode(value['stdoutBase64'], validate=True)
                stderr = base64.b64decode(value['stderrBase64'], validate=True)
            except (ValueError, TypeError):
                raise Rejected('COMMAND_OUTPUT_INVALID') from None
            require(len(stdout) + len(stderr) <= MAX_OUTPUT, 'COMMAND_OUTPUT_LIMIT')
            raw_total += len(stdout) + len(stderr)
            command_order.append(value['label'])
            require(name == f"commands/{len(command_order):02d}-{value['label']}.json", 'COMMAND_SEQUENCE_INVALID')
            commands[value['label']] = {**value, 'stdout': stdout, 'stderr': stderr}
    require(set(files) - {'report.json'} == names and raw_total <= MAX_TOTAL_OUTPUT, 'ARTIFACT_FILESET_INVALID')
    manifest = decoded.get('build-manifest.json')
    if manifest is not None:
        validate_build_manifest(manifest, source)
        for name in ('preflight.json', 'signing-certificate.der'):
            if name in files:
                require(manifest['files'][name] == {'bytes': len(files[name]['data']),
                        'sha256': hashlib.sha256(files[name]['data']).hexdigest()}, 'BUNDLE_EVIDENCE_MISMATCH')
        if 'preflight.json' in decoded:
            validate_preflight(decoded['preflight.json'], manifest)
    else:
        require(not names.intersection({'preflight.json', 'signing-certificate.der'}), 'BUNDLE_MANIFEST_MISSING')
    seed = decoded.get('seed-receipt.json'); verified = decoded.get('verify-receipt.json')
    for method, value, name in (('seedBaseline', seed, 'seed-instrumentation'), ('verifyCandidate', verified, 'verify-instrumentation')):
        if value is not None:
            require(method != 'verifyCandidate' or seed is not None, 'SEED_RECEIPT_MISSING')
            validate_receipt(value, method, source, label, report['nonceSha256'], seed if method == 'verifyCandidate' else None)
            require(name in commands and commands[name]['reason'] is None and commands[name]['exitCode'] == 0, 'RECEIPT_COMMAND_MISSING')
            try: original = commands[name]['stdout'].decode('utf-8', 'strict')
            except UnicodeError: raise Rejected('INSTRUMENTATION_ENCODING_INVALID') from None
            require(parse_instrumentation(original, method) == value, 'RECEIPT_COMMAND_MISMATCH')

    # A failed later stage must not turn an unsupported earlier claim into evidence.
    # Cleanup is independent: a partially applied service setup still needs restoration.
    phase_commands = {
        'bundle_preflight': (), 'device_preflight': COMMAND_LABELS[:9],
        'fresh_install_precondition': ('fresh-packages',),
        'install_baseline': ('install-baseline',), 'install_fixture': ('install-fixture',),
        'install_probe': ('install-probe',),
        'baseline_identity': ('baseline-package', 'baseline-path', 'baseline-pull'),
        'service_setup': COMMAND_LABELS[16:24], 'model_setup': ('reverse-add',),
        'seed_baseline': ('seed-instrumentation',),
        'baseline_main_process': ('baseline-launch', 'baseline-pid'),
        'replace_candidate': ('replace-candidate',),
        'candidate_identity': ('candidate-package', 'candidate-path', 'candidate-pull'),
        'verify_candidate': ('verify-instrumentation',),
        'cleanup': (*(('reverse-remove',) if 'reverse-add' in commands else ()), *COMMAND_LABELS[-4:]),
    }
    for phase, labels in phase_commands.items():
        if states[phase] == 'passed':
            require(all(name in commands and commands[name]['reason'] is None and commands[name]['exitCode'] == 0
                        for name in labels), 'PASSED_PHASE_COMMAND_MISSING_OR_FAILED')

    def text(label):
        try: return commands[label]['stdout'].decode('utf-8', 'strict').strip()
        except UnicodeError: raise Rejected('COMMAND_ENCODING_INVALID') from None

    if states['bundle_preflight'] == 'passed':
        require(manifest is not None and {'preflight.json', 'signing-certificate.der'} <= names, 'PASSED_BUNDLE_INCOMPLETE')
    for phase, command in (('install_baseline', 'install-baseline'), ('install_fixture', 'install-fixture'),
                           ('install_probe', 'install-probe'), ('replace_candidate', 'replace-candidate')):
        if states[phase] == 'passed':
            require(re.findall(r'^(Success)\s*$', text(command), re.M) == ['Success'], 'INSTALL_NOT_SUCCESSFUL')
    if states['install_baseline'] == 'passed':
        require(report['baselineInstallAttempts'] == 1, 'PASSED_INSTALL_ATTEMPTS_INVALID')
    if states['replace_candidate'] == 'passed':
        require(report['candidateInstallAttempts'] == 1, 'PASSED_INSTALL_ATTEMPTS_INVALID')
    before, after = report['installedBaseline'], report['installedCandidate']
    for role, version, record, receipt in (('baseline', 1, before, seed), ('candidate', 2, after, verified)):
        if states[role + '_identity'] == 'passed':
            installed_apk_path(text(role + '-path'))
            pm = parse_package_dump(text(role + '-package'), version)
            require(record == {**pm, 'apkSha256': manifest['files'][role + '.apk']['sha256'],
                    'bytes': manifest['files'][role + '.apk']['bytes'], 'certificateSha256': manifest['certificateSha256']}, 'INSTALLED_RECORD_MISMATCH')
            if receipt is not None:
                require(record['uid'] == receipt['targetUid'], 'INSTALLED_RECEIPT_UID_MISMATCH')
    if states['candidate_identity'] == 'passed':
        require(before['uid'] == after['uid'] and before['firstInstallTime'] == after['firstInstallTime'], 'INSTALLATION_NOT_CONTINUOUS')
    if states['model_setup'] == 'passed':
        require(isinstance(report['nonceSha256'], str) and SHA.fullmatch(report['nonceSha256']), 'NONCE_DIGEST_INVALID')
    if states['seed_baseline'] == 'passed':
        require(seed is not None and type(report['requestsAfterSeed']) is int and report['requestsAfterSeed'] == 1, 'PASSED_SEED_INCOMPLETE')
    if states['baseline_main_process'] == 'passed':
        require(re.findall(r'^Status: ([A-Za-z]+)\s*$', text('baseline-launch'), re.M) == ['ok'], 'BASELINE_LAUNCH_FAILED')
        require(type(report['baselinePidBeforeReplacement']) is int and report['baselinePidBeforeReplacement'] > 0
                and text('baseline-pid') == str(report['baselinePidBeforeReplacement']), 'BASELINE_PID_INVALID')
    if states['verify_candidate'] == 'passed':
        require(verified is not None and type(report['requestsAfterVerify']) is int and report['requestsAfterVerify'] == 1, 'PASSED_VERIFY_INCOMPLETE')
        require(verified['pid'] == report['candidatePid'], 'PID_RECEIPT_MISMATCH')
        validate_installed_transition(before, after, report['baselinePidBeforeReplacement'], report['candidatePid'])
    if states['device_preflight'] == 'passed':
        observed_device = report['device']
        require(isinstance(observed_device, dict) and observed_device.get('apiLevel') == APIS[label]
                and observed_device.get('serial') == device_rules.select_emulator(text('devices')), 'DEVICE_RECORD_INVALID')
        properties = {key: text('property-' + str(n)) for n, key in enumerate(('ro.kernel.qemu', 'ro.boot.qemu',
                      'ro.build.version.sdk', 'ro.product.cpu.abi', 'sys.boot_completed', 'ro.build.fingerprint'))}
        device_rules.validate_device(observed_device['serial'], properties, APIS[label])
        require(observed_device['fingerprint'] == properties['ro.build.fingerprint'] and text('current-user') == '0'
                and re.findall(r'UserInfo\{([0-9]+):', text('users')) == ['0'], 'DEVICE_OWNER_MISMATCH')
    if states['fresh_install_precondition'] == 'passed':
        require(all('package:' + name not in text('fresh-packages').splitlines() for name in (PACKAGE, FIXTURE, PROBE)), 'FRESH_INSTALL_NOT_PROVEN')
    if states['service_setup'] == 'passed' or states['cleanup'] == 'passed':
        require(all(name in commands for name in ('accessibility-list-before', 'accessibility-flag-before')), 'SERVICE_BASELINE_MISSING')
        require(report['serviceSettingsBefore'] == {'enabledServices': text('accessibility-list-before'),
                'accessibilityEnabled': text('accessibility-flag-before')} and text('accessibility-list-before') in ('', 'null')
                and text('accessibility-flag-before') in ('0', 'null'), 'SERVICE_BASELINE_INVALID')
    if states['service_setup'] == 'passed':
        require(text('accessibility-list-after') == SERVICE and text('accessibility-flag-after') == '1', 'SERVICE_TRANSITION_INVALID')
    if states['cleanup'] == 'passed':
        require(report['serviceSettingsRestored'] is True and text('accessibility-list-restored') == text('accessibility-list-before')
                and text('accessibility-flag-restored') == text('accessibility-flag-before'), 'SERVICE_SETTINGS_NOT_RESTORED')
    if report['status'] == 'passed':
        require(all(p['status'] == 'passed' for p in phases), 'PASSED_PHASES_INCOMPLETE')
        require(command_order == list(COMMAND_LABELS), 'PASSED_COMMAND_ORDER_INVALID')
    else:
        require(not all(p['status'] == 'passed' for p in phases), 'FAILED_REPORT_WITH_ALL_PHASES_PASSED')
    return report['status']


def recover_published_payloads(directory, report):
    """Recover only atomic, complete payload publications; never promote a phase."""
    known = {item['path'] for item in report['artifactFiles']}
    paths = [directory / name for name in ('build-manifest.json', 'preflight.json', 'signing-certificate.der',
                                          'seed-receipt.json', 'verify-receipt.json')]
    commands = directory / 'commands'
    require(not commands.is_symlink(), 'OUTPUT_LINK_REJECTED')
    if commands.is_dir():
        paths.extend(sorted(commands.glob('[0-9][0-9]-*.json')))
    require(len(paths) <= MAX_COMMANDS + 5, 'ARTIFACT_COUNT_INVALID')
    recovered = False
    for path in paths:
        name = path.relative_to(directory).as_posix()
        if name in known or not path.exists():
            continue
        require(report['executionAttempted'] is True and path.is_file() and not path.is_symlink()
                and 0 < path.stat().st_size <= 3 * MAX_OUTPUT, 'UNPUBLISHED_PAYLOAD_INVALID')
        if name != 'signing-certificate.der':
            payload_json(path.read_bytes())
        else:
            require(path.stat().st_size <= 16384, 'CERTIFICATE_SIZE_INVALID')
        register_file(directory, report, name)
        recovered = True
    if recovered:
        report['status'] = 'failed'
        if all(item['status'] == 'passed' for item in report['phases']):
            report['phases'][-1].update(status='failed', reason='UNREGISTERED_PAYLOAD_RECOVERED')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    build = sub.add_parser('describe-build'); build.add_argument('--bundle', type=Path, required=True); build.add_argument('--sdk-root', required=True)
    for name in ('prepare', 'run', 'finalize'):
        command = sub.add_parser(name); command.add_argument('--api', choices=APIS, required=True); command.add_argument('--output', type=Path, required=True)
        if name == 'run': command.add_argument('--bundle', type=Path, required=True); command.add_argument('--sdk-root', required=True)
        if name == 'finalize': command.add_argument('--outcome', choices=('success', 'failure', 'cancelled', 'skipped', 'unknown'), required=True)
    args = parser.parse_args(argv)
    try:
        source = source_from_environment()
        if args.command == 'describe-build': describe_build(args.bundle, args.sdk_root, source); return 0
        directory = device_rules.ensure_directory(args.output); path = directory / 'report.json'
        if args.command == 'run': return 0 if run_probe(args, source) else 1
        report = read_json(path) if path.exists() else empty_report(source, args.api)
        require(report.get('source') == source and report.get('matrixLabel') == args.api and report.get('caseId') == CASE_ID, 'REPORT_SOURCE_MISMATCH')
        if args.command == 'prepare': require(report.get('executionAttempted') is False, 'RUN_ALREADY_ATTEMPTED')
        else:
            report['workflowOutcome'] = args.outcome
            recover_published_payloads(directory, report)
            for item in report['phases']:
                if item['status'] == 'running': item.update(status='failed', reason='WORKFLOW_INTERRUPTED'); report['status'] = 'failed'
            if report['status'] == 'passed' and not all(item['status'] == 'passed' for item in report['phases']):
                report['status'] = 'failed'
        write_json(path, report); return 0
    except (Rejected, preflight.Rejected, device_rules.ProbeError, OSError) as failure:
        reason = str(failure) if isinstance(failure, (Rejected, preflight.Rejected)) else 'ENVIRONMENT_OR_IO_REJECTED'
        print(json.dumps({'status': 'rejected', 'reason': reason})); return 1


if __name__ == '__main__':
    raise SystemExit(main())
