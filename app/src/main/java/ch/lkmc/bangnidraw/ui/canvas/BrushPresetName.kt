package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets

/** Resolves shipped resource keys without turning resource names into runtime lookups. */
@Composable
internal fun brushPresetName(preset: BrushPreset): String = when (preset.id) {
    BrushPresets.PENCIL_ID -> stringResource(R.string.preset_pencil)
    BrushPresets.INK_PEN_ID -> stringResource(R.string.preset_ink_pen)
    BrushPresets.PAINTBRUSH_ID -> stringResource(R.string.preset_paintbrush)
    BrushPresets.HEAVY_PAINT_ID -> stringResource(R.string.preset_heavy_paint)
    BrushPresets.AIRBRUSH_ID -> stringResource(R.string.preset_airbrush)
    BrushPresets.MARKER_ID -> stringResource(R.string.preset_marker)
    BrushPresets.HARD_ERASER_ID -> stringResource(R.string.preset_hard_eraser)
    BrushPresets.SOFT_ERASER_ID -> stringResource(R.string.preset_soft_eraser)
    else -> preset.name
}
