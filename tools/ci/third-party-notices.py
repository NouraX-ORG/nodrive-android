#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Write THIRD-PARTY-NOTICES.txt for the NoDrive APK artifact (CI only; see NOTICE.md).

Usage:
    ./gradlew -q --no-configuration-cache :app:dependencies \
        --configuration nodriveDebugRuntimeClasspath > deps.txt
    python tools/ci/third-party-notices.py --deps deps.txt --out THIRD-PARTY-NOTICES.txt \
        [--gradle-home ~/.gradle] [--version 35.0.1-nourax.1] [--source-url URL]

It reads the resolved dependency tree that Gradle's "dependencies" task prints (the libraries
packed into the APK), then for every library in Gradle's download cache
(<gradle-home>/caches/modules-2/files-2.1/<group>/<name>/<version>/):
- the licence names and URLs of its POM (following <parent> POMs when the POM has none);
- every NOTICE file inside its jar or aar (also inside the aar's classes.jar and libs/*.jar),
  copied verbatim and once per distinct text.
Apache-2.0 section 4(d) asks that such NOTICE texts travel with the binary; this file does that
for the CI artifact. Nothing is downloaded: the build has already resolved every library.

Exit code 1 when the tree lists no library, the cache folder is missing, or more than half of the
libraries cannot be found in the cache (then the lookup itself is broken). A few libraries
missing from the cache are listed as such in the file and reported as a warning.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

TREE_LINE = re.compile(r"^[| ]*[+\\]--- (.+)$")
NOTICE_NAME = re.compile(r"^(?:.*[-_.])?notices?(?:\.(?:txt|md|html?))?$", re.I)
MAX_NOTICE_BYTES = 256 * 1024
MAX_PARENT_DEPTH = 6


def parse_tree(text: str) -> list[tuple[str, str, str]]:
    """Return the sorted, unique (group, name, version) of a Gradle dependency tree."""
    found: set[tuple[str, str, str]] = set()
    for line in text.splitlines():
        m = TREE_LINE.match(line.rstrip())
        if not m:
            continue
        item = m.group(1).strip()
        if item.startswith("project "):
            continue  # another module of this build, not a third-party library
        if re.search(r"\((?:c|n)\)$", item):
            continue  # a constraint, or a configuration that cannot be resolved
        item = re.sub(r"\s*\((?:\*|[^)]*by rule[^)]*)\)$", "", item)
        item = re.sub(r"\s+FAILED$", "", item)
        resolved = None
        if " -> " in item:
            item, resolved = item.split(" -> ", 1)
            resolved = re.sub(r"\s*\(.*\)$", "", resolved).strip()
        parts = item.split(":")
        if len(parts) < 2:
            continue
        group, name = parts[0].strip(), parts[1].strip()
        version = resolved or (parts[2].strip() if len(parts) > 2 else "")
        version = re.sub(r"^\{strictly ([^}]+)\}$", r"\1", version)
        if group and name and version:
            found.add((group, name, version))
    return sorted(found)


def module_files(cache: Path, group: str, name: str, version: str) -> list[Path]:
    folder = cache / group / name / version
    if not folder.is_dir():
        return []
    return sorted(p for p in folder.glob("*/*") if p.is_file())


def pick(files: list[Path], suffix: str, name: str, version: str) -> Path | None:
    exact = [p for p in files if p.name == f"{name}-{version}{suffix}"]
    if exact:
        return exact[0]
    others = [p for p in files if p.suffix == suffix and not re.search(r"-(sources|javadoc)\.jar$", p.name)]
    return others[0] if others else None


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def child(elem: ET.Element, tag: str) -> ET.Element | None:
    for c in elem:
        if local(c.tag) == tag:
            return c
    return None


def text_of(elem: ET.Element | None, tag: str) -> str:
    c = child(elem, tag) if elem is not None else None
    return " ".join((c.text or "").split()) if c is not None else ""


def pom_licences(cache: Path, pom: Path | None, depth: int = 0) -> list[str]:
    if pom is None or depth > MAX_PARENT_DEPTH:
        return []
    try:
        root = ET.parse(pom).getroot()
    except (ET.ParseError, OSError):
        return []
    out = []
    licences = child(root, "licenses")
    if licences is not None:
        for lic in licences:
            if local(lic.tag) != "license":
                continue
            name, url = text_of(lic, "name"), text_of(lic, "url")
            if name or url:
                out.append(f"{name} <{url}>" if name and url else name or url)
    if out:
        return out
    parent = child(root, "parent")
    if parent is None:
        return []
    g, a, v = text_of(parent, "groupId"), text_of(parent, "artifactId"), text_of(parent, "version")
    files = module_files(cache, g, a, v)
    parent_pom = pick(files, ".pom", a, v)
    found = pom_licences(cache, parent_pom, depth + 1)
    return [f"{x} (from parent {g}:{a}:{v})" for x in found] if found else []


def notices_in_zip(data: bytes, prefix: str = "") -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        return out
    for info in z.infolist():
        if info.is_dir():
            continue
        base = info.filename.rsplit("/", 1)[-1]
        if info.filename.endswith(".jar") and (info.filename == "classes.jar" or info.filename.startswith("libs/")):
            out += notices_in_zip(z.read(info), f"{prefix}{info.filename}!/")
        elif NOTICE_NAME.match(base) and info.file_size <= MAX_NOTICE_BYTES:
            out.append((prefix + info.filename, z.read(info).decode("utf-8", errors="replace")))
    return out


def normalise(text: str) -> str:
    return "\n".join(line.rstrip() for line in text.replace("\r\n", "\n").strip().split("\n"))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--deps", required=True, type=Path, help="output of the Gradle dependencies task")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--gradle-home", type=Path,
                    default=Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")))
    ap.add_argument("--version", default="", help="app versionName, for the title")
    ap.add_argument("--source-url", default="", help="where the source code of this exact build is")
    args = ap.parse_args()

    modules = parse_tree(args.deps.read_text(encoding="utf-8", errors="replace"))
    if not modules:
        print(f"third-party-notices: no library found in {args.deps}", file=sys.stderr)
        return 1
    cache = args.gradle_home / "caches" / "modules-2" / "files-2.1"
    if not cache.is_dir():
        print(f"third-party-notices: Gradle cache {cache} does not exist", file=sys.stderr)
        return 1

    rows: list[tuple[str, list[str], str]] = []
    notices: dict[str, tuple[str, list[str]]] = {}  # hash -> (text, ["coord: path", ...])
    missing, bom_only = [], 0
    for group, name, version in modules:
        coord = f"{group}:{name}:{version}"
        files = module_files(cache, group, name, version)
        if not files:
            missing.append(coord)
            rows.append((coord, [], "not found in the Gradle cache"))
            continue
        pom = pick(files, ".pom", name, version)
        binary = pick(files, ".aar", name, version) or pick(files, ".jar", name, version)
        if binary is None:
            bom_only += 1
            # Metadata only: a BOM/platform, or a Kotlin multiplatform root module whose jar lives in its
            # -android/-jvm module (listed in the tree as its own entry). Nothing of it is in the APK.
            continue
        rows.append((coord, pom_licences(cache, pom), ""))
        for path, text in notices_in_zip(binary.read_bytes()):
            key = hashlib.sha256(normalise(text).encode()).hexdigest()
            notices.setdefault(key, (normalise(text), []))[1].append(f"{coord}: {path}")

    title = f"NoDrive for Android {args.version}".rstrip() + ": third-party notices"
    # versionName <upstream>-nourax.<n> comes from upstream's tag stable-<upstream>.
    base = re.match(r"^(\d+\.\d+\.\d+)-nourax\.\d+$", args.version)
    upstream = f", tag stable-{base.group(1)}" if base else ""
    lines = [title, "=" * len(title), "",
             "This file goes with app-nodrive-debug.apk. CI writes it from the libraries Gradle packs",
             "into the APK (the nodriveDebugRuntimeClasspath configuration).", "",
             "1. NoDrive", "",
             "NoDrive for Android by Nourax is a modified version of the Nextcloud Android app",
             f"(https://github.com/nextcloud/android{upstream}). The program is licensed under the",
             "GNU General Public License, version 2 (LICENSE.txt next to this file); many source files are",
             "also available under AGPL-3.0-or-later, as their headers say. NOTICE.md next to this file",
             "lists trademarks and what Nourax changed."]
    if args.source_url:
        lines += [f"Source code of this exact build: {args.source_url}"]
    with_licence = sum(1 for _, lic, note in rows if lic)
    lines += ["", f"2. Libraries in the APK ({len(rows)})", "",
              "Licences as each library's Maven POM states them. \"Not stated\" means the POM names no",
              "licence; see the library's own project.", ""]
    for coord, lic, note in rows:
        lines.append(coord)
        if note:
            lines.append(f"    Licence: unknown ({note})")
        elif lic:
            lines += [f"    Licence: {x}" for x in lic]
        else:
            lines.append("    Licence: not stated in the POM")
    lines += ["", f"3. NOTICE files of these libraries ({len(notices)} distinct texts), verbatim", ""]
    for text, where in sorted(notices.values(), key=lambda n: n[1][0]):
        lines.append("-" * 78)
        lines += [f"From {w}" for w in where]
        lines += ["", text, ""]
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")

    print(f"third-party-notices: {len(rows)} libraries ({with_licence} with a licence in the POM), "
          f"{bom_only} metadata-only entries (BOM, multiplatform root) skipped, {len(notices)} distinct NOTICE texts, "
          f"{len(missing)} not in the Gradle cache -> {args.out}")
    if missing:
        print(f"::warning::third-party-notices: not in the Gradle cache: {', '.join(missing[:20])}")
    if len(missing) * 2 > len(modules):
        print("third-party-notices: most libraries are missing from the cache; the lookup is broken",
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
