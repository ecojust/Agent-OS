# Control Status Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40618768617

Source commit: `c73bce43c0427ead174f68042c5482ef1e8afb64` · Attempt: 1

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

![API 30 test screenshot](api-30/screenshots/api-30-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-1dbc5119-failed-3.png)

![API 30 test screenshot](api-30/screenshots/api-30-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-1dbc5119-owned-control-accessibility-failed-before-cleanup-2.png)

[All files and capture outcomes](api-30/screenshots/manifest.json)

## API 31

![API 31 test screenshot](api-31/screenshots/api-31-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-d0b8d60e-finished-3.png)

![API 31 test screenshot](api-31/screenshots/api-31-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-d0b8d60e-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-31/screenshots/manifest.json)

## API 32

![API 32 test screenshot](api-32/screenshots/api-32-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-83bd75a2-finished-3.png)

![API 32 test screenshot](api-32/screenshots/api-32-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-83bd75a2-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-32/screenshots/manifest.json)

## API 33

![API 33 test screenshot](api-33/screenshots/api-33-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-432d8189-finished-3.png)

![API 33 test screenshot](api-33/screenshots/api-33-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-432d8189-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-33/screenshots/manifest.json)

## API 34

![API 34 test screenshot](api-34/screenshots/api-34-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-bc17c217-finished-3.png)

![API 34 test screenshot](api-34/screenshots/api-34-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-bc17c217-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-34/screenshots/manifest.json)

## API 35

![API 35 test screenshot](api-35/screenshots/api-35-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-361c6882-finished-3.png)

![API 35 test screenshot](api-35/screenshots/api-35-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-361c6882-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-35/screenshots/manifest.json)

## API 36

![API 36 test screenshot](api-36/screenshots/api-36-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-25d87f5f-finished-3.png)

![API 36 test screenshot](api-36/screenshots/api-36-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-25d87f5f-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-36/screenshots/manifest.json)

## API 37.0

![API 37.0 test screenshot](api-37.0/screenshots/api-37-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-6d4d4ead-finished-3.png)

![API 37.0 test screenshot](api-37.0/screenshots/api-37-ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow-6d4d4ead-owned-control-status-exposes-current-paused-state-2.png)

[All files and capture outcomes](api-37.0/screenshots/manifest.json)
