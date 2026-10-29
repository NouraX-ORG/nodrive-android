#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Seeds the throwaway server of tools/ci/agenda-server.sh with the data of plans/noagenda-decisions.md, section 12.
(Same data as the desktop client's seed, so every app is tested against one picture.)

Python standard library only. Every call is made as the right user through the same APIs that NoAgenda reads
(NoGantt OCS, Deck REST, CalDAV, WebDAV). It talks to the throwaway server only (SERVER_URL, on 127.0.0.1 in the
runner) and never to the Drive of Nourax. The throwaway passwords come from the environment (never printed):

  SERVER_URL                      for example http://127.0.0.1:8080
  PASS_ADMIN, PASS_MARA, PASS_ALI, PASS_SARA, PASS_OLAF

Writes seed.json (ids and facts the test needs: no password) to the folder given as the first argument.
Dates are relative to today (UTC), so the same script works on any day.
"""
import base64
import datetime
import json
import os
import sys
import urllib.error
import urllib.request

SERVER = os.environ["SERVER_URL"].rstrip("/")
OUT = sys.argv[1]
ROBOT = "\U0001F916"
TODAY = datetime.datetime.now(datetime.timezone.utc).date()
WARNINGS = []

PASSWORDS = {u: os.environ["PASS_" + u.upper()] for u in ("admin", "mara", "ali", "sara", "olaf")}
LOGIN = {"admin": "ciadmin", "mara": "mara", "ali": "ali", "sara": "sara", "olaf": "olaf"}


def day(offset):
    return (TODAY + datetime.timedelta(days=offset)).isoformat()


def stamp(offset, hour, minute=0):
    d = TODAY + datetime.timedelta(days=offset)
    return "%04d%02d%02dT%02d%02d00Z" % (d.year, d.month, d.day, hour, minute)


def datestr(offset):
    d = TODAY + datetime.timedelta(days=offset)
    return "%04d%02d%02d" % (d.year, d.month, d.day)


def request(who, method, path, body=None, headers=None, expect=(200, 201, 204, 207), raw=False):
    url = SERVER + path
    data = None
    h = {"Authorization": "Basic " + base64.b64encode(("%s:%s" % (LOGIN[who], PASSWORDS[who])).encode()).decode(), "OCS-APIRequest": "true"}
    if headers:
        h.update(headers)
    if body is not None:
        if isinstance(body, (dict, list)):
            data = json.dumps(body).encode()
            h.setdefault("Content-Type", "application/json")
        elif isinstance(body, str):
            data = body.encode("utf-8")
        else:
            data = body
    req = urllib.request.Request(url, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            status, payload = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, payload = e.code, e.read()
    if expect and status not in expect:
        detail = payload[:300].decode("utf-8", "replace").replace(chr(10), " ") if payload else ""
        raise RuntimeError("%s %s as %s -> HTTP %d %s" % (method, path, who, status, detail))
    if raw:
        return status, payload
    if payload and payload[:1] in (b"{", b"["):
        try:
            return status, json.loads(payload)
        except ValueError:
            return status, None
    return status, None


def warn(text):
    WARNINGS.append(text)
    print("warning: " + text)


GANTT = "/ocs/v2.php/apps/nc_gantt/api/v1/"


def ocs(who, method, path, body=None, expect=(200, 201, 204)):
    status, data = request(who, method, GANTT + path + ("&" if "?" in path else "?") + "format=json", body, expect=expect)
    if isinstance(data, dict) and "ocs" in data:
        return data["ocs"]["data"]
    return data


def deck(who, method, path, body=None, expect=(200, 201, 204)):
    status, data = request(who, method, "/index.php/apps/deck/api/v1.0/" + path, body, expect=expect)
    return data


seed = {"today": TODAY.isoformat(), "projects": {}, "resources": {}, "tasks": {}, "cards": {}, "labels": {}, "flags": {}}

# ---- NoGantt: schedule and holiday (admin), then everything else as Mara ----------------------------------------------
schedule = ocs("admin", "POST", "admin/work-schedules", {"name": "Mon-Fri", "work_days": 31})
schedule_id = schedule["id"]
try:
    ocs("admin", "POST", "admin/holidays", {"holiday_date": day(40), "name": "Test holiday"})
except RuntimeError as e:
    warn(str(e))


def resource(name, rtype="person", uid=None, unit="h", schedule=None):
    body = {"name": name, "type": rtype, "unit": unit}
    if uid:
        body["uid"] = uid
    if schedule:
        body["schedule_id"] = schedule
    r = ocs("mara", "POST", "resources", body)
    seed["resources"][name] = r["id"]
    return r["id"]


res_ali = resource("Ali", uid="ali", schedule=schedule_id)
res_sara = resource("Sara", uid="sara", schedule=schedule_id)
res_mara = resource("Mara", uid="mara", schedule=schedule_id)
res_temp = resource("Temp worker")
res_designer = resource("Designer", rtype="role", unit="PT")
res_writer = resource(ROBOT + " Writer-Bot (Ali)")
res_design = resource(ROBOT + " Design-Bot (Sara)")
res_research = resource(ROBOT + " Research-Bot (Ali)")
res_ghost = resource(ROBOT + " Ghost-Bot (Ali)")

website = ocs("mara", "POST", "projects", {"title": "Website", "color": "#0082c9"})["id"]
internal = ocs("mara", "POST", "projects", {"title": "Internal", "color": "#e11d48"})["id"]
seed["projects"] = {"Website": website, "Internal": internal}
for project, user, permission in ((website, "ali", "editor"), (website, "sara", "viewer"), (internal, "ali", "viewer")):
    ocs("mara", "POST", "projects/%d/shares" % project, {"principal_id": user, "principal_type": "user", "permission": permission})
phase = ocs("mara", "POST", "projects/%d/subprojects" % website, {"title": "Phase 1"})["id"]


def task(project, title, start, end, rows, status=None, progress=0, deps=None, phase_id=None):
    body = {"title": title, "start_date": day(start), "end_date": day(end), "resources": [{"resource_id": rid, "work": work} for rid, work in rows],
            "manual_progress": progress}
    if status:
        body["status"] = status
    if deps:
        body["dependencies"] = [{"predecessor_id": "t-%d" % d, "dependency_type": "EA", "lag_days": 0} for d in deps]
    if phase_id:
        body["subproject_id"] = phase_id
    t = ocs("mara", "POST", "projects/%d/tasks" % project, body)
    t = t.get("task", t) if isinstance(t, dict) else t
    seed["tasks"][title] = t["id"]
    return t["id"]


task(website, "Write homepage copy", -2, 2, [(res_ali, 2), (res_writer, 6)], status="in_progress", progress=40, phase_id=phase)
task(website, "Prepare client demo", -1, 1, [(res_ali, 10)], status="in_progress", progress=10)
late = task(website, "Banner design", -8, -2, [(res_sara, 3), (res_design, 8)], status="in_progress", progress=60)
task(website, "Publish banners", -1, 2, [(res_ali, 4)], deps=[late])
task(website, "Review sitemap", 1, 3, [(res_designer, 2)])
task(website, "Orphan writing", 0, 2, [(res_writer, 5)])
task(website, "Cross pairing", 0, 1, [(res_ali, 2), (res_design, 4)])
task(website, "Two agents", 0, 5, [(res_ali, 2), (res_writer, 3), (res_research, 3)])
task(website, "Temp work", 1, 6, [(res_temp, 5), (res_sara, 2)])
try:
    task(website, "Finished task", -8, -6, [(res_ali, 2)], status="done", progress=100)
except RuntimeError as e:
    warn("finished task: " + str(e))
linked = task(website, "Landing page build", 0, 2, [(res_ali, 6)])
task(website, "Send invoice", -1, 0, [(res_ali, 1)])
task(website, "Ghost agent task", 1, 2, [(res_ali, 2), (res_ghost, 4)])
task(website, "Big push", 0, 1, [(res_ali, 60)])
task(internal, "Sara's internal task", -1, 7, [(res_sara, 4)])
ocs("mara", "POST", "projects/%d/milestones" % website, {"title": "Launch", "milestone_date": day(12)})

# ---- Deck: the board, labels, cards ----------------------------------------------------------------------------------------
board = deck("mara", "POST", "boards", {"title": "Team board", "color": "2563EB"})["id"]
seed["board"] = board
for user in ("ali", "sara"):
    deck("mara", "POST", "boards/%d/acl" % board, {"type": 0, "participant": user, "permissionEdit": True, "permissionShare": False, "permissionManage": False})
labels = {}
for name in ("Writer-Bot", "Design-Bot", "Ghost-Bot"):
    labels[name] = deck("mara", "POST", "boards/%d/labels" % board, {"title": ROBOT + " " + name, "color": "31CC7C"})["id"]
seed["labels"] = labels
stack = deck("mara", "POST", "boards/%d/stacks" % board, {"title": "To do", "order": 1})["id"]


def card(title, users, due, label=None, done=False):
    body = {"title": title, "type": "plain", "order": 1}
    if due is not None:
        body["duedate"] = day(due) + "T00:00:00+00:00"
    c = deck("mara", "POST", "boards/%d/stacks/%d/cards" % (board, stack), body)
    cid = c["id"]
    for user in users:
        deck("mara", "PUT", "boards/%d/stacks/%d/cards/%d/assignUser" % (board, stack, cid), {"userId": user, "type": 0})
    if label:
        deck("mara", "PUT", "boards/%d/stacks/%d/cards/%d/assignLabel" % (board, stack, cid), {"labelId": labels[label]})
    if done:
        try:
            update = {"title": title, "type": "plain", "owner": "mara", "description": "", "order": 1, "done": TODAY.isoformat() + "T08:00:00+00:00"}
            if due is not None:
                update["duedate"] = body["duedate"]
            deck("mara", "PUT", "boards/%d/stacks/%d/cards/%d" % (board, stack, cid), update)
        except RuntimeError as e:
            warn("done card: " + str(e))
    seed["cards"][title] = cid
    return cid


try:
    ocs("mara", "PUT", "projects/%d" % website, {"deck_board_id": board, "deck_sync_level": "read"})
except RuntimeError as e:
    warn("project deck level: " + str(e))
linked_card = card("Landing page card", ["ali"], 2)
card("Call supplier", ["ali"], 1, "Writer-Bot")
card("Pay invoice", ["ali"], 0)
card("Fix footer", ["sara"], -3, "Design-Bot")
card("Someday idea", ["ali"], None)
card("Old task", ["ali"], -18, done=True)
card("Stranger bot", ["ali"], 3, "Ghost-Bot")
card("Unassigned backlog", [], -1)
try:
    ocs("mara", "POST", "tasks/%d/deck-links" % linked, {"card_id": linked_card})
    seed["flags"]["deck_link"] = True
except RuntimeError as e:
    seed["flags"]["deck_link"] = False
    warn("deck link: " + str(e))

# ---- registry files (the placeholder format, CI user ids as persons) -------------------------------------------------------------
def agent(i, name, person, status, org, gantt_ids):
    return {
        "id": "AG-%04d" % i, "name": name, "person": person, "status": status, "organisation": org, "skills": ["writing"],
        "availability": {"timezone": "UTC", "weekly": [{"days": ["mon", "tue", "wed", "thu", "fri"], "from": "09:00", "to": "17:00"}], "capacity_hours_per_week": 40, "days_off": []},
        "links": {"nogantt_resource_ids": gantt_ids, "noboard_label": ROBOT + " " + name, "calendar_tag": "agent:AG-%04d" % i},
    }


registry = {
    "schema": "nourax-agent-registry", "version": 1, "updated_utc": TODAY.isoformat() + "T08:00:00Z",
    "source": "CI TEST DATA (made up)",
    "agents": [
        agent(1, "Writer-Bot", "ali", "active", "demo-org", [res_writer]),  # recognised by its NoGantt id
        agent(2, "Design-Bot", "sara", "active", "demo-org", []),  # recognised by its exact name only
        agent(3, "Research-Bot", "ali", "paused", "demo-org", []),
        agent(4, "Invoice-Bot", "ali", "retired", "demo-org", []),
        agent(5, "Olaf-Bot", "olaf", "active", "other-org", []),
        agent(6, "Plan-Bot", "mara", "active", "demo-org", []),
    ],
}


def put_registry(user, content):
    request(user, "MKCOL", "/remote.php/dav/files/%s/NoAgenda" % LOGIN[user], expect=(201, 405))
    request(user, "PUT", "/remote.php/dav/files/%s/NoAgenda/agent-registry.json" % LOGIN[user], content, headers={"Content-Type": "application/json"}, expect=(201, 204))


put_registry("ali", json.dumps(registry, ensure_ascii=False))
put_registry("mara", json.dumps(registry, ensure_ascii=False))
put_registry("sara", "{ this is not json")  # an invalid file on purpose
# Olaf has no file (missing) on purpose.

# ---- calendars ---------------------------------------------------------------------------------------------------------------------
def ics(uid, summary, lines, extra=""):
    body = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//nodrive ci//EN", "BEGIN:VEVENT", "UID:" + uid, "DTSTAMP:" + stamp(0, 8), "SUMMARY:" + summary] + lines
    if extra:
        body += extra.strip().split("\n")
    body += ["END:VEVENT", "END:VCALENDAR"]
    return "\r\n".join(body) + "\r\n"


def todo(uid, summary, lines):
    body = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//nodrive ci//EN", "BEGIN:VTODO", "UID:" + uid, "DTSTAMP:" + stamp(0, 8), "SUMMARY:" + summary] + lines
    body += ["END:VTODO", "END:VCALENDAR"]
    return "\r\n".join(body) + "\r\n"


def put_object(owner, calendar, name, content):
    request(owner, "PUT", "/remote.php/dav/calendars/%s/%s/%s" % (LOGIN[owner], calendar, name), content, headers={"Content-Type": "text/calendar; charset=utf-8"}, expect=(201, 204))


put_object("ali", "personal", "dentist.ics", ics("ci-dentist", "Dentist", ["DTSTART:" + stamp(0, 10), "DTEND:" + stamp(0, 11)]))
put_object("ali", "personal", "renew.ics", todo("ci-renew", "Renew passport", ["DUE;VALUE=DATE:" + datestr(2)]))
put_object("ali", "personal", "late.ics", todo("ci-late", "Late bill", ["DUE;VALUE=DATE:" + datestr(-2)]))
put_object("ali", "personal", "someday.ics", todo("ci-someday", "Someday", []))
put_object("ali", "personal", "draft.ics", todo("ci-draft", "Draft report", ["DTSTART;VALUE=DATE:" + datestr(-1), "DUE;VALUE=DATE:" + datestr(3), "CATEGORIES:agent:AG-0001"]))
put_object("ali", "personal", "done.ics", todo("ci-done", "Completed already", ["DUE;VALUE=DATE:" + datestr(-1), "STATUS:COMPLETED", "COMPLETED:" + stamp(-1, 9)]))

# The calendars ali/work and mara/team are created by occ in tools/ci/agenda-server.sh before this script runs.
put_object("ali", "work", "call.ics", ics("ci-call", "Client call", ["DTSTART:" + stamp(0, 10), "DTEND:" + stamp(0, 11), "ORGANIZER:mailto:ali@example.test", "ATTENDEE;PARTSTAT=ACCEPTED:mailto:ali@example.test"]))
put_object("ali", "work", "standup.ics", ics("ci-standup", "Stand-up", ["DTSTART:" + stamp(-3, 9, 30), "DTEND:" + stamp(-3, 9, 45), "RRULE:FREQ=DAILY"]))
put_object("ali", "work", "dayoff.ics", ics("ci-dayoff", "Day off", ["DTSTART;VALUE=DATE:" + datestr(3), "DTEND;VALUE=DATE:" + datestr(4), "TRANSP:OPAQUE"]))
put_object("ali", "work", "free.ics", ics("ci-free", "Maybe lunch", ["DTSTART:" + stamp(0, 12), "DTEND:" + stamp(0, 13), "TRANSP:TRANSPARENT"]))
put_object("ali", "work", "agent.ics", ics("ci-agent", "Writer-Bot review", ["DTSTART:" + stamp(0, 14), "DTEND:" + stamp(0, 15), "X-NOURAX-AGENT-ID:AG-0001", "CATEGORIES:agent:AG-0001,review"]))
put_object("ali", "work", "invite.ics", ics("ci-invite", "Planning meeting", ["DTSTART:" + stamp(0, 15), "DTEND:" + stamp(0, 16), "ORGANIZER:mailto:mara@example.test", "ATTENDEE;PARTSTAT=ACCEPTED:mailto:ali@example.test", "X-NOURAX-AGENT-ID:AG-0001"]))
put_object("ali", "work", "tag.ics", ics("ci-tag", "Tag only", ["DTSTART:" + stamp(0, 17), "DTEND:" + stamp(0, 18), "CATEGORIES:agent:AG-0001"]))

put_object("mara", "team", "invite.ics", ics("ci-invite", "Planning meeting", ["DTSTART:" + stamp(0, 15), "DTEND:" + stamp(0, 16), "ORGANIZER:mailto:mara@example.test", "ATTENDEE;PARTSTAT=ACCEPTED:mailto:ali@example.test"]))
put_object("mara", "team", "tentative.ics", ics("ci-tentative", "Team lunch", ["DTSTART:" + stamp(0, 16), "DTEND:" + stamp(0, 17), "ORGANIZER:mailto:mara@example.test", "ATTENDEE;PARTSTAT=TENTATIVE:mailto:ali@example.test"]))
put_object("mara", "team", "declined.ics", ics("ci-declined", "Budget talk", ["DTSTART:" + stamp(0, 17), "DTEND:" + stamp(0, 18), "ORGANIZER:mailto:mara@example.test", "ATTENDEE;PARTSTAT=DECLINED:mailto:ali@example.test"]))
put_object("mara", "team", "organised.ics", ics("ci-organised", "Ali's workshop", ["DTSTART:" + stamp(1, 9), "DTEND:" + stamp(1, 10), "ORGANIZER:mailto:ali@example.test", "ATTENDEE;PARTSTAT=ACCEPTED:mailto:ali@example.test"]))
put_object("mara", "team", "other.ics", ics("ci-other", "Mara and Olaf", ["DTSTART:" + stamp(0, 18), "DTEND:" + stamp(0, 19), "ORGANIZER:mailto:mara@example.test", "ATTENDEE;PARTSTAT=ACCEPTED:mailto:olaf@example.test"]))

share = ('<?xml version="1.0" encoding="utf-8"?><o:share xmlns:d="DAV:" xmlns:o="http://owncloud.org/ns">'
         '<o:set><d:href>principal:principals/users/ali</d:href><o:read-write/></o:set></o:share>')
try:
    request("mara", "POST", "/remote.php/dav/calendars/mara/team", share, headers={"Content-Type": "application/xml; charset=utf-8"}, expect=(200, 201, 204))
    seed["flags"]["team_shared"] = True
except RuntimeError as e:
    seed["flags"]["team_shared"] = False
    warn("sharing the team calendar: " + str(e))

# ---- NoGantt: Sara switches on the copy of her tasks into a calendar -----------------------------------------------------------------
try:
    sync = ocs("sara", "PUT", "task-sync", {"enabled": True, "create_calendar": True})
    seed["flags"]["task_sync"] = True
    seed["task_sync_calendar"] = sync.get("calendar_uri") if isinstance(sync, dict) else None
except RuntimeError as e:
    seed["flags"]["task_sync"] = False
    warn("task sync: " + str(e))

seed["warnings"] = WARNINGS
os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "seed.json"), "w", encoding="utf-8") as f:
    json.dump(seed, f, indent=2, ensure_ascii=False)
print("seeded: %d NoGantt tasks, %d cards, %d warnings" % (len(seed["tasks"]), len(seed["cards"]), len(WARNINGS)))
