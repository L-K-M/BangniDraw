package ch.lkmc.bangnidraw.engine.core

import kotlin.math.floor

/** Pure reference for one invocation of the watercolor wet fragment shader. */
object WatercolorWetKernel {

    enum class Mode { UPDATE, AGE_ONLY, EPOCH_REBASE }

    data class EncodedTick(val high: Float, val low: Float)

    /** RGBA8 wet texel expressed as normalized shader channels. */
    data class StoredCell(
        val surfaceWater: Float,
        val tickHigh: Float,
        val tickLow: Float,
        val saturation: Float,
    ) {
        init {
            requireUnit("surfaceWater", surfaceWater)
            requireUnit("tickHigh", tickHigh)
            requireUnit("tickLow", tickLow)
            requireUnit("saturation", saturation)
        }
    }

    /** One sampled texel and the dab mask evaluated at that texel. */
    data class Cell(val stored: StoredCell, val sourceMask: Float) {
        init {
            requireUnit("sourceMask", sourceMask)
        }
    }

    data class Neighbors(
        val north: Cell,
        val east: Cell,
        val south: Cell,
        val west: Cell,
    )

    data class Parameters(
        val waterLoad: Float,
        val spread: Float,
        val flowMask: Float,
        val paperRelief: Float,
        val nowTick: Int,
        val mode: Mode = Mode.UPDATE,
    ) {
        init {
            requireUnit("waterLoad", waterLoad)
            requireUnit("spread", spread)
            requireUnit("flowMask", flowMask)
            requireUnit("paperRelief", paperRelief)
            require(nowTick in 0 until WatercolorKernel.TICK_MODULUS) {
                "nowTick must fit two bytes, was $nowTick"
            }
        }
    }

    fun step(
        center: Cell,
        neighbors: Neighbors,
        parameters: Parameters,
    ): StoredCell {
        val previous = center.stored
        val aged = WatercolorKernel.evaporate(
            surfaceWater = previous.surfaceWater,
            saturation = previous.saturation,
            elapsedTicks = elapsedTicks(previous, parameters.nowTick, parameters.mode),
        )
        val stamp = encodeTick(parameters.nowTick)
        if (parameters.mode != Mode.UPDATE) {
            return StoredCell(aged.surfaceWater, stamp.high, stamp.low, aged.saturation)
        }

        val centerWet = suppliedWet(center, parameters)
        val average = (
            suppliedWet(neighbors.north, parameters) +
                suppliedWet(neighbors.east, parameters) +
                suppliedWet(neighbors.south, parameters) +
                suppliedWet(neighbors.west, parameters)
            ) * FOUR_NEIGHBOR_SCALE
        val diffusion = FOUR_NEIGHBOR_COUNT * WatercolorKernel.MAX_DIFFUSION *
            parameters.spread * parameters.flowMask
        var wet = (centerWet + diffusion * (average - centerWet)).coerceIn(0f, 1f)

        var saturation = aged.saturation
        val paperPocket = 1f - parameters.paperRelief
        val paperCapacity = WatercolorKernel.PAPER_CAPACITY_MIN +
            WatercolorKernel.PAPER_CAPACITY_RANGE * paperPocket
        val paperAbsorption = WatercolorKernel.PAPER_ABSORPTION_MIN +
            WatercolorKernel.PAPER_ABSORPTION_RANGE * paperPocket
        val absorbed = minOf(
            wet,
            WatercolorKernel.ABSORPTION_PER_STEP * paperAbsorption * paperCapacity *
                (1f - saturation) * parameters.flowMask,
        )
        wet -= absorbed
        saturation = (saturation + absorbed).coerceIn(0f, 1f)

        return StoredCell(wet, stamp.high, stamp.low, saturation)
    }

    fun encodeTick(tick: Int): EncodedTick {
        require(tick in 0 until WatercolorKernel.TICK_MODULUS) {
            "tick must fit two bytes, was $tick"
        }

        val high = tick / WatercolorKernel.TICK_RADIX
        val low = tick - high * WatercolorKernel.TICK_RADIX
        return EncodedTick(
            high = high.toFloat() / WatercolorKernel.CHANNEL_MAX,
            low = low.toFloat() / WatercolorKernel.CHANNEL_MAX,
        )
    }

    fun decodeTick(high: Float, low: Float): Int {
        requireUnit("high", high)
        requireUnit("low", low)

        val highByte = floor(high * WatercolorKernel.CHANNEL_MAX + CHANNEL_ROUNDING).toInt()
        val lowByte = floor(low * WatercolorKernel.CHANNEL_MAX + CHANNEL_ROUNDING).toInt()
        return highByte * WatercolorKernel.TICK_RADIX + lowByte
    }

    /** Raw paper relief used by both watercolor passes; it never follows a dab. */
    fun paperRelief(canvasX: Float, canvasY: Float): Float {
        require(canvasX.isFinite()) { "canvasX must be finite, was $canvasX" }
        require(canvasY.isFinite()) { "canvasY must be finite, was $canvasY" }

        val x = floor(canvasX.coerceAtLeast(0f)).toInt().toUInt()
        val y = floor(canvasY.coerceAtLeast(0f)).toInt().toUInt()
        var hash = x * DabStamp.GRAIN_HASH_X.toUInt() + y * DabStamp.GRAIN_HASH_Y.toUInt()
        hash = hash xor (hash shr DabStamp.GRAIN_HASH_SHIFT)

        return (hash and DabStamp.GRAIN_HASH_MASK.toUInt()).toFloat() /
            DabStamp.GRAIN_HASH_MASK
    }

    private fun suppliedWet(cell: Cell, parameters: Parameters): Float {
        val aged = WatercolorKernel.evaporate(
            surfaceWater = cell.stored.surfaceWater,
            saturation = cell.stored.saturation,
            elapsedTicks = elapsedTicks(cell.stored, parameters.nowTick, Mode.UPDATE),
        )
        val source = (parameters.waterLoad * cell.sourceMask).coerceIn(0f, 1f)
        return aged.surfaceWater + source * (1f - aged.surfaceWater)
    }

    private fun elapsedTicks(cell: StoredCell, nowTick: Int, mode: Mode): Int {
        val updatedTick = decodeTick(cell.tickHigh, cell.tickLow)
        if (mode == Mode.EPOCH_REBASE && updatedTick <= nowTick) {
            return WatercolorKernel.MAX_DRY_TICKS
        }

        return WatercolorKernel.ageTicks(nowTick, updatedTick)
    }

    private fun requireUnit(name: String, value: Float) {
        require(value.isFinite() && value in 0f..1f) { "$name must be 0..1, was $value" }
    }

    private const val FOUR_NEIGHBOR_COUNT = 4f
    private const val FOUR_NEIGHBOR_SCALE = 0.25f
    private const val CHANNEL_ROUNDING = 0.5f
}
