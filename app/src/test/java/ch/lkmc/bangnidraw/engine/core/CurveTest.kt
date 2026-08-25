package ch.lkmc.bangnidraw.engine.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The brush dynamics curve (`docs/plan/04-tools.md` §2). `PressureCurveTest`
 * covers the device-side curve; this one is the per-preset spline.
 */
class CurveTest {

    private val eps = 1e-4f

    @Test
    fun `the curve passes through its own knots`() {
        // Interpolating, not approximating: a Catmull-Rom spline passes
        // through every control point, so a preset's knots are the values the
        // brush actually uses at those pressures. If it ever became a
        // B-spline, every preset's "always at least this thin" floor would
        // quietly stop being reachable.
        val curve = Curve(0.15f, 0.3f, 0.6f, 1f)
        assertEquals(0.15f, curve.eval(0f), eps, "at x = 0")
        assertEquals(0.3f, curve.eval(1f / 3f), eps, "at x = 1/3")
        assertEquals(0.6f, curve.eval(2f / 3f), eps, "at x = 2/3")
        assertEquals(1f, curve.eval(1f), eps, "at x = 1")
    }

    @Test
    fun `Linear is the identity and One ignores its input`() {
        for (i in 0..100) {
            val x = i / 100f
            // Exact, not approximate: this is what the reflected end
            // tangents buy, and a return to duplicated ends would fail here.
            assertEquals(x, Curve.Linear.eval(x), eps, "Linear at $x")
            assertEquals(1f, Curve.One.eval(x), eps, "One at $x")
        }
    }

    @Test
    fun `the result never leaves 0 to 1`() {
        // Catmull-Rom overshoots between knots by design — that is what makes
        // it read as a curve — so the clamp is load-bearing. An overshoot past
        // 1 would be a dab wider than the preset's own sizeMax.
        val random = Random(3)
        repeat(500) {
            val curve = Curve(
                random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat(),
            )
            for (i in 0..64) {
                val y = curve.eval(i / 64f)
                assertTrue(y in 0f..1f, "$curve left 0..1 at ${i / 64f}: $y")
            }
        }
    }

    @Test
    fun `a step curve overshoots without the clamp, which is why it is there`() {
        // Pinning the reason rather than only the effect: this curve's knots
        // are all within 0..1, and an unclamped spline through them exceeds 1
        // between the last two. If someone removes the clamp, this is the
        // test that says what broke.
        val step = Curve(0f, 0f, 1f, 1f)
        var sawCeiling = false
        for (i in 0..256) {
            val y = step.eval(i / 256f)
            assertTrue(y in 0f..1f, "step curve left 0..1 at ${i / 256f}: $y")
            if (y >= 1f - 1e-6f) sawCeiling = true
        }
        assertTrue(sawCeiling, "the step curve must actually reach the ceiling it is clamped to")
    }

    @Test
    fun `x is clamped and NaN reads as zero`() {
        val curve = Curve(0.2f, 0.4f, 0.6f, 0.8f)
        assertEquals(curve.eval(0f), curve.eval(-1f), eps, "below 0")
        assertEquals(curve.eval(0f), curve.eval(Float.NEGATIVE_INFINITY), eps, "-inf")
        assertEquals(curve.eval(1f), curve.eval(2f), eps, "above 1")
        assertEquals(curve.eval(1f), curve.eval(Float.POSITIVE_INFINITY), eps, "+inf")
        assertEquals(curve.eval(0f), curve.eval(Float.NaN), eps, "NaN")
    }

    @Test
    fun `gamma and floor read as their intent`() {
        // gamma(1) is the identity; a gamma below 1 responds earlier.
        for (i in 0..3) {
            val x = i / 3f
            assertEquals(Curve.Linear.eval(x), Curve.gamma(1f).eval(x), eps, "gamma(1) at $x")
        }
        assertTrue(
            Curve.gamma(0.7f).eval(1f / 3f) > Curve.Linear.eval(1f / 3f),
            "a gamma below 1 must rise sooner",
        )
        assertTrue(
            Curve.gamma(1.3f).eval(1f / 3f) < Curve.Linear.eval(1f / 3f),
            "a gamma above 1 must rise later",
        )

        val floored = Curve.floor(0.3f)
        assertEquals(0.3f, floored.eval(0f), eps, "the floor is the value at zero pressure")
        assertEquals(1f, floored.eval(1f), eps, "a floor still reaches 1")
        for (i in 0..64) {
            assertTrue(floored.eval(i / 64f) >= 0.3f - eps, "floor(0.3) dipped below its floor")
        }
    }

    @Test
    fun `a non-finite knot or gamma is refused`() {
        assertFailsWith<IllegalArgumentException> { Curve(Float.NaN, 0f, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { Curve(0f, Float.POSITIVE_INFINITY, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { Curve.gamma(0f) }
        assertFailsWith<IllegalArgumentException> { Curve.gamma(-1f) }
        assertFailsWith<IllegalArgumentException> { Curve.gamma(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Curve.floor(-0.1f) }
        assertFailsWith<IllegalArgumentException> { Curve.floor(1.1f) }
    }

    @Test
    fun `the LUT agrees with eval everywhere it is sampled`() {
        // The dab path never calls eval; it indexes a LUT. If the two ever
        // disagreed, every test written against eval would be testing
        // something the engine does not run.
        val curves = listOf(
            Curve.Linear, Curve.One, Curve.gamma(0.7f), Curve.gamma(1.4f),
            Curve.floor(0.5f), Curve(0.15f, 0.3f, 0.6f, 1f),
        )
        for (curve in curves) {
            val lut = curve.lut()
            assertEquals(Curve.LUT_SIZE, lut.size)
            for (i in lut.indices) {
                val x = i.toFloat() / (Curve.LUT_SIZE - 1)
                assertEquals(curve.eval(x), Curve.lookup(lut, x), eps, "$curve at entry $i")
            }
        }
    }

    @Test
    fun `LUT lookup is within a quantisation step of the curve between entries`() {
        // Nearest-entry, not interpolated: 256 entries over 0..1 put
        // consecutive entries far closer together than a dab radius can
        // express. This pins that claim rather than asserting it in a comment.
        val curve = Curve(0.15f, 0.3f, 0.6f, 1f)
        val lut = curve.lut()
        val step = 1f / (Curve.LUT_SIZE - 1)
        var worst = 0f
        for (i in 0..4000) {
            val x = i / 4000f
            worst = maxOf(worst, kotlin.math.abs(Curve.lookup(lut, x) - curve.eval(x)))
        }
        assertTrue(worst <= step, "LUT drifted $worst from the curve, over one step of $step")
    }

    @Test
    fun `lookup clamps its index rather than reading off the end`() {
        val lut = Curve.Linear.lut()
        assertEquals(lut[0], Curve.lookup(lut, -5f), eps)
        assertEquals(lut[Curve.LUT_SIZE - 1], Curve.lookup(lut, 5f), eps)
        assertEquals(lut[0], Curve.lookup(lut, Float.NaN), eps)
    }
}
