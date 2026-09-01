<div align="center">

<img src="media-sources/icon.png" alt="帮你Draw" width="200">

# 帮你Draw

**Helps you draw.** A simple, fast sketching and painting app for Android
phones and tablets, made for the S Pen.

[![CI](https://github.com/L-K-M/BangniDraw/actions/workflows/ci.yml/badge.svg)](https://github.com/L-K-M/BangniDraw/actions/workflows/ci.yml)

Latest release: v<!-- version -->1.3.0<!-- /version --> · [Download](https://github.com/L-K-M/BangniDraw/releases/latest)

</div>

帮你Draw (Bāngnǐ Draw) is a layered raster drawing app that gets out of the
way: tap **+** and you are drawing, with pencils, pens, brushes that mix
color like real paint, erasers on the other end of the pen, smudge, fill,
layers, two-finger zoom-and-rotate, undo that survives closing the app, and
every painting kept up to date in your gallery. It runs from a phone (quick
sketches) to a large Samsung tablet (real paintings) and reshapes its UI
between them.

> [!IMPORTANT]
> **LLM disclosure:** this app is developed almost entirely by LLM agents,
> including its reviews. See [AGENTS.md](AGENTS.md) for the operational
> conventions and [PLAN.md](PLAN.md) for the design.

> [!NOTE]
> **Status:** v1. Automated gates pass; real-device acceptance remains
> unverified.

## Building

```sh
./gradlew assembleDebug        # or: scripts/build.sh --debug
scripts/build.sh --install     # desktop .app -> /Applications (macOS)
scripts/install.sh             # build + install + launch the APK on a device
```

Requirements: JDK 17, Android SDK (set `sdk.dir` in `local.properties` —
see `local.properties.example`). Both build types are signed with the
checked-in debug keystore so any clone produces installable,
upgrade-compatible APKs (a deliberate sideload-only decision — see
`docs/decisions/0005-zero-secret-signing.md`).

Paintings and undo history stay in app storage. Uninstalling removes them;
gallery copies remain.

## Releasing

`scripts/release.sh X.Y.Z --push` — never hand-edit `versionCode`, never
create a `v*` tag by hand. CI publishes the APK to GitHub Releases.

## Desktop (macOS, Linux)

The same repo builds a desktop app (`DESKTOP.md` is the design study):

```sh
./gradlew :desktop:run              # needs a display
./gradlew :desktop:test
```

Linux uses the system GLES (Mesa or the vendor driver's `libEGL`/
`libGLESv2`); nothing extra to install. macOS has no native GLES — the
context comes from ANGLE's Metal backend. Builds do not vendor ANGLE.
The GitHub DMG is an unsigned developer preview; Gatekeeper may require
explicit approval. Production distribution still needs bundled, signed ANGLE, Developer ID
signing, and notarization (DESKTOP.md Phase 4).
Provide matching `libEGL.dylib` and `libGLESv2.dylib` either in the
packaged app's Compose resources directory or explicitly:

```sh
JAVA_TOOL_OPTIONS=-Dbangnidraw.angle.dir=/absolute/path/to/angle \
  ./gradlew :desktop:run
```

For an installed preview, run its launcher from Terminal with the same
`JAVA_TOOL_OPTIONS`; Finder cannot supply that property.

The packaged target directories are `desktop/packaging/angle/macos-arm64`
and `macos-x64`. Startup logs the GL version and renderer. Missing ANGLE or
ES 3.0 opens an instruction window; it no longer leaves a menu-only process.

The desktop shell is v1-minimal: canvas, brush picker, color, undo/redo,
Save PNG (to `~/Pictures/BangniDraw`), mouse input with synthetic
pressure. Pens with real pressure are Phase 3 (per-OS native input shims,
see DESKTOP.md). The app and native About menu use the canonical 帮你Draw
name and project icon. **Mixbox attribution carries over**: pigment mixing
is Mixbox © Secret Weapons, CC BY-NC 4.0 — non-commercial; the About button
states this when included, and `-Pbangnidraw.mixbox=false` strips it.

## License

[Unlicense](LICENSE) — public domain, for everything written here.

Natural color mixing is [Mixbox](https://github.com/scrtwpns/mixbox)
© 2022 Secret Weapons, licensed **CC BY-NC 4.0 — non-commercial use only**
(`third-party/mixbox/`). Because Mixbox ships inside the app, 帮你Draw as
distributed is non-commercial; the reasoning and the way out are recorded in
`docs/decisions/0003-mixbox-non-commercial.md`. Build without it with
`-Pbangnidraw.mixbox=false`.
