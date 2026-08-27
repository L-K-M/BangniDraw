package ch.lkmc.bangnidraw.tools

import ch.lkmc.bangnidraw.engine.core.BrushPreset

class EraserTool(preset: BrushPreset) : BrushTool(requireEraser(preset))

private fun requireEraser(preset: BrushPreset): BrushPreset {
    require(preset.eraseMode) { "eraser tool requires an erase-mode preset" }
    return preset
}
