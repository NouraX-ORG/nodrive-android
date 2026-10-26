#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Proves, against a throwaway Nextcloud 35.0.2 server started by tools/ci/server-app-login.sh, the sign-in the
apps use for the in-app web view ("native session hand-off", plans/workspace-apps-design.md section 1.4):

1. the app's own request (GET /ocs/v2.php/cloud/user, Basic app password, no redirects) answers 200 with the
   user id and the session cookies;
2. a page request with only those cookies (no Authorization header) is signed in, and is not without the
   session passphrase cookie;
3. the navigation list works with the cookies alone, has the fields the app parses, and answers 304 for its ETag;
4. no credential or cookie: redirect to the login page; wrong app password: 401 and no cookie;
5. the web "Log out" leaves the app signed in; revoking the app password ends the web session at once;
6. a web view user agent (platform default + " NoDrive") is not sent to /unsupported (Playwright, Chromium).

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
import time
from pathlib import Path

# The Android app's API user agent shape (server rule IRequest::USER_AGENT_CLIENT_ANDROID).
APP_UA = "Mozilla/5.0 (Android) Nextcloud-android/35.0.1-nourax.1 NoDrive"
# What the in-app web view sends: the platform default plus " NoDrive" (design rule 7).
WEBVIEW_UA = ("Mozilla/5.0 (Linux; Android 15; Pixel 6 Build/AP3A.241005.015; wv) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.58 Mobile Safari/537.36 NoDrive")
REQUIRED_KEYS = {"id", "href", "icon", "type", "name", "active", "classes", "unread"}


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
        expired = value in ("", "deleted") or attrs.get("max-age", "1").lstrip("-").isdigit() and \
            int(attrs.get("max-age", "1")) <= 0 or "1970" in attrs.get("expires", "")
        if expired:
            jar.pop(name, None)
        else:
            jar[name] = value
    return jar


def cookie_header(jar: dict[str, str], skip: str = "") -> str:
    return "; ".join(f"{k}={v}" for k, v in jar.items() if not skip or skip not in k)


def bootstrap(srv: Server, user: str, secret: str):
    status, headers, set_cookies, body = srv.request("GET", "/ocs/v2.php/cloud/user?format=json", {
        "Authorization": basic(user, secret), "OCS-APIRequest": "true", "Accept": "application/json",
        "User-Agent": APP_UA})
    return status, headers, set_cookies, body


def signed_in(body: str, user: str) -> bool:
    return f'data-user="{user}"' in body


def run_container(name: str, args: list[str], env: dict[str, str] | None = None) -> str:
    cmd = ["docker", "exec", "-u", "www-data"]
    for k in (env or {}):
        cmd += ["-e", k]
    cmd += [name, "php", "occ", *args]
    return subprocess.run(cmd, check=True, capture_output=True, text=True, env={**os.environ, **(env or {})}).stdout


def fixture_keys(repo: Path) -> set[str]:
    data = json.loads((repo / "app/src/test/resources/workspace/navigation-apps.json").read_text("utf-8"))
    return {k for entry in data["ocs"]["data"] for k in entry}


def check_token(srv: Server, rep: Report, label: str, user: str, secret: str, repo: Path) -> dict[str, str] | None:
    status, _, set_cookies, body = bootstrap(srv, user, secret)
    ok = status == 200
    uid = None
    try:
        uid = json.loads(body)["ocs"]["data"]["id"]
    except Exception:  # noqa: BLE001 - reported below
        pass
    rep.check(f"{label}: app request answers 200 with the user id", ok and uid == user,
              f"status {status}, id matches {uid == user}")
    jar = cookie_pairs(set_cookies)
    names = sorted(jar)
    counts = {n: sum(1 for c in set_cookies if c.split("=", 1)[0].strip() == n) for n in names}
    has = lambda pat: any(re.fullmatch(pat, n) for n in names)  # noqa: E731
    rep.check(f"{label}: session cookies handed out", has("oc_sessionPassphrase") and has("oc[a-z0-9]{10}")
              and has(r"(__Host-)?nc_sameSiteCookielax") and has(r"(__Host-)?nc_sameSiteCookiestrict"),
              f"cookie names {names}, Set-Cookie count per name {counts}")
    if not (ok and uid == user):
        return None

    page = "/index.php/apps/dashboard/"
    s1, h1, _, b1 = srv.request("GET", page, {"Cookie": cookie_header(jar), "User-Agent": WEBVIEW_UA})
    rep.check(f"{label}: page with the cookies only is signed in", s1 == 200 and signed_in(b1, user),
              f"status {s1}, data-user present {signed_in(b1, user)}")
    s2, h2, _, b2 = srv.request("GET", page, {"Cookie": cookie_header(jar, "oc_sessionPassphrase"),
                                              "User-Agent": WEBVIEW_UA})
    rep.check(f"{label}: without oc_sessionPassphrase the page is not signed in",
              not signed_in(b2, user), f"status {s2}, location {h2.get('location', '-')[:60]}")

    nav_path = "/ocs/v2.php/core/navigation/apps?absolute=false&format=json"
    ocs = {"Cookie": cookie_header(jar), "OCS-APIRequest": "true", "Accept": "application/json",
           "User-Agent": APP_UA}
    s3, h3, _, b3 = srv.request("GET", nav_path, ocs)
    entries = []
    try:
        entries = json.loads(b3)["ocs"]["data"]
    except Exception:  # noqa: BLE001
        pass
    rep.check(f"{label}: navigation list with the cookies only", s3 == 200 and len(entries) > 0,
              f"status {s3}, {len(entries)} entries, ids {[e.get('id') for e in entries][:12]}")
    if label == "S":
        Path("build/server-login").mkdir(parents=True, exist_ok=True)
        Path("build/server-login/navigation-apps.json").write_text(b3, encoding="utf-8")
    known = fixture_keys(repo)
    unknown = sorted({k for e in entries for k in e} - known)
    missing = sorted({k for e in entries for k in REQUIRED_KEYS - set(e)})
    rep.check(f"{label}: navigation entries match the unit-test fixture", not unknown and not missing,
              f"keys not in fixture {unknown}, required keys missing {missing}")
    etag = h3.get("etag", "")
    s4, _, _, _ = srv.request("GET", nav_path, {**ocs, "If-None-Match": etag})
    rep.check(f"{label}: navigation list answers 304 for its ETag", bool(etag) and s4 == 304,
              f"etag present {bool(etag)}, status {s4}")
    s5, _, _, b5 = srv.request("GET", nav_path.replace("absolute=false", "absolute=true"), ocs)
    absolute = json.loads(b5)["ocs"]["data"] if s5 == 200 else []
    base = f"http://localhost:{srv.port}"
    same = [a["href"] for a in absolute] == [base + e["href"] for e in entries] if absolute else False
    rep.check(f"{label}: relative hrefs resolve to the absolute ones", same,
              f"{len(absolute)} absolute entries, first {absolute[0]['href'] if absolute else '-'}")
    return jar


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

    jars: dict[str, dict[str, str] | None] = {}
    for label, secret in tokens.items():
        jars[label] = check_token(srv, rep, label, user, secret, repo)

    # Controls.
    s, h, _, _ = srv.request("GET", "/index.php/apps/dashboard/", {"User-Agent": WEBVIEW_UA})
    rep.check("control: no credential and no cookie goes to the login page", s in (302, 303) and "/login" in h.get("location", ""),
              f"status {s}, location path {h.get('location', '-').split('?')[0][-40:]}")
    s, _, sc, _ = bootstrap(srv, user, "wrong-" + tokens["P"][:8])
    rep.check("control: wrong app password gives 401 and no session cookie",
              s == 401 and "oc_sessionPassphrase" not in cookie_pairs(sc), f"status {s}, cookie names {sorted(cookie_pairs(sc))}")

    # Informational: the same page with the Basic header directly (the method the design rejects).
    s, _, _, b = srv.request("GET", "/index.php/apps/dashboard/", {"Authorization": basic(user, tokens["S"]),
                                                                  "User-Agent": WEBVIEW_UA})
    rep.check("info: page with the Basic header directly (rejected method A)", None,
              f"status {s}, signed in {signed_in(b, user)}")

    # Web logout keeps the app signed in.
    jar = jars["S"]
    if jar:
        s, _, _, page = srv.request("GET", "/index.php/apps/dashboard/", {"Cookie": cookie_header(jar), "User-Agent": WEBVIEW_UA})
        m = re.search(r'data-requesttoken="([^"]+)"', page)
        if m:
            from urllib.parse import quote
            srv.request("GET", "/index.php/logout?requesttoken=" + quote(m.group(1), safe=""),
                        {"Cookie": cookie_header(jar), "User-Agent": WEBVIEW_UA})
        s2, _, _, page2 = srv.request("GET", "/index.php/apps/dashboard/", {"Cookie": cookie_header(jar), "User-Agent": WEBVIEW_UA})
        s3, _, _, _ = bootstrap(srv, user, tokens["S"])
        rep.check("web logout ends the web session but the app password still works",
                  bool(m) and not signed_in(page2, user) and s3 == 200,
                  f"requesttoken found {bool(m)}, web session signed in after logout {signed_in(page2, user)}, app request {s3}")

    # Revoking an app password ends the web session at once.
    revoke = run_container(args.container, ["user:auth-tokens:add", "--name", "nodrive-ci-revoke", user])
    revoke_secret = revoke.strip().splitlines()[-1].strip()
    s, _, sc, _ = bootstrap(srv, user, revoke_secret)
    jar_r = cookie_pairs(sc)
    s_before, _, _, b_before = srv.request("GET", "/index.php/apps/dashboard/", {"Cookie": cookie_header(jar_r), "User-Agent": WEBVIEW_UA})
    listing = json.loads(run_container(args.container, ["user:auth-tokens:list", user, "--output=json"]))
    tid = next(str(t["id"]) for t in listing if t["name"] == "nodrive-ci-revoke")
    run_container(args.container, ["user:auth-tokens:delete", user, tid])
    s_after, _, _, b_after = srv.request("GET", "/index.php/apps/dashboard/", {"Cookie": cookie_header(jar_r), "User-Agent": WEBVIEW_UA})
    rep.check("revoking the app password ends its web session at once",
              signed_in(b_before, user) and not signed_in(b_after, user),
              f"before: {s_before} signed in {signed_in(b_before, user)}; after: {s_after} signed in {signed_in(b_after, user)}")

    # The web view user agent is not sent to /unsupported.
    if jars.get("S"):
        try:
            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw:
                browser = pw.chromium.launch()
                ctx = browser.new_context(user_agent=WEBVIEW_UA)
                ctx.add_cookies([{"name": n, "value": v, "url": f"http://localhost:{srv.port}/"}
                                 for n, v in jars["S"].items()])
                page = ctx.new_page()
                page.goto(f"http://localhost:{srv.port}/index.php/apps/dashboard/", wait_until="networkidle")
                final = page.url.split("?")[0]
                data_user = page.evaluate("document.head.dataset.user")
                page.screenshot(path=str(out / "dashboard-webview-ua.png"))
                browser.close()
            rep.check("web view user agent: dashboard loads, not /unsupported or /login",
                      data_user == user and "/unsupported" not in final and "/login" not in final,
                      f"final path {final.replace(f'http://localhost:{srv.port}', '')}, data-user matches {data_user == user}")
        except ImportError:
            rep.check("web view user agent", None, "playwright not installed")

    (out / "server-login-report.md").write_text(rep.markdown(), encoding="utf-8")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(rep.markdown() + "\n")
    return 1 if rep.failed else 0


if __name__ == "__main__":
    sys.exit(main())
