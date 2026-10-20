#!/usr/bin/env bash
# Runs the smoke test (the :smokeTest module) against an app APK on an Android emulator: signs the app in to the
# fake Nextcloud in tools/fake-nextcloud, makes a few edits and checks that they reach it.
#
#   scripts/smoke-test.sh path/to/NextTask.apk
#
# The release workflow runs it on the signed APK before publishing. It replaces the app on the device, data
# included, so it only runs on an emulator. With several devices connected, pick one with ANDROID_SERIAL.
# Needs adb (on PATH, or in $ADB) and python3.
set -euo pipefail

if [ $# -ne 1 ] || [ ! -f "$1" ]; then
  echo "Usage: $0 path/to/app.apk" >&2
  exit 2
fi
apk=$1
root=$(cd "$(dirname "$0")/.." && pwd)
adb=${ADB:-adb}
app=cc.sche.apps.nexttask
test_package=cc.sche.apps.nexttask.smoketest
port=8099

if [ "$("$adb" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]; then
  echo "The connected device is not an emulator; the smoke test would delete the app's data on it." >&2
  exit 2
fi

"$root/gradlew" -p "$root" --quiet :smokeTest:assembleDebug
test_apk="$root/smokeTest/build/outputs/apk/debug/smokeTest-debug.apk"

python3 -I "$root/tools/fake-nextcloud/fake_nextcloud.py" &
server=$!
trap 'kill "$server" 2> /dev/null || true' EXIT
for _ in $(seq 50); do
  curl --silent --fail --output /dev/null "http://localhost:$port/admin/state" && break
  sleep 0.1
done
"$adb" reverse "tcp:$port" "tcp:$port"

# A copy signed with another key cannot be updated in place.
"$adb" uninstall "$app" > /dev/null 2>&1 || true
"$adb" install "$apk"
"$adb" install -r "$test_apk"

# `am instrument` exits 0 even when a test fails; its summary line tells.
output=$("$adb" shell am instrument -w "$test_package/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r')
echo "$output"
if ! grep -q '^OK (' <<< "$output"; then
  echo "Server log:" >&2
  curl --silent "http://localhost:$port/admin/log" >&2 || true
  exit 1
fi
