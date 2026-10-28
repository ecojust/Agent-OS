#!/usr/bin/env python3
"""Offline, pinned-current-signer preflight for two local Agent OS APKs (POSIX)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import struct
import sys
import subprocess
import tempfile
import time
import zipfile
import zlib

from verify_visual_acceptance_apks import xml_elements

PACKAGE = 'dev.agentos.app'
MAX_APK_BYTES = 64 * 1024 * 1024
MAX_ENTRIES = 4096
MAX_CENTRAL_DIRECTORY_BYTES = 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_EXPANDED_BYTES = 256 * 1024 * 1024
MAX_TOOL_OUTPUT_BYTES = 1024 * 1024  # stdout and stderr combined, while reading
TOOL_TIMEOUT_SECONDS = 30
ANDROID = 'http://schemas.android.com/apk/res/android:'


class Rejected(Exception):
    """Only fixed internal reason codes cross the CLI/report boundary."""


def require(condition, reason):
    if not condition:
        raise Rejected(reason)


def run_tool(argv, *, timeout=TOOL_TIMEOUT_SECONDS, limit=MAX_TOOL_OUTPUT_BYTES, stderr=False):
    """Bound captured output in memory, kill the process group on rejection/timeout."""
    require(os.name == 'posix', 'UNSUPPORTED_HOST')
    process = None
    try:
        process = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True,
                                   env={**os.environ, 'LC_ALL': 'C'})
        deadline = time.monotonic() + timeout
        output = {1: bytearray(), 2: bytearray()}
        total = 0
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ, 1)
            selector.register(process.stderr, selectors.EVENT_READ, 2)
            while selector.get_map():
                remaining = deadline - time.monotonic()
                require(remaining > 0, 'TOOL_TIMEOUT')
                for key, _ in selector.select(min(remaining, 0.1)):
                    block = os.read(key.fileobj.fileno(), min(65536, limit - total + 1))
                    if not block:
                        selector.unregister(key.fileobj)
                        continue
                    total += len(block)
                    require(total <= limit, 'TOOL_OUTPUT_LIMIT')
                    output[key.data].extend(block)
            remaining = deadline - time.monotonic()
            require(remaining > 0, 'TOOL_TIMEOUT')
            try:
                code = process.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                raise Rejected('TOOL_TIMEOUT') from None
        require(code == 0, 'TOOL_FAILED')
        try:
            return bytes(output[2 if stderr else 1]).decode('utf-8', 'strict')
        except UnicodeError:
            raise Rejected('TOOL_OUTPUT_INVALID') from None
    except OSError:
        raise Rejected('TOOL_UNAVAILABLE') from None
    finally:
        if process is not None:
            primary_failure = sys.exc_info()[0] is not None
            cleanup_failed = False
            # Also stop any children retaining pipes after the SDK's parent exits.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            except OSError:
                cleanup_failed = True
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                cleanup_failed = True
            process.stdout.close()
            process.stderr.close()
            if cleanup_failed and not primary_failure:
                raise Rejected('TOOL_CLEANUP_FAILED')


def tool_path(value):
    try:
        path = Path(value).resolve(strict=True)
        require(path.is_file() and os.access(path, os.X_OK), 'TOOL_UNAVAILABLE')
        return str(path)
    except (OSError, RuntimeError, ValueError):
        raise Rejected('TOOL_UNAVAILABLE') from None


def digest(path):
    count = 0
    sha = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(65536):
            count += len(block)
            require(count <= MAX_APK_BYTES, 'APK_SIZE_LIMIT')
            sha.update(block)
    return {'bytes': count, 'sha256': sha.hexdigest()}


def snapshot(source, destination):
    """One bounded read of a regular input, never follow a final-component symlink."""
    try:
        fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            os.close(fd)
            raise Rejected('INPUT_NOT_REGULAR')
        with os.fdopen(fd, 'rb') as original:
            require(0 < before.st_size <= MAX_APK_BYTES, 'APK_SIZE_LIMIT')
            count = 0
            sha = hashlib.sha256()
            with destination.open('xb') as target:
                os.chmod(destination, 0o600)
                while block := original.read(min(65536, MAX_APK_BYTES - count + 1)):
                    count += len(block)
                    require(count <= MAX_APK_BYTES, 'APK_SIZE_LIMIT')
                    target.write(block)
                    sha.update(block)
            after = os.fstat(original.fileno())
            require((before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                    (after.st_size, after.st_mtime_ns, after.st_ctime_ns) and count == before.st_size,
                    'INPUT_CHANGED_DURING_COPY')
        os.chmod(destination, 0o400)
        identity = {'bytes': count, 'sha256': sha.hexdigest()}
        require(digest(destination) == identity, 'SNAPSHOT_CHANGED')
        return identity
    except OSError:
        raise Rejected('INPUT_UNAVAILABLE') from None


def check_zip(path):
    try:
        # Bound the central directory before zipfile allocates ZipInfo objects.
        # This small standalone-APK contract excludes ZIP64, spanning and trailing data.
        size = path.stat().st_size
        with path.open('rb') as stream:
            stream.seek(max(0, size - 65557))
            tail = stream.read(65557)
        offset = tail.rfind(b'PK\x05\x06')
        require(offset >= 0 and len(tail) - offset >= 22, 'ZIP_INVALID')
        eocd_offset = size - len(tail) + offset
        with path.open('rb') as stream:
            stream.seek(max(0, eocd_offset - 20))
            locator = stream.read(20)
        require(not locator.startswith(b'PK\x06\x07'), 'ZIP_LAYOUT_UNSUPPORTED')
        _, disk, start_disk, disk_count, count, cd_bytes, cd_offset, comment = struct.unpack(
            '<4s4H2IH', tail[offset:offset + 22])
        require(disk == start_disk == 0 and disk_count == count and count != 65535
                and cd_bytes != 4294967295 and cd_offset != 4294967295, 'ZIP_LAYOUT_UNSUPPORTED')
        require(0 < count <= MAX_ENTRIES, 'ZIP_ENTRY_LIMIT')
        require(cd_bytes <= MAX_CENTRAL_DIRECTORY_BYTES, 'ZIP_DIRECTORY_LIMIT')
        require(len(tail) - offset == 22 + comment
                and cd_offset + cd_bytes == size - len(tail) + offset, 'ZIP_LAYOUT_UNSUPPORTED')
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            require(0 < len(entries) <= MAX_ENTRIES, 'ZIP_ENTRY_LIMIT')
            require(len(entries) == count, 'ZIP_INVALID')
            for entry in entries:
                require(0 <= entry.header_offset < cd_offset, 'ZIP_INVALID')
                extra_offset = 0
                while extra_offset < len(entry.extra):
                    require(len(entry.extra) - extra_offset >= 4, 'ZIP_INVALID')
                    kind, length = struct.unpack_from('<HH', entry.extra, extra_offset)
                    require(kind != 1, 'ZIP_LAYOUT_UNSUPPORTED')
                    extra_offset += 4 + length
                    require(extra_offset <= len(entry.extra), 'ZIP_INVALID')
            names = [entry.filename for entry in entries]
            require(len(set(names)) == len(names), 'ZIP_DUPLICATE_ENTRY')
            require(names.count('AndroidManifest.xml') == 1, 'MANIFEST_MISSING')
            require(all(not entry.flag_bits & 1 for entry in entries), 'ZIP_ENCRYPTED')
            require(all(entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                        for entry in entries), 'ZIP_COMPRESSION_UNSUPPORTED')
            require(sum(entry.file_size for entry in entries) <= MAX_EXPANDED_BYTES,
                    'ZIP_EXPANSION_LIMIT')
            manifest = archive.getinfo('AndroidManifest.xml')
            require(0 < manifest.file_size <= MAX_MANIFEST_BYTES, 'MANIFEST_SIZE_LIMIT')
            with archive.open(manifest) as stream:
                require(len(stream.read(MAX_MANIFEST_BYTES + 1)) == manifest.file_size,
                        'MANIFEST_SIZE_LIMIT')
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile, EOFError, zlib.error, OverflowError):
        raise Rejected('ZIP_INVALID') from None


def attribute(node, name, *, android=False, optional=False):
    prefix = re.escape(ANDROID + name) + r'(?:\(0x[0-9a-fA-F]+\))?' if android else re.escape(name)
    values = []
    for line in node['attributes']:
        match = re.fullmatch(r'\s*A: ' + prefix + r'=(.*)', line)
        if match:
            values.append(match.group(1))
    require(len(values) <= 1 and (optional or len(values) == 1), 'MANIFEST_METADATA_INVALID')
    return values[0] if values else None


def integer(value):
    require(value is not None and re.fullmatch(r'[0-9]{1,10}', value) is not None,
            'MANIFEST_METADATA_INVALID')
    result = int(value)
    require(result <= 2147483647, 'MANIFEST_METADATA_INVALID')
    return result


def manifest_metadata(output):
    # Do not let a newline embedded in a manifest string impersonate dump structure.
    for line in output.splitlines():
        if re.match(r'\s*A:', line) and '=\"' in line:
            value = line.split('=', 1)[1]
            require(re.fullmatch(r'\"([^\"\r\n]*)\"(?: \(Raw: \"\1\"\))?', value) is not None,
                    'MANIFEST_METADATA_INVALID')
    try:
        roots = xml_elements(output)
    except ValueError:
        raise Rejected('MANIFEST_METADATA_INVALID') from None
    require(len(roots) == 1 and roots[0]['tag'] == 'manifest', 'MANIFEST_METADATA_INVALID')
    manifest = roots[0]
    package = attribute(manifest, 'package')
    require(package in ('"' + PACKAGE + '"', '"' + PACKAGE + '" (Raw: "' + PACKAGE + '")'),
            'PACKAGE_MISMATCH')
    # Check actual manifest declarations; badging alone does not establish a standalone APK.
    split_names = ('split', 'isFeatureSplit', 'configForSplit', 'requiredSplitTypes',
                   'splitTypes', 'isSplitRequired')
    for line in manifest['attributes']:
        require(not re.search(r'(?:A: |:)(?:' + '|'.join(split_names) + r')(?:\(|=)', line),
                'SPLIT_UNSUPPORTED')
    pending = list(manifest['children'])
    while pending:
        node = pending.pop()
        require(node['tag'] != 'uses-split', 'SPLIT_UNSUPPORTED')
        for line in node['attributes']:
            require(not re.search(r':isSplitRequired(?:\(|=)', line), 'SPLIT_UNSUPPORTED')
        pending.extend(node['children'])
    major = attribute(manifest, 'versionCodeMajor', android=True, optional=True)
    require(major is None or integer(major) == 0, 'VERSION_MAJOR_UNSUPPORTED')
    version = integer(attribute(manifest, 'versionCode', android=True))
    require(version > 0, 'MANIFEST_METADATA_INVALID')
    sdk = [child for child in manifest['children'] if child['tag'] == 'uses-sdk']
    require(len(sdk) == 1, 'MANIFEST_METADATA_INVALID')
    minimum = integer(attribute(sdk[0], 'minSdkVersion', android=True))
    target = integer(attribute(sdk[0], 'targetSdkVersion', android=True))
    require(minimum == 30 and target >= minimum, 'SDK_POLICY_UNSUPPORTED')
    return {'package': PACKAGE, 'versionCode': version, 'minSdk': minimum, 'targetSdk': target}


def signature_metadata(output, pin):
    require(re.findall(r'^Verifies\s*$', output, re.M) == ['Verifies'], 'SIGNATURE_UNVERIFIED')
    require(re.findall(r'^Number of signers:\s*([0-9]+)\s*$', output, re.M) == ['1'],
            'SIGNER_COUNT_UNSUPPORTED')
    certs = re.findall(r'^Signer #([0-9]+) certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$',
                       output, re.M)
    require(len(certs) == 1 and certs[0][0] == '1', 'SIGNER_COUNT_UNSUPPORTED')
    require(certs[0][1].lower() == pin, 'TRUSTED_CERTIFICATE_MISMATCH')
    # apksigner verifies platform signing schemes, but this text is not a lineage attestation.
    return {'currentSignerCount': 1, 'currentSignerSha256': pin}


def preflight(baseline, candidate, pin, apksigner, aapt2):
    require(isinstance(pin, str) and re.fullmatch(r'[0-9a-fA-F]{64}', pin) is not None,
            'TRUSTED_CERTIFICATE_INVALID')
    pin = pin.lower()
    apksigner, aapt2 = tool_path(apksigner), tool_path(aapt2)
    signer_version = run_tool([apksigner, 'version']).strip()
    aapt_version = run_tool([aapt2, 'version'], stderr=True).strip()
    require(re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,3}', signer_version) is not None and
            re.fullmatch(r'Android Asset Packaging Tool \(aapt\) [0-9A-Za-z.\-]+', aapt_version) is not None,
            'TOOL_VERSION_UNSUPPORTED')
    records = {}
    with tempfile.TemporaryDirectory(prefix='agentos-apk-preflight-') as directory:
        os.chmod(directory, 0o700)
        for role, source in (('baseline', baseline), ('candidate', candidate)):
            copy = Path(directory) / (role + '.apk')
            identity = snapshot(source, copy)
            check_zip(copy)
            signature = signature_metadata(run_tool([apksigner, 'verify', '--verbose', '--print-certs',
                                                      '-Werr', str(copy)]), pin)
            metadata = manifest_metadata(run_tool([aapt2, 'dump', 'xmltree', str(copy),
                                                    '--file', 'AndroidManifest.xml']))
            require(digest(copy) == identity, 'SNAPSHOT_CHANGED')
            records[role] = {**identity, **metadata, **signature}
    require(records['candidate']['versionCode'] > records['baseline']['versionCode'],
            'VERSION_NOT_INCREASING')
    return {'schemaVersion': 1, 'kind': 'agentos-offline-apk-upgrade-preflight', 'status': 'passed',
            'signingPolicy': 'same-current-certificate-pinned', 'lineageEvaluated': False,
            'installationPerformed': False, 'deviceCompatibilityEvaluated': False,
            'configurationMigrationEvaluated': False, 'trustedCertificateSha256': pin,
            'tools': {'apksigner': signer_version, 'aapt2': aapt_version}, **records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--candidate', required=True, type=Path)
    parser.add_argument('--trusted-cert-sha256', required=True)
    parser.add_argument('--apksigner', required=True)
    parser.add_argument('--aapt2', required=True)
    args = parser.parse_args()
    try:
        report = preflight(args.baseline, args.candidate, args.trusted_cert_sha256,
                           args.apksigner, args.aapt2)
    except Rejected as error:
        print(json.dumps({'schemaVersion': 1, 'kind': 'agentos-offline-apk-upgrade-preflight',
                          'status': 'rejected', 'reason': str(error)}, sort_keys=True))
        return 1
    except (OSError, UnicodeError):
        print(json.dumps({'schemaVersion': 1, 'kind': 'agentos-offline-apk-upgrade-preflight',
                          'status': 'rejected', 'reason': 'LOCAL_IO_FAILED'}, sort_keys=True))
        return 1
    print(json.dumps(report, sort_keys=True, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
