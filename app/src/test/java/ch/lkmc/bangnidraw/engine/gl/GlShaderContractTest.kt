package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.BlendMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shader contract (`docs/plan/11-testing.md` §4, ported from Meltorama).
 *
 * CI cannot run a shader, so these hold the *source* to what the Kotlin side
 * assumes. Each assertion's comment names the failure it prevents, because
 * every one of them is a failure that compiles cleanly on both sides and only
 * shows up as a wrong picture on a device.
 *
 * **What this PR can pin, and what it cannot.** `11-testing.md` §4 also lists
 * assertions over `merge.frag`, `preview.frag`, `u_strokeMode`, `u_alphaLock`,
 * the `#include` substitution, the `mixbox_lut` sampler and the `*_mix`
 * variants. Those shaders do not exist yet — `StrokeBuffer`, `DabPass`,
 * `MergePass` and `SmudgePass` are roadmap 2.4 and 2.5 — so asserting over
 * them here would mean asserting over nothing, which is the vacuous-test
 * failure mode this suite exists to avoid. They land with their shaders.
 */
class GlShaderContractTest {

    private val sources = Shaders.ALL

    @Test
    fun `every source starts with the ES 3 version line`() {
        // A missing #version makes the driver parse the source as ES 2, where
        // `in`/`out`, texture arrays and texelFetch do not exist — a wall of
        // syntax errors pointing at the wrong thing.
        for (s in sources) {
            for ((what, text) in listOf("${s.name}.vert" to s.vertex, "${s.name}.frag" to s.fragment)) {
                assertEquals(
                    Shaders.VERSION_LINE,
                    text.lineSequence().first(),
                    "$what must open with ${Shaders.VERSION_LINE}",
                )
            }
        }
    }

    @Test
    fun `every uniform the binder looks up is declared, and every declared one is looked up`() {
        // Both directions, and the second is the one that bites. A uniform
        // declared and never read is optimized out, so glGetUniformLocation
        // returns -1 and GlProgram.link throws at startup on a device — while
        // the JVM sees a perfectly consistent pair of lists.
        for (s in sources) {
            val declared = declaredUniforms(s.vertex) + declaredUniforms(s.fragment)
            val expected = s.uniforms.map { it.name to it.type }.toSet()
            assertEquals(
                expected,
                declared,
                "${s.name}: the uniform list and the GLSL disagree",
            )
        }
    }

    @Test
    fun `every uniform in the list is actually read by the source`() {
        // The other half of the -1 trap: declared AND listed, but never used
        // in a body. The driver still strips it.
        for (s in sources) {
            for (u in s.uniforms) {
                val body = stripDeclarations(s.vertex) + "\n" + stripDeclarations(s.fragment)
                assertTrue(
                    Regex("""\b${Regex.escape(u.name)}\b""").containsMatchIn(body),
                    "${s.name} declares ${u.name} and never reads it — it will link to -1",
                )
            }
        }
    }

    @Test
    fun `the attribute locations are the ones the VAOs bind`() {
        // A VAO binds by index; the shader declares by index. If the two drift
        // the geometry arrives in the wrong attribute and the canvas draws a
        // single degenerate triangle, with no error anywhere.
        assertTrue(
            "layout(location = ${Shaders.ATTR_POS}) in vec2 a_canvas;" in Shaders.COMPOSITE_VERT,
            "a_canvas must be at ATTR_POS (${Shaders.ATTR_POS})",
        )
        assertTrue(
            "layout(location = ${Shaders.ATTR_UV}) in vec3 a_uvw;" in Shaders.COMPOSITE_VERT,
            "a_uvw must be at ATTR_UV (${Shaders.ATTR_UV})",
        )
        assertTrue(Shaders.ATTR_POS != Shaders.ATTR_UV, "two attributes cannot share a location")
    }

    @Test
    fun `the blend dispatch has a case for every non-normal mode`() {
        // The failure this catches is a mode silently falling through to
        // normal: the layer panel offers Overlay, the canvas draws Normal, and
        // nothing anywhere reports a problem.
        for (mode in BlendMode.entries) {
            if (mode == BlendMode.NORMAL) continue
            assertTrue(
                "case ${mode.shaderId}:" in Shaders.COMPOSITE_FRAG,
                "no case for ${mode.name} (shaderId ${mode.shaderId}) in the blend dispatch",
            )
        }
    }

    @Test
    fun `normal takes the hardware path and is not a case in the dispatch`() {
        // §3.2: a Normal layer is drawn with glBlendFunc(ONE,
        // ONE_MINUS_SRC_ALPHA) and never reads the backdrop. A `case 0:` in
        // the switch would mean somebody wired Normal through the slow path.
        assertTrue(
            "case ${BlendMode.NORMAL.shaderId}:" !in Shaders.COMPOSITE_FRAG,
            "NORMAL must not have a case: it is the hardware blend path",
        )
        assertTrue(
            "if (u_blend == ${BlendMode.NORMAL.shaderId})" in Shaders.COMPOSITE_FRAG,
            "the fragment shader must short-circuit on NORMAL's shaderId",
        )
    }

    @Test
    fun `the dispatch has exactly one case per mode and no orphan case`() {
        // A hand-edited `case 8:` for a mode that no longer exists, or two
        // cases with the same id, would both pass the per-mode check above.
        val cases = Regex("""case (\d+):""").findAll(Shaders.COMPOSITE_FRAG)
            .map { it.groupValues[1].toInt() }
            .toList()
        val expected = BlendMode.entries.filter { it != BlendMode.NORMAL }.map { it.shaderId }
        assertEquals(expected.sorted(), cases.sorted(), "the dispatch's cases are not the enum's ids")
        assertEquals(cases.size, cases.toSet().size, "a shaderId appears twice in the dispatch")
    }

    @Test
    fun `the blend formulas are the ones 05-layers declares`() {
        // The GLSL is generated from BlendMode, so a missing mode cannot
        // compile — but a WRONG formula still can. These are 05 §4's table,
        // and they are also what Composite.kt implements on Int pixels
        // (PLAN.md §7): when one changes, both change, and this fails first.
        val expected = mapOf(
            BlendMode.MULTIPLY to "cb * cs",
            BlendMode.SCREEN to "cb + cs - cb * cs",
            BlendMode.DARKEN to "min(cb, cs)",
            BlendMode.LIGHTEN to "max(cb, cs)",
            BlendMode.ADD to "min(cb + cs, vec3(1.0))",
            BlendMode.DIFFERENCE to "abs(cb - cs)",
            BlendMode.OVERLAY to
                "mix(2.0 * cs * cb, 1.0 - 2.0 * (1.0 - cs) * (1.0 - cb), step(0.5, cb))",
        )
        for ((mode, formula) in expected) {
            assertTrue(
                "case ${mode.shaderId}: return $formula;" in Shaders.COMPOSITE_FRAG,
                "${mode.name} is not `$formula` in the shader",
            )
        }
        assertEquals(
            BlendMode.entries.size - 1,
            expected.size,
            "a BlendMode was added without pinning its GLSL formula here",
        )
    }

    @Test
    fun `the composite shader samples the tile page as a texture array`() {
        // The pool is texture-array pages (§2.1); a sampler2D here would mean
        // somebody rewrote the compositor for one texture per tile, which is
        // the design §2.3 rejects by name.
        assertTrue(
            "uniform sampler2DArray u_tiles;" in Shaders.COMPOSITE_FRAG,
            "u_tiles must be a sampler2DArray",
        )
        assertTrue(
            "precision highp sampler2DArray;" in Shaders.COMPOSITE_FRAG,
            "sampler2DArray has no default precision in the fragment stage",
        )
    }

    @Test
    fun `the backdrop is read by texelFetch, not by texture`() {
        // The backdrop is the Scratch copy of Accum at THIS pixel (§3.3).
        // Sampling it with `texture` and a uv would filter across the
        // neighbouring pixels of the backdrop, which is a different image.
        assertTrue(
            "texelFetch(u_backdrop, ivec2(gl_FragCoord.xy), 0)" in Shaders.COMPOSITE_FRAG,
            "the backdrop must be fetched at the fragment's own texel",
        )
    }

    @Test
    fun `the supersample loops are bounded by a constant, not by u_taps`() {
        // GLSL ES 3.0 requires loop bounds a compiler can unroll; `i < u_taps`
        // with a uniform bound is rejected by some drivers and silently
        // unrolled to 1 iteration by others. §3.4's shader uses a constant
        // bound of 4 with an early break, and this pins that shape.
        assertTrue(
            "for (int j = 0; j < 4; j++)" in Shaders.COMPOSITE_FRAG &&
                "for (int i = 0; i < 4; i++)" in Shaders.COMPOSITE_FRAG,
            "the tap loops must have constant bounds",
        )
        assertTrue(
            "if (j >= u_taps) break;" in Shaders.COMPOSITE_FRAG &&
                "if (i >= u_taps) break;" in Shaders.COMPOSITE_FRAG,
            "the tap loops must break at u_taps",
        )
        assertTrue(
            "if (u_taps == 1) return texture(u_tiles, v_uvw);" in Shaders.COMPOSITE_FRAG,
            "the single-tap case must skip the loop entirely",
        )
    }

    @Test
    fun `the vertex shader applies the buffer transform before the projection`() {
        // graphics-core's transform is meant to be consumed in BUFFER PIXEL
        // space, before an ortho of bufferInfo.width x height. Reversing the
        // two puts a pre-rotated device's canvas off screen — and it looks
        // perfectly right on a device that never pre-rotates.
        assertTrue(
            "gl_Position = u_projection * u_bufferTransform * vec4(p, 0.0, 1.0);"
                in Shaders.COMPOSITE_VERT,
            "the order must be projection x transform x pixelPos",
        )
    }

    @Test
    fun `no source declares a uniform the plan dropped`() {
        // u_viewport is in the §3.1 snippet and is NOT in the shader: the
        // projection is built on the JVM from the viewport, so the shader
        // never reads it, and a declared-but-unread uniform links to -1.
        // Re-adding it from the plan would break startup on every device.
        for (s in sources) {
            assertTrue(
                "u_viewport" !in s.vertex && "u_viewport" !in s.fragment,
                "${s.name} declares u_viewport, which nothing reads — see COMPOSITE_VERT's KDoc",
            )
        }
    }

    @Test
    fun `program names are distinct`() {
        // The name is what a link failure is reported under and what a future
        // program cache would key on.
        val names = sources.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate program name in $names")
    }

    private fun declaredUniforms(source: String): Set<Pair<String, String>> =
        Regex("""^\s*uniform\s+(\w+)\s+(\w+)\s*;""", RegexOption.MULTILINE)
            .findAll(source)
            .map { it.groupValues[2] to it.groupValues[1] }
            .toSet()

    /** The source with its `uniform`, `in` and `out` declarations removed. */
    private fun stripDeclarations(source: String): String = source.lineSequence()
        .filterNot { Regex("""^\s*(uniform|in|out|layout)\b""").containsMatchIn(it) }
        .joinToString("\n")
}
