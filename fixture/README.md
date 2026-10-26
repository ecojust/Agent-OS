# Android execution fixture

This separate, offline application supplies deterministic UI state for Agent OS integration tests. It has no network or account permissions and is not included in the Agent OS application package.

The fixture contains editable text with an observable saved result, a click counter, a scrollable list, a second activity, and a password field containing only synthetic test data. An explicitly armed, one-shot slow accessibility node simulates remote observation latency for responsiveness and cancellation checks.

On an unlocked test emulator with no screen-lock credential:

```sh
./gradlew :fixture:installDebug :app:connectedDebugAndroidTest
```

The instrumentation suite temporarily adopts the test device's shell permission identity to configure the normal Agent OS accessibility service through the settings provider. It checks the stored component identity, leaves the user-visible task controls present, and restores the previous accessibility settings after each device-tool test. Run it only on a dedicated development emulator or test device.

Checks include actual text entry and saved state, stale-target rejection, application scope, password redaction, unavailable screenshot reporting, cancellation, service revocation, encrypted configuration persistence and recovery boundaries, and launcher startup. An intentionally edge-to-edge list exercises scrolling with normal system bars; back and home must also remain available. A separately titled test overlay must still obscure the target, pause execution, and leave the dispatch counter unchanged. Ordinary fixture controls use system-bar and keyboard insets so their semantic bounds match the visible content.

The slow-node check verifies the Agent control thread can receive a heartbeat while a remote observation is in flight and that pausing discards the old result; its generous timing limit is a regression guard, not proof of the full performance budget. Model-network behavior is covered separately by the core JVM tests. Passing this fixture does not demonstrate compatibility with third-party applications or physical phones.

The continuous integration matrix targets Android API 30 through API 36 and the SDK's API 37.0 image (runtime API 37). Test source or a successful APK build alone is not a passing emulator result; inspect the uploaded instrumentation reports for each run.

Each CI emulator uses the named `agent-os-phone` AVD with `hw.keyboard=no`, configured after AVD creation and before startup. The test command verifies that exact file setting again before running. This requests a touchscreen phone configuration; it does not prove the runtime reports no physical keyboard. In the recorded API 36 and 37 runs, runtime `Configuration` still reported `qwerty/v/v`, so a runtime `NOKEYS` baseline has not been demonstrated. Tests retain the system image's own input method and require actual keyboard-window and visible-inset behavior. They do not establish compatibility with attached hardware keyboards or replace a missing keyboard view with a successful test result.

The API 36/37 keyboard case temporarily rebinds the already selected system input method after enabling software-keyboard display, because those images retained an earlier cached preference. It keeps the same component and input-method data, then restores the original enabled list, selected method, subtype, subtype history and display preference, including on preparation failure. This is dedicated emulator setup; Agent OS does not reconfigure users' keyboards.

The current environment comparison explicitly sets and verifies all three system animation scales (`window_animation_scale`, `transition_animation_scale`, and `animator_duration_scale`) at `1.0` before tests. Earlier runs with animations disabled and their failures remain archived. This comparison investigates window-lifecycle behavior; it is not by itself a fix for stale server-side accessibility windows or a requirement to change animation settings on users' phones.

## Screenshot evidence

Android instrumentation records real screenshots in the tested application's `files/screenshots` directory. Each PNG has the runtime API level, test name, stage and a unique suffix. Per-test JSON files and `manifest.json` associate the images with the actual test result and capture status (`pass`, `fail` or `blocked`). CI retrieves this directory before removing the development APK; reviewed run evidence belongs under the repository's `screenshots/` directory.

Only the separate synthetic fixture and a verified empty application screen are eligible. The product's secure-window flag remains enabled in production. A test may temporarily clear it only after confirming that saved configuration and visible input fields are empty and no Agent task is running; it restores the flag in `finally`. Blank or protected surfaces and unrelated foreground applications are recorded as blocked instead of being presented as visible UI evidence. Storage and JVM checks have no UI screenshot to claim. Screenshot or manifest write failures remain explicit test failures.
