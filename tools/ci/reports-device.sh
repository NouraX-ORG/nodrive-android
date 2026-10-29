#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# CI only, inside the emulator job (called by tools/ci/device.sh after conflicts-device.sh and before workspace-device.sh:
# the removal test of that script makes the throwaway server answer 429 afterwards). Runs the problem report tests of the
# nodrive flavour with "am instrument" (not through Gradle, which uninstalls the app and with it the screenshots):
#   ReportsSettingsIT  Settings > More: the switch (off), "Report a problem", no Logs row, no DAVx5 entry, the report screen
#                      and "What's included". No server. Light and dark look.
#   ReportsPromptIT    a crash (adb shell am crash, so NoDrive's own recorder is tested) with the switch off, then the next
#                      start asks once and "Not now" sends nothing. No server. Light and dark look.
#   ReportsUploadIT    against the throwaway Nextcloud (never drive.nourax.org): the first report ends with "Saved in your
#                      NoDrive > Problem reports." (no nourax-support user yet), the script creates that user, the second
#                      report is sent, the third makes no new share request, and with the switch on a crash report arrives
#                      without a question. Needs NODRIVE_WS_URL, NODRIVE_WS_USER and NODRIVE_RP_TOKEN (masked in the log).
#   ReportsOfflineIT  mobile data is turned off by this script: "Report a problem" must say that the report is saved on the
#                      phone and enable Send again, the report stays in the outbox and the run stays queued. Data is then
#                      turned on again (the next scripts need the network).
#   ReportsScrubberSpeedIT  the scrubber on the emulator's own java.util.regex (a 4 MB log with 5000 addresses in a few
#                      seconds; a 200 KB quoted secret is still removed). No server.
# Then the server's own data folder is checked (tools/ci/reports-server-check.py). A native crash (kill -SEGV) is binding: the
# next start must write an exit-*.txt with "Reason: CRASH_NATIVE" (seen on the CI emulator, API 35, run 40795318521).
# Pulls reports-*.png to build/device/screens/reports/ and fails when one of the expected ones is missing.
set -uo pipefail

ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
OUT=build/device
PKG=org.nourax.nodrive
APK=app/build/outputs/apk/nodrive/debug/app-nodrive-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/nodrive/debug/app-nodrive-debug-androidTest.apk
SETTINGS=org.nourax.nodrive.reports.ReportsSettingsIT
PROMPT=org.nourax.nodrive.reports.ReportsPromptIT
UPLOAD=org.nourax.nodrive.reports.ReportsUploadIT
OFFLINE=org.nourax.nodrive.reports.ReportsOfflineIT
SCRUBBER=org.nourax.nodrive.reports.ReportsScrubberSpeedIT
MAIN_ACTIVITY="$PKG/com.owncloud.android.ui.activity.FileDisplayActivity"
SERVER=nodrive-ci-server
DATA="/var/www/html/data/${NODRIVE_WS_USER:-citest}/files/Problem reports"
SHOTS="$OUT/screens/reports"
status=0

mkdir -p "$SHOTS"
"$ADB" install -r "$APK" > "$OUT/reports-install.log" 2>&1 && "$ADB" install -r -t "$TEST_APK" >> "$OUT/reports-install.log" 2>&1 \
    || { cat "$OUT/reports-install.log"; echo "reports: install failed"; exit 1; }
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS > /dev/null 2>&1 || true
RUNNER=$("$ADB" shell pm list instrumentation | tr -d '\r' \
    | sed -n "s|^instrumentation:\([^ ]*\) (target=$PKG)\$|\1|p" | head -n 1)
[ -n "$RUNNER" ] || { echo "reports: no instrumentation for $PKG"; exit 1; }

night() { "$ADB" shell cmd uimode night "$1" > /dev/null 2>&1 || true; }

# The system has a default network ("Active default network: <id>" in dumpsys connectivity; "none" without one).
has_network() { "$ADB" shell dumpsys connectivity 2> /dev/null | tr -d '\r' | grep -Eq '^Active default network: [0-9]+'; }

run_it() { # $1 = look (light|dark), $2 = class or class#method list, $3 = log name, $4 = expected number of tests
    local log="$OUT/reports-it-$3-$1.log"
    "$ADB" shell am instrument -w -e class "$2" \
        -e NODRIVE_WS_URL "${NODRIVE_WS_URL:-}" -e NODRIVE_WS_USER "${NODRIVE_WS_USER:-}" \
        -e NODRIVE_RP_TOKEN "${NODRIVE_RP_TOKEN:-}" -e NODRIVE_BK_LOOK "$1" "$RUNNER" 2>&1 | tr -d '\r' > "$log"
    if grep -q "^OK ($4 test" "$log"; then echo "reports IT $3 ($1): $(grep '^OK (' "$log")"; else
        echo "reports IT $3 ($1): FAIL (expected OK ($4 tests))"; grep -v "^INSTRUMENTATION_" "$log" | tail -n 40 | cut -c1-300
        "$ADB" logcat -d -b crash 2> /dev/null | tail -n 30 | cut -c1-300; status=1; fi
}

pending_files() { "$ADB" shell run-as "$PKG" ls files/nodrive-reports/pending 2> /dev/null | tr -d '\r'; }

# Starts the app, crashes it the way the system does for "am crash", waits until the recorder has written the crash file,
# then stops the app so that no crash dialog stays on the screen.
crash_the_app() {
    "$ADB" shell am start -W -n "$MAIN_ACTIVITY" > /dev/null 2>&1
    sleep 10
    "$ADB" shell am crash "$PKG" > /dev/null 2>&1
    local found=""
    for _ in $(seq 1 30); do
        if pending_files | grep -q '^crash-'; then found=1; break; fi
        sleep 1
    done
    "$ADB" shell am force-stop "$PKG" > /dev/null 2>&1
    sleep 2
    if [ -n "$found" ]; then echo "reports: crash file written ($(pending_files | tr '\n' ' '))"; else
        echo "reports: FAIL the crash left no crash file"; status=1; fi
}

# ---- 1. Settings, report screen, "What's included"
night no
run_it light "$SETTINGS" settings 3
night yes
run_it dark "$SETTINGS" settings 3

# ---- 1b. the scrubber on the emulator's own regex engine (speed and a very long quoted secret)
night no
run_it light "$SCRUBBER" scrubber-speed 2

# ---- 2. the question after a crash (switch off): light, then dark
for look in light dark; do
    if [ "$look" = light ]; then night no; else night yes; fi
    run_it "$look" "$PROMPT#a1PrepareAnOfflineAccountWithTheSwitchOff" prompt-prepare 1
    if [ "$look" = light ]; then
        # A native crash (binding, see the header): the system records it, the next start writes the exit file.
        "$ADB" shell am start -W -n "$MAIN_ACTIVITY" > /dev/null 2>&1
        sleep 10
        pid=$("$ADB" shell pidof "$PKG" | tr -d '\r' | awk '{print $1}')
        if [ -n "$pid" ]; then
            "$ADB" shell run-as "$PKG" kill -SEGV "$pid" > /dev/null 2>&1
            sleep 8
            "$ADB" shell dumpsys activity exit-info "$PKG" 2> /dev/null | tr -d '\r' | head -n 40 > "$OUT/reports-native-probe.txt"
            echo "reports: native crash probe: $(grep -m 2 -i 'reason=' "$OUT/reports-native-probe.txt" | tr -s ' ' | tr '\n' ';')"
        else
            echo "reports: FAIL native crash probe: the app has no process"; status=1
        fi
        "$ADB" shell am force-stop "$PKG" > /dev/null 2>&1
    fi
    crash_the_app
    if [ "$look" = light ]; then
        exit_file=$(pending_files | grep -m 1 '^exit-')
        if [ -n "$exit_file" ] && "$ADB" shell run-as "$PKG" cat "files/nodrive-reports/pending/$exit_file" | tr -d '\r' \
            | grep -q '^Reason: CRASH_NATIVE'; then
            echo "reports: native crash recorded as CRASH_NATIVE ($exit_file)"
        else
            echo "reports: FAIL no exit file with Reason: CRASH_NATIVE after kill -SEGV (pending: $(pending_files | tr '\n' ' '))"; status=1
        fi
    fi
    run_it "$look" "$PROMPT#a2AfterTheCrashTheUserIsAskedOnceAndNotNowSendsNothing" prompt-question 1
    run_it "$look" "$PROMPT#a3CleanUp" prompt-clean 1
done
night no

# ---- 3. against the throwaway server
if [ -n "${NODRIVE_WS_URL:-}" ] && [ -n "${NODRIVE_RP_TOKEN:-}" ]; then
    run_it light "$UPLOAD#b1TheFirstReportIsSavedWhenTheSupportUserDoesNotExistYet" upload-b1 1
    # What the Workspace session will do later: the account that receives the share. Random masked password.
    support_pw=$(openssl rand -hex 16)
    echo "::add-mask::$support_pw"
    docker exec -u www-data -e OC_PASS="$support_pw" "$SERVER" php occ user:add --password-from-env \
        --display-name "Nourax Support" nourax-support > /dev/null 2>&1 \
        && echo "reports: test user nourax-support created" || { echo "reports: FAIL nourax-support not created"; status=1; }
    run_it light "$UPLOAD#b2TheSecondReportIsSentOnceTheSupportUserExists" upload-b2 1
    run_it light "$UPLOAD#b3TheThirdReportMakesNoNewShareRequest" upload-b3 1
    run_it light "$UPLOAD#b4PrepareTheAutomaticSend" upload-b4 1
    crash_the_app
    run_it light "$UPLOAD#b5TheCrashReportArrivedWithoutAQuestion" upload-b5 1

    # Independent proof: the files are in the server's own data folder, not only visible to the app.
    rm -rf "$OUT/reports"; mkdir -p "$OUT/reports"
    docker cp "$SERVER:$DATA/." "$OUT/reports/" > /dev/null 2>&1 || echo "reports: nothing to copy from $DATA"
    docker logs "$SERVER" 2>&1 | grep -E '"POST [^"]*files_sharing/api/v1/shares' | cut -c1-300 > "$OUT/reports-share-posts.txt" || true
    ls -l "$OUT/reports" | tail -n +2 | awk '{print $5, $9}' > "$OUT/reports-server-files.txt"
    python3 tools/ci/reports-server-check.py --zip-dir "$OUT/reports" --share-posts "$OUT/reports-share-posts.txt" \
        --base-url "http://localhost:${NODRIVE_WS_PORT:-8080}" --user "${NODRIVE_WS_USER:-citest}" | tee "$OUT/reports-server-check.txt" \
        | grep -E "^\[FAIL\]|^reports server check"
    grep -q "^reports server check: PASS" "$OUT/reports-server-check.txt" || status=1
else
    echo "reports: no test server, upload test skipped"
    status=1
fi

# ---- 4. without a network (the throwaway server is not needed: the account is a local one)
night no
"$ADB" shell svc data disable > /dev/null 2>&1
offline=""
for _ in $(seq 1 30); do
    if ! has_network; then offline=1; break; fi
    sleep 2
done
if [ -n "$offline" ]; then
    run_it light "$OFFLINE#c1WithoutANetworkTheScreenSaysTheReportIsSavedOnThePhone" offline-c1 1
else
    echo "reports: FAIL the emulator stayed online after svc data disable"; status=1
fi
"$ADB" shell svc data enable > /dev/null 2>&1
online=""
for _ in $(seq 1 60); do
    if has_network; then online=1; break; fi
    sleep 2
done
[ -n "$online" ] && echo "reports: network is back" || { echo "reports: FAIL the network did not come back"; status=1; }
run_it light "$OFFLINE#c2CleanUp" offline-c2 1

for f in $("$ADB" shell ls "/sdcard/Android/data/$PKG/files/" 2> /dev/null | tr -d '\r' | grep '^reports-.*\.png$'); do
    "$ADB" pull "/sdcard/Android/data/$PKG/files/$f" "$SHOTS/$f" > /dev/null
done
for name in settings report included crash-prompt; do
    for look in light dark; do
        f="reports-$name-$look.png"
        if [ -s "$SHOTS/$f" ]; then echo "reports screenshot: $f"; else echo "reports screenshot MISSING: $f"; status=1; fi
    done
done
for name in saved sent auto-sent offline; do
    f="reports-$name-light.png"
    if [ -s "$SHOTS/$f" ]; then echo "reports screenshot: $f"; else echo "reports screenshot MISSING: $f"; status=1; fi
done

"$ADB" uninstall "$PKG.test" > /dev/null 2>&1 || true
"$ADB" uninstall "$PKG" > /dev/null 2>&1 || true
exit "$status"
