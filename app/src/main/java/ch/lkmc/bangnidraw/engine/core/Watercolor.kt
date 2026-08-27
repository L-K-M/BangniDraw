package ch.lkmc.bangnidraw.engine.core

import kotlin.math.ceil
import kotlin.math.floor

/** Pure reference arithmetic shared by the wet-state pass and JVM tests. */
object WatercolorKernel {
    /** One wet texel covers this many canvas pixels on each axis. */
    const val CELL_SIZE = 4

    /** One explicit four-neighbor step stays a convex blend at or below 0.25. */
    const val MAX_DIFFUSION = 0.24f

    const val ABSORPTION_PER_STEP = 0.08f
    const val ABSORBED_FLOW_WEIGHT = 0.4f

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

    /** A ten-hertz wet clock keeps paint active for twelve seconds. */
    const val DRY_TICKS = 120

    const val TICK_NANOS = 100_000_000L

    /** Wetness reaches zero this long after its last update. */
    const val DRY_TIME_MILLIS = DRY_TICKS * TICK_NANOS / 1_000_000L

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

    fun ageMillis(nowMillis: Long, updatedAtMillis: Long): Long {
        require(nowMillis >= 0L) { "nowMillis must not be negative, was $nowMillis" }
        require(updatedAtMillis >= 0L) {
            "updatedAtMillis must not be negative, was $updatedAtMillis"
        }
        if (nowMillis <= updatedAtMillis) return 0L

        return nowMillis - updatedAtMillis
    }

    fun tickAt(monotonicNanos: Long): Int {
        require(monotonicNanos >= 0L) {
            "monotonicNanos must not be negative, was $monotonicNanos"
        }

        return ((monotonicNanos / TICK_NANOS) % TICK_MODULUS).toInt()
    }

    fun ageTicks(nowTick: Int, updatedTick: Int): Int {
        require(nowTick in 0 until TICK_MODULUS) { "nowTick must fit two bytes, was $nowTick" }
        require(updatedTick in 0 until TICK_MODULUS) {
            "updatedTick must fit two bytes, was $updatedTick"
        }

        return (nowTick - updatedTick + TICK_MODULUS) % TICK_MODULUS
    }

    fun retention(
        elapsedMillis: Long,
        dryTimeMillis: Long = DRY_TIME_MILLIS,
    ): Float {
        require(elapsedMillis >= 0L) { "elapsedMillis must not be negative, was $elapsedMillis" }
        require(dryTimeMillis > 0L) { "dryTimeMillis must be positive, was $dryTimeMillis" }
        if (elapsedMillis >= dryTimeMillis) return 0f

        return 1f - elapsedMillis.toFloat() / dryTimeMillis.toFloat()
    }

    fun dry(
        wetness: Float,
        elapsedMillis: Long,
        dryTimeMillis: Long = DRY_TIME_MILLIS,
    ): Float {
        requireUnit("wetness", wetness)

        return wetness * retention(elapsedMillis, dryTimeMillis)
    }

    private fun requireUnit(name: String, value: Float) {
        require(value.isFinite() && value in 0f..1f) {
            "$name must be 0..1, was $value"
        }
    }
}

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
