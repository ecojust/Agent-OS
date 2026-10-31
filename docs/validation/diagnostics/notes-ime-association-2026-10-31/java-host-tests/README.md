# Post-failure collector host substitutes

These tests compile the actual `NotesImeAssociationDiagnostic.java` against controlled host
substitutes for three Android classes. They exercise the caller deadline, occupied-worker
handling, late descriptor cleanup, output cap and prefix checksum, EOF/error classification,
and preservation of the original throwable, including injected supplemental `Error` failures. The helper's parser selftests also run.
They do not test Android Binder cancellation, system service permissions, real dump formats,
or any device behavior. The controlled blocked call deliberately ignores interruption.

Requires Java 17 and `org.json:json:20240303` from
https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar .
The runner verifies SHA256
`3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`;
the dependency binary is not committed or downloaded by the test.

Run from any directory:

```sh
python3 docs/validation/diagnostics/notes-ime-association-2026-10-31/java-host-tests/run.py --java-home /path/to/jdk17 --json-jar /path/to/json-20240303.jar
```

The timing test has one 3000ms wait and allows host scheduling overhead up to 6000ms;
this tolerance is a host test assertion, not a changed production diagnostic budget.
The actual remote command can outlive the caller; the test checks that no further
commands are submitted while that worker remains occupied.
