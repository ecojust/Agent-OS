# Task Report Focus Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40625129440

Source commit: `5c71d4a485de8806a4436db02bfc72e43fc68670` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

Source branch: `agent/screen-off-regression`. Development branch evidence awaiting review; not mainline acceptance.

Execution scope: `task-report-focus-diagnostic`. Only `dev.agentos.app.TaskDiagnosticsInstrumentedTest#completedCodeSurvivesButClearReplacementAndLeavingInvalidateOldDialogActions` was selected.

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
