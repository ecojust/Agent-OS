"""Offline refusal tests plus real SDK/certificate APK integration (no installation)."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import struct
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

import check_apk_upgrade as check


class BoundariesTest(unittest.TestCase):
    def rejected(self, reason, function, *args, **kwargs):
        with self.assertRaises(check.Rejected) as caught:
            function(*args, **kwargs)
        self.assertEqual(reason, str(caught.exception))

    def test_runner_bounds_combined_output_while_child_is_alive(self):
        self.rejected('TOOL_OUTPUT_LIMIT', check.run_tool,
                      [sys.executable, '-c', "import os,time;os.write(1,b'x'*60);os.write(2,b'y'*60);time.sleep(10)"],
                      limit=100, timeout=2)

    def test_runner_timeout_and_nonzero_never_disclose_tool_text(self):
        self.rejected('TOOL_TIMEOUT', check.run_tool,
                      [sys.executable, '-c', 'import time;time.sleep(10)'], timeout=0.1)
        self.rejected('TOOL_FAILED', check.run_tool,
                      [sys.executable, '-c', "import sys;sys.stderr.write('private/path/arbitrary text');sys.exit(7)"])

    def test_runner_bounds_parent_exit_with_child_holding_pipes(self):
        self.rejected('TOOL_TIMEOUT', check.run_tool,
                      [sys.executable, '-c', 'import os,time;pid=os.fork();time.sleep(10) if pid==0 else None'],
                      timeout=0.1)

    def test_runner_accepts_exact_output_limit_and_rejects_invalid_utf8(self):
        self.assertEqual('x' * 100, check.run_tool([sys.executable, '-c', "print('x'*100,end='')"], limit=100))
        self.rejected('TOOL_OUTPUT_INVALID', check.run_tool,
                      [sys.executable, '-c', "import os;os.write(1,b'\\xff')"])

    def test_snapshot_rejects_links_nonregular_and_oversize(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source.apk'
            source.write_bytes(b'x')
            (root / 'link.apk').symlink_to(source)
            self.rejected('INPUT_UNAVAILABLE', check.snapshot, root / 'link.apk', root / 'copy.apk')
            self.rejected('INPUT_NOT_REGULAR', check.snapshot, root, root / 'copy.apk')
            with source.open('wb') as stream:
                stream.truncate(check.MAX_APK_BYTES + 1)
            self.rejected('APK_SIZE_LIMIT', check.snapshot, source, root / 'copy.apk')

    def test_zip_missing_duplicate_manifest_and_expansion_bounds(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'archive.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('not-manifest', b'x')
            self.rejected('MANIFEST_MISSING', check.check_zip, apk)
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                with zipfile.ZipFile(apk, 'w') as archive:
                    archive.writestr('AndroidManifest.xml', b'x')
                    archive.writestr('AndroidManifest.xml', b'y')
            self.rejected('ZIP_DUPLICATE_ENTRY', check.check_zip, apk)
            with zipfile.ZipFile(apk, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('AndroidManifest.xml', b'x' * (check.MAX_MANIFEST_BYTES + 1))
            self.rejected('MANIFEST_SIZE_LIMIT', check.check_zip, apk)
            with mock.patch.object(check, 'MAX_EXPANDED_BYTES', 10):
                self.rejected('ZIP_EXPANSION_LIMIT', check.check_zip, apk)
            with mock.patch.object(check, 'MAX_ENTRIES', 0):
                self.rejected('ZIP_ENTRY_LIMIT', check.check_zip, apk)
            apk.write_bytes(b'not a zip')
            self.rejected('ZIP_INVALID', check.check_zip, apk)

    def test_corrupt_deflate_is_a_fixed_rejection(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'broken.apk'
            with zipfile.ZipFile(apk, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('AndroidManifest.xml', b'x' * 1000)
            data = bytearray(apk.read_bytes())
            name_length, extra_length = struct.unpack_from('<HH', data, 26)
            data[30 + name_length + extra_length] = 0x07  # invalid DEFLATE block type
            apk.write_bytes(data)
            self.rejected('ZIP_INVALID', check.check_zip, apk)

    def test_declared_directory_limits_are_checked_before_zipfile_allocation(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'directory.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'x')
            original = apk.read_bytes()
            for count, size, reason in ((4097, 1, 'ZIP_ENTRY_LIMIT'),
                                        (65535, 1, 'ZIP_LAYOUT_UNSUPPORTED'),
                                        (1, check.MAX_CENTRAL_DIRECTORY_BYTES + 1, 'ZIP_DIRECTORY_LIMIT')):
                data = bytearray(original)
                eocd = data.rfind(b'PK\x05\x06')
                struct.pack_into('<HHI', data, eocd + 8, count, count, size)
                apk.write_bytes(data)
                with mock.patch.object(check.zipfile, 'ZipFile', side_effect=AssertionError('too early')):
                    self.rejected(reason, check.check_zip, apk)

    def test_zip64_locator_cannot_override_small_eocd_before_zipfile_allocation(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'zip64.apk'
            for comment in (b'', b'c' * 65535):
                with zipfile.ZipFile(apk, 'w') as archive:
                    archive.writestr('AndroidManifest.xml', b'x')
                    archive.comment = comment
                data = bytearray(apk.read_bytes())
                eocd = data.rfind(b'PK\x05\x06')
                old_size = struct.unpack_from('<I', data, eocd + 12)[0]
                locator = struct.pack('<4sIQI', b'PK\x06\x07', 0, 0, 1)
                data[eocd:eocd] = locator
                struct.pack_into('<I', data, eocd + 20 + 12, old_size + 20)
                apk.write_bytes(data)
                with mock.patch.object(check.zipfile, 'ZipFile', side_effect=AssertionError('too early')):
                    self.rejected('ZIP_LAYOUT_UNSUPPORTED', check.check_zip, apk)

    def test_entry_zip64_metadata_and_out_of_range_offset_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'entry.apk'
            info = zipfile.ZipInfo('AndroidManifest.xml')
            info.extra = struct.pack('<HH', 1, 0)
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr(info, b'x')
            self.rejected('ZIP_LAYOUT_UNSUPPORTED', check.check_zip, apk)
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'x')
            data = bytearray(apk.read_bytes())
            central = data.find(b'PK\x01\x02')
            struct.pack_into('<I', data, central + 42, 0xfffffffe)
            apk.write_bytes(data)
            self.rejected('ZIP_INVALID', check.check_zip, apk)

    def test_signature_parser_is_strict_about_signer_number_and_explicit_pin(self):
        pin = 'a' * 64
        output = 'Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: ' + pin + '\n'
        self.assertEqual(pin, check.signature_metadata(output, pin)['currentSignerSha256'])
        self.rejected('SIGNER_COUNT_UNSUPPORTED', check.signature_metadata, output.replace('#1', '#2'), pin)
        self.rejected('TRUSTED_CERTIFICATE_MISMATCH', check.signature_metadata, output, 'b' * 64)
        self.rejected('SIGNATURE_UNVERIFIED', check.signature_metadata, output + output, pin)


class RealSdkTest(unittest.TestCase):
    """Sign actual binary-manifest APKs using temporary, never committed test keys."""
    rejected = BoundariesTest.rejected

    @classmethod
    def setUpClass(cls):
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME',
                   '/workspace/android-toolchain/sdk')))
        build = Path(os.environ.get('APK_PREFLIGHT_BUILD_TOOLS', str(sdk / 'build-tools/35.0.0')))
        cls.signer, cls.aapt = build / 'apksigner', build / 'aapt2'
        cls.android = Path(os.environ.get('APK_PREFLIGHT_ANDROID_JAR', str(sdk / 'platforms/android-35/android.jar')))
        if not all(path.is_file() for path in (cls.signer, cls.aapt, cls.android)) or not shutil.which('keytool'):
            raise unittest.SkipTest('Real SDK tests need build-tools 35.0.0, android-35/android.jar and keytool')
        cls.temporary = tempfile.TemporaryDirectory(prefix='agentos-apk-test-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.keystore = cls.root / 'test.p12'
        for alias in ('first', 'second'):
            cls.command(['keytool', '-genkeypair', '-alias', alias, '-keystore', str(cls.keystore),
                         '-storepass', 'test-password', '-keypass', 'test-password', '-keyalg', 'RSA',
                         '-keysize', '2048', '-validity', '2', '-dname', 'CN=Ephemeral APK Preflight Test', '-noprompt'])
        certificate = cls.command(['keytool', '-exportcert', '-alias', 'first', '-keystore', str(cls.keystore),
                                   '-storepass', 'test-password'])
        cls.pin = hashlib.sha256(certificate).hexdigest()
        cls.baseline = cls.make_apk('baseline', 1)
        cls.candidate = cls.make_apk('candidate', 2)

    @classmethod
    def command(cls, argv):
        result = subprocess.run([str(value) for value in argv], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=30)
        if result.returncode:
            raise AssertionError('Fixture SDK command failed: ' + result.stderr.decode('utf-8', 'replace'))
        return result.stdout

    @classmethod
    def make_apk(cls, name, version, *, package=check.PACKAGE, minimum=30, root_attributes='',
                 child='', app_attributes='', aliases=('first',)):
        manifest = cls.root / (name + '.xml')
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                            f'package="{package}" android:versionCode="{version}" {root_attributes}>'
                            f'<uses-sdk android:minSdkVersion="{minimum}" android:targetSdkVersion="35"/>'
                            f'{child}<application android:hasCode="false" {app_attributes}/></manifest>')
        unsigned, signed = cls.root / (name + '-unsigned.apk'), cls.root / (name + '.apk')
        cls.command([cls.aapt, 'link', '--manifest', manifest, '-I', cls.android, '-o', unsigned])
        command = [cls.signer, 'sign', '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
                   '--v3-signing-enabled', 'false', '--out', signed]
        for index, alias in enumerate(aliases):
            if index:
                command.append('--next-signer')
            command.extend(['--ks', cls.keystore, '--ks-key-alias', alias, '--ks-pass', 'pass:test-password'])
        cls.command(command + [unsigned])
        return signed

    def inspect(self, candidate=None, baseline=None, pin=None):
        return check.preflight(baseline or self.baseline, candidate or self.candidate,
                               pin or self.pin, self.signer, self.aapt)

    def test_real_two_signed_apks_pass_and_report_exact_immutable_artifacts(self):
        before = [check.digest(path) for path in (self.baseline, self.candidate)]
        report = self.inspect()
        self.assertEqual('passed', report['status'])
        self.assertFalse(report['installationPerformed'])
        self.assertFalse(report['lineageEvaluated'])
        self.assertEqual([1, 2], [report[role]['versionCode'] for role in ('baseline', 'candidate')])
        for role, identity in zip(('baseline', 'candidate'), before):
            self.assertEqual(identity, {key: report[role][key] for key in identity})
        self.assertEqual(before, [check.digest(path) for path in (self.baseline, self.candidate)])
        self.assertNotIn(str(self.root), json.dumps(report))

    def test_real_certificate_pin_is_required_even_for_two_matching_signers(self):
        self.rejected('TRUSTED_CERTIFICATE_MISMATCH', self.inspect, pin='0' * 64)
        self.rejected('TRUSTED_CERTIFICATE_INVALID', self.inspect, pin='not-a-pin')
        other = self.make_apk('other-cert', 2, aliases=('second',))
        self.rejected('TRUSTED_CERTIFICATE_MISMATCH', self.inspect, other)
        both_other = self.make_apk('other-baseline', 1, aliases=('second',))
        self.rejected('TRUSTED_CERTIFICATE_MISMATCH', self.inspect, other, both_other)

    def test_real_multiple_signers_are_rejected(self):
        other = self.make_apk('multiple-signers', 2, aliases=('first', 'second'))
        self.rejected('SIGNER_COUNT_UNSUPPORTED', self.inspect, other)

    def test_real_tampering_is_rejected_by_sdk_not_by_stdout_fixture(self):
        tampered = self.root / 'tampered.apk'
        shutil.copyfile(self.candidate, tampered)
        with zipfile.ZipFile(tampered, 'a') as archive:
            archive.writestr('injected.txt', b'changed signed APK content')
        self.rejected('TOOL_FAILED', self.inspect, tampered)

    def test_equal_and_decreasing_versions_are_rejected(self):
        self.rejected('VERSION_NOT_INCREASING', self.inspect, self.baseline)
        self.rejected('VERSION_NOT_INCREASING', self.inspect, self.baseline, self.candidate)

    def test_real_foreign_package_major_and_minimum_are_rejected(self):
        for name, kwargs, reason in (
                ('foreign', {'package': 'example.other'}, 'PACKAGE_MISMATCH'),
                ('major', {'root_attributes': 'android:versionCodeMajor="1"'}, 'VERSION_MAJOR_UNSUPPORTED'),
                ('minimum', {'minimum': 31}, 'SDK_POLICY_UNSUPPORTED')):
            with self.subTest(name=name):
                self.rejected(reason, self.inspect, self.make_apk(name, 2, **kwargs))

    def test_real_manifest_split_declarations_are_rejected(self):
        for name, kwargs in (
                ('split', {'root_attributes': 'split="feature"'}),
                ('uses-split', {'child': '<uses-split android:name="feature"/>'}),
                ('requires-split', {'app_attributes': 'android:isSplitRequired="true"'}),
                ('feature-split', {'root_attributes': 'android:isFeatureSplit="true"'}),
                ('required-types', {'root_attributes': 'android:requiredSplitTypes="base__abi"'}),
                ('split-types', {'root_attributes': 'android:splitTypes="base__abi"'}),
                ('config-split', {'root_attributes': 'configForSplit="base"'})):
            with self.subTest(name=name):
                self.rejected('SPLIT_UNSUPPORTED', self.inspect, self.make_apk(name, 2, **kwargs))

    def test_multiline_manifest_value_cannot_inject_dump_structure(self):
        apk = self.make_apk('multiline', 2, root_attributes='android:versionName="first&#10;second"')
        self.rejected('MANIFEST_METADATA_INVALID', self.inspect, apk)

    def test_live_source_replacement_cannot_change_snapshot_being_verified(self):
        source = self.root / 'replaceable.apk'
        shutil.copyfile(self.candidate, source)
        identity = check.digest(source)
        original = check.run_tool
        observed = []
        def run(argv, **kwargs):
            if 'verify' in argv and argv[-1].endswith('candidate.apk'):
                source.write_bytes(b'replaced live input after its snapshot')
                self.assertEqual(0o400, Path(argv[-1]).stat().st_mode & 0o777)
                self.assertEqual(0o700, Path(argv[-1]).parent.stat().st_mode & 0o777)
                observed.append(argv[-1])
            if 'xmltree' in argv and argv[3].endswith('candidate.apk'):
                self.assertEqual(observed[-1], argv[3])
            return original(argv, **kwargs)
        with mock.patch.object(check, 'run_tool', side_effect=run):
            report = self.inspect(source)
        self.assertEqual(identity['sha256'], report['candidate']['sha256'])
        self.assertTrue(observed)
        self.assertFalse(Path(observed[-1]).exists())

    def test_snapshot_modification_before_success_is_detected(self):
        original = check.run_tool
        def run(argv, **kwargs):
            result = original(argv, **kwargs)
            if 'xmltree' in argv:
                path = Path(argv[3])
                path.chmod(0o600)
                with path.open('ab') as stream:
                    stream.write(b'mutation')
            return result
        with mock.patch.object(check, 'run_tool', side_effect=run):
            self.rejected('SNAPSHOT_CHANGED', self.inspect)

    def test_cli_rejection_is_fixed_json_and_does_not_echo_input_or_tool_errors(self):
        result = subprocess.run([sys.executable, str(Path(check.__file__)), '--baseline', str(self.baseline),
                                 '--candidate', str(self.candidate), '--trusted-cert-sha256', '0' * 64,
                                 '--apksigner', str(self.signer), '--aapt2', str(self.aapt)],
                                capture_output=True, timeout=30)
        self.assertEqual(1, result.returncode)
        self.assertEqual('TRUSTED_CERTIFICATE_MISMATCH', json.loads(result.stdout)['reason'])
        self.assertEqual(b'', result.stderr)
        self.assertNotIn(str(self.root).encode(), result.stdout)


if __name__ == '__main__':
    unittest.main()
