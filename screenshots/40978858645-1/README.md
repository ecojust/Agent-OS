# Navigation Origin Rebuild Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40978858645

Source commit: `b3e2cacfcc48c5819b0c25f9d8900b6d723c9f44` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

This case preserves a real unscoped Settings observation across actual control-window removal and recreation before one automatic launch attempt on all eight API lanes. It requires TASK_PAUSED, zero approval callbacks and dispatch attempts, and retained Settings focus. STALE_OBSERVATION alone does not satisfy the required source interruption. Protected or missing captures remain explicit; case results do not require a PNG. This controlled regression is distinct from the original navigation-origin diagnostic; a standalone pass does not resolve a historical full-suite failure.

Execution scope: `navigation-origin-rebuild-diagnostic`. Only `dev.agentos.app.NavigationObservationGuardInstrumentedTest#aKnownUnscopedSettingsOriginStillPausesAfterControlWindowRecreation` was selected.

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
