#!/usr/bin/env python3
"""Build a shell diagnostic dex; hidden-platform ABI stubs are compile-only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'tools/window-infos-raw'
MAX_JAR = 2 * 1024 * 1024


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def dex_classes(data):
    """Read class_defs, not referenced types: Android references are expected, definitions are not."""
    require(len(data) <= MAX_JAR and len(data) >= 112 and re.fullmatch(b'dex\n[0-9]{3}\x00', data[:8]), 'Invalid dex header')
    word = lambda offset: struct.unpack_from('<I', data, offset)[0]
    require(word(32) == len(data) and word(36) == 112 and word(40) == 0x12345678, 'Unsupported dex layout')
    string_count, string_offset, type_count, type_offset = [word(n) for n in (56, 60, 64, 68)]
    class_count, class_offset = word(96), word(100)
    require(0 < class_count <= 256 and 0 < type_count <= 65535 and 0 < string_count <= 65535, 'Dex count bound')
    for start, size, count in [(string_offset, 4, string_count), (type_offset, 4, type_count), (class_offset, 32, class_count)]:
        require(start >= 112 and start + size * count <= len(data), 'Dex table bounds')
    names = []
    for i in range(class_count):
        type_id = word(class_offset + 32 * i)
        require(type_id < type_count, 'Dex class type index')
        string_id = word(type_offset + 4 * type_id)
        require(string_id < string_count, 'Dex type string index')
        start = word(string_offset + 4 * string_id)
        require(112 <= start < len(data), 'Dex string bounds')
        for j in range(5):
            require(start + j < len(data), 'Dex string length bounds')
            if data[start + j] < 128:
                start += j + 1
                break
        else:
            raise ValueError('Dex invalid ULEB length')
        end = data.find(b'\0', start, min(start + 1024, len(data)))
        require(end >= start, 'Dex class descriptor bound')
        name = data[start:end].decode('ascii')
        require(re.fullmatch(r'Ldev/agentos/raw/[A-Za-z0-9_$]+;', name), 'Foreign class definition in dex')
        names.append(name[1:-1].replace('/', '.'))
    require(len(names) == len(set(names)), 'Duplicate dex class')
    return sorted(names)


def run(command):
    result = subprocess.run([str(x) for x in command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    require(len(result.stdout) <= 65536, 'Build output exceeds bound')
    require(result.returncode == 0, 'Build tool failed: ' + result.stdout.decode('utf-8', 'replace')[:2000])
    return result.stdout.decode('utf-8', 'strict').strip()


def add_zip(archive, name, data):
    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    archive.writestr(info, data)


def build(sdk_root, output):
    sdk_root = sdk_root.resolve(); output = output.resolve()
    require(not output.exists() and not Path(str(output) + '.json').exists(), 'Do not overwrite a frozen build output')
    android = sdk_root / 'platforms/android-35/android.jar'
    d8 = sdk_root / 'build-tools/35.0.0/d8'
    javac = Path(os.environ['JAVA_HOME']) / 'bin/javac' if os.environ.get('JAVA_HOME') else Path(shutil.which('javac') or '/missing/javac')
    require(all(p.is_file() for p in (android, d8, javac)), 'Java17 / Android35 build tools missing')
    javac_version = run([javac, '-version'])
    require(re.fullmatch(r'javac 17\..+', javac_version), 'Require Java17 compiler')
    d8_version = run([d8, '--version'])
    stubs = sorted((SOURCE / 'stubs').rglob('*.java'))
    sources = sorted((SOURCE / 'src').rglob('*.java'))
    require(len(stubs) == 2 and len(sources) == 4, 'Unexpected build source catalog')
    commands = []
    with tempfile.TemporaryDirectory(prefix='window-infos-raw-build-') as directory:
        work = Path(directory); stub_classes = work/'stub-classes'; classes = work/'classes'; dex_dir = work/'dex'
        for p in (stub_classes, classes, dex_dir): p.mkdir()
        commands.append([str(javac), '--release', '8', '-encoding', 'UTF-8', '-cp', str(android), '-d', str(stub_classes), *map(str, stubs)])
        run(commands[-1])
        commands.append([str(javac), '--release', '8', '-encoding', 'UTF-8', '-cp', os.pathsep.join(map(str, [android, stub_classes])), '-d', str(classes), *map(str, sources)])
        run(commands[-1])
        compiled = sorted(p.relative_to(classes).as_posix() for p in classes.rglob('*.class'))
        require(compiled and all(re.fullmatch(r'dev/agentos/raw/[A-Za-z0-9_$]+\.class', p) for p in compiled), 'Foreign compiled class')
        compile_only = sorted(p.relative_to(stub_classes).as_posix()[:-6].replace('/', '.') for p in stub_classes.rglob('*.class'))
        classes_jar = work/'owned-classes.jar'
        with zipfile.ZipFile(classes_jar, 'w') as archive:
            for name in compiled: add_zip(archive, name, (classes/name).read_bytes())
        commands.append([str(d8), '--lib', str(android), '--min-api', '30', '--output', str(dex_dir), str(classes_jar)])
        run(commands[-1])
        require(sorted(p.name for p in dex_dir.iterdir()) == ['classes.dex'], 'Unexpected dex outputs')
        dex = (dex_dir/'classes.dex').read_bytes()
        packaged = dex_classes(dex)
        expected = sorted(name[:-6].replace('/', '.') for name in compiled)
        require(packaged == expected, 'Actual dex definitions differ from owned compiled classes')
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, 'w') as archive: add_zip(archive, 'classes.dex', dex)
    jar = output.read_bytes()
    require(len(jar) <= MAX_JAR, 'Jar too large')
    metadata = {
        'schemaVersion': 1, 'kind': 'window-infos-raw-dex-build',
        'jarBytes': len(jar), 'jarSha256': digest(jar),
        'classesDexBytes': len(dex), 'classesDexSha256': digest(dex),
        'packagedClasses': packaged, 'compileOnlyStubClasses': compile_only,
        'sourceFiles': [{'path': str(p.relative_to(ROOT)), 'bytes': p.stat().st_size, 'sha256': digest(p.read_bytes())}
                        for p in [*sources, *stubs, Path(__file__).resolve()]],
        'androidJar': {'api': 35, 'bytes': android.stat().st_size, 'sha256': digest(android.read_bytes())},
        'javacVersion': javac_version, 'd8Version': d8_version, 'commands': commands,
        'runtimeHiddenPolicyMutation': False, 'compileOnlyStubsPackaged': False,
    }
    encoded = (json.dumps(metadata, indent=2) + '\n').encode()
    require(len(encoded) <= 65536, 'Build metadata too large')
    Path(str(output) + '.json').write_bytes(encoded)
    print(json.dumps({'output': str(output), 'bytes': len(jar), 'sha256': digest(jar), 'packagedClasses': len(packaged)}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.sdk_root, args.output)
