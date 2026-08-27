package ch.lkmc.bangnidraw.engine.core

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `docs/plan/03-canvas-engine.md` §7.2 and §7.3 — the dab's shape and the
 * stroke buffer's blend, which are what `DabPass` reproduces in GL.
 *
 * §7.2 promises "dab overlap within a batch is deterministic and identical to
 * the CPU reference `DabStamp`". These tests are the half of that promise the
 * JVM can check; `GlShaderContractTest` checks the shader carries the same
 * constants and the same falloff expression.
 */
class DabStampTest {

    private val white = floatArrayOf(1f, 1f, 1f)

    private fun dab(
        x: Float = 0f,
        y: Float = 0f,
        radius: Float = 10f,
        flow: Float = 1f,
        hardness: Float = 0.8f,
        angle: Float = 0f,
        aspect: Float = 1f,
    ) = Dab(x, y, radius, flow, hardness, angle, aspect, seed = 0f)

    // ------------------------------------------------------------- falloff

    @Test
    fun `coverage is full at the centre and gone at the rim`() {
        assertEquals(1f, DabStamp.coverage(0f, 10f, 0.8f), 1e-6f, "the centre is fully covered")
        assertEquals(0f, DabStamp.coverage(10f, 10f, 0.8f), 1e-6f, "the rim is not covered at all")
        assertEquals(0f, DabStamp.coverage(11f, 10f, 0.8f), 1e-6f, "outside the rim is not covered")
    }

    @Test
    fun `coverage falls monotonically from centre to rim`() {
        for (hardness in floatArrayOf(0f, 0.5f, 0.8f, 1f)) {
            var previous = Float.MAX_VALUE
            var d = 0f
            while (d <= 12f) {
                val c = DabStamp.coverage(d, 10f, hardness)
                assertTrue(
                    c <= previous + 1e-6f,
                    "hardness $hardness: coverage rose from $previous to $c at d=$d",
                )
                assertTrue(c in 0f..1f, "hardness $hardness at d=$d gave $c")
                previous = c
                d += 0.25f
            }
        }
    }

    @Test
    fun `even a fully hard dab keeps a one-pixel anti-aliased band`() {
        // A circle's distance gradient is one, so the per-axis rule reduces
        // to the old one-pixel `r − 1` band.
        val r = 10f
        val justInside = DabStamp.coverage(r - 0.5f, r, 1f)
        assertTrue(
            justInside > 0f && justInside < 1f,
            "half a pixel inside the rim must be partially covered, was $justInside",
        )
        assertEquals(1f, DabStamp.coverage(r - 1f, r, 1f), 1e-6f, "the plateau reaches r − 1")
    }

    @Test
    fun `a softer dab spreads its falloff further in`() {
        val atHalfRadius = DabStamp.coverage(5f, 10f, 0.9f)
        val softer = DabStamp.coverage(5f, 10f, 0.2f)
        assertTrue(
            softer < atHalfRadius,
            "a soft brush must be fainter mid-radius: soft $softer vs hard $atHalfRadius",
        )
    }

    // --------------------------------------------------------- sub-pixel

    @Test
    fun `a sub-pixel dab is drawn a pixel wide and dimmed by its true area`() {
        // §7.3: without the clamp a 0.3 px dab lands between sample points and
        // vanishes at some positions but not others — a dotted line, not a
        // thin one. The area weight is what keeps it *thin* rather than merely
        // present.
        assertEquals(1f, DabStamp.drawRadius(0.3f), 1e-6f, "drawn at one pixel")
        assertEquals(0.09f, DabStamp.areaWeight(0.3f), 1e-6f, "dimmed by r²")
        assertEquals(1f, DabStamp.areaWeight(1f), 1e-6f, "at 1 px there is nothing to compensate")
        assertEquals(1f, DabStamp.areaWeight(50f), 1e-6f, "and nothing above it either")
    }

    @Test
    fun `a thinning stroke fades out instead of snapping off`() {
        // The user-visible consequence of the area weight: alpha must decrease
        // continuously as the radius shrinks through 1 px, with no step.
        var previous = Float.MAX_VALUE
        var r = 1.5f
        while (r >= 0.1f) {
            val a = DabStamp.contribution(0f, 0f, dab(radius = r), white).a
            assertTrue(a <= previous + 1e-6f, "alpha rose from $previous to $a at r=$r")
            assertTrue(a > 0f, "a dab of radius $r must still make a mark, got $a")
            previous = a
            r -= 0.1f
        }
        assertTrue(previous < 0.05f, "the thinnest dab must be nearly invisible, was $previous")
    }

    // ---------------------------------------------------------- ellipse

    @Test
    fun `an elliptical dab is narrower across its minor axis`() {
        val d = dab(radius = 10f, aspect = 0.25f, angle = 0f)
        // Along the major axis (x here) the dab reaches its full radius.
        assertTrue(
            DabStamp.contribution(9f, 0f, d, white).a > 0f,
            "9 px along the major axis is still inside a radius-10 dab",
        )
        // Across the minor axis it reaches only aspect × radius.
        assertEquals(
            0f,
            DabStamp.contribution(0f, 3f, d, white).a,
            1e-6f,
            "3 px across a 0.25-aspect minor axis is outside",
        )
    }

    @Test
    fun `a hard flat dab keeps a one-pixel feather on both axes`() {
        val flat = dab(radius = 10f, hardness = 1f, aspect = 0.25f)

        val majorHalfPixelInside = DabStamp.contribution(9.5f, 0f, flat, white).a
        val minorHalfPixelInside = DabStamp.contribution(0f, 2f, flat, white).a

        assertTrue(
            majorHalfPixelInside in 0f..1f && majorHalfPixelInside != 0f && majorHalfPixelInside != 1f,
            "the major edge needs partial coverage, was $majorHalfPixelInside",
        )
        assertTrue(
            minorHalfPixelInside in 0f..1f && minorHalfPixelInside != 0f && minorHalfPixelInside != 1f,
            "the minor edge needs the same one-pixel feather, was $minorHalfPixelInside",
        )
        assertEquals(
            majorHalfPixelInside,
            minorHalfPixelInside,
            1e-5f,
            "half a canvas pixel inside either axis must have equal coverage",
        )
    }

    @Test
    fun `rotating a dab rotates its footprint with it`() {
        val flat = dab(radius = 10f, aspect = 0.25f, angle = 0f)
        val upright = dab(radius = 10f, aspect = 0.25f, angle = (PI / 2).toFloat())
        val alongX = 9f to 0f
        assertTrue(
            DabStamp.contribution(alongX.first, alongX.second, flat, white).a > 0f,
            "unrotated, the major axis lies along x",
        )
        assertEquals(
            0f,
            DabStamp.contribution(alongX.first, alongX.second, upright, white).a,
            1e-6f,
            "rotated a quarter turn, x is now the minor axis",
        )
    }

    @Test
    fun `a round dab is symmetric under rotation`() {
        val a = DabStamp.contribution(4f, 3f, dab(aspect = 1f, angle = 0f), white).a
        val b = DabStamp.contribution(4f, 3f, dab(aspect = 1f, angle = 1.1f), white).a
        assertEquals(a, b, 1e-5f, "angle must not matter when aspect is 1")
    }

    // ---------------------------------------------------- buffer blending

    @Test
    fun `Accumulate builds up where a stroke crosses itself and Max does not`() {
        // The whole difference between a pencil and an ink pen (§7.2).
        val dabColor = StrokeMerge.Rgba(0.4f, 0f, 0f, 0.4f)
        var accumulate = StrokeMerge.Rgba.TRANSPARENT
        var max = StrokeMerge.Rgba.TRANSPARENT
        repeat(3) {
            accumulate = DabStamp.blendIntoBuffer(accumulate, dabColor, BufferMode.Accumulate)
            max = DabStamp.blendIntoBuffer(max, dabColor, BufferMode.Max)
        }
        assertTrue(accumulate.a > 0.7f, "three overlapping dabs must build up, got ${accumulate.a}")
        assertEquals(0.4f, max.a, 1e-6f, "Max never exceeds the strongest single dab")
        assertTrue(accumulate.isPremultiplied(), "$accumulate")
        assertTrue(max.isPremultiplied(), "$max")
    }

    @Test
    fun `Max is componentwise, not a whole-pixel choice`() {
        // GL_MAX applies per channel and ignores the blend factors. With one
        // colour per stroke the two readings agree, which is exactly why the
        // difference has to be pinned now rather than discovered when a grain
        // texture starts modulating colour per dab.
        val existing = StrokeMerge.Rgba(0.8f, 0.1f, 0f, 0.8f)
        val incoming = StrokeMerge.Rgba(0.2f, 0.5f, 0f, 0.5f)
        val blended = DabStamp.blendIntoBuffer(existing, incoming, BufferMode.Max)
        assertEquals(0.8f, blended.r, 1e-6f, "red keeps the larger of the two")
        assertEquals(0.5f, blended.g, 1e-6f, "green takes the incoming, which is larger")
        assertEquals(0.8f, blended.a, 1e-6f, "alpha keeps the larger")
    }

    @Test
    fun `an eraser dab accumulates coverage with no colour`() {
        // §7.3: erasers use this exact shader with i_color = 0, so the buffer
        // carries alpha only and the merge's ERASE branch reads that alpha.
        val black = floatArrayOf(0f, 0f, 0f)
        val c = DabStamp.contribution(0f, 0f, dab(radius = 8f, flow = 0.6f), black)
        assertEquals(0f, c.r, 1e-6f)
        assertEquals(0f, c.g, 1e-6f)
        assertEquals(0f, c.b, 1e-6f)
        assertEquals(0.6f, c.a, 1e-6f, "coverage is flow at the dab's centre")
        assertTrue(c.isPremultiplied(), "$c")
    }

    @Test
    fun `flow scales the dab's contribution linearly`() {
        val full = DabStamp.contribution(0f, 0f, dab(flow = 1f), white).a
        val half = DabStamp.contribution(0f, 0f, dab(flow = 0.5f), white).a
        assertEquals(full * 0.5f, half, 1e-6f, "flow is the per-dab weight")
    }

    @Test
    fun `procedural grain is stable in canvas space`() {
        val weights = (0 until 16).map { x -> DabStamp.proceduralGrain(x + 0.5f, 3.5f) }

        assertTrue(weights.all { it in DabStamp.GRAIN_MIN_WEIGHT..1f })
        assertTrue(weights.distinct().size > 8, "the hash must vary across the paper: $weights")
        assertEquals(
            DabStamp.proceduralGrain(4.1f, 7.1f),
            DabStamp.proceduralGrain(4.9f, 7.9f),
            1e-6f,
            "sub-pixel dab movement must not make the paper grain swim",
        )

        val d = dab(x = 4.5f, y = 7.5f)
        val plain = DabStamp.contribution(4.5f, 7.5f, d, white).a
        val grain = DabStamp.contribution(4.5f, 7.5f, d, white, GrainMode.Procedural).a
        assertEquals(plain * DabStamp.proceduralGrain(4.5f, 7.5f), grain, 1e-6f)
    }

    @Test
    fun `every contribution over a spread of dabs stays premultiplied`() {
        val color = floatArrayOf(1f, 0.4f, 0f)
        for (radius in floatArrayOf(0.2f, 1f, 7f, 60f)) {
            for (hardness in floatArrayOf(0f, 0.5f, 1f)) {
                for (flow in floatArrayOf(0.05f, 0.5f, 1f)) {
                    for (d in floatArrayOf(0f, radius * 0.5f, radius, radius * 2f)) {
                        val c = DabStamp.contribution(
                            d, 0f, dab(radius = radius, flow = flow, hardness = hardness), color,
                        )
                        assertTrue(
                            c.isPremultiplied(),
                            "r=$radius h=$hardness flow=$flow d=$d gave $c",
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------- smoothstep

    @Test
    fun `smoothstep matches GLSL at its edges and midpoint`() {
        assertEquals(0f, DabStamp.smoothstep(0f, 1f, -0.5f), 1e-6f)
        assertEquals(0f, DabStamp.smoothstep(0f, 1f, 0f), 1e-6f)
        assertEquals(0.5f, DabStamp.smoothstep(0f, 1f, 0.5f), 1e-6f, "the curve is symmetric about its midpoint")
        assertEquals(1f, DabStamp.smoothstep(0f, 1f, 1f), 1e-6f)
        assertEquals(1f, DabStamp.smoothstep(0f, 1f, 2f), 1e-6f)
        // The cubic, not a straight line — a lerp here would be a visibly
        // different (harder-shouldered) brush edge.
        assertEquals(0.15625f, DabStamp.smoothstep(0f, 1f, 0.25f), 1e-6f)
    }

    @Test
    fun `a degenerate smoothstep interval is a hard step rather than a NaN`() {
        assertEquals(0f, DabStamp.smoothstep(1f, 1f, 0.5f), 1e-6f)
        assertEquals(1f, DabStamp.smoothstep(1f, 1f, 1f), 1e-6f)
        assertTrue(DabStamp.smoothstep(2f, 1f, 1.5f).isFinite(), "an inverted interval must not produce NaN")
    }
}
