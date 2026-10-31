#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# CI only, inside the "Device calendar and contacts sync on the emulator" job (reactivecircus/android-emulator-runner). Runs the device
# sync tests of the nodrive flavour with "am instrument" (not through Gradle, which uninstalls the app and with it the screenshots):
#   DeviceSyncSettingsIT   the Settings row says Off; the phone's sync system knows both adapters and the account is not syncable while off;
#                          a sync before the backup does nothing; the intro and permission screens; the status screen in the states that are hard to
#                          bring about (each reason, each refusal text, the Stop and untick questions). Light and dark. No server needed.
#   DeviceSyncFirstSyncIT  against the throwaway server (tools/ci/agenda-server.sh, seeded, plus tools/ci/devicesync-seed.py): through the
#                          screens, the backup comes first and the first sync only adds; nothing goes down on either side; another
#                          source's calendar and contact are byte for byte as they were; no to-do reaches the phone. Light and dark.
#   DeviceSyncTwoWayIT     a phone edit keeps the agent id and category; a server edit reaches the phone; one changed occurrence of the
#                          stand-up is a RECURRENCE-ID; a conflict (server wins, "Not saved", "Apply my change again"); a phone delete
#                          lands in the calendar trash bin; 25 deletes are held back by the guard.
#   DeviceSyncReminderIT   a server alarm is a phone reminder that the phone's Calendar fires (or schedules).
#   DeviceSyncContactsIT   a contacts round trip: group and photo, a changed number keeps the unknown line, a new contact, a delete that
#                          first saves the card in .Contacts-Backup.
#   DeviceSyncSafetyIT     a calendar deleted on the server does not hold "Stop syncing" up; a photo set on the phone survives a server edit
#                          and reaches the server; NoGantt's copy calendar of an account that has it on is never offered and a chosen one
#                          leaves the phone.
#   DeviceSyncStopIT       a1 sync on and a change waiting, this script turns data off, a2 stopping is refused, data goes on, a3 the final
#                          sync sends the change and only our copies are removed from the phone.
# Then tools/ci/devicesync-server-check.py asks the server itself. Pulls devicesync-*.png to build/device/screens/devicesync/, fails when an
# expected one is missing, and checks that the dark screenshots are dark and the light ones are light.
set -uo pipefail

ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
OUT=build/device
PKG=org.nourax.nodrive
APK=app/build/outputs/apk/nodrive/debug/app-nodrive-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/nodrive/debug/app-nodrive-debug-androidTest.apk
SETTINGS=org.nourax.nodrive.devicesync.DeviceSyncSettingsIT
FIRST=org.nourax.nodrive.devicesync.DeviceSyncFirstSyncIT
TWOWAY=org.nourax.nodrive.devicesync.DeviceSyncTwoWayIT
REMINDER=org.nourax.nodrive.devicesync.DeviceSyncReminderIT
CONTACTS=org.nourax.nodrive.devicesync.DeviceSyncContactsIT
STOP=org.nourax.nodrive.devicesync.DeviceSyncStopIT
SAFETY=org.nourax.nodrive.devicesync.DeviceSyncSafetyIT
SHOTS="$OUT/screens/devicesync"
status=0

mkdir -p "$SHOTS"
"$ADB" wait-for-device
"$ADB" shell settings put global private_dns_mode off
# Mobile data is the path that the virtual LAN capture sees (see tools/ci/device.sh).
for i in $(seq 0 29); do
    if [ $((i % 5)) -eq 0 ]; then
        "$ADB" shell cmd wifi set-wifi-enabled disabled || "$ADB" shell svc wifi disable
        "$ADB" shell svc data enable
    fi
    if "$ADB" shell dumpsys connectivity 2> /dev/null | tr -d '\r' | grep -Eq '^Active default network: [0-9]+'; then break; fi
    sleep 2
done

# The screen must stay on and undimmed for the whole run (a dimmed screen makes the screenshots dark).
"$ADB" shell svc power stayon true > /dev/null 2>&1 || true
"$ADB" shell settings put system screen_off_timeout 2147483647 > /dev/null 2>&1 || true
"$ADB" shell settings put global hide_error_dialogs 1 > /dev/null 2>&1 || true
# A system app of the emulator image can put an ANR dialog over the app on a busy runner; the watchdog closes the dialog of a system app
# by stopping that app. An ANR of NoDrive itself is left alone.
anr_package() {
    "$ADB" shell dumpsys window 2> /dev/null | tr -d '\r' \
        | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* Application Not Responding: \([^}]*\)}.*/\1/p' | head -n 1
}
anr_watchdog() {
    while true; do
        local pkg
        pkg=$(anr_package)
        if [ -n "$pkg" ] && [ "${pkg%%:*}" != "org.nourax.nodrive" ]; then
            echo "devicesync: closing the ANR dialog of $pkg"
            "$ADB" shell am force-stop "${pkg%%:*}" > /dev/null 2>&1
        fi
        sleep 4
    done
}
anr_watchdog &
WATCHDOG=$!
trap 'kill $WATCHDOG 2> /dev/null' EXIT
"$ADB" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard > /dev/null 2>&1 || true

"$ADB" install -r "$APK" > "$OUT/devicesync-install.log" 2>&1 && "$ADB" install -r -t "$TEST_APK" >> "$OUT/devicesync-install.log" 2>&1 \
    || { cat "$OUT/devicesync-install.log"; echo "devicesync: install failed"; exit 1; }
for p in READ_CALENDAR WRITE_CALENDAR READ_CONTACTS WRITE_CONTACTS POST_NOTIFICATIONS; do
    "$ADB" shell pm grant "$PKG" "android.permission.$p" > /dev/null 2>&1 || true
done
RUNNER=$("$ADB" shell pm list instrumentation | tr -d '\r' \
    | sed -n "s|^instrumentation:\([^ ]*\) (target=$PKG)\$|\1|p" | head -n 1)
[ -n "$RUNNER" ] || { echo "devicesync: no instrumentation for $PKG"; exit 1; }

night() { "$ADB" shell cmd uimode night "$1" > /dev/null 2>&1 || true; }
has_network() { "$ADB" shell dumpsys connectivity 2> /dev/null | tr -d '\r' | grep -Eq '^Active default network: [0-9]+'; }
data_off() {
    "$ADB" shell svc data disable > /dev/null 2>&1
    for _ in $(seq 1 30); do
        if ! has_network; then return 0; fi
        sleep 2
    done
    return 1
}
data_on() {
    "$ADB" shell svc data enable > /dev/null 2>&1
    for _ in $(seq 1 60); do
        if has_network; then return 0; fi
        sleep 2
    done
    return 1
}

# $1 = look (light|dark), $2 = class or class#method, $3 = log name, $4 = expected number of tests
run_it() {
    local log="$OUT/devicesync-it-$3-$1.log"
    "$ADB" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
    # -r (raw): one INSTRUMENTATION_STATUS_CODE per test. 0 = passed; -1 error, -2 failure, -3 ignored, -4 assumption not met (a test that
    # skipped itself, for example for a missing server, is not a pass: the plain "OK (n tests)" line counts it as one).
    "$ADB" shell am instrument -w -r -e class "$2" \
        -e NODRIVE_AG_URL "${NODRIVE_AG_DEVICE_URL:-}" \
        -e NODRIVE_AG_ALI_TOKEN "${NODRIVE_AG_ALI_TOKEN:-}" -e NODRIVE_AG_MARA_TOKEN "${NODRIVE_AG_MARA_TOKEN:-}" \
        -e NODRIVE_AG_SARA_TOKEN "${NODRIVE_AG_SARA_TOKEN:-}" -e NODRIVE_AG_OLAF_TOKEN "${NODRIVE_AG_OLAF_TOKEN:-}" \
        -e NODRIVE_BK_LOOK "$1" "$RUNNER" 2>&1 | tr -d '\r' > "$log"
    local passed skipped
    passed=$(grep -c '^INSTRUMENTATION_STATUS_CODE: 0$' "$log" || true)
    skipped=$(grep -cE '^INSTRUMENTATION_STATUS_CODE: (-1|-2|-3|-4)$' "$log" || true)
    if [ "$passed" = "$4" ] && [ "$skipped" = "0" ] && grep -q '^INSTRUMENTATION_CODE: -1$' "$log"; then
        echo "devicesync IT $3 ($1): OK ($passed tests passed, 0 failed or skipped)"; else
        echo "devicesync IT $3 ($1): FAIL (expected $4 passed, got $passed passed, $skipped failed or skipped)"
        grep -vE "^INSTRUMENTATION_(STATUS: (numtests|id|current|class)=|STATUS_CODE: 1$)" "$log" | tail -n 70 | cut -c1-400
        "$ADB" logcat -d -b crash 2> /dev/null | tail -n 30 | cut -c1-300
        "$ADB" logcat -d 2> /dev/null | grep -E "DeviceSync(Backup|Runner|Calendar|Contacts|Control|Model)|BackupWork|SaveCalendar|FileUploadWorker|TransferManager" | tail -n 80 | cut -c1-300; status=1; fi
}

# ---- 1. no server needed
for look in light dark; do
    if [ "$look" = light ]; then night no; else night yes; fi
    run_it "$look" "$SETTINGS" settings 5
done
night no

# ---- 2. against the throwaway server
if [ -n "${NODRIVE_AG_DEVICE_URL:-}" ] && [ -n "${NODRIVE_AG_ALI_TOKEN:-}" ]; then
    python3 tools/ci/devicesync-seed.py > "$OUT/devicesync-seed.log" 2>&1 || { cat "$OUT/devicesync-seed.log"; status=1; }
    for look in light dark; do
        if [ "$look" = light ]; then night no; else night yes; fi
        run_it "$look" "$FIRST" first-sync 1
    done
    night no
    run_it light "$TWOWAY" two-way 6
    run_it light "$CONTACTS" contacts 1
    run_it light "$REMINDER" reminder 1
    "$ADB" shell cat "/sdcard/Android/data/$PKG/files/devicesync-reminder-proof.txt" 2> /dev/null | tr -d '\r' > "$OUT/devicesync-reminder-proof.txt" || true
    echo "devicesync: reminder proof: $(cat "$OUT/devicesync-reminder-proof.txt" 2> /dev/null)"
    run_it light "$SAFETY" safety 3

    # Stop syncing, with the network off and on in between
    run_it light "$STOP#a1SyncIsOnAndAPhoneChangeIsWaiting" stop-a1 1
    if data_off; then
        run_it light "$STOP#a2WithoutANetworkStoppingIsRefusedAndNothingIsRemoved" stop-a2 1
    else
        echo "devicesync: FAIL the emulator stayed online after svc data disable"; status=1
    fi
    data_on && echo "devicesync: network is back" || { echo "devicesync: FAIL the network did not come back"; status=1; }
    run_it light "$STOP#a3WithTheNetworkBackAFinalSyncSendsTheChangeAndThenOnlyOurCopiesAreRemoved" stop-a3 1
    run_it light "$STOP#b1AConflictRefusesStopAndTheNotSavedEntryStaysListed" stop-b1 1

    # what the server really holds, asked as the user with plain HTTP (not through the app)
    python3 tools/ci/devicesync-server-check.py "$OUT/devicesync-server-check.md" > "$OUT/devicesync-server-check.log" 2>&1 \
        && echo "devicesync: the server holds what the phone sent" \
        || { echo "devicesync: FAIL the server does not hold what the phone sent"; cat "$OUT/devicesync-server-check.log"; status=1; }
else
    echo "devicesync: no throwaway server, the live tests did not run"
    status=1
fi

# ---- 3. the screenshots
for f in $("$ADB" shell ls "/sdcard/Android/data/$PKG/files/" 2> /dev/null | tr -d '\r' | grep '^devicesync-.*\.png$'); do
    "$ADB" pull "/sdcard/Android/data/$PKG/files/$f" "$SHOTS/$f" > /dev/null
done
for name in settings intro permissions choose backup first-sync status status-reasons status-unreadable status-stop-question; do
    for look in light dark; do
        f="devicesync-$name-$look.png"
        if [ -s "$SHOTS/$f" ]; then echo "devicesync screenshot: $f"; else echo "devicesync screenshot MISSING: $f"; status=1; fi
    done
done

# The dark screens are dark and the light ones are light (the theme really switched).
python3 - "$SHOTS" <<'EOF' || status=1
import sys
from pathlib import Path
from PIL import Image, ImageStat

bad = 0
shots = sorted(Path(sys.argv[1]).glob("devicesync-*.png"))
for path in shots:
    mean = ImageStat.Stat(Image.open(path).convert("L")).mean[0]
    dark = path.stem.endswith("-dark")
    ok = mean < 100 if dark else mean > 120
    if not ok:
        bad += 1
        print(f"devicesync pixel check FAIL: {path.name} mean luminance {mean:.0f}")
print(f"devicesync pixel check: {len(shots)} screenshots, {bad} with the wrong brightness")
sys.exit(1 if bad else 0)
EOF

"$ADB" uninstall "$PKG.test" > /dev/null 2>&1 || true
"$ADB" uninstall "$PKG" > /dev/null 2>&1 || true
exit "$status"
