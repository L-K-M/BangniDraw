package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.engine.core.BrushPreset

/** Quick-control values stored separately from the editable preset JSON. */
internal data class BrushTuning(
    val size: Float?,
    val opacity: Float?,
    val flow: Float?,
)

/** Applies only the quick controls that have meaning for this brush type. */
internal fun BrushPreset.applyTuning(tuning: BrushTuning?): BrushPreset {
    val resized = withSize(tuning?.size ?: size)
    if (watercolor == null) {
        return resized.withOpacity(tuning?.opacity ?: opacity)
    }

    val storedFlow = tuning?.flow
    val tunedFlow = if (storedFlow == null || storedFlow.isNaN()) {
        resized.flow
    } else {
        storedFlow.coerceIn(0f, 1f)
    }
    return resized.copy(
        opacity = 1f,
        flow = tunedFlow,
    )
}

/** Omits controls the renderer cannot honor, removing their obsolete keys. */
internal fun BrushPreset.persistedTuning(): BrushTuning =
    if (watercolor == null) {
        BrushTuning(
            size = size,
            opacity = opacity,
            flow = null,
        )
    } else {
        BrushTuning(
            size = size,
            opacity = null,
            flow = flow,
        )
    }
