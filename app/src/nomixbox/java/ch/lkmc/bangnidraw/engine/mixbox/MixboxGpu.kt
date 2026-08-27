package ch.lkmc.bangnidraw.engine.mixbox

import android.content.res.AssetManager

/** Stripped builds assemble no pigment shaders. */
object MixboxShaderSource {
    fun load(@Suppress("UNUSED_PARAMETER") assets: AssetManager): String = ""
}

/** Stripped builds allocate no licensed LUT. */
object MixboxLut {
    fun upload(@Suppress("UNUSED_PARAMETER") assets: AssetManager): Int = 0
}
