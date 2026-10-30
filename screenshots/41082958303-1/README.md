# App Experience Diagnostic Evidence

Source run: https://github.com/ecojust/Agent-OS/actions/runs/41082958303

Source commit: `f45fbb152ebf1b3c1335bb28744e81d8837dc50c` · Attempt: 1

These screenshots show synthetic test scenarios. Missing screenshots and failed tests remain visible in the manifest.

Target Fixture and resident Agent CPU/PSS are measured separately. Startup uses matched Perfetto android_startups time_to_display observations; am start output and frame callbacks do not substitute for TTID. Raw launch, resource and frame reports, bounded compressed traces, failed and unavailable results are retained. Manual accessibility actions and local test-model actions are synthetic; endpoint PSS is not steady-state or peak memory, and received FrameMetrics do not prove presentation. These diagnostics do not establish causal overhead, real-model, physical-device or performance-budget acceptance. Pilot and formal results are separate profiles.

Execution scope: `app-experience-diagnostic`. Only `dev.agentos.app.AppExperienceInstrumentedTest#collectTargetResourcesStartupAndFrames` was selected.

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
| 30 | success | 3 | 1 | 0 |
| 31 | success | 2 | 1 | 0 |
| 32 | success | 2 | 1 | 0 |
| 33 | success | 2 | 1 | 0 |
| 34 | success | 3 | 1 | 0 |
| 35 | success | 2 | 1 | 0 |
| 36 | success | 3 | 1 | 0 |
| 37.0 | success | 3 | 1 | 0 |

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

## App Experience Diagnostics

Profile: `formal`. Pilot data does not count toward the formal denominator. The original instrumented report status is separate from the complete evidence status, which also requires valid TTID and trace evidence. Missing, failed and incomplete data remain in the planned eight-API denominator. Only committed launch and trial prefixes are known; later entries are unavailable, not asserted unexecuted.

| API | Collection | Evidence result | Instrumented report | Raw report |
| --- | --- | --- | --- | --- |
| 30 | collected | passed | passed | [JSON](app-experience/api-30/experience-diagnostics.json) |
| 31 | collected | passed | passed | [JSON](app-experience/api-31/experience-diagnostics.json) |
| 32 | collected | passed | passed | [JSON](app-experience/api-32/experience-diagnostics.json) |
| 33 | collected | passed | passed | [JSON](app-experience/api-33/experience-diagnostics.json) |
| 34 | collected | passed | passed | [JSON](app-experience/api-34/experience-diagnostics.json) |
| 35 | collected | passed | passed | [JSON](app-experience/api-35/experience-diagnostics.json) |
| 36 | collected | passed | passed | [JSON](app-experience/api-36/experience-diagnostics.json) |
| 37.0 | collected | passed | passed | [JSON](app-experience/api-37.0/experience-diagnostics.json) |

## API 30

![API 30 test screenshot](api-30/screenshots/api-30-collectTargetResourcesStartupAndFrames-61dfcf6b-app-experience-sequence-after-1.png)

![API 30 test screenshot](api-30/screenshots/api-30-collectTargetResourcesStartupAndFrames-61dfcf6b-app-experience-sequence-before-0.png)

[All files and capture outcomes](api-30/screenshots/manifest.json)

## API 31

![API 31 test screenshot](api-31/screenshots/api-31-collectTargetResourcesStartupAndFrames-e9d2b736-app-experience-sequence-after-1.png)

![API 31 test screenshot](api-31/screenshots/api-31-collectTargetResourcesStartupAndFrames-e9d2b736-finished-2.png)

[All files and capture outcomes](api-31/screenshots/manifest.json)

## API 32

![API 32 test screenshot](api-32/screenshots/api-32-collectTargetResourcesStartupAndFrames-e3045563-app-experience-sequence-after-1.png)

![API 32 test screenshot](api-32/screenshots/api-32-collectTargetResourcesStartupAndFrames-e3045563-finished-2.png)

[All files and capture outcomes](api-32/screenshots/manifest.json)

## API 33

![API 33 test screenshot](api-33/screenshots/api-33-collectTargetResourcesStartupAndFrames-139b900a-app-experience-sequence-after-1.png)

![API 33 test screenshot](api-33/screenshots/api-33-collectTargetResourcesStartupAndFrames-139b900a-finished-2.png)

[All files and capture outcomes](api-33/screenshots/manifest.json)

## API 34

![API 34 test screenshot](api-34/screenshots/api-34-collectTargetResourcesStartupAndFrames-54715314-app-experience-sequence-after-1.png)

![API 34 test screenshot](api-34/screenshots/api-34-collectTargetResourcesStartupAndFrames-54715314-app-experience-sequence-before-0.png)

[All files and capture outcomes](api-34/screenshots/manifest.json)

## API 35

![API 35 test screenshot](api-35/screenshots/api-35-collectTargetResourcesStartupAndFrames-11a3c6fd-app-experience-sequence-after-1.png)

![API 35 test screenshot](api-35/screenshots/api-35-collectTargetResourcesStartupAndFrames-11a3c6fd-finished-2.png)

[All files and capture outcomes](api-35/screenshots/manifest.json)

## API 36

![API 36 test screenshot](api-36/screenshots/api-36-collectTargetResourcesStartupAndFrames-72ea1bd7-app-experience-sequence-after-1.png)

![API 36 test screenshot](api-36/screenshots/api-36-collectTargetResourcesStartupAndFrames-72ea1bd7-app-experience-sequence-before-0.png)

[All files and capture outcomes](api-36/screenshots/manifest.json)

## API 37.0

![API 37.0 test screenshot](api-37.0/screenshots/api-37-collectTargetResourcesStartupAndFrames-bdc40205-app-experience-sequence-after-1.png)

![API 37.0 test screenshot](api-37.0/screenshots/api-37-collectTargetResourcesStartupAndFrames-bdc40205-app-experience-sequence-before-0.png)

[All files and capture outcomes](api-37.0/screenshots/manifest.json)
