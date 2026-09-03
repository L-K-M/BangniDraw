package ch.lkmc.bangnidraw.engine.core

/**
 * The size preset and secondary value for the rail and ledge sliders.
 *
 * A watercolor preset stays unchanged because its opacity invariant is real;
 * [secondaryValue] presents flow without constructing an invalid copy. The
 * Water tool's synthesized preset carries sizes only — its water load lives
 * on [secondaryValue] alone, never smuggled through the opacity field.
 *
 * [ToolSliderSecondary] tells the caller which domain field an edit updates.
 */
enum class ToolSliderSecondary { OPACITY, FLOW, WATER }

object ToolSliderPreset {

    fun forKind(kind: ToolKind): BrushPreset? = when (kind) {
        is ToolKind.Brush -> kind.preset
        is ToolKind.Smudge -> RmwDabPreset.smudge(kind.params)
            .withOpacity(kind.params.strength)
        is ToolKind.Blur -> RmwDabPreset.blur(kind.params)
            .withOpacity(kind.params.strength)
        is ToolKind.Water -> RmwDabPreset.water(kind.params)
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

    /**
     * [preset] with its secondary slider moved to [value] — the write half of
     * [secondaryValue], so the two cannot disagree about which field the
     * slider owns. A watercolor preset's opacity is an invariant, so the
     * edit lands on flow instead (`docs/plan/04-tools.md` §5).
     *
     * NaN keeps the current value rather than writing itself in: a slider
     * whose track has not been measured yet divides by a zero width, and one
     * NaN in a preset poisons every stroke drawn with it afterwards.
     */
    fun withSecondary(preset: BrushPreset, value: Float): BrushPreset {
        if (preset.watercolor == null) return preset.withOpacity(value)

        return preset.copy(flow = if (value.isNaN()) preset.flow else value.coerceIn(0f, 1f))
    }

    fun secondaryFor(kind: ToolKind): ToolSliderSecondary = when {
        kind is ToolKind.Water -> ToolSliderSecondary.WATER
        kind is ToolKind.Brush && kind.preset.watercolor != null -> ToolSliderSecondary.FLOW
        else -> ToolSliderSecondary.OPACITY
    }
}
