#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""CI only: prove that the local DNS server answers "no such name" (NXDOMAIN) for a blocked name and still resolves
a control name. The emulator job starts that server so the app can never reach drive.nourax.org (rule of the build).

Usage: python3 tools/ci/dns-check.py --server 127.0.0.2 --blocked drive.nourax.org --control github.com
Exit code 1 when the blocked name resolves, or the control name does not (then the server would break the tests).
"""
from __future__ import annotations

import argparse
import socket
import struct
import sys

NXDOMAIN = 3


def rcode_and_answers(server: str, name: str, timeout: float = 5.0) -> tuple[int, int]:
    """Send one A query over UDP; return (RCODE, number of answer records)."""
    query = struct.pack("!HHHHHH", 0x4E44, 0x0100, 1, 0, 0, 0)
    for label in name.rstrip(".").split("."):
        query += bytes([len(label)]) + label.encode("ascii")
    query += b"\x00" + struct.pack("!HH", 1, 1)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(timeout)
        sock.sendto(query, (server, 53))
        reply, _ = sock.recvfrom(4096)
    flags, _qd, an = struct.unpack("!HHH", reply[2:8])
    return flags & 0x0F, an


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--server", required=True)
    ap.add_argument("--blocked", required=True)
    ap.add_argument("--control", required=True)
    args = ap.parse_args()
    ok = True
    try:
        rcode, answers = rcode_and_answers(args.server, args.blocked)
    except OSError as e:
        print(f"dns-check: FAIL - no answer from {args.server} for {args.blocked}: {e}")
        return 1
    blocked_ok = rcode == NXDOMAIN and answers == 0
    print(f"dns-check: {args.blocked} -> rcode {rcode}, {answers} answer(s): {'blocked' if blocked_ok else 'NOT BLOCKED'}")
    ok &= blocked_ok
    try:
        rcode, answers = rcode_and_answers(args.server, args.control)
    except OSError as e:
        print(f"dns-check: FAIL - no answer from {args.server} for {args.control}: {e}")
        return 1
    control_ok = rcode == 0 and answers > 0
    print(f"dns-check: {args.control} -> rcode {rcode}, {answers} answer(s): {'resolves' if control_ok else 'DOES NOT RESOLVE'}")
    ok &= control_ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
