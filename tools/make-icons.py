#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Nourax
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Make every NoDrive Android image from the one Nourax logo.

Usage:
    python tools/make-icons.py           # write all images
    python tools/make-icons.py --check   # exit 1 if any image is missing or differs

Rules (spec section 3, owner decisions of 2026-10-25):
- The only source is branding/nourax-logo.png (500x500 RGBA). Its SHA-256 is pinned below,
  so an edited logo is refused.
- The whole logo image is scaled proportionally. It is never cropped, recoloured, redrawn
  or stretched. Enlarging above 500 px happens only on a plain white square (approved).
- No monochrome variant is made (themed icon and notification icon are owner to-dos).
- Output is compared by pixels (mode, size, RGBA data), not by PNG bytes, so different
  zlib builds cannot make --check fail.

Needs Pillow (tested with 12.2.0).
"""
from __future__ import annotations

import argparse
import hashlib
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
LOGO = ROOT / "branding" / "nourax-logo.png"
LOGO_SHA256 = "74a8b3fdcb8b16290698f3afb0bdbb46d6a9d89e6987f5cd0d26ad45f9ebfa8a"
LOGO_SIZE = (500, 500)

WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)
COBALT = (0x25, 0x63, 0xEB, 255)  # THEME_BIBLE primary #2563EB

RES = "app/src/nodrive/res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def load_logo() -> tuple[Image.Image, tuple[int, int, int, int]]:
    data = LOGO.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if digest != LOGO_SHA256:
        sys.exit(f"make-icons: {LOGO} has SHA-256 {digest}, expected {LOGO_SHA256}. "
                 "The logo must not be edited.")
    logo = Image.open(LOGO)
    logo.load()
    if logo.size != LOGO_SIZE or logo.mode != "RGBA":
        sys.exit(f"make-icons: {LOGO} must be {LOGO_SIZE} RGBA, got {logo.size} {logo.mode}")
    bbox = logo.getchannel("A").getbbox()  # visible part of the logo
    return logo, bbox


def scaled_logo(logo: Image.Image, scale: float) -> Image.Image:
    """Scale the whole logo image proportionally (premultiplied alpha, Lanczos)."""
    side = max(1, round(LOGO_SIZE[0] * scale))
    return logo.convert("RGBa").resize((side, side), Image.Resampling.LANCZOS).convert("RGBA")


def centred(canvas: Image.Image, logo: Image.Image, scale: float) -> Image.Image:
    small = scaled_logo(logo, scale)
    x = (canvas.width - small.width) // 2
    y = (canvas.height - small.height) // 2
    canvas.alpha_composite(small, (x, y))
    return canvas


def visible_diagonal(bbox) -> float:
    return math.hypot(bbox[2] - bbox[0], bbox[3] - bbox[1])


def white_circle(size: int, diameter: float) -> Image.Image:
    """Plain white disc on a transparent square, anti-aliased by 4x supersampling.

    The mask is reduced with BOX (area average): Lanczos ringing would leave faint alpha up to
    the image border, and lint's IconLauncherShape round-icon test (IconDetector.isRound: oval
    over the bounding box of all non-transparent pixels, at most 4 % different) then fails at
    48 and 72 px.
    """
    big = size * 4
    mask = Image.new("L", (big, big), 0)
    r = diameter * 4 / 2
    c = big / 2
    ImageDraw.Draw(mask).ellipse((c - r, c - r, c + r, c + r), fill=255)
    mask = mask.resize((size, size), Image.Resampling.BOX)
    disc = Image.new("RGBA", (size, size), WHITE)
    disc.putalpha(mask)
    return disc


def legacy_launcher(logo, bbox, size: int) -> Image.Image:
    # 44/48 of the square is a white disc (legacy round icon). The logo's visible box
    # fits inside the disc with a margin, so nothing is cut off.
    diameter = size * 44 / 48
    scale = 0.86 * diameter / visible_diagonal(bbox)
    return centred(white_circle(size, diameter), logo, scale)


def adaptive_foreground(logo, bbox, size: int) -> Image.Image:
    # 108 dp layer; every mask shows at least the centred 66 dp circle. The logo's
    # visible box fits inside that circle, so no mask can crop it.
    safe = size * 66 / 108
    scale = 0.96 * safe / visible_diagonal(bbox)
    return centred(Image.new("RGBA", (size, size), CLEAR), logo, scale)


def logo_tile(logo, bbox, size: int = 640) -> Image.Image:
    # Logo at its own size (scale 1.0) on a plain white square. No enlargement.
    return centred(Image.new("RGBA", (size, size), WHITE), logo, 1.0)


def store_icon(logo, bbox, size: int = 512) -> Image.Image:
    # Play/F-Droid icon: plain white square, the logo's visible width is 80 % of it.
    scale = 0.80 * size / (bbox[2] - bbox[0])
    return centred(Image.new("RGBA", (size, size), WHITE), logo, scale)


def outputs() -> dict[str, Image.Image]:
    logo, bbox = load_logo()
    out: dict[str, Image.Image] = {}
    for dens, factor in DENSITIES.items():
        out[f"{RES}/mipmap-{dens}/ic_launcher.png"] = legacy_launcher(logo, bbox, round(48 * factor))
        out[f"{RES}/mipmap-{dens}/nodrive_ic_launcher_foreground.png"] = \
            adaptive_foreground(logo, bbox, round(108 * factor))
    out[f"{RES}/drawable-nodpi/nodrive_logo_tile.png"] = logo_tile(logo, bbox)
    # Not logos: they replace Nextcloud's blue gradient and dotted world map.
    out[f"{RES}/drawable-xxxhdpi/background_nc18.png"] = Image.new("RGB", (984, 656), COBALT[:3])
    out[f"{RES}/drawable-xxxhdpi/background.png"] = Image.new("RGBA", (984, 656), CLEAR)
    out["src/nodrive/fastlane/metadata/en-US/images/icon.png"] = store_icon(logo, bbox)
    return out


def same_pixels(path: Path, want: Image.Image) -> bool:
    if not path.is_file():
        return False
    with Image.open(path) as have:
        have.load()
        return have.mode == want.mode and have.size == want.size and have.tobytes() == want.tobytes()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="only compare, write nothing")
    args = ap.parse_args()

    bad = []
    for rel, img in outputs().items():
        path = ROOT / rel
        if same_pixels(path, img):
            continue
        bad.append(rel)
        if not args.check:
            path.parent.mkdir(parents=True, exist_ok=True)
            img.save(path, format="PNG", optimize=False)
    if args.check:
        for rel in bad:
            print(f"make-icons --check: differs or missing: {rel}")
        print(f"make-icons --check: {len(bad)} image(s) out of date" if bad else "make-icons --check: all images up to date")
        return 1 if bad else 0
    print(f"make-icons: wrote {len(bad)} image(s)" if bad else "make-icons: nothing to do")
    return 0


if __name__ == "__main__":
    sys.exit(main())
