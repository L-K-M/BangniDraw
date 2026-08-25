package ch.lkmc.bangnidraw.engine.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A brush dynamics curve: four knots on `[0,1]`, at x = 0, 1/3, 2/3, 1,
 * evaluated with Catmull-Rom and clamped to `[0,1]`
 * (`docs/plan/04-tools.md` §2).
 *
 * Four knots at fixed x rather than a spline editor: it expresses "soft
 * start", "hard threshold", "floor at 30 %" and every gamma the plan tried,
 * it serializes to four floats, and the settings sheet can draw it as four
 * draggable handles without becoming a curve editor.
 *
 * [eval] is a spline evaluation and is **not** what the dab path calls. Build
 * a [lut] once per preset change and index that — a 1024-dab batch at 120 Hz
 * has microseconds, and this is the shape `04` §3.3 requires.
 */
@Serializable
data class Curve(val p0: Float, val p1: Float, val p2: Float, val p3: Float) {

    init {
        require(p0.isFinite() && p1.isFinite() && p2.isFinite() && p3.isFinite()) {
            "curve knots must be finite, was ($p0, $p1, $p2, $p3)"
        }
    }

    @Transient
    private val knots = floatArrayOf(p0, p1, p2, p3)

    /**
     * The curve at [x], with [x] clamped to `0..1` and the result clamped to
     * `0..1`. Catmull-Rom overshoots between knots by design — that is what
     * makes it look like a curve rather than a polyline — so the clamp is
     * load-bearing, not defensive: an overshoot past 1 would be a dab wider
     * than the preset's own maximum.
     */
    fun eval(x: Float): Float {
        val t = if (x.isNaN()) 0f else x.coerceIn(0f, 1f)
        val span = t * SEGMENTS
        val i = span.toInt().coerceAtMost(SEGMENTS - 1)
        val local = span - i
        // The end tangents come from *reflected* phantom knots
        // (`2·p0 − p1` before the start, `2·p3 − p2` after the end), not from
        // duplicating the end knots. Duplicating is the more common recipe and
        // it is wrong here: it gives the first segment a tangent of half the
        // straight-line slope, so `Curve.Linear` — knots at 0, 1/3, 2/3, 1 —
        // would leave the origin visibly flatter than the line through its own
        // knots and only catch up by the second segment. A curve named Linear
        // has to *be* linear, and so does `floor(min)`, which is likewise a
        // straight line. Reflecting makes both exact, and the spline still
        // passes through all four knots either way.
        val b = knots[i]
        val c = knots[i + 1]
        val a = if (i == 0) 2f * knots[0] - knots[1] else knots[i - 1]
        val d = if (i == SEGMENTS - 1) {
            2f * knots[SEGMENTS] - knots[SEGMENTS - 1]
        } else {
            knots[i + 2]
        }
        val t2 = local * local
        val t3 = t2 * local
        val y = 0.5f * (
            2f * b +
                (-a + c) * local +
                (2f * a - 5f * b + 4f * c - d) * t2 +
                (-a + 3f * b - 3f * c + d) * t3
            )
        return y.coerceIn(0f, 1f)
    }

    /**
     * A [LUT_SIZE]-entry table of this curve, for the dab path. Index with
     * [lookup] rather than by hand so the rounding stays in one place.
     */
    fun lut(): FloatArray = FloatArray(LUT_SIZE) { eval(it.toFloat() / (LUT_SIZE - 1)) }

    companion object {
        /** Knot count minus one: the number of spline segments. */
        private const val SEGMENTS = 3

        /** `04` §3.3's table size. */
        const val LUT_SIZE = 256

        /** Straight through: pressure maps to itself. */
        val Linear = Curve(0f, 1f / 3f, 2f / 3f, 1f)

        /** Constant 1 — the curve for "this brush ignores pressure". */
        val One = Curve(1f, 1f, 1f, 1f)

        /** `y = x^g`, sampled at the four knots. */
        fun gamma(g: Float): Curve {
            require(g.isFinite() && g > 0f) { "gamma must be finite and positive, was $g" }
            fun at(x: Float) = Math.pow(x.toDouble(), g.toDouble()).toFloat().coerceIn(0f, 1f)
            return Curve(at(0f), at(1f / 3f), at(2f / 3f), at(1f))
        }

        /** `y = min + (1 - min)·x`: never below [min], still reaching 1. */
        fun floor(min: Float): Curve {
            require(min.isFinite() && min in 0f..1f) { "floor must be 0..1, was $min" }
            fun at(x: Float) = min + (1f - min) * x
            return Curve(at(0f), at(1f / 3f), at(2f / 3f), at(1f))
        }

        /**
         * Reads a table built by [lut] at [x], clamping [x] to `0..1`.
         * Nearest-entry rather than interpolated: 256 entries over `0..1` put
         * consecutive entries within 0.4 % of each other, far below what a
         * dab radius can express.
         */
        fun lookup(lut: FloatArray, x: Float): Float {
            val t = if (x.isNaN()) 0f else x.coerceIn(0f, 1f)
            return lut[(t * (LUT_SIZE - 1) + 0.5f).toInt().coerceIn(0, LUT_SIZE - 1)]
        }
    }
}
