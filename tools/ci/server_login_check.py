#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Proves, against a throwaway Nextcloud 35.0.2 server started by tools/ci/server-app-login.sh, the sign-in the
apps use for the in-app web view ("native session hand-off", plans/workspace-apps-design.md section 1.4):

1. the app's own request (GET /ocs/v2.php/cloud/user, Basic app password, no redirects) answers 200 with the
   user id and the session cookies;
2. a page request with only those cookies (no Authorization header) is signed in, and is not without the
   session passphrase cookie;
3. the navigation list (the app's own request, Basic header) has the fields the app parses and answers 304 for
   its ETag;
4. no credential or cookie: not signed in; wrong app password: 401, its cookies are not signed in;
5. the web "Log out" leaves the app signed in; after revoking an app password the server's token cache decides how soon
   requests with it end; reported for information only;
6. a web view user agent (platform default + " NoDrive") is not sent to /unsupported (Playwright, Chromium).

Every probe uses its own fresh session, because the server destroys a session that is used wrongly.
Credentials come from the environment (NODRIVE_WS_*), are never printed, and the report holds only names and
status codes. Exit code 1 when a check fails."""
from __future__ import annotations

import argparse
import base64
import http.client
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

# The Android app's API user agent shape (server rule IRequest::USER_AGENT_CLIENT_ANDROID).
APP_UA = "Mozilla/5.0 (Android) Nextcloud-android/35.0.1-nourax.1 NoDrive"
# What the in-app web view sends: the platform default plus " NoDrive" (design rule 7).
WEBVIEW_UA = ("Mozilla/5.0 (Linux; Android 15; Pixel 6 Build/AP3A.241005.015; wv) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.58 Mobile Safari/537.36 NoDrive")
REQUIRED_KEYS = {"id", "href", "icon", "type", "name", "active", "classes", "unread"}
PAGE = "/index.php/apps/dashboard/"
NAV = "/ocs/v2.php/core/navigation/apps?absolute=false&format=json"


class Report:
    def __init__(self) -> None:
        self.rows: list[tuple[str, bool | None, str]] = []

    def check(self, name: str, ok: bool | None, evidence: str) -> bool:
        self.rows.append((name, ok, evidence))
        print(f"[{'PASS' if ok else 'INFO' if ok is None else 'FAIL'}] {name}: {evidence}", flush=True)
        return bool(ok)

    @property
    def failed(self) -> bool:
        return any(ok is False for _, ok, _ in self.rows)

    def markdown(self) -> str:
        lines = ["# Server login proof (Nextcloud 35.0.2, throwaway server)", "",
                 "| Check | Result | Evidence |", "|---|---|---|"]
        for name, ok, ev in self.rows:
            lines.append(f"| {name} | {'pass' if ok else 'info' if ok is None else 'FAIL'} | {ev.replace('|', '/')} |")
        lines += ["", "## Result", "", "FAIL" if self.failed else "PASS", ""]
        return "\n".join(lines)


class Server:
    def __init__(self, port: int) -> None:
        self.port = port

    def request(self, method: str, path: str, headers: dict[str, str] | None = None):
        conn = http.client.HTTPConnection("localhost", self.port, timeout=60)
        try:
            conn.request(method, path, headers=headers or {})
            resp = conn.getresponse()
            body = resp.read().decode("utf-8", "replace")
            set_cookies = resp.msg.get_all("Set-Cookie") or []
            return resp.status, {k.lower(): v for k, v in resp.getheaders()}, set_cookies, body
        finally:
            conn.close()


def basic(user: str, secret: str) -> str:
    return "Basic " + base64.b64encode(f"{user}:{secret}".encode()).decode()


def cookie_pairs(set_cookies: list[str]) -> dict[str, str]:
    """Design rule 5 in short: keep name=value, last wins, drop deletions (expired or empty)."""
    jar: dict[str, str] = {}
    for line in set_cookies:
        parts = [p.strip() for p in line.split(";")]
        name, _, value = parts[0].partition("=")
        attrs = {p.split("=")[0].lower(): p.partition("=")[2] for p in parts[1:]}
        max_age = attrs.get("max-age", "")
        expired = value in ("", "deleted") or (max_age.lstrip("-").isdigit() and int(max_age) <= 0) \
            or "1970" in attrs.get("expires", "")
        if expired:
            jar.pop(name, None)
        else:
            jar[name] = value
    return jar


def cookie_header(jar: dict[str, str], skip: str = "") -> str:
    return "; ".join(f"{k}={v}" for k, v in jar.items() if not skip or skip not in k)


def bootstrap(srv: Server, user: str, secret: str):
    return srv.request("GET", "/ocs/v2.php/cloud/user?format=json", {
        "Authorization": basic(user, secret), "OCS-APIRequest": "true", "Accept": "application/json",
        "User-Agent": APP_UA})


def describe(status: int, headers: dict[str, str], body: str) -> str:
    """Status, redirect path and a short plain-text excerpt: enough to read a failure, no secrets."""
    text = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", body)).strip()[:120]
    loc = headers.get("location", "").split("?")[0][-50:]
    auth = headers.get("www-authenticate", "")[:30]
    return f"status {status}, location {loc or '-'}, www-authenticate {auth or '-'}, body {text!r}"


def signed_in(body: str, user: str) -> bool:
    return f'data-user="{user}"' in body


def page(srv: Server, jar: dict[str, str], skip: str = ""):
    return srv.request("GET", PAGE, {"Cookie": cookie_header(jar, skip), "User-Agent": WEBVIEW_UA})


def fresh_session(srv: Server, user: str, secret: str) -> dict[str, str]:
    status, _, set_cookies, _ = bootstrap(srv, user, secret)
    return cookie_pairs(set_cookies) if status == 200 else {}


def run_container(name: str, args: list[str]) -> str:
    cmd = ["docker", "exec", "-u", "www-data", name, "php", "occ", *args]
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def fixture_keys(repo: Path) -> set[str]:
    data = json.loads((repo / "app/src/test/resources/workspace/navigation-apps.json").read_text("utf-8"))
    return {k for entry in data["ocs"]["data"] for k in entry}


def check_token(srv: Server, rep: Report, label: str, user: str, secret: str, repo: Path) -> None:
    status, _, set_cookies, body = bootstrap(srv, user, secret)
    uid = None
    try:
        uid = json.loads(body)["ocs"]["data"]["id"]
    except Exception:  # noqa: BLE001 - reported below
        pass
    rep.check(f"{label}: app request answers 200 with the user id", status == 200 and uid == user,
              f"status {status}, id matches {uid == user}")
    jar = cookie_pairs(set_cookies)
    names = sorted(jar)
    counts = {n: sum(1 for c in set_cookies if c.split("=", 1)[0].strip() == n) for n in names}

    def has(pattern: str) -> bool:
        return any(re.fullmatch(pattern, n) for n in names)

    rep.check(f"{label}: session cookies handed out", has("oc_sessionPassphrase") and has("oc[a-z0-9]{10}")
              and has(r"(__Host-)?nc_sameSiteCookielax") and has(r"(__Host-)?nc_sameSiteCookiestrict"),
              f"cookie names {names}, Set-Cookie count per name {counts}")
    s1, h1, _, b1 = page(srv, jar)
    rep.check(f"{label}: page with the cookies only (no Authorization header) is signed in",
              s1 == 200 and signed_in(b1, user), describe(s1, h1, "") + f", data-user present {signed_in(b1, user)}")

    other = fresh_session(srv, user, secret)
    s2, h2, _, b2 = page(srv, other, skip="oc_sessionPassphrase")
    rep.check(f"{label}: without oc_sessionPassphrase the page is not signed in", not signed_in(b2, user),
              describe(s2, h2, b2))

    ocs = {"Authorization": basic(user, secret), "OCS-APIRequest": "true", "Accept": "application/json",
           "User-Agent": APP_UA}
    s3, h3, _, b3 = srv.request("GET", NAV, ocs)
    entries = []
    try:
        entries = json.loads(b3)["ocs"]["data"]
    except Exception:  # noqa: BLE001
        pass
    rep.check(f"{label}: navigation list with the app's own request", s3 == 200 and len(entries) > 0,
              f"status {s3}, {len(entries)} entries, ids {[e.get('id') for e in entries][:14]}")
    if label == "S":
        Path("build/server-login").mkdir(parents=True, exist_ok=True)
        Path("build/server-login/navigation-apps.json").write_text(b3, encoding="utf-8")
    known = fixture_keys(repo)
    unknown = sorted({k for e in entries for k in e} - known)
    missing = sorted({k for e in entries for k in REQUIRED_KEYS - set(e)})
    rep.check(f"{label}: navigation entries match the unit-test fixture", not unknown and not missing,
              f"keys not in fixture {unknown}, required keys missing {missing}")
    etag = h3.get("etag", "")
    s4, _, _, _ = srv.request("GET", NAV, {**ocs, "If-None-Match": etag})
    rep.check(f"{label}: navigation list answers 304 for its ETag", bool(etag) and s4 == 304,
              f"etag present {bool(etag)}, status {s4}")
    s5, _, _, b5 = srv.request("GET", NAV.replace("absolute=false", "absolute=true"), ocs)
    absolute = json.loads(b5)["ocs"]["data"] if s5 == 200 else []
    base = f"http://localhost:{srv.port}"
    same = bool(absolute) and [a["href"] for a in absolute] == [base + e["href"] for e in entries]
    rep.check(f"{label}: relative hrefs resolve to the absolute ones", same,
              f"{len(absolute)} absolute entries, first {absolute[0]['href'] if absolute else '-'}")


def check_web_view_user_agent(srv: Server, rep: Report, out: Path, user: str, secret: str) -> None:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        rep.check("web view user agent", None, "playwright not installed")
        return
    jar = fresh_session(srv, user, secret)
    root = f"http://localhost:{srv.port}"
    hops: list[str] = []
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        ctx = browser.new_context(user_agent=WEBVIEW_UA)
        ctx.add_cookies([{"name": n, "value": v, "url": root + "/"} for n, v in jar.items()])
        pg = ctx.new_page()
        pg.on("response", lambda r: hops.append(f"{r.status} {r.url.split('?')[0][-40:]}") if r.status >= 300 else None)
        pg.goto(root + PAGE, wait_until="networkidle")
        final = pg.url.split("?")[0].replace(root, "")
        data_user = pg.evaluate("document.head.dataset.user")
        pg.screenshot(path=str(out / "dashboard-webview-ua.png"))
        browser.close()
    rep.check("web view user agent: dashboard loads, not /unsupported or /login",
              data_user == user and "/unsupported" not in final and "/login" not in final,
              f"final path {final}, data-user matches {data_user == user}, responses >= 300: {hops[:6]}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--container", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    repo = Path(__file__).resolve().parents[2]
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    user = os.environ["NODRIVE_WS_USER"]
    tokens = {"P": os.environ["NODRIVE_WS_PW_TOKEN"], "S": os.environ["NODRIVE_WS_SSO_TOKEN"]}
    srv = Server(int(os.environ.get("NODRIVE_WS_PORT", "8080")))
    rep = Report()

    for label, secret in tokens.items():
        check_token(srv, rep, label, user, secret, repo)

    s, h, _, b = srv.request("GET", PAGE, {"User-Agent": WEBVIEW_UA})
    rep.check("control: no credential and no cookie is not signed in", not signed_in(b, user), describe(s, h, b))
    s, h, sc, b = bootstrap(srv, user, "wrong-" + tokens["P"][:8])
    rep.check("control: wrong app password gives 401 and its cookies are not signed in",
              s == 401 and not signed_in(page(srv, cookie_pairs(sc))[3], user), describe(s, h, b))

    s, h, _, b = srv.request("GET", PAGE, {"Authorization": basic(user, tokens["S"]), "User-Agent": WEBVIEW_UA})
    rep.check("info: page with the Basic header directly (rejected method A)", None,
              f"status {s}, signed in {signed_in(b, user)}")

    jar = fresh_session(srv, user, tokens["S"])
    _, _, _, pg1 = page(srv, jar)
    m = re.search(r'data-requesttoken="([^"]+)"', pg1)
    if m:
        srv.request("GET", "/index.php/logout?requesttoken=" + quote(m.group(1), safe=""),
                    {"Cookie": cookie_header(jar), "User-Agent": WEBVIEW_UA})
    _, _, _, pg2 = page(srv, jar)
    s3, _, _, _ = bootstrap(srv, user, tokens["S"])
    rep.check("web logout ends the web session but the app password still works",
              bool(m) and signed_in(pg1, user) and not signed_in(pg2, user) and s3 == 200,
              f"page signed in {signed_in(pg1, user)}, requesttoken found {bool(m)}, "
              f"web session signed in after logout {signed_in(pg2, user)}, app request {s3}")

    revoke = run_container(args.container, ["user:auth-tokens:add", "--name", "nodrive-ci-revoke", user])
    revoked_secret = revoke.strip().splitlines()[-1].strip()
    jar_r = fresh_session(srv, user, revoked_secret)
    s_before, _, _, b_before = page(srv, jar_r)
    listing = json.loads(run_container(args.container, ["user:auth-tokens:list", user, "--output=json"]))
    tid = next(str(t["id"]) for t in listing if t["name"] == "nodrive-ci-revoke")
    run_container(args.container, ["user:auth-tokens:delete", user, tid])
    s_after, h_after, _, b_after = page(srv, jar_r)
    s_new, _, sc_new, _ = bootstrap(srv, user, revoked_secret)
    rep.check("info: a new app request right after revoking (the server caches tokens for a short time)", None,
              f"before revoking: signed in {signed_in(b_before, user)}; new app request after revoking: {s_new}")
    rep.check("info: an already open web session after revoking", None,
              f"status {s_after}, signed in {signed_in(b_after, user)} (the server re-validates the token at most "
              "every 5 minutes; the app clears its web data on sign-out)")

    check_web_view_user_agent(srv, rep, out, user, tokens["S"])

    (out / "server-login-report.md").write_text(rep.markdown(), encoding="utf-8")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(rep.markdown() + "\n")
    return 1 if rep.failed else 0


if __name__ == "__main__":
    sys.exit(main())
