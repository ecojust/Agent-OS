# Android Test Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40442767222

Source commit: `95681c2d81a526bf94eac0653ad598f826683645` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

| API | Job result | Screenshots | Recorded tests | Tests without screenshots |
| --- | --- | --- | --- | --- |
| 30 | cancelled | 0 | 0 | 0 |
| 31 | success | 104 | 114 | 56 |
| 32 | success | 104 | 114 | 56 |
| 33 | success | 113 | 114 | 54 |
| 34 | success | 115 | 114 | 53 |
| 35 | success | 116 | 114 | 53 |
| 36 | failure | 115 | 114 | 53 |
| 37.0 | success | 115 | 114 | 53 |

[Full evidence manifest](manifest.json)

## Performance Diagnostics

Numeric diagnostic attachments are separate from screenshots and test counts. They do not establish performance-budget acceptance.

| API | Collection | Diagnostic result | Raw report |
| --- | --- | --- | --- |
| 30 | not_collected | None | emulator_script_did_not_finish |
| 31 | collected | passed | [JSON](performance/api-31/three-state-diagnostics.json) |
| 32 | collected | passed | [JSON](performance/api-32/three-state-diagnostics.json) |
| 33 | collected | passed | [JSON](performance/api-33/three-state-diagnostics.json) |
| 34 | collected | passed | [JSON](performance/api-34/three-state-diagnostics.json) |
| 35 | collected | passed | [JSON](performance/api-35/three-state-diagnostics.json) |
| 36 | collected | passed | [JSON](performance/api-36/three-state-diagnostics.json) |
| 37.0 | collected | passed | [JSON](performance/api-37.0/three-state-diagnostics.json) |

## API 31

![API 31 test screenshot](api-31/screenshots/api-31-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-88fb0499-finished-1.png)

![API 31 test screenshot](api-31/screenshots/api-31-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-88fb0499-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-31/screenshots/manifest.json)

## API 32

![API 32 test screenshot](api-32/screenshots/api-32-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-ba7e632d-finished-1.png)

![API 32 test screenshot](api-32/screenshots/api-32-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-ba7e632d-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-32/screenshots/manifest.json)

## API 33

![API 33 test screenshot](api-33/screenshots/api-33-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-742c7cdf-finished-1.png)

![API 33 test screenshot](api-33/screenshots/api-33-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-742c7cdf-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-33/screenshots/manifest.json)

## API 34

![API 34 test screenshot](api-34/screenshots/api-34-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-7106a76e-finished-1.png)

![API 34 test screenshot](api-34/screenshots/api-34-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-7106a76e-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-34/screenshots/manifest.json)

## API 35

![API 35 test screenshot](api-35/screenshots/api-35-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-1f2b9c6f-finished-1.png)

![API 35 test screenshot](api-35/screenshots/api-35-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-1f2b9c6f-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-35/screenshots/manifest.json)

## API 36

![API 36 test screenshot](api-36/screenshots/api-36-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-822b86ac-finished-1.png)

![API 36 test screenshot](api-36/screenshots/api-36-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-822b86ac-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-36/screenshots/manifest.json)

## API 37.0

![API 37.0 test screenshot](api-37.0/screenshots/api-37-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-db12db8f-finished-1.png)

![API 37.0 test screenshot](api-37.0/screenshots/api-37-aChangedForegroundWindowRejectsCoordinatesBoundToTheOldRevision-db12db8f-old-window-coordinate-plan-rejected-0.png)

[All files and capture outcomes](api-37.0/screenshots/manifest.json)
