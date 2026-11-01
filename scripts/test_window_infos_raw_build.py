import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

import build_window_infos_raw as build


def sample_dex(descriptors, defined=None):
    defined = list(range(len(descriptors))) if defined is None else defined
    string_offset = 112
    type_offset = string_offset + 4 * len(descriptors)
    class_offset = type_offset + 4 * len(descriptors)
    start = class_offset + 32 * len(defined)
    data = bytearray(start)
    data[:8] = b'dex\n039\0'
    def word(offset, value): struct.pack_into('<I', data, offset, value)
    for offset, value in [(36, 112), (40, 0x12345678), (56, len(descriptors)), (60, string_offset),
                          (64, len(descriptors)), (68, type_offset), (96, len(defined)), (100, class_offset)]: word(offset, value)
    for i, name in enumerate(descriptors):
        word(string_offset + 4*i, len(data)); word(type_offset + 4*i, i)
        data.extend(bytes([len(name)]) + name.encode('ascii') + b'\0')
    for i, number in enumerate(defined): word(class_offset + i*32, number)
    word(32, len(data))
    return data


class DexDefinitions(unittest.TestCase):
    def test_owned_definition(self):
        self.assertEqual(build.dex_classes(sample_dex(['Ldev/agentos/raw/Main;'])), ['dev.agentos.raw.Main'])

    def test_android_reference_is_not_android_definition(self):
        data = sample_dex(['Ldev/agentos/raw/Main;', 'Landroid/window/WindowInfosListener;'], [0])
        self.assertEqual(build.dex_classes(data), ['dev.agentos.raw.Main'])

    def test_android_stub_definition_rejected(self):
        with self.assertRaises(ValueError): build.dex_classes(sample_dex(['Landroid/window/WindowInfosListener;']))

    def test_foreign_definition_rejected(self):
        with self.assertRaises(ValueError): build.dex_classes(sample_dex(['Lother/app/Probe;']))

    def test_duplicate_definition_rejected(self):
        with self.assertRaises(ValueError): build.dex_classes(sample_dex(['Ldev/agentos/raw/Main;'], [0, 0]))

    def test_bad_type_index_rejected(self):
        with self.assertRaises(ValueError): build.dex_classes(sample_dex(['Ldev/agentos/raw/Main;'], [1]))

    def test_truncated_tables_rejected(self):
        data = sample_dex(['Ldev/agentos/raw/Main;']); struct.pack_into('<I', data, 96, 256)
        with self.assertRaises(ValueError): build.dex_classes(data)

    def test_declared_size_rejected(self):
        data = sample_dex(['Ldev/agentos/raw/Main;']); struct.pack_into('<I', data, 32, len(data)+1)
        with self.assertRaises(ValueError): build.dex_classes(data)

    def test_unterminated_descriptor_rejected(self):
        data = sample_dex(['Ldev/agentos/raw/Main;']); data[-1] = 1
        with self.assertRaises(ValueError): build.dex_classes(data)

    def test_unterminated_uleb_rejected(self):
        data = sample_dex(['Ldev/agentos/raw/Main;']); offset = struct.unpack_from('<I', data, 112)[0]
        data[offset:offset+5] = b'\xff' * 5
        with self.assertRaises(ValueError): build.dex_classes(data)

    def test_endianness_rejected(self):
        data = sample_dex(['Ldev/agentos/raw/Main;']); struct.pack_into('<I', data, 40, 0x78563412)
        with self.assertRaises(ValueError): build.dex_classes(data)

    def test_size_bound(self):
        with self.assertRaises(ValueError): build.dex_classes(bytes(build.MAX_JAR+1))


class JavaCollector(unittest.TestCase):
    def test_real_java_candidate_and_lifecycle_boundaries(self):
        java_home = os.environ.get('JAVA_HOME')
        javac = str(Path(java_home)/'bin/javac') if java_home else shutil.which('javac')
        java = str(Path(java_home)/'bin/java') if java_home else shutil.which('java')
        self.assertTrue(javac and java, 'Java17 required for the diagnostic host checks')
        sources = [build.SOURCE/'src/dev/agentos/raw/RawCollector.java',
                   build.SOURCE/'tests/dev/agentos/raw/RawCollectorHostTest.java']
        with tempfile.TemporaryDirectory() as work:
            compiled = subprocess.run([javac, '--release', '8', '-d', work, *map(str, sources)],
                                      capture_output=True, text=True, timeout=60)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run([java, '-cp', work, 'dev.agentos.raw.RawCollectorHostTest'],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), 'RawCollector checks: 37')


if __name__ == '__main__': unittest.main()
