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
    private var bristleAcross = 0f
    private var segmentBristleAlongStart = 0f
    private var segmentBristleAcrossStart = 0f

    private var inkUse = 0f
    private var segmentInkStart = 0f
    private var segmentInkEnd = 0f
    private var segmentDrying = 1f
    private var stationaryPressurePeak = 0f
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
        bristleAcross = 0f
        segmentBristleAlongStart = 0f
        segmentBristleAcrossStart = 0f
        inkUse = 0f
        segmentInkStart = 0f
        segmentInkEnd = 0f
        segmentDrying = 1f
        stationaryPressurePeak = normalizedPressure(pressure)
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
        val stylusWeight = smoothUnit(tiltFraction) * STYLUS_AXIS_WEIGHT
        val target = when (orientation) {
            TipOrientation.Fixed -> 0f
            TipOrientation.Stylus -> stylusAngle
            TipOrientation.StrokeDirection ->
                pathAngle + axisDelta(pathAngle, stylusAngle) * stylusWeight
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
        segmentBristleAcrossStart = bristleAcross
        responseLength = maxOf(
            MIN_RESPONSE_PX,
            contactRadius * (RESPONSE_BASE + RESPONSE_PRESSURE * normalizedPressure(pressure)),
        )

        segmentInkStart = inkUse
        val radiusFraction = (contactRadius / maxOf(baseRadius, 1f)).coerceIn(0f, 1f)
        val contact = MIN_INK_CONTACT + (1f - MIN_INK_CONTACT) * radiusFraction
        segmentInkEnd = inkUse + distance / maxOf(baseRadius, 1f) * contact

        val push = abs(cos(pathAngle - stylusAngle)) * tiltFraction
        val drying = 1f - FAST_DRYING * speedFraction.coerceIn(0f, 1f) - PUSH_DRYING * push
        segmentDrying = drying.coerceIn(MIN_DRYING, 1f)
    }

    fun angleAt(fraction: Float): Float {
        if (!axisReady) return 0f

        val travelled = segmentDistance * fraction.coerceIn(0f, 1f)
        val response = 1f - exp(-travelled / responseLength)
        return segmentAxisStart + axisDelta(segmentAxisStart, segmentAxisTarget) * response
    }

    fun aspectAt(pressure: Float, tiltFraction: Float): Float {
        if (!axisReady) return 1f

        val splay = sqrt(normalizedPressure(pressure))
        val pressureAspect = LIGHT_CONTACT_ASPECT +
            (fullContactAspect - LIGHT_CONTACT_ASPECT) * splay
        return (pressureAspect - TILT_ASPECT_LOSS * tiltFraction)
            .coerceIn(TipShape.Flat.MIN_ASPECT, 1f)
    }

    fun wetnessAt(fraction: Float): Float {
        val use = segmentInkStart + (segmentInkEnd - segmentInkStart) * fraction.coerceIn(0f, 1f)
        return loadedFraction(use) * segmentDrying
    }

    /** Writes the tuft orientation, ink load, and transported material phase. */
    fun writeSampleAt(fraction: Float, out: InkBrushSample) {
        val f = fraction.coerceIn(0f, 1f)
        val sampleAngle = angleAt(f)
        // Project centre travel through the midpoint axis. This transports the
        // material field even when a lagged tuft moves partly across itself.
        val middleAngle = segmentAxisStart +
            axisDelta(segmentAxisStart, sampleAngle) * 0.5f
        val relativeAngle = segmentPathAngle - middleAngle
        val travel = segmentDistance * f

        out.angle = sampleAngle
        out.wetness = wetnessAt(f)
        out.bristleAlong = segmentBristleAlongStart + travel * cos(relativeAngle)
        out.bristleAcross = segmentBristleAcrossStart + travel * sin(relativeAngle)
    }

    fun currentAngle(): Float = if (axisReady) axis else 0f

    fun currentAspect(pressure: Float, tiltFraction: Float): Float =
        aspectAt(pressure, tiltFraction)

    fun currentWetness(speedFraction: Float): Float =
        loadedFraction(inkUse) * (1f - FAST_DRYING * speedFraction.coerceIn(0f, 1f))

    fun currentBristleAlong(): Float = bristleAlong

    fun currentBristleAcross(): Float = bristleAcross

    fun finishSegment(pressure: Float) {
        writeSampleAt(1f, segmentEndSample)
        bristleAlong = segmentEndSample.bristleAlong
        bristleAcross = segmentEndSample.bristleAcross
        axis = segmentEndSample.angle
        inkUse = segmentInkEnd
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
        other.bristleAcross = bristleAcross
        other.segmentBristleAlongStart = segmentBristleAlongStart
        other.segmentBristleAcrossStart = segmentBristleAcrossStart
        other.inkUse = inkUse
        other.segmentInkStart = segmentInkStart
        other.segmentInkEnd = segmentInkEnd
        other.segmentDrying = segmentDrying
        other.stationaryPressurePeak = stationaryPressurePeak
    }

    private fun loadedFraction(use: Float): Float = 1f / (1f + use / INK_CAPACITY)

    private fun normalizedPressure(pressure: Float): Float =
        if (pressure.isFinite()) pressure.coerceIn(0f, 1f) else 0f

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

    private companion object {
        const val PI_FLOAT = PI.toFloat()
        const val HALF_PI = (PI / 2.0).toFloat()

        const val MIN_RESPONSE_PX = 1f
        const val RESPONSE_BASE = 2.4f
        const val RESPONSE_PRESSURE = 1.4f
        const val STYLUS_AXIS_WEIGHT = 0.45f

        const val LIGHT_CONTACT_ASPECT = 0.82f
        const val DEFAULT_FULL_CONTACT_ASPECT = 0.58f
        const val TILT_ASPECT_LOSS = 0.08f

        const val MIN_INK_CONTACT = 0.25f
        const val INK_CAPACITY = 8f
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
    var bristleAlong = 0f
    var bristleAcross = 0f
}
