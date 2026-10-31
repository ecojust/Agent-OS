# Complete captured attrs: host validation

This runner compiles the actual test-only helper against controlled host substitutes.
It retains the existing 25 collector checks and helper parser selftests, then runs
2,607 comparisons with the exact previous `35519e9fb` attrs scanner. The 1,789 cases
outside its former `ATTRS_SIZE_LIMIT` domain retain the same type and reason; all
818 changed cases belong to that explicitly changed size-limit domain. Separate
83 boundary checks cover complete and refused inputs, identities and collection limits.

The frozen oracle changes only method visibility and projects its immutable result
to the original type and reason; ignored metadata cannot affect those decisions.
The prior full helper SHA256 is
`c920dce631dbd4e81397282dbf598c0b754cbcb7934eb32c98be90172f9ca90d`;
the original scanner method hash before its visibility edit is
`8cce091ea7488d04bcb4d1b9940e2f6f66d5576721b8fbaab6b2eee42f98ab55`.

The correction scans for complete balanced attributes within the existing captured
input bound. It does not accept on the first type field. Nested types remain separate
from top-level types; depth over eight, duplicate anchors, duplicate or invalid
top-level types, missing closure, unreadable other records and mismatched configured
identities still refuse selection. Tests place these conditions after the old 8192
cursor boundary and at the full input limit.

`attrsDiagnostics.schemaVersion=2` declares `scanLimitChars=262144` and
`inputLimitChars=262144`. These are UTF-16 code-unit ceilings for already captured
text; the source capture retains its existing 262144-byte ceiling plus one overflow
probe byte, three commands, 3000 ms per-command caller wait and 9000 ms total wait.
Direct oversized parser input returns `WMS_INPUT_LIMIT` before regex/split work.
A complete-looking valid prefix of a truncated raw capture still returns `BYTE_LIMIT`
and is never supplied to the parser. All historical v1 receipts retain their original
8192 scan limit and outcomes.

Requires Java 17 and `org.json:json:20240303` from
https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar .
The runner verifies SHA256
`3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`.
The dependency binary is neither committed nor downloaded by this test.

```sh
python3 docs/validation/diagnostics/notes-ime-attrs-scan-2026-10-31/java-host-tests/run.py --java-home /path/to/jdk17 --json-jar /path/to/json-20240303.jar
```

These are synthetic host tests. They do not establish actual Android dump closure
positions, device interface behavior, or application acceptance. The earlier API36
failure evidence remains unchanged. The controlled blocked-call timing assertion
allows host scheduling overhead up to 6000 ms for the unchanged 3000 ms caller wait;
it does not claim the remote command ended.
