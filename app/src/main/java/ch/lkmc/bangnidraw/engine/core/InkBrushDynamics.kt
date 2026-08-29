package ch.lkmc.bangnidraw.engine.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Stateful flexible-tuft response for one Chinese ink stroke. */
internal class InkBrushDynamics(
    private val baseRadius: Float,
    val patternSeed: Float,
    tip: TipShape,
    private val orientation: TipOrientation,
) {
    private val fullContactAspect = when (tip) {
        TipShape.Round -> DEFAULT_FULL_CONTACT_ASPECT
        is TipShape.Flat -> tip.aspect
    }

    private var axisReady = false
    private var axis = 0f
    private var segmentAxisStart = 0f
    private var segmentAxisTarget = 0f
    private var segmentDistance = 0f
    private var segmentPathAngle = 0f
    private var responseLength = MIN_RESPONSE_PX

    private var bristleAlong = 0f
    private var segmentBristleAlongStart = 0f

    private var inkUse = 0f
    private var segmentInkStart = 0f
    private var segmentInkEnd = 0f
    private var segmentDrying = 1f
    private var stationaryPressurePeak = 0f

    /** Path length covered since pen-down; the splay settles in over it. */
    private var strokeTravelled = 0f
    private val segmentEndSample = InkBrushSample()

    fun reset(pressure: Float) {
        axisReady = false
        axis = 0f
        segmentAxisStart = 0f
        segmentAxisTarget = 0f
        segmentDistance = 0f
        segmentPathAngle = 0f
        responseLength = MIN_RESPONSE_PX
        bristleAlong = 0f
        segmentBristleAlongStart = 0f
        inkUse = 0f
        segmentInkStart = 0f
        segmentInkEnd = 0f
        segmentDrying = 1f
        stationaryPressurePeak = normalizedPressure(pressure)
        strokeTravelled = 0f
    }

    fun prepareSegment(
        pathAngle: Float,
        distance: Float,
        pressure: Float,
        tiltFraction: Float,
        stylusAngle: Float,
        contactRadius: Float,
        speedFraction: Float,
    ) {
        // One malformed digitizer sample must not poison the remaining stroke.
        val tilt = normalizedUnit(tiltFraction)
        val speed = normalizedUnit(speedFraction)
        val penAngle = if (stylusAngle.isFinite()) stylusAngle else pathAngle
        val radius = if (contactRadius.isFinite()) contactRadius.coerceAtLeast(0f) else baseRadius
        val stylusWeight = smoothUnit(tilt) * STYLUS_AXIS_WEIGHT
        val target = when (orientation) {
            TipOrientation.Fixed -> 0f
            TipOrientation.Stylus -> penAngle
            TipOrientation.StrokeDirection ->
                pathAngle + axisDelta(pathAngle, penAngle) * stylusWeight
        }
        if (!axisReady) {
            axis = target
            axisReady = true
        }

        segmentAxisStart = axis
        segmentAxisTarget = target
        segmentDistance = distance
        segmentPathAngle = pathAngle
        segmentBristleAlongStart = bristleAlong
        responseLength = maxOf(
            MIN_RESPONSE_PX,
            radius * (RESPONSE_BASE + RESPONSE_PRESSURE * normalizedPressure(pressure)),
        )

        segmentInkStart = inkUse
        val radiusFraction = (radius / maxOf(baseRadius, 1f)).coerceIn(0f, 1f)
        val contact = MIN_INK_CONTACT + (1f - MIN_INK_CONTACT) * radiusFraction
        segmentInkEnd = inkUse + distance / maxOf(baseRadius, 1f) * contact

        val push = abs(cos(pathAngle - penAngle)) * tilt
        val drying = 1f - FAST_DRYING * speed - PUSH_DRYING * push
        segmentDrying = drying.coerceIn(MIN_DRYING, 1f)
    }

    fun angleAt(fraction: Float): Float {
        if (!axisReady) return 0f

        val travelled = segmentDistance * fraction.coerceIn(0f, 1f)
        val response = 1f - exp(-travelled / responseLength)
        return segmentAxisStart + axisDelta(segmentAxisStart, segmentAxisTarget) * response
    }

    fun aspectAt(pressure: Float, tiltFraction: Float, travelPx: Float): Float {
        if (!axisReady) return 1f

        val splay = sqrt(normalizedPressure(pressure))
        val pressureAspect = LIGHT_CONTACT_ASPECT +
            (fullContactAspect - LIGHT_CONTACT_ASPECT) * splay
        val target = (pressureAspect - TILT_ASPECT_LOSS * tiltFraction)
            .coerceIn(TipShape.Flat.MIN_ASPECT, 1f)
        // A directionless first touch is round, but snapping to the splayed
        // footprint on the very next dab reads as a balloon on a ribbon. The
        // splay settles with the same response length as the tuft axis, so
        // the entry shapes itself over the first tuft lengths of travel.
        val settle = 1f - exp(-travelPx.coerceAtLeast(0f) / responseLength)
        return 1f + (target - 1f) * settle
    }

    fun wetnessAt(fraction: Float): Float {
        val use = segmentInkStart + (segmentInkEnd - segmentInkStart) * fraction.coerceIn(0f, 1f)
        return loadedFraction(use) * segmentDrying
    }

    /** Writes the tuft orientation, ink load, and transported material phase. */
    fun writeSampleAt(fraction: Float, out: InkBrushSample) {
        val f = fraction.coerceIn(0f, 1f)
        // The hair lanes live in the path frame: fly-white channels are drag
        // channels, so their material coordinate is plain arc length. The
        // lagged tuft axis in [angleAt] drives only the footprint.
        out.angle = angleAt(f)
        out.wetness = wetnessAt(f)
        out.travel = strokeTravelled + segmentDistance * f
        out.bristleAlong = segmentBristleAlongStart + segmentDistance * f
    }

    fun currentAngle(): Float = if (axisReady) axis else 0f

    fun currentAspect(pressure: Float, tiltFraction: Float): Float =
        aspectAt(pressure, tiltFraction, strokeTravelled)

    /** Distance covered since pen-down, for the splay settle of a resting dab. */
    fun currentTravel(): Float = strokeTravelled

    fun currentWetness(speedFraction: Float): Float =
        loadedFraction(inkUse) * (1f - FAST_DRYING * normalizedUnit(speedFraction))

    fun currentBristleAlong(): Float = bristleAlong

    /** The current segment's tangent, for a resting dab's lane frame. */
    fun currentPathAngle(): Float = if (axisReady) segmentPathAngle else 0f

    fun finishSegment(pressure: Float) {
        writeSampleAt(1f, segmentEndSample)
        bristleAlong = segmentEndSample.bristleAlong
        axis = segmentEndSample.angle
        inkUse = segmentInkEnd
        strokeTravelled += segmentDistance
        stationaryPressurePeak = normalizedPressure(pressure)
    }

    fun shouldStampStationary(pressure: Float): Boolean {
        val p = normalizedPressure(pressure)
        if (p < stationaryPressurePeak) return false
        if (p - stationaryPressurePeak < STATIONARY_PRESSURE_STEP) return false

        stationaryPressurePeak = p
        return true
    }

    fun copyInto(other: InkBrushDynamics) {
        other.axisReady = axisReady
        other.axis = axis
        other.segmentAxisStart = segmentAxisStart
        other.segmentAxisTarget = segmentAxisTarget
        other.segmentDistance = segmentDistance
        other.segmentPathAngle = segmentPathAngle
        other.responseLength = responseLength
        other.bristleAlong = bristleAlong
        other.segmentBristleAlongStart = segmentBristleAlongStart
        other.inkUse = inkUse
        other.segmentInkStart = segmentInkStart
        other.segmentInkEnd = segmentInkEnd
        other.segmentDrying = segmentDrying
        other.stationaryPressurePeak = stationaryPressurePeak
        other.strokeTravelled = strokeTravelled
    }

    private fun loadedFraction(use: Float): Float = 1f / (1f + use / INK_CAPACITY)

    private fun normalizedPressure(pressure: Float): Float =
        normalizedUnit(pressure)

    private fun normalizedUnit(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    private fun axisDelta(from: Float, to: Float): Float {
        var delta = (to - from) % PI_FLOAT
        if (delta > HALF_PI) delta -= PI_FLOAT
        if (delta < -HALF_PI) delta += PI_FLOAT
        return delta
    }

    private fun smoothUnit(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    internal companion object {
        const val PI_FLOAT = PI.toFloat()
        const val HALF_PI = (PI / 2.0).toFloat()

        const val MIN_RESPONSE_PX = 1f
        const val RESPONSE_BASE = 2.4f
        const val RESPONSE_PRESSURE = 1.4f
        const val STYLUS_AXIS_WEIGHT = 0.45f

        const val LIGHT_CONTACT_ASPECT = 0.9f
        const val DEFAULT_FULL_CONTACT_ASPECT = 0.72f
        const val TILT_ASPECT_LOSS = 0.08f

        const val MIN_INK_CONTACT = 0.25f

        /**
         * Ink lasts this many `baseRadius` units of swept contact before the
         * load halves. Measured against reference calligraphy: a tuft lays
         * down a few tuft-widths of solid line, then split hairs build over
         * the next dozen widths into full fly-white — texture must develop
         * *within* one ordinary stroke, since every stroke starts loaded.
         */
        const val INK_CAPACITY = 5f
        const val FAST_DRYING = 0.38f
        const val PUSH_DRYING = 0.12f
        const val MIN_DRYING = 0.42f
        const val STATIONARY_PRESSURE_STEP = 0.04f
    }
}

/** Reused output from [InkBrushDynamics] on the allocation-free input path. */
internal class InkBrushSample {
    var angle = 0f
    var wetness = 1f
    var travel = 0f
    var bristleAlong = 0f
}
