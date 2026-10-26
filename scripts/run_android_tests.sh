#!/usr/bin/env bash
# Preserve the test result while collecting evidence before the emulator shuts down.
set -uo pipefail

matrix_label="${1:?Android API level is required}"
if [[ ! "$matrix_label" =~ ^(3[0-6]|37\.0)$ ]]; then
  echo "Invalid Android API level" >&2
  exit 2
fi
api_level="${matrix_label%%.*}"

collect_on_exit() {
  local result=$?
  trap - EXIT
  if [[ "$result" != 0 ]]; then
    python3 scripts/print_android_test_failures.py || true
  fi
  python3 scripts/collect_android_evidence.py collect --api "$api_level" --matrix-label "$matrix_label" --exit-code "$result" || true
  python3 scripts/collect_performance_evidence.py collect --matrix-label "$matrix_label" --exit-code "$result" || true
  exit "$result"
}
trap collect_on_exit EXIT

actual_sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
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
  if ! adb shell settings put global "$animation_setting" 1.0; then
    echo "Could not configure animation baseline: $animation_setting" >&2
    exit 2
  fi
  if ! animation_value="$(adb shell settings get global "$animation_setting" | tr -d '\r')"; then
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
adb shell run-as dev.agentos.app rm -rf files/screenshots >/dev/null 2>&1 || true
adb shell run-as dev.agentos.app rm -rf files/performance >/dev/null 2>&1 || true

./gradlew --no-daemon \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  :fixture:installDebug :app:connectedDebugAndroidTest
