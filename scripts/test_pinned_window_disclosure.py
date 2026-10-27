"""File/transport-contract fixtures only; no generated image is claimed as Android evidence."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import pinned_calculator_page as shared
import pinned_window_disclosure as native
import publish_test_evidence as publisher
import test_pinned_page_archive as old
import test_pinned_calculator_page as old_driver
import third_party_smoke as base
import verify_visual_acceptance_apks as apk

BINDING = old.BINDING
SERIAL = 'emulator-5554'


def permission_proof():
    variants = {}
    for index, name in enumerate(('debug', 'release', 'visualAcceptance', 'debugAndroidTest', 'visualAcceptanceAndroidTest')):
        value = {'sha256': str(index + 1) * 64, 'bytes': 1234}
        if name.endswith('AndroidTest'):
            value['instrumentation'] = {'runner': 'androidx.test.runner.AndroidJUnitRunner', 'targetPackage': 'dev.agentos.app'}
        else:
            value['resourcePaths'] = {apk.BASE: 'res/Nf.xml' if name == 'release' else apk.BASE}
            value['serviceBinding'] = {'service': apk.SERVICE, 'metadata': 'android.accessibilityservice',
                                       'resource': apk.RESOURCE, 'resourceId': '0x7f060000'}
            value['serviceResources'] = {apk.BASE: {'canTakeScreenshot': False, 'canPerformGestures': False, 'isAccessibilityTool': False}}
            if name == 'visualAcceptance':
                value['serviceResources'][apk.V34] = {'canTakeScreenshot': True, 'canPerformGestures': False, 'isAccessibilityTool': False}
                value['resourcePaths'][apk.V34] = apk.V34
        variants[name] = value
    return {'schemaVersion': 1, 'kind': 'visual-acceptance-apk-permissions', 'sourceSha': BINDING['commit'], 'variants': variants}


def records():
    files = {}; raw = old.png(); sha = hashlib.sha256(raw).hexdigest()
    for index, name in enumerate(native.CASES):
        value = {'schemaVersion': 1, 'kind': native.KIND, 'testClass': native.TEST_CLASS, 'testName': name,
                 'api': 34, 'sourceSha': BINDING['commit'], 'runId': '123', 'runAttempt': '2', 'deviceSerial': SERIAL,
                 'status': 'passed', 'installedArtifactVerified': True, 'captureCapabilityPresent': True,
                 'gestureCapabilityAbsent': True, 'nativeOrigin': 'platform_window', 'syntheticProbeImageCount': 1,
                 'taskHttpRequests': 3 if index == 0 else 1, 'taskImageCount': 1 if index == 0 else 0,
                 'afterImageRequestWithoutImageCount': 1 if index == 0 else 0, 'reviewedSha256': sha, 'width': 64, 'height': 64,
                 'reviewDisplayed': True, 'reviewRejected': index == 1, 'actualReviewRemoved': True, 'cleanupDrained': True}
        if index == 0:
            image = name + '-received.png'; files[image] = raw
            value.update(image=image, imageSha256=sha, receivedSha256=sha, imageBytes=len(raw), width=64, height=64)
        files[name + '.case.json'] = json.dumps(value).encode()
    return files


def source_run():
    value = old.source_run(); value['path'] = native.WORKFLOW; return value


def job():
    value = old.job(); value['name'] = 'window-disclosure (34)'; return value


def write_lane(root):
    folder = root / 'incoming' / old.artifact()['name']; folder.mkdir(parents=True)
    report = native.empty_report(BINDING, '34'); proof = permission_proof()
    report.update(status='passed', apkVerification=proof, device={'serial': SERIAL, 'api': 34, 'properties': {
        'ro.kernel.qemu': '1', 'ro.boot.qemu': '1', 'ro.build.version.sdk': '34', 'ro.product.cpu.abi': 'x86_64', 'sys.boot_completed': '1'}},
        verifiedDownload={'package': base.PACKAGE, 'versionCode': '10', 'versionName': '1.4.0', 'minSdk': 26,
        'targetSdk': 36, 'abis': ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'], 'sizeBytes': base.PINNED_SIZE,
        'sha256': base.PINNED_SHA, 'certificateSha256': base.PINNED_CERT},
        driverSteps=[{'name': 'install_agent', 'status': 'passed', 'sha256': proof['variants']['visualAcceptance']['sha256']},
                     {'name': 'install_tests', 'status': 'passed', 'sha256': proof['variants']['visualAcceptanceAndroidTest']['sha256']}])
    (folder / native.EVIDENCE_DIR).mkdir()
    for name, data in records().items(): (folder / native.EVIDENCE_DIR / name).write_bytes(data)
    standards = []
    for index, name in enumerate(native.CASES):
        report['cases'][index] = {'name': name, 'status': 'passed', 'record': native.EVIDENCE_DIR + '/' + name + '.case.json'}
        standards.append({'api': 34, 'testClass': native.TEST_CLASS, 'testName': name, 'testResult': 'pass',
                          'captures': [{'status': 'blocked', 'captureError': 'protected_review'}]})
    old.dump(folder / 'screenshots/manifest.json', {'schemaVersion': 1, 'api': 34, 'tests': standards})
    log = json.dumps({'runner': 'direct_adb_instrumentation', 'exitCode': 0, 'passed': True, 'stdout': 'OK (2 tests)\n'}).encode()
    (folder / 'instrumentation.json').write_bytes(log)
    report['instrumentation'] = {'selectedClass': native.TEST_CLASS, 'expectedTests': 2, 'exitCode': 0, 'passed': True,
                                 'log': 'instrumentation.json', 'bytes': len(log), 'sha256': hashlib.sha256(log).hexdigest()}
    old.dump(folder / 'report.json', report)
    capture = shared.empty_capture('34'); capture.update(captureStatus='collected', actualSdkInt=34, instrumentationExitCode=0)
    old.dump(folder / 'capture.json', capture)
    return folder


class PinnedWindowDisclosureTests(unittest.TestCase):
    def test_fixed_dispatch_and_actual_two_test_completion(self):
        env = old_driver.environment(); env['GITHUB_WORKFLOW_REF'] = BINDING['repository'] + '/' + native.WORKFLOW + '@refs/heads/master'
        self.assertEqual(BINDING, native.source(env))
        for wrong in (old_driver.environment(), dict(env, GITHUB_EVENT_NAME='push')):
            with self.assertRaises(ValueError): native.source(wrong)
        self.assertTrue(native.instrument_result('Time: 1\nOK (2 tests)\n', 0))
        for text, code in [('OK (5 tests)', 0), ('OK (2 tests)', 1), ('OK (2 tests)\nFAILURES!!!', 0)]:
            self.assertFalse(native.instrument_result(text, code))
        self.assertEqual((123, 2), publisher.verify_source(source_run(), BINDING['repository']))
        wrong = source_run(); wrong['event'] = 'push'
        with self.assertRaises(ValueError): publisher.verify_source(wrong, BINDING['repository'])

    def test_native_reviewed_and_received_identity_cannot_be_replaced_by_synthetic_success(self):
        value = json.loads(records()[native.CASES[0] + '.case.json'])
        native.validate_case(value, BINDING, 34, SERIAL, native.CASES[0])
        for key, changed in [('nativeOrigin', 'synthetic_test'), ('captureCapabilityPresent', False),
                             ('reviewDisplayed', False), ('actualReviewRemoved', False), ('cleanupDrained', False),
                             ('syntheticProbeImageCount', True), ('syntheticProbeImageCount', 2),
                             ('taskImageCount', 2), ('afterImageRequestWithoutImageCount', 0),
                             ('receivedSha256', '0' * 64), ('reviewRejected', True), ('sourceSha', 'b' * 40)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                native.validate_case(dict(value, **{key: changed}), BINDING, 34, SERIAL, native.CASES[0])

    def test_decline_requires_explicit_rejection_zero_screen_images_and_no_retained_payload(self):
        files = records(); name = native.CASES[1]; value = json.loads(files[name + '.case.json'])
        native.validate_case(value, BINDING, 34, SERIAL, name); native.validate_case_files(value, files)
        for key, changed in [('taskImageCount', 1), ('reviewRejected', False), ('receivedSha256', '0' * 64), ('image', name + '-received.png')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                native.validate_case(dict(value, **{key: changed}), BINDING, 34, SERIAL, name)
        files[name + '-received.png'] = old.png()
        with self.assertRaises(ValueError): native.validate_case_files(value, files)

    def test_received_png_bytes_dimensions_hash_and_owner_are_exact(self):
        files = records(); name = native.CASES[0]; value = json.loads(files[name + '.case.json'])
        native.validate_case_files(value, files)
        for key, changed in [('imageBytes', value['imageBytes'] + 1), ('imageSha256', '0' * 64), ('width', 65),
                             ('image', native.CASES[1] + '-received.png'), ('actualReviewRemoved', False)]:
            with self.subTest(key=key), self.assertRaises(ValueError): native.validate_case_files(dict(value, **{key: changed}), files)
        files[value['image']] = b'not-a-PNG'
        with self.assertRaises(ValueError): native.validate_case_files(value, files)

    def test_collection_preserves_raw_files_missing_and_failed_denominator(self):
        files = records(); names = list(native.CASES)
        def command(argv, **kwargs):
            if argv[-2:] == ['ls', 'files/' + native.DEVICE_DIR]: return '\n'.join(files)
            return files[argv[-1].rsplit('/', 1)[-1]]
        with tempfile.TemporaryDirectory() as temp:
            result = shared.collect_cases('adb', SERIAL, Path(temp), BINDING, 34, command=command, profile=native)
            self.assertEqual(['passed', 'passed'], [r['status'] for r in result])
            for name, raw in files.items(): self.assertEqual(raw, (Path(temp) / native.EVIDENCE_DIR / name).read_bytes())
        del files[names[0] + '.case.json']; del files[names[0] + '-received.png']
        files[names[1] + '.case.json'] = json.dumps(dict(json.loads(files[names[1] + '.case.json']), status='failed', failureCode='PLATFORM_FAILURE')).encode()
        with tempfile.TemporaryDirectory() as temp:
            result = shared.collect_cases('adb', SERIAL, Path(temp), BINDING, 34, command=command, profile=native)
        self.assertEqual(['unverified', 'failed'], [r['status'] for r in result])

    def test_archive_keeps_eight_denominator_and_approved_payload_distinct_from_secure_ui(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); lane = write_lane(root)
            result = old.archive(root, run=source_run(), jobs=[job()])
            self.assertEqual(native.ARCHIVE_KIND, result['kind'])
            self.assertEqual(8, result['expectedTests']); self.assertEqual(2, result['expectedTestsPerApi'])
            self.assertEqual({'passed': 2, 'failed': 0, 'unverified': 6}, result['caseCounts'])
            self.assertEqual(1, len(result['apis'][0]['verifiedReceivedPngs']))
            self.assertIn('not screenshots of the protected review UI', result['scope'])
            for item in result['apis'][0]['files']:
                self.assertEqual(item['sha256'], hashlib.sha256((lane / item['path']).read_bytes()).hexdigest())
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); old.write_lane(root); legacy = old.archive(root)
            self.assertEqual(20, legacy['expectedTests']); self.assertEqual(5, legacy['expectedTestsPerApi'])
            self.assertEqual('pinned-calculator-page-predicate-evidence', legacy['kind'])

    def test_apk_permission_or_installed_hash_mismatch_and_missing_standard_do_not_pass(self):
        for field in ('permission', 'installed', 'target', 'variant', 'secure'):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temp:
                lane = write_lane(Path(temp)); report = json.loads((lane / 'report.json').read_text())
                if field == 'permission': report['apkVerification']['variants']['debug']['serviceResources'][apk.BASE]['canTakeScreenshot'] = True
                if field == 'installed': report['driverSteps'][0]['sha256'] = '0' * 64
                if field == 'target': report['apkVerification']['variants']['visualAcceptanceAndroidTest']['instrumentation']['targetPackage'] = 'other.app'
                if field == 'variant': report['buildVariant'] = 'debug'
                if field == 'secure':
                    standard = json.loads((lane / 'screenshots/manifest.json').read_text()); standard['tests'][0]['captures'][0]['secureWindowTemporarilyCleared'] = True
                    old.dump(lane / 'screenshots/manifest.json', standard)
                old.dump(lane / 'report.json', report)
                with self.assertRaises(ValueError): publisher.pinned_page_report(lane, source_run(), '34', native)
        with tempfile.TemporaryDirectory() as temp:
            lane = write_lane(Path(temp)); (lane / 'screenshots/manifest.json').unlink()
            result = publisher.pinned_page_report(lane, source_run(), '34', native)
            self.assertEqual('unverified', result['status'])
            self.assertEqual(['unverified', 'unverified'], [v['status'] for v in result['cases']])

    def test_invalid_log_or_missing_file_cannot_be_replaced_by_passed_case_flags(self):
        for failure in ('log', 'image'):
            with tempfile.TemporaryDirectory() as temp:
                lane = write_lane(Path(temp))
                if failure == 'log': (lane / 'instrumentation.json').write_text('{}')
                else: (lane / native.EVIDENCE_DIR / (native.CASES[0] + '-received.png')).unlink()
                with self.assertRaises(ValueError): publisher.pinned_page_report(lane, source_run(), '34', native)

    def test_actual_xml_boolean_and_instrumentation_checks_fail_closed(self):
        xml = '\n'.join(['E: accessibility-service (line=1)',
                          '  A: android:isAccessibilityTool(0x1)=(type 0x12)0x0',
                          '  A: android:canRetrieveWindowContent(0x2)=(type 0x12)0xffffffff'])
        self.assertFalse(apk.service_xml(xml, False)['canTakeScreenshot'])
        enabled = xml + '\n  A: android:canTakeScreenshot(0x3)=(type 0x12)0xffffffff'
        self.assertTrue(apk.service_xml(enabled, True)['canTakeScreenshot'])
        for bad in (enabled, xml + '\n  A: android:canPerformGestures(0x4)=(type 0x12)0xffffffff',
                    xml.replace('0x0', '0xffffffff'), xml.replace('(type 0x12)0x0', '"false"')):
            with self.assertRaises(ValueError): apk.service_xml(bad, False)
        manifest = 'E: manifest\n  E: instrumentation\n    A: android:name(0x1)="androidx.test.runner.AndroidJUnitRunner"\n    A: android:targetPackage(0x2)="dev.agentos.app"\n'
        self.assertEqual('dev.agentos.app', apk.instrumentation(manifest)['targetPackage'])
        for bad in (manifest.replace('dev.agentos.app', 'other.app'), manifest + manifest):
            with self.assertRaises(ValueError): apk.instrumentation(bad)

    def test_optimized_release_resource_is_resolved_by_named_resource_and_manifest_binding(self):
        resources = ('Package name=dev.agentos.app id=7f\n'
                     '  type xml id=06 entryCount=2\n'
                     '    resource 0x7f060000 xml/agent_accessibility_service\n'
                     '      () (file) res/Nf.xml type=XML\n'
                     '    resource 0x7f060001 xml/other\n'
                     '      () (file) res/da.xml type=XML\n')
        identity, paths = apk.resource_entries(resources, 'release')
        self.assertEqual('0x7f060000', identity); self.assertEqual({apk.BASE: 'res/Nf.xml'}, paths)
        manifest = ('E: manifest\n  E: application\n    E: service\n'
                    '      A: http://schemas.android.com/apk/res/android:name(0x1)="dev.agentos.app.AgentAccessibilityService" (Raw: "dev.agentos.app.AgentAccessibilityService")\n'
                    '      A: http://schemas.android.com/apk/res/android:permission(0x2)="android.permission.BIND_ACCESSIBILITY_SERVICE"\n'
                    '      E: meta-data\n'
                    '        A: http://schemas.android.com/apk/res/android:name(0x1)="android.accessibilityservice"\n'
                    '        A: http://schemas.android.com/apk/res/android:resource(0x3)=@0x7f060000\n')
        self.assertEqual(identity, apk.service_binding(manifest, identity)['resourceId'])
        for bad in (manifest.replace('@0x7f060000', '@0x7f060001'), manifest.replace('AgentAccessibilityService', 'OtherService'),
                    manifest.replace('BIND_ACCESSIBILITY_SERVICE', 'OTHER_PERMISSION')):
            with self.assertRaises(ValueError): apk.service_binding(bad, identity)
        native_xml = ('E: accessibility-service\n'
                      '  A: http://schemas.android.com/apk/res/android:isAccessibilityTool(0x1)=false\n'
                      '  A: http://schemas.android.com/apk/res/android:canRetrieveWindowContent(0x2)=true\n')
        self.assertFalse(apk.service_xml(native_xml, False)['canTakeScreenshot'])
        with self.assertRaises(ValueError): apk.service_xml(native_xml.replace('=false', '=true'), False)

    def test_resource_configurations_and_actual_file_paths_cannot_add_a_hidden_capability_variant(self):
        resources = ('    resource 0x7f060000 xml/agent_accessibility_service\n'
                     '      () (file) res/a.xml type=XML\n'
                     '      (v34) (file) res/b.xml type=XML\n')
        self.assertEqual({apk.BASE: 'res/a.xml', apk.V34: 'res/b.xml'}, apk.resource_entries(resources, 'visualAcceptance')[1])
        for bad in (resources.replace('(v34)', '(v33)'), resources + '      (land-v34) (file) res/c.xml type=XML\n',
                    resources.replace('res/b.xml', 'res/../b.xml'), resources.replace('res/b.xml', 'res/a.xml'),
                    resources.replace('      (v34) (file) res/b.xml type=XML', '      (v34) @0x7f060001')):
            with self.assertRaises(ValueError): apk.resource_entries(bad, 'visualAcceptance')
        with self.assertRaises(ValueError): apk.resource_entries(resources, 'release')
        for change in ('path', 'binding', 'boolean'):
            value = permission_proof(); release = value['variants']['release']
            if change == 'path': release['resourcePaths'][apk.BASE] = '../other.xml'
            if change == 'binding': release['serviceBinding']['resource'] = 'xml/other'
            if change == 'boolean': release['serviceResources'][apk.BASE]['canTakeScreenshot'] = 0
            with self.assertRaises(ValueError): apk.validate_report(value, BINDING['commit'])

    def test_failure_after_actual_receipt_preserves_original_failed_case_and_received_png(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); lane = write_lane(root); name = native.CASES[0]
            case_path = lane / native.EVIDENCE_DIR / (name + '.case.json')
            case = json.loads(case_path.read_text())
            case.update(status='failed', failureCode='POST_RECEIPT_ASSERTION')
            old.dump(case_path, case)
            original_case = case_path.read_bytes()
            image_path = lane / native.EVIDENCE_DIR / case['image']
            original_image = image_path.read_bytes()
            standard = json.loads((lane / 'screenshots/manifest.json').read_text())
            standard['tests'][0]['testResult'] = 'fail'
            old.dump(lane / 'screenshots/manifest.json', standard)
            original_log = json.dumps({'runner': 'direct_adb_instrumentation', 'exitCode': 1, 'passed': False,
                                       'stdout': 'FAILURES!!!\nTests run: 2,  Failures: 1\n'}).encode()
            (lane / 'instrumentation.json').write_bytes(original_log)
            report = json.loads((lane / 'report.json').read_text())
            report['status'] = 'failed'; report['cases'][0]['status'] = 'failed'
            report['instrumentation'].update(exitCode=1, passed=False, bytes=len(original_log),
                                             sha256=hashlib.sha256(original_log).hexdigest())
            old.dump(lane / 'report.json', report)
            capture = json.loads((lane / 'capture.json').read_text())
            capture['instrumentationExitCode'] = 1
            old.dump(lane / 'capture.json', capture)
            result = old.archive(root, run=source_run(), jobs=[job()])
            self.assertEqual({'passed': 1, 'failed': 1, 'unverified': 6}, result['caseCounts'])
            archived = result['apis'][0]
            self.assertEqual('failed', archived['status'])
            self.assertEqual('failed', archived['driverStatus'])
            self.assertEqual('failed', archived['cases'][0]['status'])
            self.assertEqual('failed', archived['cases'][0]['disclosureCaseStatus'])
            self.assertEqual('fail', archived['cases'][0]['standardTestResult'])
            self.assertEqual([native.EVIDENCE_DIR + '/' + case['image']], archived['verifiedReceivedPngs'])
            destination = root / 'screenshots/123-2/api-34'
            self.assertEqual(original_case, (destination / native.EVIDENCE_DIR / case_path.name).read_bytes())
            self.assertEqual(original_image, (destination / native.EVIDENCE_DIR / case['image']).read_bytes())
            self.assertEqual(original_log, (destination / 'instrumentation.json').read_bytes())
            image_entry = next(item for item in archived['files'] if item['path'] == native.EVIDENCE_DIR + '/' + case['image'])
            self.assertEqual(case['receivedSha256'], image_entry['sha256'])

    def test_unrun_native_lane_keeps_two_unverified_and_no_fake_instrumentation(self):
        env = old_driver.environment(); env['GITHUB_WORKFLOW_REF'] = BINDING['repository'] + '/' + native.WORKFLOW + '@refs/heads/master'
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, env, clear=True):
            output = Path(temp)
            self.assertEqual(0, native.main(['prepare', '--api', '37.0', '--output', str(output)]))
            self.assertEqual(0, native.main(['finalize', '--api', '37.0', '--output', str(output), '--outcome', 'skipped']))
            report = json.loads((output / 'report.json').read_text())
            self.assertEqual(['unverified', 'unverified'], [c['status'] for c in report['cases']])
            self.assertNotIn('instrumentation', report)


if __name__ == '__main__': unittest.main()
