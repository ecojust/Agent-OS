#!/usr/bin/env python3
"""Fixed-source independent host review; synthetic input and controlled platform stubs only."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--json-jar', type=Path, required=True)
parser.add_argument('--java-home', type=Path, required=True)
args = parser.parse_args()
folder = Path(__file__).resolve().parent
repo = folder.parents[4]
helper_path = 'compat-smoke/src/androidTest/java/dev/agentos/compat/NotesImeAssociationDiagnostic.java'
helper = repo / helper_path
baseline_revision = '35519e9fb77dc751bf8529768292cdcd1eb3b7ed'
expected_baseline = 'c920dce631dbd4e81397282dbf598c0b754cbcb7934eb32c98be90172f9ca90d'
expected_helper = '5de8008990573422edb9cbd33bace0dfaa2cfd860ddea1d49a17d30183534bec'
sha = lambda value: hashlib.sha256(value).hexdigest()
if sha(args.json_jar.read_bytes()) != '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
    raise SystemExit('Expected pinned org.json:json:20240303 jar')
if sha(helper.read_bytes()) != expected_helper:
    raise SystemExit('Helper differs from the reviewed source; do not relabel this review')
baseline = subprocess.check_output(['git', 'show', baseline_revision + ':' + helper_path], cwd=repo)
if sha(baseline) != expected_baseline:
    raise SystemExit('Baseline source fingerprint differs')
baseline = baseline.decode().replace('final class NotesImeAssociationDiagnostic {', 'final class CaptureBoundBaseline {').replace('private NotesImeAssociationDiagnostic() {', 'private CaptureBoundBaseline() {')
stubs = folder.parent / 'java-host-tests'
sources = [stubs / 'android/app/UiAutomation.java', stubs / 'android/os/SystemClock.java', stubs / 'android/os/ParcelFileDescriptor.java', stubs / 'dev/agentos/compat/SmokeHarness.java']
with tempfile.TemporaryDirectory(prefix='notes-ime-independent-') as temporary:
    temporary = Path(temporary)
    baseline_path = temporary / 'CaptureBoundBaseline.java'
    baseline_path.write_text(baseline)
    subprocess.run([str(args.java_home / 'bin/javac'), '-cp', str(args.json_jar), '-d', str(temporary), str(helper), str(baseline_path), *map(str, sources), *map(str, sorted(folder.glob('*.java')))], check=True)
    subprocess.run([str(args.java_home / 'bin/java'), '-cp', str(temporary) + ':' + str(args.json_jar), 'dev.agentos.compat.CaptureBoundReview'], check=True, timeout=15)
print('reviewed_helper_sha256=' + expected_helper)
