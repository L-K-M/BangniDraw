package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.StrokeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds `dab.frag` and `merge.glsl` to the pure-JVM twins that
 * `docs/plan/03-canvas-engine.md` §15 requires them to match — `DabStamp` and
 * `StrokeMerge`.
 *
 * GLSL cannot run on the JVM, so this cannot compare outputs. What it *can* do
 * is stop the two from drifting in the ways that would be invisible until a
 * device renders wrongly: a falloff term dropped, a merge branch missing, or —
 * the one with no other guard at all — the integers the shader switches on
 * silently ceasing to mean what [StrokeMode] says they mean.
 */
class StrokeShaderContractTest {

    private val dabFrag = Shaders.DAB_FRAG
    private val dabVert = Shaders.DAB_VERT
    private val merge = Shaders.MERGE_GLSL

    /**
     * The mode-1 branch, from its comparison to the next one.
     *
     * A window rather than a single line: a correct branch written across lines
     * — or run through a formatter — must not fail, and the region up to the
     * next mode comparison is a strict superset of that line.
     */
    private fun eraseBranch(body: String): String =
        body.substringAfter("u_strokeMode == 1", "").substringBefore("u_strokeMode ==")

    /**
     * The body of the `u_strokeMode == [mode]` branch: the braced block, brace-
     * matched, or the single statement up to its `;` when the branch is
     * braceless (mode 1 is written that way).
     *
     * Textual position alone cannot tell "after the last mode comparison" from
     * "inside the last mode branch" — a `MIXLERP(` call moved into mode 0's
     * body sits after every `u_strokeMode ==` just as the fall-through does.
     * Matching the braces is what makes the difference decidable, and it is
     * decidable *because* `merge.glsl` uses early returns rather than an
     * else-chain: nothing after mode 0's closing brace is reachable from
     * either branch.
     */
    private fun branchBody(body: String, mode: String): String {
        val at = body.indexOf("u_strokeMode == $mode")
        if (at < 0) return ""
        val brace = body.indexOf('{', at)
        val semi = body.indexOf(';', at)
        // Braceless: `if (...) return expr;` — the statement is the body.
        if (brace < 0 || (semi in 0 until brace)) {
            return if (semi < 0) body.substring(at) else body.substring(at, semi + 1)
        }
        var depth = 0
        for (i in brace until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return body.substring(brace, i + 1)
                }
            }
        }
        return body.substring(brace)
    }

    /** GLSL with comments removed, so a term named only in prose cannot satisfy a check. */
    private fun stripped(source: String): String =
        source
            .replace(Regex("""//[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            // Whitespace runs collapse to one space, so a GLSL reformat cannot
            // fail these checks. Collapsing only ever relaxes matching, and a
            // formatting-only failure is the thing most likely to get a
            // permanent guard weakened or deleted rather than fixed.
            .replace(Regex("""\s+"""), " ")

    // ------------------------------------------------- the mode integers

    @Test
    fun `the integers merge glsl switches on are StrokeMode's ordinals`() {
        // The whole cross-language contract rests on this and nothing else
        // checks it. `u_strokeMode` is uploaded as `mode.ordinal`, so
        // reordering the enum — adding a mode above PAINT, say — would turn
        // every erase into a paint on the GPU while every JVM test stayed
        // green, because the JVM side switches on the enum itself.
        assertEquals(0, StrokeMode.PAINT.ordinal, "merge.glsl reads mode 0 as PAINT")
        assertEquals(1, StrokeMode.ERASE.ordinal, "merge.glsl reads mode 1 as ERASE")
        assertEquals(2, StrokeMode.MIX.ordinal, "merge.glsl reads mode 2 as MIX")
        assertEquals(3, StrokeMode.entries.size, "a new mode needs a branch in merge.glsl")

        val body = stripped(merge)
        assertTrue(
            body.contains("u_strokeMode == 1"),
            "merge.glsl must branch on mode 1 for ERASE",
        )
        assertTrue(
            body.contains("u_strokeMode == 0"),
            "merge.glsl must branch on mode 0 for PAINT",
        )
        // MIX is the fall-through, not an explicit `== 2`, so asserting one
        // would fail against a correct shader. What gets pinned instead is that
        // the fall-through really is MIX, and pinning it needs more than
        // textual order: `lastIndexOf("u_strokeMode ==") < indexOf("MIXLERP(")`
        // alone is satisfied just as well by a MIXLERP call moved INTO mode 0's
        // body, so on its own it is a necessary condition wearing the costume
        // of a sufficient one.
        //
        // Brace-matching each branch makes it sufficient. Neither mode branch
        // may contain the MIX arithmetic, the call must sit past mode 0's
        // closing brace, and both branches must `return` — with early returns
        // rather than an else-chain, those three together mean nothing but
        // mode 2 can reach it.
        assertTrue(
            !body.contains("u_strokeMode == 2"),
            "MIX is the fall-through; if that changes, pin the new form instead",
        )
        val erase = branchBody(body, "1")
        val paint = branchBody(body, "0")
        assertTrue(erase.isNotEmpty() && paint.isNotEmpty(), "both mode branches must be findable: $body")
        assertTrue(!erase.contains("MIXLERP("), "the ERASE branch must not do the MIX arithmetic: $erase")
        assertTrue(!paint.contains("MIXLERP("), "the PAINT branch must not do the MIX arithmetic: $paint")
        assertTrue(erase.contains("return"), "the ERASE branch must return, not fall through: $erase")
        assertTrue(paint.contains("return"), "the PAINT branch must return, not fall through: $paint")
        val paintEnd = body.indexOf(paint) + paint.length
        assertTrue(
            body.indexOf("MIXLERP(") > paintEnd,
            "the MIX arithmetic must sit past both branches, not inside one",
        )
    }

    // ----------------------------------------------------- the merge table

    @Test
    fun `merge glsl caps the buffer at the stroke opacity before anything else`() {
        val body = stripped(merge)
        val cap = body.indexOf("u_strokeOpacity / S.a")
        assertTrue(cap >= 0, "the cap of §7.4 is missing: $body")
        val firstBranch = body.indexOf("u_strokeMode ==")
        assertTrue(
            cap < firstBranch,
            "the cap must run before the mode branches, or ERASE and MIX read an uncapped buffer",
        )
    }

    @Test
    fun `merge glsl implements every branch of section 7 4's table`() {
        val body = stripped(merge)
        // PAINT, alpha-locked: 05 §1's `Cr = s.rgb·d.a + d.rgb·(1 − s.a)`, the
        // reading Composite.alphaLocked pins. The other reading — a clamped
        // source-over — would also compile.
        assertTrue(
            body.contains("S.rgb * L.a + L.rgb * (1.0 - S.a)"),
            "alpha-locked PAINT must be s.rgb·d.a + d.rgb·(1 − s.a)",
        )
        assertTrue(body.contains("S + L * (1.0 - S.a)"), "plain PAINT must be premultiplied source-over")
        // Scoped to the mode-1 branch. Unscoped it could not fail on its own:
        // "S + L * (1.0 - S.a)" CONTAINS "L * (1.0 - S.a)", so the PAINT
        // assertion above already guaranteed it — deleting the scaling from the
        // ERASE branch left this green, which is exactly the drift this suite
        // exists to catch.
        assertTrue(
            eraseBranch(body).contains("L * (1.0 - S.a)"),
            "ERASE must scale the layer by 1 − S.a: ${eraseBranch(body)}",
        )
        // "MIXLERP(" — a call site. Bare "MIXLERP" is satisfied by the
        // `#define MIXLERP mix` alone, so it passed even if no branch called it.
        assertTrue(body.contains("MIXLERP("), "MIX must go through the compile-time mixing variant")
        assertTrue(body.contains("1.0 - u_dilution"), "MIX must apply dilution (09 §3.1)")
    }

    @Test
    fun `the eraser is a no-op on an alpha-locked layer in the shader too`() {
        // §7.4's table says so in a parenthesis — "(alpha-lock: the eraser is a
        // no-op on locked layers — 05 §1)" — but the document's own merge.frag
        // skeleton omits it and returns `L * (1.0 - S.a)` unconditionally.
        // Following the skeleton would erase through a lock on the GPU while
        // StrokeMerge refused to on the CPU, and the preview would disagree
        // with the commit. Pinned here because the shader is the copy that
        // deviates from the plan's literal text.
        val body = stripped(merge)
        val erase = eraseBranch(body)
        assertTrue(
            erase.contains("u_alphaLock"),
            "the ERASE branch must consult u_alphaLock, was: $erase",
        )
    }

    @Test
    fun `merge glsl guards both zero-alpha sides before dividing`() {
        val body = stripped(merge)
        val guardL = body.indexOf("L.a <= 0.0")
        val guardS = body.indexOf("S.a <= 0.0")
        val divide = body.indexOf("L.rgb / L.a")
        assertTrue(guardL >= 0 && guardS >= 0, "§7.4's zero-alpha guards are missing")
        assertTrue(
            guardL < divide && guardS < divide,
            "the guards must precede the straight-colour division, or MIX divides by zero",
        )
    }

    @Test
    fun `only the plain mixing variant is built, and it says so`() {
        // §7.4 makes mixing a compile-time variant. Decision 5 selects RgbMixer,
        // which means the plain `mix` form always; the pigment form needs the
        // Mixbox LUT that 09 §5 owns and this PR does not ship. If a
        // `mixbox_lerp` appeared here without that loader, the program would
        // fail to link on a device and nothing on the JVM would notice.
        assertTrue(Shaders.MERGE_GLSL.contains("#define MIXLERP mix"), "the plain variant must be defined")
        // Case-insensitive and over both constants. MERGE_FRAG does embed
        // MERGE_GLSL verbatim (it is one element of the joined list), so one
        // scan would in fact suffice — but that is a composition detail this
        // test should not silently depend on, and `Mixbox`/`MIXBOX` slipped
        // past a case-sensitive match either way.
        assertTrue(
            Shaders.MERGE_FRAG.contains(Shaders.MERGE_GLSL),
            "MERGE_FRAG must embed MERGE_GLSL, or scanning one says nothing about the other",
        )
        for (source in listOf(Shaders.MERGE_FRAG, Shaders.MERGE_GLSL)) {
            assertTrue(
                !stripped(source).contains("mixbox", ignoreCase = true),
                "no Mixbox reference until 09 §5's LUT loader exists",
            )
        }
    }

    // ------------------------------------------------------- the dab shape

    @Test
    fun `dab frag keeps the one-pixel anti-aliased band at every hardness`() {
        // DabStamp.coverage's `min(r · hardness, r − 1)`. Dropping the `r - 1`
        // term makes inner == r at hardness 1.0, smoothstep degenerates, and
        // every diagonal edge aliases — on a device, invisibly to every JVM
        // test except this one.
        val body = stripped(dabFrag)
        assertTrue(
            body.contains("min(r * v_hardness, r - 1.0)"),
            "the falloff must clamp the plateau to r − 1: $body",
        )
        assertTrue(body.contains("smoothstep(inner, r, d)"), "the falloff must be a smoothstep from inner to r")
        assertTrue(body.contains("1.0 - smoothstep"), "coverage is 1 − smoothstep, not smoothstep")
    }

    @Test
    fun `dab vert clamps sub-pixel radii and compensates with the area weight`() {
        // DabStamp.drawRadius and areaWeight. Keeping the clamp without the
        // weight would make every thin stroke a full-strength 2 px line, which
        // is the more damaging half to lose because it looks deliberate.
        val body = stripped(dabVert)
        assertTrue(body.contains("max(i_radius, 1.0)"), "sub-pixel dabs must be drawn at 1 px")
        assertTrue(
            body.contains("i_radius < 1.0 ? i_radius * i_radius : 1.0"),
            "and dimmed by their true area",
        )
        assertTrue(body.contains("i_flow * area"), "the area weight must scale flow")
    }

    @Test
    fun `dab vert pads the quad so the falloff band is not clipped`() {
        // The band runs to r and the AA needs a pixel beyond it; a quad sized
        // at exactly r would cut the softest part of every dab off square.
        assertTrue(stripped(dabVert).contains("r + 1.0"), "the quad must be padded past the radius")
    }

    @Test
    fun `dab vert unwarps the ellipse so the fragment can treat it as a circle`() {
        val body = stripped(dabVert)
        assertTrue(
            body.contains("dot(d, axisMinor) / i_aspect"),
            "the minor axis must be divided by aspect (§7.3's v_local)",
        )
    }

    @Test
    fun `colour is a stroke uniform, never a per-dab attribute`() {
        // §6: "Colour and the stroke opacity are per stroke (uniforms), never
        // per dab", and the eight per-dab fields are DAB_STRIDE. §7.3's snippet
        // shows i_color as `layout(location = 7) in vec3`, which would be a
        // ninth field and 12 bytes per dab carrying the same value 1 024 times.
        val body = stripped(dabVert)
        assertTrue(body.contains("uniform vec3 u_color"), "colour must be a uniform")
        // Any vecN: banning only the vec3 spelling let the ninth per-dab field
        // return through a one-word type change.
        assertTrue(
            !body.contains(Regex("""in\s+vec[234]\s+i_color""")),
            "colour must not be a per-instance attribute",
        )
    }

    @Test
    fun `an eraser needs no separate dab shader`() {
        // §7.3: erasers run this exact shader with u_color = 0, so the buffer
        // accumulates coverage in alpha. That only holds if the colour reaches
        // the output multiplied by coverage and nothing else re-introduces it.
        val body = stripped(dabFrag)
        assertTrue(body.contains("o_color = v_color * m"), "the output is colour × coverage, nothing more")
    }
}
