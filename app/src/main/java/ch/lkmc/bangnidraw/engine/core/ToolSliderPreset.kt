package ch.lkmc.bangnidraw.engine.core

/**
 * The size preset and secondary value for the rail and ledge sliders.
 *
 * A watercolor preset stays unchanged because its opacity invariant is real;
 * [secondaryValue] presents flow without constructing an invalid copy.
 *
 * [ToolSliderSecondary] tells the caller which domain field an edit updates.
 */
internal enum class ToolSliderSecondary { OPACITY, FLOW, WATER }

object ToolSliderPreset {

    fun forKind(kind: ToolKind): BrushPreset? = when (kind) {
        is ToolKind.Brush -> kind.preset
        is ToolKind.Smudge -> RmwDabPreset.smudge(kind.params)
            .withOpacity(kind.params.strength)
        is ToolKind.Blur -> RmwDabPreset.blur(kind.params)
            .withOpacity(kind.params.strength)
        is ToolKind.Water -> RmwDabPreset.water(kind.params)
            .withOpacity(kind.params.waterLoad)
        is ToolKind.Fill, is ToolKind.Eyedropper -> null
    }

    fun secondaryValue(kind: ToolKind): Float? = when (kind) {
        is ToolKind.Brush -> if (kind.preset.watercolor == null) {
            kind.preset.opacity
        } else {
            kind.preset.flow
        }
        is ToolKind.Smudge -> kind.params.strength
        is ToolKind.Water -> kind.params.waterLoad
        is ToolKind.Blur -> kind.params.strength
        is ToolKind.Fill, is ToolKind.Eyedropper -> null
    }

    internal fun secondaryFor(kind: ToolKind): ToolSliderSecondary = when {
        kind is ToolKind.Water -> ToolSliderSecondary.WATER
        kind is ToolKind.Brush && kind.preset.watercolor != null -> ToolSliderSecondary.FLOW
        else -> ToolSliderSecondary.OPACITY
    }
}
