package ch.lkmc.bangnidraw.engine.core

/**
 * The built-in brushes (`docs/plan/04-tools.md` §5).
 *
 * Stable IDs live here; shipped values live in `assets/brushes/`. The ink pen
 * remains the code fallback used before the asset store finishes loading.
 *
 * §5's table is the spec either way, and these values are copied from it.
 */
object BrushPresets {

    const val PENCIL_ID = "builtin.pencil"
    const val INK_PEN_ID = "builtin.ink_pen"
    const val PAINTBRUSH_ID = "builtin.paintbrush"
    const val AIRBRUSH_ID = "builtin.airbrush"
    const val SPRAY_CAN_ID = "builtin.spray_can"
    const val MARKER_ID = "builtin.marker"
    const val CHARCOAL_ID = "builtin.charcoal"
    const val SOFT_PASTEL_ID = "builtin.soft_pastel"
    const val TECHNICAL_PEN_ID = "builtin.technical_pen"
    const val CALLIGRAPHY_ID = "builtin.calligraphy"
    const val DRY_BRUSH_ID = "builtin.dry_brush"
    const val OIL_PAINT_ID = "builtin.oil_paint"
    const val PIGMENT_WASH_ID = "builtin.pigment_wash"
    const val HARD_ERASER_ID = "builtin.hard_eraser"
    const val SOFT_ERASER_ID = "builtin.soft_eraser"
    const val HARD_ERASER_NAME = "@string/preset_hard_eraser"

    internal val CORE_PAINT_IDS: List<String> = listOf(
        PENCIL_ID,
        INK_PEN_ID,
        PAINTBRUSH_ID,
        AIRBRUSH_ID,
        MARKER_ID,
    )

    val RAIL_ORDER: List<String> = CORE_PAINT_IDS + listOf(
        SPRAY_CAN_ID,
        CHARCOAL_ID,
        SOFT_PASTEL_ID,
        TECHNICAL_PEN_ID,
        CALLIGRAPHY_ID,
        DRY_BRUSH_ID,
        OIL_PAINT_ID,
        PIGMENT_WASH_ID,
        HARD_ERASER_ID,
        SOFT_ERASER_ID,
    )

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
        id = INK_PEN_ID,
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

    /** Built-ins follow the product rail; user presets follow by id. */
    fun railOrder(presets: List<BrushPreset>): List<BrushPreset> {
        val rank = RAIL_ORDER.withIndex().associate { (index, id) -> id to index }
        return presets.sortedWith(compareBy({ rank[it.id] ?: Int.MAX_VALUE }, { it.id }))
    }
}
