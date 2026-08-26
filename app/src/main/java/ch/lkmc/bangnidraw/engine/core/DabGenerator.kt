package ch.lkmc.bangnidraw.engine.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Turns a stabilized path into dabs (`docs/plan/04-tools.md` §3).
 *
 * Runs on the thread that reads the digitizer, inside the motion handler, so
 * it allocates nothing per dab: it writes into a preallocated [DabBatch].
 *
 * Two properties are what the tests are really about, and both come from
 * measuring in canvas space:
 *
 * - **Spacing is along the path, not per sample.** A fast stroke with four
 *   samples and a slow one with forty over the same geometry produce the same
 *   dab positions, because [advance] walks the segment in steps of
 *   `spacing · radius` and carries the remainder into the next segment.
 * - **Spacing is invariant under zoom.** The path arrives in canvas px and
 *   the step is computed from a canvas-px radius, so a brush does not go
 *   sparse when the user zooms in.
 *
 * Dynamics are interpolated *per dab* rather than taken from the segment
 * endpoints, so a fast pressure ramp inside one motion event still tapers
 * smoothly.
 */
class DabGenerator(
    private val preset: BrushPreset,
    private val seed: Long,
) {
    private val sizeLut = preset.pressureSize.lut()
    private val opacityLut = preset.pressureOpacity.lut()
    private val flowLut = preset.pressureFlow.lut()

    // Both clamps, and `maxRadius` floored at `minRadius` — otherwise the two
    // can cross and every `coerceIn` below throws on an empty range. A preset
    // is allowed to be smaller than the engine can draw: `BrushPreset.MIN_SIZE`
    // is 0.5 px of *diameter*, while `Dab.MIN_RADIUS` is 0.5 px of *radius*, so
    // a legal brush of 0.5..0.6 px gave minRadius 0.5 against maxRadius 0.3 and
    // crashed on its first dab. Such a brush means "always the smallest dab the
    // shader can draw", which is what an equal pair produces, not an error.
    private val minRadius = (preset.sizeMin / 2f).coerceIn(Dab.MIN_RADIUS, Dab.MAX_RADIUS)
    private val maxRadius = (preset.sizeMax / 2f).coerceIn(minRadius, Dab.MAX_RADIUS)

    private val last = StrokeInput()
    private var started = false

    /** Distance already travelled since the last dab, carried across segments. */
    private var carry = 0f

    /** Total stabilized path length, so [end] can tell a tap from a stroke. */
    private var pathLength = 0f

    private var dabIndex = 0

    /** Where the stroke's first dab went, so a tap can be rewritten by [end]. */
    private var firstBatch: DabBatch? = null
    private var firstIndex = -1

    /** Canvas px per ms, an EMA over about the last three samples. */
    private var velocity = 0f

    /** Travel from samples that shared a timestamp, waiting for a dated one. */
    private var pendingDistance = 0f

    /**
     * The stroke's opacity ceiling so far: the **maximum** pressure-opacity
     * seen, not the latest (`04-tools.md` §3.3). A stroke that starts light
     * and presses hard ends up at the hard-pressure opacity everywhere, which
     * is how "pressure → opacity" reads to users and avoids a seam where the
     * ceiling would otherwise change mid-stroke.
     */
    var pressureOpacityMax: Float = 0f
        private set

    /** How many dabs this stroke has emitted. */
    var dabCount: Int = 0
        private set

    /**
     * The spacing step for the pressure at the last sample — what
     * `Stabilizer.finish` walks the catch-up tail in.
     */
    fun currentStep(): Float = stepFor(if (started) last.pressure else 1f, last.tilt)

    /** Begins a stroke, emitting the first dab immediately at [first]. */
    fun begin(first: StrokeInput, out: DabBatch): Int {
        last.set(first)
        started = true
        carry = 0f
        pathLength = 0f
        dabIndex = 0
        dabCount = 0
        velocity = 0f
        pendingDistance = 0f
        pressureOpacityMax = 0f
        maxPressure = 0f
        firstBatch = null
        firstIndex = -1
        return if (emit(first.x, first.y, first, out)) 1 else 0
    }

    /**
     * Walks from the previous sample to [next], emitting a dab every
     * `spacing · radius` of path. Returns how many it emitted — zero for a
     * sample that did not move far enough to earn one.
     */
    fun advance(next: StrokeInput, out: DabBatch): Int {
        if (!started) return begin(next, out)

        // Before anything else, and deliberately not only inside `emit`: the
        // peak of a stroke can fall *between* two dabs, and on a tap it
        // always does — the pen presses and lifts inside one step. Folding
        // only emitted dabs left the ceiling and the tap fix-up reading the
        // near-zero pressure of the ACTION_DOWN sample.
        notePressure(next.pressure)

        val dx = next.x - last.x
        val dy = next.y - last.y
        val len = sqrt(dx * dx + dy * dy)
        updateVelocity(len, next.timeNs - last.timeNs)
        if (len <= 0f) {
            // A zero-length move still carries the new dynamics forward: the
            // pen can press harder without moving, and the next dab should
            // know. It must not emit, though, or a stationary pen would stack
            // dabs on one point until the batch filled.
            last.set(next)
            return 0
        }
        pathLength += len

        var emitted = 0
        // Mean pressure over the segment, so the step keeps the same overlap
        // ratio at light pressure as at full: a hard pencil that thins under a
        // light touch would otherwise space its dabs as if it were still fat.
        val meanPressure = (last.pressure + next.pressure) * 0.5f
        val meanTilt = (last.tilt + next.tilt) * 0.5f
        val step = stepFor(meanPressure, meanTilt)

        // Clamped at zero. `carry` is bounded by the *previous* segment's
        // step, and `step` is recomputed here from this segment's mean
        // pressure and tilt — so a pressure drop mid-stroke can leave
        // `step < carry` and a negative `t`, which emits dabs *behind* `last`,
        // extrapolated along a direction the pen never travelled. Zero is the
        // right floor rather than a skip: a dab that is overdue is due at the
        // segment start.
        var t = ((step - carry) / len).coerceAtLeast(0f)
        while (t <= 1f) {
            val x = last.x + dx * t
            val y = last.y + dy * t
            lerpInto(last, next, t, interpolated)
            // The direction of travel, for a tip that follows the stroke. Taken
            // from the segment rather than from consecutive dabs so it is
            // defined for the very first dab of a segment too.
            interpolated.strokeAngle = atan2(dy, dx)
            // Stop rather than keep walking: past a full batch every further
            // iteration burns a lerp, an atan2 and a noise draw, consumes the
            // seed stream, and leaves `carry` computed as though the dabs had
            // landed — so the gap the overflow already caused would be
            // compounded by a spacing error after it.
            if (!emit(x, y, interpolated, out)) break
            emitted++
            t += step / len
        }
        // What is left of the segment beyond the last dab, kept so the next
        // segment does not restart the spacing and cluster dabs at every
        // sample — this is the whole of "spacing is measured along the path".
        carry = len - (t - step / len) * len
        last.set(next)
        return emitted
    }

    /**
     * Pen-up (`04-tools.md` §3.4). Guarantees the stroke left at least one
     * dab, and fixes up a tap whose `ACTION_DOWN` carried almost no pressure.
     *
     * The residual [carry] deliberately does *not* emit: it would double-dot
     * every tap, since [begin] already placed a dab at the start.
     *
     * **The caller owes the pen-up sample to [advance] first.** This method
     * takes none, and the fix-up can only restore a pressure some sample
     * actually reported: a tap that produced no `ACTION_MOVE` at all has only
     * the down sample's pressure to work with, and would stay faint.
     */
    fun end(out: DabBatch): Int {
        if (!started) return 0
        started = false
        if (dabCount == 0) return 0
        if (pathLength >= currentStep()) return 0

        // A tap. The down sample of an S Pen almost always reports near-zero
        // pressure, so the one dab the tap left would be an invisible speck.
        // Rewrite it at the maximum pressure the tap actually saw — the user
        // pressed, the digitizer just reported the press after the contact.
        val batch = firstBatch ?: return 0
        val i = firstIndex
        if (i < 0 || i >= batch.count) return 0
        val p = maxPressure
        batch.radius[i] = radiusFor(p, last.tilt, dabIndexOfFirst)
        batch.flow[i] = flowFor(p, last.tilt)
        return 1
    }

    /**
     * An independent generator at this one's exact state, for §9's predicted
     * tail.
     *
     * The tail runs the predicted samples through a copy so it continues the
     * real stroke — same spacing remainder, same velocity, same jitter
     * sequence — while the real generator's state is never advanced by a
     * sample that was only a guess. Same `preset` and `seed`, so the copy's
     * dabs are exactly the dabs the real samples would produce if the
     * prediction is right, which is the whole claim §9 makes for it.
     *
     * **[firstBatch] is deliberately NOT carried over.** It points at the
     * caller's batch — the real stroke's — and [end] uses it to rewrite the
     * first dab of a tap in place. A copy holding that reference could reach
     * back into the committed stroke's batch and rewrite a dab that is already
     * on the layer. The tail never calls [end], so nulling it costs nothing
     * and closes the one way a copy could touch anything but itself.
     */
    fun copy(): DabGenerator {
        val other = DabGenerator(preset, seed)
        other.last.set(last)
        other.started = started
        other.carry = carry
        other.pathLength = pathLength
        other.dabIndex = dabIndex
        other.velocity = velocity
        other.pendingDistance = pendingDistance
        other.pressureOpacityMax = pressureOpacityMax
        other.dabCount = dabCount
        other.maxPressure = maxPressure
        other.dabIndexOfFirst = dabIndexOfFirst
        // `maxPressure` is carried for completeness, not because a test can
        // see it: it feeds only `end()`'s tap rewrite, and a tail never ends.
        // Left here rather than dropped so a future tail that DOES end starts
        // from the truth — but nobody should expect a failure if it goes.
        //
        // firstBatch / firstIndex stay null / -1: see the KDoc.
        return other
    }

    // ------------------------------------------------------------------ internals

    private val interpolated = InterpolatedSample()
    private var maxPressure = 0f
    private var dabIndexOfFirst = 0

    /**
     * A sample plus the stroke direction at that point. Not a [StrokeInput]
     * because the angle is derived from the path rather than delivered by the
     * digitizer, and putting it on `StrokeInput` would invite the input layer
     * to try to fill it.
     */
    private class InterpolatedSample {
        var pressure = 1f
        var tilt = 0f
        var orientation = 0f
        var strokeAngle = 0f
    }

    private fun lerpInto(a: StrokeInput, b: StrokeInput, t: Float, out: InterpolatedSample) {
        out.pressure = a.pressure + (b.pressure - a.pressure) * t
        out.tilt = a.tilt + (b.tilt - a.tilt) * t
        out.orientation = Stabilizer.easeAngle(a.orientation, b.orientation, t)
    }

    private fun emit(x: Float, y: Float, sample: StrokeInput, out: DabBatch): Boolean {
        interpolated.pressure = sample.pressure
        interpolated.tilt = sample.tilt
        interpolated.orientation = sample.orientation
        interpolated.strokeAngle = 0f
        return emit(x, y, interpolated, out)
    }

    private fun emit(x: Float, y: Float, sample: InterpolatedSample, out: DabBatch): Boolean {
        val p = sample.pressure
        notePressure(p)

        val index = dabIndex++
        val radius = radiusFor(p, sample.tilt, index)
        val flow = flowFor(p, sample.tilt)
        val dabSeed = noise(index, SALT_SEED)

        // Jitter is applied here, after the spacing walk, so it perturbs where
        // a dab is painted and never how many there are (`04` §3.2).
        var px = x
        var py = y
        if (preset.jitter.position > 0f) {
            val amount = preset.jitter.position * radius
            px += signed(index, SALT_JITTER_X) * amount
            py += signed(index, SALT_JITTER_Y) * amount
        }

        val elongation = elongationFor(sample.tilt)
        val angle = when {
            elongation > 1f -> sample.orientation
            else -> when (preset.orientation) {
                TipOrientation.Fixed -> 0f
                TipOrientation.Stylus -> sample.orientation
                TipOrientation.StrokeDirection -> sample.strokeAngle
            }
        }
        val baseAspect = when (val tip = preset.tip) {
            is TipShape.Round -> 1f
            is TipShape.Flat -> tip.aspect
        }
        // Elongation grows the major axis and leaves the minor alone, so the
        // stored radius (the major semi-axis) scales up and the aspect
        // (minor/major) scales down by the same factor.
        val added = if (elongation > 1f) elongation else 1f
        val finalRadius = (radius * added).coerceIn(minRadius, maxRadius)
        val aspect = (baseAspect / added).coerceIn(TipShape.Flat.MIN_ASPECT, 1f)

        val ok = out.add(px, py, finalRadius, flow, preset.hardness, angle, aspect, dabSeed)
        if (!ok) return false
        if (firstBatch == null) {
            firstBatch = out
            firstIndex = out.count - 1
            dabIndexOfFirst = index
        }
        dabCount++
        return true
    }

    /** `step = max(spacing · radius, 0.5)`: never denser than half a pixel. */
    private fun stepFor(pressure: Float, tilt: Float): Float =
        (preset.spacing * radiusFor(pressure, tilt, jitterIndex = -1))
            .coerceAtLeast(MIN_STEP_PX)

    /**
     * `r = size/2 · curveSize(p) · tiltMul · velMul · (1 ± jitter)`, clamped
     * into the preset's own range and then into what a dab quad can be.
     *
     * A [jitterIndex] below zero skips size jitter, which is what [stepFor]
     * wants: the *spacing* must not wobble with the jitter, or the dab count
     * would depend on the random stream and "leftover distance carries across
     * batches" would stop holding.
     */
    private fun radiusFor(pressure: Float, tilt: Float, jitterIndex: Int): Float {
        var r = preset.baseRadius * Curve.lookup(sizeLut, pressure)
        r *= tiltMultiplier(tilt, preset.tilt.sizeAtFlat)
        r *= velocityMultiplier(preset.velocity.sizeAtFast)
        if (jitterIndex >= 0 && preset.jitter.size > 0f) {
            r *= 1f + signed(jitterIndex, SALT_JITTER_SIZE) * preset.jitter.size
        }
        return r.coerceIn(minRadius, maxRadius)
    }

    /** `a = flow · pressureFlow(p) · tiltOpacity · velocityOpacity`. */
    private fun flowFor(pressure: Float, tilt: Float): Float {
        var a = preset.flow * Curve.lookup(flowLut, pressure)
        a *= tiltMultiplier(tilt, preset.tilt.opacityAtFlat)
        a *= velocityMultiplier(preset.velocity.opacityAtFast)
        return a.coerceIn(0f, 1f)
    }

    private fun elongationFor(tilt: Float): Float =
        if (!preset.tilt.elongate) 1f else 1f + tiltFraction(tilt)

    private fun tiltMultiplier(tilt: Float, atFlat: Float): Float =
        if (atFlat == 1f) 1f else 1f + (atFlat - 1f) * tiltFraction(tilt)

    private fun velocityMultiplier(atFast: Float): Float {
        if (atFast == 1f) return 1f
        val u = (velocity / preset.velocity.fastPxPerMs).coerceIn(0f, 1f)
        return 1f + (atFast - 1f) * u
    }

    /** `tilt / (π/2)`, clamped: 0 is perpendicular, 1 is flat against the glass. */
    private fun tiltFraction(tilt: Float): Float =
        if (tilt.isNaN()) 0f else (tilt / HALF_PI).coerceIn(0f, 1f)

    /**
     * Folds one input pressure into the stroke's peak and its opacity ceiling.
     *
     * Guards NaN itself because it feeds a `max`, not a curve. Every *other*
     * pressure path in this class reaches [Curve.lookup], which maps NaN to
     * the curve at x = 0 — so the spacing walk and the dab dynamics can pass
     * raw values through without a NaN sample poisoning `step`, `carry` or a
     * radius. The guard sits at the point they all funnel into rather than at
     * each caller.
     */
    private fun notePressure(pressure: Float) {
        val p = if (pressure.isNaN()) 0f else pressure.coerceIn(0f, 1f)
        maxPressure = maxOf(maxPressure, p)
        pressureOpacityMax = maxOf(pressureOpacityMax, Curve.lookup(opacityLut, p))
    }

    private fun updateVelocity(distance: Float, elapsedNs: Long) {
        // A non-positive dt means two samples share a timestamp, which happens
        // on devices whose historical samples all carry the batch's event
        // time. Keeping the previous velocity is right: the pen did move, we
        // just cannot say how fast from this pair.
        if (elapsedNs <= 0L) {
            // Defer rather than discard. Dropping the distance would lose the
            // pen's travel across a run of same-timestamp samples entirely, so
            // the next dated sample would report only its own segment and the
            // stroke would read as slower on those devices than the identical
            // gesture does elsewhere.
            pendingDistance += distance
            return
        }
        val ms = elapsedNs / 1_000_000f
        val instant = (distance + pendingDistance) / ms
        pendingDistance = 0f
        velocity += (instant - velocity) * VELOCITY_EMA_ALPHA
    }

    /** `0..1`, reproducible from the stroke seed and the dab's index. */
    private fun noise(index: Int, salt: Int): Float {
        var z = seed + index.toLong() * GOLDEN_GAMMA + salt.toLong() * SALT_GAMMA
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return ((z ushr 11).toDouble() / MANTISSA_SCALE).toFloat()
    }

    /** [noise] mapped to `-1..1`. */
    private fun signed(index: Int, salt: Int): Float = noise(index, salt) * 2f - 1f

    private companion object {
        const val HALF_PI = (PI / 2.0).toFloat()

        /** `04-tools.md` §3.1: never denser than half a pixel. */
        const val MIN_STEP_PX = 0.5f

        /** `2 / (N + 1)` for N = 3, the window `04` §3.3 specifies. */
        const val VELOCITY_EMA_ALPHA = 0.5f

        const val GOLDEN_GAMMA = -0x61c8864680b583ebL
        const val SALT_GAMMA = 0x165667b19e3779f9L
        const val MANTISSA_SCALE = 9007199254740992.0 // 2^53

        const val SALT_SEED = 0
        const val SALT_JITTER_X = 1
        const val SALT_JITTER_Y = 2
        const val SALT_JITTER_SIZE = 3
    }
}
