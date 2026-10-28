# Control Status Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40618062095

Source commit: `cf5139ceb2d657cc48e4f78a8e766ef7dbd9d5dd` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

Source branch: `agent/control-layout-regression`. Development branch evidence awaiting review; not mainline acceptance.

Execution scope: `control-status-diagnostic`. Only `dev.agentos.app.OwnedControlWindowInstrumentedTest#ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow` was selected.

The diagnostic denominator is 8 cases (8 APIs × 1 test); missing, skipped and unexecuted cases remain unverified. The rest of the normal suite was not run by this scope and is not counted as passed. A complete default matrix is still required for full regression acceptance.

| API | Diagnostic case result |
| --- | --- |
| 30 | failed |
| 31 | passed |
| 32 | passed |
| 33 | passed |
| 34 | passed |
| 35 | passed |
| 36 | passed |
| 37.0 | passed |

| API | Job result | Screenshots | Recorded tests | Tests without screenshots |
| --- | --- | --- | --- | --- |
| 30 | failure | 4 | 1 | 0 |
| 31 | success | 4 | 1 | 0 |
| 32 | success | 4 | 1 | 0 |
| 33 | success | 4 | 1 | 0 |
| 34 | success | 4 | 1 | 0 |
| 35 | success | 4 | 1 | 0 |
| 36 | success | 4 | 1 | 0 |
| 37.0 | success | 4 | 1 | 0 |

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

![API 30 test screenshot](api-30/screenshots/api-30-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-68976d6b-failed-3.png)

![API 30 test screenshot](api-30/screenshots/api-30-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-68976d6b-owned-control-accessibility-failed-before-cleanup-2.png)

[All files and capture outcomes](api-30/screenshots/manifest.json)

## API 31

![API 31 test screenshot](api-31/screenshots/api-31-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-e00f7cb5-finished-3.png)

![API 31 test screenshot](api-31/screenshots/api-31-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-e00f7cb5-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-31/screenshots/manifest.json)

## API 32

![API 32 test screenshot](api-32/screenshots/api-32-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-e88b0f91-finished-3.png)

![API 32 test screenshot](api-32/screenshots/api-32-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-e88b0f91-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-32/screenshots/manifest.json)

## API 33

![API 33 test screenshot](api-33/screenshots/api-33-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-7bbce005-finished-3.png)

![API 33 test screenshot](api-33/screenshots/api-33-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-7bbce005-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-33/screenshots/manifest.json)

## API 34

![API 34 test screenshot](api-34/screenshots/api-34-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-bdc677f2-finished-3.png)

![API 34 test screenshot](api-34/screenshots/api-34-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-bdc677f2-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-34/screenshots/manifest.json)

## API 35

![API 35 test screenshot](api-35/screenshots/api-35-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-a9c99de0-finished-3.png)

![API 35 test screenshot](api-35/screenshots/api-35-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-a9c99de0-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-35/screenshots/manifest.json)

## API 36

![API 36 test screenshot](api-36/screenshots/api-36-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-cf5b5029-finished-3.png)

![API 36 test screenshot](api-36/screenshots/api-36-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-cf5b5029-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-36/screenshots/manifest.json)

## API 37.0

![API 37.0 test screenshot](api-37.0/screenshots/api-37-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-4af20a98-finished-3.png)

![API 37.0 test screenshot](api-37.0/screenshots/api-37-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-4af20a98-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-37.0/screenshots/manifest.json)
