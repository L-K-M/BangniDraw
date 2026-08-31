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
    ;

    companion object {
        fun fromStored(value: String?): PressurePreference =
            entries.firstOrNull { it.name == value } ?: LINEAR
    }
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
 * Two stages compose into the one knot list: the device
 * [PressureCalibration], then the user's [PressurePreference] gamma. Sampling
 * both once means [apply] is a short scan and a lerp per input sample rather
 * than a `pow` — this runs on the main thread inside the motion handler.
 *
 * The knots are **not** at uniform x. They sit at the composed function's own
 * breakpoints, so the flat stretches below the floor and above the ceiling are
 * exact rather than smeared across a segment.
 *
 * The per-brush pressure→size/opacity/flow curves are a *third* stage and are
 * not here: they belong to the preset ([Curve]), and `StrokeInput.pressure`
 * stays the device-normalized, brush-independent value.
 */
class PressureCurve private constructor(
    private val xs: FloatArray,
    private val ys: FloatArray,
) {

    /**
     * [raw] is clamped to `0..1` before anything else: Android reports values
     * above 1 on some devices (`07-input-and-stylus.md` §2), and a raw NaN
     * from a driver would otherwise reach the dab radius.
     */
    fun apply(raw: Float): Float {
        val x = if (raw.isNaN()) 0f else raw.coerceIn(0f, 1f)
        // Eight knots, so a scan beats a binary search and both beat the `pow`
        // this table exists to avoid.
        for (i in 0 until xs.size - 1) {
            val x0 = xs[i]
            val x1 = xs[i + 1]
            if (x > x1) continue
            // Zero-width segments are normal, not degenerate: a floor of 0
            // puts two knots at x = 0 and a ceiling of 1 puts two at x = 1.
            if (x1 <= x0) return ys[i + 1]
            val t = (x - x0) / (x1 - x0)
            return ys[i] + (ys[i + 1] - ys[i]) * t
        }
        return ys[ys.size - 1]
    }

    /** The knot positions, for tests and for drawing the curve in settings. */
    fun knotsX(): FloatArray = xs.copyOf()

    /** The knot values, for tests and for drawing the curve in settings. */
    fun knotsY(): FloatArray = ys.copyOf()

    companion object {
        /** Knot count. Eight is what `07` §2 declares. */
        const val KNOTS = 8

        /** Raw pressure straight through: no calibration, no preference gamma. */
        val IDENTITY: PressureCurve = of(PressureCalibration.NONE, PressurePreference.LINEAR)

        /** What v1 ships with (`PressureCalibration.DEFAULT` + the user's preference). */
        fun of(
            calibration: PressureCalibration = PressureCalibration.DEFAULT,
            preference: PressurePreference = PressurePreference.LINEAR,
        ): PressureCurve {
            val floor = calibration.floor
            val ceiling = calibration.ceiling
            val span = ceiling - floor
            val g = preference.gamma

            // The knots sit at the composed function's own breakpoints, not at
            // uniform x. That is the whole reason this is a knot *list* rather
            // than a table: the function is flat below `floor` and flat above
            // `ceiling`, and uniform sampling would smear both knees across a
            // segment — so a pen resting at exactly `floor` would come out
            // with a small nonzero pressure and paint, which is precisely what
            // the floor exists to prevent.
            val xs = FloatArray(KNOTS)
            val ys = FloatArray(KNOTS)
            xs[0] = 0f
            ys[0] = 0f
            xs[1] = floor
            ys[1] = 0f
            for (i in 1..INTERIOR) {
                val u = i.toFloat() / (INTERIOR + 1)
                xs[1 + i] = floor + span * u
                ys[1 + i] = if (g == 1f) u else u.toDouble().pow(g).toFloat()
            }
            xs[KNOTS - 2] = ceiling
            ys[KNOTS - 2] = 1f
            xs[KNOTS - 1] = 1f
            ys[KNOTS - 1] = 1f
            return PressureCurve(xs, ys)
        }

        /** Knots strictly between the floor and the ceiling: the gamma's shape. */
        private const val INTERIOR = KNOTS - 4

        private fun Double.pow(exponent: Float): Double = Math.pow(this, exponent.toDouble())
    }
}
