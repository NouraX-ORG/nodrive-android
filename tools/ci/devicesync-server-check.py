#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Checks what the throwaway server really holds after the device sync tests on the emulator (plan 4.8, 4.9).

It asks the server directly, as Ali, with plain HTTP (WebDAV, CalDAV, CardDAV), not through the app, so a screen that only looked right is
caught. Python standard library only. It talks to the throwaway server only (NODRIVE_AG_URL, on localhost in the runner) and never to the
Drive of Nourax. The throwaway token comes from the environment and is never printed. Names and counts only go into the report.

  usage: devicesync-server-check.py REPORT.md
"""
import base64
import os
import re
import sys
import urllib.error
import urllib.request

BASE = os.environ["NODRIVE_AG_URL"].rstrip("/")
AUTH = "Basic " + base64.b64encode(("ali:" + os.environ["NODRIVE_AG_ALI_TOKEN"]).encode()).decode()
ROWS = []
FAILED = 0


def call(method, path, body=None, headers=None):
    h = {"Authorization": AUTH, "OCS-APIRequest": "true"}
    h.update(headers or {})
    request = urllib.request.Request(BASE + "/" + path.lstrip("/"), data=body, method=method, headers=h)
    try:
        with urllib.request.urlopen(request, timeout=60) as reply:
            return reply.status, reply.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def listing(path):
    status, text = call("PROPFIND", path, b'<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>',
                        {"Depth": "1", "Content-Type": "application/xml"})
    if status != 207:
        return []
    return [h for h in re.findall(r"<d:href>([^<]*)</d:href>", text, re.I) if not h.endswith("/")]


def row(check, ok, detail):
    global FAILED
    ROWS.append("| %s | %s | %s |" % (check, "PASS" if ok else "FAIL", detail))
    if not ok:
        FAILED = 1


def main():
    report = sys.argv[1]
    calendar_backup = listing("remote.php/dav/files/ali/.Calendar-Backup/")
    contacts_backup = listing("remote.php/dav/files/ali/.Contacts-Backup/")
    row("calendar backup before the first sync", len(calendar_backup) >= 1, "%d file(s) in .Calendar-Backup" % len(calendar_backup))
    plain = [h for h in contacts_backup if "removed-contacts-" not in h]
    row("contacts backup before the first sync", len(plain) >= 1, "%d backup file(s) in .Contacts-Backup" % len(plain))
    removed = [h for h in contacts_backup if "removed-contacts-" in h]
    saved = any("FN:Phone Person" in call("GET", h.lstrip("/"))[1] for h in removed)
    row("removed contact saved before the delete", len(removed) >= 1 and saved, "%d removed-contacts file(s), card inside: %s" % (len(removed), saved))
    status, work = call("GET", "remote.php/dav/calendars/ali/work/?export", headers={"Accept": "text/calendar"})
    row("phone-only event added to the server", status == 200 and "SUMMARY:Phone only event" in work, "export status %d" % status)
    # The trash bin does not list its objects with a plain PROPFIND (501); its objects are found with a CalDAV calendar-query.
    query = (b'<?xml version="1.0" encoding="utf-8"?><c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" '
             b'xmlns:nc="http://nextcloud.com/ns"><d:prop><d:getetag/><nc:deleted-at/><c:calendar-data/></d:prop>'
             b'<c:filter><c:comp-filter name="VCALENDAR"/></c:filter></c:calendar-query>')
    status, text = call("REPORT", "remote.php/dav/calendars/ali/trashbin/objects/", query, {"Depth": "1", "Content-Type": "application/xml"})
    trash = re.findall(r"<d:href>([^<]*)</d:href>", text, re.I) if status == 207 else []
    row("a phone delete is in the calendar trash bin", "SUMMARY:Delete me " in text, "%d object(s) in the trash bin" % len(trash))
    cards = {h.rsplit("/", 1)[-1]: call("GET", h.lstrip("/"))[1] for h in listing("remote.php/dav/addressbooks/users/ali/contacts/")}
    unknown = cards.get("ds-unknown.vcf", "")
    row("phone number change reached the card and kept the unknown line", "+49 30 9999999" in unknown and "X-NOURAX-TEST:keep this" in unknown,
        "card ds-unknown.vcf")
    row("seeded cards still there", all(n in cards for n in ("ds-family.vcf", "ds-unknown.vcf", "ds-photo.vcf", "ci-contact-1.vcf")),
        "%d cards" % len(cards))
    row("the other tests' data is cleaned up", not any("Phone Person" in t for t in cards.values()), "no left-over phone contact")
    with open(report, "w", encoding="utf-8") as f:
        f.write("# Device sync: what the server holds\n\n| Check | Result | Detail |\n|---|---|---|\n" + "\n".join(ROWS) + "\n")
    print("\n".join(ROWS))
    return FAILED


if __name__ == "__main__":
    sys.exit(main())
