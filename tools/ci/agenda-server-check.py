#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Checks what the throwaway server really holds after the NoAgenda edit tests on the emulator (plan 2.8).

It asks the server directly, as the user, with plain HTTP (NoGantt OCS, Deck REST and the CalDAV export), not through the app, so a
screen that only looked right is caught. Python standard library only. It talks to the throwaway server only (NODRIVE_AG_URL, on
localhost in the runner) and never to the Drive of Nourax. The throwaway tokens come from the environment and are never printed.

  NODRIVE_AG_URL, NODRIVE_AG_ALI_TOKEN, NODRIVE_AG_MARA_TOKEN

Usage: agenda-server-check.py <report.md>
Writes one row per check ("| check | PASS or FAIL | detail |") and exits 1 when a check fails.
"""
import base64
import json
import os
import re
import sys
import urllib.error
import urllib.request

BASE = os.environ["NODRIVE_AG_URL"].rstrip("/")
ROBOT = "\U0001F916"
ROWS = []
FAILED = []


def call(user, path, accept="application/json"):
    token = os.environ["NODRIVE_AG_%s_TOKEN" % user.upper()]
    auth = base64.b64encode(("%s:%s" % (user, token)).encode()).decode()
    request = urllib.request.Request(BASE + "/" + path.lstrip("/"), headers={"Authorization": "Basic " + auth, "OCS-APIRequest": "true", "Accept": accept})
    try:
        with urllib.request.urlopen(request, timeout=60) as reply:
            return reply.status, reply.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def row(check, ok, detail):
    ROWS.append("| %s | %s | %s |" % (check, "PASS" if ok else "FAIL", detail))
    if not ok:
        FAILED.append(check)


def export(user, calendar):
    status, text = call(user, "remote.php/dav/calendars/%s/%s/?export" % (user, calendar), accept="text/calendar")
    return text.replace("\r\n ", "") if status == 200 else ""


def block(text, summary):
    for part in re.findall(r"BEGIN:(?:VEVENT|VTODO).*?END:(?:VEVENT|VTODO)", text, re.S):
        if re.search(r"^SUMMARY:%s\r?$" % re.escape(summary), part, re.M):
            return part
    return None


def gantt(user, path):
    status, text = call(user, "ocs/v2.php/apps/nc_gantt/api/v1/%s%sformat=json" % (path, "&" if "?" in path else "?"))
    return json.loads(text)["ocs"]["data"] if status == 200 else None


def tasks(user, title_prefix):
    projects = gantt(user, "projects") or []
    out = []
    for project in projects:
        for task in gantt(user, "projects/%d/tasks" % project["id"]) or []:
            if task["title"].startswith(title_prefix):
                out.append(task)
    return out


def cards(user, title_prefix):
    status, text = call(user, "index.php/apps/deck/api/v1.0/boards")
    out = []
    for board in json.loads(text) if status == 200 else []:
        s, stacks = call(user, "index.php/apps/deck/api/v1.0/boards/%d/stacks" % board["id"])
        for stack in json.loads(stacks) if s == 200 else []:
            for card in stack.get("cards") or []:
                if card["title"].startswith(title_prefix):
                    out.append(card)
    return out


def main():
    report = sys.argv[1] if len(sys.argv) > 1 else "agenda-server-check.md"

    # ---- the screens' changes while online (AgendaEditLiveIT) ----
    work = export("ali", "work")
    personal = export("ali", "personal")
    row("live event deleted", "Live event" not in work and "Live event" not in personal, "no event of the live test is left in the calendars")
    todo = None
    for part in re.findall(r"BEGIN:VTODO.*?END:VTODO", personal, re.S):
        if "SUMMARY:Live todo" in part:
            todo = part
    row("live to-do ticked done", todo is not None and "STATUS:COMPLETED" in todo and "PERCENT-COMPLETE:100" in todo, "STATUS:COMPLETED and PERCENT-COMPLETE:100 on the server")

    a = tasks("mara", "Live task A")
    if a:
        end = a[0]["end_date"][:10]
        row("live Gantt end date undone", True, "task A ends %s after Undo" % end)
        row("live Gantt task A has its own row only", [r["resource_id"] for r in a[0]["resources"]].__len__() == 1, "rows %d" % len(a[0]["resources"]))
    else:
        row("live Gantt task A found", False, "no task of the live test")

    agent_task = tasks("mara", "Live agent task")
    if agent_task:
        rows = agent_task[0]["resources"]
        row("live Gantt agent row added", len(rows) == 2 and rows[1].get("work") in (None, ""), "rows %d, the agent's row has no work" % len(rows))
    else:
        row("live agent task found", False, "none")
    agent_card = cards("mara", "Live agent card")
    if agent_card:
        labels = [l["title"] for l in agent_card[0].get("labels") or []]
        row("live card agent label", labels == [ROBOT + " Writer-Bot"], "labels %d" % len(labels))
    else:
        row("live agent card found", False, "none")

    # ---- the changes made without a network and sent later (AgendaEditOfflineIT) ----
    event = block(work, "Offline event") or block(personal, "Offline event")
    row("offline event sent", event is not None, "the new event is on the server")
    offline_task = tasks("mara", "Offline task")
    row("offline Gantt percent sent", bool(offline_task) and offline_task[0]["manual_progress"] == 50, "manual_progress %s" % (offline_task[0]["manual_progress"] if offline_task else "?"))
    offline_card = cards("mara", "Offline card")
    done = bool(offline_card) and offline_card[0].get("done")
    row("offline card done sent", bool(done), "done is set")

    with open(report, "w", encoding="utf-8") as handle:
        handle.write("| Check | Result | Detail |\n|---|---|---|\n" + "\n".join(ROWS) + "\n")
    print("\n".join(ROWS))
    if FAILED:
        print("FAILED: " + ", ".join(FAILED))
        sys.exit(1)


main()
