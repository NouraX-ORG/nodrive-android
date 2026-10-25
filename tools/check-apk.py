#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Check the built NoDrive APK itself (identity, side-by-side ids, Nextcloud URLs).

Usage:
    python tools/check-apk.py APK [--aapt2 PATH] [--dump-dir DIR] [--report FILE]

It runs aapt2 (default: the newest $ANDROID_HOME/build-tools/*/aapt2) with the dump
sub-commands "badging", "resources", "strings" and "xmltree --file <xml>" and saves each raw
dump in --dump-dir. It also reads every classes*.dex string table and every other file in the
APK as bytes.

Fails (exit 1) when an identity fact is wrong:
- package is not org.nourax.nodrive, or versionName is not <upstream>-nourax.<n>;
- an application label (any language) is not "NoDrive";
- a provider authority, permission, account type or sync-adapter authority is not ours
  (anything with com.nextcloud / org.nextcloud / nextcloud, or not starting with org.nourax.nodrive);
- an intent filter uses the "nc" scheme, or a link host other than drive.nourax.org;
- webview_login_url is not https://drive.nourax.org/index.php/login/v2, or push_server_url
  is not empty;
- the user agent built from the APK's own resources does not match server 35's
  USER_AGENT_CLIENT_ANDROID rule.

Records (in the report, exit code unchanged) every Nextcloud URL or host found in the
resource table, in dex strings (with the methods that load them, via dexdump when it sits next
to aapt2) and in other APK files, plus every string resource whose text names Nextcloud or
ownCloud (including resources merged from libraries), so the sweep can classify each one.
"""
from __future__ import annotations

import argparse
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict
from pathlib import Path

PACKAGE = "org.nourax.nodrive"
APP_LABEL = "NoDrive"
LOGIN_URL = "https://drive.nourax.org/index.php/login/v2"
LINK_HOST = "drive.nourax.org"
# nextcloud/server v35.0.2, lib/public/IRequest.php, USER_AGENT_CLIENT_ANDROID (copied verbatim).
SERVER_UA_ANDROID = re.compile(r"^Mozilla\/5\.0 \(Android\) (?:ownCloud|Nextcloud)\-android\/([^ ]*).*$")
ANDROID_NS = "http://schemas.android.com/apk/res/android:"
# Hosts of Nextcloud GmbH services the app must never contact (spec section 3).
SERVICE_HOSTS = ["push-notifications.nextcloud.com", "updates.nextcloud.com", "download.nextcloud.com",
                 "apps.nextcloud.com", "help.nextcloud.com", "docs.nextcloud.com", "nextcloud.com"]
NAME_RE = re.compile(r"next\s*cloud|owncloud", re.I)
URL_RE = re.compile(r"(?:https?://)?(?:[a-z0-9-]+\.)*nextcloud\.(?:com|org|de)(?:[/:?#][^\s\"'<>]*)?", re.I)


def find_aapt2() -> str:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        sys.exit("check-apk: set ANDROID_HOME or pass --aapt2")
    tools = sorted((Path(home) / "build-tools").glob("*/aapt2"),
                   key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.parent.name)])
    if not tools:
        sys.exit(f"check-apk: no build-tools/*/aapt2 under {home}")
    return str(tools[-1])


class Apk:
    def __init__(self, apk: Path, aapt2: str, dump_dir: Path | None):
        self.apk, self.aapt2, self.dump_dir = apk, aapt2, dump_dir
        if dump_dir:
            dump_dir.mkdir(parents=True, exist_ok=True)

    def dump(self, name: str, *args: str) -> str:
        cmd = [self.aapt2, "dump", *args, str(self.apk)]
        res = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if res.returncode != 0:
            sys.exit(f"check-apk: {' '.join(cmd)} failed ({res.returncode}): {res.stderr.strip()[:500]}")
        if self.dump_dir:
            (self.dump_dir / f"{name}.txt").write_text(res.stdout, encoding="utf-8")
        return res.stdout


def parse_resources(text: str):
    """Map resource id -> name, and name -> [(config, value)], from 'aapt2 dump resources'."""
    by_id: dict[int, str] = {}
    values: dict[str, list[tuple[str, str]]] = defaultdict(list)
    current = None
    for line in text.splitlines():
        m = re.match(r"\s*resource (0x[0-9a-fA-F]{8}) ([^\s]+)", line)
        if m:
            current = m.group(2)
            by_id[int(m.group(1), 16)] = current
            continue
        m = re.match(r"\s*\(([^)]*)\) (.*)$", line)
        if m and current:
            values[current].append((m.group(1), m.group(2)))
    return by_id, values


def unquote(value: str) -> str:
    v = value.strip()
    v = re.sub(r"^\((?:styled )?string\d*\)\s*", "", v)
    if len(v) >= 2 and v[0] == '"' and v[-1] == '"':
        v = v[1:-1]
    return v.replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")


class Resources:
    def __init__(self, text: str):
        self.by_id, self.values = parse_resources(text)

    def default(self, name: str, depth: int = 0) -> str | None:
        vals = self.values.get(name)
        if not vals:
            return None
        cfg = {c: v for c, v in vals}
        raw = cfg.get("", vals[0][1])
        return self.resolve(raw, depth)

    def resolve(self, raw: str, depth: int = 0) -> str:
        raw = raw.strip()
        if depth < 5:
            m = re.fullmatch(r"@(0x[0-9a-fA-F]{8})", raw)
            if m and int(m.group(1), 16) in self.by_id:
                return self.default(self.by_id[int(m.group(1), 16)], depth + 1) or ""
            m = re.fullmatch(r"@(?:[\w.]+:)?(\w+/[\w.]+)", raw)
            if m and m.group(1) in self.values:
                return self.default(m.group(1), depth + 1) or ""
        return unquote(raw)

    def file_of(self, name: str) -> str | None:
        for _cfg, v in self.values.get(name, []):
            m = re.search(r"(res/[^\s]+\.xml)", v)
            if m:
                return m.group(1)
        return None


def xml_attrs(text: str, res: Resources):
    """Yield (element chain, attr name, resolved value) from 'aapt2 dump xmltree'.

    The element chain is a tuple of (element name, running element number), so callers can tell
    two <intent-filter> elements apart.
    """
    stack: list[tuple[int, str, int]] = []
    count = 0
    for line in text.splitlines():
        m = re.match(r"(\s*)E: ([^\s]+)", line)
        if m:
            depth = len(m.group(1))
            while stack and stack[-1][0] >= depth:
                stack.pop()
            count += 1
            stack.append((depth, m.group(2), count))
            continue
        m = re.match(r"(\s*)A: (.+?)(?:\(0x[0-9a-fA-F]+\))?=(.*)$", line)
        if m:
            name = m.group(2).replace(ANDROID_NS, "android:")
            value = m.group(3)
            raw = re.search(r'\(Raw: "(.*)"\)\s*$', value)
            if raw:
                value = raw.group(1)
            elif value.startswith("@"):
                value = res.resolve(value.split()[0])
            else:
                value = unquote(value.split(" (Raw")[0])
            yield tuple((e, i) for _, e, i in stack), name, value


def uleb128(buf: bytes, pos: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, pos
        shift += 7


def dex_strings(buf: bytes) -> list[str]:
    if buf[:4] != b"dex\n":
        return []
    size, off = struct.unpack_from("<II", buf, 0x38)
    out = []
    for i in range(size):
        (data_off,) = struct.unpack_from("<I", buf, off + 4 * i)
        _, pos = uleb128(buf, data_off)
        end = buf.index(b"\x00", pos)
        out.append(buf[pos:end].decode("utf-8", errors="replace"))
    return out


def find_users(dexdump: Path, dex_file: Path, wanted: set[str], users: dict[str, set[str]]) -> None:
    """Record which method loads each wanted string (const-string lines of 'dexdump -d')."""
    method = "?"
    with subprocess.Popen([str(dexdump), "-d", str(dex_file)], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          text=True, encoding="utf-8", errors="replace") as proc:
        for line in proc.stdout:
            m = re.search(r"\|\[[0-9a-f]+\] (\S+?):", line)
            if m:
                method = m.group(1)
                continue
            if "const-string" in line and "nextcloud" in line.lower():
                m = re.search(r'const-string(?:/jumbo)? v\d+, "(.*)" // string@', line)
                if m and m.group(1) in wanted:
                    users[m.group(1)].add(method)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apk")
    ap.add_argument("--aapt2")
    ap.add_argument("--dump-dir")
    ap.add_argument("--report")
    args = ap.parse_args()

    apk = Apk(Path(args.apk), args.aapt2 or find_aapt2(), Path(args.dump_dir) if args.dump_dir else None)
    fails: list[str] = []
    facts: list[tuple[str, str]] = []
    lines: list[str] = []

    def fact(key: str, value: str, ok: bool | None = None):
        mark = "" if ok is None else (" (ok)" if ok else " (FAIL)")
        facts.append((key, f"{value}{mark}"))
        if ok is False:
            fails.append(f"{key}: {value}")

    res = Resources(apk.dump("resources", "resources"))
    badging = apk.dump("badging", "badging")
    apk.dump("strings", "strings")
    manifest = apk.dump("manifest-xmltree", "xmltree", "--file", "AndroidManifest.xml")

    # --- badging: package, version, labels
    m = re.search(r"package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", badging)
    pkg, vcode, vname = m.groups() if m else ("?", "?", "?")
    fact("package", pkg, pkg == PACKAGE)
    fact("versionCode", vcode)
    fact("versionName", vname, bool(re.fullmatch(r"\d+\.\d+\.\d+-nourax\.\d+", vname)))
    labels = re.findall(r"^application-label(?:-([^:]+))?:'(.*)'$", badging, re.M)
    bad_labels = sorted({f"{loc or 'default'}={lab}" for loc, lab in labels if lab != APP_LABEL})
    fact("application labels", f"{len(labels)} label(s), all '{APP_LABEL}'" if labels and not bad_labels
         else f"{len(labels)} label(s), not '{APP_LABEL}': {', '.join(bad_labels[:10]) or 'none found'}",
         bool(labels) and not bad_labels)
    for key in ("launchable-activity", "sdkVersion", "targetSdkVersion"):
        mm = re.search(rf"^{key}:(.*)$", badging, re.M)
        if mm:
            fact(key, mm.group(1).strip().split(" label=")[0])

    # --- merged manifest
    authorities, permissions, uses_perms, actions = [], [], [], set()
    filters: dict[int, dict[str, set[str]]] = defaultdict(lambda: {"scheme": set(), "host": set()})
    for chain, name, value in xml_attrs(manifest, res):
        el = chain[-1][0] if chain else ""
        if el == "provider" and name == "android:authorities":
            authorities.extend(value.split(";"))
        elif el == "permission" and name == "android:name":
            permissions.append(value)
        elif el in ("uses-permission", "uses-permission-sdk-23") and name == "android:name":
            uses_perms.append(value)
        elif el == "data" and name in ("android:scheme", "android:host"):
            parent = [i for e, i in chain if e == "intent-filter"]
            if parent:
                filters[parent[-1]][name.split(":")[1]].add(value)
        elif el == "action" and name == "android:name":
            actions.add(value)
    schemes = set().union(*(f["scheme"] for f in filters.values())) if filters else set()
    link_hosts: set[str] = set()
    for f in filters.values():
        if f["scheme"] & {"http", "https"}:
            link_hosts |= f["host"] or {"*"}
    pairs = sorted({f"{sc}://{h}" for f in filters.values() for sc in f["scheme"] for h in (f["host"] or {"*"})})

    def ours(v: str) -> bool:
        return v.startswith(PACKAGE) and "nextcloud" not in v.lower()

    for a in sorted(authorities):
        fact(f"provider authority {a}", "ours" if ours(a) else "NOT ours", ours(a))
    for p in sorted(permissions):
        fact(f"declared permission {p}", "ours" if ours(p) else "NOT ours", ours(p))
    bad_uses = [p for p in uses_perms if "nextcloud" in p.lower() or "owncloud" in p.lower()]
    fact("uses-permission", f"{len(uses_perms)}: " + ", ".join(sorted(set(uses_perms))), not bad_uses)
    fact("REQUEST_INSTALL_PACKAGES", "absent" if "android.permission.REQUEST_INSTALL_PACKAGES" not in uses_perms
         else "present", "android.permission.REQUEST_INSTALL_PACKAGES" not in uses_perms)
    fact("intent-filter schemes", ", ".join(sorted(schemes)) or "none", "nc" not in schemes)
    fact("intent-filter scheme://host pairs", ", ".join(pairs) or "none")
    fact("http(s) link hosts", ", ".join(sorted(link_hosts)) or "none", link_hosts <= {LINK_HOST})
    nc_actions = sorted(a for a in actions if "nextcloud" in a.lower())
    fact("intent-filter actions with 'nextcloud'", ", ".join(nc_actions) or "none")

    # --- account type and sync adapter (res/xml)
    for xml_name in ("xml/authenticator", "xml/syncadapter_files"):
        f = res.file_of(xml_name)
        if not f:
            fact(xml_name, "not found in the resource table", False)
            continue
        tree = apk.dump(xml_name.replace("/", "-"), "xmltree", "--file", f)
        for _chain, name, value in xml_attrs(tree, res):
            if name in ("android:accountType", "android:contentAuthority"):
                fact(f"{xml_name} {name}", value, ours(value))
            elif name == "android:label":
                fact(f"{xml_name} {name}", value, value == APP_LABEL)

    # --- setup.xml values the app reads at run time
    for name, want in (("string/app_name", APP_LABEL), ("string/account_type", PACKAGE),
                       ("string/webview_login_url", LOGIN_URL), ("string/push_server_url", ""),
                       ("string/data_folder", "nodrive"), ("string/login_data_own_scheme", "nodrive")):
        got = res.default(name)
        fact(name, repr(got), got == want)
    for name in ("string/privacy_url", "string/nourax_privacy_url", "string/url_help", "string/report_issue_link",
                 "string/dev_link", "string/whatsnew_urls"):
        got = res.default(name)
        if got is not None:
            fact(name, repr(got), "nextcloud" not in got.lower())
    ua_tpl, branded = res.default("string/nextcloud_user_agent"), res.default("string/name_for_branded_user_agent")
    if ua_tpl is not None and branded is not None:
        ua = ua_tpl.replace("%1$s", vname).replace("%2$s", branded)
        mm = SERVER_UA_ANDROID.match(ua)
        fact("user agent", f"'{ua}' -> server version group {mm.group(1)!r}" if mm else f"'{ua}' does not match",
             bool(mm) and mm.group(1) == vname)
    office = res.default("string/office_user_agent")
    if office is not None:
        fact("office user agent", "'" + office.replace("%1$s", "<release>").replace("%2$s", vname) + "'")

    # --- Nextcloud URLs and hosts, and text values naming Nextcloud: resource table
    res_hits = []
    text_hits: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for name, vals in sorted(res.values.items()):
        for cfg, v in vals:
            for u in URL_RE.findall(v):
                res_hits.append((name, cfg or "default", u))
            if name.split("/")[0] in ("string", "plurals", "array") and NAME_RE.search(v):
                text_hits[name].append((cfg or "default", unquote(v)))
    # --- dex strings (full string -> dex files) and every other file
    dex_hits: dict[str, set[str]] = defaultdict(set)
    other_hits: dict[str, set[str]] = defaultdict(set)
    nc_dex_strings = 0
    users: dict[str, set[str]] = defaultdict(set)
    dexdump = Path(apk.aapt2).with_name("dexdump" + Path(apk.aapt2).suffix)
    with zipfile.ZipFile(apk.apk) as z, tempfile.TemporaryDirectory() as tmp:
        for info in z.infolist():
            data = z.read(info)
            if re.fullmatch(r"classes\d*\.dex", info.filename):
                found = False
                for s in dex_strings(data):
                    if "nextcloud" in s.lower():
                        nc_dex_strings += 1
                    if URL_RE.search(s):
                        dex_hits[s].add(info.filename)
                        found = True
                if found and dexdump.is_file():
                    dex_file = Path(tmp) / info.filename
                    dex_file.write_bytes(data)
                    find_users(dexdump, dex_file, set(dex_hits), users)
            elif info.filename != "resources.arsc":
                for u in URL_RE.findall(data.decode("latin-1")):
                    other_hits[u].add(info.filename)

    lines.append(f"# APK check: {apk.apk.name}\n")
    lines.append(f"aapt2: `{apk.aapt2}`\n")
    lines.append("## Identity and side-by-side ids\n")
    lines.append("| Check | Result |\n|---|---|")
    lines.extend(f"| {k} | {v.replace('|', '/')} |" for k, v in facts)
    lines.append(f"\n## Nextcloud URLs and hosts in the resource table ({len(res_hits)})\n")
    lines.append("| Resource | Config | Match |\n|---|---|---|")
    lines.extend(f"| {n} | {c} | `{u}` |" for n, c, u in res_hits)
    lines.append(f"\n## String resources whose text names Nextcloud or ownCloud ({len(text_hits)})\n")
    lines.append("Includes resources merged from libraries, which the source sweep does not see.\n")
    lines.append("| Resource | Configs | Default value |\n|---|---|---|")
    for name, vals in sorted(text_hits.items()):
        default = next((v for c, v in vals if c == "default"), vals[0][1])
        default = default if len(default) <= 120 else default[:117] + "..."
        lines.append(f"| {name} | {len(vals)} | `{default.replace('|', '/')}` |")
    lines.append(f"\n## Nextcloud URLs and hosts in dex strings ({len(dex_hits)} distinct)\n")
    lines.append(f"Dex strings containing 'nextcloud' in any form (class names, packages, URLs): {nc_dex_strings}\n")
    lines.append(f"Code that loads each string (`dexdump -d`, const-string): "
                 f"{'yes' if dexdump.is_file() else 'dexdump not found'}\n")
    lines.append("| Dex string | Dex files | Loaded by |\n|---|---|---|")
    for text, files in sorted(dex_hits.items()):
        who = sorted(users.get(text, ()))
        shown = text if len(text) <= 160 else text[:157] + "..."
        loaded = "<br>".join(who[:6]) + (" ..." if len(who) > 6 else "") if who else "no const-string use"
        lines.append(f"| `{shown.replace('|', '/')}` | {', '.join(sorted(files))} | {loaded} |")
    lines.append(f"\n## Nextcloud URLs and hosts in other APK files ({len(other_hits)} distinct)\n")
    lines.append("| Match | Files |\n|---|---|")
    lines.extend(f"| `{u}` | {', '.join(sorted(f)[:8])}{' ...' if len(f) > 8 else ''} |"
                 for u, f in sorted(other_hits.items()))
    lines.append("\n## Nextcloud GmbH service hosts\n")
    lines.append("| Host | resource table | dex | other files |\n|---|---|---|---|")
    for h in SERVICE_HOSTS:
        def count(items):
            return sum(1 for u in items if h in u.lower())
        lines.append(f"| {h} | {count(u for _, _, u in res_hits)} | {count(dex_hits)} | {count(other_hits)} |")
    lines.append(f"\n## Result\n\n{'FAIL: ' + str(len(fails)) + ' identity check(s) failed' if fails else 'PASS'}\n")
    lines.extend(f"- {f}" for f in fails)
    report = "\n".join(lines) + "\n"
    if args.report:
        Path(args.report).parent.mkdir(parents=True, exist_ok=True)
        Path(args.report).write_text(report, encoding="utf-8")
    print(report)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
