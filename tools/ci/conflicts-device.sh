#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# CI only, inside the emulator job (called by tools/ci/device.sh after backup-device.sh and before workspace-device.sh:
# the removal test of that script makes the throwaway server answer 429 afterwards). Runs the Smart conflicts tests of
# the nodrive flavour with "am instrument" (not through Gradle, which uninstalls the app and with it the screenshots):
#   ConflictsSettingsIT   the setting in Settings > Backup, its dialog, the per-folder Auto upload dialog. No server.
#                         Light and dark look.
#   ConflictsMigrationIT  the one-time migration on the real Auto upload table. No server.
#   ConflictsUploadIT     the Smart rule against the throwaway Nextcloud, the one grouped notification and
#                         "Resolve all conflicts..." with each choice. Needs NODRIVE_WS_URL, NODRIVE_WS_USER and
#                         NODRIVE_CF_TOKEN (masked in the log). Light look all tests; dark look the two tests that take
#                         screenshots. The script then checks the server's own data folder.
# Pulls conflicts-*.png to build/device/screens/conflicts/ and fails when one of the 12 is missing.
set -uo pipefail

ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
OUT=build/device
PKG=org.nourax.nodrive
APK=app/build/outputs/apk/nodrive/debug/app-nodrive-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/nodrive/debug/app-nodrive-debug-androidTest.apk
SETTINGS=org.nourax.nodrive.conflicts.ConflictsSettingsIT
MIGRATION=org.nourax.nodrive.conflicts.ConflictsMigrationIT
UPLOAD=org.nourax.nodrive.conflicts.ConflictsUploadIT
# Test counts: ConflictsSettingsIT 2, ConflictsMigrationIT 2, ConflictsUploadIT 6 (dark look: the 2 that take screenshots).
SHOTS="$OUT/screens/conflicts"
status=0

mkdir -p "$SHOTS"
"$ADB" install -r "$APK" > "$OUT/conflicts-install.log" 2>&1 && "$ADB" install -r -t "$TEST_APK" >> "$OUT/conflicts-install.log" 2>&1 \
    || { cat "$OUT/conflicts-install.log"; echo "conflicts: install failed"; exit 1; }
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS > /dev/null 2>&1 || true
RUNNER=$("$ADB" shell pm list instrumentation | tr -d '\r' \
    | sed -n "s|^instrumentation:\([^ ]*\) (target=$PKG)\$|\1|p" | head -n 1)
[ -n "$RUNNER" ] || { echo "conflicts: no instrumentation for $PKG"; exit 1; }

run_it() { # $1 = look (light|dark), $2 = class list (class or class#method, comma-separated), $3 = log name, $4 = expected tests
    local log="$OUT/conflicts-it-$3-$1.log"
    "$ADB" shell am instrument -w -e class "$2" \
        -e NODRIVE_WS_URL "${NODRIVE_WS_URL:-}" -e NODRIVE_WS_USER "${NODRIVE_WS_USER:-}" \
        -e NODRIVE_CF_TOKEN "${NODRIVE_CF_TOKEN:-}" -e NODRIVE_BK_LOOK "$1" "$RUNNER" 2>&1 | tr -d '\r' > "$log"
    if grep -q "^OK ($4 test" "$log"; then echo "conflicts IT $3 ($1): $(grep '^OK (' "$log")"; else
        echo "conflicts IT $3 ($1): FAIL (expected OK ($4 tests))"; grep -v "^INSTRUMENTATION_" "$log" | tail -n 40 | cut -c1-300
        "$ADB" logcat -d -b crash 2> /dev/null | tail -n 30 | cut -c1-300; status=1; fi
}

"$ADB" shell cmd uimode night no > /dev/null 2>&1 || true
run_it light "$SETTINGS" settings 2
"$ADB" shell cmd uimode night yes > /dev/null 2>&1 || true
run_it dark "$SETTINGS" settings 2
"$ADB" shell cmd uimode night no > /dev/null 2>&1 || true
run_it light "$MIGRATION" migration 2

if [ -n "${NODRIVE_WS_URL:-}" ] && [ -n "${NODRIVE_CF_TOKEN:-}" ]; then
    run_it light "$UPLOAD" upload 6
    "$ADB" shell cmd uimode night yes > /dev/null 2>&1 || true
    run_it dark "$UPLOAD#threeConflictsGiveOneSummaryNotificationAndNoPerFileOne,$UPLOAD#resolveAllKeepBothUploadsACopyNextToEveryOldFile" upload 2
    "$ADB" shell cmd uimode night no > /dev/null 2>&1 || true
    # Independent proof: the files are in the server's own data folder, not only visible to the app.
    docker exec nodrive-ci-server find '/var/www/html/data/citest/files/Phone backup' -name 'nodrive-cf-*' -printf '%f\t%s\n' \
        2> /dev/null | sort > "$OUT/conflicts-server-files.txt"
    if python3 tools/ci/conflicts-server-check.py "$OUT/conflicts-server-files.txt" --looks light,dark | tee "$OUT/conflicts-server-check.txt" \
        | grep -E "^conflicts server check|^  "; then :; fi
    grep -q "^conflicts server check: PASS" "$OUT/conflicts-server-check.txt" || status=1
else
    echo "conflicts: no test server, upload test skipped"
    status=1
fi

for f in $("$ADB" shell ls "/sdcard/Android/data/$PKG/files/" 2> /dev/null | tr -d '\r' | grep '^conflicts-.*\.png$'); do
    "$ADB" pull "/sdcard/Android/data/$PKG/files/$f" "$SHOTS/$f" > /dev/null
done
for name in setting setting-dialog folder-dialog uploads-menu resolve-all notification; do
    for look in light dark; do
        f="conflicts-$name-$look.png"
        if [ -s "$SHOTS/$f" ]; then echo "conflicts screenshot: $f"; else echo "conflicts screenshot MISSING: $f"; status=1; fi
    done
done

"$ADB" uninstall "$PKG.test" > /dev/null 2>&1 || true
"$ADB" uninstall "$PKG" > /dev/null 2>&1 || true
exit "$status"
