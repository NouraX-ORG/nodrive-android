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
# Wi-Fi on current emulators runs through netsim, which the emulator's -tcpdump capture does not
# see. With Wi-Fi off all traffic takes the mobile-data path over the captured virtual LAN.
default_is_mobile() {
    local state id
    state=$("$ADB" shell dumpsys connectivity | tr -d '\r')
    id=$(printf '%s\n' "$state" | sed -n 's/^Active default network: \([0-9][0-9]*\).*/\1/p' | head -n 1)
    [ -n "$id" ] && printf '%s\n' "$state" | grep -F "NetworkAgentInfo{network{$id}" | grep -q "MOBILE"
}
# Right after boot the emulator may still switch Wi-Fi on, so the command is repeated until it holds.
for i in $(seq 0 59); do
    if [ $((i % 5)) -eq 0 ]; then
        "$ADB" shell cmd wifi set-wifi-enabled disabled || "$ADB" shell svc wifi disable
        "$ADB" shell svc data enable
    fi
    default_is_mobile && break
    sleep 2
done
"$ADB" shell "dumpsys connectivity | grep -E -m 4 'Active default network|NetworkAgentInfo.*(MOBILE|WIFI)'" \
    | cut -c1-200 > "$OUT/network-state.txt" || true
"$ADB" shell settings get global wifi_on >> "$OUT/network-state.txt" || true
if default_is_mobile; then echo "network: default is mobile data (Wi-Fi off)"; else
    echo "network: WARNING default network is not mobile data:"; cat "$OUT/network-state.txt"; fi
"$ADB" shell pm list packages | sort > "$OUT/packages.txt"
echo "device: $("$ADB" shell getprop ro.build.version.release | tr -d '\r') (API $("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')), $(wc -l < "$OUT/packages.txt") packages"

LIST=tools/ci/instrumented-tests.txt
CLASSES=$(grep -v '^[[:space:]]*#' "$LIST" | awk 'NF && $1 !~ /^-/ { print $1 }' | paste -sd, -)
NOT_METHODS=$(grep -v '^[[:space:]]*#' "$LIST" | awk 'NF && $1 ~ /^-/ { print substr($1, 2) }' | paste -sd, -)
RESULTS=app/build/outputs/androidTest-results/connected
REPORTS=app/build/reports/androidTests/connected
MAX_RETRY=5
echo "instrumented tests: $(echo "$CLASSES" | tr ',' '\n' | wc -l) classes, $(echo "$NOT_METHODS" | tr ',' '\n' | grep -c .) method(s) left out"

connected_tests() { # $1 = class list, $2 = log file
    ./gradlew --stacktrace :app:connectedNodriveDebugAndroidTest \
        "-Pandroid.testInstrumentationRunnerArguments.class=$1" \
        "-Pandroid.testInstrumentationRunnerArguments.notClass=$NOT_METHODS" \
        "-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.nextcloud.test.Flaky,com.owncloud.android.utils.ScreenshotTest" \
        "-Pandroid.testInstrumentationRunnerArguments.timeout_msec=180000" \
        > "$2" 2>&1
}

failed_tests() { # Class#method of every failed test in the JUnit XML results, comma-separated
    python3 - "$RESULTS" <<'EOF'
import sys, xml.etree.ElementTree as ET
from pathlib import Path
failed = []
for xml in sorted(Path(sys.argv[1]).rglob("TEST-*.xml")):
    for case in ET.parse(xml).getroot().iter("testcase"):
        if case.find("failure") is not None or case.find("error") is not None:
            failed.append(f"{case.get('classname')}#{case.get('name')}")
print(",".join(dict.fromkeys(failed)))
EOF
}

# Which window has input focus, every 5 s while the tests run: on the API 35 emulator some Espresso
# tests sometimes fail with RootViewWithoutFocusException although their activity is resumed.
( while true; do
    "$ADB" shell 'echo "$(date +%H:%M:%S) $(dumpsys window | grep -E -m 2 "mCurrentFocus|mFocusedApp" | tr -s " " | tr "\n" " ")"' \
        2>/dev/null | tr -d '\r'
    sleep 5
done ) > "$OUT/focus.txt" &
FOCUS_LOGGER=$!

if connected_tests "$CLASSES" "$OUT/connected-tests.log"; then
    echo "instrumented tests: PASS"
else
    echo "instrumented tests: FAIL in the first run (last lines of $OUT/connected-tests.log)"
    grep -E "FAILED|Tests? .*failed|tests completed" "$OUT/connected-tests.log" | head -n 40
    tail -n 25 "$OUT/connected-tests.log"
    FAILED=$(failed_tests)
    COUNT=$(echo "$FAILED" | tr ',' '\n' | grep -c .)
    # A few failed methods get one more run, alone; more failures, or none found in the results
    # (a build or install error), fail at once. Both runs are kept and reported.
    if [ "$COUNT" -ge 1 ] && [ "$COUNT" -le "$MAX_RETRY" ]; then
        mkdir -p "$OUT/first-run"
        cp -r "$RESULTS" "$OUT/first-run/results"
        cp -r "$REPORTS" "$OUT/first-run/report" 2>/dev/null || true
        echo "instrumented tests: retrying $COUNT failed method(s) once: $FAILED"
        if connected_tests "$FAILED" "$OUT/connected-tests-retry.log"; then
            echo "instrumented tests: PASS after one retry of $COUNT method(s): $FAILED"
            RETRY="passed on the retry"
        else
            status=1
            echo "instrumented tests: FAIL - still failing on the retry (last lines of $OUT/connected-tests-retry.log)"
            tail -n 25 "$OUT/connected-tests-retry.log"
            RETRY="failed again on the retry"
        fi
        {
            echo "## Instrumented test retry"
            echo
            echo "The first run failed $COUNT method(s); each was run once more, alone: $RETRY."
            echo "Results of the first run: build/device/first-run/ in the test-report artifact."
            echo
            echo "$FAILED" | tr ',' '\n' | sed 's/^/- /'
        } > "$OUT/retry.md"
        cat "$OUT/retry.md" >> "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
    else
        status=1
        echo "instrumented tests: FAIL - $COUNT failed method(s) in the results, no retry (limit $MAX_RETRY)"
    fi
fi
kill "$FOCUS_LOGGER" 2>/dev/null || true

if python3 tools/ci/sweep.py --adb "$ADB" --apk "$APK" --test-apk "$TEST_APK" --out "$OUT/screens"; then
    echo "screen sweep: PASS"
else
    status=1
    echo "screen sweep: FAIL"
fi

"$ADB" logcat -d > "$OUT/logcat.txt" 2>&1 || true
exit "$status"
