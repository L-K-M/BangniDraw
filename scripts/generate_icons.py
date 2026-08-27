#!/usr/bin/env python3
"""Generate the launcher assets from media-sources/icon.png.

The artwork is used FULL-BLEED as the adaptive icon's foreground layer so
foreground-only launcher surfaces retain it. Launchers apply
their own mask (circle, squircle, rounded square); a solid indigo background
covers any edge exposed by motion. The themed/monochrome variant remains a
hand-authored brush silhouette in res/drawable/.

Per density this writes mipmap-<dpi>/ic_launcher_bg.png at the 108dp
adaptive canvas size. minSdk is 29, so no pre-8.0 legacy PNGs are needed.

Usage: python3 scripts/generate_icons.py
Requirements: Pillow.
"""
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SOURCE = os.path.join(ROOT, "media-sources", "icon.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
ADAPTIVE_DP = 108  # adaptive icon canvas; the visible safe zone is the centre 66dp


def main() -> None:
    src = Image.open(SOURCE).convert("RGB")
    if src.width != src.height:
        raise SystemExit(f"!! icon must be square, got {src.width}x{src.height}")
    for density, scale in DENSITIES.items():
        out_dir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        px = round(ADAPTIVE_DP * scale)
        out = os.path.join(out_dir, "ic_launcher_bg.png")
        src.resize((px, px), Image.LANCZOS).save(out, optimize=True)
        print(f"-- {os.path.relpath(out, ROOT)} ({px}px)")
    print("==> icons generated")


if __name__ == "__main__":
    main()
