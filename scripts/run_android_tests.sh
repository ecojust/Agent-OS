#!/usr/bin/env bash
# Preserve the test result while collecting evidence before the emulator shuts down.
set -uo pipefail

matrix_label="${1:?Android API level is required}"
test_scope="${2:-full}"
default_timeout_seconds=1200
watchdog_profile=standard
if (( $# > 2 )); then
  echo "Unexpected Android test arguments" >&2
  exit 2
fi
case "$test_scope" in
  full)
    selection=(-Pandroid.testInstrumentationRunnerArguments.notClass=dev.agentos.app.PinnedCalculatorVisualPageInstrumentedTest,dev.agentos.app.DemoWalkthroughInstrumentedTest,dev.agentos.app.PinnedWindowDisclosureInstrumentedTest,dev.agentos.app.IdlePairedPerformanceInstrumentedTest,dev.agentos.app.SegmentedStressInstrumentedTest,dev.agentos.app.ActivePairedPerformanceInstrumentedTest,dev.agentos.app.AppExperienceInstrumentedTest,dev.agentos.app.BusyStopInstrumentedTest)
    ;;
  owned-control-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.OwnedControlWindowInstrumentedTest#changedPresentationAndCallbacksStillRevokeTheActualControlOwner)
    ;;
  task-qualification-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.TaskVisionQualificationInstrumentedTest#optedInTaskUsesItsOwnRealProbeAndResumeNeverRenewsTheOldQualification)
    ;;
  control-status-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.OwnedControlWindowInstrumentedTest#ownedIdentityAndControlLeaseMatchTheActuallyRegisteredWindow)
    ;;
  screen-off-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.AgentControllerInstrumentedTest#actualScreenOffStopsPendingApprovalAndWakeDoesNotResumeIt)
    ;;
  task-report-focus-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.TaskDiagnosticsInstrumentedTest#completedCodeSurvivesButClearReplacementAndLeavingInvalidateOldDialogActions)
    ;;
  font-scale-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.FontScaleControlWindowInstrumentedTest#realSystemFontScaleRebuildsControlsAndRevokesOldWindow)
    ;;
  visual-generation-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.VisualEventGenerationInstrumentedTest#actualSameHeightStatusEventsPreserveTheOriginalLocalGuardAcrossRepeatedPublications)
    ;;
  activity-font-scale-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.MainActivityFontScaleInstrumentedTest#realSystemFontScaleRecreatesSecureActivityAndRetainsOnlyInMemoryDraft)
    ;;
  rotation-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.AgentControllerInstrumentedTest#realRotationRejectsOldApprovalAndKeepsControlsReachable)
    ;;
  numeric-import-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.NumericPreferencesInstrumentedTest#realSafImportPreviewsAndAppliesOnlyFourUnsavedNumericFields)
    ;;
  navigation-origin-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.NavigationObservationGuardInstrumentedTest#anUnscopedSettingsWindowCannotBeReplacedByAnAutomaticLaunch)
    ;;
  navigation-origin-rebuild-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.NavigationObservationGuardInstrumentedTest#aKnownUnscopedSettingsOriginStillPausesAfterControlWindowRecreation)
    ;;
  idle-pair-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.IdlePairedPerformanceInstrumentedTest#collectAlternatingServiceOffAndIdlePairs)
    ;;
  stress-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.SegmentedStressInstrumentedTest#collectThirtyMinuteTaskStopAndServiceCycles)
    default_timeout_seconds=2400
    watchdog_profile=segmented-stress
    ;;
  active-pair-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.ActivePairedPerformanceInstrumentedTest#collectAlternatingEquivalentManualAndAgentTasks)
    ;;
  app-experience-diagnostic|app-experience-pilot)
    experience_profile=formal
    if [[ "$test_scope" == 'app-experience-pilot' ]]; then experience_profile=pilot; fi
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.AppExperienceInstrumentedTest#collectTargetResourcesStartupAndFrames
      "-Pandroid.testInstrumentationRunnerArguments.experienceProfile=$experience_profile")
    if [[ "$experience_profile" == formal ]]; then
      default_timeout_seconds=2400
      watchdog_profile=app-experience
    fi
    ;;
  busy-stop-diagnostic|busy-stop-pilot)
    busy_stop_profile=formal
    if [[ "$test_scope" == 'busy-stop-pilot' ]]; then busy_stop_profile=pilot; fi
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.BusyStopInstrumentedTest#collectBusyStopDiagnostics
      "-Pandroid.testInstrumentationRunnerArguments.busyStopProfile=$busy_stop_profile")
    if [[ "$busy_stop_profile" == formal ]]; then
      default_timeout_seconds=2400
      watchdog_profile=busy-stop
    fi
    ;;
  *) echo "Invalid Android test scope" >&2; exit 2 ;;
esac
if [[ ! "$matrix_label" =~ ^(3[0-6]|37\.0)$ ]]; then
  echo "Invalid Android API level" >&2
  exit 2
fi
api_level="${matrix_label%%.*}"
instrumentation_pid=''

adb_bounded() {
  timeout --signal=TERM --kill-after=2s 10s adb "$@"
}

handle_signal() {
  local received="$1" result=$((128 + $1))
  trap '' INT TERM
  if [[ -n "$instrumentation_pid" ]]; then
    kill -s "$received" "$instrumentation_pid" 2>/dev/null || true
    wait "$instrumentation_pid"
    local child_result=$?
    # A timeout or already-completed failure is not converted to cancellation.
    if [[ "$child_result" != 0 && "$child_result" != "$result" ]]; then
      result="$child_result"
    fi
  fi
  exit "$result"
}
trap 'handle_signal 2' INT
trap 'handle_signal 15' TERM

collect_on_exit() {
  local result=$?
  local required_evidence_result=0
  trap - EXIT
  trap '' INT TERM
  if [[ "$result" != 0 ]]; then
    python3 scripts/print_android_test_failures.py || true
  fi
  if [[ "$test_scope" == app-experience-* ]]; then
    python3 scripts/run_app_experience_trace.py stop --matrix-label "$matrix_label" || required_evidence_result=1
  fi
  python3 scripts/collect_android_evidence.py collect --api "$api_level" --matrix-label "$matrix_label" --scope "$test_scope" --exit-code "$result" || true
  python3 scripts/collect_performance_evidence.py collect --matrix-label "$matrix_label" --exit-code "$result" || true
  if [[ "$test_scope" == 'idle-pair-diagnostic' ]]; then
    python3 scripts/collect_idle_pair_evidence.py collect --matrix-label "$matrix_label" --exit-code "$result" || true
  fi
  if [[ "$test_scope" == 'stress-diagnostic' ]]; then
    python3 scripts/collect_stress_evidence.py collect --matrix-label "$matrix_label" --exit-code "$result" || true
  fi
  if [[ "$test_scope" == 'active-pair-diagnostic' ]]; then
    python3 scripts/collect_active_pair_evidence.py collect --matrix-label "$matrix_label" --exit-code "$result" || true
  fi
  if [[ "$test_scope" == app-experience-* ]]; then
    python3 scripts/collect_app_experience_evidence.py collect --matrix-label "$matrix_label" --scope "$test_scope" --exit-code "$result" || required_evidence_result=1
  fi
  if [[ "$test_scope" == busy-stop-* ]]; then
    python3 scripts/collect_busy_stop_evidence.py collect --matrix-label "$matrix_label" --scope "$test_scope" --exit-code "$result" || required_evidence_result=1
  fi
  # Preserve a failed instrumentation code. A successful device case alone
  # cannot make a required-evidence scope green when its reports are incomplete.
  if [[ "$result" == 0 && "$required_evidence_result" != 0 ]]; then result=1; fi
  exit "$result"
}
trap collect_on_exit EXIT

if ! actual_sdk="$(adb_bounded shell getprop ro.build.version.sdk | tr -d '\r')"; then
  echo "Could not read device SDK within the preflight timeout" >&2
  exit 2
fi
if [[ "$actual_sdk" != "$api_level" ]]; then
  echo "Device SDK does not match requested matrix: $actual_sdk / $matrix_label" >&2
  exit 2
fi
if [[ "$matrix_label" == '37.0' ]]; then
  python3 - "${ANDROID_HOME:?}/emulator/source.properties" <<'PY'
import re,sys
from pathlib import Path
text=Path(sys.argv[1]).read_text()
match=re.search(r"^Pkg\.Revision\s*=\s*(\d+)\.(\d+)\.(\d+)",text,re.M)
if not match or tuple(map(int,match.groups())) < (37,2,11):
    raise SystemExit("API37.0 requires emulator >=37.2.11")
print("Verified installed API37.0 emulator package:",".".join(match.groups()))
PY
  if [[ "$?" != 0 ]]; then exit 2; fi
fi

# A dedicated emulator baseline, not a required setting on users' phones.
# Record explicit 1.0 values instead of assuming an image or runner's defaults.
for animation_setting in window_animation_scale transition_animation_scale animator_duration_scale; do
  if ! adb_bounded shell settings put global "$animation_setting" 1.0; then
    echo "Could not configure animation baseline: $animation_setting" >&2
    exit 2
  fi
  if ! animation_value="$(adb_bounded shell settings get global "$animation_setting" | tr -d '\r')"; then
    echo "Could not read animation baseline: $animation_setting" >&2
    exit 2
  fi
  echo "Emulator baseline: $animation_setting=$animation_value"
  if [[ "$animation_value" != '1.0' ]]; then
    echo "Expected animation baseline 1.0: $animation_setting" >&2
    exit 2
  fi
done

# The target APK may not yet exist on a clean emulator. Never reuse prior screenshots.
adb_bounded shell run-as dev.agentos.app rm -rf files/screenshots >/dev/null 2>&1 || true
adb_bounded shell run-as dev.agentos.app rm -rf files/performance >/dev/null 2>&1 || true

test_command=(./gradlew --no-daemon
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
  "${selection[@]}"
  :fixture:installDebug :app:connectedDebugAndroidTest)
if [[ "$matrix_label" == '30' && ( "$test_scope" == 'full' || "$test_scope" == 'numeric-import-diagnostic' ) ]]; then
  # The pinned API30 DocumentsUI lacks QUERY_ALL_PACKAGES. Make only the synthetic
  # test APK discoverable; AGP marks helper APKs, but not its own test APK, this way.
  # The fixed numeric-import diagnostic needs the same provider setup as full.
  # The following build and install remain children of the original watchdog.
  # A normal same-package Gradle update preserves this installation override.
  test_command=(bash -e -c '
    ./gradlew --no-daemon :app:assembleDebugAndroidTest
    echo "API30 synthetic DocumentsProvider: install only dev.agentos.app.test with force-queryable"
    timeout --signal=TERM --kill-after=2s 60s adb install -r -t --force-queryable \
      app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    exec "$@"
  ' agentos-numeric-provider "${test_command[@]}")
fi

if [[ "$test_scope" == app-experience-* ]]; then
  # Installation must finish before tracing: atrace must identify both packages.
  # The build/install/capture/test command shares the original bounded watchdog.
  test_command=(bash -e -c '
    ./gradlew --no-daemon :fixture:installDebug :app:installDebug :app:installDebugAndroidTest
    python3 scripts/run_app_experience_trace.py start --matrix-label "$1"
    shift
    exec "$@"
  ' agentos-app-experience "$matrix_label" "${test_command[@]}")
fi

# Twenty minutes normally; stress and formal experience/busy-stop have forty.
# Then about one minute of watchdog diagnostics
# (60s collection budget +1s final reap), <=10s TERM grace and <=2s final reap.
# The existing collectors independently bound their adb reads to <=35s and <=25s.
# Stress and active-pair each add a collector with a 60s total adb budget.
# Stress and formal app experience/busy-stop each have a 55-minute CI job.
# This reserves time for evidence; SDK/boot delays or the external job deadline
# can still interrupt cleanup. A kernel-blocked process may survive SIGKILL.
python3 scripts/run_bounded_instrumentation.py --matrix-label "$matrix_label" \
  --timeout-profile "$watchdog_profile" \
  --timeout-seconds "${ANDROID_TEST_TIMEOUT_SECONDS:-$default_timeout_seconds}" \
  --termination-grace-seconds "${ANDROID_TEST_TERMINATE_GRACE_SECONDS:-10}" \
  -- "${test_command[@]}" &
instrumentation_pid=$!
wait "$instrumentation_pid"
exit "$?"
