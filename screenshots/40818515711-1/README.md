# Rotation Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40818515711

Source commit: `43381e9e9c67f1c6eb7c5fbf4ced0ed5e03fa9da` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

This case exercises real portrait-to-landscape rotation during pending approval on all eight API lanes. It checks stale approval rejection and reachable task controls; it does not establish all-orientation, multiwindow, physical-device or performance acceptance. Protected or missing captures remain explicit; case results are separate from PNG counts.

Execution scope: `rotation-diagnostic`. Only `dev.agentos.app.AgentControllerInstrumentedTest#realRotationRejectsOldApprovalAndKeepsControlsReachable` was selected.

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
| 30 | success | 5 | 1 | 0 |
| 31 | success | 5 | 1 | 0 |
| 32 | success | 5 | 1 | 0 |
| 33 | success | 5 | 1 | 0 |
| 34 | success | 5 | 1 | 0 |
| 35 | success | 5 | 1 | 0 |
| 36 | success | 5 | 1 | 0 |
| 37.0 | success | 5 | 1 | 0 |

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

![API 30 test screenshot](api-30/screenshots/api-30-realRotationRejectsOldApprovalAndKeepsControlsReachable-d62a8a65-finished-4.png)

![API 30 test screenshot](api-30/screenshots/api-30-realRotationRejectsOldApprovalAndKeepsControlsReachable-d62a8a65-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-30/screenshots/manifest.json)

## API 31

![API 31 test screenshot](api-31/screenshots/api-31-realRotationRejectsOldApprovalAndKeepsControlsReachable-d2f17afb-finished-4.png)

![API 31 test screenshot](api-31/screenshots/api-31-realRotationRejectsOldApprovalAndKeepsControlsReachable-d2f17afb-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-31/screenshots/manifest.json)

## API 32

![API 32 test screenshot](api-32/screenshots/api-32-realRotationRejectsOldApprovalAndKeepsControlsReachable-3fbac329-finished-4.png)

![API 32 test screenshot](api-32/screenshots/api-32-realRotationRejectsOldApprovalAndKeepsControlsReachable-3fbac329-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-32/screenshots/manifest.json)

## API 33

![API 33 test screenshot](api-33/screenshots/api-33-realRotationRejectsOldApprovalAndKeepsControlsReachable-38c3622e-finished-4.png)

![API 33 test screenshot](api-33/screenshots/api-33-realRotationRejectsOldApprovalAndKeepsControlsReachable-38c3622e-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-33/screenshots/manifest.json)

## API 34

![API 34 test screenshot](api-34/screenshots/api-34-realRotationRejectsOldApprovalAndKeepsControlsReachable-2a356262-finished-4.png)

![API 34 test screenshot](api-34/screenshots/api-34-realRotationRejectsOldApprovalAndKeepsControlsReachable-2a356262-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-34/screenshots/manifest.json)

## API 35

![API 35 test screenshot](api-35/screenshots/api-35-realRotationRejectsOldApprovalAndKeepsControlsReachable-93fe6353-finished-4.png)

![API 35 test screenshot](api-35/screenshots/api-35-realRotationRejectsOldApprovalAndKeepsControlsReachable-93fe6353-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-35/screenshots/manifest.json)

## API 36

![API 36 test screenshot](api-36/screenshots/api-36-realRotationRejectsOldApprovalAndKeepsControlsReachable-c982ca47-finished-4.png)

![API 36 test screenshot](api-36/screenshots/api-36-realRotationRejectsOldApprovalAndKeepsControlsReachable-c982ca47-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-36/screenshots/manifest.json)

## API 37.0

![API 37.0 test screenshot](api-37.0/screenshots/api-37-realRotationRejectsOldApprovalAndKeepsControlsReachable-30f97ce9-finished-4.png)

![API 37.0 test screenshot](api-37.0/screenshots/api-37-realRotationRejectsOldApprovalAndKeepsControlsReachable-30f97ce9-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-37.0/screenshots/manifest.json)
