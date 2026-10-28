#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""CI only: check the throwaway server's own data folder after the Smart conflicts tests (round 3, A).

Input is a listing made inside the server container, one line per file, "<name><TAB><size in bytes>":
    docker exec nodrive-ci-server find '/var/www/html/data/citest/files/Phone backup' -name 'nodrive-cf-*' -printf '%f\\t%s\\n'

The tests put a 37-byte file on the Drive under the name of each phone file (or the phone file's own bytes when the
case is "same size"). So, per case:
  smart       same: stays, no "(2)";  diff: 37 bytes stays, "(2)" has the phone's size (not 37)
  both        old file 37 bytes stays, "(2)" copy next to it
  nodrive     old file 37 bytes stays, no "(2)"
  phone       the file now has the phone's size (not 37), no "(2)"
  smart-all   file 1 (same size) stays with no "(2)"; file 2: 37 bytes stays and a "(2)" copy
  note        three files, 37 bytes, no "(2)" (the conflicts were only reported, never resolved)
Nothing may be missing and no unexpected "nodrive-cf-" file may exist. Exit code 1 on any mismatch.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

OLD = 37


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("listing", type=Path)
    ap.add_argument("--looks", default="light", help="comma-separated looks whose cases ran (light,dark)")
    args = ap.parse_args()
    files: dict[str, int] = {}
    for line in args.listing.read_text(encoding="utf-8").splitlines():
        if "\t" in line:
            name, size = line.rsplit("\t", 1)
            files[name] = int(size)
    expected: set[str] = set()
    problems: list[str] = []

    def want(name: str, size_is=None, not_size=None):
        expected.add(name)
        if name not in files:
            problems.append(f"missing: {name}")
            return
        if size_is is not None and files[name] != size_is:
            problems.append(f"{name}: {files[name]} bytes, expected {size_is}")
        if not_size is not None and files[name] == not_size:
            problems.append(f"{name}: {files[name]} bytes, expected another size than {not_size}")

    def absent(name: str):
        if name in files:
            problems.append(f"unexpected: {name}")

    # Smart rule, light look only
    if "light" in args.looks.split(","):
        want("nodrive-cf-same.jpg")
        absent("nodrive-cf-same (2).jpg")
        want("nodrive-cf-diff.jpg", size_is=OLD)
        want("nodrive-cf-diff (2).jpg", not_size=OLD)
        for look in ("light",):
            for i in (1, 2):
                want(f"nodrive-cf-nodrive-{look}-{i}.jpg", size_is=OLD)
                absent(f"nodrive-cf-nodrive-{look}-{i} (2).jpg")
                want(f"nodrive-cf-phone-{look}-{i}.jpg", not_size=OLD)
                absent(f"nodrive-cf-phone-{look}-{i} (2).jpg")
            want(f"nodrive-cf-smart-{look}-1.jpg")
            absent(f"nodrive-cf-smart-{look}-1 (2).jpg")
            want(f"nodrive-cf-smart-{look}-2.jpg", size_is=OLD)
            want(f"nodrive-cf-smart-{look}-2 (2).jpg", not_size=OLD)
    for look in args.looks.split(","):
        for i in (1, 2):
            want(f"nodrive-cf-both-{look}-{i}.jpg", size_is=OLD)
            want(f"nodrive-cf-both-{look}-{i} (2).jpg", not_size=OLD)
        for i in (1, 2, 3):
            want(f"nodrive-cf-note-{look}-{i}.jpg", size_is=OLD)
            absent(f"nodrive-cf-note-{look}-{i} (2).jpg")
    extra = sorted(set(files) - expected)
    for name in extra:
        problems.append(f"unexpected: {name}")
    for name in sorted(files):
        print(f"server file: {name} ({files[name]} bytes)")
    if problems:
        print("conflicts server check: FAIL")
        for p in problems:
            print("  " + p)
        return 1
    print(f"conflicts server check: PASS - {len(files)} file(s), exactly the expected ones")
    return 0


if __name__ == "__main__":
    sys.exit(main())
