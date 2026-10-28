# Font Scale Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/40630197321

Source commit: `f2ff7ce5f911c66509bfe72819ff2fa089038cfd` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

Source branch: `agent/system-font-scale-regression`. Development branch evidence awaiting review; not mainline acceptance.

Execution scope: `font-scale-diagnostic`. Only `dev.agentos.app.FontScaleControlWindowInstrumentedTest#realSystemFontScaleRebuildsControlsAndRevokesOldWindow` was selected.

The diagnostic denominator is 8 cases (8 APIs × 1 test); missing, skipped and unexecuted cases remain unverified. The rest of the normal suite was not run by this scope and is not counted as passed. A complete default matrix is still required for full regression acceptance.

| API | Diagnostic case result |
| --- | --- |
| 30 | passed |
| 31 | failed |
| 32 | failed |
| 33 | failed |
| 34 | failed |
| 35 | failed |
| 36 | failed |
| 37.0 | failed |

| API | Job result | Screenshots | Recorded tests | Tests without screenshots |
| --- | --- | --- | --- | --- |
| 30 | success | 4 | 1 | 0 |
| 31 | failure | 2 | 1 | 0 |
| 32 | failure | 2 | 1 | 0 |
| 33 | failure | 2 | 1 | 0 |
| 34 | failure | 2 | 1 | 0 |
| 35 | failure | 2 | 1 | 0 |
| 36 | failure | 2 | 1 | 0 |
| 37.0 | failure | 2 | 1 | 0 |

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

![API 30 test screenshot](api-30/screenshots/api-30-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-937003c7-enlarged-current-buttons-reach-synthetic-pause-resume-stop-2.png)

![API 30 test screenshot](api-30/screenshots/api-30-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-937003c7-finished-3.png)

[All files and capture outcomes](api-30/screenshots/manifest.json)

## API 31

![API 31 test screenshot](api-31/screenshots/api-31-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-b97679b0-failed-1.png)

![API 31 test screenshot](api-31/screenshots/api-31-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-b97679b0-font-scale-failed-before-setting-restoration-0.png)

[All files and capture outcomes](api-31/screenshots/manifest.json)

## API 32

![API 32 test screenshot](api-32/screenshots/api-32-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-3e7e4e33-failed-1.png)

![API 32 test screenshot](api-32/screenshots/api-32-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-3e7e4e33-font-scale-failed-before-setting-restoration-0.png)

[All files and capture outcomes](api-32/screenshots/manifest.json)

## API 33

![API 33 test screenshot](api-33/screenshots/api-33-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-0504297b-failed-1.png)

![API 33 test screenshot](api-33/screenshots/api-33-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-0504297b-font-scale-failed-before-setting-restoration-0.png)

[All files and capture outcomes](api-33/screenshots/manifest.json)

## API 34

![API 34 test screenshot](api-34/screenshots/api-34-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-bf66a735-failed-1.png)

![API 34 test screenshot](api-34/screenshots/api-34-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-bf66a735-font-scale-failed-before-setting-restoration-0.png)

[All files and capture outcomes](api-34/screenshots/manifest.json)

## API 35

![API 35 test screenshot](api-35/screenshots/api-35-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-126afef1-failed-1.png)

![API 35 test screenshot](api-35/screenshots/api-35-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-126afef1-font-scale-failed-before-setting-restoration-0.png)

[All files and capture outcomes](api-35/screenshots/manifest.json)

## API 36

![API 36 test screenshot](api-36/screenshots/api-36-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-3bfa4c9a-failed-1.png)

![API 36 test screenshot](api-36/screenshots/api-36-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-3bfa4c9a-font-scale-failed-before-setting-restoration-0.png)

[All files and capture outcomes](api-36/screenshots/manifest.json)

## API 37.0

![API 37.0 test screenshot](api-37.0/screenshots/api-37-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-6de84cd1-failed-1.png)

![API 37.0 test screenshot](api-37.0/screenshots/api-37-realSystemFontScaleRebuildsControlsAndRevokesOldWindow-6de84cd1-font-scale-failed-before-setting-restoration-0.png)

[All files and capture outcomes](api-37.0/screenshots/manifest.json)
