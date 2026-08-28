package ch.lkmc.bangnidraw.engine.core

import kotlin.math.abs
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
class Stabilizer internal constructor(
    strength: Float,
    zoom: Float,
    private val samplePolicy: StabilizerSamplePolicy,
) {

    constructor(strength: Float, zoom: Float = 1f) : this(
        strength,
        zoom,
        StabilizerSamplePolicy.PositionOnly,
    )

    // Before the derived properties below, not after them: Kotlin runs
    // initializers in declaration order, so an `init` placed further down
    // would let a NaN strength or a zero zoom through `kOf` and `leashOf`
    // first — dividing by that zoom — and only then reject it.
    init {
        require(strength.isFinite() && strength in 0f..1f) {
            "stabilizer strength must be 0..1, was $strength"
        }
        require(zoom.isFinite() && zoom > 0f) { "zoom must be positive, was $zoom" }
    }

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

    private val finishStart = StrokeInput()
    private var finishDx = 0f
    private var finishDy = 0f
    private var finishSteps = 0
    private var finishIndex = 0
    private var finishPending = false

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
        finishPending = false
    }

    /**
     * Feeds one raw sample and writes the smoothed result into [out].
     *
     * [StabilizerSamplePolicy.PositionOnly] preserves the ordinary brush gate.
     * [StabilizerSamplePolicy.PositionOrDynamics] also forwards dynamics-only
     * samples so a flexible brush can stamp pressure in place and carry the
     * latest tilt and orientation into its next moving segment.
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
        val beforePressure = state.pressure
        val beforeTilt = state.tilt
        val beforeOrientation = state.orientation

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
        if (hypot(state.x - beforeX, state.y - beforeY) >= MIN_STEP_PX) return true
        if (samplePolicy == StabilizerSamplePolicy.PositionOnly) return false
        if (abs(state.pressure - beforePressure) >= DYNAMICS_EPSILON) return true
        if (abs(state.tilt - beforeTilt) >= DYNAMICS_EPSILON) return true
        return abs(state.orientation - beforeOrientation) >= DYNAMICS_EPSILON
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
     *
     * Every catch-up sample carries the pen-up timestamp: they are synthesized
     * at one instant, not observed over time. `StrokeInput.timeNs` is
     * therefore non-decreasing across a stroke rather than strictly
     * increasing, and a consumer deriving speed from it must treat a zero
     * delta as "no new information" — which is what `DabGenerator` does.
     */
    fun finish(step: Float, out: StrokeInput, emit: (StrokeInput) -> Unit): Int {
        var emitted = 0
        finishUntil(step, out) { sample ->
            emit(sample)
            emitted++
            StabilizerEmitDecision.CONTINUE
        }
        return emitted
    }

    /**
     * Continues pen-up catch-up until [emit] asks to pause.
     *
     * The original interpolation frame survives a pause, so splitting the
     * output across batches does not shift later samples through rounding.
     */
    internal fun finishUntil(
        step: Float,
        out: StrokeInput,
        emit: (StrokeInput) -> StabilizerEmitDecision,
    ): StabilizerFinishResult {
        require(step.isFinite() && step > 0f) { "catch-up step must be positive, was $step" }
        if (!started) return StabilizerFinishResult.COMPLETE
        if (!finishPending && !prepareFinish(step)) return StabilizerFinishResult.COMPLETE

        while (finishIndex <= finishSteps) {
            val t = finishIndex.toFloat() / finishSteps
            state.x = finishStart.x + finishDx * t
            state.y = finishStart.y + finishDy * t
            state.pressure = finishStart.pressure +
                (raw.pressure - finishStart.pressure) * t
            state.tilt = finishStart.tilt + (raw.tilt - finishStart.tilt) * t
            state.orientation = easeAngle(finishStart.orientation, raw.orientation, t)
            state.timeNs = raw.timeNs
            state.source = raw.source
            state.predicted = raw.predicted
            out.set(state)
            finishIndex++

            if (emit(out) == StabilizerEmitDecision.PAUSE) {
                return StabilizerFinishResult.PENDING
            }
        }

        finishPending = false
        started = false
        return StabilizerFinishResult.COMPLETE
    }

    private fun prepareFinish(step: Float): Boolean {
        finishDx = raw.x - state.x
        finishDy = raw.y - state.y
        val remaining = hypot(finishDx, finishDy)
        if (remaining < MIN_STEP_PX) {
            started = false
            return false
        }

        finishStart.set(state)
        // Bound a pen-up burst while still landing exactly on the raw point.
        finishSteps = kotlin.math.ceil(remaining / step).toInt()
            .coerceIn(1, MAX_CATCHUP_STEPS)
        finishIndex = 1
        finishPending = true
        return true
    }

    /**
     * An independent stabilizer at this one's exact state. The predicted tail
     * runs through the copy so it continues the stabilized line rather than
     * jumping ahead of it, and never advances the real state
     * (`03-canvas-engine.md` §9).
     */
    fun copy(): Stabilizer = Stabilizer(strength, zoom, samplePolicy).also { copyInto(it) }

    /**
     * [copy] into a stabilizer that already exists, so the tail costs no
     * allocation.
     *
     * The tail is rebuilt every frame and `10-performance.md` §2.4's rule
     * covers the whole span up to `renderFrontBufferedLayer`; a fresh [copy]
     * per frame would be a `Stabilizer` and two [StrokeInput]s each time.
     *
     * [retune] rather than assignment for `strength` and `zoom`: `k` and
     * [leash] are *derived*, and copying the two inputs while leaving the three
     * derived values at the target's own would give the tail different
     * smoothing from the stroke it continues. Reachable through
     * `StrokeDriver.setZoom`, which retunes the real stabilizer mid-stroke and
     * would otherwise leave the tail's copy tuned for the old zoom for the rest
     * of the stroke.
     */
    fun copyInto(other: Stabilizer) {
        require(other.samplePolicy == samplePolicy) {
            "a stabilizer copy must use the same sample policy"
        }
        other.retune(strength = strength, zoom = zoom)
        other.state.set(state)
        other.raw.set(raw)
        other.started = started
        other.finishStart.set(finishStart)
        other.finishDx = finishDx
        other.finishDy = finishDy
        other.finishSteps = finishSteps
        other.finishIndex = finishIndex
        other.finishPending = finishPending
    }

    /** The current smoothed sample, for tests and for the tail's starting point. */
    fun current(out: StrokeInput) {
        out.set(state)
    }

    companion object {
        /** Below this the output has not meaningfully moved. */
        const val MIN_STEP_PX = 0.05f

        /** Small enough to preserve pressure ramps without forwarding sensor noise forever. */
        private const val DYNAMICS_EPSILON = 1e-4f

        /** The leash at full strength, in screen px. */
        const val LEASH_PX_AT_FULL = 24f

        /** How far `1/zoom` may shrink the strength. */
        const val MIN_ZOOM_FACTOR = 0.25f

        /** `k` at full strength: the heaviest smoothing the slider can ask for. */
        const val MIN_K = 0.05f

        /** Ceiling on one pen-up's catch-up samples; see [finish]. */
        const val MAX_CATCHUP_STEPS = 256

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

internal enum class StabilizerEmitDecision { CONTINUE, PAUSE }

internal enum class StabilizerFinishResult { COMPLETE, PENDING }

/** Which stabilized changes are meaningful to the active brush model. */
internal enum class StabilizerSamplePolicy {
    PositionOnly,
    PositionOrDynamics,
}
