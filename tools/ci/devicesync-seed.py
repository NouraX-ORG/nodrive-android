#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Adds the contacts of the device sync tests to the throwaway server of tools/ci/agenda-server.sh (plan 4.8): three cards in Ali's
address book "Contacts" (one in the group Family, one with a line that the phone has no place for, one with a photo).

Python standard library only. It talks to the throwaway server only (NODRIVE_AG_URL, on localhost in the runner) with Ali's throwaway
app password (NODRIVE_AG_ALI_TOKEN, never printed) and never to the Drive of Nourax.
"""
import base64
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ["NODRIVE_AG_URL"].rstrip("/")
TOKEN = os.environ["NODRIVE_AG_ALI_TOKEN"]
AUTH = "Basic " + base64.b64encode(("ali:" + TOKEN).encode()).decode()
# a 1x1 PNG
PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="


def card(*lines):
    return ("\r\n".join(["BEGIN:VCARD", "VERSION:3.0"] + list(lines) + ["END:VCARD"]) + "\r\n").encode("utf-8")


CARDS = {
    "ds-family.vcf": card("UID:ds-family", "FN:Family Member", "N:Member;Family;;;", "TEL;TYPE=CELL:+49 30 1111111",
                          "EMAIL;TYPE=HOME:family@example.test", "CATEGORIES:Family"),
    "ds-unknown.vcf": card("UID:ds-unknown", "FN:Unknown Props", "N:Props;Unknown;;;", "TEL;TYPE=CELL:+49 30 2222222",
                           "X-NOURAX-TEST:keep this"),
    "ds-photo.vcf": card("UID:ds-photo", "FN:Photo Person", "N:Person;Photo;;;", "TEL;TYPE=CELL:+49 30 3333333",
                         "PHOTO;ENCODING=b;TYPE=PNG:" + PNG),
}


def main():
    failed = 0
    for name, body in CARDS.items():
        request = urllib.request.Request(
            BASE + "/remote.php/dav/addressbooks/users/ali/contacts/" + name, data=body, method="PUT",
            headers={"Authorization": AUTH, "Content-Type": "text/vcard; charset=utf-8"})
        try:
            with urllib.request.urlopen(request, timeout=60) as reply:
                status = reply.status
        except urllib.error.HTTPError as error:
            status = error.code
        print("devicesync seed: %s -> HTTP %d" % (name, status))
        if status not in (200, 201, 204):
            failed += 1
    print("devicesync seed: %d of %d cards failed" % (failed, len(CARDS)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
