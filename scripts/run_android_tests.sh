#!/usr/bin/env bash
# Preserve the test result while collecting evidence before the emulator shuts down.
set -uo pipefail

matrix_label="${1:?Android API level is required}"
test_scope="${2:-full}"
if (( $# > 2 )); then
  echo "Unexpected Android test arguments" >&2
  exit 2
fi
case "$test_scope" in
  full)
    selection=(-Pandroid.testInstrumentationRunnerArguments.notClass=dev.agentos.app.PinnedCalculatorVisualPageInstrumentedTest,dev.agentos.app.DemoWalkthroughInstrumentedTest,dev.agentos.app.PinnedWindowDisclosureInstrumentedTest)
    ;;
  owned-control-diagnostic)
    selection=(-Pandroid.testInstrumentationRunnerArguments.class=dev.agentos.app.OwnedControlWindowInstrumentedTest#changedPresentationAndCallbacksStillRevokeTheActualControlOwner)
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
  trap - EXIT
  trap '' INT TERM
  if [[ "$result" != 0 ]]; then
    python3 scripts/print_android_test_failures.py || true
  fi
  python3 scripts/collect_android_evidence.py collect --api "$api_level" --matrix-label "$matrix_label" --scope "$test_scope" --exit-code "$result" || true
  python3 scripts/collect_performance_evidence.py collect --matrix-label "$matrix_label" --exit-code "$result" || true
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

# Twenty minutes for this invocation, then about one minute of diagnostics
# (60s collection budget +1s final reap), <=10s TERM grace and <=2s final reap.
# The existing collectors independently bound their adb reads to <=35s and <=25s.
# This reserves time for evidence; SDK/boot delays or the external job deadline
# can still interrupt cleanup. A kernel-blocked process may survive SIGKILL.
python3 scripts/run_bounded_instrumentation.py --matrix-label "$matrix_label" \
  --timeout-seconds "${ANDROID_TEST_TIMEOUT_SECONDS:-1200}" \
  --termination-grace-seconds "${ANDROID_TEST_TERMINATE_GRACE_SECONDS:-10}" \
  -- ./gradlew --no-daemon \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  "${selection[@]}" \
  :fixture:installDebug :app:connectedDebugAndroidTest &
instrumentation_pid=$!
wait "$instrumentation_pid"
exit "$?"
