# 04 — Tools

**What this covers.** The implementable catalog behind PLAN.md §6 and
decision 9 ("tools are presets over one engine"): the `Tool`/`ToolKind`
model, the `BrushPreset` data class with every parameter and its meaning,
the built-in presets with their actual values, the `DabGenerator`
and `Stabilizer` math, and the four non-brush tool groups (smudge/blur, Water,
fill, eyedropper) plus the eraser's S Pen bindings. It expands PLAN.md; where the
engine plumbing (stroke buffer, `DabPass`, ping-pong RMW, tiles, readback)
is *how pixels move*, this document is *what numbers we feed it*. The
pipeline itself is `docs/plan/03-canvas-engine.md`; color math is
`docs/plan/09-color-and-mixing.md`; raw stylus semantics are
`docs/plan/07-input-and-stylus.md`; the settings sheet that edits presets is
`docs/plan/08-ui-and-layout.md`.

## 1. The Tool model

Two layers, deliberately: `engine/core` knows *kinds and parameters* (pure
JVM, testable); `tools/` knows *what engine operations a tool performs when
the pen moves*. Nothing in `engine/core` imports Android or GL.

```kotlin
// engine/core
@Serializable
sealed interface ToolKind {
    @Serializable data class Brush(val preset: BrushPreset) : ToolKind   // pencil, pen, watercolor, airbrush, marker, erasers
    @Serializable data class Smudge(val params: SmudgeParams) : ToolKind
    @Serializable data class Blur(val params: BlurParams) : ToolKind
    @Serializable data class Water(val params: WaterParams) : ToolKind
    @Serializable data class Fill(val params: FillParams) : ToolKind
    @Serializable data class Eyedropper(val params: EyedropperParams) : ToolKind
}

// tools/ — one object per kind, all stateless except for the live stroke
interface Tool {
    val kind: ToolKind
    /** Called on the main thread with an allocation-free StrokeInput sample. */
    fun onStrokeBegin(input: StrokeInput, ctx: StrokeContext)
    fun onStrokeSample(input: StrokeInput, ctx: StrokeContext)
    fun onStrokeEnd(ctx: StrokeContext)        // pen-up: flush, commit
    fun onStrokeCancel(ctx: StrokeContext)     // palm rejection: roll back
}
```

Why `Eraser` is not a `ToolKind`: an eraser is a `BrushPreset` with
`eraseMode = true`. It goes through exactly the same stabilizer, dab
generator and stroke buffer as a pencil; the only difference is the merge
step at pen-up (`dst.a *= 1 − strokeAlpha`, see §3.7 and
`03-canvas-engine.md` §7.4). Making it a separate kind would
duplicate 90 % of `BrushTool` for one boolean. PLAN.md §3's
`tools/EraserTool` is therefore a one-line `BrushTool` subclass pinned to
`eraseMode` presets (the rail slot and the S Pen bindings point at it), not
a kind. The UI still shows "Eraser" as its own rail slot: the rail slot is
a *preset*, not a kind.

`StrokeContext` is the tool's handle to the engine: the active layer id,
the dab ring buffer to the GL thread, the current `ViewTransform` (for
canvas-px ↔ screen-px conversions), the current color (both RGB and its
Mixbox latent, pre-computed once per color change), the selection mask
(always "everything" in v1, see §10), and the `HistoryJournal` to open
one `HistoryEntry` per stroke.

### 1.1 Which tools are which

| Tool (rail slot) | `ToolKind` | Engine path | Writes to |
| --- | --- | --- | --- |
| Core buffered rail: Pencil, Ink pen, Airbrush, Marker; library: Spray can, Charcoal, Soft pastel, Technical pen, Chinese ink brush, Dry brush, Oil paint, Pigment wash | `Brush(preset)` | `DabPass` → stroke buffer → merge on pen-up | active layer |
| Watercolor | `Brush(preset.watercolor != null)` | `WatercolorPass` RMW per dab | active layer colour + transient wet state |
| Hard eraser, Soft eraser | `Brush(preset.eraseMode=true)` | `DabPass` → stroke buffer → erase merge on pen-up | active layer (alpha only) |
| Smudge | `Smudge` | `SmudgePass` ping-pong RMW per dab | active layer, live |
| Blur | `Blur` | `SmudgePass` variant (separable kernel) | active layer, live |
| Water | `Water` | `WatercolorPass` without pigment deposition | active layer colour + transient wet state |
| Fill | `Fill` | CPU `FloodFill` → coverage uploaded into the stroke buffer → normal merge | active layer, one shot |
| Eyedropper | `Eyedropper` | `Readback` of one pixel (composite or layer) | nothing — sets color |

RMW tools bypass the stroke buffer because their result depends on what
was already under the dab; "never exceed opacity" is meaningless for them.
They journal colour identically to brushes, though: at pen-up the dirty
`TileKey`s are the union of the dab rects and the before contents come
from `06-document-and-persistence.md §5.5` (mirror/disk hold last-commit
state until this stroke's own readback lands). Only ordering matters: the
smudge stroke's readback must not be swapped into the mirror before the
entry captures its sources — the same (a)–(d) order as a brush commit.
Blank Water that touches no colour reports no dirty colour keys and creates
no history entry.

## 2. `BrushPreset`

Every parameter a brush can have, all serializable, all with defaults so a
preset JSON can omit what it does not care about. Sizes are **canvas
pixels** (never screen px, never dp): a pencil is the same width on the
paper regardless of zoom, and a stroke journal replays nothing so there is
no device-independence concern beyond "what you see is what is stored".

```kotlin
@Serializable
data class BrushPreset(
    val id: String,                       // stable, e.g. "builtin.pencil"; user copies get uuids
    val name: String,
    val icon: String = "round",           // rail glyph key
    val size: Float = 12f,                // diameter, canvas px (the slider value)
    val sizeMin: Float = 1f,
    val sizeMax: Float = 400f,
    val opacity: Float = 1f,              // stroke-buffer ceiling, 0..1
    val flow: Float = 1f,                 // per-dab weight, 0..1
    val hardness: Float = 0.8f,           // 0 = gaussian-ish falloff, 1 = solid disc with 1px AA
    val spacing: Float = 0.30f,           // fraction of the dab *radius* between dab centres (PLAN.md §3.1)
    val tip: TipShape = TipShape.Round,
    val orientation: TipOrientation = TipOrientation.Fixed,
    val pressureSize: Curve = Curve.Linear,     // pressure → size multiplier 0..1
    val pressureOpacity: Curve = Curve.One,     // pressure → opacity multiplier
    val pressureFlow: Curve = Curve.One,        // pressure → flow multiplier
    val tilt: TiltEffect = TiltEffect.None,
    val velocity: VelocityEffect = VelocityEffect.None,
    val jitter: Jitter = Jitter.None,
    val stabilizer: Float = 0.3f,         // 0..1, §4
    val mixing: Boolean = false,          // Mixbox pigment merge instead of alpha-over (09 §3.1); effective only when the mixer is pigment (09 §4)
    val dilution: Float = 0f,             // 0..1, mixing only: how much the paint's share yields to what is under it (09 §3.1)
    val watercolor: WatercolorBehavior? = null, // non-null selects direct wet RMW
    val grain: String? = null,            // post-v1: asset key of a tileable grain texture
    val model: BrushModel = BrushModel.Standard,
    val eraseMode: Boolean = false,
    val bufferMode: BufferMode = BufferMode.Max,
)

@Serializable sealed interface TipShape {
    @Serializable object Round : TipShape
    /** aspect = minor/major axis, 0.1..1. A flat marker is ~0.3. */
    @Serializable data class Flat(val aspect: Float) : TipShape
}
@Serializable enum class TipOrientation { Fixed, Stylus, StrokeDirection }
@Serializable enum class BrushModel { Standard, ChineseInk }

/** 4-point monotone curve on [0,1]: y at x = 0, 1/3, 2/3, 1; evaluated with Catmull-Rom clamped to [0,1]. */
@Serializable data class Curve(val p0: Float, val p1: Float, val p2: Float, val p3: Float) {
    fun eval(x: Float): Float
    companion object {
        val Linear = Curve(0f, 1f/3, 2f/3, 1f)
        val One = Curve(1f, 1f, 1f, 1f)                 // ignores pressure
        fun gamma(g: Float): Curve                       // y = x^g sampled at the 4 knots
        fun floor(min: Float): Curve                     // y = min + (1-min)·x
    }
}

@Serializable data class TiltEffect(
    val sizeAtFlat: Float = 1f,       // size multiplier when tilt = π/2 (1 = no effect); lerp by tilt/(π/2)
    val opacityAtFlat: Float = 1f,    // opacity multiplier at full tilt
    val elongate: Boolean = false,    // stretch the dab along the tilt azimuth (side-of-pencil shading)
) { companion object { val None = TiltEffect() } }

@Serializable data class VelocityEffect(
    val sizeAtFast: Float = 1f,       // multiplier at speed ≥ fastPxPerMs
    val opacityAtFast: Float = 1f,
    val fastPxPerMs: Float = 4f,      // canvas px per ms considered "fast"
) { companion object { val None = VelocityEffect() } }

@Serializable data class Jitter(
    val size: Float = 0f,             // ± fraction of size, uniform
    val position: Float = 0f,         // ± fraction of radius, per axis
    // no hue jitter in v1: a stroke has one colour (09 §3.1); see §10
) { companion object { val None = Jitter() } }

/** How a dab lands in the stroke buffer. Max: a = max(a, dabA) — flat, never darker than opacity.
 *  Accumulate: a = a + dabA·(1−a) — builds up with overlaps, still capped at opacity by the merge. */
@Serializable enum class BufferMode { Max, Accumulate }
```

Why a 4-point curve and not a spline editor: four knots at fixed x is
enough to express "soft start", "hard threshold", "floor at 30 %" and every
gamma we tried, it serializes to four floats, and the settings sheet can
draw it as four draggable handles on a 200 dp square without becoming a
curve editor. The `gamma`/`floor` helpers exist so presets read as intent.

`spacing` is a fraction of the dab **radius**, as PLAN.md §3.1,
`03-canvas-engine.md` §6 and the glossary state (`step = spacing ·
radius`). Brush literature and most apps' sliders express spacing as "% of
brush size" (diameter), so the settings sheet *displays* `spacing / 2` as
a percentage of size; the stored value is the radius fraction. Either way
the invariant PLAN §7 tests ("spacing invariant under resolution") holds.

### 2.1 What each parameter does to the pixels

| Parameter | Effect in `DabPass` (GLSL, see `03-canvas-engine.md §DabPass`) |
| --- | --- |
| `size`, `pressureSize`, tilt/velocity size | radius `r = size/2 · curve(p) · tiltMul · velMul · (1 ± jitter)`, clamped to `≥ 0.5` (§3.5) |
| `hardness` | falloff `f(d) = 1 − smoothstep(min(h·r, r − 1), r, d)` (`03-canvas-engine.md` §7.3: gives `f = 1` inside the inner radius, no separate branch; the falloff band is never thinner than 1 canvas px, so hardness 1 is a crisp anti-aliased disc, not a jaggy one) |
| `flow` | dab alpha `a = flow · pressureFlow(p) · f(d)` |
| `opacity` | uniform in the **merge** step: `strokeAlpha = min(buffer.a, opacity · pressureOpacityMax)` (§3.3, §3.7; `03-canvas-engine.md` §7.4); never in the dab |
| `tip = Flat(aspect)` | `d` computed in a rotated ellipse frame: `d = length(vec2(u, v/aspect))` where `(u,v)` is the offset rotated by the dab's angle |
| `orientation` | dab angle = 0 (`Fixed`), the stylus `AXIS_ORIENTATION` (`Stylus`), or `atan2(dy, dx)` of the stabilized path (`StrokeDirection`) |
| `tilt.elongate` | major axis multiplied by `1 + tilt/(π/2)`, aligned to the tilt azimuth: the side of a pencil |
| `model` | `Standard` uses the ordinary tip math. `ChineseInk` adds transported tuft direction, pressure splay, stationary presses and a depleting split-bristle mask (§3). The model is fixed at pen-down. |
| `mixing` | merge calls `bangni_mix_over(D, c_p, k)` from `09-color-and-mixing.md` §3.1 with `k = strokeAlpha`; the pigment share `t = k / a` is derived inside, reduced by `dilution` where the layer already has paint, alpha stays linear coverage. One formula (`03-canvas-engine.md` §7.4's MIX branch), pinned by the CPU `Composite` reference |
| `dilution` | mixing only: `t *= 1 − dilution` when the destination has paint (09 §3.1); 0 = plain pigment share |
| `watercolor` | non-null routes the preset through `WatercolorPass`; requires mixing, Accumulate, opacity 1, constant pressure opacity, and diameter ≤ 1960 px |
| `eraseMode` | merge: `dst.a *= 1 − strokeAlpha`, premultiplied RGB scaled with it |
| `bufferMode` | stroke-buffer blend equation: `GL_MAX` vs standard over (`03-canvas-engine.md` §7.2) |

## 3. `DabGenerator`

Turns the stabilized path into `Dab`s. Runs on the main thread inside the
motion handler, so it is allocation-free: it writes into a preallocated
`DabBatch` (struct-of-arrays of floats, `DAB_BATCH_CAPACITY` = 1024 dabs
per ring slot, `02-architecture.md` §3.2 / `10-performance.md` §4) that the
GL thread drains through the ring buffer.

```kotlin
class Dab(   // conceptually; eleven parallel FloatArrays in DabBatch
    val x: Float, val y: Float,      // canvas px, sub-pixel
    val radius: Float,               // px, ≥ 0.5
    val flow: Float,                 // dab alpha after curves, 0..1
    val hardness: Float,
    val angle: Float,                // radians
    val aspect: Float,               // 1 = round
    val seed: Float,                 // per-dab Standard phase; fixed per Chinese-ink stroke
    val wetness: Float,              // contacted-tuft ink load, 0..1; ordinary dabs use 1
    val bristleAlong: Float,         // transported material coordinates
    val bristleAcross: Float,        // ordinary dabs use 0 for both
)

class DabGenerator(preset: BrushPreset, seed: Long) {
    fun begin(first: StrokeInput, out: DabBatch)          // emits the first dab immediately
    fun advance(next: StrokeInput, out: DabBatch)         // emits 0..n dabs between last and next
    fun end(out: DabBatch)                                // taps: guarantee ≥ 1 dab; flush residual distance
}
```

### 3.1 Spacing along the path

State: last stabilized point `P₀` with its dynamics, and `carry` — the
distance already travelled since the last dab. For each new stabilized
point `P₁`:

```
step   = max(spacing · radius(p̄), 0.5)                // radius = size/2 · curveSize(p̄); never denser than half a pixel
len    = |P₁ − P₀|
t      = (step − carry) / len
while t ≤ 1:
    emit dab at lerp(P₀, P₁, t) with dynamics lerped from (P₀, P₁) at t
    t += step / len
carry  = len − (t − step/len)·len                       // remainder, kept for next segment
```

Dynamics (pressure, tilt, velocity) are interpolated *per dab*, not taken
from the endpoints, so a fast pressure ramp inside one motion event still
produces a smooth taper. `step` uses the *pressured* size (mean of the two
endpoints), so a hard pencil at light pressure keeps the same overlap ratio
as at full pressure. Spacing is expressed in canvas px and the path is in
canvas px, which is the "invariant under resolution" test in PLAN §7.

**Chinese ink state.** `BrushModel.ChineseInk` gives `builtin.calligraphy`
(the stable stored id retained from the display rename) a flexible tuft rather
than a rigid chisel. A directionless first touch is round. Once the stroke moves,
its target axis follows the stabilized path,
nudged toward stylus azimuth as tilt rises; the tuft eases toward that target
and preserves its incoming direction through a turn. Pressure splays the tuft
from point to belly. A pressure increase at zero path length emits a new dab
at the same centre, so pressing in place forms a stroke head.

One stroke seed fixes the bristle lanes. A separate canvas-fixed hash gives
every stroke the same paper tooth. `wetness` begins loaded and decays
with swept canvas distance, accelerated by speed; it never decays per emitted
dab, so changing spacing cannot change how soon the brush runs dry. Constant
flow keeps retained hairs ink-black; pressure changes contact geometry. Its
velocity curve permits only a 4 % width taper; speed primarily dries the
mark. The CPU oracle and shader derive the split-bristle mask from local
position plus along/across material phases integrated in the lagged tuft
frame. As wetness falls,
coherent lanes become zero-alpha paper gaps while surviving hairs stay
ink-dark. Generator copies used for the predicted tail copy this state exactly
and never advance the real stroke.

The model follows measured brush behavior rather than a Western chisel
metaphor. Lo et al.'s
[robot footprint study](https://group-iris.com/wp-content/uploads/2024/06/2006-Brush-Footprint-Acquisition-and-Preliminary-Analysis-for-Chinese-Calligraphy-using-a-Robot-Drawing-Platform.pdf)
found footprint axes grow strongly with penetration while steady linear speed
changes them little. Chu and Tai's
[expressive virtual brush](https://cse.hkust.edu.hk/VCB/CGA%20Brush%202004.pdf)
uses retained deformation, split maps and contact-height thresholds for dry
streaks. Here pressure grows the footprint, direction retains deformation,
and wetness thresholds one stable procedural contact field. Paper diffusion
is deliberately not claimed.

### 3.2 Sub-pixel placement

Dab centres are floats; `DabPass` renders each dab as a quad of
`2·(r + 1)` px snapped *outwards* to integer bounds, and the fragment
shader measures distance from the exact float centre. There is no pixel
snapping anywhere, which is what keeps slow diagonal lines from stair-
stepping. Jitter is added *after* spacing (it perturbs where the dab is
painted, not how far along the path we are), so jitter never changes dab
count.

### 3.3 Mapping dynamics through curves

| Input | Source (`StrokeInput`) | Transform | Feeds |
| --- | --- | --- | --- |
| pressure `p` | `getPressure()` clamped to 0..1, then the global `PressureCurve` (device calibration + the Softer / Linear / Harder preference, applied by the touch handler — `07-input-and-stylus.md` §2), then the preset curve | `pressureSize.eval`, `pressureOpacity.eval`, `pressureFlow.eval` | radius, opacity ceiling*, dab alpha |
| tilt `θ` | `AXIS_TILT`, 0..π/2 | `u = θ / (π/2)`; `mul = lerp(1, atFlat, u)` | radius, dab alpha, elongation |
| velocity `v` | `|ΔP| / Δt` in canvas px/ms over the last 3 samples (EMA) | `u = clamp(v / fastPxPerMs)`; `mul = lerp(1, atFast, u)` | radius, dab alpha |
| finger (no pressure) | `TOOL_TYPE_FINGER` | `p = 1` constant; a size curve with a floor still applies its floor | everything |

\* `pressureOpacity` affects the **stroke** opacity ceiling, which is
one number per stroke in the merge. We take the *max* pressure-opacity
seen during the stroke, so a stroke that starts light and presses hard
ends up at the hard-pressure opacity everywhere — that matches how
"pressure → opacity" reads to users (harder = darker line), and avoids
seams. Per-dab darkness is what `pressureFlow` is for; the pencil uses
both.

Curves are evaluated by a 256-entry LUT built once per preset change, not
by Catmull-Rom per dab (a 1024-dab batch at 120 Hz must cost microseconds).

### 3.4 Taps make dots

`end()` guarantees at least one dab: if `begin()` emitted the first dab
and no motion followed, a single dab at full dynamics is already there. If
the stroke was shorter than `step`, the residual `carry` does *not* emit
(that would double-dot every tap). Chinese-ink pressure-only dabs are the
intentional stationary splay described in §3.1, not residual spacing. For a
*tap with pressure ramp* (S Pen
touching and lifting within ~30 ms) the first dab is re-emitted with the
**maximum** pressure seen during the tap, because the `ACTION_DOWN` sample
almost always carries near-zero pressure and would leave an invisible
dot. This is done by `end()` overwriting the batch's first dab if the
stroke length is < `step`.

### 3.5 Radius clamp

`radius ≥ 0.5 px` always; the rendered footprint is `r` with a ≥ 1 px
inner anti-aliasing band (`03-canvas-engine.md` §7.3). A 1 px ink pen at
light pressure therefore still draws a faint 1 px anti-aliased line instead
of vanishing between sub-pixel gaps; alpha, not radius, carries the
"lighter" impression below 1 px (`DabPass` draws sub-pixel dabs at `r = 1`
and weights their alpha by the area `r²`, so a thinning stroke fades out
rather than snapping off). The upper clamp is `sizeMax` after all
multipliers, and a hard ceiling of 1024 px so one dab quad never exceeds
the tile atlas's reasonable dirty rect.

### 3.6 Hue jitter (post-v1)

Not in v1. The stroke buffer accumulates *coverage only* and a stroke has
one colour `c_p` (`09-color-and-mixing.md` §3.1), so per-dab hue cannot
be honoured by the merge. The sketch lives in §10: it requires the stroke
buffer to carry premultiplied colour, and the merge then uses
`buffer.rgb` instead of `c_p`.

### 3.7 Erase mode in the dab pipeline

Identical dab generation and stroke buffer. At merge:

```
strokeAlpha = min(buffer.a, opacity · pressureOpacityMax)   // the per-stroke ceiling of §3.3
dst.rgba   *= (1 − strokeAlpha)          // premultiplied: RGB scales with alpha
```

so a soft eraser at 50 % opacity can never remove more than half the
alpha in one stroke however many times it crosses itself — exactly the
"never exceeds opacity" guarantee brushes get. Alpha-locked layers
(`05-layers.md`) make erase a no-op and the rail shows the lock.

## 4. `Stabilizer`

"Pull string" / exponential smoothing in canvas space.

```kotlin
class Stabilizer(strength: Float) {         // 0..1
    fun reset(p: StrokeInput)               // output = input
    fun push(raw: StrokeInput, out: StrokeInput): Boolean  // moved ≥ 0.05 px, or ChineseInk dynamics changed
    fun finish(out: StrokeInput): Int       // emits catch-up samples toward the last raw point
}
```

Per raw sample, the output point moves toward the raw point by a fraction
`k`:

```
k = 1 − strength^0.5 · 0.95           // strength 0 → k = 1 (pass-through); 1 → k = 0.05
S ← S + (R − S) · k
```

plus a **string length** `L = strength · 24 px` (canvas px scaled by
`1/zoom` so the feel is constant on screen): if `|R − S| > L`, `S` is
first snapped to distance `L` from `R` along the line, *then* eased. This
is what makes it "pull string": the pen leads, the brush follows on a
short leash, and fast motion does not lag ever further behind (pure
exponential smoothing would). Pressure/tilt are smoothed with the same `k`
so a taper follows the smoothed geometry.

Ordinary brushes forward a sample only after meaningful position motion.
`ChineseInk` also forwards pressure, tilt, or orientation changes. Pressure
can stamp the tuft in place; current tilt and orientation feed its next moving
segment. Existing brush sampling does not change.

**Lag at stroke end.** With strength 0.7 the output trails the pen by up
to ~17 px on screen. While the pen is down this is visible and expected
(ink pen users want it). On pen-up `finish()` walks `S` toward the last
raw point in steps of `step` (the current dab spacing) so the stroke
*catches up* to where the pen actually lifted: the end of a pen stroke is
where you lifted, not where the leash was. Catch-up samples carry the last
pressure, decayed linearly to the final raw pressure, so the tail tapers
rather than blobbing. The predicted tail (`07-input-and-stylus.md` §8) is
run through a *copy* of the stabilizer and generator state
(`03-canvas-engine.md` §9) so it continues the stabilized line instead of
jumping ahead of it; predicted samples never advance the real stabilizer
state, and the tail is drawn in the front layer only.

Strength per preset is the default; the rail's settings sheet exposes it,
and the value is also nudged by zoom: `effective = strength · clamp(1 /
zoom, 0.25, 1)`, because at 4× zoom the raw jitter is already 4× smaller
on paper and full stabilization feels like drawing in syrup. Both `k` and
the leash `L` are computed from the *effective* strength (so `L` shrinks
with zoom as well; the `1/zoom` in `L` is the screen-px conversion on top
of that). `StabilizerTest` checks that at zoom 4 both `L` and `1 − k` are
smaller than at zoom 1.

## 5. Built-in presets

Values are what ships in `assets/brushes/*.json`; the tables *are* the
spec for PR 5's acceptance ("every preset matches its §6 description on
device"). All sizes in canvas px; spacing is a fraction of the radius (§2);
"curve" values are the four knots.

| Preset | size (min–max) | opacity | flow | hardness | spacing | tip / orientation | stabilizer | mixing (dilution) | erase |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Pencil | 4 (1–40) | 0.9 | 0.35 | 0.75 | 0.16 | Round / Fixed | 0.2 | off | – |
| Ink pen | 6 (1–60) | 1.0 | 1.0 | 1.0 | 0.10 | Round / Fixed | 0.7 | off | – |
| Watercolor | 40 (4–400) | 1.0 | 0.45 | 0.25 | 0.20 | Flat(0.7) / StrokeDirection | 0.35 | **on** (0.40) | – |
| Airbrush | 120 (10–400) | 1.0 | 0.06 | 0.0 | 0.08 | Round / Fixed | 0.1 | off | – |
| Marker | 24 (4–200) | 0.6 | 1.0 | 0.95 | 0.12 | Flat(0.3) / Stylus | 0.4 | off | – |
| Spray can | 80 (10–300) | 1.0 | 0.045 | 0.0 | 0.50 | Round / Fixed | 0.05 | off | – |
| Charcoal | 12 (2–120) | 0.95 | 0.28 | 0.6 | 0.12 | Round / Fixed | 0.15 | off | – |
| Soft pastel | 40 (6–200) | 0.8 | 0.24 | 0.62 | 0.14 | Flat(0.65) / Stylus | 0.18 | off | – |
| Technical pen | 4 (1–24) | 1.0 | 1.0 | 1.0 | 0.12 | Round / Fixed | 0.8 | off | – |
| Chinese ink brush | 40 (3–240) | 1.0 | 1.0 | 0.92 | 0.08 | Flat(0.58) / StrokeDirection | 0.3 | off | – |
| Dry brush | 52 (6–300) | 0.9 | 0.22 | 0.78 | 0.16 | Flat(0.45) / StrokeDirection | 0.25 | **on** (0.05) | – |
| Oil paint | 64 (8–400) | 1.0 | 0.95 | 0.55 | 0.25 | Flat(0.6) / StrokeDirection | 0.3 | **on** (0.25) | – |
| Pigment wash | 120 (12–600) | 0.38 | 0.12 | 0.18 | 0.10 | Flat(0.75) / StrokeDirection | 0.22 | **on** (0.65) | – |
| Hard eraser | 30 (2–400) | 1.0 | 1.0 | 0.95 | 0.20 | Round / Fixed | 0.2 | off | yes |
| Soft eraser | 80 (4–400) | 0.5 | 0.4 | 0.15 | 0.16 | Round / Fixed | 0.2 | off | yes |

Chinese ink brush alone sets `model = ChineseInk`; all other rows use
`Standard`. Its `Flat(0.58)` / `StrokeDirection` values describe neutral
geometry; the model state owns the directionless head, moving aspect and
target lag.

| Preset | pressureSize | pressureOpacity | pressureFlow | tilt | velocity | jitter | bufferMode |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Pencil | floor(0.7) | gamma(0.8) | gamma(1.3) | size 2.2 at flat, opacity 0.5, elongate | none | size 0.1, pos 0.15 | Accumulate |
| Ink pen | gamma(1.4), floor 0.15 blended: `Curve(0.15, 0.3, 0.6, 1)` | One | One | none | size 0.85 at fast (2 px/ms) | none | Max |
| Watercolor | `Curve(0.35, 0.6, 0.85, 1)` | One | gamma(0.7) | size 1.4 at flat | none | size 0.05 | Accumulate |
| Airbrush | `Curve(0.6, 0.75, 0.9, 1)` | One | Linear | none | none | none | Accumulate |
| Marker | One (constant width) | One | One | none | none | none | Max |
| Spray can | `Curve(0.7, 0.8, 0.9, 1)` | One | Linear | none | none | size 0.35, pos 0.85 | Accumulate |
| Charcoal | `Curve(0.6, 0.73, 0.87, 1)` | `Curve(0, 0.415244, 0.722981, 1)` | `Curve(0, 0.19245, 0.544331, 1)` | size 2.6 at flat, opacity 0.6, elongate | none | size 0.18, pos 0.22 | Accumulate |
| Soft pastel | `Curve(0.65, 0.75, 0.87, 1)` | `Curve(0.15, 0.4, 0.72, 1)` | `Curve(0.08, 0.3, 0.65, 1)` | size 1.8 at flat, opacity 0.75, elongate | none | size 0.18, pos 0.15 | Accumulate |
| Technical pen | One | One | One | none | none | none | Max |
| Chinese ink brush | `Curve(0.05, 0.22, 0.62, 1)` | One | One | none | size 0.96 at fast (2.5 px/ms); `ChineseInk` also dries | none | Max |
| Dry brush | `Curve(0.3, 0.5, 0.75, 1)` | One | `Curve(0.03, 0.18, 0.55, 1)` | none | none | size 0.12, pos 0.12 | Accumulate |
| Oil paint | `Curve(0.5, 0.75, 0.92, 1)` | One | `Curve(0.35, 0.6, 0.85, 1)` | size 1.3 at flat | none | size 0.12, pos 0.05 | Accumulate |
| Pigment wash | `Curve(0.65, 0.75, 0.87, 1)` | `Curve(0.25, 0.5, 0.75, 1)` | `Curve(0.15, 0.42, 0.72, 1)` | size 1.5 at flat, opacity 0.7 | none | size 0.05, pos 0.04 | Accumulate |
| Hard eraser | floor(0.5) | One | One | none | none | none | Max |
| Soft eraser | floor(0.6) | Linear | Linear | none | none | none | Accumulate |

Why the values feel the way they do:

- **Pencil.** Low flow (0.35) with tight spacing and `Accumulate` means a
  single pass is grey and going over it darkens — graphite behaviour.
  Pressure goes mostly into flow (gamma 1.3: light touch stays faint) and
  only a little into size (floor 0.7 → a hard press is at most 43 % wider).
  Tilt does the real work: at full tilt the dab is 2.2× wider, elongated
  along the tilt azimuth and half as opaque — the side of the lead for
  shading. Position jitter 0.15 breaks the dab edge into a slightly
  grainy line even before the v2 grain texture; hardness 0.75 keeps it
  from looking like a soft brush.
- **Ink pen.** Hardness 1 with the 1 px AA skirt is a crisp line; flow 1,
  `Max` buffer and opacity 1 mean overlaps are invisible (ink does not
  build up). Pressure → size on a curve that starts at 15 % (a thin hair
  line is always possible) and rises slowly then quickly — that is where
  the calligraphic thick/thin comes from. Velocity thins fast strokes to
  85 %, a subtle cue that reads as confidence. Strong stabilizer (0.7)
  because a pen line has nowhere to hide wobble.
- **Watercolor.** The flat 0.7 tip follows the stroke and widens with
  tilt. Flow 0.45 controls direct pigment deposition; opacity stays 1
  because this RMW path has no stroke-buffer ceiling. Water 0.72, Spread
  0.60, Granulation 0.32, and Edge darkening 0.40 drive the coarse wet
  field, paper mobility, and bounded rim deposit. Mixing is on with 0.40
  dilution, so crossings use the selected Mixbox/RGB mixer rather than a
  plain translucent over.
- **Airbrush.** Hardness 0, flow 0.06, spacing 0.08: hundreds of nearly
  invisible dabs per stroke that build a smooth gradient; `Accumulate` is
  what makes it build. Size barely responds to pressure (60 % floor),
  flow does, which is the "further away / closer" feel. Stabilizer low —
  the falloff hides jitter anyway.
- **Marker.** Constant width (no pressure → size), hardness 0.95, flow 1
  but **opacity 0.6 with `Max`**: one pass is a flat 60 % tint,
  overlapping *within the stroke* never gets darker, but a second stroke
  over the first does (0.6 over 0.6 = 0.84) — exactly how a felt marker
  layers. Flat(0.3) tip oriented by the stylus so the chisel tip turns
  with the pen; on finger input orientation is 0 and it is a horizontal
  chisel, which is still recognisably a marker.
- **Charcoal and soft pastel.** Procedural paper grain, pressure-driven flow,
  jitter, and broad tilt shading separate a dusty stick from the clean pencil.
- **Technical pen.** A hard 4 px round tip ignores pressure and uses the
  strongest stabilizer for constant drafting lines.
- **Spray can.** Wide position and size jitter scatter low-flow soft dabs;
  pressure controls coverage without changing the paint model.
- **Chinese ink brush.** The only `ChineseInk` preset: a pointed flexible tuft
  splays under pressure, trails through turns and reveals stable split hairs
  as its distance-based ink load falls. Stationary pressure forms a deliberate
  head; speed makes the mark drier, not materially narrower.
- **Dry brush.** Low flow, grain, jitter, and a narrow path-oriented tip leave
  a broken mark while retaining pigment mixing.
- **Oil paint.** High-flow, broad pigment dabs build opaque, mixed strokes.
- **Pigment wash.** Low opacity, low flow, and strong dilution layer broad,
  transparent pigment without claiming watercolor bloom or diffusion.
- **Hard eraser.** A hard disc; `Max` with opacity 1 so one pass removes
  fully. Size floor 0.5 so light pressure still erases, just narrower.
- **Soft eraser.** Hardness 0.15, opacity 0.5: it *lightens*; one stroke
  takes at most half the alpha (a real kneaded eraser lifts, it does not
  cut). Flow 0.4 and `Accumulate` so scrubbing within the stroke builds
  toward the 50 % cap.

The pencil's `grain` is `null` in v1; when grains land (§10) the built-in
pencil gets `"grain": "paper-fine"` and the position jitter drops to 0.05.

### 5.1 Preset storage

- Built-in: `assets/brushes/<id>.json`, loaded once by `BrushPresetStore`,
  immutable. Ids `builtin.*`. Their `name` is a string-resource key with
  the `@string/` prefix (`"@string/preset_pencil"`) resolved through
  resources at display time; any other `name` value — a user's preset or
  rename — is displayed verbatim (`01-product.md` §8). The same rule
  applies to layer names (`06-document-and-persistence.md` §3).
- User edits: the settings sheet edits a *copy* written to
  `filesDir/brushes/<id>.json` with the same id (an override), so "reset
  to default" is "delete the override". Journal-free: presets are not part
  of any painting's document (a painting stores pixels, not brushes).
- Format versioned by a top-level `"v": 1`; unknown fields are ignored
  (`ignoreUnknownKeys = true`) so old builds open new presets.
- Brush quick sliders write `size` and the secondary value (opacity or
  Watercolor flow) into the session copy and remember them per preset in
  `Prefs`. Water's quick Size and Water values remain session-only. Neither
  path edits JSON; slider fiddling is not "editing a brush".
- FULL-rail paint assignments are an ordered list of preset ids in `Prefs`.
  Choosing a preset swaps it into the active slot; the active slot index stays
  session-only. Assignments are app chrome, never painting or preset data.

### 5.2 Watercolor and Water

`WatercolorBehavior(waterLoad, spread, granulation, edgeDarkening)` is
defaulted and serializable. A non-null value selects the direct RMW path.
Watercolor presets must use mixing, `Accumulate`, opacity 1, and
`pressureOpacity = Curve.One`, and must keep `BrushModel.Standard`; the direct
path cannot consume Chinese Ink tuft or bristle state. The settings sheet
hides stroke opacity and pressure opacity, which direct RMW cannot honour,
and exposes Water, Spread, Granulation, and Edge darkening.

The transient wet grid is separate from `Dab.wetness`, which remains only
Chinese-ink tuft load and never drives diffusion. Water consumes the committed
premultiplied layer pixels, so it transports paint from every brush model.

`WaterParams` defaults to size 72 (8–400), hardness 0.2, spacing 0.18,
water 0.75, spread 0.65, and linear pressure-to-water. Its sheet exposes
Size, Water, and Spread. Water keeps the selected colour unchanged and
sets the colour pass to transport-only.

Both tools execute one wet and optional colour update per accepted non-zero
dab. They never run a frame timer or a pen-up settle pass. Wet state lasts
12 seconds by lazy monotonic age and is transient; proposal 0002 owns the
engine and lifecycle details.

## 6. Smudge and Blur

Both are RMW tools running in `SmudgePass` (`03-canvas-engine.md` §7.6)
directly on the active layer; there is no stroke buffer and
the effect is visible immediately in the front layer.

```kotlin
@Serializable data class SmudgeParams(
    val size: Float = 40f, val sizeMin: Float = 4f, val sizeMax: Float = 400f,
    val hardness: Float = 0.5f,
    val spacing: Float = 0.16f,     // fraction of radius, as BrushPreset (§2); 03 §7.6 floors RMW spacing at 0.25·r
    val strength: Float = 0.7f,     // how much of the pickup is deposited per dab (0..1)
    val pickupRate: Float = 0.5f,   // ρ: how much fresh colour the pickup buffer absorbs per dab (0..1); 0 = "clean finger", pick up once at stroke start
    val mixing: Boolean = true,     // Mixbox latent blending in the pickup
    val pressureStrength: Curve = Curve.Linear,
    val stabilizer: Float = 0.3f,
)
```

**Pickup buffer.** A small RGBA texture the size of the largest dab
footprint (`sizeMax + 2` square, allocated once — `03-canvas-engine.md`
§7.6's `Reservoir`). On stroke start it is filled with
the pixels under the first dab (`ρ = 1` for the first dab, regardless
of the setting). Then, per dab, two passes over the dab rect, the exact
math being `09-color-and-mixing.md` §3.2:

1. *Deposit*: `L' = bangni_smudge_deposit(L, P, strength · curve(p) ·
   falloff(d))` — with `mixing` on, the colour lerp is `mixbox_lerp`;
   alpha is a premultiplied lerp, so a smudge can lower coverage.
2. *Absorb*: `P' = lerp_pm(P, L, ρ · falloff(d))`, sampling the layer
   *before* step 1 (the ping-pong's other buffer) so the tool does not
   absorb its own deposit. There is no separate "dirty" flag: a clean
   finger is `ρ = 0` after the first dab.

`strength` is therefore "how much is carried out of the pickup",
`pickupRate` is "how quickly the finger gets dirty". `strength 1, pickupRate
0` drags a snapshot of the start colour indefinitely (a "clone smear");
`strength 0.7, pickupRate 0.5` is the default that behaves like a fingertip: a short
smear moves the colour, a long one gradually fades into what is passed
over. With `mixing` the carried colour and the under colour mix as
pigments, so smudging a blue edge into yellow makes a green transition
rather than a grey one; the pickup buffer stores RGB (sRGB) and the pass
converts to latent per texel — the LUT lookup is cheap relative to the
RMW copy itself.

**Blur.** Same pass structure, no pickup buffer:

```kotlin
@Serializable data class BlurParams(
    val size: Float = 60f, val sizeMin: Float = 8f, val sizeMax: Float = 400f,
    val strength: Float = 0.5f,          // blend of blurred result over original
    val radiusFraction: Float = 0.15f,   // kernel radius = size · radiusFraction, clamped 1..24 px
    val spacing: Float = 0.30f,          // fraction of radius (§2)
    val pressureStrength: Curve = Curve.Linear,
)
```

Kernel is a separable **box blur run twice** (≈ gaussian, and two
1-D passes of `2·radius + 1` taps are far cheaper than a 2-D gaussian on a
400 px dab): horizontal pass into the ping-pong buffer, vertical pass back,
each over the dab rect *expanded by the kernel radius* so the edge of the
dab reads correct neighbours. Result blended with `strength · curve(p) ·
falloff(d)`. Radius is tied to size so a bigger blur tool blurs more,
which is what users expect, and capped at 24 px because larger kernels
cost quadratically in bandwidth for no visible gain at brush scale.

Both tools clamp their dab rects to the canvas and to the selection mask
(§10) when one exists.

## 7. Fill

CPU flood fill over the tile mirrors, uploaded as tiles, journaled as one
entry. CPU because a scanline fill is inherently sequential and a 4096²
canvas is 16 M pixels — a few hundred ms on one core, which we make
cancellable and off the main thread; a GPU jump-flood fill would be faster
but is a great deal more code for a tool used a few times per painting.

```kotlin
@Serializable data class FillParams(
    val tolerance: Float = 0.1f,        // 0..1 of max colour distance
    val contiguous: Boolean = true,     // false = global (every matching pixel)
    val reference: FillReference = FillReference.Composite,
    val expand: Int = 2,                // px of dilation, 0..8
    val antialias: Boolean = true,
    val opacity: Float = 1f,
)
enum class FillReference { CurrentLayer, Composite }

// engine/core, pure JVM
class FloodFill(
    val width: Int, val height: Int,
    val reference: PixelSource,         // (x,y) → premultiplied RGBA int, backed by tile copies
    val params: FillParams,
) {
    /** Returns the coverage mask (0..255 per pixel) of the region, or null if cancelled. */
    fun run(seedX: Int, seedY: Int, progress: (Float) -> Unit, isCancelled: () -> Boolean): Coverage?
}
```

**Algorithm.**

1. *Seed & distance.* Seed colour `C₀ = reference(seed)`. A pixel matches
   when `dist(C, C₀) ≤ tolerance`, with `dist = max(|Δr|, |Δg|, |Δb|,
   |Δa|) / 255` on **un-premultiplied** RGB plus alpha — the Chebyshev
   distance is one compare per channel and behaves like users expect
   ("within N of every channel"). Fully transparent pixels compare equal to
   each other regardless of hidden RGB.
2. *Region.* Contiguous: scanline stack fill (span-based, 4-connected;
   stack of `(y, xLeft, xRight, dy)` spans in an `IntArray` that grows by
   doubling, no boxed objects). Global: one linear pass. Output is a
   1-byte-per-pixel mask `M` over the bounding box only.
3. *Expand.* Dilate `M` by `expand` px using a separable Chebyshev
   dilation (two 1-D max-filters, `O(W·H)` regardless of radius) — a
   square dilation is indistinguishable from a round one at ≤ 8 px and
   costs nothing. This is what removes the halo under anti-aliased line
   art: the fill pushes *under* the soft edge of the line instead of
   stopping at the first grey pixel. Dilation must not leak across a line
   into the neighbouring region, so it respects **walls**, defined by
   *colour*, not alpha (with an opaque colour layer under the line art
   every reference pixel is opaque, so an alpha test would make the first
   grey AA pixel a wall and `expand` would do nothing): a reference pixel
   is a wall when `dist(C, C₀) ≥ wallThreshold` with `wallThreshold =
   max(2 · tolerance, 0.5)` — clearly different, the core of a line. The
   1-D max-filter resets at walls, so expansion fills the AA skirt up to
   the line's core and stops there. A 1 px line whose core never reaches
   `wallThreshold` cannot be a wall; that is an accepted limitation the
   `expand` slider (default 2) keeps small.
4. *Anti-aliased edge.* If `antialias`, the final coverage is
   `M ⊛ box3` (a 3×3 box blur of the binary mask) *inside the bounding
   box grown by 1* — a soft threshold that gives a 1 px ramp at the
   region edge so the fill does not look cut out with scissors on a
   soft-edged region. Walls (as above) keep coverage 0 so the ramp never
   bleeds over line art.
5. *Coverage → pixels.* The coverage `c · opacity` is uploaded as the
   stroke buffer's alpha for the covered tiles and committed through the
   normal merge (`bangni_mix_over` with `k = c · opacity`, 09 §3.1) — so
   fill inherits alpha lock and mixing for free and there is no CPU
   duplicate of the blend. Fill never erases; "fill with transparent" is
   not a v1 feature.

**Reference.** `CurrentLayer` reads the active layer's tiles; `Composite`
reads the CPU `Composite` (PLAN decision 7's reference compositor) over
all *visible* layers **without paper** — transparent stays transparent, so
tolerance semantics match `CurrentLayer` — so you can fill a colour layer
under a line-art layer, which is the actual workflow the tool exists for.
The composite source is computed lazily per tile and cached for the
duration of one fill; the region is unknown up front, so reference tiles
are loaded **progressively**: `PixelSource` faults a tile in the first
time the scanline fill (or the dilation's expanded bbox) touches it. A
tile comes from the CPU mirror when it is there (dirty since last flush,
PLAN §3.1); otherwise it is fetched by GPU readback of the resident slice
(`glFramebufferTextureLayer` + one `glReadPixels` per tile via a PBO on
the GL thread, `03-canvas-engine.md` §10) — chosen over reading and
inflating `.tile` files from `TileStore` because the GPU always holds the
current state and a readback of 64 KiB is cheaper than inflate.

**Performance plan (4096², 16 M px).**

| Step | Cost model | Budget on a 4 GB Tab S6 Lite-class device |
| --- | --- | --- |
| Load reference tiles | progressive, per tile the fill reaches: CPU mirror if present, else one PBO readback per resident slice, N visible layers for `Composite` | ≤ 400 ms full-canvas, 8 layers (256 × 8 readbacks batched per frame); proportional to region otherwise |
| Composite reference (if `Composite`) | per tile, N layers, only tiles the fill reaches | proportional to region |
| Scanline fill | ~1 ns/px best case, region-bounded | ≤ 150 ms full-canvas |
| Dilation + AA | 3 separable passes over bbox | ≤ 100 ms full-canvas |
| Upload coverage + merge | only tiles with coverage > 0; `glTexSubImage3D` into the stroke buffer, then the stroke merge | ≤ 50 ms |

- Runs in `viewModelScope.launch(Dispatchers.Default)`; the ViewModel
  shows a progress pill after 150 ms and a cancel affordance; a new touch
  cancels (the `isCancelled` lambda is polled every 64 scanlines).
- Memory: mask is `bboxW·bboxH` bytes (≤ 16 MB at full canvas) plus the
  span stack; tile copies are the existing mirrors. No `Bitmap` objects.
- Result path: `FloodFill` returns `Coverage(bbox, bytes)`; `FillTool`
  uploads `coverage · opacity` as the stroke buffer's alpha for the
  covered tiles through `execute {}` on the GL thread and calls the normal
  stroke commit — merge, readback, **one** `HistoryEntry` with the covered
  `TileKey`s' before-contents per `06-document-and-persistence.md §5.5`,
  dirty marking for autosave. Undo of a fill is therefore identical to
  undo of a stroke: tile deltas, nothing special.
- Tiles that did not exist (sparse grid) and get coverage are created;
  they appear in the journal as "before = empty".

**Tests** (`FloodFillTest`): tolerance boundaries on a 3-colour fixture;
contiguous vs global; expand closing a 1 px AA gap but not a 2 px opaque
wall; opaque flat colour under an AA line, refilled with expand 2 → no
halo (walls by colour); anti-alias ramp widths; cancellation returning null without partial
application; a 4096² all-one-colour fill completes under a generous CI
bound (correctness test with a timing assertion only as a smoke check).

## 8. Eyedropper

```kotlin
@Serializable data class EyedropperParams(
    val source: SampleSource = SampleSource.Composite,   // or CurrentLayer
    val radius: Int = 0,                                 // 0 = single pixel; 1 = 3×3 average
)
```

**What it samples.** `Composite` by default — what the user *sees*,
including paper colour; the colour panel then shows it un-premultiplied
against paper. `CurrentLayer` samples the active layer only and returns
its un-premultiplied RGBA (alpha is discarded for the brush colour, but
shown in the loupe so "nothing here" is visible). Sampling: the touch is
converted to canvas px through the inverse `ViewTransform`, then the pixel
is read on the **GL side** through `Readback.pixel(x, y, source)`. For
`CurrentLayer` that is `glFramebufferTextureLayer` on the tile's slice +
a 1×1 (or 3×3) `glReadPixels`; for `Composite` it reads the texel from
`Accum`, the viewport-sized composite target of the last frame
(`03-canvas-engine.md` §3.2, paper included), which is exactly what the
user sees. Executed on the
GL thread, delivered to the main thread as an `Int`. Expected < 1 frame
(a synchronous read stalls on queued GPU work; measured in PR 5), and it
does not depend on the CPU mirror being fresh.

**Bindings** (details in `07-input-and-stylus.md`):

| Trigger | Behaviour |
| --- | --- |
| Rail slot "Eyedropper" tap | mode: next touch/pen tap samples; tool reverts to previous after one pick |
| Touch long-press (`LONG_PRESS_MS` = 500 ms, moved < `TAP_SLOP`) in stylus-only mode, or with the Eyedropper rail slot active — see `07-input-and-stylus.md` §3 and §7 | *temporary* eyedropper: loupe appears, drag to refine, release picks, previous tool restored |
| S Pen button held, if `Prefs.penButtonAction = EYEDROPPER` | same temporary mode, active while the button is down |
| Pointer hover + `Alt` (DeX nicety) | temporary mode |

Temporary mode uses the same `ToolSwitcher.pushTemporary(kind)` /
`popTemporary()` pair as the eraser (§9) so "restore previous tool" is
one implementation.

**Loupe.** A Compose overlay (`HoverCursor`'s sibling, `LoupeOverlay`) of a
64 dp circle offset 72 dp above the finger (above-left for right-handed,
above-right for left-handed, from the handedness setting — the finger
must not cover it), split horizontally: top half the *new* colour, bottom
half the *current* brush colour, with a thin ring of the 5×5 pixel
neighbourhood magnified 8× in the centre so single-pixel precision is
possible on line art. The colour is applied live while dragging (the
rail's colour dot updates), committed on release, and a cancel (second
finger / `ACTION_CANCEL`) restores the previous colour. Haptic tick on
pick.

## 9. Eraser specifics and tool switching

```kotlin
class ToolSwitcher {
    val current: StateFlow<ToolKind>
    fun select(kind: ToolKind)                   // user choice from the rail; clears the temporary stack
    fun pushTemporary(kind: ToolKind, reason: TemporaryReason)
    fun popTemporary(reason: TemporaryReason)   // restores what was current before the push; no-op if not on top
}
enum class TemporaryReason { EraserEnd, PenButton, LongPress, Hover }
```

- **S Pen eraser end.** When a stroke begins with `TOOL_TYPE_ERASER`,
  `CanvasTouchHandler` calls `pushTemporary(hardEraser, EraserEnd)` before
  `onStrokeBegin` and `popTemporary(EraserEnd)` after `onStrokeEnd`/
  `onStrokeCancel`. Hover with the eraser end (`ACTION_HOVER_*` with
  `TOOL_TYPE_ERASER`) pushes on enter and pops on exit so the hover
  cursor already shows the eraser's size. Which eraser preset the eraser
  end maps to is `Prefs.eraserEndPreset` (default hard eraser); the rail
  shows the temporary tool highlighted with a distinct "temporary" ring so
  the user sees why the pencil is erasing.
- **S Pen side button.** `BUTTON_STYLUS_PRIMARY` (or `BUTTON_SECONDARY` on
  older Samsung firmware) pressed → `pushTemporary(Prefs.penButtonAction,
  PenButton)` where the setting is `ERASER` (default), `EYEDROPPER` or
  `NONE` (`07-input-and-stylus.md` §6); released
  → pop. The button state is read on every `ACTION_DOWN`/`MOVE`, and the
  switch is **not** allowed to change tools mid-stroke: if the button
  changes state while a stroke is live, the change takes effect at the next
  `ACTION_DOWN` (switching the merge mode of a stroke in flight would
  produce a stroke that is half paint, half hole).
- **Restore semantics.** The stack is a stack, not a flag: eraser end while
  the button is held pops in the right order. `select()` from the rail
  while a temporary is active replaces the *base* tool and keeps the
  temporary on top, so tapping "Ink pen" during a button-hold means "when I
  let go, I want the pen".
- **Erase on empty / alpha-locked layer** is a no-op with a one-line
  snackbar the first time per session.

## 10. Post-v1 tools — design sketches and the hooks we leave now

Each becomes a `docs/proposals/` entry before it is built; the point of
listing them here is to shape v1 interfaces so they slot in without
rework.

- **Selection (lasso / rect) + transform.** A selection is a single-channel
  8-bit coverage mask at canvas resolution stored as sparse tiles like a
  layer (`SelectionMask`, same `TileGrid`). Every pixel-writing path takes
  it as an input today: `StrokeContext.selection` (v1: `SelectionMask.All`,
  a sentinel that `DabPass`/`SmudgePass` skip binding), `FloodFill`'s
  coverage is multiplied by it, and the merge step samples it as one more
  texture. Transform = float a "floating layer" (copy of selected pixels,
  cleared from source) through a `ViewTransform`-style affine, resampled
  bilinearly on commit; journaled as two tile deltas (source, destination).
  The marching-ants outline is a Compose overlay from the mask's tile
  boundaries, not a GL pass.
- **Straight-line / shape assist.** A `StrokeInput` *filter* between the
  stabilizer and the dab generator (`StrokeFilter` interface, v1 has the
  identity in that slot): line mode projects samples onto the line from
  the first sample to the current one, re-emitting the whole stroke each
  frame (the stroke buffer is cleared and re-stamped — cheap because dabs
  are GPU stamps); ellipse/rect fit similarly. Rulers are the same filter
  with a persistent guide.
- **Symmetry.** Also a `StrokeFilter`, but a *fan-out*: one input sample →
  N mirrored samples with mirrored orientation/tilt azimuth, all into the
  same stroke buffer (so overlapping mirrored strokes still respect
  opacity). `DabBatch` already carries per-dab angle, so mirrored flat tips
  are free. The v1 `DabGenerator` must therefore be instantiable per
  branch (it is: one object, one `carry`).
- **Gradient fill.** `FillTool` with a `GradientSource` instead of a solid
  colour: the coverage mask stays identical, only step 5 changes to
  evaluate a linear/radial gradient at `(x, y)` — with Mixbox interpolation
  between stops (`mixbox_lerp` in the CPU apply). `FillParams` gets a
  `paint: FillPaint = Solid` field, defaulted, so v1 JSON stays valid.
- **Hue jitter / "dirty brush".** `Jitter.hue` (± degrees, per dab)
  needs the stroke buffer to carry premultiplied colour instead of
  coverage only; the merge then reads `buffer.rgb` in place of `c_p` and
  `bangni_mix_over` is unchanged otherwise. The `Max` buffer mode cannot
  carry colour, so such presets are `Accumulate` only. Post-v1 because it
  breaks the "one latent round trip per stroke" property of 09 §3.1.
- **Grains.** `BrushPreset.grain` (already present, `null`) names a
  tileable greyscale texture in `assets/grains/` (public domain / CC0
  only, provenance in AGENTS.md). `DabPass` multiplies dab alpha by
  `texture(grain, canvasXY / grainScale)` — canvas-anchored, not
  dab-anchored, so overlapping dabs reinforce the same paper texture like
  real graphite. One extra sampler and two uniforms in the shader
  contract test.
- **Import image as layer.** Photo picker → decode → tiles on a new paint
  layer through the same upload path as fill. Tracing references shipped in
  roadmap step 11; they are separate private assets beneath every paint layer.

Common thread: every future tool is either a `BrushPreset` field with a
default, a `StrokeFilter` in the one slot v1 reserves, or a new `ToolKind`
sub-type — the sealed interface, the selection input and the filter slot
are the three hooks v1 must leave in place.
