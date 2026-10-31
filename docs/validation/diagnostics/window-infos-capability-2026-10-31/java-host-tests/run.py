#!/usr/bin/env python3
"""Compile actual probe and entry against controlled host Android/JUnit/API substitutes."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

parser=argparse.ArgumentParser()
parser.add_argument('--json-jar',type=Path,required=True)
parser.add_argument('--java-home',type=Path,required=True)
parser.add_argument('--cross-output',type=Path,required=True)
args=parser.parse_args()
if hashlib.sha256(args.json_jar.read_bytes()).hexdigest()!='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed':
    raise SystemExit('Expected pinned org.json:json:20240303 jar')
folder=Path(__file__).resolve().parent
repo=folder.parents[4]
java=repo/'compat-smoke/src/androidTest/java/dev/agentos/compat'
probes=[java/'WindowInfosCapabilityProbe.java',java/'WindowInfosCapabilityInstrumentedTest.java']
args.cross_output.parent.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(prefix='window-infos-capability-host-') as temporary:
    temporary=Path(temporary);common=temporary/'common';fixtures=temporary/'fixtures'
    subprocess.run([str(args.java_home/'bin/javac'),'-cp',str(args.json_jar),'-d',str(common),
                    *map(str,probes),*map(str,sorted((folder/'common').rglob('*.java')))],check=True)
    for variant in sorted((folder/'fixtures').iterdir()):
        subprocess.run([str(args.java_home/'bin/javac'),'-cp',str(common),'-d',str(fixtures/variant.name),
                        *map(str,sorted(variant.rglob('*.java')))],check=True)
    subprocess.run([str(args.java_home/'bin/java'),'-cp',str(common)+':'+str(args.json_jar),
                    'dev.agentos.compat.CapabilityHostTest',str(fixtures),str(args.cross_output)],check=True,timeout=20)
for path in probes:
    print(path.name+'_sha256='+hashlib.sha256(path.read_bytes()).hexdigest())
