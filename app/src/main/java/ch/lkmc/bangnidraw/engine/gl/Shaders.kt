package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.BlurKernel
import ch.lkmc.bangnidraw.engine.core.DabStamp
import ch.lkmc.bangnidraw.engine.core.GrainMode
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.SmudgeKernel

/**
 * Every GLSL source the engine uses, as Kotlin strings, plus the list of
 * uniforms each program's Kotlin binder looks up
 * (`docs/plan/03-canvas-engine.md` §13, §15).
 *
 * Strings rather than asset files so `GlShaderContractTest` and
 * `GlslDeclarationOrderTest` can read them on the JVM, where CI runs and no GL
 * context exists. The vendored `mixbox.glsl` is the one exception and arrives
 * from assets with the mixing passes (`09-color-and-mixing.md` §5.2).
 *
 * **The uniform lists are the contract.** [GlProgram] looks up exactly the
 * names in a [Source]'s `uniforms`, and the contract test asserts the source
 * declares exactly those — so a rename on one side fails on the JVM instead of
 * leaving `glGetUniformLocation` quietly returning −1 on a device.
 */
object Shaders {

    /** `layout(location = …)` indices, shared with every VAO the passes build. */
    const val ATTR_POS = 0
    const val ATTR_UV = 1

    const val VERSION_LINE = "#version 300 es"

    /**
     * The largest supersample grid the composite shader will run: §3.4's table
     * tops out at `u_taps = 4` (4x4 taps, an 8x8 texel footprint) below 0.25x
     * zoom, and accepts residual aliasing under 0.125x rather than going wider.
     *
     * The shader clamps to this rather than trusting the uniform, so the
     * sample count and the divisor agree for any value the binder can send.
     */
    const val MAX_TAPS = 4

    /** A GLSL type and name, as the source declares it. */
    data class Uniform(val name: String, val type: String)

    /**
     * One linkable program: two sources and the uniforms its binder resolves.
     *
     * [uniforms] must list every uniform the source declares, and no others.
     * A uniform a shader declares but never *reads* is optimized out by the
     * driver and its location comes back −1, so "declared but unused" is a
     * link-time trap rather than dead weight — which is why the contract test
     * checks both directions and why `u_viewport` is not here (see
     * [COMPOSITE_VERT]).
     */
    data class Source(
        val name: String,
        val vertex: String,
        val fragment: String,
        val uniforms: List<Uniform>,
    )

    // ---------------------------------------------------------------- vertex

    /**
     * The one vertex shader of §3.1: a quad per tile, corners in canvas px,
     * mapped through the composed `view ∘ fit` similarity.
     *
     * Two deviations from the snippet in §3.1, both because the snippet
     * declares uniforms its own body never reads:
     *
     * - **No `u_viewport`.** §3.1 says `u_projection` is built *on the JVM* as
     *   `ortho(0, w, h, 0)`; the viewport size is therefore an input to
     *   building that matrix, not something the shader needs. Declared here it
     *   would be optimized out and its location would come back −1, breaking
     *   the very binder contract this file exists to hold.
     * - **No `v_canvas`.** Nothing in this PR's fragment shaders reads a
     *   canvas-space position — the checkerboard works in screen space off
     *   `gl_FragCoord`, per §3.2 — so carrying it would be an unused varying.
     *   `DabPass` and `MergePass` (roadmap 2.4) add it back with the body that
     *   reads it.
     *
     * Matrix order is §3.1's: graphics-core's `transform` is meant to be
     * applied in **buffer pixel space**, before an orthographic projection of
     * `bufferInfo.width × bufferInfo.height`, so the product is
     * `projection × transform × pixelPos`. `u_bufferTransform` is identity for
     * the offscreen passes, where there is no pre-rotation to absorb.
     *
     * Row convention: texture row 0 is the canvas's **top** row — tiles are
     * stored y-down like the CPU copies and like `glReadPixels` returns them,
     * so there are no flips anywhere, and the y-down ortho in `u_projection`
     * is the single place GL's y-up-ness appears.
     */
    val COMPOSITE_VERT = """
        $VERSION_LINE
        precision highp float;
        layout(location = $ATTR_POS) in vec2 a_canvas;
        layout(location = $ATTR_UV) in vec3 a_uvw;
        // Packed similarity (a, b, tx, ty), i.e. the four floats of
        // ScreenTransform: p = (a*x - b*y + tx, b*x + a*y + ty). The binder
        // uploads them in this order; any other order compiles, passes the
        // uniform contract test, and renders garbage.
        uniform vec4 u_screen;
        uniform mat4 u_projection;
        uniform mat4 u_bufferTransform;
        out vec3 v_uvw;
        void main() {
            vec2 p = vec2(u_screen.x * a_canvas.x - u_screen.y * a_canvas.y + u_screen.z,
                          u_screen.y * a_canvas.x + u_screen.x * a_canvas.y + u_screen.w);
            gl_Position = u_projection * u_bufferTransform * vec4(p, 0.0, 1.0);
            v_uvw = a_uvw;
        }
    """.trimIndent()

    // -------------------------------------------------------------- fragment

    /**
     * The composite fragment shader of §3.3, in premultiplied form.
     *
     * `blendStraight`'s dispatch is **generated from [BlendMode]** rather than
     * written out: the `when` below is exhaustive over the enum, so adding a
     * mode without giving it GLSL is a Kotlin compile error. Hand-written
     * cases would fall through to normal instead, which is the exact failure
     * `11-testing.md` §4 asks the contract test to catch — this makes it
     * unreachable rather than merely tested.
     *
     * `Composite.kt` (`engine/core`) implements the identical formulas on
     * `Int` pixels and is the pinned oracle (PLAN.md §7): when one changes,
     * both change.
     */
    /**
     * The declarations every compositing fragment shader opens with.
     *
     * Shared with [PREVIEW_FRAG] so the two cannot drift on `MAX_TAPS` or
     * `TILE_PX`, which both use to convert a canvas-px offset into tile uv.
     */
    internal val COMPOSITE_HEAD = listOf(
        VERSION_LINE,
        "precision highp float;",
        "precision highp sampler2DArray;",
        "#define MAX_TAPS $MAX_TAPS",
        "#define TILE_PX $TILE_SIZE",
    ).joinToString("\n")

    /**
     * The blend and the output, byte-identical in `composite.frag` and
     * `preview.frag`.
     *
     * §7.5 defines the preview as `composite.frag` compiled with
     * `#define PREVIEW`: one source, two programs. There is no preprocessor
     * here, so the sharing has to be structural — this constant is spliced into
     * both, and `StrokeShaderContractTest` asserts that it really is. Copying
     * the W3C compositing arithmetic into a second string instead would let the
     * preview and the commit diverge in exactly the way §7.5 exists to forbid,
     * and nothing on the JVM would notice.
     *
     * `sampleLayer()` is what differs and is therefore *not* here: composite
     * reads the layer, preview reads the layer merged with the stroke buffer
     * and the tail. Both return a premultiplied, opacity-free colour, which is
     * the contract this tail depends on.
     */
    /** Shared W3C blend arithmetic for screen and tile-space compositors. */
    internal val BLEND_GLSL = """
        // Straight-alpha separable blend functions B(Cb, Cs), per channel.
        vec3 blendStraight(int mode, vec3 cb, vec3 cs) {
        ${blendDispatch(indent = 12)}
        }

        vec4 blendLayer(vec4 b, vec4 s, int mode) {
            if (mode == ${BlendMode.NORMAL.shaderId}) return s + b * (1.0 - s.a);
            vec3 cb = b.a > 0.0 ? b.rgb / b.a : vec3(0.0);
            vec3 cs = s.a > 0.0 ? s.rgb / s.a : vec3(0.0);
            vec3 f = blendStraight(mode, cb, cs);
            // W3C compositing, premultiplied:
            // co = cs'(1-ab) + cb'(1-as) + as*ab*B(Cb,Cs)
            vec3 co = s.rgb * (1.0 - b.a) + b.rgb * (1.0 - s.a) + s.a * b.a * f;
            float ao = s.a + b.a * (1.0 - s.a);
            return vec4(co, ao);
        }
    """.trimIndent()

    internal val COMPOSITE_TAIL = """
        $BLEND_GLSL

        void main() {
            vec4 s = sampleLayer() * u_opacity;
            if (u_blend == ${BlendMode.NORMAL.shaderId}) { o_color = s; return; }
            vec4 b = texelFetch(u_backdrop, ivec2(gl_FragCoord.xy), 0);
            o_color = blendLayer(b, s, u_blend);
        }
    """.trimIndent()

    val COMPOSITE_FRAG = listOf(
        COMPOSITE_HEAD,
        """
        uniform sampler2DArray u_tiles;
        uniform sampler2D u_backdrop;
        uniform int u_blend;
        uniform float u_opacity;
        uniform int u_taps;
        uniform vec2 u_canvasPerScreen;
        in vec3 v_uvw;
        out vec4 o_color;

        vec4 sampleLayer() {
            // Clamped, and then used for BOTH the loop bound and the divisor.
            // The two must agree: the loops stop at MAX_TAPS, so dividing by a
            // larger u_taps would sum 16 samples and divide by more, dimming
            // the layer, and a u_taps of 0 would sum none and return 0.0/0.0.
            // Clamping once here makes the shader correct for any value the
            // binder can send, rather than resting on a range nothing enforces.
            int taps = clamp(u_taps, 1, MAX_TAPS);
            if (taps == 1) return texture(u_tiles, v_uvw);
            // Box filter over a taps x taps grid spanning one screen pixel,
            // offsets in canvas px converted to tile uv (TILE_SIZE px per tile).
            vec4 acc = vec4(0.0);
            float n = float(taps);
            for (int j = 0; j < MAX_TAPS; j++) {
                if (j >= taps) break;
                for (int i = 0; i < MAX_TAPS; i++) {
                    if (i >= taps) break;
                    vec2 off = ((vec2(float(i), float(j)) + 0.5) / n - 0.5) * u_canvasPerScreen;
                    // The offset can push uv outside [0,1], where CLAMP_TO_EDGE
                    // repeats this tile's edge texel instead of reaching into
                    // the neighbour. That is the tile-seam error §3.4 measures
                    // and accepts for v1, with the index-texture compositor as
                    // the named escalation path — not an oversight.
                    acc += texture(u_tiles, vec3(v_uvw.xy + off / float(TILE_PX), v_uvw.z));
                }
            }
            return acc / (n * n);
        }
        """.trimIndent(),
        COMPOSITE_TAIL,
    ).joinToString("\n")

    /**
     * Step 3 of §3.2: `Accum` → the window buffer as a textured quad.
     *
     * Not a `glBlitFramebuffer`, and the reason is worth keeping next to the
     * code: a blit cannot rotate, so when graphics-core hands us a pre-rotated
     * buffer (`bufferInfo.width/height` swapped relative to the viewport) a
     * same-size blit of the viewport-oriented `Accum` is wrong or out of
     * bounds. The quad goes through `u_bufferTransform` and comes out right.
     */
    val PRESENT_FRAG = """
        $VERSION_LINE
        precision highp float;
        uniform sampler2D u_source;
        in vec3 v_uvw;
        out vec4 o_color;
        void main() {
            o_color = texture(u_source, v_uvw.xy);
        }
    """.trimIndent()

    /**
     * The canvas paper/checkerboard of §3.2 step 1. Opaque paper binds the
     * same solid colour to both colour uniforms; transparent paper binds the
     * theme's alternating pair.
     *
     * Squares are sized in **screen** space, off `gl_FragCoord`: canvas-space
     * squares would shrink to noise when zoomed out and become slabs when
     * zoomed in. `u_checkerPx` carries 8 dp already converted to px by the
     * caller, which is the only place that knows the display density.
     *
     * Colours are uniforms rather than constants so the checkerboard follows
     * the light/dark theme — `ui/theme/Color.kt` owns the two values, per
     * AGENTS.md's "no ad-hoc `Color(0x…)`" rule.
     */
    val CHECKER_FRAG = """
        $VERSION_LINE
        precision highp float;
        uniform float u_checkerPx;
        uniform vec4 u_checkerA;
        uniform vec4 u_checkerB;
        in vec3 v_uvw;
        out vec4 o_color;
        void main() {
            vec2 cell = floor(gl_FragCoord.xy / u_checkerPx);
            float parity = mod(cell.x + cell.y, 2.0);
            o_color = mix(u_checkerA, u_checkerB, parity);
        }
    """.trimIndent()

    // -------------------------------------------------------------- programs

    val COMPOSITE = Source(
        name = "composite",
        vertex = COMPOSITE_VERT,
        fragment = COMPOSITE_FRAG,
        uniforms = listOf(
            Uniform("u_screen", "vec4"),
            Uniform("u_projection", "mat4"),
            Uniform("u_bufferTransform", "mat4"),
            Uniform("u_tiles", "sampler2DArray"),
            Uniform("u_backdrop", "sampler2D"),
            Uniform("u_blend", "int"),
            Uniform("u_opacity", "float"),
            Uniform("u_taps", "int"),
            Uniform("u_canvasPerScreen", "vec2"),
        ),
    )

    val PRESENT = Source(
        name = "present",
        vertex = COMPOSITE_VERT,
        fragment = PRESENT_FRAG,
        uniforms = listOf(
            Uniform("u_screen", "vec4"),
            Uniform("u_projection", "mat4"),
            Uniform("u_bufferTransform", "mat4"),
            Uniform("u_source", "sampler2D"),
        ),
    )

    val CHECKER = Source(
        name = "checker",
        vertex = COMPOSITE_VERT,
        fragment = CHECKER_FRAG,
        uniforms = listOf(
            Uniform("u_screen", "vec4"),
            Uniform("u_projection", "mat4"),
            Uniform("u_bufferTransform", "mat4"),
            Uniform("u_checkerPx", "float"),
            Uniform("u_checkerA", "vec4"),
            Uniform("u_checkerB", "vec4"),
        ),
    )

    /**
     * Every program this PR ships. The contract tests iterate this rather than
     * a list of their own, so a program added without its uniforms declared —
     * or with a uniform the binder does not know about — fails on the JVM.
     *
     * `merge`, `preview`, `dab` and `smudge` join it with their passes
     * (roadmap 2.4 and 2.5), together with the `#include` substitution and the
     * Mixbox variants of `11-testing.md` §4 that have nothing to check until
     * then.
     */
    // ------------------------------------------------------- dab (§7.2, §7.3)

    /** Instanced attribute slots for [DAB_VERT], shared with `DabPass`'s VAO. */
    const val ATTR_DAB_CORNER = 0
    const val ATTR_DAB_CENTER = 1
    const val ATTR_DAB_RADIUS = 2
    const val ATTR_DAB_HARDNESS = 3
    const val ATTR_DAB_FLOW = 4
    const val ATTR_DAB_ANGLE = 5
    const val ATTR_DAB_ASPECT = 6

    /**
     * §7.3's `dab.vert`: one instanced quad per dab, in canvas px, mapped into
     * the target slice's 0..1 tile space.
     *
     * One deviation from the snippet, for the same reason as `u_viewport` in
     * [COMPOSITE_VERT]. The snippet declares `i_color` as a per-instance
     * attribute, but §6 is explicit that "colour and the stroke opacity are per
     * stroke (uniforms), never per dab" and that the eight per-dab fields are
     * `DAB_STRIDE`. A ninth per-instance `vec3` would contradict the dab layout
     * `02-architecture.md` §3.2 pins, and would send the same value 1 024 times
     * per batch. It is `u_color` here.
     *
     * The clamp and the area weight are `DabStamp.drawRadius`/`areaWeight`
     * (§15's twin); `StrokeShaderContractTest` holds the two together.
     */
    val DAB_VERT = """
        $VERSION_LINE
        precision highp float;
        layout(location = $ATTR_DAB_CORNER)   in vec2  a_corner;
        layout(location = $ATTR_DAB_CENTER)   in vec2  i_center;
        layout(location = $ATTR_DAB_RADIUS)   in float i_radius;
        layout(location = $ATTR_DAB_HARDNESS) in float i_hardness;
        layout(location = $ATTR_DAB_FLOW)     in float i_flow;
        layout(location = $ATTR_DAB_ANGLE)    in float i_angle;
        layout(location = $ATTR_DAB_ASPECT)   in float i_aspect;
        uniform vec2 u_tileOrigin;
        uniform vec3 u_color;
        out vec2 v_local;
        out vec2 v_canvas;
        flat out float v_radius;
        flat out float v_hardness;
        flat out vec4  v_color;
        void main() {
            float r = max(i_radius, 1.0);
            float pad = r + 1.0;
            float c = cos(i_angle), s = sin(i_angle);
            vec2 axisMajor = vec2(c, s), axisMinor = vec2(-s, c);
            vec2 p = i_center + a_corner.x * pad * axisMajor
                              + a_corner.y * pad * i_aspect * axisMinor;
            vec2 d = p - i_center;
            v_local = vec2(dot(d, axisMajor), dot(d, axisMinor) / i_aspect);
            v_canvas = p;
            v_radius = r;
            v_hardness = i_hardness;
            float area = i_radius < 1.0 ? i_radius * i_radius : 1.0;
            v_color = vec4(u_color, 1.0) * (i_flow * area);
            vec2 t = (p - u_tileOrigin) / float($TILE_SIZE);
            gl_Position = vec4(t * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    /**
     * §7.3's `dab.frag`. The falloff is `DabStamp.coverage`'s, and the
     * `fwidth(d)` converts one canvas pixel into the ellipse's local distance.
     * A literal `r - 1.0` gives a flat tip only `aspect` pixels of feather on
     * its minor axis and aliases there (REVIEW.md R-055).
     *
     * Premultiplied out, blended `GL_ONE, GL_ONE_MINUS_SRC_ALPHA` or under
     * `GL_MAX` depending on the preset's `BufferMode` (§7.2). An eraser runs
     * this exact shader with `u_color = 0`, so the buffer accumulates coverage
     * in alpha alone.
     */
    val DAB_FRAG = """
        $VERSION_LINE
        precision highp float;
        precision highp int;
        uniform int u_grainMode;
        in vec2 v_local;
        in vec2 v_canvas;
        flat in float v_radius;
        flat in float v_hardness;
        flat in vec4  v_color;
        out vec4 o_color;

        float proceduralGrain(vec2 canvas) {
            uvec2 cell = uvec2(floor(max(canvas, vec2(0.0))));
            uint h = cell.x * ${DabStamp.GRAIN_HASH_X}u + cell.y * ${DabStamp.GRAIN_HASH_Y}u;
            h = h ^ (h >> ${DabStamp.GRAIN_HASH_SHIFT}u);
            float unit = float(h & ${DabStamp.GRAIN_HASH_MASK}u) / float(${DabStamp.GRAIN_HASH_MASK});
            return ${DabStamp.GRAIN_MIN_WEIGHT} + (1.0 - ${DabStamp.GRAIN_MIN_WEIGHT}) * unit;
        }

        void main() {
            float d = length(v_local);
            float r = v_radius;
            float feather = max(fwidth(d), ${DabStamp.GRADIENT_EPSILON});
            float inner = clamp(min(r * v_hardness, r - feather), 0.0, r);
            float m = 1.0 - smoothstep(inner, r, d);
            if (u_grainMode == ${GrainMode.Procedural.shaderId}) m *= proceduralGrain(v_canvas);
            o_color = v_color * m;
        }
    """.trimIndent()

    val DAB = Source(
        name = "dab",
        vertex = DAB_VERT,
        fragment = DAB_FRAG,
        uniforms = listOf(
            Uniform("u_tileOrigin", "vec2"),
            Uniform("u_color", "vec3"),
            Uniform("u_grainMode", "int"),
        ),
    )

    /** Tile-space quad shared by merge and read-modify-write passes. */
    val TILE_VERT = """
        $VERSION_LINE
        precision highp float;
        layout(location = $ATTR_UV) in vec3 a_uvw;
        out vec2 v_uv;
        void main() {
            v_uv = a_uvw.xy;
            gl_Position = vec4(a_uvw.xy * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    // ------------------------------------------- read-modify-write tools (§7.6)

    private val RMW_MASK_GLSL = """
        float rmwMask(vec2 canvas, vec4 dab) {
            float d = distance(canvas, dab.xy);
            float feather = max(fwidth(d), ${DabStamp.GRADIENT_EPSILON});
            float inner = clamp(min(dab.z * dab.w, dab.z - feather), 0.0, dab.z);
            return 1.0 - smoothstep(inner, dab.z, d);
        }
    """.trimIndent().prependIndent("        ")

    private val RMW_SAMPLING_GLSL = """
        vec2 clampLogicalUv(vec2 uv, vec2 texel, vec2 logicalScale) {
            vec2 halfTexel = texel * 0.5;
            return clamp(uv, halfTexel, logicalScale - halfTexel);
        }
    """.trimIndent().prependIndent("        ")

    private val SMUDGE_DEPOSIT_FRAG = """
        $VERSION_LINE
        precision highp float;
        precision highp sampler2D;
        #define MIXLERP mix
        uniform sampler2D u_before;
        uniform sampler2D u_pickup;
        uniform vec2 u_tileOrigin;
        uniform vec2 u_scratchOrigin;
        uniform vec2 u_beforeTexel;
        uniform vec2 u_beforeScale;
        uniform float u_pickupEdge;
        uniform vec4 u_dab;
        uniform float u_strength;
        in vec2 v_uv;
        out vec4 o_color;

        $RMW_MASK_GLSL
        $RMW_SAMPLING_GLSL

        void main() {
            vec2 canvas = u_tileOrigin + v_uv * float($TILE_SIZE);
            vec2 scratchUv = (canvas - u_scratchOrigin) * u_beforeTexel;
            scratchUv = clampLogicalUv(scratchUv, u_beforeTexel, u_beforeScale);
            vec2 pickupUv = (canvas - u_dab.xy) / u_pickupEdge + vec2(0.5);
            vec4 D = texture(u_before, scratchUv);
            vec4 P = texture(u_pickup, pickupUv);
            float w = u_strength * rmwMask(canvas, u_dab);
            float a = mix(D.a, P.a, w);
            if (a < ${SmudgeKernel.ALPHA_EPSILON}) {
                o_color = vec4(0.0);
                return;
            }
            vec3 cD = D.rgb / max(D.a, ${SmudgeKernel.ALPHA_EPSILON});
            vec3 cP = P.rgb / max(P.a, ${SmudgeKernel.ALPHA_EPSILON});
            float t = w * P.a / a;
            vec3 c = MIXLERP(cD, cP, clamp(t, 0.0, 1.0));
            o_color = vec4(c * a, a);
        }
    """.trimIndent()

    val SMUDGE_DEPOSIT = Source(
        name = "smudge-deposit",
        vertex = TILE_VERT,
        fragment = SMUDGE_DEPOSIT_FRAG,
        uniforms = listOf(
            Uniform("u_before", "sampler2D"),
            Uniform("u_pickup", "sampler2D"),
            Uniform("u_tileOrigin", "vec2"),
            Uniform("u_scratchOrigin", "vec2"),
            Uniform("u_beforeTexel", "vec2"),
            Uniform("u_beforeScale", "vec2"),
            Uniform("u_pickupEdge", "float"),
            Uniform("u_dab", "vec4"),
            Uniform("u_strength", "float"),
        ),
    )

    private val SMUDGE_ABSORB_FRAG = """
        $VERSION_LINE
        precision highp float;
        precision highp sampler2D;
        #define MIXLERP mix
        uniform sampler2D u_before;
        uniform sampler2D u_pickup;
        uniform vec2 u_scratchOrigin;
        uniform vec2 u_beforeTexel;
        uniform vec2 u_beforeScale;
        uniform float u_pickupEdge;
        uniform vec4 u_dab;
        uniform float u_pickupRate;
        in vec2 v_uv;
        out vec4 o_color;

        $RMW_MASK_GLSL
        $RMW_SAMPLING_GLSL

        void main() {
            vec2 canvas = u_dab.xy + (v_uv - vec2(0.5)) * u_pickupEdge;
            vec2 scratchUv = (canvas - u_scratchOrigin) * u_beforeTexel;
            scratchUv = clampLogicalUv(scratchUv, u_beforeTexel, u_beforeScale);
            vec4 P = texture(u_pickup, v_uv);
            vec4 L = texture(u_before, scratchUv);
            float w = u_pickupRate * rmwMask(canvas, u_dab);
            float a = mix(P.a, L.a, w);
            if (a < ${SmudgeKernel.ALPHA_EPSILON}) {
                o_color = vec4(0.0);
                return;
            }
            vec3 cP = P.rgb / max(P.a, ${SmudgeKernel.ALPHA_EPSILON});
            vec3 cL = L.rgb / max(L.a, ${SmudgeKernel.ALPHA_EPSILON});
            float t = w * L.a / a;
            vec3 c = MIXLERP(cP, cL, clamp(t, 0.0, 1.0));
            o_color = vec4(c * a, a);
        }
    """.trimIndent()

    val SMUDGE_ABSORB = Source(
        name = "smudge-absorb",
        vertex = TILE_VERT,
        fragment = SMUDGE_ABSORB_FRAG,
        uniforms = listOf(
            Uniform("u_before", "sampler2D"),
            Uniform("u_pickup", "sampler2D"),
            Uniform("u_scratchOrigin", "vec2"),
            Uniform("u_beforeTexel", "vec2"),
            Uniform("u_beforeScale", "vec2"),
            Uniform("u_pickupEdge", "float"),
            Uniform("u_dab", "vec4"),
            Uniform("u_pickupRate", "float"),
        ),
    )

    private val BLUR_HORIZONTAL_FRAG = """
        $VERSION_LINE
        precision highp float;
        precision highp sampler2D;
        #define MAX_BLUR_RADIUS ${BlurKernel.MAX_RADIUS}
        uniform sampler2D u_source;
        uniform vec2 u_sourceScale;
        uniform vec2 u_texel;
        uniform int u_radius;
        in vec2 v_uv;
        out vec4 o_color;

        $RMW_SAMPLING_GLSL

        void main() {
            vec2 sourceUv = v_uv * u_sourceScale;
            vec4 sum = vec4(0.0);
            for (int i = -MAX_BLUR_RADIUS; i <= MAX_BLUR_RADIUS; i++) {
                if (abs(i) > u_radius) continue;
                vec2 tapUv = sourceUv + vec2(float(i) * u_texel.x, 0.0);
                sum += texture(
                    u_source,
                    clampLogicalUv(tapUv, u_texel, u_sourceScale)
                );
            }
            o_color = sum / float(u_radius * 2 + 1);
        }
    """.trimIndent()

    val BLUR_HORIZONTAL = Source(
        name = "blur-horizontal",
        vertex = TILE_VERT,
        fragment = BLUR_HORIZONTAL_FRAG,
        uniforms = listOf(
            Uniform("u_source", "sampler2D"),
            Uniform("u_sourceScale", "vec2"),
            Uniform("u_texel", "vec2"),
            Uniform("u_radius", "int"),
        ),
    )

    private val BLUR_VERTICAL_FRAG = """
        $VERSION_LINE
        precision highp float;
        precision highp sampler2D;
        #define MAX_BLUR_RADIUS ${BlurKernel.MAX_RADIUS}
        uniform sampler2D u_before;
        uniform sampler2D u_horizontal;
        uniform vec2 u_tileOrigin;
        uniform vec2 u_scratchOrigin;
        uniform vec2 u_beforeTexel;
        uniform vec2 u_beforeScale;
        uniform vec2 u_horizontalTexel;
        uniform vec2 u_horizontalScale;
        uniform vec4 u_dab;
        uniform float u_strength;
        uniform int u_radius;
        in vec2 v_uv;
        out vec4 o_color;

        $RMW_MASK_GLSL
        $RMW_SAMPLING_GLSL

        void main() {
            vec2 canvas = u_tileOrigin + v_uv * float($TILE_SIZE);
            vec2 scratchPixel = canvas - u_scratchOrigin;
            vec2 beforeUv = scratchPixel * u_beforeTexel;
            beforeUv = clampLogicalUv(beforeUv, u_beforeTexel, u_beforeScale);
            vec2 horizontalUv = scratchPixel * u_horizontalTexel;
            vec4 sum = vec4(0.0);
            for (int i = -MAX_BLUR_RADIUS; i <= MAX_BLUR_RADIUS; i++) {
                if (abs(i) > u_radius) continue;
                vec2 tapUv = horizontalUv + vec2(0.0, float(i) * u_horizontalTexel.y);
                sum += texture(
                    u_horizontal,
                    clampLogicalUv(tapUv, u_horizontalTexel, u_horizontalScale)
                );
            }
            vec4 blurred = sum / float(u_radius * 2 + 1);
            vec4 original = texture(u_before, beforeUv);
            float w = u_strength * rmwMask(canvas, u_dab);
            o_color = mix(original, blurred, w);
        }
    """.trimIndent()

    val BLUR_VERTICAL = Source(
        name = "blur-vertical",
        vertex = TILE_VERT,
        fragment = BLUR_VERTICAL_FRAG,
        uniforms = listOf(
            Uniform("u_before", "sampler2D"),
            Uniform("u_horizontal", "sampler2D"),
            Uniform("u_tileOrigin", "vec2"),
            Uniform("u_scratchOrigin", "vec2"),
            Uniform("u_beforeTexel", "vec2"),
            Uniform("u_beforeScale", "vec2"),
            Uniform("u_horizontalTexel", "vec2"),
            Uniform("u_horizontalScale", "vec2"),
            Uniform("u_dab", "vec4"),
            Uniform("u_strength", "float"),
            Uniform("u_radius", "int"),
        ),
    )

    // ----------------------------------------------------------- merge (§7.4)

    /**
     * `mergeStroke` and its uniforms, split out so `merge.frag` and — with
     * 2.5's front-buffered path — `preview.frag` share **one** copy. §7.5
     * requires the preview and the commit to run the same arithmetic on the
     * same inputs, and two transcriptions of §7.4's table would be two chances
     * to diverge.
     *
     * Substituted textually rather than through a GLSL `#include`, which ES 3.0
     * has no preprocessor support for.
     *
     * `MIXLERP` is the compile-time mixing variant of §7.4. [mergeMix] and
     * [previewMix] replace the plain define with the licensed shader and LUT
     * sampler; plain strokes keep this source and pay no LUT cost.
     *
     * **One deviation from the document's own skeleton, following its table.**
     * §7.4's table says of ERASE "(alpha-lock: the eraser is a no-op on locked
     * layers — 05 §1)", but the `merge.frag` skeleton beneath it returns
     * `L * (1.0 - S.a)` unconditionally. Transcribing the skeleton would erase
     * through a lock on the GPU while `StrokeMerge` refused to on the CPU, so
     * the branch consults `u_alphaLock`.
     */
    val MERGE_GLSL = """
        #define MIXLERP mix
        uniform int   u_strokeMode;
        uniform float u_strokeOpacity;
        uniform float u_dilution;
        uniform bool  u_alphaLock;

        vec4 fetchTile(sampler2DArray page, float slice, vec2 uv) {
            return slice < 0.0 ? vec4(0.0) : texture(page, vec3(uv, slice));
        }

        vec4 mergeStroke(vec4 L, vec4 S) {
            if (S.a > u_strokeOpacity) S *= u_strokeOpacity / S.a;
            if (u_strokeMode == 1) return u_alphaLock ? L : L * (1.0 - S.a);
            if (u_strokeMode == 0) {
                if (u_alphaLock) return vec4(S.rgb * L.a + L.rgb * (1.0 - S.a), L.a);
                return S + L * (1.0 - S.a);
            }
            float aOut = S.a + L.a * (1.0 - S.a);
            float t = aOut > 0.0 ? S.a / aOut : 0.0;
            if (L.a > 0.0) t *= 1.0 - u_dilution;
            if (u_alphaLock) { t = S.a; aOut = L.a; }
            if (aOut <= 0.0) return vec4(0.0);
            if (L.a <= 0.0) return S.a <= 0.0 ? vec4(0.0) : S;
            if (S.a <= 0.0) return L;
            vec3 cL = L.rgb / L.a;
            vec3 cS = S.rgb / S.a;
            vec3 c = MIXLERP(cL, cS, clamp(t, 0.0, 1.0));
            return vec4(c * aOut, aOut);
        }
    """.trimIndent()

    /**
     * §7.4's `merge.frag`: one 256×256 quad per key, reading the layer tile and
     * the stroke tile and writing the layer tile's replacement.
     *
     * Because it reads `L` and writes `L`, `MergePass` ping-pongs into a
     * scratch slice taken with `allocateNotOn` and swaps handles — §2.1's rule
     * that a pass never renders into a slice of a page it samples.
     */
    val MERGE_FRAG = listOf(
        VERSION_LINE,
        "precision highp float;",
        "precision highp sampler2DArray;",
        // Concatenated rather than interpolated into a raw string: `trimIndent`
        // computes the common indent across every line of the *result*, so an
        // already-unindented include spliced into an indented literal would
        // strip the literal's own indentation with it.
        // merge.frag's own plumbing. It lives here rather than in the shared
        // include because `preview.frag` supplies its own — §7.5 gives the
        // preview three array samplers (`u_tiles`, `u_strokePage`,
        // `u_tailPage`) and per-vertex slices — and a uniform a program never
        // reads is optimised out, leaving `glGetUniformLocation` at -1, which
        // `GlProgram` throws on. AGENTS.md records that trap from `u_viewport`.
        "uniform sampler2DArray u_layerPage;",
        "uniform sampler2DArray u_strokePage;",
        "uniform float u_layerSlice;",
        "uniform float u_strokeSlice;",
        MERGE_GLSL,
        "in vec2 v_uv;",
        "out vec4 o_color;",
        "void main() {",
        "    o_color = mergeStroke(fetchTile(u_layerPage, u_layerSlice, v_uv),",
        "                          fetchTile(u_strokePage, u_strokeSlice, v_uv));",
        "}",
    ).joinToString("\n")

    val MERGE = Source(
        name = "merge",
        vertex = TILE_VERT,
        fragment = MERGE_FRAG,
        uniforms = listOf(
            Uniform("u_layerPage", "sampler2DArray"),
            Uniform("u_strokePage", "sampler2DArray"),
            Uniform("u_layerSlice", "float"),
            Uniform("u_strokeSlice", "float"),
            Uniform("u_strokeMode", "int"),
            Uniform("u_strokeOpacity", "float"),
            Uniform("u_dilution", "float"),
            Uniform("u_alphaLock", "bool"),
        ),
    )

    // --------------------------------------------- tile-space composite (§4)

    /** One sandwich/structural pass: backdrop plus one layer into a new slice. */
    val TILE_COMPOSITE_FRAG = listOf(
        VERSION_LINE,
        "precision highp float;",
        "precision highp sampler2DArray;",
        "uniform sampler2DArray u_sourcePage;",
        "uniform sampler2DArray u_backdropPage;",
        "uniform float u_sourceSlice;",
        "uniform float u_backdropSlice;",
        "uniform int u_blend;",
        "uniform float u_opacity;",
        BLEND_GLSL,
        "in vec2 v_uv;",
        "out vec4 o_color;",
        "void main() {",
        "    vec4 b = texture(u_backdropPage, vec3(v_uv, u_backdropSlice));",
        "    vec4 s = texture(u_sourcePage, vec3(v_uv, u_sourceSlice)) * u_opacity;",
        "    o_color = blendLayer(b, s, u_blend);",
        "}",
    ).joinToString("\n")

    val TILE_COMPOSITE = Source(
        name = "tile-composite",
        vertex = TILE_VERT,
        fragment = TILE_COMPOSITE_FRAG,
        uniforms = listOf(
            Uniform("u_sourcePage", "sampler2DArray"),
            Uniform("u_backdropPage", "sampler2DArray"),
            Uniform("u_sourceSlice", "float"),
            Uniform("u_backdropSlice", "float"),
            Uniform("u_blend", "int"),
            Uniform("u_opacity", "float"),
        ),
    )

    // ------------------------------------------------- the truthful preview

    /** The stroke buffer's and the tail's slices for this tile; −1 = absent. */
    const val ATTR_STROKE_TAIL_SLICE = 2

    /**
     * §7.5's `preview.frag` vertex half: [COMPOSITE_VERT] plus the slices.
     *
     * The layer's slice rides in `a_uvw.z` as it always has; the stroke
     * buffer's and the tail's cannot, because all three tiles are allocated
     * independently and generally land on different pages at different slices.
     * Two more floats per vertex is the whole cost.
     */
    val PREVIEW_VERT = """
        $VERSION_LINE
        precision highp float;
        layout(location = $ATTR_POS) in vec2 a_canvas;
        layout(location = $ATTR_UV) in vec3 a_uvw;
        layout(location = $ATTR_STROKE_TAIL_SLICE) in vec2 a_strokeTailSlice;
        uniform vec4 u_screen;
        uniform mat4 u_projection;
        uniform mat4 u_bufferTransform;
        out vec3 v_uvw;
        out vec2 v_strokeTailSlice;
        void main() {
            vec2 p = vec2(u_screen.x * a_canvas.x - u_screen.y * a_canvas.y + u_screen.z,
                          u_screen.y * a_canvas.x + u_screen.x * a_canvas.y + u_screen.w);
            gl_Position = u_projection * u_bufferTransform * vec4(p, 0.0, 1.0);
            v_uvw = a_uvw;
            v_strokeTailSlice = a_strokeTailSlice;
        }
    """.trimIndent()

    /**
     * §7.5's `preview.frag`: the live composite's middle pass, drawing the
     * active layer as `mergeStroke(mergeStroke(L, S), T)` before the layer's
     * blend mode and opacity.
     *
     * **Assembled from the same pieces as [COMPOSITE_FRAG]** — [COMPOSITE_HEAD]
     * and [COMPOSITE_TAIL] are spliced into both — because §7.5 defines this
     * shader as `composite.frag` with `#define PREVIEW`, and the promise that
     * "what the user sees mid-stroke is what lands" is only worth anything if
     * the two really are one source. What differs is `sampleLayer()`, and only
     * that.
     *
     * **The merge runs per supersampling tap, not once on the average.** §7.5
     * says so and it is not a detail: `mergeStroke` is non-linear — the opacity
     * cap, the zero-alpha branches and MIX's `t` are all conditionals — so
     * merging the box-filtered average is not the average of the merged taps.
     * At `u_taps > 1` the two differ along every stroke edge, which is exactly
     * where a preview that lied would be seen.
     *
     * The tail's slice is `−1` until 2.5b builds `TailBuffer`; `fetchTile`
     * reads that as transparent and `mergeStroke(x, transparent)` returns `x`
     * unchanged for every mode, so the seam costs nothing while it is empty.
     */
    val PREVIEW_FRAG = listOf(
        COMPOSITE_HEAD,
        "#define PREVIEW",
        MERGE_GLSL,
        """
        uniform sampler2DArray u_tiles;
        uniform sampler2DArray u_strokePage;
        uniform sampler2DArray u_tailPage;
        uniform sampler2D u_backdrop;
        uniform int u_blend;
        uniform float u_opacity;
        uniform int u_taps;
        uniform vec2 u_canvasPerScreen;
        in vec3 v_uvw;
        in vec2 v_strokeTailSlice;
        out vec4 o_color;

        // One tap: the layer, the stroke buffer and the tail at the same offset,
        // merged in §7.5's order. The layer's slice is v_uvw.z as in composite.
        //
        // The LAYER goes through fetchTile too, unlike composite.frag, because
        // the preview draws the union of three key sets: a stroke on blank
        // canvas has a stroke tile and no layer tile, which is the ordinary
        // case on a new document, not an edge case. Sampling an array texture
        // at slice -1 is undefined, so composite's plain `texture()` would read
        // garbage exactly there.
        vec4 previewAt(vec2 uv) {
            vec4 L = fetchTile(u_tiles, v_uvw.z, uv);
            vec4 S = fetchTile(u_strokePage, v_strokeTailSlice.x, uv);
            vec4 T = fetchTile(u_tailPage, v_strokeTailSlice.y, uv);
            return mergeStroke(mergeStroke(L, S), T);
        }

        vec4 sampleLayer() {
            int taps = clamp(u_taps, 1, MAX_TAPS);
            if (taps == 1) return previewAt(v_uvw.xy);
            vec4 acc = vec4(0.0);
            float n = float(taps);
            for (int j = 0; j < MAX_TAPS; j++) {
                if (j >= taps) break;
                for (int i = 0; i < MAX_TAPS; i++) {
                    if (i >= taps) break;
                    vec2 off = ((vec2(float(i), float(j)) + 0.5) / n - 0.5) * u_canvasPerScreen;
                    acc += previewAt(v_uvw.xy + off / float(TILE_PX));
                }
            }
            return acc / (n * n);
        }
        """.trimIndent(),
        COMPOSITE_TAIL,
    ).joinToString("\n")

    val PREVIEW = Source(
        name = "preview",
        vertex = PREVIEW_VERT,
        fragment = PREVIEW_FRAG,
        uniforms = listOf(
            Uniform("u_screen", "vec4"),
            Uniform("u_projection", "mat4"),
            Uniform("u_bufferTransform", "mat4"),
            Uniform("u_tiles", "sampler2DArray"),
            Uniform("u_strokePage", "sampler2DArray"),
            Uniform("u_tailPage", "sampler2DArray"),
            Uniform("u_backdrop", "sampler2D"),
            Uniform("u_blend", "int"),
            Uniform("u_opacity", "float"),
            Uniform("u_taps", "int"),
            Uniform("u_canvasPerScreen", "vec2"),
            Uniform("u_strokeMode", "int"),
            Uniform("u_strokeOpacity", "float"),
            Uniform("u_dilution", "float"),
            Uniform("u_alphaLock", "bool"),
        ),
    )

    /** Builds the pigment merge variant from the verbatim vendored source. */
    fun mergeMix(vendoredGlsl: String): Source = mixingSource(MERGE, vendoredGlsl)

    /** Builds the pigment live-preview variant from the same source. */
    fun previewMix(vendoredGlsl: String): Source = mixingSource(PREVIEW, vendoredGlsl)

    /** Builds the pigment smudge-deposit variant. */
    fun smudgeDepositMix(vendoredGlsl: String): Source = mixingSource(SMUDGE_DEPOSIT, vendoredGlsl)

    /** Builds the pigment pickup variant. */
    fun smudgeAbsorbMix(vendoredGlsl: String): Source = mixingSource(SMUDGE_ABSORB, vendoredGlsl)

    private fun mixingSource(plain: Source, vendoredGlsl: String): Source {
        require(vendoredGlsl.startsWith(MIXBOX_HEADER)) { "Mixbox license header is missing" }
        require(PLAIN_MIX_DEFINE in plain.fragment) { "${plain.name} has no plain mixing seam" }

        val preamble = listOf(
            MIXING_DEFINE,
            PIGMENT_MIX_DEFINE,
            MIXBOX_LUT_DECLARATION,
            vendoredGlsl,
        ).joinToString("\n")

        return plain.copy(
            name = "${plain.name}-mix",
            fragment = plain.fragment.replaceFirst(PLAIN_MIX_DEFINE, preamble),
            uniforms = plain.uniforms + Uniform(MIXBOX_LUT_UNIFORM, "sampler2D"),
        )
    }

    val ALL: List<Source> = listOf(
        COMPOSITE,
        PRESENT,
        CHECKER,
        DAB,
        SMUDGE_DEPOSIT,
        SMUDGE_ABSORB,
        BLUR_HORIZONTAL,
        BLUR_VERTICAL,
        MERGE,
        TILE_COMPOSITE,
        PREVIEW,
    )

    private const val PLAIN_MIX_DEFINE = "#define MIXLERP mix"
    private const val MIXING_DEFINE = "#define BANGNI_MIXING 1"
    private const val PIGMENT_MIX_DEFINE = "#define MIXLERP mixbox_lerp"
    private const val MIXBOX_LUT_UNIFORM = "mixbox_lut"
    private const val MIXBOX_LUT_DECLARATION = "uniform sampler2D $MIXBOX_LUT_UNIFORM;"
    private const val MIXBOX_HEADER = "// ==========================================================\n" +
        "//  MIXBOX 2.0 (c) 2022 Secret Weapons. All rights reserved.\n"

    /**
     * The `switch` body of `blendStraight`, built from [BlendMode] so the two
     * cannot drift.
     *
     * `default` is [BlendMode.NORMAL] — the identity `Cs` — and is genuinely
     * unreachable from the compositor, which takes the hardware source-over
     * path for normal layers and never enters this function. It is here
     * because GLSL ES requires the function to return on every path.
     */
    private fun blendDispatch(indent: Int): String {
        val pad = " ".repeat(indent)
        val cases = BlendMode.entries
            .filter { it != BlendMode.NORMAL }
            .joinToString("\n") { "$pad    case ${it.shaderId}: return ${glslFor(it)};" }
        return buildString {
            append(pad).append("switch (mode) {\n")
            append(cases).append('\n')
            append(pad).append("    default: return cs;\n")
            append(pad).append('}')
        }
    }

    /**
     * `B(Cb, Cs)` for one mode, in straight colour — `docs/plan/05-layers.md`
     * §4's table, which is the normative one.
     *
     * Exhaustive over the enum on purpose: a new [BlendMode] fails to compile
     * here rather than falling through to normal on the GPU.
     */
    private fun glslFor(mode: BlendMode): String = when (mode) {
        // Never emitted — filtered out above — but the `when` must cover it,
        // and `cs` is the correct answer if it ever were.
        BlendMode.NORMAL -> "cs"
        BlendMode.MULTIPLY -> "cb * cs"
        BlendMode.SCREEN -> "cb + cs - cb * cs"
        // overlay = hardlight(cs, cb) with the arguments swapped.
        BlendMode.OVERLAY ->
            "mix(2.0 * cs * cb, 1.0 - 2.0 * (1.0 - cs) * (1.0 - cb), step(0.5, cb))"
        BlendMode.DARKEN -> "min(cb, cs)"
        BlendMode.LIGHTEN -> "max(cb, cs)"
        BlendMode.ADD -> "min(cb + cs, vec3(1.0))"
        BlendMode.DIFFERENCE -> "abs(cb - cs)"
    }
}
