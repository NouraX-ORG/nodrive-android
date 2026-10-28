#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Check the emulator's network capture: NoDrive must not contact Nextcloud GmbH (spec section 3).

Usage:
    python tools/check-network.py CAPTURE.pcap [--report FILE] [--expect-host drive.nourax.org]

CAPTURE is the emulator's own capture (emulator -tcpdump FILE: every Ethernet frame of the
virtual LAN, pcap or pcapng). From it the tool takes
- every DNS query name (UDP and TCP port 53),
- every TLS server name (SNI) from the ClientHello, after joining the first TCP segments,
- every HTTP Host header of plain-text requests,
and lists each host with the number of DNS queries, TLS and HTTP connections.

Exit code 1 when
- a host belongs to Nextcloud GmbH: a name whose last two labels are "nextcloud.<public TLD>"
  (nextcloud.com and its subdomains such as push-notifications., updates., apps., help., docs.,
  download.; also nextcloud.org / .de). Reserved test names (nextcloud.localhost, *.test,
  *.invalid, *.example) are listed but allowed;
- the capture has no DNS query at all, or a --require-host was not contacted: then the capture
  missed traffic and proves nothing. (On current emulators Wi-Fi runs through netsim, outside the
  -tcpdump capture; CI switches Wi-Fi off so all traffic takes the captured mobile-data path, and
  requires drive.nourax.org, which the sign-in test opens, to show up.)
- a --forbid-connection host got a TLS connection (SNI) or an HTTP request. A DNS question for it is fine: in CI a
  local DNS server answers "no such name" for it, so the app asks but can never connect (the build rule is that CI
  never contacts drive.nourax.org).
"""
from __future__ import annotations

import argparse
import struct
import sys
from collections import defaultdict
from pathlib import Path

RESERVED_TLDS = {"localhost", "local", "test", "invalid", "example", "internal", "lan", "home", "arpa"}


def nextcloud_gmbh(host: str) -> bool:
    labels = host.lower().rstrip(".").split(".")
    return len(labels) >= 2 and labels[-2] == "nextcloud" and labels[-1] not in RESERVED_TLDS


def frames(path: Path):
    """Yield (timestamp, linktype, bytes) for every packet of a pcap or pcapng file."""
    data = path.read_bytes()
    if len(data) < 24:
        return
    magic = data[:4]
    if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\xc3\xd4", b"\xa1\xb2\x3c\x4d"):
        e = "<" if magic in (b"\xd4\xc3\xb2\xa1", b"\x4d\x3c\xb2\xa1") else ">"
        nano = magic in (b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d")
        linktype = struct.unpack(e + "I", data[20:24])[0] & 0x0FFFFFFF
        pos = 24
        while pos + 16 <= len(data):
            sec, frac, incl, _orig = struct.unpack(e + "IIII", data[pos:pos + 16])
            pos += 16
            yield sec + frac / (1e9 if nano else 1e6), linktype, data[pos:pos + incl]
            pos += incl
    elif magic == b"\x0a\x0d\x0d\x0a":
        pos, e, links = 0, "<", []
        while pos + 12 <= len(data):
            btype = data[pos:pos + 4]
            if btype == b"\x0a\x0d\x0d\x0a":
                e = "<" if data[pos + 8:pos + 12] == b"\x4d\x3c\x2b\x1a" else ">"
                links = []
            btype_n, blen = struct.unpack(e + "II", data[pos:pos + 8])
            if blen < 12:
                break
            body = data[pos + 8:pos + blen - 4]
            if btype_n == 1:  # interface description
                links.append(struct.unpack(e + "H", body[:2])[0])
            elif btype_n == 6 and len(body) >= 20:  # enhanced packet
                iface, ts_hi, ts_lo, cap, _orig = struct.unpack(e + "IIIII", body[:20])
                link = links[iface] if iface < len(links) else 1
                yield ((ts_hi << 32) | ts_lo) / 1e6, link, body[20:20 + cap]
            elif btype_n == 3 and len(body) >= 4:  # simple packet
                yield 0.0, links[0] if links else 1, body[4:]
            pos += blen
    else:
        raise SystemExit(f"check-network: {path} is neither pcap nor pcapng")


def ip_payload(linktype: int, frame: bytes):
    """Return (src, dst, proto, payload) of an IPv4/IPv6 packet, or None."""
    if linktype == 1:  # Ethernet
        if len(frame) < 14:
            return None
        etype, off = struct.unpack("!H", frame[12:14])[0], 14
        while etype in (0x8100, 0x88A8) and len(frame) >= off + 4:
            etype, off = struct.unpack("!H", frame[off + 2:off + 4])[0], off + 4
        pkt = frame[off:]
    elif linktype in (101, 12, 14):  # raw IP
        pkt = frame
        etype = 0x0800 if pkt and pkt[0] >> 4 == 4 else 0x86DD
    elif linktype == 113:  # Linux cooked
        etype, pkt = struct.unpack("!H", frame[14:16])[0], frame[16:]
    elif linktype == 276:  # Linux cooked v2
        etype, pkt = struct.unpack("!H", frame[0:2])[0], frame[20:]
    else:
        return None
    if etype == 0x0800 and len(pkt) >= 20:
        ihl = (pkt[0] & 0x0F) * 4
        total = struct.unpack("!H", pkt[2:4])[0]
        if struct.unpack("!H", pkt[6:8])[0] & 0x1FFF:  # not the first fragment
            return None
        return ".".join(map(str, pkt[12:16])), ".".join(map(str, pkt[16:20])), pkt[9], pkt[ihl:total or None]
    if etype == 0x86DD and len(pkt) >= 40:
        nxt, off = pkt[6], 40
        while nxt in (0, 43, 60) and len(pkt) >= off + 8:
            nxt, off = pkt[off], off + (pkt[off + 1] + 1) * 8
        src = ":".join(f"{pkt[8 + i] << 8 | pkt[9 + i]:x}" for i in range(0, 16, 2))
        dst = ":".join(f"{pkt[24 + i] << 8 | pkt[25 + i]:x}" for i in range(0, 16, 2))
        return src, dst, nxt, pkt[off:]
    return None


def dns_name(msg: bytes, off: int, depth: int = 0) -> tuple[str, int]:
    labels, jumped, end = [], False, off
    while off < len(msg) and depth < 20:
        n = msg[off]
        if n == 0:
            off += 1
            break
        if n & 0xC0 == 0xC0:
            if off + 1 >= len(msg):
                break
            if not jumped:
                end = off + 2
            off, jumped, depth = ((n & 0x3F) << 8) | msg[off + 1], True, depth + 1
            continue
        labels.append(msg[off + 1:off + 1 + n].decode("ascii", "replace"))
        off += 1 + n
    return ".".join(labels).lower(), (end if jumped else off)


def dns_queries(msg: bytes) -> list[str]:
    if len(msg) < 12:
        return []
    flags, qd = struct.unpack("!HH", msg[2:6])
    if flags & 0x8000:  # a response; the query itself is counted
        return []
    names, off = [], 12
    for _ in range(min(qd, 10)):
        name, off = dns_name(msg, off)
        off += 4
        if name:
            names.append(name)
    return names


def tls_sni(stream: bytes) -> str | None:
    """Server name from the first TLS records of a client stream (ClientHello may span records)."""
    hs, pos = b"", 0
    while pos + 5 <= len(stream) and stream[pos] == 22:
        length = struct.unpack("!H", stream[pos + 3:pos + 5])[0]
        hs += stream[pos + 5:pos + 5 + length]
        pos += 5 + length
    if len(hs) < 4 or hs[0] != 1:
        return None
    body = hs[4:4 + int.from_bytes(hs[1:4], "big")]
    try:
        p = 2 + 32
        p += 1 + body[p]
        p += 2 + struct.unpack("!H", body[p:p + 2])[0]
        p += 1 + body[p]
        ext_end = p + 2 + struct.unpack("!H", body[p:p + 2])[0]
        p += 2
        while p + 4 <= min(ext_end, len(body)):
            etype, elen = struct.unpack("!HH", body[p:p + 4])
            if etype == 0:
                ext = body[p + 4:p + 4 + elen]
                if len(ext) >= 5 and ext[2] == 0:
                    n = struct.unpack("!H", ext[3:5])[0]
                    return ext[5:5 + n].decode("ascii", "replace").lower()
            p += 4 + elen
    except (IndexError, struct.error):
        return None
    return None


def http_host(stream: bytes) -> str | None:
    head = stream[:4096]
    if not head[:8].split(b" ")[0] in (b"GET", b"POST", b"HEAD", b"PUT", b"DELETE", b"OPTIONS", b"PATCH",
                                       b"PROPFIND", b"CONNECT"):
        return None
    for line in head.split(b"\r\n")[1:]:
        if line.lower().startswith(b"host:"):
            return line[5:].strip().decode("ascii", "replace").split(":")[0].lower()
    return None


def analyse(path: Path):
    dns: dict[str, int] = defaultdict(int)
    tls: dict[str, int] = defaultdict(int)
    http: dict[str, int] = defaultdict(int)
    first_seen: dict[str, float] = {}
    streams: dict[tuple, dict] = {}
    t0, packets = None, 0
    for ts, link, frame in frames(path):
        packets += 1
        t0 = ts if t0 is None else t0
        ip = ip_payload(link, frame)
        if not ip:
            continue
        src, dst, proto, seg = ip
        if proto == 17 and len(seg) >= 8:
            sport, dport = struct.unpack("!HH", seg[:4])
            if dport == 53:
                for name in dns_queries(seg[8:]):
                    dns[name] += 1
                    first_seen.setdefault(name, ts - t0)
        elif proto == 6 and len(seg) >= 20:
            sport, dport, seq = struct.unpack("!HHI", seg[:8])
            off, flags = (seg[12] >> 4) * 4, seg[13]
            key = (src, sport, dst, dport)
            if flags & 0x02:  # SYN: a new client stream
                streams[key] = {"isn": seq + 1, "parts": {}, "done": False}
                continue
            st = streams.get(key)
            payload = seg[off:]
            if not st or st["done"] or not payload:
                continue
            rel = (seq - st["isn"]) & 0xFFFFFFFF
            if rel < 16384:
                st["parts"].setdefault(rel, payload)
            data, expect = b"", 0
            for r in sorted(st["parts"]):
                if r > expect:
                    break
                data += st["parts"][r][expect - r:]
                expect = len(data)
            if dport == 53 and len(data) >= 2:
                for name in dns_queries(data[2:]):
                    dns[name] += 1
                    first_seen.setdefault(name, ts - t0)
                st["done"] = True
                continue
            name = tls_sni(data) if data[:1] == b"\x16" else http_host(data)
            if name:
                (tls if data[:1] == b"\x16" else http)[name] += 1
                first_seen.setdefault(name, ts - t0)
                st["done"] = True
            elif len(data) > 16000:
                st["done"] = True
    return packets, dns, tls, http, first_seen


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("capture", type=Path)
    ap.add_argument("--report")
    ap.add_argument("--require-host", action="append", default=[],
                    help="a host the test run is known to contact; fail if the capture does not show it")
    ap.add_argument("--forbid-connection", action="append", default=[], metavar="HOST",
                    help="fail if the capture shows a TLS (SNI) or HTTP (Host) connection to HOST or a subdomain of it")
    args = ap.parse_args()
    if not args.capture.is_file() or args.capture.stat().st_size == 0:
        print(f"check-network: FAIL - capture {args.capture} is missing or empty")
        return 1
    packets, dns, tls, http, first = analyse(args.capture)
    hosts = sorted(set(dns) | set(tls) | set(http), key=lambda h: (first.get(h, 0), h))
    bad = [h for h in hosts if nextcloud_gmbh(h)]
    allowed = [h for h in hosts if "nextcloud" in h and h not in bad]
    lines = ["# Network check (emulator capture)\n",
             f"Capture: `{args.capture.name}`, {args.capture.stat().st_size} bytes, {packets} packets, "
             f"{sum(dns.values())} DNS queries, {sum(tls.values())} TLS and {sum(http.values())} HTTP connections "
             f"with a host name.\n",
             "| Host | first seen (s) | DNS queries | TLS (SNI) | HTTP | Nextcloud GmbH |",
             "|---|---|---|---|---|---|"]
    for h in hosts:
        lines.append(f"| {h} | {first.get(h, 0):.1f} | {dns.get(h, 0)} | {tls.get(h, 0)} | {http.get(h, 0)} | "
                     f"{'YES' if h in bad else 'no'} |")
    for host in args.require_host:
        lines.append(f"\nRequired host {host}: {'contacted' if host in hosts else 'NOT contacted'} "
                     f"(DNS {dns.get(host, 0)}, TLS {tls.get(host, 0)}, HTTP {http.get(host, 0)}).")
    forbidden = sorted({h for h in set(tls) | set(http) for f in args.forbid_connection
                        if h == f.lower() or h.endswith("." + f.lower())})
    for host in args.forbid_connection:
        lines.append(f"\nForbidden connection to {host}: "
                     f"{'YES' if any(h == host.lower() or h.endswith('.' + host.lower()) for h in forbidden) else 'none'} "
                     f"(DNS questions {dns.get(host.lower(), 0)}, TLS {tls.get(host.lower(), 0)}, "
                     f"HTTP {http.get(host.lower(), 0)}).")
    lines.append(f"Names with 'nextcloud' that are reserved test names (allowed): {', '.join(allowed) or 'none'}.")
    fails = []
    if not dns:
        fails.append("no DNS query in the capture: the capture did not work")
    missing = [h for h in args.require_host if h not in hosts]
    if missing:
        fails.append("required host(s) not in the capture, so it missed traffic: " + ", ".join(missing))
    if bad:
        fails.append("Nextcloud GmbH host(s) contacted: " + ", ".join(bad))
    if forbidden:
        fails.append("forbidden connection(s) in the capture: " + ", ".join(forbidden))
    lines.append(f"\n## Result\n\n{'FAIL: ' + '; '.join(fails) if fails else 'PASS - no Nextcloud GmbH host'}\n")
    report = "\n".join(lines) + "\n"
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(report, encoding="utf-8")
    print(report)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
