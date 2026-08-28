package ch.lkmc.bangnidraw.engine.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Pure reference arithmetic shared by the wet-state pass and JVM tests. */
object WatercolorKernel {
    /** One wet texel covers this many canvas pixels on each axis. */
    const val CELL_SIZE = 4

    /** One explicit four-neighbor step stays a convex blend at or below 0.25. */
    const val MAX_DIFFUSION = 0.24f

    const val ABSORPTION_PER_STEP = 0.08f
    const val ABSORBED_FLOW_WEIGHT = 0.4f

    /** Paper valleys approach full absorption; raised fibers shed more water. */
    const val PAPER_ABSORPTION_MIN = 0.55f
    const val PAPER_ABSORPTION_RANGE = 0.45f
    const val PAPER_CAPACITY_MIN = 0.7f
    const val PAPER_CAPACITY_RANGE = 0.3f

    /** Paper noise slows pigment without ever stopping it. */
    const val PAPER_MOBILITY_MIN = 0.55f
    const val PAPER_MOBILITY_RANGE = 0.45f

    /** Normalized dab radii that bound the darker deposited rim. */
    const val RIM_INNER_RADIUS = 0.55f
    const val RIM_OUTER_RADIUS = 0.95f
    const val RIM_DEPOSIT_GAIN = 0.5f

    /** Two normalized RGBA8 channels encode the lazy update tick. */
    const val CHANNEL_MAX = 255
    const val TICK_RADIX = 256

    /** Wet tiles store update ticks in two bytes, so age wraps at this value. */
    const val TICK_MODULUS = TICK_RADIX * TICK_RADIX

    const val TICK_NANOS = 100_000_000L

    /** Constant evaporation removes one water unit in twenty-four seconds. */
    const val FULL_LOAD_DRY_TICKS = 240
    const val FULL_LOAD_DRY_TIME_MILLIS =
        FULL_LOAD_DRY_TICKS * TICK_NANOS / 1_000_000L

    /** Surface and absorbed reservoirs can jointly hold two water units. */
    const val MAX_WATER_UNITS = 2
    const val MAX_DRY_TICKS = FULL_LOAD_DRY_TICKS * MAX_WATER_UNITS
    const val MAX_DRY_NANOS = MAX_DRY_TICKS * TICK_NANOS

    /** Quarter-resolution storage, padded to TileGrid's minimum side. */
    fun wetPixels(canvasPixels: Int): Int {
        require(canvasPixels in TileGrid.MIN_EDGE..TileGrid.MAX_EDGE) {
            "canvasPixels must be ${TileGrid.MIN_EDGE}..${TileGrid.MAX_EDGE}, was $canvasPixels"
        }

        val coarsePixels = (canvasPixels + CELL_SIZE - 1) / CELL_SIZE
        return coarsePixels.coerceAtLeast(TileGrid.MIN_EDGE)
    }

    fun diffuse(
        center: Float,
        north: Float,
        east: Float,
        south: Float,
        west: Float,
        spread: Float,
    ): Float {
        requireUnit("center", center)
        requireUnit("north", north)
        requireUnit("east", east)
        requireUnit("south", south)
        requireUnit("west", west)
        requireUnit("spread", spread)

        val neighborWeight = spread * MAX_DIFFUSION
        val centerWeight = 1f - 4f * neighborWeight
        return (
            center * centerWeight +
                (north + east + south + west) * neighborWeight
            ).coerceIn(0f, 1f)
    }

    /** Covers every corner of a coarse cell for any valid tip rotation. */
    fun wetCoverageInflation(aspect: Float): Float {
        require(aspect.isFinite() && aspect in TipShape.Flat.MIN_ASPECT..1f) {
            "tip aspect must be ${TipShape.Flat.MIN_ASPECT}..1, was $aspect"
        }

        val halfCell = CELL_SIZE * 0.5f
        return halfCell * sqrt(2f) / aspect
    }

    fun ageMillis(nowMillis: Long, updatedAtMillis: Long): Long {
        if (nowMillis <= updatedAtMillis) return 0L

        return nowMillis - updatedAtMillis
    }

    fun tickAt(monotonicNanos: Long): Int = Math.floorMod(
        Math.floorDiv(monotonicNanos, TICK_NANOS),
        TICK_MODULUS.toLong(),
    ).toInt()

    fun tickEpoch(monotonicNanos: Long): Long = Math.floorDiv(
        Math.floorDiv(monotonicNanos, TICK_NANOS),
        TICK_MODULUS.toLong(),
    )

    fun isExpired(nowNanos: Long, updatedAtNanos: Long): Boolean =
        nowNanos - updatedAtNanos >= MAX_DRY_NANOS

    fun ageTicks(nowTick: Int, updatedTick: Int): Int {
        require(nowTick in 0 until TICK_MODULUS) { "nowTick must fit two bytes, was $nowTick" }
        require(updatedTick in 0 until TICK_MODULUS) {
            "updatedTick must fit two bytes, was $updatedTick"
        }

        return (nowTick - updatedTick + TICK_MODULUS) % TICK_MODULUS
    }

    fun retention(
        elapsedMillis: Long,
        dryTimeMillis: Long = FULL_LOAD_DRY_TIME_MILLIS,
    ): Float {
        require(elapsedMillis >= 0L) { "elapsedMillis must not be negative, was $elapsedMillis" }
        require(dryTimeMillis > 0L) { "dryTimeMillis must be positive, was $dryTimeMillis" }
        if (elapsedMillis >= dryTimeMillis) return 0f

        return 1f - elapsedMillis.toFloat() / dryTimeMillis.toFloat()
    }

    fun dry(
        wetness: Float,
        elapsedMillis: Long,
        dryTimeMillis: Long = FULL_LOAD_DRY_TIME_MILLIS,
    ): Float {
        requireUnit("wetness", wetness)

        val evaporated = 1f - retention(elapsedMillis, dryTimeMillis)
        return (wetness - evaporated).coerceAtLeast(0f)
    }

    internal fun evaporate(
        surfaceWater: Float,
        saturation: Float,
        elapsedTicks: Int,
    ): WaterAmounts {
        requireUnit("surfaceWater", surfaceWater)
        requireUnit("saturation", saturation)
        require(elapsedTicks >= 0) { "elapsedTicks must not be negative, was $elapsedTicks" }

        val total = surfaceWater + saturation
        if (total == 0f) return WaterAmounts(surfaceWater = 0f, saturation = 0f)

        // Remove a fixed volume while preserving the surface/absorbed split.
        val evaporated = elapsedTicks.toFloat() / FULL_LOAD_DRY_TICKS
        val remaining = (total - evaporated).coerceAtLeast(0f)
        val scale = remaining / total
        return WaterAmounts(
            surfaceWater = surfaceWater * scale,
            saturation = saturation * scale,
        )
    }

    private fun requireUnit(name: String, value: Float) {
        require(value.isFinite() && value in 0f..1f) {
            "$name must be 0..1, was $value"
        }
    }
}

internal data class WaterAmounts(
    val surfaceWater: Float,
    val saturation: Float,
)

/** Full-resolution color and quarter-resolution wet footprints for one dab. */
data class WatercolorDabPlan(
    val output: IntRect,
    val source: IntRect,
    val wetOutput: IntRect,
    val wetSource: IntRect,
) {
    companion object {
        /** Limits one dab's diffusion work while still allowing visible blooms. */
        const val MAX_SPREAD_PX = 32

        /** GLES 3.0 guarantees at least this texture edge. */
        const val MIN_GL_TEXTURE_SIZE = 2048

        /** Leaves spread, wet-cell alignment, and a source halo in one scratch texture. */
        const val MAX_DIAMETER_PX = 1960f

        const val SPREAD_RADIUS_FRACTION = 0.5f
        private const val WET_SOURCE_HALO = 1

        fun forDab(
            grid: TileGrid,
            x: Float,
            y: Float,
            radius: Float,
            spread: Float,
        ): WatercolorDabPlan {
            require(spread.isFinite() && spread in 0f..1f) {
                "watercolor spread must be 0..1, was $spread"
            }

            val diameter = radius * 2f
            require(radius.isFinite() && radius >= 0f && diameter <= MAX_DIAMETER_PX) {
                "watercolor diameter exceeds the GLES scratch bound"
            }

            val spreadPx = ceil(radius * spread * SPREAD_RADIUS_FRACTION)
                .toInt()
                .coerceAtMost(MAX_SPREAD_PX)
            val dab = IntRect.forDab(x, y, radius)
            val output = dab.inflate(spreadPx).clip(0, 0, grid.width, grid.height)
            if (output.isEmpty) {
                return WatercolorDabPlan(
                    output = IntRect.EMPTY,
                    source = IntRect.EMPTY,
                    wetOutput = IntRect.EMPTY,
                    wetSource = IntRect.EMPTY,
                )
            }

            val wetWidth = ceilDiv(grid.width, WatercolorKernel.CELL_SIZE)
            val wetHeight = ceilDiv(grid.height, WatercolorKernel.CELL_SIZE)
            val wetOutput = output.toWetRect().clip(0, 0, wetWidth, wetHeight)
            val wetSource = wetOutput.inflate(WET_SOURCE_HALO).clip(0, 0, wetWidth, wetHeight)
            val source = wetSource.toCanvasRect().clip(0, 0, grid.width, grid.height)

            return WatercolorDabPlan(output, source, wetOutput, wetSource)
        }

        private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
    }
}

private fun IntRect.inflate(pixels: Int): IntRect = IntRect(
    left = left - pixels,
    top = top - pixels,
    right = right + pixels,
    bottom = bottom + pixels,
)

private fun IntRect.clip(left: Int, top: Int, right: Int, bottom: Int): IntRect = IntRect(
    left = this.left.coerceIn(left, right),
    top = this.top.coerceIn(top, bottom),
    right = this.right.coerceIn(left, right),
    bottom = this.bottom.coerceIn(top, bottom),
)

private fun IntRect.toWetRect(): IntRect {
    val scale = WatercolorKernel.CELL_SIZE
    return IntRect(
        left = floor(left.toFloat() / scale).toInt(),
        top = floor(top.toFloat() / scale).toInt(),
        right = ceil(right.toFloat() / scale).toInt(),
        bottom = ceil(bottom.toFloat() / scale).toInt(),
    )
}

private fun IntRect.toCanvasRect(): IntRect {
    val scale = WatercolorKernel.CELL_SIZE
    return IntRect(left * scale, top * scale, right * scale, bottom * scale)
}
