# Visual Generation Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40636096797

Source commit: `81f0548d3be3f2372f492b9dcdcb865bbbb8cfb6` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

Source branch: `agent/system-font-scale-regression`. Development branch evidence awaiting review; not mainline acceptance.

API30–33 exercise only the unsupported native-source rejection path. API34–37.0 exercise the actual cosmetic-event/local-guard positive path. Eight passing selected cases would not mean that all eight APIs exercised that positive path.

Execution scope: `visual-generation-diagnostic`. Only `dev.agentos.app.VisualEventGenerationInstrumentedTest#actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications` was selected.

The diagnostic denominator is 8 cases (8 APIs × 1 test); missing, skipped and unexecuted cases remain unverified. The rest of the normal suite was not run by this scope and is not counted as passed. A complete default matrix is still required for full regression acceptance.

| API | Diagnostic case result |
| --- | --- |
| 30 | passed |
| 31 | passed |
| 32 | passed |
| 33 | passed |
| 34 | passed |
| 35 | passed |
| 36 | passed |
| 37.0 | passed |

| API | Job result | Screenshots | Recorded tests | Tests without screenshots |
| --- | --- | --- | --- | --- |
| 30 | success | 2 | 1 | 0 |
| 31 | success | 2 | 1 | 0 |
| 32 | success | 2 | 1 | 0 |
| 33 | success | 2 | 1 | 0 |
| 34 | success | 2 | 1 | 0 |
| 35 | success | 2 | 1 | 0 |
| 36 | success | 2 | 1 | 0 |
| 37.0 | success | 2 | 1 | 0 |

[Full evidence manifest](manifest.json)

## Performance Diagnostics

Numeric diagnostic attachments are separate from screenshots and test counts. They do not establish performance-budget acceptance.

| API | Collection | Diagnostic result | Raw report |
| --- | --- | --- | --- |
| 30 | invalid | None | invalid_report |
| 31 | invalid | None | invalid_report |
| 32 | invalid | None | invalid_report |
| 33 | invalid | None | invalid_report |
| 34 | invalid | None | invalid_report |
| 35 | invalid | None | invalid_report |
| 36 | invalid | None | invalid_report |
| 37.0 | invalid | None | invalid_report |

## API 30

![API 30 test screenshot](api-30/screenshots/api-30-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-1e8d385d-finished-1.png)

![API 30 test screenshot](api-30/screenshots/api-30-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-1e8d385d-review-context-unavailable-without-api34-capture-source-0.png)

[All files and capture outcomes](api-30/screenshots/manifest.json)

## API 31

![API 31 test screenshot](api-31/screenshots/api-31-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-ed11c865-finished-1.png)

![API 31 test screenshot](api-31/screenshots/api-31-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-ed11c865-review-context-unavailable-without-api34-capture-source-0.png)

[All files and capture outcomes](api-31/screenshots/manifest.json)

## API 32

![API 32 test screenshot](api-32/screenshots/api-32-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-b787d5e2-finished-1.png)

![API 32 test screenshot](api-32/screenshots/api-32-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-b787d5e2-review-context-unavailable-without-api34-capture-source-0.png)

[All files and capture outcomes](api-32/screenshots/manifest.json)

## API 33

![API 33 test screenshot](api-33/screenshots/api-33-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-5c0ddb12-finished-1.png)

![API 33 test screenshot](api-33/screenshots/api-33-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-5c0ddb12-review-context-unavailable-without-api34-capture-source-0.png)

[All files and capture outcomes](api-33/screenshots/manifest.json)

## API 34

![API 34 test screenshot](api-34/screenshots/api-34-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-87e8dd3b-finished-1.png)

![API 34 test screenshot](api-34/screenshots/api-34-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-87e8dd3b-real-owned-status-events-preserve-local-image-guard-no-native-capture-or-http-0.png)

[All files and capture outcomes](api-34/screenshots/manifest.json)

## API 35

![API 35 test screenshot](api-35/screenshots/api-35-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-db84d01c-finished-1.png)

![API 35 test screenshot](api-35/screenshots/api-35-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-db84d01c-real-owned-status-events-preserve-local-image-guard-no-native-capture-or-http-0.png)

[All files and capture outcomes](api-35/screenshots/manifest.json)

## API 36

![API 36 test screenshot](api-36/screenshots/api-36-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-6edbeadb-finished-1.png)

![API 36 test screenshot](api-36/screenshots/api-36-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-6edbeadb-real-owned-status-events-preserve-local-image-guard-no-native-capture-or-http-0.png)

[All files and capture outcomes](api-36/screenshots/manifest.json)

## API 37.0

![API 37.0 test screenshot](api-37.0/screenshots/api-37-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-73340326-finished-1.png)

![API 37.0 test screenshot](api-37.0/screenshots/api-37-actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications-73340326-real-owned-status-events-preserve-local-image-guard-no-native-capture-or-http-0.png)

[All files and capture outcomes](api-37.0/screenshots/manifest.json)
