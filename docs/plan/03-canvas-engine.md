# Canvas engine

**What this document covers.** The pixel engine behind the Canvas screen:
how a painting is laid out as tiles, how those tiles live on the GPU, how
layers are composited onto the screen, how a pen stroke becomes pixels, and
how pixels get back to the CPU for undo and autosave. It expands
[PLAN.md](../../PLAN.md) §3.1 and decision 1 (ADR 0001); it does not
restate them. Tool parameters and per-tool math are in
`docs/plan/04-tools.md`; the layer model and blend-mode semantics are owned
by `docs/plan/05-layers.md`; the on-disk tile format and the journal by
`docs/plan/06-document-and-persistence.md`; input by
`docs/plan/07-input-and-stylus.md`; budgets and targets by
`docs/plan/10-performance.md`. Everything here lives in
`ch.lkmc.bangnidraw.engine.core` (pure JVM, tested) and `engine.gl` (thin
GL). Where a name appears in PLAN.md §3's package tree it is the same class.

## 1. Coordinate system and TileGrid

**Canvas space** is the document's pixel grid: origin top-left, +x right, +y
down, one unit = one canvas pixel, pixel centers at `+0.5`. Every
`StrokeInput`, `Dab`, dirty rect and tile key is in canvas space. The only
other spaces are *view space* (window pixels, also y-down) and, inside the
GL code, tile-local texel space. Nothing in `engine/core` knows about the
screen.

Tiles are **256×256** (`TileGrid.TILE = 256`, `TILE_SHIFT = 8`). Why 256:
a tile is 256 KiB in RGBA8, small enough that a single dab dirties few
tiles and a stroke's undo delta stays small, large enough that a 4096²
canvas is only 256 tiles per layer (one index lookup per 65 536 pixels, one
FBO bind per tile per dab batch). 128 doubles bind churn for no memory win;
512 quadruples the cost of the smallest edit (a 1 MiB delta for one dot).

```kotlin
@JvmInline value class TileKey(val packed: Int) {          // (ty << 16) | tx
    constructor(tx: Int, ty: Int) : this((ty shl 16) or tx)
    val tx: Int get() = packed and 0xFFFF
    val ty: Int get() = packed ushr 16
}

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) // half-open

class TileGrid(val width: Int, val height: Int) {
    val tilesX = (width  + TILE - 1) shr TILE_SHIFT
    val tilesY = (height + TILE - 1) shr TILE_SHIFT
    val tileCount get() = tilesX * tilesY
    fun keyAt(x: Int, y: Int) = TileKey(x shr TILE_SHIFT, y shr TILE_SHIFT)
    /** Tile origin in canvas px. */
    fun origin(k: TileKey) = IntPoint(k.tx shl TILE_SHIFT, k.ty shl TILE_SHIFT)
    /** Keys touched by a half-open rect, clipped to the canvas; empty for empty/outside rects. */
    fun keysFor(r: IntRect, out: MutableList<TileKey>) {
        val l = maxOf(r.left, 0);  val t = maxOf(r.top, 0)
        val rr = minOf(r.right, width); val b = minOf(r.bottom, height)
        if (l >= rr || t >= b) return
        for (ty in (t shr TILE_SHIFT)..((b - 1) shr TILE_SHIFT))
            for (tx in (l shr TILE_SHIFT)..((rr - 1) shr TILE_SHIFT))
                out += TileKey(tx, ty)
    }
    fun index(k: TileKey) = k.ty * tilesX + k.tx                // dense row-major
}
```

Dirty rects are **half-open** (`right`/`bottom` exclusive), integer, always
clipped to the canvas before being turned into keys; a dab of radius `r` at
`(x, y)` dirties `floor(x-r-1) .. ceil(x+r+1)` (the extra pixel is the
anti-aliasing band, §7.3). `keysFor` is the function PLAN.md §7 names
("dirty-rect → tile keys") and its tests cover: rect fully outside, rect
straddling the canvas edge, a 1-px rect on a tile corner (one key), a rect
exactly on a boundary (`right = 256` gives one key, `right = 257` two).

**Edge tiles** are partial. The tile stores 256×256 texels regardless; the
texels outside the canvas are kept transparent by scissoring every pass to
the canvas rect, and the compositor never samples them (its quads are
clipped to the canvas). Persistence writes full tiles; the transparent
remainder deflates to nothing.

**Canvas size limits.** Per side `256 ≤ w, h ≤ 8192` and
`tilesX × tilesY ≤ 1024` (`CanvasPresets.MAX_TILES`), i.e. the largest
canvas is 8192² — that is the *format* ceiling; v1 offers at most
`MAX_CANVAS_EDGE_V1` = 4096 per side (`docs/plan/10-performance.md` §4,
8192 needs tile eviction) and `MemoryBudget` lowers `maxCanvasEdge` further
per device (decision 4, `docs/plan/05-layers.md` §6). The reasons
for 8192 are all in this document: the pool is addressed by 16-bit tile
coordinates, the readback chunking and the sandwich rebuild are sized for
≤1024 tiles per layer, and a layer at 8192² fully painted is 256 MiB.

## 2. The tile pool on the GPU

### 2.1 Pages, slices, handles

`TilePool` owns a small number of **pages**. A page is one
`GL_TEXTURE_2D_ARRAY` created with `glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1,
GL_RGBA8, 256, 256, slicesPerPage)`; each slice holds one tile. Every tile of
every layer, the two sandwich caches, the stroke buffer and the RMW scratch
slices come from the same pool.

`slicesPerPage = min(GL_MAX_ARRAY_TEXTURE_LAYERS, 256)`. The spec minimum
is 256 and we query at startup (§13); we do not use more than 256 even
where the driver allows 2048 because a page is the allocation granule
(64 MiB at 256 slices) and the first page must not be bigger than a
phone-sized painting needs. Pages are created lazily when the free list
runs dry and never destroyed while the document is open (freeing a page
while the driver may still be defragmenting is a known source of jank;
destroying happens on document close).

```kotlin
@JvmInline value class SliceHandle(val packed: Int) {      // (page << 16) | slice, -1 = none
    val page:  Int get() = packed ushr 16
    val slice: Int get() = packed and 0xFFFF
    companion object { val NONE = SliceHandle(-1) }
}

class TilePool(private val caps: GlCaps, private val budget: MemoryBudget) {
    private val pages = ArrayList<Page>()          // Page(texId: Int, free: IntArray stack, freeCount: Int)
    fun allocate(): SliceHandle                    // pops a free slice; new page if needed; throws PoolExhausted
    fun free(h: SliceHandle)                       // pushes back; slice contents are garbage until cleared
    fun allocateCleared(): SliceHandle             // allocate + glClear of that slice (bind as FBO layer)
    /** allocate, but never from any of [pages]; creates a fresh page if every existing one is excluded. */
    fun allocateNotOn(vararg pages: Int): SliceHandle
    fun textureOf(page: Int): Int
    val residentBytes: Long get() = pages.size * slicesPerPage * TILE_BYTES
}
```

**No sampling from the render-target page.** ES 3.0 makes rendering
undefined when a fragment shader samples the texture object bound as the
draw attachment — the rule is per texture *object and level*, not per
slice, so rendering into slice 7 of a page while sampling slice 3 of the
same page is a feedback loop even though the texels never overlap. Every
read-modify pass that reads pool slices (`MergePass`, the sandwich
rebuild, `SmudgePass`) therefore takes its render-target slice via
`allocateNotOn(pages it samples)`: the target lives on a page that is not
bound as a sampler in that pass, and a second page is created if only one
exists (64 MiB, once, on the first stroke — paid from
`Result.gpuTileBudgetBytes` like every page). The swap-handles trick of §7.4 keeps working because a
`SliceHandle` carries its page. If a driver still misbehaves, the fallback
is `glCopyTexSubImage3D` of the source slice into a private, non-pool
page before the pass (`GlCaps.forceCopyBeforeRmw`, off by default).

`allocate()` failing is an ordinary outcome, not a crash: `MemoryBudget`
already sized the layer cap so that a fully painted document fits, plus
the sandwich, one stroke buffer and scratch; if `glTexStorage3D` still
returns `GL_OUT_OF_MEMORY` (or the pool passes `Result.gpuTileBudgetBytes`),
the operation that needed the tile is refused — a stroke is cancelled with
a one-line notice, a layer add is refused with the budget readout (decision
4: honest, never silent).

### 2.2 The per-layer index

A layer's `LayerTextures` is a dense `IntArray(tilesX × tilesY)` of packed
`SliceHandle`s, `-1` where nothing was ever painted. Dense because it is
tiny (4 KiB at 1024 tiles) and O(1) to look up; sparse *content* is
expressed by the `-1`s, not by the index structure. A tile is created (and
cleared) the first time a dab batch, merge, fill or upload touches its key;
it is never freed while the layer exists (a fully erased tile stays
allocated — post-v1 residency work may reclaim all-transparent tiles after
readback proves them empty).

### 2.3 Why this and not the alternatives

| Option | Rejected because |
| --- | --- |
| One big texture per layer | Memory ∝ canvas size, not painted area: eight 4096² layers = 512 MiB the moment they are created, even empty. `GL_MAX_TEXTURE_SIZE` may be 4096 on a real API-29 GPU, which makes an 8192 canvas impossible outright. Every dirty-tile operation (readback, undo restore) would still need sub-rect addressing. |
| One `GL_TEXTURE_2D` per tile | Thousands of GL objects (8 layers × 256 tiles = 2048 textures), one `glBindTexture` per quad in the compositor, no batching. Driver object tables on mobile GPUs are not built for that. |
| Texture arrays as pages (chosen) | One bind per page per pass; quads for all tiles of a page batch into one draw; slices are allocated and freed like a heap; a slice is render-to-able via `glFramebufferTextureLayer`; memory grows in 64 MiB steps as the painting grows. |

### 2.4 Pixel format: RGBA8 premultiplied, everywhere

Every slice, the screen accumulation target and every CPU tile copy are
**RGBA8, premultiplied alpha, non-linear sRGB values**. Premultiplied
because source-over is then a single multiply-add (`ONE,
ONE_MINUS_SRC_ALPHA`), filtering is correct at transparent edges, and
Android's `Bitmap` is premultiplied too, so export is a straight copy.
8 bits because half-float render targets are optional extensions in ES 3.0
(`EXT_color_buffer_half_float`) and we target the ES 3.0 baseline with no
per-device format branching; the cost is quantization in very low-flow
strokes (an airbrush at flow 0.02 needs 2 dabs before the first LSB moves),
which `DabGenerator` mitigates by flooring the per-dab alpha at 1/255 when
flow > 0 (`docs/plan/04-tools.md` §5, Airbrush). Blending happens in sRGB
space, not linear: that is what every painting app the target users know
does, it is what Mixbox's "RGB in, RGB out" expects, and a linear pipeline
in 8 bits would band in the darks. No `GL_SRGB8_ALPHA8` anywhere.

`TILE_BYTES = 256 × 256 × 4 = 262 144` (256 KiB).

## 3. The compositor

### 3.1 Screen transform

The screen shows `view ∘ fit` of canvas space. `FitTransform` (ported from
Meltorama, verbatim) fits the canvas into the viewport; `ViewTransform`
(same, verbatim, with `MIN_SCALE = 0.25f`/`MAX_SCALE = 64f` relative to
fit — the painting-app re-tune decided in `docs/plan/07-input-and-stylus.md`
§7 and shipped with the scaffold) is the user's pan/zoom/rotate on top of
it. Both are similarities, so their composition is one similarity, computed
on the JVM once per frame and handed to the vertex shader as four floats:

```
fit(p)  = fs·p + o                 (fs = fit.scale, o = fit.offset)
view(q) = [a −b; b a]·q + t        (a = s·cosθ, b = s·sinθ, t = (tx, ty))
screen(p) = view(fit(p)) = [a' −b'; b' a']·p + t'
  a' = a·fs,  b' = b·fs,  t' = view.apply(o)
effectiveScale = fs·s               (screen px per canvas px)
```

`ScreenTransform(a', b', tx', ty', effectiveScale)` is a pure `engine/core`
value; `invert` is `ViewTransform.invert` applied then `FitTransform`
undone, and is what `CanvasTouchHandler` uses to map a `MotionEvent` to
canvas space.

Composite vertex shader — one quad per tile, corners in canvas px:

```glsl
#version 300 es
layout(location = 0) in vec2 a_canvas;      // tile corner, canvas px (clipped to the canvas rect)
layout(location = 1) in vec3 a_uvw;         // texel uv inside the tile (0..1), slice index
uniform vec4 u_screen;                      // a', b', tx', ty'
uniform vec2 u_viewport;                    // target size in px: Accum's size for offscreen passes, bufferInfo.width/height for the window pass
uniform mat4 u_projection;                  // ortho over u_viewport (y-down), applied AFTER u_bufferTransform
uniform mat4 u_bufferTransform;             // graphics-core pre-rotation, applied in buffer pixel space; identity offscreen
out vec3 v_uvw;
out vec2 v_canvas;
void main() {
    vec2 p = vec2(u_screen.x * a_canvas.x - u_screen.y * a_canvas.y + u_screen.z,
                  u_screen.y * a_canvas.x + u_screen.x * a_canvas.y + u_screen.w);
    gl_Position = u_projection * u_bufferTransform * vec4(p, 0.0, 1.0);   // projection × transform × pixelPos
    v_uvw = a_uvw;
    v_canvas = a_canvas;
}
```

Order of the matrices: graphics-core's `transform` is a 16-float 4×4 matrix
meant to be applied in **buffer pixel space**, *before* an orthographic
projection of `bufferInfo.width × bufferInfo.height` (the library's KDoc:
buffers are pre-rotated in advance and the transform "should be consumed
as input to any vertex shader"). So the shader multiplies
`projection × transform × pixelPos`; `u_projection` is built on the JVM as
`ortho(0, u_viewport.x, u_viewport.y, 0)` — y-down, which is where the
y-up-ness of GL is absorbed (see the row convention below).

Row convention: **texture row 0 is the canvas's top row** (tiles are stored
"y-down", exactly like the CPU copies and like `glReadPixels` returns them,
so no flips anywhere); the y-down ortho `u_projection` is the only place
the y-up-ness of GL appears.

### 3.2 Passes and the accumulation target

A fragment shader cannot read the framebuffer it writes, and the blend
modes other than Normal need the backdrop. The compositor therefore renders
into an offscreen, viewport-sized RGBA8 texture **`Accum`** (allocated once
per surface size, ≈16 MiB at 2560×1600) and finally draws the dirty rect
into the window buffer as a textured quad (step 3 — not a blit: the buffer
may be pre-rotated, §8.5). Per frame, in order, all scissored to the dirty
rect in window space (steps 1–2) or in buffer space (step 3):

1. **Paper.** Clear `Accum` to the paper color (premultiplied, opaque). If
   the paper is transparent, draw a full-rect quad with the checkerboard
   shader instead: 8 dp squares in **screen** space (canvas-space squares
   would shrink to noise when zoomed out and become slabs when zoomed in).
   When the sandwich is in use (§4) the paper is already baked into
   `Below`, so this step clears `Accum` to transparent (opaque paper) or
   draws the checkerboard (transparent paper) and `Below` is drawn Normal
   over it; the paper is painted here only on the per-layer path (§12
   step 3, sandwich unavailable).
2. **Layers bottom to top.** For each visible layer (or cache, §4): if its
   blend mode is Normal, draw its tile quads with GL blending
   `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` — hardware source-over,
   no backdrop read. Otherwise `glBlitFramebuffer` the dirty rect from
   `Accum` into `Scratch` (a second viewport-sized texture), bind `Scratch`
   as `u_backdrop`, disable blending, and draw the quads with the blend
   formula in the shader (the quads write the full composite, and where
   the layer has no tile `Accum` is simply left as it was).
3. **Present** `Accum` → the current window buffer (front layer or
   multi-buffered buffer): `glBindFramebuffer(GL_FRAMEBUFFER,
   bufferInfo.frameBufferId)` (the callbacks' own target, which our
   per-tile FBO binds have replaced by now), `glViewport(0, 0,
   bufferInfo.width, bufferInfo.height)`, scissor = the dirty rect mapped
   through `transform` (§8.1 step 3), and a full-rect textured quad of
   `Accum` drawn with the §3.1 vertex shader — `u_screen` identity,
   `u_viewport = (bufferInfo.width, bufferInfo.height)`, `u_bufferTransform
   = transform`. A `glBlitFramebuffer` cannot rotate: when graphics-core
   hands a pre-rotated buffer (`bufferInfo.width/height` swapped relative
   to the viewport), a same-size blit of the viewport-oriented `Accum` is
   wrong or out of bounds. `glBlitFramebuffer` is used only for
   `Accum → Scratch` (step 2), where both sides are viewport-oriented.

Quads are **batched by page**: `CompositePass.draw(layerTextures, mode,
opacity)` sorts the visible tile keys of the layer by page, uploads one
`glBufferSubData` of quads per page into a persistent streaming VBO, and
issues one `glDrawArrays` per page with that page's array texture bound.
Visible keys = `TileGrid.keysFor(inverse-transformed dirty rect ∪ its
rotated bounding box)`. A layer whose index has no slice in that set costs
one CPU loop and no draw call. Cost is therefore bounded by output pixels ×
layers, never by canvas size — a 8192² canvas with 20 tiles painted
composites as fast as a 512² one.

`EXT_shader_framebuffer_fetch` would remove the blit-to-scratch for
non-normal layers; it is an optional optimization once the capability
probe reports it (to verify per device; off by default in v1).

### 3.3 Composite fragment shader

Blend modes are those of `docs/plan/05-layers.md` — Normal, Multiply,
Screen, Overlay, Darken, Lighten, Add, Difference — in premultiplied form.
`Composite.kt` (CPU reference, `engine/core`) implements the identical
formulas on `Int` pixels and is the pinned oracle for
`GlShaderContractTest` and the blend-mode unit tests (PLAN.md §7).

```glsl
#version 300 es
precision highp float;
precision highp sampler2DArray;
uniform sampler2DArray u_tiles;         // the page of the current batch
uniform sampler2D      u_backdrop;      // Scratch copy of Accum; bound only when u_blend != 0
uniform int   u_blend;                  // BlendMode.shaderId: 0 normal 1 multiply 2 screen 3 overlay 4 darken 5 lighten 6 add 7 difference (05 §4)
uniform float u_opacity;                // layer opacity 0..1
uniform int   u_taps;                   // 1 = hardware filter, 2 = 2x2, 4 = 4x4 supersample (see §3.4)
uniform vec2  u_canvasPerScreen;        // canvas px per screen px along x and y (for supersampling offsets)
in vec3 v_uvw;
in vec2 v_canvas;
out vec4 o_color;

vec4 fetchTile(vec3 uvw) { return texture(u_tiles, uvw); }

vec4 sampleLayer() {
    if (u_taps == 1) return fetchTile(v_uvw);
    // Box filter over a u_taps × u_taps grid spanning one screen pixel, in canvas px.
    vec4 acc = vec4(0.0);
    float n = float(u_taps);
    for (int j = 0; j < 4; j++) {
        if (j >= u_taps) break;
        for (int i = 0; i < 4; i++) {
            if (i >= u_taps) break;
            vec2 off = ((vec2(float(i), float(j)) + 0.5) / n - 0.5) * u_canvasPerScreen; // canvas px
            acc += fetchTile(vec3(v_uvw.xy + off / 256.0, v_uvw.z));
        }
    }
    return acc / (n * n);
}

// Straight-alpha separable blend functions B(Cb, Cs), per channel.
vec3 blendStraight(int mode, vec3 cb, vec3 cs) {
    switch (mode) {
        case 1: return cb * cs;                                             // multiply
        case 2: return cb + cs - cb * cs;                                   // screen
        case 3: return mix(2.0 * cs * cb,                                   // overlay = hardlight(cs, cb) swapped
                           1.0 - 2.0 * (1.0 - cs) * (1.0 - cb),
                           step(0.5, cb));
        case 4: return min(cb, cs);                                         // darken
        case 5: return max(cb, cs);                                         // lighten
        case 6: return min(cb + cs, vec3(1.0));                             // add (linear dodge)
        case 7: return abs(cb - cs);                                        // difference
        default: return cs;                                                 // normal (unused: hardware path)
    }
}

void main() {
    vec4 s = sampleLayer() * u_opacity;                                     // premultiplied source
    if (u_blend == 0) { o_color = s; return; }                              // GL blend ONE, ONE_MINUS_SRC_ALPHA
    vec4 b = texelFetch(u_backdrop, ivec2(gl_FragCoord.xy), 0);             // premultiplied backdrop
    vec3 cb = b.a > 0.0 ? b.rgb / b.a : vec3(0.0);
    vec3 cs = s.a > 0.0 ? s.rgb / s.a : vec3(0.0);
    vec3 f  = blendStraight(u_blend, cb, cs);
    // W3C compositing, premultiplied: co = cs'(1-αb) + cb'(1-αs) + αs·αb·B(Cb,Cs)
    vec3  co = s.rgb * (1.0 - b.a) + b.rgb * (1.0 - s.a) + s.a * b.a * f;
    float ao = s.a + b.a * (1.0 - s.a);
    o_color = vec4(co, ao);                                                 // blending disabled: overwrites Accum
}
```

This is `docs/plan/05-layers.md` §4's `blendLayer(s, d, mode)` written in
straight-colour form — 05's table (the normative one) and the code above are
algebraically identical, and `u_blend` carries `BlendMode.shaderId`.
Properties the tests pin: `u_blend == 0` with the hardware blend equals the
formula with `B = Cs`; every mode with `s.a == 0` returns the backdrop
unchanged; every mode over an opaque backdrop with an opaque source returns
`B` exactly; Add over white stays white.

### 3.4 Filtering and zoom

| effectiveScale | Sampler | Why |
| --- | --- | --- |
| ≥ 4.0 | `GL_NEAREST` | Pixels are ≥4 screen px wide; users zoom this far to see and place individual pixels, and bilinear turns a pixel grid into mush. 400 % is the conventional threshold in raster editors. |
| 0.5 – 4.0 | `GL_LINEAR` | Bilinear is exact-enough for magnification and for minification down to 2 canvas px per screen px. |
| 0.25 – 0.5 | `GL_LINEAR`, `u_taps = 2` | 2×2 bilinear taps cover a 4×4 texel footprint: no shimmer while panning a zoomed-out painting. |
| < 0.25 | `GL_LINEAR`, `u_taps = 4` | 4×4 taps, 8×8 footprint. Below 0.125 (an 8192 canvas on a phone at half fit) residual aliasing is accepted. |

**No mipmaps.** Decision and justification: a mip chain on a texture array
page would have to be regenerated for the whole page (`glGenerateMipmap`
works per texture, not per slice) after every stroke, or maintained per
dirty slice by our own downsample passes; both cost more per stroke than
the supersampling costs per frame, and zoomed-out frames are rare
(gestures) while strokes are constant. Supersampling is also exact with
respect to the sparse index (a mip of a tile cannot see its neighbors).
The 4×4 case costs 16 fetches × 3 sandwich passes per screen pixel — at
2560×1600 that is ≈200 M fetches for a full-viewport redraw, tens of
milliseconds on a mid-range Mali; acceptable for a gesture frame (measured,
not assumed: `docs/plan/10-performance.md` §2.3). If it is not, the
escalation path is a per-layer half-resolution "overview" grid updated
from the readback, not mipmaps.

**Tile seams.** Bilinear sampling at a tile's border clamps to that tile's
edge texel instead of reaching into the neighbor. The error is confined to
a half-texel band along tile boundaries and at most half a texel of
interpolation shift; at ≥4× we use nearest, and at 0.5–4× the band is ≤2
screen px on content that is continuous across the border anyway. v1
accepts this. The escalation path (if it ever shows on a real device) is
the "index texture" compositor: a per-layer `R16UI` texture mapping
`(tx,ty)` → slice and manual bilinear `texelFetch` that crosses tile
borders through a second index lookup; it also removes per-tile quads. It
is more shader, not more memory, so it can be swapped in later without
touching the pool or the document.

`u_canvasPerScreen` and the sampler choice are set once per frame from
`ScreenTransform.effectiveScale`; `glTexParameteri` is called on each page
only when the filter changes (a state cache in `CompositePass`).

## 4. The sandwich cache

For an active layer *k* in a stack of *N*, `SandwichCache` keeps two
canvas-space tile grids in the pool, each shaped like a layer:

- **Below** = the paper (opaque color, or transparent when the paper is
  transparent) ⊕ layers `0 .. k-1` composited with their blend modes and
  opacities, in canvas space. The paper **is** baked in: with §3.3's
  formula a non-Normal layer over a transparent backdrop degenerates to
  plain source-over (`b.a = 0` ⇒ `co = cs`), so a paper-less Below drawn
  over the paper would show a Multiply layer 0 as Normal and diverge from
  the direct composite and from flatten. Baking the paper makes Below
  exact; the price is a bounded canvas-sized rebuild on the rare paper
  color change (table below). A transparent paper composites over
  transparent, which is what the checkerboard (§3.2) implies anyway.
- **Above** = layers `k+1 .. N-1` composited over transparent **only if
  every one of them is Normal**. Source-over is associative, so
  `above OVER (active BLEND below)` is exact. Multiply/Screen/… are not
  associative with respect to the backdrop, so if any layer above the
  active one has a non-normal mode, `Above` is marked `unavailable` and
  those layers are composited individually per frame, bottom to top, with
  their own modes (K extra passes, K = number of layers above). The layer
  panel does not hide this: `docs/plan/05-layers.md` §4 notes
  the cost, and it only ever bites when painting *under* a multiply layer.

A frame while a stroke is live is therefore `Below (paper baked in) →
(active ⊕ stroke buffer) → Above`: **three layer passes** whatever N is
(plus K when Above is unavailable). That is the whole point: the live path's cost does
not grow with the layer count, and layer count is what large paintings
have.

**Building a cache** is canvas-space, tile by tile: for each key present
in any contributing layer (for Below: every key of the canvas, since the
paper covers it all — an opaque paper makes every Below tile present),
ping-pong between two reserved scratch slices
(`SandwichCache.scratchA/B`, each allocated with `allocateNotOn` against
the other's page and the contributing layers' pages, §2.1), one composite
pass per contributing layer
(the same fragment shader as §3.3, drawn as a single 256×256 quad with an
identity "screen" transform), then the result *is* the cache tile (the
last pass writes straight into the cache's slice). Cost = present tiles ×
contributing layers × 64 K px; for 7 other layers fully painted at 4096²
that is ≈117 M px, tens of milliseconds, done once at layer switch.

**Invalidation rules** (`SandwichCache.invalidate(reason)`):

| Event | Below | Above |
| --- | --- | --- |
| Pixel edit on the active layer (stroke merge, fill, RMW, undo of those) | — | — |
| Pixel edit on layer *j* < *k* (undo/redo restoring a tile there) | rebuild affected tiles | — |
| Pixel edit on layer *j* > *k* | — | rebuild affected tiles |
| Active layer changes | rebuild all | rebuild all |
| Opacity / blend / visibility / alpha-lock of the active layer | — (applied per frame) | — |
| Property change of a layer below / above | rebuild all of that half | |
| Paper color change (opaque ↔ transparent included) | rebuild all (all tiles: the paper is baked in) | — |
| Add / delete / duplicate / move / merge / flatten / select | per operation — `docs/plan/05-layers.md` §8 is the normative table (e.g. `add` stales only Below because the new active layer is empty) | |
| Canvas closed / context lost | drop | drop |

"Rebuild affected tiles" means the keys of the edit's dirty rect only
(undo restores tiles, so the rect is known). "Rebuild all" marks that half
*stale*; stale tiles are rebuilt lazily on the GL thread when they are next
drawn, viewport first (plus `SANDWICH_MARGIN_PX`, `docs/plan/10-performance.md`
§2.6), so the whole-cache cases cost at most one extra composite of the
visible tiles on user actions that already expect a beat (switching
layers), never during a stroke.

The active layer is never cached: it is drawn from its own tiles, merged
with the stroke buffer in the shader (§7.5), so erasers, alpha-lock,
blend modes and pigment mixing all preview exactly what commit will
produce.

## 5. Render-on-demand

There is **no render loop**. The GL thread draws exactly when one of these
asks it to:

| Trigger | What is drawn | Path |
| --- | --- | --- |
| Input batch while a stroke is live | dirty rect (dabs ∪ previous predicted tail ∪ new tail) | front layer (§8) |
| Pen-up / `ACTION_CANCEL` | full viewport from committed state | multi-buffered via `commit()` / `cancel()` |
| View gesture step, view reset spring, surface resize | full viewport | multi-buffered (§8.6) |
| Layer edit, undo/redo, fill, property change | full viewport (after cache invalidation) | multi-buffered |
| Thumbnail / gallery flatten request | offscreen (§10.4) | `execute {}` |

A continuous loop would cost battery on a device that spends most of its
time with the pen hovering, and would compete with the front-buffered path
for the GPU. Every draw is a consequence of an event and carries the dirty
rect that justifies it.

## 6. The stroke pipeline: input to dabs

```
MotionEvent (+historical, +predicted)
  → CanvasTouchHandler: ScreenTransform.invert → StrokeInput samples   (main thread, no allocation)
  → Stabilizer                                                          (engine/core)
  → DabGenerator: spacing, dynamics → Dab batch into DabRing            (engine/core)
  → GL thread: DabPass stamps the batch into the StrokeBuffer          (engine/gl)
  → front-layer recomposite of the dirty rect                          (engine/gl)
pen-up → merge StrokeBuffer into the active layer → commit → readback → journal + autosave
```

```kotlin
class StrokeInput {                 // one sample; mutable, pooled in a StrokeInputBatch — the format is 07 §2
    var x = 0f; var y = 0f          // canvas px
    var pressure = 0f               // 0..1 after PressureCurve
    var tilt = 0f                   // radians, 0 = perpendicular
    var orientation = 0f            // radians, canvas-relative
    var timeNs = 0L
    var source = StrokeSource.STYLUS  // STYLUS, ERASER_END, FINGER, MOUSE
    var predicted = false           // §9: never enters the stroke buffer
}

enum class StrokeMode { PAINT, ERASE, MIX }          // MIX = PAINT with pigment mixing at merge

class StrokeSpec(                   // fixed for the whole stroke
    val layerId: LayerId,
    val mode: StrokeMode,
    val opacity: Float,             // the stroke's ceiling, applied at merge
    val alphaLock: Boolean,         // from the layer
    val rmw: RmwKind?,              // SMUDGE / BLUR / null; RMW strokes bypass the stroke buffer (§7.6)
)

/** A dab is a slot in a DabBatch (SoA FloatArrays, 02 §3.2); the eight per-dab fields are
 *  x, y, radius, flow, hardness, angle, aspect, seed = DAB_STRIDE (10 §4). Colour and the
 *  stroke opacity are per stroke (uniforms), never per dab. No Dab objects exist at runtime. */
```

`Dab` in PLAN.md §3's tree is this layout plus a tiny `data class Dab` used
only in tests and presets. `DabGenerator` walks the stabilized polyline,
emitting a dab every `spacing × radius` canvas px of arc length, carrying a
remainder across samples so spacing is invariant under sample rate and
zoom (PLAN.md §7: "spacing invariant under resolution"). Dynamics
(pressure → size/flow/opacity curves, tilt, velocity, jitter) are
`docs/plan/04-tools.md`'s; the generator only knows `BrushPreset`.

`DabRing` is the preallocated single-producer/single-consumer ring of
`DabBatch` slots defined in `docs/plan/02-architecture.md` §3.2
(`DAB_RING_SLOTS` × `DAB_BATCH_CAPACITY` = 8 × 1024 dabs ≈ 256 KiB,
`docs/plan/10-performance.md` §4) between the main thread and the GL
thread. The main thread fills a batch (dabs plus the header: count, dirty
rect, stroke id, `predictedFrom`) and publishes it as the `param` of
`renderFrontBufferedLayer`; the GL callback consumes it and any batch
published behind it (§11). If no slot is free the producer keeps the samples
and coalesces them into the next batch — nothing is dropped, only delayed by
a frame (02 §3.5; never happens at realistic rates: 8 K dabs is seconds of
airbrush).

## 7. Dabs, the stroke buffer, and merging

### 7.1 The stroke buffer

`StrokeBuffer` is a tile grid at the layer's resolution (the canvas's) with
its own index and slices allocated **lazily** from the pool the first time
a dab touches a key (`allocateCleared`). It lives for one stroke: cleared
(slices freed) after merge or cancel. It is why a stroke has an *opacity*
that many overlapping dabs cannot exceed — dabs accumulate flow in the
buffer, the buffer is capped by opacity at merge — and why a stroke can be
cancelled by palm rejection without touching the layer.

A stroke buffer is the one place where memory temporarily exceeds the
layer budget: a wild stroke across a 4096² canvas can touch all 256 keys
(64 MiB). `MemoryBudget` reserves one full layer's worth for it.

### 7.2 DabPass

For each batch, `DabPass`:

1. Groups dabs by the tile keys they touch (a dab touches `keysFor(its
   rect)`; large dabs touch several).
2. For each touched key, binds the FBO to the buffer's slice
   (`glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
   pageTex, 0, slice)`), sets viewport `0,0,256,256` and a scissor to the
   canvas rect ∩ tile, uploads that key's dabs as per-instance attributes
   and issues one `glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, n)`.
3. Blend state per the preset's `BufferMode` (`docs/plan/04-tools.md` §2):
   `Accumulate` → `GL_ONE, GL_ONE_MINUS_SRC_ALPHA`, premultiplied
   source-over of each dab into the buffer, so flow builds up;
   `Max` → `glBlendEquation(GL_MAX)` (ES 3.0 core), so overlapping dabs
   within a stroke never exceed the strongest single dab (ink pen, marker,
   hard eraser).

Instance order equals draw order and GL blends primitives in order, so
dab overlap within a batch is deterministic and identical to the CPU
reference `DabStamp` in `engine/core`. FBO rebinds per batch = touched
keys (typically 1–4); on tile-based GPUs each rebind ends a render pass,
which is why dabs are grouped per key rather than drawn in arrival order
across keys.

### 7.3 Dab shaders

```glsl
// dab.vert
#version 300 es
layout(location = 0) in vec2  a_corner;    // (-1,-1)..(1,1)
layout(location = 1) in vec2  i_center;    // canvas px
layout(location = 2) in float i_radius;    // canvas px, major axis
layout(location = 3) in float i_hardness;  // 0..1
layout(location = 4) in float i_flow;      // 0..1 (already through the curves)
layout(location = 5) in float i_angle;     // radians, orientation of the major axis
layout(location = 6) in float i_aspect;    // minor/major, 0 < aspect ≤ 1
layout(location = 7) in vec3  i_color;     // straight sRGB
uniform vec2 u_tileOrigin;                 // canvas px of this slice's (0,0)
out vec2 v_local;                          // dab-local, "major-axis px" (ellipse unwarped to a circle)
flat out float v_radius;
flat out float v_hardness;
flat out vec4  v_color;                    // premultiplied color × flow

void main() {
    float r  = max(i_radius, 1.0);          // sub-pixel dabs are drawn at 1 px and area-weighted (frag)
    float pad = r + 1.0;                     // room for the ≥1 px AA band
    float c = cos(i_angle), s = sin(i_angle);
    vec2 axisMajor = vec2(c, s), axisMinor = vec2(-s, c);
    vec2 p = i_center + a_corner.x * pad * axisMajor + a_corner.y * pad * i_aspect * axisMinor;
    vec2 d = p - i_center;
    v_local = vec2(dot(d, axisMajor), dot(d, axisMinor) / i_aspect);
    v_radius = r;
    v_hardness = i_hardness;
    float area = i_radius < 1.0 ? i_radius * i_radius : 1.0;
    v_color = vec4(i_color, 1.0) * (i_flow * area);
    vec2 t = (p - u_tileOrigin) / 256.0;
    gl_Position = vec4(t * 2.0 - 1.0, 0.0, 1.0);   // tile row 0 = canvas top (§3.1)
}
```

```glsl
// dab.frag
#version 300 es
precision highp float;
in vec2 v_local;
flat in float v_radius;
flat in float v_hardness;
flat in vec4  v_color;
out vec4 o_color;
void main() {
    float d = length(v_local);
    float r = v_radius;
    // Hardness: fully opaque out to r·hardness, then a smoothstep falloff to r.
    // The falloff band is never thinner than 1 canvas px so every edge is anti-aliased,
    // even at hardness 1.0.
    float inner = clamp(min(r * v_hardness, r - 1.0), 0.0, r);
    float m = 1.0 - smoothstep(inner, r, d);
    o_color = v_color * m;                     // premultiplied; blended ONE, ONE_MINUS_SRC_ALPHA
}
```

Grain/texture (pencil) multiplies `m` by a tiled grain texture sampled in
canvas space; bristle texture does the same with a rotated grain — both
`docs/plan/04-tools.md`, both an extra `sampler2D` on this shader, nothing
else changes. Eraser strokes use this exact shader with `i_color = 0`: the
buffer then accumulates coverage in alpha only.

### 7.4 Merge on pen-up

`MergePass` runs once per stroke, for each key in the stroke buffer (the
active layer's tile is created if missing), 256×256 quad, reading the
layer tile `L` and the buffer tile `S` and writing the layer tile's
replacement. Because the shader reads `L` and writes `L`, the pass
ping-pongs: it renders into a scratch slice taken with
`allocateNotOn(pageOf(L), pageOf(S))` (§2.1: never a slice of a page the
pass samples) and then **swaps handles** in the layer index (the old
slice becomes the next scratch, re-checked against the same rule at the
next key) — no copy. With
`L`, `S` premultiplied, `o` the stroke's opacity ceiling (`preset.opacity ·
pressureOpacityMax`, one number per stroke — `docs/plan/04-tools.md` §3.3),
and `S' = S · min(1, o / S.a)` the buffer **capped** at that ceiling
(`S'.a = min(S.a, o)`, colour scaled with it):

| Mode | Formula (premultiplied) |
| --- | --- |
| PAINT | `L' = S' + L·(1 − S'.a)` |
| PAINT, alpha-lock | `L'.rgb = S'.rgb·L.a + L.rgb·(1 − S'.a)`; `L'.a = L.a` |
| ERASE | `L' = L·(1 − S'.a)` (alpha-lock: the eraser is a no-op on locked layers — 05 §1) |
| MIX | see below; alpha-lock: `t = S'.a`, `a_out = L.a` (05 §1's `lerp(cd, cs, s.a)`) |

PAINT is source-over with the stroke as a single source; because `S'.a ≤
o`, no number of overlapping dabs exceeds the stroke's opacity, and `flow`
remains the per-dab weight — exactly PLAN.md §3.1's promise. Alpha-lock is
source-over on straight color restricted to the layer's existing coverage:
at `L.a = 1` it is plain source-over; at `L.a = 0` nothing lands.

**MIX (pigment merge).** This is `bangni_mix_over(L, C_S, S'.a)` of
`docs/plan/09-color-and-mixing.md` §3.1, whose derivation is normative;
restated here in this document's symbols. Straight colors go through
Mixbox's latent space; alpha is ordinary source-over; the interpolation
weight is the share the stroke would have had in a normal source-over of
the same alphas, reduced by the preset's `dilution` where the layer already
has paint:

```
a_out = S'.a + L.a·(1 − S'.a)
t     = S'.a / a_out                         (0 when the stroke is empty, 1 over a transparent layer)
t    *= (L.a > 0) ? 1 − dilution : 1         (09 §3.1; dilution = 0 for non-mixing presets)
C_L   = L.rgb / L.a,  C_S = S'.rgb / S'.a    (premultiplied → straight)
C_out = mixbox_lerp(C_L, C_S, t)             (straight → latent → linear mix → straight)
L'    = (C_out · a_out, a_out)               (straight → premultiplied)
```

```glsl
// merge.frag (skeleton; mergeStroke() is shared with preview.frag, §7.5, by include).
// Assembled by Shaders.fragment(body, mixing) — 09 §5.2: the #version/precision prologue,
// `uniform sampler2D mixbox_lut` + the vendored mixbox.glsl (CC BY-NC 4.0, ADR 0003) and
// `#define MIXLERP mixbox_lerp` in the mixing variant, `#define MIXLERP mix` in the plain one.
uniform sampler2DArray u_layerPage;          // page holding L
uniform sampler2DArray u_strokePage;         // page holding S (may be the same page)
uniform float u_layerSlice, u_strokeSlice;   // -1 = absent (transparent)
uniform int   u_strokeMode;                  // 0 PAINT 1 ERASE 2 MIX
uniform float u_strokeOpacity;               // o = preset.opacity · pressureOpacityMax (04 §3.3)
uniform float u_dilution;                    // preset.dilution, 0 for non-mixing presets (09 §3.1)
uniform bool  u_alphaLock;
in  vec2 v_uv;                               // 0..1 within the tile
out vec4 o_color;

vec4 fetch(sampler2DArray page, float slice) {
    return slice < 0.0 ? vec4(0.0) : texture(page, vec3(v_uv, slice));
}

vec4 mergeStroke(vec4 L, vec4 S) {
    if (S.a > u_strokeOpacity) S *= u_strokeOpacity / S.a;                          // cap at the stroke opacity
    if (u_strokeMode == 1) return L * (1.0 - S.a);                                   // ERASE
    if (u_strokeMode == 0) {                                                         // PAINT
        if (u_alphaLock) return vec4(S.rgb * L.a + L.rgb * (1.0 - S.a), L.a);
        return S + L * (1.0 - S.a);
    }
    float aOut = S.a + L.a * (1.0 - S.a);                                            // MIX = bangni_mix_over (09 §3.1)
    if (aOut <= 0.0) return vec4(0.0);
    float t  = S.a / aOut;
    if (L.a > 0.0) t *= 1.0 - u_dilution;
    if (u_alphaLock) { t = S.a; aOut = L.a; }                                        // 05 §1: lerp(cd, cs, s.a), alpha kept
    vec3  cL = L.a > 0.0 ? L.rgb / L.a : vec3(0.0);
    vec3  cS = S.a > 0.0 ? S.rgb / S.a : vec3(0.0);
    vec3  c  = (L.a <= 0.0) ? cS : (S.a <= 0.0 ? cL : MIXLERP(cL, cS, t));
    return vec4(c * aOut, aOut);
}

void main() {
    o_color = mergeStroke(fetch(u_layerPage, u_layerSlice), fetch(u_strokePage, u_strokeSlice));
}
```

The `L.a == 0` / `S.a == 0` guards keep MIX bit-exact with PAINT where only
one side has color (the LUT round trip would otherwise perturb the last
bit). `mixbox_lut` is uploaded once (`engine/mixbox/MixboxLut`: from
`assets/mixbox/mixbox_lut.png`, decoded with `inPremultiplied = false`, linear
filter, no mipmaps, not sRGB) — `docs/plan/09-color-and-mixing.md` §5 owns the
loader and the CPU `MixboxMixer`. Mixing is a compile-time variant, not a
uniform: `Shaders` builds `merge_stroke` (`MIXLERP = mix`, no LUT sampler)
and `merge_stroke_mix` (`MIXLERP = mixbox_lerp`) from this one source, and
the stroke picks the variant at pen-down (`preset.mixing && mixer.isPigment`,
09 §4); `RgbMixer` selected (decision 5) means the plain variant always.

### 7.5 Truthful preview

The live composite's middle pass (§4) draws the active layer's quads with
`mergeStroke(L, S)` applied *before* the layer's blend mode and opacity:
`s = mergeStroke(mergeStroke(L, S), T) × layerOpacity` (`T` = the
predicted tail, §9), then §3.3's blend. It uses its own fragment shader,
**`preview.frag`** = §3.3's `composite.frag` compiled with `#define
PREVIEW`, which adds:

- three array samplers — `u_tiles` (the active layer's page),
  `u_strokePage`, `u_tailPage` — because the layer tile, the stroke-buffer
  tile and the tail tile are allocated independently and are usually on
  different pages;
- a second and third slice attribute, `layout(location = 2) in vec2
  a_strokeTailSlice` (`−1` = absent, sampled as transparent), alongside
  `a_uvw.z` for the layer;
- `#include "merge.glsl"` (the `mergeStroke` function and its uniforms,
  split out of `merge.frag` so both shaders share one copy) applied
  **per supersampling tap** inside `sampleLayer()`: each tap fetches all
  three slices at the same offset, merges, and the box filter averages the
  merged result, so `u_taps > 1` is exact for the preview too.

The keys drawn are the union of the active layer's, the stroke buffer's
and the tail's present keys within the dirty rect, and `CompositePass`
batches them by the `(layerPage, strokePage, tailPage)` triple (one draw
per distinct triple; in practice one or two). The preview and the commit
therefore run the same arithmetic on the same inputs — what the user sees
mid-stroke is what lands, including erasers on multiply layers and pigment
mixing over a half-transparent wash.

### 7.6 Read-modify-write tools

Smudge, blur and the mixing-with-pickup brushes cannot stamp into a
private buffer: their result at dab *n* depends on the layer as modified
by dab *n−1*. They write to the layer **directly**, dab by dab, in order,
with a ping-pong over the dab's rectangle (`SmudgePass`; blur is the same
pass with a different kernel):

```
for each dab d (in batch order):
    rect = keysFor(d.rect) → up to 4 tiles (more for huge dabs)
    for each tile k in rect:
        copy the dab's sub-rect of L[k] into Scratch (glCopyTexSubImage3D from the layer slice; ≤ (2r+2)² px)
        bind FBO to L[k]; scissor to d.rect ∩ tile
        draw the dab quad: reads Scratch (the pre-dab layer) and Reservoir, writes L[k]
    update Reservoir (smudge: Reservoir ← what was under the dab before the write, sampled from Scratch)
```

`Scratch` here and `Reservoir` are dedicated `GL_TEXTURE_2D` textures
outside the pool (`Scratch` ≤ (2·256+2)², `Reservoir` = `sizeMax + 2`
square = 402² for the 04 §6 defaults, both RGBA8; `Reservoir` is 04's
"pickup buffer" `P`):
the draw samples them while a pool slice is the render target, and a pool
slice on the same page as `L[k]` would violate §2.1's rule. `Reservoir`
carries the picked-up paint from dab to dab; for smudge the deposit is
`bangni_smudge_deposit(L, Reservoir, strength·mask)` and the pickup
`lerp_pm(Reservoir, L_before, ρ·mask)` — the exact math is
`docs/plan/09-color-and-mixing.md` §3.2 (with pigment mixing the colour
lerp is `mixbox_lerp` on straight colours; alpha is a premultiplied lerp so
a smudge can lower coverage); `docs/plan/04-tools.md` §6 has the
strength/pickup constants. Because the layer is changed before pen-up, the
front layer previews it by simply drawing the active layer (no stroke
buffer), and cancel (`ACTION_CANCEL`) for an RMW stroke means *undo the
partial stroke through the journal* rather than dropping a buffer: the
journal entry for an RMW stroke is opened at pen-down (§10.3), after the
§10.1 wait on the previous stroke's readback so its "before" tiles are
current.

**Cost model.** Per dab: ≈2 × (2r+2)² px of work (copy + draw) × tiles
touched, plus one FBO bind per tile. At r = 32 and 4 tiles that is ≈36 K
px; at 200 dabs per frame ≈7 M px — trivial. At r = 256 a single dab is
≈530 K px × 2; `DabGenerator` therefore enforces a minimum spacing of
0.25·r for RMW tools regardless of preset, and the RMW presets cap
`sizeMax` at 400 px diameter (`docs/plan/04-tools.md` §6; the UI slider
knows). Dabs of a batch are never
reordered and never merged: each one's read must see the previous one's
write, which is what makes smudge drag.

## 8. The front-buffered path

`CanvasRenderer` implements `GLFrontBufferedRenderer.Callback<DabBatch>`
(`androidx.graphics:graphics-core`, coordinates in
`libs.versions.toml`). The `param` is the `DabBatch` ring slot itself
(`docs/plan/02-architecture.md` §3.2): the dabs plus a header — stroke id,
`predictedFrom`, the dirty rect (canvas px). The main thread calls
`renderer.renderFrontBufferedLayer(batch)` per input batch while a stroke
is live; slots are released on the GL thread after the multi-buffered
replay (02 §3.2).

### 8.1 `onDrawFrontBufferedLayer(eglManager, bufferInfo, transform, param)`

1. Consume `param` (and any batch already published behind it — one frame,
   all dabs, §11).
2. `DabPass` stamps committed (non-predicted) dabs into the stroke buffer
   (or `SmudgePass` into the layer for RMW).
3. Dirty rect in canvas px = dabs' rects ∪ previous predicted tail's rect ∪
   new predicted tail's rect (§9). Map it to window px through
   `ScreenTransform` (rotated → take the bounding box of the four corners),
   inflate by 1 px, clip to the viewport, then through `transform`
   (graphics-core's pre-rotation matrix) to buffer px for the scissor. Inverse
   map the inflated window rect back to canvas px for tile selection; otherwise
   the clear crosses a tile edge while the neighboring tile is omitted.
4. Composite that rect exactly as §3.2/§4: Below (paper baked in) →
   (active ⊕ stroke buffer) → Above (or per-layer above), into `Accum`,
   then draw `Accum` into the front buffer (§3.2 step 3: a textured quad
   through `u_bufferTransform` into `bufferInfo.frameBufferId`, never a
   blit) with the scissor set. The front buffer receives
   complete, opaque pixels for every point of the rect: nothing is drawn
   *incrementally* onto stale content, so whatever the front buffer held
   before is irrelevant inside the rect and untouched outside it.
5. Draw the predicted tail on top (§9), restricted to the same rect.

Since the multi-buffered layer beneath shows the pre-stroke composite and
the front layer shows the post-dab composite for the touched rects, the
two layers contain the correct pixels everywhere, and the front layer
"grows" as the stroke grows. Scan-line racing can expose only the current
incremental damage while that small region is being written.

Every app redraw uses `commit()`, whose pending-commit count holds front
requests until the multi-buffer release has cleared the front buffer. An active
completion recomposites and presents the cumulative preview once; later frames
return to incremental damage. Re-presenting the growing cumulative preview on
every sample defeats scan-line racing and produces a moving cutoff. Redraws
during a stroke are deferred, and equal Compose inputs are filtered before they
request one. A target-generation gate prevents both front renders and commits
before attachment. Surface changes replace only the `GLFrontBufferedRenderer`;
the shared `GLRenderer`, EGL context, and canvas textures remain alive.

### 8.2 `onDrawMultiDoubleBufferedLayer(eglManager, bufferInfo, transform, params)`

Draws the **full viewport** from committed state (stroke buffer already
merged, caches valid): Below (paper baked in) → active → Above into
`Accum`, then the full-rect `Accum` quad into `bufferInfo.frameBufferId`
(§3.2 step 3). The quad starts at the logical `Accum` dimensions; `transform`
maps it into a pre-rotated buffer whose dimensions may be swapped. `params` is
only iterated to release the ring slots
(`docs/plan/02-architecture.md` §3.2); the current `ScreenTransform` is
always used. This callback also serves every non-stroke redraw (§5).

### 8.3 Pen-up: `commit()`

On the main thread at `ACTION_UP` (or the stroke's natural end):

```kotlin
renderer.execute {                       // GL thread, before the multi-buffered draw
    dabPass.drain(untilStrokeEnd)        // stamp any last dabs (no predicted ones)
    mergePass.merge(strokeBuffer, activeLayer)   // §7.4; RMW strokes: nothing to merge
    readback.enqueue(activeLayer, strokeBuffer.keys)  // §10
    strokeBuffer.release()
    journal.closeEntry(strokeId)         // engine/core bookkeeping; tiles arrive from readback
}
renderer.commit()                        // multi-buffered redraw, front layer hidden
```

Ordering of `execute` blocks relative to pending front-layer renders on
the GL thread is assumed FIFO (to verify against graphics-core; the
fallback is to perform the merge at the top of
`onDrawMultiDoubleBufferedLayer`, keyed by the stroke id in `params`).

### 8.4 Cancel

`ACTION_CANCEL` (or `FLAG_CANCELED`, or palm rejection deciding the stroke
was a palm — `docs/plan/07-input-and-stylus.md`): `renderer.execute {
strokeBuffer.release(); predictedTail.clear() }` then `renderer.cancel()`,
which drops the front-buffered content; the multi-buffered layer still
shows the pre-stroke state so nothing else needs drawing. RMW strokes
additionally undo their open journal entry (§7.6) and then need a
multi-buffered redraw, so they go through `commit()` after the undo rather
than `cancel()`.

### 8.5 The buffer transform

graphics-core hands each callback a `transform` because the buffer may be
pre-rotated relative to the display (the compositor then rotates it for
free). `CanvasRenderer` binds it as `u_bufferTransform` (§3.1) for the one
pass that writes a window buffer — the `Accum` present quad of §3.2 step
3 — with `u_viewport = (bufferInfo.width, bufferInfo.height)` and
`u_projection` the y-down ortho over that size, and uses it to map the
scissor rect (§8.1 step 3). Offscreen passes (dab, merge, sandwich,
`Accum`) use identity and `Accum`'s size. Verified from the library's
source and KDoc: `transform` is a 16-float 4×4 matrix to apply in buffer
pixel space before the projection (§3.1's `projection × transform ×
pixelPos`), and `bufferInfo.frameBufferId` is the FBO the callback is
expected to render into — "useful for re-binding to the original target
after rendering to intermediate frame buffer objects", which is exactly
what the present step does after our per-tile binds. The contract test
pins the uniform names.

### 8.6 View changes during a stroke

**A view change never happens during a stroke.** When a stylus is down,
touch pointers are ignored (palm rejection), so two-finger gestures cannot
start. For a *finger* stroke, a second pointer arriving is arbitrated by
`GestureArbiter` (`engine/core`): if the stroke is younger than the
arbiter's decision window (`PENDING_MS` = 120 ms —
`docs/plan/07-input-and-stylus.md` §3) or has moved less than its
slop, the stroke is **cancelled** and the pair becomes a gesture; otherwise
the stroke is **finished** (`commit()`, as if the finger had lifted) and
the gesture starts on the next event. Never both at once: the front layer
is drawn in view space, a changed view invalidates every pixel of it, and
redrawing the whole front buffer per gesture step would defeat the
low-latency path. Non-stroke redraws (gesture steps, the reset spring)
render the full viewport into the multi-buffered layer via
an empty-param `commit()` so later front input waits for its release-time
clear.

Surface size changes (rotation, fold, multi-window) arrive through the
`SurfaceHolder`; `ViewTransform.rebase(oldFit, newFit)` keeps the canvas
point under the viewport center, `Accum`/`Scratch` are reallocated, and a
full redraw follows. If a stroke is live at that moment it is finished
first.

## 9. The predicted tail

`MotionEventPredictor.predict()` yields one predicted event per frame
(`docs/plan/07-input-and-stylus.md`). The predictor wrapper turns it into
`StrokeInput` samples with `predicted = true`; `DabGenerator` runs them
through the same stabilizer/dynamics *from a copy of its state* (the
remainder and stabilizer state are not advanced by predicted samples), so
the tail's dabs are exactly the dabs the real samples would produce if
the prediction is right.

The tail is drawn **only in the front layer**, in step 5 of §8.1, after the
truthful composite: the tail's dabs are stamped into a tail scratch grid
(`TailBuffer`, allocated like a stroke buffer, ≤ 4 keys, cleared every
frame) and composited as if they were part of the stroke buffer — the same
`mergeStroke` preview, so a predicted eraser tail erases and a predicted
mixing tail mixes. Nothing predicted ever reaches the stroke buffer or the
layer.

**Erasing the previous tail.** Each frame's dirty rect includes the
previous tail's rect (§8.1 step 3), and the composite draws complete
pixels there from committed content plus the *real* stroke buffer — the
old tail simply is not part of that content, so redrawing the rect erases
it. No "undo the tail" pass, no bookkeeping beyond one `IntRect`.

**Adaptive disable.** The handler keeps the screen-space error between the
last prediction and the sample that actually arrived, as an exponential
moving average; when it exceeds `PREDICT_ERR_DISABLE_PX` (12 px) the tail
is switched off for the rest of the stroke and re-enabled at the next
`ACTION_DOWN`, and the tail is truncated to `PREDICT_MAX_NS` (16 ms) of
lookahead — `docs/plan/07-input-and-stylus.md` §8 owns both constants. Fast
scribbles with a poor predictor would otherwise show a visible flicker of
wrong tail every frame, which is worse than the latency it hides.

## 10. Readback

### 10.1 Dirty tiles after merge

After a merge (or an RMW stroke's end, or a fill upload, or an undo
restore) the GPU holds the truth for the touched keys and the CPU does
not. `Readback` copies them out asynchronously:

1. Two PBOs (`GL_PIXEL_PACK_BUFFER`), each sized for `READBACK_CHUNK = 64`
   tiles (16 MiB), `GL_STREAM_READ`. Larger key sets are read in chunks of
   64 across successive GL entries.
2. For each key: bind the FBO to the slice (`glFramebufferTextureLayer`),
   `glReadPixels(0, 0, 256, 256, GL_RGBA, GL_UNSIGNED_BYTE, offset)` into
   the bound PBO — returns immediately.
3. `glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)` after the chunk; the
   `(pbo, fence, keys, revision)` tuple goes into a pending queue.
4. On every subsequent GL-thread entry (any render or `execute`) and from
   the `Choreographer`-driven `execute { readback.poll() }` the main thread
   posts while PBOs are in flight (`docs/plan/02-architecture.md` §3.3),
   `glClientWaitSync(fence, 0, 0)`; if signaled, `glMapBufferRange(...,
   GL_MAP_READ_BIT)`, copy each tile into a buffer from
   `TileBufferPool` (256 KiB each, recycled), unmap, and
   hand `CpuTile(layerId, key, revision, bytes)` to `TileStore`
   through a `Channel` consumed on `Dispatchers.IO`.

Row order matches the CPU tile format directly (§3.1: row 0 = canvas top),
so the copy is a `memcpy`.

**Ordering.** A merge for stroke *n+1* on the same layer is not issued
until stroke *n*'s readback chunk has been mapped (in practice always
true: strokes are longer than a frame). This keeps the CPU-side "before"
of every journal entry equal to the GPU-side "before", which is what makes
undo exact (decision 3) — *provided* the "before" is captured only after
that wait, i.e. at merge time, not at pen-down (§10.2: pen-down of *n+1*
routinely happens milliseconds after pen-up of *n*, before *n*'s fence
has signaled). The rule is enforced in `Readback.enqueue` with a wait on
the oldest fence (a real block only if the GPU is seconds behind); for
RMW strokes the same wait runs at pen-down, before the first `SmudgePass`
write (§7.6, §10.3).

### 10.2 What persistence gets

`TileStore` keeps the CPU mirror **only for tiles dirtied since the last
save** (PLAN.md §3.1); clean tiles live on disk and come back through
`TileStore.load` on context recreation. The journal's "before" tiles are
whatever `TileStore` holds for the key — mirror or disk — **at merge
time** (pen-up, inside the `execute {}` of §8.3, after `Readback.enqueue`'s
wait on the previous chunk has made `TileStore` current for the keys the
previous stroke touched). Capturing at pen-down would record the
pre-*n* contents for keys shared with a stroke *n* whose readback is
still in flight, and undoing *n+1* would then also revert *n*. The
"after" arrives from the readback and is written by the autosave flush. Formats: `docs/plan/06-document-and-persistence.md`.

### 10.3 Undo / redo, fill, and uploads

Restoring a tile is the reverse path: `LayerTextures.upload(key, bytes)`
= `glTexSubImage3D` into the (possibly newly allocated) slice, then cache
invalidation per §4 and a multi-buffered redraw. `FloodFill` (CPU,
`engine/core`) produces a mask over the affected tiles which is uploaded
into a stroke buffer and merged with `StrokeMode.PAINT` — the fill is
just a stroke with a pre-made buffer, and gets the same readback and
journal. RMW strokes open their journal entry at pen-down with the
"before" tiles of the keys the first dab touches, adding keys as the
stroke spreads (`HistoryJournal.extendOpenEntry`), so a cancel can
restore them; because they modify the layer immediately, the §10.1 wait on
the previous stroke's readback runs at pen-down, before the first
`SmudgePass` write, so every "before" they capture is current.

### 10.4 Flatten (thumbnail and gallery)

`CanvasRenderer.flatten(scaleDenominator)` renders the whole composite in
canvas space (all layers, paper included) tile by tile into a scratch
slice using the §4 rebuild machinery (identity screen transform, paper
first exactly as a Below rebuild does, then every layer contributing —
so flatten and the live preview are the same arithmetic in the same order), reads each tile back synchronously through the same
PBOs, and returns rows to the caller. `GalleryExporter` asks for `1`
(full resolution) and encodes PNG on IO; the Studio thumbnail asks for the
largest power of two that fits ~512 px and box-filters on the CPU. Full
resolution flatten of 8192² is 1024 tiles ≈ 256 MiB streamed in 64-tile
chunks — never held whole.

## 11. Frame pacing

- **One frame, all dabs.** The GL callback drains every batch that arrived
  since it last ran (§8.1 step 1). With unbuffered dispatch the main
  thread may call `renderFrontBufferedLayer` several times per display
  frame; graphics-core coalesces the requests and our drain makes the
  callback count irrelevant — each callback does all pending work. Latency
  is bounded by GPU time for the dirty rect, not by input rate.
- **No vsync coupling on the front path.** The front buffer is shown as
  soon as the GPU finishes; on a 120 Hz panel that is what makes the
  stroke feel attached to the pen. The multi-buffered path is vsync-paced
  by the system; we never sleep or spin.
- **Budget per front frame** (targets, measured in
  `docs/plan/10-performance.md`): dab stamping ≤ 1 ms for a typical batch,
  the recomposite of the dirty rect ≤ 2 ms, readback issue ≈ 0. A dirty
  rect is small by construction (a batch of dabs at pen speed spans a few
  hundred px); the only large rects are large dabs, which are rare.
- **Frame rate requests**: none. `SurfaceControlCompat.Transaction.setFrameRate`
  stays unused in v1; the panel runs at its default and the front path
  ignores it anyway.

## 12. EGL context loss and recreation

Everything on the GPU is derived state. A plain `surfaceDestroyed` /
`surfaceChanged` (activity stop, rotation, fold) does **not** drop the
pool: graphics-core's `GLRenderer` and its EGL context persist across
`SurfaceHolder` callbacks and only `Accum`/`Scratch` are re-created
(`02-architecture.md` §8.2). The cold path is a new `EngineSession` —
after `release()` on Compose dispose, `detachSession()`, or a genuine
`EGL_CONTEXT_LOST` — when `CanvasRenderer.release()` has dropped the pool,
caches, `Accum`, PBOs and shaders. On the next surface of a cold session:

1. Probe capabilities (§13), compile shaders, upload the Mixbox LUT.
2. Recreate `TilePool`; for each layer, `LayerTextures.rebuild()` asks
   `TileStore` for every key that has content: mirrored tiles upload
   immediately, disk tiles stream in on IO and upload as they arrive
   (`execute {}` per chunk). The view redraws after each chunk; a painting
   fills in over a few frames rather than blocking.
3. Rebuild the sandwich caches when the active layer's neighbors are
   complete; until then the caches are `unavailable` and the compositor
   draws per layer.
4. A stroke that was live when the context died is lost — its buffer had
   not been merged. Readbacks in flight are discarded with it: their
   tiles existed only on the GPU, so the journal entry waiting on them is
   dropped (its edit never became durable) and the keys keep their
   on-disk contents. Concretely, the last stroke before a GPU reset may
   vanish. That is the one honest loss; a GPU reset itself is a rare
   driver fault, not a normal lifecycle event (surface teardown on stop
   waits for pending readbacks first).

Because the CPU/disk state is the document and the GPU is a cache, there
is no "save GPU state" step and no path in which the on-disk painting is
worse than what was on screen a second earlier than the fault.

## 13. Startup capability probe and GL errors

`GlCaps.probe()` runs once per context, on the GL thread, before any
allocation:

| Query | Use | Degrade |
| --- | --- | --- |
| `GL_VERSION` / EGL client version 3 | ES 3.0 is required (texture arrays, PBO, `glTexStorage3D`, instancing) | No ES 3.0 (does not exist on API 29 hardware in practice): show the "unsupported device" screen; the Studio still works, the Canvas refuses to open. |
| `GL_MAX_ARRAY_TEXTURE_LAYERS` | `slicesPerPage = min(v, 256)` | `< 256` would violate the spec; if it ever happens pages are just smaller. |
| `GL_MAX_TEXTURE_SIZE` | must be ≥ 256 for tiles (always) and ≥ viewport size for `Accum` | Below viewport size: `Accum` is tiled into two halves (never seen on a real device; guard only). |
| `GL_MAX_RENDERBUFFER_SIZE`, `GL_MAX_VIEWPORT_DIMS` | same as above | same |
| Extensions: `EXT_shader_framebuffer_fetch`, `EXT_color_buffer_half_float` | optional fast paths (§3.2), recorded, unused in v1 | nothing |
| Front-buffer usage support | graphics-core decides and falls back internally | The API surface is the same; latency is worse. `CanvasRenderer` exposes `isFrontBuffered` for the About screen's diagnostics line. |
| `GL_RENDERER`, `GL_VENDOR` | logged; shown in About | — |

`GlCaps` is a plain data class so `MemoryBudget` (JVM) can be tested with
synthetic probes.

**GL errors.** `glGetError` is polled after every pass in debug builds
(`BuildConfig.DEBUG`, a `checkGl("dabPass")` helper that throws) and, in
all builds, after every allocation (`glTexStorage3D`, `glBufferData`) and
after shader/program link. `GL_OUT_OF_MEMORY` on allocation is converted
into `PoolExhausted` and handled per §2.1; a shader compile/link failure
at startup is fatal for the Canvas (a crash-report-worthy bug, not a
device condition: the shaders are ES 3.0 baseline). Any other error in a
release build is logged once per session with the pass name and ignored —
a torn frame beats a crashed painting; the next frame redraws everything
touched anyway.

**Shader contract test** (`GlShaderContractTest`, ported from Meltorama):
the GLSL sources are Kotlin string constants in `engine/gl/Shaders.kt`
(only the vendored `mixbox.glsl` comes from assets, 09 §5.2), assembled by
`Shaders.fragment(body, mixing)` and checked in the JVM test for every
uniform and attribute location the Kotlin binder references, in
declaration order, for exactly one `uniform sampler2D mixbox_lut` in the
mixing variants and none in the plain ones, and for the `#include
"merge.glsl"` lines in `merge.frag` and `preview.frag` (includes are
resolved by `Shaders.kt` at load time by string substitution — GLSL ES has
no preprocessor include).

## 14. Memory

| Item | Bytes | Note |
| --- | --- | --- |
| One tile, RGBA8 | 262 144 (256 KiB) | GPU slice and CPU copy alike |
| One pool page (256 slices) | 64 MiB | allocated lazily |
| `Accum` + `Scratch` at 2560×1600 | 2 × 16 MiB | per surface size |
| Readback PBOs | 2 × 16 MiB | 64 tiles each |
| `TailBuffer`, RMW `Reservoir`, sandwich scratch | ≤ 4 + 4 + 2 tiles | fixed |
| Mixbox LUT | 1 MiB | 512² RGBA |
| `DabRing` (CPU) | 256 KiB | fixed (8 × 1024 dabs × 8 floats) |

Per layer, worst case (every tile painted), for the size presets
`CanvasPresets` offers (`docs/plan/10-performance.md` §4; the dialog shows
only those the budget admits — these rows are the arithmetic):

| Preset | Tiles (tx × ty) | Per layer, full | 8 layers + sandwich (2) + stroke buffer (1) |
| --- | --- | --- | --- |
| Phone sketch 1080×1920 | 5 × 8 = 40 | 10 MiB | 110 MiB |
| Square 2048² | 8 × 8 = 64 | 16 MiB | 176 MiB |
| Tablet 2560×1600 | 10 × 7 = 70 | 17.5 MiB | 192.5 MiB |
| Large 4096² | 16 × 16 = 256 | 64 MiB | 704 MiB |
| Format ceiling 8192² (post-v1) | 32 × 32 = 1024 | 256 MiB | 2.75 GiB (the budget will not admit 8 layers here on any current device) |

`MemoryBudget.compute(device, canvas).maxLayers` is
`docs/plan/10-performance.md` §4's: `gpuTileBudgetBytes / layerBytes −
STROKE_BUFFER_RESERVE_LAYERS (1)`, clamped to `1..MAX_LAYERS (16)`, where
`gpuTileBudgetBytes` is `totalMem / 8` clamped to 256 MiB..1.5 GiB (a flat
256 MiB on `isLowRamDevice`). The sandwich halves are not reserved: they
are sparse canvas-space grids that ride on the tiles no layer has painted
(10 §2.6), and the lazy page allocation is the backstop. It assumes fully
painted layers — pessimistic on purpose (decision 4: honest, not clever).
Because memory grows with painted tiles, the cap is a guarantee, not an
estimate: if the budget admits 8 layers, all 8 can be painted edge to
edge. The CPU side is bounded separately by the dirty-mirror flush cadence
(`CPU_MIRROR_CAP_BYTES`, 10 §4) in
`docs/plan/06-document-and-persistence.md`.

## 15. Class map (engine/gl)

| Class | Owns | Called from |
| --- | --- | --- |
| `CanvasRenderer` | the graphics-core callbacks, frame orchestration, `GlCaps`, context lifecycle | `CanvasSurface` (UI), `CanvasViewModel` via a thin `EngineCommands` interface |
| `TilePool` | pages, free lists, `SliceHandle`s | everything below |
| `LayerTextures` | per-layer index, upload, swap-on-merge | `MergePass`, `SmudgePass`, undo restore |
| `StrokeBuffer` / `TailBuffer` | per-stroke tile sets | `DabPass`, `MergePass`, front composite (`preview.frag`) |
| `DabPass` | instanced dab stamping | front callback, commit |
| `MergePass` | stroke → layer, all four modes, Mixbox | commit, fill |
| `SmudgePass` | RMW ping-pong, `Reservoir` | front callback (RMW strokes) |
| `CompositePass` | tile quads by page, blend modes, filtering choice, `Accum`/`Scratch` | both callbacks, `SandwichCache`, flatten |
| `SandwichCache` | Below/Above grids, invalidation | layer edits, active-layer change |
| `Readback` | PBOs, fences, `CpuTile` channel | commit, undo, flatten |
| `Shaders` | source loading, include substitution, program cache, uniform locations | all passes |

Everything decision-shaped — which keys a rect touches, which cache half
an edit invalidates, which filter a zoom uses, how many layers fit, the
blend and merge arithmetic — has a pure-JVM twin in `engine/core`
(`TileGrid`, `SandwichPolicy`, `FilterPolicy`, `MemoryBudget`, `Composite`,
`DabStamp`, `StrokeMerge`) with tests; the GL classes call those and issue
GL calls, nothing else.
