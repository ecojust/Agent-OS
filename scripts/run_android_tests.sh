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
  python3 scripts/collect_android_evidence.py collect --api "$api_level" --matrix-label "$matrix_label" --exit-code "$result" || true
  exit "$result"
}
trap collect_on_exit EXIT

actual_sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$actual_sdk" != "$api_level" ]]; then
  echo "Device SDK does not match requested matrix: $actual_sdk / $matrix_label" >&2
  exit 2
fi
if [[ "$matrix_label" == '37.0' ]]; then
  "${ANDROID_HOME:?}/emulator/emulator" -version | python3 -c '
import re,sys
text=sys.stdin.read()
match=re.search(r"Android emulator version (\d+)\.(\d+)\.(\d+)",text)
if not match or tuple(map(int,match.groups())) < (37,2,11):
    raise SystemExit("API37.0 requires emulator >=37.2.11")
print("Verified API37.0 emulator:",match.group(0))
'
  if [[ "$?" != 0 ]]; then exit 2; fi
fi

# The target APK may not yet exist on a clean emulator. Never reuse prior screenshots.
adb shell run-as dev.agentos.app rm -rf files/screenshots >/dev/null 2>&1 || true

./gradlew --no-daemon \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  :fixture:installDebug :app:connectedDebugAndroidTest
