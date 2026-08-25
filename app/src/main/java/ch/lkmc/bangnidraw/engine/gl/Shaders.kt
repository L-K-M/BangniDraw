package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.BlendMode

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
    val COMPOSITE_FRAG = """
        $VERSION_LINE
        precision highp float;
        precision highp sampler2DArray;
        uniform sampler2DArray u_tiles;
        uniform sampler2D u_backdrop;
        uniform int u_blend;
        uniform float u_opacity;
        uniform int u_taps;
        uniform vec2 u_canvasPerScreen;
        in vec3 v_uvw;
        out vec4 o_color;

        vec4 sampleLayer() {
            if (u_taps == 1) return texture(u_tiles, v_uvw);
            // Box filter over a u_taps x u_taps grid spanning one screen pixel,
            // offsets in canvas px converted to tile uv (256 px per tile).
            vec4 acc = vec4(0.0);
            float n = float(u_taps);
            for (int j = 0; j < 4; j++) {
                if (j >= u_taps) break;
                for (int i = 0; i < 4; i++) {
                    if (i >= u_taps) break;
                    vec2 off = ((vec2(float(i), float(j)) + 0.5) / n - 0.5) * u_canvasPerScreen;
                    acc += texture(u_tiles, vec3(v_uvw.xy + off / 256.0, v_uvw.z));
                }
            }
            return acc / (n * n);
        }

        // Straight-alpha separable blend functions B(Cb, Cs), per channel.
        vec3 blendStraight(int mode, vec3 cb, vec3 cs) {
        ${blendDispatch(indent = 12)}
        }

        void main() {
            vec4 s = sampleLayer() * u_opacity;
            if (u_blend == ${BlendMode.NORMAL.shaderId}) { o_color = s; return; }
            vec4 b = texelFetch(u_backdrop, ivec2(gl_FragCoord.xy), 0);
            vec3 cb = b.a > 0.0 ? b.rgb / b.a : vec3(0.0);
            vec3 cs = s.a > 0.0 ? s.rgb / s.a : vec3(0.0);
            vec3 f = blendStraight(u_blend, cb, cs);
            // W3C compositing, premultiplied:
            // co = cs'(1-ab) + cb'(1-as) + as*ab*B(Cb,Cs)
            vec3 co = s.rgb * (1.0 - b.a) + b.rgb * (1.0 - s.a) + s.a * b.a * f;
            float ao = s.a + b.a * (1.0 - s.a);
            o_color = vec4(co, ao);
        }
    """.trimIndent()

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
     * The transparent-paper checkerboard of §3.2 step 1.
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
    val ALL: List<Source> = listOf(COMPOSITE, PRESENT, CHECKER)

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
