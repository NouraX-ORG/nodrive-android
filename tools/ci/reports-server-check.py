#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""CI only: check, on the throwaway server, what the problem reports tests (round 3, B) left in "Problem reports".

Inputs (made by tools/ci/reports-device.sh; the server is the CI container, never drive.nourax.org):
  --zip-dir        the zips copied out of the server's own data folder
  --share-posts    the server access log lines of POST .../files_sharing/api/v1/shares, in order
  --base-url       the server as the runner sees it, e.g. http://localhost:8080
  --user           the account that owns the folder
  NODRIVE_RP_TOKEN (environment) the app password of that account, used only for the sharing query and never printed

Checks, exit code 1 when one fails:
  - exactly 4 zips, named <YYYY-MM-DD_HH-MM-SS>_android_<version>[_n].zip, each at most 5 MB;
  - each has report.json with the 12 fields of the contract; the kinds are manual, manual, manual and crash;
    crash.txt only in the crash report; logs/log.txt in the others;
  - none of the planted fake secrets, no real CI token, no Authorization or Cookie value is inside any zip; the planted
    file name is inside, and the planted e-mail addresses are replaced with <email>;
  - the folder has exactly one share: user share (share_type 0) with nourax-support, read-only (permissions 1);
  - the server saw exactly 2 share requests: the first one failed (no such user yet), the second one worked.
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import os
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

MAX_BYTES = 5 * 1024 * 1024
NAME_RE = re.compile(r"^\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}_android_[A-Za-z0-9._-]+(_\d+)?\.zip$")
FIELDS = ["schema", "kind", "app", "app_version", "build", "platform", "os_version", "device_model", "locale",
          "created_utc", "server_host", "user_note"]
PLANTED_GONE = ["PLANTEDBEARER", "PLANTEDPASSPHRASE", "PLANTEDSESSIONID", "PLANTEDQUERYTOKEN", "PLANTEDNOTEPW",
                "Pl4nt-Edapp-Pass1-Word2-Xyz12", "planted.person@example.org", "note.person@example.org"]
PLANTED_KEPT = "nodrive-rp-photo-123.jpg"
SECRET_VALUE = re.compile(r"(?i)\b(authorization|proxy-authorization|cookie|set-cookie)\b\s*[:=]\s*(?!<removed>)\S")
FAILS: list[str] = []


def check(ok: bool, name: str, evidence: str) -> None:
    print(f"[{'PASS' if ok else 'FAIL'}] {name}: {evidence}")
    if not ok:
        FAILS.append(name)


def shares(base: str, user: str, token: str) -> list[dict]:
    url = f"{base}/ocs/v2.php/apps/files_sharing/api/v1/shares?path=/Problem%20reports&format=json"
    auth = base64.b64encode(f"{user}:{token}".encode()).decode()
    req = urllib.request.Request(url, headers={"OCS-APIRequest": "true", "Authorization": f"Basic {auth}"})
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)["ocs"]["data"]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--zip-dir", type=Path, required=True)
    ap.add_argument("--share-posts", type=Path, required=True)
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--user", required=True)
    args = ap.parse_args()
    token = os.environ.get("NODRIVE_RP_TOKEN", "")

    zips = sorted(args.zip_dir.glob("*.zip"))
    check(len(zips) == 4, "four reports on the server", ", ".join(z.name for z in zips) or "none")
    check(all(NAME_RE.match(z.name) for z in zips), "report names", "; ".join(z.name for z in zips if not NAME_RE.match(z.name)) or "all match")
    check(all(z.stat().st_size <= MAX_BYTES for z in zips), "at most 5 MB each", ", ".join(str(z.stat().st_size) for z in zips))

    kinds: list[str] = []
    everything = ""
    for z in zips:
        with zipfile.ZipFile(io.BytesIO(z.read_bytes())) as archive:
            names = archive.namelist()
            report = json.loads(archive.read("report.json").decode("utf-8"))
            texts = {n: archive.read(n).decode("utf-8", "replace") for n in names}
        everything += "\n".join(texts.values()) + "\n"
        check(list(report.keys()) == FIELDS, f"{z.name}: report.json fields", ", ".join(report.keys()))
        check(report.get("schema") == 1 and report.get("app") == "NoDrive Android" and report.get("platform") == "android",
              f"{z.name}: schema, app, platform", f"{report.get('schema')}, {report.get('app')}, {report.get('platform')}")
        check(bool(re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$", report.get("created_utc", ""))), f"{z.name}: created_utc", report.get("created_utc", ""))
        check(report.get("server_host") == "10.0.2.2", f"{z.name}: server_host", str(report.get("server_host")))
        check(bool(report.get("app_version")) and bool(report.get("build")) and bool(report.get("os_version"))
              and bool(report.get("device_model")) and bool(report.get("locale")), f"{z.name}: version, build, os, device, locale filled", "")
        kind = report.get("kind", "")
        kinds.append(kind)
        if kind == "crash":
            check("crash.txt" in names and "Thread:" in texts.get("crash.txt", ""), f"{z.name}: crash.txt", f"{len(texts.get('crash.txt', ''))} chars")
        else:
            check("crash.txt" not in names, f"{z.name}: no crash.txt for {kind}", ", ".join(names))
        check("logs/log.txt" in names, f"{z.name}: logs/log.txt", ", ".join(names))
    check(sorted(kinds) == ["crash", "manual", "manual", "manual"], "kinds", ", ".join(kinds))

    leaked = [s for s in PLANTED_GONE if s in everything]
    check(not leaked, "no planted secret inside any report", ", ".join(leaked) or f"{len(PLANTED_GONE)} checked")
    check(not token or token not in everything, "no CI token inside any report", "checked" if token else "no token given")
    values = SECRET_VALUE.findall(everything)
    check(not values, "no Authorization or Cookie value inside any report", f"{len(values)} found" if values else "none")
    check(PLANTED_KEPT in everything, "file names are kept", PLANTED_KEPT)
    check("<email>" in everything, "e-mail addresses are replaced", "<email> present")

    if token:
        try:
            found = shares(args.base_url, args.user, token)
        except Exception as e:  # noqa: BLE001 - report any failure of the query
            found = []
            check(False, "sharing query", type(e).__name__)
        check(len(found) == 1, "exactly one share on the folder", str(len(found)))
        if found:
            s = found[0]
            check(s.get("share_type") == 0 and s.get("share_with") == "nourax-support" and s.get("permissions") == 1,
                  "share is to nourax-support, read-only", f"type {s.get('share_type')}, with {s.get('share_with')}, permissions {s.get('permissions')}")
    else:
        check(False, "sharing query", "no token given")

    posts = [line for line in args.share_posts.read_text(encoding="utf-8", errors="replace").splitlines() if "POST" in line]
    statuses = [m.group(1) for line in posts if (m := re.search(r'HTTP/[0-9.]+" (\d{3})', line))]
    check(len(posts) == 2, "exactly two share requests reached the server", f"{len(posts)}: statuses {statuses}")
    if len(statuses) == 2:
        check(statuses[0] not in ("200", "201") and statuses[1] in ("200", "201"), "the first share request failed, the second worked", " then ".join(statuses))

    print(f"reports server check: {'FAIL' if FAILS else 'PASS'}")
    return 1 if FAILS else 0


if __name__ == "__main__":
    sys.exit(main())
