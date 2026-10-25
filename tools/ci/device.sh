#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Runs inside reactivecircus/android-emulator-runner once the emulator has booted (CI only).
# 1. the instrumented tests in tools/ci/instrumented-tests.txt (Gradle, nodriveDebug);
# 2. the screen sweep (tools/ci/sweep.py), which installs the APKs again and takes the screenshots.
# Both always run; the exit code is non-zero when either failed. Output goes to build/device/.
set -uo pipefail

OUT=build/device
mkdir -p "$OUT/screens"
ADB="$ANDROID_HOME/platform-tools/adb"
APK=app/build/outputs/apk/nodrive/debug/app-nodrive-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/nodrive/debug/app-nodrive-debug-androidTest.apk
status=0

"$ADB" wait-for-device
"$ADB" shell settings put global private_dns_mode off
"$ADB" shell pm list packages | sort > "$OUT/packages.txt"
echo "device: $("$ADB" shell getprop ro.build.version.release | tr -d '\r') (API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')), $(wc -l < "$OUT/packages.txt") packages"

CLASSES=$(grep -v '^[[:space:]]*#' tools/ci/instrumented-tests.txt | awk 'NF { print $1 }' | paste -sd, -)
echo "instrumented tests: $(echo "$CLASSES" | tr ',' '\n' | wc -l) classes"
if ./gradlew --stacktrace :app:connectedNodriveDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$CLASSES" \
    "-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.nextcloud.test.Flaky,com.owncloud.android.utils.ScreenshotTest" \
    "-Pandroid.testInstrumentationRunnerArguments.timeout_msec=180000" \
    > "$OUT/connected-tests.log" 2>&1; then
    echo "instrumented tests: PASS"
else
    status=1
    echo "instrumented tests: FAIL (last lines of $OUT/connected-tests.log)"
    grep -E "FAILED|Tests? .*failed|tests completed" "$OUT/connected-tests.log" | head -n 40
    tail -n 25 "$OUT/connected-tests.log"
fi

if python3 tools/ci/sweep.py --adb "$ADB" --apk "$APK" --test-apk "$TEST_APK" --out "$OUT/screens"; then
    echo "screen sweep: PASS"
else
    status=1
    echo "screen sweep: FAIL"
fi

"$ADB" logcat -d > "$OUT/logcat.txt" 2>&1 || true
exit "$status"
