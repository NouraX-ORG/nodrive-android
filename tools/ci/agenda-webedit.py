#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Does the agent id survive an edit in the server's own Calendar and Tasks pages? (plans/noagenda-decisions.md section 4.2, 12.)

NoAgenda writes the agent of an event or a to-do twice: the property X-NOURAX-AGENT-ID and, as a copy for programs that drop unknown
properties, the registry's calendar tag in CATEGORIES. This script signs in to the throwaway server (never drive.nourax.org) as Ali in a
headless Chromium (Playwright for Python), edits the title of a tagged event in Calendar and of a tagged to-do in Tasks, saves, and then
reads the raw iCalendar text over CalDAV. It reports "survived" or "dropped" for the property and for the category, separately, for each
app. The job fails only when BOTH are dropped for one app. When the page can not be driven (a button or a field was not found), the
result is "not tested" with the reason, never a guess.

The raw texts are written with every value replaced by a short hash, except the two agent properties.

Usage: agenda-webedit.py <report-folder>
Environment: NODRIVE_AG_URL, NODRIVE_AG_ALI_TOKEN (throwaway values, never printed).
"""
import base64
import hashlib
import http.cookiejar
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE = os.environ["NODRIVE_AG_URL"].rstrip("/")
TOKEN = os.environ["NODRIVE_AG_ALI_TOKEN"]
AUTH = "Basic " + base64.b64encode(("ali:" + TOKEN).encode()).decode()
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "build/agenda-server")
TAG = "agent:AG-0001"
ROWS = []
FAILED = []


def call(method, path, body=None, headers=None):
    h = {"Authorization": AUTH, "OCS-APIRequest": "true"}
    h.update(headers or {})
    request = urllib.request.Request(BASE + "/" + path.lstrip("/"), data=body.encode() if isinstance(body, str) else body, method=method, headers=h)
    try:
        with urllib.request.urlopen(request, timeout=60) as reply:
            return reply.status, reply.read().decode("utf-8", "replace"), reply.headers
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace"), error.headers


def stamp(day_offset, hour):
    d = datetime.now(timezone.utc).date() + timedelta(days=day_offset)
    return "%04d%02d%02dT%02d0000Z" % (d.year, d.month, d.day, hour)


def date(day_offset):
    d = datetime.now(timezone.utc).date() + timedelta(days=day_offset)
    return "%04d%02d%02d" % (d.year, d.month, d.day)


def hashed(text):
    """Every value replaced by a short hash, except the two agent properties."""
    lines = []
    for line in text.replace("\r\n ", "").splitlines():
        if ":" not in line or line.startswith(("BEGIN:", "END:")):
            lines.append(line)
        elif line.startswith("X-NOURAX-AGENT-ID") or line.startswith("CATEGORIES"):
            lines.append(line)
        else:
            name, value = line.split(":", 1)
            lines.append("%s:#%s" % (name, hashlib.sha1(value.encode()).hexdigest()[:8]))
    return "\n".join(lines) + "\n"


def row(app, what, state, detail):
    ROWS.append("| %s | %s | %s | %s |" % (app, what, state, detail))


def session_cookies():
    """The session cookies of the app's own sign-in request (Basic app password), as the in-app web view gets them."""
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    request = urllib.request.Request(BASE + "/ocs/v2.php/cloud/user?format=json", headers={"Authorization": AUTH, "OCS-APIRequest": "true"})
    opener.open(request, timeout=60).read()
    host = re.sub(r"^https?://", "", BASE).split(":")[0]
    return [{"name": c.name, "value": c.value, "domain": host, "path": "/"} for c in jar]


def check(app, path, summary, before_name, drive):
    """Edits one object through the page ([drive] does the clicking) and reports what the server holds afterwards."""
    status, before, _ = call("GET", path, headers={"Accept": "text/calendar"})
    (OUT / ("webedit-%s-before.ics" % before_name)).write_text(hashed(before), encoding="utf-8")
    if status != 200:
        row(app, "object found", "NOT TESTED", "the tagged object was not there (HTTP %d)" % status)
        return
    try:
        error = drive()
    except Exception as exc:  # noqa: BLE001 - the page can fail in many ways; the reason is reported
        error = "the page could not be driven: %s" % type(exc).__name__
    status, after, _ = call("GET", path, headers={"Accept": "text/calendar"})
    (OUT / ("webedit-%s-after.ics" % before_name)).write_text(hashed(after), encoding="utf-8")
    after = after.replace("\r\n ", "")
    if error or status != 200:
        row(app, "agent id", "NOT TESTED", error or "HTTP %d" % status)
        return
    edited = ("SUMMARY:" + summary + " edited") in after
    if not edited:
        row(app, "agent id", "NOT TESTED", "the title was not changed by the page, so nothing was proved")
        return
    prop = "X-NOURAX-AGENT-ID:AG-0001" in after
    category = any(TAG in c.strip().split(",") for c in re.findall(r"^CATEGORIES[^:]*:(.*)$", after, re.M))
    row(app, "property X-NOURAX-AGENT-ID", "survived" if prop else "dropped", "after a save in the page")
    row(app, "category " + TAG, "survived" if category else "dropped", "after a save in the page")
    if not prop and not category:
        FAILED.append(app)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    event = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//CI//EN\r\nBEGIN:VEVENT\r\nUID:ci-webedit-event\r\nDTSTAMP:%s\r\nSUMMARY:Web edit event\r\nDTSTART:%s\r\nDTEND:%s\r\nX-NOURAX-AGENT-ID:AG-0001\r\nCATEGORIES:%s\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n" % (stamp(0, 8), stamp(0, 12), stamp(0, 13), TAG)
    todo = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//CI//EN\r\nBEGIN:VTODO\r\nUID:ci-webedit-todo\r\nDTSTAMP:%s\r\nSUMMARY:Web edit todo\r\nDUE;VALUE=DATE:%s\r\nX-NOURAX-AGENT-ID:AG-0001\r\nCATEGORIES:%s\r\nEND:VTODO\r\nEND:VCALENDAR\r\n" % (stamp(0, 8), date(1), TAG)
    event_path = "remote.php/dav/calendars/ali/work/ci-webedit-event.ics"
    todo_path = "remote.php/dav/calendars/ali/personal/ci-webedit-todo.ics"
    for path, text in ((event_path, event), (todo_path, todo)):
        call("DELETE", path)
        status, _, _ = call("PUT", path, text, {"Content-Type": "text/calendar; charset=utf-8"})
        if status not in (200, 201, 204):
            row("setup", path.split("/")[-1], "NOT TESTED", "could not make the test object (HTTP %d)" % status)

    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        row("setup", "playwright", "NOT TESTED", "Playwright is not installed")
        finish()
        return

    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        context = browser.new_context(viewport={"width": 1400, "height": 1000})
        context.add_cookies(session_cookies())
        page = context.new_page()
        page.set_default_timeout(20000)

        def debug(name):
            """What the page showed when it could not be driven (a picture and the visible words, never a credential)."""
            try:
                page.screenshot(path=str(OUT / ("webedit-%s-debug.png" % name)))
                (OUT / ("webedit-%s-debug.txt" % name)).write_text(page.inner_text("body")[:3000], encoding="utf-8")
            except Exception:  # noqa: BLE001
                pass

        def calendar():
            day = datetime.now(timezone.utc).date().isoformat()
            page.goto("%s/index.php/apps/calendar/timeGridDay/%s" % (BASE, day))
            try:
                page.wait_for_selector(".fc-event", timeout=30000)
                page.locator(".fc-event", has_text="Web edit event").first.click()
                # Calendar 6 shows a read-only popover first: its Edit button opens the editor with the title field and an Update (or Save) button
                page.get_by_role("button", name=re.compile("^Edit$", re.I)).first.click()
                title = page.locator("input[placeholder='Title']").first
                title.wait_for(timeout=15000)
                title.fill("Web edit event edited")
                page.get_by_role("button", name=re.compile("^(Update|Save)$", re.I)).first.click()
                page.wait_for_timeout(3000)
            except Exception:
                debug("calendar")
                raise
            return None

        def tasks():
            page.goto("%s/index.php/apps/tasks/" % BASE)
            try:
                page.wait_for_selector("text=Web edit todo", timeout=30000)
                # a double click on the task opens the sidebar with its title ready to be edited
                page.locator(".task-body__info", has_text="Web edit todo").first.dblclick()
                field = page.locator("input.app-sidebar-header__mainname-input, .app-sidebar-header input[type='text'], aside input[type='text']").first
                field.wait_for(timeout=15000)
                field.fill("Web edit todo edited")
                field.press("Enter")
                page.wait_for_timeout(3000)
            except Exception:
                debug("tasks")
                raise
            return None

        check("Calendar", event_path, "Web edit event", "event", calendar)
        check("Tasks", todo_path, "Web edit todo", "todo", tasks)
        browser.close()
    finish()


def finish():
    report = OUT / "webedit-report.md"
    report.write_text("| App | What | Result | Detail |\n|---|---|---|---|\n" + "\n".join(ROWS) + "\n", encoding="utf-8")
    print("\n".join(ROWS))
    if FAILED:
        print("BOTH marks dropped by: " + ", ".join(FAILED))
        sys.exit(1)


main()
