package ch.lkmc.bangnidraw.engine.core

import kotlinx.serialization.Serializable

/**
 * The user's pressure preference (`docs/plan/07-input-and-stylus.md` §2).
 * A gamma applied after the device calibration: below 1 reaches full
 * pressure sooner (a lighter touch paints darker), above 1 later.
 */
@Serializable
enum class PressurePreference(val gamma: Float) {
    SOFTER(0.7f),
    LINEAR(1f),
    HARDER(1.4f),
}

/**
 * Per-device pressure calibration (`docs/plan/07-input-and-stylus.md` §2):
 * the raw range `[floor, ceiling]` is mapped onto the full `0..1`.
 *
 * [DEFAULT]'s floor is not zero, and that is the whole point of shipping the
 * data model in v1: S Pens report a small nonzero pressure while merely
 * touching the glass, so without a floor the lightest possible contact
 * already paints. The guided screen that *measures* a device's floor and
 * ceiling is post-v1 (`12-roadmap.md` §5); until it exists every device gets
 * these numbers.
 */
@Serializable
data class PressureCalibration(val floor: Float = 0.02f, val ceiling: Float = 1f) {
    init {
        require(floor.isFinite() && ceiling.isFinite()) {
            "calibration must be finite, was floor=$floor ceiling=$ceiling"
        }
        // Not `floor < ceiling` by a hair: the mapping divides by the span, so
        // a degenerate range would produce an infinity that then quietly
        // becomes a NaN pressure and paints nothing anywhere.
        require(floor in 0f..1f && ceiling in 0f..1f && ceiling - floor >= MIN_SPAN) {
            "calibration must be 0 <= floor, floor + $MIN_SPAN <= ceiling <= 1, " +
                "was floor=$floor ceiling=$ceiling"
        }
    }

    companion object {
        /** A hard press must be at least this far above a light one to be a range at all. */
        const val MIN_SPAN = 0.01f

        /** What every device gets until the post-v1 calibration screen exists. */
        val DEFAULT = PressureCalibration()

        /** No calibration at all: the raw value passes through. Tests and mice. */
        val NONE = PressureCalibration(floor = 0f, ceiling = 1f)
    }
}

/**
 * Device pressure → brush-independent `0..1`, as a monotone piecewise-linear
 * map over [KNOTS] knots (`docs/plan/07-input-and-stylus.md` §2).
 *
 * Two stages compose into the one table: the device [PressureCalibration],
 * then the user's [PressurePreference] gamma. Sampling both into knots once
 * means [apply] is two multiplies and a lerp per input sample rather than a
 * `pow` — this runs on the main thread inside the motion handler.
 *
 * The per-brush pressure→size/opacity/flow curves are a *third* stage and are
 * not here: they belong to the preset ([Curve]), and `StrokeInput.pressure`
 * stays the device-normalized, brush-independent value.
 */
class PressureCurve private constructor(private val knots: FloatArray) {

    /**
     * [raw] is clamped to `0..1` before anything else: Android reports values
     * above 1 on some devices (`07-input-and-stylus.md` §2), and a raw NaN
     * from a driver would otherwise reach the dab radius.
     */
    fun apply(raw: Float): Float {
        val x = if (raw.isNaN()) 0f else raw.coerceIn(0f, 1f)
        val span = x * LAST_KNOT
        val i = span.toInt().coerceAtMost(LAST_KNOT - 1)
        val t = span - i
        return knots[i] + (knots[i + 1] - knots[i]) * t
    }

    /** The knot values, for tests and for drawing the curve in settings. */
    fun knots(): FloatArray = knots.copyOf()

    companion object {
        /** Knot count. Eight is what `07` §2 declares. */
        const val KNOTS = 8
        private const val LAST_KNOT = KNOTS - 1

        /** Raw pressure straight through: no calibration, no preference gamma. */
        val IDENTITY: PressureCurve = of(PressureCalibration.NONE, PressurePreference.LINEAR)

        /** What v1 ships with (`PressureCalibration.DEFAULT` + the user's preference). */
        fun of(
            calibration: PressureCalibration = PressureCalibration.DEFAULT,
            preference: PressurePreference = PressurePreference.LINEAR,
        ): PressureCurve {
            val span = calibration.ceiling - calibration.floor
            val g = preference.gamma
            val knots = FloatArray(KNOTS) { i ->
                val x = i.toFloat() / LAST_KNOT
                val calibrated = ((x - calibration.floor) / span).coerceIn(0f, 1f)
                // Both stages are monotone non-decreasing on 0..1 and both fix
                // the endpoints, so the composition does too — which is what
                // makes the piecewise-linear sampling above faithful rather
                // than merely close.
                if (g == 1f) calibrated else calibrated.toDouble().pow(g).toFloat()
            }
            return PressureCurve(knots)
        }

        private fun Double.pow(exponent: Float): Double = Math.pow(this, exponent.toDouble())
    }
}
