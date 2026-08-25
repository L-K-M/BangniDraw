# 0003 — Mixbox for pigment mixing, non-commercial, behind `ColorMixer`

- **Status:** accepted
- **Date:** 2026-08-24

> Covers PLAN.md decision 5 and §9. The integration itself (GLSL, LUT
> upload, dab merge, the mixing dish) is `docs/plan/09-color-and-mixing.md`;
> this ADR records the licensing decision and its obligations.

## Context

"Blue + yellow = green" is a headline requirement. RGB interpolation gives
grey-green mud; what painters expect is subtractive, pigment-like mixing,
which is a Kubelka-Munk model over real pigment spectra. Mixbox
(github.com/scrtwpns/mixbox, Secret Weapons, 2022) is the practical
implementation of that: it maps RGB to a 7-value latent (three pigment
concentrations plus residual), mixes linearly in latent space, and maps
back, using a precomputed 64×64×64 lookup table shipped as
`mixbox_lut.png` (512×512 RGBA, 176,599 bytes). It ships a Java jar
(`com.scrtwpns:mixbox:2.0.0` on Maven Central: `Mixbox.lerp`,
`rgbToLatent`, `latentToRgb`, `LATENT_SIZE`) and a 166-line GLSL file
(`mixbox_rgb_to_latent`, `mixbox_latent_to_rgb`, `mixbox_lerp`, LUT as
`uniform sampler2D mixbox_lut`), which is exactly the CPU + GPU pair the
engine needs.

The catch is the license. Verbatim from the project:

> Mixbox is provided under the CC BY-NC 4.0 license for non-commercial use only.

Every source file carries the header "MIXBOX 2.0 (c) 2022 Secret Weapons.
All rights reserved. License: Creative Commons
Attribution-NonCommercial 4.0. Authors: Sarka Sochorova and Ondrej
Jamriska". Commercial licensing is by contacting mixbox@scrtwpns.com.

Everything else in the repo is Unlicense (public domain). The question is
whether one CC BY-NC component is acceptable in an otherwise public-domain
app, and what it obliges us to.

Alternatives considered:

| Option | Why not |
| --- | --- |
| RGB lerp only (`RgbMixer`) | Fails the requirement; kept as the fallback implementation, not the product |
| Implement Kubelka-Munk ourselves | The KM equations are public and short. The *value* of Mixbox is not the equations; it is the fitted pigment basis and the RGB↔latent LUT that makes an arbitrary RGB color decomposable into plausible pigment concentrations and back, with residuals so that round-trips are exact. Re-deriving that is a research project (choose primaries, measure or source reflectance spectra, fit, tabulate, validate against paint), not an afternoon. A worse home-grown LUT would deliver a worse blue + yellow |
| Spectral upsampling (per-channel spectral curves, mix in reflectance) | Same fitting problem, plus 30+ channels per pixel on the GPU where Mixbox needs a LUT tap |
| Commercial license now | The app has no revenue and no plan for any; paying for a hobby app's mixing is not justified today |

## Decision

Adopt **Mixbox 2.0** for pigment-style mixing — the jar on the CPU
(`engine/mixbox/MixboxMixer`, for the color panel's mixing dish, swatch
blending, and the CPU reference tests) and the vendored `mixbox.glsl` +
`mixbox_lut.png` on the GPU (dab merge for mixing-enabled brushes, smudge).
Both sit behind the `ColorMixer` interface in `engine/core`. The
interface (two-color and weighted mixing, `isPigment`) and its two
implementations are defined in `docs/plan/09-color-and-mixing.md` §4:
`MixboxMixer` is the default; `RgbMixer` (a component-wise lerp of the
stored sRGB bytes — the same arithmetic as the GPU `mix()` and the
`Composite` reference) is always present.

The single switch is the Gradle property `bangnidraw.mixbox` (09 §7.4):
it selects the `mixbox` or `nomixbox` source set, adds or drops the Maven
dependency, and makes `MixboxBinding.create()` return the mixer or null,
so the Hilt module (`di/AppModule.kt`) binds `MixboxMixer` or falls back
to `RgbMixer`. The GPU side mirrors it: `Shaders.kt` assembles the
dab-merge and smudge shaders with `#define BANGNI_MIXING 1` and a
`MIXLERP` macro that expands to `mixbox_lerp` or plain `mix()`.

**Licensing position.** 帮你Draw's own code is Unlicense. The distributed
app contains one CC BY-NC 4.0 component, so the *combined app as
distributed is non-commercial*: it is given away on GitHub Releases, is
not sold, carries no ads or in-app purchases, and is not on a store. That
is compatible with the license. Nothing in this repo may introduce
revenue while Mixbox is present.

**Attribution obligations** (CC BY: credit, license name, link, and note
of modifications) appear in all of:

1. `README.md` — a "Licensing" section: the app is public domain except
   Mixbox, quoted license line, link, and that the app is therefore
   non-commercial as distributed.
2. The **About screen** (`ui/home/SettingsSheet`, the Settings/About sheet of
   `docs/plan/08-ui-and-layout.md` §8, reachable from Studio → Settings): "Color mixing by Mixbox © 2022 Secret Weapons (Šárka
   Sochorová, Ondřej Jamriška), CC BY-NC 4.0" with the repository URL
   rendered as text (no INTERNET permission — nothing is fetched, the
   user copies it).
3. The vendored files themselves: `app/src/mixbox/assets/mixbox/mixbox.glsl`
   and `mixbox_lut.png` are kept byte-identical to upstream with the
   header intact; any modification (none planned) goes in a separate
   `bangni_mixbox_glue.glsl` and is noted in the About text.
4. `third-party/mixbox/` (verbatim `LICENSE` + notice `README.md`),
   with provenance also listed in AGENTS.md (PLAN.md §9 requires
   provenance for every non-Unlicense asset).

**Stripping recipe** (what "one-line change" means, tested by a CI-less
manual check documented in AGENTS.md):

1. Set `bangnidraw.mixbox=false` (gradle.properties or `-P`), per 09
   §7.4: the `nomixbox` source set replaces `mixbox`, the Maven dependency
   is dropped, `MixboxBinding.create()` returns null and Hilt binds
   `RgbMixer`; `Shaders.kt` assembles the shaders without `BANGNI_MIXING`,
   so `MIXLERP` is plain `mix()`.
2. To remove the files rather than just disable them, delete
   `app/src/mixbox/` (code and `assets/mixbox/`) and the `mixbox` entry in
   `libs.versions.toml`.
3. Remove the About/README paragraphs and `third-party/mixbox/`.
4. Mixbox-only tests (`MixboxMixerTest`) live in the mixbox source set's
   test directory and vanish from a stripped build
   (`docs/plan/11-testing.md`); everything else is unaffected. The mixing
   dish keeps working, RGB-style.

**A commercial future** (a store listing with a price, ads, or IAP)
requires one of: a commercial license from mixbox@scrtwpns.com recorded
in `third-party/mixbox/`, or performing the stripping recipe first.
Either is its own ADR, because it changes what the app promises.

## Consequences

- The best available mixing model, on day one, for the cost of a jar and
  two asset files; the engine treats mixing as a black box (`latent =
  lut(rgb)`, lerp, `rgb = poly(latent)`), so shader work is glue, not
  color science.
- **The app cannot be sold, ever, in this form.** Contributors must know
  this before proposing monetization; the README says it above the fold.
- Every release, screenshot, and store-like listing must carry the
  attribution; the About screen is part of the v1.0 acceptance in PLAN.md
  §10 step 10 for that reason.
- The LUT must be uploaded **without premultiplication and without sRGB
  decode** (Android's `BitmapFactory` premultiplies by default — load with
  `inPremultiplied = false`, or ship the LUT as raw bytes), with no
  mipmaps; a `MixboxLutTest` samples known corner texels to catch a wrong
  loader. Mixbox works in sRGB 0..1 in and out ("RGB in, RGB out") while
  our tiles are premultiplied RGBA8, so the merge shader un-premultiplies
  before `mixbox_lerp` and re-premultiplies after; alpha is ordinary
  source-over coverage, never mixed (09 §3.1).
- Upstream is quiet (jar last published 2022-09). We vendor the GLSL and
  LUT precisely so that the app does not depend on the repository staying
  up; the jar comes from Maven Central and could also be vendored if it
  ever disappeared.
- The `RgbMixer` path is not dead code: it is the CPU reference for the
  non-mixing case, the test double, and the thing that makes the
  stripping recipe safe.
- **Revisit if:** monetization is ever seriously wanted (license or
  strip); Mixbox's license changes; a public-domain pigment-mixing model
  of comparable quality appears; or someone in the family decides to do
  the research project after all.
