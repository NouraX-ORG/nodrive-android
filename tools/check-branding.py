#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Keyword sweep for Nextcloud branding in what the NoDrive Android app ships (spec section 6).

Usage:
    python tools/check-branding.py [--source] [--root DIR] [--flavour nodrive] [--report FILE]

It looks at what ends up in the <flavour>Debug variant, after Android's resource and manifest
merge (higher source sets win: debug > <flavour> > main):
- every file under res/ (all values-* languages, layouts, menus, xml, raw, drawables, images),
- the AndroidManifest.xml files (elements a higher manifest removes or replaces are skipped),
- the store metadata in src/<flavour>/fastlane/ (text and images).
The upstream "generic" source set adds only Java/Kotlin stubs to nodrive (no resources, no
manifest), so it has nothing of these file types.

Keywords (case-insensitive): nextcloud (covers "nextcloud hub", "nextcloud gmbh", every
*.nextcloud.com link, github.com/nextcloud and com.nextcloud ids), "next cloud", owncloud,
mirall, it.niedermann, and the colour codes 0082c9 / 00679e. Images: vector drawables are
checked for Nextcloud logo path data, raster images against SHA-256 fingerprints of upstream's
Nextcloud art; every shipped raster image is listed so a person can look at it.

Each hit becomes one "subject" line such as
    value: string/app_name = Nextcloud
    attr: android:name=com.nextcloud.client.widget.DashboardWidgetProvider
    comment: SPDX-FileCopyrightText: 2016-2024 Nextcloud GmbH ...
and is classified by tools/branding/allowlist.txt (first matching entry wins):
    pattern | <python regex, searched in the subject> | internal|legal | reason
    path    | <glob on the repo-relative file path>   | internal|legal | reason
Exit code 1 when any hit is not covered by the allowlist (an unclassified, possibly
user-visible hit), 0 otherwise.
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import re
import sys
import xml.parsers.expat
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

HERE = Path(__file__).resolve().parent
KEYWORDS = [
    ("nextcloud", re.compile(r"nextcloud", re.I)),
    ("next cloud", re.compile(r"next\s+cloud", re.I)),
    ("owncloud", re.compile(r"owncloud", re.I)),
    ("mirall", re.compile(r"mirall", re.I)),
    ("it.niedermann", re.compile(r"it\.niedermann", re.I)),
    ("#0082c9", re.compile(r"0082c9", re.I)),
    ("#00679e", re.compile(r"00679e", re.I)),
]
LINK_LABELS = ["nextcloud.com/privacy", "nextcloud.com/impressum", "push-notifications.nextcloud",
               "updates.nextcloud", "help.nextcloud", "docs.nextcloud", "apps.nextcloud", "github.com/nextcloud",
               "nextcloud.com", "com.nextcloud", "nextcloud gmbh", "nextcloud hub"]
# Start of the path data of upstream's Nextcloud, Talk and Deck logos (app/src/main/res/drawable).
LOGO_PATHS = {
    "M532.68,320.01C439.33": "Nextcloud logo (ic_launcher_foreground, notification_icon)",
    "M450.68,21.01C357.33": "Nextcloud logo (logo)",
    "m37.338,9.375c-5.788": "Nextcloud wordmark (nextcloud_logo)",
    "M79.87,28.3C63.7": "Nextcloud logo and wordmark (nextcloud_splash_logo)",
    "m7.9992,0.999a6.9993": "Nextcloud Talk logo (ic_talk)",
    "M63.992,0.689C29.031": "Nextcloud Talk logo (first_run_talk)",
    "M2,7L14,7A1,1 0,0 1,15 8": "Nextcloud Deck logo (ic_deck)",
}
# SHA-256 of upstream raster art that shows Nextcloud's logo, colours or app (tag stable-35.0.1).
NEXTCLOUD_IMAGES = {
    "d520883ab079195dcda896cb27837ba170dc2c7c26b8dc45f053bb9f58c2b396": "Nextcloud launcher icon (mdpi)",
    "c7c092bfdec620991dc96f0c9ee8d0537bed7f34a073a95608039b8fe03414ec": "Nextcloud launcher icon (hdpi)",
    "6a513e61a1767c7a061144c40ac219daf47934850ed0cd1962fdc240c0da3873": "Nextcloud launcher icon (xhdpi)",
    "21a4080c5953095946ec8d8780ca8ebe1c257c0215727520170e813abfb43d41": "Nextcloud launcher icon (xxhdpi)",
    "130cb1ad212a2c75f1f06c7a8ba9bc0815744275a762d3320e837bea42ca08b6": "Nextcloud launcher/store icon (xxxhdpi)",
    "e8f1276b2650748b2b02d8cd3be00b08523da9fd02ceb3a35770f0800195951f": "Nextcloud blue gradient (background_nc18)",
    "9a3c4e58892d9698b98d82cdba9a74cc2080e4f4c8b16860de964589ce1c8be2": "Nextcloud dotted world map (background)",
    "1791f430290e2adff52d3dfe2a8b4c15fd2bda700b3a26e724c9aedf23f46243": "Nextcloud store screenshot 1",
    "0ab1a16281ad8d446c658b6132b31494553715a6a14b59d4cc942840de73ae3e": "Nextcloud store screenshot 2",
    "2978682f16310317dd2dd69af19a029a4dc0dd5c2df062a597096e6fbf2cb3a0": "Nextcloud store screenshot 3",
    "fc973f4388cb46ee2c17fdd9bad31b29c03fc4764d933971c60f41b09f9824eb": "Nextcloud store screenshot 4",
    "939ab7f669ba172f3f08b86ade4e82afa2378e03e0f7987386e374e87be72743": "Nextcloud store screenshot 5",
    "e658f4d2a7f772b02b54adf42dfd187d2ba40f88a4cf8b30fd1961cc748d8202": "Nextcloud store screenshot 6",
    "dd85756bd88b520630bcfef62f4a84476e2888ec4cb0d6f94c1a9bc92b3306bb": "Nextcloud store screenshot 7",
    "85f651514a7810eacde4d6e132fc1a1ebf1627bd0493c691642ddbe80e1bfa9a": "Nextcloud store screenshot 8",
}
RASTER = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
TEXT = {".txt", ".html", ".htm", ".json", ".md", ".properties"}
NAMESPACE = "com.owncloud.android"  # android.namespace in app/build.gradle.kts


@dataclass
class Hit:
    path: str
    line: int
    subject: str
    keyword: str
    cls: str = ""
    reason: str = ""


@dataclass
class Rule:
    kind: str
    value: str
    cls: str
    reason: str
    line: int
    rx: re.Pattern | None = None
    used: int = 0


def searchable(subject: str) -> str:
    """The part of a subject that is searched: for resource values only the text after " = ",
    so a resource *name* such as placeholder_search_in_nextcloud is reported once, as resname."""
    if subject.startswith("value: ") and " = " in subject:
        return subject.split(" = ", 1)[1]
    return subject


def keyword_of(text: str) -> str | None:
    text = searchable(text)
    for label, rx in KEYWORDS:
        if rx.search(text):
            if label == "nextcloud":
                low = text.lower()
                for link in LINK_LABELS:
                    if link in low:
                        return link
            return label
    return None


def load_allowlist(path: Path) -> list[Rule]:
    rules = []
    for n, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split(" | ")]
        if len(parts) != 4 or parts[0] not in ("pattern", "path") or parts[2] not in ("internal", "legal"):
            sys.exit(f"{path}:{n}: expected 'pattern|path | value | internal|legal | reason', got: {raw}")
        rule = Rule(parts[0], parts[1], parts[2], parts[3], n)
        if rule.kind == "pattern":
            try:
                rule.rx = re.compile(rule.value)
            except re.error as e:
                sys.exit(f"{path}:{n}: bad regex: {e}")
        rules.append(rule)
    return rules


def classify(hit: Hit, rules: list[Rule]) -> None:
    for r in rules:
        if (r.kind == "path" and fnmatch.fnmatchcase(hit.path, r.value)) or \
                (r.kind == "pattern" and r.rx.search(hit.subject)):
            r.used += 1
            hit.cls, hit.reason = r.cls, f"{r.reason} [allowlist line {r.line}]"
            return


# ----------------------------------------------------------------------------------------------
# XML scanning with line numbers
# ----------------------------------------------------------------------------------------------
class XmlScan:
    """Expat walk that yields (line, subject) for every comment, tag, attribute and text node."""

    def __init__(self, data: bytes, values: bool):
        self.values = values
        self.items: list[tuple[int, str, tuple | None]] = []  # (line, subject, resource key or None)
        self.stack: list[tuple[str, dict, int, list[str]]] = []  # name, attrs, line, text buffer
        self.res_key: tuple | None = None
        p = xml.parsers.expat.ParserCreate()
        p.StartElementHandler = self.start
        p.EndElementHandler = self.end
        p.CharacterDataHandler = self.chars
        p.CommentHandler = lambda c: self.items.append((p.CurrentLineNumber, "comment: " + norm(c), self.res_key))
        self.p = p
        p.Parse(data, True)

    def chars(self, data):
        if self.stack:
            self.stack[-1][3].append(data)

    def start(self, name, attrs):
        line = self.p.CurrentLineNumber
        depth = len(self.stack)
        if self.values and depth == 1:  # a top-level resource
            rtype = attrs.get("type", name) if name == "item" else name
            self.res_key = (rtype, attrs.get("name", ""))
            self.items.append((line, f"resname: {rtype}/{attrs.get('name', '')}", self.res_key))
        if not self.values or depth == 0 or depth >= 3:
            self.items.append((line, f"tag: {name}", self.res_key))
        for k, v in attrs.items():
            if self.values and depth >= 1 and k == "name":
                if depth == 2:
                    self.items.append((line, f"resname: item {v}", self.res_key))
                continue
            self.items.append((line, f"attr: {k}={v}", self.res_key))
        self.stack.append((name, attrs, line, []))

    def end(self, name):
        _, attrs, line, buf = self.stack.pop()
        text = norm("".join(buf))
        depth = len(self.stack)
        if self.values and depth >= 2 and (name != "item" or depth >= 3):
            self.stack[-1][3].append("".join(buf))  # text of <xliff:g> and similar belongs to its parent
        if text:
            if not self.values:
                self.items.append((line, f"text: {text}", self.res_key))
            elif self.res_key and depth == 1:
                self.items.append((line, f"value: {'/'.join(self.res_key)} = {text}", self.res_key))
            elif self.res_key and depth == 2 and name == "item":
                label = "/".join([*self.res_key] + ([attrs["name"]] if "name" in attrs else []))
                self.items.append((line, f"value: {label} = {text}", self.res_key))
        if self.values and depth == 1:
            self.res_key = None


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()


# ----------------------------------------------------------------------------------------------
# What ships: effective files after the source-set merge
# ----------------------------------------------------------------------------------------------
def source_sets(root: Path, flavour: str) -> list[tuple[str, Path]]:
    return [(s, root / "app" / "src" / s) for s in ("debug", flavour, "main") if (root / "app" / "src" / s).is_dir()]


def manifest_overrides(manifest: Path) -> set[tuple[str, str]]:
    """(tag, fully-qualified android:name) of elements a manifest removes or replaces."""
    out = set()
    scan_stack = []

    def start(name, attrs):
        node = attrs.get("tools:node")
        if node in ("remove", "replace", "removeAll") and "android:name" in attrs:
            out.add((name, fq(attrs["android:name"])))
        scan_stack.append(name)

    p = xml.parsers.expat.ParserCreate()
    p.StartElementHandler = start
    p.Parse(manifest.read_bytes(), True)
    return out


def fq(name: str) -> str:
    return NAMESPACE + name if name.startswith(".") else name


def scan_manifest(path: Path, rel: str, skip: set[tuple[str, str]], hits: list[Hit], shadow: Counter) -> None:
    """Scan a manifest, skipping the subtree of elements that a higher manifest removes or replaces."""
    items = []
    stack: list[bool] = []
    text: list[str] = []
    p = xml.parsers.expat.ParserCreate()

    def skipping() -> bool:
        return any(stack)

    def start(name, attrs):
        skip_this = (name, fq(attrs.get("android:name", ""))) in skip
        stack.append(skip_this)
        line = p.CurrentLineNumber
        items.append((skipping(), line, f"tag: {name}"))
        items.extend((skipping(), line, f"attr: {k}={v}") for k, v in attrs.items())
        text.clear()

    def end(name):
        t = norm("".join(text))
        if t:
            items.append((skipping(), p.CurrentLineNumber, f"text: {t}"))
        text.clear()
        stack.pop()

    p.StartElementHandler = start
    p.EndElementHandler = end
    p.CharacterDataHandler = lambda d: text.append(d)
    p.CommentHandler = lambda c: items.append((skipping(), p.CurrentLineNumber, "comment: " + norm(c)))
    p.Parse(path.read_bytes(), True)
    for skipped, line, subject in items:
        kw = keyword_of(subject)
        if not kw:
            continue
        if skipped:
            shadow["manifest elements removed/replaced by a higher manifest"] += 1
        else:
            hits.append(Hit(rel, line, subject, kw))


def check_image(path: Path, rel: str, hits: list[Hit], images: list[str]) -> None:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    images.append(rel)
    if digest in NEXTCLOUD_IMAGES:
        hits.append(Hit(rel, 0, f"image: {NEXTCLOUD_IMAGES[digest]}", "nextcloud art"))


def check_vector_logo(text: str, rel: str, hits: list[Hit]) -> None:
    for start, label in LOGO_PATHS.items():
        if f'pathData="{start}' in text:
            hits.append(Hit(rel, 0, f"image: {label}", "nextcloud logo"))


def scan_text_file(path: Path, rel: str, hits: list[Hit]) -> None:
    for n, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        kw = keyword_of(line)
        if kw:
            hits.append(Hit(rel, n, "line: " + norm(line), kw))


def sweep(root: Path, flavour: str) -> tuple[list[Hit], Counter, list[str], list[str]]:
    hits: list[Hit] = []
    shadow: Counter = Counter()
    images: list[str] = []
    scanned: list[str] = []
    sets = source_sets(root, flavour)

    # 1. resources: non-values files win per (folder, name without extension)
    seen_files: set[tuple[str, str]] = set()
    seen_values: set[tuple[str, str, str]] = set()
    for _, sdir in sets:
        res = sdir / "res"
        if not res.is_dir():
            continue
        for path in sorted(res.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(root).as_posix()
            folder = path.parent.name
            if folder.startswith("values"):
                continue
            key = (folder, path.name.split(".")[0])
            name_kw = keyword_of(path.name)
            if key in seen_files:
                if name_kw:
                    shadow["file names in overridden resource files"] += 1
                shadow["overridden resource files (not shipped)"] += 1
                continue
            seen_files.add(key)
            scanned.append(rel)
            if name_kw:
                hits.append(Hit(rel, 0, f"file: {path.relative_to(res).as_posix()}", name_kw))
            if path.suffix.lower() in RASTER:
                check_image(path, rel, hits, images)
            elif path.suffix.lower() == ".xml":
                data = path.read_bytes()
                check_vector_logo(data.decode("utf-8", errors="replace"), rel, hits)
                for line, subject, _ in XmlScan(data, values=False).items:
                    kw = keyword_of(subject)
                    if kw:
                        hits.append(Hit(rel, line, subject, kw))
            elif path.suffix.lower() in TEXT:
                scan_text_file(path, rel, hits)
            else:
                hits.append(Hit(rel, 0, f"unknown file type: {path.name}", "unscanned"))
        # values: per resource
        for vdir in sorted(p for p in res.iterdir() if p.is_dir() and p.name.startswith("values")):
            batch: list[tuple[int, str, tuple | None, str]] = []
            keys_here: set[tuple[str, str, str]] = set()
            for path in sorted(vdir.glob("*.xml")):
                rel = path.relative_to(root).as_posix()
                scanned.append(rel)
                for line, subject, key in XmlScan(path.read_bytes(), values=True).items:
                    full = (vdir.name, *key) if key else None
                    if full:
                        keys_here.add(full)
                    batch.append((line, subject, full, rel))
            for line, subject, full, rel in batch:
                kw = keyword_of(subject)
                if not kw:
                    continue
                if full and full in seen_values:
                    shadow["values overridden by a higher source set (not shipped)"] += 1
                    continue
                hits.append(Hit(rel, line, subject, kw))
            seen_values |= keys_here

    # 2. manifests
    skip: set[tuple[str, str]] = set()
    for _, sdir in sets:
        m = sdir / "AndroidManifest.xml"
        if m.is_file():
            rel = m.relative_to(root).as_posix()
            scanned.append(rel)
            scan_manifest(m, rel, skip, hits, shadow)
            skip |= manifest_overrides(m)

    # 3. store metadata of this flavour
    fl = root / "src" / flavour / "fastlane"
    if fl.is_dir():
        for path in sorted(fl.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(root).as_posix()
            scanned.append(rel)
            if keyword_of(path.name):
                hits.append(Hit(rel, 0, f"file: {path.name}", keyword_of(path.name)))
            if path.suffix.lower() in RASTER:
                check_image(path, rel, hits, images)
            else:
                scan_text_file(path, rel, hits)
    return hits, shadow, images, scanned


def report(hits, shadow, images, scanned, rules, flavour, root) -> str:
    by_cls = Counter(h.cls or "UNCLASSIFIED" for h in hits)
    out = [f"# Branding sweep ({flavour}Debug, root {root})", "",
           f"- Files scanned: {len(scanned)}; raster images shipped (look at them): {len(images)}",
           f"- Hits: {len(hits)} (internal {by_cls['internal']}, legal {by_cls['legal']}, "
           f"UNCLASSIFIED {by_cls['UNCLASSIFIED']})", ""]
    out.append("| Keyword | Hits |")
    out.append("|---|---|")
    for kw, n in Counter(h.keyword for h in hits).most_common():
        out.append(f"| {kw} | {n} |")
    out.append("")
    if shadow:
        out.append("Not shipped (shadowed by a higher source set or removed by the merged manifest):")
        out += [f"- {k}: {v}" for k, v in sorted(shadow.items())]
        out.append("")
    unc = [h for h in hits if not h.cls]
    out.append(f"## Unclassified hits ({len(unc)})")
    out.append("")
    out += [f"- `{h.path}:{h.line}` [{h.keyword}] {h.subject[:220]}" for h in unc] or ["None."]
    out.append("")
    out.append("## Classified hits by allowlist entry")
    out.append("")
    groups = defaultdict(list)
    for h in hits:
        if h.cls:
            groups[(h.cls, h.reason)].append(h)
    for (cls, reason), hs in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        out.append(f"- **{cls}** ({len(hs)}): {reason}. Example: `{hs[0].path}:{hs[0].line}` {hs[0].subject[:120]}")
    unused = [r for r in rules if not r.used]
    if unused:
        out.append("")
        out.append("Allowlist entries with no hit in this tree: " + ", ".join(f"line {r.line}" for r in unused))
    out.append("")
    out.append("## Shipped raster images")
    out.append("")
    out += [f"- {p}" for p in images]
    return "\n".join(out) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--source", action="store_true", help="sweep the source tree (default and only mode)")
    ap.add_argument("--root", default=str(HERE.parent), help="tree to sweep (default: this repo)")
    ap.add_argument("--flavour", default="nodrive", help="product flavour whose variant is swept")
    ap.add_argument("--allowlist", default=str(HERE / "branding" / "allowlist.txt"))
    ap.add_argument("--report", help="write a Markdown report to this file")
    ap.add_argument("--max-print", type=int, default=60, help="unclassified hits printed to the console")
    args = ap.parse_args()

    root = Path(args.root).resolve()
    rules = load_allowlist(Path(args.allowlist))
    hits, shadow, images, scanned = sweep(root, args.flavour)
    for h in hits:
        classify(h, rules)
    text = report(hits, shadow, images, scanned, rules, args.flavour, root)
    if args.report:
        Path(args.report).write_text(text, encoding="utf-8")
    unc = [h for h in hits if not h.cls]
    by_cls = Counter(h.cls or "UNCLASSIFIED" for h in hits)
    print(f"check-branding: {args.flavour}Debug: {len(scanned)} files, {len(images)} raster images, "
          f"{len(hits)} hits: internal {by_cls['internal']}, legal {by_cls['legal']}, "
          f"unclassified {len(unc)}")
    for h in unc[:args.max_print]:
        print(f"  UNCLASSIFIED {h.path}:{h.line} [{h.keyword}] {h.subject[:200]}")
    if len(unc) > args.max_print:
        print(f"  ... {len(unc) - args.max_print} more (use --report)")
    if unc:
        print("check-branding: FAIL - fix these user-visible hits or classify true internal/legal ones "
              "in tools/branding/allowlist.txt")
        return 1
    print("check-branding: PASS - no unclassified hit")
    return 0


if __name__ == "__main__":
    sys.exit(main())
