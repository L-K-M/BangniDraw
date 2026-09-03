# 0005 — The desktop rail's 37.8 MB icon artifact

- **Status:** Proposed
- **Date:** 2026-09-03

## Problem

The desktop chrome (PR #192) draws the same Material glyphs the Android rail
draws, so `:desktop` depends on `compose.materialIconsExtended`. That costs
more than a line in a build file:

- JetBrains **deprecated** the multiplatform artifact after **1.7.3**, so that
  is the version it resolves to against Compose Multiplatform 1.12.0. It will
  not receive fixes.
- Its jar is **37.8 MB**, and `:desktop` configures no minification, so
  jpackage copies the whole thing into every distribution — for eleven glyphs.

Eleven, not sixteen: `material-icons-core`, which `compose.material3` already
brings, carries `Create`, `Close` and `MoreVert` of the set, and five of the
rail's silhouettes are already repo-owned (`ToolGlyphs`, `WaterToolGlyphs`).
The extended-only remainder is `Draw`, `Brush`, `Air`, `Texture`, `Gradient`,
`Architecture`, `HistoryEdu`, `FormatPaint`, `OilBarrel`, `Tune`, `MoreHoriz`,
plus the auto-mirrored `Undo`/`Redo` in the top strip.

## Why this is not a straightforward swap

Google's current advice for its own icons is exactly what the size suggests:
copy the `ImageVector` for the icons you use and drop the artifact. Two things
stand in the way here.

**The asset policy.** AGENTS.md requires third-party assets to be *public
domain / CC0 with provenance recorded in this file*. Material Symbols are
Apache-2.0. Vendoring their path data — even extracted mechanically, even
attributed — admits an Apache-2.0 asset the current rule does not allow. That
is a product decision, not a refactor.

**Drift.** `:app` keeps `material-icons-extended` regardless: the Studio,
settings and canvas screens use extended icons far beyond the rail. So a
desktop-only vendoring would give the two rails two sources for the same
artwork — the exact drift PR #192 removed by putting the repo-owned
silhouettes and the glyph mapping in one shared directory. Vendoring has to
cover *both* rails to be worth doing.

## Options

1. **Keep the artifact** (status quo). Zero drift, policy intact, matches what
   `:app` already does. Costs 37.8 MB per desktop distribution and pins a
   deprecated 1.7.3 artifact against a 1.12.0 runtime.
2. **Vendor the eleven into `ui/glyphs/`**, used by both rails, with an ADR
   admitting Apache-2.0 assets and recording provenance. Drops the artifact
   from `:desktop` entirely; `:app` keeps it for its other screens, so the APK
   is unchanged. Costs an asset-policy exception and ~400 lines of generated
   vector data that must be regenerable.
3. **Enable minification for `:desktop`'s release build**
   (`buildTypes.release.proguard`). Keeps one source of artwork and shrinks the
   jar to what is reachable. Costs a new keep-rule surface for a module that
   has none today, exercised only in the packaging CI jobs — and it does not
   address the deprecation.
4. **Draw eleven new silhouettes** in the repo's own hand, as the marker,
   eraser, spray-can, watercolor and pigment-wash glyphs already are. Policy-
   clean and no vendoring, but it changes what the rail looks like on both
   platforms, and the existing five exist because Material had *nothing
   suitable* — that argument does not hold for a pencil or a brush.

## Recommendation

Option 1 until the desktop build is close to a real release, then option 2 or
3. The size matters when someone downloads the app; it does not matter while
the desktop target is sideload-only and pre-v1, and option 1 is the only one
that costs nothing to reverse. Revisit at DESKTOP.md Phase 4 (packaging,
signing, notarization), which is when distribution size first has an audience.

Whichever way it goes, the deprecation is the part with a clock on it: 1.7.3
against a moving Compose runtime is fine today and will not be forever.
