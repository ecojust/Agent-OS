#!/usr/bin/env python3
"""Compile actual helper against controlled host stubs; no Android/device claim."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--json-jar", type=Path, required=True)
parser.add_argument("--java-home", type=Path, required=True)
args = parser.parse_args()
jar_sha = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
if hashlib.sha256(args.json_jar.read_bytes()).hexdigest() != jar_sha:
    raise SystemExit("Expected org.json:json:20240303 jar with pinned SHA256")
folder = Path(__file__).resolve().parent
repo = folder.parents[4]
helper = repo / "compat-smoke/src/androidTest/java/dev/agentos/compat/NotesImeAssociationDiagnostic.java"
with tempfile.TemporaryDirectory(prefix="notes-ime-host-") as temporary:
    subprocess.run([str(args.java_home / "bin/javac"), "-cp", str(args.json_jar), "-d", temporary,
                    str(helper), *map(str, sorted(folder.rglob("*.java")))], check=True)
    subprocess.run([str(args.java_home / "bin/java"), "-cp", temporary + ":" + str(args.json_jar),
                    "dev.agentos.compat.CollectorHostTest"], check=True, timeout=15)
print("helper_sha256=" + hashlib.sha256(helper.read_bytes()).hexdigest())
