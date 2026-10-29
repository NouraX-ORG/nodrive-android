#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# CI only. A throwaway Nextcloud 35 (the same release as the other CI servers) in Docker on the runner, with the calendar, tasks,
# Deck and nc_gantt 2.4.1 apps (pinned tarballs, SHA-256 checked), seeded with the data of plans/noagenda-decisions.md section 12.
# NEVER drive.nourax.org: the server contacts no app store and no other host (see the occ settings below).
#   start      create the server, the apps, the users (mara manager; ali, sara staff in org-a; olaf in org-b), run the seed and
#              make one app password per user. The values are random, masked in the log and written only to a 0600 file in
#              $RUNNER_TEMP and to $GITHUB_ENV, never printed and never in the folder that is uploaded.
#   leakcheck  fail when a throwaway value is found in the files of the given folders (the artifacts).
#   stop       remove the container.
# Names, status codes and counts only go into the report (build/agenda-server/server-report.md).
set -euo pipefail

IMAGE="nextcloud:35.0.2-apache@sha256:89af7ee7a96ff5e9668280566209268ae6b2d02327f6ef6d56f6d68228cf9431"
NAME="nodrive-agenda-server"
PORT="${NODRIVE_AG_PORT:-8080}"
OUT="build/agenda-server"
TMP="${RUNNER_TEMP:-/tmp}/agenda-server"

# Pinned apps (SHA-256 of the tarballs).
GANTT_URL="https://codeberg.org/fds2610/NC-Gantt/releases/download/v2.4.1-9a09070/nc_gantt-2.4.1-9a09070.tar.gz"
GANTT_SHA="9003904a4398f94f1870cc93e6a129d6c7e72298de05b3c5d0104efa976bcfb9"
DECK_URL="https://github.com/nextcloud-releases/deck/releases/download/v1.19.1/deck-v1.19.1.tar.gz"
DECK_SHA="382e11139ee399444bdfa17f018aa9109376928f9bd36090ead8ea340b4d3f0e"
CALENDAR_URL="https://github.com/nextcloud-releases/calendar/releases/download/v6.6.4/calendar-v6.6.4.tar.gz"
CALENDAR_SHA="0557b6b5852a467cf3f746e59d56478d227f78bf7a66357b30dfc10cae4c7e3c"
TASKS_URL="https://github.com/nextcloud/tasks/releases/download/v0.18.1/tasks.tar.gz"
TASKS_SHA="0c988d50531c9bfa246e6c31f3f95369adde1629ba70d15194915ab56e24687b"

FAILED=0
REPORT="$OUT/server-report.md"
say() { printf '%s\n' "$*" | tee -a "$REPORT"; }
pass() { say "| $1 | PASS | $2 |"; }
fail() { say "| $1 | FAIL | $2 |"; FAILED=1; }
check() { if [ "$2" = 0 ]; then pass "$1" "$3"; else fail "$1" "$3"; fi; }
occ() { docker exec -u www-data "$NAME" php occ "$@"; }

start() {
    rm -rf "$OUT" "$TMP"
    mkdir -p "$OUT" "$TMP/apps" "$TMP/work"
    chmod 700 "$TMP"
    {
        echo "# NoAgenda against a throwaway server"
        echo
        echo "Image: nextcloud 35.0.2. Names, status codes and counts only; no passwords, no values of private fields."
        echo
        echo "| Check | Result | Detail |"
        echo "|---|---|---|"
    } > "$REPORT"

    local pw_admin pw_mara pw_ali pw_sara pw_olaf
    pw_admin=$(openssl rand -hex 24); pw_mara=$(openssl rand -hex 24); pw_ali=$(openssl rand -hex 24)
    pw_sara=$(openssl rand -hex 24); pw_olaf=$(openssl rand -hex 24)
    for v in "$pw_admin" "$pw_mara" "$pw_ali" "$pw_sara" "$pw_olaf"; do echo "::add-mask::$v"; done
    ( umask 077
      printf 'NEXTCLOUD_ADMIN_PASSWORD=%s\n' "$pw_admin" > "$TMP/server.env"
      printf '%s\n' "$pw_admin" "$pw_mara" "$pw_ali" "$pw_sara" "$pw_olaf" > "$TMP/forbidden.txt" )

    # ---- the apps (downloaded on the runner, checked, then copied into the container) ----
    fetch_app() { # name url sha
        local name=$1 url=$2 sha=$3 file="$TMP/apps/$1.tar.gz" got
        curl -fsSL --retry 3 -o "$file" "$url"
        got=$(sha256sum "$file" | cut -d' ' -f1)
        if [ "$got" != "$sha" ]; then fail "app-$name-sha256" "expected $sha, got $got"; exit 1; fi
        mkdir -p "$TMP/apps/$name"
        tar -xzf "$file" -C "$TMP/apps/$name"
        pass "app-$name-sha256" "$sha"
    }
    fetch_app nc_gantt "$GANTT_URL" "$GANTT_SHA"
    fetch_app deck "$DECK_URL" "$DECK_SHA"
    fetch_app calendar "$CALENDAR_URL" "$CALENDAR_SHA"
    fetch_app tasks "$TASKS_URL" "$TASKS_SHA"

    # ---- the server ----
    docker rm -f "$NAME" > /dev/null 2>&1 || true
    docker run -d --name "$NAME" -p "$PORT:80" -e SQLITE_DATABASE=nc -e NEXTCLOUD_ADMIN_USER=ciadmin \
        --env-file "$TMP/server.env" -e NEXTCLOUD_TRUSTED_DOMAINS="localhost 10.0.2.2 127.0.0.1" "$IMAGE" > /dev/null
    local ok=""
    for _ in $(seq 1 120); do
        if curl -fsS "http://localhost:$PORT/status.php" 2> /dev/null | grep -q '"installed":true'; then ok=1; break; fi
        sleep 3
    done
    if [ -z "$ok" ]; then docker logs "$NAME" 2>&1 | tail -n 30; echo "server did not come up"; exit 1; fi
    local version
    version=$(curl -fsS "http://localhost:$PORT/status.php" | python3 -c 'import json,sys; print(json.load(sys.stdin)["versionstring"])')
    check server-version "$([ "$version" = 35.0.2 ] && echo 0 || echo 1)" "versionstring $version"

    # The throwaway server contacts no app store and no other host.
    occ config:system:set appstoreenabled --value=false --type=boolean > /dev/null
    occ config:system:set has_internet_connection --value=false --type=boolean > /dev/null
    occ app:disable firstrunwizard > /dev/null 2>&1 || true
    for app in nc_gantt deck calendar tasks; do
        docker cp "$TMP/apps/$app/$app" "$NAME:/var/www/html/custom_apps/$app" > /dev/null
        docker exec "$NAME" chown -R www-data:www-data "/var/www/html/custom_apps/$app"
    done
    occ app:enable calendar tasks deck nc_gantt > /dev/null
    local versions
    versions=$(occ app:list --output=json | python3 -c '
import json,sys
d=json.load(sys.stdin)["enabled"]
print(" ".join("%s=%s" % (a, d.get(a, "missing")) for a in ("calendar","tasks","deck","nc_gantt")))')
    check pinned-app-versions "$([ "$versions" = "calendar=6.6.4 tasks=0.18.1 deck=1.19.1 nc_gantt=2.4.1" ] && echo 0 || echo 1)" "$versions"

    # ---- users and calendars ----
    add_user() { # uid password display
        docker exec -u www-data -e OC_PASS="$2" "$NAME" php occ user:add --password-from-env --display-name "$3" "$1" > /dev/null
        occ user:setting "$1" settings email "$1@example.test" > /dev/null
    }
    add_user mara "$pw_mara" Mara
    add_user ali "$pw_ali" Ali
    add_user sara "$pw_sara" Sara
    add_user olaf "$pw_olaf" Olaf
    occ group:add org-a > /dev/null
    occ group:add org-b > /dev/null
    for u in mara ali sara; do occ group:adduser org-a "$u" > /dev/null; done
    occ group:adduser org-b olaf > /dev/null
    check users 0 "mara (manager), ali and sara (staff) in org-a, olaf in org-b"
    occ dav:create-calendar ali personal > /dev/null 2>&1 || true
    occ dav:create-calendar ali work > /dev/null
    occ dav:create-calendar mara team > /dev/null

    # ---- the seed ----
    SERVER_URL="http://127.0.0.1:$PORT" PASS_ADMIN="$pw_admin" PASS_MARA="$pw_mara" PASS_ALI="$pw_ali" PASS_SARA="$pw_sara" PASS_OLAF="$pw_olaf" \
        python3 tools/ci/agenda-seed.py "$OUT" | tee "$TMP/seed.log"
    pass seed "$(tail -n 1 "$TMP/seed.log")"

    # A subscription calendar (never fetched: the server has no outside access) and a contact with a birthday.
    local code bday
    code=$(curl -sS -o /dev/null -w '%{http_code}' -u "ali:$pw_ali" -X MKCOL -H 'Content-Type: application/xml' \
        --data '<d:mkcol xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"><d:set><d:prop><d:resourcetype><d:collection/><cs:subscribed/></d:resourcetype><d:displayname>Local holidays</d:displayname><cs:source><d:href>http://holidays-feed.invalid/holidays.ics</d:href></cs:source></d:prop></d:set></d:mkcol>' \
        "http://127.0.0.1:$PORT/remote.php/dav/calendars/ali/holidays" || true)
    check subscription-calendar "$([ "$code" = 201 ] && echo 0 || echo 1)" "MKCOL subscription: HTTP $code"
    bday=$(date -u +%m%d)
    code=$(curl -sS -o /dev/null -w '%{http_code}' -u "ali:$pw_ali" -X PUT -H 'Content-Type: text/vcard' \
        --data "$(printf 'BEGIN:VCARD\r\nVERSION:3.0\r\nUID:ci-contact-1\r\nFN:Sara Contact\r\nN:Contact;Sara;;;\r\nBDAY:1990%s\r\nEND:VCARD\r\n' "$bday")" \
        "http://127.0.0.1:$PORT/remote.php/dav/addressbooks/users/ali/contacts/ci-contact-1.vcf" || true)
    check contact "$([ "$code" = 201 ] && echo 0 || echo 1)" "PUT contact with a birthday: HTTP $code"
    occ dav:sync-birthday-calendar ali > /dev/null 2>&1 || true

    # ---- one app password per user, made the way the sign-in makes it ----
    app_password() { # uid password
        docker exec -u www-data -e NC_PASS="$2" "$NAME" php occ user:auth-tokens:add --password-from-env --name nodrive-ci "$1" | tail -n 1 | tr -d '\r\n '
    }
    local t_ali t_mara t_sara t_olaf
    t_ali=$(app_password ali "$pw_ali"); t_mara=$(app_password mara "$pw_mara"); t_sara=$(app_password sara "$pw_sara"); t_olaf=$(app_password olaf "$pw_olaf")
    for v in "$t_ali" "$t_mara" "$t_sara" "$t_olaf"; do
        [ ${#v} -ge 32 ] || { fail app-passwords "an app password was not made"; exit 1; }
        echo "::add-mask::$v"
        printf '%s\n' "$v" >> "$TMP/forbidden.txt"
    done
    check app-passwords 0 "four app passwords made by occ"
    # Outside the folder that is uploaded.
    ( umask 077; {
        echo "NODRIVE_AG_URL=http://localhost:$PORT"
        echo "NODRIVE_AG_ALI_TOKEN=$t_ali"
        echo "NODRIVE_AG_MARA_TOKEN=$t_mara"
        echo "NODRIVE_AG_SARA_TOKEN=$t_sara"
        echo "NODRIVE_AG_OLAF_TOKEN=$t_olaf"
    } > "$TMP/agenda.env" )
    if [ -n "${GITHUB_ENV:-}" ]; then cat "$TMP/agenda.env" >> "$GITHUB_ENV"; fi
    docker logs "$NAME" 2>&1 | grep -c "" > /dev/null || true
    cat "$REPORT" >> "${GITHUB_STEP_SUMMARY:-/dev/null}" || true
    echo "server up on port $PORT, five throwaway users, four app passwords (values masked)"
    exit "$FAILED"
}

leakcheck() {
    local leak=0 value
    [ -f "$TMP/forbidden.txt" ] || { echo "no list of throwaway values"; exit 1; }
    while IFS= read -r value; do
        [ -n "$value" ] || continue
        # The packet capture is not text; everything else that is uploaded must not hold a value.
        if grep -rlaF --exclude='*.pcap' -e "$value" "$@" 2> /dev/null | grep -q .; then leak=1; fi
    done < "$TMP/forbidden.txt"
    if [ "$leak" = 1 ]; then echo "LEAK: a throwaway password or token is in a file that gets uploaded"; exit 1; fi
    echo "no throwaway value found in: $*"
}

case "${1:-}" in
    start) start ;;
    leakcheck) shift; leakcheck "$@" ;;
    stop) docker rm -f "$NAME" > /dev/null 2>&1 || true ;;
    *) echo "usage: $0 start|leakcheck <folders>|stop"; exit 2 ;;
esac
