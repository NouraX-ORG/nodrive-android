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
#   AgendaWorkerIT    the background refresh run by the real WorkManager: an account that is gone has its rows forgotten and its work
#                     cancelled (no server); an account that is there has its saved copy refreshed (throwaway server).
#   AgendaOfflineIT   a1 reads the day, this script turns mobile data off, a2 shows the saved copy with its time, data goes on, a3 cleans up.
#   AgendaEditUiIT    the edit screens on canned data (no server): a clash and an overbooked day, the Conflicts filter, the agent of a task, the
#                     agent that is not in the project, a new event and to-do, the edit conflict, a change that waits offline, the delete
#                     question, a repeating event, an invitation, a task of a viewer project. Light and dark.
#   AgendaEditDatabaseIT  the offline queue in its own Room database: order, restart, conflicts, removing an account.
#   AgendaEditLiveIT  against the throwaway server: a new event with Writer-Bot, a changed one, a ticked to-do, a moved end date of a Gantt
#                     task with Undo, the agent of a Gantt task and of a card, all through the screens.
#   AgendaTimelineIT  the Timeline on canned data (no server): groups by project and phase, % done, arrows, diamonds, shading, the agent on
#                     each item; the date editor in the sheet and its range picker; one day later with Undo; a to-do and a card; a viewer
#                     project's bar and a milestone can not be changed; a change made offline shows "Waiting to send"; the empty state;
#                     the arrows move the range. Light and dark.
#   AgendaTimelineLiveIT  against the throwaway server: Ali's seeded work on the Timeline, a bar moved one day through the date editor
#                     and taken back with Undo (agenda-live-timeline-{light,dark}.png).
#   AgendaEditOfflineIT  a1 makes the data, this script turns data off, a2 makes three changes (marked "Waiting to send"), data goes on,
#                     a3 starts the app again: everything is sent in order. Then tools/ci/agenda-server-check.py asks the server itself.
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
WORKER=org.nourax.nodrive.agenda.AgendaWorkerIT
EDITUI=org.nourax.nodrive.agenda.AgendaEditUiIT
EDITDB=org.nourax.nodrive.agenda.AgendaEditDatabaseIT
EDITLIVE=org.nourax.nodrive.agenda.AgendaEditLiveIT
EDITOFF=org.nourax.nodrive.agenda.AgendaEditOfflineIT
TIMELINE=org.nourax.nodrive.agenda.AgendaTimelineIT
TIMELINELIVE=org.nourax.nodrive.agenda.AgendaTimelineLiveIT
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
# A system app of the emulator image ("googlesdksetup isn't responding") can put an ANR dialog over the app on a busy runner; that dims
# the screenshots and hides the screen from the tests. The emulator's own error dialogs are switched off.
"$ADB" shell settings put global hide_error_dialogs 1 > /dev/null 2>&1 || true
# The setting does not cover every case (the dialog still came up in run 40941449956), so a watchdog closes the dialog of a system app by
# stopping that app ("am force-stop", like tools/ci/device.sh). An ANR of NoDrive itself is left alone.
anr_package() {
    "$ADB" shell dumpsys window 2> /dev/null | tr -d '\r' \
        | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* Application Not Responding: \([^}]*\)}.*/\1/p' | head -n 1
}
anr_watchdog() {
    while true; do
        local pkg
        pkg=$(anr_package)
        if [ -n "$pkg" ] && [ "${pkg%%:*}" != "org.nourax.nodrive" ]; then
            echo "agenda: closing the ANR dialog of $pkg"
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
    run_it "$look" "$EDITUI" edit-ui 13
    run_it "$look" "$TIMELINE" timeline 9
done
night no
run_it light "$EDITDB" edit-database 3
run_it light "$WORKER#anAccountThatIsGoneHasItsRowsForgottenAndItsWorkCancelled" worker-gone 1

# ---- 2. against the throwaway server
if [ -n "${NODRIVE_AG_DEVICE_URL:-}" ] && [ -n "${NODRIVE_AG_ALI_TOKEN:-}" ]; then
    run_it light "$DRAWER" drawer 1
    run_it light "$WORKER#anAccountThatIsThereHasItsSavedCopyRefreshed" worker-present 1
    for look in light dark; do
        if [ "$look" = light ]; then night no; else night yes; fi
        run_it "$look" "$LIVE" live 4
        run_it "$look" "$TIMELINELIVE" timeline-live 1
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

    # the changes through the screens, against the throwaway server
    run_it light "$EDITLIVE" edit-live 4

    # the changes in airplane mode: made without a network, sent when it is back, in order, by a new start of the app
    run_it light "$EDITOFF#a1MakeTheDataAndReadTheDay" edit-offline-a1 1
    "$ADB" shell svc data disable > /dev/null 2>&1
    offline=""
    for _ in $(seq 1 30); do
        if ! has_network; then offline=1; break; fi
        sleep 2
    done
    if [ -n "$offline" ]; then
        run_it light "$EDITOFF#a2ThreeChangesWithoutANetworkWaitOnTheScreen" edit-offline-a2 1
    else
        echo "agenda: FAIL the emulator stayed online after svc data disable (edit offline)"; status=1
    fi
    "$ADB" shell svc data enable > /dev/null 2>&1
    online=""
    for _ in $(seq 1 60); do
        if has_network; then online=1; break; fi
        sleep 2
    done
    [ -n "$online" ] && echo "agenda: network is back (edit offline)" || { echo "agenda: FAIL the network did not come back (edit offline)"; status=1; }
    run_it light "$EDITOFF#a3WhenTheNetworkIsBackEverythingIsSentInOrder" edit-offline-a3 1
    run_it light "$EDITOFF#a4CleanUp" edit-offline-a4 1

    # what the server really holds, asked as the user with plain HTTP (not through the app)
    python3 tools/ci/agenda-server-check.py "$OUT/agenda-server-check.md" > "$OUT/agenda-server-check.log" 2>&1 \
        && echo "agenda: the server holds what the screens sent" \
        || { echo "agenda: FAIL the server does not hold what the screens sent"; cat "$OUT/agenda-server-check.log"; status=1; }
else
    echo "agenda: no throwaway server, the live tests did not run"
    status=1
fi

# ---- 3. the screenshots
for f in $("$ADB" shell ls "/sdcard/Android/data/$PKG/files/" 2> /dev/null | tr -d '\r' | grep '^agenda-.*\.png$'); do
    "$ADB" pull "/sdcard/Android/data/$PKG/files/$f" "$SHOTS/$f" > /dev/null
done
for name in day week month my-team sources registry-missing personal-question detail empty-not-linked offline gantt-down \
            live-day live-my-team live-sources-invalid-registry live-olaf live-timeline; do
    for look in light dark; do
        f="agenda-$name-$look.png"
        if [ -s "$SHOTS/$f" ]; then echo "agenda screenshot: $f"; else echo "agenda screenshot MISSING: $f"; status=1; fi
    done
done
for name in day-clash-overbooked edit-agent-gantt edit-agent-not-in-project new-event edit-conflict pending-offline delete-confirm \
            repeating-locked viewer-locked timeline timeline-date-editor timeline-date-picker timeline-undo timeline-readonly timeline-waiting             timeline-empty; do
    for look in light dark; do
        f="agenda-$name-$look.png"
        if [ -s "$SHOTS/$f" ]; then echo "agenda screenshot: $f"; else echo "agenda screenshot MISSING: $f"; status=1; fi
    done
done
for f in agenda-menu-light.png agenda-offline-live-light.png agenda-edit-live-new-event-light.png agenda-edit-live-delete-light.png \
         agenda-edit-live-todo-light.png agenda-edit-live-gantt-date-light.png agenda-edit-live-agent-task-light.png \
         agenda-edit-offline-waiting-light.png agenda-edit-offline-sent-light.png; do
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
