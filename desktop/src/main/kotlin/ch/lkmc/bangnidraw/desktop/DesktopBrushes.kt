package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import kotlinx.serialization.json.Json

/**
 * The shipped brush presets for the desktop shell, read from the single
 * copy under `app/src/main/assets/brushes` that ships as desktop classpath
 * resources (`brushes` JSON files). The Android app reads the same files as
 * assets; the engine-core tests pin their shipped values.
 */
internal object DesktopBrushes {

    fun loadAll(): List<BrushPreset> {
        val json = Json { ignoreUnknownKeys = true }
        // Asset files are named by the id stem ("builtin.pencil" → "pencil.json").
        val names = BRUSHES_INDEX.map { "$BRUSH_DIR/${it.removePrefix("builtin.")}.json" }
        val presets = names.mapNotNull { path ->
            val text = javaClass.getResourceAsStream("/$path")?.bufferedReader()?.use { it.readText() }
            if (text == null) {
                System.err.println("missing brush resource: $path")
                null
            } else {
                try {
                    json.decodeFromString<BrushPreset>(text)
                } catch (e: kotlinx.serialization.SerializationException) {
                    error("malformed brush resource: $path (${e.message})")
                }
            }
        }
        check(presets.size == names.size) {
            "missing brush resources on the classpath (${presets.size} of ${names.size} loaded)"
        }
        return presets
    }

    /** The shared rail order — one source with the Android app. */
    private val BRUSHES_INDEX = BrushPresets.RAIL_ORDER

    private const val BRUSH_DIR = "brushes"
}
