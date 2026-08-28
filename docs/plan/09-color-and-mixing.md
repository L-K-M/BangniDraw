# Color and mixing

**What this covers / relationship to PLAN.md.** This document expands
PLAN.md decision 5 (Mixbox behind `ColorMixer`, license stated loudly),
§3 (`engine/core` `ColorMixer`/`RgbMixer`, `engine/mixbox` `MixboxMixer` +
LUT loader), §5 screen 2 (color panel with HSV wheel, swatches and mixing
dish) and §9 (licensing red lines). It owns: the app's color model, the
exact mixing math used by the dab/merge and smudge shaders (the shaders
themselves belong to `docs/plan/03-canvas-engine.md`; the tool parameters
to `docs/plan/04-tools.md`), the CPU `ColorMixer` family, the LUT asset
and GLSL include mechanism, the Mixbox licensing obligations and the
stripping recipe (ADR 0003), and the palette / mixing-dish design of the
color panel (layout in `docs/plan/08-ui-and-layout.md`).

## 1. Color model

| Aspect | v1 decision | Why |
| --- | --- | --- |
| Storage | sRGB-encoded 8-bit RGBA, **premultiplied**, in tiles and GL textures (`GL_RGBA8`, no `GL_SRGB8_ALPHA8`) | RGBA8 render targets are the only guaranteed baseline on ES 3.0 (half-float is an optional extension); premultiplied makes `over` a single FMA and matches Android `Bitmap` conventions for export |
| Blending | on the stored (non-linear) values | Every consumer painting app users compare against blends in sRGB; linear-light blending makes soft edges look thin and dark to people used to Photoshop/Procreate. Mixbox's default mode is also sRGB in/out, so one convention covers dab merge, compositing and mixing |
| Working color | `Int` ARGB (straight alpha) everywhere on the CPU; `vec4` premultiplied on the GPU | ARGB `Int` is what `com.scrtwpns.Mixbox`, `android.graphics.Color` and Compose's `Color.toArgb()` agree on; no boxing on the input path |
| Picker | HSV | The wheel-plus-square picker is the one painters know; HSV maps to it directly. `HsvColor` conversions live in `engine/core` and are unit-tested against `android.graphics.Color` fixtures |
| Gamut / ICC | sRGB only, no wide gamut, no ICC | §8 |

`engine/core/Colors.kt` holds the small pure-JVM helpers every layer uses:

```kotlin
@JvmInline value class Argb(val value: Int) { val a get() = value ushr 24; val r get() = (value shr 16) and 0xFF; ... }
data class HsvColor(val h: Float /* 0..360 */, val s: Float, val v: Float) { fun toArgb(): Int; companion object { fun fromArgb(argb: Int): HsvColor } }
fun premultiply(argb: Int): Int; fun unpremultiply(pm: Int): Int   // used by Readback + eyedropper + Composite reference
```

Paint colors are always fully opaque `Argb`s; transparency is a property
of the stroke (opacity, flow), never of the color.

## 2. What Mixbox is

Mixbox (Sochorová & Jamriška, Secret Weapons, 2022) is a pigment-mixing
model with an ordinary RGB-in/RGB-out API. Internally each RGB color is
mapped to a **latent** vector: concentrations of a few real pigments
(Kubelka-Munk absorption/scattering model, which is why blue and yellow
pigments give green — the way subtractive mixing does, and RGB averaging
never does), plus a **residual** that captures whatever part of the input
color the pigment set cannot reproduce (near-black, near-white, very
saturated screen colors). The latent space is **linear**: mixing N colors
is the weighted sum of their latents, followed by one latent→RGB
evaluation. Two consequences drive the whole design below:

1. `mixbox_lerp(a, b, t)` = `latent_to_rgb(mix(rgb_to_latent(a), rgb_to_latent(b), t))`.
   Every place we mix is therefore "compute a share `t`, call lerp" — the
   share is *our* math, the color science is theirs.
2. An N-way mix is `latent_to_rgb(Σ wᵢ · rgb_to_latent(cᵢ))` — the
   post-v1 N-well dish and any future "wet" brush cost one extra latent
   per input, nothing structural.

CPU: `com.scrtwpns:mixbox` (Maven Central; version in
`gradle/libs.versions.toml`) exposes `Mixbox.lerp(Int, Int, Float): Int`,
`Mixbox.rgbToLatent(Int): FloatArray`, `Mixbox.latentToRgb(FloatArray): Int`
and `Mixbox.LATENT_SIZE`. GPU: the vendored `mixbox.glsl` (166 lines, latent
is a `mat3` via `#define mixbox_latent mat3`) with `mixbox_rgb_to_latent`,
`mixbox_latent_to_rgb`, `mixbox_lerp(vec3, vec3, float)`, and a 512×512
RGBA lookup texture `mixbox_lut.png` (176,599 bytes). We treat the latent
layout as opaque: `LATENT_SIZE` and `mat3` are the only facts the code
relies on.

Mixbox is calibrated for **sRGB-encoded 0..1 inputs** unless
`MIXBOX_COLORSPACE_LINEAR` is defined. Our textures are sRGB-encoded 8-bit
(§1), so the define stays **undefined** on the GPU and the Java lib is
fed plain ARGB on the CPU. Defining it would make Mixbox interpret our
already-encoded values as linear light and every mix would come out wrong
by a gamma curve.

## 3. The three places mixing happens

| Where | Runs on | Inputs | Output | Cost bound |
| --- | --- | --- | --- | --- |
| Stroke merge (`merge_stroke_mix` program — `docs/plan/03-canvas-engine.md` §7.4's `merge.frag` in its mixing variant; also the live preview of the dirty rect, 03 §7.5) | GPU, per stroke, dirty tiles only | active-layer tile + stroke buffer + paint color | active-layer tile (ping-pong) | dirty rect of the stroke |
| Smudge pickup + deposit (`smudge_mix` program) | GPU, per dab | layer under the dab + pickup texture | layer (ping-pong) + pickup | dab rect |
| Mixing dish, palette, preview gradients | CPU, `MixboxMixer` | two or N ARGB colors + weights | ARGB | a handful of calls per UI frame |

**Never in the compositor.** `CompositePass` (layer over layer with blend
modes, `docs/plan/03-canvas-engine.md` §3) is a full-viewport
pass on every pan/zoom/rotate; adding four LUT taps per layer per pixel
there would cost more than the whole rest of the frame on a 2560×1600
tablet and would change what "layer opacity" means. Pigment mixing is a
property of *paint meeting paint on one layer*; between layers we use
ordinary blend modes, which is what every painting app does.

### 3.1 Mixing merge: the exact math

Setting. A destination pixel of the active layer, premultiplied:
`D = (rgb_d · a_d, a_d)`. A paint color `c_p` (straight sRGB, alpha 1)
arriving with **coverage** `k ∈ [0, 1]` — for the merge that is the
stroke buffer's accumulated alpha at that pixel (already capped at the
brush's opacity, `docs/plan/03-canvas-engine.md` §Stroke buffer), times
the layer-level factors the merge applies (nothing in v1).

Ordinary source-over of a straight color with coverage `k` over `D` is:

```
a   = k + a_d · (1 − k)                                  (1)
rgb = (k · c_p + a_d · (1 − k) · rgb_d) / a              (2)   straight result
```

(2) is a convex combination of the two straight colors with the paint's
share

```
t = k / (k + a_d · (1 − k)) = k / a                      (3)
```

so source-over is *exactly* `rgb = lerp(rgb_d, c_p, t)`, `a` from (1),
and `t` has a physical reading: of the pigment that ends up in the pixel,
`k` came from the paint and `a_d·(1−k)` was already there (the part of the
old paint not covered by the new). Pigment mixing replaces the one
function that averages colors with the one that mixes pigments:

```
rgb = mixbox_lerp(rgb_d, c_p, t)                         (4)
```

and nothing else changes. Alpha is coverage, not pigment, so it stays
linear — this is why we do not use `mixbox_lerp(vec4, vec4, t)` (which
also lerps alpha, but with the wrong weight for premultiplied data).
With `RgbMixer` selected, (4) degenerates to (2): the mixing toggle
changes only the color function, and the non-mixing program is bit-exact
source-over, which is what the CPU `Composite` reference pins.

Degenerate cases, all handled by (1)–(4) without branches except the last:

| Case | `t` | `a` | Result |
| --- | --- | --- | --- |
| `a_d = 0` (empty pixel) | 1 | `k` | pure paint at coverage `k` — you cannot mix with nothing |
| `k = 0` (dab does not touch pixel) | 0 | `a_d` | unchanged, bit-exact |
| `k = 1` (fully opaque paint) | 1 | 1 | pure paint; opaque buffered strokes do not "see through" to mix — matches physical opaque paint at full opacity. Their mixing comes from flow/opacity < 1; Watercolor uses the separate direct-RMW colour pass. |
| `a_d = 0` and `k = 0` | 0/0 | 0 | guard: `a < ε` → write `vec4(0)` |

**Dilution.** The preset's `dilution ∈ [0, 1]` (`docs/plan/04-tools.md`
§2, `BrushPreset.dilution`) caps the paint's share in (4) without touching (1): `t = (k / a) ·
(1 − dilution)` when `a_d > 0`, and `t = k / a` unchanged when `a_d = 0`
(there is nothing to dilute into). `dilution = 0` reproduces (3) exactly.
Watercolor's direct-RMW colour pass applies the same destination-present
reduction with its built-in dilution of 0.40.

GLSL, the body of the mix branch of the merge shader (the smudge deposit
in §3.2 shares it except for the alpha line):

```glsl
// D: premultiplied destination; c: straight paint rgb; k: coverage 0..1
vec4 bangni_mix_over(vec4 D, vec3 c, float k) {
    float a = k + D.a * (1.0 - k);
    if (a < 1.0 / 512.0) return vec4(0.0);          // below half an 8-bit step: nothing to keep
    vec3 rgb_d = D.rgb / max(D.a, 1.0 / 512.0);     // unpremultiply; weight of the noisy case is small (see §6)
    float t = k / a;
    if (D.a > 0.0) t *= 1.0 - u_dilution;            // preset dilution, 0 for non-mixing programs
#ifdef BANGNI_MIXING
    vec3 rgb = mixbox_lerp(rgb_d, c, t);
#else
    vec3 rgb = mix(rgb_d, c, t);
#endif
    return vec4(rgb * a, a);                         // re-premultiply
}
```

`BANGNI_MIXING` is a compile-time variant, not a uniform: the non-mixing
program must not pay for a LUT sampler it never uses, and the mixing one
must not branch per fragment. `Shaders` compiles `merge_stroke` and
`merge_stroke_mix` (and `smudge`/`smudge_mix`) from the same source text.
`docs/plan/03-canvas-engine.md` §7.4's `mergeStroke()` is this function's
home in the merge shader (its MIX branch, with the stroke-mode / alpha-lock
branches around it); implementations short-circuit the one-sided cases
(`a_d = 0`, `k = 0`) so they are bit-exact with source-over, which the
derivation above reaches only up to LUT rounding.

Where it runs. The stroke buffer accumulates coverage for the **whole
stroke** and the merge happens once, on pen-up, per dirty tile: read the
layer's slice, write the merged result into a scratch slice, swap the
index (ping-pong, because a fragment shader cannot read the texture it
writes). The live preview while the pen is down applies the same function
to the dirty rect on the way into the front buffer (below ⊕
`bangni_mix_over(active, c_p, stroke)` ⊕ above), so what the user sees
mid-stroke is what the merge will produce. Two properties follow:

- One stroke = **one** latent round trip per pixel, however many dabs
  overlapped. Quantization error does not accumulate within a stroke.
- Self-crossing strokes mix once with the layer, never with themselves —
  correct for a single-color stroke, which is all v1 has. Per-dab color
  dynamics (hue jitter, "dirty brush") would need the stroke buffer to
  carry color and mix per dab; that is a post-v1 proposal.

Erasers take the ERASE branch of the merge (`L' = L·(1 − k)`,
`docs/plan/03-canvas-engine.md` §7.4, `docs/plan/04-tools.md` §3.7) and
never mix. Alpha-locked layers
(`docs/plan/05-layers.md` §1) keep `a = a_d` and use `t = k` (the
`lerp(cd, cs, s.a)` alpha-lock formula of 05-layers.md) in (4); (3) does
not apply because no new coverage is added.

### 3.2 Smudge pickup mixing

Smudge carries a small **pickup** texture `P` (premultiplied RGBA, the
dab footprint; owned by `SmudgePass`, sized in `docs/plan/04-tools.md`
§Smudge). Per dab, with strength `s`, dab mask `m(p)` and pickup rate
`ρ` (both from the preset):

```
deposit:  L'  = bangni_smudge_deposit(L, P, s · m)       // P has its own alpha; alpha is *lerped*, not source-over
pickup:   P'  = lerp_pm(P, L, ρ · m)                      // samples L, the layer *before* the deposit
```

The pickup samples the pre-deposit slice `L` (the ping-pong source, which
is already bound), not `L'`, so the finger does not absorb its own deposit
each dab — otherwise the carried color never fades into what it passes
over and a long smudge becomes a clone-smear (`docs/plan/04-tools.md`
§Smudge step 2 says the same). It is also why the absorb pass can run in
the same draw as the deposit without a third buffer.

The deposit differs from the stroke merge in exactly one line, the alpha.
A smudge must be able to *lower* coverage (dragging paint outward into
empty canvas erodes its edge; dragging from empty canvas into paint pulls
transparency in), so alpha is a premultiplied lerp, while the color share
is still a pigment lerp:

```glsl
vec4 bangni_smudge_deposit(vec4 D, vec4 S, float w) {    // S premultiplied, w = strength · mask 0..1
    float a = mix(D.a, S.a, w);
    if (a < 1.0 / 512.0) return vec4(0.0);
    vec3 rgb_d = D.rgb / max(D.a, 1.0 / 512.0);
    vec3 rgb_s = S.rgb / max(S.a, 1.0 / 512.0);
    float t = w * S.a / a;                                // share of the pixel's pigment that came from the finger
    vec3 rgb = MIXLERP(rgb_d, rgb_s, t);
    return vec4(rgb * a, a);
}
```

This matches `dst = mix(dst, pickup, strength·curve(p)·falloff(d))` in
`docs/plan/04-tools.md` §Smudge for the alpha and premultiplied-RGB case
and only swaps the color function. `bangni_mix_over_pm` — the two-sided
source-over form of §3.1 where the source is premultiplied instead of a
straight color with alpha 1 — is used by the stroke merge only and can
never lower alpha:

```glsl
vec4 bangni_mix_over_pm(vec4 D, vec4 S, float w) {       // S premultiplied, w = weight 0..1
    float ks = w * S.a;                                  // effective coverage of the source
    float a  = ks + D.a * (1.0 - ks);
    if (a < 1.0 / 512.0) return vec4(0.0);
    vec3 rgb_d = D.rgb / max(D.a, 1.0 / 512.0);
    vec3 rgb_s = S.rgb / max(S.a, 1.0 / 512.0);
    float t = ks / a;
    if (D.a > 0.0) t *= 1.0 - u_dilution;               // as in §3.1; u_dilution = 0 for smudge and non-mixing
    vec3 rgb = MIXLERP(rgb_d, rgb_s, t);                 // mixbox_lerp or mix by variant
    return vec4(rgb * a, a);
}
```

Substituting `S = (c_p, 1)` gives §3.1 exactly, so `bangni_mix_over` and
`bangni_mix_over_pm` are one function in the source; §3.1 is written out
separately above only so the derivation reads cleanly. `bangni_smudge_deposit`
shares everything but the alpha line. The pickup update also uses `MIXLERP` on the
straight colors (the finger's load mixes with what it picks up — a
pigment smudge across a blue/yellow boundary goes through green, the
demo every reviewer will try) and linear alpha. Smudge does one latent
round trip **per dab** on the dab rect; a long smudge stroke therefore
does accumulate quantization (§6), which is invisible at 8 bits over
realistic stroke lengths but is the reason the pickup texture should be
half-float where `EXT_color_buffer_half_float` exists — a device-time
choice `SmudgePass` makes, not a format the document depends on.

Blur never mixes (it averages the neighborhood of the same paint;
pigment-mixing an average of itself is a no-op in intent and an
expensive one in practice).

### 3.3 CPU: mixing dish, palette, previews

Everything the UI mixes goes through the injected `ColorMixer` (§4). The
dish renders a 9-step gradient between its two wells with nine
`mix(a, b, i/8)` calls; a slider drag updates one call. That is
microseconds, so the CPU path has no caching, no coroutines, no state
beyond the wells and `t`.

## 4. `ColorMixer` and its implementations

```kotlin
// engine/core — pure JVM
interface ColorMixer {
    /** Mix straight ARGB colors; alpha is ignored and the result is opaque. t=0 → a, t=1 → b. */
    fun mix(a: Int, b: Int, t: Float): Int
    val isPigment: Boolean            // true → the UI shows the "pigment" badge on the dish
}

/** Mixers with a linear latent space; N-way mixes are weighted latent sums. */
interface LatentColorMixer : ColorMixer {
    val latentSize: Int
    fun toLatent(argb: Int, out: FloatArray)         // out.size == latentSize, no allocation
    fun fromLatent(latent: FloatArray): Int
    fun mixWeighted(colors: IntArray, weights: FloatArray): Int   // Σ wᵢ = 1 normalised inside
}

object RgbMixer : LatentColorMixer {                  // the fallback — always present
    override val isPigment = false
    override val latentSize = 3
    override fun mix(a: Int, b: Int, t: Float): Int   // per-channel lerp of the stored sRGB bytes, rounded
    ...                                               // latent = (r, g, b) / 255 — trivially linear
}
```

```kotlin
// engine/mixbox — only compiled when Mixbox is present (§7.4)
class MixboxMixer : LatentColorMixer {
    override val isPigment = true
    override val latentSize = com.scrtwpns.Mixbox.LATENT_SIZE
    override fun mix(a: Int, b: Int, t: Float) = com.scrtwpns.Mixbox.lerp(a or ALPHA, b or ALPHA, t.coerceIn(0f, 1f)) or ALPHA
    override fun toLatent(argb: Int, out: FloatArray) { com.scrtwpns.Mixbox.rgbToLatent(argb).copyInto(out) }
    override fun fromLatent(latent: FloatArray) = com.scrtwpns.Mixbox.latentToRgb(latent) or ALPHA
    override fun mixWeighted(colors: IntArray, weights: FloatArray): Int { /* Σ wᵢ·latentᵢ → fromLatent */ }
}
```

`rgbToLatent` allocates a `FloatArray` per call in the jar's API; that is
fine on the UI path and forbidden on the input path — which is why no
input-path code calls the CPU mixer at all (GPU mixes strokes). `ALPHA =
0xFF shl 24` because the lib's ARGB alpha handling is not something we
depend on.

`RgbMixer` is plain component-wise linear interpolation of the stored
sRGB bytes — deliberately *not* linear-light — because it must equal what
the non-mixing GPU program (`mix()` on sRGB-encoded texels) produces; the
`Composite` CPU reference and `RgbMixer` are the same arithmetic. Its
test pins `mix(blue, yellow, 0.5) == 0xFF808080` (the famous gray), the
counter-example the pigment test contrasts with.

**Binding.** Hilt (`di/AppModule.kt`, `docs/plan/02-architecture.md` §6)
provides one `ColorMixer` singleton: `MixboxBinding.create() ?: RgbMixer`.
`MixboxBinding` is a tiny object with two source-set implementations
(§7.4): the Mixbox one returns `MixboxMixer()`, the stripped one returns
`null`. On top of that build-time presence sits the **runtime switch**
PLAN.md decision 5 and roadmap step 7 promise: `Prefs.mixer` ∈ `PIGMENT`
(default when Mixbox is present) / `RGB`; with `RGB` the active mixer is
`RgbMixer` everywhere (dish, dab merge, smudge) even in a Mixbox build. The
UI reads the active mixer's `isPigment` to decide whether to show the
pigment controls at all.

**Per-brush toggle.**

```kotlin
// BrushPreset (docs/plan/04-tools.md §2 is the canonical schema)
val mixing: Boolean = false              // pigment merge for this preset; erase-mode presets ignore it
val dilution: Float = 0f                 // 0..1, only with mixing: how much the paint's share yields to what is under it
// resolution, in BrushTool.effectiveMixing():
activeMixer.isPigment && !preset.eraseMode && preset.mixing
```

The result selects `merge_stroke_mix` vs `merge_stroke` for the stroke
(fixed at pen-down; a setting change mid-stroke applies to the next one).
Built-in Watercolor and Smudge use pigment mixing; pencil, ink, marker,
airbrush, and erasers do not. Watercolor fixes mixing on for its direct-RMW
invariants. A user who wants pencil strokes to blend like chalk can still
enable it on a non-watercolor preset in the brush settings sheet. The mixer
switch is in Settings as "Colour mixing: pigment (Mixbox) / RGB" with the
license line beneath it. Switching is never destructive: pixels already
mixed stay as they are.

## 5. LUT asset and GLSL include

### 5.1 The LUT

`app/src/mixbox/assets/mixbox/mixbox_lut.png` — vendored verbatim
(512×512 RGBA PNG, 176,599 bytes; the file's sha256 is recorded in
AGENTS.md's provenance list and checked by a unit test that reads the
asset from the source tree, so a "helpful" re-encode by an image tool
fails CI). It encodes a 64×64×64 RGB→latent table as 8×8 slabs of 64×64;
the shader does the trilinear lookup as two bilinear taps on adjacent
slabs, so the texture **must** be uploaded so that bilinear filtering
reads back the stored bytes untouched:

| Step | Setting | Why |
| --- | --- | --- |
| Decode | `BitmapFactory.Options`: `inPremultiplied = false`, `inScaled = false`, `inPreferredConfig = ARGB_8888` | Android premultiplies by default; the LUT's alpha channel is data, not coverage — premultiplying would corrupt the table wherever alpha < 255. `inScaled` off because the asset must stay 512×512 |
| Upload | `bitmap.copyPixelsToBuffer(buf)` then `glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 512, 512, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf)` | Raw copy under our control rather than `GLUtils.texImage2D` (whose behavior with non-premultiplied bitmaps is a "to verify"); byte order of `ARGB_8888` in memory is R,G,B,A — confirmed by the flipped-LUT probe working on device and by the smoke test in §5.3 |
| Format | `GL_RGBA8`, never `GL_SRGB8_ALPHA8` | The table stores latent coefficients, not colors; sRGB decode on sampling would bend them |
| Filtering | `GL_LINEAR` min/mag, `GL_CLAMP_TO_EDGE`, **no mipmaps** (`GL_TEXTURE_MAX_LEVEL = 0`) | The shader interpolates inside a slab bilinearly and expects `textureLod(…, 0.0)`; mips would blur slab boundaries into each other |
| Orientation | as decoded, top row first — **do not flip** | `mixbox.glsl` probes texel (0,0) and detects a vertically flipped LUT itself; flipping to "help" it would be flipping twice on some loaders and not others. We rely on the probe and verify once on device |
| Lifetime | one texture per EGL context, created in `CanvasRenderer.onSurfaceCreated`, bound to unit 7 (`MIXBOX_LUT_UNIT`) for every mixing program | Loaded once (≈1 MiB of native texture memory), never re-uploaded |

Loader: `engine/mixbox/MixboxLut.kt`, `fun upload(assets: AssetManager): Int
/* texture name */`. It is the only Android-dependent file in
`engine/mixbox`; `MixboxMixer` is plain JVM so the jar is exercised by
`testDebugUnitTest` (the jar has no Android dependency — to verify at
PR 7; if it turned out to need one, the test would move to an
instrumentation job, which PLAN §7 wants to avoid, so this is checked
early).

### 5.2 The GLSL include

There is no `#include` in GLSL ES. `engine/gl/Shaders.kt` assembles every
program's fragment source by string concatenation, in this fixed order:

```kotlin
object Shaders {
    fun fragment(body: String, mixing: Boolean): String = buildString {
        append("#version 300 es\n")                      // must be the first line
        append("precision highp float;\nprecision highp sampler2D;\n")
        if (mixing) {
            append("#define BANGNI_MIXING 1\n")
            append("#define MIXLERP mixbox_lerp\n")
            append("uniform sampler2D mixbox_lut;\n")   // mixbox.glsl declares no uniform; the includer must
            append(MixboxShaderSource.text)             // mixbox.glsl verbatim
        } else {
            append("#define MIXLERP mix\n")
        }
        append(COMMON)                                   // bangni_mix_over_pm, unpremultiply helpers
        append(body)
    }
}
```

`MixboxShaderSource` lives in the Mixbox source set and is the file
contents read from `assets/mixbox/mixbox.glsl` once (assets, not a Kotlin
string literal, so the vendored file stays byte-identical to upstream and
its license header stays in it). `MIXBOX_COLORSPACE_LINEAR` is never
defined (§2). The Kotlin side binds `mixbox_lut` by name after link, and
`GlShaderContractTest` (Meltorama pattern, `docs/plan/11-testing.md`)
parses the assembled sources and asserts: exactly one `uniform sampler2D
mixbox_lut` declaration in mixing variants, none in plain ones; `#version`
on line 1; no `MIXBOX_COLORSPACE_LINEAR` anywhere; the uniform list and
order the Kotlin binder expects. `mixbox.glsl` declares no uniform,
`precision` or `#version` line of its own (verified against upstream: its
only preprocessor lines are the include guard, the `MIXBOX_LUT(UV)` macro,
`#define mixbox_latent mat3` and the `MIXBOX_COLORSPACE_LINEAR` branches);
the includer declares `uniform sampler2D mixbox_lut` exactly once, before
the include, and the ES 3.0 `#version` makes the `MIXBOX_LUT` macro resolve
to `textureLod(mixbox_lut, UV, 0.0)`.

### 5.3 Device smoke check

Not a CI job (PLAN decision 7) but a step in the PR 7 acceptance
checklist (`docs/plan/12-roadmap.md`): a debug-only menu action paints a
blue square, strokes yellow over it at 50% opacity with mixing on, reads
the center pixel back and logs it; the acceptance line is "hue in
70°–170°". This is the GPU twin of the unit test in §9 and the one
place where a wrong byte order, a premultiplied LUT or an sRGB-decoded
texture would show up.

## 6. Cost and precision

**Cost.** One `mixbox_rgb_to_latent` is two bilinear LUT taps (the two
slabs of the trilinear lookup) plus a little index arithmetic;
`mixbox_latent_to_rgb` is the polynomial (`mixbox_eval_polynomial`, a few
dozen multiply-adds) plus adding the residual. A `mixbox_lerp` therefore
costs 4 taps + 1 polynomial per fragment. Budget (`docs/plan/10-performance.md`
carries the frame budget this feeds into):

| Pass | Fragments per event | Mix cost | Verdict |
| --- | --- | --- | --- |
| Live preview of a dab batch, 64 px brush at 2× zoom | ~10⁴ | 4 taps + poly each | negligible against the front-buffer write itself |
| Stroke merge, 200 dirty tiles (a long, fat stroke) | 200 × 65,536 ≈ 1.3·10⁷ | same | ≈ 3.2 fullscreen-equivalents of a 2560×1600 display (4.1·10⁶); against the `docs/plan/10-performance.md` merge budget of ≤ 2 ms per 64 tiles that is ~6 ms, once per stroke on pen-up on the multi-buffered path, not per frame — acceptable, and it is why merge is per-stroke rather than per-dab |
| Smudge dab, 128 px | ~1.6·10⁴ per dab, ×2 (deposit + pickup) | same | fine at 120 Hz input; the smudge cap on dab spacing in `docs/plan/04-tools.md` keeps it there |
| Compositor | 4·10⁶ per frame × layers | — | **never mixes** (§3) |

The LUT is 1 MiB and texture-cache friendly (nearby colors hit nearby
texels); the two taps of one lookup are in different slabs and therefore
different cache lines, which is the real cost, not the ALU.

**Precision.**

- Storage is 8-bit sRGB; each latent round trip re-quantizes. Merge does
  one per stroke (§3.1), so a painting built from strokes has exactly the
  error of its last stroke on any pixel — no drift. Smudge is the only
  per-dab round trip and its pickup texture prefers half-float where
  available (§3.2).
- `unpremultiply` of a low-alpha destination amplifies 8-bit noise (an
  `a_d` of 2/255 leaves 7 distinct values per channel). In (3)–(4) that
  color enters with weight `1 − t = a_d(1−k)/a`, which is small exactly
  when `a_d` is small: the noisy color is the one that barely counts. No
  special-casing is needed beyond the `ε` guard.
- Near black and near white are where pigment models are weakest;
  Mixbox's residual term carries the part of such colors its pigments
  cannot express, so black and white mix as expected (tints and shades)
  rather than collapsing to a dark brown. What *is* expected and must not
  be filed as a bug: pigment mixtures are darker and more saturated than
  RGB averages (blue + yellow → a green that is darker than either), and
  pure white mixed at 50% with a saturated color gives a lighter tint,
  not exactly the RGB midpoint. The About screen's "how mixing works"
  paragraph says this in one sentence.
- Mixbox is calibrated for sRGB in and out; feeding it linear values, P3
  values, or premultiplied values gives plausible-looking but wrong
  results. The contract test (no `MIXBOX_COLORSPACE_LINEAR`), the LUT
  loader (`inPremultiplied = false`) and §8 (sRGB only) are the three
  guards.

## 7. Licensing

### 7.1 The obligation

Mixbox is licensed **Creative Commons Attribution-NonCommercial 4.0
(CC BY-NC 4.0)**; a commercial license is available from the authors
(mixbox@scrtwpns.com). Every vendored source file carries the header:

> MIXBOX 2.0 (c) 2022 Secret Weapons. All rights reserved. License:
> Creative Commons Attribution-NonCommercial 4.0. Authors: Sarka Sochorova
> and Ondrej Jamriska

CC BY-NC 4.0 requires, when we distribute an app containing Mixbox:
**attribution** (creator names, the copyright notice, the license name
with a link to it, a statement of whether we modified the material — we
do not: the GLSL and LUT are vendored verbatim and only concatenated at
runtime; the jar is consumed as published), **no commercial use** of the
combined work, and **no additional restrictions** on Mixbox itself (our
Unlicense covers our code; it does not and cannot cover theirs).

### 7.2 Consequence for the app (ADR 0003)

帮你Draw is a public-domain hobby app distributed free via GitHub
Releases with no ads, purchases or accounts, which is squarely
non-commercial. The consequences that ADR 0003 records and this document
makes concrete:

- The **combined app cannot be sold, put behind a paywall, or carry ads**
  while it contains Mixbox. Anyone who wants to do that strips Mixbox
  (§7.4) or licenses it commercially.
- Everything **we** write stays Unlicense; Mixbox is the only component
  under a different license (PLAN §9), and it is confined to one Gradle
  source set and one Maven dependency so the boundary is auditable.
- The notice is **loud** — the four places in §7.3 — because a hobbyist
  forking a public-domain repo would otherwise assume the whole thing is
  free for any use.

### 7.3 Where the notice appears

| Place | Content |
| --- | --- |
| `README.md` § Licensing | one paragraph: our code is Unlicense; "Natural color mixing uses Mixbox 2.0 © 2022 Secret Weapons, CC BY-NC 4.0 — this makes the app as distributed non-commercial; see `third-party/mixbox/LICENSE` and ADR 0003. Build without it: `bangnidraw.mixbox=false`." |
| About screen → Licenses → Mixbox (shown only when `BuildConfig.MIXBOX`) | the header text verbatim, the license name linked to `https://creativecommons.org/licenses/by-nc/4.0/`, the line "unmodified; used for pigment-style color mixing", and the non-commercial statement. No INTERNET permission — the link opens via `ACTION_VIEW`, which needs none |
| `third-party/mixbox/` (`LICENSE` + `README.md`, both committed) | `LICENSE`: the upstream license text verbatim; `README.md`: the notice — the header text, the CC BY-NC 4.0 name and URL, the upstream repository URL, what is used (the Maven artifact; `mixbox.glsl` + `mixbox_lut.png` once step 7 vendors them, with their sha256), "no modifications", and the non-commercial statement |
| `docs/decisions/0003-mixbox-non-commercial.md` | the decision and its alternatives (RGB-only; a home-grown KM model; a commercial license) |

Settings also shows the license line under the mixer switch
(§4), which is where a user who never opens About meets it.

### 7.4 Stripping recipe (one line)

`gradle.properties`:

```
bangnidraw.mixbox=false        # default true; or -Pbangnidraw.mixbox=false on the command line
```

`app/build.gradle.kts` reads the property once and does four things with
it: adds `implementation(libs.mixbox)` only when true; adds
`src/mixbox/java` + `src/mixbox/assets` (else `src/nomixbox/java`) to the
`main` source set; emits `buildConfigField("boolean", "MIXBOX", …)`; and
sets `resValue` `mixbox_enabled` for the About screen. The two source
sets each define `object MixboxBinding { fun create(): ColorMixer? }`
and, for the shaders, `object MixboxShaderSource { val text: String }`
(the stripped one returns `""` and is never appended because
`effectiveMixing()` is false when the mixer is not pigment). Nothing else
in the codebase mentions Mixbox by class name; `grep -ri mixbox app/src/main`
returning only string resources and comments is a lint-style assertion
in `docs/plan/11-testing.md`. A stripped build has no CC BY-NC bytes in
it, shows no Mixbox notice, mixes with `RgbMixer`, and passes the same
test suite minus the `MixboxMixerTest` class (which lives in the Mixbox
source set's test directory).

## 8. Color management stance

**v1: sRGB only.** Documents are untagged sRGB; the surface is the
default color space; export PNGs are written without an ICC profile
(Android's encoder tags them sRGB implicitly). Why:

- Mixbox is calibrated for sRGB; a P3 pipeline would need every mix input
  converted to sRGB and back, doubling the conversion cost in the one
  shader we care about and losing the wide-gamut values in the round
  trip anyway.
- The consumers of our output — the gallery, chat apps, browsers — treat
  untagged 8-bit as sRGB. Wide gamut without a tag is *worse* than sRGB.
- 8-bit per channel in P3 has visibly coarser steps in the sRGB-sized
  region where almost all paint colors live; wide gamut really wants
  10+ bits, which our RGBA8 baseline (§1) does not have.
- No user of a sketching app has asked for it; painters ask for mixing,
  latency and undo.

**What would change** if a Display P3 canvas were ever wanted (a
post-v1 proposal, not on the roadmap): a per-document `colorSpace` field
in `project.json`; `SurfaceControlCompat.Transaction.setDataSpace` (from
graphics-core) for the canvas surface; the picker and swatches working in
the document space; export tagging via `Bitmap.setColorSpace`/PNG iCCP;
a 16-bit or half-float tile format; and either a linear/P3 conversion
around every Mixbox call or accepting that mixing is computed "as if
sRGB". The last point is why the answer is likely to stay "no" unless
Mixbox itself grows a P3 mode.

## 9. Palette and color panel

The panel's layout and placement are in `docs/plan/08-ui-and-layout.md`
§Color panel; this section defines what it holds and how the pieces
connect.

### 9.1 Contents

| Region | Behavior |
| --- | --- |
| Current / previous chips | large current-color chip (tap: nothing; long-press: add to palette); small previous-color chip (tap: swap) |
| HSV wheel + SV square | hue ring, saturation/value square; commits on release, live-updates the current chip while dragging; haptic tick at hue 0/60/120/… |
| Swatch rows | the active palette's swatches, 8 per row on compact, fills the width on expanded; tap: select; long-press: replace with current / delete / move (drag) |
| Palette switcher | chips: **Painter's**, **Basic**, **Recent**, user palettes; "+" creates a user palette from the current swatches |
| Mixing dish | §9.3 |

### 9.2 Palettes and persistence

```kotlin
@Serializable data class Palette(val id: String, val name: String, val swatches: List<Int> /* ARGB */, val builtIn: Boolean = false)
```

- **Painter's** (default on first run): the 13 pigments Mixbox's
  documentation lists as its basis, in their documented sRGB
  approximations, plus white and black. The 13 pigments and their sRGB
  values are the ones in the Mixbox README (verified 2026-08-24); white
  and black are our own choice:

  | Swatch | sRGB |
  | --- | --- |
  | Cadmium Yellow | 254, 236, 0 |
  | Hansa Yellow | 252, 211, 0 |
  | Cadmium Orange | 255, 105, 0 |
  | Cadmium Red | 255, 39, 2 |
  | Quinacridone Magenta | 128, 2, 46 |
  | Cobalt Violet | 78, 0, 66 |
  | Ultramarine Blue | 25, 0, 89 |
  | Cobalt Blue | 0, 33, 133 |
  | Phthalo Blue | 13, 27, 68 |
  | Phthalo Green | 0, 60, 50 |
  | Permanent Green | 7, 109, 22 |
  | Sap Green | 107, 148, 4 |
  | Burnt Sienna | 123, 72, 0 |
  | Titanium White / Ivory Black | 255, 255, 255 / 20, 20, 20 |

  Why these: they are the colors Mixbox mixes *best* (they are its
  pigments), so a beginner who only ever taps swatches gets the
  showpiece behavior — ultramarine + cadmium yellow → a convincing green.
- **Basic**: 16 screen-primary colors + 6 grays for people who want a
  pure red. Mixing them still works; the result is what those screen
  colors' nearest pigments give.
- **Recent**: the last 16 colors *used to paint* (a stroke was committed
  with them), most recent first, deduplicated; stored in Prefs
  (`recent_colors`, an `IntArray` encoded as a comma-separated string).
  Selecting from a swatch does not count; painting does — otherwise the
  row fills with the colors you tried and rejected.
- **User palettes**: `filesDir/palettes/<id>.json` via
  kotlinx-serialization, tmp+rename like everything else
  (`docs/plan/06-document-and-persistence.md` §4); the active
  palette id and the mixer choice are Prefs keys. Palettes are
  global, not per painting — painters keep a palette across works.
  Export/import of palettes is post-v1 (a share-sheet JSON).

`PaletteStore` (in `data/`) is the only reader/writer;
`CanvasViewModel` (the single writer, `docs/plan/02-architecture.md` §4.2)
exposes the slice `ColorUiState(current, previous, palettes,
activePaletteId, dish: DishState, mixerIsPigment)`.

### 9.3 Mixing dish

Two **wells** A and B, a slider `t`, a result strip, and two buttons:

```
[ A ]  ────────●────────  [ B ]      ← wells: tap = fill with current color; long-press = eyedrop into it
[ ■ ■ ■ ■ ▣ ■ ■ ■ ■ ]                ← 9-step strip from A to B computed by mixer.mix(A, B, i/8); the ● marks t
        ( use )  ( add to palette )
```

- The result at `t` becomes the *current color* on "use" (and on tapping
  a strip step, which also snaps `t`); "add to palette" appends it to
  the active user palette (creates "My palette" if the active one is
  built-in).
- The strip is the honest demo of the mixer: with `MixboxMixer` the
  middle of blue→yellow is green; with `RgbMixer` it is gray, and a small
  "RGB" badge replaces the "pigment" badge so the user knows why.
- Wells persist in Prefs so the dish survives leaving the canvas; the
  slider resets to 0.5.
- `DishState(a: Int, b: Int, t: Float)` and the pure function
  `dishSteps(mixer, a, b, n)` live in `engine/core`/`ui/canvas` and are
  unit-tested with both mixers.

**N-well dish (post-v1).** The `LatentColorMixer.mixWeighted` path is
already there; the UX question (weights as slider lengths? drops per
well?) is a `docs/proposals/` entry. The two-well dish stays the default
because it is the one that fits a phone-width sheet.

### 9.4 Eyedropper integration

`EyedropperTool` (`docs/plan/04-tools.md` §Eyedropper) samples the
composite (or the current layer) by readback, unpremultiplies, and
returns an opaque `Argb` — a fully transparent sample returns the paper
color, because that is what the user sees. On *transparent* paper there is
no paper color to return: a fully transparent sample leaves the current
color unchanged, the loupe shows the checkerboard, and the pick ends with
the "error" haptic instead of the tick ("Nothing here to pick"). Its result goes to whichever
target invoked it: the current color (default), a dish well (long-press
on a well), or a swatch being edited. Touch long-press and the S Pen
button (when configured, `docs/plan/07-input-and-stylus.md`) both route
here. The sampled color enters **Recent** only once painted with.

## 10. Tests (summary; the suite is `docs/plan/11-testing.md`)

```kotlin
class MixboxMixerTest {                                    // Mixbox source set; runs in testDebugUnitTest via the jar
    private val mixer = MixboxMixer()
    private val blue = 0xFF0000FF.toInt(); private val yellow = 0xFFFFFF00.toInt()

    @Test fun `blue plus yellow is green`() {
        val hsv = HsvColor.fromArgb(mixer.mix(blue, yellow, 0.5f))
        assertTrue("hue ${hsv.h}", hsv.h in 70f..170f)     // green-ish, tolerant of the exact pigment answer
        assertTrue(hsv.s > 0.3f)                             // and clearly not the RGB gray
        assertEquals(blue, mixer.mix(blue, yellow, 0f)); assertEquals(yellow, mixer.mix(blue, yellow, 1f))
    }
    @Test fun `endpoints are exact and mixing is symmetric`()  // mix(a,b,t) == mix(b,a,1-t) within 1/255
    @Test fun `weighted three-way equals pairwise composition`() // mixWeighted([a,b,c],[.5,.25,.25]) ≈ mix(a, mix(b,c,.5), .5)
    @Test fun `latent size matches the library constant`()
}

class RgbMixerTest {  // engine/core
    @Test fun `blue plus yellow is gray`() = assertEquals(0xFF808080.toInt(), RgbMixer.mix(blue, yellow, 0.5f))
    @Test fun `rgb mixer is component-linear in stored sRGB`()   // mix(a,b,t) == round(a + (b-a)·t) per channel
}

class MixOverMathTest {  // engine/core: the CPU twin of bangni_mix_over_pm, used by Composite as the reference
    @Test fun `empty destination yields pure paint at coverage`()
    @Test fun `zero coverage is bit-exact identity`()
    @Test fun `with RgbMixer it equals premultiplied source-over`()   // pins the non-mixing GPU program
    @Test fun `share t is k over a`()
}
```

Plus `GlShaderContractTest` assertions from §5.2, the LUT sha256 test
from §5.1, and `PaletteStoreTest` (round trip, tmp+rename, corrupt file
→ built-in fallback). The GPU program is validated on device by §5.3.
