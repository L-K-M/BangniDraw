package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.LayerNameResolver

@Composable
internal fun layerName(stored: String): String {
    val defaultName = stringResource(R.string.layer_default_display)
    val flattenedName = stringResource(R.string.layer_flattened)
    val copySuffix = stringResource(R.string.layer_copy_suffix)

    return LayerNameResolver.resolve(
        stored = stored,
        defaultName = { "$defaultName $it" },
        flattenedName = flattenedName,
        copySuffix = copySuffix,
    )
}

@Composable
internal fun blendModeName(mode: BlendMode): String = stringResource(
    when (mode) {
        BlendMode.NORMAL -> R.string.blend_normal
        BlendMode.MULTIPLY -> R.string.blend_multiply
        BlendMode.SCREEN -> R.string.blend_screen
        BlendMode.OVERLAY -> R.string.blend_overlay
        BlendMode.DARKEN -> R.string.blend_darken
        BlendMode.LIGHTEN -> R.string.blend_lighten
        BlendMode.ADD -> R.string.blend_add
        BlendMode.DIFFERENCE -> R.string.blend_difference
    },
)
