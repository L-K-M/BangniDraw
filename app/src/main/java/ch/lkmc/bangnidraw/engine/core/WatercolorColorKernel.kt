package ch.lkmc.bangnidraw.engine.core

import ch.lkmc.bangnidraw.engine.core.StrokeMerge.ColorLerp
import ch.lkmc.bangnidraw.engine.core.StrokeMerge.Rgba
import ch.lkmc.bangnidraw.engine.core.StrokeMerge.Scratch

/** Pure reference for one invocation of the watercolor color fragment shader. */
object WatercolorColorKernel {

    enum class DepositMode { PIGMENT, CLEAR_WATER }

    enum class AlphaLock { DISABLED, ENABLED }

    /** Straight brush color, matching the shader's `u_color`. */
    data class StraightRgb(val red: Float, val green: Float, val blue: Float) {
        init {
            requireUnit("red", red)
            requireUnit("green", green)
            requireUnit("blue", blue)
        }
    }

    data class Neighbors(
        val north: Rgba,
        val east: Rgba,
        val south: Rgba,
        val west: Rgba,
    ) {
        init {
            requirePremultiplied("north", north)
            requirePremultiplied("east", east)
            requirePremultiplied("south", south)
            requirePremultiplied("west", west)
        }
    }

    /** Normalized wet-state, paper, and dab inputs for one output pixel. */
    data class Parameters(
        val surfaceWater: Float,
        val absorbedSaturation: Float,
        val spread: Float,
        val flowMask: Float,
        val paperRelief: Float,
        val granulation: Float,
        val dabMask: Float,
        val normalizedRadius: Float,
        val strength: Float,
        val neighborDepositAverage: Float,
        val edgeDarkening: Float,
        val dilution: Float,
        val color: StraightRgb,
        val depositMode: DepositMode,
        val alphaLock: AlphaLock,
    ) {
        init {
            requireUnit("surfaceWater", surfaceWater)
            requireUnit("absorbedSaturation", absorbedSaturation)
            requireUnit("spread", spread)
            requireUnit("flowMask", flowMask)
            requireUnit("paperRelief", paperRelief)
            requireUnit("granulation", granulation)
            requireUnit("dabMask", dabMask)
            require(normalizedRadius.isFinite() && normalizedRadius >= 0f) {
                "normalizedRadius must be finite and non-negative, was $normalizedRadius"
            }
            requireUnit("strength", strength)
            requireUnit("neighborDepositAverage", neighborDepositAverage)
            requireUnit("edgeDarkening", edgeDarkening)
            requireUnit("dilution", dilution)
        }
    }

    fun evaluate(
        center: Rgba,
        neighbors: Neighbors,
        parameters: Parameters,
        lerp: ColorLerp = ColorLerp.Linear,
        scratch: Scratch = Scratch(),
    ): Rgba {
        requirePremultiplied("center", center)

        val average = average(neighbors)
        val paper = paperMobility(parameters)
        val flow = minOf(
            WatercolorKernel.MAX_DIFFUSION,
            (
                parameters.surfaceWater +
                    parameters.absorbedSaturation * WatercolorKernel.ABSORBED_FLOW_WEIGHT
                ) * parameters.spread * parameters.flowMask * paper,
        )
        var flowed = mixPigment(center, average, flow, lerp, scratch)
        if (parameters.alphaLock == AlphaLock.ENABLED) {
            flowed = retainAlpha(flowed, center.a)
        }

        if (parameters.depositMode == DepositMode.CLEAR_WATER) {
            return clampPremultiplied(flowed)
        }

        // Carry this dab's source alpha with the same wet flow as existing pigment.
        val localDeposit = pigmentDeposit(parameters, paper)
        val deposit = localDeposit +
            flow * (parameters.neighborDepositAverage - localDeposit)
        val result = depositPigment(flowed, deposit, parameters, lerp, scratch)

        return clampPremultiplied(result)
    }

    private fun average(neighbors: Neighbors): Rgba = Rgba(
        r = (neighbors.north.r + neighbors.east.r + neighbors.south.r + neighbors.west.r) *
            FOUR_NEIGHBOR_SCALE,
        g = (neighbors.north.g + neighbors.east.g + neighbors.south.g + neighbors.west.g) *
            FOUR_NEIGHBOR_SCALE,
        b = (neighbors.north.b + neighbors.east.b + neighbors.south.b + neighbors.west.b) *
            FOUR_NEIGHBOR_SCALE,
        a = (neighbors.north.a + neighbors.east.a + neighbors.south.a + neighbors.west.a) *
            FOUR_NEIGHBOR_SCALE,
    )

    private fun paperMobility(parameters: Parameters): Float {
        val reliefMobility = WatercolorKernel.PAPER_MOBILITY_MIN +
            WatercolorKernel.PAPER_MOBILITY_RANGE * parameters.paperRelief

        return 1f + (reliefMobility - 1f) * parameters.granulation
    }

    private fun pigmentDeposit(parameters: Parameters, paper: Float): Float {
        val rim = smoothstep(
            WatercolorKernel.RIM_INNER_RADIUS,
            WatercolorKernel.RIM_OUTER_RADIUS,
            parameters.normalizedRadius,
        ) * parameters.dabMask

        return (
            parameters.strength * parameters.dabMask * paper *
                (
                    1f + WatercolorKernel.RIM_DEPOSIT_GAIN *
                        parameters.edgeDarkening * rim
                    )
            ).coerceIn(0f, 1f)
    }

    private fun mixPigment(
        center: Rgba,
        average: Rgba,
        amount: Float,
        lerp: ColorLerp,
        scratch: Scratch,
    ): Rgba {
        val alpha = center.a + (average.a - center.a) * amount
        if (alpha <= SmudgeKernel.ALPHA_EPSILON) return Rgba.TRANSPARENT

        if (center.a <= SmudgeKernel.ALPHA_EPSILON) {
            val sourceAlpha = maxOf(average.a, SmudgeKernel.ALPHA_EPSILON)

            return Rgba(
                average.r / sourceAlpha * alpha,
                average.g / sourceAlpha * alpha,
                average.b / sourceAlpha * alpha,
                alpha,
            )
        }
        if (average.a <= SmudgeKernel.ALPHA_EPSILON) {
            return Rgba(
                center.r / center.a * alpha,
                center.g / center.a * alpha,
                center.b / center.a * alpha,
                alpha,
            )
        }

        setStraight(scratch.from, center)
        setStraight(scratch.to, average)
        lerp.lerp(scratch.from, scratch.to, amount.coerceIn(0f, 1f), scratch.out)
        requireFiniteLerp(scratch.out)

        return premultiply(scratch.out, alpha)
    }

    private fun retainAlpha(color: Rgba, alpha: Float): Rgba {
        if (color.a <= SmudgeKernel.ALPHA_EPSILON) return Rgba(0f, 0f, 0f, alpha)

        return Rgba(
            color.r / color.a * alpha,
            color.g / color.a * alpha,
            color.b / color.a * alpha,
            alpha,
        )
    }

    private fun depositPigment(
        layer: Rgba,
        sourceAlpha: Float,
        parameters: Parameters,
        lerp: ColorLerp,
        scratch: Scratch,
    ): Rgba {
        if (sourceAlpha <= 0f) return layer

        var alpha = sourceAlpha + layer.a * (1f - sourceAlpha)
        var amount = if (alpha > 0f) sourceAlpha / alpha else 0f
        if (layer.a > 0f) amount *= 1f - parameters.dilution

        if (parameters.alphaLock == AlphaLock.ENABLED) {
            if (layer.a <= 0f) return layer

            amount = sourceAlpha
            alpha = layer.a
        }

        if (
            layer.a <= SmudgeKernel.ALPHA_EPSILON &&
            parameters.alphaLock == AlphaLock.DISABLED
        ) {
            return Rgba(
                parameters.color.red * sourceAlpha,
                parameters.color.green * sourceAlpha,
                parameters.color.blue * sourceAlpha,
                sourceAlpha,
            )
        }

        setStraight(scratch.from, layer)
        scratch.to[0] = parameters.color.red
        scratch.to[1] = parameters.color.green
        scratch.to[2] = parameters.color.blue
        lerp.lerp(scratch.from, scratch.to, amount.coerceIn(0f, 1f), scratch.out)
        requireFiniteLerp(scratch.out)

        return premultiply(scratch.out, alpha)
    }

    private fun setStraight(out: FloatArray, color: Rgba) {
        out[0] = color.r / color.a
        out[1] = color.g / color.a
        out[2] = color.b / color.a
    }

    private fun premultiply(straight: FloatArray, alpha: Float): Rgba = Rgba(
        straight[0] * alpha,
        straight[1] * alpha,
        straight[2] * alpha,
        alpha,
    )

    private fun clampPremultiplied(color: Rgba): Rgba {
        val alpha = color.a.coerceIn(0f, 1f)

        return Rgba(
            color.r.coerceIn(0f, 1f).coerceAtMost(alpha),
            color.g.coerceIn(0f, 1f).coerceAtMost(alpha),
            color.b.coerceIn(0f, 1f).coerceAtMost(alpha),
            alpha,
        )
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val amount = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)

        return amount * amount * (3f - 2f * amount)
    }

    private fun requirePremultiplied(name: String, color: Rgba) {
        require(color.r.isFinite() && color.g.isFinite() && color.b.isFinite() && color.a.isFinite() &&
            color.a in 0f..1f &&
            color.r in 0f..color.a && color.g in 0f..color.a && color.b in 0f..color.a) {
            "$name must be finite premultiplied RGBA in 0..1, was $color"
        }
    }

    private fun requireFiniteLerp(color: FloatArray) {
        require(color[0].isFinite() && color[1].isFinite() && color[2].isFinite()) {
            "ColorLerp must return finite RGB components"
        }
    }

    private fun requireUnit(name: String, value: Float) {
        require(value.isFinite() && value in 0f..1f) { "$name must be 0..1, was $value" }
    }

    private const val FOUR_NEIGHBOR_SCALE = 0.25f
}
