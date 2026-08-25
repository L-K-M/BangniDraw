package ch.lkmc.bangnidraw.engine.core

/**
 * The built-in brushes (`docs/plan/04-tools.md` §5).
 *
 * Only the ink pen ships here, which is what roadmap 2.2 scopes ("the one
 * round preset"). The other six of §5's table arrive with `BrushPresetStore`
 * and their JSON under `assets/brushes/`, because that is the PR that can also
 * check a preset *file* parses — declaring them as Kotlin constants now would
 * pin the numbers in the one place the shipped format is not.
 *
 * §5's table is the spec either way, and these values are copied from it.
 */
object BrushPresets {

    /**
     * Hardness 1 with the shader's 1 px anti-aliasing skirt is a crisp line;
     * flow 1, `Max` buffering and opacity 1 mean overlaps within one stroke
     * are invisible, because ink does not build up.
     *
     * The calligraphic thick/thin comes from pressure → size on a curve that
     * starts at 15 % — a hair line is always available — and rises slowly and
     * then quickly. Velocity thins a fast stroke to 85 %, which reads as
     * confidence. The stabilizer is strong (0.7) because a pen line has
     * nowhere to hide a wobble.
     */
    val INK_PEN = BrushPreset(
        id = "builtin.ink_pen",
        name = "@string/preset_ink_pen",
        icon = "round",
        size = 6f,
        sizeMin = 1f,
        sizeMax = 60f,
        opacity = 1f,
        flow = 1f,
        hardness = 1f,
        spacing = 0.10f,
        tip = TipShape.Round,
        orientation = TipOrientation.Fixed,
        pressureSize = Curve(0.15f, 0.3f, 0.6f, 1f),
        pressureOpacity = Curve.One,
        pressureFlow = Curve.One,
        tilt = TiltEffect.None,
        velocity = VelocityEffect(sizeAtFast = 0.85f, fastPxPerMs = 2f),
        jitter = Jitter.None,
        stabilizer = 0.7f,
        bufferMode = BufferMode.Max,
    )

    /** Every preset this build ships as code. */
    val ALL: List<BrushPreset> = listOf(INK_PEN)

    /** The preset a fresh document opens with. */
    val DEFAULT: BrushPreset get() = INK_PEN
}
