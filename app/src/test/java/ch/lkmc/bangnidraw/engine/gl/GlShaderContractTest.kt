package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.PerfConstants
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
        assertTrue(
            "layout(location = ${Shaders.ATTR_POS}) in vec2 a_canvas;" in Shaders.PRESENT.vertex,
            "present a_canvas must be at ATTR_POS (${Shaders.ATTR_POS})",
        )
        assertTrue(
            "layout(location = ${Shaders.ATTR_UV}) in vec3 a_uvw;" in Shaders.PRESENT.vertex,
            "present a_uvw must be at ATTR_UV (${Shaders.ATTR_UV})",
        )
        assertTrue(Shaders.ATTR_POS != Shaders.ATTR_UV, "two attributes cannot share a location")
    }

    @Test
    fun `only the window present shader flips source texture rows`() {
        val sourceRowFlip = "vec3(a_uvw.x, 1.0 - a_uvw.y, a_uvw.z)"
        val assignments = Regex("""v_uvw\s*=\s*([^;]+);""")
            .findAll(Shaders.PRESENT.vertex)
            .map { it.groupValues[1] }
            .toList()

        assertEquals(
            listOf(sourceRowFlip),
            assignments,
            "Accum's top row must be sampled while the window target writes buffer row zero",
        )
        assertTrue(
            sourceRowFlip !in Shaders.COMPOSITE_VERT,
            "offscreen compositing keeps the engine's existing y-down row convention",
        )
        assertTrue(
            Shaders.PRESENT.vertex != Shaders.COMPOSITE_VERT,
            "the window target needs a present-only vertex convention",
        )
        assertTrue(
            "gl_Position = u_projection * u_bufferTransform * vec4(p, 0.0, 1.0);" in
                Shaders.PRESENT.vertex,
            "presented pixels must use the same buffer transform as live damage",
        )
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
    fun `the tile compositor shares every blend formula`() {
        assertTrue(Shaders.BLEND_GLSL in Shaders.COMPOSITE_FRAG)
        assertTrue(Shaders.BLEND_GLSL in Shaders.TILE_COMPOSITE_FRAG)
        for (mode in BlendMode.entries) {
            if (mode == BlendMode.NORMAL) continue

            assertTrue(
                "case ${mode.shaderId}:" in Shaders.TILE_COMPOSITE_FRAG,
                "tile composite has no ${mode.name} branch",
            )
        }
        assertTrue("uniform sampler2DArray u_backdropPage;" in Shaders.TILE_COMPOSITE_FRAG)
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
    fun `the tap count is clamped, so the sample count always matches the divisor`() {
        // The bug this pins: the loops stop at MAX_TAPS while the average
        // divides by u_taps squared, so a u_taps above MAX_TAPS sums 16
        // samples and divides by more — the whole layer dims, with no GL
        // error and no disagreement with the CPU oracle that anything checks.
        // A u_taps of 0 is worse: no samples, and 0.0/0.0 is NaN straight into
        // the blend. Clamping once and using the clamped value for BOTH is
        // what makes the shader correct for any value the binder can send —
        // and the binder does not exist yet, so nothing else could enforce it.
        //
        // (The constant loop bound is kept for conservative drivers. GLSL ES
        // 3.00 does allow a dynamic bound — it is ES 1.00 that required a
        // compile-time-constant one — so this is a preference, not a rule.)
        assertTrue(
            "int taps = clamp(u_taps, 1, MAX_TAPS);" in Shaders.COMPOSITE_FRAG,
            "u_taps must be clamped before it is used",
        )
        assertTrue(
            "float n = float(taps);" in Shaders.COMPOSITE_FRAG &&
                "return acc / (n * n);" in Shaders.COMPOSITE_FRAG,
            "the divisor must come from the clamped count, not from u_taps",
        )
        assertTrue(
            "if (j >= taps) break;" in Shaders.COMPOSITE_FRAG &&
                "if (i >= taps) break;" in Shaders.COMPOSITE_FRAG,
            "the loops must break at the clamped count, not at u_taps",
        )
        // After the clamp statement itself — `substringAfter("int taps = clamp")`
        // starts at "(u_taps, …" and can never be clean.
        assertTrue(
            "u_taps" !in stripComments(Shaders.COMPOSITE_FRAG)
                .substringAfter("int taps = clamp(u_taps, 1, MAX_TAPS);"),
            "nothing after the clamp may read the raw u_taps again",
        )
        assertTrue(
            "if (taps == 1) return texture(u_tiles, v_uvw);" in Shaders.COMPOSITE_FRAG,
            "the single-tap case must skip the loop entirely",
        )
        assertTrue(
            "#define MAX_TAPS ${Shaders.MAX_TAPS}" in Shaders.COMPOSITE_FRAG,
            "the GLSL cap must be the Kotlin constant, not a literal",
        )
    }

    @Test
    fun `the tile size in the shader is the engine's constant, not a literal`() {
        // The canvas-px to tile-uv conversion divides by the tile size. As a
        // literal 256.0 inside a GLSL string it is invisible to every compiler
        // and every other test: change PerfConstants.TILE_SIZE and the filter
        // footprint silently becomes wrong.
        assertTrue(
            "#define TILE_PX ${PerfConstants.TILE_SIZE}" in Shaders.COMPOSITE_FRAG,
            "TILE_PX must be defined from PerfConstants.TILE_SIZE",
        )
        // Past the #define's own value, for the same reason — and the
        // forbidden literal is DERIVED, not written as 256. Hardcoding it made
        // the guard vacuous in the one scenario it exists for: on the day
        // TILE_SIZE becomes 512, a check for "256" finds only stale values and
        // waves a fresh `512.0` through. Comments are stripped for the same
        // reason the u_viewport ban is checked over declarations rather than
        // raw text — prose that names the number is not a use of it.
        assertTrue(
            "${PerfConstants.TILE_SIZE}" !in stripComments(Shaders.COMPOSITE_FRAG)
                .substringAfter("#define TILE_PX ${PerfConstants.TILE_SIZE}"),
            "the tile size must not also appear as a literal in the body",
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
    fun `the composite vertex accepts an affine image transform`() {
        assertTrue("uniform vec4 u_screenBasis;" in Shaders.COMPOSITE_VERT)
        assertTrue("uniform vec2 u_screenTranslation;" in Shaders.COMPOSITE_VERT)
        assertTrue(
            "u_screenBasis.x * a_canvas.x + u_screenBasis.y * a_canvas.y" in
                Shaders.COMPOSITE_VERT,
        )
        assertTrue(
            "u_screenBasis.z * a_canvas.x + u_screenBasis.w * a_canvas.y" in
                Shaders.COMPOSITE_VERT,
        )
    }

    @Test
    fun `no source declares a uniform the plan dropped`() {
        // u_viewport is in the §3.1 snippet and is NOT in the shader: the
        // projection is built on the JVM from the viewport, so the shader
        // never reads it, and a declared-but-unread uniform links to -1.
        // Re-adding it from the plan would break startup on every device.
        for (s in sources) {
            // Over declarations, not raw text: as a substring check this failed
            // on any comment that named u_viewport — including the comment
            // explaining why it is banned, which the message below points at.
            assertTrue(
                declaredUniforms(s.vertex + "\n" + s.fragment).none { it.first == "u_viewport" },
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

    /**
     * Every `uniform` the source declares, as `name to type`.
     *
     * Comments are stripped first so a commented-out declaration cannot
     * register as a real one; the optional precision qualifier is matched so
     * `uniform highp vec4 u_x;` — legal, and one edit away — does not report a
     * disagreement that does not exist; and the optional array suffix is
     * matched because **not** matching it is fail-OPEN for one of this file's
     * two callers, not fail-safe as an earlier version of this KDoc claimed.
     * An unmatched declaration makes the bidirectional test fail (the sets
     * differ) but makes the `u_viewport` ban *pass*: `none { … }` over a set
     * that never captured `uniform vec4 u_viewport[2];` is trivially true, and
     * the banned uniform slips through the check that was hardened to stop it.
     */
    private fun declaredUniforms(source: String): Set<Pair<String, String>> =
        Regex(
            """^\s*uniform\s+(?:(?:lowp|mediump|highp)\s+)?(\w+)\s+(\w+)\s*(?:\[\w*\])?\s*;""",
            RegexOption.MULTILINE,
        )
            .findAll(stripComments(source))
            .map { it.groupValues[2] to it.groupValues[1] }
            .toSet()

    /**
     * The source with its comments and its declarations removed — what is left
     * is code that could actually *read* a uniform.
     *
     * Stripping comments is the point. Without it a uniform named only in a
     * comment ("// u_taps is clamped to 4 here") satisfies the
     * "actually read" assertion, the driver strips the uniform anyway,
     * `glGetUniformLocation` returns -1 and `GlProgram.link` throws on a
     * device — the exact failure that assertion exists to catch, passing.
     */
    private fun stripDeclarations(source: String): String = stripComments(source)
        .lineSequence()
        .filterNot { Regex("""^\s*(uniform|in|out|layout)\b""").containsMatchIn(it) }
        .joinToString("\n")

    private fun stripComments(source: String): String =
        source.replace(Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
}
