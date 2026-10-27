#!/usr/bin/env python3
"""Fixed internal variant: actual production-service window review and HTTP disclosure."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys

import pinned_calculator_page as shared
import third_party_smoke as base
import verify_visual_acceptance_apks as apk_proof

APIS = shared.APIS
WORKFLOW = '.github/workflows/pinned-window-disclosure.yml'
KIND = 'pinned-window-disclosure'
TEST_CLASS = 'dev.agentos.app.PinnedWindowDisclosureInstrumentedTest'
CASES = ('approvedActualWindowIsReviewedAndSentOnce', 'declinedActualWindowNeverReachesModel')
EVIDENCE_DIR = DEVICE_DIR = 'pinned-window-disclosure'
CASE_FILE_PATTERN = r'[A-Za-z][A-Za-z0-9]*-received\.png|[A-Za-z][A-Za-z0-9]*\.case\.json'
MAX_CASE_FILES = 3
MAX_IMAGE_BYTES = 1024 * 1024
CASE_STATUS_KEY = 'disclosureCaseStatus'
IMAGE_LIST_KEY = 'verifiedReceivedPngs'
JOB_PREFIX = 'window-disclosure'
ARCHIVE_KIND = 'pinned-window-disclosure-evidence'
ARCHIVE_TITLE = 'Pinned Native Window Disclosure Evidence'
BUILD_VARIANT = 'visualAcceptance'
IMAGE_CAPTION = 'Actual approved HTTP PNG payload'
ARCHIVE_SCOPE = ('Internal visualAcceptance variant only: real production service and Controller, fixed Calculator 1.4.0, '
                 'native window capture, actual same-PNG protected review, and local HTTP model-fixture delivery. '
                 'Qualification uses a separate synthetic PNG challenge. Received PNG artifacts are approved HTTP payloads, '
                 'not screenshots of the protected review UI. Normal debug/release capture permission remains closed. '
                 'Not real-model, physical-device, general-application or physical-memory acceptance.')
INSTRUMENTATION_MARKERS = {'nativeDisclosureMarker': 'agent-os-native-disclosure-v1',
                           'freshInstallMarker': 'agent-os-native-disclosure-fresh-v1'}
require = base.require
IMAGE_FIELDS = ('image', 'imageSha256', 'imageBytes')


def source(env=None):
    return shared.source(env, WORKFLOW)


def empty_report(binding, label):
    return {'schemaVersion': 1, 'kind': KIND, 'source': binding, 'matrixLabel': label,
            'buildVariant': 'visualAcceptance', 'status': 'environment_failed', 'expectedTests': len(CASES),
            'scope': ARCHIVE_SCOPE, 'cases': [{'name': name, 'status': 'unverified'} for name in CASES], 'driverSteps': []}



def verify_run_inputs(args, binding, report):
    proof = apk_proof.validate_report(base.read_json(args.apk_verification), binding['commit'])
    require(base.sha256_file(args.agent_apk) == proof['variants']['visualAcceptance']['sha256']
            and base.sha256_file(args.test_apk) == proof['variants']['visualAcceptanceAndroidTest']['sha256'],
            'Installed APKs differ from actual permission/target verification')
    report['apkVerification'] = proof


def validate_report_extra(report, require_actual=False):
    proof = report.get('apkVerification')
    if proof is None:
        require(not require_actual, 'Actual native cases require packaged permission evidence')
        return
    apk_proof.validate_report(proof, report['source']['commit'])
    if require_actual:
        steps = report.get('driverSteps', [])
        for step, variant in [('install_agent', 'visualAcceptance'), ('install_tests', 'visualAcceptanceAndroidTest')]:
            found = [item for item in steps if item.get('name') == step]
            require(len(found) == 1 and found[0].get('status') == 'passed'
                    and found[0].get('sha256') == proof['variants'][variant]['sha256'], 'Actual installed APK differs from permission proof')


def instrument_result(text, exit_code):
    return shared.instrument_result(text, exit_code, len(CASES))


def _hash(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None


def _count(data, key, minimum=0, maximum=32):
    value = data.get(key)
    require(type(value) is int and minimum <= value <= maximum, 'Invalid bounded count: ' + key)
    return value


def validate_case(data, binding, api, serial, expected):
    require(isinstance(data, dict) and data.get('schemaVersion') == 1 and data.get('kind') == KIND, 'Invalid disclosure case format')
    require(data.get('testClass') == TEST_CLASS and data.get('testName') == expected and expected in CASES, 'Foreign disclosure case')
    require(data.get('api') == api and data.get('sourceSha') == binding['commit'] and data.get('runId') == str(binding['runId'])
            and data.get('runAttempt') == str(binding['attempt']) and data.get('deviceSerial') == serial, 'Case belongs to another source/device')
    require(data.get('status') in ('passed', 'failed', 'unverified'), 'Invalid disclosure status')
    # Incomplete/failed actual outcomes are retained rather than promoted by missing assertions.
    if data['status'] == 'passed':
        for flag in ('installedArtifactVerified', 'captureCapabilityPresent', 'gestureCapabilityAbsent',
                     'reviewDisplayed', 'actualReviewRemoved', 'cleanupDrained'):
            require(data.get(flag) is True, 'Missing actual disclosure evidence: ' + flag)
        require(data.get('nativeOrigin') == 'platform_window' and _hash(data.get('reviewedSha256')), 'Missing native reviewed PNG identity')
        require(type(data.get('width')) is int and type(data.get('height')) is int
                and 0 < data['width'] <= 1280 and 0 < data['height'] <= 1280
                and data['width'] * data['height'] <= 1048576, 'Invalid reviewed PNG dimensions')
        require(_count(data, 'syntheticProbeImageCount') == 1, 'Exactly one real qualification image is required')
        _count(data, 'taskHttpRequests', minimum=1)
        if expected == CASES[0]:
            require(data.get('reviewRejected') is False and _count(data, 'taskImageCount') == 1
                    and _count(data, 'afterImageRequestWithoutImageCount', minimum=1) >= 1,
                    'Approved case lacks once-only image and following image-free request')
            require(data.get('receivedSha256') == data['reviewedSha256'] == data.get('imageSha256'), 'The received PNG is not the reviewed PNG')
            require(all(field in data for field in IMAGE_FIELDS), 'Approved received PNG artifact is missing')
        else:
            require(data.get('reviewRejected') is True and _count(data, 'taskImageCount') == 0
                    and _count(data, 'afterImageRequestWithoutImageCount') == 0,
                    'Declined case must explicitly reject and send zero device images')
            require('receivedSha256' not in data and not any(key in data for key in IMAGE_FIELDS), 'A declined image cannot be archived as approved')
    return data


def validate_case_files(record, files):
    image_name = record['testName'] + '-received.png'
    present = [field in record for field in IMAGE_FIELDS]
    if not any(present):
        require(image_name not in files, 'Unreferenced received PNG')
        return record
    require(all(present) and record['testName'] == CASES[0] and record.get('reviewRejected') is False,
            'Only an actually approved received image may be retained')
    require(record.get('nativeOrigin') == 'platform_window' and record.get('reviewDisplayed') is True
            and record.get('actualReviewRemoved') is True and record.get('taskImageCount') == 1,
            'Payload was not received after actual native review/removal')
    require(record.get('image') == image_name and image_name in files, 'Missing or foreign received PNG')
    raw = files[image_name]
    require(24 <= len(raw) <= MAX_IMAGE_BYTES and raw.startswith(b'\x89PNG\r\n\x1a\n') and raw[12:16] == b'IHDR', 'Invalid received PNG')
    require(type(record.get('imageBytes')) is int and record['imageBytes'] == len(raw)
            and hashlib.sha256(raw).hexdigest() == record.get('imageSha256') == record.get('receivedSha256') == record.get('reviewedSha256'),
            'Received/reviewed PNG hash or size differs')
    width, height = struct.unpack('>II', raw[16:24])
    require(type(record.get('width')) is int and type(record.get('height')) is int
            and (width, height) == (record['width'], record['height'])
            and 0 < width <= 1280 and 0 < height <= 1280 and width * height <= 1048576, 'PNG exceeds original image limits')
    return record


def image_paths(record):
    return [record['image']] if 'image' in record else []


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__); commands = parser.add_subparsers(dest='command', required=True)
    for name in ('prepare', 'run', 'finalize'):
        child = commands.add_parser(name); child.add_argument('--api', choices=APIS, required=True)
        child.add_argument('--output', type=Path, required=True)
        if name == 'run':
            child.add_argument('--sdk-root', required=True)
            child.add_argument('--apk-verification', type=Path, required=True)
            for flag in ('agent-apk', 'fixture-apk', 'test-apk', 'calculator-apk'): child.add_argument('--' + flag, type=Path, required=True)
        if name == 'finalize': child.add_argument('--outcome', choices=('success', 'failure', 'cancelled', 'skipped', 'unknown'), required=True)
    args = parser.parse_args(argv); binding = source()
    if args.command == 'run': return 0 if shared.run(args, binding, profile=sys.modules[__name__]) else 1
    report = empty_report(binding, args.api)
    if args.command == 'finalize':
        path = args.output / 'report.json'
        if path.exists(): report = base.read_json(path)
        require(report.get('source') == binding and report.get('matrixLabel') == args.api and report.get('kind') == KIND
                and report.get('buildVariant') == 'visualAcceptance', 'Cannot finalize foreign disclosure evidence')
        report['emulatorStepOutcome'] = args.outcome
        if args.outcome != 'success' and report['status'] == 'passed': report['status'] = 'environment_failed'
    base.write_report(args.output, report)
    path = args.output / 'capture.json'; capture = base.read_json(path) if path.exists() else shared.empty_capture(args.api)
    require(capture.get('apiLevel') == APIS[args.api] and capture.get('matrixLabel') == args.api, 'Foreign standard evidence')
    if args.command == 'finalize': capture['emulatorStepOutcome'] = args.outcome
    shared.write_capture(args.output, capture)
    return 0


if __name__ == '__main__': raise SystemExit(main())
