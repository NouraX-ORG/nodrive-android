#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A stand-in for "another host" in the emulator test (CI only). The emulator reaches it as localhost:<port>
(adb reverse), a different host from the test Nextcloud server at 10.0.2.2:8080. It must be another HOST: cookies are
scoped by host and not by port.

It answers every request with a small page and logs one JSON line per request: method, path without query,
the NAMES of the request headers and of the cookies, never their values.

  probe-server.py --port 8081 --log build/device/probe.jsonl            run the server
  probe-server.py --check build/device/probe.jsonl                      exit 1 when a request carried the
      Authorization header or any cookie of the account's server (oc*, nc_*): nothing of the account may reach
      another origin. --min-requests N also fails when fewer than N requests arrived (a positive control)."""
from __future__ import annotations

import argparse
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ACCOUNT_COOKIE_PREFIXES = ("oc", "nc_", "__host-nc_")


def serve(port: int, log: Path) -> None:
    log.parent.mkdir(parents=True, exist_ok=True)

    class Handler(BaseHTTPRequestHandler):
        def _answer(self) -> None:
            cookies = [c.split("=", 1)[0].strip() for c in self.headers.get("Cookie", "").split(";") if c.strip()]
            entry = {"method": self.command, "path": self.path.split("?")[0],
                     "headers": sorted(k.lower() for k in self.headers.keys()), "cookies": sorted(cookies)}
            with open(log, "a", encoding="utf-8") as fh:
                fh.write(json.dumps(entry) + "\n")
            body = b"<html><body>probe</body></html>"
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        do_GET = do_POST = do_HEAD = do_OPTIONS = _answer

        def log_message(self, *_args) -> None:
            return

    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


def check(log: Path, min_requests: int = 0) -> int:
    entries = [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines() if line.strip()] \
        if log.is_file() else []
    bad = [e for e in entries if "authorization" in e["headers"]
           or any(c.lower().startswith(ACCOUNT_COOKIE_PREFIXES) for c in e["cookies"])]
    print(f"probe server: {len(entries)} request(s), {len(bad)} carrying the account")
    for e in bad:
        print(f"  LEAK {e['method']} {e['path']} headers={e['headers']} cookies={e['cookies']}")
    if len(entries) < min_requests:
        print(f"  NO CONTROL: expected at least {min_requests} request(s) to reach the probe server")
        return 1
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8081)
    ap.add_argument("--log", type=Path, default=Path("build/device/probe.jsonl"))
    ap.add_argument("--check", type=Path)
    ap.add_argument("--min-requests", type=int, default=0)
    args = ap.parse_args()
    if args.check:
        return check(args.check, args.min_requests)
    serve(args.port, args.log)
    return 0


if __name__ == "__main__":
    sys.exit(main())
