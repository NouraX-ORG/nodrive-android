#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# CI only, inside the "NoAgenda on the emulator" job (reactivecircus/android-emulator-runner). Runs the NoAgenda tests of the nodrive
# flavour with "am instrument" (not through Gradle, which uninstalls the app and with it the screenshots):
#   AgendaUiIT        the screens on canned data (no server): Day, Week, Month, My team, Sources, the one-time question, the detail
#                     sheet, the empty and error states. Light and dark. No visible text names the server software.
#   AgendaDatabaseIT  the offline cache in a real Room database.
#   AgendaDrawerIT    against the throwaway server: "NoAgenda" is the first item of the Apps group, above the server's apps.
#   AgendaLiveIT      against the throwaway server (tools/ci/agenda-server.sh, seeded): Ali's day with his agents, the manager's team
#                     problems, an invalid agent list, nothing of another organisation for Olaf; "Open in ..." opens the signed-in page.
#   AgendaOfflineIT   a1 reads the day, this script turns mobile data off, a2 shows the saved copy with its time, data goes on, a3 cleans up.
# Pulls agenda-*.png to build/device/screens/agenda/, fails when an expected one is missing, and checks that the dark screenshots
# are dark and the light ones are light.
set -uo pipefail

ADB="${ADB:-$ANDROID_HOME/platform-tools/adb}"
OUT=build/device
PKG=org.nourax.nodrive
APK=app/build/outputs/apk/nodrive/debug/app-nodrive-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/nodrive/debug/app-nodrive-debug-androidTest.apk
UI=org.nourax.nodrive.agenda.AgendaUiIT
DB=org.nourax.nodrive.agenda.AgendaDatabaseIT
DRAWER=org.nourax.nodrive.agenda.AgendaDrawerIT
LIVE=org.nourax.nodrive.agenda.AgendaLiveIT
OFFLINE=org.nourax.nodrive.agenda.AgendaOfflineIT
SHOTS="$OUT/screens/agenda"
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
"$ADB" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard > /dev/null 2>&1 || true

"$ADB" install -r "$APK" > "$OUT/agenda-install.log" 2>&1 && "$ADB" install -r -t "$TEST_APK" >> "$OUT/agenda-install.log" 2>&1 \
    || { cat "$OUT/agenda-install.log"; echo "agenda: install failed"; exit 1; }
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS > /dev/null 2>&1 || true
RUNNER=$("$ADB" shell pm list instrumentation | tr -d '\r' \
    | sed -n "s|^instrumentation:\([^ ]*\) (target=$PKG)\$|\1|p" | head -n 1)
[ -n "$RUNNER" ] || { echo "agenda: no instrumentation for $PKG"; exit 1; }

night() { "$ADB" shell cmd uimode night "$1" > /dev/null 2>&1 || true; }
has_network() { "$ADB" shell dumpsys connectivity 2> /dev/null | tr -d '\r' | grep -Eq '^Active default network: [0-9]+'; }

# $1 = look (light|dark), $2 = class or class#method, $3 = log name, $4 = expected number of tests
run_it() {
    local log="$OUT/agenda-it-$3-$1.log"
    "$ADB" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1 || true
    "$ADB" shell am instrument -w -e class "$2" \
        -e NODRIVE_AG_URL "${NODRIVE_AG_DEVICE_URL:-}" \
        -e NODRIVE_AG_ALI_TOKEN "${NODRIVE_AG_ALI_TOKEN:-}" -e NODRIVE_AG_MARA_TOKEN "${NODRIVE_AG_MARA_TOKEN:-}" \
        -e NODRIVE_AG_SARA_TOKEN "${NODRIVE_AG_SARA_TOKEN:-}" -e NODRIVE_AG_OLAF_TOKEN "${NODRIVE_AG_OLAF_TOKEN:-}" \
        -e NODRIVE_BK_LOOK "$1" "$RUNNER" 2>&1 | tr -d '\r' > "$log"
    if grep -q "^OK ($4 test" "$log"; then echo "agenda IT $3 ($1): $(grep '^OK (' "$log")"; else
        echo "agenda IT $3 ($1): FAIL (expected OK ($4 tests))"; grep -v "^INSTRUMENTATION_" "$log" | tail -n 60 | cut -c1-300
        "$ADB" logcat -d -b crash 2> /dev/null | tail -n 30 | cut -c1-300; status=1; fi
}

# ---- 1. the screens on canned data, and the cache (no server)
for look in light dark; do
    if [ "$look" = light ]; then night no; else night yes; fi
    run_it "$look" "$UI" ui 13
    run_it "$look" "$DB" database 3
done
night no

# ---- 2. against the throwaway server
if [ -n "${NODRIVE_AG_DEVICE_URL:-}" ] && [ -n "${NODRIVE_AG_ALI_TOKEN:-}" ]; then
    run_it light "$DRAWER" drawer 1
    for look in light dark; do
        if [ "$look" = light ]; then night no; else night yes; fi
        run_it "$look" "$LIVE" live 4
    done
    night no
    # offline: the saved copy of Ali's day, with the time it was read
    run_it light "$OFFLINE#a1ReadTheDayAndKeepTheSavedCopy" offline-a1 1
    "$ADB" shell svc data disable > /dev/null 2>&1
    offline=""
    for _ in $(seq 1 30); do
        if ! has_network; then offline=1; break; fi
        sleep 2
    done
    if [ -n "$offline" ]; then
        run_it light "$OFFLINE#a2WithoutANetworkTheSavedDayIsShownWithItsTime" offline-a2 1
    else
        echo "agenda: FAIL the emulator stayed online after svc data disable"; status=1
    fi
    "$ADB" shell svc data enable > /dev/null 2>&1
    online=""
    for _ in $(seq 1 60); do
        if has_network; then online=1; break; fi
        sleep 2
    done
    [ -n "$online" ] && echo "agenda: network is back" || { echo "agenda: FAIL the network did not come back"; status=1; }
    run_it light "$OFFLINE#a3CleanUp" offline-a3 1
else
    echo "agenda: no throwaway server, the live tests did not run"
    status=1
fi

# ---- 3. the screenshots
for f in $("$ADB" shell ls "/sdcard/Android/data/$PKG/files/" 2> /dev/null | tr -d '\r' | grep '^agenda-.*\.png$'); do
    "$ADB" pull "/sdcard/Android/data/$PKG/files/$f" "$SHOTS/$f" > /dev/null
done
for name in day week month my-team sources registry-missing personal-question detail empty-not-linked offline gantt-down \
            live-day live-my-team live-sources-invalid-registry live-olaf; do
    for look in light dark; do
        f="agenda-$name-$look.png"
        if [ -s "$SHOTS/$f" ]; then echo "agenda screenshot: $f"; else echo "agenda screenshot MISSING: $f"; status=1; fi
    done
done
for f in agenda-menu-light.png agenda-offline-live-light.png; do
    if [ -s "$SHOTS/$f" ]; then echo "agenda screenshot: $f"; else echo "agenda screenshot MISSING: $f"; status=1; fi
done

# The dark screens are dark and the light ones are light (the theme really switched).
python3 - "$SHOTS" <<'EOF' || status=1
import sys
from pathlib import Path
from PIL import Image, ImageStat

bad = 0
shots = sorted(Path(sys.argv[1]).glob("agenda-*.png"))
for path in shots:
    mean = ImageStat.Stat(Image.open(path).convert("L")).mean[0]
    dark = path.stem.endswith("-dark")
    ok = mean < 100 if dark else mean > 120
    if not ok:
        bad += 1
        print(f"agenda pixel check FAIL: {path.name} mean luminance {mean:.0f}")
print(f"agenda pixel check: {len(shots)} screenshots, {bad} with the wrong brightness")
sys.exit(1 if bad else 0)
EOF

"$ADB" uninstall "$PKG.test" > /dev/null 2>&1 || true
"$ADB" uninstall "$PKG" > /dev/null 2>&1 || true
exit "$status"
