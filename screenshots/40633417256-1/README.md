# Android Test Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40633417256

Source commit: `3cff90d62fe181fdee9c83b6348ea4becd6ac121` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

Source branch: `agent/system-font-scale-regression`. Development branch evidence awaiting review; not mainline acceptance.

| API | Job result | Screenshots | Recorded tests | Tests without screenshots |
| --- | --- | --- | --- | --- |
| 30 | success | 271 | 246 | 111 |
| 31 | success | 270 | 246 | 112 |
| 32 | success | 270 | 246 | 112 |
| 33 | success | 282 | 246 | 110 |
| 34 | success | 285 | 246 | 109 |
| 35 | success | 285 | 246 | 109 |
| 36 | failure | 284 | 246 | 109 |
| 37.0 | success | 284 | 246 | 109 |

[Full evidence manifest](manifest.json)

## Performance Diagnostics

Numeric diagnostic attachments are separate from screenshots and test counts. They do not establish performance-budget acceptance.

| API | Collection | Diagnostic result | Raw report |
| --- | --- | --- | --- |
| 30 | collected | passed | [JSON](performance/api-30/three-state-diagnostics.json) |
| 31 | collected | passed | [JSON](performance/api-31/three-state-diagnostics.json) |
| 32 | collected | passed | [JSON](performance/api-32/three-state-diagnostics.json) |
| 33 | collected | passed | [JSON](performance/api-33/three-state-diagnostics.json) |
| 34 | collected | passed | [JSON](performance/api-34/three-state-diagnostics.json) |
| 35 | collected | passed | [JSON](performance/api-35/three-state-diagnostics.json) |
| 36 | collected | passed | [JSON](performance/api-36/three-state-diagnostics.json) |
| 37.0 | collected | passed | [JSON](performance/api-37.0/three-state-diagnostics.json) |

## API 30

![API 30 test screenshot](api-30/screenshots/api-30-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-02f52588-finished-1.png)

![API 30 test screenshot](api-30/screenshots/api-30-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-02f52588-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-30/screenshots/manifest.json)

## API 31

![API 31 test screenshot](api-31/screenshots/api-31-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-3ed634cf-finished-1.png)

![API 31 test screenshot](api-31/screenshots/api-31-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-3ed634cf-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-31/screenshots/manifest.json)

## API 32

![API 32 test screenshot](api-32/screenshots/api-32-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-f5fe3667-finished-1.png)

![API 32 test screenshot](api-32/screenshots/api-32-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-f5fe3667-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-32/screenshots/manifest.json)

## API 33

![API 33 test screenshot](api-33/screenshots/api-33-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-ab3e76c6-finished-1.png)

![API 33 test screenshot](api-33/screenshots/api-33-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-ab3e76c6-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-33/screenshots/manifest.json)

## API 34

![API 34 test screenshot](api-34/screenshots/api-34-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-9a0f709b-finished-1.png)

![API 34 test screenshot](api-34/screenshots/api-34-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-9a0f709b-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-34/screenshots/manifest.json)

## API 35

![API 35 test screenshot](api-35/screenshots/api-35-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-e1f74fb2-finished-1.png)

![API 35 test screenshot](api-35/screenshots/api-35-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-e1f74fb2-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-35/screenshots/manifest.json)

## API 36

![API 36 test screenshot](api-36/screenshots/api-36-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-093c6f30-finished-1.png)

![API 36 test screenshot](api-36/screenshots/api-36-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-093c6f30-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-36/screenshots/manifest.json)

## API 37.0

![API 37.0 test screenshot](api-37.0/screenshots/api-37-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-879cc481-finished-1.png)

![API 37.0 test screenshot](api-37.0/screenshots/api-37-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-879cc481-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-37.0/screenshots/manifest.json)
