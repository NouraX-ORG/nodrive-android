#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# CI only, inside the emulator job (called by tools/ci/device.sh, before workspace-device.sh: the removal test of
# that script makes the throwaway server answer 429 afterwards). Runs the backup tests of the nodrive flavour with
# "am instrument" (not through Gradle, which uninstalls the app and with it the screenshots the tests saved):
#   BackupSettingsIT   Settings section, setup screen, Auto upload, Stop Google backup, Takeout guide, first
#                      sign-in offer. Needs no server; run in the light and in the dark look.
#   BackupUploadIT     a photo that was already on the phone must appear on the throwaway Nextcloud under
#                      /Phone backup/<phone>/DCIM/Camera. Needs NODRIVE_WS_URL, NODRIVE_WS_USER, NODRIVE_BK_TOKEN
#                      (masked in the log). The script then checks the server's data folder on its own.
# Pulls backup-*.png to build/device/screens/backup/ and fails when one of the 12 is missing.
set -uo pipefail

ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
OUT=build/device
PKG=org.nourax.nodrive
APK=app/build/outputs/apk/nodrive/debug/app-nodrive-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/nodrive/debug/app-nodrive-debug-androidTest.apk
SETTINGS=org.nourax.nodrive.backup.BackupSettingsIT
UPLOAD=org.nourax.nodrive.backup.BackupUploadIT
SHOTS="$OUT/screens/backup"
status=0

mkdir -p "$SHOTS"
"$ADB" install -r "$APK" > "$OUT/backup-install.log" 2>&1 && "$ADB" install -r -t "$TEST_APK" >> "$OUT/backup-install.log" 2>&1 \
    || { cat "$OUT/backup-install.log"; echo "backup: install failed"; exit 1; }
RUNNER=$("$ADB" shell pm list instrumentation | tr -d '\r' \
    | sed -n "s|^instrumentation:\([^ ]*\) (target=$PKG)\$|\1|p" | head -n 1)
[ -n "$RUNNER" ] || { echo "backup: no instrumentation for $PKG"; exit 1; }

run_it() { # $1 = look (light|dark), $2 = class, $3 = log name
    local log="$OUT/backup-it-$3-$1.log"
    "$ADB" shell am instrument -w -e class "$2" \
        -e NODRIVE_WS_URL "${NODRIVE_WS_URL:-}" -e NODRIVE_WS_USER "${NODRIVE_WS_USER:-}" \
        -e NODRIVE_BK_TOKEN "${NODRIVE_BK_TOKEN:-}" -e NODRIVE_BK_LOOK "$1" "$RUNNER" 2>&1 | tr -d '\r' > "$log"
    if grep -q "^OK (" "$log"; then echo "backup IT $3 ($1): $(grep '^OK (' "$log")"; else
        echo "backup IT $3 ($1): FAIL"; grep -v "^INSTRUMENTATION_" "$log" | tail -n 40 | cut -c1-300; status=1; fi
}

"$ADB" shell cmd uimode night no > /dev/null 2>&1 || true
run_it light "$SETTINGS" settings
"$ADB" shell cmd uimode night yes > /dev/null 2>&1 || true
run_it dark "$SETTINGS" settings
"$ADB" shell cmd uimode night no > /dev/null 2>&1 || true

if [ -n "${NODRIVE_WS_URL:-}" ] && [ -n "${NODRIVE_BK_TOKEN:-}" ]; then
    run_it light "$UPLOAD" upload
    if grep -q "OK (1 test)" "$OUT/backup-it-upload-light.log"; then
        # Independent proof: the file is in the server's own data folder, not only visible to the app.
        hits=$(docker exec nodrive-ci-server find /var/www/html/data -path '*Phone backup*' -name 'nodrive-ci-*.jpg' 2> /dev/null | tee "$OUT/backup-server-files.txt" | grep -c .)
        if [ "$hits" -ge 1 ]; then echo "backup server check: $hits file(s) under Phone backup on the server"; else
            echo "backup server check: FAIL no file under Phone backup in the server's data folder"; status=1; fi
    else
        echo "backup server check: the upload test did not pass (OK (1 test) missing), so the server was not checked"; status=1
    fi
else
    echo "backup: no test server, upload test skipped"
fi

for f in $("$ADB" shell ls "/sdcard/Android/data/$PKG/files/" 2> /dev/null | tr -d '\r' | grep '^backup-.*\.png$'); do
    "$ADB" pull "/sdcard/Android/data/$PKG/files/$f" "$SHOTS/$f" > /dev/null
done
for name in settings setup-result auto-upload stop-google move-google offer; do
    for look in light dark; do
        f="backup-$name-$look.png"
        if [ -s "$SHOTS/$f" ]; then echo "backup screenshot: $f"; else echo "backup screenshot MISSING: $f"; status=1; fi
    done
done

"$ADB" uninstall "$PKG.test" > /dev/null 2>&1 || true
"$ADB" uninstall "$PKG" > /dev/null 2>&1 || true
exit "$status"
