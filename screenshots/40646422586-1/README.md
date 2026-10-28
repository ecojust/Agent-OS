# Activity Font Scale Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40646422586

Source commit: `14c621aac92a4412a29b08e35ddc34f23d666821` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

This case exercises real system font-scale changes on all eight API lanes. Protected-window captures may be blocked; JSON case results do not require a PNG.

Execution scope: `activity-font-scale-diagnostic`. Only `dev.agentos.app.MainActivityFontScaleInstrumentedTest#realSystemFontScaleRecreatesSecureActivityAndRetainsOnlyInMemoryDraft` was selected.

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
| 30 | success | 0 | 1 | 1 |
| 31 | success | 0 | 1 | 1 |
| 32 | success | 0 | 1 | 1 |
| 33 | success | 0 | 1 | 1 |
| 34 | success | 0 | 1 | 1 |
| 35 | success | 0 | 1 | 1 |
| 36 | success | 0 | 1 | 1 |
| 37.0 | success | 0 | 1 | 1 |

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
