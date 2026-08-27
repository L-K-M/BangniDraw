package ch.lkmc.bangnidraw.data

import android.content.res.AssetManager
import java.io.IOException

internal const val BRUSH_PRESET_DIRECTORY = "brushes"

/** The Android asset boundary behind the JVM-testable [BrushPresetStore]. */
internal interface BrushPresetAssets {

    @Throws(IOException::class)
    fun names(): List<String>

    @Throws(IOException::class)
    fun read(name: String): String
}

internal class AndroidBrushPresetAssets(
    private val assets: AssetManager,
) : BrushPresetAssets {

    override fun names(): List<String> =
        assets.list(DIRECTORY).orEmpty().filter { it.endsWith(JSON_SUFFIX) }.sorted()

    override fun read(name: String): String =
        assets.open("$DIRECTORY/$name").bufferedReader().use { it.readText() }

    private companion object {
        const val DIRECTORY = BRUSH_PRESET_DIRECTORY
        const val JSON_SUFFIX = ".json"
    }
}
