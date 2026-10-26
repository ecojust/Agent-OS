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

The continuous integration matrix targets Android API 30 and API 35. Test source or a successful APK build alone is not a passing emulator result; inspect the uploaded instrumentation reports for each run.
