package ch.lkmc.bangnidraw.engine.core

import kotlin.math.ceil

/** Freezes read-modify-write parameters once, at pen-down. */
object RmwStrokePolicy {

    fun spec(kind: ToolKind, mixer: ColorMixer): RmwSpec? = when (kind) {
        is ToolKind.Smudge -> {
            val params = kind.params
            RmwSpec.Smudge(
                pickupRate = params.pickupRate,
                pickupEdge = ceil(params.sizeMax).toInt() + PICKUP_FEATHER_PX,
                mixing = if (params.mixing && mixer.isPigment) {
                    RmwMixing.Pigment
                } else {
                    RmwMixing.Linear
                },
            )
        }
        is ToolKind.Blur -> RmwSpec.Blur(
            BlurKernel.radius(kind.params.size, kind.params.radiusFraction),
        )
        else -> null
    }

    private const val PICKUP_FEATHER_PX = 2
}
