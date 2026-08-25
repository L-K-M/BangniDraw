package ch.lkmc.bangnidraw.engine.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `docs/plan/11-testing.md` §3.5. */
class PressureCurveTest {

    private val eps = 1e-4f

    @Test
    fun `the identity curve is identity`() {
        val curve = PressureCurve.IDENTITY
        for (i in 0..100) {
            val x = i / 100f
            assertEquals(x, curve.apply(x), eps, "identity must not move $x")
        }
    }

    @Test
    fun `endpoints are pinned to 0 and 1`() {
        // Including the shipped calibration, whose floor is deliberately above
        // zero: a floor that also lifted apply(0) off zero would mean the
        // lightest possible touch already paints, which is the exact thing the
        // floor exists to prevent.
        for (preference in PressurePreference.entries) {
            for (calibration in listOf(
                PressureCalibration.NONE,
                PressureCalibration.DEFAULT,
                PressureCalibration(floor = 0.3f, ceiling = 0.8f),
            )) {
                val curve = PressureCurve.of(calibration, preference)
                assertEquals(0f, curve.apply(0f), eps, "$calibration/$preference at 0")
                assertEquals(1f, curve.apply(1f), eps, "$calibration/$preference at 1")
            }
        }
    }

    @Test
    fun `curves are monotone`() {
        val random = Random(11)
        val calibrations = buildList {
            add(PressureCalibration.NONE)
            add(PressureCalibration.DEFAULT)
            repeat(50) {
                val floor = random.nextFloat() * 0.5f
                val ceiling = floor + PressureCalibration.MIN_SPAN +
                    random.nextFloat() * (1f - floor - PressureCalibration.MIN_SPAN)
                add(PressureCalibration(floor, ceiling))
            }
        }
        for (calibration in calibrations) {
            for (preference in PressurePreference.entries) {
                val curve = PressureCurve.of(calibration, preference)
                var previous = curve.apply(0f)
                for (i in 1..200) {
                    val y = curve.apply(i / 200f)
                    assertTrue(
                        y >= previous - eps,
                        "$calibration/$preference fell from $previous to $y at ${i / 200f}",
                    )
                    assertTrue(y in 0f..1f, "$calibration/$preference left 0..1 at $y")
                    previous = y
                }
            }
        }
    }

    @Test
    fun `pressure above 1 is clamped`() {
        // Real devices report above 1.0 (`07-input-and-stylus.md` §2). Without
        // the clamp the knot index would run off the end of the table.
        val curve = PressureCurve.of()
        val atOne = curve.apply(1f)
        for (over in listOf(1.0001f, 1.5f, 4f, Float.MAX_VALUE, Float.POSITIVE_INFINITY)) {
            assertEquals(atOne, curve.apply(over), eps, "$over must read as 1")
        }
    }

    @Test
    fun `a negative or NaN reading is the lightest touch, not a crash`() {
        val curve = PressureCurve.of()
        for (bad in listOf(-0f, -1f, -Float.MAX_VALUE, Float.NEGATIVE_INFINITY, Float.NaN)) {
            assertEquals(0f, curve.apply(bad), eps, "$bad must read as no pressure")
        }
    }

    @Test
    fun `the preference gammas order the curves`() {
        // Softer must reach a given pressure sooner than Linear, and Harder
        // later — that is the whole user-visible content of the setting, and
        // it would survive a sign slip in the gamma otherwise.
        val softer = PressureCurve.of(PressureCalibration.NONE, PressurePreference.SOFTER)
        val linear = PressureCurve.of(PressureCalibration.NONE, PressurePreference.LINEAR)
        val harder = PressureCurve.of(PressureCalibration.NONE, PressurePreference.HARDER)
        for (i in 1..99) {
            val x = i / 100f
            assertTrue(softer.apply(x) > linear.apply(x), "softer must be above linear at $x")
            assertTrue(harder.apply(x) < linear.apply(x), "harder must be below linear at $x")
        }
    }

    @Test
    fun `the calibration floor and ceiling map onto the full range`() {
        val curve = PressureCurve.of(
            PressureCalibration(floor = 0.2f, ceiling = 0.6f),
            PressurePreference.LINEAR,
        )
        assertEquals(0f, curve.apply(0.2f), eps, "the floor is no pressure")
        assertEquals(1f, curve.apply(0.6f), eps, "the ceiling is full pressure")
        assertEquals(0f, curve.apply(0.1f), eps, "below the floor stays at no pressure")
        assertEquals(1f, curve.apply(0.9f), eps, "above the ceiling stays at full")
        assertEquals(0.5f, curve.apply(0.4f), eps, "the midpoint of the range is the midpoint")
    }

    @Test
    fun `a degenerate calibration range is refused, not divided by`() {
        // The mapping divides by the span. An inverted or empty range would
        // give an infinity that becomes a NaN pressure and paints nothing,
        // far from whoever wrote the numbers.
        assertFailsWith<IllegalArgumentException> { PressureCalibration(0.5f, 0.5f) }
        assertFailsWith<IllegalArgumentException> { PressureCalibration(0.8f, 0.2f) }
        assertFailsWith<IllegalArgumentException> { PressureCalibration(-0.1f, 1f) }
        assertFailsWith<IllegalArgumentException> { PressureCalibration(0f, 1.5f) }
        assertFailsWith<IllegalArgumentException> { PressureCalibration(Float.NaN, 1f) }
    }

    @Test
    fun `the shipped calibration lifts a resting pen off zero`() {
        // The reason `DEFAULT.floor` is not zero: an S Pen resting on the
        // glass reports a small nonzero pressure, and without the floor that
        // resting contact already paints.
        val curve = PressureCurve.of()
        assertEquals(0f, curve.apply(PressureCalibration.DEFAULT.floor), eps)
        assertTrue(
            curve.apply(PressureCalibration.DEFAULT.floor / 2f) == 0f,
            "anything below the floor is no pressure at all",
        )
    }
}
