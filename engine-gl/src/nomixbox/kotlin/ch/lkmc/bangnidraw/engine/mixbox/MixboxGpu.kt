package ch.lkmc.bangnidraw.engine.mixbox

import ch.lkmc.bangnidraw.engine.gl.platform.EngineAssets

/** Stripped builds assemble no pigment shaders. */
object MixboxShaderSource {
    fun load(@Suppress("UNUSED_PARAMETER") assets: EngineAssets): String = ""
}

/** Stripped builds allocate no licensed LUT. */
object MixboxLut {
    fun upload(@Suppress("UNUSED_PARAMETER") assets: EngineAssets): Int = 0
}
