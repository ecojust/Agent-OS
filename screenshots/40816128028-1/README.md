# Rotation Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40816128028

Source commit: `a6112fe73a094735168a4e751ecb790ffb3ce7f7` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

This case exercises real portrait-to-landscape rotation during pending approval on all eight API lanes. It checks stale approval rejection and reachable task controls; it does not establish all-orientation, multiwindow, physical-device or performance acceptance. Protected or missing captures remain explicit; case results are separate from PNG counts.

Execution scope: `rotation-diagnostic`. Only `dev.agentos.app.AgentControllerInstrumentedTest#realRotationRejectsOldApprovalAndKeepsControlsReachable` was selected.

The diagnostic denominator is 8 cases (8 APIs × 1 test); missing, skipped and unexecuted cases remain unverified. The rest of the normal suite was not run by this scope and is not counted as passed. A complete default matrix is still required for full regression acceptance.

| API | Diagnostic case result |
| --- | --- |
| 30 | failed |
| 31 | failed |
| 32 | failed |
| 33 | failed |
| 34 | failed |
| 35 | failed |
| 36 | failed |
| 37.0 | failed |

| API | Job result | Screenshots | Recorded tests | Tests without screenshots |
| --- | --- | --- | --- | --- |
| 30 | failure | 3 | 1 | 0 |
| 31 | failure | 4 | 1 | 0 |
| 32 | failure | 4 | 1 | 0 |
| 33 | failure | 4 | 1 | 0 |
| 34 | failure | 4 | 1 | 0 |
| 35 | failure | 4 | 1 | 0 |
| 36 | failure | 4 | 1 | 0 |
| 37.0 | failure | 4 | 1 | 0 |

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

![API 30 test screenshot](api-30/screenshots/api-30-realRotationRejectsOldApprovalAndKeepsControlsReachable-bfe57409-failed-2.png)

![API 30 test screenshot](api-30/screenshots/api-30-realRotationRejectsOldApprovalAndKeepsControlsReachable-bfe57409-portrait-pending-approval-before-real-rotation-0.png)

[All files and capture outcomes](api-30/screenshots/manifest.json)

## API 31

![API 31 test screenshot](api-31/screenshots/api-31-realRotationRejectsOldApprovalAndKeepsControlsReachable-7f6ae35b-failed-3.png)

![API 31 test screenshot](api-31/screenshots/api-31-realRotationRejectsOldApprovalAndKeepsControlsReachable-7f6ae35b-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-31/screenshots/manifest.json)

## API 32

![API 32 test screenshot](api-32/screenshots/api-32-realRotationRejectsOldApprovalAndKeepsControlsReachable-64b73b23-failed-3.png)

![API 32 test screenshot](api-32/screenshots/api-32-realRotationRejectsOldApprovalAndKeepsControlsReachable-64b73b23-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-32/screenshots/manifest.json)

## API 33

![API 33 test screenshot](api-33/screenshots/api-33-realRotationRejectsOldApprovalAndKeepsControlsReachable-31edc6b8-failed-3.png)

![API 33 test screenshot](api-33/screenshots/api-33-realRotationRejectsOldApprovalAndKeepsControlsReachable-31edc6b8-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-33/screenshots/manifest.json)

## API 34

![API 34 test screenshot](api-34/screenshots/api-34-realRotationRejectsOldApprovalAndKeepsControlsReachable-0d4ec2a1-failed-3.png)

![API 34 test screenshot](api-34/screenshots/api-34-realRotationRejectsOldApprovalAndKeepsControlsReachable-0d4ec2a1-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-34/screenshots/manifest.json)

## API 35

![API 35 test screenshot](api-35/screenshots/api-35-realRotationRejectsOldApprovalAndKeepsControlsReachable-e340b098-failed-3.png)

![API 35 test screenshot](api-35/screenshots/api-35-realRotationRejectsOldApprovalAndKeepsControlsReachable-e340b098-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-35/screenshots/manifest.json)

## API 36

![API 36 test screenshot](api-36/screenshots/api-36-realRotationRejectsOldApprovalAndKeepsControlsReachable-c9ba1395-failed-3.png)

![API 36 test screenshot](api-36/screenshots/api-36-realRotationRejectsOldApprovalAndKeepsControlsReachable-c9ba1395-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-36/screenshots/manifest.json)

## API 37.0

![API 37.0 test screenshot](api-37.0/screenshots/api-37-realRotationRejectsOldApprovalAndKeepsControlsReachable-6f147ec4-failed-3.png)

![API 37.0 test screenshot](api-37.0/screenshots/api-37-realRotationRejectsOldApprovalAndKeepsControlsReachable-6f147ec4-landscape-current-controls-old-view-listeners-inert-1.png)

[All files and capture outcomes](api-37.0/screenshots/manifest.json)
