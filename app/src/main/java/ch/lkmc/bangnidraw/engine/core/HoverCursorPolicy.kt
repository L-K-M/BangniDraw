package ch.lkmc.bangnidraw.engine.core

internal enum class HoverRing {
    Solid,
    Dashed,
    None,
}

internal data class HoverCursorSpec(
    val diameterPx: Float,
    val ring: HoverRing,
    val crosshair: Boolean,
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
            return HoverCursorSpec(0f, HoverRing.None, crosshair = false)
        }

        val preset = if (pointer == PointerTool.ERASER) {
            eraserPreset
        } else {
            (active as? ToolKind.Brush)?.preset ?: return null
        }
        val diameter = (preset.size * canvasToScreenScale).coerceAtLeast(0f)
        val ring = if (preset.eraseMode || pointer == PointerTool.ERASER) {
            HoverRing.Dashed
        } else {
            HoverRing.Solid
        }
        return HoverCursorSpec(
            diameterPx = diameter,
            ring = ring,
            crosshair = diameter < CROSSHAIR_THRESHOLD_PX,
        )
    }

    private const val CROSSHAIR_THRESHOLD_PX = 6f
}
