# Attrs rejection reasons: host validation

This runner compiles the actual test-only helper against controlled host substitutes.
It runs the existing 25 collector checks and helper parser selftests, then compares
3,235 attribute inputs with the exact `windowType` implementation frozen at
`9b86b2a842f9e837e20e2df183893da697faaf40`, and checks 129 metadata properties.
The oracle changes only method visibility; its original helper SHA256 is
`273104d38ca9ec7e4d0fbca2aecdc00c3e24467dc66f6029776ccd861bd172e9`.
The source method hash before that visibility edit is
`5afef7af6d1257a413187890241380ef5834ee38302639d0b2efe68224d1333f`.

The checks cover nested and malformed attributes, duplicate anchors and types,
size/depth/type-lookahead boundaries, all fixed rejection reasons, coarse lexical
classes without unknown type text, known WMS tail headings, header relations,
ordered retention of at most eight failed records, omitted counts, and original
caller/error/output limits. Tests use synthetic records, including malformed
records. They do not establish what the earlier API36 dump contained.

Requires Java 17 and `org.json:json:20240303` from
https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar .
The runner verifies SHA256
`3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`.
The dependency binary is neither committed nor downloaded by the test.

```sh
python3 docs/validation/diagnostics/notes-ime-attrs-reasons-2026-10-31/java-host-tests/run.py --java-home /path/to/jdk17 --json-jar /path/to/json-20240303.jar
```

Android classes are controlled host substitutes; these tests do not test Android
Binder cancellation, system service permissions, actual device formats, or APK
compatibility. The blocked-call test allows host scheduling overhead up to 6000 ms
for the unchanged 3000 ms caller wait. It does not claim the remote command ended.

Metadata offsets and lengths are Java UTF-16 code units. Attrs offsets point to
the first and second recognized opening braces. `scanChars` is the outer-loop
cursor extent, at most 8192; the unchanged original type-value lookahead may read
up to 65 code units past its `ty=` token and may cross that outer cursor boundary.
`topLevelTypeCount` counts encountered top-level `ty=` fields, including an invalid
candidate; the old accepted-field counter used for selection remains separate.
When `typeValueComplete` is false, the lexical class describes only the observed
bounded prefix. It cannot reconstruct or classify the unknown full value.

Known tail metadata matches only four fixed whole-line heading texts, confirmed in
AOSP source. `firstKnownTailSection` reports the earliest match; the separate boolean
reports whether any such match lies between the first two attrs opening braces.
The header relation is a comparison of header/reference text, not IME identity.
Known headings do not relax refusal or change Window-number partitioning.
