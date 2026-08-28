package ch.lkmc.bangnidraw.engine.core

import kotlin.math.ceil

internal enum class HoverRing {
    Solid,
    Dashed,
    None,
}

internal data class HoverCursorSpec(
    val diameterPx: Float,
    val ring: HoverRing,
    val crosshair: Boolean,
    /** True when the tool lays down the current brush colour, so the ring can show it. */
    val ink: Boolean,
)

/** Pure cursor choice; Compose only draws the returned description. */
internal object HoverCursorPolicy {

    fun resolve(
        pointer: PointerTool,
        active: ToolKind,
        eraserPreset: BrushPreset,
        canvasToScreenScale: Float,
    ): HoverCursorSpec? {
        if (pointer == PointerTool.FINGER) return null
        if (pointer != PointerTool.ERASER && active is ToolKind.Eyedropper) {
            return HoverCursorSpec(0f, HoverRing.None, crosshair = false, ink = false)
        }

        if (pointer != PointerTool.ERASER && active is ToolKind.Water) {
            // The ring previews the wet bloom, not just the tip: the flow mask
            // reaches radius + spread, mirroring WatercolorDabBounds.set.
            val params = active.params
            val radius = params.size * 0.5f
            val spreadPx = ceil(
                radius * params.spread * WatercolorDabPlan.SPREAD_RADIUS_FRACTION,
            ).toInt().coerceAtMost(WatercolorDabPlan.MAX_SPREAD_PX)
            val diameter = ((params.size + 2f * spreadPx) * canvasToScreenScale).coerceAtLeast(0f)
            return HoverCursorSpec(
                diameterPx = diameter,
                ring = HoverRing.Solid,
                crosshair = diameter < CROSSHAIR_THRESHOLD_PX,
                ink = false,
            )
        }

        val preset = if (pointer == PointerTool.ERASER) {
            eraserPreset
        } else {
            (active as? ToolKind.Brush)?.preset ?: return null
        }
        val erasing = preset.eraseMode || pointer == PointerTool.ERASER
        val diameter = (preset.size * canvasToScreenScale).coerceAtLeast(0f)
        val ring = if (erasing) HoverRing.Dashed else HoverRing.Solid
        return HoverCursorSpec(
            diameterPx = diameter,
            ring = ring,
            crosshair = diameter < CROSSHAIR_THRESHOLD_PX,
            ink = !erasing,
        )
    }

    private const val CROSSHAIR_THRESHOLD_PX = 6f
}
