package ch.lkmc.bangnidraw.engine.core

/**
 * The preset that drives the rail and ledge size/opacity sliders
 * (`docs/plan/08-ui-and-layout.md` §3.2: the two thin sliders "edit the
 * *active tool's* size and opacity").
 *
 * Brushes carry their own [BrushPreset]. Read-modify-write tools carry
 * [SmudgeParams]/[BlurParams], and the stroke path already synthesizes a
 * preset for them ([RmwDabPreset]); the sliders show that same synthesis, so
 * the numbers on the sliders are the numbers the next stroke uses. Smudge
 * and blur have no opacity — their per-dab intensity is *strength* — so the
 * opacity slider edits strength, carried on the synthesized preset's
 * `opacity` field where [ToolRail]'s sliders read it.
 *
 * Fill and the eyedropper take no size, so they get no sliders.
 */
object ToolSliderPreset {

    fun forKind(kind: ToolKind): BrushPreset? = when (kind) {
        is ToolKind.Brush -> kind.preset
        is ToolKind.Smudge -> RmwDabPreset.smudge(kind.params)
            .withOpacity(kind.params.strength)
        is ToolKind.Blur -> RmwDabPreset.blur(kind.params)
            .withOpacity(kind.params.strength)
        is ToolKind.Fill, is ToolKind.Eyedropper -> null
    }
}
