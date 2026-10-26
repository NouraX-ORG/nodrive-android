#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# CI only, inside the emulator job (called by tools/ci/device.sh). Runs WorkspaceAppsIT against the
# throwaway Nextcloud 35.0.2 server that the workflow started on the runner (10.0.2.2:8080 from the
# emulator), once in the light look and once in the dark look, and pulls the screenshots.
# The test is started with "am instrument" and not through Gradle: Gradle uninstalls the app after a run,
# which would delete the screenshots the test saved on the device.
# Needs NODRIVE_WS_URL, NODRIVE_WS_USER, NODRIVE_WS_SSO_TOKEN and NODRIVE_WS_REMOVE_TOKEN (masked in the log).
set -uo pipefail

ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
OUT=build/device
PKG=org.nourax.nodrive
APK=app/build/outputs/apk/nodrive/debug/app-nodrive-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/nodrive/debug/app-nodrive-debug-androidTest.apk
CLASS=org.nourax.nodrive.workspace.WorkspaceAppsIT
SHOTS="$OUT/screens/workspace"
status=0

if [ -z "${NODRIVE_WS_URL:-}" ]; then echo "workspace: no test server, skipped"; exit 0; fi
mkdir -p "$SHOTS"

"$ADB" install -r "$APK" > "$OUT/workspace-install.log" 2>&1 && "$ADB" install -r -t "$TEST_APK" >> "$OUT/workspace-install.log" 2>&1 \
    || { cat "$OUT/workspace-install.log"; echo "workspace: install failed"; exit 1; }
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS > /dev/null 2>&1 || true
RUNNER=$("$ADB" shell pm list instrumentation | tr -d '\r' \
    | sed -n "s|^instrumentation:\([^ ]*\) (target=$PKG)\$|\1|p" | head -n 1)
[ -n "$RUNNER" ] || { echo "workspace: no instrumentation for $PKG"; exit 1; }

run_it() { # $1 = look (light|dark), $2 = class or class#method
    local log="$OUT/workspace-it-$1.log"
    "$ADB" shell am instrument -w -e class "$2" \
        -e NODRIVE_WS_URL "$NODRIVE_WS_URL" -e NODRIVE_WS_USER "$NODRIVE_WS_USER" \
        -e NODRIVE_WS_APP_PASSWORD "$NODRIVE_WS_SSO_TOKEN" -e NODRIVE_WS_REMOVE_PASSWORD "$NODRIVE_WS_REMOVE_TOKEN" \
        -e NODRIVE_WS_LOOK "$1" "$RUNNER" 2>&1 | tr -d '\r' > "$log"
    if grep -q "^OK (" "$log"; then echo "workspace IT ($1): $(grep '^OK (' "$log")"; else
        echo "workspace IT ($1): FAIL"; grep -v "^INSTRUMENTATION_" "$log" | tail -n 40 | cut -c1-300; status=1; fi
}

"$ADB" shell cmd uimode night no > /dev/null 2>&1 || true
run_it light "$CLASS"
"$ADB" shell cmd uimode night yes > /dev/null 2>&1 || true
run_it dark "$CLASS#menuListsTheWorkspaceAppsAndTapOpensASignedInPage"
"$ADB" shell cmd uimode night no > /dev/null 2>&1 || true

for f in $("$ADB" shell ls "/sdcard/Android/data/$PKG/files/" 2> /dev/null | tr -d '\r' | grep '^workspace-.*\.png$'); do
    "$ADB" pull "/sdcard/Android/data/$PKG/files/$f" "$SHOTS/$f" > /dev/null
done
for f in workspace-menu-light.png workspace-page-light.png workspace-menu-dark.png workspace-page-dark.png; do
    if [ -s "$SHOTS/$f" ]; then echo "workspace screenshot: $f"; else echo "workspace screenshot MISSING: $f"; status=1; fi
done

"$ADB" uninstall "$PKG.test" > /dev/null 2>&1 || true
"$ADB" uninstall "$PKG" > /dev/null 2>&1 || true
exit "$status"
