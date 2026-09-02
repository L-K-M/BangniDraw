#!/usr/bin/env python3
"""Generate Android and desktop icons from media-sources/icon.png.

The artwork is used FULL-BLEED as the adaptive icon's foreground layer so
foreground-only launcher surfaces retain it. Launchers apply their own mask
(circle, squircle, rounded square); a solid indigo background covers any edge
exposed by motion. The themed/monochrome variant remains a hand-authored brush
silhouette in res/drawable/.

Per density this writes mipmap-<dpi>/ic_launcher_bg.png at the 108dp adaptive
canvas size. It also writes jpackage's Linux PNG and macOS ICNS derivatives.

Usage: python3 scripts/generate_icons.py
Requirements: Pillow (>= 9.1).
"""
import os

from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SOURCE = os.path.join(ROOT, "media-sources", "icon.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")
DESKTOP_ICONS = os.path.join(ROOT, "desktop", "packaging", "icons")
DESKTOP_RESOURCES = os.path.join(ROOT, "desktop", "src", "main", "resources")

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
ADAPTIVE_DP = 108  # adaptive icon canvas; the visible safe zone is the centre 66dp
DESKTOP_ICON_PX = 512
MAC_ICON_PX = 1024


def main() -> None:
    source = Image.open(SOURCE)
    if source.width != source.height:
        raise SystemExit(f"!! icon must be square, got {source.width}x{source.height}")
    android_source = source.convert("RGB")
    desktop_source = source.convert("RGBA")

    for density, scale in DENSITIES.items():
        out_dir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        px = round(ADAPTIVE_DP * scale)
        out = os.path.join(out_dir, "ic_launcher_bg.png")
        android_source.resize((px, px), Image.Resampling.LANCZOS).save(out, optimize=True)
        print(f"-- {os.path.relpath(out, ROOT)} ({px}px)")

    os.makedirs(DESKTOP_ICONS, exist_ok=True)
    os.makedirs(DESKTOP_RESOURCES, exist_ok=True)
    desktop = desktop_source.resize((DESKTOP_ICON_PX, DESKTOP_ICON_PX), Image.Resampling.LANCZOS)
    for out in (
        os.path.join(DESKTOP_ICONS, "bangnidraw.png"),
        os.path.join(DESKTOP_RESOURCES, "bangnidraw.png"),
    ):
        desktop.save(out, optimize=True)
        print(f"-- {os.path.relpath(out, ROOT)} ({DESKTOP_ICON_PX}px)")

    mac = desktop_source.resize((MAC_ICON_PX, MAC_ICON_PX), Image.Resampling.LANCZOS)
    mac_out = os.path.join(DESKTOP_ICONS, "bangnidraw.icns")
    mac.save(mac_out, format="ICNS")
    print(f"-- {os.path.relpath(mac_out, ROOT)} ({MAC_ICON_PX}px source)")
    print("==> icons generated")


if __name__ == "__main__":
    main()
