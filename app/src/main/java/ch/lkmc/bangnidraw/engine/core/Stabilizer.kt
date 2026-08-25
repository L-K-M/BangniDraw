package ch.lkmc.bangnidraw.engine.core

import kotlin.math.sqrt

/**
 * "Pull string" smoothing in canvas space (`docs/plan/04-tools.md` §4).
 *
 * The output point follows the raw pen position on a short leash: each raw
 * sample first snaps the output to within [leash] of the pen if it has fallen
 * further behind, then eases toward it by a fraction `k`. The snap is what
 * makes it a *string* rather than an exponential filter — pure exponential
 * smoothing falls ever further behind during fast motion and never catches
 * up, which reads as the brush detaching from the pen.
 *
 * Strength is nudged by zoom: `effective = strength · clamp(1/zoom, 0.25, 1)`.
 * At 4× the raw jitter is already 4× smaller on paper, and full stabilization
 * there feels like drawing in syrup.
 *
 * Not thread-safe and not meant to be: one stroke, one stabilizer, on the
 * thread that reads the digitizer. The predicted tail runs through a [copy]
 * so it continues the stabilized line without ever advancing the real state.
 */
class Stabilizer(strength: Float, zoom: Float = 1f) {

    var strength: Float = strength
        private set

    var zoom: Float = zoom
        private set

    /** `strength · clamp(1/zoom, 0.25, 1)` — what `k` and [leash] are built from. */
    var effectiveStrength: Float = effectiveStrengthOf(strength, zoom)
        private set

    /** The easing fraction: 1 passes through, 0.05 is the heaviest smoothing. */
    var k: Float = kOf(effectiveStrength)
        private set

    /** The leash length in canvas px: the output is never further than this behind. */
    var leash: Float = leashOf(effectiveStrength, zoom)
        private set

    private val state = StrokeInput()
    private val raw = StrokeInput()
    private var started = false

    init {
        require(strength.isFinite() && strength in 0f..1f) {
            "stabilizer strength must be 0..1, was $strength"
        }
        require(zoom.isFinite() && zoom > 0f) { "zoom must be positive, was $zoom" }
    }

    /**
     * Retunes for a new zoom or a new preset mid-stroke without moving the
     * output — the smoothing changes, the brush does not jump.
     */
    fun retune(strength: Float = this.strength, zoom: Float = this.zoom) {
        require(strength.isFinite() && strength in 0f..1f) {
            "stabilizer strength must be 0..1, was $strength"
        }
        require(zoom.isFinite() && zoom > 0f) { "zoom must be positive, was $zoom" }
        this.strength = strength
        this.zoom = zoom
        effectiveStrength = effectiveStrengthOf(strength, zoom)
        k = kOf(effectiveStrength)
        leash = leashOf(effectiveStrength, zoom)
    }

    /**
     * Begins a stroke at [first]. The first sample is emitted unchanged —
     * there is no dead start, and a tap lands exactly where it was made.
     */
    fun reset(first: StrokeInput) {
        state.set(first)
        raw.set(first)
        started = true
    }

    /**
     * Feeds one raw sample and writes the smoothed result into [out].
     *
     * Returns false when the output moved less than [MIN_STEP_PX] — the
     * caller should drop it rather than emit a dab on top of the last one.
     * The state still advances, so the motion is not lost, only the sample.
     */
    fun push(sample: StrokeInput, out: StrokeInput): Boolean {
        if (!started) {
            reset(sample)
            out.set(sample)
            return true
        }
        raw.set(sample)
        val beforeX = state.x
        val beforeY = state.y

        // The leash first, then the ease. Order matters: easing from the
        // snapped position is what bounds the lag at `leash`, where easing
        // first and snapping after would let a fast stroke sit permanently at
        // the end of the leash with no smoothing left to give.
        var dx = raw.x - state.x
        var dy = raw.y - state.y
        val dist = hypot(dx, dy)
        if (dist > leash && dist > 0f) {
            val pull = (dist - leash) / dist
            state.x += dx * pull
            state.y += dy * pull
            dx = raw.x - state.x
            dy = raw.y - state.y
        }
        state.x += dx * k
        state.y += dy * k

        // Pressure and tilt ease with the same k as position, so a taper
        // follows the smoothed geometry instead of arriving early.
        state.pressure += (raw.pressure - state.pressure) * k
        state.tilt += (raw.tilt - state.tilt) * k
        state.orientation = easeAngle(state.orientation, raw.orientation, k)
        state.timeNs = raw.timeNs
        state.source = raw.source
        state.predicted = raw.predicted

        out.set(state)
        return hypot(state.x - beforeX, state.y - beforeY) >= MIN_STEP_PX
    }

    /**
     * Pen-up: walks the output to the last raw point in steps of [step],
     * writing each into [out] via [emit], and returns how many it wrote.
     *
     * A stroke ends where the pen lifted, not where the leash was. With
     * strength 0.7 the output can trail by ~17 px, and stopping there leaves
     * a visibly short line. The catch-up samples carry the pressure decayed
     * linearly to the final raw pressure, so the tail tapers instead of
     * ending in a blob.
     */
    fun finish(step: Float, out: StrokeInput, emit: (StrokeInput) -> Unit): Int {
        require(step.isFinite() && step > 0f) { "catch-up step must be positive, was $step" }
        if (!started) return 0
        val dx = raw.x - state.x
        val dy = raw.y - state.y
        val remaining = hypot(dx, dy)
        if (remaining < MIN_STEP_PX) {
            started = false
            return 0
        }
        val steps = kotlin.math.ceil(remaining / step).toInt().coerceAtLeast(1)
        val fromX = state.x
        val fromY = state.y
        val fromPressure = state.pressure
        val fromTilt = state.tilt
        val fromOrientation = state.orientation
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            state.x = fromX + dx * t
            state.y = fromY + dy * t
            state.pressure = fromPressure + (raw.pressure - fromPressure) * t
            state.tilt = fromTilt + (raw.tilt - fromTilt) * t
            state.orientation = easeAngle(fromOrientation, raw.orientation, t)
            state.timeNs = raw.timeNs
            state.source = raw.source
            state.predicted = raw.predicted
            out.set(state)
            emit(out)
        }
        started = false
        return steps
    }

    /**
     * An independent stabilizer at this one's exact state. The predicted tail
     * runs through the copy so it continues the stabilized line rather than
     * jumping ahead of it, and never advances the real state
     * (`03-canvas-engine.md` §9).
     */
    fun copy(): Stabilizer {
        val other = Stabilizer(strength, zoom)
        other.state.set(state)
        other.raw.set(raw)
        other.started = started
        return other
    }

    /** The current smoothed sample, for tests and for the tail's starting point. */
    fun current(out: StrokeInput) {
        out.set(state)
    }

    companion object {
        /** Below this the output has not meaningfully moved. */
        const val MIN_STEP_PX = 0.05f

        /** The leash at full strength, in screen px. */
        const val LEASH_PX_AT_FULL = 24f

        /** How far `1/zoom` may shrink the strength. */
        const val MIN_ZOOM_FACTOR = 0.25f

        /** `k` at full strength: the heaviest smoothing the slider can ask for. */
        const val MIN_K = 0.05f

        fun effectiveStrengthOf(strength: Float, zoom: Float): Float =
            strength * (1f / zoom).coerceIn(MIN_ZOOM_FACTOR, 1f)

        /**
         * `k = 1 − sqrt(strength) · 0.95`, so 0 passes through and 1 gives
         * [MIN_K]. The square root front-loads the slider: the audible
         * difference between 0.0 and 0.3 is much larger than between 0.7 and
         * 1.0, and a linear slider would put most of its travel in the range
         * nobody can tell apart.
         */
        fun kOf(effectiveStrength: Float): Float =
            1f - sqrt(effectiveStrength) * (1f - MIN_K)

        /**
         * The leash in **canvas** px. `LEASH_PX_AT_FULL` is a screen-space
         * feel, so it divides by zoom on top of the strength nudge — at 4× a
         * 24 px leash on screen is 6 canvas px.
         */
        fun leashOf(effectiveStrength: Float, zoom: Float): Float =
            effectiveStrength * LEASH_PX_AT_FULL / zoom

        private fun hypot(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

        /**
         * Eases an angle the short way round. Interpolating the raw radians
         * would send a pen crossing from +π to −π the long way, spinning a
         * chisel tip through half a turn on one sample.
         */
        fun easeAngle(from: Float, to: Float, t: Float): Float {
            val twoPi = (2.0 * Math.PI).toFloat()
            var delta = (to - from) % twoPi
            if (delta > Math.PI) delta -= twoPi
            if (delta < -Math.PI) delta += twoPi
            return from + delta * t
        }
    }
}
