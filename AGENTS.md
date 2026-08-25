# AGENTS.md — operating manual

The operational source of truth for agents (and humans) working on
帮你Draw. When you learn something durable about how this repo behaves — a
quirk, a footgun, a changed convention — **update this document** in the
same PR.

## Name

**帮你Draw** (Bāngnǐ Draw, "helps you draw"). The launcher label is the full
name — it is short enough. The applicationId and package are
`ch.lkmc.bangnidraw` and never change: changing an applicationId breaks
upgrades for everyone who sideloaded a build, and no user ever sees it.
PLAN.md's *Renaming* section lists every file a future rename would touch.

## What this app is

A simple, fast, layered raster drawing app for Android phones and tablets
with first-class S Pen support and Mixbox pigment mixing. Read
[PLAN.md](PLAN.md) first; it is the constitution (product framing,
architecture, decisions, roadmap). The detailed design is in `docs/plan/`
(index at the end of PLAN.md). Deviations from the plan get recorded here
as they happen.

## Build, test, lint

```sh
./gradlew testDebugUnitTest    # the whole test suite (JVM-only, by design)
./gradlew lintDebug            # hard CI gate — keep it clean
./gradlew assembleDebug        # debug APK
scripts/build.sh               # release APK staged into dist/
scripts/install.sh             # build + install + launch on a device
python3 scripts/generate_icons.py   # regenerate launcher PNGs from media-sources/icon.png
```

- JDK 17. Android SDK path via `local.properties` (`sdk.dir=…`) or
  `ANDROID_HOME`. Agent sessions: `.claude/setup-android.sh` bootstraps the
  SDK idempotently (wired as a SessionStart hook). Note that the SDK's
  `aapt2` is x86_64-only — an arm64 Linux sandbox cannot assemble; CI is
  the source of truth there.
- Versions are pinned ONLY in `gradle/libs.versions.toml`. Never add an
  ad-hoc version to a build file; never restate catalog versions in docs.

## Toolchain quirks — don't "fix" these

- `compileSdkVersion("android-37.0")` (string form) is deliberately paired
  with `android.suppressUnsupportedCompileSdk=37` in `gradle.properties`.
  The two move together or not at all.
- There is NO `kotlin-android` plugin: AGP 9 provides built-in Kotlin
  support. Only android-application, kotlin-compose, kotlin-serialization,
  ksp, and hilt are applied.
- `app/debug.keystore` is checked in ON PURPOSE and signs BOTH build types
  (`.gitignore` whitelists it). Zero-secret CI, reproducible builds,
  sideload-only distribution — see `docs/decisions/0005`. Do not "rotate"
  it, do not add signing secrets without a recorded product decision.
- **minSdk is 29** (ADR 0002). Don't add legacy storage or pre-29 fallbacks;
  the permission-free MediaStore path and front-buffered rendering both
  assume it.
- Adaptive icons live in `mipmap-anydpi-v26` even though minSdk is 29;
  `app/lint.xml` silences the (wrong) ObsoleteSdkInt advice. The artwork is
  the adaptive **background** layer (full-bleed PNGs generated per density);
  the foreground is an empty vector; the monochrome icon is a hand-drawn
  brush silhouette.

## Architecture in one paragraph

Single module `:app`, packages first (PLAN.md §3). MVVM with one immutable
UiState per screen (StateFlow from a ViewModel), Hilt DI, single activity,
Compose + Material 3 for the chrome, a SurfaceView driven by a GLES 3.0
engine for the canvas. The document is a stack of layers made of sparse
256×256 premultiplied RGBA8 tiles, pooled on the GPU in texture arrays,
composited per visible tile; strokes are stamped as dabs into a per-stroke
buffer and previewed through `GLFrontBufferedRenderer`; undo is a persisted
journal of tile deltas in the project folder; autosave is the only save;
each painting mirrors to one MediaStore image. Decision logic lives in
`engine/core` as pure JVM classes.

## Conventions and footguns

- **Tests are JVM-only** (`testDebugUnitTest`); keep decision logic out of
  composables and the GL renderer so it stays testable. No androidTest
  directory exists; adding one means adding the emulator CI job too.
- **"Works in debug, breaks in release"** is almost always a missing R8
  keep rule for a new reflection/serialization entry point — check
  `app/proguard-rules.pro` first.
- The CPU reference implementations in `engine/core` (`Composite`, the
  mixing formula, dab falloff) and the GLSL must stay trivially close; when
  one changes, change both, and let the unit tests pin the semantics.
- `engine/core/ViewTransform` and `FitTransform` are Meltorama's, ported
  verbatim — except the scale limits: `MIN_SCALE = 0.25f`, `MAX_SCALE = 64f`
  (Meltorama: 0.5 / 8), because pixel work on a 4096² canvas needs to zoom
  far past a photo warper's range (docs/plan/07 §7). Keep the math in sync
  with Meltorama if either side finds a bug; keep the constants ours.
- **User-visible wording is a string resource, everywhere.** ViewModels
  and the renderer have no Context and no locale, so failures travel as
  `@StringRes Int` and the exception's own English text goes to Logcat.
  The app ships `values/` and `values-b+zh+Hans/`, listed in
  `res/xml/locales_config.xml` (Android 13+ per-app language) — add a
  locale to that file in the same change that adds its `values-*` folder.
  Lint's `MissingTranslation` is a hard CI gate, so brand strings carry
  `translatable="false"`.
- **Mixbox is CC BY-NC 4.0** (ADR 0003). The attribution lives in the About
  string, the README and `third-party/mixbox/`; keep all three when
  touching any of them. Vendored `mixbox.glsl`/`mixbox_lut.png` must stay
  byte-identical to upstream.
- The app has **no permissions**. Keep it that way; adding any is a
  product decision requiring an ADR.
- App display name lives ONLY in `strings.xml` (`app_name`). Never
  hardcode "帮你Draw" in a composable (rename checklist: PLAN.md "Renaming").
- Colors come from `ui/theme/Color.kt` — no ad-hoc `Color(0x…)` in
  screens. The theme follows the system (light and dark), no dynamic color.
- Scripts follow the family house style: header comment doubles as
  `--help` via the awk one-liner; `==>` / `--` / `!!` log prefixes;
  `set -euo pipefail`.
- Third-party assets (brush grains, sample art, fonts) must be public
  domain / CC0 with provenance recorded in this file when added. Currently
  none besides Mixbox.
- Proposals for post-v1 features live in `docs/proposals/` as numbered
  pre-decision docs (same Status/Date header as ADRs); an accepted proposal
  graduates into `docs/plan/12-roadmap.md`, a declined one stays with its
  status flipped so the reasoning isn't lost.

## CI/CD

Three workflows (details: [CICD.md](CICD.md)): `ci.yml` (tests + lint +
debug APK on every PR/main push), `release.yml` (v* tags → verified,
published APK), `zai-code-review.yml` (GLM reviews every PR; respond
per [CLAUDE.md](CLAUDE.md)). Family contract on every workflow:
least-privilege permissions, explicit concurrency, timeouts, wrapper
validation.

## Releasing

`scripts/release.sh X.Y.Z --push` (shared lkm-release engine) bumps
versionName, auto-increments versionCode by exactly 1, rewrites the README
version marker, commits, tags `vX.Y.Z`, pushes. **Never hand-edit
versionCode. Never create a `v*` tag by hand.** Bump the most-minor version
component + versionCode on every non-trivial change set.

## Review process

PRs are reviewed by GLM automatically. Findings are triaged
apply/decline/refute per [CLAUDE.md](CLAUDE.md); declined findings and
their reasons accumulate in [REVIEW.md](REVIEW.md) so later rounds (and
later agents) don't flip-flop.

The repository defaults to **hybrid** review: the first review is full, then
follow-ups review changes since the last completed review plus a rotating
sample of older PR changes. Keep hybrid for normal review/fix cycles.

Before the next review-triggering push, an implementer may apply exactly one
scope label:

- `zai-review:full` — use for high-risk changes, after a force push, or for a
  deliberate final deep audit.
- `zai-review:hybrid` — restore the normal delta-plus-rotating-audit mode.
- `zai-review:incremental` — use only for a low-risk, latency-sensitive
  follow-up after a completed full review; it omits the rotating old-code audit.

Labels override the workflow setting but do not themselves start a review; the
next synchronize, reopen, or other configured PR event does. Remove an override
to return to the repository's hybrid default. Missing/incomplete state or
non-ancestor history automatically falls back to a full review.
