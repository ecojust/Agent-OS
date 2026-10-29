# Numeric Import Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40902327883

Source commit: `42db0baf3528ff33dd1beaa4da1cb32dc54c16f4` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

This case exercises the real system file picker, numeric-preference preview and application of only four unsaved numeric fields on all eight API lanes. Protected captures remain blocked without clearing FLAG_SECURE; case results are separate from PNG counts. A standalone pass does not resolve a failure in the full suite.

Execution scope: `numeric-import-diagnostic`. Only `dev.agentos.app.NumericPreferencesInstrumentedTest#realSafImportPreviewsAndAppliesOnlyFourUnsavedNumericFields` was selected.

The diagnostic denominator is 8 cases (8 APIs × 1 test); missing, skipped and unexecuted cases remain unverified. The rest of the normal suite was not run by this scope and is not counted as passed. A complete default matrix is still required for full regression acceptance.

| API | Diagnostic case result |
| --- | --- |
| 30 | passed |
| 31 | passed |
| 32 | failed |
| 33 | passed |
| 34 | passed |
| 35 | passed |
| 36 | passed |
| 37.0 | passed |

| API | Job result | Screenshots | Recorded tests | Tests without screenshots |
| --- | --- | --- | --- | --- |
| 30 | success | 0 | 1 | 1 |
| 31 | success | 0 | 1 | 1 |
| 32 | failure | 0 | 1 | 1 |
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
