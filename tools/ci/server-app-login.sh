#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# CI only. A throwaway Nextcloud server (the same release as drive.nourax.org) in Docker on the runner,
# never drive.nourax.org. Used to prove, on the real server code, how the apps sign the in-app web view in
# (docs: tools/ci/server_login_check.py).
#   start  create the server, one user, app passwords (with and without a login password, the second like
#          a "Log in with Nourax" user), and theme it as "Nourax Workspace" so screenshots show no stock name.
#          Credentials are random, masked in the log and exported through $GITHUB_ENV (never printed).
#   check  run the checks of tools/ci/server_login_check.py against that server (report in build/server-login/).
#   stop   remove the container.
set -euo pipefail

IMAGE="nextcloud:35.0.2-apache@sha256:89af7ee7a96ff5e9668280566209268ae6b2d02327f6ef6d56f6d68228cf9431"
NAME="nodrive-ci-server"
PORT="${NODRIVE_WS_PORT:-8080}"
USER_ID="citest"
OUT="build/server-login"



start() {
    mkdir -p "$OUT"
    local admin_pw login_pw
    admin_pw=$(openssl rand -hex 16); login_pw=$(openssl rand -hex 16)
    echo "::add-mask::$admin_pw"; echo "::add-mask::$login_pw"

    docker rm -f "$NAME" > /dev/null 2>&1 || true
    docker run -d --name "$NAME" -p "$PORT:80" \
        -e SQLITE_DATABASE=nc -e NEXTCLOUD_ADMIN_USER=ciadmin -e "NEXTCLOUD_ADMIN_PASSWORD=$admin_pw" \
        -e NEXTCLOUD_TRUSTED_DOMAINS="localhost 10.0.2.2" "$IMAGE" > /dev/null
    local ok=""
    for _ in $(seq 1 120); do
        if curl -fsS "http://localhost:$PORT/status.php" 2> /dev/null | grep -q '"installed":true'; then ok=1; break; fi
        sleep 3
    done
    if [ -z "$ok" ]; then docker logs "$NAME" 2>&1 | tail -n 30; echo "server did not come up"; exit 1; fi
    curl -fsS "http://localhost:$PORT/status.php" | tee "$OUT/status.json"; echo
    grep -q '"versionstring":"35.0.2"' "$OUT/status.json"

    # The first-run wizard names the stock product; the theme gives the server the Nourax look.
    docker exec -u www-data "$NAME" php occ app:disable firstrunwizard > /dev/null || true
    docker cp branding/nourax-logo.png "$NAME:/tmp/nodrive-logo.png"
    for kv in "name=Nourax Workspace" "slogan=Nourax Workspace" "primary_color=#2563EB" "background_color=#F8FAFC" \
              "logo=/tmp/nodrive-logo.png" "logoheader=/tmp/nodrive-logo.png" "favicon=/tmp/nodrive-logo.png"; do
        docker exec -u www-data "$NAME" php occ theming:config "${kv%%=*}" "${kv#*=}" > /dev/null
    done
    docker exec -u www-data "$NAME" php occ theming:config background backgroundColor > /dev/null

    docker exec -u www-data -e OC_PASS="$login_pw" "$NAME" php occ user:add --password-from-env \
        --display-name "Demo User" "$USER_ID" > /dev/null
    local pw_token sso_token remove_token bk_token cf_token rp_token
    pw_token=$(docker exec -u www-data -e NC_PASS="$login_pw" "$NAME" php occ user:auth-tokens:add \
        --password-from-env --name nodrive-ci "$USER_ID" | tail -n 1 | tr -d '\r')
    echo "::add-mask::$pw_token"
    sso_token=$(docker exec -u www-data "$NAME" php occ -n user:auth-tokens:add --name nodrive-ci-sso "$USER_ID" \
        | tail -n 1 | tr -d '\r')
    echo "::add-mask::$sso_token"
    # A third one for the app test that removes the account (the removal deletes the app password on the server).
    remove_token=$(docker exec -u www-data "$NAME" php occ -n user:auth-tokens:add --name nodrive-ci-removal "$USER_ID" \
        | tail -n 1 | tr -d '\r')
    echo "::add-mask::$remove_token"
    # A fourth one for the backup upload test (tools/ci/backup-device.sh).
    bk_token=$(docker exec -u www-data "$NAME" php occ -n user:auth-tokens:add --name nodrive-ci-backup "$USER_ID" \
        | tail -n 1 | tr -d '\r')
    echo "::add-mask::$bk_token"
    # A fifth one for the Smart conflicts tests (tools/ci/conflicts-device.sh).
    cf_token=$(docker exec -u www-data "$NAME" php occ -n user:auth-tokens:add --name nodrive-ci-conflicts "$USER_ID" \
        | tail -n 1 | tr -d '\r')
    echo "::add-mask::$cf_token"
    # A sixth one for the problem report tests (tools/ci/reports-device.sh). The user nourax-support is NOT made here:
    # that script makes it in the middle of its run, to test the report that ends without the share.
    rp_token=$(docker exec -u www-data "$NAME" php occ -n user:auth-tokens:add --name nodrive-ci-reports "$USER_ID" \
        | tail -n 1 | tr -d '\r')
    echo "::add-mask::$rp_token"
    [ ${#pw_token} -ge 32 ] && [ ${#sso_token} -ge 32 ] && [ ${#remove_token} -ge 32 ] && [ ${#bk_token} -ge 32 ] \
        && [ ${#cf_token} -ge 32 ] && [ ${#rp_token} -ge 32 ] || { echo "app password creation failed"; exit 1; }

    local envfile="$OUT/server.env"
    ( umask 077; {
        echo "NODRIVE_WS_PORT=$PORT"
        echo "NODRIVE_WS_USER=$USER_ID"
        echo "NODRIVE_WS_PW_TOKEN=$pw_token"
        echo "NODRIVE_WS_SSO_TOKEN=$sso_token"
        echo "NODRIVE_WS_REMOVE_TOKEN=$remove_token"
        echo "NODRIVE_BK_TOKEN=$bk_token"
        echo "NODRIVE_CF_TOKEN=$cf_token"
        echo "NODRIVE_RP_TOKEN=$rp_token"
        echo "NODRIVE_WS_LOGIN_PASSWORD=$login_pw"
    } > "$envfile" )
    if [ -n "${GITHUB_ENV:-}" ]; then cat "$envfile" >> "$GITHUB_ENV"; fi
    echo "server up on port $PORT, user $USER_ID, six app passwords created (values masked)"
}

case "${1:-}" in
    start) start ;;
    check)
        mkdir -p "$OUT"
        python3 tools/ci/server_login_check.py --container "$NAME" --out "$OUT"
        ;;
    stop) docker rm -f "$NAME" > /dev/null 2>&1 || true ;;
    *) echo "usage: $0 start|check|stop"; exit 2 ;;
esac
