package ch.lkmc.bangnidraw.engine.core

/** Adapts RMW tool parameters to the shared stabilizer and dab generator. */
object RmwDabPreset {

    fun smudge(params: SmudgeParams): BrushPreset = BrushPreset(
        id = SMUDGE_ID,
        name = SMUDGE_NAME,
        size = params.size,
        sizeMin = params.sizeMin,
        sizeMax = params.sizeMax,
        opacity = 1f,
        flow = params.strength,
        hardness = params.hardness,
        spacing = params.spacing,
        pressureSize = Curve.One,
        pressureOpacity = Curve.One,
        pressureFlow = params.pressureStrength,
        stabilizer = params.stabilizer,
    )

    fun blur(params: BlurParams): BrushPreset = BrushPreset(
        id = BLUR_ID,
        name = BLUR_NAME,
        size = params.size,
        sizeMin = params.sizeMin,
        sizeMax = params.sizeMax,
        opacity = 1f,
        flow = params.strength,
        hardness = 0f,
        spacing = params.spacing,
        pressureSize = Curve.One,
        pressureOpacity = Curve.One,
        pressureFlow = params.pressureStrength,
        stabilizer = 0f,
    )

    private const val SMUDGE_ID = "internal.rmw.smudge"
    private const val SMUDGE_NAME = "Smudge"
    private const val BLUR_ID = "internal.rmw.blur"
    private const val BLUR_NAME = "Blur"
}
